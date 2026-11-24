package com.ghosttap.core.vnic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VnicNetworkProtocolTest {

    @Test
    fun identifiesAdvertisedHttpServiceWithStrongerEvidenceThanPortGuess() {
        val profiles = VnicNetworkProtocol.classify(
            host = "192.168.4.27",
            openPorts = listOf(8080),
            advertisedServices = listOf(
                VnicAdvertisedService(
                    name = "LED Sign Web Interface",
                    host = "192.168.4.27",
                    port = 8080,
                    type = "_http._tcp"
                )
            )
        )
        assertEquals(1, profiles.size)
        assertEquals("HTTP", profiles.single().protocol)
        assertEquals("advertised by mDNS/NSD", profiles.single().confidence)
        assertTrue(profiles.single().controls.any { it.contains("GET/read-only") })
        assertTrue(profiles.single().controls.any { it.contains("No POST") })
    }

    @Test
    fun labelsMqttPortAsHintAndDoesNotPublish() {
        val profile = VnicNetworkProtocol.classify(
            host = "192.168.4.10",
            openPorts = listOf(1883),
            advertisedServices = emptyList()
        ).single()
        assertEquals("MQTT broker", profile.protocol)
        assertTrue(profile.confidence.contains("not protocol-verified"))
        assertTrue(profile.controls.any { it.contains("No publish") })
    }

    @Test
    fun labelsModbusWithoutInventingRegisterWrites() {
        val profile = VnicNetworkProtocol.classify(
            host = "192.168.4.11",
            openPorts = listOf(502),
            advertisedServices = emptyList()
        ).single()
        assertEquals("Modbus TCP", profile.protocol)
        assertTrue(profile.controls.any { it.contains("No register writes") })
    }

    @Test
    fun unknownPortsRemainUnknownAndReceiveNoPayload() {
        val profile = VnicNetworkProtocol.classify(
            host = "192.168.4.12",
            openPorts = listOf(45678),
            advertisedServices = emptyList()
        ).single()
        assertEquals("Unknown TCP service", profile.protocol)
        assertTrue(profile.controls.any { it.contains("No payload is sent") })
    }

    @Test
    fun ignoresServicesForOtherHosts() {
        val profiles = VnicNetworkProtocol.classify(
            host = "192.168.4.27",
            openPorts = emptyList(),
            advertisedServices = listOf(
                VnicAdvertisedService("Printer", "192.168.4.28", 631, "_printer._tcp")
            )
        )
        assertTrue(profiles.isEmpty())
    }
}
