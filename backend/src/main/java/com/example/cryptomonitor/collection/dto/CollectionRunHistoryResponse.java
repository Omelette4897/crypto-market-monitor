package com.example.cryptomonitor.collection.dto;

import java.util.List;

/** 実行履歴一覧。該当なしもitems=[]とし、検索失敗とは区別する。 */
public record CollectionRunHistoryResponse(List<CollectionRunHistoryItem> items) {}
