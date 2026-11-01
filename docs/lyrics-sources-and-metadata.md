# 既存歌詞と曲情報・画像の取得

2026-10-31のユーザー指定に合わせ、歌詞取得にはネットを使い、取得後は端末保存して再利用する。
同期は任意で端末内に限定する。音声ファイル、URI、音声ハッシュを外部へ送信しない。

## 実装

- 保存済み音源のメニューにLRCLIB検索、LRC/Enhanced LRC/テキスト読み込み、手動行同期、曲情報・画像検索、歌詞履歴を追加。
- LRCLIBへ送るのは編集可能な曲名とアーティスト名。検索結果を曲長で比較し、候補と本文を確認して保存する。
  約2.5秒を超える曲長差は採用しない。検索で4秒・30秒の抜粋版が返っても、244秒の音源と混同しない。
- 時刻付きLRCは取得した時刻で表示。時刻なし歌詞は`plainText`へ保存し、「未同期」と明示。
  文字数から時刻を作らず、未同期歌詞はLRCエクスポートを選択肢に出さない。
- 内部JSON v1に任意の`plainText`と`origin`を追加。従来のJSONも読み込める。
  取得元、URL、同期方式を保持。再取得・手動同期・履歴切替はリビジョンを追加し、前の歌詞を保持する。
- LRCは反復タイムタグ、offset、空行の終了タグ、Enhanced LRCの単語開始・終了を読み込む。
  行や単語の不正時刻を検査し、単語時刻が不正ならその行を行同期へ落とす。
- 任意の手動行同期は、再生位置を各行の開始時刻としてユーザーが記録する。
  各行の終了は次の行の開始。やり直し・再生/停止・保存に対応し、音声認識や通信は行わない。
  旧タップ方式は画面回転で未保存記録を保持しない。新しいなぞり方式は下書き保存・再開に対応する。
  [なぞり方式と任意の端末内AI同期](lyrics-sync-modes.md)を追加した。AIはASR照合による候補で、専用の日本語強制アライメントは未実装。
- yt-dlpへ`--write-info-json`を追加。保存完了後、`track`/`artist`/`album`を別テーブルへ保存。
  元ファイルの音声・ファイル名・従来のジョブタイトルを上書きしない。
- 自動曲情報取得は標準有効で設定から無効化可能。Wi-Fi限定のジョブはその制限を引き継ぐ。
  MusicBrainzの曲名・アーティスト名・曲長が一致し、候補が一意ならカタログを採用する。
  確信できない場合は配信元情報を残し、ユーザーが検索して選べる。
- アルバム画像はCover Art Archiveから取得。見つからない場合の配信元サムネイルは
  「アルバム画像は未確認」と区別する。情報取得の失敗を、完了した音源保存の失敗にしない。
- 一覧はカタログ曲名、アーティスト、アルバム、画像を表示。画像はバックグラウンドで読み込む。
  キー変更版はその完成音源IDに元の曲情報をコピーする。
- SQLite v3→v4で`track_metadata`のみを追加。歌詞・キュー・旧履歴を維持。
- HTTPS GETのみ。レスポンスサイズ、接続/読取時間、画像の寸法を制限する。
  MusicBrainzは1秒超、LRCLIBは400msのリクエスト間隔を設け、429/503の`Retry-After`は端末保存して守る。
  曲情報も歌詞も、閲覧・再生だけで再取得しない。

## 実機確認

SC-51G / Android 17、既存と別の`jp.tonekeep.app.offlineverify`で確認した。
以前から保存している同一MP3を使い、音声アップロードは行っていない。

| 情報 | 取得結果 |
| --- | --- |
| 曲名 | SAYONARA MAYBE |
| アーティスト | NOMELON NOLEMON |
| アルバム | ルール |
| MusicBrainz recording | `339994ff-6f8d-4c32-9deb-e8cd9ee322e2` |
| 選択されたrelease | `903be4c3-4d17-4983-bcec-1f00baae7881` |
| 画像 | Cover Art Archiveから取得し端末内JPEGへ保存 |
| LRCLIB record | `11244266`、244秒、歌詞本文あり・時刻なし |

任意の`OnlineSongInfoTest`でネット取得、画像保存、歌詞保存、ローカル再読込、URI分離を確認した。
通常のAPK画面でも、一覧の曲名・アーティスト・アルバム画像と、プレーヤーの「歌詞提供: LRCLIB・未同期」の本文表示を確認した。
`LocalLyricsSyncUiTest`では自作音声と自作歌詞を使い、実際の再生位置1秒・3秒をダイアログで記録し、
端末内の歌詞保存とLRC書き出しまで確認した。このテストは通信しない。
単体テスト56件（失敗0）、Android lint（エラー0・警告20）、APK/テストAPKビルドが成功。
通常CIではネット取得テストをskipする。外部の歌詞・ジャケットをテストfixtureや通常APKに含めない。
元音源を再ダウンロードする実際のyt-dlp→MusicBrainz自動連携は、実機での新規曲取得では未検証。
配信元sidecarのタグ保存と、取得失敗が音源保存を壊さない構造はローカルテストで検証する。
検証端末のAPKは更新済み。`dist/ToneKeep-lyrics-info-preview.apk`は同じ検証用applicationIdで、既存の別IDのToneKeepを上書きしない。

## 主な変更ファイル

`LyricsLookup.java`、`LrcParser.java`、`LyricsSourcesUi.java`、`SongDetails.java`、`MetadataHttp.java`、
`TrackMetadata.java`、`MusicCatalog.java`、`MetadataEnricher.java`、`ArtworkLoader.java`を追加。
`Lyrics.java`、`LyricsStore.java`、`LyricsUi.java`、`LyricsView.java`、`AudioPlayer.java`、`MainActivity.java`、
`DownloadStore.java`、`YtDlpEngine.java`、`DownloadService.java`を更新した。

## 取得元

- LRCLIB API: https://lrclib.net/docs （APIキー不要、User-Agent・レート制御を実装）
- LRCLIBのOSSサーバー: https://github.com/tranxuanthang/lrclib （MIT。コードは同梱していない）
- MusicBrainz API: https://musicbrainz.org/doc/MusicBrainz_API
- MusicBrainz core data: https://musicbrainz.org/doc/About/Data_License （CC0）
- Cover Art Archive API: https://musicbrainz.org/doc/Cover_Art_Archive/API

ソフトウェアのOSSライセンスと、歌詞・ジャケット個々の権利は別扱い。
追加クライアントはToneKeepのGPL-3.0-or-laterに従い、追加の外部SDKは同梱していない。
