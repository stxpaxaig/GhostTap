package com.ghosttap.core.vnic

import java.net.URI

/**
 * Converts the user's objective and corroborating research into only the
 * named arguments a registered Ghost Tap operation declares.
 *
 * This is deliberately evidence-led: host/port/device identifiers must
 * appear in the objective or findings, and outbound text is populated only
 * when the user explicitly supplies a payload. A natural-language command
 * is never blindly sent to a device as if it were payload.
 */
object VnicArgumentResolver {

    data class Resolution(
        val args: Map<String, String>,
        val unresolvedInputs: List<String>
    )

    private val ipv4 = Regex("""(?<![\\d.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\d.])""")
    private val mac = Regex("""(?i)(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}""")
    private val url = Regex("""(?i)https?://[^\\s"'<>]+""")
    private val host = Regex("""(?i)\\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+(?:local|lan|com|net|org|io|dev)\\b""")
    private val explicitPort = Regex("""(?i)\\bport\\s*[:=]?\\s*(\\d{1,5})\\b""")
    private val hostPort = Regex("""(?<![\\d.])(?:\\d{1,3}\\.){3}\\d{1,3}:(\\d{1,5})\\b""")
    private val gpio = Regex("""(?i)\\bGPIO\\s*([0-9]{1,2})\\b""")
    private val explicitId = Regex("""(?i)\\b(?:device\\s+)?id\\s*[:=]?\\s*([a-z0-9][a-z0-9._:-]{2,80})""")
    private val quotedPayload = Regex("""(?is)\\b(?:display|show|print|send|write|say|message|text)\\b[^\\n]{0,50}?["'](.+?)["']""")
    private val labeledPayload = Regex("""(?is)\\b(?:display|show|print|send|write|say)\\s+(?:the\\s+)?(?:message|text)\\s*[:=]\\s*(.+?)(?:\\s+(?:on|to|through)\\s+(?:the\\s+)?(?:device|sign|board|printer)|$)""")
    private val explicitCommand = Regex("""(?is)\\b(?:run|execute)\\s+(?:this\\s+)?command\\s*[:=]\\s*["']?(.+?)["']?$""")
    private val explicitCredential = Regex("""(?i)\\b(password|passphrase|ssid)\\s*[:=]\\s*([^\\s,;]+)""")

    fun resolve(
        capability: VnicCapabilityInfo?,
        observation: VnicObservation,
        findings: List<VnicFinding>
    ): Resolution {
        val evidence = buildString {
            append(observation.text).append(' ')
            findings.forEach { append(it.title).append(' ').append(it.snippet).append(' ').append(it.url).append(' ') }
        }
        val args = linkedMapOf("subject" to observation.subject, "text" to observation.text)
        val urlValue = url.find(evidence)?.value?.trimEnd('.', ',', ';', ')', ']')
        val hostValue = ipv4.find(evidence)?.value?.takeIf { isValidIpv4(it) }
            ?: urlValue?.let { runCatching { URI(it).host }.getOrNull() }
            ?: host.find(evidence)?.value
        val macValue = mac.find(evidence)?.value
        val portValue = explicitPort.find(evidence)?.groupValues?.getOrNull(1)
            ?: hostPort.find(evidence)?.groupValues?.getOrNull(1)
        val payload = quotedPayload.find(observation.text)?.groupValues?.getOrNull(1)?.trim()
            ?: labeledPayload.find(observation.text)?.groupValues?.getOrNull(1)?.trim()
        val command = explicitCommand.find(observation.text)?.groupValues?.getOrNull(1)?.trim()
        val gpioValue = gpio.find(evidence)?.groupValues?.getOrNull(1)?.let { "GPIO$it" }
        val stateMatch = Regex("""(?i)\\b(?:turn|switch|set|make)\\b.{0,50}\\b(on|off|high|low|true|false|1|0)\\b|\\bstate\\s*[:=]\\s*(on|off|high|low|true|false|1|0)\\b""")
            .find(observation.text)
        val stateValue = stateMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.lowercase()
        val credentials = explicitCredential.findAll(evidence).associate {
            it.groupValues[1].lowercase() to it.groupValues[2]
        }
        val explicitDeviceId = explicitId.find(evidence)?.groupValues?.getOrNull(1)

        capability?.inputs.orEmpty().forEach { input ->
            val value = when (input.lowercase()) {
                "host", "ip", "hostname" -> hostValue
                "url", "uri" -> urlValue
                "port" -> portValue
                "targetid", "deviceid", "address", "mac", "macaddress" ->
                    when (input.lowercase()) {
                        "mac", "macaddress" -> macValue
                        else -> macValue ?: explicitDeviceId ?: hostValue
                    }
                "pin", "gpio", "gpioid" -> gpioValue
                "state", "value", "enabled" -> stateValue
                "message", "payload" -> payload
                "text" -> if (capability.riskLevel == "WRITE" || capability.riskLevel == "CONTROL") payload else observation.text
                "command" -> command
                "ssid", "password", "passphrase" -> credentials[input.lowercase()]
                "name", "title" -> observation.subject.takeIf { it.isNotBlank() }
                "instruction", "query", "goal" -> observation.text.takeIf { it.isNotBlank() }
                else -> null
            }?.takeIf { it.isNotBlank() }
            if (value != null) args[input] = value
        }
        val unresolved = capability?.inputs.orEmpty().filter { args[it].isNullOrBlank() }
        return Resolution(args, unresolved)
    }

    private fun isValidIpv4(value: String): Boolean {
        val octets = value.split(".")
        return octets.size == 4 && octets.all { it.toIntOrNull() in 0..255 }
    }
}
