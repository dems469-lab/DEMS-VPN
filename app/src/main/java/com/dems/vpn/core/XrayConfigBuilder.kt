package com.dems.vpn.core

import com.dems.vpn.data.Profile
import com.dems.vpn.data.Protocol
import com.dems.vpn.data.VpnSettings
import com.dems.vpn.data.TCP_BASED_NETWORKS
import com.dems.vpn.data.listenAddress
import com.dems.vpn.data.xrayDns
import org.json.JSONArray
import org.json.JSONObject

/**
 * Construit la configuration Xray complète :
 *
 *   [apps] ──► interface TUN ──► tun2socks ──► SOCKS/HTTP 127.0.0.1 ──► Xray ──► serveur
 *
 * - `tun2socks` (hev) lit les paquets de l'interface VPN et les transforme en SOCKS5.
 * - Xray écoute sur la boucle locale (ou sur le LAN si le partage est activé),
 *   renifle les domaines et applique le routage.
 * - Les requêtes DNS passent par le module DNS de Xray (donc dans le tunnel).
 */
object XrayConfigBuilder {

    private const val DNS_IPV6 = "2606:4700:4700::1111"

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
        httpPort: Int,
        socksUser: String,
        socksPass: String
    ): String {
        val root = JSONObject()

        root.put("log", JSONObject().put("loglevel", settings.logLevel))

        // Compteurs de trafic : sans le bloc `stats`, le gestionnaire de
        // statistiques du cœur n'existe pas et `queryAllOutboundTrafficStats`
        // ne renvoie rien (voire fait échouer l'assertion côté Go).
        root.put("stats", JSONObject())
        root.put(
            "policy",
            JSONObject().put(
                "system",
                JSONObject()
                    .put("statsOutboundUplink", true)
                    .put("statsOutboundDownlink", true)
            )
        )

        val dnsServers = JSONArray()
        if (settings.fakeDns) dnsServers.put("fakedns")
        dnsServers.put(settings.xrayDns())
        if (settings.ipv6 && !settings.dnsIsRemote) dnsServers.put(DNS_IPV6)

        root.put(
            "dns",
            JSONObject()
                .put("servers", dnsServers)
                .put("queryStrategy", "UseIP")
        )

        // FakeDNS : renvoie des IP fictives (198.18.0.0/15) pour les domaines,
        // ce qui évite une résolution lente avant le tunnel.
        if (settings.fakeDns) {
            root.put(
                "fakedns",
                JSONArray().put(
                    JSONObject()
                        .put("ipPool", "198.18.0.0/15")
                        .put("poolSize", 10000)
                )
            )
        }

        val listen = settings.listenAddress()
        val sniffing = buildSniffing(settings)

        val socks = JSONObject()
            .put("tag", "socks-in")
            .put("listen", listen)
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
                    .put("udp", settings.socksUdp)
            )
            .put("sniffing", sniffing)

        val http = JSONObject()
            .put("tag", "http-in")
            .put("listen", listen)
            .put("port", httpPort)
            .put("protocol", "http")
            .put("settings", JSONObject().put("timeout", 300))
            .put("sniffing", buildSniffing(settings))

        root.put("inbounds", JSONArray().put(socks).put(http))

        val outbounds = JSONArray()
        outbounds.put(buildOutbound(profile, settings))
        outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        outbounds.put(JSONObject().put("tag", "dns-out").put("protocol", "dns"))
        outbounds.put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        root.put("outbounds", outbounds)

        val rules = JSONArray()
        // DNS de l'interface VPN -> module DNS de Xray (résolution dans le tunnel).
        rules.put(
            fieldRule()
                .put("inboundTag", JSONArray().put("socks-in").put("http-in"))
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
        // IPv6 désactivé : tout ce qui n'est pas un réseau local v6 est bloqué,
        // y compris si une application contourne le DNS de l'interface.
        if (!settings.ipv6) {
            rules.put(
                fieldRule()
                    .put("ip", JSONArray().put("::/0"))
                    .put("outboundTag", "block")
            )
        }
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

    private fun buildSniffing(settings: VpnSettings): JSONObject {
        val enabled = settings.sniffing || settings.fakeDns
        val destOverride = JSONArray()
        if (enabled) {
            destOverride.put("http").put("tls").put("quic")
            if (settings.fakeDns) destOverride.put("fakedns")
        }
        return JSONObject()
            .put("enabled", enabled)
            .put("destOverride", destOverride)
            .put("routeOnly", settings.routeOnly)
    }

    private fun fieldRule(): JSONObject = JSONObject().put("type", "field")

    private fun buildOutbound(profile: Profile, settings: VpnSettings): JSONObject {
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
            outbound.put("streamSettings", buildStreamSettings(profile, settings))
        }
        return outbound
    }

    /**
     * Anti-censure : fragmentation du handshake TLS et bruit aléatoire sur
     * l'UDP (`finalmask` du cœur Xray). Rend le trafic peu identifiable par un
     * deep packet inspection.
     */
    private fun applyFinalMask(stream: JSONObject, network: String, security: String, settings: VpnSettings) {
        if (!settings.finalMask) return
        if (network !in TCP_BASED_NETWORKS) return

        // REALITY n'est pas un handshake TLS classique : le fragment doit porter sur 1-3 paquets.
        val packets = if (security.equals("reality", ignoreCase = true)) "1-3" else "tlshello"

        val tcpMask = JSONObject()
            .put("type", "fragment")
            .put(
                "settings",
                JSONObject()
                    .put("packets", packets)
                    .put("length", "50-100")
                    .put("delay", "10-20")
                    .put("maxSplit", "10")
            )

        val udpMask = JSONObject()
            .put("type", "noise")
            .put(
                "settings",
                JSONObject().put(
                    "noise",
                    JSONArray().put(
                        JSONObject()
                            .put("rand", "10-20")
                            .put("delay", "10-16")
                    )
                )
            )

        stream.put(
            "finalmask",
            JSONObject()
                .put("tcp", JSONArray().put(tcpMask))
                .put("udp", JSONArray().put(udpMask))
        )
    }

    private fun buildStreamSettings(profile: Profile, settings: VpnSettings): JSONObject {
        val stream = JSONObject()
        val network = profile.network.ifBlank { "tcp" }
        val security = profile.securityLayer.ifBlank { "none" }
        stream.put("network", network)
        stream.put("security", security)

        applyFinalMask(stream, network, security, settings)

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
                    if (settings.tlsMinVersion != "auto") put("minVersion", settings.tlsMinVersion)
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
