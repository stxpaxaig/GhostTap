package com.ghosttap.core.ops

import com.ghosttap.core.OpResult

/**
 * Termux as one more tool (additive).
 *
 * Mirrors OperationAreaVnic.kt: every operation wraps a function that
 * already exists on [com.ghosttap.TermuxViewModel] — nothing is
 * re-implemented here. Registering these operations also publishes
 * them into the CapabilityRegistry (via
 * OperationRegistry.publishToCapabilityRegistry), so the planner, the
 * loop and VNIC see Termux as one more tool option with Ghost Tap as
 * orchestrator. Termux never controls Ghost Tap.
 *
 * Plain-words rule: every title, description, prompt and outcome here
 * is in plain words. Operation ids are machine names, never shown.
 * verified = false everywhere until a real run on a real phone proves it.
 */
fun registerAreaTermuxOperations() {

    // ----- Open the Termux screen -----
    // UNIFIED BUILD (2026-11-18) — there is no Termux screen any
    // more: Termux is core plumbing, not a page. This operation stays
    // in the registry so plans can still use Termux tools, but it no
    // longer opens anything — it checks Termux is there and says so
    // in plain words. The one-paste setup is reachable from the
    // command box's status line when Termux isn't ready.
    OperationRegistry.register(
        Operation(
            id = "screen.termux",
            title = "Use Termux tools",
            description = "Termux is part of Ghost Tap now — it runs in the background whenever Ghost Tap needs it. There is no separate Termux screen any more.",
            area = OperationArea.TERMUX,
            execute = { context ->
                val termux = context.termux
                if (termux != null) {
                    val check = termux.checkInstalledNow()
                    if (check.ok) termux.discoverTermux()
                    OpResult(true, "Termux is part of Ghost Tap now — it runs in the background whenever it's needed.", "termux")
                } else {
                    OpResult(false, "Termux tools aren't available right now.")
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ----- Check Termux is there -----
    OperationRegistry.register(
        Operation(
            id = "termux.check-installed",
            title = "Check Termux is on this phone",
            description = "Checks whether Termux is installed, whether Ghost Tap is allowed to run things in it, and whether the helper app is there too.",
            area = OperationArea.TERMUX,
            execute = { context ->
                val termux = context.termux
                    ?: return@Operation OpResult(false, "Termux tools aren't available right now.")
                termux.checkInstalledNow()
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ----- Check the link is up -----
    OperationRegistry.register(
        Operation(
            id = "termux.check-bridge-health",
            title = "Check the link to Termux",
            description = "Checks whether Ghost Tap's own link — the one Termux scripts send answers back on — is up and answering.",
            area = OperationArea.TERMUX,
            preparation = listOf(
                PreparationStep("Checking Termux is there") { context ->
                    val termux = context.termux
                        ?: return@PreparationStep PreparationOutcome.Failed("Termux tools aren't available right now.")
                    val check = termux.checkInstalledNow()
                    if (check.ok) PreparationOutcome.Ready
                    else PreparationOutcome.Failed(check.summary)
                }
            ),
            execute = { context ->
                val termux = context.termux
                    ?: return@Operation OpResult(false, "Termux tools aren't available right now.")
                termux.bridgeHealthCheck()
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ----- Run a helper script (built-in or one of the user's own) -----
    OperationRegistry.register(
        Operation(
            id = "termux.run-approved-script",
            title = "Run a Termux script",
            description = "Runs one of the helper scripts in Termux and brings the answer back — one that came with Ghost Tap, or one you added in Termux yourself.",
            area = OperationArea.TERMUX,
            preparation = listOf(
                PreparationStep("Picking a script to run") { context ->
                    val termux = context.termux
                        ?: return@PreparationStep PreparationOutcome.Failed("Termux tools aren't available right now.")
                    val scripts = termux.userScripts()
                    if (scripts.isEmpty()) {
                        return@PreparationStep PreparationOutcome.Failed("No scripts are set up yet.")
                    }
                    val choice = context.pendingChoice
                    if (choice != null && scripts.any { it.title == choice }) {
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    PreparationOutcome.NeedsUserChoice(
                        prompt = "Which script?",
                        choices = scripts.map { it.title }
                    )
                }
            ),
            execute = { context ->
                val termux = context.termux
                    ?: return@Operation OpResult(false, "Termux tools aren't available right now.")
                val title = context.pendingChoice
                context.pendingChoice = null
                // Named script arguments a plan step supplied (e.g. the
                // host for "Check the network") — carried on the context
                // by Operation.asCapability from the step's "arg.*" args.
                val scriptArgs = context.pendingArgs
                context.pendingArgs = emptyMap()
                val script = title?.let { t -> termux.userScripts().firstOrNull { it.title == t } }
                    ?: return@Operation OpResult(false, "Couldn't find that script any more.")
                termux.runScriptAndWait(script.id, scriptArgs)
            },
            riskLevel = "WRITE",
            verified = false
        )
    )

    // ----- Run a typed command -----
    OperationRegistry.register(
        Operation(
            id = "termux.run-command",
            title = "Run a command in Termux",
            description = "Runs a command you typed — or one the plan put together — in Termux, exactly as written, and brings the answer back. Pipes and redirects work.",
            area = OperationArea.TERMUX,
            preparation = listOf(
                PreparationStep("Getting the command") { context ->
                    val termux = context.termux
                        ?: return@PreparationStep PreparationOutcome.Failed("Termux tools aren't available right now.")
                    val typed = context.pendingChoice
                    if (!typed.isNullOrBlank()) {
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    val recent = termux.recentFreeCommands()
                    if (recent.isNotEmpty()) {
                        PreparationOutcome.NeedsUserChoice(
                            prompt = "What should Termux run? Pick one you ran before — or type a new one in the box at the top.",
                            choices = recent
                        )
                    } else {
                        // UNIFIED BUILD (2026-11-18) — there is no
                        // Termux screen to open any more: the command
                        // is typed in the box at the top of the screen.
                        PreparationOutcome.Failed(
                            "No command yet — type it in the box at the top of the screen, and Ghost Tap runs it from there."
                        )
                    }
                }
            ),
            execute = { context ->
                val termux = context.termux
                    ?: return@Operation OpResult(false, "Termux tools aren't available right now.")
                val command = context.pendingChoice
                context.pendingChoice = null
                if (command.isNullOrBlank()) {
                    return@Operation OpResult(false, "No command was given — type one in the box at the top of the screen.")
                }
                termux.runFreeCommandAndWait(command)
            },
            riskLevel = "WRITE",
            verified = false
        )
    )

    // ----- Check what scripts the user added -----
    OperationRegistry.register(
        Operation(
            id = "termux.refresh-library",
            title = "Check what scripts are in Termux",
            description = "Asks Termux what scripts you put there yourself and shows them under Your scripts.",
            area = OperationArea.TERMUX,
            preparation = listOf(
                PreparationStep("Checking Termux is there") { context ->
                    val termux = context.termux
                        ?: return@PreparationStep PreparationOutcome.Failed("Termux tools aren't available right now.")
                    val check = termux.checkInstalledNow()
                    if (check.ok) PreparationOutcome.Ready
                    else PreparationOutcome.Failed(check.summary)
                }
            ),
            execute = { context ->
                val termux = context.termux
                    ?: return@Operation OpResult(false, "Termux tools aren't available right now.")
                termux.refreshLibrary()
                OpResult(true, "Checking what scripts are in Termux — the list fills in when Termux answers.")
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ----- List the user's own scripts -----
    OperationRegistry.register(
        Operation(
            id = "termux.list-library",
            title = "List your Termux scripts",
            description = "Says which scripts you added in Termux yourself, from the last check.",
            area = OperationArea.TERMUX,
            execute = { context ->
                val termux = context.termux
                    ?: return@Operation OpResult(false, "Termux tools aren't available right now.")
                val found = termux.libraryScripts
                if (found.isEmpty()) {
                    OpResult(true, "No scripts of your own in Termux yet — add one in Termux and run this check again.")
                } else {
                    OpResult(true, "Your scripts in Termux: ${found.joinToString(", ") { it.title }}.")
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ----- List what Termux can do -----
    OperationRegistry.register(
        Operation(
            id = "termux.list-capabilities",
            title = "List what Termux can do",
            description = "Says what Termux can do on this phone, from the last real check — Python, network checks, file tools, and the rest.",
            area = OperationArea.TERMUX,
            execute = { context ->
                val termux = context.termux
                    ?: return@Operation OpResult(false, "Termux tools aren't available right now.")
                OpResult(true, termux.capabilitySummary(), "capabilities=${termux.capabilities.size}")
            },
            riskLevel = "READ",
            verified = false
        )
    )
    
    // ----- Aircrack-ng local readiness (read-only preflight) -----
    // This does not install tools, enable monitor mode, capture traffic,
    // inject frames, or attempt access to any network. It reports only
    // what is already present in the user's Termux environment and what
    // the current Wi-Fi driver advertises.
    OperationRegistry.register(
        Operation(
            id = "aircrack.preflight",
            title = "Check Aircrack-ng readiness",
            description = "Checks whether Aircrack-ng tools are already installed in Termux and whether the Wi-Fi driver reports monitor-mode support. This is a read-only local check; it does not capture traffic or change network settings.",
            area = OperationArea.TERMUX,
            prerequisites = listOf(
                "Termux is installed and Ghost Tap is allowed to run commands in it"
            ),
            preparation = listOf(
                PreparationStep("Checking the Termux connection") { context ->
                    val termux = context.termux
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Termux tools aren't available right now."
                        )
                    val check = termux.checkInstalledNow()
                    if (check.ok) PreparationOutcome.Ready
                    else PreparationOutcome.Failed(check.summary)
                }
            ),
            execute = { context ->
                val termux = context.termux
                    ?: return@Operation OpResult(false, "Termux tools aren't available right now.")
                val command = """
                    for tool in aircrack-ng airodump-ng aireplay-ng airmon-ng iw; do
                      printf '%s=' "$tool"
                      command -v "$tool" 2>/dev/null || printf 'MISSING'
                      printf '\\n'
                    done
                    printf 'uid='
                    id -u 2>/dev/null || printf 'UNKNOWN'
                    printf '\\n'
                    if command -v iw >/dev/null 2>&1; then
                      iw list 2>/dev/null | awk '
                        /Supported interface modes:/ { in_modes=1; next }
                        in_modes && /^[[:space:]]*\\*/ {
                          if ($0 ~ /monitor/) found=1
                        }
                        in_modes && /Band [0-9]+:/ { in_modes=0 }
                        END { print "monitor_mode=" (found ? "YES" : "NO") }
                      '
                    else
                      printf 'monitor_mode=UNKNOWN (iw missing)\\n'
                    fi
                """.trimIndent()
                val probe = termux.runFreeCommandAndWait(command, timeoutMs = 20000)
                if (!probe.ok) {
                    OpResult(false, "The local readiness check did not complete: ${probe.summary}", probe.data)
                } else {
                    OpResult(
                        true,
                        "Local Aircrack-ng readiness check completed. No packets were captured or transmitted, and no settings were changed.",
                        probe.data.ifBlank { probe.summary }
                    )
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )

}
