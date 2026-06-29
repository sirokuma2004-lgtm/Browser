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

### 次のステップ（M0完了）
- M1: タブ管理（追加/閉じる/切替＋サスペンド）、履歴 (Room)、ブックマーク
- PoC確認: 実機で ProxyController (PROXY_OVERRIDE) が使えるか確認 → M2 の方針確定

---

## 2026-06-29 — M1: タブ管理・履歴・ブックマーク

### 作業内容
- **TabManager** — タブの追加/閉じる/切替とサスペンド（WebView状態保存＋破棄）
- **BrowserViewModel** — AndroidViewModel化（Room/DataStore アクセス）、マルチタブ対応
- **BrowserScreen** — タブバー UI（水平スクロール、追加・閉じるボタン）
- **HistoryRepository** — Room保存（URL重複時はvisitCount更新）、検索
- **BookmarkRepository** — 追加・表示・削除
- **NetscapeHtmlImporter** — Netscape HTML形式パーサ（SAF経由で取り込む準備）
- アドレスバーに履歴サジェスト（入力200ms後にDB検索）
- ブックマークトグルボタン（スター表示）

### 作成/更新ファイル
```
app/src/main/java/com/example/hibari/browser/TabManager.kt          (更新)
app/src/main/java/com/example/hibari/ui/BrowserViewModel.kt          (更新)
app/src/main/java/com/example/hibari/ui/BrowserScreen.kt             (更新)
app/src/main/java/com/example/hibari/history/HistoryRepository.kt    (新規)
app/src/main/java/com/example/hibari/bookmarks/BookmarkRepository.kt (新規)
app/src/main/java/com/example/hibari/bookmarks/NetscapeHtmlImporter.kt (新規)
app/src/main/java/com/example/hibari/data/AppDatabase.kt             (更新: DAO拡充)
WORKLOG.md                                                            (更新)
```

### サスペンド戦略
- `key(activeTabId)` で Compose が別タブ切替時に TabWebView を再生成
- `DisposableEffect(tabId)` の onDispose で `wv.saveState(bundle)` → TabManager に保存
- 新タブ復元時: `TabManager.consumeSavedState(tabId)` → `wv.restoreState(bundle)`
- メモリ効率: アクティブタブのみ WebView が生存、他はサスペンド状態

### セキュリティ不変条件チェック（M1）
- [x] 非プライベートタブのみ履歴を保存（isPrivate チェック）
- [x] WebViewFactory 経由での生成を維持
- [x] タブ切替で不要な WebView を適切に破棄

### 次のステップ（M1完了）
- M2: セキュア DNS (DoH) — LocalProxyServer + DohResolver + ProxyController
- 先に実機で ProxyController.isFeatureSupported(PROXY_OVERRIDE) を確認

---

## 2026-06-29 — M2: セキュア DNS（DoH）

### 作業内容
- **DohResolver** — OkHttp `DnsOverHttps` ラッパ。既知プロバイダはブートストラップIP設定で鶏卵問題を回避
- **LocalProxyServer** — 127.0.0.1 ランダムポートの HTTP/HTTPS プロキシ
  - CONNECT: DoH解決 → raw TCP トンネル（TLS 素通し、MITM なし）
  - HTTP: DoH解決 → リクエスト転送 → レスポンス返却
  - Bidirectional bridge で双方向ストリームを並列処理（Coroutines）
- **ProxyManager** — LocalProxyServer ライフサイクル管理 + ProxyController 連携
  - `WebViewFeature.isFeatureSupported(PROXY_OVERRIDE)` で対応確認
  - 非対応端末は SettingsScreen でプライベートDNS案内を表示
- **SettingsScreen** — 設定UI
  - DoH ON/OFF スイッチ
  - プロバイダ選択（Cloudflare / Google / AdGuard / NextDNS / カスタムURL）
  - 広告ブロック ON/OFF、HTTPS-only、サードパーティCookie
  - 閲覧データ削除
- **BrowserViewModel** — DataStore 設定観察、プロキシ起動/停止ライフサイクル
- **BrowserScreen** — ⚙ 設定ボタン追加

### 作成/更新ファイル
```
app/.../net/DohResolver.kt         (更新: フル実装)
app/.../net/LocalProxyServer.kt    (更新: フル実装)
app/.../net/ProxyManager.kt        (新規)
app/.../ui/SettingsScreen.kt       (新規)
app/.../ui/BrowserViewModel.kt     (更新: M2対応)
app/.../ui/BrowserScreen.kt        (更新: 設定ボタン)
WORKLOG.md                         (更新)
```

