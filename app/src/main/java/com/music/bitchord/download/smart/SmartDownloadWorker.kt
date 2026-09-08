package com.music.bitchord.download.smart

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.DownloadTarget
import com.music.bitchord.download.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Background WorkManager worker that orchestrates YouTube Music-style Smart Downloads.
 * Runs periodically to ensure the user's offline mixtape is always fresh and cached.
 * Respects mobile data preference (allows downloading over cellular when configured).
 */
class SmartDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val enabled = AppSettings.smartDownloads.value
        if (!enabled) {
            Log.d(TAG, "Smart Downloads disabled in settings, skipping sync")
            return@withContext Result.success()
        }

        val allowMobile = AppSettings.smartDownloadsOverMobile.value || !AppSettings.wifiOnlyDownloads.value
        val isMetered = AppSettings.meteredConnection.value == true

        if (isMetered && !allowMobile) {
            Log.d(TAG, "Active network is metered and mobile downloads not permitted; waiting for Wi-Fi")
            return@withContext Result.retry()
        }

        val quota = AppSettings.smartDownloadsQuota.value.coerceIn(10, 500)
        Log.d(TAG, "Starting Smart Downloads sync (quota: $quota songs, allowMobile: $allowMobile)")

        val candidates = SmartDownloadCurator.curateCandidates(applicationContext, quota)
        if (candidates.isEmpty()) {
            Log.d(TAG, "No candidate songs found for Smart Downloads")
            return@withContext Result.success()
        }

        // Register the smart downloads collection so it displays in Library / Downloads
        Downloads.rememberCollection(
            DownloadTarget(
                id = SMART_COLLECTION_ID,
                title = "Smart Downloads",
                subtitle = "Offline Mixtape · Auto-curated",
                thumbnailUrl = candidates.firstOrNull()?.thumbnailUrl,
                playlist = true,
            ),
            candidates,
        )

        // Find songs not yet saved on disk
        val saved = Downloads.saved.value
        val missing = candidates.filter { it.videoId !in saved }

        Log.d(TAG, "Found ${candidates.size} curated tracks (${missing.size} new to download)")

        missing.forEach { song ->
            Downloads.enqueue(
                context = applicationContext,
                song = song,
                from = "Smart Downloads",
                forceAllowMobile = allowMobile,
            )
        }

        SmartDownloadStore.recordSmartDownloads(candidates.map { it.videoId })
        SmartDownloadStore.recordLastSync()

        // Prune old smart downloads if total exceeds quota
        val keepIds = candidates.map { it.videoId }.toSet()
        val pruned = SmartDownloadStore.pruneToQuota(applicationContext, quota, pinnedIds = keepIds)
        if (pruned.isNotEmpty()) {
            Log.d(TAG, "Pruned ${pruned.size} old smart downloads exceeding quota")
        }

        Result.success()
    }

    companion object {
        const val TAG = "SmartDownloadWorker"
        const val WORK_NAME = "bitchord_smart_downloads_sync"
        const val SMART_COLLECTION_ID = "smart_downloads"

        fun schedule(context: Context, enabled: Boolean, allowMobile: Boolean) {
            val workManager = WorkManager.getInstance(context)
            if (!enabled) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }

            val networkType = if (allowMobile) NetworkType.CONNECTED else NetworkType.UNMETERED
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<SmartDownloadWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun triggerNow(context: Context, allowMobile: Boolean) {
            val workManager = WorkManager.getInstance(context)
            val networkType = if (allowMobile) NetworkType.CONNECTED else NetworkType.UNMETERED
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .build()

            val request = OneTimeWorkRequestBuilder<SmartDownloadWorker>()
                .setConstraints(constraints)
                .build()

            workManager.enqueueUniqueWork(
                "${WORK_NAME}_now",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
