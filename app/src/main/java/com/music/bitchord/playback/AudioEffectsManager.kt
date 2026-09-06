package com.music.bitchord.playback

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EqualizerBand(
    val index: Short,
    val centerFreqHz: Int,
    val minLevelMb: Short,
    val maxLevelMb: Short,
    val levelMb: Short,
)

/**
 * Manages device hardware equalizer, bass boost, and virtualizer audio effects
 * attached directly to the playback audio session.
 */
object AudioEffectsManager {
    private const val TAG = "AudioEffectsManager"
    private const val PREFS_NAME = "bitchord_audio_effects"
    private const val KEY_ENABLED = "eq_enabled"
    private const val KEY_PRESET = "eq_preset"
    private const val KEY_BAND_PREFIX = "eq_band_"
    private const val KEY_BASS_BOOST = "bass_boost_strength"
    private const val KEY_VIRTUALIZER = "virtualizer_strength"

    private var prefs: SharedPreferences? = null
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var currentSessionId: Int = 0

    private val _isSupported = MutableStateFlow(true)
    val isSupported = _isSupported.asStateFlow()

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled = _isEnabled.asStateFlow()

    private val _presetNames = MutableStateFlow<List<String>>(emptyList())
    val presetNames = _presetNames.asStateFlow()

    private val _currentPreset = MutableStateFlow<Short>(-1)
    val currentPreset = _currentPreset.asStateFlow()

    private val _bands = MutableStateFlow<List<EqualizerBand>>(emptyList())
    val bands = _bands.asStateFlow()

    private val _bassBoostStrength = MutableStateFlow<Short>(0)
    val bassBoostStrength = _bassBoostStrength.asStateFlow()

