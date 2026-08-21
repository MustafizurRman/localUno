package com.mutsho.localuno.model

/**
 * Ceilings on numbers that arrive from another device and are then used as sizes.
 *
 * A count is not like a string: a string costs what it weighs, but a count costs whatever it says.
 * `opponentCardCounts` is twenty bytes of JSON on the wire and was turned straight into
 * `MutableList(count)` on the receiving side, so `{"p1":2000000000}` asked every guest at the table
 * to allocate two billion cards. The line cap does not help - the message really is tiny. This is
 * the decompression-bomb shape, and it needs a bound on what the number is allowed to MEAN.
 *
 * Negative values are the other half and fail sooner and louder: `MutableList(-1)` throws.
 *
 * The limits are deliberately generous against the real game rather than tight. The largest deck in
 * the app is No Mercy's 168 cards, so no legitimate hand can exceed it and no legitimate draw stack
 * comes close; anything above these is either a bug or an attack, and treating the two the same is
 * correct.
 */
object WireLimits {

    /** Comfortably above the largest deck, so a real hand can never trip it. */
    const val MAX_HAND_CARDS = 256

    /** The draw pile cannot exceed the deck either. */
    const val MAX_DECK_CARDS = 512

    /** Stacked draw penalties are bounded by the cards that can be played onto one. */
    const val MAX_PENDING_DRAW = 256

    /** Seats at one table. The UI offers 2-8; this is the outer bound on a hostile roster. */
    const val MAX_PLAYERS = 32

    /** True when [n] is a plausible size rather than an instruction to allocate a fortune. */
    fun isPlausibleCount(n: Int, max: Int): Boolean = n in 0..max
}
