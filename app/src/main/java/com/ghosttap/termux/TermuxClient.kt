package com.ghosttap.termux

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import com.ghosttap.core.IntelEvent
import com.ghosttap.core.IntelEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Termux cross-app layer (Intent integration).
 *
 * Verified against Termux's own docs/source (2026-11-15):
 * - RUN_COMMAND is a SERVICE call, not a broadcast: action
 *   "com.termux.RUN_COMMAND", explicit component
 *   com.termux/com.termux.app.RunCommandService, started with
 *   startService from a foreground context. (Termux:Tasker README +
 *   RunCommandService.java; Total Commander forum thread.)
 * - Extras: com.termux.RUN_COMMAND_PATH (String, the script to run),
 *   ..._ARGUMENTS (String[]), ..._WORKDIR (String),
 *   ..._BACKGROUND (boolean), ..._SESSION_ACTION (String "0" in the
 *   adb-documented usage — the Java example passes int 0; the String
 *   form is used here and flagged NEEDS-ON-DEVICE-TEST),
 *   ..._STDIN (String), ..._DESCRIPTION (String),
 *   ..._PENDING_INTENT (Parcelable PendingIntent, fired with the
 *   result), ..._RESULT_DIRECTORY / ..._RESULT_SINGLE_FILE (file
 *   results). Exact result-bundle key strings were NOT verified from
 *   official docs, so results are read by scanning the returned
 *   extras/bundle for keys containing "stdout", "stderr" and "exit".
 * - Permission: com.termux.permission.RUN_COMMAND, a dangerous
 *   permission defined by Termux. The caller declares it and the user
 *   grants it in Settings -> Apps -> Ghost Tap -> Permissions ->
 *   Additional permissions -> "Run commands in Termux environment".
 * - Termux:Tasker (com.termux.tasker) is NOT required: direct
 *   RUN_COMMAND to com.termux works with Termux alone. Tasker is a
 *   separate plugin host. Scripts in ~/.termux/tasker/ run without
 *   anything else; scripts elsewhere need allow-external-apps=true.
 *
 * Safety: this class runs the built-in helper scripts, scripts the
 * user added in Termux (found by the library lister), and — through
 * the runner script — any command the user typed or the intelligence
 * composed. That is the design: Termux is a terminal, and Ghost Tap
 * passes the command to it exactly as given. Nothing here invents a
 * command on its own; every run starts from the user or the
 * intelligence, and every answer comes back through the bridge or
 * the PendingIntent.
 *
 * Ghost Tap works fully with Termux absent, stopped, crashed or
 * denied: every call here returns a plain result instead of throwing.
 */
class TermuxClient(private val context: Context) {

    companion object {
        /** Termux itself. */
        const val PKG = "com.termux"

        /** Termux:Tasker plugin — optional, never required for RUN_COMMAND. */
        const val TASKER_PKG = "com.termux.tasker"

        /** The service that runs commands. Verified: a service, not a receiver. */
        const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"

        /** RUN_COMMAND intent action. */
        const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"

        /** Dangerous permission defined by Termux; the user grants it. */
        const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"

        // RUN_COMMAND extras (verified names from Termux source/docs).
        const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
        const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
        const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
        const val EXTRA_DESCRIPTION = "com.termux.RUN_COMMAND_DESCRIPTION"
        const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
        const val EXTRA_RESULT_DIRECTORY = "com.termux.RUN_COMMAND_RESULT_DIRECTORY"
        const val EXTRA_RESULT_SINGLE_FILE = "com.termux.RUN_COMMAND_RESULT_SINGLE_FILE"

        /** Termux home, for the allowlisted script paths. */
        const val TERMUX_HOME = "/data/data/com.termux/files/home"
        const val TASKER_DIR = "/data/data/com.termux/files/home/.termux/tasker"

        /** Action for our own result receiver (never exported). */
        const val ACTION_RUN_RESULT = "com.ghosttap.action.TERMUX_RUN_RESULT"

        /** Default wait for Termux to answer a run. */
        const val DEFAULT_RESULT_TIMEOUT_MS = 60_000L
    }

