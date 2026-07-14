package com.example.sleppify

import android.content.Context
import java.io.File

object CommentsCacheManager {

    private const val CACHE_DIR_NAME = "comments_cache"

    private fun getCacheDir(context: Context): File {
        val dir = File(context.cacheDir, CACHE_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun getCacheFile(context: Context, videoId: String): File {
        // Sanitizar el videoId para evitar problemas en el sistema de archivos
        val safeName = videoId.replace(Regex("[^a-zA-Z0-9_-]"), "")
        return File(getCacheDir(context), "comments_$safeName.json")
    }

    /**
     * Guarda la respuesta JSON en disco (solo para la primera página).
     */
    fun saveFirstPageCache(context: Context, videoId: String, jsonBody: String) {
        if (videoId.isEmpty() || jsonBody.isEmpty()) return
        try {
            val file = getCacheFile(context, videoId)
            file.writeText(jsonBody)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Recupera la respuesta JSON de la caché, si existe.
     */
    fun getFirstPageCache(context: Context, videoId: String): String? {
        if (videoId.isEmpty()) return null
        try {
            val file = getCacheFile(context, videoId)
            if (file.exists()) {
                return file.readText()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    /**
     * True si existe un caché de primera página más joven que maxAgeMs. Permite saltarse el
     * refresh de red al abrir el sheet y evitar prefetches redundantes.
     */
    fun isFresh(context: Context, videoId: String, maxAgeMs: Long): Boolean {
        if (videoId.isEmpty()) return false
        return try {
            val file = getCacheFile(context, videoId)
            file.exists() && (System.currentTimeMillis() - file.lastModified()) < maxAgeMs
        } catch (e: Exception) {
            false
        }
    }

    /** Borra el caché de un video (p. ej. cuando el JSON guardado quedó corrupto). */
    fun deleteCache(context: Context, videoId: String) {
        if (videoId.isEmpty()) return
        try {
            getCacheFile(context, videoId).delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Limpia la caché si es necesario (opcional)
     */
    fun clearCache(context: Context) {
        try {
            val dir = getCacheDir(context)
            dir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
