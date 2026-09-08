package com.music.bitchord.download.smart

import android.content.Context
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.LastPlayed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Intelligent curation engine for Smart Downloads (YouTube Music's Offline Mixtape replica).
 * Discovers and aggregates high-affinity tracks tailored to the listener's tastes:
 * 1. Quick Picks / Supermix recommendations from YouTube Music
 * 2. Recent listening history & on-device played tracks
 * 3. Radio discovery batches based on recently played favorites
 */
object SmartDownloadCurator {

    suspend fun curateCandidates(context: Context, quota: Int): List<Song> = withContext(Dispatchers.IO) {
        val candidates = mutableListOf<Song>()
        val seenIds = mutableSetOf<String>()

        fun addSongs(songs: List<Song>) {
            for (song in songs) {
                if (candidates.size >= quota * 2) break
                val id = song.videoId
                if (id.isNotBlank() && id !in seenIds && !id.startsWith("content://") && !id.startsWith("file://")) {
                    seenIds.add(id)
                    candidates.add(song)
                }
            }
        }

        // 1. Personalized Quick Picks / Supermix (highest quality recommendations from YouTube Music)
        runCatching {
            YtMusicRepository.quickPicks().getOrNull()?.let { addSongs(it) }
        }

        // 2. Local play history from LastPlayed
        runCatching {
            LastPlayed.load()?.songs?.let { addSongs(it) }
        }

        // 3. YouTube Music account recents & history
        runCatching {
            YtMusicRepository.recents().getOrNull()?.let { addSongs(it) }
        }
        runCatching {
            YtMusicRepository.history().getOrNull()?.let { addSongs(it) }
        }

        // 4. Radio discoveries: for the top seeds, query radio to bring fresh music like YouTube Music Offline Mixtape
        val topSeeds = candidates.take(3)
        for (seed in topSeeds) {
            if (candidates.size >= quota) break
            runCatching {
                YtMusicRepository.radio(seed.videoId).getOrNull()?.let { addSongs(it) }
            }
        }

        candidates.take(quota)
    }
}
