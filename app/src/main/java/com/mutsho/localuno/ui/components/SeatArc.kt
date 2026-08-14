package com.mutsho.localuno.ui.components

import kotlin.math.PI
import kotlin.math.sin

/** Arc height when seats sit on one row; flattened when they stagger, so the rows stay distinct. */
private const val ARC_SINGLE_ROW = 22f
private const val ARC_STAGGERED = 6f

/**
 * How far the back row drops.
 *
 * Must comfortably exceed [ARC_STAGGERED], because the arc works AGAINST the drop at the ends of
 * the row: a front-row seat near the edge is already pushed down by the arc, so its back-row
 * neighbour is barely below it. At a drop of 16 against an arc of 12 the outermost pair on a
 * six- and seven-opponent table still overlapped.
 */
private const val ROW_DROP = 18f

/**
 * Fractional (x, y) in [0,1] for opponent seat [k] of [total] around the felt's top arc.
 * More players -> a flatter, wider arc; past 4 the seats alternate between two rows, because at
 * that point an avatar is wider than the horizontal gap between neighbours.
 *
 * Adapted from seatPos() in UnoBoard.dc.html rather than copied. The original staggered on absolute
 * index parity - `k % 2 == 1` - which breaks the arc whenever the middle seat happens to have an
 * odd index. At seven opponents (a full eight-player table) that put the apex seat, the one that
 * should be highest, into the BACK row and therefore below both its neighbours: the arc read
 * `[31, 33, 11.9, 22, 11.9, 33, 31]`, a visible notch in the middle of the table.
 *
 * Two changes fix it:
 *
 *  - The stagger alternates relative to the APEX rather than to index zero, so the top-centre seat
 *    is always on the front row and the arc peaks where the eye expects it to.
 *  - The arc flattens while staggering. The row drop and the arc's own rise were previously
 *    comparable in size, so they fought: a back-row seat could still land above a front-row one.
 *    A shallow arc plus a decisive drop reads as two clean rows instead of one jagged line.
 */
fun seatPositionFraction(k: Int, total: Int): Pair<Float, Float> {
    if (total <= 1) return 0.5f to 0.12f

    val t = k / (total - 1).toFloat()
    val x = (8f + 84f * t) / 100f

    val staggered = total > 4
    val arc = if (staggered) ARC_STAGGERED else ARC_SINGLE_ROW
    // Integer apex; for an even count there is no true middle seat, so this takes the left of the
    // two. Adjacent seats still alternate, which is all the stagger has to guarantee.
    val apex = (total - 1) / 2
    val row = if (staggered && Math.floorMod(k - apex, 2) != 0) ROW_DROP else 0f

    val dip = sin(PI.toFloat() * t)
    val y = (9f + (1f - dip) * arc + row) / 100f
    return x to y
}
