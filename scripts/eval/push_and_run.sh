#!/bin/bash
# Pushes a labeled dataset (one subdir per class, named by the slugs in
# ClassificationEvalTest.slugToTag) into the app's INTERNAL files/eval, fixes
# ownership + SELinux MLS category, runs ClassificationEvalTest via am instrument,
# and pulls the per-image CSV + summary.
#
# Internal storage (not the external files dir) is used because adb-pushed files in
# the external dir are not enumerable by the app under FUSE on API 33. The CLIP int8
# models must already be installed in /data/data/PKG/files/models (push them as in
# SemanticSearchInstrumentedTest, or let the app download them once).
#
# Usage: scripts/eval/push_and_run.sh <local_dataset_dir> <out_label> [out_dir]
set -e
: "${ANDROID_SDK_ROOT:=$HOME/Library/Android/sdk}"
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
PKG=com.okapiorbits.sshotclassifier
SRC="$1"; LABEL="$2"; OUTDIR="${3:-eval-out}"
INT=/data/data/$PKG/files/eval
mkdir -p "$OUTDIR"

$ADB root >/dev/null 2>&1; sleep 2; $ADB wait-for-device
UIDG=$($ADB shell stat -c '%u:%g' /data/data/$PKG/files/models | tr -d '\r')
CTX=$($ADB shell stat -c '%C' /data/data/$PKG/files/models | tr -d '\r')   # carries MLS categories
$ADB shell rm -rf $INT /data/local/tmp/evalpush
$ADB shell mkdir -p $INT /data/local/tmp/evalpush
for d in "$SRC"/*/; do $ADB push "$d" /data/local/tmp/evalpush/ >/dev/null 2>&1; done
$ADB shell "cp -r /data/local/tmp/evalpush/* $INT/"
$ADB shell chown -R "$UIDG" $INT
$ADB shell chcon -R "$CTX" $INT
$ADB unroot >/dev/null 2>&1; sleep 2; $ADB wait-for-device

$ADB logcat -c
$ADB shell am instrument -w -e class com.okapiorbits.sshotclassifier.pipeline.ClassificationEvalTest \
  $PKG.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tail -4
$ADB pull /sdcard/Android/data/$PKG/files/eval-summary.txt "$OUTDIR/summary-$LABEL.txt" >/dev/null 2>&1
$ADB pull /sdcard/Android/data/$PKG/files/eval-results.csv "$OUTDIR/results-$LABEL.csv" >/dev/null 2>&1
echo "===== SUMMARY ($LABEL) ====="
cat "$OUTDIR/summary-$LABEL.txt"
