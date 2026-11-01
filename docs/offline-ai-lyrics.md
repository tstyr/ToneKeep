# オフラインAI歌詞：実装と検証

## Phase 1：調査

2026-10-31にmain `9f973e4511efbc4eede6601e7749b15b088e2038` を確認。
空のワークスペースへcloneし、`feature/offline-ai-lyrics` を作成した。既存の未コミット変更はなかった。

| 対象 | 既存構造と今回の対応 |
| --- | --- |
| アプリ | Java 17、Android API 29以降、compile/target 35、Activityと標準ViewによるUI。既存構造を維持 |
| 保存済み音源 | `DownloadStore.Entry`のUUID `id`、完成音源の`content_uri`、MediaStore.Downloads。タイトルを識別子に使用しない |
| 履歴・SQLite | `downloads.db` v2の`jobs`が保存とキー変更を管理。v3は歌詞用2テーブルを追加するだけで既存行を変更しない |
| キー変更 | 元URIが`jobs.url`、完成音源URIが`jobs.content_uri`。完成ファイルを個別に解析し、元の歌詞や時刻を流用しない |
| 音声処理 | `PitchEngine.execute`が同梱FFmpeg/ffprobeとPythonの共有ライブラリを初期化。これを再利用 |
| Python | youtubedl-androidが同梱・展開する実行環境。AIのためのPythonパッケージは追加しない |
| 再生 | `AudioPlayer`とMediaPlayer。直接再生できない形式は`PreviewAudio`で一時WAVへ展開。歌詞も同じプレーヤーの実測位置を参照 |
| 背景処理 | `DownloadService`の保存・キー変更キューはそのまま。AIには独立サービスと直列キューを追加 |
| CI | `.github/workflows/android.yml`のunit/lint/APK、端末検証、mainのみのプレビュー公開条件を維持 |

## Phase 2〜5：実装

- whisper.cpp v1.8.2 / commit `4979e04f5dcaccb36057e059bbaed8a2f5288315`をsubmoduleで固定。
  JNIとNDK 28.2、CMake 3.22.1で3 ABIをビルド。C++ランタイムとupstreamライブラリは静的リンクし、既存FFmpegとの名前衝突を避ける。
  プレビューAPKでもWhisper/ggmlを`-O3`でビルドする。最初の実機試行ではdebugの無最適化が遅かったため中断し、最適化後に再検証した。
  新しいJNIライブラリは16KBページ向けリンク設定を使用する。既存のFFmpeg/Python全体のAndroid 17互換性は別途端末確認が必要。
- small多言語モデルを標準、mediumを選択可能にした。モデルはAPKに含めない。
  Hugging Faceの固定revisionとSHA-256、サイズをコードに固定。
  HTTPS取得、HTTP Range再開、残容量確認、検証後のatomic rename、削除に対応。
  モデルはバックアップ対象外のアプリ専用ディレクトリに置く。認識前にもSHA-256を検証する。
- `LyricsEngine`は完成音源URIを読み取り、一時コピーのハッシュを保存。
  同梱FFmpegでPCM16 / mono / 16kHzのWAVを作る。元ファイルへの書き込みは行わない。
  WAVヘッダーを解析して30秒ずつ読み込み、全曲のPCMをメモリに展開しない。
  分割位置をサンプル数からミリ秒へ変換して全曲時刻へ加算する。
  `asetpts=PTS-STARTPTS`でデコード後の先頭を基準にする。
- 日本語固定（既定）と自動言語判定、翻訳なしに対応。
  推論コンテキストを分割間で引き継がず、非音声トークンを抑制する。
  ほぼデジタル無音の窓、無音確率が高い行、信頼度が低い行を除外。
  時刻が異なる繰り返し歌詞は除外しない。トークン確率・行平均・無音確率を保持する。
- 日本語の分かち書きはAndroid ICU、アライメントは`LyricsAlignment.Aligner`で別処理。
  UTF-8トークン境界と単語境界が一致し、時刻・確率の検査を通った場合のみ単語時刻を採用。
  不一致・不正時刻・低信頼度では行同期へフォールバックする。文字数による時刻の捏造は行わない。
  同期方式を`whisper-token-estimate`または`line-fallback`として記録する。
