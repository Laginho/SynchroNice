package synchronice.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

internal const val LARGE_FILE_BYTES = 10L * 1024 * 1024

internal fun scheduleDelivery(context: Context) {
    val request = OneTimeWorkRequestBuilder<DeliveryWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .apply {
            val state = (context.applicationContext as SynchroNiceApplication).state
            if (Build.VERSION.SDK_INT >= 31 && !state.hasLargePending()) {
                setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
        }
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork("pending-delivery", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
}

internal class DeliveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val state = (applicationContext as SynchroNiceApplication).state
        if (!state.hasPending()) return Result.success()
        var foreground = false
        var lastPercent = -1
        return try {
            state.retryPending(
                onFileProgress = { percent ->
                    if (foreground && percent != lastPercent) {
                        lastPercent = percent
                        if (Build.VERSION.SDK_INT < 33 || applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                            manager().notify(1, notification(percent))
                        }
                    }
                },
                onFileStart = { size ->
                    if (size >= LARGE_FILE_BYTES && !foreground) {
                        setForegroundAsync(foregroundInfo(0)).get()
                        foreground = true
                    }
                },
            )
            if (state.hasPending()) Result.retry() else Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun manager(): NotificationManager = applicationContext.getSystemService(NotificationManager::class.java)

    private fun notification(percent: Int): Notification {
        val channel = "delivery-progress"
        manager().createNotificationChannel(NotificationChannel(channel, "Envio de arquivos", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(applicationContext, channel)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Enviando para o PC")
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .build()
    }

    private fun foregroundInfo(percent: Int) = ForegroundInfo(
        1, notification(percent), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
}
