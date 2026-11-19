package com.ghosttap.core.engines

import com.ghosttap.TermuxViewModel
import com.ghosttap.core.OpResult

/**
 * Termux as a registered peer engine.
 *
 * Thin wrapper only: every answer comes from the TermuxViewModel,
 * which already owns the client, the bridge and the capability list.
 * Nothing here re-implements a Termux call, and nothing here touches
 * NFC, networking, hotspot, VirtualESP32 or USB.
 *
 * - isInstalled reads the last checked state (false until Ghost Tap
 *   has looked). healthCheck is the live answer — it starts the
 *   bridge if needed and pings it for real.
 * - capabilityTitles lists what Termux can do, from the last real
 *   probe, with the user's off-switches respected.
 */
class TermuxEngine(private val viewModel: TermuxViewModel) : Engine {

    override val descriptor = EngineDescriptor(
        id = "termux",
        name = "Termux",
        detail = "A separate app that runs Linux tools on this phone — Python, network checks, file tools, and more.",
        version = "v2"
    )

    override fun isInstalled(): Boolean = viewModel.termuxInstalled

    override suspend fun healthCheck(): OpResult = viewModel.bridgeHealthCheck()

    override fun capabilityTitles(): List<String> =
        viewModel.capabilities.filter { it.enabled }.map { it.title }
}
