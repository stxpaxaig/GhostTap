package com.ghosttap.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the piece-3 selection wiring: the gate, the
 * capability -> op map, the target -> steps matching, and the
 * refined-plan guard. Pure Kotlin, JUnit 4 — no Android framework.
 *
 * The gate provider is a process-global, so every test sets the gate
 * it needs and tearDown clears it: no gate must ever leak into the
 * next test (an unset gate means "Termux is not available").
 */
class TermuxPlanSelectionTest {

    private fun gate(
        useTermuxInPlans: Boolean = true,
        installed: Boolean = true,
        permission: Boolean = true,
        available: Set<String> = setOf(
            "python", "network", "dns", "http", "discovery", "files",
            "json", "data", "dev", "logs", "build", "bash",
            "automation", "termux-api", "wireless-audit"
        )
    ) = TermuxPlanSelection.TermuxPlanGate(
        useTermuxInPlans = useTermuxInPlans,
        termuxInstalled = installed,
        hasRunPermission = permission,
        availableCapabilities = available
    )

    private fun target(text: String, kind: String = "NETWORK") =
        IntelTarget(kind = kind, title = "test", text = text)

    @After
    fun tearDown() {
        TermuxPlanSelection.gateProvider = null
    }

    // ----- The gate -----

    @Test
    fun `no gate set means nothing is offered`() {
        TermuxPlanSelection.gateProvider = null
        assertFalse(TermuxPlanSelection.canOffer("network"))
        assertFalse(TermuxPlanSelection.termuxReady())
        assertTrue(
            TermuxPlanSelection.stepsFor(target("host 192.168.1.1"), setOf("net.ping")).isEmpty()
        )
    }

    @Test
    fun `master planning toggle does not block autonomous Termux discovery`() {
        TermuxPlanSelection.gateProvider = { gate(useTermuxInPlans = false) }
        assertTrue(TermuxPlanSelection.canOffer("network"))
        assertTrue(
            TermuxPlanSelection.stepsFor(target("host 192.168.1.1"), setOf("net.ping")).isNotEmpty()
        )
    }

    @Test
    fun `missing Termux closes the gate`() {
        TermuxPlanSelection.gateProvider = { gate(installed = false) }
        assertFalse(TermuxPlanSelection.termuxReady())
        assertTrue(TermuxPlanSelection.stepsFor(target("python"), setOf()).isEmpty())
    }

    @Test
    fun `missing permission closes the gate`() {
        TermuxPlanSelection.gateProvider = { gate(permission = false) }
        assertFalse(TermuxPlanSelection.termuxReady())
        assertTrue(TermuxPlanSelection.stepsFor(target("python"), setOf()).isEmpty())
    }

    // ----- Matching: the positive paths -----

    @Test
    fun `failed built-in ping becomes a Termux ping when the probe says network is there`() {
        TermuxPlanSelection.gateProvider = { gate() }
        val steps = TermuxPlanSelection.stepsFor(
            target("device at 192.168.1.42"), setOf("net.ping")
        )
        val ping = steps.firstOrNull { it.capabilityId == "termux.run-command" }
        assertEquals("ping -c 3 192.168.1.42", ping?.args?.get("command"))
        // WRITE steps are manual: Justin taps to run them (standing rule).
        assertTrue(ping?.auto == false)
    }

    @Test
    fun `a host name gets a Termux DNS lookup with no failure needed`() {
        TermuxPlanSelection.gateProvider = { gate() }
        val steps = TermuxPlanSelection.stepsFor(target("see example.com"), setOf())
        val lookup = steps.firstOrNull { it.args["command"] == "nslookup example.com" }
        assertEquals("termux.run-command", lookup?.capabilityId)
        // WRITE steps are manual: Justin taps to run them (standing rule).
        assertTrue(lookup?.auto == false)
    }

    @Test
    fun `failed built-in fetch becomes a Termux curl fetch`() {
        TermuxPlanSelection.gateProvider = { gate() }
        val steps = TermuxPlanSelection.stepsFor(
            target("page at https://example.com/status"), setOf("net.http_fetch")
        )
        val fetch = steps.firstOrNull { it.args["command"]?.startsWith("curl -sS") == true }
        assertTrue(fetch?.args?.get("command")?.endsWith("'https://example.com/status'") == true)
        // WRITE steps are manual: Justin taps to run them (standing rule).
        assertTrue(fetch?.auto == false)
    }

    @Test
    fun `Termux URL metacharacters remain inside one quoted argument`() {
        TermuxPlanSelection.gateProvider = { gate() }
        val steps = TermuxPlanSelection.stepsFor(
            target("page at https://example.com/a;id"), setOf("net.http_fetch")
        )
        val fetch = steps.firstOrNull { it.args["command"]?.startsWith("curl -sS") == true }
        assertTrue(fetch?.args?.get("command")?.endsWith("'https://example.com/a;id'") == true)
    }

