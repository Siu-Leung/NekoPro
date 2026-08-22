package io.nekohasekai.sagernet.fmt.trojan_go

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import moe.matsuri.nb4a.utils.JavaUtil

class TrojanGoBean : AbstractBean() {

    @JvmField var password: String? = null
    @JvmField var sni: String? = null
    @JvmField var type: String? = null
    @JvmField var host: String? = null
    @JvmField var path: String? = null
    @JvmField var encryption: String? = null
    @JvmField var plugin: String? = null
    @JvmField var allowInsecure: Boolean? = null

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        if (password == null) password = ""
        if (sni == null) sni = ""
        if (JavaUtil.isNullOrBlank(type)) type = "original"
        if (host == null) host = ""
        if (path == null) path = ""
        if (JavaUtil.isNullOrBlank(encryption)) encryption = "none"
        if (plugin == null) plugin = ""
        if (allowInsecure == null) allowInsecure = false
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(1)
        super.serialize(output)
        output.writeString(password)
        output.writeString(sni)
        output.writeString(type)
        when (type) {
            "ws" -> { output.writeString(host); output.writeString(path) }
        }
        output.writeString(encryption)
        output.writeString(plugin)
        output.writeBoolean(allowInsecure!!)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        password = input.readString()
        sni = input.readString()
        type = input.readString()
        when (type) {
            "ws" -> { host = input.readString(); path = input.readString() }
        }
        encryption = input.readString()
        plugin = input.readString()
        if (version >= 1) allowInsecure = input.readBoolean()
    }

    override fun clone(): TrojanGoBean =
        KryoConverters.deserialize(TrojanGoBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField val CREATOR = object : CREATOR<TrojanGoBean>() {
            override fun newInstance() = TrojanGoBean()
            override fun newArray(size: Int) = arrayOfNulls<TrojanGoBean>(size)
        }
    }
}
