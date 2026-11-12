package com.example.cryptomonitor.collection.service;

import com.example.cryptomonitor.coinbase.CoinbaseAccessException;
import com.example.cryptomonitor.coinbase.dto.CoinbaseBookConversionResult;
import com.example.cryptomonitor.coinbase.mapper.CoinbaseBookMapper;
import com.example.cryptomonitor.coinbase.dto.CoinbaseBookResponse;
import com.example.cryptomonitor.coinbase.dto.CoinbaseBookResponseWithReceivedAt;
import com.example.cryptomonitor.coinbase.client.CoinbaseClient;
import com.example.cryptomonitor.market.model.MarketObservation;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 収集の進行をDB・HTTPなしで検証する。rollbackそのものは実DBの試験で確認する。
 * 代表例はcollectsAndPersistsUsingSameRunId。順序と実行ID・観測の引継ぎを追える。
 *
 * 試験区分：UT。Client・Mapper・保存ServiceをMockitoで置換し、実DB/HTTPを使わない。
 * 実行方法：通常の .\mvnw.cmd test またはIntelliJのクラス実行。
 * ケース仕様：各メソッドの観点ID・前提/入力・操作・期待結果を正とする。
 * 共通データ：固定値はフィールド/準備メソッド、入力の組合せは各Source注釈を参照。
 * 保証範囲外・未実施：verifyは呼出しを保証するだけで、実DB状態・commit/rollback・ログ内容は保証しない。DB結合確認を参照。
 */
@DisplayName("機能B / UT：収集進行・保存分岐・障害時の制御")
@ExtendWith(MockitoExtension.class)
class CollectionServiceTest {
    @Mock CoinbaseClient coinbaseClient;
    @Mock CoinbaseBookMapper coinbaseBookMapper;
    @Mock CollectionPersistenceService collectionPersistenceService;
    @InjectMocks CollectionService collectionService;
    private final Instant receivedAt = Instant.parse("2026-11-06T00:00:00.123456789Z");
    // Mapper自体をモック化するため板の中身は不要。実Mapperへ渡す正常応答ではなく、受渡し確認用の識別データ。
    private final CoinbaseBookResponse coinbaseBookResponse = new CoinbaseBookResponse(null, null, false, null);

    /**
     * 観点：B-SVC-01 — 開始・取得・変換・成功保存を同じ実行IDで進める。
     * 前提・入力：開始はID=42を返す。Clientは固定応答/時刻、MapperはACCEPTEDと観測を返すモック。
     * 操作：collectBtcUsdを呼ぶ。
     * 期待結果：開始→Client→Mapper→成功保存の順序、同じIDと観測の引継ぎ、FAILED更新を呼ばない。実commitはDB結合試験で確認する。
     */
    @DisplayName("B-SVC-01：開始・取得・変換・成功保存を同じ実行IDで進める")
    @Test void collectsAndPersistsUsingSameRunId() {
        var marketObservation = acceptedObservation();
        stubConversion(new CoinbaseBookConversionResult(CoinbaseBookConversionResult.Status.ACCEPTED, marketObservation, null));
        collectionService.collectBtcUsd();
        var invocationOrder = inOrder(collectionPersistenceService, coinbaseClient, coinbaseBookMapper);
        invocationOrder.verify(collectionPersistenceService).startBtcUsd();
        invocationOrder.verify(coinbaseClient).fetchBtcUsd();
        invocationOrder.verify(coinbaseBookMapper).convertBtcUsd(coinbaseBookResponse, receivedAt);
        invocationOrder.verify(collectionPersistenceService).completeSuccessfully(42L, marketObservation);
        verify(collectionPersistenceService, never()).completeAsFailedIfRunning(any(), any());
    }

    /**
     * 観点：B-SVC-02 — 開始保存失敗時は外部取得を行わない。
     * 前提・入力：開始保存のモックが例外を送出する。
     * 操作：collectBtcUsdを呼ぶ。
     * 期待結果：例外を呼出し元へ送出せず、Client・Mapperを呼ばない。開始行の有無やログ本文はこのUTでは検証しない。
     */
    @DisplayName("B-SVC-02：開始保存失敗時は外部取得を行わない")
    @Test void doesNotFetchWhenStartFails() {
        when(collectionPersistenceService.startBtcUsd()).thenThrow(new IllegalStateException("DB unavailable"));
        assertDoesNotThrow(collectionService::collectBtcUsd);
        verifyNoInteractions(coinbaseClient, coinbaseBookMapper);
    }