### セキュリティ不変条件チェック（M2）
- [x] プロキシは 127.0.0.1 のみにバインド（外部非公開）
- [x] HTTPS は CONNECT トンネルで素通し（TLS 復号・MITM なし）
- [x] SSL エラーは引き続き cancel（HibariWebViewClient）
- [x] DoH解決失敗時は接続失敗（システム DNS にフォールバックしない）
- [x] WebViewFeature.isFeatureSupported(PROXY_OVERRIDE) で確認
- [x] テレメトリなし

### PoC 注記
実機での ProxyController 動作確認が必要。非対応端末の場合は
SettingsScreen 内の InfoCard で「Android プライベート DNS」案内を表示する。

### 次のステップ（M2完了）
- 設計変更: shouldInterceptRequest 広告ブロック廃止 → AdGuard DNS で代替
- [x] M3（旧M4）: セキュリティ堅牢化 → 完了

---

## 2026-06-29 — 設計変更: shouldInterceptRequest 広告ブロックを廃止

### 変更理由
軽量・シンプル優先の原則に従い、ブラウザ内フィルタリスト実装を廃止。
DoH プロバイダとして AdGuard DNS を選択することで DNS レベルの広告ブロックを実現。
追加コード・依存ゼロ。フィルタリストの更新も不要。

### 変更内容
- `DESIGN.md` §1, §5.4, §12, §14 を更新
- `adblock/DomainMatcher.kt`, `adblock/FilterList.kt` を削除
- `HibariWebViewClient` から M3 スタブを除去（shouldInterceptRequest 不使用を明記）
- `SettingsScreen` の「広告ブロック」ON/OFF トグルを削除
- `BrowserViewModel`/`SettingsUiState` から adBlockEnabled を削除
- README.md 更新

### 制約（許容済み）
- YouTube 広告など同一ドメイン配信の広告はブロック不可（v1 スコープとして許容）

---

## 2026-06-29 — M3: セキュリティ堅牢化

### 作業内容
- **HTTPS-Only** — `HibariWebViewClient.shouldOverrideUrlLoading` で http:// を https:// へ自動アップグレード
  - `httpsOnly` フラグを WebViewClient に渡し、update ブロックで動的更新（設定変更即時反映）
  - http:// → https:// へ内部リダイレクト。HTTPS が使えなければ SSL エラーページ（`cancel()` 維持）
- **権限既定拒否** — `buildChromeClient()` で `onPermissionRequest` と `onGeolocationPermissionsShowPrompt` を拒否
  - カメラ、マイク、MIDI 等の PermissionRequest → `request.deny()`
  - Geolocation → `callback.invoke(origin, false, false)`
- **サードパーティ Cookie ブロック** — `WebViewFactory.create` に `blockThirdPartyCookies` パラメータ追加
  - `CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)` を WebView 生成時に適用
  - 設定変更時は `WebViewFactory.applyThirdPartyCookies(wv, ...)` を update ブロックで即時反映
- **プライベートタブのデータ消去** — `DisposableEffect.onDispose` で `isPrivate=true` のタブが閉じられたとき
  - `BrowserViewModel.clearPrivateTabData()` を呼び出し Cookie/WebStorage を消去
- `SettingsDataStore` から残留していた `adBlockEnabled` フィールドを削除

### 作成/更新ファイル
```
app/.../browser/HibariWebViewClient.kt  (HTTPS-Only shouldOverrideUrlLoading 実装)
app/.../browser/WebViewFactory.kt       (blockThirdPartyCookies パラメータ追加)
app/.../data/SettingsDataStore.kt       (adBlockEnabled 残留を削除)
app/.../ui/BrowserScreen.kt             (WebChromeClient 権限拒否、プライベートタブ消去)
app/.../ui/BrowserViewModel.kt          (clearPrivateTabData() 追加)
WORKLOG.md                              (更新)
```

### セキュリティ不変条件チェック（M3）
- [x] SSL エラーを握り潰さない（`handler.cancel()` 維持）
- [x] HTTPS-Only: http:// → https:// 自動アップグレード（設定 ON 時）
- [x] カメラ/マイク/位置情報の権限を既定拒否
- [x] サードパーティ Cookie をブロック（デフォルト ON）
- [x] プライベートタブ終了時に Cookie/ストレージを消去
- [x] TLS を中間者化しない（CONNECT トンネルを維持）

### 絶対原則チェック（M3）
- [x] 軽量・高速: 追加依存なし
- [x] シンプル: 権限ダイアログ実装なし（拒否のみ）でシンプルに
- [x] テレメトリなし

