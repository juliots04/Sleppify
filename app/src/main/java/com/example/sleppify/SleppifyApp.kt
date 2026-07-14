package com.example.sleppify

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import java.util.concurrent.Executors

class SleppifyApp : Application() {
    @Volatile
    private var mediaProjectionPermissionData: Intent? = null

    @Volatile
    private var heavyInitDone = false

    override fun onCreate() {
        super.onCreate()

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        // Store app context so ExoPlayerManager can lazy-init on first use
        ExoPlayerManager.setAppContext(this)

        // Only run heavy initialization if the user already has a YouTube Music session.
        // On first install (no session), defer until after successful web login.
        if (hasExistingSession()) {
            performHeavyInit()
        } else {
            // performHeavyInit (which pre-warms the data caches) is deferred until after web login,
            // but PlaybackHistoryStore is still read on the cold-start UI path (the mini-player's
            // first updateUi). Pre-warm it off the main thread now so that first read is O(1)
            // instead of a synchronous prefs-XML disk hit on the first frame.
            val warmExec = Executors.newSingleThreadExecutor()
            warmExec.execute {
                try { PlaybackHistoryStore.load(this) } catch (e: Exception) {
                    Log.w("SleppifyApp", "PlaybackHistoryStore pre-warm (no session) failed", e)
                }
            }
            // Let the one-shot warm-up run, then terminate the worker thread instead of leaking an
            // idle non-daemon core thread for the whole process lifetime.
            warmExec.shutdown()
        }
    }

    /**
     * Performs heavy initialization: Glide (main thread), ProviderInstaller (background).
     * ExoPlayer is lazy-initialized on first use via [ExoPlayerManager.getSharedExoPlayer].
     * Called immediately on startup if session exists, or after first login otherwise.
     * Safe to call multiple times — guards with [heavyInitDone].
     */
    fun performDeferredInit() {
        performHeavyInit()
    }

    private fun performHeavyInit() {
        if (heavyInitDone) return
        synchronized(this) {
            if (heavyInitDone) return
            heavyInitDone = true
        }

        // Pre-warm Glide (fast, recommended on main thread)
        try {
            com.bumptech.glide.Glide.get(this)
        } catch (e: Exception) {
            android.util.Log.w("SleppifyApp", "Glide pre-warm failed", e)
        }

        // ExoPlayer is now lazy-initialized on first use via ExoPlayerManager.getSharedExoPlayer()
        // — no need to block the main thread here.

        // Background: pre-warm data caches + ProviderInstaller for TLS security
        val initExec = Executors.newSingleThreadExecutor()
        initExec.execute {
            // Pre-load caches so MainActivity.onCreate finds them warm (O(1) read)
            try { PlaybackHistoryStore.load(this) } catch (e: Exception) {
                Log.w("SleppifyApp", "PlaybackHistoryStore pre-warm failed", e)
            }
            try { FavoritesPlaylistStore.loadFavorites(this) } catch (e: Exception) {
                Log.w("SleppifyApp", "FavoritesPlaylistStore pre-warm failed", e)
            }
            // Pre-warm the streaming-cache prefs XML (large: one entry per cached playlist) so
            // PrincipalFragment's covers cache read is O(1) and cached covers render during
            // startup instead of a skeleton flash + late swap.
            try {
                getSharedPreferences(AppConstants.PREFS_STREAMING_CACHE, MODE_PRIVATE)
                    .getString("home_covers_data", "")
            } catch (e: Exception) {
                Log.w("SleppifyApp", "streaming_cache pre-warm failed", e)
            }
            try {
                com.google.android.gms.security.ProviderInstaller.installIfNeeded(this)
            } catch (e: Exception) {
                Log.w("SleppifyApp", "ProviderInstaller failed", e)
            }
        }
        // Terminate the worker thread after the one-shot init completes (no leaked idle thread).
        initExec.shutdown()
    }

    @Suppress("DEPRECATION") // TRIM_MEMORY_RUNNING_CRITICAL: still delivered on the API levels this app targets
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Glide scales its memory cache down according to the system's pressure signal.
        // Guarded by heavyInitDone so a trim callback never force-initializes Glide.
        if (heavyInitDone) {
            try { com.bumptech.glide.Glide.get(this).trimMemory(level) } catch (e: Exception) {
                Log.w("SleppifyApp", "Glide trimMemory failed", e)
            }
        }
        // When the system genuinely needs memory (process backgrounded in the LRU list, or
        // critical pressure while foreground), drop our own in-memory bitmap caches too.
        // Disk caches (offline songs, art composites, embedded art) are untouched — everything
        // repaints from disk instead of being re-fetched.
        if (level >= TRIM_MEMORY_BACKGROUND || level == TRIM_MEMORY_RUNNING_CRITICAL) {
            try { PlaylistGridArtLoader.trimMemory() } catch (e: Exception) {
                Log.w("SleppifyApp", "PlaylistGridArtLoader trim failed", e)
            }
            try { RadioArtComposer.trimMemory() } catch (e: Exception) {
                Log.w("SleppifyApp", "RadioArtComposer trim failed", e)
            }
            try { LocalArtworkResolver.trimMemory() } catch (e: Exception) {
                Log.w("SleppifyApp", "LocalArtworkResolver trim failed", e)
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (heavyInitDone) {
            try { com.bumptech.glide.Glide.get(this).clearMemory() } catch (e: Exception) {
                Log.w("SleppifyApp", "Glide clearMemory failed", e)
            }
        }
        try { PlaylistGridArtLoader.trimMemory() } catch (e: Exception) {
            Log.w("SleppifyApp", "PlaylistGridArtLoader trim failed", e)
        }
        try { RadioArtComposer.trimMemory() } catch (e: Exception) {
            Log.w("SleppifyApp", "RadioArtComposer trim failed", e)
        }
        try { LocalArtworkResolver.trimMemory() } catch (e: Exception) {
            Log.w("SleppifyApp", "LocalArtworkResolver trim failed", e)
        }
    }

    private fun hasExistingSession(): Boolean {
        val prefs = getSharedPreferences(AppConstants.PREFS_PLAYER_STATE, MODE_PRIVATE)
        val cookie = prefs.getString(AppConstants.PREF_LAST_YOUTUBE_WEB_COOKIE, "") ?: ""
        return cookie.trim().isNotEmpty()
    }

    @Synchronized
    fun setMediaProjectionPermissionData(data: Intent?) {
        mediaProjectionPermissionData = data?.let { Intent(it) }
    }

    @Synchronized
    fun getMediaProjectionPermissionData(): Intent? {
        return mediaProjectionPermissionData?.let { Intent(it) }
    }

    @Synchronized
    fun hasMediaProjectionPermissionData(): Boolean {
        return mediaProjectionPermissionData != null
    }
}
