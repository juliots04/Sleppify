package com.example.sleppify

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class AnimatedEqualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barHeights = floatArrayOf(0.2f, 0.2f, 0.2f)
    private val targetHeights = floatArrayOf(0.2f, 0.2f, 0.2f)

    // Deterministic keyframe pattern — same table for all bars, offset by phase
    private val keyframes = floatArrayOf(0.3f, 0.75f, 0.45f, 0.9f, 0.55f, 0.35f, 0.8f, 0.5f)
    private val phaseIndex = intArrayOf(0, 3, 5) // staggered start phases per bar

    private var animating = false
    private var barColor = -0x1

    private var frameCounter = 0

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 400L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            frameCounter++
            if (frameCounter % 4 != 0) return@addUpdateListener
            for (i in 0..2) {
                if (Math.abs(barHeights[i] - targetHeights[i]) < 0.02f) {
                    phaseIndex[i] = (phaseIndex[i] + 1) % keyframes.size
                    targetHeights[i] = keyframes[phaseIndex[i]]
                }
                if (barHeights[i] < targetHeights[i]) {
                    barHeights[i] += 0.015f
                } else {
                    barHeights[i] -= 0.015f
                }
            }
            invalidate()
        }
    }

    init {
        attrs?.let {
            val a = context.obtainStyledAttributes(it, R.styleable.AnimatedEqualizerView)
            barColor = a.getColor(R.styleable.AnimatedEqualizerView_barColor, -0x1)
            a.recycle()
        }
        paint.color = barColor
        paint.style = Paint.Style.FILL
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setBarColor(color: Int) {
        this.barColor = color
        paint.color = color
        invalidate()
    }

    fun setAnimating(animating: Boolean) {
        if (this.animating == animating) return
        this.animating = animating
        if (animating) {
            animator.start()
        } else {
            animator.cancel()
            // Reset to low height
            for (i in 0..2) {
                barHeights[i] = 0.2f
                targetHeights[i] = 0.2f
                phaseIndex[i] = i * 3 // reset staggered phases
            }
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // onDetachedFromWindow cancels the animator but keeps `animating` true, so a
        // recycled/re-attached row would otherwise stay frozen: setAnimating(true) no-ops.
        if (animating && !animator.isStarted) {
            animator.start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

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
    }
}
