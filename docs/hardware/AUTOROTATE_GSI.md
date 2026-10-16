# Auto-rotate on Rabbit r1 / LineageOS GSI — diagnosis

Status: diagnosed, system-level fix deferred · Updated: 2026-10-15

## Symptom

System auto-rotate never triggers, even with `accelerometer_rotation=1`.
Forced rotation (`accelerometer_rotation=0` + `settings put system
user_rotation {0,1,2,3}`) works fine — display, SurfaceFlinger and
WindowManager rotation are all functional.

## Root cause

The MTK sub-HAL exposes a `DEVICE_ORIENTATION` sensor
(`android.sensor.device_orientation`, type 27, handle `0x1b`) that never
fires under this GSI. Android 14's `WindowOrientationListener` prefers
`TYPE_DEVICE_ORIENTATION` over the accelerometer when either is present, so
it binds to the dead sensor (`OrientationSensorJudge mDesiredRotation=-1`
forever) and never consumes the healthy accelerometer stream.

Sensors confirmed active via `dumpsys sensorservice`: ACCELEROMETER,
GYROSCOPE, ORIENTATION, Game Rotation Vector all stream events; only
DEVICE_ORIENTATION has zero history.

## Why it cannot be toggled off locally

`/vendor/etc/sensors/hals.conf` only names the HAL library
(`android.hardware.sensors@2.X-subhal-mediatek.so`); the sensor list is
compiled into the binary. There is no sysfs node and no framework knob to
blacklist one sensor type.

## Paths forward

1. **App-level (implemented):** `core/AutoRotate.kt` streams the
   accelerometer itself and writes `user_rotation`; needs
   `WRITE_SETTINGS` grant (Settings → Installed apps → Modify system
   settings).
2. **GSI framework patch:** make `WindowOrientationListener` skip
   `TYPE_DEVICE_ORIENTATION`. Requires GSI rebuild.
3. **Vendor HAL edit:** remove/disable the DEVICE_ORIENTATION entry.
   Requires HAL sources or a binary patch.
4. **Workaround apps:** any rotation manager app that reads the
   accelerometer and forces orientation.

## Verification commands

```sh
adb shell 'dumpsys sensorservice | grep -A3 DEVICE_ORIENTATION'
adb shell 'dumpsys window | grep -E "mUserRotationMode|mRotation"'
adb shell 'settings put system accelerometer_rotation 0; settings put system user_rotation 1'
```

Note `user_rotation` only takes effect in locked mode
(`accelerometer_rotation=0`); it is ignored by design in FREE mode.