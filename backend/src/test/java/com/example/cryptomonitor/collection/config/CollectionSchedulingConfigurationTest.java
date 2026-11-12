package com.example.cryptomonitor.collection.config;

import java.time.Duration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 機能Dの設定結合試験。実Springの設定バインド・実ThreadPoolTaskSchedulerを検査する。
 * Errorの伝播確認だけローカル処理を予約する。DBや実外部APIを呼ぶタスクではない。
 * 実行：mvnw.cmd test / IntelliJ。DB/HTTP/STは対象外。Schedulerの生成と破棄はSpringへ任せる。
 */
@DisplayName("機能D / 設定結合確認：既定値・不正値・専用スレッド")
class CollectionSchedulingConfigurationTest {
    private final ApplicationContextRunner applicationContextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CollectionSchedulingConfiguration.class);

    /** 前提：設定省略。操作：コンテキスト生成。期待：true/60s/30sと専用1スレッドの停止方針。 */
    @DisplayName("D-CONFIG-01：既定値と専用1スレッド構成")
    @Test void bindsDefaultsAndCreatesDedicatedTaskScheduler() {
        applicationContextRunner.run(applicationContext -> {
            assertNull(applicationContext.getStartupFailure());
            var collectionSchedulingProperties = applicationContext.getBean(CollectionSchedulingProperties.class);
            var collectionTaskScheduler = applicationContext.getBean(ThreadPoolTaskScheduler.class);
            try {
                assertAll(() -> assertTrue(collectionSchedulingProperties.enabled()),
                        () -> assertEquals(Duration.ofSeconds(60), collectionSchedulingProperties.fixedDelay()),
                        () -> assertEquals(Duration.ofSeconds(30), collectionSchedulingProperties.shutdownWait()),
                        () -> assertEquals(1, collectionTaskScheduler.getScheduledThreadPoolExecutor().getCorePoolSize()),
                        () -> assertFalse(collectionTaskScheduler.getScheduledThreadPoolExecutor().getContinueExistingPeriodicTasksAfterShutdownPolicy()),
                        () -> assertFalse(collectionTaskScheduler.getScheduledThreadPoolExecutor().getExecuteExistingDelayedTasksAfterShutdownPolicy()));
            } finally { collectionTaskScheduler.shutdown(); }
        });
    }

    /** 前提：明示設定。操作：バインド。期待：無効化・周期・停止待機時間が指定値になる。 */
    @DisplayName("D-CONFIG-02：外部設定をバインドする")
    @Test void bindsOverrides() {
        applicationContextRunner.withPropertyValues("market-monitor.collection.enabled=false",
                "market-monitor.collection.fixed-delay=2s", "market-monitor.collection.shutdown-wait=1s")
                .run(applicationContext -> {
                    var collectionTaskScheduler = applicationContext.getBean(ThreadPoolTaskScheduler.class);
                    try {
                        assertEquals(new CollectionSchedulingProperties(false, Duration.ofSeconds(2), Duration.ofSeconds(1)),
                                applicationContext.getBean(CollectionSchedulingProperties.class));
                    } finally { collectionTaskScheduler.shutdown(); }
                });
    }

    /** 前提：実Spring Scheduler。操作：Errorを投げるタスクを予約。期待：Futureが例外完了し抑制されない。 */
    @DisplayName("D-CONFIG-04：SpringのErrorHandlerもErrorを伝播する")
    @Test void realSchedulerPropagatesError() {
        applicationContextRunner.run(applicationContext -> {
            var collectionTaskScheduler = applicationContext.getBean(ThreadPoolTaskScheduler.class);
            var fatalError = new AssertionError("fatal scheduling test");
            var scheduledTask = collectionTaskScheduler.scheduleWithFixedDelay(() -> { throw fatalError; }, Duration.ofSeconds(60));
            var executionException = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> scheduledTask.get(3, java.util.concurrent.TimeUnit.SECONDS));
            assertSame(fatalError, executionException.getCause());
        });
    }

    /** 前提：Boot標準用に別のpool/prefixを指定。操作：専用Beanを起動。期待：専用の1スレッド・名称を維持。 */
    @DisplayName("D-CONFIG-05：Boot全体の設定が収集専用Schedulerへ混入しない")
    @Test void keepsDedicatedSettingsSeparateFromBootSchedulingProperties() {
        applicationContextRunner.withPropertyValues("spring.task.scheduling.pool.size=8",
                "spring.task.scheduling.thread-name-prefix=unrelated-").run(applicationContext -> {
                    var collectionTaskScheduler = applicationContext.getBean(ThreadPoolTaskScheduler.class);
                    assertEquals(1, collectionTaskScheduler.getScheduledThreadPoolExecutor().getCorePoolSize());
                    assertEquals("market-collection-", collectionTaskScheduler.getThreadNamePrefix());
                });
    }

    /** 前提：ゼロ/負/ナノ秒へ変換不可/非時間。操作：起動。期待：設定エラーで起動失敗。 */
    @DisplayName("D-CONFIG-03：無効な時間設定で起動を拒否する")
    @ParameterizedTest(name = "{0} → 設定起動失敗")
    @ValueSource(strings = {"fixed-delay=0s", "fixed-delay=-1s", "fixed-delay=999999999999s",
            "fixed-delay=invalid", "shutdown-wait=0s", "shutdown-wait=-1s", "shutdown-wait=999999999999s"})
    void rejectsInvalidConfiguration(String propertyAssignment) {
        applicationContextRunner.withPropertyValues("market-monitor.collection." + propertyAssignment)
                .run(applicationContext -> assertNotNull(applicationContext.getStartupFailure()));
    }
}
