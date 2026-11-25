package com.ghosttap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val CommandBg = Color(0xFF0B1118)
private val CommandSurface = Color(0xFF121C27)
private val CommandLine = Color(0xFF263748)
private val CommandAccent = Color(0xFF62D6C5)
private val CommandText = Color(0xFFEAF2F7)
private val CommandMuted = Color(0xFF9AAAB8)

@Composable
fun GhostCommandCenterHeader(
    status: String,
    bluetoothEnabled: Boolean,
    scanning: Boolean,
    targetCount: Int,
    activeTaskTitle: String? = null,
    activeTaskState: String? = null,
    activeTaskRoute: String? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CommandBg, RoundedCornerShape(24.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = RoundedCornerShape(14.dp),
                color = CommandAccent.copy(alpha = 0.14f)
            ) {
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                    Text("G", color = CommandAccent, style = MaterialTheme.typography.titleLarge)
                }
            }
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text("GHOST TAP", color = CommandText, style = MaterialTheme.typography.titleLarge)
                Text("Unified device intelligence", color = CommandMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            "Tell Ghost Tap what you want to accomplish. It investigates, chooses the tools, and brings you into the right live workspace.",
            color = CommandText,
            style = MaterialTheme.typography.bodyMedium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill("Bluetooth", if (bluetoothEnabled) "On" else "Off", bluetoothEnabled)
            StatusPill("Discovery", if (scanning) "Running" else "Idle", scanning)
            StatusPill("Targets", targetCount.toString(), targetCount > 0)
        }
        if (!activeTaskTitle.isNullOrBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = CommandAccent.copy(alpha = 0.09f),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, CommandAccent.copy(alpha = 0.32f))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text("ACTIVE TASK · ${activeTaskState ?: "IN CONTEXT"}", color = CommandAccent, style = MaterialTheme.typography.labelSmall)
                    Text(activeTaskTitle, color = CommandText, style = MaterialTheme.typography.titleSmall)
                    if (!activeTaskRoute.isNullOrBlank()) {
                        Text("WORKSPACE  /  $activeTaskRoute", color = CommandMuted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (status.isNotBlank()) {
            Text(status, color = CommandMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatusPill(label: String, value: String, active: Boolean) {
    Surface(
        color = if (active) CommandAccent.copy(alpha = 0.12f) else CommandSurface,
        shape = RoundedCornerShape(50),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (active) CommandAccent.copy(alpha = 0.45f) else CommandLine)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = CommandMuted, style = MaterialTheme.typography.labelSmall)
            Text(value, color = if (active) CommandAccent else CommandText, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun GhostFunctionSection(
    onUsb: () -> Unit,
    onVirtualEsp32: () -> Unit,
    onNetwork: () -> Unit,
    onNfc: () -> Unit,
    onVisual: () -> Unit,
    onInvestigations: () -> Unit,
    onLogs: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("FUNCTION WORKSPACES", color = CommandText, style = MaterialTheme.typography.titleMedium)
        Text(
            "Each function keeps its own tools and controls. Ghost Tap can route a task here with its discovered context.",
            color = CommandMuted,
            style = MaterialTheme.typography.bodySmall
        )
        FunctionCard("USB UART / ESP32", "Wired boards, serial communication and firmware", "USB", onUsb)
        FunctionCard("VirtualESP32", "Virtual board runtime and GPIO control", "ESP", onVirtualEsp32)
        FunctionCard("Network tools", "Discover hosts, inspect services and manage network sessions", "NET", onNetwork)
        FunctionCard("NFC tools", "Read and work with supported NFC tags and sessions", "NFC", onNfc)
        FunctionCard("Visual intelligence", "Use the camera to inspect devices, boards and labels", "VIS", onVisual)
        FunctionCard("Investigations", "Review evidence, device research and intelligence activity", "INT", onInvestigations)
        FunctionCard("Activity log", "Review Ghost Tap operations and outcomes", "LOG", onLogs)
    }
}

@Composable
private fun FunctionCard(title: String, detail: String, symbol: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CommandSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, CommandLine)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(13.dp),
                color = CommandAccent.copy(alpha = 0.12f)
            ) {
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                    Text(symbol, color = CommandAccent, style = MaterialTheme.typography.labelLarge)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = CommandText, style = MaterialTheme.typography.titleSmall)
                Text(detail, color = CommandMuted, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", color = CommandAccent, style = MaterialTheme.typography.headlineMedium)
        }
    }
}
