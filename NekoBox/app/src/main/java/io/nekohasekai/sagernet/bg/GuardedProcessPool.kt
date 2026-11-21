package io.nekohasekai.sagernet.bg

import android.os.Build
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.MainThread
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.utils.Commandline
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import libcore.Libcore
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.concurrent.thread

class GuardedProcessPool(private val onFatal: suspend (IOException) -> Unit) : CoroutineScope {
    companion object {
        private val pid by lazy {
            Class.forName("java.lang.ProcessManager\$ProcessImpl").getDeclaredField("pid")
                .apply { isAccessible = true }
        }
    }

    private inner class Guard(
        private val cmd: List<String>,
        private val env: Map<String, String> = mapOf()
    ) {
        private lateinit var process: Process

        private fun streamLogger(input: InputStream, logger: (String) -> Unit) = try {
            input.bufferedReader().forEachLine(logger)
        } catch (_: IOException) {
        }    // ignore

        fun start() {
            process = ProcessBuilder(cmd).directory(SagerNet.application.noBackupFilesDir).apply {
                environment().putAll(env)
            }.start()
        }

        /** Terminates an already-started process immediately if the pool closed mid-start. */
        fun destroyImmediately() {
            if (!::process.isInitialized) return
            runCatching {
                process.destroy()
                if (Build.VERSION.SDK_INT >= 26) {
                    process.destroyForcibly()
                }
            }.onFailure { Logs.w(it) }
        }

        @DelicateCoroutinesApi
        suspend fun looper(onRestartCallback: (suspend () -> Unit)?) {
            var running = true
            val cmdName = File(cmd.first()).nameWithoutExtension
            val exitChannel = Channel<Int>()
            try {
                while (true) {
                    thread(name = "stderr-$cmdName") {
                        streamLogger(process.errorStream) { Libcore.nekoLogPrintln("[$cmdName] $it") }
                    }
                    thread(name = "stdout-$cmdName") {
                        streamLogger(process.inputStream) { Libcore.nekoLogPrintln("[$cmdName] $it") }
                        // this thread also acts as a daemon thread for waitFor
                        runBlocking { exitChannel.send(process.waitFor()) }
                    }
                    val startTime = SystemClock.elapsedRealtime()
                    val exitCode = exitChannel.receive()
                    running = false
                    when {
                        SystemClock.elapsedRealtime() - startTime < 1000 -> throw IOException(
                            "$cmdName exits too fast (exit code: $exitCode)"
                        )

                        exitCode == 128 + OsConstants.SIGKILL -> Logs.w("$cmdName was killed")
                        else -> Logs.w(IOException("$cmdName unexpectedly exits with code $exitCode"))
                    }
                    Logs.i("restart process: ${Commandline.toString(cmd)} (last exit code: $exitCode)")
                    start()
                    running = true
                    onRestartCallback?.invoke()
                }
            } catch (e: IOException) {
                Logs.w("error occurred. stop guard: ${Commandline.toString(cmd)}")
                GlobalScope.launch(Dispatchers.Main) { onFatal(e) }
            } finally {
                if (running) withContext(NonCancellable) {  // clean-up cannot be cancelled
                    if (Build.VERSION.SDK_INT < 24) {
                        try {
                            Os.kill(pid.get(process) as Int, OsConstants.SIGTERM)
                        } catch (e: ErrnoException) {
                            if (e.errno != OsConstants.ESRCH) Logs.w(e)
                        } catch (e: ReflectiveOperationException) {
                            Logs.w(e)
                        }
                        if (withTimeoutOrNull(500) { exitChannel.receive() } != null) return@withContext
                    }
                    process.destroy()                       // kill the process
                    if (Build.VERSION.SDK_INT >= 26) {
                        if (withTimeoutOrNull(1000) { exitChannel.receive() } != null) return@withContext
                        process.destroyForcibly()           // Force to kill the process if it's still alive
                    }
                    exitChannel.receive()
                }                                           // otherwise process already exited, nothing to be done
            }
        }
    }

    override val coroutineContext = Dispatchers.Main.immediate + Job()
    private val stateLock = Any()
    @Volatile
    private var isClosed = false
    @Volatile
    var processCount = 0

    /**
     * Spawns and supervises an external plugin process unless this pool has already been
     * closed or cancelled. Thread-safe against concurrent [close] calls from test cancellation.
     */
    fun start(
        cmd: List<String>,
        env: MutableMap<String, String> = mutableMapOf(),
        onRestartCallback: (suspend () -> Unit)? = null
    ) {
        synchronized(stateLock) {
            if (isClosed || !isActive) return
        }
        Logs.i("start process: ${Commandline.toString(cmd)}")
        val guard = Guard(cmd, env)
        guard.start() // if start fails, IOException will be thrown directly
        val launched = synchronized(stateLock) {
            if (isClosed || !isActive) {
                false
            } else {
                launch { guard.looper(onRestartCallback) }
                processCount += 1
                true
            }
        }
        if (!launched) {
            // Pool closed concurrently with ProcessBuilder.start(): kill immediately to avoid orphans.
            guard.destroyImmediately()
        }
    }

    /** Marks the pool closed, cancels all supervisor coroutines, and joins cleanup on [scope]. */
    fun close(scope: CoroutineScope) {
        synchronized(stateLock) {
            isClosed = true
            cancel()
        }
        coroutineContext[Job]!!.also { job -> scope.launch { job.cancelAndJoin() } }
    }
}
