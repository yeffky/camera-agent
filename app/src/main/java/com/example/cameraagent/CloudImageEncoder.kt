package com.example.cameraagent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.util.Base64
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

object CloudImageEncoder {
    private const val MAX_IMAGE_EDGE = 1024
    private const val MAX_COMPRESSED_BYTES = 512 * 1024
    private const val INITIAL_JPEG_QUALITY = 78
    private const val MIN_JPEG_QUALITY = 62

    fun encodeJpegDataUrl(image: ImageProxy, cancellation: AgentRequestCancellation? = null): String {
        cancellation?.throwIfCancelled()
        require(image.format == ImageFormat.JPEG) { "相机没有返回 JPEG 取景帧" }
        val plane = image.planes.firstOrNull() ?: error("取景帧为空")
        val buffer = plane.buffer.duplicate()
        val jpegBytes = ByteArray(buffer.remaining()).also(buffer::get)
        cancellation?.throwIfCancelled()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取取景帧" }

        val sampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
        val decoded = BitmapFactory.decodeByteArray(
            jpegBytes,
            0,
            jpegBytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: error("无法解码取景帧")
        if (cancellation?.isCancelled() == true) {
            decoded.recycle()
            cancellation.throwIfCancelled()
        }

        var oriented = decoded
        val rotation = image.imageInfo.rotationDegrees % 360
        if (rotation != 0) {
            val rotated = Bitmap.createBitmap(
                decoded,
                0,
                0,
                decoded.width,
                decoded.height,
                Matrix().apply { postRotate(rotation.toFloat()) },
                true,
            )
            if (rotated !== decoded) decoded.recycle()
            oriented = rotated
        }
        if (maxOf(oriented.width, oriented.height) > MAX_IMAGE_EDGE) {
            val scale = MAX_IMAGE_EDGE.toFloat() / maxOf(oriented.width, oriented.height)
            val resized = Bitmap.createScaledBitmap(
                oriented,
                (oriented.width * scale).toInt().coerceAtLeast(1),
                (oriented.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            if (resized !== oriented) oriented.recycle()
            oriented = resized
        }

        var candidate = oriented
        try {
            var quality = INITIAL_JPEG_QUALITY
            repeat(MAX_ENCODE_ATTEMPTS) { attempt ->
                cancellation?.throwIfCancelled()
                val output = ByteArrayOutputStream()
                candidate.compress(Bitmap.CompressFormat.JPEG, quality, output)
                val compressed = output.toByteArray()
                if (compressed.size <= MAX_COMPRESSED_BYTES) {
                    return "data:image/jpeg;base64,${Base64.encodeToString(compressed, Base64.NO_WRAP)}"
                }
                if (quality > MIN_JPEG_QUALITY) {
                    quality = (quality - QUALITY_STEP).coerceAtLeast(MIN_JPEG_QUALITY)
                } else if (attempt < MAX_ENCODE_ATTEMPTS - 1) {
                    val resized = Bitmap.createScaledBitmap(
                        candidate,
                        (candidate.width * RESIZE_FACTOR).toInt().coerceAtLeast(1),
                        (candidate.height * RESIZE_FACTOR).toInt().coerceAtLeast(1),
                        true,
                    )
                    if (resized !== candidate) candidate.recycle()
                    candidate = resized
                    quality = INITIAL_JPEG_QUALITY
                }
            }
            error("压缩后的取景帧仍超过 512 KB")
        } finally {
            if (!candidate.isRecycled) candidate.recycle()
        }
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        while (maxOf(width / (sampleSize * 2), height / (sampleSize * 2)) >= MAX_IMAGE_EDGE) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private const val QUALITY_STEP = 8
    private const val RESIZE_FACTOR = 0.8
    private const val MAX_ENCODE_ATTEMPTS = 6
}
