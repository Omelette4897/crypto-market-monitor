package com.example.cryptomonitor.market.dto;

/** 一覧の件数制限前の全期間集計。0件では架空のゼロ価格を作らず金額をnullにする。 */
public record SpreadStatistics(long observationCount, String averageSpread,
        String minimumSpread, String maximumSpread) {}
