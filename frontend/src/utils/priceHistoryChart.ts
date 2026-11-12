import type { QuoteHistoryItem } from '../types/marketMonitor'
import { calculateQuoteMetrics } from './marketCalculations'
export type ChartMetric = 'mid' | 'spread'
export interface HistoryChartPoint { x: number; y: number | null; exactValue: string | null; observedAt: string }
/** 保存点だけを変換。描画座標のnumberは表示計算/統計へ逆流させない。 */
export function toHistoryChartPoints(items: readonly QuoteHistoryItem[], metric: ChartMetric): HistoryChartPoint[] {
  return items.map(item => {
    const metrics = calculateQuoteMetrics(item.bidPrice, item.askPrice)
    const exactValue = metrics === null ? null : metric === 'mid' ? metrics.midPrice : item.spread
    const numericValue = exactValue === null ? NaN : Number(exactValue)
    return { x: Date.parse(item.observedAt), y: Number.isFinite(numericValue) ? numericValue : null,
      exactValue, observedAt: item.observedAt }
  })
}
