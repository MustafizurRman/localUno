package com.mutsho.localuno.model

enum class GamePhase {
    WAITING_IN_LOBBY,
    DEALING,
    PLAYING,
    CHOOSING_COLOR,
    CHALLENGING_DRAW_FOUR,
    // A 7 was played with the seven-swaps rule on and its owner is choosing whose hand to take.
    // The round is held here - nobody else may act - until the choice lands or the turn clock
    // picks for them.
    CHOOSING_SWAP,
    ROUND_OVER,
    GAME_OVER,
    PAUSED_DISCONNECT
}

enum class Direction {
    CLOCKWISE, COUNTER_CLOCKWISE;

    fun reversed(): Direction = when (this) {
        CLOCKWISE -> COUNTER_CLOCKWISE
        COUNTER_CLOCKWISE -> CLOCKWISE
    }
}

/**
 * House rules, per HANDOFF_MENUS.md's "Host a Table" §2.3 - independently toggleable regardless
 * of game mode, replacing what used to be a single stackingEnabled flag hard-tied to
 * gameMode == NO_MERCY (zero-rotate and seven-swap weren't toggleable at all; they were flatly
 * "on in No Mercy, off otherwise").
 */
data class GameRules(
    val stacking: Boolean = false,
    val lastCardMustBeNumber: Boolean = false,
    val zeroRotates: Boolean = false,
    val sevenSwaps: Boolean = false
)

data class GameSettings(
    val gameMode: GameMode = GameMode.CLASSIC,
    val maxPlayers: Int = 6,
    val lobbyName: String = "",
    val pin: String? = null,
    val rules: GameRules = GameRules(),
    // null = no timer ("Off" in the turn-clock picker). Previously hardcoded to
    // GameEngine.TURN_TIMEOUT_SECONDS with no way to change it per lobby.
    val turnTimeoutSeconds: Int? = 15,
    // No Mercy's knockout line: a player holding this many cards (or more) is knocked out of the
    // round. Only meaningful in NO_MERCY - Classic and Flip never read it. 25 mirrors the physical
    // game's rule and was previously a hardcoded constant on GameEngine with no way to change it
    // per table; kept here in `model` rather than referencing that constant directly so this file
    // doesn't have to depend on `engine`.
    val mercyThreshold: Int = 25
)

/**
 * A hand-to-hand transfer that just happened, so every device can animate the same thing.
 *
 * Both moves that move whole hands - a 7's swap and a 0's rotation - previously left no trace in
 * the state at all: hands simply changed size between one broadcast and the next. A client could
 * see the counts move but had no way to know WHO gave their hand to WHOM, so the animation could
 * only ever be a generic "something got swapped" flourish. Recording the participants makes the
 * transfer describable, and describable identically on every screen.
 *
 * [order] is always a cycle: order[k] gives their hand to order[k+1], wrapping at the end. A swap
 * is just the two-element case of that, which is why one field covers both.
 *
 * [id] is the sequenceNumber of the state that performed the transfer. The UI fires on a CHANGE of
 * this value, so the field never needs clearing - a stale descriptor with an id the UI has already
 * played is simply ignored.
 */
data class HandTransfer(
    val id: Long,
    val isRotation: Boolean,
    val order: List<String>
)

data class GameState(
    val phase: GamePhase = GamePhase.WAITING_IN_LOBBY,
    val players: List<Player> = emptyList(),
    val currentPlayerIndex: Int = 0,
    val direction: Direction = Direction.CLOCKWISE,
    val discardPile: List<Card> = emptyList(),
    val drawPile: List<Card> = emptyList(),
    val currentColor: CardColor = CardColor.RED,
    val settings: GameSettings = GameSettings(),
    val pendingDrawCount: Int = 0,
    val lastDrawCardValue: Int = 0,
    val sequenceNumber: Long = 0,
    val winnerId: String? = null,
    val isFlipDarkSide: Boolean = false,
    val disconnectedPlayerId: String? = null,
    // Whose 7 is waiting on a swap target, while phase == CHOOSING_SWAP.
    val pendingSwapPlayerId: String? = null,
    /** The most recent whole-hand transfer, for the board animation. See [HandTransfer]. */
    val handTransfer: HandTransfer? = null,
    val disconnectCountdown: Int = 30,
    val roundStartTime: Long = 0L,
    val turnStartedAtMillis: Long = 0L
) {
    val topCard: Card? get() = discardPile.lastOrNull()
    val currentPlayer: Player? get() = players.getOrNull(currentPlayerIndex)

    fun getNextPlayerIndex(): Int {
        val step = if (direction == Direction.CLOCKWISE) 1 else -1
        var next = currentPlayerIndex + step
        if (next >= players.size) next = 0
        if (next < 0) next = players.size - 1
        var attempts = players.size
        while (!players[next].isConnected && attempts > 0) {
            next += step
            if (next >= players.size) next = 0
            if (next < 0) next = players.size - 1
            attempts--
        }
        return next
    }

    fun getPlayerById(id: String): Player? = players.find { it.id == id }

    /**
     * Whether [playerId] is out of THIS round but the round is still being played around them -
     * i.e. they were knocked out (Mercy threshold, or a disconnect that ran past its grace window)
     * and are now watching rather than participating.
     *
     * Lives here rather than in a screen because it's purely a question about state, and both the
     * board scaffold and the overlays above it need the same answer - deriving it twice is how the
     * two drift apart. Note this reads isConnected in its engine-wide sense of "still in the round",
     * NOT "has a live socket": see Player.isConnected.
     */
    fun isSpectating(playerId: String): Boolean {
        val player = getPlayerById(playerId) ?: return false
        return !player.isConnected &&
            phase != GamePhase.ROUND_OVER &&
            phase != GamePhase.GAME_OVER
    }
}
