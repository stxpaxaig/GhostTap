package com.ghosttap.core.vnic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VnicReasoningPlannerTest {

    @Test
    fun hyphenatedWifiFindsNetworkOperations() {
        val capabilities = listOf(
            VnicCapabilityInfo(
                id = "net.scan-wifi",
                title = "Find devices on Wi-Fi",
                description = "Discovers devices on the local wireless network.",
                riskLevel = "READ",
                verified = true,
                category = "NETWORK"
            )
        )
        val plan = LocalRuleReasoner().planFor(
            "Find devices on Wi-Fi",
            capabilities,
            emptyList(),
            emptyList()
        )
        assertEquals(listOf("net.scan-wifi"), plan)
    }

    @Test
    fun explicitControlGoalCanSelectControlCapability() {
        val capabilities = listOf(
            VnicCapabilityInfo(
                id = "net.read-status",
                title = "Read LED status",
                description = "Reads the current LED state.",
                riskLevel = "READ",
                verified = true,
                category = "NETWORK"
            ),
            VnicCapabilityInfo(
                id = "led.gpio4.control",
                title = "Turn LED GPIO4 on",
                description = "Changes the requested LED output state.",
                riskLevel = "CONTROL",
                verified = false,
                category = "ESP32",
                inputs = listOf("pin", "state")
            )
        )
        val plan = LocalRuleReasoner().planFor(
            "Turn LED GPIO4 on",
            capabilities,
            emptyList(),
            emptyList()
        )
        assertTrue(plan.contains("led.gpio4.control"))
    }

    @Test
    fun plannerCanReturnMoreThanFiveRelevantOperations() {
        val capabilities = (1..8).map { index ->
            VnicCapabilityInfo(
                id = "net.wifi.action$index",
                title = "Wireless network action $index",
                description = "Wi-Fi network discovery and inspection operation.",
                riskLevel = "READ",
                verified = true,
                category = "NETWORK"
            )
        }
        val plan = LocalRuleReasoner().planFor(
            "Investigate Wi-Fi network",
            capabilities,
            emptyList(),
            emptyList()
        )
        assertEquals(8, plan.size)
    }
    @Test
    fun investigationDoesNotSelectWriteOrControlOperations() {
        val capabilities = listOf(
            VnicCapabilityInfo(
                id = "led.read-status",
                title = "Read LED status",
                description = "Reads the current LED state.",
                riskLevel = "READ",
                verified = true,
                category = "ESP32"
            ),
            VnicCapabilityInfo(
                id = "led.gpio4.control",
                title = "Turn LED GPIO4 on",
                description = "Changes the requested LED output state.",
                riskLevel = "CONTROL",
                verified = false,
                category = "ESP32",
                inputs = listOf("pin", "state")
            )
        )
        val plan = LocalRuleReasoner().planFor(
            "Investigate the LED sign and identify its controls",
            capabilities,
            emptyList(),
            emptyList()
        )
        assertTrue(plan.contains("led.read-status"))
        assertFalse(plan.contains("led.gpio4.control"))
    }

    @Test
    fun coreRejectsRemoteWritePlanWhenObjectiveOnlyRequestsInvestigation() = kotlinx.coroutines.runBlocking {
        val reasoner = object : VnicReasoningProvider {
            override val name = "test reasoner"
            override fun hypotheses(observation: String, knownFacts: List<String>) = emptyList<String>()
            override fun planFor(goal: String, capabilityIds: List<String>) = listOf("led.gpio4.control")
        }
        val researcher = object : VnicResearchPort {
            override suspend fun research(query: String) =
                VnicResearchReport(query, emptyList(), emptyList(), 0, 0)
        }
        val core = VnicCore(
            memory = InMemoryVnicMemory(),
            researcher = researcher,
            reasoner = reasoner
        )
        val executed = mutableListOf<String>()
        val result = core.process(
            VnicObservation("LED sign", "Investigate the LED sign and identify its controls"),
            capabilities = listOf(
                VnicCapabilityInfo(
                    id = "led.gpio4.control",
                    title = "Turn LED GPIO4 on",
                    description = "Changes the LED output.",
                    riskLevel = "CONTROL",
                    verified = false,
                    category = "ESP32",
                    inputs = listOf("pin", "state")
                )
            ),
            executor = object : VnicExecutor {
                override suspend fun execute(capabilityId: String, args: Map<String, String>): VnicExecutionOutcome {
                    executed += capabilityId
                    return VnicExecutionOutcome(VnicExecutionState.VERIFIED, "test")
                }
            }
        )
        assertTrue(result.phases.any { it.contains("withheld because the objective did not explicitly request") })
        assertTrue(executed.isEmpty())
    }

}
