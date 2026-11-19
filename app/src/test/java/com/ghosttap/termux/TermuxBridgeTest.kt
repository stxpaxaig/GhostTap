package com.ghosttap.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Termux bridge protocol helpers and the execution
 * manager. Pure Kotlin, JUnit 4 — no Android framework.
 *
 * NOTE: TermuxBridge itself uses org.json (Android built-in), so the
 * socket/server parts are NOT covered here — they need the device.
 * What is covered is everything that can be wrong in pure code:
 * request ids, token shape, loopback checks, JSON field extraction,
 * and run state transitions (including the late-answer-after-cancel
 * rule).
 */
class TermuxBridgeTest {

    @Test
    fun `request ids are unique and look like UUIDs`() {
        val a = newRequestId()
        val b = newRequestId()
        assertFalse(a == b)
        assertTrue(a.length == 36 && a.count { it == '-' } == 4)
    }

    @Test
    fun `only loopback hosts are accepted`() {
        assertTrue(isLoopbackHost("127.0.0.1"))
        assertTrue(isLoopbackHost("localhost"))
        assertTrue(isLoopbackHost("LOCALHOST"))
        assertTrue(isLoopbackHost("::1"))
        assertFalse(isLoopbackHost("192.168.1.5"))
        assertFalse(isLoopbackHost("10.0.0.1"))
        assertFalse(isLoopbackHost("example.com"))
        assertFalse(isLoopbackHost(""))
    }

    @Test
    fun `token shape check rejects empty and short tokens`() {
        assertFalse(isPlausibleBridgeToken(""))
        assertFalse(isPlausibleBridgeToken("short"))
        assertFalse(isPlausibleBridgeToken("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa with space"))
        assertTrue(isPlausibleBridgeToken("a".repeat(32)))
        assertTrue(isPlausibleBridgeToken("Ab3-_x".repeat(6)))
    }

    @Test
    fun `extractJsonStringField pulls flat string fields`() {
        val line = """{"type":"result","requestId":"abc-123","token":"tok","stdout":"hello"}"""
        assertEquals("result", extractJsonStringField(line, "type"))
        assertEquals("abc-123", extractJsonStringField(line, "requestId"))
        assertEquals("hello", extractJsonStringField(line, "stdout"))
        assertNull(extractJsonStringField(line, "missing"))
    }

    @Test
    fun `extractJsonStringField handles escapes`() {
        val line = """{"stdout":"line1\nline2 \"quoted\""}"""
        assertEquals("line1\nline2 \"quoted\"", extractJsonStringField(line, "stdout"))
    }

    @Test
    fun `extractJsonStringField ignores non-string values`() {
        val line = """{"exitCode":0,"ok":true}"""
        assertNull(extractJsonStringField(line, "exitCode"))
        assertNull(extractJsonStringField(line, "ok"))
    }
}

class TermuxExecutionTest {

    private val hello = TermuxRegistry.scriptById("hello")!!

    @Test
    fun `start then complete marks DONE with the answer`() {
        val manager = TermuxExecutionManager()
        val run = manager.startRun("r1", hello, emptyList())
        assertEquals(TermuxRunState.RUNNING, run.state)
        val done = manager.completeRun("r1", true, "hi\n", "", 0)!!
        assertEquals(TermuxRunState.DONE, done.state)
        assertEquals("hi\n", done.stdout)
        assertEquals(0, done.exitCode)
        assertTrue(done.summary.contains("finished fine"))
    }

    @Test
    fun `a failed run keeps its exit code and problem`() {
        val manager = TermuxExecutionManager()
        manager.startRun("r1", hello, emptyList())
        val done = manager.completeRun("r1", false, "", "nope", 1, "It broke.")!!
        assertEquals(TermuxRunState.FAILED, done.state)
        assertEquals(1, done.exitCode)
        assertEquals("It broke.", done.summary)
    }

    @Test
    fun `cancel wins over a late answer`() {
        val manager = TermuxExecutionManager()
        manager.startRun("r1", hello, emptyList())
        val cancelled = manager.cancelRun("r1")!!
        assertEquals(TermuxRunState.CANCELLED, cancelled.state)
        // A late answer from Termux must NOT overwrite the user's stop.
        val late = manager.completeRun("r1", true, "late", "", 0)!!
        assertEquals(TermuxRunState.CANCELLED, late.state)
        assertEquals("", late.stdout)
    }

    @Test
    fun `timeout marks TIMED_OUT and a late answer is ignored`() {
        val manager = TermuxExecutionManager()
        manager.startRun("r1", hello, emptyList())
        val timedOut = manager.timeoutRun("r1")!!
        assertEquals(TermuxRunState.TIMED_OUT, timedOut.state)
        val late = manager.completeRun("r1", true, "late", "", 0)!!
        assertEquals(TermuxRunState.TIMED_OUT, late.state)
    }

    @Test
    fun `history is most-recent-first and capped at 20`() {
        val manager = TermuxExecutionManager()
        repeat(25) { i -> manager.startRun("r$i", hello, emptyList()) }
        val history = manager.history()
        assertEquals(20, history.size)
        assertEquals("r24", history.first().id)
        assertEquals("r5", history.last().id)
    }

