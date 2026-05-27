package com.example.sleppify.utils

import android.graphics.Bitmap
import android.graphics.Color
import android.text.TextUtils
import android.util.Log
import kotlin.math.max
import kotlin.math.min

object YouTubeImageProcessor {
    private const val TAG = "YouTubeImageProcessor"

    /**
     * Minimum decode size (one side) before running [smartCrop]. Tiny bitmaps (e.g. 64×64 list
     * thumbs) make margin detection unreliable so the row art no longer matches the full player.
     */
    private const val MIN_DECODE_PX_FOR_SMART_CROP = 320

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

        var cropTop = 0
        var cropBottom = height
        var cropLeft = 0
        var cropRight = width

        // 1. Detect Top Margin (checking more of the image and using 7 points)
        for (y in 2 until height / 2 step 2) {
            if (!isRowUniform(source, y)) {
                cropTop = maxOf(0, y - 2)
                break
            }
        }

        // 2. Detect Bottom Margin
        for (y in height - 3 downTo height / 2 step 2) {
            if (!isRowUniform(source, y)) {
                cropBottom = minOf(height, y + 2)
                break
            }
        }

        // 3. Detect Left Margin
        for (x in 2 until width / 2 step 2) {
            if (!isColumnUniform(source, x)) {
                cropLeft = maxOf(0, x - 2)
                break
            }
        }

        // 4. Detect Right Margin
        for (x in width - 3 downTo width / 2 step 2) {
            if (!isColumnUniform(source, x)) {
                cropRight = minOf(width, x + 2)
                break
            }
        }

        // Ensure we don't crop into oblivion
        val finalWidth = cropRight - cropLeft
        val finalHeight = cropBottom - cropTop

        if (finalWidth < width / 5 || finalHeight < height / 5) {
            return source
        }

        val minHorizontalMarginPx = max(2, width / 20) // 5% of width
        val leftMargin = cropLeft
        val rightMargin = width - cropRight
        val hasStrongHorizontalMargins = leftMargin >= minHorizontalMarginPx && rightMargin >= minHorizontalMarginPx
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

    private fun isRowUniform(bmp: Bitmap, y: Int): Boolean {
        val w = bmp.width
        // Use 7 points for better coverage
        val points = intArrayOf(w/10, 2*w/10, 4*w/10, 5*w/10, 6*w/10, 8*w/10, 9*w/10)
        val firstColor = bmp.getPixel(points[0], y)
        
        for (i in 1 until points.size) {
            if (!isColorSimilar(firstColor, bmp.getPixel(points[i], y))) return false
        }
        return true
    }

    private fun isColumnUniform(bmp: Bitmap, x: Int): Boolean {
        val h = bmp.height
        val points = intArrayOf(h/10, 2*h/10, 4*h/10, 5*h/10, 6*h/10, 8*h/10, 9*h/10)
        val firstColor = bmp.getPixel(x, points[0])
        
        for (i in 1 until points.size) {
            if (!isColorSimilar(firstColor, bmp.getPixel(x, points[i]))) return false
        }
        return true
    }

    private fun isColorSimilar(c1: Int, c2: Int): Boolean {
        // Reduced threshold for tighter "uniformity" (better margin detection)
        val threshold = 18 
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
