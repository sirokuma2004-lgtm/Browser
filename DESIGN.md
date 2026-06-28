# 設計書 — 軽量 Android ブラウザ「Hibari」(仮称)

> 仮称「Hibari（雲雀）」は自由に変更してください。本書は Claude Code に実装を依頼するための設計仕様です。
> 併せて `CLAUDE.md`（実装中に常時参照させる規約）を用意しています。
> 対象は **Android 専用 / ネイティブ Kotlin**。

---

## 0. 最重要の前提（最初に読むこと）

**ブラウザエンジンは自作しない。** Android System WebView（Chromium ベース、Play ストア経由で更新される）を**薄くラップした「ブラウザシェル」**を作る。レンダリングは WebView に任せ、我々は「ガワ（UI）/ タブ管理 / セキュアDNS / 広告ブロック / ブックマーク / 設定 / セキュリティ」を実装する。

この方針が「軽量・高速」を実現する理由：
- レンダリングエンジンを同梱しない → APK が小さい・メモリが小さい
- エンジンのセキュリティ修正は **Android System WebView の更新（Play ストア）経由で自動的に降ってくる**（自前パッチ不要＝セキュリティ上もプラス）

割り切り（正直に明記）：
- 表示は端末の WebView 実装に依存する（WebView のバージョン差で挙動が変わりうる）。
- 一部 API は WebView のバージョン依存。`WebViewFeature.isFeatureSupported(...)` で必ず存在確認してから使う（§11）。

---

## 1. 設計目標と優先順位

要件を整理する。判断に迷ったら上から優先する。

| 優先 | 目標 | 具体的な意味 |
|---|---|---|
| ★最優先1 | **軽量・高速** | 起動が速い／メモリが小さい／APK が小さい。重い依存を入れない |
| ★最優先2 | **可能な限りシンプル** | 機能を盛らない。複雑/侵襲的な仕組み（TLS 中間者化、端末全体 VPN など）を避ける |
| 機能 | **セキュアDNS（DoH）** | Chrome の「セキュア DNS を使用する」相当。DoH プロバイダを選択可能に（プライバシー機能） |
| 機能 | **DNS フィルタリング（広告ブロック）** | DoH プロバイダで AdGuard DNS を選択することで DNS レベルの広告・トラッカブロックを実現。**ブラウザ内 shouldInterceptRequest は使わない**（軽量・シンプル優先） |
| 機能 | **ブックマークのインポート** | 標準 HTML（Netscape）形式のファイルを取り込む |
| 機能 | **ブラウザに必要なセキュリティ** | WebView 堅牢化・権限管理・プライベートモード等 |

> **設計変更（2026-06-29）**：当初「shouldInterceptRequest によるブラウザ内広告ブロック」を予定していたが、**軽量・シンプル優先**の原則に従い DNS フィルタリング方式に変更。AdGuard DNS（DoH）をプロバイダ選択肢に含めることで、追加実装ゼロで DNS レベルの広告ブロックを実現する。YouTube 広告など同一ドメイン配信の広告はブロック対象外となるが、v1 のスコープとしては許容する。

### 要件トレーサビリティ（要件 → 実現手段）

| 要件 | 実現手段（本書の節） |
|---|---|
| 軽量・高速 | ネイティブ Kotlin、System WebView、バックグラウンドタブのサスペンド、Coroutines（§2,§5.2,§8） |
| セキュア DNS（DoH） | ローカルプロキシ＋`ProxyController`＋OkHttp DoH、プロバイダ選択。MITM しない。フォールバックは端末のプライベート DNS（§5.3） |
| DNS フィルタリング | DoH プロバイダとして AdGuard DNS を選択（§5.3 と同一機構。追加コードなし） |
| ブックマークインポート | Netscape HTML を SAF ファイルピッカで取り込み（§5.4） |
| セキュリティ | WebView 堅牢化・Safe Browsing・権限既定拒否・プライベートモード・データ削除・no telemetry（§7） |
| シンプル | 中間者化なし・端末全体 VPN なし・拡張機構なし(v1)・最小依存（§2,§13） |

---

## 2. 技術選定

