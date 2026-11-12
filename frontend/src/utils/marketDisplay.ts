import Decimal from 'decimal.js'
import type { CollectionRunStatus } from '../types/marketMonitor'

export const STALE_OBSERVATION_MILLISECONDS = 3 * 60 * 1_000
const jstFormatter = new Intl.DateTimeFormat('ja-JP', {
  year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
  hourCycle: 'h23', timeZone: 'Asia/Tokyo',
})

/** APIのUTCを表示時だけJSTへ変換する。nullに架空の時刻を与えない。 */
export function formatJst(timestamp: string | null): string {
  return timestamp === null ? '—' : `${jstFormatter.format(new Date(timestamp))} JST`
}
/** 最終取得成功時点で判定。Date.nowやタイマーで勝手に判定を進めない。 */
export function isObservationStale(observedAt: string, evaluatedAt: string | null): boolean {
  return evaluatedAt !== null && Date.parse(evaluatedAt) - Date.parse(observedAt) >= STALE_OBSERVATION_MILLISECONDS
}
const statusLabels: Record<CollectionRunStatus, string> = {
  RUNNING: '実行中', SUCCESS: '成功', FAILED: '失敗', SKIPPED: 'スキップ', INTERRUPTED: '中断',
}
export function collectionStatusLabel(status: CollectionRunStatus): string { return statusLabels[status] }
export function exchangeLabel(exchange: string): string {
  return exchange === 'COINBASE_EXCHANGE' ? 'Coinbase Exchange' : exchange
}
/** BASE-QUOTEの表示単位。業務上の通貨ペア変換や価格計算はバックエンドに残す。 */
export function currencyUnits(symbol: string): { base: string; quote: string } {
  const [base, quote] = symbol.split('-')
  return { base: base || '—', quote: quote || '—' }
}

/** 小数文字列のまま桁区切りと末尾ゼロだけを整理する。丸めやNumber変換は行わない。 */
export function formatDecimal(value: string): string {
  const [integerPart = '0', fractionPart = ''] = value.split('.')
  const trimmedFraction = fractionPart.replace(/0+$/, '')
  return integerPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + (trimmedFraction ? `.${trimmedFraction}` : '')
}

/** 同一板のAsk−Bid。小数桁を揃えた整数で計算し、表示用差額にも浮動小数の誤差を混ぜない。
 * 手数料・数量・他市場を考慮する裁定利益計算ではない。
 */
export function calculateQuotedSpread(askPrice: string, bidPrice: string): string {
  const decimalPlaces = Math.max(askPrice.split('.')[1]?.length ?? 0, bidPrice.split('.')[1]?.length ?? 0)
  const toScaledInteger = (price: string): bigint => {
    const [integerPart = '0', fractionPart = ''] = price.replace(/^-/, '').split('.')
    const magnitude = BigInt(integerPart + fractionPart.padEnd(decimalPlaces, '0'))
    return price.startsWith('-') ? -magnitude : magnitude
  }
  const difference = toScaledInteger(askPrice) - toScaledInteger(bidPrice)
  const digits = (difference < 0n ? -difference : difference).toString().padStart(decimalPlaces + 1, '0')
  const decimalValue = decimalPlaces === 0 ? digits : `${digits.slice(0, -decimalPlaces)}.${digits.slice(-decimalPlaces)}`
  return `${difference < 0n ? '-' : ''}${decimalValue}`
}

/** 収集完了までの経過時間。中断検出は終了ではないため、finishedAtがない場合は推定しない。 */
export function formatCollectionDuration(startedAt: string, finishedAt: string | null): string {
  if (finishedAt === null) return '—'
  const elapsedMilliseconds = Date.parse(finishedAt) - Date.parse(startedAt)
  if (elapsedMilliseconds < 0) return '—'
  return `${(elapsedMilliseconds / 1_000).toFixed(3)} 秒`
}

/** API取得成功時点の経過時間。時計の不整合を「新鮮」と誤表示しない。 */
export function observationAgeLabel(observedAt: string, evaluatedAt: string | null): string {
  if (evaluatedAt === null) return '未判定'
  const elapsedMilliseconds = Date.parse(evaluatedAt) - Date.parse(observedAt)
  if (elapsedMilliseconds < 0) return '観測時刻が端末時刻より未来です'
  const elapsedSeconds = Math.floor(elapsedMilliseconds / 1_000)
  return elapsedSeconds < 60 ? `${elapsedSeconds}秒経過` : `${Math.floor(elapsedSeconds / 60)}分${elapsedSeconds % 60}秒経過`
}

/** 平均スプレッドの表示専用。API値・集計精度を変えず、小数4桁へ四捨五入する。
 * 正の微小値を0と見せないよう、0.0001未満は閾値表示にする。
 */
export function formatAverageSpread(value: string | null): string {
  if (value === null) return '—'
  try {
    const amount = new Decimal(value)
    if (!amount.isFinite() || amount.isNegative()) return '—'
    if (amount.greaterThan(0) && amount.lessThan('0.0001')) return '<0.0001'
    return formatDecimal(amount.toDecimalPlaces(4, Decimal.ROUND_HALF_UP).toFixed(4))
  } catch { return '—' }
}
