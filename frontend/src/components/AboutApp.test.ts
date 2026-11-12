import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AboutApp from './AboutApp.vue'

/** 機能E / 表示部品UT。VueとURL判定は実体、外部通信なし。npm.cmd test。
 * GitHub自体への到達性や公開状態は保証しない。設定例は試験入力であり公開URLではない。
 */
afterEach(() => vi.unstubAllEnvs())
describe('機能E / アプリ概要とGitHub導線', () => {
  // 前提：未設定。操作：表示。期待：短い概要のみ、架空リンクや未実装CIを表示しない。
  it('E-ABOUT-01：未設定ならGitHubリンクを作らない', () => {
    vi.stubEnv('VITE_GITHUB_REPOSITORY_URL', '')
    const wrapper = mount(AboutApp)
    expect(wrapper.find('a').exists()).toBe(false)
    expect(wrapper.get('details').attributes('open')).toBeUndefined()
    expect(wrapper.text()).toContain('定期収集')
    expect(wrapper.text()).toContain('JUnit / Testcontainers')
    expect(wrapper.text()).not.toContain('GitHub Actions')
    wrapper.unmount()
  })
  // 前提：明示設定されたHTTPSのリポジトリURL。操作：表示。期待：GitHubと既定ブランチに依存しないREADME導線。
  it('E-ABOUT-02：設定したリポジトリとREADMEへリンクする', () => {
    const wrapper = mount(AboutApp, { props: { repositoryUrl: 'https://github.com/example/market-monitor/' } })
    expect(wrapper.findAll('a').map(link => link.attributes('href'))).toEqual([
      'https://github.com/example/market-monitor', 'https://github.com/example/market-monitor#readme',
    ])
    expect(wrapper.get('a').attributes('rel')).toBe('noopener noreferrer')
    wrapper.unmount()
  })
  // 前提：不正なURL。操作：表示。期待：リンクを生成せずコード/任意サイトへ誘導しない。
  it.each(['javascript:alert(1)', 'https://other.example/project'])('E-ABOUT-03：不正設定%sを表示しない', repositoryUrl => {
    const wrapper = mount(AboutApp, { props: { repositoryUrl } })
    expect(wrapper.find('a').exists()).toBe(false)
    wrapper.unmount()
  })
})
