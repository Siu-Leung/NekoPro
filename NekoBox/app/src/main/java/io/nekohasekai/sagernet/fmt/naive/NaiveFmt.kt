package io.nekohasekai.sagernet.fmt.naive

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.ktx.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

fun parseNaive(link: String): NaiveBean {
    val proto = link.substringAfter("+").substringBefore(":")
    val url = ("https://" + link.substringAfter("://")).toHttpUrlOrNull()
        ?: error("Invalid naive link: ${link.redactLink()}")
    return NaiveBean().also {
        it.proto = proto
    }.apply {
        serverAddress = url.host
        serverPort = url.port
        username = url.username
        password = url.password
        sni = url.queryParameter("sni")
        certificates = url.queryParameter("cert")
        extraHeaders = url.queryParameter("extra-headers")?.unUrlSafe()?.replace("\r\n", "\n")
        insecureConcurrency = url.queryParameter("insecure-concurrency")?.toIntOrNull()
        name = url.fragment
        initializeDefaultValues()
    }
}

fun NaiveBean.toUri(proxyOnly: Boolean = false, hostOverride: String? = null): String {
    val host = hostOverride ?: finalAddress ?: serverAddress!!
    val port = if (proxyOnly) finalPort else (serverPort ?: finalPort)
    val builder = linkBuilder().host(host).port(port)
    if (username!!.isNotBlank()) {
        builder.username(username!!)
        if (password!!.isNotBlank()) {
            builder.password(password!!)
        }
    }
    if (!proxyOnly) {
        if (sni!!.isNotBlank()) {
            builder.addQueryParameter("sni", sni)
        }
        if (certificates!!.isNotBlank()) {
            builder.addQueryParameter("cert", certificates)
        }
        if (extraHeaders!!.isNotBlank()) {
            builder.addQueryParameter("extra-headers", extraHeaders)
        }
        if (name!!.isNotBlank()) {
            builder.encodedFragment(name!!.urlSafe())
        }
        if (insecureConcurrency!! > 0) {
            builder.addQueryParameter("insecure-concurrency", "$insecureConcurrency")
        }
    }
    return builder.toLink(if (proxyOnly) proto!! else "naive+$proto", false)
}

fun NaiveBean.buildNaiveConfig(port: Int): String {
    return JSONObject().apply {
        // process ipv6 using local variables without mutating the bean
        val resolvedFinalAddress = finalAddress!!.wrapIPV6Host()
        val rawServerAddress = serverAddress!!
        var proxyHost = resolvedFinalAddress

        // process sni
        if (sni!!.isNotBlank()) {
            put("host-resolver-rules", "MAP $sni $resolvedFinalAddress")
            proxyHost = sni!!
        } else {
            if (rawServerAddress.isIpAddress()) {
                // for naive, using IP as SNI name hardly happens
                // and host-resolver-rules cannot resolve the SNI problem
                // so do nothing
            } else {
                put("host-resolver-rules", "MAP $rawServerAddress $resolvedFinalAddress")
                proxyHost = rawServerAddress
            }
        }

        put("listen", "socks://$LOCALHOST:$port")
        put("proxy", toUri(proxyOnly = true, hostOverride = proxyHost))
        if (extraHeaders!!.isNotBlank()) {
            put("extra-headers", extraHeaders!!.split("\n").joinToString("\r\n"))
        }
        if (DataStore.logLevel > 0) {
            put("log", "")
        }
        if (insecureConcurrency!! > 0) {
            put("insecure-concurrency", insecureConcurrency)
        }
    }.toStringPretty()
}