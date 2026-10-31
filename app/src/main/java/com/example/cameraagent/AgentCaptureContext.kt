package com.example.cameraagent

import java.util.UUID

data class CaptureIntentOption(val id: String, val label: String)

object CaptureIntentCatalog {
    fun options(sceneName: String?): List<CaptureIntentOption> = when (sceneFamily(sceneName)) {
        "portrait" -> listOf(
            CaptureIntentOption("portrait_subject", "突出人物"),
            CaptureIntentOption("portrait_environment", "保留环境"),
        )
        "travel" -> listOf(
            CaptureIntentOption("travel_subject", "突出人物或地标"),
            CaptureIntentOption("travel_environment", "展示周围环境"),
        )
        "food" -> listOf(
            CaptureIntentOption("food_dish", "突出菜品"),
            CaptureIntentOption("food_table", "保留餐桌氛围"),
        )
        else -> emptyList()
    }

    fun sceneFamily(sceneName: String?): String = when {
        sceneName == null -> "unknown"
        sceneName.contains("人像") || sceneName.contains("人物") -> "portrait"
        sceneName.contains("美食") || sceneName.contains("食物") -> "food"
        sceneName.contains("风景") || sceneName.contains("旅行") || sceneName.contains("街") -> "travel"
        sceneName.contains("通用") -> "travel"
        else -> "unknown"
    }

    fun find(sceneName: String?, id: String?): CaptureIntentOption? =
        options(sceneName).firstOrNull { it.id == id }
}

data class AgentCaptureTurnContext(
    val requestId: String,
    val intentId: String?,
    val sceneName: String,
    val summary: String,
    val subjectType: String,
    val peopleCountClass: String,
    val appliedActions: List<String>,
    val skippedActions: List<String>,
    val failedActions: List<String>,
    val completedAtMs: Long,
)

data class AgentCaptureContext(
    val sessionId: String = UUID.randomUUID().toString(),
    var selectedIntentId: String? = null,
    var previousTurn: AgentCaptureTurnContext? = null,
)

data class ActiveAgentRequest(
    val requestId: String,
    val sceneName: String?,
    val visualSignature: Long?,
    val faceBounds: android.graphics.RectF?,
    val intentId: String?,
    val selectedPresetId: String,
    val styleLocked: Boolean,
    val startedAtMs: Long,
    val cancellation: AgentRequestCancellation,
)
