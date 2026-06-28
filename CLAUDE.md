# CLAUDE.md — Hibari (軽量 Android ブラウザ) 開発ガイド

このファイルは実装中ずっと従う規約。詳細仕様は `docs/DESIGN.md` を参照。
**迷ったら本ファイルの「絶対原則」に従う。**

---

## プロジェクト概要
Android System WebView を薄くラップした**軽量ブラウザシェル**。**Android 専用 / ネイティブ Kotlin（Jetpack Compose）**。
**ブラウザエンジンは自作しない**（描画は System WebView に任せる）。

主要機能（**2 つは別物**として実装）：
- **セキュア DNS（DoH）**＝ Chrome の「セキュア DNS を使用する」相当。プロバイダ選択可（プライバシー機能）。
- **広告ブロック**＝ DNS とは独立。ブラウザ内のリクエスト遮断で実現。
- ブックマークの HTML インポート / 基本的なセキュリティ。

---

## 絶対原則（Non‑negotiables / これを破る変更は却下）
1. **軽量・高速が最優先。** 起動が速く、メモリが小さく、APK が小さいこと。
2. **シンプルを最優先。** 機能を盛らない。複雑/侵襲的な仕組み（TLS 中間者化・端末全体 VPN）を避ける。
3. **エンジンを同梱しない。** System WebView を使う。
4. **Web コンテンツに JS ブリッジを出さない。** Web ページに `addJavascriptInterface` を公開しない。
5. **TLS を中間者化しない／SSL エラーを握り潰さない。** DoH は名前解決のみ、HTTPS は素通し。
6. **広告ブロックとセキュア DNS は別機構。** 前者は `shouldInterceptRequest`、後者はローカルプロキシ＋DoH。
7. **テレメトリを足さない。** データはローカルのみ。
8. **新規依存・新機能は、1・2 を損なわないと示せた場合のみ追加する。**

> 判断に迷ったら：「これは軽いか？ 単純か？ 安全か？」の順で自問する。

---

## 技術スタック（勝手に変えない）
- 言語：**Kotlin** / 非同期：**Coroutines**
- UI：**Jetpack Compose**（最小限。重ければ View へ）
- Web：**Android System WebView**（`android.webkit` ＋ `androidx.webkit`）
- DoH：**OkHttp ＋ `okhttp-dnsoverhttps`**
- データ：**Room**（ブックマーク/履歴）＋ **DataStore**（設定）

> `ProxyController`(PROXY_OVERRIDE)・Safe Browsing は**端末の WebView バージョン依存**。`WebViewFeature.isFeatureSupported(...)` で必ず確認してから使う（`docs/DESIGN.md` §2,§5.3,§11）。

---

## アーキテクチャ要点
- **非信頼ゾーン＝タブの Web コンテンツ**。JS ブリッジを出さない。`file://` アクセス無効。
- 全 Web トラフィックは **ローカルプロキシ（127.0.0.1: ランダムポート, 外部非公開）** 経由（`ProxyController`）。プロキシは **OkHttp DoH で解決**し、HTTPS は **CONNECT トンネルで素通し**。
- 広告遮断は **WebView 層（`shouldInterceptRequest`）**、セキュア DNS は **ネットワーク層（プロキシ）** と層を分ける。
- タブ＝WebView。バックグラウンドはサスペンド（破棄＋状態保存、再選択で復元）。
- WebView は必ず**共通ファクトリ**（`WebViewFactory`）で生成し、堅牢化/プロキシ/Client 設定の漏れを防ぐ。

詳細図は `docs/DESIGN.md` §3。

---

## ディレクトリ構成
`docs/DESIGN.md` §9 を参照。要点：`ui/`（Compose）, `browser/`（WebViewFactory/TabManager/WebViewClient）, `net/`（LocalProxyServer/DohResolver）, `adblock/`（FilterList/DomainMatcher）, `bookmarks/`, `history/`, `data/`（Room/DataStore）, `security/`。

---

## 開発コマンド
```bash
./gradlew assembleDebug          # デバッグビルド
./gradlew installDebug           # 実機/エミュにインストール
./gradlew assembleRelease        # リリースビルド
./gradlew test                   # 単体テスト(JVM)
./gradlew connectedAndroidTest   # 計装テスト(実機/エミュ)
./gradlew lint                   # Android Lint
```
**コミット前に必須**：`./gradlew test` と `./gradlew lint` が通ること。フォーマットは Android Studio 既定／ktlint を推奨。

---

## コーディング規約
**Kotlin**
- Kotlin 公式スタイル準拠。`!!`（強制アンラップ）を避ける。例外はラップして文脈を付ける。
- I/O・ネットワーク・DB は Coroutines（`Dispatchers.IO`）で。UI スレッドをブロックしない。
- イミュータブル優先（`val`、data class）。関数/クラスは小さく単一責務。
- 公開 API・データ形式を変えたら `docs/DESIGN.md` を更新。

