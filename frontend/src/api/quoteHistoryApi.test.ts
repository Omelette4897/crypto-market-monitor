import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchQuoteHistory } from './marketMonitorApi'
import { quoteHistoryFixture } from '../test/marketFixtures'
/** 機能E / API境界UT。fetchのみモック、JSON/型/期間/集計の検証は実体。実DB/HTTPの保証ではない。npm.cmd test。 */
afterEach(() => vi.unstubAllGlobals())
describe('機能E / 履歴分析API境界', () => {
  // 前提：正しい固定応答。操作：取得。期待：指定対象のURL・GET・サーバー統計をそのまま保持。
  it('E-HAPI-01：期間・統計・観測を同じ応答として扱う', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(quoteHistoryFixture())))
    vi.stubGlobal('fetch',fetchMock)
    expect(await fetchQuoteHistory()).toEqual(quoteHistoryFixture())
    expect(fetchMock.mock.calls[0]![0]).toBe('/api/v1/quotes/history?exchange=COINBASE_EXCHANGE&symbol=BTC-USD')
  })
  // 前提：期間や統計の不整合。操作：取得。期待：不正を空配列や架空の集計で補正せず失敗。
  it.each(['period','statistics','order','market','count','spread'] as const)('E-HAPI-02：%sの不正応答は拒否する', async kind => {
    const body = quoteHistoryFixture()
    if (kind==='period') body.rangeEnd=body.rangeStart
    if (kind==='statistics') (body.spreadStatistics as unknown as Record<string,unknown>).averageSpread=1
    if (kind==='order') body.items=[{...body.items[0]!, observedAt:'2026-11-10T10:00:30Z'}, body.items[0]!]
    if (kind==='market') body.items[0]!.symbol='ETH-USD'
    if (kind==='count') body.spreadStatistics.observationCount=0
    if (kind==='spread') (body.items[0] as unknown as Record<string,unknown>).spread=null
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response(JSON.stringify(body))))
    await expect(fetchQuoteHistory()).rejects.toMatchObject({kind:'invalid-response'})
  })
  // 前提：0件・統計null。操作：取得。期待：正常応答を維持し、0件を障害にしない。
  it('E-HAPI-03：正常0件とnull統計を受け付ける', async () => {
    const body={...quoteHistoryFixture(), items:[], spreadStatistics:{observationCount:0,averageSpread:null,minimumSpread:null,maximumSpread:null}}
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response(JSON.stringify(body))))
    expect((await fetchQuoteHistory()).spreadStatistics.averageSpread).toBeNull()
  })
  // 前提：同一ミリ秒内の範囲上端直前。操作：取得。期待：マイクロ秒の比較を保ち、上端そのものだけを拒否する。
  it('E-HAPI-04：期間上端をミリ秒へ切り捨てず検証する', async () => {
    const body=quoteHistoryFixture()
    body.rangeEnd='2026-11-10T10:01:00.123456Z'
    body.items[0]!.observedAt='2026-11-10T10:01:00.123455Z'
    vi.stubGlobal('fetch',vi.fn().mockResolvedValueOnce(new Response(JSON.stringify(body))))
    expect((await fetchQuoteHistory()).items).toHaveLength(1)
    body.items[0]!.observedAt=body.rangeEnd
    vi.stubGlobal('fetch',vi.fn().mockResolvedValueOnce(new Response(JSON.stringify(body))))
    await expect(fetchQuoteHistory()).rejects.toMatchObject({kind:'invalid-response'})
  })

  // 前提：実行中の模擬fetch。操作：外部Controllerをabort。期待：合成signalへ伝播し、キャンセルをサーバー障害へ変換しない。
  it('E-HAPI-05：画面破棄の中断要求をHTTP境界へ伝える', async () => {
    let receivedSignal: AbortSignal | undefined
    vi.stubGlobal('fetch',vi.fn((_url: string, options: RequestInit) => new Promise((_resolve,reject) => {
      receivedSignal=options.signal as AbortSignal
      receivedSignal.addEventListener('abort',()=>reject(new DOMException('cancelled','AbortError')),{once:true})
    })))
    const controller=new AbortController()
    const request=fetchQuoteHistory(controller.signal)
    controller.abort()
    await expect(request).rejects.toMatchObject({name:'AbortError'})
    expect(receivedSignal?.aborted).toBe(true)
  })

})
