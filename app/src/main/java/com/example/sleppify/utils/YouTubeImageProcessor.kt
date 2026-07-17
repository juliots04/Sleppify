package com.example.sleppify.utils

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlin.math.max

object YouTubeImageProcessor {
    private const val TAG = "YouTubeImageProcessor"

    /**
     * Minimum decode size (one side) before running [smartCrop]. Tiny bitmaps (e.g. 64×64 list
     * thumbs) make margin detection unreliable so the row art no longer matches the full player.
     */
    private const val MIN_DECODE_PX_FOR_SMART_CROP = 320

    /** Reduced threshold for tighter "uniformity" (better margin detection). */
    private const val COLOR_SIMILARITY_THRESHOLD = 18

    /**
     * Safety cap on the pixel buffer. A decoded bitmap larger than this (4M px ≈ a 2000² image,
     * 16 MB int[]) is never produced by our Glide overrides, so if one shows up we skip the crop
     * rather than allocate a huge transient array. Keeps [smartCrop] memory bounded.
     */
    private const val MAX_SCAN_PIXELS = 4_000_000

    /**
     * Glide [.override] width/height for a view that will display at [displayPx] on one axis,
     * when [YouTubeCropTransformation] / [smartCrop] will run. Keeps bucketing for cache hits but
     * never decodes smaller than [MIN_DECODE_PX_FOR_SMART_CROP] for consistent crops.
     */
    @JvmStatic
    fun decodeDimensionForSmartCrop(displayPx: Int): Int {
        val safe = max(1, displayPx)
        val bucketed = max(64, ((safe + 63) / 64) * 64)
        return max(bucketed, MIN_DECODE_PX_FOR_SMART_CROP)
    }

    @JvmStatic
    fun smartCrop(source: Bitmap?): Bitmap? {
        if (source == null) return null

        val width = source.width
        val height = source.height
        if (width < 50 || height < 50) return source
        if (width.toLong() * height > MAX_SCAN_PIXELS) return source

        // Las fuentes CUADRADAS (carátulas lh3/googleusercontent) ya son el arte final — nunca se
        // recortan. Este recorte existe solo para quitar el letterbox de los frames de video
        // (16:9 / 4:3 de i.ytimg). Escanear dentro de un cuadrado convertía carátulas de fondo
        // plano (p.ej. arte blanco) en un zoom del centro: el detector de márgenes confundía el
        // fondo de la propia carátula con bandas, y la fila se veía recortada mientras el player
        // (que pinta la imagen completa) la mostraba bien.
        if (height in (width * 9 / 10)..(width * 11 / 10)) return source

        // Read every pixel we'll inspect in ONE native call. The previous implementation probed
        // margins with thousands of individual Bitmap.getPixel() calls — each a JNI hop that
        // locks the pixel buffer — which was the dominant CPU cost while scrolling and starved
        // the UI thread. A single getPixels() (a bulk memcpy) plus a pure-Kotlin scan is 10–50x
        // cheaper and produces byte-identical crops.
        val pixels: IntArray = try {
            IntArray(width * height).also { source.getPixels(it, 0, width, 0, 0, width, height) }
        } catch (e: Exception) {
            Log.e(TAG, "smartCrop: getPixels failed", e)
            return source
        }

        // Sample offsets are identical to the original 7-point probes (10/20/40/50/60/80/90%).
        val xPoints = samplePoints(width)
        val yPoints = samplePoints(height)

        var cropTop = 0
        var cropBottom = height
        var cropLeft = 0
        var cropRight = width

        // 1. Detect Top Margin (checking more of the image and using 7 points)
        var yTop = 2
        while (yTop < height / 2) {
            if (!isRowUniform(pixels, width, yTop, xPoints)) {
                cropTop = max(0, yTop - 2)
                break
            }
            yTop += 2
        }

        // 2. Detect Bottom Margin
        var yBottom = height - 3
        while (yBottom >= height / 2) {
            if (!isRowUniform(pixels, width, yBottom, xPoints)) {
                cropBottom = minOf(height, yBottom + 2)
                break
            }
            yBottom -= 2
        }

        // 3. Detect Left Margin
        var xLeft = 2
        while (xLeft < width / 2) {
            if (!isColumnUniform(pixels, width, xLeft, yPoints)) {
                cropLeft = max(0, xLeft - 2)
                break
            }
            xLeft += 2
        }

        // 4. Detect Right Margin
        var xRight = width - 3
        while (xRight >= width / 2) {
            if (!isColumnUniform(pixels, width, xRight, yPoints)) {
                cropRight = minOf(width, xRight + 2)
                break
            }
            xRight -= 2
        }

        // Ensure we don't crop into oblivion
        val finalWidth = cropRight - cropLeft
        val finalHeight = cropBottom - cropTop

        if (finalWidth < width / 5 || finalHeight < height / 5) {
            return source
        }

        // Un bar de letterbox REAL es geométrico: fino (las bandas de YT son 12.5% o ~21.9% por
        // lado — nunca más de ~30%) y SIMÉTRICO (arriba≈abajo, izquierda≈derecha). Márgenes
        // gruesos o asimétricos son fondo de la propia imagen (arte con cielo, fondos planos):
        // en ese caso el eje NO se recorta, en vez de comerse parte del arte.
        val topMargin = cropTop
        val bottomMargin = height - cropBottom
        val maxVerticalBar = height * 3 / 10
        val verticalSymmetric = Math.abs(topMargin - bottomMargin) <= max(4, max(topMargin, bottomMargin) / 3)
        if (topMargin <= 0 || bottomMargin <= 0 || topMargin > maxVerticalBar ||
            bottomMargin > maxVerticalBar || !verticalSymmetric
        ) {
            cropTop = 0
            cropBottom = height
        }

        val minHorizontalMarginPx = max(2, width / 20) // 5% of width
        val leftMargin = cropLeft
        val rightMargin = width - cropRight
        val maxHorizontalBar = width * 3 / 10
        val horizontalSymmetric = Math.abs(leftMargin - rightMargin) <= max(4, max(leftMargin, rightMargin) / 3)
        val hasStrongHorizontalMargins = leftMargin >= minHorizontalMarginPx && rightMargin >= minHorizontalMarginPx &&
            leftMargin <= maxHorizontalBar && rightMargin <= maxHorizontalBar && horizontalSymmetric
        if (!hasStrongHorizontalMargins) {
            cropLeft = 0
            cropRight = width
        }

        return try {
            val safeWidth = cropRight - cropLeft
            val safeHeight = cropBottom - cropTop
            if (safeWidth <= 0 || safeHeight <= 0) {
                return source
            }
            Bitmap.createBitmap(source, cropLeft, cropTop, safeWidth, safeHeight)
        } catch (e: Exception) {
            Log.e(TAG, "smartCrop: failed", e)
            source
        }
    }

