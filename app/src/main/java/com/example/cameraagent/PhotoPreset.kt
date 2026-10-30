package com.example.cameraagent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PhotoRecipe(
    val exposureEv: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val saturation: Float = 0f,
) {
    fun toJson() = JSONObject()
        .put("exposure_ev", exposureEv)
        .put("contrast", contrast)
        .put("highlights", highlights)
        .put("shadows", shadows)
        .put("temperature", temperature)
        .put("tint", tint)
        .put("saturation", saturation)

    companion object {
        fun fromJson(json: JSONObject) = PhotoRecipe(
            exposureEv = json.optDouble("exposure_ev", 0.0).toFloat().coerceIn(-2f, 2f),
            contrast = json.optDouble("contrast", 0.0).toFloat().coerceIn(-1f, 1f),
            highlights = json.optDouble("highlights", 0.0).toFloat().coerceIn(-1f, 1f),
            shadows = json.optDouble("shadows", 0.0).toFloat().coerceIn(-1f, 1f),
            temperature = json.optDouble("temperature", 0.0).toFloat().coerceIn(-1f, 1f),
            tint = json.optDouble("tint", 0.0).toFloat().coerceIn(-1f, 1f),
            saturation = json.optDouble("saturation", 0.0).toFloat().coerceIn(-1f, 1f),
        )
    }
}

data class PhotoPreset(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val style: ColorStyle,
    val defaultAmount: Int = 70,
    val recipe: PhotoRecipe = PhotoRecipe(),
    val isCustom: Boolean = false,
)

object PhotoPresets {
    val all = listOf(
        PhotoPreset("natural", "自然标准", "基础", "保留现场色彩，可叠加手动调整", ColorStyle.NATURAL, 100),
        PhotoPreset("warm_film", "暖色胶片", "胶片", "暖高光与柔和色彩", ColorStyle.WARM, 72,
            PhotoRecipe(contrast = -0.08f, highlights = -0.12f, shadows = 0.08f, temperature = 0.32f, saturation = 0.05f)),
        PhotoPreset("faded_film", "褪色复古", "胶片", "低反差、轻微褪色", ColorStyle.FADED_FILM, 68,
            PhotoRecipe(contrast = -0.22f, highlights = -0.12f, shadows = 0.18f, temperature = 0.12f, saturation = -0.18f)),
        PhotoPreset("cinematic", "冷调电影", "电影", "冷色氛围与清晰层次", ColorStyle.CINEMATIC, 65,
            PhotoRecipe(contrast = 0.16f, highlights = -0.18f, shadows = -0.05f, temperature = -0.18f, tint = 0.04f, saturation = -0.04f)),
        PhotoPreset("neon", "鲜活夜色", "夜景", "加强色彩表现", ColorStyle.NEON, 55,
            PhotoRecipe(contrast = 0.18f, highlights = -0.10f, shadows = -0.12f, temperature = -0.08f, tint = 0.12f, saturation = 0.20f)),
        PhotoPreset("portrait", "柔和人像", "人像", "柔和反差与暖肤色", ColorStyle.PORTRAIT, 58,
            PhotoRecipe(exposureEv = 0.12f, contrast = -0.16f, highlights = -0.22f, shadows = 0.14f, temperature = 0.16f, saturation = -0.03f)),
        PhotoPreset("food", "鲜润美食", "美食", "增强食物色彩", ColorStyle.FOOD, 60,
            PhotoRecipe(exposureEv = 0.08f, contrast = 0.10f, highlights = -0.12f, shadows = 0.08f, temperature = 0.12f, saturation = 0.18f)),
        PhotoPreset("monochrome", "银盐黑白", "黑白", "经典黑白影调", ColorStyle.MONOCHROME, 100,
            PhotoRecipe(contrast = 0.22f, highlights = -0.08f, shadows = -0.08f, saturation = -1f)),
    )
    private val byId = all.associateBy { it.id }
    fun find(id: String): PhotoPreset? = byId[id]
}

class PhotoPresetStore(context: Context) {
    private val preferences = context.getSharedPreferences("photo_presets", Context.MODE_PRIVATE)

