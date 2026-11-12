package com.example.cryptomonitor.coinbase.client;

import com.example.cryptomonitor.coinbase.CoinbaseAccessException;
import com.example.cryptomonitor.coinbase.dto.CoinbaseBookConversionResult;
import com.example.cryptomonitor.coinbase.mapper.CoinbaseBookMapper;
import com.example.cryptomonitor.coinbase.config.CoinbaseHttpConfiguration;
import com.example.cryptomonitor.coinbase.config.CoinbaseHttpProperties;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 実取引所の代わりに、このPC内の一時HTTPサーバーでClientの通信境界を確認する。
 * MySQLやSpring全体は起動しない。固定時計で受信時刻と提供元時刻を区別する。
 *
 * 試験区分：HTTP結合確認（実Clientと一時ローカルHTTPサーバー）。設定の不正値ケースだけはUT。
 * 実行方法：通常の .\mvnw.cmd test。ローカル接続の許可が必要。Docker・実Coinbase・DBは不要。
 * ケース仕様：各メソッドの観点ID・前提/入力・操作・期待結果を正とする。
 * 共通データ：固定値はフィールド/準備メソッド、入力の組合せは各Source注釈を参照。
 * 保証範囲外・未実施：実取引所の到達性・到達不能ネットワークの接続タイムアウト・総処理時間の上限は保証しない。
 */
@DisplayName("機能A / HTTP結合確認：取得・読込み・障害分類")
class CoinbaseClientTest {
    private static final Instant FIXED_RECEIVED_AT = Instant.parse("2026-11-03T01:02:03.123456789Z");
    private static final String NORMAL_BOOK_RESPONSE_JSON = """
            {"bids":[["100.10","0.1",1]],"asks":[["100.20","0.2",2]],
             "auction_mode":false,"time":"2026-11-03T01:02:00Z"}
            """;
    private HttpServer mockHttpServer;
    private int httpResponseStatus;
    private String responseBodyText;
    private long headerDelayMillis;
    private long bodyDelayMillis;
    private String requestedHttpMethod;
    private String requestedRequestUri;
    private final AtomicInteger httpRequestCount = new AtomicInteger();
    private final AtomicInteger clockReadCount = new AtomicInteger();

    /** ケースごとに独立したポート・応答を用意し、終了時に必ずサーバーを閉じる。 */
    @BeforeEach
    void startServer() throws Exception {
        httpResponseStatus = 200;
        responseBodyText = NORMAL_BOOK_RESPONSE_JSON;
        mockHttpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockHttpServer.createContext("/", httpExchange -> {
            httpRequestCount.incrementAndGet();
            requestedHttpMethod = httpExchange.getRequestMethod();
            requestedRequestUri = httpExchange.getRequestURI().toString();
            try {
                Thread.sleep(headerDelayMillis);
                byte[] responseBodyBytes = responseBodyText.getBytes(StandardCharsets.UTF_8);
                httpExchange.getResponseHeaders().set("Content-Type", "application/json");
                httpExchange.sendResponseHeaders(httpResponseStatus, responseBodyBytes.length);
                Thread.sleep(bodyDelayMillis);
                httpExchange.getResponseBody().write(responseBodyBytes);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException ignored) {
                // タイムアウト試験ではClientが先に接続を閉じるため、書込み失敗は想定内。
            } finally {
                httpExchange.close();
            }
        });
        mockHttpServer.start();
    }

    @AfterEach
    void stopServer() { if (mockHttpServer != null) mockHttpServer.stop(0); }

