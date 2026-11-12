package com.example.cryptomonitor.coinbase.mapper;

import com.example.cryptomonitor.coinbase.dto.CoinbaseBookConversionResult;
import com.example.cryptomonitor.coinbase.dto.CoinbaseBookResponse;
import com.example.cryptomonitor.market.model.MarketObservation;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import static com.example.cryptomonitor.coinbase.dto.CoinbaseBookConversionResult.Status.*;

/**
 * CoinbaseのBTC-USD/Level 1応答を検証し、アプリ共通の観測へ変換する。
 * 外部HTTP通信・現在時刻の取得・DB保存は行わないため、固定データだけでテストできる。
 * このメソッドへ別ペアの応答を渡さない責任は、今後作成する通信側が持つ。
 */
@org.springframework.stereotype.Component
public class CoinbaseBookMapper {

    /**
     * オークション判定→構造→数値→採用判定の順に処理し、最初の不採用理由を返す。
     * 複数の問題がある場合、ここに記述した順序が理由コードの優先順位になる。
     *
     * @param coinbaseBookResponse 外部JSONから読み込んだ応答。nullや欠落項目も検証対象
     * @param observedAt 呼び出し元で計測した本文受信完了時刻。変換時刻で代用しない
     * @return 採用時は観測を、不採用時は理由コードを含む結果
     * @throws NullPointerException 呼び出し元が必須の受信時刻を渡していない場合
     */
    public CoinbaseBookConversionResult convertBtcUsd(
            CoinbaseBookResponse coinbaseBookResponse,
            Instant observedAt
    ) {
        // 受信時刻の渡し忘れは外部応答の異常ではなく、呼び出し側の実装ミス。
        Objects.requireNonNull(observedAt, "observedAt");

        // ① 応答と構造の確認
        if (coinbaseBookResponse == null || coinbaseBookResponse.auctionMode() == null) {
            return rejected(FAILED, "INVALID_RESPONSE");
        }

        if (coinbaseBookResponse.auctionMode()) {
            // オークション中の気配は通常板の検証より先に除外する。逆転価格もあり得る。
            return rejected(SKIPPED, "AUCTION_MODE");
        }

        if (coinbaseBookResponse.bids() == null || coinbaseBookResponse.asks() == null) {
            return rejected(FAILED, "INVALID_RESPONSE");
        }

        if (coinbaseBookResponse.bids().isEmpty() || coinbaseBookResponse.asks().isEmpty()) {
            // 明示的な空配列は板なしとしてスキップ。項目自体の欠落/nullとは区別する。
            return rejected(SKIPPED, "EMPTY_BOOK");
        }

        if (!hasValidLevelOneSide(coinbaseBookResponse.bids())
                || !hasValidLevelOneSide(coinbaseBookResponse.asks())) {
            return rejected(FAILED, "INVALID_RESPONSE");
        }

        var bestBidEntry = coinbaseBookResponse.bids().get(0);
        var bestAskEntry = coinbaseBookResponse.asks().get(0);
        // 各行の[0]は価格、[1]は合計数量、[2]は注文数。数量へ注文数を掛けない。

        // ② 数値の変換・検証
        BigDecimal bidPrice;
        BigDecimal bidSize;
        BigDecimal askPrice;
        BigDecimal askSize;

        try {
            // JSONの文字列から直接生成する。double経由の近似誤差を持ち込まない。
            bidPrice = new BigDecimal(bestBidEntry.get(0).textValue());
            bidSize = new BigDecimal(bestBidEntry.get(1).textValue());
            askPrice = new BigDecimal(bestAskEntry.get(0).textValue());
            askSize = new BigDecimal(bestAskEntry.get(1).textValue());
        } catch (NumberFormatException exception) {
            // 文字列であっても数値とは限らない。内部例外文ではなく分類コードを返す。
            return rejected(FAILED, "INVALID_RESPONSE");
        }

        // signumは負数=-1、ゼロ=0、正数=1。価格は正数、数量はまず非負を要求する。
        if (bidPrice.signum() <= 0 || askPrice.signum() <= 0) {
            return rejected(FAILED, "INVALID_PRICE");
        }

        if (bidSize.signum() < 0 || askSize.signum() < 0) {
            return rejected(FAILED, "INVALID_SIZE");
        }

        if (!fitsDecimal(bidPrice) || !fitsDecimal(bidSize)
                || !fitsDecimal(askPrice) || !fitsDecimal(askSize)) {
            return rejected(FAILED, "UNSUPPORTED_PRECISION");
        }

        // compareToは値の大小を比較する。小数桁の違いも見るequalsとは用途が異なる。
        // Bid=Askは許容し、Bid>Askだけを拒否する。
        if (bidPrice.compareTo(askPrice) > 0) {
            return rejected(FAILED, "CROSSED_BOOK");
        }

        if (bidSize.signum() == 0 || askSize.signum() == 0) {
            // ほかの検証に通ったうえで数量0なら、取引可能数量なしとしてスキップ。
            return rejected(SKIPPED, "ZERO_LIQUIDITY");
        }

        // ③ 共通の価格観測データへ変換
        var marketObservation = new MarketObservation(
                "COINBASE_EXCHANGE",
                "BTC-USD",
                bidPrice,
                bidSize,
                askPrice,
                askSize,
                observedAt,
                // 提供元timeの意味が確認できるまでは、sourceAtへ採用しない。
                null
        );

        return new CoinbaseBookConversionResult(ACCEPTED, marketObservation, null);
    }

