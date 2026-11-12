<script setup lang="ts">
import { computed } from 'vue'
import type { LatestQuoteItem, MarketDataState } from '../types/marketMonitor'
import { calculateQuoteMetrics, formatSpreadRate } from '../utils/marketCalculations'
import { exchangeLabel, formatDecimal } from '../utils/marketDisplay'
const props = defineProps<{ markets: readonly { exchange: string; symbol: string }[];
  expansionCandidates: readonly { exchange: string; exchangeName: string; symbol: string }[];
  selectedMarket: { exchange: string; symbol: string }; state: Readonly<MarketDataState<LatestQuoteItem>>; evaluationTime: string }>()
const emit = defineEmits<{ selectMarket: [market: { exchange: string; symbol: string }] }>()
/** 対応市場を軸に取得値を対応付ける。0件や通信失敗でも市場数を変えず、価格を捏造しない。 */
const marketRows = computed(() => props.markets.map(market => {
  const quote = props.state.items.find(item => item.exchange === market.exchange && item.symbol === market.symbol)
  return { market, quote, metrics: quote ? calculateQuoteMetrics(quote.bidPrice, quote.askPrice) : null }
}))
</script>
<template>
  <section class="panel" aria-labelledby="market-overview-title" :aria-busy="state.isLoading">
    <div class="section-heading"><div><p class="eyebrow">MARKET OVERVIEW</p><h2 id="market-overview-title">市場一覧</h2></div></div>
    <p v-if="state.errorMessage" class="overview-warning">{{ state.lastSucceededAt ? '価格の取得に失敗しました。前回値を表示しています。' : '価格を取得できませんでした。' }}</p>
    <table class="market-overview-table">
      <caption class="sr-only">収集対象と未実装の拡張候補。Mid Price・Bid/Ask Spread</caption>
      <thead><tr><th scope="col">市場</th><th scope="col"><abbr title="Bid（買気配）とAsk（売気配）の中間価格">Mid Price (USD)</abbr></th><th scope="col"><abbr title="同じ市場のAskとBidの差額。取引所間の裁定利益ではありません">Bid/Ask Spread</abbr></th><th scope="col">取得状態</th></tr></thead>
      <tbody><tr v-for="row in marketRows" :key="`${row.market.exchange}:${row.market.symbol}`" @click="emit('selectMarket', row.market)" :aria-selected="row.market.exchange === selectedMarket.exchange && row.market.symbol === selectedMarket.symbol">
        <th scope="row"><span class="block text-xs font-normal text-slate-500">{{ exchangeLabel(row.market.exchange) }}</span><button type="button" class="market-select-button" :aria-label="`${exchangeLabel(row.market.exchange)} / ${row.market.symbol}を選択`" :aria-pressed="row.market.exchange === selectedMarket.exchange && row.market.symbol === selectedMarket.symbol" @click.stop="emit('selectMarket', row.market)">{{ row.market.symbol }}</button>
          <span class="selected-market-label" :class="{ invisible: row.market.exchange !== selectedMarket.exchange || row.market.symbol !== selectedMarket.symbol }" :aria-hidden="row.market.exchange !== selectedMarket.exchange || row.market.symbol !== selectedMarket.symbol">選択中</span></th>
        <td><span class="mobile-column-label">Mid Price (USD)</span><span class="overview-price">{{ row.metrics ? formatDecimal(row.metrics.midPrice) : '—' }}</span><span v-if="!row.quote" class="block text-xs text-slate-500">{{ state.errorMessage ? '取得失敗' : state.lastSucceededAt ? '未観測' : '取得中' }}</span><span v-if="row.quote && !row.metrics" class="block text-xs text-rose-800">価格不整合</span></td>
        <td><span class="mobile-column-label">Bid/Ask Spread</span><span class="block font-mono font-semibold">{{ row.metrics ? formatDecimal(row.metrics.spread) + ' USD' : '—' }}</span>
          <span class="text-xs text-slate-600">{{ row.metrics ? formatSpreadRate(row.metrics.spreadRate) : '' }}</span></td>
        <td><span class="mobile-column-label">取得状態</span><span class="status-badge">{{ state.errorMessage ? '取得エラー' : row.quote ? '保存済み' : state.isLoading ? '取得中' : '未観測' }}</span></td>
      </tr>
      <!-- 候補の選択は未収集案内の表示だけ。実データやAPI取得対象へ混ぜない。 -->
      <tr v-for="candidate in expansionCandidates" :key="`candidate:${candidate.exchange}:${candidate.symbol}`" class="expansion-candidate" @click="emit('selectMarket', candidate)" :aria-selected="candidate.exchange === selectedMarket.exchange && candidate.symbol === selectedMarket.symbol">
        <th scope="row"><span class="block text-xs font-normal">{{ candidate.exchangeName }}</span><button type="button" class="market-select-button" :aria-label="`${candidate.exchangeName} / ${candidate.symbol}を選択`" :aria-pressed="candidate.exchange === selectedMarket.exchange && candidate.symbol === selectedMarket.symbol" @click.stop="emit('selectMarket', candidate)">{{ candidate.symbol }}</button><span class="selected-market-label" :class="{ invisible: candidate.exchange !== selectedMarket.exchange || candidate.symbol !== selectedMarket.symbol }" :aria-hidden="candidate.exchange !== selectedMarket.exchange || candidate.symbol !== selectedMarket.symbol">選択中</span></th>
        <td><span class="mobile-column-label">Mid Price (USD)</span>—</td>
        <td><span class="mobile-column-label">Bid/Ask Spread</span>—</td>
        <td><span class="mobile-column-label">取得状態</span><span class="status-badge">拡張候補</span></td>
      </tr></tbody>
    </table>
  </section>
</template>
