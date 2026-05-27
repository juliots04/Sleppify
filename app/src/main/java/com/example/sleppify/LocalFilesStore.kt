package com.example.sleppify

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object LocalFilesStore {

    const val PLAYLIST_ID = "LOCAL_FILES_PLAYLIST"
    const val LOCAL_VIDEO_ID_PREFIX = "local_"
    private const val PREFS_NAME = "sleppify_local_files"
    private const val KEY_CACHED_TRACKS = "cached_tracks"
    private const val KEY_ENABLED = "local_files_enabled"
    private const val CONFIG_PREFS = "sleppify_local_config"
    private const val MIN_DURATION_MS = 10_000L

    data class LocalTrack(
        val videoId: String,
        val title: String,
        val artist: String,
        val duration: String,
        val albumArtUri: String,
        val contentUri: String
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @JvmStatic
    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    @JvmStatic
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    @JvmStatic
    fun scanLocalFiles(context: Context): List<LocalTrack> {
        val tracks = mutableListOf<LocalTrack>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val artworkUri = android.net.Uri.parse("content://media/external/audio/albumart")

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.IS_MUSIC,
            MediaStore.Audio.Media.ALBUM_ID
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= ?"
        val selectionArgs = arrayOf(MIN_DURATION_MS.toString())
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        try {
            context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: "Desconocido"
                    var artist = cursor.getString(artistCol) ?: ""
                    if (artist == "<unknown>") artist = ""
                    val durationMs = cursor.getLong(durationCol)

                    val contentUri = ContentUris.withAppendedId(collection, id).toString()
                    val videoId = LOCAL_VIDEO_ID_PREFIX + id
                    val durationLabel = formatDuration(durationMs)
                    val albumArtUri = if (albumIdCol >= 0) {
                        val albumId = cursor.getLong(albumIdCol)
                        ContentUris.withAppendedId(artworkUri, albumId).toString()
                    } else ""

                    tracks.add(LocalTrack(videoId, title, artist, durationLabel, albumArtUri, contentUri))
                }
            }
        } catch (_: Exception) {}

        return tracks
    }

    @JvmStatic
    fun getCachedFiles(context: Context): List<LocalTrack> {
        val json = getPrefs(context).getString(KEY_CACHED_TRACKS, "[]") ?: "[]"
        return parseTracks(json)
    }

    @JvmStatic
    fun cacheFiles(context: Context, tracks: List<LocalTrack>) {
        val arr = JSONArray()
        for (t in tracks) {
            arr.put(JSONObject().apply {
                put("videoId", t.videoId)
                put("title", t.title)
                put("artist", t.artist)
                put("duration", t.duration)
                put("albumArtUri", t.albumArtUri)
                put("contentUri", t.contentUri)
            })
        }
        getPrefs(context).edit().putString(KEY_CACHED_TRACKS, arr.toString()).apply()
    }

    @JvmStatic
    fun getContentUriForVideoId(context: Context, videoId: String): String? {
        if (!videoId.startsWith(LOCAL_VIDEO_ID_PREFIX)) return null
        val cached = getCachedFiles(context)
        return cached.firstOrNull { it.videoId == videoId }?.contentUri
    }

    @JvmStatic
    fun isLocalVideoId(videoId: String?): Boolean {
        return videoId != null && videoId.startsWith(LOCAL_VIDEO_ID_PREFIX)
    }

    private fun parseTracks(json: String): List<LocalTrack> {
        val list = mutableListOf<LocalTrack>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(LocalTrack(
                    obj.optString("videoId", ""),
                    obj.optString("title", ""),
                    obj.optString("artist", ""),
                    obj.optString("duration", ""),
                    obj.optString("albumArtUri", ""),
                    obj.optString("contentUri", "")
                ))
            }
        } catch (_: JSONException) {}
        return list
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }
}
