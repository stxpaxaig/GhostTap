package com.ghosttap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ghosttap.UsbUartViewModel
import com.ghosttap.core.TvCandidate
import com.ghosttap.core.investigate.InvestigationSource
import com.ghosttap.core.investigate.LiveTargetStore

private val UartWarn = Color(0xFFFFAA44)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UsbUartScreen(
    viewModel: UsbUartViewModel,
    onBack: () -> Unit,
    onSelectFirmware: () -> Unit,
    onOpenWhatToDo: (() -> Unit)? = null,
    // MASTER PROMPT: live-target card wiring (thinking engine,
    // command runner, open-page). Null-safe: the card renders with
    // the app's own rules when the engine is not wired.
    intelVm: com.ghosttap.IntelViewModel? = null,
    onRunLiveCommand: ((String, String?) -> Unit)? = null,
    onOpenRoute: ((String) -> Unit)? = null,
    // ZERO-CONFIG — the intent box ("what I'm tryna do"). Null = no intent box.
    onIntent: ((String) -> Unit)? = null,
    intentAnswer: String = "",
    // UNIFIED BUILD (2026-11-18) — the same do-it command box as home,
    // wired to the same engine: typing here runs it no matter which
    // page this is. Null = no box (standalone use).
    commandStatusLine: String = "",
    commandRequest: String = "",
    commandLines: List<String> = emptyList(),
    commandBusy: Boolean = false,
    commandTvCandidates: List<TvCandidate> = emptyList(),
    commandShowTryAgain: Boolean = false,
    onDoIt: ((String) -> Unit)? = null,
    onPickTv: ((TvCandidate) -> Unit)? = null,
    onTryAgain: (() -> Unit)? = null,
    onClearCommand: (() -> Unit)? = null,
    showTermuxSetup: Boolean = false,
    onTermuxSetup: (() -> Unit)? = null
) {
    val clipboard = LocalClipboardManager.current

    // ZERO-CONFIG FIX (2026-11-06): the intent answer line is shared
    // across screens — clear it when this screen opens so a stale
    // outcome from another screen never shows here.
    LaunchedEffect(Unit) { intelVm?.clearCommandAnswer() }

    // ZERO-CONFIG — SELECT → AUTO-CONNECT (2026-11-06): a board that
    // is plugged in and already permitted connects over the cable on
    // its own, once per board — no CONNECT tap. Permission is the one
    // genuine gate: Android only grants USB access through its own
    // prompt, so a board without permission keeps the ALLOW USB ACCESS
    // button instead of auto-connecting, and connects by itself the
    // moment permission lands (the prompt's callback refreshes the
    // state this effect reads). A board the user deliberately
    // disconnected is NEVER auto-connected again: that choice lives in
    // the thinking engine's ViewModel
    // (IntelViewModel.manuallyDisconnectedIds), so it survives leaving
    // and re-entering — until the user taps CONNECT himself.
    var usbAutoConnectFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel.selectedDevice, viewModel.isConnected, viewModel.permissionNeeded) {
        val dev = viewModel.selectedDevice
        val key = dev?.vidPidString()
        // Connected again (by any path) clears a stale manual-
        // disconnect entry, so a later drop that was NOT the user's
        // choice can still auto-reconnect.
        if (viewModel.isConnected && key != null) intelVm?.clearManualDisconnect(key)
        if (dev != null && !viewModel.isConnected && !viewModel.permissionNeeded &&
            key != null && usbAutoConnectFor != key &&
            intelVm?.wasManuallyDisconnected(key) != true
        ) {
            usbAutoConnectFor = key
            viewModel.connect()
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
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "USB UART / ESP32",
                color = Accent,
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            UartButton("BACK", onBack)
        }

        onOpenWhatToDo?.let { UartButton("What do you want to do?", it) }

        // UNIFIED BUILD (2026-11-18) — the same do-it command box as
        // home: type what you want, Ghost Tap does it right here.
        CommandBox(
            statusLine = commandStatusLine,
            request = commandRequest,
            lines = commandLines,
            busy = commandBusy,
            tvCandidates = commandTvCandidates,
            showTryAgain = commandShowTryAgain,
            onSubmit = onDoIt ?: {},
            onPickTv = onPickTv ?: {},
            onTryAgain = onTryAgain ?: {},
            onClear = onClearCommand ?: {},
            showTermuxSetup = showTermuxSetup,
            onTermuxSetup = onTermuxSetup
        )

        // DISCOVER (USB): one tap checks what is plugged in right now —
        // no typing, no setup. Runs the same checks the plug-in trigger
        // runs, for every device found, including ones already known.
        // Findings list below; a permitted board connects by itself.
        GhostActionCard(
            "Investigate the unfamiliar",
            "Checks what is plugged in right now — no typing, no setup.",
            "Investigate the unfamiliar",
            onClick = { viewModel.discoverUsb() }
        )
        viewModel.usbDiscoverNote?.let { note ->
            Text(note, color = Dim, fontFamily = FontFamily.Monospace)
        }

        // MASTER PROMPT — LIVE TARGETS, not history: every live
        // target for plugged-in boards, newest first — a re-check
        // refreshes a board's card in place, so the list never stacks
        // duplicates of the same board. Connect runs the existing USB
        // connect path; a capability button runs only an existing USB
        // action, and anything else stays plain words on the card.
        // New on the card: what the thinking engine says, and — once
        // connected — the commands you can tap on the board. Nothing
        // here runs on its own — the user presses the button.
        val usbLive = remember(viewModel.latestUsbReport) {
            LiveTargetStore.all().filter { it.source == InvestigationSource.USB }
        }
        usbLive.forEach { live ->
            LiveTargetCard(
                target = live,
                intelVm = intelVm,
                isConnected = viewModel.isConnected,
                onConnect = { if (!viewModel.isConnected) viewModel.connect() },
                onControl = { control -> viewModel.runUsbReportControl(control) },
                onRunCommand = onRunLiveCommand,
                onOpenRoute = onOpenRoute,
                commandAnswer = intelVm?.lastCommandAnswer.orEmpty()
            )
        }

        // PIECE 2 (2026-11-18) — the "what this screen does" card is
        // gone: it only re-listed what the cards below already do.
        // USB Device Section
        UartPanelBox(title = "USB BRIDGE") {
            Text(viewModel.usbStatus, color = Dim, fontFamily = FontFamily.Monospace)

            Spacer(modifier = Modifier.height(8.dp))

            viewModel.selectedDevice?.let { dev ->
                Text("Found: ${dev.manufacturer} ${dev.product}", color = Accent, fontFamily = FontFamily.Monospace)
                Text("Device code: ${dev.vidPidString()}", color = Dim, fontFamily = FontFamily.Monospace)
                Text("Connection type: ${dev.driverName}", color = Dim, fontFamily = FontFamily.Monospace)
                Text(
                    "Phone permission: ${if (dev.hasPermission || !viewModel.permissionNeeded) "Allowed" else "Needed"}",
                    color = if (dev.hasPermission || !viewModel.permissionNeeded) Accent else UartWarn,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            GhostActionCard(
                "CHECK AGAIN",
                "Looks again for a board on the USB cable.",
                "CHECK AGAIN",
                onClick = { viewModel.refreshDevices() }
            )
            if (viewModel.permissionNeeded) {
                GhostActionCard(
                    "ALLOW USB ACCESS",
                    "Lets the phone use the plugged-in board.",
                    "ALLOW USB ACCESS",
                    onClick = { viewModel.requestPermission() }
                )
            } else if (!viewModel.isConnected) {
                GhostActionCard(
                    "CONNECT",
                    "Connects to the plugged-in board.",
                    "CONNECT",
                    onClick = { viewModel.connect() }
                )
            } else {
                // ZERO-CONFIG FIX (2026-11-06): a manual DISCONNECT
                // is the user's decision — remember it so
                // re-entering this screen never auto-connects this
                // board again.
                GhostActionCard(
                    "DISCONNECT",
                    "Closes the USB link to the board.",
                    "DISCONNECT",
                    onClick = {
                        viewModel.selectedDevice?.vidPidString()?.let { intelVm?.noteManualDisconnect(it) }
                        viewModel.disconnect()
                    }
                )
            }
            // Op "usb.describe-board": no button on the page before —
            // the board's details were shown as text only.
            GhostActionCard(
                "What is this plugged-in board?",
                "Says what the plugged-in board is — who made it, which chip talks for it, and whether the phone is allowed to use it — in plain words, not codes.",
                "What is this plugged-in board?",
                onClick = { onRunLiveCommand?.invoke("usb.describe-board", null) },
                enabled = onRunLiveCommand != null
            )
        }

        if (viewModel.isConnected) {
            // Serial Terminal Section
            UartPanelBox(title = "SERIAL TERMINAL") {
                // Baud rate selector
                GhostActionCard(
                    "Speed",
                    "How fast the board and the phone talk to each other."
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val rates = listOf(9600, 115200, 230400, 460800)
                        rates.forEach { rate ->
                            val selected = viewModel.baudRate == rate
                            UartButton(
                                text = "$rate",
                                onClick = { viewModel.changeBaudRate(rate) },
                                highlighted = selected
                            )
                        }
                    }
                    Text("Sent as plain text, 8 bits at a time", color = Dim, fontFamily = FontFamily.Monospace)
                }
                Spacer(modifier = Modifier.height(4.dp))
                // AUTO-BAUD: find the right speed by itself.
                GhostActionCard(
                    "FIND THE RIGHT SPEED",
                    "Tries the speeds one by one and picks the one where the replies come back as clean text.",
                    if (viewModel.isAutoBaudRunning) "TRYING SPEEDS…" else "FIND THE RIGHT SPEED",
                    onClick = { viewModel.autoBaud() }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // RX output
                Text("Replies from the device:", color = Dim, fontFamily = FontFamily.Monospace)
                Text(
                    viewModel.rxText.ifEmpty { "(nothing back yet)" },
                    color = Accent,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0A0A0A), RoundedCornerShape(4.dp))
                        .padding(8.dp)
                        .height(150.dp)
                        .verticalScroll(rememberScrollState())
                )
                // Op "usb.watch-replies": no button on the page before —
                // replies were shown in the box above only.
                GhostActionCard(
                    "Is the board talking back?",
                    "Watches the cable for 10 seconds and reports anything the board sends back.",
                    "Is the board talking back?",
                    onClick = { onRunLiveCommand?.invoke("usb.watch-replies", null) },
                    enabled = onRunLiveCommand != null
                )

                Spacer(modifier = Modifier.height(8.dp))

                // TX input
                GhostActionCard(
                    "Send a message to the board",
                    "Sends exactly what you type, letter for letter."
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = viewModel.txText,
                            onValueChange = viewModel::updateTxText,
                            label = { Text("Type a message to send...") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        UartButton("SEND", viewModel::sendTx)
                    }
                }

                GhostActionCard(
                    if (viewModel.hexMode) "CODE MODE" else "TEXT MODE",
                    "Switches between plain words and code pairs.",
                    if (viewModel.hexMode) "CODE MODE" else "TEXT MODE",
                    onClick = { viewModel.toggleHexMode() }
                )
                GhostActionCard(
                    "CLEAR MESSAGES",
                    "Empties the message box.",
                    "CLEAR MESSAGES",
                    onClick = { viewModel.clearTerminal() }
                )

                // Package 5 (D-10) — quick messages, sent-before list,
                // and the new-line choice. Titles unchanged.
                Spacer(modifier = Modifier.height(4.dp))
                GhostActionCard(
                    "Quick messages",
                    "Tap one to send it right away."
                ) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        viewModel.commandPresets.forEach { (text, does) ->
                            UartButton(text, {
                                viewModel.updateTxText(text)
                                viewModel.sendTx()
                            })
                        }
                    }
                    Text(
                        viewModel.commandPresets.joinToString(" · ") { (text, does) -> "$text — $does" },
                        color = Dim, fontFamily = FontFamily.Monospace
                    )
                }
                if (viewModel.commandHistory.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    GhostActionCard(
                        "Sent before",
                        "Tap one to send it again."
                    ) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            viewModel.commandHistory.take(5).forEach { sent ->
                                UartButton(sent, {
                                    viewModel.updateTxText(sent)
                                    viewModel.sendTx()
                                })
                            }
                        }
                        UartButton("FORGET SENT MESSAGES", viewModel::clearCommandHistory)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                GhostActionCard(
                    "ADD A NEW LINE AT THE END: ${if (viewModel.sendNewline) "ON" else "OFF"}",
                    "Most boards only answer when the message ends with a new line.",
                    "ADD A NEW LINE AT THE END: ${if (viewModel.sendNewline) "ON" else "OFF"}",
                    onClick = { viewModel.toggleSendNewline() }
                )
            }

            // Control Lines Section
            UartPanelBox(title = "CONTROL LINES") {
                Text(
                    "These switch the USB cable's control wires on and off.",
                    color = Dim,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(8.dp))
                GhostActionCard(
                    "Wire DTR: ${if (viewModel.dtrAsserted) "ON" else "OFF"}",
                    "Switches the board's boot wire — ON means the board starts in load mode next time it restarts.",
                    "Wire DTR: ${if (viewModel.dtrAsserted) "ON" else "OFF"}",
                    onClick = { viewModel.setDtr(!viewModel.dtrAsserted) }
                )
                GhostActionCard(
                    "Wire RTS: ${if (viewModel.rtsAsserted) "ON" else "OFF"}",
                    "Switches the board's reset wire — ON holds the board in reset.",
                    "Wire RTS: ${if (viewModel.rtsAsserted) "ON" else "OFF"}",
                    onClick = { viewModel.setRts(!viewModel.rtsAsserted) }
                )
                // Op "usb.wires-what-they-do": no button on the page
                // before — the wires were only described in one line.
                GhostActionCard(
                    "What do the control wires do?",
                    "Explains the two control wires in plain words and says what each one is doing right now.",
                    "What do the control wires do?",
                    onClick = { onRunLiveCommand?.invoke("usb.wires-what-they-do", null) },
                    enabled = onRunLiveCommand != null
                )
            }

            // ESP32 Programmer Section
            UartPanelBox(title = "ESP32 PROGRAMMER") {
                Text(viewModel.esp32Status, color = Accent, fontFamily = FontFamily.Monospace)
                viewModel.esp32ChipInfo?.let {
                    Text("Chip: $it", color = Dim, fontFamily = FontFamily.Monospace)
                }

                Spacer(modifier = Modifier.height(8.dp))

                GhostActionCard(
                    "TEST THE BOARD",
                    "Checks if the board answers.",
                    "TEST THE BOARD",
                    onClick = { viewModel.testEsp32() }
                )
                // Op "usb.read-board-setting": no button on the page
                // before.
                GhostActionCard(
                    "Read the board's ID",
                    "Reads the chip's ID and names the chip when Ghost Tap knows it.",
                    "Read the board's ID",
                    onClick = { onRunLiveCommand?.invoke("usb.read-board-setting", null) },
                    enabled = onRunLiveCommand != null
                )

                Spacer(modifier = Modifier.height(8.dp))

                GhostActionCard(
                    "Start-up mode",
                    "Picks how the board starts next time."
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        UartButton("PUT BOARD IN LOAD MODE", viewModel::enterDownloadMode)
                        UartButton("RESTART BOARD", viewModel::resetBoard)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Firmware selection
                Text("Software to load:", color = Dim, fontFamily = FontFamily.Monospace)
                if (viewModel.firmwareName != null) {
                    Text(
                        "Picked: ${viewModel.firmwareName}",
                        color = Accent,
                        fontFamily = FontFamily.Monospace
                    )
                    Text("Ready to load onto the board", color = Dim, fontFamily = FontFamily.Monospace)
                    // Package 5 (D-20) — the check result for the picked file.
                    viewModel.firmwareValidation?.let { check ->
                        Text(
                            "Check: ${check.summary}",
                            color = Accent,
                            fontFamily = FontFamily.Monospace
                        )
                        if (check.issues.isNotEmpty()) {
                            Text(
                                "Notes: ${check.issues.joinToString("; ")}",
                                color = UartWarn,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                } else {
                    Text("No software picked yet", color = Dim, fontFamily = FontFamily.Monospace)
                }

                GhostActionCard(
                    "PICK SOFTWARE FILE",
                    "Opens the file picker to choose the software file (.bin) to load onto the board. Once picked, it is checked automatically.",
                    "PICK SOFTWARE FILE",
                    onClick = { onSelectFirmware() }
                )
                // Op "usb.check-software-file": no button on the page
                // before — the picked file's check result was shown as
                // text only.
                GhostActionCard(
                    "Check the software file",
                    "Checks the picked file is a real board file before it goes anywhere near the board.",
                    "Check the software file",
                    onClick = { onRunLiveCommand?.invoke("usb.check-software-file", null) },
                    enabled = onRunLiveCommand != null
                )

                // Package 5 (D-19) — where on the board the software goes.
                // The usual spot is the app spot; the others are only for
                // bootloader or settings-table files.
                Spacer(modifier = Modifier.height(8.dp))
                GhostActionCard(
                    "Where on the board",
                    "Picks where on the board the software goes. The usual spot is the app spot; the others are only for bootloader or settings-table files."
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        UartButton(
                            "APP (USUAL)",
                            { viewModel.pickFlashOffset(0x10000) },
                            highlighted = viewModel.flashOffset == 0x10000
                        )
                        UartButton(
                            "BOOTLOADER",
                            { viewModel.pickFlashOffset(0x0) },
                            highlighted = viewModel.flashOffset == 0x0
                        )
                        UartButton(
                            "SETTINGS TABLE",
                            { viewModel.pickFlashOffset(0x8000) },
                            highlighted = viewModel.flashOffset == 0x8000
                        )
                    }
                }
                if (viewModel.flashOffset != 0x10000) {
                    Text(
                        "Warning: this writes the file somewhere other than the usual app spot. Only use this for a bootloader or settings-table file — the wrong spot can stop the board from starting.",
                        color = UartWarn,
                        fontFamily = FontFamily.Monospace
                    )
                }

                if (viewModel.firmwareName != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    GhostActionCard(
                        "LOAD IT ONTO THE BOARD",
                        "Wipes the board and writes the new software onto it.",
                        "LOAD IT ONTO THE BOARD",
                        onClick = { viewModel.requestFlashConfirm() }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    GhostActionCard(
                        "CHECK WHAT'S ON THE BOARD",
                        "Checks the software already on the board matches the picked file.",
                        "CHECK WHAT'S ON THE BOARD",
                        onClick = { viewModel.verifyFlashMd5() }
                    )
                    viewModel.flashVerifyResult?.let {
                        Text(it, color = Accent, fontFamily = FontFamily.Monospace)
                    }
                }

                viewModel.flashProgress?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, color = UartWarn, fontFamily = FontFamily.Monospace)
                    if (viewModel.flashPercent > 0) {
                        Text("${viewModel.flashPercent}%", color = Accent, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            // Details/Raw Log Section
            UartPanelBox(title = "DETAILS / RAW LOG") {
                GhostActionCard(
                    if (viewModel.showDetails) "HIDE DETAILS" else "SHOW DETAILS",
                    "Shows or hides the raw log of everything on the USB cable.",
                    if (viewModel.showDetails) "HIDE DETAILS" else "SHOW DETAILS",
                    onClick = { viewModel.toggleDetails() }
                )
                if (viewModel.showDetails) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        viewModel.rawLog.ifEmpty { "(nothing logged yet)" },
                        color = Dim,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .verticalScroll(rememberScrollState())
                    )
                    GhostActionCard(
                        "COPY THE LOG",
                        "Copies the whole log so you can paste it somewhere else.",
                        "COPY THE LOG",
                        onClick = {
                            clipboard.setText(AnnotatedString(viewModel.copyLog()))
                        }
                    )
                }
            }
        }
    }

    // Flash confirmation dialog
    if (viewModel.showFlashConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::cancelFlashConfirm,
            title = { Text("Load this onto the board?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "This will wipe the board and write the new software onto it.\n\n" +
                        "Board: ${viewModel.esp32ChipInfo ?: "Unknown"}\n" +
                        "File: ${viewModel.firmwareName}\n\n" +
                        "Make sure the board is in load mode first."
                    )
                    // Package 5 (D-19) — where on the board, with the warning
                    // when it is not the usual app spot.
                    Text(
                        "Where on the board: ${"0x%X".format(viewModel.flashOffset)}" +
                            if (viewModel.flashOffset == 0x10000) " (usual place for the app)"
                            else " — not the usual app spot"
                    )
                    if (viewModel.flashOffset != 0x10000) {
                        Text(
                            "Warning: this writes the file somewhere other than the usual app spot. Only use this for a bootloader or settings-table file — the wrong spot can stop the board from starting.",
                            color = UartWarn
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmFlash) {
                    Text("LOAD IT", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelFlashConfirm) {
                    Text("CANCEL")
                }
            }
        )
    }
}

@Composable
private fun UartPanelBox(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(GhostCard, RoundedCornerShape(6.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, color = Accent, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
        content()
    }
}

@Composable
private fun UartButton(
    text: String,
    onClick: () -> Unit,
    highlighted: Boolean = false
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (highlighted) Accent else GhostCard,
            contentColor = if (highlighted) Color.Black else Accent
        ),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(text, fontFamily = FontFamily.Monospace)
    }
}
