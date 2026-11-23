package com.ghosttap.termux

import org.json.JSONObject

/**
 * Termux capability registry: the scripts Ghost Tap may run, and the
 * 15 things Termux can do for Ghost Tap.
 *
 * TWO KINDS OF SCRIPT (v2 — zero limitations, Justin's rule):
 * - BUILT_IN: the helper scripts that come with Ghost Tap, fixed
 *   files the user puts in Termux once, under ~/.termux/tasker/
 *   (see the setup sheet). Two of them are internal (the probe and
 *   the library lister) and never appear in the picker; one of them
 *   is the runner that carries a command the user typed.
 * - ADDED: scripts the user wrote themselves in Termux and put in
 *   ~/.termux/tasker/. Ghost Tap finds them with the library lister,
 *   shows them under "Your scripts", and runs them as they are —
 *   no rebuild, no approval list to update. If it is a .sh file in
 *   that folder, it runs.
 *
 * FREE COMMANDS: the user can also type any command in the box on
 * the Termux screen (or the intelligence can compose one). It runs
 * through the runner script (`ghosttap-run.sh`) with `bash -c`, so
 * pipes, redirects and the rest of the shell work — that is the
 * whole point of a terminal. The only thing refused is an empty
 * command. Nothing else about a command is checked, blocked or
 * "cleaned up" by Ghost Tap.
 *
 * The probe script (ghosttap-probe.sh) is also built in. It checks
 * which tools are installed (`command -v …`) and sends the answers
 * back to Ghost Tap's bridge. It is the only script that talks to the
 * bridge with a capabilities message; the others send result
 * messages. The library lister sends a library message. Every
 * script takes the bridge port and token as its last two arguments,
 * so it knows where to send its answer.
 */
data class ScriptArg(
    /** Argument name inside the script ($1, $2 … in order). */
    val name: String,
    /** Plain-words label shown next to the box. */
    val label: String,
    val hint: String = "",
    val required: Boolean = false,
    /** Allowed characters, or null for "any short text". Checked in validateArgs. */
    val pattern: Regex? = null,
    val maxLength: Int = 200
)

/** Where a script came from. Built-ins ship with Ghost Tap; added scripts are the user's own. */
enum class ScriptOrigin {
    BUILT_IN,
    ADDED
}

data class ApprovedScript(
    val id: String,
    /** File name under ~/.termux/tasker/. Fixed — never user-typed. */
    val fileName: String,
    /** Plain-words title shown on the button. */
    val title: String,
    /** One plain-words line saying what it does. */
    val detail: String,
    val category: String,
    val args: List<ScriptArg> = emptyList(),
    /** Internal scripts (the probe, the library lister, the runner) run but never appear in the picker. */
    val internalOnly: Boolean = false,
    /** Built-ins come with Ghost Tap; added scripts were found in Termux. */
    val origin: ScriptOrigin = ScriptOrigin.BUILT_IN
) {
    /** Absolute path inside Termux. Ghost Tap never guesses a path. */
    val termuxPath: String get() = "${TermuxClient.TASKER_DIR}/$fileName"
}

/** What Ghost Tap knows about one Termux capability right now. */
enum class TermuxCapabilityState {
    /** Not checked yet in this session. */
    UNKNOWN,

    /** A real probe answered: the tool is there. */
    YES,

    /** A real probe answered: the tool is not there. */
    NO,

    /** The probe could not run or did not answer — not proof of anything. */
    COULD_NOT_CHECK
}

data class TermuxCapability(
    val id: String,
    /** One of the 14 categories, in plain words. */
    val category: String,
    val title: String,
    val detail: String,
    /** Tool names the probe checks with `command -v` (any one counts). */
    val probeTools: List<String>,
    val state: TermuxCapabilityState = TermuxCapabilityState.UNKNOWN,
    /** The user can switch a capability off; off means Ghost Tap won't offer it. */
    val enabled: Boolean = true
)

object TermuxRegistry {

    /** The probe script id — the only internal script that checks tools. */
    const val PROBE_SCRIPT_ID = "probe"

    /** The runner script id — carries a command the user typed. Internal only. */
    const val FREE_COMMAND_SCRIPT_ID = "free-command"

    /** The library lister script id — lists the user's own scripts. Internal only. */
    const val LIST_LIBRARY_SCRIPT_ID = "list-library"

    /** Files in the tasker folder that are never offered as runnable scripts. */
    val LIBRARY_EXCLUDED_FILES: Set<String> = setOf("ghosttap-lib.sh")

