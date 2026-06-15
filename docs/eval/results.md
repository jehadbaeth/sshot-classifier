# Classification accuracy evaluation

Measured 2026-06-15 on the Galaxy S20 FE AVD (API 33) with both int8 CLIP models
installed. This answers the field complaint from 2026-06-14 (Reddit and email
screenshots being classified as "document", and many shots landing on "Other") and
checks that the email/reddit OCR fix (commit `8fa333d`) did not regress the classes
that already worked.

## How it is measured

`app/src/androidTest/.../pipeline/ClassificationEvalTest.kt` runs the **exact**
production path: ML Kit OCR → `OcrHeuristics` → CLIP image embedding →
`ZeroShotClassifier` → `TagFuser.fuse` → `TagFuser.decide`. The "predicted label"
and the needs-review flag come from `TagFuser.decide`, the same function
`ImageProcessor` uses in the app, so the eval cannot drift from what ships.

Two hard constraints shaped the method:

1. **OCR is ML Kit, which is Android only.** The pipeline cannot be evaluated as a
   desktop JVM test; it has to run on a device/emulator. So this is an instrumented
   test, fed images from the app's internal files dir.
2. **No public dataset matches our taxonomy.** RICO/Enrico (the standard mobile-UI
   set) has no email, no social/forum, and no document-of-our-kind class, which are
   exactly the classes the fix targets. So Enrico can only be a regression baseline;
   the fix itself needs separately-sourced screenshots.

Images are **not committed** (licensing + repo size). The fetch scripts and the
manifests with source URLs and licenses are committed, so the sets are reproducible.

## Datasets

| Set | Source | Images | Role |
|---|---|---|---|
| Enrico slice | [Enrico](https://github.com/luileito/enrico) (MIT) | 100 | Regression on classes that already worked |
| Field slice | Wikimedia Commons FOSS app screenshots (CC/GPL/MPL) | 16 | Direct test of the email/reddit/document fix |

Enrico topic → our tag mapping (only confidently-mappable topics): `chat`→chat/messaging,
`maps`→map, `news`→news, `mediaplayer`→video/streaming, `settings`→other, `terms`→document
(full-screen legal text as a genuine-document proxy).

The field slice is **desktop** FOSS app screenshots (Thunderbird/Evolution/KMail,
Diaspora/Friendica, LibreOffice/gedit), because Commons has almost no free-licensed
mobile Gmail/Reddit/Instagram content. It is a small, optimistic, directional proxy,
not a stand-in for a real phone Screenshots folder.

## Results — field slice (the fix)

Overall 10/16 = 63%. Per class:

| Truth | Support | Recall | Reads as |
|---|---|---|---|
| email | 6 | **83% (5/6)** | email |
| document | 5 | **80% (4/5)** | document |
| social media | 5 | 20% (1/5) | browser/web |

- **Email fix is validated.** Email screens land on `email` at strong fused weights
  (0.60–0.63), not `document`. This is the exact failure the user reported, now fixed.
  The single miss (Evolution-on-KDE) is CLIP-dominated ("other" 0.81) and is correctly
  flagged needs-review.
- **Documents are not cannibalized.** Genuine documents still predict `document`
  (4/5); the email fix did not steal them.
- **Social media is weak (1/5), but the sample is the wrong shape, not necessarily
  the fix.** These are desktop FOSS web-social pages (Diaspora/Friendica) that (a)
  visually look like a browser page, so CLIP says `browser/web`, and (b) do not use
  Reddit's vocabulary (upvote/subreddit/`r/`), which is what the OCR social rule keys
  on. The Reddit path is covered by `OcrHeuristicsTest`. Validating broader social
  generalization needs representative mobile social screenshots, which we do not have.
  Logged as a TODO; not "fixed" by tuning against 5 unrepresentative desktop shots.

## Results — Enrico regression slice

Overall 56/100 = 56%; predicted "other" 21%; needs-review 49%. Per class:

| Truth | Support | Recall | Most common prediction |
|---|---|---|---|
| video/streaming | 20 | 70% | video/streaming (14) |
| news | 20 | 65% | news (13) |
| chat/messaging | 11 | 64% | chat/messaging (7) |
| map | 9 | 56% | map (5) |
| other (settings) | 20 | 55% | other (11) |
| document (terms) | 20 | 30% | document (6), news (6) |

Reading this honestly: 56% is mediocre, but Enrico is a **hard out-of-distribution
set** — 2017-era app crawls, partly desktop-shaped, with an approximate topic→tag
mapping. The point of this slice is regression, and the regression signal is clean:

- The email/social fix is **well contained**. Across 100 images there are only 3
  stray email/social predictions total (chat→email 1, chat→social 1, other→social 1)
  and **zero** document→email or document→social. The fix did not bleed into the
  working classes.
- The weakest class is `document` (terms-of-service walls of text often read as
  `news`). This is a pre-existing CLIP-ceiling weakness on dense text, **not** caused
  by the email fix (these screens have no email/reddit markers). Not chased here to
  avoid overfitting to one proxy.

## Taxonomy gap vs CLIP ceiling

- **Fixed by taxonomy/OCR work:** email (was the reported bug) now resolves correctly.
- **CLIP ceiling, text-heavy:** dense-text screens (terms, some documents) drift to
  `news`/`other`; OCR helps but visual signal is genuinely ambiguous.
- **Distribution gap:** desktop-shaped screens drift to `browser/web`. Less relevant
  for the real target (phone screenshots) but it is why the desktop social proxy
  scores low.
- **By design:** low-confidence results are flagged needs-review (49% on the hard
  Enrico set, 44% on field), so the app surfaces its own uncertainty rather than
  silently mislabeling.

## Reproduce

```bash
scripts/eval/fetch_enrico.sh .evaldata           # MIT dataset + regression slice
python3 scripts/eval/fetch_field_commons.py .evaldata
# boot AVD, install app + androidTest apk, install both CLIP int8 models, then:
scripts/eval/push_and_run.sh .evaldata/enrico_slice enrico eval-out
scripts/eval/push_and_run.sh .evaldata/field_slice  field  eval-out
```

Raw per-image rows and summaries from this run are in `results-enrico.csv`,
`results-field.csv`, `summary-enrico.txt`, `summary-field.txt`.

## Bottom line

The email/reddit fix does what it was meant to: email screenshots that used to be
called "document" are now called "email", documents are unharmed, and the change is
contained. The residual misses are the CLIP visual ceiling on dense text and a
desktop-vs-mobile distribution gap, not a taxonomy bug. Broader social-media
generalization beyond Reddit is the one open item and needs representative mobile
data before any tuning.
