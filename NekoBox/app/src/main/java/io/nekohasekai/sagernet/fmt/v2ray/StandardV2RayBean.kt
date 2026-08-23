package io.nekohasekai.sagernet.fmt.v2ray

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import moe.matsuri.nb4a.utils.JavaUtil

abstract class StandardV2RayBean : AbstractBean() {

    @JvmField var uuid: String? = null
    @JvmField var encryption: String? = null // or VLESS flow

    // "V2Ray Transport" tcp/http/ws/quic/grpc/httpupgrade/xhttp
    @JvmField var type: String? = null
    @JvmField var host: String? = null
    @JvmField var path: String? = null
    // xhttp 专用: mode = auto / packet-up / stream-up / stream-one
    @JvmField var xhttpMode: String? = null

    // tls
    @JvmField var security: String? = null
    @JvmField var sni: String? = null
    @JvmField var alpn: String? = null
    @JvmField var utlsFingerprint: String? = null
    @JvmField var allowInsecure: Boolean? = null

    // reality
    @JvmField var realityPubKey: String? = null
    @JvmField var realityShortId: String? = null

    @JvmField var wsMaxEarlyData: Int? = null
    @JvmField var earlyDataHeaderName: String? = null
    @JvmField var certificates: String? = null

    // ech
    @JvmField var enableECH: Boolean? = null
    @JvmField var echConfig: String? = null

    // Mux
    @JvmField var enableMux: Boolean? = null
    @JvmField var muxPadding: Boolean? = null
    @JvmField var muxType: Int? = null
    @JvmField var muxConcurrency: Int? = null

    @JvmField var packetEncoding: Int? = null // 1:packet 2:xudp

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        if (JavaUtil.isNullOrBlank(uuid)) uuid = ""
        if (JavaUtil.isNullOrBlank(type)) type = "tcp"
        else if (type == "h2") type = "http"
        type = type!!.lowercase()
        if (JavaUtil.isNullOrBlank(host)) host = ""
        if (JavaUtil.isNullOrBlank(path)) path = ""
        if (JavaUtil.isNullOrBlank(security)) {
            security = if (this is TrojanBean) "tls" else "none"
        }
        if (JavaUtil.isNullOrBlank(sni)) sni = ""
        if (JavaUtil.isNullOrBlank(alpn)) alpn = ""
        if (JavaUtil.isNullOrBlank(certificates)) certificates = ""
        if (JavaUtil.isNullOrBlank(earlyDataHeaderName)) earlyDataHeaderName = ""
        if (JavaUtil.isNullOrBlank(utlsFingerprint)) utlsFingerprint = ""
        if (JavaUtil.isNullOrBlank(xhttpMode)) xhttpMode = ""
        if (wsMaxEarlyData == null) wsMaxEarlyData = 0
        if (allowInsecure == null) allowInsecure = false
        if (packetEncoding == null) packetEncoding = 0
        if (realityPubKey == null) realityPubKey = ""
        if (realityShortId == null) realityShortId = ""
        if (enableECH == null) enableECH = false
        if (JavaUtil.isNullOrBlank(echConfig)) echConfig = ""
        if (enableMux == null) enableMux = false
        if (muxPadding == null) muxPadding = false
        if (muxType == null) muxType = 0
        if (muxConcurrency == null) muxConcurrency = 1
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(6)
        super.serialize(output)
        output.writeString(uuid)
        output.writeString(encryption)
        if (this is VMessBean) {
            output.writeInt((this as VMessBean).alterId!!)
        }
        val t = type ?: "tcp"
        val s = security ?: ""
        output.writeString(t)
        when (t) {
            "tcp", "quic" -> {}
            "ws" -> {
                output.writeString(host)
                output.writeString(path)
                output.writeInt(wsMaxEarlyData!!)
                output.writeString(earlyDataHeaderName)
            }
            "http", "httpupgrade", "xhttp" -> {
                output.writeString(host)
                output.writeString(path)
            }
            "grpc" -> output.writeString(path)
        }
        output.writeString(s)
        if (s == "tls") {
            output.writeString(sni)
            output.writeString(alpn)
            output.writeString(certificates)
            output.writeBoolean(allowInsecure!!)
            output.writeString(utlsFingerprint)
            output.writeString(realityPubKey)
            output.writeString(realityShortId)
        }
        output.writeBoolean(enableECH!!)
        output.writeString(echConfig)
        output.writeInt(packetEncoding!!)
        output.writeBoolean(enableMux!!)
        output.writeBoolean(muxPadding!!)
        output.writeInt(muxType!!)
        output.writeInt(muxConcurrency!!)
        output.writeString(xhttpMode)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        uuid = input.readString()
        encryption = input.readString()
        if (this is VMessBean) {
            (this as VMessBean).alterId = input.readInt()
        }
        type = input.readString()
        if (type != null) {
            when (type) {
                "tcp", "quic" -> {}
                "ws" -> {
                    host = input.readString()
                    path = input.readString()
                    wsMaxEarlyData = input.readInt()
                    earlyDataHeaderName = input.readString()
                }
                "http", "httpupgrade", "xhttp" -> {
                    host = input.readString()
                    path = input.readString()
                }
                "grpc" -> {
                    path = input.readString()
                    if (version < 4) {
                        input.readString()
                        input.readString()
                    }
                }
            }
        }
        security = input.readString()
        if (security == "tls") {
            sni = input.readString()
            alpn = input.readString()
            certificates = input.readString()
            allowInsecure = input.readBoolean()
            utlsFingerprint = input.readString()
            realityPubKey = input.readString()
            realityShortId = input.readString()
        }
        if (version >= 1) {
            enableECH = input.readBoolean()
            if (version >= 3) {
                echConfig = input.readString()
            } else {
                if (enableECH == true) {
                    input.readBoolean()
                    input.readBoolean()
                    echConfig = input.readString()
                }
            }
        } else if (version == 0) {
            val position = input.byteBuffer.position()
            val tmpEnableECH = input.readBoolean()
            val tmpPacketEncoding = input.readInt()
            input.setPosition(position)
            if (tmpPacketEncoding != 1 && tmpPacketEncoding != 2) {
                enableECH = tmpEnableECH
                if (enableECH == true) {
                    input.readBoolean()
                    input.readBoolean()
                    echConfig = input.readString()
                }
            }
        }
        packetEncoding = input.readInt()
        if (version >= 2) {
            enableMux = input.readBoolean()
            muxPadding = input.readBoolean()
            muxType = input.readInt()
            muxConcurrency = input.readInt()
        }
        if (version >= 6) {
            xhttpMode = input.readString()
            if (JavaUtil.isNullOrBlank(xhttpMode)) xhttpMode = ""
        }
    }

    val isVLESS: Boolean
        get() {
            if (this is VMessBean) {
                val aid = (this as VMessBean).alterId
                return aid != null && aid == -1
            }
            return false
        }
}
