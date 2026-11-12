package com.example.cryptomonitor.collection.startup;

import com.example.cryptomonitor.collection.config.CollectionSchedulingProperties;
import com.example.cryptomonitor.collection.scheduler.CollectionScheduler;
import com.example.cryptomonitor.collection.service.CollectionPersistenceService;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 機能DのRunner UT。保存ServiceとSchedulerはモックで、DB/HTTP/実スレッドは使わない。
 * 実行：mvnw.cmd test / IntelliJ。共通設定は下記のpropertiesメソッド参照。
 * 実DBコミットはCollectionPersistenceIT、Springの起動失敗伝播はライフサイクル試験で確認する。
 */
@DisplayName("機能D / Runner UT：回収と開始の順序")
class CollectionStartupRunnerTest {
    /** 前提：回収が0件/複数件で正常復帰。操作：run。期待：回収の後に1回だけ開始する。 */
    @DisplayName("D-START-01：回収正常復帰の後で収集を開始する")
    @ParameterizedTest(name = "回収件数={0} → 回収後に開始")
    @ValueSource(ints = {0, 3})
    void startsOnlyAfterRecoveryReturns(int updatedRowCount) {
        var collectionPersistenceService = mock(CollectionPersistenceService.class);
        var collectionScheduler = mock(CollectionScheduler.class);
        when(collectionPersistenceService.recoverInterruptedRuns()).thenReturn(updatedRowCount);
        new CollectionStartupRunner(collectionPersistenceService, collectionScheduler, properties(true)).run(null);
        var invocationOrder = inOrder(collectionPersistenceService, collectionScheduler);
        invocationOrder.verify(collectionPersistenceService).recoverInterruptedRuns();
        invocationOrder.verify(collectionScheduler).start();
        invocationOrder.verifyNoMoreInteractions();
    }

    /** 前提：無効化設定。操作：run。期待：回収を呼ぶが、収集を登録しない。 */
    @DisplayName("D-START-02：収集無効でも回収は実施する")
    @Test void recoversEvenWhenAutomaticCollectionIsDisabled() {
        var collectionPersistenceService = mock(CollectionPersistenceService.class);
        var collectionScheduler = mock(CollectionScheduler.class);
        new CollectionStartupRunner(collectionPersistenceService, collectionScheduler, properties(false)).run(null);
        verify(collectionPersistenceService).recoverInterruptedRuns();
        verifyNoInteractions(collectionScheduler);
    }

    /** 前提：回収が例外。操作：有効/無効それぞれrun。期待：同じ例外を伝播し、開始しない。 */
    @DisplayName("D-START-03：回収失敗は有効無効を問わず伝播する")
    @ParameterizedTest(name = "enabled={0} → 例外伝播・開始なし")
    @ValueSource(booleans = {true, false})
    void propagatesRecoveryFailure(boolean enabled) {
        var collectionPersistenceService = mock(CollectionPersistenceService.class);
        var collectionScheduler = mock(CollectionScheduler.class);
        var recoveryException = new IllegalStateException("database unavailable");
        when(collectionPersistenceService.recoverInterruptedRuns()).thenThrow(recoveryException);
        var collectionStartupRunner = new CollectionStartupRunner(collectionPersistenceService, collectionScheduler, properties(enabled));
        assertSame(recoveryException, assertThrows(IllegalStateException.class, () -> collectionStartupRunner.run(null)));
        verifyNoInteractions(collectionScheduler);
    }

    /** 承認済みの周期・待機枠をUTへ渡す。実際に60秒/30秒待つものではない。 */
    private static CollectionSchedulingProperties properties(boolean enabled) {
        return new CollectionSchedulingProperties(enabled, Duration.ofSeconds(60), Duration.ofSeconds(30));
    }
}
