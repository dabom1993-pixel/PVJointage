package com.adf.pvjointage.ui

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * ImageView qui se zoome à la souris/au doigt : pincement à deux doigts pour agrandir,
 * glissement pour se déplacer dans l'image agrandie, double-tap pour basculer entre
 * l'affichage ajusté et un zoom x2,5.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val matrixValues = FloatArray(9)
    private val imgMatrix = Matrix()
    private var minScale = 1f
    private val maxScale = 6f
    private var currentScale = 1f

    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val previous = currentScale
            currentScale = (currentScale * detector.scaleFactor).coerceIn(minScale, maxScale)
            val factor = if (previous != 0f) currentScale / previous else 1f
            imgMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
            constrainMatrix()
            imageMatrix = imgMatrix
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            toggleZoom(e.x, e.y)
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1 && currentScale > minScale + 0.01f) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    imgMatrix.postTranslate(dx, dy)
                    constrainMatrix()
                    imageMatrix = imgMatrix
                    lastX = event.x
                    lastY = event.y
                }
            }
        }
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitToView()
    }

    /** Réinitialise l'image en position "ajustée" (visible en entier, centrée). */
    fun fitToView() {
        val d = drawable ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (vw == 0f || vh == 0f || dw <= 0f || dh <= 0f) return
        val scale = minOf(vw / dw, vh / dh)
        val dx = (vw - dw * scale) / 2f
        val dy = (vh - dh * scale) / 2f
        imgMatrix.reset()
        imgMatrix.postScale(scale, scale)
        imgMatrix.postTranslate(dx, dy)
        imageMatrix = imgMatrix
        currentScale = 1f
        minScale = 1f
    }

    private fun toggleZoom(focusX: Float, focusY: Float) {
        if (currentScale > minScale + 0.01f) {
            fitToView()
        } else {
            val target = 2.5f
            val factor = target / currentScale
            imgMatrix.postScale(factor, factor, focusX, focusY)
            currentScale = target
            constrainMatrix()
            imageMatrix = imgMatrix
        }
    }

    private fun constrainMatrix() {
        val d = drawable ?: return
        imgMatrix.getValues(matrixValues)
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]
        val scaleX = matrixValues[Matrix.MSCALE_X]
        val dw = d.intrinsicWidth * scaleX
        val dh = d.intrinsicHeight * scaleX
        val vw = width.toFloat()
        val vh = height.toFloat()

        var fixedTransX = transX
        var fixedTransY = transY

        if (dw <= vw) {
            fixedTransX = (vw - dw) / 2f
        } else if (transX > 0f) {
            fixedTransX = 0f
        } else if (transX < vw - dw) {
            fixedTransX = vw - dw
        }

        if (dh <= vh) {
            fixedTransY = (vh - dh) / 2f
        } else if (transY > 0f) {
            fixedTransY = 0f
        } else if (transY < vh - dh) {
            fixedTransY = vh - dh
        }

        imgMatrix.postTranslate(fixedTransX - transX, fixedTransY - transY)
    }
}
