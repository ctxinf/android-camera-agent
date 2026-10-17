package io.github.ctxinf.cameraagent.camera

import android.view.Surface
import io.github.ctxinf.cameraagent.domain.CameraCommand
import io.github.ctxinf.cameraagent.domain.CameraConfig
import io.github.ctxinf.cameraagent.domain.CameraConfigurationException
import io.github.ctxinf.cameraagent.domain.CameraController
import io.github.ctxinf.cameraagent.domain.CameraDisabledException
import io.github.ctxinf.cameraagent.domain.CameraState
import io.github.ctxinf.cameraagent.domain.CameraTimeoutException
import io.github.ctxinf.cameraagent.domain.CameraUnavailableException
import io.github.ctxinf.cameraagent.domain.Frame
import io.github.ctxinf.cameraagent.domain.Snapshot
import io.github.ctxinf.cameraagent.infrastructure.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong

/**
 * CameraCoordinator is the sole owner of all Camera hardware lifecycle and session state.
 *
 * All state mutations and commands are processed sequentially on a single coroutine event loop,
 * eliminating race conditions between external caller commands and underlying Camera2 callbacks.
 */
class CameraCoordinator(
    private val backend: CameraBackend,
    parentScope: CoroutineScope,
    private val retentionPolicy: RetentionPolicy = AlwaysClosePolicy(),
    private val sessionPlanner: CaptureSessionPlanner = DefaultCaptureSessionPlanner(),
    private val frameEncoder: FrameEncoder = DefaultFrameEncoder(),
    initialConfig: CameraConfig = CameraConfig(),
    private val snapshotTimeoutMs: Long = DEFAULT_SNAPSHOT_TIMEOUT_MS,
) : CameraController, AutoCloseable {

    companion object {
        const val DEFAULT_SNAPSHOT_TIMEOUT_MS = 10_000L
    }

    private sealed interface Action {
        data class Command(val command: CameraCommand) : Action
        data class BackendEvent(val event: CameraBackendEvent, val generation: Long) : Action
        data class IdleTimeout(val generation: Long) : Action
    }

    private val coordinatorJob = kotlinx.coroutines.SupervisorJob(parentScope.coroutineContext[kotlinx.coroutines.Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + coordinatorJob)

    private val actionChannel = Channel<Action>(Channel.UNLIMITED)

    private val _config = MutableStateFlow(initialConfig)
    override val config: StateFlow<CameraConfig> = _config.asStateFlow()

    private val _state = MutableStateFlow<CameraState>(
        if (initialConfig.enabled) CameraState.Closed else CameraState.Disabled
    )
    override val state: StateFlow<CameraState> = _state.asStateFlow()

    // Internal state only accessed from within the event loop coroutine
    private val currentGeneration = AtomicLong(0)
    private var currentSessionPlan: CaptureSessionPlan? = null
    private var currentCameraId: String? = null
    private var isRepeating = false

    private val pendingSnapshots = mutableListOf<CompletableDeferred<Snapshot>>()
    private val activeStreams = mutableMapOf<String, MutableSharedFlow<Frame>>()
    private val attachedPreviewSurfaces = mutableSetOf<Surface>()
    private var idleCloseJob: Job? = null

    private fun cancelIdleCloseTimer() {
        idleCloseJob?.cancel()
        idleCloseJob = null
    }

    private fun scheduleIdleCloseTimer(delayMs: Long) {
        cancelIdleCloseTimer()
        val gen = currentGeneration.get()
        idleCloseJob = scope.launch {
            kotlinx.coroutines.delay(delayMs)
            actionChannel.send(Action.IdleTimeout(gen))
        }
    }

    init {
        // Start single-coroutine event loop
        scope.launch {
            for (action in actionChannel) {
                try {
                    when (action) {
                        is Action.Command -> handleCommand(action.command)
                        is Action.BackendEvent -> handleBackendEvent(action.event, action.generation)
                        is Action.IdleTimeout -> handleIdleTimeout(action.generation)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    AppLog.e("Unexpected error in CameraCoordinator event loop", t)
                }
            }
        }

        // Bridge backend events to the event loop
        scope.launch {
            backend.events.collect { event ->
                actionChannel.send(Action.BackendEvent(event, currentGeneration.get()))
            }
        }
    }

    // --- CameraController API ---

    override suspend fun takeSnapshot(): Snapshot {
        val deferred = CompletableDeferred<Snapshot>()
        actionChannel.send(Action.Command(CameraCommand.Snapshot(deferred)))
        return try {
            withTimeout(snapshotTimeoutMs) {
                deferred.await()
            }
        } catch (e: CancellationException) {
            deferred.cancel(e)
            throw if (e is kotlinx.coroutines.TimeoutCancellationException) {
                CameraTimeoutException("Snapshot timed out after ${snapshotTimeoutMs}ms")
            } else {
                e
            }
        }
    }

    override suspend fun openStream(token: String): Flow<Frame> {
        val deferred = CompletableDeferred<Flow<Frame>>()
        actionChannel.send(Action.Command(CameraCommand.OpenStream(token, deferred)))
        return deferred.await()
    }

    override suspend fun closeStream(token: String) {
        actionChannel.send(Action.Command(CameraCommand.CloseStream(token)))
    }

    override suspend fun attachPreview(surface: Surface) {
        val deferred = CompletableDeferred<Unit>()
        actionChannel.send(Action.Command(CameraCommand.AttachPreview(surface, deferred)))
        deferred.await()
    }

    override suspend fun detachPreview(surface: Surface) {
        actionChannel.send(Action.Command(CameraCommand.DetachPreview(surface)))
    }

    override suspend fun updateConfig(config: CameraConfig): CameraConfig {
        val deferred = CompletableDeferred<CameraConfig>()
        actionChannel.send(Action.Command(CameraCommand.UpdateConfig(config, deferred)))
        return deferred.await()
    }

    override suspend fun setEnabled(enabled: Boolean) {
        val deferred = CompletableDeferred<Unit>()
        actionChannel.send(Action.Command(CameraCommand.SetEnabled(enabled, deferred)))
        deferred.await()
    }

    override suspend fun warmup(durationMs: Long) {
        val deferred = CompletableDeferred<Unit>()
        actionChannel.send(Action.Command(CameraCommand.Warmup(durationMs, deferred)))
        deferred.await()
    }

    fun shutdown() {
        actionChannel.trySend(Action.Command(CameraCommand.Shutdown))
    }

    // --- Single-Coroutine Event Loop Handlers ---

    private suspend fun handleCommand(command: CameraCommand) {
        cleanInactiveSnapshots()

        when (command) {
            is CameraCommand.Snapshot -> {
                if (!_config.value.enabled) {
                    command.response.completeExceptionally(CameraDisabledException())
                    return
                }
                cancelIdleCloseTimer()
                pendingSnapshots.add(command.response)
                ensureCameraStarted()
            }

            is CameraCommand.OpenStream -> {
                if (!_config.value.enabled) {
                    command.response.completeExceptionally(CameraDisabledException())
                    return
                }
                cancelIdleCloseTimer()
                val existing = activeStreams[command.token]
                if (existing != null) {
                    command.response.complete(wrapStreamFlow(command.token, existing))
                    return
                }

                val streamFlow = MutableSharedFlow<Frame>(
                    replay = 1,
                    extraBufferCapacity = 1,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )
                activeStreams[command.token] = streamFlow
                command.response.complete(wrapStreamFlow(command.token, streamFlow))

                ensureCameraStarted()
                updateActiveStateIfActive()
            }

            is CameraCommand.CloseStream -> {
                activeStreams.remove(command.token)
                updateActiveStateIfActive()
                checkIdleAndApplyRetention()
            }

            is CameraCommand.AttachPreview -> {
                if (!_config.value.enabled) {
                    command.response.completeExceptionally(CameraDisabledException())
                    return
                }
                cancelIdleCloseTimer()
                attachedPreviewSurfaces.add(command.surface)
                if (_state.value is CameraState.Active) {
                    reopenWithUpdatedPlan()
                } else {
                    ensureCameraStarted()
                }
                command.response.complete(Unit)
            }

            is CameraCommand.DetachPreview -> {
                attachedPreviewSurfaces.remove(command.surface)
                if (_state.value is CameraState.Active) {
                    if (attachedPreviewSurfaces.isEmpty()) {
                        checkIdleAndApplyRetention()
                    } else {
                        reopenWithUpdatedPlan()
                    }
                }
            }

            is CameraCommand.Warmup -> {
                if (!_config.value.enabled) {
                    command.response.completeExceptionally(CameraDisabledException())
                    return
                }
                cancelIdleCloseTimer()
                ensureCameraStarted()
                if (!isRepeating && _state.value is CameraState.Active) {
                    try {
                        backend.startRepeating()
                        isRepeating = true
                    } catch (e: Exception) {
                        AppLog.w("Error starting repeating in warmup", e)
                    }
                }
                scheduleIdleCloseTimer(command.durationMs)
                command.response.complete(Unit)
            }

            is CameraCommand.UpdateConfig -> {
                val oldConfig = _config.value
                val newConfig = command.config
                _config.value = newConfig

                if (_state.value is CameraState.Active) {
                    val facingChanged = oldConfig.facing != newConfig.facing
                    val sizeChanged = oldConfig.targetWidth != newConfig.targetWidth ||
                            oldConfig.targetHeight != newConfig.targetHeight
                    if (facingChanged || sizeChanged) {
                        reopenWithUpdatedPlan()
                    } else {
                        try {
                            backend.updateRequest(newConfig)
                        } catch (e: Exception) {
                            AppLog.w("Failed to update camera request config", e)
                        }
                    }
                }
                command.response.complete(newConfig)
            }

            is CameraCommand.SetEnabled -> {
                val wasEnabled = _config.value.enabled
                val isNowEnabled = command.enabled
                _config.value = _config.value.copy(enabled = isNowEnabled)

                if (!isNowEnabled) {
                    cancelIdleCloseTimer()
                    val ex = CameraDisabledException()
                    failAllPendingSnapshots(ex)
                    activeStreams.clear()
                    attachedPreviewSurfaces.clear()

                    if (_state.value !is CameraState.Closed && _state.value !is CameraState.Disabled) {
                        stopRepeatingAndCloseBackend()
                    }
                    _state.value = CameraState.Disabled
                } else if (!wasEnabled) {
                    _state.value = CameraState.Closed
                }
                command.response.complete(Unit)
            }

            is CameraCommand.Shutdown -> {
                cancelIdleCloseTimer()
                failAllPendingSnapshots(CameraUnavailableException("Camera service is shutting down"))
                activeStreams.clear()
                attachedPreviewSurfaces.clear()
                stopRepeatingAndCloseBackend()
                _state.value = CameraState.Closed
                actionChannel.close()
                coordinatorJob.cancel()
            }
        }
    }

    override fun close() {
        shutdown()
    }

    private suspend fun handleBackendEvent(event: CameraBackendEvent, generation: Long) {
        // Drop events from stale generations, except for Closed confirmations
        if (generation != currentGeneration.get() && event !is CameraBackendEvent.Closed) {
            AppLog.d("Ignoring event from stale generation ($generation != ${currentGeneration.get()}): $event")
            return
        }

        when (event) {
            is CameraBackendEvent.Opened -> {
                val plan = currentSessionPlan
                if (plan != null) {
                    _state.value = CameraState.Active(
                        cameraId = event.cameraId,
                        width = plan.outputSize.width,
                        height = plan.outputSize.height,
                        activeStreams = activeStreams.size,
                    )
                    if (hasActiveConsumers() && !isRepeating) {
                        backend.startRepeating()
                        isRepeating = true
                    }
                }
            }

            is CameraBackendEvent.FrameAvailable -> {
                cleanInactiveSnapshots()
                val frame = event.frame

                // 1. Deliver frame to all pending snapshots once AE has converged
                if (pendingSnapshots.isNotEmpty() && event.isAeConverged) {
                    val jpegBytes = frameEncoder.encodeToJpeg(frame, _config.value.jpegQuality)
                    val snapshot = Snapshot(
                        jpegBytes = jpegBytes,
                        width = frame.width,
                        height = frame.height,
                        rotation = frame.rotation,
                    )
                    for (deferred in pendingSnapshots) {
                        if (deferred.isActive) {
                            deferred.complete(snapshot)
                        }
                    }
                    pendingSnapshots.clear()
                }

                // 2. Deliver frame to active streams
                for (stream in activeStreams.values) {
                    stream.tryEmit(frame)
                }

                // 3. If no consumers left, trigger retention decision (e.g. low power close)
                checkIdleAndApplyRetention()
            }

            is CameraBackendEvent.Error -> {
                AppLog.e("Camera backend error: ${event.message} (fatal=${event.isFatal})")
                _state.value = CameraState.Failed(event.message, isRecoverable = !event.isFatal)
                failAllPendingSnapshots(CameraUnavailableException(event.message))
                if (event.isFatal) {
                    stopRepeatingAndCloseBackend()
                }
            }

            is CameraBackendEvent.Disconnected -> {
                AppLog.w("Camera backend disconnected")
                _state.value = CameraState.Failed("Camera disconnected", isRecoverable = true)
                failAllPendingSnapshots(CameraUnavailableException("Camera disconnected"))
                stopRepeatingAndCloseBackend()
            }

            is CameraBackendEvent.Closed -> {
                isRepeating = false
                if (_state.value is CameraState.Closing) {
                    _state.value = if (_config.value.enabled) CameraState.Closed else CameraState.Disabled
                }
            }
        }
    }

    // --- Helpers ---

    private fun wrapStreamFlow(token: String, flow: MutableSharedFlow<Frame>): Flow<Frame> {
        return flow.asSharedFlow().onCompletion {
            closeStream(token)
        }
    }

    private fun hasDirectConsumers(): Boolean {
        cleanInactiveSnapshots()
        return pendingSnapshots.isNotEmpty() || activeStreams.isNotEmpty() || attachedPreviewSurfaces.isNotEmpty()
    }

    private fun isWarmingUp(): Boolean = idleCloseJob?.isActive == true

    private fun hasActiveConsumers(): Boolean {
        return hasDirectConsumers() || isWarmingUp()
    }

    private fun cleanInactiveSnapshots() {
        pendingSnapshots.removeAll { !it.isActive }
    }

    private fun failAllPendingSnapshots(throwable: Throwable) {
        for (deferred in pendingSnapshots) {
            if (deferred.isActive) {
                deferred.completeExceptionally(throwable)
            }
        }
        pendingSnapshots.clear()
    }

    private fun updateActiveStateIfActive() {
        val plan = currentSessionPlan ?: return
        if (_state.value is CameraState.Active) {
            _state.value = CameraState.Active(
                cameraId = plan.cameraId,
                width = plan.outputSize.width,
                height = plan.outputSize.height,
                activeStreams = activeStreams.size,
            )
        }
    }

    private suspend fun ensureCameraStarted() {
        when (_state.value) {
            is CameraState.Closed, is CameraState.Retained, is CameraState.Failed -> {
                openCameraBackend()
            }
            is CameraState.Opening -> {
                // Already opening, consumers will be serviced when Active / FrameAvailable
            }
            is CameraState.Active -> {
                if (!isRepeating) {
                    backend.startRepeating()
                    isRepeating = true
                }
            }
            is CameraState.Closing -> {
                // Wait for close to complete, or open directly
                openCameraBackend()
            }
            is CameraState.Disabled -> {
                // Cannot open when disabled
            }
        }
    }

    private suspend fun openCameraBackend() {
        val gen = currentGeneration.incrementAndGet()
        val capabilities = try {
            backend.enumerate()
        } catch (e: Exception) {
            val msg = "Failed to enumerate cameras: ${e.message}"
            AppLog.e(msg, e)
            _state.value = CameraState.Failed(msg, isRecoverable = true)
            failAllPendingSnapshots(CameraUnavailableException(msg, e))
            return
        }

        val plan = try {
            sessionPlanner.plan(capabilities, _config.value, attachedPreviewSurfaces.firstOrNull())
        } catch (e: Exception) {
            val msg = "Failed to plan capture session: ${e.message}"
            AppLog.e(msg, e)
            _state.value = CameraState.Failed(msg, isRecoverable = false)
            failAllPendingSnapshots(CameraConfigurationException(msg, e))
            return
        }

        currentSessionPlan = plan
        currentCameraId = plan.cameraId
        _state.value = CameraState.Opening(plan.cameraId)

        try {
            backend.open(plan.cameraId, plan)
            _state.value = CameraState.Active(
                cameraId = plan.cameraId,
                width = plan.outputSize.width,
                height = plan.outputSize.height,
                activeStreams = activeStreams.size,
            )
            if (hasActiveConsumers() && !isRepeating) {
                backend.startRepeating()
                isRepeating = true
            }
        } catch (e: Exception) {
            val msg = "Failed to open camera backend: ${e.message}"
            AppLog.e(msg, e)
            _state.value = CameraState.Failed(msg, isRecoverable = true)
            failAllPendingSnapshots(CameraUnavailableException(msg, e))
        }
    }

    private suspend fun reopenWithUpdatedPlan() {
        stopRepeatingAndCloseBackend()
        openCameraBackend()
    }

    private suspend fun stopRepeatingAndCloseBackend() {
        if (isRepeating) {
            try {
                backend.stopRepeating()
            } catch (e: Exception) {
                AppLog.w("Error stopping repeating request", e)
            }
            isRepeating = false
        }
        try {
            backend.close()
        } catch (e: Exception) {
            AppLog.w("Error closing backend", e)
        }
    }

    private suspend fun checkIdleAndApplyRetention() {
        if (!hasDirectConsumers() && (_state.value is CameraState.Active || _state.value is CameraState.Retained)) {
            val decision = retentionPolicy.decide(RetentionInput(activeConsumers = 0, idleDurationMs = 0))
            when (decision) {
                RetentionDecision.CLOSE_IMMEDIATELY -> {
                    cancelIdleCloseTimer()
                    _state.value = CameraState.Closing
                    stopRepeatingAndCloseBackend()
                    _state.value = if (_config.value.enabled) CameraState.Closed else CameraState.Disabled
                }
                RetentionDecision.RETAIN -> {
                    val graceMs = retentionPolicy.gracePeriodMs
                    if (graceMs > 0) {
                        // Keep camera active and repeating during grace period, schedule close timer
                        val job = idleCloseJob
                        if (job == null || !job.isActive) {
                            scheduleIdleCloseTimer(graceMs)
                        }
                    } else {
                        cancelIdleCloseTimer()
                        if (isRepeating) {
                            try {
                                backend.stopRepeating()
                            } catch (e: Exception) {
                                AppLog.w("Error stopping repeating request", e)
                            }
                            isRepeating = false
                        }
                        val camId = currentCameraId ?: ""
                        _state.value = CameraState.Retained(camId, "Retention policy retain")
                    }
                }
            }
        }
    }

    private suspend fun handleIdleTimeout(generation: Long) {
        if (generation != currentGeneration.get()) return
        cancelIdleCloseTimer()
        if (!hasDirectConsumers() && (_state.value is CameraState.Active || _state.value is CameraState.Retained)) {
            AppLog.i("CameraCoordinator: retention grace period expired with no active consumers, closing backend")
            _state.value = CameraState.Closing
            stopRepeatingAndCloseBackend()
            _state.value = if (_config.value.enabled) CameraState.Closed else CameraState.Disabled
        }
    }
}