    /** The 7 probe offsets along an axis of length [size] (10/20/40/50/60/80/90%). */
    private fun samplePoints(size: Int): IntArray = intArrayOf(
        size / 10, 2 * size / 10, 4 * size / 10, 5 * size / 10, 6 * size / 10, 8 * size / 10, 9 * size / 10
    )

    /** Reads row [y] at the precomputed x-probes straight from the [pixels] buffer (no JNI). */
    private fun isRowUniform(pixels: IntArray, width: Int, y: Int, xPoints: IntArray): Boolean {
        val rowBase = y * width
        val firstColor = pixels[rowBase + xPoints[0]]
        for (i in 1 until xPoints.size) {
            if (!isColorSimilar(firstColor, pixels[rowBase + xPoints[i]])) return false
        }
        return true
    }

    /** Reads column [x] at the precomputed y-probes straight from the [pixels] buffer (no JNI). */
    private fun isColumnUniform(pixels: IntArray, width: Int, x: Int, yPoints: IntArray): Boolean {
        val firstColor = pixels[yPoints[0] * width + x]
        for (i in 1 until yPoints.size) {
            if (!isColorSimilar(firstColor, pixels[yPoints[i] * width + x])) return false
        }
        return true
    }

    private fun isColorSimilar(c1: Int, c2: Int): Boolean {
        val threshold = COLOR_SIMILARITY_THRESHOLD
        return Math.abs(Color.red(c1) - Color.red(c2)) <= threshold &&
               Math.abs(Color.green(c1) - Color.green(c2)) <= threshold &&
               Math.abs(Color.blue(c1) - Color.blue(c2)) <= threshold
    }

    @JvmStatic
    fun shouldProcess(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        val lower = url.lowercase()
        return lower.contains("ytimg.com") ||
               lower.contains("googleusercontent.com") ||
               lower.contains("youtube.com/vi/")
    }
}
