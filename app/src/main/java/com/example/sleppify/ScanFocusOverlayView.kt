package com.example.sleppify

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class ScanFocusOverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val rect = RectF()

    private var dimAlpha = 0f

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 0f
    }

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF000000.toInt()
    }

    fun setRect(r: RectF) {
        rect.set(r)
        invalidate()
    }

    fun clearRect() {
        rect.setEmpty()
        dimAlpha = 0f
        invalidate()
    }

    fun setDimAlpha(a: Float) {
        dimAlpha = a.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rect.isEmpty) return

        val w = width.toFloat()
        val h = height.toFloat()

        val dim = (dimAlpha * 170f).toInt().coerceIn(0, 255)
        dimPaint.alpha = dim

        canvas.drawRect(0f, 0f, w, rect.top, dimPaint)
        canvas.drawRect(0f, rect.bottom, w, h, dimPaint)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, dimPaint)
        canvas.drawRect(rect.right, rect.top, w, rect.bottom, dimPaint)

        val cornerRadius = 14f * resources.displayMetrics.density
        val stroke = 1.25f * resources.displayMetrics.density
        strokePaint.strokeWidth = stroke

        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)

        val cornerLen = 20f * resources.displayMetrics.density
        val halfStroke = stroke / 2f
        val l = rect.left + halfStroke
        val t = rect.top + halfStroke
        val r = rect.right - halfStroke
        val b = rect.bottom - halfStroke

        canvas.drawLine(l, t, l + cornerLen, t, strokePaint)
        canvas.drawLine(l, t, l, t + cornerLen, strokePaint)

        canvas.drawLine(r, t, r - cornerLen, t, strokePaint)
        canvas.drawLine(r, t, r, t + cornerLen, strokePaint)

        canvas.drawLine(l, b, l + cornerLen, b, strokePaint)
        canvas.drawLine(l, b, l, b - cornerLen, strokePaint)

        canvas.drawLine(r, b, r - cornerLen, b, strokePaint)
        canvas.drawLine(r, b, r, b - cornerLen, strokePaint)
    }
}
