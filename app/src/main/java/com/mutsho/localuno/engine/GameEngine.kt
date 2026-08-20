package com.mutsho.localuno.engine

import com.mutsho.localuno.model.*

class GameEngine(
    private val settings: GameSettings,
    /** Injectable only so a test can replay an exact deal - see DeckFactory.shuffle. */
    private val random: kotlin.random.Random = kotlin.random.Random
) {

    private var state: GameState = GameState(settings = settings)
    private var sequenceCounter: Long = 0

    companion object {
        /** Seconds to wait for a disconnected player before knocking them out. */
        const val DISCONNECT_TIMEOUT_SECONDS = 30

        /** Seconds a player gets to act before the host auto-draws for them. */
        const val TURN_TIMEOUT_SECONDS = 20
    }

    fun getState(): GameState = state

    /**
     * Test-only seam for standing up a specific position - a named card in a named hand, a chosen
     * colour on the table - which initializeGame's random deal can't be asked for directly.
     *
     * `internal` rather than public: the unit-test source set has friend access to this module's
     * internals, so tests can reach it while nothing in the app (or a future module) can. Every
     * production path still goes through the ordinary mutators, so this can't become a back door
     * for real game logic without someone very deliberately making it one.
     */
    internal fun setStateForTest(newState: GameState) {
        state = newState
    }

    fun initializeGame(players: List<Player>): GameState {
        val deck = DeckFactory.createDeckForMode(settings.gameMode)
        DeckFactory.shuffle(deck, random)

        // Deal 7 cards to each player
        val updatedPlayers = players.map { player ->
            val hand = mutableListOf<Card>()
            repeat(7) {
                if (deck.isNotEmpty()) {
                    hand.add(deck.removeFirst())
                }
            }
            player.copy(hand = hand)
        }

        // Find first non-action card for discard pile (per rules: ignore action cards)
        var firstCard: Card? = null
        val drawPile = deck.toMutableList()
        for (i in drawPile.indices) {
            if (drawPile[i].isNumberCard()) {
                firstCard = drawPile.removeAt(i)
                break
            }
        }
        if (firstCard == null && drawPile.isNotEmpty()) {
            firstCard = drawPile.removeFirst()
        }

        state = state.copy(
            phase = GamePhase.PLAYING,
            players = updatedPlayers,
            currentPlayerIndex = 0,
            direction = Direction.CLOCKWISE,
            discardPile = listOfNotNull(firstCard),
            drawPile = drawPile,
            currentColor = firstCard?.color ?: CardColor.RED,
            pendingDrawCount = 0,
            sequenceNumber = nextSequence(),
            roundStartTime = System.currentTimeMillis()
        )
        return state
    }

    fun canPlayCard(card: Card, playerId: String): Boolean {
        if (state.currentPlayer?.id != playerId) return false
        if (state.phase != GamePhase.PLAYING) return false

        // "Last card must be a number" (HANDOFF_MENUS.md house rule): a power/wild/draw card
        // locks - stays untappable - while it's the only card left in your hand. Checked before
        // everything else so it applies uniformly, including a forced draw-stack response; you
        // cannot win on it either way. Once you're back to 2+ cards it plays normally.
        if (settings.rules.lastCardMustBeNumber) {
            val player = state.getPlayerById(playerId)
            if (player != null && !card.isNumberCard() && emptiesHand(card, player)) return false
        }

        // If there's a pending draw stack, only draw cards of equal or higher value can be played
        if (state.pendingDrawCount > 0) {
            return canStackOnPending(card)
        }

        return isCardPlayable(card)
    }

    /**
     * Whether [card] answers the live draw penalty instead of taking it.
     *
     * The card being answered is the top of the discard pile: a pending penalty is only ever
     * created by playing a draw card, and that play pushes it onto the pile in the same step, so
     * the top card IS the penalty.
     *
     * Reading it from the pile rather than from a separate remembered field means there is nothing
     * to keep in sync and nothing to send over the wire - clients already receive the top card, so
     * they can evaluate the identical rule.
     */
    private fun canStackOnPending(card: Card): Boolean {
        // A pending penalty can exist with stacking switched off (a reverse +4 hands one on), and
        // with the rule off nothing may answer it at all - the penalty is simply taken.
        if (!settings.rules.stacking) return false
        val penalty = state.topCard ?: return false
        return Card.canStack(card.type, penalty.type)
    }

    /**
     * Whether playing [card] would leave [player] holding nothing - i.e. whether this is a WINNING
     * play. This is what "last card must be a number" actually needs to ask.
     *
     * A hand can empty two different ways, and the rule used to check only the first:
     *  - the ordinary one, playCard removing the single card you had left (hand.size == 1);
     *  - Discard All, which additionally sweeps every card matching ITS OWN colour out of your hand
     *    in the same move. Holding [DiscardAll(red), red5, red7] was never caught by a hand.size ==
     *    1 test, yet the play emptied the hand and won the round on a power card - precisely what
     *    the rule promises cannot happen.
     *
     * Deliberately phrased as "would this win" rather than "is this card allowed at one card left":
     * the rule locks a winning MOVE, not a card. Discard All stays perfectly playable whenever
     * something survives the sweep, so this never soft-locks a hand - any blocked play can be
     * unblocked by drawing.
     */
    private fun emptiesHand(card: Card, player: Player): Boolean {
        val remaining = player.hand.filterNot { it.id == card.id }
        if (remaining.isEmpty()) return true
        // Discard All also removes every OTHER card sharing its colour.
        if (card.type == CardType.DISCARD_ALL) {
            return remaining.all { it.color == card.color }
        }
        return false
    }

    private fun isCardPlayable(card: Card): Boolean {
        val topCard = state.topCard ?: return true
        return when {
            card.isWildCard() -> true
            card.color == state.currentColor -> true
            card.type == topCard.type -> true
            else -> false
        }
    }

    fun playCard(playerId: String, card: Card, chosenColor: CardColor? = null): GameState {
        // Turn and phase are enforced HERE as well as in the caller.
        //
        // GameViewModel.executePlayCard already gates on canPlayCard before reaching this, so an
        // out-of-turn play could not actually be made - but the guarantee lived entirely in the
        // caller, and this method is public on the class that is supposed to BE the authority.
        // Called directly it would happily let any seat play at any time: verified by probe, a
        // player whose turn it was not moved a card onto the pile and advanced the game.
        //
        // Every message reaching this comes from another phone, so "the caller checks" is one
        // refactor, one new call site, or one reordered guard away from being a table where
        // somebody can play whenever they like. Cheap to enforce twice.
        //
        // Deliberately turn and phase only, NOT full legality: drawUntilPlayable auto-plays the
        // card it just drew through here, having established playability by its own route, and
        // duplicating canPlayCard's rule checks would risk rejecting that.
        if (state.currentPlayer?.id != playerId) return state
        if (state.phase != GamePhase.PLAYING) return state

        val player = state.getPlayerById(playerId) ?: return state
        val playerIndex = state.players.indexOf(player)

        // Verify the card is actually in the player's hand
        if (player.hand.none { it.id == card.id }) return state

        // Remove card from player's hand
        val updatedHand = player.hand.toMutableList()
        updatedHand.removeAll { it.id == card.id }
        val updatedPlayer = player.copy(
            hand = updatedHand,
            hasCalledUno = if (updatedHand.size == 1) player.hasCalledUno else false
        )
        val updatedPlayers = state.players.toMutableList()
        updatedPlayers[playerIndex] = updatedPlayer

        // Add to discard pile
        val updatedDiscard = state.discardPile.toMutableList()
        updatedDiscard.add(card)

        // Determine new color.
        //
        // Only a wild card may set the colour, and only to a REAL colour. The previous form tested
        // `chosenColor != null` before it tested whether the card was wild, so any card carrying a
        // chosenColor overrode the table - a client could play a red 5 with chosenColor = BLUE and
        // simply choose the active colour on every turn. It also never rejected CardColor.WILD
        // itself, and setting currentColor = WILD soft-locks the round: nothing matches it except
        // wilds and same-type cards, so most hands become unplayable until someone happens to draw
        // one. Both are now impossible regardless of what arrives over the wire.
        val newColor = if (card.isWildCard()) {
            chosenColor?.takeIf { it != CardColor.WILD } ?: state.currentColor
        } else {
            card.color
        }

        // Track last draw card value for stacking
        val newLastDrawValue = if (card.isDrawCard()) Card.drawValue(card.type) else state.lastDrawCardValue

        state = state.copy(
            players = updatedPlayers,
            discardPile = updatedDiscard,
            currentColor = newColor,
            lastDrawCardValue = newLastDrawValue,
            sequenceNumber = nextSequence()
        )

        // Check if player won
        if (updatedPlayer.hand.isEmpty()) {
            state = applyWinningCardPenalty(card, playerIndex)
            state = state.copy(
                phase = GamePhase.ROUND_OVER,
                winnerId = playerId,
                // Nobody is left to answer a stack, and the penalty above has already been dealt.
                pendingDrawCount = 0,
                lastDrawCardValue = 0
            )
            return state
        }

        // Apply card effect.
        //
        // Passes the SANITIZED newColor, not the raw chosenColor off the wire. Only currentColor
        // was being sanitized above, which left Wild Color Roulette reading the unchecked value:
        // sending chosenColor = CardColor.WILD made its target colour WILD, and drawUntilColor's
        // stop condition (`card.color == target && !card.isWildCard()`) is a contradiction for that
        // value - no card can ever satisfy it - so it ran to its 40-card safety limit and dumped 40
        // cards on the next player, an instant knockout in No Mercy. Using newColor also keeps the
        // colour the roulette draws toward identical to the colour the felt is now showing, which
        // the raw value did not guarantee even for legitimate input.
        state = applyCardEffect(card, newColor)

        // Check mercy rule (No Mercy mode)
        if (settings.gameMode == GameMode.NO_MERCY) {
            state = checkMercyRule()
        }

        return state
    }

    private fun applyCardEffect(card: Card, chosenColor: CardColor?): GameState {
        return when (card.type) {
            CardType.ZERO -> {
                // 0's Pass: ALL players pass their hand to the next player in current direction
                if (settings.rules.zeroRotates) {
                    applyZeroPass()
                } else {
                    val nextIndex = getNextPlayerFrom(state.currentPlayerIndex)
                    state.copy(currentPlayerIndex = nextIndex, sequenceNumber = nextSequence())
                }
            }

            CardType.SEVEN -> {
                // 7's Swap: the player MUST trade hands, and picks the seat themselves. This used
                // to auto-target whoever held the most cards, so "You pick the seat you trade with"
                // - which the house-rules screen promises - was never actually true. Now the round
                // holds in CHOOSING_SWAP until chooseSwapTarget() lands (or the turn clock picks).
                val hasTarget = state.players.any {
                    it.id != state.players[state.currentPlayerIndex].id && it.isConnected
                }
                if (settings.rules.sevenSwaps && hasTarget) {
                    state.copy(
                        phase = GamePhase.CHOOSING_SWAP,
                        pendingSwapPlayerId = state.players[state.currentPlayerIndex].id,
                        sequenceNumber = nextSequence()
                    )
                } else {
                    val nextIndex = getNextPlayerFrom(state.currentPlayerIndex)
                    state.copy(currentPlayerIndex = nextIndex, sequenceNumber = nextSequence())
                }
            }

            CardType.SKIP -> {
                // Skip the next ACTIVE player
                val skippedIndex = getNextPlayerFrom(state.currentPlayerIndex)
                val afterSkip = getNextPlayerFrom(skippedIndex)
                state.copy(currentPlayerIndex = afterSkip, sequenceNumber = nextSequence())
            }

            CardType.REVERSE -> {
                val newDirection = state.direction.reversed()
                val newState = state.copy(direction = newDirection, sequenceNumber = nextSequence())
                val activePlayers = state.players.count { it.isConnected }
                if (activePlayers == 2) {
                    // With 2 active players, reverse acts as skip ? current player goes again
                    newState
                } else {
                    newState.copy(currentPlayerIndex = getNextPlayerIndexWith(newDirection))
                }
            }

            CardType.DRAW_TWO -> {
                applyDrawCard(2)
            }

            CardType.DRAW_FOUR -> {
                applyDrawCard(4)
            }

            CardType.DISCARD_ALL -> {
                // Discard all cards matching the color of this Discard All card
                applyDiscardAll(card)
            }

            CardType.SKIP_EVERYONE -> {
                // Skip all other players, current player takes another turn
                state.copy(sequenceNumber = nextSequence())
            }

            CardType.WILD -> {
                val nextIndex = getNextPlayerFrom(state.currentPlayerIndex)
                state.copy(currentPlayerIndex = nextIndex, sequenceNumber = nextSequence())
            }

            CardType.WILD_REVERSE_DRAW_FOUR -> {
                // Reverse direction, then next player in NEW direction draws 4 and loses turn
                val newDirection = state.direction.reversed()
                val nextIndex = getNextPlayerFromWith(state.currentPlayerIndex, newDirection)

                // Every branch below builds its result from the LIVE `state`, never from a snapshot
                // taken earlier in this block.
                //
                // There used to be a `tempState = state.copy(direction = newDirection)` captured
                // here and returned from at the end. drawCards() writes the shortened pile back
                // into `state` as a side effect, so returning `tempState.copy(...)` restored the
                // pre-draw drawPile while ALSO handing the drawn cards to a player: four cards
                // existed simultaneously in a hand and in the deck.
                //
                // That is a duplicate card id, and the hand renders each card inside
                // `key(card.id)`. Duplicate keys corrupt Compose's slot table, and the corruption
                // does not surface where it happens - it takes down a later frame from inside the
                // runtime, with no application code on the stack at all. It is the crash reported
                // from a real game: IndexOutOfBoundsException at Stack.pop / ComposerImpl.endRoot.
                //
                // See CardConservationTest, which fuzzes whole games and asserts the deck is a
                // conserved multiset after every move.
                val activePlayers = state.players.count { it.isConnected }
                if (activePlayers == 2) {
                    // With 2 active players: reverse acts as skip, so YOU draw 4 (per rules)
                    val self = state.players[state.currentPlayerIndex]
                    val drawn = drawCards(4)
                    val updatedPlayers = state.players.toMutableList()
                    val updatedHand = self.hand.toMutableList()
                    updatedHand.addAll(drawn)
                    updatedPlayers[state.currentPlayerIndex] = self.copy(hand = updatedHand)
                    state.copy(
                        direction = newDirection,
                        players = updatedPlayers,
                        currentPlayerIndex = nextIndex,
                        pendingDrawCount = 0,
                        lastDrawCardValue = 0,
                        sequenceNumber = nextSequence()
                    )
                } else if (settings.rules.stacking) {
                    state.copy(
                        direction = newDirection,
                        currentPlayerIndex = nextIndex,
                        pendingDrawCount = state.pendingDrawCount + 4,
                        sequenceNumber = nextSequence()
                    )
                } else {
                    // Stacking off: the next player draws now and loses the turn, exactly as every
                    // other draw card behaves via applyDrawCard. This branch used to hand over a
                    // pending penalty regardless of the setting - the one way a stack could open
                    // with stacking switched off.
                    val nextPlayer = state.players[nextIndex]
                    val drawn = drawCards(4)
                    val updatedPlayers = state.players.toMutableList()
                    updatedPlayers[nextIndex] = nextPlayer.copy(hand = nextPlayer.hand + drawn)
                    state.copy(
                        direction = newDirection,
                        players = updatedPlayers,
                        currentPlayerIndex = getNextPlayerFromWith(nextIndex, newDirection),
                        pendingDrawCount = 0,
                        lastDrawCardValue = 0,
                        sequenceNumber = nextSequence()
                    )
                }
            }

            CardType.WILD_DRAW_SIX -> {
                applyDrawCard(6)
            }

            CardType.WILD_DRAW_TEN -> {
                applyDrawCard(10)
            }

            CardType.WILD_COLOR_ROULETTE -> {
                // Current player chose the target color; next active player draws until that color
                // appears. chosenColor is playCard's already-sanitized newColor, so it is always a
                // real colour - but the takeIf keeps this branch honest on its own terms rather
                // than relying on a guarantee made three call frames away.
                val nextIndex = getNextPlayerFrom(state.currentPlayerIndex)
                val nextPlayer = state.players[nextIndex]
                val targetColor = chosenColor?.takeIf { it != CardColor.WILD } ?: CardColor.RED
                val drawnCards = drawUntilColor(targetColor)
                val updatedPlayers = state.players.toMutableList()
                val updatedHand = nextPlayer.hand.toMutableList()
                updatedHand.addAll(drawnCards)
                updatedPlayers[nextIndex] = nextPlayer.copy(hand = updatedHand)
                val afterNext = getNextPlayerFrom(nextIndex)
                state.copy(
                    players = updatedPlayers,
                    currentPlayerIndex = afterNext,
                    pendingDrawCount = 0,
                    lastDrawCardValue = 0,
                    sequenceNumber = nextSequence()
                )
            }

            CardType.FLIP -> {
                val newDarkSide = !state.isFlipDarkSide
                val nextIndex = getNextPlayerFrom(state.currentPlayerIndex)
                state.copy(
                    isFlipDarkSide = newDarkSide,
                    currentPlayerIndex = nextIndex,
                    sequenceNumber = nextSequence()
                )
            }

            else -> {
                // Regular number cards (1-6, 8, 9 in No Mercy, or any unhandled)
                val nextIndex = getNextPlayerFrom(state.currentPlayerIndex)
                state.copy(currentPlayerIndex = nextIndex, sequenceNumber = nextSequence())
            }
        }
    }

    /**
     * Apply a draw card effect. In No Mercy, stacking is always enabled and works by value.
     * The draw penalty accumulates until someone can't stack, then they draw all.
     */
    private fun applyDrawCard(amount: Int): GameState {
        val nextIndex = getNextPlayerFrom(state.currentPlayerIndex)
        val newPending = state.pendingDrawCount + amount

        // A genuine independent house rule, not implicitly forced on for No Mercy. The No Mercy
        // table bundles it on, and a custom table can turn it off - the engine just reads the
        // setting and has no opinion about which deck is in play.
        val stackingActive = settings.rules.stacking

        if (!stackingActive) {
            // Immediate draw - next player draws and loses turn
            val nextPlayer = state.players[nextIndex]
            val drawn = drawCards(amount)
            val updatedPlayers = state.players.toMutableList()
            val updatedHand = nextPlayer.hand.toMutableList()
            updatedHand.addAll(drawn)
            updatedPlayers[nextIndex] = nextPlayer.copy(hand = updatedHand)
            val afterNext = getNextPlayerFrom(nextIndex)
            return state.copy(
                players = updatedPlayers,
                currentPlayerIndex = afterNext,
                pendingDrawCount = 0,
                lastDrawCardValue = 0,
                sequenceNumber = nextSequence()
            )
        }

        // Stacking: pass to next player with accumulated penalty
        return state.copy(
            currentPlayerIndex = getNextPlayerFrom(state.currentPlayerIndex),
            pendingDrawCount = newPending,
            sequenceNumber = nextSequence()
        )
    }

    /**
     * 0's Pass: ALL players pass their hand to the next player in current direction.
     */
    private fun applyZeroPass(): GameState {
        val players = state.players.toMutableList()
        val connectedIndices = players.indices.filter { players[it].isConnected }
        val size = connectedIndices.size

        if (size > 1) {
            val hands = connectedIndices.map { players[it].hand.toMutableList() }
            val rotatedHands = MutableList(size) { mutableListOf<Card>() }
            for (i in 0 until size) {
                val targetIndex = if (state.direction == Direction.CLOCKWISE) {
                    (i + 1) % size
                } else {
                    (i - 1 + size) % size
                }
                rotatedHands[targetIndex] = hands[i]
            }

            for (i in 0 until size) {
                val playerIndex = connectedIndices[i]
                // Same as the 7-swap: the UNO call belongs to the hand, not the seat. Everyone is
                // holding someone else's cards now, so everyone has to call again.
                players[playerIndex] = players[playerIndex]
                    .copy(hand = rotatedHands[i], hasCalledUno = false)
            }
        }

        // Describe the rotation as a cycle where each entry hands off to the NEXT one, so the
        // animation needs no notion of direction of its own. Clockwise already reads that way
        // (hand i moves to i+1); counter-clockwise is the same cycle walked backwards, so the list
        // is simply reversed rather than every consumer having to special-case it.
        val cycle = connectedIndices.map { players[it].id }
            .let { if (state.direction == Direction.CLOCKWISE) it else it.reversed() }

        val nextIndex = getNextPlayerFrom(state.currentPlayerIndex)
        val seq = nextSequence()
        return state.copy(
            players = players,
            currentPlayerIndex = nextIndex,
            // size <= 1 means no hand actually moved, so there is nothing to animate.
            handTransfer = if (size > 1) {
                HandTransfer(seq, isRotation = true, order = cycle)
            } else state.handTransfer,
            sequenceNumber = seq
        )
    }

    /**
     * Discard All: discard all cards from your hand that match the color of the Discard All card.
     */
    private fun applyDiscardAll(discardAllCard: Card): GameState {
        val currentPlayer = state.players[state.currentPlayerIndex]
        val matchingCards = currentPlayer.hand.filter { it.color == discardAllCard.color && it.id != discardAllCard.id }

        if (matchingCards.isEmpty()) {
            val nextIndex = getNextPlayerFrom(state.currentPlayerIndex)
            return state.copy(currentPlayerIndex = nextIndex, sequenceNumber = nextSequence())
        }

        val updatedHand = currentPlayer.hand.toMutableList()
        updatedHand.removeAll { it.color == discardAllCard.color && it.id != discardAllCard.id }

        // Place discarded cards under the Discard All card in the discard pile
        val updatedDiscard = state.discardPile.toMutableList()
        updatedDiscard.addAll(updatedDiscard.size - 1, matchingCards) // Insert under the top card

        val updatedPlayers = state.players.toMutableList()
        updatedPlayers[state.currentPlayerIndex] = currentPlayer.copy(hand = updatedHand)

        // Check if player won by discarding all
        if (updatedHand.isEmpty()) {
            return state.copy(
                players = updatedPlayers,
                discardPile = updatedDiscard,
                phase = GamePhase.ROUND_OVER,
                winnerId = currentPlayer.id,
                sequenceNumber = nextSequence()
            )
        }

        val nextIndex = getNextPlayerFrom(state.currentPlayerIndex)
        return state.copy(
            players = updatedPlayers,
            discardPile = updatedDiscard,
            currentPlayerIndex = nextIndex,
            sequenceNumber = nextSequence()
        )
    }

    /**
     * Mercy Rule: if a player has 25+ cards, they are knocked out of the game.
     * Their cards are set aside for reshuffling later.
     */
    private fun checkMercyRule(): GameState {
        var currentState = state
        val knockedOut = mutableListOf<String>()

        val updatedPlayers = currentState.players.toMutableList()
        for (i in updatedPlayers.indices) {
            val player = updatedPlayers[i]
            if (player.hand.size >= settings.mercyThreshold && player.isConnected) {
                // Player is knocked out - set aside their cards
                val knockedOutCards = player.hand.toList()
                updatedPlayers[i] = player.copy(
                    hand = mutableListOf(),
                    isConnected = false, // Mark as out
                    // Bank the hand's value before it's cleared - see calculateScores.
                    score = knockedOutCards.sumOf { it.points }
                )
                knockedOut.add(player.id)

                // Add their cards to a reserve pile (will be used on reshuffle)
                val newDrawPile = currentState.drawPile.toMutableList()
                newDrawPile.addAll(knockedOutCards)
                newDrawPile.shuffle(random)
                currentState = currentState.copy(drawPile = newDrawPile)
            }
        }

        if (knockedOut.isNotEmpty()) {
            currentState = currentState.copy(players = updatedPlayers)

            // Check if only one player remains.
            //
            // `<= 1`, matching knockOutPlayer's own terminal check, which the two paths disagreed
            // on: this one tested `== 1`, so a state with ZERO players left standing fell through
            // to the else branch and the round carried on with nobody in it - findNextActivePlayer
            // would return the index it started from and the turn could never legally move again.
            // Reaching zero needs two seats to cross the threshold in a single action, which no
            // current card effect does, but the cost of being wrong is a permanently hung round and
            // the cost of the guard is one character.
            val activePlayers = updatedPlayers.filter { it.isConnected }
            if (activePlayers.size <= 1) {
                currentState = currentState.copy(
                    phase = GamePhase.ROUND_OVER,
                    winnerId = activePlayers.firstOrNull()?.id,
                    sequenceNumber = nextSequence()
                )
            } else {
                // If current player was knocked out, advance turn
                val currentPlayerStillActive = updatedPlayers[currentState.currentPlayerIndex].isConnected
                if (!currentPlayerStillActive) {
                    val nextActive = findNextActivePlayer(currentState.currentPlayerIndex, updatedPlayers)
                    currentState = currentState.copy(currentPlayerIndex = nextActive)
                }
            }
        }

        return currentState
    }

    private fun findNextActivePlayer(fromIndex: Int, players: List<Player>): Int {
        val step = if (state.direction == Direction.CLOCKWISE) 1 else -1
        var next = fromIndex
        repeat(players.size) {
            next += step
            if (next >= players.size) next = 0
            if (next < 0) next = players.size - 1
            if (players[next].isConnected) return next
        }
        return fromIndex
    }

    /**
     * Called when a player's socket drops mid-game. Pauses the round so nobody can act
     * while we wait to see if they come back. If someone else is already being waited on,
     * this player is knocked out immediately instead (no second grace period).
     */
    fun beginDisconnectCountdown(playerId: String): GameState {
        if (state.phase == GamePhase.PAUSED_DISCONNECT) {
            return disconnectAdditionalPlayer(playerId)
        }
        // Settle a pending 7's swap first. Without this the drop hits the PLAYING guard below and
        // is discarded entirely: no pause, no knockout, and the player stays marked connected -
        // a zombie seat that collects an auto-draw every time the turn reaches it. That's most
        // likely exactly when it happens, since the chooser is the one holding the round open.
        if (state.phase == GamePhase.CHOOSING_SWAP) {
            state = autoResolveSwap()
        }
        if (state.phase != GamePhase.PLAYING) return state
        val player = state.getPlayerById(playerId) ?: return state
        if (!player.isConnected) return state

        state = state.copy(
            phase = GamePhase.PAUSED_DISCONNECT,
            disconnectedPlayerId = playerId,
            disconnectCountdown = DISCONNECT_TIMEOUT_SECONDS,
            sequenceNumber = nextSequence()
        )
        return state
    }

    /** A second player dropped while we were already waiting on someone else - knock them out right away. */
    private fun disconnectAdditionalPlayer(playerId: String): GameState {
        if (playerId == state.disconnectedPlayerId) return state
        val player = state.getPlayerById(playerId) ?: return state
        if (!player.isConnected) return state
        return knockOutPlayer(playerId, resumePlayingAfter = false)
    }

    /**
     * [playerId] made it back inside their own grace window - resume the round exactly where it
     * paused. Nothing about the round state changes while PAUSED_DISCONNECT (nobody may act, per
     * canPlayCard/drawCardForPlayer's own phase == PLAYING gates), so "resume" really is just
     * clearing the pause: currentPlayerIndex is untouched, so if it was this player's own turn
     * when they dropped, it still is.
     *
     * Deliberately narrow: only reconnects a player who is CURRENTLY the one being waited on. A
     * player already knocked out (the 30s ran out, or disconnectAdditionalPlayer already claimed
     * their seat while someone else was being waited on) has had their hand cleared and shuffled
     * back into the draw pile - there is no "their cards" left to hand back, so that seat is gone
     * for the round. They can still rejoin the NEXT round via a rematch.
     */
    fun reconnectPlayer(playerId: String): GameState {
        if (state.phase != GamePhase.PAUSED_DISCONNECT) return state
        if (state.disconnectedPlayerId != playerId) return state
        val player = state.getPlayerById(playerId) ?: return state

        val updatedPlayers = state.players.toMutableList()
        updatedPlayers[state.players.indexOf(player)] = player.copy(isConnected = true)
        state = state.copy(
            players = updatedPlayers,
            phase = GamePhase.PLAYING,
            disconnectedPlayerId = null,
            disconnectCountdown = DISCONNECT_TIMEOUT_SECONDS,
            sequenceNumber = nextSequence()
        )
        return state
    }

    /**
     * Call once per second while phase == PAUSED_DISCONNECT. Ticks the countdown down and,
     * once it hits zero, knocks the disconnected player out and resumes the round without them.
     */
    fun tickDisconnectCountdown(): GameState {
        if (state.phase != GamePhase.PAUSED_DISCONNECT) return state

        val remaining = state.disconnectCountdown - 1
        if (remaining > 0) {
            state = state.copy(disconnectCountdown = remaining, sequenceNumber = nextSequence())
            return state
        }

        val playerId = state.disconnectedPlayerId
        return if (playerId != null) {
            knockOutPlayer(playerId, resumePlayingAfter = true)
        } else {
            state.copy(
                phase = GamePhase.PLAYING,
                disconnectCountdown = DISCONNECT_TIMEOUT_SECONDS,
                sequenceNumber = nextSequence()
            ).also { state = it }
        }
    }

    /**
     * A player intentionally left mid-match (as opposed to their connection just dropping) -
     * knock them out immediately with no grace period, since there's no point waiting on someone
     * who's already gone. Reuses the same knockout path a timed-out disconnect would take, so
     * everyone else sees the exact same, already-handled "player is out" behavior.
     */
    fun playerLeft(playerId: String): GameState {
        // Don't resume the round if we're paused waiting on a DIFFERENT player - knockOutPlayer's
        // resume path clears phase/disconnectedPlayerId/countdown wholesale, which would silently
        // cancel that player's grace period and leave them marked connected but unreachable,
        // collecting auto-draws on every turn instead of ever being knocked out.
        val pausedForSomeoneElse =
            state.phase == GamePhase.PAUSED_DISCONNECT && state.disconnectedPlayerId != playerId
        return knockOutPlayer(playerId, resumePlayingAfter = !pausedForSomeoneElse)
    }

    /**
     * Marks a player disconnected (knocked out of turn rotation), advancing the turn off them
     * if needed and ending the round if only one connected player remains. Their hand is cleared
     * and shuffled back into the draw pile, same as a Mercy-rule knockout - so "out" is scored
     * consistently regardless of whether it happened by disconnecting or by hitting 25 cards.
     */
    private fun knockOutPlayer(playerId: String, resumePlayingAfter: Boolean): GameState {
        val player = state.getPlayerById(playerId) ?: return state
        val playerIndex = state.players.indexOf(player)
        val updatedPlayers = state.players.toMutableList()
        val knockedOutCards = player.hand.toList()
        updatedPlayers[playerIndex] = player.copy(
            hand = mutableListOf(),
            isConnected = false,
            score = knockedOutCards.sumOf { it.points }
        )

        val newDrawPile = state.drawPile.toMutableList()
        newDrawPile.addAll(knockedOutCards)
        newDrawPile.shuffle(random)

        val activePlayers = updatedPlayers.filter { it.isConnected }
        state = if (activePlayers.size <= 1) {
            state.copy(
                players = updatedPlayers,
                drawPile = newDrawPile,
                phase = GamePhase.ROUND_OVER,
                winnerId = activePlayers.firstOrNull()?.id,
                disconnectedPlayerId = null,
                // A 7's swap can be outstanding when the round ends this way; leaving it set would
                // strand CHOOSING_SWAP state on a finished round.
                pendingSwapPlayerId = null,
                disconnectCountdown = DISCONNECT_TIMEOUT_SECONDS,
                sequenceNumber = nextSequence()
            )
        } else {
            val currentPlayerKnockedOut = playerIndex == state.currentPlayerIndex
            val nextIndex = if (currentPlayerKnockedOut) {
                findNextActivePlayer(playerIndex, updatedPlayers)
            } else {
                state.currentPlayerIndex
            }
            state.copy(
                players = updatedPlayers,
                drawPile = newDrawPile,
                phase = if (resumePlayingAfter) GamePhase.PLAYING else state.phase,
                currentPlayerIndex = nextIndex,
                pendingSwapPlayerId = if (resumePlayingAfter) null else state.pendingSwapPlayerId,
                disconnectedPlayerId = if (resumePlayingAfter) null else state.disconnectedPlayerId,
                disconnectCountdown = if (resumePlayingAfter) DISCONNECT_TIMEOUT_SECONDS else state.disconnectCountdown,
                sequenceNumber = nextSequence()
            )
        }
        return state
    }

    /**
     * In No Mercy: player must draw cards UNTIL they draw one they can play, then play it.
     * In Classic: player draws 1 card.
     */
    /**
     * The turn clock ran out on [playerId]. Always moves the game on.
     *
     * "Always" is the whole point. This used to be `drawCardForPlayer`, which refuses to draw for a
     * player holding a playable card - so for the commonest case of all, someone with a perfectly
     * good hand who simply walked away, the timeout did precisely nothing. The clock hit zero, the
     * state came back unchanged, and the table sat there with no way forward but quitting.
     *
     * What it does is the host's [TimeoutAction] setting; SKIP everywhere unless a custom table asks
     * for the harsher one. A live draw stack is not negotiable either way - it is already owed, so it
     * is taken - and CHOOSING_SWAP has its own resolver.
     */
    fun applyTurnTimeout(playerId: String): GameState {
        if (state.currentPlayer?.id != playerId) return state
        if (state.phase != GamePhase.PLAYING) return state
        val player = state.getPlayerById(playerId) ?: return state
        val playerIndex = state.players.indexOf(player)

        // An owed stack is taken regardless of the setting - it is a debt, not a penalty.
        if (state.pendingDrawCount > 0) return drawCardForPlayer(playerId)

        val hand = player.hand.toMutableList()
        if (settings.turnTimeoutAction == TimeoutAction.DRAW_ONE) {
            hand.addAll(drawCards(1))
        }
        val updatedPlayers = state.players.toMutableList()
        // hasCalledUno is cleared for the same reason a draw clears it: the hand grew, or the turn
        // passed without them ever declaring.
        updatedPlayers[playerIndex] = player.copy(hand = hand, hasCalledUno = false)

        state = state.copy(
            players = updatedPlayers,
            currentPlayerIndex = getNextPlayerFrom(playerIndex),
            voluntaryDrawBy = null,
            sequenceNumber = nextSequence()
        )

        if (settings.gameMode == GameMode.NO_MERCY) {
            state = checkMercyRule()
        }
        return state
    }

    fun drawCardForPlayer(playerId: String): GameState {
        if (state.currentPlayer?.id != playerId) return state
        if (state.phase != GamePhase.PLAYING) return state
        val player = state.getPlayerById(playerId) ?: return state
        val playerIndex = state.players.indexOf(player)

        if (state.pendingDrawCount > 0) {
            // Forced draw from stacking - draw ALL pending cards, lose turn
            val drawn = drawCards(state.pendingDrawCount)
            val updatedHand = player.hand.toMutableList()
            updatedHand.addAll(drawn)
            val updatedPlayers = state.players.toMutableList()
            updatedPlayers[playerIndex] = player.copy(hand = updatedHand, hasCalledUno = false)
            val nextIndex = getNextPlayerFrom(playerIndex)

            state = state.copy(
                players = updatedPlayers,
                currentPlayerIndex = nextIndex,
                pendingDrawCount = 0,
                lastDrawCardValue = 0,
                sequenceNumber = nextSequence()
            )

            // Check mercy rule
            if (settings.gameMode == GameMode.NO_MERCY) {
                state = checkMercyRule()
            }
            return state
        }

        // Drawing is always allowed on your own turn, whether or not you hold something playable.
        //
        // It used to be refused outright while you had a legal card, on the reading that the rule is
        // "if you don't have a matching card, you must draw". But that is a rule about when you MUST
        // draw, not about when you MAY, and it took away an ordinary decision: holding a green +2 and
        // a green 5 and wanting to keep both is a real choice, and the board simply ignored the tap.
        //
        // The refusal was closing a genuine exploit, and that still has to be closed: a drawn
        // PLAYABLE card keeps the turn, so with unlimited draws a player could fish the deck all
        // turn and stop whenever it suited them. The cap below - one voluntary draw per turn - closes
        // it without removing the choice.
        //
        // This is also what made the turn clock do nothing. The timeout handler called this very
        // function, it returned `state` untouched for anyone holding a playable card, and the turn
        // never advanced; see [applyTurnTimeout].
        val hasPlayable = getPlayableCards(playerId).isNotEmpty()
        if (hasPlayable && state.voluntaryDrawBy == playerId) return state

        if (!hasPlayable && settings.gameMode == GameMode.NO_MERCY) {
            // No Mercy: with nothing playable, draw until you get a card you can play and play it.
            // Only when there was nothing to begin with - a VOLUNTARY draw must never auto-play a
            // card the player didn't choose.
            return drawUntilPlayable(playerId, playerIndex)
        } else {
            // Classic: draw 1 card. If the drawn card is playable, keep the turn so the
            // player can choose to play it. Otherwise the turn passes to the next player.
            val drawn = drawCards(1)
            val updatedHand = player.hand.toMutableList()
            updatedHand.addAll(drawn)
            val updatedPlayers = state.players.toMutableList()
            updatedPlayers[playerIndex] = player.copy(hand = updatedHand, hasCalledUno = false)

            val drawnCard = drawn.firstOrNull()
            // A voluntary draw always keeps the turn - the player still holds something playable and
            // has to actually play it. Otherwise the usual rule: keep the turn only if the card that
            // came up can be played.
            val keepTurn = hasPlayable || (drawnCard != null && isCardPlayableFromHand(drawnCard))
            val nextIndex = if (keepTurn) playerIndex else getNextPlayerFrom(playerIndex)

            state = state.copy(
                players = updatedPlayers,
                currentPlayerIndex = nextIndex,
                pendingDrawCount = 0,
                // Spent for this turn if it was voluntary; cleared outright once the turn moves on.
                voluntaryDrawBy = if (!keepTurn) null else if (hasPlayable) playerId else state.voluntaryDrawBy,
                sequenceNumber = nextSequence()
            )
            return state
        }
    }

    /**
     * No Mercy draw rule: draw from pile until you get a card you can play, then play it.
     */
    private fun drawUntilPlayable(playerId: String, playerIndex: Int): GameState {
        val drawPile = state.drawPile.toMutableList()
        val playerHand = state.players[playerIndex].hand.toMutableList()
        var playableCard: Card? = null
        var safetyCounter = 50 // Prevent infinite loop

        while (safetyCounter > 0) {
            if (drawPile.isEmpty()) {
                reshuffleDiscardIntoPile(drawPile)
            }
            if (drawPile.isEmpty()) break

            val drawn = drawPile.removeFirst()
            playerHand.add(drawn)

            // Check if drawn card is playable
            if (isCardPlayableFromHand(drawn)) {
                playableCard = drawn
                break
            }

            // Check mercy threshold while drawing
            if (playerHand.size >= settings.mercyThreshold) break

            safetyCounter--
        }

        val updatedPlayers = state.players.toMutableList()
        updatedPlayers[playerIndex] = state.players[playerIndex].copy(hand = playerHand, hasCalledUno = false)
        state = state.copy(players = updatedPlayers, drawPile = drawPile, sequenceNumber = nextSequence())

        if (playableCard != null) {
            // Auto-play the drawn card. For wild cards pick the color the player has most of.
            val autoColor = if (playableCard.isWildCard()) {
                playerHand
                    .filter { it.color != CardColor.WILD }
                    .groupBy { it.color }
                    .maxByOrNull { it.value.size }?.key
                    ?: state.currentColor
            } else null
            state = playCard(playerId, playableCard, autoColor)
        } else {
            // Couldn't find a playable card (deck empty or mercy hit) - just pass
            val nextIndex = getNextPlayerFrom(state.currentPlayerIndex)
            state = state.copy(currentPlayerIndex = nextIndex, sequenceNumber = nextSequence())
        }

        // Check mercy rule after all drawing
        if (settings.gameMode == GameMode.NO_MERCY) {
            state = checkMercyRule()
        }

        return state
    }

    private fun isCardPlayableFromHand(card: Card): Boolean {
        val topCard = state.topCard ?: return true
        return when {
            card.isWildCard() -> true
            card.color == state.currentColor -> true
            card.type == topCard.type -> true
            else -> false
        }
    }

    /**
     * Completes a 7's swap. [chooserId] must be the player whose 7 is pending, and [targetId] a
     * different, still-connected seat.
     *
     * hasCalledUno is cleared on both sides: the flag describes a hand, not a seat, so whoever
     * ends up holding a fresh hand has to call again.
     */
    fun chooseSwapTarget(chooserId: String, targetId: String): GameState {
        if (state.phase != GamePhase.CHOOSING_SWAP) return state
        if (state.pendingSwapPlayerId != chooserId) return state
        if (chooserId == targetId) return state

        val chooser = state.getPlayerById(chooserId) ?: return state
        val target = state.getPlayerById(targetId) ?: return state
        if (!target.isConnected) return state

        val chooserIndex = state.players.indexOf(chooser)
        val targetIndex = state.players.indexOf(target)
        val updatedPlayers = state.players.toMutableList()
        val chooserHand = chooser.hand.toMutableList()

        updatedPlayers[chooserIndex] = chooser.copy(hand = target.hand.toMutableList(), hasCalledUno = false)
        updatedPlayers[targetIndex] = target.copy(hand = chooserHand, hasCalledUno = false)

        val seq = nextSequence()
        state = state.copy(
            players = updatedPlayers,
            phase = GamePhase.PLAYING,
            pendingSwapPlayerId = null,
            currentPlayerIndex = getNextPlayerFrom(chooserIndex),
            // A two-element cycle: the chooser's hand goes to the target and back again.
            handTransfer = HandTransfer(seq, isRotation = false, order = listOf(chooserId, targetId)),
            sequenceNumber = seq
        )

        if (settings.gameMode == GameMode.NO_MERCY) {
            state = checkMercyRule()
        }
        return state
    }

    /**
     * Fallback when the chooser never picks - the turn clock calls this so a 7 can't stall the
     * round forever. Targets the largest hand, which is what the engine used to do automatically
     * for every 7 before the picker existed.
     */
    fun autoResolveSwap(): GameState {
        if (state.phase != GamePhase.CHOOSING_SWAP) return state
        val chooserId = state.pendingSwapPlayerId ?: return state
        val target = state.players
            .filter { it.id != chooserId && it.isConnected }
            .maxByOrNull { it.cardCount }
            ?: run {
                // Nobody left to trade with - release the hold rather than stranding the round.
                val chooser = state.getPlayerById(chooserId)
                val idx = state.players.indexOf(chooser)
                state = state.copy(
                    phase = GamePhase.PLAYING,
                    pendingSwapPlayerId = null,
                    currentPlayerIndex = if (idx >= 0) getNextPlayerFrom(idx) else state.currentPlayerIndex,
                    sequenceNumber = nextSequence()
                )
                return state
            }
        return chooseSwapTarget(chooserId, target.id)
    }

    fun callUno(playerId: String): GameState {
        // Only during live play. Without this a CallUno arriving after the round ended still
        // mutated the finished state and bumped the sequence number, which pushed applyHostState
        // back down its ROUND_OVER branch and re-broadcast GameOver on top of a completed game;
        // during a disconnect pause it let penalties land while the table was supposed to be frozen.
        if (state.phase != GamePhase.PLAYING) return state
        val player = state.getPlayerById(playerId) ?: return state
        if (!player.isConnected) return state  // knocked out - not a participant any more
        val playerIndex = state.players.indexOf(player)
        val updatedPlayers = state.players.toMutableList()

        if (player.hasCalledUno) return state  // already called, no-op

        // UNO is declared at TWO cards - before the card that takes you down to one, not after.
        //
        // This used to accept the call at one card, which is a materially easier game: you played
        // your second-to-last card, saw the result, and only then decided to declare. Nothing was
        // ever risked, so the call was a formality and the CATCH mechanic could only ever punish
        // someone who was slow rather than someone who was careless.
        //
        // Declaring first is the actual rule, and it is a real decision: you have to see the play
        // coming before you make it. It also carries a genuine cost when the last-card rule is on -
        // declaring on two power cards commits you publicly to a hand you cannot legally finish
        // with, since canPlayCard locks a non-number card that would empty your hand, so everyone
        // now knows you must draw on your next turn.
        //
        // playCard already preserves the flag across exactly this transition (it keeps
        // hasCalledUno when the hand lands on one card, clearing it otherwise), so a declaration
        // made at two survives the play it was made for and nothing else.
        if (player.hand.size == 2) {
            // Valid UNO call
            updatedPlayers[playerIndex] = player.copy(hasCalledUno = true)
        } else {
            // False call penalty: draw 2 cards
            val drawn = drawCards(2)
            val updatedHand = player.hand.toMutableList()
            updatedHand.addAll(drawn)
            updatedPlayers[playerIndex] = player.copy(hand = updatedHand)
        }

        state = state.copy(players = updatedPlayers, sequenceNumber = nextSequence())
        return state
    }

    fun penalizeUnoNotCalled(reporterId: String, targetPlayerId: String): GameState {
        // Same reasoning as callUno: catching someone is only meaningful while the round is live.
        if (state.phase != GamePhase.PLAYING) return state

        // The reporter has to still be IN the round. A player knocked out at the Mercy threshold
        // keeps watching the table (see GameState.isSpectating) and their board still renders every
        // opponent seat, so nothing stopped them tapping CATCH and forcing two cards onto a live
        // player - someone eliminated from the round was still able to change its outcome.
        val reporter = state.getPlayerById(reporterId) ?: return state
        if (!reporter.isConnected) return state
        // Catching yourself is not a thing; callUno is how you declare, and self-reporting here
        // would be a free way to dodge the real penalty by taking it on your own terms.
        if (reporterId == targetPlayerId) return state

        val player = state.getPlayerById(targetPlayerId) ?: return state
        if (!player.isConnected) return state
        val playerIndex = state.players.indexOf(player)

        if (player.hand.size == 1 && !player.hasCalledUno) {
            val drawn = drawCards(2)
            val updatedHand = player.hand.toMutableList()
            updatedHand.addAll(drawn)
            val updatedPlayers = state.players.toMutableList()
            updatedPlayers[playerIndex] = player.copy(hand = updatedHand)
            state = state.copy(players = updatedPlayers, sequenceNumber = nextSequence())
        }
        return state
    }

    private fun drawCards(count: Int): List<Card> {
        val drawn = mutableListOf<Card>()
        val drawPile = state.drawPile.toMutableList()

        repeat(count) {
            if (drawPile.isEmpty()) {
                reshuffleDiscardIntoPile(drawPile)
            }
            if (drawPile.isNotEmpty()) {
                drawn.add(drawPile.removeFirst())
            }
        }

        state = state.copy(drawPile = drawPile)
        return drawn
    }

    /**
     * Draw cards from pile until the specified color appears (Wild cards do NOT count).
     * Used for Wild Color Roulette.
     */
    private fun drawUntilColor(color: CardColor): List<Card> {
        val drawn = mutableListOf<Card>()
        val drawPile = state.drawPile.toMutableList()
        var maxDraw = 40 // Safety limit

        while (maxDraw > 0) {
            if (drawPile.isEmpty()) {
                reshuffleDiscardIntoPile(drawPile)
            }
            if (drawPile.isEmpty()) break

            val card = drawPile.removeFirst()
            drawn.add(card)
            // Wild cards do NOT count - must be an actual colored card
            if (card.color == color && !card.isWildCard()) break
            maxDraw--
        }

        state = state.copy(drawPile = drawPile)
        return drawn
    }

    private fun reshuffleDiscardIntoPile(drawPile: MutableList<Card>) {
        val discardPile = state.discardPile.toMutableList()
        if (discardPile.size <= 1) return

        val topCard = discardPile.removeLast()
        discardPile.shuffle(random)
        drawPile.addAll(discardPile)

        state = state.copy(discardPile = listOf(topCard))
    }

    /**
     * The draw penalty carried by a card that WON the round, dealt before the round closes.
     *
     * Winning on a +2 used to cost the next player nothing at all: playCard returned at ROUND_OVER
     * before applyCardEffect ever ran, so the card's whole effect evaporated at the moment it
     * mattered most. That is wrong twice over - the next player should take the cards, and because
     * scoring sums what everyone is still holding, those cards are points for the winner. Closing a
     * round on a +4 was silently worth less than closing it on a 9.
     *
     * Only the DRAW half is applied. Skip and Reverse change nothing once the round is over, and
     * the hand-manipulation rules must NOT run: a 0 or a 7 is a number card, so it can legally be
     * the winning card even under "last card must be a number", and rotating or swapping hands
     * afterwards would deal the winner a fresh hand and un-win the round they just won.
     *
     * Any pending stack is included. If the winner answered a live +4 with their own +4, the next
     * player owes the whole tower, and there is nobody left for them to pass it to.
     */
    private fun applyWinningCardPenalty(card: Card, winnerIndex: Int): GameState {
        val penalty = state.pendingDrawCount + Card.drawValue(card.type)
        if (penalty <= 0) return state

        // A reverse +4 flips the direction before it points at anyone, so the victim is on the
        // other side of the table from the one the current direction would suggest.
        val direction = if (card.type == CardType.WILD_REVERSE_DRAW_FOUR) {
            state.direction.reversed()
        } else {
            state.direction
        }
        val victimIndex = getNextPlayerFromWith(winnerIndex, direction)
        val victim = state.players.getOrNull(victimIndex) ?: return state
        if (victimIndex == winnerIndex) return state   // no one else left in the round

        val drawn = drawCards(penalty)
        if (drawn.isEmpty()) return state

        val updatedPlayers = state.players.toMutableList()
        updatedPlayers[victimIndex] = victim.copy(hand = (victim.hand + drawn).toMutableList())
        return state.copy(players = updatedPlayers, sequenceNumber = nextSequence())
    }

    private fun getNextPlayerFrom(index: Int): Int {
        val step = if (state.direction == Direction.CLOCKWISE) 1 else -1
        var next = index + step
        if (next >= state.players.size) next = 0
        if (next < 0) next = state.players.size - 1

        // Skip knocked-out players
        var attempts = state.players.size
        while (!state.players[next].isConnected && attempts > 0) {
            next += step
            if (next >= state.players.size) next = 0
            if (next < 0) next = state.players.size - 1
            attempts--
        }
        // If we exhausted attempts without finding a connected player, stay at current index
        // (game should already be ROUND_OVER at this point)
        if (attempts == 0 && !state.players[next].isConnected) return index
        return next
    }

    private fun getNextPlayerFromWith(index: Int, direction: Direction): Int {
        val step = if (direction == Direction.CLOCKWISE) 1 else -1
        var next = index + step
        if (next >= state.players.size) next = 0
        if (next < 0) next = state.players.size - 1

        var attempts = state.players.size
        while (!state.players[next].isConnected && attempts > 0) {
            next += step
            if (next >= state.players.size) next = 0
            if (next < 0) next = state.players.size - 1
            attempts--
        }
        if (attempts == 0 && !state.players[next].isConnected) return index
        return next
    }

    private fun getNextPlayerIndexWith(direction: Direction): Int {
        return getNextPlayerFromWith(state.currentPlayerIndex, direction)
    }

    private fun getPreviousPlayerIndex(): Int {
        val step = if (state.direction == Direction.CLOCKWISE) -1 else 1
        var prev = state.currentPlayerIndex + step
        if (prev >= state.players.size) prev = 0
        if (prev < 0) prev = state.players.size - 1

        // Skip disconnected/knocked-out players, same as getNextPlayerFrom - otherwise a
        // challenge could resolve against the wrong seat if someone between the challenger and
        // the wild-+4 player dropped out.
        var attempts = state.players.size
        while (!state.players[prev].isConnected && attempts > 0) {
            prev += step
            if (prev >= state.players.size) prev = 0
            if (prev < 0) prev = state.players.size - 1
            attempts--
        }
        if (attempts == 0 && !state.players[prev].isConnected) return state.currentPlayerIndex
        return prev
    }

    private fun nextSequence(): Long = ++sequenceCounter

    fun getPlayableCards(playerId: String): List<Card> {
        val player = state.getPlayerById(playerId) ?: return emptyList()
        return player.hand.filter { canPlayCard(it, playerId) }
    }

    fun calculateScores(): Map<String, Int> {
        // A knocked-out player's hand was emptied and shuffled back into the draw pile, so scoring
        // off their live hand gave them 0 - the BEST possible loser score, ranking whoever exploded
        // at 25 cards above everyone who survived. It also short-changed the winner, whose score is
        // the sum of what everyone else was left holding. Use the value banked at knockout instead.
        fun pointsFor(p: Player): Int =
            if (!p.isConnected) p.score else p.hand.sumOf { it.points }

        val losersTotal = state.players
            .filter { it.id != state.winnerId }
            .sumOf { pointsFor(it) }

        val scores = mutableMapOf<String, Int>()
        for (player in state.players) {
            scores[player.id] = if (player.id == state.winnerId) losersTotal else pointsFor(player)
        }
        return scores
    }
}
