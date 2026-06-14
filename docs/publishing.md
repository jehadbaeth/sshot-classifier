# Release signing and Play Store publishing

This project ships two kinds of build:

| Artifact | Built by | Signed with | Use |
| --- | --- | --- | --- |
| `app-debug.apk` | `assembleDebug` | debug key | day to day testing |
| `sshot-classifier-<tag>.apk` | `assembleRelease` (CI on a `v*` tag) | upload key | sideloading a release |
| `sshot-classifier-<tag>.aab` | `bundleRelease` (same CI run) | upload key | upload to Google Play |

The release APK and the AAB are minified (R8, `isMinifyEnabled = true`). The debug
build is not, so the release build is the only one that exercises R8. Always smoke
test a release build on a device after changing dependencies or keep rules: R8 can
drop a runtime only class (ML Kit OCR, TFLite) while the build stays green.

## One time: create the upload keystore

```sh
keytool -genkeypair -v -keystore upload.jks -keyalg RSA -keysize 4096 \
        -validity 10000 -alias upload
```

Keep `upload.jks` and its passwords somewhere safe and out of git (the repo
ignores `*.jks`, `*.keystore`, and `keystore.properties`). Losing the upload key
is recoverable through Play support; committing it is not, so never commit it.

Enroll the app in **Play App Signing**: Google holds the real app signing key and
this upload key only signs what you upload. That is the default and recommended path.

## Local release builds

Copy the template and fill it in (the file is gitignored):

```sh
cp keystore.properties.template keystore.properties
# edit keystore.properties: storeFile (absolute path), storePassword, keyAlias, keyPassword
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleRelease bundleRelease
```

If `keystore.properties` is absent the build falls back to debug signing and prints
a warning. That APK still sideloads, but the AAB cannot be uploaded to Play.

The signing config also reads the four values from environment variables
(`RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
`RELEASE_KEY_PASSWORD`), which is how CI supplies them.

## CI release builds

The `Release` workflow triggers on any `v*` tag. To make it produce upload signed
artifacts, add these repository secrets (Settings, Secrets and variables, Actions):

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 upload.jks` (the whole keystore, base64) |
| `RELEASE_STORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | key alias (for example `upload`) |
| `RELEASE_KEY_PASSWORD` | key password |

On macOS use `base64 -i upload.jks | tr -d '\n'` instead of `base64 -w0`.

Without the secrets the workflow still runs and warns; artifacts are debug signed.
The AAB is uploaded as a workflow artifact (it is not attached to the public
release because an AAB is not installable, it is only the Play upload input).

## What is still required to publish, outside this repo

These are Play Console and legal tasks, not code:

- Play Console developer account ($25 once). An organization account needs a
  D-U-N-S number and identity verification, which takes days, so start early.
- **Photo and Video Permissions declaration**: the app holds `READ_MEDIA_IMAGES`
  (broad photo access). Google reviews this by hand and can reject it. A screenshot
  monitor watching a folder is a plausible core use case, but it must be justified.
- **Foreground service declaration**: the app runs a `dataSync` foreground service.
- Privacy policy URL, and the Data Safety form. The app is fully on device and
  collects nothing, so the form is favorable, but it must be declared accurately.
- Store listing: 512x512 icon, 1024x500 feature graphic, phone screenshots (the
  ones in `docs/images/` can be reused), descriptions, IARC content rating.

See the project TODO for the live status of these items.
