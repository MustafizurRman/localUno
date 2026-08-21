package com.mutsho.localuno.ui.components

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * The QR scanner, pinned to portrait.
 *
 * Exists only so the manifest has something of ours to declare `android:screenOrientation="portrait"`
 * on. The library's own CaptureActivity is declared in ITS manifest, which we cannot edit, and
 * ScanOptions has no "always portrait" switch - it only offers lock-to-current or don't-lock.
 *
 * Every other screen in this app is portrait (MainActivity is locked that way in the manifest), so
 * the scanner following the sensor made it the one place the whole UI could swing round mid-flow -
 * and it does so while the camera is up and the user is trying to line up someone else's phone.
 *
 * Pairs with ScanOptions.setOrientationLocked(false) in [rememberQrJoinScanner], which is load-
 * bearing and NOT the thing to "fix" if this ever regresses: with it true, CaptureManager calls
 * setRequestedOrientation() itself at launch with whatever orientation the device is currently in,
 * which overrides the manifest and puts the scanner right back into landscape whenever the phone
 * happens to be held that way. Leaving it false is what lets the manifest have the last word.
 */
class PortraitCaptureActivity : CaptureActivity()
