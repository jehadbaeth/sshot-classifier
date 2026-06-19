# Screenshot Classifier

[![CI](https://github.com/jehadbaeth/sshot-classifier/actions/workflows/ci.yml/badge.svg)](https://github.com/jehadbaeth/sshot-classifier/actions/workflows/ci.yml)

## What this app is for

Everyone screenshots constantly — receipts, maps, code snippets, articles, error messages,
chat conversations, QR codes. The screenshots pile up until the device is full and finding
anything means scrolling back months hoping you remember roughly when you took it.

**Screenshot Classifier** is an on-device Android app that watches your screenshot folders,
reads and classifies every new image automatically using machine learning, and lets you find
things again through either free-text search (matching words the app read inside the image)
or visual concept search (matching what a screenshot *looks like*, even when no relevant text
appears in it at all).

The design principles are:

- **Fully offline.** All classification, tagging, and search happen on device. No image, no
  extracted text, and no embedding ever leaves the phone. The only network use is a one-time
  download of the two CLIP models on first launch, and the entirely optional, off-by-default
  QR link-preview resolver.
- **Tags, not folders.** Each screenshot gets weighted tags — it might be 70% `receipt` and
  30% `finance` — so the same image is findable under multiple concepts without being moved
  or copied anywhere.
- **Honest uncertainty.** When the classifier is not confident it says so (a "Needs review"
  flag), rather than attaching a confident wrong tag. You fix those manually; the app does
  not pretend.
- **Non-destructive by default.** Files are never moved or deleted without an explicit user
  action and, in the case of deletion, a system confirmation dialog.

Beyond screenshots the app includes an in-app camera so you can photograph real-world things
(storefronts, street signs, business cards, menus, QR codes) and search them alongside your
screenshots — same pipeline, same gallery, same tags.

See the [usage guide](docs/usage.md) for a screen-by-screen walkthrough,
[docs/design.md](docs/design.md) for the architecture and all design decisions, and
[docs/eval/performance-and-accuracy.md](docs/eval/performance-and-accuracy.md) for the
measured classification accuracy with honest caveats.

---

## Status

Latest release: **v0.9.26**.

### What is shipped

| Area | Detail |
|---|---|
| Screenshot tagging | CLIP ViT-B/32 zero-shot image classifier fused with ML Kit OCR heuristics. ~16 built-in tags (receipt, code editor, map, finance, chat, browser, video, game, …). Margin-gated: low-confidence results surface as "Needs review" rather than attaching a wrong tag. |
| Visual + text search | Hybrid search fuses CLIP semantic similarity (free-text concept queries) with FTS4 full-text search (words extracted from images by OCR) via reciprocal rank fusion. Both models required for visual search; OCR-only text search works without them. |
| Custom categories | Define your own visual concept (e.g. "boarding pass"). The text encoder embeds it on device and scores it against your library independently, without touching the built-in softmax. |
| Gallery | Staggered timeline grid with date headers, tag-chip filtering, multi-tag intersection filter, sort by newest/oldest/recently tagged, multi-select with bulk tag-add, pull-to-refresh, scroll-to-top, swipe left/right between images in the detail view. Search bar lives in the gallery (no separate tab). |
| Near-duplicate detection | Union-find grouping by CLIP cosine similarity ≥ 0.96. Surfaces visually near-identical images side by side; nothing is deleted automatically. |
| Camera capture | In-app CameraX camera writes photos to `Pictures/ScreenshotClassifier/Captures`. QR/barcode decoding is on-device (ML Kit). Real-world tags (storefront, street sign, business card, menu, poster, advertisement, product, qr code) scored separately from the screenshot classifier so the screenshot eval is preserved by construction. |
| QR link preview | Off by default. When enabled, a scanned URL can be resolved to an OpenGraph preview card (title, description, og:image). Manual-per-tap by default; optionally automatic, Wi-Fi-only, and with image loading each a separate toggle. |
| Generative captions | Experimental. An on-device vision-language model (MediaPipe LLM Inference + Gemma 3n E2B, ~3.1 GB, user-imported) writes free-form descriptions of camera captures on high-end phones (Pixel 8 / Galaxy S23 class). Gated behind device capability check, an explicit opt-in, and a model import step. Falls back to a deterministic structured description on any error or under-spec device. **Not verified on hardware by us yet.** |
| Tag backup | Export and import your manual tags and custom categories as JSON via the system file picker. Survives reinstall; re-attaches by image content hash. |
| Watched folders | Configurable list of folders monitored for new images. Settings shows them sorted and collapsed, with a filter field. |
| Reorganization | Optional copy (or move) of files into `Pictures/<root>/<tag>/` albums. Configurable root name, copy-vs-move, needs-review handling, and auto-run after each scan. Move is undoable. |
| Arabic OCR | Tesseract `ara+eng` engine for mixed-script images. Four modes: Latin only, Arabic and mixed, Latin + Arabic max, Auto. Re-run on existing images on demand. |
| Theming | Material You (wallpaper-based, default), Brand blue, Indigo & amber, Teal & coral. |
| Animations | Grid item placement, image crossfade, tab crossfade transitions, detail screen entrance, haptic feedback on long-press and capture. |
| Developer mode | Force-enable the generative path on under-spec devices for testing (may be slow or crash; still falls back). Export the app's own logcat for bug reports. |
| Model delivery | CLIP image encoder (~90 MB) and text encoder (~65 MB) hosted on a public mirror repo, downloaded on first launch with sha256 verification. Never bundled in the APK. OCR works immediately without them. |

### Known limitations

- Real-world CLIP classification accuracy (storefront, street sign, etc.) has not been
  validated on a labeled dataset. The screenshot classifier accuracy is measured and documented.
- Confident-class precision on screenshots: game 88%, finance 83%, map 72%, video 68% (see the
  accuracy report). The app abstains rather than mislabel for classes where it is not confident.
- Social-media and document/dense-text classification are CLIP-ceiling limited. A fine-tuned
  UI-domain model is the real fix, deferred.
- The generative VLM caption path has not been verified on real hardware by us. A checklist for
  user verification is in `docs/spikes/vlm-device-research.md`.

---

## Requirements

- JDK 17
- Android SDK (platform 35, build-tools 35.0.0, platform-tools)
- For emulator testing: the `emulator` package and a system image
  (`system-images;android-35;google_apis;arm64-v8a` on Apple Silicon)

The build uses the Gradle wrapper, so no global Gradle install is needed.

---

## Download a prebuilt APK

Each `v*` tag publishes a [GitHub Release](https://github.com/jehadbaeth/sshot-classifier/releases)
with a release-signed APK attached. Enable "install unknown apps" on the device to sideload it.

---

## Build

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. For a minified release build (the only build
that exercises R8 — always test this before tagging a release):

```bash
./gradlew assembleRelease
```

See [docs/publishing.md](docs/publishing.md) for signing setup and Play Store notes.

---

## CI and releases

- `.github/workflows/ci.yml` builds the debug APK and runs unit tests on every push and PR
  to `main`, and uploads the APK as a build artifact.
- `.github/workflows/release.yml` runs on a `v*` tag, builds a minified release APK and AAB,
  and creates a GitHub Release with the APK attached.

To cut a release: `git tag v0.9.26 && git push origin v0.9.26`.

---

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

A Galaxy S20 FE-shaped AVD (`galaxy_s20fe_api33`, API 33) is also used for instrumented
tests involving the real CLIP models. Environment setup and gotchas are in the assistant
memory files.

---

## Project layout

```
app/src/main/java/com/okapiorbits/sshotclassifier/
├── data/
│   ├── db/              Room database, entities, DAOs, FTS4 virtual tables
│   ├── media/           MediaStore scanning, content hashing
│   ├── prefs/           DataStore-backed preference stores
│   └── repository/      ScreenshotRepository, SearchFusion, EmbeddingCache
├── di/                  Hilt modules
├── diagnostics/         DebugLogExporter
├── pipeline/
│   ├── clip/            ClipEncoder, ClipModelManager, ZeroShotClassifier, BpeTokenizer
│   ├── ocr/             OcrExtractor, OcrHeuristics
│   ├── search/          SemanticSearcher, HybridSearcher
│   ├── tagging/         TagFuser, CustomCategoryScorer, DuplicateFinder
│   ├── vlm/             GenerativeCaptureDescriber, VlmModelManager, DeviceCapability
│   └── reorg/           ScreenshotOrganizer, FileReorganizer
├── ui/
│   ├── camera/          CameraCaptureScreen, CameraCaptureViewModel
│   ├── detail/          ScreenshotDetailScreen, ScreenshotDetailViewModel
│   ├── gallery/         GalleryScreen, GalleryViewModel
│   ├── settings/        SettingsScreen, SettingsViewModel (collapsible card sections)
│   └── theme/           AppTheme, Color, Shape
├── MainActivity.kt
└── ScreenshotClassifierApp.kt
```
