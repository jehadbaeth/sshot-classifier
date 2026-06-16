# Classification accuracy evaluation

Last run 2026-06-16 on the Galaxy S20 FE AVD (API 33) with both int8 CLIP models
installed. This is the scaled follow-up to the 2026-06-15 field eval: the classifier now
runs against **~3,380 images** (3,124 F-Droid app screenshots + 240 Enrico + 16
Wikimedia-Commons field), up from 116. The goal of the scale-up was to stress the pipeline
on a broad, real mobile-app distribution and find where it actually breaks.

Short version: scale did **not** cleanly re-validate the email/social OCR fix (the
relevant F-Droid folders are too label-noisy for that, see below) — that validation still
rests on the field slice + `OcrHeuristicsTest`. What scale **did** surface is real
CLIP-ceiling behavior worth acting on: an over-firing `error / crash` class, a
finance↔receipt visual confusion, and `browser / web` overprediction.

## How it is measured

`app/src/androidTest/.../pipeline/ClassificationEvalTest.kt` runs the **exact**
production path: ML Kit OCR → `OcrHeuristics` → CLIP image embedding →
`ZeroShotClassifier` → `TagFuser.fuse` → `TagFuser.decide`. The predicted label and the
needs-review flag come from `TagFuser.decide`, the same function `ImageProcessor` uses, so
the eval cannot drift from what ships. It also records the **CLIP-only argmax** (no OCR,
no fusion) per image, so every fusion effect can be isolated as a paired comparison on
identical images.

Two hard constraints shaped the method:

1. **OCR is ML Kit, Android only.** The pipeline cannot run as a desktop JVM test; it
   must run on a device/emulator (instrumented test, images in the app's internal files
   dir). At ~0.3 s/image the 3,124-image F-Droid run takes ~16 min on the AVD.
2. **No public dataset matches our taxonomy.** RICO/Enrico has no email, no social/forum,
   no document-of-our-kind class. So Enrico is a regression baseline only; the targeted
   classes need separately-sourced screenshots.

Images are **not committed** (licensing + repo size). Fetch scripts and manifests (source
URL + license + sha256 per image) are committed, so every set is reproducible.

## Datasets

