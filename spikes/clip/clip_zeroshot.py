"""
CLIP zero-shot spike for the screenshot classifier.

Purpose: validate whether CLIP ViT-B/32 (the model we plan to port to TFLite for
on-device use) produces sensible zero-shot tags on real Android screenshots,
BEFORE building the full on-device pipeline. Host-side ViT-B/32 quality is a
valid proxy for the TFLite port (same weights; quantization aside).

Runs two label sets:
  A) the design doc's 15-label taxonomy as-is
  B) an expanded set adding the specific utility-app labels in the test set,
     to see whether CLIP CAN distinguish them when given the option.

Usage: python clip_zeroshot.py <dir-of-screenshots>
"""
import sys, glob, os
import torch
import open_clip

# Design taxonomy -> short concept phrase (templates above wrap each one).
TAXONOMY = {
    "social media": "a social media app",
    "receipt": "a receipt",
    "map": "a map",
    "code editor": "program source code",
    "chat / messaging": "a chat conversation",
    "document": "a text document",
    "browser / web": "a web browser",
    "game": "a video game",
    "shopping": "an online shopping app",
    "news": "a news article",
    "video / streaming": "a video streaming app",
    "error / crash": "an error message dialog",
    "calendar": "a calendar",
    "finance": "a banking or finance app",
    "other": "a phone app",
}

# Expanded set: taxonomy plus specific utility apps present in the test set.
EXPANDED = dict(TAXONOMY)
EXPANDED.update({
    "clock": "a clock app",
    "contacts": "a contacts list",
    "phone dialer": "a phone dialer",
    "file manager": "a file manager",
    "settings": "a phone settings screen",
})


# Prompt-ensemble templates, averaged per label (standard CLIP zero-shot practice).
TEMPLATES = [
    "a screenshot of {}.",
    "a phone screenshot showing {}.",
    "an image of {}.",
    "{} on a smartphone screen.",
]


def encode_labels(model, tokenizer, device, concepts):
    """concepts: list of short noun phrases. Returns ensembled, normalized text features."""
    feats = []
    with torch.no_grad():
        for c in concepts:
            toks = tokenizer([t.format(c) for t in TEMPLATES]).to(device)
            f = model.encode_text(toks)
            f /= f.norm(dim=-1, keepdim=True)
            f = f.mean(dim=0)
            f /= f.norm()
            feats.append(f)
    return torch.stack(feats)


def run(model, preprocess, tokenizer, device, images, labels, concepts, title):
    with torch.no_grad():
        tfeat = encode_labels(model, tokenizer, device, concepts)
        logit_scale = model.logit_scale.exp()  # ~100, i.e. softmax temperature ~0.01

        print(f"\n{'='*70}\n{title}\n{'='*70}")
        for name, img in images:
            ifeat = model.encode_image(img)
            ifeat /= ifeat.norm(dim=-1, keepdim=True)
            weights = (logit_scale * ifeat @ tfeat.T).softmax(dim=-1)[0]
            top = sorted(zip(labels, weights.tolist()), key=lambda x: -x[1])[:3]
            tags = "  ".join(f"{l}:{w:.2f}" for l, w in top)
            print(f"{name:16s} -> {tags}")


def main():
    d = sys.argv[1]
    arch = sys.argv[2] if len(sys.argv) > 2 else "ViT-B-32"
    pretrained = sys.argv[3] if len(sys.argv) > 3 else "openai"
    device = "cpu"
    print(f"model: {arch} / {pretrained}")
    model, _, preprocess = open_clip.create_model_and_transforms(arch, pretrained=pretrained)
    model.eval().to(device)
    tokenizer = open_clip.get_tokenizer(arch)

    files = sorted(
        f for f in glob.glob(os.path.join(d, "*.png"))
        if not os.path.basename(f).startswith("_")
    )
    from PIL import Image
    images = []
    with torch.no_grad():
        for f in files:
            im = preprocess(Image.open(f).convert("RGB")).unsqueeze(0).to(device)
            images.append((os.path.basename(f)[:-4], im))

    run(model, preprocess, tokenizer, device,
        images, list(TAXONOMY.keys()), list(TAXONOMY.values()),
        "A) Design taxonomy (15 labels)")
    run(model, preprocess, tokenizer, device,
        images, list(EXPANDED.keys()), list(EXPANDED.values()),
        "B) Expanded taxonomy (+ utility apps)")


if __name__ == "__main__":
    main()
