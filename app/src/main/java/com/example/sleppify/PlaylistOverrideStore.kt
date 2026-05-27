package com.example.sleppify

import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tracks, per-playlist, which restricted videoIds have been replaced by an
 * alternative playable track. Replacements are applied at render/cache time,
 * so the restricted track never appears to the user again (even after pull-
 * to-refresh or reinstall, since we also persist the data to Firestore via
 * [CloudSyncManager]).
 */
object PlaylistOverrideStore {
    private const val TAG = "PlaylistOverrideStore"
    private const val PREFS_NAME = "playlist_overrides"
    private const val KEY_PLAYLIST_PREFIX = "overrides_"

    fun interface OverrideListener {
        fun onOverridesChanged(playlistId: String)
    }

    private val listeners = CopyOnWriteArrayList<OverrideListener>()

    data class Override(
        val originalVideoId: String,
        val replacementVideoId: String,
        val title: String,
        val artist: String,
        val duration: String,
        val imageUrl: String,
        val createdAt: Long
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("originalVideoId", originalVideoId)
            put("replacementVideoId", replacementVideoId)
            put("title", title)
            put("artist", artist)
            put("duration", duration)
            put("imageUrl", imageUrl)
            put("createdAt", createdAt)
        }

        fun toMap(): Map<String, Any?> = mapOf(
            "originalVideoId" to originalVideoId,
            "replacementVideoId" to replacementVideoId,
            "title" to title,
            "artist" to artist,
            "duration" to duration,
            "imageUrl" to imageUrl,
            "createdAt" to createdAt
        )

