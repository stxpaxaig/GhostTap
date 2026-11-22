package com.ghosttap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ghosttap.core.ops.AreaBState
import com.ghosttap.core.ops.Operation
import com.ghosttap.core.ops.OperationArea
import com.ghosttap.core.ops.OperationRegistry
import com.ghosttap.core.ops.OperationResult
import com.ghosttap.core.ops.OperationRunner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Package 0b — the per-function "pick what you want to do" screen.
 *
 * ZERO-CONFIG (2026-11-06): this screen shows ONE section only, and
 * there is no standalone global list any more — the "what_to_do"
 * route with no area was REMOVED, and [area] is now required. The
 * content that used to live in the global list lives here, inside
 * each function, already configured for that section's devices.
 * Each row shows the outcome and what it needs. Tapping one runs it
 * through the [OperationRunner] with live step progress shown; a
 * screen-first operation opens its screen through the same runner.
 *
 * Title is the area's own label — no original title to preserve
 * here. The six main-screen buttons keep their original wording.
 */
@Composable
fun OperationEntryScreen(
    runner: OperationRunner,
    onBack: () -> Unit,
    area: OperationArea
) {
    val operations = OperationRegistry.byArea(area)
    val scope = rememberCoroutineScope()
    val progress by runner.progress.collectAsState()
    val result by runner.result.collectAsState()
    var runningId by remember { mutableStateOf<String?>(null) }
    var lastOp by remember { mutableStateOf<Operation?>(null) }
    // Package 2 (A-04) — live count while the "find the strongest" scan
    // runs: the runner only reports step labels, so a one-second ticker
    // re-reads how many devices the scan has found so far.
    var scanTick by remember { mutableStateOf(0) }
    LaunchedEffect(runningId) {
        if (runningId == "scan.find-strongest") {
            while (true) {
                delay(1000)
                scanTick++
            }
        }
    }

    fun startRun(op: Operation) {
        if (runningId != null) return
        runningId = op.id
        lastOp = op
        runner.clearResult()
        scope.launch {
            runner.run(op)
            runningId = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .background(Bg),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            area.label,
            color = Accent,
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = FontFamily.Monospace
        )

        TerminalButton("Back", onBack)

        val operatorGuidance = when (area) {
            OperationArea.BLE -> Triple(
                "Bluetooth discovery exposes nearby advertisements and device identifiers. BLE GATT and Classic Bluetooth RFCOMM are distinct transports; a target may support one or both.",
                "Select a discovered target, connect using its supported transport, then inspect services and characteristics. Read, write, subscribe, pair, and disconnect using the controls shown for that target.",
                "Correlate advertisements, services, characteristic properties, signal observations, and actual responses to infer device role and discover useful controls."
            )
            OperationArea.USB -> Triple(
                "USB tools communicate with attached USB and serial hardware through Ghost Tap's USB/UART subsystem. Device descriptors and driver matches identify the available interface.",
                "Select a device, inspect VID/PID and interface details, connect at the required serial settings, then send and receive UART data or use supported firmware controls.",
                "Use descriptors, serial output, firmware metadata, and observed responses to identify protocols, baud rates, board families, and useful next diagnostics."
            )
            OperationArea.NETWORK -> Triple(
                "Network tools use the phone's active network interfaces and direct TCP, UDP, DNS, HTTP, and host-discovery functions.",
                "Provide the host, port, protocol, and payload required by each tool. Run discovery, ping, port checks, DNS, traceroute, or socket tests separately and inspect the returned evidence.",
                "Correlate reachable hosts, open ports, service banners, DNS records, route hops, and responses to map the environment and choose the next probe."
            )
            OperationArea.NFC -> Triple(
                "NFC tools use the phone's reader/writer interface and the presented tag's actual technology and protocol response.",
                "Present a tag to the phone, inspect the detected technology, then choose a supported read, write, command, copy, or emulation operation.",
                "Interpret tag technology, identifiers, readable records, raw responses, and command behavior to determine what the tag supports and what to test next."
            )
            OperationArea.VISUAL -> Triple(
                "Visual tools use a live camera preview or selected image to extract text, barcodes, QR data, labels, and component markings.",
                "Capture or select an image, review extracted findings, then send a chosen identifier to a relevant lookup, network, or Bluetooth workflow.",
                "Combine OCR, code data, part markings, image context, and reference research. Keep uncertain matches visibly marked as candidates and refine them as evidence arrives."
            )
            OperationArea.INVESTIGATIONS -> Triple(
                "The intelligence workspace coordinates registered Ghost Tap capabilities and the active target's observations, findings, and investigation history.",
                "Describe an objective, review the proposed plan, select individual operations, run or repeat procedures, and inspect the evidence timeline.",
                "Maintain target context, research unfamiliar identifiers and protocols, compare observations, explain why each tool is relevant, and adapt the next steps to new evidence."
            )
            OperationArea.TERMUX -> Triple(
                "Ghost Tap's Termux bridge communicates with the installed Termux runtime through its local TCP/JSON bridge and helper scripts. The helper library reports capabilities and returns execution results.",
                "Check bridge health, list available capabilities and scripts, refresh the library, then choose a script or enter a command through the existing Termux controls. Inspect stdout, stderr, and exit status.",
                "Use the runtime's available packages and scripts as an extensible local toolset. Discover installed tools, inspect their help and versions, and use results to select or compose the next operation."
            )
            OperationArea.SYSTEM -> Triple(
                "System operations expose Ghost Tap's own registered app, navigation, intelligence, and runtime functions.",
                "Choose a named operation from this list. Its prerequisites, execution progress, result, and evidence are shown here.",
                "Use the live capability registry as the source of truth; inspect what is registered, identify missing integrations, and connect new functions through the existing operation framework."
            )
        }
        OperatorCapabilityPanel(
            area = area,
            connectionGuide = operatorGuidance.first,
            controlGuide = operatorGuidance.second,
            intelligenceGuide = operatorGuidance.third,
            liveState = "${operations.size} operations registered in this section • Current operation: ${lastOp?.title ?: "none"} • Running: ${runningId ?: "none"}"
        )

        // PIECE 2 (2026-11-18) — the "pick what you want to happen"
        // card is gone: the heading above says the section, and each
        // row below says what it does.
        if (runningId != null) {
            PanelBox("WORKING") {
                Text(
                    progress.step.label,
                    color = Accent,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress.step.fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = Accent,
                    trackColor = Color(0xFF101010)
                )
                if (runningId == "scan.find-strongest") {
                    val foundSoFar = remember(scanTick) {
                        runner.operationContext.ble?.allTargets?.size ?: 0
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Devices found so far: $foundSoFar",
                        color = Dim,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                // Package 3 (B-012/B-013) — live progress of a bulk
                // read/listen run: the operation updates AreaBState as
                // channels finish, and Compose re-reads it here.
                if (runningId == "ble.read-everything" || runningId == "ble.listen-everything") {
                    if (AreaBState.bulkProgressLabel.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            if (AreaBState.bulkProgressTotal > 0) {
                                "${AreaBState.bulkProgressLabel} ${AreaBState.bulkProgressDone} of ${AreaBState.bulkProgressTotal}"
                            } else {
                                AreaBState.bulkProgressLabel
                            },
                            color = Dim,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        val currentResult = result
        if (currentResult != null) {
            PanelBox("RESULT") {
                when (currentResult) {
                    is OperationResult.Done -> {
                        Text(
                            currentResult.reason,
                            color = Accent,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (currentResult.evidence.isNotBlank()) {
                            Text(
                                currentResult.evidence,
                                color = Dim,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        TerminalButton("Got it") { runner.clearResult() }
                    }
                    is OperationResult.Failed -> {
                        Text(
                            "Didn't work: ${currentResult.reason}",
                            color = Color.Red,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (currentResult.evidence.isNotBlank()) {
                            Text(
                                currentResult.evidence,
                                color = Dim,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        TerminalButton("Got it") { runner.clearResult() }
                    }
                    is OperationResult.NeedsUserAction -> {
                        Text(
                            currentResult.prompt,
                            color = Accent,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (currentResult.actionLabel != null) {
                            TerminalButton(currentResult.actionLabel) {
                                currentResult.action?.invoke()
                                runner.clearResult()
                            }
                        }
                        TerminalButton("Got it") { runner.clearResult() }
                    }
                    is OperationResult.NeedsUserChoice -> {
                        Text(
                            currentResult.reason,
                            color = Accent,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                        currentResult.choices.forEach { choice ->
                            TerminalButton(choice) {
                                runner.operationContext.pendingChoice = choice
                                runner.clearResult()
                                lastOp?.let { startRun(it) }
                            }
                        }
                        TerminalButton("Not now") { runner.clearResult() }
                    }
                }
            }
        }

        // PIECE 2 (2026-11-18) — the "NOTHING HERE YET" panel is gone:
        // it only described an empty section and pointed back at the
        // home screen. Every section now has real operations, so an
        // empty section just shows its heading and Back.
        if (operations.isNotEmpty()) {
            // One section only — the heading above already says the area,
            // so the rows go straight on the screen with no extra box.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                operations.forEach { op -> OperationRow(op) { startRun(it) } }
            }
        }
    }
}

/**
 * One pickable outcome row. Shared by the all-areas list and the
 * single-area list so both show the same thing.
 */
@Composable
private fun OperationRow(op: Operation, onRun: (Operation) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF101010), RoundedCornerShape(6.dp))
            .clickable { onRun(op) }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            op.title,
            color = Accent,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            op.description,
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        if (op.prerequisites.isNotEmpty()) {
            Text(
                "Needs: ${op.prerequisites.joinToString(", ")}",
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
