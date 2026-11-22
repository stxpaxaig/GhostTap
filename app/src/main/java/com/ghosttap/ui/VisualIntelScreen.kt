package com.ghosttap.ui

import com.ghosttap.core.ops.OperationArea
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ghosttap.GhostTapViewModel
import com.ghosttap.NetworkToolsViewModel
import com.ghosttap.VisualAction
import com.ghosttap.VisualFinding
import com.ghosttap.VisualIntelViewModel
import com.ghosttap.VisualProfile
import com.ghosttap.core.BringUpKind
import com.ghosttap.core.BringUpTarget
import com.ghosttap.core.TvCandidate
import com.ghosttap.describeVisualThing
import com.ghosttap.core.investigate.FindingsReport
import com.ghosttap.core.investigate.InvestigationSource
import com.ghosttap.core.investigate.LiveTargetStore
import com.ghosttap.core.investigate.liveTargetKey
import com.ghosttap.suggestActions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * VISUAL INTEL — point the camera at real-world targets: circuit boards,
 * chips, connectors, labels, devices. Ghost Tap reads the text, scans
 * barcodes/QR codes, and pulls out candidate part markings. What it finds
 * becomes a starting point you can act on: copy it, search for matching
 * Bluetooth targets, or save it as a target profile.
 *
 * Everything the camera guesses is labeled a candidate. Verify against the
 * real object before trusting it.
 */

