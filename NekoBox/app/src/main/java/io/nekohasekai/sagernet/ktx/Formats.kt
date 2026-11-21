package io.nekohasekai.sagernet.ktx

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.Serializable
import io.nekohasekai.sagernet.fmt.http.parseHttp
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria1
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria2
import io.nekohasekai.sagernet.fmt.naive.parseNaive
import io.nekohasekai.sagernet.fmt.parseUniversal
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.socks.parseSOCKS
import io.nekohasekai.sagernet.fmt.trojan.parseTrojan
import io.nekohasekai.sagernet.fmt.tuic.parseTuic
import io.nekohasekai.sagernet.fmt.juicity.parseJuicity
import io.nekohasekai.sagernet.fmt.masque.parseMasque
import io.nekohasekai.sagernet.fmt.mieru.parseMieru
import io.nekohasekai.sagernet.fmt.trojan_go.parseTrojanGo
import io.nekohasekai.sagernet.fmt.v2ray.parseV2Ray
import moe.matsuri.nb4a.proxy.anytls.parseAnytls
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// JSON & Base64

fun JSONObject.toStringPretty(): String {
    return gson.toJson(JsonParser.parseString(this.toString()))
}

inline fun <reified T : Any> JSONArray.filterIsInstance(): List<T> {
    val list = mutableListOf<T>()
    for (i in 0 until this.length()) {
        if (this[i] is T) list.add(this[i] as T)
    }
    return list
}

inline fun JSONArray.forEach(action: (Int, Any) -> Unit) {
    for (i in 0 until this.length()) {
        action(i, this[i])
    }
}

inline fun JSONObject.forEach(action: (String, Any) -> Unit) {
    for (k in this.keys()) {
        action(k, this.get(k))
    }
}

fun isJsonObjectValid(j: Any): Boolean {
    if (j is JSONObject) return true
    if (j is JSONArray) return true
    try {
        JSONObject(j as String)
    } catch (ex: JSONException) {
        try {
            JSONArray(j)
        } catch (ex1: JSONException) {
            return false
        }
    }
    return true
}

// wtf hutool
fun JSONObject.getStr(name: String): String? {
    val obj = this.opt(name) ?: return null
    if (obj is String) {
        if (obj.isBlank()) {
            return null
        }
        return obj
    } else {
        return null
    }
}

fun JSONObject.getBool(name: String): Boolean? {
    return try {
        getBoolean(name)
    } catch (ignored: Exception) {
        null
    }
}


// 重名了喵
fun JSONObject.getIntNya(name: String): Int? {
    return try {
        getInt(name)
    } catch (ignored: Exception) {
        null
    }
}


fun String.decodeBase64UrlSafe(): String {
    return String(Util.b64Decode(this))
}

fun String.redactLink(): String {
    val trimmed = trim()
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd <= 0) return "<redacted>"
    val scheme = trimmed.substring(0, schemeEnd)
    val authority = trimmed.substring(schemeEnd + 3)
        .substringBefore('#')
        .substringBefore('?')
        .substringBefore('/')
    val atIndex = authority.lastIndexOf('@')
    return if (atIndex >= 0 && atIndex + 1 < authority.length) {
        "$scheme://<redacted>@${authority.substring(atIndex + 1)}"
    } else {
        "$scheme://<redacted>"
    }
}

// Sub

class SubscriptionFoundException(val link: String) : RuntimeException()

