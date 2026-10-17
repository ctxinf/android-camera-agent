package io.github.ctxinf.cameraagent.fake_camera

import io.github.ctxinf.cameraagent.camera.CameraBackend
import io.github.ctxinf.cameraagent.camera.CameraBackendEvent
import io.github.ctxinf.cameraagent.camera.CaptureSessionPlan
import io.github.ctxinf.cameraagent.domain.CameraCapabilities
import io.github.ctxinf.cameraagent.domain.CameraConfig
import io.github.ctxinf.cameraagent.domain.FpsRange
import io.github.ctxinf.cameraagent.domain.Frame
import io.github.ctxinf.cameraagent.domain.LensFacing
import io.github.ctxinf.cameraagent.domain.Size
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class FakeCameraBackend : CameraBackend {
    private val _events = MutableSharedFlow<CameraBackendEvent>(extraBufferCapacity = 64)
    override val events: Flow<CameraBackendEvent> = _events.asSharedFlow()

    var isOpened = false
        private set
    var isRepeating = false
        private set
    var lastOpenedCameraId: String? = null
        private set
    var lastPlan: CaptureSessionPlan? = null
        private set

    var capabilities: List<CameraCapabilities> = listOf(
        CameraCapabilities(
            cameraId = "0",
            facing = LensFacing.BACK,
            sensorOrientation = 90,
            hardwareLevel = "FULL",
            supportedSizes = listOf(
                Size(1920, 1080),
                Size(1280, 720),
                Size(640, 480),
            ),
            supportedFpsRanges = listOf(
                FpsRange(15, 15),
                FpsRange(15, 30),
                FpsRange(30, 30),
            ),
        ),
        CameraCapabilities(
            cameraId = "1",
            facing = LensFacing.FRONT,
            sensorOrientation = 270,
            hardwareLevel = "LIMITED",
            supportedSizes = listOf(
                Size(1280, 720),
                Size(640, 480),
            ),
            supportedFpsRanges = listOf(
                FpsRange(15, 30),
                FpsRange(30, 30),
            ),
        ),
    )

    override suspend fun enumerate(): List<CameraCapabilities> = capabilities

    override suspend fun open(cameraId: String, plan: CaptureSessionPlan) {
        lastOpenedCameraId = cameraId
        lastPlan = plan
        isOpened = true
        _events.emit(CameraBackendEvent.Opened(cameraId))
    }

    override suspend fun startRepeating() {
        isRepeating = true
    }

    override suspend fun stopRepeating() {
        isRepeating = false
    }

    override suspend fun updateRequest(config: CameraConfig) {
        // no-op for fake
    }

    override suspend fun close() {
        isOpened = false
        isRepeating = false
        _events.emit(CameraBackendEvent.Closed)
    }

    suspend fun emitEvent(event: CameraBackendEvent) {
        _events.emit(event)
    }

    suspend fun emitFrame(
        data: ByteArray = byteArrayOf(1, 2, 3, 4),
        width: Int = 1280,
        height: Int = 720,
        rotation: Int = 90,
        timestampNs: Long = System.nanoTime(),
        isAeConverged: Boolean = true,
    ) {
        _events.emit(
            CameraBackendEvent.FrameAvailable(
                Frame(
                    data = data,
                    width = width,
                    height = height,
                    rotation = rotation,
                    timestampNs = timestampNs,
                ),
                isAeConverged = isAeConverged,
            )
        )
    }
}
