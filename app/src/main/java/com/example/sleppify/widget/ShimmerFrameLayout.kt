package com.example.sleppify.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import android.view.animation.AnimationUtils
import android.widget.FrameLayout

/**
 * Skeleton shimmer container (no external dependency): draws its children into an offscreen layer
 * and sweeps a tilted highlight band across them with SRC_ATOP, so the light only shows over the
 * skeleton blocks — never in the gaps — like YouTube's loading shimmer.
 *
 * Self-animating: the sweep phase derives from the global animation clock, so every instance on
 * screen shimmers in sync with no animator to start/stop. The redraw loop only runs while the view
 * is attached, visible and laid out (dispatchDraw re-posts itself via postInvalidateOnAnimation).
 */
class ShimmerFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val PERIOD_MS = 1300L
        // Band is this fraction of the view width; the sweep travels one band width past each edge.
        private const val BAND_WIDTH_FRACTION = 0.9f
        private const val TILT_DEGREES = 18f
        private const val HIGHLIGHT_CENTER = 0x33FFFFFF
        private const val HIGHLIGHT_EDGE = 0x00FFFFFF
    }

    private val shimmerPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        isDither = true
    }
    private val shaderMatrix = Matrix()
    private var shaderWidth = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0) return
        shaderWidth = w * BAND_WIDTH_FRACTION
        shimmerPaint.shader = LinearGradient(
            0f, 0f, shaderWidth, 0f,
            intArrayOf(HIGHLIGHT_EDGE, HIGHLIGHT_CENTER, HIGHLIGHT_EDGE),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        val w = width
        val h = height
        val shader = shimmerPaint.shader
        if (w <= 0 || h <= 0 || shader == null || !isShown) {
            super.dispatchDraw(canvas)
            return
        }

        val saved = canvas.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), null)
        super.dispatchDraw(canvas)

        val phase = (AnimationUtils.currentAnimationTimeMillis() % PERIOD_MS) / PERIOD_MS.toFloat()
        // Travel from fully off the left edge to fully off the right edge.
        val dx = -shaderWidth + phase * (w + 2f * shaderWidth)
        shaderMatrix.setRotate(TILT_DEGREES, shaderWidth / 2f, h / 2f)
        shaderMatrix.postTranslate(dx, 0f)
        shader.setLocalMatrix(shaderMatrix)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shimmerPaint)

        canvas.restoreToCount(saved)
        postInvalidateOnAnimation()
    }
}
