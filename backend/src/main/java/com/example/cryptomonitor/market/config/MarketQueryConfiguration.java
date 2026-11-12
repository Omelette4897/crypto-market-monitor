package com.example.cryptomonitor.market.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

/** 参照APIの検索期間を決める時計。収集用の時計と責務・Bean名を分ける。 */
@Configuration(proxyBeanMethods = false)
public class MarketQueryConfiguration {
    @Bean("marketQueryClock")
    Clock marketQueryClock() { return Clock.systemUTC(); }
}
