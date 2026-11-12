package com.example.cryptomonitor.collection.scheduler;

import com.example.cryptomonitor.collection.config.CollectionSchedulingProperties;
import com.example.cryptomonitor.collection.service.CollectionService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * 回収後の明示登録と、DB破棄前の停止を担当する。自動起動はせず、Runnerだけが開始する。
 * スレッド管理と実行中タスクの完了検出はSpringへ委譲する。単一プロセス用の制御。
 */
@Component
public class CollectionScheduler implements SmartLifecycle {
    private static final Logger logger = LoggerFactory.getLogger(CollectionScheduler.class);
    // 登録はTaskSchedulerのAPI、停止はSmartLifecycle/ThreadPoolTaskSchedulerのAPIを使う。
    // TaskSchedulerだけでは停止・割込みを扱えないため、注入型はその標準実装とする。
    private final ThreadPoolTaskScheduler collectionTaskScheduler;
    private final CollectionService collectionService;
    private final CollectionSchedulingProperties collectionSchedulingProperties;
    private final Object lifecycleMonitor = new Object();
    private final CompletableFuture<Void> collectionStopCompletion = new CompletableFuture<>();
    private ScheduledFuture<?> scheduledCollectionFuture;
    private boolean started;
    private boolean stopped;
    private boolean stopCompleted;
    private long shutdownStartedAtNanos;

    public CollectionScheduler(
            @Qualifier("collectionTaskScheduler") ThreadPoolTaskScheduler collectionTaskScheduler,
            CollectionService collectionService, CollectionSchedulingProperties collectionSchedulingProperties) {
        this.collectionTaskScheduler = collectionTaskScheduler;
        this.collectionService = collectionService;
        this.collectionSchedulingProperties = collectionSchedulingProperties;
    }

    /** 回収コミット後に初回即時・fixed delayで1回だけ登録する。停止済みなら再開しない。 */
    @Override
    public void start() {
        synchronized (lifecycleMonitor) {
            if (started || stopped) return;
            scheduledCollectionFuture = collectionTaskScheduler.scheduleWithFixedDelay(
                    this::collectSafely, collectionTaskScheduler.getClock().instant(),
                    collectionSchedulingProperties.fixedDelay());
            started = true;
        }
    }

    /** 同期実行を維持する。RuntimeExceptionは記録して継続し、Errorは握りつぶさない。 */
    private void collectSafely() {
        synchronized (lifecycleMonitor) {
            if (stopped) return;
            // この受付を通った呼出しは実行中として、Springの完了通知まで停止処理が待つ。
        }
        try {
            collectionService.collectBtcUsd();
        } catch (RuntimeException runtimeException) {
            logger.error("Unexpected collection failure; next fixed-delay execution remains scheduled", runtimeException);
        }
    }

    /**
     * 予約を非割込みで取り消し、Springのstop(callback)が通知する実処理完了を待つ。
     * Futureのcancel完了を収集完了とはみなさない。重複stopは同じ完了通知と待機枠を共有する。
     */
    @Override
    public void stop() {
        synchronized (lifecycleMonitor) {
            if (!stopped) {
                stopped = true;
                shutdownStartedAtNanos = System.nanoTime();
                if (scheduledCollectionFuture != null) scheduledCollectionFuture.cancel(false);
                collectionTaskScheduler.initiateShutdown();
                collectionTaskScheduler.stop(() -> collectionStopCompletion.complete(null));
            }
        }
        long remainingWaitNanos = Math.max(0, collectionSchedulingProperties.shutdownWait().toNanos()
                - (System.nanoTime() - shutdownStartedAtNanos));
        try {
            collectionStopCompletion.get(remainingWaitNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeoutException) {
            logger.warn("Collection shutdown wait exceeded {}; requesting interruption",
                    collectionSchedulingProperties.shutdownWait());
            collectionTaskScheduler.shutdown();
        } catch (InterruptedException interruptedException) {
            collectionTaskScheduler.shutdown();
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while waiting for collection shutdown", interruptedException);
        } catch (ExecutionException executionException) {
            // 完了通知は正常完了のみを設定する。将来の変更で例外完了しても終了処理を放置しない。
            collectionTaskScheduler.shutdown();
            logger.error("Collection shutdown completion failed", executionException.getCause());
        } finally {
            synchronized (lifecycleMonitor) { stopCompleted = true; }
        }
    }

    /** Springの停止フェーズでも同じ1枠の待機を使い、DB等のBean破棄へ進む前に通知する。 */
    @Override
    public void stop(Runnable callback) {
        try { stop(); } finally { callback.run(); }
    }

    /** 回収前の自動startを防ぐ。Scheduler基盤の自動初期化と業務タスクの開始は別。 */
    @Override
    public boolean isAutoStartup() { return false; }

    /** Spring標準Schedulerより先に停止する。通常のBean破棄はこの停止フェーズより後。 */
    @Override
    public int getPhase() { return SmartLifecycle.DEFAULT_PHASE; }

    /** 手動stop中も完了/時間切れまではtrueにし、同時Context.closeが待機を飛ばさない。 */
    @Override
    public boolean isRunning() {
        synchronized (lifecycleMonitor) { return started && !stopCompleted; }
    }
}
