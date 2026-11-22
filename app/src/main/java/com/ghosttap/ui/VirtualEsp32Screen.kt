package com.ghosttap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ghosttap.core.TvCandidate
import com.ghosttap.core.esp32.VirtualEsp32Client
import com.ghosttap.core.esp32.loadBundledFirmware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ghosttap.core.investigate.FindingsReport
import com.ghosttap.core.investigate.InvestigatedControl
import com.ghosttap.core.investigate.InvestigationInput
import com.ghosttap.core.investigate.InvestigationLedger
import com.ghosttap.core.investigate.InvestigationSource
import com.ghosttap.core.investigate.LiveTargetStore
import com.ghosttap.core.investigate.Investigator

/** ZERO-CONFIG FIX (2026-11-06): stable id for the board in
 * the thinking engine's manual-disconnect memory — one board, so one
 * key, shared by the auto-connect guard and the DISCONNECT button. */
private const val VIRTUAL_ESP32_ID = "virtual-esp32"

/** GHOST TAP control surface for the Virtual ESP32 QEMU target.
 *
 * All operations go through the AIDL IPC bridge to the real executing
 * Xtensa firmware. UART and GPIO are not simulated here. */
@Composable
fun VirtualEsp32Screen(
    client: VirtualEsp32Client,
    onBack: () -> Unit,
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
    val connState by client.connectionState.collectAsState()
    val targetInfo by client.targetInfo.collectAsState()
    val uartLog by client.uartLog.collectAsState()
    val gpio2 by client.gpio2State.collectAsState()
    val gpio4 by client.gpio4State.collectAsState()
    val lastError by client.lastError.collectAsState()
    val firmwareInfo by client.firmwareInfo.collectAsState()
    val answeredCommand by client.answeredCommand.collectAsState()
    var uartInput by remember { mutableStateOf("") }
    // Autonomous investigation: the latest virtual-device report for this
    // screen, plus the state signatures already investigated this session.
    // Nothing here acts on its own — the report only offers buttons.
    var latestVirtualReport by remember { mutableStateOf<FindingsReport?>(null) }
    val investigatedVirtualStates = remember { mutableSetOf<String>() }
    var validationText by remember { mutableStateOf<String?>(null) }
    // DISCOVER (Virtual ESP32): plain-words note from the last "Check
    // the practice board" tap — e.g. no board info yet. Null = nothing
    // to say. Shown under the DISCOVER button; never a faked board.
    var virtualDiscoverNote by remember { mutableStateOf<String?>(null) }

    val installed = remember { client.isInstalled() }
    val context = LocalContext.current
    // UNIFIED BUILD (2026-11-18) — the bundled software is a 4 MB file
    // copy, so loading runs off the main thread.
    val loadScope = rememberCoroutineScope()

    // ZERO-CONFIG FIX (2026-11-06): the intent answer line is shared
    // across screens — clear it when this screen opens so a stale
    // outcome from another screen never shows here.
    LaunchedEffect(Unit) { intelVm?.clearCommandAnswer() }

    // ZERO-CONFIG — SELECT → AUTO-CONNECT (2026-11-06): opening the
    // practice-board screen connects to the board's bridge on its own
    // (listens for its messages, asks its state) — no CONNECT tap.
    // Once per entry, unless the user deliberately disconnected: that
    // choice lives in the thinking engine's ViewModel
    // (IntelViewModel.manuallyDisconnectedIds), so it survives leaving
    // and re-entering — disconnecting is the user's call and is never
    // undone by re-entering, until the user taps CONNECT himself.
    // Starting the guest stays one tap: the software is already inside
    // Ghost Tap, so LOAD SOFTWARE just loads it — no file to pick.
    var vespAutoConnected by remember { mutableStateOf(false) }
    LaunchedEffect(installed, connState) {
        // Connected again (by any path) clears a stale manual-
        // disconnect entry, so a later drop that was NOT the user's
        // choice can still auto-reconnect.
        if (connState !is VirtualEsp32Client.ConnectionState.Disconnected) {
            intelVm?.clearManualDisconnect(VIRTUAL_ESP32_ID)
        }
        if (installed && connState is VirtualEsp32Client.ConnectionState.Disconnected &&
            !vespAutoConnected &&
            intelVm?.wasManuallyDisconnected(VIRTUAL_ESP32_ID) != true
        ) {
            vespAutoConnected = true
            client.connect()
        }
    }

    // Autonomous-investigation trigger (additive): a signature of the
    // virtual device's reported state. When it changes to a state not yet
    // investigated this session — a new target, new firmware, new pin
    // readings, a first real reply, or new messages from the board — the
    // virtual device is investigated once with real evidence only. The
    // report is filed in the ledger and shown below the device output.
    // Re-investigation happens only when the reported state changes.
    val virtualStateKey = buildString {
        val info = targetInfo
        if (info != null) {
            append("status="); append(info.status); append("|")
            append("cpu="); append(info.cpu); append("|")
            append("backend="); append(info.backend); append("|")
            append("firmware="); append(info.firmware); append("|")
        }
        val fw = firmwareInfo
        if (fw != null) {
            append("file="); append(fw.fileName); append("|")
            append("type="); append(fw.imageType); append("|")
        }
        append("pin2="); append(gpio2?.toString() ?: "?"); append("|")
        append("pin4="); append(gpio4?.toString() ?: "?"); append("|")
        append("answered="); append(answeredCommand); append("|")
        append("recent=")
        append(uartLog.filter { it.startsWith("RX:") }.takeLast(5).joinToString(";"))
    }
    // DISCOVER (Virtual ESP32): the same check the automatic trigger
    // below runs, on demand. [force] lifts the already-checked gate so
    // a tap re-checks the board's current state; the report carries the
    // board's stable id, so a re-check refreshes its report in place
    // instead of duplicating it. When there is no board info yet, it
    // says so plainly and files nothing — never a faked board.
    fun investigateVirtualNow(force: Boolean) {
        val info = targetInfo
        if (info == null) {
            virtualDiscoverNote = if (!installed) {
                "The board app is not installed — install it first."
            } else {
                "No board info yet — tap CONNECT, wait a moment, then tap this again."
            }
            return
        }
        if (!force && virtualStateKey in investigatedVirtualStates) return
        investigatedVirtualStates += virtualStateKey
        val fw = firmwareInfo
        val details = mutableMapOf<String, String>()
        details["status"] = info.status
        details["processor"] = info.cpu
        details["running on"] = info.backend
        details["software"] = info.firmware
        if (fw != null) {
            details["software file"] = fw.fileName
            details["software kind"] = fw.imageType
        }
        gpio2?.let { details["pin 2"] = if (it) "ON" else "OFF" }
        gpio4?.let { details["pin 4"] = if (it) "ON" else "OFF" }
        val recentReadings = uartLog.filter { it.startsWith("RX:") }.takeLast(8)
        val report = Investigator.investigate(
            InvestigationInput(
                source = InvestigationSource.VIRTUAL_ESP32,
                rawName = listOf(info.firmware, fw?.fileName)
                    .firstOrNull { !it.isNullOrBlank() && it != "?" }
                    ?: "Virtual ESP32 board",
                rawDetails = details,
                readableValues = listOf(
                    "A real board running on this phone — its software actually executes here."
                ) + recentReadings,
                // Honest handshake proof only: true when the practice-board
                // app actually answered a command over UART this session.
                answeredHandshake = answeredCommand,
                signalText = null,
                // Stable board id, so a re-check refreshes this board's
                // report in place instead of duplicating it.
                targetId = "virtual:practice-board"
            )
        )
        InvestigationLedger.upsert(report)
        // MASTER PROMPT: the same report is also a LIVE TARGET — what
        // the user sees and connects to. The ledger stays as internal
        // storage/dedup only.
        LiveTargetStore.publish(report, InvestigationSource.VIRTUAL_ESP32)
        latestVirtualReport = report
        virtualDiscoverNote = null
    }
    LaunchedEffect(virtualStateKey) {
        investigateVirtualNow(force = false)
    }

    // UNIFIED BUILD (2026-11-18) — no file picker any more: the
    // software ships inside Ghost Tap (see LOAD SOFTWARE below), so
    // there is nothing to pick, no other app to open, nothing to
    // configure.

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .background(Bg),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("VIRTUAL ESP32", color = Accent, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
            Button(onClick = onBack) { Text("Back") }
        }

        onOpenWhatToDo?.let { TerminalButton("What do you want to do?", it) }

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

        OperatorCapabilityPanel(
            area = OperationArea.SYSTEM,
            connectionGuide = "Ghost Tap connects to the VirtualESP32 companion service over Android IPC. The guest firmware runs in the emulated ESP32 runtime; UART and GPIO commands travel through that bridge. Check the connection state below before sending controls.",
            controlGuide = "Use LOAD SOFTWARE, CONNECT, START, STOP, RESTART, UART SEND/TEST REPLY, and GPIO controls below. Each control is explicit. UART transmission is not proof that guest firmware acted; look for its reply or a GPIO state change.",
            intelligenceGuide = "Ghost Tap can inspect firmware metadata, compare local and guest-side validation, query guest status, inspect UART replies, and read GPIO state. It should use those observations to suggest the next probe rather than assume an unknown firmware behavior.",
            liveState = "Service installed: ${if (installed) "YES" else "NO"} • Connection: ${connState} • Firmware: ${firmwareInfo?.fileName ?: "not loaded"} • GPIO4: ${gpio4 ?: "unknown"}"
        )

        // DISCOVER (Virtual ESP32): one tap checks the practice board's
        // reported state right now — no typing, no setup. It asks the
        // board for its current state first, then runs the same check
        // the automatic trigger runs. Findings list below; nothing here
        // runs on its own beyond the check.
        GhostActionCard(
            "Investigate the unfamiliar",
            "Checks the board's reported state right now — no typing, no setup.",
            "Investigate the unfamiliar",
            onClick = {
                client.queryStatus()
                investigateVirtualNow(force = true)
            }
        )
        virtualDiscoverNote?.let { note ->
            Text(
                note,
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }

        // PIECE 2 (2026-11-18) — the "what this screen does" card is
        // gone: it only re-listed what the cards below already do.
        // Op "vesp32.check-installed": no button on the page before —
        // the install check only ran by itself when the screen opened.
        GhostActionCard(
            "Is the board app installed?",
            "Checks whether the board app is on this phone — the board runs in that app, not in Ghost Tap.",
            "Is the board app installed?",
            onClick = { onRunLiveCommand?.invoke("vesp32.check-installed", null) },
            enabled = onRunLiveCommand != null
        )
        // Op "vesp32.use-practice-board": no button on the page
        // before.
        GhostActionCard(
            "Use the board",
            "Does the whole board flow in one tap: checks the app is installed, connects, loads software, and starts it.",
            "Use the board",
            onClick = { onRunLiveCommand?.invoke("vesp32.use-practice-board", null) },
            enabled = onRunLiveCommand != null
        )

        if (!installed) {
            Text(
                "The board app is not installed. Install it to use this board.",
                color = Color.Red, fontFamily = FontFamily.Monospace
            )
            return@Column
        }

        // Firmware manager (top: load before connecting)
        PanelBox(title = "FIRMWARE") {
            val fw = firmwareInfo
            if (fw != null) {
                Text("File: ${fw.fileName}", color = Dim, fontFamily = FontFamily.Monospace)
                Text("Kind: ${fw.imageType}", color = Dim, fontFamily = FontFamily.Monospace)
                Text("Size: ${fw.fileSize / 1024} KB", color = Dim, fontFamily = FontFamily.Monospace)
                Text("Check: ${fw.validationSummary}", color = Dim, fontFamily = FontFamily.Monospace)
                if (fw.isBuiltin) {
                    Text("(built-in test software)", color = Accent, fontFamily = FontFamily.Monospace)
                }
            } else {
                Text("No software loaded yet.", color = Dim, fontFamily = FontFamily.Monospace)
                Text("Use LOAD SOFTWARE below — the software is already inside Ghost Tap.", color = Dim, fontFamily = FontFamily.Monospace)
            }
            validationText?.let {
                Text("Last check: $it", color = Color(0xFFFFC857), fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall)
            }
            // Package 5 (D-29) — ONE verdict from the two checks: Ghost
            // Tap's own check (validationText) and the practice board's
            // (firmwareInfo.validationSummary). Disagreement is said
            // plainly; a failed check is never softened.
            if (validationText != null || fw != null) {
                Text(
                    "One verdict: ${reconcileFirmwareChecks(validationText, fw?.validationSummary)}",
                    color = Accent, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            GhostActionCard(
                "LOAD SOFTWARE",
                "Loads the software already inside Ghost Tap onto the board — no file to pick.",
                "LOAD SOFTWARE",
                onClick = {
                    loadScope.launch {
                        validationText = withContext(Dispatchers.IO) {
                            loadBundledFirmware(context, client)
                        }
                    }
                }
            )
            GhostActionCard(
                "REMOVE SOFTWARE",
                "Takes the loaded software off the board.",
                "REMOVE SOFTWARE",
                onClick = client::ejectFirmware
            )
            GhostActionCard(
                "BUILT-IN TEST SOFTWARE",
                "Loads the built-in test software onto the board.",
                "BUILT-IN TEST SOFTWARE",
                onClick = client::useBuiltinFirmware
            )
            Text(
                "The software is the MicroPython test image that comes with Ghost Tap. It is checked first, then handed to the board to run.",
                color = Dim, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Connection
        PanelBox(title = "CONNECTION") {
            Text(
                "State: ${when (connState) {
                    is VirtualEsp32Client.ConnectionState.Disconnected -> "Not connected"
                    is VirtualEsp32Client.ConnectionState.Connected -> "Connected"
                }}",
                color = Dim, fontFamily = FontFamily.Monospace
            )
            lastError?.let {
                Text("Problem: $it", color = Color.Red, fontFamily = FontFamily.Monospace)
            }
            GhostActionCard(
                "CONNECT",
                "Connects to the board.",
                "CONNECT",
                onClick = client::connect
            )
            // ZERO-CONFIG FIX (2026-11-06): a manual DISCONNECT is
            // the user's decision — remember it so re-entering this
            // screen never auto-connects the board again.
            GhostActionCard(
                "DISCONNECT",
                "Closes the link to the board.",
                "DISCONNECT",
                onClick = { intelVm?.noteManualDisconnect(VIRTUAL_ESP32_ID); client.disconnect() }
            )
            // Op "vesp32.check-practice-status": no button on the page
            // before — the status was shown as text only.
            GhostActionCard(
                "How is the board doing?",
                "Asks the board for its status — what it is doing, what processor it is, and what software it is running — in plain words.",
                "How is the board doing?",
                onClick = { onRunLiveCommand?.invoke("vesp32.check-practice-status", null) },
                enabled = onRunLiveCommand != null
            )
        }

        val info = targetInfo
        if (info != null) {
            PanelBox(title = "TARGET") {
                Text("Status: ${info.status}", color = Dim, fontFamily = FontFamily.Monospace)
                Text("Processor: ${info.cpu}", color = Dim, fontFamily = FontFamily.Monospace)
                Text("Running on: ${info.backend}", color = Dim, fontFamily = FontFamily.Monospace)
                Text("Software: ${info.firmware}", color = Dim, fontFamily = FontFamily.Monospace)
            }
        }

        // Guest lifecycle
        PanelBox(title = "GUEST CONTROL") {
            GhostActionCard(
                "START",
                "Starts the board's software.",
                "START",
                onClick = client::startGuest
            )
            GhostActionCard(
                "STOP",
                "Stops the board's software.",
                "STOP",
                onClick = client::stopGuest
            )
            GhostActionCard(
                "RESTART",
                "Restarts the board's software.",
                "RESTART",
                onClick = client::resetGuest
            )
        }

        // UART terminal
        PanelBox(title = "UART TERMINAL") {
            GhostActionCard(
                "Send a message to the board",
                "Type a message, then tap SEND — the board's replies show below."
            ) {
                OutlinedTextField(
                    value = uartInput,
                    onValueChange = { uartInput = it },
                    label = { Text("Send a message to the board") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TerminalButton("SEND", {
                        if (uartInput.isNotBlank()) {
                            client.sendUart(uartInput)
                            uartInput = ""
                        }
                    })
                    TerminalButton("TEST REPLY", { client.sendUart("PING") })
                    TerminalButton("CLEAR MESSAGES", client::clearUartLog)
                }
            }
            Text(
                uartLog.takeLast(30).joinToString("\n").ifEmpty { "(no messages yet)" },
                color = Color(0xFFA9E5C5), fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }

        // GPIO
        PanelBox(title = "GPIO") {
            Text(
                "Pin 2 (board's output): ${gpio2?.let { if (it) "ON" else "OFF" } ?: "(unknown)"}",
                color = Dim, fontFamily = FontFamily.Monospace
            )
            Text(
                "Pin 4 (your input): ${gpio4?.let { if (it) "ON" else "OFF" } ?: "OFF"}",
                color = Dim, fontFamily = FontFamily.Monospace
            )
            GhostActionCard(
                "SWITCH PIN 4 ON/OFF",
                "Flips your switch into the board.",
                "SWITCH PIN 4 ON/OFF",
                onClick = {
                    client.setGpio4(!(gpio4 ?: false))
                }
            )
            // Op "vesp32.check-light-pin": no button on the page
            // before — pin 2 was shown as text only.
            GhostActionCard(
                "What is the board's light doing?",
                "Reads pin 2 — the board's output — and says whether it is on or off.",
                "What is the board's light doing?",
                onClick = { onRunLiveCommand?.invoke("vesp32.check-light-pin", null) },
                enabled = onRunLiveCommand != null
            )
            Text(
                "Pin 2 shows what the board's software is doing. Pin 4 sends your switch into the board.",
                color = Dim, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }

        // EXECUTE BUILD (2026-11-17) — REAL BOARD: the board on this
        // screen is real, and so is a board plugged in by USB. These
        // cards open the USB screen, where the board's own flasher
        // writes software onto a plugged-in board over the USB cable
        // (CH340) and the message box talks to it. One tap on
        // LOAD IT ONTO THE BOARD there flashes — it puts the board in
        // download mode itself. Termux esptool is NOT used here on
        // purpose: Termux cannot reach a USB serial port on Android, so
        // an esptool flash would be fake; the in-app flasher is the real
        // path and it is what the USB screen runs.
        PanelBox(title = "REAL BOARD") {
            GhostActionCard(
                "Flash a real board",
                "Opens the USB screen — pick a software file there, then tap LOAD IT ONTO THE BOARD to write it onto a plugged-in board.",
                "Flash a real board",
                onClick = { onOpenRoute?.invoke("usb_uart") },
                enabled = onOpenRoute != null
            )
            GhostActionCard(
                "Talk to a real board",
                "Opens the USB screen to send messages to a plugged-in board and read what it says back.",
                "Talk to a real board",
                onClick = { onOpenRoute?.invoke("usb_uart") },
                enabled = onOpenRoute != null
            )
            Text(
                "No board plugged in? The USB screen says so plainly — plug one in with a USB cable and it shows up on its own.",
                color = Dim, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }

        // MASTER PROMPT — LIVE TARGET for this practice board.
        // Shown below the device output; nothing here runs on its own.
        // "Connect" reuses the existing CONNECT path; each control button
        // runs only an existing button action on this screen, and a
        // capability with no matching button is shown as text only.
        // A re-check refreshes the board's card in place, so the list
        // never stacks duplicates of the same board. New on the card:
        // what the thinking engine says, and — once connected — the
        // commands you can tap on the board.
        val virtualLive = remember(latestVirtualReport) {
            LiveTargetStore.all().filter { it.source == InvestigationSource.VIRTUAL_ESP32 }
        }
        if (virtualLive.isNotEmpty()) {
            Text(
                "What we found about this board",
                color = Accent, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Nothing here runs on its own — you press the buttons.",
                color = Dim, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            virtualLive.forEach { live ->
                LiveTargetCard(
                    target = live,
                    intelVm = intelVm,
                    isConnected = connState is VirtualEsp32Client.ConnectionState.Connected,
                    onConnect = client::connect,
                    onControl = { control -> runVirtualControl(client, control, gpio4) },
                    onRunCommand = onRunLiveCommand,
                    onOpenRoute = onOpenRoute,
                    commandAnswer = intelVm?.lastCommandAnswer.orEmpty()
                )
            }
        }
    }
}

/**
 * Maps an investigated capability to an EXISTING action on this screen.
 * Anything with no matching button is left alone (shown as text only) —
 * no new action is invented for it. Order matters: "restart" is checked
 * before "start" because it contains "start".
 */
private fun runVirtualControl(
    client: VirtualEsp32Client,
    control: InvestigatedControl,
    pin4On: Boolean?
) {
    // Match on the capability's own label + description (stable fields),
    // never on the data-class toString, which also contains field names.
    val text = (control.label + " " + control.plainDescription).lowercase()
    when {
        "pin 4" in text || "gpio4" in text || "gpio 4" in text || "switch" in text ->
            client.setGpio4(!(pin4On ?: false))
        "pin 2" in text || "gpio2" in text || "gpio 2" in text ->
            client.readGpio2()
        "uart" in text || "message" in text || "send" in text ->
            client.sendUart("PING")
        "restart" in text || "reset" in text ->
            client.resetGuest()
        "start" in text ->
            client.startGuest()
        "stop" in text ->
            client.stopGuest()
        "status" in text || "check" in text ->
            client.queryStatus()
        else -> { /* No matching button on this screen — text only. */ }
    }
}

/**
 * Package 5 (D-29) — one verdict from the two firmware checks: Ghost
 * Tap's own check of the picked file (localSummary) and the practice
 * board app's check of the file it received (remoteSummary). Pure
 * function, no Android calls. A failed check is never softened, and
 * disagreement is said plainly instead of picked silently.
 */
fun reconcileFirmwareChecks(localSummary: String?, remoteSummary: String?): String {
    val local = localSummary?.trim().orEmpty()
    val remote = remoteSummary?.trim().orEmpty()
    if (local.isEmpty() && remote.isEmpty()) return "Nothing checked yet."
    if (local.isEmpty()) return "Only the board has checked it so far: $remote"
    if (remote.isEmpty()) return "Only Ghost Tap has checked it so far: $local"
    fun imageType(summary: String): String = when {
        "Merged flash image" in summary -> "Merged flash image"
        "Single app image" in summary -> "Single app image"
        else -> ""
    }
    val localType = imageType(local)
    val remoteType = imageType(remote)
    val eitherHasIssue = "issue" in local.lowercase() || "issue" in remote.lowercase()
    if (eitherHasIssue) {
        return "One check found a problem — Ghost Tap says: $local. " +
            "The board says: $remote. Trust the stricter one."
    }
    if (localType.isNotEmpty() && remoteType.isNotEmpty() && localType != remoteType) {
        return "The checks disagree — Ghost Tap says: $local. " +
            "The board says: $remote. Don't start it until they agree."
    }
    val agreed = localType.ifEmpty { remoteType }
    if (agreed.isNotEmpty()) return "Both checks agree: $agreed — good to start."
    return "Both checks ran. Ghost Tap says: $local. The board says: $remote."
}
