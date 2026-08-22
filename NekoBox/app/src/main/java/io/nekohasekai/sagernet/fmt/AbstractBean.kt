package io.nekohasekai.sagernet.fmt

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.ktx.wrapIPV6Host
import io.nekohasekai.sagernet.ktx.unwrapIPV6Host
import moe.matsuri.nb4a.utils.JavaUtil

abstract class AbstractBean : Serializable(), Cloneable {

    @JvmField var serverAddress: String? = null
    @JvmField var serverPort: Int? = null
    @JvmField var name: String? = null
    @JvmField var customOutboundJson: String? = null
    @JvmField var customConfigJson: String? = null

    @JvmField @Transient var finalAddress: String? = null
    @JvmField @Transient var finalPort: Int = 0

    private @Transient var serializeWithoutName = false

    open fun displayName(): String =
        if (JavaUtil.isNotBlank(name)) name!! else displayAddress()

    open fun displayAddress(): String =
        "${serverAddress!!.wrapIPV6Host()}:$serverPort"

    open fun network(): String = "tcp,udp"
    open fun canICMPing(): Boolean = true
    open fun canTCPing(): Boolean = true
    open fun canMapping(): Boolean = true

    override fun initializeDefaultValues() {
        if (JavaUtil.isNullOrBlank(serverAddress)) {
            serverAddress = "127.0.0.1"
        } else if (serverAddress!!.startsWith("[") && serverAddress!!.endsWith("]")) {
            serverAddress = serverAddress!!.unwrapIPV6Host()
        }
        if (serverPort == null) serverPort = 1080
        if (name == null) name = ""
        finalAddress = serverAddress
        finalPort = serverPort!!
        if (customOutboundJson == null) customOutboundJson = ""
        if (customConfigJson == null) customConfigJson = ""
    }

    override fun serializeToBuffer(output: ByteBufferOutput) {
        serialize(output)
        output.writeInt(1)
        if (!serializeWithoutName) {
            output.writeString(name)
        }
        output.writeString(customOutboundJson)
        output.writeString(customConfigJson)
    }

    override fun deserializeFromBuffer(input: ByteBufferInput) {
        deserialize(input)
        val extraVersion = input.readInt()
        name = input.readString()
        customOutboundJson = input.readString()
        customConfigJson = input.readString()
    }

    open fun serialize(output: ByteBufferOutput) {
        output.writeString(serverAddress)
        output.writeInt(serverPort!!)
    }

    open fun deserialize(input: ByteBufferInput) {
        serverAddress = input.readString()
        serverPort = input.readInt()
    }

    public abstract override fun clone(): AbstractBean
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val o = other as AbstractBean
        return try {
            serializeWithoutName = true
            o.serializeWithoutName = true
            KryoConverters.serialize(this).contentEquals(KryoConverters.serialize(o))
        } finally {
            serializeWithoutName = false
            o.serializeWithoutName = false
        }
    }

    override fun hashCode(): Int {
        return try {
            serializeWithoutName = true
            KryoConverters.serialize(this).contentHashCode()
        } finally {
            serializeWithoutName = false
        }
    }

    override fun toString(): String =
        "${javaClass.simpleName} ${JavaUtil.gson.toJson(this)}"
}
