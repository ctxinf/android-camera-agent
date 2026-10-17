package io.github.ctxinf.cameraagent.domain

import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow

sealed interface CameraCommand {
    data class Snapshot(
        val response: CompletableDeferred<io.github.ctxinf.cameraagent.domain.Snapshot>,
    ) : CameraCommand

    data class OpenStream(
        val token: String,
        val response: CompletableDeferred<Flow<Frame>>,
    ) : CameraCommand

    data class CloseStream(
        val token: String,
    ) : CameraCommand

    data class AttachPreview(
        val surface: Surface,
        val response: CompletableDeferred<Unit>,
    ) : CameraCommand

    data class DetachPreview(
        val surface: Surface,
    ) : CameraCommand

    data class UpdateConfig(
        val config: CameraConfig,
        val response: CompletableDeferred<CameraConfig>,
    ) : CameraCommand

    data class SetEnabled(
        val enabled: Boolean,
        val response: CompletableDeferred<Unit>,
    ) : CameraCommand

    data class Warmup(
        val durationMs: Long = 5000L,
        val response: CompletableDeferred<Unit>,
    ) : CameraCommand

    data object Shutdown : CameraCommand
}
