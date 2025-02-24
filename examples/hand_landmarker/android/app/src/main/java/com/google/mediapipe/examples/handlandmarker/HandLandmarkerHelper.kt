package com.google.mediapipe.examples.handlandmarker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

class HandLandmarkerHelper(
        val context: Context,
        // this listener is only used when running in RunningMode.LIVE_STREAM
        val handLandmarkerHelperListener: LandmarkerListener? = null
) {

    // For this example this needs to be a var so it can be reset on changes.
    // If the Hand Landmarker will not change, a lazy val would be preferable.
    private var handLandmarker: HandLandmarker? = null

    init {
        setupHandLandmarker()
    }

    fun clearHandLandmarker() {
        handLandmarker?.close()
        handLandmarker = null
    }

    // Return running status of HandLandmarkerHelper
    fun isClose(): Boolean {
        return handLandmarker == null
    }

    // Initialize the Hand landmarker using current settings on the
    // thread that is using it. CPU can be used with Landmarker
    // that are created on the main thread and used on a background thread, but
    // the GPU delegate needs to be used on the thread that initialized the
    // Landmarker
    fun setupHandLandmarker() {
        // Check if runningMode is consistent with handLandmarkerHelperListener

        if (handLandmarkerHelperListener == null) {
            throw IllegalStateException(
                    "handLandmarkerHelperListener must be set when runningMode is LIVE_STREAM."
            )
        }

        try {
            // Set general hand landmarker options
            val baseOptions =
                    BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build()
            // Create an option builder with base options and specific
            // options only use for Hand Landmarker.
            val options =
                    HandLandmarker.HandLandmarkerOptions.builder()
                            .setBaseOptions(baseOptions)
                            .setRunningMode(RunningMode.LIVE_STREAM)
                            // The ResultListener and ErrorListener only use for LIVE_STREAM mode.
                            .setResultListener(::returnLivestreamResult)
                            .setErrorListener(::returnLivestreamError)
                            .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
        } catch (e: IllegalStateException) {
            handLandmarkerHelperListener.onError(
                    "Hand Landmarker failed to initialize. See error logs for details"
            )
            Log.e(TAG, "MediaPipe failed to load the task with error: " + e.message)
        } catch (e: RuntimeException) {
            // This occurs if the model being used does not support GPU
            handLandmarkerHelperListener.onError(
                    "Hand Landmarker failed to initialize. See error logs for details"
            )
            Log.e(TAG, "Image classifier failed to load model with error: " + e.message)
        }
    }

    // Convert the ImageProxy to MP Image and feed it to HandlandmakerHelper.
    fun detectLiveStream(imageProxy: ImageProxy) {

        val frameTime = SystemClock.uptimeMillis()

        // Copy out RGB bits from the frame to a bitmap buffer
        val bitmapBuffer =
                Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
        imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
        imageProxy.close()

        val matrix =
                Matrix().apply {
                    // Rotate the frame received from the camera to be in the same direction as
                    // it'll be shown
                    postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

                    //  // flip image if user use front camera
                    //  if (isFrontCamera) {
                    //     postScale(-1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
                    // }
                }
        val rotatedBitmap =
                Bitmap.createBitmap(
                        bitmapBuffer,
                        0,
                        0,
                        bitmapBuffer.width,
                        bitmapBuffer.height,
                        matrix,
                        true
                )

        // Convert the input Bitmap object to an MPImage object to run inference
        val mpImage = BitmapImageBuilder(rotatedBitmap).build()

        detectAsync(mpImage, frameTime)
    }

    // Run hand hand landmark using MediaPipe Hand Landmarker API
    @VisibleForTesting
    fun detectAsync(mpImage: MPImage, frameTime: Long) {
        handLandmarker?.detectAsync(mpImage, frameTime)
        // As we're using running mode LIVE_STREAM, the landmark result will
        // be returned in returnLivestreamResult function
    }

    // Return the landmark result to this HandLandmarkerHelper's caller
    private fun returnLivestreamResult(result: HandLandmarkerResult, input: MPImage) {
        handLandmarkerHelperListener?.onResults(
                ResultBundle(listOf(result), input.height, input.width)
        )
    }

    // Return errors thrown during detection to this HandLandmarkerHelper's
    // caller
    private fun returnLivestreamError(error: RuntimeException) {
        handLandmarkerHelperListener?.onError(error.message ?: "An unknown error has occurred")
    }

    companion object {
        const val TAG = "HandLandmarkerHelper"
    }

    data class ResultBundle(
            val results: List<HandLandmarkerResult>,
            val inputImageHeight: Int,
            val inputImageWidth: Int,
    )

    interface LandmarkerListener {
        fun onError(error: String)
        fun onResults(resultBundle: ResultBundle)
    }
}
