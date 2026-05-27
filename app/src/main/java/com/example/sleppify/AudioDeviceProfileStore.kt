package com.example.sleppify

import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import android.os.Build
import android.text.TextUtils
import android.util.Log

/**
 * Native Kotlin implementation of AudioDeviceProfileStore.
 * Manages EQ and Bass profiles for different audio outputs.
 */
object AudioDeviceProfileStore {
    private const val TAG = "AudioProfileStore"

    private const val GLOBAL_PROFILE_ID = "global_audio_profile"
    private const val BUILTIN_SPEAKER_PROFILE_ID = "builtin_speaker"
    private const val BUILTIN_EARPIECE_PROFILE_ID = "builtin_earpiece"
    
    private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
    private const val KEY_PROFILE_PREFIX = "device_profile_"
    private const val KEY_SELECTED_PRESET = "selected_preset"
    private const val KEY_USER_PRESET_BAND_PREFIX = "user_preset_band_"
    private const val KEY_PROFILE_CUSTOM_PRESET_NAME_PREFIX = "profile_custom_preset_name_"

    private const val PRESET_DEFAULT = "default"
    private const val PRESET_CUSTOM = "custom"
    private const val USER_PRESET_ID_PREFIX = "user_profile_"

    private val BAND_COUNT = AudioEffectsService.EQ_BAND_COUNT
    private const val FLOAT_TOLERANCE = 0.01f

    private const val PROFILE_DEFAULT_BASS_DB = 0f
    private const val PROFILE_DEFAULT_BASS_TYPE = AudioEffectsService.BASS_TYPE_NATURAL
    private const val PROFILE_DEFAULT_BASS_FREQUENCY_HZ = 62f
    private const val PROFILE_DEFAULT_ENABLED = false

    /**
     * Syncs the active profile values based on the currently connected audio output.
     */
    @JvmStatic
    fun syncActiveProfileForOutput(prefs: SharedPreferences, selectedOutput: AudioDeviceInfo?): Boolean {
        val targetProfileId = buildProfileId(selectedOutput)
        val currentProfileId = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)
        if (currentProfileId == targetProfileId) {
            if (!hasProfileData(prefs, targetProfileId)) {
                prefs.edit().apply {
                    writeDefaultValuesToProfile(this, targetProfileId)
                    writeDefaultValuesToActive(this)
                }.apply()
                return true
            }
            return false
        }

        prefs.edit().apply {
            if (!currentProfileId.isNullOrEmpty()) {
                copyActiveValuesToProfile(this, prefs, currentProfileId)
            }

            if (hasProfileData(prefs, targetProfileId)) {
                copyProfileValuesToActive(this, prefs, targetProfileId)
            } else {
                writeDefaultValuesToProfile(this, targetProfileId)
                writeDefaultValuesToActive(this)
            }

            putString(KEY_ACTIVE_PROFILE_ID, targetProfileId)
        }.apply()

