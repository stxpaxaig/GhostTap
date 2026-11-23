package com.ghosttap.core

import java.net.URI

/**
 * Piece 3 (v3) — the intelligence picks Termux tools.
 *
 * This is the selection wiring only. It does NOT run anything, does
 * NOT replace the planner, and does NOT touch the loop. It answers two
 * questions the planner and the reasoners ask before a Termux step is
 * ever offered:
 *
 * 1. "Can this Termux job run right now?" — the gate ([TermuxPlanGate]).
 *    A Termux step is offered ONLY when all three hold:
 *    (a) the user left "Let Ghost Tap offer Termux steps when it makes
 *        a plan" on ([TermuxViewModel.useTermuxInPlans]),
 *    (b) Termux is installed AND Ghost Tap is allowed to run things
 *        in it,
 *    (c) the last real probe said the capability is there (state YES)
 *        and the user did not switch that capability off.
 *    A capability the probe says is NOT there (or never checked) is
 *    never offered as if it works.
 * 2. "Which Termux tool does this job need?" — the capability→op map
 *    ([TermuxJob]) plus [stepsFor], which turns a target into concrete
 *    plan steps only when every input the step needs can be read from
 *    the target itself.
 *
 * How the gate gets its live values: the app sets [gateProvider] once
 * at startup (GhostTapApp, next to the TermuxEngine registration) to a
 * function that reads the TermuxViewModel's current state. Until it is
 * set — and in unit tests, where it is never set — every answer is
 * "no": nothing Termux is ever offered. The planner, the loop and the
 * reasoners all read through here, so there is one gate, not three.
 *
 * Commands are composed from FIXED templates plus identifiers pulled
 * out of the target text by regex (an IP, a host name, a URL). Raw
 * target text is NEVER pasted into a command: the host must match the
 * same letters-digits-dots-dashes pattern the Termux screen's host box
 * already enforces, and an IP's octets must each be 0–255. A job whose
 * inputs cannot be pinned down this way gets no step — the plan simply
 * does not offer it.
 *
 * Pure Kotlin, no Android framework — unit-testable on the JVM.
 */
object TermuxPlanSelection {

    // ----- Plan-step arg keys (the contract with Operation.asCapability) -----
    //
    // A plan step carries its details in its args map. The operations
    // layer reads a pending choice from OperationContext, so
    // asCapability seeds that channel from these keys before running:
    // "command" -> the composed command for termux.run-command,
    // "scriptTitle" -> the script title for termux.run-approved-script,
    // "arg.<name>" -> one named argument for that script (e.g.
    // "arg.host" for the check-network script's host box).

    /** Args key: the composed command a termux.run-command step runs. */
    const val ARG_COMMAND = "command"

    /** Args key: the script title a termux.run-approved-script step runs. */
    const val ARG_SCRIPT_TITLE = "scriptTitle"

    /** Args prefix: "arg.<name>" carries one named script argument. */
    const val ARG_PREFIX = "arg."

    // ----- The gate -----

    /**
     * A snapshot of "can Termux do this job right now". Built by the
     * app from the TermuxViewModel; [availableCapabilities] holds the
     * ids of the probed capabilities that are YES and switched on.
     * Capability ids are the 15 from TermuxRegistry.defaultCapabilities
     * ("python", "network", "dns", "http", "discovery", "files", "json",
     * "data", "dev", "logs", "build", "bash", "automation", "termux-api").
     */
    data class TermuxPlanGate(
        val useTermuxInPlans: Boolean,
        val termuxInstalled: Boolean,
        val hasRunPermission: Boolean,
        val availableCapabilities: Set<String> = emptySet()
    ) {
        /** Termux itself is usable for a job at all (no capability needed). */
        fun termuxReady(): Boolean =
            termuxInstalled && hasRunPermission

        /** One probed capability is usable right now. */
        fun canOffer(capabilityId: String): Boolean =
            termuxReady() && capabilityId in availableCapabilities
    }

