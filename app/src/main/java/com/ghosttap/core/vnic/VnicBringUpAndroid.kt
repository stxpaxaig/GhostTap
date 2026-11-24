package com.ghosttap.core.vnic

import com.ghosttap.core.BringUpKind
import com.ghosttap.core.BringUpPipeline
import com.ghosttap.core.BringUpTarget
import com.ghosttap.core.IntelEngine

/**
 * Android bring-up port — the VNIC core's bridge to the REAL
 * [BringUpPipeline.bringUp] (the existing zero-tap bring-up: connect ->
 * enumerate -> handshake -> map-controls -> READY, with the final
 * still-connected honesty check). The VNIC drives THIS in production; it
 * never invents a parallel bring-up.
 *
 * ESP32 targets go to the [VnicEsp32Bridge] instead (its guest lifecycle
 * is not a BringUpPipeline kind); every other kind maps 1:1 onto the
 * pipeline's own kinds. BLE and USB report held only after their live
 * connection checks. NFC is held only while the tag remains present after
 * the read. NETWORK performs ping + port discovery, then opens and retains a TCP socket
 * only on a port the scan actually observed open. It sends no unknown-protocol payload.
 */
class AndroidVnicBringUp(
    private val engine: IntelEngine,
    private val esp32: VnicEsp32Bridge? = null
) : VnicBringUpPort {

    override suspend fun bringUp(target: VnicBringUpTarget, note: String): VnicBringUpResult {
        if (target.kind == VnicBringUpKind.ESP32) {
            val bridge = esp32
                ?: return VnicBringUpResult(
                    ready = false, route = "", controlLines = emptyList(),
                    log = listOf("No VirtualESP32 bridge attached — ESP32 bring-up unavailable."),
                    connectionHeld = false,
                    failReason = "No VirtualESP32 bridge attached — ESP32 bring-up needs the VirtualESP32 app connected."
                )
            return bridge.bringUpEsp32().copy(
                targetKind = target.kind,
                targetId = target.id,
                targetTitle = target.title
            )
        }
        val kind = when (target.kind) {
            VnicBringUpKind.BLE -> BringUpKind.BLE
            VnicBringUpKind.NFC -> BringUpKind.NFC
            VnicBringUpKind.NETWORK -> BringUpKind.NETWORK
            VnicBringUpKind.USB -> BringUpKind.USB
            VnicBringUpKind.ESP32 -> error("unreachable — ESP32 handled above")
        }
        val result = BringUpPipeline.bringUp(
            BringUpTarget(kind, target.id, target.title, target.detail),
            engine
        )
        val networkHold = if (target.kind == VnicBringUpKind.NETWORK && result.ready) {
            runCatching { engine.holdNetworkSession(target.id) }
                .getOrElse { "TCP session could not be held: ${it.message ?: "connection failed"}." }
        } else ""
        val networkHeld = target.kind == VnicBringUpKind.NETWORK && engine.networkSessionHeld(target.id)
        val controls = result.descriptor?.summaryLines.orEmpty() + if (networkHeld) {
            engine.networkSessionControls(target.id)
        } else emptyList()
        return VnicBringUpResult(
            ready = result.ready && (target.kind != VnicBringUpKind.NETWORK || networkHeld),
            route = result.descriptor?.route ?: "",
            controlLines = controls,
            log = result.log + listOfNotNull(networkHold.takeIf { it.isNotBlank() }),
            connectionHeld = when (target.kind) {
                VnicBringUpKind.BLE, VnicBringUpKind.USB -> result.ready
                VnicBringUpKind.NFC -> result.ready && engine.nfcTagPresent()
                VnicBringUpKind.NETWORK -> networkHeld
                VnicBringUpKind.ESP32 -> false
            },
            failReason = result.failReason ?: if (target.kind == VnicBringUpKind.NETWORK && !networkHeld) {
                networkHold.ifBlank { "No observed open TCP port could be held; no port was guessed." }
            } else null,
            targetKind = target.kind,
            targetId = target.id,
            targetTitle = target.title
        )
    }
}
