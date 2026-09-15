# TextGrab
Select and copy text from any image on Android while respecting your privacy. Works like iOS Live Text or the text selection on Pixel phones, but fully local and without Google Play services.

## What's different in this fork

This is a fork of [notune/TextGrab](https://github.com/notune/TextGrab)
with its own package name, `com.arslan.textgrab`, so it installs next to
the original rather than updating it. 

Changes:

- **Assistant gesture:** long-press home / the navigation handle once
  TextGrab is the default digital assistant. The home screen has a button
  that opens that setting.
- **adb trigger:** start a capture from a computer. The home screen shows the
  command and copies it on tap. The receiver requires the `DUMP` permission,
  so only adb (not other apps) can send these:

  ```sh
  # Capture the current screen (instant capture), or open the latest screenshot if it's off
  adb shell am broadcast -a com.arslan.textgrab.action.CAPTURE -p com.arslan.textgrab
  # Always open the latest screenshot
  adb shell am broadcast -a com.arslan.textgrab.action.LATEST_SCREENSHOT -p com.arslan.textgrab
  ```
- **Capture preview:** instant captures open slightly shrunk, below the
  toolbar, with rounded corners over a blurred copy of the screen, so text
  at the edges is easy to reach.
- **More native text selection:** dragging follows lines instead of jumping
  between words, handles keep their grab offset, and copied text no longer
  contains stray empty lines.
- **Better recognition:** icons and stray symbols are filtered out, lines
  are ordered as they appear on screen, and Chinese, Japanese and Korean
  models are bundled.

The download and Obtainium badges and the signing certificate below still
refer to the upstream project.

## How to use

**Main workflow, from anything on your screen to copied text in seconds:**

1. Swipe down and tap the **OCR screenshot** tile
2. Tap or drag over the text, then copy

One-time setup: open quick settings, tap the edit (pencil) button and drag
the **OCR screenshot** tile into your tiles. Then enable instant capture via
the button on the app's home screen, so the tile can take the screenshot by
itself (Android 12+, uses a screenshot-only accessibility service that reads
no screen content). Without instant capture, take a screenshot first and the
tile opens it.

**Second workflow, for existing images:**

Share any image from any app (gallery, browser, messenger) to **TextGrab**
and the text is selectable. Opening image files with TextGrab from a file
manager works too, as does picking an image from the app's home screen.

**Long-press home / navigation handle:**

Set TextGrab under *Settings → Apps → Default apps → Digital assistant app*.
Long-pressing home (or the gesture handle) then captures the screen, just
like the tile. Needs instant capture enabled; without it, the latest
screenshot is opened. On devices with Circle to Search, turn that off first.

## Screenshots

| Tap a word | Long-press and drag | Full text view |
|---|---|---|
| ![Select a word](screenshots/select_word.png) | ![Sweep selection](screenshots/sweep_selection.png) | ![Text view](screenshots/text_view.png) |

## How it works

The app bundles Google's ML Kit **on-device** text recognizer, the same class
of neural model that powers Pixel Live Text. The model is packaged inside the
APK, so recognition:

- runs entirely on the device. The app has **no internet permission**.
- needs **no Google Play services** and works on GrapheneOS out of the box.
- is fast: typically well under a second per screenshot on real hardware.

## Supported languages

All Latin-script languages, including:

English, German, French, Spanish, Italian, Portuguese, Dutch, Polish, Czech,
Danish, Swedish, Norwegian, Finnish, Hungarian, Romanian, Turkish, Croatian,
Slovak, Slovenian, Estonian, Latvian, Lithuanian, Albanian, Catalan, Basque,
Galician, Icelandic, Irish, Maltese, Swahili, Tagalog, Vietnamese (partial,
some diacritics may be missed) and more, plus digits and common punctuation.

Chinese, Japanese and Korean (each also reads Latin text) are bundled too.
They only run when the Latin recognizer's result looks unreliable, so Latin
text stays fast. Arabic and Devanagari are not supported yet.

## Building

```sh
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`.

To build a signed release, create a keystore and put its credentials in
`keystore/keystore.properties` (this directory is gitignored):

```properties
storeFile=keystore/your-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without that file the release build is unsigned. Keep your keystore safe:
updates must be signed with the same key.

## Installing with Obtainium

Tap the Obtainium badge above on your phone (or add
`https://github.com/notune/TextGrab` as an app source in
[Obtainium](https://github.com/ImranR98/Obtainium)) to install TextGrab and
get updates straight from this repo's releases.

## Verifying the APK

Release APKs are signed with this certificate (package + SHA-256, ready to
paste into [AppVerifier](https://github.com/soupslurpr/AppVerifier)):

```
com.arslan.textgrab
AB:C4:BB:AE:C5:6F:D3:DB:AB:AC:C8:62:D0:B4:5D:29:3B:53:CC:40:BF:67:D7:25:3B:3E:1B:7D:2D:48:0C:31
```

On a computer you can check a downloaded APK with:

```sh
apksigner verify --print-certs TextGrab.apk
```

After the first install, Android itself rejects any update that is not
signed with the same key.

## Notes

- `minSdk 26` (Android 8.0), `targetSdk 36`.
- The APK is ~43 MB; ~30 MB of that is the bundled recognition model.
- Photo permission is only requested for the "latest screenshot" feature.
  Images opened via share or the picker need no permission at all.

## License

The app source code is licensed under the [MIT License](LICENSE).

The bundled ML Kit text recognition SDK and its model are proprietary Google
software, used and redistributed under the
[ML Kit Terms of Service](https://developers.google.com/ml-kit/terms). They
are pulled from Google's Maven repository at build time and are not part of
this repository.
