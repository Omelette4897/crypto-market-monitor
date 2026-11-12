<script setup lang="ts">
import { KeepAlive, computed, ref, onMounted, onBeforeUnmount } from 'vue'
import AboutApp from './components/AboutApp.vue'
import MarketDetailPanel from './components/MarketDetailPanel.vue'
import MarketOverviewPanel from './components/MarketOverviewPanel.vue'
import CollectionRunHistoryPanel from './components/CollectionRunHistoryPanel.vue'
import PriceHistoryChart from './components/PriceHistoryChart.vue'
import CollectionStatusPanel from './components/CollectionStatusPanel.vue'
import { useMarketMonitor } from './composables/useMarketMonitor'

const { latestQuotesState, collectionRunsState, quoteHistoryState, evaluationTime, hasPartialFailure,
  isRefreshing, refreshMarketData, startAutoRefresh, stopAutoRefresh } = useMarketMonitor()
// 現在の収集対象の表示定義。APIが0件/失敗でも対応市場数を0と表示しない。
// 画面の選択と実際の収集対象は分離し、候補を選択してもAPIを呼ばない。
const supportedMarkets = [{ exchange: 'COINBASE_EXCHANGE', symbol: 'BTC-USD' }] as const
// 表示専用の拡張候補。収集対象・API検索条件・対応市場数には含めない。
// 対応開始時は収集/APIを実装した上でsupportedMarketsへ移し、この候補から削除する。
const expansionCandidates = [
  { exchange: 'COINBASE_EXCHANGE', exchangeName: 'Coinbase Exchange', symbol: 'ETH-USD' },
  { exchange: 'KRAKEN', exchangeName: 'Kraken', symbol: 'BTC-USD' },
] as const
const selectedMarket = ref<{ exchange: string; symbol: string }>({ ...supportedMarkets[0] })
const isSelectedMarketSupported = computed(() => supportedMarkets.some(market =>
  market.exchange === selectedMarket.value.exchange && market.symbol === selectedMarket.value.symbol))
const selectedMarketLabel = computed(() => {
  const candidate = expansionCandidates.find(market => market.exchange === selectedMarket.value.exchange && market.symbol === selectedMarket.value.symbol)
  return `${candidate?.exchangeName ?? 'Coinbase Exchange'} / ${selectedMarket.value.symbol}`
})
/** 選択は表示対象の変更だけ。実収集・取得対象の設定は書き換えない。 */
function selectMarket(market: { exchange: string; symbol: string }): void {
  if (![...supportedMarkets, ...expansionCandidates].some(known => known.exchange === market.exchange && known.symbol === market.symbol)) return
  if (selectedMarket.value.exchange === market.exchange && selectedMarket.value.symbol === market.symbol) return
  selectedMarket.value = { exchange: market.exchange, symbol: market.symbol }
}
const selectedLatestState = computed(() => ({ ...latestQuotesState, items: latestQuotesState.items.filter(
  item => item.exchange === selectedMarket.value.exchange && item.symbol === selectedMarket.value.symbol) }))
const selectedCollectionState = computed(() => ({ ...collectionRunsState, items: collectionRunsState.items.filter(
  item => item.exchange === selectedMarket.value.exchange && item.symbol === selectedMarket.value.symbol) }))
// 初回取得の入口は親だけに置き、子からの二重取得を防ぐ。
onMounted(startAutoRefresh)
onBeforeUnmount(stopAutoRefresh)
</script>

<template>
  <main class="mx-auto max-w-6xl px-4 py-4 sm:px-6 sm:py-6">
    <header class="mb-4 flex flex-col justify-between gap-3 border-b border-slate-200 pb-4 lg:flex-row lg:items-center">
      <div>
        <p class="eyebrow mb-1">CRYPTO MARKET MONITOR</p>
        <h1 class="text-xl font-semibold tracking-tight sm:text-2xl">暗号資産 市場モニター</h1>
        <p class="mt-1 max-w-2xl text-sm leading-6 text-slate-600">暗号資産取引所の市場データを定期収集・蓄積し、価格やスプレッドの推移を表示するWebアプリです。</p>
        <p class="mt-1 text-xs font-medium text-slate-600">現在の収集対象：Coinbase Exchange / BTC-USD（{{ supportedMarkets.length }}市場）</p>
      </div>
      <div class="flex shrink-0 flex-col items-start gap-2 sm:items-end">
      <p class="text-xs text-slate-500">{{ isRefreshing ? '取得中' : '30秒ごとに自動更新' }}</p>
      <p v-if="hasPartialFailure" class="text-xs font-semibold text-rose-800" role="status">一部のデータ取得に失敗しています</p>
      <button class="refresh-button" type="button" :disabled="isRefreshing" @click="refreshMarketData">
        <span aria-hidden="true">↻</span> {{ isRefreshing ? '更新中…' : '表示更新' }}
      </button>
      </div>
    </header>
    <div class="grid gap-4 dashboard-workspace">
      <MarketOverviewPanel :markets="supportedMarkets" :expansion-candidates="expansionCandidates" :selected-market="selectedMarket" :state="latestQuotesState" :evaluation-time="evaluationTime" @select-market="selectMarket" />
      <KeepAlive>
      <MarketDetailPanel v-if="isSelectedMarketSupported" :market="selectedMarket" :state="selectedLatestState" :evaluation-time="evaluationTime">
        <PriceHistoryChart :state="quoteHistoryState" />
      </MarketDetailPanel>
      </KeepAlive>
      <section v-if="!isSelectedMarketSupported" class="panel" aria-labelledby="market-detail-title">
        <div class="section-heading"><div><p class="eyebrow">MARKET DETAIL</p><h2 id="market-detail-title">選択市場の詳細</h2></div></div>
        <p class="text-sm font-medium">{{ selectedMarketLabel }}</p>
        <p class="empty-notice mt-3" role="status">この市場は拡張候補です。まだデータを収集していないため、価格・チャート・統計は表示できません。</p>
      </section>
      <KeepAlive>
      <CollectionRunHistoryPanel v-if="isSelectedMarketSupported" :state="selectedCollectionState">
        <template #summary>
          <CollectionStatusPanel :history-state="selectedCollectionState" :latest-state="selectedLatestState" :has-analysis-error="!!quoteHistoryState.errorMessage" :evaluation-time="evaluationTime" />
        </template>
      </CollectionRunHistoryPanel>
      </KeepAlive>
      <section v-if="!isSelectedMarketSupported" class="collection-information" aria-labelledby="history-title">
        <div class="collection-information-heading"><h2 id="history-title">データ収集について</h2></div>
        <p class="section-note mt-2" role="status">{{ selectedMarketLabel }}は未収集のため、収集状況・履歴はありません。</p>
      </section>
      <AboutApp />
    </div>

  </main>
</template>
