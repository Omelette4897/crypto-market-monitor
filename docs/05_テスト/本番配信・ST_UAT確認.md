# 本番配信・ST/UAT確認

更新：2026-11-14。Railwayへ初回公開済み。本番ST/UATは一部実施済みであり、全件完了ではない。個別自動テスト仕様はソースを正本とする。

## 現在の公開環境・実施記録

- 公開URL：https://app-production-d13c7e57.up.railway.app/
- 環境：Railway production、App（Vue同梱Spring Boot）＋MySQL 8.4.13＋永続Volume。
- main：`24669d6b6bbe1d333c26227a8579bbc3862ed86e`（PR #3）。[CI成功](CIの実行と確認.md)を確認。本番の表示ラベルと配信資産はPR #3反映を確認済み。本番Deploymentの完全なソースSHAとの厳密照合は未実施。
- 2026-11-14のユーザー提示ログ：App/MySQL Online、Tomcat 8080、static/index.html、起動時回復0件、runId 1〜3のACCEPTED・保存commit・約60秒間隔の継続を確認。
- 2026-11-14 20:08〜20:09 JST、公開URLへ読み取り専用HTTP確認を実施。HTML・参照JS/CSS・最新価格・収集履歴・24時間履歴・healthが200。HTMLの日本語タイトルとContent-Typeを確認。実ブラウザの描画確認とは区別する。
- healthは`{"status":"UP"}`のみ。`/actuator/env`は404。`collection-runs?limit=0`は400・`INVALID_PARAMETER`。DB障害試験や秘密情報の網羅的な検査を行ったという意味ではない。
- 最新観測はid 62（11:08:21.575482Z）から63（11:09:21.712492Z）へ進み、取得済み収集履歴63件はすべてSUCCESS。継続収集を別時点の応答でも確認した。長時間安定稼働の保証ではない。
- 同一の履歴API応答63件・truncated=falseで、観測日時昇順、各Spread=Ask−Bid、最小/最大を再計算して一致。平均の差は約4.8×10^-18でAPIの小数16桁丸めの範囲。現時点の保存データに対する確認であり、24時間分の蓄積完了ではない。

残る確認は下表の未実施項目。計画停止/再開と永続化は後述の実績を参照し、バックアップ復元は対象外とする。

2026-11-14ユーザーの公開画面確認：初期表示、Mid/Spreadチャート切替、候補市場の未対応案内と実市場への復帰、履歴展開/詳細/ページ送り、自動更新と操作状態維持の①〜⑤は問題なし。スマートフォンも操作可能。市場一覧2行化・Bid/Ask横並び・統計の項目/値横並びをローカル修正し、Frontend133件・型チェック/ビルド成功。ユーザーが変更後のスマートフォン画面を一旦受入れ。その後PR #3として本番反映し、変更後のモバイル表示もユーザー確認済み。

## 本番更新・計画停止からの再開（2026-11-14）

- PR #3/main `24669d6`のCI成功後、ユーザーが旧App DeploymentをRemoveし、新版を起動。旧版REMOVEDと新版ACTIVEを画像で確認。公開CSSでモバイル用2行grid・Bid/Ask用クラスを確認し、ユーザーも本番表示を受入れ。
- App更新後の20:56 JST確認でhealth UP、観測履歴105件、更新前のid1・101を保持し、新規id105がSUCCESS。MySQLはこの更新中は稼働したまま。
- 別途、21:18 JSTにhealth UP・履歴127件・id1〜127を記録。ユーザーがApp停止完了→MySQL停止→同じVolumeのMySQL起動/ready確認→App起動の順に操作した。
- App起動中の公開URLは404。起動完了後の21:26 JSTに画面/health/3 APIの200を確認し、healthはUP。履歴131件に停止前のid1・101・127が残っていた。
- 再開後のid131（21:25:25.570443 JST）からid132（21:26:25.739517 JST）へ更新。収集履歴は両方SUCCESSで、前回終了21:25:25.644423から次回開始21:26:25.691097まで約60秒だった。直前の旧観測id130は21:20:53で、停止中の欠測は補間していない。
- 以上により手動停止/再開、MySQL再起動後の永続化、画面/API復旧、定期収集継続を確認。実行中収集に対するSIGTERM待機順序、強制終了時回復、バックアップ復元をこの確認で保証したとは扱わない。

2026-11-14 21:43 JSTの追加読み取り確認でも、公開HTML・health UP・3参照APIの200を確認。これはHTTP疎通であり、ブラウザの新たな描画試験ではない。

## 自動試験の境界

| 試験 | 実体・確認範囲 | 保証しないこと |
|---|---|---|
| 通常JUnit | UT/模擬HTTP/MVC/起動終了制御 | 実Coinbase・本番環境 |
| mysql-it | Testcontainers MySQL、保存/検索/回復/DB存続 | Railway設定 |
| Frontend | Vitest、型チェック、本番ビルド | 実ブラウザ |
| web-bundle | 実Vite成果物、prod MVCのHTML/JS/CSS・JSON・health共存 | 実DB・実HTTP・Docker実行 |
| Docker build | Linuxで各段階・JREイメージ構築 | 本番DB・HTTPS・SIGTERM |

