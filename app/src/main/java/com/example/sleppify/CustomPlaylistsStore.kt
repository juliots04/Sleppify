package com.example.sleppify

import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object CustomPlaylistsStore {
    private const val PREFS_NAME = "sleppify_custom_playlists"
    const val CUSTOM_PLAYLIST_PREFIX = "custom_playlist_"
    private const val KEY_PLAYLIST_NAMES = "all_playlist_names"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getAllPlaylistNames(context: Context): List<String> {
        val prefs = getPrefs(context)
        val namesJson = prefs.getString(KEY_PLAYLIST_NAMES, "[]")
        val names = mutableListOf<String>()
        try {
            val arr = JSONArray(namesJson)
            for (i in 0 until arr.length()) {
                val name = arr.getString(i)
                if (!TextUtils.isEmpty(name)) {
                    names.add(name)
                }
            }
        } catch (e: JSONException) {
        }
        return names.sorted()
    }

    fun createPlaylist(context: Context, name: String): Boolean {
        val trimmed = name.trim()
        if (TextUtils.isEmpty(trimmed)) return false
        val names = getAllPlaylistNames(context).toMutableList()
        val exists = names.any { it.equals(trimmed, ignoreCase = true) }
        if (exists) return false
        
        names.add(trimmed)
        getPrefs(context).edit().putString(KEY_PLAYLIST_NAMES, JSONArray(names).toString()).apply()
        
        // Sync empty playlist to cloud if signed in
        if (AuthManager.getInstance(context).isSignedIn()) {
            CloudSyncManager.getInstance(context).syncPlaylistToCloud(trimmed, emptyList())
        }
        return true
    }

    fun addTrackToPlaylist(context: Context, playlistName: String, videoId: String, title: String, subtitle: String, duration: String, thumbnailUrl: String) {
        val prefs = getPrefs(context)
        val key = CUSTOM_PLAYLIST_PREFIX + playlistName
        val existingJson = prefs.getString(key, "[]")
        val arr = try { JSONArray(existingJson) } catch (e: JSONException) { JSONArray() }
        
        // Remove duplicates if exist
        val newArr = JSONArray()
        var added = false
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.getString("videoId") == videoId) {
                // Update existing
                obj.put("title", title)
                obj.put("subtitle", subtitle)
                obj.put("duration", duration)
                obj.put("thumbnailUrl", thumbnailUrl)
                added = true
            }
            newArr.put(obj)
        }
        
        if (!added) {
            val trackObj = JSONObject()
            trackObj.put("videoId", videoId)
            trackObj.put("title", title)
            trackObj.put("subtitle", subtitle)
            trackObj.put("duration", duration)
            trackObj.put("thumbnailUrl", thumbnailUrl)
            newArr.put(trackObj)
        }
        
        prefs.edit().putString(key, newArr.toString()).apply()
        
        // Sync to cloud if signed in
        if (AuthManager.getInstance(context).isSignedIn()) {
            val updatedTracks = getTracksFromPlaylist(context, playlistName)
            CloudSyncManager.getInstance(context).syncPlaylistToCloud(playlistName, updatedTracks)
        }
    }

    fun removeTrackFromPlaylist(context: Context, playlistName: String, videoId: String) {
        val prefs = getPrefs(context)
        val key = CUSTOM_PLAYLIST_PREFIX + playlistName
        val existingJson = prefs.getString(key, "[]")
        val arr = try { JSONArray(existingJson) } catch (e: JSONException) { JSONArray() }
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.getString("videoId") != videoId) {
                newArr.put(obj)
            }
        }
        prefs.edit().putString(key, newArr.toString()).apply()

        if (AuthManager.getInstance(context).isSignedIn()) {
            val updatedTracks = getTracksFromPlaylist(context, playlistName)
            CloudSyncManager.getInstance(context).syncPlaylistToCloud(playlistName, updatedTracks)
        }
    }

    fun getTracksFromPlaylist(context: Context, playlistName: String): List<FavoritesPlaylistStore.FavoriteTrack> {
        val prefs = getPrefs(context)
        val key = CUSTOM_PLAYLIST_PREFIX + playlistName
        val existingJson = prefs.getString(key, "[]")
        val tracks = mutableListOf<FavoritesPlaylistStore.FavoriteTrack>()
        try {
            val arr = JSONArray(existingJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                tracks.add(FavoritesPlaylistStore.FavoriteTrack(
                    obj.getString("videoId"),
                    obj.getString("title"),
                    obj.getString("subtitle"),
                    obj.getString("duration"),
                    obj.getString("thumbnailUrl")
                ))
            }
        } catch (e: JSONException) {
        }
        return tracks
    }

    fun savePlaylist(context: Context, playlistName: String, tracks: List<FavoritesPlaylistStore.FavoriteTrack>) {
        val prefs = getPrefs(context)
        val key = CUSTOM_PLAYLIST_PREFIX + playlistName
        val newArr = JSONArray()
        for (track in tracks) {
            val trackObj = JSONObject()
            trackObj.put("videoId", track.videoId)
            trackObj.put("title", track.title)
            trackObj.put("subtitle", track.artist)
            trackObj.put("duration", track.duration)
            trackObj.put("thumbnailUrl", track.imageUrl)
            newArr.put(trackObj)
        }
        prefs.edit().putString(key, newArr.toString()).apply()
        
        if (AuthManager.getInstance(context).isSignedIn()) {
            CloudSyncManager.getInstance(context).syncPlaylistToCloud(playlistName, tracks)
        }
    }

    fun deletePlaylist(context: Context, name: String): Boolean {
        val trimmed = name.trim()
        if (TextUtils.isEmpty(trimmed)) return false
        
        val prefs = getPrefs(context)
        val names = getAllPlaylistNames(context).toMutableList()
        val index = names.indexOfFirst { it.equals(trimmed, ignoreCase = true) }
        if (index < 0) return false
        
        // Remove from names list
        names.removeAt(index)
        prefs.edit().putString(KEY_PLAYLIST_NAMES, JSONArray(names).toString()).apply()
        
        // Remove playlist tracks data
        val key = CUSTOM_PLAYLIST_PREFIX + trimmed
        prefs.edit().remove(key).apply()
        
        // Note: Cloud sync deletion can be added here when implemented
        return true
    }

    fun renamePlaylist(context: Context, oldName: String, newName: String): Boolean {
        val from = oldName.trim()
        val to = newName.trim()
        if (TextUtils.isEmpty(from) || TextUtils.isEmpty(to)) return false
        if (from.equals(to, ignoreCase = true)) return false

        val prefs = getPrefs(context)
        val names = getAllPlaylistNames(context).toMutableList()
        val index = names.indexOfFirst { it.equals(from, ignoreCase = true) }
        if (index < 0) return false

        val existsTarget = names.any { it.equals(to, ignoreCase = true) }
        if (existsTarget) return false

        val oldKey = CUSTOM_PLAYLIST_PREFIX + from
        val newKey = CUSTOM_PLAYLIST_PREFIX + to
        val existingJson = prefs.getString(oldKey, "[]") ?: "[]"

        prefs.edit()
            .putString(newKey, existingJson)
            .remove(oldKey)
            .apply()

        names[index] = to
        prefs.edit().putString(KEY_PLAYLIST_NAMES, JSONArray(names).toString()).apply()

        // Sync rename as a full re-sync under new name if signed in.
        if (AuthManager.getInstance(context).isSignedIn()) {
            val updatedTracks = getTracksFromPlaylist(context, to)
            CloudSyncManager.getInstance(context).syncPlaylistToCloud(to, updatedTracks)
        }

        return true
    }

    // --- Global last-used playlist for quick "Add to playlist" ---
    private const val PREFS_QUICK_ADD = "sleppify_quick_add"
    private const val KEY_LAST_PLAYLIST_KEY = "last_playlist_key"
    private const val KEY_LAST_PLAYLIST_NAME = "last_playlist_name"

    @JvmStatic
    fun getLastSavedPlaylistKey(context: Context): String? {
        return context.getSharedPreferences(PREFS_QUICK_ADD, Context.MODE_PRIVATE)
            .getString(KEY_LAST_PLAYLIST_KEY, null)
    }

    @JvmStatic
    fun getLastSavedPlaylistName(context: Context): String? {
        return context.getSharedPreferences(PREFS_QUICK_ADD, Context.MODE_PRIVATE)
            .getString(KEY_LAST_PLAYLIST_NAME, null)
    }

    @JvmStatic
    fun setLastSavedPlaylist(context: Context, key: String?, name: String?) {
        context.getSharedPreferences(PREFS_QUICK_ADD, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_PLAYLIST_KEY, key)
            .putString(KEY_LAST_PLAYLIST_NAME, name)
            .apply()
    }

    @JvmStatic
    fun clearLastSavedPlaylist(context: Context) {
        setLastSavedPlaylist(context, null, null)
    }
}
