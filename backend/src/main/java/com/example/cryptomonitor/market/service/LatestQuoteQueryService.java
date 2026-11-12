package com.example.cryptomonitor.market.service;

import com.example.cryptomonitor.common.api.MarketQueryParameterValidator;
import com.example.cryptomonitor.market.dto.LatestQuoteItem;
import com.example.cryptomonitor.market.dto.LatestQuotesResponse;
import com.example.cryptomonitor.market.entity.MarketSnapshot;
import com.example.cryptomonitor.market.repository.MarketSnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 最新保存価格の参照担当。収集Service・Clientを依存に持たず、外部収集を起動しない。 */
@Service
public class LatestQuoteQueryService {
    private final MarketSnapshotRepository marketSnapshotRepository;

    public LatestQuoteQueryService(MarketSnapshotRepository marketSnapshotRepository) {
        this.marketSnapshotRepository = marketSnapshotRepository;
    }

    /** 検証・検索・DTO組立てを参照Tx内で完結する。検索例外を空配列で隠さない。 */
    @Transactional(readOnly = true)
    public LatestQuotesResponse findLatestQuotes(String exchange, String symbol) {
        MarketQueryParameterValidator.validateMarket(exchange, symbol);
        var latestQuoteItems = marketSnapshotRepository.findLatestByMarket(exchange, symbol).stream()
                .map(this::toLatestQuoteItem).toList();
        return new LatestQuotesResponse(latestQuoteItems);
    }

    /** Entityを公開DTOへ変換。JOIN取得済みの関連を使い、金額をdouble経由にしない。 */
    private LatestQuoteItem toLatestQuoteItem(MarketSnapshot marketSnapshot) {
        var collectionRun = marketSnapshot.getCollectionRun();
        return new LatestQuoteItem(marketSnapshot.getId().toString(), collectionRun.getExchange(),
                collectionRun.getSymbol(), marketSnapshot.getBidPrice().toPlainString(),
                marketSnapshot.getBidSize().toPlainString(), marketSnapshot.getAskPrice().toPlainString(),
                marketSnapshot.getAskSize().toPlainString(), marketSnapshot.getObservedAt(), marketSnapshot.getSourceAt());
    }
}
