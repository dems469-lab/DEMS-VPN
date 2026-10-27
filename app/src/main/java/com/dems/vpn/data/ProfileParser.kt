package com.dems.vpn.data

import android.util.Base64
import org.json.JSONObject
import java.net.URLDecoder
import java.util.UUID

/**
 * Transforme les liens de partage (vless://, vmess://, trojan://, ss://)
 * en [Profile]. Aucun accès réseau : uniquement du parsing.
 */
object ProfileParser {

    private data class Link(
        val userInfo: String,
        val host: String,
        val port: Int,
        val query: Map<String, String>,
        val name: String
    )

    fun parse(rawInput: String): Profile? {
        val raw = rawInput.trim()
        if (raw.isEmpty()) return null
        return runCatching {
            when {
                raw.startsWith("vless://", ignoreCase = true) -> parseVless(raw)
                raw.startsWith("vmess://", ignoreCase = true) -> parseVmess(raw)
                raw.startsWith("trojan://", ignoreCase = true) -> parseTrojan(raw)
                raw.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(raw)
                else -> null
            }
        }.getOrNull()
    }

    // ---------------------------------------------------------------- VLESS

    private fun parseVless(raw: String): Profile? {
        val link = parseLink(raw) ?: return null
        if (link.userInfo.isBlank()) return null
        val q = link.query
        return Profile(
            id = UUID.randomUUID().toString(),
            name = link.name.ifBlank { "${link.host}:${link.port}" },
            protocol = Protocol.VLESS,
            server = link.host,
            port = link.port,
            uuid = link.userInfo,
            encryption = q["encryption"]?.ifBlank { "none" } ?: "none",
            flow = q["flow"].orEmpty(),
            network = q["type"]?.ifBlank { "tcp" } ?: "tcp",
            securityLayer = q["security"]?.ifBlank { "none" } ?: "none",
            sni = q["sni"] ?: q["peer"].orEmpty(),
            alpn = splitList(q["alpn"]),
            fingerprint = q["fp"].orEmpty(),
            allowInsecure = q["allowInsecure"] == "1" || q["insecure"] == "1",
            publicKey = q["pbk"].orEmpty(),
            shortId = q["sid"].orEmpty(),
            spiderX = q["spx"].orEmpty(),
            path = q["path"].orEmpty(),
            host = q["host"].orEmpty(),
            serviceName = q["serviceName"].orEmpty(),
            headerType = q["headerType"].orEmpty(),
            rawUri = raw
        )
    }

    // ---------------------------------------------------------------- VMess

    private fun parseVmess(raw: String): Profile? {
        val payload = raw.substringAfter("://").substringBefore('#')
        val jsonText = decodeBase64(payload) ?: return null
        val json = JSONObject(jsonText)
        val host = json.optString("add")
        if (host.isBlank()) return null
        val port = json.optString("port").toIntOrNull() ?: json.optInt("port", 443)
        if (port !in 1..65535) return null
        val tls = json.optString("tls")
        return Profile(
            id = UUID.randomUUID().toString(),
            name = json.optString("ps").ifBlank { "$host:$port" },
            protocol = Protocol.VMESS,
            server = host,
            port = port,
            uuid = json.optString("id"),
            alterId = json.optString("aid").toIntOrNull() ?: 0,
            vmessSecurity = json.optString("scy", "auto").ifBlank { "auto" },
            network = json.optString("net", "tcp").ifBlank { "tcp" },
            securityLayer = if (tls.equals("tls", ignoreCase = true)) "tls" else "none",
            sni = json.optString("sni"),
            alpn = splitList(json.optString("alpn")),
            fingerprint = json.optString("fp"),
            path = json.optString("path"),
            host = json.optString("host"),
            headerType = json.optString("type"),
            rawUri = raw
        )
    }

    // --------------------------------------------------------------- TROJAN

