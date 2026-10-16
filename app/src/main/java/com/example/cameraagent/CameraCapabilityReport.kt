package com.example.cameraagent

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.ZoomState
import java.util.Locale

object CameraCapabilityReport {
    fun create(camera: Camera, zoomState: ZoomState?): String {
        val exposure = camera.cameraInfo.exposureState
        val exposureStep = exposure.exposureCompensationStep.toFloat()
        val exposureRange = exposure.exposureCompensationRange
        val camera2Info = Camera2CameraInfo.from(camera.cameraInfo)

        val hardwareLevel = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL,
        )
        val capabilities = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
        )?.toSet().orEmpty()
        val focusModes = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES,
        )?.mapModes(FOCUS_MODES).orEmpty()
        val exposureModes = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES,
        )?.mapModes(EXPOSURE_MODES).orEmpty()
        val whiteBalanceModes = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES,
        )?.mapModes(WHITE_BALANCE_MODES).orEmpty()
        val whiteBalanceModeIds = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES,
        )?.toSet().orEmpty()
        val minimumFocusDistance = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE,
        )
        val streamMap = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
        )
        val jpegSizes = streamMap?.getOutputSizes(ImageFormat.JPEG)
            ?.sortedByDescending { it.width.toLong() * it.height }
            ?.take(MAX_REPORTED_JPEG_SIZES)
            ?.joinToString { "${it.width}×${it.height}" }
            ?: "设备未报告"

        val zoomDescription = zoomState?.let { state ->
            if (state.maxZoomRatio > state.minZoomRatio) {
                "${format(state.minZoomRatio)}×–${format(state.maxZoomRatio)}×（当前 ${format(state.zoomRatio)}×）"
            } else {
                "不支持（设备报告范围 ${format(state.minZoomRatio)}×–${format(state.maxZoomRatio)}×）"
            }
        } ?: "读取中"

        val minExposureEv = exposureRange.lower * exposureStep
        val maxExposureEv = exposureRange.upper * exposureStep
        return buildString {
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android：${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
            appendLine("Camera ID：${camera2Info.cameraId}")
            appendLine("Camera2 硬件等级：${hardwareLevelName(hardwareLevel)}")
            appendLine("闪光灯：${if (camera.cameraInfo.hasFlashUnit()) "支持" else "不支持"}")
            appendLine("变焦：$zoomDescription")
            appendLine("曝光补偿：${if (exposure.isExposureCompensationSupported) "支持" else "不支持"}")
            if (exposure.isExposureCompensationSupported) {
                appendLine(
                    "  索引 ${exposureRange.lower}…${exposureRange.upper}，步长 ${format(exposureStep)} EV，范围 ${format(minExposureEv)}…${format(maxExposureEv)} EV",
                )
            }
            appendLine(
                "手动曝光/ISO：${if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities) "支持" else "不支持或未报告"}",
            )
            appendLine(
                "手动白平衡：${if (CameraCharacteristics.CONTROL_AWB_MODE_OFF in whiteBalanceModeIds && CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in capabilities) "支持" else "不支持或未报告"}",
            )
            appendLine(
                "手动对焦：${if (minimumFocusDistance != null && minimumFocusDistance > 0f) "支持（最近对焦距离 ${format(minimumFocusDistance)} 屈光度）" else "不支持或固定焦距"}",
            )
            appendLine("自动对焦模式：${focusModes.ifEmpty { listOf("设备未报告") }.joinToString()}")
            appendLine("自动曝光模式：${exposureModes.ifEmpty { listOf("设备未报告") }.joinToString()}")
            appendLine("白平衡模式：${whiteBalanceModes.ifEmpty { listOf("设备未报告") }.joinToString()}")
            appendLine("JPEG 输出尺寸（最大 5 项）：$jpegSizes")
        }
    }

    private fun IntArray.mapModes(names: Map<Int, String>): List<String> =
        map { value -> names[value] ?: "未知($value)" }

    private fun hardwareLevelName(level: Int?): String = when (level) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        null -> "设备未报告"
        else -> "未知($level)"
    }

    private fun format(value: Float): String = String.format(Locale.US, "%.2f", value)

    private const val MAX_REPORTED_JPEG_SIZES = 5

    private val FOCUS_MODES = mapOf(
        CameraCharacteristics.CONTROL_AF_MODE_OFF to "OFF",
        CameraCharacteristics.CONTROL_AF_MODE_AUTO to "AUTO",
        CameraCharacteristics.CONTROL_AF_MODE_MACRO to "MACRO",
        CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO to "CONTINUOUS_VIDEO",
        CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE to "CONTINUOUS_PICTURE",
        CameraCharacteristics.CONTROL_AF_MODE_EDOF to "EDOF",
    )

    private val EXPOSURE_MODES = mapOf(
        CameraCharacteristics.CONTROL_AE_MODE_OFF to "OFF（手动）",
        CameraCharacteristics.CONTROL_AE_MODE_ON to "ON",
        CameraCharacteristics.CONTROL_AE_MODE_ON_AUTO_FLASH to "AUTO_FLASH",
        CameraCharacteristics.CONTROL_AE_MODE_ON_ALWAYS_FLASH to "ALWAYS_FLASH",
        CameraCharacteristics.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE to "AUTO_FLASH_REDEYE",
        CameraCharacteristics.CONTROL_AE_MODE_ON_EXTERNAL_FLASH to "EXTERNAL_FLASH",
    )

    private val WHITE_BALANCE_MODES = mapOf(
        CameraCharacteristics.CONTROL_AWB_MODE_OFF to "OFF（手动）",
        CameraCharacteristics.CONTROL_AWB_MODE_AUTO to "AUTO",
        CameraCharacteristics.CONTROL_AWB_MODE_INCANDESCENT to "INCANDESCENT",
        CameraCharacteristics.CONTROL_AWB_MODE_FLUORESCENT to "FLUORESCENT",
        CameraCharacteristics.CONTROL_AWB_MODE_WARM_FLUORESCENT to "WARM_FLUORESCENT",
        CameraCharacteristics.CONTROL_AWB_MODE_DAYLIGHT to "DAYLIGHT",
        CameraCharacteristics.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "CLOUDY_DAYLIGHT",
        CameraCharacteristics.CONTROL_AWB_MODE_TWILIGHT to "TWILIGHT",
        CameraCharacteristics.CONTROL_AWB_MODE_SHADE to "SHADE",
    )
}
