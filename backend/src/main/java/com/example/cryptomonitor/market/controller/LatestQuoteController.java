package com.example.cryptomonitor.market.controller;

import com.example.cryptomonitor.market.dto.LatestQuotesResponse;
import com.example.cryptomonitor.market.service.LatestQuoteQueryService;
import org.springframework.web.bind.annotation.*;

/** 最新保存価格のHTTP受付。HTTP表現と業務検索を分け、保存や外部API取得を呼ばない。 */
@RestController
public class LatestQuoteController {
    private final LatestQuoteQueryService latestQuoteQueryService;

    public LatestQuoteController(LatestQuoteQueryService latestQuoteQueryService) {
        this.latestQuoteQueryService = latestQuoteQueryService;
    }

    /** 任意条件はnullのままServiceへ渡す。省略と空文字を同一視しない。 */
    @GetMapping("/api/v1/quotes/latest")
    public LatestQuotesResponse findLatestQuotes(
            @RequestParam(name = "exchange", required = false) String exchange,
            @RequestParam(name = "symbol", required = false) String symbol) {
        return latestQuoteQueryService.findLatestQuotes(exchange, symbol);
    }
}
