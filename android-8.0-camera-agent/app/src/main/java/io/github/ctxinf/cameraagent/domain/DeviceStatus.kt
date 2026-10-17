package io.github.ctxinf.cameraagent.domain

import kotlinx.serialization.Serializable

@Serializable
data class DeviceStatus(
    val cameraState: String,
    val cameraId: String?,
    val facing: LensFacing?,
    val width: Int?,
    val height: Int?,
    val fps: Int?,
    val jpegQuality: Int?,
    val activeStreams: Int,
    val lastFrameTimestampNs: Long?,
    val lastError: String?,
    val cameraEnabled: Boolean,
    val androidSdk: Int,
    val hardwareLevel: String?,
)
