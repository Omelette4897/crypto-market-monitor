package com.example.cryptomonitor.market.repository;

import com.example.cryptomonitor.market.entity.MarketSnapshot;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.time.Instant;
import org.springframework.data.domain.Pageable;

/** 観測の保存を担当。参照API向け検索は機能③で追加する。 */
public interface MarketSnapshotRepository extends JpaRepository<MarketSnapshot, Long> {
    /**
     * 同じ市場に自分より新しい観測がない行を返す。同時刻はIDで一意に決める。
     * 履歴はfetch joinで同時取得し、DTO変換時の追加SELECTを防ぐ。SUCCESS条件は付けない。
     */
    @Query("""
        select marketSnapshot from MarketSnapshot marketSnapshot
        join fetch marketSnapshot.collectionRun collectionRun
        where (:exchange is null or collectionRun.exchange = :exchange)
          and (:symbol is null or collectionRun.symbol = :symbol)
          and not exists (
              select newerSnapshot.id from MarketSnapshot newerSnapshot
              join newerSnapshot.collectionRun newerCollectionRun
              where newerCollectionRun.exchange = collectionRun.exchange
                and newerCollectionRun.symbol = collectionRun.symbol
                and (newerSnapshot.observedAt > marketSnapshot.observedAt
                    or (newerSnapshot.observedAt = marketSnapshot.observedAt
                        and newerSnapshot.id > marketSnapshot.id))
          )
        order by collectionRun.exchange asc, collectionRun.symbol asc
        """)
    List<MarketSnapshot> findLatestByMarket(@Param("exchange") String exchange, @Param("symbol") String symbol);

    /** 最新側から上限+1件だけ読む。ManyToOneのfetch joinでありコレクションのページングではない。 */
    @Query("""
        select marketSnapshot from MarketSnapshot marketSnapshot
        join fetch marketSnapshot.collectionRun collectionRun
        where collectionRun.exchange = :exchange and collectionRun.symbol = :symbol
          and marketSnapshot.observedAt >= :rangeStart and marketSnapshot.observedAt < :rangeEnd
        order by marketSnapshot.observedAt desc, marketSnapshot.id desc
        """)
    List<MarketSnapshot> findHistoryInRange(@Param("exchange") String exchange, @Param("symbol") String symbol,
            @Param("rangeStart") Instant rangeStart, @Param("rangeEnd") Instant rangeEnd, Pageable pageable);

    /** 一覧上限を適用せず、同期間の全観測をDBで集計する。0件のSUM/MIN/MAXはnull。 */
    @Query("""
        select count(marketSnapshot) as observationCount,
               sum(marketSnapshot.askPrice - marketSnapshot.bidPrice) as totalSpread,
               min(marketSnapshot.askPrice - marketSnapshot.bidPrice) as minimumSpread,
               max(marketSnapshot.askPrice - marketSnapshot.bidPrice) as maximumSpread
        from MarketSnapshot marketSnapshot join marketSnapshot.collectionRun collectionRun
        where collectionRun.exchange = :exchange and collectionRun.symbol = :symbol
          and marketSnapshot.observedAt >= :rangeStart and marketSnapshot.observedAt < :rangeEnd
        """)
    SpreadAggregate aggregateSpreadsInRange(@Param("exchange") String exchange, @Param("symbol") String symbol,
            @Param("rangeStart") Instant rangeStart, @Param("rangeEnd") Instant rangeEnd);
}