    /**
     * 観点：B-SVC-03 — スキップ理由を保存担当へ渡し成功保存しない。
     * 前提・入力：MapperがSKIPPED/EMPTY_BOOKを返す。
     * 操作：collectBtcUsdを呼ぶ。
     * 期待結果：ID=42でcompleteAsSkippedを呼び、completeSuccessfullyは呼ばない。
     */
    @DisplayName("B-SVC-03：スキップ理由を保存担当へ渡し成功保存しない")
    @Test void persistsSkippedReasonWithoutPrice() {
        stubConversion(new CoinbaseBookConversionResult(CoinbaseBookConversionResult.Status.SKIPPED, null, "EMPTY_BOOK"));
        collectionService.collectBtcUsd();
        verify(collectionPersistenceService).completeAsSkipped(42L, "EMPTY_BOOK");
        verify(collectionPersistenceService, never()).completeSuccessfully(any(), any());
    }

    /**
     * 観点：B-SVC-04 — Mapperの不採用理由でFAILED保存を依頼する。
     * 前提・入力：MapperがFAILED/INVALID_PRICE、条件付き失敗更新はtrue。
     * 操作：collectBtcUsdを呼ぶ。
     * 期待結果：ID=42・INVALID_PRICEで失敗保存を呼び、成功保存は呼ばない。
     */
    @DisplayName("B-SVC-04：Mapperの不採用理由でFAILED保存を依頼する")
    @Test void persistsMapperFailure() {
        stubConversion(new CoinbaseBookConversionResult(CoinbaseBookConversionResult.Status.FAILED, null, "INVALID_PRICE"));
        when(collectionPersistenceService.completeAsFailedIfRunning(42L, "INVALID_PRICE")).thenReturn(true);
        collectionService.collectBtcUsd();
        verify(collectionPersistenceService).completeAsFailedIfRunning(42L, "INVALID_PRICE");
        verify(collectionPersistenceService, never()).completeSuccessfully(any(), any());
    }

    /**
     * 観点：B-SVC-05 — 通信例外の全分類を履歴理由へ対応付ける。
     * 前提・入力：EnumSourceで通信Reason全6値を渡し、Clientがその例外を送出する。
     * 操作：collectBtcUsdを呼ぶ。
     * 期待結果：同名の理由コードでFAILED更新を依頼し、Mapperは呼ばない。DBの実際の状態はこのUTでは確認しない。
     */
    @DisplayName("B-SVC-05：通信例外の全分類を履歴理由へ対応付ける")
    @ParameterizedTest(name = "[{index}] {argumentsWithNames}") @EnumSource(CoinbaseAccessException.Reason.class)
    void persistsEachAccessFailure(CoinbaseAccessException.Reason accessFailureReason) {
        when(collectionPersistenceService.startBtcUsd()).thenReturn(42L);
        when(coinbaseClient.fetchBtcUsd()).thenThrow(new CoinbaseAccessException(accessFailureReason, null, null));
        collectionService.collectBtcUsd();
        verify(collectionPersistenceService).completeAsFailedIfRunning(42L, accessFailureReason.name());
        verifyNoInteractions(coinbaseBookMapper);
    }

    /**
     * 観点：B-SVC-06 — 想定外の変換例外を通信不正と混同しない。
     * 前提・入力：Clientは成功し、MapperがIllegalArgumentExceptionを送出する。
     * 操作：collectBtcUsdを呼ぶ。
     * 期待結果：UNEXPECTED_ERRORでFAILED更新を依頼する。ログのスタックトレース本文はassertしていない。
     */
    @DisplayName("B-SVC-06：想定外の変換例外を通信不正と混同しない")
    @Test void recordsUnexpectedMapperException() {
        stubReceivedResponse();
        when(coinbaseBookMapper.convertBtcUsd(coinbaseBookResponse, receivedAt)).thenThrow(new IllegalArgumentException("unexpected"));
        collectionService.collectBtcUsd();
        verify(collectionPersistenceService).completeAsFailedIfRunning(42L, "UNEXPECTED_ERROR");
    }