- `LyricsService`は別プロセス、単一ワーカー、永続ジョブ、通知付きで実行。
  同時AI推論は1件のみ。処理中のCPUスリープを防ぐWakeLock、メモリ事前確認、危険な高温での中断を追加。
  キャンセルはFFmpegとJNIに伝搬。JNIのキャンセル状態はモデルセッションごとに保持する。
  終了・失敗・中止時に音声作業ディレクトリを削除。
  プロセス終了後は次回起動時に中断を検知し、明示的な再試行を可能にする。
  途中までの推論再開は行わず先頭から再解析する。モデルの中断取得は再開できる。
  認識進捗は完了サンプル数、取得進捗は受信byte数に基づく。準備・検証・モデル読み込みは不定進捗。
- v3の`lyrics_tasks`はジョブと状態を、`lyrics_documents`は編集可能なversion 1 JSONのリビジョンを保持する。
  歌詞はUUIDと完成音源URIの組で関連付ける。再生成に失敗しても直前の歌詞を保持。
  中止済みタスクからの遅延保存をトランザクションで拒否する。
- 保存音源のメニューに「AI歌詞生成」「再生成」「中止」「書き出す」を追加。
  設定にモデル管理を追加。解析済みの場合は保存済み歌詞の利用を案内し、自動再解析しない。
- プレーヤーの「歌詞」で現在行と再生済み単語を色分けし、自動スクロール。
  行タップのシーク、シーク後の追従、一時停止時の停止、既存のダーク配色に対応。
  SAFでEnhanced LRC（単語の開始・終了タグ）、LRC、テキスト、内部JSONを書き出す。
  ファイル選択中の回転でも書き出し対象を保持する。

## 限界・未実装

- 歌唱の正確な強制アライメントは未実装。Whisper時刻は推定であり、単語同期の採用検査も正確さの保証ではない。
  日本語のトークン分割によって行同期になることが多い可能性がある。
- ボーカル分離は未実装。`AudioPreprocessor`に拡張口だけを用意している。
- 認識した歌詞の編集画面・JSON再取り込み画面は未実装。データ形式と履歴は編集・再生成を想定している。
- 30秒の窓にオーバーラップを設けていないため、窓境界の歌声で欠落が生じる可能性がある。
  全体の語句欠落・重複修復、高音・長母音・ハモリ・伴奏の分離処理は行わない。
- 最大2時間・元ファイル2GBに制限。mediumの温度・速度・電池消費と長時間安定性は要検証。
- アプリ再起動後の処理は安全な再試行を優先し、チャンク単位のチェックポイント再開や自動リトライはしない。
- 外部アプリが同じMediaStore URIの内容を上書きした場合、再生のたびのハッシュ再計算は行わない。
  変更後のファイルでは明示的に再生成する必要がある。生成時のsourceHashはJSONに記録される。
- キー変更ファイルのデコード開始オフセットと圧縮形式のエンコーダ遅延は、実ファイルで追加比較が必要。
- 認識は端末内のみ。モデル取得以外のAI関連のネットワーク通信は実装していない。
  既存の音源検索・ダウンロード機能は従来どおりインターネットを使う。

## Phase 6：再現手順

```sh
git submodule update --init --recursive
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python scripts/smoke_ytdlp.py
python scripts/smoke_pitch.py
# Androidテスト専用assetsへtinyモデルを取得（通常APKには入らない）
python scripts/fetch_lyrics_test_model.py
./gradlew :app:connectedDebugAndroidTest
# 標準smallでサービス全体も試す。既存アプリと異なるIDを使う。
python scripts/fetch_lyrics_test_model.py --model small
./gradlew :app:connectedDebugAndroidTest -PofflineVerification
```

Windows PowerShellでは、ドットを含むGradleプロパティ引数をクォートする。
`-PofflineVerification`は検証用のapplicationId `jp.tonekeep.app.offlineverify`を生成する。
instrumentationはテスト音源をMediaStoreに追加し、終了時に削除する。
検証JSON・画面は`Download/ToneKeep-verification`へ出力する。
CIはtinyによるJNI認識、FFmpeg前処理、推定時刻を機内モードのAPI 35エミュレータで検証する。
tinyはCIの起動確認専用で、製品のモデル選択肢には含めない。
smallサービス全体のテストはsmallのテストassetがある場合に実行し、tinyのCIでは明示的にskipする。

自作素材は`app/src/androidTest/assets/lyrics-ja.wav`と同名のtxtに出典・生成条件を記載。
短いオリジナル日本語をオフラインTTSで生成したものであり、商用曲や第三者の歌詞を含まない。
これは音声認識の起動確認用で、J-POPの歌唱品質を検証する素材ではない。

GPL-3.0-or-laterは維持。追加のMITライセンス全文をルートとアプリ内の`THIRD_PARTY_NOTICES.md`に追加した。
モデルは公式変換モデルの固定revisionを参照し、再配布時にもモデルのライセンス表示を保持する。

