package moe.matsuri.nb4a.proxy.neko

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.ktx.Logs
import org.json.JSONObject

class NekoBean : AbstractBean() {

    @JvmField var plgId: String? = null
    @JvmField var protocolId: String? = null
    @JvmField var sharedStorage: JSONObject = JSONObject()

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        if (protocolId == null) protocolId = ""
        if (plgId == null) plgId = "moe.matsuri.plugin.donotexist"
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(0); super.serialize(output)
        output.writeString(plgId); output.writeString(protocolId)
        output.writeString(sharedStorage.toString())
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt(); super.deserialize(input)
        plgId = input.readString(); protocolId = input.readString()
        sharedStorage = tryParseJSON(input.readString())
    }

    fun displayType(): String = "invalid"
    override fun canMapping(): Boolean = false
    override fun canICMPing(): Boolean = false
    override fun canTCPing(): Boolean = false

    override fun clone(): NekoBean =
        KryoConverters.deserialize(NekoBean(), KryoConverters.serialize(this))

    companion object {
        @JvmStatic
        fun tryParseJSON(input: String?): JSONObject = try {
            JSONObject(input ?: "")
        } catch (e: Exception) {
            Logs.e(e); JSONObject()
        }

        @JvmField val CREATOR = object : CREATOR<NekoBean>() {
            override fun newInstance() = NekoBean()
            override fun newArray(size: Int) = arrayOfNulls<NekoBean>(size)
        }
    }
}
