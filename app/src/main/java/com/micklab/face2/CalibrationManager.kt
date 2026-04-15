package com.micklab.face2

import kotlin.math.abs

class CalibrationManager(
    private val minConfidence: Float = 0.55f,
) {

    private var baseline: CalibrationData? = null
    private var lastMessage: String = DEFAULT_GUIDANCE

    val requiredSamples: Int
        get() = DEFAULT_REQUIRED_SAMPLES

    fun beginCalibration(): State {
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
                sampleCount = DEFAULT_REQUIRED_SAMPLES,
                requiredSamples = DEFAULT_REQUIRED_SAMPLES,
                message = DEFAULT_COMPLETE_MESSAGE,
                baseline = currentBaseline,
            )
        } else {
            State(
                isCalibrated = false,
                progress = 0f,
                sampleCount = 0,
                requiredSamples = DEFAULT_REQUIRED_SAMPLES,
                message = lastMessage,
                baseline = null,
            )
        }
    }

    fun capture(features: FrameFeatures): State {
        if (baseline != null) {
            return currentState()
        }

        if (!isUsable(features)) {
            lastMessage = buildGuidance(features)
            return currentState()
        }

        baseline = buildCalibration(listOf(features))
        lastMessage = DEFAULT_COMPLETE_MESSAGE
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
                "Move slightly closer until both irises stay visible."
            features.faceConfidence < minConfidence ->
                "Hold the phone steady until the face mesh locks on."
            abs(features.yaw) >= 24f || abs(features.pitch) >= 20f || abs(features.roll) >= 18f ->
                "Face the screen straight on while calibrating."
            abs(features.eyeCenter.x - 0.5f) >= 0.24f || abs(features.eyeCenter.y - 0.5f) >= 0.22f ->
                "Center your face and keep looking at the center target."
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
        const val DEFAULT_REQUIRED_SAMPLES = 1
        const val DEFAULT_GUIDANCE = "Look at the center target, then tap it while keeping your head and phone steady."
        const val DEFAULT_COMPLETE_MESSAGE = "Calibration complete. Live gaze tracking is active."
    }
}
