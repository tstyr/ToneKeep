# Third-party components

ToneKeep includes and links to the following open-source projects. Component licenses continue to apply. ToneKeep's combined application source is distributed under GPL-3.0-or-later; see `LICENSE`.

| Component | Version | License / source |
| --- | --- | --- |
| youtubedl-android library, common, ffmpeg | 0.18.1 | [GPL-3.0](https://github.com/yausername/youtubedl-android/blob/0.18.1/LICENSE), [corresponding wrapper source](https://github.com/yausername/youtubedl-android/tree/0.18.1) |
| Bundled yt-dlp Python zipapp, including EJS | 2026.10.21 | [upstream licensing information](https://github.com/yt-dlp/yt-dlp/tree/2026.10.21#license), [tagged source](https://github.com/yt-dlp/yt-dlp/tree/2026.10.21) |
| Python, FFmpeg, QuickJS and their bundled libraries | supplied by youtubedl-android 0.18.1 | [runtime distribution and build information](https://github.com/yausername/youtubedl-android/tree/0.18.1), [Python build](https://github.com/yausername/youtubedl-android/blob/0.18.1/BUILD_PYTHON.md), [FFmpeg build](https://github.com/yausername/youtubedl-android/blob/0.18.1/BUILD_FFMPEG.md) |
| Rubber Band Library (used through FFmpeg) | supplied by the FFmpeg runtime above | [GPL licensing and upstream source](https://github.com/breakfastquay/rubberband), [project](https://breakfastquay.com/rubberband/) |
| Kotlin stdlib | 1.7.22 | Apache-2.0, https://github.com/JetBrains/kotlin |
| AndroidX | versions resolved in Gradle | Apache-2.0, https://android.googlesource.com/platform/frameworks/support/ |
| Jackson | 2.11.1 | Apache-2.0, https://github.com/FasterXML/jackson |
| Apache Commons IO / Compress | 2.5 / 1.12 | Apache-2.0, https://commons.apache.org/ |

The Python zipapp contains Python sources. It was downloaded from the official release asset `https://github.com/yt-dlp/yt-dlp/releases/download/2026.10.21/yt-dlp` and verified against the official `SHA2-256SUMS` file:

```
b7af77f686e95f7845ce8d09d21957718febb8b48b30555adb9dd2292ad7bf14  yt-dlp
```

The in-app update action downloads future stable yt-dlp releases using the upstream Android wrapper updater. Site support is dependent on both the engine and the bundled native runtime. Updating yt-dlp does not replace the packaged native executables; those require a new APK build.

Runtime components are substantial, which increases the universal APK size. The APK includes arm64-v8a, armeabi-v7a and x86_64 support. `useLegacyPackaging` enables extraction of the executable native files; the two `*.zip.so` files are runtime archives and must not be stripped.


## Offline lyrics components

Optional online data sources: LRCLIB (https://lrclib.net/docs), MusicBrainz (https://musicbrainz.org/doc/MusicBrainz_API) and Cover Art Archive (https://musicbrainz.org/doc/Cover_Art_Archive/API). MusicBrainz core metadata is CC0 (https://musicbrainz.org/doc/About/Data_License). The API clients are ToneKeep code; no additional external SDK is bundled. Downloaded lyrics and artwork keep their respective rights and are not bundled in the application APK. Data source labels and references are retained with cached metadata and lyrics.

- whisper.cpp / ggml v1.8.2, commit 4979e04f5dcaccb36057e059bbaed8a2f5288315: MIT; corresponding source is the pinned submodule at app/src/main/cpp/whisper.cpp. No network support is compiled into this engine.
- Whisper multilingual model weights (small / medium), MIT, OpenAI. Downloaded separately from https://huggingface.co/ggerganov/whisper.cpp at revision 5359861c739e955e79d9a303bcbc70fb988958b1; they are not included in the application APK. SHA-256 and size are pinned in ModelManager.java. Upstream model license: https://github.com/openai/whisper/blob/main/LICENSE .
- Japanese word segmentation uses Android platform ICU (no extra dictionary or unverified alignment library bundled).

### whisper.cpp / ggml license

MIT License

Copyright (c) 2023-2024 The ggml authors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

### Whisper model license

MIT License

Copyright (c) 2022 OpenAI

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
