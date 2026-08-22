package io.nekohasekai.sagernet.fmt.http

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean

class HttpBean : StandardV2RayBean() {

    @JvmField var username: String? = null
    @JvmField var password: String? = null

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        if (username == null) username = ""
        if (password == null) password = ""
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(0)
        super.serialize(output)
        output.writeString(username)
        output.writeString(password)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        username = input.readString()
        password = input.readString()
    }

    override fun clone(): HttpBean =
        KryoConverters.deserialize(HttpBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField val CREATOR = object : CREATOR<HttpBean>() {
            override fun newInstance() = HttpBean()
            override fun newArray(size: Int) = arrayOfNulls<HttpBean>(size)
        }
    }
}
