package x.vladgba.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/** Rotating-dial compass. Tap toggles magnetic / true north (when a location is known). */
@SuppressLint("ViewConstructor")
class CompassView(ctx: Context) : View(ctx), SensorEventListener, Screen {

    override val view: View get() = this

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val grav = FloatArray(3)
    private val geo = FloatArray(3)
    private var hasGrav = false
    private var hasGeo = false
    private val rot = FloatArray(9)
    private val rotRemap = FloatArray(9)
    private val orient = FloatArray(3)

    private var heading = 0f          // smoothed, magnetic
    private var hasHeading = false
    private var magAccuracy = -1
    private var declination: Float? = null
    private var trueNorth = false

    private val ringPaint = Ui.stroke(Ui.GRID, Ui.dp(ctx, 2f))
    private val tickMajor = Ui.stroke(Ui.FG, Ui.dp(ctx, 2.5f))
    private val tickMinor = Ui.stroke(Ui.DIM, Ui.dp(ctx, 1.2f))
    private val cardPaint = Ui.text(Ui.FG, Ui.sp(ctx, 26f), bold = true)
    private val northPaint = Ui.text(Ui.RED, Ui.sp(ctx, 30f), bold = true)
    private val interPaint = Ui.text(Ui.DIM, Ui.sp(ctx, 15f), bold = true)
    private val numPaint = Ui.text(Ui.DIM, Ui.sp(ctx, 13f))
    private val headPaint = Ui.text(Ui.FG, Ui.sp(ctx, 52f), bold = true)
    private val subPaint = Ui.text(Ui.DIM, Ui.sp(ctx, 15f))
    private val indexPaint = Ui.fill(Ui.ACCENT)
    private val needleN = Ui.fill(Ui.RED)
    private val needleS = Ui.fill(Ui.FG)
    private val hubPaint = Ui.fill(Ui.BG)
    private val hubRing = Ui.stroke(Ui.ACCENT, Ui.dp(ctx, 2f))
    private val path = Path()

    init {
        setBackgroundColor(Ui.BG)
        setOnClickListener {
            if (declination != null) {
                trueNorth = !trueNorth
                invalidate()
            }
        }
    }

    override fun start() {
        if (rotSensor != null) {
            sm.registerListener(this, rotSensor, SensorManager.SENSOR_DELAY_GAME)
            // magnetometer only for its calibration-accuracy callback
            magSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        } else {
            accSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            magSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }
        updateDeclination()
    }

    override fun stop() {
        sm.unregisterListener(this)
    }

