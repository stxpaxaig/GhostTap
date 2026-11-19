package com.ghosttap.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ghosttap.core.TvCandidate

/**
 * v3.2 — home command box: DO-IT (replaces the v3.1 plan box).
 *
 * One box at the top of the home screen. The user types what they
 * want in plain words and taps "Do it"; Ghost Tap runs it right
 * here and shows what actually happened, line by line, under the
 * box. There is no plan to read, no Run button per step, no "you
 * decide what happens next" — the outcome IS the result on the
 * screen (devices found, connected, controls opened), not a report
 * of steps taken.
 *
 * The box itself matches nothing and runs nothing: [onSubmit] hands
 * the typed words to the app shell, which runs them. The one
 * exception to "no taps" is picking one device out of several found
 * ([tvCandidates] — one tap on a row connects that one); a single
 * clear match connects itself. [onTryAgain] re-runs the last
 * command (for example after turning the TV's Bluetooth on).
 *
 * Plain-words rule: only real outcomes are shown, in the order they
 * happened. No text here promises a timeline, list, or result that
 * the screen does not show.
 */
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
    // UNIFIED BUILD (2026-11-18) — the one-paste Termux setup is
    // reachable from this status line only: when Termux is there but
    // the helper tools aren't in yet, one tap copies the setup and
    // opens Termux. Never shown when Termux is ready.
    showTermuxSetup: Boolean = false,
    onTermuxSetup: (() -> Unit)? = null
) {
    var text by remember { mutableStateOf("") }
    PanelBox("TELL GHOST TAP WHAT YOU WANT") {
        if (statusLine.isNotBlank()) {
            Text(
                statusLine,
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
        if (showTermuxSetup) {
            TerminalButton("Set up Termux — one paste", onClick = { onTermuxSetup?.invoke() })
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Termux isn't ready yet — tap here, paste once in Termux, press Enter, then come back.",
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
        Text(
            "Type it in plain words — Ghost Tap does it right here. No plan to tap through, no setup.",
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("What do you want Ghost Tap to do?") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        TerminalButton(
            if (busy) "Working…" else "Do it",
            onClick = { if (text.isNotBlank()) onSubmit(text.trim()) },
            enabled = !busy
        )
        if (lines.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "WHAT HAPPENED — for \"$request\"",
                color = Accent,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            lines.forEach { line ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    line,
                    color = Color(0xFFA9E5C5),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        if (tvCandidates.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Tap the one you want:",
                color = Accent,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            tvCandidates.forEach { candidate ->
                Spacer(modifier = Modifier.height(4.dp))
                TerminalButton(
                    "${candidate.name} — ${candidate.confidenceWords}",
                    onClick = { onPickTv(candidate) },
                    enabled = !busy
                )
            }
        }
        if (showTryAgain) {
            Spacer(modifier = Modifier.height(6.dp))
            TerminalButton("Try again", onClick = onTryAgain, enabled = !busy)
        }
        if (lines.isNotEmpty() || tvCandidates.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            TerminalButton("Start over", onClear)
        }
    }
}
