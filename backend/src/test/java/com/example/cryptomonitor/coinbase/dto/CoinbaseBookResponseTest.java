package com.example.cryptomonitor.coinbase.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 固定JSONを専用DTOへ読み込む境界テスト。実取引所やMySQLへは接続しない。
 * Spring全体を起動せず、Jacksonの項目対応付けと元のJSON型の保持を確認する。
 * このテストの成功だけでは、価格の妥当性やMapperの採否判定は保証しない。
 *
 * 試験区分：UT（固定JSONとJackson）。実HTTP・DB・Spring起動なし。
 * 実行方法：通常の .\mvnw.cmd test またはIntelliJのクラス実行。
 * ケース仕様：各メソッドの観点ID・前提/入力・操作・期待結果を正とする。
 * 共通データ：固定値はフィールド/準備メソッド、入力の組合せは各Source注釈を参照。
 * 保証範囲外・未実施：価格の業務妥当性はMapperの試験へ委ねる。
 */
@DisplayName("機能A / UT：外部JSONとDTOの対応")
class CoinbaseBookResponseTest {

    // このDTOに必要な基本変換をテストするためのMapper。SpringのDIは不要。
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 観点：A-DTO-01 — 正常JSONの値型・項目対応を保持する。
     * 前提・入力：価格・数量は文字列、注文数は整数、追加項目を含む固定JSON。
     * 操作：ObjectMapperでCoinbaseBookResponseへ読み込む。
     * 期待結果：板の行数・各値と型、auction_modeからauctionModeへの対応、timeの保持が一致する。
     */
    @DisplayName("A-DTO-01：正常JSONの値型・項目対応を保持する")
    @Test
    void readsBookResponsePreservingValueTypes() throws Exception {
        // Arrange：入力を用意する
        String responseJson = """
                {
                  "bids": [["77747.9", "0.11129672", 4]],
                  "asks": [["77747.91", "0.00736996", 2]],
                  "sequence": 123456789,
                  "auction_mode": false,
                  "auction": null,
                  "time": "2026-11-01T12:50:16.901623856Z"
                }
                """;

        // Act：JSONをDTOへ変換する
        CoinbaseBookResponse coinbaseBookResponse =
                objectMapper.readValue(responseJson, CoinbaseBookResponse.class);

        // Assert：読み込んだ結果を検証する
        assertEquals(1, coinbaseBookResponse.bids().size());
        assertEquals(1, coinbaseBookResponse.asks().size());

        // Level 1の各側の先頭行を取り出す。行内の価格・数量は文字列、注文数は整数。
        var bestBidEntry = coinbaseBookResponse.bids().get(0);
        var bestAskEntry = coinbaseBookResponse.asks().get(0);

        assertTrue(bestBidEntry.get(0).isTextual());
        assertEquals("77747.9", bestBidEntry.get(0).textValue());
        assertEquals("0.11129672", bestBidEntry.get(1).textValue());

        assertTrue(bestBidEntry.get(2).isIntegralNumber());
        assertEquals(4, bestBidEntry.get(2).intValue());

        assertEquals("77747.91", bestAskEntry.get(0).textValue());
        assertEquals("0.00736996", bestAskEntry.get(1).textValue());
        // JSONのauction_modeがJavaのauctionModeへ対応付けられたことも検証する。
        assertEquals(Boolean.FALSE, coinbaseBookResponse.auctionMode());
        assertEquals(
                "2026-11-01T12:50:16.901623856Z",
                coinbaseBookResponse.time()
        );
    }

    /**
     * 観点：A-DTO-02 — 欠落したオークション状態をfalseで補完しない。
     * 前提・入力：auction_modeを含まない固定JSON。
     * 操作：ObjectMapperでDTOへ読み込む。
     * 期待結果：auctionModeはnull。採否判定は後段Mapperの責務で、この試験では行わない。
     */
    @DisplayName("A-DTO-02：欠落したオークション状態をfalseで補完しない")
    @Test
    void keepsMissingAuctionModeAsNull() throws Exception {
        String responseJson = """
                {
                  "bids": [["77747.9", "0.1", 1]],
                  "asks": [["77747.91", "0.2", 1]]
                }
                """;

        CoinbaseBookResponse coinbaseBookResponse =
                objectMapper.readValue(responseJson, CoinbaseBookResponse.class);

        assertNull(coinbaseBookResponse.auctionMode());
    }
}
