"""
Quantize the CLIP ViT-B/32 (LAION-2B) image encoder to shrink it from ~351 MB.
Tries litert_torch recipes (int8 weight-only, then fp16) and keeps the first that
converts cleanly. Validates parity vs PyTorch and reports size.

Usage: python convert_quantized.py <dir-of-screenshots>
"""
import sys, os, glob
import numpy as np
import torch
import open_clip
from PIL import Image

ARCH = "ViT-B-32"
PRETRAINED = "laion2b_s34b_b79k"
OUT = os.path.join(os.path.dirname(__file__), "out")


def main():
    shots_dir = sys.argv[1]
    os.makedirs(OUT, exist_ok=True)

    model, _, preprocess = open_clip.create_model_and_transforms(ARCH, pretrained=PRETRAINED)
    model.eval()
    visual = model.visual.eval()
    sample = torch.randn(1, 3, 224, 224)

    import litert_torch
    from litert_torch.generative.quantize import quant_recipes

    files = sorted(
        f for f in glob.glob(os.path.join(shots_dir, "*.png"))
        if not os.path.basename(f).startswith("_")
    )

    recipes = [
        ("int8w", "clip_image_b32_int8w.tflite", quant_recipes.full_weight_only_recipe),
        ("fp16", "clip_image_b32_f16.tflite", quant_recipes.full_fp16_recipe),
    ]

    for name, fname, recipe_fn in recipes:
        path = os.path.join(OUT, fname)
        print(f"\n### trying recipe: {name} ###")
        try:
            edge = litert_torch.convert(visual, (sample,), quant_config=recipe_fn())
            edge.export(path)
        except Exception as e:
            print(f"  {name} FAILED: {repr(e)[:200]}")
            continue
        size = os.path.getsize(path) / 1e6
        cosines = parity(visual, preprocess, path, files)
        print(f"  {name}: {size:.1f} MB   min cos={min(cosines):.4f}  mean cos={sum(cosines)/len(cosines):.4f}")


def parity(visual, preprocess, path, files):
    from ai_edge_litert.interpreter import Interpreter
    interp = Interpreter(model_path=path)
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    cosines = []
    for f in files:
        img = preprocess(Image.open(f).convert("RGB")).unsqueeze(0)
        with torch.no_grad():
            t = visual(img)[0].numpy()
        interp.set_tensor(inp["index"], img.numpy().astype(np.float32))
        interp.invoke()
        l = interp.get_tensor(out["index"])[0]
        cosines.append(float(np.dot(t, l) / (np.linalg.norm(t) * np.linalg.norm(l))))
    return cosines


if __name__ == "__main__":
    main()
