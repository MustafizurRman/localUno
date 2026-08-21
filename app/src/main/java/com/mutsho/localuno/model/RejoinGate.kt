package com.mutsho.localuno.model

/**
 * Whether somebody claiming a seat mid-match may have it.
 *
 * Pure, and extracted for the same reason [TableRemoval] was: the decision lived inside
 * GameViewModel, which is an AndroidViewModel and so unreachable from the JVM suite - and this
 * project cannot run instrumented tests at all, because the app never reaches Compose UI idle. A
 * security check nothing can test is a security check that quietly stops being made.
 *
 * That is not hypothetical here. This path never consulted the PIN. The lobby's handler bows out
 * entirely once the round starts, so the one route that admits somebody into a match in progress
 * was the one route with no access control on it, and nothing anywhere would have noticed.
 *
 * Order matters and is asserted by the tests. The PIN is checked first, before anything that would
 * otherwise reveal whether a given player id is real, is seated, or is currently disconnected -
 * a refusal that varies by seat state hands an unauthenticated caller a way to enumerate the table.
 */
object RejoinGate {

    sealed class Verdict {
        /** Resume the seat and bind the connection to it. */
        data object Accept : Verdict()

        /** Refuse. [reason] is shown to the joiner, so it says only what they are entitled to know. */
        data class Refuse(val reason: String) : Verdict()
    }

    /**
     * @param isHost only the host runs an engine; a guest has nothing to admit anyone to.
     * @param pinRequired whether this table has a PIN at all.
     * @param pinAccepted the verdict from [PinGate] - already rate-limited by the caller.
     * @param seatHeldByLiveConnection whether a healthy socket currently holds this player id. A
     *   genuine reconnect arrives on a dead one; anything else is somebody claiming a player who
     *   never left.
     * @param attemptResume asks the engine to resume the seat, returning whether it did. **Lazy on
     *   purpose**: it is the only argument with a side effect, and it must not run for a caller who
     *   has not proved they belong here. Passing it as a value would evaluate it before the PIN was
     *   ever looked at, which is most of what was wrong with this path to begin with.
     * @param wasInThisMatch whether this id is on the roster at all, used only to word the refusal.
     *   Also lazy, so an unauthenticated caller cannot learn it either.
     */
    fun decide(
        isHost: Boolean,
        pinRequired: Boolean,
        pinAccepted: Boolean,
        seatHeldByLiveConnection: Boolean,
        wasInThisMatch: () -> Boolean,
        attemptResume: () -> Boolean
    ): Verdict {
        if (!isHost) return Verdict.Refuse("Not the host")

        // First, and deliberately: everything below this line either leaks something about the
        // table or changes it.
        if (pinRequired && !pinAccepted) return Verdict.Refuse("Invalid PIN")

        if (seatHeldByLiveConnection) return Verdict.Refuse("That player is already connected")

        if (!attemptResume()) {
            // Two different people land here and they deserve different answers. Someone who was
            // never at this table is a stranger whose Join tap found a match already underway;
            // someone who IS on the roster tried to resume a seat that can no longer be resumed -
            // knocked out, or their grace window already expired.
            return Verdict.Refuse(
                if (wasInThisMatch()) "Can't rejoin — your seat is gone"
                else "Game already in progress"
            )
        }

        return Verdict.Accept
    }
}
