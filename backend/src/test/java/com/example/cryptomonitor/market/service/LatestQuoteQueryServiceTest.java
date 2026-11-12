package com.example.cryptomonitor.market.service;

import com.example.cryptomonitor.collection.entity.CollectionRun;
import com.example.cryptomonitor.common.api.InvalidApiParameterException;
import com.example.cryptomonitor.market.entity.MarketSnapshot;
import com.example.cryptomonitor.market.repository.MarketSnapshotRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 試験区分：機能CのUT。Repositoryだけをモック化し、実HTTP・DB・Springは使用しない。
 * 実行方法：mvnw.cmd testまたはIntelliJ。各テストの観点IDとSourceがケース仕様。
 * 保証範囲外：SQLの最新選択・JOIN・TxはQueryApiITで確認する。モック結果だけでSQLを保証しない。
 */
@DisplayName("機能C / UT：最新価格の入力検証・DTO変換")
@ExtendWith(MockitoExtension.class)
class LatestQuoteQueryServiceTest {
    @Mock MarketSnapshotRepository marketSnapshotRepository;
    @InjectMocks LatestQuoteQueryService latestQuoteQueryService;

    /**
     * 観点：C-QUOTE-UT-01。前提：省略・片方・両方の有効な検索条件。Repositoryは0件を返す。
     * 操作：findLatestQuotes。期待：条件を変更せず渡し、結果は空のitems。外部取得は行わない。
     */
    @DisplayName("C-QUOTE-UT-01：任意フィルターを検索へ渡し0件を空配列にする")
    @ParameterizedTest(name = "[{index}] exchange={0}, symbol={1}")
    @CsvSource(value = {"NONE,NONE", "COINBASE_EXCHANGE,NONE", "NONE,BTC-USD", "COINBASE_EXCHANGE,BTC-USD"}, nullValues = "NONE")
    void passesOptionalFilters(String exchange, String symbol) {
        when(marketSnapshotRepository.findLatestByMarket(exchange, symbol)).thenReturn(List.of());
        assertTrue(latestQuoteQueryService.findLatestQuotes(exchange, symbol).items().isEmpty());
        verify(marketSnapshotRepository).findLatestByMarket(exchange, symbol);
    }

    /**
     * 観点：C-QUOTE-UT-02。前提：空・空白・大小文字違い・未知値を一方の条件に指定。
     * 操作：各値をexchange/symbolへ個別に渡す。期待：専用入力例外、検索呼出し0回。
     */
    @DisplayName("C-QUOTE-UT-02：不正な市場条件を検索前に拒否する")
    @ParameterizedTest(name = "[{index}] 不正値=[{0}]")
    @ValueSource(strings = {"", " ", "coinbase_exchange", "UNKNOWN"})
    void rejectsInvalidFilters(String invalidFilter) {
        assertThrows(InvalidApiParameterException.class, () -> latestQuoteQueryService.findLatestQuotes(invalidFilter, null));
        assertThrows(InvalidApiParameterException.class, () -> latestQuoteQueryService.findLatestQuotes(null, invalidFilter));
        verifyNoInteractions(marketSnapshotRepository);
    }

    /**
     * 観点：C-QUOTE-UT-03。前提：大きなID、小数の指数表記、null/非nullの提供元時刻を返すEntityモック。
     * 操作：検索ServiceでDTO変換。期待：IDと4数値は正確な文字列、日時・市場値をそのまま保持する。
     */
    @DisplayName("C-QUOTE-UT-03：ID・価格数量を正確な文字列へ変換し日時を保持する")
    @ParameterizedTest(name = "[{index}] sourceAtあり={0}")
    @ValueSource(booleans = {false, true})
    void convertsEntityWithoutPrecisionLoss(boolean hasSourceTime) {
        var marketSnapshot = mock(MarketSnapshot.class);
        var collectionRun = mock(CollectionRun.class);
        var observedAt = Instant.parse("2026-11-08T01:02:03.123456Z");
        var sourceAt = hasSourceTime ? observedAt.minusSeconds(1) : null;
        when(marketSnapshot.getId()).thenReturn(9007199254740993L);
        when(marketSnapshot.getCollectionRun()).thenReturn(collectionRun);
        when(collectionRun.getExchange()).thenReturn("COINBASE_EXCHANGE");
        when(collectionRun.getSymbol()).thenReturn("BTC-USD");
        when(marketSnapshot.getBidPrice()).thenReturn(new BigDecimal("100.100000000000"));
        when(marketSnapshot.getAskPrice()).thenReturn(new BigDecimal("101.200000000000"));
        when(marketSnapshot.getBidSize()).thenReturn(new BigDecimal("1E-12"));
        when(marketSnapshot.getAskSize()).thenReturn(new BigDecimal("2E-12"));
        when(marketSnapshot.getObservedAt()).thenReturn(observedAt);
        when(marketSnapshot.getSourceAt()).thenReturn(sourceAt);
        when(marketSnapshotRepository.findLatestByMarket(null, null)).thenReturn(List.of(marketSnapshot));

        var latestQuoteItem = latestQuoteQueryService.findLatestQuotes(null, null).items().getFirst();
        assertAll(() -> assertEquals("9007199254740993", latestQuoteItem.id()),
                () -> assertEquals("COINBASE_EXCHANGE", latestQuoteItem.exchange()),
                () -> assertEquals("BTC-USD", latestQuoteItem.symbol()),
                () -> assertEquals("100.100000000000", latestQuoteItem.bidPrice()),
                () -> assertEquals("101.200000000000", latestQuoteItem.askPrice()),
                () -> assertEquals("0.000000000001", latestQuoteItem.bidSize()),
                () -> assertEquals("0.000000000002", latestQuoteItem.askSize()),
                () -> assertEquals(observedAt, latestQuoteItem.observedAt()),
                () -> assertEquals(sourceAt, latestQuoteItem.sourceAt()));
    }

    /** 観点：C-QUOTE-UT-04。前提：検索が例外。操作：Service呼出し。期待：同じ例外を伝え、空配列で隠さない。 */
    @Test @DisplayName("C-QUOTE-UT-04：検索失敗を空配列へ置き換えない")
    void propagatesSearchFailure() {
        var searchException = new IllegalStateException("repository failure");
        when(marketSnapshotRepository.findLatestByMarket(null, null)).thenThrow(searchException);
        assertSame(searchException, assertThrows(IllegalStateException.class,
                () -> latestQuoteQueryService.findLatestQuotes(null, null)));
    }
}
