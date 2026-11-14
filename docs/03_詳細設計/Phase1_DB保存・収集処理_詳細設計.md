# 機能B：DB保存・収集処理の詳細設計

更新：2026-11-09。現行設計。実装・自動テストは完了。本文は保存機能の責務で、他機能の未実装を意味しない。

## 1. 目的・範囲・完了条件

BTC-USDを1回収集し、開始履歴と結果を整合的に保存する。既存のClient → Mapper → MarketObservationを利用する。実行IDは収集メソッドのローカル変数で保持し、Client・Mapperへ渡さない。

実装タスクの完了条件は、正常・スキップ・取得失敗を保存でき、結果確定の原子性と確定済み状態の保護を実MySQLで検証できること。

対象外：Scheduler、起動時回収の実装、REST API、画面、デプロイ。V1/V2・API契約は変更しない。起動時RUNNING → INTERRUPTEDの方針は維持し、起動時回収は機能Dに実装済み。

## 2. 保存・状態遷移の方針

- 開始履歴をRUNNINGでコミットしてからHTTP取得する。開始登録に失敗したら取得しない。
- HTTP通信をDBトランザクションに含めない。
- 採用時は観測INSERTとSUCCESS更新を同一トランザクションで確定する。失敗したら両方ロールバックする。
- 完了更新はDB上のRUNNINGを条件にする。SUCCESSなど確定済みの状態を上書きしない。
- 保存失敗後は別トランザクションでFAILED更新を試みる。DB障害で更新不能ならログに残して終了する。保存結果不明と、FAILEDを記録できた状態を区別する。
- アプリ再起動なしにDBだけ復旧した場合、残存RUNNINGは次のアプリ起動まで残り得る。稼働中の定期回収は追加しない。
- 保存担当名はCollectionPersistenceService。CollectionServiceは収集の進行役。
- 開発用は既存Compose MySQL、DB統合テスト用はTestcontainersの別MySQL。テスト用コンテナの起動・破棄を管理し、開発データへ接続しない。
- 同価格でも収集ごとに保存。数値はBigDecimal、日時はInstant、保存時に小数秒6桁へ切り捨てる。提供元時刻sourceAtは現行取得ではnull。

## 3. 呼出しとトランザクション

```text
CollectionService.collectBtcUsd()：処理全体のトランザクションなし
  ├─ CollectionPersistenceService.startBtcUsd()
  │    └─ Tx A：RUNNINGをINSERT → コミット → Long runId
  ├─ CoinbaseClient.fetchBtcUsd() → 応答DTO＋receivedAt
  ├─ CoinbaseBookMapper.convertBtcUsd(response, receivedAt)
  │    └─ ACCEPTED＋MarketObservation／SKIPPED・FAILED＋理由
  └─ CollectionPersistenceServiceで結果確定
       ├─ Tx B：RUNNING → SUCCESSの条件付き更新＋観測INSERT
       └─ または短いTx：RUNNING → SKIPPED／FAILED

結果保存の例外 → 元のTx終了後、別の短いTxでRUNNING → FAILEDを試行
```

現行実装：別Beanの公開保存メソッドに@Transactionalを付ける。CollectionServiceから非トランザクションで呼ぶので、各呼出しで独立したTxになる。REQUIRES_NEWは初めから付けず、収集を既存Tx内で呼ばないという前提を明記・検証する。同一クラス内の自己呼出しでTxを開始しない。

成功確定は、実行ID・RUNNING・観測の取引所/通貨ペアを条件に履歴を更新し、更新1件を確認してから観測をINSERTする。0件なら不一致として例外とし、観測は作らない。INSERTやコミットが失敗したらSUCCESS更新も取り消す。確定後の再呼出しは二重登録しない。再呼出しを新たな成功とは扱わない。

FAILED更新は更新件数からbooleanを返す。0件ならFAILED確定とログに出さない。SUCCESSだった場合はそのまま守り、状態確認も不能なら結果不明としてログへ残す。コミット応答喪失の完全な再現は模擬テストで扱い、実DB試験は条件付き更新による保護を検証する。

## 4. 主要クラス・メソッド

基準パス：backend/src/main/java/com/example/cryptomonitor/。

| ファイル | 役割・主な入出力 |
|---|---|
| collection/service/CollectionService.java | collectBtcUsd(): void。開始→取得→変換→結果保存を進行。失敗は分類して記録を試み、記録不能もログへ残す。戻り値で成功を偽装しない |
| collection/service/CollectionPersistenceService.java | startBtcUsd(): Long、completeSuccessfully(Long, MarketObservation): void、completeAsSkipped(Long, String): void、completeAsFailedIfRunning(Long, String): boolean |
| collection/entity/CollectionRun.java | collection_runsに対応するEntity。DB生成Long ID、取引所・ペア・状態・時刻・理由を保持。開始用の生成処理と参照用getterを中心にする |
| collection/entity/CollectionRunStatus.java | 既存5状態のenum。DBへ文字列保存 |
| collection/repository/CollectionRunRepository.java | JpaRepositoryによる開始登録、RUNNINGを条件とした更新。更新件数を返す |
| market/entity/MarketSnapshot.java | market_snapshotsに対応するEntity。共通観測と実行履歴から保存用データを作成 |
| market/repository/MarketSnapshotRepository.java | JpaRepositoryによる観測登録。検索API用メソッドは参照API設計を参照 |

