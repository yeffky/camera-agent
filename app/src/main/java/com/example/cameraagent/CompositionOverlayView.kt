package com.example.cameraagent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator

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
    private val cropShadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(145, 0, 0, 0) }
    private val cropBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 211, 106)
        strokeWidth = 2.5f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }

    private var faceBounds: RectF? = null
    private var imageAspectRatio = 1f
    private var colorStyle = ColorStyle.NATURAL
    private var previewTintOverride: Int? = null
    private var aiCropRect: NormalizedCropRect? = null
    private var cropSourceAspectRatio = 1f
    private var cropRevealProgress = 1f
    private var cropAnimator: ValueAnimator? = null

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

    fun setAiCropRect(rect: NormalizedCropRect?, sourceAspectRatio: Float = 1f, animate: Boolean = true) {
        cropAnimator?.cancel()
        aiCropRect = rect
        cropSourceAspectRatio = sourceAspectRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
        if (rect == null || !animate) {
            cropRevealProgress = 1f
        } else {
            cropRevealProgress = 0f
            cropAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 320L
                interpolator = DecelerateInterpolator()
                addUpdateListener { animation ->
                    cropRevealProgress = animation.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
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
        drawAiCrop(canvas)
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
        aiCropRect?.let { canvas.drawRoundRect(displayCropRect(it), 8f * resources.displayMetrics.density, 8f * resources.displayMetrics.density, cropBorderPaint) }
    }

    private fun drawAiCrop(canvas: Canvas) {
        val normalized = aiCropRect ?: return
        val target = mapCropRect(normalized)
        val progress = cropRevealProgress.coerceIn(0f, 1f)
        val full = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val current = RectF(
            full.left + (target.left - full.left) * progress,
            full.top + (target.top - full.top) * progress,
            full.right + (target.right - full.right) * progress,
            full.bottom + (target.bottom - full.bottom) * progress,
        )
        val outside = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(full, Path.Direction.CW)
            addRoundRect(current, 8f * resources.displayMetrics.density, 8f * resources.displayMetrics.density, Path.Direction.CW)
        }
        canvas.drawPath(outside, cropShadePaint)
    }

    private fun displayCropRect(rect: NormalizedCropRect): RectF {
        val target = mapCropRect(rect)
        val progress = cropRevealProgress.coerceIn(0f, 1f)
        return RectF(
            target.left * progress,
            target.top * progress,
            width - (width - target.right) * progress,
            height - (height - target.bottom) * progress,
        )
    }

    private fun mapCropRect(rect: NormalizedCropRect): RectF {
        val topLeft = mapNormalizedPoint(rect.left, rect.top, cropSourceAspectRatio)
        val bottomRight = mapNormalizedPoint(rect.right, rect.bottom, cropSourceAspectRatio)
        return RectF(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
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

    fun isCropFullyVisible(rect: NormalizedCropRect, sourceAspectRatio: Float): Boolean {
        if (width <= 0 || height <= 0 || sourceAspectRatio <= 0f) return false
        val viewAspect = width.toFloat() / height
        return if (sourceAspectRatio > viewAspect) {
            val visibleWidth = viewAspect / sourceAspectRatio
            val margin = (1f - visibleWidth) / 2f
            rect.left >= margin && rect.right <= 1f - margin
        } else if (sourceAspectRatio < viewAspect) {
            val visibleHeight = sourceAspectRatio / viewAspect
            val margin = (1f - visibleHeight) / 2f
            rect.top >= margin && rect.bottom <= 1f - margin
        } else {
            true
        }
    }
}
