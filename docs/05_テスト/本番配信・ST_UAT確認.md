# 本番配信・ST/UAT確認

更新：2026-11-14。Railwayへ初回公開済み。本番ST/UATは一部実施済みであり、全件完了ではない。個別自動テスト仕様はソースを正本とする。

## 現在の公開環境・実施記録

- 公開URL：https://app-production-d13c7e57.up.railway.app/
- 環境：Railway production、App（Vue同梱Spring Boot）＋MySQL 8.4.13＋永続Volume。
- mainの準備済みコミット：`612cac3dab3f20b07a467f4ad64c0b1bc8e0ee5b`。PR #2とmain CI成功を確認済み。本番DeploymentのソースSHAとの照合は未実施。画像で確認したDeployment IDは`e4a5a33b`。
- 2026-11-14のユーザー提示ログ：App/MySQL Online、Tomcat 8080、static/index.html、起動時回復0件、runId 1〜3のACCEPTED・保存commit・約60秒間隔の継続を確認。
- 2026-11-14 20:08〜20:09 JST、公開URLへ読み取り専用HTTP確認を実施。HTML・参照JS/CSS・最新価格・収集履歴・24時間履歴・healthが200。HTMLの日本語タイトルとContent-Typeを確認。実ブラウザの描画確認とは区別する。
- healthは`{"status":"UP"}`のみ。`/actuator/env`は404。`collection-runs?limit=0`は400・`INVALID_PARAMETER`。DB障害試験や秘密情報の網羅的な検査を行ったという意味ではない。
- 最新観測はid 62（11:08:21.575482Z）から63（11:09:21.712492Z）へ進み、取得済み収集履歴63件はすべてSUCCESS。継続収集を別時点の応答でも確認した。長時間安定稼働の保証ではない。
- 同一の履歴API応答63件・truncated=falseで、観測日時昇順、各Spread=Ask−Bid、最小/最大を再計算して一致。平均の差は約4.8×10^-18でAPIの小数16桁丸めの範囲。現時点の保存データに対する確認であり、24時間分の蓄積完了ではない。

次は実画面のST/UAT→本番DeploymentのSHA照合・設定/利用量確認→計画停止・再起動/永続化/復旧の確認→README・運用資料の確定を行う。DB停止・再デプロイ・復元試験は操作順を確認してから実施し、現時点では未実施とする。

2026-11-14ユーザーの公開画面確認：初期表示、Mid/Spreadチャート切替、候補市場の未対応案内と実市場への復帰、履歴展開/詳細/ページ送り、自動更新と操作状態維持の①〜⑤は問題なし。スマートフォンも操作可能。市場一覧2行化・Bid/Ask横並び・統計の項目/値横並びをローカル修正し、Frontend133件・型チェック/ビルド成功。ユーザーが変更後のスマートフォン画面を一旦受入れ。本番反映と反映後の再確認は未実施。

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
| PROD-01 | HTTPS画面、JS/CSSと3 APIが200、HTTP混在なし | HTTP応答成功。ブラウザ描画・混在通信確認は未実施 |
| PROD-02 | 新規DBへV1/V2、再起動で再適用なし、MySQL8.4.13/専用ユーザー | 未実施 |
| PROD-03 | 回復commit後初回収集、終了から約60秒後に次回、表示更新は外部収集を増やさない | 起動ログと観測更新を確認。画面更新との分離の本番操作確認は未実施 |
| PROD-04 | 最新価格/24hチャート/全観測統計/UTC-JST/候補市場の未収集案内 | 公開画面・候補切替をユーザー確認、API統計再計算成功。UTC-JSTの厳密照合は未実施 |
| PROD-05 | 30秒更新、履歴展開/ページ送り、PC/スマートフォン | 公開版の操作・自動更新をユーザー確認。モバイル調整はローカル受入れ済み、本番反映待ち |
| PROD-06 | 部分通信失敗でも正常データ保持・エラー明示、3分経過警告 | 未実施 |
| PROD-07 | health詳細なし/env404、DB非公開、HTML/JS/ログに秘密なし | health詳細なし/env404を確認。DB公開設定と秘密情報の網羅点検は未実施 |
| PROD-08 | SIGTERMで収集待機・DB存続、停止完了後だけ新App起動、新旧重複なし | 未実施 |
| PROD-09 | App再デプロイ/MySQL再起動後もVolumeの履歴維持 | 未実施 |
| PROD-10 | 公開前確認時間帯のDB停止で起動/health異常、復旧後正常。空配列に偽装しない | 未実施 |
| PROD-11 | 通知15/Hard25、1 replica、sleep/autodeploy/PR環境OFF、リソース/Usage確認 | 未実施 |
| PROD-12 | バックアップと隔離復元、停止/削除/解約方法確認 | 未実施 |
| UAT-01 | 最新価格・推移・スプレッド・観測日時を利用者が把握できる | 基本画面のユーザー確認済み。モバイル調整はローカル受入れ、本番反映待ち |
| UAT-02 | 公開URL/対象コミット/制限をユーザーが確認して受入れ | 未実施 |

実施時は対象コミット・環境・日時・結果・問題を記録。DBを削除/初期化して障害試験をしない。費用上限を実際に消費して検証しない。未確認は未確認のまま明示する。
