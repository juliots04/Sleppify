package com.example.sleppify

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import java.io.File
import java.util.LinkedHashMap

object OfflineAudioStore {
    private const val OFFLINE_AUDIO_DIR = "offline_audio"
    private const val OFFLINE_AUDIO_EXTENSION_WEBM = ".webm"
    private const val OFFLINE_AUDIO_EXTENSION = ".m4a"
    private const val OFFLINE_VIDEO_EXTENSION = ".mp4"
    private const val LEGACY_OFFLINE_AUDIO_EXTENSION = ".mp3"
    private const val LEGACY_BIN_OFFLINE_AUDIO_EXTENSION = ".bin"

    // Pref del toggle "Usar tarjeta SD" (Configuración > Descargas, archivo "sleppify_settings").
    // Redirige el directorio de ESCRITURA a la SD; las búsquedas/borrados siempre recorren ambos
    // volúmenes, así que las descargas viejas siguen encontrándose después de cambiar el toggle.
    const val KEY_USE_SD_CARD = "use_sd_card"

    // Lookup priority shared by every existing-file search (video first, then audio, then legacy).
    private val LOOKUP_EXTENSIONS = arrayOf(
        OFFLINE_VIDEO_EXTENSION,
        OFFLINE_AUDIO_EXTENSION_WEBM,
        OFFLINE_AUDIO_EXTENSION,
        LEGACY_OFFLINE_AUDIO_EXTENSION,
        LEGACY_BIN_OFFLINE_AUDIO_EXTENSION
    )
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

