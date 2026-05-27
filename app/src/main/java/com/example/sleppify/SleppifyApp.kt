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
        } catch (e: Exception) {}

        // ExoPlayer is now lazy-initialized on first use via ExoPlayerManager.getSharedExoPlayer()
        // — no need to block the main thread here.

        // Background: ProviderInstaller for TLS security
        Executors.newSingleThreadExecutor().execute {
            try {
                com.google.android.gms.security.ProviderInstaller.installIfNeeded(this)
            } catch (e: Exception) {
                Log.w("SleppifyApp", "ProviderInstaller failed", e)
            }
        }
    }

    private fun hasExistingSession(): Boolean {
        val prefs = getSharedPreferences("player_state", MODE_PRIVATE)
        val cookie = prefs.getString("stream_last_youtube_web_cookie", "") ?: ""
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
