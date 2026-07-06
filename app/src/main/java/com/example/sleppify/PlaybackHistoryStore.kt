package com.example.sleppify

import android.content.Context
import android.text.TextUtils
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayList
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PlaybackHistoryStore private constructor() {
    class QueueTrack(
        @JvmField val videoId: String,
        @JvmField val title: String,
        @JvmField val artist: String,
        @JvmField val duration: String,
        @JvmField val imageUrl: String
    )

    class Snapshot @JvmOverloads constructor(
        queue: List<QueueTrack>,
        // Scalars are mutable so the cached parsed snapshot can be refreshed in place from the
        // primitive prefs (persisted separately from the queue JSON) without re-parsing or
        // re-copying the queue lists. Consumers use snapshots transiently on the main thread.
        @JvmField var currentIndex: Int,
        @JvmField var currentSeconds: Int,
        @JvmField var totalSeconds: Int,
        @JvmField var isPlaying: Boolean,
        @JvmField var updatedAtMs: Long,
        originalQueue: List<QueueTrack>? = null
    ) {
        @JvmField
        val queue: List<QueueTrack> = Collections.unmodifiableList(ArrayList(queue))

        @JvmField
        val originalQueue: List<QueueTrack> = Collections.unmodifiableList(
            ArrayList(originalQueue?.takeIf { it.isNotEmpty() } ?: queue)
        )

        fun isValid(): Boolean {
            return queue.isNotEmpty() && currentIndex >= 0 && currentIndex < queue.size
        }

        fun currentTrack(): QueueTrack? {
            if (!isValid()) {
                return null
            }
            return queue[currentIndex]
        }
    }

    companion object {
        private const val PREFS_NAME = "playback_history_store"
        private const val KEY_SNAPSHOT_JSON = "snapshot_json"

        // Scalars live OUTSIDE the queue JSON: they change every few seconds during playback,
        // and embedding them in the JSON invalidated the parse cache on every persist — forcing
        // the mini-player's periodic load() to re-parse the whole queue on the main thread.
        private const val KEY_CURRENT_INDEX = "cur_index"
        private const val KEY_CURRENT_SECONDS = "cur_seconds"
        private const val KEY_TOTAL_SECONDS = "total_seconds"
        private const val KEY_IS_PLAYING = "is_playing"
        private const val KEY_UPDATED_AT = "updated_at_ms"

        private val IO_EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor()
        private val CACHE_LOCK = Any()

        @Volatile
        private var cachedRawSnapshot: String = ""

        @Volatile
        private var cachedSnapshot: Snapshot = emptySnapshot()

        /** Identity of the persisted queue contents (ids + sizes). When unchanged, save() skips
         *  re-serializing the queue JSON entirely and only writes the scalar prefs. */
        @Volatile
        private var cachedQueueSignature: Long = Long.MIN_VALUE

        @JvmStatic
        fun load(context: Context): Snapshot {
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_SNAPSHOT_JSON, "").orEmpty()
            if (raw.isEmpty()) {
                val empty = emptySnapshot()
                synchronized(CACHE_LOCK) {
                    cachedRawSnapshot = ""
                    cachedSnapshot = empty
                    cachedQueueSignature = Long.MIN_VALUE
                }
                return empty
            }

            synchronized(CACHE_LOCK) {
                if (TextUtils.equals(raw, cachedRawSnapshot) && cachedSnapshot.queue.isNotEmpty()) {
                    // Queue JSON unchanged — refresh only the scalars (persisted separately) and
                    // reuse the already-parsed snapshot. No JSON parse, no list copies.
                    applyScalarPrefs(prefs, cachedSnapshot)
                    return cachedSnapshot
                }
            }

            return try {
                val parsed = parseSnapshot(raw)
                applyScalarPrefs(prefs, parsed)
                synchronized(CACHE_LOCK) {
                    cachedRawSnapshot = raw
                    cachedSnapshot = parsed
                    cachedQueueSignature = queueSignature(parsed.queue, parsed.originalQueue)
                }
                parsed
            } catch (e: Exception) {
                // If the JSON is corrupted, clear it and return empty
                synchronized(CACHE_LOCK) {
                    cachedRawSnapshot = ""
                    cachedSnapshot = emptySnapshot()
                    cachedQueueSignature = Long.MIN_VALUE
                }
                emptySnapshot()
            }
        }

        /** Overlays the separately-persisted scalar prefs onto [snapshot]. No-ops for legacy data
         *  (scalars embedded in the JSON only) so old snapshots keep working untouched. */
        private fun applyScalarPrefs(prefs: android.content.SharedPreferences, snapshot: Snapshot) {
            if (snapshot.queue.isEmpty() || !prefs.contains(KEY_UPDATED_AT)) return
            snapshot.currentIndex = prefs.getInt(KEY_CURRENT_INDEX, snapshot.currentIndex)
                .coerceIn(0, snapshot.queue.size - 1)
            snapshot.currentSeconds = prefs.getInt(KEY_CURRENT_SECONDS, snapshot.currentSeconds).coerceAtLeast(0)
            snapshot.totalSeconds = prefs.getInt(KEY_TOTAL_SECONDS, snapshot.totalSeconds).coerceAtLeast(1)
            snapshot.isPlaying = prefs.getBoolean(KEY_IS_PLAYING, snapshot.isPlaying)
            snapshot.updatedAtMs = prefs.getLong(KEY_UPDATED_AT, snapshot.updatedAtMs)
        }

        /** Cheap identity of the queue contents: sizes + videoId hashes. Collisions are
         *  astronomically unlikely (64-bit), and a false hit only skips a redundant JSON write. */
        private fun queueSignature(queue: List<QueueTrack>, originalQueue: List<QueueTrack>?): Long {
            var h = 1125899906842597L
            h = h * 31 + queue.size
            for (t in queue) h = h * 31 + t.videoId.hashCode()
            if (originalQueue != null && originalQueue !== queue) {
                h = h * 31 + originalQueue.size
                for (t in originalQueue) h = h * 31 + t.videoId.hashCode()
            }
            return h
        }

        @JvmStatic
        fun save(
            context: Context,
            queue: List<QueueTrack>,
            currentIndex: Int,
            currentSeconds: Int,
            totalSeconds: Int,
            isPlaying: Boolean
        ) {
            save(context, queue, currentIndex, currentSeconds, totalSeconds, isPlaying, false, null)
        }

        @JvmStatic
        fun save(
            context: Context,
            queue: List<QueueTrack>,
            currentIndex: Int,
            currentSeconds: Int,
            totalSeconds: Int,
            isPlaying: Boolean,
            synchronous: Boolean
        ) {
            save(context, queue, currentIndex, currentSeconds, totalSeconds, isPlaying, synchronous, null)
        }

        @JvmStatic
        fun save(
            context: Context,
            queue: List<QueueTrack>,
            currentIndex: Int,
            currentSeconds: Int,
            totalSeconds: Int,
            isPlaying: Boolean,
            synchronous: Boolean,
            originalQueue: List<QueueTrack>?
        ) {
            if (queue.isEmpty()) {
                return
            }

            val appContext = context.applicationContext
            val safeIndex = currentIndex.coerceIn(0, queue.size - 1)
            val safeCurrentSeconds = currentSeconds.coerceAtLeast(0)
            val safeTotalSeconds = totalSeconds.coerceAtLeast(1)
            val updatedAtMs = System.currentTimeMillis()

            val task = Runnable {
                try {
                    val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val signature = queueSignature(queue, originalQueue)

                    val queueUnchanged: Boolean
                    synchronized(CACHE_LOCK) {
                        queueUnchanged = signature == cachedQueueSignature && cachedSnapshot.queue.isNotEmpty()
                    }

                    if (queueUnchanged) {
                        // Queue identical to what is already persisted: write ONLY the scalars.
                        // KEY_SNAPSHOT_JSON stays byte-identical, so load()'s parse cache keeps
                        // hitting — no JSON serialization here, no JSON parse on the readers.
                        // commit() (not apply()) for the same crash-safety reason as below.
                        prefs.edit()
                            .putInt(KEY_CURRENT_INDEX, safeIndex)
                            .putInt(KEY_CURRENT_SECONDS, safeCurrentSeconds)
                            .putInt(KEY_TOTAL_SECONDS, safeTotalSeconds)
                            .putBoolean(KEY_IS_PLAYING, isPlaying)
                            .putLong(KEY_UPDATED_AT, updatedAtMs)
                            .commit()
                        synchronized(CACHE_LOCK) {
                            val cached = cachedSnapshot
                            if (cached.queue.isNotEmpty()) {
                                cached.currentIndex = safeIndex.coerceIn(0, cached.queue.size - 1)
                                cached.currentSeconds = safeCurrentSeconds
                                cached.totalSeconds = safeTotalSeconds
                                cached.isPlaying = isPlaying
                                cached.updatedAtMs = updatedAtMs
                            }
                        }
                        return@Runnable
                    }

                    val queueCopy = copyQueue(queue)
                    val origCopy = if (originalQueue != null) copyQueue(originalQueue) else null
                    val snapshot = Snapshot(
                        queueCopy,
                        safeIndex,
                        safeCurrentSeconds,
                        safeTotalSeconds,
                        isPlaying,
                        updatedAtMs,
                        origCopy
                    )

                    val raw = serializeSnapshot(snapshot)
                    if (raw.isEmpty()) {
                        return@Runnable
                    }

                    // Always use commit() — this task runs on IO_EXECUTOR (background thread),
                    // so commit() does NOT block the UI thread. Using apply() here is unsafe:
                    // apply() defers the disk write to the main thread's message queue, which
                    // means a crash or force-kill can discard the pending write before it ever
                    // reaches disk — resulting in the mini-player showing 0:00 on next launch.
                    prefs.edit()
                        .putString(KEY_SNAPSHOT_JSON, raw)
                        .putInt(KEY_CURRENT_INDEX, safeIndex)
                        .putInt(KEY_CURRENT_SECONDS, safeCurrentSeconds)
                        .putInt(KEY_TOTAL_SECONDS, safeTotalSeconds)
                        .putBoolean(KEY_IS_PLAYING, isPlaying)
                        .putLong(KEY_UPDATED_AT, updatedAtMs)
                        .commit()

                    synchronized(CACHE_LOCK) {
                        cachedRawSnapshot = raw
                        cachedSnapshot = snapshot
                        cachedQueueSignature = signature
                    }
                } catch (e: Exception) {
                    Log.w("PlaybackHistStore", "Failed to persist playback snapshot", e)
                }
            }

            if (synchronous) {
                task.run()
            } else {
                IO_EXECUTOR.execute(task)
            }
        }

        private fun parseSnapshot(raw: String): Snapshot {
            return try {
                val root = JSONObject(raw)
                val queueArray = root.optJSONArray("queue")
                val queue = ArrayList<QueueTrack>()

                if (queueArray != null) {
                    for (i in 0 until queueArray.length()) {
                        val item = queueArray.optJSONObject(i) ?: continue
                        val videoId = safe(item.optString("videoId", ""))
                        if (videoId.isEmpty()) {
                            continue
                        }

                        queue.add(
                            QueueTrack(
                                videoId,
                                safe(item.optString("title", "")),
                                safe(item.optString("artist", "")),
                                safe(item.optString("duration", "")),
                                safe(item.optString("imageUrl", ""))
                            )
                        )
                    }
                }

                val originalQueueArray = root.optJSONArray("originalQueue")
                val originalQueue = ArrayList<QueueTrack>()
                if (originalQueueArray != null) {
                    for (i in 0 until originalQueueArray.length()) {
                        val item = originalQueueArray.optJSONObject(i) ?: continue
                        val vid = safe(item.optString("videoId", ""))
                        if (vid.isEmpty()) continue
                        originalQueue.add(QueueTrack(
                            vid,
                            safe(item.optString("title", "")),
                            safe(item.optString("artist", "")),
                            safe(item.optString("duration", "")),
                            safe(item.optString("imageUrl", ""))
                        ))
                    }
                }

                val currentIndex = root.optInt("currentIndex", 0)
                val currentSeconds = root.optInt("currentSeconds", 0).coerceAtLeast(0)
                val totalSeconds = root.optInt("totalSeconds", 1).coerceAtLeast(1)
                val isPlaying = root.optBoolean("isPlaying", false)
                val updatedAtMs = root.optLong("updatedAtMs", 0L)

                if (queue.isEmpty()) {
                    emptySnapshot()
                } else {
                    val safeIndex = currentIndex.coerceIn(0, queue.size - 1)
                    Snapshot(queue, safeIndex, currentSeconds, totalSeconds, isPlaying, updatedAtMs,
                        originalQueue.takeIf { it.isNotEmpty() })
                }
            } catch (_: Exception) {
                emptySnapshot()
            }
        }

        private fun serializeSnapshot(snapshot: Snapshot): String {
            return try {
                val queueArray = JSONArray()
                for (track in snapshot.queue) {
                    val item = JSONObject()
                    item.put("videoId", safe(track.videoId))
                    item.put("title", safe(track.title))
                    item.put("artist", safe(track.artist))
                    item.put("duration", safe(track.duration))
                    item.put("imageUrl", safe(track.imageUrl))
                    queueArray.put(item)
                }

                val root = JSONObject()
                root.put("queue", queueArray)

                if (snapshot.originalQueue.isNotEmpty() && snapshot.originalQueue != snapshot.queue) {
                    val origArray = JSONArray()
                    for (track in snapshot.originalQueue) {
                        val item = JSONObject()
                        item.put("videoId", safe(track.videoId))
                        item.put("title", safe(track.title))
                        item.put("artist", safe(track.artist))
                        item.put("duration", safe(track.duration))
                        item.put("imageUrl", safe(track.imageUrl))
                        origArray.put(item)
                    }
                    root.put("originalQueue", origArray)
                }

                root.put("currentIndex", snapshot.currentIndex)
                root.put("currentSeconds", snapshot.currentSeconds)
                root.put("totalSeconds", snapshot.totalSeconds)
                root.put("isPlaying", snapshot.isPlaying)
                root.put("updatedAtMs", snapshot.updatedAtMs)
                root.toString()
            } catch (_: Exception) {
                ""
            }
        }

        private fun copyQueue(queue: List<QueueTrack>): List<QueueTrack> {
            val copy = ArrayList<QueueTrack>(queue.size)
            for (track in queue) {
                copy.add(
                    QueueTrack(
                        safe(track.videoId),
                        safe(track.title),
                        safe(track.artist),
                        safe(track.duration),
                        safe(track.imageUrl)
                    )
                )
            }
            return copy
        }

        private fun emptySnapshot(): Snapshot {
            return Snapshot(emptyList(), 0, 0, 1, false, 0L)
        }

        private fun safe(value: String?): String {
            return value.orEmpty()
        }
    }
}
