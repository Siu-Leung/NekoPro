package io.nekohasekai.sagernet.fmt.wireguard

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters

class WireGuardBean : AbstractBean() {

    @JvmField var localAddress: String? = null
    @JvmField var privateKey: String? = null
    @JvmField var peerPublicKey: String? = null
    @JvmField var peerPreSharedKey: String? = null
    @JvmField var mtu: Int? = null
    @JvmField var reserved: String? = null

    override fun canTCPing(): Boolean = false

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        if (localAddress == null) localAddress = ""
        if (privateKey == null) privateKey = ""
        if (peerPublicKey == null) peerPublicKey = ""
        if (peerPreSharedKey == null) peerPreSharedKey = ""
        if (mtu == null) mtu = 1420
        if (reserved == null) reserved = ""
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(2); super.serialize(output)
        output.writeString(localAddress); output.writeString(privateKey)
        output.writeString(peerPublicKey); output.writeString(peerPreSharedKey)
        output.writeInt(mtu!!); output.writeString(reserved)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt(); super.deserialize(input)
        localAddress = input.readString(); privateKey = input.readString()
        peerPublicKey = input.readString(); peerPreSharedKey = input.readString()
        mtu = input.readInt(); reserved = input.readString()
    }

    override fun clone(): WireGuardBean =
        KryoConverters.deserialize(WireGuardBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField val CREATOR = object : CREATOR<WireGuardBean>() {
            override fun newInstance() = WireGuardBean()
            override fun newArray(size: Int) = arrayOfNulls<WireGuardBean>(size)
        }
    }
}
