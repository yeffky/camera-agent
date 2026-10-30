package com.example.cameraagent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.View

class CompositionOverlayView(context: Context) : View(context) {
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(185, 255, 255, 255)
        strokeWidth = resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(80, 230, 190)
        strokeWidth = 3f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var faceBounds: RectF? = null
    private var imageAspectRatio = 1f
    private var colorStyle = ColorStyle.NATURAL
    private var previewTintOverride: Int? = null

    fun updateAssessment(assessment: SceneAssessment?, style: ColorStyle) {
        faceBounds = assessment?.faceBounds
        imageAspectRatio = assessment?.imageAspectRatio ?: 1f
        colorStyle = style
        invalidate()
    }

    fun setColorStyle(style: ColorStyle) {
        colorStyle = style
        invalidate()
    }

    fun setPreviewTint(color: Int) {
        previewTintOverride = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val previewTint = previewTintOverride ?: colorStyle.previewTint
        if ((previewTint ushr 24) > 0) {
            tintPaint.color = previewTint
            tintPaint.style = Paint.Style.FILL
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), tintPaint)
        }
        val thirdWidth = width / 3f
        val thirdHeight = height / 3f
        canvas.drawLine(thirdWidth, 0f, thirdWidth, height.toFloat(), linePaint)
        canvas.drawLine(thirdWidth * 2, 0f, thirdWidth * 2, height.toFloat(), linePaint)
        canvas.drawLine(0f, thirdHeight, width.toFloat(), thirdHeight, linePaint)
        canvas.drawLine(0f, thirdHeight * 2, width.toFloat(), thirdHeight * 2, linePaint)

        faceBounds?.let { bounds ->
            val mapped = mapCenterCrop(bounds)
            canvas.drawRoundRect(mapped, 12f * resources.displayMetrics.density, 12f * resources.displayMetrics.density, facePaint)
        }
    }

    private fun mapCenterCrop(bounds: RectF): RectF {
        val topLeft = mapNormalizedPoint(bounds.left, bounds.top, imageAspectRatio)
        val bottomRight = mapNormalizedPoint(bounds.right, bounds.bottom, imageAspectRatio)
        return RectF(
            topLeft.x,
            topLeft.y,
            bottomRight.x,
            bottomRight.y,
        )
    }

    fun mapNormalizedPoint(x: Float, y: Float, sourceAspectRatio: Float): PointF {
        val viewAspect = width.toFloat() / height.coerceAtLeast(1)
        var mappedX = x
        var mappedY = y
        if (sourceAspectRatio > viewAspect) {
            val visibleWidth = viewAspect / sourceAspectRatio
            mappedX = (mappedX - (1f - visibleWidth) / 2f) / visibleWidth
        } else if (sourceAspectRatio < viewAspect) {
            val visibleHeight = sourceAspectRatio / viewAspect
            mappedY = (mappedY - (1f - visibleHeight) / 2f) / visibleHeight
        }
        return PointF(
            mappedX.coerceIn(0f, 1f) * width,
            mappedY.coerceIn(0f, 1f) * height,
        )
    }
}
