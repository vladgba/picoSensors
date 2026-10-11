package x.vladgba.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * GPS speed and coordinates.
 * Tap speed = change unit, tap coordinates = copy, long press = reset max speed / trip.
 */
@SuppressLint("ViewConstructor")
class GpsView(ctx: Context) : View(ctx), Screen, LocationListener {

    override val view: View get() = this

    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val prefs = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    private var loc: Location? = null
    private var prev: Location? = null
    private var fixElapsed = 0L
    private var speed = 0f           // m/s
    private var maxSpeed = 0f
    private var trip = 0f            // metres
    private var satUsed = 0
    private var satSeen = 0
    private var running = false
    private var unit = prefs.getInt("gps_unit", 0)
    private var coordFmt = prefs.getInt("gps_fmt", 0)

    private val speedRect = RectF()
    private val coordRect = RectF()
    private var downX = 0f
    private var downY = 0f

    private val bigText = Ui.text(Ui.FG, Ui.sp(ctx, 72f), bold = true)
    private val unitText = Ui.text(Ui.ACCENT, Ui.sp(ctx, 18f), bold = true)
    private val label = Ui.text(Ui.DIM, Ui.sp(ctx, 13f), align = Paint.Align.LEFT)
    private val value = Ui.mono(Ui.FG, Ui.sp(ctx, 17f), Paint.Align.RIGHT)
    private val coordText = Ui.mono(Ui.FG, Ui.sp(ctx, 21f), Paint.Align.CENTER)
    private val msgText = Ui.text(Ui.DIM, Ui.sp(ctx, 16f))
    private val arcBg = Ui.stroke(Ui.GRID, Ui.dp(ctx, 10f))
    private val arcFg = Ui.stroke(Ui.ACCENT, Ui.dp(ctx, 10f))
    private val sep = Ui.stroke(Ui.GRID, Ui.dp(ctx, 1f))
    private val rect = RectF()

    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            satUsed = used
            satSeen = status.satelliteCount
            invalidate()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            if (running) main.postDelayed(this, 1000)
        }
    }

    init {
        setBackgroundColor(Ui.BG)
        isLongClickable = true
        setOnLongClickListener {
            maxSpeed = 0f; trip = 0f
            Toast.makeText(context, "Max speed and trip reset", Toast.LENGTH_SHORT).show()
            invalidate(); true
        }
    }

    private fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override fun start() {
        if (running) return
        if (!hasPermission()) { invalidate(); return }
        running = true
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this, Looper.getMainLooper())
            @Suppress("DEPRECATION")
            lm.registerGnssStatusCallback(gnss, main)
            if (loc == null) {
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { loc = it }
            }
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
            // no GPS provider on this device
        }
        main.post(ticker)
    }

    override fun stop() {
        running = false
        main.removeCallbacks(ticker)
        try {
            lm.removeUpdates(this)
            lm.unregisterGnssStatusCallback(gnss)
        } catch (_: Exception) {
        }
        prefs.edit().putInt("gps_unit", unit).putInt("gps_fmt", coordFmt).apply()
    }

    // ---- LocationListener (all four overridden for pre-API-30 devices) ----

    override fun onLocationChanged(l: Location) {
        val p = prev
        speed = when {
            l.hasSpeed() -> l.speed
            p != null -> {
                val dt = (l.elapsedRealtimeNanos - p.elapsedRealtimeNanos) / 1e9f
                if (dt > 0.2f) p.distanceTo(l) / dt else speed
            }
            else -> 0f
        }
        if (l.accuracy < 30f) {
            maxSpeed = max(maxSpeed, speed)
            if (p != null) {
                val d = p.distanceTo(l)
                // ignore jitter while standing still
                if (d > max(l.accuracy, 3f) * 0.5f && speed > 0.5f) trip += d
            }
            prev = l
        }
        loc = l
        fixElapsed = SystemClock.elapsedRealtime()
        invalidate()
    }

    @Deprecated("Deprecated in API 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) { invalidate() }
    override fun onProviderDisabled(provider: String) { invalidate() }

    // ---- formatting ----

    private fun speedIn(ms: Float) = when (unit) {
        0 -> ms * 3.6f
        1 -> ms * 2.236936f
        2 -> ms * 1.943844f
        else -> ms
    }
    private fun unitName() = arrayOf("km/h", "mph", "kn", "m/s")[unit]
    private fun fmtSpeed(ms: Float) = Ui.f("%.1f %s", speedIn(ms), unitName())
    private fun fmtDist(m: Float) = if (unit == 1) Ui.f("%.2f mi", m / 1609.344f)
        else if (m < 1000) Ui.f("%.0f m", m) else Ui.f("%.2f km", m / 1000f)

    private fun dms(v: Double, pos: Char, neg: Char): String {
        val a = abs(v)
        val d = a.toInt()
        val mf = (a - d) * 60
        val m = mf.toInt()
        val s = (mf - m) * 60
        return Ui.f("%d°%02d'%05.2f\"%c", d, m, s, if (v >= 0) pos else neg)
    }

    private fun coordString(l: Location): Pair<String, String> = when (coordFmt) {
        1 -> dms(l.latitude, 'N', 'S') to dms(l.longitude, 'E', 'W')
        else -> Ui.f("%.6f", l.latitude) to Ui.f("%.6f", l.longitude)
    }

    // ---- touch ----

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val handled = super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> {
                val moved = abs(e.x - downX) > 20 || abs(e.y - downY) > 20
                val quick = e.eventTime - e.downTime < 400
                if (!moved && quick) onTap(e.x, e.y)
            }
        }
        return handled || isEnabled
    }

    private fun onTap(x: Float, y: Float): Boolean {
        if (!hasPermission()) {
            (context as? Activity)?.requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOCATION)
            return true
        }
        if (speedRect.contains(x, y)) {
            unit = (unit + 1) % 4
            invalidate(); return true
        }
        if (coordRect.contains(x, y)) {
            val l = loc ?: return true
            val text = Ui.f("%.6f, %.6f", l.latitude, l.longitude)
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("coordinates", text))
            Toast.makeText(context, "Copied $text", Toast.LENGTH_SHORT).show()
            coordFmt = (coordFmt + 1) % 2
            invalidate(); return true
        }
        return false
    }

    // ---- drawing ----

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2
        val pad = Ui.dp(context, 20f)

        if (!hasPermission()) {
            c.drawText("Location permission needed", cx, h / 2 - msgText.textSize, msgText)
            c.drawText("Tap to grant", cx, h / 2 + msgText.textSize, unitText)
            return
        }
        val gpsOn = try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (_: Exception) { false }

        // Speed gauge
        val r = min(w * 0.36f, h * 0.2f)
        val gy = pad + r + Ui.dp(context, 10f)
        rect.set(cx - r, gy - r, cx + r, gy + r)
        val scale = when { speedIn(maxSpeed) <= 60f -> 60f; speedIn(maxSpeed) <= 140f -> 140f; else -> 300f }
        val frac = (speedIn(speed) / scale).coerceIn(0f, 1f)
        c.drawArc(rect, 150f, 240f, false, arcBg)
        if (frac > 0f) c.drawArc(rect, 150f, 240f * frac, false, arcFg)
        val fresh = SystemClock.elapsedRealtime() - fixElapsed < 5000 && fixElapsed > 0
        bigText.color = if (fresh) Ui.FG else Ui.DIM
        val sp = speedIn(if (fresh) speed else 0f)
        c.drawText(if (sp >= 100) Ui.f("%.0f", sp) else Ui.f("%.1f", sp), cx, gy + bigText.textSize * 0.3f, bigText)
        c.drawText(unitName(), cx, gy + r * 0.72f, unitText)
        speedRect.set(cx - r, gy - r, cx + r, gy + r)

        // Coordinates
        var y = gy + r + Ui.dp(context, 28f)
        val l = loc
        val status = when {
            !gpsOn -> "GPS is turned off"
            l == null -> "Waiting for fix…"
            !fresh -> "Last known position"
            else -> null
        }
        if (status != null) {
            msgText.color = if (!gpsOn) Ui.RED else Ui.AMBER
            c.drawText(status, cx, y, msgText)
            msgText.color = Ui.DIM
            y += msgText.textSize * 1.6f
        }
        val coordTop = y - coordText.textSize
        if (l != null) {
            val (la, lo) = coordString(l)
            c.drawText(la, cx, y, coordText)
            y += coordText.textSize * 1.35f
            c.drawText(lo, cx, y, coordText)
            y += coordText.textSize * 0.5f
            label.textAlign = Paint.Align.CENTER
            c.drawText("tap to copy · switch format", cx, y + label.textSize, label)
            label.textAlign = Paint.Align.LEFT
            y += label.textSize * 2f
        }
        coordRect.set(0f, coordTop - 10, w, y)

        // Details table
        c.drawLine(pad, y, w - pad, y, sep)
        y += Ui.dp(context, 8f)
        val rows = ArrayList<Pair<String, String>>()
        rows += "Altitude" to (if (l != null && l.hasAltitude()) Ui.f("%.0f m", l.altitude) else "—")
        rows += "Accuracy" to (if (l != null && l.hasAccuracy()) Ui.f("± %.0f m", l.accuracy) else "—")
        rows += "Bearing" to (if (l != null && l.hasBearing() && speed > 0.5f)
            Ui.f("%.0f° %s", l.bearing, Ui.cardinal(l.bearing)) else "—")
        rows += "Satellites" to "$satUsed / $satSeen"
        rows += "Max speed" to fmtSpeed(maxSpeed)
        rows += "Trip" to fmtDist(trip)
        rows += "Last fix" to (if (fixElapsed > 0) "${(SystemClock.elapsedRealtime() - fixElapsed) / 1000} s ago" else "—")
        val rowH = max(value.textSize * 1.75f, (h - y - pad) / rows.size).coerceAtMost(value.textSize * 2.4f)
        for ((k, v) in rows) {
            y += rowH
            if (y > h - 4) break
            c.drawText(k, pad, y - rowH * 0.3f, label)
            c.drawText(v, w - pad, y - rowH * 0.3f, value)
        }
    }

    companion object {
        const val REQ_LOCATION = 42
    }
}
