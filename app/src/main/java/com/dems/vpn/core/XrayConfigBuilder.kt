package com.dems.vpn.core

import com.dems.vpn.data.Profile
import com.dems.vpn.data.Protocol
import com.dems.vpn.data.VpnSettings
import com.dems.vpn.data.numericDns
import org.json.JSONArray
import org.json.JSONObject

/**
 * Construit la configuration Xray complète :
 *
 *   [apps] ──► interface TUN ──► tun2socks ──► SOCKS 127.0.0.1:10808 ──► Xray ──► serveur
 *
 * - `tun2socks` (hev) lit les paquets de l'interface VPN et les transforme en SOCKS5.
 * - Xray écoute sur la boucle locale, renifle les domaines et applique le routage.
 * - Les requêtes DNS passent par le module DNS de Xray (donc dans le tunnel).
 */
object XrayConfigBuilder {

    private val PRIVATE_IPS = listOf(
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "100.64.0.0/10",
        "::1/128",
        "fc00::/7",
        "fe80::/10"
    )

    fun build(
        profile: Profile,
        settings: VpnSettings,
        socksPort: Int,
        socksUser: String,
        socksPass: String
    ): String {
        val root = JSONObject()

        root.put("log", JSONObject().put("loglevel", "warning"))

        val dnsServers = JSONArray().put(settings.numericDns())
        if (settings.ipv6) dnsServers.put("2606:4700:4700::1111")
        root.put(
            "dns",
            JSONObject()
                .put("servers", dnsServers)
                .put("queryStrategy", "UseIP")
        )

        val inbound = JSONObject()
            .put("tag", "socks-in")
            .put("listen", "127.0.0.1")
            .put("port", socksPort)
            .put("protocol", "socks")
            .put(
                "settings",
                JSONObject()
                    .put("auth", "password")
                    .put(
                        "accounts",
                        JSONArray().put(
                            JSONObject().put("user", socksUser).put("pass", socksPass)
                        )
                    )
                    .put("udp", true)
            )
            .put(
                "sniffing",
                JSONObject()
                    .put("enabled", true)
                    .put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                    .put("routeOnly", false)
            )
        root.put("inbounds", JSONArray().put(inbound))

        val outbounds = JSONArray()
        outbounds.put(buildOutbound(profile))
        outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        outbounds.put(JSONObject().put("tag", "dns-out").put("protocol", "dns"))
        outbounds.put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        root.put("outbounds", outbounds)

        val rules = JSONArray()
        // DNS de l'interface VPN -> module DNS de Xray (résolution dans le tunnel).
        rules.put(
            fieldRule()
                .put("inboundTag", JSONArray().put("socks-in"))
                .put("port", "53")
                .put("outboundTag", "dns-out")
        )
        // Option : forcer HTTP/3 à repasser en TCP.
        if (settings.blockQuic) {
            rules.put(
                fieldRule()
                    .put("network", "udp")
                    .put("port", "443")
                    .put("outboundTag", "block")
            )
        }
        // Réseaux locaux en direct (imprimantes, NAS, box…).
        rules.put(fieldRule().put("ip", JSONArray(PRIVATE_IPS)).put("outboundTag", "direct"))
        // Tout le reste dans le tunnel.
        rules.put(fieldRule().put("network", "tcp,udp").put("outboundTag", "proxy"))

        root.put(
            "routing",
            JSONObject()
                .put("domainStrategy", "AsIs")
                .put("rules", rules)
        )

        return root.toString(2)
    }

    private fun fieldRule(): JSONObject = JSONObject().put("type", "field")

