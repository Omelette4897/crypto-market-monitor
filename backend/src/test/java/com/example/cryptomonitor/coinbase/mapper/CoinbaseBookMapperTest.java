package com.example.cryptomonitor.coinbase.mapper;

import com.example.cryptomonitor.coinbase.dto.CoinbaseBookConversionResult;
import com.example.cryptomonitor.coinbase.dto.CoinbaseBookResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coinbase応答を、共通の価格観測データへ変換する処理のテスト。
 * 固定JSONと固定時刻を使い、外部API・DB・実際の現在時刻に依存させない。
 *
 * 試験区分：UT（実Mapper、固定DTO/時刻）。外部API・DBは使わない。
 * 実行方法：通常の .\mvnw.cmd test またはIntelliJのクラス実行。
 * ケース仕様：各メソッドの観点ID・前提/入力・操作・期待結果を正とする。
 * 共通データ：固定値はフィールド/準備メソッド、入力の組合せは各Source注釈を参照。
 * 保証範囲外・未実施：既存ケースの仕様を記す。全JSON欠落構造・全Ask側異常・結果型コンストラクタの不変条件は未網羅。
 */
@DisplayName("機能A / UT：注文板の検証・共通観測変換")
class CoinbaseBookMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CoinbaseBookMapper coinbaseBookMapper = new CoinbaseBookMapper();

    /**
     * 観点：A-MAP-01 — 正常板を共通観測へ変換し数値・受信時刻を保持する。
     * 前提・入力：正常Bid/Ask・数量と、提供元timeとは異なる固定受信時刻。
     * 操作：convertBtcUsdへDTOと受信時刻を渡す。
     * 期待結果：ACCEPTED、理由なし。取引所・ペア・4数値と小数秒9桁の受信時刻を保持し、sourceAtはnull。
     */
    @DisplayName("A-MAP-01：正常板を共通観測へ変換し数値・受信時刻を保持する")
    @Test
    void convertsNormalBookToObservation() throws Exception {
        // Arrange：正常な応答を用意する。提供元時刻と受信時刻は別にする。
        String responseJson = """
                {
                  "bids": [["77747.9", "0.11129672", 4]],
                  "asks": [["77747.91", "0.00736996", 2]],
                  "auction_mode": false,
                  "time": "2026-11-01T12:50:16.901623856Z"
                }
                """;

        CoinbaseBookResponse coinbaseBookResponse =
                objectMapper.readValue(responseJson, CoinbaseBookResponse.class);

        // 実運用ではHTTP本文の受信完了時に計測する。
        // テストでは固定し、Mapperが別の時刻に置き換えないことを確認する。
        Instant observedAt =
                Instant.parse("2026-11-01T12:50:17.123456789Z");

        // Act：検証・変換を実行する。
        CoinbaseBookConversionResult coinbaseBookConversionResult =
                coinbaseBookMapper.convertBtcUsd(coinbaseBookResponse, observedAt);

        // Assert：変換成功であり、不採用理由がないことを確認する。
        assertEquals(CoinbaseBookConversionResult.Status.ACCEPTED, coinbaseBookConversionResult.status());
        assertNull(coinbaseBookConversionResult.resultCode());
        assertNotNull(coinbaseBookConversionResult.observation());

        var marketObservation = coinbaseBookConversionResult.observation();

        assertEquals("COINBASE_EXCHANGE", marketObservation.exchange());
        assertEquals("BTC-USD", marketObservation.symbol());

        // BigDecimalはcompareToで数値として比較する。
        // equalsとは異なり、1.0と1.00のような小数桁の違いだけでは失敗しない。
        assertEquals(0, new BigDecimal("77747.9")
                .compareTo(marketObservation.bidPrice()));
        assertEquals(0, new BigDecimal("0.11129672")
                .compareTo(marketObservation.bidSize()));
        assertEquals(0, new BigDecimal("77747.91")
                .compareTo(marketObservation.askPrice()));
        assertEquals(0, new BigDecimal("0.00736996")
                .compareTo(marketObservation.askSize()));

        // DB保存前なので、小数秒9桁もそのまま保持する。
        assertEquals(observedAt, marketObservation.observedAt());

        // 提供元timeの意味が未確認のため、値があっても採用しない。
        assertNull(marketObservation.sourceAt());
    }

    /**
     * 観点：A-MAP-02 — 空板よりオークション判定を優先する。
     * 前提・入力：auctionMode=true、Bid/Askはともに空リスト。
     * 操作：convertBtcUsdを呼ぶ。
     * 期待結果：SKIPPED/AUCTION_MODE、観測なし。EMPTY_BOOKではなくオークション理由を採用する。
     */
    @DisplayName("A-MAP-02：空板よりオークション判定を優先する")
    @Test
    void skipsAuctionBeforeCheckingBookContents() {
        var coinbaseBookResponse = new CoinbaseBookResponse(
                List.of(),
                List.of(),
                true,
                null
        );

        var coinbaseBookConversionResult = coinbaseBookMapper.convertBtcUsd(
                coinbaseBookResponse,
                Instant.parse("2026-11-01T12:50:17Z")
        );

        assertEquals(CoinbaseBookConversionResult.Status.SKIPPED, coinbaseBookConversionResult.status());
        assertEquals("AUCTION_MODE", coinbaseBookConversionResult.resultCode());
        assertNull(coinbaseBookConversionResult.observation());
    }

    /**
     * 観点：A-MAP-03 — オークション状態不明を空板スキップより先に拒否する。
     * 前提・入力：auctionMode=null、Bid/Askは空リスト。
     * 操作：convertBtcUsdを呼ぶ。
     * 期待結果：FAILED/INVALID_RESPONSE、観測なし。状態不明を通常板と決めつけない。
     */
    @DisplayName("A-MAP-03：オークション状態不明を空板スキップより先に拒否する")
    @Test
    void rejectsUnknownAuctionMode() {
        var coinbaseBookResponse = new CoinbaseBookResponse(
                List.of(),
                List.of(),
                null,
                null
        );

        var coinbaseBookConversionResult = coinbaseBookMapper.convertBtcUsd(
                coinbaseBookResponse,
                Instant.parse("2026-11-01T12:50:17Z")
        );

        assertEquals(CoinbaseBookConversionResult.Status.FAILED, coinbaseBookConversionResult.status());
        assertEquals("INVALID_RESPONSE", coinbaseBookConversionResult.resultCode());
        assertNull(coinbaseBookConversionResult.observation());
    }

    /**
     * 観点：A-MAP-04 — 通常市場の空板を取得データ不正と区別してスキップする。
     * 前提・入力：auctionMode=false、Bid/Askは空リスト。
     * 操作：convertBtcUsdを呼ぶ。
     * 期待結果：SKIPPED/EMPTY_BOOK、観測なし。
     */
    @DisplayName("A-MAP-04：通常市場の空板を取得データ不正と区別してスキップする")
    @Test
    void skipsEmptyBook() {
        var coinbaseBookResponse = new CoinbaseBookResponse(
                List.of(),
                List.of(),
                false,
                null
        );

        var coinbaseBookConversionResult = coinbaseBookMapper.convertBtcUsd(
                coinbaseBookResponse,
                Instant.parse("2026-11-01T12:50:17Z")
        );

        assertEquals(CoinbaseBookConversionResult.Status.SKIPPED, coinbaseBookConversionResult.status());
        assertEquals("EMPTY_BOOK", coinbaseBookConversionResult.resultCode());
        assertNull(coinbaseBookConversionResult.observation());
    }

    /**
     * 観点：A-MAP-05 — 不正値・境界外・数量ゼロを理由別に分類する。
     * 前提・入力：CsvSourceの列順はBid価格・Bid数量・Ask価格・Ask数量・期待状態・期待理由。各行の値だけを変更する。
     * 操作：表の文字列を正常構造のJSONへ埋め込み、DTOを変換する。
     * 期待結果：表の期待状態・理由と一致し、観測はnull。価格0・数量負数/0・逆転板・小数13桁・整数19桁・非数値を区別する。
     */
    @DisplayName("A-MAP-05：不正値・境界外・数量ゼロを理由別に分類する")
    @ParameterizedTest(name = "[{index}] {argumentsWithNames}")
    @CsvSource({
            // A-MAP-05/1：価格0 → FAILED / INVALID_PRICE。
            "0,                   0.1, 101,                 0.2, FAILED,  INVALID_PRICE",
            // A-MAP-05/2：負の数量 → FAILED / INVALID_SIZE。
            "100,                -0.1, 101,                 0.2, FAILED,  INVALID_SIZE",
            // A-MAP-05/3：数量0 → SKIPPED / ZERO_LIQUIDITY。
            "100,                 0,   101,                 0.2, SKIPPED, ZERO_LIQUIDITY",
            // A-MAP-05/4：Bid > Ask → FAILED / CROSSED_BOOK。
            "102,                 0.1, 101,                 0.2, FAILED,  CROSSED_BOOK",
            // A-MAP-05/5：小数13桁に有効な値 → FAILED / UNSUPPORTED_PRECISION。
            "100.0000000000001,   0.1, 101,                 0.2, FAILED,  UNSUPPORTED_PRECISION",
            // A-MAP-05/6：整数19桁 → FAILED / UNSUPPORTED_PRECISION。
            "1000000000000000000, 0.1, 1000000000000000001, 0.2, FAILED,  UNSUPPORTED_PRECISION",
            // A-MAP-05/7：数値変換不能 → FAILED / INVALID_RESPONSE。
            "not-a-number,        0.1, 101,                 0.2, FAILED,  INVALID_RESPONSE"
    })
    void rejectsOrSkipsInvalidNumbers(
            String bidPriceText,
            String bidSizeText,
            String askPriceText,
            String askSizeText,
            CoinbaseBookConversionResult.Status expectedStatus,
            String expectedResultCode
    ) throws Exception {
        // Arrange：表から受け取った値を、価格・数量の文字列として埋め込む。
        // 今回はテストコード内の固定値だけを使う。
        String responseJson = """
                {
                  "bids": [["%s", "%s", 1]],
                  "asks": [["%s", "%s", 1]],
                  "auction_mode": false
                }
                """.formatted(bidPriceText, bidSizeText, askPriceText, askSizeText);

        var coinbaseBookResponse =
                objectMapper.readValue(responseJson, CoinbaseBookResponse.class);

        // Act：通信せず、変換処理だけを実行する。
        var coinbaseBookConversionResult = coinbaseBookMapper.convertBtcUsd(
                coinbaseBookResponse,
                Instant.parse("2026-11-01T12:50:17Z")
        );

        // Assert：不採用の分類と理由が一致し、観測データを返さないことを確認する。
        assertEquals(expectedStatus, coinbaseBookConversionResult.status());
        assertEquals(expectedResultCode, coinbaseBookConversionResult.resultCode());
        assertNull(coinbaseBookConversionResult.observation());
    }

    /**
     * 観点：A-MAP-06 — 採用可能な数値境界を丸めず保持する。
     * 前提・入力：CsvSourceの列順はBid価格・Bid数量・Ask価格・Ask数量。等値板、無意味な末尾0、整数18桁/小数12桁、最小数量桁を用いる。
     * 操作：固定時刻と各行のDTOをconvertBtcUsdへ渡す。
     * 期待結果：ACCEPTED、理由なし、観測あり。4数値はBigDecimal.compareToで元の値と一致する。
     */
    @DisplayName("A-MAP-06：採用可能な数値境界を丸めず保持する")
    @ParameterizedTest(name = "[{index}] {argumentsWithNames}")
    @CsvSource({
            // BidとAskが同じでも、今回の仕様では採用する。
            "100, 0.1, 100, 0.2",

            // 小数部分は13桁あるが、末尾0を除けば値を変えずに保存できる。
            "100.0000000000000, 0.1, 101, 0.2",

            // DECIMAL(30,12)の整数18桁・小数12桁の境界内。
            "999999999999999999.999999999998, 0.1, 999999999999999999.999999999999, 0.2",

            // 数量も小数12桁まで、正確に保持できる。
            "100, 0.000000000001, 101, 0.000000000001"
    })
    void acceptsValidBoundaryValues(
            String bidPriceText,
            String bidSizeText,
            String askPriceText,
            String askSizeText
    ) throws Exception {
        // Arrange：採用されるべき境界値を、正常な応答構造に入れる。
        String responseJson = """
                {
                  "bids": [["%s", "%s", 1]],
                  "asks": [["%s", "%s", 1]],
                  "auction_mode": false
                }
                """.formatted(bidPriceText, bidSizeText, askPriceText, askSizeText);

        var coinbaseBookResponse =
                objectMapper.readValue(responseJson, CoinbaseBookResponse.class);

        // Act：固定時刻を渡し、検証・変換を実行する。
        var coinbaseBookConversionResult = coinbaseBookMapper.convertBtcUsd(
                coinbaseBookResponse,
                Instant.parse("2026-11-01T12:50:17Z")
        );

        // Assert：採用結果であり、観測データがあることを確認する。
        assertEquals(CoinbaseBookConversionResult.Status.ACCEPTED, coinbaseBookConversionResult.status());
        assertNull(coinbaseBookConversionResult.resultCode());
        assertNotNull(coinbaseBookConversionResult.observation());

        var marketObservation = coinbaseBookConversionResult.observation();

        // 採用されるだけでなく、丸めや桁落ちで値が変わっていないことを確認する。
        assertEquals(0, new BigDecimal(bidPriceText)
                .compareTo(marketObservation.bidPrice()));
        assertEquals(0, new BigDecimal(bidSizeText)
                .compareTo(marketObservation.bidSize()));
        assertEquals(0, new BigDecimal(askPriceText)
                .compareTo(marketObservation.askPrice()));
        assertEquals(0, new BigDecimal(askSizeText)
                .compareTo(marketObservation.askSize()));
    }
}
