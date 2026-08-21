package com.mutsho.localuno.model

/**
 * A card that just landed on the discard pile, and who put it there.
 *
 * [byPlayerId] is what makes the flight animation worth building. A card that appears on the pile
 * from nowhere tells you a card was played; one that travels from a seat tells you WHO played it,
 * which on a four-player table is the thing you actually have to keep track of and the thing the
 * move log currently exists to tell you in words.
 */
data class PlayedCard(val card: Card, val byPlayerId: String)

/**
 * Which card, if any, was just played, and by whom.
 *
 * Derived by diffing rather than carried on the wire, for the same reason the move log is: the host
 * would have to add a field, every guest would need a build that reads it, and the two could drift.
 * Both ends already receive enough to work it out, and working it out cannot desync from the board
 * it describes.
 *
 * The attribution rule is simply that **the player whose turn it was is the one who played it**.
 * That holds for every card in this game: you cannot play on somebody else's turn, and every path
 * that puts a card on the pile - an ordinary play, a wild with a colour chosen, the auto-play at
 * the end of drawUntilPlayable - runs on the actor's own turn.
 *
 * Returns null rather than guessing in the three cases where nobody played anything:
 *
 *  - a new round, whose deal flips a starting card onto an empty pile;
 *  - a state where the pile did not change at all;
 *  - a first update on a client that has no previous board to compare against.
 */
fun detectPlayedCard(previous: GameState, next: GameState): PlayedCard? {
    val landed = next.topCard ?: return null
    val before = previous.topCard

    // A fresh round deals a new starting card. Nobody played it, and flying it in from whichever
    // seat happened to be current would be a small lie told at the start of every round.
    if (next.roundStartTime != previous.roundStartTime) return null

    // Nothing new on the pile.
    if (before != null && before.id == landed.id) return null

    // No prior board to attribute against - the very first update a client applies.
    if (before == null && previous.players.isEmpty()) return null

    val actor = previous.currentPlayer?.id ?: return null
    return PlayedCard(landed, actor)
}
