package com.ghosttap.core.vnic

data class VnicAdvertisedService(
    val name: String,
    val host: String,
    val port: Int,
    val type: String
)

data class VnicNetworkServiceProfile(
    val host: String,
    val port: Int,
    val protocol: String,
    val confidence: String,
    val evidence: String,
    val controls: List<String>
)

/**
 * Maps observed open TCP ports and actual mDNS/NSD advertisements to
 * protocol-aware, honest capability hints. Port-only classifications remain
 * explicitly unverified; this layer never sends a probe payload.
 */
object VnicNetworkProtocol {
    fun classify(
        host: String,
        openPorts: List<Int>,
        advertisedServices: List<VnicAdvertisedService>
    ): List<VnicNetworkServiceProfile> {
        val hostServices = advertisedServices.filter {
            it.host.equals(host, ignoreCase = true) && it.port in 1..65535
        }
        val ports = (openPorts.filter { it in 1..65535 } + hostServices.map { it.port })
            .distinct()
            .sorted()
        return ports.map { port ->
            val advertised = hostServices.firstOrNull { it.port == port }
            val protocol = protocolFor(advertised?.type.orEmpty(), port)
            val confidence = if (advertised != null) "advertised by mDNS/NSD" else "port-based hint; not protocol-verified"
            val evidence = if (advertised != null) {
                "${advertised.name} announced ${advertised.type} on TCP $port"
            } else {
                "TCP port $port answered the connection scan"
            }
            VnicNetworkServiceProfile(
                host = host,
                port = port,
                protocol = protocol,
                confidence = confidence,
                evidence = evidence,
                controls = controlsFor(protocol)
            )
        }
    }

    private fun protocolFor(serviceType: String, port: Int): String {
        val type = serviceType.lowercase()
        return when {
            "_https._tcp" in type -> "HTTPS"
            "_http._tcp" in type -> "HTTP"
            "_ipp._tcp" in type || "_printer._tcp" in type || port == 631 -> "IPP printer"
            "_mqtt._tcp" in type || port == 1883 || port == 8883 -> "MQTT broker"
            "_ssh._tcp" in type || port == 22 -> "SSH"
            "_smb._tcp" in type || port == 445 || port == 139 -> "SMB"
            "_ftp._tcp" in type || port == 21 -> "FTP"
            "_telnet._tcp" in type || port == 23 -> "Telnet"
            port == 80 || port == 8080 -> "HTTP candidate"
            port == 443 || port == 8443 -> "HTTPS candidate"
            port == 9100 -> "Raw printer"
            port == 502 -> "Modbus TCP"
            port == 554 -> "RTSP"
            port == 53 -> "DNS"
            port == 3389 -> "RDP"
            else -> "Unknown TCP service"
        }
    }

    private fun controlsFor(protocol: String): List<String> = when (protocol) {
        "HTTP", "HTTPS", "HTTP candidate", "HTTPS candidate" -> listOf(
            "HTTP page inspection: GET/read-only discovery",
            "Map advertised links and forms before offering device controls",
            "No POST, PUT, or other state-changing request is sent automatically"
        )
        "IPP printer" -> listOf(
            "Printer service advertised: inspect status and queue where supported",
            "Printing requires an explicit document and destination"
        )
        "Raw printer" -> listOf(
            "Raw TCP printer channel detected by port",
            "A print payload is required; no data is sent automatically"
        )
        "MQTT broker" -> listOf(
            "MQTT broker advertised or suggested by port",
            "Topic and any required credentials must be identified before subscribing or publishing",
            "No publish message is sent automatically"
        )
        "Modbus TCP" -> listOf(
            "Modbus TCP service suggested by port",
            "Register map and unit identifier are required for meaningful reads",
            "No register writes are sent automatically"
        )
        "SSH" -> listOf("SSH service advertised or suggested by port; credentials are required; no login attempted")
        "Telnet" -> listOf("Telnet service advertised or suggested by port; plaintext protocol; no login attempted")
        "FTP" -> listOf("FTP service advertised or suggested by port; credentials may be required")
        "RTSP" -> listOf("RTSP media service suggested by port; stream path and credentials may be required")
        "SMB" -> listOf("SMB file-sharing service advertised or suggested by port; credentials may be required")
        "DNS" -> listOf("DNS service suggested by port; query-only inspection is available")
        "RDP" -> listOf("Remote Desktop service suggested by port; credentials are required; no login attempted")
        else -> listOf(
            "TCP session is available, but the service protocol is not identified",
            "No payload is sent until a protocol or explicit message is known"
        )
    }
}