    @SuppressLint("MissingPermission")
    private fun updateDeclination() {
        val c = context
        if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        var best: Location? = null
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            val l = try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } ?: continue
            if (best == null || l.time > best.time) best = l
        }
        best?.let {
            declination = GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(),
                it.altitude.toFloat(), System.currentTimeMillis()).declination
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            magAccuracy = accuracy
            invalidate()
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                apply(0.25f)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                lowPass(e.values, grav, hasGrav); hasGrav = true
                if (rotSensor == null && hasGeo && SensorManager.getRotationMatrix(rot, null, grav, geo)) apply(0.15f)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                if (rotSensor == null) {
                    lowPass(e.values, geo, hasGeo); hasGeo = true
                }
            }
        }
    }

    private fun lowPass(input: FloatArray, out: FloatArray, init: Boolean) {
        val a = if (init) 0.15f else 1f
        for (i in 0..2) out[i] += a * (input[i] - out[i])
    }

    private fun apply(alpha: Float) {
        // When the phone is held upright (screen facing the user), use the camera
        // direction instead of the unstable "top edge" azimuth.
        val m = if (abs(rot[8]) < 0.45f) {
            SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, rotRemap)
            rotRemap
        } else rot
        SensorManager.getOrientation(m, orient)
        val az = (Math.toDegrees(orient[0].toDouble()).toFloat() + 360f) % 360f
        if (!hasHeading) {
            heading = az
            hasHeading = true
        } else {
            heading = (heading + alpha * Ui.angleDiff(heading, az) + 360f) % 360f
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val top = Ui.dp(context, 24f)
        val headH = headPaint.textSize + subPaint.textSize * 2.2f + Ui.dp(context, 16f)
        val cy = top + headH + (h - top - headH) / 2f - Ui.dp(context, 8f)
        val r = min(w * 0.42f, (h - top - headH) * 0.44f)

        if (rotSensor == null && (accSensor == null || magSensor == null)) {
            canvas.drawText("No compass sensor on this device", cx, h / 2f, subPaint)
            return
        }

        val shown = if (trueNorth && declination != null) (heading + declination!! + 360f) % 360f else heading

        // Heading readout
        val hdg = Math.round(shown) % 360
        canvas.drawText("$hdg° ${Ui.cardinal(shown)}", cx, top + headPaint.textSize, headPaint)
        val ref = when {
            trueNorth -> "true north"
            declination != null -> "magnetic north · tap for true"
            else -> "magnetic north"
        }
        canvas.drawText(ref, cx, top + headPaint.textSize + subPaint.textSize * 1.5f, subPaint)

        // Fixed index mark at the top of the dial
        path.reset()
        val iy = cy - r - Ui.dp(context, 6f)
        val iw = Ui.dp(context, 11f)
        path.moveTo(cx, iy + Ui.dp(context, 16f))
        path.lineTo(cx - iw, iy - Ui.dp(context, 4f))
        path.lineTo(cx + iw, iy - Ui.dp(context, 4f))
        path.close()
        canvas.drawPath(path, indexPaint)

        // Rotating dial
        canvas.save()
        canvas.rotate(-shown, cx, cy)
        canvas.drawCircle(cx, cy, r, ringPaint)
        for (d in 0 until 360 step 5) {
            val major = d % 30 == 0
            val len = when {
                major -> r * 0.12f
                d % 10 == 0 -> r * 0.075f
                else -> r * 0.045f
            }
            canvas.save()
            canvas.rotate(d.toFloat(), cx, cy)
            canvas.drawLine(cx, cy - r, cx, cy - r + len, if (major) tickMajor else tickMinor)
            if (major && d % 90 != 0) {
                canvas.drawText(d.toString(), cx, cy - r + len + numPaint.textSize * 1.15f, numPaint)
            }
            canvas.restore()
        }
        val labels = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        for (i in 0 until 8) {
            canvas.save()
            canvas.rotate(i * 45f, cx, cy)
            when {
                i == 0 -> canvas.drawText("N", cx, cy - r * 0.66f, northPaint)
                i % 2 == 0 -> canvas.drawText(labels[i], cx, cy - r * 0.66f, cardPaint)
                else -> canvas.drawText(labels[i], cx, cy - r * 0.70f, interPaint)
            }
            canvas.restore()
        }
        // Needle (part of the dial: always points north)
        val nw = r * 0.075f
        path.reset()
        path.moveTo(cx, cy - r * 0.50f); path.lineTo(cx - nw, cy); path.lineTo(cx + nw, cy); path.close()
        canvas.drawPath(path, needleN)
        path.reset()
        path.moveTo(cx, cy + r * 0.50f); path.lineTo(cx - nw, cy); path.lineTo(cx + nw, cy); path.close()
        canvas.drawPath(path, needleS)
        canvas.restore()

        canvas.drawCircle(cx, cy, r * 0.05f, hubPaint)
        canvas.drawCircle(cx, cy, r * 0.05f, hubRing)

        // Calibration hint
        val (txt, col) = when (magAccuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "Calibration: high" to Ui.GREEN
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "Calibration: medium" to Ui.AMBER
            SensorManager.SENSOR_STATUS_ACCURACY_LOW, SensorManager.SENSOR_STATUS_UNRELIABLE ->
                "Calibration low – wave the phone in a figure 8" to Ui.RED
            else -> "" to Ui.DIM
        }
        if (txt.isNotEmpty()) {
            subPaint.color = col
            canvas.drawText(txt, cx, h - Ui.dp(context, 18f), subPaint)
            subPaint.color = Ui.DIM
        }
    }
}
