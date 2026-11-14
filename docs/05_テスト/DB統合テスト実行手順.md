# DB統合テストの実行手順

更新：2026-11-14。mysql-itはCollectionPersistenceIT（保存・回収・終了順序）とQueryApiIT（最新価格・収集履歴・24時間履歴/集計）を実行する。実MySQLとSpringを使い、実Coinbaseには接続しない。テスト専用接続を動的に注入し、開発DBや.envは使わない。最新の件数・結果は[CI記録](CIの実行と確認.md)を参照。

## 対象と必要なもの

機能②のCollectionPersistenceITは、実MySQL・Flyway・JPA・保存Serviceのつながりとトランザクションを検証する。Docker Desktop（Linuxコンテナ）を起動する。開発用MySQL・Vite・Spring Bootアプリを別途起動する必要はない。

Testcontainersがmysql:8.4.13から別のコンテナを作成し、起動を待ち、テスト後に破棄する。初回はテスト依存ライブラリやMySQL・後片付け用イメージのダウンロードに時間がかかる。開発用DB・volumeは使用しない。コンテナはクラス単位で共有し、各テスト前に専用DB内の業務データだけを削除する。

## PowerShell／Maven

backendをカレントディレクトリにして実行する。

```powershell
# 通常テストのみ。Docker不要
.\mvnw.cmd test

# 通常テスト＋DB統合テスト
.\mvnw.cmd -Pmysql-it verify
```

mysql-itはpom.xml内のMavenプロファイルで、Failsafeのintegration-test/verifyを有効にする。application-mysql-it.ymlは別物で、テストの@ActiveProfilesが有効にするSpringプロファイル。

通常testではCollectionPersistenceITは対象外。mysql-itでも実Coinbase接続テストは実行しない。DB試験の中で使うClientはモック。Dockerが利用できない場合はDB試験を失敗とし、スキップして成功扱いにはしない。

## IntelliJ

1. pom.xmlの変更通知からMavenを再読込みし、テスト依存ライブラリを取得する。
2. Docker Desktopが起動していることを確認する。
3. src/test/javaのcollection/service/CollectionPersistenceITを開き、クラス名左の▶から実行する。

DBテストは実Coinbase用の-Dcoinbase.live-test=trueを必要としない。JUnitの@ActiveProfiles・@Testcontainersが接続先と起動を管理する。Mavenで実行する場合は、Maven欄のプロファイルmysql-itを有効にしてライフサイクルverifyを実行する。

## 代表コードの読み方

1. CollectionService.collectBtcUsd：開始→Client→Mapper→結果保存。
2. CollectionPersistenceService.completeSuccessfully：条件付きUPDATE→更新件数確認→観測INSERT。
3. CollectionPersistenceIT.commitsSuccessAndSnapshotTogether：メソッド終了後、commit済みの履歴と観測を読む。
4. CollectionPersistenceIT.rollsBackSuccessWhenSnapshotInsertViolatesConstraint：不正価格で実DBのCHECK違反を発生させ、履歴がRUNNINGに戻ることを読む。

各テスト全体には@Transactionalを付けていない。テスト終了時の自動rollbackによって本番のcommit失敗を見逃さず、保存Service本来のTx境界を確認する。

結果はtarget/surefire-reports（通常）、target/failsafe-reports（DB統合）に出力される。ビルドが成功しても、対象テストが実行されたことと失敗・スキップ件数を確認する。
