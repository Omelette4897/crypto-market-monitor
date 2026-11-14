# Crypto Market Monitor

取引所・通貨ペアごとの公開市場データを収集し、価格とBid/Askスプレッドの推移・統計を確認するWebアプリケーション。現在の収集対象はCoinbase Exchange / BTC-USDの1市場。実際の売買・資金移動は行いません。

[公開デモ：暗号資産 市場モニター](https://app-production-d13c7e57.up.railway.app/)

保存済みデータを表示します。収集停止中は観測が古くなり、直近24時間に観測がない場合はチャートが空になります。

## 現在の実装範囲

2026-11-12：価格取得・保存・参照API・定期収集とVue業務画面を実装しました。画面構成は受入れ確認済みです。

- Spring Boot 3.5.16 / Java 21 / Maven Wrapper。
- Vue 3 / TypeScript / Tailwind CSS / Viteによる最新保存価格・24時間推移・スプレッド分析・収集状況のダッシュボード。
- MySQLのCompose設定、ローカル資格情報の生成、JPA/Flywayの接続設定。
- `/actuator/health`による接続確認。詳細情報や環境情報は公開しません。
- Coinbase BTC-USDのBid/Ask・数量の検証、共通形式への変換、実行履歴・価格観測の保存。
- 最新価格・収集実行履歴と、24時間の観測/スプレッド統計を返す3 GET API。
- 起動時の中断回収、初回即時・処理終了から60秒後の定期収集、正常終了時の最大30秒待機。

2026-11-14にRailwayへ初回公開し、HTTPS配信・3参照API・実Coinbaseの定期収集/DB保存、PCの基本操作を確認しました。モバイル調整はローカルで受入れ済み、本番反映は準備中です。再起動・障害復旧・バックアップ復元などの運用確認は継続中です。[確認結果と未実施事項](docs/05_テスト/本番配信・ST_UAT確認.md)を参照してください。画面から外部収集は開始せず、保存済みデータを参照します。

## 起動

Windows PowerShellで操作します。JDK 21、対応するNode.js（今回の検証は24.18.0）、起動済みのDocker Desktopが必要です。Maven・MySQLの単体インストールは不要です。

1. このフォルダで `node scripts/init-local-env.mjs` を実行。
2. `docker compose up -d --wait` でMySQLを起動。
3. 別のPowerShellで `cd backend` → `.\mvnw.cmd spring-boot:run`。
4. もう1つのPowerShellで `cd frontend` → `npm.cmd ci` → `npm.cmd run dev`。
5. ブラウザで <http://127.0.0.1:5173/> を開く。表示を再取得する場合は「表示更新」を押す。

最新保存価格と収集実行履歴を、ブラウザ→Vite→Spring Boot→MySQLの経路で取得します。詳細は[業務画面の動作確認](docs/05_テスト/業務画面の動作確認.md)を参照してください。初回は依存やMySQLイメージのダウンロードに時間がかかります。

バックエンドを通常起動すると実Coinbaseから自動収集し、DBへ保存します。`COLLECTION_ENABLED=false`で自動収集を無効化できますが、残存RUNNINGの起動時回収は実行します。同じDBに対するバックエンドは1プロセスにしてください。APIと定期収集の確認方法は[定期実行の動作確認](docs/05_テスト/定期実行の動作確認.md)を参照してください。

詳しいコマンド、停止方法、よくあるエラーは[ローカル起動手順](docs/04_開発環境/ローカル起動手順.md)を参照してください。

## 構成

```text
ブラウザ → Vite :5173 → Spring Boot :8080 → MySQL :3306
                         ├ 管理・参照API
                         └ 定期収集 → Coinbase公開API
```

- `frontend/`：ブラウザ側の画面と開発サーバー設定。
- `backend/`：Javaアプリ、依存管理、DB接続設定、起動テスト。
- `compose.yaml`：開発用MySQLとデータvolume。
- `.env`：生成されたローカル資格情報。Git管理対象外。
- `docs/`：要件、基本設計、API、公開計画、起動手順。

## ビルドとテスト

バックエンド：backendで `.\mvnw.cmd verify`。
フロント：frontendで `npm.cmd ci`、`npm.cmd test`、`npm.cmd run build`。ビルドは型チェックを含みます。

通常テストではUT・模擬HTTP・MockMvc・Spring起動/終了制御を確認します。DB結合テストはDocker Desktopを起動し、backendで`.\mvnw.cmd -Pmysql-it verify`を実行します。Testcontainersの専用MySQLを使い、開発DBは使いません。これらの自動試験は実Coinbaseへ通信しません。実接続試験は別途明示実行します。

## CI

PRとmain更新時にBackend（通常・実MySQLテストとJAR作成、Vue同梱試験を含む本番Dockerイメージ構築）とFrontend（テスト・型チェック・ビルド）を検証します。PR #2とmain取り込み後のCI成功を確認済みです。デプロイやイメージ送信は行いません。[CIの実行と確認](docs/05_テスト/CIの実行と確認.md)を参照してください。

## 方針と設計

最新価格・収集実行履歴・24時間履歴分析の3 APIを提供します。Mid/Spreadチャートと期間内全観測のスプレッド平均/最大/最小を表示し、30秒ごとに保存済みデータを再取得します。Chart.jsとdecimal.jsを利用し、実売買は行いません。
本番はRailway HobbyのApp＋永続MySQLを採用しています。DockerでVueをJARへ同梱し、Spring Bootから画面とAPIを配信します。初回公開済み、本番ST/UAT・運用確認は継続中です。[Railway操作手順](docs/06_リリース・運用/Railwayデプロイ・運用手順.md)に設定・単一起動を守る手動更新・停止/解約をまとめています。Bootのサポート版と依存関係の継続的な再評価は別の保守タスクとして扱います。

- [要件](docs/01_要件定義/要件定義.md)
- [基本設計](docs/02_基本設計/基本設計.md)
- [業務APIの契約](docs/02_基本設計/API基本設計.md)
- [公開計画](docs/06_リリース・運用/公開構成・デプロイ設計.md)
- [現行資料の一覧](docs/資料一覧.md)

## 開発構成を確認する

- [現行資料の一覧](docs/資料一覧.md)：要件・設計・実行手順の入口。
- [開発環境とIDEの操作](docs/04_開発環境/開発環境とIDEの操作.md)：IntelliJでJava、VS CodeでVueを扱う現行手順。
