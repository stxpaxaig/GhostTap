package com.ghosttap.core

/**
 * What the intelligence system is investigating. Built from a Visual Intel
 * finding, a BLE target, or something the user typed by hand.
 */
data class IntelTarget(
    val kind: String, // "VISUAL", "BLE", "MANUAL", "NFC", "NETWORK", "USB"
    val title: String,
    val text: String,
    val markings: List<String> = emptyList(),
    val barcodes: List<String> = emptyList()
)

/**
 * One step of an investigation plan.
 *
 * @param auto true when the planner judges the step safe to run on its own.
 *   Ghost Tap runs in full autonomous mode: every step runs without asking,
 *   at any risk level. Risk is still recorded on every step and every
 *   auto-run action is logged to the investigations timeline.
 * @param riskLevel copied from the registry entry at plan time ("READ",
 *   "CONFIGURE", "WRITE", "CONTROL"). The UI badges it; the engine enforces it.
 */
data class PlanStep(
    val capabilityId: String,
    val args: Map<String, String> = emptyMap(),
    val why: String,
    val auto: Boolean,
    val riskLevel: String = "READ"
)

/**
 * Rule-based investigation planner. Works fully offline — no network, no AI
 * service, no guessing. Reads the target's text/markings/barcodes, matches
 * simple patterns, and emits plan steps that reference ONLY registered
 * capability ids (validated; unknown ids are dropped, never crash).
 *
 * The UI labels this honestly: "On-device planner (rule-based)."
 */
object Planner {

    private val macRegex = Regex("(?i)\\b([0-9A-F]{2}[:-]){5}[0-9A-F]{2}\\b")
    private val ipv4Regex = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    private val urlRegex = Regex("(?i)\\bhttps?://[^\\s]+")
    private val hostRegex = Regex("(?i)\\b([a-z0-9-]+\\.)+(com|net|org|io|local|lan)\\b")
    private val nfcWords = Regex("(?i)\\b(nfc|rfid|tag|mifare|ntag|card|felica)\\b")
    private val bleWords = Regex("(?i)\\b(bluetooth|ble|beacon|peripheral)\\b")
    private val usbWords = Regex("(?i)\\b(esp32|uart|serial|cp210|ch340|ftdi|usb|ttl|flash|firmware)\\b")

