package io.github.ctxinf.cameraagent.camera

import android.graphics.ImageFormat
import android.view.Surface
import io.github.ctxinf.cameraagent.domain.CameraCapabilities
import io.github.ctxinf.cameraagent.domain.CameraConfig
import io.github.ctxinf.cameraagent.domain.CameraUnavailableException
import io.github.ctxinf.cameraagent.domain.FpsRange
import io.github.ctxinf.cameraagent.domain.Size
import kotlin.math.abs

interface CaptureSessionPlanner {
    fun plan(
        capabilities: List<CameraCapabilities>,
        config: CameraConfig,
        previewSurface: Surface? = null,
    ): CaptureSessionPlan
}

class DefaultCaptureSessionPlanner : CaptureSessionPlanner {
    override fun plan(
        capabilities: List<CameraCapabilities>,
        config: CameraConfig,
        previewSurface: Surface?,
    ): CaptureSessionPlan {
        if (capabilities.isEmpty()) {
            throw CameraUnavailableException("No camera devices available")
        }

        // 1. Select camera matching requested lens facing, or fallback to first available
        val matchedCapability = capabilities.firstOrNull { it.facing == config.facing }
            ?: capabilities.first()

        // 2. Select optimal output size
        val selectedSize = selectOptimalSize(
            supportedSizes = matchedCapability.supportedSizes,
            targetWidth = config.targetWidth,
            targetHeight = config.targetHeight,
        )

        // 3. Select optimal FPS range
        val selectedFpsRange = selectOptimalFpsRange(
            supportedRanges = matchedCapability.supportedFpsRanges,
            targetFps = config.targetFps,
        )

        // 4. Calculate output rotation
        val rotation = ((matchedCapability.sensorOrientation + config.rotationOffset) % 360 + 360) % 360

        return CaptureSessionPlan(
            cameraId = matchedCapability.cameraId,
            outputSize = selectedSize,
            imageFormat = ImageFormat.YUV_420_888,
            fpsRange = selectedFpsRange,
            rotation = rotation,
            previewSurface = previewSurface,
        )
    }

    private fun selectOptimalSize(
        supportedSizes: List<Size>,
        targetWidth: Int,
        targetHeight: Int,
    ): Size {
        if (supportedSizes.isEmpty()) {
            return Size(targetWidth, targetHeight)
        }

        // Check for exact match
        val exactMatch = supportedSizes.firstOrNull { it.width == targetWidth && it.height == targetHeight }
        if (exactMatch != null) return exactMatch

        val targetAspectRatio = targetWidth.toDouble() / targetHeight.coerceAtLeast(1)
        val targetPixels = targetWidth * targetHeight

        // Pick the size with closest aspect ratio, then closest pixel count
        return supportedSizes.minWithOrNull(
            compareBy<Size> { size ->
                val ratio = size.width.toDouble() / size.height.coerceAtLeast(1)
                abs(ratio - targetAspectRatio)
            }.thenBy { size ->
                abs(size.width * size.height - targetPixels)
            }
        ) ?: supportedSizes.first()
    }

    private fun selectOptimalFpsRange(
        supportedRanges: List<FpsRange>,
        targetFps: Int,
    ): FpsRange {
        if (supportedRanges.isEmpty()) {
            return FpsRange(targetFps, targetFps)
        }

        // 1. Check if an exact fixed range exists (targetFps, targetFps)
        val exactFixed = supportedRanges.firstOrNull { it.minFps == targetFps && it.maxFps == targetFps }
        if (exactFixed != null) return exactFixed

        // 2. Filter ranges that contain targetFps
        val containingRanges = supportedRanges.filter { targetFps in it.minFps..it.maxFps }
        if (containingRanges.isNotEmpty()) {
            // Prefer range with smallest span (most stable frame rate), then closest maxFps to targetFps
            return containingRanges.minWithOrNull(
                compareBy<FpsRange> { it.maxFps - it.minFps }
                    .thenBy { abs(it.maxFps - targetFps) }
            ) ?: containingRanges.first()
        }

        // 3. Fallback: range with maxFps closest to targetFps
        return supportedRanges.minByOrNull { abs(it.maxFps - targetFps) } ?: supportedRanges.first()
    }
}
