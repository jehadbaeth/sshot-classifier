# Project TODO and Working Log

Living, cross-session tracker for the Screenshot Classifier. We both edit this.
The design lives in [docs/design.md](docs/design.md); spike results in
[docs/spikes/](docs/spikes/). This file tracks state, next steps, ideas, issues,
and the decisions we have locked so we do not relitigate them.

Convention: `[ ]` todo, `[x]` done, `[~]` in progress, `[!]` blocked.
Keep absolute dates. Newest decisions at the top of the decisions log.

---

## Current state (snapshot)

- **2026-06-14: Phase 4 complete (targeting v0.5.0).** Settings screen, reprocess
  action, custom user tags (manual + auto-categories), needs-review surface, and
  non-destructive copy-into-tag-albums reorganization all done. Real-model path
  validated on a Galaxy S20 FE AVD (API 33): two-file download, built-in tagging,
  custom-category autotag. Tech debt cleared: OCR min-score floor, foreground-service
  processing, doc updates. All Phase 4 features smoke-tested on device; 15 instrumented
  tests + unit tests green. Phases 0-3 before it. Repo private, CI green.
- Latest released tag target: `v0.5.0` (Phase 4). v0.4.0 = Phase 3, v0.3.0 = Phase 2,
  v0.2.0 = Phase 1.

---

## Now / next up

- [x] **Camera capture inventory — Phase A (RELEASED v0.7.0 2026-06-17).** New feature:
      in-app camera capture of real-world things (storefronts, signs, ads, QR codes)
      classified into the same gallery/search/tag inventory as screenshots. Fully offline.
      Design: docs/design.md section 15. Shipped in Phase A:
      * **CameraX capture** (`ui/camera/CameraCaptureScreen` + VM): full-screen camera, FAB
        on the gallery; photos written to `Pictures/ScreenshotClassifier/Captures` via
        MediaStore, indexed directly as `source_type=CAMERA` (`repository.indexCapture`),
        processed by the existing worker.
      * **Schema**: `ScreenshotEntity` + `source_type`/`description`/`qr_payload`; DB v5→v6
        (destructive, consistent with prior bumps). Gallery gains an All/Screenshots/Photos
        filter once captures exist; detail screen shows description + QR payload.
      * **QR/barcode decode** (`BarcodeExtractor`, ML Kit, on-device, camera-only): decoded
        code → authoritative `qr code` tag + stored payload + not-needs-review. No URL fetch.
      * **Taxonomy**: 8 real-world labels (storefront, advertisement, street sign, business
        card, product, menu, poster, qr code) **appended** to `label_embeddings.f32` with
        photo-style prompts via `spikes/clip/add_realworld_labels.py`. Verified the 22
        screenshot rows stayed BYTE-IDENTICAL (head sha unchanged). NOTE: byte-identical rows
        alone do NOT prove the screenshot eval is unchanged — a softmax over a bigger label
        set can flip the argmax. The real guarantee is candidate-set gating:
        `ZeroShotClassifier.classify` scores screenshots against `ClipLabels.screenshotLabels`
        (the original 22) and only captures opt into all 30 (`includeRealWorld=true`). So the
        screenshot candidate set + softmax denominator are unchanged by construction, and the
        eval harness (default arg) is identical. Also fixed a latent bug: `precompute_labels.py` had email→
        `document` while labels.json (runtime) correctly had email→`email`; the canonical
        script now matches.
      * **Description**: `CaptureDescriber` interface + `StructuredCaptureDescriber` (OCR +
        tags + QR host, deterministic). Interface lets a generative VLM impl drop in (Phase B).
      * **Tests**: `CaptureDescriberTest` (6 unit, green) + `CameraCapturePipelineTest`
        (instrumented, real ML Kit + describer on a real QR image, green on galaxy_s20fe_api33).
        Build + full unit suite + androidTest compile green.
      * **NOT validated**: real-world CLIP classification accuracy (no labeled real-world
        capture set; eval emulator has no CLIP model — tracked separately below) and the live
        CameraX shutter→file flow (hardware/UI path, not automatable headlessly). The physical
        shutter is the only remaining human-eyeball step; the feature is shipped/released.