    /** What came back from one run. Never throws; a missing answer is data, not a crash. */
    data class RunOutcome(
        val runId: String,
        val ok: Boolean,
        val stdout: String = "",
        val stderr: String = "",
        val exitCode: Int? = null,
        /** Plain-words explanation when ok is false. */
        val problem: String = ""
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nextRequestCode = AtomicInteger(1)
    private val pending = mutableMapOf<Int, (RunOutcome) -> Unit>()
    private val pendingLock = Any()
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != ACTION_RUN_RESULT) return
            val requestCode = intent.getIntExtra("ghosttap_request_code", -1)
            val runId = intent.getStringExtra("ghosttap_run_id") ?: return
            val outcome = parseRunResult(runId, intent)
            val callback: ((RunOutcome) -> Unit)?
            synchronized(pendingLock) { callback = pending.remove(requestCode) }
            callback?.invoke(outcome)
        }
    }

    private fun ensureReceiver() {
        if (receiverRegistered) return
        try {
            val filter = IntentFilter(ACTION_RUN_RESULT)
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            receiverRegistered = true
        } catch (_: Exception) {
            // Receiver registration failed — runs still go out, results
            // just come back through the bridge instead. Never fatal.
        }
    }

    /** True if Termux is installed (package visible via the manifest queries entry). */
    fun isInstalled(): Boolean = isPackageInstalled(PKG)

    /** True if the optional Termux:Tasker plugin is installed. Never required. */
    fun isTaskerInstalled(): Boolean = isPackageInstalled(TASKER_PKG)

    private fun isPackageInstalled(pkg: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** True if the user has granted Ghost Tap the RUN_COMMAND permission. */
    fun hasRunCommandPermission(): Boolean {
        return try {
            context.checkSelfPermission(PERMISSION_RUN_COMMAND) ==
                PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    /** Opens the Termux app itself — this IS the terminal access. Returns false when it can't. */
    fun launchTermux(): Boolean {
        if (!isInstalled()) return false
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(PKG) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Runs one script in Termux and waits for its answer.
     *
     * [script] must be runnable — a built-in, or one of the user's
     * own scripts found by the library lister (see
     * [TermuxRegistry.isRunnable]). Anything else is refused before
     * anything is sent. [args] are the script's own structured
     * arguments, already validated by [TermuxRegistry.validateArgs].
     * For a command the user typed, the script is the runner
     * (ghosttap-run.sh) and the single argument is the command text,
     * passed exactly as typed — pipes, redirects and all.
     * When [bridgePort] and [bridgeToken] are given, they are passed
     * to the script as its last two arguments so the script can send
     * its answer back to Ghost Tap's localhost bridge; the token is
     * handed over in-memory only, never written to a file and never
     * logged. The PendingIntent callback is the second answer path —
     * whichever answers first wins.
     *
     * Returns the run id. The [onResult] callback fires at most once:
     * with the real answer, or with ok=false when Termux never answers
     * within [timeoutMs].
     */
    fun runApprovedScript(
        script: ApprovedScript,
        args: List<String>,
        bridgePort: Int? = null,
        bridgeToken: String? = null,
        timeoutMs: Long = DEFAULT_RESULT_TIMEOUT_MS,
        runId: String = "termux-${System.currentTimeMillis()}-${nextRequestCode.get()}",
        onResult: (RunOutcome) -> Unit
    ): String {
        if (!TermuxRegistry.isRunnable(script)) {
            onResult(
                RunOutcome(runId, false, problem = "That script isn't one Ghost Tap knows — check what scripts are there first.")
            )
            return runId
        }
        if (!isInstalled()) {
            onResult(RunOutcome(runId, false, problem = "Termux is not on this phone."))
            return runId
        }
        if (!hasRunCommandPermission()) {
            onResult(
                RunOutcome(
                    runId, false,
                    problem = "Termux hasn't been told to let Ghost Tap run things yet. " +
                        "Open Settings, find Ghost Tap, and allow \"Run commands in Termux environment\"."
                )
            )
            return runId
        }
        ensureReceiver()
        val requestCode = nextRequestCode.getAndIncrement()
        val resultIntent = Intent(ACTION_RUN_RESULT).apply {
            setPackage(context.packageName)
            putExtra("ghosttap_request_code", requestCode)
            putExtra("ghosttap_run_id", runId)
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val resultPending = try {
            PendingIntent.getBroadcast(context, requestCode, resultIntent, pendingFlags)
        } catch (_: Exception) {
            null
        }
        synchronized(pendingLock) { pending[requestCode] = onResult }

        // Watchdog: Termux never answering must end the run honestly.
        scope.launch {
            delay(timeoutMs)
            val callback: ((RunOutcome) -> Unit)?
            synchronized(pendingLock) { callback = pending.remove(requestCode) }
            callback?.invoke(
                RunOutcome(
                    runId, false,
                    problem = "Termux didn't answer in time. It may be stopped or busy — open Termux and try again."
                )
            )
        }

        val allArgs = buildList {
            addAll(args)
            if (bridgePort != null && bridgeToken != null) {
                add(bridgePort.toString())
                add(bridgeToken)
            }
        }
        val intent = Intent(ACTION_RUN_COMMAND).apply {
            // Explicit component: the RUN_COMMAND service. NOT a
            // broadcast — Termux only runs commands through this service.
            component = ComponentName(PKG, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_PATH, script.termuxPath)
            putExtra(EXTRA_ARGUMENTS, allArgs.toTypedArray())
            putExtra(EXTRA_WORKDIR, TERMUX_HOME)
            putExtra(EXTRA_BACKGROUND, true)
            // "0" = run it and stay in the background (String form, as
            // documented in Termux's adb examples). NEEDS-ON-DEVICE-TEST:
            // some examples pass int 0 instead.
            putExtra(EXTRA_SESSION_ACTION, "0")
            putExtra(EXTRA_DESCRIPTION, script.title)
            if (resultPending != null) putExtra(EXTRA_PENDING_INTENT, resultPending)
        }
        try {
            context.startService(intent)
            pubTermux("termux.run", true, "Sent \"${script.title}\" to Termux.")
        } catch (e: IllegalStateException) {
            // Background start refused (Android 12+): one foreground-service try.
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                    pubTermux("termux.run", true, "Sent \"${script.title}\" to Termux.")
                } else {
                    throw e
                }
            } catch (_: Exception) {
                finishPending(requestCode, runId, onResult, "Couldn't start Termux — open Termux once, then try again.")
            }
        } catch (e: SecurityException) {
            finishPending(
                requestCode, runId, onResult,
                "Termux said no — Ghost Tap doesn't have the run-commands permission. " +
                    "Allow it in Settings, then try again."
            )
        } catch (_: Exception) {
            finishPending(requestCode, runId, onResult, "Couldn't reach Termux. Check it is installed and try again.")
        }
        return runId
    }

    private fun finishPending(
        requestCode: Int,
        runId: String,
        onResult: (RunOutcome) -> Unit,
        problem: String
    ) {
        val callback: ((RunOutcome) -> Unit)?
        synchronized(pendingLock) { callback = pending.remove(requestCode) }
        (callback ?: onResult).invoke(RunOutcome(runId, false, problem = problem))
    }

    /**
     * Reads a RUN_COMMAND result out of the PendingIntent's intent.
     * The exact result-bundle key strings were NOT verified against
     * official docs, so this scans the intent's extras and any Bundle
     * inside them for keys mentioning stdout / stderr / exit code,
     * instead of trusting one guessed key name.
     */
    fun parseRunResult(runId: String, intent: Intent): RunOutcome {
        var stdout = ""
        var stderr = ""
        var exitCode: Int? = null
        fun scan(bundle: android.os.Bundle) {
            for (key in bundle.keySet()) {
                val lower = key.lowercase()
                try {
                    when {
                        "stdout" in lower -> stdout = bundle.get(key)?.toString() ?: stdout
                        "stderr" in lower -> stderr = bundle.get(key)?.toString() ?: stderr
                        "exit" in lower && "code" in lower -> {
                            val v = bundle.get(key)
                            exitCode = when (v) {
                                is Int -> v
                                is Number -> v.toInt()
                                is String -> v.toIntOrNull()
                                else -> exitCode
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
        try {
            intent.extras?.let { extras ->
                scan(extras)
                for (key in extras.keySet()) {
                    try {
                        (extras.get(key) as? android.os.Bundle)?.let { scan(it) }
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        val ok = exitCode == null || exitCode == 0
        return RunOutcome(
            runId = runId,
            ok = ok,
            stdout = stdout,
            stderr = stderr,
            exitCode = exitCode,
            problem = if (ok) "" else "Termux said the script finished with a problem" +
                (exitCode?.let { " (code $it)" } ?: "") + "."
        )
    }

    /** Event bus: Termux results flow to the intelligence timeline. */
    private fun pubTermux(id: String, ok: Boolean, summary: String) {
        try {
            IntelEventBus.publish(IntelEvent.ToolOpResult(id, ok, summary.take(1200)))
        } catch (_: Exception) {
        }
    }

    fun close() {
        if (receiverRegistered) {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
    }
}
