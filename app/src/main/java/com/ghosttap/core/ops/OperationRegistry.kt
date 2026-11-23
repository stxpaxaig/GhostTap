package com.ghosttap.core.ops

import com.ghosttap.core.CapabilityRegistry
import com.ghosttap.core.IntelCapability
import com.ghosttap.core.OpResult
import com.ghosttap.core.TermuxPlanSelection

/**
 * The single list of operations the user can pick. The entry screen,
 * the planner, the loop and VNIC all read from here — an operation
 * that is not registered is invisible to all of them.
 *
 * Registration is idempotent: registering the same id twice keeps the
 * latest definition. That is what lets area packages (1–7) add their
 * operations later without touching this file.
 */
object OperationRegistry {

    private val ops = LinkedHashMap<String, Operation>()

    /** Register one operation (overwrites any earlier definition with the same id). */
    fun register(op: Operation) {
        ops[op.id] = op
    }

    /** Look one operation up by id. Null when nothing is registered under that id. */
    fun get(id: String): Operation? = ops[id]

    /** Every registered operation, in registration order. */
    fun all(): List<Operation> = ops.values.toList()

    /** Every registered operation in one area, in registration order. */
    fun byArea(area: OperationArea): List<Operation> =
        ops.values.filter { it.area == area }

    /** The areas that have at least one operation, in first-seen order. */
    fun areas(): List<OperationArea> = ops.values.map { it.area }.distinct()

    /** Test-only: empties the registry so tests start from a known state. Never called by the app. */
    internal fun clear() {
        ops.clear()
    }

    /**
     * Publishes every registered operation to the [CapabilityRegistry] so
     * the planner, the loop and VNIC can see and run them through the
     * same [com.ghosttap.core.IntelEngine.run] path as every other
     * capability. Idempotent — safe to call again when new operations
     * are registered later.
     */
    fun publishToCapabilityRegistry(context: OperationContext) {
        ops.values.forEach { op ->
            CapabilityRegistry.register(op.asCapability(context))
        }
    }
}

/**
 * The bridge to the existing [CapabilityRegistry]: one operation becomes
 * one [IntelCapability] with the same id, title and plain-words detail,
 * so the planner can emit the operation's id and the loop can run it.
 * The capability's run executes the operation through a fresh
 * [OperationRunner] bound to [context] — preparation, execution and
 * verification all still happen, and the [OperationResult] is mapped to
 * an [OpResult] (DONE → ok=true, anything else → ok=false with the
 * plain-words reason). Nothing is re-implemented here.
 *
 * Plan-step args reach the operation through the context's choice
 * channel: before the runner starts, the step's args are seeded into
 * [OperationContext.pendingChoice] ([TermuxPlanSelection.ARG_COMMAND]
 * for a composed command, [TermuxPlanSelection.ARG_SCRIPT_TITLE] for
 * a script to run, "choice"/"pendingChoice" for anything else that
 * asks the user to pick) and into [OperationContext.pendingArgs]
 * ([TermuxPlanSelection.ARG_PREFIX] keys become the named script
 * arguments). The published [IntelCapability.inputs] now describes
 * those real argument channels, so the planner can supply them instead
 * of treating every operation as input-free. The seeded values are
 * restored afterwards, so a manual run that already had a pending
 * choice is never clobbered.
 */
fun Operation.asCapability(context: OperationContext): IntelCapability =
    IntelCapability(
        id = id,
        title = title,
        detail = description,
        category = area.capabilityCategory,
        inputs = operationInputs(this),
        outputs = listOf("outcome: what actually happened, in plain words"),
        preconditions = prerequisites,
        readOnly = riskLevel == "READ",
        riskLevel = riskLevel,
        effects = description,
        verified = verified,
        sourceMethod = if (route != null) "Manual: $route screen (operation $id)" else "Operation: $id",
        run = { args ->
            val savedChoice = context.pendingChoice
            val savedArgs = context.pendingArgs
            val seededChoice = args["pendingChoice"]
                ?: args[TermuxPlanSelection.ARG_COMMAND]
                ?: args[TermuxPlanSelection.ARG_SCRIPT_TITLE]
                ?: args["choice"]
            if (seededChoice != null) context.pendingChoice = seededChoice
            val seededArgs = args
                .filterKeys { it.startsWith(TermuxPlanSelection.ARG_PREFIX) }
                .mapKeys { it.key.removePrefix(TermuxPlanSelection.ARG_PREFIX) }
            if (seededArgs.isNotEmpty()) context.pendingArgs = seededArgs
            try {
                when (val result = OperationRunner(context).run(this)) {
                    is OperationResult.Done ->
                        OpResult(true, result.reason.removePrefix("${title}: "), result.evidence)
                    is OperationResult.Failed ->
                        OpResult(false, result.reason, result.evidence)
                    is OperationResult.NeedsUserAction ->
                        OpResult(false, result.prompt, result.evidence)
                    is OperationResult.NeedsUserChoice ->
                        OpResult(false, result.reason, result.evidence)
                }
            } catch (e: Exception) {
                OpResult(false, "Operation '$id' failed: ${e.message ?: "unknown problem"}")
            } finally {
                context.pendingChoice = savedChoice
                context.pendingArgs = savedArgs
            }
        }
    )

/**
 * Exact caller-facing argument shape for a registered operation.
 *
 * This is intentionally explicit: operation inputs are derived from the
 * concrete operation implementations, not guessed from their descriptions.
 * Screen-first operations and operations that read live ViewModel state
 * take no plan arguments. The listed ids are the operations that actually
 * consume the pending-choice or pending-argument channels.
 */
internal fun operationInputs(op: Operation): List<String> {
    if (op.route != null) return emptyList()
    return when (op.id) {
        "termux.run-command" -> listOf("command")
        "termux.run-approved-script" -> listOf("scriptTitle", "arg.<name>")
        "net.traceroute", "net.dns_lookup" ->
            listOf("arg.host", "pendingChoice", "choice")
        "net.qr_generate" ->
            listOf("arg.text", "pendingChoice", "choice")
        "visual.see_and_tap" ->
            listOf("arg.goal", "pendingChoice", "choice")
        "net.talk-to-host",
        "spp.watch-for-reply",
        "usb.set-speed",
        "usb.talk-to-board",
        "usb.load-software",
        "usb.set-flash-spot",
        "vesp32.send-message",
        "net.find-and-talk",
        "net.open-advertised-service",
        "net.fetch-page",
        "net.port-scan",
        "visual.save-last-photo",
        "visual.rename-profile",
        "visual.delete-profile",
        "visual.copy-from-photo",
        "intel.look-into-device",
        "intel.delete-case-file",
        "intel.delete-saved-plan",
        "intel.check-what-else",
        "intel.run-any-control",
        "nfc.erase-confirmed",
        "nfc.send-named-command",
        "nfc.run-named-script",
        "nfc.arm-named-script",
        "nfc.read-card-blocks",
        "nfc.classic-write-guided",
        "vnic.use-saved-plan" -> listOf("pendingChoice", "choice")
        else -> emptyList()
    }
}
