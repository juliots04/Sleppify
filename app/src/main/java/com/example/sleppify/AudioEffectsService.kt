package com.example.sleppify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import android.media.audiofx.DynamicsProcessing

/**
 * Motor DSP avanzado basado en DynamicsProcessing (API 28+).
 *
 * Proporciona un ecualizador parametrico de 9 bandas con filtros biquad IIR,
 * ajuste de graves independiente (low-shelf), y cero limitador/compresor/auto-ganancia.
 *
 * Al subir las bandas el volumen general NO se reduce.
 * Se aplica globalmente (session 0) para afectar todo el audio del sistema.
 */
class AudioEffectsService : Service() {

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var currentAudioSessionId: Int = GLOBAL_SESSION_ID
    private var isEngineActive = false
    private var isForegroundStarted = false
    private var audioManager: AudioManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>?) {
            syncAndRefresh()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>?) {
            syncAndRefresh()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager?.registerAudioDeviceCallback(deviceCallback, mainHandler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        when (action) {
            ACTION_APPLY -> {
                val sessionId = intent?.getIntExtra(EXTRA_AUDIO_SESSION_ID, GLOBAL_SESSION_ID) ?: GLOBAL_SESSION_ID
                enterForeground()
                applyEffects(sessionId)
            }
            ACTION_STOP -> {
                releaseEngine()
                exitForeground()
                stopSelf()
            }
            else -> {
                enterForeground()
                applyEffects(GLOBAL_SESSION_ID)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        audioManager?.unregisterAudioDeviceCallback(deviceCallback)
        releaseEngine()
        exitForeground()
        super.onDestroy()
    }

    // ───────────────────────────────────────────────────────────────────
    // Foreground service lifecycle
    // ───────────────────────────────────────────────────────────────────

    private fun enterForeground() {
        if (isForegroundStarted) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        try {
            val notification = buildForegroundNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                val mediaPlaybackType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                startForeground(FOREGROUND_NOTIFICATION_ID, notification, mediaPlaybackType)
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification)
            }
            isForegroundStarted = true
        } catch (e: Exception) {
            Log.w(TAG, "foreground:enter_failed", e)
        }
    }

    private fun exitForeground() {
        if (!isForegroundStarted) return
        isForegroundStarted = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "foreground:exit_error", e)
        }
    }

    private fun buildForegroundNotification(): Notification {
        // Invisible notification - required for foreground service but hidden from user
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val contentIntent = PendingIntent.getActivity(this, 0, intent, pendingFlags)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.color.transparent)  // Invisible icon
            .setContentTitle("")  // No visible title
            .setContentText("")   // No visible text
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)      // No sound
            .setShowWhen(false)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)  // Hidden on lock screen
            .setContentIntent(contentIntent)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Sleppify Audio",
            NotificationManager.IMPORTANCE_MIN  // Lowest importance - invisible
        ).apply {
            description = "Audio processing"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)  // No sound
        }
        manager.createNotificationChannel(channel)
    }

    // ───────────────────────────────────────────────────────────────────
    // Core DSP Engine
    // ───────────────────────────────────────────────────────────────────

    private fun applyEffects(sessionId: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.w(TAG, "DynamicsProcessing requires API 28+, current=${Build.VERSION.SDK_INT}")
            stopSelfResult(0)
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        
        // Initial sync to ensure correct profile for current device on startup
        val manager = audioManager
        if (manager != null) {
            val selected = AudioDeviceProfileStore.selectPreferredOutput(manager)
            AudioDeviceProfileStore.syncActiveProfileForOutput(prefs, selected)
        }

        val enabled = prefs.getBoolean(KEY_ENABLED, false)

        if (!enabled) {
            releaseEngine()
            stopSelf()
            return
        }

        val targetSession = if (sessionId != GLOBAL_SESSION_ID) sessionId else GLOBAL_SESSION_ID

        Thread {
            try {
                // Rebuild engine if session changed or not active
                if (dynamicsProcessing == null || currentAudioSessionId != targetSession) {
                    releaseEngine()
                    dynamicsProcessing = createDynamicsProcessingEngine(targetSession)
                    currentAudioSessionId = targetSession
                    isEngineActive = true
                }

                applyParametersFromPrefs(prefs)
                dynamicsProcessing?.enabled = true

            } catch (e: Exception) {
                Log.e(TAG, "engine:error session=$targetSession", e)

                // If global session failed, try with session 0 as absolute fallback
                if (targetSession != GLOBAL_SESSION_ID) {
                    try {
                        releaseEngine()
                        dynamicsProcessing = createDynamicsProcessingEngine(GLOBAL_SESSION_ID)
                        currentAudioSessionId = GLOBAL_SESSION_ID
                        isEngineActive = true
                        applyParametersFromPrefs(prefs)
                        dynamicsProcessing?.enabled = true
                    } catch (fallbackError: Exception) {
                        Log.e(TAG, "engine:fallback_failed", fallbackError)
                        releaseEngine()
                    }
                }
            }
        }.start()
    }

    /**
     * Creates a DynamicsProcessing engine with:
     * - 9-band pre-EQ (biquad parametric)
     * - NO multi-band compressor
     * - NO post-EQ
     * - NO limiter
     *
     * This ensures ZERO volume reduction when boosting bands.
     */
    private fun createDynamicsProcessingEngine(sessionId: Int): DynamicsProcessing {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            throw UnsupportedOperationException("DynamicsProcessing requires API 28+")
        }

        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            /* channelCount */ 2,
            /* preEqInUse */ true,
            /* preEqBandCount */ EQ_BAND_COUNT,
            /* mbcInUse */ false,       // NO compressor
            /* mbcBandCount */ 0,
            /* postEqInUse */ false,     // NO post-EQ
            /* postEqBandCount */ 0,
            /* limiterInUse */ false     // NO limiter
        )

        // Configure pre-EQ channel 0
        val preEqChannel0 = DynamicsProcessing.EqBand(true, 1000f, 0f)
        val eqConfig = DynamicsProcessing.Eq(
            true,  // inUse
            true,  // enabled
            EQ_BAND_COUNT
        )
        for (i in 0 until EQ_BAND_COUNT) {
            val freq = WAVELET_9_BAND_FREQUENCIES_HZ[i].toFloat()
            eqConfig.setBand(i, DynamicsProcessing.EqBand(true, freq, 0f))
        }
        config.setPreEqByChannelIndex(0, eqConfig)
        config.setPreEqByChannelIndex(1, eqConfig)

        // Build and configure
        val dp = DynamicsProcessing(0, sessionId, config.build())

        // Explicitly disable limiter on each channel
        for (ch in 0..1) {
            try {
                val limiter = dp.getLimiterByChannelIndex(ch)
                limiter.isEnabled = false
                dp.setLimiterByChannelIndex(ch, limiter)
            } catch (ignored: Exception) {
                // Some devices may not support limiter configuration
            }
        }

        // Set input gain to 0 dB (no pre-attenuation)
        try {
            dp.setInputGainAllChannelsTo(0f)
        } catch (ignored: Exception) {
        }


        return dp
    }

    /**
     * Reads all EQ band values, bass settings from SharedPreferences
     * and applies them to the DynamicsProcessing engine.
     *
     * NO auto-gain compensation is applied — bands boost freely.
     */
    private fun applyParametersFromPrefs(prefs: SharedPreferences) {
        val dp = dynamicsProcessing ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        // Apply 9-band EQ values
        for (bandIndex in 0 until EQ_BAND_COUNT) {
            val gainDb = prefs.getFloat(bandDbKey(bandIndex), 0f)
                .coerceIn(EQ_GAIN_MIN_DB, EQ_GAIN_MAX_DB)
            val freq = WAVELET_9_BAND_FREQUENCIES_HZ[bandIndex].toFloat()

            for (ch in 0..1) {
                try {
                    val band = dp.getPreEqBandByChannelIndex(ch, bandIndex)
                    band.cutoffFrequency = freq
                    band.gain = gainDb
                    band.isEnabled = true
                    dp.setPreEqBandByChannelIndex(ch, bandIndex, band)
                } catch (e: Exception) {
                    Log.w(TAG, "eq:set_band_failed ch=$ch band=$bandIndex", e)
                }
            }
        }

        // Apply bass boost via input gain (low-shelf simulation)
        // DynamicsProcessing doesn't have a dedicated bass shelf, so we use the
        // lowest EQ band (62.5 Hz) with additional gain from the bass tuner
        val bassDb = prefs.getFloat(KEY_BASS_DB, 0f).coerceIn(0f, BASS_DB_MAX)
        if (bassDb > 0.1f) {
            // Add bass gain to the lowest frequency bands
            val bassCutoffHz = prefs.getFloat(KEY_BASS_FREQUENCY_HZ, BASS_FREQUENCY_DEFAULT_HZ)
                .coerceIn(BASS_FREQUENCY_MIN_HZ, BASS_FREQUENCY_MAX_HZ)

            for (bandIndex in 0 until EQ_BAND_COUNT) {
                val bandFreq = WAVELET_9_BAND_FREQUENCIES_HZ[bandIndex].toFloat()
                if (bandFreq > bassCutoffHz * 2f) break

                // Scale bass contribution by how far the band is below cutoff
                val ratio = if (bandFreq <= bassCutoffHz) {
                    1.0f
                } else {
                    val logRatio = Math.log10((bassCutoffHz * 2f / bandFreq).toDouble()).toFloat()
                    logRatio.coerceIn(0f, 1f)
                }

                val additionalBassGain = bassDb * ratio
                val currentBandGain = prefs.getFloat(bandDbKey(bandIndex), 0f)
                    .coerceIn(EQ_GAIN_MIN_DB, EQ_GAIN_MAX_DB)
                val totalGain = (currentBandGain + additionalBassGain)
                    .coerceIn(EQ_GAIN_MIN_DB * 2f, EQ_GAIN_MAX_DB * 2f) // Extended range for bass

                for (ch in 0..1) {
                    try {
                        val band = dp.getPreEqBandByChannelIndex(ch, bandIndex)
                        band.gain = totalGain
                        dp.setPreEqBandByChannelIndex(ch, bandIndex, band)
                    } catch (ignored: Exception) {
                    }
                }
            }
        }

        // Ensure NO input gain reduction — keep at 0 dB
        try {
            dp.setInputGainAllChannelsTo(0f)
        } catch (ignored: Exception) {
        }


    }

    private fun syncAndRefresh() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val manager = audioManager ?: return
        val selected = AudioDeviceProfileStore.selectPreferredOutput(manager)
        
        val changed = AudioDeviceProfileStore.syncActiveProfileForOutput(prefs, selected)
        if (changed) {
            if (prefs.getBoolean(KEY_ENABLED, false)) {
                applyEffects(currentAudioSessionId)
            }
        }
    }

    private fun releaseEngine() {
        try {
            dynamicsProcessing?.enabled = false
            dynamicsProcessing?.release()
        } catch (e: Exception) {
            Log.w(TAG, "engine:release_error", e)
        }
        dynamicsProcessing = null
        isEngineActive = false
        currentAudioSessionId = GLOBAL_SESSION_ID
    }

    // ───────────────────────────────────────────────────────────────────
    // Companion: Constants, keys, and utilities
    // ───────────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "AudioEffectsService"
        private const val GLOBAL_SESSION_ID = 0

        const val EQ_GAIN_MIN_DB = -10f
        const val EQ_GAIN_MAX_DB = 10f
        const val BASS_DB_MAX = 5f

        const val BASS_FREQUENCY_DEFAULT_HZ = 25f
        const val BASS_FREQUENCY_MIN_HZ = 20f
        const val BASS_FREQUENCY_MAX_HZ = 250f

        @JvmField
        val WAVELET_9_BAND_FREQUENCIES_HZ = intArrayOf(62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

        const val EQ_BAND_COUNT = 9

        const val ACTION_APPLY = "com.example.sleppify.action.APPLY_EFFECTS"
        const val ACTION_STOP = "com.example.sleppify.action.STOP_EFFECTS"

        const val EXTRA_AUDIO_SESSION_ID = "extra_audio_session_id"

        const val PREFS_NAME = "global_eq_prefs"

        const val KEY_ENABLED = "enabled"
        const val KEY_BASS_DB = "bass_db"
        const val KEY_BASS_FREQUENCY_HZ = "bass_frequency_hz"
        const val KEY_BASS_TYPE = "bass_type"
        const val KEY_SPATIAL_ENABLED = "spatial_enabled"
        const val KEY_SPATIAL_STRENGTH = "spatial_strength"
        const val KEY_REVERB_LEVEL = "reverb_level"

        const val SPATIAL_STRENGTH_DEFAULT = 1000

        const val REVERB_LEVEL_OFF = 0
        const val REVERB_LEVEL_LIGHT = 1
        const val REVERB_LEVEL_MEDIUM = 2
        const val REVERB_LEVEL_STRONG = 3

        const val BASS_TYPE_NATURAL = 0
        const val BASS_TYPE_TRANSIENT_COMPRESSOR = 1
        const val BASS_TYPE_SUSTAIN_COMPRESSOR = 2

        private const val KEY_BAND_DB_PREFIX = "band_db_"

        @JvmStatic
        fun bandDbKey(bandIndex: Int): String = KEY_BAND_DB_PREFIX + bandIndex

        @JvmStatic
        fun normalizeBassFrequencySliderValue(rawValue: Float): Float {
            return rawValue.coerceIn(BASS_FREQUENCY_MIN_HZ, BASS_FREQUENCY_MAX_HZ)
        }

        @JvmStatic
        fun bassSliderFromCutoffHz(cutoffHz: Float): Float {
            return cutoffHz.coerceIn(BASS_FREQUENCY_MIN_HZ, BASS_FREQUENCY_MAX_HZ)
        }

        private const val NOTIFICATION_CHANNEL_ID = "sleppify_media_playback"
        private const val FOREGROUND_NOTIFICATION_ID = 11032

        /**
         * Starts the EQ engine as a foreground service with mediaPlayback type.
         * Uses startForegroundService() on API 26+ so the service can call startForeground().
         */
        @JvmStatic
        @JvmOverloads
        fun sendApply(context: Context, audioSessionId: Int = GLOBAL_SESSION_ID) {
            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val enabled = prefs.getBoolean(KEY_ENABLED, false)

            if (!enabled) {
                sendStop(context)
                return
            }

            val intent = Intent(context, AudioEffectsService::class.java).apply {
                action = ACTION_APPLY
                putExtra(EXTRA_AUDIO_SESSION_ID, audioSessionId)
            }
            try {
                // Use regular startService to avoid ForegroundServiceDidNotStartInTimeException
                // The service will run without a notification since player notification handles it
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "sendApply:failed", e)
            }
        }

        /**
         * Sends a stop intent to the service.
         */
        @JvmStatic
        fun sendStop(context: Context) {
            val intent = Intent(context, AudioEffectsService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "sendStop:failed", e)
            }
        }
    }
}