**WebView 周り（重要）**
- WebView は `WebViewFactory` 経由でのみ生成。直接 `WebView(context)` を散在させない。
- `addJavascriptInterface` を Web コンテンツに使わない。
- `onReceivedSslError` で `proceed()` しない（SSL エラーを無視しない）。
- `shouldInterceptRequest` は遮断/素通し判定専用（POST ボディ非取得を前提）。

**Compose**
- 状態は ViewModel に集約。副作用は適切な effect API で。
- 不要な再コンポーズを避ける（安定キー・状態の分割）。

---

## セキュリティ規約（最重要・必読）
実装時に必ず守る不変条件：
1. **Web コンテンツに JS ブリッジを出さない**（`addJavascriptInterface`）。新規 WebView でも確認。
2. **TLS を中間者化しない／SSL エラーを握り潰さない**。HTTPS は CONNECT トンネルで素通し。
3. **`file://` アクセスを無効**（`allowFileAccess=false` ほか）。
4. **Safe Browsing 有効**・**混在コンテンツ遮断**（`MIXED_CONTENT_NEVER_ALLOW`）。
5. **権限（カメラ/マイク/位置/通知）は既定拒否**＋都度プロンプト。
6. **プロキシは 127.0.0.1 限定**・外部非公開。
7. **プライベートモードで履歴を残さず、終了時に Cookie/キャッシュ/ストレージを消去**。
8. **テレメトリ/外部送信を足さない**。リリースで WebView デバッグを無効化。
9. セキュリティ挙動を変える変更は、`docs/DESIGN.md` §7/§14 のどの基準を満たすか明記。

---

## パフォーマンス規約
- エンジンを同梱しない。重い依存を足さない。
- 起動を妨げない（フィルタ読込・プロキシ起動・DoH 接続はバックグラウンド/遅延）。
- バックグラウンドタブはサスペンド（破棄＋状態保存）でメモリ回収。
- フィルタ照合は HashSet で高速に。ホットパスで不要なアロケーションを避ける。

---

## やること / やらないこと
**やる**
- System WebView を薄くラップ。
- セキュア DNS（DoH, プロバイダ選択）をプロキシ＋OkHttp DoH で。非対応端末はプライベート DNS 案内へフォールバック。
- 広告ブロックを `shouldInterceptRequest`＋ドメインリストで。
- ブックマークは Netscape HTML を SAF で取り込み。
- 機能ごとに `docs/DESIGN.md` §14 の受け入れ基準を満たす。

**やらない（v1）**
- 独自レンダリングエンジン／エンジン同梱。
- TLS 中間者化・ルート証明書導入。
- 端末全体 VPN（VpnService）・端末全体 DNS 変更（アプリ内のみ）。
- HTTPS 内 URL 単位フィルタ・コスメティックフィルタ。
- 拡張機構・クラウド同期・テレメトリ。
- 「念のため」の追加機能。要件外は入れない。

---

## 機能追加のワークフロー
1. `docs/DESIGN.md` の該当節と §14 を確認。
2. **絶対原則 1〜8 に反しないか**を先に確認（特に依存追加時）。
3. 最小実装 → `./gradlew test` / `./gradlew lint` を通す。
4. セキュリティ不変条件（上記）を確認。
5. 仕様/データ形式を変えたら `docs/DESIGN.md` を更新。
6. 受け入れ基準を満たしたことを報告に明記。

---

## 依存追加ルール
新しいライブラリを足す前に、次を満たすこと：
- 標準ライブラリ/既存依存で代替できないか確認した。
- APK サイズ/メモリ/ビルド時間への影響が小さい。
- メンテされており、ライセンスが許容範囲。
- **「軽量・シンプルを損なわない理由」を一文で説明できる**（コミット/報告に記載）。
満たさなければ追加しない。

---

## テスト方針
- 広告フィルタ判定（ブロック/許可/サブドメイン/allowlist）に単体テスト。
- ブックマーク HTML インポータに、実際のエクスポート例でテスト。
- DoH 解決はモック可能に設計。
- セキュリティ不変条件（JS ブリッジ非公開・プロキシ非公開・SSL エラー非無視）を確認するテスト/チェックを用意。

---

## 完了の定義（Definition of Done）
- [ ] `docs/DESIGN.md` §14 の該当受け入れ基準を満たす。
- [ ] `./gradlew test` / `./gradlew lint` が通る。
- [ ] セキュリティ不変条件（本ファイル）に違反しない。
- [ ] 絶対原則 1〜8（軽量・シンプル・非同梱・JS 分離・非MITM・機構分離・無テレメトリ・依存規律）を損なわない。
- [ ] 仕様/データ形式を変えた場合 `docs/DESIGN.md` を更新済み。

---

## 着手手順（最初にやること）
1. 本ファイルと `docs/DESIGN.md` を通読。
2. **M0**：Kotlin 雛形＋最小ガワで 1 ページ表示。WebView 基本堅牢化（JS ブリッジ無し・file:// 制限・Safe Browsing・混在遮断）。
3. **PoC**：`ProxyController` で WebView をローカルプロキシ経由にできるか実機確認し報告（`WebViewFeature.isFeatureSupported(PROXY_OVERRIDE)` を確認）。
4. 以降は DESIGN §12 のロードマップ（M1→M5）に従う。
