<script setup lang="ts">
// 公開URLが確定してから設定する。未設定/不正値ならリンクを作らず、架空の導線を表示しない。
const props = defineProps<{ repositoryUrl?: string }>()
const configuredUrl = props.repositoryUrl ?? import.meta.env.VITE_GITHUB_REPOSITORY_URL ?? ''
const repositoryUrl = /^https:\/\/github\.com\/[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+\/?$/.test(configuredUrl)
  ? configuredUrl.replace(/\/$/, '') : null
</script>
<template>
  <details class="about-app">
    <summary>このアプリについて</summary>
    <div class="about-app-body">
      <p>Coinbase Exchange / BTC-USDの公開市場データを定期収集し、保存した価格の検索・推移表示・スプレッド集計を行います。実際の売買・資金移動は行いません。</p>
      <p>今後の構想：複数市場の比較、手数料を考慮した裁定機会の検出（未実装）。</p>
      <p class="technology-summary">Java / Spring Boot · MySQL / JPA / Flyway · Vue 3 / TypeScript · JUnit / Testcontainers · Docker Compose</p>
      <nav v-if="repositoryUrl" aria-label="プロジェクト資料" class="flex gap-4">
        <a :href="repositoryUrl" target="_blank" rel="noopener noreferrer">GitHub</a>
        <a :href="`${repositoryUrl}#readme`" target="_blank" rel="noopener noreferrer">README</a>
      </nav>
    </div>
  </details>
</template>
