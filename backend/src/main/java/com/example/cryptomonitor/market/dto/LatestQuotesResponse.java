package com.example.cryptomonitor.market.dto;

import java.util.List;

/** 最新価格一覧の応答。0件でもitemsは空配列で返し、単一オブジェクト/nullへ形を変えない。 */
public record LatestQuotesResponse(List<LatestQuoteItem> items) {}
