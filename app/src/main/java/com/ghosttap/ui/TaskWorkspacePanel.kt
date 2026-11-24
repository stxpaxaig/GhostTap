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
            Text("ACTIVE TASK", color = Color(0xFF7AFFC4), style = MaterialTheme.typography.labelMedium)
            Text(session.objective, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(
                "${session.targetTitle} · ${session.targetKind} · ${session.connectionState}",
                color = if (session.connectionState == "CONNECTED") Color(0xFF44FF99) else Color(0xFFFFC078),
                style = MaterialTheme.typography.bodySmall
            )
            if (session.controls.isNotEmpty()) {
                Text("AVAILABLE", color = Color(0xFF7AFFC4), style = MaterialTheme.typography.labelSmall)
                session.controls.take(5).forEach {
                    Text("• $it", color = Color(0xFFD6E5DE), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (session.lastResult.isNotBlank()) {
                Text("LAST RESULT", color = Color(0xFF7AFFC4), style = MaterialTheme.typography.labelSmall)
                Text(session.lastResult, color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
            if (session.evidence.isNotEmpty()) {
                Text("LATEST EVIDENCE", color = Color(0xFF7AFFC4), style = MaterialTheme.typography.labelSmall)
                session.evidence.takeLast(3).forEach {
                    Text(it, color = Color(0xFFB6C8BF), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (session.events.isNotEmpty()) {
                Text("SESSION HISTORY", color = Color(0xFF7AFFC4), style = MaterialTheme.typography.labelSmall)
                session.events.takeLast(3).forEach {
                    Text("• $it", color = Color(0xFFB6C8BF), style = MaterialTheme.typography.bodySmall)
                }
            }
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
