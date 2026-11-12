package com.example.cryptomonitor.collection.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.TaskUtils;

/** 収集専用のSpring TaskSchedulerを構成する。Boot全体のspring.task.scheduling設定とは分離する。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CollectionSchedulingProperties.class)
public class CollectionSchedulingConfiguration {
    /**
     * スレッドの生成・初期化・破棄はSpringに任せる。タスク登録だけは回収後のRunnerから行う。
     * 停止待機はCollectionSchedulerがSpringのstop(callback)完了通知を使って1枠だけ管理する。
     */
    @Bean
    ThreadPoolTaskScheduler collectionTaskScheduler() {
        var collectionTaskScheduler = new ThreadPoolTaskScheduler();
        collectionTaskScheduler.setPoolSize(1);
        collectionTaskScheduler.setThreadNamePrefix("market-collection-");
        collectionTaskScheduler.setRemoveOnCancelPolicy(true);
        collectionTaskScheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        collectionTaskScheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        // 初期値を明示する。stop(callback)で猶予を与えた後のshutdown()は割込み要求だけを行う。
        collectionTaskScheduler.setWaitForTasksToCompleteOnShutdown(false);
        collectionTaskScheduler.setAwaitTerminationMillis(0);
        // Spring既定の定期タスクはErrorも抑制するため、既存の「Errorは伝播」を維持する。
        // 通常のRuntimeExceptionはCollectionScheduler側で記録し、次回を継続する。
        collectionTaskScheduler.setErrorHandler(TaskUtils.LOG_AND_PROPAGATE_ERROR_HANDLER);
        return collectionTaskScheduler;
    }
}
