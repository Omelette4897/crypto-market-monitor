import { mount, flushPromises, enableAutoUnmount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'
import { fetchCollectionRuns, fetchLatestQuotes, fetchQuoteHistory, MarketMonitorApiError } from './api/marketMonitorApi'
import { collectionRunFixture, createDeferred, latestQuoteFixture, quoteHistoryFixture } from './test/marketFixtures'
import type { CollectionRunStatus } from './types/marketMonitor'

vi.mock('./api/marketMonitorApi', async importOriginal => ({
  ...await importOriginal<typeof import('./api/marketMonitorApi')>(),
  fetchLatestQuotes: vi.fn(), fetchCollectionRuns: vi.fn(), fetchQuoteHistory: vi.fn(),
}))
vi.mock('chart.js', () => ({
  Chart: Object.assign(vi.fn(function () { return { destroy: vi.fn(), resize: vi.fn(), update: vi.fn() } }), { register: vi.fn() }),
  LineController: {}, LineElement: {}, PointElement: {}, LinearScale: {}, Tooltip: {},
}))
enableAutoUnmount(afterEach)
/** 既存の履歴観点は利用者が開いた状態で確認する。初期折りたたみはE-VIEW-15で独立検証。 */
async function openHistory(wrapper: VueWrapper) {
  const toggle=wrapper.get('.history-toggle')
  if(toggle.attributes('aria-expanded') === 'false') await toggle.trigger('click')
}

/**
 * 機能E / Vue画面結合：App・実Composable・実Panel・実表示補助を結合し、API関数とCanvas描画ライブラリだけモック。
 * 実行：npm.cmd test。jsdomによるDOM確認であり、CSSレイアウト/実ブラウザ/実Boot・DBのSTではない。
 * 日時固定、ケース後にunmount・時計/モックを復元する。HTML全体のsnapshotではなく挙動をassertする。
 */
describe('機能E / Vue画面の結合確認', () => {
  const latestQuotesMock = vi.mocked(fetchLatestQuotes)
  const collectionRunsMock = vi.mocked(fetchCollectionRuns)
  beforeEach(() => {
    vi.mocked(fetchQuoteHistory).mockResolvedValue(quoteHistoryFixture())
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-11-10T10:01:00Z'))
    latestQuotesMock.mockResolvedValue([latestQuoteFixture()])
    collectionRunsMock.mockResolvedValue([collectionRunFixture()])
  })
  afterEach(() => { vi.useRealTimers(); vi.resetAllMocks() })

  // 前提：両API正常。操作：mount。期待：各1回取得、精度維持・単位・JST・ID・状態を表示。
  it('E-VIEW-01：初回表示で価格と履歴を取得し、型/精度を維持して表示する', async () => {
    const wrapper = mount(App)
    await flushPromises(); await openHistory(wrapper)
    expect(latestQuotesMock).toHaveBeenCalledTimes(1)
    expect(collectionRunsMock).toHaveBeenCalledTimes(1)
    const text = wrapper.text()
    expect(text).toContain('999,999,999,999,999,999.123456789012')
    expect(text).toContain('999,999,999,999,999,999.123456789013')
    expect(text).toContain('0.000000000001')
    expect(text).toContain('9223372036854775806')
    expect(text).toContain('Coinbase Exchange')
    expect(text).toContain('(USD)')
    expect(wrapper.get('.quantity-details').text()).toContain('BTC')
    expect(text).toContain('2026/11/10 19:00:00 JST')
    expect(text).not.toContain('表示更新日時')
    expect(text).not.toContain('画面データ最終取得')
    expect(wrapper.find('[data-status="SUCCESS"]').text()).toBe('成功')
  })

  // 前提：価格だけ取得済み・履歴未完了。操作：mount→履歴解決。期待：先に価格表示、両完了までボタン無効。
  it('E-VIEW-02：領域を独立表示し、更新中はボタンを無効にする', async () => {
    const pendingHistory = createDeferred<ReturnType<typeof collectionRunFixture>[]>()
    collectionRunsMock.mockReturnValueOnce(pendingHistory.promise)
    const wrapper = mount(App)
    await flushPromises(); await openHistory(wrapper)
    expect(wrapper.text()).toContain('999,999,999,999,999,999.123456789012')
    expect(wrapper.text()).toContain('収集実行履歴を読み込んでいます')
    expect(wrapper.get('.refresh-button').attributes()).toHaveProperty('disabled')
    pendingHistory.resolve([])
    await flushPromises(); await openHistory(wrapper)
    expect(wrapper.get('.refresh-button').attributes()).not.toHaveProperty('disabled')
  })

  // 前提：両API正常0件。操作：mount。期待：空表示で価格0や通信エラーに置換しない。
  it('E-VIEW-03：正常0件の表示を通信失敗と区別する', async () => {
    latestQuotesMock.mockResolvedValue([]); collectionRunsMock.mockResolvedValue([])
    const wrapper = mount(App)
    await flushPromises(); await openHistory(wrapper)
    expect(wrapper.text()).toContain('保存価格がありません')
    expect(wrapper.text()).toContain('収集履歴がありません')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.find('.quote-price').exists()).toBe(false)
  })

  // 前提：片側が初回失敗。操作：mount。期待：失敗だけ警告、成功側データを維持、失敗側に正常空表示を出さない。
  it.each(['quotes', 'history'] as const)('E-VIEW-04：%sの初回失敗でも他方を表示する', async failedRegion => {
    if (failedRegion === 'quotes') latestQuotesMock.mockRejectedValue(new MarketMonitorApiError('unavailable'))
    else collectionRunsMock.mockRejectedValue(new MarketMonitorApiError('network'))
    const wrapper = mount(App)
    await flushPromises(); await openHistory(wrapper)
    expect(wrapper.findAll('[role="alert"]')).toHaveLength(1)
    expect(wrapper.text()).toContain('取得できませんでした')
    if (failedRegion === 'quotes') {
      expect(wrapper.text()).toContain('9223372036854775806')
      expect(wrapper.text()).not.toContain('保存価格がありません')
    } else {
      expect(wrapper.text()).toContain('999,999,999,999,999,999.123456789012')
      expect(wrapper.text()).not.toContain('収集履歴がありません')
    }
  })

  // 前提：正常表示後、更新で片側失敗。操作：クリック。期待：成功側更新、失敗側は前回値/日時と警告。
  it('E-VIEW-05：更新失敗時は前回値を保持し、正常0件ではクリアする', async () => {
    const wrapper = mount(App)
    await flushPromises(); await openHistory(wrapper)
    vi.setSystemTime(new Date('2026-11-10T10:02:00Z'))
    latestQuotesMock.mockRejectedValueOnce(new MarketMonitorApiError('timeout'))
    collectionRunsMock.mockResolvedValueOnce([])
    await wrapper.get('.refresh-button').trigger('click')
    await flushPromises(); await openHistory(wrapper)
    const quotesPanel = wrapper.get('section[aria-labelledby="market-detail-title"]')
    const historyPanel = wrapper.get('section[aria-labelledby="history-title"]')
    expect(quotesPanel.text()).toContain('999,999,999,999,999,999.123456789012')
    expect(quotesPanel.text()).toContain('前回の表示を保持')
    expect(quotesPanel.text()).toContain('19:01:00 JST')
    expect(historyPanel.text()).toContain('収集履歴がありません')
    expect(historyPanel.text()).not.toContain('前回取得成功：')
    latestQuotesMock.mockResolvedValueOnce([])
    await wrapper.get('.refresh-button').trigger('click'); await flushPromises(); await openHistory(wrapper)
    expect(quotesPanel.find('[role="alert"]').exists()).toBe(false)
    expect(quotesPanel.text()).toContain('保存価格がありません')
    expect(quotesPanel.text()).not.toContain('999,999,999,999,999,999.123456789012')
  })

  // 前提：3分前ちょうどの観測。操作：mount。期待：価格を残して注意表示、観測日時もそのまま。
  it('E-VIEW-06：3分以上の観測は消さずに注意を表示する', async () => {
    latestQuotesMock.mockResolvedValue([latestQuoteFixture({ observedAt: '2026-11-10T09:58:00Z' })])
    const wrapper = mount(App)
    await flushPromises(); await openHistory(wrapper)
    expect(wrapper.get('[role="note"]').text()).toContain('観測から時間が経過')
    expect(wrapper.text()).toContain('999,999,999,999,999,999.123456789012')
    expect(wrapper.text()).toContain('2026/11/10 18:58:00 JST')
  })

  // 前提：全業務状態・終了null・中断検出あり。操作：mount。期待：5ラベル、FAILEDでも画面取得成功、中断と終了の区別。
  it('E-VIEW-07：収集状態と中断検出日時を正しく表示する', async () => {
    const statuses: CollectionRunStatus[] = ['RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED', 'INTERRUPTED']
    collectionRunsMock.mockResolvedValue(statuses.map((status, index) => collectionRunFixture({
      id: String(index + 1), status, finishedAt: status === 'RUNNING' || status === 'INTERRUPTED' ? null : '2026-11-10T10:00:01Z',
      interruptionDetectedAt: status === 'INTERRUPTED' ? '2026-11-10T10:00:30Z' : null,
      resultCode: status === 'SUCCESS' || status === 'RUNNING' ? null : status,
      resultDescription: status === 'FAILED' ? '外部APIがタイムアウトしました' : null,
    })))
    const wrapper = mount(App)
    await flushPromises(); await openHistory(wrapper)
    expect(wrapper.findAll('.history-scroll tbody .status-badge').map(badge => badge.text())).toEqual(['実行中', '成功', '失敗', 'スキップ', '中断'])
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    const interruptedRow = wrapper.get('.history-scroll tbody [data-status="INTERRUPTED"]').element.closest('tr')!
    expect(interruptedRow.cells[2]!.textContent).toBe('—')
    expect(interruptedRow.textContent).toContain('中断検出日時：2026/11/10 19:00:30 JST')
  })

  // 前提：100件と長い理由・HTML風文字列。操作：mount。期待：10件表示とページ送り、文字列として安全に表示しタグを実行しない。
  it('E-VIEW-08：100件を10件ずつ表示し、長い理由やHTMLを文字として安全に表示する', async () => {
    const reason = '<img src=x onerror="alert(1)">' + '長い理由'.repeat(60)
    collectionRunsMock.mockResolvedValue(Array.from({ length: 100 }, (_, index) => collectionRunFixture({
      id: String(100 - index), resultDescription: reason,
    })))
    const wrapper = mount(App)
    await flushPromises(); await openHistory(wrapper)
    expect(wrapper.findAll('.history-scroll tbody tr')).toHaveLength(5)
    await wrapper.findAll('button').find(button => button.text() === '履歴を詳しく見る')!.trigger('click')
    expect(wrapper.findAll('.history-scroll tbody tr')).toHaveLength(10)
    expect(wrapper.find('.history-scroll tbody tr').text()).toContain(reason)
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('.history-scroll tbody tr').text()).toContain('100')
    expect(wrapper.text()).toContain('取得済み100件中 1〜10件')
    const nextPageButton = wrapper.findAll('button').find(button => button.text() === '次の10件')!
    for (let page = 1; page < 10; page++) await nextPageButton.trigger('click')
    expect(wrapper.text()).toContain('取得済み100件中 91〜100件')
    expect(wrapper.find('.history-scroll tbody tr').text()).toContain('10')
    expect(nextPageButton.attributes()).toHaveProperty('disabled')
    expect(latestQuotesMock).toHaveBeenCalledTimes(1)
    expect(collectionRunsMock).toHaveBeenCalledTimes(1)
  })

  // 前提：想定外市場も含む応答。操作：表示。期待：対応対象の1市場だけ表示し、別市場を選択したように見せない。
  it('E-VIEW-09：対応市場だけを一覧と詳細へ表示する', async () => {
    latestQuotesMock.mockResolvedValue([latestQuoteFixture(), latestQuoteFixture({ id: '2', symbol: 'ETH-USDT', exchange: 'OTHER' })])
    const wrapper = mount(App)
    await flushPromises(); await openHistory(wrapper)
    expect(wrapper.findAll('.market-overview-table tbody tr:not(.expansion-candidate)')).toHaveLength(1)
    expect(wrapper.text()).not.toContain('ETH-USDT')
    expect(wrapper.text()).toContain('現在の収集対象：Coinbase Exchange / BTC-USD（1市場）')
    expect(wrapper.text()).toContain('選択中')
  })

  // 前提：12件取得後に2ページ目。操作：更新失敗→正常3件。期待：失敗時はページ保持、成功時は先頭へ戻りボタン境界も更新。
  it('E-VIEW-10：履歴の最終ページ・失敗時保持・成功時先頭復帰を扱う', async () => {
    collectionRunsMock.mockResolvedValueOnce(Array.from({ length: 12 }, (_, index) => collectionRunFixture({ id: String(12 - index) })))
    const wrapper = mount(App)
    await flushPromises(); await openHistory(wrapper)
    await wrapper.findAll('button').find(button => button.text() === '履歴を詳しく見る')!.trigger('click')
    const pageButton = (label: string) => wrapper.findAll('button').find(button => button.text() === label)!
    expect(pageButton('前の10件').attributes()).toHaveProperty('disabled')
    await pageButton('次の10件').trigger('click')
    expect(wrapper.findAll('.history-scroll tbody tr')).toHaveLength(2)
    expect(wrapper.text()).toContain('取得済み12件中 11〜12件')
    collectionRunsMock.mockRejectedValueOnce(new MarketMonitorApiError('network'))
    await wrapper.get('.refresh-button').trigger('click'); await flushPromises(); await openHistory(wrapper)
    expect(wrapper.text()).toContain('取得済み12件中 11〜12件')
    expect(wrapper.get('section[aria-labelledby="history-title"]').text()).toContain('前回の表示を保持')
    collectionRunsMock.mockResolvedValueOnce([collectionRunFixture(), collectionRunFixture({ id: '2' }), collectionRunFixture({ id: '3' })])
    await wrapper.get('.refresh-button').trigger('click'); await flushPromises(); await openHistory(wrapper)
    expect(wrapper.text()).toContain('取得済み3件中 1〜3件')
    expect(pageButton('前の10件').attributes()).toHaveProperty('disabled')
    expect(pageButton('次の10件').attributes()).toHaveProperty('disabled')
  })

  // 前提：小数12桁の差とFAILEDの最新履歴。操作：初回表示。期待：スプレッド/処理時間/経過/失敗理由を明示し通信失敗とは区別。
  it('E-VIEW-11：スプレッド・経過時間・直近の収集失敗を目立つ領域へ表示する', async () => {
    collectionRunsMock.mockResolvedValueOnce([collectionRunFixture({ status: 'FAILED', resultDescription: '外部APIがタイムアウトしました' })])
    const wrapper = mount(App)
    await flushPromises(); await openHistory(wrapper)
    expect(wrapper.get('.spread-value').text()).toBe('0.000000000001 USD')
    expect(wrapper.get('.observation-time').text()).toContain('2026/11/10 19:00:00 JST')
    expect(wrapper.get('.detail-observation .overview-freshness').text()).toContain('59秒経過')
    expect(wrapper.get('section[aria-labelledby="collection-status-title"]').text()).toContain('失敗')
    expect(wrapper.get('.history-scroll').text()).toContain('1.000 秒')
    expect(wrapper.get('.summary-failure').text()).toContain('タイムアウト')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })


  // 前提：成功1/失敗1/スキップ1/中断1/実行中1。操作：表示。期待：終了済み4件が分母、画面は初期5件・統計表示。
  it('E-VIEW-12：終了済み履歴の成功率と分析統計を表示する', async () => {
    collectionRunsMock.mockResolvedValueOnce((['SUCCESS','FAILED','SKIPPED','INTERRUPTED','RUNNING'] as const).map((status,index)=>collectionRunFixture({id:String(index+1),status})))
    const wrapper=mount(App); await flushPromises(); await openHistory(wrapper)
    const health=wrapper.get('section[aria-labelledby="collection-status-title"]')
    expect(health.text()).toContain('25.0%')
    expect(health.text()).toContain('終了済み4件')
    expect(health.get('p[title]').attributes('title')).toContain('RUNNING除外1件')
    expect(wrapper.findAll('.history-scroll tbody tr')).toHaveLength(5)
    expect(wrapper.get('.mid-price').text()).toBe('999,999,999,999,999,999.1234567890125')
    expect(wrapper.get('.spread-statistics').text()).toContain('平均スプレッド')
  })

  // 前提：期限直前の観測。操作：時計2秒進める。期待：追加通信なしで鮮度注意。unmountでタイマー解除。
  it('E-VIEW-13：通信失敗を待たず画面の時計で鮮度警告を進める', async () => {
    vi.useFakeTimers()
    latestQuotesMock.mockResolvedValueOnce([latestQuoteFixture({observedAt:'2026-11-10T09:58:01Z'})])
    const wrapper=mount(App); await flushPromises(); await openHistory(wrapper)
    expect(wrapper.find('[role="note"]').exists()).toBe(false)
    await vi.advanceTimersByTimeAsync(2_000)
    expect(wrapper.get('[role="note"]').text()).toContain('3分以上')
    expect(latestQuotesMock).toHaveBeenCalledTimes(1)
    wrapper.unmount(); expect(vi.getTimerCount()).toBe(0)
  })

  // 前提：履歴分析APIだけ初回失敗。操作：mount。期待：価格・履歴は表示し、全体成功時刻を捏造しない。
  it('E-VIEW-14：分析APIの失敗でも既存の価格と履歴を表示する', async () => {
    vi.mocked(fetchQuoteHistory).mockRejectedValueOnce(new MarketMonitorApiError('unavailable'))
    const wrapper=mount(App); await flushPromises(); await openHistory(wrapper)
    expect(wrapper.text()).toContain('一部のデータ取得に失敗')
    expect(wrapper.text()).not.toContain('画面データ最終取得')
    expect(wrapper.findAll('.history-scroll tbody tr')).toHaveLength(1)
    expect(wrapper.find('.mid-price').exists()).toBe(true)
  })


  // 前提：初回正常。操作：mountのみ。期待：日本語タイトルと対応市場を明示。稼働状況は閉じた履歴内でも見え、開くと5件、詳細は10件。
  it('E-VIEW-15：市場分析の目的と1市場を明示し履歴は初期状態で閉じる', async () => {
    collectionRunsMock.mockResolvedValueOnce(Array.from({length:20},(_,index)=>collectionRunFixture({id:String(index+1)})))
    const wrapper=mount(App); await flushPromises()
    expect(wrapper.get('h1').text()).toBe('暗号資産 市場モニター')
    expect(wrapper.findAll('.dashboard-workspace > section').map(section => section.attributes('aria-labelledby'))).toEqual(['market-overview-title', 'market-detail-title', 'history-title'])
    expect(wrapper.get('section[aria-labelledby="history-title"] section[aria-labelledby="collection-status-title"]').isVisible()).toBe(true)
    expect(wrapper.text()).toContain('市場データを定期収集・蓄積し')
    expect(wrapper.text()).toContain('価格やスプレッドの推移を表示するWebアプリ')
    expect(wrapper.text()).toContain('現在の収集対象：Coinbase Exchange / BTC-USD（1市場）')
    expect(wrapper.get('.history-toggle').attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('#collection-history-body').exists()).toBe(false)
    expect(wrapper.findAll('.history-scroll tbody tr')).toHaveLength(0)
    expect(wrapper.get('.market-overview-table .overview-price').text()).toBe(wrapper.get('.detail-summary .mid-price').text())
    const overview=wrapper.get('.market-overview-table')
    expect(overview.text()).toContain('0.000000000001 USD')
    expect(wrapper.get('.detail-quote .spread-value').text()).toBe('0.000000000001 USD')
    await openHistory(wrapper)
    expect(wrapper.findAll('.history-scroll tbody tr')).toHaveLength(5)
    await wrapper.findAll('button').find(button=>button.text()==='履歴を詳しく見る')!.trigger('click')
    expect(wrapper.findAll('.history-scroll tbody tr')).toHaveLength(10)
  })

  // 前提：最新価格0件または通信失敗。操作：初回表示。期待：対応市場数は1、候補とは区別し、ダミー価格を追加しない。
  it.each(['empty','failed'] as const)('E-VIEW-16：%sでも市場を消さず価格を捏造しない', async kind => {
    if(kind==='empty') latestQuotesMock.mockResolvedValueOnce([])
    else latestQuotesMock.mockRejectedValueOnce(new MarketMonitorApiError('network'))
    const wrapper=mount(App); await flushPromises()
    const overview=wrapper.get('.market-overview-table')
    expect(overview.findAll('tbody tr:not(.expansion-candidate)')).toHaveLength(1)
    expect(overview.get('.overview-price').text()).toBe('—')
    expect(overview.text()).toContain(kind==='empty'?'未観測':'取得失敗')
    expect(wrapper.text()).toContain('現在の収集対象：Coinbase Exchange / BTC-USD（1市場）')
    expect(wrapper.find('.detail-quote').exists()).toBe(false)
    expect(wrapper.find('canvas').exists()).toBe(true)
  })

  // 前提：履歴が閉じていて最新収集FAILED、次に履歴取得も失敗。操作：更新。期待：異常を隠さず、成功表示で上書きしない。
  it('E-VIEW-17：閉じた履歴でも収集失敗と履歴取得エラーを確認できる', async () => {
    collectionRunsMock.mockResolvedValueOnce([collectionRunFixture({status:'FAILED',resultDescription:'外部APIの応答待ちがタイムアウトしました'})])
    const wrapper=mount(App); await flushPromises()
    const health=wrapper.get('section[aria-labelledby="collection-status-title"]')
    expect(health.attributes('data-warning')).toBe('true')
    expect(health.attributes('data-failed')).toBe('true')
    expect(health.text()).toContain('タイムアウト')
    collectionRunsMock.mockRejectedValueOnce(new MarketMonitorApiError('network'))
    await wrapper.get('.refresh-button').trigger('click'); await flushPromises()
    expect(wrapper.get('.history-toggle').attributes('aria-expanded')).toBe('false')
    expect(wrapper.get('section[aria-labelledby="history-title"] [role="alert"]').isVisible()).toBe(true)
    expect(health.text()).toContain('取得エラー')
  })

  // 前提：履歴2ページ目・Spreadチャート選択。操作：30秒更新後に履歴を閉じさらに30秒。期待：操作状態を維持し、APIは周期だけ呼ぶ。
  it('E-VIEW-18：自動更新でも選択指標・履歴の開閉・ページを保持する', async () => {
    vi.useFakeTimers()
    collectionRunsMock.mockResolvedValue(Array.from({length:25},(_,index)=>collectionRunFixture({id:String(index+1)})))
    const wrapper=mount(App); await flushPromises()
    await openHistory(wrapper)
    await wrapper.findAll('button').find(button=>button.text()==='履歴を詳しく見る')!.trigger('click')
    await wrapper.findAll('button').find(button=>button.text()==='次の10件')!.trigger('click')
    await wrapper.findAll('button').find(button=>button.text()==='スプレッド')!.trigger('click')
    await vi.advanceTimersByTimeAsync(30_000)
    expect(wrapper.text()).toContain('取得済み25件中 11〜20件')
    expect(wrapper.findAll('button').find(button=>button.text()==='スプレッド')!.attributes('aria-pressed')).toBe('true')
    await wrapper.get('.history-toggle').trigger('click')
    await vi.advanceTimersByTimeAsync(30_000)
    expect(wrapper.find('#collection-history-body').exists()).toBe(false)
    expect(latestQuotesMock).toHaveBeenCalledTimes(3)
    wrapper.unmount(); expect(vi.getTimerCount()).toBe(0)
  })

  // 前提：実Composable/部品、APIとCanvasのみモック。操作：候補行を選択→30秒更新→実市場へ復帰。
  // 期待：全3市場が選択可能。候補は実データを流用せず未収集案内、追加通信なし。復帰時はCanvas/履歴状態を保持。
  it('E-VIEW-19：候補市場を選択すると未収集を示し実市場へ戻れる', async () => {
    vi.useFakeTimers()
    const wrapper = mount(App); await flushPromises()
    const originalCanvas = wrapper.get('canvas').element
    await openHistory(wrapper)
    const candidates = wrapper.findAll('.expansion-candidate')
    expect(candidates).toHaveLength(2)
    expect(wrapper.findAll('.market-overview-table tbody tr')).toHaveLength(3)
    for (const [index, label] of ['Coinbase Exchange / ETH-USD', 'Kraken / BTC-USD'].entries()) {
      await wrapper.findAll('.expansion-candidate')[index]!.get('td').trigger('click')
      expect(wrapper.get(`button[aria-label="${label}を選択"]`).attributes('aria-pressed')).toBe('true')
      expect(wrapper.get('section[aria-labelledby="market-detail-title"]').text()).toContain(label)
      expect(wrapper.get('section[aria-labelledby="market-detail-title"]').text()).toContain('まだデータを収集していない')
      expect(wrapper.get('section[aria-labelledby="history-title"]').text()).toContain('未収集のため')
      expect(wrapper.find('canvas').exists()).toBe(false)
      expect(wrapper.find('.spread-statistics').exists()).toBe(false)
      expect(wrapper.find('.collection-health-summary').exists()).toBe(false)
    }
    for (const apiMock of [latestQuotesMock, collectionRunsMock, vi.mocked(fetchQuoteHistory)]) expect(apiMock).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(30_000)
    expect(wrapper.get('button[aria-label="Kraken / BTC-USDを選択"]').attributes('aria-pressed')).toBe('true')
    for (const apiMock of [latestQuotesMock, collectionRunsMock, vi.mocked(fetchQuoteHistory)]) expect(apiMock).toHaveBeenCalledTimes(2)
    await wrapper.get('.market-overview-table tbody tr:not(.expansion-candidate) td').trigger('click')
    expect(wrapper.get('canvas').element).toBe(originalCanvas)
    expect(wrapper.get('.history-toggle').attributes('aria-expanded')).toBe('true')
    expect(wrapper.findAll('.history-scroll tbody tr')).toHaveLength(1)
    expect(wrapper.text()).toContain('現在の収集対象：Coinbase Exchange / BTC-USD（1市場）')
  })
})
