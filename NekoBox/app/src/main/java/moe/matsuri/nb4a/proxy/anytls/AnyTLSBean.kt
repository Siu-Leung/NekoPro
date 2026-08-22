package moe.matsuri.nb4a.proxy.anytls

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters

class AnyTLSBean : AbstractBean() {

    @JvmField var password: String? = null
    @JvmField var sni: String? = null
    @JvmField var alpn: String? = null
    @JvmField var certificates: String? = null
    @JvmField var utlsFingerprint: String? = null
    @JvmField var allowInsecure: Boolean? = null
    @JvmField var echConfig: String? = null
    @JvmField var idleSessionCheckInterval: String? = null
    @JvmField var idleSessionTimeout: String? = null
    @JvmField var minIdleSession: Int? = null
    @JvmField var clientMetadata: String? = null

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        if (password == null) password = ""
        if (sni == null) sni = ""
        if (alpn == null) alpn = ""
        if (certificates == null) certificates = ""
        if (utlsFingerprint == null) utlsFingerprint = ""
        if (allowInsecure == null) allowInsecure = false
        if (echConfig == null) echConfig = ""
        if (idleSessionCheckInterval == null) idleSessionCheckInterval = ""
        if (idleSessionTimeout == null) idleSessionTimeout = ""
        if (minIdleSession == null) minIdleSession = 0
        if (clientMetadata == null) clientMetadata = ""
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(1); super.serialize(output)
        output.writeString(password); output.writeString(sni); output.writeString(alpn)
        output.writeString(certificates); output.writeString(utlsFingerprint)
        output.writeBoolean(allowInsecure!!); output.writeString(echConfig)
        output.writeString(idleSessionCheckInterval); output.writeString(idleSessionTimeout)
        output.writeInt(minIdleSession ?: 0); output.writeString(clientMetadata)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt(); super.deserialize(input)
        password = input.readString(); sni = input.readString(); alpn = input.readString()
        certificates = input.readString(); utlsFingerprint = input.readString()
        allowInsecure = input.readBoolean(); echConfig = input.readString()
        if (version >= 1) {
            idleSessionCheckInterval = input.readString()
            idleSessionTimeout = input.readString()
            minIdleSession = input.readInt()
            clientMetadata = input.readString()
        }
    }

    override fun clone(): AnyTLSBean =
        KryoConverters.deserialize(AnyTLSBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField val CREATOR = object : CREATOR<AnyTLSBean>() {
            override fun newInstance() = AnyTLSBean()
            override fun newArray(size: Int) = arrayOfNulls<AnyTLSBean>(size)
        }
    }
}
