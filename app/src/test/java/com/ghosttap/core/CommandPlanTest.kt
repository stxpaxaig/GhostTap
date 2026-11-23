package com.ghosttap.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for the v3.2 home command box plan builder
 * ([planForCommand]): the typed words become a MANUAL target for
 * the existing rule-based planner, which proposes steps that
 * reference ONLY registered capability ids — and, since v3.2, never
 * a do-it-yourself step: the box runs the work itself.
 *
 * Pure Kotlin, JUnit 4 — no Android framework. The planner
 * validates every step against the shared [CapabilityRegistry]
 * singleton and drops unknown ids, so the test registers the stub
 * capabilities the planner's steps need (same pattern as
 * Package6OperationsTest). The Termux gate stays closed in every
 * test here: no Termux step may appear without the gate.
 */
class CommandPlanTest {

    private fun reg(id: String, risk: String = "READ") {
        CapabilityRegistry.register(
            IntelCapability(
                id = id,
                title = id,
                detail = id,
                category = "TEST",
                readOnly = risk == "READ",
                riskLevel = risk,
                sourceMethod = "test",
                run = { OpResult(true, "ok") }
            )
        )
    }

    @Before
    fun setUp() {
        // Every capability id the planner can emit for a MANUAL target.
        reg("ble.list_targets")
        reg("ble.start_scan")
        reg("net.ping")
        reg("net.port_scan")
        reg("net.http_fetch")
        reg("sys.manual_step")
        reg("usb.refresh")
        reg("usb.status")
        reg("usb.connect")
        reg("vesp32.check-installed")
        reg("vesp32.connect-practice-board", "CONFIGURE")
        reg("vesp32.check-practice-status")
        reg("vesp32.check-light-pin")
        reg("vesp32.flip-switch-pin", "CONTROL")
        reg("vesp32.use-practice-board", "CONTROL")
        reg("nfc.open_tools")
        reg("nfc.read_summary")
        reg("nfc.emulation_status")
        reg("visual.capture_analyze")
        reg("vnic.ask-about-device", "CONTROL")
        TermuxPlanSelection.gateProvider = null
    }

    @After
    fun tearDown() {
        TermuxPlanSelection.gateProvider = null
    }

    @Test
    fun `an IP in the words proposes a ping step carrying the host`() {
        val plan = planForCommand("ping 192.168.1.1 and see if it answers")
        val ping = plan.firstOrNull { it.capabilityId == "net.ping" }
        assertTrue("expected a net.ping step", ping != null)
        assertEquals("192.168.1.1", ping!!.args["host"])
        assertTrue("ping step must be a READ", ping.riskLevel == "READ")
    }

    @Test
    fun `bluetooth words propose a scan step`() {
        val plan = planForCommand("what bluetooth devices are near me")
        assertTrue(plan.any { it.capabilityId == "ble.start_scan" })
    }

    @Test
    fun `plain words with no pattern still propose a non-empty plan`() {
        val plan = planForCommand("what is around me")
        assertTrue("expected at least the list step", plan.isNotEmpty())
        assertTrue(plan.any { it.capabilityId == "ble.list_targets" })
        // v3.2 — the command box never shows a do-it-yourself step:
        // the old end-of-plan "review the timeline" step is gone here
        // (it stays in the Investigations planner, where the timeline
        // it points at really is on the screen).
        assertFalse(plan.any { it.capabilityId == "sys.manual_step" })
    }

    @Test
    fun `no command-box plan ever contains a do-it-yourself step`() {
        // TV words, NFC words, a Wi-Fi code and an IP at once — every
        // matcher that used to end in a manual step fires.
        val plan = planForCommand(
            "find me a tv, scan the nfc tag, join WIFI:S:Home;T:WPA;P:secret;; and ping 10.0.0.5"
        )
        assertTrue(plan.isNotEmpty())
        assertFalse(
            "command-box plan must not contain sys.manual_step",
            plan.any { it.capabilityId == "sys.manual_step" }
        )
    }

    @Test
    fun `every proposed step is a registered capability`() {
        val plan = planForCommand("check the usb esp32 and ping 10.0.0.5")
        assertTrue(plan.isNotEmpty())
        for (step in plan) {
            assertTrue(
                "step ${step.capabilityId} is not registered",
                CapabilityRegistry.get(step.capabilityId) != null
            )
        }
    }

    @Test
    fun `usb words propose the usb bench steps`() {
        val plan = planForCommand("is my esp32 plugged into usb")
        assertTrue(plan.any { it.capabilityId == "usb.refresh" })
        assertTrue(plan.any { it.capabilityId == "usb.status" })
    }

    @Test
    fun `identified investigation targets offer the unified brain as a deliberate action`() {
        val target = IntelTarget(
            kind = "USB",
            title = "ESP32 board",
            text = "ESP32 development board connected over USB"
        )
        val plan = Planner.planFor(target, CapabilityRegistry)
        val brainStep = plan.firstOrNull { it.capabilityId == "vnic.ask-about-device" }
        assertTrue("expected the unified brain hand-off", brainStep != null)
        assertFalse("device bring-up must remain user-triggered", brainStep!!.auto)
        assertEquals("CONTROL", brainStep.riskLevel)
    }

    @Test
    fun `ESP32 investigation offers existing VirtualESP32 diagnostics without auto-starting or changing pins`() {
        val target = IntelTarget(
            kind = "USB",
            title = "ESP32-SOLO-1C board",
            text = "development board, UART serial interface"
        )
        val plan = Planner.planFor(target, CapabilityRegistry)
        val ids = plan.map { it.capabilityId }

        assertTrue(ids.contains("vesp32.check-installed"))
        assertTrue(ids.contains("vesp32.connect-practice-board"))
        assertTrue(ids.contains("vesp32.check-practice-status"))
        assertTrue(ids.contains("vesp32.check-light-pin"))

        assertTrue(plan.first { it.capabilityId == "vesp32.check-installed" }.auto)
        assertFalse(plan.first { it.capabilityId == "vesp32.connect-practice-board" }.auto)
        assertFalse(plan.first { it.capabilityId == "vesp32.check-practice-status" }.auto)
        assertFalse(plan.first { it.capabilityId == "vesp32.check-light-pin" }.auto)

        assertFalse(ids.contains("vesp32.flip-switch-pin"))
        assertFalse(ids.contains("vesp32.use-practice-board"))
        assertTrue(plan.all { CapabilityRegistry.get(it.capabilityId) != null })
    }

    @Test
    fun `ESP32 kind is recognized even when the description omits the chip name`() {
        val target = IntelTarget(
            kind = "ESP32",
            title = "Practice board",
            text = "serial device"
        )
        val plan = Planner.planFor(target, CapabilityRegistry)
        assertTrue(plan.any { it.capabilityId == "vesp32.check-installed" })
        assertTrue(plan.any { it.capabilityId == "vesp32.connect-practice-board" })
    }

    @Test
    fun `termux steps stay out when the gate is closed`() {
        val plan = planForCommand("check python and the network in termux")
        assertFalse(plan.any { it.capabilityId.startsWith("termux.") })
    }

    @Test
    fun `the plan never invents a step id`() {
        // A URL + a MAC + NFC words at once: several matchers fire.
        val plan = planForCommand(
            "fetch http://example.com and scan nfc tag AA:BB:CC:DD:EE:FF"
        )
        assertTrue(plan.any { it.capabilityId == "net.http_fetch" })
        assertTrue(plan.any { it.capabilityId == "nfc.open_tools" })
        assertTrue(plan.all { CapabilityRegistry.get(it.capabilityId) != null })
    }
}
