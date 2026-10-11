package x.vladgba.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.View
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Bubble level. Flat on a surface → round bubble (X/Y tilt).
 * Standing on an edge → tube level for that edge.
 * Tap = calibrate to current position, long press = reset calibration.
 */
@SuppressLint("ViewConstructor")
class LevelView(ctx: Context) : View(ctx), SensorEventListener, Screen {

    override val view: View get() = this

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val g = FloatArray(3)
    private var hasData = false

    private val prefs = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
    private var offX = prefs.getFloat("lvl_x", 0f)
    private var offY = prefs.getFloat("lvl_y", 0f)
    private var offEdge = prefs.getFloat("lvl_edge", 0f)

    // computed each frame
    private var flat = true
    private var angX = 0f
    private var angY = 0f
    private var edgeAngle = 0f
    private var tubeVertical = false

    private val ring = Ui.stroke(Ui.GRID, Ui.dp(ctx, 1.5f))
    private val ringOuter = Ui.stroke(Ui.DIM, Ui.dp(ctx, 2.5f))
    private val cross = Ui.stroke(Ui.GRID, Ui.dp(ctx, 1f))
    private val bubble = Ui.fill(Ui.ACCENT)
    private val bubbleEdge = Ui.stroke(Ui.FG, Ui.dp(ctx, 1.5f))
    private val tubeFill = Ui.fill(Ui.PANEL)
    private val horizon = Ui.stroke(Ui.AMBER, Ui.dp(ctx, 1.5f))
    private val bigText = Ui.text(Ui.FG, Ui.sp(ctx, 56f), bold = true)
    private val midText = Ui.text(Ui.FG, Ui.sp(ctx, 18f))
    private val hintText = Ui.text(Ui.DIM, Ui.sp(ctx, 14f))
    private val rect = RectF()

    init {
        setBackgroundColor(Ui.BG)
        setOnClickListener { calibrate() }
        setOnLongClickListener { resetCalibration(); true }
    }

    override fun start() {
        hasData = false
        sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun stop() = sm.unregisterListener(this)

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        val a = if (!hasData) 1f else if (e.sensor.type == Sensor.TYPE_GRAVITY) 0.3f else 0.1f
        for (i in 0..2) g[i] += a * (e.values[i] - g[i])
        hasData = true
        compute()
        invalidate()
    }

    private fun deg(r: Float) = Math.toDegrees(r.toDouble()).toFloat()

    private fun compute() {
        val n = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
        if (n < 0.1f) return
        flat = abs(g[2]) / n > 0.7f
        if (flat) {
            val z = abs(g[2])
            angX = deg(atan2(g[0], z)) - offX
            angY = deg(atan2(g[1], z)) - offY
        } else {
            // "up" in screen coordinates (screen y grows downward)
            val ux = g[0]
            val uy = -g[1]
            tubeVertical = abs(uy) < abs(ux)    // device lies on a long edge
            val s = if (tubeVertical) uy / hypot(ux, uy) else ux / hypot(ux, uy)
            edgeAngle = deg(asin(s.coerceIn(-1f, 1f))) - offEdge
        }
    }

    private fun calibrate() {
        if (!hasData) return
        if (flat) {
            offX += angX; offY += angY
            prefs.edit().putFloat("lvl_x", offX).putFloat("lvl_y", offY).apply()
        } else {
            offEdge += edgeAngle
            prefs.edit().putFloat("lvl_edge", offEdge).apply()
        }
        Toast.makeText(context, "Calibrated", Toast.LENGTH_SHORT).show()
        compute(); invalidate()
    }

    private fun resetCalibration() {
        offX = 0f; offY = 0f; offEdge = 0f
        prefs.edit().remove("lvl_x").remove("lvl_y").remove("lvl_edge").apply()
        Toast.makeText(context, "Calibration reset", Toast.LENGTH_SHORT).show()
        compute(); invalidate()
    }

