package com.mutsho.localuno

import com.mutsho.localuno.ui.components.seatPositionFraction
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Opponent seat placement, at every table size the app allows (2..8 players, so 1..7 opponents).
 *
 * Checked arithmetically because the failure is only obvious at the sizes nobody tests by hand: a
 * full eight-player table used to put the top-centre seat BELOW both its neighbours, a visible
 * notch in the middle of the arc, and every smaller table looked fine.
 */
class SeatArcTest {

    /** Avatar footprint as a fraction of the felt - roughly 78dp wide on a ~390dp board. */
    private val avatarW = 0.20f
    private val avatarH = 0.13f

    @Test
    fun `no two seats overlap at any table size`() {
        for (total in 1..7) {
            val seats = (0 until total).map { seatPositionFraction(it, total) }
            for (i in seats.indices) {
                for (j in i + 1 until seats.size) {
                    val dx = abs(seats[i].first - seats[j].first)
                    val dy = abs(seats[i].second - seats[j].second)
                    assertTrue(
                        "total=$total seats $i and $j overlap (dx=$dx dy=$dy)",
                        dx >= avatarW || dy >= avatarH
                    )
                }
            }
        }
    }

    @Test
    fun `the arc peaks at its centre seat`() {
        // Odd counts have a genuine middle seat, and it must be the highest one on the felt - that
        // is what makes the row read as an arc rather than as a zigzag.
        for (total in listOf(3, 5, 7)) {
            val seats = (0 until total).map { seatPositionFraction(it, total) }
            val apex = (total - 1) / 2
            val highest = seats.indices.minByOrNull { seats[it].second }
            assertTrue(
                "total=$total: highest seat was $highest, expected the middle seat $apex " +
                    "(y values ${seats.map { "%.1f".format(it.second * 100) }})",
                highest == apex
            )
        }
    }

    @Test
    fun `adjacent seats always alternate rows once staggering starts`() {
        // The stagger exists because past four opponents an avatar is wider than the gap between
        // neighbours; it only helps if NEIGHBOURS are the ones separated.
        for (total in 5..7) {
            val seats = (0 until total).map { seatPositionFraction(it, total) }
            for (k in 0 until total - 1) {
                val gap = abs(seats[k].second - seats[k + 1].second)
                assertTrue(
                    "total=$total: seats $k and ${k + 1} are nearly level (gap=$gap)",
                    gap >= avatarH * 0.7f
                )
            }
        }
    }

    @Test
    fun `every seat stays inside the felt`() {
        for (total in 1..7) {
            for (k in 0 until total) {
                val (x, y) = seatPositionFraction(k, total)
                assertTrue("total=$total seat $k x=$x off the felt", x in 0.02f..0.98f)
                // Leaves room for the avatar and its name label below it.
                assertTrue("total=$total seat $k y=$y off the felt", y in 0.02f..0.62f)
            }
        }
    }

    @Test
    fun `seats are laid out left to right`() {
        for (total in 2..7) {
            val xs = (0 until total).map { seatPositionFraction(it, total).first }
            assertTrue("total=$total: x order is not monotonic ($xs)", xs.zipWithNext().all { it.first < it.second })
        }
    }
}
