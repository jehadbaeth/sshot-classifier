# On-device VLM for capture descriptions — device & model research

> Spike, 2026-06-17. Question: can we generate free-form image descriptions for camera
> captures on-device, and on *what* devices? Scope is deliberately small: pick a runtime +
> model and define a device-capability gate, since this flow is compute-heavy.
>
> **Status 2026-06-17: BUILT behind the gate, UNVERIFIED on hardware.** The runtime, describer,
> router, device gate, and user-import flow are all implemented and the app builds. Caption
> generation has NOT been run on a real device by the author (it cannot run on the emulator). A
> high-end device is available to the user; see the verification checklist at the end.

## Outcome (recommendation)

Ship it as an **experimental, opt-in, downloadable** feature gated to high-end devices:

- **Runtime:** MediaPipe LLM Inference API (`com.google.mediapipe:tasks-genai`), which exposes
  multimodal (image + text) prompting today. It is in maintenance mode (Google is steering new
  work to LiteRT-LM), so treat LiteRT-LM as the eventual migration target, but tasks-genai is the
  pragmatic buildable path now.
- **Model:** Gemma 3n **E2B** (effective 2B, multimodal text/vision) as a `.task` bundle (this is
  the format the MediaPipe LLM Inference API loads; `.litertlm` is the *future* LiteRT-LM runtime's
  format, a different code path — do not confuse them).
- **Not shipped in the APK, and not hosted by us.** The user imports a model file they downloaded
  themselves (see Delivery below). This differs from the CLIP models, which we mirror, because the
  3.1 GB size and Gemma's gated licence rule out our mirror.
- **Device gate:** high-end only. The API is explicitly "optimized for high-end Android devices,
  such as Pixel 8 and Samsung S23 or later" and "does not reliably support device emulators."

## The numbers that drive this

| Fact | Value | Source |
|---|---|---|
| tasks-genai dependency | `com.google.mediapipe:tasks-genai:0.10.27` | MediaPipe Android guide |
| MPImage/BitmapImageBuilder | `com.google.mediapipe:tasks-core:0.10.28` (genai does NOT bundle them; tasks-core is pure-Java, no extra native libs) | verified against the AAR |
| APK size cost (measured) | universal debug APK **+~52 MB** (one `libllm_inference_engine_jni.so` per ABI: arm64 12.8 MB, the rest other ABIs); per-arm64-device via AAB split ≈ +12.8 MB | local `assembleDebug` before/after |
| Multimodal API | `GraphOptions.setEnableVisionModality(true)` + `LlmInferenceOptions.setMaxNumImages(n)`; per-turn `session.addQueryChunk(text)` then `session.addImage(MPImage)`; `LlmInference`/`LlmInferenceSession` are `AutoCloseable` | verified against the AAR + Android guide |
| Gemma 3n E2B download | ~3.1 GB (`.task`) | HF litert-community/Kaggle, gated |
| Gemma 3n E2B peak memory | ~5.9 GB | HF model card |
| Throughput, mid-range CPU | ~2–5 tokens/s → a 30–50-token caption ≈ **10–25 s** | community benchmarks |
| Throughput, flagship GPU (OpenCL) | ~52 tokens/s → a caption ≈ **~1 s** | LiteRT-LM benchmark (S26 Ultra) |
| Official device guidance | Pixel 8 / Samsung S23 or later; emulators not reliably supported | MediaPipe Android guide |
| Smaller alt: SmolVLM-256M / 500M | <1 GB / ~1.23 GB RAM | SmolVLM model cards |

## Why Gemma 3n E2B over the smaller SmolVLM

SmolVLM-256M/500M are far lighter (<1.5 GB RAM, mid-range feasible) and would widen device
reach a lot. But there is **no turnkey Android runtime** for them: you would need a llama.cpp /
GGUF JNI bridge (multimodal mmproj) or a custom LiteRT conversion + tokenizer + decode loop —
much more integration, and still not verifiable on the emulator. Gemma 3n via MediaPipe is the
only path with an official, documented Android multimodal API. We accept "high-end only" as the
cost, which is exactly why this is gated + experimental. SmolVLM stays a future option if we add
a lighter runtime.