private fun hasCameraPermission(context: android.content.Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VisualIntelScreen(
    viewModel: VisualIntelViewModel,
    mainViewModel: GhostTapViewModel,
    netViewModel: NetworkToolsViewModel,
    onBack: () -> Unit,
    onNeedPermissions: () -> Unit,
    onPickImage: () -> Unit,
    onNavigate: (String) -> Unit,
    onInvestigate: (VisualFinding) -> Unit,
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
    // ZERO-CONFIG FIX (2026-11-06): the intent answer line is shared
    // across screens — clear it when this screen opens so a stale
    // outcome from another screen never shows here.
    LaunchedEffect(Unit) {
        intelVm?.clearCommandAnswer()
        // PIECE 2 — check tap control on open, so the panel says
        // right away whether it's on or still needs switching on.
        viewModel.refreshTapControlState()
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var cameraGranted by remember { mutableStateOf(hasCameraPermission(context)) }
    // EXECUTE BUILD (2026-11-17) — auto-connect bookkeeping: the finding
    // id already on screen when opened (never acted on — it's stale),
    // and the plain-words line for a photo with nothing to connect to.
    var seenFindingId by remember { mutableStateOf(viewModel.lastFinding?.id) }
    var noConnectionNote by remember { mutableStateOf<String?>(null) }

    // Re-check the permission every time this screen comes back into view,
    // so granting it from the system dialog immediately unlocks the camera.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                cameraGranted = hasCameraPermission(context)
                // PIECE 2 — re-check tap control too: the user may just
                // have switched it on in the phone's settings.
                viewModel.refreshTapControlState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    OperatorCapabilityPanel(
        area = OperationArea.VISUAL,
        connectionGuide = "Visual Intelligence uses the camera preview or a selected image. It extracts visible text, codes, labels, and component markings, then passes candidate identifiers to the existing device and network tools.",
        controlGuide = "Point the camera at a device, board, chip, connector, label, or screen; capture or select an image; inspect the extracted findings; then choose a follow-up such as web lookup, network inspection, or Bluetooth matching.",
        intelligenceGuide = "Combine OCR, barcode/QR data, part markings, visual context, and external reference lookups. Keep uncertain matches as candidates, show the evidence behind each match, and adapt the next investigation to the object identified.",
        liveState = "Camera permission: ${if (cameraGranted) "available" else "not granted"} • Latest finding: ${viewModel.lastFinding?.title ?: "none"}"
    )


    // Runs one suggested action: each one says what it does before it runs.
    // One tap does the whole chain — no config screens, no scan-first.
    fun runVisualAction(action: VisualAction) {
        when (action.id) {
            "wifi" -> {
                val parts = action.arg.split("||", limit = 3)
                viewModel.joinWifi(
                    ssid = parts.getOrElse(0) { "" },
                    password = parts.getOrElse(1) { "" }.ifEmpty { null },
                    security = parts.getOrElse(2) { "" }
                )
            }
            "fetch_url" -> viewModel.fetchLinkPreview(action.arg)
            "connect_ip" -> {
                netViewModel.portScan(action.arg)
                onNavigate("network_tools")
            }
            "ble" -> {
                // EXECUTE BUILD (2026-11-17) — same as the ops path: a
                // Bluetooth address from a photo connects through
                // bring-up, so the user lands on the control screen with
                // the connection held. Without the thinking engine, the
                // old direct connect is the fallback.
                val mac = action.arg.trim().uppercase()
                val intel = intelVm
                if (intel != null) {
                    val target = mainViewModel.allTargets.firstOrNull { it.id == "ble:$mac" }
                    intel.bringUpTarget(
                        BringUpTarget(
                            kind = BringUpKind.BLE,
                            id = mac,
                            title = target?.name?.takeIf { it.isNotBlank() } ?: "Bluetooth device"
                        ),
                        autoNavigate = true
                    )
                } else {
                    val id = mainViewModel.connectBleByAddress(action.arg)
                    if (id != null) {
                        mainViewModel.selectTarget(id)
                        onNavigate("target/$id")
                    }
                }
            }
            "usb" -> onNavigate("usb_uart")
            "nfc" -> onNavigate("nfc_tools")
            // Package 5 (A-28) — an action we don't know yet is no longer
            // a silent no-op: say so plainly, and say what we can do.
            else -> viewModel.reportUnknownVisualAction(action.id)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TerminalButton("BACK", onBack)
            Spacer(modifier = Modifier.padding(4.dp))
            Text(
                "VISUAL INTEL",
                color = Accent,
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace
            )
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

        // DISCOVER (Visual): one tap checks a picture — no typing, no
        // setup. It starts the picture through the section's existing
        // flow: the live camera takes the photo when the camera is on,
        // otherwise it opens the gallery to pick one. When the picture
        // shows something Ghost Tap can connect to, it connects on its
        // own and opens the controls — nothing to tap through first.
        GhostActionCard(
            title = "Investigate the unfamiliar",
            description = "Checks a picture — takes one with the live camera when it is on, or opens your gallery to pick one. If the picture shows something to connect to, Ghost Tap connects to it on its own.",
            buttonText = "Investigate the unfamiliar",
            onClick = {
                if (cameraGranted) viewModel.captureAndAnalyze() else onPickImage()
            }
        )

        // PIECE 2 (2026-11-18) — the "what this screen does" card and
        // the long description below it are gone: the cards say what
        // they do, and the status line says what's happening.
        Text(
            "WHAT'S HAPPENING: ${viewModel.statusLine}",
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )

        // ============ PIECE 2 (2026-11-18) — SCREEN-SEE-AND-TAP ============
        // You name something on the screen; Ghost Tap looks at the
        // screen, the brain says where it is, and Ghost Tap taps it.
        // Tap control needs one switch-on in the phone's own settings
        // first; no brain key or credit means no tap, said plainly.
        PanelBox("LOOK AT THE SCREEN AND TAP IT") {
            Text(
                "Name something on the screen — Ghost Tap looks at it, the brain says where it is, and Ghost Tap taps it. Nothing is tapped unless the brain points at a real spot.",
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            if (!viewModel.tapControlOn) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Ghost Tap's tap control is off.",
                    color = Dim,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
                TerminalButton("Turn on tap control") {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = viewModel.seeAndTapGoal,
                onValueChange = { viewModel.updateSeeAndTapGoal(it) },
                label = { Text("What should I tap? For example: the blue button") },
                modifier = Modifier.fillMaxWidth()
            )
            TerminalButton(
                if (viewModel.seeAndTapBusy) "Looking…" else "Look at the screen and tap it"
            ) {
                viewModel.seeAndTap(viewModel.seeAndTapGoal)
            }
            viewModel.seeAndTapStatus?.let { status ->
                Text(
                    status,
                    color = Dim,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (!cameraGranted) {
            PanelBox("CAMERA ACCESS") {
                Text(
                    "Camera access is off, so the live camera view cannot start. " +
                        "Allow the camera and it turns on right here. " +
                        "You can still pick a photo from your gallery below without it.",
                    color = Dim,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(6.dp))
                TerminalButton("ALLOW THE CAMERA", onNeedPermissions)
                Spacer(modifier = Modifier.height(6.dp))
                TerminalButton("PICK FROM GALLERY", onPickImage)
            }
        } else {
            PanelBox("LIVE CAMERA") {
                Text(
                    "Hold the thing steady and fill the frame. TAKE PHOTO & READ IT takes one " +
                        "photo and reads it. Good light and a steady hand find far more text.",
                    color = Dim,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(6.dp))
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }
                    },
                    update = { previewView ->
                        viewModel.bindCamera(lifecycleOwner, previewView)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                )
                DisposableEffect(Unit) {
                    onDispose { viewModel.unbindCamera() }
                }
                Spacer(modifier = Modifier.height(6.dp))
                GhostActionCard(
                    title = "Take a photo and do what it suggests",
                    description = "Takes one photo, reads it, and runs what the photo suggests.",
                    buttonText = "TAKE PHOTO & READ IT",
                    onClick = { onRunLiveCommand?.invoke("visual.capture-and-act", null) }
                )
                GhostActionCard(
                    title = "Read a photo from the gallery",
                    description = "Picks a photo from your gallery and reads it.",
                    buttonText = "PICK FROM GALLERY",
                    onClick = { onRunLiveCommand?.invoke("visual.read-gallery-photo", null) }
                )
                GhostActionCard(
                    title = "Open the camera",
                    description = "Opens the Visual Intel camera so you can point it at a board, chip, label, or device.",
                    buttonText = "Open the camera",
                    onClick = { onRunLiveCommand?.invoke("visual.open-camera", null) }
                )
                GhostActionCard(
                    title = "Turn the camera off",
                    description = "Closes the live camera view.",
                    buttonText = "Turn the camera off",
                    onClick = { onRunLiveCommand?.invoke("visual.stop-camera", null) }
                )
                if (viewModel.isAnalyzing) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "READING THE PHOTO…",
                        color = Accent,
                        fontFamily = FontFamily.Monospace
                    )
                }
                viewModel.cameraError?.let { err ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        err,
                        color = Color.Red,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        val finding = viewModel.lastFinding
        // EXECUTE BUILD (2026-11-17) — AUTO-CONNECT: a fresh photo acts
        // on its own. When a new finding arrives (not the one already on
        // screen when opened), the first thing it suggests runs by
        // itself — a Bluetooth address connects through bring-up and
        // lands on the control screen; a Wi-Fi code joins; a link
        // fetches. The ops mark findings they already acted on, so a
        // photo the command box already handled is never run twice. A
        // Wi-Fi code without its password is the one exception: it needs
        // one typed password first, so it waits below instead. When
        // nothing matches a tool, say plainly what the picture shows and
        // that there's nothing to connect to.
        LaunchedEffect(finding?.id) {
            val f = finding ?: return@LaunchedEffect
            if (f.id == seenFindingId) return@LaunchedEffect
            seenFindingId = f.id
            noConnectionNote = null
            if (viewModel.alreadyAutoActed(f.id)) return@LaunchedEffect
            val actions = suggestActions(f)
            if (actions.isEmpty()) {
                viewModel.markAutoActed(f.id)
                noConnectionNote =
                    "I can see it's ${describeVisualThing(f)}, but there's nothing to connect to."
            } else {
                val first = actions.first()
                if (first.id == "wifi") {
                    val parts = first.arg.split("||", limit = 3)
                    val pw = parts.getOrElse(1) { "" }
                    val sec = parts.getOrElse(2) { "" }
                    if (pw.isBlank() && sec.isNotBlank() && !sec.equals("nopass", ignoreCase = true)) {
                        viewModel.markAutoActed(f.id)
                        noConnectionNote =
                            "This network needs a password the picture didn't show — type it once below, then tap JOIN THIS WI-FI."
                        return@LaunchedEffect
                    }
                }
                viewModel.markAutoActed(f.id)
                runVisualAction(first)
            }
        }
        if (finding != null) {
            PanelBox("WHAT WAS FOUND") {
                Text(
                    "From: ${finding.source}. The text below is exactly what the camera read — " +
                        "mistakes happen, so check anything important against the real thing.",
                    color = Dim,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (finding.fullText.isNotBlank()) {
                    Text(
                        finding.fullText,
                        color = Accent,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState())
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
                if (finding.barcodes.isNotEmpty()) {
                    Text("BARCODES:", color = Accent, fontFamily = FontFamily.Monospace)
                    finding.barcodes.forEach { bc ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "${bc.formatName} · ${bc.valueTypeName}",
                                    color = Dim,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    bc.rawValue.ifBlank { "(nothing readable)" },
                                    color = Accent,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (bc.rawValue.isNotBlank()) {
                                TerminalButton("COPY") {
                                    viewModel.copyToClipboard("barcode", bc.rawValue)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
                finding.wifiSummary?.let { wifi ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("WI-FI CODE:", color = Accent, fontFamily = FontFamily.Monospace)
                    Text(
                        wifi,
                        color = Dim,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    TerminalButton("COPY PASSWORD") {
                        val pass = wifi.lines()
                            .firstOrNull { it.startsWith("Password:") }
                            ?.removePrefix("Password:")?.trim() ?: ""
                        if (pass.isNotBlank() && !pass.startsWith("(")) {
                            viewModel.copyToClipboard("Wi-Fi password", pass)
                        }
                    }
                }
                if (finding.markings.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "CANDIDATE MARKINGS (verify by eye — these are guesses):",
                        color = Accent,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                    finding.markings.forEach { marking ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                marking,
                                color = Dim,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                            TerminalButton("COPY") {
                                viewModel.copyToClipboard("marking", marking)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                GhostActionCard(
                    title = "Fetch what the barcode points to",
                    description = "Downloads what the link in the last photo points to and shows the text — no browser needed.",
                    buttonText = "Fetch what the barcode points to",
                    onClick = { onRunLiveCommand?.invoke("visual.fetch-link", null) }
                )
                GhostActionCard(
                    title = "Copy something from the photo",
                    description = "Copies one thing the camera found — the Wi-Fi password, the full text, a barcode, or a marking — to the clipboard.",
                    buttonText = "Copy something from the photo",
                    onClick = { onRunLiveCommand?.invoke("visual.copy-from-photo", null) }
                )
                GhostActionCard(
                    title = "Check the address from the photo",
                    description = "Checks the address Visual Intel spotted in a photo.",
                    buttonText = "Check the address from the photo",
                    onClick = { onRunLiveCommand?.invoke("net.check-spotted-address", null) }
                )
                GhostActionCard(
                    title = "Do what the last photo suggests",
                    description = "Runs the first thing the last photo suggests.",
                    buttonText = "Do what the last photo suggests",
                    onClick = { onRunLiveCommand?.invoke("visual.act-on-last-photo", null) }
                )
                GhostActionCard(
                    title = "Open Wi-Fi settings",
                    description = "Opens the phone's Wi-Fi settings so you can join a network by hand.",
                    buttonText = "Open Wi-Fi settings",
                    onClick = { onRunLiveCommand?.invoke("visual.open-wifi-settings", null) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Autonomous Investigation report for this photo, shown below
            // the analysis output. Report-only: it describes what the
            // camera read; nothing here touches a device on its own.
            viewModel.latestVisualReport?.let { report ->
                VisualFindingsReport(
                    report = report,
                    finding = finding,
                    onInvestigate = onInvestigate
                )
                // MASTER PROMPT: the same photo is a LIVE TARGET too —
                // what the thinking engine says about what the picture
                // found, right here under the report. Its commands live
                // in the WHAT YOU CAN DO WITH THIS panel below and on
                // this photo's live card on the Investigations screen.
                LiveTargetStore.get(liveTargetKey(report, InvestigationSource.VISUAL))?.let { live ->
                    AiReasoningBlock(target = live, intelVm = intelVm)
                }
            }

            // MASTER PROMPT — LIVE TARGETS from earlier pictures this
            // session, newest first — the current photo's report is
            // shown above, so it is left out here. A re-read photo
            // refreshes its card in place, so the list never stacks
            // duplicates. A picture that showed a device was already
            // connected to when it was read, so these have no connect
            // button — but they do carry what the thinking engine says
            // and the commands that act on what the picture found.
            val earlierVisualLive = remember(viewModel.latestVisualReport) {
                val currentId = viewModel.latestVisualReport?.targetId
                LiveTargetStore.all()
                    .filter { it.source == InvestigationSource.VISUAL && it.report.targetId != currentId }
            }
            if (earlierVisualLive.isNotEmpty()) {
                Text(
                    "Earlier pictures:",
                    color = Accent,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "If a picture showed a device, Ghost Tap connected to it when the picture was read — these are here to look back at.",
                    color = Dim,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
                earlierVisualLive.forEach { live ->
                    LiveTargetCard(
                        target = live,
                        intelVm = intelVm,
                        isConnected = false,
                        onConnect = null,
                        onControl = null,
                        onRunCommand = onRunLiveCommand,
                        onOpenRoute = onOpenRoute,
                        commandAnswer = intelVm?.lastCommandAnswer.orEmpty()
                    )
                }
            }

            // WHAT YOU CAN DO WITH THIS — automatic: the finding is matched
            // against Ghost Tap's real tools, each option in plain English.
            val actions = remember(finding.id) { suggestActions(finding) }
            PanelBox("WHAT YOU CAN DO WITH THIS") {
                Text(
                    "Ghost Tap looked at what the camera found and matched it against its " +
                        "real tools. Each button says what it does and what it opens up next.",
                    color = Dim,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
                // Package 5 (A-28) — the fallback when an action id is
                // unknown: shown here, in plain words, instead of silence.
                viewModel.visualActionFallback?.let { fallback ->
                    Text(
                        fallback,
                        color = Accent,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                if (actions.isEmpty()) {
                    Text(
                        noConnectionNote
                            ?: "Nothing in this picture matched a tool directly — no link, address, " +
                            "or device clue the app knows.",
                        color = Dim,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    VisualActionRow(
                        action = VisualAction(
                            title = "SEARCH FOR BLUETOOTH DEVICES ANYWAY",
                            does = "Starts looking for nearby Bluetooth devices in case the thing in the photo is one of them.",
                            unlocks = "If anything shows up, you can tap it and try to connect.",
                            id = "ble_fallback"
                        ),
                        onRun = {
                            val query = finding.markings.firstOrNull()
                                ?: finding.fullText.trim().take(24)
                            if (query.isNotBlank()) mainViewModel.visualIntelHint(query)
                            onNavigate("home")
                        }
                    )
                } else {
                    actions.forEach { action ->
                        // Wi-Fi without a password: let him type it once, then join.
                        // Control sometimes needs one credential — the steps stay visible.
                        if (action.id == "wifi" && action.arg.split("||", limit = 3).getOrElse(1) { "" }.isBlank()) {
                            val parts = action.arg.split("||", limit = 3)
                            var typedPw by remember(action.arg) { mutableStateOf("") }
                            GhostActionCard(
                                title = "Join the Wi-Fi from a photo",
                                description = action.does,
                                content = {
                                    Text(
                                        "This network needs a password the picture didn't show — type it once:",
                                        color = Dim,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    OutlinedTextField(
                                        value = typedPw,
                                        onValueChange = { typedPw = it },
                                        label = { Text("Wi-Fi password") },
                                        singleLine = true,
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    TerminalButton("JOIN THIS WI-FI") {
                                        viewModel.joinWifi(
                                            ssid = parts.getOrElse(0) { "" },
                                            password = typedPw.ifBlank { null },
                                            security = parts.getOrElse(2) { "" }
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        "Then: ${action.unlocks}",
                                        color = Dim,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            )
                        } else {
                            VisualActionRow(action = action, onRun = { runVisualAction(action) })
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            // Link preview, filled in by FETCH WHAT THIS CODE POINTS TO.
            viewModel.linkPreview?.let { preview ->
                PanelBox("LINK PREVIEW") {
                    Text(
                        preview,
                        color = Dim,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    TerminalButton("CLOSE") { viewModel.closeLinkPreview() }
                }
            }

            GhostActionCard(
                title = "Look into the last photo",
                description = "Starts a closer look at what the camera last found.",
                buttonText = "LOOK INTO THIS DEVICE",
                onClick = { onRunLiveCommand?.invoke("visual.investigate-photo", null) }
            )
            if (finding.fullText.isNotBlank()) {
                GhostActionCard(
                    title = "Copy the full text",
                    description = "Copies everything the camera read, word for word.",
                    buttonText = "COPY FULL TEXT",
                    onClick = { viewModel.copyToClipboard("full text", finding.fullText) }
                )
            }

            PanelBox("SAVE AS TARGET PROFILE") {
                Text(
                    "Keep what was found. Saving stores what the camera found plus your own notes, " +
                        "so you can come back to it later.",
                    color = Dim,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(6.dp))
                var profileName by remember(finding.id) { mutableStateOf("") }
                GhostActionCard(
                    title = "Save what the last photo found",
                    description = "Saves what the camera found as a target profile, so you can come back to it later.",
                    content = {
                        OutlinedTextField(
                            value = profileName,
                            onValueChange = { profileName = it },
                            label = { Text("Give it a name") },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
                            colors = TextFieldDefaults.colors(
                                focusedTextColor = Accent,
                                unfocusedTextColor = Accent,
                                focusedLabelColor = Dim,
                                unfocusedLabelColor = Dim
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        TerminalButton("SAVE IT") {
                            viewModel.saveCurrentAsProfile(profileName)
                            profileName = ""
                        }
                    }
                )
            }
        }

        PanelBox("SAVED TARGET PROFILES (${viewModel.profiles.size})") {
            if (viewModel.profiles.isEmpty()) {
                Text(
                    "Nothing saved yet. Take a photo or pick one, then save what you find.",
                    color = Dim,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                viewModel.profiles.forEach { profile ->
                    ProfileCard(
                        profile = profile,
                        onRename = { viewModel.renameProfile(profile.id, it) },
                        onSaveNotes = { viewModel.updateNotes(profile.id, it) },
                        onDelete = { viewModel.deleteProfile(profile.id) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            GhostActionCard(
                title = "Reload the saved profiles",
                description = "Re-reads the saved target profiles from the phone.",
                buttonText = "Reload the saved profiles",
                onClick = { onRunLiveCommand?.invoke("visual.reload-profiles", null) }
            )
        }

        GhostActionCard(
            title = "What is this",
            description = "Says what a device probably is — best guess, how sure, and what backs it up. When it can't tell, it says so.",
            buttonText = "What is this",
            onClick = { onRunLiveCommand?.invoke("intel.what-is-this", null) }
        )
        GhostActionCard(
            title = "Search the web for this",
            description = "Searches the web for what the open case file is about — web findings only, marked as leads until a real test checks them.",
            buttonText = "Search the web for this",
            onClick = { onRunLiveCommand?.invoke("intel.search-the-web-for-this", null) }
        )
        GhostActionCard(
            title = "Save what I just taught it",
            description = "Saves the last learning result as a named control, suggesting a name from what actually changed.",
            buttonText = "Save what I just taught it",
            onClick = { onRunLiveCommand?.invoke("learn.save-with-name", null) }
        )
    }
}

@Composable
private fun VisualFindingsReport(
    report: FindingsReport,
    finding: VisualFinding?,
    onInvestigate: (VisualFinding) -> Unit
) {
    val currentFinding = finding
    if (currentFinding != null) {
        // A real follow-up path exists for a visual finding: the existing
        // "look into this device" path — so the card's connect button is
        // wired to it, and the user decides by tapping it.
        FindingsReportCard(
            report = report,
            onConnect = { onInvestigate(currentFinding) },
            // No device action is ever started from a picture, and no
            // investigated capability maps to an existing visual
            // follow-up action, so capabilities render as text only.
            onControl = { },
            capabilitiesAsText = true
        )
    } else {
        // No finding to follow up on, so no connect path exists: show the
        // report as plain text with no connect button at all, rather than
        // a button that leads nowhere.
        PanelBox("WHAT THE PICTURE TELLS US") {
            Text(
                "What was pictured: ${report.targetLabel}",
                color = Accent,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "What it looks like: ${report.believedIdentity}",
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "How sure: ${report.confidence}",
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            if (report.evidence.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Why: ${report.evidence.joinToString("; ")}",
                    color = Dim,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (report.risks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Watch out for: ${report.risks.joinToString("; ")}",
                    color = Dim,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "A picture alone can't prove the thing answers, so nothing " +
                    "here connects on its own.",
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun VisualActionRow(action: VisualAction, onRun: () -> Unit) {
    GhostActionCard(
        title = action.title,
        description = action.does,
        buttonText = action.title,
        onClick = onRun
    ) {
        Text(
            "Then: ${action.unlocks}",
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ProfileCard(
    profile: VisualProfile,
    onRename: (String) -> Unit,
    onSaveNotes: (String) -> Unit,
    onDelete: () -> Unit
) {
    val dateFmt = remember {
        SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
    }
    var nameEdit by remember(profile.id) { mutableStateOf(profile.name) }
    var notesEdit by remember(profile.id) { mutableStateOf(profile.notes) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            "${profile.name} · ${profile.findings.size} finding(s) · ${dateFmt.format(Date(profile.createdMs))}",
            color = Accent,
            fontFamily = FontFamily.Monospace
        )
        OutlinedTextField(
            value = nameEdit,
            onValueChange = { nameEdit = it },
            label = { Text("Name") },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
            colors = TextFieldDefaults.colors(
                focusedTextColor = Accent,
                unfocusedTextColor = Accent,
                focusedLabelColor = Dim,
                unfocusedLabelColor = Dim
            ),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = notesEdit,
            onValueChange = { notesEdit = it },
            label = { Text("Your notes") },
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
            colors = TextFieldDefaults.colors(
                focusedTextColor = Accent,
                unfocusedTextColor = Accent,
                focusedLabelColor = Dim,
                unfocusedLabelColor = Dim
            ),
            modifier = Modifier.fillMaxWidth()
        )
        GhostActionCard(
            title = "Rename a saved profile",
            description = "Renames this saved target profile to the name you typed above.",
            buttonText = "RENAME",
            onClick = { onRename(nameEdit) }
        )
        GhostActionCard(
            title = "Save notes",
            description = "Saves the notes you typed for this profile.",
            buttonText = "SAVE NOTES",
            onClick = { onSaveNotes(notesEdit) }
        )
        GhostActionCard(
            title = "Delete a saved profile",
            description = "Deletes this saved target profile.",
            buttonText = "DELETE",
            onClick = onDelete
        )
    }
}
