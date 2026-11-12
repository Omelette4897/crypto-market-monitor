package com.example.cryptomonitor.market.dto;

import java.time.Instant;

/** 最新の保存観測1件。IDと数値はJSON文字列、InstantはUTCのZ形式で返す。 */
public record LatestQuoteItem(String id, String exchange, String symbol,
        String bidPrice, String bidSize, String askPrice, String askSize,
        Instant observedAt, Instant sourceAt) {}
