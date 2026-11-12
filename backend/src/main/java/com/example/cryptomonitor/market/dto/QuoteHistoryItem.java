package com.example.cryptomonitor.market.dto;

import java.time.Instant;

/** 保存済み観測のチャート用DTO。spreadもサーバーで算出し、数値は文字列で公開する。 */
public record QuoteHistoryItem(String id, String exchange, String symbol, Instant observedAt,
        String bidPrice, String askPrice, String spread) {}
