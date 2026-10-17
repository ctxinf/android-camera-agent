package io.github.ctxinf.cameraagent.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import io.github.ctxinf.cameraagent.domain.CameraCapabilities
import io.github.ctxinf.cameraagent.domain.FpsRange
import io.github.ctxinf.cameraagent.domain.LensFacing
import io.github.ctxinf.cameraagent.domain.Size
import io.github.ctxinf.cameraagent.infrastructure.logging.AppLog

class Camera2CapabilitiesProbe(
    private val context: Context,
) {
    private val cameraManager: CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    fun probe(): List<CameraCapabilities> {
        val result = mutableListOf<CameraCapabilities>()
        try {
            val cameraIds = cameraManager.cameraIdList
            AppLog.i("Found ${cameraIds.size} camera device(s): ${cameraIds.joinToString()}")

            for (id in cameraIds) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facingInt = chars.get(CameraCharacteristics.LENS_FACING)
                val facing = when (facingInt) {
                    CameraCharacteristics.LENS_FACING_FRONT -> LensFacing.FRONT
                    CameraCharacteristics.LENS_FACING_BACK -> LensFacing.BACK
                    else -> LensFacing.EXTERNAL
                }
                val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                val hwLevelInt = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                val hwLevel = when (hwLevelInt) {
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                    else -> "UNKNOWN($hwLevelInt)"
                }

                val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val yuvSizes = streamMap?.getOutputSizes(ImageFormat.YUV_420_888)?.map {
                    Size(it.width, it.height)
                } ?: emptyList()

                val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.map {
                    FpsRange(it.lower, it.upper)
                } ?: emptyList()

                val capabilities = CameraCapabilities(
                    cameraId = id,
                    facing = facing,
                    sensorOrientation = sensorOrientation,
                    hardwareLevel = hwLevel,
                    supportedSizes = yuvSizes,
                    supportedFpsRanges = fpsRanges,
                )
                result.add(capabilities)

                AppLog.i(
                    "Camera [$id] Facing=$facing Level=$hwLevel SensorOrientation=$sensorOrientation° " +
                        "SupportedYuvSizesCount=${yuvSizes.size} (Top: ${yuvSizes.take(3).joinToString { "${it.width}x${it.height}" }}) " +
                        "FpsRanges=${fpsRanges.joinToString { "[${it.minFps},${it.maxFps}]" }}"
                )
            }
        } catch (e: Exception) {
            AppLog.e("Failed to probe camera capabilities", e)
        }
        return result
    }
}
