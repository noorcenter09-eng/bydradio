# Kurd Radio for BYD (Android)

1. Create a **public** GitHub repo, upload all these files (keep the `.github` folder).
2. Repo -> Settings -> Secrets and variables -> Actions -> New secret:
   - `KEYSTORE_PASSWORD` and `KEYSTORE_BASE64` (values are in GITHUB-SECRETS.txt)
3. Push to `main` -> Actions builds the APK -> Releases page has `BYDRadio.apk`.
4. Every new push = a new release = a new update; the app checks Releases from Settings.

## Files
- All Kotlin code is in ONE file: `app/src/main/java/com/kurdradio/byd/KurdRadio.kt`
- Texts (3 languages): `app/src/main/res/values*/strings.xml`
- Android needs `AndroidManifest.xml` and the Gradle files separately - they cannot be merged.
