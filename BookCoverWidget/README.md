# Book Cover Widget

Shows the cover of the most recently modified `.epub` file in a folder you
choose — designed to track whatever you last read in ReadEra, since ReadEra
doesn't expose an official "currently reading" API.

## How it works
1. You pick your ReadEra books folder once via Android's folder picker (Storage
   Access Framework — no root needed).
2. A background job (WorkManager) scans that folder every 15 minutes (Android's
   minimum interval for periodic widget jobs) and finds the `.epub` with the
   most recent "last modified" timestamp.
3. It opens that epub as a zip, reads `META-INF/container.xml` to find the
   `.opf` package file, parses the manifest for the cover image entry, and
   extracts it as a PNG.
4. The home-screen widget displays that PNG. Tapping the widget forces an
   immediate refresh.

## Setup
1. Open this folder in **Android Studio** (File → Open → select `BookCoverWidget`).
2. Let Gradle sync (it will download dependencies — needs internet).
3. Run the app on your device/emulator (min SDK 26 / Android 8.0+).
4. In the app, tap **"Select ReadEra Books Folder"** and choose the folder
   where your epub files actually live (e.g. wherever you store/download
   books that ReadEra scans — often `Internal Storage/Books` or similar,
   depending on your setup).
5. Long-press your home screen → Widgets → find **Book Cover Widget** → drag
   it onto your home screen.
6. Open a book in ReadEra, read a bit (this updates the file's modified time
   in most cases), back out, and either wait ~15 min or tap the widget to
   force a refresh.

## Important caveats (read before relying on this)

- **"Last modified" is a heuristic, not a guarantee.** ReadEra stores your
  actual reading progress in its own private database (not directly
  accessible without root), not by rewriting the epub file itself. On some
  Android versions/ReadEra versions, opening a book *does* touch the file's
  metadata (e.g. via a companion `.json`/cache write beside it); on others it
  might not. Test with your setup — if it doesn't reliably pick the right
  book, the more robust route is locating and querying ReadEra's SQLite
  database directly (requires either root or ReadEra exposing it through
  `Android/data/org.readera/...`, which itself may be blocked by scoped
  storage on Android 11+ unless you disable it or use `adb`).
- **Android's minimum periodic widget refresh is 15 minutes** — a hard OS
  limit, not something this app can shorten. The manual tap-to-refresh
  works instantly.
- **Some epubs have no explicit cover metadata.** The extractor falls back to
  the first image it finds in the manifest, but a small number of poorly
  formed epubs may have no usable cover at all.
- You may need to adjust `minSdk`/`targetSdk` depending on your device.

## Files of interest
- `CoverExtractor.kt` — all the epub/zip/XML parsing logic (self-contained,
  no external epub library needed).
- `UpdateWidgetWorker.kt` — background job that finds the latest book and
  extracts its cover.
- `BookCoverWidgetProvider.kt` — the actual home-screen widget.
- `MainActivity.kt` — one-time folder picker + manual refresh button.
