# 機能D：定期実行・起動時回収の詳細設計

更新：2026-11-09。D-02の実装後、ユーザーの依頼でD-03「Spring標準Schedulerへの移行」を実施。起動時回収・周期・停止の合意は維持する。現行構成と結果は末尾を参照。

## 目的・対象範囲

既存のCollectionService.collectBtcUsdを自動実行し、開始前に前回残存したRUNNINGを回収する。単一バックエンドプロセスを前提とする。価格検証・保存の基本動作、2参照API、DBスキーマは維持する。

## 合意済みの動作

```text
Spring Boot起動・Flyway適用
  → 残存RUNNINGをINTERRUPTEDへ更新してコミット
  → 初回の収集をすぐに実行
  → 1回の処理終了から60秒待つ
  → 次の収集（直列実行）
```

1. **初回**：回収のコミット完了後、すぐに1回実行する。回収前に収集を開始しない。
2. **周期**：fixed delay。前回終了から60秒後に次の実行を始める。5秒かかれば開始間隔は約65秒。毎分00秒や開始から60秒を意味しない。
3. **起動時回収失敗**：起動を失敗させ、収集を開始しない。稼働中の通常の通信/DB障害は既存Bの記録・ログ方針を使い、次回周期を継続する。停止期間の価格を後追い取得したことにはしない。
4. **正常停止**：停止要求後、新規の定期実行を止め、実行中の収集が終了するまで最大30秒待つ。完了した場合は結果保存まで終えて終了へ進む。待機中は収集に必要なDB等を利用可能にする終了順序が必要。時間超過で待機を打ち切って終了へ進むが、処理を安全に強制終了できることやプロセス全体が30秒以内に終了することは保証しない。残ったRUNNINGは次回起動時に回収する。

回収では実際の停止時刻を推測せず、finished_atはnull、interruption_detected_atへ検出時刻を記録する。RUNNING以外の確定状態は変更しない。日時精度は既存のマイクロ秒切捨て方針に合わせる。

強制終了では正常停止処理が実行されないことがある。HTTPの接続3秒・読取り待ち5秒は総所要時間の上限ではなく、停止時の30秒の猶予とは別の設定。

## 追加合意

- 設定で収集を無効化できる。enabled=falseでも起動時回収は行い、初回/周期収集だけを開始しない。アプリ全体のDB書込みを完全禁止する設定ではない。
- Coinbaseから429を受けたら既存のFAILED/RATE_LIMITED記録を使い、処理終了から通常の60秒後に次回収集する。即時再試行は行わない。Retry-Afterによる待機時間調整は今回実装しない。この仕様は画面→参照APIの通信制御ではない。

## クラス・主要メソッド

以下の4クラスを追加した。メソッド名・設定キーも下記の内容で実装した。

| 配置 | クラス | 主な担当・入出力 |
|---|---|---|
| collection.startup | CollectionStartupRunner | ApplicationRunnerのrunで回収Serviceを呼ぶ。更新件数をログへ残し、回収成功後、enabledならScheduler.start()を呼ぶ |
| collection.scheduler | CollectionScheduler | start()で初回即時・fixed delayを1回だけ登録。定期タスクから既存collectBtcUsd()を同期呼出し。stop()で新規実行を停止し実行中の終了を待つ |
| collection.config | CollectionSchedulingConfiguration | 1スレッドの収集専用Schedulerと設定Beanを構成する。通常のHTTPリクエスト用スレッドと分ける |
| collection.config | CollectionSchedulingProperties | enabled、実行間隔、停止待機時間を外部設定から受け、正の時間等を検証する |

既存クラスへ追加する処理：

| 対象 | 追加する役割 |
|---|---|
| CollectionPersistenceService.recoverInterruptedRuns(): int | 短い@Transactional内でUTCの検出時刻を小数秒6桁へ切捨て、Repositoryを呼び、更新件数を返す |
| CollectionRunRepositoryの回収メソッド | status=RUNNINGだけを一括更新。INTERRUPTED・検出時刻・result_code=INTERRUPTEDを設定し、finished_at=null。その他の状態と観測は変更しない |

既存CollectionService、Client/Mapperの取得・保存・エラー分類の基本動作は再実装しない。今回の追加により「アプリ起動後の自動収集」が初めて有効になる。

## 起動・Tx・失敗時の境界

