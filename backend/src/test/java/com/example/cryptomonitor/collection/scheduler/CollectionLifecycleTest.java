package com.example.cryptomonitor.collection.scheduler;

import com.example.cryptomonitor.collection.config.CollectionSchedulingConfiguration;
import com.example.cryptomonitor.collection.config.CollectionSchedulingProperties;
import com.example.cryptomonitor.collection.service.CollectionPersistenceService;
import com.example.cryptomonitor.collection.service.CollectionService;
import com.example.cryptomonitor.collection.startup.CollectionStartupRunner;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.beans.factory.config.BeanPostProcessor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 機能Dの起動/終了ライフサイクル結合確認。実Spring・ThreadPoolTaskSchedulerを使い、保存/収集Serviceはモック。
 * 実行：mvnw.cmd test / IntelliJ。実DB・HTTP・強制プロセス停止を行うSTではない。
 * DB代替のDisposableBeanで破棄順を観測する。実DB回収Txと実DataSourceの存続はCollectionPersistenceITで別途確認。
 * 同期はLatchを使い、3秒は失敗検出の上限。各SchedulerとContextはfinally/tryで必ず閉じる。
 */
@DisplayName("機能D / ライフサイクル結合：起動・直列実行・終了順序")
class CollectionLifecycleTest {
    /** 前提：無効設定・回収正常。操作：実SpringApplicationを起動/終了。期待：回収1回、収集0回。 */
    @DisplayName("D-LIFE-01：Spring起動で回収し、無効設定では収集しない")
    @Test void bootRunsRecoveryWithoutCollectionWhenDisabled() {
        var collectionPersistenceService = mock(CollectionPersistenceService.class);
        var collectionService = mock(CollectionService.class);
        try (var applicationContext = application(collectionPersistenceService, collectionService)
                .run("--market-monitor.collection.enabled=false")) {
            assertTrue(applicationContext.isActive());
            verify(collectionPersistenceService).recoverInterruptedRuns();
            verifyNoInteractions(collectionService);
        }
    }

    /** 前提：回収失敗。操作：実SpringApplication起動。期待：起動例外、収集なし、作成Schedulerも解放。 */
    @DisplayName("D-LIFE-02：回収例外でSpring起動を失敗させる")
    @Test void recoveryFailureFailsBootAndReleasesExecutor() {
        var collectionPersistenceService = mock(CollectionPersistenceService.class);
        var collectionService = mock(CollectionService.class);
        when(collectionPersistenceService.recoverInterruptedRuns()).thenThrow(new IllegalStateException("recovery unavailable"));
        var collectionTaskSchedulerReference = new AtomicReference<ThreadPoolTaskScheduler>();
        var springApplication = application(collectionPersistenceService, collectionService);
        springApplication.addInitializers(applicationContext -> applicationContext.addApplicationListener(applicationEvent -> {
            if (applicationEvent instanceof org.springframework.context.event.ContextRefreshedEvent) {
                collectionTaskSchedulerReference.set(applicationContext.getBean("collectionTaskScheduler", ThreadPoolTaskScheduler.class));
            }
        }));
        assertThrows(IllegalStateException.class, () -> springApplication.run("--market-monitor.collection.enabled=true"));
        verifyNoInteractions(collectionService);
        assertNotNull(collectionTaskSchedulerReference.get());
        assertTrue(collectionTaskSchedulerReference.get().getScheduledThreadPoolExecutor().isTerminated());
    }

