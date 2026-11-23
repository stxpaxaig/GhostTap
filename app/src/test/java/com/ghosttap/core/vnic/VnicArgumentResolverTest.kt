package com.ghosttap.core.vnic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VnicArgumentResolverTest {

    @Test
    fun extractsOnlyExplicitNetworkAndHardwareArguments() {
        val capability = VnicCapabilityInfo(
            id = "led.control",
            title = "Control LED sign",
            description = "Sends an explicit message and changes the GPIO state.",
            riskLevel = "CONTROL",
            verified = false,
            inputs = listOf("host", "port", "message", "pin", "state")
        )
        val result = VnicArgumentResolver.resolve(
            capability,
            VnicObservation(
                subject = "LED sign",
                text = "Turn LED GPIO4 on at 192.168.4.10 port 8080 and display \"HELLO WORLD\" on the sign"
            ),
            emptyList()
        )
        assertEquals("192.168.4.10", result.args["host"])
        assertEquals("8080", result.args["port"])
        assertEquals("HELLO WORLD", result.args["message"])
        assertEquals("GPIO4", result.args["pin"])
        assertEquals("on", result.args["state"])
        assertTrue(result.unresolvedInputs.isEmpty())
    }

    @Test
    fun usesResearchEvidenceForDiscoveredDeviceIdentifiers() {
        val capability = VnicCapabilityInfo(
            id = "ble.connect",
            title = "Connect to Bluetooth device",
            description = "Connects to a discovered GATT device.",
            riskLevel = "CONFIGURE",
            verified = false,
            inputs = listOf("targetId")
        )
        val result = VnicArgumentResolver.resolve(
            capability,
            VnicObservation("LED controller", "Connect to the LED controller"),
            listOf(
                VnicFinding(
                    query = "LED controller",
                    title = "Bluetooth device",
                    url = "",
                    snippet = "Advertised address AA:BB:CC:DD:EE:FF",
                    source = "fixture",
                    confidence = 0.8
                )
            )
        )
        assertEquals("AA:BB:CC:DD:EE:FF", result.args["targetId"])
        assertTrue(result.unresolvedInputs.isEmpty())
    }

    @Test
    fun doesNotInventOutboundPayloadFromControlInstruction() {
        val capability = VnicCapabilityInfo(
            id = "vesp32.send-message",
            title = "Send message to VirtualESP32",
            description = "Sends an explicit message to the practice board.",
            riskLevel = "WRITE",
            verified = false,
            inputs = listOf("text")
        )
        val result = VnicArgumentResolver.resolve(
            capability,
            VnicObservation("Practice board", "Turn the LED on"),
            emptyList()
        )
        assertTrue(result.args["text"].isNullOrBlank())
        assertEquals(listOf("text"), result.unresolvedInputs)
    }
}
