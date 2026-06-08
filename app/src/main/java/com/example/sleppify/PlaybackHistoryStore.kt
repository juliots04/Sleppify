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
        @JvmField val currentIndex: Int,
        @JvmField val currentSeconds: Int,
        @JvmField val totalSeconds: Int,
        @JvmField val isPlaying: Boolean,
        @JvmField val updatedAtMs: Long,
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
        private val IO_EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor()
        private val CACHE_LOCK = Any()

        @Volatile
        private var cachedRawSnapshot: String = ""

        @Volatile
        private var cachedSnapshot: Snapshot = emptySnapshot()

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
                }
                return empty
            }

            synchronized(CACHE_LOCK) {
                if (TextUtils.equals(raw, cachedRawSnapshot)) {
                    return cachedSnapshot
                }
            }

            return try {
                val parsed = parseSnapshot(raw)
                synchronized(CACHE_LOCK) {
                    cachedRawSnapshot = raw
                    cachedSnapshot = parsed
                }
                parsed
            } catch (e: Exception) {
                // If the JSON is corrupted, clear it and return empty
                synchronized(CACHE_LOCK) {
                    cachedRawSnapshot = ""
                    cachedSnapshot = emptySnapshot()
                }
                emptySnapshot()
            }
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

                    synchronized(CACHE_LOCK) {
                        if (TextUtils.equals(raw, cachedRawSnapshot)) {
                            cachedSnapshot = snapshot
                            return@Runnable
                        }
                    }

                    val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val editor = prefs.edit().putString(KEY_SNAPSHOT_JSON, raw)
                    // Always use commit() — this task runs on IO_EXECUTOR (background thread),
                    // so commit() does NOT block the UI thread. Using apply() here is unsafe:
                    // apply() defers the disk write to the main thread's message queue, which
                    // means a crash or force-kill can discard the pending write before it ever
                    // reaches disk — resulting in the mini-player showing 0:00 on next launch.
                    editor.commit()

                    synchronized(CACHE_LOCK) {
                        cachedRawSnapshot = raw
                        cachedSnapshot = snapshot
                    }
                } catch (e: Exception) {
                    Log.w("PlaybackHistStore", "Failed to parse playback snapshot", e)
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