## 実機で確認できた範囲（2026-10-31）

ユーザーの許可を得て、接続端末SC-51G / Android 17 / arm64で検証アプリIDを使った。
smallモデル、約5.998秒のオリジナル日本語TTSでJNIテスト3件が成功した。
不正モデルのJava例外、キャンセル後の新しいセッションでの認識、元WAVの不変性、
FFmpeg→Whisper→SQLite、別URIへの誤紐付け防止、MediaPlayerでの再生・シーク・停止、歌詞Viewの操作を確認。

認識テキストは「青い空に小さな光 今日も一緒に歩いていこう」。
平均トークン確率は約0.924、無音確率は約0.0000193だった。
これは正解率や同期精度の指標ではない。この実音声は単語同期の採用条件を満たさず、
0〜5997msの行同期へフォールバックした。単語ハイライトは分離した単体テストでも確認する。

実機のネットワーク設定は変更していないため、実機での機内モード試験は未実施。
CIには機内モードのエミュレータテストを追加したが、GitHub上のワークフロー自体はローカル作業から実行していない。
実機でのモデルダウンロード中断・再開、medium、歌唱・伴奏・長母音・ハモリ、長い曲、
発熱・電池・ピークメモリ、強制終了・再起動、キー変更版の先頭オフセットは追加検証が必要。

既存のAndroid音声テストではFLACのMediaExtractor MIMEがAPI 35の`audio/raw`と異なり、
Android 17では`audio/flac`だった。両方を許容するテストへ修正し、FFprobeのcodec検証は維持した。
画面証跡は端末全体のスクリーンショットではなく、対象ActivityのViewだけを描画して保存する。

修正後、同じ実機で歌詞3件と既存音声1件のinstrumentationテスト計4件がすべて成功。
既存テストではWAV/M4A/Opus入力、キー変更、全出力形式、MediaStore保存、試聴・再生・シークとUI配置を確認した。
実機のページサイズは4096byteだったため、16KBページ端末での既存ランタイムを含めた確認は残る。
ホストの`smoke_ytdlp.py`と`smoke_pitch.py`もPASS。

最終の通常アプリID `jp.tonekeep.app.debug` で、`testDebugUnitTest`（44件・失敗0）、
`lintDebug`（エラー0・警告17）、`assembleDebug`が成功した。
警告は既存のリソース・依存更新通知や空き容量APIの推奨などを含む。
GitHubへのpush・PR作成・Actions実行・Release公開は行っていない。

## 変更ファイル

| 分類 | ファイル |
| --- | --- |
| ネイティブ | `.gitmodules`、`app/src/main/cpp/whisper.cpp`（固定submodule）、`app/src/main/cpp/CMakeLists.txt`、`app/src/main/cpp/lyrics_jni.cpp`、`app/src/main/java/jp/tonekeep/app/NativeWhisper.java` |
| 認識・モデル | 同Javaディレクトリの`LyricsEngine.java`、`ModelManager.java`、`JapaneseWords.java`、`LyricsAlignment.java` |
| データ・ジョブ | 同Javaディレクトリの`Lyrics.java`、`LyricsStore.java`、`LyricsService.java`、`DownloadStore.java` |
| UI | 同Javaディレクトリの`LyricsUi.java`、`LyricsView.java`、`AudioPlayer.java`、`MainActivity.java` |
| ビルド・CI | `app/build.gradle`、`app/src/main/AndroidManifest.xml`、`.github/workflows/android.yml`、`.ci/android_device.sh`、`.gitignore` |
| 単体テスト | `app/src/test/java/jp/tonekeep/app/LyricsTest.java`、`LyricsAlignmentTest.java`、`LyricsViewTest.java`、`DownloadStoreTest.java` |
| Androidテスト | `app/src/androidTest/java/jp/tonekeep/app/NativeLyricsTest.java`、`NativePitchTest.java`、`app/src/androidTest/assets/lyrics-ja.wav`、`lyrics-ja.txt`、`scripts/fetch_lyrics_test_model.py` |
| 説明・ライセンス | `README.md`、この文書、`THIRD_PARTY_NOTICES.md`、`app/src/main/assets/THIRD_PARTY_NOTICES.md` |

通常のAPKにWhisperモデルが含まれないことと、JNIライブラリの3 ABI収録をAPK内の一覧でも確認した。
成果物はignoredの`dist/`、テストモデルと実機証跡はignoredの`.tools/`に保存している。
