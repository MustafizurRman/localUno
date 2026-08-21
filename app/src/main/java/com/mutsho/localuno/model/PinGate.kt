package com.mutsho.localuno.model

/**
 * Rate-limits PIN attempts against a hosted table.
 *
 * A four-digit PIN is about thirteen bits. That is a perfectly reasonable thing to read aloud to
 * someone across a room, and a perfectly unreasonable thing to leave unguarded on a socket: ten
 * thousand guesses over a LAN takes seconds. The PIN was not weak by mistake - it is short so a
 * person can type it - so the defence belongs here, in how many times it may be tried, rather than
 * in making the number longer than a human wants to deal with.
 *
 * Keyed by remote address rather than by connection, because a connection is free to make: the old
 * behaviour did not even close the socket on a wrong PIN, and closing it alone would only mean an
 * attacker reconnects between guesses.
 *
 * Deliberately forgiving of honest mistakes. [maxAttempts] failures buys a [lockoutMillis] pause,
 * not a permanent ban - somebody who mistypes twice and then reads the host's screen properly
 * should not be locked out of the evening. A success clears the record entirely.
 *
 * Pure Kotlin and time-injected so the whole policy is testable without waiting for a clock.
 */
class PinGate(
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val lockoutMillis: Long = DEFAULT_LOCKOUT_MILLIS
) {

    companion object {
        /** Enough for a genuine fumble, nowhere near enough to search 10,000 possibilities. */
        const val DEFAULT_MAX_ATTEMPTS = 5

        /** Long enough that brute force is hopeless, short enough that a real guest just waits. */
        const val DEFAULT_LOCKOUT_MILLIS = 60_000L
    }

    sealed class Verdict {
        /** The PIN matched, or the table has none. */
        data object Ok : Verdict()

        /** Wrong PIN. [attemptsLeft] is what remains before a lockout. */
        data class Wrong(val attemptsLeft: Int) : Verdict()

        /** Too many failures from this source. [retryAfterMillis] until it clears. */
        data class LockedOut(val retryAfterMillis: Long) : Verdict()
    }

    private data class Record(val failures: Int, val lastFailureAt: Long)

    private val records = mutableMapOf<String, Record>()

    /**
     * @param key how to group attempts - the remote address, not the claimed player id, which an
     *   attacker chooses freely and could vary to reset the count.
     * @param supplied what the joiner sent, or null if they sent none.
     * @param expected the table's PIN, or null if the table is open.
     */
    fun check(key: String, supplied: String?, expected: String?, nowMillis: Long): Verdict {
        if (expected == null) return Verdict.Ok

        val record = records[key]
        if (record != null && record.failures >= maxAttempts) {
            val elapsed = nowMillis - record.lastFailureAt
            if (elapsed < lockoutMillis) return Verdict.LockedOut(lockoutMillis - elapsed)
            // Lockout served - start them fresh rather than leaving them one failure from another.
            records.remove(key)
        }

        if (supplied != null && constantTimeEquals(supplied, expected)) {
            records.remove(key)
            return Verdict.Ok
        }

        val failures = (records[key]?.failures ?: 0) + 1
        records[key] = Record(failures, nowMillis)
        return if (failures >= maxAttempts) Verdict.LockedOut(lockoutMillis)
        else Verdict.Wrong(maxAttempts - failures)
    }

    /** Drops everything - a new table is a clean slate for everyone. */
    fun reset() = records.clear()

    /**
     * Length-independent comparison.
     *
     * Not because a timing attack across Wi-Fi against a four-digit PIN is realistic - it is not -
     * but because `==` on a String is the kind of thing that gets copied into somewhere it does
     * matter, and this costs four character comparisons.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
