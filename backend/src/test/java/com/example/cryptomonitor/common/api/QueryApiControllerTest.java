package com.example.cryptomonitor.common.api;

import com.example.cryptomonitor.coinbase.client.CoinbaseClient;
import com.example.cryptomonitor.collection.controller.CollectionRunController;
import com.example.cryptomonitor.collection.entity.CollectionRun;
import com.example.cryptomonitor.collection.entity.CollectionRunStatus;
import com.example.cryptomonitor.collection.repository.CollectionRunRepository;
import com.example.cryptomonitor.collection.service.*;
import com.example.cryptomonitor.market.controller.LatestQuoteController;
import com.example.cryptomonitor.market.entity.MarketSnapshot;
import com.example.cryptomonitor.market.repository.MarketSnapshotRepository;
import com.example.cryptomonitor.market.service.LatestQuoteQueryService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.*;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 試験区分：MockMvcによるHTTP契約の結合確認。Controller・検索Service・例外変換は実物、Repositoryはモック。
 * 実行：mvnw.cmd test。外部HTTP・MySQLは不要。値は固定データ、エラーは例外モックで再現する。
 * 未保証：実SQL・接続断の故障注入・ブラウザSTは対象外。QueryApiITが実DBの検索を確認する。
 */
@DisplayName("機能C / MockMvc：2 GET APIのJSON・入力・エラー契約")
@WebMvcTest(controllers = {LatestQuoteController.class, CollectionRunController.class})
@Import({LatestQuoteQueryService.class, CollectionRunQueryService.class, ApiExceptionHandler.class})
class QueryApiControllerTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean MarketSnapshotRepository marketSnapshotRepository;
    @MockitoBean CollectionRunRepository collectionRunRepository;
    // API参照から収集/更新を起動していないことを、全ケース終了時に確認するための監視用モック。
    @MockitoBean CoinbaseClient coinbaseClient;
    @MockitoBean CollectionService collectionService;
    @MockitoBean CollectionPersistenceService collectionPersistenceService;

    /** 共通期待：GET処理から収集進行・外部取得・保存Serviceへの呼出しが発生しない。 */
    @AfterEach void verifiesReadOnlyCallBoundary() {
        verifyNoInteractions(coinbaseClient, collectionService, collectionPersistenceService);
    }

    /**
     * 観点：C-WEB-01。前提：Repositoryが固定観測1件を返す。操作：最新価格GET。
     * 期待：200/items配列、IDと4数値は文字列、日時はUTC、sourceAtは明示null、Entity関連を公開しない。
     */
    @Test @DisplayName("C-WEB-01：最新価格のJSON型・UTC・null契約を守る")
    void returnsLatestQuoteJsonContract() throws Exception {
        var collectionRun = mock(CollectionRun.class);
        when(collectionRun.getExchange()).thenReturn("COINBASE_EXCHANGE");
        when(collectionRun.getSymbol()).thenReturn("BTC-USD");
        var marketSnapshot = mock(MarketSnapshot.class);
        when(marketSnapshot.getId()).thenReturn(9007199254740993L);
        when(marketSnapshot.getCollectionRun()).thenReturn(collectionRun);
        when(marketSnapshot.getBidPrice()).thenReturn(new BigDecimal("100.100000000000"));
        when(marketSnapshot.getAskPrice()).thenReturn(new BigDecimal("101.200000000000"));
        when(marketSnapshot.getBidSize()).thenReturn(new BigDecimal("1E-12"));
        when(marketSnapshot.getAskSize()).thenReturn(BigDecimal.ONE);
        when(marketSnapshot.getObservedAt()).thenReturn(Instant.parse("2026-11-08T01:02:03.123456Z"));
        when(marketSnapshotRepository.findLatestByMarket(null, null)).thenReturn(List.of(marketSnapshot));

        mockMvc.perform(get("/api/v1/quotes/latest"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").isString()).andExpect(jsonPath("$.items[0].id").value("9007199254740993"))
                .andExpect(jsonPath("$.items[0].exchange").value("COINBASE_EXCHANGE"))
                .andExpect(jsonPath("$.items[0].symbol").value("BTC-USD"))
                .andExpect(jsonPath("$.items[0].bidPrice").isString()).andExpect(jsonPath("$.items[0].bidPrice").value("100.100000000000"))
                .andExpect(jsonPath("$.items[0].askPrice").isString())
                .andExpect(jsonPath("$.items[0].bidSize").isString()).andExpect(jsonPath("$.items[0].bidSize").value("0.000000000001"))
                .andExpect(jsonPath("$.items[0].askSize").isString())
                .andExpect(jsonPath("$.items[0].observedAt").value("2026-11-08T01:02:03.123456Z"))
                .andExpect(jsonPath("$.items[0].sourceAt").value(nullValue()))
                .andExpect(jsonPath("$.items[0].collectionRun").doesNotExist());
    }

    /** 観点：C-WEB-02。前提：FAILED/TIMEOUTの履歴。操作：履歴GET。期待：HTTP200、文字列ID・理由説明・null項目を返す。 */
    @Test @DisplayName("C-WEB-02：FAILED履歴も正常な検索結果として200を返す")
    void returnsFailedHistoryAsSuccessfulResponse() throws Exception {
        var collectionRun = mock(CollectionRun.class);
        when(collectionRun.getId()).thenReturn(42L);
        when(collectionRun.getExchange()).thenReturn("COINBASE_EXCHANGE");
        when(collectionRun.getSymbol()).thenReturn("BTC-USD");
        when(collectionRun.getStatus()).thenReturn(CollectionRunStatus.FAILED);
        when(collectionRun.getStartedAt()).thenReturn(Instant.parse("2026-11-08T01:00:00Z"));
        when(collectionRun.getFinishedAt()).thenReturn(Instant.parse("2026-11-08T01:00:05Z"));
        when(collectionRun.getResultCode()).thenReturn("TIMEOUT");
        when(collectionRunRepository.findHistory(isNull(), isNull(), any())).thenReturn(List.of(collectionRun));
        mockMvc.perform(get("/api/v1/collection-runs"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").isString())
                .andExpect(jsonPath("$.items[0].status").value("FAILED"))
                .andExpect(jsonPath("$.items[0].startedAt").value("2026-11-08T01:00:00Z"))
                .andExpect(jsonPath("$.items[0].finishedAt").value("2026-11-08T01:00:05Z"))
                .andExpect(jsonPath("$.items[0].interruptionDetectedAt").value(nullValue()))
                .andExpect(jsonPath("$.items[0].resultCode").value("TIMEOUT"))
                .andExpect(jsonPath("$.items[0].resultDescription").value("外部APIの応答待ちがタイムアウトしました"));
    }

    /** 観点：C-WEB-03。前提：検索結果なし。操作：各GET。期待：200とitems=[]で、nullや404にしない。 */
    @DisplayName("C-WEB-03：該当なしは200と空のitemsを返す")
    @ParameterizedTest(name = "[{index}] GET {0}")
    @ValueSource(strings = {"/api/v1/quotes/latest", "/api/v1/collection-runs"})
    void returnsEmptyArrayForNoData(String apiPath) throws Exception {
        mockMvc.perform(get(apiPath)).andExpect(status().isOk()).andExpect(content().json("{\"items\":[]}"));
    }

    /** 観点：C-WEB-04。前提：limit不正各値。操作：履歴GET。期待：400/code/message、DB検索なし、itemsなし。 */
    @DisplayName("C-WEB-04：不正limitを400で返す")
    @ParameterizedTest(name = "[{index}] limit=[{0}]")
    @ValueSource(strings = {"", " ", "0", "101", "-1", "1.1", "abc", "99999999999999999999999"})
    void returnsBadRequestForInvalidLimit(String invalidLimit) throws Exception {
        mockMvc.perform(get("/api/v1/collection-runs").param("limit", invalidLimit))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.message").isString()).andExpect(jsonPath("$.items").doesNotExist());
        verifyNoInteractions(collectionRunRepository, marketSnapshotRepository);
    }

    /** 観点：C-WEB-05。前提：両API×両条件×空/空白/未対応値。操作：GET。期待：400、DB検索なし。 */
    @DisplayName("C-WEB-05：不正なexchange・symbolを両APIで拒否する")
    @ParameterizedTest(name = "[{index}] GET {0}, {1}=[{2}]")
    @MethodSource("invalidMarketRequests")
    void returnsBadRequestForInvalidMarket(String apiPath, String parameterName, String invalidValue) throws Exception {
        mockMvc.perform(get(apiPath).param(parameterName, invalidValue)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        verifyNoInteractions(collectionRunRepository, marketSnapshotRepository);
    }

    /** 観点：C-WEB-06。前提：未知クエリを指定。操作：両GET。期待：既存契約のとおり厳格拒否せず200。 */
    @DisplayName("C-WEB-06：未知クエリだけを理由とした拒否を追加しない")
    @ParameterizedTest(name = "[{index}] GET {0}")
    @ValueSource(strings = {"/api/v1/quotes/latest", "/api/v1/collection-runs"})
    void leavesUnknownQueryOutsideContract(String apiPath) throws Exception {
        mockMvc.perform(get(apiPath).param("unknown", "value")).andExpect(status().isOk());
    }

    /**
     * 観点：C-WEB-07。前提：検索で各種例外を送出。操作：両GET。
     * 期待：接続障害だけ503、SQL/JPA/内部引数不具合は500。固定code/messageのみ、内部文字列を漏らさない。
     */
    @DisplayName("C-WEB-07：接続障害と内部不具合を分類し内部情報を隠す")
    @ParameterizedTest(name = "[{index}] GET {0} / {1} → HTTP {3}")
    @MethodSource("failureRequests")
    void classifiesFailureWithoutLeakingDetails(String apiPath, String failureLabel,
            RuntimeException searchException, int expectedStatus, String expectedCode) throws Exception {
        if (apiPath.endsWith("latest")) {
            when(marketSnapshotRepository.findLatestByMarket(null, null)).thenThrow(searchException);
        } else {
            when(collectionRunRepository.findHistory(isNull(), isNull(), any())).thenThrow(searchException);
        }
        mockMvc.perform(get(apiPath)).andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(expectedCode)).andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.items").doesNotExist())
                .andExpect(content().string(not(containsString("PRIVATE_DATABASE_DETAIL"))))
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    /** 2 API×2フィルター×3種の不正値を供給。実データを混ぜず、入力不正だけを変える。 */
    static Stream<Arguments> invalidMarketRequests() {
        return Stream.of("/api/v1/quotes/latest", "/api/v1/collection-runs").flatMap(apiPath ->
                Stream.of("exchange", "symbol").flatMap(parameterName ->
                        Stream.of("", " ", "UNSUPPORTED").map(invalidValue -> Arguments.of(apiPath, parameterName, invalidValue))));
    }

    /**
     * 観点：C-WEB-08。前提：有効条件の省略/片方/両方、検索結果なし。操作：両GET。
     * 期待：Controllerが条件を取り違えず渡し200。履歴は省略時100件、他方のRepositoryを呼ばない。
     */
    @DisplayName("C-WEB-08：有効な任意フィルターを正しい検索先へ渡す")
    @ParameterizedTest(name = "[{index}] GET {0}, exchange={1}, symbol={2}")
    @MethodSource("validMarketRequests")
    void passesValidFiltersToCorrectRepository(String apiPath, String exchange, String symbol) throws Exception {
        var requestBuilder = get(apiPath);
        if (exchange != null) requestBuilder.param("exchange", exchange);
        if (symbol != null) requestBuilder.param("symbol", symbol);
        mockMvc.perform(requestBuilder).andExpect(status().isOk());
        if (apiPath.endsWith("latest")) {
            verify(marketSnapshotRepository).findLatestByMarket(exchange, symbol);
            verifyNoInteractions(collectionRunRepository);
        } else {
            verify(collectionRunRepository).findHistory(exchange, symbol, org.springframework.data.domain.PageRequest.of(0, 100));
            verifyNoInteractions(marketSnapshotRepository);
        }
    }

    /** 有効な条件4組を両APIへ供給する。nullはクエリパラメータ自体を送らない場合。 */
    static Stream<Arguments> validMarketRequests() {
        return Stream.of("/api/v1/quotes/latest", "/api/v1/collection-runs").flatMap(apiPath ->
                Stream.of(new String[] {null, null}, new String[] {"COINBASE_EXCHANGE", null},
                        new String[] {null, "BTC-USD"}, new String[] {"COINBASE_EXCHANGE", "BTC-USD"})
                        .map(filters -> Arguments.of(apiPath, filters[0], filters[1])));
    }

    /** 型/原因に基づく分類表。内部文字列は全ケース共通とし、文言検索で分類する実装を許さない。 */
    static Stream<Arguments> failureRequests() {
        String privateDetail = "PRIVATE_DATABASE_DETAIL";
        return Stream.of("/api/v1/quotes/latest", "/api/v1/collection-runs").flatMap(apiPath -> Stream.of(
                Arguments.of(apiPath, "JDBC接続取得不能", new CannotGetJdbcConnectionException(privateDetail), 503, "SERVICE_UNAVAILABLE"),
                Arguments.of(apiPath, "Tx開始中の接続断", new CannotCreateTransactionException(privateDetail,
                        new org.hibernate.exception.JDBCConnectionException(privateDetail, new SQLException(privateDetail, "08006"))), 503, "SERVICE_UNAVAILABLE"),
                Arguments.of(apiPath, "SQLState08", new DataAccessResourceFailureException(privateDetail,
                        new SQLException(privateDetail, "08001")), 503, "SERVICE_UNAVAILABLE"),
                Arguments.of(apiPath, "接続待ちタイムアウト", new DataAccessResourceFailureException(privateDetail,
                        new SQLTransientConnectionException(privateDetail)), 503, "SERVICE_UNAVAILABLE"),
                Arguments.of(apiPath, "SQL構文不正", new InvalidDataAccessResourceUsageException(privateDetail), 500, "INTERNAL_ERROR"),
                Arguments.of(apiPath, "制約違反", new DataIntegrityViolationException(privateDetail), 500, "INTERNAL_ERROR"),
                Arguments.of(apiPath, "JPA内部不具合", new JpaSystemException(new IllegalStateException(privateDetail)), 500, "INTERNAL_ERROR"),
                Arguments.of(apiPath, "内部引数不具合", new IllegalArgumentException(privateDetail), 500, "INTERNAL_ERROR"),
                Arguments.of(apiPath, "接続以外のTx開始失敗", new CannotCreateTransactionException(privateDetail,
                        new IllegalStateException(privateDetail)), 500, "INTERNAL_ERROR"),
                Arguments.of(apiPath, "08以外のSQLState", new DataAccessResourceFailureException(privateDetail,
                        new SQLException(privateDetail, "42000")), 500, "INTERNAL_ERROR")
        ));
    }
}
