# Camera Failure on Rabbit R1 LineageOS — RESOLVED

## Final state
- boot_a + vendor_a + dtbo_a = **v0.8.293 (May 2025, matched trio)**, system = LineageOS 21 GSI (Andy Yan 21-20250621-arm64_bgN)
- Camera: **1 device enumerated** (hi846), sessions stream, PASS
- Motor: works via sysfs (needed resync cycle after boot — driver state desyncs from physical)

## Root causes (two separate bugs)
1. **Camera enumeration failure**: factory-shipped mixed pair (boot v0.8.82 May 9 2024 + vendor Jun 6 2024 OTA) + GSI = MTK HAL builds empty device list, silently. Matched boot/vendor/dtbo from one firmware fixes it.
2. **Camera motor "can't move"**: nothing under LineageOS drives `/sys/devices/platform/step_motor_ms35774/orientation`. Stock RabbitOS app did this. Kernel driver is fine; a value cycle (0→180→90→180) resyncs driver state with physical position, then single writes work.

## Motor control (manual, needs root)
```
# values: 0=Front  90=Privacy(parked)  180=Rear
adb shell "echo 180 > /sys/devices/platform/step_motor_ms35774/orientation"
```
After boot, run one resync cycle if first write doesn't move it:
`for v in 0 180 90 180; do echo $v > .../orientation; sleep 2; done`
App integration needs root shell (Magisk su or adb-shelled helper) — normal apps lack sysfs write perms (SELinux + file perms).

## Known quirks (current setup)
- First camera open after boot can fail with `configureStreams ENODEV/broken pipe` — CameraX auto-recovers; reopen works.
- Occasional mid-stream `ERROR_CAMERA_DISABLED` + `CAMA Lost p1 done` (kernel drops frame-done IRQ) — session re-establishes.
- fastbootd only reachable via ORIGINAL (May 2024) boot ramdisk; under 293 boot `fastboot reboot fastboot` hangs. Recovery pattern: mtkbootcmd.py preloader catch → flash.

## Flash procedure that worked (from bootloop state)
1. `python3 scripts/mtkbootcmd.py FASTBOOT` (catches preloader VCOM 0E8D:2000 during bootloop)
2. fastboot: flash boot → `fastboot reboot fastboot` (fastbootd) → flash vendor → `fastboot reboot bootloader` → flash dtbo + final boot → reboot
3. dtbo MUST match boot (293 boot + old dtbo = bootloop)

## Key files
- scripts/mtkbootcmd.py — preloader FASTBOOT catch (from r1_escape)
- scripts/carve-vendor.py — sparse→raw, LP metadata parse, carve vendor_a from super.img
- scripts/test-camera.sh — enumeration check
- backup/boot_a_current.img, vendor_a_current.img — pre-flash originals (May 2024 boot, Jun 2024 vendor)
- Firmware source: github.com/rabbit-hmi-oss/firmware releases (v0.8.293 zip: boot.img/dtbo.img + super.img containing vendor_a)

## Auto-rotate (diagnosed, not fixed in place)
- **Root cause**: MTK sub-HAL exposes `DEVICE_ORIENTATION` (type 27, handle 0x1b) that never fires under GSI. Android 14 `WindowOrientationListener` binds to it, ignoring the working accelerometer. Classic MTK GSI issue.
- `hals.conf` only lists the HAL library — sensor list is compiled into `android.hardware.sensors@2.X-subhal-mediatek.so`, no config to disable individual sensors.
- Forced rotation WORKS: `accelerometer_rotation=0` + `user_rotation={0,1,2,3}` → display rotates. Sensor-based does not.
- **Fix paths**:
  1. Vendor HAL edit: remove/disable DEVICE_ORIENTATION entry (requires HAL source or binary patch)
  2. GSI framework patch: skip TYPE_DEVICE_ORIENTATION in WindowOrientationListener
  3. Workaround (no rebuild): install rotation manager app (reads accel, sets user_rotation) or build accelerometer rotation service into Muse
  4. Manual: QS rotation-lock tile + `settings put system user_rotation X`

## Community references
- r1_escape #26: camera fix = matched boot+vendor dumps (spgohome)
- r1_escape #56 + device_rabbit_r1 StepMotorControls: motor sysfs interface (0/90/180)
- substrate.dougbelshaw.com/rabbit-r1-android: same GSI working reference on matched stock pair