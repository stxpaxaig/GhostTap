package com.ghosttap.termux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Localhost-only JSON bridge between Ghost Tap and Termux.
 *
 * Design decision (verified against how Termux works): GHOST TAP
 * hosts the server. Termux has no built-in bridge — it runs sshd or a
 * Python http server only when the user starts one, and Android 12+
 * can kill a long-lived Termux background process. Ghost Tap is the
 * app on screen, so it owns the listener; short-lived Termux scripts
 * talk to it with curl and exit. The reverse direction (Ghost Tap as
 * an HTTP client to a Termux-side server) exists as [postToTermuxService]
 * for the day the user starts one — it is not the main path.
 *
 * Security, all enforced here:
 * - Binds 127.0.0.1 explicitly. Never 0.0.0.0. Nothing off this phone
 *   can reach it.
 * - One session token, generated fresh on every start with
 *   SecureRandom, kept in RAM only. Never written to a file, never
 *   logged. It reaches Termux only as an in-memory RUN_COMMAND
 *   argument (see [TermuxClient]). A message without the right token
 *   is refused and the connection closed.
 * - Every message carries a request id (UUID). Replies echo it.
 * - Line-delimited JSON, one message per line, 256 KB cap per
 *   message, 10 s read timeout, at most 8 connections at once.
 * - Stdlib only: java.net + org.json (already in the tree). No new
 *   dependency.
 *
 * Message shapes (JSON, one per line):
 * - {"type":"ping","requestId":"…","token":"…"} -> {"type":"pong","requestId":"…"}
 * - {"type":"result","requestId":"…","token":"…","scriptId":"…","stdout":"…","stderr":"…","exitCode":0}
 * - {"type":"log","requestId":"…","token":"…","text":"…"}
 * - {"type":"capabilities","requestId":"…","token":"…","tools":{"python3":true,…}}
 * - {"type":"library","requestId":"…","token":"…","scripts":[{"file":"my-backup.sh"},…]}
 * - {"type":"ask","requestId":"…","token":"…","operation":"termux.list-capabilities","details":{…}}
 *   -> {"type":"ok","requestId":"…","answer":"…"} or {"type":"error","requestId":"…","problem":"…"}
 *
 * The "ask" direction (v3, piece 2): a Termux script asks Ghost Tap to
 * do something, instead of the usual Ghost Tap -> Termux direction.
 * Ghost Tap stays the controller — an ask is answered only when its
 * operation is on [ASK_ALLOWLIST], and the answer goes back on the
 * SAME connection as the reply line. READ asks are executed and
 * answered with the real answer; termux.run-approved-script only
 * STARTS the run and answers that it started — the run's outcome
 * still arrives through the normal result path, never by blocking
 * this connection. A typed command (termux.run-command) is NEVER
 * askable: that would be arbitrary command execution from Termux
 * into Ghost Tap. The [onAsk] handler does the work; the bridge only
 * gates and replies.
 */