    /**
     * 観点：B-SVC-07 — 成功保存の例外後に保存障害の記録を試みる。
     * 前提・入力：MapperはACCEPTED。成功保存が例外、救済更新はtrueを返す。
     * 操作：collectBtcUsdを呼ぶ。
     * 期待結果：開始→成功保存→PERSISTENCE_ERRORで失敗保存の順。別Txでの確定・rollback自体はDB試験で確認する。
     */
    @DisplayName("B-SVC-07：成功保存の例外後に保存障害の記録を試みる")
    @Test void attemptsFailureAfterSuccessfulCompletionThrows() {
        var marketObservation = acceptedObservation();
        stubConversion(new CoinbaseBookConversionResult(CoinbaseBookConversionResult.Status.ACCEPTED, marketObservation, null));
        doThrow(new IllegalStateException("commit unconfirmed")).when(collectionPersistenceService).completeSuccessfully(42L, marketObservation);
        when(collectionPersistenceService.completeAsFailedIfRunning(42L, "PERSISTENCE_ERROR")).thenReturn(true);
        collectionService.collectBtcUsd();
        var invocationOrder = inOrder(collectionPersistenceService);
        invocationOrder.verify(collectionPersistenceService).startBtcUsd();
        invocationOrder.verify(collectionPersistenceService).completeSuccessfully(42L, marketObservation);
        invocationOrder.verify(collectionPersistenceService).completeAsFailedIfRunning(42L, "PERSISTENCE_ERROR");
    }

    /**
     * 観点：B-SVC-08 — スキップ保存失敗を保存障害として救済する。
     * 前提・入力：SKIPPED/AUCTION_MODEの保存モックが例外を送出する。
     * 操作：collectBtcUsdを呼ぶ。
     * 期待結果：PERSISTENCE_ERRORでFAILED更新を依頼する。
     */
    @DisplayName("B-SVC-08：スキップ保存失敗を保存障害として救済する")
    @Test void attemptsFailureWhenSkippedCompletionThrows() {
        stubConversion(new CoinbaseBookConversionResult(CoinbaseBookConversionResult.Status.SKIPPED, null, "AUCTION_MODE"));
        doThrow(new IllegalStateException("DB down")).when(collectionPersistenceService).completeAsSkipped(42L, "AUCTION_MODE");
        collectionService.collectBtcUsd();
        verify(collectionPersistenceService).completeAsFailedIfRunning(42L, "PERSISTENCE_ERROR");
    }

    /**
     * 観点：B-SVC-09 — 検証失敗の保存が失敗した場合にも救済を試みる。
     * 前提・入力：FAILED/INVALID_PRICEの条件付き更新が例外を送出する。
     * 操作：collectBtcUsdを呼ぶ。
     * 期待結果：PERSISTENCE_ERRORで別のFAILED更新を依頼する。
     */
    @DisplayName("B-SVC-09：検証失敗の保存が失敗した場合にも救済を試みる")
    @Test void attemptsFailureWhenMapperFailureCannotBeSaved() {
        stubConversion(new CoinbaseBookConversionResult(CoinbaseBookConversionResult.Status.FAILED, null, "INVALID_PRICE"));
        when(collectionPersistenceService.completeAsFailedIfRunning(42L, "INVALID_PRICE")).thenThrow(new IllegalStateException("DB down"));
        collectionService.collectBtcUsd();
        verify(collectionPersistenceService).completeAsFailedIfRunning(42L, "PERSISTENCE_ERROR");
    }

    /**
     * 観点：B-SVC-10 — 救済更新0件なら再取得・再更新を繰り返さない。
     * 前提・入力：成功保存はコミット応答喪失を模した例外、救済更新はfalse。
     * 操作：collectBtcUsdを呼ぶ。
     * 期待結果：例外を外へ送出せず、Client取得と救済呼出しは各1回。DBのSUCCESS保護そのものはB-DB-08で検証する。
     */
    @DisplayName("B-SVC-10：救済更新0件なら再取得・再更新を繰り返さない")
    @Test void doesNotRetryOrOverwriteWhenRescueUpdatesZeroRows() {
        var marketObservation = acceptedObservation();
        stubConversion(new CoinbaseBookConversionResult(CoinbaseBookConversionResult.Status.ACCEPTED, marketObservation, null));
        doThrow(new IllegalStateException("commit response lost")).when(collectionPersistenceService).completeSuccessfully(42L, marketObservation);
        when(collectionPersistenceService.completeAsFailedIfRunning(42L, "PERSISTENCE_ERROR")).thenReturn(false);
        assertDoesNotThrow(collectionService::collectBtcUsd);
        verify(collectionPersistenceService, times(1)).completeAsFailedIfRunning(42L, "PERSISTENCE_ERROR");
        verify(coinbaseClient, times(1)).fetchBtcUsd();
    }

