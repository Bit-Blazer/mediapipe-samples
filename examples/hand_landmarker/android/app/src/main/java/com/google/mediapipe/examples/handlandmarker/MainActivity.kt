package com.google.mediapipe.examples.handlandmarker

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mediapipe.examples.handlandmarker.databinding.ActivityMainBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), HandLandmarkerHelper.LandmarkerListener {

    private lateinit var binding: ActivityMainBinding

    /** Blocking ML operations are performed using this executor */
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var handLandmarkerHelper: HandLandmarkerHelper
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraFacing = CameraSelector.LENS_FACING_FRONT

    companion object {
        private const val TAG = "MainActivity"
    }

    // ✅ Register permission launcher **once** at the class level
    private val requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                    isGranted: Boolean ->
                if (isGranted) {
                    Toast.makeText(this, "Permission request granted", Toast.LENGTH_LONG).show()
                    setupCamera()
                } else {
                    Toast.makeText(this, "Permission request denied", Toast.LENGTH_LONG).show()
                    finish()
                }
            }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // ✅ Ensure the UI is ready before requesting permissions
        binding.viewFinder.post { requestCameraPermission() }
    }

    private fun requestCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED -> {
                setupCamera()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    // Initialize CameraX, and prepare to bind the camera use cases
    // Declare and bind preview, capture and analysis use cases
    @SuppressLint("UnsafeOptInUsageError")
    private fun setupCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(
                {
                    cameraProvider = cameraProviderFuture.get()

                    val cameraSelector =
                            CameraSelector.Builder().requireLensFacing(cameraFacing).build()

                    // Preview. Only using the 4:3 ratio because this is the closest to our models
                    val preview =
                            Preview.Builder()
                                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                                    .setTargetRotation(
                                            binding.viewFinder.display?.rotation
                                                    ?: Surface.ROTATION_0
                                    )
                                    .build()
                                    .also {
                                        // Attach the viewfinder's surface provider to preview use
                                        // case
                                        it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                                    }

                    // ImageAnalysis. Using RGBA 8888 to match how our models work
                    imageAnalyzer =
                            ImageAnalysis.Builder()
                                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                                    .setTargetRotation(
                                            binding.viewFinder.display?.rotation
                                                    ?: Surface.ROTATION_0
                                    )
                                    .setBackpressureStrategy(
                                            ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                                    )
                                    .setOutputImageFormat(
                                            ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                                    )
                                    .build()
                                    .also {
                                        it.setAnalyzer(cameraExecutor) { image ->
                                            detectHand(image)
                                        }
                                    }

                    try {
                        // Must unbind the use-cases before rebinding them
                        cameraProvider?.unbindAll()
                        // A variable number of use-cases can be passed here -
                        // camera provides access to CameraControl & CameraInfo
                        camera =
                                cameraProvider?.bindToLifecycle(
                                        this,
                                        cameraSelector,
                                        preview,
                                        imageAnalyzer
                                )
                    } catch (exc: Exception) {
                        Log.e(TAG, "Use case binding failed", exc)
                    }
                },
                ContextCompat.getMainExecutor(this)
        )

        handLandmarkerHelper =
                HandLandmarkerHelper(
                        context = this,
                        runningMode = RunningMode.LIVE_STREAM,
                        handLandmarkerHelperListener = this
                )
    }

    private fun detectHand(imageProxy: ImageProxy) {
        handLandmarkerHelper.detectLiveStream(
                imageProxy = imageProxy,
                isFrontCamera = (cameraFacing == CameraSelector.LENS_FACING_FRONT)
        )
    }

    // Update UI after hand have been detected. Extracts original
    // image height/width to scale and place the landmarks properly through
    // OverlayView
    override fun onResults(resultBundle: HandLandmarkerHelper.ResultBundle) {
        runOnUiThread {
            resultBundle.results.firstOrNull()?.let {

                // Pass necessary information to OverlayView for drawing on the canvas
                binding.overlay.setResults(
                        it,
                        resultBundle.inputImageHeight,
                        resultBundle.inputImageWidth,
                        RunningMode.LIVE_STREAM
                )
                // Force a redraw
                binding.overlay.invalidate()
            }
        }
    }

    override fun onError(error: String, errorCode: Int) {
        runOnUiThread { Toast.makeText(this, error, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        handLandmarkerHelper.clearHandLandmarker()
    }
    override fun onBackPressed() {
        finish()
    }
}
