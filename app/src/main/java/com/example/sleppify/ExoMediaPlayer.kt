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
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.SeekParameters
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

    interface OnRenderedFirstFrameListener {
        fun onRenderedFirstFrame(mp: ExoMediaPlayer)
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

        /**
         * Clave de caché ESTABLE por contenido para URLs de googlevideo: id + itag. La URL
         * completa caduca cada ~6h (cambian expire/sig/ip) y con la clave default (la URI entera)
         * los bytes ya cacheados quedaban huérfanos en cada re-resolución — una canción cacheada
         * al 100% volvía a descargarse entera. Con la clave estable: pista totalmente cacheada →
         * reproduce DESDE DISCO al instante sin red aunque la URL haya caducado; pista parcial →
         * reusa los rangos ya bajados con la URL nueva. itag en la clave separa calidades (bytes
         * distintos). URLs no-googlevideo conservan la URI como clave (comportamiento default).
         */
        @JvmStatic
        fun stableCacheKey(uri: Uri): String {
            val host = uri.host ?: return uri.toString()
            if (!host.contains("googlevideo")) return uri.toString()
            val id = try { uri.getQueryParameter("id") } catch (_: Exception) { null }
                ?: return uri.toString()
            val itag = try { uri.getQueryParameter("itag") } catch (_: Exception) { null } ?: ""
            return "gv:$id:$itag"
        }

        /** Fábrica compartida por playback ([prepareAsync]) y warm ([warmStreamHead]) — las claves
         *  DEBEN coincidir entre ambos para que el head calentado se lea al preparar. */
        @JvmStatic
        val stableCacheKeyFactory: androidx.media3.datasource.cache.CacheKeyFactory =
            androidx.media3.datasource.cache.CacheKeyFactory { spec -> stableCacheKey(spec.uri) }

        /**
         * True si el audio de [videoId] está COMPLETO en el exo_stream_cache (los bytes quedaron
         * en disco al reproducir la canción entera). Recorre las claves estables "gv:id:itag" del
         * id y compara los bytes cacheados contra el content length que registró Media3 — solo un
         * stream entero cuenta como descargado-de-facto. El índice de SimpleCache vive en
         * memoria, así que es barato.
         */
        @JvmStatic
        fun isFullyCached(context: Context, videoId: String): Boolean {
            if (videoId.isBlank()) return false
            return try {
                val cache = getSharedCache(context)
                val prefix = "gv:$videoId:"
                for (key in cache.keys) {
                    if (!key.startsWith(prefix)) continue
                    val len = androidx.media3.datasource.cache.ContentMetadata.getContentLength(
                        cache.getContentMetadata(key)
                    )
                    if (len <= 0) continue
                    if (cache.getCachedBytes(key, 0, len) >= len) return true
                }
                false
            } catch (_: Throwable) {
                false
            }
        }

        /**
         * Borra del exo_stream_cache TODOS los streams de [videoId] (todas las calidades). Solo
         * para la acción EXPLÍCITA del usuario "eliminar descarga" sobre una canción que estaba
         * disponible offline vía caché — la política de nunca evictar el cache aplica a la
         * gestión automática, no a un borrado pedido por el usuario.
         */
        @JvmStatic
        fun removeCachedAudio(context: Context, videoId: String) {
            if (videoId.isBlank()) return
            try {
                val cache = getSharedCache(context)
                val prefix = "gv:$videoId:"
                for (key in cache.keys.toList()) {
                    if (key.startsWith(prefix)) {
                        try { cache.removeResource(key) } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {
            }
        }

        /**
         * C2 — warms roughly the first [lengthBytes] of [url] into the SHARED exo_stream_cache on a
         * background thread, best-effort and cancellable. Uses the SAME CacheDataSource factory +
         * browser [headers] as playback ([prepareAsync]) so the cache key (= the URL) matches: when
         * the mode swap later prepares this URL, ExoPlayer reads the head straight from disk instead
         * of opening a cold googlevideo connection.
         *
         * The shared cache uses NoOpCacheEvictor by design (project rule: never cap/evict it); a
         * ~1.5MB head per track is acceptable. All errors are swallowed (cancellation throws EOF/
         * interrupted, network can 403, etc.). Returns a [WarmHandle] whose [WarmHandle.cancel]
         * aborts the in-flight read.
         */
        @JvmStatic
        fun warmStreamHead(
            context: Context,
            url: String?,
            headers: Map<String, String>?,
            lengthBytes: Long
        ): WarmHandle {
            val handle = WarmHandle()
            if (url.isNullOrBlank()) return handle
            val appContext = context.applicationContext
            Thread({ runStreamHeadWarm(appContext, url, headers, lengthBytes, handle) }, "ExoWarm").start()
            return handle
        }

        /** Blocking body of [warmStreamHead], run on the ExoWarm thread. Swallows all errors. */
        private fun runStreamHeadWarm(
            appContext: Context,
            url: String,
            headers: Map<String, String>?,
            lengthBytes: Long,
            handle: WarmHandle
        ) {
            if (handle.isCancelled()) return
            try {
                val httpFactory = DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(20_000)
                    .setReadTimeoutMs(20_000)
                    .setAllowCrossProtocolRedirects(true)
                if (!headers.isNullOrEmpty()) {
                    httpFactory.setDefaultRequestProperties(headers)
                }
                // Identical wrapping to prepareAsync() so cache keys line up.
                val cacheDataSource = CacheDataSource.Factory()
                    .setCache(getSharedCache(appContext))
                    .setCacheKeyFactory(stableCacheKeyFactory)
                    .setUpstreamDataSourceFactory(httpFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                    .createDataSource()
                val dataSpec = DataSpec.Builder()
                    .setUri(Uri.parse(url))
                    .setPosition(0)
                    .setLength(lengthBytes)
                    .build()
                val writer = CacheWriter(cacheDataSource, dataSpec, null, null)
                handle.attach(writer)
                if (handle.isCancelled()) {
                    try { writer.cancel() } catch (_: Throwable) { }
                    return
                }
                writer.cache()
                Log.d(TAG, "warmStreamHead: cached head (~$lengthBytes bytes)")
            } catch (t: Throwable) {
                // Best-effort warm — cancellation, EOF, 403, etc. are all non-fatal.
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
    private var renderedFirstFrameListener: OnRenderedFirstFrameListener? = null

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
                    // Back-buffer: los seeks hacia atrás dentro de lo recién reproducido se
                    // sirven de RAM en vez de re-pedir el rango al CDN (el default es 0).
                    .setBackBuffer(15_000, true)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            }
            else -> {
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 50_000, 250, 500)
                    // Mismo back-buffer que el player compartido (ExoPlayerManager): tras un
                    // hot-swap o crossfade el player activo es uno de estos PROPIOS, y sin
                    // back-buffer cada seek hacia atrás re-descargaba desde el CDN.
                    .setBackBuffer(30_000, true)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            }
        }

        val renderersFactory = DefaultRenderersFactory(appContext)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        val player = ExoPlayer.Builder(appContext, renderersFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        // Seek al keyframe más cercano (ver ExoPlayerManager): sin esto, un seek en una fuente
        // con video decodifica desde el keyframe previo hasta el frame exacto antes de READY.
        // El player del hot-swap lo revierte a EXACT hasta el commit (setSeekToClosestSync).
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
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

    // IMPORTANT: the posted dispatches below re-read the listener FIELD at execution time
    // instead of capturing the listener at event time. Capturing meant that replacing or
    // nulling a listener could not stop an already-queued post — e.g. a stale error post
    // installed by a finished crossfade could release the player AFTER it had been promoted
    // to the active player, silently killing playback.
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY && !prepared) {
                prepared = true
                mainHandler.post {
                    if (!released) {
                        preparedListener?.onPrepared(this@ExoMediaPlayer)
                    }
                }
            } else if (playbackState == Player.STATE_ENDED) {
                mainHandler.post {
                    if (!released) {
                        completionListener?.onCompletion(this@ExoMediaPlayer)
                    }
                }
            }
            // Notify buffering state changes for seek feedback
            if (bufferingListener != null) {
                val isBuffering = playbackState == Player.STATE_BUFFERING
                mainHandler.post {
                    if (!released) {
                        bufferingListener?.onBufferingChanged(this@ExoMediaPlayer, isBuffering)
                    }
                }
            }
        }

        override fun onRenderedFirstFrame() {
            mainHandler.post {
                if (!released) {
                    renderedFirstFrameListener?.onRenderedFirstFrame(this@ExoMediaPlayer)
                }
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            // The value captured in the constructor is usually AUDIO_SESSION_ID_UNSET (the sink
            // isn't initialized yet) — this callback is the only reliable source of the real
            // session. Feed it to the EQ service so app-scoped EQ can attach to the live session.
            this@ExoMediaPlayer.audioSessionId = audioSessionId
            // Solo el player AUDIBLE reporta su sesión al EQ. Media3 genera y despacha el id en
            // la construcción (sin reproducir nada), así que los players desechables (pre-buffer
            // gapless, crossfade entrante, swap de modo) pisaban KEY_PLAYER_SESSION_ID con
            // sesiones mudas que mueren al release — el EQ solo-app quedaba colgado de una
            // sesión muerta. El flag lo activa markAsActiveForEq() al promocionar el player.
            if (reportsSessionToEq) {
                AudioEffectsService.notifyPlayerSessionChanged(appContext, audioSessionId)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "onPlayerError: code=${error.errorCode} message=${error.message}")
            mainHandler.post {
                if (!released) {
                    errorListener?.onError(this@ExoMediaPlayer, error.errorCode, 0)
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

    fun setOnRenderedFirstFrameListener(l: OnRenderedFirstFrameListener?) {
        this.renderedFirstFrameListener = l
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
                .setConnectTimeoutMs(20_000)
                .setReadTimeoutMs(20_000)
                .setAllowCrossProtocolRedirects(true)

            pendingHeaders?.let { headers ->
                if (headers.isNotEmpty()) {
                    httpFactory.setDefaultRequestProperties(headers)
                }
            }
            // Wrap with CacheDataSource for disk caching of streamed audio. Clave estable por
            // contenido (ver stableCacheKey): una canción ya cacheada reproduce desde disco al
            // instante aunque su URL de googlevideo haya caducado.
            CacheDataSource.Factory()
                .setCache(getSharedCache(appContext))
                .setCacheKeyFactory(stableCacheKeyFactory)
                .setUpstreamDataSourceFactory(httpFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } else {
            DefaultDataSource.Factory(appContext)
        }

        val mediaItem = MediaItem.fromUri(uri)

        val loadErrorPolicy = object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                val e = loadErrorInfo.exception
                if (e is java.io.EOFException || e is java.net.SocketTimeoutException || e.cause is java.io.EOFException) {
                    val retryCount = loadErrorInfo.errorCount
                    if (retryCount < 15) {
                        return Math.min((retryCount - 1) * 1000L + 500L, 5000L)
                    }
                }
                return super.getRetryDelayMsFor(loadErrorInfo)
            }
        }

        val source = ProgressiveMediaSource.Factory(factory)
            .setLoadErrorHandlingPolicy(loadErrorPolicy)
            .createMediaSource(mediaItem)

        player.stop()
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

    fun getPlayWhenReady(): Boolean {
        if (released) return false
        return exoPlayer?.playWhenReady ?: false
    }

    fun seekTo(msec: Int) {
        if (released) return
        exoPlayer?.seekTo(Math.max(0L, msec.toLong()))
    }

    /**
     * Alterna entre seek al keyframe más cercano (default, rápido en fuentes con video) y seek
     * EXACT. El hot-swap Audio↔Video necesita EXACT mientras sincroniza su player en silencio:
     * el intercambio sin pausa depende de que viejo y nuevo queden a <~900ms de drift, y
     * CLOSEST_SYNC en un mp4 puede caer segundos lejos (GOPs del itag-18). Al adoptar el player
     * como activo se vuelve a CLOSEST_SYNC para que los seeks del usuario sean rápidos.
     */
    fun setSeekToClosestSync(enabled: Boolean) {
        if (released) return
        exoPlayer?.setSeekParameters(
            if (enabled) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT
        )
    }

    fun getCurrentPosition(): Int {
        if (released) return 0
        return (exoPlayer?.currentPosition?.coerceAtLeast(0L)?.toInt()) ?: 0
    }

    fun getBufferedPosition(): Int {
        if (released) return 0
        return (exoPlayer?.bufferedPosition?.coerceAtLeast(0L)?.toInt()) ?: 0
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

    // True solo para el player que SUENA: es el único que puede persistir su sesión de audio
    // para el EQ solo-app (ver onAudioSessionIdChanged).
    @Volatile private var reportsSessionToEq: Boolean = false

    /**
     * Marca este player como el AUDIBLE y reporta su sesión al EQ solo-app. Llamar en cada
     * promoción (start normal, adopción del shared/pre-buffer, commit de crossfade o de swap
     * de modo). El reporte inmediato es imprescindible para el player COMPARTIDO: su id se
     * generó una vez en ExoPlayerManager.initialize() y nunca cambia, así que
     * onAudioSessionIdChanged jamás dispara para él; para players propios recién construidos
     * cuyo id aún no llegó (UNSET/-1), el listener gateado lo reporta cuando aterrice.
     */
    fun markAsActiveForEq() {
        reportsSessionToEq = true
        val sid = getAudioSessionId()
        if (sid > 0) {
            AudioEffectsService.notifyPlayerSessionChanged(appContext, sid)
        }
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
        releaseInternal(false)
    }

    /**
     * Igual que [release], pero la liberación PESADA del player propio (ExoPlayer.release(),
     * que bloquea al caller hasta ~500ms esperando su hilo interno) se difiere a un mensaje
     * posterior del main looper. El stop + detach de listeners corre YA, así el caller (el
     * gesto de seek, el commit del hot-swap, un cambio de pista) nunca paga el release dentro
     * de su propio frame. Media3 exige release() en el hilo donde se construyó el player
     * (main) — por eso se postea al mainHandler, nunca a un executor de fondo.
     */
    fun releaseAsync() {
        releaseInternal(true)
    }

    private fun releaseInternal(deferHeavyRelease: Boolean) {
        if (released) return
        released = true
        // Si este era el player audible con dueño propio, su sesión de audio muere con el
        // release: invalida la persistida para que el EQ solo-app caiga a global en vez de
        // quedarse atado a una sesión muerta. El shared player sobrevive al wrapper — no tocar.
        if (reportsSessionToEq && ownsPlayer) {
            try { AudioEffectsService.notifyPlayerSessionInvalid(appContext) } catch (_: Exception) { }
        }
        reportsSessionToEq = false
        prepared = false
        preparedListener = null
        completionListener = null
        errorListener = null
        bufferingListener = null
        renderedFirstFrameListener = null
        // Drop this instance from the active registry so released players don't accumulate as
        // dead WeakReferences (stopOthers only prunes lazily while iterating).
        activeInstances.removeIf { it.get() === this }
        exoPlayer?.let { player ->
            try {
                player.removeListener(playerListener)
                if (ownsPlayer) {
                    Log.d(TAG, "[PLAYBACK_DBG] release: OWNED player — stop+release hash=${this.hashCode()} deferred=$deferHeavyRelease")
                    player.stop()
                    player.clearMediaItems()
                    if (deferHeavyRelease) {
                        mainHandler.post {
                            try {
                                player.release()
                            } catch (e: Exception) {
                                Log.w(TAG, "deferred release: exception", e)
                            }
                        }
                    } else {
                        player.release()
                    }
                } else {
                    // For shared player: stop and clear to prevent stale audio from continuing
                    Log.d(TAG, "[PLAYBACK_DBG] release: SHARED player — stop+clear hash=${this.hashCode()}")
                    player.stop()
                    player.clearMediaItems()
                    player.playWhenReady = false
                }
            } catch (e: Exception) {
                Log.w(TAG, "release: exception", e)
            }
            exoPlayer = null
        }
    }

    /**
     * Cancellable handle for a [warmStreamHead] task. Thread-safe: the warm runs on its own thread
     * while [cancel] may be called from the main thread. If cancel arrives before the CacheWriter is
     * attached, the pending flag makes the worker bail out (or cancel the writer as soon as it exists).
     */
    class WarmHandle internal constructor() {
        @Volatile private var cancelled = false
        private var writer: CacheWriter? = null

        internal fun isCancelled(): Boolean = cancelled

        @Synchronized internal fun attach(w: CacheWriter) {
            writer = w
            if (cancelled) {
                try { w.cancel() } catch (_: Throwable) { }
            }
        }

        @Synchronized fun cancel() {
            cancelled = true
            try { writer?.cancel() } catch (_: Throwable) { }
        }
    }
}