    private fun parseTrojan(raw: String): Profile? {
        val link = parseLink(raw) ?: return null
        if (link.userInfo.isBlank()) return null
        val q = link.query
        return Profile(
            id = UUID.randomUUID().toString(),
            name = link.name.ifBlank { "${link.host}:${link.port}" },
            protocol = Protocol.TROJAN,
            server = link.host,
            port = link.port,
            password = link.userInfo,
            network = q["type"]?.ifBlank { "tcp" } ?: "tcp",
            securityLayer = q["security"]?.ifBlank { "tls" } ?: "tls",
            sni = q["sni"] ?: q["peer"].orEmpty(),
            alpn = splitList(q["alpn"]),
            fingerprint = q["fp"].orEmpty(),
            allowInsecure = q["allowInsecure"] == "1" || q["insecure"] == "1",
            publicKey = q["pbk"].orEmpty(),
            shortId = q["sid"].orEmpty(),
            spiderX = q["spx"].orEmpty(),
            path = q["path"].orEmpty(),
            host = q["host"].orEmpty(),
            serviceName = q["serviceName"].orEmpty(),
            headerType = q["headerType"].orEmpty(),
            rawUri = raw
        )
    }

    // ---------------------------------------------------------- SHADOWSOCKS

    private fun parseShadowsocks(raw: String): Profile? {
        var body = raw.substringAfter("://")
        var name = ""
        val hash = body.indexOf('#')
        if (hash >= 0) {
            name = decode(body.substring(hash + 1))
            body = body.substring(0, hash)
        }
        val question = body.indexOf('?')
        if (question >= 0) body = body.substring(0, question)

        val at = body.lastIndexOf('@')
        val method: String
        val password: String
        val hostPort: String
        if (at >= 0) {
            // SIP002 : ss://base64(method:password)@host:port — souvent sans padding
            val credentials = decodeBase64(body.substring(0, at)) ?: decode(body.substring(0, at))
            val parts = credentials.split(':', limit = 2)
            if (parts.size < 2) return null
            method = parts[0]
            password = parts[1]
            hostPort = body.substring(at + 1)
        } else {
            // Ancien format : ss://base64(method:password@host:port)
            val decoded = decodeBase64(body) ?: return null
            val lastAt = decoded.lastIndexOf('@')
            if (lastAt < 0) return null
            val parts = decoded.substring(0, lastAt).split(':', limit = 2)
            if (parts.size < 2) return null
            method = parts[0]
            password = parts[1]
            hostPort = decoded.substring(lastAt + 1)
        }

        val (host, port) = splitHostPort(hostPort) ?: return null
        if (method.isBlank()) return null
        return Profile(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "$host:$port" },
            protocol = Protocol.SHADOWSOCKS,
            server = host,
            port = port,
            method = decode(method),
            password = decode(password),
            network = "tcp",
            securityLayer = "none",
            rawUri = raw
        )
    }

    // --------------------------------------------------------------- Outils

    private fun parseLink(raw: String): Link? {
        var rest = raw.substringAfter("://")
        var name = ""
        val hash = rest.indexOf('#')
        if (hash >= 0) {
            name = decode(rest.substring(hash + 1))
            rest = rest.substring(0, hash)
        }
        var rawQuery = ""
        val question = rest.indexOf('?')
        if (question >= 0) {
            rawQuery = rest.substring(question + 1)
            rest = rest.substring(0, question)
        }
        val at = rest.lastIndexOf('@')
        if (at < 0) return null
        val userInfo = decode(rest.substring(0, at))
        var hostPort = rest.substring(at + 1)
        val slash = hostPort.indexOf('/')
        if (slash >= 0) hostPort = hostPort.substring(0, slash)
        val (host, port) = splitHostPort(hostPort) ?: return null
        return Link(userInfo, host, port, parseQuery(rawQuery), name)
    }

    private fun splitHostPort(value: String): Pair<String, Int>? {
        val hostPort = value.substringBefore('/')
        if (hostPort.isBlank()) return null
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            if (end < 0) return null
            val host = hostPort.substring(1, end)
            val port = hostPort.substring(end + 1).removePrefix(":").toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return host to port
        }
        val colon = hostPort.lastIndexOf(':')
        if (colon <= 0) return null
        val host = hostPort.substring(0, colon)
        val port = hostPort.substring(colon + 1).toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        return host to port
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&')
            .filter { it.isNotBlank() }
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index < 0) decode(part) to ""
                else decode(part.substring(0, index)) to decode(part.substring(index + 1))
            }
            .toMap()
    }

    private fun splitList(value: String?): List<String> =
        value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)

    private fun decodeBase64(value: String): String? {
        val cleaned = value.trim().replace('-', '+').replace('_', '/').substringBefore('?')
        if (cleaned.isEmpty()) return null
        val padded = when (cleaned.length % 4) {
            2 -> "$cleaned=="
            3 -> "$cleaned="
            else -> cleaned
        }
        return runCatching {
            String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }
}
