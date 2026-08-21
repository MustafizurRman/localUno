package com.mutsho.localuno.viewmodel

import com.mutsho.localuno.model.Card
import com.mutsho.localuno.model.CardColor
import com.mutsho.localuno.model.CardType
import com.mutsho.localuno.model.GameState
import com.mutsho.localuno.model.NetworkMessage
import com.mutsho.localuno.model.Player

/**
 * The two halves of host -> client state replication, as plain functions.
 *
 * The host holds the only real GameState; every other device rebuilds an approximation of it from
 * a [NetworkMessage.StateUpdate] addressed to them. That makes this pair the single most
 * consequential piece of the multiplayer code, and the least observable: a field the host forgets
 * to send, or the client forgets to apply, produces no error anywhere. It shows up as one player
 * seeing a different board from everyone else - a turn indicator on the wrong seat, a stale colour,
 * a hand count that stopped moving - and it is indistinguishable from lag until someone compares
 * two phones side by side.
 *
 * Pulled out of GameViewModel purely so the round trip can be tested. Both were inline in a message
 * handler, tangled with the emissions and side effects that follow them, and so reachable only by
 * standing up a ViewModel with a live server. Here they are ordinary functions over ordinary data,
 * and StateSyncTest can drive a host state through both and assert what actually survives.
 *
 * Neither reads the clock; the caller passes [nowMillis]. Turn timing is the one value that MUST be
 * translated rather than copied - see [applyStateUpdate].
 */

/** What the host tells [player] about [state]. One message per recipient: hands differ. */
internal fun buildStateUpdate(
    state: GameState,
    player: Player,
    nowMillis: Long
): NetworkMessage.StateUpdate? {
    val topCard = state.topCard ?: return null
    val others = state.players.filter { it.id != player.id }
    return NetworkMessage.StateUpdate(
        currentPlayerIndex = state.currentPlayerIndex,
        direction = state.direction,
        topCard = topCard,
        currentColor = state.currentColor,
        playerHand = player.hand,
        opponentCardCounts = others.associate { it.id to it.cardCount },
        pendingDrawCount = state.pendingDrawCount,
        lastDrawCardValue = state.lastDrawCardValue,
        phase = state.phase,
        drawPileCount = state.drawPile.size,
        isFlipDarkSide = state.isFlipDarkSide,
        myHasCalledUno = player.hasCalledUno,
        myIsConnected = player.isConnected,
        opponentHasCalledUno = others.associate { it.id to it.hasCalledUno },
        opponentConnected = others.associate { it.id to it.isConnected },
        turnStartedAtMillis = state.turnStartedAtMillis,
        // How long the turn has ALREADY run, by the host's clock. The absolute timestamp above is
        // sent too but must not be interpreted directly by the receiver: two phones' wall clocks
        // routinely differ by seconds, which would leave one player's countdown wrong or frozen.
        turnElapsedMillis = if (state.turnStartedAtMillis > 0L) {
            nowMillis - state.turnStartedAtMillis
        } else 0L,
        currentPlayerId = state.currentPlayer?.id ?: "",
        winnerId = state.winnerId,
        disconnectedPlayerId = state.disconnectedPlayerId,
        disconnectCountdown = state.disconnectCountdown,
        pendingSwapPlayerId = state.pendingSwapPlayerId,
        handTransfer = state.handTransfer,
        roundStartedAtMillis = state.roundStartTime,
        sequenceNumber = state.sequenceNumber
    )
}

/**
 * A client's new GameState, folding [message] onto what it already had.
 *
 * Opponent hands and the draw pile are filled with placeholder cards - the host never sends what
 * anyone else is holding, so only their COUNTS are real. Those placeholders all carry `id = -1`,
 * which is safe precisely because nothing renders them in a keyed list; see
 * `ui.components.occurrenceIndices` for why that matters.
 */
