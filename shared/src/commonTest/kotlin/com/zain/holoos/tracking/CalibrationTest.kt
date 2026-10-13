package com.zain.holoos.tracking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CalibrationTest {
    private val targets = listOf(0.10f to 0.14f, 0.90f to 0.14f, 0.90f to 0.86f, 0.10f to 0.86f, 0.50f to 0.50f)

    /** Body-space hand positions for a relaxed reach: ~1.6 shoulder widths across, ~1.2 tall. */
    private fun reach(jitter: Float = 0f) = targets.mapIndexed { i, (tx, ty) ->
        val n = if (i % 2 == 0) jitter else -jitter
        floatArrayOf(-0.45f + tx * 1.6f + n, -1.0f + ty * 1.2f - n, tx, ty)
    }

    @Test fun cleanReachIsAccepted() {
        val r = Calibration.fit(true, reach(0.03f))
        assertIs<Calibration.Companion.Fitted>(r)
        val p = r.calibration.apply(-0.45f + 0.5f * 1.6f, -1.0f + 0.5f * 1.2f)
        assertEquals(0.5f, p.x, 0.03f); assertEquals(0.5f, p.y, 0.03f)
        assertTrue(r.rmsError < 0.05f)
    }

    @Test fun mirroredReachIsRejected() {
        val mirrored = reach().map { floatArrayOf(-it[0], it[1], it[2], it[3]) }
        assertIs<Calibration.Companion.Rejected>(Calibration.fit(true, mirrored))
    }

    @Test fun barelyMovingIsRejected() {
        val tiny = reach().map { floatArrayOf(it[0] * 0.1f, it[1] * 0.1f, it[2], it[3]) }
        assertIs<Calibration.Companion.Rejected>(Calibration.fit(true, tiny))
    }

    @Test fun scatteredPinchesAreRejected() {
        val scattered = reach().mapIndexed { i, s -> floatArrayOf(s[0] + (if (i == 1) -1.2f else 0f), s[1] + (if (i == 2) -0.9f else 0f), s[2], s[3]) }
        assertIs<Calibration.Companion.Rejected>(Calibration.fit(true, scattered))
    }

    @Test fun serialisationRoundTrips() {
        val c = (Calibration.fit(true, reach()) as Calibration.Companion.Fitted).calibration
        val back = Calibration.parse(c.serialize())!!
        assertEquals(c.apply(0.2f, -0.3f), back.apply(0.2f, -0.3f))
    }
}
