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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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
        val modes = arrayOf("Line", "Triangle", "Square")
        modes.forEachIndexed { i, name ->
            val b = button(name) { ruler.mode = i; refresh() }
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
            b.setTextColor(if (i == ruler.mode && !ruler.calibrating) Ui.ACCENT else Ui.DIM)
        }
        unitBtn.text = if (ruler.inches) "in" else "mm"
        unitBtn.setTextColor(Ui.FG)
        calBtn.text = if (ruler.calibrating) "Done" else "Cal"
        calBtn.setTextColor(if (ruler.calibrating) Ui.AMBER else Ui.DIM)
        ruler.invalidate()
    }

    override fun start() {}
    override fun stop() = ruler.save()
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
    private var tx = 0f; private var ty = 0f      // triangle legs
    private var sx = 0f; private var sy = 0f      // square corner
    private var initialised = false
    private var active = -1

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
    private val rect = RectF()

    private val handleR = Ui.dp(ctx, 13f)
    private val margin = Ui.dp(ctx, 30f)   // keeps handles clear of the edge scales

    fun save() {
        prefs.edit().putFloat("ruler_cal", cal).putInt("ruler_mode", mode)
            .putBoolean("ruler_in", inches).apply()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!initialised && w > 0 && h > 0) {
            ax = margin + 10 * pxX; ay = h * 0.35f
            bx = w - margin; by = h * 0.35f
            tx = w * 0.7f; ty = h * 0.5f
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
        tx = tx.coerceIn(margin, w); ty = ty.coerceIn(margin, h)
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
                    mode == 1 -> if (hypot(x - tx, y - margin) < hypot(x - margin, y - ty)) 2 else 3
                    else -> 4
                }
                move(x, y)
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
            2 -> tx = x
            3 -> ty = y
            4 -> {
                sx = x; sy = y
                // snap to a perfect square when close
                val wmm = sx / pxX; val hmm = sy / pxY
                if (abs(wmm - hmm) < 2f) { val m = (wmm + hmm) / 2f; sx = m * pxX; sy = m * pxY }
            }
            9 -> {
                // card width in px determines the scale
                val target = max(x, Ui.dp(context, 60f))
                cal = (target / (CARD_W * baseX)).coerceIn(0.5f, 2f)
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
        val p = Path()
        p.moveTo(0f, 0f); p.lineTo(tx, 0f); p.lineTo(0f, ty); p.close()
        c.drawPath(p, shapeFill)
        c.drawPath(p, shapeStroke)
        // guides from the handles to the grips
        c.drawLine(tx, 0f, tx, margin, guide)
        c.drawLine(0f, ty, margin, ty, guide)
        handle(c, tx, margin); handle(c, margin, ty)

        val a = tx / pxX; val b = ty / pxY
        val hyp = sqrt(a * a + b * b)
        val alpha = Math.toDegrees(atan2(b, a).toDouble()).toFloat()
        pill(c, len(a), tx / 2, margin + Ui.dp(context, 26f))
        pill(c, len(b), margin + Ui.dp(context, 50f), ty / 2)
        pill(c, len(hyp), tx / 2 + Ui.dp(context, 20f), ty / 2 + Ui.dp(context, 20f))
        drawInfo(c, listOf(
            Ui.f("Angles %.1f° / %.1f° / 90°", alpha, 90f - alpha),
            "Area ${area(a * b / 2f)}"
        ), h)
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
        val cw = CARD_W * pxX; val ch = CARD_H * pxY
        rect.set(0f, 0f, cw, ch)
        c.drawRoundRect(rect, 3.2f * pxX, 3.2f * pxX, cardStroke)
        c.drawLine(cw, 0f, cw, h, guide)
        val y0 = min(ch + Ui.dp(context, 40f), h * 0.6f)
        val lines = listOf(
            "Calibration",
            "Put a bank card in the top-left corner",
            "and drag sideways until the outline",
            "matches its width (85.60 mm).",
            Ui.f("Scale %.1f %%", cal * 100f)
        )
        var y = y0
        for (s in lines) {
            c.drawText(s, w / 2, y, if (s == lines[0]) pillText else smallText)
            y += smallText.textSize * 1.6f
        }
    }

    companion object {
        private const val CARD_W = 85.60f
        private const val CARD_H = 53.98f
    }
}
