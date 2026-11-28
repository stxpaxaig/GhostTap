package com.ghosttap.core

import java.lang.reflect.Modifier

/**
 * What the scanner found when it looked at the real code.
 *
 * @param registered "ClassName.methodName" strings whose sourceMethod appears
 *   in the registry — the wiring the engine already knows about.
 * @param candidates public methods that exist on the ViewModels but have NO
 *   registry entry — real code that isn't visible to the intelligence system
 *   yet. Candidates are leads for wiring, not promises: a method may be UI
 *   glue, a helper, or genuinely new capability surface.
 */
data class ScanReport(
    val registered: List<String>,
    val candidates: List<String>
)

/**
 * Genuine introspection over Ghost Tap's actual ViewModel code — no new
 * dependencies, just java.lang.reflect.
 *
 * For each ViewModel instance it lists public declared methods (name +
 * parameter types), normalizes Kotlin property accessors (getFoo -> foo),
 * and compares against every registry entry's [IntelCapability.sourceMethod]
 * ("ClassName.methodName"). Anything public with no registry entry is
 * reported as a candidate. This is how the system discovers capabilities
 * from the real implementation rather than a fixed list.
 */
object CapabilityScanner {

    private val skipNames = setOf(
        "toString", "hashCode", "equals", "getClass",
        "notify", "notifyAll", "wait", "clone", "finalize"
    )

    fun scan(viewModels: List<Any>, registry: CapabilityRegistry): ScanReport {
        // Keep ownership in the match: a method registered for one
        // ViewModel must never make a same-named method on another ViewModel
        // look integrated.
        val knownSources = registry.all()
            .flatMap { capability ->
                capability.sourceMethod.split(';').mapNotNull { rawSource ->
                    val source = rawSource.substringBefore('(').trim()
                    if (source.isEmpty() || source.startsWith("Manual:") ||
                        source == "IntelViewModel (manual instruction)" || '.' !in source
                    ) {
                        null
                    } else {
                        val owner = source.substringBeforeLast('.').substringAfterLast('.')
                        val method = normalize(source.substringAfterLast('.')) ?: return@mapNotNull null
                        owner to method
                    }
                }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, methods) -> methods.toSet() }
        // Keep each concrete signature: overloaded methods are distinct
        // implementation entry points and must not overwrite one another.
        data class ScannedMethod(
            val owner: String,
            val normalizedName: String,
            val display: String
        )
        val declaredMethods = mutableListOf<ScannedMethod>()

        for (vm in viewModels) {
            val cls = vm.javaClass
            val clsName = cls.simpleName.ifBlank { cls.name.substringAfterLast('.') }
            for (m in cls.declaredMethods) {
                try {
                    if (m.isSynthetic || m.isBridge) continue
                    if (!Modifier.isPublic(m.modifiers)) continue
                    val raw = m.name
                    if (raw in skipNames) continue
                    if ('
        return ScanReport(
            registered = registered.sorted(),
            candidates = candidates.sorted()
        )
    }

    /**
     * Kotlin `val foo` compiles to `getFoo()`/`isFoo()`; normalize both to
     * "foo" so property-style sourceMethods ("GhostTapViewModel.allTargets")
     * match their accessors. Returns null for names we should ignore.
     */
    private fun normalize(raw: String): String? {
        if (raw.startsWith("get") && raw.length > 3 && raw[3].isUpperCase()) {
            return raw.substring(3).replaceFirstChar { it.lowercase() }
        }
        if (raw.startsWith("is") && raw.length > 2 && raw[2].isUpperCase()) {
            return raw.substring(2).replaceFirstChar { it.lowercase() }
        }
        if (raw.startsWith("set") && raw.length > 3 && raw[3].isUpperCase()) {
            return null // setters are not capability surface
        }
        return raw
    }
}
 in raw) continue
                    if (raw.startsWith("component")) continue
                    if (raw == "copy") continue
                    val norm = normalize(raw) ?: continue
                    val sig = "$clsName.$raw(" +
                        m.parameterTypes.joinToString(", ") { it.simpleName } + ")"
                    declaredMethods.add(ScannedMethod(clsName, norm, sig))
                } catch (_: Exception) {
                    // One bad method must never break the scan.
                }
            }
        }

        val registered = mutableListOf<String>()
        val candidates = mutableListOf<String>()
        // For each concrete signature, check its own class and normalized
        // method name. This preserves overloads while retaining owner safety.
        for (method in declaredMethods.distinctBy { it.owner to it.display }) {
            if (method.normalizedName in knownSources[method.owner].orEmpty()) {
                registered.add(method.display)
            } else {
                candidates.add(method.display)
            }
        }
        return ScanReport(
            registered = registered.sorted(),
            candidates = candidates.sorted()
        )
    }

    /**
     * Kotlin `val foo` compiles to `getFoo()`/`isFoo()`; normalize both to
     * "foo" so property-style sourceMethods ("GhostTapViewModel.allTargets")
     * match their accessors. Returns null for names we should ignore.
     */
    private fun normalize(raw: String): String? {
        if (raw.startsWith("get") && raw.length > 3 && raw[3].isUpperCase()) {
            return raw.substring(3).replaceFirstChar { it.lowercase() }
        }
        if (raw.startsWith("is") && raw.length > 2 && raw[2].isUpperCase()) {
            return raw.substring(2).replaceFirstChar { it.lowercase() }
        }
        if (raw.startsWith("set") && raw.length > 3 && raw[3].isUpperCase()) {
            return null // setters are not capability surface
        }
        return raw
    }
}
