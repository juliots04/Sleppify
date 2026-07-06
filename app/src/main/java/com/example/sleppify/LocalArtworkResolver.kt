package com.example.sleppify

import android.content.ContentUris
import android.content.Context
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import java.util.concurrent.Executors

/**
 * Resolves album art for local device tracks from each file's OWN embedded picture.
 *
 * The previous approach keyed art by the MediaStore ALBUM_ID
 * (`content://media/external/audio/albumart/<albumId>`), which is resolved at the ALBUM
 * level. That caused two bugs:
 *  - art-less files still received a non-empty album URI and inherited a sibling/colliding
 *    album's cached thumbnail (an unrelated, "wrong" image), and
 *  - files that DO carry embedded art whose album thumbnail MediaStore never indexed
 *    resolved to null and fell back to the generic music-note icon.
 *
 * This reads the file's own APIC/cover bytes via [MediaMetadataRetriever] (works on
 * minSdk 24), so each track shows its real art — or none at all (the music icon) when the
 * file genuinely has no embedded picture. Extraction runs off the main thread and results
 * are memory-cached per videoId, including a negative ("no art") marker so art-less files
 * are probed only once.
 */
object LocalArtworkResolver {

    /** Java-friendly single-method callback for byte[] consumers (player cover, notification). */
    fun interface BytesCallback {
        fun onResult(bytes: ByteArray?)
    }

    /** Sentinel meaning "this file was probed and has no embedded art". */
    private val EMPTY = ByteArray(0)

    private val cache: LruCache<String, ByteArray> = object : LruCache<String, ByteArray>(
        (Runtime.getRuntime().maxMemory() / 16L)
            .coerceIn(4L * 1024 * 1024, 16L * 1024 * 1024)
            .toInt()
    ) {
        override fun sizeOf(key: String, value: ByteArray): Int =
            if (value.isEmpty()) 1 else value.size
    }

    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "local-artwork").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
        }
    }

    /** Drops the in-memory embedded-art cache under system memory pressure; files re-probe on demand. */
    fun trimMemory() {
        cache.evictAll()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @JvmStatic
    fun isLocal(videoId: String?): Boolean = LocalFilesStore.isLocalVideoId(videoId)

    /**
     * Call when binding NON-local artwork into a view that may previously have shown local art
     * (mixed/reused adapters, the mini player). Invalidates the recycle guard so a late
     * background extraction result from a prior local bind cannot overwrite the new content.
     */
    @JvmStatic
    fun detach(imageView: ImageView) {
        imageView.setTag(R.id.tag_local_art, null)
    }

    /** Reconstruct the playable audio content URI directly from a local videoId. */
    private fun contentUriFor(videoId: String?): Uri? {
        if (videoId == null || !videoId.startsWith(LocalFilesStore.LOCAL_VIDEO_ID_PREFIX)) return null
        val id = videoId.substring(LocalFilesStore.LOCAL_VIDEO_ID_PREFIX.length).toLongOrNull() ?: return null
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        return ContentUris.withAppendedId(collection, id)
    }

    /**
     * Loads the track's own embedded art into [imageView]; shows [R.drawable.ic_music] when the
     * file has no embedded picture. Safe to call on every list bind — memory-cached and guarded
     * against view recycling.
     */
    @JvmStatic
    @JvmOverloads
    fun loadInto(imageView: ImageView, videoId: String?, sizePx: Int = 0) {
        if (videoId.isNullOrEmpty()) {
            imageView.setTag(R.id.tag_local_art, null)
            showIcon(imageView)
            return
        }
        imageView.setTag(R.id.tag_local_art, videoId)

        val cached = cache.get(videoId)
        if (cached != null) {
            if (cached.isEmpty()) showIcon(imageView) else bindBytes(imageView, videoId, cached, sizePx)
            return
        }

        // Cache miss: show the icon as a loading state, then extract off the main thread.
        showIcon(imageView)
        val appCtx = imageView.context.applicationContext
        executor.execute {
            val bytes = resolveBytes(appCtx, videoId)
            cache.put(videoId, bytes ?: EMPTY)
            mainHandler.post {
                if (imageView.getTag(R.id.tag_local_art) != videoId) return@post // recycled to another track
                if (bytes == null) showIcon(imageView) else bindBytes(imageView, videoId, bytes, sizePx)
            }
        }
    }

    /**
     * Resolves the track's embedded-art bytes for non-ImageView consumers (the full-player
     * bitmap cover, the media-notification large icon). The callback always fires on the main
     * thread; [BytesCallback.onResult] receives null when there is no embedded art.
     */
    @JvmStatic
    fun loadBytes(context: Context, videoId: String?, callback: BytesCallback) {
        if (videoId.isNullOrEmpty()) {
            callback.onResult(null)
            return
        }
        val cached = cache.get(videoId)
        if (cached != null) {
            callback.onResult(if (cached.isEmpty()) null else cached)
            return
        }
        val appCtx = context.applicationContext
        executor.execute {
            val bytes = resolveBytes(appCtx, videoId)
            cache.put(videoId, bytes ?: EMPTY)
            mainHandler.post { callback.onResult(bytes) }
        }
    }

    private fun bindBytes(imageView: ImageView, videoId: String, bytes: ByteArray, sizePx: Int) {
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        imageView.setBackgroundColor(Color.TRANSPARENT)
        var req = Glide.with(imageView)
            .load(bytes)
            .signature(ObjectKey("localart:$videoId"))
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .placeholder(R.drawable.ic_music)
            .error(R.drawable.ic_music)
            .centerCrop()
        if (sizePx > 0) req = req.override(sizePx, sizePx)
        req.into(imageView)
    }

    private fun showIcon(imageView: ImageView) {
        Glide.with(imageView).clear(imageView)
        imageView.scaleType = ImageView.ScaleType.FIT_CENTER
        imageView.setImageResource(R.drawable.ic_music)
    }

    /**
     * Extracts the file's OWN embedded picture (the ID3 APIC / FLAC PICTURE / MP4 cover atom).
     * MUST be called off the main thread. Returns null when the file has no embedded art.
     *
     * Deliberately does NOT fall back to MediaStore album-level art: that is exactly what
     * bled wrong covers onto art-less tracks. No embedded picture -> no art (music icon).
     */
    @JvmStatic
    fun resolveBytes(context: Context, videoId: String?): ByteArray? {
        val uri = contentUriFor(videoId) ?: return null

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val pic = retriever.embeddedPicture
            if (pic != null && pic.isNotEmpty()) return pic
        } catch (e: Exception) {
            // unreadable file -> no art
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // ignore
            }
        }

        return null
    }
}
