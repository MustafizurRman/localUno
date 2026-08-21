package com.mutsho.localuno

import com.mutsho.localuno.engine.GameEngine
import com.mutsho.localuno.engine.SeedJournal
import com.mutsho.localuno.model.CardColor
import com.mutsho.localuno.model.GameMode
import com.mutsho.localuno.model.GameRules
import com.mutsho.localuno.model.GameSettings
import com.mutsho.localuno.model.Player
import com.mutsho.localuno.model.TimeoutAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * A recorded round has to come back identical, or the recording is worse than useless - it sends
 * whoever reads it after a bug that was never there.
 *
 * The claim being tested is narrow and total: play a round, take the log, replay the log, and every
 * hand, the pile, the colour, the turn and the phase must match card for card. Anything the engine
 * does that the journal doesn't capture shows up here as a divergence.
 */
class SeedJournalTest {

    private fun table(n: Int) = (0 until n).map {
        Player(id = "p$it", name = "P$it", isHost = it == 0, isBot = it != 0)
    }

    private fun settings(mode: GameMode = GameMode.NO_MERCY) = GameSettings(
        gameMode = mode,
        maxPlayers = 4,
        rules = GameRules(stacking = true, zeroRotates = true, sevenSwaps = true),
        turnTimeoutSeconds = 15,
        turnTimeoutAction = TimeoutAction.DRAW_ONE
    )

    /**
     * Play a table of bots for [moves] turns, taking whatever the current player can legally do.
     * Deliberately dumb: the point is to exercise the engine, not to play well.
     */
    private fun playOut(engine: GameEngine, moves: Int) {
        repeat(moves) {
            val s = engine.getState()
            val me = s.currentPlayer ?: return
            val playable = engine.getPlayableCards(me.id)
            if (playable.isEmpty()) {
                engine.drawCardForPlayer(me.id)
            } else {
                val card = playable.first()
                val colour = if (card.color == CardColor.WILD) CardColor.BLUE else null
                engine.playCard(me.id, card, colour)
            }
            // Both of these are no-ops most turns, which is exactly why they belong here: the
            // journal has to record the calls that changed nothing as faithfully as the ones that
            // did, or the replay's sequence numbers drift away from the original's.
            engine.callUno(me.id)
            engine.getState().pendingSwapPlayerId?.let { engine.autoResolveSwap() }
        }
    }

    private fun fingerprint(e: GameEngine): String {
        val s = e.getState()
        return buildString {
            appendLine("phase=${s.phase} colour=${s.currentColor} dir=${s.direction}")
            appendLine("turn=${s.currentPlayerIndex} pending=${s.pendingDrawCount} seq=${s.sequenceNumber}")
            appendLine("draw=${s.drawPile.map { it.id }}")
            appendLine("discard=${s.discardPile.map { it.id }}")
            s.players.forEach {
                appendLine("${it.id} ${it.isConnected} ${it.hasCalledUno} ${it.hand.map { c -> c.id }}")
            }
        }
    }

    private fun recorded(mode: GameMode, seed: Long, moves: Int): Pair<GameEngine, String> {
        val journal = SeedJournal(seed)
        val engine = GameEngine(settings(mode), Random(seed), journal)
        engine.initializeGame(table(4))
        playOut(engine, moves)
        return engine to journal.dump()
    }

    @Test fun `a recorded round replays card for card`() {
        val (original, log) = recorded(GameMode.NO_MERCY, seed = 20260821L, moves = 120)
        assertEquals(fingerprint(original), fingerprint(SeedJournal.replay(log)))
    }

    @Test fun `a classic round replays card for card`() {
        val (original, log) = recorded(GameMode.CLASSIC, seed = 777L, moves = 120)
        assertEquals(fingerprint(original), fingerprint(SeedJournal.replay(log)))
    }

    @Test fun `many different seeds all replay`() {
        // One seed passing could be luck about which cards came up. Twenty is not.
        repeat(20) { i ->
            val (original, log) = recorded(GameMode.NO_MERCY, seed = 1000L + i, moves = 60)
            assertEquals("seed ${1000L + i}", fingerprint(original), fingerprint(SeedJournal.replay(log)))
        }
    }

