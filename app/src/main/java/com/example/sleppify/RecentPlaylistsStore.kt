package com.example.sleppify

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Registro duradero de las playlists y radios abiertas por el usuario en orden cronológico reciente.
 *
 * A diferencia del cálculo previo basado en conteo de canciones reproducidas (PlayCountStore),
 * aquí se registra CUALQUIER playlist que el usuario abra (YT Music, radios, o creadas),
 * SIN requerir contador de reproducciones: basta con que el usuario la abra para que aparezca
 * de inmediato en "Playlists recientes".
 */
object RecentPlaylistsStore {

    private const val PREFS_NAME = "sleppify_recent_opened_playlists"
    private const val KEY_PLAYLISTS_JSON = "recent_playlists_json"
    private const val MAX_ENTRIES = 30
    private val IO = Executors.newSingleThreadExecutor()

    data class RecentPlaylist(
        val playlistId: String,
        val title: String,
        val subtitle: String = "",
        val imageUrl: String = "",
        val isRadio: Boolean = false,
        val lastOpenedAtMs: Long = System.currentTimeMillis()
    )

    @Volatile
    private var cachedEntries: List<RecentPlaylist>? = null

    /**
     * Registra una playlist o radio cuando el usuario la abre.
     */
    @JvmStatic
    fun recordPlaylistOpened(
        context: Context,
        playlistId: String?,
        title: String?,
        subtitle: String? = "",
        imageUrl: String? = "",
        isRadio: Boolean = false
    ) {
        val pid = playlistId?.trim().orEmpty()
        if (pid.isEmpty() || pid == LocalFilesStore.PLAYLIST_ID || LocalFilesStore.isLocalAlbumId(pid)) return
        val appCtx = context.applicationContext

        val cleanTitle = title?.trim().orEmpty().ifEmpty { "Playlist" }
        val cleanSubtitle = subtitle?.trim().orEmpty()
        val cleanThumb = imageUrl?.trim().orEmpty()

        // Si hay portada remota oficial y no es radio, registrar en OfficialCoverStore
        if (cleanThumb.startsWith("http") && !isRadio) {
            OfficialCoverStore.save(appCtx, pid, cleanThumb)
        }

        IO.execute {
            val list = loadEntriesMutable(appCtx)
            val normPid = if (pid.startsWith("VL") && pid.length > 2) pid.substring(2) else pid
            val existingIdx = list.indexOfFirst {
                val itNorm = if (it.playlistId.startsWith("VL") && it.playlistId.length > 2) it.playlistId.substring(2) else it.playlistId
                itNorm == normPid || it.playlistId == pid
            }

            val finalThumb = if (cleanThumb.isNotEmpty()) cleanThumb
                else (if (existingIdx >= 0) list[existingIdx].imageUrl else OfficialCoverStore.get(appCtx, pid).orEmpty())

            val finalTitle = if (cleanTitle.isNotEmpty() && cleanTitle != "Playlist") cleanTitle
                else (if (existingIdx >= 0) list[existingIdx].title else "Playlist")

            val finalSubtitle = if (cleanSubtitle.isNotEmpty()) cleanSubtitle
                else (if (existingIdx >= 0) list[existingIdx].subtitle else "")

            val finalIsRadio = isRadio || (pid.startsWith("RD") && !pid.startsWith("RDCLAK"))

            val entry = RecentPlaylist(
                playlistId = pid,
                title = finalTitle,
                subtitle = finalSubtitle,
                imageUrl = finalThumb,
                isRadio = finalIsRadio,
                lastOpenedAtMs = System.currentTimeMillis()
            )

            if (existingIdx >= 0) {
                list.removeAt(existingIdx)
            }
            list.add(0, entry)

            if (list.size > MAX_ENTRIES) {
                while (list.size > MAX_ENTRIES) {
                    list.removeAt(list.size - 1)
                }
            }

            saveEntries(appCtx, list)
        }
    }

    /**
     * Obtiene las playlists recientemente abiertas en orden de apertura (la más reciente primero).
     */
    @JvmStatic
    fun getRecentPlaylists(context: Context, limit: Int = 10): List<RecentPlaylist> {
        val list = loadEntriesMutable(context.applicationContext)
        return list.take(limit)
    }

    private fun loadEntriesMutable(appCtx: Context): MutableList<RecentPlaylist> {
        cachedEntries?.let { return ArrayList(it) }
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PLAYLISTS_JSON, "") ?: ""
        if (raw.isEmpty()) {
            cachedEntries = emptyList()
            return mutableListOf()
        }
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<RecentPlaylist>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val pid = obj.optString("playlistId", "").trim()
                if (pid.isEmpty()) continue
                list.add(
                    RecentPlaylist(
                        playlistId = pid,
                        title = obj.optString("title", "Playlist"),
                        subtitle = obj.optString("subtitle", ""),
                        imageUrl = obj.optString("imageUrl", ""),
                        isRadio = obj.optBoolean("isRadio", false),
                        lastOpenedAtMs = obj.optLong("lastOpenedAtMs", 0L)
                    )
                )
            }
            cachedEntries = ArrayList(list)
            list
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveEntries(appCtx: Context, entries: List<RecentPlaylist>) {
        cachedEntries = ArrayList(entries)
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject().apply {
                    put("playlistId", e.playlistId)
                    put("title", e.title)
                    put("subtitle", e.subtitle)
                    put("imageUrl", e.imageUrl)
                    put("isRadio", e.isRadio)
                    put("lastOpenedAtMs", e.lastOpenedAtMs)
                }
            )
        }
        appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PLAYLISTS_JSON, arr.toString())
            .apply()
    }
}