    /**
     * The live gate source, set once by the app at startup. Null until
     * set — and deliberately never set in unit tests — which means
     * "Termux is not available", never "assume it is".
     */
    @Volatile
    var gateProvider: (() -> TermuxPlanGate?)? = null

    /** The gate right now, or null when the app has not wired one in. */
    fun currentGate(): TermuxPlanGate? = try {
        gateProvider?.invoke()
    } catch (_: Exception) {
        null
    }

    /** True when this Termux capability may be offered right now. */
    fun canOffer(capabilityId: String): Boolean =
        currentGate()?.canOffer(capabilityId) ?: false

    /** True when Termux itself may be offered right now (no capability needed). */
    fun termuxReady(): Boolean = currentGate()?.termuxReady() ?: false

    /** True when this capability id belongs to a Termux operation. */
    fun isTermuxStep(capabilityId: String): Boolean =
        capabilityId == "screen.termux" || capabilityId.startsWith("termux.")

    // ----- The capability -> op map -----

    /**
     * One row of the map: a probed Termux capability, the operation
     * that runs it, and how the step is built.
     *
     * [commandTemplate] holds "{host}", "{ip}" or "{url}" where the
     * identifier goes, or is null when the job runs a script instead.
     * [scriptTitle] is the title the run-approved-script preparation
     * step matches on, or null when the job runs a command instead.
     * [needs] names, in plain words, what the target must supply for
     * the step to be concrete — a job whose input is not in the target
     * gets no step.
     */
    data class TermuxJob(
        val capabilityId: String,
        val opId: String,
        val commandTemplate: String? = null,
        val scriptTitle: String? = null,
        val needs: String
    )

    /**
     * The full map, one row per probed capability. Rows with a command
     * template run through termux.run-command; rows with a script title
     * run through termux.run-approved-script. Rows whose [needs] cannot
     * be read from a target (a file path, a repo, a log to search) have
     * no template yet — the map still names the op, so a future step
     * builder (or the remote reasoner, offering it as a manual step)
     * knows where the job lives.
     */
    val jobs: List<TermuxJob> = listOf(
        TermuxJob(
            "python", "termux.run-approved-script",
            scriptTitle = "Check what Python is there",
            needs = "the target mentions Python"
        ),
        TermuxJob(
            "network", "termux.run-command",
            commandTemplate = "ping -c 3 {host}",
            needs = "an IP or host name, and the built-in ping already failed on this target"
        ),
        TermuxJob(
            "dns", "termux.run-command",
            commandTemplate = "nslookup {host}",
            needs = "a host name"
        ),
        TermuxJob(
            "http", "termux.run-command",
            commandTemplate = "curl -sS -m 15 -o /dev/null -w 'code=%{http_code} size=%{size_download}' {url}",
            needs = "a web address, and the built-in fetch already failed on this target"
        ),
        TermuxJob(
            "discovery", "termux.run-command",
            commandTemplate = "nmap {ip}",
            needs = "an IP address, and the built-in port scan already failed on this target"
        ),
        TermuxJob(
            "wireless-audit", "termux.run-command",
            commandTemplate = "aircrack-ng --help",
            needs = "the operator explicitly requests Aircrack-ng availability or wireless adapter diagnostics; this read-only invocation confirms the local tool interface and does not capture traffic or recover credentials"
        ),
        TermuxJob(
            "files", "termux.run-command",
            needs = "a file path and words to look for — not in a target on its own"
        ),
        TermuxJob(
            "json", "termux.run-command",
            needs = "a data file to read — not in a target on its own"
        ),
        TermuxJob(
            "data", "termux.run-command",
            needs = "a data file or database to work on — not in a target on its own"
        ),
        TermuxJob(
            "dev", "termux.run-command",
            needs = "a code folder to work in — not in a target on its own"
        ),
        TermuxJob(
            "logs", "termux.run-command",
            needs = "a log file and words to look for — not in a target on its own"
        ),
        TermuxJob(
            "build", "termux.run-command",
            needs = "code to build and where it lives — not in a target on its own"
        ),
        TermuxJob(
            "bash", "termux.run-approved-script",
            scriptTitle = "Say hello",
            needs = "the target is about Termux itself — a hello run proves the link works"
        ),
        TermuxJob(
            "automation", "termux.run-command",
            needs = "a job to run on a schedule — the plan cannot schedule from a target on its own"
        ),
        TermuxJob(
            "termux-api", "termux.run-command",
            needs = "a phone detail to read — not in a target on its own"
        )
    )

