# On-device VLM for capture descriptions — device & model research

> Spike, 2026-06-17. Question: can we generate free-form image descriptions for camera
> captures on-device, and on *what* devices? Scope is deliberately small: pick a runtime +
> model and define a device-capability gate, since this flow is compute-heavy.

## Outcome (recommendation)

Ship it as an **experimental, opt-in, downloadable** feature gated to high-end devices:

- **Runtime:** MediaPipe LLM Inference API (`com.google.mediapipe:tasks-genai`), which exposes
  multimodal (image + text) prompting today. It is in maintenance mode (Google is steering new
  work to LiteRT-LM), so treat LiteRT-LM as the eventual migration target, but tasks-genai is the
  pragmatic buildable path now.
- **Model:** Gemma 3n **E2B** (effective 2B, multimodal text/vision) in `.litertlm`/`.task` form.
- **Not shipped in the APK.** Downloaded on first opt-in, like the CLIP models.
- **Device gate:** high-end only. The API is explicitly "optimized for high-end Android devices,
  such as Pixel 8 and Samsung S23 or later" and "does not reliably support device emulators."

## The numbers that drive this

| Fact | Value | Source |
|---|---|---|
| tasks-genai dependency | `com.google.mediapipe:tasks-genai:0.10.27` | MediaPipe Android guide |
| Multimodal API | `GraphOptions.setEnableVisionModality(true)` + `LlmInferenceOptions.setMaxNumImages(n)`; per-turn `session.addQueryChunk(text)` then `session.addImage(MPImage)` | MediaPipe Android guide |
| Gemma 3n E2B download | ~3.1 GB (`.litertlm`) | HF litert-community/gemma E2B |
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

On a non-qualifying device the Generative option stays **disabled** with a reason shown; the app
silently keeps using the offline structured describer.

## Hard constraints / honesty (these block a fully-verified ship here)

1. **Cannot be verified in this environment.** The emulator does not reliably run the API, and
   there is no high-end physical device in the loop. So the plumbing (gate, config, download,
   describer) can be built and compiled, but actual caption generation must be verified on a real
   Pixel 8 / S23-class device before the feature is trustworthy. This is unlike every other
   classification path in the app, which was device-verified.
2. **Model hosting.** The E2B `.litertlm` is ~3.1 GB. GitHub release assets cap at 2 GB, so our
   existing mirror-repo download pattern (used for the ~90 MB CLIP files) will **not** hold it.
   Options: download directly from the official source (HuggingFace `litert-community` / Kaggle),
   which may require auth + Gemma license acceptance, or host the file where >2 GB is allowed.
   This must be resolved before the download flow is real.
3. **License.** Gemma is under Google's Gemma Terms of Use. Redistributing the weights ourselves
   needs that to be honored; pointing at the official download side-steps redistribution.
4. **Battery/heat/latency.** Even on qualifying devices, a caption is ~1 s (flagship GPU) to many
   seconds (CPU). The flow must be explicitly user-triggered or clearly background, never blocking.

## Plan (incremental, what's buildable now vs blocked)

- **Buildable + testable now:** the pure device-capability gate (`DeviceCapability`, unit-tested
  with injected inputs) and the experimental opt-in config (the `descriptionSource = GENERATIVE`
  selector already exists; enable it only when the device qualifies AND the user accepts an
  "experimental" notice). Settings shows why it's unavailable when it is.
- **Buildable, NOT verifiable here:** `GenerativeCaptureDescriber` (tasks-genai LlmInference +
  vision session) and the model download. Compiles and is wired behind the gate, but generation
  is unverified until a real device runs it, and the >2 GB hosting/license question is open.

## Sources

- [LLM Inference guide for Android — Google AI Edge](https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android)
- [Gemma 3n developer guide — Google Developers Blog](https://developers.googleblog.com/en/introducing-gemma-3n-developer-guide/)
- [google/gemma-3n-E2B-it-litert-lm — Hugging Face](https://huggingface.co/google/gemma-3n-E2B-it-litert-lm)
- [SmolVLM 256M & 500M — Hugging Face blog](https://huggingface.co/blog/smolervlm)
- [SmolVLM-500M-Instruct model card](https://huggingface.co/HuggingFaceTB/SmolVLM-500M-Instruct)
