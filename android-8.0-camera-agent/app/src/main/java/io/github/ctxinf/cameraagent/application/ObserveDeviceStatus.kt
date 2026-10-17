package io.github.ctxinf.cameraagent.application

import io.github.ctxinf.cameraagent.domain.DeviceStatus
import kotlinx.coroutines.flow.StateFlow

interface DeviceStatusProvider {
    val status: StateFlow<DeviceStatus>
}

class ObserveDeviceStatus(
    private val statusProvider: DeviceStatusProvider,
) {
    operator fun invoke(): StateFlow<DeviceStatus> {
        return statusProvider.status
    }
}
