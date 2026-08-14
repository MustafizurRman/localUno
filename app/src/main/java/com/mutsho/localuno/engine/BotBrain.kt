package com.mutsho.localuno.engine

import com.mutsho.localuno.model.Card
import com.mutsho.localuno.model.CardColor
import com.mutsho.localuno.model.CardType
import com.mutsho.localuno.model.GameState
import com.mutsho.localuno.model.Player

/**
 * Move selection for bot seats.
 *
 * Deliberately pure and stateless: every decision is a function of the GameState the host already
 * holds. That keeps it testable without a ViewModel, and means a bot can only ever "know" what the
 * engine would let a real player at that seat know - it reads its own hand and everyone else's card
 * COUNTS, never anyone else's cards. A bot that peeked at hands would be trivial to write here and
 * impossible to lose to.
 *
 * The caller supplies the legal moves (GameEngine.getPlayableCards), so nothing in this file has to
 * re-derive playability, stacking or the last-card-must-be-a-number rule. It only ranks.
 */
object BotBrain {

    /**
     * Picks a card from [playable], which must be non-empty and must already be legal for [botId].
     *
     * Scores every option rather than running a priority ladder, because the considerations
     * genuinely compete: dumping a Wild is usually wasteful, but not when the next player is one
     * card from winning.
     */
    fun chooseCard(state: GameState, botId: String, playable: List<Card>): Card {
        val bot = state.getPlayerById(botId)
        val hand = bot?.hand ?: emptyList()
        val threat = nextOpponentThreat(state, botId)

        return playable.maxByOrNull { card -> scoreCard(card, hand, state, threat) } ?: playable.first()
    }

    /**
     * How urgently the next player needs to be attacked, 0f..1f. A seat one card from going out is
     * the whole reason to burn a +4 now rather than save it.
     */
    private fun nextOpponentThreat(state: GameState, botId: String): Float {
        val nextIndex = state.getNextPlayerIndex()
        val next = state.players.getOrNull(nextIndex) ?: return 0f
        if (next.id == botId) return 0f
        return when (next.cardCount) {
            1 -> 1f
            2 -> 0.6f
            3 -> 0.3f
            else -> 0f
        }
    }

    private fun scoreCard(card: Card, hand: List<Card>, state: GameState, threat: Float): Double {
        var score = 0.0

        // A live draw stack needs no special case. Stacking matches by type, so every legal answer
        // is the SAME card type - there is no cheaper or dearer one to choose between, and the only
        // thing separating them is colour, which the general scoring below already weighs (the
        // answer sets the colour the next player has to work against).
        // Attack cards are worth more the closer the next seat is to winning.
        if (card.isDrawCard()) score += 25.0 + threat * 55.0
        if (card.type == CardType.SKIP || card.type == CardType.DARK_SKIP ||
            card.type == CardType.SKIP_EVERYONE || card.type == CardType.DARK_SKIP_EVERYONE
        ) {
            score += 18.0 + threat * 45.0
        }
        // Reverse only skips the next seat in a two-player round; elsewhere it just turns the table
        // around, which is a much weaker answer to someone about to go out.
        if (card.type == CardType.REVERSE || card.type == CardType.DARK_REVERSE) {
            val heads = state.players.count { it.isConnected }
            score += if (heads == 2) 18.0 + threat * 45.0 else 8.0
        }

        // Wilds are the only cards that are playable on anything, so they're the bot's escape hatch
        // when it gets colour-locked. Spending one early to play a card it could have matched
        // normally trades that insurance for nothing.
        if (card.isWildCard()) score -= 35.0 - threat * 30.0

        // Staying in a colour the hand is deep in keeps future turns playable.
        val sameColor = hand.count { it.color == card.color && it.color != CardColor.WILD }
        score += sameColor * 4.0

        // Between otherwise equal cards, shed the one that costs most if the round ends - scoring is
        // by the value left in hand, so a 9 held to the end is worth nine points to the winner.
        score += card.points * 0.35

        // The "last card must be a number" rule turns a power card into a dead end at one card left.
        // With exactly two in hand, play the power card NOW so the survivor is a number and the bot
        // can actually go out. Without this the bot plays the number, then sits on an unplayable
        // last card drawing until it finds another one.
        if (state.settings.rules.lastCardMustBeNumber && hand.size == 2 && !card.isNumberCard()) {
            score += 70.0
        }

        return score
    }

    /**
     * Colour to declare on a wild. [hand] should already exclude the wild being played, so a hand
     * whose only remaining cards are wilds doesn't get counted as evidence for any colour.
     */
    fun chooseColor(hand: List<Card>, fallback: CardColor): CardColor {
        val byColor = hand.filter { it.color != CardColor.WILD }.groupBy { it.color }
        if (byColor.isEmpty()) return fallback.takeIf { it != CardColor.WILD } ?: CardColor.RED
        // Most cards wins; ties break toward the colour carrying more points, which is the one the
        // bot most wants rid of.
        return byColor.entries
            .sortedWith(
                compareByDescending<Map.Entry<CardColor, List<Card>>> { it.value.size }
                    .thenByDescending { entry -> entry.value.sumOf { it.points } }
            )
            .first().key
    }

    /**
     * Seat to trade hands with on a 7. Takes the smallest hand on the table, which is both the
     * strongest move and the one that most needs answering - it's usually the seat about to win.
     * Returns null if there's nobody legal, leaving the engine's own auto-resolve to handle it.
     */
    fun chooseSwapTarget(state: GameState, botId: String): String? {
        return state.players
            .filter { it.id != botId && it.isConnected }
            .minByOrNull { it.cardCount }
            ?.id
    }

    /**
     * Anyone sitting on one card who never declared - fair game for a CATCH.
     *
     * No longer restricted to bots. Under the declare-at-two rule a human who forgets is supposed
     * to be liable, and in a solo game there is nobody but a bot to hold them to it: without this
     * the rule would have teeth against bots and none at all against the player, which is exactly
     * backwards from the one the user described.
     */
    fun canBeCaughtForUno(player: Player): Boolean =
        player.isConnected && player.hand.size == 1 && !player.hasCalledUno

    /**
     * Whether this bot declares UNO now, before playing down to one card.
     *
     * Bots forget roughly one hand in four, on purpose. A bot that always declares correctly is a
     * bot that can never be caught, which would quietly delete the CATCH mechanic from every solo
     * game - the button would be permanently dead UI.
     *
     * The forgetfulness is derived from the hand rather than rolled: this is consulted on every
     * state change, and a fresh random each time would converge on "always declares" the moment
     * anything else on the table moved.
     */
    fun shouldDeclareUno(player: Player): Boolean {
        if (!player.isBot || !player.isConnected) return false
        if (player.hand.size != 2 || player.hasCalledUno) return false
        val fingerprint = player.id.hashCode() * 31 + player.hand.sumOf { it.id }
        return Math.floorMod(fingerprint, 4) != 0
    }
}
