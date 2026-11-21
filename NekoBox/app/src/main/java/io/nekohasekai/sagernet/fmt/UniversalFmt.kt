package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import moe.matsuri.nb4a.utils.Util
import java.util.Base64

/** Preserve the existing unpadded URL-safe wire encoding, independently of Android UI APIs. */
private fun encodeUniversalPayload(data: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(Util.zlibCompress(data, 9))

/** Accept the URL-safe encoding and the alphabet/padding used by older exporters. */
private fun decodeUniversalPayload(encoded: String): ByteArray =
    Base64.getUrlDecoder().decode(encoded.filterNot(Char::isWhitespace).replace('+', '-').replace('/', '_'))

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

/** Export only the node bean; the group's private DNS does not travel in a node link. */
fun AbstractBean.toUniversalLink(): String {
    val type = TypeMap.reversed[ProxyEntity().putBean(this).type]
    return "sn://$type?${encodeUniversalPayload(KryoConverters.serialize(this))}"
}

/** Export the subscription descriptor without its local DNS settings. */
fun ProxyGroup.toUniversalLink(): String {
    val share = copy().apply { export = true }
    return "sn://subscription?${encodeUniversalPayload(KryoConverters.serialize(share))}"
}