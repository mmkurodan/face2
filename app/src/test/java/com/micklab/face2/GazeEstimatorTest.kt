package com.micklab.face2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GazeEstimatorTest {

    @Test
    fun mapsNeutralFrameToScreenCenterAfterCalibration() {
        val estimator = GazeEstimator()
        estimator.setCalibration(TestFixtures.calibration())

        val estimate = estimator.estimate(TestFixtures.frame(timestampMs = 0L))

        assertEquals(0.5f, estimate!!.rawPoint.x, 0.05f)
        assertEquals(0.5f, estimate.rawPoint.y, 0.05f)
    }

    @Test
    fun movesRightWhenBothEyesShiftRight() {
        val estimator = GazeEstimator()
        estimator.setCalibration(TestFixtures.calibration())
        estimator.estimate(TestFixtures.frame(timestampMs = 0L))

        val estimate = estimator.estimate(
            TestFixtures.frame(
                timestampMs = 33L,
                leftLocal = Vec3(0.12f, -0.01f, 0f),
                rightLocal = Vec3(0.10f, -0.01f, 0f),
            ),
        )

        assertTrue(estimate!!.rawPoint.x > 0.5f)
        assertTrue(estimate.smoothedPoint.x > 0.5f)
    }

    @Test
    fun enablesDwellAfterStableFrames() {
        val estimator = GazeEstimator()
        estimator.setCalibration(TestFixtures.calibration())

        estimator.estimate(TestFixtures.frame(timestampMs = 0L))
        estimator.estimate(TestFixtures.frame(timestampMs = 120L))
        estimator.estimate(TestFixtures.frame(timestampMs = 240L))
        val estimate = estimator.estimate(TestFixtures.frame(timestampMs = 360L))

        assertTrue(estimate!!.isDwelling)
        assertTrue(estimate.dwellDurationMs >= 300L)
    }
}
