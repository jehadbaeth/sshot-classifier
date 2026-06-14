# Spike: CLIP image encoder to TFLite (Phase 2 de-risking)

> Date: 2026-06-14
> Question: Can we get a faithful, small-enough on-device CLIP image encoder?
> Verdict: **Yes. int8 weight-only ViT-B/32, 89.5 MB, cosine ≥ 0.998 vs PyTorch.**

## Key decision baked in

Zero-shot tagging against a FIXED taxonomy needs only the IMAGE encoder on device.
Label text embeddings are precomputed offline (prompt-ensembled, LAION-2B) and
bundled as a tiny asset. The text encoder + on-device tokenizer is only needed for
free-text search queries (Phase 3). So Phase 2 ships one converted model.

## Method

- `open_clip` ViT-B/32, LAION-2B (`laion2b_s34b_b79k`).
- Converter: `litert-torch` 0.9.1 (the renamed ai-edge-torch), Python 3.13.
- Validated TFLite vs PyTorch by cosine similarity of image embeddings on the 11
  spike screenshots.
- Scripts: `spikes/clip/convert_image_encoder.py` (f32 + parity),
  `spikes/clip/convert_quantized.py` (int8w / fp16), `spikes/clip/precompute_labels.py`.

## Results

| Build | Size | min cosine | mean cosine |
|---|---|---|---|
| float32 | 351.5 MB | 1.00000 | 1.00000 |
| **int8 weight-only** | **89.5 MB** | **0.9980** | **0.9989** |
| fp16 | 176.2 MB | 1.0000 | 1.0000 |

float32 parity is exact, so the conversion itself is faithful. **int8 weight-only is
the chosen delivery model**: 4x smaller than f32, fidelity high enough that tag
rankings are unchanged.

## Gotchas (for future reference)

- `ai-edge-torch` is renamed to `litert-torch`; the API is `litert_torch.convert(module, args).export(path)`.
- PT2E dynamic int8 (`get_symmetric_quantization_config`) FAILS in the converter on
  the leading patch-embed conv: `stablehlo.uniform_dequantize ... got tensor<768x3x32x32xi8>`.
  Per-tensor and excluding the conv via `set_module_type` did not help. Use the
  generative quant recipes instead: `litert_torch.generative.quantize.quant_recipes`
  (`full_weight_only_recipe`, `full_fp16_recipe`).
- Image input is float32 NCHW (1,3,224,224). open_clip preprocessing: resize shorter
  side to 224 (bicubic), center crop 224, normalize mean
  (0.48145466, 0.4578275, 0.40821073) std (0.26862954, 0.26130258, 0.27577711).

## Delivery

89.5 MB is too big to bundle in the APK (already ~117 MB) or commit to git. Delivered
by download to app internal storage on first launch (design section 9). For dev/test
the model is pushed via adb. Public hosting for the download URL is still an open item
(repo is private). The model file is gitignored (`spikes/clip/out/`).

## Text encoder (Phase 3, 2026-06-14)

Same pipeline for the CLIP text tower (free-text semantic search). Wrap
`model.encode_text(tokens, normalize=False)` in a module so the exported graph
takes int32 token ids `(1, 77)` and returns a 512-d vector
(`spikes/clip/convert_text_encoder.py`).

| recipe | size | min cos vs PyTorch |
|--------|------|--------------------|
| int8 weight-only | 65.2 MB | 0.9993 (mean 0.9996) |
| fp16 | 127.3 MB | 1.0000 |

Shipped int8w to match the image encoder. The token-embedding table (49408 x 512)
dominates the size. sha256 of the shipped file:
`322c79491743246b38e00cbd9ce26cd915d1042ab642602ea9db04da009d52d4`.

Cross-check: single-prompt text embeddings have cosine 0.83-0.95 with the bundled,
prompt-ensembled label embeddings (same image-comparable space), confirming the
text tower lands where the image vectors live.

### Tokenizer

open_clip's `SimpleTokenizer` (byte-level BPE, 49408 vocab, context 77) is ported to
Kotlin (`BpeTokenizer`), proven byte-identical to the Python reference by unit tests.
The vocab is reconstructed from the bundled BPE merges in the exact open_clip order, so
only the merges file is bundled. Gotcha: aapt auto-gunzips and renames any `.gz` asset
at build time, so the merges ship as plain `clip/bpe_merges.txt` (aapt still DEFLATEs
it inside the APK). Cleaning simplification: we skip ftfy/HTML-unescape and only
lowercase + collapse whitespace, which is identity for plain search queries.

### On-device end-to-end (emulator, int8 image + text)

Cross-modal retrieval over 5 distractor images, real Android TFLite runtime:
- "a map of streets and roads" -> map 0.274 (next 0.129)
- "program source code in an editor" -> code 0.234 (next 0.181)
- "a store receipt with prices and total" -> receipt 0.300 (next 0.111)

SELinux note: pushing the model into app internal storage as root mislabels the file's
per-app MLS category after a reinstall; `chcon` it to the app's category (or download it
the normal way) or the app gets `avc: denied { open }`.
