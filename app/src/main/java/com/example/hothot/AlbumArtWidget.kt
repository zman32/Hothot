package com.example.hothot

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.RemoteViews
import androidx.palette.graphics.Palette
import com.example.hothot.R
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.pow
import kotlin.math.max
import kotlin.math.min
import android.graphics.Color
import java.util.Locale
import androidx.core.graphics.ColorUtils

class AlbumArtWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId, null, null, null)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
    }

    companion object {
        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            albumArt: Bitmap?,
            songTitle: String?,
            artistName: String?
        ) {
            val views = RemoteViews(context.packageName, R.layout.app_widget_big)

            // Intent to launch MainActivity (bring to front or open if closed)
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER
                )
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.media_titles, pendingIntent)

            // Widget media controls intents
            val prevIntent = Intent(context, MediaService::class.java).apply { action = "com.example.hothot.WIDGET_PREV" }
            val playPauseIntent = Intent(context, MediaService::class.java).apply { action = "com.example.hothot.WIDGET_PLAY_PAUSE" }
            val nextIntent = Intent(context, MediaService::class.java).apply { action = "com.example.hothot.WIDGET_NEXT" }

            val prevPendingIntent = PendingIntent.getService(context, 100 + appWidgetId, prevIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val playPausePendingIntent = PendingIntent.getService(context, 200 + appWidgetId, playPauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val nextPendingIntent = PendingIntent.getService(context, 300 + appWidgetId, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            views.setOnClickPendingIntent(R.id.button_prev, prevPendingIntent)
            views.setOnClickPendingIntent(R.id.button_toggle_play_pause, playPausePendingIntent)
            views.setOnClickPendingIntent(R.id.button_next, nextPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)

            views.setTextViewText(R.id.title, songTitle ?: "Song Title")
            views.setTextViewText(R.id.text, artistName ?: "Artist Name")



            // Set album art or fallback
            if (albumArt != null) {
                views.setImageViewBitmap(R.id.image, albumArt)

                // Set media_titles background first
                // Crop the region for background palette
                var backgroundColor = Color.DKGRAY

                val regionHeight = albumArt.height / 2


                val palette = Palette.from(albumArt)
                   // .setRegion(0, albumArt.height - regionHeight, albumArt.width, albumArt.height)
                    .clearFilters()
                    .maximumColorCount(31)
                    .generate()

                val bgColor = getMostUsedColor(albumArt)
                val dominantSwatch = palette.mutedSwatch
                //backgroundColor = dominantSwatch?.rgb ?: Color.DKGRAY
                  //views.setInt(R.id.media_titles, "setBackgroundColor", backgroundColor)

                // Keep reference to HSL for filter use
                val backgroundHsl = FloatArray(3)
                ColorUtils.colorToHSL(backgroundColor, backgroundHsl)

                val swatches = palette.swatches
                val fallback = swatches.maxByOrNull { it.population }

                val vibrant = palette.vibrantSwatch
                val darkVibrant = palette.darkVibrantSwatch
                val lightVibrant = palette.lightVibrantSwatch
                val muted = palette.mutedSwatch
                val darkMuted = palette.darkMutedSwatch
                val lightMuted = palette.lightMutedSwatch


                fun toHex(swatch: Palette.Swatch?): String =
                    if (swatch != null) String.format("#%06X", 0xFFFFFF and swatch.rgb) else "null"

                Log.d("PaletteSwatches", "vibrant: ${toHex(vibrant)}")
                Log.d("PaletteSwatches", "darkVibrant: ${toHex(darkVibrant)}")
                Log.d("PaletteSwatches", "lightVibrant: ${toHex(lightVibrant)}")
                Log.d("PaletteSwatches", "muted: ${toHex(muted)}")
                Log.d("PaletteSwatches", "darkMuted: ${toHex(darkMuted)}")
                Log.d("PaletteSwatches", "lightMuted: ${toHex(lightMuted)}")


                // Generate palette for contrasting text
                Palette.Builder(albumArt)
                    .setRegion(0, 0, albumArt.width, albumArt.height)
                    .maximumColorCount(48) //was 48
                    .clearFilters()
                    .generate { palette ->
                        palette?.let {
                          //  val bgColor = getMostUsedColor(albumArt)
                            val (titleColor2, textArtist) = pickContrastingTextColor(it, bgColor)

                            val (rawTitleColor, textColor) = pickContrastingTextColor(it, bgColor)

                            val isBgLight = calculateLuminance(bgColor) > 0.5
                            val titleColor = if (isBgLight) {
                                adjustBrightness(rawTitleColor, -0.2f) // if is light darken song title
                            } else {
                                adjustBrightness(rawTitleColor, 0.12f) // darken? by 13% seems to lighten
                            }




                            views.setInt(R.id.media_titles, "setBackgroundColor", bgColor)
                            views.setTextColor(R.id.title, titleColor)
                            views.setTextColor(R.id.text, titleColor2)

                            appWidgetManager.updateAppWidget(appWidgetId, views)
                        }
                    }

            } else {
                // No album art fallback
                views.setImageViewResource(R.id.image, R.drawable.blank_album_art)
                views.setInt(R.id.media_titles, "setBackgroundColor", 0xFF000000.toInt()) // dark fallback
                views.setTextColor(R.id.title, 0xFFFFFFFF.toInt())
                views.setTextColor(R.id.text, 0xFFAAAAAA.toInt())
                views.setTextViewText(R.id.title, songTitle ?: "Song Title")
                views.setTextViewText(R.id.text, artistName ?: "Artist Name")
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }




        fun getMostUsedColor(bitmap: Bitmap): Int {
            val width = bitmap.width
            val height = bitmap.height
            val top = height / 2 // Use bottom half

            val palette = Palette.Builder(bitmap)
                .setRegion(0, top, width, height)
                .clearFilters()
                .maximumColorCount(24)
                .generate()

            val swatches = palette.swatches
            val fallback = swatches.maxByOrNull { it.population }

            val vibrant = palette.vibrantSwatch
            val darkVibrant = palette.darkVibrantSwatch
            val lightVibrant = palette.lightVibrantSwatch
            val muted = palette.mutedSwatch
            val darkMuted = palette.darkMutedSwatch
            val lightMuted = palette.lightMutedSwatch

            fun toHex(swatch: Palette.Swatch?): String =
                if (swatch != null) String.format("#%06X", 0xFFFFFF and swatch.rgb) else "null"

            Log.d("PaletteSwatches", "vibrant: ${toHex(vibrant)}")
            Log.d("PaletteSwatches", "darkVibrant: ${toHex(darkVibrant)}")
            Log.d("PaletteSwatches", "lightVibrant: ${toHex(lightVibrant)}")
            Log.d("PaletteSwatches", "muted: ${toHex(muted)}")
            Log.d("PaletteSwatches", "darkMuted: ${toHex(darkMuted)}")
            Log.d("PaletteSwatches", "lightMuted: ${toHex(lightMuted)}")

            // Threshold: swatch must have at least 50% the population of the dominant swatch
            val minPop = 0.32 * (fallback?.population ?: 1)
            val minPopMuted = 0.9 * (fallback?.population ?: 1)

            return when {
               // vibrant != null && vibrant.population >= minPop -> vibrant.rgb
                 muted != null && muted.population >= minPopMuted -> muted.rgb
                fallback != null -> fallback.rgb
                else -> 0xFFD4757B.toInt() // fallback to test color
            }
        }




        private fun isWhiteOrBlack(hsl: FloatArray?): Boolean {
            if (hsl == null) return false
            val lightness = hsl[2]
            return lightness >= 0.95f || lightness <= 0.05f
        }


        private fun adjustBrightness(color: Int, percent: Float): Int {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(color, hsl)
            hsl[2] = (hsl[2] * (1 + percent)).coerceIn(0f, 1f)
            return ColorUtils.HSLToColor(hsl)
        }


        private fun calculateLuminance(color: Int): Double {
            val r = Color.red(color) / 255.0
            val g = Color.green(color) / 255.0
            val b = Color.blue(color) / 255.0

            val rLin = if (r <= 0.03928) r / 12.92 else Math.pow(((r + 0.055) / 1.055), 2.4)
            val gLin = if (g <= 0.03928) g / 12.92 else Math.pow(((g + 0.055) / 1.055), 2.4)
            val bLin = if (b <= 0.03928) b / 12.92 else Math.pow(((b + 0.055) / 1.055), 2.4)

            return 0.2126 * rLin + 0.7152 * gLin + 0.0722 * bLin
        }

        private fun getContrastRatio(color1: Int, color2: Int): Double {
            val l1 = calculateLuminance(color1)
            val l2 = calculateLuminance(color2)
            return if (l1 > l2) (l1 + 0.05) / (l2 + 0.05) else (l2 + 0.05) / (l1 + 0.05)
        }


        private fun pickContrastingTextColor(
            palette: Palette,
            bgColor: Int,
            minContrast: Double = 3.5 //ai did 3.5
        ): Pair<Int, Int> {
            val swatchLabels = mapOf(
                palette.vibrantSwatch to "vibrant",
                palette.lightVibrantSwatch to "lightVibrant",
                palette.darkVibrantSwatch to "darkVibrant",
                palette.mutedSwatch to "muted",
                palette.lightMutedSwatch to "lightMuted",
                palette.darkMutedSwatch to "darkMuted"
            )

            val swatchesInOrder = listOfNotNull(
                palette.vibrantSwatch,
                palette.lightVibrantSwatch,
                palette.darkVibrantSwatch,
                palette.mutedSwatch,
                palette.lightMutedSwatch,
                palette.darkMutedSwatch
            )

            for (swatch in swatchesInOrder) {
                val contrast = getContrastRatio(bgColor, swatch.rgb)
                if (contrast >= minContrast) {
                    Log.d("TextColorPicker", "Using ${swatchLabels[swatch]} swatch with contrast $contrast")
                    return swatch.rgb to swatch.bodyTextColor
                }
            }

            val fallbackText = if (calculateLuminance(bgColor) < 0.5) Color.WHITE else Color.BLACK
            Log.d("TextColorPicker", "No swatch passed contrast. Falling back to ${if (fallbackText == Color.WHITE) "WHITE" else "BLACK"}")
            return fallbackText to fallbackText
        }



        fun contrastRatio(c1: Int, c2: Int): Double {
            fun luminance(color: Int): Double {
                val r = (color shr 16 and 0xFF) / 255.0
                val g = (color shr 8 and 0xFF) / 255.0
                val b = (color and 0xFF) / 255.0

                fun adjust(channel: Double): Double =
                    if (channel <= 0.03928) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)

                return 0.2126 * adjust(r) + 0.7152 * adjust(g) + 0.0722 * adjust(b)
            }

            val lum1 = luminance(c1)
            val lum2 = luminance(c2)

            return (max(lum1, lum2) + 0.05) / (min(lum1, lum2) + 0.05)
        }



        fun updateWidgets(context: Context, albumArt: Bitmap?, songTitle: String?, artistName: String?) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(ComponentName(context, AlbumArtWidget::class.java))
            for (appWidgetId in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, appWidgetId, albumArt, songTitle, artistName)
            }
        }
    }
}