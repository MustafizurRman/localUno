package com.mutsho.localuno

import com.mutsho.localuno.model.PinGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Four digits is thirteen bits. The limiter is what makes that survivable.
 *
 * Ten thousand possibilities is nothing over a LAN socket, and the old code neither counted
 * attempts nor closed the connection after a bad one - so a client that simply declined to hang up
 * could sit there guessing. The PIN is short on purpose, because a person reads it aloud across a
 * room; the defence has to be in how many guesses are allowed, not in making the number longer than
 * anyone wants to type.
 */
class PinGateTest {

    private val T0 = 1_000_000L

    private fun gate() = PinGate(maxAttempts = 3, lockoutMillis = 60_000L)

    @Test fun `the right pin is accepted`() {
        assertTrue(gate().check("1.2.3.4", "4821", "4821", T0) is PinGate.Verdict.Ok)
    }

    @Test fun `a table with no pin accepts anyone`() {
        assertTrue(gate().check("1.2.3.4", null, null, T0) is PinGate.Verdict.Ok)
    }

    @Test fun `a wrong pin is refused`() {
        assertTrue(gate().check("1.2.3.4", "0000", "4821", T0) is PinGate.Verdict.Wrong)
    }

    @Test fun `a missing pin on a protected table is refused`() {
        assertTrue(gate().check("1.2.3.4", null, "4821", T0) is PinGate.Verdict.Wrong)
    }

    @Test fun `guesses run out`() {
        val g = gate()
        assertEquals(2, (g.check("a", "0000", "4821", T0) as PinGate.Verdict.Wrong).attemptsLeft)
        assertEquals(1, (g.check("a", "0001", "4821", T0) as PinGate.Verdict.Wrong).attemptsLeft)
        assertTrue(g.check("a", "0002", "4821", T0) is PinGate.Verdict.LockedOut)
    }

    @Test fun `a locked out source stays locked out even with the right pin`() {
        // Otherwise the lockout is no lockout at all - it just makes the attacker's last guess free.
        val g = gate()
        repeat(3) { g.check("a", "0000", "4821", T0) }
        assertTrue(g.check("a", "4821", "4821", T0 + 1_000) is PinGate.Verdict.LockedOut)
    }

    @Test fun `the lockout expires`() {
        val g = gate()
        repeat(3) { g.check("a", "0000", "4821", T0) }
        assertTrue(g.check("a", "4821", "4821", T0 + 60_001) is PinGate.Verdict.Ok)
    }

    @Test fun `serving the lockout restores the full allowance`() {
        // Not one guess and straight back to locked - somebody who mistyped and waited is a guest,
        // not an attacker.
        val g = gate()
        repeat(3) { g.check("a", "0000", "4821", T0) }
        assertEquals(
            2,
            (g.check("a", "9999", "4821", T0 + 60_001) as PinGate.Verdict.Wrong).attemptsLeft
        )
    }

    @Test fun `a success clears the record`() {
        val g = gate()
        g.check("a", "0000", "4821", T0)
        g.check("a", "0001", "4821", T0)
        assertTrue(g.check("a", "4821", "4821", T0) is PinGate.Verdict.Ok)
        // Back to a clean slate rather than one failure from a lockout.
        assertEquals(2, (g.check("a", "0000", "4821", T0) as PinGate.Verdict.Wrong).attemptsLeft)
    }

    @Test fun `attempts are counted per source`() {
        val g = gate()
        repeat(3) { g.check("attacker", "0000", "4821", T0) }
        // One person guessing badly must not lock the table for everybody else.
        assertTrue(g.check("guest", "4821", "4821", T0) is PinGate.Verdict.Ok)
    }

    @Test fun `brute forcing the whole keyspace gets nowhere`() {
        // The actual claim: 10,000 guesses used to take seconds. Now they take three.
        val g = gate()
        var accepted = 0
        for (guess in 1000..9999) {
            if (g.check("attacker", guess.toString(), "4821", T0) is PinGate.Verdict.Ok) accepted++
        }
        assertEquals("no guess may land once locked out", 0, accepted)
    }

    @Test fun `a wrong pin of a different length is refused rather than throwing`() {
        // The comparison is length-sensitive by construction; it must not index off the end.
        assertTrue(gate().check("a", "1", "4821", T0) is PinGate.Verdict.Wrong)
        assertTrue(gate().check("b", "482100000", "4821", T0) is PinGate.Verdict.Wrong)
    }

    @Test fun `reset clears every source`() {
        val g = gate()
        repeat(3) { g.check("a", "0000", "4821", T0) }
        g.reset()
        assertTrue(g.check("a", "4821", "4821", T0) is PinGate.Verdict.Ok)
    }
}