ルートで`docker build -t crypto-market-monitor:production-check .`。既存DBは変更しない。内部のJUnit/web-bundle失敗はイメージビルド失敗となる。Docker接続不能なら未実施としCIまたは利用可能PCで確認する。

本番イメージの自動検証結果は[CI記録](CIの実行と確認.md)に集約する。ローカルでも最終runtimeイメージの構築と実行ユーザー`10001:10001`を確認した。Docker build成功だけで本番DB接続や終了猶予を保証しない。

構築時にはMaven Wrapperがunzip不在で配布形式を切り替える問題があり、builderにだけunzipを追加した。配布物のSHA-256検証は維持した。[Apache Maven Wrapper issue #425](https://github.com/apache/maven-wrapper/issues/425)。これはビルド環境の対応で、業務処理やDB契約の変更ではない。

## 本番配信を担う主なファイル

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

この表は配信構成の参照用。現在のJava業務処理・DB契約とは役割を区別する。

Dockerなしで同梱試験を再実行する場合、Vueをビルドしdistを`backend/target/frontend-dist`へコピーしてから、backendで`./mvnw -Pweb-bundle verify`を実行する。`clean`はこの入力も消すため、その場合はVue成果物を配置し直す。通常のJava開発だけへ戻す際は`./mvnw clean verify`で前回同梱物の残存を除去できる。

## 本番ST・UAT

| ID | 操作・期待結果 | 結果 |
|---|---|---|
| PROD-01 | HTTPS画面、JS/CSSと3 APIが200、HTTP混在なし | HTTP応答と公開版PC/スマートフォン基本描画・操作は確認済み。混在通信の網羅確認は未実施 |
| PROD-02 | 新規DBへV1/V2、再起動で再適用なし、MySQL8.4.13/専用ユーザー | 未実施 |
| PROD-03 | 回復commit後初回収集、終了から約60秒後に次回、表示更新は外部収集を増やさない | 起動ログと観測更新を確認。画面更新との分離の本番操作確認は未実施 |
| PROD-04 | 最新価格/24hチャート/全観測統計/UTC-JST/候補市場の未収集案内 | 公開画面・候補切替をユーザー確認、API統計再計算成功。UTC-JSTの厳密照合は未実施 |
| PROD-05 | 30秒更新、履歴展開/ページ送り、PC/スマートフォン | 成功：公開版の操作・自動更新をユーザー確認。モバイル調整も本番反映後の表示をユーザー確認 |
| PROD-06 | 部分通信失敗でも正常データ保持・エラー明示、3分経過警告 | 未実施 |
| PROD-07 | health詳細なし/env404、DB非公開、HTML/JS/ログに秘密なし | health詳細なし/env404を確認。ユーザー提示MySQL Networking画面で公開Domain/TCP Proxyなし・private有効を確認。秘密情報の網羅点検は未実施 |
| PROD-08 | SIGTERMで収集待機・DB存続、停止完了後だけ新App起動、新旧重複なし | 旧App REMOVED後に新版起動する手順を確認。実行中の収集を狙ったSIGTERM待機順序の本番試験は未実施（既存自動テストあり） |
| PROD-09 | App再デプロイ/MySQL再起動後もVolumeの履歴維持 | 成功：App更新後とMySQL停止/再開後に既存id1・101・127を確認。再開後id131/132のSUCCESSと約60秒周期も確認 |
| PROD-10 | 公開前確認時間帯のDB停止で起動/health異常、復旧後正常。空配列に偽装しない | 未実施 |
| PROD-11 | 通知15/Hard25、1 replica、sleep/autodeploy/PR環境OFF、リソース/Usage確認 | App/MySQL各1GB・1vCPUと1 replica、App Serverless OFF、Usage表示を提示画面で確認。通知/上限・自動デプロイ/PR環境の最終設定の再照合は未実施 |
| PROD-12 | バックアップと隔離復元、停止/削除/解約方法確認 | バックアップ/復元は2026-11-14合意でPhase 1対象外。取得なし・復元不可を許容。計画停止/再開は成功、削除/解約は手順整理のみで未実行 |
| UAT-01 | 最新価格・推移・スプレッド・観測日時を利用者が把握できる | 成功：基本画面とモバイル調整の本番表示をユーザー受入れ |
| UAT-02 | 公開URL/対象コミット/制限をユーザーが確認して受入れ | 未実施 |

実施時は対象コミット・環境・日時・結果・問題を記録。DBを削除/初期化して障害試験をしない。費用上限を実際に消費して検証しない。未確認は未確認のまま明示する。