### 採用スタック（Android ネイティブ）

| 層 | 採用 | 理由 |
|---|---|---|
| 言語 | **Kotlin** | Android 標準。簡潔・安全・Coroutines で非同期が容易 |
| UI（ガワ） | **Jetpack Compose** | 宣言的で状態管理が単純。実装が速い。依存は最小限に保つ（重ければ View に置換可） |
| Web 描画 | **Android System WebView**（`android.webkit.WebView` ＋ `androidx.webkit`） | エンジン非同梱。`ProxyController`/Safe Browsing 等の必要 API に直結 |
| 名前解決(DoH) | **OkHttp + `okhttp-dnsoverhttps`** | `DnsOverHttps` で RFC 8484 の DoH 解決。ローカルプロキシ内で使用 |
| データ | **Room（SQLite）** ＋ 設定は **DataStore** | ブックマーク/履歴は Room、設定は DataStore。いずれも Jetpack 標準で軽量 |
| 非同期 | **Kotlin Coroutines** | プロキシ・DoH・DB を非ブロッキングで |

### 検討した代替案と不採用理由
- **Tauri 2 mobile / Flutter / React Native**：いずれも遮断処理・プロキシ等で結局ネイティブ（Kotlin）プラグインが必要になり、Android 単独では中間レイヤが増えるだけ。最優先1・2（軽量・シンプル）に反するため不採用。Android 専用なら**ネイティブが最軽量・最速・最単純**。

### バージョン依存（Claude Code は実装時に必ず確認）
- `ProxyController`（`WebViewFeature.PROXY_OVERRIDE`）と Safe Browsing は **端末の WebView バージョンに依存**。`WebViewFeature.isFeatureSupported(...)` で存在確認してから使う。未対応端末向けのフォールバック（§5.3）を用意する。
- `androidx.webkit` の最新安定版のドキュメントで API を確認すること。

---

## 3. アーキテクチャ全体像

```
┌──────────────────────────────────────────────────────────────┐
│ Hibari アプリ (Android / Kotlin プロセス)                     │
│                                                               │
│  ┌─────────────────────────┐    ┌──────────────────────────┐ │
│  │ Chrome UI (Compose)      │    │ アプリ内ロジック          │ │
│  │  - アドレスバー/タブ/設定  │◄──►│  - タブ/セッション管理     │ │
│  │  - ViewModel/State        │    │  - ブックマーク/履歴(Room) │ │
│  └─────────────────────────┘    │  - 設定(DataStore)        │ │
│              │ 表示               │  - フィルタリスト管理      │ │
│              ▼                    └────────────┬─────────────┘ │
│  ┌──── 描画する Web コンテンツ ────────────────┼───────────┐  │
│  │ タブ1 WebView   タブ2 WebView  …            │           │  │
│  │  ・JavaScriptInterface を Web に出さない      │           │  │
│  │  ・file:// アクセス無効 / Safe Browsing 有効  │           │  │
│  │  ・shouldInterceptRequest で広告遮断 ◄────────┘(フィルタ)  │  │
│  │        │ 全通信は ProxyController でローカルプロキシ経由    │  │
│  └────────┼───────────────────────────────────────────────┘  │
│           ▼                                                    │
│  ┌──────────────────────────────────────────────────────┐    │
│  │ ローカルプロキシ (127.0.0.1:<random>, Coroutines)      │    │
│  │  ・HTTP / HTTPS(CONNECT) を受ける                      │    │
│  │  ・ホスト名を OkHttp DoH で解決（=セキュア DNS）         │    │
│  │  ・解決 IP へトンネル（HTTPS は TLS 素通し/復号しない）   │    │
│  └──────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────┘
        │ DoH(暗号化)                 │ TCP/TLS
        ▼                             ▼
  選択した DoH プロバイダ        実サーバ（end-to-end TLS）
 (Cloudflare/Google/AdGuard/任意)
```

