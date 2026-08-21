package com.mutsho.localuno

import com.mutsho.localuno.engine.GameEngine
import com.mutsho.localuno.model.GameMode
import com.mutsho.localuno.model.GameRules
import com.mutsho.localuno.model.GameSettings
import com.mutsho.localuno.model.Player
import com.mutsho.localuno.viewmodel.turnClockStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Declaring UNO must not stop the turn clock.
 *
 * The bug: `turnStartedAtMillis` is set by GameViewModel and by nothing else - the engine has never
 * heard of the field, so its own copy sits at 0L from the deal to the end of the round. Every state
 * the engine hands back therefore carries a meaningless 0, and applyHostState used to pass that
 * straight through whenever it was told not to restart the clock.
 *
 * "Don't restart the clock" thus meant "set the clock to nothing". A 0 stamp reads as "turn hasn't
 * started", so every countdown on every device jumped to full and froze; worse, the already-armed
 * timeout woke up, compared the state's stamp against its own, saw them differ, concluded a real
 * move had beaten it, and returned without firing. The turn had no deadline left at all.
 *
 * It was reported as "pressing UNO pauses the timer". Four other paths did the same thing, and the
 * ones nobody would ever have looked for are the bot's: a bot declaring UNO, or catching someone,
 * silently disarmed the human player's clock.
 *
 * Two claims are pinned here - the decision itself, and the engine-level fact the decision rests on.
 */
class TurnClockStampTest {

    // ── The decision ────────────────────────────────────────────────────────

    @Test fun `a move that does not start a turn keeps the deadline it had`() {
        assertEquals(1_000L, turnClockStamp(previousStamp = 1_000L, startsFreshTurn = false, nowMillis = 9_000L))
    }

    @Test fun `declaring UNO never zeroes the clock`() {
        // The exact shape of the bug: the engine's 0 must not be able to reach the state.
        val carried = turnClockStamp(previousStamp = 4_242L, startsFreshTurn = false, nowMillis = 9_000L)
        assertTrue("a zero stamp reads as 'turn not started' and disarms the timeout", carried > 0L)
        assertEquals(4_242L, carried)
    }

    @Test fun `a move that starts a turn restarts the clock`() {
        assertEquals(9_000L, turnClockStamp(previousStamp = 1_000L, startsFreshTurn = true, nowMillis = 9_000L))
    }

    @Test fun `the first turn of a round gets a real stamp even with nothing before it`() {
        assertEquals(9_000L, turnClockStamp(previousStamp = 0L, startsFreshTurn = true, nowMillis = 9_000L))
    }

    @Test fun `carrying forward is idempotent`() {
        // Several clock-neutral moves in a row - a call, a catch, a bot doing both - must not erode
        // the deadline a step at a time.
        var stamp = 5_000L
        repeat(10) { stamp = turnClockStamp(stamp, startsFreshTurn = false, nowMillis = 9_000L + it) }
        assertEquals(5_000L, stamp)
    }

    // ── The fact it rests on ────────────────────────────────────────────────

    @Test fun `the engine never stamps the turn clock itself`() {
        // If this ever stops being true, the carry-forward above becomes the wrong rule and the
        // ViewModel should be reading the engine's value instead. Pinned so that change can't pass
        // unnoticed - it would be silent in every other test.
        val engine = GameEngine(
            GameSettings(gameMode = GameMode.NO_MERCY, maxPlayers = 4, rules = GameRules(stacking = true))
        )
        val players = (0 until 4).map { Player(id = "p$it", name = "P$it", isHost = it == 0) }
        assertEquals(0L, engine.initializeGame(players).turnStartedAtMillis)

        // And it stays 0 through the moves that provoked the report.
        val me = engine.getState().currentPlayer!!.id
        assertEquals(0L, engine.callUno(me).turnStartedAtMillis)
        assertEquals(0L, engine.penalizeUnoNotCalled(me, "p1").turnStartedAtMillis)
        assertEquals(0L, engine.drawCardForPlayer(me).turnStartedAtMillis)
    }
}
