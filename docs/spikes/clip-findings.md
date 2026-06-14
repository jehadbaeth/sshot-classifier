# Spike: CLIP zero-shot quality on screenshots

> Date: 2026-06-14
> Question: Does CLIP ViT-B/32 produce trustworthy zero-shot tags on real
> Android screenshots, before we build the on-device pipeline?
> Verdict: **Partially. Good for visually distinctive screens, confidently wrong
> on text-heavy and ambiguous UI. CLIP cannot be the sole classifier.**

## Method

- Host-side CLIP ViT-B/32 via `open_clip`, CPU. Host quality is a valid proxy for
  the planned TFLite port (same weights, quantization aside).
- 11 real screenshots captured from the emulator: clock, source code (raw C from
  GitHub), contacts, dialer, files, settings, OpenStreetMap, Wikipedia article,
  and three big-site pages that hit GDPR consent walls (reddit, youtube, yahoo
  finance). The consent walls are realistic text-heavy web screens, kept on purpose.
- Two weight sets (OpenAI, LAION-2B), with 4-template prompt ensembling.
- Two label sets: the design's 15-label taxonomy, and an expanded set adding
  concrete utility-app labels (clock, contacts, dialer, file manager, settings).
- Script: `spikes/clip/clip_zeroshot.py`. Weights per image are softmax over
  cosine similarity at the model's logit scale (~temperature 0.01), as designed.

## Results (top-1, design taxonomy, with prompt ensembling)

| Screenshot | Human label | OpenAI | LAION-2B |
|---|---|---|---|
| map (OpenStreetMap) | map | **map 0.91** | **map 1.00** |
| source code | code editor | **code editor 0.63** | **code editor 0.98** |
| wikipedia (article on receipts) | document | receipt 0.97 ✗ | **document 0.89** |
| settings | other | **other 0.47** | **other 0.72** |
| contacts | contacts/other | other 0.31 ~ | document 0.48 ~ |
| dialer | other/phone | other 0.42 ~ | chat 0.72 ✗ |
| social (reddit) | social media | **social media 0.36** | error/crash 0.41 ✗ |
| youtube (consent wall) | video/web | **video 0.55** | error/crash 0.61 ✗ |
| yahoo (consent wall) | finance/web | shopping 0.22 ✗ | news 0.88 ~ |
| clock | other/clock | video 0.36 ✗ | code editor 0.65 ✗ |
| files (empty Downloads) | document/other | shopping 0.53 ✗ | shopping 0.41 ✗ |

With concrete labels available (expanded set), LAION nailed the utility apps:
contacts 0.96, settings 0.99, dialer→contacts 1.00 (still confused with contacts),
but clock still went to code editor 0.53 and files to settings 0.44.

## What this tells us

**CLIP is strong where the screen looks like a natural image or has a distinctive
visual signature.** Maps were near-perfect on both models. Source code was reliable
with prompt ensembling (and excellent on LAION, 0.98). This is the good news, and
it also means CLIP stays valuable for semantic *search* over visual concepts.

**CLIP is confidently wrong on text-heavy and ambiguous UI.** The dangerous part is
not that it errs, it is that it errs at high confidence:
- clock screen → video/streaming or code editor (0.36 to 0.65)
- empty file manager → shopping (0.41 to 0.53)
- an article whose topic is "receipts" → receipt 0.97 on OpenAI (topical leakage,
  not actual layout understanding)
- web consent dialogs → error/crash, news, shopping, scattered

Because these wrong answers carry 0.5 to 0.97 weight, **a confidence floor does not
protect against them.** The design's "primary weight >= 0.4 else uncategorized"
rule would happily auto-file a clock screen as "video" and a file manager as
"shopping."

**Neither weight set wins outright.** LAION is better overall (code 0.98, wikipedia
correct, utility apps excellent with concrete labels) but invents its own confident
errors (clock→code, reddit/youtube→error). Use LAION-2B as the default, but do not
treat its confidence as truth.

**Prompt ensembling and concrete labels both help a lot.** Single abstract prompts
under-sell CLIP. A granular, concrete internal label set beats abstract buckets.

## Impact on the design

This does not kill the design, but it reshapes one core assumption. The design
treated CLIP as the primary classifier with OCR mainly for search. That is wrong.

1. **Demote CLIP from sole classifier to one signal.** Keep it as the primary
   signal for visually distinctive categories (map, photo-like, game, video) and as
   the engine for semantic search. Do not let it auto-file text-heavy screens alone.
2. **Promote OCR to a co-classifier.** Most failure cases (code vs error vs
   document, clock, finance vs news, file manager) are trivially separable by text
   content and keyword/heuristic rules. Fuse OCR-derived signals with CLIP for
   tagging, not just for search.
3. **Gate on margin, not absolute confidence.** Use top1-minus-top2 margin and
   OCR agreement. Route low-margin or OCR-contradicted predictions to "needs
   review" / "other" instead of auto-filing.
4. **Default to LAION-2B ViT-B/32 + prompt ensembling + a concrete label set.**
   Map user-facing tags onto finer internal labels.
5. **Follow-up worth its own spike:** a UI-domain model (SigLIP, or CLIP fine-tuned
   on mobile-UI datasets like RICO / Screen2Words) would likely beat generic CLIP on
   exactly the screens where it fails here. Costs more size/complexity on device.

## Caveats

- Small set (11 images), skewed toward utility/document screens; no game, calendar,
  real receipt, or real shopping page (those needed accounts or hit consent walls).
- Consent walls stood in for news/finance/social, which muddies those three rows.
- Host ViT-B/32 ignores TFLite quantization effects (likely a small extra quality
  hit on device). Separately, sourcing a good TFLite CLIP port is still an open
  risk not tested here.
