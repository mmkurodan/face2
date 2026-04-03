package com.micklab.face2

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class GazeEstimator(
    private val minAlpha: Float = 0.10f,
    private val maxAlpha: Float = 0.42f,
    private val jumpThreshold: Float = 0.26f,
    private val dwellRadius: Float = 0.035f,
    private val dwellThresholdMs: Long = 300L,
) {

    private var calibration: CalibrationData? = null
    private var smoothedPoint: Vec2? = null
    private var stableAnchor: Vec2? = null
    private var stableSinceMs = 0L

    fun calibration(): CalibrationData? = calibration

    fun clearCalibration() {
        calibration = null
        resetTemporalState()
    }

    fun setCalibration(calibration: CalibrationData) {
        this.calibration = calibration
        resetTemporalState()
    }

    fun estimate(features: FrameFeatures): GazeEstimate? {
        val calibration = calibration ?: return null
        val scaleFactor = (
            calibration.noseToEyeDistance / max(features.noseToEyeDistance, EPSILON)
            ).coerceIn(0.72f, 1.35f)

        val baselineRotationT = calibration.rotation.transpose()
        val anchorDelta = (
            ((features.eyeCenter - calibration.eyeCenter) * 0.6f) +
                ((features.nose - calibration.nose) * 0.4f)
            ) * scaleFactor
        val translationInBaselineFrame = baselineRotationT * anchorDelta

        val leftEstimate = estimateEye(
            measurement = features.leftEye,
            baselineLocal = calibration.leftEyeLocal,
            currentRotation = features.rotation,
            baselineRotationT = baselineRotationT,
            scaleFactor = scaleFactor,
        )
        val rightEstimate = estimateEye(
            measurement = features.rightEye,
            baselineLocal = calibration.rightEyeLocal,
            currentRotation = features.rotation,
            baselineRotationT = baselineRotationT,
            scaleFactor = scaleFactor,
        )

        val eyeEstimates = listOfNotNull(leftEstimate, rightEstimate)
        if (eyeEstimates.isEmpty()) {
            return holdPrevious(features.timestampMs)
        }

        val weightSum = eyeEstimates.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(EPSILON)
        val combinedVector = eyeEstimates.fold(Vec3.ZERO) { acc, estimate ->
            acc + (estimate.vector * (estimate.weight / weightSum))
        }
        val combinedConfidence = (
            (weightSum / eyeEstimates.size.toFloat()) * features.faceConfidence
            ).coerceIn(0f, 1f)

        val yawDelta = (features.yaw - calibration.yaw) / 18f
        val pitchDelta = (features.pitch - calibration.pitch) / 18f

        val fx = combinedVector.x - (translationInBaselineFrame.x * 0.75f) + (yawDelta * 0.18f)
        val fy = combinedVector.y - (translationInBaselineFrame.y * 0.85f) - (pitchDelta * 0.16f)
        val gz = combinedVector.z - (translationInBaselineFrame.z * 0.20f)

        val rawPoint = Vec2(
            x = (
                0.5f +
                    (2.55f * fx) +
                    (0.14f * fy) +
                    (0.12f * gz) +
                    (0.30f * fx * fx) -
                    (0.08f * fy * fy) +
                    (0.18f * fx * fy)
                ).coerceIn(0f, 1f),
            y = (
                0.5f +
                    (0.10f * fx) +
                    (2.25f * fy) -
                    (0.10f * gz) -
                    (0.05f * fx * fx) +
                    (0.28f * fy * fy) +
                    (0.14f * fx * fy)
                ).coerceIn(0f, 1f),
        )

        val smoothed = smooth(rawPoint, combinedConfidence)
        val dwellDurationMs = updateDwell(smoothed, features.timestampMs)

        return GazeEstimate(
            rawPoint = rawPoint,
            smoothedPoint = smoothed,
            confidence = combinedConfidence,
            leftConfidence = leftEstimate?.weight ?: 0f,
            rightConfidence = rightEstimate?.weight ?: 0f,
            scaleFactor = scaleFactor,
            isDwelling = dwellDurationMs >= dwellThresholdMs,
            dwellDurationMs = dwellDurationMs,
        )
    }

    private fun estimateEye(
        measurement: EyeMeasurement?,
        baselineLocal: Vec3,
        currentRotation: Matrix3,
        baselineRotationT: Matrix3,
        scaleFactor: Float,
    ): EyeEstimate? {
        measurement ?: return null

        val localDelta = (measurement.localVector - baselineLocal) * scaleFactor
        val localGazeVector = Vec3(
            x = localDelta.x * 1.4f,
            y = localDelta.y * 1.35f,
            z = localDelta.z * 0.9f,
        )
        val cameraSpaceVector = currentRotation * (FORWARD_VECTOR + localGazeVector)
        val baselineFrameVector = baselineRotationT * cameraSpaceVector
        return EyeEstimate(
            vector = baselineFrameVector - FORWARD_VECTOR,
            weight = measurement.confidence.coerceIn(0f, 1f),
        )
    }

    private fun holdPrevious(timestampMs: Long): GazeEstimate? {
        val previous = smoothedPoint ?: return null
        val dwellDurationMs = updateDwell(previous, timestampMs)
        return GazeEstimate(
            rawPoint = previous,
            smoothedPoint = previous,
            confidence = 0f,
            leftConfidence = 0f,
            rightConfidence = 0f,
            scaleFactor = 1f,
            isDwelling = dwellDurationMs >= dwellThresholdMs,
            dwellDurationMs = dwellDurationMs,
        )
    }

    private fun smooth(target: Vec2, confidence: Float): Vec2 {
        val current = smoothedPoint
        if (current == null) {
            smoothedPoint = target
            return target
        }

        val delta = target - current
        val distance = delta.magnitude()
        val limitedTarget = if (distance > jumpThreshold && confidence < 0.75f) {
            current + (delta.normalizedOrZero() * jumpThreshold)
        } else {
            target
        }

        val alpha = lerp(minAlpha, maxAlpha, confidence.coerceIn(0f, 1f))
        return current.lerp(limitedTarget, alpha).also {
            smoothedPoint = it
        }
    }

    private fun updateDwell(point: Vec2, timestampMs: Long): Long {
        val anchor = stableAnchor
        if (anchor == null || anchor.distanceTo(point) > dwellRadius) {
            stableAnchor = point
            stableSinceMs = timestampMs
            return 0L
        }
        return timestampMs - stableSinceMs
    }

    private fun resetTemporalState() {
        smoothedPoint = null
        stableAnchor = null
        stableSinceMs = 0L
    }

    private data class EyeEstimate(
        val vector: Vec3,
        val weight: Float,
    )

    companion object {
        private const val EPSILON = 1.0e-6f
        private val FORWARD_VECTOR = Vec3(0f, 0f, 1f)

        private val LEFT_IRIS_INDICES = (468..472).toList()
        private val RIGHT_IRIS_INDICES = (473..477).toList()
        private val LEFT_EYE_CONTOUR = listOf(263, 249, 390, 373, 374, 380, 381, 382, 362, 466, 388, 387, 386, 385, 384, 398)
        private val RIGHT_EYE_CONTOUR = listOf(33, 7, 163, 144, 145, 153, 154, 155, 133, 246, 161, 160, 159, 158, 157, 173)
        private val LEFT_EYE_CORNERS = listOf(263, 362)
        private val RIGHT_EYE_CORNERS = listOf(33, 133)
        private val LEFT_UPPER_LID = listOf(466, 388, 387, 386, 385, 384, 398)
        private val LEFT_LOWER_LID = listOf(249, 390, 373, 374, 380, 381, 382)
        private val RIGHT_UPPER_LID = listOf(246, 161, 160, 159, 158, 157, 173)
        private val RIGHT_LOWER_LID = listOf(7, 163, 144, 145, 153, 154, 155)
        private val NOSE_INDICES = listOf(1, 4, 5, 195, 197)
        private val FOREHEAD_INDICES = listOf(10, 9, 151)
        private val CHIN_INDICES = listOf(152, 175, 199)
        private val LEFT_FACE_EDGE = listOf(234, 93, 132, 58)
        private val RIGHT_FACE_EDGE = listOf(454, 323, 361, 288)

        fun extractFeatures(
            landmarks: List<NormalizedLandmark>,
            timestampMs: Long,
            mirrorX: Boolean,
        ): FrameFeatures? {
            val leftEye = extractEyeMeasurement(
                landmarks = landmarks,
                irisIndices = LEFT_IRIS_INDICES,
                contourIndices = LEFT_EYE_CONTOUR,
                cornerIndices = LEFT_EYE_CORNERS,
                upperLidIndices = LEFT_UPPER_LID,
                lowerLidIndices = LEFT_LOWER_LID,
                mirrorX = mirrorX,
            )
            val rightEye = extractEyeMeasurement(
                landmarks = landmarks,
                irisIndices = RIGHT_IRIS_INDICES,
                contourIndices = RIGHT_EYE_CONTOUR,
                cornerIndices = RIGHT_EYE_CORNERS,
                upperLidIndices = RIGHT_UPPER_LID,
                lowerLidIndices = RIGHT_LOWER_LID,
                mirrorX = mirrorX,
            )

            val nose = averagePoint(landmarks, NOSE_INDICES, mirrorX) ?: return null
            val forehead = averagePoint(landmarks, FOREHEAD_INDICES, mirrorX) ?: return null
            val chin = averagePoint(landmarks, CHIN_INDICES, mirrorX) ?: return null
            val leftFace = averagePoint(landmarks, LEFT_FACE_EDGE, mirrorX)
            val rightFace = averagePoint(landmarks, RIGHT_FACE_EDGE, mirrorX)

            val eyeCenter = averageVec3(
                listOfNotNull(
                    leftEye?.eyeCenter,
                    rightEye?.eyeCenter,
                ),
            ) ?: averageVec3(listOfNotNull(leftFace, rightFace)) ?: return null

            val horizontalAnchors = listOfNotNull(leftEye?.eyeCenter, rightEye?.eyeCenter, leftFace, rightFace)
            if (horizontalAnchors.size < 2) {
                return null
            }
            val screenLeftAnchor = horizontalAnchors.minByOrNull(Vec3::x) ?: return null
            val screenRightAnchor = horizontalAnchors.maxByOrNull(Vec3::x) ?: return null

            val xAxis = (screenRightAnchor - screenLeftAnchor).normalizedOrNull() ?: return null
            var yAxis = (forehead - chin) - (xAxis * (forehead - chin).dot(xAxis))
            yAxis = yAxis.normalizedOrNull() ?: return null

            var zAxis = xAxis.cross(yAxis).normalizedOrNull() ?: return null
            if ((nose - eyeCenter).dot(zAxis) < 0f) {
                zAxis = zAxis * -1f
            }
            yAxis = zAxis.cross(xAxis).normalizedOrNull() ?: return null

            val rotation = Matrix3.fromColumns(xAxis, yAxis, zAxis)
            val yaw = Math.toDegrees(atan2(zAxis.x.toDouble(), zAxis.z.toDouble())).toFloat()
            val pitch = Math.toDegrees((-asin(zAxis.y.coerceIn(-1f, 1f).toDouble()))).toFloat()
            val roll = Math.toDegrees(atan2(xAxis.y.toDouble(), xAxis.x.toDouble())).toFloat()

            val combinedLocalGaze = averageVec3(
                listOfNotNull(
                    leftEye?.localVector,
                    rightEye?.localVector,
                ),
            ) ?: return null

            val faceConfidenceInputs = buildList {
                leftEye?.let { add(it.confidence) }
                rightEye?.let { add(it.confidence) }
                add(averageConfidence(landmarks, NOSE_INDICES))
                add(averageConfidence(landmarks, FOREHEAD_INDICES))
                add(averageConfidence(landmarks, CHIN_INDICES))
            }
            val faceConfidence = if (faceConfidenceInputs.isEmpty()) {
                0f
            } else {
                faceConfidenceInputs.sum() / faceConfidenceInputs.size.toFloat()
            }.coerceIn(0f, 1f)

            return FrameFeatures(
                timestampMs = timestampMs,
                leftEye = leftEye,
                rightEye = rightEye,
                nose = nose,
                eyeCenter = eyeCenter,
                noseToEyeDistance = max(nose.distanceTo(eyeCenter), EPSILON),
                rotation = rotation,
                yaw = yaw,
                pitch = pitch,
                roll = roll,
                faceConfidence = faceConfidence,
                combinedLocalGaze = combinedLocalGaze,
            )
        }

        private fun extractEyeMeasurement(
            landmarks: List<NormalizedLandmark>,
            irisIndices: List<Int>,
            contourIndices: List<Int>,
            cornerIndices: List<Int>,
            upperLidIndices: List<Int>,
            lowerLidIndices: List<Int>,
            mirrorX: Boolean,
        ): EyeMeasurement? {
            val irisCenter = averagePoint(landmarks, irisIndices, mirrorX) ?: return null
            val contourCenter = averagePoint(landmarks, contourIndices, mirrorX) ?: return null
            val corners = cornerIndices.mapNotNull { landmarkToVec3(landmarks.getOrNull(it), mirrorX) }
            if (corners.size < 2) {
                return null
            }

            val screenLeftCorner = corners.minByOrNull(Vec3::x) ?: return null
            val screenRightCorner = corners.maxByOrNull(Vec3::x) ?: return null
            val upperLid = averagePoint(landmarks, upperLidIndices, mirrorX) ?: return null
            val lowerLid = averagePoint(landmarks, lowerLidIndices, mirrorX) ?: return null

            val horizontal = (screenRightCorner - screenLeftCorner).normalizedOrNull() ?: return null
            val verticalSeed = lowerLid - upperLid
            val vertical = (verticalSeed - (horizontal * verticalSeed.dot(horizontal))).normalizedOrNull() ?: return null
            val depth = horizontal.cross(vertical).normalizedOrNull() ?: return null

            val eyeWidth = max(screenRightCorner.distanceTo(screenLeftCorner), EPSILON)
            val eyeHeight = max(lowerLid.distanceTo(upperLid), EPSILON)
            val relative = irisCenter - contourCenter

            val confidence = (
                averageConfidence(landmarks, irisIndices) * 0.6f +
                    averageConfidence(landmarks, contourIndices) * 0.4f
                ).coerceIn(0f, 1f)

            return EyeMeasurement(
                irisCenter = irisCenter,
                eyeCenter = contourCenter,
                localVector = Vec3(
                    x = relative.dot(horizontal) / eyeWidth,
                    y = relative.dot(vertical) / eyeHeight,
                    z = relative.dot(depth) / eyeWidth,
                ),
                confidence = confidence,
            )
        }

        private fun averagePoint(
            landmarks: List<NormalizedLandmark>,
            indices: List<Int>,
            mirrorX: Boolean,
        ): Vec3? {
            val points = indices.mapNotNull { landmarkToVec3(landmarks.getOrNull(it), mirrorX) }
            return averageVec3(points)
        }

        private fun averageConfidence(
            landmarks: List<NormalizedLandmark>,
            indices: List<Int>,
        ): Float {
            val confidences = indices.mapNotNull(landmarks::getOrNull).map(::landmarkConfidence)
            return if (confidences.isEmpty()) {
                0f
            } else {
                confidences.sum() / confidences.size.toFloat()
            }
        }

        private fun landmarkToVec3(
            landmark: NormalizedLandmark?,
            mirrorX: Boolean,
        ): Vec3? {
            landmark ?: return null
            return Vec3(
                x = if (mirrorX) 1f - landmark.x() else landmark.x(),
                y = landmark.y(),
                z = landmark.z(),
            )
        }

        private fun landmarkConfidence(landmark: NormalizedLandmark): Float {
            val visibility = landmark.visibility().orElse(1f)
            val presence = landmark.presence().orElse(1f)
            return ((visibility + presence) * 0.5f).coerceIn(0f, 1f)
        }

        private fun lerp(start: Float, end: Float, amount: Float): Float {
            return start + ((end - start) * amount)
        }
    }
}

