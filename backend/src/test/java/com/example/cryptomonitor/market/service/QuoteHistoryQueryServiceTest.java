package com.example.cryptomonitor.market.service;

import com.example.cryptomonitor.common.api.*;
import com.example.cryptomonitor.market.controller.QuoteHistoryController;
import com.example.cryptomonitor.market.entity.MarketSnapshot;
import com.example.cryptomonitor.collection.entity.CollectionRun;
import com.example.cryptomonitor.market.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.data.domain.Pageable;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 試験区分：Service UTとMVC契約結合。Service/Controller/例外変換は実体、Repositoryのみモック。
 * 実行：mvnw.cmd test。固定UTC時計で期間と丸めを検証。実SQL/Tx/実接続はQueryApiITで別確認する。
 */
@DisplayName("機能E / 24時間履歴・統計のUTとMVC契約")
class QuoteHistoryQueryServiceTest {
    private final MarketSnapshotRepository marketSnapshotRepository = mock(MarketSnapshotRepository.class);
    private static final Instant RANGE_END = Instant.parse("2026-11-12T10:00:00Z");
    private final QuoteHistoryQueryService quoteHistoryQueryService = new QuoteHistoryQueryService(
            marketSnapshotRepository, Clock.fixed(RANGE_END, ZoneOffset.UTC));
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new QuoteHistoryController(quoteHistoryQueryService))
            .setControllerAdvice(new ApiExceptionHandler())
            .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS))).build();

    @BeforeEach void prepareEmptyResponse() {
        when(marketSnapshotRepository.findHistoryInRange(anyString(), anyString(), any(), any(), any())).thenReturn(List.of());
        doReturn(aggregate(0, null)).when(marketSnapshotRepository).aggregateSpreadsInRange(anyString(), anyString(), any(), any());
    }

    /** E-HIST-01：前提0件。操作：条件省略でHTTP検索。期待：24時間・null統計・空配列、同一期間/対象で検索。 */
    @Test @DisplayName("E-HIST-01：省略時の対象・期間と正常0件のJSON契約")
    void returnsEmptyHistoryWithDefaults() throws Exception {
        mockMvc.perform(get("/api/v1/quotes/history")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.rangeStart").value("2026-11-11T10:00:00Z"))
                .andExpect(jsonPath("$.rangeEnd").value(RANGE_END.toString())).andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.spreadStatistics.observationCount").value(0))
                .andExpect(jsonPath("$.spreadStatistics.averageSpread").isEmpty());
        verify(marketSnapshotRepository).findHistoryInRange(eq("COINBASE_EXCHANGE"),eq("BTC-USD"),
                eq(RANGE_END.minusSeconds(86400)),eq(RANGE_END),argThat(pageable -> pageable.getPageSize() == 2001));
        verify(marketSnapshotRepository).aggregateSpreadsInRange("COINBASE_EXCHANGE", "BTC-USD", RANGE_END.minusSeconds(86400), RANGE_END);
    }

    /** E-HIST-02：前提降順2件とDB集計。操作：Service。期待：昇順化、文字列精度、平均小数16桁。 */
    @ParameterizedTest(name = "合計={0} / 件数={1} → 平均={2}")
    @CsvSource({"1,3,0.3333333333333333", "1,6,0.1666666666666667"})
    @DisplayName("E-HIST-02：昇順への変換とBigDecimalの平均丸め")
    void mapsHistoryAndRoundsAverage(String totalSpread, long observationCount, String expectedAverage) {
        doReturn(List.of(snapshot(2), snapshot(1))).when(marketSnapshotRepository).findHistoryInRange(anyString(),anyString(),any(),any(),any());
        doReturn(aggregate(observationCount, totalSpread)).when(marketSnapshotRepository).aggregateSpreadsInRange(anyString(),anyString(),any(),any());
        var response = quoteHistoryQueryService.findQuoteHistory("COINBASE_EXCHANGE", "BTC-USD");
        assertEquals(List.of("1","2"), response.items().stream().map(item -> item.id()).toList());
        assertEquals("0.000000000001",response.items().getFirst().spread());
        assertEquals(expectedAverage,response.spreadStatistics().averageSpread());
        assertEquals("999999999999999999.123456789012",response.items().getFirst().bidPrice());
    }

    /** E-HIST-03：前提上限+1件・全期間3000件。操作：Service。期待：最新2000件、truncated、統計は3000件。 */
    @Test @DisplayName("E-HIST-03：一覧上限は全期間統計を切り詰めない")
    void capsOnlyChartItems() {
        doReturn(IntStream.iterate(2001,i -> i-1).limit(2001).mapToObj(this::snapshot).toList()).when(marketSnapshotRepository).findHistoryInRange(anyString(),anyString(),any(),any(),any());
        doReturn(aggregate(3000,"3")).when(marketSnapshotRepository).aggregateSpreadsInRange(anyString(),anyString(),any(),any());
        var response=quoteHistoryQueryService.findQuoteHistory(null,null);
        assertTrue(response.truncated());assertEquals(2000,response.items().size());
        assertEquals("2",response.items().getFirst().id());assertEquals("2001",response.items().getLast().id());
        assertEquals(3000,response.spreadStatistics().observationCount());
    }

    /** E-HIST-04：前提空/空白/未対応値。操作：HTTP。期待：400・既存形式、DB検索なし。 */
    @ParameterizedTest @CsvSource(value={"exchange|''", "exchange|' '", "exchange|OTHER", "symbol|''", "symbol|ETH-USD"},delimiter='|',emptyValue="")
    @DisplayName("E-HIST-04：未対応・空の市場条件は400")
    void rejectsInvalidMarket(String parameter,String value) throws Exception {
        clearInvocations(marketSnapshotRepository);
        mockMvc.perform(get("/api/v1/quotes/history").param(parameter,value)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        verifyNoInteractions(marketSnapshotRepository);
    }

    /** E-HIST-05：前提DB接続不能。操作：HTTP。期待：503と固定説明、内部例外文は非公開。 */
    @Test @DisplayName("E-HIST-05：履歴APIにも共通の接続障害変換が適用される")
    void mapsDatabaseFailure() throws Exception {
        when(marketSnapshotRepository.findHistoryInRange(anyString(),anyString(),any(),any(),any()))
                .thenThrow(new org.springframework.jdbc.CannotGetJdbcConnectionException("private connection details"));
        mockMvc.perform(get("/api/v1/quotes/history")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }

    /** E-HIST-06：前提予期しない検索不具合。操作：HTTP。期待：500で空配列にはしない。 */
    @Test @DisplayName("E-HIST-06：内部不具合は500で返す")
    void mapsInternalFailure() throws Exception {
        when(marketSnapshotRepository.findHistoryInRange(anyString(),anyString(),any(),any(),any())).thenThrow(new IllegalStateException("private"));
        mockMvc.perform(get("/api/v1/quotes/history")).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    private SpreadAggregate aggregate(long count,String total) {
        var aggregate=mock(SpreadAggregate.class);when(aggregate.getObservationCount()).thenReturn(count);
        if(count>0) { when(aggregate.getTotalSpread()).thenReturn(new BigDecimal(total));
            when(aggregate.getMinimumSpread()).thenReturn(new BigDecimal("0.000000000001"));
            when(aggregate.getMaximumSpread()).thenReturn(BigDecimal.ONE); }
        return aggregate;
    }
    private MarketSnapshot snapshot(int id) {
        var snapshot=mock(MarketSnapshot.class);var run=mock(CollectionRun.class);
        when(run.getExchange()).thenReturn("COINBASE_EXCHANGE");when(run.getSymbol()).thenReturn("BTC-USD");
        when(snapshot.getCollectionRun()).thenReturn(run);when(snapshot.getId()).thenReturn((long)id);
        when(snapshot.getObservedAt()).thenReturn(RANGE_END.minusSeconds(3000-id));
        when(snapshot.getBidPrice()).thenReturn(new BigDecimal("999999999999999999.123456789012"));
        when(snapshot.getAskPrice()).thenReturn(new BigDecimal("999999999999999999.123456789013"));return snapshot;
    }
}
