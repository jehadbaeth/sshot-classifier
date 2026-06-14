# Screenshot Classifier

An Android app that watches your screenshot folders, classifies new images with
on-device machine learning, tags them, and lets you find them again through
semantic search (visual concepts and text inside the image). Fully offline. No
backend. Nothing leaves the device.

See [docs/design.md](docs/design.md) for the full design and roadmap.

## Status

Phase 0: project scaffold, Room schema, permissions, MediaStore-backed gallery.
The ML pipeline (OCR, CLIP, semantic search) lands in later phases per the design doc.

## Requirements

- JDK 17
- Android SDK (platform 35, build-tools 35.0.0, platform-tools)
- For emulator testing: the `emulator` package and a system image
  (`system-images;android-35;google_apis;arm64-v8a` on Apple Silicon)

The build uses the Gradle wrapper, so no global Gradle install is needed.

## Build

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

## Run on an emulator

```bash
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
# create an AVD once
avdmanager create avd -n pixel_api35 \
  -k "system-images;android-35;google_apis;arm64-v8a" -d pixel_7
# launch it
$ANDROID_SDK_ROOT/emulator/emulator -avd pixel_api35 &
# install and start
./gradlew installDebug
adb shell am start -n com.okapiorbits.sshotclassifier/.MainActivity
```

## Project layout

```
app/src/main/java/com/okapiorbits/sshotclassifier/
├── data/
│   ├── db/          Room database, entities, DAO
│   ├── media/       MediaStore scanning + content hashing
│   └── repository/  ScreenshotRepository
├── di/              Hilt modules
├── ui/
│   ├── gallery/     gallery grid + viewmodel
│   └── theme/
├── MainActivity.kt
└── ScreenshotClassifierApp.kt
```
