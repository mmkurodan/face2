package com.micklab.face2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationManagerTest {

    @Test
    fun completesCalibrationAfterStableSamples() {
        val manager = CalibrationManager(requiredSampleCount = 3)
        manager.beginCalibration()

        var state = manager.currentState()
        repeat(3) { index ->
            state = manager.consume(TestFixtures.frame(timestampMs = index.toLong()))
        }

        assertTrue(state.isCalibrated)
        assertEquals(1f, state.progress, 0.0001f)
        assertNotNull(state.baseline)
    }

    @Test
    fun ignoresLowConfidenceFrames() {
        val manager = CalibrationManager(requiredSampleCount = 3)
        manager.beginCalibration()

        val state = manager.consume(
            TestFixtures.frame(
                timestampMs = 0L,
                faceConfidence = 0.2f,
            ),
        )

        assertFalse(state.isCalibrated)
        assertEquals(0, state.sampleCount)
    }
}
