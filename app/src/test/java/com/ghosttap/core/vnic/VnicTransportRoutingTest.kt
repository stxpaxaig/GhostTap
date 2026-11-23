package com.ghosttap.core.vnic

import kotlinx.coroutines.runBlocking
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

    @Test
    fun discoveredBluetoothAddressTriggersBringUpAfterScan() = runBlocking {
        var scanCalls = 0
        var bringUpCalls = 0
        val researcher = object : VnicResearchPort {
            override suspend fun research(query: String) =
                VnicResearchReport(query, emptyList(), emptyList(), 0, 0)
        }
        val core = VnicCore(memory = InMemoryVnicMemory(), researcher = researcher)
        val result = core.process(
            VnicObservation("Bluetooth controller", "Find the Bluetooth controller"),
            capabilities = listOf(
                VnicCapabilityInfo(
                    id = "ble.scan",
                    title = "Find Bluetooth devices",
                    description = "Scans for nearby Bluetooth devices and returns their addresses.",
                    riskLevel = "READ",
                    verified = false,
                    category = "BLE"
                )
            ),
            executor = object : VnicExecutor {
                override suspend fun execute(capabilityId: String, args: Map<String, String>): VnicExecutionOutcome {
                    scanCalls++
                    return VnicExecutionOutcome(
                        VnicExecutionState.VERIFIED,
                        "Found Bluetooth controller AA:BB:CC:DD:EE:FF",
                        "AA:BB:CC:DD:EE:FF"
                    )
                }
            },
            bringUp = object : VnicBringUpPort {
                override suspend fun bringUp(target: VnicBringUpTarget, observation: String) : VnicBringUpResult {
                    bringUpCalls++
                    assertEquals(VnicBringUpKind.BLE, target.kind)
                    assertEquals("AA:BB:CC:DD:EE:FF", target.id)
                    return VnicBringUpResult(
                        ready = true,
                        route = "ble://AA:BB:CC:DD:EE:FF",
                        controlLines = listOf("read", "write", "notify"),
                        log = listOf("connected", "services discovered", "controls mapped"),
                        connectionHeld = true,
                        failReason = null
                    )
                }
            }
        )
        assertEquals(1, scanCalls)
        assertEquals(1, bringUpCalls)
        assertEquals(VnicExecutionState.CONTROLLABLE, result.executionState)
        assertTrue(result.bringUp?.ready == true)
        assertTrue(result.controlsMapped.contains("write"))
    }

}
