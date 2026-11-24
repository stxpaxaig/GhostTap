package com.ghosttap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import com.ghosttap.core.TvCandidate

private val ComposerSurface = Color(0xFF121C27)
private val ComposerLine = Color(0xFF2A3B4A)
private val ComposerAccent = Color(0xFF62D6C5)
private val ComposerText = Color(0xFFEAF2F7)
private val ComposerMuted = Color(0xFF9AAAB8)

@Composable
fun CommandBox(
    statusLine: String,
    request: String,
    lines: List<String>,
    busy: Boolean,
    tvCandidates: List<TvCandidate>,
    showTryAgain: Boolean,
    onSubmit: (String) -> Unit,
    onPickTv: (TvCandidate) -> Unit,
    onTryAgain: () -> Unit,
    onClear: () -> Unit,
    showTermuxSetup: Boolean = false,
    onTermuxSetup: (() -> Unit)? = null
) {
    var text by remember { mutableStateOf("") }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = ComposerSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, ComposerLine)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text("WHAT SHOULD GHOST TAP DO?", color = ComposerAccent, style = MaterialTheme.typography.labelLarge)
            Text(
                "Describe the outcome. Ghost Tap will investigate, select the available tools, and open the right workspace when a live connection is ready.",
                color = ComposerMuted,
                style = MaterialTheme.typography.bodySmall
            )
            if (statusLine.isNotBlank()) {
                Surface(color = Color(0xFF0B1118), shape = RoundedCornerShape(12.dp)) {
                    Text(statusLine, modifier = Modifier.padding(11.dp), color = ComposerMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (showTermuxSetup) {
                Button(
                    onClick = { onTermuxSetup?.invoke() },
                    colors = ButtonDefaults.buttonColors(containerColor = ComposerAccent, contentColor = Color(0xFF071512))
                ) { Text("Prepare command tools") }
                Text("Termux needs its one-time helper setup before these commands can run.", color = ComposerMuted, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Describe what you want to accomplish") },
                placeholder = { Text("Example: connect to my LED sign and change its message") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                shape = RoundedCornerShape(15.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ComposerAccent,
                    unfocusedBorderColor = ComposerLine,
                    focusedLabelColor = ComposerAccent,
                    unfocusedLabelColor = ComposerMuted,
                    focusedTextColor = ComposerText,
                    unfocusedTextColor = ComposerText,
                    cursorColor = ComposerAccent
                )
            )
            Button(
                onClick = { if (text.isNotBlank()) onSubmit(text.trim()) },
                enabled = !busy && text.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ComposerAccent, contentColor = Color(0xFF071512))
            ) {
                Text(if (busy) "Investigating and connecting…" else "Start task")
            }
            if (lines.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text("TASK ACTIVITY", color = ComposerAccent, style = MaterialTheme.typography.labelLarge)
                Text(request, color = ComposerText, style = MaterialTheme.typography.bodySmall)
                lines.forEach { line ->
                    Text("• $line", color = ComposerMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (tvCandidates.isNotEmpty()) {
                Text("Choose the device to connect:", color = ComposerText, style = MaterialTheme.typography.titleSmall)
                tvCandidates.forEach { candidate ->
                    Button(
                        onClick = { onPickTv(candidate) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF203242), contentColor = ComposerText)
                    ) { Text("${candidate.name} · ${candidate.confidenceWords}") }
                }
            }
            if (showTryAgain) {
                Button(
                    onClick = onTryAgain,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF203242), contentColor = ComposerText)
                ) { Text("Try again") }
            }
            if (lines.isNotEmpty() || tvCandidates.isNotEmpty()) {
                Button(
                    onClick = onClear,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = ComposerMuted)
                ) { Text("Clear task activity") }
            }
        }
    }
}