    @Test fun `replaying part of a log stops where told`() {
        // This is what makes a log bisectable: half the moves must give the state after half.
        val journal = SeedJournal(4242L)
        val engine = GameEngine(settings(), Random(4242L), journal)
        engine.initializeGame(table(4))
        playOut(engine, 30)
        val half = journal.moves.size / 2
        val partial = fingerprint(SeedJournal.replay(journal.dump(), upTo = half))

        // The same prefix, replayed twice, is the same position.
        assertEquals(partial, fingerprint(SeedJournal.replay(journal.dump(), upTo = half)))
        // And it is genuinely earlier than the whole thing.
        assertTrue(partial != fingerprint(SeedJournal.replay(journal.dump())))
    }

    @Test fun `the header survives a round trip`() {
        val journal = SeedJournal(99L)
        val s = settings()
        GameEngine(s, Random(99L), journal).initializeGame(table(4))
        val rec = SeedJournal.parse(journal.dump())

        assertEquals(99L, rec.seed)
        assertEquals(s.gameMode, rec.settings.gameMode)
        assertEquals(s.maxPlayers, rec.settings.maxPlayers)
        assertEquals(s.rules, rec.settings.rules)
        assertEquals(s.turnTimeoutSeconds, rec.settings.turnTimeoutSeconds)
        assertEquals(s.turnTimeoutAction, rec.settings.turnTimeoutAction)
        assertEquals(s.mercyThreshold, rec.settings.mercyThreshold)
        assertEquals(listOf("p0", "p1", "p2", "p3"), rec.players.map { it.id })
        assertEquals(listOf(false, true, true, true), rec.players.map { it.isBot })
    }

    @Test fun `a turned-off clock survives a round trip`() {
        // "off" is written as a word, not a number, and parsing it back as 0 would silently arm a
        // clock that was never on.
        val journal = SeedJournal(5L)
        GameEngine(settings().copy(turnTimeoutSeconds = null), Random(5L), journal)
            .initializeGame(table(2))
        assertEquals(null, SeedJournal.parse(journal.dump()).settings.turnTimeoutSeconds)
    }

    @Test fun `a name with spaces survives a round trip`() {
        val journal = SeedJournal(6L)
        GameEngine(settings(), Random(6L), journal).initializeGame(
            listOf(Player(id = "p0", name = "Sheikh Mustafizur", isHost = true), Player("p1", "Bot"))
        )
        assertEquals("Sheikh Mustafizur", SeedJournal.parse(journal.dump()).players[0].name)
    }

    @Test fun `an engine given no journal records nothing and still plays`() {
        // The release path. It has to behave identically to a recorded one.
        val plain = GameEngine(settings(), Random(31337L))
        plain.initializeGame(table(4))
        playOut(plain, 60)

        val (recordedEngine, _) = recorded(GameMode.NO_MERCY, seed = 31337L, moves = 60)
        assertEquals(fingerprint(recordedEngine), fingerprint(plain))
    }

    @Test fun `timeouts and departures replay too`() {
        // The paths a soak never reaches and a real evening always does.
        val journal = SeedJournal(8L)
        val engine = GameEngine(settings(), Random(8L), journal)
        engine.initializeGame(table(4))
        playOut(engine, 20)
        engine.getState().currentPlayer?.let { engine.applyTurnTimeout(it.id) }
        engine.beginDisconnectCountdown("p3")
        engine.tickDisconnectCountdown()
        engine.reconnectPlayer("p3")
        playOut(engine, 20)
        engine.playerLeft("p2")
        playOut(engine, 20)

        assertEquals(fingerprint(engine), fingerprint(SeedJournal.replay(journal.dump())))
    }

    @Test fun `the log is capped`() {
        val journal = SeedJournal(1L)
        val engine = GameEngine(settings(), Random(1L), journal)
        engine.initializeGame(table(4))
        repeat(SeedJournal.MOVE_LIMIT + 500) { journal.record("uno", "p0") }
        assertEquals(SeedJournal.MOVE_LIMIT, journal.moves.size)
    }

    @Test fun `a dump is plain enough to paste`() {
        val (_, log) = recorded(GameMode.NO_MERCY, seed = 2L, moves = 10)
        assertTrue(log.startsWith(SeedJournal.BANNER))
        assertTrue(log.lines().any { it == "--" })
        assertTrue("nothing but printable ASCII, so it survives a chat message",
            log.all { it == '\n' || it.code in 32..126 })
    }
}
