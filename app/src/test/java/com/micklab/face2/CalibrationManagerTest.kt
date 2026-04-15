package com.micklab.face2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationManagerTest {

    @Test
    fun capturesCalibrationFromTappedFrame() {
        val manager = CalibrationManager()
        manager.beginCalibration()

        val state = manager.capture(TestFixtures.frame(timestampMs = 0L))

        assertTrue(state.isCalibrated)
        assertEquals(1f, state.progress, 0.0001f)
        assertEquals(1, state.sampleCount)
        assertNotNull(state.baseline)
    }

    @Test
    fun ignoresLowConfidenceTap() {
        val manager = CalibrationManager()
        manager.beginCalibration()

        val state = manager.capture(
            TestFixtures.frame(
                timestampMs = 0L,
                faceConfidence = 0.2f,
            ),
        )

        assertFalse(state.isCalibrated)
        assertEquals(0, state.sampleCount)
    }

    @Test
    fun capturesCalibrationWithoutRequiringFaceCenteredInPreview() {
        val manager = CalibrationManager()
        manager.beginCalibration()

        val state = manager.capture(
            TestFixtures.frame(
                timestampMs = 0L,
                eyeCenter = Vec3(0.78f, 0.34f, 0f),
                nose = Vec3(0.78f, 0.49f, -0.02f),
            ),
        )

        assertTrue(state.isCalibrated)
        assertEquals(1, state.sampleCount)
    }
}
