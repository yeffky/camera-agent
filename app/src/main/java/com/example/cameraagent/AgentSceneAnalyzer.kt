package com.example.cameraagent

import android.graphics.RectF
import android.media.Image
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.util.Locale
import kotlin.math.max

enum class ColorStyle(
    val displayName: String,
    val previewTint: Int,
    val matrix: FloatArray,
) {
    NATURAL("自然", 0x00000000, floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )),
    WARM("暖调", 0x16ff9d55, floatArrayOf(
        1.0f, 0f, 0f, 0f, 7f,
        0f, 1.0f, 0f, 0f, 1f,
        0f, 0f, 1.0f, 0f, -7f,
        0f, 0f, 0f, 1.0f, 0f,
    )),
    SOFT("柔和", 0x10ffe8d6, floatArrayOf(
        0.91f, 0.045f, 0.045f, 0f, 5f,
        0.065f, 0.87f, 0.065f, 0f, 5f,
        0.025f, 0.025f, 0.95f, 0f, 5f,
        0f, 0f, 0f, 1f, 0f,
    )),
    VIVID("鲜明", 0x0affffff, floatArrayOf(
        1.14166f, -0.1287f, -0.01296f, 0f, 0f,
        -0.03834f, 1.0513f, -0.01296f, 0f, 0f,
        -0.03834f, -0.1287f, 1.16704f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )),
    COOL("冷调", 0x163d8dce, floatArrayOf(
        1f, 0f, 0f, 0f, -4f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 7f,
        0f, 0f, 0f, 1f, 0f,
    ));

}

data class SceneAssessment(
    val sceneName: String,
    val confidence: Float,
    val compositionHint: String,
    val faceBounds: RectF?,
    val imageAspectRatio: Float,
    val recommendedColorStyle: ColorStyle,
    val exposureCompensationOffset: Int,
)

