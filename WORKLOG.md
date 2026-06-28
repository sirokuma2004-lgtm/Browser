# Hibari 開発作業ログ

## 概要
軽量 Android ブラウザ「Hibari」の開発ログ。
設計仕様: `DESIGN.md` / 規約: `CLAUDE.md`
GitHub: https://github.com/sirokuma2004-lgtm/Browser

---

## 2026-06-29 — M0: プロジェクト雛形＋WebView 堅牢化

### 作業内容
- Android Kotlin (Jetpack Compose) プロジェクト骨格を作成
- アドレスバー＋戻る/進む/更新の最小 UI
- WebView 基本堅牢化（JS ブリッジ無し・file:// 制限・Safe Browsing・混在遮断）
- GitHub リポジトリ (sirokuma2004-lgtm/Browser) への初回 push

### 作成ファイル
```
settings.gradle.kts
build.gradle.kts
gradle.properties
gradle/wrapper/gradle-wrapper.properties
gradlew / gradlew.bat
app/build.gradle.kts
app/proguard-rules.pro
app/src/main/AndroidManifest.xml
app/src/main/res/values/strings.xml
app/src/main/res/values/colors.xml
app/src/main/res/values/themes.xml
app/src/main/res/xml/network_security_config.xml
app/src/main/java/com/example/hibari/MainActivity.kt
app/src/main/java/com/example/hibari/ui/BrowserScreen.kt
app/src/main/java/com/example/hibari/ui/BrowserViewModel.kt
app/src/main/java/com/example/hibari/ui/theme/Color.kt
app/src/main/java/com/example/hibari/ui/theme/Theme.kt
app/src/main/java/com/example/hibari/ui/theme/Type.kt
app/src/main/java/com/example/hibari/browser/WebViewFactory.kt
app/src/main/java/com/example/hibari/browser/HibariWebViewClient.kt
app/src/main/java/com/example/hibari/browser/TabManager.kt
app/src/main/java/com/example/hibari/net/LocalProxyServer.kt (stub)
app/src/main/java/com/example/hibari/net/DohResolver.kt (stub)
app/src/main/java/com/example/hibari/adblock/FilterList.kt (stub)
app/src/main/java/com/example/hibari/adblock/DomainMatcher.kt (stub)
app/src/main/java/com/example/hibari/data/AppDatabase.kt (stub)
app/src/main/java/com/example/hibari/data/SettingsDataStore.kt (stub)
```

### セキュリティ不変条件チェック（M0）
- [x] Web コンテンツに JS ブリッジを出さない（addJavascriptInterface 未使用）
- [x] TLS を中間者化しない / SSL エラーを握り潰さない（onReceivedSslError で cancel）
- [x] file:// アクセスを無効（allowFileAccess=false）
- [x] Safe Browsing 有効（WebViewFeature.isFeatureSupported 確認後）
- [x] 混在コンテンツ遮断（MIXED_CONTENT_NEVER_ALLOW）
- [x] テレメトリなし
- [x] WebView デバッグ無効（setWebContentsDebuggingEnabled(false)）

### 絶対原則チェック（M0）
- [x] 軽量・高速: エンジン非同梱、最小依存
- [x] シンプル: 最小 UI、不要機能なし
- [x] エンジンを同梱しない: System WebView 使用
- [x] JS ブリッジを Web に出さない

### 次のステップ
- M1: タブ管理（追加/閉じる/切替＋サスペンド）、履歴 (Room)、ブックマーク
- PoC確認: 実機で ProxyController (PROXY_OVERRIDE) が使えるか確認 → M2 の方針確定

---

## TODO（将来マイルストーン）
- [ ] M1: タブ管理 + 履歴 (Room) + ブックマーク
- [ ] M2: セキュア DNS (DoH) — ローカルプロキシ + OkHttp DnsOverHttps + ProxyController
- [ ] M3: 広告ブロック — shouldInterceptRequest + フィルタリスト + allowlist
- [ ] M4: セキュリティ堅牢化総点検
- [ ] M5: パフォーマンス最適化・リリースビルド
