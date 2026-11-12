CREATE TABLE collection_runs (
                                 id BIGINT NOT NULL AUTO_INCREMENT,
                                 exchange VARCHAR(32) NOT NULL,
                                 symbol VARCHAR(32) NOT NULL,
                                 status VARCHAR(16) NOT NULL,
                                 started_at DATETIME(6) NOT NULL,
                                 finished_at DATETIME(6) NULL,
                                 interruption_detected_at DATETIME(6) NULL,
                                 result_code VARCHAR(64) NULL,

                                 CONSTRAINT pk_collection_runs
                                     PRIMARY KEY (id),

                                 CONSTRAINT chk_collection_runs_status
                                     CHECK (
                                         status IN (
                                                    'RUNNING',
                                                    'SUCCESS',
                                                    'FAILED',
                                                    'SKIPPED',
                                                    'INTERRUPTED'
                                             )
                                         )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_as_cs;