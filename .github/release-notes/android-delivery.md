# Android delivery build

This release contains signed-by-GitHub build artifacts for distributing the Android app.

## What's included
- **Android App Bundle (.aab)** for uploading to Google Play Console.
- **Android APK (.apk)** for direct installation on devices.

## How to use
1. **Google Play upload**: Use the `.aab` file from the release assets when creating a new release in the Play Console.
2. **Direct install**: Download the `.apk` asset and sideload it on a device (enable "Install unknown apps" when prompted).

## Notes
- These artifacts are generated from the tagged commit that created this release.
- If you need production signing, update the workflow to inject a keystore and signing configs before building.
