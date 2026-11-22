package com.ghosttap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.StrokeCap
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

private val GhostCardShape = RoundedCornerShape(16.dp)

private fun Modifier.ghostCardChrome(enabled: Boolean): Modifier = this
    .fillMaxWidth()
    .background(
        Brush.linearGradient(listOf(Color(0xFF14211F), Color(0xFF0B1114), Color(0xFF101010))),
        GhostCardShape
    )
    .border(
        1.dp,
        Brush.linearGradient(listOf(Accent.copy(alpha = if (enabled) 0.42f else 0.16f), Cyan.copy(alpha = 0.18f))),
        GhostCardShape
    )

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
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .ghostCardChrome(enabled)
            .clickable(enabled = enabled) { onClick() }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        CapabilityArtwork(title)
        Text(
            title,
            color = Accent,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            description,
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3
        )
        Text(
            "OPEN MODULE  ›",
            color = Cyan,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Original vector artwork for the seven Ghost Tap modules.
 * These are drawn inside the APK with Compose Canvas (not emoji,
 * generic Material glyphs, or remote images), so they render sharply
 * at every screen density and work offline.
 */
@Composable
private fun CapabilityArtwork(title: String) {
    val green = Color(0xFF8DFFB2)
    val cyan = Color(0xFF4DD8FF)
    val dark = Color(0xFF081A19)
    Canvas(modifier = Modifier.fillMaxWidth().height(76.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            brush = Brush.linearGradient(listOf(Color(0xFF153B34), Color(0xFF101E2A), Color(0xFF17152A))),
            size = Size(w, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx())
        )
        // Subtle technical grid gives each module art its own visual field.
        for (x in 12..w.toInt() step 18) {
            drawLine(Color.White.copy(alpha = 0.035f), Offset(x.toFloat(), 0f), Offset(x.toFloat(), h), 1f)
        }
        for (y in 10..h.toInt() step 18) {
            drawLine(Color.White.copy(alpha = 0.035f), Offset(0f, y.toFloat()), Offset(w, y.toFloat()), 1f)
        }
        val cx = w * 0.5f
        val cy = h * 0.5f
        val s = h / 76f
        when {
            title.contains("USB", true) -> {
                drawRoundRect(green, Offset(cx-17*s, cy-15*s), Size(34*s, 30*s), androidx.compose.ui.geometry.CornerRadius(5*s), style=Stroke(2.4f*s))
                drawLine(cyan, Offset(cx, cy-15*s), Offset(cx, cy-29*s), 3*s, cap=StrokeCap.Round)
                drawCircle(cyan, 4*s, Offset(cx, cy-31*s))
                drawLine(green, Offset(cx-17*s, cy), Offset(cx-31*s, cy), 3*s, cap=StrokeCap.Round)
                drawCircle(green, 4*s, Offset(cx-33*s, cy))
                drawLine(green, Offset(cx+17*s, cy+5*s), Offset(cx+30*s, cy+17*s), 3*s, cap=StrokeCap.Round)
                drawRect(cyan, Offset(cx+26*s, cy+14*s), Size(8*s, 8*s))
                for (i in -2..2) drawLine(Color.White.copy(alpha=.65f), Offset(cx+i*6*s, cy+15*s), Offset(cx+i*6*s, cy+20*s), 1.5f*s)
            }
            title.contains("VIRTUAL", true) -> {
                drawRoundRect(Color(0xFF164E42), Offset(cx-27*s, cy-22*s), Size(54*s,44*s), androidx.compose.ui.geometry.CornerRadius(7*s))
                for (i in -3..3) {
                    val px=cx+i*7*s
                    drawLine(green, Offset(px,cy-28*s),Offset(px,cy-22*s),2*s)
                    drawLine(green, Offset(px,cy+22*s),Offset(px,cy+28*s),2*s)
                }
                for (i in -2..2) {
                    val py=cy+i*7*s
                    drawLine(green,Offset(cx-33*s,py),Offset(cx-27*s,py),2*s)
                    drawLine(green,Offset(cx+27*s,py),Offset(cx+33*s,py),2*s)
                }
                drawRoundRect(dark, Offset(cx-15*s,cy-12*s), Size(30*s,24*s), androidx.compose.ui.geometry.CornerRadius(4*s), style=Stroke(2*s))
                drawCircle(cyan,3.5*s,Offset(cx,cy))
                drawLine(cyan,Offset(cx,cy-7*s),Offset(cx,cy+7*s),1.5f*s)
                drawLine(cyan,Offset(cx-7*s,cy),Offset(cx+7*s,cy),1.5f*s)
            }
            title.contains("NETWORK", true) -> {
                val pts=listOf(Offset(cx,cy),Offset(cx-31*s,cy-17*s),Offset(cx+31*s,cy-17*s),Offset(cx-31*s,cy+19*s),Offset(cx+31*s,cy+19*s))
                for (i in 1 until pts.size) drawLine(cyan,pts[0],pts[i],2*s)
                pts.drop(1).forEach { drawCircle(green,7*s,it); drawCircle(dark,3*s,it) }
                drawCircle(cyan,11*s,pts[0]); drawCircle(dark,4*s,pts[0])
            }
            title.contains("NFC", true) -> {
                drawRoundRect(green,Offset(cx-15*s,cy-25*s),Size(30*s,50*s),androidx.compose.ui.geometry.CornerRadius(5*s),style=Stroke(2.2f*s))
                drawLine(green,Offset(cx-5*s,cy+18*s),Offset(cx+5*s,cy+18*s),2*s)
                for (i in 0..2) {
                    val radius=(12+i*8)*s
                    drawArc(cyan, -55f,110f,false,Offset(cx- radius,cy-radius),Size(radius*2,radius*2),style=Stroke(2*s))
                    drawArc(cyan,125f,110f,false,Offset(cx-radius,cy-radius),Size(radius*2,radius*2),style=Stroke(2*s))
                }
            }
            title.contains("EMV", true) -> {
                drawRoundRect(Color(0xFF183D4C),Offset(cx-37*s,cy-22*s),Size(74*s,44*s),androidx.compose.ui.geometry.CornerRadius(7*s),style=Stroke(2*s))
                drawRoundRect(green,Offset(cx-25*s,cy-10*s),Size(18*s,20*s),androidx.compose.ui.geometry.CornerRadius(3*s),style=Stroke(1.8f*s))
                drawLine(green,Offset(cx-25*s,cy),Offset(cx-7*s,cy),1.2f*s)
                drawLine(green,Offset(cx-16*s,cy-10*s),Offset(cx-16*s,cy+10*s),1.2f*s)
                drawLine(cyan,Offset(cx+3*s,cy-9*s),Offset(cx+25*s,cy-9*s),2*s)
                drawLine(cyan,Offset(cx+3*s,cy),Offset(cx+29*s,cy),2*s)
                drawLine(cyan,Offset(cx+3*s,cy+9*s),Offset(cx+20*s,cy+9*s),2*s)
            }
            title.contains("VISUAL", true) -> {
                drawRoundRect(Color(0xFF17423D),Offset(cx-35*s,cy-20*s),Size(70*s,40*s),androidx.compose.ui.geometry.CornerRadius(9*s))
                drawRoundRect(green,Offset(cx-25*s,cy-27*s),Size(18*s,8*s),androidx.compose.ui.geometry.CornerRadius(3*s))
                drawCircle(cyan,16*s,Offset(cx+2*s,cy))
                drawCircle(dark,11*s,Offset(cx+2*s,cy))
                drawCircle(green,5*s,Offset(cx+2*s,cy))
                drawCircle(Color.White.copy(alpha=.75f),2*s,Offset(cx-2*s,cy-4*s))
                drawCircle(green,3*s,Offset(cx+25*s,cy-10*s))
            }
            else -> {
                drawCircle(Color(0xFF1B5360),22*s,Offset(cx-4*s,cy-5*s),style=Stroke(3*s))
                drawCircle(cyan,14*s,Offset(cx-4*s,cy-5*s),style=Stroke(2*s))
                drawLine(green,Offset(cx+12*s,cy+11*s),Offset(cx+31*s,cy+29*s),5*s,cap=StrokeCap.Round)
                drawCircle(green,4*s,Offset(cx-4*s,cy-5*s))
                drawCircle(cyan,3*s,Offset(cx-29*s,cy-27*s))
                drawLine(cyan,Offset(cx-26*s,cy-24*s),Offset(cx-17*s,cy-15*s),1.5f*s)
            }
        }
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
                modifier = Modifier.size(24.dp)
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
