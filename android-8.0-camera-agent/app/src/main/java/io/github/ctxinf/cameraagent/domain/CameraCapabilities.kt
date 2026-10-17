package io.github.ctxinf.cameraagent.domain

import kotlinx.serialization.Serializable

@Serializable
data class Size(
    val width: Int,
    val height: Int,
)

@Serializable
data class FpsRange(
    val minFps: Int,
    val maxFps: Int,
)

@Serializable
data class CameraCapabilities(
    val cameraId: String,
    val facing: LensFacing,
    val sensorOrientation: Int,
    val hardwareLevel: String,
    val supportedSizes: List<Size>,
    val supportedFpsRanges: List<FpsRange>,
)
