package com.example.cryptomonitor.common.api;

import com.example.cryptomonitor.coinbase.client.CoinbaseClient;
import com.example.cryptomonitor.collection.entity.CollectionRun;
import com.example.cryptomonitor.collection.entity.CollectionRunStatus;
import com.example.cryptomonitor.collection.repository.CollectionRunRepository;
import com.example.cryptomonitor.collection.service.CollectionRunQueryService;
import com.example.cryptomonitor.market.entity.MarketSnapshot;
import com.example.cryptomonitor.market.model.MarketObservation;
import com.example.cryptomonitor.market.repository.MarketSnapshotRepository;
import com.example.cryptomonitor.market.service.LatestQuoteQueryService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 試験区分：機能CのDB/API結合確認。実MySQL・Flyway・JPA・検索Service・Controllerを使う。
 * 実行：Docker Desktop起動後、mvnw.cmd -Pmysql-it verify。専用query_api_testをクラス単位で作成・破棄する。
 * 各ケース前に専用DBだけを初期化。Clientはモックで、実Coinbase・開発DBは使わない。
 * 未保証：ブラウザST、実接続断の故障注入、負荷性能は対象外。コメント/Sourceを個別ケース仕様とする。
 */
@DisplayName("機能C / 実MySQL結合：最新・履歴検索とAPI連携")
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@ActiveProfiles("mysql-it")
@Testcontainers
class QueryApiIT {
    @Container
    static final MySQLContainer<?> MYSQL_CONTAINER = new MySQLContainer<>("mysql:8.4.13")
            .withDatabaseName("query_api_test").withUsername("test_app").withPassword("test_only_password")
            .withUrlParam("connectionTimeZone", "UTC").withUrlParam("forceConnectionTimeZoneToSession", "true");