class AgentSceneAnalyzer(
    private val onAssessment: (SceneAssessment) -> Unit,
) : ImageAnalysis.Analyzer, AutoCloseable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val labeler: ImageLabeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
    private val faceDetector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build(),
    )

    @Volatile
    private var processing = false
    @Volatile
    private var closed = false
    private var lastAnalysisAtMs = 0L

    override fun analyze(imageProxy: ImageProxy) {
        if (closed) {
            imageProxy.close()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (processing || now - lastAnalysisAtMs < ANALYSIS_INTERVAL_MS) {
            imageProxy.close()
            return
        }

        val mediaImage: Image = imageProxy.image ?: run {
            imageProxy.close()
            return
        }
        val inputImage = try {
            InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        } catch (_: Exception) {
            imageProxy.close()
            return
        }

        lastAnalysisAtMs = now
        processing = true
        val brightness = estimateLuma(imageProxy)
        val rotated = imageProxy.imageInfo.rotationDegrees % 180 != 0
        val imageWidth = if (rotated) imageProxy.height else imageProxy.width
        val imageHeight = if (rotated) imageProxy.width else imageProxy.height
        val imageAspectRatio = imageWidth.toFloat() / max(imageHeight, 1)

        try {
            labeler.process(inputImage).addOnCompleteListener { labelTask ->
                try {
                    val labels = if (labelTask.isSuccessful) {
                        labelTask.result.orEmpty().map { it.text to it.confidence }
                    } else {
                        emptyList()
                    }
                    val hasPerson = labels.any { (name, score) -> isPersonLabel(name) && score >= PERSON_LABEL_THRESHOLD }
                    if (!hasPerson) {
                        publish(
                            imageProxy,
                            createAssessment(labels, brightness, null, imageWidth, imageHeight, imageAspectRatio),
                        )
                        return@addOnCompleteListener
                    }

                    faceDetector.process(inputImage).addOnCompleteListener { faceTask ->
                        val face = if (faceTask.isSuccessful) {
                            faceTask.result.orEmpty().maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                        } else {
                            null
                        }
                        publish(
                            imageProxy,
                            createAssessment(labels, brightness, face, imageWidth, imageHeight, imageAspectRatio),
                        )
                    }
                } catch (_: Exception) {
                    publish(
                        imageProxy,
                        createAssessment(emptyList(), brightness, null, imageWidth, imageHeight, imageAspectRatio),
                    )
                }
            }
        } catch (_: Exception) {
            publish(
                imageProxy,
                createAssessment(emptyList(), brightness, null, imageWidth, imageHeight, imageAspectRatio),
            )
        }
    }

    private fun createAssessment(
        labels: List<Pair<String, Float>>,
        brightness: Float,
        face: Face?,
        imageWidth: Int,
        imageHeight: Int,
        imageAspectRatio: Float,
    ): SceneAssessment {
        val personConfidence = labels.categoryConfidence(::isPersonLabel)
        val foodConfidence = labels.categoryConfidence(::isFoodLabel)
        val landscapeConfidence = labels.categoryConfidence(::isLandscapeLabel)
        val isPortrait = face != null || personConfidence >= PERSON_LABEL_THRESHOLD
        val isFood = foodConfidence >= SCENE_LABEL_THRESHOLD
        val isLandscape = landscapeConfidence >= SCENE_LABEL_THRESHOLD
        val scene = when {
            isPortrait -> "人像"
            isFood -> "美食"
            brightness < LOW_LIGHT_THRESHOLD -> "低照度"
            isLandscape -> "风景"
            else -> "通用场景"
        }
        val style = when (scene) {
            "人像" -> ColorStyle.SOFT
            "美食" -> ColorStyle.WARM
            "风景" -> ColorStyle.VIVID
            "低照度" -> ColorStyle.COOL
            else -> ColorStyle.NATURAL
        }
        val normalizedFace = face?.boundingBox?.let { bounds ->
            RectF(
                (bounds.left.toFloat() / imageWidth).coerceIn(0f, 1f),
                (bounds.top.toFloat() / imageHeight).coerceIn(0f, 1f),
                (bounds.right.toFloat() / imageWidth).coerceIn(0f, 1f),
                (bounds.bottom.toFloat() / imageHeight).coerceIn(0f, 1f),
            )
        }
        val faceCenterX = normalizedFace?.centerX() ?: 0.5f
        val composition = when {
            normalizedFace != null && faceCenterX < 0.38f -> "人物偏左，右侧可留出视线空间。"
            normalizedFace != null && faceCenterX > 0.62f -> "人物偏右，可尝试向左三分线调整。"
            normalizedFace != null -> "尝试将人物面部靠近上方三分线。"
            scene == "风景" -> "保持地平线水平，可放在上方或下方三分线。"
            scene == "美食" -> "让主体靠近三分线交点，减少边缘杂物。"
            scene == "低照度" -> "保持手机稳定，避免提高数码变焦。"
            scene == "人像" -> "尝试将人物放在左或右三分线附近。"
            else -> "让主要主体靠近三分线交点。"
        }
        val exposureOffset = when {
            brightness < LOW_LIGHT_THRESHOLD -> 1
            brightness > HIGH_LIGHT_THRESHOLD -> -1
            else -> 0
        }
        val confidence = when {
            isPortrait -> face?.let { FACE_DETECTION_CONFIDENCE } ?: personConfidence
            isFood -> foodConfidence
            isLandscape -> landscapeConfidence
            brightness < LOW_LIGHT_THRESHOLD -> LOW_LIGHT_HEURISTIC_CONFIDENCE
            else -> 0f
        }
        return SceneAssessment(
            sceneName = scene,
            confidence = confidence,
            compositionHint = composition,
            faceBounds = normalizedFace,
            imageAspectRatio = imageAspectRatio,
            recommendedColorStyle = style,
            exposureCompensationOffset = exposureOffset,
        )
    }

    private fun estimateLuma(imageProxy: ImageProxy): Float {
        val plane = imageProxy.planes.firstOrNull() ?: return 128f
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val sampleStep = max(imageProxy.width / LUMA_SAMPLE_GRID, 1)
        var sum = 0L
        var count = 0
        var y = 0
        while (y < imageProxy.height) {
            var x = 0
            while (x < imageProxy.width) {
                val index = y * rowStride + x * pixelStride
                if (index < buffer.limit()) {
                    sum += buffer.get(index).toInt() and 0xff
                    count++
                }
                x += sampleStep
            }
            y += sampleStep
        }
        return if (count == 0) 128f else sum.toFloat() / count
    }

    private fun publish(imageProxy: ImageProxy, assessment: SceneAssessment) {
        try {
            if (!closed) mainHandler.post { if (!closed) onAssessment(assessment) }
        } finally {
            imageProxy.close()
            processing = false
        }
    }

    override fun close() {
        closed = true
        labeler.close()
        faceDetector.close()
    }

    private fun isPersonLabel(label: String): Boolean {
        val normalized = label.lowercase(Locale.ROOT)
        return normalized.contains("person") || normalized.contains("human") ||
            normalized.contains("portrait") || normalized == "face"
    }

    private fun isFoodLabel(label: String): Boolean {
        val normalized = label.lowercase(Locale.ROOT)
        return normalized.contains("food") || normalized.contains("dish") || normalized.contains("meal")
    }

    private fun isLandscapeLabel(label: String): Boolean {
        val normalized = label.lowercase(Locale.ROOT)
        return normalized.contains("landscape") || normalized.contains("mountain") ||
            normalized.contains("beach") || normalized.contains("nature") || normalized.contains("sky") ||
            normalized.contains("outdoor") || normalized.contains("plant")
    }

    private fun List<Pair<String, Float>>.categoryConfidence(matches: (String) -> Boolean): Float =
        filter { (name, _) -> matches(name) }
            .maxOfOrNull { (_, confidence) -> confidence }
            ?: 0f

    private companion object {
        const val ANALYSIS_INTERVAL_MS = 900L
        const val PERSON_LABEL_THRESHOLD = 0.35f
        const val SCENE_LABEL_THRESHOLD = 0.5f
        const val FACE_DETECTION_CONFIDENCE = 0.92f
        const val LOW_LIGHT_HEURISTIC_CONFIDENCE = 0.7f
        const val LOW_LIGHT_THRESHOLD = 55f
        const val HIGH_LIGHT_THRESHOLD = 205f
        const val LUMA_SAMPLE_GRID = 48
    }
}
