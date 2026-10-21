package io.github.ctxinf.cameraagent.adapters.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionCountDisplayTest {
    @Test fun briefReconnectDoesNotFlicker() {
        val display = ConnectionCountDisplay()
        assertEquals(1, display.update(1, 0))
        assertEquals(1, display.update(0, 100))
        assertEquals(1, display.update(1, 1000))
        assertEquals(1, display.update(0, 2000))
        assertEquals(1, display.update(0, 6999))
        assertEquals(0, display.update(0, 7000))
    }

    @Test fun increasesAndServiceStopAreImmediate() {
        val display = ConnectionCountDisplay()
        assertEquals(2, display.update(2, 0))
        assertEquals(2, display.update(1, 100))
        assertEquals(3, display.update(3, 200))
        assertEquals(0, display.update(0, 300, reset = true))
    }
}