    /** コンテナの接続先だけを登録し、localの.envや開発用3306への依存を防ぐ。 */
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry propertyRegistry) {
        propertyRegistry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
        propertyRegistry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        propertyRegistry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
    }

    @Autowired com.example.cryptomonitor.market.service.QuoteHistoryQueryService quoteHistoryQueryService;
    @MockitoBean(name = "marketQueryClock") java.time.Clock marketQueryClock;
    @Autowired LatestQuoteQueryService latestQuoteQueryService;
    @Autowired CollectionRunQueryService collectionRunQueryService;
    @Autowired CollectionRunRepository collectionRunRepository;
    @Autowired MarketSnapshotRepository marketSnapshotRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired MockMvc mockMvc;
    @MockitoBean CoinbaseClient coinbaseClient;
    private static final Instant BASE_OBSERVED_AT = Instant.parse("2026-11-08T01:02:03.123456Z");

    /** 接続DB名を確認してから子→親を削除する。テストを自動rollbackで包まず、実Txの結果を読む。 */
    @BeforeEach void prepareIsolatedDatabase() {
        org.mockito.Mockito.when(marketQueryClock.instant()).thenReturn(BASE_OBSERVED_AT.plusSeconds(86400));
        assertEquals("query_api_test", jdbcTemplate.queryForObject("select database()", String.class));
        jdbcTemplate.update("delete from market_snapshots");
        jdbcTemplate.update("delete from collection_runs");
    }

    /** 共通期待：検索を行っても外部取得を開始しない。SQL更新の有無はC-DB-10でも実DB検査する。 */
    @AfterEach void verifiesNoExternalCollection() { verifyNoInteractions(coinbaseClient); }

    /**
     * 観点：C-DB-01。前提：同じ対象の新旧観測、後からINSERTした古い時刻、同時刻2件、別対象2組。
     * 操作：条件なしの最新検索。期待：時刻優先・同時刻ID優先で対象ごと1件、市場/ペア昇順。
     */
    @Test @DisplayName("C-DB-01：時刻とIDで対象ごとの最新を一意に選ぶ")
    void selectsLatestPerMarketByTimeThenId() {
        saveObservation("COINBASE_EXCHANGE", "BTC-USD", BASE_OBSERVED_AT, CollectionRunStatus.SUCCESS);
        saveObservation("COINBASE_EXCHANGE", "BTC-USD", BASE_OBSERVED_AT.plusSeconds(60), CollectionRunStatus.SUCCESS);
        saveObservation("COINBASE_EXCHANGE", "BTC-USD", BASE_OBSERVED_AT.minusSeconds(60), CollectionRunStatus.SUCCESS);
        Long expectedBitcoinId = saveObservation("COINBASE_EXCHANGE", "BTC-USD", BASE_OBSERVED_AT.plusSeconds(60), CollectionRunStatus.SUCCESS);
        Long expectedEtherId = saveObservation("COINBASE_EXCHANGE", "ETH-USD", BASE_OBSERVED_AT, CollectionRunStatus.SUCCESS);
        Long expectedOtherExchangeId = saveObservation("OTHER_EXCHANGE", "BTC-USD", BASE_OBSERVED_AT, CollectionRunStatus.SUCCESS);
        var latestItems = latestQuoteQueryService.findLatestQuotes(null, null).items();
        assertEquals(List.of(expectedBitcoinId.toString(), expectedEtherId.toString(), expectedOtherExchangeId.toString()),
                latestItems.stream().map(item -> item.id()).toList());
    }

    /** 観点：C-DB-02。前提：3対象。操作：省略/片方/両方の条件で検索。期待：AND条件の件数と全行の市場一致。 */
    @DisplayName("C-DB-02：最新価格の任意フィルターをDBで適用する")
    @ParameterizedTest(name = "[{index}] exchange={0}, symbol={1} → {2}件")
    @CsvSource(value = {"NONE,NONE,3", "COINBASE_EXCHANGE,NONE,2", "NONE,BTC-USD,2", "COINBASE_EXCHANGE,BTC-USD,1"}, nullValues = "NONE")
    void filtersLatestObservations(String exchange, String symbol, int expectedCount) {
        prepareThreeMarkets();
        var latestItems = latestQuoteQueryService.findLatestQuotes(exchange, symbol).items();
        assertEquals(expectedCount, latestItems.size());
        assertTrue(latestItems.stream().allMatch(item -> (exchange == null || exchange.equals(item.exchange()))
                && (symbol == null || symbol.equals(item.symbol()))));
    }

    /**
     * 観点：C-DB-03。前提：観測を持つ履歴をSQLでSUCCESS以外へ変更（正規保存では作らない試験専用状態）。
     * 操作：最新検索。期待：状態による除外を行わず、その観測を返す。機能Bの保存ルール変更ではない。
     */
    @DisplayName("C-DB-03：最新検索にSUCCESS条件を追加しない")
    @ParameterizedTest(name = "[{index}] 観測に紐づく実行状態={0}")
    @EnumSource(value = CollectionRunStatus.class, names = {"RUNNING", "FAILED", "SKIPPED", "INTERRUPTED"})
    void doesNotFilterSnapshotsByRunStatus(CollectionRunStatus status) {
        Long expectedSnapshotId = saveObservation("COINBASE_EXCHANGE", "BTC-USD", BASE_OBSERVED_AT, status);
        assertEquals(expectedSnapshotId.toString(), latestQuoteQueryService.findLatestQuotes(null, null).items().getFirst().id());
    }

    /** 観点：C-DB-04。前提：保存観測の後に観測なしのFAILED実行。操作：最新検索。期待：以前の価格・観測時刻を維持。 */
    @Test @DisplayName("C-DB-04：後続の収集失敗で保存済み最新価格を消さない")
    void keepsPreviousQuoteAfterLaterFailedCollection() {
        Long snapshotId = saveObservation("COINBASE_EXCHANGE", "BTC-USD", BASE_OBSERVED_AT, CollectionRunStatus.SUCCESS);
        var failedRun = collectionRunRepository.saveAndFlush(CollectionRun.start(
                "COINBASE_EXCHANGE", "BTC-USD", BASE_OBSERVED_AT.plusSeconds(60)));
        jdbcTemplate.update("update collection_runs set status='FAILED', result_code='TIMEOUT', finished_at=? where id=?",
                Timestamp.from(BASE_OBSERVED_AT.plusSeconds(65)), failedRun.getId());
        var latestItem = latestQuoteQueryService.findLatestQuotes(null, null).items().getFirst();
        assertEquals(snapshotId.toString(), latestItem.id());
        assertEquals(BASE_OBSERVED_AT, latestItem.observedAt());
    }

    /** 観点：C-DB-05。前提：空DB。操作：両GET。期待：Spring/JPA実接続下でも200/items=[]。 */
    @DisplayName("C-DB-05：実DBの該当なしを空配列で返す")
    @ParameterizedTest(name = "[{index}] GET {0}")
    @ValueSource(strings = {"/api/v1/quotes/latest", "/api/v1/collection-runs"})
    void returnsNoDataFromRealDatabase(String apiPath) throws Exception {
        mockMvc.perform(get(apiPath)).andExpect(status().isOk()).andExpect(content().json("{\"items\":[]}"));
    }

    /** 観点：C-DB-06。前提：他市場にのみデータあり。操作：対応済みだがデータのないBTC-USDを検索。期待：空、404にしない。 */
    @Test @DisplayName("C-DB-06：対応対象でもデータがなければ空配列")
    void returnsEmptyWhenOnlyDifferentMarketExists() {
        saveObservation("OTHER_EXCHANGE", "ETH-USD", BASE_OBSERVED_AT, CollectionRunStatus.SUCCESS);
        assertTrue(latestQuoteQueryService.findLatestQuotes("COINBASE_EXCHANGE", "BTC-USD").items().isEmpty());
        assertTrue(collectionRunQueryService.findCollectionRuns("COINBASE_EXCHANGE", "BTC-USD", null).items().isEmpty());
    }

    /** 観点：C-DB-07。前提：開始時刻が新しい行、同時刻2行、後から作成した古い行。操作：履歴検索。期待：時刻→ID降順。 */
    @Test @DisplayName("C-DB-07：履歴の開始時刻とIDの降順を守る")
    void sortsRunHistoryByStartTimeThenId() {
        var firstRun = collectionRunRepository.saveAndFlush(CollectionRun.start("COINBASE_EXCHANGE", "BTC-USD", BASE_OBSERVED_AT));
        var secondRun = collectionRunRepository.saveAndFlush(CollectionRun.start("COINBASE_EXCHANGE", "BTC-USD", BASE_OBSERVED_AT));
        var newestRun = collectionRunRepository.saveAndFlush(CollectionRun.start("COINBASE_EXCHANGE", "BTC-USD", BASE_OBSERVED_AT.plusSeconds(1)));
        var olderRun = collectionRunRepository.saveAndFlush(CollectionRun.start("COINBASE_EXCHANGE", "BTC-USD", BASE_OBSERVED_AT.minusSeconds(1)));
        assertEquals(List.of(newestRun.getId().toString(), secondRun.getId().toString(), firstRun.getId().toString(), olderRun.getId().toString()),
                collectionRunQueryService.findCollectionRuns(null, null, null).items().stream().map(item -> item.id()).toList());
    }

    /**
     * 観点：C-DB-08。前提：同時刻の105実行。操作：limit省略・境界1/100・指定20で検索。
     * 期待：指定/既定件数、先頭は最大ID。SQLは1回で総件数COUNTを行わない。
     */
    @DisplayName("C-DB-08：件数制限をDBに適用し余分なCOUNTをしない")
    @ParameterizedTest(name = "[{index}] limit={0}")
    @NullSource @ValueSource(strings = {"1", "20", "100"})
    void limitsHistoryAtDatabase(String limit) {
        var preparedRuns = collectionRunRepository.saveAllAndFlush(IntStream.range(0, 105)
                .mapToObj(index -> CollectionRun.start("COINBASE_EXCHANGE", "BTC-USD", BASE_OBSERVED_AT)).toList());
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        var historyItems = collectionRunQueryService.findCollectionRuns(null, null, limit).items();
        assertEquals(limit == null ? 100 : Integer.parseInt(limit), historyItems.size());
        assertEquals(preparedRuns.getLast().getId().toString(), historyItems.getFirst().id());
        assertEquals(1, statistics.getPrepareStatementCount());
    }

    /** 観点：C-DB-09。前提：3対象の実行履歴。操作：各組合せで検索。期待：同じ条件で絞り、異なる市場を混ぜない。 */
    @DisplayName("C-DB-09：実行履歴の任意フィルターをDBで適用する")
    @ParameterizedTest(name = "[{index}] exchange={0}, symbol={1} → {2}件")
    @CsvSource(value = {"NONE,NONE,3", "COINBASE_EXCHANGE,NONE,2", "NONE,BTC-USD,2", "COINBASE_EXCHANGE,BTC-USD,1"}, nullValues = "NONE")
    void filtersRunHistory(String exchange, String symbol, int expectedCount) {
        prepareThreeMarkets();
        var historyItems = collectionRunQueryService.findCollectionRuns(exchange, symbol, "100").items();
        assertEquals(expectedCount, historyItems.size());
        assertTrue(historyItems.stream().allMatch(item -> (exchange == null || exchange.equals(item.exchange()))
                && (symbol == null || symbol.equals(item.symbol()))));
    }

    /**
     * 観点：C-DB-10。前提：実DBに3対象の正常観測。操作：両GET。
     * 期待：200、表の全行の値が前後で一致し、HibernateのINSERT/UPDATE/DELETE件数0。
     */
    @Test @DisplayName("C-DB-10：API参照で実DBを更新しない")
    void doesNotMutateDataWhenReadingApis() throws Exception {
        prepareThreeMarkets();
        var beforeRuns = jdbcTemplate.queryForList("select * from collection_runs order by id");
        var beforeSnapshots = jdbcTemplate.queryForList("select * from market_snapshots order by id");
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        mockMvc.perform(get("/api/v1/quotes/latest")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/collection-runs")).andExpect(status().isOk());
        assertEquals(beforeRuns, jdbcTemplate.queryForList("select * from collection_runs order by id"));
        assertEquals(beforeSnapshots, jdbcTemplate.queryForList("select * from market_snapshots order by id"));
        assertEquals(0, statistics.getEntityInsertCount());
        assertEquals(0, statistics.getEntityUpdateCount());
        assertEquals(0, statistics.getEntityDeleteCount());
    }

    /** 観点：C-DB-11。前提：3対象の観測。操作：最新検索とDTO変換。期待：SQL1回・関連の追加fetch0、DTO3件。 */
    @Test @DisplayName("C-DB-11：JOIN取得でN+1 SELECTを発生させない")
    void fetchesRunAssociationInSingleQuery() {
        prepareThreeMarkets();
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        assertEquals(3, latestQuoteQueryService.findLatestQuotes(null, null).items().size());
        assertEquals(1, statistics.getPrepareStatementCount());
        assertEquals(0, statistics.getEntityFetchCount());
    }

    /** 観点：C-DB-12。前提：全5状態の履歴とTIMEOUT理由。操作：実HTTP処理をMockMvcで呼ぶ。期待：5件・UTC文字列・FAILED説明。 */
    @Test @DisplayName("C-DB-12：実DBの全状態をJSON応答へ接続する")
    void returnsAllRunStatesThroughController() throws Exception {
        for (var status : CollectionRunStatus.values()) {
            saveObservation("COINBASE_EXCHANGE", "BTC-USD", BASE_OBSERVED_AT, status);
        }
        String responseBody = mockMvc.perform(get("/api/v1/collection-runs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(5))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var responseJson = new com.fasterxml.jackson.databind.ObjectMapper().readTree(responseBody);
        for (var historyItem : responseJson.get("items")) {
            assertTrue(historyItem.get("id").isTextual());
            assertTrue(historyItem.get("startedAt").asText().endsWith("Z"));
            if (historyItem.get("status").asText().equals("SUCCESS")) {
                assertTrue(historyItem.get("resultCode").isNull());
                assertTrue(historyItem.get("resultDescription").isNull());
            }
            if (historyItem.get("status").asText().equals("FAILED")) {
                assertEquals("外部APIの応答待ちがタイムアウトしました", historyItem.get("resultDescription").asText());
            }
        }
    }

    /** E-HDB-01：前提：期間境界・同時刻2件・対象外市場。操作：実SQL/API。期待：半開区間・ID昇順・全期間統計。 */
    @Test @DisplayName("E-HDB-01：実MySQLで期間・市場・同時刻順と統計を確認する")
    void queriesHistoryBoundariesAndStatistics() throws Exception {
        saveObservation("COINBASE_EXCHANGE","BTC-USD",BASE_OBSERVED_AT.minusNanos(1000),CollectionRunStatus.SUCCESS);
        Long first=saveObservation("COINBASE_EXCHANGE","BTC-USD",BASE_OBSERVED_AT,CollectionRunStatus.SUCCESS);
        Long second=saveObservation("COINBASE_EXCHANGE","BTC-USD",BASE_OBSERVED_AT,CollectionRunStatus.SUCCESS);
        saveObservation("COINBASE_EXCHANGE","BTC-USD",BASE_OBSERVED_AT.plusSeconds(86400),CollectionRunStatus.SUCCESS);
        saveObservation("OTHER_EXCHANGE","BTC-USD",BASE_OBSERVED_AT,CollectionRunStatus.SUCCESS);
        jdbcTemplate.update("update market_snapshots set ask_price=bid_price+2 where id=?",second);
        var response=quoteHistoryQueryService.findQuoteHistory(null,null);
        assertEquals(List.of(first.toString(),second.toString()),response.items().stream().map(item->item.id()).toList());
        assertEquals(2,response.spreadStatistics().observationCount());
        assertEquals("1.5500000000000000",response.spreadStatistics().averageSpread());
        assertEquals("1.100000000000",response.spreadStatistics().minimumSpread());
        assertEquals("2.000000000000",response.spreadStatistics().maximumSpread());
        mockMvc.perform(get("/api/v1/quotes/history")).andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].spread").value("1.100000000000"));
    }

    /** E-HDB-02：前提2001件、最古の1件だけ大きな差。操作：検索。期待：一覧から除外されても全期間集計には含む。 */
    @Test @DisplayName("E-HDB-02：一覧2000件制限と全期間2001件の集計・参照専用を実DBで確認")
    void aggregatesBeyondHistoryLimit() {
        for(int i=0;i<2001;i++) {
            jdbcTemplate.update("insert into collection_runs(exchange,symbol,status,started_at) values('COINBASE_EXCHANGE','BTC-USD','RUNNING',?)",Timestamp.from(BASE_OBSERVED_AT.plusSeconds(i)));
            // 接続プールをまたぐLAST_INSERT_IDへ依存しないよう、専用DBの一意な開始時刻で親IDを取得する。
            Long runId=jdbcTemplate.queryForObject("select id from collection_runs where started_at=?",Long.class,Timestamp.from(BASE_OBSERVED_AT.plusSeconds(i)));
            jdbcTemplate.update("insert into market_snapshots(run_id,bid_price,bid_size,ask_price,ask_size,observed_at) values(?,100,1,?,1,?)",runId,i==0?110:101,Timestamp.from(BASE_OBSERVED_AT.plusSeconds(i)));
        }
        var statistics=entityManagerFactory.unwrap(SessionFactory.class).getStatistics();statistics.clear();
        var response=quoteHistoryQueryService.findQuoteHistory(null,null);
        assertEquals(2000,response.items().size());assertTrue(response.truncated());
        assertEquals(2001,response.spreadStatistics().observationCount());
        assertEquals("10.000000000000",response.spreadStatistics().maximumSpread());
        assertEquals(0,new BigDecimal("2010").divide(new BigDecimal("2001"),16,java.math.RoundingMode.HALF_UP).compareTo(new BigDecimal(response.spreadStatistics().averageSpread())));
        assertEquals(2,statistics.getPrepareStatementCount());
        assertEquals(0,statistics.getEntityInsertCount());assertEquals(0,statistics.getEntityUpdateCount());assertEquals(0,statistics.getEntityDeleteCount());
    }

    /** E-HDB-03：前提0件→1件。操作：API/Service。期待：0件null統計、1件の平均・最大・最小は同額。 */
    @Test @DisplayName("E-HDB-03：実DBの0件・1件と非SUCCESS観測を扱う")
    void handlesEmptyAndSingleHistory() throws Exception {
        mockMvc.perform(get("/api/v1/quotes/history")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
            .andExpect(jsonPath("$.spreadStatistics.observationCount").value(0)).andExpect(jsonPath("$.spreadStatistics.averageSpread").isEmpty());
        saveObservation("COINBASE_EXCHANGE","BTC-USD",BASE_OBSERVED_AT,CollectionRunStatus.FAILED);
        var response=quoteHistoryQueryService.findQuoteHistory(null,null);
        assertEquals(1,response.items().size());assertFalse(response.truncated());
        assertEquals("1.1000000000000000",response.spreadStatistics().averageSpread());
        assertEquals(response.spreadStatistics().minimumSpread(),response.spreadStatistics().maximumSpread());
    }

    /** 共通データ：Coinbase/BTC、Coinbase/ETH、別取引所/BTC。追加対象は検索試験専用で、実収集対象を増やさない。 */
    private void prepareThreeMarkets() {
        saveObservation("COINBASE_EXCHANGE", "BTC-USD", BASE_OBSERVED_AT, CollectionRunStatus.SUCCESS);
        saveObservation("COINBASE_EXCHANGE", "ETH-USD", BASE_OBSERVED_AT, CollectionRunStatus.SUCCESS);
        saveObservation("OTHER_EXCHANGE", "BTC-USD", BASE_OBSERVED_AT, CollectionRunStatus.SUCCESS);
    }

    /**
     * 読取り試験のfixtureを直接用意する。状態がSUCCESS以外でも観測を作るのは、検索の状態非依存を検査するため。
     * 本番の収集/保存Serviceを変更・迂回して不正状態を許可する実装ではない。変更先は専用テストDBだけ。
     */
    private Long saveObservation(String exchange, String symbol, Instant observedAt, CollectionRunStatus status) {
        var collectionRun = collectionRunRepository.saveAndFlush(CollectionRun.start(exchange, symbol, observedAt));
        var marketObservation = new MarketObservation(exchange, symbol, new BigDecimal("100.100000000000"),
                new BigDecimal("0.000000000001"), new BigDecimal("101.200000000000"), BigDecimal.ONE, observedAt, null);
        var marketSnapshot = marketSnapshotRepository.saveAndFlush(MarketSnapshot.from(collectionRun, marketObservation));
        Timestamp finishedAt = status == CollectionRunStatus.RUNNING || status == CollectionRunStatus.INTERRUPTED ? null : Timestamp.from(observedAt.plusSeconds(1));
        Timestamp interruptionDetectedAt = status == CollectionRunStatus.INTERRUPTED ? Timestamp.from(observedAt.plusSeconds(2)) : null;
        String resultCode = switch (status) {
            case SUCCESS, RUNNING -> null;
            case FAILED -> "TIMEOUT";
            case SKIPPED -> "EMPTY_BOOK";
            case INTERRUPTED -> "INTERRUPTED";
        };
        jdbcTemplate.update("update collection_runs set status=?, finished_at=?, interruption_detected_at=?, result_code=? where id=?",
                status.name(), finishedAt, interruptionDetectedAt, resultCode, collectionRun.getId());
        return marketSnapshot.getId();
    }
}
