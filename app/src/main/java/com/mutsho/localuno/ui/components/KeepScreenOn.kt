package com.mutsho.localuno.ui.components

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Holds the screen awake for as long as this composable is in the tree.
 *
 * A card game is mostly spent watching other people play, and a typical screen timeout is thirty
 * seconds - so the board went dark partway through every single round, for every player, and you
 * had to wake and often unlock the phone to see whose turn it was or to take your own. The turn
 * alert buzzes when it reaches you, which helped and also gave the game away: the app already
 * assumed you might not be looking at it.
 *
 * Scoped rather than set on the Activity, and that scoping is the point. `onDispose` clears the
 * flag when you leave the board, so the menu, the lobby and the end screen all time out normally.
 * A flag set once in onCreate would keep the phone awake in the app's menus for as long as it
 * happened to be open, which is the version of this fix that gets a bad review.
 */
@Composable
fun KeepScreenOn() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
