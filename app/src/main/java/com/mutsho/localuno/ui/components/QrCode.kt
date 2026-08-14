package com.mutsho.localuno.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A QR code, drawn as vector squares rather than a scaled bitmap.
 *
 * Rendering the modules directly means the code is crisp at any size on any density - a QR resized
 * from a fixed-resolution bitmap picks up interpolation blur along every module edge, which is the
 * thing that makes a code slow to acquire (or refuse entirely) at an angle in poor light. It also
 * sidesteps allocating and recycling a Bitmap inside a composable.
 *
 * Painted on an explicit white plate with a quiet zone, not on the app's dark surface. A QR needs a
 * light background and four modules of clear margin to be found at all; a dark-themed code with the
 * colours inverted scans on some readers and not others, and cutting the margin to save space is
 * the single most common reason a code is unreadable.
 */
@Composable
fun QrCode(
    content: String,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp
) {
    val matrix: BitMatrix? = remember(content) {
        try {
            QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                // Encode at the module grid's natural size and scale by drawing; asking zxing for a
                // pixel size here would have it pad to whole modules and hand back a grid whose
                // dimensions no longer divide evenly into the space we draw into.
                0,
                0,
                mapOf(
                    // A join link is short, so the highest correction level costs almost no extra
                    // modules and buys tolerance for a fingerprinted screen or a low-light scan.
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
                    EncodeHintType.MARGIN to 1,
                    EncodeHintType.CHARACTER_SET to "UTF-8"
                )
            )
        } catch (_: Exception) {
            // Only thrown when the content cannot fit any QR version - a join link never will, but
            // a blank plate is a better outcome than taking the lobby screen down with it.
            null
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .padding(size * 0.05f)
    ) {
        if (matrix != null) {
            Canvas(modifier = Modifier.size(size * 0.9f)) {
                val cells = matrix.width
                val cell = this.size.width / cells
                for (y in 0 until matrix.height) {
                    for (x in 0 until cells) {
                        if (matrix.get(x, y)) {
                            drawRect(
                                color = Color.Black,
                                topLeft = Offset(x * cell, y * cell),
                                // Overdrawn by a hair so neighbouring modules meet cleanly instead
                                // of leaving hairline seams where the float grid doesn't align.
                                size = Size(cell + 0.6f, cell + 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }
}
