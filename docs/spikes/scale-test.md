# Scale / load test findings (2026-06-14)

The app's whole purpose is classifying and searching a *large* screenshot library,
but until now the suite only ever exercised a handful of rows. This spike measures
the two things that actually scale with library size:

1. **Search + memory** at 500 .. 10,000 images.
2. **Classification throughput** (the per-screenshot pipeline draining a deep queue).

Both run on the Galaxy S20 FE-shaped AVD (1080x2400, API 33) via instrumented
tests against a **real on-disk Room database** (not in-memory), so SQLite blob I/O
is real. Tests:

- `ScaleInstrumentedTest` — seeds N synthetic 512-d L2-normalized embeddings + OCR
  text, then drives the real `SemanticSearcher` and `ScreenshotRepository.hybridSearch`.
- `ProcessingThroughputTest` — runs the real `ImageProcessor` (real ML Kit OCR +
  heuristics, CLIP model absent) over 300 real bundled screenshots, in the same loop
  the worker uses.

> Caveat: emulator, not a physical S20 FE. A real device is faster, especially for
> ML Kit OCR and SQLite. Treat these as conservative.

## 1. Search latency + memory

| Images | Visual search | Hybrid (visual+OCR+RRF) | Vectors in RAM | Reorg routing |
|-------:|--------------:|------------------------:|---------------:|--------------:|
| 500    | 3.6 ms        | 5.7 ms                  | 1.0 MB         | 7 ms          |
| 1,000  | 5.5 ms        | 5.6 ms                  | 2.0 MB         | 9 ms          |
| 2,000  | 12.1 ms       | 14.2 ms                 | 3.9 MB         | 16 ms         |
| 5,000  | 39.8 ms       | 43.7 ms                 | 9.8 MB         | 36 ms         |
| 10,000 | **125.7 ms**  | **138.0 ms**            | 19.5 MB        | 67 ms         |

(latencies are the mean of 20 runs after a warmup query)

**Findings:**

- **At realistic sizes (500 .. 1000) search is excellent: under 6 ms.** No issue at
  the volume most users will ever reach.
- **The design's "<50 ms search, brute force fine to ~20k" claim is wrong on this
  hardware.** Latency is linear in library size and crosses the 50 ms target between
  5k and 10k. At 10k it is ~126 ms (2.5x over target); linear extrapolation puts 20k
  near ~250 ms. Memory (~20 MB at 10k) matches the design estimate.
- **Root cause is structural, and fixable.** `SemanticSearcher.search()` calls
  `dao.allEmbeddings()` on *every query*, re-reading and re-deserializing every blob
  from SQLite. The dot products are cheap; the per-query deserialization dominates.
  Caching the decoded `FloatArray`s in memory (load once, invalidate on new embedding)
  would make repeat searches sub-millisecond and push the real ceiling well past 20k.
  Logged as a TODO — not urgent, because it only bites past ~5k images.

## 2. Classification throughput (OCR-only path)

300 real screenshots through the real `ImageProcessor`, CLIP model not installed:

| Metric | Value |
|---|---|
| Processed / failed | 300 / 0 |
| All reached DONE | yes (300/300) |
| Total | 6.56 s |
| Per image | 21.9 ms |
| Slowest (first, warmup) | 122 ms |
| Heap before -> after (post-GC) | 6 MB -> 3 MB (no leak) |
| Projected for 1000 | ~22 s |

**Findings:**

- The pipeline drains a few-hundred-deep queue cleanly: steady per-image latency,
  every item reaches a terminal state, and the heap is flat across the batch (no
  leak / unbounded growth).
- OCR-only classification of 1000 screenshots is ~22 s on the emulator. A real device
  is faster.
- **Not covered here:** the CLIP image-encode cost per screenshot (needs the 90 MB
  model; design estimates 1-2 s/image, which would dominate a full re-embed and is the
  thing to benchmark next), and WorkManager/JobScheduler scheduling at volume. The
  scheduler was deliberately bypassed: its emulator wedging ("Job didn't exist in
  JobStore") is a known harness artifact, not app logic, so driving `ImageProcessor`
  directly measures the app's real batch behavior without that noise.

## Bottom line

For the library sizes the user actually has (hundreds to low thousands), both search
and classification are fast and stable. The only real finding is that the design
overstated the brute-force search ceiling: it is fine to ~5k, not ~20k, until the
embedding-cache optimization lands.