    /**
     * @param failedIds capability ids that already failed on this investigation
     *   (from memory). Those steps are deprioritized — moved to the end with
     *   auto cleared and the reason noted — unless the user taps RUN manually.
     */
    fun planFor(
        t: IntelTarget,
        registry: CapabilityRegistry,
        failedIds: Set<String> = emptySet()
    ): List<PlanStep> {
        val normal = mutableListOf<PlanStep>()
        val demoted = mutableListOf<PlanStep>()
        val text = (t.title + " " + t.text + " " + t.markings.joinToString(" ") + " " + t.barcodes.joinToString(" "))
        // Dedupe by capability AND args: the same tool with different
        // details is a different step (e.g. two Termux commands), while
        // the same tool with the same details still appears once.
        val seen = mutableSetOf<Pair<String, Map<String, String>>>()

        fun add(step: PlanStep) {
            // Dedupe by capability id + args; keep the first (most specific) reason.
            if (!seen.add(step.capabilityId to step.args)) return
            // Risk comes from the registry — the single source of truth.
            val risk = registry.get(step.capabilityId)?.riskLevel ?: "READ"
            // Full autonomous mode: auto stays as the planner judged it, at
            // any risk level. Risk is recorded on the step and every auto-run
            // action is logged to the timeline.
            var s = step.copy(riskLevel = risk)
            if (s.capabilityId in failedIds) {
                s = s.copy(
                    why = s.why + " — tried before on this target and failed",
                    auto = false
                )
                demoted.add(s)
            } else {
                normal.add(s)
            }
        }

        // 0. Always start with context: what do we already know?
        add(
            PlanStep(
                "ble.list_targets",
                why = "See what Bluetooth targets Ghost Tap already knows about before doing anything new.",
                auto = true
            )
        )

        // 1. MAC address spotted -> the device may be advertising right now.
        val mac = macRegex.find(text)?.value
        if (mac != null) {
            add(
                PlanStep(
                    "ble.start_scan", mapOf("hint" to mac),
                    why = "Found a hardware address ($mac) in the text — scan to see if that device is nearby.",
                    auto = true
                )
            )
        } else if (bleWords.containsMatchIn(text)) {
            add(
                PlanStep(
                    "ble.start_scan",
                    why = "The text mentions Bluetooth — scan to see what's actually nearby.",
                    auto = true
                )
            )
        }

        // 2. IP address -> ping it, then offer a deeper look.
        val ip = ipv4Regex.find(text)?.value
        if (ip != null) {
            add(
                PlanStep(
                    "net.ping", mapOf("host" to ip),
                    why = "Found an IP address ($ip) — ping it to see if the host answers.",
                    auto = true
                )
            )
            add(
                PlanStep(
                    "net.port_scan", mapOf("host" to ip),
                    why = "If $ip answers, a port scan shows which services it exposes. Tap RUN when ready.",
                    auto = false
                )
            )
        }

        // 3. URL or hostname -> fetch it.
        val url = urlRegex.find(text)?.value
            ?: hostRegex.find(text)?.value?.let { "http://$it" }
        if (url != null) {
            add(
                PlanStep(
                    "net.http_fetch", mapOf("url" to url),
                    why = "Found a web address ($url) — fetch it to see what it serves.",
                    auto = true
                )
            )
        }

        // 3b. PIECE 2 (2026-11-18) — the five missing tools + see-and-tap,
        // as plain typed commands. These read the words and the host /
        // text inside them, and add the matching tool as a step the
        // command box runs itself — no plan shown, no tap-through.
        val lower = text.lowercase()
        val ipOrHost = ip ?: hostRegex.find(text)?.value
        if (lower.contains("traceroute") || lower.contains("trace route") ||
            lower.contains("hops to") || lower.contains("route to")
        ) {
            if (ipOrHost != null) {
                add(
                    PlanStep(
                        "net.traceroute", mapOf("host" to ipOrHost),
                        why = "Asked for the route to $ipOrHost — trace the hops there.",
                        auto = true
                    )
                )
            }
        }
        if (lower.contains("dns") || lower.contains("resolve ") ||
            lower.contains("look up ") || lower.contains("lookup")
        ) {
            if (ipOrHost != null) {
                add(
                    PlanStep(
                        "net.dns_lookup", mapOf("host" to ipOrHost),
                        why = "Asked to look up $ipOrHost — resolve it.",
                        auto = true
                    )
                )
            }
        }
        if (lower.contains("speed test") || lower.contains("internet speed") ||
            lower.contains("how fast is") || lower.contains("check my speed")
        ) {
            add(
                PlanStep(
                    "net.speed_test",
                    why = "Asked for a speed check — measure download and upload once.",
                    auto = true
                )
            )
        }
        if (lower.contains("wifi analyzer") || lower.contains("wi-fi analyzer") ||
            lower.contains("analyze wifi") || lower.contains("wifi around") ||
            lower.contains("networks around")
        ) {
            add(
                PlanStep(
                    "net.wifi_analyzer",
                    why = "Asked what's around on Wi-Fi — list the networks with channel, band and signal.",
                    auto = true
                )
            )
        }
        if (lower.contains("qr code") || lower.contains("qr for") || lower.contains("make a qr")) {
            val qrText = text
                .replace(Regex("(?i)make a qr code for|make a qr code|generate a qr code|qr code for|qr for|qr code|make a qr"), "")
                .trim()
                .trim('"', '\'')
            add(
                PlanStep(
                    "net.qr_generate", mapOf("text" to qrText),
                    why = "Asked for a QR code — make one from what was typed.",
                    auto = true
                )
            )
        }
        // "Tap the blue button" — but never when it's an NFC tap.
        val isNfcTap = nfcWords.containsMatchIn(text)
        if (!isNfcTap && (lower.startsWith("tap ") || lower.startsWith("tap the ") ||
                lower.startsWith("click ") || lower.startsWith("click the ") ||
                lower.startsWith("press ") || lower.startsWith("press the "))
        ) {
            val goal = text
                .replace(Regex("(?i)^(tap the|tap|click the|click|press the|press)\\s+"), "")
                .trim()
            if (goal.isNotEmpty()) {
                add(
                    PlanStep(
                        "visual.see_and_tap", mapOf("goal" to goal),
                        why = "Asked to tap \"$goal\" — look at the screen and tap it.",
                        auto = true
                    )
                )
            }
        }

        // 4. Wi-Fi QR code -> joining is manual, then network tools apply.
        if (t.barcodes.any { it.startsWith("WIFI:") } || text.contains("WIFI:")) {
            add(
                PlanStep(
                    "sys.manual_step",
                    mapOf("instruction" to "Join the Wi-Fi network from the QR code (tap it in your Wi-Fi settings), then come back — NETWORK TOOLS can probe it."),
                    why = "A Wi-Fi code was scanned. Joining the network is a phone setting Ghost Tap can't change itself.",
                    auto = false
                )
            )
        }

        // 5. Serial/ESP32 words -> check the USB bench.
        if (usbWords.containsMatchIn(text) || t.kind == "USB") {
            add(
                PlanStep(
                    "usb.refresh",
                    why = "The target involves serial/ESP32 hardware — check whether a USB adapter is plugged in.",
                    auto = true
                )
            )
            add(
                PlanStep(
                    "usb.status",
                    why = "Read the USB serial state before touching anything.",
                    auto = true
                )
            )
            add(
                PlanStep(
                    "usb.connect",
                    why = "If an adapter shows up, connect it to open the serial link. Tap RUN when ready.",
                    auto = false
                )
            )
        }

        // 5b. ESP32 / VirtualESP32: connect the investigation to the
        // already-registered practice-board operations. The installed-app
        // check is read-only and safe to run automatically. Opening the
        // cross-app link and querying the guest require an explicit tap;
        // starting firmware, loading software, UART writes and GPIO changes
        // are intentionally left to their existing dedicated controls.
        val isEsp32Target = Regex(
            "(?i)\\b(esp[- ]?32|esp32[- ]?(?:solo|dev|wroom|s[0-9])|xtensa)\\b"
        ).containsMatchIn(text) || t.kind.equals("ESP32", ignoreCase = true)
        if (isEsp32Target) {
            add(
                PlanStep(
                    "vesp32.check-installed",
                    why = "This target is identified as ESP32-class hardware — check whether the separate VirtualESP32 companion app is installed.",
                    auto = true
                )
            )
            add(
                PlanStep(
                    "vesp32.connect-practice-board",
                    why = "Offer the existing Ghost Tap ↔ VirtualESP32 IPC link for this ESP32 investigation. This opens the companion-app connection, so it stays user-approved.",
                    auto = false
                )
            )
            add(
                PlanStep(
                    "vesp32.check-practice-status",
                    why = "After connecting, query the running guest for its real status, CPU, backend and firmware details.",
                    auto = false
                )
            )
            add(
                PlanStep(
                    "vesp32.check-light-pin",
                    why = "After the guest is connected, read its existing GPIO2 state as a non-writing diagnostic.",
                    auto = false
                )
            )
        }

        // 6. NFC words (or an NFC-kind target) -> NFC needs a physical tap.
        if (nfcWords.containsMatchIn(text) || t.kind == "NFC") {
            add(
                PlanStep(
                    "nfc.open_tools",
                    why = "This looks NFC-related. NFC needs a physical tap — open NFC TOOLS and tap the tag.",
                    auto = false
                )
            )
            add(
                PlanStep(
                    "nfc.read_summary",
                    why = "Check what the last tapped tag reported, if any.",
                    auto = true
                )
            )
            add(
                PlanStep(
                    "nfc.emulation_status",
                    why = "Check whether Ghost Tap is currently pretending to be a tag.",
                    auto = true
                )
            )
        }

        // 6b. Network-kind target without an IP yet -> point at the scanner.
        if (t.kind == "NETWORK" && ip == null) {
            add(
                PlanStep(
                    "sys.manual_step",
                    mapOf(
                        "instruction" to "No address on this target yet. Run SCAN MY NETWORK on the NETWORK TOOLS screen, " +
                            "note the device's IP, then start a new investigation with that IP in the description."
                    ),
                    why = "This is a network target with no address — the network scan finds it first.",
                    auto = false
                )
            )
        }

        // 7. Came from a photo -> offer a closer look.
        if (t.kind == "VISUAL") {
            add(
                PlanStep(
                    "visual.capture_analyze",
                    why = "This target came from a photo — capture a closer shot for a sharper read. Tap RUN when ready.",
                    auto = false
                )
            )
        }

        // 8. Markings worth remembering -> searchable later.
        if (t.markings.isNotEmpty() && mac == null) {
            add(
                PlanStep(
                    "sys.manual_step",
                    mapOf(
                        "instruction" to "Markings on file: ${t.markings.take(5).joinToString(", ")}. " +
                            "Verify them by eye against the real object, then use KNOWLEDGE SEARCH to check past investigations."
                    ),
                    why = "Chip markings were pulled from the photo. They're candidates until you verify them visually.",
                    auto = false
                )
            )
        }

        // 8b. Termux (v3 piece 3): jobs the Termux tools cover, when
        // Termux is ready and the last real probe says the tool is
        // there. TermuxPlanSelection does the matching AND the gating —
        // when the gate is closed (setting off, Termux missing, no
        // probe answer) it returns nothing, so the plan is unchanged.
        // Fallbacks for a failed built-in tool arrive through failedIds.
        TermuxPlanSelection.stepsFor(t, failedIds).forEach { add(it) }

        // 8c. A real investigation target can be handed to the existing
        // VNIC closed-loop engine. Keep this user-triggered: VNIC can
        // research, plan and attempt bring-up, so it must never be silently
        // auto-run as part of the initial passive probes. The home command
        // box uses kind=MANUAL and deliberately does not receive this step.
        if (t.kind != "MANUAL") {
            add(
                PlanStep(
                    "vnic.ask-about-device",
                    why = "Hand this identified target to Ghost Tap's unified brain for observation, research and a reviewed bring-up attempt.",
                    auto = false
                )
            )
        }

        // 9. Always end with a human decision point.
        add(
            PlanStep(
                "sys.manual_step",
                mapOf("instruction" to "Review the timeline below, then pick the next move — or save this as a reusable procedure."),
                why = "The automatic probes are done. You decide what happens next.",
                auto = false
            )
        )

        // Validate against the registry: drop anything unknown, never crash.
        // Demoted (previously failed) steps go last.
        val all = normal + demoted
        val bad = all.map { it.capabilityId }.filter { registry.get(it) == null }.toSet()
        return all.filter { it.capabilityId !in bad }
    }
}
