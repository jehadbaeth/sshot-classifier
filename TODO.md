# Project TODO and Working Log

Living, cross-session tracker for the Screenshot Classifier. We both edit this.
The design lives in [docs/design.md](docs/design.md); spike results in
[docs/spikes/](docs/spikes/). This file tracks state, next steps, ideas, issues,
and the decisions we have locked so we do not relitigate them.

Convention: `[ ]` todo, `[x]` done, `[~]` in progress, `[!]` blocked.
Keep absolute dates. Newest decisions at the top of the decisions log.

---

## Current state (snapshot)

- **2026-06-14:** Phase 0 done and verified on emulator. Repo pushed to
  `git@github.com:jehadbaeth/sshot-classifier.git` (private). CI green on runner.
  Release `v0.1.0` published with a downloadable APK. CLIP spike done; design
  revised to make OCR a co-classifier.
- Latest tag: `v0.1.0`. Branch: `main`.

---

## Now / next up

- [ ] Decide Phase 1 entry point: build the OCR + FTS5 text-search pipeline
      (processing queue + WorkManager + ML Kit OCR + FTS5). This is the agreed
      next phase and de-risks background work before the heavy CLIP dependency.
- [ ] Design the OCR-derived classification signals now (not just raw text for
      search), since the spike made OCR a co-classifier. Define keyword/pattern
      rules per category (currency/totals -> receipt, stack-trace shapes ->
      error, monospace + language tokens -> code, etc.).
- [ ] Shrink the APK (currently ~117 MB debug). See Known issues.

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

### Phase 1 — OCR + text search
- [ ] Processing queue abstraction (status: PENDING/PROCESSING/DONE/FAILED already in schema)
- [ ] WorkManager: periodic catch-up scan + expedited single-item processing
- [ ] FileObserver on the screenshots dir for low-latency live detection
- [ ] ML Kit OCR extraction -> persist OcrEntry
- [ ] FTS5 virtual table + triggers, BM25 text search
- [ ] OCR-derived classification signals (keyword/pattern rules per category)
- [ ] Search UI (text query -> ranked results)
- [ ] Foreground notification + progress for batch backfill
- [ ] Instrumented test on emulator with generated screenshots

### Phase 2 — CLIP integration
- [ ] Pick + pin a TFLite CLIP ViT-B/32 port (LAION-2B weights), image + text encoders
- [ ] First-launch model download with checksum verify + progress screen (design sec 9)
- [ ] Image embedding generation -> persist EmbeddingEntity
- [ ] Zero-shot scoring with prompt ensembling + concrete internal label set
- [ ] Fusion of CLIP scores with OCR signals + margin gate (design sec 4.3)
- [ ] Validate on-device quality vs the host spike (quantization impact)

### Phase 3 — Semantic search
- [ ] CLIP text-encode query -> in-memory brute-force cosine over embeddings
- [ ] Hybrid merge of visual score + FTS5 BM25 (tune the 0.6/0.4 split)
- [ ] Search UX for visual + text + tag filters
- [ ] HNSW index only if libraries exceed ~20k images (defer)

### Phase 4 — Reorg, taxonomy, polish
- [ ] User-triggered file reorganization into app-managed folder (margin/OCR gate, not raw floor)
- [ ] Custom user tags (new prompt-ensembled label embeddings)
- [ ] "Needs review" surface for low-confidence/contradicted tags
- [ ] Settings: scan interval, manage tags, model management

---

## Backlog / ideas (not scheduled)

- [ ] Follow-up spike: UI-domain model (SigLIP or CLIP fine-tuned on RICO /
      Screen2Words) to beat generic CLIP on text-heavy screens. Costs size.
- [ ] Per-ABI APK splits or app bundle to cut download size.
- [ ] Duplicate / near-duplicate screenshot detection (hash already stored).
- [ ] Export/import of the tag database.
- [ ] Widen the spike test set (real game, calendar, receipt, shopping screens;
      the v1 spike leaned on web consent walls for several categories).
- [ ] Signed release builds (keystore in CI secrets) instead of debug-signed.
- [ ] On-device benchmark harness for CLIP encode time (design sec 12 targets).

---

## Known issues / tech debt

- **APK size ~117 MB (debug).** Bulk is ML Kit OCR + TFLite native libs across 4
  ABIs. CLIP model is NOT in the APK (downloaded at runtime). Mitigate later with
  abiFilters / per-ABI splits / app bundle. Tracked in backlog.
- **Design doc vs reality:** design.md sec 14 phase plan predates the OCR
  co-classifier change; the rest of the doc is updated. Minor.
- **GitHub Actions deprecation warning:** actions run on Node 20, forced to Node
  24 after 2026-06-16. Bump action versions when they release Node 24 builds.
- **Spike caveats:** 11 images, several categories stood in by web consent walls;
  host-side CLIP ignores TFLite quantization. See docs/spikes/clip-findings.md.

---

## Decisions log (newest first)

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
