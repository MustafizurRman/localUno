package com.mutsho.localuno

import com.mutsho.localuno.ui.components.DialMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The hand dial's arithmetic (HandArc). All three of these are modular-arithmetic variations whose
 * failures look identical on screen - the dial turns either way - so they are checked here rather
 * than by eye.
 */
class DialMathTest {

    @Test
    fun `step is clamped at both ends`() {
        // A tiny hand must not spray across the whole rim...
        assertEquals(9.5f, DialMath.step(3), 1e-4f)
        // ...and a huge one must stop packing tighter, or a No Mercy hand becomes unusable.
        assertEquals(4.2f, DialMath.step(40), 1e-4f)
        // In between it is the source's 100/n.
        assertEquals(100f / 14f, DialMath.step(14), 1e-4f)
    }

    @Test
    fun `at rest the middle card sits on the needle`() {
        for (n in listOf(1, 2, 5, 8, 13, 20, 31)) {
            val best = DialMath.nearest(n, theta = 0f)
            val expected = (n - 1) / 2   // lower middle for an even hand
            assertTrue(
                "n=$n picked $best, wanted about $expected",
                abs(best - expected) <= 1
            )
            assertTrue(
                "n=$n: the chosen card should be essentially on the needle",
                abs(DialMath.angleOf(best, n, 0f)) <= DialMath.step(n) / 2f + 1e-3f
            )
        }
    }

    @Test
    fun `every angle stays inside the wrapped span`() {
        val n = 17
        val span = DialMath.span(n)
        // Well past a full turn in both directions - the wrap is what makes the dial endless.
        var theta = -3f * span
        while (theta <= 3f * span) {
            for (i in 0 until n) {
                val a = DialMath.angleOf(i, n, theta)
                assertTrue(
                    "i=$i theta=$theta gave $a, outside +/-${span / 2}",
                    a >= -span / 2f - 1e-3f && a <= span / 2f + 1e-3f
                )
            }
            theta += span / 7f
        }
    }

    @Test
    fun `spinning to a card puts exactly that card on the needle`() {
        val n = 14
        // From a range of starting angles, including ones several wraps away.
        for (theta in listOf(0f, 3.3f, -12.7f, 61f, -140f, 999f)) {
            for (i in 0 until n) {
                val after = theta + DialMath.spinDelta(i, n, theta)
                assertEquals(
                    "spinning to card $i from theta=$theta did not select it",
                    i, DialMath.nearest(n, after)
                )
                assertTrue(
                    "card $i should be centred, not merely nearest",
                    abs(DialMath.angleOf(i, n, after)) < 1e-2f
                )
            }
        }
    }

    /**
     * The dial's own rotation must stay folded, however long it is played.
     *
     * Left unfolded it only ever grows, and Float precision degrades with magnitude until the
     * quantum exceeds [DialMath.REST_EPSILON] and the snap can no longer park the dial. This is
     * checked by simulating rather than by reasoning about it, because the mutation happens in four
     * separate places in the composable and it only takes one of them to forget.
     */
    @Test
    fun `rotation stays folded across a long session`() {
        val n = 20
        val span = DialMath.span(n)
        val rng = java.util.Random(20260812)
        var theta = 0f
        repeat(200_000) {
            theta = when (rng.nextInt(3)) {
                0 -> DialMath.normalise(theta + (rng.nextFloat() - 0.5f) * 90f, n)   // a drag
                1 -> DialMath.normalise(theta + rng.nextFloat() * 40f, n)            // a flick
                else -> DialMath.normalise(
                    theta + DialMath.spinDelta(rng.nextInt(n), n, theta), n
                )                                                                     // a tap
            }
            assertTrue("rotation escaped to $theta", abs(theta) <= span / 2f + 1e-3f)
        }
        // And precision is still good enough to park on a detent after all of that.
        repeat(400) {
            val best = DialMath.nearest(n, theta)
            val off = DialMath.angleOf(best, n, theta)
            if (abs(off) > DialMath.REST_EPSILON) {
                theta = DialMath.normalise(theta - off * DialMath.SNAP, n)
            }
        }
        val residual = abs(DialMath.angleOf(DialMath.nearest(n, theta), n, theta))
        assertTrue("could no longer settle: $residual off", residual <= DialMath.REST_EPSILON)
    }

    @Test
    fun `the snap converges onto a detent and then parks`() {
        val n = 11
        // Start deliberately between two cards, where the snap has the furthest to travel.
        var theta = DialMath.step(n) / 2f
        repeat(400) {
            val best = DialMath.nearest(n, theta)
            val off = DialMath.angleOf(best, n, theta)
            if (abs(off) > DialMath.REST_EPSILON) theta -= off * DialMath.SNAP
        }
        val best = DialMath.nearest(n, theta)
        val residual = abs(DialMath.angleOf(best, n, theta))
        assertTrue("dial parked $residual off the needle", residual <= DialMath.REST_EPSILON)
    }

    @Test
    fun `coasting decays to a stop`() {
        var velocity = 40f
        var frames = 0
        while (abs(velocity) > DialMath.COAST_EPSILON && frames < 10_000) {
            velocity *= DialMath.FRICTION
            frames++
        }
        assertTrue("a flick never stopped", abs(velocity) <= DialMath.COAST_EPSILON)
        // Roughly two seconds at 60fps - long enough to feel like a spin, short enough that the
        // turn timer is not spent watching it.
        assertTrue("a flick coasted for $frames frames", frames in 60..200)
    }

    @Test
    fun `an empty hand selects nothing instead of crashing`() {
        assertEquals(-1, DialMath.nearest(0, 0f))
        assertTrue(DialMath.span(0) > 0f)   // never a divide-by-zero in the wrap
    }
}

/**
 * The hand's composition keys. A duplicate key crashes the whole app from inside the Compose
 * runtime, and the hand is built from data sent by another phone that may be running an older,
 * buggier build - so this is checked rather than assumed.
 */
class HandKeyTest {

    @Test
    fun `unique ids all get occurrence zero`() {
        // The normal case must stay a stable identity across sorts, so every suffix is 0.
        val ids = listOf(4, 9, 1, 27, 3)
        assertEquals(listOf(0, 0, 0, 0, 0), com.mutsho.localuno.ui.components.occurrenceIndices(ids))
    }

    @Test
    fun `duplicates get distinct suffixes instead of colliding`() {
        assertEquals(
            listOf(0, 0, 1, 2),
            com.mutsho.localuno.ui.components.occurrenceIndices(listOf(7, 3, 7, 7))
        )
    }

    @Test
    fun `every id-suffix pair is unique however mangled the input`() {
        // Worst case: an old host sends the same card many times over.
        for (ids in listOf(
            listOf(1, 1, 1, 1, 1, 1),
            listOf(-1, -1, -1),
            emptyList(),
            listOf(5),
            (1..30).map { it % 4 }
        )) {
            val pairs = ids.zip(com.mutsho.localuno.ui.components.occurrenceIndices(ids))
            assertEquals("collision in $ids", pairs.size, pairs.toSet().size)
        }
    }
}
