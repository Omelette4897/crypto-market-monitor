package com.example.cryptomonitor.collection.service;

import com.example.cryptomonitor.coinbase.dto.CoinbaseBookResponse;
import com.example.cryptomonitor.coinbase.dto.CoinbaseBookResponseWithReceivedAt;
import com.example.cryptomonitor.coinbase.client.CoinbaseClient;
import com.example.cryptomonitor.collection.repository.CollectionRunRepository;
import com.example.cryptomonitor.collection.entity.CollectionRunStatus;
import com.example.cryptomonitor.market.model.MarketObservation;
import com.example.cryptomonitor.market.repository.MarketSnapshotRepository;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 実MySQL・Flyway・JPA・保存Serviceを結合して検証する。
 * 各テスト全体に@Transactionalを付けず、本番と同じメソッド境界でcommit/rollbackさせる。
 * Docker接続不能は失敗として扱い、スキップで成功扱いしない。実Coinbaseへは接続しない。
 *
 * 試験区分：DB結合確認。実MySQL 8.4.13・Flyway・JPA・保存Service。Clientだけモック。
 * 実行方法：Docker Desktop起動後 .\mvnw.cmd -Pmysql-it verify。またはIntelliJでこのクラスを実行。
 * ケース仕様：各メソッドの観点ID・前提/入力・操作・期待結果を正とする。
 * 共通データ：固定値はフィールド/準備メソッド、入力の組合せは各Source注釈を参照。
 * 保証範囲外・未実施：ブラウザ/SchedulerからのST/E2Eは機能⑥で実施予定。実ネットワークのcommit応答喪失・プロセス強制停止は故障注入していない。
 */
@DisplayName("機能B・D / DB結合確認：保存・状態遷移・起動時回収")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("mysql-it")
@Testcontainers
class CollectionPersistenceIT {
    @Container
    static final MySQLContainer<?> MYSQL_CONTAINER = new MySQLContainer<>("mysql:8.4.13")
            .withDatabaseName("collection_test")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true")
            .withUsername("test_app").withPassword("test_only_password");

