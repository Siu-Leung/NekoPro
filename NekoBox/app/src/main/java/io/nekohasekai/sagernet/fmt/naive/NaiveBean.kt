package io.nekohasekai.sagernet.fmt.naive

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.UdpOverTcpConfigurable

class NaiveBean : AbstractBean(), UdpOverTcpConfigurable {

    @JvmField var proto: String? = null
    @JvmField var username: String? = null
    @JvmField var password: String? = null
    @JvmField var extraHeaders: String? = null
    @JvmField var sni: String? = null
    @JvmField var certificates: String? = null
    @JvmField var insecureConcurrency: Int? = null
    override var sUoT: Boolean? = null

    override fun initializeDefaultValues() {
        if (serverPort == null) serverPort = 443
        super.initializeDefaultValues()
        if (proto == null) proto = "https"
        if (username == null) username = ""
        if (password == null) password = ""
        if (extraHeaders == null) extraHeaders = ""
        if (certificates == null) certificates = ""
        if (sni == null) sni = ""
        if (insecureConcurrency == null) insecureConcurrency = 0
        if (sUoT == null) sUoT = false
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(3)
        super.serialize(output)
        output.writeString(proto)
        output.writeString(username)
        output.writeString(password)
        output.writeString(extraHeaders)
        output.writeString(certificates)
        output.writeString(sni)
        output.writeInt(insecureConcurrency!!)
        output.writeBoolean(sUoT!!)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        proto = input.readString()
        username = input.readString()
        password = input.readString()
        extraHeaders = input.readString()
        if (version >= 2) {
            certificates = input.readString()
            sni = input.readString()
        }
        if (version >= 1) insecureConcurrency = input.readInt()
        if (version >= 3) sUoT = input.readBoolean()
    }

    override fun clone(): NaiveBean =
        KryoConverters.deserialize(NaiveBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField val CREATOR = object : CREATOR<NaiveBean>() {
            override fun newInstance() = NaiveBean()
            override fun newArray(size: Int) = arrayOfNulls<NaiveBean>(size)
        }
    }
}
