package com.mutsho.localuno

import com.mutsho.localuno.engine.GameEngine
import com.mutsho.localuno.model.CardColor
import com.mutsho.localuno.model.GameMode
import com.mutsho.localuno.model.GamePhase
import com.mutsho.localuno.model.GameSettings
import com.mutsho.localuno.model.GameState
import com.mutsho.localuno.model.Player
import com.mutsho.localuno.network.MessageSerializer
import com.mutsho.localuno.viewmodel.applyStateUpdate
import com.mutsho.localuno.viewmodel.buildStateUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Host -> client state replication, driven by real games.
 *
 * The host owns the only true GameState; every other phone reconstructs one from a message. A field
 * dropped on either side produces no error at all - just one player looking at a different board
 * from everyone else, which reads as lag until two phones are held side by side. That is the class
 * of bug this covers.
 *
 * Every update is pushed through [MessageSerializer] on the way, so what is asserted is what
 * genuinely survives the wire rather than what survives an in-process object copy.
 */
class StateSyncTest {

    private val HOST_CLOCK = 1_700_000_000_000L
    /** A client whose wall clock is over a minute off the host's - routine between two phones. */
    private val CLIENT_CLOCK = HOST_CLOCK + 73_500L

    private fun players(n: Int) = (0 until n).map {
        Player(id = "p$it", name = "P$it", avatarColor = it)
    }

    /** Ship one update from host to [viewer], through the serializer, and fold it in. */
    private fun replicate(host: GameState, viewer: Player, clientPrevious: GameState): GameState {
        val update = buildStateUpdate(host, viewer, HOST_CLOCK)
        assertNotNull("host produced no update for ${viewer.id}", update)
        val wire = MessageSerializer.serialize(update!!)
        val received = MessageSerializer.deserialize(wire)
        assertNotNull("update for ${viewer.id} did not survive the wire", received)
        return applyStateUpdate(
            previous = clientPrevious,
            message = received as com.mutsho.localuno.model.NetworkMessage.StateUpdate,
            localPlayerId = viewer.id,
            nowMillis = CLIENT_CLOCK
        )
    }

    private fun assertAgrees(host: GameState, client: GameState, viewerId: String, where: String) {
        assertEquals("$where: phase", host.phase, client.phase)
        assertEquals("$where: direction", host.direction, client.direction)
        assertEquals("$where: colour", host.currentColor, client.currentColor)
        assertEquals("$where: top card", host.topCard, client.topCard)
        assertEquals("$where: pending draw", host.pendingDrawCount, client.pendingDrawCount)
        assertEquals("$where: last draw value", host.lastDrawCardValue, client.lastDrawCardValue)
        assertEquals("$where: winner", host.winnerId, client.winnerId)
        assertEquals("$where: disconnected player", host.disconnectedPlayerId, client.disconnectedPlayerId)
        assertEquals("$where: disconnect countdown", host.disconnectCountdown, client.disconnectCountdown)
        assertEquals("$where: pending swap", host.pendingSwapPlayerId, client.pendingSwapPlayerId)
        assertEquals("$where: hand transfer", host.handTransfer, client.handTransfer)
        assertEquals("$where: sequence", host.sequenceNumber, client.sequenceNumber)
        assertEquals("$where: draw pile size", host.drawPile.size, client.drawPile.size)

        // Whose turn it is, by IDENTITY - the index is meaningless across devices.
        assertEquals(
            "$where: whose turn",
            host.currentPlayer?.id,
            client.currentPlayer?.id
        )

        // This player's own hand is the one hand the host actually sends.
        val hostHand = host.getPlayerById(viewerId)!!.hand
        val clientHand = client.getPlayerById(viewerId)!!.hand
        assertEquals("$where: own hand", hostHand.toList(), clientHand.toList())

        // Everyone else: counts and flags, never contents.
        for (other in host.players.filter { it.id != viewerId }) {
            val mirrored = client.getPlayerById(other.id)
            assertNotNull("$where: ${other.id} missing from client roster", mirrored)
            assertEquals("$where: ${other.id} card count", other.cardCount, mirrored!!.cardCount)
            assertEquals("$where: ${other.id} connected", other.isConnected, mirrored.isConnected)
            assertEquals("$where: ${other.id} uno flag", other.hasCalledUno, mirrored.hasCalledUno)
        }
    }

