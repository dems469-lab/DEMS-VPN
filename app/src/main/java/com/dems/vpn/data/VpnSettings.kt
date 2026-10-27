package com.dems.vpn.data

/**
 * Tous les réglages de l'application. Les valeurs par défaut doivent rester
 * sûres : elles sont utilisées avant le premier enregistrement.
 */
data class VpnSettings(
    // --- Réseau ---
    val dns: String = "1.1.1.1",
    val fakeDns: Boolean = false,
    val mtu: Int = 1500,
    val ipv6: Boolean = false,
    val blockQuic: Boolean = false,

    // --- Routage ---
    val sniffing: Boolean = true,
    val routeOnly: Boolean = false,
    val bypassApps: Set<String> = emptySet(),
    val splitInclude: Boolean = false,
    val allowLan: Boolean = false,

    // --- Anti-censure ---
    val finalMask: Boolean = false,

    // --- Stabilité ---
    val autoReconnect: Boolean = true,
    val wakeLock: Boolean = true,
    val tcpBufferKb: Int = 64,
    val udpBufferKb: Int = 512,

    // --- Ports locaux ---
    val socksPort: Int = 10808,
    val httpPort: Int = 10809,
    val socksUdp: Boolean = true,

    // --- Sécurité ---
    val logLevel: String = "warning",
    val tlsMinVersion: String = "auto",
    val appLockPin: String = "",

    // --- Diagnostic ---
    val testUrl: String = "https://www.gstatic.com/generate_204",

    // --- Moteur de décision ---
    val autoBench: Boolean = false
) {

    /** DNS résolu par le cœur (DoH / TCP) au lieu d'une adresse IP nue. */
    val dnsIsRemote: Boolean
        get() = dns.trim().let { value ->
            value.startsWith("https://", ignoreCase = true) ||
                value.startsWith("h2c://", ignoreCase = true) ||
                value.startsWith("tcp://", ignoreCase = true)
        }

    val effectiveTestUrl: String
        get() = testUrl.trim().ifBlank { "https://www.gstatic.com/generate_204" }
}

private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

private fun isIpLiteral(value: String): Boolean {
    val ipv4 = IPV4_LITERAL.matches(value) &&
        value.split('.').all { (it.toIntOrNull() ?: -1) in 0..255 }
    val ipv6 = value.count { it == ':' } >= 2 &&
        value.all { it.isDigit() || it == ':' || it == '.' || it.lowercaseChar() in 'a'..'f' }
    return ipv4 || ipv6
}

/**
 * `VpnService.Builder.addDnsServer` n'accepte qu'une adresse IP numérique.
 * Pour un DNS chiffré, on donne aux applications une IP publique : leurs
 * requêtes port 53 sont de toute façon interceptées par Xray, qui utilise
 * ensuite le resolver DoH/DoT configuré.
 */
fun VpnSettings.numericDns(): String {
    val value = dns.trim()
    return if (isIpLiteral(value)) value else "1.1.1.1"
}

/**
 * Xray ne gère que `https://` (DoH), `h2c://` et `tcp://` pour un résolveur
 * distant ; il n'existe pas de DoT (`tls://`) ni de DoQ distant. On évite donc
 * une configuration silencieusement inutilisable : un `tls://1.1.1.1` est
 * ramené à l'IP nue, un hôte non numérique retombe sur Cloudflare.
 */
fun VpnSettings.xrayDns(): String {
    val value = dns.trim().ifBlank { "1.1.1.1" }
    val supported = value.startsWith("https://", ignoreCase = true) ||
        value.startsWith("h2c://", ignoreCase = true) ||
        value.startsWith("tcp://", ignoreCase = true)
    if (supported) return value
    if (!value.contains("://")) return value

    val host = value.substringAfter("://")
        .substringBefore('/')
        .substringBefore('@')
        .substringBefore('?')
    return if (isIpLiteral(host)) host else "1.1.1.1"
}

/** Adresse d'écoute du proxy local selon le partage LAN. */
fun VpnSettings.listenAddress(): String = if (allowLan) "0.0.0.0" else "127.0.0.1"

val DNS_PRESETS = listOf(
    "1.1.1.1",
    "8.8.8.8",
    "94.140.14.14",
    "https://cloudflare-dns.com/dns-query"
)

val MTU_PRESETS = listOf(1500, 1400, 1300, 1280, 1700)

val TLS_VERSIONS = listOf("auto", "1.2", "1.3")

val LOG_LEVELS = listOf("warning", "info", "debug")

val TCP_BUFFER_PRESETS = listOf(32, 64, 128, 256)

/** Transports basés sur TCP : seuls ils peuvent subir la fragmentation TCP. */
val TCP_BASED_NETWORKS = listOf("tcp", "ws", "grpc", "h2", "http", "httpupgrade", "xhttp")

val UDP_BUFFER_PRESETS = listOf(256, 512, 1024, 2048)
