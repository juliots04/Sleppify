package com.example.sleppify

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

/**
 * Custom animated equalizer view drawing three vertical bars.
 *
 * Uses Choreographer-aligned [postInvalidateOnAnimation] instead of [android.animation.ValueAnimator]
 * to guarantee robust playback across RecyclerView scrolling, recycling, view detach/attach,
 * fragment transitions, and devices where system animator scale is disabled (0x).
 */
class AnimatedEqualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barHeights = floatArrayOf(0.2f, 0.2f, 0.2f)
    private val targetHeights = floatArrayOf(0.35f, 0.65f, 0.45f)

    // Keyframe pattern for natural equalizer bounce
    private val keyframes = floatArrayOf(0.24f, 0.52f, 0.32f, 0.62f, 0.40f, 0.22f, 0.56f, 0.34f)
    private val phaseIndex = intArrayOf(0, 3, 5) // Staggered start phases per bar

    private var animating = false
    private var barColor = -0x1
    private var lastFrameTime = 0L

    init {
        attrs?.let {
            val a = context.obtainStyledAttributes(it, R.styleable.AnimatedEqualizerView)
            barColor = a.getColor(R.styleable.AnimatedEqualizerView_barColor, -0x1)
            a.recycle()
        }
        paint.color = barColor
        paint.style = Paint.Style.FILL
    }

    fun setBarColor(color: Int) {
        this.barColor = color
        paint.color = color
        invalidate()
    }

    fun setAnimating(animating: Boolean) {
        this.animating = animating
        if (animating) {
            lastFrameTime = SystemClock.uptimeMillis()
            postInvalidateOnAnimation()
        } else {
            // Reset to rest height
            for (i in 0..2) {
                barHeights[i] = 0.2f
                targetHeights[i] = 0.2f
                phaseIndex[i] = i * 3
            }
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animating) {
            lastFrameTime = SystemClock.uptimeMillis()
            postInvalidateOnAnimation()
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && animating) {
            lastFrameTime = SystemClock.uptimeMillis()
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (animating) {
            val now = SystemClock.uptimeMillis()
            val dt = if (lastFrameTime > 0) (now - lastFrameTime).coerceIn(8L, 40L) else 16L
            lastFrameTime = now

            // Smooth tempo: ~0.0011 per ms produces relaxed, fluid equalizer movement
            val speed = 0.0011f * dt
            for (i in 0..2) {
                if (Math.abs(barHeights[i] - targetHeights[i]) < 0.035f) {
                    phaseIndex[i] = (phaseIndex[i] + 1) % keyframes.size
                    targetHeights[i] = keyframes[phaseIndex[i]]
                }
                if (barHeights[i] < targetHeights[i]) {
                    barHeights[i] = Math.min(barHeights[i] + speed, targetHeights[i])
                } else {
                    barHeights[i] = Math.max(barHeights[i] - speed, targetHeights[i])
                }
            }
        }

        val width = width.toFloat()
        val height = height.toFloat()
        val barWidth = width / 5f
        val spacing = width / 10f

        for (i in 0..2) {
            val barHeight = height * barHeights[i]
            val left = spacing + i * (barWidth + spacing)
            val top = height - barHeight
            val right = left + barWidth
            val bottom = height

            canvas.drawRect(left, top, right, bottom, paint)
        }

        if (animating) {
            postInvalidateOnAnimation()
        }
    }
}
