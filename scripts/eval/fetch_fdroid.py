#!/usr/bin/env python3
"""Build a large, weakly-labeled mobile-screenshot eval set from the F-Droid catalog.

F-Droid apps are FOSS (openly licensed) and ship real phone screenshots organized by
app function. We use the app's primary category as a WEAK label for every screenshot in
that app (category -> our taxonomy slug). This is weak supervision, not per-image ground
truth: an email app's folder can contain settings/onboarding shots too. So per-class
recall here is a noisy floor, and the trustworthy signal is the PAIRED fused-vs-CLIP-only
delta on identical images (label noise cancels in the paired comparison).

Why F-Droid and not Reddit/GitHub scraping (which the user suggested): Reddit/GitHub app
screenshots are copyrighted and not redistributable, need API/OAuth, and aren't
reproducible. F-Droid is licensed, has a stable index with sha256 per asset, and the
manifest is committable so anyone can rebuild the exact set.

Design choices (see docs/eval/results.md):
  * English locale only (en-US, else any en-*). OCR heuristics key on English tokens;
    a German screenshot makes fused collapse to CLIP-only and dilutes the delta.
  * Dedup by sha256 (the same asset repeats across locales).
  * Cap per app (first N, F-Droid orders the hero/main-function shot first = cleanest
    label) and cap per class, so game/document don't drown out email/social.

Usage: python3 scripts/eval/fetch_fdroid.py <out_dir> [index-v2.json] [--per-app N] [--per-class M]
Output: <out_dir>/fdroid_slice/<slug>/<pkg>__<file>  and  <out_dir>/fdroid_manifest.json
"""
import json, os, sys, hashlib, urllib.request, urllib.parse, collections, argparse

REPO = "https://f-droid.org/repo"

# F-Droid fine category -> our taxonomy slug (slugToTag in ClassificationEvalTest).
# Only categories whose primary function maps cleanly. Noise level noted in the report.
CATMAP = {
    'Email': 'email',
    'Browser': 'browser',
    'News': 'news',
    'Forum': 'social_media', 'Social Network': 'social_media',
    'Messaging': 'chat', 'Voice & Video Chat': 'chat', 'AI Chat': 'chat',
    'Navigation': 'map', 'Public Transport': 'map',
    'Online Media Player': 'video', 'Local Media Player': 'video',
    'Podcast': 'video', 'Radio': 'video',
    'Calendar & Agenda': 'calendar', 'Schedule': 'calendar',
    'Finance Manager': 'finance', 'Wallet': 'finance', 'Market & Price': 'finance',
    'Development': 'code', 'Text Editor': 'code',
    'Ebook Reader': 'document', 'Note': 'document', 'Writing': 'document',
    'Shopping List': 'shopping',
}
GAME_CATS = {'Puzzle Game', 'Board Game', 'Action Game', 'Card Game', 'Role-Playing Game',
             'Strategy Game', 'Shooter Game', 'Casual Game', 'Party Game',
             'Platformer Game', 'Sport Game', 'Word Game', 'Dice', 'Educational Game',
             'Visual Novel'}


def slug_for(cats):
    for c in cats:
        if c in CATMAP:
            return CATMAP[c]
        if c in GAME_CATS:
            return 'game'
    return None


def pick_en(phone):
    """Return the screenshot list for the best English locale, or None."""
    if 'en-US' in phone:
        return phone['en-US']
    for loc in sorted(phone):
        if loc.lower().startswith('en'):
            return phone[loc]
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('out')
    ap.add_argument('index', nargs='?', default='index-v2.json')
    ap.add_argument('--per-app', type=int, default=4)
    ap.add_argument('--per-class', type=int, default=400)
    a = ap.parse_args()

    idx = json.load(open(a.index))
    pkgs = idx['packages']
    dst = os.path.join(a.out, 'fdroid_slice')
    os.makedirs(dst, exist_ok=True)

    # Gather candidates: (slug, pkg, license, name, sha256) preserving F-Droid order.
    by_slug = collections.defaultdict(list)
    for pk, pv in pkgs.items():
        md = pv.get('metadata', {})
        slug = slug_for(md.get('categories', []))
        if not slug:
            continue
        phone = md.get('screenshots', {}).get('phone', {})
        shots = pick_en(phone)
        if not shots:
            continue
        lic = (md.get('license') or 'unknown')
        for s in shots[:a.per_app]:
            by_slug[slug].append((pk, lic, s['name'], s.get('sha256', '')))

    seen = set()
    manifest = []
    counts = collections.Counter()
    for slug in sorted(by_slug):
        os.makedirs(os.path.join(dst, slug), exist_ok=True)
        for pk, lic, name, sha in by_slug[slug]:
            if counts[slug] >= a.per_class:
                break
            if sha and sha in seen:
                continue
            url = REPO + urllib.parse.quote(name)
            fn = pk + '__' + os.path.basename(name).replace(' ', '_')
            out = os.path.join(dst, slug, fn)
            try:
                req = urllib.request.Request(url, headers={'User-Agent': 'sshot-eval/1.0'})
                data = urllib.request.urlopen(req, timeout=30).read()
            except Exception as e:
                print('  skip', url, e)
                continue
            got = hashlib.sha256(data).hexdigest()
            if sha and got != sha:
                print('  sha mismatch', url)
                continue
            if got in seen:
                continue
            seen.add(got)
            with open(out, 'wb') as f:
                f.write(data)
            counts[slug] += 1
            manifest.append({'file': fn, 'class': slug, 'package': pk,
                             'license': lic, 'sha256': got, 'url': url})
        print(f'{slug:14s} {counts[slug]:4d}')

    json.dump(manifest, open(os.path.join(a.out, 'fdroid_manifest.json'), 'w'), indent=1)
    print('total', len(manifest))


if __name__ == '__main__':
    main()
