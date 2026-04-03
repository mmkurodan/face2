package com.micklab.face2

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.atomic.AtomicBoolean

class FaceMeshProcessor(
    context: Context,
    private val listener: Listener,
    private val mirrorXAxis: Boolean,
) {

    private val isProcessing = AtomicBoolean(false)
    private val baseOptions = BaseOptions.builder()
        .setModelAssetPath(MODEL_ASSET_PATH)
        .build()
    private val faceLandmarker: FaceLandmarker

    private var bitmapBuffer: Bitmap? = null
    private var lastSubmittedAtMs = 0L
    private var submittedImageWidth = 0
    private var submittedImageHeight = 0
    private var submittedRotationDegrees = 0

    init {
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setOutputFaceBlendshapes(false)
            .setResultListener(this::onResult)
            .setErrorListener(this::onError)
            .build()

        faceLandmarker = FaceLandmarker.createFromOptions(context, options)
    }

    fun analyze(imageProxy: ImageProxy) {
        val frameTime = SystemClock.uptimeMillis()
        if (frameTime - lastSubmittedAtMs < MIN_FRAME_INTERVAL_MS ||
            !isProcessing.compareAndSet(false, true)
        ) {
            imageProxy.close()
            return
        }

        val frameWidth = imageProxy.width
        val frameHeight = imageProxy.height
        val bitmap = obtainBitmapBuffer(frameWidth, frameHeight)
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        try {
            imageProxy.use {
                val planeBuffer = imageProxy.planes.firstOrNull()?.buffer
                if (planeBuffer == null) {
                    isProcessing.set(false)
                    listener.onError("Camera frame buffer is unavailable.")
                    return
                }
                planeBuffer.rewind()
                bitmap.copyPixelsFromBuffer(planeBuffer)
            }

            val mpImage = BitmapImageBuilder(bitmap).build()
            val imageProcessingOptions = ImageProcessingOptions.builder()
                .setRotationDegrees(rotationDegrees)
                .build()

            submittedImageWidth = frameWidth
            submittedImageHeight = frameHeight
            submittedRotationDegrees = rotationDegrees
            lastSubmittedAtMs = frameTime
            faceLandmarker.detectAsync(mpImage, imageProcessingOptions, frameTime)
        } catch (t: Throwable) {
            isProcessing.set(false)
            listener.onError(t.message ?: "Face landmarker failed to process the frame.")
        }
    }

    fun close() {
        faceLandmarker.close()
    }

    private fun obtainBitmapBuffer(width: Int, height: Int): Bitmap {
        val current = bitmapBuffer
        if (current != null && current.width == width && current.height == height) {
            return current
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            bitmapBuffer = it
        }
    }

    private fun onResult(result: FaceLandmarkerResult, input: MPImage) {
        isProcessing.set(false)
        val landmarks = result.faceLandmarks().firstOrNull()
        if (landmarks.isNullOrEmpty()) {
            listener.onNoFaceDetected()
            return
        }

        val inferenceTimeMs = SystemClock.uptimeMillis() - result.timestampMs()
        listener.onFaceResult(
            FrameResult(
                landmarks = landmarks,
                imageWidth = submittedImageWidth.takeIf { it > 0 } ?: input.width,
                imageHeight = submittedImageHeight.takeIf { it > 0 } ?: input.height,
                rotationDegrees = submittedRotationDegrees,
                inferenceTimeMs = inferenceTimeMs,
                timestampMs = result.timestampMs(),
                mirrorX = mirrorXAxis,
            ),
        )
    }

    private fun onError(error: RuntimeException) {
        isProcessing.set(false)
        listener.onError(error.message ?: "MediaPipe Face Landmarker reported an error.")
    }

    data class FrameResult(
        val landmarks: List<NormalizedLandmark>,
        val imageWidth: Int,
        val imageHeight: Int,
        val rotationDegrees: Int,
        val inferenceTimeMs: Long,
        val timestampMs: Long,
        val mirrorX: Boolean,
    )

    interface Listener {
        fun onFaceResult(frameResult: FrameResult)
        fun onNoFaceDetected()
        fun onError(message: String)
    }

    private companion object {
        private const val MODEL_ASSET_PATH = "face_landmarker.task"
        private const val MIN_FRAME_INTERVAL_MS = 33L
    }
}
