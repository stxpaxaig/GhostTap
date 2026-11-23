package com.ghosttap.core.vnic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VnicTransportRoutingTest {

    @Test
    fun esp32IsFirstAndSpecificTransportCandidate() {
        val targets = vnicDetectBringUpTargets(
            subject = "LED controller",
            text = "Find the ESP32 controller using Bluetooth at AA:BB:CC:DD:EE:FF and Wi-Fi at 192.168.4.27"
        )
        assertEquals(VnicBringUpKind.ESP32, targets.first().kind)
        assertTrue(targets.any { it.kind == VnicBringUpKind.BLE })
        assertTrue(targets.any { it.kind == VnicBringUpKind.NETWORK })
    }

    @Test
    fun networkCandidateUsesDiscoveredIpv4Address() {
        val targets = vnicDetectBringUpTargets(
            subject = "LED sign",
            text = "Investigate the sign on Wi-Fi",
            findings = listOf(
                VnicFinding(
                    query = "LED sign",
                    title = "Device admin page",
                    url = "http://192.168.4.27/",
                    snippet = "The controller responds at 192.168.4.27",
                    source = "fixture",
                    confidence = 0.8
                )
            )
        )
        val network = targets.single { it.kind == VnicBringUpKind.NETWORK }
        assertEquals("192.168.4.27", network.id)
    }

    @Test
    fun bluetoothCandidateUsesDiscoveredMacAddress() {
        val targets = vnicDetectBringUpTargets(
            subject = "LED sign",
            text = "Find its Bluetooth GATT interface at AA:BB:CC:DD:EE:FF"
        )
        assertEquals("AA:BB:CC:DD:EE:FF", targets.single { it.kind == VnicBringUpKind.BLE }.id)
    }

    @Test
    fun noTransportIsInventedWithoutEvidence() {
        assertTrue(vnicDetectBringUpTargets("unknown object", "Tell me what this is").isEmpty())
    }
    @Test
    fun transportWordsAloneDoNotInventDeviceAddresses() {
        val targets = vnicDetectBringUpTargets(
            subject = "LED controller",
            text = "Find the LED controller using Bluetooth and Wi-Fi"
        )
        assertTrue(targets.isEmpty())
    }

}
