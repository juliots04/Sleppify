package com.example.sleppify

import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

@PublishedApi internal const val SHARED_PREFS_EXT_TAG = "SharedPrefsExt"

/**
 * Reads a JSON array string from [SharedPreferences] and returns
 * the parsed [JSONArray], or an empty one on failure.
 */
fun SharedPreferences.getJsonArray(key: String): JSONArray {
    val raw = getString(key, "[]") ?: "[]"
    return try {
        JSONArray(raw)
    } catch (e: Exception) {
        Log.w(SHARED_PREFS_EXT_TAG, "Failed to parse JSON array for key=$key", e)
        JSONArray()
    }
}

/**
 * Reads a JSON array string from [SharedPreferences] and maps each
 * element via [transform], skipping entries that throw.
 */
inline fun <T> SharedPreferences.mapJsonArray(
    key: String,
    transform: (JSONObject) -> T
): List<T> {
    val arr = getJsonArray(key)
    val result = ArrayList<T>(arr.length())
    for (i in 0 until arr.length()) {
        try {
            result.add(transform(arr.getJSONObject(i)))
        } catch (e: Exception) {
            Log.w(SHARED_PREFS_EXT_TAG, "Skipping malformed entry at index $i for key=$key", e)
        }
    }
    return result
}