### 信頼境界（最重要のセキュリティ概念）
- **非信頼ゾーン＝タブで開く Web コンテンツ**。ここに対して `addJavascriptInterface`（JS ブリッジ）を**絶対に公開しない**（公開すると任意ページからアプリ内部に到達でき RCE につながりうる）。これは Android 版の最重要不変条件。
- 全 Web トラフィックはローカルプロキシ（127.0.0.1 のランダムポート、外部非公開）を通す。
- 広告遮断は WebView 層（`shouldInterceptRequest`）、セキュア DNS はネットワーク層（プロキシ）と、**層を分ける**。

---

## 4. コンポーネント一覧

| # | コンポーネント | 役割 |
|---|---|---|
| 5.1 | Chrome UI（Compose） | アドレスバー・タブバー・ナビ・設定 |
| 5.2 | タブ / WebView 管理 | WebView の生成・切替・サスペンド |
| 5.3 | セキュア DNS（DoH） | ローカルプロキシ＋ProxyController＋DoH（**プライバシー機能**） |
| 5.4 | 広告ブロック | shouldInterceptRequest＋フィルタリスト（**DNS と独立**） |
| 5.5 | ブックマーク | 保存・HTML インポート |
| 5.6 | 履歴 | Room、サジェスト |
| 5.7 | 設定 | DataStore |
| 5.8 | セキュリティ | WebView 堅牢化・権限・プライベート・データ削除 |

---

## 5. コンポーネント詳細

### 5.1 Chrome UI（ガワ・Compose）
- 最小構成：**アドレスバー / 戻る・進む・更新 / タブ一覧（＋追加・閉じる） / メニュー（設定・ブックマーク・履歴・プライベート新規タブ）**。
- 状態（タブ、アクティブタブ、URL、ローディング、ブックマーク）は ViewModel で保持。
- WebView は Compose の `AndroidView` でホストするか、Activity/Fragment で管理（タブごとの WebView ライフサイクル管理がしやすい方を選択）。
- 依存を増やさない。アイコンはベクター。アニメ最小限。

### 5.2 タブ / WebView 管理
- タブ＝WebView インスタンス。モバイルはメモリが限られるため、**少数のライブ WebView＋それ以外はサスペンド**。
- **サスペンド方式**：非アクティブなタブは状態（URL/スクロール/タイトル/ファビコン、可能なら `saveState`）を保存して WebView を破棄し、再選択時に再生成・復元。これでメモリを回収（§8）。
- すべての WebView を共通設定（堅牢化・プロキシ・WebViewClient）で生成する**ファクトリ**を用意し、設定漏れを防ぐ。

### 5.3 セキュア DNS（DoH） ★プライバシー機能（Chrome の「セキュア DNS」相当）

**目的**：ブラウザの名前解決を **DoH（DNS over HTTPS）** で暗号化し、プロバイダを選べるようにする。Android の WebView は標準ではアプリ内で DoH を指定できないため、ローカルプロキシで実現する。

**仕組み**
1. アプリ内に `127.0.0.1:<ランダム高ポート>` のローカルプロキシを起動（Coroutines）。localhost のみ・外部非公開。
2. `ProxyController.getInstance().setProxyOverride(...)` で**全 WebView をこのプロキシ経由**にする（`WebViewFeature.PROXY_OVERRIDE` をサポート確認。設定適用のコールバックを待ってから最初のナビゲーションを行う）。
3. プロキシは受けたホスト名（HTTP は Host、HTTPS は `CONNECT host:443`）を **OkHttp の `DnsOverHttps`** で解決（＝セキュア DNS）。
4. 解決した IP へ接続し、**HTTPS は CONNECT トンネルで TLS をそのまま素通し**（復号しない＝中間者化しない）。HTTP は転送。

**プロバイダ選択（Chrome 相当）**
- 設定でプリセット＋カスタムを選択可能に：Cloudflare（1.1.1.1）、Google（8.8.8.8）、AdGuard DNS、NextDNS、**任意の DoH テンプレート URL**。
- 解決結果は TTL を尊重してキャッシュ。

**中間者化しない理由**：HTTPS 復号にはルート証明書導入が必要で、侵襲的かつ危険。要件の「シンプル」「セキュリティ」に反するため**やらない**。プロキシは TLS を素通しするだけ。

