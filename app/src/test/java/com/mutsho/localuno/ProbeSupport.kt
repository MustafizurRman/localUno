package com.mutsho.localuno

import com.mutsho.localuno.engine.GameEngine
import com.mutsho.localuno.model.Card
import com.mutsho.localuno.model.CardColor
import com.mutsho.localuno.model.CardType
import com.mutsho.localuno.model.Direction
import com.mutsho.localuno.model.GameMode
import com.mutsho.localuno.model.GamePhase
import com.mutsho.localuno.model.GameRules
import com.mutsho.localuno.model.GameSettings
import com.mutsho.localuno.model.GameState
import com.mutsho.localuno.model.Player
import com.mutsho.localuno.model.TimeoutAction

/**
 * Shared rigging for the behaviour probes.
 *
 * These exist to ASK the engine questions rather than to describe what it already does. A probe
 * that fails is a claim about UNO that the engine disagrees with, and either the claim or the
 * engine is wrong - which one is the interesting part, and not something to decide in advance.
 *
 * Card ids start high on purpose. Seeding a hand with low ids collides with the ids the deal
 * already handed out, and a duplicate id fails the conservation checks for a reason that has
 * nothing to do with the rule under test - that mistake has already cost one debugging session
 * here.
 */
internal object Probe {

    fun settings(
        mode: GameMode = GameMode.CLASSIC,
        stacking: Boolean = false,
        lastCardMustBeNumber: Boolean = false,
        zeroRotates: Boolean = false,
        sevenSwaps: Boolean = false,
        seats: Int = 4,
        clock: Int? = null,
        onTimeout: TimeoutAction = TimeoutAction.SKIP,
        mercyThreshold: Int = 25
    ) = GameSettings(
        gameMode = mode,
        maxPlayers = seats,
        rules = GameRules(
            stacking = stacking,
            lastCardMustBeNumber = lastCardMustBeNumber,
            zeroRotates = zeroRotates,
            sevenSwaps = sevenSwaps
        ),
        turnTimeoutSeconds = clock,
        turnTimeoutAction = onTimeout,
        mercyThreshold = mercyThreshold
    )

    fun seats(n: Int) = (0 until n).map { Player(id = "p$it", name = "P$it", avatarColor = it) }

    /** Ids from 9000 up, well clear of anything the deal produced. */
    fun card(n: Int, color: CardColor, type: CardType) = Card(9000 + n, color, type)

    /**
     * An engine mid-round, with [hands] dealt to the first players and everything else stated
     * outright so a probe never depends on what the shuffle happened to produce.
     */
    fun table(
        hands: List<List<Card>>,
        settings: GameSettings = settings(),
        top: Card = card(500, CardColor.RED, CardType.THREE),
        colour: CardColor = CardColor.RED,
        turn: Int = 0,
        direction: Direction = Direction.CLOCKWISE,
        pendingDraw: Int = 0,
        phase: GamePhase = GamePhase.PLAYING,
        seatCount: Int = hands.size
    ): GameEngine {
        val engine = GameEngine(settings)
        engine.initializeGame(seats(seatCount))
        val players = engine.getState().players.toMutableList()
        hands.forEachIndexed { i, hand ->
            players[i] = players[i].copy(hand = hand.toMutableList())
        }
        engine.setStateForTest(
            engine.getState().copy(
                players = players,
                phase = phase,
                currentPlayerIndex = turn,
                direction = direction,
                currentColor = colour,
                pendingDrawCount = pendingDraw,
                discardPile = listOf(top)
            )
        )
        return engine
    }

    fun hand(engine: GameEngine, id: String): List<Card> =
        engine.getState().getPlayerById(id)?.hand.orEmpty()

    fun count(engine: GameEngine, id: String): Int = hand(engine, id).size

    fun turnId(engine: GameEngine): String? = engine.getState().currentPlayer?.id

    /** Every card the engine believes exists, for conservation checks. */
    fun allCards(state: GameState): List<Card> =
        state.players.flatMap { it.hand } + state.drawPile + state.discardPile

    fun duplicateIds(state: GameState): Set<Int> =
        allCards(state).map { it.id }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
}
