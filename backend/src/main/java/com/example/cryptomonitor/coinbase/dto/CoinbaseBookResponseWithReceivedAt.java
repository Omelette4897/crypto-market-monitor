package com.example.cryptomonitor.coinbase.dto;

import com.example.cryptomonitor.market.model.MarketObservation;

import java.time.Instant;
import java.util.Objects;

/**
 * Coinbaseの応答と本文受信完了時刻を一緒に運ぶ。まだ価格の検証は完了していない。
 * receivedAtは後段のMapperへ渡し、MarketObservation.observedAtになる。
 */
public record CoinbaseBookResponseWithReceivedAt(
        CoinbaseBookResponse response,
        Instant receivedAt
) {
    /** DTOと受信時刻の取り違え・渡し忘れを作成時に検出する。 */
    public CoinbaseBookResponseWithReceivedAt {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }
}
