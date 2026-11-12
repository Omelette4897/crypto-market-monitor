/** 公開APIのDTO。ID・価格・数量は精度を保つため文字列で扱う。 */
export interface LatestQuoteItem {
  id: string
  exchange: string
  symbol: string
  bidPrice: string
  bidSize: string
  askPrice: string
  askSize: string
  observedAt: string
  sourceAt: string | null
}

export const COLLECTION_RUN_STATUSES = ['RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED', 'INTERRUPTED'] as const
export type CollectionRunStatus = typeof COLLECTION_RUN_STATUSES[number]

/** 収集自体の結果。FAILEDの行を参照できた場合も、画面の通信失敗ではない。 */
export interface CollectionRunHistoryItem {
  id: string
  exchange: string
  symbol: string
  status: CollectionRunStatus
  startedAt: string
  finishedAt: string | null
  interruptionDetectedAt: string | null
  resultCode: string | null
  resultDescription: string | null
}

/** 正常0件と一度も取得成功していない状態を、成功日時で区別する。 */
export interface MarketDataState<T> {
  items: readonly T[]
  isLoading: boolean
  errorMessage: string | null
  lastSucceededAt: string | null
}


/** 履歴のspread・統計はサーバーが算出し、Vue側で別の平均へ置き換えない。 */
export interface QuoteHistoryItem {
  id: string
  exchange: string
  symbol: string
  observedAt: string
  bidPrice: string
  askPrice: string
  spread: string
}
export interface SpreadStatistics {
  observationCount: number
  averageSpread: string | null
  minimumSpread: string | null
  maximumSpread: string | null
}
export interface QuoteHistoryResponse {
  rangeStart: string
  rangeEnd: string
  truncated: boolean
  items: QuoteHistoryItem[]
  spreadStatistics: SpreadStatistics
}
export interface QuoteHistoryState extends MarketDataState<QuoteHistoryItem> {
  rangeStart: string | null
  rangeEnd: string | null
  truncated: boolean
  spreadStatistics: SpreadStatistics | null
}
