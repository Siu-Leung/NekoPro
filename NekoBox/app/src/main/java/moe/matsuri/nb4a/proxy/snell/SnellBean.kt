package moe.matsuri.nb4a.proxy.snell

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters

class SnellBean : AbstractBean() {

    @JvmField var psk: String? = null
    @JvmField var version: Int? = null
    @JvmField var mode: String? = null
    @JvmField var obfsHost: String? = null
    @JvmField var obfsMode: String? = null // "none", "http", "tls"
    @JvmField var clientFingerprint: String? = null

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        if (serverPort == null) serverPort = 443
        if (version == null) version = 4
        if (mode == null) mode = "default"
        if (obfsHost == null) obfsHost = ""
        if (obfsMode == null) obfsMode = "none"
        if (clientFingerprint == null) clientFingerprint = ""
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(2); super.serialize(output)
        output.writeString(psk); output.writeInt(version ?: 4)
        output.writeString(obfsHost); output.writeString(obfsMode)
        output.writeString(clientFingerprint)
        output.writeString(mode)
    }

    override fun deserialize(input: ByteBufferInput) {
        val v = input.readInt(); super.deserialize(input)
        psk = input.readString(); version = input.readInt()
        if (v >= 1) {
            obfsHost = input.readString(); obfsMode = input.readString()
            clientFingerprint = input.readString()
        }
        if (v >= 2) mode = input.readString()
    }

    override fun clone(): SnellBean =
        KryoConverters.deserialize(SnellBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField val CREATOR = object : CREATOR<SnellBean>() {
            override fun newInstance() = SnellBean()
            override fun newArray(size: Int) = arrayOfNulls<SnellBean>(size)
        }
    }
}
