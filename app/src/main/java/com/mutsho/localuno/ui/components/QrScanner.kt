package com.mutsho.localuno.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.mutsho.localuno.network.JoinLink

/**
 * Returns a callback that opens the camera and reports back a scanned [JoinLink].
 *
 * Uses zxing-android-embedded's own capture activity rather than a hand-built CameraX preview: it
 * already handles the camera runtime permission, rotation, torch, and the focus/exposure tuning
 * that decides whether a code on a phone screen across a table actually acquires. Deliberately not
 * ML Kit - that pulls in Play Services, and a game whose entire premise is a LAN with no internet
 * should not stop working on a handset that has no Google services on it.
 *
 * [onResult] receives null when the scan was cancelled OR when the code was not one of ours, and
 * the two are distinguished by [onNotOurCode] so the caller can say "that's not a Local UNO code"
 * instead of silently doing nothing - a scanner that ignores a successful scan of the wrong QR is
 * indistinguishable from a broken camera.
 */
@Composable
fun rememberQrJoinScanner(
    onResult: (JoinLink) -> Unit,
    onNotOurCode: () -> Unit = {},
    onCancelled: () -> Unit = {}
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        when {
            contents == null -> onCancelled()
            else -> {
                val link = JoinLink.decode(contents)
                if (link != null) onResult(link) else onNotOurCode()
            }
        }
    }

    return remember(launcher) {
        {
            launcher.launch(
                ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("Point at the host's QR code")
                    setBeepEnabled(false)
                    // Portrait, always - every other screen in the app is, and a camera that swings
                    // round while you are lining up someone else's phone is disorienting at exactly
                    // the wrong moment. See PortraitCaptureActivity.
                    setCaptureActivity(PortraitCaptureActivity::class.java)
                    // Must stay FALSE, and it is what makes the line above work. `true` does not
                    // mean "portrait" - it means "lock to whatever orientation the device is in
                    // right now", which CaptureManager applies with its own
                    // setRequestedOrientation() call at launch, overriding the manifest and landing
                    // back in landscape for anyone holding the phone sideways.
                    setOrientationLocked(false)
                }
            )
        }
    }
}
