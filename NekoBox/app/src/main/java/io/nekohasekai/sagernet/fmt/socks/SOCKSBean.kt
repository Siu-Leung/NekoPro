package io.nekohasekai.sagernet.fmt.socks

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.UdpOverTcpConfigurable

class SOCKSBean : AbstractBean(), UdpOverTcpConfigurable {

    @JvmField var protocol: Int? = null
    override var sUoT: Boolean? = null
    @JvmField var username: String? = null
    @JvmField var password: String? = null

    fun protocolVersion(): Int = when (protocol) { 0, 1 -> 4; else -> 5 }
    fun protocolName(): String = when (protocol) { 0 -> "SOCKS4"; 1 -> "SOCKS4A"; else -> "SOCKS5" }
    fun protocolVersionName(): String = when (protocol) { 0 -> "4"; 1 -> "4a"; else -> "5" }

    override fun network(): String = if (protocol != null && protocol!! < PROTOCOL_SOCKS5) "tcp" else super.network()

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        if (protocol == null) protocol = PROTOCOL_SOCKS5
        if (username == null) username = ""
        if (password == null) password = ""
        if (sUoT == null) sUoT = false
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(2)
        super.serialize(output)
        output.writeInt(protocol!!)
        output.writeString(username)
        output.writeString(password)
        output.writeBoolean(sUoT!!)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        if (version >= 1) protocol = input.readInt()
        username = input.readString()
        password = input.readString()
        if (version >= 2) sUoT = input.readBoolean()
    }

    override fun clone(): SOCKSBean =
        KryoConverters.deserialize(SOCKSBean(), KryoConverters.serialize(this))

    companion object {
        const val PROTOCOL_SOCKS4 = 0
        const val PROTOCOL_SOCKS4A = 1
        const val PROTOCOL_SOCKS5 = 2

        @JvmField val CREATOR = object : CREATOR<SOCKSBean>() {
            override fun newInstance() = SOCKSBean()
            override fun newArray(size: Int) = arrayOfNulls<SOCKSBean>(size)
        }
    }
}