    private val hostArg = ScriptArg(
        name = "host",
        label = "What to check",
        hint = "A website or an address on your Wi-Fi, like google.com",
        required = false,
        pattern = Regex("^[A-Za-z0-9._:-]{1,200}$"),
        maxLength = 200
    )

    /**
     * The command box argument. No pattern, no character rules — the
     * whole point is that any command runs as typed. The only limit
     * is a very generous length cap so one paste cannot break the
     * handover to Termux; it is not a limit on what the command does.
     */
    private val commandArg = ScriptArg(
        name = "command",
        label = "What should Termux run",
        hint = "Any command, just like in Termux — pipes and redirects work",
        required = true,
        pattern = null,
        maxLength = 100_000
    )

    /** Every script Ghost Tap ships with. The user's own scripts join this list at run time (see [mergeLibrary]). */
    val scripts: List<ApprovedScript> = listOf(
        ApprovedScript(
            id = "hello",
            fileName = "ghosttap-hello.sh",
            title = "Say hello",
            detail = "Runs a tiny test in Termux and sends the answer back. Use it first — if this works, the link works.",
            category = "Checking the link"
        ),
        ApprovedScript(
            id = "check-python",
            fileName = "ghosttap-check-python.sh",
            title = "Check what Python is there",
            detail = "Asks Termux which Python it has and what version it is.",
            category = "Python"
        ),
        ApprovedScript(
            id = "check-network",
            fileName = "ghosttap-check-network.sh",
            title = "Check the network",
            detail = "Pings a website or an address on your Wi-Fi and says whether it answered.",
            category = "Network checks",
            args = listOf(hostArg)
        ),
        ApprovedScript(
            id = "list-tools",
            fileName = "ghosttap-list-tools.sh",
            title = "List the tools installed",
            detail = "Lists the command-line tools Termux has ready to use.",
            category = "Checking the link"
        ),
        ApprovedScript(
            id = "check-storage",
            fileName = "ghosttap-check-storage.sh",
            title = "Check storage space",
            detail = "Says how much space is free on the phone and in Termux.",
            category = "Checking the link"
        ),
        ApprovedScript(
            id = "check-time",
            fileName = "ghosttap-check-time.sh",
            title = "Check the time and date",
            detail = "Asks Termux for the date and time, so Ghost Tap can see if they match the phone.",
            category = "Checking the link"
        ),
        ApprovedScript(
            id = PROBE_SCRIPT_ID,
            fileName = "ghosttap-probe.sh",
            title = "Check what Termux can do",
            detail = "Checks every tool Ghost Tap knows about and sends the list back.",
            category = "Checking the link",
            internalOnly = true
        ),
        ApprovedScript(
            id = FREE_COMMAND_SCRIPT_ID,
            fileName = "ghosttap-run.sh",
            title = "Your command",
            detail = "Runs the command you typed, exactly as you typed it.",
            category = "Your command",
            args = listOf(commandArg),
            internalOnly = true
        ),
        ApprovedScript(
            id = LIST_LIBRARY_SCRIPT_ID,
            fileName = "ghosttap-list-library.sh",
            title = "Check what scripts are there",
            detail = "Lists the scripts you put in Termux yourself and sends the list back.",
            category = "Your scripts",
            internalOnly = true
        )
    )

    /** Scripts the user can pick. The probe runs on its own. */
    val userScripts: List<ApprovedScript> get() = scripts.filter { !it.internalOnly }

    fun scriptById(id: String): ApprovedScript? = scripts.firstOrNull { it.id == id }

    fun isAllowlisted(id: String): Boolean = scripts.any { it.id == id }

    /**
     * True when Ghost Tap may run this script: it is a built-in, or
     * it is one of the user's own scripts found in Termux. The check
     * is on the script object, not on a fixed list of ids — a script
     * the user added is runnable the moment it is found.
     */
    fun isRunnable(script: ApprovedScript): Boolean =
        script.origin == ScriptOrigin.ADDED || isAllowlisted(script.id)

    /**
     * Validates structured arguments for a script. Returns the cleaned
     * argument list, or null (with the reason in plain words via
     * [validateArgsProblem]) when an argument is not allowed.
     *
     * What is checked here: a required box left empty, a value too
     * long to hand over, or a value that does not look like what the
     * box asks for (the host box wants a website or address). What is
     * NOT checked, on purpose: shell characters. Pipes, redirects,
     * quotes and the rest are the command's business — Ghost Tap
     * passes the text to Termux exactly as typed.
     */
    fun validateArgs(script: ApprovedScript, args: Map<String, String>): List<String>? {
        val out = mutableListOf<String>()
        for (spec in script.args) {
            val raw = (args[spec.name] ?: "").trim()
            if (raw.isEmpty()) {
                if (spec.required) return null
                out.add("")
                continue
            }
            if (raw.length > spec.maxLength) return null
            if (spec.pattern != null && !spec.pattern.matches(raw)) return null
            out.add(raw)
        }
        return out
    }

