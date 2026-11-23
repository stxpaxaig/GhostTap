package com.ghosttap.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Termux capability registry: the allowlist, the
 * argument validation, the probe-answer parsing, and the 15
 * categories. Pure Kotlin, JUnit 4 — no Android framework.
 */
class TermuxCapabilitiesTest {

    @Test
    fun `the built-in list has the helpers plus the runner and the lister`() {
        // 6 user scripts + 3 internal (probe, runner, library lister).
        assertEquals(9, TermuxRegistry.scripts.size)
        assertEquals(6, TermuxRegistry.userScripts.size)
        assertTrue(TermuxRegistry.isAllowlisted("hello"))
        assertTrue(TermuxRegistry.isAllowlisted("check-python"))
        assertTrue(TermuxRegistry.isAllowlisted("check-network"))
        assertTrue(TermuxRegistry.isAllowlisted("list-tools"))
        assertTrue(TermuxRegistry.isAllowlisted("check-storage"))
        assertTrue(TermuxRegistry.isAllowlisted("check-time"))
        assertTrue(TermuxRegistry.isAllowlisted(TermuxRegistry.PROBE_SCRIPT_ID))
        assertTrue(TermuxRegistry.isAllowlisted(TermuxRegistry.FREE_COMMAND_SCRIPT_ID))
        assertTrue(TermuxRegistry.isAllowlisted(TermuxRegistry.LIST_LIBRARY_SCRIPT_ID))
        assertFalse(TermuxRegistry.isAllowlisted("rm"))
        assertFalse(TermuxRegistry.isAllowlisted("bash"))
        assertFalse(TermuxRegistry.isAllowlisted(""))
        assertFalse(TermuxRegistry.isAllowlisted("ghosttap-hello.sh"))
    }

    @Test
    fun `a script the user added is runnable the moment it is found`() {
        val added = ApprovedScript(
            id = "lib:my-backup.sh",
            fileName = "my-backup.sh",
            title = "My backup",
            detail = "A script you put in Termux.",
            category = "Your scripts",
            origin = ScriptOrigin.ADDED
        )
        assertTrue(TermuxRegistry.isRunnable(added))
        assertFalse(TermuxRegistry.isRunnable(added.copy(id = "lib:other.sh", origin = ScriptOrigin.BUILT_IN)))
        val unknown = ApprovedScript(
            id = "nope",
            fileName = "nope.sh",
            title = "Nope",
            detail = "Not known.",
            category = "Your scripts"
        )
        assertFalse(TermuxRegistry.isRunnable(unknown))
    }

    @Test
    fun `every allowlisted script lives under the tasker dir`() {
        TermuxRegistry.scripts.forEach { script ->
            assertTrue(script.termuxPath.startsWith(TermuxClient.TASKER_DIR + "/"))
            assertTrue(script.termuxPath.endsWith(".sh"))
            assertFalse(script.termuxPath.contains(" "))
        }
    }

    @Test
    fun `there are exactly 15 capability categories`() {
        val caps = TermuxRegistry.defaultCapabilities()
        assertEquals(15, caps.size)
        assertEquals(15, caps.map { it.id }.toSet().size)
        assertTrue(caps.all { it.probeTools.isNotEmpty() })
        assertTrue(caps.all { it.state == TermuxCapabilityState.UNKNOWN })
        assertTrue(caps.all { it.enabled })
    }

    @Test
    @Test
    fun `Aircrack tools are detected as a separate wireless diagnostics capability`() {
        val caps = TermuxRegistry.defaultCapabilities()
        val wireless = caps.first { it.id == "wireless-audit" }
        assertTrue(wireless.probeTools.contains("aircrack-ng"))
        val updated = TermuxRegistry.applyProbeResult(caps, """{"aircrack-ng":true}""")
        assertEquals(TermuxCapabilityState.YES, updated.first { it.id == "wireless-audit" }.state)
    }
    fun `probe JSON parsing reads true and false per tool`() {
        val parsed = parseProbeToolsJson("""{"python3":true,"git":false,"curl":true}""")
        assertNotNull(parsed)
        assertEquals(true, parsed!!["python3"])
        assertEquals(false, parsed["git"])
        assertEquals(true, parsed["curl"])
    }

    @Test
    fun `probe JSON parsing rejects non-objects`() {
        assertNull(parseProbeToolsJson("not json"))
        assertNull(parseProbeToolsJson("[1, 2]"))
        assertNull(parseProbeToolsJson(""))
        assertEquals(emptyMap<String, Boolean>(), parseProbeToolsJson("{}"))
    }

    @Test
    fun `applyProbeResult marks YES only on a real true answer`() {
        val caps = TermuxRegistry.defaultCapabilities()
        val updated = TermuxRegistry.applyProbeResult(
            caps,
            """{"python3":true,"bash":true,"git":false,"nmap":false}"""
        )
        val byId = updated.associateBy { it.id }
        assertEquals(TermuxCapabilityState.YES, byId["python"]!!.state)
        assertEquals(TermuxCapabilityState.YES, byId["bash"]!!.state)
        // dev probed git (false) and ssh/scp (unanswered): git answered,
        // so the capability is NO, not "couldn't check".
        assertEquals(TermuxCapabilityState.NO, byId["dev"]!!.state)
        // discovery probed nmap (false) and arp-scan/ping (unanswered).
        assertEquals(TermuxCapabilityState.NO, byId["discovery"]!!.state)
        // json probed jq (unanswered) and python3 (true) -> YES.
        assertEquals(TermuxCapabilityState.YES, byId["json"]!!.state)
        // build probed nothing in this answer -> COULD_NOT_CHECK.
        assertEquals(TermuxCapabilityState.COULD_NOT_CHECK, byId["build"]!!.state)
    }

