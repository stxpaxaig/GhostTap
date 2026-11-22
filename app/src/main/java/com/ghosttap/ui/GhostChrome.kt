package com.ghosttap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ghosttap.core.CapabilityRegistry
import com.ghosttap.core.ops.OperationArea
import com.ghosttap.core.ops.OperationRegistry
import androidx.compose.ui.unit.dp

/**
 * UI REDESIGN (2026-11-17) — shared chrome for every screen.
 *
 * One visual standard, used by home and every function page:
 * dark background, neon green primary ([Accent]), cyan secondary
 * ([Cyan]). One panel style ([PanelBox], restyled in GhostTapApp.kt),
 * one button style ([TerminalButton], restyled in GhostTapApp.kt),
 * and the two card styles below.
 *
 * EXECUTE BUILD (2026-11-17) — real icons: every tile and every
 * action card shows an actual visual icon next to its title, not
 * text only. Tiles take an explicit icon; action cards pick one from
 * their title through [iconForAction] (an explicit [icon] argument
 * wins when a card passes one). The mapping is keyword-based and
 * centralized here so one read shows what every card looks like.
 *
 * Presentation only: nothing here runs anything, changes any state,
 * or gates any behavior. Zero-config stands.
 */

/** Neon cyan — secondary accent. Green ([Accent]) stays primary. */
internal val Cyan = Color(0xFF4DD8FF)

/** Card background — same value as the private Panel in GhostTapApp.kt. */
internal val GhostCard = Color(0xFF101010)

private val GhostCardShape = RoundedCornerShape(8.dp)

private fun Modifier.ghostCardChrome(enabled: Boolean): Modifier = this
    .fillMaxWidth()
    .background(GhostCard, GhostCardShape)
    .border(1.dp, Accent.copy(alpha = if (enabled) 0.35f else 0.15f), GhostCardShape)

/**
 * The icon for an action, from its title. Specific phrases win over
 * generic words ("take a photo" beats "check"; "clear messages" beats
 * "message"). Anything unmatched gets the bolt — the generic "do it"
 * mark — so no card is ever icon-less. Pure function, no Android calls.
 */
internal fun iconForAction(title: String): ImageVector {
    val t = title.lowercase()
    return when {
        "barcode" in t -> Icons.Filled.QrCodeScanner
        "take a photo" in t -> Icons.Filled.PhotoCamera
        "camera" in t -> Icons.Filled.PhotoCamera
        "gallery" in t -> Icons.Filled.Image
        "photo" in t -> Icons.Filled.PhotoCamera
        "termux" in t -> Icons.Filled.Terminal
        "script" in t -> Icons.Filled.Terminal
        "command" in t -> Icons.Filled.Terminal
        "wi-fi" in t || "wifi" in t -> Icons.Filled.Wifi
        "disconnect" in t -> Icons.Filled.BluetoothDisabled
        "connect" in t -> Icons.Filled.BluetoothConnected
        "load it onto the board" in t -> Icons.Filled.FileDownload
        "load software" in t -> Icons.Filled.FileDownload
        "remove software" in t -> Icons.Filled.Delete
        "pick software" in t -> Icons.Filled.FolderOpen
        "built-in test software" in t -> Icons.Filled.Memory
        "check the software" in t -> Icons.Filled.Description
        "start-up" in t -> Icons.Filled.PowerSettingsNew
        "start" in t -> Icons.Filled.PlayArrow
        "stop" in t -> Icons.Filled.Stop
        "restart" in t -> Icons.Filled.Refresh
        "reload" in t -> Icons.Filled.Refresh
        "delete" in t || "discard" in t || "clear" in t -> Icons.Filled.Delete
        "rename" in t -> Icons.Filled.Edit
        "send" in t || "message" in t -> Icons.Filled.Send
        "save" in t -> Icons.Filled.Save
        "search" in t -> Icons.Filled.Search
        "investigat" in t -> Icons.Filled.Search
        "look into" in t -> Icons.Filled.Search
        "check" in t -> Icons.Filled.Search
        "copy" in t -> Icons.Filled.ContentCopy
        "pin" in t || "light" in t -> Icons.Filled.FlashOn
        "talk back" in t -> Icons.Filled.Mic
        "test the board" in t -> Icons.Filled.BugReport
        "practice board" in t || "board" in t -> Icons.Filled.Memory
        "speed" in t -> Icons.Filled.Speed
        "history" in t || "sent before" in t || "case files" in t -> Icons.Filled.History
        "plan" in t -> Icons.Filled.List
        "note" in t -> Icons.Filled.Edit
        "web" in t -> Icons.Filled.Public
        "settings" in t -> Icons.Filled.Settings
        "usb" in t -> Icons.Filled.Usb
        "wire" in t -> Icons.Filled.Cable
        "text mode" in t -> Icons.Filled.TextFields
        "log" in t -> Icons.Filled.Description
        "profile" in t -> Icons.Filled.Person
        "close" in t -> Icons.Filled.Close
        "mark as done" in t -> Icons.Filled.CheckCircle
        "approve" in t -> Icons.Filled.Check
        "what is this" in t -> Icons.Filled.Help
        "detail" in t -> Icons.Filled.Info
        "link" in t -> Icons.Filled.Link
        else -> Icons.Filled.Bolt
    }
}

