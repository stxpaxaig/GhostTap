package com.ghosttap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ghosttap.core.ops.Operation
import com.ghosttap.core.ops.OperationArea
import com.ghosttap.core.vnic.VnicCapabilityInfo
import com.ghosttap.core.vnic.VnicStatusSnapshot
import kotlinx.coroutines.flow.StateFlow

/** Live view of Ghost Tap's registered operations and VNIC runtime. */
@Composable
fun CapabilityAtlasScreen(
    operations: List<Operation>,
    capabilities: List<VnicCapabilityInfo>,
    statusFlow: StateFlow<VnicStatusSnapshot>,
    onRunOperation: (String) -> Unit,
    onBack: () -> Unit
) {
    val status by statusFlow.collectAsState()
    var query by remember { mutableStateOf("") }
    var selectedArea by remember { mutableStateOf("ALL") }
    val areas = listOf("ALL") + OperationArea.values().map { it.name }
    val filtered = operations.filter { op ->
        val areaMatch = selectedArea == "ALL" || op.area.name == selectedArea
        val haystack = (op.title + " " + op.description + " " + op.id + " " + op.area.label).lowercase()
        areaMatch && (query.isBlank() || haystack.contains(query.trim().lowercase()))
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("CAPABILITY ATLAS", color = Accent, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
                Text("Live map of Ghost Tap's registered tools and actions.", color = Dim, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onBack) { Text("BACK", color = Accent) }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101A16)), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("VNIC INTELLIGENCE", color = Accent, fontFamily = FontFamily.Monospace)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("OPERATIONS", operations.size.toString())
                    Metric("VNIC TOOLS", capabilities.size.toString())
                    Metric("CYCLE", if (status.lastCycleMs > 0) status.lastCycleMs.toString() + " ms" else "—")
                }
                Text("Memory: " + status.memoryCounts.values.sum() + " records · Procedures: " + status.procedureCount + " · Store: " + if (status.storeBackedByRoom) "persistent" else "in-memory fallback", color = Dim, style = MaterialTheme.typography.bodySmall)
                if (status.lastSubject.isNotBlank()) {
                    Text("Last subject: " + status.lastSubject, color = Color.White, style = MaterialTheme.typography.bodySmall)
                    Text("Last state: " + status.lastState + " · Confidence: " + (status.lastConfidence * 100).toInt() + "%", color = Dim, style = MaterialTheme.typography.bodySmall)
                }
                if (status.lastPhases.isNotEmpty()) {
                    Text("LATEST COGNITION TRACE", color = Accent, fontFamily = FontFamily.Monospace)
                    status.lastPhases.takeLast(5).forEach { Text(it, color = Dim, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Search tools, devices, protocols, actions") }, singleLine = true
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            areas.forEach { area ->
                Surface(
                    color = if (selectedArea == area) Accent.copy(alpha = 0.18f) else Color(0xFF151515),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.clickable { selectedArea = area }
                ) {
                    Text(
                        if (area == "ALL") "ALL" else OperationArea.valueOf(area).label.uppercase(),
                        color = if (selectedArea == area) Accent else Dim,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
        Text(filtered.size.toString() + " OF " + operations.size + " REGISTERED ACTIONS", color = Accent, fontFamily = FontFamily.Monospace)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            filtered.forEach { op ->
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(op.title, color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text(op.riskLevel, color = Accent, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        }
                        Text(op.description, color = Dim, style = MaterialTheme.typography.bodySmall)
                        Text(op.area.label + " · " + if (op.verified) "hardware exercised" else "not yet hardware-verified", color = Color(0xFF9BADA4), style = MaterialTheme.typography.labelSmall)
                        Text(op.id, color = Color(0xFF718078), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        if (op.prerequisites.isNotEmpty()) Text("Needs: " + op.prerequisites.joinToString(" · "), color = Dim, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { onRunOperation(op.id) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (op.isScreenFirst) "OPEN WORKSPACE" else "RUN ACTION")
                        }
                    }
                }
            }
            if (filtered.isEmpty()) Text("No registered actions match this search.", color = Dim)
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = Color.White, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
        Text(label, color = Dim, style = MaterialTheme.typography.labelSmall)
    }
}
