package com.example.sleppify

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri

/** Reads audio metadata once; a provider/decoder failure stays distinct from an invalid file. */
data class AudioFileMetadata(val durationSeconds: Int, val audioTracks: Int, val channels: Int) {
    companion object {
        fun read(context: Context, uri: Uri): AudioFileMetadata? {
            val extractor = MediaExtractor()
            var audioTracks = 0
            var usableTracks = 0
            var channels = -1
            var durationUs = -1L
            try {
                extractor.setDataSource(context, uri, null)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") != true) continue
                    audioTracks++
                    val count = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                        format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else -1
                    if (count == 0) continue
                    usableTracks++
                    channels = maxOf(channels, count)
                    if (format.containsKey(MediaFormat.KEY_DURATION)) durationUs = maxOf(durationUs, format.getLong(MediaFormat.KEY_DURATION))
                }
            } catch (_: Exception) {
                return null
            } finally {
                runCatching { extractor.release() }
            }
            if (audioTracks == 0 || usableTracks == 0) return AudioFileMetadata(0, audioTracks, 0)
            val duration = if (durationUs > 0) seconds(durationUs / 1000L) else readDuration(context, uri)
            return AudioFileMetadata(duration, audioTracks, channels)
        }

        private fun readDuration(context: Context, uri: Uri): Int {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(context, uri)
                seconds(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L)
            } catch (_: Exception) { -1 }
            finally { runCatching { retriever.release() } }
        }

        private fun seconds(milliseconds: Long): Int = if (milliseconds <= 0) -1
            else (milliseconds / 1000L).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }
}
