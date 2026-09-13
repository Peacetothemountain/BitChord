package com.music.bitchord.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.music.bitchord.data.DebugLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "DownloadQueueWorker started draining background queue")
        val semaphore = Semaphore(CONCURRENT_WORKERS)

        coroutineScope {
            while (true) {
                val song = Downloads.takeNext() ?: break
                launch {
                    semaphore.withPermit {
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
