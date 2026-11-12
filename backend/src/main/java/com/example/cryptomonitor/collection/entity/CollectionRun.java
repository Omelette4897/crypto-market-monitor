package com.example.cryptomonitor.collection.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * collection_runsの1行。開始履歴を先に確定し、取得中の停止も追跡できるようにする。
 * 結果確定はRepositoryの条件付きUPDATEで行い、自由なsetterによる状態変更を許さない。
 */
@Entity
@Table(name = "collection_runs")
public class CollectionRun {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 32)
    private String exchange;
    @Column(nullable = false, length = 32)
    private String symbol;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CollectionRunStatus status;
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "started_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant startedAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "finished_at", columnDefinition = "datetime(6)")
    private Instant finishedAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "interruption_detected_at", columnDefinition = "datetime(6)")
    private Instant interruptionDetectedAt;
    @Column(name = "result_code", length = 64)
    private String resultCode;

    /** JPAがDBから復元するときに使う。呼出し元は開始用factoryを利用する。 */
    protected CollectionRun() {}

    /** 開始時点だけを生成する。時刻はDBの丸めに任せず、明示的にマイクロ秒へ切り捨てる。 */
    public static CollectionRun start(String exchange, String symbol, Instant startedAt) {
        var collectionRun = new CollectionRun();
        collectionRun.exchange = Objects.requireNonNull(exchange);
        collectionRun.symbol = Objects.requireNonNull(symbol);
        collectionRun.status = CollectionRunStatus.RUNNING;
        collectionRun.startedAt = Objects.requireNonNull(startedAt).truncatedTo(ChronoUnit.MICROS);
        return collectionRun;
    }

    // 参照専用。状態更新は条件付きSQLを経由し、同時更新や古いEntityによる上書きを避ける。
    public Long getId() { return id; }
    public String getExchange() { return exchange; }
    public String getSymbol() { return symbol; }
    public CollectionRunStatus getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Instant getInterruptionDetectedAt() { return interruptionDetectedAt; }
    public String getResultCode() { return resultCode; }
}