    @Test
    fun `an unparseable probe answer never invents a YES`() {
        val caps = TermuxRegistry.defaultCapabilities()
        val updated = TermuxRegistry.applyProbeResult(caps, "garbage")
        assertTrue(updated.all { it.state == TermuxCapabilityState.COULD_NOT_CHECK })
        assertFalse(updated.any { it.state == TermuxCapabilityState.YES })
    }

    @Test
    fun `argument validation accepts a plain host and refuses a bad address`() {
        val script = TermuxRegistry.scriptById("check-network")!!
        assertNotNull(TermuxRegistry.validateArgs(script, mapOf("host" to "google.com")))
        assertNotNull(TermuxRegistry.validateArgs(script, mapOf("host" to "192.168.1.1")))
        assertNotNull(TermuxRegistry.validateArgs(script, mapOf("host" to "")))
        // The host box wants a website or address — these fail that shape, not a character rule.
        assertNull(TermuxRegistry.validateArgs(script, mapOf("host" to "google.com; rm -rf /")))
        assertNull(TermuxRegistry.validateArgs(script, mapOf("host" to "a|b")))
        assertNull(TermuxRegistry.validateArgs(script, mapOf("host" to "\$(whoami)")))
        assertNull(TermuxRegistry.validateArgs(script, mapOf("host" to "x".repeat(201))))
        assertTrue(TermuxRegistry.validateArgsProblem(script, mapOf("host" to "a;b")).isNotBlank())
    }

    @Test
    fun `a typed command runs exactly as typed, pipes and redirects included`() {
        val script = TermuxRegistry.scriptById(TermuxRegistry.FREE_COMMAND_SCRIPT_ID)!!
        val command = "ls -la ~/storage | grep -i photo > /tmp/photos.txt; echo done"
        val cleaned = TermuxRegistry.validateArgs(script, mapOf("command" to command))
        assertNotNull(cleaned)
        assertEquals(listOf(command), cleaned)
        assertEquals("", TermuxRegistry.validateArgsProblem(script, mapOf("command" to command)))
        // Only an empty command is refused.
        assertNull(TermuxRegistry.validateArgs(script, mapOf("command" to "   ")))
        assertTrue(TermuxRegistry.validateArgsProblem(script, mapOf("command" to "")).isNotBlank())
    }

    @Test
    fun `library JSON parsing reads file names from an array or an object`() {
        assertEquals(
            listOf("my-backup.sh", "ghosttap-hello.sh"),
            parseLibraryJson("""[{"file":"my-backup.sh"},{"file":"ghosttap-hello.sh"}]""")
        )
        assertEquals(
            listOf("my-backup.sh"),
            parseLibraryJson("""{"scripts":[{"file":"my-backup.sh"}]}""")
        )
        assertEquals(emptyList<String>(), parseLibraryJson("not json"))
        assertEquals(emptyList<String>(), parseLibraryJson(""))
        assertEquals(emptyList<String>(), parseLibraryJson("[]"))
        assertEquals(emptyList<String>(), parseLibraryJson("""{"scripts":[]}"""))
    }

    @Test
    fun `script titles come from the file name in plain words`() {
        assertEquals("My Backup", TermuxRegistry.scriptTitleFromFile("my-backup.sh"))
        assertEquals("Check Disk Space", TermuxRegistry.scriptTitleFromFile("check_disk_space.sh"))
        assertEquals("Hello", TermuxRegistry.scriptTitleFromFile("hello.sh"))
        assertEquals("Photos 2026", TermuxRegistry.scriptTitleFromFile("photos-2026.sh"))
    }

    @Test
    fun `merging the library keeps the user's scripts and leaves built-ins out`() {
        val merged = TermuxRegistry.mergeLibrary(
            listOf("my-backup.sh", "ghosttap-hello.sh", "ghosttap-lib.sh", "notes.txt", "my-backup.sh", "check-disk.sh")
        )
        assertEquals(listOf("my-backup.sh", "check-disk.sh"), merged.map { it.fileName })
        assertEquals(listOf("lib:my-backup.sh", "lib:check-disk.sh"), merged.map { it.id })
        assertTrue(merged.all { it.origin == ScriptOrigin.ADDED })
        assertEquals("My Backup", merged[0].title)
        assertEquals("Check Disk", merged[1].title)
        assertTrue(TermuxRegistry.mergeLibrary(emptyList()).isEmpty())
    }

    @Test
    fun `scripts without args need no arguments`() {
        val script = TermuxRegistry.scriptById("hello")!!
        assertEquals(emptyList<String>(), TermuxRegistry.validateArgs(script, emptyMap()))
    }
}
