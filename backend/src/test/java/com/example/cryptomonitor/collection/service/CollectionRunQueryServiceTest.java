package com.example.cryptomonitor.collection.service;

import com.example.cryptomonitor.collection.entity.CollectionRun;
import com.example.cryptomonitor.collection.entity.CollectionRunStatus;
import com.example.cryptomonitor.collection.repository.CollectionRunRepository;
import com.example.cryptomonitor.common.api.InvalidApiParameterException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 試験区分：機能CのUT。Repositoryをモック化し、実DB/HTTPは使わない。
 * 実行：mvnw.cmd testまたはIntelliJ。条件・期待値は各Sourceと観点コメントを参照。
 * 未保証：実SQLの件数制限・並び順・接続障害のHTTP変換は、DB結合/MockMvc試験が担当。
 */
@DisplayName("機能C / UT：実行履歴の条件・件数・公開DTO")
@ExtendWith(MockitoExtension.class)
class CollectionRunQueryServiceTest {
    @Mock CollectionRunRepository collectionRunRepository;
    @InjectMocks CollectionRunQueryService collectionRunQueryService;

    /** 観点：C-RUN-UT-01。前提：有効limitまたは省略。操作：検索。期待：0ページ目・指定/既定件数でDB検索を依頼、items空。 */
    @DisplayName("C-RUN-UT-01：省略時100件・有効limitをDB検索へ渡す")
    @ParameterizedTest(name = "[{index}] limit={0}")
    @NullSource @ValueSource(strings = {"1", "20", "100", "001"})
    void appliesDefaultAndValidLimits(String limit) {
        int expectedLimit = limit == null ? 100 : Integer.parseInt(limit);
        when(collectionRunRepository.findHistory("COINBASE_EXCHANGE", "BTC-USD", PageRequest.of(0, expectedLimit)))
                .thenReturn(List.of());
        assertTrue(collectionRunQueryService.findCollectionRuns("COINBASE_EXCHANGE", "BTC-USD", limit).items().isEmpty());
        verify(collectionRunRepository).findHistory("COINBASE_EXCHANGE", "BTC-USD", PageRequest.of(0, expectedLimit));
    }

    /** 観点：C-RUN-UT-02。前提：空/空白/範囲外/小数/非数値/桁あふれ。操作：検索。期待：400対象の専用例外、DB呼出しなし。 */
    @DisplayName("C-RUN-UT-02：不正limitをDB検索前に拒否する")
    @ParameterizedTest(name = "[{index}] limit=[{0}]")
    @ValueSource(strings = {"", " ", "0", "101", "-1", "1.5", "abc", "99999999999999999999999", "+1"})
    void rejectsInvalidLimits(String invalidLimit) {
        assertThrows(InvalidApiParameterException.class, () -> collectionRunQueryService.findCollectionRuns(null, null, invalidLimit));
        verifyNoInteractions(collectionRunRepository);
    }

    /** 観点：C-RUN-UT-03。前提：有効フィルターの省略/片方/両方。操作：検索。期待：同じ条件をRepositoryへ渡す。 */
    @DisplayName("C-RUN-UT-03：任意フィルターを変更せず渡す")
    @ParameterizedTest(name = "[{index}] exchange={0}, symbol={1}")
    @CsvSource(value = {"NONE,NONE", "COINBASE_EXCHANGE,NONE", "NONE,BTC-USD", "COINBASE_EXCHANGE,BTC-USD"}, nullValues = "NONE")
    void passesOptionalFilters(String exchange, String symbol) {
        collectionRunQueryService.findCollectionRuns(exchange, symbol, null);
        verify(collectionRunRepository).findHistory(exchange, symbol, PageRequest.of(0, 100));
    }

    /** 観点：C-RUN-UT-04。前提：空・未知の市場値。操作：各条件へ指定。期待：入力不正、DB呼出しなし。 */
    @DisplayName("C-RUN-UT-04：不正な市場条件を拒否する")
    @ParameterizedTest(name = "[{index}] 不正値=[{0}]")
    @ValueSource(strings = {"", " ", "OTHER"})
    void rejectsInvalidFilters(String invalidFilter) {
        assertThrows(InvalidApiParameterException.class, () -> collectionRunQueryService.findCollectionRuns(invalidFilter, null, null));
        assertThrows(InvalidApiParameterException.class, () -> collectionRunQueryService.findCollectionRuns(null, invalidFilter, null));
        verifyNoInteractions(collectionRunRepository);
    }

