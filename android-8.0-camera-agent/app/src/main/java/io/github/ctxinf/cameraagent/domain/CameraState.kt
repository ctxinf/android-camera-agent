package io.github.ctxinf.cameraagent.domain

import kotlinx.serialization.Serializable

@Serializable
sealed interface CameraState {
    @Serializable
    data object Disabled : CameraState

    @Serializable
    data object Closed : CameraState

    @Serializable
    data class Opening(val cameraId: String) : CameraState

    @Serializable
    data class Active(
        val cameraId: String,
        val width: Int,
        val height: Int,
        val activeStreams: Int,
    ) : CameraState

    @Serializable
    data class Retained(
        val cameraId: String,
        val retainReason: String,
    ) : CameraState

    @Serializable
    data object Closing : CameraState

    @Serializable
    data class Failed(
        val reason: String,
        val isRecoverable: Boolean,
    ) : CameraState
}
