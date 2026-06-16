#!/usr/bin/env python3
"""Build a clean-ish set of REAL bank-app screenshots to test whether the classic
finance->receipt failure (a balance/transactions dashboard mislabeled "receipt")
actually exists. Motivated by docs/eval/results.md: the F-Droid "finance" folder is
FOSS expense/crypto/budget apps, so it can't exercise that failure on real banks.

Source: Google Play store listing pages for major banking apps (neobanks +
traditional). The phone screenshots are served from play-lh.googleusercontent.com;
they're extracted from the listing HTML. These are copyrighted bank screenshots used
only for internal eval, never redistributed — images stay gitignored, only the
manifest (app + URL + sha256) is committed for reproducibility.

CAVEAT (and why the test is still valid): Play listings also contain marketing /
feature graphics and onboarding screens, which this script cannot perfectly filter
out (it keeps URLs unique to one app to drop shared icons/related-app chrome, and the
caller filters to portrait phone aspect ratios). So this is NOT a clean per-image
"dashboard" set. It does not need to be: the finance->receipt question is answered by
inspecting only the subset that the classifier PREDICTS receipt (a handful), then
checking by eye whether those are real dashboards or genuinely receipt-like screens.
Marketing noise in the rest only depresses the finance-recall number, it can't create
a false "dashboard mislabeled receipt".

Usage: python3 scripts/eval/fetch_bank_dashboards.py <out_dir> [--per-app N]
Writes <out_dir>/banks_slice/finance/<app>_<i>.png + <out_dir>/bank_manifest.json
"""
import argparse
import collections
import hashlib
import json
import os
import re
import urllib.request

UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    "(KHTML, like Gecko) Chrome/120 Safari/537.36"}

# app label -> Play package id. Mix of neobanks and traditional banks so the set
# spans balance dashboards, transaction lists, and card views.
APPS = {
    "revolut": "com.revolut.revolut", "monzo": "co.uk.getmondo",
    "starling": "com.starlingbank.android", "cashapp": "com.squareup.cash",
    "chime": "com.onedebit.chime", "n26": "de.number26.android",
    "wise": "com.transferwise.android", "chase": "com.chase.sig.android",
    "bofa": "com.infonow.bofa", "wellsfargo": "com.wf.wellsfargomobile",
    "capitalone": "com.konylabs.capitalone", "usaa": "com.usaa.mobile.android.usaa",
    "hsbc": "com.htsu.hsbcpersonalbanking",
    "barclays": "com.barclays.android.barclaysmobilebanking",
    "venmo": "com.venmo", "varo": "com.varomoney.bank",
    "current": "com.current.app", "sofi": "com.sofi.mobile",
}


def get(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30).read()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--per-app", type=int, default=12)
    a = ap.parse_args()
    dst = os.path.join(a.out, "banks_slice", "finance")
    os.makedirs(dst, exist_ok=True)

    per_app = {}
    for name, pkg in APPS.items():
        try:
            html = get(f"https://play.google.com/store/apps/details?id={pkg}&hl=en_US&gl=US").decode("utf-8", "ignore")
            urls = re.findall(r"https://play-lh\.googleusercontent\.com/[A-Za-z0-9_-]{30,}", html)
            per_app[name] = list(dict.fromkeys(urls))
            print(f"{name:12s} {len(per_app[name])} urls")
        except Exception as e:
            print(f"{name:12s} FAIL {e}")

    # URLs shared across >=2 apps are chrome / related-app icons, not screenshots.
    freq = collections.Counter(u for us in per_app.values() for u in us)
    manifest = []
    for name, us in per_app.items():
        for i, u in enumerate([x for x in us if freq[x] == 1][:a.per_app]):
            try:
                data = get(u + "=w1080")
            except Exception:
                continue
            if len(data) < 10000:
                continue
            fn = f"{name}_{i:02d}.png"
            with open(os.path.join(dst, fn), "wb") as f:
                f.write(data)
            manifest.append({"file": fn, "app": name, "url": u,
                             "sha256": hashlib.sha256(data).hexdigest()})
    json.dump(manifest, open(os.path.join(a.out, "bank_manifest.json"), "w"), indent=1)
    print("downloaded", len(manifest))
    print("NOTE: filter to portrait phone aspect (h/w 1.7-2.4) before pushing; "
          "marketing/feature graphics are expected noise — see module docstring.")


if __name__ == "__main__":
    main()
