package io.github.ctxinf.cameraagent.domain

import kotlinx.serialization.Serializable

enum class LensFacing {
    BACK,
    FRONT,
    EXTERNAL,
}

@Serializable
data class CameraConfig(
    val enabled: Boolean = true,
    val facing: LensFacing = LensFacing.BACK,
    val targetWidth: Int = 1280,
    val targetHeight: Int = 720,
    val targetFps: Int = 15,
    val jpegQuality: Int = 80,
    val rotationOffset: Int = 0,
    val retentionGracePeriodSec: Int = 5,
)
