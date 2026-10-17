package io.github.ctxinf.cameraagent.domain

sealed class CameraException(
    message: String,
    cause: Throwable? = null,
    val isRecoverable: Boolean = true,
) : RuntimeException(message, cause)

class CameraUnavailableException(message: String = "Camera is currently unavailable", cause: Throwable? = null) :
    CameraException(message, cause, isRecoverable = true)

class CameraDisabledException(message: String = "Camera access is disabled by policy", cause: Throwable? = null) :
    CameraException(message, cause, isRecoverable = false)

class CameraInUseException(message: String = "Camera is in use by another higher-priority consumer", cause: Throwable? = null) :
    CameraException(message, cause, isRecoverable = true)

class CameraTimeoutException(message: String = "Camera operation timed out", cause: Throwable? = null) :
    CameraException(message, cause, isRecoverable = true)

class CameraConfigurationException(message: String, cause: Throwable? = null) :
    CameraException(message, cause, isRecoverable = false)
