package com.ghosttap.core.vnic

import com.ghosttap.core.OpResult
import org.junit.Assert.assertEquals
import org.junit.Test

class VnicVerifyTest {
    @Test
    fun usbRefreshWithoutADeviceDoesNotClaimDiscovery() {
        val result = VnicVerify.verify(
            "usb.refresh",
            OpResult(true, "No USB device found", "")
        )
        assertEquals(VnicExecutionState.ATTEMPTED, result.state)
    }

    @Test
    fun usbRefreshWithOneDeviceVerifiesDiscovery() {
        val result = VnicVerify.verify(
            "usb.refresh",
            OpResult(true, "USB cable found — ready to connect", "")
        )
        assertEquals(VnicExecutionState.VERIFIED, result.state)
    }

    @Test
    fun usbRefreshWithMultipleDevicesVerifiesDiscovery() {
        val result = VnicVerify.verify(
            "usb.refresh",
            OpResult(true, "2 USB devices found — tap one to pick it", "")
        )
        assertEquals(VnicExecutionState.VERIFIED, result.state)
    }

    @Test
    fun usbSendPhoneTransmitEchoIsNotMistakenForDeviceReply() {
        val result = VnicVerify.verify(
            "usb.send",
            OpResult(
                true,
                "USB write completed; no device reply observed in the response window.",
                "PHONE → ESP32: AT"
            )
        )
        assertEquals(VnicExecutionState.ATTEMPTED, result.state)
    }

    @Test
    fun usbSendWithFreshDeviceReplyIsVerified() {
        val result = VnicVerify.verify(
            "usb.send",
            OpResult(
                true,
                "USB write completed. Recent device reply: OK",
                "OK"
            )
        )
        assertEquals(VnicExecutionState.VERIFIED, result.state)
    }

    @Test
    fun unansweredNetworkPingIsNotReportedAsConnected() {
        val result = VnicVerify.verify(
            "net.ping",
            OpResult(true, "192.168.1.20 didn't answer (it may block this check, or be offline).", "")
        )
        assertEquals(VnicExecutionState.ATTEMPTED, result.state)
    }

    @Test
    fun refusedTcpConnectionIsNotReportedAsConnected() {
        val result = VnicVerify.verify(
            "net.tcp_connect",
            OpResult(true, "Connection refused at 192.168.1.20:80.", "")
        )
        assertEquals(VnicExecutionState.ATTEMPTED, result.state)
    }

    @Test
    fun completedPortScanWithNoOpenPortsIsStillVerified() {
        val result = VnicVerify.verify(
            "net.port_scan",
            OpResult(true, "Port check — done. Open: none. Closed or blocked: 80, 443", "")
        )
        assertEquals(VnicExecutionState.VERIFIED, result.state)
    }

    @Test
    fun emptyAsyncNetworkFallbackIsNotReportedAsConnected() {
        val result = VnicVerify.verify(
            "net.ping",
            OpResult(true, "Ping finished with no output.", "")
        )
        assertEquals(VnicExecutionState.ATTEMPTED, result.state)
    }

    @Test
    fun missingNfcTapIsNotReportedAsSuccessfulRead() {
        val result = VnicVerify.verify(
            "nfc.read_summary",
            OpResult(true, "No tag tapped yet.", "")
        )
        assertEquals(VnicExecutionState.ATTEMPTED, result.state)
    }

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
    @Test
    fun udpDatagramWithoutReplyIsOnlyAttempted() {
        val output = "Sent 5 bytes to 192.168.1.20:1234. No confirmation that it arrived.\n" +
            "No reply within 3000ms (normal for UDP)."
        val result = VnicVerify.verify(
            "net.udp_send",
            OpResult(true, output, output)
        )
        assertEquals(VnicExecutionState.ATTEMPTED, result.state)
    }

    @Test
    fun udpDatagramWithActualTargetReplyIsVerified() {
        val output = "Sent 5 bytes to 192.168.1.20:1234.\n" +
            "Reply from 192.168.1.20:1234: OK"
        val result = VnicVerify.verify(
            "net.udp_send",
            OpResult(true, output, output)
        )
        assertEquals(VnicExecutionState.VERIFIED, result.state)
    }

}
