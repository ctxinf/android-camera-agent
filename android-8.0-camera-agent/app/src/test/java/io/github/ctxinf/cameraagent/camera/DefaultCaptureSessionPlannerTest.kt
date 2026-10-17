package io.github.ctxinf.cameraagent.camera

import io.github.ctxinf.cameraagent.domain.CameraCapabilities
import io.github.ctxinf.cameraagent.domain.CameraConfig
import io.github.ctxinf.cameraagent.domain.FpsRange
import io.github.ctxinf.cameraagent.domain.LensFacing
import io.github.ctxinf.cameraagent.domain.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultCaptureSessionPlannerTest {

    private val planner = DefaultCaptureSessionPlanner()

    private val backCamera = CameraCapabilities(
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
    )

    private val frontCamera = CameraCapabilities(
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
    )

    private val capabilities = listOf(backCamera, frontCamera)

    @Test
    fun `plans back camera with exact resolution and fps`() {
        val config = CameraConfig(
            facing = LensFacing.BACK,
            targetWidth = 1280,
            targetHeight = 720,
            targetFps = 15,
            rotationOffset = 0,
        )

        val plan = planner.plan(capabilities, config)

        assertEquals("0", plan.cameraId)
        assertEquals(Size(1280, 720), plan.outputSize)
        assertEquals(FpsRange(15, 15), plan.fpsRange)
        assertEquals(90, plan.rotation)
    }

    @Test
    fun `plans front camera with rotation offset`() {
        val config = CameraConfig(
            facing = LensFacing.FRONT,
            targetWidth = 640,
            targetHeight = 480,
            targetFps = 30,
            rotationOffset = 90,
        )

        val plan = planner.plan(capabilities, config)

        assertEquals("1", plan.cameraId)
        assertEquals(Size(640, 480), plan.outputSize)
        assertEquals(FpsRange(30, 30), plan.fpsRange)
        assertEquals(0, plan.rotation) // (270 + 90) % 360 = 0
    }

    @Test
    fun `selects closest size when exact match does not exist`() {
        val config = CameraConfig(
            facing = LensFacing.BACK,
            targetWidth = 1000,
            targetHeight = 562, // approx 16:9
            targetFps = 30,
        )

        val plan = planner.plan(capabilities, config)

        assertEquals("0", plan.cameraId)
        // 1280x720 is 16:9 and closer than 1920x1080
        assertEquals(Size(1280, 720), plan.outputSize)
    }

    @Test
    fun `falls back to first camera when requested facing not found`() {
        val onlyFront = listOf(frontCamera)
        val config = CameraConfig(
            facing = LensFacing.BACK, // requested back, only front available
            targetWidth = 640,
            targetHeight = 480,
            targetFps = 30,
        )

        val plan = planner.plan(onlyFront, config)

        assertEquals("1", plan.cameraId)
    }
}
