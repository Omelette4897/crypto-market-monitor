import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { API_TIMEOUT_MILLISECONDS, fetchCollectionRuns, fetchLatestQuotes, MarketMonitorApiError } from './marketMonitorApi'
import { collectionRunFixture, latestQuoteFixture } from '../test/marketFixtures'

/**
 * 機能E / UT：HTTP境界。fetchのみモック、JSON解析と応答検証は実装を通す。
 * 実行：npm.cmd test。実Boot/DB/Coinbaseへは接続しない。globalの置換は毎回戻す。
 * timeoutはAbortSignalの8秒指定と中断時の分類を検証し、8秒の実待機はしない。
 */
describe('機能E / API境界のUT', () => {
  const fetchMock = vi.fn<typeof fetch>()
  beforeEach(() => { vi.stubGlobal('fetch', fetchMock) })
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); fetchMock.mockReset() })

  /** 生のJSONをResponseへ入れる。構造不正もAPIの実検証を通す。 */
  function respondWith(body: unknown, status = 200) {
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify(body), { status }))
  }

  // 前提：有効な応答。操作：両APIを呼ぶ。期待：GET/相対URL/limit100/キャッシュ禁止、全数値・IDの文字列保持。
  it('E-API-01：既存2 GETの契約と文字列精度を維持する', async () => {
    const timeoutSpy = vi.spyOn(AbortSignal, 'timeout')
    const quote = latestQuoteFixture()
    const collectionRun = collectionRunFixture()
    respondWith({ items: [quote] }); respondWith({ items: [collectionRun] })
    expect(await fetchLatestQuotes()).toEqual([quote])
    expect(await fetchCollectionRuns()).toEqual([collectionRun])
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual(['/api/v1/quotes/latest', '/api/v1/collection-runs?limit=100'])
    for (const [, options] of fetchMock.mock.calls) {
      expect(options).toMatchObject({ method: 'GET', cache: 'no-store' })
      expect(options?.signal).toBeInstanceOf(AbortSignal)
    }
    expect(timeoutSpy).toHaveBeenNthCalledWith(1, API_TIMEOUT_MILLISECONDS)
    expect(timeoutSpy).toHaveBeenNthCalledWith(2, 8_000)
  })

  // 前提：正常0件。操作：両API。期待：空配列を正常値として返す。
  it('E-API-02：正常0件はエラーにしない', async () => {
    respondWith({ items: [] }); respondWith({ items: [] })
    expect(await fetchLatestQuotes()).toEqual([])
    expect(await fetchCollectionRuns()).toEqual([])
  })

  // 前提：HTTP失敗・機微な本文。操作：取得。期待：固定説明と分類、再試行なし、本文を公開しない。
  it.each([[400, 'invalid-request'], [500, 'server'], [503, 'unavailable'], [502, 'server']] as const)(
    'E-API-03：HTTP%s → %s、本文を隠して再試行しない', async (status, kind) => {
      respondWith({ message: 'PRIVATE_SQL_PASSWORD' }, status)
      const error = await fetchLatestQuotes().catch(error => error)
      expect(error).toBeInstanceOf(MarketMonitorApiError)
      expect(error.kind).toBe(kind)
      expect(error.message).not.toContain('PRIVATE_SQL_PASSWORD')
      expect(fetchMock).toHaveBeenCalledTimes(1)
    })

  // 前提：200でも契約不正。操作：価格GET。期待：初期値/空配列に置換せず形式エラー。
  it.each([
    ['null', null], ['配列直返却', []], ['items欠落', {}], ['itemsがnull', { items: null }],
    ['itemsが文字列', { items: 'invalid' }], ['itemがnull', { items: [null] }],
    ['IDが数値', { items: [{ ...latestQuoteFixture(), id: 1 }] }],
    ['価格が数値', { items: [{ ...latestQuoteFixture(), bidPrice: 100 }] }],
    ['数量が非数値文字列', { items: [{ ...latestQuoteFixture(), askSize: 'NaN' }] }],
    ['日時が不正', { items: [{ ...latestQuoteFixture(), observedAt: 'invalid' }] }],
    ['提供元時刻の欠落', { items: [{ ...latestQuoteFixture(), sourceAt: undefined }] }],
    ['市場が空', { items: [{ ...latestQuoteFixture(), exchange: '' }] }],
  ])('E-API-04：%sは形式エラー', async (_caseName, responseBody) => {
    respondWith(responseBody)
    await expect(fetchLatestQuotes()).rejects.toMatchObject({ kind: 'invalid-response' })
  })

  // 前提：履歴応答の型/日時/状態が不正。操作：履歴GET。期待：表示前に形式エラー。
  it.each([
    ['状態不明', { status: 'UNKNOWN' }], ['理由がオブジェクト', { resultDescription: {} }],
    ['終了日時不正', { finishedAt: 'tomorrow' }], ['中断検出日時不正', { interruptionDetectedAt: 123 }],
    ['開始日時欠落', { startedAt: undefined }], ['理由コード欠落', { resultCode: undefined }],
  ])('E-API-05：履歴の%sを拒否する', async (_caseName, overrides) => {
    respondWith({ items: [{ ...collectionRunFixture(), ...overrides }] })
    await expect(fetchCollectionRuns()).rejects.toMatchObject({ kind: 'invalid-response' })
  })

  // 前提：全状態・null/非null・小数秒9桁。操作：履歴/価格GET。期待：既存契約の正常値は受け付ける。
  it('E-API-06：全業務状態と任意項目を受け取る', async () => {
    const history = ['RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED', 'INTERRUPTED'].map((status, index) => ({
      ...collectionRunFixture(), id: String(index + 1), status, finishedAt: null,
      interruptionDetectedAt: status === 'INTERRUPTED' ? '2026-11-10T10:00:02Z' : null,
      resultCode: status === 'SUCCESS' ? null : 'TEST_REASON', resultDescription: status === 'SUCCESS' ? null : '理由',
    }))
    respondWith({ items: history })
    expect(await fetchCollectionRuns()).toEqual(history)
    const quote = latestQuoteFixture({ sourceAt: '2026-11-10T09:59:59.123456789Z' })
    respondWith({ items: [quote] })
    expect(await fetchLatestQuotes()).toEqual([quote])
  })

  // 前提：HTTPは200だがHTML/壊れたJSON。操作：取得。期待：本文を出さず形式エラー。
  it.each(['<html>PRIVATE_DETAIL</html>', '{invalid'])('E-API-07：JSONでない本文を拒否する：%s', async body => {
    fetchMock.mockResolvedValueOnce(new Response(body))
    await expect(fetchLatestQuotes()).rejects.toMatchObject({ kind: 'invalid-response' })
  })

  // 前提：通信断。操作：取得。期待：内部例外を隠し接続エラー、再試行なし。
  it('E-API-08：通信断を公開用の接続エラーにする', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('PRIVATE_DETAIL'))
    const error = await fetchLatestQuotes().catch(error => error)
    expect(error.kind).toBe('network')
    expect(error.message).not.toContain('PRIVATE_DETAIL')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  // 前提：ヘッダー待ち/本文待ちに期限切れ。操作：AbortSignalを中断。期待：どちらもtimeoutに分類。
  it.each(['headers', 'body'] as const)('E-API-09：%s待機中のタイムアウト', async waitingPhase => {
    const timeoutController = new AbortController()
    vi.spyOn(AbortSignal, 'timeout').mockReturnValue(timeoutController.signal)
    const timeoutError = new DOMException('timeout', 'TimeoutError')
    if (waitingPhase === 'headers') fetchMock.mockImplementationOnce(async () => {
      timeoutController.abort(timeoutError)
      throw timeoutError
    })
    else {
      const response = new Response('{}')
      vi.spyOn(response, 'json').mockImplementation(async () => {
        timeoutController.abort(timeoutError)
        throw timeoutError
      })
      fetchMock.mockResolvedValueOnce(response)
    }
    await expect(fetchLatestQuotes()).rejects.toMatchObject({ kind: 'timeout' })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
