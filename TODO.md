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

- [x] **Classification accuracy eval against datasets (done 2026-06-15).** Built an
      on-device eval harness (`app/src/androidTest/.../pipeline/ClassificationEvalTest.kt`)
      that runs the EXACT production path (OCR + heuristics + CLIP + `TagFuser.fuse` +
      `TagFuser.decide`) over labeled images and emits a confusion matrix + per-class
      recall/precision + "other"/needs-review rates. Single-sourced the primary-tag/
      needs-review decision into `TagFuser.decide` (used by both `ImageProcessor` and the
      eval) so they cannot drift. Datasets (fetch scripts in `scripts/eval/`, images
      gitignored, manifests + results in `docs/eval/`): Enrico (MIT, 100-image regression
      slice) + a small Wikimedia-Commons FOSS field slice (16: email/social/document).
      **Findings** (`docs/eval/results.md`): email fix CAUSALLY confirmed — the harness
      logs CLIP-only argmax next to fused, and on the field set fusion flips 4/6 email
      images (3 of them straight from CLIP's `document`, the reported bug) to `email`,
      0 regressions; documents not cannibalized (4/5). Honest tradeoff: on the hard OOD
      Enrico slice fusion is marginally net-NEGATIVE (CLIP-only 59% vs fused 56%; 3 OCR
      misfires incl. one chat→email in 100), but the bug's direction (document→email)
      never reverses and weak calls are flagged needs-review, so net-positive where it
      matters. Enrico is a regression baseline only (2017 app crawls, approximate
      mapping). Residual misses are the CLIP ceiling on dense text + a desktop-vs-mobile
      distribution gap, not a taxonomy bug.
- [ ] **Social-media generalization beyond Reddit (open, needs data).** The OCR social
      rule keys on Reddit markers (upvote/subreddit/`r/`); generic web-social screens
      (Diaspora/Friendica in the field slice) score 1/5, drifting to `browser/web`.
      Do NOT tune against the desktop FOSS proxy — overfit risk. Needs representative
      MOBILE social screenshots (Reddit/Twitter/Instagram app captures) before any change.
- [ ] **Document/dense-text drift (open, CLIP ceiling).** Terms/long-text screens drift
      to `news`/`other` (Enrico document recall 30%). Pre-existing visual ambiguity, not
      caused by the email fix. Candidate for the UI-domain-model spike, not a quick tune.
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
- [ ] Tune fusion (receipts-article -> receipt is a soft false positive)
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
- [ ] Duplicate / near-duplicate screenshot detection (hash already stored).
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
