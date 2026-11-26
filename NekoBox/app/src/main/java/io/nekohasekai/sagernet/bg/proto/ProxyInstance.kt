package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.runBlocking


class ProxyInstance(profile: ProxyEntity, var service: BaseService.Interface? = null) :
    BoxInstance(profile) {

    var notTmp = true

    var lastSelectorGroupId = -1L
    var displayProfileName = ServiceNotification.genTitle(profile)

    // for TrafficLooper
    var looper: TrafficLooper? = null

    override fun buildConfig() {
        super.buildConfig()
        lastSelectorGroupId = super.config.selectorGroupId
    }

    // only use this in temporary instance
    fun buildConfigTmp() {
        notTmp = false
        buildConfig()
    }

    override suspend fun init() {
        super.init()
    }

    override suspend fun loadConfig() {
        super.loadConfig()
        val statsTags = hashSetOf(
            io.nekohasekai.sagernet.fmt.TAG_PROXY,
            io.nekohasekai.sagernet.fmt.TAG_BYPASS,
            io.nekohasekai.sagernet.fmt.TAG_DIRECT,
        ).apply {
            addAll(config.trafficMap.keys)
        }
        box.setV2rayStats(statsTags.joinToString("\n"))
    }

    override fun launch() {
        box.setAsMain()
        super.launch() // start box
        if (config.selectorGroupId >= 0L) {
            config.profileTagMap[profile.id]?.takeIf { it.isNotBlank() }?.let { selectedTag ->
                box.selectOutbound(selectedTag)
            }
        }
        runOnDefaultDispatcher {
            looper = service?.let { TrafficLooper(it.data, this) }
            looper?.start()
        }
    }

    override fun close() {
        runBlocking {
            looper?.stop()
            looper = null
        }
        super.close()
    }
}
