package com.example.cryptomonitor.coinbase.dto;

import com.example.cryptomonitor.market.model.MarketObservation;


import java.util.Objects;

/**
 * Coinbase応答の検証・変換結果を、呼び出し元へ伝える型。
 * 想定内のスキップや不正データを結果として返し、DB保存自体は担当しない。
 *
 * @param status 変換段階の結果。DBの収集実行状態とは別のもの
 * @param observation ACCEPTEDの場合だけ設定する共通観測データ
 * @param resultCode SKIPPED/FAILEDの場合だけ設定する理由コード。内部例外の全文は入れない
 */
public record CoinbaseBookConversionResult(
        Status status,
        MarketObservation observation,
        String resultCode
) {
    /** 変換後に観測を採用できるかを表す。保存済みかどうかは表さない。 */
    public enum Status {
        // 検証に通過した状態。DB保存完了のSUCCESSとは異なる。
        ACCEPTED,
        // オークションや数量0など、意図的に保存を見送る状態。
        SKIPPED,
        // 解釈できない応答や不正な価格など、取得データを採用できない状態。
        FAILED
    }

    /**
     * コンパクトコンストラクタで、状態とデータの矛盾を作成時に拒否する。
     * 外部データの不正を判定する処理ではなく、この型を使う実装側の誤りを検出する。
     * @throws NullPointerException 必須の状態、または採用時の観測がnullの場合
     * @throws IllegalArgumentException 状態と観測・理由コードの組合せが矛盾する場合
     */
    public CoinbaseBookConversionResult {
        Objects.requireNonNull(status, "status");

        if (status == Status.ACCEPTED) {
            // 採用結果には観測を必須とし、不採用理由は持たせない。
            Objects.requireNonNull(observation, "observation");

            if (resultCode != null) {
                throw new IllegalArgumentException(
                        "ACCEPTED must not have a resultCode"
                );
            }
        } else {
            // 不採用結果を誤って保存しないよう、観測を持つことを禁止する。
            if (observation != null) {
                throw new IllegalArgumentException(
                        "SKIPPED/FAILED must not have an observation"
                );
            }

            if (resultCode == null || resultCode.isBlank()) {
                throw new IllegalArgumentException(
                        "SKIPPED/FAILED requires a resultCode"
                );
            }
        }
    }
}
