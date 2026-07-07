package com.example.sleppify

import androidx.work.Data
import androidx.work.WorkInfo

/**
 * Single source of truth for reading the offline-download [OfflinePlaylistDownloadWorker]'s
 * progress blob and computing the counter/fraction the UI shows.
 *
 * Previously the 8 Data-key reads, the per-track fraction aggregation, and the
 * "(downloaded + in-flight fractions) / total" math were copy-pasted across
 * MusicPlayerFragment (x2 observers) and PlaylistDetailFragment. Now they live here once.
 */
class DownloadProgress private constructor(
    @JvmField val done: Int,
    @JvmField val total: Int,
    @JvmField val downloaded: Int,
    @JvmField val currentId: String,
    @JvmField val playlistTitle: String,
    /** trackId -> 0..1 download fraction of the currently in-flight tracks (insertion-ordered). */
    @JvmField val perTrackFraction: Map<String, Float>
) {
    /** Ordered active track ids (the keys of [perTrackFraction]). */
    val activeIds: Array<String> get() = perTrackFraction.keys.toTypedArray()

    /** In-flight fractions aligned 1:1 with [activeIds]. */
    val activeFractions: FloatArray get() = perTrackFraction.values.toFloatArray()

    /** True once the worker has emitted anything meaningful. */
    val hasSignal: Boolean get() = total > 0 || perTrackFraction.isNotEmpty()

    /** Overall 0..1 completion = (fully-downloaded + sum of in-flight fractions) / total. */
    val overallFraction: Float
        get() {
            if (total <= 0) return 0f
            var sumActive = 0f
            for (f in perTrackFraction.values) sumActive += f.coerceIn(0f, 1f)
            return ((downloaded + sumActive) / total.toFloat()).coerceIn(0f, 1f)
        }

    companion object {
        @JvmField
        val EMPTY = DownloadProgress(0, 0, 0, "", "", emptyMap())

        /** Parse a single Worker progress [Data] blob. */
        @JvmStatic
        fun from(data: Data?): DownloadProgress {
            if (data == null) return EMPTY
            val ids = data.getStringArray(OfflinePlaylistDownloadWorker.PROGRESS_ACTIVE_IDS) ?: emptyArray()
            val fractions = data.getFloatArray(OfflinePlaylistDownloadWorker.PROGRESS_ACTIVE_FRACTIONS) ?: FloatArray(0)
            val map = LinkedHashMap<String, Float>()
            for (i in ids.indices) {
                val id = ids[i]?.trim().orEmpty()
                if (id.isEmpty()) continue
                val v = (if (i < fractions.size) fractions[i] else 0f).coerceIn(0f, 1f)
                val prev = map[id]
                if (prev == null || v > prev) map[id] = v
            }
            return DownloadProgress(
                done = data.getInt(OfflinePlaylistDownloadWorker.PROGRESS_DONE, 0),
                total = data.getInt(OfflinePlaylistDownloadWorker.PROGRESS_TOTAL, 0),
                downloaded = data.getInt(OfflinePlaylistDownloadWorker.PROGRESS_DOWNLOADED, 0),
                currentId = data.getString(OfflinePlaylistDownloadWorker.PROGRESS_CURRENT_ID)?.trim().orEmpty(),
                playlistTitle = data.getString(OfflinePlaylistDownloadWorker.PROGRESS_PLAYLIST_TITLE)?.trim().orEmpty(),
                perTrackFraction = map
            )
        }

        /**
         * Merge every RUNNING [WorkInfo] into one progress view: max of the counters, union of the
         * in-flight tracks (keeping the highest fraction seen per id), first non-empty id/title.
         */
        @JvmStatic
        fun mergeRunning(infos: Collection<WorkInfo>): DownloadProgress {
            var done = 0
            var total = 0
            var downloaded = 0
            var currentId = ""
            var title = ""
            val map = LinkedHashMap<String, Float>()
            for (info in infos) {
                if (info.state != WorkInfo.State.RUNNING) continue
                val p = from(info.progress)
                done = maxOf(done, p.done)
                total = maxOf(total, p.total)
                downloaded = maxOf(downloaded, p.downloaded)
                if (currentId.isEmpty() && p.currentId.isNotEmpty()) currentId = p.currentId
                if (title.isEmpty() && p.playlistTitle.isNotEmpty()) title = p.playlistTitle
                for ((id, v) in p.perTrackFraction) {
                    val prev = map[id]
                    if (prev == null || v > prev) map[id] = v
                }
            }
            if (currentId.isEmpty() && map.isNotEmpty()) currentId = map.keys.first()
            return DownloadProgress(done, total, downloaded, currentId, title, map)
        }
    }
}