## Device-capability gate (what "can run it" means)

A device qualifies only if **all** hold:

1. `arm64-v8a` in `Build.SUPPORTED_ABIS` (these builds are arm64-only).
2. Total RAM ≥ ~6 GB hard floor; ~8 GB recommended (E2B peak ~5.9 GB + OS headroom). Read via
   `ActivityManager.MemoryInfo.totalMem`.
3. `!ActivityManager.isLowRamDevice()`.
4. Not an emulator (the API does not reliably run there; also our own honesty: we can't claim it
   works where it isn't supported).

On a non-qualifying device the Generative option is **disabled by default** with the reason shown,
and the app keeps using the offline structured describer. **Developer mode** (Settings → Developer,
added v0.9.1) overrides this: a tester can turn it on to force-enable the import + generative path on
an under-spec device (with a "may be slow or crash" warning), so the real limits can be measured
empirically rather than assumed. The gate is then advisory: `shouldUseGenerative` allows the path
when `deviceCapable || devModeForced`, still falling back to structured on any failure. Developer
mode also exposes a debug-log export (own-process logcat) for reporting how a device behaved.

## Delivery: why the model is user-imported, not downloaded by the app

Decided with the user 2026-06-17, after ruling out every hosting path we control:

- **GitHub — no.** Release assets cap at 2 GB (the model is 3.1 GB); Git LFS free tier has a
  1 GB/month bandwidth quota that a single download would blow; a raw >100 MB blob is rejected.
  Our existing CLIP mirror-repo pattern (for the ~90 MB files) simply cannot hold this.
- **A hardcoded no-auth HuggingFace link — no, for Gemma.** Gemma 3n is a *gated* model: the file
  only resolves for a logged-in user who has accepted Google's licence. An unauthenticated fetch
  returns 401/redirect, not the weights. A non-gated community mirror exists but is legally grey
  (it sidesteps the licence) and can vanish; we won't build the shipped feature on one.
- **An ungated model (SmolVLM, Apache-2.0) — would allow a direct link, but has no turnkey Android
  runtime** (see below), so it's a much larger build, not a quick swap.

**Decision (v0.9.0): user-provided via SAF.** The user downloads the `.task` from its official source
and imports it with the system file picker. `VlmModelManager.importFrom` streams it into app-internal
storage (`.part` temp + rename) and validates the size.

**Update (v0.9.5): in-app download from a public community mirror, sha256-verified.** Re-checked the
ecosystem at the user's request: the only loadable `.task` on the official source
(`google/gemma-3n-E2B-it-litert-preview`) is `gated = manual` (401 without a token); the ungated
copies (unsloth/ggml/onnx) are GGUF/ONNX which MediaPipe can't load. BUT several community re-hosts
carry the genuine `.task` ungated and serve it with no auth (HF public CDN, `user_id=public`),
byte-identical across mirrors (sha256 `a7f544cf…4200`, 3,136,226,711 bytes). So `VlmModelManager.download`
now pulls from one such mirror (`xiaohan1/gemma3n`) with the **sha256 as the trust + integrity anchor**
— a swapped/corrupt file is rejected, never loaded. Gemma's Terms (§3.1) permit redistribution and
impose no gating requirement, so a public re-host is within the licence (pass along terms + NOTICE).
TRADE-OFFS (honest): a community repo can be deleted/renamed (then the download 404s — not under our
control), and the sha proves byte-stability, not that the bytes equal Google's official file (can't
fetch the gated original to compare). The URL is a single constant
(`VlmModelManager.MODEL_URL`) to swap to a self-owned public mirror for durability. SAF import remains
as the fallback. No resume yet: a failed ~3 GB download restarts.

## Hard constraints / honesty

