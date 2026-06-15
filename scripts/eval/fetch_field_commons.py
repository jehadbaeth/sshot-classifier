#!/usr/bin/env python3
"""Download openly-licensed FOSS app screenshots from Wikimedia Commons as a small
"field" set for the fix-relevant classes (email, social media, document).

These are DESKTOP app screenshots (Thunderbird/Evolution/KMail, Diaspora/Friendica,
LibreOffice/gedit). They are a legitimate, attributable, directional proxy only:
the app targets phone screenshots, and Commons has little free-licensed Gmail/Reddit/
Instagram mobile content (that content is copyrighted). Treat the resulting numbers as
optimistic and small-N. See docs/eval/results.md and docs/eval/field_manifest.json.

Usage: python3 scripts/eval/fetch_field_commons.py [.evaldata]
"""
import urllib.request, urllib.parse, json, os, shutil, ssl, sys, time

OUT = sys.argv[1] if len(sys.argv) > 1 else ".evaldata"
DST = os.path.join(OUT, "field_slice")
API = "https://commons.wikimedia.org/w/api.php"
UA = {"User-Agent": "sshot-classifier-eval/1.0 (research; mohamed.baeth@okapiorbits.com)"}
ctx = ssl.create_default_context()

PLAN = {
    "email": ["Mozilla Thunderbird email client screenshot",
              "GNOME Evolution mail screenshot", "KMail screenshot inbox"],
    "social_media": ["Mastodon web interface screenshot",
                     "Diaspora social network screenshot", "Friendica screenshot"],
    "document": ["LibreOffice Writer document screenshot",
                 "AbiWord screenshot document", "GNOME gedit text editor screenshot"],
}
PER_CLASS = 8


def search(term, n=8):
    q = {"action": "query", "format": "json", "generator": "search",
         "gsrsearch": f"{term} filetype:bitmap", "gsrnamespace": 6, "gsrlimit": n,
         "prop": "imageinfo", "iiprop": "url|extmetadata|mime", "iiurlwidth": 1000}
    req = urllib.request.Request(API + "?" + urllib.parse.urlencode(q), headers=UA)
    d = json.load(urllib.request.urlopen(req, context=ctx, timeout=30))
    out = []
    for p in d.get("query", {}).get("pages", {}).values():
        ii = p.get("imageinfo", [{}])[0]
        url = ii.get("thumburl") or ii.get("url"); mime = ii.get("mime", "")
        lic = ii.get("extmetadata", {}).get("LicenseShortName", {}).get("value", "?")
        if url and mime.startswith("image/"):
            out.append((p["title"], url, lic))
    return out


if os.path.isdir(DST):
    shutil.rmtree(DST)
manifest, seen = [], set()
for slug, terms in PLAN.items():
    os.makedirs(f"{DST}/{slug}", exist_ok=True)
    cnt = 0
    for t in terms:
        if cnt >= PER_CLASS:
            break
        for title, url, lic in search(t, PER_CLASS):
            if cnt >= PER_CLASS or title in seen:
                continue
            seen.add(title)
            fn = "".join(c for c in title.replace("File:", "").replace(" ", "_")
                         if c.isalnum() or c in "._-")[:60]
            if not fn.lower().endswith((".png", ".jpg", ".jpeg")):
                fn += ".png"
            try:
                req = urllib.request.Request(url, headers=UA)
                data = urllib.request.urlopen(req, context=ctx, timeout=40).read()
                if len(data) < 3000:
                    continue
                open(f"{DST}/{slug}/{fn}", "wb").write(data)
                manifest.append({"file": fn, "class": slug, "source": "wikimedia_commons",
                                 "title": title, "license": lic, "url": url})
                cnt += 1
                time.sleep(1.0)  # be polite; Commons rate-limits bots
            except Exception as e:
                print("  skip", title, e)
    print(f"{slug:14s} {cnt} imgs")
json.dump(manifest, open(os.path.join(OUT, "field_manifest.json"), "w"), indent=1)
print("total", len(manifest))
