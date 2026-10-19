package x.vladgba.sensors

import android.app.Activity
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var container: FrameLayout
    private val screens = arrayOfNulls<Screen>(TABS.size)
    private val tabViews = ArrayList<TextView>()
    private var current = -1
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        container = FrameLayout(this)
        root.addView(container, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.PANEL)
        }
        TABS.forEachIndexed { i, name ->
            val tv = TextView(this).apply {
                text = name
                textSize = 14f
                gravity = Gravity.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isClickable = true
                setOnClickListener { select(i) }
            }
            tabViews.add(tv)
            bar.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 56f).toInt()))

        // targetSdk 35 is edge-to-edge: keep content clear of the system bars.
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(root)

        val start = savedInstanceState?.getInt(KEY_TAB) ?: prefs().getInt(KEY_TAB, 0)
        select(start.coerceIn(0, TABS.size - 1))
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun create(i: Int): Screen = when (i) {
        0 -> CompassView(this)
        1 -> LevelView(this)
        2 -> RulerScreen(this)
        3 -> GpsView(this)
        else -> SensorsScreen(this)
    }

    private fun select(i: Int) {
        if (i == current) return
        if (current >= 0) screens[current]?.stop()
        container.removeAllViews()
        val s = screens[i] ?: create(i).also { screens[i] = it }
        container.addView(s.view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        current = i
        if (resumed) s.start()
        tabViews.forEachIndexed { idx, tv -> tv.setTextColor(if (idx == i) Ui.ACCENT else Ui.DIM) }
        prefs().edit().putInt(KEY_TAB, i).apply()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (current >= 0) screens[current]?.start()
    }

    override fun onPause() {
        resumed = false
        if (current >= 0) screens[current]?.stop()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, current)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == GpsView.REQ_LOCATION) {
            val gps = screens[3] as? GpsView ?: return
            if (resumed && current == 3) {
                gps.stop()
                gps.start()
            } else {
                gps.view.invalidate()
            }
        }
    }

    companion object {
        const val PREFS = "pico"
        private const val KEY_TAB = "tab"
        private val TABS = arrayOf("Compass", "Level", "Ruler", "GPS", "Sensors")

    }
}
