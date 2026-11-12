package com.example.cryptomonitor.collection.scheduler;

import com.example.cryptomonitor.collection.config.CollectionSchedulingProperties;
import com.example.cryptomonitor.collection.service.CollectionService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ExecutorConfigurationSupport;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 機能DのScheduler UT。Service/Spring TaskSchedulerはモック、登録Runnableを手動実行する。
 * 実行：mvnw.cmd test / IntelliJ。DB/HTTP/実際の60秒・30秒待機は使わない。
 * 実スレッドとSpring終了順はCollectionLifecycleTest、実DB回収はCollectionPersistenceITを参照。
 */
@DisplayName("機能D / Scheduler UT：Springへの登録・継続・停止")
class CollectionSchedulerTest {
    private static final Instant FIXED_CURRENT_TIME = Instant.parse("2026-11-09T01:00:00Z");
    private final ThreadPoolTaskScheduler collectionTaskScheduler = mock(ThreadPoolTaskScheduler.class);
    private final CollectionService collectionService = mock(CollectionService.class);
    private final ScheduledFuture<?> scheduledCollectionFuture = mock(ScheduledFuture.class);
    private CollectionScheduler collectionScheduler;

    /** Springの完了通知は既定で即時に返す。収集自体はcaptorから明示実行するまで動かさない。 */
    @BeforeEach void prepareScheduler() {
        when(collectionTaskScheduler.getClock()).thenReturn(Clock.fixed(FIXED_CURRENT_TIME, java.time.ZoneOffset.UTC));
        doReturn(scheduledCollectionFuture).when(collectionTaskScheduler)
                .scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class));
        doAnswer(invocation -> { invocation.getArgument(0, Runnable.class).run(); return null; })
                .when(collectionTaskScheduler).stop(any(Runnable.class));
        collectionScheduler = schedulerWithShutdownWait(Duration.ofSeconds(30));
    }

    /** 前提：未開始。操作：startを2回。期待：開始時刻は現在、fixed delay60秒、登録1回。 */
    @DisplayName("D-SCHED-01：即時・fixed delayで1回だけ登録する")
    @Test void registersImmediateFixedDelayOnlyOnce() {
        collectionScheduler.start();
        collectionScheduler.start();
        verify(collectionTaskScheduler).scheduleWithFixedDelay(any(Runnable.class), eq(FIXED_CURRENT_TIME), eq(Duration.ofSeconds(60)));
        assertTrue(collectionScheduler.isRunning());
        verifyNoInteractions(collectionService);
    }

    /** 前提：初回RuntimeException。操作：登録callbackを2回。期待：例外を記録し次回も同期実行。 */
    @DisplayName("D-SCHED-02：RuntimeException後も次回を実行できる")
    @Test void continuesAfterUnexpectedRuntimeException() {
        doThrow(new IllegalStateException("unexpected")).doNothing().when(collectionService).collectBtcUsd();
        Runnable collectionCallback = registeredCallback();
        assertDoesNotThrow(collectionCallback::run);
        assertDoesNotThrow(collectionCallback::run);
        verify(collectionService, times(2)).collectBtcUsd();
    }

    /** 前提：ServiceがError。操作：callbackを実行。期待：アプリ側で握りつぶさない。Spring境界は設定試験も参照。 */
    @DisplayName("D-SCHED-03：Errorを通常失敗として握りつぶさない")
    @Test void propagatesError() {
        var fatalError = new AssertionError("fatal");
        doThrow(fatalError).when(collectionService).collectBtcUsd();
        Runnable collectionCallback = registeredCallback();
        assertSame(fatalError, assertThrows(AssertionError.class, collectionCallback::run));
    }

    /** 前提：開始済み。操作：stopを2回・start・遅延callback。期待：非割込み取消、停止通知1回、再収集なし。 */
    @DisplayName("D-SCHED-04：重複停止・停止後の開始で収集しない")
    @Test void stopsWithoutInterruptingAndNeverRestarts() {
        Runnable collectionCallback = registeredCallback();
        collectionScheduler.stop();
        collectionScheduler.stop();
        collectionScheduler.start();
        collectionCallback.run();
        var invocationOrder = inOrder(scheduledCollectionFuture, collectionTaskScheduler);
        invocationOrder.verify(scheduledCollectionFuture).cancel(false);
        invocationOrder.verify(collectionTaskScheduler).initiateShutdown();
        invocationOrder.verify(collectionTaskScheduler).stop(any(Runnable.class));
        verify(collectionTaskScheduler, never()).shutdown();
        verify(collectionTaskScheduler).scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class));
        assertFalse(collectionScheduler.isRunning());
        verifyNoInteractions(collectionService);
    }

    /** 前提：未開始。操作：stop後start。期待：停止通知は行うが、予約も収集も行わない。 */
    @DisplayName("D-SCHED-05：開始前停止も安全に扱う")
    @Test void canStopBeforeStart() {
        collectionScheduler.stop();
        collectionScheduler.start();
        verify(collectionTaskScheduler).initiateShutdown();
        verifyNoInteractions(collectionService, scheduledCollectionFuture);
        verify(collectionTaskScheduler, never()).scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class));
    }

    /** 前提：Springが完了通知を返さない。操作：1nsの待機枠でstop。期待：Springのshutdownで割込み要求、再待機なし。 */
    @DisplayName("D-SCHED-06：待機超過ならSpringに割込みを要求する")
    @Test void requestsInterruptionWhenWaitExpires() {
        doNothing().when(collectionTaskScheduler).stop(any(Runnable.class));
        collectionScheduler = schedulerWithShutdownWait(Duration.ofNanos(1));
        collectionScheduler.start();
        collectionScheduler.stop();
        verify(collectionTaskScheduler).shutdown();
        assertFalse(collectionScheduler.isRunning());
    }

    /** 前提：未完了通知と呼出しスレッドの割込み。操作：stop。期待：割込み要求後も割込みフラグを保持。 */
    @DisplayName("D-SCHED-07：待機中の割込みフラグを復元する")
    @Test void restoresInterruptedFlag() {
        doNothing().when(collectionTaskScheduler).stop(any(Runnable.class));
        try {
            Thread.currentThread().interrupt();
            collectionScheduler.stop();
            assertTrue(Thread.currentThread().isInterrupted());
            verify(collectionTaskScheduler).shutdown();
        } finally { Thread.interrupted(); }
    }

    /** 前提：SpringのLifecycle管理。操作：設定参照とstop(callback)。期待：自動起動なし、基盤より先の停止、通知1回。 */
    @DisplayName("D-SCHED-08：SmartLifecycleで回収前の自動開始を防ぎ、停止完了を通知する")
    @Test void participatesInLifecycleWithoutAutomaticStartup() {
        assertFalse(collectionScheduler.isAutoStartup());
        assertEquals(SmartLifecycle.DEFAULT_PHASE, collectionScheduler.getPhase());
        assertTrue(collectionScheduler.getPhase() > ExecutorConfigurationSupport.DEFAULT_PHASE);
        Runnable stopCallback = mock(Runnable.class);
        collectionScheduler.stop(stopCallback);
        verify(stopCallback).run();
    }

    /** 前提：初回の登録が失敗。操作：再start。期待：失敗時は開始済みにせず正常登録を再試行できる。 */
    @DisplayName("D-SCHED-09：登録失敗で開始済み扱いにしない")
    @Test void doesNotMarkFailedRegistrationAsStarted() {
        doThrow(new RejectedExecutionException()).doReturn(scheduledCollectionFuture).when(collectionTaskScheduler)
                .scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class));
        assertThrows(RejectedExecutionException.class, collectionScheduler::start);
        assertFalse(collectionScheduler.isRunning());
        assertDoesNotThrow(collectionScheduler::start);
    }

    /** 前提：登録の途中。操作：並行stop。期待：登録と停止を直列化し予約取消・基盤への停止通知を行う。 */
    @DisplayName("D-SCHED-10：開始と停止の競合でも予約を取り残さない")
    @Test void serializesStartAndStop() throws Exception {
        var registrationEntered = new CountDownLatch(1);
        var allowRegistration = new CountDownLatch(1);
        var asynchronousFailure = new AtomicReference<Throwable>();
        doAnswer(invocation -> {
            registrationEntered.countDown();
            assertTrue(allowRegistration.await(3, TimeUnit.SECONDS));
            return scheduledCollectionFuture;
        }).when(collectionTaskScheduler).scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class));
        Thread startingThread = guardedThread(collectionScheduler::start, asynchronousFailure);
        Thread stoppingThread = guardedThread(collectionScheduler::stop, asynchronousFailure);
        try {
            startingThread.start();
            assertTrue(registrationEntered.await(3, TimeUnit.SECONDS));
            stoppingThread.start();
        } finally {
            allowRegistration.countDown();
            startingThread.join(3000);
            stoppingThread.join(3000);
        }
        assertFalse(startingThread.isAlive());
        assertFalse(stoppingThread.isAlive());
        assertNull(asynchronousFailure.get());
        verify(scheduledCollectionFuture).cancel(false);
        verify(collectionTaskScheduler).initiateShutdown();
    }

    /** 待機時間だけを変えた試験対象。標準の周期は維持し、モックは自動実行しない。 */
    private CollectionScheduler schedulerWithShutdownWait(Duration shutdownWait) {
        return new CollectionScheduler(collectionTaskScheduler, collectionService,
                new CollectionSchedulingProperties(true, Duration.ofSeconds(60), shutdownWait));
    }

    /** Springへ渡したRunnableを捕捉し、実HTTPを使わず手動実行できるようにする。 */
    private Runnable registeredCallback() {
        collectionScheduler.start();
        var callbackCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(collectionTaskScheduler).scheduleWithFixedDelay(callbackCaptor.capture(), any(Instant.class), any(Duration.class));
        return callbackCaptor.getValue();
    }

    /** 別スレッドのassert失敗も親スレッドで検出する。Latchの待機上限はハング検出用。 */
    private static Thread guardedThread(Runnable action, AtomicReference<Throwable> asynchronousFailure) {
        return Thread.ofPlatform().daemon().unstarted(() -> {
            try { action.run(); } catch (Throwable throwable) { asynchronousFailure.compareAndSet(null, throwable); }
        });
    }
}
