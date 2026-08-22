package io.nekohasekai.sagernet.fmt.v2ray

import io.nekohasekai.sagernet.fmt.KryoConverters
import moe.matsuri.nb4a.utils.JavaUtil

class VMessBean : StandardV2RayBean() {

    @JvmField var alterId: Int? = null // alterID == -1 --> VLESS

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        if (alterId == null) alterId = 0
        encryption = if (alterId == -1) {
            if (JavaUtil.isNotBlank(encryption)) encryption else ""
        } else {
            if (JavaUtil.isNotBlank(encryption)) encryption else "auto"
        }
    }

    override fun clone(): VMessBean =
        KryoConverters.deserialize(VMessBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField val CREATOR = object : CREATOR<VMessBean>() {
            override fun newInstance() = VMessBean()
            override fun newArray(size: Int) = arrayOfNulls<VMessBean>(size)
        }
    }
}