    fun validateArgsProblem(script: ApprovedScript, args: Map<String, String>): String {
        for (spec in script.args) {
            val raw = (args[spec.name] ?: "").trim()
            if (raw.isEmpty() && spec.required) return "Fill in \"${spec.label}\" first."
            if (raw.length > spec.maxLength) return "\"${spec.label}\" is too long — keep it short."
            if (spec.pattern != null && raw.isNotEmpty() && !spec.pattern.matches(raw)) {
                return "\"${spec.label}\" doesn't look like a website or address. Use letters, numbers, dots and dashes only."
            }
        }
        return ""
    }

    /**
     * The 15 things Termux can do for Ghost Tap. Each entry names the
     * tools the probe looks for; a capability counts as there when ANY
     * of its tools answers `command -v`. Nothing is marked "there"
     * without a real probe answer.
     */
    fun defaultCapabilities(): List<TermuxCapability> = listOf(
        TermuxCapability(
            id = "python", category = "Python",
            title = "Python",
            detail = "Run Python programs and check Python versions.",
            probeTools = listOf("python3", "python", "pip")
        ),
        TermuxCapability(
            id = "bash", category = "Bash",
            title = "Shell scripts",
            detail = "Run small shell scripts that do one job.",
            probeTools = listOf("bash", "sh")
        ),
        TermuxCapability(
            id = "files", category = "File and text",
            title = "Files and text",
            detail = "Read, search, sort and change text files.",
            probeTools = listOf("grep", "sed", "awk", "tail", "sort")
        ),
        TermuxCapability(
            id = "json", category = "JSON",
            title = "Working with lists and data files",
            detail = "Read and tidy up JSON data files.",
            probeTools = listOf("jq", "python3")
        ),
        TermuxCapability(
            id = "network", category = "Network checks",
            title = "Network checks",
            detail = "Ping things and see if they answer.",
            probeTools = listOf("ping", "curl")
        ),
        TermuxCapability(
            id = "dns", category = "DNS",
            title = "Looking up addresses",
            detail = "Turn a website name into its address.",
            probeTools = listOf("nslookup", "dig", "getent", "host")
        ),
        TermuxCapability(
            id = "http", category = "Web requests",
            title = "Fetching web pages",
            detail = "Download a web page or send a web request.",
            probeTools = listOf("curl", "wget")
        ),
        TermuxCapability(
            id = "wireless-audit", category = "Wireless diagnostics",
            title = "Aircrack-ng wireless diagnostics",
            detail = "Detects Aircrack-ng and exposes its local version/help and wireless adapter diagnostics. Does not capture handshakes, deauthenticate clients, or attempt password recovery.",
            probeTools = listOf("aircrack-ng", "airmon-ng", "airodump-ng", "aireplay-ng")
        ),
        TermuxCapability(
            id = "discovery", category = "Finding things nearby",
            title = "Finding things on the network",
            detail = "Look for other devices and services on the Wi-Fi.",
            probeTools = listOf("nmap", "arp-scan", "ping")
        ),
        TermuxCapability(
            id = "dev", category = "Developer tools",
            title = "Developer tools",
            detail = "Version control, secure shell, and code tools.",
            probeTools = listOf("git", "ssh", "scp")
        ),
        TermuxCapability(
            id = "data", category = "Working with data",
            title = "Working with data",
            detail = "Crunch numbers and small databases.",
            probeTools = listOf("python3", "sqlite3")
        ),
        TermuxCapability(
            id = "automation", category = "Running things on a schedule",
            title = "Running things on a schedule",
            detail = "Run a job later or on a regular schedule.",
            probeTools = listOf("termux-job-scheduler", "at", "crond")
        ),
        TermuxCapability(
            id = "logs", category = "Reading logs",
            title = "Reading logs",
            detail = "Search through saved log files for a problem.",
            probeTools = listOf("grep", "tail", "less")
        ),
        TermuxCapability(
            id = "build", category = "Building apps",
            title = "Building things",
            detail = "Compilers and build tools for code.",
            probeTools = listOf("gcc", "clang", "make", "cargo", "go")
        ),
        TermuxCapability(
            id = "termux-api", category = "Phone tools",
            title = "Phone tools",
            detail = "Extra Termux tools that can read phone details.",
            probeTools = listOf("termux-battery-status", "termux-info", "termux-clipboard-get")
        )
    )

