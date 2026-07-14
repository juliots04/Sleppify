package com.example.sleppify

import android.content.Context

/**
 * Tiny durable index of the playlists the user has DOWNLOADED from: playlistId -> (displayName,
 * coverUrl). Written at download-enqueue time (the one moment a playlist "gains" downloads and its
 * real name + cover are known on screen), so the "Descargas" library view can label every kind of
 * source correctly — albums (MPRE…), radios (RD…), YouTube playlists, custom lists — even when the
 * playlist itself isn't otherwise in the library list.
 *
 * This store records METADATA only; whether a playlist still has ≥1 downloaded song is decided at
 * read time by intersecting its track ids with the files actually on disk
 * ([OfflineAudioStore.listDownloadedTrackIds]), so deleting downloads self-corrects the view.
 */
object PlaylistMetaStore {
    private const val PREFS_NAME = "playlist_download_meta"
    private const val KEY_NAME_PREFIX = "name_"
    private const val KEY_THUMB_PREFIX = "thumb_"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Meta(val name: String, val thumbnailUrl: String)

    /** Records (or refreshes) a playlist's display name + cover. No-op on a blank id/name. */
    @JvmStatic
    fun save(context: Context, playlistId: String?, name: String?, thumbnailUrl: String?) {
        val id = playlistId?.trim().orEmpty()
        val displayName = name?.trim().orEmpty()
        if (id.isEmpty() || displayName.isEmpty()) return
        prefs(context).edit()
            .putString(KEY_NAME_PREFIX + id, displayName)
            .putString(KEY_THUMB_PREFIX + id, thumbnailUrl?.trim().orEmpty())
            .apply()
    }

    @JvmStatic
    fun getName(context: Context, playlistId: String): String? =
        prefs(context).getString(KEY_NAME_PREFIX + playlistId, null)

    @JvmStatic
    fun getThumbnail(context: Context, playlistId: String): String? =
        prefs(context).getString(KEY_THUMB_PREFIX + playlistId, null)

    /** All recorded entries, keyed by playlistId. Safe to call off the main thread. */
    @JvmStatic
    fun getAll(context: Context): Map<String, Meta> {
        val out = LinkedHashMap<String, Meta>()
        val all = try { prefs(context).all } catch (_: Exception) { return out }
        for ((key, value) in all) {
            if (!key.startsWith(KEY_NAME_PREFIX)) continue
            val id = key.substring(KEY_NAME_PREFIX.length)
            if (id.isEmpty()) continue
            val name = (value as? String)?.trim().orEmpty()
            if (name.isEmpty()) continue
            val thumb = (all[KEY_THUMB_PREFIX + id] as? String)?.trim().orEmpty()
            out[id] = Meta(name, thumb)
        }
        return out
    }

    @JvmStatic
    fun remove(context: Context, playlistId: String) {
        prefs(context).edit()
            .remove(KEY_NAME_PREFIX + playlistId)
            .remove(KEY_THUMB_PREFIX + playlistId)
            .apply()
    }
}
