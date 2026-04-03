package com.micklab.face2

import kotlin.collections.ArrayDeque
import kotlin.math.abs

class CalibrationManager(
    private val requiredSampleCount: Int = DEFAULT_REQUIRED_SAMPLES,
    private val minConfidence: Float = 0.55f,
) {

    private val samples = ArrayDeque<FrameFeatures>()
    private var baseline: CalibrationData? = null
    private var lastMessage: String = DEFAULT_GUIDANCE

    val requiredSamples: Int
        get() = requiredSampleCount

    fun beginCalibration(): State {
        samples.clear()
        baseline = null
        lastMessage = DEFAULT_GUIDANCE
        return currentState()
    }

    fun currentState(): State {
        val currentBaseline = baseline
        return if (currentBaseline != null) {
            State(
                isCalibrated = true,
                progress = 1f,
                sampleCount = requiredSampleCount,
                requiredSamples = requiredSampleCount,
                message = DEFAULT_COMPLETE_MESSAGE,
                baseline = currentBaseline,
            )
        } else {
            State(
                isCalibrated = false,
                progress = samples.size.toFloat() / requiredSampleCount.toFloat(),
                sampleCount = samples.size,
                requiredSamples = requiredSampleCount,
                message = lastMessage,
                baseline = null,
            )
        }
    }

    fun consume(features: FrameFeatures): State {
        if (baseline != null) {
            return currentState()
        }

        if (!isUsable(features)) {
            if (samples.isNotEmpty()) {
                samples.removeFirst()
            }
            lastMessage = buildGuidance(features)
            return currentState()
        }

        val previous = samples.lastOrNull()
        if (previous != null && !isStable(previous, features)) {
            // Large head movement → full reset. Minor gaze jitter → skip frame, keep samples.
            if (isLargeMovement(previous, features)) {
                samples.clear()
                lastMessage = DEFAULT_GUIDANCE
            }
            return currentState()
        }

        samples.addLast(features)
        while (samples.size > requiredSampleCount) {
            samples.removeFirst()
        }

        if (samples.size == requiredSampleCount) {
            baseline = buildCalibration(samples.toList())
            lastMessage = DEFAULT_COMPLETE_MESSAGE
        } else {
            lastMessage = buildProgressGuidance(samples.size, requiredSampleCount)
        }

        return currentState()
    }

    private fun isUsable(features: FrameFeatures): Boolean {
        return features.leftEye != null &&
            features.rightEye != null &&
            features.faceConfidence >= minConfidence &&
            abs(features.yaw) < 24f &&
            abs(features.pitch) < 20f &&
            abs(features.roll) < 18f &&
            abs(features.eyeCenter.x - 0.5f) < 0.24f &&
            abs(features.eyeCenter.y - 0.5f) < 0.22f
    }

    private fun isStable(
        previous: FrameFeatures,
        current: FrameFeatures,
    ): Boolean {
        return previous.eyeCenter.distanceTo(current.eyeCenter) < 0.040f &&
            previous.combinedLocalGaze.distanceTo(current.combinedLocalGaze) < 0.08f &&
            abs(previous.yaw - current.yaw) < 8f &&
            abs(previous.pitch - current.pitch) < 8f &&
            abs(previous.roll - current.roll) < 8f
    }

    private fun isLargeMovement(previous: FrameFeatures, current: FrameFeatures): Boolean {
        return previous.eyeCenter.distanceTo(current.eyeCenter) > 0.06f ||
            abs(previous.yaw - current.yaw) > 15f ||
            abs(previous.pitch - current.pitch) > 15f
    }

    private fun buildProgressGuidance(count: Int, required: Int): String {
        val ratio = count.toFloat() / required.toFloat()
        return when {
            ratio < 0.30f -> DEFAULT_GUIDANCE
            ratio < 0.55f -> GUIDANCE_QUARTER
            ratio < 0.80f -> GUIDANCE_HALF
            else -> GUIDANCE_ALMOST
        }
    }

    private fun buildCalibration(samples: List<FrameFeatures>): CalibrationData {
        val leftEyeLocal = averageVec3(samples.mapNotNull { it.leftEye?.localVector }) ?: Vec3.ZERO
        val rightEyeLocal = averageVec3(samples.mapNotNull { it.rightEye?.localVector }) ?: Vec3.ZERO
        val nose = averageVec3(samples.map { it.nose }) ?: Vec3.ZERO
        val eyeCenter = averageVec3(samples.map { it.eyeCenter }) ?: Vec3.ZERO
        val xAxis = averageVec3(samples.map { it.rotation.xAxis() })?.normalizedOrNull() ?: Vec3(1f, 0f, 0f)
        var yAxisSeed = averageVec3(samples.map { it.rotation.yAxis() }) ?: Vec3(0f, -1f, 0f)
        yAxisSeed = (yAxisSeed - (xAxis * yAxisSeed.dot(xAxis))).normalizedOrNull() ?: Vec3(0f, -1f, 0f)
        var zAxis = xAxis.cross(yAxisSeed).normalizedOrNull() ?: Vec3(0f, 0f, 1f)
        val forwardHint = averageVec3(samples.map { it.nose - it.eyeCenter }) ?: Vec3(0f, 0f, 1f)
        if (forwardHint.dot(zAxis) < 0f) {
            zAxis = zAxis * -1f
        }
        val yAxis = zAxis.cross(xAxis).normalizedOrNull() ?: yAxisSeed

        return CalibrationData(
            leftEyeLocal = leftEyeLocal,
            rightEyeLocal = rightEyeLocal,
            nose = nose,
            eyeCenter = eyeCenter,
            noseToEyeDistance = samples.map { it.noseToEyeDistance }.averageFloat(),
            rotation = Matrix3.fromColumns(xAxis, yAxis, zAxis),
            yaw = samples.map { it.yaw }.averageFloat(),
            pitch = samples.map { it.pitch }.averageFloat(),
            roll = samples.map { it.roll }.averageFloat(),
        )
    }

    private fun List<Float>.averageFloat(): Float {
        return if (isEmpty()) 0f else sum() / size.toFloat()
    }

    private fun buildGuidance(features: FrameFeatures): String {
        return when {
            features.leftEye == null || features.rightEye == null ->
                "Move slightly closer so both irises stay visible."
            features.faceConfidence < minConfidence ->
                "Hold the phone steady until the face mesh confidence rises."
            abs(features.yaw) >= 24f || abs(features.pitch) >= 20f || abs(features.roll) >= 18f ->
                "Face the screen more directly while calibrating."
            abs(features.eyeCenter.x - 0.5f) >= 0.24f || abs(features.eyeCenter.y - 0.5f) >= 0.22f ->
                "Center your face and keep looking at the middle dot."
            else -> DEFAULT_GUIDANCE
        }
    }

    data class State(
        val isCalibrated: Boolean,
        val progress: Float,
        val sampleCount: Int,
        val requiredSamples: Int,
        val message: String,
        val baseline: CalibrationData?,
    ) {
        companion object {
            fun pending(
                requiredSamples: Int,
                message: String,
            ): State = State(
                isCalibrated = false,
                progress = 0f,
                sampleCount = 0,
                requiredSamples = requiredSamples,
                message = message,
                baseline = null,
            )
        }
    }

    companion object {
        const val DEFAULT_REQUIRED_SAMPLES = 18
        const val DEFAULT_GUIDANCE = "Look at the center dot and keep your head steady."
        const val GUIDANCE_QUARTER = "Good — keep your gaze on the center dot."
        const val GUIDANCE_HALF = "Halfway there, stay still a moment longer…"
        const val GUIDANCE_ALMOST = "Almost done, keep looking at the dot!"
        const val DEFAULT_COMPLETE_MESSAGE = "Calibration complete. Live gaze tracking is active."
    }
}
