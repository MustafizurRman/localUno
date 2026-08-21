package com.mutsho.localuno.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.mutsho.localuno.model.TableEvent
import com.mutsho.localuno.model.detectTableEvents

/**
 * The "it's your turn now" cue.
 *
 * Deliberately NOT Compose's LocalHapticFeedback: that only exposes LongPress and TextHandleMove,
 * and LongPress is already what playing or drawing a card fires. An alert that feels exactly like
 * the tap you just made carries no information - the whole point of this one is that you can tell
 * it apart without looking, because the situation it signals (the table is waiting on YOU) is the
 * one where you might not be looking at all.
 *
 * The pattern is two short pulses with a gap, which reads as a distinct "ba-dum" against the single
 * flat buzz of the action feedback. Amplitudes are set explicitly rather than left at DEFAULT_
 * AMPLITUDE so the shape survives on devices whose default is weak; on hardware without amplitude
 * control the platform falls back to on/off, which still preserves the two-pulse rhythm.
 */
private val TURN_PATTERN_TIMINGS = longArrayOf(0, 45, 90, 45)
private val TURN_PATTERN_AMPLITUDES = intArrayOf(0, 160, 0, 220)

/**
 * Returns a callback that fires the turn cue, or a no-op if this device has no vibrator (or the
 * service is unavailable). Safe to call on every recomposition - the Vibrator lookup is remembered.
 */
@Composable
fun rememberTurnAlert(): () -> Unit {
    val context = LocalContext.current
    val vibrator = remember(context) { resolveVibrator(context) }
    return remember(vibrator) {
        {
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(
                        TURN_PATTERN_TIMINGS,
                        TURN_PATTERN_AMPLITUDES,
                        -1 // no repeat
                    )
                )
            }
        }
    }
}

/**
 * The rest of the vocabulary: what the table did to you.
 *
 * Three shapes, chosen to be told apart through a pocket rather than to be pleasant:
 *
 *  - **Hit** - one firm pulse. Something landed on you (a draw stack, a hand you did not ask for).
 *    Shorter and harder than the turn cue's "ba-dum", because it reports a fact rather than asking
 *    for a decision.
 *  - **Alarm** - three rising pulses. You are out. It is the only pattern that escalates, and it is
 *    reserved for the only event that ends your involvement in the round.
 *
 * Nothing here fires for an action you took. See [detectTableEvents] for how that is guaranteed.
 */
private val HIT_TIMINGS = longArrayOf(0, 70)
private val HIT_AMPLITUDES = intArrayOf(0, 255)

private val ALARM_TIMINGS = longArrayOf(0, 55, 70, 55, 70, 90)
private val ALARM_AMPLITUDES = intArrayOf(0, 140, 0, 190, 0, 255)

/**
 * Returns a callback that plays the right pattern for a [TableEvent], or a no-op on a device with
 * no vibrator. Callers gate on the user's haptics preference; this does not check it, so that the
 * decision lives in one place rather than being re-made at every call site.
 */
@Composable
fun rememberTableHaptics(): (TableEvent) -> Unit {
    val context = LocalContext.current
    val vibrator = remember(context) { resolveVibrator(context) }
    return remember(vibrator) {
        { event ->
            if (vibrator != null && vibrator.hasVibrator()) {
                val (timings, amplitudes) = when (event) {
                    is TableEvent.StackLanded -> HIT_TIMINGS to HIT_AMPLITUDES
                    TableEvent.HandHit -> HIT_TIMINGS to HIT_AMPLITUDES
                    TableEvent.KnockedOut -> ALARM_TIMINGS to ALARM_AMPLITUDES
                }
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            }
        }
    }
}

private fun resolveVibrator(context: Context): Vibrator? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // getSystemService(Vibrator::class.java) still works on 31+ but is deprecated there;
        // VibratorManager is the supported path and correctly picks the default vibrator on
        // devices that expose more than one.
        (context.getSystemService(VibratorManager::class.java))?.defaultVibrator
    } else {
        context.getSystemService(Vibrator::class.java)
    }
} catch (_: Exception) {
    null
}
