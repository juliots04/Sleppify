package com.example.sleppify

import android.content.Context
import org.json.JSONObject

/** Explicit library saves, independent of recent browsing and remote library refreshes. */
object SavedPlaylistsStore {
    @Volatile private var cached: List<YouTubeMusicService.TrackResult>? = null
    private fun prefs(context: Context) = context.getSharedPreferences("saved_playlists", Context.MODE_PRIVATE)

    @JvmStatic
    fun contains(context: Context, id: String): Boolean = prefs(context).contains(id)

    @JvmStatic @Synchronized
    fun save(context: Context, id: String, title: String, thumbnail: String, params: String, artHint: String) {
        prefs(context).edit().putString(id, JSONObject().apply {
            put("title", title)
            put("thumbnail", thumbnail)
            put("params", params)
            put("artHint", artHint)
        }.toString()).apply()
        cached = null
    }

    @JvmStatic
    fun get(context: Context, id: String): JSONObject? = try {
        prefs(context).getString(id, null)?.let { JSONObject(it) }
    } catch (_: Exception) { null }

    @JvmStatic @Synchronized
    fun getAll(context: Context): List<YouTubeMusicService.TrackResult> {
        cached?.let { return it }
        val result = prefs(context).all.mapNotNull { (id, value) ->
        try {
            val data = JSONObject(value as String)
            YouTubeMusicService.TrackResult("playlist", id, data.optString("title"),
                if (RadioArt.isRadioId(id)) "Radio" else "Playlist", data.optString("thumbnail"))
        } catch (_: Exception) { null }
        }
        cached = result
        return result
    }

    @JvmStatic @Synchronized
    fun remove(context: Context, id: String) {
        prefs(context).edit().remove(id).apply()
        cached = null
    }
}
