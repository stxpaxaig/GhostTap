package com.ghosttap.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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

private val EmvInk = Color(0xFF0A1118)
private val EmvCard = Color(0xFF14212D)
private val EmvMint = Color(0xFF5DF0B2)
private val EmvMuted = Color(0xFF9BB0BF)

/**
 * Visual EMV workspace. The live-card path and synthetic fixture are
 * deliberately separate: only the fixture is eligible for a future
 * simulator adapter. No live card data is sent to a remote service.
 */
@Composable
fun EmvLabScreen(
    viewModel: GhostTapViewModel,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var resultKind by remember { mutableStateOf("") }
    val tag = viewModel.lastNfcTag

    Surface(modifier = Modifier.fillMaxSize(), color = EmvInk) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack) { Text("BACK") }
                Spacer(Modifier.weight(1f))
                Text("GHOST TAP / LAB", color = EmvMint, fontWeight = FontWeight.Bold)
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("EMV LAB", color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("Payment-chip inspection and controlled test workspace", color = EmvMuted, style = MaterialTheme.typography.bodyMedium)
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = EmvCard)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("◉", color = EmvMint, style = MaterialTheme.typography.headlineLarge)
                        Spacer(Modifier.padding(4.dp))
                        Column {
                            Text("NFC CHIP READER", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(if (tag == null) "No card currently in range" else "A tag is available to inspect", color = EmvMuted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Button(
                        onClick = {
                            val currentTag = viewModel.lastNfcTag
                            if (currentTag == null) {
                                resultKind = "LIVE CARD"
                                result = "No NFC tag is available. Return to NFC Tools, tap the card against the phone, then open EMV Lab again."
                            } else {
                                running = true
                                resultKind = "LIVE CARD READ"
                                result = "Reading chip…"
                                scope.launch {
                                    val outcome = withContext(Dispatchers.IO) { EmvEngine.readLiveCard(currentTag) }
                                    result = outcome.report
                                    running = false
                                }
                            }
                        },
                        enabled = !running && tag != null,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (running) "READING…" else "READ TAPPED CARD") }
                    Text("Reads the card locally through Android NFC. This action does not submit a payment or contact a bank.", color = EmvMuted, style = MaterialTheme.typography.bodySmall)
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = EmvCard)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("◇", color = EmvMint, style = MaterialTheme.typography.headlineLarge)
                        Spacer(Modifier.padding(4.dp))
                        Column {
                            Text("SYNTHETIC TEST FIXTURE", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("Recorded test-card APDU exchange", color = EmvMuted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            running = true
                            resultKind = "LOCAL FIXTURE"
                            result = "Running recorded test fixture…"
                            scope.launch {
                                val outcome = withContext(Dispatchers.Default) { EmvEngine.runDemoCard() }
                                result = outcome.report
                                running = false
                            }
                        },
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("RUN LOCAL TEST FIXTURE") }
                    Text("Synthetic data only. The fixture's recorded response is not a live issuer authorization.", color = EmvMuted, style = MaterialTheme.typography.bodySmall)
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2028))
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SIMULATOR CONNECTION", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text("NOT CONFIGURED", color = Color(0xFFFFC36A), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                    }
                    Text("External authorization simulator is not connected yet. No endpoint or request format is assumed. Only synthetic fixture data may be passed to a future test adapter.", color = EmvMuted, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (result.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF101A22))
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(resultKind, color = EmvMint, fontWeight = FontWeight.Bold)
                        Text(result, color = Color.White, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
