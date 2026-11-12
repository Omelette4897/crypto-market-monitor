<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { CollectionRunHistoryItem, MarketDataState } from '../types/marketMonitor'
import { collectionStatusLabel, formatCollectionDuration, formatJst } from '../utils/marketDisplay'
/** 収集結果と画面取得エラーを区別して表示する。propsは書き換えない。 */
const props = defineProps<{ state: Readonly<MarketDataState<CollectionRunHistoryItem>> }>()
const HISTORY_PAGE_SIZE = 10
const isExpanded = ref(false)
const isHistoryOpen = ref(false)
const currentPage = ref(1)
const pageCount = computed(() => Math.max(1, Math.ceil(props.state.items.length / HISTORY_PAGE_SIZE)))
const pageStartIndex = computed(() => (currentPage.value - 1) * HISTORY_PAGE_SIZE)
const visibleCollectionRuns = computed(() => isExpanded.value ? props.state.items.slice(pageStartIndex.value, pageStartIndex.value + HISTORY_PAGE_SIZE) : props.state.items.slice(0, 5))
// 自動更新でも閲覧中のページを維持する。正常0件/件数減少だけ有効範囲へ戻す。
watch(pageCount, count => { currentPage.value = Math.min(currentPage.value, count) })
</script>

<template>
  <section class="collection-information" aria-labelledby="history-title" :aria-busy="state.isLoading">
    <div class="collection-information-heading">
      <div><h2 id="history-title">データ収集について</h2><p class="section-note">バックエンドで定期収集した保存データを表示しています。</p></div>
      <span v-if="state.isLoading" class="loading-label" role="status">読み込み中…</span>
      <span v-else-if="state.errorMessage" class="text-xs font-semibold text-rose-800">履歴取得エラー</span>
      <button class="history-toggle text-link" type="button" :aria-expanded="isHistoryOpen" aria-controls="collection-history-body" @click="isHistoryOpen = !isHistoryOpen">{{ isHistoryOpen ? '収集実行履歴を閉じる' : '収集実行履歴を開く' }}</button>
    </div>
    <p v-if="state.errorMessage" class="error-notice" role="alert">
      {{ state.lastSucceededAt === null ? '取得できませんでした。' : '更新に失敗しました。前回の表示を保持しています。' }}
      {{ state.errorMessage }}
    </p>
    <!-- 稼働サマリーは履歴の補助情報として内包し、閉じた状態でも異常を確認できる。 -->
    <slot name="summary" />
    <!-- 開閉してもページ状態は残す。自動更新ではこの状態を操作しない。 -->
    <div v-if="isHistoryOpen" id="collection-history-body" class="mt-5">
    <p v-if="state.lastSucceededAt === null && !state.errorMessage" class="empty-notice">
      {{ state.isLoading ? '収集実行履歴を読み込んでいます。' : 'まだデータを取得していません。' }}
    </p>
    <p v-if="state.lastSucceededAt !== null && state.items.length === 0" class="empty-notice">収集履歴がありません。</p>
    <div v-if="state.items.length" class="history-scroll" tabindex="0" role="region" aria-label="収集実行履歴の一覧。狭い画面では横スクロールできます">
      <table>
        <caption class="sr-only">収集実行履歴。開始日時の新しい順、最大100件。</caption>
        <thead><tr><th scope="col">実行ID</th><th scope="col">開始日時</th><th scope="col">終了日時</th><th scope="col">処理時間</th><th scope="col">状態</th><th scope="col">理由</th></tr></thead>
        <tbody>
          <tr v-for="collectionRun in visibleCollectionRuns" :key="collectionRun.id">
            <td class="font-mono text-xs">{{ collectionRun.id }}</td>
            <td class="whitespace-nowrap">{{ formatJst(collectionRun.startedAt) }}</td>
            <td class="whitespace-nowrap">{{ formatJst(collectionRun.finishedAt) }}</td>
            <td class="whitespace-nowrap font-mono">{{ formatCollectionDuration(collectionRun.startedAt, collectionRun.finishedAt) }}</td>
            <td><span class="status-badge" :data-status="collectionRun.status">{{ collectionStatusLabel(collectionRun.status) }}</span></td>
            <td class="reason-cell">
              <p>{{ collectionRun.resultDescription ?? '—' }}</p>
              <p v-if="collectionRun.resultCode" class="mt-1 font-mono text-xs text-slate-500">{{ collectionRun.resultCode }}</p>
              <p v-if="collectionRun.interruptionDetectedAt" class="mt-2 text-xs text-slate-600">中断検出日時：{{ formatJst(collectionRun.interruptionDetectedAt) }}</p>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <button v-if="state.items.length > 5" class="page-button mt-4" type="button" :aria-expanded="isExpanded" @click="isExpanded = !isExpanded; currentPage = 1">{{ isExpanded ? '最新5件に戻る' : '履歴を詳しく見る' }}</button>
    <nav v-if="state.items.length && isExpanded" class="history-pagination" aria-label="収集履歴のページ送り">
      <p aria-live="polite">取得済み{{ state.items.length }}件中 {{ pageStartIndex + 1 }}〜{{ Math.min(pageStartIndex + HISTORY_PAGE_SIZE, state.items.length) }}件 · {{ currentPage }} / {{ pageCount }}ページ</p>
      <div class="flex gap-2">
        <button type="button" class="page-button" :disabled="currentPage === 1" @click="currentPage--">前の10件</button>
        <button type="button" class="page-button" :disabled="currentPage === pageCount" @click="currentPage++">次の10件</button>
      </div>
    </nav>
    </div>
    <p v-if="state.errorMessage && state.lastSucceededAt" class="updated-at">前回取得成功：{{ formatJst(state.lastSucceededAt) }}</p>
  </section>
</template>
