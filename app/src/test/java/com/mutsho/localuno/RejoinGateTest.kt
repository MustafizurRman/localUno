package com.mutsho.localuno

import com.mutsho.localuno.model.RejoinGate
import com.mutsho.localuno.model.RejoinGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one route that admits somebody into a match already in progress.
 *
 * It had no access control on it at all. The lobby stops answering JoinRequests the moment the
 * round starts, so mid-match rejoins were handled somewhere else entirely - and that somewhere
 * validated only that the engine was willing to resume the seat. The PIN, which is the table's
 * only means of saying who may be here, was never consulted.
 *
 * Most of these tests are about ORDER rather than outcome, which is the part that would rot
 * silently. Getting the same refusals in a different sequence still refuses the right people, and
 * still hands an unauthenticated caller a way to learn which player ids are real, which are seated,
 * and which are currently disconnected - by reading how the refusal is worded.
 */
class RejoinGateTest {

    private fun decide(
        isHost: Boolean = true,
        pinRequired: Boolean = true,
        pinAccepted: Boolean = true,
        seatLive: Boolean = false,
        inMatch: () -> Boolean = { true },
        resume: () -> Boolean = { true }
    ) = RejoinGate.decide(isHost, pinRequired, pinAccepted, seatLive, inMatch, resume)

    // ── Outcomes ────────────────────────────────────────────────────────────

    @Test fun `a dropped player with the right pin gets their seat back`() {
        assertEquals(Verdict.Accept, decide())
    }

    @Test fun `a table with no pin still admits a genuine reconnect`() {
        assertEquals(Verdict.Accept, decide(pinRequired = false, pinAccepted = false))
    }

    @Test fun `a wrong pin is refused`() {
        assertTrue(decide(pinAccepted = false) is Verdict.Refuse)
    }

    @Test fun `a guest cannot admit anyone`() {
        assertTrue(decide(isHost = false) is Verdict.Refuse)
    }

    @Test fun `a seat somebody is still sitting in is refused`() {
        assertTrue(decide(seatLive = true) is Verdict.Refuse)
    }

    @Test fun `a seat that cannot be resumed is refused`() {
        assertTrue(decide(resume = { false }) is Verdict.Refuse)
    }

    @Test fun `a stranger and a lapsed player are told different things`() {
        // Telling a first-time joiner they "can't rejoin" describes something they never did.
        val stranger = decide(resume = { false }, inMatch = { false }) as Verdict.Refuse
        val lapsed = decide(resume = { false }, inMatch = { true }) as Verdict.Refuse
        assertTrue(stranger.reason != lapsed.reason)
    }

    // ── Order, which is the actual security property ────────────────────────

    @Test fun `the engine is never touched by a caller with the wrong pin`() {
        // The whole point of the lazy attemptResume. reconnectPlayer mutates the table; running it
        // for an unauthenticated caller is the shape of the original bug.
        var resumeCalled = false
        decide(pinAccepted = false, resume = { resumeCalled = true; true })
        assertFalse("a failed PIN must not reach the engine", resumeCalled)
    }

    @Test fun `a caller with the wrong pin learns nothing about the roster`() {
        // wasInThisMatch is how the refusal is worded, so evaluating it for an unauthenticated
        // caller would let them probe which player ids are real.
        var rosterRead = false
        decide(pinAccepted = false, inMatch = { rosterRead = true; true })
        assertFalse("a failed PIN must not read the roster", rosterRead)
    }

    @Test fun `a wrong pin is refused even when everything else would have allowed it`() {
        assertEquals(
            "Invalid PIN",
            (decide(pinAccepted = false, seatLive = false, resume = { true }) as Verdict.Refuse).reason
        )
    }

    @Test fun `a wrong pin is reported as a pin problem, not a seat problem`() {
        // If a bad PIN on an occupied seat reported "already connected", the wording itself would
        // confirm that the id is real and in use.
        val r = decide(pinAccepted = false, seatLive = true) as Verdict.Refuse
        assertEquals("Invalid PIN", r.reason)
    }

    @Test fun `a live seat is refused before the engine is consulted`() {
        var resumeCalled = false
        decide(seatLive = true, resume = { resumeCalled = true; true })
        assertFalse("no need to touch the engine for a seat nobody left", resumeCalled)
    }

    @Test fun `a non-host is refused before anything else runs`() {
        var touched = false
        decide(isHost = false, inMatch = { touched = true; true }, resume = { touched = true; true })
        assertFalse(touched)
    }

    @Test fun `an accepted rejoin resumes the seat exactly once`() {
        // Called twice, the engine would advance the sequence number a second time and broadcast a
        // state nobody asked for.
        var calls = 0
        assertEquals(Verdict.Accept, decide(resume = { calls++; true }))
        assertEquals(1, calls)
    }

    @Test fun `a refusal never carries the pin or the player id`() {
        // The reason string is shown to whoever asked, including someone who should not be here.
        val reasons = listOf(
            decide(pinAccepted = false),
            decide(seatLive = true),
            decide(resume = { false }, inMatch = { false }),
            decide(resume = { false }, inMatch = { true }),
            decide(isHost = false)
        ).filterIsInstance<Verdict.Refuse>().map { it.reason }
        assertEquals(5, reasons.size)
        reasons.forEach {
            assertFalse("a refusal must not echo secrets: $it", it.contains("4821"))
            assertTrue("a refusal must say something: $it", it.isNotBlank())
        }
    }
}
