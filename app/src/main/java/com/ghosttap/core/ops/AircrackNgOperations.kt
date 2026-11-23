package com.ghosttap.core.ops

import com.ghosttap.core.OpResult

/**
 * Read-only Aircrack-ng readiness check through Ghost Tap's existing Termux bridge.
 * It never installs tools, changes Wi-Fi state, captures traffic, injects frames, or
 * attempts password recovery.
 */
private val AIRCRACK_PREFLIGHT_COMMAND = """
for tool in aircrack-ng airodump-ng aireplay-ng airmon-ng; do
  case "$tool" in
    aircrack-ng) if command -v aircrack-ng >/dev/null 2>&1; then printf 'FOUND aircrack-ng=%s\\n' "$(command -v aircrack-ng)"; else printf 'MISSING aircrack-ng\\n'; fi ;;
    airodump-ng) if command -v airodump-ng >/dev/null 2>&1; then printf 'FOUND airodump-ng=%s\\n' "$(command -v airodump-ng)"; else printf 'MISSING airodump-ng\\n'; fi ;;
    aireplay-ng) if command -v aireplay-ng >/dev/null 2>&1; then printf 'FOUND aireplay-ng=%s\\n' "$(command -v aireplay-ng)"; else printf 'MISSING aireplay-ng\\n'; fi ;;
    airmon-ng) if command -v airmon-ng >/dev/null 2>&1; then printf 'FOUND airmon-ng=%s\\n' "$(command -v airmon-ng)"; else printf 'MISSING airmon-ng\\n'; fi ;;
  esac
done
printf 'ROOT_UID='
id -u 2>/dev/null || printf 'UNKNOWN\\n'
if command -v iw >/dev/null 2>&1; then
  if iw list 2>/dev/null | grep -A12 -F 'Supported interface modes' | grep -q 'monitor'; then
    printf 'MONITOR_MODE=YES\\n'
  else
    printf 'MONITOR_MODE=NO_OR_UNAVAILABLE\\n'
  fi
else
  printf 'MONITOR_MODE=UNKNOWN_IW_MISSING\\n'
fi
""".trimIndent()

fun registerAircrackNgOperations() {
    OperationRegistry.register(
        Operation(
            id = "wifi.aircrack-preflight",
            title = "Check Aircrack-ng readiness",
            description = "Checks whether Aircrack-ng tools are already available through Ghost Tap's Termux bridge and reports root and monitor-mode support. Read-only; no installation, Wi-Fi changes, packet capture, injection, or password recovery.",
            area = OperationArea.TERMUX,
            preparation = listOf(
                PreparationStep("Checking the local command runner") { context ->
                    val termux = context.termux
                        ?: return@PreparationStep PreparationOutcome.Failed("The local Termux command runner isn't available right now.")
                    val check = termux.checkInstalledNow()
                    if (check.ok) PreparationOutcome.Ready else PreparationOutcome.Failed(check.summary)
                }
            ),
            execute = { context ->
                val termux = context.termux
                    ?: return@Operation OpResult(false, "The local Termux command runner isn't available right now.")
                val result = termux.runFreeCommandAndWait(AIRCRACK_PREFLIGHT_COMMAND, 20_000L)
                if (!result.ok) result else {
                    val output = result.data
                    val lines = output.lineSequence().toList()
                    val found = lines.filter { it.startsWith("FOUND ") }.map { it.removePrefix("FOUND ").substringBefore("=") }
                    val missing = lines.filter { it.startsWith("MISSING ") }.map { it.removePrefix("MISSING ") }
                    val rootUid = lines.firstOrNull { it.startsWith("ROOT_UID=") }?.substringAfter("=")?.trim()
                    val monitorMode = lines.firstOrNull { it.startsWith("MONITOR_MODE=") }?.substringAfter("=")?.trim()
                    val summary = when {
                        found.isEmpty() -> "Aircrack-ng is not available through Termux. Ghost Tap did not install or download anything."
                        missing.isNotEmpty() -> "Aircrack-ng is only partly available. Missing tools: ${missing.joinToString(", ")}."
                        rootUid != "0" -> "All four tools were found, but root access is unavailable; monitor-mode capture may be blocked on this phone."
                        monitorMode == "YES" -> "All four tools, root access, and driver-reported monitor-mode support were detected. This was only a readiness check."
                        else -> "All four tools and root access were detected, but monitor-mode support was not confirmed by the Wi-Fi driver."
                    }
                    OpResult(true, summary, output)
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )
}