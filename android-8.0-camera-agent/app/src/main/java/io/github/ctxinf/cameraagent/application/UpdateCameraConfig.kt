package io.github.ctxinf.cameraagent.application

import io.github.ctxinf.cameraagent.domain.CameraConfig
import io.github.ctxinf.cameraagent.domain.CameraController

class UpdateCameraConfig(
    private val cameraController: CameraController,
) {
    suspend operator fun invoke(config: CameraConfig): CameraConfig {
        return cameraController.updateConfig(config)
    }
}
