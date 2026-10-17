package io.github.ctxinf.cameraagent.application

import io.github.ctxinf.cameraagent.domain.CameraController
import io.github.ctxinf.cameraagent.domain.Snapshot

class CaptureSnapshot(
    private val cameraController: CameraController,
) {
    suspend operator fun invoke(): Snapshot {
        return cameraController.takeSnapshot()
    }
}
