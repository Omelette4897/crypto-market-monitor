import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// テストではHTTPサーバーやTailwindを起動せず、Vueと模擬DOMの動作を検証する。
export default defineConfig({
  plugins: [vue()],
  test: { environment: 'jsdom', clearMocks: true, restoreMocks: true },
})