    @Test
    fun `every seat sees the same board through a whole game`() {
        val rng = Random(4242)
        for (playerCount in 2..6) {
            // Seeded deal as well as seeded moves, so a failure here replays exactly.
            val engine = GameEngine(
                GameSettings(gameMode = GameMode.NO_MERCY, maxPlayers = playerCount, rules = HOUSE_RULES_ON),
                Random(9000 + playerCount)
            )
            var host = engine.initializeGame(players(playerCount))
            // Each client starts blank, exactly as a phone that has just joined does.
            val clients = HashMap<String, GameState>()
            host.players.forEach { clients[it.id] = GameState() }

            repeat(160) { move ->
                if (host.phase == GamePhase.GAME_OVER || host.phase == GamePhase.ROUND_OVER) return@repeat

                host.players.forEach { viewer ->
                    val next = replicate(host, viewer, clients[viewer.id]!!)
                    clients[viewer.id] = next
                    assertAgrees(host, next, viewer.id, "n=$playerCount move=$move seat=${viewer.id}")
                }

                val current = host.players.getOrNull(host.currentPlayerIndex) ?: return@repeat
                host = when {
                    host.phase == GamePhase.CHOOSING_SWAP -> {
                        val target = host.players.firstOrNull { it.id != current.id && it.isConnected }
                            ?: return@repeat
                        engine.chooseSwapTarget(current.id, target.id)
                    }
                    else -> {
                        val playable = engine.getPlayableCards(current.id)
                        if (playable.isNotEmpty() && rng.nextInt(10) > 1) {
                            val card = playable[rng.nextInt(playable.size)]
                            val colour = if (card.color == CardColor.WILD) {
                                listOf(CardColor.RED, CardColor.YELLOW, CardColor.GREEN, CardColor.BLUE)[rng.nextInt(4)]
                            } else null
                            engine.playCard(current.id, card, colour)
                        } else {
                            engine.drawCardForPlayer(current.id)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `the turn clock survives a badly skewed client clock`() {
        // The absolute timestamp is meaningless on another device; only the ELAPSED time is real.
        val engine = GameEngine(GameSettings(gameMode = GameMode.CLASSIC, maxPlayers = 3))
        val host = engine.initializeGame(players(3))
            .copy(turnStartedAtMillis = HOST_CLOCK - 5_000L)

        for (skew in listOf(0L, 73_500L, -600_000L, 86_400_000L)) {
            val update = buildStateUpdate(host, host.players[1], HOST_CLOCK)!!
            val client = applyStateUpdate(
                previous = GameState(),
                message = update,
                localPlayerId = "p1",
                nowMillis = HOST_CLOCK + skew
            )
            // Five seconds elapsed on the host must read as five seconds on the client, whatever
            // its wall clock says.
            val clientElapsed = (HOST_CLOCK + skew) - client.turnStartedAtMillis
            assertEquals("skew=$skew produced the wrong elapsed time", 5_000L, clientElapsed)
        }
    }

    @Test
    fun `a turn that has not started reports no elapsed time`() {
        val engine = GameEngine(GameSettings(gameMode = GameMode.CLASSIC, maxPlayers = 2))
        val host = engine.initializeGame(players(2)).copy(turnStartedAtMillis = 0L)
        val update = buildStateUpdate(host, host.players[0], HOST_CLOCK)!!
        val client = applyStateUpdate(GameState(), update, "p0", CLIENT_CLOCK)
        assertEquals(0L, client.turnStartedAtMillis)
    }

    @Test
    fun `no client is ever told another player's cards`() {
        // The single privacy guarantee of the protocol: hands are not broadcast.
        val engine = GameEngine(
            GameSettings(gameMode = GameMode.NO_MERCY, maxPlayers = 4, rules = HOUSE_RULES_ON)
        )
        val host = engine.initializeGame(players(4))

        for (viewer in host.players) {
            val update = buildStateUpdate(host, viewer, HOST_CLOCK)!!
            val wire = MessageSerializer.serialize(update)
            val othersCards = host.players
                .filter { it.id != viewer.id }
                .flatMap { it.hand }
                .map { it.id }
                .toSet()
            val mine = viewer.hand.map { it.id }.toSet()
            // Ids unique to somebody else must not appear anywhere in the payload.
            for (id in othersCards - mine) {
                assertTrue(
                    "seat ${viewer.id} was sent card id $id belonging to another player",
                    !wire.contains("\\\"id\\\":$id,")
                )
            }
        }
    }

    @Test
    fun `a client joining mid-round builds a full roster from one update`() {
        // A reconnecting phone has no prior state at all; one message has to be enough.
        val engine = GameEngine(
            GameSettings(gameMode = GameMode.NO_MERCY, maxPlayers = 5, rules = HOUSE_RULES_ON)
        )
        var host = engine.initializeGame(players(5))
        repeat(12) {
            val current = host.players[host.currentPlayerIndex]
            val playable = engine.getPlayableCards(current.id)
            host = if (playable.isNotEmpty()) {
                engine.playCard(current.id, playable.first(), CardColor.RED)
            } else engine.drawCardForPlayer(current.id)
        }

        val viewer = host.players[3]
        val client = replicate(host, viewer, GameState())
        assertEquals("roster size", host.players.size, client.players.size)
        assertAgrees(host, client, viewer.id, "cold join")
    }

    @Test
    fun `a stale update is dropped rather than applied`() {
        val engine = GameEngine(GameSettings(gameMode = GameMode.CLASSIC, maxPlayers = 3))
        var host = engine.initializeGame(players(3))
        val viewer = host.players[0]

        val old = buildStateUpdate(host, viewer, HOST_CLOCK)!!
        val current = host.players[host.currentPlayerIndex]
        host = engine.getPlayableCards(current.id).firstOrNull()
            ?.let { engine.playCard(current.id, it, CardColor.RED) }
            ?: engine.drawCardForPlayer(current.id)
        val fresh = buildStateUpdate(host, viewer, HOST_CLOCK)!!

        val afterFresh = applyStateUpdate(GameState(), fresh, viewer.id, CLIENT_CLOCK)
        val afterStale = applyStateUpdate(afterFresh, old, viewer.id, CLIENT_CLOCK)

        assertEquals(
            "a lower sequence number from the same round must be ignored entirely",
            afterFresh, afterStale
        )
        assertEquals(
            "the board must not roll backwards",
            afterFresh.sequenceNumber, afterStale.sequenceNumber
        )
        assertEquals("nor the top card", afterFresh.topCard, afterStale.topCard)
    }

    @Test
    fun `a duplicate of the current update is harmless`() {
        val engine = GameEngine(GameSettings(gameMode = GameMode.CLASSIC, maxPlayers = 3))
        val host = engine.initializeGame(players(3))
        val viewer = host.players[0]
        val update = buildStateUpdate(host, viewer, HOST_CLOCK)!!

        val once = applyStateUpdate(GameState(), update, viewer.id, CLIENT_CLOCK)
        val twice = applyStateUpdate(once, update, viewer.id, CLIENT_CLOCK)
        assertEquals("re-delivery of the same update must be idempotent", once, twice)
    }

    @Test
    fun `a rematch is accepted even though its sequence numbers restart`() {
        // The trap the staleness guard has to avoid. A rematch builds a FRESH GameEngine, so the
        // new round's first update carries sequence 1 while the client is still holding the
        // finished round's much higher number. Reject that and the client freezes on the old board
        // for the rest of the evening.
        val settings = GameSettings(gameMode = GameMode.NO_MERCY, maxPlayers = 3, rules = HOUSE_RULES_ON)

        val roundOne = GameEngine(settings)
        var host = roundOne.initializeGame(players(3))
        val viewer = host.players[0]
        var client = GameState()
        repeat(25) {
            client = replicate(host, viewer, client)
            val current = host.players[host.currentPlayerIndex]
            // CHOOSING_SWAP has to be answered, not played through. Without this the loop stalls
            // the moment a 7 lands: nothing is playable during a pending swap and draws are
            // refused, so the round would sit still and the sequence stop climbing - which made
            // this test pass or fail on the shuffle.
            host = if (host.phase == GamePhase.CHOOSING_SWAP) {
                val target = host.players.first { it.id != current.id && it.isConnected }
                roundOne.chooseSwapTarget(current.id, target.id)
            } else {
                roundOne.getPlayableCards(current.id).firstOrNull()
                    ?.let { roundOne.playCard(current.id, it, CardColor.RED) }
                    ?: roundOne.drawCardForPlayer(current.id)
            }
        }
        val endOfRoundOne = client.sequenceNumber
        assertTrue("round one should have run up a real sequence", endOfRoundOne > 5L)

        // Second round: new engine, sequence restarts, epoch changes.
        val roundTwo = GameEngine(settings)
        val fresh = roundTwo.initializeGame(players(3))
        val firstOfRoundTwo = buildStateUpdate(fresh, fresh.players[0], HOST_CLOCK + 60_000L)!!
        assertTrue(
            "precondition: the new round really does restart lower",
            firstOfRoundTwo.sequenceNumber < endOfRoundOne
        )

        val afterRematch = applyStateUpdate(client, firstOfRoundTwo, viewer.id, CLIENT_CLOCK)
        assertEquals(
            "the first update of a new round must be accepted despite the lower sequence",
            firstOfRoundTwo.sequenceNumber, afterRematch.sequenceNumber
        )
        assertEquals("and must bring the new round's board", firstOfRoundTwo.topCard, afterRematch.topCard)
        assertEquals(
            "and adopt the new round's epoch, so the next update can be judged against it",
            firstOfRoundTwo.roundStartedAtMillis, afterRematch.roundStartTime
        )
    }

    @Test
    fun `a host too old to send the epoch is never blocked`() {
        // Forwards compatibility: an un-updated phone hosting leaves roundStartedAtMillis at 0.
        // The guard must switch off rather than reject everything.
        val engine = GameEngine(GameSettings(gameMode = GameMode.CLASSIC, maxPlayers = 3))
        val host = engine.initializeGame(players(3))
        val viewer = host.players[0]
        val legacy = buildStateUpdate(host, viewer, HOST_CLOCK)!!.copy(
            roundStartedAtMillis = 0L,
            sequenceNumber = 5
        )
        val ahead = applyStateUpdate(GameState(), legacy.copy(sequenceNumber = 40), viewer.id, CLIENT_CLOCK)
        val behind = applyStateUpdate(ahead, legacy, viewer.id, CLIENT_CLOCK)
        assertEquals(
            "with no epoch the check must not engage at all",
            5L, behind.sequenceNumber
        )
    }

    @Test
    fun `an eight player table replicates to every seat`() {
        val engine = GameEngine(
            GameSettings(gameMode = GameMode.NO_MERCY, maxPlayers = 8, rules = HOUSE_RULES_ON)
        )
        var host = engine.initializeGame(players(8))
        val clients = HashMap<String, GameState>()
        host.players.forEach { clients[it.id] = GameState() }

        repeat(60) { move ->
            if (host.phase != GamePhase.PLAYING && host.phase != GamePhase.CHOOSING_SWAP) return@repeat
            host.players.forEach { viewer ->
                val next = replicate(host, viewer, clients[viewer.id]!!)
                clients[viewer.id] = next
                assertAgrees(host, next, viewer.id, "8-seat move=$move seat=${viewer.id}")
            }
            val current = host.players[host.currentPlayerIndex]
            host = if (host.phase == GamePhase.CHOOSING_SWAP) {
                val t = host.players.first { it.id != current.id && it.isConnected }
                engine.chooseSwapTarget(current.id, t.id)
            } else {
                engine.getPlayableCards(current.id).firstOrNull()
                    ?.let { engine.playCard(current.id, it, CardColor.BLUE) }
                    ?: engine.drawCardForPlayer(current.id)
            }
        }
        // One final replication, then every seat must agree exactly. Checking mid-loop would only
        // prove the clients were behind by the last move, which they legitimately are - they are
        // replicated at the top of each iteration, before the host acts.
        host.players.forEach { viewer ->
            val settled = replicate(host, viewer, clients[viewer.id]!!)
            assertEquals(
                "seat ${viewer.id} did not settle on the host's sequence",
                host.sequenceNumber, settled.sequenceNumber
            )
            assertAgrees(host, settled, viewer.id, "8-seat settled seat=${viewer.id}")
        }
    }
}