/**
 * One function, as a tile: its real icon, its title (kept exactly),
 * one plain-words line saying what it does, and a cyan "Tap to open"
 * line saying what tapping does. Home uses one tile per function; the
 * tiles all look the same and say different things.
 */
@Composable
internal fun GhostTile(
    title: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    icon: ImageVector
) {
    Column(
        modifier = Modifier
            .ghostCardChrome(enabled)
            .clickable(enabled = enabled) { onClick() }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(28.dp)
            )
            Text(
                title,
                color = Accent,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyLarge
            )
        }
        Text(
            description,
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Tap to open",
            color = Cyan,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * One action inside a function page, as a card: its real icon, its
 * title (kept exactly), one plain-words line saying what it does,
 * then its button — or, when the action needs typing first, its
 * inputs and button in the [content] slot instead of
 * [buttonText]/[onClick].
 *
 * The card never invents an action: [onClick] must call the existing
 * handler for that action (a ViewModel call or
 * `onRunLiveCommand("<op id>", null)`), never a new implementation.
 */
@Composable
internal fun GhostActionCard(
    title: String,
    description: String,
    buttonText: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    Column(
        modifier = Modifier
            .ghostCardChrome(enabled)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon ?: iconForAction(title),
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(22.dp)
            )
            Text(
                title,
                color = Accent,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyLarge
            )
        }
        Text(
            description,
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        if (buttonText != null && onClick != null) {
            Spacer(modifier = Modifier.height(4.dp))
            TerminalButton(buttonText, onClick, enabled = enabled)
        }
        content()
    }
}


/**
 * Operator-facing capability dossier. This is deliberately separate
 * from execution: it tells the operator what exists, what is required,
 * how to drive it, and what level of verification the operation has.
 * The registry is the same source used by the planner and operation runner.
 */
@Composable
internal fun OperatorCapabilityPanel(
    area: OperationArea,
    operationIdPrefix: String? = null,
    connectionGuide: String,
    controlGuide: String,
    intelligenceGuide: String,
    liveState: String
) {
    var expanded by remember(area) { mutableStateOf(true) }
    val operations = remember(area, operationIdPrefix) {
        OperationRegistry.byArea(area).filter { operationIdPrefix == null || it.id.startsWith(operationIdPrefix) }
    }
    val intelligenceCapabilities = remember(area) {
        CapabilityRegistry.byCategory(area.capabilityCategory)
    }
    Column(
        modifier = Modifier
            .ghostCardChrome(true)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text("OPERATOR CAPABILITY / INTELLIGENCE", color = Accent,
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall)
        Text("WHAT IT CAN DO", color = Cyan, fontFamily = FontFamily.Monospace)
        Text(operations.joinToString("\n") { "• ${it.title}: ${it.description}" }
            .ifBlank { "Capabilities are still loading. Reopen this panel after the operation registry initializes." },
            color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Text("REGISTERED INTELLIGENCE CAPABILITIES", color = Cyan, fontFamily = FontFamily.Monospace)
        Text(
            intelligenceCapabilities.joinToString("\n\n") { capability ->
                buildString {
                    append(capability.title)
                    append(" [").append(capability.id).append("]")
                    append("\n").append(capability.detail)
                    if (capability.inputs.isNotEmpty()) append("\nINPUTS: ").append(capability.inputs.joinToString(", "))
                    if (capability.outputs.isNotEmpty()) append("\nOUTPUTS: ").append(capability.outputs.joinToString(", "))
                    if (capability.preconditions.isNotEmpty()) append("\nREQUIRES: ").append(capability.preconditions.joinToString("; "))
                    if (capability.effects.isNotBlank()) append("\nEFFECT: ").append(capability.effects)
                    append("\nSOURCE: ").append(capability.sourceMethod.ifBlank { "not specified" })
                    append(" • RISK: ").append(capability.riskLevel)
                    append(" • VERIFIED: ").append(if (capability.verified) "YES" else "NOT YET")
                }
            }.ifBlank { "No intelligence capabilities are registered in this category yet." },
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        Text("HOW IT CONNECTS", color = Cyan, fontFamily = FontFamily.Monospace)
        Text(connectionGuide, color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Text("HOW YOU CONTROL IT", color = Cyan, fontFamily = FontFamily.Monospace)
        Text(controlGuide, color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Text("ADAPTIVE INTELLIGENCE", color = Cyan, fontFamily = FontFamily.Monospace)
        Text(intelligenceGuide, color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Text("CURRENT STATE", color = Cyan, fontFamily = FontFamily.Monospace)
        Text(liveState, color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Text(if (expanded) "HIDE OPERATION REQUIREMENTS" else "SHOW OPERATION REQUIREMENTS",
            color = Accent, fontFamily = FontFamily.Monospace,
            modifier = Modifier.clickable { expanded = !expanded })
        if (expanded) {
            operations.forEach { operation ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF080D0B), RoundedCornerShape(6.dp))
                        .padding(9.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(operation.title, color = Accent, fontFamily = FontFamily.Monospace)
                    Text("CONTROL: ${operation.description}", color = Dim,
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    if (operation.prerequisites.isNotEmpty()) {
                        Text("NEEDS: ${operation.prerequisites.joinToString("; ")}",
                            color = Dim, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text("RISK: ${operation.riskLevel}  •  DEVICE-VERIFIED: ${if (operation.verified) "YES" else "NOT YET VERIFIED"}",
                        color = if (operation.verified) Accent else Cyan,
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
