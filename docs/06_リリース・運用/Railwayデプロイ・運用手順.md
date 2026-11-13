# Railwayデプロイ・運用手順

更新：2026-11-13。契約・クラウド操作はユーザーが行う。構築・公開は未実施。秘密値はGit・チャットへ貼らない。

## 1. 予算設定

Railwayの対象Workspaceを確認してHobbyを契約。Usage → Set Usage LimitsでComputeのCustom email alert=$15 / Hard limit=$25を設定し、再表示して保存を確認する。Railway Agentは使わない。Billingで課金期間・基本料・税等も確認する。

Hard Limitは請求総額の保証ではない。到達時にApp/DBが停止し、上限変更後や翌周期に自動再開される場合がある。恒久停止には削除・解約を使う。目標月$10〜20を超えそうなら追加契約前に原因確認する。[料金](https://docs.railway.com/pricing/plans)、[費用制御](https://docs.railway.com/pricing/cost-control)

## 2. MySQLとVolume

同一Project/production環境へMySQLを追加する。テンプレート作成で即時起動される場合があるため、データ投入前に設定を確認する。

| 項目 | 値 |
|---|---|
| Image | `mysql:8.4.13`。既定のメジャー版を無検証で使わない |
| Volume | 1つ、`/var/lib/mysql`。再デプロイでも維持 |
| Networking | privateのみ。外部TCP Proxy不要。テンプレートが既定で作成した場合も無効化/削除 |
| Replica/Sleep | 1 / OFF |
| 初期リソース上限案 | 1GB / 1vCPU。起動・実測で確認 |

公式イメージ初期化変数：

| 変数 | 値 |
|---|---|
| `MYSQL_ROOT_PASSWORD` | 生成した管理者用秘密値 |
| `MYSQL_DATABASE` | `crypto_market_monitor` |
| `MYSQL_USER` | `crypto_app` |
| `MYSQL_PASSWORD` | rootとは別に生成したApp専用秘密値 |

Railwayの`MYSQLUSER`と公式イメージの`MYSQL_USER`は別名。Appは後述の公式イメージ用変数への参照を使う。既定の`MYSQL_URL`やroot接続を無検証で使用しない。

**初期化変数は空のデータディレクトリだけに有効**。既存Volumeへ変数だけ変えてもユーザー・パスワードは更新されない。別メジャー版で初期化されたVolumeへ8.4を向けてダウングレードしない。空であると確認できない場合は削除せず相談する。

DBログとVolumeを確認。RailwayのDBコンテナシェル/CLIを使える場合、`mysql -u root -p`で対話入力し次を確認する。CLI未導入ならその時点で操作を案内する。

```sql
SELECT VERSION();
SHOW DATABASES;
SHOW GRANTS FOR 'crypto_app'@'%';
```

期待：8.4.13、対象DB、Appユーザーの対象DBへの権限。テンプレート変更後の版・初期化・バックアップは自分で管理する。[Railway MySQL](https://docs.railway.com/databases/mysql)、[公式MySQL初期化](https://hub.docker.com/_/mysql)

DBの独自Start Commandは設定せず、公式イメージの初期化・mysqld起動を使う。テンプレート側に別の起動指定がある場合は、8.4の標準起動を妨げないか確認する。

## 3. App設定

空のAppサービスへ先に変数を設定する。GitHub接続で即時ビルドされる場合があるため、DBと変数が整ってから接続し、自動デプロイを無効化する。

| 項目 | 値 |
|---|---|
| Source / Root | 本リポジトリmain / `/` |
| Builder | ルートDockerfile |
| Start Command | 空欄、ENTRYPOINT使用 |
| Replicas / Region | 1 / 単一リージョン |
| Auto Deploy / PR環境 / Sleep | すべてOFF |
| Healthcheck | `/actuator/health`、300秒 |
| 初期リソース上限案 | 1GB / 1vCPU |

App Variables（DBサービス名がMySQLの場合。違えば参照先を変更）：

```text
SPRING_PROFILES_ACTIVE=prod
DB_HOST=${{MySQL.RAILWAY_PRIVATE_DOMAIN}}
DB_PORT=3306
DB_NAME=${{MySQL.MYSQL_DATABASE}}
DB_USER=${{MySQL.MYSQL_USER}}
DB_PASSWORD=${{MySQL.MYSQL_PASSWORD}}
COLLECTION_ENABLED=true
COLLECTION_FIXED_DELAY=60s
COLLECTION_SHUTDOWN_WAIT=30s
RAILWAY_DEPLOYMENT_DRAINING_SECONDS=90
RAILWAY_DEPLOYMENT_OVERLAP_SECONDS=0
```

PORTはRailway注入値を使う。JAVA_TOOL_OPTIONSはDockerfileの最大ヒープ50%/初期10%を初期値とする。ヒープ外メモリもある。DB秘密をDocker ARGへ渡さない。公開GitHubリンクだけは公開ARGに設定済み。

## 4. デプロイ

1. ローカル/PRの試験→CI成功→main取り込み。GitHubからCDはしない。
2. MySQL起動・同じDBを使う別Appがないことを確認する。
3. 初回はGitHub接続時のビルド状態を確認。以後はDeploy Latest Commit等から対象mainコミットを手動デプロイする。
4. Vue/JARビルド、Flyway V1/V2、回復、初回/次回収集をログとDBで確認する。
5. App NetworkingでRailway提供ドメインを生成し実際のPORTを使う。HTTPS画面・3 API・ST/UATを確認する。

**更新は旧App Deploymentを停止（Remove等）→停止完了確認→新App起動。** MySQL/Volumeは削除しない。新旧並行起動を避けるため、単なるRedeploy/Restart/Rollbackを無検証で押さない。失敗時も新Appを停止完了してから旧コミットを起動する。設定変更で起動される場合も同じ順序。短時間の公開停止を許容する。

[GitHub連携](https://docs.railway.com/deployments/github-autodeploys)、[終了猶予](https://docs.railway.com/deployments/deployment-teardown)

## 5. 公開後

当日・翌日にOOM/CPU/メモリ/Volume増加/Usage見込を確認し、1週間程度の実測で月額を再評価する。healthcheckは継続監視ではないので収集ログと鮮度も確認する。

公開前・DB変更前にVolume Backupを手動取得し、料金と隔離環境への復元を確認する。復元検証は本番DBへ接続しない。バックアップは無制限無料と仮定せず、自動スケジュール・自動削除は今回追加しない。[バックアップ](https://docs.railway.com/volumes/backups)

## 6. 停止・削除・解約

| 操作 | 残るもの・確認事項 |
|---|---|
| App停止 | DB・Volume・契約が残る |
| DB停止 | Volume使用量と契約が残る |
| サービス/Project削除 | 関連Volume/Backupが残らないか確認。必要データを先に退避 |
| Volume削除 | データ消失操作。公式では48時間の復元猶予後に永久削除 |
| Hobby解約 | Workspace Billing → Active Plan → Cancel Plan。稼働停止、期間末までプラン有効、既発生額/最終請求確認 |

完全終了はApp/DB停止→必要データ退避→サービス/Volume/Backup削除確認→不要Project削除→Hobby解約→Billing/Usageで残存リソースと最終請求を確認する。**停止だけでストレージ費用・契約料がなくなったと判断しない。** 削除猶予中の扱いも含め課金終了は画面で確認する。破壊操作はユーザー判断後に行う。

[Volume・削除](https://docs.railway.com/volumes/reference)、[解約](https://docs.railway.com/pricing/faqs)