    /**
     * 観点：C-RUN-UT-05。前提：各状態の履歴モック、IDはJS安全整数の上限より大きい。
     * 操作：DTO変換。期待：全項目を保持し、SUCCESSの理由と説明はnull。それ以外のnull理由も捏造しない。
     */
    @DisplayName("C-RUN-UT-05：全状態のID・日時・null契約を保持する")
    @ParameterizedTest(name = "[{index}] status={0}")
    @EnumSource(CollectionRunStatus.class)
    void convertsAllStatuses(CollectionRunStatus status) {
        var collectionRun = historyEntity(status, status == CollectionRunStatus.SUCCESS ? "TIMEOUT" : null);
        when(collectionRunRepository.findHistory(null, null, PageRequest.of(0, 100))).thenReturn(List.of(collectionRun));
        var historyItem = collectionRunQueryService.findCollectionRuns(null, null, null).items().getFirst();
        assertAll(() -> assertEquals("9007199254740993", historyItem.id()),
                () -> assertEquals("COINBASE_EXCHANGE", historyItem.exchange()),
                () -> assertEquals("BTC-USD", historyItem.symbol()),
                () -> assertEquals(status.name(), historyItem.status()),
                () -> assertEquals(collectionRun.getStartedAt(), historyItem.startedAt()),
                () -> assertEquals(collectionRun.getFinishedAt(), historyItem.finishedAt()),
                () -> assertEquals(collectionRun.getInterruptionDetectedAt(), historyItem.interruptionDetectedAt()),
                () -> assertNull(historyItem.resultCode()), () -> assertNull(historyItem.resultDescription()));
    }

    /** 観点：C-RUN-UT-06。前提：既存理由コードと未知コード。操作：検索時の説明生成。期待：表の固定文言を返す。 */
    @DisplayName("C-RUN-UT-06：理由コードを公開用の固定説明へ変換する")
    @ParameterizedTest(name = "[{index}] code={0} → {1}")
    @CsvSource(delimiter = '|', value = {
        "AUCTION_MODE|オークション中のため観測を採用しませんでした",
        "EMPTY_BOOK|注文板が空のため観測を採用しませんでした",
        "ZERO_LIQUIDITY|取引可能な数量がないため観測を採用しませんでした",
        "INVALID_RESPONSE|外部APIの応答形式が不正でした", "INVALID_PRICE|価格の値が不正でした",
        "INVALID_SIZE|数量の値が不正でした", "UNSUPPORTED_PRECISION|保存可能な数値精度を超えています",
        "CROSSED_BOOK|買気配が売気配を上回っています", "TIMEOUT|外部APIの応答待ちがタイムアウトしました",
        "NETWORK_ERROR|外部APIとの通信に失敗しました", "EXTERNAL_HTTP_ERROR|外部APIが正常以外のHTTPステータスを返しました",
        "RATE_LIMITED|外部APIの利用制限に達しました", "RESPONSE_TOO_LARGE|外部APIの応答サイズが上限を超えています",
        "PERSISTENCE_ERROR|収集結果の保存を確認できませんでした", "UNEXPECTED_ERROR|収集処理中に予期しないエラーが発生しました",
        "INTERRUPTED|前回の収集処理の中断を検出しました", "UNKNOWN_CODE|収集結果の詳細は確認できません"
    })
    void describesStoredReason(String resultCode, String expectedDescription) {
        var collectionRun = historyEntity(CollectionRunStatus.FAILED, resultCode);
        when(collectionRunRepository.findHistory(null, null, PageRequest.of(0, 100))).thenReturn(List.of(collectionRun));
        var historyItem = collectionRunQueryService.findCollectionRuns(null, null, null).items().getFirst();
        assertEquals(resultCode, historyItem.resultCode());
        assertEquals(expectedDescription, historyItem.resultDescription());
    }

    /** 観点：C-RUN-UT-07。前提：Repositoryが例外。操作：検索。期待：例外を保持し、該当0件と誤認させない。 */
    @Test @DisplayName("C-RUN-UT-07：検索例外を呼出し元へ伝える")
    void propagatesSearchFailure() {
        var searchException = new IllegalStateException("query failed");
        when(collectionRunRepository.findHistory(null, null, PageRequest.of(0, 100))).thenThrow(searchException);
        assertSame(searchException, assertThrows(IllegalStateException.class,
                () -> collectionRunQueryService.findCollectionRuns(null, null, null)));
    }

    /** DTO変換用の履歴。固定日時で各状態の終了/中断検出時刻を組み立て、実DBの状態変更は行わない。 */
    private CollectionRun historyEntity(CollectionRunStatus status, String resultCode) {
        var collectionRun = mock(CollectionRun.class);
        var startedAt = Instant.parse("2026-11-08T01:02:03.123456Z");
        when(collectionRun.getId()).thenReturn(9007199254740993L);
        when(collectionRun.getExchange()).thenReturn("COINBASE_EXCHANGE");
        when(collectionRun.getSymbol()).thenReturn("BTC-USD");
        when(collectionRun.getStatus()).thenReturn(status);
        when(collectionRun.getStartedAt()).thenReturn(startedAt);
        when(collectionRun.getFinishedAt()).thenReturn(status == CollectionRunStatus.RUNNING || status == CollectionRunStatus.INTERRUPTED
                ? null : startedAt.plusSeconds(1));
        when(collectionRun.getInterruptionDetectedAt()).thenReturn(status == CollectionRunStatus.INTERRUPTED ? startedAt.plusSeconds(2) : null);
        // SUCCESSではServiceがgetResultCodeを呼ばないため、不要スタブは作らない。
        if (status != CollectionRunStatus.SUCCESS) when(collectionRun.getResultCode()).thenReturn(resultCode);
        return collectionRun;
    }
}
