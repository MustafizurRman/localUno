package com.mutsho.localuno.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.random.Random

/**
 * Fine grain for the felt surfaces.
 *
 * The design's felt-*.svg gets its texture from an SVG filter:
 *     <feTurbulence baseFrequency=".9" numOctaves="3"/>  drawn at 5% opacity
 *
 * That cannot be imported. VectorDrawable is a path-and-paint format - <path>, <group>,
 * <clip-path>, gradients - with no filter pipeline whatsoever, while feTurbulence is a per-pixel
 * procedural noise function. No finite set of paths represents it. Browsers manage it only because
 * SVG filters run over a rasterized pixel buffer, which VectorDrawable never produces.
 *
 * So the texture is generated here instead: one small tile of noise, built once and repeated across
 * the surface by the shader. Deliberately NOT an AGSL RuntimeShader, which would be the faithful
 * equivalent - that needs API 33 and this app ships to API 29, so it would need this fallback
 * anyway. One path is simpler than two.
 *
 * A note on fidelity: this is white noise, not the source's 3-octave fractal noise. Fractal noise
 * is cloudier/blotchier; white noise reads as fine film grain. For a felt weave the finer grain is
 * arguably the better texture, and at 5% alpha the two are essentially indistinguishable anyway.
 */
@Composable
fun rememberFeltGrain(tilePx: Int = 96): ShaderBrush {
    val tile = remember(tilePx) {
        // Fixed seed: the grain must not reshuffle on every recomposition or process restart,
        // or the felt would visibly crawl.
        val rng = Random(0x5EED)
        val pixels = IntArray(tilePx * tilePx) {
            val v = rng.nextInt(256)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        Bitmap.createBitmap(tilePx, tilePx, Bitmap.Config.ARGB_8888)
            .apply { setPixels(pixels, 0, tilePx, 0, 0, tilePx, tilePx) }
            .asImageBitmap()
    }
    return remember(tile) {
        ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
    }
}

/** Opacity the design applies to its noise layer. */
const val FELT_GRAIN_ALPHA = 0.05f
