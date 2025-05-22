// PaletteUtil.kt
package com.example.hothot // Replace with your actual package name

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import kotlin.math.max
import kotlin.math.min

//creates the in app ui colors
object PaletteUtil {
    data class AlbumArtColors(
        val lightVibrant: Color? = null,
        val vibrant: Color? = null,
        val darkVibrant: Color? = null,
        val muted: Color? = null,
        val mutedVibrant: Color? = null
    )

    fun extractColors(bitmap: Bitmap?): AlbumArtColors {
        if (bitmap == null) return AlbumArtColors()

        val palette = Palette.from(bitmap)
            .maximumColorCount(31) //flavors of colors
            .generate()

        val vibrantColor = palette.vibrantSwatch?.let { Color(it.rgb) }
        val mutedVibrantColor = vibrantColor?.let { createMutedVibrant(it) }

        return AlbumArtColors(
            lightVibrant = palette.lightVibrantSwatch?.let { Color(it.rgb) },
            vibrant = vibrantColor,
            darkVibrant = palette.darkVibrantSwatch?.let { Color(it.rgb) },
            muted = palette.mutedSwatch?.let { Color(it.rgb) },
            mutedVibrant = mutedVibrantColor
        )
    }

    private fun createMutedVibrant(vibrant: Color): Color {
        // Convert Compose Color to RGB
        val red = vibrant.red
        val green = vibrant.green
        val blue = vibrant.blue

        // Convert RGB to HSL
        val hsl = rgbToHsl(red, green, blue)

        // Adjust HSL: reduce saturation and lightness
        // Option 1: 30% reduction (multiply by 0.7f)
        // val saturation = hsl[1] * 0.7f // Reduce saturation by 30%
        // val lightness = hsl[2] * 0.7f // Reduce lightness by 30%

        // Option 2: 50% reduction (multiply by 0.5f)
        val saturation = hsl[1] * 0.5f // Reduce saturation by 50%
        val lightness = hsl[2] * 0.5f // Reduce lightness by 50%

        // Convert adjusted HSL back to RGB
        val rgb = hslToRgb(hsl[0], saturation, lightness) // Fixed: use hsl[0] instead of hue

        // Return new Color with clamped values
        return Color(
            red = rgb[0].coerceIn(0f, 1f),
            green = rgb[1].coerceIn(0f, 1f),
            blue = rgb[2].coerceIn(0f, 1f)
        )
    }

    private fun rgbToHsl(r: Float, g: Float, b: Float): FloatArray {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val sum = max + min
        val lightness = sum / 2f

        if (max == min) {
            return floatArrayOf(0f, 0f, lightness) // Achromatic
        }

        val delta = max - min
        val saturation = if (lightness > 0.5f) {
            delta / (2f - sum)
        } else {
            delta / sum
        }

        val hue = when (max) {
            r -> (g - b) / delta % 6f
            g -> (b - r) / delta + 2f
            b -> (r - g) / delta + 4f
            else -> 0f
        } * 60f

        return floatArrayOf(
            if (hue < 0f) hue + 360f else hue,
            saturation.coerceIn(0f, 1f),
            lightness.coerceIn(0f, 1f)
        )
    }

    private fun hslToRgb(h: Float, s: Float, l: Float): FloatArray {
        if (s == 0f) {
            return floatArrayOf(l, l, l) // Achromatic
        }

        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        val hNormalized = h / 360f

        val r = hueToRgb(p, q, hNormalized + 1f / 3f)
        val g = hueToRgb(p, q, hNormalized)
        val b = hueToRgb(p, q, hNormalized - 1f / 3f)

        return floatArrayOf(r, g, b)
    }

    private fun hueToRgb(p: Float, q: Float, t: Float): Float {
        var tAdjusted = t
        if (tAdjusted < 0f) tAdjusted += 1f
        if (tAdjusted > 1f) tAdjusted -= 1f
        return when {
            tAdjusted < 1f / 6f -> p + (q - p) * 6f * tAdjusted
            tAdjusted < 0.5f -> q
            tAdjusted < 2f / 3f -> p + (q - p) * (2f / 3f - tAdjusted) * 6f
            else -> p
        }.coerceIn(0f, 1f)
    }
}