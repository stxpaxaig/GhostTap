package com.ghosttap.core.ops

import com.ghosttap.core.OpResult
import kotlinx.coroutines.delay

/**
 * PIECE 2 (2026-11-18) — the five missing tools + screen-see-and-tap,
 * as operations the command box, the planner and the tool panels can
 * all run. Every op wraps an EXISTING ViewModel function (the tools
 * in NetworkToolsViewModel / VisualIntelViewModel) — nothing here
 * re-implements what the ViewModel already does.
 *
 * - net.traceroute / net.dns_lookup: host comes from the plan step's
 *   args ("host"), from a pending choice, or — when tapped as a card
 *   with nothing typed — from the first live host on the network.
 * - net.speed_test / net.wifi_analyzer: no input, just run.
 * - net.qr_generate: text comes from args ("text") or a pending choice.
 * - visual.see_and_tap: the goal comes from args ("goal") or a pending
 *   choice; the tap only happens when the brain pointed at a real spot.
 *
 * Legitimate lane only: these discover, look up and connect to things
 * available to the user. Nothing here bypasses a login, guesses a
 * password, or touches a device the user doesn't own.
 */

/** Waits, bounded, for [check] to turn true. Never waits forever. */
private suspend fun waitForPiece2(
    timeoutMs: Long,
    pollMs: Long = 500L,
    check: () -> Boolean
): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (check()) return true
        delay(pollMs)
    }
    return check()
}

/** Log lines a network op produced, from [before] onwards. */
private fun netLogSincePiece2(
    net: com.ghosttap.NetworkToolsViewModel,
    before: Int
): List<String> = net.log.drop(before)

/** Strips the "[HH:mm:ss] " prefix the log adds, for op summaries. */
private fun plainLine(line: String): String = line.substringAfter("] ").trim()

