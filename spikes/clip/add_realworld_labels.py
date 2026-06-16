"""
Append the real-world capture labels (REALWORLD_LABELS in precompute_labels.py)
to the committed CLIP label assets WITHOUT touching the existing rows.

Why append instead of a full regenerate: the 22 screenshot label embeddings were
validated by the classification eval (docs/eval/performance-and-accuracy.md). A full
regenerate recomputes every row and can drift at floating-point noise level across
torch/open_clip versions, which would silently change screenshot classification.
Appending leaves label_embeddings.f32's existing bytes exactly as-is and only adds
the new real-world rows, so screenshot behavior is provably unchanged.

Idempotent: labels already present (matched by internal phrase) are skipped, so
re-running adds nothing.

Usage: python add_realworld_labels.py
"""
import os, json, struct

from precompute_labels import (
    ASSETS, PHOTO_TEMPLATES, REALWORLD_LABELS, embed_label, load_model,
)

DIM = 512
LABELS_JSON = os.path.join(ASSETS, "labels.json")
EMB_F32 = os.path.join(ASSETS, "label_embeddings.f32")


def main():
    with open(LABELS_JSON) as fh:
        labels = json.load(fh)
    existing_internal = {e["internal"] for e in labels}

    emb_bytes = open(EMB_F32, "rb").read()
    expected = len(labels) * DIM * 4
    if len(emb_bytes) != expected:
        raise SystemExit(
            f"ABORT: {EMB_F32} is {len(emb_bytes)} bytes but labels.json has "
            f"{len(labels)} rows ({expected} bytes expected). Files are out of sync; "
            f"fix before appending."
        )

    to_add = [(c, t) for c, t in REALWORLD_LABELS if c not in existing_internal]
    if not to_add:
        print("nothing to add; all real-world labels already present")
        return

    model, tokenizer = load_model()
    new_vectors = [embed_label(model, tokenizer, c, PHOTO_TEMPLATES) for c, _ in to_add]

    # Append: existing bytes untouched, new rows tacked on the end in order.
    with open(EMB_F32, "ab") as fh:
        for v in new_vectors:
            assert len(v) == DIM, f"unexpected dim {len(v)}"
            fh.write(struct.pack(f"<{DIM}f", *v))

    labels.extend({"internal": c, "tag": t} for c, t in to_add)
    with open(LABELS_JSON, "w") as fh:
        json.dump(labels, fh, indent=2)

    # Verify alignment after writing.
    final = os.path.getsize(EMB_F32)
    if final != len(labels) * DIM * 4:
        raise SystemExit(f"POST-CHECK FAILED: {final} bytes != {len(labels)} rows")

    print(f"appended {len(to_add)} labels: {[t for _, t in to_add]}")
    print(f"labels.json now {len(labels)} rows; {EMB_F32} now {final} bytes (aligned)")


if __name__ == "__main__":
    main()
