package com.example.cryptomonitor.collection.repository;

import com.example.cryptomonitor.collection.entity.CollectionRun;
import com.example.cryptomonitor.collection.entity.CollectionRunStatus;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;

/** 履歴のDB操作。条件付き更新は保存Serviceが開始したトランザクション内でだけ実行する。 */
public interface CollectionRunRepository extends JpaRepository<CollectionRun, Long> {
    /** 前回プロセスの未確定実行だけを回収する。実際の終了時刻は不明なので設定しない。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("""
        update CollectionRun collectionRun set collectionRun.status = :interruptedStatus,
            collectionRun.interruptionDetectedAt = :interruptionDetectedAt,
            collectionRun.finishedAt = null, collectionRun.resultCode = :resultCode
        where collectionRun.status = :runningStatus
        """)
    int recoverInterruptedRuns(@Param("runningStatus") CollectionRunStatus runningStatus,
            @Param("interruptedStatus") CollectionRunStatus interruptedStatus,
            @Param("interruptionDetectedAt") Instant interruptionDetectedAt,
            @Param("resultCode") String resultCode);

    /** ListとPageableを使い、件数制限をDBへ渡す。Page用の総件数COUNTは要求しない。 */
    @Query("""
        select collectionRun from CollectionRun collectionRun
        where (:exchange is null or collectionRun.exchange = :exchange)
          and (:symbol is null or collectionRun.symbol = :symbol)
        order by collectionRun.startedAt desc, collectionRun.id desc
        """)
    List<CollectionRun> findHistory(@Param("exchange") String exchange, @Param("symbol") String symbol,
            Pageable pageable);

    /**
     * 採用した観測と市場が一致し、かつ未確定の実行だけをSUCCESSにする。
     * 更新件数0を成功とみなさず、観測INSERTの前に呼出し元で確認する。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("""
        update CollectionRun collectionRun set collectionRun.status = :successStatus, collectionRun.finishedAt = :finishedAt,
            collectionRun.resultCode = null
        where collectionRun.id = :collectionRunId and collectionRun.status = :runningStatus
            and collectionRun.exchange = :exchange and collectionRun.symbol = :symbol
        """)
    int completeSuccessfully(@Param("collectionRunId") Long collectionRunId, @Param("exchange") String exchange,
            @Param("symbol") String symbol, @Param("finishedAt") Instant finishedAt,
            @Param("runningStatus") CollectionRunStatus runningStatus, @Param("successStatus") CollectionRunStatus successStatus);

    /** スキップ・失敗の確定。SUCCESS等を例外後の救済処理から上書きしない。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("""
        update CollectionRun collectionRun set collectionRun.status = :status, collectionRun.finishedAt = :finishedAt,
            collectionRun.resultCode = :resultCode
        where collectionRun.id = :collectionRunId and collectionRun.status = :runningStatus
        """)
    int completeIfRunning(@Param("collectionRunId") Long collectionRunId, @Param("status") CollectionRunStatus status,
            @Param("resultCode") String resultCode, @Param("finishedAt") Instant finishedAt,
            @Param("runningStatus") CollectionRunStatus runningStatus);
}
