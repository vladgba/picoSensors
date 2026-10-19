package x.vladgba.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/** Ruler page: drawing surface + toolbar (shape, units, calibration). */
class RulerScreen(ctx: Context) : LinearLayout(ctx), Screen {

    override val view: View get() = this
    private val ruler = RulerView(ctx)
    private val modeButtons = ArrayList<TextView>()
    private lateinit var unitBtn: TextView
    private lateinit var calBtn: TextView

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.BG)
        addView(ruler, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            setBackgroundColor(Ui.BG)
        }
        MODES.forEachIndexed { i, name ->
            val b = button(name) {
                // while calibrating these buttons become  −  /  Reset  /  +
                if (ruler.calibrating) ruler.nudgeCal(i - 1) else ruler.mode = i
                refresh()
            }
            modeButtons.add(b)
            bar.addView(b, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        unitBtn = button("mm") { ruler.inches = !ruler.inches; refresh() }
        bar.addView(unitBtn, LayoutParams(0, LayoutParams.MATCH_PARENT, 0.7f))
        calBtn = button("Cal") { ruler.calibrating = !ruler.calibrating; refresh() }
        bar.addView(calBtn, LayoutParams(0, LayoutParams.MATCH_PARENT, 0.7f))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(ctx, 48f).toInt()))
        refresh()
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun refresh() {
        modeButtons.forEachIndexed { i, b ->
            if (ruler.calibrating) {
                b.text = CAL_BUTTONS[i]
                b.setTextColor(Ui.FG)
            } else {
                b.text = MODES[i]
                b.setTextColor(if (i == ruler.mode) Ui.ACCENT else Ui.DIM)
            }
        }
        unitBtn.text = if (ruler.inches) "in" else "mm"
        unitBtn.setTextColor(Ui.FG)
        calBtn.text = if (ruler.calibrating) "Done" else "Cal"
        calBtn.setTextColor(if (ruler.calibrating) Ui.AMBER else Ui.DIM)
        ruler.invalidate()
    }

    override fun start() {}
    override fun stop() = ruler.save()

    private companion object {
        val MODES = arrayOf("Line", "Triangle", "Square")
        val CAL_BUTTONS = arrayOf("−", "Reset", "+")
    }
}

@SuppressLint("ViewConstructor")
class RulerView(ctx: Context) : View(ctx) {

    var mode = 0
        set(v) { field = v; invalidate() }
    var inches = false
        set(v) { field = v; invalidate() }
    var calibrating = false
        set(v) { field = v; if (!v) save(); invalidate() }

    private val prefs = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
    private var cal = prefs.getFloat("ruler_cal", 1f)

    private val baseX: Float
    private val baseY: Float

    init {
        val dm: DisplayMetrics = ctx.resources.displayMetrics
        // Some devices report bogus xdpi/ydpi – fall back to densityDpi in that case.
        fun sane(v: Float) = if (v < dm.densityDpi * 0.7f || v > dm.densityDpi * 1.3f) dm.densityDpi.toFloat() else v
        baseX = sane(dm.xdpi) / 25.4f
        baseY = sane(dm.ydpi) / 25.4f
        mode = prefs.getInt("ruler_mode", 0)
        inches = prefs.getBoolean("ruler_in", false)
        setBackgroundColor(Ui.BG)
    }

    private val pxX get() = baseX * cal   // pixels per millimetre
    private val pxY get() = baseY * cal

    // handles (pixels)
    private var ax = 0f; private var ay = 0f      // line A
    private var bx = 0f; private var by = 0f      // line B
    private val tri = FloatArray(6)               // triangle vertices A, B, C (x, y pairs)
    private var sx = 0f; private var sy = 0f      // square corner
    private var initialised = false
    private var active = -1
    private var lastTouchY = 0f                   // calibration drag is relative

