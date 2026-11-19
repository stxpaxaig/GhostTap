package com.ghosttap

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ghosttap.core.IntelEvent
import com.ghosttap.core.IntelEventBus
import com.ghosttap.core.OpResult
import com.ghosttap.core.ops.OperationContext
import com.ghosttap.core.ops.OperationRegistry
import com.ghosttap.data.LibraryEntry
import com.ghosttap.data.TermuxHistoryStore
import com.ghosttap.data.TermuxSettings
import com.ghosttap.termux.AskReply
import com.ghosttap.termux.AskRequest
import com.ghosttap.termux.ApprovedScript
import com.ghosttap.termux.TermuxBridge
import com.ghosttap.termux.askRefusalFor
import com.ghosttap.termux.isAskableScriptId
import com.ghosttap.termux.TermuxCapability
import com.ghosttap.termux.TermuxCapabilityState
import com.ghosttap.termux.TermuxClient
import com.ghosttap.termux.TermuxExecutionManager
import com.ghosttap.termux.TermuxExchange
import com.ghosttap.termux.TermuxRegistry
import com.ghosttap.termux.TermuxRun
import com.ghosttap.termux.TermuxRunState
import com.ghosttap.termux.ScriptOrigin
import com.ghosttap.termux.parseLibraryJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * ViewModel for the TERMUX INTEGRATION screen.
 *
 * Same shape as the other four ViewModels (AndroidViewModel, I/O in
 * viewModelScope.launch(Dispatchers.IO), state writes on Main, cleanup
 * in onCleared). Owns the Termux client, the localhost bridge, the
 * file exchange, the capability list, the execution manager and the
 * history store.
 *
 * Survival rule: Termux absent, stopped, crashed or denied must NEVER
 * crash Ghost Tap. Every Termux call here is wrapped so a failure
 * becomes a plain-words status line, never an exception to the UI.
 * Nothing here touches NFC, networking, hotspot, VirtualESP32 or USB.
 */
class TermuxViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val client = TermuxClient(appContext)
    private val exchange = TermuxExchange(appContext)
    private val historyStore = TermuxHistoryStore(appContext)
    private val execution = TermuxExecutionManager()

    private val bridge = TermuxBridge(
        onMessage = { message -> onBridgeMessage(message) },
        onAsk = { request -> handleAsk(request) }
    )

    // ----- Status (plain words, shown on the screen) -----
    var termuxInstalled by mutableStateOf(false)
        private set
    var taskerInstalled by mutableStateOf(false)
        private set
    var hasRunPermission by mutableStateOf(false)
        private set
    /**
     * v3.2 (Piece 4) — true once [checkInstalledNow] has actually
     * run. Before that, `termuxInstalled`/`hasRunPermission` are
     * just defaults (false) — the home status line says "checking…"
     * until this is true instead of flashing "not installed" for a
     * Termux that is really there.
     */
    var termuxStateKnown by mutableStateOf(false)
        private set
    var bridgeRunning by mutableStateOf(false)
        private set
    var bridgePortText by mutableStateOf("")
        private set
    var statusLine by mutableStateOf("Checking Termux…")
        private set
    var capabilities by mutableStateOf<List<TermuxCapability>>(TermuxRegistry.defaultCapabilities())
        private set
    var runs by mutableStateOf<List<TermuxRun>>(emptyList())
        private set
    /** Scripts the user added in Termux, found by the library lister. Never a built-in. */
    var libraryScripts by mutableStateOf<List<ApprovedScript>>(emptyList())
        private set
    var selectedRun by mutableStateOf<TermuxRun?>(null)
        private set
    var selectedScriptId by mutableStateOf<String?>(null)
        private set

    // ----- Settings (safe, optional toggles — everything on by default) -----
    var useTermuxInPlans by mutableStateOf(true)
        private set
    var keepHistory by mutableStateOf(true)
        private set

    private var settings = TermuxSettings()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            settings = runCatching { historyStore.loadSettings() }.getOrDefault(TermuxSettings())
            val saved = runCatching { historyStore.loadHistory() }.getOrDefault(emptyList())
            execution.restore(saved)
            val savedLibrary = runCatching { historyStore.loadLibrary() }.getOrDefault(emptyList())
            val savedProbe = runCatching { historyStore.loadProbe() }.getOrNull()
            withContext(Dispatchers.Main) {
                useTermuxInPlans = settings.useTermuxInPlans
                keepHistory = settings.keepHistory
                runs = execution.history()
                libraryScripts = savedLibrary.map { libraryScriptFromEntry(it) }
                // Restore the last probe answer so the "What Termux
                // can do" list is filled in from the last real check
                // instead of falling back to "not checked yet" after
                // the app restarts (v3 fix). Only when nothing newer
                // has answered yet — a fresh probe result always wins
                // over the saved one.
                val probeJson = savedProbe?.toolsJson.orEmpty()
                if (probeJson.isNotBlank() &&
                    capabilities.all { it.state == TermuxCapabilityState.UNKNOWN }
                ) {
                    val enabled = settings.capabilityEnabled
                    capabilities = TermuxRegistry.applyProbeResult(
                        TermuxRegistry.defaultCapabilities(), probeJson
                    ).map { it.copy(enabled = enabled[it.id] ?: true) }
                }
            }
        }
    }

    // ----- Zero-config: setup state + auto-discover (v3) -----

    /**
     * True when nothing is left to set up: Termux is there, Ghost Tap
     * is allowed to run things in it, and a real probe has answered
     * (at least one capability YES or NO — a probe that ran and found
     * nothing is still a real answer). A probe can only complete when
     * the helper scripts are in Termux, so a real probe answer is
     * honest proof the scripts are there too. The screen shows one
     * "Termux is ready" line instead of the setup steps when this is
     * true.
     */
    val setupComplete: Boolean
        get() = termuxInstalled && hasRunPermission &&
            capabilities.any {
                it.state == TermuxCapabilityState.YES || it.state == TermuxCapabilityState.NO
            }

    /**
     * The ONE thing still missing, in plain words. Blank when setup
     * is complete. Checked in order, so the screen never lists five
     * steps — it names the next one only.
     */
    val setupMissingLine: String
        get() = when {
            !termuxInstalled -> "Termux isn't on this phone yet — install it first."
            !hasRunPermission -> "Termux hasn't been told to let Ghost Tap run things yet."
            capabilities.none {
                it.state == TermuxCapabilityState.YES || it.state == TermuxCapabilityState.NO
            } -> "The helper scripts aren't in Termux yet, or the check hasn't run."
            else -> ""
        }

    private var autoDiscovered = false

    /**
     * Runs the discovery chain once per session when the Termux screen
     * opens (install check -> link -> probe), so the screen connects
     * and fills itself in with no tap. Later opens in the same session
     * do nothing — "Check again" re-runs it by hand any time. A
     * missing Termux or a missing permission stops the chain honestly
     * with the reason in the status line.
     */
    fun autoDiscoverOnce() {
        if (autoDiscovered) return
        autoDiscovered = true
        discoverTermux()
    }

    /**
     * The one "Set it up" action for whatever is missing right now.
     * Each branch does the best thing Ghost Tap can actually do from
     * inside its own app — it cannot install Termux, cannot turn the
     * permission on itself, and cannot write Termux's files, so the
     * button takes Justin to the exact place (or copies the exact
     * paste) instead of pretending the step is gone.
     */
    fun setupAction() {
        when {
            !termuxInstalled -> openTermuxInstallPage()
            !hasRunPermission -> openAppSettings()
            else -> copySetupPasteAndOpenTermux()
        }
    }

    /** Opens the Termux page in F-Droid so it can be installed. */
    private fun openTermuxInstallPage() {
        val ok = runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            true
        }.getOrDefault(false)
        if (!ok) setStatus("Couldn't open the install page — install Termux from F-Droid.")
    }

    /**
     * Opens Ghost Tap's own page in Settings, where Permissions lives.
     * The permission itself ("Run commands in Termux environment") is
     * turned on by hand there — Android does not let Ghost Tap turn on
     * another app's permission for itself.
     */
    private fun openAppSettings() {
        val ok = runCatching {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", appContext.packageName, null)
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            true
        }.getOrDefault(false)
        if (!ok) {
            setStatus(
                "Couldn't open Settings — open Settings, find Ghost Tap, and allow " +
                    "\"Run commands in Termux environment\"."
            )
        }
    }

    /**
     * Copies the whole setup paste (the same text as the setup sheet's
     * one-paste file — all 10 helper files, the runnable bit, and the
     * allow-external-apps line) and opens Termux, so the only job left
     * is one paste and Enter. Ghost Tap cannot write Termux's files
     * itself — that is an Android limit, not a missing feature — so
     * this is the closest to zero-config that step can get.
     *
     * UNIFIED BUILD (2026-11-18) — public now, and returns the outcome
     * line: the command box's status line calls this, and the outcome
     * shows under the box so a failed copy is said plainly.
     */
    fun copySetupPasteAndOpenTermux(): String {
        val text = loadSetupPasteText()
        if (text.isNullOrBlank()) {
            val outcome = "Couldn't get the setup paste ready — use the setup sheet's one-paste file instead."
            setStatus(outcome)
            return outcome
        }
        val copied = runCatching {
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Ghost Tap Termux setup", text))
            true
        }.getOrDefault(false)
        val opened = openTermux()
        val outcome = when {
            copied && opened -> "Setup copied — paste it into Termux, press Enter, then come back here."
            copied -> "Setup copied, but Termux wouldn't open — open Termux and paste it there."
            else -> "Couldn't copy the setup — use the setup sheet's one-paste file instead."
        }
        setStatus(outcome)
        return outcome
    }

    /** The bundled one-paste setup text, byte-identical to the setup sheet's one-paste file. */
    private fun loadSetupPasteText(): String? {
        return runCatching {
            appContext.assets.open("termux-setup-one-paste.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
    }

    // ----- Discovery: "Investigate the unfamiliar" for Termux -----

    /**
     * One tap checks everything: is Termux there, can Ghost Tap run
     * things in it, is the link up, and what can Termux do. Each step
     * reports honestly; a failed step stops the chain with a plain
     * reason instead of pretending the later steps ran.
     */
    fun discoverTermux() {
        viewModelScope.launch(Dispatchers.IO) {
            val check = checkInstalledNow()
            if (!check.ok) {
                setStatus(check.summary)
                return@launch
            }
            val port = ensureBridge()
            if (port < 0) {
                setStatus("Termux is there, but Ghost Tap couldn't start its own link. Close other apps and tap \"Check again\".")
                return@launch
            }
            if (!hasRunPermission) {
                setStatus(
                    "Termux is there, and the link is up — but Termux hasn't been told to let Ghost Tap run things yet. " +
                        "Open Settings, find Ghost Tap, and allow \"Run commands in Termux environment\", then tap \"Check again\"."
                )
                return@launch
            }
            setStatus("Termux is there and the link is up. Checking what Termux can do…")
            runProbe()
        }
    }

    /**
     * Checks install + permission state synchronously (on IO) and
     * updates the screen state. The operations layer uses this so a
     * check can be a real step with a real outcome. ok=false means
     * "don't go further" — the summary says why, in plain words.
     */
    suspend fun checkInstalledNow(): OpResult {
        val installed = runCatching { client.isInstalled() }.getOrDefault(false)
        val tasker = runCatching { client.isTaskerInstalled() }.getOrDefault(false)
        val permission = runCatching { client.hasRunCommandPermission() }.getOrDefault(false)
        withContext(Dispatchers.Main) {
            termuxInstalled = installed
            taskerInstalled = tasker
            hasRunPermission = permission
            termuxStateKnown = true
        }
        return when {
            !installed -> OpResult(
                false,
                "Termux is not on this phone. Install it from F-Droid first — the home screen says what's missing."
            )
            !permission -> OpResult(
                false,
                "Termux is there, but it hasn't been told to let Ghost Tap run things yet. " +
                    "Open Settings, find Ghost Tap, and allow \"Run commands in Termux environment\"."
            )
            else -> OpResult(
                true,
                "Termux is there and Ghost Tap is allowed to run things in it." +
                    if (tasker) " The Termux:Tasker helper app is there too (not needed)." else ""
            )
        }
    }

    /** Starts the bridge if needed and pings it. True only on a real pong. */
    suspend fun bridgeHealthCheck(): OpResult = withContext(Dispatchers.IO) {
        val port = ensureBridge()
        if (port < 0) {
            return@withContext OpResult(
                false,
                "Ghost Tap couldn't start its own link. Close other apps and try again."
            )
        }
        val token = bridge.token
            ?: return@withContext OpResult(false, "The link started but has no key — try again.")
        val ok = runCatching { bridge.ping(port, token) }.getOrDefault(false)
        OpResult(
            ok,
            if (ok) "The link is up and answered — Ghost Tap is listening on this phone only."
            else "The link started but didn't answer. Try again."
        )
    }

    /** Same as discoverTermux — the screen's "Check again" button. */
    fun checkAgain() = discoverTermux()

    /** Opens the Termux app itself — this IS the terminal. False when it can't. */
    fun openTermux(): Boolean {
        val ok = runCatching { client.launchTermux() }.getOrDefault(false)
        if (!ok) setStatus("Couldn't open Termux — it may not be on this phone.")
        return ok
    }

    // ----- Running approved scripts -----

    /** Scripts the user can pick, in plain words: the helpers that came with Ghost Tap, then the user's own. */
    fun userScripts(): List<ApprovedScript> = TermuxRegistry.userScripts + libraryScripts

    /** Commands the user typed before, most recent first — offered as one-tap picks. */
    fun recentFreeCommands(): List<String> {
        return execution.history()
            .filter { it.scriptId == TermuxRegistry.FREE_COMMAND_SCRIPT_ID }
            .map { it.scriptTitle }
            .distinct()
            .take(5)
    }

    /**
     * Asks Termux what scripts the user put there. Runs the library
     * lister; the answer comes back on the bridge and lands in
     * [libraryScripts]. Nothing here reads Termux's files — Termux
     * lists them itself and sends the list.
     */
    fun refreshLibrary() {
        setStatus("Checking what scripts are in Termux…")
        runApprovedScript(TermuxRegistry.LIST_LIBRARY_SCRIPT_ID)
    }

    fun selectScript(id: String?) {
        selectedScriptId = id
    }

    fun selectRun(run: TermuxRun?) {
        selectedRun = run
    }

    /**
     * Runs one script — a built-in helper or one of the user's own
     * scripts found in Termux. Fire-and-forget for the screen: the
     * run shows in the list, and [onFinished] fires when Termux
     * answers (or doesn't). Returns the run id, or null when the run
     * could not even start (the reason is in [statusLine]).
     */
    fun runApprovedScript(
        scriptId: String,
        args: Map<String, String> = emptyMap(),
        timeoutMs: Long = settings.defaultTimeoutMs,
        onFinished: ((TermuxRun) -> Unit)? = null
    ): String? {
        val script = TermuxRegistry.scriptById(scriptId)
            ?: libraryScripts.firstOrNull { it.id == scriptId }
        if (script == null) {
            setStatus("Ghost Tap doesn't know that script — check what scripts are there first.")
            return null
        }
        val problem = TermuxRegistry.validateArgsProblem(script, args)
        if (problem.isNotBlank()) {
            setStatus(problem)
            return null
        }
        val cleanArgs = TermuxRegistry.validateArgs(script, args) ?: run {
            setStatus(problem.ifBlank { "Those details aren't allowed — check them and try again." })
            return null
        }
        return launchScriptRun(script, cleanArgs, script.title, timeoutMs, onFinished)
    }

    /**
     * Runs a command the user typed (or the intelligence composed),
     * exactly as typed — pipes, redirects and all. It goes through
     * the runner script, so the answer comes back on the bridge like
     * any other run. Fire-and-forget for the screen; [onFinished]
     * fires when Termux answers (or doesn't). Returns the run id, or
     * null when nothing was typed or the run could not start.
     */
    fun runFreeCommand(
        command: String,
        timeoutMs: Long = settings.defaultTimeoutMs,
        onFinished: ((TermuxRun) -> Unit)? = null
    ): String? {
        val text = command.trim()
        if (text.isEmpty()) {
            setStatus("Type a command first.")
            return null
        }
        val script = TermuxRegistry.scriptById(TermuxRegistry.FREE_COMMAND_SCRIPT_ID)
            ?: run {
                setStatus("The command runner isn't set up yet — check the setup steps on the Termux screen.")
                return null
            }
        val args = mapOf("command" to text)
        val problem = TermuxRegistry.validateArgsProblem(script, args)
        if (problem.isNotBlank()) {
            setStatus(problem)
            return null
        }
        val cleanArgs = TermuxRegistry.validateArgs(script, args) ?: run {
            setStatus("That command is too long to send — shorten it and try again.")
            return null
        }
        val displayTitle = if (text.length > 60) text.take(60) + "…" else text
        return launchScriptRun(script, cleanArgs, displayTitle, timeoutMs, onFinished)
    }

    /**
     * Same free command, but waits for the answer — the operations
     * layer uses this. Returns an OpResult in plain words.
     * Cancelling the caller stops the run too.
     */
    suspend fun runFreeCommandAndWait(
        command: String,
        timeoutMs: Long = settings.defaultTimeoutMs
    ): OpResult {
        return withTimeoutOrNull(timeoutMs + 10_000L) {
            suspendCancellableCoroutine { cont ->
                val runId = runFreeCommand(command, timeoutMs) { run ->
                    if (cont.isActive) {
                        cont.resume(
                            OpResult(
                                run.state == TermuxRunState.DONE,
                                run.summary,
                                run.stdout.take(1200)
                            )
                        )
                    }
                }
                if (runId == null) {
                    if (cont.isActive) cont.resume(OpResult(false, statusLine))
                    return@suspendCancellableCoroutine
                }
                cont.invokeOnCancellation { cancelRun(runId) }
            }
        } ?: OpResult(false, "Termux didn't answer in time. Open Termux and try again.")
    }

    /**
     * The shared launch path for a script run and a typed command.
     * [displayTitle] is what the run is called in the history — the
     * script's title, or the command itself for a typed command.
     * The bridge-failure branch records a finished failed run so a
     * waiter is never left hanging on a callback that will never
     * come.
     */
    private fun launchScriptRun(
        script: ApprovedScript,
        cleanArgs: List<String>,
        displayTitle: String,
        timeoutMs: Long,
        onFinished: ((TermuxRun) -> Unit)?
    ): String {
        val runId = "run-" + UUID.randomUUID().toString()
        viewModelScope.launch(Dispatchers.IO) {
            val port = ensureBridge()
            if (port < 0) {
                // The run never started — say so as a finished failed
                // run, so a waiter (runScriptAndWait) is never left
                // hanging on a callback that will never come.
                execution.startRun(runId, script.id, displayTitle, cleanArgs)
                val failed = execution.completeRun(
                    runId, false, "", "", null,
                    "Ghost Tap couldn't start its own link, so nothing was run."
                )
                saveHistory()
                refreshRuns()
                setStatus("Ghost Tap couldn't start its own link, so nothing was run.")
                if (failed != null) onFinished?.invoke(failed)
                return@launch
            }
            execution.startRun(runId, script.id, displayTitle, cleanArgs)
            refreshRuns()
            setStatus("Running \"$displayTitle\"…")
            runCatching {
                client.runApprovedScript(
                    script = script,
                    args = cleanArgs,
                    bridgePort = port,
                    bridgeToken = bridge.token,
                    timeoutMs = timeoutMs,
                    runId = runId,
                    onResult = { outcome -> finishRun(runId, outcome, onFinished) }
                )
            }.onFailure {
                finishRun(
                    runId,
                    TermuxClient.RunOutcome(runId, false, problem = "Couldn't reach Termux. Check it is installed and try again."),
                    onFinished
                )
            }
        }
        return runId
    }

    /**
     * Same run, but waits for the answer — the operations layer uses
     * this. Returns an OpResult in plain words. Cancelling the caller
     * stops the run too.
     */
    suspend fun runScriptAndWait(
        scriptId: String,
        args: Map<String, String> = emptyMap(),
        timeoutMs: Long = settings.defaultTimeoutMs
    ): OpResult {
        // Belt and braces: even if an answer is somehow lost on both
        // paths (bridge + PendingIntent), this resolves instead of
        // hanging. Cancelling on timeout stops the run too.
        return withTimeoutOrNull(timeoutMs + 10_000L) {
            suspendCancellableCoroutine { cont ->
                val runId = runApprovedScript(scriptId, args, timeoutMs) { run ->
                    if (cont.isActive) {
                        cont.resume(
                            OpResult(
                                run.state == TermuxRunState.DONE,
                                run.summary,
                                run.stdout.take(1200)
                            )
                        )
                    }
                }
                if (runId == null) {
                    if (cont.isActive) cont.resume(OpResult(false, statusLine))
                    return@suspendCancellableCoroutine
                }
                cont.invokeOnCancellation { cancelRun(runId) }
            }
        } ?: OpResult(false, "Termux didn't answer in time. Open Termux and try again.")
    }

    /** Stops a running script. A late answer afterwards is ignored. */
    fun cancelRun(runId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            execution.cancelRun(runId)
            saveHistory()
            refreshRuns()
            setStatus("Stopped.")
        }
    }

    /** Clears past runs from the screen and from storage. */
    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            execution.clearHistory()
            runCatching { historyStore.clearHistory() }
            withContext(Dispatchers.Main) {
                runs = emptyList()
                selectedRun = null
                statusLine = "Past runs cleared."
            }
        }
    }

    // ----- Capabilities -----

    /** Runs the probe script: checks every tool and reports back. */
    fun runProbe() {
        runApprovedScript(TermuxRegistry.PROBE_SCRIPT_ID)
    }

    /** Tests one capability by re-running the probe. */
    fun testCapability(id: String) {
        setStatus("Checking \"$id\"…")
        runProbe()
    }

    /** User switch: Ghost Tap won't offer a capability that is off. */
    fun setCapabilityEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            settings = settings.copy(
                capabilityEnabled = settings.capabilityEnabled + (id to enabled)
            )
            runCatching { historyStore.saveSettings(settings) }
            withContext(Dispatchers.Main) {
                capabilities = capabilities.map {
                    if (it.id == id) it.copy(enabled = enabled) else it
                }
            }
        }
    }

    /** Plain-words summary of what Termux can do right now. */
    fun capabilitySummary(): String {
        val known = capabilities.filter { it.state == TermuxCapabilityState.YES && it.enabled }
        val unknown = capabilities.count { it.state == TermuxCapabilityState.UNKNOWN }
        val couldNot = capabilities.count { it.state == TermuxCapabilityState.COULD_NOT_CHECK }
        return when {
            capabilities.all { it.state == TermuxCapabilityState.UNKNOWN } ->
                "Not checked yet — it checks by itself when this screen opens. Tap \"Check again\" to check now."
            known.isEmpty() && couldNot > 0 ->
                "Couldn't check what Termux can do — open Termux and try again."
            known.isEmpty() ->
                "Termux answered, but none of the usual tools were found."
            else -> buildString {
                append("Termux can do ${known.size} of ${capabilities.size} things: ")
                append(known.joinToString(", ") { it.title.lowercase() })
                append(".")
                if (unknown > 0) append(" $unknown still unchecked.")
            }
        }
    }

    // ----- Settings toggles -----

    fun updateUseTermuxInPlans(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            settings = settings.copy(useTermuxInPlans = enabled)
            runCatching { historyStore.saveSettings(settings) }
            withContext(Dispatchers.Main) { useTermuxInPlans = enabled }
        }
    }

    fun updateKeepHistory(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            settings = settings.copy(keepHistory = enabled)
            runCatching { historyStore.saveSettings(settings) }
            withContext(Dispatchers.Main) {
                keepHistory = enabled
                if (!enabled) {
                    execution.clearHistory()
                    runCatching { historyStore.clearHistory() }
                    runs = emptyList()
                    selectedRun = null
                }
            }
        }
    }

    /** Plain-words line for the bridge. */
    fun bridgeStatusLine(): String = when {
        !bridgeRunning -> "The link is off."
        else -> "The link is up — Ghost Tap is listening on this phone only."
    }

    // ----- Internals -----

    private fun ensureBridge(): Int {
        if (bridge.isRunning) return bridge.port
        val port = runCatching { bridge.start() }.getOrDefault(-1)
        viewModelScope.launch(Dispatchers.Main) {
            bridgeRunning = port >= 0
            bridgePortText = if (port >= 0) "Listening on this phone only" else ""
        }
        return port
    }

    private fun onBridgeMessage(message: TermuxBridge.TermuxBridgeMessage) {
        when (message) {
            is TermuxBridge.TermuxBridgeMessage.Result -> {
                // The script knows its own id; match the newest run of
                // that script still waiting for an answer. The
                // PendingIntent path (exact run id) is the other way an
                // answer arrives — whichever lands first wins, and the
                // execution manager ignores a late second answer.
                val run = execution.history().firstOrNull {
                    it.scriptId == message.scriptId && it.state == TermuxRunState.RUNNING
                } ?: return
                finishRun(
                    run.id,
                    TermuxClient.RunOutcome(
                        runId = run.id,
                        ok = message.exitCode == null || message.exitCode == 0,
                        stdout = message.stdout,
                        stderr = message.stderr,
                        exitCode = message.exitCode
                    ),
                    onFinished = null
                )
            }
            is TermuxBridge.TermuxBridgeMessage.Capabilities -> {
                viewModelScope.launch(Dispatchers.Main) {
                    val enabled = settings.capabilityEnabled
                    capabilities = TermuxRegistry.applyProbeResult(capabilities, message.toolsJson)
                        .map { it.copy(enabled = enabled[it.id] ?: true) }
                    statusLine = capabilitySummary()
                }
                // Keep the probe answer on disk (tools object only —
                // never the bridge token) so the list survives the
                // app restarting (v3 fix).
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching { historyStore.saveProbe(message.toolsJson) }
                }
            }
            is TermuxBridge.TermuxBridgeMessage.Log -> {
                setStatus(message.text.take(300))
            }
            is TermuxBridge.TermuxBridgeMessage.Library -> {
                // The lister answered: rebuild the user's script list
                // from what Termux really has, then keep it on disk so
                // the picker still shows it before the next check.
                val found = TermuxRegistry.mergeLibrary(parseLibraryJson(message.scriptsJson))
                viewModelScope.launch(Dispatchers.Main) {
                    libraryScripts = found
                    statusLine = if (found.isEmpty()) {
                        "No scripts of your own in Termux yet — write one there, then tap \"Check what scripts are there\"."
                    } else {
                        "Found ${found.size} script${if (found.size == 1) "" else "s"} of yours in Termux."
                    }
                }
                viewModelScope.launch(Dispatchers.IO) { saveLibrary(found) }
            }
            is TermuxBridge.TermuxBridgeMessage.Ping -> {
                // Health check answered — nothing to show.
            }
            is TermuxBridge.TermuxBridgeMessage.AskArrived -> {
                // Termux asked Ghost Tap to do something — say so in
                // plain words, using the operation's own title.
                val title = OperationRegistry.get(message.operation)?.title ?: message.operation
                setStatus("Termux asked Ghost Tap to ${title.lowercase()} — checking…")
            }
            is TermuxBridge.TermuxBridgeMessage.AskRefused -> {
                // Refusals are shown, never silent — the problem is
                // already in plain words.
                setStatus(message.problem)
            }
        }
    }

    /**
     * Answers one ask from Termux (v3, piece 2). The bridge already
     * checked the token and the allowlist before calling here, so
     * this only ever sees an allowlisted operation — the check at the
     * top is belt and braces, in case this is ever called from
     * anywhere else.
     *
     * Reply semantics — the split matters, so it is stated here and
     * in the bridge's docs:
     * - READ asks (check-installed, check-bridge-health,
     *   list-capabilities, list-library) run the SAME execute step
     *   the registered operation wraps, on the spot, and the reply
     *   carries the real answer. refresh-library is a READ that only
     *   STARTS a check — its answer says the list is coming, and the
     *   list itself still arrives through the normal library message.
     * - termux.run-approved-script only STARTS the run and replies
     *   that it started. The run's outcome still arrives through the
     *   normal result path (bridge result / PendingIntent). This
     *   function never waits on a run, and the bridge thread is never
     *   blocked by one.
     * - Anything that fails, or a script id that is not askable, is a
     *   refusal with a plain-words problem — nothing executes.
     */
    private suspend fun handleAsk(request: AskRequest): AskReply {
        val operation = request.operation
        askRefusalFor(operation)?.let { return AskReply.Refused(it) }

        if (operation == "termux.run-approved-script") {
            val details = request.details
            val scriptId = details?.optString("scriptId", "").orEmpty().trim()
            if (scriptId.isBlank()) {
                return AskReply.Refused("No script was named — say which script to run.")
            }
            if (!isAskableScriptId(scriptId, libraryScripts.map { it.id }.toSet())) {
                return AskReply.Refused(
                    "Ghost Tap can't run that script — it has to be one of the helper scripts, " +
                        "or one of your own scripts Ghost Tap already found in Termux."
                )
            }
            val script = TermuxRegistry.scriptById(scriptId)
                ?: libraryScripts.firstOrNull { it.id == scriptId }
                ?: return AskReply.Refused("Ghost Tap can't find that script any more.")
            val args = mutableMapOf<String, String>()
            details?.optJSONObject("args")?.let { argsJson ->
                val keys = argsJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    args[key] = argsJson.optString(key, "")
                }
            }
            val problem = TermuxRegistry.validateArgsProblem(script, args)
            if (problem.isNotBlank()) return AskReply.Refused(problem)
            runApprovedScript(scriptId, args)
                ?: return AskReply.Refused("Ghost Tap couldn't start that run — try again.")
            return AskReply.Ok(
                "Started \"${script.title}\" — the answer will come back the usual way when Termux finishes."
            )
        }

        // Every other allowlisted op is a fast READ: run the same
        // execute step the registered operation wraps, with this
        // ViewModel as its Termux side. No preparation steps run here
        // — those need the screen (a user choice, a navigation), and
        // an ask has neither; the execute step is the real work.
        val op = OperationRegistry.get(operation)
            ?: return AskReply.Refused("Ghost Tap doesn't know that job right now.")
        val result = runCatching { op.execute(OperationContext(termux = this)) }
            .getOrElse { return AskReply.Refused("That didn't work — try again.") }
        return if (result.ok) AskReply.Ok(result.summary)
        else AskReply.Refused(result.summary)
    }

    private fun finishRun(
        runId: String,
        outcome: TermuxClient.RunOutcome,
        onFinished: ((TermuxRun) -> Unit)?
    ) {
        val finished = execution.completeRun(
            runId = runId,
            ok = outcome.ok,
            stdout = outcome.stdout,
            stderr = outcome.stderr,
            exitCode = outcome.exitCode,
            problem = outcome.problem
        ) ?: return
        saveHistory()
        stageRunAnswer(finished)
        viewModelScope.launch(Dispatchers.Main) {
            runs = execution.history()
            selectedRun = finished
            statusLine = finished.summary
        }
        try {
            IntelEventBus.publish(
                IntelEvent.ToolOpResult("termux.run", outcome.ok, finished.summary.take(1200))
            )
        } catch (_: Exception) {
        }
        onFinished?.invoke(finished)
    }

    private fun saveHistory() {
        if (!settings.keepHistory) return
        runCatching { historyStore.saveHistory(execution.history()) }
    }

    /** Rebuilds a runnable library entry from a saved file name + title. */
    private fun libraryScriptFromEntry(entry: LibraryEntry): ApprovedScript {
        return ApprovedScript(
            id = "lib:${entry.fileName}",
            fileName = entry.fileName,
            title = entry.title.ifBlank { TermuxRegistry.scriptTitleFromFile(entry.fileName) },
            detail = "A script you put in Termux — runs the file as it is.",
            category = "Your scripts",
            origin = ScriptOrigin.ADDED
        )
    }

    /** Keeps the found scripts on disk so the picker shows them before the next check. */
    private fun saveLibrary(scripts: List<ApprovedScript>) {
        runCatching {
            historyStore.saveLibrary(scripts.map { LibraryEntry(it.fileName, it.title) })
        }
    }

    /**
     * Files the finished run's answer in the exchange dir, so the
     * answer is kept as a file Termux can pick up later. The file
     * holds the script id, what it said back, and its exit code —
     * never the bridge token.
     */
    private fun stageRunAnswer(run: TermuxRun) {
        runCatching {
            val json = org.json.JSONObject().apply {
                put("scriptId", run.scriptId)
                put("scriptTitle", run.scriptTitle)
                put("stdout", run.stdout)
                put("stderr", run.stderr)
                if (run.exitCode != null) put("exitCode", run.exitCode)
                put("summary", run.summary)
            }
            exchange.stageJson("run-${run.id}.json", json.toString())
        }
    }

    private fun refreshRuns() {
        viewModelScope.launch(Dispatchers.Main) { runs = execution.history() }
    }

    private fun setStatus(line: String) {
        viewModelScope.launch(Dispatchers.Main) { statusLine = line }
    }

    override fun onCleared() {
        runCatching { bridge.close() }
        runCatching { client.close() }
        super.onCleared()
    }
}
