package io.github.ctxinf.cameraagent.camera

import io.github.ctxinf.cameraagent.domain.CameraCapabilities
import io.github.ctxinf.cameraagent.domain.CameraConfig
import kotlinx.coroutines.flow.Flow

interface CameraBackend {
    suspend fun enumerate(): List<CameraCapabilities>

    suspend fun open(
        cameraId: String,
        plan: CaptureSessionPlan,
    )

    suspend fun startRepeating()
    suspend fun stopRepeating()
    suspend fun updateRequest(config: CameraConfig)
    suspend fun close()

    val events: Flow<CameraBackendEvent>
}
