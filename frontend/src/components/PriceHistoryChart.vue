<script setup lang="ts">
import { computed, nextTick, onActivated, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { Chart, LineController, LineElement, PointElement, LinearScale, Tooltip } from 'chart.js'
import type { QuoteHistoryState } from '../types/marketMonitor'
import { formatAverageSpread, formatDecimal, formatJst } from '../utils/marketDisplay'
import { toHistoryChartPoints, type ChartMetric, type HistoryChartPoint } from '../utils/priceHistoryChart'

Chart.register(LineController, LineElement, PointElement, LinearScale, Tooltip)
const props = defineProps<{ state: Readonly<QuoteHistoryState> }>()
const metric = ref<ChartMetric>('mid')
const canvasElement = ref<HTMLCanvasElement | null>(null)
let priceChart: Chart<'line', HistoryChartPoint[]> | null = null
const points = computed(() => toHistoryChartPoints(props.state.items, metric.value))
// 注記の表示だけを判定する。系列・補間・集計値は変更しない。
const hasLongGaps = computed(() => props.state.items.some((item, index, items) =>
  index > 0 && Date.parse(item.observedAt) - Date.parse(items[index - 1]!.observedAt) > 180_000))
const hasInvalidPoints = computed(() => points.value.some(point => point.y === null))
const jstAxisFormatter = new Intl.DateTimeFormat('ja-JP', { timeZone: 'Asia/Tokyo', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
/** 実Canvasはこの部品だけで管理。アンマウントや再描画前に旧インスタンスを破棄する。 */
function renderChart(): void {
  priceChart?.destroy(); priceChart = null
  if (!canvasElement.value || !points.value.length) return
  priceChart = new Chart(canvasElement.value, {
    type: 'line',
    data: { datasets: [{ label: metric.value === 'mid' ? 'Mid Price (USD)' : 'スプレッド (USD)',
      data: points.value, borderColor: metric.value === 'mid' ? '#0f766e' : '#0284c7',
      borderWidth: 2, pointRadius: points.value.length === 1 ? 4 : 0, pointHitRadius: 12,
      tension: 0, spanGaps: 180_000 }] },
    options: { responsive: true, maintainAspectRatio: false, animation: false, parsing: false,
      // モバイルでは日時ラベルの重なりを避ける。データ点や期間は間引かない。
      onResize: (chart, size) => {
        const xScale = chart.options.scales?.x
        if (xScale) xScale.ticks = { ...xScale.ticks, maxTicksLimit: size.width < 500 ? 2 : 6 }
      },
      interaction: { mode: 'nearest', intersect: false },
      scales: {
        x: { type: 'linear', min: props.state.rangeStart ? Date.parse(props.state.rangeStart) : undefined,
          max: props.state.rangeEnd ? Date.parse(props.state.rangeEnd) : undefined,
          ticks: { maxTicksLimit: 6, callback: value => jstAxisFormatter.format(new Date(Number(value))) }, title: { display: true, text: '観測日時 (JST)' } },
        y: { title: { display: true, text: 'USD' }, ticks: { maxTicksLimit: 5 } },
      },
      plugins: { tooltip: { callbacks: {
        title: entries => { const point = entries[0]?.raw as HistoryChartPoint | undefined; return point ? formatJst(point.observedAt) : '' },
        label: entry => { const point = entry.raw as HistoryChartPoint; return `${metric.value === 'mid' ? 'Mid' : 'Spread'}: ${point.exactValue === null ? '—' : formatDecimal(point.exactValue)} USD` },
      } } },
    },
  })
}
watch([points, () => props.state.rangeStart, () => props.state.rangeEnd], renderChart, { flush: 'post' })
onMounted(renderChart)
// KeepAliveから復帰するとCanvasがDOMへ戻る。再生成せず、復帰後の実寸で再描画する。
onActivated(async () => {
  await nextTick()
  priceChart?.resize()
  priceChart?.update('none')
})
onBeforeUnmount(() => { priceChart?.destroy(); priceChart = null })
</script>
<template>
  <section class="market-analysis" aria-labelledby="price-history-title" :aria-busy="state.isLoading">
    <div class="section-heading"><div><p class="eyebrow">24-HOUR MARKET ANALYSIS</p><h3 id="price-history-title">価格・スプレッド推移</h3></div>
      <div class="flex gap-2" role="group" aria-label="表示する指標">
        <button class="page-button" type="button" :aria-pressed="metric === 'mid'" @click="metric = 'mid'">Mid Price</button>
        <button class="page-button" type="button" :aria-pressed="metric === 'spread'" @click="metric = 'spread'">スプレッド</button>
      </div>
    </div>
    <p v-if="state.errorMessage" class="error-notice" role="alert">{{ state.lastSucceededAt ? '更新に失敗しました。前回の表示を保持しています。' : '価格推移を取得できませんでした。' }} {{ state.errorMessage }}</p>
    <p v-if="state.isLoading" class="loading-label" role="status">価格推移を読み込み中…</p>
    <p v-if="state.lastSucceededAt && !state.items.length" class="empty-notice">直近24時間の価格データがありません。</p>
    <p v-if="state.truncated" class="stale-notice" role="note">グラフは最新2,000件のみです。スプレッド統計は検索期間内の全観測を対象にしています。</p>
    <p v-if="hasInvalidPoints" class="error-notice" role="alert">価格の整合性を確認できない観測点は描画していません。</p>
    <div v-show="state.items.length" class="price-chart-container"><canvas ref="canvasElement" role="img" :aria-label="`${metric === 'mid' ? 'Mid Price' : 'スプレッド'}の24時間推移。横軸はJST、縦軸はUSD。`">チャートの代替情報は検索期間と統計カードを参照してください。</canvas></div>
    <p v-if="state.rangeStart && state.rangeEnd" class="metric-caption" :title="`${formatJst(state.rangeStart)} 〜 ${formatJst(state.rangeEnd)}。保存した観測のみ・3分超の欠測区間は接続しません。`">直近24時間（JST）<span v-if="hasLongGaps"> · 3分超の欠測区間は線で接続しません。</span></p>
    <div v-if="state.spreadStatistics" class="spread-statistics">
      <div><p class="metric-label">平均スプレッド (USD)</p><p class="summary-value" :title="state.spreadStatistics.averageSpread === null ? undefined : `元の平均値：${state.spreadStatistics.averageSpread} USD`">{{ formatAverageSpread(state.spreadStatistics.averageSpread) }}</p></div>
      <div><p class="metric-label">最大スプレッド (USD)</p><p class="summary-value">{{ state.spreadStatistics.maximumSpread === null ? '—' : formatDecimal(state.spreadStatistics.maximumSpread) }}</p></div>
      <div><p class="metric-label">最小スプレッド (USD)</p><p class="summary-value">{{ state.spreadStatistics.minimumSpread === null ? '—' : formatDecimal(state.spreadStatistics.minimumSpread) }}</p></div>
    </div>
    <p v-if="state.spreadStatistics" class="metric-caption" title="保存済みの期間内全観測を対象とする単純集計。平均は時間加重ではありません。">集計対象：{{ state.spreadStatistics.observationCount }}観測</p>
    <p v-if="state.errorMessage && state.lastSucceededAt" class="updated-at">前回取得成功：{{ formatJst(state.lastSucceededAt) }}</p>
  </section>
</template>