    private val tick = Ui.stroke(Ui.FG, Ui.dp(ctx, 1.2f))
    private val tickLabel = Ui.text(Ui.FG, Ui.sp(ctx, 11f))
    private val shapeStroke = Ui.stroke(Ui.ACCENT, Ui.dp(ctx, 2f))
    private val shapeFill = Ui.fill(0x224FC3F7)
    private val guide = Ui.stroke(Ui.GRID, Ui.dp(ctx, 1f))
    private val handleFill = Ui.fill(Ui.ACCENT)
    private val handleRing = Ui.stroke(Ui.FG, Ui.dp(ctx, 2f))
    private val pillBg = Ui.fill(0xE01A2027.toInt())
    private val pillText = Ui.text(Ui.FG, Ui.sp(ctx, 16f), bold = true)
    private val smallText = Ui.text(Ui.DIM, Ui.sp(ctx, 13f))
    private val infoText = Ui.text(Ui.FG, Ui.sp(ctx, 15f), align = Paint.Align.LEFT)
    private val cardStroke = Ui.stroke(Ui.AMBER, Ui.dp(ctx, 2f))
    private val cardFill = Ui.fill(0x18FFCA28)
    private val angleText = Ui.text(Ui.AMBER, Ui.sp(ctx, 13f), bold = true)
    private val rect = RectF()
    private val path = Path()

    private val handleR = Ui.dp(ctx, 13f)
    private val margin = Ui.dp(ctx, 30f)   // keeps handles clear of the edge scales

    fun save() {
        prefs.edit().putFloat("ruler_cal", cal).putInt("ruler_mode", mode)
            .putBoolean("ruler_in", inches).apply()
    }