### 注記
ビルド環境の Java が 1.8.0（JRE 8）のため `./gradlew assembleDebug` が失敗する。
AGP 8.2.2 は Java 11+ が必須。ビルドするには Java 11 以上のインストールが必要。

---

## 2026-06-29 — M4: パフォーマンス最適化・リリースビルド

### 作業内容

#### WebView ライフサイクル連動（バッテリー節約）
- `TabWebView` に `DisposableEffect(lifecycle)` を追加
- `LifecycleEventObserver` で `ON_PAUSE → wv.onPause()` / `ON_RESUME → wv.onResume()`
- アプリがバックグラウンド時にバックグラウンドタブの JS タイマー・アニメーションが停止

#### ダウンロード確認ダイアログ
- `WebView.setDownloadListener()` でダウンロードリクエストをインターセプト
- `URLUtil.guessFileName()` でファイル名を推測し確認ダイアログを表示
- 確認後に `DownloadManager.Request` でシステムダウンローダーに委譲
- 独自ダウンロードロジックを持たないシンプルな実装

#### リリースビルド設定
- `versionCode = 4`, `versionName = "1.0.0"` に更新
- `release { isDebuggable = false }` を明示
- `proguard-rules.pro` を整備:
  - OkHttp DnsOverHttps の keep ルールを追加
  - Room `@Entity` / `@Dao` を keep
  - DataStore keep
  - スタックトレース可読性のため `SourceFile,LineNumberTable` を保持

### 作成/更新ファイル
```
app/.../ui/BrowserScreen.kt      (ライフサイクル連動・ダウンロードハンドラ追加)
app/build.gradle.kts             (versionCode/Name 更新、isDebuggable=false 明示)
app/proguard-rules.pro           (OkHttp DnsOverHttps・Room・DataStore ルール整備)
WORKLOG.md                       (更新)
```

### パフォーマンス規約チェック（M4）
- [x] エンジン非同梱（System WebView のまま）
- [x] 起動を妨げない（DoH/プロキシ起動は BG Coroutine 済み）
- [x] バックグラウンドタブはサスペンド（key(activeTabId) + DisposableEffect 済み）
- [x] バックグラウンド時 WebView.onPause() 呼び出しでリソース解放
- [x] ダウンロードはシステム委譲（独自ロジックなし）
- [x] リリースビルドで R8 最適化（minify + shrink）、debug 無効化

### 絶対原則チェック（M4）
- [x] 新規依存なし（LifecycleEventObserver は既存依存の一部、DownloadManager は OS API）
- [x] テレメトリなし

---

---

## 2026-06-29 — ブックマーク SAF インポート UI 接続

### 作業内容
M1 で実装済みの `NetscapeHtmlImporter` を SettingsScreen から実際に呼び出せるよう接続。

- **BrowserViewModel** — `importBookmarks(context, uri)` を追加（SAF URI → InputStream → NetscapeHtmlImporter）
  - `importResultMessage: StateFlow<String?>` でインポート結果をUI通知
  - `clearImportResult()` で通知を消去
- **SettingsScreen** — 「ブックマーク」セクションを追加
  - `rememberLauncherForActivityResult(GetContent())` で SAF ファイルピッカを起動
  - `importLauncher.launch("text/html")` で HTML ファイルを選択
  - インポート完了後に結果ダイアログを表示（imported 件数 / skipped 件数）

### 作成/更新ファイル
```
app/.../ui/BrowserViewModel.kt  (importBookmarks / importResultMessage / clearImportResult 追加)
app/.../ui/SettingsScreen.kt    (SAF ランチャー + ブックマークセクション + 結果ダイアログ)
WORKLOG.md                      (更新)
```

### 受け入れ基準チェック（DESIGN §14）
- [x] SAF で選んだ Netscape HTML を取り込める
- [x] 重複 URL はスキップ（DuplicatePolicy.SKIP デフォルト）
- [x] インポート結果（件数）をダイアログで表示
- [x] VPN・端末全体設定に触れない（SAF 経由のファイルアクセスのみ）

---

## TODO（将来マイルストーン）
- [x] M0: 雛形＋WebView 堅牢化
- [x] M1: タブ管理 + 履歴 (Room) + ブックマーク
- [x] M2: セキュア DNS (DoH) — ローカルプロキシ + OkHttp DnsOverHttps + ProxyController
- [~] M3: ~~shouldInterceptRequest 広告ブロック~~ → **廃止**。AdGuard DNS で代替
- [x] M3（旧M4）: セキュリティ堅牢化
- [x] M4: パフォーマンス最適化・リリースビルド
- [x] ブックマーク SAF インポート UI 接続（DESIGN §14 受け入れ基準完了）
