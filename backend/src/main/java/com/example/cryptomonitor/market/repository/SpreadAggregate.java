package com.example.cryptomonitor.market.repository;

import java.math.BigDecimal;

/** DB集計の射影。期間内全観測の結果だけを受け取り、全行をJavaへ転送しない。 */
public interface SpreadAggregate {
    long getObservationCount();
    BigDecimal getTotalSpread();
    BigDecimal getMinimumSpread();
    BigDecimal getMaximumSpread();
}