    fun allPresets(): List<PhotoPreset> = PhotoPresets.all + customPresets()
    fun find(id: String): PhotoPreset? = PhotoPresets.find(id) ?: customPresets().firstOrNull { it.id == id }
    fun recipe(id: String): PhotoRecipe = preferences.getString("recipe_$id", null)
        ?.let { runCatching { PhotoRecipe.fromJson(JSONObject(it)) }.getOrNull() }
        ?: find(id)?.recipe ?: PhotoRecipe()
    fun setRecipe(id: String, recipe: PhotoRecipe) { preferences.edit().putString("recipe_$id", recipe.toJson().toString()).apply() }
    fun amount(id: String): Int = preferences.getInt("amount_$id", find(id)?.defaultAmount ?: 70).coerceIn(0, 100)
    fun setAmount(id: String, value: Int) { preferences.edit().putInt("amount_$id", value.coerceIn(0, 100)).apply() }
    fun selectedId(): String = preferences.getString("selected_id", "natural")?.takeIf { find(it) != null } ?: "natural"
    fun setSelectedId(id: String) { if (find(id) != null) preferences.edit().putString("selected_id", id).apply() }

    fun favorites(): Set<String> = preferences.getStringSet("favorites", emptySet())?.toSet().orEmpty()
    fun toggleFavorite(id: String): Boolean {
        val updated = favorites().toMutableSet()
        val nowFavorite = if (updated.contains(id)) { updated.remove(id); false } else { updated.add(id); true }
        preferences.edit().putStringSet("favorites", updated).apply()
        return nowFavorite
    }

    fun recent(): List<String> = preferences.getString("recent", "").orEmpty().split(',').filter { it.isNotBlank() }
    fun markRecent(id: String) {
        val updated = (listOf(id) + recent().filterNot { it == id }).take(8)
        preferences.edit().putString("recent", updated.joinToString(",")).apply()
    }

    fun customPresets(): List<PhotoPreset> {
        val raw = preferences.getString("custom_presets", "[]") ?: "[]"
        return runCatching {
            val json = JSONArray(raw)
            (0 until json.length()).mapNotNull { index ->
                val item = json.optJSONObject(index) ?: return@mapNotNull null
                val style = runCatching { ColorStyle.valueOf(item.optString("style")) }.getOrNull() ?: return@mapNotNull null
                PhotoPreset(
                    id = item.optString("id"),
                    name = item.optString("name", "自定义预设").take(24),
                    category = "自定义",
                    description = "我的配方",
                    style = style,
                    defaultAmount = item.optInt("amount", 70).coerceIn(0, 100),
                    recipe = PhotoRecipe.fromJson(item.optJSONObject("recipe") ?: JSONObject()),
                    isCustom = true,
                )
            }
        }.getOrDefault(emptyList())
    }

    fun createCustomPreset(name: String, style: ColorStyle, amount: Int, recipe: PhotoRecipe): PhotoPreset {
        val preset = PhotoPreset(
            id = "custom_${UUID.randomUUID().toString().replace("-", "").take(12)}",
            name = name.trim().take(24).ifBlank { "我的预设" },
            category = "自定义",
            description = "我的配方",
            style = style,
            defaultAmount = amount.coerceIn(0, 100),
            recipe = recipe,
            isCustom = true,
        )
        writeCustomPresets(customPresets() + preset)
        return preset
    }

    fun renameCustomPreset(id: String, name: String) {
        writeCustomPresets(customPresets().map { if (it.id == id) it.copy(name = name.trim().take(24).ifBlank { it.name }) else it })
    }

    fun deleteCustomPreset(id: String) {
        val wasSelected = preferences.getString("selected_id", "natural") == id
        writeCustomPresets(customPresets().filterNot { it.id == id })
        val favorites = favorites() - id
        val recent = recent().filterNot { it == id }
        preferences.edit().putStringSet("favorites", favorites).putString("recent", recent.joinToString(","))
            .remove("amount_$id").remove("recipe_$id")
            .apply()
        if (wasSelected) setSelectedId("natural")
    }

    private fun writeCustomPresets(presets: List<PhotoPreset>) {
        val json = JSONArray()
        presets.forEach { preset ->
            json.put(JSONObject()
                .put("id", preset.id)
                .put("name", preset.name)
                .put("style", preset.style.name)
                .put("amount", preset.defaultAmount)
                .put("recipe", preset.recipe.toJson()))
        }
        preferences.edit().putString("custom_presets", json.toString()).apply()
    }
}
