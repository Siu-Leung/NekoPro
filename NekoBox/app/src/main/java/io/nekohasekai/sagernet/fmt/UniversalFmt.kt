package io.nekohasekai.sagernet.fmt

import com.google.common.io.BaseEncoding
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import moe.matsuri.nb4a.utils.Util

private const val ZLIB_MAX_COMPRESSION_LEVEL = 9
private val UNIVERSAL_BASE64_URL = BaseEncoding.base64Url().omitPadding()

/**
 * Compresses [data] with zlib and encodes it using unpadded URL-safe Base64.
 *
 * Uses Guava [BaseEncoding] rather than `java.util.Base64` so the encoder works
 * identically across Android API 21+, R8 release builds, and JVM unit tests.
 */
private fun encodeUniversalPayload(data: ByteArray): String =
    UNIVERSAL_BASE64_URL.encode(Util.zlibCompress(data, ZLIB_MAX_COMPRESSION_LEVEL))

/**
 * Decodes a universal link payload, accepting both URL-safe and standard Base64
 * alphabets with or without trailing padding or embedded whitespace.
 *
 * @param encoded Raw Base64 payload string extracted from an `sn://` link.
 * @return Decoded raw bytes.
 * @throws IllegalArgumentException if [encoded] contains invalid Base64 characters.
 */
private fun decodeUniversalPayload(encoded: String): ByteArray {
    val normalized = encoded
        .filterNot(Char::isWhitespace)
        .replace('+', '-')
        .replace('/', '_')
        .trimEnd('=')
    return UNIVERSAL_BASE64_URL.decode(normalized)
}

/**
 * Parses an `sn://` universal proxy link into its corresponding [AbstractBean].
 *
 * @param link Universal link string (either zlib-compressed `sn://<type>?<b64>` or legacy `sn://<type>:<b64>`).
 * @return Deserialized [AbstractBean] instance.
 * @throws IllegalStateException if the proxy type is unknown or payload cannot be decoded.
 */
fun parseUniversal(link: String): AbstractBean {
    return if (link.contains("?")) {
        val type = link.substringAfter("sn://").substringBefore("?")
        ProxyEntity(type = TypeMap[type] ?: error("Type $type not found")).apply {
            putByteArray(Util.zlibDecompress(decodeUniversalPayload(link.substringAfter("?"))))
        }.requireBean()
    } else {
        val type = link.substringAfter("sn://").substringBefore(":")
        ProxyEntity(type = TypeMap[type] ?: error("Type $type not found")).apply {
            putByteArray(decodeUniversalPayload(link.substringAfter(":").substringAfter(":")))
        }.requireBean()
    }
}

/**
 * Serializes this node bean into an `sn://` universal share link.
 * Group-level private DNS settings are intentionally excluded from node links.
 */
fun AbstractBean.toUniversalLink(): String {
    val type = TypeMap.reversed[ProxyEntity().putBean(this).type]
        ?: error("Unsupported proxy bean type: ${this::class.java.simpleName}")
    return "sn://$type?${encodeUniversalPayload(KryoConverters.serialize(this))}"
}

/**
 * Serializes this subscription group into an `sn://subscription?...` universal share link
 * without exporting local private DNS settings.
 *
 * @throws IllegalArgumentException if this group is not a [GroupType.SUBSCRIPTION] group
 * or has no [ProxyGroup.subscription] descriptor.
 */
fun ProxyGroup.toUniversalLink(): String {
    require(type == GroupType.SUBSCRIPTION && subscription != null) {
        "Only subscription groups can be exported as universal subscription links"
    }
    val share = copy().apply { export = true }
    return "sn://subscription?${encodeUniversalPayload(KryoConverters.serialize(share))}"
}