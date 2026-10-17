package io.github.ctxinf.cameraagent.application

import io.github.ctxinf.cameraagent.domain.CameraController
import io.github.ctxinf.cameraagent.domain.Frame
import kotlinx.coroutines.flow.Flow

class OpenFrameStream(
    private val cameraController: CameraController,
) {
    suspend operator fun invoke(token: String): Flow<Frame> {
        return cameraController.openStream(token)
    }

    suspend fun close(token: String) {
        cameraController.closeStream(token)
    }
}
