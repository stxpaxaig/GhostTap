package com.ghosttap.core.vnic

/**
 * Bring-up types for the closed VNIC loop — pure Kotlin, no Android imports.
 *
 * The owner's end-state definition: "connect" means bringing the target to a
 * CONTROLLED / CONTROLLABLE state and STAYING connected — Ghost Tap holds
 * the connection, the controls are mapped, and the owner drives the target
 * himself through Ghost Tap. VNIC does the research, the bring-up, the
 * handshake and the control mapping; it never takes autonomous control and
 * never connects-then-disconnects.
 *
 * A bring-up port is the seam between the pure core ([VnicCore]) and the
 * real transport: on Android it is [AndroidVnicBringUp] (real
 * BringUpPipeline) / [VnicEsp32Bridge] (real VirtualESP32 client); in the
 * JVM demo it is the TCP-emulator-backed port in VnicClosedLoopTest.
 */

/** Target kinds the VNIC can bring to a controllable state. */
enum class VnicBringUpKind { BLE, NFC, NETWORK, USB, ESP32 }

/** One target to bring up — built from the observation + research findings. */
data class VnicBringUpTarget(
    val kind: VnicBringUpKind,
    /** Subject id: BLE device id, NFC tag key, host IP, "usb", or ESP32 node id. */
    val id: String,
    val title: String,
    val detail: String = ""
)

/** The outcome of a bring-up attempt. `ready` is true ONLY when the target is controllable AND the connection is still held. */
data class VnicBringUpResult(
    val ready: Boolean,
    /** Nav route the control interface lives on ("" when not ready). */
    val route: String,
    /** Plain-English lines describing what's controllable (empty when not ready). */
    val controlLines: List<String>,
    val log: List<String>,
    /** True only when the link is still open RIGHT NOW, not just during setup. */
    val connectionHeld: Boolean,
    /** Plain-English blocking reason when not ready. */
    val failReason: String?
)

/** The port the core drives a bring-up through. Implemented per transport. */
interface VnicBringUpPort {
    suspend fun bringUp(target: VnicBringUpTarget, note: String = ""): VnicBringUpResult
}

/** A procedure the VNIC developed for a goal: steps + whether its test passed. Pure data — the engine/bridge decides how to test and persist it. */
data class VnicDevelopedProcedure(
    val id: String,
    val steps: List<String>,
    val testPassed: Boolean,
    val notes: List<String>
)

/**
 * Determine the bring-up kind from observation text + research findings.
 * Check order is most-specific first: an ESP32 mention implies Wi-Fi/BLE
 * words too ("ESP32 Bluetooth Low Energy GATT"), so ESP32 must win over
 * BLE/NETWORK or the target would be mis-routed to a generic bring-up.
 * Returns null when no bring-up path can be determined — the cycle then
 * falls back to sequential plan execution and says so in its phases.
 */
/**
 * Build an ordered set of viable transport candidates from the actual
 * objective and research evidence. This is deliberately multi-route:
 * the core can try another evidenced transport when the first one fails.
 * Identifiers are extracted from evidence where possible; otherwise the
 * supplied subject is retained for transports whose discovery layer owns
 * target resolution.
 */