    /**
     * 観点：B-SVC-11 — 失敗履歴も保存不能なら再帰的に再試行せず終了する。
     * 前提・入力：ClientはTIMEOUT、TIMEOUTの失敗記録も例外を送出する。
     * 操作：collectBtcUsdを呼ぶ。
     * 期待結果：例外を外へ送出せず、TIMEOUTの記録試行は1回。ログ本文と実DB復旧は対象外。
     */
    @DisplayName("B-SVC-11：失敗履歴も保存不能なら再帰的に再試行せず終了する")
    @Test void endsWithoutRecursiveRetriesWhenFailureCannotBeRecorded() {
        when(collectionPersistenceService.startBtcUsd()).thenReturn(42L);
        when(coinbaseClient.fetchBtcUsd()).thenThrow(new CoinbaseAccessException(CoinbaseAccessException.Reason.TIMEOUT, null, null));
        when(collectionPersistenceService.completeAsFailedIfRunning(42L, "TIMEOUT")).thenThrow(new IllegalStateException("DB down"));
        assertDoesNotThrow(collectionService::collectBtcUsd);
        verify(collectionPersistenceService, times(1)).completeAsFailedIfRunning(42L, "TIMEOUT");
    }

    /**
     * 観点：B-SVC-12 — 検証失敗の更新0件で追加の救済更新を行わない。
     * 前提・入力：MapperはFAILED/INVALID_PRICE、失敗更新はfalseを返す。
     * 操作：collectBtcUsdを呼ぶ。
     * 期待結果：INVALID_PRICE更新は1回、PERSISTENCE_ERROR更新は0回。既確定状態のDB値をこのモック試験で保証したとは扱わない。
     */
    @DisplayName("B-SVC-12：検証失敗の更新0件で追加の救済更新を行わない")
    @Test void leavesAlreadyCompletedRunUnchangedForMapperFailure() {
        stubConversion(new CoinbaseBookConversionResult(CoinbaseBookConversionResult.Status.FAILED, null, "INVALID_PRICE"));
        collectionService.collectBtcUsd();
        verify(collectionPersistenceService, times(1)).completeAsFailedIfRunning(42L, "INVALID_PRICE");
        verify(collectionPersistenceService, never()).completeAsFailedIfRunning(42L, "PERSISTENCE_ERROR");
    }

    /**
     * 観点：B-SVC-13 — 外側Tx付き収集呼出しを開始前に拒否する。
     * 前提・入力：TransactionSynchronizationManagerでアクティブTxを模擬し、finallyで必ず解除する。
     * 操作：collectBtcUsdを呼ぶ。
     * 期待結果：IllegalStateException。Client・Mapper・保存担当をいずれも呼ばない。実Txでの確認はB-DB-17。
     */
    @DisplayName("B-SVC-13：外側Tx付き収集呼出しを開始前に拒否する")
    @Test void rejectsOuterTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThrows(IllegalStateException.class, collectionService::collectBtcUsd); }
        finally { TransactionSynchronizationManager.clear(); }
        verifyNoInteractions(coinbaseClient, coinbaseBookMapper, collectionPersistenceService);
    }

    /** 共通前提：開始ID=42、Clientは固定DTOと固定受信時刻を返す。実HTTP・DBは使わない。 */
    private void stubReceivedResponse() {
        when(collectionPersistenceService.startBtcUsd()).thenReturn(42L);
        when(coinbaseClient.fetchBtcUsd()).thenReturn(new CoinbaseBookResponseWithReceivedAt(coinbaseBookResponse, receivedAt));
    }
    /** 共通前提に加え、各ケースが指定した採否結果をMapperの戻り値として設定する。 */
    private void stubConversion(CoinbaseBookConversionResult coinbaseBookConversionResult) {
        stubReceivedResponse();
        when(coinbaseBookMapper.convertBtcUsd(coinbaseBookResponse, receivedAt)).thenReturn(coinbaseBookConversionResult);
    }
    /** 採用ケース用の固定観測。価格100/101、数量1/1、受信時刻は固定、sourceAtはnull。 */
    private MarketObservation acceptedObservation() {
        return new MarketObservation("COINBASE_EXCHANGE", "BTC-USD", new BigDecimal("100"),
                BigDecimal.ONE, new BigDecimal("101"), BigDecimal.ONE, receivedAt, null);
    }
}
