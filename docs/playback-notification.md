# 再生通知とバックグラウンド再生

保存済み音源を再生すると、Android標準のメディア通知へ曲名・アーティスト・アルバム・ジャケット・長さ・現在位置を渡す。通知パネル、クイック設定、ロック画面のカードの配置や色はOSが決める。Galaxyのスクリーンショットと同じ外観をアプリ側で描画する実装ではない。

## 今回の変更

| ファイル | 役割 |
| --- | --- |
| `app/src/main/java/jp/tonekeep/app/PlaybackCore.java` | アプリ共通のMediaPlayer、再生キュー、シャッフル/リピート、音声フォーカス、イヤホン切断、FFmpegフォールバックを管理。保持するContextはApplicationのみ。 |
| `app/src/main/java/jp/tonekeep/app/PlaybackService.java` | mediaPlaybackフォアグラウンドサービス、MediaSession、MediaStyle通知、端末内ジャケットの非同期読み込み。 |
| `app/src/main/java/jp/tonekeep/app/AudioPlayer.java` | ミニ/全画面プレーヤーと歌詞を共通の再生位置へ接続。Activity終了時は表示の購読を解除する。 |
| `app/src/main/java/jp/tonekeep/app/MainActivity.java` | サービス内の曲を引き継ぎ、通知タップで再生画面を開く。曲送り後は同じ保存IDの歌詞へ切り替える。 |
| `app/src/main/AndroidManifest.xml` | `FOREGROUND_SERVICE_MEDIA_PLAYBACK`権限と非公開サービスを登録。 |
| `app/src/main/res/drawable/ic_stat_music.xml` | 単色の通知アイコン。 |
| `app/src/test/java/jp/tonekeep/app/PlaybackNotificationTest.java` | PCで再生・通知・画面ライフサイクルの回帰確認。 |
| `app/src/androidTest/java/jp/tonekeep/app/PlayerUiTest.java` | 実際のMediaSessionトークン、メタデータ、通知からの再開を検証するテストを追加。 |
| `app/src/androidTest/java/jp/tonekeep/app/{LyricsSyncModesTest,LocalLyricsSyncUiTest,NativePitchTest}.java` | 自作テスト音源の削除前に再生を明示的に終了。 |
| `README.md`, `docs/player-ui.md` | 通常再生が画面を離れても継続する仕様へ更新。 |

新しいライブラリやネットワーク送信は追加していない。保存済みメタデータと端末内音源を使う。既存の保存・キー変更・歌詞処理のサービス/キュー/DBスキーマは変更していない。

## 操作とライフサイクル

- 通知/MediaSessionから再生、一時停止、前後の曲、シーク。3秒を超えてから「前」を押すと現在曲の先頭へ戻る。
- 同名の曲やキー変更版も保存済みIDで区別。画面を閉じても曲送り、シャッフル、リピートが動く。
- 再生位置はMediaPlayerの実時計。PlaybackStateに位置・速度・更新時刻を渡してOSのシークバーへ連動する。アプリ内の歌詞も同じ時計を使う。
- 通知タップで曲を再読み込みせず再生画面へ戻る。回転後も再生状態を維持する。
- イヤホン切断や音声フォーカス喪失時は一時停止。短いキー変更の試聴は画面を離れると停止する。
- 再生準備やバックグラウンドからの再開では、フォアグラウンド通知を掲載してから音声フォーカスを取得する。
- 一時停止後5分でフォアグラウンド状態を解除する。通知/セッションが残っている間は再開可能。明示的な終了では再生・通知・一時WAVを解放する。
- サービスは`START_NOT_STICKY`。OSによるプロセス終了、強制停止、端末再起動後に勝手に再生しない。プロセス終了後のBluetooth再開、起動後の再生履歴カード復元、外部機器への独自配信は未実装。
- 通知用ジャケットは最大640pxへサンプリング。曲変更後に古い画像読み込みが完了しても現在曲へ適用しない。

## 検証の範囲

Windows PCで次のコマンドが成功した。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest -PofflineVerification --no-daemon --max-workers=2
```

- 単体テスト89件成功、失敗/スキップ0件。今回の通知テスト7件を含む。
- lint: エラー0、既存警告16。
- アプリAPKとAndroidテストAPKを生成。`apksigner verify`成功。
- 配布ファイル: `dist/ToneKeep-media-notification-preview.apk`（162,173,548 bytes）。公開テスト鍵で署名した、従来の検証用ID `jp.tonekeep.app.offlineverify` のAPK。
- SHA-256: `c80ad563f3aff174b052844844a02b2d68bb84e74c05125c60e44aca235dd903`。
- 機械可読の結果: `dist/media-notification-verification.json`。

PCテストはRobolectricのMediaPlayer/Androidビューを使用する。Robolectric標準のMediaSessionにはBinder実装がないため、この境界だけ記録用Shadowに置き換えている。通知本文、MediaStyle、メタデータ、再生状態、コールバックから実再生コードへの接続、画面の再作成/終了を確認する。実際のAndroid音声出力やSamsung SystemUIの表示確認を意味しない。

Android用の追加テストは自作の短い日本語音声と自作画像を使用し、実際の通知トークンからMediaControllerを作って操作する。既存CIは全Androidテストを実行する設定のため、この追加テストも対象。通知機能の初回PC検証時点ではGitHub Actionsのリモート実行や公開リリースは行っていなかった。後記の自動Release経路は別途追加した。

Galaxy / Android 17では、通知とロック画面のジャケット・タイトル・シークバー、通知からの操作、画面消灯/タスクを閉じた後の継続、Bluetooth/有線機器の切断、Samsungの省電力設定の影響を確認する必要がある。今回のAPKはこれらが未検証のプレビュー。

実装時の確認資料: [MediaPlayerのバックグラウンド再生](https://developer.android.com/media/platform/mediaplayer/background)、[mediaPlaybackサービス](https://developer.android.com/develop/background-work/services/fgs/service-types#media)、[音声フォーカス](https://developer.android.com/media/optimize/audio-focus)。


## GitHubからAPKを取得する

`main` と `feature/offline-ai-lyrics` へのpushでは、既存のビルド・単体テスト・lint・Androidエミュレーターテストの両ジョブが成功した後に `preview-<run番号>-<試行番号>` タグとプレビューReleaseを作り、`ToneKeep-preview.apk` と `SHA256SUMS.txt` を添付する。失敗した場合は公開しない。

[Releases](https://github.com/tstyr/ToneKeep/releases) の該当プレビューを開き、AssetsのAPKを直接ダウンロードできる。機能ブランチでは従来の検証アプリID `jp.tonekeep.app.offlineverify` を維持する。`main` の既存配布ルールと、PRで公開しない制約は維持する。


### 停止後の古い通知からの操作

CIで短時間の停止・再開後に、SystemUIの古い通知削除処理が新しい再生へ届くケースを確認した。サービス/MediaSessionの世代ごとに通知IDと操作PendingIntentのURIを分け、古いセッションからの操作・古いサービスの破棄が現在の再生を変更しないようにした。所有権はContextを持たないトークンで判定する。PCの回帰テストでは旧セッションの停止・一時停止・削除Intentを新しい再生へ送っても再生が継続することを確認する。

この追加修正後はPC単体テスト90件成功、lintエラー0・既存警告16、アプリ/AndroidテストAPKのビルド成功を確認した。
