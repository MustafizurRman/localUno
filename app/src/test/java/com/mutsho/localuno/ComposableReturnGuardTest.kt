package com.mutsho.localuno

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No composable lambda may be left early.
 *
 * This is the invariant that, broken in exactly one line, killed the app intermittently for weeks:
 *
 *     Column(...) {
 *         if (!isChooser) {
 *             Text("SWAPPING HANDS")
 *             return@Column          // <- here
 *         }
 *         ...
 *     }
 *
 * `Column` is an INLINE composable, so that is a non-local return: it jumps straight out of the
 * enclosing function, past the end-group calls the Compose compiler generated for every group being
 * unwound. The composition is then left carrying more group ends than starts, and Compose does not
 * fail where it happened - the slot table simply no longer balances, and some LATER frame dies
 * popping an empty group stack:
 *
 *     java.lang.IndexOutOfBoundsException: Index -1 out of bounds for length 0
 *       at androidx.compose.runtime.Stack.pop(Stack.kt:26)
 *       at androidx.compose.runtime.ComposerImpl.exitGroup(Composer.kt:2333)
 *       at androidx.compose.runtime.ComposerImpl.endRoot(Composer.kt:1481)
 *
 * with not one line of application code on the trace. That absence is the whole problem: the report
 * names the frame that tripped, never the one that broke the ground. This one hid behind a branch
 * that needs somebody ELSE to play a 7 with seven-swaps on, so it presented as a random crash, and
 * it survived a Compose runtime bump, a dozen overlay bisects, and being declared ruled out twice.
 *
 * It reads as ordinary Kotlin, which is exactly why a person will write it again. So it is checked
 * mechanically instead of remembered.
 *
 * Scope: this looks for `return@X` where X is a composable layout or container whose content lambda
 * is inline. Returns from `forEach`, `let`, `LaunchedEffect`, `drawBehind`, `clickable` and friends
 * are ordinary Kotlin control flow with no composition groups involved, and are left alone.
 */
class ComposableReturnGuardTest {

    /**
     * Content-lambda names it is never safe to return out of. All of these are inline composables
     * (or take an inline composable content slot), so a labelled return crosses group boundaries.
     */
    private val forbidden = listOf(
        "Column", "Row", "Box", "BoxWithConstraints", "Surface", "Card",
        "Scaffold", "LazyColumn", "LazyRow", "FlowRow", "FlowColumn",
        "AnimatedVisibility", "AnimatedContent", "Crossfade", "key", "CompositionLocalProvider"
    )

    private fun uiSources(): List<File> {
        val root = File("src/main/java/com/mutsho/localuno/ui")
        assertTrue("UI sources not found at ${root.absolutePath}", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test fun `no composable lambda is returned out of`() {
        val offenders = mutableListOf<String>()
        uiSources().forEach { file ->
            file.readLines().forEachIndexed { i, line ->
                val code = line.substringBefore("//")
                forbidden.forEach { name ->
                    if (Regex("""return@$name\b""").containsMatchIn(code)) {
                        offenders += "${file.name}:${i + 1}  ${line.trim()}"
                    }
                }
            }
        }
        assertTrue(
            "A labelled return out of an inline composable unbalances Compose's group stack and " +
                "kills a later frame from inside the runtime, with no application code on the " +
                "trace. Use if/else instead.\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test fun `the guard would catch the line that actually crashed the app`() {
        // A guard nobody has seen fail is a guard nobody knows works. This is the exact text that
        // was in SwapPickerOverlay.
        val sample = listOf(
            "                Text(\"SWAPPING HANDS\")",
            "                return@Column",
            "            }"
        )
        val hits = sample.count { line ->
            forbidden.any { Regex("""return@$it\b""").containsMatchIn(line.substringBefore("//")) }
        }
        assertTrue("the guard must match the real offender", hits == 1)
    }

    @Test fun `the guard leaves ordinary kotlin returns alone`() {
        // These are all in the tree today and all fine - no composition group is involved.
        val innocent = listOf(
            "if (color == Color.Transparent) return@drawBehind",
            "if (opacityNow() <= 0.01f) return@clickable",
            "if (trigger == 0) return@LaunchedEffect",
            "val server = lobbyViewModel.getServer() ?: return@LaunchedEffect",
            "players.forEach { if (it.isBot) return@forEach }"
        )
        innocent.forEach { line ->
            assertTrue(
                "false positive on: $line",
                forbidden.none { Regex("""return@$it\b""").containsMatchIn(line) }
            )
        }
    }
}
