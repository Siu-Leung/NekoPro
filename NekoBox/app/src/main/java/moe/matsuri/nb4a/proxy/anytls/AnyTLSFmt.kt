package moe.matsuri.nb4a.proxy.anytls

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.ktx.blankAsNull
import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.redactLink
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.urlSafe
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun buildSingBoxOutboundAnyTLSBean(bean: AnyTLSBean): SingBoxOptions.Outbound_AnyTLSOptions =
    buildSingBoxOutboundAnyTLSBean(bean, DataStore.globalAllowInsecure)

internal fun buildSingBoxOutboundAnyTLSBean(
    bean: AnyTLSBean,
    globalAllowInsecure: Boolean,
): SingBoxOptions.Outbound_AnyTLSOptions {
    return SingBoxOptions.Outbound_AnyTLSOptions().apply {
        type = "anytls"
        server = bean.serverAddress
        server_port = bean.serverPort
        password = bean.password

        tls = SingBoxOptions.OutboundTLSOptions().apply {
            enabled = true
            server_name = bean.sni.blankAsNull()
            if (bean.allowInsecure == true || globalAllowInsecure) insecure = true
            val parsedPins = parseAnyTLSCertificatePins(bean.certificateSha256)
            val customPem = bean.certificates.blankAsNull()
            // Fail fast before core start: sing-box rejects combining PEM CA certs with leaf SHA-256 pins.
            require(parsedPins.isEmpty() || customPem == null) {
                "AnyTLS cannot combine custom PEM certificates with certificate SHA-256 pins"
            }
            certificate_sha256 = parsedPins.takeIf { it.isNotEmpty() }
            alpn = bean.alpn.blankAsNull()?.listByLineOrComma()
            customPem?.let {
                certificate = it
            }
            bean.utlsFingerprint.blankAsNull()?.let {
                utls = SingBoxOptions.OutboundUTLSOptions().apply {
                    enabled = true
                    fingerprint = it
                }
            }
            bean.echConfig.blankAsNull()?.let {
                // In new version, some complex options will be deprecated, so we just do this.
                ech = SingBoxOptions.OutboundECHOptions().apply {
                    enabled = true
                    config = listOf(it)
                }
            }
        }

        // idle session management
        bean.idleSessionCheckInterval.blankAsNull()?.let {
            idle_session_check_interval = it
        }
        bean.idleSessionTimeout.blankAsNull()?.let {
            idle_session_timeout = it
        }
        if (bean.minIdleSession != null && bean.minIdleSession!! > 0) {
            min_idle_session = bean.minIdleSession!!
        }
        bean.clientMetadata.blankAsNull()?.let {
            client_metadata = it
        }
    }
}

fun AnyTLSBean.toUri(): String {
    // The standard AnyTLS URI has no certificate pin; only universal sharing preserves it.
    if (!certificateSha256.isNullOrBlank()) return toUniversalLink()
    val builder = linkBuilder()
        .host(serverAddress!!)
        .port(serverPort!!)
        .username(password!!)
    val beanName = name
    if (!beanName.isNullOrBlank()) {
        builder.encodedFragment(beanName.urlSafe())
    }
    if (allowInsecure == true) {
        builder.addQueryParameter("insecure", "1")
    }
    if (!sni.isNullOrBlank()) {
        builder.addQueryParameter("sni", sni!!)
    }
    val alpnList = alpn.blankAsNull()?.listByLineOrComma().orEmpty()
    if (alpnList.isNotEmpty()) {
        builder.addQueryParameter("alpn", alpnList.joinToString(","))
    }
    if (!utlsFingerprint.isNullOrBlank()) {
        builder.addQueryParameter("fp", utlsFingerprint!!)
    }
    return builder.toLink("anytls")
}

private val ANYTLS_SCHEME_REGEX = Regex("^anytls://", RegexOption.IGNORE_CASE)

fun parseAnytls(url: String): AnyTLSBean {
    // https://github.com/anytls/anytls-go/blob/main/docs/uri_scheme.md
    val trimmed = url.trim()
    val replaced = ANYTLS_SCHEME_REGEX.replaceFirst(trimmed, "https://")
    val link = replaced.toHttpUrlOrNull() ?: error(
        "invalid anytls link ${url.redactLink()}"
    )
    // Preserve passwords containing ':' (OkHttp splits userinfo on the first ':').
    val rawAuthority = replaced.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    val hasUserInfoColon = rawAuthority.contains('@') && rawAuthority.substringBeforeLast('@').contains(':')
    return AnyTLSBean().apply {
        serverAddress = link.host
        serverPort = link.port
        name = link.fragment
        password = if (hasUserInfoColon || link.encodedPassword.isNotEmpty()) {
            "${link.username}:${link.password}"
        } else {
            link.username
        }
        sni = link.queryParameter("sni") ?: ""
        alpn = link.queryParameter("alpn") ?: ""
        allowInsecure = link.queryParameter("insecure")?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: false
        link.queryParameter("fp")?.let {
            utlsFingerprint = it
        }
    }
}
