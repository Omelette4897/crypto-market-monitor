package com.example.cryptomonitor.coinbase.client;

import com.example.cryptomonitor.coinbase.CoinbaseAccessException;
import com.example.cryptomonitor.coinbase.dto.CoinbaseBookResponse;
import com.example.cryptomonitor.coinbase.dto.CoinbaseBookResponseWithReceivedAt;
import com.example.cryptomonitor.coinbase.config.CoinbaseHttpConfiguration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.util.Objects;
import static com.example.cryptomonitor.coinbase.CoinbaseAccessException.Reason.*;

/**
 * BTC-USDのLevel 1をHTTP取得し、DTOと受信時刻を返す。
 * 数値の業務検証は既存Mapper、DB保存は後続の収集処理の責務。
 */
@Component
public class CoinbaseClient {
    // Level 1の小さな応答を想定。異常な巨大本文でメモリを使い切らないための防御上限。
    private static final int MAX_RESPONSE_BODY_BYTES = 256 * 1024;
    private final RestClient coinbaseRestClient;
    private final ObjectMapper responseObjectMapper;
    private final Clock utcClock;

    public CoinbaseClient(@Qualifier("coinbaseRestClient") RestClient coinbaseRestClient,
                          ObjectMapper objectMapper,
                          @Qualifier("coinbaseClock") Clock utcClock) {
        this.coinbaseRestClient = Objects.requireNonNull(coinbaseRestClient);
        this.responseObjectMapper = CoinbaseHttpConfiguration.strictResponseMapper(objectMapper);
        this.utcClock = Objects.requireNonNull(utcClock);
    }

    /**
     * 成功時だけ本文を読み、受信完了時刻をJSON解析前に計測する。
     * @return 外部DTOと本文受信完了時刻。価格の採否はまだ未判定
     * @throws CoinbaseAccessException 通信・HTTP・JSON読込みに失敗した場合
     */
    public CoinbaseBookResponseWithReceivedAt fetchBtcUsd() {
        try {
            return coinbaseRestClient.get().uri("/products/BTC-USD/book?level=1")
                    .exchange((httpRequest, httpResponse) -> {
                        int httpStatus = httpResponse.getStatusCode().value();
                        if (!httpResponse.getStatusCode().is2xxSuccessful()) {
                            throw new CoinbaseAccessException(
                                    httpStatus == 429 ? RATE_LIMITED : EXTERNAL_HTTP_ERROR, httpStatus, null);
                        }

                        // Content-Lengthだけを信用せず、実際の読込みも上限+1バイトで止める。
                        byte[] responseBodyBytes = httpResponse.getBody().readNBytes(MAX_RESPONSE_BODY_BYTES + 1);
                        if (responseBodyBytes.length > MAX_RESPONSE_BODY_BYTES) {
                            throw new CoinbaseAccessException(RESPONSE_TOO_LARGE, httpStatus, null);
                        }
                        var receivedAt = utcClock.instant();
                        try {
                            var coinbaseBookResponse = responseObjectMapper.readValue(
                                    responseBodyBytes, CoinbaseBookResponse.class);
                            // JSONのnullは構文上正しくても、応答DTOとしては使えない。
                            if (coinbaseBookResponse == null) {
                                throw new CoinbaseAccessException(INVALID_RESPONSE, httpStatus, null);
                            }
                            return new CoinbaseBookResponseWithReceivedAt(coinbaseBookResponse, receivedAt);
                        } catch (JsonProcessingException exception) {
                            throw new CoinbaseAccessException(INVALID_RESPONSE, httpStatus, exception);
                        }
                    });
        } catch (ResourceAccessException exception) {
            // IOExceptionだけを通信失敗として扱い、プログラム上の不具合を一律に握り潰さない。
            throw new CoinbaseAccessException(isTimeout(exception) ? TIMEOUT : NETWORK_ERROR, null, exception);
        }
    }

    /** ライブラリが例外を包む場合も、原因チェーンからソケットタイムアウトを識別する。 */
    private boolean isTimeout(Throwable accessException) {
        for (Throwable currentCause = accessException; currentCause != null; currentCause = currentCause.getCause()) {
            if (currentCause instanceof SocketTimeoutException) return true;
        }
        return false;
    }
}
