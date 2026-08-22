package io.nekohasekai.sagernet.fmt.internal

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.KryoConverters
import moe.matsuri.nb4a.utils.JavaUtil
import kotlin.math.abs

class ChainBean : InternalBean() {

    @JvmField var proxies: MutableList<Long>? = null

    override fun displayName(): String =
        if (JavaUtil.isNotBlank(name)) name!! else "Chain ${abs(hashCode())}"

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        if (name == null) name = ""
        if (proxies == null) proxies = mutableListOf()
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(1)
        output.writeInt(proxies!!.size)
        for (proxy in proxies!!) output.writeLong(proxy)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        if (version < 1) { input.readString(); input.readInt() }
        val length = input.readInt()
        proxies = mutableListOf()
        for (i in 0 until length) proxies!!.add(input.readLong())
    }

    override fun clone(): ChainBean =
        KryoConverters.deserialize(ChainBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField val CREATOR = object : CREATOR<ChainBean>() {
            override fun newInstance() = ChainBean()
            override fun newArray(size: Int) = arrayOfNulls<ChainBean>(size)
        }
    }
}
