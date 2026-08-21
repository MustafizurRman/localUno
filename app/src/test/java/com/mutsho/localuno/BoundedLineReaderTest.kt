package com.mutsho.localuno

import com.mutsho.localuno.network.LineTooLongException
import com.mutsho.localuno.network.readBoundedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/**
 * A peer must not get to decide how much memory this process allocates.
 *
 * Both ends framed messages with a bare readLine(), which has no ceiling: bytes with no newline
 * among them grow the reader's buffer until the process dies. No PIN and no successful join
 * required - just a TCP connection to an advertised port - and it works in both directions, so a
 * malicious host could do it to every guest just as easily.
 *
 * The rest of these tests are about not breaking the ordinary cases while fixing that, which is the
 * part that would actually get noticed: a framing reader that mangles a legitimate message breaks
 * the whole game rather than one attack.
 */
class BoundedLineReaderTest {

    @Test fun `an over-long line is refused rather than buffered`() {
        val hostile = StringReader("x".repeat(5_000))
        try {
            hostile.readBoundedLine(limit = 1_000)
            throw AssertionError("expected the read to be refused")
        } catch (e: LineTooLongException) {
            assertEquals(1_000, e.limit)
        }
    }

    @Test fun `the refusal happens at the limit, not after consuming everything`() {
        // The point is to stop allocating, so a reader that swallowed all 10 MB and then complained
        // would pass a naive version of the test above while fixing nothing.
        val hostile = StringReader("x".repeat(10_000_000))
        try {
            hostile.readBoundedLine(limit = 4_096)
            throw AssertionError("expected the read to be refused")
        } catch (e: LineTooLongException) {
            // Anything still readable proves we stopped early rather than draining the source.
            assertTrue("should not have consumed the whole stream", hostile.read() != -1)
        }
    }

    @Test fun `a normal line is returned without its terminator`() {
        assertEquals("""{"type":"PING"}""", StringReader("{\"type\":\"PING\"}\n").readBoundedLine())
    }

    @Test fun `consecutive lines are read one at a time`() {
        val r = StringReader("first\nsecond\nthird\n")
        assertEquals("first", r.readBoundedLine())
        assertEquals("second", r.readBoundedLine())
        assertEquals("third", r.readBoundedLine())
    }

    @Test fun `end of stream returns null`() {
        assertNull(StringReader("").readBoundedLine())
    }

    @Test fun `a final line with no newline is still returned`() {
        // A peer that closes cleanly right after its last message must not have it discarded.
        assertEquals("tail", StringReader("tail").readBoundedLine())
    }

    @Test fun `carriage returns are not passed through to the parser`() {
        // A CRLF peer is well-behaved; a stray \r would ride into Gson as trailing junk.
        assertEquals("""{"a":1}""", StringReader("{\"a\":1}\r\n").readBoundedLine())
    }

    @Test fun `a line exactly at the limit is allowed`() {
        // Off-by-one here would reject the largest legitimate message rather than an attack.
        val line = "y".repeat(1_000)
        assertEquals(line, StringReader(line + "\n").readBoundedLine(limit = 1_000))
    }

    @Test fun `an empty line is returned rather than treated as end of stream`() {
        // Blank lines are dropped by the deserializer, not by the framer - conflating the two
        // would end the connection on a stray newline.
        assertEquals("", StringReader("\nnext\n").readBoundedLine())
    }

    @Test fun `the default limit leaves room for a real message`() {
        // The largest thing on this wire is a StateUpdate carrying one hand. If the cap were ever
        // tightened below that, multiplayer would break for the biggest hands only - which is to
        // say, rarely and confusingly.
        val bigStateUpdate = "z".repeat(64 * 1024)
        assertEquals(bigStateUpdate, StringReader(bigStateUpdate + "\n").readBoundedLine())
    }
}