    /** 前提：短い周期・初回RuntimeException。操作：実スレッドで3回実行。期待：再実行・非重複・終了後の間隔。 */
    @DisplayName("D-LIFE-03：失敗後も直列にfixed delayで継続する")
    @Test void realTaskSchedulerContinuesWithFixedDelayWithoutOverlap() throws Exception {
        var collectionService = mock(CollectionService.class);
        var invocationCount = new AtomicInteger();
        var activeInvocationCount = new AtomicInteger();
        var maximumActiveCount = new AtomicInteger();
        var completedInvocations = new CountDownLatch(3);
        List<Long> startedAtNanos = new CopyOnWriteArrayList<>();
        List<Long> finishedAtNanos = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            maximumActiveCount.accumulateAndGet(activeInvocationCount.incrementAndGet(), Math::max);
            startedAtNanos.add(System.nanoTime());
            try {
                if (invocationCount.incrementAndGet() == 1) throw new IllegalStateException("first collection failed");
                return null;
            } finally {
                finishedAtNanos.add(System.nanoTime());
                activeInvocationCount.decrementAndGet();
                completedInvocations.countDown();
            }
        }).when(collectionService).collectBtcUsd();
        try (var applicationContext = schedulerContext(collectionService, Duration.ofSeconds(1), null)) {
            applicationContext.getBean(CollectionScheduler.class).start();
            assertTrue(completedInvocations.await(3, TimeUnit.SECONDS));
            applicationContext.getBean(CollectionScheduler.class).stop();
            assertEquals(1, maximumActiveCount.get());
            for (int invocationIndex = 1; invocationIndex < startedAtNanos.size(); invocationIndex++) {
                assertTrue(startedAtNanos.get(invocationIndex) - finishedAtNanos.get(invocationIndex - 1)
                        >= Duration.ofMillis(20).toNanos());
            }
        }
    }

    /** 前提：収集中。操作：Context.closeと重複stopを開始後、処理完了を許可。期待：DB代替Beanは完了後に破棄。 */
    @DisplayName("D-LIFE-04：実行中収集の完了を待ってからBeanを破棄する")
    @Test void closeWaitsForInFlightCollectionBeforeDestroyingResources() throws Exception {
        var collectionStarted = new CountDownLatch(1);
        var allowCollectionCompletion = new CountDownLatch(1);
        var shutdownRequested = new CountDownLatch(1);
        var resourceDestroyed = new AtomicBoolean();
        var resourceWasAvailable = new AtomicBoolean();
        var collectionService = mock(CollectionService.class);
        doAnswer(invocation -> {
            collectionStarted.countDown();
            assertTrue(allowCollectionCompletion.await(3, TimeUnit.SECONDS));
            resourceWasAvailable.set(!resourceDestroyed.get());
            return null;
        }).when(collectionService).collectBtcUsd();
        var applicationContext = schedulerContext(collectionService, Duration.ofSeconds(2), shutdownRequested);
        var collectionTaskScheduler = applicationContext.getBean(ThreadPoolTaskScheduler.class);
        applicationContext.getBeanFactory().registerSingleton("databaseResourceStandIn", (DisposableBean) () -> resourceDestroyed.set(true));
        // registerSingleton単独では破棄コールバック登録は行われないため明示する。
        applicationContext.getDefaultListableBeanFactory().registerDisposableBean("databaseResourceStandIn",
                () -> resourceDestroyed.set(true));
        CompletableFuture<Void> closingFuture = null;
        CompletableFuture<Void> repeatedStopFuture = null;
        try {
            var collectionScheduler = applicationContext.getBean(CollectionScheduler.class);
            collectionScheduler.start();
            assertTrue(collectionStarted.await(3, TimeUnit.SECONDS));
            closingFuture = CompletableFuture.runAsync(applicationContext::close);
            assertTrue(shutdownRequested.await(3, TimeUnit.SECONDS));
            repeatedStopFuture = CompletableFuture.runAsync(collectionScheduler::stop);
            assertFalse(resourceDestroyed.get());
            assertFalse(closingFuture.isDone());
            allowCollectionCompletion.countDown();
            closingFuture.get(3, TimeUnit.SECONDS);
            repeatedStopFuture.get(3, TimeUnit.SECONDS);
            assertTrue(resourceWasAvailable.get());
            assertTrue(resourceDestroyed.get());
            assertTrue(collectionTaskScheduler.getScheduledThreadPoolExecutor().isTerminated());
            verify(collectionService).collectBtcUsd();
        } finally {
            allowCollectionCompletion.countDown();
            if (closingFuture != null) closingFuture.get(3, TimeUnit.SECONDS);
            if (repeatedStopFuture != null) repeatedStopFuture.get(3, TimeUnit.SECONDS);
            applicationContext.close();
            collectionTaskScheduler.shutdown();
        }
    }

    /** 前提：割込みを受けても終了しない処理。操作：短い待機枠でstop。期待：割込み要求し戻るが処理完了を偽らない。 */
    @DisplayName("D-LIFE-05：待機超過は処理強制終了の保証ではない")
    @Test void timeoutRequestsInterruptionWithoutClaimingCompletion() throws Exception {
        var collectionStarted = new CountDownLatch(1);
        var allowCollectionCompletion = new CountDownLatch(1);
        var interruptionObserved = new CountDownLatch(1);
        var collectionService = mock(CollectionService.class);
        doAnswer(invocation -> {
            collectionStarted.countDown();
            boolean completed = false;
            while (!completed) {
                try { completed = allowCollectionCompletion.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException interruptedException) { interruptionObserved.countDown(); }
            }
            return null;
        }).when(collectionService).collectBtcUsd();
        var applicationContext = schedulerContext(collectionService, Duration.ofMillis(20), null);
        var collectionTaskScheduler = applicationContext.getBean(ThreadPoolTaskScheduler.class);
        try (applicationContext) {
            var collectionScheduler = applicationContext.getBean(CollectionScheduler.class);
            collectionScheduler.start();
            assertTrue(collectionStarted.await(3, TimeUnit.SECONDS));
            collectionScheduler.stop();
            assertTrue(interruptionObserved.await(3, TimeUnit.SECONDS));
            assertFalse(collectionTaskScheduler.getScheduledThreadPoolExecutor().isTerminated());
            allowCollectionCompletion.countDown();
            assertTrue(collectionTaskScheduler.getScheduledThreadPoolExecutor().awaitTermination(3, TimeUnit.SECONDS));
        } finally {
            allowCollectionCompletion.countDown();
            collectionTaskScheduler.shutdown();
        }
    }

    /**
     * 観点D-LIFE-06。前提：割込みでも終了しない収集、停止枠20ms。
     * 操作：実Context.closeを呼ぶ。期待：割込みを要求して戻り、Springが追加の30秒待機をしない。
     * 猶予超過後も処理が生存し得る限界を確認し、最後はLatchで解放する。時間のassertはハング検出用。
     */
    @DisplayName("D-LIFE-06：Context.closeの待機超過で二重待機せず割込みを要求する")
    @Test void contextCloseTimesOutWithoutAnAdditionalLifecycleWait() throws Exception {
        var collectionStarted = new CountDownLatch(1);
        var allowCollectionCompletion = new CountDownLatch(1);
        var interruptionObserved = new CountDownLatch(1);
        var collectionService = mock(CollectionService.class);
        doAnswer(invocation -> {
            collectionStarted.countDown();
            while (allowCollectionCompletion.getCount() != 0) {
                try { allowCollectionCompletion.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException interruptedException) { interruptionObserved.countDown(); }
            }
            return null;
        }).when(collectionService).collectBtcUsd();
        var applicationContext = schedulerContext(collectionService, Duration.ofMillis(20), null);
        var collectionTaskScheduler = applicationContext.getBean(ThreadPoolTaskScheduler.class);
        CompletableFuture<Void> closingFuture = null;
        try {
            applicationContext.getBean(CollectionScheduler.class).start();
            assertTrue(collectionStarted.await(3, TimeUnit.SECONDS));
            closingFuture = CompletableFuture.runAsync(applicationContext::close);
            closingFuture.get(3, TimeUnit.SECONDS);
            assertTrue(interruptionObserved.await(3, TimeUnit.SECONDS));
            assertFalse(applicationContext.isActive());
            assertFalse(collectionTaskScheduler.getScheduledThreadPoolExecutor().isTerminated());
        } finally {
            allowCollectionCompletion.countDown();
            if (closingFuture != null) closingFuture.get(3, TimeUnit.SECONDS);
            applicationContext.close();
            assertTrue(collectionTaskScheduler.getScheduledThreadPoolExecutor().awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    /**
     * 観点D-LIFE-07。前提：収集有効、回収正常。操作：本番4クラスを実Bootで起動。
     * 期待：回収復帰後に初回収集を即時予約し、モック収集が実スレッドで動く。実DB/HTTPは対象外。
     */
    @DisplayName("D-LIFE-07：実Bootの回収復帰後に初回収集を開始する")
    @Test void bootStartsFirstCollectionAfterRecoveryReturns() throws Exception {
        var recoveryCompleted = new AtomicBoolean();
        var firstCollectionCompleted = new CountDownLatch(1);
        var recoveryWasCompleteAtCollection = new AtomicBoolean();
        var collectionPersistenceService = mock(CollectionPersistenceService.class);
        var collectionService = mock(CollectionService.class);
        when(collectionPersistenceService.recoverInterruptedRuns()).thenAnswer(invocation -> {
            recoveryCompleted.set(true);
            return 0;
        });
        doAnswer(invocation -> {
            recoveryWasCompleteAtCollection.set(recoveryCompleted.get());
            firstCollectionCompleted.countDown();
            return null;
        }).when(collectionService).collectBtcUsd();
        try (var applicationContext = application(collectionPersistenceService, collectionService)
                .run("--market-monitor.collection.enabled=true")) {
            assertTrue(firstCollectionCompleted.await(3, TimeUnit.SECONDS));
            assertTrue(recoveryWasCompleteAtCollection.get());
            verify(collectionPersistenceService).recoverInterruptedRuns();
            assertTrue(applicationContext.getBean(CollectionScheduler.class).isRunning());
        }
    }

    /** 実SpringApplicationでRunnerを実行する最小構成。自動設定/DB/HTTPを読み込まない。 */
    private static SpringApplication application(CollectionPersistenceService collectionPersistenceService, CollectionService collectionService) {
        var springApplication = new SpringApplication(StartupTestConfiguration.class);
        springApplication.setWebApplicationType(WebApplicationType.NONE);
        springApplication.setRegisterShutdownHook(false);
        springApplication.addInitializers(applicationContext -> {
            applicationContext.getBeanFactory().registerSingleton("collectionPersistenceService", collectionPersistenceService);
            applicationContext.getBeanFactory().registerSingleton("collectionService", collectionService);
        });
        return springApplication;
    }

    /** 本番4クラスだけを取り込み、起動失敗/無効化を実際のBoot Runner機構へ通す。 */
    @TestConfiguration(proxyBeanMethods = false)
    @Import({CollectionSchedulingConfiguration.class, CollectionScheduler.class, CollectionStartupRunner.class})
    static class StartupTestConfiguration { }

    /**
     * 本番Configurationの実SchedulerをSpringに初期化/破棄させる。短い周期はモック収集だけに適用。
     * 任意のLatchは実stop(callback)の登録後に通知するSpyで、停止処理自体はSpringの実装を通す。
     */
    private static AnnotationConfigApplicationContext schedulerContext(CollectionService collectionService,
            Duration shutdownWait, CountDownLatch shutdownRequested) {
        var applicationContext = new AnnotationConfigApplicationContext();
        applicationContext.getEnvironment().getPropertySources().addFirst(new MapPropertySource("lifecycle-test",
                java.util.Map.of("market-monitor.collection.fixed-delay", "20ms",
                        "market-monitor.collection.shutdown-wait", shutdownWait.toString())));
        if (shutdownRequested != null) {
            applicationContext.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof ThreadPoolTaskScheduler taskScheduler)) return bean;
                    var observedTaskScheduler = spy(taskScheduler);
                    doAnswer(invocation -> {
                        Object invocationResult = invocation.callRealMethod();
                        shutdownRequested.countDown();
                        return invocationResult;
                    }).when(observedTaskScheduler).stop(any(Runnable.class));
                    return observedTaskScheduler;
                }
            });
        }
        applicationContext.registerBean(CollectionService.class, () -> collectionService);
        applicationContext.register(CollectionSchedulingConfiguration.class, CollectionScheduler.class);
        applicationContext.refresh();
        return applicationContext;
    }
}
