import { computed, readonly, ref, shallowReactive } from 'vue'
import { fetchCollectionRuns, fetchLatestQuotes, fetchQuoteHistory, MarketMonitorApiError } from '../api/marketMonitorApi'
import type { CollectionRunHistoryItem, LatestQuoteItem, MarketDataState, QuoteHistoryState } from '../types/marketMonitor'

export const AUTO_REFRESH_MILLISECONDS = 30_000
/** 3領域の独立状態・重複防止・更新時計を管理する。外部収集やDB書込みを開始しない。 */
export function useMarketMonitor() {
  const latestQuotesState = shallowReactive<MarketDataState<LatestQuoteItem>>(initialState())
  const collectionRunsState = shallowReactive<MarketDataState<CollectionRunHistoryItem>>(initialState())
  const quoteHistoryState = shallowReactive<QuoteHistoryState>({ ...initialState(), rangeStart: null,
    rangeEnd: null, truncated: false, spreadStatistics: null })
  const evaluationTime = ref(new Date().toISOString())
  const lastAllSucceededAt = ref<string | null>(null)
  const isRefreshing = computed(() => latestQuotesState.isLoading || collectionRunsState.isLoading || quoteHistoryState.isLoading)
  const hasPartialFailure = computed(() => [latestQuotesState, collectionRunsState, quoteHistoryState].some(state => state.errorMessage !== null))
  let refreshTimer: ReturnType<typeof setInterval> | undefined
  let clockTimer: ReturnType<typeof setInterval> | undefined
  let activeRequestController: AbortController | null = null
  let disposed = false

  /** 失敗を領域内に閉じ、全体成功日時だけは3領域すべて成功した場合に進める。 */
  async function refreshMarketData(): Promise<void> {
    if (disposed || isRefreshing.value) return
    activeRequestController = new AbortController()
    const signal = activeRequestController.signal
    const results = await Promise.all([
      refreshRegion(latestQuotesState, async () => ({ items: await fetchLatestQuotes(signal) })),
      refreshRegion(collectionRunsState, async () => ({ items: await fetchCollectionRuns(signal) })),
      refreshRegion(quoteHistoryState, () => fetchQuoteHistory(signal)),
    ])
    if (!disposed && results.every(Boolean)) lastAllSucceededAt.value = new Date().toISOString()
    activeRequestController = null
  }

  /** API取得は30秒、鮮度の表示時計は1秒。実行中なら次の取得機会をスキップする。 */
  function startAutoRefresh(): void {
    if (disposed || refreshTimer !== undefined) return
    void refreshMarketData()
    refreshTimer = setInterval(() => { void refreshMarketData() }, AUTO_REFRESH_MILLISECONDS)
    clockTimer = setInterval(() => { evaluationTime.value = new Date().toISOString() }, 1_000)
  }
  /** 画面破棄時に両タイマーと通信を解除し、遅れて届く応答で状態を書き換えない。 */
  function stopAutoRefresh(): void {
    disposed = true
    clearInterval(refreshTimer); clearInterval(clockTimer)
    activeRequestController?.abort()
    refreshTimer = undefined; clockTimer = undefined
  }

  async function refreshRegion<T extends { items: readonly unknown[]; isLoading: boolean; errorMessage: string | null; lastSucceededAt: string | null }>(
    regionState: T, fetchRegion: () => Promise<Partial<T>>): Promise<boolean> {
    regionState.isLoading = true; regionState.errorMessage = null
    try {
      const received = await fetchRegion()
      if (disposed) return false
      Object.assign(regionState, received, { lastSucceededAt: new Date().toISOString() })
      return true
    } catch (requestError) {
      if (!disposed) regionState.errorMessage = requestError instanceof MarketMonitorApiError ? requestError.message
        : 'データを取得できませんでした。表示更新で再度お試しください。'
      return false
    } finally { regionState.isLoading = false }
  }
  return { latestQuotesState: readonly(latestQuotesState), collectionRunsState: readonly(collectionRunsState),
    quoteHistoryState: readonly(quoteHistoryState), evaluationTime: readonly(evaluationTime),
    lastAllSucceededAt: readonly(lastAllSucceededAt), hasPartialFailure, isRefreshing, refreshMarketData, startAutoRefresh, stopAutoRefresh }
}
function initialState<T>(): MarketDataState<T> {
  return { items: [], isLoading: false, errorMessage: null, lastSucceededAt: null }
}