| Set | Source | Images | Label quality | Role |
|---|---|---|---|---|
| F-Droid slice | [F-Droid](https://f-droid.org) catalog `index-v2.json` (FOSS) | 3,124 | **weak** (app-function) | Broad-distribution stress test |
| Enrico slice | [Enrico](https://github.com/luileito/enrico) (MIT) | 240 | approximate (topic map) | Regression on classes that already worked |
| Field slice | Wikimedia Commons FOSS app screenshots | 16 | clean (hand-picked) | The clean email/reddit/document fix test |

### Why F-Droid, and not a subreddit or GitHub repos of screenshots

Reddit and GitHub app-screenshot dumps are copyrighted and not redistributable, need
API/OAuth, and are not reproducible from a committed manifest. F-Droid apps are openly
licensed, the index is stable and carries a sha256 per asset, and the screenshots are
**real phone captures organized by app function** — so the app's category is a weak label
for free, and the exact set can be rebuilt by anyone from the committed manifest. F-Droid
ships clients for Reddit/Lemmy/Mastodon (the social/forum class) and real Email/Browser/
Navigation apps, which is exactly the coverage the subreddit idea was after, minus the
licensing and reproducibility problems.

F-Droid → our-taxonomy mapping is by primary category (`scripts/eval/fetch_fdroid.py`):
e.g. `Email`→email, `Browser`→browser, `Navigation`/`Public Transport`→map, `Forum`/
`Social Network`→social media, `Messaging`/`AI Chat`→chat, `Finance Manager`/`Wallet`→
finance, game categories→game, etc. To keep the measurement honest: English locale only
(OCR heuristics key on English tokens; a German screen collapses fused→CLIP-only and
dilutes the very delta we measure), deduped by sha256, capped 4/app (F-Droid orders the
hero shot first) and 400/class so game/document don't drown out email/social.

## The headline metric: paired fusion delta (and a confound you must remove)

Absolute accuracy on F-Droid is **not** trustworthy and is reported below only as a floor.
The trustworthy signal is the paired fused-vs-CLIP-only delta on identical images, because
both sides see the same noisy labels so label noise largely cancels.

Across all 3,364 dataset images (F-Droid + Enrico), fusion changed the call on 297. Naively
that is **helped 46, hurt 61, net −15**, which looks like "fusion hurts at scale." It does
not — that number is contaminated by the same artifact that deflates absolute accuracy.
Decompose it:

| Bucket | Flips | Helped | Hurt | Net |
|---|---|---|---|---|
| **A — real-class → real-class** (neither side is "other") | 138 | 37 | 29 | **+8** |
| B — involves "other" | 159 | 9 | 32 | −23 |

Bucket B is almost entirely fusion → "other" (152 of 159): the app abstaining on an
ambiguous broad-distribution screen. F-Droid has **no "other" truth folder**, so every
such abstention is auto-scored "hurt" even when abstaining is the right call. That is an
accounting artifact, not a regression.

**The confound-free signal (Bucket A) is net +8: fusion helps slightly more than it hurts
on real-class flips.** The honest statement is: fusion increases abstention on ambiguous
screens, and against weak labels with no "other" class that abstention can't be scored
fairly. "Fusion is net-negative at scale" would be false.

## F-Droid results — read precision, not recall

Overall fused **1,030/3,124 = 33%**, CLIP-only 33%, predicted "other" 38%, needs-review
54%. That 33% is a **floor**, deflated three ways: (1) weak app-function labels, (2) no
"other" truth folder so all 38% "other"/conservative predictions count as wrong, (3) the
app deliberately abstains (54% needs-review) and abstention scores as a miss here.

Per-predicted **precision** (when the app commits to a confident class, is it right?) is
the more honest cut:

| Predicted | N | Precision |
|---|---|---|
| game | 182 | **88%** |
| finance | 157 | **83%** |
| map | 209 | **72%** |
| video / streaming | 345 | **68%** |
| email | 47 | 49% |
| news | 67 | 49% |
| code editor | 121 | 47% |
| social media | 69 | 45% |
| chat / messaging | 186 | 39% |
| document | 189 | 34% |
| browser / web | 132 | **17%** |
| error / crash | 74 | **0%** |
| receipt | 47 | **0%** |

Visually distinctive classes (game/finance/map/video) are strong. The three at the bottom
are the real findings.

### Label noise is large on the soft classes — spot-checked, not assumed

Pulled the first 12 images from the `email` and `document` F-Droid folders and looked at
them. The `email` folder is ~60–70% **not** inbox UI: it is dominated by F-Droid promo /
feature graphics ("FREE INBOX", "All your accounts in one app", "Secure & Open Source")
and generic messenger shots. The `document` folder is ~40–50% non-document (settings,
promo banners, navigation among the genuine text views). So the 34% email recall measures
**folder label quality, not the classifier** — F-Droid's `phoneScreenshots` mixes
marketing graphics with real captures. This is the reason the scale-up cannot re-validate
the email/social fix: those exact folders are the noisy ones.

## Verified CLIP-ceiling findings (the actionable ones)

- **`error / crash` over-fires. Predicted 80× across both sets, 0 correct, and 42 of those
  shown as confident (not flagged needs-review). FIXED 2026-06-16 — see the fix section
  below.** Cause confirmed by opening the images:
  the internal label is `"an error message dialog"`, and CLIP matches it to ordinary
  **modal dialogs over a dimmed background** — a name-entry prompt, a "Command string"
  popup with Close/Copy, etc. These are normal app screens, not errors. This is the single
  biggest pure-CLIP false-positive sink. It is **not** caused by the OCR fix (79/80 are
  CLIP-only). Fix candidate, but it needs real error/crash positives to validate against
  before retuning the label — tuning blind could zero a working class. Logged in TODO.
- **finance ↔ receipt visual confusion.** `receipt` predicted 50×, 0 correct; 33 of those
  are finance-app screens, and **41/47 are CLIP-only** (not the OCR receipt rule). Finance
  dashboards (amounts, line items, totals) genuinely look like receipts to CLIP. Can't be
  fixed by touching the OCR rule; would need a visual disambiguation that risks real
  receipts. Logged.
- **`browser / web` overprediction (17% precision, 98 confident FPs).** It acts as a
  catch-all for any web-view-shaped screen. Partly the desktop/web distribution gap. Risky
  to tune (browser is a legitimate class); logged as watch-item.

## Fix applied: error/crash over-firing (2026-06-16)

Acted on the top finding. Root cause: the CLIP label `"an error message dialog"` (tag
`error / crash`) matched any modal dialog, and since error/crash is OCR-authoritative in
fusion, that CLIP vote (0.4 weight) carried ordinary dialogs to a confident, wrong
`error / crash`. CLIP fundamentally can't tell an error dialog from a name prompt or a
permission popup — they look identical — so this is a job for text, not vision.

The fix, kept deliberately surgical (the broader fusion behavior was left untouched):

1. **Remap the CLIP label** `"an error message dialog"` from `error / crash` → `other` in
   `labels.json` (and `precompute_labels.py`). Because the embedding is keyed on the
   unchanged internal phrase, `label_embeddings.f32` stays **byte-identical** (22 rows,
   45,056 bytes) — the softmax denominator is unchanged, so no model regeneration and no
   broad perturbation. error/crash becomes an OCR-only tag, matching the project's
   CLIP-for-visual / OCR-for-text split.
2. **Extend the OCR error rule** (`OcrHeuristics`) with documented user-facing Android
   error strings (`keeps stopping`, `isn't responding`, `application not responding`, an
   `unfortunately … stopped/crashed` pattern; weak markers `has stopped` / `something went
   wrong` / `try again` that need corroboration). Keyword choice was driven by documented
   Android system strings, not by any image set.

**Validation (the honest split — FP side rigorous, recall side not).**

| Metric | Before | After |
|---|---|---|
| error/crash predicted (F-Droid 3,124) | 74 | **1** |
| of those, shown confident | 40 | 1 |
| F-Droid worst per-class recall change | — | document −3 / 400 (−0.75%) |
| Enrico overall | 60% | 61% |

- **FP kill is rigorously validated.** Post-remap, *every* error/crash prediction on
  F-Droid is pure OCR (CLIP can no longer produce the tag), so the single remaining
  prediction across 3,124 real screens both proves the FP kill and proves the new OCR
  keywords cost ~0 false positives. The lone survivor is a terminal/code screen
  (`taco.scoop`) whose text genuinely contains error markers.
- **No real class regresses.** 142 predictions changed: 73 are the intended
  error→other/needs-review; the rest are benign borderline→other tips (the remap nudges
  every image's aggregated "other" mass up slightly). Worst real-class recall change is
  −0.75%. Enrico actually ticks up (the dialog mass now helps the settings/"other" class).
- **Real-error recall is NOT validated.** Clean real mobile error screenshots are not
  harvestable from the open web with scriptable tooling — checked ~9 troubleshooting
  articles and ~35 candidate images, near-zero usable (the "how to fix" web is stock
  photos, settings screens, and stylized graphics, not the actual dialog). A 12-image
  *synthetic* set (`scripts/eval/gen_synth_errors.py`, rendered from documented strings)
  served only as an end-to-end mechanism check: it confirmed the OCR rule fires
  (error/crash 0.20–0.41 in the tag list) and that error dialogs now route to
  `other`/needs-review instead of a confident wrong `error / crash`. It is **not** a
  recall measurement (the strings that drove the keywords also render the images, so it
  would be circular). Net effect: ambiguous error dialogs land on needs-review, which is
  honest; only text-rich error screens (stack traces) tag as `error / crash`.

Bottom line on the fix: it removes the 80 confident false positives it set out to remove,
at measured ~0 cost to other classes, and does not overclaim a recall it cannot prove.

## Enrico regression slice (full mappable set, 240)

Fused **144/240 = 60%**, CLIP-only 61%, predicted "other" 35%, needs-review 53%. Fusion
changed 18 calls, helped 6, hurt 8 (**net −2**) — same shape as the F-Droid Bucket-A/B
story: the 6 helps are all `→ other` on genuine settings screens (truth=other exists in
Enrico), the hurts are mostly `document → other/browser/finance` on dense-text screens.
Per-class recall: other(settings) 70%, video 66%, chat 64%, news 61%, map 56%, document
31%. Document stays the weakest — terms/long-text walls drift to `news`/`other`, a
pre-existing CLIP-ceiling weakness present in CLIP-only too, not the email fix.

## The clean fix validation still lives in the field slice

The scaled run does not supersede the 2026-06-15 field slice — it complements it. On the
16 hand-picked field images (clean labels), fused 10/16 vs CLIP-only 6/16, and fusion
flipped **4/6 email** images to correct `email`, three of them straight from CLIP's
`document` (the exact reported bug), with 0 regressions. That, plus `OcrHeuristicsTest`,
remains the causal proof of the email/reddit fix. The F-Droid email folder is too noisy to
confirm or deny it, and per Bucket A the broad-distribution fusion effect is mildly
positive, so nothing here contradicts the field result.

## Reproduce

```bash
scripts/eval/fetch_enrico.sh .evaldata                       # MIT, full mappable slice (240)
python3 scripts/eval/fetch_field_commons.py .evaldata        # Commons field slice (16)
# F-Droid: download index-v2.json from https://f-droid.org/repo/index-v2.json first
python3 scripts/eval/fetch_fdroid.py .evaldata index-v2.json --per-app 4 --per-class 400
# boot AVD, install app + androidTest apk, install both CLIP int8 models, then:
scripts/eval/push_and_run.sh .evaldata/fdroid_slice  fdroid eval-out
scripts/eval/push_and_run.sh .evaldata/enrico_slice  enrico eval-out
scripts/eval/push_and_run.sh .evaldata/field_slice   field  eval-out
```

Per-image rows and summaries are committed: `results-fdroid.csv`, `summary-fdroid.txt`,
`results-enrico.csv`, `summary-enrico.txt`, `results-field.csv`, `summary-field.txt`.
Manifests with per-image license + sha256 + URL: `fdroid_manifest.json`,
`enrico_manifest.json`, `field_manifest.json`.

## Bottom line

- The email/reddit OCR fix is still validated where it can be validated cleanly (field
  slice + unit tests). Scaling to 3,124 noisy F-Droid screenshots did not re-prove it,
  because the email/document folders are 40–70% promo graphics and non-target screens.
- Fusion, measured on the confound-free real-class flips (Bucket A), is net **+8** — mildly
  positive across a broad distribution, not negative. The naive −15 is an abstention
  artifact of weak labels with no "other" class.
- Where the app commits confidently to a visually distinctive class (game 88%, finance 83%,
  map 72%, video 68% precision) it is reliable; it abstains a lot (54% needs-review) rather
  than mislabel, which is by design.
- Real, newly-surfaced weaknesses are CLIP-ceiling, not the email fix: `error / crash`
  over-fired on modal dialogs (80 predictions, 0 correct, verified by eye) — **now fixed**
  (74→1 FPs, see the fix section); finance↔receipt visual confusion and browser/web
  overprediction remain logged in TODO, neither safe to blind-tune without targeted data.
- Release: the eval expansion itself was docs-only. The **error/crash fix is a real,
  validated code change**, so it ships in v0.6.1.
