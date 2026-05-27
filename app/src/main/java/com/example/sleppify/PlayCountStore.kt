package com.example.sleppify

import android.content.Context
import android.text.TextUtils
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

object PlayCountStore {

    private const val PREFS_NAME = "play_count_store"
    private const val KEY_ENTRIES_JSON = "entries_json"
    private val IO = Executors.newSingleThreadExecutor()

    data class PlayCountEntry(
        @JvmField val videoId: String,
        @JvmField val title: String,
        @JvmField val artist: String,
        @JvmField val imageUrl: String,
        @JvmField val playlistId: String,
        @JvmField val playlistName: String,
        @JvmField val count: Int,
        @JvmField val lastPlayedAtMs: Long
    )

    @JvmStatic
    fun incrementPlayCount(
        context: Context,
        videoId: String,
        title: String,
        artist: String,
        imageUrl: String,
        playlistId: String?,
        playlistName: String?
    ) {
        if (videoId.isEmpty()) return
        val appCtx = context.applicationContext
        IO.execute {
            val entries = loadEntriesMutable(appCtx)
            val existing = entries.indexOfFirst { it.videoId == videoId }
            if (existing >= 0) {
                val old = entries[existing]
                entries[existing] = old.copy(
                    title = if (title.isNotEmpty()) title else old.title,
                    artist = if (artist.isNotEmpty()) artist else old.artist,
                    imageUrl = if (imageUrl.isNotEmpty()) imageUrl else old.imageUrl,
                    playlistId = playlistId ?: old.playlistId,
                    playlistName = playlistName ?: old.playlistName,
                    count = old.count + 1,
                    lastPlayedAtMs = System.currentTimeMillis()
                )
            } else {
                entries.add(
                    PlayCountEntry(
                        videoId = videoId,
                        title = title,
                        artist = artist,
                        imageUrl = imageUrl,
                        playlistId = playlistId ?: "",
                        playlistName = playlistName ?: "",
                        count = 1,
                        lastPlayedAtMs = System.currentTimeMillis()
                    )
                )
            }
            saveEntries(appCtx, entries)
        }
    }

    @JvmStatic
    fun getTopEntries(context: Context, limit: Int): List<PlayCountEntry> {
        val entries = loadEntriesMutable(context.applicationContext)
        entries.sortWith(compareByDescending<PlayCountEntry> { it.count }
            .thenByDescending { it.lastPlayedAtMs })
        return entries.take(limit)
    }

    @JvmStatic
    fun getAllEntries(context: Context): List<PlayCountEntry> {
        return loadEntriesMutable(context.applicationContext).toList()
    }

    /**
     * Aggregates play counts by playlistId and returns top playlists.
     * Each returned entry uses the playlist's first track image, the playlist name as title,
     * total play count across all tracks, and the most recent lastPlayedAtMs.
     */
    @JvmStatic
    fun getTopPlaylists(context: Context, limit: Int): List<PlayCountEntry> {
        val entries = loadEntriesMutable(context.applicationContext)
        val byPlaylist = LinkedHashMap<String, MutableList<PlayCountEntry>>()
        for (e in entries) {
            val pid = e.playlistId
            if (pid.isEmpty()) continue
            byPlaylist.getOrPut(pid) { mutableListOf() }.add(e)
        }
        val result = mutableListOf<PlayCountEntry>()
        for ((pid, tracks) in byPlaylist) {
            val totalCount = tracks.sumOf { it.count }
            val mostRecent = tracks.maxOf { it.lastPlayedAtMs }
            val bestTrack = tracks.maxByOrNull { it.count } ?: continue
            val name = if (bestTrack.playlistName.isNotEmpty()) bestTrack.playlistName else "Playlist"
            result.add(PlayCountEntry(
                videoId = pid,
                title = name,
                artist = "${tracks.size} canciones",
                imageUrl = bestTrack.imageUrl,
                playlistId = pid,
                playlistName = name,
                count = totalCount,
                lastPlayedAtMs = mostRecent
            ))
        }
        result.sortWith(compareByDescending<PlayCountEntry> { it.count }
            .thenByDescending { it.lastPlayedAtMs })
        return result.take(limit)
    }

    @JvmStatic
    fun getPlaylistTrackImages(context: Context, playlistId: String, limit: Int): List<String> {
        if (playlistId.isEmpty()) return emptyList()
        val entries = loadEntriesMutable(context.applicationContext)
        val seen = mutableSetOf<String>()
        val urls = mutableListOf<String>()
        entries.filter { it.playlistId == playlistId && it.imageUrl.isNotEmpty() }
            .sortedByDescending { it.count }
            .forEach { e ->
                if (seen.add(e.imageUrl)) {
                    urls.add(e.imageUrl)
                }
                if (urls.size >= limit) return@forEach
            }
        return urls
    }

    @JvmStatic
    fun exportToJson(context: Context): JSONArray {
        val entries = loadEntriesMutable(context.applicationContext)
        val arr = JSONArray()
        for (e in entries) {
            arr.put(entryToJson(e))
        }
        return arr
    }

    @JvmStatic
    fun importFromJson(context: Context, arr: JSONArray) {
        val appCtx = context.applicationContext
        IO.execute {
            val local = loadEntriesMutable(appCtx)
            val localMap = HashMap<String, PlayCountEntry>()
            for (e in local) localMap[e.videoId] = e

            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val remote = jsonToEntry(obj) ?: continue
                val existing = localMap[remote.videoId]
                if (existing == null || remote.count > existing.count) {
                    localMap[remote.videoId] = remote
                }
            }
            saveEntries(appCtx, ArrayList(localMap.values))
        }
    }

    // --- internals ---

    private fun loadEntriesMutable(appCtx: Context): MutableList<PlayCountEntry> {
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ENTRIES_JSON, "") ?: ""
        if (raw.isEmpty()) return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<PlayCountEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                jsonToEntry(obj)?.let { list.add(it) }
            }
            list
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveEntries(appCtx: Context, entries: List<PlayCountEntry>) {
        val arr = JSONArray()
        for (e in entries) arr.put(entryToJson(e))
        appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES_JSON, arr.toString())
            .commit()
    }

    private fun entryToJson(e: PlayCountEntry): JSONObject {
        return JSONObject().apply {
            put("videoId", e.videoId)
            put("title", e.title)
            put("artist", e.artist)
            put("imageUrl", e.imageUrl)
            put("playlistId", e.playlistId)
            put("playlistName", e.playlistName)
            put("count", e.count)
            put("lastPlayedAtMs", e.lastPlayedAtMs)
        }
    }

    private fun jsonToEntry(obj: JSONObject): PlayCountEntry? {
        val videoId = obj.optString("videoId", "").trim()
        if (videoId.isEmpty()) return null
        return PlayCountEntry(
            videoId = videoId,
            title = obj.optString("title", ""),
            artist = obj.optString("artist", ""),
            imageUrl = obj.optString("imageUrl", ""),
            playlistId = obj.optString("playlistId", ""),
            playlistName = obj.optString("playlistName", ""),
            count = obj.optInt("count", 1),
            lastPlayedAtMs = obj.optLong("lastPlayedAtMs", 0L)
        )
    }
}
