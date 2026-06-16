#!/usr/bin/env python3
"""Generate the charts in docs/eval/performance-and-accuracy.md from the committed eval
CSVs, so the graphs can never silently disagree with the data.

Accuracy charts are computed straight from docs/eval/results-*.csv. The performance chart
(search latency) is transcribed from docs/spikes/scale-test.md — those numbers live only
in that prose table, so keep them in sync by hand if the scale test is re-run.

Usage: python3 scripts/eval/make_charts.py
Requires matplotlib. Writes PNGs into docs/eval/charts/.
"""
import csv
import os

import matplotlib
matplotlib.use("Agg")  # headless: must be set before pyplot
import matplotlib.pyplot as plt  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
EVAL = os.path.normpath(os.path.join(HERE, "..", "..", "docs", "eval"))
OUT = os.path.join(EVAL, "charts")
os.makedirs(OUT, exist_ok=True)
GOOD, AMBER, BAD, GREY, OKAPI = "#2e8b57", "#d99000", "#c0392b", "#9aa0a6", "#1b6ec2"


def load(name):
    with open(os.path.join(EVAL, name)) as f:
        return list(csv.DictReader(f))


# 1. F-Droid per-class precision (post-fix), annotated with support n
rows = load("results-fdroid2.csv")
data = []
for c in sorted(set(r["truth"] for r in rows)):
    preds = [r for r in rows if r["predicted"] == c]
    if not preds:
        continue
    tp = sum(1 for r in preds if r["truth"] == c)
    supp = sum(1 for r in rows if r["truth"] == c)
    data.append((c, 100 * tp / len(preds), supp))
data.sort(key=lambda x: x[1])
fig, ax = plt.subplots(figsize=(8, 5))
vals = [v for _, v, _ in data]
ax.barh([f"{c}  (n={s})" for c, _, s in data], vals,
        color=[GOOD if v >= 60 else (AMBER if v >= 40 else BAD) for v in vals])
for i, v in enumerate(vals):
    ax.text(v + 1, i, f"{v:.0f}%", va="center", fontsize=9)
ax.set_xlabel("Precision (of screens predicted this class, % actually it)")
ax.set_xlim(0, 100)
ax.set_title("F-Droid per-class precision (3,124 real app screenshots, post-fix)\n"
             "Confident visual classes are reliable; n = support in set", fontsize=10)
fig.tight_layout(); fig.savefig(f"{OUT}/fdroid_precision.png", dpi=130); plt.close()

# 2. Fusion paired delta, A/B decomposed (original eval: fdroid + enrico)
allr = load("results-fdroid.csv") + load("results-enrico.csv")
flips = [r for r in allr if r["clip_only"] != r["predicted"]]
A = [r for r in flips if r["clip_only"] != "other" and r["predicted"] != "other"]
B = [r for r in flips if r["clip_only"] == "other" or r["predicted"] == "other"]
net = lambda rs: sum(1 for r in rs if r["predicted"] == r["truth"]) - sum(1 for r in rs if r["clip_only"] == r["truth"])
nets = [net(A), net(B)]
fig, ax = plt.subplots(figsize=(7, 4.5))
ax.bar(["Bucket A\nreal→real\n(confound-free)", "Bucket B\ninvolves 'other'\n(abstention artifact)"],
       nets, color=[GOOD if n >= 0 else BAD for n in nets], width=0.5)
for i, n in enumerate(nets):
    ax.text(i, n + (0.6 if n >= 0 else -0.6), f"net {n:+d}", ha="center",
            va="bottom" if n >= 0 else "top", fontweight="bold")
ax.axhline(0, color="black", lw=0.8)
ax.set_ylabel("Net fusion effect (helped − hurt)")
ax.set_title("Does OCR fusion help? Paired fused-vs-CLIP-only delta\n"
             "(3,364 images) — the confound-free signal is Bucket A: +8", fontsize=10)
ax.text(0.5, -0.30, "Naïve combined net = −15, but B is the app correctly abstaining to 'other',\n"
        "auto-scored wrong because F-Droid has no 'other' truth folder.",
        transform=ax.transAxes, ha="center", fontsize=8, color=GREY)
fig.subplots_adjust(bottom=0.30); fig.savefig(f"{OUT}/fusion_delta.png", dpi=130); plt.close()

