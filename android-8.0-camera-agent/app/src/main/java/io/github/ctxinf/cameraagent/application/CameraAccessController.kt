package io.github.ctxinf.cameraagent.application

import io.github.ctxinf.cameraagent.domain.CameraConfig
import io.github.ctxinf.cameraagent.domain.CameraController
import io.github.ctxinf.cameraagent.domain.LensFacing
import io.github.ctxinf.cameraagent.infrastructure.preferences.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes local privacy changes with remote updates. Remote callers never own the privacy gate. */
class CameraAccessController(
    private val delegate: CameraController,
    private val settings: SettingsRepository,
    private val runtimeActive: () -> Boolean = { true },
) : CameraController by delegate {
    private val mutex = Mutex()

    override suspend fun setEnabled(enabled: Boolean) {
        throw SecurityException("Camera enable/disable is device-only")
    }

    override suspend fun updateConfig(config: CameraConfig): CameraConfig = mutex.withLock {
        if (!runtimeActive() || settings.manuallyExited)
            throw SecurityException("Camera agent is stopped")
        val stored = settings.config.value
        if (config.enabled != stored.enabled) throw SecurityException("Camera enable/disable is device-only")
        if (!stored.enabled && config.facing != stored.facing)
            throw SecurityException("Camera is disabled locally; enable it on the device")
        checkFront(config)
        apply(config)
    }

    suspend fun updateLocal(change: (CameraConfig) -> CameraConfig): CameraConfig = mutex.withLock {
        val updated = change(settings.config.value)
        checkFront(updated)
        apply(updated)
    }

    suspend fun setFrontDisabled(disabled: Boolean) = mutex.withLock {
        settings.setFrontCameraDisabled(disabled)
        // Move away from front immediately, including an existing preview or stream.
        val config = settings.config.value
        if (disabled && config.facing == LensFacing.FRONT) {
            delegate.setEnabled(false)
            apply(config.copy(facing = LensFacing.BACK))
        } else apply(config)
    }

    suspend fun restore() = mutex.withLock { apply(settings.config.value) }
    suspend fun suspendHardware() = mutex.withLock { delegate.setEnabled(false) }

    private fun checkFront(config: CameraConfig) {
        if (config.facing == LensFacing.FRONT && settings.frontCameraDisabled)
            throw SecurityException("Front camera is disabled on the device")
    }

    private suspend fun apply(config: CameraConfig): CameraConfig {
        // setEnabled performs real hardware shutdown; updateConfig alone only changes configuration.
        if (!config.enabled) delegate.setEnabled(false)
        else if (!delegate.config.value.enabled) delegate.setEnabled(true)
        delegate.updateConfig(config)
        settings.updateConfig(config)
        return config
    }
}
