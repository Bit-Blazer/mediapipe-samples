package com.google.mediapipe.examples.handlandmarker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.max

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var results: HandLandmarkerResult? = null
    private val linePaint =
            Paint().apply {
                color = Color.BLUE
                strokeWidth = 8F
                style = Paint.Style.STROKE
            }
    private val pointPaint =
            Paint().apply {
                color = Color.YELLOW
                strokeWidth = 8F
                style = Paint.Style.FILL
            }

    private var scaleFactor = 1f
    private var imageWidth = 1
    private var imageHeight = 1

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        results?.landmarks()?.forEach { landmark ->
            landmark.forEach { point ->
                canvas.drawPoint(
                        point.x() * imageWidth * scaleFactor,
                        point.y() * imageHeight * scaleFactor,
                        pointPaint
                )
            }
            HandLandmarker.HAND_CONNECTIONS.forEach {
                val start = landmark[it!!.start()]
                val end = landmark[it.end()]
                canvas.drawLine(
                        start.x() * imageWidth * scaleFactor,
                        start.y() * imageHeight * scaleFactor,
                        end.x() * imageWidth * scaleFactor,
                        end.y() * imageHeight * scaleFactor,
                        linePaint
                )
            }
        }
    }

    fun setResults(
            handLandmarkerResults: HandLandmarkerResult,
            imageHeight: Int,
            imageWidth: Int,
    ) {
        results = handLandmarkerResults

        this.imageHeight = imageHeight
        this.imageWidth = imageWidth

        // PreviewView is in FILL_START mode. So we need to scale up the
        // landmarks to match with the size that the captured images will be
        // displayed.
        scaleFactor = max(width * 1f / imageWidth, height * 1f / imageHeight)

        invalidate()
    }
}