# 3. error/crash before vs after the fix
errstats = lambda rs: (sum(1 for r in rs if r["predicted"] == "error / crash"),
                       sum(1 for r in rs if r["predicted"] == "error / crash" and r["needs_review"] == "false"))
b_tot, b_conf = errstats(load("results-fdroid.csv"))
a_tot, a_conf = errstats(load("results-fdroid2.csv"))
fig, ax = plt.subplots(figsize=(6.5, 4.5))
x = ["Before fix\n(v0.6.0)", "After fix\n(v0.6.1)"]
ax.bar(x, [b_tot, a_tot], color=GREY, label="predicted error/crash (all)", width=0.5)
ax.bar(x, [b_conf, a_conf], color=BAD, label="shown confident (not needs-review)", width=0.5)
for i, t in enumerate([b_tot, a_tot]):
    ax.text(i, t + 1, str(t), ha="center", fontweight="bold")
ax.set_ylabel("predictions on 3,124 real F-Droid screenshots")
ax.set_title("error/crash false positives: before vs after the fix\n"
             "(0 of these are real errors — F-Droid has no error screens)", fontsize=10)
ax.legend(fontsize=8); fig.tight_layout(); fig.savefig(f"{OUT}/errorfix.png", dpi=130); plt.close()

# 4. search latency vs library size (TRANSCRIBED from docs/spikes/scale-test.md — verify on re-run)
sizes = [500, 1000, 2000, 5000, 10000]
old = [3.6, 5.5, 12.1, 39.8, 125.7]
cold = [(500, 4), (1000, 6), (5000, 41), (10000, 127)]
warm = [(500, 1.2), (1000, 1.4), (5000, 5.5), (10000, 11.3)]
fig, ax = plt.subplots(figsize=(7.5, 4.8))
ax.plot(sizes, old, "o-", color=BAD, label="before cache (per-query decode)")
ax.plot([s for s, _ in cold], [v for _, v in cold], "s--", color=AMBER, label="after cache: cold (first query, rebuild)")
ax.plot([s for s, _ in warm], [v for _, v in warm], "^-", color=GOOD, label="after cache: warm (repeat query)")
ax.axhline(50, color=GREY, ls=":", lw=1); ax.text(520, 52, "50 ms target", color=GREY, fontsize=8)
ax.set_xlabel("library size (images)"); ax.set_ylabel("search latency (ms, mean of 20)")
ax.set_title("Semantic search latency vs library size (S20 FE AVD)\n"
             "EmbeddingCache makes repeat search ~11 ms at 10k", fontsize=10)
ax.legend(fontsize=8); ax.grid(alpha=0.3); fig.tight_layout()
fig.savefig(f"{OUT}/search_latency.png", dpi=130); plt.close()

# 5. finance↔receipt on the real-bank set
banks = [r for r in load("results-banks.csv") if not r["file"].startswith("_sheet")]
rec = [r for r in banks if r["predicted"] == "receipt"]
rev = [r for r in rec if r["needs_review"] == "true"]
conf = [r for r in rec if r["needs_review"] == "false"]
fig, ax = plt.subplots(figsize=(8, 2.9))
left = 0
for lbl, v, col in [("not predicted receipt", len(banks) - len(rec), GOOD),
                    ("receipt, needs-review", len(rev), AMBER),
                    ("receipt, confident — the BofA 'Receipt Organizer' screen (correct)", len(conf), OKAPI)]:
    ax.barh([0], [v], left=left, color=col, label=f"{lbl} ({v})", height=0.5); left += v
ax.set_yticks([0]); ax.set_yticklabels(["104 bank\nscreenshots"])
ax.set_xlim(0, len(banks)); ax.set_xlabel("screenshots")
ax.set_title("finance↔receipt on real bank apps (18 banks): the classic failure doesn't occur\n"
             "only 4/104 predicted receipt; the one confident prediction is literally a receipt screen", fontsize=9.5)
ax.legend(fontsize=8, loc="upper center", bbox_to_anchor=(0.5, -0.45), ncol=1, frameon=False)
fig.savefig(f"{OUT}/bank_receipt.png", dpi=130, bbox_inches="tight"); plt.close()

print("charts ->", OUT)
