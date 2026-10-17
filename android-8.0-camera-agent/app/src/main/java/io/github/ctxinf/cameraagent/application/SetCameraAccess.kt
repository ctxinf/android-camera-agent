package io.github.ctxinf.cameraagent.application

import io.github.ctxinf.cameraagent.domain.CameraController

class SetCameraAccess(
    private val cameraController: CameraController,
) {
    suspend operator fun invoke(enabled: Boolean) {
        cameraController.setEnabled(enabled)
    }
}
