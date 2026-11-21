package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import libcore.Libcore
import libcore.SpeedTestResult
import libcore.SpeedTestSession
import moe.matsuri.nb4a.net.LocalResolverImpl
import kotlin.coroutines.coroutineContext

data class SpeedTestSnapshot(
    val profileId: Long,
    val profileName: String,
    val mode: String,
    val stage: String,
    val downloadBitsPerSecond: Long = 0,
    val uploadBitsPerSecond: Long = 0,
    val downloadBytes: Long = 0,
    val uploadBytes: Long = 0,
    val latencyMs: Long = 0,
    val serverName: String = "",
    val serverCountry: String = "",
    val error: String = "",
    val cancelled: Boolean = false,
    val done: Boolean = false,
)

internal fun completedSpeedTestCount(index: Int, total: Int, done: Boolean): Int {
    if (total <= 0) return 0
    return (index + if (done) 1 else 0).coerceIn(0, total)
}

interface SpeedTestNodeSession : AutoCloseable {
    suspend fun run(onSample: (SpeedTestSnapshot) -> Unit): SpeedTestSnapshot
    fun cancel()
}

class SpeedTestQueueRunner<T>(
    private val sessionFactory: (T) -> SpeedTestNodeSession,
    private val failureSnapshot: (T, Exception) -> SpeedTestSnapshot,
) {
    @Volatile
    private var currentSession: SpeedTestNodeSession? = null

    @Volatile
    private var isCancelled = false

    /**
     * Sequentially runs speed test sessions for [profiles] and emits live snapshots.
     * Stops immediately when [cancel] is invoked or the coroutine is cancelled.
     */
    suspend fun run(
        profiles: List<T>,
        onSample: (index: Int, total: Int, sample: SpeedTestSnapshot) -> Unit,
    ): List<SpeedTestSnapshot> {
        val results = ArrayList<SpeedTestSnapshot>(profiles.size)
        profiles.forEachIndexed { index, profile ->
            coroutineContext.ensureActive()
            if (isCancelled) throw CancellationException("SpeedTestQueueRunner cancelled")
            val session = sessionFactory(profile)
            currentSession = session
            if (isCancelled) {
                session.cancel()
                runCatching { session.close() }.onFailure { Logs.w(it) }
                currentSession = null
                throw CancellationException("SpeedTestQueueRunner cancelled")
            }
            try {
                results += session.run { onSample(index, profiles.size, it) }
            } catch (e: CancellationException) {
                session.cancel()
                throw e
            } catch (e: Exception) {
                val failed = failureSnapshot(profile, e)
                onSample(index, profiles.size, failed)
                results += failed
            } finally {
                runCatching { session.close() }.onFailure { Logs.w(it) }
                currentSession = null
            }
        }
        return results
    }

    /** Marks the queue cancelled and cancels whichever node session is currently active. */
    fun cancel() {
        isCancelled = true
        currentSession?.cancel()
    }

    companion object {
        const val STAGE_PENDING = "pending"
        const val STAGE_DISCOVERY = "discovery"
        const val STAGE_LATENCY = "latency"
        const val STAGE_DOWNLOAD = "download"
        const val STAGE_UPLOAD = "upload"
        const val STAGE_COMPLETE = "complete"
        const val STAGE_CANCELLED = "cancelled"
        const val STAGE_ERROR = "error"
    }
}

class AndroidSpeedTestSession(profile: ProxyEntity) : BoxInstance(profile), SpeedTestNodeSession {
    private val sessionLock = Any()

    @Volatile
    private var nativeSession: SpeedTestSession? = null

    @Volatile
    private var isCancelled = false

    override fun buildConfig() {
        config = buildConfig(profile, true)
    }

    override suspend fun loadConfig() = Unit

    override suspend fun run(onSample: (SpeedTestSnapshot) -> Unit): SpeedTestSnapshot {
        if (isCancelled) throw CancellationException("SpeedTest cancelled before init")
        processes = GuardedProcessPool { error ->
            Logs.w("SpeedTest plugin failed for ${profile.displayName()}: ${error.readableMessage}")
            cancel()
        }
        init()
        if (isCancelled) throw CancellationException("SpeedTest cancelled before plugin launch")
        launchExternal()
        if (processes.processCount > 0) delay(500)
        if (isCancelled) throw CancellationException("SpeedTest cancelled before native session")
        if (BuildConfig.DEBUG) Logs.d("SpeedTestSession starting for ${profile.displayName()}")

        val session = Libcore.newSpeedTestSession(
            profile.id.toString(),
            config.config,
            LocalResolverImpl,
            DataStore.speedTestMode,
            DataStore.speedTestTimeoutMs,
            DataStore.speedTestServerListURL,
            DataStore.speedTestFallbackServerListURL,
            DataStore.simpleDownloadURL,
        )
        // Atomically bind nativeSession only if cancel() has not already fired.
        val shouldAbort = synchronized(sessionLock) {
            if (isCancelled) {
                true
            } else {
                nativeSession = session
                false
            }
        }
        if (shouldAbort) {
            runCatching { session.close() }.onFailure { Logs.w(it) }
            throw CancellationException("SpeedTest cancelled during native session creation")
        }
        try {
            session.start()
            while (true) {
                coroutineContext.ensureActive()
                if (isCancelled) throw CancellationException("SpeedTest cancelled during sampling")
                val snapshot = session.result.toSnapshot()
                onSample(snapshot)
                if (snapshot.done) return snapshot
                delay(SAMPLE_INTERVAL_MS)
            }
        } catch (e: CancellationException) {
            session.cancel()
            throw e
        }
    }

    override fun cancel() {
        val activeSession = synchronized(sessionLock) {
            isCancelled = true
            nativeSession
        }
        activeSession?.cancel()
    }

    override fun close() {
        val sessionToClose = synchronized(sessionLock) {
            isCancelled = true
            val existing = nativeSession
            nativeSession = null
            existing
        }
        runCatching { sessionToClose?.close() }.onFailure { Logs.w(it) }
        super.close()
    }

    private fun SpeedTestResult.toSnapshot() = SpeedTestSnapshot(
        profileId = profile.id,
        profileName = profile.displayName(),
        mode = mode,
        stage = stage,
        downloadBitsPerSecond = downloadBitsPerSecond,
        uploadBitsPerSecond = uploadBitsPerSecond,
        downloadBytes = downloadBytes,
        uploadBytes = uploadBytes,
        latencyMs = latencyMs,
        serverName = serverName.orEmpty(),
        serverCountry = serverCountry.orEmpty(),
        error = error.orEmpty(),
        cancelled = cancelled,
        done = done,
    )

    private companion object {
        const val SAMPLE_INTERVAL_MS = 100L
    }
}
