package com.example.cameraagent

import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.os.Build
import android.view.View
import kotlin.math.pow
import kotlin.math.roundToInt

/** Applies a lightweight GPU-backed approximation of a photo preset to the live preview. */
object PresetPreviewEffect {
    fun apply(view: View, preset: PhotoPreset, recipe: PhotoRecipe, amount: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false
        }
        return runCatching {
            val strength = amount.coerceIn(0, 100) / 100f
            if (strength == 0f || (preset.style == ColorStyle.NATURAL && recipe == PhotoRecipe())) {
                view.setRenderEffect(null)
            } else {
                val presetMatrix = blendWithIdentity(preset.style.matrix, strength)
                val recipeMatrix = recipeMatrix(recipe, strength)
                val combined = multiply(presetMatrix, recipeMatrix)
                view.setRenderEffect(
                    RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(combined)),
                )
            }
            true
        }.getOrDefault(false)
    }

    fun fallbackTint(preset: PhotoPreset, recipe: PhotoRecipe, amount: Int): Int {
        val strength = amount.coerceIn(0, 100) / 100f
        if (strength <= 0f) return Color.TRANSPARENT
        val base = preset.style.previewTint
        val baseAlpha = ((base ushr 24) and 0xff) * strength
        val temperature = recipe.temperature * strength
        val tint = recipe.tint * strength
        val alpha = (baseAlpha + kotlin.math.abs(temperature) * 18f + kotlin.math.abs(tint) * 14f)
            .roundToInt().coerceIn(0, 34)
        val red = (((base shr 16) and 0xff) + temperature * 26f + tint * 10f).roundToInt().coerceIn(0, 255)
        val green = (((base shr 8) and 0xff) + tint * 20f).roundToInt().coerceIn(0, 255)
        val blue = ((base and 0xff) - temperature * 26f + tint * 6f).roundToInt().coerceIn(0, 255)
        return Color.argb(alpha, red, green, blue)
    }

    private fun blendWithIdentity(matrix: FloatArray, amount: Float): FloatArray {
        val identity = IDENTITY
        return FloatArray(20) { index ->
            identity[index] + (matrix[index] - identity[index]) * amount
        }
    }

    private fun recipeMatrix(recipe: PhotoRecipe, amount: Float): FloatArray {
        val exposure = 2.0.pow((recipe.exposureEv * amount).toDouble()).toFloat()
        val contrast = 1f + recipe.contrast * 0.65f * amount
        val saturation = 1f + recipe.saturation * amount
        val temperature = recipe.temperature * 22f * amount
        val tint = recipe.tint * amount
        val lumaR = 0.213f * (1f - saturation)
        val lumaG = 0.715f * (1f - saturation)
        val lumaB = 0.072f * (1f - saturation)
        val saturationMatrix = floatArrayOf(
            lumaR + saturation, lumaG, lumaB, 0f, 0f,
            lumaR, lumaG + saturation, lumaB, 0f, 0f,
            lumaR, lumaG, lumaB + saturation, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
        val colorMatrix = floatArrayOf(
            1f, 0f, 0f, 0f, temperature + tint * 3f,
            0f, 1f, 0f, 0f, tint * 16f,
            0f, 0f, 1f, 0f, -temperature + tint * 3f,
            0f, 0f, 0f, 1f, 0f,
        )
        val contrastMatrix = floatArrayOf(
            contrast * exposure, 0f, 0f, 0f, 128f * (1f - contrast),
            0f, contrast * exposure, 0f, 0f, 128f * (1f - contrast),
            0f, 0f, contrast * exposure, 0f, 128f * (1f - contrast),
            0f, 0f, 0f, 1f, 0f,
        )
        return multiply(saturationMatrix, multiply(colorMatrix, contrastMatrix))
    }

    private fun multiply(left: FloatArray, right: FloatArray): FloatArray {
        val output = FloatArray(20)
        for (row in 0 until 4) {
            for (column in 0 until 5) {
                var value = if (column == 4) left[row * 5 + 4] else 0f
                for (inner in 0 until 4) {
                    val rightValue = if (column == 4) right[inner * 5 + 4] else right[inner * 5 + column]
                    value += left[row * 5 + inner] * rightValue
                }
                output[row * 5 + column] = value
            }
        }
        return output
    }

    private val IDENTITY = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
}
