package com.example.cameraagent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt
import kotlin.math.pow

object PhotoColorProcessor {
    fun process(inputFile: File, outputFile: File, preset: PhotoPreset, recipe: PhotoRecipe, amount: Int = 100) {
        val strength = amount.coerceIn(0, 100) / 100f
        if ((preset.style == ColorStyle.NATURAL && recipe == PhotoRecipe()) || strength == 0f) {
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
            applyColorMatrix(bitmap, preset.style.matrix, recipe, strength)
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

    private fun applyColorMatrix(bitmap: Bitmap, matrix: FloatArray, recipe: PhotoRecipe, strength: Float) {
        val width = bitmap.width
        val rowsPerBatch = ROWS_PER_BATCH
        val pixels = IntArray(width * rowsPerBatch)
        val exposureMultiplier = 2f.pow(recipe.exposureEv * strength)
        val contrastFactor = 1f + recipe.contrast * 0.65f * strength
        val saturationFactor = 1f + recipe.saturation * strength
        val temperatureOffset = recipe.temperature * 22f * strength
        val tintOffset = recipe.tint * strength
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
                val mixedRed = red + (transformedRed - red) * strength
                val mixedGreen = green + (transformedGreen - green) * strength
                val mixedBlue = blue + (transformedBlue - blue) * strength
                var r = mixedRed * exposureMultiplier
                var g = mixedGreen * exposureMultiplier
                var b = mixedBlue * exposureMultiplier
                val luminance = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0f, 255f)
                val highlightWeight = (luminance / 255f) * (luminance / 255f)
                val shadowWeight = ((255f - luminance) / 255f) * ((255f - luminance) / 255f)
                val tonalDelta = (recipe.highlights * highlightWeight + recipe.shadows * shadowWeight) * 44f * strength
                r += tonalDelta
                g += tonalDelta
                b += tonalDelta
                r = (r - 128f) * contrastFactor + 128f
                g = (g - 128f) * contrastFactor + 128f
                b = (b - 128f) * contrastFactor + 128f
                r += temperatureOffset + tintOffset * 3f
                g += tintOffset * 16f
                b -= temperatureOffset - tintOffset * 3f
                val adjustedLuma = 0.299f * r + 0.587f * g + 0.114f * b
                r = adjustedLuma + (r - adjustedLuma) * saturationFactor
                g = adjustedLuma + (g - adjustedLuma) * saturationFactor
                b = adjustedLuma + (b - adjustedLuma) * saturationFactor
                val outputRed = r.roundToInt().coerceIn(0, 255)
                val outputGreen = g.roundToInt().coerceIn(0, 255)
                val outputBlue = b.roundToInt().coerceIn(0, 255)
                pixels[index] = (alpha shl 24) or (outputRed shl 16) or (outputGreen shl 8) or outputBlue
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