class TermuxBridge(
    private val onMessage: (TermuxBridgeMessage) -> Unit = {},
    /** Answers one allowlisted ask. Null = Ghost Tap can't answer asks right now. */
    private val onAsk: (suspend (AskRequest) -> AskReply)? = null
) {
    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        const val DEFAULT_READ_TIMEOUT_MS = 10_000
        const val MAX_MESSAGE_CHARS = 256 * 1024
        const val MAX_CONNECTIONS = 8
    }

    /** Messages that arrive from Termux, already token-checked. */
    sealed interface TermuxBridgeMessage {
        val requestId: String
        data class Ping(override val requestId: String) : TermuxBridgeMessage
        data class Result(
            override val requestId: String,
            val scriptId: String,
            val stdout: String,
            val stderr: String,
            val exitCode: Int?
        ) : TermuxBridgeMessage
        data class Log(override val requestId: String, val text: String) : TermuxBridgeMessage
        data class Capabilities(
            override val requestId: String,
            /** Raw tools JSON, e.g. {"python3":true,"git":false}. Parsed by TermuxRegistry. */
            val toolsJson: String
        ) : TermuxBridgeMessage
        data class Library(
            override val requestId: String,
            /** Raw scripts JSON, e.g. [{"file":"my-backup.sh"}]. Parsed by parseLibraryJson. */
            val scriptsJson: String
        ) : TermuxBridgeMessage
        /**
         * An ask from Termux arrived, passed the token check and the
         * allowlist, and is being answered. Shown on the screen in
         * plain words so the user can see Termux asked for something.
         */
        data class AskArrived(
            override val requestId: String,
            val operation: String
        ) : TermuxBridgeMessage
        /**
         * An ask was refused — not on the allowlist, unknown, badly
         * formed, or the answer itself failed. Shown on the screen in
         * plain words; refusals are never silent.
         */
        data class AskRefused(
            override val requestId: String,
            val operation: String,
            val problem: String
        ) : TermuxBridgeMessage
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private val clients = ConcurrentHashMap.newKeySet<Socket>()

    /** The session token. RAM only — never persisted, never logged. Null when stopped. */
    @Volatile
    var token: String? = null
        private set

    /** The port the bridge is listening on. -1 when stopped. */
    @Volatile
    var port: Int = -1
        private set

    val isRunning: Boolean get() = server?.isClosed == false

    /**
     * Starts the bridge on 127.0.0.1 with an OS-assigned port and a fresh
     * session token. Returns the port, or -1 when it could not start.
     * Starting twice returns the existing port (token unchanged).
     */
    @Synchronized
    fun start(): Int {
        if (isRunning) return port
        return try {
            token = newSessionToken()
            val socket = ServerSocket()
            // Loopback only — this is the whole point. Never 0.0.0.0.
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            server = socket
            port = socket.localPort
            scope.launch { acceptLoop(socket) }
            port
        } catch (_: Exception) {
            token = null
            port = -1
            -1
        }
    }

    /** Stops the bridge, closes every connection, forgets the token. */
    @Synchronized
    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        token = null
        port = -1
    }

    /** Full stop for ViewModel.onCleared(): closes the scope too. */
    fun close() {
        stop()
        scope.cancel()
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            try {
                val client = socket.accept()
                if (clients.size >= MAX_CONNECTIONS) {
                    runCatching { client.close() }
                    continue
                }
                clients.add(client)
                scope.launch { handleConnection(client) }
            } catch (_: Exception) {
                break
            }
        }
    }

    private suspend fun handleConnection(socket: Socket) {
        try {
            socket.soTimeout = DEFAULT_READ_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
            var total = 0
            while (true) {
                val line = reader.readLine() ?: break
                total += line.length
                if (total > MAX_MESSAGE_CHARS) break
                val reply = handleLine(line) ?: break
                writer.write(reply + "\n")
                writer.flush()
            }
        } catch (_: Exception) {
            // A dropped or timed-out connection is normal — Termux
            // scripts connect, send one message, and go away.
        } finally {
            clients.remove(socket)
            runCatching { socket.close() }
        }
    }

    /**
     * Handles one line. Returns the reply line, or null to close the
     * connection without a reply (bad token, bad shape).
     *
     * Suspend because an "ask" runs the answer on the spot: READ asks
     * execute and reply with the real answer. The one WRITE ask
     * (termux.run-approved-script) only STARTS the run — the [onAsk]
     * handler returns once the run is started, and the run's outcome
     * still arrives through the normal result path. This function
     * never blocks waiting on a run.
     */
    suspend fun handleLine(line: String): String? {
        val json = try {
            JSONObject(line)
        } catch (_: Exception) {
            return null
        }
        val requestId = json.optString("requestId", "")
        if (requestId.isBlank()) return null
        // Token check first, before anything else happens.
        val presented = json.optString("token", "")
        val expected = token
        if (expected == null || presented != expected) return null
        return when (json.optString("type", "")) {
            "ping" -> {
                onMessage(TermuxBridgeMessage.Ping(requestId))
                JSONObject().apply {
                    put("type", "pong")
                    put("requestId", requestId)
                }.toString()
            }
            "result" -> {
                onMessage(
                    TermuxBridgeMessage.Result(
                        requestId = requestId,
                        scriptId = json.optString("scriptId", ""),
                        stdout = json.optString("stdout", ""),
                        stderr = json.optString("stderr", ""),
                        exitCode = if (json.has("exitCode")) json.optInt("exitCode") else null
                    )
                )
                JSONObject().apply {
                    put("type", "ok")
                    put("requestId", requestId)
                }.toString()
            }
            "log" -> {
                onMessage(TermuxBridgeMessage.Log(requestId, json.optString("text", "")))
                JSONObject().apply {
                    put("type", "ok")
                    put("requestId", requestId)
                }.toString()
            }
            "capabilities" -> {
                val tools = json.optJSONObject("tools")?.toString() ?: "{}"
                onMessage(TermuxBridgeMessage.Capabilities(requestId, tools))
                JSONObject().apply {
                    put("type", "ok")
                    put("requestId", requestId)
                }.toString()
            }
            "library" -> {
                val scripts = json.optJSONArray("scripts")?.toString() ?: "[]"
                onMessage(TermuxBridgeMessage.Library(requestId, scripts))
                JSONObject().apply {
                    put("type", "ok")
                    put("requestId", requestId)
                }.toString()
            }
            "ask" -> {
                // Token check already happened above, before the when
                // — so this branch is token-gated by construction.
                // The allowlist is the only gate that matters next:
                // anything not on it is refused, nothing executes.
                val operation = json.optString("operation", "")
                val refusal = askRefusalFor(operation)
                if (refusal != null) {
                    onMessage(TermuxBridgeMessage.AskRefused(requestId, operation, refusal))
                    return errorReply(requestId, refusal)
                }
                onMessage(TermuxBridgeMessage.AskArrived(requestId, operation))
                val handler = onAsk
                if (handler == null) {
                    val problem = "Ghost Tap can't answer that right now — try again."
                    onMessage(TermuxBridgeMessage.AskRefused(requestId, operation, problem))
                    return errorReply(requestId, problem)
                }
                when (val reply = handler(AskRequest(requestId, operation, json.optJSONObject("details")))) {
                    is AskReply.Ok -> JSONObject().apply {
                        put("type", "ok")
                        put("requestId", requestId)
                        put("answer", reply.answer)
                    }.toString()
                    is AskReply.Refused -> {
                        onMessage(TermuxBridgeMessage.AskRefused(requestId, operation, reply.problem))
                        errorReply(requestId, reply.problem)
                    }
                }
            }
            else -> JSONObject().apply {
                put("type", "error")
                put("requestId", requestId)
                put("problem", "unknown message type")
            }.toString()
        }
    }

    /** One error reply line, in the same shape for every refusal. */
    private fun errorReply(requestId: String, problem: String): String =
        JSONObject().apply {
            put("type", "error")
            put("requestId", requestId)
            put("problem", problem)
        }.toString()

    /**
     * Health check against our own bridge (or any loopback JSON bridge
     * speaking the same line protocol): opens a socket, sends a ping,
     * waits for the pong. True only on a real pong with our request id.
     */
    fun ping(
        port: Int,
        token: String,
        timeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS
    ): Boolean {
        val requestId = newRequestId()
        val message = JSONObject().apply {
            put("type", "ping")
            put("requestId", requestId)
            put("token", token)
        }.toString()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                socket.soTimeout = timeoutMs
                val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
                writer.write(message + "\n")
                writer.flush()
                val reply = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).readLine()
                    ?: return false
                val json = JSONObject(reply)
                json.optString("type", "") == "pong" && json.optString("requestId", "") == requestId
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Reverse direction: POST one JSON message to a service running in
     * Termux (e.g. a small Python server the user started there).
     * Loopback only — any other host is refused. Returns the response
     * body, or null when it could not be reached. Follows the same
     * timeout + disconnect discipline as the rest of the tree's HTTP.
     */
    fun postToTermuxService(
        port: Int,
        token: String,
        message: JSONObject,
        timeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Ghost-Tap-Token", token)
                setRequestProperty("User-Agent", "GHOST-TAP/1.0")
                doOutput = true
            }
            conn.outputStream.use { it.write(message.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.take(256 * 1024)
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun newSessionToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/** A fresh request id. Pure Kotlin — unit-tested on the JVM. */
fun newRequestId(): String = UUID.randomUUID().toString()

/**
 * One ask from Termux, already token-checked and allowlisted by the
 * bridge. [details] is the ask's "details" object, or null when the
 * ask carried none — only termux.run-approved-script reads it
 * (for "scriptId" and optional "args").
 */
data class AskRequest(
    val requestId: String,
    val operation: String,
    val details: JSONObject?
)

/** What the [onAsk] handler answers with. */
sealed interface AskReply {
    /** The ask ran (or, for a run, started). [answer] is plain words. */
    data class Ok(val answer: String) : AskReply
    /** Refused — nothing ran. [problem] is plain words. */
    data class Refused(val problem: String) : AskReply
}

/**
 * The ONLY operations Termux may ask Ghost Tap to do. Every id here
 * is a real registered operation in OperationAreaTermux — reuse, never
 * re-implement. Anything else is refused by [askRefusalFor], and the
 * bridge executes nothing for it.
 */
val ASK_ALLOWLIST: Set<String> = setOf(
    "termux.check-installed",
    "termux.check-bridge-health",
    "termux.list-capabilities",
    "termux.list-library",
    "termux.refresh-library",
    "termux.run-approved-script"
)

/**
 * Real operations Termux is explicitly NOT allowed to ask for, with
 * the plain-words reason each one is refused. Kept separate from the
 * allowlist so a refusal can say why, not just no.
 */
private val ASK_EXCLUDED_KNOWN: Map<String, String> = mapOf(
    "termux.run-command" to
        "Termux can't ask Ghost Tap to run a typed command — only scripts Ghost Tap already knows.",
    "screen.termux" to
        "Termux can't ask Ghost Tap to open a screen — screens open when you tap them."
)

/**
 * The allowlist gate, in plain words. Returns null when the operation
 * may be asked for, otherwise the plain-words refusal. Pure Kotlin —
 * unit-tested on the JVM. Never invents an operation id: an id that
 * is not on the allowlist and not known at all is refused as unknown.
 */
fun askRefusalFor(operation: String): String? {
    if (operation in ASK_ALLOWLIST) return null
    ASK_EXCLUDED_KNOWN[operation]?.let { return it }
    return "Ghost Tap doesn't know that job — check the name and try again."
}

/**
 * True when Termux may ask Ghost Tap to run this script id: it is a
 * built-in script the user can pick, or one of the user's own scripts
 * already found in Termux ([libraryScriptIds] holds their ids, e.g.
 * "lib:my-backup.sh").
 *
 * Internal scripts are NEVER askable — that is what keeps a typed
 * command out: the free-command runner ("free-command") is internal,
 * so asking for it is refused even though Ghost Tap itself runs it.
 * The probe and the library lister are internal too; they are Ghost
 * Tap's own checks, not scripts to ask for. Pure Kotlin — unit-tested
 * on the JVM.
 */
fun isAskableScriptId(scriptId: String, libraryScriptIds: Set<String>): Boolean {
    if (scriptId.isBlank()) return false
    val builtIn = TermuxRegistry.scriptById(scriptId)
    if (builtIn != null) {
        return !builtIn.internalOnly && TermuxRegistry.isRunnable(builtIn)
    }
    return scriptId in libraryScriptIds
}

/** True only for loopback hosts. The bridge never talks to anything else. */
fun isLoopbackHost(host: String): Boolean =
    host == "127.0.0.1" || host.equals("localhost", ignoreCase = true) || host == "::1"

/** A session token is a long URL-safe Base64 string. Guards against an empty or truncated token. */
fun isPlausibleBridgeToken(token: String): Boolean =
    token.length >= 32 && token.all { it.isLetterOrDigit() || it == '-' || it == '_' }

/**
 * Pulls one string field out of a flat JSON object line without a JSON
 * parser — the JVM-testable core of message handling. Returns null
 * when the field is absent. Handles \" and \\ escapes.
 */
fun extractJsonStringField(line: String, field: String): String? {
    val key = "\"$field\""
    var index = line.indexOf(key)
    while (index >= 0) {
        var i = index + key.length
        while (i < line.length && line[i].isWhitespace()) i++
        if (i < line.length && line[i] == ':') {
            i++
            while (i < line.length && line[i].isWhitespace()) i++
            if (i < line.length && line[i] == '"') {
                val out = StringBuilder()
                i++
                while (i < line.length) {
                    val c = line[i]
                    if (c == '\\' && i + 1 < line.length) {
                        when (line[i + 1]) {
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'r' -> out.append('\r')
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            else -> out.append(line[i + 1])
                        }
                        i += 2
                    } else if (c == '"') {
                        return out.toString()
                    } else {
                        out.append(c)
                        i++
                    }
                }
                return null
            }
            return null
        }
        index = line.indexOf(key, index + 1)
    }
    return null
}
