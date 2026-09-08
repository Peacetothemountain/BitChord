package com.music.bitchord.download.smart

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.download.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Persists the set of videoIds downloaded automatically via Smart Downloads.
 * Differentiates smart downloads from manual downloads so the curator can safely
 * prune old smart downloads when the quota is reached without touching manual downloads.
 */
object SmartDownloadStore {

    private const val PREFS_NAME = "bitchord_smart_downloads"
    private const val KEY_SMART_IDS = "smart_download_ids"
    private const val KEY_LAST_SYNC = "last_sync_timestamp"

    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = SetSerializer(String.serializer())

    private val _smartIds = MutableStateFlow<Set<String>>(emptySet())
    val smartIds: StateFlow<Set<String>> = _smartIds.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SMART_IDS, null)
        val ids = if (raw != null) {
            runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptySet())
        } else {
            emptySet()
        }
        _smartIds.value = ids
    }

    fun getSmartDownloadIds(): Set<String> = _smartIds.value

    fun recordSmartDownloads(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val updated = _smartIds.value + ids
        _smartIds.value = updated
        persist(updated)
    }

    fun removeSmartDownloads(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val updated = _smartIds.value - ids.toSet()
        _smartIds.value = updated
        persist(updated)
    }

    fun recordLastSync(timestampMs: Long = System.currentTimeMillis()) {
        if (!::prefs.isInitialized) return
        prefs.edit().putLong(KEY_LAST_SYNC, timestampMs).apply()
    }

    fun getLastSync(): Long = if (::prefs.isInitialized) prefs.getLong(KEY_LAST_SYNC, 0L) else 0L

    /**
     * If smart downloads exceed [quota], evicts the oldest entries that are not in [pinnedIds].
     * Returns the list of videoIds deleted.
     */
    suspend fun pruneToQuota(context: Context, quota: Int, pinnedIds: Set<String> = emptySet()): List<String> = withContext(Dispatchers.IO) {
        val current = _smartIds.value.toList()
        if (current.size <= quota) return@withContext emptyList()

        val excessCount = current.size - quota
        val eligibleToPrune = current.filter { it !in pinnedIds }
        val toPrune = eligibleToPrune.take(excessCount)

        toPrune.forEach { videoId ->
            Downloads.delete(context, videoId)
        }
        removeSmartDownloads(toPrune)
        toPrune
    }

    private fun persist(ids: Set<String>) {
        if (!::prefs.isInitialized) return
        val encoded = runCatching { json.encodeToString(serializer, ids) }.getOrNull() ?: return
        prefs.edit().putString(KEY_SMART_IDS, encoded).apply()
    }
}
