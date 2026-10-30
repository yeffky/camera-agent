package com.example.cameraagent

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale

class OpenAiCompatibleClient {
    fun complete(config: AgentApiConfig, apiKey: String, requestBody: JSONObject): String {
        val endpoint = "${config.baseUrl.trimEnd('/')}/${config.chatCompletionsPath.trimStart('/')}"
        val url = URL(endpoint)
        require(url.protocol.equals("https", ignoreCase = true) && !url.host.isNullOrBlank()) {
            "API 地址必须使用 HTTPS"
        }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val requestBytes = requestBody.toString().toByteArray(StandardCharsets.UTF_8)
            connection.outputStream.use { it.write(requestBytes) }
            val status = connection.responseCode
            if (status !in 200..299) throw CloudApiException("云端请求失败（HTTP $status）")
            val responseBytes = connection.inputStream.use(::readLimited)
            val responseJson = JSONObject(String(responseBytes, StandardCharsets.UTF_8))
            val choices = responseJson.optJSONArray("choices")
                ?: throw CloudApiException("云端响应缺少 choices 字段")
            if (choices.length() == 0) throw CloudApiException("云端没有返回建议内容")
            val message = choices.optJSONObject(0)?.optJSONObject("message")
                ?: throw CloudApiException("云端响应缺少 message 字段")
            message.optString("content").takeIf { it.isNotBlank() }
                ?: throw CloudApiException("云端返回了空内容")
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RESPONSE_BYTES) throw CloudApiException("云端响应超过大小限制")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 25_000
        const val MAX_RESPONSE_BYTES = 64 * 1024
        const val BUFFER_SIZE = 4 * 1024
    }
}

class CloudApiException(message: String) : Exception(message)

data class CameraAdviceAction(
    val kind: String,
    val numberValue: Double? = null,
    val textValue: String? = null,
)

data class CameraAdvice(
    val summary: String,
    val rationale: String,
    val actions: List<CameraAdviceAction>,
)

object CameraAdviceParser {
    fun parse(response: String): CameraAdvice {
        val content = response.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val json = runCatching { JSONObject(content) }
            .getOrElse { throw CloudApiException("无法解析云端建议 JSON") }
        val rawActions = json.optJSONArray("suggestions")
            ?: throw CloudApiException("云端建议缺少 suggestions 数组")
        if (rawActions.length() > MAX_ACTIONS) throw CloudApiException("云端建议项过多")
        val actions = (0 until rawActions.length()).map { index ->
            val item = rawActions.optJSONObject(index)
                ?: throw CloudApiException("云端建议格式无效")
            val kind = item.optString("kind")
            val value = item.opt("value")
            when (kind) {
                "exposure_ev", "zoom_ratio", "preset_strength" -> {
                    val number = (value as? Number)?.toDouble()
                        ?: throw CloudApiException("云端参数值不是数字")
                    if (!number.isFinite()) throw CloudApiException("云端参数值无效")
                    if (kind == "zoom_ratio" && number <= 0.0) {
                        throw CloudApiException("云端变焦倍率必须大于 0")
                    }
                    if (kind == "preset_strength" && number !in 0.0..100.0) {
                        throw CloudApiException("云端预设强度必须在 0 到 100 之间")
                    }
                    CameraAdviceAction(kind, numberValue = number)
                }
                "preset_id" -> {
                    val id = (value as? String)?.trim()
                        ?: throw CloudApiException("云端预设建议格式无效")
                    if (PhotoPresets.find(id) == null) throw CloudApiException("云端返回了不支持的风格预设")
                    CameraAdviceAction(kind, textValue = id)
                }
                "color_style" -> {
                    val style = (value as? String)?.uppercase(Locale.US)
                        ?: throw CloudApiException("云端影调建议格式无效")
                    if (style !in COLOR_STYLES) throw CloudApiException("云端返回了不支持的影调")
                    CameraAdviceAction(kind, textValue = style)
                }
                "composition" -> {
                    val hint = (value as? String)?.trim()?.take(MAX_TEXT_LENGTH)
                        ?: throw CloudApiException("云端构图建议格式无效")
                    CameraAdviceAction(kind, textValue = hint)
                }
                else -> throw CloudApiException("云端返回了不支持的建议类型")
            }
        }
        return CameraAdvice(
            summary = json.optString("summary", "拍摄建议").take(MAX_TEXT_LENGTH),
            rationale = json.optString("rationale", "").take(MAX_RATIONALE_LENGTH),
            actions = actions,
        )
    }

    private const val MAX_ACTIONS = 8
    private const val MAX_TEXT_LENGTH = 200
    private const val MAX_RATIONALE_LENGTH = 400
    private val COLOR_STYLES = ColorStyle.values().map { it.name }.toSet()
}
