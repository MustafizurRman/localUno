package com.mutsho.localuno.model

/**
 * Something the table did TO you, worth feeling without looking.
 *
 * Deliberately not a list of everything that happens. The app already buzzes when you play, draw or
 * call UNO - all confirmations of a tap you just made and already knew about - and adding more of
 * those would make the feedback channel noise. These are the opposite: the moments you are least
 * likely to be watching the screen, because nothing has been asked of you yet.
 */
sealed class TableEvent {

    /** A draw stack is now pointing at you and it is your turn. [count] is what you owe. */
    data class StackLanded(val count: Int) : TableEvent()

    /** Cards arrived in your hand during a stretch where you were never the active player - a
     *  seven's swap, a zero's rotation, or a penalty. */
    data object HandHit : TableEvent()

    /**
     * You are no longer an active participant in this round - in practice a No Mercy knockout,
     * since that is the only way a seated player stops playing while the round carries on.
     */
    data object KnockedOut : TableEvent()
}

/**
 * What the table did to [me] between two states.
 *
 * Pure, and tested hard, because the rule that keeps this useful is a negative one and negatives
 * are what rot silently: **never report something the player caused themselves**. Someone who taps a
 * card and feels three separate pulses learns to ignore all of them, which costs you the one that
 * mattered.
 *
 * The suppression is structural rather than a list of special cases. Every event below requires
 * that the local player was NOT the active seat across the transition, and "the active seat" is
 * exactly what "did this" means in this game: you cannot play a card, draw, take a stack or choose
 * a swap target on someone else's turn. So anything that changes your hand while the turn was never
 * yours was, by construction, done to you.
 *
 * That is also why this takes both states rather than reacting to messages. A seven's swap that YOU
 * played changes your hand exactly as much as one played against you; the only thing that tells
 * them apart is whose turn it was.
 */
fun detectTableEvents(previous: GameState, next: GameState, me: String): List<TableEvent> {
    // Nothing to say about a table that has not started, finished, or has no seat for this player.
    if (me.isBlank()) return emptyList()
    val before = previous.players.find { it.id == me } ?: return emptyList()
    val after = next.players.find { it.id == me } ?: return emptyList()

    // A brand-new round deals everyone a hand and knocks nobody out; reporting that as a hit would
    // buzz every player at the start of every round.
    if (next.roundStartTime != previous.roundStartTime) return emptyList()

    val wasMyTurn = previous.currentPlayer?.id == me
    val isMyTurn = next.currentPlayer?.id == me

    return buildList {
        // Knocked out first: if this fired alongside a hand change, the knockout is the thing worth
        // feeling, and it is the one event that ends your involvement rather than adding to it.
        val nowOut = next.isSpectating(me)
        if (nowOut && !previous.isSpectating(me)) {
            add(TableEvent.KnockedOut)
            return@buildList
        }
        if (nowOut) return@buildList

        // A stack that grew and then stopped on you. It cannot be your own play: answering a stack
        // passes the turn along, so a stack you added to lands on somebody else.
        if (next.pendingDrawCount > previous.pendingDrawCount && isMyTurn && !wasMyTurn) {
            add(TableEvent.StackLanded(next.pendingDrawCount))
        }

        // Cards appeared in your hand across a transition where you were never the active seat.
        // Requiring BOTH sides rules out everything you initiated: drawing, taking a stack, and the
        // seven you played yourself all happen on a turn that was yours.
        if (after.hand.size > before.hand.size && !wasMyTurn && !isMyTurn) {
            add(TableEvent.HandHit)
        }
    }
}
