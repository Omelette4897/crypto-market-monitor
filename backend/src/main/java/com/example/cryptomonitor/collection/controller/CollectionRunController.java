package com.example.cryptomonitor.collection.controller;

import com.example.cryptomonitor.collection.dto.CollectionRunHistoryResponse;
import com.example.cryptomonitor.collection.service.CollectionRunQueryService;
import org.springframework.web.bind.annotation.*;

/** 実行履歴GETの受付。検索Serviceへ委譲し、収集進行・状態更新は行わない。 */
@RestController
public class CollectionRunController {
    private final CollectionRunQueryService collectionRunQueryService;

    public CollectionRunController(CollectionRunQueryService collectionRunQueryService) {
        this.collectionRunQueryService = collectionRunQueryService;
    }

    /** limitも文字列のまま渡すことで、省略時の既定値と空入力のエラーを分ける。 */
    @GetMapping("/api/v1/collection-runs")
    public CollectionRunHistoryResponse findCollectionRuns(
            @RequestParam(name = "exchange", required = false) String exchange,
            @RequestParam(name = "symbol", required = false) String symbol,
            @RequestParam(name = "limit", required = false) String limit) {
        return collectionRunQueryService.findCollectionRuns(exchange, symbol, limit);
    }
}
