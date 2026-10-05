package com.music.bitchord.download.sync

import android.content.Context
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.model.Song
import com.music.bitchord.download.DownloadTarget
import com.music.bitchord.download.Downloads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sync engine that brings tracks from the user's YouTube Music account
 * (Liked Music auto-playlist VLLM, and user-created/saved playlists)
 * into BitChord's local offline download storage.
 */
object YtMusicDownloadSync {

    private const val TAG = "YtMusicDownloadSync"

    data class SyncStatus(
        val isSyncing: Boolean = false,
        val currentStep: String? = null,
        val discoveredCount: Int = 0,
        val enqueuedCount: Int = 0,
        val error: String? = null,
    )

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null

    /**
     * Whether the user is currently signed in to YouTube Music with an active session cookie.
     */
    val isAvailable: Boolean
        get() = !Innertube.cookie.isNullOrBlank()

    /**
     * Synchronizes the user's Liked Music (VLLM) from YouTube Music to local downloads.
     * Enqueues any songs not already present on disk.
     *
     * @param forceAllowMobile whether to download even if on cellular data with Wi-Fi only enabled.
     * @return the number of new songs enqueued.
     */
    suspend fun syncLikedMusic(
        context: Context,
        forceAllowMobile: Boolean = false,
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (!isAvailable) {
            return@withContext Result.failure(IllegalStateException("Sign in to YouTube Music to sync Liked Songs"))
        }

        _status.value = SyncStatus(isSyncing = true, currentStep = "Fetching Liked Music from YouTube Music...")
        try {
            val songsResult = YtMusicRepository.allSongs(YtMusicRepository.LIKED_MUSIC)
            val songs = songsResult.getOrThrow()
            if (songs.isEmpty()) {
                _status.value = SyncStatus(isSyncing = false, currentStep = "No liked songs found")
                return@withContext Result.success(0)
            }

            val savedIds = Downloads.saved.value
            val missing = songs.filter { it.videoId !in savedIds }

            val target = DownloadTarget(
                id = YtMusicRepository.LIKED_MUSIC,
                title = "Liked Music",
                subtitle = "YouTube Music",
                thumbnailUrl = songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl,
                playlist = true,
            )

            // Remember the collection under Downloads
            Downloads.rememberCollection(target, songs)
            if (missing.isNotEmpty()) {
                Downloads.markRequested(target.id, missing.map { it.videoId })
                Downloads.enqueueAll(context, missing, from = target.title, forceAllowMobile = forceAllowMobile)
            }

            Log.d(TAG, "Synced Liked Music: ${songs.size} discovered, ${missing.size} new enqueued")
            _status.value = SyncStatus(
                isSyncing = false,
                currentStep = "Synced ${missing.size} new tracks from Liked Music",
                discoveredCount = songs.size,
                enqueuedCount = missing.size,
            )
            Result.success(missing.size)
        } catch (e: CancellationException) {
            _status.value = SyncStatus(isSyncing = false)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Liked music sync failed: ${e.message}", e)
            _status.value = SyncStatus(isSyncing = false, error = e.message ?: "Failed to sync Liked Music")
            Result.failure(e)
        }
    }

    /**
     * Synchronizes all user playlists from YouTube Music to local downloads.
     */
    suspend fun syncPlaylists(
        context: Context,
        forceAllowMobile: Boolean = false,
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (!isAvailable) {
            return@withContext Result.failure(IllegalStateException("Sign in to YouTube Music to sync playlists"))
        }

        _status.value = SyncStatus(isSyncing = true, currentStep = "Fetching user playlists...")
        try {
            val playlists = YtMusicRepository.userPlaylists().getOrThrow()
            var totalEnqueued = 0
            var totalDiscovered = 0

            for (playlist in playlists) {
                val pId = playlist.browseId
                if (pId.isBlank()) continue
                _status.value = SyncStatus(
                    isSyncing = true,
                    currentStep = "Fetching ${playlist.title}...",
                    discoveredCount = totalDiscovered,
                    enqueuedCount = totalEnqueued,
                )

                val songs = YtMusicRepository.allSongs(pId).getOrElse { emptyList() }
                if (songs.isEmpty()) continue
                totalDiscovered += songs.size

                val savedIds = Downloads.saved.value
                val missing = songs.filter { it.videoId !in savedIds }

                val target = DownloadTarget(
                    id = pId,
                    title = playlist.title,
                    subtitle = "YouTube Music",
                    thumbnailUrl = playlist.thumbnailUrl ?: songs.firstOrNull()?.thumbnailUrl,
                    playlist = true,
                )

                Downloads.rememberCollection(target, songs)
                if (missing.isNotEmpty()) {
                    Downloads.markRequested(target.id, missing.map { it.videoId })
                    Downloads.enqueueAll(context, missing, from = playlist.title, forceAllowMobile = forceAllowMobile)
                    totalEnqueued += missing.size
                }
            }

            _status.value = SyncStatus(
                isSyncing = false,
                currentStep = "Synced $totalEnqueued new tracks across ${playlists.size} playlists",
                discoveredCount = totalDiscovered,
                enqueuedCount = totalEnqueued,
            )
            Result.success(totalEnqueued)
        } catch (e: CancellationException) {
            _status.value = SyncStatus(isSyncing = false)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Playlists sync failed: ${e.message}", e)
            _status.value = SyncStatus(isSyncing = false, error = e.message ?: "Failed to sync playlists")
            Result.failure(e)
        }
    }

    /**
     * Performs a complete sync of the user's YouTube Music source:
     * Liked Music + all user playlists.
     */
    fun syncAllAsync(context: Context, forceAllowMobile: Boolean = false, onComplete: ((Int) -> Unit)? = null) {
        syncJob?.cancel()
        syncJob = scope.launch {
            val app = context.applicationContext
            val likedCount = syncLikedMusic(app, forceAllowMobile).getOrDefault(0)
            val playlistCount = syncPlaylists(app, forceAllowMobile).getOrDefault(0)
            val total = likedCount + playlistCount
            _status.value = SyncStatus(
                isSyncing = false,
                currentStep = "YouTube Music Sync Complete: $total new downloads queued",
                enqueuedCount = total,
            )
            onComplete?.invoke(total)
        }
    }

    fun cancelSync() {
        syncJob?.cancel()
        syncJob = null
        _status.value = SyncStatus(isSyncing = false, currentStep = "Sync cancelled")
    }
}
