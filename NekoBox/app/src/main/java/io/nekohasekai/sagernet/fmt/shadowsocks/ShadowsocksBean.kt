package io.nekohasekai.sagernet.fmt.shadowsocks

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.UdpOverTcpConfigurable
import moe.matsuri.nb4a.utils.JavaUtil

class ShadowsocksBean : AbstractBean(), UdpOverTcpConfigurable {

    @JvmField var method: String? = null
    @JvmField var password: String? = null
    @JvmField var plugin: String? = null
    override var sUoT: Boolean? = null

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        if (JavaUtil.isNullOrBlank(method)) method = "aes-256-gcm"
        if (method == null) method = ""
        if (password == null) password = ""
        if (plugin == null) plugin = ""
        if (sUoT == null) sUoT = false
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(2)
        super.serialize(output)
        output.writeString(method)
        output.writeString(password)
        output.writeString(plugin)
        output.writeBoolean(sUoT!!)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        method = input.readString()
        password = input.readString()
        plugin = input.readString()
        sUoT = input.readBoolean()
    }

    override fun clone(): ShadowsocksBean =
        KryoConverters.deserialize(ShadowsocksBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField val CREATOR = object : CREATOR<ShadowsocksBean>() {
            override fun newInstance() = ShadowsocksBean()
            override fun newArray(size: Int) = arrayOfNulls<ShadowsocksBean>(size)
        }
    }
}