        companion object {
            @JvmStatic
            fun fromJson(obj: JSONObject?): Override? {
                if (obj == null) return null
                val originalVideoId = obj.optString("originalVideoId", "").trim()
                val replacementVideoId = obj.optString("replacementVideoId", "").trim()
                if (originalVideoId.isEmpty() || replacementVideoId.isEmpty()) return null
                return Override(
                    originalVideoId = originalVideoId,
                    replacementVideoId = replacementVideoId,
                    title = obj.optString("title", "").trim(),
                    artist = obj.optString("artist", "").trim(),
                    duration = obj.optString("duration", "").trim(),
                    imageUrl = obj.optString("imageUrl", "").trim(),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                )
            }

            @JvmStatic
            fun fromMap(map: Map<String, Any?>?): Override? {
                if (map == null) return null
                val originalVideoId = (map["originalVideoId"] as? String)?.trim().orEmpty()
                val replacementVideoId = (map["replacementVideoId"] as? String)?.trim().orEmpty()
                if (originalVideoId.isEmpty() || replacementVideoId.isEmpty()) return null
                val rawCreated = map["createdAt"]
                val createdAt = when (rawCreated) {
                    is Number -> rawCreated.toLong()
                    is String -> rawCreated.toLongOrNull() ?: System.currentTimeMillis()
                    else -> System.currentTimeMillis()
                }
                return Override(
                    originalVideoId = originalVideoId,
                    replacementVideoId = replacementVideoId,
                    title = (map["title"] as? String)?.trim().orEmpty(),
                    artist = (map["artist"] as? String)?.trim().orEmpty(),
                    duration = (map["duration"] as? String)?.trim().orEmpty(),
                    imageUrl = (map["imageUrl"] as? String)?.trim().orEmpty(),
                    createdAt = createdAt
                )
            }
        }
    }

    @JvmStatic
    fun addOverrideListener(listener: OverrideListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    @JvmStatic
    fun removeOverrideListener(listener: OverrideListener) {
        listeners.remove(listener)
    }

    private fun notifyChanged(playlistId: String) {
        for (l in listeners) {
            try { l.onOverridesChanged(playlistId) } catch (_: Exception) {}
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun keyFor(playlistId: String): String {
        return KEY_PLAYLIST_PREFIX + playlistId.trim()
    }

    /** Returns overrides indexed by originalVideoId. */
    @JvmStatic
    fun getOverrides(context: Context, playlistId: String): Map<String, Override> {
        val trimmed = playlistId.trim()
        if (trimmed.isEmpty()) return emptyMap()
        val raw = getPrefs(context).getString(keyFor(trimmed), "").orEmpty()
        if (raw.isBlank()) return emptyMap()
        return try {
            parseOverridesJson(raw)
        } catch (e: Exception) {
            Log.w(TAG, "getOverrides:parse_fail playlist=$trimmed err=${e.message}")
            emptyMap()
        }
    }

    private fun parseOverridesJson(raw: String): Map<String, Override> {
        val array = JSONArray(raw)
        val result = LinkedHashMap<String, Override>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val override = Override.fromJson(obj) ?: continue
            result[override.originalVideoId] = override
        }
        return result
    }

    private fun writeOverrides(context: Context, playlistId: String, overrides: Map<String, Override>) {
        val array = JSONArray()
        for (o in overrides.values) array.put(o.toJson())
        getPrefs(context).edit().putString(keyFor(playlistId), array.toString()).apply()
    }

    @JvmStatic
    fun putOverride(context: Context, playlistId: String, override: Override) {
        val trimmed = playlistId.trim()
        if (trimmed.isEmpty() || override.originalVideoId.isEmpty() ||
            override.replacementVideoId.isEmpty()
        ) return

        val current = LinkedHashMap(getOverrides(context, trimmed))
        current[override.originalVideoId] = override
        writeOverrides(context, trimmed, current)
        notifyChanged(trimmed)

        // Fire-and-forget cloud sync.
        try {
            CloudSyncManager.getInstance(context).syncPlaylistOverridesToCloud(trimmed, current.values.toList())
        } catch (e: Exception) {
            Log.w(TAG, "putOverride:cloud_sync_fail playlist=$trimmed err=${e.message}")
        }
    }

    @JvmStatic
    fun removeOverride(context: Context, playlistId: String, originalVideoId: String) {
        val trimmedPlaylistId = playlistId.trim()
        val trimmedOriginal = originalVideoId.trim()
        if (trimmedPlaylistId.isEmpty() || trimmedOriginal.isEmpty()) return

        val current = LinkedHashMap(getOverrides(context, trimmedPlaylistId))
        if (current.remove(trimmedOriginal) == null) return
        writeOverrides(context, trimmedPlaylistId, current)
        notifyChanged(trimmedPlaylistId)

        try {
            CloudSyncManager.getInstance(context).syncPlaylistOverridesToCloud(trimmedPlaylistId, current.values.toList())
        } catch (e: Exception) {
            Log.w(TAG, "removeOverride:cloud_sync_fail playlist=$trimmedPlaylistId err=${e.message}")
        }
    }

    /**
     * Merge cloud-provided overrides into local storage. Used during sign-in / pull.
     * Newer override (by createdAt) wins on conflict.
     */
    @JvmStatic
    fun mergeFromCloud(context: Context, playlistId: String, cloudOverrides: List<Override>) {
        val trimmed = playlistId.trim()
        if (trimmed.isEmpty()) return
        val merged = LinkedHashMap(getOverrides(context, trimmed))
        for (o in cloudOverrides) {
            if (o.originalVideoId.isEmpty() || o.replacementVideoId.isEmpty()) continue
            val existing = merged[o.originalVideoId]
            if (existing == null || o.createdAt >= existing.createdAt) {
                merged[o.originalVideoId] = o
            }
        }
        writeOverrides(context, trimmed, merged)
        notifyChanged(trimmed)
    }

    /**
     * Returns the list of restricted/original videoIds that have been replaced in any playlist.
     * Useful to filter them out globally.
     */
    @JvmStatic
    fun getAllOverriddenOriginalIds(context: Context): Set<String> {
        val result = HashSet<String>()
        val all = getPrefs(context).all
        for ((key, value) in all) {
            if (!key.startsWith(KEY_PLAYLIST_PREFIX)) continue
            val raw = (value as? String) ?: continue
            try {
                val map = parseOverridesJson(raw)
                result.addAll(map.keys)
            } catch (_: Exception) {}
        }
        return result
    }

    /**
     * Returns the replacement videoId for an original, if any, across ANY playlist.
     * Useful for player-side substitution when the source playlist id is unknown.
     */
    @JvmStatic
    fun findReplacementInAnyPlaylist(context: Context, originalVideoId: String): Override? {
        val id = originalVideoId.trim()
        if (id.isEmpty()) return null
        val all = getPrefs(context).all
        for ((key, value) in all) {
            if (!key.startsWith(KEY_PLAYLIST_PREFIX)) continue
            val raw = (value as? String) ?: continue
            try {
                val map = parseOverridesJson(raw)
                val o = map[id]
                if (o != null) return o
            } catch (_: Exception) {}
        }
        return null
    }

    /** Playlist IDs that have at least one override stored locally. */
    @JvmStatic
    fun getPlaylistIdsWithOverrides(context: Context): Set<String> {
        val result = HashSet<String>()
        val all = getPrefs(context).all
        for (key in all.keys) {
            if (key.startsWith(KEY_PLAYLIST_PREFIX)) {
                result.add(key.removePrefix(KEY_PLAYLIST_PREFIX))
            }
        }
        return result
    }

    /**
     * Applies overrides (from a given map) in-place to a list of tracks.
     * The restricted original track is substituted by the replacement track at the same
     * index; originals are never shown.
     *
     * @param tracks any list of items with a `videoId` field mapped via [getVideoId].
     * @param overrides map keyed by originalVideoId.
     * @param factory factory to build a replacement item of type T from the override.
     */
    @JvmStatic
    fun <T> applyOverridesTo(
        tracks: List<T>,
        overrides: Map<String, Override>,
        getVideoId: (T) -> String,
        factory: (Override) -> T
    ): List<T> {
        if (tracks.isEmpty() || overrides.isEmpty()) return tracks
        val result = ArrayList<T>(tracks.size)
        val seenReplacements = HashSet<String>()
        // First, collect replacement videoIds to ensure we don't double-add
        for (t in tracks) {
            val videoId = getVideoId(t).trim()
            if (videoId.isEmpty()) {
                result.add(t)
                continue
            }
            val override = overrides[videoId]
            if (override != null) {
                // Avoid adding the replacement if it already exists somewhere in the list
                if (seenReplacements.add(override.replacementVideoId)) {
                    result.add(factory(override))
                }
                // drop the restricted original
                continue
            }
            // Also skip if this is the replacement that appears naturally twice
            if (!seenReplacements.add(videoId)) {
                continue
            }
            result.add(t)
        }
        return result
    }
}
