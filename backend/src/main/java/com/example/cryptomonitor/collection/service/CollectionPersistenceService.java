package com.example.cryptomonitor.collection.service;

import com.example.cryptomonitor.collection.entity.CollectionRun;
import com.example.cryptomonitor.collection.repository.CollectionRunRepository;
import com.example.cryptomonitor.collection.entity.CollectionRunStatus;
import com.example.cryptomonitor.market.model.MarketObservation;
import com.example.cryptomonitor.market.entity.MarketSnapshot;
import com.example.cryptomonitor.market.repository.MarketSnapshotRepository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import static com.example.cryptomonitor.collection.entity.CollectionRunStatus.*;

/**
 * 収集履歴・観測の保存を業務上のまとまりで確定するサービス。
 * 非トランザクションのCollectionServiceから別Beanとして呼び、各メソッドで短いTxを作る。
 */
@Service
public class CollectionPersistenceService {
    private final CollectionRunRepository collectionRunRepository;
    private final MarketSnapshotRepository marketSnapshotRepository;
    private final Clock utcClock;

    public CollectionPersistenceService(
            CollectionRunRepository collectionRunRepository,
            MarketSnapshotRepository marketSnapshotRepository,
            @Qualifier("coinbaseClock") Clock utcClock) {
        this.collectionRunRepository = collectionRunRepository;
        this.marketSnapshotRepository = marketSnapshotRepository;
        this.utcClock = utcClock;
    }

    /** 起動時の回収を1つのTxで確定する。戻った後にRunnerが収集を開始する。 */
    @Transactional
    public int recoverInterruptedRuns() {
        return collectionRunRepository.recoverInterruptedRuns(RUNNING, INTERRUPTED, now(), "INTERRUPTED");
    }

    /** 開始を先にコミットする。DBが採番したIDはメソッドのTx確定後に呼出し元へ戻る。 */
    @Transactional
    public Long startBtcUsd() {
        return collectionRunRepository.save(
                CollectionRun.start("COINBASE_EXCHANGE", "BTC-USD", now())).getId();
    }

    /**
     * 未確定・同一市場の実行だけを更新し、観測とSUCCESSを一括確定する。
     * INSERTやコミットが失敗するとUPDATEもrollbackされる。自己呼出しで利用しない。
     */
    @Transactional
    public void completeSuccessfully(Long collectionRunId, MarketObservation marketObservation) {
        Objects.requireNonNull(marketObservation);
        int updatedRowCount = collectionRunRepository.completeSuccessfully(
                collectionRunId, marketObservation.exchange(), marketObservation.symbol(),
                now(), RUNNING, SUCCESS);
        requireUpdated(updatedRowCount);
        // 更新で存在を確認済みなので、関連用参照だけを取得して余分なSELECTを避ける。
        var collectionRun = collectionRunRepository.getReferenceById(collectionRunId);
        marketSnapshotRepository.saveAndFlush(MarketSnapshot.from(collectionRun, marketObservation));
    }

    /** スキップを確定。対象不明・確定済みなら例外とし、成功したふりをしない。 */
    @Transactional
    public void completeAsSkipped(Long collectionRunId, String resultCode) {
        validateCode(resultCode);
        requireUpdated(collectionRunRepository.completeIfRunning(collectionRunId, SKIPPED, resultCode, now(), RUNNING));
    }

    /**
     * 通常の取得失敗と保存失敗後の救済に使用する。falseは「FAILEDを保存していない」。
     * コミット応答喪失後にDBが既にSUCCESSでも、条件付き更新なので上書きしない。
     */
    @Transactional
    public boolean completeAsFailedIfRunning(Long collectionRunId, String resultCode) {
        validateCode(resultCode);
        return collectionRunRepository.completeIfRunning(collectionRunId, FAILED, resultCode, now(), RUNNING) == 1;
    }

    private Instant now() { return utcClock.instant().truncatedTo(ChronoUnit.MICROS); }

    private static void requireUpdated(int updatedRowCount) {
        if (updatedRowCount != 1) throw new IllegalStateException("Collection run is missing, completed, or market does not match");
    }

    /** 内部理由コードの渡し忘れを保存前に検出する。例外の全文をコードとして保存しない。 */
    private static void validateCode(String resultCode) {
        if (resultCode == null || resultCode.isBlank() || resultCode.length() > 64)
            throw new IllegalArgumentException("A result code of 1..64 characters is required");
    }
}