```text
Flyway・JPA初期化
  → CollectionStartupRunner.run()
      → CollectionPersistenceService.recoverInterruptedRuns()
          → Repository更新（Tx開始〜コミット）→ 更新件数
      → enabledを判定
          true  → CollectionScheduler.start() → 定期タスク登録
          false → 収集は登録せず、参照APIは利用可能

定期タスク
  → CollectionService.collectBtcUsd()（全体のTxなし）
  → 成功/失敗を問わず、その呼出し終了から指定時間待って次回
```

- 回収のTxが確定してから登録する。RunnerやSchedulerに回収・HTTP・保存全体を包む@Transactionalを付けない。
- 回収例外はRunnerで握りつぶさず、起動失敗へ伝える。enabled=falseでも回収に失敗したら起動失敗とする。
- Springの自動起動で回収より先に定期タスクが動かないよう、収集開始はRunnerから明示的に行う。@Scheduledを付けただけで順序が保証されると仮定しない。
- 1スレッドで既存Serviceを同期呼出しし、非同期処理へ投げ直さない。startの重複呼出しでタスクを二重登録しない。
- 既存Serviceが扱う通常の取得/保存失敗では次回を残す。Scheduler境界へ想定外のRuntimeExceptionが届いた場合もログを残し、定期登録が黙って消えないよう制御する。JVMのErrorまで一律に握りつぶさない。

## 停止と設定

停止制御はSpringの終了ライフサイクルへ接続し、DB/EntityManagerFactory等の破棄より前に、新規実行停止と実行中の待機を行う。

- 停止受付後は次回予約を取り消し、停止と実行開始が競合しても新しい収集へ進まないようにする。
- 予約の取消と、実行中の処理の終了確認を区別する。Futureの取消完了だけで収集も完了したと扱わない。
- 待機枠は1回の停止に対し最大30秒。複数の停止フックで30秒ずつ重複待機する実装を避ける。
- 待機枠を超えたらログへ残して終了へ進む。割込み要求と処理の確実な終了は別であり、架空の終了時刻や成功状態を保存しない。
- 開始前/未開始での停止、停止の複数回呼出しでも余計な収集や例外を生まない構成にする。

| 設定キー | 初期値 | 意味 |
|---|---|---|
| market-monitor.collection.enabled | true | 回収後の自動収集を開始するか |
| market-monitor.collection.fixed-delay | 60s | 前回の処理終了から次回まで |
| market-monitor.collection.shutdown-wait | 30s | 停止時の収集終了待機枠 |

既存のSpring起動テスト/DB結合テストではenabled=falseを明示し、実Coinbaseへ自動接続させない。回収自体を確認するテストと、無効化設定のテストは区別する。API契約・DBスキーマは変更せず、application設定とテスト設定は必要分だけ追加する。

## テスト分担・実装後の完了条件

| 区分 | 確認事項 |
|---|---|
| RunnerのUT | 回収→開始の順、更新件数、回収失敗なら開始なし、enabled=falseでも回収するが開始なし |
| Schedulerのテスト | 初回即時・fixed delay登録、二重start防止、同期直列呼出し、通常失敗/想定外RuntimeException後の次回継続 |
| 停止/ライフサイクル試験 | 新規実行停止、処理中の完了待ち/待機超過、開始と停止の競合、二重stop、DB破棄より前の待機 |
| 実MySQL結合 | RUNNINGだけ回収、複数件/0件、確定済み状態不変、finished_at=null・検出時刻/理由、観測不変、Tx確定/失敗と収集開始の順序 |
| 既存試験の回帰確認 | A/B/Cの通常・MySQL試験を維持し、通常testやDB試験が実Coinbaseを呼ばない |

通常試験は実際に60秒/30秒待つ方式にせず、Schedulerの登録検証・固定Clock・制御可能なタスク/待機・必要な同期手段を使う。ライフサイクルの順序はSpringコンテキストを含む試験でも確認する。実HTTP/実プロセス強制終了を伴わない試験の保証範囲はソースに明記する。

個別ケース仕様はテストソースへ観点ID・日本語表示名・前提/入力・操作・期待結果として記載する。本書は設計と分担のみを示し、ケース仕様を二重管理しない。

実装後は、回収→初回→周期継続、停止順序、異常系が合意どおりに動き、各試験とユーザーのコード確認が完了したことをもって機能DのDoneを判断する。

## 現在地

D-03のSpring標準Scheduler移行を実装。通常テスト200件成功、DB結合51件成功を更新レポートで確認した（既存50件の実行＋追加D-DB-05の個別実行1件）。合計251件成功、失敗・エラー・スキップ0。2026-11-09にユーザーから業務コード・テスト観点の確認完了を受領した。設計・実装・自動テスト・読解レビューの開発工程は完了。開発アプリでの手動確認・システム全体のST/E2Eは未確認として機能⑥へ引き継ぎ、次は機能⑤のVue業務画面設計へ進む。

