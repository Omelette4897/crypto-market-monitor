package com.example.cryptomonitor.market.controller;

import com.example.cryptomonitor.market.dto.QuoteHistoryResponse;
import com.example.cryptomonitor.market.service.QuoteHistoryQueryService;
import org.springframework.web.bind.annotation.*;

/** 24時間の保存観測と分析結果のHTTP受付。外部収集や書き込みは呼び出さない。 */
@RestController
public class QuoteHistoryController {
    private final QuoteHistoryQueryService quoteHistoryQueryService;

    public QuoteHistoryController(QuoteHistoryQueryService quoteHistoryQueryService) {
        this.quoteHistoryQueryService = quoteHistoryQueryService;
    }

    /** このAPIは1市場が対象。省略時の既定値はServiceで設定し、空文字は補正しない。 */
    @GetMapping("/api/v1/quotes/history")
    public QuoteHistoryResponse findQuoteHistory(
            @RequestParam(name = "exchange", required = false) String exchange,
            @RequestParam(name = "symbol", required = false) String symbol) {
        return quoteHistoryQueryService.findQuoteHistory(exchange, symbol);
    }
}
