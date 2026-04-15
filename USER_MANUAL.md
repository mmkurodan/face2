# Face Gaze Tracker Demo — User Manual

Version: 1.0

Overview
--------
Face Gaze Tracker Demo (package: com.micklab.face2) is an Android application that uses CameraX and MediaPipe Tasks Vision to estimate where a user is looking (gaze tracking) using the front camera and an on-device landmarker model (app/src/main/assets/face_landmarker.task).

This manual covers prerequisites, building and installing the app, using and calibrating it, and troubleshooting common issues.

Quick Start
-----------
1. Requirements: Android device (API 24+), Android SDK & platform-tools (adb), JDK 17 or Android Studio.
2. Build (project root):
   - ./gradlew assembleDebug
3. Install to an attached device:
   - ./gradlew installDebug
   - or: adb install -r app/build/outputs/apk/debug/app-debug.apk
4. Open the app on the device. Grant CAMERA permission when prompted.
5. Start calibration, look at the on-screen center target, and tap that target to capture calibration. The app then shows "Gaze tracking live".

System Requirements
-------------------
- Android device with camera (front camera recommended).
- Android OS API level 24 or newer (minSdk 24).
- For building: JDK 17, Android SDK (compileSdk/targetSdk 35).
- Android Studio recommended for iterative development.

Building & Installing
---------------------
From the repository root:
- Build debug APK: ./gradlew assembleDebug
- Build and install to the first connected device: ./gradlew installDebug
- Direct install: adb install -r app/build/outputs/apk/debug/app-debug.apk

Using Android Studio:
- Open the project folder in Android Studio, let Gradle sync, then Run on a device or emulator.

Note about emulators: Emulators may not provide accurate front-camera input or the performance needed for gaze tracking. Use a real device for best results.

Permissions & First Run
-----------------------
- The app requires CAMERA permission. On first launch, grant permission via the system prompt.
- If permission is denied permanently, use the "Open app settings" flow in the app or Android system Settings to enable Camera permission.

App Flow & UI Walkthrough
-------------------------
Main screen elements:
- Camera preview: live feed from the front camera.
- Overlay: drawn gaze/landmark indicators (OverlayView).
- Status card: shows initialization, prompts, calibration step position, and tracking state. The resolution selector is also embedded in this upper card. Typical messages:
  - "Initializing camera and face landmarker..."
  - "Ready to start calibration"
  - "No face detected. Center one face in the preview."
  - "Gaze tracking live"
  - "Dwell detected"
- Controls:
  - Resolution selector (640x480, 720p) inside the upper status card
  - Start calibration / Recalibrate button
  - Permission helper buttons (Grant camera permission / Open app settings)

Calibration Instructions
------------------------
Accurate gaze estimation depends on a short calibration step.
1. Hold the phone at a comfortable, typical viewing distance and orientation.
2. Ensure both eyes are visible in the preview and the face is well-lit.
3. Tap "Start calibration" (or "Recalibrate") and then tap the Start button in the popup.
4. The app shows the current calibration step as "Calibration step 1/2" or "Calibration step 2/2" in the upper status card.
5. When prompted, look at the center target with your eyes and tap that target while keeping both your head and the phone as steady as possible. The calibration uses the eye position at the tap timing.
6. The app shows a visual effect and a capture message so you know the tap was accepted, then the status changes to "Gaze tracking live".

Tips for good calibration:
- Keep ambient lighting even (avoid backlight and strong shadows).
- Remove eyeglasses reflections or sunglasses.
- Hold the device steady at the distance you intend to use the app.

Settings & Options
------------------
- Resolution: Choose lower resolution for higher frame rate or battery savings (640x480), or 720p for higher detail.
- Recalibrate: Reruns calibration procedure.

Troubleshooting
---------------
Problem: "Camera permission is required"
- Solution: Tap "Grant camera permission" or open app settings and enable Camera permission.

Problem: "No face detected"
- Solution: Center your face in the preview, ensure both eyes are visible, increase lighting, remove obstructions.

Problem: Poor tracking accuracy
- Solutions:
  - Recalibrate and follow the calibration guidance.
  - Try a different resolution.
  - Make sure camera lens is clean and lighting is sufficient.

Problem: App crashes or cannot open
- Check device compatibility (minSdk 24), ensure you used a compatible JDK and proper Gradle plugin.
- Collect logs with Android Studio Logcat or:
  - adb logcat | grep com.micklab.face2
- Provide logs, steps to reproduce, device model and Android version when filing a bug.

Testing
-------
- Unit tests: ./gradlew test
- Use Android Studio’s instrumentation/test tooling for additional tests if added.

Developer Notes (brief)
-----------------------
- Package: com.micklab.face2
- Model asset: app/src/main/assets/face_landmarker.task (bundled MediaPipe landmarker task file)
- Key libs: CameraX (androidx.camera:camera-*), MediaPipe tasks-vision 0.10.33
- Kotlin jvmTarget: 17; compileSdk/targetSdk: 35; minSdk: 24

FAQ
---
Q: Can I use the back camera?
A: The app is designed for the front camera (strings and UX indicate front-camera flow). Behavior with the back camera is not the primary use-case.

Q: Why does the emulator perform poorly?
A: Emulators often lack accurate camera input and hardware performance required for realtime gaze tracking; prefer a real device.

Support & Reporting Bugs
------------------------
When reporting a bug, include:
- Steps to reproduce
- Device model and Android version
- App build (debug/release) and APK timestamp
- Logcat excerpts

For contributors: open an issue in the project repository and attach logs/screenshots.

License & Acknowledgements
--------------------------
This demo uses MediaPipe Tasks Vision and CameraX. See the repository's LICENSE and build files for third-party license details.

---

If additional screenshots, a quickstart one-page guide, or printable PDF is desired, request and the manual can be exported or expanded.
