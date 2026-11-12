package com.example.sleppify

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.util.LinkedHashMap

object OfflineAudioStore {
    private val deletionExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    fun interface DeleteCallback { fun onComplete(deleted: Int) }

    @JvmStatic @JvmOverloads
    fun deleteOfflineAudioAsync(context: Context, ids: List<String>, callback: DeleteCallback? = null) {
        val app = context.applicationContext
        val snapshot = ids.toList()
        deletionExecutor.execute {
            val deleted = try { deleteOfflineAudio(app, snapshot) } catch (error: Exception) {
                android.util.Log.w("OfflineAudioStore", "Cannot delete downloaded audio", error)
                0
            }
            PlaybackEventBus.notifyPlaybackSnapshotUpdated()
            if (callback != null) mainHandler.post { callback.onComplete(deleted) }
        }
    }

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
    private const val MIN_VALID_AUDIO_FILE_BYTES = AudioValidationPolicy.MIN_BYTES
    private const val OFFLINE_STATE_CACHE_MAX_ENTRIES = 4096

    private val OFFLINE_STATE_CACHE_LOCK = Any()
    private val OFFLINE_STATE_CACHE = object : LinkedHashMap<String, Boolean>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean {
            return size > OFFLINE_STATE_CACHE_MAX_ENTRIES
        }
    }

    // Cache file inspection separately from duration expectations. A row without duration and
    // a playlist scan with duration reuse the same read, but both run the appropriate policy.
    private data class InspectionCache(val fingerprint: String, val metadata: AudioFileMetadata)
    private val INSPECTIONS = object : LinkedHashMap<String, InspectionCache>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, InspectionCache>): Boolean =
            size > OFFLINE_STATE_CACHE_MAX_ENTRIES
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
    fun hasSdCard(context: Context): Boolean {
        refreshSdCardState()
        val dir = getSdOfflineAudioDir(context) ?: return false
        return try {
            val parent = dir.parentFile ?: dir
            (parent.exists() || parent.mkdirs()) && StatFs(parent.absolutePath).totalBytes > 0L
        } catch (_: Exception) {
            false
        }
    }

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

    /** Playback uses content URIs for public/custom folders and paths for legacy staging files. */
    @JvmStatic
    fun getPlaybackSource(context: Context, trackId: String): String? {
        val file = getExistingOfflineAudioFile(context, trackId)
        return file.takeIf { it.isFile && it.length() > 0 }?.absolutePath
            ?: DownloadLocationStore.get(context, trackId)?.uri?.toString()
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
    fun hasValidatedOfflineAudio(context: Context, trackId: String, expectedDurationLabel: String?): Boolean =
        validateOfflineAudio(context, trackId, expectedDurationLabel) == AudioValidationPolicy.Result.VALID

    /** UNREADABLE never authorizes deletion: a folder/decoder may be temporarily unavailable. */
    @JvmStatic
    fun validateOfflineAudio(context: Context, trackId: String, expectedDurationLabel: String?): AudioValidationPolicy.Result {
        val normalized = normalizeTrackId(trackId)
        val existing = getExistingOfflineAudioFile(context, normalized)
        val stored = if (existing.isFile) null else DownloadLocationStore.get(context, normalized)
        if (stored == null && !existing.isFile) {
            markOfflineAudioState(normalized, false)
            return AudioValidationPolicy.Result.INVALID
        }
        val uri = stored?.uri ?: android.net.Uri.fromFile(existing)
        val size = if (stored == null) existing.length() else try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                if (it.length >= 0) it.length else it.parcelFileDescriptor.statSize.takeIf { n -> n >= 0 } ?: stored.bytes
            } ?: return AudioValidationPolicy.Result.UNREADABLE
        } catch (_: Exception) { return AudioValidationPolicy.Result.UNREADABLE }
        val fingerprint = if (stored == null) fingerprintOf(existing) else "$uri:$size"
        val cached = synchronized(OFFLINE_STATE_CACHE_LOCK) { INSPECTIONS[normalized] }
        val metadata = if (size < MIN_VALID_AUDIO_FILE_BYTES) null
            else if (cached?.fingerprint == fingerprint) cached.metadata
            else AudioFileMetadata.read(context, uri)
        val result = AudioValidationPolicy.evaluate(size, metadata?.durationSeconds ?: -1,
            metadata?.audioTracks ?: -1, metadata?.channels ?: -1,
            TrackDuration.parseSeconds(expectedDurationLabel, -1))
        when (result) {
            AudioValidationPolicy.Result.VALID -> synchronized(OFFLINE_STATE_CACHE_LOCK) {
                INSPECTIONS[normalized] = InspectionCache(fingerprint, metadata!!)
                OFFLINE_STATE_CACHE[normalized] = true
            }
            AudioValidationPolicy.Result.INVALID -> deleteOfflineAudio(context, normalized)
            AudioValidationPolicy.Result.UNREADABLE -> synchronized(OFFLINE_STATE_CACHE_LOCK) {
                INSPECTIONS.remove(normalized)
            }
        }
        return result
    }

    @JvmStatic
    fun deleteOfflineAudio(context: Context, trackId: String): Boolean {
        val normalized = normalizeTrackId(trackId)
        var removedAny = DownloadLocationStore.delete(context, normalized)
        // Purga en TODOS los volúmenes: la pista pudo descargarse antes de cambiar el toggle SD.
        for (dir in allOfflineAudioDirs(context)) {
            for (ext in LOOKUP_EXTENSIONS) {
                val file = File(dir, normalized + ext)
                if (file.exists()) {
                    removedAny = file.delete() || removedAny
                }
            }
        }
        putCachedOfflineState(normalized, hasOfflineAudioOnDisk(context, normalized))
        synchronized(OFFLINE_STATE_CACHE_LOCK) { INSPECTIONS.remove(normalized) }
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
        var removedCount = DownloadLocationStore.deleteAll(context)
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

    @JvmStatic
    fun getDownloadsTotalBytes(context: Context): Long {
        var total = DownloadLocationStore.totalBytes(context)
        for (dir in allOfflineAudioDirs(context)) {
            val files = dir.listFiles() ?: continue
            for (file in files) {
                if (file.isFile) {
                    total += file.length().coerceAtLeast(0L)
                }
            }
        }
        return total
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
        for (id in DownloadLocationStore.ids(context)) {
            if (DownloadLocationStore.exists(context, id)) ids.add(id)
        }
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
        DownloadLocationStore.get(context, trackId)?.let { return it.uri }
        val file = getExistingOfflineAudioFile(context, trackId)
        return if (file.exists()) android.net.Uri.fromFile(file) else null
    }

    @JvmStatic
    fun invalidateOfflineAudioState(trackId: String) {
        val key = normalizeTrackId(trackId)
        synchronized(OFFLINE_STATE_CACHE_LOCK) {
            OFFLINE_STATE_CACHE.remove(key)
            INSPECTIONS.remove(key)
        }
    }

    @JvmStatic
    fun clearOfflineAudioStateCache() {
        synchronized(OFFLINE_STATE_CACHE_LOCK) {
            OFFLINE_STATE_CACHE.clear()
            INSPECTIONS.clear()
        }
    }

    private fun hasOfflineAudioOnDisk(context: Context, normalizedTrackId: String): Boolean {
        if (DownloadLocationStore.exists(context, normalizedTrackId)) return true
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
