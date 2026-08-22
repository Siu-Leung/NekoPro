package io.nekohasekai.sagernet.fmt.mieru

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters

class MieruBean : AbstractBean() {

    @JvmField var protocol: String? = null
    @JvmField var username: String? = null
    @JvmField var password: String? = null
    @JvmField var mtu: Int? = null

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        if (protocol == null) protocol = "TCP"
        if (username == null) username = ""
        if (password == null) password = ""
        if (mtu == null) mtu = 1400
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(0)
        super.serialize(output)
        output.writeString(protocol)
        output.writeString(username)
        output.writeString(password)
        if (protocol == "UDP") output.writeInt(mtu!!)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        protocol = input.readString()
        username = input.readString()
        password = input.readString()
        if (protocol == "UDP") mtu = input.readInt()
    }

    override fun clone(): MieruBean =
        KryoConverters.deserialize(MieruBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField val CREATOR = object : CREATOR<MieruBean>() {
            override fun newInstance() = MieruBean()
            override fun newArray(size: Int) = arrayOfNulls<MieruBean>(size)
        }
    }
}