    /**
     * 空でないLevel 1の片側が「1行・3要素」の契約に合うか確認する。
     * 呼び出し前にorderBookSideのnull/空を処理済み。行や値のnullはここで拒否する。
     * 価格/数量が文字列、注文数が非負整数であることまで確認し、数値内容は後で検証する。
     */
    private boolean hasValidLevelOneSide(List<List<JsonNode>> orderBookSide) {
        if (orderBookSide.size() != 1) {
            return false;
        }

        var orderBookEntry = orderBookSide.get(0);

        if (orderBookEntry == null || orderBookEntry.size() != 3
                || orderBookEntry.get(0) == null
                || orderBookEntry.get(1) == null
                || orderBookEntry.get(2) == null) {
            return false;
        }

        return orderBookEntry.get(0).isTextual()
                && orderBookEntry.get(1).isTextual()
                && orderBookEntry.get(2).isIntegralNumber()
                && orderBookEntry.get(2).bigIntegerValue().signum() >= 0;
    }

    /**
     * 値を丸めず、DBのDECIMAL(30,12)（整数最大18桁、小数最大12桁）に収まるか調べる。
     * 末尾0の多い1.2300000000000などを誤って拒否しないよう正規化してから数える。
     * このメソッドは検証のみで、元のBigDecimalの値・桁数は変更しない。
     */
    private boolean fitsDecimal(BigDecimal decimalValue) {
        try {
            var normalizedDecimalValue = decimalValue.stripTrailingZeros();
            // 指数表記ではscaleが負になる。引算のオーバーフローを避けるためlongで計算する。
            long integerDigitCount =
                    (long) normalizedDecimalValue.precision() - normalizedDecimalValue.scale();

            return integerDigitCount <= 18 && normalizedDecimalValue.scale() <= 12;
        } catch (ArithmeticException exception) {
            // 極端な指数などで正規化できない場合も、保存可能とはみなさない。
            return false;
        }
    }

    /** スキップ/失敗の結果を作る共通処理。不採用なので観測データは常にnull。 */
    private CoinbaseBookConversionResult rejected(
            CoinbaseBookConversionResult.Status status,
            String resultCode
    ) {
        return new CoinbaseBookConversionResult(status, null, resultCode);
    }
}
