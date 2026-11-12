package com.example.cryptomonitor.coinbase.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Clock;

/** HTTPの組立てを業務処理から分離する。Beanを作るだけでは通信は発生しない。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CoinbaseHttpProperties.class)
public class CoinbaseHttpConfiguration {
    /** 接続先は固定HTTPS。任意URLを画面から受け取る機能は設けない。 */
    @Bean
    RestClient coinbaseRestClient(CoinbaseHttpProperties coinbaseHttpProperties) {
        return RestClient.builder()
                .baseUrl("https://api.exchange.coinbase.com")
                .requestFactory(createHttpRequestFactory(coinbaseHttpProperties))
                .build();
    }

    /** 受信時刻を計測する時計。テストでは固定時計や記録用時計へ差し替える。 */
    @Bean
    Clock coinbaseClock() { return Clock.systemUTC(); }

    /**
     * 既存のHTTP実装を使い、タイムアウトを明示する。GETの自動リダイレクトも無効化し、
     * 想定外の転送先や3xxを正常取得として扱わない。アプリ独自の再試行は行わない。
     * 別パッケージのClientテストでも同一設定を使えるよう、この補助メソッドだけ公開する。
     */
    public static SimpleClientHttpRequestFactory createHttpRequestFactory(CoinbaseHttpProperties coinbaseHttpProperties) {
        var httpRequestFactory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection httpConnection, String httpMethod)
                    throws IOException {
                super.prepareConnection(httpConnection, httpMethod);
                httpConnection.setInstanceFollowRedirects(false);
            }
        };
        httpRequestFactory.setConnectTimeout(coinbaseHttpProperties.connectTimeout());
        httpRequestFactory.setReadTimeout(coinbaseHttpProperties.readTimeout());
        return httpRequestFactory;
    }

    /**
     * Coinbase専用のコピーを作り、公開API等が使うObjectMapperの設定を変更しない。
     * "false"や0をbooleanへ、数値を日時文字列へ暗黙変換して型不正を隠さない。
     * clientパッケージのCoinbaseClientから利用するため公開する。Bean生成メソッドの公開範囲は広げない。
     */
    public static ObjectMapper strictResponseMapper(ObjectMapper originalObjectMapper) {
        var strictObjectMapper = originalObjectMapper.copy();
        for (var coercionInputShape : new CoercionInputShape[] {
                CoercionInputShape.String, CoercionInputShape.Integer, CoercionInputShape.Float }) {
            strictObjectMapper.coercionConfigFor(LogicalType.Boolean).setCoercion(coercionInputShape, CoercionAction.Fail);
        }
        for (var coercionInputShape : new CoercionInputShape[] {
                CoercionInputShape.Boolean, CoercionInputShape.Integer, CoercionInputShape.Float }) {
            strictObjectMapper.coercionConfigFor(LogicalType.Textual).setCoercion(coercionInputShape, CoercionAction.Fail);
        }
        return strictObjectMapper;
    }
}
