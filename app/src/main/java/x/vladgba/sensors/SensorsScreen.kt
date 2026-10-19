package x.vladgba.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Live readings of the device's motion and environment sensors, each with a scrolling graph.
 * Only sensors the device actually has are shown. Tap a card to freeze / resume it.
 */
@SuppressLint("ViewConstructor")
class SensorsScreen(ctx: Context) : ScrollView(ctx), Screen, SensorEventListener {

    override val view: View get() = this

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val cards = HashMap<Int, SensorCard>()

    init {
        setBackgroundColor(Ui.BG)
        isFillViewport = true
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val pad = Ui.dp(ctx, 10f).toInt()
        list.setPadding(pad, pad, pad, pad)

        val missing = ArrayList<String>()
        for (spec in SPECS) {
            val sensor = sm.getDefaultSensor(spec.type)
            if (sensor == null) { missing += spec.title; continue }
            val card = SensorCard(ctx, spec, sensor)
            cards[spec.type] = card
            list.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                Ui.dp(ctx, if (spec.axes.size > 1) 150f else 120f).toInt()).apply { bottomMargin = pad })
        }

        val all = sm.getSensorList(Sensor.TYPE_ALL)
        val footer = buildString {
            if (missing.isNotEmpty()) append("Not available: ${missing.joinToString(", ")}\n\n")
            append("All sensors on this device (${all.size}):\n")
            all.forEach { append("• ${it.name} — ${it.vendor}\n") }
        }
        list.addView(TextView(ctx).apply {
            text = footer.trimEnd()
            textSize = 12f
            setTextColor(Ui.DIM)
            setPadding(pad / 2, pad / 2, pad / 2, pad * 2)
        })
        addView(list)
    }

    override fun start() {
        for (card in cards.values) sm.registerListener(this, card.sensor, SensorManager.SENSOR_DELAY_UI)
    }

    override fun stop() = sm.unregisterListener(this)

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        cards[e.sensor.type]?.push(e.values)
    }

    /** What to show for one sensor type: axis labels and the conversion from raw event values. */
    class Spec(
        val type: Int,
        val title: String,
        val unit: String,
        val axes: Array<String>,
        val magnitude: Boolean = false,
        val decimals: Int = 2,
        val convert: (FloatArray, FloatArray) -> Unit = { src, dst -> for (i in dst.indices) dst[i] = src[i] },
        val extra: ((Float) -> String)? = null,
    )

    private companion object {
        val XYZ = arrayOf("X", "Y", "Z")
        const val RAD2DEG = 57.29578f

        private val rotTmp = FloatArray(9)
        private val oriTmp = FloatArray(3)

        val SPECS = arrayOf(
            Spec(Sensor.TYPE_ACCELEROMETER, "Accelerometer", "m/s²", XYZ, magnitude = true),
            Spec(Sensor.TYPE_LINEAR_ACCELERATION, "Linear acceleration", "m/s²", XYZ, magnitude = true),
            Spec(Sensor.TYPE_GRAVITY, "Gravity", "m/s²", XYZ, magnitude = true),
            Spec(Sensor.TYPE_GYROSCOPE, "Gyroscope", "°/s", XYZ, magnitude = true, decimals = 1,
                convert = { src, dst -> for (i in 0..2) dst[i] = src[i] * RAD2DEG }),
            Spec(Sensor.TYPE_MAGNETIC_FIELD, "Magnetometer", "µT", XYZ, magnitude = true, decimals = 1),
            Spec(Sensor.TYPE_ROTATION_VECTOR, "Orientation", "°", arrayOf("Azimuth", "Pitch", "Roll"), decimals = 1,
                convert = { src, dst ->
                    SensorManager.getRotationMatrixFromVector(rotTmp, src)
                    SensorManager.getOrientation(rotTmp, oriTmp)
                    for (i in 0..2) dst[i] = oriTmp[i] * RAD2DEG
                }),
            Spec(Sensor.TYPE_LIGHT, "Light", "lx", arrayOf("Illuminance"), decimals = 0),
            Spec(Sensor.TYPE_PROXIMITY, "Proximity", "cm", arrayOf("Distance"), decimals = 1),
            Spec(Sensor.TYPE_PRESSURE, "Barometer", "hPa", arrayOf("Pressure"), decimals = 2,
                extra = { p -> Ui.f("≈ %.0f m altitude (std. atmosphere)",
                    SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, p)) }),
            Spec(Sensor.TYPE_AMBIENT_TEMPERATURE, "Temperature", "°C", arrayOf("Ambient"), decimals = 1),
            Spec(Sensor.TYPE_RELATIVE_HUMIDITY, "Humidity", "%", arrayOf("Relative"), decimals = 1),
        )
    }
}

/** One sensor: current values, min/max (single-value sensors) and a scrolling history graph. */
@SuppressLint("ViewConstructor")
class SensorCard(ctx: Context, private val spec: SensorsScreen.Spec, val sensor: Sensor) : View(ctx) {

    private val n = spec.axes.size
    private val cur = FloatArray(n)
    private val history = Array(n) { FloatArray(HISTORY) }
    private var count = 0          // samples stored (≤ HISTORY)
    private var head = 0           // next write index in the ring buffer
    private var minV = Float.MAX_VALUE
    private var maxV = -Float.MAX_VALUE
    private var frozen = false

