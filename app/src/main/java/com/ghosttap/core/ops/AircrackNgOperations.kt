package com.ghosttap.core.ops

import com.ghosttap.core.OpResult

/**
 * Aircrack-ng integration boundary for Ghost Tap.
 *
 * Ghost Tap does not install packages or fetch binaries from another
 * source. It uses the existing Termux execution bridge and reports
 * exactly what is already available on the phone. This first operation
 * is deliberately read-only: it checks the suite, root status, and
 * driver-reported monitor-mode support without changing Wi-Fi state,
 * capturing packets, injecting frames, or attempting key recovery.
 */
private const val AIRCRACK_PREFLIGHT_COMMAND = """
for tool in aircrack-ng airodump-ng aireplay-ng airmon-ng; do
  if command -v "$tool" >/dev/null 2>&1; then
    printf 'FOUND %s=%s\n' "$tool" "$(command -v "$tool")"
  else
    printf 'MISSING %s\n' "$tool"
  fi
done
printf 'ROOT_UID='
id -u 2>/dev/null || printf 'UNKNOWN\n'
if command -v iw >/dev/null 2>&1; then
  if iw list 2>/dev/null | grep -A12 -F 'Supported interface modes' | grep -q 'monitor'; then
    printf 'MONITOR_MODE=YES\n'
  else
    printf 'MONITOR_MODE=NO_OR_UNAVAILABLE\n'
  fi
else
  printf 'MONITOR_MODE=UNKNOWN_IW_MISSING\n'
fi
""".trimIndent()

/** Register the Aircrack-ng readiness check as a normal Ghost Tap operation. */
fun registerAircrackNgOperations() {
    OperationRegistry.register(
        Operation(
            id = "wifi.aircrack-preflight",
            title = "Check Aircrack-ng readiness",
            description = "Checks whether the Aircrack-ng tools are already available through Ghost Tap's Termux execution bridge, and whether root access and monitor-mode support are reported. Read-only: it does not install tools, change Wi-Fi settings, capture traffic, inject packets, or attempt password recovery.",
            area = OperationArea.TERMUX,
            preparation = listOf(
                PreparationStep("Checking the local command runner") { context ->
                    val termux = context.termux
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "The local Termux command runner isn't available right now."
                        )
                    val check = termux.checkInstalledNow()
                    if (check.ok) PreparationOutcome.Ready
                    else PreparationOutcome.Failed(check.summary)
                }
            ),
            execute = { context ->
                val termux = context.termux
                    ?: return@Operation OpResult(
                        false,
                        "The local Termux command runner isn't available right now."
                    )
                val result = termux.runFreeCommandAndWait(
                    AIRCRACK_PREFLIGHT_COMMAND,
                    20_000L
                )
                if (!result.ok) {
                    result
                } else {
                    val output = result.data
                    val lines = output.lineSequence().toList()
                    val found = lines
                        .filter { it.startsWith("FOUND ") }
                        .map { it.removePrefix("FOUND ").substringBefore("=") }
                    val missing = lines
                        .filter { it.startsWith("MISSING ") }
                        .map { it.removePrefix("MISSING ") }
                    val rootUid = lines
                        .firstOrNull { it.startsWith("ROOT_UID=") }
                        ?.substringAfter("=")
                        ?.trim()
                    val monitorMode = lines
                        .firstOrNull { it.startsWith("MONITOR_MODE=") }
                        ?.substringAfter("=")
                        ?.trim()

                    val summary = when {
                        found.isEmpty() ->
                            "Aircrack-ng isn't available in Termux yet. Ghost Tap did not install or download anything."
                        missing.isNotEmpty() ->
                            "Aircrack-ng is only partly available. Missing tools: " + missing.joinToString(", ") + "."
                        rootUid != "0" ->
                            "All four Aircrack-ng tools were found, but root access isn't available. Monitor-mode capture may be blocked on this phone."
                        monitorMode == "YES" ->
                            "All four Aircrack-ng tools, root access, and driver-reported monitor-mode support were detected. This was a readiness check only; no traffic was captured or changed."
                        else ->
                            "All four Aircrack-ng tools and root access were detected, but monitor-mode support was not confirmed by the Wi-Fi driver."
                    }
                    OpResult(true, summary, output)
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )
}
