package com.example.sleppify

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
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
 * Diseño "Frost" de la tarjeta de radio (elegido con el usuario, reemplaza por completo el diseño
 * anterior de 3 círculos + barras + degradado de color): UNA sola carátula central a sangre completa
 * + un panel inferior de VIDRIO ESMERILADO — un smear difuminado de la propia carátula bajo un
 * degradado oscuro — con la etiqueta "RADIO" y el título encima. Sin círculos laterales, sin barras,
 * sin campo de color: nada de eso queda.
 *
 * Todo se compone en UNA bitmap cacheada (memoria + disco, firmada por url/título/tamaño) para que
 * el carrusel no dispare Glide en cada bind. En tamaños pequeños (filas/thumbs) NO se dibuja el panel
 * — solo la carátula — porque el texto sería ilegible; el panel aparece solo en tarjetas grandes.
 */
object RadioArtComposer {

    // Se sube cuando cambia la geometría/estilo para que ningún composite viejo (memoria/disco) se
    // reutilice con el diseño nuevo.
    private const val STYLE_VERSION = "v9-frost"

    // Por debajo de este tamaño (px) NO se pinta el panel esmerilado: solo la carátula. Bajo para
    // que las filas de biblioteca / detalle (thumbs ~48-50dp) también muestren el panel.
    private const val LABEL_MIN_PX = 130

    private val mainHandler = Handler(Looper.getMainLooper())

