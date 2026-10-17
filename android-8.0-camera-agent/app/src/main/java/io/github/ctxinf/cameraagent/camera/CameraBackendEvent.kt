package io.github.ctxinf.cameraagent.camera

import io.github.ctxinf.cameraagent.domain.Frame

sealed interface CameraBackendEvent {
    data class Opened(val cameraId: String) : CameraBackendEvent
    data class FrameAvailable(
        val frame: Frame,
        val isAeConverged: Boolean = true,
    ) : CameraBackendEvent
    data class Error(val message: String, val isFatal: Boolean) : CameraBackendEvent
    data object Disconnected : CameraBackendEvent
    data object Closed : CameraBackendEvent
}
