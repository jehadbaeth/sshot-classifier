"""
Precompute prompt-ensembled, L2-normalized CLIP text embeddings for a fixed
label set, so the app can do zero-shot tagging with only the IMAGE encoder on
device (no on-device text encoder / tokenizer needed for tagging).

Must use the SAME arch/weights as the converted image encoder.

Outputs (committed assets, small):
  app/src/main/assets/clip/labels.json          ordered [{internal, tag}]
  app/src/main/assets/clip/label_embeddings.f32 N*512 little-endian float32

Concrete "decoy" labels (clock, settings, dialer, ...) absorb probability mass
so utility screens are not forced into a wrong real category. They map to the
user-facing tag "other". (See docs/spikes/clip-findings.md.)

Usage: python precompute_labels.py
"""
import os, json, struct
import torch
import open_clip

ARCH = "ViT-B-32"
PRETRAINED = "laion2b_s34b_b79k"

ASSETS = os.path.join(
    os.path.dirname(__file__), "..", "..",
    "app", "src", "main", "assets", "clip",
)

TEMPLATES = [
    "a screenshot of {}.",
    "a phone screenshot showing {}.",
    "an image of {}.",
    "{} on a smartphone screen.",
]

# (internal concept phrase, user-facing tag)
LABELS = [
    ("a social media app feed", "social media"),
    ("a receipt or invoice", "receipt"),
    ("a map", "map"),
    ("program source code", "code editor"),
    ("a chat conversation", "chat / messaging"),
    ("a text document", "document"),
    ("a web browser page", "browser / web"),
    ("a video game", "game"),
    ("an online shopping product page", "shopping"),
    ("a news article", "news"),
    ("a video streaming app", "video / streaming"),
    ("an error message dialog", "error / crash"),
    ("a calendar", "calendar"),
    ("a banking or finance app", "finance"),
    ("a photo or picture", "other"),
    # Decoy utility-app labels -> "other".
    ("a clock app", "other"),
    ("a contacts list", "other"),
    ("a phone dialer", "other"),
    ("a file manager", "other"),
    ("a phone settings screen", "other"),
    ("a music player", "video / streaming"),
    ("an email inbox", "document"),
]


def main():
    os.makedirs(ASSETS, exist_ok=True)
    model, _, _ = open_clip.create_model_and_transforms(ARCH, pretrained=PRETRAINED)
    model.eval()
    tokenizer = open_clip.get_tokenizer(ARCH)

    vectors = []
    with torch.no_grad():
        for concept, _tag in LABELS:
            toks = tokenizer([t.format(concept) for t in TEMPLATES])
            f = model.encode_text(toks)
            f /= f.norm(dim=-1, keepdim=True)
            f = f.mean(dim=0)
            f /= f.norm()
            vectors.append(f.numpy().astype("float32"))

    labels_json = [{"internal": c, "tag": t} for c, t in LABELS]
    with open(os.path.join(ASSETS, "labels.json"), "w") as fh:
        json.dump(labels_json, fh, indent=2)

    with open(os.path.join(ASSETS, "label_embeddings.f32"), "wb") as fh:
        for v in vectors:
            fh.write(struct.pack(f"<{len(v)}f", *v))

    dim = len(vectors[0])
    print(f"wrote {len(vectors)} labels x {dim} dims")
    print("labels.json + label_embeddings.f32 ->", os.path.normpath(ASSETS))


if __name__ == "__main__":
    main()
