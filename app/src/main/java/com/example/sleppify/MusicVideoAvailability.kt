package com.example.sleppify

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Registro durable de "¿esta canción tiene video musical?" por videoId — la señal que alimenta la
 * pastilla Canción|Video del player (se bloquea en Canción cuando NO existe video, en vez de
 * reproducir el mp4 de portada estática del upload "Topic").
 *
 * La fuente de verdad es el /next de YT Music: un item envuelto en playlistPanelVideoWrapperRenderer
 * con `counterpart` tiene pareja canción↔video; un playlistPanelVideoRenderer suelto con
 * musicVideoType ATV no la tiene (misma semántica que ytmusicapi parsers/watch.py). Se alimenta
 * GRATIS desde cada radio/cola que la app ya parsea ([YouTubeMusicService.parseMixTracks]) y bajo
 * demanda con el probe [YouTubeMusicService.fetchVideoCounterpart].
 */
object MusicVideoAvailability {

    enum class State { UNKNOWN, YES, NO }

    private const val PREFS = "music_video_availability"

    // videoId -> tiene video. counterparts: videoId -> videoId a reproducir en modo video.
    private val mem = ConcurrentHashMap<String, Boolean>()
    private val counterparts = ConcurrentHashMap<String, String>()

    @Volatile
    private var prefs: SharedPreferences? = null

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mv-avail").apply { isDaemon = true }
    }

    /** Carga el caché persistido y habilita la persistencia. Idempotente; llamar desde App. */
    @JvmStatic
    fun init(context: Context) {
        if (prefs != null) return
        val appCtx = context.applicationContext
        io.execute {
            try {
                val p = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                for ((k, v) in p.all) {
                    when {
                        k.startsWith("has_") && v is Boolean -> mem.putIfAbsent(k.removePrefix("has_"), v)
                        k.startsWith("cp_") && v is String && v.isNotEmpty() ->
                            counterparts.putIfAbsent(k.removePrefix("cp_"), v)
                    }
                }
                prefs = p
            } catch (_: Exception) {
            }
        }
    }

    @JvmStatic
    fun get(videoId: String?): State {
        val id = videoId?.trim().orEmpty()
        if (id.isEmpty()) return State.UNKNOWN
        return when (mem[id]) {
            true -> State.YES
            false -> State.NO
            null -> State.UNKNOWN
        }
    }

    /** El videoId que reproduce la versión VIDEO de esta canción (puede ser ella misma), o null. */
    @JvmStatic
    fun counterpartId(videoId: String?): String? {
        val id = videoId?.trim().orEmpty()
        if (id.isEmpty()) return null
        return counterparts[id]
    }

    @JvmStatic
    @JvmOverloads
    fun put(videoId: String?, hasVideo: Boolean, counterpartVideoId: String? = null) {
        val id = videoId?.trim().orEmpty()
        if (id.isEmpty()) return
        val prev = mem.put(id, hasVideo)
        val cp = counterpartVideoId?.trim().orEmpty()
        val cpChanged = cp.isNotEmpty() && counterparts.put(id, cp) != cp
        if (prev == hasVideo && !cpChanged) return
        val p = prefs ?: return
        io.execute {
            try {
                val e = p.edit().putBoolean("has_$id", hasVideo)
                if (cp.isNotEmpty()) e.putString("cp_$id", cp)
                e.apply()
            } catch (_: Exception) {
            }
        }
    }
}
