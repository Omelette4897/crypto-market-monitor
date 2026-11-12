package com.example.cryptomonitor.coinbase.config;

import com.example.cryptomonitor.coinbase.dto.CoinbaseBookConversionResult;
import com.example.cryptomonitor.coinbase.mapper.CoinbaseBookMapper;
import com.example.cryptomonitor.coinbase.client.CoinbaseClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 実CoinbaseへのHTTP通信から共通観測への変換までを確認する、明示実行専用の結合テスト。
 * ITという末尾名で通常のSurefire実行対象から外し、さらにシステムプロパティで実行を許可する。
 * Spring全体を起動しないため、MySQL・Vue・定期実行には依存せず、DBへの書込みも行わない。
 *
 * 試験区分：実外部接続確認。実Client/Mapperを使うがDB・画面は使わない。
 * 実行方法：Maven: .\mvnw.cmd "-Dtest=CoinbaseLiveConnectionIT" "-Dcoinbase.live-test=true" test。IntelliJ: VMオプション -Dcoinbase.live-test=true。
 * ケース仕様：各メソッドの観点ID・前提/入力・操作・期待結果を正とする。
 * 共通データ：固定値はフィールド/準備メソッド、入力の組合せは各Source注釈を参照。
 * 保証範囲外・未実施：通常test/DB統合プロファイルの対象外。外部状況で失敗し得る。機能全体のST/E2E・異常系網羅ではない。
 */
@DisplayName("機能A / 実外部接続：Coinbase公開APIから共通観測まで")
@Tag("live")
@EnabledIfSystemProperty(named = "coinbase.live-test", matches = "true")
class CoinbaseLiveConnectionIT {

    /**
     * 観点：A-LIVE-01 — 実Coinbaseの注文板を共通観測へ変換できる。
     * 前提・入力：coinbase.live-test=trueで明示許可。実インターネットとCoinbase公開APIを使い、固定価格を期待しない。
     * 操作：既存設定・ClientでBTC-USDを1回取得し、実Mapperで変換する。
     * 期待結果：ACCEPTED、正の価格/数量、Bid≦Ask、取引所/ペア一致、受信時刻の引継ぎと要求前後の時刻範囲、sourceAt=null。DB保存や画面は検証しない。
     */
    @DisplayName("A-LIVE-01：実Coinbaseの注文板を共通観測へ変換できる")
    @Test
    void fetchesLiveBookAndConvertsToMarketObservation() {
        // 通信Beanの生成方法を再利用する。テスト独自の接続先・緩いJSON設定へ差し替えない。
        var coinbaseHttpConfiguration = new CoinbaseHttpConfiguration();
        var coinbaseHttpProperties = new CoinbaseHttpProperties(Duration.ofSeconds(3), Duration.ofSeconds(5));
        var coinbaseClient = new CoinbaseClient(coinbaseHttpConfiguration.coinbaseRestClient(coinbaseHttpProperties),
                new ObjectMapper(), coinbaseHttpConfiguration.coinbaseClock());
        var coinbaseBookMapper = new CoinbaseBookMapper();

        Instant requestStartedAt = Instant.now();
        var coinbaseBookResponseWithReceivedAt = coinbaseClient.fetchBtcUsd();
        Instant requestCompletedAt = Instant.now();

        // receivedAtをそのまま渡す。変換の瞬間を新しい観測時刻として作り直さない。
        var coinbaseBookConversionResult = coinbaseBookMapper.convertBtcUsd(coinbaseBookResponseWithReceivedAt.response(), coinbaseBookResponseWithReceivedAt.receivedAt());
        assertEquals(CoinbaseBookConversionResult.Status.ACCEPTED, coinbaseBookConversionResult.status(),
                () -> "実応答を採用できませんでした。resultCode=" + coinbaseBookConversionResult.resultCode());

        var marketObservation = coinbaseBookConversionResult.observation();
        assertAll("共通観測への変換結果",
                () -> assertEquals("COINBASE_EXCHANGE", marketObservation.exchange()),
                () -> assertEquals("BTC-USD", marketObservation.symbol()),
                () -> assertTrue(marketObservation.bidPrice().signum() > 0),
                () -> assertTrue(marketObservation.askPrice().signum() > 0),
                () -> assertTrue(marketObservation.bidSize().signum() > 0),
                () -> assertTrue(marketObservation.askSize().signum() > 0),
                () -> assertTrue(marketObservation.bidPrice().compareTo(marketObservation.askPrice()) <= 0),
                () -> assertEquals(coinbaseBookResponseWithReceivedAt.receivedAt(), marketObservation.observedAt()),
                () -> assertFalse(marketObservation.observedAt().isBefore(requestStartedAt)),
                () -> assertFalse(marketObservation.observedAt().isAfter(requestCompletedAt)),
                () -> assertNull(marketObservation.sourceAt()));

        // 公開市場値だけを表示する。接続情報や外部応答全文はログへ出さない。
        System.out.printf("Coinbase実接続成功: observedAt=%s, bid=%s (%s BTC), ask=%s (%s BTC)%n",
                marketObservation.observedAt(), marketObservation.bidPrice(), marketObservation.bidSize(),
                marketObservation.askPrice(), marketObservation.askSize());
    }
}
