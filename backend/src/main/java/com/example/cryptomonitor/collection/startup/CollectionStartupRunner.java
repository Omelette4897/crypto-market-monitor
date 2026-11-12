package com.example.cryptomonitor.collection.startup;

import com.example.cryptomonitor.collection.config.CollectionSchedulingProperties;
import com.example.cryptomonitor.collection.scheduler.CollectionScheduler;
import com.example.cryptomonitor.collection.service.CollectionPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Flyway/JPA初期化後に残存RUNNINGを回収し、コミット成功後だけ定期実行を開始する。 */
@Component
public class CollectionStartupRunner implements ApplicationRunner {
    private static final Logger logger = LoggerFactory.getLogger(CollectionStartupRunner.class);
    private final CollectionPersistenceService collectionPersistenceService;
    private final CollectionScheduler collectionScheduler;
    private final CollectionSchedulingProperties collectionSchedulingProperties;

    public CollectionStartupRunner(CollectionPersistenceService collectionPersistenceService,
            CollectionScheduler collectionScheduler, CollectionSchedulingProperties collectionSchedulingProperties) {
        this.collectionPersistenceService = collectionPersistenceService;
        this.collectionScheduler = collectionScheduler;
        this.collectionSchedulingProperties = collectionSchedulingProperties;
    }

    /** 回収例外を握りつぶさない。enabled=falseでも回収は必要で、失敗なら起動を失敗させる。 */
    @Override
    public void run(ApplicationArguments applicationArguments) {
        int updatedRowCount = collectionPersistenceService.recoverInterruptedRuns();
        logger.info("Recovered interrupted collection runs: {}", updatedRowCount);
        if (collectionSchedulingProperties.enabled()) {
            collectionScheduler.start();
        } else {
            logger.info("Automatic collection is disabled; startup recovery completed");
        }
    }
}
