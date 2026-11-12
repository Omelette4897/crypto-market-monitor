CREATE TABLE market_snapshots (
                                  id BIGINT NOT NULL AUTO_INCREMENT,
                                  run_id BIGINT NOT NULL,
                                  bid_price DECIMAL(30,12) NOT NULL,
                                  bid_size DECIMAL(30,12) NOT NULL,
                                  ask_price DECIMAL(30,12) NOT NULL,
                                  ask_size DECIMAL(30,12) NOT NULL,
                                  observed_at DATETIME(6) NOT NULL,
                                  source_at DATETIME(6) NULL,

                                  CONSTRAINT pk_market_snapshots
                                      PRIMARY KEY (id),

                                  CONSTRAINT uq_market_snapshots_run
                                      UNIQUE (run_id),

                                  CONSTRAINT fk_market_snapshots_run
                                      FOREIGN KEY (run_id)
                                          REFERENCES collection_runs (id)
                                          ON DELETE RESTRICT
                                          ON UPDATE RESTRICT,

                                  CONSTRAINT chk_market_snapshots_prices
                                      CHECK (bid_price > 0 AND ask_price > 0),

                                  CONSTRAINT chk_market_snapshots_sizes
                                      CHECK (bid_size >= 0 AND ask_size >= 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_as_cs;