1. **UNVERIFIED on hardware by the author.** The emulator does not reliably run the API and there is
   no high-end device in *our* loop, so caption generation has only been compile- and logic-checked.
   Every other classification path in the app was device-verified; this one is not, until the user
   runs the checklist below. The feature is fenced (gate + opt-in + imported-model + fall back to
   structured on any error) precisely so an unverified path can never corrupt a capture.
2. **License.** Gemma is under Google's Gemma Terms of Use. User-import means the user accepts the
   licence at the official source; we never redistribute the weights.
3. **Battery/heat/latency.** A caption is ~1 s (flagship GPU) to tens of seconds (CPU). The model
   weights are ~3 GB resident, so `GenerativeCaptureDescriber` loads-runs-closes per call (never a
   singleton) and serialises calls with a mutex. Per-call model load is wasteful but safe; captures
   are infrequent. Batch-level loading is a future optimisation, not a correctness issue.

## What was built (2026-06-17)

- **Device gate** — `DeviceCapability` (pure, `DeviceCapabilityTest`×6) + `DeviceCapabilityChecker`.
- **Model manager** — `VlmModelManager`: SAF import (atomic `.part` rename, size gate), install
  state, delete.
- **Describer** — `GenerativeCaptureDescriber` (tasks-genai `LlmInference` + vision session,
  load/run/close, mutex-serialised, null-on-failure) and `StructuredCaptureDescriber` (unchanged).
- **Router** — `CaptureDescriberRouter` (bound as the pipeline `CaptureDescriber`); pure
  `shouldUseGenerative` gate, unit-tested; falls back to structured always.
- **UI** — Settings (capable devices only) model import/replace/remove with progress; the Generative
  radio unlocks only when a capable device has a model imported.
- **Cost** — +~52 MB universal APK (see table). Mitigation noted in TODO: drop x86/x86_64 ABIs from
  release builds (emulator-only, can't run this anyway) to cut ~32 MB.

## Verification checklist (run on a Pixel 8 / Galaxy S23 or newer)

The author cannot run these; the user must. Until step 4 passes, treat generation as unproven.

1. **Get the model (in-app).** Sideload the release APK. Settings → Camera capture → under the
   Generative option tap **Download (~3 GB)** — it fetches the sha256-verified `.task` from the mirror.
   (Or **Import file** to pick a `.task` you downloaded yourself.) On an under-spec device (e.g. a 6 GB
   S20 FE) the controls are hidden until you turn on **Settings → Developer → Developer mode**.
2. **Confirm install.** Wait for the progress bar to finish; confirm it then reads "Model installed
   (~3.x GB)". Keep the screen open during download (no resume yet).
3. **Enable + capture.** Select the **Generative** radio (now enabled). Take a camera capture of a
   real scene (a storefront, a product, a poster).
4. **Verify the caption.** Open the capture's detail screen. Confirm the description is a sensible
   free-form sentence about the image (not the structured "Tag. Text reads: …" form). Try 3–5 varied
   scenes. Note latency per caption.
5. **Verify the fallback.** Remove the model (Settings → **Remove model**); confirm the source
   reverts to Structured and a new capture gets a structured description, no crash.

Report back: does it generate, is it coherent, how slow, any OOM/crash. Then this doc's status flips
from UNVERIFIED to verified (or we adjust).

## Sources

- [LLM Inference guide for Android — Google AI Edge](https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android)
- [Gemma 3n developer guide — Google Developers Blog](https://developers.googleblog.com/en/introducing-gemma-3n-developer-guide/)
- [google/gemma-3n-E2B-it-litert-lm — Hugging Face](https://huggingface.co/google/gemma-3n-E2B-it-litert-lm)
- [SmolVLM 256M & 500M — Hugging Face blog](https://huggingface.co/blog/smolervlm)
- [SmolVLM-500M-Instruct model card](https://huggingface.co/HuggingFaceTB/SmolVLM-500M-Instruct)