    private val io = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "radio-art").apply {
            isDaemon = true
            priority = Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2)
        }
    }

    // Caché en memoria acotada por bytes (~1/12 del heap). Nunca recicla los evictados: uno evictado
    // puede seguir attachado a un ImageView visible.
    private val memCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 12 / 1024L).toInt().coerceIn(4 * 1024, 24 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    // Composites incompletos (falló el fetch de la carátula): viven solo en memoria y el próximo
    // load() reintenta completarlos, en vez de congelar un fallo en disco.
    private val partialSigs = java.util.Collections.synchronizedSet(HashSet<String>())

    private val crop = YouTubeCropTransformation()

    /** Suelta la caché en memoria bajo presión del sistema; el disco no se toca. */
    fun trimMemory() {
        memCache.evictAll()
    }

    /**
     * Pinta el arte Frost en [target]. Instantáneo desde memoria si está caliente; si no, se compone
     * fuera del hilo principal (disco primero) y entra con fade — solo si el ImageView no se recicló
     * a otra radio. [showLabel] pinta el panel + "RADIO" + [title] (solo se aplica si el tamaño es de
     * tarjeta; en thumbs pequeños se ignora y queda solo la carátula).
     */
    @JvmStatic
    @JvmOverloads
    fun load(
        target: ImageView,
        radioId: String,
        centerUrl: String,
        title: String,
        sizePx: Int,
        showLabel: Boolean = true
    ) {
        if (sizePx <= 0) return
        val ctx = target.context ?: return
        val appCtx = ctx.applicationContext
        val labeled = showLabel && sizePx >= LABEL_MIN_PX
        val sig = signature(radioId, centerUrl, title, sizePx, labeled)
        if (sig == target.getTag(R.id.tag_radio_art_sig)) {
            maybeRetryPartial(appCtx, target, sig, centerUrl, title, sizePx, labeled)
            return
        }
        target.setTag(R.id.tag_radio_art_sig, sig)

        memCache.get(sig)?.let {
            target.setImageBitmap(it)
            maybeRetryPartial(appCtx, target, sig, centerUrl, title, sizePx, labeled)
            return
        }
        // Cache miss en una tarjeta (posiblemente reciclada): limpia el composite previo para que no
        // quede el arte de otra radio; el fondo neutro del view se ve hasta que aterrice.
        target.setImageDrawable(null)

        io.execute {
            val bmp = getOrBuild(appCtx, sig, centerUrl, title, sizePx, labeled) ?: return@execute
            mainHandler.post {
                if (sig == target.getTag(R.id.tag_radio_art_sig)) setWithFade(target, bmp)
            }
        }
    }

    /** Reintenta completar en bg un composite que quedó incompleto (falló la carátula) y lo cambia
     *  con fade al sanar. Sin bucles: el sig sale del set al reintentar y solo vuelve si falla otra vez. */
    private fun maybeRetryPartial(
        appCtx: Context,
        target: ImageView,
        sig: String,
        centerUrl: String,
        title: String,
        sizePx: Int,
        labeled: Boolean
    ) {
        if (!partialSigs.remove(sig)) return
        io.execute {
            val result = compose(appCtx, centerUrl, title, sizePx, labeled)
            if (result == null) { partialSigs.add(sig); return@execute }
            if (!result.complete) partialSigs.add(sig)
            else writeDisk(diskFile(appCtx, sig), result.bitmap)
            memCache.put(sig, result.bitmap)
            mainHandler.post {
                if (sig == target.getTag(R.id.tag_radio_art_sig)) setWithFade(target, result.bitmap)
            }
        }
    }

    private fun setWithFade(target: ImageView, bmp: Bitmap) {
        target.setImageBitmap(bmp)
        target.imageAlpha = 0
        ObjectAnimator.ofInt(target, "imageAlpha", 0, 255).setDuration(220L).start()
    }

    /** Fire-and-forget: construye + cachea el composite para que el primer scroll ya sea instantáneo. */
    @JvmStatic
    @JvmOverloads
    fun precompose(
        ctx: Context,
        radioId: String,
        centerUrl: String,
        title: String,
        sizePx: Int,
        showLabel: Boolean = true
    ) {
        if (sizePx <= 0) return
        val labeled = showLabel && sizePx >= LABEL_MIN_PX
        val sig = signature(radioId, centerUrl, title, sizePx, labeled)
        if (memCache.get(sig) != null) return
        val appCtx = ctx.applicationContext
        io.execute { getOrBuild(appCtx, sig, centerUrl, title, sizePx, labeled) }
    }

    private fun getOrBuild(
        ctx: Context,
        sig: String,
        centerUrl: String,
        title: String,
        sizePx: Int,
        labeled: Boolean
    ): Bitmap? {
        memCache.get(sig)?.let { return it }
        return try {
            val file = diskFile(ctx, sig)
            var bmp: Bitmap? = if (file.exists()) {
                try { BitmapFactory.decodeFile(file.absolutePath) } catch (_: Throwable) { null }
            } else null
            if (bmp == null) {
                val result = compose(ctx, centerUrl, title, sizePx, labeled)
                if (result != null) {
                    bmp = result.bitmap
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

    private fun compose(
        ctx: Context,
        centerUrl: String,
        title: String,
        sizePx: Int,
        labeled: Boolean
    ): ComposeResult? {
        val s = sizePx.toFloat()
        // UNA sola carátula (un único Glide request), cropeada a cuadrado del tamaño de la tarjeta.
        var future: FutureTarget<Bitmap>? = null
        try {
            var cover: Bitmap? = null
            if (centerUrl.isNotBlank()) {
                future = try {
                    Glide.with(ctx).asBitmap()
                        .load(centerUrl.trim())
                        .transform(crop)
                        .format(DecodeFormat.PREFER_RGB_565)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .submit(sizePx, sizePx)
                } catch (_: Throwable) { null }
                cover = try { future?.get(8, TimeUnit.SECONDS) } catch (_: Throwable) { null }
            }
            if (cover == null) return null

            val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)

            // 1. Carátula a sangre completa (center-crop al cuadrado).
            drawCoverCropped(canvas, cover, RectF(0f, 0f, s, s))

            // 2. Panel de vidrio esmerilado + texto (solo en tarjetas grandes).
            if (labeled) drawFrostPanel(canvas, cover, s, title)

            return ComposeResult(out, true)
        } finally {
            if (future != null) {
                try { Glide.with(ctx).clear(future) } catch (_: Throwable) {}
            }
        }
    }

    /** Dibuja [bmp] llenando [rect] con center-crop (cubre sin deformar). */
    private fun drawCoverCropped(canvas: Canvas, bmp: Bitmap, rect: RectF) {
        val bw = bmp.width
        val bh = bmp.height
        if (bw <= 0 || bh <= 0) return
        val scale = Math.max(rect.width() / bw, rect.height() / bh)
        val dw = bw * scale
        val dh = bh * scale
        val m = Matrix()
        m.setScale(scale, scale)
        m.postTranslate(rect.centerX() - dw / 2f, rect.centerY() - dh / 2f)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.save()
        canvas.clipRect(rect)
        canvas.drawBitmap(bmp, m, paint)
        canvas.restore()
    }

    /** Panel inferior estilo glassmorphism: smear difuminado de la carátula + degradado oscuro +
     *  hairline superior + "RADIO" y el título. */
    private fun drawFrostPanel(canvas: Canvas, cover: Bitmap, s: Float, title: String) {
        // Panel más bajo (ocupa ~28% del alto, no ~36%): el blur empieza más abajo.
        val panelTop = s * 0.72f
        val panelRect = RectF(0f, panelTop, s, s)

        // 2a. "Vidrio": un downscale fuerte de la carátula reescalado a todo el cuadrado y recortado
        //     al panel — un smear suave de los colores de la portada (blur barato y determinista).
        val small = try {
            Bitmap.createScaledBitmap(
                cover,
                Math.max(1, cover.width / 16),
                Math.max(1, cover.height / 16),
                true
            )
        } catch (_: Throwable) { null }
        if (small != null) {
            canvas.save()
            canvas.clipRect(panelRect)
            canvas.drawBitmap(small, null, RectF(0f, 0f, s, s), Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
            if (small != cover) small.recycle()
        }

        // 2b. Degradado oscuro para legibilidad (arriba translúcido → abajo casi opaco).
        val darken = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, panelTop, 0f, s,
                0x40101014, 0xF0090909.toInt(),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(panelRect, darken)

        // 2c. Hairline superior (borde de vidrio).
        val hair = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x22FFFFFF
            strokeWidth = Math.max(1f, s * 0.004f)
        }
        canvas.drawLine(0f, panelTop, s, panelTop, hair)

        // 2d. Texto: kicker "RADIO" + título (elipsado al ancho).
        val padX = s * 0.06f
        val kicker = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCFFFFFF.toInt()
            textSize = s * 0.05f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.18f
        }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = s * 0.086f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setShadowLayer(s * 0.02f, 0f, s * 0.004f, 0x66000000)
        }
        val avail = s - 2f * padX
        canvas.drawText("RADIO", padX, s - s * 0.135f, kicker)
        val t = title.trim().ifEmpty { "Radio" }
        val shown = TextUtils.ellipsize(t, titlePaint, avail, TextUtils.TruncateAt.END).toString()
        canvas.drawText(shown, padX, s - s * 0.05f, titlePaint)
    }

    /** Borra todos los composites de disco + memoria (usado una vez al cambiar de versión de estilo). */
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
