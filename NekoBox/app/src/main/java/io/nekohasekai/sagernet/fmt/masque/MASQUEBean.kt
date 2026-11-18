package io.nekohasekai.sagernet.fmt.masque

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters

class MASQUEBean : AbstractBean() {

    @JvmField var privateKey: String? = null
    @JvmField var publicKey: String? = null
    @JvmField var localAddress: String? = null
    @JvmField var localAddressV6: String? = null
    @JvmField var uri: String? = null
    @JvmField var sni: String? = null
    @JvmField var mtu: Int? = null
    @JvmField var network: String? = null
    @JvmField var congestionController: String? = null
    @JvmField var allowInsecure: Boolean? = null
    @JvmField var remoteDnsResolve: Boolean? = null

    override fun canTCPing(): Boolean = false

    override fun initializeDefaultValues() {
        if (serverPort == null) serverPort = 443
        super.initializeDefaultValues()
        if (privateKey == null) privateKey = ""
        if (publicKey == null) publicKey = ""
        if (localAddress == null) localAddress = "172.16.0.2/32"
        if (localAddressV6 == null) localAddressV6 = ""
        if (uri == null) uri = ""
        if (sni == null) sni = ""
        if (mtu == null || mtu!! <= 0) mtu = 1280
        if (network.isNullOrBlank()) network = "h3"
        if (congestionController.isNullOrBlank()) congestionController = "bbr"
        if (allowInsecure == null) allowInsecure = false
        if (remoteDnsResolve == null) remoteDnsResolve = false
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(1)
        super.serialize(output)
        output.writeString(privateKey)
        output.writeString(publicKey)
        output.writeString(localAddress)
        output.writeString(localAddressV6)
        output.writeString(uri)
        output.writeString(sni)
        output.writeInt(mtu ?: 1280)
        output.writeString(network)
        output.writeString(congestionController)
        output.writeBoolean(allowInsecure == true)
        output.writeBoolean(remoteDnsResolve == true)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        privateKey = input.readString()
        publicKey = input.readString()
        localAddress = input.readString()
        localAddressV6 = input.readString()
        uri = input.readString()
        sni = input.readString()
        mtu = input.readInt()
        network = input.readString()
        congestionController = input.readString()
        allowInsecure = input.readBoolean()
        if (version >= 1) {
            remoteDnsResolve = input.readBoolean()
        }
    }

    override fun clone(): MASQUEBean =
        KryoConverters.deserialize(MASQUEBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField val CREATOR = object : CREATOR<MASQUEBean>() {
            override fun newInstance() = MASQUEBean()
            override fun newArray(size: Int) = arrayOfNulls<MASQUEBean>(size)
        }
    }
}