    private val bg = Ui.fill(Ui.PANEL)
    private val titleP = Ui.text(Ui.FG, Ui.sp(ctx, 15f), bold = true, align = android.graphics.Paint.Align.LEFT)
    private val nameP = Ui.text(Ui.DIM, Ui.sp(ctx, 11f), align = android.graphics.Paint.Align.RIGHT)
    private val labelP = Ui.text(Ui.DIM, Ui.sp(ctx, 11f), align = android.graphics.Paint.Align.LEFT)
    private val valueP = Ui.mono(Ui.FG, Ui.sp(ctx, 16f))
    private val bigP = Ui.mono(Ui.FG, Ui.sp(ctx, 24f))
    private val smallP = Ui.text(Ui.DIM, Ui.sp(ctx, 12f), align = android.graphics.Paint.Align.LEFT)
    private val grid = Ui.stroke(Ui.GRID, Ui.dp(ctx, 1f))
    private val lines = Array(n) { Ui.stroke(AXIS_COLORS[it % AXIS_COLORS.size], Ui.dp(ctx, 1.6f)) }
    private val rect = RectF()
    private val path = Path()

    init {
        setOnClickListener { frozen = !frozen; invalidate() }
    }

    fun push(values: FloatArray) {
        if (frozen) return
        spec.convert(values, cur)
        for (i in 0 until n) history[i][head] = cur[i]
        head = (head + 1) % HISTORY
        if (count < HISTORY) count++
        if (n == 1) { minV = min(minV, cur[0]); maxV = max(maxV, cur[0]) }
        invalidate()
    }

    private fun fmt(v: Float) = Ui.f("%+.${spec.decimals}f", v)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val pad = Ui.dp(context, 12f)
        val r = Ui.dp(context, 10f)
        rect.set(0f, 0f, w, h)
        c.drawRoundRect(rect, r, r, bg)

        // title row
        var y = pad + titleP.textSize
        c.drawText("${spec.title}  ${spec.unit}", pad, y, titleP)
        nameP.color = if (frozen) Ui.AMBER else Ui.DIM
        c.drawText(if (frozen) "PAUSED · tap to resume" else sensor.name.take(32), w - pad, y, nameP)

        // values
        if (count == 0) {
            c.drawText("waiting for data…", pad, y + Ui.dp(context, 28f), smallP)
            return
        }
        val graphTop: Float
        if (n > 1) {
            val cols = n + if (spec.magnitude) 1 else 0
            val colW = (w - 2 * pad) / cols
            val ly = y + labelP.textSize * 1.6f
            val vy = ly + valueP.textSize * 1.25f
            for (i in 0 until n) {
                labelP.color = AXIS_COLORS[i % AXIS_COLORS.size]
                c.drawText(spec.axes[i], pad + i * colW, ly, labelP)
                c.drawText(fmt(cur[i]), pad + i * colW, vy, valueP)
            }
            if (spec.magnitude) {
                labelP.color = Ui.DIM
                val mag = sqrt(cur.fold(0f) { a, v -> a + v * v })
                c.drawText("Total", pad + n * colW, ly, labelP)
                c.drawText(Ui.f("%.${spec.decimals}f", mag), pad + n * colW, vy, valueP)
            }
            graphTop = vy + Ui.dp(context, 10f)
        } else {
            val vy = y + bigP.textSize * 1.3f
            c.drawText(Ui.f("%.${spec.decimals}f", cur[0]), pad, vy, bigP)
            val sub = spec.extra?.invoke(cur[0])
                ?: if (spec.type == Sensor.TYPE_PROXIMITY) (if (cur[0] >= sensor.maximumRange) "far" else "near") else null
            val info = Ui.f("min %.${spec.decimals}f · max %.${spec.decimals}f", minV, maxV)
            smallP.textAlign = android.graphics.Paint.Align.RIGHT
            c.drawText(info, w - pad, vy, smallP)
            smallP.textAlign = android.graphics.Paint.Align.LEFT
            if (sub != null) {
                c.drawText(sub, pad, vy + smallP.textSize * 1.5f, smallP)
                graphTop = vy + smallP.textSize * 2.2f
            } else graphTop = vy + Ui.dp(context, 10f)
        }
        drawGraph(c, pad, graphTop, w - pad, h - pad)
    }

    private fun drawGraph(c: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        if (bottom - top < Ui.dp(context, 12f)) return
        // range: symmetric around zero for vectors, min..max for single values
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (a in 0 until n) for (k in 0 until count) {
            val v = history[a][k]; lo = min(lo, v); hi = max(hi, v)
        }
        if (n > 1) { val m = max(max(abs(lo), abs(hi)), 0.1f); lo = -m; hi = m }
        if (hi - lo < 1e-3f) { lo -= 1f; hi += 1f }
        val span = hi - lo
        rect.set(left, top, right, bottom)
        c.drawRect(rect, grid)
        if (lo < 0f && hi > 0f) {
            val zy = bottom - (0f - lo) / span * (bottom - top)
            c.drawLine(left, zy, right, zy, grid)
        }
        val dx = (right - left) / (HISTORY - 1)
        for (a in 0 until n) {
            path.reset()
            for (k in 0 until count) {
                // oldest sample first; the newest one sits at the right edge
                val idx = (head - count + k + HISTORY) % HISTORY
                val x = right - (count - 1 - k) * dx
                val y = bottom - (history[a][idx] - lo) / span * (bottom - top)
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            c.drawPath(path, lines[a])
        }
    }

    private companion object {
        const val HISTORY = 160
        val AXIS_COLORS = intArrayOf(Ui.RED, Ui.GREEN, Ui.ACCENT)
    }
}