- [x] **Camera capture — Phase B (QR link resolution RELEASED v0.7.0 2026-06-17).** User
      asked to proceed with Step B and make everything fully user-controllable. Shipped:
      * **All behavior is now a stored preference** (`CapturePreferences` + `CapturePreferencesStore`,
        DataStore `capture_prefs`, mirrors ReorgPreferencesStore) surfaced in a Settings "Camera
        capture" section: resolveQrLinks (off), resolveTrigger (manual), resolveOnWifiOnly (on),
        downloadPreviewImages (off), descriptionSource (structured), decodeQrCodes (on),
        captureAlbumRoot. Defaults are all offline-private.
      * **QR link-preview resolution**: `LinkPreviewResolver` (HttpURLConnection, NO new dep) +
        pure `OgParser` (regex OG/title extraction, no HTML-parser dep). Conservative: http/https
        allowlist re-checked after each redirect, LAN-host guard, timeouts, 512KB cap, content-type
        check, swallows all failures. Resolved fields stored as entity columns qr_title/
        qr_description/qr_image_url/qr_resolved_at (DB v6→v7 destructive). Manual "Resolve link"
        button on the detail screen; automatic path in the worker (only when trigger=AUTOMATIC).
      * **Pure `ResolvePolicy.decide(prefs,isUrl,network)`** is the single privacy gate shared by
        the button and the worker (off→no fetch; non-url→no fetch; wifi-only on metered→no fetch).
        Unit-tested (ResolvePolicyTest) + OgParserTest. `NetworkChecker` (ConnectivityManager) feeds it.
      * **downloadPreviewImages gate is at the Coil call site**, not the resolver: the og:image URL
        is always stored, only handed to AsyncImage when the toggle is on (advisor catch — otherwise
        Coil fetches regardless and the toggle is a lie).
      * **Capture flag seam**: `ImageProcessor.process(shot, decodeQrCodes=true)` takes the flag as a
        param (worker reads prefs once, passes it) so the prefs store stays off the throughput hot
        path and the existing ImageProcessor test call sites keep working via the default.
      * **HONEST CORRECTION**: Phase A usage.md §9 said resolution would be "never an automatic
        fetch." The comprehensive-config requirement added an opt-in automatic trigger, so usage.md §9
        + design.md §1.2/§15.2 were corrected and the reversal called out. Default is still off +
        manual; nothing touches the network on its own by default. (advisor catch.)
      * Also: ACCESS_NETWORK_STATE permission added. Build + unit suite + 2 instrumented camera
        tests green; the 4 repo-constructing instrumented tests updated for the 3 new repo ctor deps.
      * Live OG fetch now VERIFIED on device by `LinkPreviewResolverLiveTest` (real fetch of
        example.com extracts the title; file:// and LAN hosts rejected). Local-only/network test
        (CI is Linux, no emulator). Only the visual og:image render in the preview card still
        wants a human eyeball; standard Coil AsyncImage, low risk.
- [ ] **Camera capture — still deferred.**
      * **Generative description**: second `CaptureDescriber` impl backed by an on-device VLM
        (BLIP/Florence/SmolVLM-class). The Settings selector exists with Generative shown but
        DISABLED (generativeDescriptionAvailable=false) until a model is bundled. Hundreds of MB;
        its own LiteRT conversion + tokenizer + decode loop + model-delivery + perf project.
      * **Real-world classification eval**: build/borrow a labeled real-world capture set
        (storefronts/signs/products) and run the eval harness with the CLIP model installed.
- [x] **Classification accuracy eval against datasets (done 2026-06-15, SCALED 2026-06-16).**
      Built an on-device eval harness (`app/src/androidTest/.../pipeline/ClassificationEvalTest.kt`)
      that runs the EXACT production path (OCR + heuristics + CLIP + `TagFuser.fuse` +
      `TagFuser.decide`) over labeled images and emits a confusion matrix + per-class
      recall/precision + CLIP-only-vs-fused paired delta + "other"/needs-review rates.
      Single-sourced the primary-tag/needs-review decision into `TagFuser.decide` so the
      eval and `ImageProcessor` cannot drift. **Scaled 2026-06-16 to ~3,380 images**:
      F-Droid catalog (3,124 FOSS phone screenshots, weak app-function labels, English
      only, sha256-deduped, 4/app + 400/class — `scripts/eval/fetch_fdroid.py`) + full
      Enrico mappable slice (240) + the clean Wikimedia field slice (16). Scripts + result
      CSVs + summaries + manifests (license/sha256/URL per image) in `docs/eval/`; images
      gitignored. **Findings** (`docs/eval/performance-and-accuracy.md`): (1) The email/reddit fix stays
      validated only on the CLEAN field slice (4/6 email flips from CLIP's `document`,
      0 regressions) + `OcrHeuristicsTest`; the F-Droid email/document folders are 40–70%
      promo graphics / non-target screens (spot-checked by eye) so scale can't re-prove it.
      (2) Fusion's confound-free paired delta (real-class→real-class flips, Bucket A) is
      net **+8** — mildly POSITIVE on the broad distribution. The naive −15 is an
      abstention artifact (152 fusion→"other" flips auto-scored wrong because F-Droid has
      no "other" truth folder). So "fusion hurts at scale" is FALSE. (3) Confident-class
      precision is strong where CLIP is visual: game 88%, finance 83%, map 72%, video 68%.
      See the three new CLIP-ceiling items below. No new release (docs-only; v0.6.0 already
      shipped the code).
- [x] **`error / crash` over-fires on modal dialogs (FIXED 2026-06-16, v0.6.1).** Was:
      predicted 80× across F-Droid+Enrico, 0 correct, 42 confident, 79/80 CLIP-only. Cause
      (confirmed by eye): CLIP label `"an error message dialog"` matched any modal dialog
      and carried it (0.4 fusion weight) to a confident wrong `error / crash`. Fix (surgical,
      no fusion changes): (1) remapped that CLIP label `error / crash`→`other` in labels.json
      + precompute_labels.py — embedding is keyed on the unchanged phrase so
      `label_embeddings.f32` stayed BYTE-IDENTICAL (no regen, no broad perturbation); (2)
      extended the OcrHeuristics error rule with documented user-facing Android strings
      (`keeps stopping`, `isn't responding`, `application not responding`, `unfortunately…
      stopped/crashed`; weak `has stopped`/`something went wrong`/`try again`). Validated:
      F-Droid error FP 74→1 (the 1 is a terminal screen with real error text); every
      error/crash prediction is now pure OCR so that proves the new keywords cost ~0 FP on
      3,124 real screens; no real class regresses (worst −0.75%); Enrico 60%→61%. CAVEAT:
      real-error RECALL is NOT validated — clean real mobile error screenshots aren't
      harvestable from the open web (checked ~9 articles/~35 images, ~0 usable). A 12-image
      synthetic set (scripts/eval/gen_synth_errors.py) was a mechanism check only, not a
      recall metric. Ambiguous error dialogs now route to needs-review (honest); only
      text-rich error screens (stack traces) tag error. Unit tests updated (OcrHeuristicsTest).
- [x] **finance ↔ receipt — investigated + closed as NOT a fixable bug (2026-06-16).**
      Took a crack: per-image inspection of all 45 receipt predictions. Of 32 confident,
      only 7 have weight ≥0.50, and 6/7 are correct or defensible (invoiceninja = an invoice
      app ×3; happytaxes = tax app, one screen literally titled "RECEIPT" ×2; a shopping
      list = checkout≈receipt). The remaining 25 confident are low conf (0.26–0.49) on
      receipt-ADJACENT content (expense forms, crypto send/receive, price lists, an itemized
      timetable) — CLIP keys on "itemized list + amounts," genuinely receipt-like. Exactly 1
      arguable over-fire (evmap price list 0.56). ZERO clean bank dashboards mislabeled (and
      FOSS finance apps skew expense/crypto/budget, so the set can't even show that failure).
      So: dominated by weak F-Droid label noise + receipt-adjacency, not a model bug. No safe
      fix — receipt is validated working (receipt→receipt 1.0) so can't be remapped like
      error/crash; confusion is CLIP-driven (39/45) so OCR isn't the lever; the only CLIP
      lever (budgeting/expense decoy label) regenerates all embeddings + perturbs every
      softmax + risks the working receipt class for ~1 real over-fire. Closed no-fix with
      evidence. See docs/eval/performance-and-accuracy.md. (Phase 2 "receipts-article soft FP" is the related
      OCR-side item, still separately open.) CONFIRMED on a clean real-bank set 2026-06-16:
      104 screenshots from 18 banks (Revolut/Monzo/Starling/N26/Chime/Chase/BofA/WellsFargo/
      CapitalOne/USAA/HSBC/Barclays…) via scripts/eval/fetch_bank_dashboards.py — finance
      recall 59%, only 4/104 predicted receipt (3 low-conf needs-review; the 1 confident
      "receipt 1.00" is literally a BofA "Receipt Organizer" screen showing real receipts =
      correct). ZERO clean balance dashboards confidently mislabeled. Classic failure does
      not occur on real bank UI.
- [x] **`browser / web` overprediction — OCR rule fixed 2026-06-17.** Eval (results-fdroid2.csv):
      89 confident browser preds, 23 correct = 26% confident precision; the 66 confident FPs were
      spread across 11 true classes (code 13, social 11, document 10, news 7, chat 6, …). Cause
      (from the CSV `clip_only` column): the OCR rule treated `http://`/`https://`/`www.` as STRONG
      markers, so any screen merely quoting a URL got a confident browser vote, and browser/web is
      OCR-authoritative (0.6). 38 of the 66 FPs were OCR-driven (clip_only != browser); only 4 of
      the 23 true positives depended on OCR (CLIP catches 19/57 on its own). Fix: demoted the URL
      scheme markers to WEAK so a bare/partial URL no longer emits browser alone; OCR votes browser
      only when several web signals co-occur, CLIP stays primary. Estimated effect (CSV-derived, not
      re-run — corpus is offline): ~9:1 FP:TP trade, confident precision ~26%→~37%, recall ~40%→~33%;
      lost positives fall to needs-review, not wrong tags. Unit-tested (OcrHeuristicsTest: repo-url
      in code / shared link in chat no longer browser; saturated web page still browser). CAVEAT:
      the full fusion precision/recall delta on F-Droid was NOT re-measured (the 3,124-image corpus
      and CLIP model are not on disk); re-validate when the eval corpus is regenerated.
- [!] **Social-media generalization beyond Reddit — BLOCKED on clean data (reviewed 2026-06-17).**
      OCR social rule keys on Reddit markers (upvote/subreddit/`r/`). The F-Droid social folder is
      too noisy to settle this (promo graphics + messenger shots); social precision 45%. Needs
      representative, hand-verified MOBILE social captures before any change. Do NOT tune against
      weak-labeled folders — overfit risk. DECISION: not actionable until such a set exists; not a
      blind-tuning candidate. Unblock = build/borrow a clean mobile-social slice (ties to the P3
      real-world / clean-data datasets effort).
- [!] **Document/dense-text drift — BLOCKED, CLIP ceiling (reviewed 2026-06-17).** Terms/long-text
      screens drift to `news`/`other` (Enrico document recall 31%). Pre-existing visual ambiguity,
      not an OCR-tunable bug (a dense-text page genuinely looks like news/doc to CLIP). DECISION:
      the only real lever is the UI-domain-model spike (SigLIP / CLIP fine-tuned on RICO or
      Screen2Words), tracked in the backlog; no standalone quick fix. Closed as folded into that spike.
- [x] **Close the search-path test gap (done 2026-06-14).** The repository fusion
      glue was previously unverified at runtime. Extracted RRF + FTS sanitization +
      result reordering into a pure `SearchFusion` object with JVM unit tests (run in
      CI), added a `TextEmbedder` interface seam, and added `HybridSearchInstrumentedTest`
      that drives the REAL Room FTS4 DB through `hybridSearch` with a fake embedder
      (no model files). 5/5 pass on emulator. Remaining gaps: two-file download not
      re-run e2e (low risk, loop logic is simple), and the on-device CLIP test set is
      small/partly synthetic (backlog).
- [x] **Phase 4 COMPLETE, v0.5.0 RELEASED (2026-06-14).** Reprocess, Settings, custom
      tags (manual + auto-categories), needs-review surface, copy-into-tag-albums reorg.
      Tech debt cleared: OCR min-score floor, foreground-service processing, doc updates.
- [x] **Usage guide + screenshots (done 2026-06-14).** Wrote [docs/usage.md](docs/usage.md),
      a screen-by-screen walkthrough (models/scan/gallery/search/manual tags/custom
      categories/needs-review/reorganize) with 5 real captures from the S20 FE AVD in
      docs/images/ (gallery, tag editor, visual search, settings, custom category).
      Linked from the README. Captures show real on-device results, e.g. a "potted plant"
      visual search ranking the cactus first and a custom "houseplant" category tagging it.
- [x] **Scale / load test on 500-1000 screenshots (done 2026-06-14).** Two instrumented
      tests against a real on-disk Room DB on the S20 FE AVD; full numbers in
      [docs/spikes/scale-test.md](docs/spikes/scale-test.md). `ScaleInstrumentedTest`:
      search + memory at 500..10k. `ProcessingThroughputTest`: real `ImageProcessor`
      draining 300 real screenshots. Results: at 500-1000 search is sub-6 ms and OCR
      classify is ~22 ms/image, queue drains cleanly with no heap leak — fine. **Real
      finding: the design's "<50 ms / brute force fine to 20k" claim is wrong** — search
      is linear and hits ~126 ms at 10k because `SemanticSearcher` re-deserializes every
      embedding per query. Fine to ~5k; needs the embedding cache below to go further.
      Memory (~20 MB at 10k) matched. Design sec 8 + 12 corrected. JobScheduler-at-volume
      deliberately not tested (its emulator wedging is a known harness artifact, not app
      logic; the direct-loop test measures real app behavior without that noise).
- [x] **Embedding cache for search (done 2026-06-14).** Added `EmbeddingCache`: decodes
      all embeddings once and reuses the FloatArrays across queries, invalidated by
      `ImageProcessor` whenever an embedding is written. `SemanticSearcher` now reads the
      cache instead of `dao.allEmbeddings()` per query. Re-measured on the S20 FE AVD:
      warm queries dropped ~10x (10k: 126 ms -> 11 ms; under the 50 ms target, ~22 ms
      extrapolated at 20k). Only the first query after the library changes pays the
      one-time rebuild. Scale test now reports cold-vs-warm; design sec 8 + 12 updated.
- [x] **Device-matrix + real-model validation (done 2026-06-14).** Created a Galaxy
      S20 FE-shaped AVD (1080x2400, 420 dpi) on Android 13 / API 33 (galaxy_s20fe_api33).
      NOTE: the stock emulator cannot run Samsung firmware / One UI — S20-FE-shaped AVD on
      a Google system image; useful for screen + API-level coverage, not literal Samsung
      OS. Results: full instrumented suite (14 tests) passes; UI smoke-tested. Then drove
      the REAL models on it and closed several gaps at once:
        * two-file model download via the production downloader (image 89.5MB + text
          65.2MB, sha256-verified + renamed) — gap closed.
        * built-in CLIP tagging correct: map->map 0.85, receipt->receipt 1.0,
          code->code editor 0.79, city->other.
        * custom auto-category e2e: added "street map" in Settings -> embedded on-device
          -> auto-tagged ONLY the map (cosine 0.281); receipt/code/city stayed below the
          0.24 threshold. Clean separation; 0.24 looks reasonable on this sample.
      Caveat: emulator JobScheduler wedged mid-test ("Job didn't exist in JobStore");
      indexing only worked after an `adb reboot` (harness flakiness, not app — API 35
      indexed fine earlier). Legacy permission path (<API 33, READ_EXTERNAL_STORAGE)
      still untested; candidate follow-up (API 30 run).
- [x] **Reprocess action (done 2026-06-14).** Gallery banner appears when the image
      model is installed and there are DONE screenshots with no embedding; tapping it
      resets those rows to PENDING and enqueues the worker, which re-runs them through
      the now-CLIP-capable pipeline. DAO `NOT IN (embeddings)` queries verified on real
      SQLite (ReprocessDaoInstrumentedTest). Edge: an image that genuinely cannot be
      encoded stays counted, so the banner persists until dismissed by success; not a
      retry loop (user-initiated only). Worker enqueue uses KEEP, so a reprocess tapped
      while a scan is mid-run waits for the next trigger.
- [x] Node 24 for GitHub Actions (done 2026-06-14): set
      FORCE_JAVASCRIPT_ACTIONS_TO_NODE24=true in both workflows to opt in now and
      silence the deprecation warning. (It was only a warning; GitHub force-defaults
      to Node 24 on 2026-06-16 and removes Node 20 on 2026-09-16, so CI never broke.)
- [x] **Reorganization is configurable (done 2026-06-14).** DataStore-backed prefs
      (`ReorgPreferencesStore`): copy-vs-move, album root name, needs-review handling
      (uncategorized vs skip), and auto-run after each scan. Move copies then deletes
      originals via `MediaStore.createDeleteRequest` (system consent dialog, API 30+;
      degrades to copy below) and records an undo log (`reorg_moves`, DB v5) so it can
      be reversed; after a move the DB repoints each screenshot at its album copy. Auto-run
      always copies (never deletes) since a background worker cannot show the consent dialog.
      All paths verified on the S20 FE AVD: copy (default + custom root), needs-review
      routing, move (Allow -> originals deleted + log + repoint), undo (originals restored
      + log cleared), auto-run (background copy, no deletion). Pure routing + root sanitize
      unit-tested. needs-review SKIP is unit-tested (not separately device-driven).
- [x] **Heuristic tuning pass (done 2026-06-14).** Split OcrHeuristics keywords into
      strong (specific, one clears the floor) and weak (generic, WEAK_HIT 0.14 < half the
      0.30 floor, so two generic words alone still do not emit). Kills the single-generic-
      keyword false positives: a Settings "Account" no longer reads finance, "Total steps"
      no longer receipt, "Send us a message" no longer chat, "Sort order" no longer
      shopping. Added a labeled corpus test (10 true-positive + 8 false-positive realistic
      OCR snippets) so the rules are measured, not just asserted. All green. Caveat: hand-
      labeled cases, not a sample of real screenshots, so this validates the logic, not
      field precision. Fusion tuning (TagFuser receipts-article soft FP) is still separate.
- [ ] Shrink the APK (now ~125 MB debug, +1.3 MB BPE merges asset). See Known issues.

---

## Phase roadmap

Mirrors docs/design.md section 14, with task-level detail.

### Phase 0 — Scaffold (DONE)
- [x] Gradle project, Compose + Hilt + Room + WorkManager, minSdk 26 / target 35
- [x] Room schema: screenshots, tags, ocr_entries, embeddings
- [x] MediaStore scanner + SHA-256 dedup
- [x] Permission gate + reactive gallery + manual Scan
- [x] Build, install, verify on emulator end to end
- [x] CI (build + unit tests) and Release (tagged APK) workflows
- [x] Initial release v0.1.0

### Phase 1 — OCR + text search (DONE 2026-06-14, v0.2.0)
- [x] Processing status lifecycle (PENDING/PROCESSING/DONE/FAILED)
- [x] WorkManager: expedited one-off processing + 6h periodic catch-up
- [x] Live detection via ContentObserver on MediaStore (NOT FileObserver — see deviations)
- [x] ML Kit OCR extraction -> persist OcrEntry
- [x] FTS4 (not FTS5) text search, recency-ranked -> see deviations
- [x] OCR-derived classification signals (OcrHeuristics, source = ocr_heuristic)
- [x] Search UI: text query + tag filter chips, bottom-nav Gallery/Search
- [x] Progress notification (normal, not foreground service yet)
- [x] Verified on emulator: OCR + FTS search ("linux" -> code shot) + tags
- [x] Unit tests for heuristics (regressions: status-bar clock, #include)
- [ ] Foreground service for very large backfills (deferred; normal notification for now)
- [ ] Heuristic tuning is ongoing, not "done" — see Known issues

### Phase 2 — CLIP integration (code done + verified 2026-06-14; release pending hosting)
- [x] Convert CLIP image encoder to TFLite (int8 weight-only, 89.5 MB, cos >= 0.998)
- [x] Precompute + bundle label embeddings (prompt-ensembled, concrete labels)
- [x] ClipEncoder (preprocess + TFLite run + L2 normalize), ClipModelManager
- [x] Image embedding generation -> persist EmbeddingEntity
- [x] Zero-shot scoring (softmax, aggregate internal->user-facing tags)
- [x] Fusion of CLIP scores with OCR signals + margin gate (TagFuser)
- [x] Verified on-device: map/code/settings/receipt tags correct, no TFLite errors
- [x] Text encoder NOT needed on-device for tagging (label embeddings precomputed)
- [x] Model delivery: public mirror repo + first-launch download with sha256
      verify; verified end to end on emulator (download -> checksum -> install)
- [x] Tune fusion (receipts-article -> receipt soft FP) — INVESTIGATED + closed no-fix 2026-06-17.
      Eval (results-fdroid2.csv): 32 confident receipt FPs, but 26/32 are CLIP-driven (the
      finance↔receipt visual confusion already investigated + closed no-fix). Only 6 are
      OCR-driven, and those are currency-pattern overlap (`[$€£]\s?\d` / `\d+\.\d{2}`) on finance
      screens with amounts — NOT the literal-"receipt"-word-in-an-article case this item names,
      which does not materially appear in the scaled set. Removing the currency pattern from the
      receipt rule would hurt genuine receipt recall (receipts are all amounts) to fix 6 weak-label
      finance shots. No safe OCR lever; folded into the finance↔receipt no-fix conclusion.
- [x] Reprocess action to re-tag/re-embed already-DONE screenshots after model
      install (done 2026-06-14, see Phase 4)

### Phase 3 — Semantic search (DONE 2026-06-14, v0.4.0)
- [x] CLIP text encoder -> TFLite int8w (65.2 MB, min cos 0.9993); hosted on mirror
- [x] On-device BPE tokenizer (Kotlin port of open_clip SimpleTokenizer, byte-identical, unit-tested)
- [x] Text-encode query -> in-memory brute-force cosine over stored image embeddings (SemanticSearcher)
- [x] Hybrid merge of visual + OCR rankings via reciprocal rank fusion (RRF over FTS4, not BM25 weighting)
- [x] Search UX: query embeds visual+text, graceful fallback if text model absent
- [x] Verified on-device cross-modal retrieval (instrumented test, map/code/receipt)
- [ ] HNSW index only if libraries exceed ~20k images (defer)
- [ ] Tune RRF k / cap; consider showing a per-result "visual vs text" provenance hint

### Phase 4 — Reorg, taxonomy, polish
- [x] Reprocess already-DONE-but-unembedded screenshots after model install
      (gallery banner -> reset to PENDING -> worker re-embeds; DAO tested on device)
- [x] User-triggered reorganization: non-destructive COPY into Pictures/ScreenshotClassifier/<tag>/
      (uncategorized when needs_review); idempotent; API 29+. Chose copy over move with user.
- [x] "Needs review" surface (done): needs_review persisted, set on weak CLIP tagging /
      untagged, cleared on manual edit; Gallery filter chip.
- [x] Custom user tags (DONE 2026-06-14, both slices). Slice 1: manual per-image tags
      (gallery cell -> detail/tag-editor; add user tag trim+lowercase+dedup source=USER,
      or remove ANY tag). Slice 2: custom AUTO-categories. User defines a concept; it is
      prompt-ensembled on-device by the text encoder (CategoryEmbedder, 7 templates,
      averaged+normalized), stored, and scored against image embeddings by INDEPENDENT
      cosine threshold (CustomCategoryScorer, 0.24) — additive, does not touch the
      built-in softmax (decided with user). Adding a category immediately tags matching
      already-indexed screenshots; new ones are scored in ImageProcessor. Managed in
      Settings (gated on text model). Tests: CustomCategoryScorerTest (unit, CI) +
      CustomCategoryRepoTest (real Room, fake embedder) + Settings UI smoke-tested.
- [x] Settings screen (done 2026-06-14): third bottom-nav tab. Library stats, AI
      model status + install/update, reprocess + scan-now, About/version. Smoke-tested
      on emulator (renders, navigates, no crash). Deferred (need DataStore / tag CRUD,
      not stubbed): configurable scan interval, manage/edit tags.

---

## Backlog / ideas (not scheduled)

- [ ] Follow-up spike: UI-domain model (SigLIP or CLIP fine-tuned on RICO /
      Screen2Words) to beat generic CLIP on text-heavy screens. Costs size.
- [x] App bundle (AAB) to cut download size. `bundleRelease` produces a 49 MB AAB
      vs the 101 MB universal release APK; Play serves per-ABI/density splits. See
      docs/publishing.md. (Per-ABI APK splits not needed once shipping the AAB.)
- [~] Duplicate / near-duplicate screenshot detection. Exact dupes are already prevented at
      ingest (sha256 unique index), so this is NEAR-dupe via CLIP embeddings (cosine = dot,
      they're L2-normalized). Engine done 2026-06-17: pure `DuplicateFinder.groups()` (union-find
      over pairwise cosine ≥ 0.96, O(n²) like brute-force search) + DuplicateFinderTest (5).
      Remaining: repository hook + a way to surface groups in the UI (leaning to a gallery
      "duplicates" filter that reuses the grid/detail, no new destructive delete subsystem).
- [ ] Export/import of the tag database.
- [ ] Widen the spike test set (real game, calendar, receipt, shopping screens;
      the v1 spike leaned on web consent walls for several categories).
- [x] Signed release builds (keystore in CI secrets) instead of debug-signed.
      build.gradle signingConfig reads keystore.properties / env (debug fallback +
      loud warning); Release workflow builds signed APK + AAB from secrets. The
      minified R8 release build was verified on device: OCR (ML Kit) + CLIP image
      and query encoding (TFLite) all run, worker SUCCESS, no stripping crashes.
      CI release path is wired but unexercised until the keystore secrets exist.
- [ ] On-device benchmark harness for CLIP encode time (design sec 12 targets).

---

## Deviations from design.md (intentional)

- **FTS4, not FTS5.** Room natively supports only FTS3/FTS4; FTS5 (and its bm25
  ranking) needs hand-rolled SQL and has uneven OEM availability. Phase 1 uses
  `@Fts4` with recency-ranked MATCH and prefix queries. Revisit FTS5 if ranking
  quality demands it.
- **ContentObserver, not FileObserver.** FileObserver is unreliable under scoped
  storage. We watch the MediaStore images URI instead (live while app alive) plus
  the WorkManager periodic backstop.
- **Normal progress notification, not a foreground service.** Avoids Android 14
  foreground-service-type requirements for now. Large backfills want a real FGS.

## Known issues / tech debt

- ~~CLIP model has no public host.~~ **Resolved 2026-06-14.** Model hosted on the
  public repo `jehadbaeth/sshot-classifier-models` (release `clip-vit-b32-laion-int8`).
  App downloads from there on first launch with sha256 verification. End-to-end
  download verified on emulator. The 89.5 MB model stays gitignored in the app repo.
- ~~**OCR heuristics over-fire on a single weak keyword / shared currency pattern.**~~
  **Addressed 2026-06-14, hardened later same day.** First a MIN_EMIT=0.30 floor dropped
  lone shared patterns. Then keywords were split into strong (specific) and weak (generic):
  WEAK_HIT=0.14 is below half the floor, so even two generic words ("account", "total",
  "order", "message") alone no longer emit a tag, while one strong term or real
  corroboration does. Covered by a labeled corpus test. Calendar from OCR stays weak
  (abbreviated day grids do not match full day names); acceptable since CLIP is not the
  calendar path anyway.
- ~~**APK size ~124 MB (debug).**~~ **Addressed 2026-06-14:** bulk is ML Kit OCR +
  TFLite native libs across 4 ABIs (CLIP models are NOT in the APK, downloaded at
  runtime). The minified universal release APK is 101 MB; the AAB is 49 MB and Play
  serves only each device's ABI/density split, so real download is far smaller. Ship
  the AAB. See docs/publishing.md.
- ~~**Design doc sec 14 stale.**~~ Updated 2026-06-14 to current phase reality.
- ~~**GitHub Actions Node 20 deprecation.**~~ **Done 2026-06-14:** opted into Node 24
  via FORCE_JAVASCRIPT_ACTIONS_TO_NODE24; CI runs the actions on Node 24.
- **Spike caveats:** 11 images, several categories stood in by web consent walls;
  host-side CLIP ignores TFLite quantization. See docs/spikes/clip-findings.md.

---

## Decisions log (newest first)

- **2026-06-14 — Custom auto-categories use an independent cosine threshold, not the
  softmax pool.** User-defined categories are scored on their own (CustomCategoryScorer,
  threshold 0.24) and are purely additive, so they cannot regress the fragile built-in
  zero-shot tagging. Chosen with the user over joining the built-in softmax. The label is
  prompt-ensembled on-device by the text encoder. Threshold 0.24 is an untuned starting
  value (just below the 0.25-0.30 match band seen in Phase 3); manual tag removal is the
  false-positive safety net. Built manual tagging first so the override surface existed.
- **2026-06-14 — Search fusion is pure + seam-tested.** Hybrid search logic lives in
  `SearchFusion` (pure, JVM-unit-tested in CI) and `SemanticSearcher` depends on a
  `TextEmbedder` interface, not the concrete `ClipTextEncoder`. This lets the real
  Room path be integration-tested with a fake embedder (no 65 MB model), closing the
  earlier "repository fusion never exercised at runtime" gap.
- **2026-06-14 — Phase 3 fusion = reciprocal rank fusion, not weighted scores.**
  CLIP text-image cosine (~0.2-0.35) and a binary FTS hit live on incompatible
  scales; normalizing them is fiddly and fragile. RRF fuses by rank position, is
  parameter-light (k=60), and degrades gracefully when one source is empty (no text
  model -> pure OCR; no OCR match -> pure visual). Replaces the design's 0.6/0.4
  weighted merge.
- **2026-06-14 — Tokenizer ported in-app, not bundled as data.** Only the BPE merges
  are bundled (~1.3 MB); the 49408-token vocab is reconstructed in Kotlin in the exact
  open_clip order. A unit test pins it byte-identical to open_clip. aapt auto-gunzips
  `.gz` assets, so merges ship as plain `clip/bpe_merges.txt`.
- **2026-06-14 — On-device verification via instrumented test.** Cross-modal retrieval
  (text query -> ranks matching image highest among distractors) is the gold-standard
  proof and is committed as `SemanticSearchInstrumentedTest`. Test images are downloaded
  /generated into a gitignored `.testdata/` and bundled in androidTest assets. CI stays
  Linux-only (no emulator), so this test is run locally, not in CI.

- **2026-06-14 — Phase 1 tech choices:** FTS4 over FTS5 (Room support +
  reliability), ContentObserver over FileObserver (scoped storage), normal
  notification over foreground service (Android 14 FGS-type cost). OCR heuristic
  tags are signals feeding Phase 2 fusion, not authoritative tags. See deviations.
- **2026-06-14 — Release strategy:** v* tags publish debug-signed APKs via GitHub
  Releases for sideloading/sharing. Signed release builds deferred. CI on Linux
  runners only (1x billing) to stay within the Free plan 2,000 min/month.
- **2026-06-14 — OCR is a co-classifier.** CLIP spike showed CLIP is confidently
  wrong on text-heavy UI. CLIP now handles visual categories + search; OCR handles
  text-heavy categories; tagging gates on top1-top2 margin + OCR agreement, not a
  raw confidence floor. Default to LAION-2B weights + prompt ensembling + concrete
  internal labels. (docs/spikes/clip-findings.md)
- **2026-06-14 — Test target:** emulator (Pixel API 35 arm64) + full Android
  Studio for dev. Physical phone still preferred long-term for a screenshot app.
- **Earlier — Core design:** fully offline, no backend; tags not folders; multi
  weighted tags per image; CLIP downloaded on first launch; Room + FTS5 for text,
  in-memory cosine for vectors; file moves only on explicit user trigger.

---

## Environment notes

Setup specifics (SDK paths, gotchas, verified build/run/test commands) are kept in
the assistant memory files, not here. Key reminders: `export
JAVA_HOME=/opt/homebrew/opt/openjdk@17` before Gradle; SDK at
`~/Library/Android/sdk`; emulator AVD `pixel_api35`; use the in-SDK `avdmanager`
not the brew-linked one.
