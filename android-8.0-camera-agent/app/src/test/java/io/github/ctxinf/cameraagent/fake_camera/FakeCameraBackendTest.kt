package io.github.ctxinf.cameraagent.fake_camera

import app.cash.turbine.test
import io.github.ctxinf.cameraagent.camera.CameraBackendEvent
import io.github.ctxinf.cameraagent.camera.CaptureSessionPlan
import io.github.ctxinf.cameraagent.domain.FpsRange
import io.github.ctxinf.cameraagent.domain.Size
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeCameraBackendTest {

    @Test
    fun testOpenAndClose() = runTest {
        val fake = FakeCameraBackend()

        fake.events.test {
            assertFalse(fake.isOpened)

            fake.open(
                cameraId = "0",
                plan = CaptureSessionPlan(
                    cameraId = "0",
                    outputSize = Size(1280, 720),
                    fpsRange = FpsRange(15, 30),
                    rotation = 90,
                ),
            )

            assertTrue(fake.isOpened)
            assertEquals(CameraBackendEvent.Opened("0"), awaitItem())

            fake.close()
            assertFalse(fake.isOpened)
            assertEquals(CameraBackendEvent.Closed, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }
}
