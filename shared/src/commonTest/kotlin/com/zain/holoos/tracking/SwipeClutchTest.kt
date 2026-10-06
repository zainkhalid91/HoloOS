package com.zain.holoos.tracking

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the gesture engine with a synthetic open hand to check the return-stroke
 * filter: swipe right, travel back left, swipe right again.
 */
class SwipeClutchTest {
    private class Recorder : GestureSink {
        val flings = ArrayList<Float>()
        val ignored = ArrayList<String>()
        val spreads = ArrayList<Float>()
        override fun onCursor(x: Float, y: Float, timeMs: Long) {}
        override fun onPress(pressed: Boolean) {}
        override fun onTap(x: Float, y: Float) {}
        override fun onDrag(dx: Float, dy: Float) {}
        override fun onGrip(closed: Boolean) {}
        override fun onFling(vx: Float, vy: Float) { flings += vx }
        override fun onHold() {}
        override fun onSpread(amount: Float, at: Offset) { spreads += amount }
        override fun onReturnIgnored(kind: String) { ignored += kind }
        override fun onZoom(amount: Float, at: Offset) {}
        override fun onBack() {}
        override fun onHome() {}
        override fun onAccept() {}
        override fun onHandsLost() {}
    }

    private val aspect = 16f / 9f

    /** An open right hand (fingers up), palm centred at image x = [cx], y = 0.5. Offsets in height units. */
    private fun openHand(cx: Float): TrackedHand {
        val pts = listOf(
            0f to 0.12f,
            -0.04f to 0.09f, -0.07f to 0.06f, -0.10f to 0.03f, -0.13f to 0.0f,
            -0.03f to 0f, -0.035f to -0.05f, -0.04f to -0.08f, -0.045f to -0.11f,
            0f to 0f, 0f to -0.055f, 0f to -0.09f, 0f to -0.12f,
            0.03f to 0.005f, 0.035f to -0.045f, 0.04f to -0.08f, 0.045f to -0.11f,
            0.055f to 0.02f, 0.065f to -0.02f, 0.07f to -0.05f, 0.075f to -0.075f,
        )
        val arr = FloatArray(63)
        pts.forEachIndexed { i, (dx, dy) -> arr[i * 3] = cx + dx / aspect; arr[i * 3 + 1] = 0.5f + dy; arr[i * 3 + 2] = 0f }
        return TrackedHand(true, 0.95f, arr)
    }

    private fun run(xs: List<Float>): Recorder {
        val rec = Recorder()
        val engine = GestureEngine(rec)
        engine.flingClaimed = true
        var t = 1_000_000L
        xs.forEachIndexed { i, x ->
            t += 33
            val f = TrackFrame(i, t.toDouble(), t, 30f, 1280, 720, null, listOf(openHand(x)))
            engine.process(f, t, 1920f, 1080f)
        }
        return rec
    }

    private fun runTwo(pairs: List<Pair<Float, Float>>): Recorder {
        val rec = Recorder()
        val engine = GestureEngine(rec)
        engine.flingClaimed = true
        var t = 1_000_000L
        pairs.forEachIndexed { i, (l, r) ->
            t += 33
            val left = openHand(l).let { TrackedHand(false, 0.95f, it.pts) }
            val f = TrackFrame(i, t.toDouble(), t, 30f, 1280, 720, null, listOf(openHand(r), left))
            engine.process(f, t, 1920f, 1080f)
        }
        return rec
    }

    private fun spreadMove(from: Float, to: Float, n: Int) = List(n) { val g = from + (to - from) * (it + 1) / n; (0.5f - g) to (0.5f + g) }
    private fun spreadStill(g: Float, n: Int) = List(n) { (0.5f - g) to (0.5f + g) }

    @Test fun bringingHandsBackAfterSpreadIsIgnored() {
        val rec = runTwo(spreadStill(0.06f, 15) + spreadMove(0.06f, 0.26f, 6) + spreadStill(0.26f, 3) + spreadMove(0.26f, 0.08f, 6) + spreadStill(0.08f, 10))
        assertTrue(rec.spreads.any { it > 0f }, "spreading apart should zoom in")
        assertTrue(rec.spreads.none { it < 0f }, "returning the hands should not zoom out: ${rec.spreads}")
        assertTrue(rec.ignored.any { it.startsWith("spread") })
    }

    @Test fun deliberateZoomOutAfterPauseWorks() {
        val rec = runTwo(spreadStill(0.06f, 15) + spreadMove(0.06f, 0.26f, 6) + spreadStill(0.26f, 35) + spreadMove(0.26f, 0.08f, 6) + spreadStill(0.08f, 10))
        assertTrue(rec.spreads.any { it < 0f }, "closing the hands after a pause should zoom out: ${rec.spreads}")
    }

    @Test fun reversingIsNeverStuck() {
        // Swipe right, hand drifts back left (slower → ignored), then the user really swipes
        // left at full speed: that must spin left.
        val rec = run(
            still(0.3f, 15) + move(0.3f, 0.6f, 6) + still(0.6f, 3) +
                move(0.6f, 0.4f, 6) + still(0.4f, 3) + move(0.4f, 0.1f, 6) + still(0.1f, 10),
        )
        assertEquals(1, rec.ignored.count { it == "swipe" }, "only the slow return is ignored: ${rec.ignored}")
        assertTrue(rec.flings.any { it < 0f }, "the full-speed leftward swipe must spin left: ${rec.flings}")
    }