    private val _virtualizerStrength = MutableStateFlow<Short>(0)
    val virtualizerStrength = _virtualizerStrength.asStateFlow()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadSavedState()
        }
    }

    private fun loadSavedState() {
        val p = prefs ?: return
        _isEnabled.value = p.getBoolean(KEY_ENABLED, false)
        _currentPreset.value = p.getInt(KEY_PRESET, -1).toShort()
        _bassBoostStrength.value = p.getInt(KEY_BASS_BOOST, 0).toShort()
        _virtualizerStrength.value = p.getInt(KEY_VIRTUALIZER, 0).toShort()
    }

    fun attachSession(sessionId: Int, context: Context? = null) {
        if (sessionId <= 0) return
        context?.let { init(it) }

        release()
        currentSessionId = sessionId

        try {
            val eq = Equalizer(0, sessionId).also { equalizer = it }
            val bb = BassBoost(0, sessionId).also { bassBoost = it }
            val virt = Virtualizer(0, sessionId).also { virtualizer = it }

            _isSupported.value = true

            // Read presets
            val presets = mutableListOf<String>()
            val numPresets = eq.numberOfPresets.toInt()
            for (i in 0 until numPresets) {
                runCatching { eq.getPresetName(i.toShort()) }
                    .onSuccess { presets.add(it) }
                    .onFailure { presets.add("Preset $i") }
            }
            _presetNames.value = presets

            // Read bands
            val bandList = mutableListOf<EqualizerBand>()
            val numBands = eq.numberOfBands.toInt()
            val levelRange = runCatching { eq.bandLevelRange }.getOrDefault(shortArrayOf(-1500, 1500))
            val minMb = levelRange.getOrElse(0) { -1500 }
            val maxMb = levelRange.getOrElse(1) { 1500 }

            val savedPreset = prefs?.getInt(KEY_PRESET, -1)?.toShort() ?: -1
            if (savedPreset >= 0 && savedPreset < numPresets) {
                runCatching { eq.usePreset(savedPreset) }
                _currentPreset.value = savedPreset
            }

            for (i in 0 until numBands) {
                val bandIndex = i.toShort()
                val centerFreq = runCatching { eq.getCenterFreq(bandIndex) / 1000 }.getOrDefault(0) // convert to Hz
                val savedLevel = prefs?.getInt("$KEY_BAND_PREFIX$i", Int.MIN_VALUE)
                val level = if (savedLevel != null && savedLevel != Int.MIN_VALUE) {
                    val coerced = savedLevel.coerceIn(minMb.toInt(), maxMb.toInt()).toShort()
                    runCatching { eq.setBandLevel(bandIndex, coerced) }
                    coerced
                } else {
                    runCatching { eq.getBandLevel(bandIndex) }.getOrDefault(0)
                }
                bandList.add(EqualizerBand(bandIndex, centerFreq, minMb, maxMb, level))
            }
            _bands.value = bandList

            // Bass boost
            val savedBb = prefs?.getInt(KEY_BASS_BOOST, 0)?.toShort() ?: 0
            if (bb.strengthSupported) {
                runCatching { bb.setStrength(savedBb) }
                _bassBoostStrength.value = savedBb
            }

            // Virtualizer
            val savedVirt = prefs?.getInt(KEY_VIRTUALIZER, 0)?.toShort() ?: 0
            if (virt.strengthSupported) {
                runCatching { virt.setStrength(savedVirt) }
                _virtualizerStrength.value = savedVirt
            }

            // Apply enabled state
            val isEqOn = prefs?.getBoolean(KEY_ENABLED, false) ?: false
            _isEnabled.value = isEqOn
            eq.enabled = isEqOn
            bb.enabled = isEqOn && savedBb > 0
            virt.enabled = isEqOn && savedVirt > 0

        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach Equalizer to session $sessionId", e)
            _isSupported.value = false
        }
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()

        runCatching { equalizer?.enabled = enabled }
        val bbStrength = _bassBoostStrength.value
        runCatching { bassBoost?.enabled = enabled && bbStrength > 0 }
        val virtStrength = _virtualizerStrength.value
        runCatching { virtualizer?.enabled = enabled && virtStrength > 0 }
    }

    fun setPreset(presetIndex: Short) {
        _currentPreset.value = presetIndex
        prefs?.edit()?.putInt(KEY_PRESET, presetIndex.toInt())?.apply()

        val eq = equalizer ?: return
        if (presetIndex in 0 until eq.numberOfPresets) {
            runCatching {
                eq.usePreset(presetIndex)
                // Refresh band levels
                val updated = _bands.value.map { band ->
                    val newLevel = runCatching { eq.getBandLevel(band.index) }.getOrDefault(band.levelMb)
                    prefs?.edit()?.putInt("$KEY_BAND_PREFIX${band.index}", newLevel.toInt())?.apply()
                    band.copy(levelMb = newLevel)
                }
                _bands.value = updated
            }
        }
    }

    fun setBandLevel(bandIndex: Short, levelMb: Short) {
        _currentPreset.value = -1 // custom
        prefs?.edit()?.putInt(KEY_PRESET, -1)?.apply()
        prefs?.edit()?.putInt("$KEY_BAND_PREFIX$bandIndex", levelMb.toInt())?.apply()

        runCatching { equalizer?.setBandLevel(bandIndex, levelMb) }

        _bands.value = _bands.value.map {
            if (it.index == bandIndex) it.copy(levelMb = levelMb) else it
        }
    }

    fun setBassBoost(strength: Short) {
        val coerced = strength.coerceIn(0, 1000)
        _bassBoostStrength.value = coerced
        prefs?.edit()?.putInt(KEY_BASS_BOOST, coerced.toInt())?.apply()

        val bb = bassBoost ?: return
        runCatching {
            if (bb.strengthSupported) {
                bb.setStrength(coerced)
                bb.enabled = _isEnabled.value && coerced > 0
            }
        }
    }

    fun setVirtualizer(strength: Short) {
        val coerced = strength.coerceIn(0, 1000)
        _virtualizerStrength.value = coerced
        prefs?.edit()?.putInt(KEY_VIRTUALIZER, coerced.toInt())?.apply()

        val virt = virtualizer ?: return
        runCatching {
            if (virt.strengthSupported) {
                virt.setStrength(coerced)
                virt.enabled = _isEnabled.value && coerced > 0
            }
        }
    }

    fun openSystemEqualizer(context: Context) {
        val sessionId = if (currentSessionId > 0) currentSessionId else 0
        val pkg = context.packageName

        // Broadcast session open first
        context.sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, pkg)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            }
        )

        val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, pkg)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        runCatching { context.startActivity(intent) }.onFailure {
            runCatching {
                context.startActivity(Intent(android.provider.Settings.ACTION_SOUND_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }.onFailure {
                Toast.makeText(context, "No external system equalizer found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun release() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        equalizer = null
        bassBoost = null
        virtualizer = null
    }
}
