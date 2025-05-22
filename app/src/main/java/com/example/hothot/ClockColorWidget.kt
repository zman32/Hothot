package com.example.hothot


import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import android.widget.RemoteViews
import androidx.palette.graphics.Palette
import kotlinx.coroutines.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

import java.util.Locale
import androidx.core.graphics.ColorUtils

class ClockColorWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            updateAppWidget(context, manager, id, null)
        }
    }

    companion object {
        fun updateWidgets(context: Context, albumArt: Bitmap?) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, ClockColorWidget::class.java))
            for (id in ids) {
                updateAppWidget(context, appWidgetManager, id, albumArt)
            }
        }

        fun updateAppWidget(context: Context, manager: AppWidgetManager, widgetId: Int, albumArt: Bitmap?) {
            val views = RemoteViews(context.packageName, R.layout.app_widget_clock)

            if (albumArt != null) {
                Palette.from(albumArt)
                    .clearFilters()
                    .maximumColorCount(24)
                    .generate { palette ->

                        // Log all available swatches
                        val swatchesToLog = listOf(
                            "Vibrant" to palette?.vibrantSwatch,
                            "Dark Vibrant" to palette?.darkVibrantSwatch,
                            "Light Vibrant" to palette?.lightVibrantSwatch,
                            "Muted" to palette?.mutedSwatch,
                            "Dark Muted" to palette?.darkMutedSwatch,
                            "Light Muted" to palette?.lightMutedSwatch,
                            "Dominant" to palette?.dominantSwatch
                        )
                        for ((name, swatch) in swatchesToLog) {
                            val hex = swatch?.rgb?.let { String.format("#%06X", 0xFFFFFF and it) } ?: "null"
                            val pop = swatch?.population ?: 0
                            Log.d("PaletteSwatch", "$name: $hex (pop: $pop)")
                        }

                        // Preferred swatch order
                        val preferredSwatches = listOf(
                            palette?.vibrantSwatch,
                            palette?.lightVibrantSwatch,
                            palette?.mutedSwatch,
                            palette?.dominantSwatch
                        )
                        val swatchNames = listOf("Vibrant", "Light Vibrant", "Muted", "Dominant")
                        val swatchIndex = preferredSwatches.indexOfFirst { it != null }
                        val swatch = preferredSwatches.getOrNull(swatchIndex)
                        val swatchName = swatchNames.getOrNull(swatchIndex) ?: "None"
                        Log.d("ClockColorWidget", "Selected swatch: $swatchName")

                        if (swatch != null) {
                            val baseColor = swatch.rgb
                            val contrastColor = findContrastColor(baseColor, Color.BLACK, true, 2.5)

                            val contrastRatio = contrastRatio(contrastColor, Color.BLACK)
                            Log.d("ClockColorWidget", "Final contrastColor: #${"%06X".format(0xFFFFFF and contrastColor)} (contrast: $contrastRatio)")

                            val bright = shiftColor(contrastColor, -0.05f)
                            val dark = shiftColor(contrastColor, 0.1f)

                            views.setTextColor(R.id.Hour, bright)
                            views.setTextColor(R.id.Sep, dark)
                            views.setTextColor(R.id.Minute, bright)
                            views.setTextColor(R.id.AmPm, dark)

                            views.setTextColor(R.id.day, dark)
                            views.setTextColor(R.id.date, bright)
                            views.setTextColor(R.id.month, dark)
                        } else {
                            Log.d("ClockColorWidget", "No usable swatch found. Using fallback colors.")
                            val white = Color.WHITE
                            views.setTextColor(R.id.Hour, white)
                            views.setTextColor(R.id.Sep, white)
                            views.setTextColor(R.id.Minute, white)
                            views.setTextColor(R.id.AmPm, white)
                            views.setTextColor(R.id.day, white)
                            views.setTextColor(R.id.date, white)
                            views.setTextColor(R.id.month, white)
                        }

                        manager.updateAppWidget(widgetId, views)
                        Log.d("ClockColorWidget", "Updated widget $widgetId with album art = ${albumArt != null}")
                    }

            } else {
                // Fallback colors if no album art
                val white = Color.WHITE
                views.setTextColor(R.id.Hour, white)
                views.setTextColor(R.id.Sep, white)
                views.setTextColor(R.id.Minute, white)
                views.setTextColor(R.id.AmPm, white)
                views.setTextColor(R.id.day, white)
                views.setTextColor(R.id.date, white)
                views.setTextColor(R.id.month, white)
                manager.updateAppWidget(widgetId, views)
            }
        }


        fun findContrastColor(fg: Int, bg: Int, isDark: Boolean, minContrast: Double): Int {
            var test = fg
            for (i in 0..10) {
                if (contrastRatio(test, bg) >= minContrast) break
                test = shiftColor(test, if (isDark) 0.05f else -0.05f)
            }
            return test
        }

        fun shiftColor(color: Int, factor: Float): Int {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(color, hsl)
            hsl[2] = (hsl[2] + factor).coerceIn(0f, 1f)
            return ColorUtils.HSLToColor(hsl)
        }

        fun contrastRatio(c1: Int, c2: Int): Double {
            fun luminance(color: Int): Double {
                val r = Color.red(color) / 255.0
                val g = Color.green(color) / 255.0
                val b = Color.blue(color) / 255.0
                fun adjust(c: Double) = if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
                return 0.2126 * adjust(r) + 0.7152 * adjust(g) + 0.0722 * adjust(b)
            }
            val l1 = luminance(c1) + 0.05
            val l2 = luminance(c2) + 0.05
            return max(l1, l2) / min(l1, l2)
        }
    }
}
