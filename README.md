# ToneKeep

曲名・アーティスト名で保存可能な音源を検索し、配信元にある高品質ファイルをそのままダウンロードするAndroidアプリです。Android 10以降に対応します。

## できること

- Internet ArchiveのCCライセンス・パブリックドメイン表記がある音源を検索
- 作品内の曲を選び、同じ録音の形式・容量を比較
- ロスレス優先でファイルを選択、ロスレスのみの絞り込み
- AndroidのDownloadManagerでバックグラウンド保存、進捗表示・中止・再試行
- 今後のダウンロードをWi-Fi限定に設定
- 保存済み音源を対応プレーヤーで開く、配信元・ライセンスを再確認

保存先は `ダウンロード/ToneKeep/` です。アカウントやAPIキーは必要ありません。

## GitHubからAPKをダウンロード

1. リポジトリの **Releases** を開き、最新の **ToneKeep Preview** を選びます。
2. **Assets → ToneKeep-preview.apk** をAndroid端末でダウンロードします。
3. APKを開き、Androidの案内に従ってインストールします。必要に応じて使用したブラウザーの「不明なアプリのインストール」を許可します。

`main` へのpush、または **Actions → Android APK → Run workflow** でテスト・lint・ビルドが実行され、成功したビルドからAPK付きのプレリリースが作成されます。PRではテストとビルドのみを実行します。ActionsのArtifactからも30日間取得できます。

APKは公開テスト鍵で署名した検証版です。[署名について](.ci/README.md)を参照してください。一般配布向けの本番署名には別途非公開鍵を設定してください。

## 検索と音質について

検索はInternet Archiveの作品メタデータ（タイトル・作者・説明）を対象にします。曲単位で索引化されていないアルバムや市販曲など、見つからない曲があります。検索結果の作品を開くと、その中の曲と保存可能な形式を取得します。結果の件数には、ライセンスが確認できずアプリで除外する候補も含まれます。

最高品質の選択は、同一録音の明示された派生関係をたどり、ロスレス、既知のビット深度・サンプルレート、FLACを優先します。非可逆圧縮では既知のビットレートを優先します。情報がない数値は推定しません。異なる録音同士の品質を保証するものではなく、ロスレス形式でも元録音が非可逆圧縮の場合があります。変換やアップサンプリングは行いません。M4Aは拡張子だけではロスレスを判別できないため、ロスレス扱いしません。

ライセンスは配信元の投稿者情報に基づき、検索時・ファイル取得時の両方でCCの正式なURLを検証します。帰属表示や非営利利用など各音源の条件に従って利用してください。配信元の削除・アクセス制限・ネットワーク状況により保存できない場合があります。

## ローカルビルド

必要環境: JDK 17、Android SDK Platform 35 / Build Tools 35.0.0。

`local.properties` に `sdk.dir` を設定するか、`ANDROID_HOME` を設定します。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

macOS/Linuxでは `./gradlew` を使用します。出力は `app/build/outputs/apk/debug/app-debug.apk` です。

```sh
python scripts/smoke_archive.py
```

上の任意テストは実際の検索・メタデータAPIと音源の先頭1KBの取得を確認します。通常の単体テストはオフラインで再実行でき、形式選択、録音の分離、URLエンコード、ライセンス検証、検索条件の固定を確認します。端末でのUI・実ダウンロードは別途確認が必要です。

## データと通信

検索語をInternet Archiveに送信します。検索・メタデータ取得とダウンロードはHTTPSを使用します。アプリ固有の解析SDK・広告SDKはありません。端末内にダウンロード履歴（管理ID・タイトル・形式・配信URL・ライセンス）とWi-Fi設定を保存します。Wi-Fi限定は新規保存に適用されます。アプリ削除時に履歴は消えますが、共有ダウンロードフォルダーの音源ファイルは残ります。

## API資料

- [Internet Archive Advanced Search](https://archive.org/advancedsearch.php)
- [Internet Archive Metadata API](https://archive.org/developers/md-read.html)
- [Android DownloadManager](https://developer.android.com/reference/android/app/DownloadManager)
