package com.example.cameraagent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.floor

object PhotoCropProcessor {
    fun crop(inputFile: File, outputFile: File, crop: NormalizedCropRect) {
        val exif = ExifInterface(inputFile.absolutePath)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(inputFile.absolutePath, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "无法读取拍摄照片尺寸" }
        val rawRect = toRawRect(crop, options.outWidth, options.outHeight, exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        ))
        val regionDecoder = android.graphics.BitmapRegionDecoder.newInstance(inputFile.absolutePath, false)
            ?: error("无法打开照片区域解码器")
        val bitmap = try {
            regionDecoder.decodeRegion(rawRect, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: error("无法裁剪照片区域")
        } finally {
            regionDecoder.recycle()
        }
        val orientedBitmap = try {
            orient(bitmap, exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            ))
        } catch (error: Exception) {
            bitmap.recycle()
            throw error
        } catch (error: OutOfMemoryError) {
            bitmap.recycle()
            throw error
        }
        if (orientedBitmap !== bitmap) bitmap.recycle()
        try {
            require(minOf(orientedBitmap.width, orientedBitmap.height) >= MIN_OUTPUT_EDGE) {
                "AI 裁剪区域分辨率过低"
            }
            FileOutputStream(outputFile).use { output ->
                check(orientedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) { "裁剪照片编码失败" }
            }
            copyCaptureMetadata(exif, outputFile)
        } catch (error: Exception) {
            outputFile.delete()
            throw error
        } finally {
            orientedBitmap.recycle()
        }
    }

    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        val values = when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> floatArrayOf(-1f, 0f, width, 0f, 1f, 0f, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> floatArrayOf(-1f, 0f, width, 0f, -1f, height, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> floatArrayOf(1f, 0f, 0f, 0f, -1f, height, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_ROTATE_90 -> floatArrayOf(0f, -1f, height, 1f, 0f, 0f, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_TRANSVERSE -> floatArrayOf(0f, -1f, height, -1f, 0f, width, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_ROTATE_270 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, width, 0f, 0f, 1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { setValues(values) }, true)
    }

    private fun toRawRect(crop: NormalizedCropRect, width: Int, height: Int, orientation: Int): Rect {
        fun toRawPoint(x: Float, y: Float): Pair<Float, Float> = when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> 1f - x to y
            ExifInterface.ORIENTATION_ROTATE_180 -> 1f - x to 1f - y
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> x to 1f - y
            ExifInterface.ORIENTATION_TRANSPOSE -> y to x
            ExifInterface.ORIENTATION_ROTATE_90 -> y to 1f - x
            ExifInterface.ORIENTATION_TRANSVERSE -> 1f - y to 1f - x
            ExifInterface.ORIENTATION_ROTATE_270 -> 1f - y to x
            else -> x to y
        }
        val points = listOf(
            toRawPoint(crop.left, crop.top),
            toRawPoint(crop.right, crop.top),
            toRawPoint(crop.left, crop.bottom),
            toRawPoint(crop.right, crop.bottom),
        )
        val left = floor(points.minOf { it.first } * width).toInt().coerceIn(0, width - 1)
        val top = floor(points.minOf { it.second } * height).toInt().coerceIn(0, height - 1)
        val right = ceil(points.maxOf { it.first } * width).toInt().coerceIn(left + 1, width)
        val bottom = ceil(points.maxOf { it.second } * height).toInt().coerceIn(top + 1, height)
        return Rect(left, top, right, bottom)
    }

    private fun copyCaptureMetadata(source: ExifInterface, outputFile: File) {
        val destination = ExifInterface(outputFile.absolutePath)
        CAPTURE_METADATA.forEach { tag -> source.getAttribute(tag)?.let { destination.setAttribute(tag, it) } }
        destination.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        destination.saveAttributes()
    }

    private const val JPEG_QUALITY = 98
    private const val MIN_OUTPUT_EDGE = 480
    private val CAPTURE_METADATA = listOf(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
    )
}