    @Test fun neverMoreThanTwoIgnoredInARow() {
        // Right swipe, then three slower left strokes: the third must get through.
        val rec = run(
            still(0.3f, 15) + move(0.3f, 0.7f, 6) + still(0.7f, 3) +
                move(0.7f, 0.55f, 6) + still(0.55f, 3) + move(0.55f, 0.4f, 6) + still(0.4f, 3) + move(0.4f, 0.25f, 6) + still(0.25f, 10),
        )
        assertTrue(rec.flings.any { it < 0f }, "after two ignored strokes the next one counts: ${rec.flings} ${rec.ignored}")
    }

    @Test fun diagonalSwipeKeepsItsAngle() {
        val rec = Recorder()
        val engine = GestureEngine(object : GestureSink by rec {
            override fun onFling(vx: Float, vy: Float) { rec.flings += vx; diag += vy / vx }
        })
        engine.flingClaimed = true
        var t = 1_000_000L
        val path = List(15) { 0.3f to 0.4f } + List(6) { (0.3f + 0.05f * (it + 1)) to (0.4f + 0.05f * (it + 1)) } + List(10) { 0.6f to 0.7f }
        path.forEachIndexed { i, (x, y) ->
            t += 33
            val h = openHand(x).let { hh -> TrackedHand(true, 0.95f, FloatArray(63) { k -> if (k % 3 == 1) hh.pts[k] - 0.5f + y else hh.pts[k] }) }
            engine.process(TrackFrame(i, t.toDouble(), t, 30f, 1280, 720, null, listOf(h)), t, 1920f, 1080f)
        }
        assertTrue(diag.isNotEmpty(), "diagonal swipe should fling")
        // Equal normalised motion in x and y: screen ratio ≈ (0.05·1080/0.58) / (0.05·1920/0.62) ≈ 0.6
        assertTrue(diag.all { it > 0.3f }, "the spin keeps the diagonal angle, not one axis: $diag")
    }
    private val diag = ArrayList<Float>()

    @Test fun zoomReversingIsNeverStuck() {
        // Zoom in, hands come back slowly (ignored), then close them fast: that zooms out.
        val rec = runTwo(
            spreadStill(0.06f, 15) + spreadMove(0.06f, 0.26f, 6) + spreadStill(0.26f, 3) +
                spreadMove(0.26f, 0.08f, 6) + spreadStill(0.08f, 3) +
                spreadMove(0.08f, 0.28f, 4) + spreadStill(0.28f, 3) + spreadMove(0.28f, 0.06f, 4) + spreadStill(0.06f, 10),
        )
        assertTrue(rec.spreads.any { it < 0f }, "a fast close after the return must zoom out: ${rec.spreads}")
    }

    /** Open hand tilted so the back of the hand faces up (knuckles toward the camera). */
    private fun tiltedHand(cy: Float): TrackedHand {
        val base = openHand(0.5f).pts.copyOf()
        for (i in 0 until 21) {
            base[i * 3 + 1] = base[i * 3 + 1] - 0.5f + cy
            if (i >= 5) base[i * 3 + 2] = -0.1f / aspect
        }
        return TrackedHand(true, 0.95f, base)
    }

    @Test fun palmLedReturnIsIgnoredEvenAtSameSpeed() {
        val vys = ArrayList<Float>(); val ignored = ArrayList<String>()
        val engine = GestureEngine(object : GestureSink by Recorder() {
            override fun onFling(vx: Float, vy: Float) { vys += vy }
            override fun onReturnIgnored(kind: String) { ignored += kind }
        })
        engine.flingClaimed = true
        var t = 1_000_000L
        val ys = List(15) { 0.6f } + List(6) { 0.6f - 0.05f * (it + 1) } + List(3) { 0.3f } + List(6) { 0.3f + 0.05f * (it + 1) } + List(10) { 0.6f }
        ys.forEachIndexed { i, y ->
            t += 33
            engine.process(TrackFrame(i, t.toDouble(), t, 30f, 1280, 720, null, listOf(tiltedHand(y))), t, 1920f, 1080f)
        }
        assertTrue(vys.isNotEmpty() && vys.all { it < 0f }, "back-led upward swipe spins, palm-led return doesn't: $vys")
        assertTrue("swipe" in ignored, "the palm-led stroke down is a return: $ignored")
    }

    private fun still(x: Float, n: Int) = List(n) { x }
    private fun move(from: Float, to: Float, n: Int) = List(n) { from + (to - from) * (it + 1) / n }

    @Test fun returnStrokeAfterSwipeIsIgnored() {
        val rec = run(still(0.3f, 15) + move(0.3f, 0.6f, 6) + still(0.6f, 3) + move(0.6f, 0.36f, 6) + still(0.36f, 10))
        assertTrue(rec.flings.isNotEmpty(), "the swipe right should fling")
        assertTrue(rec.flings.all { it > 0f }, "no leftward fling from the return stroke: ${rec.flings}")
        assertTrue("swipe" in rec.ignored, "return stroke should be reported as ignored")
    }

    @Test fun secondSwipeSameDirectionStillWorks() {
        val rec = run(
            still(0.3f, 15) + move(0.3f, 0.6f, 6) + still(0.6f, 3) +
                move(0.6f, 0.36f, 6) + still(0.36f, 3) + move(0.36f, 0.66f, 6) + still(0.66f, 10),
        )
        val bursts = rec.flings.size
        assertTrue(bursts >= 2, "both rightward swipes should fling, got $bursts")
        assertTrue(rec.flings.all { it > 0f })
    }

    @Test fun deliberateLeftSwipeAfterAPauseWorks() {
        val rec = run(still(0.3f, 15) + move(0.3f, 0.6f, 6) + still(0.6f, 30) + move(0.6f, 0.3f, 6) + still(0.3f, 10))
        assertTrue(rec.flings.any { it < 0f }, "a left swipe after a 1 s pause is deliberate: ${rec.flings}")
        assertEquals(0, rec.ignored.size)
    }
}
