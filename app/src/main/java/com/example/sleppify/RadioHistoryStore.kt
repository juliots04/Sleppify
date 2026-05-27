package com.example.sleppify

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object RadioHistoryStore {

    private const val PREFS_NAME = "sleppify_radio_history"
    private const val KEY_RADIOS = "radio_entries"
    private const val KEY_PINNED = "pinned_radio_ids"
    private const val MAX_UNPINNED = 10

    data class RadioEntry(
        val radioPlaylistId: String,
        val songTitle: String,
        val songThumbnail: String,
        val tracks: List<RadioTrack>,
        val createdAt: Long
    )

    data class RadioTrack(
        val videoId: String,
        val title: String,
        val artist: String,
        val thumbnailUrl: String
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveRadio(
        context: Context,
        radioPlaylistId: String,
        songTitle: String,
        songThumbnail: String,
        tracks: List<RadioTrack>
    ) {
        if (radioPlaylistId.isEmpty() || tracks.isEmpty()) return
        val prefs = getPrefs(context)
        val existing = loadRadiosInternal(prefs)

        // Remove existing entry with same id (to update / move to front)
        val filtered = existing.filter { it.getString("radioPlaylistId") != radioPlaylistId }

        val entry = JSONObject().apply {
            put("radioPlaylistId", radioPlaylistId)
            put("songTitle", songTitle)
            put("songThumbnail", songThumbnail)
            put("createdAt", System.currentTimeMillis())
            val arr = JSONArray()
            for (t in tracks) {
                arr.put(JSONObject().apply {
                    put("videoId", t.videoId)
                    put("title", t.title)
                    put("artist", t.artist)
                    put("thumbnailUrl", t.thumbnailUrl)
                })
            }
            put("tracks", arr)
        }

        // Insert at front (most recent)
        val updated = mutableListOf(entry)
        updated.addAll(filtered)

        // Enforce limit: keep all pinned + max unpinned
        val pinnedIds = loadPinnedIds(prefs)
        val result = mutableListOf<JSONObject>()
        var unpinnedCount = 0
        for (obj in updated) {
            val id = obj.optString("radioPlaylistId", "")
            if (pinnedIds.contains(id)) {
                result.add(obj)
            } else {
                if (unpinnedCount < MAX_UNPINNED) {
                    result.add(obj)
                    unpinnedCount++
                }
            }
        }

        prefs.edit().putString(KEY_RADIOS, JSONArray(result).toString()).apply()
    }

    fun getRadios(context: Context): List<RadioEntry> {
        val prefs = getPrefs(context)
        val list = loadRadiosInternal(prefs)
        return list.mapNotNull { parseEntry(it) }
    }

    fun deleteRadio(context: Context, radioPlaylistId: String) {
        val prefs = getPrefs(context)
        val existing = loadRadiosInternal(prefs)
        val filtered = existing.filter { it.optString("radioPlaylistId") != radioPlaylistId }
        prefs.edit().putString(KEY_RADIOS, JSONArray(filtered).toString()).apply()
        // Also unpin if pinned
        unpinRadio(context, radioPlaylistId)
    }

    fun isPinned(context: Context, radioPlaylistId: String): Boolean {
        return loadPinnedIds(getPrefs(context)).contains(radioPlaylistId)
    }

    fun pinRadio(context: Context, radioPlaylistId: String) {
        val prefs = getPrefs(context)
        val pinned = loadPinnedIds(prefs).toMutableSet()
        pinned.add(radioPlaylistId)
        prefs.edit().putString(KEY_PINNED, JSONArray(pinned.toList()).toString()).apply()
    }

    fun unpinRadio(context: Context, radioPlaylistId: String) {
        val prefs = getPrefs(context)
        val pinned = loadPinnedIds(prefs).toMutableSet()
        if (pinned.remove(radioPlaylistId)) {
            prefs.edit().putString(KEY_PINNED, JSONArray(pinned.toList()).toString()).apply()
        }
    }

    // --- Pinned playlists (any type, not just radios) ---

    private const val PREFS_PINNED_PLAYLISTS = "sleppify_pinned_playlists"
    private const val KEY_PINNED_PLAYLISTS = "pinned_ids"

    private fun getPinnedPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_PINNED_PLAYLISTS, Context.MODE_PRIVATE)
    }

    fun isPlaylistPinned(context: Context, contentId: String): Boolean {
        return loadPinnedPlaylistIds(getPinnedPrefs(context)).contains(contentId)
    }

    fun pinPlaylist(context: Context, contentId: String) {
        val prefs = getPinnedPrefs(context)
        val pinned = loadPinnedPlaylistIds(prefs).toMutableList()
        if (!pinned.contains(contentId)) {
            pinned.add(contentId)
            prefs.edit().putString(KEY_PINNED_PLAYLISTS, JSONArray(pinned).toString()).apply()
        }
    }

    fun unpinPlaylist(context: Context, contentId: String) {
        val prefs = getPinnedPrefs(context)
        val pinned = loadPinnedPlaylistIds(prefs).toMutableList()
        if (pinned.remove(contentId)) {
            prefs.edit().putString(KEY_PINNED_PLAYLISTS, JSONArray(pinned).toString()).apply()
        }
    }

    fun getPinnedPlaylistIds(context: Context): List<String> {
        return loadPinnedPlaylistIds(getPinnedPrefs(context))
    }

    // --- Internal helpers ---

    private fun loadRadiosInternal(prefs: SharedPreferences): List<JSONObject> {
        val json = prefs.getString(KEY_RADIOS, "[]") ?: "[]"
        val result = mutableListOf<JSONObject>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                result.add(arr.getJSONObject(i))
            }
        } catch (_: JSONException) {}
        return result
    }

    private fun loadPinnedIds(prefs: SharedPreferences): Set<String> {
        val json = prefs.getString(KEY_PINNED, "[]") ?: "[]"
        val set = mutableSetOf<String>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                set.add(arr.getString(i))
            }
        } catch (_: JSONException) {}
        return set
    }

    private fun loadPinnedPlaylistIds(prefs: SharedPreferences): List<String> {
        val json = prefs.getString(KEY_PINNED_PLAYLISTS, "[]") ?: "[]"
        val list = mutableListOf<String>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
        } catch (_: JSONException) {}
        return list
    }

    private fun parseEntry(obj: JSONObject): RadioEntry? {
        val id = obj.optString("radioPlaylistId", "")
        if (id.isEmpty()) return null
        val title = obj.optString("songTitle", "")
        val thumb = obj.optString("songThumbnail", "")
        val createdAt = obj.optLong("createdAt", 0L)
        val tracksArr = obj.optJSONArray("tracks") ?: return null
        val tracks = mutableListOf<RadioTrack>()
        for (i in 0 until tracksArr.length()) {
            val t = tracksArr.optJSONObject(i) ?: continue
            tracks.add(RadioTrack(
                t.optString("videoId", ""),
                t.optString("title", ""),
                t.optString("artist", ""),
                t.optString("thumbnailUrl", "")
            ))
        }
        return RadioEntry(id, title, thumb, tracks, createdAt)
    }
}
