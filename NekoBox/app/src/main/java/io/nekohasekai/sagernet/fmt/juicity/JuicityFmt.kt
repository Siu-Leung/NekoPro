package io.nekohasekai.sagernet.fmt.juicity

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.redactLink
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.urlSafe
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.SingBoxOptions.Outbound_JuicityOptions
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString.Companion.toByteString

fun parseJuicity(url: String): JuicityBean {
    val link = url.replace("juicity://", "https://").toHttpUrlOrNull() ?: error(
        "invalid juicity link ${url.redactLink()}"
    )
    return JuicityBean().apply {
        name = link.fragment
        uuid = link.username
        password = link.password
        serverAddress = link.host
        serverPort = link.port

        link.queryParameter("sni")?.let {
            sni = it
        }
        link.queryParameter("pinned_certchain_sha256")?.let {
            normalizePinnedCertChainHash(it)?.let { hash ->
                pinnedCertchainSha256 = hash
            }
        }
        link.queryParameter("allow_insecure")?.let {
            if (it == "1" || it == "true") allowInsecure = true
        }
        link.queryParameter("congestion_control")?.takeIf { it.isNotBlank() }?.let {
            congestionControl = it
        }
    }
}

fun JuicityBean.toUri(): String {
    val builder = linkBuilder()
        .username(uuid ?: "")
        .password(password ?: "")
        .host(serverAddress ?: "")
        .port(serverPort ?: 443)

    if (!congestionControl.isNullOrBlank()) {
        builder.addQueryParameter("congestion_control", congestionControl)
    }
    if (!sni.isNullOrBlank()) {
        builder.addQueryParameter("sni", sni)
    }
    val hash = pinnedCertchainSha256
    if (!hash.isNullOrBlank()) {
        normalizePinnedCertChainHash(hash.listByLineOrComma().firstOrNull())?.let {
            builder.addQueryParameter("pinned_certchain_sha256", it)
        }
    }
    if (java.lang.Boolean.TRUE == allowInsecure) {
        builder.addQueryParameter("allow_insecure", "1")
    }
    if (!name.isNullOrBlank()) {
        builder.encodedFragment(name!!.urlSafe())
    }

    return builder.toLink("juicity")
}

fun buildSingBoxOutboundJuicityBean(bean: JuicityBean): Outbound_JuicityOptions {
    return Outbound_JuicityOptions().apply {
        type = "juicity"
        server = bean.serverAddress
        server_port = bean.serverPort
        uuid = bean.uuid
        password = bean.password

        val normalizedPin = bean.pinnedCertchainSha256
            ?.takeIf { it.isNotBlank() }
            ?.let { normalizePinnedCertChainHash(it.listByLineOrComma().firstOrNull()) }

        tls = SingBoxOptions.OutboundTLSOptions().apply {
            enabled = true
            if (!bean.sni.isNullOrBlank()) {
                server_name = bean.sni
            }
            val hasHash = !normalizedPin.isNullOrBlank()
            insecure = java.lang.Boolean.TRUE == bean.allowInsecure || DataStore.globalAllowInsecure || hasHash
        }

        if (!normalizedPin.isNullOrBlank()) {
            pin_cert_sha256 = normalizedPin
        }
    }
}

private val HEX_64_REGEX = Regex("^[0-9a-fA-F]{64}$")

private fun normalizePinnedCertChainHash(rawHash: String?): String? {
    val certChainHash = rawHash?.trim()?.replace(":", "")?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        certChainHash.length == 64 && HEX_64_REGEX.matches(certChainHash) -> runCatching {
            // java.util.Base64 is unavailable on API < 26; okio is portable.
            certChainHash.chunked(2).map { chunk -> chunk.toInt(16).toByte() }.toByteArray()
                .toByteString().base64Url()
        }.getOrNull()
        certChainHash.length == 64 -> null
        else -> certChainHash.replace('/', '_').replace('+', '-')
    }
}
