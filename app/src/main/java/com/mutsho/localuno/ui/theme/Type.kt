package com.mutsho.localuno.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font as GoogleFontRef
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import com.mutsho.localuno.R

// Nocturne specifies "Inter" for both heading and body, at heading-weight 500.
// The game itself only ever needs the local network, so this stays opportunistic:
// Compose's downloadable-fonts loader fetches real Inter from Google Play Services
// when it can (live internet + Play Services present), caches it on-device after
// that, and otherwise silently keeps rendering with the platform's default
// sans-serif until/unless it resolves — it never blocks or crashes on a fetch
// failure, so this is safe to leave on even when the device is fully offline.
private val interProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)
private val interGoogleFont = GoogleFont("Inter")

val NocturneFontFamily = FontFamily(
    GoogleFontRef(googleFont = interGoogleFont, fontProvider = interProvider, weight = FontWeight.Normal),
    GoogleFontRef(googleFont = interGoogleFont, fontProvider = interProvider, weight = FontWeight.Medium),
    GoogleFontRef(googleFont = interGoogleFont, fontProvider = interProvider, weight = FontWeight.SemiBold),
    GoogleFontRef(googleFont = interGoogleFont, fontProvider = interProvider, weight = FontWeight.Bold)
)

val NocturneTypography = Typography(
    displayLarge = TextStyle(fontFamily = NocturneFontFamily, fontWeight = FontWeight.Medium, fontSize = 42.sp, letterSpacing = (-0.015).sp),
    headlineLarge = TextStyle(fontFamily = NocturneFontFamily, fontWeight = FontWeight.Medium, fontSize = 32.sp, letterSpacing = (-0.015).sp),
    headlineMedium = TextStyle(fontFamily = NocturneFontFamily, fontWeight = FontWeight.Medium, fontSize = 25.sp),
    headlineSmall = TextStyle(fontFamily = NocturneFontFamily, fontWeight = FontWeight.Medium, fontSize = 20.sp),
    titleLarge = TextStyle(fontFamily = NocturneFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    titleMedium = TextStyle(fontFamily = NocturneFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    titleSmall = TextStyle(fontFamily = NocturneFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
    bodyLarge = TextStyle(fontFamily = NocturneFontFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = NocturneFontFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = NocturneFontFamily, fontWeight = FontWeight.Normal, fontSize = 11.sp),
    labelLarge = TextStyle(fontFamily = NocturneFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = NocturneFontFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.02.sp),
    labelSmall = TextStyle(fontFamily = NocturneFontFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.08.sp)
)
