package com.example.sleppify

import android.content.Context
import android.util.Log
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer

/**
 * Singleton manager para una instancia compartida de ExoPlayer.
 * Lazy-initializes on first call to [getSharedExoPlayer] to avoid blocking cold start.
 */
@UnstableApi
object ExoPlayerManager {

    private const val TAG = "ExoPlayerManager"

    @Volatile
    private var initialized = false

    @Volatile
    private var sharedExoPlayer: ExoPlayer? = null

    @Volatile
    private var appContextRef: Context? = null

    private val initLock = Any()

    /**
     * Inicializa la instancia compartida de ExoPlayer.
     * Safe to call from any thread — ExoPlayer is built on the main Looper.
     */
    fun initialize(@NonNull context: Context) {
        if (!initialized) {
            synchronized(initLock) {
                if (!initialized) {
                    try {
                        val appContext = context.applicationContext
                        appContextRef = appContext
                        val audioAttributes = AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build()

                        // Aggressive LoadControl: start playback after only 500ms of
                        // buffered audio instead of the default 2500ms.  Keep a
                        // reasonable back-buffer so seeks within recently-played
                        // sections are instant.
                        val loadControl = DefaultLoadControl.Builder()
                            .setBufferDurationsMs(
                                /* minBufferMs */            15_000,
                                /* maxBufferMs */            50_000,
                                /* bufferForPlaybackMs */       250,
                                /* bufferForPlaybackAfterRebufferMs */ 500
                            )
                            .setBackBuffer(
                                /* backBufferDurationMs */ 30_000,
                                /* retainBackBufferFromKeyframe */ true
                            )
                            .setPrioritizeTimeOverSizeThresholds(true)
                            .build()

                        val renderersFactory = object : DefaultRenderersFactory(appContext) {
                            override fun buildVideoRenderers(
                                context: Context,
                                extensionRendererMode: Int,
                                mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                                enableDecoderFallback: Boolean,
                                eventHandler: android.os.Handler,
                                eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                                allowedVideoJoiningTimeMs: Long,
                                out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>
                            ) { /* no-op: audio-only app */ }
                        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

                        sharedExoPlayer = ExoPlayer.Builder(appContext, renderersFactory)
                            .setLoadControl(loadControl)
                            .setLooper(android.os.Looper.getMainLooper())
                            .setAudioAttributes(audioAttributes, true)
                            .setHandleAudioBecomingNoisy(true)
                            .build()
                        initialized = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Falló la inicialización del ExoPlayer compartido", e)
                    }
                }
            }
        }
    }

    /**
     * Obtiene la instancia compartida de ExoPlayer.
     * Lazy-initializes on first call if a context was previously provided via [initialize]
     * or [setAppContext]. Returns null only if initialization failed.
     */
    @Nullable
    fun getSharedExoPlayer(): ExoPlayer? {
        if (!initialized) {
            val ctx = appContextRef
            if (ctx != null) {
                initialize(ctx)
            }
        }
        return sharedExoPlayer
    }

    /**
     * Stores the app context for deferred lazy initialization.
     * Call early (e.g. Application.onCreate) so [getSharedExoPlayer] can self-init later.
     */
    fun setAppContext(@NonNull context: Context) {
        appContextRef = context.applicationContext
    }

    /**
     * Reinicializa el ExoPlayer compartido si está en estado inválido.
     * Útil cuando hay problemas con handlers de threads muertos.
     */
    fun reinitialize(@NonNull context: Context) {
        synchronized(initLock) {
            try {
                sharedExoPlayer?.let { player ->
                    // Stop + clear before release to ensure AudioTrack resources are freed
                    // immediately. On low-memory devices, release() alone may leave AudioFlinger
                    // resources in a stale state causing -12 (ENOMEM) on the next AudioTrack init.
                    player.stop()
                    player.clearMediaItems()
                    player.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error liberando ExoPlayer durante reinitialización", e)
            }
            sharedExoPlayer = null
            initialized = false
            initialize(context)
        }
    }

    /**
     * Verifica si el ExoPlayer compartido está inicializado.
     */
    fun isInitialized(): Boolean = initialized && sharedExoPlayer != null

    /**
     * Libera la instancia compartida de ExoPlayer. Llamar esto cuando la app se está cerrando.
     */
    fun release() {
        synchronized(initLock) {
            sharedExoPlayer?.release()
            sharedExoPlayer = null
            initialized = false
        }
    }
}
