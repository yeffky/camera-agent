package com.example.cameraagent

import android.graphics.Color
import kotlin.math.roundToInt

/** Calculates a subtle tint overlay without changing the active camera preview surface. */
object PresetPreviewEffect {
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
}