    @Test
    fun `URL credentials are rejected instead of composed into a command`() {
        TermuxPlanSelection.gateProvider = { gate() }
        val steps = TermuxPlanSelection.stepsFor(
            target("page at https://trusted.example@127.0.0.1/private"), setOf("net.http_fetch")
        )
        assertFalse(steps.any { it.args["command"]?.startsWith("curl -sS") == true })
    }
    @Test
    fun `failed built-in port scan becomes a Termux nmap of that host`() {
        TermuxPlanSelection.gateProvider = { gate() }
        val steps = TermuxPlanSelection.stepsFor(
            target("device at 192.168.1.42"), setOf("net.port_scan")
        )
        val scan = steps.firstOrNull { it.args["command"] == "nmap 192.168.1.42" }
        assertEquals("termux.run-command", scan?.capabilityId)
    }

    @Test
    fun `a network target gets a Termux sweep of its subnet`() {
        TermuxPlanSelection.gateProvider = { gate() }
        val steps = TermuxPlanSelection.stepsFor(
            target("router at 192.168.1.1", kind = "NETWORK"), setOf()
        )
        val sweep = steps.firstOrNull { it.args["command"] == "nmap -sn 192.168.1.0/24" }
        assertEquals("termux.run-command", sweep?.capabilityId)
    }

    @Test
    fun `mentioning Python offers the check-Python script`() {
        TermuxPlanSelection.gateProvider = { gate() }
        val steps = TermuxPlanSelection.stepsFor(
            target("a python script on this device", kind = "MANUAL"), setOf()
        )
        val check = steps.firstOrNull { it.capabilityId == "termux.run-approved-script" }
        assertEquals("Check what Python is there", check?.args?.get("scriptTitle"))
        // WRITE steps are manual: Justin taps to run them (standing rule).
        assertTrue(check?.auto == false)
    }

    @Test
    fun `a Termux-flavoured target offers listing what Termux can do`() {
        TermuxPlanSelection.gateProvider = { gate() }
        val steps = TermuxPlanSelection.stepsFor(
            target("something in termux", kind = "MANUAL"), setOf()
        )
        assertTrue(steps.any { it.capabilityId == "termux.list-capabilities" && it.auto })
    }

    // ----- Matching: the negative paths -----

    @Test
    fun `probe says network is NOT there so no Termux ping is offered`() {
        TermuxPlanSelection.gateProvider = { gate(available = setOf("dns", "python")) }
        val steps = TermuxPlanSelection.stepsFor(
            target("device at 192.168.1.42"), setOf("net.ping")
        )
        assertFalse(steps.any { it.args["command"]?.startsWith("ping ") == true })
        // DNS is there, so the lookup still is — only the missing one is withheld.
        assertTrue(steps.any { it.args["command"] == "nslookup 192.168.1.42" })
    }

    @Test
    fun `probe never checked means no Termux step is offered`() {
        TermuxPlanSelection.gateProvider = { gate(available = emptySet()) }
        val steps = TermuxPlanSelection.stepsFor(
            target("python on host 192.168.1.42 at https://example.com"),
            setOf("net.ping", "net.http_fetch", "net.port_scan")
        )
        assertTrue(steps.isEmpty())
    }

    @Test
    fun `no failed built-in means no Termux fallback is offered`() {
        TermuxPlanSelection.gateProvider = { gate() }
        val steps = TermuxPlanSelection.stepsFor(
            target("device at 192.168.1.42"), setOf()
        )
        assertFalse(steps.any { it.args["command"]?.startsWith("ping ") == true })
        assertFalse(steps.any { it.args["command"]?.startsWith("curl ") == true })
        assertFalse(steps.any { it.args["command"] == "nmap 192.168.1.42" })
    }

    @Test
    fun `an impossible IP gets no step`() {
        TermuxPlanSelection.gateProvider = { gate() }
        assertFalse(TermuxPlanSelection.isValidIpv4("999.999.999.999"))
        assertFalse(TermuxPlanSelection.isValidIpv4("192.168.1"))
        assertTrue(TermuxPlanSelection.isValidIpv4("192.168.1.42"))
        val steps = TermuxPlanSelection.stepsFor(
            target("device at 999.999.999.999"), setOf("net.ping")
        )
        assertTrue(steps.none { it.args["command"]?.contains("999.999.999.999") == true })
    }

    @Test
    fun `a hostile host name is never put in a command`() {
        assertFalse(TermuxPlanSelection.isSafeHost("google.com; rm -rf /"))
        assertFalse(TermuxPlanSelection.isSafeHost("google.com\" && id"))
        assertFalse(TermuxPlanSelection.isSafeHost(""))
        assertTrue(TermuxPlanSelection.isSafeHost("example.com"))
        assertTrue(TermuxPlanSelection.isSafeHost("192.168.1.42"))
    }

    // ----- The map -----

