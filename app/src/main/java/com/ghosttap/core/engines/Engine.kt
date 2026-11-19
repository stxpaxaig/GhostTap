package com.ghosttap.core.engines

import com.ghosttap.core.OpResult

/**
 * The standardized engine interface (v2 seed — Phase 2 grows it).
 *
 * An engine is a peer that does work for Ghost Tap: Termux runs
 * Linux tools, VirtualESP32 runs a virtual board, and future engines
 * will plug in the same way. Ghost Tap stays the orchestrator — it
 * asks, the engine answers, and nothing connects or runs until the
 * user says so. This interface is deliberately small on purpose:
 * every engine can say what it is, whether it is there, whether its
 * link is healthy, and what it can do. Anything an engine cannot do
 * yet is simply not claimed here.
 *
 * Rules for engines (from Justin's standing rules):
 * - Ghost Tap works fully with any engine absent, crashed or denied.
 *   Every call here returns a plain result instead of throwing.
 * - No engine is mandatory. Nothing in Ghost Tap depends on an
 *   engine being there.
 * - Registration never changes the engine itself. VirtualESP32 keeps
 *   its own app and its own way of talking to Ghost Tap — when it
 *   registers (Phase 2), it is registered as it is, not restructured.
 */
data class EngineDescriptor(
    /** Stable machine id, e.g. "termux". */
    val id: String,
    /** Plain-words name, e.g. "Termux". */
    val name: String,
    /** One plain-words line saying what it is. */
    val detail: String,
    /** The engine's version as it reports it, e.g. "v2". */
    val version: String
)

/** One peer engine, speaking the same small interface as every other engine. */
interface Engine {
    val descriptor: EngineDescriptor

    /** True when the engine is on this phone, as last checked. */
    fun isInstalled(): Boolean

    /** Live check: is the engine reachable right now. Never throws. */
    suspend fun healthCheck(): OpResult

    /** Plain-words titles of what this engine can do right now. */
    fun capabilityTitles(): List<String>
}

/**
 * The registry every engine joins. Registering twice is a no-op —
 * the first registration wins, so a screen rebuild never makes a
 * second copy. Read-only from the outside: engines are added once,
 * never removed at run time.
 */
object EngineRegistry {
    private val engines = mutableListOf<Engine>()

    fun register(engine: Engine) {
        if (engines.none { it.descriptor.id == engine.descriptor.id }) {
            engines.add(engine)
        }
    }

    fun all(): List<Engine> = engines.toList()

    fun byId(id: String): Engine? = engines.firstOrNull { it.descriptor.id == id }
}
