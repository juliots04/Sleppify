package com.example.sleppify

import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.lang.ref.WeakReference

/**
 * Thin adapter around ExoPlayer (Media3) that mimics the relevant subset of
 * [android.media.MediaPlayer] API used by [SongPlayerFragment].
 *
 * The goal is to swap MediaPlayer implementations with minimal changes to
 * the fragment. Only the listeners and APIs actually used are exposed.
 */
@UnstableApi
class ExoMediaPlayer {

    interface OnPreparedListener {
        fun onPrepared(mp: ExoMediaPlayer)
    }

    interface OnCompletionListener {
        fun onCompletion(mp: ExoMediaPlayer)
    }

    interface OnErrorListener {
        /**
         * @return true if the error has been handled
         */
        fun onError(mp: ExoMediaPlayer, what: Int, extra: Int): Boolean
    }

    interface OnBufferingListener {
        fun onBufferingChanged(mp: ExoMediaPlayer, isBuffering: Boolean)
    }

    companion object {
        private const val TAG = "ExoMediaPlayer"

        @Volatile
        private var sharedCache: SimpleCache? = null
        private val cacheLock = Any()

        @JvmStatic
        fun getSharedCache(context: Context): SimpleCache {
            return sharedCache ?: synchronized(cacheLock) {
                sharedCache ?: run {
                    val cacheDir = File(context.cacheDir, "exo_stream_cache")
                    val evictor = NoOpCacheEvictor()
                    SimpleCache(cacheDir, evictor, androidx.media3.database.StandaloneDatabaseProvider(context)).also {
                        sharedCache = it
                    }
                }
            }
        }

        // Registry of all active ExoMediaPlayer instances in the app
        private val activeInstances = CopyOnWriteArrayList<WeakReference<ExoMediaPlayer>>()

        /**
         * Pauses all other playing instances that are not part of a crossfade.
         */
        @JvmStatic
        private fun stopOthers(current: ExoMediaPlayer) {
            val iterator = activeInstances.iterator()
            while (iterator.hasNext()) {
                val ref = iterator.next()
                val other = ref.get()
                if (other == null) {
                    activeInstances.remove(ref)
                    continue
                }
                if (other !== current && other.isPlaying()) {
                    // Only stop if it's a DIFFERENT underlying ExoPlayer instance.
                    // If they share the same player, there is no competition.
                    if (other.exoPlayer !== current.exoPlayer) {
                        // Allow overlap if one of them is a crossfade component
                        if (!current.isCrossfadeComponent && !other.isCrossfadeComponent) {
                            try {
                                other.pause()
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to pause other instance", e)
                            }
                        }
                    }
                }
            }
        }
    }

    private val appContext: Context
    private val mainHandler = Handler(Looper.getMainLooper())
    private var exoPlayer: ExoPlayer? = null

    private var preparedListener: OnPreparedListener? = null
    private var completionListener: OnCompletionListener? = null
    private var errorListener: OnErrorListener? = null
    private var bufferingListener: OnBufferingListener? = null

    private var pendingUri: Uri? = null
    private var pendingPath: String? = null
    private var pendingHeaders: Map<String, String>? = null
    private var pendingIsHttpSource: Boolean = false
    private var prepared: Boolean = false
    private var released: Boolean = false
    private var ownsPlayer: Boolean = false
    private var audioSessionId: Int = 0
    private var leftVolume = 1f
    private var rightVolume = 1f
    
    /**
     * Mark this instance as part of a crossfade transition.
     * Crossfade components are allowed to overlap with other players briefly.
     */
    @JvmField
    var isCrossfadeComponent: Boolean = false

    @JvmOverloads
    constructor(context: Context, lowBuffer: Boolean = false, backgroundBuffer: Boolean = false) {
        this.appContext = context.applicationContext
        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val loadControl = when {
            backgroundBuffer -> {
                // Progressive background download: buffer up to 5 minutes of audio
                // so the entire next track gets cached to disk via CacheDataSource.
                // RAM usage is bounded (~5MB for 5 min of 128kbps MP3).
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(30_000, 300_000, 250, 500)
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .build()
            }
            lowBuffer -> {
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(5_000, 15_000, 250, 500)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            }
            else -> {
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 50_000, 250, 500)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            }
        }

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

