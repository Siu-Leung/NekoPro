package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.plus
import kotlinx.coroutines.suspendCancellableCoroutine
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Ephemeral sing-box instance used for single-node URL latency tests.
 *
 * Uses [lifecycleLock] to synchronize cancellation against concurrent Go box
 * creation so a box created right after cancellation is closed immediately
 * instead of leaking native resources or plugin child processes.
 */
class TestInstance(profile: ProxyEntity, val link: String, private val timeout: Int) :
    BoxInstance(profile) {

    private val lifecycleLock = Any()
    @Volatile
    private var isClosed = false
    private var isBoxClosed = false

    @OptIn(DelicateCoroutinesApi::class)
    private fun closeResources() {
        val boxToClose = synchronized(lifecycleLock) {
            isClosed = true
            if (isBoxInitialized && !isBoxClosed) {
                isBoxClosed = true
                box
            } else {
                null
            }
        }
        runCatching { boxToClose?.close() }.onFailure { Logs.w(it) }
        for (instance in externalInstances.values) {
            runCatching { instance.close() }.onFailure { Logs.w(it) }
        }
        deleteCacheFiles()
        if (isProcessesInitialized) {
            runCatching {
                processes.close(GlobalScope + Dispatchers.IO)
            }.onFailure { Logs.w(it) }
        }
    }

    /**
     * Builds and runs the ephemeral test instance to measure HTTP latency against [link].
     *
     * @return Measured round-trip latency in milliseconds.
     * @throws Exception if configuration fails, the proxy errors, or the test is cancelled.
     */
    suspend fun doTest(): Int {
        return suspendCancellableCoroutine { continuation ->
            processes = GuardedProcessPool { error ->
                Logs.w(error)
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            val workerJob = runOnDefaultDispatcher {
                try {
                    if (isClosed) throw CancellationException("TestInstance cancelled before init")
                    init()
                    if (isClosed) throw CancellationException("TestInstance cancelled before launch")
                    launch()
                    if (processes.processCount > 0) {
                        // Wait briefly for external plugin subprocesses to bind their local port.
                        delay(500)
                    }
                    if (isClosed) throw CancellationException("TestInstance cancelled before urlTest")
                    val result = Libcore.urlTest(box, link, timeout)
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                } finally {
                    closeResources()
                }
            }
            continuation.invokeOnCancellation {
                synchronized(lifecycleLock) {
                    isClosed = true
                }
                workerJob.cancel()
                runOnDefaultDispatcher {
                    closeResources()
                }
            }
        }
    }

    override fun buildConfig() {
        config = buildConfig(profile, true)
    }

    override suspend fun loadConfig() {
        // Do not call destroyAllJsi here; this is an isolated test instance.
        if (BuildConfig.DEBUG) Logs.d("TestInstance loaded for ${profile.displayName()}")
        val createdBox = Libcore.newTestSingBoxInstance(config.config, LocalResolverImpl)
        val shouldCloseImmediately = synchronized(lifecycleLock) {
            box = createdBox
            if (isClosed) {
                isBoxClosed = true
                true
            } else {
                false
            }
        }
        if (shouldCloseImmediately) {
            runCatching { createdBox.close() }.onFailure { Logs.w(it) }
            throw CancellationException("TestInstance cancelled during box creation")
        }
    }

    override fun close() {
        closeResources()
    }

}
