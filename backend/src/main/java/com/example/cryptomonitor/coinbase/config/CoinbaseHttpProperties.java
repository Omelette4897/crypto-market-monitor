package com.example.cryptomonitor.coinbase.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

/**
 * market-monitor.coinbase配下の外部設定を受ける。初期値は調整可能な運用値。
 * 0はHTTPライブラリで無期限を意味するため、不正値は起動時に拒否する。
 */
@ConfigurationProperties("market-monitor.coinbase")
public record CoinbaseHttpProperties(
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout
) {
    public CoinbaseHttpProperties {
        validateTimeout(connectTimeout, "connectTimeout");
        validateTimeout(readTimeout, "readTimeout");
    }

    /** ミリ秒単位のHTTP設定に変換できる正の時間だけを許可する。 */
    private static void validateTimeout(Duration timeoutDuration, String propertyName) {
        if (timeoutDuration == null || timeoutDuration.isNegative()
                || timeoutDuration.compareTo(Duration.ofMillis(1)) < 0
                || timeoutDuration.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException(propertyName + " must be 1ms..2147483647ms");
        }
    }
}
