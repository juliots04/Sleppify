package com.example.sleppify

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

class EqCurveEditorView : View {
    private val bandValues = FloatArray(BAND_COUNT)
    private val bandX = FloatArray(BAND_COUNT)
    private val bandY = FloatArray(BAND_COUNT)

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pointFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pointStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val modalGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val zeroLinePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val curvePath = Path()
    private val fillPath = Path()

    private var contentLeft = 0f
    private var contentTop = 0f
    private var contentRight = 0f
    private var contentBottom = 0f
    private var handleRadiusPx = 0f

    private var activeBand = -1
    private var editingEnabled = true
    private var handlesVisible = true
    private var gridVisible = true
    private var areaFillEnabled = false
    private var axisDbLabelsEnabled = false
    private var modalBandGuidesEnabled = false
    private var onBandChangeListener: OnBandChangeListener? = null

    private var focusedBand = -1

    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init()
    }

    private fun init() {
        val curveColor = ContextCompat.getColor(context, R.color.stitch_blue_light)
        val gridColor = ContextCompat.getColor(context, R.color.surface_high)

        gridPaint.style = Paint.Style.STROKE
        gridPaint.strokeWidth = dp(1f)
        gridPaint.color = adjustAlpha(gridColor, 0.46f)

        fillPaint.style = Paint.Style.FILL
        fillPaint.color = adjustAlpha(curveColor, 0.24f)

        curvePaint.style = Paint.Style.STROKE
        curvePaint.strokeWidth = dp(4f)
        curvePaint.strokeCap = Paint.Cap.ROUND
        curvePaint.strokeJoin = Paint.Join.ROUND
        curvePaint.color = curveColor

        pointFillPaint.style = Paint.Style.FILL
        pointFillPaint.color = ContextCompat.getColor(context, R.color.surface_dark)

        pointStrokePaint.style = Paint.Style.STROKE
        pointStrokePaint.strokeWidth = dp(2.2f)
        pointStrokePaint.color = curveColor

        axisLabelPaint.style = Paint.Style.FILL
        axisLabelPaint.color = ContextCompat.getColor(context, R.color.text_secondary)
        axisLabelPaint.textSize = dp(7f)

        modalGuidePaint.style = Paint.Style.STROKE
        modalGuidePaint.strokeWidth = dp(2.8f)
        modalGuidePaint.strokeCap = Paint.Cap.ROUND
        modalGuidePaint.color = adjustAlpha(curveColor, 0.42f)

        zeroLinePaint.style = Paint.Style.STROKE
        zeroLinePaint.strokeWidth = dp(1.2f)
        zeroLinePaint.color = adjustAlpha(curveColor, 0.52f)

        handleRadiusPx = dp(6.4f)
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        val horizontalInset = dp(CONTENT_HORIZONTAL_INSET_DP)
        val verticalInset = dp(CONTENT_VERTICAL_INSET_DP)

        contentLeft = paddingLeft + horizontalInset
        contentRight = w - paddingRight - horizontalInset
        contentTop = paddingTop + verticalInset
        contentBottom = h - paddingBottom - verticalInset

        if (contentRight <= contentLeft) {
            contentRight = contentLeft + 1f
        }
        if (contentBottom <= contentTop) {
            contentBottom = contentTop + 1f
        }

        val width = contentRight - contentLeft
        for (i in 0 until BAND_COUNT) {
            val fraction = if (BAND_COUNT == 1) 0f else i.toFloat() / (BAND_COUNT - 1).toFloat()
            bandX[i] = contentLeft + (fraction * width)
        }
        recalculateBandY()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (gridVisible) {
            drawGrid(canvas)
        }
        if (modalBandGuidesEnabled) {
            drawModalBandGuides(canvas)
        }
        drawCurve(canvas)
        if (handlesVisible) {
            drawHandles(canvas)
        }
        if (axisDbLabelsEnabled) {
            drawDbLabels(canvas)
        }
        if (isFocused && focusedBand >= 0) {
            drawFocusIndicator(canvas)
        }
    }

    private fun drawGrid(canvas: Canvas) {
        for (db in DB_GUIDE_VALUES) {
            val y = yForDb(db)
            val paint = if (abs(db) < 0.01f) zeroLinePaint else gridPaint
            canvas.drawLine(contentLeft, y, contentRight, y, paint)
        }

        for (i in 0 until BAND_COUNT) {
            canvas.drawLine(bandX[i], contentTop, bandX[i], contentBottom, gridPaint)
        }

        val cornerRadius = dp(GRID_CORNER_RADIUS_DP)
        canvas.drawRoundRect(contentLeft, contentTop, contentRight, contentBottom, cornerRadius, cornerRadius, gridPaint)
    }

    private fun drawCurve(canvas: Canvas) {
        curvePath.reset()
        fillPath.reset()

        curvePath.moveTo(bandX[0], bandY[0])
        for (i in 0 until (BAND_COUNT - 1)) {
            val p1x = bandX[i]
            val p1y = bandY[i]
            val p2x = bandX[i + 1]
            val p2y = bandY[i + 1]

            val p0x: Float
            val p0y: Float
            if (i == 0) {
                p0x = p1x - (p2x - p1x)
                p0y = p1y - (p2y - p1y)
            } else {
                p0x = bandX[i - 1]
                p0y = bandY[i - 1]
            }

            val p3x: Float
            val p3y: Float
            if (i + 2 >= BAND_COUNT) {
                p3x = p2x + (p2x - p1x)
                p3y = p2y + (p2y - p1y)
            } else {
                p3x = bandX[i + 2]
                p3y = bandY[i + 2]
            }

            val cp1x = p1x + ((p2x - p0x) * CURVE_TENSION / 6f)
            val cp1y = p1y + ((p2y - p0y) * CURVE_TENSION / 6f)
            val cp2x = p2x - ((p3x - p1x) * CURVE_TENSION / 6f)
            val cp2y = p2y - ((p3y - p1y) * CURVE_TENSION / 6f)

            curvePath.cubicTo(cp1x, cp1y, cp2x, cp2y, p2x, p2y)
        }

        if (areaFillEnabled) {
            fillPath.addPath(curvePath)
            fillPath.lineTo(contentRight, contentBottom)
            fillPath.lineTo(contentLeft, contentBottom)
            fillPath.close()
            canvas.drawPath(fillPath, fillPaint)
        }

        canvas.drawPath(curvePath, curvePaint)
    }

    private fun drawDbLabels(canvas: Canvas) {
        val labelX = contentLeft + dp(4f)
        val ascentAbs = abs(axisLabelPaint.ascent())
        val descentAbs = abs(axisLabelPaint.descent())
        val minBaseline = contentTop + ascentAbs
        val maxBaseline = contentBottom - descentAbs

        for (db in DB_GUIDE_VALUES) {
            val y = yForDb(db)
            var baseline = y + ((ascentAbs - descentAbs) / 2f)
            baseline = max(minBaseline, min(maxBaseline, baseline))
            canvas.drawText(formatDbLabel(db), labelX, baseline, axisLabelPaint)
        }
    }

    private fun drawModalBandGuides(canvas: Canvas) {
        val top = contentTop + dp(MODAL_GUIDE_TOP_INSET_DP)
        val bottom = contentBottom - dp(MODAL_GUIDE_BOTTOM_INSET_DP)
        for (i in 0 until BAND_COUNT) {
            val x = bandX[i]
            canvas.drawLine(x, top, x, bottom, modalGuidePaint)
        }
    }

    private fun drawHandles(canvas: Canvas) {
        for (i in 0 until BAND_COUNT) {
            val radius = if (i == activeBand || (isFocused && i == focusedBand)) 
                handleRadiusPx + dp(3.5f) else handleRadiusPx
            canvas.drawCircle(bandX[i], bandY[i], radius, pointFillPaint)
            canvas.drawCircle(bandX[i], bandY[i], radius, pointStrokePaint)
        }
    }

    private fun drawFocusIndicator(canvas: Canvas) {
        val x = bandX[focusedBand]
        val paint = Paint(modalGuidePaint)
        paint.color = adjustAlpha(paint.color, 0.8f)
        paint.strokeWidth = dp(2f)
        canvas.drawLine(x, contentTop, x, contentBottom, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || !editingEnabled) {
            return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeBand = findClosestBandByX(event.x)
                if (activeBand < 0) {
                    return false
                }
                parent.requestDisallowInterceptTouchEvent(true)
                updateActiveBandFromTouch(event.y, true)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (activeBand >= 0) {
                    updateActiveBandFromTouch(event.y, true)
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeBand >= 0) {
                    updateActiveBandFromTouch(event.y, true)
                    activeBand = -1
                    invalidate()
                    return true
                }
            }
        }

        return super.onTouchEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (!isEnabled || !editingEnabled) return super.onKeyDown(keyCode, event)

        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (focusedBand > 0) {
                    focusedBand--
                    invalidate()
                    return true
                }
                return false
            }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (focusedBand < BAND_COUNT - 1) {
                    focusedBand++
                    invalidate()
                    return true
                }
                return false
            }
            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                if (focusedBand >= 0) {
                    val newValue = clampDb(bandValues[focusedBand] + STEP_DB)
                    if (abs(newValue - bandValues[focusedBand]) > 0.001f) {
                        bandValues[focusedBand] = newValue
                        recalculateBandY()
                        onBandChangeListener?.onBandChanged(focusedBand, newValue)
                        invalidate()
                    }
                }
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (focusedBand >= 0) {
                    val newValue = clampDb(bandValues[focusedBand] - STEP_DB)
                    if (abs(newValue - bandValues[focusedBand]) > 0.001f) {
                        bandValues[focusedBand] = newValue
                        recalculateBandY()
                        onBandChangeListener?.onBandChanged(focusedBand, newValue)
                        invalidate()
                    }
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus && focusedBand < 0) {
            focusedBand = 0
        }
        invalidate()
    }

    fun setBandValues(values: FloatArray?) {
        if (values == null) {
            return
        }
        val count = min(values.size, BAND_COUNT)
        for (i in 0 until count) {
            bandValues[i] = clampDb(values[i])
        }
        recalculateBandY()
        invalidate()
    }

    fun getBandValue(bandIndex: Int): Float {
        if (bandIndex < 0 || bandIndex >= BAND_COUNT) {
            return 0f
        }
        return bandValues[bandIndex]
    }

    fun getBandValuesSnapshot(): FloatArray {
        return bandValues.copyOf()
    }

    fun setOnBandChangeListener(listener: OnBandChangeListener?) {
        onBandChangeListener = listener
    }

    fun setEditingEnabled(enabled: Boolean) {
        editingEnabled = enabled
        isClickable = enabled
        isFocusable = enabled
    }

    fun setHandlesVisible(visible: Boolean) {
        handlesVisible = visible
        invalidate()
    }

    fun setGridVisible(visible: Boolean) {
        gridVisible = visible
        invalidate()
    }

    fun setAreaFillEnabled(enabled: Boolean) {
        areaFillEnabled = enabled
        invalidate()
    }

    fun setAxisDbLabelsEnabled(enabled: Boolean) {
        axisDbLabelsEnabled = enabled
        invalidate()
    }

    fun setModalBandGuidesEnabled(enabled: Boolean) {
        modalBandGuidesEnabled = enabled
        invalidate()
    }

    private fun findClosestBandByX(x: Float): Int {
        var closest = -1
        var minDistance = Float.MAX_VALUE

        for (i in 0 until BAND_COUNT) {
            val distance = abs(x - bandX[i])
            if (distance < minDistance) {
                minDistance = distance
                closest = i
            }
        }

        if (closest < 0) return -1

        // Edge bands (first and last): accept any touch within the view width
        if (closest == 0 || closest == BAND_COUNT - 1) {
            if (x >= 0f && x <= width.toFloat()) return closest
        }

        // Inner bands: use a generous acceptance radius (half the spacing between bands)
        val bandSpacing = if (BAND_COUNT > 1) (contentRight - contentLeft) / (BAND_COUNT - 1).toFloat() else dp(MIN_TOUCH_TARGET_DP)
        val acceptRadius = max(dp(MIN_TOUCH_TARGET_DP) / 2f, bandSpacing / 2f)
        return if (minDistance <= acceptRadius) closest else -1
    }

    private fun updateActiveBandFromTouch(touchY: Float, notify: Boolean) {
        if (activeBand < 0 || activeBand >= BAND_COUNT) {
            return
        }

        val clampedY = max(contentTop, min(contentBottom, touchY))
        val height = contentBottom - contentTop
        val normalized = (contentBottom - clampedY) / height
        val rawDb = MIN_DB + (normalized * (MAX_DB - MIN_DB))
        val quantizedDb = clampDb(rawDb)

        if (abs(quantizedDb - bandValues[activeBand]) < 0.001f) {
            return
        }

        bandValues[activeBand] = quantizedDb
        recalculateBandY()

        if (notify) {
            onBandChangeListener?.onBandChanged(activeBand, quantizedDb)
        }
    }

    private fun recalculateBandY() {
        val height = contentBottom - contentTop
        if (height <= 0f) {
            return
        }
        for (i in 0 until BAND_COUNT) {
            bandY[i] = yForDb(bandValues[i])
        }
    }

    private fun yForDb(dbValue: Float): Float {
        val clampedDb = max(MIN_DB, min(MAX_DB, dbValue))
        val height = contentBottom - contentTop
        val normalized = (clampedDb - MIN_DB) / (MAX_DB - MIN_DB)
        return contentBottom - (normalized * height)
    }

    private fun clampDb(value: Float): Float {
        val stepped = round(value / STEP_DB) * STEP_DB
        return max(MIN_DB, min(MAX_DB, stepped))
    }

    private fun formatDbLabel(db: Float): String {
        return if (abs(db - round(db)) < 0.001f) {
            String.format("%.0f", db)
        } else {
            String.format("%.1f", db)
        }
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }

    private fun adjustAlpha(color: Int, alphaFactor: Float): Int {
        val alpha = round(Color.alpha(color) * alphaFactor).toInt()
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    interface OnBandChangeListener {
        fun onBandChanged(bandIndex: Int, value: Float)
    }

    companion object {
        private const val BAND_COUNT = 9
        private const val MIN_DB = AudioEffectsService.EQ_GAIN_MIN_DB
        private const val MAX_DB = AudioEffectsService.EQ_GAIN_MAX_DB
        private const val STEP_DB = 0.5f
        private const val CURVE_TENSION = 0.64f
        private const val CONTENT_HORIZONTAL_INSET_DP = 2f
        private const val CONTENT_VERTICAL_INSET_DP = 2f
        private const val GRID_CORNER_RADIUS_DP = 0f
        private const val MODAL_GUIDE_TOP_INSET_DP = 12f
        private const val MODAL_GUIDE_BOTTOM_INSET_DP = 8f
        private const val MIN_TOUCH_TARGET_DP = 48f

        private val DB_GUIDE_VALUES = floatArrayOf(
            MAX_DB,
            MAX_DB * 0.8f,
            MAX_DB * 0.6f,
            MAX_DB * 0.4f,
            MAX_DB * 0.2f,
            0f,
            MIN_DB * 0.2f,
            MIN_DB * 0.4f,
            MIN_DB * 0.6f,
            MIN_DB * 0.8f,
            MIN_DB
        )
    }
}
