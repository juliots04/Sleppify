package com.example.sleppify

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException

/** Owns only files created by Sleppify. A folder change never grants ownership of other files. */
object DownloadLocationStore {
    const val KEY_CUSTOM = "downloads_custom_location"
    const val KEY_TREE = "downloads_tree_uri"
    private const val KEY_VISIBLE = "downloads_visible_in_storage"
    private const val KEY_ERROR = "downloads_location_error"
    private const val INDEX = "downloaded_audio_locations"
    private const val PENDING = "pending_audio_publications"
    private fun settings(context: Context) = context.getSharedPreferences(AppConstants.PREFS_SETTINGS, Context.MODE_PRIVATE)
    private fun index(context: Context) = context.getSharedPreferences(INDEX, Context.MODE_PRIVATE)
    private fun pending(context: Context) = context.getSharedPreferences(PENDING, Context.MODE_PRIVATE)

    data class Entry(val uri: Uri, val bytes: Long, val title: String, val artist: String,
                     val extension: String, val destination: String)

    @JvmStatic fun isCustom(context: Context) = settings(context).getBoolean(KEY_CUSTOM, false)
    @JvmStatic fun isVisibleInStorage(context: Context) = settings(context).getBoolean(KEY_VISIBLE, true)
    @JvmStatic fun setVisibleInStorage(context: Context, visible: Boolean) {
        settings(context).edit().putBoolean(KEY_VISIBLE, visible).remove(KEY_ERROR).apply()
    }
    @JvmStatic fun tree(context: Context): Uri? = settings(context).getString(KEY_TREE, null)?.let(Uri::parse)
    @JvmStatic fun lastError(context: Context) = settings(context).getString(KEY_ERROR, "").orEmpty()
    @JvmStatic fun description(context: Context): String = if (!isVisibleInStorage(context)) {
        "Solo en Sleppify"
    } else if (isCustom(context)) {
        tree(context)?.let { Uri.decode(DocumentsContract.getTreeDocumentId(it)) } ?: "Selecciona una carpeta"
    } else "Music/Sleppify"

    @JvmStatic fun chooseFolder(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        settings(context).edit().putString(KEY_TREE, uri.toString()).putBoolean(KEY_CUSTOM, true).putBoolean(KEY_VISIBLE, true)
            .remove(KEY_ERROR).apply()
    }

    @JvmStatic fun useDefault(context: Context) {
        settings(context).edit().putBoolean(KEY_CUSTOM, false).putBoolean(KEY_VISIBLE, true).remove(KEY_ERROR).apply()
    }

    @JvmStatic fun get(context: Context, id: String): Entry? = try {
        index(context).getString(OfflineAudioStore.normalizeTrackId(id), null)?.let {
            val json = JSONObject(it)
            Entry(Uri.parse(json.getString("uri")), json.getLong("bytes"), json.optString("title"),
                json.optString("artist"), json.optString("extension", "m4a"), json.optString("destination"))
        }
    } catch (_: Exception) { null }

    @JvmStatic fun ids(context: Context): Set<String> = index(context).all.keys.toSet()

    @JvmStatic fun exists(context: Context, id: String): Boolean {
        val entry = get(context, id) ?: return false
        return try {
            context.contentResolver.openAssetFileDescriptor(entry.uri, "r")?.use { true } ?: false
        } catch (_: Exception) { false }
    }

    @JvmStatic fun totalBytes(context: Context): Long = ids(context).sumOf { get(context, it)?.bytes ?: 0L }

    @JvmStatic @Synchronized fun delete(context: Context, id: String): Boolean {
        val key = OfflineAudioStore.normalizeTrackId(id)
        val entry = get(context, key) ?: return false
        val removed = deleteUri(context, entry.uri) || isConfirmedMissing(context, entry.uri)
        // Keep the record when permission has been revoked, so the user can reconnect and retry.
        if (removed) index(context).edit().remove(key).apply()
        return removed
    }

    @JvmStatic fun deleteAll(context: Context): Int = ids(context).count { delete(context, it) }

    private fun isConfirmedMissing(context: Context, uri: Uri): Boolean = try {
        if (uri.scheme == "file") !File(uri.path!!).exists()
        else context.contentResolver.query(uri, null, null, null, null)?.use { !it.moveToFirst() } ?: false
    } catch (_: Exception) { false }

