package com.example.cryptomonitor.collection.service;

import com.example.cryptomonitor.collection.dto.CollectionRunHistoryItem;
import com.example.cryptomonitor.collection.dto.CollectionRunHistoryResponse;
import com.example.cryptomonitor.collection.entity.CollectionRun;
import com.example.cryptomonitor.collection.entity.CollectionRunStatus;
import com.example.cryptomonitor.collection.repository.CollectionRunRepository;
import com.example.cryptomonitor.common.api.MarketQueryParameterValidator;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 履歴の参照担当。FAILEDの履歴も正常な検索対象であり、HTTPエラーとは区別する。 */
@Service
public class CollectionRunQueryService {
    private final CollectionRunRepository collectionRunRepository;

    public CollectionRunQueryService(CollectionRunRepository collectionRunRepository) {
        this.collectionRunRepository = collectionRunRepository;
    }

    /** limitは文字列として受け、未指定・空文字・数値不正を明確に区別して検証する。 */
    @Transactional(readOnly = true)
    public CollectionRunHistoryResponse findCollectionRuns(String exchange, String symbol, String limit) {
        MarketQueryParameterValidator.validateMarket(exchange, symbol);
        int historyLimit = MarketQueryParameterValidator.parseHistoryLimit(limit);
        var historyItems = collectionRunRepository.findHistory(exchange, symbol, PageRequest.of(0, historyLimit))
                .stream().map(this::toHistoryItem).toList();
        return new CollectionRunHistoryResponse(historyItems);
    }

    /** SUCCESSは理由なし。未知コードは内部例外文で補完せず、安全な固定説明を返す。 */
    private CollectionRunHistoryItem toHistoryItem(CollectionRun collectionRun) {
        String resultCode = collectionRun.getStatus() == CollectionRunStatus.SUCCESS ? null : collectionRun.getResultCode();
        return new CollectionRunHistoryItem(collectionRun.getId().toString(), collectionRun.getExchange(),
                collectionRun.getSymbol(), collectionRun.getStatus().name(), collectionRun.getStartedAt(),
                collectionRun.getFinishedAt(), collectionRun.getInterruptionDetectedAt(),
                resultCode, describeResult(resultCode));
    }

    /** DBへ説明文は追加しない。既存の収集理由コードを公開用の固定日本語へ対応付ける。 */
    private String describeResult(String resultCode) {
        if (resultCode == null) return null;
        return switch (resultCode) {
            case "AUCTION_MODE" -> "オークション中のため観測を採用しませんでした";
            case "EMPTY_BOOK" -> "注文板が空のため観測を採用しませんでした";
            case "ZERO_LIQUIDITY" -> "取引可能な数量がないため観測を採用しませんでした";
            case "INVALID_RESPONSE" -> "外部APIの応答形式が不正でした";
            case "INVALID_PRICE" -> "価格の値が不正でした";
            case "INVALID_SIZE" -> "数量の値が不正でした";
            case "UNSUPPORTED_PRECISION" -> "保存可能な数値精度を超えています";
            case "CROSSED_BOOK" -> "買気配が売気配を上回っています";
            case "TIMEOUT" -> "外部APIの応答待ちがタイムアウトしました";
            case "NETWORK_ERROR" -> "外部APIとの通信に失敗しました";
            case "EXTERNAL_HTTP_ERROR" -> "外部APIが正常以外のHTTPステータスを返しました";
            case "RATE_LIMITED" -> "外部APIの利用制限に達しました";
            case "RESPONSE_TOO_LARGE" -> "外部APIの応答サイズが上限を超えています";
            case "PERSISTENCE_ERROR" -> "収集結果の保存を確認できませんでした";
            case "UNEXPECTED_ERROR" -> "収集処理中に予期しないエラーが発生しました";
            case "INTERRUPTED" -> "前回の収集処理の中断を検出しました";
            default -> "収集結果の詳細は確認できません";
        };
    }
}
