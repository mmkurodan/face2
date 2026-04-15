package com.micklab.face2

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
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

    private val tapEffectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.overlay_target_progress)
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
    }

    private val tapEffectFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.overlay_target_progress)
        style = Paint.Style.FILL
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
    private var calibrationTargetTapListener: (() -> Unit)? = null
    private var tapEffectProgress: Float = 0f
    private var tapEffectAnimator: ValueAnimator? = null

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

    fun setOnCalibrationTargetTapListener(listener: (() -> Unit)?) {
        calibrationTargetTapListener = listener
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

    fun triggerCalibrationCaptureEffect() {
        tapEffectAnimator?.cancel()
        tapEffectAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320L
            addUpdateListener { animator ->
                tapEffectProgress = animator.animatedValue as Float
                invalidate()
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        tapEffectProgress = 0f
                        invalidate()
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        tapEffectProgress = 0f
                        invalidate()
                    }
                },
            )
            start()
        }
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

        if (tapEffectProgress > 0f) {
            drawCalibrationTapEffect(canvas)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!showCalibrationTarget) {
            return false
        }

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> isWithinCalibrationTarget(event.x, event.y)
            MotionEvent.ACTION_UP -> {
                if (!isWithinCalibrationTarget(event.x, event.y)) {
                    false
                } else {
                    calibrationTargetTapListener?.invoke()
                    performClick()
                    true
                }
            }
            else -> false
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        tapEffectAnimator?.cancel()
        tapEffectAnimator = null
        super.onDetachedFromWindow()
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

    private fun drawCalibrationTapEffect(canvas: Canvas) {
        val centerX = width * 0.5f
        val centerY = height * 0.5f
        val baseRadius = dp(20f)
        val ringRadius = baseRadius + (dp(44f) * tapEffectProgress)
        val fillRadius = dp(8f) + (dp(22f) * tapEffectProgress)
        val alpha = ((1f - tapEffectProgress) * 255).toInt().coerceIn(0, 255)

        tapEffectPaint.alpha = alpha
        tapEffectFillPaint.alpha = (alpha * 0.32f).toInt().coerceIn(0, 96)
        canvas.drawCircle(centerX, centerY, fillRadius, tapEffectFillPaint)
        canvas.drawCircle(centerX, centerY, ringRadius, tapEffectPaint)
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

    private fun isWithinCalibrationTarget(
        x: Float,
        y: Float,
    ): Boolean {
        val centerX = width * 0.5f
        val centerY = height * 0.5f
        val hitRadius = dp(48f)
        val dx = x - centerX
        val dy = y - centerY
        return (dx * dx) + (dy * dy) <= hitRadius * hitRadius
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
