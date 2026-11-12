package com.example.cryptomonitor.collection.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 自動収集の運用設定。enabled=falseでも起動時の中断回収は省略しない。 */
@ConfigurationProperties("market-monitor.collection")
public record CollectionSchedulingProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("60s") Duration fixedDelay,
        @DefaultValue("30s") Duration shutdownWait) {
    public CollectionSchedulingProperties {
        validateDuration(fixedDelay, "fixedDelay");
        validateDuration(shutdownWait, "shutdownWait");
    }

    /** 無期限待機・連続実行を避け、実行基盤が扱える正のナノ秒数だけを許可する。 */
    private static void validateDuration(Duration duration, String propertyName) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(propertyName + " must be positive");
        }
        try {
            duration.toNanos();
        } catch (ArithmeticException arithmeticException) {
            throw new IllegalArgumentException(propertyName + " is too large", arithmeticException);
        }
    }
}
