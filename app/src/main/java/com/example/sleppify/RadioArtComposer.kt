package com.example.sleppify

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
 * by the three source URLs + the seed's raw Palette color.
 *
 * Diseño YT Music (réplica de la tarjeta de radio real, iterada con el usuario): fondo con
 * DEGRADADO horizontal del color FUSIONADO de las 3 carátulas (dominante de cada una ponderado,
 * el central pesa más; más claro a la izquierda, más oscuro a la derecha), barras verticales
 * redondeadas EN DOS ROMBOS — onda triangular de dos picos (sube-baja-sube-baja) hasta ~88% del
 * alto en los picos — en un tono acento vivo del mismo matiz, y TRES círculos DEL MISMO tamaño a
 * todo color (sin escala de grises, sin halo blanco) superpuestos en fila — el CENTRAL por
 * encima de los laterales. Sin chip de play, sin insignia "RADIO" ni título dentro de la tarjeta.
 *
 * Why a composite: the carousel previously fired 3–4 separate Glide requests per card on every
 * bind, so scrolling showed the thumbnails streaming in one by one. Now each card's art is built
 * once, persisted as one image, and painted atomically (with a fade the first time it lands).
 */
object RadioArtComposer {

    // Bumped whenever the composition geometry/style changes so stale cached composites
    // (memory + disk, keyed by signature) are never reused for the new design.
    private const val STYLE_VERSION = "v7-ytm-diamonds"

    private val mainHandler = Handler(Looper.getMainLooper())

