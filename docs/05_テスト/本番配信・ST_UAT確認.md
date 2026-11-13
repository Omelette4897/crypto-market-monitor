# 本番配信・ST/UAT確認

更新：2026-11-13。クラウド未構築・未公開。以下は実施前の観点であり成功記録ではない。個別自動テスト仕様はソースを正本とする。

## 自動試験の境界

| 試験 | 実体・確認範囲 | 保証しないこと |
|---|---|---|
| 通常JUnit | UT/模擬HTTP/MVC/起動終了制御 | 実Coinbase・本番環境 |
| mysql-it | Testcontainers MySQL、保存/検索/回復/DB存続 | Railway設定 |
| Frontend | Vitest、型チェック、本番ビルド | 実ブラウザ |
| web-bundle | 実Vite成果物、prod MVCのHTML/JS/CSS・JSON・health共存 | 実DB・実HTTP・Docker実行 |
| Docker build | Linuxで各段階・JREイメージ構築 | 本番DB・HTTPS・SIGTERM |

ルートで`docker build -t crypto-market-monitor:production-check .`。既存DBは変更しない。内部のJUnit/web-bundle失敗はイメージビルド失敗となる。Docker接続不能なら未実施としCIまたは利用可能PCで確認する。

2026-11-13ローカル結果：通常JUnit211件、同梱配信5件、Frontend133件と型チェック/ビルド、JAR内static確認、actionlint成功。ユーザー実行後の更新XMLで実MySQL54件・失敗/エラー/スキップ0を確認。Dockerは最初Vueで失敗（詳細未取得）。生成物を除外しCOPYを限定した後、Linux内Vueビルド成功をユーザーログで確認。その後Maven配布物SHA-256検証で失敗。Wrapper 3.3.4がunzip不在時にtar.gzへ切り替える既知の問題と実ソースを確認し、builderにだけunzipを追加した。ZIPチェックサムは維持。追加後のイメージ構築は未確認。本番HTTP・コンテナ実行・終了猶予は下表で検証する。

根拠：[Apache Maven Wrapper issue #425](https://github.com/apache/maven-wrapper/issues/425)。Linuxビルド環境の前提を整える変更であり、Mavenの版・検証値・業務処理は変更しない。

修正後、ユーザーからDocker内Mavenの`BUILD SUCCESS`報告あり。さらに`docker image inspect`で最終タグ`crypto-market-monitor:production-check`、Image ID `sha256:3aeaa765c67357e40a297b54759b56a4b988351369f13af88fec4e5b0f548e3c`、実行ユーザー`10001:10001`を確認。**最終runtimeイメージの構築完了**。実コンテナ起動・本番接続・変更後GitHub CIは未確認。Codex側のDockerアクセス拒否は継続している。

## 今回の変更ファイル

| ファイル | 役割 |
|---|---|
| `Dockerfile` / `.dockerignore` | 3段階ビルド・非root実行・秘密/生成物のコンテキスト除外 |
| `backend/pom.xml` | 任意のweb-bundle profile、配信結合試験の起動 |
| `backend/src/main/resources/application-prod.yml` | 必須DB変数・PORT・外部待受け・正常終了・health範囲 |
| `backend/src/test/java/com/example/cryptomonitor/PackagedWebApplicationIT.java` | 実Vue成果物・既存API共存・管理API・prod待受けの5観点 |
| `.github/workflows/ci.yml` | Dockerイメージ構築確認。CD・pushは追加しない |
| `README.md` / `docs/資料一覧.md` / `docs/05_テスト/CIの実行と確認.md` | 採用構成・検証状態・新手順への入口 |
| `docs/06_リリース・運用/公開構成・デプロイ設計.md` | Railway採用構成・更新/終了順序・費用制御 |
| `docs/06_リリース・運用/Railwayデプロイ・運用手順.md` | ユーザーが行う構築・更新・利用終了の操作 |
| このファイル | 自動試験の境界・実施結果・ST/UAT観点 |

公開リポジトリ外の内部用「開発進行ガイド」も現在地を更新。Java業務コード・API契約・DB/Flyway・Vue表示処理・開発用Composeは変更していない。

Dockerなしで同梱試験を再実行する場合、Vueをビルドしdistを`backend/target/frontend-dist`へコピーしてから、backendで`./mvnw -Pweb-bundle verify`を実行する。`clean`はこの入力も消すため、その場合はVue成果物を配置し直す。通常のJava開発だけへ戻す際は`./mvnw clean verify`で前回同梱物の残存を除去できる。

## 本番ST・UAT

| ID | 操作・期待結果 | 結果 |
|---|---|---|
| PROD-01 | HTTPS画面、JS/CSSと3 APIが200、HTTP混在なし | 未実施 |
| PROD-02 | 新規DBへV1/V2、再起動で再適用なし、MySQL8.4.13/専用ユーザー | 未実施 |
| PROD-03 | 回復commit後初回収集、終了から約60秒後に次回、表示更新は外部収集を増やさない | 未実施 |
| PROD-04 | 最新価格/24hチャート/全観測統計/UTC-JST/候補市場の未収集案内 | 未実施 |
| PROD-05 | 30秒更新、履歴展開/ページ送り、PC/スマートフォン | 未実施 |
| PROD-06 | 部分通信失敗でも正常データ保持・エラー明示、3分経過警告 | 未実施 |
| PROD-07 | health詳細なし/env404、DB非公開、HTML/JS/ログに秘密なし | 未実施 |
| PROD-08 | SIGTERMで収集待機・DB存続、停止完了後だけ新App起動、新旧重複なし | 未実施 |
| PROD-09 | App再デプロイ/MySQL再起動後もVolumeの履歴維持 | 未実施 |
| PROD-10 | 公開前確認時間帯のDB停止で起動/health異常、復旧後正常。空配列に偽装しない | 未実施 |
| PROD-11 | 通知15/Hard25、1 replica、sleep/autodeploy/PR環境OFF、リソース/Usage確認 | 未実施 |
| PROD-12 | バックアップと隔離復元、停止/削除/解約方法確認 | 未実施 |
| UAT-01 | 最新価格・推移・スプレッド・観測日時を利用者が把握できる | 未実施 |
| UAT-02 | 公開URL/対象コミット/制限をユーザーが確認して受入れ | 未実施 |

実施時は対象コミット・環境・日時・結果・問題を記録。DBを削除/初期化して障害試験をしない。費用上限を実際に消費して検証しない。未確認は未確認のまま明示する。
