package com.music.bitchord.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.music.bitchord.R
import com.music.bitchord.data.DebugLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Background WorkManager worker that drains the download queue when [DownloadService]
 * cannot be started as a foreground service (e.g. Android 14+ background launch restrictions)
 * or when [DownloadService] was stopped by the system while downloads were still pending.
 */
class DownloadQueueWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createChannel()
        val notification = buildNotification()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Songs being saved to your Music folder"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle("Downloading songs")
            .setContentText("Saving to your Music folder in background")
            .setProgress(100, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "DownloadQueueWorker started draining background queue")
        Downloads.init(applicationContext)

        if (!Downloads.busy()) {
            Log.d(TAG, "DownloadQueueWorker found empty queue, finishing")
            return@withContext Result.success()
        }

        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            Log.w(TAG, "Could not set foreground info for worker: ${e.message}")
        }

        coroutineScope {
            repeat(CONCURRENT_WORKERS) {
                launch {
                    while (true) {
                        val song = Downloads.takeNext() ?: break
                        val job = launch {
                            try {
                                Downloads.run(applicationContext, song)
                            } finally {
                                Downloads.onIdle(song.videoId)
                            }
                        }
                        Downloads.onRunning(song.videoId, job)
                        job.join()
                    }
                }
            }
        }

        Log.d(TAG, "DownloadQueueWorker finished draining background queue")
        Result.success()
    }

    companion object {
        private const val TAG = "DownloadQueueWorker"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 0x8175
        private const val WORK_NAME = "bitchord_download_queue_drain"
        private const val CONCURRENT_WORKERS = 2

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<DownloadQueueWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
            Log.d(TAG, "Enqueued DownloadQueueWorker via WorkManager")
        }
    }
}