        val player = ExoPlayer.Builder(appContext, renderersFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        this.exoPlayer = player
        this.ownsPlayer = true
        this.audioSessionId = player.audioSessionId
        player.addListener(playerListener)
        activeInstances.add(WeakReference(this))
    }

    /**
     * Constructor that uses a shared ExoPlayer instance to reduce startup latency.
     * @param context Application context
     * @param sharedExoPlayer Pre-initialized ExoPlayer instance
     */
    constructor(context: Context, sharedExoPlayer: ExoPlayer) {
        this.appContext = context.applicationContext
        this.exoPlayer = sharedExoPlayer
        this.ownsPlayer = false
        this.audioSessionId = sharedExoPlayer.audioSessionId
        sharedExoPlayer.addListener(playerListener)
        activeInstances.add(WeakReference(this))
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY && !prepared) {
                prepared = true
                preparedListener?.let { listener ->
                    mainHandler.post {
                        if (!released) {
                            listener.onPrepared(this@ExoMediaPlayer)
                        }
                    }
                }
            } else if (playbackState == Player.STATE_ENDED) {
                completionListener?.let { listener ->
                    mainHandler.post {
                        if (!released) {
                            listener.onCompletion(this@ExoMediaPlayer)
                        }
                    }
                }
            }
            // Notify buffering state changes for seek feedback
            bufferingListener?.let { listener ->
                val isBuffering = playbackState == Player.STATE_BUFFERING
                mainHandler.post {
                    if (!released) {
                        listener.onBufferingChanged(this@ExoMediaPlayer, isBuffering)
                    }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "onPlayerError: code=${error.errorCode} message=${error.message}")
            errorListener?.let { listener ->
                mainHandler.post {
                    if (!released) {
                        listener.onError(this@ExoMediaPlayer, error.errorCode, 0)
                    }
                }
            }
        }
    }

    fun setAudioAttributes(attrs: AudioAttributes) {
        if (released) return
        val player = exoPlayer ?: return
        // Map framework AudioAttributes to Media3
        val media3Attrs = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        player.setAudioAttributes(media3Attrs, false)
    }

    fun setOnPreparedListener(l: OnPreparedListener?) {
        this.preparedListener = l
    }

    fun setOnCompletionListener(l: OnCompletionListener?) {
        this.completionListener = l
    }

    fun setOnErrorListener(l: OnErrorListener?) {
        this.errorListener = l
    }

    fun setOnBufferingListener(l: OnBufferingListener?) {
        this.bufferingListener = l
    }

    fun setDataSource(context: Context, uri: Uri, headers: Map<String, String>?) {
        this.pendingUri = uri
        this.pendingPath = null
        this.pendingHeaders = headers?.let { HashMap(it) }
        val scheme = uri.scheme
        this.pendingIsHttpSource = "http".equals(scheme, ignoreCase = true) || "https".equals(scheme, ignoreCase = true)
    }

    fun setDataSource(path: String) {
        this.pendingPath = path
        this.pendingUri = null
        this.pendingHeaders = null
        this.pendingIsHttpSource = path.startsWith("http://") || path.startsWith("https://")
    }

    /**
     * Alias of [prepareAsync] — ExoPlayer always prepares asynchronously.
     * Callers that relied on MediaPlayer's blocking `prepare()` should
     * wait for [OnPreparedListener] before interacting.
     */
    fun prepare() {
        prepareAsync()
    }

    fun prepareAsync() {
        if (released) {
            throw IllegalStateException("ExoMediaPlayer released")
        }
        val player = exoPlayer ?: throw IllegalStateException("ExoMediaPlayer released")
        val uri = pendingUri ?: pendingPath?.let { Uri.parse(it) } ?: throw IllegalStateException("No data source set")

        val factory: DataSource.Factory = if (pendingIsHttpSource) {
            // Usar DefaultHttpDataSource (HttpURLConnection nativo de Android)
            // en vez de OkHttpDataSource — el TLS fingerprint de OkHttp es detectado
            // por el CDN de YouTube causando 403, mientras que HttpURLConnection
            // tiene un fingerprint consistente con el de una app Android real.
            val httpFactory = DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(8_000)
                .setReadTimeoutMs(8_000)
                .setAllowCrossProtocolRedirects(true)

            pendingHeaders?.let { headers ->
                if (headers.isNotEmpty()) {
                    httpFactory.setDefaultRequestProperties(headers)
                }
            }
            // Wrap with CacheDataSource for disk caching of streamed audio
            CacheDataSource.Factory()
                .setCache(getSharedCache(appContext))
                .setUpstreamDataSourceFactory(httpFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } else {
            DefaultDataSource.Factory(appContext)
        }

        val mediaItem = MediaItem.fromUri(uri)

        val source = ProgressiveMediaSource.Factory(factory)
            .createMediaSource(mediaItem)

        player.setMediaSource(source)
        player.volume = Math.max(leftVolume, rightVolume)
        player.prepare()
        prepared = false
    }

    fun start() {
        if (released) return
        val player = exoPlayer ?: return
        
        // Enforce single-playback policy (Global Guardian)
        stopOthers(this)
        
        player.playWhenReady = true
        if (player.playbackState == Player.STATE_IDLE) {
            player.prepare()
        }
    }

    fun pause() {
        if (released) return
        exoPlayer?.playWhenReady = false
    }

    fun stop() {
        if (released) return
        if (ownsPlayer) {
            exoPlayer?.stop()
        } else {
            // Soft stop for shared player to keep decoders warm
            exoPlayer?.playWhenReady = false
        }
    }

    fun isPlaying(): Boolean {
        if (released) return false
        val player = exoPlayer ?: return false
        return player.isPlaying || (player.playWhenReady &&
                player.playbackState != Player.STATE_IDLE &&
                player.playbackState != Player.STATE_ENDED)
    }

    fun seekTo(msec: Int) {
        if (released) return
        exoPlayer?.seekTo(Math.max(0L, msec.toLong()))
    }

    fun getCurrentPosition(): Int {
        if (released) return 0
        return (exoPlayer?.currentPosition?.coerceAtLeast(0L)?.toInt()) ?: 0
    }

    fun getDuration(): Int {
        if (released) return 0
        val d = exoPlayer?.duration ?: 0L
        if (d == C.TIME_UNSET || d < 0) return 0
        return d.toInt()
    }

    fun setVolume(left: Float, right: Float) {
        this.leftVolume = left
        this.rightVolume = right
        if (released) return
        exoPlayer?.volume = Math.max(left, right)
    }

    fun getAudioSessionId(): Int {
        if (released) return audioSessionId
        return exoPlayer?.audioSessionId ?: audioSessionId
    }

    fun getExoPlayer(): Player? {
        if (released) return null
        return exoPlayer
    }

    fun attachPlayerView(playerView: androidx.media3.ui.PlayerView?) {
        if (released) return
        exoPlayer?.let { playerView?.player = it }
    }

    fun detachPlayerView(playerView: androidx.media3.ui.PlayerView?) {
        if (playerView?.player === exoPlayer) {
            playerView?.player = null
        }
    }

    fun release() {
        if (released) return
        released = true
        prepared = false
        preparedListener = null
        completionListener = null
        errorListener = null
        exoPlayer?.let { player ->
            try {
                player.removeListener(playerListener)
                if (ownsPlayer) {
                    player.stop()
                    player.clearMediaItems()
                    player.release()
                } else {
                    // For shared player: just pause it.
                    // DO NOT clear media items or stop aggressively, so decoders remain warm
                    // and transitions to the next track are nearly instantaneous.
                    player.playWhenReady = false
                }
            } catch (e: Exception) {
                Log.w(TAG, "release: exception", e)
            }
            exoPlayer = null
        }
    }
}
