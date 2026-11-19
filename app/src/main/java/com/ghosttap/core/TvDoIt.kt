package com.ghosttap.core

import com.ghosttap.data.ScanTarget

/**
 * v3.2 — DO-IT: find a TV close by and connect it to a controllable state.
 *
 * Pure Kotlin, no Android framework, no network, no database — the
 * matching and ranking here runs on what a scan already found. The
 * actual scan and connect run through the existing paths
 * ([IntelEngine] `ble.start_scan` and the bring-up pipeline); this
 * file only decides WHAT counts as a TV and HOW SURE Ghost Tap is.
 *
 * Rules, all plain words:
 * - A device Ghost Tap already identified as a TV (`likely == "TV"`)
 *   is a candidate, with the scan's own confidence.
 * - A device Ghost Tap already identified as something else
 *   (headphones, phone, speaker…) is NEVER a TV, even when its name
 *   carries a TV brand — Samsung makes phones too.
 * - An unidentified device whose name says TV (or a streaming box)
 *   is a candidate at Medium confidence; a TV brand name on its own
 *   is only a weak hint (Low) and never auto-connects.
 * - Candidates rank by confidence first, then by signal (closer first).
 * - Auto-connect needs exactly ONE candidate at Medium confidence or
 *   better. Several, or one weak hint, means the user picks — one tap.
 */
data class TvCandidate(
    val targetId: String,
    val name: String,
    val confidence: Confidence,
    /** Plain-words confidence: "almost certainly a TV", "looks like a TV", "might be a TV". */
    val confidenceWords: String,
    /** Signal strength in dBm when known — closer (higher) first. Null sorts last. */
    val signalDbm: Int? = null
)

/** Words that mean a TV (or a box that turns a TV smart). */
private val tvWords = Regex(
    "(?i)\\b(tv|television|smart ?tv|roku|fire ?tv|chromecast|bravia|apple ?tv|shield ?tv|qled|oled ?tv|webos|tizen|the frame)\\b"
)

/** TV maker names — a weak hint on their own, never proof. */
private val tvBrandWords = Regex(
    "(?i)\\b(samsung|lg|sony|tcl|hisense|vizio|panasonic|sharp|insignia|toshiba)\\b"
)

/** True when the typed words are asking about a TV. */
fun isTvCommand(text: String): Boolean = tvWords.containsMatchIn(text)

fun tvConfidenceWords(confidence: Confidence): String = when (confidence) {
    Confidence.HIGH -> "almost certainly a TV"
    Confidence.MEDIUM -> "looks like a TV"
    Confidence.LOW -> "might be a TV"
}

/** Every scanned device that could be a TV, best first. */
fun findTvCandidates(targets: List<ScanTarget>): List<TvCandidate> {
    val out = mutableListOf<TvCandidate>()
    for (t in targets) {
        val label = t.likely?.trim().orEmpty()
        val name = t.name.ifBlank { t.id }
        when {
            label.equals("TV", ignoreCase = true) -> {
                val conf = when (t.confidence?.lowercase()) {
                    "high" -> Confidence.HIGH
                    "low" -> Confidence.LOW
                    else -> Confidence.MEDIUM
                }
                out += TvCandidate(t.id, name, conf, tvConfidenceWords(conf), t.rssi)
            }
            // Already identified as something else — never a TV.
            label.isNotBlank() && !label.startsWith("UNKNOWN", ignoreCase = true) -> Unit
            tvWords.containsMatchIn(t.name) -> {
                out += TvCandidate(
                    t.id, name, Confidence.MEDIUM,
                    tvConfidenceWords(Confidence.MEDIUM), t.rssi
                )
            }
            tvBrandWords.containsMatchIn(t.name) -> {
                out += TvCandidate(
                    t.id, name, Confidence.LOW,
                    tvConfidenceWords(Confidence.LOW), t.rssi
                )
            }
        }
    }
    return out.sortedWith(
        compareByDescending<TvCandidate> { it.confidence.ordinal }
            .thenByDescending { it.signalDbm ?: Int.MIN_VALUE }
    )
}

/**
 * True when this candidate may connect itself: exactly one candidate
 * and its confidence is better than a weak hint. Anything else means
 * the user picks — one tap on the row.
 */
fun mayAutoConnect(candidates: List<TvCandidate>): Boolean =
    candidates.size == 1 && candidates[0].confidence != Confidence.LOW
