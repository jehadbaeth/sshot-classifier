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
  shown as confident (not flagged needs-review).** Cause confirmed by opening the images:
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
- Real, newly-surfaced weaknesses are CLIP-ceiling, not the fix: `error / crash` over-fires
  on modal dialogs (80 predictions, 0 correct, verified by eye), finance↔receipt visual
  confusion, and browser/web overprediction. All logged in TODO; none are safe to blind-tune
  without targeted positive data.
- No new release: this is a docs/eval expansion with no shipped code change. v0.6.0 already
  satisfied the release goal; cutting another tag for docs alone would be hollow.
