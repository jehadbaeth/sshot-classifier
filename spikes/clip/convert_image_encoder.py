"""
Convert the CLIP ViT-B/32 (LAION-2B) image encoder to TFLite and validate that
the TFLite outputs match PyTorch on real screenshots.

This is the Phase 2 de-risking step: if we cannot get a faithful on-device image
encoder, the whole on-device tagging + search plan is in trouble.

Outputs into spikes/clip/out/:
  clip_image_b32_f32.tflite
  clip_image_b32_f16.tflite
  parity.txt   (per-image cosine between torch and tflite embeddings)

Usage: python convert_image_encoder.py <dir-of-screenshots>
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

    # open_clip's image embedding is exactly model.visual(image).
    sample = torch.randn(1, 3, 224, 224)
    with torch.no_grad():
        ref = visual(sample)
    print("torch visual output shape:", tuple(ref.shape))

    import litert_torch

    print("converting to TFLite (float32)...")
    edge = litert_torch.convert(visual, (sample,))
    f32_path = os.path.join(OUT, "clip_image_b32_f32.tflite")
    edge.export(f32_path)
    print("wrote", f32_path, sz(f32_path))

    # float16 weights (smaller, usually negligible quality loss for ViT).
    f16_path = os.path.join(OUT, "clip_image_b32_f16.tflite")
    try:
        from litert_torch.quantize import quant_recipes
        recipe = quant_recipes.full_fp16_recipe()
        edge16 = litert_torch.convert(visual, (sample,), quant_config=recipe)
        edge16.export(f16_path)
        print("wrote", f16_path, sz(f16_path))
    except Exception as e:
        print("float16 conversion skipped:", repr(e))
        f16_path = None

    # Parity check on real screenshots.
    from ai_edge_litert.interpreter import Interpreter
    interp = Interpreter(model_path=f32_path)
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]

    files = sorted(
        f for f in glob.glob(os.path.join(shots_dir, "*.png"))
        if not os.path.basename(f).startswith("_")
    )
    lines = [f"model: {ARCH}/{PRETRAINED}", ""]
    cosines = []
    for f in files:
        img = preprocess(Image.open(f).convert("RGB")).unsqueeze(0)
        with torch.no_grad():
            t = visual(img)[0].numpy()
        interp.set_tensor(inp["index"], img.numpy().astype(np.float32))
        interp.invoke()
        l = interp.get_tensor(out["index"])[0]
        cos = float(np.dot(t, l) / (np.linalg.norm(t) * np.linalg.norm(l)))
        cosines.append(cos)
        lines.append(f"{os.path.basename(f)[:-4]:16s} cos(torch,tflite)={cos:.5f}")
    lines += ["", f"min cosine = {min(cosines):.5f}", f"mean cosine = {sum(cosines)/len(cosines):.5f}"]

    report = "\n".join(lines)
    with open(os.path.join(OUT, "parity.txt"), "w") as fh:
        fh.write(report + "\n")
    print("\n" + report)


def sz(p):
    return f"{os.path.getsize(p)/1e6:.1f} MB"


if __name__ == "__main__":
    main()
