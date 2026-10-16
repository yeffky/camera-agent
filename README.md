# Camera Agent

Android Camera Agent MVP: a native camera app exploring scene-aware framing, camera controls, and color guidance.

## Current status

Stages A–D are implemented: CameraX preview/capture, composition guides, device capability reporting, on-device scene and face analysis, framing guidance, automatic exposure/focus metering suggestions, and local color styles applied after capture. Stage E requires testing on physical phones from multiple manufacturers.

See [docs/MVP_TECHNICAL_DESIGN.md](docs/MVP_TECHNICAL_DESIGN.md) for scope, architecture, milestones, and limitations.
Use [docs/DEVICE_CAPABILITY_MATRIX.md](docs/DEVICE_CAPABILITY_MATRIX.md) to record real-device capability reports.
The cloud Agent design and the user’s next manual acceptance steps are documented in [docs/NEXT_PHASE_CLOUD_AGENT_DESIGN.md](docs/NEXT_PHASE_CLOUD_AGENT_DESIGN.md).
The Agent tab sends one multimodal request only after the user explicitly taps “发送当前画面并获取建议”. Each request includes one in-memory captured JPEG frame (maximum edge 1024 px, compressed size at most 512 KB) plus structured scene/camera data. There is no app-level cooldown; only one request may be in flight. The image is not saved locally and no video or continuous frame stream is sent. Defaults are `https://api.deepseek.com` and `deepseek-flash`; DeepSeek documents image input for this model. Other providers/models must also support OpenAI-compatible image input. Automatic or low-frequency capture is out of scope. Cloud suggestions currently show a confirmation dialog before application; planned behavior is validated direct application with undo. Preset and broader parameter research is in [PHOTO_PRESET_RESEARCH.md](docs/PHOTO_PRESET_RESEARCH.md). The API key and request are sent to the configured HTTPS endpoint. The example configuration is [agent_config.example.json](app/src/main/assets/agent_config.example.json).

## Build prerequisites

- JDK 17
- Android SDK Platform 35
- Gradle 9.6.0 (or open the project in Android Studio with a compatible Gradle installation)

The debug APK builds and has been installed on an Android 37 emulator. Preview, permission denial, exposure control, automatic-mode toggle, and natural/warm photo saving were exercised. Physical-device compatibility across manufacturers remains unverified.

The packaged APK requests `CAMERA` and `INTERNET`; `ACCESS_NETWORK_STATE` is removed. Camera preview/capture and local analysis do not depend on the network. Cloud requests are manual only.

## Verification

Run the build and packaged-permission checks from PowerShell:

```powershell
.\scripts\verify-mvp.ps1 -AndroidSdkRoot $env:ANDROID_SDK_ROOT -BuildOnly
```

With a device visible in `adb devices -l`, run the same script with `-DeviceSerial "SERIAL"` to install the APK, grant camera permission, launch the app, check its process/logcat, and save a screenshot under `build/verification/`. Manual scene, control, permission-denial, and photo checks are listed in [docs/DEVICE_CAPABILITY_MATRIX.md](docs/DEVICE_CAPABILITY_MATRIX.md).
