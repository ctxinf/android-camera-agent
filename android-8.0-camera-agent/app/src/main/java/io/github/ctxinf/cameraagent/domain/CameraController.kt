package io.github.ctxinf.cameraagent.domain

import android.view.Surface
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface CameraController {
    val state: StateFlow<CameraState>
    val config: StateFlow<CameraConfig>

    suspend fun takeSnapshot(): Snapshot
    suspend fun openStream(token: String): Flow<Frame>
    suspend fun closeStream(token: String)
    suspend fun attachPreview(surface: Surface)
    suspend fun detachPreview(surface: Surface)
    suspend fun updateConfig(config: CameraConfig): CameraConfig
    suspend fun setEnabled(enabled: Boolean)
    suspend fun warmup(durationMs: Long = 5000L)
}
