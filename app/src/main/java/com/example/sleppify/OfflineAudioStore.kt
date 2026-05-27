package com.example.sleppify

import android.content.Context
import android.media.MediaMetadataRetriever
import java.io.File
import java.util.LinkedHashMap

object OfflineAudioStore {
    private const val OFFLINE_AUDIO_DIR = "offline_audio"
    private const val OFFLINE_AUDIO_EXTENSION_WEBM = ".webm"
    private const val OFFLINE_AUDIO_EXTENSION = ".m4a"
    private const val OFFLINE_VIDEO_EXTENSION = ".mp4"
    private const val LEGACY_OFFLINE_AUDIO_EXTENSION = ".mp3"
    private const val LEGACY_BIN_OFFLINE_AUDIO_EXTENSION = ".bin"
    private const val MIN_VALID_AUDIO_FILE_BYTES = 24L * 1024L
    private const val MIN_VALID_AUDIO_DURATION_SECONDS = 6
    private const val EXPECTED_DURATION_MATCH_RATIO = 0.88f
    private const val EXPECTED_DURATION_LEEWAY_SECONDS = 8
    private const val OFFLINE_STATE_CACHE_MAX_ENTRIES = 4096

    private val OFFLINE_STATE_CACHE_LOCK = Any()
    private val OFFLINE_STATE_CACHE = object : LinkedHashMap<String, Boolean>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean {
            return size > OFFLINE_STATE_CACHE_MAX_ENTRIES
        }
    }

    @JvmStatic
    fun getOfflineAudioDir(context: Context): File {
        return File(context.filesDir, OFFLINE_AUDIO_DIR)
    }

    @JvmStatic
    fun getOfflineAudioFile(context: Context, trackId: String): File {
        val normalized = normalizeTrackId(trackId)
        return File(getOfflineAudioDir(context), normalized + OFFLINE_AUDIO_EXTENSION)
    }

    @JvmStatic
    fun getOfflineAudioFileForFormat(context: Context, trackId: String, isWebm: Boolean): File {
        val normalized = normalizeTrackId(trackId)
        val ext = if (isWebm) OFFLINE_AUDIO_EXTENSION_WEBM else OFFLINE_AUDIO_EXTENSION
        return File(getOfflineAudioDir(context), normalized + ext)
    }

    @JvmStatic
    fun getOfflineVideoFile(context: Context, trackId: String): File {
        val normalized = normalizeTrackId(trackId)
        return File(getOfflineAudioDir(context), normalized + OFFLINE_VIDEO_EXTENSION)
    }

    @JvmStatic
    fun hasOfflineVideo(context: Context, trackId: String): Boolean {
        val normalized = normalizeTrackId(trackId)
        val mp4 = File(getOfflineAudioDir(context), normalized + OFFLINE_VIDEO_EXTENSION)
        return mp4.isFile && mp4.length() >= MIN_VALID_AUDIO_FILE_BYTES
    }

    @JvmStatic
    fun getExistingOfflineAudioFile(context: Context, trackId: String): File {
        val normalized = normalizeTrackId(trackId)

        val mp4 = File(getOfflineAudioDir(context), normalized + OFFLINE_VIDEO_EXTENSION)
        if (mp4.isFile && mp4.length() > 0L) {
            return mp4
        }

        val webm = File(getOfflineAudioDir(context), normalized + OFFLINE_AUDIO_EXTENSION_WEBM)
        if (webm.isFile && webm.length() > 0L) {
            return webm
        }

        val preferred = File(getOfflineAudioDir(context), normalized + OFFLINE_AUDIO_EXTENSION)
        if (preferred.isFile && preferred.length() > 0L) {
            return preferred
        }

        val legacy = File(getOfflineAudioDir(context), normalized + LEGACY_OFFLINE_AUDIO_EXTENSION)
        if (legacy.isFile && legacy.length() > 0L) {
            return legacy
        }

        val legacyBin = File(getOfflineAudioDir(context), normalized + LEGACY_BIN_OFFLINE_AUDIO_EXTENSION)
        if (legacyBin.isFile && legacyBin.length() > 0L) {
            return legacyBin
        }

        return preferred
    }

    @JvmStatic
    fun hasOfflineAudio(context: Context, trackId: String): Boolean {
        val normalized = normalizeTrackId(trackId)
        val cached = getCachedOfflineState(normalized)
        if (cached != null) {
            return cached
        }

        val available = hasOfflineAudioOnDisk(context, normalized)
        putCachedOfflineState(normalized, available)
        return available
    }

    @JvmStatic
    fun countOfflineAvailable(context: Context, trackIds: List<String>): Int {
        var count = 0
        for (id in trackIds) {
            if (id.isNotBlank() && hasOfflineAudio(context, id)) {
                count++
            }
        }
        return count
    }

    @JvmStatic
    fun hasValidatedOfflineAudio(context: Context, trackId: String, expectedDurationLabel: String?): Boolean {
        val normalized = normalizeTrackId(trackId)

        // Always check disk — do NOT short-circuit on cache. This method is the
        // "source of truth" that callers rely on to detect failed downloads.
        val existing = getExistingOfflineAudioFile(context, normalized)
        if (!existing.isFile || existing.length() <= 0L) {
            putCachedOfflineState(normalized, false)
            return false
        }

        val actualDurationSeconds = readAudioDurationSeconds(existing)
        if (!isPlausibleOfflineAudioFile(existing, actualDurationSeconds)) {
            deleteOfflineAudio(context, normalized)
            return false
        }

        val expectedDurationSeconds = parseDurationSeconds(expectedDurationLabel)
        if (expectedDurationSeconds <= 0) {
            putCachedOfflineState(normalized, true)
            return true
        }

        val scaledExpectedSeconds = kotlin.math.round(expectedDurationSeconds * EXPECTED_DURATION_MATCH_RATIO).toInt()
        val minimumAllowedDurationSeconds = kotlin.math.max(
            MIN_VALID_AUDIO_DURATION_SECONDS,
            scaledExpectedSeconds - EXPECTED_DURATION_LEEWAY_SECONDS
        )

        val matchesExpectedDuration = actualDurationSeconds >= minimumAllowedDurationSeconds
        if (!matchesExpectedDuration) {
            android.util.Log.w("OfflineAudioStore", "validation:fail id=$normalized actual=$actualDurationSeconds expected=$expectedDurationLabel (min=$minimumAllowedDurationSeconds)")
            deleteOfflineAudio(context, normalized)
            return false
        }

        putCachedOfflineState(normalized, true)
        return true
    }

    @JvmStatic
    fun deleteOfflineAudio(context: Context, trackId: String): Boolean {
        val normalized = normalizeTrackId(trackId)
        val dir = getOfflineAudioDir(context)
        val mp4 = File(dir, normalized + OFFLINE_VIDEO_EXTENSION)
        val webm = File(dir, normalized + OFFLINE_AUDIO_EXTENSION_WEBM)
        val preferred = File(dir, normalized + OFFLINE_AUDIO_EXTENSION)
        val legacy = File(dir, normalized + LEGACY_OFFLINE_AUDIO_EXTENSION)
        val legacyBin = File(dir, normalized + LEGACY_BIN_OFFLINE_AUDIO_EXTENSION)

        var removedAny = false
        if (mp4.exists()) {
            removedAny = mp4.delete() || removedAny
        }
        if (webm.exists()) {
            removedAny = webm.delete() || removedAny
        }
        if (preferred.exists()) {
            removedAny = preferred.delete() || removedAny
        }
        if (legacy.exists()) {
            removedAny = legacy.delete() || removedAny
        }
        if (legacyBin.exists()) {
            removedAny = legacyBin.delete() || removedAny
        }
        putCachedOfflineState(normalized, false)
        return removedAny
    }

    @JvmStatic
    fun deleteOfflineAudio(context: Context, trackIds: List<String>): Int {
        var removed = 0
        for (id in trackIds) {
            if (id.isBlank()) {
                continue
            }
            if (deleteOfflineAudio(context, id)) {
                removed++
            }
        }
        return removed
    }

    @JvmStatic
    fun deleteAllOfflineAudio(context: Context): Int {
        val dir = getOfflineAudioDir(context)
        val files = dir.listFiles() ?: return 0
        var removedCount = 0
        for (file in files) {
            if (file.isFile) {
                if (file.delete()) removedCount++
            }
        }
        clearOfflineAudioStateCache()
        return removedCount
    }

    @JvmStatic
    fun normalizeTrackId(trackId: String): String {
        val raw = trackId.trim()
        if (raw.isEmpty()) {
            return "track_0"
        }

        val builder = StringBuilder(raw.length)
        for (c in raw) {
            if (c.isLetterOrDigit() || c == '_' || c == '-' || c == '.') {
                builder.append(c)
            } else {
                builder.append('_')
            }
        }

        var normalized = builder.toString()
        if (normalized.isEmpty()) {
            normalized = "track_" + raw.hashCode().toString(16)
        }
        return normalized
    }

    @JvmStatic
    fun markOfflineAudioState(trackId: String, available: Boolean) {
        putCachedOfflineState(normalizeTrackId(trackId), available)
    }

    @JvmStatic
    fun getThumbnailUri(context: Context, trackId: String): android.net.Uri? {
        val file = getExistingOfflineAudioFile(context, trackId)
        return if (file.exists()) android.net.Uri.fromFile(file) else null
    }

    @JvmStatic
    fun clearOfflineAudioStateCache() {
        synchronized(OFFLINE_STATE_CACHE_LOCK) {
            OFFLINE_STATE_CACHE.clear()
        }
    }

    private fun hasOfflineAudioOnDisk(context: Context, normalizedTrackId: String): Boolean {
        val dir = getOfflineAudioDir(context)

        val mp4 = File(dir, normalizedTrackId + OFFLINE_VIDEO_EXTENSION)
        if (mp4.isFile && mp4.length() >= MIN_VALID_AUDIO_FILE_BYTES) {
            return true
        }

        val webm = File(dir, normalizedTrackId + OFFLINE_AUDIO_EXTENSION_WEBM)
        if (webm.isFile && webm.length() >= MIN_VALID_AUDIO_FILE_BYTES) {
            return true
        }

        val preferred = File(dir, normalizedTrackId + OFFLINE_AUDIO_EXTENSION)
        if (preferred.isFile && preferred.length() >= MIN_VALID_AUDIO_FILE_BYTES) {
            return true
        }

        val legacy = File(dir, normalizedTrackId + LEGACY_OFFLINE_AUDIO_EXTENSION)
        if (legacy.isFile && legacy.length() >= MIN_VALID_AUDIO_FILE_BYTES) {
            return true
        }

        val legacyBin = File(dir, normalizedTrackId + LEGACY_BIN_OFFLINE_AUDIO_EXTENSION)
        if (legacyBin.isFile && legacyBin.length() >= MIN_VALID_AUDIO_FILE_BYTES) {
            return true
        }

        return false
    }

    private fun isPlausibleOfflineAudioFile(file: File, durationSeconds: Int): Boolean {
        if (!file.isFile || file.length() < MIN_VALID_AUDIO_FILE_BYTES) {
            return false
        }
        return durationSeconds >= MIN_VALID_AUDIO_DURATION_SECONDS
    }

    private fun readAudioDurationSeconds(file: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMsValue = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            if (durationMsValue.isNullOrBlank()) {
                -1
            } else {
                val durationMs = durationMsValue.trim().toLong()
                if (durationMs <= 0L) {
                    -1
                } else {
                    kotlin.math.max(1L, durationMs / 1000L).toInt()
                }
            }
        } catch (_: Exception) {
            -1
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun parseDurationSeconds(durationLabel: String?): Int {
        if (durationLabel.isNullOrBlank()) {
            return -1
        }

        val parts = durationLabel.trim().split(":")
        return try {
            when (parts.size) {
                2 -> {
                    val minutes = parts[0].toInt()
                    val seconds = parts[1].toInt()
                    (minutes * 60) + seconds
                }
                3 -> {
                    val hours = parts[0].toInt()
                    val minutes = parts[1].toInt()
                    val seconds = parts[2].toInt()
                    (hours * 3600) + (minutes * 60) + seconds
                }
                else -> -1
            }
        } catch (_: Exception) {
            -1
        }
    }

    private fun getCachedOfflineState(normalizedTrackId: String): Boolean? {
        synchronized(OFFLINE_STATE_CACHE_LOCK) {
            return OFFLINE_STATE_CACHE[normalizedTrackId]
        }
    }

    private fun putCachedOfflineState(normalizedTrackId: String, available: Boolean) {
        synchronized(OFFLINE_STATE_CACHE_LOCK) {
            OFFLINE_STATE_CACHE[normalizedTrackId] = available
        }
    }
}
