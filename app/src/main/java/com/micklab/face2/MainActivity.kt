package com.micklab.face2

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Range
import android.util.Size
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalMirrorMode
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.micklab.face2.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

@ExperimentalMirrorMode
class MainActivity : AppCompatActivity(), FaceMeshProcessor.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var analysisExecutor: ExecutorService
    private lateinit var faceMeshProcessor: FaceMeshProcessor

    private val calibrationManager = CalibrationManager()
    private val gazeEstimator = GazeEstimator()

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var hasRequestedPermission = false
    private var hasBegunCalibrationFlow = false
    private var hasShownInitialCalibrationDialog = false
    private var isCalibrationSamplingActive = false
    private var calibrationStartDialog: AlertDialog? = null
    private var selectedResolution = CameraResolution.VGA
    private var calibrationState = CalibrationManager.State.pending(
        requiredSamples = CalibrationManager.DEFAULT_REQUIRED_SAMPLES,
        message = CalibrationManager.DEFAULT_GUIDANCE,
    )

    companion object {
        private const val CALIBRATION_TOTAL_STEPS = 2
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                showCameraUi()
                binding.previewView.post { startCamera() }
            } else {
                showPermissionUi()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        analysisExecutor = Executors.newSingleThreadExecutor()
        faceMeshProcessor = FaceMeshProcessor(
            context = applicationContext,
            listener = this,
            mirrorXAxis = true,
        )

        binding.previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        binding.previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        binding.previewView.scaleX = -1f

        configureControls()

        if (hasCameraPermission()) {
            showCameraUi()
        } else {
            showPermissionUi()
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission()) {
            showCameraUi()
            if (cameraProvider == null) {
                binding.previewView.post { startCamera() }
            }
        } else {
            showPermissionUi()
        }
    }

    override fun onDestroy() {
        calibrationStartDialog?.dismiss()
        cameraProvider?.unbindAll()
        imageAnalysis?.clearAnalyzer()
        faceMeshProcessor.close()
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    override fun onFaceResult(frameResult: FaceMeshProcessor.FrameResult) {
        val features = GazeEstimator.extractFeatures(
            landmarks = frameResult.landmarks,
            timestampMs = frameResult.timestampMs,
            mirrorX = frameResult.mirrorX,
        )

        runOnUiThread {
            if (features == null) {
                showTrackingState(
                    frameResult = frameResult,
                    features = null,
                    estimate = null,
                    headline = getString(R.string.status_no_face),
                    detail = buildNoFaceDetail(),
                )
                return@runOnUiThread
            }

            if (isCalibrationSamplingActive) {
                calibrationState = calibrationManager.consume(features)
                calibrationState.baseline?.let { baseline ->
                    if (gazeEstimator.calibration() !== baseline) {
                        gazeEstimator.setCalibration(baseline)
                    }
                }
                if (calibrationState.isCalibrated) {
                    isCalibrationSamplingActive = false
                }
            }

            val estimate = if (calibrationState.isCalibrated) {
                gazeEstimator.estimate(features)
            } else {
                null
            }

            val headline = when {
                isCalibrationSamplingActive ->
                    getString(R.string.calibration_progress, (calibrationState.progress * 100f).roundToInt())
                !calibrationState.isCalibrated -> getString(R.string.status_ready_to_calibrate)
                estimate?.isDwelling == true -> getString(R.string.status_dwell_active)
                else -> getString(R.string.status_tracking)
            }
            val detail = when {
                isCalibrationSamplingActive -> calibrationState.message
                !calibrationState.isCalibrated -> buildStandbyDetail()
                estimate != null -> buildTrackingDetail(features, estimate)
                else -> getString(R.string.status_camera_ready)
            }

            showTrackingState(
                frameResult = frameResult,
                features = features,
                estimate = estimate,
                headline = headline,
                detail = detail,
            )
        }
    }

    override fun onNoFaceDetected() {
        runOnUiThread {
            showTrackingState(
                frameResult = null,
                features = null,
                estimate = null,
                headline = getString(R.string.status_no_face),
                detail = buildNoFaceDetail(),
            )
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            binding.statusTextView.text = getString(R.string.status_error, message)
            binding.detailTextView.text = buildStandbyDetail()
            binding.metricsTextView.text = buildMetricsPlaceholder()
            binding.overlayView.clear(
                showCalibrationTarget = shouldShowCalibrationTarget(),
                calibrationProgress = calibrationOverlayProgress(),
            )
            updateCalibrationChrome()
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun configureControls() {
        binding.permissionButton.setOnClickListener {
            if (shouldOpenAppSettings()) {
                openAppSettings()
            } else {
                hasRequestedPermission = true
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        binding.recalibrateButton.setOnClickListener {
            showCalibrationStartDialog()
        }

        binding.resolutionToggleGroup.check(R.id.resolution640Button)
        binding.resolutionToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) {
                return@addOnButtonCheckedListener
            }

            val newResolution = when (checkedId) {
                R.id.resolution720Button -> CameraResolution.HD
                else -> CameraResolution.VGA
            }
            if (newResolution == selectedResolution) {
                return@addOnButtonCheckedListener
            }

            selectedResolution = newResolution
            binding.metricsTextView.text = buildMetricsPlaceholder()
            if (hasCameraPermission()) {
                binding.previewView.post { startCamera() }
            }
        }
    }

    private fun startCamera() {
        cameraProvider?.let {
            bindUseCases(it)
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                val provider = providerFuture.get()
                cameraProvider = provider

                if (!provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    onError("Front camera is not available on this device.")
                    return@addListener
                }

                bindUseCases(provider)
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    @ExperimentalMirrorMode
    private fun bindUseCases(provider: ProcessCameraProvider) {
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    selectedResolution.size,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()

        val preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(binding.previewView.display.rotation)
            .setTargetFrameRate(Range(15, 30))
            .setMirrorMode(MirrorMode.MIRROR_MODE_OFF)
            .build()
            .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

        imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(binding.previewView.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackgroundExecutor(analysisExecutor)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    faceMeshProcessor.analyze(imageProxy)
                }
            }

        provider.unbindAll()
        provider.bindToLifecycle(
            this,
            CameraSelector.DEFAULT_FRONT_CAMERA,
            preview,
            imageAnalysis,
        )

        binding.statusTextView.text = when {
            isCalibrationSamplingActive ->
                getString(R.string.calibration_progress, (calibrationState.progress * 100f).roundToInt())
            calibrationState.isCalibrated -> getString(R.string.status_tracking)
            else -> getString(R.string.status_ready_to_calibrate)
        }
        binding.detailTextView.text = buildStandbyDetail()
        binding.metricsTextView.text = buildMetricsPlaceholder()
        updateCalibrationChrome()
        maybeShowInitialCalibrationStartDialog()
    }

    private fun showTrackingState(
        frameResult: FaceMeshProcessor.FrameResult?,
        features: FrameFeatures?,
        estimate: GazeEstimate?,
        headline: String,
        detail: String,
    ) {
        binding.statusTextView.text = headline
        binding.detailTextView.text = detail
        binding.metricsTextView.text = buildMetricsText(frameResult, features, estimate)
        binding.overlayView.render(
            rawPoint = estimate?.rawPoint,
            smoothedPoint = estimate?.smoothedPoint,
            confidence = estimate?.confidence ?: 0f,
            isDwelling = estimate?.isDwelling == true,
            showCalibrationTarget = shouldShowCalibrationTarget(),
            calibrationProgress = calibrationOverlayProgress(),
        )
        updateCalibrationChrome()
    }

    private fun showPermissionUi() {
        cameraProvider?.unbindAll()
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        cameraProvider = null

        binding.permissionButton.visibility = View.VISIBLE
        binding.recalibrateButton.isEnabled = false
        binding.permissionButton.text = getString(
            if (shouldOpenAppSettings()) R.string.open_app_settings
            else R.string.grant_camera_permission,
        )
        binding.statusTextView.text = getString(R.string.status_permission_required)
        binding.detailTextView.text = getString(R.string.metrics_permission_help)
        binding.metricsTextView.text = buildMetricsPlaceholder()
        binding.overlayView.clear()
        updateCalibrationChrome()
    }

    private fun showCameraUi() {
        binding.permissionButton.visibility = View.GONE
        binding.recalibrateButton.isEnabled = true
        binding.statusTextView.text = getString(R.string.status_initializing)
        binding.detailTextView.text = buildStandbyDetail()
        binding.metricsTextView.text = buildMetricsPlaceholder()
        binding.overlayView.clear(
            showCalibrationTarget = shouldShowCalibrationTarget(),
            calibrationProgress = calibrationOverlayProgress(),
        )
        updateCalibrationChrome()
    }

    private fun buildTrackingDetail(
        features: FrameFeatures,
        estimate: GazeEstimate,
    ): String {
        return String.format(
            Locale.US,
            "%s • conf %.2f • dwell %d ms",
            selectedResolution.label,
            estimate.confidence,
            estimate.dwellDurationMs,
        )
    }

    private fun buildMetricsText(
        frameResult: FaceMeshProcessor.FrameResult?,
        features: FrameFeatures?,
        estimate: GazeEstimate?,
    ): String {
        if (frameResult == null || features == null) {
            return buildMetricsPlaceholder()
        }

        return buildString {
            appendLine("Inference: ${frameResult.inferenceTimeMs} ms")
            appendLine("Resolution: ${selectedResolution.label}")
            appendLine("Calibration samples: ${buildCalibrationSampleSummary()}")
            appendLine(
                String.format(
                    Locale.US,
                    "Face conf: %.2f  left: %.2f  right: %.2f",
                    features.faceConfidence,
                    features.leftEye?.confidence ?: 0f,
                    features.rightEye?.confidence ?: 0f,
                ),
            )
            appendLine(
                String.format(
                    Locale.US,
                    "Pose: yaw=%+.1f  pitch=%+.1f  roll=%+.1f",
                    features.yaw,
                    features.pitch,
                    features.roll,
                ),
            )
            appendLine(
                String.format(
                    Locale.US,
                    "Anchors: nose=(%.3f, %.3f) eyes=(%.3f, %.3f)",
                    features.nose.x,
                    features.nose.y,
                    features.eyeCenter.x,
                    features.eyeCenter.y,
                ),
            )
            estimate?.let {
                appendLine(
                    String.format(
                        Locale.US,
                        "Raw gaze: x=%.3f  y=%.3f",
                        it.rawPoint.x,
                        it.rawPoint.y,
                    ),
                )
                appendLine(
                    String.format(
                        Locale.US,
                        "Smooth gaze: x=%.3f  y=%.3f",
                        it.smoothedPoint.x,
                        it.smoothedPoint.y,
                    ),
                )
                appendLine(
                    String.format(
                        Locale.US,
                        "Scale correction: %.2fx",
                        it.scaleFactor,
                    ),
                )
            } ?: run {
                appendLine("Raw gaze: --")
                appendLine("Smooth gaze: --")
                appendLine("Scale correction: --")
            }
        }.trimEnd()
    }

    private fun buildStandbyDetail(): String {
        return when {
            isCalibrationSamplingActive -> calibrationState.message
            !calibrationState.isCalibrated -> getString(R.string.calibration_waiting_detail)
            else -> getString(R.string.status_camera_ready)
        }
    }

    private fun buildNoFaceDetail(): String {
        return when {
            isCalibrationSamplingActive -> getString(R.string.calibration_no_face_detail)
            !calibrationState.isCalibrated -> getString(R.string.calibration_waiting_detail)
            else -> getString(R.string.status_align_face)
        }
    }

    private fun buildMetricsPlaceholder(): String {
        return buildString {
            appendLine("Inference: -- ms")
            appendLine("Resolution: ${selectedResolution.label}")
            appendLine("Calibration samples: ${buildCalibrationSampleSummary()}")
            appendLine("Face conf: --  left: --  right: --")
            appendLine("Pose: yaw=--  pitch=--  roll=--")
            appendLine("Anchors: nose=(--, --) eyes=(--, --)")
            appendLine("Raw gaze: --")
            appendLine("Smooth gaze: --")
            append("Scale correction: --")
        }
    }

    private fun buildCalibrationSampleSummary(): String {
        return if (calibrationState.isCalibrated) {
            "${calibrationState.requiredSamples}/${calibrationState.requiredSamples}"
        } else {
            "${calibrationState.sampleCount}/${calibrationState.requiredSamples}"
        }
    }

    private fun showCalibrationStartDialog() {
        if (calibrationStartDialog?.isShowing == true) {
            return
        }

        hasShownInitialCalibrationDialog = true
        calibrationStartDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.calibration_start_dialog_title)
            .setMessage(getString(R.string.calibration_start_dialog_message))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.calibration_dialog_start) { _, _ ->
                beginCalibration()
            }
            .show()
    }

    private fun maybeShowInitialCalibrationStartDialog() {
        if (
            hasShownInitialCalibrationDialog ||
            hasBegunCalibrationFlow ||
            calibrationState.isCalibrated ||
            !hasCameraPermission()
        ) {
            return
        }

        showCalibrationStartDialog()
    }

    private fun beginCalibration() {
        calibrationStartDialog = null
        calibrationState = calibrationManager.beginCalibration()
        gazeEstimator.clearCalibration()
        isCalibrationSamplingActive = true
        hasBegunCalibrationFlow = true
        binding.statusTextView.text = getString(R.string.calibration_starting)
        binding.detailTextView.text = calibrationState.message
        binding.metricsTextView.text = buildMetricsPlaceholder()
        binding.overlayView.clear(
            showCalibrationTarget = shouldShowCalibrationTarget(),
            calibrationProgress = calibrationOverlayProgress(),
        )
        updateCalibrationChrome()
    }

    private fun updateCalibrationChrome() {
        val currentStep = currentCalibrationStep()
        binding.calibrationStepTextView.isVisible = currentStep != null
        binding.calibrationStepTextView.text = currentStep?.let { step ->
            getString(R.string.calibration_step_format, step.first, step.second)
        }.orEmpty()
        binding.recalibrateButton.text = getString(
            if (!hasBegunCalibrationFlow && !isCalibrationSamplingActive && !calibrationState.isCalibrated) {
                R.string.start_calibration
            } else {
                R.string.recalibrate
            },
        )
    }

    private fun currentCalibrationStep(): Pair<Int, Int>? {
        if (!hasCameraPermission()) {
            return null
        }

        return when {
            isCalibrationSamplingActive -> 2 to CALIBRATION_TOTAL_STEPS
            !hasBegunCalibrationFlow && !calibrationState.isCalibrated -> 1 to CALIBRATION_TOTAL_STEPS
            else -> null
        }
    }

    private fun shouldShowCalibrationTarget(): Boolean {
        return isCalibrationSamplingActive && !calibrationState.isCalibrated
    }

    private fun calibrationOverlayProgress(): Float {
        return if (shouldShowCalibrationTarget()) calibrationState.progress else 0f
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun shouldOpenAppSettings(): Boolean {
        return hasRequestedPermission &&
            !hasCameraPermission() &&
            !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
        startActivity(intent)
    }

    private enum class CameraResolution(
        val label: String,
        val size: Size,
    ) {
        VGA("640x480", Size(640, 480)),
        HD("1280x720", Size(1280, 720)),
    }
}