## D-03 Spring標準Schedulerへの移行（2026-11-09）

### 変更前後のクラス構成

クラス名・パッケージは維持し、新しい業務クラスは増やしていない。

| クラス | 変更前 | 変更後 |
|---|---|---|
| CollectionSchedulingConfiguration | JDK ScheduledThreadPoolExecutorを手動生成したBean | Spring ThreadPoolTaskSchedulerをcollectionTaskSchedulerとして定義。pool=1・market-collection-を設定 |
| CollectionScheduler | JDK Executorへ登録し、終了イベント/DisposableBeanで停止 | TaskSchedulerのscheduleWithFixedDelayへ明示登録。SmartLifecycleで停止 |
| CollectionStartupRunner | 回収復帰後にstart | 無変更 |
| CollectionSchedulingProperties | enabled / fixedDelay / shutdownWaitと検証 | 無変更 |
| CollectionPersistenceService / CollectionRunRepository | 回収TxとRUNNING条件付き更新 | 無変更 |

登録はTaskSchedulerのAPIを使用する。TaskSchedulerインターフェース自体は停止/割込みAPIを持たないため、注入型は標準実装ThreadPoolTaskSchedulerとし、同じ専用BeanのライフサイクルAPIも呼ぶ。独自のアダプターやJDK Executor取得は業務コードに追加しない。

### @Scheduledを使わない理由

`@Scheduled`の通常の自動登録に置き換えると、ApplicationRunnerの回収より先に収集可能になる。今回もRunnerが回収Txの正常コミット後に明示的にstartを呼ぶ方式を維持した。周期そのものはSpring標準TaskSchedulerに任せる。`@EnableScheduling`は明示登録には不要。

### Springとアプリの担当

| Springに委譲 | アプリに残す |
|---|---|
| スレッド作成、初期化、pool管理、Bean破棄 | 回収コミット後の開始、enabled判定 |
| fixed delayの予約と実行 | 二重登録・停止後の再開防止 |
| stop(callback)による実行中タスクの完了検出 | 取消と完了通知を区別すること |
| SmartLifecycleによる停止フェーズ | 共有する最大30秒の待機枠、超過時の割込み要求 |
| initiateShutdown / shutdownによる基盤停止 | RuntimeExceptionの記録・次回継続、Errorの伝播方針 |

Java標準Executorの直接生成、getScheduledExecutor等による実体取得、shutdownNow/awaitTerminationの直接操作、ContextClosedEventの独自受信、ApplicationContextの保持、DisposableBeanの独自破棄処理を削除した。開始/停止の同期、予約Future、共有待機開始時刻は既存の安全性のため残す。完了の検出は独自カウンターを使わず、Springの停止callbackをCompletableFutureへつなぐ。

### 起動順序

```text
SpringによるTaskScheduler初期化（まだ業務タスクは未登録）
→ Flyway/JPA初期化
→ CollectionStartupRunner.run
→ recoverInterruptedRunsのTx・コミット
→ enabledならCollectionScheduler.start
→ scheduleWithFixedDelay(callback, Schedulerの現在時刻, fixedDelay)
→ 初回即時、以後は処理終了から60秒
```

CollectionScheduler.isAutoStartup=falseにより、Springの自動起動で業務収集を開始しない。基盤のスレッド初期化と収集タスクの登録は別。回収失敗は起動失敗へ伝播する。enabled=falseでも回収は実行する。

### 終了順序と設定の理由

```text
Context.close
→ Spring標準Schedulerが早期終了通知で新規予約を拒否（実行中には割り込まない）
→ CollectionSchedulerのSmartLifecycle停止（基盤より高いphase）
  → cancel(false)で次回を取り消す
  → initiateShutdownで受付停止（手動stopでも同じ動作）
  → ThreadPoolTaskScheduler.stop(callback)で実処理の完了通知を依頼
  → 最初の停止受付から最大shutdownWaitだけ通知を待つ
     完了：先へ進む
     超過/待機割込み：Springのshutdownで割込みを要求して先へ進む
→ Springの他の停止フェーズ
→ Bean破棄・Scheduler/DataSource等の破棄
```

