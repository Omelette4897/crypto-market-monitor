import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchCollectionRuns, fetchLatestQuotes, fetchQuoteHistory, MarketMonitorApiError } from '../api/marketMonitorApi'
import { collectionRunFixture, createDeferred, latestQuoteFixture, quoteHistoryFixture } from '../test/marketFixtures'
import { isObservationStale } from '../utils/marketDisplay'
import { useMarketMonitor } from './useMarketMonitor'

vi.mock('../api/marketMonitorApi', async importOriginal => ({
  ...await importOriginal<typeof import('../api/marketMonitorApi')>(),
  fetchLatestQuotes: vi.fn(), fetchCollectionRuns: vi.fn(), fetchQuoteHistory: vi.fn(),
}))

/**
 * 機能E / UT：実Vueの状態管理、API関数だけをモック。DB/HTTP/画面DOMは使わない。
 * 実行：npm.cmd test。固定時計と制御可能Promiseで、通信順序に依存せず検証する。
 * ケースごとにモックと時計を戻す。実ブラウザの表示確認は別工程。
 */
describe('機能E / 状態管理のUT', () => {
  const latestQuotesMock = vi.mocked(fetchLatestQuotes)
  const collectionRunsMock = vi.mocked(fetchCollectionRuns)
  beforeEach(() => {
    vi.mocked(fetchQuoteHistory).mockResolvedValue(quoteHistoryFixture())
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-11-10T10:01:00Z'))
    latestQuotesMock.mockResolvedValue([latestQuoteFixture()])
    collectionRunsMock.mockResolvedValue([collectionRunFixture()])
  })
  afterEach(() => { vi.useRealTimers(); vi.resetAllMocks() })

  // 前提：Composable生成直後。操作：状態参照。期待：未成功・未取得、生成だけで通信しない。
  it('E-STATE-01：初期状態は未取得で、別インスタンスと共有しない', () => {
    const firstMonitor = useMarketMonitor()
    const secondMonitor = useMarketMonitor()
    expect(firstMonitor.latestQuotesState).toEqual({ items: [], isLoading: false, errorMessage: null, lastSucceededAt: null })
    expect(firstMonitor.collectionRunsState.lastSucceededAt).toBeNull()
    expect(firstMonitor.isRefreshing.value).toBe(false)
    expect(firstMonitor.latestQuotesState).not.toBe(secondMonitor.latestQuotesState)
    expect(latestQuotesMock).not.toHaveBeenCalled()
  })

  // 前提：履歴だけ未完了。操作：更新。期待：両取得を開始し、価格を先に表示できる状態へ変える。
  it('E-STATE-02：並行取得し、先に完了した領域を独立更新する', async () => {
    const pendingHistory = createDeferred<ReturnType<typeof collectionRunFixture>[]>()
    collectionRunsMock.mockReturnValue(pendingHistory.promise)
    const monitor = useMarketMonitor()
    const refreshing = monitor.refreshMarketData()
    expect(latestQuotesMock).toHaveBeenCalledTimes(1)
    expect(collectionRunsMock).toHaveBeenCalledTimes(1)
    await flushPromises()
    expect(monitor.latestQuotesState.items).toEqual([latestQuoteFixture()])
    expect(monitor.latestQuotesState.isLoading).toBe(false)
    expect(monitor.collectionRunsState.isLoading).toBe(true)
    expect(monitor.isRefreshing.value).toBe(true)
    pendingHistory.resolve([collectionRunFixture()])
    await refreshing
    expect(monitor.isRefreshing.value).toBe(false)
    expect(monitor.collectionRunsState.lastSucceededAt).toBe('2026-11-10T10:01:00.000Z')
  })

  // 前提：どちらかだけ失敗。操作：初回取得。期待：失敗領域は未成功、他方にはデータが残る。
  it.each(['quotes', 'history'] as const)('E-STATE-03：%sだけ失敗しても他方を表示する', async failedRegion => {
    if (failedRegion === 'quotes') latestQuotesMock.mockRejectedValue(new MarketMonitorApiError('unavailable'))
    else collectionRunsMock.mockRejectedValue(new MarketMonitorApiError('unavailable'))
    const monitor = useMarketMonitor()
    await monitor.refreshMarketData()
    const failedState = failedRegion === 'quotes' ? monitor.latestQuotesState : monitor.collectionRunsState
    const successfulState = failedRegion === 'quotes' ? monitor.collectionRunsState : monitor.latestQuotesState
    expect(failedState.lastSucceededAt).toBeNull()
    expect(failedState.errorMessage).toContain('現在データを取得できません')
    expect(successfulState.items).toHaveLength(1)
    expect(successfulState.errorMessage).toBeNull()
    expect(monitor.isRefreshing.value).toBe(false)
  })

  // 前提：成功後、両APIが失敗。操作：時計を進めて更新。期待：値/成功日時/注意判定を保持し内部例外を隠す。
  it('E-STATE-04：再取得失敗では前回値・成功日時・判定を保持する', async () => {
    const monitor = useMarketMonitor()
    await monitor.refreshMarketData()
    const originalQuotes = [...monitor.latestQuotesState.items]
    const originalHistory = [...monitor.collectionRunsState.items]
    vi.setSystemTime(new Date('2026-11-10T10:10:00Z'))
    latestQuotesMock.mockRejectedValue(new Error('PRIVATE_DETAIL'))
    collectionRunsMock.mockRejectedValue(new MarketMonitorApiError('network'))
    await monitor.refreshMarketData()
    expect(monitor.latestQuotesState.items).toEqual(originalQuotes)
    expect(monitor.collectionRunsState.items).toEqual(originalHistory)
    expect(monitor.latestQuotesState.lastSucceededAt).toBe('2026-11-10T10:01:00.000Z')
    expect(monitor.collectionRunsState.lastSucceededAt).toBe('2026-11-10T10:01:00.000Z')
    expect(monitor.latestQuotesState.errorMessage).not.toContain('PRIVATE_DETAIL')
    expect(isObservationStale(originalQuotes[0]!.observedAt, monitor.latestQuotesState.lastSucceededAt)).toBe(false)
    expect(monitor.isRefreshing.value).toBe(false)
  })

  // 前提：過去の表示あり。操作：正常0件で再取得。期待：前回値を消し、成功日時は新しくする。
  it('E-STATE-05：正常0件は前回値をクリアして成功日時を更新する', async () => {
    const monitor = useMarketMonitor()
    await monitor.refreshMarketData()
    vi.setSystemTime(new Date('2026-11-10T10:02:00Z'))
    latestQuotesMock.mockResolvedValue([]); collectionRunsMock.mockResolvedValue([])
    await monitor.refreshMarketData()
    expect(monitor.latestQuotesState.items).toEqual([])
    expect(monitor.collectionRunsState.items).toEqual([])
    expect(monitor.latestQuotesState.lastSucceededAt).toBe('2026-11-10T10:02:00.000Z')
    expect(monitor.collectionRunsState.errorMessage).toBeNull()
  })

  // 前提：失敗済み。操作：次の手動取得が成功。期待：エラーを消し正常データへ戻す。
  it('E-STATE-06：次の取得成功でエラーを解消する', async () => {
    latestQuotesMock.mockRejectedValueOnce(new MarketMonitorApiError('network'))
    const monitor = useMarketMonitor()
    await monitor.refreshMarketData()
    expect(monitor.latestQuotesState.errorMessage).not.toBeNull()
    await monitor.refreshMarketData()
    expect(monitor.latestQuotesState.errorMessage).toBeNull()
    expect(monitor.latestQuotesState.items).toHaveLength(1)
  })

  // 前提：未完了の取得あり。操作：同じ更新を重ねる。期待：API呼出しは各1回。
  it('E-STATE-07：取得中の重複呼出しを防ぐ', async () => {
    const pendingQuotes = createDeferred<ReturnType<typeof latestQuoteFixture>[]>()
    latestQuotesMock.mockReturnValue(pendingQuotes.promise)
    const monitor = useMarketMonitor()
    const refreshing = monitor.refreshMarketData()
    await monitor.refreshMarketData()
    expect(latestQuotesMock).toHaveBeenCalledTimes(1)
    expect(collectionRunsMock).toHaveBeenCalledTimes(1)
    pendingQuotes.resolve([])
    await refreshing
  })

  // 前提：取得成功。操作：10分経過させる。期待：自動取得/成功日時更新なし。
  it('E-STATE-08：時間経過だけでは取得・成功日時を更新しない', async () => {
    const monitor = useMarketMonitor()
    await monitor.refreshMarketData()
    await vi.advanceTimersByTimeAsync(600_000)
    expect(latestQuotesMock).toHaveBeenCalledTimes(1)
    expect(collectionRunsMock).toHaveBeenCalledTimes(1)
    expect(monitor.latestQuotesState.lastSucceededAt).toBe('2026-11-10T10:01:00.000Z')
  })

  // 前提：3領域成功。操作：開始を二重呼出し→30秒経過→停止。期待：取得は初回+1回、時計更新、停止後通信なし。
  it('E-STATE-09：30秒自動更新と1秒時計を一度だけ開始し破棄時に停止する', async () => {
    const monitor = useMarketMonitor()
    monitor.startAutoRefresh(); monitor.startAutoRefresh()
    await flushPromises()
    expect(vi.mocked(fetchQuoteHistory)).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(30_000)
    expect(latestQuotesMock).toHaveBeenCalledTimes(2)
    expect(monitor.evaluationTime.value).toBe('2026-11-10T10:01:30.000Z')
    expect(monitor.lastAllSucceededAt.value).toBe('2026-11-10T10:01:30.000Z')
    monitor.stopAutoRefresh()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(latestQuotesMock).toHaveBeenCalledTimes(2)
    expect(vi.getTimerCount()).toBe(0)
  })

  // 前提：初回の履歴分析だけ未完了。操作：自動周期/手動更新/画面破棄→遅延応答。期待：重複なし、signal中断、破棄後データ反映なし。
  it('E-STATE-10：未完了リクエストを重複させず破棄後の応答も反映しない', async () => {
    const pending = createDeferred<ReturnType<typeof quoteHistoryFixture>>()
    vi.mocked(fetchQuoteHistory).mockReturnValueOnce(pending.promise)
    const monitor = useMarketMonitor()
    monitor.startAutoRefresh(); await flushPromises()
    await vi.advanceTimersByTimeAsync(30_000)
    await monitor.refreshMarketData()
    expect(vi.mocked(fetchQuoteHistory)).toHaveBeenCalledTimes(1)
    const signal = vi.mocked(fetchQuoteHistory).mock.calls[0]![0]!
    expect(signal.aborted).toBe(false)
    monitor.stopAutoRefresh()
    expect(signal.aborted).toBe(true)
    pending.resolve(quoteHistoryFixture()); await flushPromises()
    expect(monitor.quoteHistoryState.lastSucceededAt).toBeNull()
    expect(monitor.quoteHistoryState.items).toHaveLength(0)
    expect(monitor.isRefreshing.value).toBe(false)
  })

  // 前提：全成功後に分析のみ失敗。操作：2回の更新。期待：統計/範囲を保持し全体成功時刻は止める。他領域は更新。
  it('E-STATE-11：分析領域の部分失敗で前回統計と全体成功日時を保持する', async () => {
    const monitor = useMarketMonitor()
    await monitor.refreshMarketData()
    vi.setSystemTime(new Date('2026-11-10T10:02:00Z'))
    vi.mocked(fetchQuoteHistory).mockRejectedValueOnce(new MarketMonitorApiError('timeout'))
    await monitor.refreshMarketData()
    expect(monitor.quoteHistoryState.spreadStatistics?.observationCount).toBe(1)
    expect(monitor.quoteHistoryState.rangeEnd).toBe(quoteHistoryFixture().rangeEnd)
    expect(monitor.quoteHistoryState.lastSucceededAt).toBe('2026-11-10T10:01:00.000Z')
    expect(monitor.lastAllSucceededAt.value).toBe('2026-11-10T10:01:00.000Z')
    expect(monitor.latestQuotesState.lastSucceededAt).toBe('2026-11-10T10:02:00.000Z')
    expect(monitor.hasPartialFailure.value).toBe(true)
  })

})
