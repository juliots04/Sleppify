package com.example.sleppify

object AppConstants {
    // const (not @JvmField val) so Kotlin `const val` aliases can derive from these and Java still
    // sees them as `public static final` constants. Single source of truth for these prefs names.
    const val PREFS_PLAYER_STATE = "player_state"
    const val PREFS_STREAMING_CACHE = "streaming_cache"
    const val PREFS_SETTINGS = "sleppify_settings"

    const val MEDIA_NOTIFICATION_ID = 11031

    // No proxy host anymore: streaming (audio + muxed mp4-360 music video) and offline downloads
    // all resolve directly from the googlevideo CDN via NewPipe (see StreamResolver /
    // SleppifyDownloaderResolver).

    // Unique WorkManager queue for single-track (manual) offline downloads. Enqueue single-track
    // work with this exact name + ExistingWorkPolicy.APPEND_OR_REPLACE so its progress/counter is
    // picked up by the manual-queue observers (a plain enqueue() escapes them and shows no progress).
    const val OFFLINE_MANUAL_TRACK_QUEUE = "offline_manual_track_queue"

    // Single source of truth for the full-screen player's fragment tag. It used to be a raw
    // "song_player" literal duplicated across ~15 sites plus three separate local constants — a
    // typo in any one silently made findFragmentByTag() miss and add a DUPLICATE player fragment.
    const val TAG_SONG_PLAYER = "song_player"

    // Player-state SharedPreferences keys, previously written/read as raw literals in many files
    // (the web cookie gates ALL stream resolution). One canonical name so writer and reader can
    // never silently drift apart on a rename.
    const val PREF_LAST_YOUTUBE_WEB_COOKIE = "stream_last_youtube_web_cookie"
}
