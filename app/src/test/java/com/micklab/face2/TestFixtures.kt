package com.micklab.face2

internal object TestFixtures {

    private val defaultEyeCenter = Vec3(0.5f, 0.42f, 0f)
    private val defaultNose = Vec3(0.5f, 0.57f, -0.02f)

    fun calibration(): CalibrationData = CalibrationData(
        leftEyeLocal = Vec3.ZERO,
        rightEyeLocal = Vec3.ZERO,
        nose = defaultNose,
        eyeCenter = defaultEyeCenter,
        noseToEyeDistance = defaultNose.distanceTo(defaultEyeCenter),
        rotation = Matrix3.IDENTITY,
        yaw = 0f,
        pitch = 0f,
        roll = 0f,
    )

    fun frame(
        timestampMs: Long,
        leftLocal: Vec3 = Vec3.ZERO,
        rightLocal: Vec3 = Vec3.ZERO,
        faceConfidence: Float = 0.95f,
        eyeCenter: Vec3 = defaultEyeCenter,
        nose: Vec3 = defaultNose,
        yaw: Float = 0f,
        pitch: Float = 0f,
        roll: Float = 0f,
    ): FrameFeatures {
        val leftEye = EyeMeasurement(
            irisCenter = eyeCenter + leftLocal,
            eyeCenter = eyeCenter,
            localVector = leftLocal,
            confidence = faceConfidence,
        )
        val rightEye = EyeMeasurement(
            irisCenter = eyeCenter + rightLocal,
            eyeCenter = eyeCenter,
            localVector = rightLocal,
            confidence = faceConfidence,
        )

        return FrameFeatures(
            timestampMs = timestampMs,
            leftEye = leftEye,
            rightEye = rightEye,
            nose = nose,
            eyeCenter = eyeCenter,
            noseToEyeDistance = nose.distanceTo(eyeCenter),
            rotation = Matrix3.IDENTITY,
            yaw = yaw,
            pitch = pitch,
            roll = roll,
            faceConfidence = faceConfidence,
            combinedLocalGaze = averageVec3(listOf(leftLocal, rightLocal)) ?: Vec3.ZERO,
        )
    }
}
