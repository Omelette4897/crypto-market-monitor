package com.example.cryptomonitor.coinbase;

/**
 * 外部取得に失敗した理由を呼び出し元へ伝える。DBの状態更新は担当しない。
 * 内部例外は調査用のcauseとして保持し、公開レスポンスへそのまま出さない。
 */
public class CoinbaseAccessException extends RuntimeException {
    /** 通信・HTTP・読込みの失敗分類。Mapperの採否結果とは別の境界。 */
    public enum Reason {
        TIMEOUT, NETWORK_ERROR, EXTERNAL_HTTP_ERROR, RATE_LIMITED,
        INVALID_RESPONSE, RESPONSE_TOO_LARGE
    }

    private final Reason reason;
    private final Integer httpStatus;

    /** HTTP応答が得られなかった場合はhttpStatusをnullとする。 */
    public CoinbaseAccessException(Reason reason, Integer httpStatus, Throwable cause) {
        super("Coinbase access failed: " + reason, cause);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
        this.httpStatus = httpStatus;
    }

    /** 呼び出し元が履歴の理由コードへ対応付けるための分類。 */
    public Reason reason() { return reason; }

    /** HTTP 500等の応答ステータス。通信できなかった場合はnull。 */
    public Integer httpStatus() { return httpStatus; }
}
