<script setup lang="ts">
import { computed } from 'vue'
import { calculateQuoteMetrics, formatSpreadRate } from '../utils/marketCalculations'
import type { LatestQuoteItem, MarketDataState } from '../types/marketMonitor'
import { currencyUnits, exchangeLabel, formatDecimal, formatJst, isObservationStale, observationAgeLabel } from '../utils/marketDisplay'
const props = defineProps<{ market: { exchange: string; symbol: string }; state: Readonly<MarketDataState<LatestQuoteItem>>; evaluationTime: string }>()
const selectedQuote = computed(() => props.state.items.find(item => item.exchange === props.market.exchange && item.symbol === props.market.symbol))
const quoteMetrics = computed(() => selectedQuote.value ? calculateQuoteMetrics(selectedQuote.value.bidPrice, selectedQuote.value.askPrice) : null)
/** 数値と観測時刻を担当。分析チャートのslotは最新価格の成功/失敗に関係なく維持する。 */
</script>
<template>
  <section class="panel" aria-labelledby="market-detail-title">
    <div class="section-heading"><div><p class="eyebrow">MARKET DETAIL</p><h2 id="market-detail-title">選択市場の詳細</h2>
      <p class="mt-2 text-sm text-slate-600">{{ exchangeLabel(market.exchange) }} / {{ market.symbol }}</p></div>
      <span v-if="state.isLoading" class="loading-label" role="status">読み込み中…</span></div>
    <div :aria-busy="state.isLoading">
      <p v-if="state.errorMessage" class="error-notice" role="alert">{{ state.lastSucceededAt ? '更新に失敗しました。前回の表示を保持しています。' : '取得できませんでした。' }} {{ state.errorMessage }}</p>
      <p v-if="!selectedQuote && !state.errorMessage" class="empty-notice">{{ state.lastSucceededAt ? '保存価格がありません。' : '最新の保存価格を読み込んでいます。' }}</p>
      <article v-if="selectedQuote" class="detail-quote">
        <div class="detail-summary"><p><abbr title="買気配と売気配の中間価格">Mid Price (USD)</abbr> <span class="mid-price">{{ quoteMetrics ? formatDecimal(quoteMetrics.midPrice) : '—' }}</span></p>
          <p><abbr title="同じ市場の売気配と買気配の差額">Bid/Ask Spread</abbr> <span class="spread-value">{{ quoteMetrics ? formatDecimal(quoteMetrics.spread) + ' USD' : '—' }}</span>
            <span class="ml-2 font-mono">{{ quoteMetrics ? formatSpreadRate(quoteMetrics.spreadRate) : '' }}</span></p></div>
        <p v-if="!quoteMetrics" class="error-notice" role="alert">価格の整合性を確認できないため、Mid・スプレッドを計算できません。</p>
        <dl class="grid gap-4 sm:grid-cols-2">
          <div class="quote-side border-l-2 border-teal-600"><dt><abbr title="現在の最良買気配（買い注文の最高価格）">Best Bid</abbr> ({{ currencyUnits(market.symbol).quote }})</dt><dd class="quote-price" :title="selectedQuote.bidPrice">{{ formatDecimal(selectedQuote.bidPrice) }}</dd>
</div>
          <div class="quote-side border-l-2 border-sky-600"><dt><abbr title="現在の最良売気配（売り注文の最低価格）">Best Ask</abbr> ({{ currencyUnits(market.symbol).quote }})</dt><dd class="quote-price" :title="selectedQuote.askPrice">{{ formatDecimal(selectedQuote.askPrice) }}</dd>
</div>
        </dl>
        <details class="quantity-details"><summary>板数量を見る</summary><p>Bid数量：<span class="font-mono">{{ formatDecimal(selectedQuote.bidSize) }}</span> {{ currencyUnits(market.symbol).base }} · Ask数量：<span class="font-mono">{{ formatDecimal(selectedQuote.askSize) }}</span> {{ currencyUnits(market.symbol).base }}</p></details>
        <div class="detail-observation"><p>最終観測日時：<time class="observation-time" :datetime="selectedQuote.observedAt">{{ formatJst(selectedQuote.observedAt) }}</time></p>
          <p class="overview-freshness" :data-warning="isObservationStale(selectedQuote.observedAt, evaluationTime) || Date.parse(selectedQuote.observedAt) > Date.parse(evaluationTime)">{{ observationAgeLabel(selectedQuote.observedAt, evaluationTime) }}</p></div>
        <p v-if="isObservationStale(selectedQuote.observedAt, evaluationTime)" class="stale-notice" role="note">観測から時間が経過しています。3分以上経過した保存価格です。</p>
      </article>
      <p v-if="state.errorMessage && state.lastSucceededAt" class="updated-at">前回取得成功：{{ formatJst(state.lastSucceededAt) }}</p>
    </div>
    <div class="detail-analysis"><slot /></div>
  </section>
</template>
