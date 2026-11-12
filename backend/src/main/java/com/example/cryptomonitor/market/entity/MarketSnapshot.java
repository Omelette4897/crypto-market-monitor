package com.example.cryptomonitor.market.entity;

import com.example.cryptomonitor.collection.entity.CollectionRun;
import com.example.cryptomonitor.market.model.MarketObservation;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** market_snapshotsの1行。共通観測にDBの主キー・実行履歴への参照を加えた永続化用Entity。 */
@Entity
@Table(name = "market_snapshots")
public class MarketSnapshot {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    // FKに対応する片方向の関連。履歴の自動作成・削除や逆方向リストは設けない。
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false, unique = true)
    private CollectionRun collectionRun;
    @Column(name = "bid_price", nullable = false, precision = 30, scale = 12)
    private BigDecimal bidPrice;
    @Column(name = "bid_size", nullable = false, precision = 30, scale = 12)
    private BigDecimal bidSize;
    @Column(name = "ask_price", nullable = false, precision = 30, scale = 12)
    private BigDecimal askPrice;
    @Column(name = "ask_size", nullable = false, precision = 30, scale = 12)
    private BigDecimal askSize;
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "observed_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant observedAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "source_at", columnDefinition = "datetime(6)")
    private Instant sourceAt;

    protected MarketSnapshot() {}

    /**
     * 検証済み観測を保存形式へ変換する。時刻は切捨てるが、価格・数量の有効桁は丸めない。
     * Mapperを迂回した誤った呼出しでも、DBの暗黙丸めで値を変えて保存しない。
     */
    public static MarketSnapshot from(CollectionRun collectionRun, MarketObservation marketObservation) {
        Objects.requireNonNull(marketObservation);
        var marketSnapshot = new MarketSnapshot();
        marketSnapshot.collectionRun = Objects.requireNonNull(collectionRun);
        marketSnapshot.bidPrice = decimal(marketObservation.bidPrice());
        marketSnapshot.bidSize = decimal(marketObservation.bidSize());
        marketSnapshot.askPrice = decimal(marketObservation.askPrice());
        marketSnapshot.askSize = decimal(marketObservation.askSize());
        marketSnapshot.observedAt = Objects.requireNonNull(marketObservation.observedAt()).truncatedTo(ChronoUnit.MICROS);
        marketSnapshot.sourceAt = marketObservation.sourceAt() == null ? null
                : marketObservation.sourceAt().truncatedTo(ChronoUnit.MICROS);
        return marketSnapshot;
    }

    /** DECIMAL(30,12)へ損失なく入る値だけを受ける。UNNECESSARYで桁の丸めを禁止する。 */
    private static BigDecimal decimal(BigDecimal decimalValue) {
        var databaseScaleValue = Objects.requireNonNull(decimalValue).setScale(12, RoundingMode.UNNECESSARY);
        if (databaseScaleValue.precision() > 30) throw new IllegalArgumentException("Value exceeds DECIMAL(30,12)");
        return databaseScaleValue;
    }

    public Long getId() { return id; }
    public CollectionRun getCollectionRun() { return collectionRun; }
    public BigDecimal getBidPrice() { return bidPrice; }
    public BigDecimal getBidSize() { return bidSize; }
    public BigDecimal getAskPrice() { return askPrice; }
    public BigDecimal getAskSize() { return askSize; }
    public Instant getObservedAt() { return observedAt; }
    public Instant getSourceAt() { return sourceAt; }
}
