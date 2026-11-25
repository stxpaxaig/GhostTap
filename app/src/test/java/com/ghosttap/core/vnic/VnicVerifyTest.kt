package com.ghosttap.core.vnic

import com.ghosttap.core.OpResult
import org.junit.Assert.assertEquals
import org.junit.Test

class VnicVerifyTest {
    @Test
    fun uartSendWithoutReplyIsOnlyAttempted() {
        val result = VnicVerify.verify(
            "vesp32.send_uart",
            OpResult(true, "Sent 'AT' — no UART reply arrived yet.", "")
        )
        assertEquals(VnicExecutionState.ATTEMPTED, result.state)
    }

    @Test
    fun uartSendWithActualReplyIsConnectedButNotAutomaticallyVerified() {
        val result = VnicVerify.verify(
            "vesp32.send_uart",
            OpResult(true, "UART reply: RX:OK", "RX:OK")
        )
        assertEquals(VnicExecutionState.CONNECTED, result.state)
    }
}
