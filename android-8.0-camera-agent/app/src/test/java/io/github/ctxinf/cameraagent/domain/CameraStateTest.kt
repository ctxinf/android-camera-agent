package io.github.ctxinf.cameraagent.domain

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraStateTest {

    @Test
    fun testCameraStateTransitions() {
        val state: CameraState = CameraState.Closed
        assertEquals(CameraState.Closed, state)

        val opening = CameraState.Opening("0")
        assertEquals("0", opening.cameraId)
    }

    @Test
    fun testCameraStateSerialization() {
        val json = Json { prettyPrint = false }
        val state: CameraState = CameraState.Active(
            cameraId = "0",
            width = 1280,
            height = 720,
            activeStreams = 1,
        )

        val encoded = json.encodeToString(CameraState.serializer(), state)
        assertTrue(encoded.contains("1280"))

        val decoded = json.decodeFromString(CameraState.serializer(), encoded)
        assertEquals(state, decoded)
    }
}
