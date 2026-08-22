package moe.matsuri.nb4a.proxy.config

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.internal.InternalBean
import moe.matsuri.nb4a.utils.JavaUtil
import kotlin.math.abs

class ConfigBean : InternalBean() {

    @JvmField var type: Int? = null // 0=config 1=outbound
    @JvmField var config: String? = null

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        if (type == null) type = 0
        if (config == null) config = ""
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(0); super.serialize(output)
        output.writeInt(type!!); output.writeString(config)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt(); super.deserialize(input)
        type = input.readInt(); config = input.readString()
    }

    override fun displayName(): String =
        if (JavaUtil.isNotBlank(name)) name!! else "Custom ${abs(hashCode())}"

    fun displayType(): String {
        if (type == 1 && JavaUtil.isNotBlank(config)) {
            try {
                val json = JavaUtil.gson.fromJson(config, JsonObject::class.java)
                if (json?.has("type") == true) return json.get("type").asString + " (sing-box)"
            } catch (_: Exception) {}
        }
        return if (type == 0) "sing-box config" else "sing-box outbound"
    }

    override fun clone(): ConfigBean =
        KryoConverters.deserialize(ConfigBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField val CREATOR = object : CREATOR<ConfigBean>() {
            override fun newInstance() = ConfigBean()
            override fun newArray(size: Int) = arrayOfNulls<ConfigBean>(size)
        }
    }
}