        return true
    }

    @JvmStatic
    fun persistActiveValuesToCurrentProfile(prefs: SharedPreferences): Boolean {
        val currentId = prefs.getString(KEY_ACTIVE_PROFILE_ID, null) ?: return false
        prefs.edit().apply {
            copyActiveValuesToProfile(this, prefs, currentId)
        }.apply()
        return true
    }

    @JvmStatic
    fun restoreActiveValuesForOutput(prefs: SharedPreferences, selectedOutput: AudioDeviceInfo?): Boolean {
        val targetProfileId = buildProfileId(selectedOutput)

        if (!hasProfileData(prefs, targetProfileId)) {
            return false
        }

        val currentId = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)
        if (currentId == targetProfileId) {
            return false
        }

        prefs.edit().apply {
            copyProfileValuesToActive(this, prefs, targetProfileId)
            putString(KEY_ACTIVE_PROFILE_ID, targetProfileId)
        }.apply()
        return true
    }

    @JvmStatic
    fun getActiveProfileId(prefs: SharedPreferences, selectedOutput: AudioDeviceInfo?): String {
        return prefs.getString(KEY_ACTIVE_PROFILE_ID, null) ?: buildProfileId(selectedOutput)
    }

    @JvmStatic
    fun buildProfileId(selectedOutput: AudioDeviceInfo?): String {
        if (selectedOutput == null || !selectedOutput.isSink) return GLOBAL_PROFILE_ID

        val type = selectedOutput.type
        return when {
            isBluetoothType(type) -> {
                val unique = sanitize(uniqueOutputName(selectedOutput))
                if (unique.isNotEmpty()) "bluetooth_$unique" 
                else sanitizeProductName(selectedOutput).let { if (it.isNotEmpty()) "bluetooth_$it" else "bluetooth" }
            }
            isWiredType(type) -> {
                val unique = sanitize(uniqueOutputName(selectedOutput))
                if (unique.isNotEmpty()) "wired_$unique"
                else sanitizeProductName(selectedOutput).let { if (it.isNotEmpty()) "wired_$it" else "wired" }
            }
            type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> BUILTIN_EARPIECE_PROFILE_ID
            type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> BUILTIN_SPEAKER_PROFILE_ID
            else -> sanitizeProductName(selectedOutput).let { if (it.isNotEmpty()) "type_${type}_$it" else "type_$type" }
        }
    }

    // --- PRIVATE UTILS ---

    private fun hasProfileData(prefs: SharedPreferences, id: String): Boolean {
        return prefs.contains(profileKey(id, AudioEffectsService.KEY_ENABLED)) ||
               prefs.contains(profileKey(id, AudioEffectsService.KEY_BASS_DB)) ||
               prefs.contains(profileKey(id, AudioEffectsService.KEY_BASS_TYPE)) ||
               prefs.contains(profileKey(id, AudioEffectsService.KEY_BASS_FREQUENCY_HZ)) ||
               prefs.contains(profileKey(id, KEY_SELECTED_PRESET)) ||
               prefs.contains(profileCustomPresetNameKey(id)) ||
               hasAnyUserPresetBands(prefs, id) ||
               prefs.contains(profileBandKey(id, 0))
    }

    private fun areActiveValuesAlignedWithProfile(prefs: SharedPreferences, id: String): Boolean {
        if (!hasProfileData(prefs, id)) return false

        if (!sameFloat(prefs.getFloat(AudioEffectsService.KEY_BASS_DB, 0f), 
                       prefs.getFloat(profileKey(id, AudioEffectsService.KEY_BASS_DB), 0f))) return false
        
        if (prefs.getBoolean(AudioEffectsService.KEY_ENABLED, false) != 
            prefs.getBoolean(profileKey(id, AudioEffectsService.KEY_ENABLED), false)) return false

        if (prefs.getInt(AudioEffectsService.KEY_BASS_TYPE, AudioEffectsService.BASS_TYPE_NATURAL) != 
            prefs.getInt(profileKey(id, AudioEffectsService.KEY_BASS_TYPE), AudioEffectsService.BASS_TYPE_NATURAL)) return false

        if (!sameFloat(prefs.getFloat(AudioEffectsService.KEY_BASS_FREQUENCY_HZ, AudioEffectsService.BASS_FREQUENCY_DEFAULT_HZ),
                       prefs.getFloat(profileKey(id, AudioEffectsService.KEY_BASS_FREQUENCY_HZ), AudioEffectsService.BASS_FREQUENCY_DEFAULT_HZ))) return false

        val activePreset = prefs.getString(KEY_SELECTED_PRESET, PRESET_CUSTOM)
        val profilePreset = resolveProfilePresetId(prefs, id)
        if (activePreset != profilePreset) return false

        for (i in 0 until BAND_COUNT) {
            if (!sameFloat(prefs.getFloat(AudioEffectsService.bandDbKey(i), 0f),
                           resolveProfileBandValue(prefs, id, i, profilePreset))) return false
        }
        return true
    }

    private fun sameFloat(l: Float, r: Float) = Math.abs(l - r) <= FLOAT_TOLERANCE

    private fun copyActiveValuesToProfile(editor: SharedPreferences.Editor, prefs: SharedPreferences, id: String) {
        editor.putBoolean(profileKey(id, AudioEffectsService.KEY_ENABLED), prefs.getBoolean(AudioEffectsService.KEY_ENABLED, false))
        editor.putFloat(profileKey(id, AudioEffectsService.KEY_BASS_DB), prefs.getFloat(AudioEffectsService.KEY_BASS_DB, 0f))
        editor.putInt(profileKey(id, AudioEffectsService.KEY_BASS_TYPE), prefs.getInt(AudioEffectsService.KEY_BASS_TYPE, AudioEffectsService.BASS_TYPE_NATURAL))
        editor.putFloat(profileKey(id, AudioEffectsService.KEY_BASS_FREQUENCY_HZ), prefs.getFloat(AudioEffectsService.KEY_BASS_FREQUENCY_HZ, AudioEffectsService.BASS_FREQUENCY_DEFAULT_HZ))
        editor.putString(profileKey(id, KEY_SELECTED_PRESET), prefs.getString(KEY_SELECTED_PRESET, "custom"))

        for (i in 0 until BAND_COUNT) {
            editor.putFloat(profileBandKey(id, i), prefs.getFloat(AudioEffectsService.bandDbKey(i), 0f))
        }
    }

    private fun copyProfileValuesToActive(editor: SharedPreferences.Editor, prefs: SharedPreferences, id: String) {
        val presetId = resolveProfilePresetId(prefs, id)
        editor.putBoolean(AudioEffectsService.KEY_ENABLED, prefs.getBoolean(profileKey(id, AudioEffectsService.KEY_ENABLED), false))
        editor.putFloat(AudioEffectsService.KEY_BASS_DB, prefs.getFloat(profileKey(id, AudioEffectsService.KEY_BASS_DB), 0f))
        editor.putInt(AudioEffectsService.KEY_BASS_TYPE, prefs.getInt(profileKey(id, AudioEffectsService.KEY_BASS_TYPE), AudioEffectsService.BASS_TYPE_NATURAL))
        editor.putFloat(AudioEffectsService.KEY_BASS_FREQUENCY_HZ, prefs.getFloat(profileKey(id, AudioEffectsService.KEY_BASS_FREQUENCY_HZ), AudioEffectsService.BASS_FREQUENCY_DEFAULT_HZ))
        editor.putString(KEY_SELECTED_PRESET, presetId)

        for (i in 0 until BAND_COUNT) {
            editor.putFloat(AudioEffectsService.bandDbKey(i), resolveProfileBandValue(prefs, id, i, presetId))
        }
    }

    private fun copyProfileValuesToProfile(editor: SharedPreferences.Editor, prefs: SharedPreferences, src: String, dst: String) {
        editor.putBoolean(profileKey(dst, AudioEffectsService.KEY_ENABLED), prefs.getBoolean(profileKey(src, AudioEffectsService.KEY_ENABLED), false))
        editor.putFloat(profileKey(dst, AudioEffectsService.KEY_BASS_DB), prefs.getFloat(profileKey(src, AudioEffectsService.KEY_BASS_DB), 0f))
        editor.putInt(profileKey(dst, AudioEffectsService.KEY_BASS_TYPE), prefs.getInt(profileKey(src, AudioEffectsService.KEY_BASS_TYPE), AudioEffectsService.BASS_TYPE_NATURAL))
        editor.putFloat(profileKey(dst, AudioEffectsService.KEY_BASS_FREQUENCY_HZ), prefs.getFloat(profileKey(src, AudioEffectsService.KEY_BASS_FREQUENCY_HZ), AudioEffectsService.BASS_FREQUENCY_DEFAULT_HZ))
        
        val presetId = resolveProfilePresetId(prefs, src)
        editor.putString(profileKey(dst, KEY_SELECTED_PRESET), presetId)
        for (i in 0 until BAND_COUNT) {
            editor.putFloat(profileBandKey(dst, i), resolveProfileBandValue(prefs, src, i, presetId))
        }
    }

    private fun writeDefaultValuesToProfile(editor: SharedPreferences.Editor, id: String) {
        editor.putBoolean(profileKey(id, AudioEffectsService.KEY_ENABLED), PROFILE_DEFAULT_ENABLED)
        editor.putFloat(profileKey(id, AudioEffectsService.KEY_BASS_DB), PROFILE_DEFAULT_BASS_DB)
        editor.putInt(profileKey(id, AudioEffectsService.KEY_BASS_TYPE), PROFILE_DEFAULT_BASS_TYPE)
        editor.putFloat(profileKey(id, AudioEffectsService.KEY_BASS_FREQUENCY_HZ), PROFILE_DEFAULT_BASS_FREQUENCY_HZ)
        editor.putString(profileKey(id, KEY_SELECTED_PRESET), PRESET_DEFAULT)
        for (i in 0 until BAND_COUNT) editor.putFloat(profileBandKey(id, i), 0f)
    }

    private fun writeDefaultValuesToActive(editor: SharedPreferences.Editor) {
        editor.putBoolean(AudioEffectsService.KEY_ENABLED, PROFILE_DEFAULT_ENABLED)
        editor.putFloat(AudioEffectsService.KEY_BASS_DB, PROFILE_DEFAULT_BASS_DB)
        editor.putInt(AudioEffectsService.KEY_BASS_TYPE, PROFILE_DEFAULT_BASS_TYPE)
        editor.putFloat(AudioEffectsService.KEY_BASS_FREQUENCY_HZ, PROFILE_DEFAULT_BASS_FREQUENCY_HZ)
        editor.putString(KEY_SELECTED_PRESET, PRESET_DEFAULT)
        for (i in 0 until BAND_COUNT) editor.putFloat(AudioEffectsService.bandDbKey(i), 0f)
    }

    private fun resolveProfileBandValue(prefs: SharedPreferences, id: String, idx: Int, presetId: String? = null): Float {
        val resolvedId = presetId ?: resolveProfilePresetId(prefs, id)
        val fallbackEligible = useUserPresetFallback(prefs, id, resolvedId)
        val preferUser = fallbackEligible && preferUserSnapshot(prefs, id)

        if (preferUser) {
            val key = userPresetBandKey(id, idx)
            if (prefs.contains(key)) return prefs.getFloat(key, 0f)
        }

        val directKey = profileBandKey(id, idx)
        if (prefs.contains(directKey)) return prefs.getFloat(directKey, 0f)

        if (fallbackEligible) {
            val key = userPresetBandKey(id, idx)
            if (prefs.contains(key)) return prefs.getFloat(key, 0f)
        }
        return 0f
    }

    private fun useUserPresetFallback(prefs: SharedPreferences, id: String, presetId: String?): Boolean {
        val resolved = presetId ?: resolveProfilePresetId(prefs, id)
        if (resolved.isNotEmpty()) {
            if (resolved.startsWith(USER_PRESET_ID_PREFIX)) return true
            if (PRESET_CUSTOM == resolved) return prefs.contains(profileCustomPresetNameKey(id)) || hasAnyUserPresetBands(prefs, id)
            return false
        }
        return hasAnyUserPresetBands(prefs, id)
    }

    private fun resolveProfilePresetId(prefs: SharedPreferences, id: String): String {
        prefs.getString(profileKey(id, KEY_SELECTED_PRESET), null)?.let { return it }
        if (hasAnyUserPresetBands(prefs, id)) {
            return if (prefs.contains(profileCustomPresetNameKey(id))) userPresetIdForProfile(id) else PRESET_CUSTOM
        }
        return PRESET_CUSTOM
    }

    private fun hasAnyUserPresetBands(prefs: SharedPreferences, id: String) = (0 until BAND_COUNT).any { prefs.contains(userPresetBandKey(id, it)) }

    private fun preferUserSnapshot(prefs: SharedPreferences, id: String): Boolean {
        if (!hasAnyUserPresetBands(prefs, id)) return false
        if (!hasAnyDirectProfileBands(prefs, id)) return false
        if (!isProfileBandsFlat(prefs, id)) return false
        return hasNonFlatUserPresetBands(prefs, id)
    }

    private fun hasAnyDirectProfileBands(prefs: SharedPreferences, id: String) = (0 until BAND_COUNT).any { prefs.contains(profileBandKey(id, it)) }

    private fun isProfileBandsFlat(prefs: SharedPreferences, id: String): Boolean {
        for (i in 0 until BAND_COUNT) {
            val key = profileBandKey(id, i)
            if (prefs.contains(key) && Math.abs(prefs.getFloat(key, 0f)) > FLOAT_TOLERANCE) return false
        }
        return true
    }

    private fun hasNonFlatUserPresetBands(prefs: SharedPreferences, id: String): Boolean {
        for (i in 0 until BAND_COUNT) {
            val key = userPresetBandKey(id, i)
            if (prefs.contains(key) && Math.abs(prefs.getFloat(key, 0f)) > FLOAT_TOLERANCE) return true
        }
        return false
    }

    private fun findExistingAliasProfileId(prefs: SharedPreferences, targetId: String, info: AudioDeviceInfo?): String? {
        if (info == null) return null
        val type = info.type
        val product = sanitizeProductName(info)

        if (isBluetoothType(type)) {
            if (product.isNotEmpty()) {
                val alias = "bluetooth_$product"
                if (alias != targetId && hasProfileData(prefs, alias)) return alias
            }
            if ("bluetooth" != targetId && hasProfileData(prefs, "bluetooth")) return "bluetooth"
        }
        if (isWiredType(type)) {
            if (product.isNotEmpty()) {
                val alias = "wired_$product"
                if (alias != targetId && hasProfileData(prefs, alias)) return alias
            }
            if ("wired" != targetId && hasProfileData(prefs, "wired")) return "wired"
        }
        val typeOnly = "type_$type"
        if (typeOnly != targetId && hasProfileData(prefs, typeOnly)) return typeOnly
        if (GLOBAL_PROFILE_ID != targetId && hasProfileData(prefs, GLOBAL_PROFILE_ID)) return GLOBAL_PROFILE_ID

        return null
    }

    private fun sanitizeProductName(info: AudioDeviceInfo): String = sanitize(info.productName?.toString()?.trim())

    private fun uniqueOutputName(info: AudioDeviceInfo): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.address?.trim()?.let { if (it.isNotEmpty() && it != "00:00:00:00:00:00") return it }
        }
        return info.productName?.toString()?.trim() ?: ""
    }

    private fun sanitize(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val lower = raw.lowercase()
        return lower.map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }
            .joinToString("")
            .replace(Regex("_+"), "_")
            .removePrefix("_")
            .removeSuffix("_")
    }

    @JvmStatic
    fun selectPreferredOutput(manager: android.media.AudioManager?): AudioDeviceInfo? {
        if (manager == null) return null
        val outputs = manager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        if (outputs == null || outputs.isEmpty()) return null

        var bluetooth: AudioDeviceInfo? = null
        var wired: AudioDeviceInfo? = null
        var speaker: AudioDeviceInfo? = null
        var earpiece: AudioDeviceInfo? = null
        var fallback: AudioDeviceInfo? = null

        for (output in outputs) {
            if (!output.isSink) continue
            if (fallback == null) fallback = output

            val type = output.type
            when {
                isBluetoothType(type) && bluetooth == null -> bluetooth = output
                isWiredType(type) && wired == null -> wired = output
                (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE) && speaker == null -> speaker = output
                type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE && earpiece == null -> earpiece = output
            }
        }
        return bluetooth ?: wired ?: speaker ?: earpiece ?: fallback
    }

    private fun profileBandKey(id: String, idx: Int) = profileKey(id, AudioEffectsService.bandDbKey(idx))
    private fun userPresetBandKey(id: String, idx: Int) = "$KEY_USER_PRESET_BAND_PREFIX${id}_$idx"
    private fun profileCustomPresetNameKey(id: String) = "$KEY_PROFILE_CUSTOM_PRESET_NAME_PREFIX$id"
    private fun userPresetIdForProfile(id: String) = "$USER_PRESET_ID_PREFIX$id"
    private fun profileKey(id: String, base: String) = "$KEY_PROFILE_PREFIX${id}_$base"

    @JvmStatic
    fun isBluetoothType(type: Int) = type in setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST
    )

    @JvmStatic
    fun isWiredType(type: Int) = type in setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE
    )
}
