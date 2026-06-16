#!/usr/bin/env python3
"""Render a small set of synthetic Android error/crash dialogs for an end-to-end
MECHANISM check of the error/crash fix (docs/eval/results.md, 2026-06-16).

WHY SYNTHETIC: real mobile crash/error screenshots are not harvestable from the
open web with scriptable tooling. The "how to fix X error" article ecosystem is
overwhelmingly stock photos, settings screenshots, desktop recovery tools and
stylized graphics, not the actual error dialog (verified across ~9 troubleshooting
articles, ~35 candidate images, ~0 usable). So these are RENDERED from real,
documented Android system error strings.

WHAT THEY VALIDATE (and what they do NOT):
  - DO: confirm the OCR error rule (OcrHeuristics) fires on real Android error text,
    and that after the CLIP-label remap, error dialogs route to other/needs-review
    rather than a confident wrong "error / crash". A mechanism check.
  - DO NOT: measure field recall. The strings here are the same documented strings
    that drove the OCR keyword choice, so recall on them is not an independent metric.
    Real-error recall remains unvalidated for lack of real positive data. Stated
    plainly in the writeup; do not promote this to a success number.

Usage: python3 scripts/eval/gen_synth_errors.py <out_dir>
Writes <out_dir>/error/*.png (one subdir 'error' so it slots into the eval harness,
whose slugToTag maps error -> "error / crash").
"""
import os
import sys
from PIL import Image, ImageDraw, ImageFont

# Any sans-serif + monospace TTF works; these are macOS defaults.
SANS = "/System/Library/Fonts/Supplemental/Arial.ttf"
BOLD = "/System/Library/Fonts/Supplemental/Arial Bold.ttf"
MONO = "/System/Library/Fonts/Monaco.ttf"
W, H = 1080, 2340


def font(sz, bold=False, mono=False):
    return ImageFont.truetype(MONO if mono else (BOLD if bold else SANS), sz)


def base(dark=False):
    img = Image.new("RGB", (W, H), (18, 18, 20) if dark else (235, 236, 238))
    d = ImageDraw.Draw(img)
    d.text((40, 30), "22:47", font=font(34, bold=True), fill=(200, 200, 200) if dark else (40, 40, 40))
    return img, d


def dialog(d, title, body, buttons, dark=False, top=820):
    card = (40, 40, 44) if dark else (255, 255, 255)
    tcol = (245, 245, 245) if dark else (20, 20, 20)
    bcol = (200, 200, 205) if dark else (90, 90, 90)
    x0, x1, y0, y1 = 110, W - 110, top, top + 560
    d.rounded_rectangle([x0, y0, x1, y1], 28, fill=card)
    d.text((x0 + 50, y0 + 50), title, font=font(52, bold=True), fill=tcol)
    yy = y0 + 160
    for line in body:
        d.text((x0 + 50, yy), line, font=font(40), fill=bcol)
        yy += 58
    bx = x1 - 60
    for b in buttons[::-1]:
        w = int(d.textlength(b, font=font(40, bold=True)))
        d.text((bx - w, y1 - 90), b, font=font(40, bold=True), fill=(80, 140, 240))
        bx -= w + 70


def main():
    out = os.path.join(sys.argv[1] if len(sys.argv) > 1 else ".", "error")
    os.makedirs(out, exist_ok=True)

    def save(name, img):
        img.save(os.path.join(out, name))

    img, d = base(True); dialog(d, "App keeps stopping", ["Skytube keeps stopping."], ["App info", "Close app"], dark=True); save("01_keeps_stopping.png", img)
    img, d = base(); dialog(d, "Maps isn't responding", ["Do you want to close it or wait", "for it to respond?"], ["Wait", "Close app"]); save("02_isnt_responding.png", img)
    img, d = base(); dialog(d, "", ["Unfortunately, Settings has stopped."], ["OK"]); save("03_unfortunately_stopped.png", img)
    img, d = base(); dialog(d, "Something went wrong", ["Something went wrong. Try again.", "Error retrieving information from", "server. [DF-DFERH-01]"], ["Retry"]); save("04_went_wrong.png", img)
    img, d = base(); dialog(d, "", ["Process system isn't responding.", "Do you want to close it?"], ["Wait", "OK"]); save("05_process_system.png", img)
    img, d = base(True); dialog(d, "", ["Unfortunately, System UI has stopped."], ["OK"], dark=True); save("06_systemui.png", img)
    img, d = base(True); dialog(d, "Application Not Responding", ["com.android.chrome is not responding."], ["Wait", "Close"], dark=True); save("07_anr.png", img)
    img, d = base(True)
    trace = ["FATAL EXCEPTION: main", "java.lang.NullPointerException: Attempt to",
             "invoke virtual method 'int android.view.View", ".getId()' on a null object reference",
             "  at com.example.app.MainActivity.onCreate", "  (MainActivity.java:42)",
             "  at android.app.Activity.performCreate", "  (Activity.java:7136)"]
    yy = 200
    for ln in trace:
        d.text((50, yy), ln, font=font(34, mono=True), fill=(240, 120, 120)); yy += 56
    save("08_stacktrace.png", img)
    img, d = base(); dialog(d, "Connection error", ["Couldn't connect to the server.", "Error code: 0x80004005"], ["Cancel", "Retry"]); save("09_conn_error.png", img)
    img, d = base(True); dialog(d, "", ["Telegram has crashed. A report will", "be sent to the developer."], ["Restart app"], dark=True); save("10_crashed.png", img)
    img, d = base(); dialog(d, "", ["Something went wrong on our end.", "Please try again."], ["Try again"]); save("11_server_wrong.png", img)
    img, d = base(); dialog(d, "Gallery keeps stopping", ["This app keeps stopping."], ["Send feedback", "App info", "Close app"]); save("12_keeps_stopping2.png", img)
    print("rendered 12 synthetic error dialogs ->", out)


if __name__ == "__main__":
    main()