**フォールバック（コード最小・任意）**
- `PROXY_OVERRIDE` 非対応端末、またはプロキシを避けたい場合：**Android のシステム「プライベート DNS」**（設定 → ネットワーク → プライベート DNS、DoT）を案内する。端末全体が暗号化 DNS になる。アプリ内のプロバイダ選択 UI は持てないが、ゼロに近いコストで「セキュア DNS」を満たせる。
- 実装方針：まずプロキシ方式を本命とし、非対応時はこの案内にフォールバック。

**受け入れ基準** → §14

### 5.4 DNS フィルタリング（広告ブロック）★設計変更

**方針変更（2026-06-29）**：`shouldInterceptRequest` によるブラウザ内フィルタリストは**実装しない**。
軽量・シンプル優先の原則に従い、DoH プロバイダとして **AdGuard DNS** を選択することで
DNS レベルの広告・トラッカブロックを実現する。追加コード・依存なし。

**仕組み**
- セキュア DNS（§5.3）で DoH プロバイダに AdGuard DNS（`https://dns.adguard-dns.com/dns-query`）を選択。
- AdGuard が管理するブロックリストに含まれる広告/トラッカドメインは DNS 解決が失敗し、リクエストが届かない。
- フィルタリストの更新は AdGuard 側が行うため、アプリ側のメンテナンスコストはゼロ。

**制約（許容済み）**
- YouTube 広告など、コンテンツと同一ドメインから配信される広告はブロック不可。
- DNS 解決が成功している CDN 経由の広告はパス単位でブロックできない。
- v1 スコープとして上記は許容する。

**受け入れ基準** → §14

### 5.5 ブックマーク（保存・インポート）

**保存**：Room（`bookmarks`：id, parentFolderId, title, url, position, createdAt）。フォルダ階層を表現。

**インポート（Android 固有の制約に注意）**
- Android はアプリ間がサンドボックス化されており、**他ブラウザ（Chrome 等）の内部データを直接読めない**。よって「他ブラウザのブックマーク DB を読む」方式は不可。
- 現実的な方式：**Netscape Bookmark HTML 形式のファイルを取り込む**。
  - ユーザは **SAF（`Intent.ACTION_OPEN_DOCUMENT`）** でファイルを選択。
  - `<DL><DT><A HREF>`／フォルダ `<H3>` をパースし、**フォルダ階層を保持**して Room に格納。重複 URL はスキップ/マージを設定可能に。
- HTML の入手方法（README/設定画面で案内）：デスクトップ Chrome 等で「ブックマークを HTML にエクスポート」→ ファイルを端末へ転送（モバイル版ブラウザは HTML エクスポート不可なことが多いため）。

### 5.6 履歴
- Room（`history`：id, url, title, visitedAt, visitCount）。
- アドレスバーのサジェスト（履歴・ブックマークの前方/部分一致）。
- **プライベートモード中は履歴を残さない**。
- 期間指定/全消去（§5.8 のデータ削除）。

### 5.7 設定（DataStore）
- 主な項目：
  - **セキュア DNS**：有効/無効、DoH プロバイダ（プリセット＋カスタム URL）、フォールバック挙動。
  - **セキュリティ**：HTTPS-Only、サードパーティ Cookie ブロック、権限既定値、Safe Browsing。
  - **一般**：既定検索エンジン、起動時の挙動、ダウンロード先。
- 変更は可能な限り即時反映（要再起動の項目は明示）。

### 5.8 セキュリティ
詳細は §7。WebView 生成ファクトリと権限ハンドラに集約。

---

## 6. データ設計
- **Room（SQLite）**：`bookmarks`, `history`（必要なら `tab_session` でセッション復元）。
- **DataStore**：設定（型安全・非同期）。
- **プライベートモード**：永続化しない一時セッション（履歴を書かない、終了時に Cookie/キャッシュ/ストレージを消す）。
- すべて**ユーザのローカルのみ**。クラウド同期・テレメトリは持たない（軽量・プライバシー）。

---

## 7. セキュリティモデル（「ブラウザに必要なセキュリティ」の具体・Android API）