    private fun buildOutbound(profile: Profile): JSONObject {
        val outbound = JSONObject()
        when (profile.protocol) {
            Protocol.VLESS -> {
                outbound.put("protocol", "vless")
                val user = JSONObject()
                    .put("id", profile.uuid)
                    .put("encryption", profile.encryption.ifBlank { "none" })
                    .put("level", 0)
                if (profile.flow.isNotBlank()) user.put("flow", profile.flow)
                outbound.put(
                    "settings",
                    JSONObject().put(
                        "vnext",
                        JSONArray().put(
                            JSONObject()
                                .put("address", profile.server)
                                .put("port", profile.port)
                                .put("users", JSONArray().put(user))
                        )
                    )
                )
            }

            Protocol.VMESS -> {
                outbound.put("protocol", "vmess")
                val user = JSONObject()
                    .put("id", profile.uuid)
                    .put("alterId", profile.alterId)
                    .put("security", profile.vmessSecurity.ifBlank { "auto" })
                    .put("level", 0)
                outbound.put(
                    "settings",
                    JSONObject().put(
                        "vnext",
                        JSONArray().put(
                            JSONObject()
                                .put("address", profile.server)
                                .put("port", profile.port)
                                .put("users", JSONArray().put(user))
                        )
                    )
                )
            }

            Protocol.TROJAN -> {
                outbound.put("protocol", "trojan")
                outbound.put(
                    "settings",
                    JSONObject().put(
                        "servers",
                        JSONArray().put(
                            JSONObject()
                                .put("address", profile.server)
                                .put("port", profile.port)
                                .put("password", profile.password)
                        )
                    )
                )
            }

            Protocol.SHADOWSOCKS -> {
                outbound.put("protocol", "shadowsocks")
                outbound.put(
                    "settings",
                    JSONObject().put(
                        "servers",
                        JSONArray().put(
                            JSONObject()
                                .put("address", profile.server)
                                .put("port", profile.port)
                                .put("method", profile.method)
                                .put("password", profile.password)
                        )
                    )
                )
            }
        }

        outbound.put("tag", "proxy")
        if (profile.protocol != Protocol.SHADOWSOCKS) {
            outbound.put("streamSettings", buildStreamSettings(profile))
        }
        return outbound
    }

    private fun buildStreamSettings(profile: Profile): JSONObject {
        val stream = JSONObject()
        val network = profile.network.ifBlank { "tcp" }
        val security = profile.securityLayer.ifBlank { "none" }
        stream.put("network", network)
        stream.put("security", security)

        when (security) {
            "tls" -> stream.put(
                "tlsSettings",
                JSONObject().apply {
                    put(
                        "serverName",
                        profile.sni.ifBlank {
                            profile.host.ifBlank { profile.server }
                        }
                    )
                    put("allowInsecure", profile.allowInsecure)
                    if (profile.alpn.isNotEmpty()) put("alpn", JSONArray(profile.alpn))
                    if (profile.fingerprint.isNotBlank()) put("fingerprint", profile.fingerprint)
                }
            )

            "reality" -> stream.put(
                "realitySettings",
                JSONObject().apply {
                    put("serverName", profile.sni)
                    put("fingerprint", profile.fingerprint.ifBlank { "chrome" })
                    put("publicKey", profile.publicKey)
                    put("shortId", profile.shortId)
                    if (profile.spiderX.isNotBlank()) put("spiderX", profile.spiderX)
                    put("show", false)
                }
            )
        }

        when (network) {
            "ws" -> stream.put(
                "wsSettings",
                JSONObject().apply {
                    put("path", profile.path.ifBlank { "/" })
                    if (profile.host.isNotBlank()) {
                        put("headers", JSONObject().put("Host", profile.host))
                    }
                }
            )

            "grpc" -> stream.put(
                "grpcSettings",
                JSONObject().apply {
                    put("serviceName", profile.serviceName)
                    put("multiMode", false)
                }
            )

            "httpupgrade" -> stream.put(
                "httpupgradeSettings",
                JSONObject().apply {
                    put("path", profile.path.ifBlank { "/" })
                    if (profile.host.isNotBlank()) put("host", profile.host)
                }
            )

            "xhttp" -> stream.put(
                "xhttpSettings",
                JSONObject().apply {
                    put("path", profile.path.ifBlank { "/" })
                    if (profile.host.isNotBlank()) put("host", profile.host)
                    put("mode", "auto")
                }
            )

            "h2", "http" -> stream.put(
                "httpSettings",
                JSONObject().apply {
                    put("path", profile.path.ifBlank { "/" })
                    if (profile.host.isNotBlank()) put("host", JSONArray().put(profile.host))
                }
            )

            "tcp" -> if (profile.headerType == "http") {
                stream.put(
                    "tcpSettings",
                    JSONObject().put(
                        "header",
                        JSONObject().apply {
                            put("type", "http")
                            put(
                                "request",
                                JSONObject().apply {
                                    put("path", JSONArray().put(profile.path.ifBlank { "/" }))
                                    if (profile.host.isNotBlank()) {
                                        put(
                                            "headers",
                                            JSONObject().put("Host", JSONArray().put(profile.host))
                                        )
                                    }
                                }
                            )
                        }
                    )
                )
            }

            "kcp" -> stream.put(
                "kcpSettings",
                JSONObject().apply {
                    put("mtu", 1350)
                    put("tti", 50)
                    if (profile.headerType.isNotBlank() && profile.headerType != "none") {
                        put("header", JSONObject().put("type", profile.headerType))
                    }
                }
            )
        }

        return stream
    }
}
