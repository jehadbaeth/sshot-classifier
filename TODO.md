# Project TODO and Working Log

Living, cross-session tracker for the Screenshot Classifier. We both edit this.
The design lives in [docs/design.md](docs/design.md); spike results in
[docs/spikes/](docs/spikes/). This file tracks state, next steps, ideas, issues,
and the decisions we have locked so we do not relitigate them.

Convention: `[ ]` todo, `[x]` done, `[~]` in progress, `[!]` blocked.
Keep absolute dates. Newest decisions at the top of the decisions log.

---

## Current state (snapshot)

- **2026-06-14:** Phase 1 done and verified on emulator (OCR + FTS text search +
  OCR heuristic tagging + background processing). Phase 0 before it. Repo on
  `git@github.com:jehadbaeth/sshot-classifier.git` (private), CI green.
- Latest tag: `v0.2.0` (target). Branch: `main`.

---

## Now / next up

- [ ] Phase 2: CLIP integration (pick TFLite LAION-2B port, model download,
      embeddings, zero-shot scoring, fuse with OCR heuristics + margin gate).
- [ ] Heuristic tuning pass: reduce false positives (see Known issues), consider
      requiring a minimum total score before attaching a tag.
- [ ] Shrink the APK (still ~117 MB debug). See Known issues.

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

- **OCR heuristics are a first pass and over-fire.** Fixed the worst (status-bar
  clock -> chat on everything; `#include` -> social on all code). Remaining: rules
  trigger on a single weak keyword with no minimum-score floor; calendar time
  pattern could false-positive on 12h status-bar clocks; currency pattern is
  shared by receipt/finance/shopping. These are signals for Phase 2 fusion, not
  final tags, but a min-score floor and better disambiguation are wanted.
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
