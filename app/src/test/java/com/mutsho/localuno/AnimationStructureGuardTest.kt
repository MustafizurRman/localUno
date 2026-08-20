package com.mutsho.localuno

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * An animation may decide how a thing LOOKS. It may never decide whether the thing EXISTS.
 *
 * Compose has three phases, and the rule that matters here is which one reads a value that changes
 * every frame. Reading it in layout (`offset {}`) or draw (`graphicsLayer {}`, a draw lambda) is
 * free. Reading it in COMPOSITION subscribes the composition itself to the frame clock, and if that
 * read then decides whether a child is emitted, the shape of the tree is being rewritten sixty
 * times a second underneath the composer.
 *
 * That is not a theoretical concern in this app. It crashed for weeks:
 *
 *     java.lang.IndexOutOfBoundsException: Index -1 out of bounds for length 0
 *       at androidx.compose.runtime.Stack.pop(Stack.kt:26)
 *       at androidx.compose.runtime.ComposerImpl.endRoot(Composer.kt:1452)
 *
 * No application frame anywhere on it - the report names the frame that tripped, not the one that
 * broke the ground, which is why six readings of the stack led nowhere. It was found by bisection
 * instead: freezing HandArc's per-frame loop made three soak rounds clean where the same driver had
 * killed the app in round two, twice running.
 *
 * The instructive part is the fix that did NOT work. Wrapping the read in `derivedStateOf` looks
 * like the textbook answer and still crashed, because `derivedStateOf` narrows how OFTEN the
 * composition is invalidated without changing the fact that it is a subscriber. The value has to
 * leave composition altogether.
 *
 * So the invariant is syntactic and cheap to check: no `if` in the UI tree may test a value read
 * off an animation. Overlays gate their emission on a plain `finished` boolean that their
 * `LaunchedEffect` writes once, and every per-frame read lives in a layout or draw lambda.
 *
 * Scope, stated plainly: this catches the direct form, `if (progress.value >= 1f)`. It does not
 * catch a read laundered through a local first - `val t = progress.value; if (t >= 1f)` - which is
 * how three of these were actually written before being converted. [DialCompositionGuardTest]
 * covers HandArc's own variable by name, and the soak driver covers what neither can.
 */
class AnimationStructureGuardTest {

    private val uiRoot = File("src/main/java/com/mutsho/localuno/ui")

    private fun uiSources(): List<File> =
        uiRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `the ui tree is present`() {
        // Guards the guard: a moved directory would make the scan below vacuously pass, which is a
        // failure mode this suite has actually shipped before.
        assertTrue("found no Kotlin sources under $uiRoot", uiSources().size > 15)
    }

    @Test
    fun `no branch is taken on an animating value`() {
        val offenders = uiSources().flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                if (violates(line)) "${file.name}:${i + 1}  ${line.trim()}" else null
            }
        }
        assertTrue(
            "an animation is deciding whether something is composed. Gate emission on a plain " +
                "boolean written once by the LaunchedEffect, and read the animation in " +
                "offset {} / graphicsLayer {} instead:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun `the scanner actually detects a violation`() {
        // Without this the suite could pass because the matcher is broken rather than because the
        // tree is clean - which is exactly what happened twice while building these scans.
        assertTrue(violates("    if (!(progress.value >= 1f)) {"))
        assertTrue(violates("    if (progress.value < 1f && participants.size >= 2) {"))
        assertTrue(violates("        if (anim.value > 0f) {"))

        // ...and stays quiet on the shapes that are correct.
        assertTrue(!violates("    if (!finished) {"))
        assertTrue(!violates("        .graphicsLayer { alpha = sceneAlphaAt(progress.value) }"))
        assertTrue(!violates("            val e = easedAt(progress.value)"))
        assertTrue(!violates("    var finished by remember { mutableStateOf(false) }"))
        assertTrue(!violates("// if (progress.value >= 1f) - what this used to be"))
    }

    // ── scanner ─────────────────────────────────────────────────────────────

    /** `if (` ... `.value` ... on one line, outside a comment. */
    private val branchOnValue = Regex("""\bif\s*\([^)]*\.value\b""")

    private fun violates(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.startsWith("//") || trimmed.startsWith("*")) return false
        return branchOnValue.containsMatchIn(line.substringBefore("//"))
    }
}