    /** 動的な接続先を優先登録し、開発用3306や.envへのフォールバックを防ぐ。 */
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry propertyRegistry) {
        propertyRegistry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
        propertyRegistry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        propertyRegistry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
    }

    @Autowired CollectionPersistenceService collectionPersistenceService;
    @Autowired CollectionService collectionService;
    @Autowired CollectionRunRepository collectionRunRepository;
    @Autowired MarketSnapshotRepository marketSnapshotRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean CoinbaseClient coinbaseClient;
    @TestBean(name = "coinbaseClock", methodName = "fixedUtcClock", enforceOverride = true) Clock utcClock;
    private static final Instant FIXED_CURRENT_TIME = Instant.parse("2026-11-06T01:02:03.123456789Z");

    /** Runner実行はBeforeEachより早い。起動時から使える固定ClockへBeanを置換する。 */
    static Clock fixedUtcClock() {
        return Clock.fixed(FIXED_CURRENT_TIME, java.time.ZoneOffset.UTC);
    }

    /** 削除対象はこのテストが起動した専用コンテナ内だけ。FKの子→親の順で初期化する。 */
    @BeforeEach void prepareIsolatedDatabase() {
        assertEquals("collection_test", jdbcTemplate.queryForObject("select database()", String.class));
        jdbcTemplate.update("delete from market_snapshots");
        jdbcTemplate.update("delete from collection_runs");
    }

    /**
     * 観点：B-DB-01 — 専用MySQLにV1・V2が適用される。
     * 前提・入力：空の使い捨てMySQLからSpringコンテキストを起動しFlywayを実行。
     * 操作：flyway_schema_historyのV1/V2成功件数をSQLで読む。
     * 期待結果：2件。Entity/SQLの対応はコンテキスト起動時のddl-auto=validateでも検査される。
     */
    @DisplayName("B-DB-01：専用MySQLにV1・V2が適用される")
    @Test void appliesBothFlywayMigrations() {
        assertEquals(2, jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where success = 1 and version in ('1','2')", Integer.class));
    }

    /**
     * 観点：B-DB-02 — 開始履歴をRUNNINGとして単独確定する。
     * 前提・入力：専用DBの業務テーブルは空、時計は小数秒9桁の固定Instant。
     * 操作：startBtcUsd後、メソッドTxの外から履歴を読む。
     * 期待結果：RUNNINGと切捨て済みstartedAt。終了/理由/中断検出時刻はnull、観測0件。
     */
    @DisplayName("B-DB-02：開始履歴をRUNNINGとして単独確定する")
    @Test void commitsRunningBeforeCollectionStarts() {
        Long collectionRunId = collectionPersistenceService.startBtcUsd();
        var collectionRun = collectionRunRepository.findById(collectionRunId).orElseThrow();
        assertAll(() -> assertEquals(CollectionRunStatus.RUNNING, collectionRun.getStatus()),
                () -> assertEquals(FIXED_CURRENT_TIME.truncatedTo(ChronoUnit.MICROS), collectionRun.getStartedAt()),
                () -> assertNull(collectionRun.getFinishedAt()), () -> assertNull(collectionRun.getResultCode()),
                () -> assertNull(collectionRun.getInterruptionDetectedAt()), () -> assertEquals(0, marketSnapshotRepository.count()));
    }

    /**
     * 観点：B-DB-03 — 成功履歴と同一実行の観測をコミットする。
     * 前提・入力：開始済み実行と正常な共通観測。時計は固定。
     * 操作：completeSuccessfully後にRepositoryで読み直す。
     * 期待結果：SUCCESS・同一IDへの関連・Bid値・観測/終了時刻の切捨てを確認。理由/sourceAtはnull。異常時の原子性はB-DB-04。
     */
    @DisplayName("B-DB-03：成功履歴と同一実行の観測をコミットする")
    @Test void commitsSuccessAndSnapshotTogether() {
        Long collectionRunId = collectionPersistenceService.startBtcUsd();
        collectionPersistenceService.completeSuccessfully(collectionRunId, marketObservation());
        var collectionRun = collectionRunRepository.findById(collectionRunId).orElseThrow();
        var marketSnapshot = marketSnapshotRepository.findAll().getFirst();
        assertAll(() -> assertEquals(CollectionRunStatus.SUCCESS, collectionRun.getStatus()),
                () -> assertEquals(collectionRunId, marketSnapshot.getCollectionRun().getId()),
                () -> assertEquals(0, new BigDecimal("100.123456789012").compareTo(marketSnapshot.getBidPrice())),
                () -> assertEquals(FIXED_CURRENT_TIME.truncatedTo(ChronoUnit.MICROS), marketSnapshot.getObservedAt()),
                () -> assertEquals(FIXED_CURRENT_TIME.truncatedTo(ChronoUnit.MICROS), collectionRun.getFinishedAt()),
                () -> assertNull(marketSnapshot.getSourceAt()), () -> assertNull(collectionRun.getResultCode()));
    }

    /**
     * 観点：B-DB-04 — 観測INSERT失敗でSUCCESS更新もロールバックする。
     * 前提・入力：開始履歴はcommit済み。負のBid価格を保存境界へ直接渡しDBのCHECK違反を起こす。
     * 操作：成功保存が例外となった後、別の読み取りで状態を確認し、FAILED更新を実行する。
     * 期待結果：RUNNINGへ戻り終了時刻null・観測0件。その後の別TxではFAILEDを確定できる。外部Mapperの検証試験ではない。
     */
    @DisplayName("B-DB-04：観測INSERT失敗でSUCCESS更新もロールバックする")
    @Test void rollsBackSuccessWhenSnapshotInsertViolatesConstraint() {
        Long collectionRunId = collectionPersistenceService.startBtcUsd();
        var invalidObservation = withPrices(new BigDecimal("-1"), new BigDecimal("101"));
        assertThrows(RuntimeException.class, () -> collectionPersistenceService.completeSuccessfully(collectionRunId, invalidObservation));
        assertEquals(CollectionRunStatus.RUNNING, collectionRunRepository.findById(collectionRunId).orElseThrow().getStatus());
        assertNull(collectionRunRepository.findById(collectionRunId).orElseThrow().getFinishedAt());
        assertEquals(0, marketSnapshotRepository.count());
        // 元のTxが戻った後なら、別Txで失敗履歴だけを確定できる。
        assertTrue(collectionPersistenceService.completeAsFailedIfRunning(collectionRunId, "PERSISTENCE_ERROR"));
        assertEquals(CollectionRunStatus.FAILED, collectionRunRepository.findById(collectionRunId).orElseThrow().getStatus());
    }

    /**
     * 観点：B-DB-05 — 同価格でも別収集として履歴を残す。
     * 前提・入力：同じ正常観測を2つの異なる開始履歴に渡す。
     * 操作：各実行を成功確定する。
     * 期待結果：履歴2件・観測2件。価格の一致を重複排除条件にしない。
     */
    @DisplayName("B-DB-05：同価格でも別収集として履歴を残す")
    @Test void persistsSamePriceForDifferentRuns() {
        collectionPersistenceService.completeSuccessfully(collectionPersistenceService.startBtcUsd(), marketObservation());
        collectionPersistenceService.completeSuccessfully(collectionPersistenceService.startBtcUsd(), marketObservation());
        assertEquals(2, marketSnapshotRepository.count());
        assertEquals(2, collectionRunRepository.count());
    }

    /**
     * 観点：B-DB-06 — スキップは履歴だけに保存する。
     * 前提・入力：開始済み実行とEMPTY_BOOK理由。
     * 操作：completeAsSkipped後にDBを読む。
     * 期待結果：SKIPPED/EMPTY_BOOK、観測0件。
     */
    @DisplayName("B-DB-06：スキップは履歴だけに保存する")
    @Test void recordsSkippedWithoutSnapshot() {
        Long collectionRunId = collectionPersistenceService.startBtcUsd();
        collectionPersistenceService.completeAsSkipped(collectionRunId, "EMPTY_BOOK");
        var collectionRun = collectionRunRepository.findById(collectionRunId).orElseThrow();
        assertEquals(CollectionRunStatus.SKIPPED, collectionRun.getStatus());
        assertEquals("EMPTY_BOOK", collectionRun.getResultCode());
        assertEquals(0, marketSnapshotRepository.count());
    }

    /**
     * 観点：B-DB-07 — 通信失敗は理由と終了時刻を保存する。
     * 前提・入力：開始済み実行とTIMEOUT理由。
     * 操作：completeAsFailedIfRunningを呼び、DBを読む。
     * 期待結果：true、FAILED/TIMEOUT・終了時刻あり、観測0件。
     */
    @DisplayName("B-DB-07：通信失敗は理由と終了時刻を保存する")
    @Test void recordsFailedWithoutSnapshot() {
        Long collectionRunId = collectionPersistenceService.startBtcUsd();
        assertTrue(collectionPersistenceService.completeAsFailedIfRunning(collectionRunId, "TIMEOUT"));
        var collectionRun = collectionRunRepository.findById(collectionRunId).orElseThrow();
        assertEquals(CollectionRunStatus.FAILED, collectionRun.getStatus());
        assertEquals("TIMEOUT", collectionRun.getResultCode());
        assertNotNull(collectionRun.getFinishedAt());
        assertEquals(0, marketSnapshotRepository.count());
    }

    /**
     * 観点：B-DB-08 — 確定済みSUCCESSを救済や二重確定で変更しない。
     * 前提・入力：正常にSUCCESSと観測をcommit済み。
     * 操作：FAILED救済更新、再度の成功確定、スキップ確定を順に試す。
     * 期待結果：救済はfalse、成功/スキップの再確定は例外。DBはSUCCESS・観測1件を維持する。
     */
    @DisplayName("B-DB-08：確定済みSUCCESSを救済や二重確定で変更しない")
    @Test void protectsCommittedSuccessFromFailureAndDuplicateCompletion() {
        Long collectionRunId = collectionPersistenceService.startBtcUsd();
        collectionPersistenceService.completeSuccessfully(collectionRunId, marketObservation());
        assertFalse(collectionPersistenceService.completeAsFailedIfRunning(collectionRunId, "PERSISTENCE_ERROR"));
        assertThrows(IllegalStateException.class, () -> collectionPersistenceService.completeSuccessfully(collectionRunId, marketObservation()));
        assertThrows(IllegalStateException.class, () -> collectionPersistenceService.completeAsSkipped(collectionRunId, "EMPTY_BOOK"));
        assertEquals(CollectionRunStatus.SUCCESS, collectionRunRepository.findById(collectionRunId).orElseThrow().getStatus());
        assertEquals(1, marketSnapshotRepository.count());
    }

    /**
     * 観点：B-DB-09 — FAILED・SKIPPED・INTERRUPTEDを再確定しない。
     * 前提・入力：ValueSourceの状態をSQLで設定。起動時回収は実行しない。
     * 操作：FAILED救済と成功確定を試す。
     * 期待結果：救済false、成功確定は例外。元の状態を保持し観測0件。中断回収の正しさは対象外。
     */
    @DisplayName("B-DB-09：FAILED・SKIPPED・INTERRUPTEDを再確定しない")
    @ParameterizedTest(name = "[{index}] {argumentsWithNames}") @ValueSource(strings = {"FAILED", "SKIPPED", "INTERRUPTED"})
    void doesNotOverwriteOtherCompletedStates(String completedStatus) {
        Long collectionRunId = collectionPersistenceService.startBtcUsd();
        jdbcTemplate.update("update collection_runs set status = ? where id = ?", completedStatus, collectionRunId);
        assertFalse(collectionPersistenceService.completeAsFailedIfRunning(collectionRunId, "PERSISTENCE_ERROR"));
        assertThrows(IllegalStateException.class, () -> collectionPersistenceService.completeSuccessfully(collectionRunId, marketObservation()));
        assertEquals(completedStatus, collectionRunRepository.findById(collectionRunId).orElseThrow().getStatus().name());
        assertEquals(0, marketSnapshotRepository.count());
    }

    /**
     * 観点：B-DB-10 — 存在しない実行IDでは観測を作らない。
     * 前提・入力：実行ID=-1。
     * 操作：成功確定とFAILED更新を試す。
     * 期待結果：成功確定は例外、FAILED更新はfalse、観測0件。
     */
    @DisplayName("B-DB-10：存在しない実行IDでは観測を作らない")
    @Test void rejectsMissingRun() {
        assertThrows(IllegalStateException.class, () -> collectionPersistenceService.completeSuccessfully(-1L, marketObservation()));
        assertFalse(collectionPersistenceService.completeAsFailedIfRunning(-1L, "TIMEOUT"));
        assertEquals(0, marketSnapshotRepository.count());
    }

    /**
     * 観点：B-DB-11 — 実行履歴と観測の市場不一致を拒否する。
     * 前提・入力：開始履歴はCOINBASE_EXCHANGE/BTC-USD。exchangeまたはsymbolだけを別値にした観測。
     * 操作：completeSuccessfullyを呼ぶ。
     * 期待結果：例外。履歴はRUNNING、観測0件。市場不一致でSUCCESSにしない。
     */
    @DisplayName("B-DB-11：実行履歴と観測の市場不一致を拒否する")
    @ParameterizedTest(name = "[{index}] {argumentsWithNames}") @ValueSource(strings = {"exchange", "symbol"})
    void rejectsDifferentMarket(String mismatchedField) {
        Long collectionRunId = collectionPersistenceService.startBtcUsd();
        var originalObservation = marketObservation();
        var mismatchedObservation = new MarketObservation(mismatchedField.equals("exchange") ? "OTHER" : originalObservation.exchange(),
                mismatchedField.equals("symbol") ? "ETH-USD" : originalObservation.symbol(), originalObservation.bidPrice(), originalObservation.bidSize(),
                originalObservation.askPrice(), originalObservation.askSize(), FIXED_CURRENT_TIME, null);
        assertThrows(IllegalStateException.class, () -> collectionPersistenceService.completeSuccessfully(collectionRunId, mismatchedObservation));
        assertEquals(CollectionRunStatus.RUNNING, collectionRunRepository.findById(collectionRunId).orElseThrow().getStatus());
        assertEquals(0, marketSnapshotRepository.count());
    }

    /**
     * 観点：B-DB-12 — 提供元時刻と観測時刻をUTC・マイクロ秒で保存する。
     * 前提・入力：小数秒9桁の観測時刻と、それより1秒前の非null提供元時刻。
     * 操作：成功保存し、Entity値とSQL表示・DBセッションのタイムゾーンを読む。
     * 期待結果：sourceAtは6桁へ切捨て。observed_atはUTCの01:02:03.123456、session.time_zoneは+00:00。実Coinbaseの提供元時刻を採用する仕様変更ではない。
     */
    @DisplayName("B-DB-12：提供元時刻と観測時刻をUTC・マイクロ秒で保存する")
    @Test void storesUtcAndTruncatesBothObservationTimestamps() {
        Long collectionRunId = collectionPersistenceService.startBtcUsd();
        var originalObservation = marketObservation();
        collectionPersistenceService.completeSuccessfully(collectionRunId, new MarketObservation(originalObservation.exchange(), originalObservation.symbol(),
                originalObservation.bidPrice(), originalObservation.bidSize(), originalObservation.askPrice(), originalObservation.askSize(), FIXED_CURRENT_TIME, FIXED_CURRENT_TIME.minusSeconds(1)));
        var marketSnapshot = marketSnapshotRepository.findAll().getFirst();
        assertEquals(FIXED_CURRENT_TIME.minusSeconds(1).truncatedTo(ChronoUnit.MICROS), marketSnapshot.getSourceAt());
        assertEquals("2026-11-06 01:02:03.123456", jdbcTemplate.queryForObject(
                "select date_format(observed_at, '%Y-%m-%d %H:%i:%s.%f') from market_snapshots", String.class));
        assertEquals("+00:00", jdbcTemplate.queryForObject("select @@session.time_zone", String.class));
    }

    /**
     * 観点：B-DB-13 — DECIMALの最大桁境界を損失なく保存する。
     * 前提・入力：Bid/Askとも整数18桁・小数12桁の最大値。
     * 操作：成功保存してBid値を読み直す。
     * 期待結果：BigDecimal.equalsで入力値と一致する。Ask側の全境界を網羅した試験ではない。
     */
    @DisplayName("B-DB-13：DECIMALの最大桁境界を損失なく保存する")
    @Test void preservesDecimalBoundaryExactly() {
        var maximumDecimalValue = new BigDecimal("999999999999999999.999999999999");
        collectionPersistenceService.completeSuccessfully(collectionPersistenceService.startBtcUsd(), withPrices(maximumDecimalValue, maximumDecimalValue));
        assertEquals(maximumDecimalValue, marketSnapshotRepository.findAll().getFirst().getBidPrice());
    }

    /**
     * 観点：B-DB-14 — 保存精度外の値を丸めず拒否する。
     * 前提・入力：小数13桁の有効数字、または整数19桁の値をBid/Askへ指定。
     * 操作：成功保存を試みる。
     * 期待結果：例外、RUNNINGを維持し観測0件。保存境界の丸め禁止を確認する。
     */
    @DisplayName("B-DB-14：保存精度外の値を丸めず拒否する")
    @ParameterizedTest(name = "[{index}] {argumentsWithNames}") @ValueSource(strings = {"1.0000000000001", "1000000000000000000"})
    void rejectsUnrepresentablePrecisionWithoutRounding(String priceText) {
        Long collectionRunId = collectionPersistenceService.startBtcUsd();
        assertThrows(RuntimeException.class, () -> collectionPersistenceService.completeSuccessfully(collectionRunId,
                withPrices(new BigDecimal(priceText), new BigDecimal(priceText))));
        assertEquals(CollectionRunStatus.RUNNING, collectionRunRepository.findById(collectionRunId).orElseThrow().getStatus());
        assertEquals(0, marketSnapshotRepository.count());
    }

    /**
     * 観点：B-DB-15 — 実MySQLがFK・一意・CHECK制約を適用する。
     * 前提・入力：正常な親子行を1組保存済み。
     * 操作：直接SQLで同一run重複、親なし、価格0、負の数量、未知ステータスを順に試す。
     * 期待結果：各操作は例外となり正常観測1件を維持。SQLStateごとの例外分類や制約の全組合せまではassertしていない。
     */
    @DisplayName("B-DB-15：実MySQLがFK・一意・CHECK制約を適用する")
    @Test void enforcesForeignKeyUniqueAndCheckConstraints() {
        Long collectionRunId = collectionPersistenceService.startBtcUsd();
        collectionPersistenceService.completeSuccessfully(collectionRunId, marketObservation());
        String insertSnapshotSql = "insert into market_snapshots(run_id,bid_price,bid_size,ask_price,ask_size,observed_at) values (?, ?, 1, 101, 1, '2026-11-06 00:00:00')";
        assertThrows(RuntimeException.class, () -> jdbcTemplate.update(insertSnapshotSql, collectionRunId, 100)); // 同一runの重複
        assertThrows(RuntimeException.class, () -> jdbcTemplate.update(insertSnapshotSql, -1L, 100)); // 親なし
        Long otherCollectionRunId = collectionPersistenceService.startBtcUsd();
        assertThrows(RuntimeException.class, () -> jdbcTemplate.update(insertSnapshotSql, otherCollectionRunId, 0)); // 不正価格
        assertThrows(RuntimeException.class, () -> jdbcTemplate.update("update market_snapshots set bid_size = -1"));
        assertThrows(RuntimeException.class, () -> jdbcTemplate.update("update collection_runs set status = 'UNKNOWN' where id = ?", otherCollectionRunId));
        assertEquals(1, marketSnapshotRepository.count());
    }

    /**
     * 観点：B-DB-16 — 収集進行から実Mapper・実DB保存までを接続する。
     * 前提・入力：Clientだけ正常JSON由来DTOを返すモック。他は実Bean、実MySQL。
     * 操作：collectBtcUsdを呼ぶ。Client呼出し中にTxなし・RUNNINGがDBに見えることも確認。
     * 期待結果：履歴SUCCESS・観測1件。実ネットワークやScheduler・画面を含むST/E2Eではない。
     */
    @DisplayName("B-DB-16：収集進行から実Mapper・実DB保存までを接続する")
    @Test void collectsIntoDatabaseUsingMockedHttpBoundary() throws Exception {
        var coinbaseBookResponse = new com.fasterxml.jackson.databind.ObjectMapper().readValue("""
            {"bids":[["100","1",1]],"asks":[["101","2",1]],"auction_mode":false}
            """, CoinbaseBookResponse.class);
        when(coinbaseClient.fetchBtcUsd()).thenAnswer(invocation -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1, jdbcTemplate.queryForObject("select count(*) from collection_runs where status='RUNNING'", Integer.class));
            return new CoinbaseBookResponseWithReceivedAt(coinbaseBookResponse, FIXED_CURRENT_TIME);
        });
        collectionService.collectBtcUsd();
        assertEquals(CollectionRunStatus.SUCCESS, collectionRunRepository.findAll().getFirst().getStatus());
        assertEquals(1, marketSnapshotRepository.count());
    }

    /**
     * 観点：B-DB-17 — 実トランザクション内からの収集を拒否する。
     * 前提・入力：TransactionTemplateが実DBのTxを開始する。
     * 操作：その中からcollectBtcUsdを呼ぶ。
     * 期待結果：IllegalStateException。開始履歴0件、Client呼出し0回。
     */
    @DisplayName("B-DB-17：実トランザクション内からの収集を拒否する")
    @Test void refusesCollectionInsideExistingTransaction() {
        var transactionTemplate = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> transactionTemplate.executeWithoutResult(status -> collectionService.collectBtcUsd()));
        assertEquals(0, collectionRunRepository.count());
        verifyNoInteractions(coinbaseClient);
    }

    /**
     * 観点D-DB-01。前提：複数市場のRUNNING2件と全確定状態、SUCCESSには観測あり。
     * 操作：回収を実Txで確定し再検索。期待：RUNNINGだけINTERRUPTED・検出時刻6桁・終了null・理由設定。
     * 確定行全列と観測全列は不変。実停止時刻を推測して保存しない。
     */
    @DisplayName("D-DB-01：複数市場のRUNNINGのみ回収し、確定行・観測を変えない")
    @Test void recoversOnlyRunningAcrossMarketsWithoutChangingCompletedData() {
        Long successRunId = collectionPersistenceService.startBtcUsd();
        collectionPersistenceService.completeSuccessfully(successRunId, marketObservation());
        collectionPersistenceService.completeAsFailedIfRunning(collectionPersistenceService.startBtcUsd(), "TIMEOUT");
        collectionPersistenceService.completeAsSkipped(collectionPersistenceService.startBtcUsd(), "AUCTION_MODE");
        Long oldInterruptedRunId = collectionPersistenceService.startBtcUsd();
        jdbcTemplate.update("update collection_runs set status='INTERRUPTED', result_code='INTERRUPTED', "
                + "interruption_detected_at='2026-01-01 00:00:00' where id=?", oldInterruptedRunId);
        var completedRowsBefore = jdbcTemplate.queryForList("select * from collection_runs order by id");
        var snapshotRowsBefore = jdbcTemplate.queryForList("select * from market_snapshots order by id");
        Long firstRunningRunId = collectionPersistenceService.startBtcUsd();
        Long secondRunningRunId = collectionPersistenceService.startBtcUsd();
        jdbcTemplate.update("update collection_runs set exchange='OTHER', symbol='ETH-USD' where id=?", secondRunningRunId);
        assertEquals(2, collectionPersistenceService.recoverInterruptedRuns());
        for (Long collectionRunId : java.util.List.of(firstRunningRunId, secondRunningRunId)) {
            var collectionRun = collectionRunRepository.findById(collectionRunId).orElseThrow();
            assertEquals(CollectionRunStatus.INTERRUPTED, collectionRun.getStatus());
            assertEquals(FIXED_CURRENT_TIME.truncatedTo(ChronoUnit.MICROS), collectionRun.getInterruptionDetectedAt());
            assertEquals(FIXED_CURRENT_TIME.truncatedTo(ChronoUnit.MICROS), collectionRun.getStartedAt());
            assertNull(collectionRun.getFinishedAt());
            assertEquals("INTERRUPTED", collectionRun.getResultCode());
        }
        assertEquals("OTHER", collectionRunRepository.findById(secondRunningRunId).orElseThrow().getExchange());
        assertEquals("ETH-USD", collectionRunRepository.findById(secondRunningRunId).orElseThrow().getSymbol());
        assertEquals(completedRowsBefore, jdbcTemplate.queryForList("select * from collection_runs where id < ? order by id", firstRunningRunId));
        assertEquals(snapshotRowsBefore, jdbcTemplate.queryForList("select * from market_snapshots order by id"));
    }

    /** 観点D-DB-02。前提：空DB/回収済みDB。操作：回収を繰返す。期待：0件、二重回収で検出時刻を変更しない。 */
    @DisplayName("D-DB-02：0件と再回収を安全に扱う")
    @Test void recoveryIsIdempotentAndHandlesEmptyDatabase() {
        assertEquals(0, collectionPersistenceService.recoverInterruptedRuns());
        collectionPersistenceService.startBtcUsd();
        assertEquals(1, collectionPersistenceService.recoverInterruptedRuns());
        var rowsAfterRecovery = jdbcTemplate.queryForList("select * from collection_runs");
        assertEquals(0, collectionPersistenceService.recoverInterruptedRuns());
        assertEquals(rowsAfterRecovery, jdbcTemplate.queryForList("select * from collection_runs"));
    }

    /** 観点D-DB-03。前提：開始行はcommit済み。操作：回収を含むTxを例外でrollback。期待：全行RUNNINGを維持。 */
    @DisplayName("D-DB-03：回収Txが失敗すると一括更新を取り消す")
    @Test void rollsBackRecoveryAtomically() {
        collectionPersistenceService.startBtcUsd();
        collectionPersistenceService.startBtcUsd();
        var transactionTemplate = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> transactionTemplate.executeWithoutResult(transactionStatus -> {
            assertEquals(2, collectionPersistenceService.recoverInterruptedRuns());
            throw new IllegalStateException("failure after bulk update");
        }));
        assertEquals(2, jdbcTemplate.queryForObject("select count(*) from collection_runs where status='RUNNING' "
                + "and interruption_detected_at is null and result_code is null", Integer.class));
    }

    /**
     * 観点D-DB-04。前提：実Springの保存ServiceプロキシとRUNNING、Schedulerだけモック。
     * 操作：Runnerを実行。期待：start呼出し時はTx外・別読取りでINTERRUPTEDが見える。
     * commit後に開始する境界の試験で、実定期HTTPを開始するSTではない。
     */
    @DisplayName("D-DB-04：回収をコミットしてからSchedulerを開始する")
    @Test void runnerStartsOnlyAfterRealRecoveryCommit() {
        collectionPersistenceService.startBtcUsd();
        var collectionScheduler = mock(com.example.cryptomonitor.collection.scheduler.CollectionScheduler.class);
        doAnswer(invocation -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1, jdbcTemplate.queryForObject("select count(*) from collection_runs where status='INTERRUPTED'", Integer.class));
            return null;
        }).when(collectionScheduler).start();
        new com.example.cryptomonitor.collection.startup.CollectionStartupRunner(collectionPersistenceService, collectionScheduler,
                new com.example.cryptomonitor.collection.config.CollectionSchedulingProperties(true,
                        java.time.Duration.ofSeconds(60), java.time.Duration.ofSeconds(30))).run(null);
        verify(collectionScheduler).start();
        verifyNoInteractions(coinbaseClient);
    }

    /**
     * 観点D-DB-05。前提：専用MySQLへ接続する実HikariDataSourceと実Spring TaskScheduler。
     * 操作：モック収集をLatchで止めてContext.closeを開始し、その後収集中に実SELECTを実行する。
     * 期待：停止待機中もDB接続を利用でき、収集復帰後にDataSourceが閉じる。
     * 別コンテキスト/別接続プールを使い、他ケース用Springコンテキストは閉じない。外部HTTPは呼ばない。
     */
    @DisplayName("D-DB-05：Spring停止待機中の実MySQL接続を維持し、収集完了後にプールを閉じる")
    @Test void keepsRealDataSourceAvailableUntilCollectionCompletes() throws Exception {
        var collectionStarted = new java.util.concurrent.CountDownLatch(1);
        var allowCollectionCompletion = new java.util.concurrent.CountDownLatch(1);
        var stopCallbackRegistered = new java.util.concurrent.CountDownLatch(1);
        var databaseReadResult = new java.util.concurrent.atomic.AtomicReference<Integer>();
        var databaseReadFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var shutdownCollectionService = mock(CollectionService.class);
        var hikariConfiguration = new com.zaxxer.hikari.HikariConfig();
        hikariConfiguration.setJdbcUrl(MYSQL_CONTAINER.getJdbcUrl());
        hikariConfiguration.setUsername(MYSQL_CONTAINER.getUsername());
        hikariConfiguration.setPassword(MYSQL_CONTAINER.getPassword());
        hikariConfiguration.setMaximumPoolSize(1);
        hikariConfiguration.setPoolName("shutdown-lifecycle-test");
        var shutdownDataSource = new com.zaxxer.hikari.HikariDataSource(hikariConfiguration);
        var shutdownJdbcTemplate = new JdbcTemplate(shutdownDataSource);
        doAnswer(invocation -> {
            collectionStarted.countDown();
            try {
                if (!allowCollectionCompletion.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IllegalStateException("collection completion was not released");
                }
                databaseReadResult.set(shutdownJdbcTemplate.queryForObject("select 1", Integer.class));
            } catch (Throwable throwable) {
                databaseReadFailure.set(throwable);
                throw throwable;
            }
            return null;
        }).when(shutdownCollectionService).collectBtcUsd();
        var shutdownContext = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        shutdownContext.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "shutdown-database-test", java.util.Map.of("market-monitor.collection.shutdown-wait", "3s")));
        shutdownContext.getBeanFactory().addBeanPostProcessor(new org.springframework.beans.factory.config.BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskScheduler)) return bean;
                var observedTaskScheduler = spy(taskScheduler);
                doAnswer(invocation -> {
                    Object invocationResult = invocation.callRealMethod();
                    stopCallbackRegistered.countDown();
                    return invocationResult;
                }).when(observedTaskScheduler).stop(any(Runnable.class));
                return observedTaskScheduler;
            }
        });
        shutdownContext.registerBean("shutdownDataSource", com.zaxxer.hikari.HikariDataSource.class,
                () -> shutdownDataSource, beanDefinition -> beanDefinition.setDestroyMethodName("close"));
        shutdownContext.registerBean(CollectionService.class, () -> shutdownCollectionService);
        shutdownContext.register(com.example.cryptomonitor.collection.config.CollectionSchedulingConfiguration.class,
                com.example.cryptomonitor.collection.scheduler.CollectionScheduler.class);
        java.util.concurrent.CompletableFuture<Void> closingFuture = null;
        try {
            shutdownContext.refresh();
            shutdownContext.getBean(com.example.cryptomonitor.collection.scheduler.CollectionScheduler.class).start();
            assertTrue(collectionStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            closingFuture = java.util.concurrent.CompletableFuture.runAsync(shutdownContext::close);
            assertTrue(stopCallbackRegistered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(shutdownDataSource.isClosed());
            assertFalse(closingFuture.isDone());
            allowCollectionCompletion.countDown();
            closingFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertNull(databaseReadFailure.get());
            assertEquals(1, databaseReadResult.get());
            assertTrue(shutdownDataSource.isClosed());
            verify(shutdownCollectionService).collectBtcUsd();
        } finally {
            allowCollectionCompletion.countDown();
            if (closingFuture != null) closingFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
            shutdownContext.close();
            shutdownDataSource.close();
        }
    }

    /** 保存試験の正常観測：Bid小数12桁、数量最小桁、固定受信時刻、sourceAt=null。 */
    private MarketObservation marketObservation() {
        return withPrices(new BigDecimal("100.123456789012"), new BigDecimal("101"));
    }
    /** 指定価格だけを差し替え、他条件を固定する。負値・精度外など保存境界を直接検査する場合にも使う。 */
    private MarketObservation withPrices(BigDecimal bid, BigDecimal ask) {
        return new MarketObservation("COINBASE_EXCHANGE", "BTC-USD", bid,
                new BigDecimal("0.000000000001"), ask, BigDecimal.ONE, FIXED_CURRENT_TIME, null);
    }
}
