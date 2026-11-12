package com.example.cryptomonitor.coinbase.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Coinbase Exchangeのbook応答を受け取る専用DTO。まだ検証済みの観測ではない。
 * bids/asksの各行は「価格の文字列・数量の文字列・注文数の整数」という混在配列なので、
 * JsonNodeで元のJSON型を保持し、後段のMapperで構造と値を確認する。
 *
 * @param bids 買い板。Level 1では最良買気配の行を受け取る
 * @param asks 売り板。Level 1では最良売気配の行を受け取る
 * @param auctionMode オークション状態。Booleanにして欠落/nullをfalseと区別する
 * @param time 提供元の日時文字列。意味が未確認のためsourceAtにはまだ採用しない
 */
// 使用しないsequenceやauctionが応答に含まれていても、読込みを拒否しない。
@JsonIgnoreProperties(ignoreUnknown = true)
public record CoinbaseBookResponse(
        List<List<JsonNode>> bids,
        List<List<JsonNode>> asks,
        // 外部JSONのsnake_caseと、JavaのcamelCaseを対応付ける。
        @JsonProperty("auction_mode") Boolean auctionMode,
        String time
) {
}
