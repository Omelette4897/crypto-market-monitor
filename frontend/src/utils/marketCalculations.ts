import Decimal from 'decimal.js'

// 他用途のDecimal設定を変更せず、この表示計算だけ有効数字50桁とする。
const MarketDecimal = Decimal.clone({ precision: 50, rounding: Decimal.ROUND_HALF_UP })
export interface QuoteMetrics { midPrice: string; spread: string; spreadRate: string }
/** 数値の演算はここに集約。書式やChart.jsのnumber座標と混在させない。 */
export function calculateQuoteMetrics(bidPrice: string, askPrice: string): QuoteMetrics | null {
  try {
    const bid = new MarketDecimal(bidPrice), ask = new MarketDecimal(askPrice)
    if (!bid.isFinite() || !ask.isFinite() || bid.lessThanOrEqualTo(0) || ask.lessThanOrEqualTo(0) || ask.lessThan(bid)) return null
    const mid = bid.plus(ask).dividedBy(2), spread = ask.minus(bid)
    return { midPrice: mid.toFixed(), spread: spread.toFixed(), spreadRate: spread.dividedBy(mid).times(100).toFixed() }
  } catch { return null }
}
/** 率だけ表示時に丸める。正の非ゼロ値を0%と誤表示しない。 */
export function formatSpreadRate(rate: string): string {
  const value = new MarketDecimal(rate)
  if (value.greaterThan(0) && value.lessThan('0.000001')) return '0.000001%未満'
  return `${value.toFixed(6)}%`
}