    @Test
    fun `the map covers all fifteen probed capabilities`() {
        val ids = TermuxPlanSelection.jobs.map { it.capabilityId }.toSet()
        assertEquals(
            setOf(
                "python", "bash", "files", "json", "network", "dns", "http",
                "discovery", "dev", "data", "automation", "logs", "build",
                "termux-api", "wireless-audit"
            ),
            ids
        )
    }

    @Test
    fun `wireless request automatically runs live adapter diagnostics`() {
        TermuxPlanSelection.gateProvider = { gate() }
        val steps = TermuxPlanSelection.stepsFor(
            target("Check Aircrack-ng availability for my wireless adapter"), emptySet()
        )
        val audit = steps.firstOrNull { it.args["command"]?.contains("aircrack-ng --version") == true }
        assertEquals("termux.run-command", audit?.capabilityId)
        assertTrue(audit?.args?.get("command")?.contains("iw dev") == true)
        assertTrue(audit?.args?.get("command")?.contains("airmon-ng") == true)
        assertTrue(audit?.auto == true)
    }

    @Test
    fun `Aircrack diagnostics are not offered when the tool probe is missing`() {
        TermuxPlanSelection.gateProvider = { gate(available = setOf("network", "discovery")) }
        val steps = TermuxPlanSelection.stepsFor(
            target("Check Aircrack-ng availability"), emptySet()
        )
        assertFalse(steps.any { it.args["command"]?.contains("aircrack-ng --version") == true })
    }
    @Test
    fun `command tools map to their capability`() {
        assertEquals("network", TermuxPlanSelection.capabilityForCommand("ping -c 3 1.2.3.4"))
        assertEquals("http", TermuxPlanSelection.capabilityForCommand("curl -sS https://x.com"))
        assertEquals("dns", TermuxPlanSelection.capabilityForCommand("nslookup example.com"))
        assertEquals("discovery", TermuxPlanSelection.capabilityForCommand("nmap 192.168.1.1"))
        assertEquals("python", TermuxPlanSelection.capabilityForCommand("python3 script.py"))
        assertEquals(null, TermuxPlanSelection.capabilityForCommand("echo hello"))
    }

    // ----- The refined-plan guard -----

    @Test
    fun `filterSteps drops a Termux step the gate forbids and keeps the rest`() {
        TermuxPlanSelection.gateProvider = { gate(available = setOf("dns")) }
        val steps = listOf(
            PlanStep("net.ping", mapOf("host" to "192.168.1.1"), why = "built-in", auto = true),
            PlanStep(
                "termux.run-command", mapOf("command" to "ping -c 3 192.168.1.1"),
                why = "Termux ping", auto = true
            ),
            PlanStep(
                "termux.run-command", mapOf("command" to "nslookup example.com"),
                why = "Termux lookup", auto = true
            )
        )
        val kept = TermuxPlanSelection.filterSteps(steps)
        assertEquals(
            listOf("net.ping", "termux.run-command"),
            kept.map { it.capabilityId }
        )
        assertEquals("nslookup example.com", kept[1].args["command"])
    }

    @Test
    fun `filterSteps drops a run-command step with no command`() {
        TermuxPlanSelection.gateProvider = { gate() }
        val steps = listOf(
            PlanStep("termux.run-command", emptyMap(), why = "nothing to run", auto = true)
        )
        assertTrue(TermuxPlanSelection.filterSteps(steps).isEmpty())
    }

    @Test
    fun `filterSteps drops a run-script step with no script title`() {
        TermuxPlanSelection.gateProvider = { gate() }
        val steps = listOf(
            PlanStep("termux.run-approved-script", emptyMap(), why = "no script", auto = true)
        )
        assertTrue(TermuxPlanSelection.filterSteps(steps).isEmpty())
    }

    @Test
    fun `filterSteps keeps check-installed when only the setting is on`() {
        TermuxPlanSelection.gateProvider = {
            gate(installed = false, permission = false, available = emptySet())
        }
        val steps = listOf(
            PlanStep("termux.check-installed", why = "is Termux there?", auto = true),
            PlanStep("termux.run-command", mapOf("command" to "ping -c 3 1.2.3.4"), why = "ping", auto = true)
        )
        val kept = TermuxPlanSelection.filterSteps(steps)
        assertEquals(listOf("termux.check-installed"), kept.map { it.capabilityId })
    }

    @Test
    fun `filterSteps with no gate drops every Termux step`() {
        TermuxPlanSelection.gateProvider = null
        val steps = listOf(
            PlanStep("net.ping", mapOf("host" to "1.2.3.4"), why = "built-in", auto = true),
            PlanStep("termux.list-capabilities", why = "what can Termux do", auto = true)
        )
        val kept = TermuxPlanSelection.filterSteps(steps)
        assertEquals(listOf("net.ping"), kept.map { it.capabilityId })
    }
}
