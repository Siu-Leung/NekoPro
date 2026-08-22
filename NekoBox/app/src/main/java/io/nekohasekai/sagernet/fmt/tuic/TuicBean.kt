package io.nekohasekai.sagernet.fmt.tuic

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters

class TuicBean : AbstractBean() {

    @JvmField var token: String? = null
    @JvmField var caText: String? = null
    @JvmField var udpRelayMode: String? = null
    @JvmField var congestionController: String? = null
    @JvmField var alpn: String? = null
    @JvmField var disableSNI: Boolean? = null
    @JvmField var reduceRTT: Boolean? = null
    @JvmField var mtu: Int? = null
    @JvmField var sni: String? = null
    @JvmField var fastConnect: Boolean? = null
    @JvmField var allowInsecure: Boolean? = null
    @JvmField var customJSON: String? = null
    @JvmField var protocolVersion: Int? = null
    @JvmField var uuid: String? = null

    override fun canTCPing(): Boolean = false

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        if (token == null) token = ""
        if (caText == null) caText = ""
        if (udpRelayMode == null) udpRelayMode = "native"
        if (congestionController == null) congestionController = "cubic"
        if (alpn == null) alpn = ""
        if (disableSNI == null) disableSNI = false
        if (reduceRTT == null) reduceRTT = false
        if (mtu == null) mtu = 1400
        if (sni == null) sni = ""
        if (fastConnect == null) fastConnect = false
        if (allowInsecure == null) allowInsecure = false
        if (customJSON == null) customJSON = ""
        if (protocolVersion == null) protocolVersion = 5
        if (uuid == null) uuid = ""
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(2)
        super.serialize(output)
        output.writeString(token); output.writeString(caText)
        output.writeString(udpRelayMode); output.writeString(congestionController)
        output.writeString(alpn); output.writeBoolean(disableSNI!!)
        output.writeBoolean(reduceRTT!!); output.writeInt(mtu!!)
        output.writeString(sni); output.writeBoolean(fastConnect!!)
        output.writeBoolean(allowInsecure!!); output.writeString(customJSON)
        output.writeInt(protocolVersion!!); output.writeString(uuid)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        token = input.readString(); caText = input.readString()
        udpRelayMode = input.readString(); congestionController = input.readString()
        alpn = input.readString(); disableSNI = input.readBoolean()
        reduceRTT = input.readBoolean(); mtu = input.readInt()
        sni = input.readString()
        if (version >= 1) { fastConnect = input.readBoolean(); allowInsecure = input.readBoolean() }
        if (version >= 2) { customJSON = input.readString(); protocolVersion = input.readInt(); uuid = input.readString() }
        else protocolVersion = 4
    }

    override fun clone(): TuicBean =
        KryoConverters.deserialize(TuicBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField val CREATOR = object : CREATOR<TuicBean>() {
            override fun newInstance() = TuicBean()
            override fun newArray(size: Int) = arrayOfNulls<TuicBean>(size)
        }
    }
}
