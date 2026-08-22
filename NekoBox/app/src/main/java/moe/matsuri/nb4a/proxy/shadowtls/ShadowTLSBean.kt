package moe.matsuri.nb4a.proxy.shadowtls

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean

class ShadowTLSBean : StandardV2RayBean() {

    @JvmField var version: Int? = null
    @JvmField var password: String? = null

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        security = "tls"
        if (version == null) version = 3
        if (password == null) password = ""
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(0)
        super.serialize(output)
        output.writeInt(version!!)
        output.writeString(password)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version_ = input.readInt()
        super.deserialize(input)
        version = input.readInt()
        password = input.readString()
    }

    override fun clone(): ShadowTLSBean =
        KryoConverters.deserialize(ShadowTLSBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField val CREATOR = object : CREATOR<ShadowTLSBean>() {
            override fun newInstance() = ShadowTLSBean()
            override fun newArray(size: Int) = arrayOfNulls<ShadowTLSBean>(size)
        }
    }
}
