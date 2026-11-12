package com.example.cryptomonitor.common.api;

/** 両参照APIで共通の条件検証。省略を空文字へ置き換えず、対応する市場値だけを受け付ける。 */
public final class MarketQueryParameterValidator {
    private MarketQueryParameterValidator() {}

    /** nullは絞り込みなし。空白・大小文字違い・未対応値を正常値へ暗黙補正しない。 */
    public static void validateMarket(String exchange, String symbol) {
        if (exchange != null && !"COINBASE_EXCHANGE".equals(exchange))
            throw new InvalidApiParameterException("exchangeに対応する取引所を指定してください");
        if (symbol != null && !"BTC-USD".equals(symbol))
            throw new InvalidApiParameterException("symbolに対応する通貨ペアを指定してください");
    }

    /** 省略時100。空文字・小数・非数値・整数の桁あふれも同じ入力不正として扱う。 */
    public static int parseHistoryLimit(String limit) {
        if (limit == null) return 100;
        if (limit.matches("[0-9]+")) {
            try {
                int requestedLimit = Integer.parseInt(limit);
                if (requestedLimit >= 1 && requestedLimit <= 100) return requestedLimit;
            } catch (NumberFormatException exception) {
                // 任意桁数を受け入れず、内部例外文を公開しない。
            }
        }
        throw new InvalidApiParameterException("limitは1から100までの整数で指定してください");
    }
}
