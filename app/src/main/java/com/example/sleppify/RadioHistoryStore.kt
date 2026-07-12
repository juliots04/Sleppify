package com.example.sleppify

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
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

    // In-memory copy of the parsed radio list. getRadios() re-parsed every radio AND its full
    // track list from SharedPreferences JSON on each call — it runs on the main thread from the
    // library render pipeline (Radios chip) and the home carousel. Invalidated on the three
    // KEY_RADIOS write paths (saveRadio / deleteRadio / importFromCloud).
    @Volatile
    private var radiosCache: List<RadioEntry>? = null

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
        radiosCache = null
        pushToCloud(context)
    }

    fun getRadios(context: Context): List<RadioEntry> {
        radiosCache?.let { return it }
        val prefs = getPrefs(context)
        val parsed = loadRadiosInternal(prefs).mapNotNull { parseEntry(it) }
        radiosCache = parsed
        return parsed
    }

    fun deleteRadio(context: Context, radioPlaylistId: String) {
        val prefs = getPrefs(context)
        val existing = loadRadiosInternal(prefs)
        val filtered = existing.filter { it.optString("radioPlaylistId") != radioPlaylistId }
        prefs.edit().putString(KEY_RADIOS, JSONArray(filtered).toString()).apply()
        radiosCache = null
        // Also unpin if pinned
        unpinRadio(context, radioPlaylistId)
        pushToCloud(context)
    }

    fun isPinned(context: Context, radioPlaylistId: String): Boolean {
        return loadPinnedIds(getPrefs(context)).contains(radioPlaylistId)
    }

    fun pinRadio(context: Context, radioPlaylistId: String) {
        val prefs = getPrefs(context)
        val pinned = loadPinnedIds(prefs).toMutableSet()
        pinned.add(radioPlaylistId)
        prefs.edit().putString(KEY_PINNED, JSONArray(pinned.toList()).toString()).apply()
        pushToCloud(context)
    }

    fun unpinRadio(context: Context, radioPlaylistId: String) {
        val prefs = getPrefs(context)
        val pinned = loadPinnedIds(prefs).toMutableSet()
        if (pinned.remove(radioPlaylistId)) {
            prefs.edit().putString(KEY_PINNED, JSONArray(pinned.toList()).toString()).apply()
            pushToCloud(context)
        }
    }

    // --- Cloud sync (Firestore persistence so generated radios survive a reinstall) ---

    /**
     * Pushes the whole radio history + pinned set up to Firestore. No-op when signed out (the
     * CloudSyncManager method guards on the active user). Called on every mutation, mirroring
     * ListenHistoryStore.record()'s on-write upload style.
     */
    private fun pushToCloud(context: Context) {
        try {
            CloudSyncManager.getInstance(context.applicationContext)
                .syncRadiosToCloud(context.applicationContext)
        } catch (e: Exception) {
            Log.w("RadioHistoryStore", "syncRadiosToCloud failed", e)
        }
    }

    /** All radio entries as a JSONArray of the raw stored objects (id, title, thumbnail, tracks, createdAt). */
    fun exportToJson(context: Context): JSONArray {
        return JSONArray(loadRadiosInternal(getPrefs(context)))
    }

    /** The pinned radio ids as a JSONArray. */
    fun exportPinnedToJson(context: Context): JSONArray {
        return JSONArray(loadPinnedIds(getPrefs(context)).toList())
    }

    /**
     * Download side of sync (called during sign-in hydration). Merges cloud radios/pins into local:
     * union by radioPlaylistId keeping the newer createdAt, then re-applies the pinned-aware
     * MAX_UNPINNED cap (mirrors saveRadio). Does NOT re-upload. Empty cloud data leaves local
     * untouched, so a fresh/empty cloud doc can never wipe existing local radios.
     */
    fun importFromCloud(context: Context, radiosArr: JSONArray, pinnedArr: JSONArray) {
        val prefs = getPrefs(context)

        // 1. Merge pinned ids (union of local + cloud).
        val mergedPinned = loadPinnedIds(prefs).toMutableSet()
        for (i in 0 until pinnedArr.length()) {
            val id = pinnedArr.optString(i, "")
            if (id.isNotEmpty()) mergedPinned.add(id)
        }

        // 2. Merge radio entries by id, preferring the newer createdAt.
        val byId = LinkedHashMap<String, JSONObject>()
        for (obj in loadRadiosInternal(prefs)) {
            val id = obj.optString("radioPlaylistId", "")
            if (id.isNotEmpty()) byId[id] = obj
        }
        for (i in 0 until radiosArr.length()) {
            val obj = radiosArr.optJSONObject(i) ?: continue
            val id = obj.optString("radioPlaylistId", "")
            if (id.isEmpty()) continue
            val existing = byId[id]
            if (existing == null || obj.optLong("createdAt", 0L) > existing.optLong("createdAt", 0L)) {
                byId[id] = obj
            }
        }

        // Nothing came down and nothing local to reshuffle → leave prefs alone.
        if (byId.isEmpty() && mergedPinned.isEmpty()) return

        // 3. Recency order (newest first), then re-apply the pinned-aware cap.
        val ordered = byId.values.sortedByDescending { it.optLong("createdAt", 0L) }
        val result = mutableListOf<JSONObject>()
        var unpinnedCount = 0
        for (obj in ordered) {
            val id = obj.optString("radioPlaylistId", "")
            if (mergedPinned.contains(id)) {
                result.add(obj)
            } else if (unpinnedCount < MAX_UNPINNED) {
                result.add(obj)
                unpinnedCount++
            }
        }

        prefs.edit()
            .putString(KEY_RADIOS, JSONArray(result).toString())
            .putString(KEY_PINNED, JSONArray(mergedPinned.toList()).toString())
            .apply()
        radiosCache = null
    }

    // --- Pinned playlists (any type, not just radios) ---

    private const val PREFS_PINNED_PLAYLISTS = "sleppify_pinned_playlists"
    private const val KEY_PINNED_PLAYLISTS = "pinned_ids"

    private fun getPinnedPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_PINNED_PLAYLISTS, Context.MODE_PRIVATE)
    }

    // In-memory snapshot of the pinned-playlist id set. isPlaylistPinned() is called once per
    // RecyclerView row bind on the UI thread; parsing the JSON every time caused scroll jank.
    // The set is small and only mutated via pin/unpin, so cache it and refresh on every write.
    @Volatile
    private var pinnedPlaylistIdsCache: Set<String>? = null

    // LinkedHashSet preserves pin order, which buildDisplayLibrary relies on ("Pinned in pin order").
    private fun pinnedSet(context: Context): Set<String> {
        pinnedPlaylistIdsCache?.let { return it }
        val set = LinkedHashSet(loadPinnedPlaylistIds(getPinnedPrefs(context)))
        pinnedPlaylistIdsCache = set
        return set
    }

    fun isPlaylistPinned(context: Context, contentId: String): Boolean {
        return pinnedSet(context).contains(contentId)
    }

    fun pinPlaylist(context: Context, contentId: String) {
        val prefs = getPinnedPrefs(context)
        val pinned = LinkedHashSet(pinnedSet(context))
        if (pinned.add(contentId)) {
            prefs.edit().putString(KEY_PINNED_PLAYLISTS, JSONArray(pinned.toList()).toString()).apply()
            pinnedPlaylistIdsCache = pinned
        }
    }

    fun unpinPlaylist(context: Context, contentId: String) {
        val prefs = getPinnedPrefs(context)
        val pinned = LinkedHashSet(pinnedSet(context))
        if (pinned.remove(contentId)) {
            prefs.edit().putString(KEY_PINNED_PLAYLISTS, JSONArray(pinned.toList()).toString()).apply()
            pinnedPlaylistIdsCache = pinned
        }
    }

    fun getPinnedPlaylistIds(context: Context): List<String> {
        return pinnedSet(context).toList()
    }

    // --- Internal helpers ---

    private fun loadRadiosInternal(prefs: SharedPreferences): List<JSONObject> {
        return prefs.mapJsonArray(KEY_RADIOS) { it }
    }

    private fun loadPinnedIds(prefs: SharedPreferences): Set<String> {
        val arr = prefs.getJsonArray(KEY_PINNED)
        val set = mutableSetOf<String>()
        for (i in 0 until arr.length()) set.add(arr.getString(i))
        return set
    }

    private fun loadPinnedPlaylistIds(prefs: SharedPreferences): List<String> {
        val arr = prefs.getJsonArray(KEY_PINNED_PLAYLISTS)
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) list.add(arr.getString(i))
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
