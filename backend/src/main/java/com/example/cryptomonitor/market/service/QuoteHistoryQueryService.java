package com.example.cryptomonitor.market.service;

import com.example.cryptomonitor.common.api.MarketQueryParameterValidator;
import com.example.cryptomonitor.market.dto.*;
import com.example.cryptomonitor.market.entity.MarketSnapshot;
import com.example.cryptomonitor.market.repository.MarketSnapshotRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.temporal.ChronoUnit;

/** 期間確定・一覧検索・DB集計・DTO組立てを担当する読み取り専用Service。 */
@Service
public class QuoteHistoryQueryService {
    public static final int MAX_HISTORY_ITEMS = 2_000;
    private final MarketSnapshotRepository marketSnapshotRepository;
    private final Clock marketQueryClock;

    public QuoteHistoryQueryService(MarketSnapshotRepository marketSnapshotRepository,
            @Qualifier("marketQueryClock") Clock marketQueryClock) {
        this.marketSnapshotRepository = marketSnapshotRepository;
        this.marketQueryClock = marketQueryClock;
    }

    /**
     * 同じ市場・半開区間[start,end)で2回のSELECTを行う。
     * REPEATABLE_READにより、並行収集のcommitで一覧と集計の読取り対象がずれないようにする。
     * 既存収集/保存処理のTx境界は変更しない。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public QuoteHistoryResponse findQuoteHistory(String exchange, String symbol) {
        MarketQueryParameterValidator.validateMarket(exchange, symbol);
        String selectedExchange = exchange == null ? "COINBASE_EXCHANGE" : exchange;
        String selectedSymbol = symbol == null ? "BTC-USD" : symbol;
        var rangeEnd = marketQueryClock.instant().truncatedTo(ChronoUnit.MICROS);
        var rangeStart = rangeEnd.minus(24, ChronoUnit.HOURS);
        // 追加1件で省略を検出する。PageではなくListを使い、不要なCOUNTは発行しない。
        var historySnapshots = marketSnapshotRepository.findHistoryInRange(selectedExchange, selectedSymbol,
                rangeStart, rangeEnd, PageRequest.of(0, MAX_HISTORY_ITEMS + 1));
        var spreadAggregate = marketSnapshotRepository.aggregateSpreadsInRange(selectedExchange, selectedSymbol,
                rangeStart, rangeEnd);
        var historyItems = historySnapshots.subList(0, Math.min(historySnapshots.size(), MAX_HISTORY_ITEMS))
                .reversed().stream().map(this::toHistoryItem).toList();
        long observationCount = spreadAggregate.getObservationCount();
        var spreadStatistics = observationCount == 0 ? new SpreadStatistics(0, null, null, null)
                : new SpreadStatistics(observationCount,
                    spreadAggregate.getTotalSpread().divide(BigDecimal.valueOf(observationCount), 16, RoundingMode.HALF_UP).toPlainString(),
                    spreadAggregate.getMinimumSpread().toPlainString(), spreadAggregate.getMaximumSpread().toPlainString());
        return new QuoteHistoryResponse(rangeStart, rangeEnd, historySnapshots.size() > MAX_HISTORY_ITEMS,
                historyItems, spreadStatistics);
    }

    /** JOIN取得済みの関連を利用し、N+1 SELECTとdouble経由の金額変換を避ける。 */
    private QuoteHistoryItem toHistoryItem(MarketSnapshot marketSnapshot) {
        var collectionRun = marketSnapshot.getCollectionRun();
        return new QuoteHistoryItem(marketSnapshot.getId().toString(), collectionRun.getExchange(),
                collectionRun.getSymbol(), marketSnapshot.getObservedAt(), marketSnapshot.getBidPrice().toPlainString(),
                marketSnapshot.getAskPrice().toPlainString(),
                marketSnapshot.getAskPrice().subtract(marketSnapshot.getBidPrice()).toPlainString());
    }
}
