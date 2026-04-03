package com.micklab.face2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val targetStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.overlay_target)
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }

    private val targetFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.overlay_target)
        style = Paint.Style.FILL
        alpha = 96
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.overlay_target_progress)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(4f)
    }

    private val rawPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.overlay_gaze_raw)
        style = Paint.Style.FILL
    }

    private val gazeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.overlay_gaze)
        style = Paint.Style.FILL
    }

    private val gazeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.overlay_gaze_outline)
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }

    private val dwellFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.overlay_gaze_dwell)
        style = Paint.Style.FILL
    }

    private var rawPoint: Vec2? = null
    private var smoothedPoint: Vec2? = null
    private var confidence: Float = 0f
    private var isDwelling: Boolean = false
    private var showCalibrationTarget: Boolean = true
    private var calibrationProgress: Float = 0f

    fun render(
        rawPoint: Vec2?,
        smoothedPoint: Vec2?,
        confidence: Float,
        isDwelling: Boolean,
        showCalibrationTarget: Boolean,
        calibrationProgress: Float,
    ) {
        this.rawPoint = rawPoint
        this.smoothedPoint = smoothedPoint
        this.confidence = confidence.coerceIn(0f, 1f)
        this.isDwelling = isDwelling
        this.showCalibrationTarget = showCalibrationTarget
        this.calibrationProgress = calibrationProgress.coerceIn(0f, 1f)
        invalidate()
    }

    fun clear(
        showCalibrationTarget: Boolean = false,
        calibrationProgress: Float = 0f,
    ) {
        render(
            rawPoint = null,
            smoothedPoint = null,
            confidence = 0f,
            isDwelling = false,
            showCalibrationTarget = showCalibrationTarget,
            calibrationProgress = calibrationProgress,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) {
            return
        }

        if (showCalibrationTarget) {
            drawCalibrationTarget(canvas)
        }

        rawPoint?.let { drawRawPoint(canvas, it) }
        smoothedPoint?.let { drawGazePoint(canvas, it) }
    }

    private fun drawCalibrationTarget(canvas: Canvas) {
        val centerX = width * 0.5f
        val centerY = height * 0.5f
        val innerRadius = dp(7f)
        val outerRadius = dp(26f)
        canvas.drawCircle(centerX, centerY, innerRadius, targetFillPaint)
        canvas.drawCircle(centerX, centerY, outerRadius, targetStrokePaint)
        canvas.drawLine(centerX - dp(16f), centerY, centerX + dp(16f), centerY, targetStrokePaint)
        canvas.drawLine(centerX, centerY - dp(16f), centerX, centerY + dp(16f), targetStrokePaint)

        if (calibrationProgress > 0f) {
            val arcInset = dp(10f)
            val arcBounds = RectF(
                centerX - outerRadius - arcInset,
                centerY - outerRadius - arcInset,
                centerX + outerRadius + arcInset,
                centerY + outerRadius + arcInset,
            )
            canvas.drawArc(
                arcBounds,
                -90f,
                360f * calibrationProgress,
                false,
                progressPaint,
            )
        }
    }

    private fun drawRawPoint(
        canvas: Canvas,
        point: Vec2,
    ) {
        val screenPoint = point.toScreenPoint()
        rawPointPaint.alpha = (72 + (80 * confidence)).toInt().coerceIn(48, 152)
        canvas.drawCircle(screenPoint.x, screenPoint.y, dp(6f), rawPointPaint)
    }

    private fun drawGazePoint(
        canvas: Canvas,
        point: Vec2,
    ) {
        val screenPoint = point.toScreenPoint()
        val radius = dp(12f) + (dp(10f) * confidence)
        val fillPaint = if (isDwelling) dwellFillPaint else gazeFillPaint
        canvas.drawCircle(screenPoint.x, screenPoint.y, radius, fillPaint)
        canvas.drawCircle(screenPoint.x, screenPoint.y, radius + dp(4f), gazeStrokePaint)
    }

    private fun Vec2.toScreenPoint(): Vec2 = Vec2(
        x = x.coerceIn(0f, 1f) * width.toFloat(),
        y = y.coerceIn(0f, 1f) * height.toFloat(),
    )

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
