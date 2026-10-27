package com.dems.vpn.data

import org.json.JSONArray
import org.json.JSONObject

enum class Protocol { VLESS, VMESS, TROJAN, SHADOWSOCKS }

/**
 * Un serveur tel qu'il est stocké localement. Les champs sont volontairement
 * « à plat » pour rester simples à sérialiser et à faire évoluer.
 */
data class Profile(
    val id: String,
    val name: String,
    val protocol: Protocol,
    val server: String,
    val port: Int,
    // VLESS / VMess
    val uuid: String = "",
    // Trojan / Shadowsocks
    val password: String = "",
    // Shadowsocks
    val method: String = "",
    // VMess
    val alterId: Int = 0,
    val vmessSecurity: String = "auto",
    // VLESS
    val encryption: String = "none",
    val flow: String = "",
    // Transport
    val network: String = "tcp",
    val securityLayer: String = "none",
    val sni: String = "",
    val alpn: List<String> = emptyList(),
    val fingerprint: String = "",
    val allowInsecure: Boolean = false,
    val publicKey: String = "",
    val shortId: String = "",
    val spiderX: String = "",
    val path: String = "",
    val host: String = "",
    val serviceName: String = "",
    val headerType: String = "",
    val rawUri: String = ""
) {
    val endpoint: String
        get() = if (server.contains(':')) "[$server]:$port" else "$server:$port"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("protocol", protocol.name)
        put("server", server)
        put("port", port)
        put("uuid", uuid)
        put("password", password)
        put("method", method)
        put("alterId", alterId)
        put("vmessSecurity", vmessSecurity)
        put("encryption", encryption)
        put("flow", flow)
        put("network", network)
        put("securityLayer", securityLayer)
        put("sni", sni)
        put("alpn", JSONArray(alpn))
        put("fingerprint", fingerprint)
        put("allowInsecure", allowInsecure)
        put("publicKey", publicKey)
        put("shortId", shortId)
        put("spiderX", spiderX)
        put("path", path)
        put("host", host)
        put("serviceName", serviceName)
        put("headerType", headerType)
        put("rawUri", rawUri)
    }

    companion object {
        fun fromJson(json: JSONObject): Profile {
            val alpnList = mutableListOf<String>()
            val alpnArray = json.optJSONArray("alpn")
            if (alpnArray != null) {
                for (i in 0 until alpnArray.length()) {
                    val item = alpnArray.optString(i)
                    if (item.isNotBlank()) alpnList += item
                }
            }
            return Profile(
                id = json.optString("id"),
                name = json.optString("name"),
                protocol = runCatching { Protocol.valueOf(json.optString("protocol")) }
                    .getOrDefault(Protocol.VLESS),
                server = json.optString("server"),
                port = json.optInt("port", 443),
                uuid = json.optString("uuid"),
                password = json.optString("password"),
                method = json.optString("method"),
                alterId = json.optInt("alterId", 0),
                vmessSecurity = json.optString("vmessSecurity", "auto"),
                encryption = json.optString("encryption", "none"),
                flow = json.optString("flow"),
                network = json.optString("network", "tcp"),
                securityLayer = json.optString("securityLayer", "none"),
                sni = json.optString("sni"),
                alpn = alpnList,
                fingerprint = json.optString("fingerprint"),
                allowInsecure = json.optBoolean("allowInsecure", false),
                publicKey = json.optString("publicKey"),
                shortId = json.optString("shortId"),
                spiderX = json.optString("spiderX"),
                path = json.optString("path"),
                host = json.optString("host"),
                serviceName = json.optString("serviceName"),
                headerType = json.optString("headerType"),
                rawUri = json.optString("rawUri")
            )
        }
    }
}

fun Protocol.shortLabel(): String = when (this) {
    Protocol.VLESS -> "VLESS"
    Protocol.VMESS -> "VMess"
    Protocol.TROJAN -> "TROJAN"
    Protocol.SHADOWSOCKS -> "SS"
}