fun registerPiece2Operations() {

    // ---- Traceroute: the hops to a host, in plain words. ----
    OperationRegistry.register(
        Operation(
            id = "net.traceroute",
            title = "Check the route to a device",
            description = "Shows the hops between this phone and a device or website, in plain words — which stop answers and which doesn't.",
            area = OperationArea.NETWORK,
            inputs = listOf("host"),
            execute = { context ->
                val net = context.net
                    ?: return@Operation OpResult(false, "Network tools aren't available right now.")
                val host = context.pendingArgs["host"]?.takeIf { it.isNotBlank() }
                    ?: context.pendingChoice?.takeIf { it.isNotBlank() }
                    ?: net.liveHosts.firstOrNull()
                context.pendingChoice = null
                if (host == null) {
                    return@Operation OpResult(
                        false,
                        "Type a name or address first — in the box above, or say it in the command box."
                    )
                }
                val logBefore = net.log.size
                net.traceroute(host)
                waitForPiece2(75000L) { !net.isBusy }
                val lines = netLogSincePiece2(net, logBefore)
                val hops = lines.filter { "hop(s) to" in it }
                val failed = lines.firstOrNull {
                    "isn't ready" in it || "doesn't have" in it ||
                        "doesn't look like" in it || "nothing came back" in it
                }
                if (failed != null) {
                    OpResult(false, plainLine(failed))
                } else if (hops.isNotEmpty()) {
                    OpResult(true, plainLine(hops.last()), "net-traceroute:$host")
                } else {
                    OpResult(true, lines.lastOrNull()?.let { plainLine(it) } ?: "Route check done.", "net-traceroute:$host")
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- DNS Lookup: a name to its addresses, or back to its name. ----
    OperationRegistry.register(
        Operation(
            id = "net.dns_lookup",
            title = "Look up a name or address",
            description = "Turns a name into its addresses, or an address back into its name — and says plainly when nothing is on record.",
            area = OperationArea.NETWORK,
            inputs = listOf("host"),
            execute = { context ->
                val net = context.net
                    ?: return@Operation OpResult(false, "Network tools aren't available right now.")
                val host = context.pendingArgs["host"]?.takeIf { it.isNotBlank() }
                    ?: context.pendingChoice?.takeIf { it.isNotBlank() }
                    ?: net.liveHosts.firstOrNull()
                context.pendingChoice = null
                if (host == null) {
                    return@Operation OpResult(
                        false,
                        "Type a name or address first — in the box above, or say it in the command box."
                    )
                }
                val logBefore = net.log.size
                net.dnsLookup(host)
                waitForPiece2(30000L) { !net.isBusy }
                val lines = netLogSincePiece2(net, logBefore)
                val found = lines.filter {
                    "DNS check —" in it && (" is " in it || "belongs to" in it)
                }
                val noName = lines.firstOrNull { "has no name on record" in it }
                val failed = lines.firstOrNull { "couldn't find" in it }
                if (found.isNotEmpty()) {
                    OpResult(true, found.joinToString(" ") { plainLine(it) }, "net-dns:$host")
                } else if (noName != null) {
                    OpResult(true, plainLine(noName), "net-dns:$host")
                } else if (failed != null) {
                    OpResult(false, plainLine(failed))
                } else {
                    OpResult(false, lines.lastOrNull()?.let { plainLine(it) } ?: "Nothing found for \"$host\".")
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Speed Test: one download and one upload, once per tap. ----
    OperationRegistry.register(
        Operation(
            id = "net.speed_test",
            title = "Check the internet speed",
            description = "Measures download and upload once, against Cloudflare's test server — and says that's what it measured.",
            area = OperationArea.NETWORK,
            execute = { context ->
                val net = context.net
                    ?: return@Operation OpResult(false, "Network tools aren't available right now.")
                val logBefore = net.log.size
                net.speedTest()
                waitForPiece2(90000L) { !net.isBusy }
                val lines = netLogSincePiece2(net, logBefore)
                val speeds = lines.filter { "download:" in it || "upload:" in it }
                val failed = lines.firstOrNull { "couldn't reach" in it }
                if (failed != null) {
                    OpResult(false, plainLine(failed))
                } else if (speeds.isNotEmpty()) {
                    OpResult(true, speeds.joinToString(" ") { plainLine(it) }, "net-speed")
                } else {
                    OpResult(false, lines.lastOrNull()?.let { plainLine(it) } ?: "The speed check didn't finish.")
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Wi-Fi Analyzer: channel, band and signal for each network. ----
    OperationRegistry.register(
        Operation(
            id = "net.wifi_analyzer",
            title = "Check the Wi-Fi around you",
            description = "Lists the Wi-Fi networks nearby with their channel, band and signal in plain words — Strong, OK or Weak, never a distance.",
            area = OperationArea.NETWORK,
            execute = { context ->
                val net = context.net
                    ?: return@Operation OpResult(false, "Network tools aren't available right now.")
                val logBefore = net.log.size
                net.analyzeWifi()
                // The scan is async: its results arrive through the
                // receiver, so wait for the per-network lines, not
                // just for the busy flag.
                waitForPiece2(30000L) {
                    net.log.drop(logBefore).any {
                        ("Wi-Fi check —" in it && "channel" in it) ||
                            "nothing found" in it
                    }
                }
                val lines = netLogSincePiece2(net, logBefore)
                    .filter { "Wi-Fi check —" in it && "channel" in it }
                if (lines.isNotEmpty()) {
                    val count = lines.size
                    OpResult(
                        true,
                        "Found $count Wi-Fi network(s). ${lines.firstOrNull()?.let { plainLine(it) } ?: ""}",
                        "net-wifi-analyzer"
                    )
                } else {
                    OpResult(false, "No Wi-Fi networks found. If location is off, the phone can't list networks.")
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- QR Generate: a real QR code from typed text. ----
    OperationRegistry.register(
        Operation(
            id = "net.qr_generate",
            title = "Make a QR code",
            description = "Makes a QR code from what you typed — a link, some words, or Wi-Fi details — and shows it on the screen.",
            area = OperationArea.NETWORK,
            inputs = listOf("text"),
            execute = { context ->
                val net = context.net
                    ?: return@Operation OpResult(false, "Network tools aren't available right now.")
                val text = context.pendingArgs["text"]?.takeIf { it.isNotBlank() }
                    ?: context.pendingChoice?.takeIf { it.isNotBlank() }
                context.pendingChoice = null
                if (text == null) {
                    return@Operation OpResult(
                        false,
                        "Type something first — a link, some words, or Wi-Fi details."
                    )
                }
                net.generateQr(text)
                val error = net.qrError
                if (error != null) {
                    OpResult(false, error)
                } else if (net.qrMatrix != null) {
                    OpResult(true, "QR code made — point another phone's camera at it.", "net-qr")
                } else {
                    OpResult(false, "Couldn't make that QR code.")
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Screen-see-and-tap: look at the screen, tap what was named. ----
    OperationRegistry.register(
        Operation(
            id = "visual.see_and_tap",
            title = "Look at the screen and tap it",
            description = "You name something on the screen; Ghost Tap looks at the screen, the brain says where it is, and Ghost Tap taps it. No brain means no tap, said plainly.",
            area = OperationArea.VISUAL,
            inputs = listOf("goal"),
            execute = { context ->
                val visual = context.visual
                    ?: return@Operation OpResult(false, "Visual Intel isn't available right now.")
                val goal = context.pendingArgs["goal"]?.takeIf { it.isNotBlank() }
                    ?: context.pendingChoice?.takeIf { it.isNotBlank() }
                context.pendingChoice = null
                if (goal == null) {
                    return@Operation OpResult(
                        false,
                        "Say what to tap first — for example, \"the blue button\"."
                    )
                }
                visual.seeAndTap(goal)
                waitForPiece2(90000L) { !visual.seeAndTapBusy }
                val status = visual.seeAndTapStatus
                if (status == "Tapped it.") {
                    OpResult(true, "Tapped it.", "visual-see-and-tap")
                } else {
                    OpResult(false, status ?: "The tap didn't go through — try again.")
                }
            },
            riskLevel = "WRITE",
            verified = false
        )
    )
}
