# Screenshot Classifier

[![CI](https://github.com/jehadbaeth/sshot-classifier/actions/workflows/ci.yml/badge.svg)](https://github.com/jehadbaeth/sshot-classifier/actions/workflows/ci.yml)

An Android app that watches your screenshot folders, classifies new images with
on-device machine learning, tags them, and lets you find them again through
semantic search (visual concepts and text inside the image). Fully offline. No
backend. Nothing leaves the device.

See [docs/design.md](docs/design.md) for the full design and roadmap.

## Status

Phase 2 done: on-device CLIP image encoder (TFLite int8, downloaded on first
launch) produces image embeddings and zero-shot tags against bundled label
embeddings, fused with OCR signals behind a margin gate. Phase 1: on-device OCR,
full-text search, OCR tagging, background processing. Phase 0: scaffold, Room,
permissions, gallery. Free-text semantic visual search (CLIP text encoder) is
Phase 3.

The ~90 MB CLIP model is hosted on a public mirror repo
([sshot-classifier-models](https://github.com/jehadbaeth/sshot-classifier-models))
and downloaded on first launch with sha256 verification. Until it is installed the
app tags from OCR text only.

## Requirements

- JDK 17
- Android SDK (platform 35, build-tools 35.0.0, platform-tools)
- For emulator testing: the `emulator` package and a system image
  (`system-images;android-35;google_apis;arm64-v8a` on Apple Silicon)

The build uses the Gradle wrapper, so no global Gradle install is needed.

## Download a prebuilt APK

Each `v*` tag publishes a [GitHub Release](https://github.com/jehadbaeth/sshot-classifier/releases)
with the APK attached. It is debug-signed for sideloading, so enable "install
unknown apps" on the device. It is not a Play Store signed release.

## Build

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

## CI and releases

- `.github/workflows/ci.yml` builds the debug APK and runs unit tests on every
  push and PR to `main`, and uploads the APK as a build artifact.
- `.github/workflows/release.yml` runs on a `v*` tag, builds the APK, and creates
  a GitHub Release with it attached. To cut a release:
  `git tag v0.1.0 && git push origin v0.1.0`.

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
