#!/bin/bash
# Downloads the Enrico mobile-UI dataset (MIT, 1460 screenshots, 20 design topics)
# and builds a regression slice mapped to our taxonomy, under .evaldata/enrico_slice/.
# Enrico has NO email/social/document-of-our-kind classes, so this slice is a
# REGRESSION baseline only (does the classifier still get the working classes right),
# not a test of the email/reddit fix. See docs/eval/results.md.
#
# Source: https://github.com/luileito/enrico  (Leiva et al., MobileHCI 2020)
set -e
OUT="${1:-.evaldata}"
mkdir -p "$OUT"
cd "$OUT"

[ -f design_topics.csv ] || curl -fsSL -o design_topics.csv \
  "https://raw.githubusercontent.com/luileito/enrico/master/design_topics.csv"
[ -f screenshots.zip ] || curl -fsSL -o screenshots.zip \
  "https://userinterfaces.aalto.fi/enrico/resources/screenshots.zip"
[ -d enrico_imgs ] || { mkdir -p enrico_imgs && unzip -q -o screenshots.zip -d enrico_imgs; }

python3 - <<'PY'
import csv, os, shutil, collections, json
rows=list(csv.DictReader(open('design_topics.csv')))
# our-slug : (enrico topic, sample cap). Only confidently mappable topics.
# terms = full-screen legal text -> a fair genuine-"document" proxy.
# Caps are high enough to take ALL images of each topic (full regression slice).
plan={'chat':('chat',9999),'map':('maps',9999),'news':('news',9999),
      'video':('mediaplayer',9999),'other':('settings',9999),'document':('terms',9999)}
by=collections.defaultdict(list)
for r in rows: by[r['topic']].append(r['screen_id'])
src='enrico_imgs/screenshots'; dst='enrico_slice'
if os.path.isdir(dst): shutil.rmtree(dst)
manifest=[]
for slug,(topic,cap) in plan.items():
    ids=sorted(by[topic], key=lambda x:int(x))[:cap]
    os.makedirs(f'{dst}/{slug}',exist_ok=True)
    for sid in ids:
        p=f'{src}/{sid}.jpg'
        if os.path.exists(p):
            shutil.copy(p,f'{dst}/{slug}/{sid}.jpg')
            manifest.append({'file':f'{sid}.jpg','class':slug,'source':'enrico','enrico_topic':topic})
    print(f'{slug:10s} <- enrico:{topic}')
json.dump(manifest,open('enrico_manifest.json','w'),indent=1)
print('total',len(manifest))
PY
echo "Done. Slice at $OUT/enrico_slice/  (push with scripts/eval/push_and_run.sh)"