| 分類 | 項目 | 方針（具体 API） |
|---|---|---|
| 分離 | **JS ブリッジを Web に出さない** | Web コンテンツに `addJavascriptInterface` を**使わない**（最重要）。必要時も信頼コンテンツ限定＋`@JavascriptInterface` 厳格運用 |
| 分離 | file:// アクセス制限 | `settings.allowFileAccess=false`、`allowFileAccessFromFileURLs=false`、`allowUniversalAccessFromFileURLs=false` |
| 防御 | **Safe Browsing 有効** | `WebSettingsCompat.setSafeBrowsingEnabled(true)`（または manifest の `EnableSafeBrowsing`）。マルウェア/フィッシング保護を無償で利用 |
| 通信 | 混在コンテンツ遮断 | `settings.mixedContentMode = MIXED_CONTENT_NEVER_ALLOW` |
| 通信 | **HTTPS-Only モード** | http→https へアップグレード、失敗時は警告。トグル可 |
| 通信 | **中間者化しない** | HTTPS は CONNECT トンネルで素通し。証明書検証を無効化しない（`onReceivedSslError` で素通し承認をしない） |
| 権限 | カメラ/マイク/位置/通知 | **既定で拒否**し都度プロンプト。`WebChromeClient.onPermissionRequest` / `onGeolocationPermissionsShowPrompt` で制御。アプリの実行時権限とも整合 |
| プライバシー | サードパーティ Cookie | `CookieManager.setAcceptThirdPartyCookies(webView, false)` を選択可能に |
| プライバシー | プライベートモード | 履歴を残さない。終了時に Cookie/キャッシュ/Web ストレージを消去（WebView の Cookie はプロセス共有のため完全分離は不可：本モードは「一時セッション＋終了時消去」と定義） |
| プライバシー | 閲覧データ削除 | `webView.clearCache(true)` / `CookieManager.removeAllCookies(...)` / `WebStorage.deleteAllData()` ＋ Room 履歴削除。期間指定/全消去 |
| プライバシー | テレメトリ | **持たない** |
| ダウンロード | 実行ファイル警告 | `setDownloadListener` で取得元/ファイル名を提示し確認。SAF/DownloadManager 経由で保存 |
| 更新 | アプリ更新 | Play ストア配布（または APK）。WebView エンジンは System WebView の更新で最新化（利点） |
| 入力 | URL 検証 | アドレスバー入力を検証・正規化。スキーム/IDN を確認 |
| 堅牢化 | 攻撃面削減 | `exported` を最小化、不要な intent-filter を作らない、デバッグ用 WebView contents debugging はリリースで無効 |

> 実装者向け不変条件（CLAUDE.md にも記載）：
> 1. Web コンテンツに JS ブリッジを出さない。2. TLS を中間者化しない／SSL エラーを握り潰さない。3. 権限は既定拒否。4. プロキシは 127.0.0.1 限定で外部非公開。5. Safe Browsing 有効・file:// 制限。6. テレメトリを足さない。

---

## 8. パフォーマンス設計（最優先1の具体策）
- **エンジン非同梱**（System WebView）で APK 小・メモリ小・起動速。
- **バックグラウンドタブのサスペンド**（WebView 破棄＋状態保存、再選択で復元）でメモリ回収。
- **遅延初期化**：起動を妨げない（フィルタリスト読込・プロキシ起動・DoH 接続はバックグラウンド/遅延）。
- Coroutines で I/O 非ブロッキング。
- フィルタ照合は HashSet で高速。
- ガワ（Compose）は最小限・依存を増やさない。重ければ View へ。
- v1 では拡張機構・重い内蔵機能を入れない。
- 目安（実測で検証）：APK は WebView 非同梱で小さく、空タブ起動が体感即時、アイドルメモリを抑制。

---

## 9. プロジェクト構成（提案）

