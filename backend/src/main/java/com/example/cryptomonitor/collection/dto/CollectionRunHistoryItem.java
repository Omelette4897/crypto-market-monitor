package com.example.cryptomonitor.collection.dto;

import java.time.Instant;

/** 実行履歴1件の公開DTO。DBのEntityとは分離し、理由説明は保存せず検索時に生成する。 */
public record CollectionRunHistoryItem(String id, String exchange, String symbol, String status,
        Instant startedAt, Instant finishedAt, Instant interruptionDetectedAt,
        String resultCode, String resultDescription) {}
