import { describe, expect, it } from 'vitest'
import { calculateQuoteMetrics, formatSpreadRate } from './marketCalculations'
import { toHistoryChartPoints } from './priceHistoryChart'
import { quoteHistoryFixture } from '../test/marketFixtures'
/** 機能E / UT：十進計算・表示丸め・チャート変換の実体。HTTP/DB/Canvasは使用しない。実行：npm.cmd test。 */
describe('機能E / 市場データの計算と描画変換', () => {
  // 前提：12桁の差と大きい金額。操作：Mid/Spread/率。期待：浮動小数誤差なし、Midの13桁も維持。
  it('E-CALC-01：安全整数を超える価格から正確なMidと差額を求める', () => {
    const metrics = calculateQuoteMetrics('999999999999999999.123456789012', '999999999999999999.123456789013')!
    expect(metrics.midPrice).toBe('999999999999999999.1234567890125')
    expect(metrics.spread).toBe('0.000000000001')
    expect(formatSpreadRate(metrics.spreadRate)).toBe('0.000001%未満')
  })
  // 前提：無効価格。操作：計算。期待：0除算せずnull、NaN/Infinity/負値/逆転を正常に見せない。
  it.each([['0','1'], ['1','0'], ['-1','1'], ['2','1'], ['NaN','2'], ['1','Infinity'], ['bad','2']])(
    'E-CALC-02：Bid=%s Ask=%sは計算不可', (bid, ask) => expect(calculateQuoteMetrics(bid, ask)).toBeNull())
  // 前提：丸め境界と差0。操作：率表示。期待：HALF_UPの6桁、非ゼロ微小値を0にしない。
  it.each([['0','0.000000%'], ['0.1234565','0.123457%'], ['0.0000001','0.000001%未満']])(
    'E-CALC-03：率%sを%sと表示', (rate, expected) => expect(formatSpreadRate(rate)).toBe(expected))
  // 前提：実保存点2件、時間差4分。操作：チャート変換。期待：補間点なし、Midは計算しSpreadはサーバー値を表示。
  it('E-CALC-04：描画座標と元精度値を分け、保存点を増やさない', () => {
    const items = [quoteHistoryFixture().items[0]!, { ...quoteHistoryFixture().items[0]!, id: '2', observedAt: '2026-11-10T10:04:00Z', spread: '1.000000000001' }]
    const points = toHistoryChartPoints(items, 'mid')
    expect(points).toHaveLength(2); expect(points[0]!.exactValue).toBe('100.5')
    expect(points[1]!.x - points[0]!.x).toBe(240_000)
    expect(toHistoryChartPoints(items, 'spread')[1]!.exactValue).toBe('1.000000000001')
    expect(toHistoryChartPoints([], 'mid')).toEqual([])
    expect(toHistoryChartPoints([{...items[0]!, bidPrice:'0'}], 'mid')[0]!.y).toBeNull()
  })
})
