# DB統合テストの実行手順

2026-11-09追記：mysql-itプロファイルはCollectionPersistenceIT（B21件＋Dの回収4件・実DB終了順序1件）とQueryApiIT（C25件）の計51件を実行する。D-DB-05ではSpring標準Schedulerの停止待機中も実MySQLへのSELECTが成功し、収集完了後に接続プールが閉じることを確認する。どちらも実Coinbaseへ接続しない。application-mysql-it.ymlで自動収集を無効化するが起動時回収は行う。固定時計は起動時から使えるTestBeanで供給する。手順は[参照APIの動作確認](参照APIの動作確認.md)・[定期実行の動作確認](定期実行の動作確認.md)を参照。

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
