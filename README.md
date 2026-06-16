# Screenshot Classifier

[![CI](https://github.com/jehadbaeth/sshot-classifier/actions/workflows/ci.yml/badge.svg)](https://github.com/jehadbaeth/sshot-classifier/actions/workflows/ci.yml)

An Android app that watches your screenshot folders, classifies new images with
on-device machine learning, tags them, and lets you find them again through
semantic search (visual concepts and text inside the image). Fully offline. No
backend. Nothing leaves the device.

See the [usage guide](docs/usage.md) for a screen by screen walkthrough,
[docs/design.md](docs/design.md) for the full design and roadmap, and
[docs/eval/performance-and-accuracy.md](docs/eval/performance-and-accuracy.md) for the
measured accuracy and performance (with charts).

## Status

Latest release: **v0.6.1**. Phases 0-4 are complete (scaffold and gallery; OCR and
full-text search; CLIP image encoder with zero-shot tags fused with OCR behind a margin
gate; free-text semantic search via the CLIP text encoder, an on-device BPE tokenizer, and
reciprocal rank fusion; then a Settings tab, manual and custom-category tags, a needs-review
surface, and configurable reorganization into per-tag albums). Recent work since v0.5.0: an
email/reddit OCR fix, configurable multi-folder watching, a new icon and brand, release
signing, a large classification eval, and the error/crash classification fix in v0.6.1.

On classification quality, be realistic: the app is reliable where it commits confidently to
a visually distinctive class (game, finance, map, video all measured at 68-88% precision on
real app screenshots), and where it is unsure it abstains to a "needs review" state by design
rather than attach a wrong tag. Broad-distribution absolute accuracy figures are a weak-label
floor, not a real accuracy. The full measured picture, methodology, and honest caveats are in
the [performance and accuracy report](docs/eval/performance-and-accuracy.md).

The two CLIP models (~90 MB image encoder, ~65 MB text encoder) are hosted on a
public mirror repo
([sshot-classifier-models](https://github.com/jehadbaeth/sshot-classifier-models))
and downloaded on first launch with sha256 verification. Until they are installed
the app tags and searches from OCR text only.

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
