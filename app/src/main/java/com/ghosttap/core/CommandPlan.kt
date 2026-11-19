package com.ghosttap.core

/**
 * v3.2 — home command box (DO-IT): turn the user's typed words into
 * steps Ghost Tap runs ITSELF, end to end.
 *
 * Pure function, no Android framework, no network, no database: the
 * typed words become a MANUAL target for the existing rule-based
 * [Planner], which proposes steps that reference ONLY registered
 * capability ids (validated; unknown ids are dropped, never crash).
 *
 * v3.2 change — no do-it-yourself steps: `sys.manual_step` steps
 * are dropped here (they stay in [Planner] for the Investigations
 * screen, where the timeline they point at really is on the screen).
 * The command box never shows a plan and never asks the user to do
 * the work — anything Ghost Tap cannot do alone is said plainly as
 * an outcome line by the box itself, not as a step to tap through.
 * Steps that need the user in the real world (an NFC tap) still run
 * through the engine and report their honest instruction as the
 * outcome — never dressed up as done.
 *
 * Nothing runs here — the plan is only built. The home screen runs
 * every step in order, automatically, through [IntelEngine.run].
 * A step that changes something runs when its target is pinned by
 * the plan (an IP, a MAC, a host) or by the device in front of it;
 * the TV flow handles the one case where the target is a choice
 * (several TVs) by asking once, with a list.
 *
 * The Termux steps ride along through [TermuxPlanSelection] inside
 * [Planner.planFor]: when its gate is open (setting on, Termux
 * installed + permission on, last real probe says the tool is
 * there) the plan can propose Termux work; when the gate is closed
 * it returns nothing and the plan is unchanged.
 */
fun planForCommand(text: String): List<PlanStep> {
    val target = IntelTarget(kind = "MANUAL", title = text.take(80), text = text)
    return Planner.planFor(target, CapabilityRegistry)
        .filter { it.capabilityId != "sys.manual_step" }
}
