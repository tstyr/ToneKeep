# Third-party components

ToneKeep includes and links to the following open-source projects. Component licenses continue to apply. ToneKeep's combined application source is distributed under GPL-3.0-or-later; see `LICENSE`.

| Component | Version | License / source |
| --- | --- | --- |
| youtubedl-android library, common, ffmpeg | 0.18.1 | [GPL-3.0](https://github.com/yausername/youtubedl-android/blob/0.18.1/LICENSE), [corresponding wrapper source](https://github.com/yausername/youtubedl-android/tree/0.18.1) |
| Bundled yt-dlp Python zipapp, including EJS | 2026.10.21 | [upstream licensing information](https://github.com/yt-dlp/yt-dlp/tree/2026.10.21#license), [tagged source](https://github.com/yt-dlp/yt-dlp/tree/2026.10.21) |
| Python, FFmpeg, QuickJS and their bundled libraries | supplied by youtubedl-android 0.18.1 | [runtime distribution and build information](https://github.com/yausername/youtubedl-android/tree/0.18.1), [Python build](https://github.com/yausername/youtubedl-android/blob/0.18.1/BUILD_PYTHON.md), [FFmpeg build](https://github.com/yausername/youtubedl-android/blob/0.18.1/BUILD_FFMPEG.md) |
| Rubber Band Library (used through FFmpeg) | supplied by the FFmpeg runtime above | [GPL licensing and upstream source](https://github.com/breakfastquay/rubberband), [project](https://breakfastquay.com/rubberband/) |
| ONNX Runtime Android | 1.20.0 | MIT, https://github.com/microsoft/onnxruntime/tree/v1.20.0 |
| JTransforms / JLargeArrays | 3.1 / 1.5 | BSD-2-Clause, https://github.com/wendykierp/JTransforms, https://gitlab.com/ICM-VisLab/JLargeArrays |
| Apache Commons Math | 3.5 (JTransforms dependency) | Apache-2.0, https://commons.apache.org/proper/commons-math/ |
| UVR-MDX-NET-Voc_FT model (downloaded on first use) | SHA-256 pinned below | MIT per [UVR's model usage notice](https://github.com/Anjok07/ultimatevocalremovergui#license); credit to Ultimate Vocal Remover developers **Anjok07 and aufr33**, and **Kuielab / Woosung Choi** for the original MDX-Net work. [Model distribution](https://github.com/TRvlvr/model_repo/releases/tag/all_public_uvr_models) |
| Kotlin stdlib | 1.7.22 | Apache-2.0, https://github.com/JetBrains/kotlin |
| AndroidX | versions resolved in Gradle | Apache-2.0, https://android.googlesource.com/platform/frameworks/support/ |
| Jackson | 2.11.1 | Apache-2.0, https://github.com/FasterXML/jackson |
| Apache Commons IO / Compress | 2.5 / 1.12 | Apache-2.0, https://commons.apache.org/ |

The Python zipapp contains Python sources. It was downloaded from the official release asset `https://github.com/yt-dlp/yt-dlp/releases/download/2026.10.21/yt-dlp` and verified against the official `SHA2-256SUMS` file:

```
b7af77f686e95f7845ce8d09d21957718febb8b48b30555adb9dd2292ad7bf14  yt-dlp
```

The in-app update action downloads future stable yt-dlp releases using the upstream Android wrapper updater. Site support is dependent on both the engine and the bundled native runtime. Updating yt-dlp does not replace the packaged native executables; those require a new APK build.

Vocal extraction uses the UVR MDX model distributed by [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/releases/tag/source-separation-models). The Java centered periodic-Hann STFT, complex tensor layout, and overlap context follow the [sherpa-onnx UVR reference implementation](https://github.com/k2-fsa/sherpa-onnx/blob/master/scripts/uvr_mdx/test.py) (Apache-2.0, Xiaomi Corporation). Model weights are downloaded separately over HTTPS after the user starts extraction; they are not embedded in the APK. Both the app and host test verify this exact file:

```
e411182ce2c53541fefdcc99a8f46f2fe03978eb22038b9497c1d9d95a00fad4  UVR-MDX-NET-Voc_FT.onnx
```

Download URL: `https://github.com/k2-fsa/sherpa-onnx/releases/download/source-separation-models/UVR-MDX-NET-Voc_FT.onnx`.

Runtime components are substantial, which increases the universal APK size. The APK includes arm64-v8a, armeabi-v7a and x86_64 support. `useLegacyPackaging` enables extraction of the executable native files; the two `*.zip.so` files are runtime archives and must not be stripped.