data class EyeMeasurement(
    val irisCenter: Vec3,
    val eyeCenter: Vec3,
    val localVector: Vec3,
    val confidence: Float,
)

data class FrameFeatures(
    val timestampMs: Long,
    val leftEye: EyeMeasurement?,
    val rightEye: EyeMeasurement?,
    val nose: Vec3,
    val eyeCenter: Vec3,
    val noseToEyeDistance: Float,
    val rotation: Matrix3,
    val yaw: Float,
    val pitch: Float,
    val roll: Float,
    val faceConfidence: Float,
    val combinedLocalGaze: Vec3,
)

data class CalibrationData(
    val leftEyeLocal: Vec3,
    val rightEyeLocal: Vec3,
    val nose: Vec3,
    val eyeCenter: Vec3,
    val noseToEyeDistance: Float,
    val rotation: Matrix3,
    val yaw: Float,
    val pitch: Float,
    val roll: Float,
)

data class GazeEstimate(
    val rawPoint: Vec2,
    val smoothedPoint: Vec2,
    val confidence: Float,
    val leftConfidence: Float,
    val rightConfidence: Float,
    val scaleFactor: Float,
    val isDwelling: Boolean,
    val dwellDurationMs: Long,
)

data class Vec2(
    val x: Float,
    val y: Float,
) {
    operator fun plus(other: Vec2): Vec2 = Vec2(x + other.x, y + other.y)
    operator fun minus(other: Vec2): Vec2 = Vec2(x - other.x, y - other.y)
    operator fun times(scale: Float): Vec2 = Vec2(x * scale, y * scale)

    fun lerp(other: Vec2, amount: Float): Vec2 = Vec2(
        x = x + ((other.x - x) * amount),
        y = y + ((other.y - y) * amount),
    )

    fun magnitude(): Float = sqrt((x * x) + (y * y))

    fun distanceTo(other: Vec2): Float = (this - other).magnitude()

    fun normalizedOrZero(): Vec2 {
        val magnitude = magnitude()
        if (magnitude <= 1.0e-6f) {
            return Vec2(0f, 0f)
        }
        return Vec2(x / magnitude, y / magnitude)
    }
}

