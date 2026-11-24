package com.ghosttap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ghosttap.core.TaskWorkspaceSession

/**
 * Shared continuation surface for function workspaces. It renders only
 * observed task/session context; transport handles remain with their owners.
 */
@Composable
fun TaskWorkspacePanel(
    session: TaskWorkspaceSession?,
    onContinue: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (session == null) return
    var nextInstruction by remember(session.taskId) { mutableStateOf("") }
    var expanded by remember(session.taskId) { mutableStateOf(false) }
    val connectionLabel = when (session.connectionState) {
        "CONNECTED" -> "Live connection"
        "CONTEXT_READY" -> "Context retained · NFC tag not present"
        "CONNECTION_LOST" -> "Connection lost"
        "CONNECTION_UNVERIFIED" -> "Connection not yet verified"
        "CONNECTING" -> "Connecting"
        "FAILED" -> "Setup failed"
        else -> session.connectionState.replace('_', ' ').lowercase()
            .replaceFirstChar { it.uppercase() }
    }
    val connectionColor = when (session.connectionState) {
        "CONNECTED" -> Color(0xFF44FF99)
        "CONTEXT_READY" -> Color(0xFF7CCFC0)
        "FAILED", "CONNECTION_LOST" -> Color(0xFFFF8A80)
        else -> Color(0xFFFFC078)
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xFF101A17),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("ACTIVE TASK", color = Color(0xFF7AFFC4), style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Collapse" else "Open task")
                }
            }
            Text(session.objective, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(
                "${session.targetTitle} · ${session.targetKind} · $connectionLabel",
                color = connectionColor,
                style = MaterialTheme.typography.bodySmall
            )
            if (!expanded && session.lastResult.isNotBlank()) {
                Text(session.lastResult.take(120), color = Color(0xFFB6C8BF), style = MaterialTheme.typography.bodySmall)
            }
            if (expanded && session.controls.isNotEmpty()) {
                Text("AVAILABLE", color = Color(0xFF7AFFC4), style = MaterialTheme.typography.labelSmall)
                session.controls.take(5).forEach {
                    Text("• $it", color = Color(0xFFD6E5DE), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (expanded && session.lastResult.isNotBlank()) {
                Text("LAST RESULT", color = Color(0xFF7AFFC4), style = MaterialTheme.typography.labelSmall)
                Text(session.lastResult, color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
            if (expanded && session.evidence.isNotEmpty()) {
                Text("LATEST EVIDENCE", color = Color(0xFF7AFFC4), style = MaterialTheme.typography.labelSmall)
                session.evidence.takeLast(3).forEach {
                    Text(it, color = Color(0xFFB6C8BF), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (expanded && session.events.isNotEmpty()) {
                Text("SESSION HISTORY", color = Color(0xFF7AFFC4), style = MaterialTheme.typography.labelSmall)
                session.events.takeLast(3).forEach {
                    Text("• $it", color = Color(0xFFB6C8BF), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (expanded) {
                OutlinedTextField(
                    value = nextInstruction,
                onValueChange = { nextInstruction = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("What should Ghost Tap do next?") },
                singleLine = false
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    enabled = nextInstruction.isNotBlank(),
                    onClick = {
                        val instruction = nextInstruction.trim()
                        nextInstruction = ""
                        onContinue(instruction)
                    }
                ) { Text("CONTINUE TASK") }
                }
            }
        }
    }
}