internal fun applyStateUpdate(
    previous: GameState,
    message: NetworkMessage.StateUpdate,
    localPlayerId: String,
    nowMillis: Long
): GameState {
    // Drop an update older than the board already on screen.
    //
    // Within one TCP connection order is guaranteed, so this only matters where it is genuinely
    // possible: across a reconnect, where the host replies to a resync while updates from the old
    // socket are still being drained. Applying the older one rolls the board backwards - a card
    // un-plays itself, the turn hops to the previous seat - and then the next update snaps it
    // forward again.
    //
    // The comparison is scoped to the round, and that scoping is the whole difficulty. A rematch
    // constructs a FRESH GameEngine, so sequence numbers restart from 1 while the client is still
    // holding the finished round's much higher number. A plain "lower means stale" rule would
    // therefore reject every update of every rematch, and the client would sit frozen on the
    // previous round's final board with no error anywhere - a far worse failure than the one being
    // fixed. So staleness is only judged between two updates known to belong to the same round.
    //
    // A host too old to send the epoch leaves it 0; the check is skipped and behaviour is exactly
    // as before. Fail open.
    val sameRound = message.roundStartedAtMillis != 0L &&
        message.roundStartedAtMillis == previous.roundStartTime
    if (sameRound && message.sequenceNumber < previous.sequenceNumber) return previous

    fun hidden(count: Int) = MutableList(count) {
        Card(id = -1, color = CardColor.WILD, type = CardType.WILD)
    }

    val updatedPlayers = if (previous.players.isNotEmpty()) {
        previous.players.map { player ->
            if (player.id == localPlayerId) {
                player.copy(
                    hand = message.playerHand.toMutableList(),
                    hasCalledUno = message.myHasCalledUno,
                    isConnected = message.myIsConnected
                )
            } else {
                player.copy(
                    hand = hidden(message.opponentCardCounts[player.id] ?: player.cardCount),
                    hasCalledUno = message.opponentHasCalledUno[player.id] ?: player.hasCalledUno,
                    isConnected = message.opponentConnected[player.id] ?: player.isConnected
                )
            }
        }
    } else {
        // First update of a round, before this device has a roster of its own.
        val localPlayer = Player(
            id = localPlayerId,
            name = "",
            hand = message.playerHand.toMutableList()
        )
        val opponents = message.opponentCardCounts.map { (id, count) ->
            Player(
                id = id,
                name = "",
                hand = hidden(count),
                isConnected = message.opponentConnected[id] ?: true
            )
        }
        listOf(localPlayer) + opponents
    }

    return previous.copy(
        phase = message.phase,
        currentPlayerIndex = resolveTurnIndex(
            players = updatedPlayers,
            currentPlayerId = message.currentPlayerId,
            hostIndex = message.currentPlayerIndex,
            previousIndex = previous.currentPlayerIndex
        ),
        direction = message.direction,
        discardPile = listOf(message.topCard),
        currentColor = message.currentColor,
        pendingDrawCount = message.pendingDrawCount,
        lastDrawCardValue = message.lastDrawCardValue,
        isFlipDarkSide = message.isFlipDarkSide,
        players = updatedPlayers,
        drawPile = hidden(message.drawPileCount),
        // Rebased onto THIS device's clock, so the countdown is right however far the two phones'
        // wall clocks differ. Copying turnStartedAtMillis straight across is the bug this avoids.
        turnStartedAtMillis = if (message.turnStartedAtMillis > 0L) {
            nowMillis - message.turnElapsedMillis
        } else 0L,
        winnerId = message.winnerId,
        disconnectedPlayerId = message.disconnectedPlayerId,
        disconnectCountdown = message.disconnectCountdown,
        pendingSwapPlayerId = message.pendingSwapPlayerId,
        handTransfer = message.handTransfer,
        // Carried so the NEXT update can be judged against the same round. Without storing it the
        // epoch comparison above would never match and the staleness check would never engage.
        roundStartTime = if (message.roundStartedAtMillis != 0L) {
            message.roundStartedAtMillis
        } else previous.roundStartTime,
        sequenceNumber = message.sequenceNumber
    )
}

/**
 * Decide what turn-clock stamp a freshly-computed host state should carry.
 *
 * Separated out because getting it wrong is invisible. `turnStartedAtMillis` is owned by the
 * ViewModel, not the engine: the engine has never heard of the field and its own copy of the state
 * has sat at 0L since the deal. So EVERY state handed back by an engine call carries a meaningless
 * stamp, and the only question is which real one to put on it.
 *
 * Two answers, and previously only one of them was implemented:
 *  - the move started a new turn, so the clock restarts from [nowMillis];
 *  - the move did not, so the turn keeps the deadline it already had - [previousStamp].
 *
 * The second case used to pass the engine's state through untouched, which is not "leave the clock
 * alone" but "set the clock to 0". That is what let declaring UNO stop the clock: a 0 stamp reads
 * as "turn not started", so every countdown froze at full and the armed timeout, finding a stamp
 * that no longer matched its own, concluded the turn had moved on and never fired.
 *
 * @param startsFreshTurn whether the caller is restarting the clock AND the new phase is one that
 *   runs a clock at all. A phase with no clock (round over, paused for a disconnect) keeps the old
 *   stamp too - it is not read while it is not running, and it is the honest value if play resumes.
 */
internal fun turnClockStamp(
    previousStamp: Long,
    startsFreshTurn: Boolean,
    nowMillis: Long
): Long = if (startsFreshTurn) nowMillis else previousStamp