```
hibari/
├─ CLAUDE.md
├─ README.md
├─ docs/DESIGN.md                 # 本書
├─ settings.gradle(.kts)
├─ build.gradle(.kts)             # ルート
└─ app/
   ├─ build.gradle(.kts)
   └─ src/main/
      ├─ AndroidManifest.xml
      └─ java(or kotlin)/com/example/hibari/
         ├─ MainActivity.kt
         ├─ ui/                   # Compose 画面（アドレスバー/タブ/設定 等）
         ├─ browser/
         │  ├─ WebViewFactory.kt  # 共通設定で WebView を生成（堅牢化/プロキシ/Client）
         │  ├─ TabManager.kt      # タブ/サスペンド管理
         │  └─ HibariWebViewClient.kt # shouldInterceptRequest 等
         ├─ net/
         │  ├─ LocalProxyServer.kt# ローカル HTTP/CONNECT プロキシ
         │  └─ DohResolver.kt     # OkHttp DnsOverHttps ラッパ
         ├─ adblock/
         │  ├─ FilterList.kt      # リスト取得/更新/キャッシュ
         │  └─ DomainMatcher.kt   # HashSet＋サブドメイン照合/allowlist
         ├─ bookmarks/
         │  ├─ BookmarkRepository.kt
         │  └─ NetscapeHtmlImporter.kt
         ├─ history/HistoryRepository.kt
         ├─ data/                 # Room(Entities/Dao/DB) ＋ DataStore(Settings)
         └─ security/             # 権限ハンドラ・ポリシー
```

---

## 10. 依存（最小限に保つ）
- AndroidX：`core-ktx`, `appcompat`(必要に応じ), `webkit`(ProxyController/SafeBrowsing), `lifecycle-viewmodel-compose`
- Compose：BOM ＋ 必要モジュール
- ネットワーク：`okhttp`, `okhttp-dnsoverhttps`
- データ：`room-runtime`/`room-ktx`(＋ksp)、`datastore-preferences`
- 非同期：`kotlinx-coroutines-android`

> これ以上は原則増やさない。依存追加は CLAUDE.md の「依存追加ルール」に従い、毎回「軽量・シンプル」を損なわないか検証する。

---

## 11. 重要な Android 固有の注意（正直な制約）
- **WebView バージョン依存**：`ProxyController`(PROXY_OVERRIDE) と Safe Browsing は端末の WebView バージョン依存。`WebViewFeature.isFeatureSupported(...)` で確認し、未対応時はフォールバック（§5.3）。
- **プロキシ適用範囲**：`setProxyOverride` はアプリ内全 WebView に適用され、非同期。適用コールバック後に最初のナビゲーションを行う。
- **`shouldInterceptRequest` の制約**：POST ボディ非取得。**遮断/素通しの判定専用**に使う。
- **incognito の限界**：WebView の Cookie/ストレージはプロセス共有のため、タブ単位の完全分離は不可。プライベートモードは「一時セッション＋終了時消去」と定義する（§7）。
- **ブックマーク**：サンドボックスにより他アプリの内部データは読めない。HTML ファイル取り込み方式にする（§5.5）。
- **minSdk**：`androidx.webkit` の対象機能と利用者層を考慮して決定（目安 API 26〜28 以上）。機能は SDK ではなく WebView バージョンに依存する点に注意。
- **Play 配布**：プライバシー（データ収集なし）・WebView 利用・権限の宣言を正しく行う。VPN は使わない方針なので審査は比較的素直。

> Claude Code への指示：**M0 で実機（または対象 WebView）で `ProxyController` が使えるかを PoC で先に確認**してから §5.3 を本実装する。

---

## 12. 実装ロードマップ（マイルストーン）
各 M の最後に §14 の該当受け入れ基準を満たすこと。

- **M0 — 足場固め & PoC**
  - Kotlin プロジェクト雛形、最小ガワ（アドレスバー＋戻る/進む/更新）で 1 ページ表示。WebView 基本堅牢化（JS ブリッジ無し・file:// 制限・Safe Browsing・混在遮断）。
  - **PoC：`ProxyController` で WebView をローカルプロキシ経由にできるか実機確認**（不可なら §5.3 フォールバック方針を確定）。
- **M1 — ブラウザ基本**
  - タブ（追加/閉じる/切替＋サスペンド）。履歴（Room）保存・サジェスト。ブックマーク保存・表示。
