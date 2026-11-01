# SAYONARA MAYBE.mp3 実機テスト

2026-10-31、ユーザー指定の `/storage/emulated/0/Download/SAYONARA MAYBE.mp3` を、
接続端末SC-51G / Android 17 / arm64-v8aで解析した。

| 項目 | 結果 |
| --- | --- |
| 原本サイズ | 9,827,357 bytes |
| デコード後の長さ | 244,390ms（4分4.390秒） |
| モデル・言語 | Whisper small多言語、日本語固定 |
| 解析経路 | 同梱FFmpeg → PCM16 / 16kHz / mono → 30秒窓のWhisper → SQLite |
| 解析所要時間 | 284,425ms（4分44.425秒、テスト準備のモデルコピー等は除く） |
| 結果 | 歌詞43行、単語同期3行・17単語、行同期フォールバック40行 |
| 時刻検証 | 行・単語の順序、範囲、所属行、JSON往復の検査を通過 |
| サービスのメモリ観測 | PSS 819,687KiB / 802,249KiB（2回の観測値でありピーク測定ではない） |
| 最大Android thermal status | 1（温度そのものの測定ではない） |
| 実行結果 | instrumentation 1件成功、クラッシュ・温度中断なし |

原本のSHA-256は前後とも以下で一致した。

```
cb8720a671a60a0dc6027bbf8213c35057907575735ee79e8baf881fbfd59fe6
```

原本は読み取りのみ。空白を含むファイル名を安全に扱うため、ADB shell所有の一時コピーを経由し、
同一ハッシュのアプリ所有MediaStoreコピーを実際のLyricsServiceへ渡した。
既存ToneKeepとは別の `jp.tonekeep.app.offlineverify` で実行した。
一時コピーとモデルは終了後に削除。音楽ファイル・認識した歌詞はGitに追加していない。

音声と歌詞は外部へ送信していない。既に取得済みのモデルをテストAPKから端末へコピーした。
端末全体の通信設定は変更しておらず、機内モードでの試験ではない。

認識結果は端末の `Download/ToneKeep-verification` と、ローカルのignoredディレクトリ
`dist/SAYONARA-MAYBE-test` にJSON、通常LRC、Enhanced LRC、テキスト、集計JSONとして保存した。
MediaStoreがtext/plainのLRCに`.txt`を追加したため、取り出したローカル成果物は`.lrc`の名前にした。

## 評価上の限界

単語同期を採用できたのは43行中3行（約7%）で、大部分は行同期だった。
Enhanced LRCも単語時刻を持つのはこの3行のみ。カラオケとして曲全体を単語単位で追う品質には達していない。
正解歌詞・正解アライメントとの照合や、曲全体を再生しての耳による同期評価は行っていない。
したがって、認識正解率や時刻誤差は算出していない。末尾には推定信頼度約0.35の短い行も含まれた。

実機の指定ファイルを扱う `DeviceLyricsFileTest.java` を追加した。
`sourcePath`を明示しない通常のCI実行では、このテストはskipする。
テストAPKのビルドとAndroid lintも成功した。アプリ本体の認識処理には今回変更を加えていない。

## 通常APKでの表示確認

同日、ユーザーのスマホ操作許可に基づき、検証用APKを再インストールした。
`DeviceLyricsDemoSetup`で同一ハッシュの曲コピーと前回の認識済みJSONを登録し、再解析せずに利用した。
その後は通常のActivityを起動し、ライブラリの再生ボタンから実際に再生。
実機画面で43行の歌詞表示、現在行の強調、自動スクロール、2分40秒付近へのシーク追従を確認した。
最終的に2分46秒付近から再生して歌詞画面を前面に置いた。

アプリ `jp.tonekeep.app.offlineverify` と、曲コピー `Download/ToneKeep-lyrics-demo/SAYONARA MAYBE.mp3`、
保存済み歌詞はユーザーがそのまま利用できるよう端末に残している。
既存ToneKeepと原本は変更していない。セットアップ専用テストAPKとADBの一時音声は削除した。
実際の端末画面はignoredの `dist/SAYONARA-MAYBE-test/phone-lyrics.png` に保存した。
