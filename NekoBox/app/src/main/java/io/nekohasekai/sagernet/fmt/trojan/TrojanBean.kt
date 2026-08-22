package io.nekohasekai.sagernet.fmt.trojan

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean

class TrojanBean : StandardV2RayBean() {

    @JvmField var password: String? = null

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        if (security.isNullOrEmpty()) security = "tls"
        if (password == null) password = ""
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(2)
        super.serialize(output)
        output.writeString(password)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        if (version >= 2) {
            super.deserialize(input)
            password = input.readString()
        } else {
            serverAddress = input.readString()
            serverPort = input.readInt()
            password = input.readString()
            security = input.readString()
            sni = input.readString()
            alpn = input.readString()
            if (version == 1) allowInsecure = input.readBoolean()
        }
    }

    override fun clone(): TrojanBean =
        KryoConverters.deserialize(TrojanBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField val CREATOR = object : CREATOR<TrojanBean>() {
            override fun newInstance() = TrojanBean()
            override fun newArray(size: Int) = arrayOfNulls<TrojanBean>(size)
        }
    }
}