    /** 通信先だけをローカルへ替え、実運用と同じrequest factoryとJSON設定を使う。 */
    private CoinbaseClient createCoinbaseClient(Duration readTimeout) {
        var coinbaseHttpProperties = new CoinbaseHttpProperties(Duration.ofSeconds(1), readTimeout);
        var coinbaseRestClient = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + mockHttpServer.getAddress().getPort())
                .requestFactory(CoinbaseHttpConfiguration.createHttpRequestFactory(coinbaseHttpProperties)).build();
        Clock fixedClock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId timeZone) { return Clock.fixed(FIXED_RECEIVED_AT, timeZone); }
            public Instant instant() { clockReadCount.incrementAndGet(); return FIXED_RECEIVED_AT; }
        };
        return new CoinbaseClient(coinbaseRestClient, new ObjectMapper(), fixedClock);
    }

    /**
     * 観点：A-HTTP-01 — 正しいGETで取得し受信時刻をMapperへ引き継ぐ。
     * 前提・入力：ローカル模擬HTTPが正常JSONを返す。受信時刻は記録回数を数える固定時計。
     * 操作：Clientで取得後、実Mapperへ応答とreceivedAtを渡す。
     * 期待結果：GETパス・クエリが一致し時計読取りは1回。変換はACCEPTED、observedAtとBid価格を保持しsourceAtはnull。
     */
    @DisplayName("A-HTTP-01：正しいGETで取得し受信時刻をMapperへ引き継ぐ")
    @Test
    void fetchesBookAndPassesReceivedTimeToMapper() {
        // Act：正常な本文を受信し、専用DTOと受信時刻を取得する。
        var coinbaseBookResponseWithReceivedAt = createCoinbaseClient(Duration.ofSeconds(2)).fetchBtcUsd();
        assertEquals("GET", requestedHttpMethod);
        assertEquals("/products/BTC-USD/book?level=1", requestedRequestUri);
        assertEquals(FIXED_RECEIVED_AT, coinbaseBookResponseWithReceivedAt.receivedAt());
        assertEquals(1, clockReadCount.get());

        // 受信時刻を取り直さず、既存Mapperへそのまま渡す。
        var coinbaseBookConversionResult = new CoinbaseBookMapper().convertBtcUsd(coinbaseBookResponseWithReceivedAt.response(), coinbaseBookResponseWithReceivedAt.receivedAt());
        assertEquals(CoinbaseBookConversionResult.Status.ACCEPTED, coinbaseBookConversionResult.status());
        assertEquals(FIXED_RECEIVED_AT, coinbaseBookConversionResult.observation().observedAt());
        assertNull(coinbaseBookConversionResult.observation().sourceAt());
        assertEquals(0, new java.math.BigDecimal("100.10").compareTo(coinbaseBookConversionResult.observation().bidPrice()));
    }

    /**
     * 観点：A-HTTP-02 — HTTPエラーを本文解析より先に分類し再試行しない。
     * 前提・入力：HTTP 400/429/500/503/302の各応答。本文は不正JSON。
     * 操作：fetchBtcUsdを呼ぶ。
     * 期待結果：429はRATE_LIMITED、それ以外はEXTERNAL_HTTP_ERROR。HTTP値を保持、要求1回、時計読取り0回。302へ追従しない。
     */
    @DisplayName("A-HTTP-02：HTTPエラーを本文解析より先に分類し再試行しない")
    @ParameterizedTest(name = "[{index}] {argumentsWithNames}")
    @ValueSource(ints = {400, 429, 500, 503, 302})
    void classifiesHttpFailure(int httpStatus) {
        httpResponseStatus = httpStatus;
        responseBodyText = "not json";
        var accessException = assertThrows(CoinbaseAccessException.class,
                () -> createCoinbaseClient(Duration.ofSeconds(2)).fetchBtcUsd());
        assertEquals(httpStatus == 429 ? CoinbaseAccessException.Reason.RATE_LIMITED
                : CoinbaseAccessException.Reason.EXTERNAL_HTTP_ERROR, accessException.reason());
        assertEquals(httpStatus, accessException.httpStatus());
        assertEquals(1, httpRequestCount.get());
        assertEquals(0, clockReadCount.get());
    }

    /**
     * 観点：A-HTTP-03 — 空・不正JSON・DTO型不一致を読込み失敗にする。
     * 前提・入力：ValueSourceは空本文、壊れたJSON、null、配列、bidsの数値、auction_modeの文字列/数値、timeの数値。
     * 操作：HTTP成功応答をfetchBtcUsdで読む。
     * 期待結果：INVALID_RESPONSEの例外。本文受信後・解析前に時計を1回読む。価格の業務検証はこの試験の対象外。
     */
    @DisplayName("A-HTTP-03：空・不正JSON・DTO型不一致を読込み失敗にする")
    @ParameterizedTest(name = "[{index}] {argumentsWithNames}")
    @ValueSource(strings = {"", "{", "null", "[]", "{\"bids\":42}",
            "{\"auction_mode\":\"false\"}", "{\"auction_mode\":0}", "{\"time\":123}"})
    void rejectsUnreadableResponse(String invalidResponseBodyText) {
        responseBodyText = invalidResponseBodyText;
        var accessException = assertThrows(CoinbaseAccessException.class,
                () -> createCoinbaseClient(Duration.ofSeconds(2)).fetchBtcUsd());
        assertEquals(CoinbaseAccessException.Reason.INVALID_RESPONSE, accessException.reason());
        // 不正JSONでも本文は受信済み。解析失敗より先に時計を読んだことを確認する。
        assertEquals(1, clockReadCount.get());
    }

    /**
     * 観点：A-HTTP-04 — ヘッダー待ちと本文待ちのタイムアウトを分類する。
     * 前提・入力：delayHeaders=trueはヘッダーを500ms遅延、falseは本文を500ms遅延。読取り待ちは100ms。
     * 操作：fetchBtcUsdを呼ぶ。
     * 期待結果：TIMEOUT、例外のhttpStatusはnull、時計読取り0回、要求1回。処理全体の厳密な所要時間はassertしていない。
     */
    @DisplayName("A-HTTP-04：ヘッダー待ちと本文待ちのタイムアウトを分類する")
    @ParameterizedTest(name = "[{index}] {argumentsWithNames}")
    @ValueSource(booleans = {true, false})
    void timesOutWithoutRetry(boolean delayHeaders) {
        headerDelayMillis = delayHeaders ? 500 : 0;
        bodyDelayMillis = delayHeaders ? 0 : 500;
        var accessException = assertThrows(CoinbaseAccessException.class,
                () -> createCoinbaseClient(Duration.ofMillis(100)).fetchBtcUsd());
        assertEquals(CoinbaseAccessException.Reason.TIMEOUT, accessException.reason());
        assertNull(accessException.httpStatus());
        assertEquals(0, clockReadCount.get());
        assertEquals(1, httpRequestCount.get());
    }

    /**
     * 観点：A-HTTP-05 — 接続拒否をHTTP応答エラーと区別する。
     * 前提・入力：一時確保して閉じたローカルポート。接続/読取り待ちを200msに設定。
     * 操作：そのポートへClientで接続する。
     * 期待結果：NETWORK_ERRORの例外。到達不能ネットワークの接続タイムアウト実測とは異なる。
     */
    @DisplayName("A-HTTP-05：接続拒否をHTTP応答エラーと区別する")
    @Test
    void classifiesConnectionFailure() throws Exception {
        int closedPort;
        try (var socket = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        var coinbaseHttpProperties = new CoinbaseHttpProperties(Duration.ofMillis(200), Duration.ofMillis(200));
        var coinbaseRestClient = RestClient.builder().baseUrl("http://127.0.0.1:" + closedPort)
                .requestFactory(CoinbaseHttpConfiguration.createHttpRequestFactory(coinbaseHttpProperties)).build();
        var accessException = assertThrows(CoinbaseAccessException.class,
                () -> new CoinbaseClient(coinbaseRestClient, new ObjectMapper(), Clock.fixed(FIXED_RECEIVED_AT, ZoneOffset.UTC))
                        .fetchBtcUsd());
        assertEquals(CoinbaseAccessException.Reason.NETWORK_ERROR, accessException.reason());
    }

    /**
     * 観点：A-HTTP-06 — 応答本文の受信上限超過を拒否する。
     * 前提・入力：HTTP成功で256KiB+1バイトの本文を返す。
     * 操作：fetchBtcUsdを呼ぶ。
     * 期待結果：RESPONSE_TOO_LARGEの例外。上限ちょうどの成功ケースはこのメソッドでは確認しない。
     */
    @DisplayName("A-HTTP-06：応答本文の受信上限超過を拒否する")
    @Test
    void rejectsOversizedBody() {
        responseBodyText = "x".repeat(256 * 1024 + 1);
        var accessException = assertThrows(CoinbaseAccessException.class,
                () -> createCoinbaseClient(Duration.ofSeconds(2)).fetchBtcUsd());
        assertEquals(CoinbaseAccessException.Reason.RESPONSE_TOO_LARGE, accessException.reason());
    }

    /**
     * 観点：A-CFG-01 — 不正タイムアウト設定を起動設定作成時に拒否する。
     * 前提・入力：接続待ちに0ms、-1ms、Integer.MAX_VALUE+1msを指定。読取り待ちは正常値。
     * 操作：CoinbaseHttpPropertiesを生成する。
     * 期待結果：IllegalArgumentException。実HTTPは呼ばない。設定検証のUTであり、通信待ち時間の測定ではない。
     */
    @DisplayName("A-CFG-01：不正タイムアウト設定を起動設定作成時に拒否する")
    @ParameterizedTest(name = "[{index}] {argumentsWithNames}")
    @ValueSource(longs = {0, -1, 2147483648L})
    void rejectsInvalidTimeout(long timeoutMillis) {
        assertThrows(IllegalArgumentException.class,
                () -> new CoinbaseHttpProperties(Duration.ofMillis(timeoutMillis), Duration.ofSeconds(5)));
    }
}
