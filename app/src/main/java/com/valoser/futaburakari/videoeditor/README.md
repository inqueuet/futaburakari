# Futaburakari 動画編集機能

## 概要
claude.mdの仕様に基づいた、シンプルで高速な動画編集機能の実装。
カット編集と音声編集に特化し、大型UIで見やすく操作しやすい設計。

## アーキテクチャ

### レイヤー構成
```
presentation/          # Presentation層
├── ui/
│   ├── editor/       # エディタ画面
│   └── components/   # UIコンポーネント
└── viewmodel/        # ViewModel (MVI)

domain/               # Domain層
├── model/           # ドメインモデル
├── usecase/         # ユースケース
└── session/         # セッション管理

data/                # Data層
├── session/         # セッション管理実装
├── repository/      # リポジトリ
└── usecase/         # ユースケース実装

media/               # Media Engine層
├── player/          # プレイヤーエンジン
├── audio/           # 音声処理
├── thumbnail/       # サムネイル生成
└── export/          # エクスポート

core/                # Core層
└── di/              # 依存性注入
```

## 主な機能

### Phase 1 実装済み機能

#### 編集機能
- ✅ トリム（クリップの両端を調整）- ドラッグ&ドロップで操作可能
- ✅ 分割（プレイヘッド位置で2分割）
- ✅ 範囲削除（クリップの途中部分を削除）
- ✅ クリップ削除（クリップ全体を削除）
- ✅ 並べ替え（ドラッグ&ドロップで順序変更）- **実装完了**
- ✅ コピー（クリップを複製）
- ✅ 速度変更（0.25x/0.5x/1x/2x/4x）

#### 音声機能
- ✅ 音量調整（0-200%のスライダー）
- ✅ 音量キーフレーム（時間経過で音量を変化）- **実装完了**
- ✅ ミュート（音声ON/OFF）
- ✅ 音声トラック編集（音声の独立編集）
- ✅ 音声無音化（範囲を無音に）
- ✅ 音声差し替え（別音声に置き換え）
- ✅ 音声トラック追加（BGM/ナレーション追加）
- ✅ フェードイン/アウト（0.3s/0.5s/1s）- **実装完了**

#### UI機能
- ✅ 大型プレビュー（画面の45%）- **ExoPlayer統合完了**
- ✅ フィルムストリップ（64dp高さ）- **サムネイル表示実装完了**
- ✅ 波形表示（48dp高さ）- **RMS波形描画実装完了**
- ✅ キーフレームバー（32dp高さ）
- ✅ Undo/Redo（最大20回）
- ✅ ドラッグ&ドロップUI - **トリム・移動実装完了**

#### エクスポート・トランジション
- ✅ エクスポート機能（3つのプリセット）- **実装完了**
- ✅ トランジション（クロスフェード）- **実装完了**
- ✅ オーディオ処理（フェード、音量調整）- **実装完了**

### 今後の実装予定

#### Phase 2
- マーカー機能の完全実装
- キーフレームUI の改善
- エクスポート進捗表示の改善
- パフォーマンス最適化

#### Phase 3
- テキスト追加
- 録音機能
- A-Bループ再生
- 速度カーブ
- 4K出力対応

## 使用方法

### セッションの作成
```kotlin
viewModel.handleIntent(
    EditorIntent.CreateSession(
        videoUris = listOf(videoUri1, videoUri2)
    )
)
```

### クリップの編集
```kotlin
// トリム
viewModel.handleIntent(
    EditorIntent.TrimClip(
        clipId = "clip-id",
        start = 0L,
        end = 5000L
    )
)

// 分割
viewModel.handleIntent(
    EditorIntent.SplitClip(
        clipId = "clip-id",
        position = 2500L
    )
)

// 速度変更
viewModel.handleIntent(
    EditorIntent.SetSpeed(
        clipId = "clip-id",
        speed = 2.0f
    )
)
```

### 音声編集
```kotlin
// 音量設定
viewModel.handleIntent(
    EditorIntent.SetVolume(
        trackId = "track-id",
        clipId = "clip-id",
        volume = 1.5f
    )
)

// 音量キーフレーム追加
viewModel.handleIntent(
    EditorIntent.AddVolumeKeyframe(
        trackId = "track-id",
        clipId = "clip-id",
        time = 1000L,
        value = 0.5f
    )
)

// 音声トラック追加
viewModel.handleIntent(
    EditorIntent.AddAudioTrack(
        name = "BGM",
        audioUri = audioUri,
        position = 0L
    )
)
```

### 再生制御
```kotlin
// 再生
viewModel.handleIntent(EditorIntent.Play)

// 一時停止
viewModel.handleIntent(EditorIntent.Pause)

// シーク
viewModel.handleIntent(
    EditorIntent.SeekTo(timeMs = 5000L)
)
```

## 技術仕様

### パフォーマンス要件
- 起動時間: 0.2秒以内
- プレビューフレームレート: 120fps
- スクラブ応答時間: 16ms以内
- メモリ使用量: 180MB以内（1080p編集時）

### 対応フォーマット
- 入力: MP4(H.264/H.265), MOV, WEBM
- 音声: WAV, MP3, AAC
- 出力: MP4(H.264 + AAC)

### UI サイズ仕様
- ヘッダー: 56dp
- プレビュー: 45%（画面高さの約半分）
- タイムライン操作バー: 48dp
- フィルムストリップ: 64dp（1.8倍に拡大）
- 映像クリップ: 72dp（1.5倍に拡大）
- 音声クリップ: 64dp（1.3倍に拡大）
- キーフレーム: 32dp（1.3倍に拡大）
- 波形: 48dp（1.5倍に拡大）
- 時間軸: 32dp（1.3倍に拡大）
- ツールバー: 72dp

## 依存関係
- Jetpack Compose
- Media3 ExoPlayer
- Hilt (依存性注入)
- Coroutines + Flow

## 設計パターン
- MVI (Model-View-Intent)
- Clean Architecture
- Repository Pattern
- UseCase Pattern

## メモ
- プロジェクト保存機能は意図的に省略（シンプルさ優先）
- メモリ内でのみセッション管理
- アプリ終了時にキャッシュをクリア