    @Test
    fun `restore and clear round-trip`() {
        val manager = TermuxExecutionManager()
        manager.startRun("r1", hello, emptyList())
        val restored = TermuxExecutionManager()
        restored.restore(manager.history())
        assertEquals(1, restored.history().size)
        restored.clearHistory()
        assertTrue(restored.history().isEmpty())
    }

    @Test
    fun `startRun with an explicit title shows the typed command in history`() {
        val manager = TermuxExecutionManager()
        val run = manager.startRun("r1", "free-command", "ls -la | head", emptyList())
        assertEquals("free-command", run.scriptId)
        assertEquals("ls -la | head", run.scriptTitle)
        assertEquals(TermuxRunState.RUNNING, run.state)
        val done = manager.completeRun("r1", true, "total 4\n", "", 0)!!
        assertEquals(TermuxRunState.DONE, done.state)
        assertEquals("ls -la | head", done.scriptTitle)
        assertTrue(done.summary.contains("finished fine"))
    }

    @Test
    fun `exit words are plain words`() {
        val base = TermuxRun(id = "x", scriptId = "hello", scriptTitle = "Say hello", startedMs = 1000L)
        assertEquals("Still going…", base.copy(state = TermuxRunState.RUNNING).exitWords())
        assertEquals("It finished fine.", base.copy(state = TermuxRunState.DONE).exitWords())
        assertEquals("It finished with a problem (code 1).", base.copy(state = TermuxRunState.FAILED, exitCode = 1).exitWords())
        assertEquals("It didn't finish in time.", base.copy(state = TermuxRunState.TIMED_OUT).exitWords())
        assertEquals("You stopped it.", base.copy(state = TermuxRunState.CANCELLED).exitWords())
        assertEquals("5 seconds", base.copy(state = TermuxRunState.DONE, finishedMs = 6000L).durationWords())
    }
}

/**
 * JVM tests for the Termux -> Ghost Tap ask gate (v3, piece 2).
 * Pure Kotlin — no bridge socket, no Android framework. What is
 * covered is the only thing that must never be wrong: which
 * operations Termux may ask for, and which script ids may be run
 * on an ask. The socket/reply half is hand-traced + fake-bridge
 * tested (see the piece-2 report) because handleLine needs org.json.
 */
class TermuxAskTest {

    @Test
    fun `the six allowlisted asks pass the gate`() {
        listOf(
            "termux.check-installed",
            "termux.check-bridge-health",
            "termux.list-capabilities",
            "termux.list-library",
            "termux.refresh-library",
            "termux.run-approved-script"
        ).forEach { op ->
            assertNull("expected $op to be allowlisted", askRefusalFor(op))
        }
    }

    @Test
    fun `a typed command ask is refused with a plain-words reason`() {
        val refusal = askRefusalFor("termux.run-command")
        assertNotNull(refusal)
        assertTrue(refusal!!.contains("typed command"))
    }

    @Test
    fun `opening a screen is refused`() {
        assertNotNull(askRefusalFor("screen.termux"))
    }

    @Test
    fun `an unknown operation is refused, never invented`() {
        val refusal = askRefusalFor("printer.print")
        assertNotNull(refusal)
        assertTrue(refusal!!.contains("doesn't know that job"))
        assertNotNull(askRefusalFor(""))
        assertNotNull(askRefusalFor("termux.run-approved-script "))
    }

    @Test
    fun `a built-in script the user can pick is askable`() {
        assertTrue(isAskableScriptId("hello", emptySet()))
        assertTrue(isAskableScriptId("check-python", emptySet()))
        assertTrue(isAskableScriptId("check-network", emptySet()))
        assertTrue(isAskableScriptId("list-tools", emptySet()))
        assertTrue(isAskableScriptId("check-storage", emptySet()))
        assertTrue(isAskableScriptId("check-time", emptySet()))
    }

    @Test
    fun `internal scripts are never askable — the typed-command runner included`() {
        // "free-command" is the typed-command runner: asking for it
        // would be arbitrary command execution through the back door.
        assertFalse(isAskableScriptId("free-command", emptySet()))
        // The probe and the library lister are Ghost Tap's own
        // internal checks, not scripts to ask for.
        assertFalse(isAskableScriptId("probe", emptySet()))
        assertFalse(isAskableScriptId("list-library", emptySet()))
    }

    @Test
    fun `a library script is askable only once Ghost Tap already found it`() {
        val found = setOf("lib:my-backup.sh")
        assertTrue(isAskableScriptId("lib:my-backup.sh", found))
        assertFalse(isAskableScriptId("lib:my-backup.sh", emptySet()))
        assertFalse(isAskableScriptId("lib:not-found.sh", found))
    }

    @Test
    fun `blank and unknown script ids are not askable`() {
        assertFalse(isAskableScriptId("", emptySet()))
        assertFalse(isAskableScriptId("nope", emptySet()))
        assertFalse(isAskableScriptId("ghosttap-lib.sh", emptySet()))
    }
}
