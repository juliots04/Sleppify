package com.example.sleppify

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object LocalFilesStore {

    const val PLAYLIST_ID = "LOCAL_FILES_PLAYLIST"
    const val LOCAL_VIDEO_ID_PREFIX = "local_"
    // Synthetic playlist id for a single on-device album (encodes the album name).
    const val LOCAL_ALBUM_ID_PREFIX = "LOCAL_ALBUM::"
    private const val PREFS_NAME = "sleppify_local_files"
    private const val KEY_CACHED_TRACKS = "cached_tracks"
    private const val KEY_ENABLED = "local_files_enabled"
    private const val CONFIG_PREFS = "sleppify_local_config"
    private const val MIN_DURATION_MS = 10_000L

    // Bump when the cached track schema changes so existing caches are regenerated once.
    // v2: dropped the per-ALBUM_ID album-art URI (it served wrong/missing covers); album art
    // is now resolved per-file from each track's embedded picture by LocalArtworkResolver.
    // v3: added the ALBUM name per track (for grouping into local albums).
    private const val KEY_SCHEMA_VERSION = "cache_schema_version"
    private const val CACHE_SCHEMA_VERSION = 3

    // In-memory copy of the parsed track cache. getCachedFiles is called from the library render
    // pipeline ON THE MAIN THREAD — for a device with thousands of songs the SharedPreferences
    // JSON parse is megabytes of work per call, which visibly froze the UI. Only cacheFiles()
    // writes the backing JSON, so it is the single invalidation point.
    @Volatile private var cachedTracksMemory: List<LocalTrack>? = null

    data class LocalTrack(
        val videoId: String,
        val title: String,
        val artist: String,
        val duration: String,
        val albumArtUri: String,
        val contentUri: String,
        val album: String = ""
    )

    /** A grouped on-device album (name + owning artist + song count + first track for a cover). */
    data class LocalAlbum(
        val name: String,
        val artist: String,
        val trackCount: Int,
        val coverVideoId: String
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

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.IS_MUSIC
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= ?"
        val selectionArgs = arrayOf(MIN_DURATION_MS.toString())
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        try {
            context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: "Desconocido"
                    var artist = cursor.getString(artistCol) ?: ""
                    if (artist == "<unknown>") artist = ""
                    var album = cursor.getString(albumCol) ?: ""
                    if (album == "<unknown>") album = ""
                    val durationMs = cursor.getLong(durationCol)

                    val contentUri = ContentUris.withAppendedId(collection, id).toString()
                    val videoId = LOCAL_VIDEO_ID_PREFIX + id
                    val durationLabel = formatDuration(durationMs)
                    // Album art is resolved per-file from the embedded picture at display time
                    // (LocalArtworkResolver), not from the unreliable ALBUM_ID albumart provider.
                    tracks.add(LocalTrack(videoId, title, artist, durationLabel, "", contentUri, album))
                }
            }
        } catch (e: Exception) {
            Log.w("LocalFilesStore", "Failed to scan local audio files", e)
        }

        return tracks
    }

    @JvmStatic
    fun getCachedFiles(context: Context): List<LocalTrack> {
        cachedTracksMemory?.let { return it }
        val parsed = getPrefs(context).mapJsonArray(KEY_CACHED_TRACKS) { obj ->
            LocalTrack(
                obj.optString("videoId", ""),
                obj.optString("title", ""),
                obj.optString("artist", ""),
                obj.optString("duration", ""),
                obj.optString("albumArtUri", ""),
                obj.optString("contentUri", ""),
                obj.optString("album", "")
            )
        }
        cachedTracksMemory = parsed
        return parsed
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
                put("album", t.album)
            })
        }
        getPrefs(context).edit()
            .putString(KEY_CACHED_TRACKS, arr.toString())
            .putInt(KEY_SCHEMA_VERSION, CACHE_SCHEMA_VERSION)
            .apply()
        cachedTracksMemory = ArrayList(tracks)
    }

    /** Group the cached local tracks into albums (one pass; empty album names are skipped). */
    @JvmStatic
    fun getAlbums(context: Context): List<LocalAlbum> = albumsFrom(getCachedFiles(context))

    /** Group an already-loaded track list into albums — avoids re-parsing the cache. */
    @JvmStatic
    fun albumsFrom(tracks: List<LocalTrack>): List<LocalAlbum> {
        val grouped = LinkedHashMap<String, MutableList<LocalTrack>>()
        for (t in tracks) {
            val album = t.album.trim()
            if (album.isEmpty()) continue
            grouped.getOrPut(album) { mutableListOf() }.add(t)
        }
        return grouped.map { (name, list) ->
            val artist = list.firstOrNull { it.artist.isNotBlank() }?.artist ?: ""
            LocalAlbum(name, artist, list.size, list.firstOrNull()?.videoId ?: "")
        }.sortedBy { it.name.lowercase() }
    }

    @JvmStatic
    fun getTracksForAlbum(context: Context, albumName: String): List<LocalTrack> {
        val target = albumName.trim()
        return getCachedFiles(context).filter { it.album.trim().equals(target, ignoreCase = true) }
    }

    @JvmStatic
    fun isLocalAlbumId(id: String?): Boolean = id != null && id.startsWith(LOCAL_ALBUM_ID_PREFIX)

    @JvmStatic
    fun albumIdFor(albumName: String): String = LOCAL_ALBUM_ID_PREFIX + albumName

    @JvmStatic
    fun albumNameFromId(id: String): String =
        if (id.startsWith(LOCAL_ALBUM_ID_PREFIX)) id.substring(LOCAL_ALBUM_ID_PREFIX.length) else id

    /**
     * True when the persisted cache predates the current schema and should be regenerated.
     * Used to force a single rescan after the album-art URI was dropped (v2).
     */
    @JvmStatic
    fun isCacheStale(context: Context): Boolean {
        return getPrefs(context).getInt(KEY_SCHEMA_VERSION, 1) != CACHE_SCHEMA_VERSION
    }

    // The MediaStore id is embedded in the videoId (LOCAL_VIDEO_ID_PREFIX + rowId) and the content
    // URI is deterministically ContentUris.withAppendedId(collection, id) — the exact value the scan
    // persisted (see scan at ~88-89). Reconstruct it directly instead of parsing the whole cached-
    // tracks JSON + allocating a LocalTrack per entry on every local track transition. Falls back to
    // the cache only if the id can't be parsed.
    @JvmStatic
    fun getContentUriForVideoId(context: Context, videoId: String): String? {
        if (!videoId.startsWith(LOCAL_VIDEO_ID_PREFIX)) return null
        val id = videoId.substring(LOCAL_VIDEO_ID_PREFIX.length).toLongOrNull()
        if (id != null) {
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
            return ContentUris.withAppendedId(collection, id).toString()
        }
        return getCachedFiles(context).firstOrNull { it.videoId == videoId }?.contentUri
    }

    @JvmStatic
    fun isLocalVideoId(videoId: String?): Boolean {
        return videoId != null && videoId.startsWith(LOCAL_VIDEO_ID_PREFIX)
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }
}
