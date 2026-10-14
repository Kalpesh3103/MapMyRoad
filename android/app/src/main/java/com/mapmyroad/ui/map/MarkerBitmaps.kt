package com.mapmyroad.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import com.mapmyroad.detector.Severity
import com.mapmyroad.ui.theme.RoadQualityColors
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws hazard severity markers (UI_UX_GUIDELINES.md section 4.3): shape plus color, never color alone.
 * Minor is a circle, moderate a triangle, severe an octagon with a white `!`. Every shape has a white outline for
 * contrast against the base map. Used for the map style images and for the legend and hazard sheet.
 */
internal object MarkerBitmaps {
    /** Single marker size. At least 24 dp (UI_UX_GUIDELINES.md section 4.3); taps use a 48 dp target. */
    const val MARKER_SIZE_DP = 28f

    /** Cluster markers are larger so the count fits inside. */
    const val CLUSTER_SIZE_DP = 36f

    private const val OUTLINE_DP = 2f
    private const val OCTAGON_CORNERS = 8

    fun create(
        severity: Severity,
        density: Float,
        sizeDp: Float = MARKER_SIZE_DP,
        withGlyph: Boolean = true,
    ): Bitmap {
        val sizePx = (sizeDp * density).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val outlinePx = OUTLINE_DP * density
        val size = sizePx.toFloat()
        val path = shapePath(severity, size, inset = outlinePx)

        val fillPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = fillColor(severity)
            }
        val outlinePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = outlinePx
                strokeJoin = Paint.Join.ROUND
                color = android.graphics.Color.WHITE
            }
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, outlinePaint)
        if (withGlyph && severity == Severity.SEVERE) {
            drawExclamation(canvas, size)
        }
        return bitmap
    }

    fun fillColor(severity: Severity): Int =
        when (severity) {
            Severity.MINOR -> RoadQualityColors.SeverityMinor.toArgb()
            Severity.MODERATE -> RoadQualityColors.SeverityModerate.toArgb()
            Severity.SEVERE -> RoadQualityColors.SeveritySevere.toArgb()
        }

    private fun shapePath(
        severity: Severity,
        size: Float,
        inset: Float,
    ): Path {
        val center = size / 2f
        val radius = center - inset
        val path = Path()
        when (severity) {
            Severity.MINOR -> path.addCircle(center, center, radius, Path.Direction.CW)
            Severity.MODERATE -> {
                // Upward triangle filling the square, flat base at the bottom.
                path.moveTo(center, inset)
                path.lineTo(size - inset, size - inset)
                path.lineTo(inset, size - inset)
                path.close()
            }
            Severity.SEVERE -> {
                // Regular octagon with flat top and bottom edges.
                for (corner in 0 until OCTAGON_CORNERS) {
                    val angle = Math.PI / OCTAGON_CORNERS + corner * 2.0 * Math.PI / OCTAGON_CORNERS
                    val x = center + radius * cos(angle).toFloat()
                    val y = center + radius * sin(angle).toFloat()
                    if (corner == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }
        }
        return path
    }

    /** White `!` drawn as shapes, so it does not depend on a font. */
    private fun drawExclamation(
        canvas: Canvas,
        size: Float,
    ) {
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = android.graphics.Color.WHITE
            }
        val barHalfWidth = size * 0.065f
        val center = size / 2f
        val bar = RectF(center - barHalfWidth, size * 0.22f, center + barHalfWidth, size * 0.6f)
        canvas.drawRoundRect(bar, barHalfWidth, barHalfWidth, paint)
        canvas.drawCircle(center, size * 0.74f, barHalfWidth * 1.15f, paint)
    }
}
