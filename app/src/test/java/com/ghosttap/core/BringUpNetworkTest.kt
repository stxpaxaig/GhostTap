package com.ghosttap.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BringUpNetworkTest {

    @Test
    fun openTcpPortsProveReachabilityWhenPingIsBlocked() {
        val decision = networkReachabilityDecision(
            pingAnswered = false,
            openTcpPorts = listOf(80, 443)
        )
        assertTrue(decision.reachable)
        assertTrue(decision.explanation.contains("did not answer"))
        assertTrue(decision.explanation.contains("80, 443"))
    }

    @Test
    fun pingAloneIsNotEnoughToClaimHeldTcpSession() {
        val decision = networkReachabilityDecision(
            pingAnswered = true,
            openTcpPorts = emptyList()
        )
        assertFalse(decision.reachable)
        assertTrue(decision.explanation.contains("persistent TCP session cannot be held"))
    }

    @Test
    fun noIcmpAndNoOpenPortsIsNotReachable() {
        val decision = networkReachabilityDecision(
            pingAnswered = false,
            openTcpPorts = emptyList()
        )
        assertFalse(decision.reachable)
    }

    @Test
    fun icmpAndOpenPortsBothCountAsVerifiedReachability() {
        val decision = networkReachabilityDecision(
            pingAnswered = true,
            openTcpPorts = listOf(8080, 80, 8080)
        )
        assertTrue(decision.reachable)
        assertTrue(decision.explanation.contains("80, 8080"))
    }

    @Test
    fun invalidPortsAreNotUsedAsReachabilityEvidence() {
        val decision = networkReachabilityDecision(
            pingAnswered = false,
            openTcpPorts = listOf(-1, 0, 70000)
        )
        assertFalse(decision.reachable)
    }
}