- **M2 — セキュア DNS（DoH）＋DNS フィルタリング**
  - ローカルプロキシ実装（HTTP＋HTTPS CONNECT）。OkHttp DoH 解決。プロバイダ選択 UI（AdGuard DNS 含む）。`ProxyController` 連携。非対応端末はプライベート DNS 案内へフォールバック。
  - ~~M3 — shouldInterceptRequest 広告ブロック~~ → **廃止**。AdGuard DNS による DNS フィルタリングで代替（§5.4）。
- **M3 — セキュリティ堅牢化**（旧 M4）
  - 権限既定拒否プロンプト、HTTPS-Only、プライベートモード、閲覧データ削除、サードパーティ Cookie 制御、Safe Browsing 設定の総点検。
- **M4 — 仕上げ & パフォーマンス**（旧 M5）
  - タブサスペンド最適化、起動最適化、ダウンロード確認、リリースビルド（debug 無効化等）。実測で軽量・高速を検証。

---

## 13. 既知の制約と将来拡張（v1 では入れない）
- 表示は端末 WebView 依存（§0）。
- HTTPS 内の URL 単位フィルタ・コスメティックフィルタは行わない（中間者化回避・シンプル優先）。
- 将来候補：EasyList 高度ルール/コスメティックフィルタ、ブックマーク/履歴同期、コンテナタブ、テーマ、PWA インストール、端末全体 DNS（VpnService）— いずれも「軽量・シンプル」を損なわない範囲で慎重に。

---

## 14. 受け入れ基準（各機能の「完了」）

**軽量・高速**
- エンジンを同梱していない（APK に Chromium を含まない）。
- 空タブ起動が体感即時。バックグラウンドタブがサスペンドされメモリが回収される。

**セキュア DNS（DoH）**
- 設定で DoH を有効化し、プロバイダ（プリセット＋カスタム URL）を選べる。
- 有効時、名前解決が選択した DoH 経由で行われる（暗号化される）。
- `PROXY_OVERRIDE` 非対応端末では、プライベート DNS 案内へ適切にフォールバックする。
- HTTPS は復号されない（TLS が end-to-end のまま）。

**DNS フィルタリング**
- DoH プロバイダとして AdGuard DNS を選択すると、広告・トラッカドメインの名前解決が失敗し接続が発生しない。
- VPN や端末全体設定を変更していない（アプリ内ローカルプロキシのみ）。

**ブックマークインポート**
- SAF で選んだ Netscape HTML を取り込め、フォルダ階層が保持される。
- 重複の扱い（スキップ/マージ）が設定どおり。

**セキュリティ**
- Web コンテンツに JS ブリッジ（`addJavascriptInterface`）を公開していない。
- file:// アクセスが制限され、Safe Browsing が有効、混在コンテンツが遮断される。
- HTTPS-Only 時に http が https へ上がり、失敗時に警告。SSL エラーを握り潰さない。
- カメラ/マイク/位置/通知が既定拒否でプロンプトされる。
- プライベートモードで履歴が残らず、終了時に Cookie/キャッシュ/ストレージが消える。閲覧データ削除が機能する。
- プロキシが 127.0.0.1 限定で外部から到達不能。テレメトリを送らない。

**シンプル**
- 依存が最小限で、各依存に「軽量・シンプルを損なわない」理由がある。
- 中間者化・端末全体 VPN など複雑/侵襲的な仕組みを採用していない。

---

### 付録：Claude Code への最初の依頼文（コピペ用の例）

> このリポジトリの `CLAUDE.md` と `docs/DESIGN.md` を読んでから着手して。対象は Android 専用・ネイティブ Kotlin（Jetpack Compose）。まず M0：プロジェクト雛形＋最小ガワ（アドレスバー＋戻る/進む/更新）で 1 ページ表示し、WebView を基本堅牢化（JS ブリッジ無し・file:// 制限・Safe Browsing・混在遮断）して。さらに「`ProxyController` で WebView をローカルプロキシ経由にできるか」を実機 PoC で確認して結果を報告して。WebView バージョン依存の機能は `WebViewFeature.isFeatureSupported` で確認してから使うこと。
