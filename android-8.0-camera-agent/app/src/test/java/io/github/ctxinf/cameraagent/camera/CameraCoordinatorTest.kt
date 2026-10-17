package io.github.ctxinf.cameraagent.camera

import app.cash.turbine.test
import io.github.ctxinf.cameraagent.domain.CameraConfig
import io.github.ctxinf.cameraagent.domain.CameraDisabledException
import io.github.ctxinf.cameraagent.domain.CameraState
import io.github.ctxinf.cameraagent.domain.Frame
import io.github.ctxinf.cameraagent.domain.LensFacing
import io.github.ctxinf.cameraagent.domain.Size
import io.github.ctxinf.cameraagent.fake_camera.FakeCameraBackend
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraCoordinatorTest {

    private fun TestScope.createCoordinator(
        backend: FakeCameraBackend,
        retentionPolicy: RetentionPolicy = AlwaysClosePolicy(),
    ): CameraCoordinator {
        return CameraCoordinator(
            backend = backend,
            parentScope = backgroundScope,
            retentionPolicy = retentionPolicy,
        )
    }

    @Test
    fun `snapshot cold start opens camera, fulfills snapshot, and closes camera immediately`() = runTest {
        val fakeBackend = FakeCameraBackend()
        val coordinator = createCoordinator(fakeBackend)
        runCurrent()
        assertEquals(CameraState.Closed, coordinator.state.value)

        val snapshotDeferred = async {
            coordinator.takeSnapshot()
        }
        runCurrent()

        // Backend should be opened and repeating
        assertTrue(fakeBackend.isOpened)
        assertTrue(fakeBackend.isRepeating)
        assertTrue(coordinator.state.value is CameraState.Active)

        // Emit a frame
        val frameData = byteArrayOf(10, 20, 30)
        fakeBackend.emitFrame(data = frameData, width = 1280, height = 720, rotation = 90)
        runCurrent()

        val snapshot = snapshotDeferred.await()
        assertEquals(1280, snapshot.width)
        assertEquals(720, snapshot.height)
        assertEquals(90, snapshot.rotation)
        assertTrue(frameData.contentEquals(snapshot.jpegBytes))

        // Backend should now be closed due to AlwaysClosePolicy
        assertFalse(fakeBackend.isRepeating)
        assertFalse(fakeBackend.isOpened)
        assertEquals(CameraState.Closed, coordinator.state.value)
    }

    @Test
    fun `concurrent snapshots coalesce into single open and all receive the frame`() = runTest {
        val fakeBackend = FakeCameraBackend()
        val coordinator = createCoordinator(fakeBackend)
        runCurrent()

        val snap1 = async { coordinator.takeSnapshot() }
        val snap2 = async { coordinator.takeSnapshot() }
        val snap3 = async { coordinator.takeSnapshot() }
        runCurrent()

        assertTrue(fakeBackend.isOpened)
        assertTrue(fakeBackend.isRepeating)

        val frameData = byteArrayOf(1, 2, 3)
        fakeBackend.emitFrame(data = frameData, width = 1280, height = 720, rotation = 90)
        runCurrent()

        val res1 = snap1.await()
        val res2 = snap2.await()
        val res3 = snap3.await()

        assertTrue(frameData.contentEquals(res1.jpegBytes))
        assertTrue(frameData.contentEquals(res2.jpegBytes))
        assertTrue(frameData.contentEquals(res3.jpegBytes))

        // After all snapshots fulfilled, camera closes immediately
        assertFalse(fakeBackend.isOpened)
        assertEquals(CameraState.Closed, coordinator.state.value)
    }

    @Test
    fun `openStream and closeStream manages camera lifecycle and delivers frames`() = runTest {
        val fakeBackend = FakeCameraBackend()
        val coordinator = createCoordinator(fakeBackend)
        runCurrent()

        val streamFlow = coordinator.openStream("client-1")
        runCurrent()

        assertTrue(fakeBackend.isOpened)
        assertTrue(fakeBackend.isRepeating)
        val activeState = coordinator.state.value as CameraState.Active
        assertEquals(1, activeState.activeStreams)

        streamFlow.test {
            fakeBackend.emitFrame(data = byteArrayOf(99), width = 1280, height = 720)
            val frame = awaitItem()
            assertEquals(99.toByte(), frame.data[0])

            // Close stream
            coordinator.closeStream("client-1")
            runCurrent()

            // Stream closed and no other consumers -> camera closed
            assertFalse(fakeBackend.isOpened)
            assertEquals(CameraState.Closed, coordinator.state.value)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stream backpressure conflates frames for slow consumer`() = runTest {
        val fakeBackend = FakeCameraBackend()
        val coordinator = createCoordinator(fakeBackend)
        runCurrent()

        val streamFlow = coordinator.openStream("client-fast-producer")
        runCurrent()

        // Emit multiple frames without consuming
        fakeBackend.emitFrame(data = byteArrayOf(1), timestampNs = 100)
        fakeBackend.emitFrame(data = byteArrayOf(2), timestampNs = 200)
        fakeBackend.emitFrame(data = byteArrayOf(3), timestampNs = 300)
        runCurrent()

        // Consumer reads: should get the latest frame
        val latest = streamFlow.first()
        assertEquals(3.toByte(), latest.data[0])

        coordinator.closeStream("client-fast-producer")
        runCurrent()
    }

    @Test
    fun `snapshot while stream is active does not close camera upon snapshot completion`() = runTest {
        val fakeBackend = FakeCameraBackend()
        val coordinator = createCoordinator(fakeBackend)
        runCurrent()

        val streamFlow = coordinator.openStream("stream-client")
        runCurrent()
        assertTrue(fakeBackend.isOpened)

        // Now trigger snapshot
        val snap = async { coordinator.takeSnapshot() }
        runCurrent()

        fakeBackend.emitFrame(data = byteArrayOf(42))
        runCurrent()

        val snapshot = snap.await()
        assertEquals(42.toByte(), snapshot.jpegBytes[0])

        // Stream is still active, so camera must remain OPEN
        assertTrue(fakeBackend.isOpened)
        assertTrue(fakeBackend.isRepeating)
        val activeState = coordinator.state.value as CameraState.Active
        assertEquals(1, activeState.activeStreams)

        // Close stream
        coordinator.closeStream("stream-client")
        runCurrent()

        // Now it closes
        assertFalse(fakeBackend.isOpened)
        assertEquals(CameraState.Closed, coordinator.state.value)
    }

    @Test
    fun `stream collector cancellation automatically triggers closeStream`() = runTest {
        val fakeBackend = FakeCameraBackend()
        val coordinator = createCoordinator(fakeBackend)
        runCurrent()

        val streamFlow = coordinator.openStream("auto-cleanup-client")
        runCurrent()
        assertTrue(fakeBackend.isOpened)

        val job = launch {
            streamFlow.collect {
                // consume frames
            }
        }
        runCurrent()

        // Cancel consumer job
        job.cancel()
        runCurrent()

        // Coordinator should have cleaned up the stream and closed backend
        assertFalse(fakeBackend.isOpened)
        assertEquals(CameraState.Closed, coordinator.state.value)
    }

    @Test
    fun `setEnabled false cancels pending operations, closes backend, and rejects new commands`() = runTest {
        val fakeBackend = FakeCameraBackend()
        val coordinator = createCoordinator(fakeBackend)
        runCurrent()

        supervisorScope {
            val snap = async { coordinator.takeSnapshot() }
            runCurrent()
            assertTrue(fakeBackend.isOpened)

            // Disable camera access
            coordinator.setEnabled(false)
            runCurrent()

            // Snapshot should fail with CameraDisabledException
            try {
                snap.await()
                fail("Expected CameraDisabledException")
            } catch (e: Exception) {
                assertTrue(e is CameraDisabledException)
            }
        }

        // Camera must be closed and state Disabled
        assertFalse(fakeBackend.isOpened)
        assertEquals(CameraState.Disabled, coordinator.state.value)

        // New snapshot attempt must be rejected immediately
        try {
            coordinator.takeSnapshot()
            fail("Expected CameraDisabledException")
        } catch (e: Exception) {
            assertTrue(e is CameraDisabledException)
        }

        // Re-enabling transitions state to Closed
        coordinator.setEnabled(true)
        runCurrent()
        assertEquals(CameraState.Closed, coordinator.state.value)
    }

    @Test
    fun `stale generation events are safely ignored`() = runTest {
        val fakeBackend = FakeCameraBackend()
        val coordinator = createCoordinator(fakeBackend)
        runCurrent()

        // Open camera first time (generation 1)
        val snap = async { coordinator.takeSnapshot() }
        runCurrent()
        assertTrue(fakeBackend.isOpened)

        // Fulfill snapshot
        fakeBackend.emitFrame(data = byteArrayOf(1))
        runCurrent()
        snap.await()
        assertEquals(CameraState.Closed, coordinator.state.value)

        // Simulate delayed stale FrameAvailable event from old generation
        fakeBackend.emitFrame(data = byteArrayOf(99))
        runCurrent()

        // State remains Closed, no spurious transitions
        assertEquals(CameraState.Closed, coordinator.state.value)
    }

    @Test
    fun `backend error fails pending snapshot and can recover on next request`() = runTest {
        val fakeBackend = FakeCameraBackend()
        val coordinator = createCoordinator(fakeBackend)
        runCurrent()

        supervisorScope {
            val snap = async { coordinator.takeSnapshot() }
            runCurrent()
            assertTrue(fakeBackend.isOpened)

            // Inject backend error
            fakeBackend.emitEvent(CameraBackendEvent.Error("HAL fatal sensor crash", isFatal = true))
            runCurrent()

            try {
                snap.await()
                fail("Expected exception due to backend error")
            } catch (e: Exception) {
                assertNotNull(e.message)
            }
        }

        val failedState = coordinator.state.value as CameraState.Failed
        assertEquals("HAL fatal sensor crash", failedState.reason)
        assertFalse(failedState.isRecoverable)

        // Subsequent request should attempt recovery / reopen
        val retrySnap = async { coordinator.takeSnapshot() }
        runCurrent()
        assertTrue(fakeBackend.isOpened)

        fakeBackend.emitFrame(data = byteArrayOf(77))
        runCurrent()

        val snapshot = retrySnap.await()
        assertEquals(77.toByte(), snapshot.jpegBytes[0])
        assertEquals(CameraState.Closed, coordinator.state.value)
    }

    @Test
    fun `updateConfig with facing change recreates session with requested camera`() = runTest {
        val fakeBackend = FakeCameraBackend()
        val coordinator = createCoordinator(fakeBackend)
        runCurrent()

        val stream = coordinator.openStream("stream-1")
        runCurrent()

        assertEquals("0", fakeBackend.lastOpenedCameraId) // Default BACK camera

        // Switch to FRONT camera
        coordinator.updateConfig(
            CameraConfig(
                facing = LensFacing.FRONT,
                targetWidth = 640,
                targetHeight = 480,
            )
        )
        runCurrent()

        // Backend should be reopened with camera 1 (FRONT)
        assertEquals("1", fakeBackend.lastOpenedCameraId)
        assertEquals(Size(640, 480), fakeBackend.lastPlan?.outputSize)

        coordinator.closeStream("stream-1")
        runCurrent()
        assertEquals(CameraState.Closed, coordinator.state.value)
    }

    @Test
    fun `retention policy Retained keeps camera open without repeating request when idle`() = runTest {
        val fakeBackend = FakeCameraBackend()
        val customPolicy = object : RetentionPolicy {
            override fun decide(input: RetentionInput): RetentionDecision {
                return RetentionDecision.RETAIN
            }
        }
        val coordinator = createCoordinator(fakeBackend, retentionPolicy = customPolicy)
        runCurrent()

        val snap = async { coordinator.takeSnapshot() }
        runCurrent()
        assertTrue(fakeBackend.isOpened)
        assertTrue(fakeBackend.isRepeating)

        fakeBackend.emitFrame(data = byteArrayOf(11))
        runCurrent()
        snap.await()

        // When snapshot completes, camera should be RETAINED: repeating stopped, but camera device still open
        assertFalse(fakeBackend.isRepeating)
        assertTrue(fakeBackend.isOpened)
        val state = coordinator.state.value
        assertTrue("Expected Retained state, but was $state", state is CameraState.Retained)
    }

    @Test
    fun `snapshot waits for AE converged frame while stream receives unconverged frames immediately`() = runTest {
        val fakeBackend = FakeCameraBackend()
        val coordinator = createCoordinator(fakeBackend)
        runCurrent()

        val streamFlow = coordinator.openStream("client-1")
        runCurrent()

        val receivedStreamFrames = mutableListOf<Frame>()
        val streamJob = backgroundScope.launch {
            streamFlow.collect { receivedStreamFrames.add(it) }
        }

        val snapDeferred = async { coordinator.takeSnapshot() }
        runCurrent()

        // 1. Emit unconverged frame (AE still searching)
        fakeBackend.emitFrame(data = byteArrayOf(1), isAeConverged = false)
        runCurrent()

        // Snapshot must NOT be completed yet
        assertFalse(snapDeferred.isCompleted)
        // Stream should have received the frame immediately
        assertEquals(1, receivedStreamFrames.size)
        assertEquals(1.toByte(), receivedStreamFrames[0].data[0])

        // 2. Emit converged frame
        fakeBackend.emitFrame(data = byteArrayOf(99), isAeConverged = true)
        runCurrent()

        // Snapshot must now be completed with the converged frame
        assertTrue(snapDeferred.isCompleted)
        val snapshot = snapDeferred.await()
        assertEquals(99.toByte(), snapshot.jpegBytes[0])
        assertEquals(2, receivedStreamFrames.size)

        streamJob.cancel()
    }

    @Test
    fun `GracePeriodRetentionPolicy keeps camera active after snapshot and closes after grace period expires`() = runTest {
        val fakeBackend = FakeCameraBackend()
        val coordinator = createCoordinator(fakeBackend, retentionPolicy = GracePeriodRetentionPolicy(gracePeriodMs = 5000L))
        runCurrent()

        val snap1 = async { coordinator.takeSnapshot() }
        runCurrent()
        assertTrue(fakeBackend.isOpened)
        assertTrue(fakeBackend.isRepeating)

        fakeBackend.emitFrame(data = byteArrayOf(10))
        runCurrent()
        val res1 = snap1.await()
        assertEquals(10.toByte(), res1.jpegBytes[0])

        // After snapshot completion, camera must STILL BE OPEN and REPEATING during grace period!
        assertTrue(fakeBackend.isOpened)
        assertTrue(fakeBackend.isRepeating)
        assertTrue(coordinator.state.value is CameraState.Active)

        // Advance time by 3 seconds - still active!
        testScheduler.advanceTimeBy(3000)
        runCurrent()
        assertTrue(fakeBackend.isOpened)
        assertTrue(coordinator.state.value is CameraState.Active)

        // A second snapshot arrives within grace period - immediate fulfillment without reopen!
        val snap2 = async { coordinator.takeSnapshot() }
        runCurrent()
        fakeBackend.emitFrame(data = byteArrayOf(20))
        runCurrent()
        val res2 = snap2.await()
        assertEquals(20.toByte(), res2.jpegBytes[0])

        // Timer reset! Advance by another 3 seconds - still active!
        testScheduler.advanceTimeBy(3000)
        runCurrent()
        assertTrue(fakeBackend.isOpened)

        // Advance by remaining 2.1 seconds (total > 5s since last activity)
        testScheduler.advanceTimeBy(2100)
        runCurrent()

        // Grace period expired, camera closes!
        assertFalse(fakeBackend.isOpened)
        assertEquals(CameraState.Closed, coordinator.state.value)
    }

    @Test
    fun `warmup pre-activates camera and stays open for specified duration`() = runTest {
        val fakeBackend = FakeCameraBackend()
        val coordinator = createCoordinator(fakeBackend)
        runCurrent()
        assertEquals(CameraState.Closed, coordinator.state.value)

        // Trigger warmup for 3000ms
        val warmupJob = launch { coordinator.warmup(3000L) }
        runCurrent()
        warmupJob.join()

        // Camera is now pre-activated and repeating
        assertTrue(fakeBackend.isOpened)
        assertTrue(fakeBackend.isRepeating)
        assertTrue(coordinator.state.value is CameraState.Active)

        // Advance 3100ms
        testScheduler.advanceTimeBy(3100)
        runCurrent()

        // Grace period expired, camera closes
        assertFalse(fakeBackend.isOpened)
        assertEquals(CameraState.Closed, coordinator.state.value)
    }
}