suspend fun parseProxies(text: String, forSubscription: Boolean = false): List<AbstractBean> {
    val links = text.lineSequence().flatMap { it.trim().splitToSequence(' ') }.filter { it.isNotEmpty() }.toList()
    val linksByLine = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    val entities = ArrayList<AbstractBean>()
    val entitiesByLine = ArrayList<AbstractBean>()
    var pendingSubscriptionLink: String? = null

    fun String.hasExplicitProxyPortOrCredentials(): Boolean {
        val parsed = runCatching { java.net.URI(this) }.getOrNull() ?: return false
        return parsed.port in 1..65535 || !parsed.rawUserInfo.isNullOrBlank()
    }

    fun String.parseLink(entities: ArrayList<AbstractBean>, allowHttpSubscription: Boolean) {
        if (!forSubscription && (startsWith("clash://install-config?") || startsWith("sn://subscription?"))) {
            throw SubscriptionFoundException(this)
        }

        val lower = lowercase()
        if (lower.startsWith("sn://")) {
            Logs.d("Try parse universal link")
            runCatching {
                entities.add(parseUniversal(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (lower.startsWith("socks://") || lower.startsWith("socks4://") || lower.startsWith("socks4a://") || lower.startsWith(
                "socks5://"
            )
        ) {
            Logs.d("Try parse socks link")
            runCatching {
                entities.add(parseSOCKS(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (lower.startsWith("http://") || lower.startsWith("https://")) {
            // In subscription payloads, never treat a bare webpage URL (e.g. https://example.com
            // inside an HTTP 200 maintenance page) as an HTTP proxy node unless it declares an
            // explicit proxy port or credentials.
            if (forSubscription && !hasExplicitProxyPortOrCredentials()) {
                return
            }
            Logs.d("Try parse http link")
            runCatching {
                entities.add(parseHttp(this))
            }.onFailure {
                Logs.w(it)
                if (!forSubscription && allowHttpSubscription && pendingSubscriptionLink == null) {
                    pendingSubscriptionLink = HttpUrl.Builder()
                        .scheme("https")
                        .host("install-config")
                        .addQueryParameter("url", this)
                        .build()
                        .toString()
                        .replaceFirst("https://", "clash://")
                }
            }
        } else if (lower.startsWith("vmess://")) {
            Logs.d("Try parse v2ray link")
            runCatching {
                entities.add(parseV2Ray(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (lower.startsWith("vless://")) {
            Logs.d("Try parse vless link")
            runCatching {
                entities.add(parseV2Ray(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (lower.startsWith("trojan://")) {
            Logs.d("Try parse trojan link")
            runCatching {
                entities.add(parseTrojan(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (lower.startsWith("trojan-go://")) {
            Logs.d("Try parse trojan-go link")
            runCatching {
                entities.add(parseTrojanGo(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (lower.startsWith("ss://")) {
            Logs.d("Try parse shadowsocks link")
            runCatching {
                entities.add(parseShadowsocks(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (lower.startsWith("naive+")) {
            Logs.d("Try parse naive link")
            runCatching {
                entities.add(parseNaive(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (lower.startsWith("hysteria://")) {
            Logs.d("Try parse hysteria1 link")
            runCatching {
                entities.add(parseHysteria1(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (lower.startsWith("hysteria2://") || lower.startsWith("hy2://")) {
            Logs.d("Try parse hysteria2 link")
            runCatching {
                entities.add(parseHysteria2(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (lower.startsWith("tuic://")) {
            Logs.d("Try parse TUIC link")
            runCatching {
                entities.add(parseTuic(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (lower.startsWith("masque://")) {
            Logs.d("Try parse MASQUE link")
            runCatching {
                entities.add(parseMasque(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (lower.startsWith("juicity://")) {
            Logs.d("Try parse Juicity link")
            runCatching {
                entities.add(parseJuicity(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (lower.startsWith("anytls://")) {
            Logs.d("Try parse anytls link")
            runCatching {
                entities.add(parseAnytls(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (lower.startsWith("snell://")) {
            Logs.d("Try parse snell link")
            runCatching {
                entities.add(moe.matsuri.nb4a.proxy.snell.parseSnell(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (lower.startsWith("mieru://")) {
            Logs.d("Try parse mieru link")
            runCatching {
                entities.add(parseMieru(this))
            }.onFailure {
                Logs.w(it)
            }
        }
    }

    for (link in links) {
        link.parseLink(entities, allowHttpSubscription = false)
    }
    for (link in linksByLine) {
        link.parseLink(entitiesByLine, allowHttpSubscription = true)
    }
    if (entities.onEach { it.initializeDefaultValues() }.size == entitiesByLine.onEach { it.initializeDefaultValues() }.size) run test@{
        entities.forEachIndexed { index, bean ->
            val lineBean = entitiesByLine[index]
            if (bean == lineBean && bean.displayName() != lineBean.displayName()) {
                return@test
            }
        }
    }
    val result = if (entities.size > entitiesByLine.size) entities else entitiesByLine
    if (!forSubscription && result.isEmpty() && pendingSubscriptionLink != null) {
        throw SubscriptionFoundException(pendingSubscriptionLink!!)
    }
    return result
}

fun <T : Serializable> T.applyDefaultValues(): T {
    initializeDefaultValues()
    return this
}