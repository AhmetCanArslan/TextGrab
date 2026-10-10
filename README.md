# TextGrab

Select, copy and translate the text of anything on your Android screen, like
iOS Live Text or Circle to Search. Recognition runs on your device and needs
no Google Play services.

| Capture any screen | Select like native text | Translate in place |
|---|---|---|
| ![Captured screen shown as an inset preview](screenshots/capture.png) | ![Two lines selected with handles and a copy toolbar](screenshots/select.png) | ![The same screen translated from German to English](screenshots/translate.png) |

## Install

Download the latest APK from
[Releases](https://github.com/AhmetCanArslan/TextGrab/releases). Requires
Android 8.0; screen capture needs Android 11.

## Setup

Only screen capture needs setup; the home screen lists what is still missing.

1. **Turn on screen capture:** enable *TextGrab screen capture* under
   accessibility settings. The service only takes screenshots.
2. **Add the tile:** drag **OCR screenshot** into your quick settings.
3. **Set as assistant app** (optional): pick TextGrab as the digital assistant
   app to capture with a long-press on home. Turn off Circle to Search first.

## Use

- **Screen:** tap the **OCR screenshot** tile or long-press home.
- **Image:** share an image to **TextGrab**, or tap **Choose image**.
- **Camera:** tap **Camera** on the home screen, or long-press the app icon
  and pick **Camera**. Pinch to zoom, tap to focus. Photos are not kept.

Then tap a word, or long-press and drag, to copy, share or search the
selection. The translate button replaces every line in place; tap again for
the original, long-press to pick the target language.

## Translation engines

Chosen under **Translation engine** on the home screen. Cloud engines need
your own API key.

| Engine | Notes |
|---|---|
| On-device (ML Kit) | The default. Free, private, offline; about 30 MB per language pack. |
| DeepL | Natural wording, strong on European languages. |
| Google Cloud Translation | The widest language coverage. |
| LLM (OpenAI-compatible) | Any `/chat/completions` endpoint: OpenAI, Gemini, DeepSeek, OpenRouter, Groq, Mistral, Together, Ollama, LM Studio. |
| Claude (Anthropic) | Good with interface wording and short labels. |

## Privacy

- Text recognition always runs on the device.
- The on-device engine uses the internet only to download language packs.
- A cloud engine receives the text of the screen you translate, and only when
  you tap translate.
- Captures and photos are never saved. The only runtime permission is the
  camera, requested when you first open it.

## Languages

**Recognition:** Latin-script languages, Chinese, Japanese and Korean. Arabic,
Cyrillic and Devanagari are not recognized yet.

**Translation:** about 60 languages on device; cloud engines cover whatever
the service supports.

## adb

```sh
# Capture the screen
adb shell am broadcast --receiver-foreground -a com.arslan.textgrab.action.CAPTURE -p com.arslan.textgrab
# Open the camera
adb shell am start -n com.arslan.textgrab/.CameraActivity
```

## Credits and license

A fork of [notune/TextGrab](https://github.com/notune/TextGrab) that adds
instant screen capture, the assistant gesture, the camera, in-place
translation, CJK recognition and a redesigned interface. Its own package
name, `com.arslan.textgrab`, lets it install next to the original.

The source code is under the [MIT License](LICENSE). The bundled ML Kit SDKs
and models are proprietary Google software, used under the
[ML Kit Terms of Service](https://developers.google.com/ml-kit/terms).
