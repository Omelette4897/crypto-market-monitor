<script setup lang="ts">
import { computed } from 'vue'
import type { CollectionRunHistoryItem, LatestQuoteItem, MarketDataState } from '../types/marketMonitor'
import { collectionStatusLabel, isObservationStale, observationAgeLabel } from '../utils/marketDisplay'
const props = defineProps<{ historyState: Readonly<MarketDataState<CollectionRunHistoryItem>>;
  latestState: Readonly<MarketDataState<LatestQuoteItem>>; evaluationTime: string; hasAnalysisError?: boolean }>()
const latestRun = computed(() => props.historyState.items[0])
const latestQuote = computed(() => props.latestState.items[0])
const completedCount = computed(() => props.historyState.items.filter(run => run.status !== 'RUNNING').length)
const successfulCount = computed(() => props.historyState.items.filter(run => run.status === 'SUCCESS').length)
const successRate = computed(() => completedCount.value ? `${(successfulCount.value / completedCount.value * 100).toFixed(1)}%` : '—')
const freshnessWarning = computed(() => !!latestQuote.value && (isObservationStale(latestQuote.value.observedAt, props.evaluationTime)
  || Date.parse(latestQuote.value.observedAt) > Date.parse(props.evaluationTime)))
const hasWarning = computed(() => !!props.historyState.errorMessage || !!props.latestState.errorMessage || !!props.hasAnalysisError
  || freshnessWarning.value || ['FAILED','INTERRUPTED','SKIPPED'].includes(latestRun.value?.status ?? '')
  || (props.latestState.lastSucceededAt !== null && !latestQuote.value))
/** 成功率の定義は維持する。画面取得成功や過去のSUCCESSだけで収集正常とは断定しない。 */
</script>
<template>
  <section class="collection-health-summary" :data-warning="hasWarning" :data-failed="latestRun?.status === 'FAILED'" aria-labelledby="collection-status-title">
    <h3 id="collection-status-title" class="sr-only">データ収集・稼働状況</h3>
    <div class="collection-health-metrics">
      <p>直近収集：<span v-if="latestRun" class="status-badge" :data-status="latestRun.status">{{ collectionStatusLabel(latestRun.status) }}</span><span v-else>{{ historyState.lastSucceededAt ? '履歴なし' : '未確認' }}</span></p>
      <p :title="`取得済み${historyState.items.length}件のうち成功${successfulCount}件 / 終了済み${completedCount}件・RUNNING除外${historyState.items.length - completedCount}件`">成功率 <strong>{{ successRate }}</strong> <span class="text-xs">（終了済み{{ completedCount }}件）</span></p>

      <p>最終観測：{{ latestQuote ? observationAgeLabel(latestQuote.observedAt, evaluationTime) : '未観測' }}</p>

    </div>
    <p v-if="latestRun?.status === 'FAILED' || latestRun?.status === 'INTERRUPTED'" class="summary-failure">{{ latestRun.resultDescription ?? '収集履歴で理由を確認してください。' }}</p>
    <p v-if="freshnessWarning" class="summary-warning">{{ latestQuote && Date.parse(latestQuote.observedAt) > Date.parse(evaluationTime) ? '観測時刻と端末時計の不整合を確認してください。' : 'データが3分以上古くなっています。' }}</p>
    <p v-if="latestState.lastSucceededAt && !latestQuote" class="summary-warning">最新市場データが存在しません。</p>
    <p v-if="historyState.errorMessage || latestState.errorMessage || hasAnalysisError" class="summary-warning">画面データの取得エラーがあります。取得に失敗した領域は前回値・成功日時を保持しています。各領域のエラーを確認してください。</p>
  </section>
</template>
