package io.nekohasekai.sagernet.fmt.hysteria

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.ktx.wrapIPV6Host

class HysteriaBean : AbstractBean() {

    @JvmField var protocolVersion: Int? = null
    @JvmField var serverPorts: String? = null
    @JvmField var authPayload: String? = null
    @JvmField var obfuscation: String? = null
    @JvmField var sni: String? = null
    @JvmField var caText: String? = null
    @JvmField var uploadMbps: Int? = null
    @JvmField var downloadMbps: Int? = null
    @JvmField var allowInsecure: Boolean? = null
    @JvmField var streamReceiveWindow: Int? = null
    @JvmField var connectionReceiveWindow: Int? = null
    @JvmField var disableMtuDiscovery: Boolean? = null
    @JvmField var hopInterval: Int? = null
    @JvmField var alpn: String? = null
    @JvmField var authPayloadType: Int? = null
    @JvmField var protocol: Int? = null

    override fun canMapping(): Boolean = protocol != PROTOCOL_FAKETCP
    override fun canTCPing(): Boolean = false

    override fun displayAddress(): String = serverAddress!!.wrapIPV6Host() + ":" + serverPorts

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        if (protocolVersion == null) protocolVersion = 2
        if (authPayloadType == null) authPayloadType = TYPE_NONE
        if (authPayload == null) authPayload = ""
        if (protocol == null) protocol = PROTOCOL_UDP
        if (obfuscation == null) obfuscation = ""
        if (sni == null) sni = ""
        if (alpn == null) alpn = ""
        if (caText == null) caText = ""
        if (allowInsecure == null) allowInsecure = false
        if (protocolVersion == 1) {
            if (uploadMbps == null) uploadMbps = 10
            if (downloadMbps == null) downloadMbps = 50
        } else {
            if (uploadMbps == null) uploadMbps = 0
            if (downloadMbps == null) downloadMbps = 0
        }
        if (streamReceiveWindow == null) streamReceiveWindow = 0
        if (connectionReceiveWindow == null) connectionReceiveWindow = 0
        if (disableMtuDiscovery == null) disableMtuDiscovery = false
        if (hopInterval == null) hopInterval = 10
        if (serverPorts == null) serverPorts = "443"
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(7)
        super.serialize(output)
        output.writeInt(protocolVersion!!)
        output.writeInt(authPayloadType!!)
        output.writeString(authPayload)
        output.writeInt(protocol!!)
        output.writeString(obfuscation)
        output.writeString(sni)
        output.writeString(alpn)
        output.writeInt(uploadMbps!!)
        output.writeInt(downloadMbps!!)
        output.writeBoolean(allowInsecure!!)
        output.writeString(caText)
        output.writeInt(streamReceiveWindow!!)
        output.writeInt(connectionReceiveWindow!!)
        output.writeBoolean(disableMtuDiscovery!!)
        output.writeInt(hopInterval!!)
        output.writeString(serverPorts)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        protocolVersion = if (version >= 7) input.readInt() else 1
        authPayloadType = input.readInt()
        authPayload = input.readString()
        if (version >= 3) protocol = input.readInt()
        obfuscation = input.readString()
        sni = input.readString()
        if (version >= 2) alpn = input.readString()
        uploadMbps = input.readInt()
        downloadMbps = input.readInt()
        allowInsecure = input.readBoolean()
        if (version >= 1) {
            caText = input.readString()
            streamReceiveWindow = input.readInt()
            connectionReceiveWindow = input.readInt()
            if (version != 4) disableMtuDiscovery = input.readBoolean()
        }
        if (version >= 5) hopInterval = input.readInt()
        if (version >= 6) {
            serverPorts = input.readString()
        } else {
            if (isMultiPort(serverAddress!!)) {
                serverPorts = serverAddress!!.substringAfterLast(":")
                serverAddress = serverAddress!!.substringBeforeLast(":")
            } else {
                serverPorts = serverPort.toString()
            }
        }
    }

    override fun clone(): HysteriaBean =
        KryoConverters.deserialize(HysteriaBean(), KryoConverters.serialize(this))

    companion object {
        const val TYPE_NONE = 0
        const val TYPE_STRING = 1
        const val TYPE_BASE64 = 2
        const val PROTOCOL_UDP = 0
        const val PROTOCOL_FAKETCP = 1
        const val PROTOCOL_WECHAT_VIDEO = 2

        @JvmField val CREATOR = object : CREATOR<HysteriaBean>() {
            override fun newInstance() = HysteriaBean()
            override fun newArray(size: Int) = arrayOfNulls<HysteriaBean>(size)
        }
    }
}
