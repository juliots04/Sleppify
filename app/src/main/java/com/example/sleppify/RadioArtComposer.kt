package com.example.sleppify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.FutureTarget
import com.example.sleppify.utils.YouTubeCropTransformation
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Composes the radio card artwork (the 3 overlapping circular thumbnails: left, center, right) into
 * a SINGLE bitmap that is cached in memory AND on disk, keyed by the three source URLs.
 *
 * Why: the carousel previously fired 3–4 separate Glide requests per card on every bind, so scrolling
 * showed the thumbnails streaming in one by one. Now each card's art is built once, persisted as one
 * image, and painted atomically — instant on every re-scroll, and warmable ahead of first paint.
 *
 * The composite is drawn on a TRANSPARENT canvas so the card's colored gradient (vRadioBg) still shows
 * through the gaps between circles, exactly like the original layered layout.
 */
object RadioArtComposer {

    private val mainHandler = Handler(Looper.getMainLooper())

    // Small dedicated pool; low priority so it never competes with the UI thread.
    private val io = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "radio-art").apply {
            isDaemon = true
            priority = Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2)
        }
    }

    // Byte-bounded memory cache (~1/12 of the heap, clamped to a sane range). Never recycles evicted
    // bitmaps — an evicted composite may still be attached to a visible ImageView.
    private val memCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 12 / 1024L).toInt().coerceIn(4 * 1024, 24 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val crop = YouTubeCropTransformation()

    /**
     * Loads the composed radio art into [target]. Paints instantly from the memory cache when warm;
     * otherwise builds it off the main thread (disk cache first) and sets it when ready — only if the
     * ImageView hasn't since been recycled to a different radio.
     */
    fun load(
        target: ImageView,
        radioId: String,
        centerUrl: String,
        leftUrl: String,
        rightUrl: String,
        sizePx: Int
    ) {
        if (sizePx <= 0) return
        val ctx = target.context ?: return
        val sig = signature(radioId, centerUrl, leftUrl, rightUrl, sizePx)
        if (sig == target.getTag(R.id.tag_radio_art_sig)) return
        target.setTag(R.id.tag_radio_art_sig, sig)

        memCache.get(sig)?.let { target.setImageBitmap(it); return }
        // Cache miss on a (possibly recycled) card: clear any stale composite so the previous radio's
        // circles don't linger — the vRadioBg gradient shows through until the new composite arrives.
        target.setImageDrawable(null)

        val appCtx = ctx.applicationContext
        io.execute {
            val bmp = getOrBuild(appCtx, sig, centerUrl, leftUrl, rightUrl, sizePx) ?: return@execute
            mainHandler.post {
                // Guard against ViewHolder recycling: only set if this view still wants this radio.
                if (sig == target.getTag(R.id.tag_radio_art_sig)) {
                    target.setImageBitmap(bmp)
                }
            }
        }
    }

    /** Fire-and-forget warm: builds + caches the composite so the first scroll is already instant. */
    fun precompose(
        ctx: Context,
        radioId: String,
        centerUrl: String,
        leftUrl: String,
        rightUrl: String,
        sizePx: Int
    ) {
        if (sizePx <= 0) return
        val sig = signature(radioId, centerUrl, leftUrl, rightUrl, sizePx)
        if (memCache.get(sig) != null) return
        val appCtx = ctx.applicationContext
        io.execute { getOrBuild(appCtx, sig, centerUrl, leftUrl, rightUrl, sizePx) }
    }

    private fun getOrBuild(
        ctx: Context,
        sig: String,
        centerUrl: String,
        leftUrl: String,
        rightUrl: String,
        sizePx: Int
    ): Bitmap? {
        memCache.get(sig)?.let { return it }
        return try {
            val file = diskFile(ctx, sig)
            var bmp: Bitmap? = if (file.exists()) {
                try { BitmapFactory.decodeFile(file.absolutePath) } catch (_: Throwable) { null }
            } else null
            if (bmp == null) {
                bmp = compose(ctx, centerUrl, leftUrl, rightUrl, sizePx)
                if (bmp != null) writeDisk(file, bmp)
            }
            if (bmp != null) memCache.put(sig, bmp)
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    private fun compose(
        ctx: Context,
        centerUrl: String,
        leftUrl: String,
        rightUrl: String,
        sizePx: Int
    ): Bitmap? {
        val density = ctx.resources.displayMetrics.density
        val centerDia = Math.max(160, Math.round(95f * density))
        val sideDia = Math.max(160, Math.round(68f * density))

        val futures = ArrayList<FutureTarget<Bitmap>>()
        fun thumb(url: String, decodePx: Int): Bitmap? {
            if (url.isBlank()) return null
            return try {
                val f = Glide.with(ctx).asBitmap()
                    .load(url.trim())
                    .transform(crop)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .submit(decodePx, decodePx)
                futures.add(f)
                f.get(12, TimeUnit.SECONDS)
            } catch (_: Throwable) {
                null
            }
        }

        try {
            val center = thumb(centerUrl, centerDia)
            val left = thumb(leftUrl, sideDia)
            val right = thumb(rightUrl, sideDia)
            if (center == null && left == null && right == null) return null

            val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val cy = sizePx / 2f
            val centerR = 95f * density / 2f
            val sideR = 68f * density / 2f
            val leftCx = 22f * density
            val rightCx = sizePx - 22f * density
            // Draw order matches the layout's elevation: side circles first, center on top.
            drawCircle(canvas, left, leftCx, cy, sideR)
            drawCircle(canvas, right, rightCx, cy, sideR)
            drawCircle(canvas, center, sizePx / 2f, cy, centerR)
            return out
        } finally {
            for (f in futures) {
                try { Glide.with(ctx).clear(f) } catch (_: Throwable) {}
            }
        }
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x22000000
    }

    private fun drawCircle(canvas: Canvas, bmp: Bitmap?, cx: Float, cy: Float, r: Float) {
        if (bmp == null || bmp.isRecycled) return
        val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        // center-crop the bitmap to cover the circle
        val d = r * 2f
        val scale = d / Math.max(1, Math.min(bmp.width, bmp.height))
        val m = Matrix()
        m.setScale(scale, scale)
        m.postTranslate(cx - bmp.width * scale / 2f, cy - bmp.height * scale / 2f)
        shader.setLocalMatrix(m)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.shader = shader
        canvas.drawCircle(cx, cy, r, paint)
        // Thin dark ring to separate overlapping circles (mimics the original elevation seam).
        ringPaint.strokeWidth = Math.max(1f, r * 0.03f)
        canvas.drawCircle(cx, cy, r - ringPaint.strokeWidth / 2f, ringPaint)
    }

    private fun writeDisk(file: File, bmp: Bitmap) {
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                // PNG keeps the transparency in the gaps between circles.
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (_: Throwable) {
            try { file.delete() } catch (_: Throwable) {}
        }
    }

    private fun diskFile(ctx: Context, sig: String): File =
        File(File(ctx.cacheDir, "radio_art").also { it.mkdirs() }, "$sig.png")

    private fun signature(vararg parts: Any): String = md5(parts.joinToString("|"))

    private fun md5(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) sb.append(String.format("%02x", b))
            sb.toString()
        } catch (_: Throwable) {
            input.hashCode().toString()
        }
    }
}
