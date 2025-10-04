# ふたブラ仮 (Futaburakari)

Futaburakari は、二次元画像掲示板「ふたば☆ちゃんねる」系サイトの閲覧・投稿を支援する Android 向け非公式クライアントです。Jetpack Compose を全面採用した UI、WorkManager によるバックグラウンド監視、画像メタデータ解析や TTS 読み上げといった補助機能を備えています。

## 主な機能
- **ブラウズ**: カタログをグリッド/リスト切替で表示し、スレッド詳細ではレス/画像/動画を Compose でシームレスに描画。
- **投稿**: 画像添付や hidden/token 自動取得を含むレス投稿ダイアログ、送信失敗時の再送制御。
- **保存と監視**: 履歴・ブックマーク管理、WorkManager を用いたスレッド監視とスナップショット保存、未読レス数の追跡。
- **NG & 検索**: NG ルール（板・レス・投稿者単位）の追加/編集、スレ内/カタログ内検索履歴 (`RecentSearchStore`) の保持。
- **メディア**: Coil 3 + Media3 による画像/動画読み込み、ローカル保存、キャッシュ管理 (`DetailCacheManager`)。
- **プロンプト解析**: EXIF や JSON（ComfyUI ワークフローを含む）から Stable Diffusion 系プロンプトを抽出し、フォーマット表示 (`PromptFormatter`, `MetadataExtractor`)。
- **画像編集**: Mosaic/消しゴムツール付きの簡易画像編集 (`ImageEditActivity` + `EditingEngine`)、保存時に EXIF プロンプトも可能な限り引き継ぎ。
- **読み上げ**: スレ本文の Text-To-Speech 読み上げと速度調整、レス番号追跡 (`TtsManager`)。
- **ユーティリティ**: 端末内画像のメタデータ閲覧 (`ImageDisplayActivity`)、catset 設定の自動適用、AdMob バナー（テスト ID デフォルト）。

## 技術スタック
- Kotlin 1.9 / Gradle 8.12 / JDK 17
- Jetpack Compose (Material3, Foundation, Runtime LiveData) + ViewBinding 併用
- Hilt (DI) + WorkManager（背景監視）
- Coil 3 (画像キャッシュ) + OkHttp / Jsoup (通信・HTML 解析)
- Media3 (ExoPlayer UI)、AndroidX ExifInterface
- Firebase Analytics（`google-services.json` が存在する場合のみ有効）
- Google Mobile Ads SDK（AdMob、OSS 版はテスト ID）

## 動作要件
- Android Studio Ladybug 以上
- Android 端末 / エミュレータ: Android 7.0 (API 24) 以降
- JDK 17、Gradle 8.12 系

## セットアップ
1. リポジトリをクローンします。
2. （任意）Firebase Analytics を使う場合は `app/google-services.example.json` をコピーして `google-services.json` を作成し、Firebase Console の値を設定します。ファイルが無い場合はプラグインが自動的に無効化されます。
3. （任意）AdMob を本番 ID で使う場合は `app/src/main/res/values/strings.xml` の `admob_app_id` / `admob_banner_id` をビルド時に差し替えてください（`build.gradle.kts` の `resValue` でも可）。
4. Android Studio でプロジェクトを開き Gradle Sync を実行、または CLI で `./gradlew assembleDebug` を実行してデバッグビルドを生成します。

### 秘匿情報の管理
- Firebase API キーや AdMob ID、署名鍵 (`.jks` / `.keystore` / `.p12`) は公開リポジトリへ含めないでください。
- 署名鍵や本番用 ID は `local.properties` や CI のシークレットから読み込む構成を推奨します。

### 開発メモ
- メイン画面は `MainActivity` → `ui/compose/MainCatalogScreen`、スレ詳細は `DetailActivity` → `ui/detail` 配下で構成されています。
- 背景監視は `ThreadMonitorWorker`（WorkManager）と `HistoryManager` が連携しており、履歴削除時は監視も自動停止します。
- プロンプト解析は `MetadataExtractor` / `PromptFormatter` を中心に、EXIF や ComfyUI JSON のケースをサポートしています。

## プライバシーポリシー / サポート
- プライバシーポリシー: [https://note.com/inqueuet/n/nb86f8e3f405a](https://note.com/inqueuet/n/nb86f8e3f405a)（`docs/PRIVACY.md` と同内容）
- 問い合わせ・バグ報告: [GitHub Issues](https://github.com/inqueuet/futaburakari/issues)

## ライセンス
- 本プロジェクトは MIT License の下で提供されます。詳細は [`LICENSE`](LICENSE) を参照してください。
- 依存 OSS のライセンス一覧は [`NOTICE.md`](NOTICE.md) を参照してください。

## コントリビューション
Pull Request / Issue は歓迎します。コントリビューション時は次の点にご留意ください。
- Firebase や AdMob の実環境情報はモック化・削除した状態で共有する
- 変更内容にテスト（単体/UI）がある場合は PR に記載し、必要に応じて追加する
- 大きな仕様変更やリファクタリングは事前に Issue で方向性を相談してください
