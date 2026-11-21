package io.nekohasekai.sagernet.bg

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Schedules and executes periodic background subscription updates via WorkManager in `:bg`.
 */
object SubscriptionUpdater {

    private const val WORK_NAME = "SubscriptionUpdater"
    private const val MIN_PERIODIC_INTERVAL_MINUTES = 15L
    private const val MAX_INITIAL_DELAY_SECONDS = 60L
    private const val NOTIFICATION_ID_SUBSCRIPTION_UPDATE = 2

    /**
     * Reconfigures the periodic background subscription updater based on current group settings.
     *
     * Awaits cancellation before re-enqueuing so the new schedule cannot race with a pending
     * cancel IPC against `RemoteWorkManagerService`.
     */
    suspend fun reconfigureUpdater() {
        val remoteWorkManager = RemoteWorkManager.getInstance(app)
        withContext(Dispatchers.IO) {
            runCatching { remoteWorkManager.cancelUniqueWork(WORK_NAME).get() }
                .onFailure { Logs.w(it) }
        }

        val subscriptions = SagerDatabase.groupDao.subscriptions()
            .filter { it.subscription?.autoUpdate == true }
        if (subscriptions.isEmpty()) return

        // PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS requires at least 15 minutes.
        val minDelayMinutes = subscriptions
            .minOf { (it.subscription?.autoUpdateDelay ?: MIN_PERIODIC_INTERVAL_MINUTES.toInt()).toLong() }
            .coerceAtLeast(MIN_PERIODIC_INTERVAL_MINUTES)
        val nowSeconds = System.currentTimeMillis() / 1000L
        val minInitDelaySeconds = subscriptions.minOf { group ->
            val sub = group.subscription
            val intervalSeconds = (sub?.autoUpdateDelay ?: MIN_PERIODIC_INTERVAL_MINUTES.toInt())
                .toLong()
                .coerceAtLeast(MIN_PERIODIC_INTERVAL_MINUTES) * 60L
            val lastUpdatedSeconds = (sub?.lastUpdated ?: 0).toLong()
            (lastUpdatedSeconds + intervalSeconds - nowSeconds).coerceAtLeast(0L)
        }.coerceAtMost(MAX_INITIAL_DELAY_SECONDS)

        val request = PeriodicWorkRequest.Builder(
            UpdateTask::class.java,
            minDelayMinutes,
            TimeUnit.MINUTES,
        ).apply {
            if (minInitDelaySeconds > 0L) {
                setInitialDelay(minInitDelaySeconds, TimeUnit.SECONDS)
            }
        }.build()

        withContext(Dispatchers.IO) {
            runCatching {
                remoteWorkManager.enqueueUniquePeriodicWork(WORK_NAME, UPDATE, request).get()
            }.onFailure { Logs.w(it) }
        }
    }

    /**
     * Background worker executing due subscription updates inside the `:bg` process.
     */
    class UpdateTask(
        appContext: Context, params: WorkerParameters
    ) : CoroutineWorker(appContext, params) {

        private val notificationManager = NotificationManagerCompat.from(applicationContext)

        private val notification = NotificationCompat.Builder(applicationContext, "service-subscription")
            .setWhen(0)
            .setTicker(applicationContext.getString(R.string.forward_success))
            .setContentTitle(applicationContext.getString(R.string.subscription_update))
            .setSmallIcon(R.drawable.ic_service_active)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        override suspend fun doWork(): Result {
            var subscriptions = SagerDatabase.groupDao.subscriptions()
                .filter { it.subscription?.autoUpdate == true }
            if (!DataStore.serviceState.connected) {
                Logs.d("work: not connected")
                subscriptions = subscriptions.filter { it.subscription?.updateWhenConnectedOnly != true }
            }

            if (subscriptions.isEmpty()) return Result.success()

            // Always dismiss the progress notification even when the worker is cancelled or throws.
            try {
                for (profile in subscriptions) {
                    val subscription = profile.subscription ?: continue
                    val intervalSeconds = (subscription.autoUpdateDelay ?: MIN_PERIODIC_INTERVAL_MINUTES.toInt())
                        .toLong()
                        .coerceAtLeast(MIN_PERIODIC_INTERVAL_MINUTES) * 60L
                    val lastUpdatedSeconds = (subscription.lastUpdated ?: 0).toLong()
                    if ((System.currentTimeMillis() / 1000L - lastUpdatedSeconds) < intervalSeconds) {
                        Logs.d("work: not updating " + profile.displayName())
                        continue
                    }
                    Logs.d("work: updating " + profile.displayName())

                    notification.setContentText(
                        applicationContext.getString(
                            R.string.subscription_update_message, profile.displayName()
                        )
                    )
                    runCatching {
                        notificationManager.notify(
                            NOTIFICATION_ID_SUBSCRIPTION_UPDATE,
                            notification.build(),
                        )
                    }.onFailure { Logs.w(it) }

                    GroupUpdater.executeUpdate(profile, false)
                }
            } finally {
                notificationManager.cancel(NOTIFICATION_ID_SUBSCRIPTION_UPDATE)
            }

            return Result.success()
        }
    }

}