fun vnicDetectBringUpTargets(
    subject: String,
    text: String,
    findings: List<VnicFinding> = emptyList()
): List<VnicBringUpTarget> {
    val evidence = (text + " " + findings.joinToString(" ") { "${it.title} ${it.snippet} ${it.url}" })
    val hay = evidence.lowercase()
    val ipv4 = Regex("""(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])""")
        .findAll(evidence).map { it.value }
        .firstOrNull { ip -> ip.split(".").all { it.toIntOrNull() in 0..255 } }
    val mac = Regex("""(?i)(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}""").find(evidence)?.value
    val hostname = Regex("""(?i)\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+(?:local|lan|com|net|org|io|dev)\b""")
        .find(evidence)?.value
    val candidates = mutableListOf<VnicBringUpTarget>()
    fun add(kind: VnicBringUpKind, id: String, title: String) {
        if (candidates.none { it.kind == kind && it.id == id }) {
            candidates += VnicBringUpTarget(kind, id, title, evidence.take(300))
        }
    }

    // Prefer a user-requested VirtualESP32 explicitly. For a physical ESP32,
    // prefer discovered BLE/NETWORK/USB identifiers; retain the virtual
    // companion as a fallback only when the task identifies an ESP32 target.
    val objectiveHay = (subject + " " + text).lowercase()
    val esp32Mentioned = "esp32" in objectiveHay || "espressif" in objectiveHay
    val virtualEsp32Requested =
        Regex("""(?i)\bvirtual[\s_-]*esp32\b""").containsMatchIn(objectiveHay) ||
            ("esp32" in objectiveHay && ("practice board" in objectiveHay || "emulator" in objectiveHay))
    if (virtualEsp32Requested) add(VnicBringUpKind.ESP32, "virtualesp32", subject)
    // A transport keyword is not a device address. Only attempt direct
    // BLE/NETWORK bring-up after discovery or research supplies a real id;
    // otherwise the VNIC planner must run the matching discovery operation.
    if (mac != null && ("bluetooth" in hay || Regex("""\bble\b""").containsMatchIn(hay) || "gatt" in hay)) {
        add(VnicBringUpKind.BLE, mac, subject)
    }
    if (ipv4 != null || hostname != null) {
        val hostId = ipv4 ?: hostname!!
        add(VnicBringUpKind.NETWORK, hostId, subject.ifBlank { hostId })
    }
    // USB/NFC are promoted only after a live discovery operation reports
    // an attached board or a tag that is actually present.
    if ("usb board found" in hay || "usb device found" in hay) {
        add(VnicBringUpKind.USB, "usb", subject.ifBlank { "USB board" })
    }
    if ("nfc tag present" in hay) {
        add(VnicBringUpKind.NFC, subject.ifBlank { "nfc-target" }, subject.ifBlank { "NFC target" })
    }
    if (esp32Mentioned && !virtualEsp32Requested) {
        add(VnicBringUpKind.ESP32, "virtualesp32", subject)
    }
    return candidates
}

fun vnicDiscoveryMatchesTarget(subject: String, objective: String, evidence: String): Boolean {
    val generic = setOf(
        "find", "locate", "discover", "identify", "investigate", "connect", "scan", "control",
        "device", "devices", "controller", "target", "thing", "board", "nearby", "strongest",
        "bluetooth", "wireless", "wifi", "network", "usb", "serial", "uart", "nfc",
        "the", "and", "for", "with", "from", "onto", "into", "this", "that", "my", "near"
    )
    fun words(value: String): Set<String> =
        Regex("[a-z0-9]+").findAll(value.lowercase()).map { it.value }
            .filter { it.length > 2 && it !in generic }.toSet()
    val targetWords = words("$subject $objective")
    if (targetWords.isEmpty()) return true
    val evidenceWords = words(evidence)
    return targetWords.any { it in evidenceWords }
}

fun vnicDetectBringUpKind(text: String, findings: List<VnicFinding> = emptyList()): VnicBringUpKind? {
    val hay = (text + " " + findings.joinToString(" ") { "${it.title} ${it.snippet}" }).lowercase()
    return when {
        "esp32" in hay || "espressif" in hay -> VnicBringUpKind.ESP32
        "bluetooth" in hay || "ble" in hay || "gatt" in hay -> VnicBringUpKind.BLE
        "nfc" in hay || "tag" in hay -> VnicBringUpKind.NFC
        "usb" in hay || "serial" in hay || "uart" in hay || "ch340" in hay -> VnicBringUpKind.USB
        "wifi" in hay || "ssid" in hay || "network" in hay || "ip " in hay || "host" in hay -> VnicBringUpKind.NETWORK
        else -> null
    }
}
