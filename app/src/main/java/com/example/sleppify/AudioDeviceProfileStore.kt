package com.example.sleppify

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.os.Build
import android.text.TextUtils
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Native Kotlin implementation of AudioDeviceProfileStore.
 * Manages EQ and Bass profiles for different audio outputs with persistent,
 * hardware-based identification for Bluetooth and wired devices.
 */
object AudioDeviceProfileStore {
    private const val TAG = "AudioProfileStore"

    private const val GLOBAL_PROFILE_ID = "global_audio_profile"
    private const val BUILTIN_SPEAKER_PROFILE_ID = "builtin_speaker"
    private const val BUILTIN_EARPIECE_PROFILE_ID = "builtin_earpiece"
    
    const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
    private const val KEY_PROFILE_PREFIX = "device_profile_"
    private const val KEY_SELECTED_PRESET = "selected_preset"
    private const val KEY_USER_PRESET_BAND_PREFIX = "user_preset_band_"
    private const val KEY_PROFILE_CUSTOM_PRESET_NAME_PREFIX = "profile_custom_preset_name_"

    // Registry prefix for device mapping (hardware MAC, fingerprint, aliases)
    private const val KEY_REG_MAC_PREFIX = "bt_reg_mac_"
    private const val KEY_REG_FP_PREFIX = "bt_reg_fp_"
    private const val KEY_REG_NAME_PREFIX = "bt_reg_name_"

    private const val PRESET_DEFAULT = "default"
    private const val PRESET_CUSTOM = "custom"
    private const val USER_PRESET_ID_PREFIX = "user_profile_"

    private const val KEY_USER_PRESET_BASS_DB_PREFIX = "user_preset_bass_db_"
    private const val KEY_USER_PRESET_BASS_FREQ_PREFIX = "user_preset_bass_freq_"
    private const val KEY_USER_PRESET_BASS_TYPE_PREFIX = "user_preset_bass_type_"

    private val BAND_COUNT = AudioEffectsService.EQ_BAND_COUNT
    private const val FLOAT_TOLERANCE = 0.01f

    private const val PROFILE_DEFAULT_BASS_DB = 0f
    private const val PROFILE_DEFAULT_BASS_TYPE = AudioEffectsService.BASS_TYPE_NATURAL
    private const val PROFILE_DEFAULT_BASS_FREQUENCY_HZ = 62f
    private const val PROFILE_DEFAULT_ENABLED = true

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var a2dpProxy: android.bluetooth.BluetoothProfile? = null
    @Volatile
    private var headsetProxy: android.bluetooth.BluetoothProfile? = null

