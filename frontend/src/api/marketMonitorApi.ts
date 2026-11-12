import { COLLECTION_RUN_STATUSES, type CollectionRunHistoryItem, type LatestQuoteItem, type QuoteHistoryResponse } from '../types/marketMonitor'

export const API_TIMEOUT_MILLISECONDS = 8_000
type ApiFailureKind = 'invalid-request' | 'unavailable' | 'server' | 'network' | 'timeout' | 'invalid-response'
const failureMessages: Record<ApiFailureKind, string> = {
  'invalid-request': '表示データの取得条件が正しくありません。',
  unavailable: '現在データを取得できません。時間をおいて表示更新してください。',
  server: 'データの取得中にエラーが発生しました。',
  network: 'サーバーへ接続できません。接続状態を確認して表示更新してください。',
  timeout: 'データの取得がタイムアウトしました。表示更新で再度お試しください。',
  'invalid-response': '取得したデータの形式を確認できませんでした。',
}

/** 固定の公開説明だけを保持する。サーバー本文・内部例外をそのまま表示しない。 */
export class MarketMonitorApiError extends Error {
  constructor(public readonly kind: ApiFailureKind) {
    super(failureMessages[kind])
    this.name = 'MarketMonitorApiError'
  }
}

/** 相対URLにより開発プロキシや将来の配信先から独立させ、GETだけを呼ぶ。 */
export async function fetchLatestQuotes(signal?: AbortSignal): Promise<LatestQuoteItem[]> {
  return fetchItems('/api/v1/quotes/latest', isLatestQuoteItem, signal)
}
export async function fetchCollectionRuns(signal?: AbortSignal): Promise<CollectionRunHistoryItem[]> {
  return fetchItems('/api/v1/collection-runs?limit=100', isCollectionRunHistoryItem, signal)
}

/** HTTP→JSON→実行時の型確認。TypeScriptの型指定だけで不正応答を正常扱いしない。 */
async function fetchItems<T>(url: string, isItem: (value: unknown) => value is T, signal?: AbortSignal): Promise<T[]> {
  const body = await fetchValidated(url, (value): value is { items: T[] } =>
    isRecord(value) && Array.isArray(value.items) && value.items.every(isItem), signal)
  return body.items
}

/** 価格推移と全期間統計を一緒に取得。既存の2 APIの応答型は変更しない。 */
export async function fetchQuoteHistory(signal?: AbortSignal): Promise<QuoteHistoryResponse> {
  return fetchValidated('/api/v1/quotes/history?exchange=COINBASE_EXCHANGE&symbol=BTC-USD', isQuoteHistoryResponse, signal)
}