- CollectionSchedulerのphaseはSmartLifecycle.DEFAULT_PHASE。ThreadPoolTaskScheduler既定phaseより高いため先に停止する。通常のDataSource/EntityManagerFactory破棄はその後になる。
- 基盤の`waitForTasksToCompleteOnShutdown=false`と`awaitTerminationMillis=0`は意図的。正常な猶予は先行するSmartLifecycleで与え、最後のshutdownは割込み要求のみとする。Springの破棄時にもう一度30秒待つことを避ける。
- `waitForTasksToCompleteOnShutdown=true`と30秒のawaitを付けるだけでは、時間超過後の割込み要求が行われず、今回の仕様を満たさない。またSpringのstop(callback)の経路も変わるため併用しない。
- Springのstop(callback)は基盤をpause状態にするため、後続の自動ライフサイクル停止で再び完了待機しない。最終破棄時のshutdown呼出しは待機0で、二重の待機枠は作らない。
- 重複stopは同じ完了通知と最初の停止からの待機枠を共有する。手動stop中も完了/時間切れまではisRunningをtrueにし、同時Context.closeが待機を飛ばさない。
- Futureのcancel(false)は実行中処理の完了を意味しない。Springの実タスク完了通知を待つ。時間超過後も割込みに従わない処理は存続し得て、その後DB等が閉じられることがある。架空の終了時刻/成功状態は保存しない。

設定キー・既定値・検証は維持した。専用BeanへBoot全体の`spring.task.scheduling.*`を混ぜない。既存の`market-monitor.collection.shutdown-wait`を上記1枠へ適用する。外部依存追加・pom変更は行っていない。

### テストと保証範囲

- 通常200件成功（失敗・エラー・スキップ0）。Dの内訳はRunner5・設定11・Scheduler10・実Springライフサイクル7の計33件。
- 既存29件の観点は維持し、終了イベントのUTはSmartLifecycleの自動起動禁止・停止phase・停止callbackの観点へ置き換えた。削除/無効化による成功扱いはしていない。
- 追加4件：Spring境界でもErrorを伝播、Boot全体設定の混入防止、実Context.closeでの超過/追加待機なし、実Bootでの回収後初回実行。実スレッドはThreadPoolTaskSchedulerを使用する。
- DB結合の既存50件はユーザー環境のXMLレポートで成功確認。D-DB-05を追加し全対象51件。実MySQL/実HikariDataSource/実Spring Schedulerを使い、停止待機中のSELECT成功と完了後のプール破棄を確認する。追加1件は2026-11-09 16:38更新レポートでケース名と成功・失敗/エラー/スキップ0を確認した。既存50件は同日16:33更新レポートで確認済み。個別実行でCollectionPersistenceITのXMLが1件分へ置き換わっているため、現ディレクトリの件数合計と累計51件を混同しない。
- 開発用DB・実Coinbaseはテストで使用しない。機能A/B/C、回収処理、トランザクション境界、application.yml、Flywayは変更前後のハッシュでも同一を確認した。
- 未確認：OS/IntelliJからの強制プロセス停止、実Coinbase収集と画面までを通すST/E2E。変更後コードのユーザー読解は2026-11-09に完了報告を受領済み。通常のSpringコンテキスト終了と区別する。

### 変更ファイル

- main：CollectionSchedulingConfiguration、CollectionSchedulerの2クラス。
- test：CollectionSchedulingConfigurationTest、CollectionSchedulerTest、CollectionLifecycleTest、CollectionPersistenceIT。
- docs：本書、定期実行の動作確認、DB統合テスト実行手順、パッケージ構成、および内部開発進行ガイドの進捗。

### 公式情報

- [ThreadPoolTaskScheduler API](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/scheduling/concurrent/ThreadPoolTaskScheduler.html)：TaskSchedulerの標準実装とpool設定。
- [ExecutorConfigurationSupport実装](https://github.com/spring-projects/spring-framework/blob/v6.2.19/spring-context/src/main/java/org/springframework/scheduling/concurrent/ExecutorConfigurationSupport.java)：stop(callback)、終了通知、shutdownとawaitの関係。
- [ExecutorLifecycleDelegate実装](https://github.com/spring-projects/spring-framework/blob/v6.2.19/spring-context/src/main/java/org/springframework/scheduling/concurrent/ExecutorLifecycleDelegate.java)：実行中タスクの完了通知、pause、isRunning。

## 過去の検証記録

D-02のJDK Executor版は2026-11-08に通常196件＋DB50件＝246件成功を確認した。現在は上記D-03構成へ置換済みであり、この旧件数・旧実装を現行の試験結果と混同しない。
