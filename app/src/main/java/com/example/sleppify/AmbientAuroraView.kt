package com.example.sleppify

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * Vista de fondo ambiental animada con orbes de color suaves y difuminados sobre fondo oscuro.
 * Proporciona un efecto aurora cinemático, moderno y minimalista con mínimo consumo de CPU.
 */
class AmbientAuroraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }

    private data class AuroraOrb(
        val colorHex: Int,
        val relX: Float,
        val relY: Float,
        val relRadius: Float,
        val speedX: Float,
        val speedY: Float,
        val ampX: Float,
        val ampY: Float,
        val phase: Float
    )

    // Paleta de orbes difuminados elegantes: violeta real, fucsia, magenta y azul eléctrico
    private val orbs = listOf(
        AuroraOrb(Color.parseColor("#4F46E5"), 0.32f, 0.28f, 0.58f, 0.70f, 0.55f, 0.16f, 0.14f, 0.0f),
        AuroraOrb(Color.parseColor("#9333EA"), 0.68f, 0.38f, 0.62f, 0.60f, 0.85f, 0.15f, 0.18f, 1.8f),
        AuroraOrb(Color.parseColor("#DB2777"), 0.38f, 0.62f, 0.52f, 0.55f, 0.65f, 0.13f, 0.15f, 3.2f),
        AuroraOrb(Color.parseColor("#0284C7"), 0.68f, 0.72f, 0.48f, 0.80f, 0.50f, 0.18f, 0.12f, 4.5f)
    )

    private var animator: ValueAnimator? = null
    private var timeSeconds: Float = 0f

    init {
        // En Android 12+ aplicamos un RenderEffect de desenfoque gaussiano suave
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                setRenderEffect(RenderEffect.createBlurEffect(130f, 130f, Shader.TileMode.CLAMP))
            } catch (_: Throwable) {
                // Fallback seguro a RadialGradient nativo
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            startAnimation()
        } else {
            stopAnimation()
        }
    }

    private fun startAnimation() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1000f).apply {
            duration = 120_000L // Ciclo de 2 minutos ultra suave
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                timeSeconds = it.animatedFraction * 120f
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Base negra pura AMOLED
        canvas.drawColor(Color.BLACK)

        // 2. Dibujar cada orbe aurora con gradiente radial suave
        val baseDim = maxOf(w, h)
        for (orb in orbs) {
            val cx = (orb.relX + orb.ampX * sin(timeSeconds * orb.speedX + orb.phase)) * w
            val cy = (orb.relY + orb.ampY * cos(timeSeconds * orb.speedY + orb.phase)) * h
            val radius = orb.relRadius * baseDim

            val r = Color.red(orb.colorHex)
            val g = Color.green(orb.colorHex)
            val b = Color.blue(orb.colorHex)

            val shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(
                    Color.argb(95, r, g, b),
                    Color.argb(50, r, g, b),
                    Color.argb(15, r, g, b),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.40f, 0.75f, 1.0f),
                Shader.TileMode.CLAMP
            )
            orbPaint.shader = shader
            canvas.drawCircle(cx, cy, radius, orbPaint)
        }
    }
}
