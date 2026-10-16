package com.example.cameraagent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

object PhotoColorProcessor {
    fun process(inputFile: File, outputFile: File, style: ColorStyle) {
        if (style == ColorStyle.NATURAL) {
            inputFile.copyTo(outputFile, overwrite = true)
            return
        }

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val bitmap = BitmapFactory.decodeFile(inputFile.absolutePath, options)
            ?: error("无法解码拍摄照片")
        try {
            applyColorMatrix(bitmap, style.matrix)
            FileOutputStream(outputFile).use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) {
                    "照片编码失败"
                }
            }
            copyCaptureMetadata(inputFile, outputFile)
        } finally {
            bitmap.recycle()
        }
    }

    private fun applyColorMatrix(bitmap: Bitmap, matrix: FloatArray) {
        val width = bitmap.width
        val rowsPerBatch = ROWS_PER_BATCH
        val pixels = IntArray(width * rowsPerBatch)
        var top = 0
        while (top < bitmap.height) {
            val rowCount = minOf(rowsPerBatch, bitmap.height - top)
            val count = width * rowCount
            bitmap.getPixels(pixels, 0, width, 0, top, width, rowCount)
            for (index in 0 until count) {
                val color = pixels[index]
                val alpha = (color ushr 24) and 0xff
                val red = (color ushr 16) and 0xff
                val green = (color ushr 8) and 0xff
                val blue = color and 0xff
                val transformedRed = transformChannel(matrix, 0, red, green, blue, alpha)
                val transformedGreen = transformChannel(matrix, 5, red, green, blue, alpha)
                val transformedBlue = transformChannel(matrix, 10, red, green, blue, alpha)
                pixels[index] = (alpha shl 24) or
                    (transformedRed shl 16) or
                    (transformedGreen shl 8) or
                    transformedBlue
            }
            bitmap.setPixels(pixels, 0, width, 0, top, width, rowCount)
            top += rowCount
        }
    }

    private fun transformChannel(
        matrix: FloatArray,
        row: Int,
        red: Int,
        green: Int,
        blue: Int,
        alpha: Int,
    ): Int = (
        matrix[row] * red +
            matrix[row + 1] * green +
            matrix[row + 2] * blue +
            matrix[row + 3] * alpha +
            matrix[row + 4]
        ).roundToInt().coerceIn(0, 255)

    private fun copyCaptureMetadata(inputFile: File, outputFile: File) {
        val source = ExifInterface(inputFile.absolutePath)
        val destination = ExifInterface(outputFile.absolutePath)
        CAPTURE_METADATA.forEach { tag ->
            source.getAttribute(tag)?.let { destination.setAttribute(tag, it) }
        }
        destination.saveAttributes()
    }

    private const val JPEG_QUALITY = 95
    private const val ROWS_PER_BATCH = 32
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