    /** Fine-tune the calibration: -1 / +1 = one 0.1 % step, 0 = back to the reported DPI. */
    fun nudgeCal(dir: Int) {
        cal = if (dir == 0) 1f else (cal * (1f + dir * 0.001f)).coerceIn(CAL_MIN, CAL_MAX)
        save()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!initialised && w > 0 && h > 0) {
            ax = margin + 10 * pxX; ay = h * 0.35f
            bx = w - margin; by = h * 0.35f
            tri[0] = w * 0.25f; tri[1] = h * 0.22f
            tri[2] = w * 0.80f; tri[3] = h * 0.30f
            tri[4] = w * 0.35f; tri[5] = h * 0.62f
            sx = w * 0.6f; sy = w * 0.6f * pxY / pxX
            initialised = true
        }
        clampAll()
    }

    private fun clampAll() {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f) return
        ax = ax.coerceIn(0f, w); ay = ay.coerceIn(0f, h)
        bx = bx.coerceIn(0f, w); by = by.coerceIn(0f, h)
        for (i in 0..2) {
            tri[2 * i] = tri[2 * i].coerceIn(0f, w)
            tri[2 * i + 1] = tri[2 * i + 1].coerceIn(0f, h)
        }
        sx = sx.coerceIn(margin, w); sy = sy.coerceIn(margin, h)
    }

    // ---- units -------------------------------------------------------------

    private fun len(mm: Float) = if (inches) Ui.f("%.2f in", mm / 25.4f) else
        if (mm >= 100f) Ui.f("%.1f cm", mm / 10f) else Ui.f("%.1f mm", mm)

    private fun area(mm2: Float) = if (inches) Ui.f("%.2f in²", mm2 / 645.16f)
        else Ui.f("%.2f cm²", mm2 / 100f)

    // ---- touch -------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                active = when {
                    calibrating -> 9
                    mode == 0 -> if (hypot(x - ax, y - ay) < hypot(x - bx, y - by)) 0 else 1
                    mode == 1 -> 10 + (0..2).minBy { hypot(x - tri[2 * it], y - tri[2 * it + 1]) }
                    else -> 4
                }
                lastTouchY = y
                if (active != 9) move(x, y)
            }
            MotionEvent.ACTION_MOVE -> move(x, y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (active == 9) save()
                active = -1
            }
        }
        return true
    }

    private fun move(x: Float, y: Float) {
        when (active) {
            0 -> { ax = x; ay = y }
            1 -> { bx = x; by = y }
            10, 11, 12 -> { val i = active - 10; tri[2 * i] = x; tri[2 * i + 1] = y }
            4 -> {
                sx = x; sy = y
                // snap to a perfect square when close
                val wmm = sx / pxX; val hmm = sy / pxY
                if (abs(wmm - hmm) < 2f) { val m = (wmm + hmm) / 2f; sx = m * pxX; sy = m * pxY }
            }
            9 -> {
                // Relative drag: the card's bottom edge follows the finger's vertical motion,
                // so the finger never has to cover the edge being aligned.
                cal = (cal + (y - lastTouchY) / (CARD_W * baseY)).coerceIn(CAL_MIN, CAL_MAX)
                lastTouchY = y
            }
        }
        clampAll()
        invalidate()
    }

    // ---- drawing -----------------------------------------------------------

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (calibrating) {
            drawCalibration(c, w, h)
            drawScales(c, w, h)
            return
        }
        when (mode) {
            0 -> drawLine(c)
            1 -> drawTriangle(c, h)
            else -> drawSquare(c, h)
        }
        drawScales(c, w, h)
    }

    private fun drawScales(c: Canvas, w: Float, h: Float) {
        val l1 = Ui.dp(context, 18f); val l2 = Ui.dp(context, 12f); val l3 = Ui.dp(context, 7f)
        if (!inches) {
            var i = 0
            while (i * pxX <= w) {
                val x = i * pxX
                val l = if (i % 10 == 0) l1 else if (i % 5 == 0) l2 else l3
                c.drawLine(x, 0f, x, l, tick)
                if (i % 10 == 0 && i > 0) c.drawText((i / 10).toString(), x, l1 + tickLabel.textSize, tickLabel)
                i++
            }
            i = 0
            while (i * pxY <= h) {
                val y = i * pxY
                val l = if (i % 10 == 0) l1 else if (i % 5 == 0) l2 else l3
                c.drawLine(0f, y, l, y, tick)
                if (i % 10 == 0 && i > 0) c.drawText((i / 10).toString(), l1 + tickLabel.textSize * 0.9f, y + tickLabel.textSize * 0.35f, tickLabel)
                i++
            }
        } else {
            val stepX = pxX * 25.4f / 16f; val stepY = pxY * 25.4f / 16f
            fun lenFor(i: Int) = when {
                i % 16 == 0 -> l1
                i % 8 == 0 -> l1 * 0.75f
                i % 4 == 0 -> l2
                i % 2 == 0 -> l3 * 1.2f
                else -> l3 * 0.75f
            }
            var i = 0
            while (i * stepX <= w) {
                val x = i * stepX
                c.drawLine(x, 0f, x, lenFor(i), tick)
                if (i % 16 == 0 && i > 0) c.drawText((i / 16).toString(), x, l1 + tickLabel.textSize, tickLabel)
                i++
            }
            i = 0
            while (i * stepY <= h) {
                val y = i * stepY
                c.drawLine(0f, y, lenFor(i), y, tick)
                if (i % 16 == 0 && i > 0) c.drawText((i / 16).toString(), l1 + tickLabel.textSize * 0.9f, y + tickLabel.textSize * 0.35f, tickLabel)
                i++
            }
        }
        c.drawText(if (inches) "in" else "cm", l1 + tickLabel.textSize, l1 + tickLabel.textSize, smallText)
    }

    private fun pill(c: Canvas, text: String, x: Float, y: Float) {
        val tw = pillText.measureText(text)
        val ph = pillText.textSize * 1.6f
        val pad = pillText.textSize * 0.6f
        val cx = x.coerceIn(tw / 2 + pad + 4, width - tw / 2 - pad - 4)
        val cy = y.coerceIn(ph / 2 + 4, height - ph / 2 - 4)
        rect.set(cx - tw / 2 - pad, cy - ph / 2, cx + tw / 2 + pad, cy + ph / 2)
        c.drawRoundRect(rect, ph / 2, ph / 2, pillBg)
        c.drawText(text, cx, cy + pillText.textSize * 0.35f, pillText)
    }

    private fun handle(c: Canvas, x: Float, y: Float) {
        c.drawCircle(x, y, handleR, handleFill)
        c.drawCircle(x, y, handleR, handleRing)
    }

    private fun drawInfo(c: Canvas, lines: List<String>, h: Float) {
        var y = h - Ui.dp(context, 12f) - (lines.size - 1) * infoText.textSize * 1.4f
        for (s in lines) {
            c.drawText(s, Ui.dp(context, 44f), y, infoText)
            y += infoText.textSize * 1.4f
        }
    }

    private fun drawLine(c: Canvas) {
        val dxMm = (bx - ax) / pxX; val dyMm = (by - ay) / pxY
        val l = hypot(dxMm, dyMm)
        // perpendicular end caps + mm ticks along the line
        val lpx = hypot(bx - ax, by - ay)
        if (lpx > 1f) {
            val ux = (bx - ax) / lpx; val uy = (by - ay) / lpx
            val nx = -uy; val ny = ux
            val cap = Ui.dp(context, 16f)
            c.drawLine(ax - nx * cap, ay - ny * cap, ax + nx * cap, ay + ny * cap, shapeStroke)
            c.drawLine(bx - nx * cap, by - ny * cap, bx + nx * cap, by + ny * cap, shapeStroke)
            val pxPerMmAlong = hypot(ux * pxX, uy * pxY)
            val stepMm = if (inches) 25.4f / 8f else 1f
            val major = if (inches) 8 else 10
            var i = 1
            while (i * stepMm * pxPerMmAlong < lpx) {
                val d = i * stepMm * pxPerMmAlong
                val t = if (i % major == 0) cap * 0.8f else if (!inches && i % 5 == 0) cap * 0.55f else cap * 0.35f
                val px = ax + ux * d; val py = ay + uy * d
                c.drawLine(px, py, px + nx * t, py + ny * t, tick)
                i++
            }
        }
        c.drawLine(ax, ay, bx, by, shapeStroke)
        handle(c, ax, ay); handle(c, bx, by)
        var ang = Math.toDegrees(atan2(-dyMm, dxMm).toDouble()).toFloat()
        if (ang < 0) ang += 360f
        pill(c, len(l), (ax + bx) / 2, (ay + by) / 2 - Ui.dp(context, 34f))
        drawInfo(c, listOf("Δx ${len(abs(dxMm))}   Δy ${len(abs(dyMm))}", Ui.f("Angle %.1f°", ang)), height.toFloat())
    }

    private fun drawTriangle(c: Canvas, h: Float) {
        path.reset()
        path.moveTo(tri[0], tri[1]); path.lineTo(tri[2], tri[3]); path.lineTo(tri[4], tri[5]); path.close()
        c.drawPath(path, shapeFill)
        c.drawPath(path, shapeStroke)

        // geometry in millimetres (x/y scales may differ slightly)
        val mx = FloatArray(3) { tri[2 * it] / pxX }
        val my = FloatArray(3) { tri[2 * it + 1] / pxY }
        val gx = (tri[0] + tri[2] + tri[4]) / 3f
        val gy = (tri[1] + tri[3] + tri[5]) / 3f
        var perimeter = 0f

        // side lengths: side i joins vertex i and i+1, label pushed away from the centroid
        for (i in 0..2) {
            val j = (i + 1) % 3
            val l = hypot(mx[j] - mx[i], my[j] - my[i])
            perimeter += l
            val midX = (tri[2 * i] + tri[2 * j]) / 2f
            val midY = (tri[2 * i + 1] + tri[2 * j + 1]) / 2f
            val (ox, oy) = unit(midX - gx, midY - gy)
            pill(c, len(l), midX + ox * Ui.dp(context, 30f), midY + oy * Ui.dp(context, 30f))
        }

        // interior angles just inside each vertex; right angles get the usual square mark
        for (i in 0..2) {
            val j = (i + 1) % 3; val k = (i + 2) % 3
            val ux = mx[j] - mx[i]; val uy = my[j] - my[i]
            val vx = mx[k] - mx[i]; val vy = my[k] - my[i]
            val ang = Math.toDegrees(atan2(abs(ux * vy - uy * vx), ux * vx + uy * vy).toDouble()).toFloat()
            val px = tri[2 * i]; val py = tri[2 * i + 1]
            if (abs(ang - 90f) < 0.5f) drawRightAngle(c, i, j, k)
            val (dx, dy) = unit(gx - px, gy - py)
            val d = Ui.dp(context, 42f)
            c.drawText(Ui.f("%.1f°", ang), px + dx * d, py + dy * d + angleText.textSize * 0.35f, angleText)
        }

        for (i in 0..2) handle(c, tri[2 * i], tri[2 * i + 1])

        val area = abs((mx[1] - mx[0]) * (my[2] - my[0]) - (mx[2] - mx[0]) * (my[1] - my[0])) / 2f
        drawInfo(c, listOf("Perimeter ${len(perimeter)}", "Area ${area(area)}"), h)
    }

    private fun drawRightAngle(c: Canvas, i: Int, j: Int, k: Int) {
        val px = tri[2 * i]; val py = tri[2 * i + 1]
        val s = Ui.dp(context, 14f)
        val (ax1, ay1) = unit(tri[2 * j] - px, tri[2 * j + 1] - py)
        val (ax2, ay2) = unit(tri[2 * k] - px, tri[2 * k + 1] - py)
        path.reset()
        path.moveTo(px + ax1 * s, py + ay1 * s)
        path.lineTo(px + (ax1 + ax2) * s, py + (ay1 + ay2) * s)
        path.lineTo(px + ax2 * s, py + ay2 * s)
        c.drawPath(path, shapeStroke)
    }

    private fun unit(x: Float, y: Float): Pair<Float, Float> {
        val l = hypot(x, y)
        return if (l < 1e-3f) 0f to 0f else (x / l) to (y / l)
    }

    private fun drawSquare(c: Canvas, h: Float) {
        rect.set(0f, 0f, sx, sy)
        c.drawRect(rect, shapeFill)
        c.drawRect(rect, shapeStroke)
        handle(c, sx, sy)
        val wm = sx / pxX; val hm = sy / pxY
        val square = abs(wm - hm) < 0.05f
        pill(c, len(wm), sx / 2, sy + Ui.dp(context, 24f))
        pill(c, len(hm), sx + Ui.dp(context, 50f), sy / 2)
        drawInfo(c, listOf(
            (if (square) "Square   " else "") + "Area ${area(wm * hm)}",
            "Diagonal ${len(hypot(wm, hm))}"
        ), h)
    }

    private fun drawCalibration(c: Canvas, w: Float, h: Float) {
        // Portrait card: the short side (53.98 mm) fits across every phone, the long side
        // (85.60 mm) runs down the screen and is the one being matched.
        val cw = CARD_H * pxX; val ch = CARD_W * pxY
        rect.set(0f, 0f, cw, ch)
        val r = 3.2f * pxX
        c.drawRoundRect(rect, r, r, cardFill)
        c.drawRoundRect(rect, r, r, cardStroke)
        c.drawLine(0f, ch, w, ch, guide)

        val lines = listOf(
            "Calibration",
            "Put a bank card upright in the top-left",
            "corner and drag up/down until its bottom",
            "edge matches (85.60 mm).",
            "Fine-tune with − / +",
            Ui.f("Scale %.1f %%", cal * 100f)
        )
        val step = smallText.textSize * 1.6f
        val blockH = step * lines.size
        // below the card when it fits, otherwise pinned to the bottom of the view
        var y = min(ch + Ui.dp(context, 36f), h - blockH)
        for (s in lines) {
            c.drawText(s, w / 2, y, if (s == lines[0]) pillText else smallText)
            y += step
        }
    }

    companion object {
        private const val CARD_W = 85.60f
        private const val CARD_H = 53.98f
        private const val CAL_MIN = 0.5f
        private const val CAL_MAX = 2f
    }
}
