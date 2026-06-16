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

There are two label groups:
  - SCREENSHOT_LABELS  : on-screen UI categories, embedded with screenshot-style
                         prompt templates.
  - REALWORLD_LABELS   : physical-world things a hand camera captures (storefront,
                         street sign, ...), embedded with photo-style prompts. Added
                         for the camera-capture inventory feature (Phase A).

IMPORTANT — how the committed assets were actually produced:
This script is the canonical, reproducible spec for the WHOLE label set. But the
committed label_embeddings.f32 was NOT regenerated from scratch when the real-world
labels were added. The real-world rows were APPENDED to the existing file by
add_realworld_labels.py, leaving the 22 screenshot rows byte-identical, so the
validated screenshot classification eval (docs/eval/performance-and-accuracy.md)
could not drift. Running this script from scratch will recompute every row and may
differ from the committed bytes at the floating-point noise level if the local
torch/open_clip versions differ from those used originally. Prefer the append path
for incremental label additions; use a full regen only when you intend to re-run
the eval.

Usage: python precompute_labels.py        # full regenerate (overwrites both files)
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

# Screenshot-style templates: the subject is something on a phone screen.
SCREENSHOT_TEMPLATES = [
    "a screenshot of {}.",
    "a phone screenshot showing {}.",
    "an image of {}.",
    "{} on a smartphone screen.",
]

# Photo-style templates: the subject is a real-world thing in front of a camera.
PHOTO_TEMPLATES = [
    "a photo of {}.",
    "a picture of {}.",
    "a phone photo of {}.",
    "{} photographed with a phone camera.",
]

# (internal concept phrase, user-facing tag)
SCREENSHOT_LABELS = [
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
    # "an error message dialog" maps to "other", NOT "error / crash": CLIP cannot
    # visually distinguish an error dialog from any ordinary modal (name prompts,
    # permission popups, confirm dialogs all look the same), so as an error class it
    # fired on 80 normal screens for 0 correct in the 2026-06-16 eval. Kept as an
    # "other"/decoy so modal-dialog probability mass routes to needs-review instead.
    # error/crash is now an OCR-only tag (OcrHeuristics): error text is the reliable
    # signal, matching the CLIP-for-visual / OCR-for-text split. See docs/eval/results.md.
    ("an error message dialog", "other"),
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
    ("an email inbox", "email"),
]

# Real-world capture labels (Phase A). Embedded with PHOTO_TEMPLATES. These add new
# user-facing tags; they do not change any screenshot tag. A "qr code" CLIP label is
# included as a visual backstop, but the authoritative QR signal is the on-device
# barcode decoder (BarcodeExtractor), not CLIP.
REALWORLD_LABELS = [
    ("a storefront or shop front", "storefront"),
    ("a billboard or outdoor advertisement", "advertisement"),
    ("a street sign or signboard", "street sign"),
    ("a business card", "business card"),
    ("product packaging or a product label", "product"),
    ("a restaurant menu", "menu"),
    ("a poster or flyer", "poster"),
    ("a QR code", "qr code"),
]


def load_model():
    model, _, _ = open_clip.create_model_and_transforms(ARCH, pretrained=PRETRAINED)
    model.eval()
    tokenizer = open_clip.get_tokenizer(ARCH)
    return model, tokenizer


def embed_label(model, tokenizer, concept, templates):
    """Prompt-ensemble one concept over the given templates -> one L2-normalized vector."""
    with torch.no_grad():
        toks = tokenizer([t.format(concept) for t in templates])
        f = model.encode_text(toks)
        f /= f.norm(dim=-1, keepdim=True)
        f = f.mean(dim=0)
        f /= f.norm()
        return f.numpy().astype("float32")


def main():
    os.makedirs(ASSETS, exist_ok=True)
    model, tokenizer = load_model()

    vectors = []
    labels_json = []
    for concept, tag in SCREENSHOT_LABELS:
        vectors.append(embed_label(model, tokenizer, concept, SCREENSHOT_TEMPLATES))
        labels_json.append({"internal": concept, "tag": tag})
    for concept, tag in REALWORLD_LABELS:
        vectors.append(embed_label(model, tokenizer, concept, PHOTO_TEMPLATES))
        labels_json.append({"internal": concept, "tag": tag})

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
