# HoloOS

A gesture-driven holographic interface for Windows and Android, built with Compose Multiplatform.
Today it is driven by mouse and touch; the camera hand-tracker will feed the same `HandState`.

## Run it

**Android Studio (both platforms)**
1. File → Open → `D:\AndroidStudioProjects\HoloOS`, let Gradle sync.
2. Pick a run configuration from the toolbar:
   - **androidApp** → phone or emulator (landscape).
   - **HoloOS Desktop** → the Windows app (runs `:shared:run`).

**Command line (Windows)**
```powershell
.\run-desktop.ps1            # fullscreen
.\run-desktop.ps1 -Windowed  # 1600x900 window
```

## Hand tracking (Windows)

One-time setup (Python venv, MediaPipe, models):
```powershell
powershell -ExecutionPolicy Bypass -File tools\tracker\setup.ps1
```
After that HoloOS starts the tracker automatically. Open **HANDS** on the home screen (or press
`C`) to calibrate: the camera finds your shoulders, you raise an open hand, then pinch at five
targets from a relaxed position. Calibration is saved in `%USERPROFILE%\.holoos`.

Privacy: the tracker sends only landmark coordinates over localhost. No video, no face, nothing saved.
Set `HOLOOS_TRACKER=off` to disable the camera.

| Gesture | Action |
| --- | --- |
| Point | Move the cursor |
| Pinch (quick) | Select / open |
| Pinch + move | Drag / spin (globe, city) |
| Pinch with both hands | Zoom in / out |
| Open palm, swipe left | Back |
| Fist, hold ~1 s | Home |
| Thumbs up, hold | Accept the predicted target |

## Phone as the camera (Android, USB)

Install the app on the phone (Android Studio, `androidApp`), open **HoloOS Sensor**, prop the
phone facing you, and start HoloOS on the laptop. The laptop sets up `adb reverse` over the
cable, switches to the phone automatically and stops the webcam tracker. The phone runs
MediaPipe on its GPU and sends only landmarks; its screen shows a skeleton, never the image.

## Controls

| Action | Mouse / keyboard | Touch |
| --- | --- | --- |
| Dive into a hologram | Click, or scroll up over it | Tap, or pinch out over it |
| Rise one level | Scroll down on empty space, `Esc`, or ◄ BACK | Pinch in, back gesture |
| Home | `H` | — |
| Toggle prediction | `P` | — |
| Spin the globe | Drag | Drag |
| Quit (fullscreen) | `Alt+F4` | — |

## Layout

```
shared/src/commonMain/kotlin/com/zain/holoos/
  core/      HoloOsApp (root, input, backdrop, overlay), HoloState (navigator, particles), Hud
  input/     HandState + MotionPredictor + IntentModel, HoloTarget registry
  holo/      palette, additive glow primitives
  apps/      Home (arc reactor), Files (nested holograms), Calculator, Globe
  fs/        file source: real disk on desktop, demo drive on Android
shared/src/desktopMain   Window entry point, cursor hiding
androidApp/              Android activity
```

## How the "instant" feel works

- `MotionPredictor` extrapolates the hand ~110 ms ahead from smoothed velocity and acceleration.
- `IntentModel` turns distance and heading into a probability per target (softmax). Targets above
  the threshold pre-charge in amber before the hand arrives.
- All per-frame values are float state read only in draw blocks, so animation never recomposes.
- Navigation is semantic zoom: each screen grows out of the element it was opened from.
