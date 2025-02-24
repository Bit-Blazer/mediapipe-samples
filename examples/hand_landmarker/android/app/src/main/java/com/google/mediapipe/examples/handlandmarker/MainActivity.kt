package com.google.mediapipe.examples.handlandmarker

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), HandLandmarkerHelper.LandmarkerListener {

    /** UI Binding */
    private lateinit var binding: ActivityMainBinding

    /** CameraX Components */
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraFacing = CameraSelector.LENS_FACING_BACK

    /** AI Processing */
    private lateinit var handLandmarkerHelper: HandLandmarkerHelper

    /** Executor for Background Tasks - Blocking ML operations are performed using this executor */
    private lateinit var cameraExecutor: ExecutorService

    /** Permission Request Launcher */
    private val requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                    isGranted: Boolean ->
                if (isGranted) {
                    Toast.makeText(this, "Permission granted", Toast.LENGTH_LONG).show()
                    initializeApp()
                } else {
                    // Check if the user has previously denied permission
                    if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                        // User denied permission once but can still allow it
                        Toast.makeText(
                                        this,
                                        "Camera permission is required to use this app.",
                                        Toast.LENGTH_LONG
                                )
                                .show()
                    } else {
                        // User permanently denied permission (selected "Don't ask again")
                        Toast.makeText(
                                        this,
                                        "Permission denied. Enable it in app settings.",
                                        Toast.LENGTH_LONG
                                )
                                .show()
                    }
                }
            }

    // --------------------------------------------------------------
    // ✅ Activity Lifecycle Methods
    // --------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize UI
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize background executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Only request permission without setting up anything yet
        if (!hasPermissions(this)) {
            requestCameraPermission()
        } else {
            initializeApp()
        }
    }

    // Separate initialization logic into this function
    private fun initializeApp() {
        binding.viewFinder.post { setupCamera() }

        // Initialize AI hand tracking model
        cameraExecutor.execute {
            handLandmarkerHelper =
                    HandLandmarkerHelper(
                            context = this,
                            handLandmarkerHelperListener = this
                    )
        }
    }

    override fun onResume() {
        super.onResume()
        // Ensure permissions are still granted (User may have revoked them while the app was in
        // paused state.)
        if (!hasPermissions(this)) {
            requestCameraPermission()
        }
        // Restart HandLandmarkerHelper when app returns to foreground
        cameraExecutor.execute {
            if (::handLandmarkerHelper.isInitialized && handLandmarkerHelper.isClose()) {
                handLandmarkerHelper.setupHandLandmarker()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::handLandmarkerHelper.isInitialized) {
            // Release AI processing resources
            cameraExecutor.execute { handLandmarkerHelper.clearHandLandmarker() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Shut down background executor
        cameraExecutor.shutdown()
        cameraExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
        handLandmarkerHelper.clearHandLandmarker()
    }

    override fun onBackPressed() {
        finish()
    }

    // --------------------------------------------------------------
    // ✅ Permission Handling
    // --------------------------------------------------------------

    private fun requestCameraPermission() {
        when {
            // Only setup the camera if permission is already granted
            hasPermissions(this) -> setupCamera()
            // Request permission first, do NOT setup the camera yet
            else -> requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // --------------------------------------------------------------
    // ✅ CameraX Setup
    // --------------------------------------------------------------

    /** Initializes CameraX and binds camera use cases */
    @SuppressLint("UnsafeOptInUsageError", "MissingPermission")
    private fun setupCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(
                {
                    cameraProvider = cameraProviderFuture.get()
                    bindCameraUseCases()
                },
                ContextCompat.getMainExecutor(this)
        )

        // Ensure HandLandmarkerHelper is initialized
        cameraExecutor.execute {
            handLandmarkerHelper =
                    HandLandmarkerHelper(
                            context = this,
                            handLandmarkerHelperListener = this
                    )
        }
    }

    /** Binds necessary CameraX use cases: Preview & Image Analysis */
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val cameraProvider =
                cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector = CameraSelector.Builder().requireLensFacing(cameraFacing).build()

        // Preview setup - Only using the 4:3 ratio because this is the closest to our models
        preview =
                Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setTargetRotation(
                                binding.viewFinder.display?.rotation ?: Surface.ROTATION_0
                        )
                        .build()
                        .also {
                            // Attach the viewfinder's surface provider to preview use case
                            it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                        }

        // Image analysis setup (for hand tracking) - Using RGBA 8888 to match how our models work
        imageAnalyzer =
                ImageAnalysis.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setTargetRotation(
                                binding.viewFinder.display?.rotation ?: Surface.ROTATION_0
                        )
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                        // The analyzer can then be assigned to the instance
                        .also { it.setAnalyzer(cameraExecutor) { image -> detectHand(image) } }

        // Unbind previous use cases before rebinding
        cameraProvider.unbindAll()
        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    // --------------------------------------------------------------
    // ✅ AI Hand Tracking Processing
    // --------------------------------------------------------------

    /** Sends each camera frame to the AI model for hand landmark detection */
    private fun detectHand(imageProxy: ImageProxy) {
        handLandmarkerHelper.detectLiveStream(
                imageProxy = imageProxy
        )
    }

    /**
     * Updates UI after detecting hand landmarks - Extracts original image height/width to scale and
     * place the landmarks properly through OverlayView
     */
    override fun onResults(resultBundle: HandLandmarkerHelper.ResultBundle) {
        runOnUiThread {
            resultBundle.results.firstOrNull()?.let {
                // Pass necessary information to OverlayView for drawing on the canvas
                binding.overlay.setResults(
                        it,
                        resultBundle.inputImageHeight,
                        resultBundle.inputImageWidth,
                )
                binding.overlay.invalidate() // Redraw overlay
            }
        }
    }

    /** Handles errors during AI hand detection */
    override fun onError(error: String) {
        runOnUiThread { Toast.makeText(this, error, Toast.LENGTH_SHORT).show() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation = binding.viewFinder.display?.rotation ?: Surface.ROTATION_0
    }

    companion object {
        private const val TAG = "MainActivity"
        private val PERMISSIONS_REQUIRED = arrayOf(Manifest.permission.CAMERA)

        /** Checks if all required permissions are granted */
        fun hasPermissions(context: AppCompatActivity) =
                PERMISSIONS_REQUIRED.all {
                    ContextCompat.checkSelfPermission(context, it) ==
                            PackageManager.PERMISSION_GRANTED
                }
    }
}
