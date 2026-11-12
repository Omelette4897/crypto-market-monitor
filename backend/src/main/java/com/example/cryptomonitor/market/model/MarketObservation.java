package com.example.cryptomonitor.market.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 取引所固有の応答を変換した後、アプリ内で受け渡す共通の価格観測データ。
 * Coinbaseの配列形式やDBの主キーを持たせず、通信形式・永続化から切り離す。
 * recordはデータを保持する型であり、この宣言だけでは値の妥当性を保証しない。
 *
 * @param exchange アプリ内の取引所識別子（例：COINBASE_EXCHANGE）
 * @param symbol 基軸通貨-決済通貨の共通表記（例：BTC-USD）
 * @param bidPrice 最良買気配。基軸通貨1単位あたりの決済通貨価格
 * @param bidSize 最良買気配の価格水準にある合計数量。単位は基軸通貨
 * @param askPrice 最良売気配。単位はbidPriceと同じ
 * @param askSize 最良売気配の価格水準にある合計数量。単位は基軸通貨
 * @param observedAt 応答本文を受信完了したアプリ側の時刻。変換・保存時刻ではない
 * @param sourceAt 意味を確認できた提供元時刻。意味が未確認の間はnull
 */
public record MarketObservation(
        String exchange,
        String symbol,
        BigDecimal bidPrice,
        BigDecimal bidSize,
        BigDecimal askPrice,
        BigDecimal askSize,
        Instant observedAt,
        Instant sourceAt
) {
    // 金額・数量はBigDecimal、時刻はInstantで保持する。
    // DATETIME(6)向けの小数秒切り捨ては、今後のDB保存境界で行う。
}
