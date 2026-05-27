package com.example.sleppify

import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * Stores per-user display name overrides for any playlist (YouTube, Favorites, custom).
 * Overrides are app-only — they never touch the actual playlist on YouTube.
 * Persisted locally (SharedPreferences) and synced to Firestore under:
 *   users/{uid}/sleppify/playlist_name_overrides  (field: map of playlistId -> displayName)
 */
object PlaylistNameOverrideStore {

    private const val PREFS_NAME = "playlist_name_overrides"
    private const val FIRESTORE_COLLECTION = "users"
    private const val FIRESTORE_APP_SCOPE = "sleppify"
    private const val FIRESTORE_DOC = "playlist_name_overrides"
    private const val FIELD_NAMES = "names"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @JvmStatic
    fun getDisplayName(context: Context, playlistId: String): String? {
        val key = playlistId.trim()
        if (key.isEmpty()) return null
        val v = prefs(context).getString(key, null)
        return if (v.isNullOrBlank()) null else v
    }

    @JvmStatic
    fun setDisplayName(context: Context, playlistId: String, displayName: String) {
        val key = playlistId.trim()
        val name = displayName.trim()
        if (key.isEmpty()) return
        if (name.isEmpty()) {
            prefs(context).edit().remove(key).apply()
        } else {
            prefs(context).edit().putString(key, name).apply()
        }
        syncToCloud(context)
    }

    @JvmStatic
    fun removeDisplayName(context: Context, playlistId: String) {
        val key = playlistId.trim()
        if (key.isEmpty()) return
        prefs(context).edit().remove(key).apply()
        syncToCloud(context)
    }

    @JvmStatic
    fun getAllOverrides(context: Context): Map<String, String> {
        val all = prefs(context).all
        val result = LinkedHashMap<String, String>()
        for ((k, v) in all) {
            if (v is String && v.isNotBlank()) result[k] = v
        }
        return result
    }

    @JvmStatic
    fun mergeFromCloud(context: Context, cloudMap: Map<String, String>) {
        val editor = prefs(context).edit()
        for ((k, v) in cloudMap) {
            if (k.isNotBlank() && v.isNotBlank()) {
                editor.putString(k.trim(), v.trim())
            }
        }
        editor.apply()
    }

    private fun syncToCloud(context: Context) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val allOverrides = getAllOverrides(context)
        val data = mapOf<String, Any>(FIELD_NAMES to allOverrides)
        FirebaseFirestore.getInstance()
            .collection(FIRESTORE_COLLECTION).document(uid)
            .collection(FIRESTORE_APP_SCOPE).document(FIRESTORE_DOC)
            .set(data, SetOptions.merge())
    }

    @JvmStatic
    fun fetchFromCloud(context: Context, onDone: Runnable?) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run { onDone?.run(); return }
        FirebaseFirestore.getInstance()
            .collection(FIRESTORE_COLLECTION).document(uid)
            .collection(FIRESTORE_APP_SCOPE).document(FIRESTORE_DOC)
            .get()
            .addOnSuccessListener { doc ->
                if (doc != null && doc.exists()) {
                    @Suppress("UNCHECKED_CAST")
                    val map = doc.get(FIELD_NAMES) as? Map<String, String>
                    if (!map.isNullOrEmpty()) mergeFromCloud(context, map)
                }
                onDone?.run()
            }
            .addOnFailureListener { onDone?.run() }
    }
}
