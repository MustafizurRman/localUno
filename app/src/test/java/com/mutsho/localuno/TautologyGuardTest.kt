package com.mutsho.localuno

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No test may assert that something equals itself.
 *
 * Written after finding one in this very suite. `assertEquals(labels(s), labels(s))` sat in
 * RulesDigestProbeTest under the name "the order is stable", and it could not fail - it would have
 * passed against a digest that shuffled its output on every single call, which is precisely the
 * thing it claimed to rule out.
 *
 * That is the dangerous shape: not a test that is absent, but a test that is present, named after a
 * real property, green forever, and worth nothing. It makes the suite report coverage it does not
 * have, and it is invisible in a passing run by construction.
 *
 * Scope is deliberately narrow. This flags only assertions whose two sides are textually identical
 * - an unambiguous defect with no legitimate use. It does NOT flag tests without assertions: a
 * dozen of those delegate to asserting helpers like EdgeProbeTest's `fuzz`, which is good practice,
 * and flagging them would train people to ignore this.
 *
 * Note what it deliberately allows: `assertEquals(play(), play())` in EdgeProbeTest is textually
 * identical on both sides and is NOT a tautology, because `play()` builds a fresh seeded engine
 * each call - it asserts determinism. Calls are therefore exempt unless they take no arguments
 * AND the enclosing test does not name reproducibility. That exemption is narrow on purpose;
 * see the allowlist below.
 */
class TautologyGuardTest {

    /**
     * Assertions where identical sides are meaningful, because the expression has a side effect
     * or is intentionally invoked twice. Each entry is a file name plus the exact source line, so
     * adding one is a deliberate act rather than a wildcard.
     */
    private val allowed = setOf(
        // Same seed, same deal - the whole point is that two separate runs agree.
        "EdgeProbeTest.kt::assertEquals(play(), play())"
    )

    private fun testSources(): List<File> {
        val root = File("src/test/java/com/mutsho/localuno")
        assertTrue("test sources not found at ${root.absolutePath}", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /**
     * Removes everything that only LOOKS like code: string literals first, then line comments.
     *
     * Both are needed and the order matters. Comments quoting a bad line were the first false
     * positive; the second was this guard reporting its own proving samples, which are literal
     * tautologies deliberately embedded in raw strings a few lines below. A scanner that cannot
     * read its own test file is not one anybody will keep.
     *
     * Strings are stripped before comments because a URL inside a literal contains `//` and would
     * otherwise truncate the line.
     */
    internal fun stripNonCode(source: String): String {
        var s = source.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")              // block comments, KDoc
        s = s.replace(Regex("\"\"\"[\\s\\S]*?\"\"\""), "\"\"")               // raw/triple-quoted
        s = s.replace(Regex("\"(\\\\.|[^\"\\\\\\n])*\""), "\"\"")            // ordinary literals
        return s.lines().joinToString("\n") { it.substringBefore("//") }
    }

    /** Splits the balanced argument list starting at the '(' at [open]. */
    private fun args(s: String, open: Int): List<String> {
        var depth = 0
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var i = open
        while (i < s.length) {
            when (val c = s[i]) {
                '(' -> { depth++; if (depth > 1) cur.append(c) }
                ')' -> { depth--; if (depth == 0) { out.add(cur.toString()); return out }; cur.append(c) }
                ',' -> if (depth == 1) { out.add(cur.toString()); cur.clear() } else cur.append(c)
                else -> cur.append(c)
            }
            i++
        }
        return out
    }

    internal fun findTautologies(fileName: String, source: String): List<String> {
        val flat = stripNonCode(source)
        val call = Regex("""\bassert(Equals|NotEquals|Same|NotSame)\s*\(""")
        val out = mutableListOf<String>()
        for (m in call.findAll(flat)) {
            var parts = args(flat, m.range.last).map { it.trim() }
            // Drop a leading message literal - assertEquals("why", expected, actual).
            if (parts.size == 3 && parts[0].startsWith("\"")) parts = parts.drop(1)
            if (parts.size != 2) continue
            val (a, b) = parts
            if (a.isEmpty() || a != b) continue
            val text = "${m.value}$a, $b)"
            if ("$fileName::$text" in allowed) continue
            out += "$fileName  $text"
        }
        return out
    }

    @Test fun `no assertion compares a value to itself`() {
        val offenders = testSources().flatMap { findTautologies(it.name, it.readText()) }
        assertTrue(
            "These assertions cannot fail - both sides are the same expression. A test that " +
                "cannot fail reports coverage the suite does not have.\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test fun `the guard catches the tautology that was actually in this suite`() {
        // A guard nobody has seen fail is a guard nobody knows works - and this one exists because
        // the real thing was found here, under a name that described a property it never checked.
        val sample = """
            @Test fun `the order is stable`() {
                val s = settings()
                assertEquals(labels(s), labels(s))
            }
        """.trimIndent()
        assertEquals(1, findTautologies("Sample.kt", sample).size)
    }

    @Test fun `the guard ignores a KDoc block that quotes a bad line`() {
        // The third false positive, and the one that took two attempts: this class's own KDoc
        // explains the bug by quoting it, and `/** */` has no `//` for the line stripper to find.
        val sample = """
            /**
             * `assertEquals(labels(s), labels(s))` sat in RulesDigestProbeTest and could not fail.
             */
            fun ok() { assertEquals(expected, actual) }
        """.trimIndent()
        assertTrue(findTautologies("Sample.kt", sample).isEmpty())
    }

    @Test fun `the guard ignores a comment that quotes a bad line`() {
        // The first version of this scanner flagged the comment explaining the fix, in the very
        // file it had just been fixed in.
        val sample = """
            // Was `assertEquals(labels(s), labels(s))`, which cannot fail.
            assertEquals(expected, labels(s))
        """.trimIndent()
        assertTrue(findTautologies("Sample.kt", sample).isEmpty())
    }

    @Test fun `the guard leaves a real comparison alone`() {
        val sample = """
            assertEquals(listOf("Stacking"), labels(s))
            assertEquals("expected a skip", GamePhase.PLAYING, e.getState().phase)
        """.trimIndent()
        assertTrue(findTautologies("Sample.kt", sample).isEmpty())
    }

    @Test fun `a message argument does not hide a tautology`() {
        // assertEquals("why", x, x) must still be caught.
        val sample = """assertEquals("the order is stable", labels(s), labels(s))"""
        assertEquals(1, findTautologies("Sample.kt", sample).size)
    }

    @Test fun `the determinism check is allowed through by name`() {
        // Textually identical, genuinely meaningful: play() deals a fresh seeded game each call.
        val sample = """assertEquals(play(), play())"""
        assertTrue(findTautologies("EdgeProbeTest.kt", sample).isEmpty())
        // ...and only in the file it was vouched for.
        assertEquals(1, findTautologies("SomeOtherTest.kt", sample).size)
    }
}
