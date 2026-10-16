# Camera motor control — Rabbit r1 on LineageOS

Status: working (root required) · Updated: 2026-10-15

## Interface

Kernel driver `step_motor_ms35774` exposes:

```
/sys/devices/platform/step_motor_ms35774/orientation
```

Values: `0` = front (faces user/screen), `90` = privacy (parked),
`180` = rear (faces away).

Node owner is `system`, not `shell`, and SELinux blocks unprivileged writes —
a normal app UID cannot drive the motor. This device runs Magisk
(`su` daemon active after first-run setup), so the app uses
`Runtime.exec("su", "-c", "echo …")`.

## Post-boot resync

After a reboot the driver state desyncs from the physical position; the
first single write may not produce motion. A state cycle restores it:

```sh
adb shell 'echo 0 > /sys/devices/platform/step_motor_ms35774/orientation; sleep 1; \
echo 90 > /sys/devices/platform/step_motor_ms35774/orientation; sleep 1; \
echo 180 > /sys/devices/platform/step_motor_ms35774/orientation'
```

`scripts/motor-reset.sh` on the host does the same via `adb root`.

## App integration (camera/CameraActivity.kt)

- `onResume` → motor `180` (rear view by default).
- `onPause` → motor `90` (privacy whenever the camera leaves the foreground).
- FLIP button → toggles motor `0`/`180` **and** rebinds the camera
  selector. One physical camera only (hi846, `Facing=Back` always) — never
  bind `DEFAULT_FRONT_CAMERA`, it throws `No available camera can be found`;
  flipping is motor rotation plus preview/image correction.

## Orientation correction

- The module physically flips, so front mode is 180° rotated. The preview
  must run on a TextureView (`implementationMode = COMPATIBLE`) — a
  SurfaceView ignores `previewView.rotation`.
- Front captures are post-corrected: decode JPEG, `postRotate(180f)`,
  recompress into the same MediaStore URI.

## Camera open quirk

First open after boot can fail with `configureStreams ENODEV` / broken
pipe; CameraX retries or a quick reopen recovers. See
`CAMERA_ISSUE.md` for the firmware story and `scripts/test-camera.sh`.

## Shutter wiring

`SideButtonService` routes a single side-button press to
`CameraActivity.takePhoto()` while the camera is foreground. Long-press in
camera continues to mean nothing (Muse voice capture is suppressed inside
the camera so shutter taps are not misread). Double-press launches the
camera from anywhere.