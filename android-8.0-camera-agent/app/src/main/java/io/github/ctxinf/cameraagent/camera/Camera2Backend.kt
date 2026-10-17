package io.github.ctxinf.cameraagent.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import io.github.ctxinf.cameraagent.domain.CameraCapabilities
import io.github.ctxinf.cameraagent.domain.CameraConfig
import io.github.ctxinf.cameraagent.domain.CameraConfigurationException
import io.github.ctxinf.cameraagent.domain.CameraInUseException
import io.github.ctxinf.cameraagent.domain.CameraTimeoutException
import io.github.ctxinf.cameraagent.domain.CameraUnavailableException
import io.github.ctxinf.cameraagent.domain.Frame
import io.github.ctxinf.cameraagent.infrastructure.logging.AppLog
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class Camera2Backend(
    private val context: Context,
    private val capabilitiesProbe: Camera2CapabilitiesProbe = Camera2CapabilitiesProbe(context),
) : CameraBackend {

    companion object {
        private const val OPEN_TIMEOUT_MS = 6000L
        private const val SESSION_CONFIGURE_TIMEOUT_MS = 6000L
    }

    private val cameraManager: CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private val _events = MutableSharedFlow<CameraBackendEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<CameraBackendEvent> = _events.asSharedFlow()

    private val currentGeneration = AtomicLong(0)

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var currentPlan: CaptureSessionPlan? = null

    @Volatile
    private var isAeConverged: Boolean = false
    @Volatile
    private var isAfReady: Boolean = false
    @Volatile
    private var hasAfMotor: Boolean = false
    private var sessionFrameCount: Long = 0

    var isRepeating: Boolean = false
        private set

    @Synchronized
    private fun ensureThread(): Handler {
        if (cameraThread == null || !cameraThread!!.isAlive) {
            cameraThread = HandlerThread("Camera2BackgroundThread").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)
        }
        return cameraHandler!!
    }

    @Synchronized
    private fun quitThreadSafely() {
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    override suspend fun enumerate(): List<CameraCapabilities> {
        return capabilitiesProbe.probe()
    }

    @SuppressLint("MissingPermission")
    override suspend fun open(cameraId: String, plan: CaptureSessionPlan) {
        val generation = currentGeneration.incrementAndGet()
        AppLog.i("Camera2Backend.open: cameraId=$cameraId, size=${plan.outputSize.width}x${plan.outputSize.height}, gen=$generation")

        val handler = ensureThread()
        currentPlan = plan

        val characteristics = try {
            cameraManager.getCameraCharacteristics(cameraId)
        } catch (e: Exception) {
            null
        }
        val minFocusDistance = characteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        hasAfMotor = minFocusDistance != null && minFocusDistance > 0f

        isAeConverged = false
        isAfReady = !hasAfMotor
        sessionFrameCount = 0

        // 1. Open CameraDevice asynchronously with timeout
        val device = try {
            withTimeout(OPEN_TIMEOUT_MS) {
                suspendCancellableCoroutine<CameraDevice> { cont ->
                    try {
                        cameraManager.openCamera(
                            cameraId,
                            object : CameraDevice.StateCallback() {
                                override fun onOpened(camera: CameraDevice) {
                                    if (generation != currentGeneration.get()) {
                                        camera.close()
                                        return
                                    }
                                    cameraDevice = camera
                                    if (cont.isActive) cont.resume(camera)
                                }

                                override fun onDisconnected(camera: CameraDevice) {
                                    if (generation != currentGeneration.get()) return
                                    AppLog.w("CameraDevice onDisconnected for [$cameraId]")
                                    camera.close()
                                    cameraDevice = null
                                    _events.tryEmit(CameraBackendEvent.Disconnected)
                                    if (cont.isActive) {
                                        cont.resumeWithException(CameraUnavailableException("Camera [$cameraId] was disconnected"))
                                    }
                                }

                                override fun onError(camera: CameraDevice, error: Int) {
                                    if (generation != currentGeneration.get()) return
                                    val (msg, isFatal) = mapCameraError(error)
                                    AppLog.e("CameraDevice onError [$error]: $msg for [$cameraId]")
                                    camera.close()
                                    cameraDevice = null
                                    _events.tryEmit(CameraBackendEvent.Error(msg, isFatal))
                                    if (cont.isActive) {
                                        val ex = if (error == ERROR_CAMERA_IN_USE || error == ERROR_MAX_CAMERAS_IN_USE) {
                                            CameraInUseException(msg)
                                        } else {
                                            CameraUnavailableException(msg)
                                        }
                                        cont.resumeWithException(ex)
                                    }
                                }

                                override fun onClosed(camera: CameraDevice) {
                                    if (generation != currentGeneration.get()) return
                                    AppLog.d("CameraDevice onClosed for [$cameraId]")
                                }
                            },
                            handler,
                        )
                    } catch (e: SecurityException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    } catch (e: CameraAccessException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw CameraTimeoutException("Timed out opening CameraDevice [$cameraId]")
        }

        // 2. Set up ImageReader for YUV_420_888
        val reader = ImageReader.newInstance(
            plan.outputSize.width,
            plan.outputSize.height,
            ImageFormat.YUV_420_888,
            2,
        )
        imageReader = reader

        reader.setOnImageAvailableListener({ ir ->
            if (generation != currentGeneration.get()) return@setOnImageAvailableListener
            try {
                ir.acquireLatestImage()?.use { image ->
                    sessionFrameCount++
                    val aeReady = isAeConverged || sessionFrameCount >= 8
                    val afReady = !hasAfMotor || isAfReady || sessionFrameCount >= 20
                    val is3aReady = aeReady && afReady

                    val nv21 = YuvUtils.yuv420ToNv21(image)
                    val frame = Frame(
                        data = nv21,
                        width = image.width,
                        height = image.height,
                        rotation = plan.rotation,
                        timestampNs = image.timestamp,
                    )
                    _events.tryEmit(CameraBackendEvent.FrameAvailable(frame, isAeConverged = is3aReady))
                }
            } catch (e: Exception) {
                AppLog.w("Error processing image from ImageReader", e)
            }
        }, handler)

        // 3. Create CaptureSession
        val outputSurfaces = mutableListOf(reader.surface)
        if (plan.previewSurface != null && plan.previewSurface.isValid) {
            outputSurfaces.add(plan.previewSurface)
        }

        val session = try {
            withTimeout(SESSION_CONFIGURE_TIMEOUT_MS) {
                suspendCancellableCoroutine<CameraCaptureSession> { cont ->
                    try {
                        @Suppress("DEPRECATION")
                        device.createCaptureSession(
                            outputSurfaces,
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    if (generation != currentGeneration.get()) {
                                        s.close()
                                        return
                                    }
                                    captureSession = s
                                    if (cont.isActive) cont.resume(s)
                                }

                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    if (generation != currentGeneration.get()) return
                                    AppLog.e("CaptureSession configuration failed for [$cameraId]")
                                    s.close()
                                    if (cont.isActive) {
                                        cont.resumeWithException(
                                            CameraConfigurationException("Failed to configure CaptureSession for [$cameraId]")
                                        )
                                    }
                                }

                                override fun onClosed(s: CameraCaptureSession) {
                                    AppLog.d("CaptureSession onClosed")
                                }
                            },
                            handler,
                        )
                    } catch (e: CameraAccessException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw CameraTimeoutException("Timed out configuring CaptureSession for [$cameraId]")
        }

        captureSession = session
        _events.tryEmit(CameraBackendEvent.Opened(cameraId))
        AppLog.i("Camera2Backend session successfully established for [$cameraId]")
    }

    private fun createCaptureCallback(generation: Long): CameraCaptureSession.CaptureCallback {
        return object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                if (generation != currentGeneration.get()) return
                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                if (aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                    aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                    aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
                ) {
                    isAeConverged = true
                }

                val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                if (afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                    afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                    afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ||
                    afState == CaptureResult.CONTROL_AF_STATE_INACTIVE
                ) {
                    isAfReady = true
                }
            }
        }
    }

    override suspend fun startRepeating() {
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        val plan = currentPlan ?: return
        val reader = imageReader ?: return
        val handler = cameraHandler ?: return

        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(reader.surface)
            if (plan.previewSurface != null && plan.previewSurface.isValid) {
                builder.addTarget(plan.previewSurface)
            }

            // Apply FPS range
            builder.set(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(plan.fpsRange.minFps, plan.fpsRange.maxFps),
            )

            // Standard 3A continuous modes
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

            session.setRepeatingRequest(builder.build(), createCaptureCallback(currentGeneration.get()), handler)
            isRepeating = true
            AppLog.i("Camera2Backend repeating request started: FPS=[${plan.fpsRange.minFps}, ${plan.fpsRange.maxFps}]")
        } catch (e: Exception) {
            AppLog.e("Failed to start repeating request", e)
            throw e
        }
    }

    override suspend fun stopRepeating() {
        if (!isRepeating) return
        try {
            captureSession?.stopRepeating()
        } catch (e: Exception) {
            AppLog.w("Failed to stop repeating request", e)
        } finally {
            isRepeating = false
            AppLog.i("Camera2Backend repeating request stopped")
        }
    }

    override suspend fun updateRequest(config: CameraConfig) {
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        val plan = currentPlan ?: return
        val reader = imageReader ?: return
        val handler = cameraHandler ?: return

        if (!isRepeating) return

        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(reader.surface)
            if (plan.previewSurface != null && plan.previewSurface.isValid) {
                builder.addTarget(plan.previewSurface)
            }

            builder.set(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(plan.fpsRange.minFps, plan.fpsRange.maxFps),
            )
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

            session.setRepeatingRequest(builder.build(), createCaptureCallback(currentGeneration.get()), handler)
            AppLog.i("Camera2Backend repeating request updated with new config")
        } catch (e: Exception) {
            AppLog.w("Failed to update repeating request", e)
        }
    }

    override suspend fun close() {
        currentGeneration.incrementAndGet()
        AppLog.i("Camera2Backend closing hardware resources...")

        isRepeating = false

        try {
            captureSession?.stopRepeating()
        } catch (e: Exception) {
            AppLog.w("Error stopping repeating in close", e)
        }

        try {
            captureSession?.close()
        } catch (e: Exception) {
            AppLog.w("Error closing captureSession", e)
        }
        captureSession = null

        try {
            cameraDevice?.close()
        } catch (e: Exception) {
            AppLog.w("Error closing cameraDevice", e)
        }
        cameraDevice = null

        try {
            imageReader?.close()
        } catch (e: Exception) {
            AppLog.w("Error closing imageReader", e)
        }
        imageReader = null

        quitThreadSafely()
        _events.tryEmit(CameraBackendEvent.Closed)
        AppLog.i("Camera2Backend closed completely")
    }

    private fun mapCameraError(error: Int): Pair<String, Boolean> {
        return when (error) {
            CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "Camera in use by another application" to false
            CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "Maximum number of cameras already in use" to false
            CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "Camera device disabled by system policy" to false
            CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "Fatal camera device hardware failure" to true
            CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "Fatal camera system service failure" to true
            else -> "Unknown camera error ($error)" to true
        }
    }
}