    @JvmStatic
    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        ensureBluetoothProxies(app)
    }

    @JvmStatic
    fun ensureBluetoothProxies(context: Context) {
        val app = context.applicationContext
        if (appContext == null) appContext = app
        if (a2dpProxy != null && headsetProxy != null) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(app, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return
                }
            }
            val bm = app.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            val adapter = bm?.adapter ?: android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            if (adapter != null && adapter.isEnabled) {
                if (a2dpProxy == null) {
                    adapter.getProfileProxy(app, object : android.bluetooth.BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
                            if (profile == android.bluetooth.BluetoothProfile.A2DP) a2dpProxy = proxy
                        }
                        override fun onServiceDisconnected(profile: Int) {
                            if (profile == android.bluetooth.BluetoothProfile.A2DP) a2dpProxy = null
                        }
                    }, android.bluetooth.BluetoothProfile.A2DP)
                }
                if (headsetProxy == null) {
                    adapter.getProfileProxy(app, object : android.bluetooth.BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
                            if (profile == android.bluetooth.BluetoothProfile.HEADSET) headsetProxy = proxy
                        }
                        override fun onServiceDisconnected(profile: Int) {
                            if (profile == android.bluetooth.BluetoothProfile.HEADSET) headsetProxy = null
                        }
                    }, android.bluetooth.BluetoothProfile.HEADSET)
                }
            }
        } catch (_: Throwable) {}
    }


    /**
     * Syncs the active profile values based on the currently connected audio output.
     */
    @JvmStatic
    @JvmOverloads
    fun syncActiveProfileForOutput(
        prefs: SharedPreferences,
        selectedOutput: AudioDeviceInfo?,
        context: Context? = null
    ): Boolean {
        val targetProfileId = buildProfileId(selectedOutput, context)
        val currentProfileId = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)

        Log.d(TAG, "syncProfile: current=$currentProfileId → target=$targetProfileId product=${selectedOutput?.productName}")

        if (currentProfileId == targetProfileId) {
            if (!hasProfileData(prefs, targetProfileId)) {
                Log.d(TAG, "syncProfile: same ID, no data → initializing profile")
                val currentEnabled = prefs.getBoolean(AudioEffectsService.KEY_ENABLED, false)
                prefs.edit().apply {
                    writeDefaultValuesToProfile(this, targetProfileId, currentEnabled)
                    writeDefaultValuesToActive(this, currentEnabled)
                    recordDeviceRegistration(this, targetProfileId, selectedOutput, context)
                }.apply()
                return true
            }
            prefs.edit().apply {
                recordDeviceRegistration(this, targetProfileId, selectedOutput, context)
            }.apply()
            return false
        }

        Log.d(TAG, "syncProfile: SWITCHING from $currentProfileId to $targetProfileId")

        val currentEnabled = prefs.getBoolean(AudioEffectsService.KEY_ENABLED, false)

        prefs.edit().apply {
            if (!currentProfileId.isNullOrEmpty()) {
                copyActiveValuesToProfile(this, prefs, currentProfileId)
            }

            if (hasProfileData(prefs, targetProfileId)) {
                copyProfileValuesToActive(this, prefs, targetProfileId)
            } else {
                val alias = findExistingAliasProfileId(prefs, targetProfileId, selectedOutput, context)
                if (alias != null) {
                    copyProfileValuesToProfile(this, prefs, alias, targetProfileId)
                    copyProfileValuesToActive(this, prefs, targetProfileId)
                } else {
                    writeDefaultValuesToProfile(this, targetProfileId, currentEnabled)
                    writeDefaultValuesToActive(this, currentEnabled)
                }
            }

            putString(KEY_ACTIVE_PROFILE_ID, targetProfileId)
            recordDeviceRegistration(this, targetProfileId, selectedOutput, context)
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
    @JvmOverloads
    fun restoreActiveValuesForOutput(
        prefs: SharedPreferences,
        selectedOutput: AudioDeviceInfo?,
        context: Context? = null
    ): Boolean {
        return syncActiveProfileForOutput(prefs, selectedOutput, context)
    }

    @JvmStatic
    @JvmOverloads
    fun getActiveProfileId(
        prefs: SharedPreferences,
        selectedOutput: AudioDeviceInfo?,
        context: Context? = null
    ): String {
        return prefs.getString(KEY_ACTIVE_PROFILE_ID, null) ?: buildProfileId(selectedOutput, context)
    }

    @JvmStatic
    @JvmOverloads
    fun buildProfileId(selectedOutput: AudioDeviceInfo?, context: Context? = null): String {
        if (selectedOutput == null || !selectedOutput.isSink) return GLOBAL_PROFILE_ID

        val type = selectedOutput.type
        return when {
            isBluetoothType(type) -> {
                // Tier 1: Hardware MAC address (stable, completely independent of device name)
                val mac = resolveBluetoothMac(selectedOutput, context)
                if (mac.isNotEmpty()) {
                    "bluetooth_mac_$mac"
                } else {
                    val ctx = context ?: appContext
                    val prefs = ctx?.getSharedPreferences(AudioEffectsService.PREFS_NAME, Context.MODE_PRIVATE)
                    val prodName = sanitizeProductName(selectedOutput)
                    val fp = buildAudioHardwareFingerprint(selectedOutput)

                    // Tier 1.5: If we already have a registered canonical hardware MAC profile for this device,
                    // reuse it so we don't flap to a temporary name-based profile ID while Bluetooth proxies connect.
                    val registeredMacFromFp = if (fp.isNotEmpty()) prefs?.getString("$KEY_REG_FP_PREFIX$fp", null) else null
                    val registeredMacFromName = if (prodName.isNotEmpty() && !isGenericBluetoothName(prodName)) {
                        prefs?.getString("$KEY_REG_NAME_PREFIX$prodName", null)
                    } else null

                    val canonicalCandidate = registeredMacFromFp ?: registeredMacFromName
                    if (!canonicalCandidate.isNullOrEmpty() && canonicalCandidate.startsWith("bluetooth_mac_") && prefs != null && hasProfileData(prefs, canonicalCandidate)) {
                        canonicalCandidate
                    } else {
                        // Tier 2: Specific device product name / alias (non-generic)
                        val unique = sanitize(uniqueOutputName(selectedOutput))
                        val resolvedName = if (unique.isNotEmpty() && !isGenericBluetoothName(unique)) unique
                                           else if (prodName.isNotEmpty() && !isGenericBluetoothName(prodName)) prodName
                                           else ""
                        if (resolvedName.isNotEmpty()) {
                            "bluetooth_name_$resolvedName"
                        } else {
                            // Tier 3: Port ID (keeps session separate even without name or MAC)
                            "bluetooth_id_${selectedOutput.id}"
                        }
                    }
                }
            }
            isWiredType(type) -> {
                val unique = sanitize(uniqueOutputName(selectedOutput))
                if (unique.isNotEmpty() && !isGenericWiredName(unique)) "wired_$unique"
                else sanitizeProductName(selectedOutput).let { if (it.isNotEmpty() && !isGenericWiredName(it)) "wired_$it" else "wired" }
            }
            type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> BUILTIN_EARPIECE_PROFILE_ID
            type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> BUILTIN_SPEAKER_PROFILE_ID
            else -> sanitizeProductName(selectedOutput).let { if (it.isNotEmpty()) "type_${type}_$it" else "type_$type" }
        }
    }

    /**
     * Resolves the hardware MAC address of a connected Bluetooth audio device without dashes or colons.
     * Checks AudioDeviceInfo.address first, then queries BluetoothManager active A2DP/Headset profiles.
     */
    @JvmStatic
    @JvmOverloads
    fun resolveBluetoothMac(info: AudioDeviceInfo?, context: Context? = null): String {
        if (info == null) return ""

        val ctx = context ?: appContext
        if (ctx != null) ensureBluetoothProxies(ctx)

        // 1. AudioDeviceInfo.address (Android 9+ / API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val rawAddr = info.address?.trim()
                if (!rawAddr.isNullOrEmpty() && isValidMacAddress(rawAddr)) {
                    return cleanMac(rawAddr)
                }
            } catch (_: Throwable) {}
        }

        // 2. Query A2DP & Headset profile proxies (Official Android BluetoothProfile API)
        try {
            // Check getActiveDevice() via reflection on A2DP proxy
            val activeMethod = a2dpProxy?.javaClass?.methods?.firstOrNull { it.name == "getActiveDevice" && it.parameterTypes.isEmpty() }
            val activeDev = activeMethod?.invoke(a2dpProxy) as? android.bluetooth.BluetoothDevice
            if (activeDev != null && isValidMacAddress(activeDev.address)) {
                return cleanMac(activeDev.address)
            }

            val a2dpDevices = a2dpProxy?.connectedDevices
            if (!a2dpDevices.isNullOrEmpty()) {
                if (a2dpDevices.size == 1 && isValidMacAddress(a2dpDevices[0].address)) {
                    return cleanMac(a2dpDevices[0].address)
                }
                val prodName = info.productName?.toString()?.trim() ?: ""
                val matched = a2dpDevices.firstOrNull { dev ->
                    val devName = dev.name?.trim() ?: ""
                    val devAlias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try { dev.alias?.trim() ?: "" } catch (_: SecurityException) { "" }
                    } else ""
                    prodName.isNotEmpty() && (prodName.equals(devName, ignoreCase = true) || prodName.equals(devAlias, ignoreCase = true)
                            || devName.contains(prodName, ignoreCase = true) || prodName.contains(devName, ignoreCase = true))
                }
                if (matched != null && isValidMacAddress(matched.address)) {
                    return cleanMac(matched.address)
                }
            }
        } catch (_: Throwable) {}

        try {
            val hsActiveMethod = headsetProxy?.javaClass?.methods?.firstOrNull { it.name == "getActiveDevice" && it.parameterTypes.isEmpty() }
            val hsActiveDev = hsActiveMethod?.invoke(headsetProxy) as? android.bluetooth.BluetoothDevice
            if (hsActiveDev != null && isValidMacAddress(hsActiveDev.address)) {
                return cleanMac(hsActiveDev.address)
            }

            val hsDevices = headsetProxy?.connectedDevices
            if (!hsDevices.isNullOrEmpty()) {
                if (hsDevices.size == 1 && isValidMacAddress(hsDevices[0].address)) {
                    return cleanMac(hsDevices[0].address)
                }
                val prodName = info.productName?.toString()?.trim() ?: ""
                val matchedHs = hsDevices.firstOrNull { dev ->
                    val devName = dev.name?.trim() ?: ""
                    val devAlias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try { dev.alias?.trim() ?: "" } catch (_: SecurityException) { "" }
                    } else ""
                    prodName.isNotEmpty() && (prodName.equals(devName, ignoreCase = true) || prodName.equals(devAlias, ignoreCase = true))
                }
                if (matchedHs != null && isValidMacAddress(matchedHs.address)) {
                    return cleanMac(matchedHs.address)
                }
            }
        } catch (_: Throwable) {}

        // 3. Query BluetoothAdapter bonded devices
        if (ctx != null) {
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                ) {
                    val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
                    val adapter = bm?.adapter ?: android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                    if (adapter != null && adapter.isEnabled) {
                        val bonded = adapter.bondedDevices
                        if (!bonded.isNullOrEmpty()) {
                            val prodName = info.productName?.toString()?.trim() ?: ""

                            val matched = bonded.firstOrNull { dev ->
                                val devName = dev.name?.trim() ?: ""
                                val devAlias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    try { dev.alias?.trim() ?: "" } catch (_: SecurityException) { "" }
                                } else ""
                                (prodName.isNotEmpty() && (prodName.equals(devName, ignoreCase = true) || prodName.equals(devAlias, ignoreCase = true)
                                        || devName.contains(prodName, ignoreCase = true) || prodName.contains(devName, ignoreCase = true)))
                            }
                            if (matched != null && !matched.address.isNullOrEmpty() && isValidMacAddress(matched.address)) {
                                return cleanMac(matched.address)
                            }

                            // If only 1 bonded device exists on system and A2DP is connected
                            if (bonded.size == 1 && adapter.getProfileConnectionState(android.bluetooth.BluetoothProfile.A2DP) == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                                val single = bonded.first()
                                if (!single.address.isNullOrEmpty() && isValidMacAddress(single.address)) {
                                    return cleanMac(single.address)
                                }
                            }
                        }
                    }
                }
            } catch (_: SecurityException) {
            } catch (_: Throwable) {}
        }

        // 4. Check if info.address has a non-MAC hardware identifier
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val addr = info.address?.trim() ?: ""
                if (addr.isNotEmpty() && addr != "00:00:00:00:00:00" && addr != "02:00:00:00:00:00") {
                    val sanitized = sanitize(addr)
                    if (sanitized.length >= 4) return sanitized
                }
            } catch (_: Throwable) {}
        }

        return ""
    }


    private fun isValidMacAddress(mac: String): Boolean {
        val clean = mac.replace(":", "").replace("-", "").trim()
        if (clean.length != 12) return false
        if (clean.all { it == '0' } || clean.equals("020000000000", ignoreCase = true)) return false
        return clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    private fun cleanMac(mac: String): String {
        return mac.lowercase().replace(":", "").replace("-", "").trim()
    }

    /**
     * Builds a hardware audio fingerprint that remains constant even if the device is renamed.
     */
    @JvmStatic
    fun buildAudioHardwareFingerprint(info: AudioDeviceInfo?): String {
        if (info == null) return ""
        val sb = StringBuilder()
        sb.append("t").append(info.type)
        // Include product name so two BT devices with the same codec/channels get distinct IDs
        val prod = info.productName?.toString()?.trim() ?: ""
        if (prod.isNotEmpty()) {
            sb.append("_n").append(Integer.toHexString(prod.lowercase().hashCode()))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val enc = info.encodings.sorted().joinToString("")
            val sr = info.sampleRates.sorted().joinToString("")
            val ch = info.channelCounts.sorted().joinToString("")
            val cm = info.channelMasks.sorted().joinToString("")
            if (enc.isNotEmpty() || sr.isNotEmpty() || ch.isNotEmpty()) {
                val raw = "${enc}_${sr}_${ch}_${cm}"
                sb.append("_").append(Integer.toHexString(raw.hashCode()))
            }
        }
        return sb.toString()
    }

    // --- PRIVATE UTILS ---

    private fun recordDeviceRegistration(
        editor: SharedPreferences.Editor,
        profileId: String,
        info: AudioDeviceInfo?,
        context: Context?
    ) {
        if (info == null || !isBluetoothType(info.type)) return
        val mac = resolveBluetoothMac(info, context)
        if (mac.isNotEmpty()) {
            editor.putString("$KEY_REG_MAC_PREFIX$mac", profileId)
        }
        val fp = buildAudioHardwareFingerprint(info)
        if (fp.isNotEmpty()) {
            editor.putString("$KEY_REG_FP_PREFIX$fp", profileId)
        }
        val prod = sanitizeProductName(info)
        if (prod.isNotEmpty()) {
            editor.putString("$KEY_REG_NAME_PREFIX$prod", profileId)
        }
    }

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
        val dstPresetId = if (presetId == userPresetIdForProfile(src)) userPresetIdForProfile(dst) else presetId
        editor.putString(profileKey(dst, KEY_SELECTED_PRESET), dstPresetId)
        for (i in 0 until BAND_COUNT) {
            editor.putFloat(profileBandKey(dst, i), resolveProfileBandValue(prefs, src, i, presetId))
        }

        val isDeviceSpecific = isDeviceSpecificAlias(src, dst)
        if (isDeviceSpecific) {
            // Migrate user preset to dst and remove from src to prevent ghost duplication
            val customNameKey = profileCustomPresetNameKey(src)
            if (prefs.contains(customNameKey)) {
                val customName = prefs.getString(customNameKey, null)
                if (!customName.isNullOrEmpty()) {
                    editor.putString(profileCustomPresetNameKey(dst), customName)
                }
                editor.remove(customNameKey)
            }
            for (i in 0 until BAND_COUNT) {
                val userBandKey = userPresetBandKey(src, i)
                if (prefs.contains(userBandKey)) {
                    editor.putFloat(userPresetBandKey(dst, i), prefs.getFloat(userBandKey, 0f))
                    editor.remove(userBandKey)
                }
            }
            val bassDbKey = KEY_USER_PRESET_BASS_DB_PREFIX + src
            if (prefs.contains(bassDbKey)) {
                editor.putFloat(KEY_USER_PRESET_BASS_DB_PREFIX + dst, prefs.getFloat(bassDbKey, 0f))
                editor.remove(bassDbKey)
            }
            val bassFreqKey = KEY_USER_PRESET_BASS_FREQ_PREFIX + src
            if (prefs.contains(bassFreqKey)) {
                editor.putFloat(KEY_USER_PRESET_BASS_FREQ_PREFIX + dst, prefs.getFloat(bassFreqKey, AudioEffectsService.BASS_FREQUENCY_DEFAULT_HZ))
                editor.remove(bassFreqKey)
            }
            val bassTypeKey = KEY_USER_PRESET_BASS_TYPE_PREFIX + src
            if (prefs.contains(bassTypeKey)) {
                editor.putInt(KEY_USER_PRESET_BASS_TYPE_PREFIX + dst, prefs.getInt(bassTypeKey, AudioEffectsService.BASS_TYPE_NATURAL))
                editor.remove(bassTypeKey)
            }

            // Also clean up obsolete device profile keys for the migrated alias
            editor.remove(profileKey(src, AudioEffectsService.KEY_ENABLED))
            editor.remove(profileKey(src, AudioEffectsService.KEY_BASS_DB))
            editor.remove(profileKey(src, AudioEffectsService.KEY_BASS_TYPE))
            editor.remove(profileKey(src, AudioEffectsService.KEY_BASS_FREQUENCY_HZ))
            editor.remove(profileKey(src, KEY_SELECTED_PRESET))
            for (i in 0 until BAND_COUNT) {
                editor.remove(profileBandKey(src, i))
            }
        }
    }

    private fun writeDefaultValuesToProfile(editor: SharedPreferences.Editor, id: String, initialEnabled: Boolean = PROFILE_DEFAULT_ENABLED) {
        editor.putBoolean(profileKey(id, AudioEffectsService.KEY_ENABLED), initialEnabled)
        editor.putFloat(profileKey(id, AudioEffectsService.KEY_BASS_DB), PROFILE_DEFAULT_BASS_DB)
        editor.putInt(profileKey(id, AudioEffectsService.KEY_BASS_TYPE), PROFILE_DEFAULT_BASS_TYPE)
        editor.putFloat(profileKey(id, AudioEffectsService.KEY_BASS_FREQUENCY_HZ), PROFILE_DEFAULT_BASS_FREQUENCY_HZ)
        editor.putString(profileKey(id, KEY_SELECTED_PRESET), PRESET_DEFAULT)
        for (i in 0 until BAND_COUNT) editor.putFloat(profileBandKey(id, i), 0f)
    }

    private fun writeDefaultValuesToActive(editor: SharedPreferences.Editor, initialEnabled: Boolean = PROFILE_DEFAULT_ENABLED) {
        editor.putBoolean(AudioEffectsService.KEY_ENABLED, initialEnabled)
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
        val rawPreset = prefs.getString(profileKey(id, KEY_SELECTED_PRESET), null)
        if (!rawPreset.isNullOrEmpty()) {
            if (rawPreset.startsWith(USER_PRESET_ID_PREFIX)) {
                val ownerProfileId = rawPreset.removePrefix(USER_PRESET_ID_PREFIX)
                if (ownerProfileId != id) {
                    if (prefs.contains(profileCustomPresetNameKey(ownerProfileId))) {
                        return rawPreset
                    }
                    return if (prefs.contains(profileCustomPresetNameKey(id))) userPresetIdForProfile(id) else PRESET_DEFAULT
                }
            }
            return rawPreset
        }
        if (hasAnyUserPresetBands(prefs, id)) {
            return if (prefs.contains(profileCustomPresetNameKey(id))) userPresetIdForProfile(id) else PRESET_CUSTOM
        }
        return PRESET_DEFAULT
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

    fun findExistingAliasProfileId(
        prefs: SharedPreferences,
        targetId: String,
        info: AudioDeviceInfo?,
        context: Context? = null
    ): String? {
        if (info == null) return null
        val type = info.type

        if (isBluetoothType(type)) {
            // 1. Check registry by MAC address
            val mac = resolveBluetoothMac(info, context)
            if (mac.isNotEmpty()) {
                val mappedId = prefs.getString("$KEY_REG_MAC_PREFIX$mac", null)
                if (!mappedId.isNullOrEmpty() && mappedId != targetId && hasProfileData(prefs, mappedId)) {
                    return mappedId
                }
            }

            // 2. Check registry by specific (non-generic) product name
            val product = sanitizeProductName(info)
            if (product.isNotEmpty() && !isGenericBluetoothName(product)) {
                val mappedNameId = prefs.getString("$KEY_REG_NAME_PREFIX$product", null)
                if (!mappedNameId.isNullOrEmpty() && mappedNameId != targetId && hasProfileData(prefs, mappedNameId)) {
                    return mappedNameId
                }
                val oldAlias = "bluetooth_$product"
                if (oldAlias != targetId && hasProfileData(prefs, oldAlias)) return oldAlias
            }

            return null
        }

        if (isWiredType(type)) {
            val product = sanitizeProductName(info)
            if (product.isNotEmpty() && !isGenericWiredName(product)) {
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

    fun isGenericBluetoothName(name: String): Boolean {
        val lower = name.lowercase().replace("_", "").trim()
        return lower.isEmpty() ||
                lower == "bluetooth" ||
                lower == "bluetoothaudio" ||
                lower == "audiobluetooth" ||
                lower == "auricularesbluetooth" ||
                lower == "altavozbluetooth" ||
                lower == "bluetootha2dp" ||
                lower == "dispositivobluetooth" ||
                lower == "bluetoothdevice" ||
                lower == "headset" ||
                lower == "headphones" ||
                lower == "auriculares"
    }

    fun isGenericWiredName(name: String): Boolean {
        val lower = name.lowercase().replace("_", "").trim()
        return lower.isEmpty() || lower == "wired" || lower == "auriculares" || lower == "headphones" || lower == "headset"
    }

    private fun sanitizeProductName(info: AudioDeviceInfo): String = sanitize(info.productName?.toString()?.trim())

    private fun uniqueOutputName(info: AudioDeviceInfo): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.address?.trim()?.let { if (it.isNotEmpty() && isValidMacAddress(it)) return cleanMac(it) }
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

        val btDevices = mutableListOf<AudioDeviceInfo>()
        var wired: AudioDeviceInfo? = null
        var speaker: AudioDeviceInfo? = null
        var earpiece: AudioDeviceInfo? = null
        var fallback: AudioDeviceInfo? = null

        for (output in outputs) {
            if (!output.isSink) continue
            if (fallback == null) fallback = output

            val type = output.type
            when {
                isBluetoothType(type) -> btDevices.add(output)
                isWiredType(type) && wired == null -> wired = output
                (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE) && speaker == null -> speaker = output
                type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE && earpiece == null -> earpiece = output
            }
        }

        var selectedBt: AudioDeviceInfo? = null
        if (btDevices.isNotEmpty()) {
            if (btDevices.size == 1) {
                selectedBt = btDevices[0]
            } else {
                // Multiple Bluetooth devices connected! Find the truly active one playing media.
                val ctx = appContext
                if (ctx != null) ensureBluetoothProxies(ctx)

                var activeMac = ""
                var activeName = ""
                try {
                    val activeMethod = a2dpProxy?.javaClass?.methods?.firstOrNull { it.name == "getActiveDevice" && it.parameterTypes.isEmpty() }
                    val activeDev = activeMethod?.invoke(a2dpProxy) as? android.bluetooth.BluetoothDevice
                    if (activeDev != null) {
                        if (isValidMacAddress(activeDev.address)) {
                            activeMac = cleanMac(activeDev.address)
                        }
                        activeName = activeDev.name?.trim() ?: ""
                    }
                } catch (_: Throwable) {}

                if (activeMac.isNotEmpty() || activeName.isNotEmpty()) {
                    if (activeMac.isNotEmpty()) {
                        selectedBt = btDevices.firstOrNull { dev ->
                            val devMac = resolveBluetoothMac(dev, ctx)
                            devMac.isNotEmpty() && devMac == activeMac
                        }
                    }
                    if (selectedBt == null && activeName.isNotEmpty()) {
                        selectedBt = btDevices.firstOrNull { dev ->
                            val prod = dev.productName?.toString()?.trim() ?: ""
                            prod.isNotEmpty() && (prod.equals(activeName, ignoreCase = true) || prod.contains(activeName, ignoreCase = true) || activeName.contains(prod, ignoreCase = true))
                        }
                    }
                }

                if (selectedBt == null) {
                    selectedBt = btDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                        ?: btDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER }
                        ?: btDevices[0]
                }
            }
        }

        return selectedBt ?: wired ?: speaker ?: earpiece ?: fallback
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
    private fun isDeviceSpecificAlias(src: String, dst: String): Boolean {
        if (src == GLOBAL_PROFILE_ID || src.startsWith("type_")) return false
        if (dst == GLOBAL_PROFILE_ID || dst.startsWith("type_")) return false
        if (src.startsWith("bluetooth_") && dst.startsWith("bluetooth_")) return true
        if (src.startsWith("wired") && dst.startsWith("wired")) return true
        return false
    }

    @JvmStatic
    fun cleanStaleUserPresetDuplicates(prefs: SharedPreferences) {
        val allValues = prefs.all
        if (allValues.isEmpty()) return

        val editor = prefs.edit()
        var hasChanges = false

        val customPresets = mutableListOf<Pair<String, String>>()
        for ((key, value) in allValues) {
            if (key != null && key.startsWith(KEY_PROFILE_CUSTOM_PRESET_NAME_PREFIX) && value is String) {
                val profileId = key.substring(KEY_PROFILE_CUSTOM_PRESET_NAME_PREFIX.length)
                val name = value.trim()
                if (profileId.isNotEmpty() && name.isNotEmpty()) {
                    customPresets.add(profileId to name)
                }
            }
        }

        if (customPresets.size <= 1) return

        fun profileScore(id: String): Int = when {
            id.startsWith("bluetooth_mac_") -> 100
            id.startsWith("wired_") && id != "wired" -> 80
            id.startsWith("bluetooth_name_") -> 60
            id.startsWith("bluetooth_") -> 50
            id == "wired" -> 40
            id == BUILTIN_SPEAKER_PROFILE_ID -> 30
            id == BUILTIN_EARPIECE_PROFILE_ID -> 20
            else -> 10
        }

        // 1. Group by exact custom name (case-insensitive) to catch cloned duplicates
        val groupedByName = customPresets.groupBy { it.second.lowercase(java.util.Locale.US) }
        for ((_, entries) in groupedByName) {
            if (entries.size <= 1) continue

            val sortedEntries = entries.sortedByDescending { profileScore(it.first) }
            val canonicalProfileId = sortedEntries.first().first

            for (i in 1 until sortedEntries.size) {
                val staleProfileId = sortedEntries[i].first

                val canonicalBands = FloatArray(BAND_COUNT) { prefs.getFloat(userPresetBandKey(canonicalProfileId, it), 0f) }
                val staleBands = FloatArray(BAND_COUNT) { prefs.getFloat(userPresetBandKey(staleProfileId, it), 0f) }
                val bandsMatch = canonicalBands.indices.all { 
                    Math.abs(canonicalBands[it] - staleBands[it]) <= FLOAT_TOLERANCE 
                }

                val isRelatedAlias = (staleProfileId.startsWith("bluetooth_") && canonicalProfileId.startsWith("bluetooth_")) ||
                                     (staleProfileId.startsWith("wired") && canonicalProfileId.startsWith("wired")) ||
                                     bandsMatch

                if (isRelatedAlias) {
                    Log.d(TAG, "cleanStaleUserPresetDuplicates: removing stale preset '$staleProfileId' in favor of '$canonicalProfileId'")
                    editor.remove(profileCustomPresetNameKey(staleProfileId))
                    for (b in 0 until BAND_COUNT) {
                        editor.remove(userPresetBandKey(staleProfileId, b))
                    }
                    editor.remove(KEY_USER_PRESET_BASS_DB_PREFIX + staleProfileId)
                    editor.remove(KEY_USER_PRESET_BASS_FREQ_PREFIX + staleProfileId)
                    editor.remove(KEY_USER_PRESET_BASS_TYPE_PREFIX + staleProfileId)

                    val currentSelected = prefs.getString(KEY_SELECTED_PRESET, null)
                    if (currentSelected == userPresetIdForProfile(staleProfileId)) {
                        editor.putString(KEY_SELECTED_PRESET, userPresetIdForProfile(canonicalProfileId))
                    }
                    hasChanges = true
                }
            }
        }

        // 2. Also check if any bluetooth_name_ or bluetooth_id_ alias preset exists when its registered canonical MAC exists
        for ((profileId, _) in customPresets) {
            if (profileId.startsWith("bluetooth_name_") || profileId.startsWith("bluetooth_id_")) {
                val prodName = profileId.removePrefix("bluetooth_name_")
                val mappedMacProfile = prefs.getString("$KEY_REG_NAME_PREFIX$prodName", null)
                if (!mappedMacProfile.isNullOrEmpty() && mappedMacProfile != profileId && mappedMacProfile.startsWith("bluetooth_mac_")) {
                    if (prefs.contains(profileCustomPresetNameKey(mappedMacProfile))) {
                        Log.d(TAG, "cleanStaleUserPresetDuplicates: removing obsolete alias preset '$profileId' (canonical: '$mappedMacProfile')")
                        editor.remove(profileCustomPresetNameKey(profileId))
                        for (b in 0 until BAND_COUNT) {
                            editor.remove(userPresetBandKey(profileId, b))
                        }
                        editor.remove(KEY_USER_PRESET_BASS_DB_PREFIX + profileId)
                        editor.remove(KEY_USER_PRESET_BASS_FREQ_PREFIX + profileId)
                        editor.remove(KEY_USER_PRESET_BASS_TYPE_PREFIX + profileId)
                        hasChanges = true
                    }
                }
            }
        }

        if (hasChanges) {
            editor.apply()
        }
    }
}
