package com.ghosttap.termux

import kotlinx.coroutines.Job
import kotlinx.serialization.Serializable

/** What one Termux run is doing right now. */
@Serializable
enum class TermuxRunState {
    /** Sent to Termux, waiting for an answer. */
    RUNNING,

    /** Finished and Termux said it went fine (exit code 0). */
    DONE,

    /** Finished and Termux said it went wrong, or it never answered. */
    FAILED,

    /** Still going after the time limit — marked failed, not left hanging. */
    TIMED_OUT,

    /** The user stopped it. */
    CANCELLED
}

/**
 * One run of one approved script. Saved to history as JSON (see
 * [com.ghosttap.data.TermuxHistoryStore]). Output is capped when
 * stored so one chatty script cannot grow the history file without
 * bound.
 */
@Serializable
data class TermuxRun(
    val id: String,
    val scriptId: String,
    val scriptTitle: String,
    val args: List<String> = emptyList(),
    val state: TermuxRunState = TermuxRunState.RUNNING,
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    val startedMs: Long = 0L,
    val finishedMs: Long? = null,
    /** Plain-words line for the run — what happened, in one sentence. */
    val summary: String = ""
) {
    companion object {
        const val MAX_STORED_OUTPUT_CHARS = 8_000
    }

    /** How long the run took, in plain words. Empty while still running. */
    fun durationWords(): String {
        val end = finishedMs ?: return ""
        val ms = (end - startedMs).coerceAtLeast(0L)
        return when {
            ms < 1_000 -> "${ms} ms"
            ms < 60_000 -> "${ms / 1_000} seconds"
            else -> "${ms / 60_000} minutes ${ms % 60_000 / 1_000} seconds"
        }
    }

    /** Exit code in plain words. */
    fun exitWords(): String = when (state) {
        TermuxRunState.DONE -> "It finished fine."
        TermuxRunState.FAILED -> "It finished with a problem" +
            (exitCode?.let { " (code $it)" } ?: "") + "."
        TermuxRunState.TIMED_OUT -> "It didn't finish in time."
        TermuxRunState.CANCELLED -> "You stopped it."
        TermuxRunState.RUNNING -> "Still going…"
    }
}

/**
 * Execution management: one run at a time is tracked live, every
 * finished run lands in history (most-recent-first, capped like
 * UsbCommandHistoryStore's 20). Pure Kotlin state — the Android
 * work (sending, timeouts, the bridge) lives in the ViewModel, so
 * this class is unit-testable on the JVM with no Android framework.
 *
 * Cancellation: the ViewModel hands the running coroutine [Job] in
 * with [attachJob]; [cancelRun] cancels it and marks the run
 * CANCELLED. A run that finishes after cancellation keeps its
 * CANCELLED state — a late answer never overwrites the user's stop.
 */
class TermuxExecutionManager(private val maxHistory: Int = 20) {

    private val runs = mutableListOf<TermuxRun>()
    private val jobs = mutableMapOf<String, Job>()

    /** All runs, most-recent-first. */
    fun history(): List<TermuxRun> = runs.toList()

    /** The run still going, if any. */
    fun activeRun(): TermuxRun? = runs.firstOrNull { it.state == TermuxRunState.RUNNING }

    fun runById(id: String): TermuxRun? = runs.firstOrNull { it.id == id }

    /** Starts tracking a run. Returns the run record. */
    fun startRun(id: String, script: ApprovedScript, args: List<String>): TermuxRun {
        return startRun(id, script.id, script.title, args)
    }

    /**
     * Starts tracking a run with an explicit title. The free-command
     * run uses this so the history shows the command itself instead
     * of a fixed label.
     */
    fun startRun(id: String, scriptId: String, scriptTitle: String, args: List<String>): TermuxRun {
        val run = TermuxRun(
            id = id,
            scriptId = scriptId,
            scriptTitle = scriptTitle,
            args = args.filter { it.isNotEmpty() },
            state = TermuxRunState.RUNNING,
            startedMs = System.currentTimeMillis(),
            summary = "Running \"$scriptTitle\"…"
        )
        runs.add(0, run)
        trim()
        return run
    }

    fun attachJob(runId: String, job: Job) {
        jobs[runId] = job
    }

    /** Marks a run finished with Termux's answer. Late answers after a cancel are ignored. */
    fun completeRun(
        runId: String,
        ok: Boolean,
        stdout: String,
        stderr: String,
        exitCode: Int?,
        problem: String = ""
    ): TermuxRun? {
        val index = runs.indexOfFirst { it.id == runId }
        if (index < 0) return null
        val old = runs[index]
        // Only a run still going can finish. A late answer after the
        // user stopped it — or after it timed out — is ignored, never
        // overwriting the state the user already saw.
        if (old.state != TermuxRunState.RUNNING) return old
        val state = if (ok) TermuxRunState.DONE else TermuxRunState.FAILED
        val summary = if (ok) {
            "\"${old.scriptTitle}\" finished fine."
        } else if (problem.isNotBlank()) {
            problem
        } else {
            "\"${old.scriptTitle}\" finished with a problem" +
                (exitCode?.let { " (code $it)" } ?: "") + "."
        }
        val updated = old.copy(
            state = state,
            stdout = stdout.take(TermuxRun.MAX_STORED_OUTPUT_CHARS),
            stderr = stderr.take(TermuxRun.MAX_STORED_OUTPUT_CHARS),
            exitCode = exitCode,
            finishedMs = System.currentTimeMillis(),
            summary = summary
        )
        runs[index] = updated
        jobs.remove(runId)
        return updated
    }

    /** Marks a run as timed out. A late answer afterwards is ignored. */
    fun timeoutRun(runId: String): TermuxRun? {
        val index = runs.indexOfFirst { it.id == runId }
        if (index < 0) return null
        val old = runs[index]
        if (old.state != TermuxRunState.RUNNING) return old
        val updated = old.copy(
            state = TermuxRunState.TIMED_OUT,
            finishedMs = System.currentTimeMillis(),
            summary = "\"${old.scriptTitle}\" didn't finish in time."
        )
        runs[index] = updated
        jobs.remove(runId)?.cancel()
        return updated
    }

    /** The user stopped the run. Cancels the job and marks it CANCELLED. */
    fun cancelRun(runId: String): TermuxRun? {
        val index = runs.indexOfFirst { it.id == runId }
        if (index < 0) return null
        val old = runs[index]
        if (old.state != TermuxRunState.RUNNING) return old
        jobs.remove(runId)?.cancel()
        val updated = old.copy(
            state = TermuxRunState.CANCELLED,
            finishedMs = System.currentTimeMillis(),
            summary = "You stopped \"${old.scriptTitle}\"."
        )
        runs[index] = updated
        return updated
    }

    /** Replaces the history (loaded from disk on start). */
    fun restore(saved: List<TermuxRun>) {
        runs.clear()
        runs.addAll(saved.take(maxHistory))
    }

    fun clearHistory() {
        runs.clear()
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    private fun trim() {
        while (runs.size > maxHistory) runs.removeAt(runs.lastIndex)
    }
}
