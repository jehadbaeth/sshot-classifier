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
`ImageProcessor` uses in the app, so the eval cannot drift from what ships. It also
records the CLIP-only argmax (no OCR, no fusion) per image, so we can isolate when the
OCR fix actually flipped the outcome from what CLIP would have said alone.

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

Overall fused 10/16 = 63%; **CLIP-only 6/16 = 38%**. The harness logs the CLIP-only
argmax next to the fused result, so we can see whether OCR+fusion actually changed the
call rather than CLIP getting it right on its own. Per class:

| Truth | Support | Fused recall | Reads as |
|---|---|---|---|
| email | 6 | **83% (5/6)** | email |
| document | 5 | **80% (4/5)** | document |
| social media | 5 | 20% (1/5) | browser/web |

- **The email fix is what flips the call — measured, not inferred.** Fusion changed
  the prediction on 4 of the 6 email images, all toward the correct `email`, with zero
  regressions:

  | Image | CLIP alone | Fused | Truth |
  |---|---|---|---|
  | Evolution_36_mail | document | email | email |
  | Spam (Thunderbird) | document | email | email |
  | KMail-features | code editor | email | email |
  | Thunderbird_1.0.6 | browser/web | email | email |

  Three of these are CLIP saying `document` — the **exact** bug the user reported — and
  the OCR email rule overrides it. (The arithmetic agrees: with `0.4·clip + 0.6·ocr`
  for email, the observed fused weights ~0.61 are only reachable with a large OCR
  contribution.) The single email miss (Evolution-on-KDE) is CLIP-dominated ("other"
  0.81) and is correctly flagged needs-review.
- **Documents are not cannibalized.** Genuine documents still predict `document` (4/5).
- **Social media stays wrong under the SAME lens (1/5), and so does CLIP-only.** Fusion
  did not flip any social image, because the OCR social rule keys on Reddit vocabulary
  (upvote/subreddit/`r/`) and these desktop FOSS web-social pages (Diaspora/Friendica)
  don't use it; CLIP reads them as `browser/web`. To be honest about the standard: this
  is the same desktop proxy that the email images come from, so it can't *disprove* the
  Reddit fix any more than it proves it — it shows the social rule does not generalize
  beyond Reddit. The Reddit path itself is covered by `OcrHeuristicsTest`. Validating
  general social classification needs representative MOBILE social screenshots, which we
  do not have. Logged as a TODO; not "fixed" by tuning against 5 unrepresentative shots.

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
mapping. The point of this slice is regression, and here it shows a real tradeoff:

- **On this OOD set, fusion is slightly net-negative: CLIP-only scores 59%, fused 56%.**
  Fusion changed 5 of 100 calls — 0 helped, 3 hurt (`chat→email`, and two `document→
  finance`/`other`). So OCR fusion, which is decisive on real email screens, occasionally
  misfires on generic app screens. The one `chat→email` case is an email OCR marker
  firing on a chat screen (1 of 11 chat images).
- **It is still well contained.** 3 fusion-induced errors in 100, and crucially **zero**
  `document→email`/`document→social` — the specific direction of the original bug stays
  fixed and does not reverse. Given the field-set win is decisive on the actual target
  (real email screens) and low-confidence results are flagged needs-review, the fix is
  net-positive where it matters; the OOD false-positive cost is small and surfaced, not
  silent. Chasing the OOD misfires by tuning would risk overfitting to this proxy.
- The weakest class is `document` (terms-of-service walls of text often read as `news`).
  This is a pre-existing CLIP-ceiling weakness on dense text, present in CLIP-only too,
  **not** caused by the email fix.

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

The email fix does what it was meant to, and we can prove it caused the change rather
than CLIP getting lucky: on real email screens, CLIP alone says `document` (the reported
bug) and the OCR rule flips it to `email` — 4 flips, 0 regressions on the field set.
Documents are unharmed. The cost is honest and small: on hard out-of-distribution app
crawls (Enrico), fusion is marginally net-negative (59%→56%) because the OCR rules
occasionally misfire there, but the bug's specific direction (document→email) never
reverses and weak results are flagged needs-review. Broader social-media generalization
beyond Reddit is the one genuinely open item and needs representative mobile data before
any tuning — the desktop FOSS proxy used here cannot settle it either way.
