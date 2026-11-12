package com.example.cryptomonitor.market.dto;

import java.time.Instant;
import java.util.List;

/** 検索期間と一覧・全期間統計を同じ応答で返す。truncatedは一覧のみの省略を示す。 */
public record QuoteHistoryResponse(Instant rangeStart, Instant rangeEnd, boolean truncated,
        List<QuoteHistoryItem> items, SpreadStatistics spreadStatistics) {}