EntityはJavaBeansの無制限なsetterを公開しない。状態確定はRepositoryの条件付き更新に統一し、Entity内の更新操作と二重管理しない。JPA一括更新後に同じ永続化コンテキスト内の古いEntityを参照し続けない構成にする。

MarketSnapshotからCollectionRunへの片方向関連（遅延取得、cascadeなし）を採用する。逆方向の観測リストは不要。成功確定では対象取引所・ペアを条件付き更新で確認し、同じrunIdへの参照を設定する。存在確認のためだけの不要な全件検索を行わない。

理由コードは既存Mapperの文字列をそのまま保持し、通信Reasonは同名コードへ明示対応付けする。保存障害にPERSISTENCE_ERROR、想定外のRuntimeExceptionにUNEXPECTED_ERRORを使用する。公開説明は履歴DTOへ変換する際に付与し、DBへ保存しない。SUCCESSのresult_codeはnull。

関連設定：CoinbaseBookMapper.javaをBean登録し、Clockを注入する。Mapperの判定仕様とClientの取得仕様は変更しない。Clockは既存UTC時計を再利用し、テストは固定時計を渡す。

## 5. 保存データ・例外の扱い

| 場面 | DBの結果／制御 |
|---|---|
| 開始成功 | RUNNING、started_atあり、finished_at/result_code/interruption_detected_atはnull |
| 採用 | SUCCESS＋観測1件、finished_atあり、result_codeはnull |
| スキップ | SKIPPED＋理由、finished_atあり、観測0件 |
| 取得・検証失敗 | FAILED＋理由、finished_atあり、観測0件 |
| 成功保存失敗 | Tx全体をrollback後、RUNNINGならFAILED/PERSISTENCE_ERRORの記録を試行 |
| 結果更新自体が失敗 | 別TxでFAILED/PERSISTENCE_ERRORを試行。元の取得理由もログに保持 |
| 既に確定済み | 更新しない。SUCCESSをFAILEDへ変更せず、観測を二重INSERTしない |
| DB接続不能 | 記録不能・結果不明をログに残す。FAILEDが保存できたとは扱わない |

取得・変換と結果保存のtry/catchを分け、保存例外をNETWORK_ERROR/INVALID_RESPONSEへ分類しない。想定外のRuntimeExceptionは調査用ログとFAILED記録の対象とする。JVMのErrorを一律に握りつぶさない。

開始・終了・観測・提供元時刻の保存値はtruncatedTo(MICROS)を通す。観測時刻を現在時刻で置き換えない。価格・数量はDECIMAL(30,12)の精度を維持し、保存時に意味のある桁を丸めない。DBの既存CHECKは数量0を許すが、収集時の数量0スキップ規則はMapperが担う。

ログはrunId（未確定ならなし）、取引所・ペア・理由・例外を中心とする。認証情報や応答全文は出力しない。

## 6. テスト観点と実行方法

| テスト | 必須ケース |
|---|---|
| CollectionServiceTest：JUnit/Mockito | 開始失敗ならHTTPなし／正常のIDと観測引継ぎ／SKIPPED・FAILED分岐／各通信理由の対応／想定外例外／保存失敗後のFAILED試行／救済更新失敗で終了／更新0件を確定成功と誤認しない |
| CollectionPersistenceIT：Testcontainers MySQL | 空DBへのFlyway V1/V2適用とJPA検証／RUNNING開始／SUCCESSと観測1件のcommit／同価格の別run保存／SKIPPED・FAILEDは観測0件／不正runId・市場不一致を拒否／確定済み状態を保護／二重確定の防止 |
| 同DB統合テスト | 観測INSERT失敗時にSUCCESS更新もrollback／FK・UNIQUE・CHECK／DECIMAL境界／UTCと小数秒6桁の切捨て／sourceAtのnull・非null保存 |

代表的な整合性確認は、同じrunIdへのSUCCESS＋観測保存と、観測INSERT失敗時の両操作のロールバック。DB試験はテストメソッド全体の自動rollbackだけに頼らず、ServiceのTx終了後に別Txで保存結果を読む。コミットで出る制約違反も確認する。

Testcontainersは既存と同じMySQL 8.4.13を使い、開発用volumeを接続しない。テスト専用接続設定を動的に渡し、localプロファイルを無効化して.envや開発DBへ依存させない。テスト間の初期化は専用DBの中だけで行う。Docker未起動時に統合テストを成功扱いのスキップにしない。

実装済みの分離方法：pom.xmlにtestスコープのTestcontainers依存、mysql-itプロファイルにFailsafeを設定している。FailsafeはDB統合テストを明示対象とし、既存CoinbaseLiveConnectionITは対象外にする。

```powershell
# 通常テスト。Docker・実Coinbase・開発DBを使わない
.\mvnw.cmd test

# 実装済みのDB統合テスト。通常テストも実行する。Dockerが必要
.\mvnw.cmd -Pmysql-it verify
```

mysql-itは実装済み。application-mysql-it.ymlとTestcontainersで開発用DBから分離し、実Coinbaseを呼ばず模擬Clientを使用する。詳しくは[DB統合テスト実行手順](../05_テスト/DB統合テスト実行手順.md)を参照。


## 現在の参照先

起動時回収・周期制御は[機能D](Phase1_定期実行・起動時回収_詳細設計.md)、参照は[機能C](Phase1_参照API_詳細設計.md)を参照。個別ケース仕様はテストソース。