    // Fingerprints (path:size:mtime) of files that PASSED full validation this process. Full
    // validation opens a MediaMetadataRetriever per file (10-100ms of disk+parse); the UI calls
    // hasValidatedOfflineAudio for every visible row on every scroll-settle and for EVERY track
    // of a playlist on each ready-state recompute — re-parsing unchanged files over and over was
    // the main source of jank until caches "warmed". A file that changes on disk (re-download,
    // truncation) changes its size/mtime, so the fingerprint self-invalidates.
    private val VALIDATED_FINGERPRINTS = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
            return size > OFFLINE_STATE_CACHE_MAX_ENTRIES
        }
    }

    private fun fingerprintOf(file: File): String {
        return file.absolutePath + ":" + file.length() + ":" + file.lastModified()
    }

    // Resolved once per process: getExternalFilesDirs es una llamada binder — no queremos pagarla
    // en cada lookup (hasOfflineAudio corre por fila visible). Un cambio de montaje de SD en
    // caliente es rarísimo; refreshSdCardState() permite re-evaluar si hiciera falta.
    @Volatile
    private var cachedSdDir: File? = null
    @Volatile
    private var sdDirResolved = false

    private fun getSdOfflineAudioDir(context: Context): File? {
        if (sdDirResolved) return cachedSdDir
        val resolved = try {
            context.getExternalFilesDirs(null)
                ?.drop(1) // el slot 0 es SIEMPRE el volumen primario (interno); el resto es SD real
                ?.filterNotNull()
                ?.firstOrNull { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
                ?.let { File(it, OFFLINE_AUDIO_DIR) }
        } catch (_: Exception) {
            null
        }
        cachedSdDir = resolved
        sdDirResolved = true
        return resolved
    }

    @JvmStatic
    fun refreshSdCardState() {
        sdDirResolved = false
    }

    @JvmStatic
    fun hasSdCard(context: Context): Boolean = getSdOfflineAudioDir(context) != null

    private fun useSdCard(context: Context): Boolean {
        return context.getSharedPreferences(AppConstants.PREFS_SETTINGS, Context.MODE_PRIVATE)
            .getBoolean(KEY_USE_SD_CARD, false)
    }

    private fun internalOfflineAudioDir(context: Context): File =
        File(context.filesDir, OFFLINE_AUDIO_DIR)

    /** WRITE directory: SD when the toggle is on and a card is mounted, internal otherwise. */
    @JvmStatic
    fun getOfflineAudioDir(context: Context): File {
        if (useSdCard(context)) {
            getSdOfflineAudioDir(context)?.let { return it }
        }
        return internalOfflineAudioDir(context)
    }

    /** Every volume that can hold downloads — current write dir first, for lookup locality. */
    private fun allOfflineAudioDirs(context: Context): List<File> {
        val internal = internalOfflineAudioDir(context)
        val sd = getSdOfflineAudioDir(context) ?: return listOf(internal)
        return if (useSdCard(context)) listOf(sd, internal) else listOf(internal, sd)
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
        for (dir in allOfflineAudioDirs(context)) {
            val mp4 = File(dir, normalized + OFFLINE_VIDEO_EXTENSION)
            if (mp4.isFile && mp4.length() >= MIN_VALID_AUDIO_FILE_BYTES) {
                return true
            }
        }
        return false
    }

    /** El .mp4 offline existente en cualquiera de los volúmenes, o null si no hay. */
    @JvmStatic
    fun findExistingOfflineVideoFile(context: Context, trackId: String): File? {
        val normalized = normalizeTrackId(trackId)
        for (dir in allOfflineAudioDirs(context)) {
            val mp4 = File(dir, normalized + OFFLINE_VIDEO_EXTENSION)
            if (mp4.isFile && mp4.length() > 0L) {
                return mp4
            }
        }
        return null
    }

    @JvmStatic
    fun getExistingOfflineAudioFile(context: Context, trackId: String): File {
        val normalized = normalizeTrackId(trackId)
        val dirs = allOfflineAudioDirs(context)

        // Extension priority outranks volume: an .mp4 on either volume beats a .webm anywhere.
        for (ext in LOOKUP_EXTENSIONS) {
            for (dir in dirs) {
                val candidate = File(dir, normalized + ext)
                if (candidate.isFile && candidate.length() > 0L) {
                    return candidate
                }
            }
        }

        // Nothing on disk: report the preferred-format path on the current WRITE volume
        // (callers treat a non-existing result as "not downloaded").
        return File(getOfflineAudioDir(context), normalized + OFFLINE_AUDIO_EXTENSION)
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

        // Always check disk — do NOT short-circuit on the boolean cache. This method is the
        // "source of truth" that callers rely on to detect failed downloads.
        val existing = getExistingOfflineAudioFile(context, normalized)
        if (!existing.isFile || existing.length() <= 0L) {
            putCachedOfflineState(normalized, false)
            synchronized(OFFLINE_STATE_CACHE_LOCK) { VALIDATED_FINGERPRINTS.remove(normalized) }
            return false
        }

        // Fingerprint fast-path: if this exact file (path+size+mtime) already passed FULL
        // validation this process, it is still valid — skip the expensive MediaMetadataRetriever
        // parse. The disk stat above still guarantees a deleted/replaced file is re-checked.
        val fingerprint = fingerprintOf(existing)
        synchronized(OFFLINE_STATE_CACHE_LOCK) {
            if (VALIDATED_FINGERPRINTS[normalized] == fingerprint) {
                OFFLINE_STATE_CACHE[normalized] = true
                return true
            }
        }

        val actualDurationSeconds = readAudioDurationSeconds(existing)

        // Genuinely-tiny files are junk — safe to remove.
        if (existing.length() < MIN_VALID_AUDIO_FILE_BYTES) {
            deleteOfflineAudio(context, normalized)
            return false
        }

        // Duration could not be read (transient MediaMetadataRetriever failure) on an
        // otherwise size-valid file: do NOT delete. This method is called from read-only UI
        // status scans, and deleting here would destroy a good download and make the progress
        // circle regress on a mere refresh. Report not-validated-now without poisoning the cache.
        if (actualDurationSeconds < 0) {
            return false
        }

        // Positively too short -> corrupt/partial download, remove it.
        if (actualDurationSeconds < MIN_VALID_AUDIO_DURATION_SECONDS) {
            deleteOfflineAudio(context, normalized)
            return false
        }

        val expectedDurationSeconds = parseDurationSeconds(expectedDurationLabel)
        if (expectedDurationSeconds <= 0) {
            // Deliberately NOT fingerprinting this pass: without an expected duration the
            // ratio check was skipped, so this is a WEAKER validation. Fingerprinting it would
            // let a later call WITH a real duration label short-circuit to true on a partial
            // file that the ratio check exists to catch (and the worker would never repair it).
            // Only the full-strictness PASS below stores the fingerprint.
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
            // Shorter than expected. Don't delete on a read-only scan; just report not-validated
            // so a re-download can replace it. The worker's own post-download
            // validateDownloadedTrackFile still prunes genuinely-bad downloads at fetch time.
            putCachedOfflineState(normalized, false)
            return false
        }

        putCachedOfflineState(normalized, true)
        synchronized(OFFLINE_STATE_CACHE_LOCK) { VALIDATED_FINGERPRINTS[normalized] = fingerprint }
        return true
    }

    @JvmStatic
    fun deleteOfflineAudio(context: Context, trackId: String): Boolean {
        val normalized = normalizeTrackId(trackId)
        var removedAny = false
        // Purga en TODOS los volúmenes: la pista pudo descargarse antes de cambiar el toggle SD.
        for (dir in allOfflineAudioDirs(context)) {
            for (ext in LOOKUP_EXTENSIONS) {
                val file = File(dir, normalized + ext)
                if (file.exists()) {
                    removedAny = file.delete() || removedAny
                }
            }
        }
        putCachedOfflineState(normalized, false)
        synchronized(OFFLINE_STATE_CACHE_LOCK) { VALIDATED_FINGERPRINTS.remove(normalized) }
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
        var removedCount = 0
        for (dir in allOfflineAudioDirs(context)) {
            val files = dir.listFiles() ?: continue
            for (file in files) {
                if (file.isFile) {
                    if (file.delete()) removedCount++
                }
            }
        }
        clearOfflineAudioStateCache()
        return removedCount
    }

    /**
     * Every downloaded track id currently on disk (normalized filename stem, extension stripped),
     * across all volumes. For standard YouTube ids the normalized stem equals the original videoId,
     * so callers can intersect this set directly against a playlist's videoIds to learn which
     * playlists contain downloads — WITHOUT a per-row disk stat. Meant to be called ONCE off the
     * main thread (e.g. when entering the "Descargas" view), not per scroll.
     */
    @JvmStatic
    fun listDownloadedTrackIds(context: Context): Set<String> {
        val ids = HashSet<String>()
        for (dir in allOfflineAudioDirs(context)) {
            val files = dir.listFiles() ?: continue
            for (file in files) {
                if (!file.isFile || file.length() < MIN_VALID_AUDIO_FILE_BYTES) continue
                val name = file.name
                val ext = LOOKUP_EXTENSIONS.firstOrNull { name.endsWith(it, ignoreCase = true) } ?: continue
                val stem = name.substring(0, name.length - ext.length)
                if (stem.isNotEmpty()) ids.add(stem)
            }
        }
        return ids
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
            VALIDATED_FINGERPRINTS.clear()
        }
    }

    private fun hasOfflineAudioOnDisk(context: Context, normalizedTrackId: String): Boolean {
        for (dir in allOfflineAudioDirs(context)) {
            for (ext in LOOKUP_EXTENSIONS) {
                val file = File(dir, normalizedTrackId + ext)
                if (file.isFile && file.length() >= MIN_VALID_AUDIO_FILE_BYTES) {
                    return true
                }
            }
        }
        return false
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
            } catch (e: Exception) {
                android.util.Log.w("OfflineAudioStore", "Failed to release MediaMetadataRetriever", e)
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
