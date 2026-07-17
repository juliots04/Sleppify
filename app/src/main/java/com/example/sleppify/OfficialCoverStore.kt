package com.example.sleppify

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * Registro durable de la portada OFICIAL que YouTube entrega para una playlist (playlistId → url).
 *
 * Las playlists que vienen de YT Music con imagen propia (Recap/Replay, mixes personales, listas
 * editoriales, e incluso las del usuario con su miniatura de YT) deben mostrar ESA imagen en todas
 * las superficies — home, biblioteca, descargas, detalle, búsqueda — y no el collage 2x2 que la
 * app sintetiza con las primeras 4 carátulas. El collage queda solo para listas sin portada
 * conocida (p.ej. playlists locales creadas en la app).
 *
 * Se escribe desde los puntos donde YT es la FUENTE del dato (tarjetas del home browse, filas de
 * biblioteca), nunca desde imágenes de pistas sueltas, para no registrar carátulas de canciones
 * como portada de playlist.
 */
object OfficialCoverStore {

    private const val PREFS = "official_playlist_covers"

    private val mem = ConcurrentHashMap<String, String>()

    @Volatile
    private var loaded = false

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                for ((k, v) in prefs(ctx).all) {
                    if (v is String && v.isNotEmpty()) mem.putIfAbsent(k, v)
                }
            } catch (_: Exception) {
            }
            loaded = true
        }
    }

    /** Registra (o refresca) la portada oficial. No-op con id/url vacíos o ids no-YT (locales). */
    @JvmStatic
    fun save(ctx: Context, playlistId: String?, url: String?) {
        val id = playlistId?.trim().orEmpty()
        val u = url?.trim().orEmpty()
        if (id.isEmpty() || u.isEmpty()) return
        // Solo urls remotas de YT/Google (nunca file:// ni content://) y nunca ids locales.
        if (!u.startsWith("http")) return
        if (LocalFilesStore.isLocalAlbumId(id) || LocalFilesStore.PLAYLIST_ID == id) return
        ensureLoaded(ctx)
        if (mem.put(id, u) == u) return
        try {
            prefs(ctx).edit().putString(id, u).apply()
        } catch (_: Exception) {
        }
    }

    /** Portada oficial registrada para esta playlist, o null si no se conoce. */
    @JvmStatic
    fun get(ctx: Context, playlistId: String?): String? {
        val id = playlistId?.trim().orEmpty()
        if (id.isEmpty()) return null
        ensureLoaded(ctx)
        return mem[id]?.takeIf { it.isNotEmpty() }
    }
}
