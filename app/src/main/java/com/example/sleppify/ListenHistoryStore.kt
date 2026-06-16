package com.example.sleppify

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Persists a chronological log of every song played.
 * Entries are stored newest-first, capped at [MAX_ENTRIES].
 * Supports lazy-loading via offset/limit for the UI.
 */
object ListenHistoryStore {

    private const val PREFS_NAME = "listen_history_store"
    private const val KEY_ENTRIES = "entries_json"
    private const val MAX_ENTRIES = 2000
    private val IO = Executors.newSingleThreadExecutor()

    data class HistoryEntry(
        @JvmField val videoId: String,
        @JvmField val title: String,
        @JvmField val artist: String,
        @JvmField val imageUrl: String,
        @JvmField val timestampMs: Long
    )

    @JvmStatic
    fun record(context: Context, videoId: String, title: String, artist: String, imageUrl: String) {
        if (videoId.isEmpty()) return
        val appCtx = context.applicationContext
        IO.execute {
            val entries = loadMutable(appCtx)
            // Dedup: remove any existing entry with same videoId, then insert at top
            entries.removeAll { it.videoId == videoId }
            entries.add(0, HistoryEntry(videoId, title, artist, imageUrl, System.currentTimeMillis()))
            if (entries.size > MAX_ENTRIES) {
                entries.subList(MAX_ENTRIES, entries.size).clear()
            }
            save(appCtx, entries)
            // Debounced cloud sync
            CloudSyncManager.getInstance(appCtx).syncListenHistoryToCloud(appCtx)
        }
    }

    @JvmStatic
    fun getPage(context: Context, offset: Int, limit: Int): List<HistoryEntry> {
        val entries = loadMutable(context.applicationContext)
        if (offset >= entries.size) return emptyList()
        return entries.subList(offset, (offset + limit).coerceAtMost(entries.size)).toList()
    }

    @JvmStatic
    fun getAll(context: Context): List<HistoryEntry> = loadMutable(context.applicationContext).toList()

    @JvmStatic
    fun getTotalCount(context: Context): Int = loadMutable(context.applicationContext).size

    @JvmStatic
    fun exportToJson(context: Context): JSONArray {
        val entries = loadMutable(context.applicationContext)
        val arr = JSONArray()
        for (e in entries) arr.put(entryToJson(e))
        return arr
    }

    @JvmStatic
    fun importFromJson(context: Context, arr: JSONArray) {
        val appCtx = context.applicationContext
        IO.execute {
            val local = loadMutable(appCtx)
            val existingTimestamps = local.map { "${it.videoId}_${it.timestampMs}" }.toHashSet()
            var added = false
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val entry = jsonToEntry(obj) ?: continue
                val key = "${entry.videoId}_${entry.timestampMs}"
                if (key !in existingTimestamps) {
                    local.add(entry)
                    existingTimestamps.add(key)
                    added = true
                }
            }
            if (added) {
                local.sortByDescending { it.timestampMs }
                if (local.size > MAX_ENTRIES) local.subList(MAX_ENTRIES, local.size).clear()
                save(appCtx, local)
            }
        }
    }

    @JvmStatic
    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_ENTRIES).apply()
    }

    // --- internals ---

    private fun loadMutable(appCtx: Context): MutableList<HistoryEntry> {
        val raw = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, "") ?: ""
        if (raw.isEmpty()) return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<HistoryEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                jsonToEntry(obj)?.let { list.add(it) }
            }
            list
        } catch (_: Exception) { mutableListOf() }
    }

    private fun save(appCtx: Context, entries: List<HistoryEntry>) {
        val arr = JSONArray()
        for (e in entries) arr.put(entryToJson(e))
        appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }

    private fun entryToJson(e: HistoryEntry) = JSONObject().apply {
        put("videoId", e.videoId)
        put("title", e.title)
        put("artist", e.artist)
        put("imageUrl", e.imageUrl)
        put("timestampMs", e.timestampMs)
    }

    private fun jsonToEntry(obj: JSONObject): HistoryEntry? {
        val videoId = obj.optString("videoId", "").trim()
        if (videoId.isEmpty()) return null
        return HistoryEntry(
            videoId = videoId,
            title = obj.optString("title", ""),
            artist = obj.optString("artist", ""),
            imageUrl = obj.optString("imageUrl", ""),
            timestampMs = obj.optLong("timestampMs", 0L)
        )
    }
}
