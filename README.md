# ReaderMC

An Android manga/manhwa reader built with **Capacitor**. It supports reading
local CBZ files from a folder you pick, plus browsing, reading online, and
downloading chapters as CBZ from online sources (MangaDex, Asura Scans, and
Manga Read).

## How it's put together

- **`www/`** — the actual app (a web app). `www/index.html` is the UI + most of
  the logic. `www/js/custom-sources.js` holds the community source adapters
  (Asura Scans, Manga Read), the `window.MangaSources` registry, and the
  `load*` dispatcher functions.
- **`android/`** — the native Android wrapper. The important custom code lives
  in `android/app/src/main/java/com/azan/readermc/`:
  - `MainActivity.java` — the custom `NativePlugin` bridge. It does the strong
    "hidden WebView" network fetches (to get past Cloudflare / TLS blocking),
    saves CBZ files via the Storage Access Framework + MediaStore, and handles
    folder picking, thumbnails, and zip extraction.
  - `WebViewActivity.java` — visible WebView used to solve Cloudflare challenges.

> The web app is copied into `android/app/src/main/assets/public/` when you run
> `npx cap sync`. If you edit files in `www/`, run `npx cap sync` (or copy them
> into `assets/public/`) before building so the APK picks up your changes.

## Building the app (Android Studio)

1. Open the `android/` folder in Android Studio.
2. Let Gradle sync finish.
3. Run on a device/emulator, or build an APK via **Build > Build App Bundle(s) /
   APK(s) > Build APK(s)**.

### If you changed anything in `www/`
From the project root:
```
npx cap sync android
```
Then build in Android Studio as above.

## Releasing a new version

Before each release, bump **both** values in `android/app/build.gradle`:

- `versionCode` — an integer that must increase by at least 1 every release.
- `versionName` — the human-readable label (e.g. `"1.1"`, `"1.2"`).

## Adding a new online source

Open `www/js/custom-sources.js` and add a new source object with these methods:
`getPopularManga`, `getLatestUpdates`, `searchManga`, `getMangaDetails`,
`getChapterList`, `getPages` (and `_fetchImageBase64` for images). Then:

1. Register it in `window.MangaSources`.
2. Add a branch for it in each of the four `load*` dispatchers.
3. Add an `<option>` for it to the source `<select>` in `index.html`.

Route network calls through `window.Capacitor.Plugins.NativePlugin.webFetch`
(the strong bridge) — see the Asura Scans / Manga Read adapters as templates.

## Notes

- This app is for personal use. Respect the terms of service and copyright of
  any source you connect to.
