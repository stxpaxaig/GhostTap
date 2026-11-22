package com.ghosttap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ghosttap.GhostTapViewModel
import com.ghosttap.core.EmvEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** EMV workspace. Live chip inspection and synthetic fixture testing stay separate. */
@Composable
fun EmvLabScreen(
    viewModel: GhostTapViewModel,
    onBack: () -> Unit,
    commandStatusLine: String = "",
    commandRequest: String = "",
    commandLines: List<String> = emptyList(),
    commandBusy: Boolean = false,
    commandTvCandidates: List<com.ghosttap.core.TvCandidate> = emptyList(),
    commandShowTryAgain: Boolean = false,
    onDoIt: ((String) -> Unit)? = null,
    onPickTv: ((com.ghosttap.core.TvCandidate) -> Unit)? = null,
    onTryAgain: (() -> Unit)? = null,
    onClearCommand: (() -> Unit)? = null,
    showTermuxSetup: Boolean = false,
    onTermuxSetup: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var resultTitle by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    val tagAvailable = viewModel.lastNfcTag != null

    Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onBack) { Text("BACK") }
                Spacer(Modifier.weight(1f))
                Text("GHOST TAP / EMV", color = Accent, fontWeight = FontWeight.Bold)
            }
            Text("EMV LAB", color = Accent, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(
                "Payment-chip inspection and controlled test workspace. Live card reading and synthetic testing stay separate.",
                color = Dim,
                style = MaterialTheme.typography.bodyMedium
            )

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

            GhostActionCard(
                title = "READ A TAPPED PAYMENT CHIP",
                description = if (tagAvailable) "A tag is available. Read its supported EMV application data locally on this phone." else "Tap a compatible contactless test card on the phone, then return here to inspect it.",
                buttonText = if (busy) "READING…" else "READ TAPPED CARD",
                onClick = {
                    val currentTag = viewModel.lastNfcTag
                    if (currentTag == null) {
                        resultTitle = "LIVE CARD READ"
                        result = "No NFC tag is currently available. Open NFC Tools, tap the card against the phone, and return here."
                    } else {
                        busy = true
                        resultTitle = "LIVE CARD READ"
                        result = "Reading chip locally…"
                        scope.launch {
                            try {
                                val outcome = withContext(Dispatchers.IO) { EmvEngine.readLiveCard(currentTag) }
                                result = outcome.report
                            } catch (error: Exception) {
                                result = "The local read stopped: ${error.message ?: "unknown error"}"
                            } finally {
                                busy = false
                            }
                        }
                    }
                },
                enabled = !busy && tagAvailable
            )

            GhostActionCard(
                title = "RUN SYNTHETIC TEST FIXTURE",
                description = "Exercise the EMV command flow with the built-in recorded test card. This does not use a live bank card.",
                buttonText = if (busy) "RUNNING…" else "RUN LOCAL TEST",
                onClick = {
                    busy = true
                    resultTitle = "SYNTHETIC TEST"
                    result = "Running the local fixture…"
                    scope.launch {
                        try {
                            val outcome = withContext(Dispatchers.Default) { EmvEngine.runDemoCard() }
                            result = outcome.report
                        } catch (error: Exception) {
                            result = "The local test stopped: ${error.message ?: "unknown error"}"
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy
            )

            GhostActionCard(
                title = "AUTHORIZATION SIMULATOR",
                description = "Not connected. The simulator adapter must be configured and verified before any request can be sent. Only synthetic fixture data may be used; live card data is never sent to a simulator."
            )

            if (result.isNotBlank()) {
                GhostActionCard(
                    title = resultTitle.ifBlank { "RESULT" },
                    description = "Local result from Ghost Tap. A fixture result is not a live issuer authorization."
                ) {
                    Text(result, color = Color.White, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }

            Text(
                "This workspace does not submit payments, contact a bank, or treat a recorded fixture response as a real authorization.",
                color = Dim,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