    private fun fmt(v: Float) = Ui.f("%.1f°", v)
    private fun fmtS(v: Float) = Ui.f("%+.1f°", v)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        if (sensor == null) {
            canvas.drawText("No accelerometer on this device", cx, h / 2f, midText)
            return
        }
        val top = Ui.dp(context, 24f)
        canvas.drawText("Tap to calibrate · long press to reset", cx, h - Ui.dp(context, 18f), hintText)
        if (flat) drawFlat(canvas, w, h, cx, top) else drawEdge(canvas, w, h, cx, top)
    }

    private fun levelColor(a: Float) = when {
        a < 0.3f -> Ui.GREEN
        a < 2f -> Ui.AMBER
        else -> Ui.ACCENT
    }

    private fun drawFlat(c: Canvas, w: Float, h: Float, cx: Float, top: Float) {
        val tilt = hypot(angX, angY)
        bigText.color = levelColor(tilt)
        c.drawText(fmt(tilt), cx, top + bigText.textSize, bigText)
        c.drawText("X ${fmtS(angX)}     Y ${fmtS(angY)}", cx, top + bigText.textSize + midText.textSize * 1.6f, midText)

        val areaTop = top + bigText.textSize + midText.textSize * 2.5f
        val areaBot = h - Ui.dp(context, 48f)
        val cy = (areaTop + areaBot) / 2f
        val r = min(w * 0.42f, (areaBot - areaTop) * 0.46f)
        val full = 10f // degrees at the outer ring

        c.drawCircle(cx, cy, r, ringOuter)
        for (d in floatArrayOf(1f, 2f, 5f)) c.drawCircle(cx, cy, r * d / full, ring)
        c.drawLine(cx - r, cy, cx + r, cy, cross)
        c.drawLine(cx, cy - r, cx, cy + r, cross)

        val br = r * 0.12f
        var bx = angX / full * r
        var by = -angY / full * r
        val d = hypot(bx, by)
        val maxD = r - br
        if (d > maxD) { bx *= maxD / d; by *= maxD / d }
        bubble.color = levelColor(tilt)
        c.drawCircle(cx + bx, cy + by, br, bubble)
        c.drawCircle(cx + bx, cy + by, br, bubbleEdge)
    }

    private fun drawEdge(c: Canvas, w: Float, h: Float, cx: Float, top: Float) {
        val a = abs(edgeAngle)
        bigText.color = levelColor(a)
        c.drawText(fmt(a), cx, top + bigText.textSize, bigText)
        c.drawText(if (tubeVertical) "Long edge" else "Short edge", cx,
            top + bigText.textSize + midText.textSize * 1.6f, midText)

        val areaTop = top + bigText.textSize + midText.textSize * 2.5f
        val areaBot = h - Ui.dp(context, 48f)
        val cy = (areaTop + areaBot) / 2f
        val thick = Ui.dp(context, 44f)
        val half = if (tubeVertical) min((areaBot - areaTop) / 2f, w) * 0.9f else w * 0.42f
        val full = 10f
        val br = thick * 0.38f
        val off = (edgeAngle / full).coerceIn(-1f, 1f) * (half - br - thick * 0.1f)

        // True horizon line through the centre
        val ux = g[0]; val uy = -g[1]
        val n = hypot(ux, uy)
        if (n > 0.1f) {
            val hx = -uy / n * w; val hy = ux / n * w
            c.drawLine(cx - hx, cy - hy, cx + hx, cy + hy, horizon)
        }

        if (tubeVertical) rect.set(cx - thick / 2, cy - half, cx + thick / 2, cy + half)
        else rect.set(cx - half, cy - thick / 2, cx + half, cy + thick / 2)
        c.drawRoundRect(rect, thick / 2, thick / 2, tubeFill)
        c.drawRoundRect(rect, thick / 2, thick / 2, ringOuter)
        // centre marks
        val m = br + Ui.dp(context, 4f)
        if (tubeVertical) {
            c.drawLine(rect.left, cy - m, rect.right, cy - m, ringOuter)
            c.drawLine(rect.left, cy + m, rect.right, cy + m, ringOuter)
        } else {
            c.drawLine(cx - m, rect.top, cx - m, rect.bottom, ringOuter)
            c.drawLine(cx + m, rect.top, cx + m, rect.bottom, ringOuter)
        }
        bubble.color = levelColor(a)
        val bx = if (tubeVertical) cx else cx + off
        val by = if (tubeVertical) cy + off else cy
        c.drawCircle(bx, by, br, bubble)
        c.drawCircle(bx, by, br, bubbleEdge)

    }
}