    /**
     * Applies a probe answer to the capability list. [toolsJson] is the
     * raw {"tool": true/false} object the probe sent. A tool counts only
     * when its value is literally true. Capabilities whose tools are
     * all absent stay NO; anything unparseable leaves the list as
     * COULD_NOT_CHECK rather than guessing.
     */
    fun applyProbeResult(
        current: List<TermuxCapability>,
        toolsJson: String
    ): List<TermuxCapability> {
        val tools = parseProbeToolsJson(toolsJson) ?: return current.map {
            it.copy(state = TermuxCapabilityState.COULD_NOT_CHECK)
        }
        return current.map { cap ->
            val found = cap.probeTools.any { tools[it] == true }
            val anyAnswered = cap.probeTools.any { tools.containsKey(it) }
            val state = when {
                found -> TermuxCapabilityState.YES
                anyAnswered -> TermuxCapabilityState.NO
                else -> TermuxCapabilityState.COULD_NOT_CHECK
            }
            cap.copy(state = state)
        }
    }

    /** Builds the probe JSON check a script sends: {"python3":true,…}. */
    fun probeResultJson(tools: Map<String, Boolean>): JSONObject =
        JSONObject().apply {
            val inner = JSONObject()
            tools.forEach { (name, present) -> inner.put(name, present) }
            put("tools", inner)
        }

    /**
     * Turns a script file name into a plain-words button title:
     * "my-backup.sh" -> "My backup". The file name is the id — the
     * title is only for showing.
     */
    fun scriptTitleFromFile(fileName: String): String {
        val base = fileName.removeSuffix(".sh")
        if (base.isBlank()) return fileName
        return base.split('-', '_', ' ', '.')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { it.uppercaseChar() }
            }.ifBlank { fileName }
    }

    /**
     * Merges the scripts the library lister found into runnable
     * entries. Built-in files and the shared helper are left out —
     * they already have their own entries, and the helper is not a
     * script to run. Anything else the user put in the folder runs
     * as it is. Order is the order Termux listed them.
     */
    fun mergeLibrary(discoveredFileNames: List<String>): List<ApprovedScript> {
        val builtInFiles = scripts.map { it.fileName }.toSet()
        return discoveredFileNames
            .distinct()
            .filter { it.endsWith(".sh") }
            .filter { it !in builtInFiles && it !in LIBRARY_EXCLUDED_FILES }
            .map { file ->
                ApprovedScript(
                    id = "lib:$file",
                    fileName = file,
                    title = scriptTitleFromFile(file),
                    detail = "A script you put in Termux — runs the file as it is.",
                    category = "Your scripts",
                    origin = ScriptOrigin.ADDED
                )
            }
    }
}

/**
 * Parses a probe tools object — {"python3":true,"git":false} — with no
 * JSON library, so it is unit-testable on the JVM. Returns null when
 * the text is not a JSON object at all. Unknown keys are kept; values
 * other than true/false are ignored.
 */
fun parseProbeToolsJson(text: String): Map<String, Boolean>? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
    val out = mutableMapOf<String, Boolean>()
    val pair = Regex("\"([^\"]+)\"\\s*:\\s*(true|false)")
    pair.findAll(trimmed).forEach { match ->
        out[match.groupValues[1]] = match.groupValues[2] == "true"
    }
    return out
}

/**
 * Pulls the script file names out of a library message —
 * {"scripts":[{"file":"my-backup.sh"},{"file":"ghosttap-hello.sh"}]}
 * — with no JSON library, so it is unit-testable on the JVM.
 * Returns an empty list when nothing usable is there; never throws.
 * Only the "file" values are read; anything else in the message is
 * ignored.
 */
fun parseLibraryJson(text: String): List<String> {
    val trimmed = text.trim()
    // Accepts a bare array ([{"file":"x.sh"}]) or an object wrapping
    // one ({"scripts":[{"file":"x.sh"}]}) — the bridge sends the
    // array on its own, the object form is for hand-written tests.
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return emptyList()
    if (!trimmed.endsWith("}") && !trimmed.endsWith("]")) return emptyList()
    val out = mutableListOf<String>()
    val file = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"")
    file.findAll(trimmed).forEach { match ->
        val name = match.groupValues[1].trim()
        if (name.isNotBlank()) out.add(name)
    }
    return out
}
