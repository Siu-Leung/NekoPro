package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.tryResume
import io.nekohasekai.sagernet.ktx.tryResumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import java.util.concurrent.atomic.AtomicBoolean

class TestInstance(profile: ProxyEntity, val link: String, private val timeout: Int) :
    BoxInstance(profile) {

    private val closed = AtomicBoolean(false)

    private fun closeOnce() {
        if (closed.compareAndSet(false, true)) {
            runCatching { close() }
        }
    }

    suspend fun doTest(): Int {
        return suspendCancellableCoroutine { c ->
            processes = GuardedProcessPool {
                Logs.w(it)
                c.tryResumeWithException(it)
            }
            val job = runOnDefaultDispatcher {
                try {
                    init()
                    launch()
                    if (processes.processCount > 0) {
                        // wait for plugin start
                        delay(500)
                    }
                    c.tryResume(Libcore.urlTest(box, link, timeout))
                } catch (e: Exception) {
                    c.tryResumeWithException(e)
                } finally {
                    closeOnce()
                }
            }
            c.invokeOnCancellation {
                job.cancel()
                runOnDefaultDispatcher {
                    closeOnce()
                }
            }
        }
    }

    override fun buildConfig() {
        config = buildConfig(profile, true)
    }

    override suspend fun loadConfig() {
        // don't call destroyAllJsi here
        if (BuildConfig.DEBUG) Logs.d("TestInstance loaded for ${profile.displayName()}")
        box = Libcore.newTestSingBoxInstance(config.config, LocalResolverImpl)
    }

}
