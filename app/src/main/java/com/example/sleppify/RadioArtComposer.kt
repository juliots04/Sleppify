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
 * Composes the radio card artwork into a SINGLE bitmap that is cached in memory AND on disk, keyed
 * by the three source URLs.
 *
 * Spotify-radio style (measured from Spotify's live generated covers, normalized to a square of
 * side S): two faint concentric "sonar" discs (black @ 5% alpha, radii 0.33S / 0.52S centered at
 * 0.50S, 0.45S), then in the lower half a large full-color seed circle (r = 0.2225S at 0.50S,
 * 0.64S) flanked by two smaller GRAYSCALE circles (r = 0.16S at 0.165S / 0.835S) — grayscale is
 * what visually says "radio = this artist + similar artists". Every circle carries a thin
 * off-white halo stroke. The canvas stays TRANSPARENT so the card's fluorescent field (vRadioBg,
 * see [cardBackgroundColor]) shows through; the title and "RADIO" label are TextViews in
 * item_radio_carousel.xml.
 *
 * Why a composite: the carousel previously fired 3–4 separate Glide requests per card on every
 * bind, so scrolling showed the thumbnails streaming in one by one. Now each card's art is built
 * once, persisted as one image, and painted atomically.
 */
object RadioArtComposer {

    // Bumped whenever the composition geometry/style changes so stale cached composites
    // (memory + disk, keyed by signature) are never reused for the new design.
    private const val STYLE_VERSION = "v3-bigger-circles"

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

    /** Drops the in-memory composite cache under system memory pressure; disk cache is untouched. */
    fun trimMemory() {
        memCache.evictAll()
    }

    /**
     * Flat "fluorescent" card field behind the circles — the same punchy family as the liked
     * (#FC5DAE→#8B70F5) and favorites (#FF512F→#F09819) gradients but a single saturated color,
     * no gradient. Derived from the seed's raw Palette color so each radio keeps its own hue.
     * Shared by every surface that paints a radio cover (home carousels, detail header).
     */
    @JvmStatic
    fun cardBackgroundColor(rawColor: Int): Int {
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(rawColor, hsl)
        // Near-grayscale seeds (b/w covers) have no meaningful hue — forcing a bright color (the old
        // violet) looked out of place because nothing in the image was that color. Give them a deep
        // neutral slate that sits behind any artwork instead.
        if (hsl[1] < 0.12f) {
            hsl[0] = 220f
            hsl[1] = 0.16f
            hsl[2] = 0.34f
            return androidx.core.graphics.ColorUtils.HSLToColor(hsl)
        }
        // Keep the seed's OWN hue so the field combines with the central image; vivid but not neon
        // (toned down from the old 0.85–0.98 saturation), and deep enough that the white circles and
        // halo read clearly on top.
        hsl[1] = hsl[1].coerceIn(0.42f, 0.66f)
        hsl[2] = hsl[2].coerceIn(0.40f, 0.52f)
        return androidx.core.graphics.ColorUtils.HSLToColor(hsl)
    }

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
        // Spotify-radio geometry, proportional to the card size. Circles sit at the vertical
        // center now: the badge lives top-right and the seed title bottom-left (both small),
        // so the image row owns the middle band and can be larger.
        val centerR = sizePx * 0.27f
        val sideR = sizePx * 0.19f
        val circleCy = sizePx * 0.50f
        val centerDia = Math.max(160, Math.round(centerR * 2f))
        val sideDia = Math.max(120, Math.round(sideR * 2f))

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

            // Faint concentric "sonar" discs — the tone-on-tone radio-wave motif behind everything.
            discPaint.color = 0x0D000000
            canvas.drawCircle(sizePx * 0.5f, circleCy, sizePx * 0.52f, discPaint)
            canvas.drawCircle(sizePx * 0.5f, circleCy, sizePx * 0.36f, discPaint)

            // Side circles (similar artists) in GRAYSCALE, behind; full-color seed circle on top.
            drawCircle(canvas, left, sizePx * 0.165f, circleCy, sideR, sizePx, grayscale = true)
            drawCircle(canvas, right, sizePx * 0.835f, circleCy, sideR, sizePx, grayscale = true)
            drawCircle(canvas, center, sizePx * 0.5f, circleCy, centerR, sizePx, grayscale = false)
            return out
        } finally {
            for (f in futures) {
                try { Glide.with(ctx).clear(f) } catch (_: Throwable) {}
            }
        }
    }

    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFF4F5F2.toInt() // off-white halo, like the light tint stroke on Spotify's circles
    }

    private val grayscaleFilter = android.graphics.ColorMatrixColorFilter(
        android.graphics.ColorMatrix().apply { setSaturation(0f) }
    )

    private fun drawCircle(
        canvas: Canvas,
        bmp: Bitmap?,
        cx: Float,
        cy: Float,
        r: Float,
        sizePx: Int,
        grayscale: Boolean
    ) {
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
        if (grayscale) paint.colorFilter = grayscaleFilter
        canvas.drawCircle(cx, cy, r, paint)
        // Thin off-white halo ring (~8px at 640, proportional here).
        haloPaint.strokeWidth = Math.max(2f, sizePx * 0.0125f)
        canvas.drawCircle(cx, cy, r - haloPaint.strokeWidth / 2f, haloPaint)
    }

    /** Wipes every composite from disk + memory (used once when the art style version changes). */
    fun clearAllCaches(ctx: Context) {
        val appCtx = ctx.applicationContext
        memCache.evictAll()
        io.execute {
            try { File(appCtx.cacheDir, "radio_art").deleteRecursively() } catch (_: Throwable) {}
        }
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

    private fun signature(vararg parts: Any): String =
        md5(parts.joinToString("|") + "|" + STYLE_VERSION)

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