    /** The map row for one capability, or null when it has no row. */
    fun jobFor(capabilityId: String): TermuxJob? =
        jobs.firstOrNull { it.capabilityId == capabilityId }

    // ----- Matching: target -> plan steps -----

    private val ipv4Regex = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    private val urlRegex = Regex("(?i)\\bhttps?://[^\\s]+")
    /** Parse URLs as data and quote them before composing a shell command. */
    private fun safeHttpUrl(raw: String): String? {
        val candidate = raw.trim().trimEnd('.', ',', ';', ')', ']', '}')
        if (candidate.length > 500) return null
        return try {
            val uri = URI(candidate)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host
            if (scheme !in setOf("http", "https") || host.isNullOrBlank() ||
                uri.rawUserInfo != null || (uri.port != -1 && uri.port !in 1..65535) ||
                !isSafeHost(host)
            ) null else candidate
        } catch (_: Exception) {
            null
        }
    }

    /** POSIX single-quote escaping; the URL remains one literal shell argument. */
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    private val hostRegex = Regex("(?i)\\b([a-z0-9-]+\\.)+(com|net|org|io|local|lan)\\b")
    private val pythonWords = Regex("(?i)\\bpython\\b")
    private val termuxWords = Regex("(?i)\\b(termux|linux terminal|shell script)\\b")

    /**
     * The host pattern the Termux screen's host box already enforces
     * (see TermuxRegistry.hostArg): letters, digits, dots, dashes,
     * colons, underscores, 1–200 chars. A host that does not match is
     * never put in a command.
     */
    private val hostPattern = Regex("^[A-Za-z0-9._:-]{1,200}$")

    /** True when this host name is safe to put in a composed command. */
    fun isSafeHost(host: String): Boolean = hostPattern.matches(host)

