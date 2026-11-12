package com.example.cryptomonitor.collection.service;

import com.example.cryptomonitor.coinbase.CoinbaseAccessException;
import com.example.cryptomonitor.coinbase.dto.CoinbaseBookConversionResult;
import com.example.cryptomonitor.coinbase.mapper.CoinbaseBookMapper;
import com.example.cryptomonitor.coinbase.client.CoinbaseClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * BTC-USDの収集1回を進行する。HTTP待ちをTx外に置き、開始・結果を別々に確定する。
 * Scheduler/Controllerを新設しないため、アプリを起動しただけでは収集を開始しない。
 */
@Service
public class CollectionService {
    private static final Logger log = LoggerFactory.getLogger(CollectionService.class);
    private final CoinbaseClient coinbaseClient;
    private final CoinbaseBookMapper coinbaseBookMapper;
    private final CollectionPersistenceService collectionPersistenceService;

    public CollectionService(CoinbaseClient coinbaseClient, CoinbaseBookMapper coinbaseBookMapper,
            CollectionPersistenceService collectionPersistenceService) {
        this.coinbaseClient = coinbaseClient;
        this.coinbaseBookMapper = coinbaseBookMapper;
        this.collectionPersistenceService = collectionPersistenceService;
    }

    /**
     * 結果をDBとログへ記録する。戻り値なしを成功の意味に使わない。
     * 外側Txを引き継ぐ呼出しは拒否する。これによりHTTP中の接続保持や開始未コミットを防ぐ。
     */
    public void collectBtcUsd() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Collection must be invoked outside a transaction");

        final Long collectionRunId;
        try {
            collectionRunId = collectionPersistenceService.startBtcUsd();
        } catch (RuntimeException exception) {
            log.error("Collection start could not be confirmed; exchange=COINBASE_EXCHANGE symbol=BTC-USD; HTTP not called", exception);
            return;
        }

        final CoinbaseBookConversionResult coinbaseBookConversionResult;
        try {
            var coinbaseBookResponseWithReceivedAt = coinbaseClient.fetchBtcUsd();
            coinbaseBookConversionResult = coinbaseBookMapper.convertBtcUsd(
                    coinbaseBookResponseWithReceivedAt.response(),
                    coinbaseBookResponseWithReceivedAt.receivedAt());
        } catch (CoinbaseAccessException exception) {
            log.warn("Collection access failed; runId={} reason={} httpStatus={}",
                    collectionRunId, exception.reason(), exception.httpStatus());
            recordFailure(collectionRunId, accessCode(exception.reason()));
            return;
        } catch (RuntimeException exception) {
            log.error("Unexpected collection error; runId={}", collectionRunId, exception);
            recordFailure(collectionRunId, "UNEXPECTED_ERROR");
            return;
        }

        // 保存失敗を通信エラーとして誤分類しないため、取得・変換とは別のtry/catchを使う。
        try {
            switch (coinbaseBookConversionResult.status()) {
                case ACCEPTED -> collectionPersistenceService.completeSuccessfully(
                        collectionRunId, coinbaseBookConversionResult.observation());
                case SKIPPED -> collectionPersistenceService.completeAsSkipped(
                        collectionRunId, coinbaseBookConversionResult.resultCode());
                case FAILED -> {
                    if (!collectionPersistenceService.completeAsFailedIfRunning(
                            collectionRunId, coinbaseBookConversionResult.resultCode())) {
                        log.warn("FAILED not written; runId={} reason={}; run is absent or already completed",
                                collectionRunId, coinbaseBookConversionResult.resultCode());
                        return;
                    }
                }
            }
            log.info("Collection result committed; runId={} conversion={} reason={}",
                    collectionRunId, coinbaseBookConversionResult.status(), coinbaseBookConversionResult.resultCode());
        } catch (RuntimeException exception) {
            log.error("Collection persistence result unconfirmed; runId={} conversion={} reason={}",
                    collectionRunId, coinbaseBookConversionResult.status(), coinbaseBookConversionResult.resultCode(), exception);
            recordFailure(collectionRunId, "PERSISTENCE_ERROR");
        }
    }

    /** 元のTx終了後に呼ぶ。救済に失敗しても再帰的に再試行せず、記録不能をログに残す。 */
    private void recordFailure(Long collectionRunId, String resultCode) {
        try {
            if (collectionPersistenceService.completeAsFailedIfRunning(collectionRunId, resultCode)) {
                log.info("FAILED committed; runId={} reason={}", collectionRunId, resultCode);
            } else {
                log.warn("FAILED not written; runId={} reason={}; existing state preserved, final result not confirmed", collectionRunId, resultCode);
            }
        } catch (RuntimeException exception) {
            log.error("Could not confirm failure recording; runId={} reason={}; RUNNING may remain", collectionRunId, resultCode, exception);
        }
    }

    /** 通信境界の分類を履歴コードへ明示対応付けする。DB/API用の例外には置き換えない。 */
    private static String accessCode(CoinbaseAccessException.Reason reason) {
        return switch (reason) {
            case TIMEOUT -> "TIMEOUT";
            case NETWORK_ERROR -> "NETWORK_ERROR";
            case EXTERNAL_HTTP_ERROR -> "EXTERNAL_HTTP_ERROR";
            case RATE_LIMITED -> "RATE_LIMITED";
            case INVALID_RESPONSE -> "INVALID_RESPONSE";
            case RESPONSE_TOO_LARGE -> "RESPONSE_TOO_LARGE";
        };
    }
}