async function fetchValidated<T>(url: string, validate: (value: unknown) => value is T, externalSignal?: AbortSignal): Promise<T> {
  const timeoutSignal = AbortSignal.timeout(API_TIMEOUT_MILLISECONDS)
  try {
    const response = await fetch(url, { method: 'GET', cache: 'no-store', signal: externalSignal ? AbortSignal.any([timeoutSignal, externalSignal]) : timeoutSignal })
    if (!response.ok) {
      throw new MarketMonitorApiError(response.status === 400 ? 'invalid-request'
        : response.status === 503 ? 'unavailable' : 'server')
    }
    let responseBody: unknown
    try { responseBody = await response.json() }
    catch (jsonError) {
      // 本文の読取り中にもタイムアウトし得る。JSONの解析失敗と区別する。
      if (timeoutSignal.aborted) throw jsonError
      throw new MarketMonitorApiError('invalid-response')
    }
    if (!validate(responseBody)) {
      throw new MarketMonitorApiError('invalid-response')
    }
    return responseBody
  } catch (requestError) {
    if (externalSignal?.aborted) throw new DOMException('Request cancelled', 'AbortError')
    if (requestError instanceof MarketMonitorApiError) throw requestError
    if (timeoutSignal.aborted || (requestError instanceof DOMException && requestError.name === 'TimeoutError')) {
      throw new MarketMonitorApiError('timeout')
    }
    throw new MarketMonitorApiError('network')
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}
/** 数値に変換せず表現形式を確認する。価格の業務検証はサーバーの責務。 */
function isDecimalString(value: unknown): value is string {
  return typeof value === 'string' && /^-?\d+(?:\.\d+)?$/.test(value)
}
function isIdentifier(value: unknown): value is string {
  return typeof value === 'string' && /^\d+$/.test(value)
}
function isUtcTimestamp(value: unknown): value is string {
  return typeof value === 'string'
    && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value)
    && Number.isFinite(Date.parse(value))
}
function isNullableTimestamp(value: unknown): value is string | null {
  return value === null || isUtcTimestamp(value)
}
function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === 'string'
}
function isLatestQuoteItem(value: unknown): value is LatestQuoteItem {
  return isRecord(value) && isIdentifier(value.id) && isNonEmptyString(value.exchange) && isNonEmptyString(value.symbol)
    && isDecimalString(value.bidPrice) && isDecimalString(value.bidSize)
    && isDecimalString(value.askPrice) && isDecimalString(value.askSize)
    && isUtcTimestamp(value.observedAt) && isNullableTimestamp(value.sourceAt)
}
function isCollectionRunHistoryItem(value: unknown): value is CollectionRunHistoryItem {
  return isRecord(value) && isIdentifier(value.id) && isNonEmptyString(value.exchange) && isNonEmptyString(value.symbol)
    && COLLECTION_RUN_STATUSES.some(status => status === value.status)
    && isUtcTimestamp(value.startedAt) && isNullableTimestamp(value.finishedAt)
    && isNullableTimestamp(value.interruptionDetectedAt)
    && isNullableString(value.resultCode) && isNullableString(value.resultDescription)
}


/** 空統計のnullと件数、期間、一覧の順序までAPI境界で確認する。DBの金額検証は移植しない。 */
function isQuoteHistoryResponse(value: unknown): value is QuoteHistoryResponse {
  if (!isRecord(value) || !isUtcTimestamp(value.rangeStart) || !isUtcTimestamp(value.rangeEnd)
      || Date.parse(value.rangeStart) >= Date.parse(value.rangeEnd) || typeof value.truncated !== 'boolean'
      || !Array.isArray(value.items) || value.items.length > 2000 || !isRecord(value.spreadStatistics)) return false
  const statistics = value.spreadStatistics
  if (!Number.isSafeInteger(statistics.observationCount) || (statistics.observationCount as number) < value.items.length) return false
  const statisticValues = [statistics.averageSpread, statistics.minimumSpread, statistics.maximumSpread]
  if (!(statistics.observationCount === 0 ? statisticValues.every(amount => amount === null)
    : statisticValues.every(isDecimalString))) return false
  if (value.truncated !== ((statistics.observationCount as number) > value.items.length)) return false
  let previousTimestamp = ''
  return value.items.every(item => {
    if (!isRecord(item) || !isIdentifier(item.id) || item.exchange !== 'COINBASE_EXCHANGE' || item.symbol !== 'BTC-USD'
        || !isUtcTimestamp(item.observedAt) || !isDecimalString(item.bidPrice) || !isDecimalString(item.askPrice)
        || !isDecimalString(item.spread)) return false
    const timestamp = utcTimestampSortKey(item.observedAt)
    if (timestamp < previousTimestamp || timestamp < utcTimestampSortKey(value.rangeStart as string) || timestamp >= utcTimestampSortKey(value.rangeEnd as string)) return false
    previousTimestamp = timestamp
    return true
  })
}

/** UTC固定形式をナノ秒桁で揃える。Date.parseのミリ秒切捨てで範囲末尾の正当な観測を拒否しない。 */
function utcTimestampSortKey(timestamp: string): string {
  const fraction = timestamp.includes('.') ? timestamp.slice(20, -1) : ''
  return `${timestamp.slice(0, 19)}.${fraction.padEnd(9, '0')}`
}
