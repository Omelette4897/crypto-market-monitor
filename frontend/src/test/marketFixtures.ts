import type { CollectionRunHistoryItem, LatestQuoteItem } from '../types/marketMonitor'

/** テスト専用データ。実市場の価格ではない。安全整数を超えるIDと小数12桁を残して精度を検査する。 */
export function latestQuoteFixture(overrides: Partial<LatestQuoteItem> = {}): LatestQuoteItem {
  return { id: '9223372036854775807', exchange: 'COINBASE_EXCHANGE', symbol: 'BTC-USD',
    bidPrice: '999999999999999999.123456789012', bidSize: '0.123456789012',
    askPrice: '999999999999999999.123456789013', askSize: '0.000000000001',
    observedAt: '2026-11-10T10:00:00.123456Z', sourceAt: null, ...overrides }
}

/** 状態別の表示は必要な項目だけ上書きして作る。毎回新しいオブジェクトを返す。 */
export function collectionRunFixture(overrides: Partial<CollectionRunHistoryItem> = {}): CollectionRunHistoryItem {
  return { id: '9223372036854775806', exchange: 'COINBASE_EXCHANGE', symbol: 'BTC-USD', status: 'SUCCESS',
    startedAt: '2026-11-10T10:00:00Z', finishedAt: '2026-11-10T10:00:01Z', interruptionDetectedAt: null,
    resultCode: null, resultDescription: null, ...overrides }
}

/** Promiseの解決順をテストから制御する。sleepや実ネットワークの速度に依存しない。 */
export function createDeferred<T>() {
  let resolvePromise!: (value: T) => void
  let rejectPromise!: (reason: unknown) => void
  const promise = new Promise<T>((resolve, reject) => { resolvePromise = resolve; rejectPromise = reject })
  return { promise, resolve: resolvePromise, reject: rejectPromise }
}


/** 履歴分析APIの固定応答。統計値はサーバーで計算済みとしてテストへ渡す。 */
export function quoteHistoryFixture() {
  return { rangeStart: '2026-11-09T10:01:00Z', rangeEnd: '2026-11-10T10:01:00Z', truncated: false,
    items: [{ id: '1', exchange: 'COINBASE_EXCHANGE', symbol: 'BTC-USD', observedAt: '2026-11-10T10:00:00Z',
      bidPrice: '100', askPrice: '101', spread: '1' }],
    spreadStatistics: { observationCount: 1, averageSpread: '1.0000000000000000', minimumSpread: '1', maximumSpread: '1' } }
}
