import { mount, enableAutoUnmount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PriceHistoryChart from './PriceHistoryChart.vue'
import { quoteHistoryFixture } from '../test/marketFixtures'
import type { QuoteHistoryState } from '../types/marketMonitor'

const chartSpies = vi.hoisted(() => ({ construct: vi.fn(), destroy: vi.fn() }))
vi.mock('chart.js', () => ({ Chart: class {
  static register() {}
  constructor(target: unknown, configuration: unknown) { chartSpies.construct(target,configuration) }
  destroy() { chartSpies.destroy() }
}, LineController:{}, LineElement:{}, PointElement:{}, LinearScale:{}, Tooltip:{} }))
enableAutoUnmount(afterEach)
beforeEach(() => vi.clearAllMocks())
function state(overrides: Partial<QuoteHistoryState> = {}): QuoteHistoryState {
  return { ...quoteHistoryFixture(), isLoading:false,errorMessage:null,lastSucceededAt:'2026-11-10T10:01:00Z', ...overrides }
}
/** 機能E / チャート部品結合。Vue・変換・書式は実体、Chart.js描画だけモック。npm.cmd test。
 * 実Canvasの線・レスポンシブ・タッチ操作は実ブラウザ/STで別確認する。
 */
describe('機能E / チャート部品', () => {
  // 前提：0件。操作：mount。期待：空表示とnull統計、架空のグラフを作らない。
  it('E-CHART-01：正常0件はグラフを作らず空表示する', () => {
    const wrapper=mount(PriceHistoryChart,{props:{state:state({items:[],spreadStatistics:{observationCount:0,averageSpread:null,minimumSpread:null,maximumSpread:null}})}})
    expect(wrapper.text()).toContain('価格データがありません')
    expect(chartSpies.construct).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('集計対象：0観測')
  })
  // 前提：1件。操作：mount→指標切替→unmount。期待：点・時間数値軸・描画破棄、統計はサーバー値。
  it('E-CHART-02：1件は点表示し、切替と破棄のライフサイクルを守る', async () => {
    const wrapper=mount(PriceHistoryChart,{props:{state:state()}})
    const configuration=chartSpies.construct.mock.calls[0]![1]
    expect(configuration.data.datasets[0].pointRadius).toBe(4)
    expect(configuration.options.scales.x.type).toBe('linear')
    const chartOptions={options:{scales:{x:{ticks:{maxTicksLimit:6}}}}}
    configuration.options.onResize(chartOptions,{width:300})
    expect(chartOptions.options.scales.x.ticks.maxTicksLimit).toBe(2)
    configuration.options.onResize(chartOptions,{width:900})
    expect(chartOptions.options.scales.x.ticks.maxTicksLimit).toBe(6)
    expect(configuration.data.datasets[0].data[0].exactValue).toBe('100.5')
    await wrapper.findAll('button')[1]!.trigger('click')
    expect(chartSpies.destroy).toHaveBeenCalledTimes(1)
    expect(chartSpies.construct.mock.calls[1]![1].data.datasets[0].data[0].exactValue).toBe('1')
    expect(wrapper.get('[title*="単純集計"]').attributes('title')).toContain('平均は時間加重ではありません')
    wrapper.unmount(); expect(chartSpies.destroy).toHaveBeenCalledTimes(2)
  })
  // 前提：長い欠測と小さい差額。操作：設定/Tooltipを検査。期待：3分閾値・直線・JST・元精度。
  it('E-CHART-03：欠測閾値とJST・元精度のツールチップを設定する', () => {
    mount(PriceHistoryChart,{props:{state:state()}})
    const configuration=chartSpies.construct.mock.calls[0]![1]
    expect(configuration.data.datasets[0].spanGaps).toBe(180_000)
    expect(configuration.data.datasets[0].tension).toBe(0)
    const point={observedAt:'2026-11-10T10:00:00Z',exactValue:'0.000000000001'}
    expect(configuration.options.plugins.tooltip.callbacks.title([{raw:point}])).toBe('2026/11/10 19:00:00 JST')
    expect(configuration.options.plugins.tooltip.callbacks.label({raw:point})).toContain('0.000000000001 USD')
  })
  // 前提：失敗/上限/無効点。操作：mount。期待：前回値を保持し警告・全期間統計を区別する。
  it('E-CHART-04：更新失敗・一覧上限・無効価格を明示する', () => {
    const wrapper=mount(PriceHistoryChart,{props:{state:state({errorMessage:'タイムアウト',truncated:true,
      items:[{...quoteHistoryFixture().items[0]!,bidPrice:'0'}]})}})
    expect(wrapper.text()).toContain('前回の表示を保持')
    expect(wrapper.text()).toContain('統計は検索期間内の全観測')
    expect(wrapper.text()).toContain('描画していません')
    expect(wrapper.findAll('[role="alert"]')).toHaveLength(2)
  })
  // 前提：サーバー平均は多桁、2観測は4分離れる。操作：描画。期待：平均の表示だけ丸め、元値と欠測表現を維持。
  it('E-CHART-05：平均を表示用に丸め欠測注記を必要時だけ表示する', async () => {
    const initial = state({ spreadStatistics: { observationCount: 1, averageSpread: '0.0488721804511278', minimumSpread: '0.01', maximumSpread: '1' } })
    const wrapper = mount(PriceHistoryChart, { props: { state: initial } })
    expect(wrapper.get('.spread-statistics .summary-value').text()).toBe('0.0489')
    expect(wrapper.get('.spread-statistics .summary-value').attributes('title')).toContain('0.0488721804511278')
    expect(wrapper.text()).not.toContain('3分超の欠測区間は線で接続しません')
    expect(initial.spreadStatistics?.averageSpread).toBe('0.0488721804511278')
    await wrapper.setProps({ state: { ...initial, items: [initial.items[0]!, { ...initial.items[0]!, id: '2', observedAt: '2026-11-10T10:04:00Z' }] } })
    expect(wrapper.text()).toContain('3分超の欠測区間は線で接続しません')
  })

})
