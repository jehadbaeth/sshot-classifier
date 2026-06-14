"""
Convert the CLIP ViT-B/32 (LAION-2B) TEXT encoder to TFLite for Phase 3
free-text semantic search. The text encoder embeds an arbitrary query into the
SAME 512-d space as the on-device image embeddings, so cosine over stored image
vectors gives visual search.

Tries litert_torch recipes (int8 weight-only, then fp16) and keeps the first
that converts cleanly. Validates parity vs PyTorch over query prompts, and
cross-checks that the TFLite text embedding for a label prompt matches the
bundled label_embeddings.f32 used by zero-shot tagging (same space sanity).

Usage: python convert_text_encoder.py
"""
import os
import json
import numpy as np
import torch
import open_clip

ARCH = "ViT-B-32"
PRETRAINED = "laion2b_s34b_b79k"
CONTEXT_LEN = 77
OUT = os.path.join(os.path.dirname(__file__), "out")
ASSETS = os.path.join(
    os.path.dirname(__file__), "..", "..",
    "app", "src", "main", "assets", "clip",
)

# Query prompts to measure parity on (free-text, not label-shaped).
QUERIES = [
    "a screenshot of a map with directions",
    "source code in a text editor",
    "a bank account balance",
    "sunset over the mountains",
    "a chat conversation about dinner plans",
    "an error message dialog",
    "a shopping cart with sneakers",
    "my flight boarding pass",
]


class TextTower(torch.nn.Module):
    """Wraps encode_text so the exported graph takes int token ids -> 512-d."""

    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, tokens):
        return self.model.encode_text(tokens, normalize=False)


def main():
    os.makedirs(OUT, exist_ok=True)
    model, _, _ = open_clip.create_model_and_transforms(ARCH, pretrained=PRETRAINED)
    model.eval()
    tokenizer = open_clip.get_tokenizer(ARCH)

    tower = TextTower(model).eval()
    sample = tokenizer(["a sample query"])  # (1, 77) int
    sample = sample.to(torch.int32)
    print("sample token shape", tuple(sample.shape), sample.dtype)

    import litert_torch
    from litert_torch.generative.quantize import quant_recipes

    recipes = [
        ("int8w", "clip_text_b32_int8w.tflite", quant_recipes.full_weight_only_recipe),
        ("fp16", "clip_text_b32_f16.tflite", quant_recipes.full_fp16_recipe),
    ]

    for name, fname, recipe_fn in recipes:
        path = os.path.join(OUT, fname)
        print(f"\n### trying recipe: {name} ###")
        try:
            edge = litert_torch.convert(tower, (sample,), quant_config=recipe_fn())
            edge.export(path)
        except Exception as e:
            print(f"  {name} FAILED: {repr(e)[:300]}")
            continue
        size = os.path.getsize(path) / 1e6
        cosines = parity(tower, tokenizer, path)
        print(f"  {name}: {size:.1f} MB   min cos={min(cosines):.4f}  mean cos={sum(cosines)/len(cosines):.4f}")

    cross_check_labels(tower, tokenizer)


def parity(tower, tokenizer, path):
    from ai_edge_litert.interpreter import Interpreter
    interp = Interpreter(model_path=path)
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    cosines = []
    for q in QUERIES:
        tok = tokenizer([q]).to(torch.int32)
        with torch.no_grad():
            t = tower(tok)[0].numpy()
        interp.set_tensor(inp["index"], tok.numpy().astype(inp["dtype"]))
        interp.invoke()
        l = interp.get_tensor(out["index"])[0]
        cosines.append(float(np.dot(t, l) / (np.linalg.norm(t) * np.linalg.norm(l))))
    return cosines


def cross_check_labels(tower, tokenizer):
    """Confirm the text tower lands in the same space as bundled label embeddings.
    Not an exact match (labels were prompt-ensembled), but a single-prompt embed
    of a label should have high cosine with its bundled, normalized vector."""
    labels_path = os.path.join(ASSETS, "labels.json")
    emb_path = os.path.join(ASSETS, "label_embeddings.f32")
    if not (os.path.exists(labels_path) and os.path.exists(emb_path)):
        print("\n(skip cross-check: bundled label assets not found)")
        return
    meta = json.load(open(labels_path))
    labels = meta["labels"] if isinstance(meta, dict) and "labels" in meta else meta
    vecs = np.fromfile(emb_path, dtype=np.float32).reshape(len(labels), 512)
    print("\n### cross-check vs bundled label_embeddings.f32 ###")
    for i, lab in enumerate(labels[:6]):
        name = lab["internal"] if isinstance(lab, dict) else str(lab)
        tok = tokenizer([name]).to(torch.int32)
        with torch.no_grad():
            t = tower(tok)[0].numpy()
        t = t / np.linalg.norm(t)
        v = vecs[i] / np.linalg.norm(vecs[i])
        print(f"  {name:18s} cos(single-prompt, bundled) = {float(np.dot(t, v)):.3f}")


if __name__ == "__main__":
    main()
