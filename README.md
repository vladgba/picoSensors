# picoSensors

Tiny Android sensor toolbox. Kotlin, **no external libraries** — only the Android
platform API and the Kotlin stdlib (no AndroidX, no AppCompat). Package `x.vladgba.sensors`.

## Tools

| Tab | What it does | Gestures |
|---|---|---|
| **Compass** | Rotating dial with heading in degrees + cardinal point. Uses the rotation-vector sensor (falls back to accelerometer + magnetometer). Switches to camera-direction heading when the phone is held upright. Shows magnetometer calibration state. | Tap → magnetic / true north (true north needs a last known location to compute declination) |
| **Level** | Flat on a surface → round bubble with X/Y tilt. Standing on an edge → tube level for that edge, plus a true-horizon line. Green < 0.3°. | Tap → calibrate to current position · long press → reset |
| **Ruler** | Physical-size scales along the top and left screen edges (mm/cm or 1/16 in). Three resizable shapes anchored at the top-left corner: **Line** (two free handles, length, Δx/Δy, angle), **Triangle** (right triangle, legs + hypotenuse, angles, area), **Square** (rectangle with snap-to-square, area, diagonal). | Drag anywhere → nearest handle moves · `mm/in` toggles units · `Cal` → calibrate with a bank card (85.60 mm) |
| **GPS** | Speed with arc gauge, coordinates (decimal / DMS), altitude, accuracy, bearing, satellites used/visible, max speed, trip distance, fix age. | Tap speed → km/h / mph / kn / m/s · tap coordinates → copy + switch format · long press → reset max/trip |

Screen stays on while the app is open; sensors run only for the visible tab.

## Build

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17+, Android SDK 35. minSdk 24, targetSdk 35.
Open the folder in Android Studio, or create `local.properties` with `sdk.dir=...`.

## Structure

```
app/src/main/java/x/vladgba/sensors/
  MainActivity.kt   tab bar + screen host, edge-to-edge insets, permission callback
  Ui.kt             colours, paints, Screen interface, helpers
  CompassView.kt
  LevelView.kt
  RulerScreen.kt    RulerScreen (toolbar) + RulerView (drawing/touch)
  GpsView.kt
```
