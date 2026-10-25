# Third-party components

ToneKeep includes and links to the following open-source projects. Component licenses continue to apply. ToneKeep's combined application source is distributed under GPL-3.0-or-later; see `LICENSE`.

| Component | Version | License / source |
| --- | --- | --- |
| youtubedl-android library, common, ffmpeg | 0.18.1 | [GPL-3.0](https://github.com/yausername/youtubedl-android/blob/0.18.1/LICENSE), [corresponding wrapper source](https://github.com/yausername/youtubedl-android/tree/0.18.1) |
| Bundled yt-dlp Python zipapp, including EJS | 2026.10.21 | [upstream licensing information](https://github.com/yt-dlp/yt-dlp/tree/2026.10.21#license), [tagged source](https://github.com/yt-dlp/yt-dlp/tree/2026.10.21) |
| Python, FFmpeg, QuickJS and their bundled libraries | supplied by youtubedl-android 0.18.1 | [runtime distribution and build information](https://github.com/yausername/youtubedl-android/tree/0.18.1), [Python build](https://github.com/yausername/youtubedl-android/blob/0.18.1/BUILD_PYTHON.md), [FFmpeg build](https://github.com/yausername/youtubedl-android/blob/0.18.1/BUILD_FFMPEG.md) |
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