    /** Called after validation, on a worker. The private staging file is removed only after commit. */
    @JvmStatic @Synchronized
    fun publish(context: Context, id: String, title: String?, artist: String?): Boolean {
        val key = OfflineAudioStore.normalizeTrackId(id)
        val previous = get(context, key)
        // Recover a copy interrupted by process death, retaining a destination already committed.
        pending(context).getString(key, null)?.let { uri ->
            val unfinished = Uri.parse(uri)
            if (unfinished != previous?.uri) deleteUri(context, unfinished)
            pending(context).edit().remove(key).commit()
        }
        if (!isVisibleInStorage(context)) return moveToPrivateStorage(context, key, previous)
        val targetLocation = if (isCustom(context)) tree(context)?.toString()
            ?: return fail(context, "Selecciona una carpeta de descargas") else "public:Music/Sleppify"
        val staging = OfflineAudioStore.getExistingOfflineAudioFile(context, key)
        if (previous?.destination == targetLocation && exists(context, key)) {
            if (staging.isFile) staging.delete()
            return true
        }
        val source = if (staging.isFile) Uri.fromFile(staging) else previous?.uri
            ?: return fail(context, "No se encontró el archivo descargado")
        val expectedBytes = if (staging.isFile) staging.length() else previous!!.bytes
        val extension = if (staging.isFile) staging.extension.lowercase() else previous!!.extension
        val displayTitle = title?.takeIf { it.isNotBlank() } ?: previous?.title?.takeIf { it.isNotBlank() } ?: key
        val displayArtist = artist?.takeIf { it.isNotBlank() } ?: previous?.artist.orEmpty()
        val filename = DownloadFileNames.forTrack(key, displayTitle, displayArtist, extension)
        val mime = when (extension) {
            "webm" -> "audio/webm"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            else -> "audio/mp4"
        }
        var target: Uri? = null
        var committed = false
        try {
            target = createTarget(context, targetLocation, filename, mime, displayTitle, displayArtist)
            if (!pending(context).edit().putString(key, target.toString()).commit()) {
                throw IOException("No se pudo preparar el archivo de descarga")
            }
            var copied = 0L
            context.contentResolver.openInputStream(source)?.use { input ->
                context.contentResolver.openOutputStream(target, "w")?.use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Download move cancelled")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                    }
                    output.flush()
                } ?: throw IOException("No se puede escribir en la carpeta elegida")
            } ?: throw IOException("No se puede leer la descarga")
            if (copied <= 0 || copied != expectedBytes) throw IOException("La copia de la descarga quedó incompleta")
            if (Build.VERSION.SDK_INT >= 29 && targetLocation.startsWith("public:")) {
                context.contentResolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            val record = JSONObject().apply {
                put("uri", target.toString()); put("bytes", copied); put("title", displayTitle)
                put("artist", displayArtist); put("extension", extension); put("destination", targetLocation)
            }
            if (!index(context).edit().putString(key, record.toString()).commit()) throw IOException("No se pudo registrar la descarga")
            committed = true
            pending(context).edit().remove(key).commit()
            if (staging.isFile) staging.delete()
            if (previous != null && previous.uri != target) deleteUri(context, previous.uri)
            if (target.scheme == "file") MediaScannerConnection.scanFile(context, arrayOf(target.path!!), arrayOf(mime), null)
            settings(context).edit().remove(KEY_ERROR).apply()
            OfflineAudioStore.invalidateOfflineAudioState(key)
            return true
        } catch (error: Exception) {
            if (committed) {
                OfflineAudioStore.invalidateOfflineAudioState(key)
                return true
            }
            target?.let { deleteUri(context, it) }
            pending(context).edit().remove(key).commit()
            if (error is InterruptedIOException) Thread.currentThread().interrupt()
            return fail(context, error.message ?: "No se pudo guardar en la carpeta de descargas")
        }
    }

    private fun moveToPrivateStorage(context: Context, key: String, previous: Entry?): Boolean {
        if (previous == null) return OfflineAudioStore.getExistingOfflineAudioFile(context, key).isFile
        val target = OfflineAudioStore.getOfflineAudioFileForFormat(context, key, previous.extension == "webm")
        val temporary = File(target.parentFile, "${target.name}.migration")
        try {
            if (!target.isFile || target.length() != previous.bytes) {
                context.contentResolver.openInputStream(previous.uri)?.use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Download move cancelled")
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                } ?: throw IOException("No se puede leer la descarga")
                if (temporary.length() != previous.bytes || previous.bytes <= 0 || !temporary.renameTo(target)) {
                    throw IOException("No se pudo completar el traslado a Sleppify")
                }
            }
            if (!deleteUri(context, previous.uri) && !isConfirmedMissing(context, previous.uri)) {
                throw IOException("La copia está en Sleppify, pero no se pudo retirar el archivo de la carpeta anterior")
            }
            index(context).edit().remove(key).commit()
            settings(context).edit().remove(KEY_ERROR).apply()
            OfflineAudioStore.invalidateOfflineAudioState(key)
            return true
        } catch (error: Exception) {
            if (error is InterruptedIOException) Thread.currentThread().interrupt()
            return fail(context, error.message ?: "No se pudo mover la descarga")
        } finally {
            temporary.delete()
        }
    }

    private fun createTarget(context: Context, destination: String, filename: String, mime: String,
                             title: String, artist: String): Uri {
        if (!destination.startsWith("public:")) {
            val tree = Uri.parse(destination)
            val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            return DocumentsContract.createDocument(context.contentResolver, document, mime, filename)
                ?: throw IOException("La carpeta elegida ya no está disponible")
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, filename)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.TITLE, title)
                put(MediaStore.Audio.Media.ARTIST, artist)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Sleppify")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
            }
            return context.contentResolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IOException("No se pudo crear el archivo en Music/Sleppify")
        }
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            throw IOException("Permite guardar archivos desde Descargas y almacenamiento, o elige una carpeta personalizada")
        }
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Sleppify")
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("No se pudo crear Music/Sleppify")
        // Never overwrite a same-named file supplied by another app or the user.
        var file = File(folder, filename)
        var suffix = 1
        while (!file.createNewFile()) file = File(folder, "${filename.substringBeforeLast('.')} (${suffix++}).${filename.substringAfterLast('.')}")
        return Uri.fromFile(file)
    }

    private fun deleteUri(context: Context, uri: Uri): Boolean = try {
        when {
            uri.scheme == "file" -> File(uri.path!!).delete()
            DocumentsContract.isDocumentUri(context, uri) -> DocumentsContract.deleteDocument(context.contentResolver, uri)
            else -> context.contentResolver.delete(uri, null, null) > 0
        }
    } catch (_: Exception) { false }

    private fun fail(context: Context, message: String): Boolean {
        settings(context).edit().putString(KEY_ERROR, message).apply()
        android.util.Log.w("DownloadLocation", message)
        return false
    }
}