data class Vec3(
    val x: Float,
    val y: Float,
    val z: Float,
) {
    operator fun plus(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Float): Vec3 = Vec3(x * scale, y * scale, z * scale)

    fun dot(other: Vec3): Float = (x * other.x) + (y * other.y) + (z * other.z)

    fun cross(other: Vec3): Vec3 = Vec3(
        x = (y * other.z) - (z * other.y),
        y = (z * other.x) - (x * other.z),
        z = (x * other.y) - (y * other.x),
    )

    fun magnitude(): Float = sqrt(dot(this))

    fun distanceTo(other: Vec3): Float = (this - other).magnitude()

    fun normalizedOrNull(): Vec3? {
        val magnitude = magnitude()
        if (magnitude <= 1.0e-6f) {
            return null
        }
        return Vec3(x / magnitude, y / magnitude, z / magnitude)
    }

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
    }
}

data class Matrix3(
    val m00: Float,
    val m01: Float,
    val m02: Float,
    val m10: Float,
    val m11: Float,
    val m12: Float,
    val m20: Float,
    val m21: Float,
    val m22: Float,
) {
    operator fun times(vector: Vec3): Vec3 = Vec3(
        x = (m00 * vector.x) + (m01 * vector.y) + (m02 * vector.z),
        y = (m10 * vector.x) + (m11 * vector.y) + (m12 * vector.z),
        z = (m20 * vector.x) + (m21 * vector.y) + (m22 * vector.z),
    )

    fun transpose(): Matrix3 = Matrix3(
        m00 = m00,
        m01 = m10,
        m02 = m20,
        m10 = m01,
        m11 = m11,
        m12 = m21,
        m20 = m02,
        m21 = m12,
        m22 = m22,
    )

    fun xAxis(): Vec3 = Vec3(m00, m10, m20)
    fun yAxis(): Vec3 = Vec3(m01, m11, m21)
    fun zAxis(): Vec3 = Vec3(m02, m12, m22)

    companion object {
        val IDENTITY = fromColumns(
            xAxis = Vec3(1f, 0f, 0f),
            yAxis = Vec3(0f, 1f, 0f),
            zAxis = Vec3(0f, 0f, 1f),
        )

        fun fromColumns(
            xAxis: Vec3,
            yAxis: Vec3,
            zAxis: Vec3,
        ): Matrix3 = Matrix3(
            m00 = xAxis.x,
            m01 = yAxis.x,
            m02 = zAxis.x,
            m10 = xAxis.y,
            m11 = yAxis.y,
            m12 = zAxis.y,
            m20 = xAxis.z,
            m21 = yAxis.z,
            m22 = zAxis.z,
        )
    }
}

fun averageVec3(points: List<Vec3>): Vec3? {
    if (points.isEmpty()) {
        return null
    }
    val count = points.size.toFloat()
    return Vec3(
        x = points.sumOf { it.x.toDouble() }.toFloat() / count,
        y = points.sumOf { it.y.toDouble() }.toFloat() / count,
        z = points.sumOf { it.z.toDouble() }.toFloat() / count,
    )
}