    // Pool dedicado; low priority so it never competes with the UI thread. 3 hilos: cada compose
    // ya paraleliza sus 3 thumbnails, esto solo evita que una tarjeta fría bloquee a las demás.
    private val io = Executors.newFixedThreadPool(3) { r ->
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

    // Composites a los que les faltó algún thumbnail (fetch fallido/timeout). Antes se cacheaban
    // en disco para SIEMPRE — la causa de tarjetas con "solo 1 de 3" imágenes que nunca sanaban.
    // Ahora un parcial vive solo en memoria y el próximo load() reintenta completarlo en bg.
    private val partialSigs = java.util.Collections.synchronizedSet(HashSet<String>())

    private val crop = YouTubeCropTransformation()

    /** Drops the in-memory composite cache under system memory pressure; disk cache is untouched. */
    fun trimMemory() {
        memCache.evictAll()
    }

    // ===== Paleta derivada del color raw de Palette (darkMuted/muted del thumbnail seed) =====

    private fun hsl(rawColor: Int): FloatArray {
        val out = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(rawColor, out)
        return out
    }

    /**
     * Selector ÚNICO del color raw de una radio desde Palette. El color debe ser el DOMINANTE
     * real de la carátula central y COLORIDO: se usa el dominante si tiene saturación decente,
     * y si es apagado se rescata el matiz con los swatches vibrantes (una carátula amarilla debe
     * dar amarillo, no el gris-oliva que devolvían darkMuted/muted, que desaturan por diseño).
     */
    @JvmStatic
    fun pickRawColor(palette: androidx.palette.graphics.Palette?): Int {
        val fallback = 0xFF333333.toInt()
        palette ?: return fallback
        val dominant = palette.getDominantColor(0)
        if (dominant != 0 && hsl(dominant)[1] >= 0.22f) return dominant
        val candidates = intArrayOf(
            palette.getVibrantColor(0),
            palette.getLightVibrantColor(0),
            palette.getDarkVibrantColor(0),
            dominant,
            palette.getMutedColor(0),
            palette.getDarkMutedColor(0)
        )
        for (c in candidates) if (c != 0) return c
        return fallback
    }

    private fun isGrayish(h: FloatArray) = h[1] < 0.12f

    /** Lado IZQUIERDO (claro) del campo degradado de la tarjeta. */
    @JvmStatic
    fun fieldStartColor(rawColor: Int): Int {
        val h = hsl(rawColor)
        if (isGrayish(h)) { h[0] = 220f; h[1] = 0.10f; h[2] = 0.44f }
        else { h[1] = h[1].coerceIn(0.30f, 0.55f); h[2] = 0.50f }
        return androidx.core.graphics.ColorUtils.HSLToColor(h)
    }

    /** Lado DERECHO (oscuro) del campo degradado de la tarjeta. */
    @JvmStatic
    fun fieldEndColor(rawColor: Int): Int {
        val h = hsl(rawColor)
        if (isGrayish(h)) { h[0] = 220f; h[1] = 0.14f; h[2] = 0.16f }
        else { h[1] = h[1].coerceIn(0.40f, 0.62f); h[2] = 0.20f }
        return androidx.core.graphics.ColorUtils.HSLToColor(h)
    }

    /** Tono acento vivo (mismo matiz) para las barras de onda. */
    @JvmStatic
    fun accentColor(rawColor: Int): Int {
        val h = hsl(rawColor)
        if (isGrayish(h)) { h[0] = 220f; h[1] = 0.10f; h[2] = 0.62f }
        else { h[1] = h[1].coerceIn(0.65f, 0.85f); h[2] = 0.55f }
        return androidx.core.graphics.ColorUtils.HSLToColor(h)
    }

    /**
     * Campo degradado izquierda(claro)→derecha(oscuro) del color dominante — el fondo/placeholder
     * de TODA superficie que pinte una radio (tarjetas del home, filas de biblioteca, header de
     * detalle). Se ve mientras el composite se construye y a través de nada (el composite es
     * opaco), pero mantiene la transición sin salto porque usa exactamente los mismos colores.
     */
    @JvmStatic
    fun fieldDrawable(rawColor: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(fieldStartColor(rawColor), fieldEndColor(rawColor))
        )

    /**
     * Color plano legado (media del campo). Conservado para superficies que aún pintan un color
     * sólido (p.ej. fallbacks); las tarjetas usan [fieldDrawable].
     */
    @JvmStatic
    fun cardBackgroundColor(rawColor: Int): Int =
        androidx.core.graphics.ColorUtils.blendARGB(fieldStartColor(rawColor), fieldEndColor(rawColor), 0.5f)

    /**
     * Loads the composed radio art into [target]. Paints instantly from the memory cache when warm;
     * otherwise builds it off the main thread (disk cache first) and sets it with a fade — only if
     * the ImageView hasn't since been recycled to a different radio. [rawColor] is the persisted
     * Palette color (0 = not yet resolved → neutral palette; re-call with the real color once
     * resolved and the card recomposes/repaints itself).
     */
    @JvmStatic
    fun load(
        target: ImageView,
        radioId: String,
        centerUrl: String,
        leftUrl: String,
        rightUrl: String,
        sizePx: Int,
        rawColor: Int
    ) {
        if (sizePx <= 0) return
        val ctx = target.context ?: return
        val sig = signature(radioId, centerUrl, leftUrl, rightUrl, sizePx, rawColor)
        val appCtx = ctx.applicationContext
        if (sig == target.getTag(R.id.tag_radio_art_sig)) {
            maybeRetryPartial(appCtx, target, sig, radioId, centerUrl, leftUrl, rightUrl, sizePx, rawColor)
            return
        }
        target.setTag(R.id.tag_radio_art_sig, sig)

        memCache.get(sig)?.let {
            target.setImageBitmap(it)
            maybeRetryPartial(appCtx, target, sig, radioId, centerUrl, leftUrl, rightUrl, sizePx, rawColor)
            return
        }
        // Cache miss on a (possibly recycled) card: clear any stale composite so the previous
        // radio's art doesn't linger — the field gradient (fondo del view) shows until it lands.
        target.setImageDrawable(null)

        io.execute {
            val bmp = getOrBuild(appCtx, sig, radioId, centerUrl, leftUrl, rightUrl, sizePx, rawColor) ?: return@execute
            mainHandler.post {
                // Guard against ViewHolder recycling: only set if this view still wants this radio.
                if (sig == target.getTag(R.id.tag_radio_art_sig)) {
                    setWithFade(target, bmp)
                }
            }
        }
    }

    /** Si el composite en pantalla quedó incompleto (faltó un thumbnail), reintenta completarlo
     *  en bg y lo intercambia con fade cuando sana. Sin bucles: el sig sale del set al reintentar
     *  y solo vuelve a entrar si el nuevo intento también quedó parcial. */
    private fun maybeRetryPartial(
        appCtx: Context,
        target: ImageView,
        sig: String,
        radioId: String,
        centerUrl: String,
        leftUrl: String,
        rightUrl: String,
        sizePx: Int,
        rawColor: Int
    ) {
        if (!partialSigs.remove(sig)) return
        io.execute {
            val result = compose(appCtx, radioId, centerUrl, leftUrl, rightUrl, sizePx, rawColor)
            if (result == null) { partialSigs.add(sig); return@execute }
            if (!result.complete) partialSigs.add(sig)
            else writeDisk(diskFile(appCtx, sig), result.bitmap)
            memCache.put(sig, result.bitmap)
            mainHandler.post {
                if (sig == target.getTag(R.id.tag_radio_art_sig)) {
                    setWithFade(target, result.bitmap)
                }
            }
        }
    }

    private fun setWithFade(target: ImageView, bmp: Bitmap) {
        target.setImageBitmap(bmp)
        // Fade del DRAWABLE (imageAlpha), no de la vista: el fondo degradado del view debe seguir
        // visible debajo durante la transición.
        target.imageAlpha = 0
        ObjectAnimator.ofInt(target, "imageAlpha", 0, 255).setDuration(220L).start()
    }

    /** Fire-and-forget warm: builds + caches the composite so the first scroll is already instant. */
    @JvmStatic
    fun precompose(
        ctx: Context,
        radioId: String,
        centerUrl: String,
        leftUrl: String,
        rightUrl: String,
        sizePx: Int,
        rawColor: Int
    ) {
        if (sizePx <= 0) return
        val sig = signature(radioId, centerUrl, leftUrl, rightUrl, sizePx, rawColor)
        if (memCache.get(sig) != null) return
        val appCtx = ctx.applicationContext
        io.execute { getOrBuild(appCtx, sig, radioId, centerUrl, leftUrl, rightUrl, sizePx, rawColor) }
    }

    private fun getOrBuild(
        ctx: Context,
        sig: String,
        radioId: String,
        centerUrl: String,
        leftUrl: String,
        rightUrl: String,
        sizePx: Int,
        rawColor: Int
    ): Bitmap? {
        memCache.get(sig)?.let { return it }
        return try {
            val file = diskFile(ctx, sig)
            var bmp: Bitmap? = if (file.exists()) {
                try { BitmapFactory.decodeFile(file.absolutePath) } catch (_: Throwable) { null }
            } else null
            if (bmp == null) {
                val result = compose(ctx, radioId, centerUrl, leftUrl, rightUrl, sizePx, rawColor)
                if (result != null) {
                    bmp = result.bitmap
                    // Solo los composites COMPLETOS se persisten; un parcial (thumb caído) queda
                    // en memoria y marcado para reintento — nunca "congelado" en disco.
                    if (result.complete) writeDisk(file, result.bitmap) else partialSigs.add(sig)
                }
            }
            if (bmp != null) memCache.put(sig, bmp)
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    private class ComposeResult(val bitmap: Bitmap, val complete: Boolean)

    /** Dominante de una bitmap ya decodificada (Palette síncrono; corre en el pool radio-art,
     *  nunca en el UI thread). null si no se puede extraer. */
    private fun dominantOf(bmp: Bitmap?): Int? {
        if (bmp == null || bmp.isRecycled) return null
        return try {
            val c = pickRawColor(androidx.palette.graphics.Palette.from(bmp).clearFilters().generate())
            if (c == 0) null else c
        } catch (_: Throwable) {
            null
        }
    }

    /** Fusión de los dominantes de las 3 carátulas: media ponderada de canales con el CENTRAL
     *  pesando más (2.4×) que los laterales (1× c/u) — el color de la tarjeta lo marca sobre todo
     *  la carátula central pero recoge un matiz de las laterales. Fallback a [fallback] (el color
     *  ya persistido del centro) si ninguna bitmap da dominante. */
    private fun blendedRawColor(center: Bitmap?, left: Bitmap?, right: Bitmap?, fallback: Int): Int {
        var wr = 0f; var wg = 0f; var wb = 0f; var wsum = 0f
        fun add(bmp: Bitmap?, w: Float) {
            val c = dominantOf(bmp) ?: return
            wr += android.graphics.Color.red(c) * w
            wg += android.graphics.Color.green(c) * w
            wb += android.graphics.Color.blue(c) * w
            wsum += w
        }
        add(center, 2.4f)
        add(left, 1f)
        add(right, 1f)
        if (wsum <= 0f) return fallback
        return android.graphics.Color.rgb(
            (wr / wsum).toInt().coerceIn(0, 255),
            (wg / wsum).toInt().coerceIn(0, 255),
            (wb / wsum).toInt().coerceIn(0, 255)
        )
    }

    private fun compose(
        ctx: Context,
        radioId: String,
        centerUrl: String,
        leftUrl: String,
        rightUrl: String,
        sizePx: Int,
        rawColor: Int
    ): ComposeResult? {
        val s = sizePx.toFloat()
        // Geometría medida de la tarjeta de referencia de YT Music: 3 círculos IGUALES en fila
        // (diámetro ≈ 0.42·S) con solape, centrados verticalmente.
        val circleR = s * 0.21f
        val circleCy = s * 0.50f
        val circleDia = Math.max(140, Math.round(circleR * 2f))

        // Los 3 thumbnails se piden EN PARALELO (antes eran seriales con hasta 12 s de timeout
        // cada uno — la razón de que las tarjetas frías tardaran tanto).
        val futures = ArrayList<FutureTarget<Bitmap>>()
        fun submit(url: String): FutureTarget<Bitmap>? {
            if (url.isBlank()) return null
            return try {
                val f = Glide.with(ctx).asBitmap()
                    .load(url.trim())
                    .transform(crop)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .submit(circleDia, circleDia)
                futures.add(f)
                f
            } catch (_: Throwable) {
                null
            }
        }

        fun await(f: FutureTarget<Bitmap>?): Bitmap? {
            f ?: return null
            return try { f.get(8, TimeUnit.SECONDS) } catch (_: Throwable) { null }
        }

        try {
            val fCenter = submit(centerUrl)
            val fLeft = submit(leftUrl)
            val fRight = submit(rightUrl)
            val center = await(fCenter)
            val left = await(fLeft)
            val right = await(fRight)
            if (center == null && left == null && right == null) return null
            // Completo = cada URL no vacía consiguió su bitmap (una radio con <3 tracks es
            // legítimamente de 1–2 círculos y también cuenta como completa).
            val complete = (centerUrl.isBlank() || center != null) &&
                (leftUrl.isBlank() || left != null) &&
                (rightUrl.isBlank() || right != null)

            // Color EFECTIVO: fusión de los dominantes de las 3 carátulas ya decodificadas (el
            // central pesa más), con fallback al rawColor pasado. Se computa aquí porque las 3
            // bitmaps ya están en mano — sin cargas extra de Glide.
            val effectiveRaw = blendedRawColor(center, left, right, rawColor)

            val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)

            // 1. Campo degradado izquierda (claro) → derecha (oscuro) del color fusionado.
            val field = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, s, 0f,
                    fieldStartColor(effectiveRaw), fieldEndColor(effectiveRaw),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, s, s, field)

            // 2. Barras EN DOS ROMBOS: onda triangular de DOS picos — sube-baja-sube-baja, con
            //    picos a ~1/4 y ~3/4 del ancho (~88% del alto) y valles en bordes y centro
            //    (~12%). Determinista e idéntica en todas las tarjetas (solo cambia el color).
            val accent = accentColor(effectiveRaw)
            val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val barW = s * 0.020f
            val bars = 19
            val step = s / bars
            for (i in 0 until bars) {
                val cx = step * (i + 0.5f)
                val p = (i + 0.5f) / bars // 0..1 a lo ancho de la tarjeta
                // Onda triangular de dos ciclos: pico en fase 0.5, valle en 0 y 1.
                val phase = (p * 2f) % 1f
                val tri = 1f - Math.abs(phase - 0.5f) * 2f // 0 en valles, 1 en picos
                val halfH = s * (0.06f + (0.44f - 0.06f) * tri) // 12% en valles → 88% en picos
                barPaint.color = accent
                barPaint.alpha = (150 + 90 * tri).toInt() // más presentes en los picos
                val rect = RectF(cx - barW / 2f, circleCy - halfH, cx + barW / 2f, circleCy + halfH)
                canvas.drawRoundRect(rect, barW / 2f, barW / 2f, barPaint)
            }

            // 3. Círculos a TODO color, mismo tamaño, con solape: laterales primero y el CENTRAL
            //    por encima. Sin halo/borde.
            drawCircle(canvas, left, s * 0.27f, circleCy, circleR)
            drawCircle(canvas, right, s * 0.73f, circleCy, circleR)
            drawCircle(canvas, center, s * 0.50f, circleCy, circleR)

            return ComposeResult(out, complete)
        } finally {
            for (f in futures) {
                try { Glide.with(ctx).clear(f) } catch (_: Throwable) {}
            }
        }
    }

    private fun drawCircle(
        canvas: Canvas,
        bmp: Bitmap?,
        cx: Float,
        cy: Float,
        r: Float
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
        canvas.drawCircle(cx, cy, r, paint)
    }

    /** Wipes every composite from disk + memory (used once when the art style version changes). */
    fun clearAllCaches(ctx: Context) {
        val appCtx = ctx.applicationContext
        memCache.evictAll()
        partialSigs.clear()
        io.execute {
            try { File(appCtx.cacheDir, "radio_art").deleteRecursively() } catch (_: Throwable) {}
        }
    }

    private fun writeDisk(file: File, bmp: Bitmap) {
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                // El composite ahora es opaco (campo incluido): JPEG-quality PNG no hace falta,
                // pero PNG evita banding en el degradado.
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