    /** True when this is a real IPv4 address (shape AND octet range). */
    fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { p ->
            p.isNotEmpty() && p.all { it.isDigit() } &&
                (p.toIntOrNull() ?: 256) in 0..255
        }
    }

    /**
     * Builds the concrete plan steps Termux can add for this target.
     * Called by [Planner.planFor]; the planner's own add() still
     * dedupes, takes risk from the registry, and demotes steps whose
     * id already failed on this investigation.
     *
     * What is offered, in order — each line only when its gate holds:
     * - the built-in ping failed and the probe says network checks are
     *   there: the same ping from Termux (retry with another tool);
     * - the built-in web fetch failed and the probe says web fetching
     *   is there: the same fetch from Termux;
     * - the built-in port scan failed and the probe says discovery is
     *   there: a Termux scan of that one host;
     * - a host name is on the target and the probe says address
     *   lookups are there: look it up (no built-in tool does this);
     * - a network-kind target with an IP and the probe says discovery
     *   is there: look for everything else answering on that network;
     * - the target mentions Python and the probe says Python is there:
     *   ask Termux what Python it has;
     * - the target is about Termux itself and Termux is ready: list
     *   what Termux can do on this phone.
     *
     * A step is never offered for a capability the gate refuses, and
     * a step is never built when its identifier is missing or unsafe.
     */
    fun stepsFor(target: IntelTarget, failedIds: Set<String> = emptySet()): List<PlanStep> {
        val out = mutableListOf<PlanStep>()
        val text = (target.text + " " + target.markings.joinToString(" ") +
            " " + target.barcodes.joinToString(" "))
        val ip = ipv4Regex.find(text)?.value?.takeIf { isValidIpv4(it) }
        val requestsWirelessAudit = Regex("(?i)\\b(aircrack|wireless audit|wireless adapter diagnostic|wifi adapter diagnostic)\\b").containsMatchIn(text)
        if (requestsWirelessAudit && canOffer("wireless-audit")) {
            out.add(
                PlanStep(
                    "termux.run-command",
                    mapOf(ARG_COMMAND to "aircrack-ng --help"),
                    why = "Aircrack-ng is installed; inspect its local command interface for wireless diagnostics.",
                    auto = false
                )
            )
        }
        val url = urlRegex.find(text)?.value?.let { safeHttpUrl(it) }
        val host = hostRegex.find(text)?.value
            ?: ip
        val safeHost = host?.takeIf { isSafeHost(it) }

        // WRITE steps below are manual (auto = false): Justin taps to
        // run them. Standing rule — autonomous investigation, not
        // autonomous control — and it matches the remote reasoner path,
        // which forces WRITE steps manual. Only the READ step (7) runs
        // on its own.
        // 1. Built-in ping failed -> the same check from Termux.
        // The command comes from the map row, not a second copy here.
        if ("net.ping" in failedIds && safeHost != null && canOffer("network")) {
            val command = jobFor("network")?.commandTemplate?.replace("{host}", safeHost)
            if (command != null) {
                out.add(
                    PlanStep(
                        "termux.run-command",
                        mapOf(ARG_COMMAND to command),
                        why = "The built-in ping didn't get an answer from $safeHost — " +
                            "try the same check from Termux instead.",
                        auto = false
                    )
                )
            }
        }

        // 2. Built-in web fetch failed -> the same fetch from Termux.
        if ("net.http_fetch" in failedIds && url != null && canOffer("http")) {
            val command = jobFor("http")?.commandTemplate?.replace("{url}", shellQuote(url))
            if (command != null) {
                out.add(
                    PlanStep(
                        "termux.run-command",
                        mapOf(ARG_COMMAND to command),
                        why = "The built-in fetch didn't load $url — " +
                            "try fetching it from Termux instead.",
                        auto = false
                    )
                )
            }
        }

        // 3. Built-in port scan failed -> a Termux scan of that one host.
        if ("net.port_scan" in failedIds && ip != null && canOffer("discovery")) {
            val command = jobFor("discovery")?.commandTemplate?.replace("{ip}", ip)
            if (command != null) {
                out.add(
                    PlanStep(
                        "termux.run-command",
                        mapOf(ARG_COMMAND to command),
                        why = "The built-in port scan didn't work on $ip — " +
                            "try Termux's network scanner on that host instead.",
                        auto = false
                    )
                )
            }
        }

        // 4. A host name is on the target -> look it up (no built-in tool does this).
        if (safeHost != null && canOffer("dns")) {
            val command = jobFor("dns")?.commandTemplate?.replace("{host}", safeHost)
            if (command != null) {
                out.add(
                    PlanStep(
                        "termux.run-command",
                        mapOf(ARG_COMMAND to command),
                        why = "Found a host name ($safeHost) — look up its address in Termux.",
                        auto = false
                    )
                )
            }
        }

        // 5. A network-kind target with an IP -> what else is answering on that network.
        if (target.kind == "NETWORK" && ip != null && canOffer("discovery")) {
            val network = ip.substringBeforeLast(".") + ".0/24"
            out.add(
                PlanStep(
                    "termux.run-command",
                    mapOf(ARG_COMMAND to "nmap -sn $network"),
                    why = "This is a network to look at — ask Termux's scanner " +
                        "what else is answering on $network.",
                    auto = false
                )
            )
        }

        // 6. The target mentions Python -> ask Termux what Python it has.
        if (pythonWords.containsMatchIn(text) && canOffer("python")) {
            val scriptTitle = jobFor("python")?.scriptTitle
            if (scriptTitle != null) {
                out.add(
                    PlanStep(
                        "termux.run-approved-script",
                        mapOf(ARG_SCRIPT_TITLE to scriptTitle),
                        why = "This mentions Python — ask Termux what Python it has.",
                        auto = false
                    )
                )
            }
        }

        // 7. The target is about Termux itself -> list what Termux can do.
        if (termuxWords.containsMatchIn(text) && termuxReady()) {
            out.add(
                PlanStep(
                    "termux.list-capabilities",
                    why = "This is about Termux itself — list what Termux can do on this phone first.",
                    auto = true
                )
            )
        }

        return out
    }

    // ----- Guard: keep ungated Termux steps out of a refined plan -----

    /**
     * Which probed capability a composed command needs, from the tool
     * it starts with. Null when the command names no tool this map
     * knows — then only the Termux-ready check applies.
     */
    fun capabilityForCommand(command: String): String? {
        val first = command.trim().substringBefore(" ").lowercase()
        return when (first) {
            "ping" -> "network"
            "curl", "wget" -> "http"
            "nslookup", "dig", "host" -> "dns"
            "nmap", "arp-scan" -> "discovery"
            "aircrack-ng", "airmon-ng", "airodump-ng", "aireplay-ng" -> "wireless-audit"
            "python3", "python", "pip" -> "python"
            "jq" -> "json"
            "git", "ssh", "scp" -> "dev"
            "grep", "sed", "awk", "tail", "sort", "less" -> "files"
            "sqlite3" -> "data"
            "gcc", "clang", "make", "cargo", "go" -> "build"
            else -> null
        }
    }

    /** Which probed capability a built-in script needs. Null when none. */
    fun capabilityForScriptTitle(scriptTitle: String): String? = when (scriptTitle) {
        "Check what Python is there" -> "python"
        "Check the network" -> "network"
        else -> null
    }

    /**
     * Drops Termux steps the gate forbids — the enforcement point for
     * every path that can put a Termux step in a plan (the rule-based
     * planner AND the remote reasoner, which picks from the same
     * registry JSON). Non-Termux steps pass through untouched.
     *
     * - "Check Termux is there" and "Use Termux tools" need only the
     *   setting on: they are how the user finds out Termux is missing.
     * - Every other Termux step needs Termux ready (setting on,
     *   installed, permission on).
     * - A run-command step needs the capability its command's tool
     *   belongs to, when the tool is one this map knows.
     * - A run-approved-script step needs the capability its script
     *   belongs to, when the script is one this map knows.
     * - A run-command step with no command, and a run-approved-script
     *   step with no script title, are dropped — there is nothing to run.
     */
    fun filterSteps(steps: List<PlanStep>): List<PlanStep> {
        return steps.filter { step ->
            if (!isTermuxStep(step.capabilityId)) return@filter true
            val gate = currentGate() ?: return@filter false
            when (step.capabilityId) {
                "termux.check-installed", "screen.termux" ->
                    gate.useTermuxInPlans
                else -> {
                    if (!gate.termuxReady()) return@filter false
                    when (step.capabilityId) {
                        "termux.run-command" -> {
                            val command = step.args[ARG_COMMAND].orEmpty()
                            if (command.isBlank()) return@filter false
                            val needed = capabilityForCommand(command)
                            needed == null || gate.canOffer(needed)
                        }
                        "termux.run-approved-script" -> {
                            val title = step.args[ARG_SCRIPT_TITLE].orEmpty()
                            if (title.isBlank()) return@filter false
                            val needed = capabilityForScriptTitle(title)
                            needed == null || gate.canOffer(needed)
                        }
                        else -> true
                    }
                }
            }
        }
    }
}
