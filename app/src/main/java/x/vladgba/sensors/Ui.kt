package x.vladgba.sensors

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import java.util.Locale

/** A tool page hosted by [MainActivity]. Sensors are only active between start() and stop(). */
interface Screen {
    val view: View
    fun start()
    fun stop()
}

object Ui {
    /** Locale-independent formatting (dot decimals, copy-paste safe). */
    fun f(fmt: String, vararg args: Any?): String = String.format(Locale.US, fmt, *args)

    val BG = Color.rgb(0x10, 0x14, 0x18)
    val PANEL = Color.rgb(0x1A, 0x20, 0x27)
    val FG = Color.rgb(0xE8, 0xEE, 0xF2)
    val DIM = Color.rgb(0x8A, 0x96, 0xA3)
    val GRID = Color.rgb(0x2C, 0x35, 0x3F)
    val ACCENT = Color.rgb(0x4F, 0xC3, 0xF7)
    val RED = Color.rgb(0xEF, 0x53, 0x50)
    val GREEN = Color.rgb(0x66, 0xBB, 0x6A)
    val AMBER = Color.rgb(0xFF, 0xCA, 0x28)

    fun dp(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density
    fun sp(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.scaledDensity

    fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
    }

    fun text(color: Int, size: Float, bold: Boolean = false, align: Paint.Align = Paint.Align.CENTER) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            textAlign = align
            typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        }

    fun mono(color: Int, size: Float, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            textAlign = align
            typeface = Typeface.MONOSPACE
        }

    private val CARDINALS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    fun cardinal(deg: Float): String = CARDINALS[(((deg % 360f + 360f) % 360f + 22.5f) / 45f).toInt() % 8]

    /** Shortest signed difference b - a in degrees, in (-180, 180]. */
    fun angleDiff(a: Float, b: Float): Float {
        var d = (b - a) % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }
}
