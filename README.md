# Hibari — 軽量 Android ブラウザ

Android System WebView を薄くラップした**軽量ブラウザシェル**。

## 特徴
- **軽量・高速**: エンジン非同梱（System WebView 使用）、APK 小・起動速
- **セキュア DNS (DoH)**: Chrome の「セキュア DNS を使用する」相当。プロバイダ選択可
- **広告ブロック**: DNS とは独立。`shouldInterceptRequest` によるブラウザ内遮断
- **ブックマーク**: Netscape HTML インポート対応
- **プライバシー**: テレメトリなし、データはローカルのみ

## ビルド要件
- Android Studio Hedgehog 以降
- JDK 17 以降
- Android SDK (compileSdk 34, minSdk 26)

## セットアップ
```bash
# gradle-wrapper.jar が必要（Android Studio で開くと自動セットアップ）
# または:
gradle wrapper --gradle-version 8.6
```

```bash
./gradlew assembleDebug      # デバッグビルド
./gradlew installDebug       # 実機/エミュにインストール
./gradlew test               # 単体テスト
./gradlew lint               # Lint
```

## アーキテクチャ
詳細は `DESIGN.md` を参照。

```
UI (Jetpack Compose)
  └─ WebView (Android System WebView)
       ├─ shouldInterceptRequest → 広告ブロック (M3)
       └─ ProxyController → ローカルプロキシ → OkHttp DoH (M2)
```

## ロードマップ
- [x] M0: 雛形＋WebView 堅牢化
- [ ] M1: タブ管理・履歴・ブックマーク
- [ ] M2: セキュア DNS (DoH)
- [ ] M3: 広告ブロック
- [ ] M4: セキュリティ堅牢化
- [ ] M5: パフォーマンス最適化・リリース

## ライセンス
Apache 2.0
