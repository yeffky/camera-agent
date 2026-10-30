package com.example.cameraagent

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale

class OpenAiCompatibleClient {
    fun complete(
        config: AgentApiConfig,
        apiKey: String,
        requestBody: JSONObject,
        cancellation: AgentRequestCancellation? = null,
    ): String {
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
        cancellation?.attach(connection)
        return try {
            cancellation?.throwIfCancelled()
            val requestBytes = requestBody.toString().toByteArray(StandardCharsets.UTF_8)
            connection.outputStream.use { it.write(requestBytes) }
            cancellation?.throwIfCancelled()
            val status = connection.responseCode
            if (status !in 200..299) throw CloudApiException("云端请求失败（HTTP $status）")
            val responseBytes = connection.inputStream.use(::readLimited)
            cancellation?.throwIfCancelled()
            val responseJson = JSONObject(String(responseBytes, StandardCharsets.UTF_8))
            val choices = responseJson.optJSONArray("choices")
                ?: throw CloudApiException("云端响应缺少 choices 字段")
            if (choices.length() == 0) throw CloudApiException("云端没有返回建议内容")
            val message = choices.optJSONObject(0)?.optJSONObject("message")
                ?: throw CloudApiException("云端响应缺少 message 字段")
            message.optString("content").takeIf { it.isNotBlank() }
                ?: throw CloudApiException("云端返回了空内容")
        } finally {
            cancellation?.detach(connection)
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

class AgentRequestCancellation {
    @Volatile
    private var cancelled = false
    @Volatile
    private var connection: HttpURLConnection? = null

    fun attach(value: HttpURLConnection) {
        connection = value
        if (cancelled) value.disconnect()
    }

    fun detach(value: HttpURLConnection) {
        if (connection === value) connection = null
    }

    fun cancel() {
        cancelled = true
        connection?.disconnect()
    }

    fun isCancelled(): Boolean = cancelled

    fun throwIfCancelled() {
        if (cancelled) throw CloudApiException("请求已取消")
    }
}

class CloudApiException(message: String) : Exception(message)

data class CameraAdviceAction(
    val kind: String,
    val numberValue: Double? = null,
    val textValue: String? = null,
)

data class CameraAdvice(
    val schemaVersion: Int,
    val requestId: String,
    val summary: String,
    val rationale: String,
    val actions: List<CameraAdviceAction>,
    val cropRect: NormalizedCropRect? = null,
    val subjectType: String,
    val peopleCountClass: String,
    val subjectConfidence: Float,
    val cropConfidence: Float,
    val reframeTip: String?,
    val subjectRegions: List<NormalizedCropRect>,
    val cropRejected: Boolean,
)

data class NormalizedCropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Float get() = width * height
}

object CameraAdviceParser {
    fun parse(response: String, expectedRequestId: String, styleLocked: Boolean): CameraAdvice {
        val content = response.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val json = runCatching { JSONObject(content) }
            .getOrElse { throw CloudApiException("无法解析云端建议 JSON") }
        val schemaVersion = json.optInt("schema_version", -1)
        if (schemaVersion != 2) throw CloudApiException("云端建议版本不受支持")
        val requestId = json.optString("request_id")
        if (requestId != expectedRequestId) throw CloudApiException("云端响应与当前请求不匹配")
        val subject = json.optJSONObject("subject") ?: throw CloudApiException("云端建议缺少主体判断")
        val subjectType = subject.optString("type")
        val peopleCountClass = subject.optString("people_count_class")
        val subjectConfidence = confidence(subject, "confidence")
        val cropConfidence = confidence(json, "crop_confidence")
        val reframeTip = json.optString("reframe_tip").takeIf { it.isNotBlank() }?.take(MAX_TEXT_LENGTH)
        if (subjectType !in SUBJECT_TYPES || peopleCountClass !in PEOPLE_COUNT_CLASSES) {
            throw CloudApiException("云端主体判断格式无效")
        }
        val rawRegions = subject.optJSONArray("regions")
            ?: throw CloudApiException("云端建议缺少主体区域")
        val subjectRegions = (0 until rawRegions.length()).map { index ->
            rawRegions.optJSONObject(index)?.let(::parseSubjectRegion)
                ?: throw CloudApiException("云端主体区域格式无效")
        }
        if (subjectRegions.size > MAX_SUBJECT_REGIONS) throw CloudApiException("云端主体区域过多")
        val rawActions = json.optJSONArray("suggestions")
            ?: throw CloudApiException("云端建议缺少 suggestions 数组")
        val hasCrop = json.opt("crop_rect")?.let { it != JSONObject.NULL } == true
        if (rawActions.length() + (if (hasCrop) 1 else 0) > MAX_ACTIONS) {
            throw CloudApiException("云端建议项过多")
        }
        val actions = (0 until rawActions.length()).map { index ->
            val item = rawActions.optJSONObject(index)
                ?: throw CloudApiException("云端建议格式无效")
            val kind = item.optString("kind")
            if (styleLocked && kind in STYLE_ACTIONS) throw CloudApiException("请求已锁定用户预设，云端不能更改风格")
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
        val rawCrop = json.opt("crop_rect")
        val cropRequested = json.opt("crop_rect")?.let { it != JSONObject.NULL } == true
        val cropRect = when {
            rawCrop == null || rawCrop == JSONObject.NULL -> null
            rawCrop is JSONObject -> {
                if (subjectType == "unknown" || peopleCountClass == "unknown" ||
                    subjectConfidence < MIN_CONFIDENCE || cropConfidence < MIN_CONFIDENCE ||
                    subjectRegions.isEmpty() || reframeTip != null
                ) {
                    null
                } else {
                    val rect = preserveSourceAspect(parseCropRect(rawCrop))
                    val retainsAllSubjects = subjectRegions.all { region ->
                        region.left >= rect.left && region.top >= rect.top &&
                            region.right <= rect.right && region.bottom <= rect.bottom
                    }
                    rect.takeIf { rect.area >= MIN_CROP_RETAINED_AREA && retainsAllSubjects }
                }
            }
            else -> throw CloudApiException("云端裁剪建议格式无效")
        }
        return CameraAdvice(
            schemaVersion = schemaVersion,
            requestId = requestId,
            summary = json.optString("summary", "拍摄建议").take(MAX_TEXT_LENGTH),
            rationale = json.optString("rationale", "").take(MAX_RATIONALE_LENGTH),
            actions = actions,
            cropRect = cropRect,
            subjectType = subjectType,
            peopleCountClass = peopleCountClass,
            subjectConfidence = subjectConfidence,
            cropConfidence = cropConfidence,
            reframeTip = reframeTip,
            subjectRegions = subjectRegions,
            cropRejected = cropRequested && cropRect == null,
        )
    }

    private fun preserveSourceAspect(rect: NormalizedCropRect): NormalizedCropRect {
        val side = maxOf(rect.width, rect.height).coerceAtMost(1f)
        val left = (rect.centerX - side / 2f).coerceIn(0f, 1f - side)
        val top = (rect.centerY - side / 2f).coerceIn(0f, 1f - side)
        return NormalizedCropRect(left, top, left + side, top + side)
    }

    private fun confidence(json: JSONObject, name: String): Float {
        val value = json.optDouble(name, Double.NaN)
        if (!value.isFinite() || value !in 0.0..1.0) throw CloudApiException("云端置信度无效")
        return value.toFloat()
    }

    private fun parseCropRect(json: JSONObject): NormalizedCropRect {
        val rect = NormalizedCropRect(
            left = coordinate(json, "left"),
            top = coordinate(json, "top"),
            right = coordinate(json, "right"),
            bottom = coordinate(json, "bottom"),
        )
        if (rect.width < MIN_CROP_EDGE || rect.height < MIN_CROP_EDGE || rect.area < MIN_CROP_AREA) {
            throw CloudApiException("云端裁剪区域过小")
        }
        return rect
    }

    private fun parseSubjectRegion(json: JSONObject): NormalizedCropRect {
        val rect = NormalizedCropRect(
            left = coordinate(json, "left"),
            top = coordinate(json, "top"),
            right = coordinate(json, "right"),
            bottom = coordinate(json, "bottom"),
        )
        if (rect.width <= 0f || rect.height <= 0f) throw CloudApiException("云端主体区域无效")
        return rect
    }

    private fun coordinate(json: JSONObject, name: String): Float {
        val value = json.optDouble(name, Double.NaN)
        if (!value.isFinite() || value !in 0.0..1.0) throw CloudApiException("云端坐标无效")
        return value.toFloat()
    }

    private const val MAX_ACTIONS = 8
    private const val MAX_TEXT_LENGTH = 200
    private const val MAX_RATIONALE_LENGTH = 400
    private const val MIN_CROP_EDGE = 0.15f
    private const val MIN_CROP_AREA = 0.08f
    private const val MIN_CROP_RETAINED_AREA = 0.70f
    private const val MIN_CONFIDENCE = 0.65f
    private const val MAX_SUBJECT_REGIONS = 20
    private val SUBJECT_TYPES = setOf("person", "food", "object", "landscape", "mixed", "unknown")
    private val PEOPLE_COUNT_CLASSES = setOf("single", "pair", "group", "crowded", "not_applicable", "unknown")
    private val STYLE_ACTIONS = setOf("preset_id", "preset_strength", "color_style")
    private val COLOR_STYLES = ColorStyle.values().map { it.name }.toSet()
}
