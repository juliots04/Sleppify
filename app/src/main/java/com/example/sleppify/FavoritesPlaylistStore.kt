package com.example.sleppify

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object FavoritesPlaylistStore {
    const val PLAYLIST_ID = "sleppify_favorites"
    const val PLAYLIST_TITLE = "Favoritos"

    private val PREFS_STREAMING_CACHE = AppConstants.PREFS_STREAMING_CACHE
    private const val PREF_TRACKS_UPDATED_AT_PREFIX = "playlist_tracks_updated_at_"
    private const val PREF_TRACKS_DATA_PREFIX = "playlist_tracks_data_"
    private const val PREF_TRACKS_FULL_CACHE_PREFIX = "playlist_tracks_cache_full_"
    private const val PREF_PLAYLIST_OFFLINE_COMPLETE_PREFIX = "playlist_offline_complete_"
    private const val MAX_TRACKS = 5000

    private var favoritesCache: List<FavoriteTrack>? = null
    private var favoritesIdSet: Set<String>? = null
    private var likedMusicIdSet: Set<String>? = null
    private var likedTombstones: MutableSet<String>? = null
    private val CACHE_LOCK = Any()
    private const val LIKED_PLAYLIST_ID = "__liked_videos__"

    // Un-liking is local-only (there is no YouTube unlike API in the app), so the next server
    // refetch of "Música que te gustó" would silently resurrect the track. Tombstones make the
    // removal stick: a tombstoned id is treated as not-liked even if the server cache contains
    // it, until the user likes it again (which clears the tombstone).
    private const val PREF_LIKED_REMOVED_IDS = "liked_music_removed_ids"

    @JvmStatic
    fun buildSubtitle(count: Int): String {
        val safeCount = count.coerceAtLeast(0)
        return String.format(Locale.getDefault(), "Playlist generada por ti • %d canciones", safeCount)
    }

    @JvmStatic
    fun invalidateCache() {
        synchronized(CACHE_LOCK) {
            favoritesCache = null
            favoritesIdSet = null
            likedMusicIdSet = null
        }
    }

    @JvmStatic
    fun isInLikedMusic(context: Context, videoId: String): Boolean {
        val target = safe(videoId)
        if (target.isEmpty()) return false
        synchronized(CACHE_LOCK) {
            if (loadTombstonesLocked(context).contains(target)) return false
            val cached = likedMusicIdSet
            if (cached != null) return cached.contains(target)

            // Load + cache atomically inside the lock so a concurrent invalidateLikedMusicCache()
            // can't slot in between the disk read and the cache write and leave a stale set.
            val appContext = context.applicationContext
            val raw = appContext.getSharedPreferences(PREFS_STREAMING_CACHE, Activity.MODE_PRIVATE)
                .getString("playlist_tracks_data_$LIKED_PLAYLIST_ID", "").orEmpty()
            val idSet = HashSet<String>()
            if (raw.isNotBlank()) {
                try {
                    val array = org.json.JSONArray(raw)
                    for (i in 0 until array.length()) {
                        val vid = safe(array.optJSONObject(i)?.optString("videoId", ""))
                        if (vid.isNotEmpty()) idSet.add(vid)
                    }
                } catch (e: Exception) {
                    Log.w("FavPlaylistStore", "Failed to parse liked music IDs", e)
                }
            }
            likedMusicIdSet = idSet
            return idSet.contains(target)
        }
    }

    @JvmStatic
    fun invalidateLikedMusicCache() {
        synchronized(CACHE_LOCK) { likedMusicIdSet = null }
    }

    /**
     * Removes a track from the server-synced "Música que te gustó" cache
     * (playlist_tracks_data___liked_videos__). The liked playlist the user sees is the union of
     * this cache and the local yt_mirror, so un-liking must clear this half too — otherwise a
     * song liked on the YouTube account would stay "liked" forever locally.
     */
    /** Membership tombstones for the liked playlist; treat these ids as removed. */
    @JvmStatic
    fun getLikedTombstones(context: Context): Set<String> {
        synchronized(CACHE_LOCK) {
            return HashSet(loadTombstonesLocked(context))
        }
    }

    /** Called on every "like" so a re-liked song stops being filtered out. */
    @JvmStatic
    fun clearLikedTombstone(context: Context, videoId: String) {
        val target = safe(videoId)
        if (target.isEmpty()) return
        synchronized(CACHE_LOCK) {
            val set = loadTombstonesLocked(context)
            if (set.remove(target)) persistTombstonesLocked(context, set)
        }
    }

    private fun loadTombstonesLocked(context: Context): MutableSet<String> {
        likedTombstones?.let { return it }
        val raw = context.applicationContext
            .getSharedPreferences(PREFS_STREAMING_CACHE, Activity.MODE_PRIVATE)
            .getStringSet(PREF_LIKED_REMOVED_IDS, null)
        val set = HashSet(raw ?: emptySet())
        likedTombstones = set
        return set
    }

    private fun persistTombstonesLocked(context: Context, set: Set<String>) {
        context.applicationContext
            .getSharedPreferences(PREFS_STREAMING_CACHE, Activity.MODE_PRIVATE)
            .edit()
            .putStringSet(PREF_LIKED_REMOVED_IDS, HashSet(set))
            .apply()
    }

    @JvmStatic
    fun removeFromLikedMusic(context: Context, videoId: String): Boolean {
        val target = safe(videoId)
        if (target.isEmpty()) return false
        synchronized(CACHE_LOCK) {
            // Tombstone first: even if the local cache copy is empty/missing, the id must stay
            // removed after the next server refetch repopulates the cache.
            val tombstones = loadTombstonesLocked(context)
            if (tombstones.add(target)) persistTombstonesLocked(context, tombstones)

            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(PREFS_STREAMING_CACHE, Activity.MODE_PRIVATE)
            val key = "playlist_tracks_data_$LIKED_PLAYLIST_ID"
            val raw = prefs.getString(key, "").orEmpty()
            if (raw.isBlank()) {
                likedMusicIdSet = null
                return false
            }
            return try {
                val array = JSONArray(raw)
                val out = JSONArray()
                var removed = false
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    if (safe(obj.optString("videoId", "")) == target) {
                        removed = true
                        continue
                    }
                    out.put(obj)
                }
                if (removed) {
                    prefs.edit().putString(key, out.toString()).apply()
                }
                likedMusicIdSet = null
                removed
            } catch (e: Exception) {
                Log.w("FavPlaylistStore", "Failed to remove from liked music", e)
                likedMusicIdSet = null
                false
            }
        }
    }

    @JvmStatic
    fun getFavoritesCount(context: Context): Int {
        return loadFavorites(context).size
    }

    @JvmStatic
    fun isFavorite(context: Context, videoId: String): Boolean {
        val target = safe(videoId)
        if (target.isEmpty()) {
            return false
        }

        synchronized(CACHE_LOCK) {
            if (favoritesIdSet == null) {
                loadFavorites(context)
            }
            return favoritesIdSet?.contains(target) ?: false
        }
    }

    @JvmStatic
    fun upsertFavorite(
        context: Context,
        videoId: String,
        title: String,
        artist: String,
        duration: String,
        imageUrl: String
    ): Boolean {
        val safeVideoId = safe(videoId)
        if (safeVideoId.isEmpty()) {
            return false
        }

        synchronized(CACHE_LOCK) {
            val tracks = loadFavorites(context).toMutableList()
            val existed = tracks.removeAll { it.videoId == safeVideoId }

            tracks.add(
                0,
                FavoriteTrack(
                    safeVideoId,
                    fallback(title, "Tema"),
                    safe(artist),
                    sanitizeDuration(duration),
                    safe(imageUrl)
                )
            )

            if (tracks.size > MAX_TRACKS) {
                tracks.subList(MAX_TRACKS, tracks.size).clear()
            }

            updateCache(tracks)
            saveFavorites(context, tracks)
            return !existed
        }
    }

    @JvmStatic
    fun storeFavorites(context: Context, tracks: List<FavoriteTrack>) {
        synchronized(CACHE_LOCK) {
            val deduped = LinkedHashMap<String, FavoriteTrack>()
            for (track in tracks) {
                deduped[track.videoId] = track
            }
            val finalTracks = ArrayList(deduped.values)
            updateCache(finalTracks)
            saveFavorites(context, finalTracks)
        }
    }

    @JvmStatic
    fun removeFavorite(context: Context, videoId: String): Boolean {
        val safeVideoId = safe(videoId)
        if (safeVideoId.isEmpty()) {
            return false
        }

        synchronized(CACHE_LOCK) {
            val tracks = loadFavorites(context).toMutableList()
            val removed = tracks.removeAll { it.videoId == safeVideoId }
            if (!removed) {
                return false
            }

            updateCache(tracks)
            saveFavorites(context, tracks)
            OfflineAudioStore.deleteOfflineAudio(context, safeVideoId)
            return true
        }
    }

    @JvmStatic
    fun loadFavorites(context: Context): List<FavoriteTrack> {
        synchronized(CACHE_LOCK) {
            favoritesCache?.let { return it }

            // Load + cache atomically inside the lock: holding it across the disk read avoids a
            // concurrent invalidateCache() racing between the read and the cache write (which
            // could return data inconsistent with the cached state). Only the cold (uncached)
            // call does I/O here; warm calls return from cache above without touching disk.
            val appContext = context.applicationContext
            val prefs = getPrefs(appContext)
            val raw = prefs.getString(PREF_TRACKS_DATA_PREFIX + PLAYLIST_ID, "").orEmpty()
            if (raw.isBlank()) {
                val empty = emptyList<FavoriteTrack>()
                updateCache(empty)
                return empty
            }

            val parsed = try {
                val array = JSONArray(raw)
                val dedup = LinkedHashMap<String, FavoriteTrack>()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val videoId = safe(obj.optString("videoId", ""))
                    if (videoId.isEmpty()) {
                        continue
                    }

                    val track = FavoriteTrack(
                        videoId,
                        fallback(obj.optString("title", ""), "Tema"),
                        safe(obj.optString("artist", "")),
                        sanitizeDuration(obj.optString("duration", "")),
                        safe(obj.optString("imageUrl", ""))
                    )
                    dedup[videoId] = track
                }
                ArrayList(dedup.values)
            } catch (_: Exception) {
                emptyList<FavoriteTrack>()
            }

            updateCache(parsed)
            return parsed
        }
    }

    private fun updateCache(tracks: List<FavoriteTrack>) {
        favoritesCache = tracks
        favoritesIdSet = HashSet(tracks.map { it.videoId })
    }

    private fun saveFavorites(context: Context, tracks: List<FavoriteTrack>) {
        val appContext = context.applicationContext

        try {
            val array = JSONArray()
            for (track in tracks) {
                val obj = JSONObject()
                obj.put("videoId", track.videoId)
                obj.put("title", track.title)
                obj.put("artist", track.artist)
                obj.put("duration", track.duration)
                obj.put("imageUrl", track.imageUrl)
                array.put(obj)
            }

            getPrefs(appContext).edit()
                .putLong(PREF_TRACKS_UPDATED_AT_PREFIX + PLAYLIST_ID, System.currentTimeMillis())
                .putBoolean(PREF_TRACKS_FULL_CACHE_PREFIX + PLAYLIST_ID, true)
                .putBoolean(PREF_PLAYLIST_OFFLINE_COMPLETE_PREFIX + PLAYLIST_ID, false)
                .putString(PREF_TRACKS_DATA_PREFIX + PLAYLIST_ID, array.toString())
                .apply()
        } catch (e: Exception) {
            Log.w("FavPlaylistStore", "Failed to persist liked music tracks", e)
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_STREAMING_CACHE, Activity.MODE_PRIVATE)
    }

    private fun safe(value: String?): String {
        return value?.trim().orEmpty()
    }

    private fun fallback(value: String?, fallback: String): String {
        val safeValue = safe(value)
        return if (safeValue.isEmpty()) fallback else safeValue
    }

    private fun sanitizeDuration(value: String?): String {
        val safeValue = safe(value)
        return if (safeValue == "--:--") "" else safeValue
    }

    class FavoriteTrack(
        @JvmField val videoId: String,
        @JvmField val title: String,
        @JvmField val artist: String,
        @JvmField val duration: String,
        @JvmField val imageUrl: String
    )
}
