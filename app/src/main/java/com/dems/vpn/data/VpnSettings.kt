package com.dems.vpn.data

data class VpnSettings(
    val dns: String = "1.1.1.1",
    val mtu: Int = 1500,
    val ipv6: Boolean = false,
    val blockQuic: Boolean = false
)

private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

/**
 * `VpnService.Builder.addDnsServer` n'accepte qu'une adresse IP numérique.
 * Si l'utilisateur a saisi autre chose (nom d'hôte, faute de frappe…),
 * on retombe sur un résolveur public au lieu de faire planter le service.
 */
fun VpnSettings.numericDns(): String {
    val value = dns.trim()
    val ipv4 = IPV4_LITERAL.matches(value) &&
        value.split('.').all { (it.toIntOrNull() ?: -1) in 0..255 }
    val ipv6 = value.count { it == ':' } >= 2 &&
        value.all { it.isDigit() || it == ':' || it == '.' || it.lowercaseChar() in 'a'..'f' }
    return if (ipv4 || ipv6) value else "1.1.1.1"
}
