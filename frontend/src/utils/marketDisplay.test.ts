import { describe, expect, it } from 'vitest'
import { calculateQuotedSpread, collectionStatusLabel, currencyUnits, exchangeLabel, formatAverageSpread, formatCollectionDuration, formatDecimal, formatJst, isObservationStale, observationAgeLabel } from './marketDisplay'

/**
 * 機能E / UT：副作用のない表示関数。固定入力のみでDB/HTTP/実ブラウザを使用しない。
 * 実行：frontendでnpm.cmd test。ローカル時刻や端末のタイムゾーンに依存しない。
 */
describe('機能E / 表示補助のUT', () => {
  // 前提：UTCの前日15時。操作：JST表示。期待：翌日00時とJST表記、nullはダッシュ。
  it('E-FMT-01：JSTの日付繰上りとnullを表示する', () => {
    expect(formatJst('2026-11-09T15:00:00Z')).toBe('2026/11/10 00:00:00 JST')
    expect(formatJst(null)).toBe('—')
  })

  // 前提：観測時刻を固定。操作：判定時刻の境界値を渡す。期待：3分以上だけtrue、未取得/未来観測はfalse。
  it.each([
    ['未取得', null, false],
    ['3分未満', '2026-11-10T10:02:59.999Z', false],
    ['ちょうど3分', '2026-11-10T10:03:00Z', true],
    ['3分超過', '2026-11-10T10:03:00.001Z', true],
    ['端末時刻より未来', '2026-11-10T09:59:00Z', false],
  ] as const)('E-FMT-02：%s、判定時刻=%s → 注意=%s', (_caseName, evaluatedAt, expected) => {
    expect(isObservationStale('2026-11-10T10:00:00Z', evaluatedAt)).toBe(expected)
  })

  // 前提：既存の全状態。操作：ラベル変換。期待：HTTP成功/失敗と混同しない業務状態名。
  it.each([
    ['RUNNING', '実行中'], ['SUCCESS', '成功'], ['FAILED', '失敗'], ['SKIPPED', 'スキップ'], ['INTERRUPTED', '中断'],
  ] as const)('E-FMT-03：%s → %s', (status, expected) => expect(collectionStatusLabel(status)).toBe(expected))

  // 前提：既存/未知取引所とBASE-QUOTE。操作：表示名・単位変換。期待：未知の取引所名は破棄しない。
  it('E-FMT-04：取引所名と通貨単位を表示する', () => {
    expect(exchangeLabel('COINBASE_EXCHANGE')).toBe('Coinbase Exchange')
    expect(exchangeLabel('OTHER')).toBe('OTHER')
    expect(currencyUnits('BTC-USD')).toEqual({ base: 'BTC', quote: 'USD' })
    expect(currencyUnits('ETH-USDT')).toEqual({ base: 'ETH', quote: 'USDT' })
  })

  // 前提：大きな整数・小数・末尾ゼロ。操作：表示整形。期待：有効桁を失わず共通の桁区切り。
  it.each([
    ['999999999999999999.123456789012', '999,999,999,999,999,999.123456789012'],
    ['74344.230000000000', '74,344.23'], ['0.000000000001', '0.000000000001'],
    ['1000.000', '1,000'], ['-1234.5000', '-1,234.5'], ['0.000', '0'],
  ])('E-FMT-05：数値%sを精度を保ち%sへ整形する', (input, expected) => {
    expect(formatDecimal(input)).toBe(expected)
  })

  // 前提：安全整数を超える価格、異なる小数桁、ゼロ/負の差。操作：Ask−Bid。期待：誤差のない差額。
  it.each([
    ['999999999999999999.123456789013', '999999999999999999.123456789012', '0.000000000001'],
    ['100.2', '100.19', '0.01'], ['100', '99.999', '0.001'],
    ['2', '1', '1'], ['1.00', '1', '0'], ['99.9', '100', '-0.1'],
  ])('E-FMT-06：Ask=%s、Bid=%sの差額は%s', (askPrice, bidPrice, expected) => {
    expect(formatDecimal(calculateQuotedSpread(askPrice, bidPrice))).toBe(expected)
  })

  // 前提：完了/未完了・不正な前後関係。操作：処理時間表示。期待：完了のみ秒表示、nullを現在時刻で補完しない。
  it.each([
    ['2026-11-10T10:00:01.234Z', '1.234 秒'],
    ['2026-11-10T10:00:00Z', '0.000 秒'], [null, '—'],
    ['2026-11-10T09:59:59Z', '—'],
  ])('E-FMT-07：終了時刻%sの処理時間=%s', (finishedAt, expected) => {
    expect(formatCollectionDuration('2026-11-10T10:00:00Z', finishedAt)).toBe(expected)
  })

  // 前提：成功時点を固定。操作：観測経過を表示。期待：秒/分・未判定・時計不整合を区別する。
  it.each([
    [null, '未判定'], ['2026-11-10T10:00:30Z', '30秒経過'],
    ['2026-11-10T10:03:00Z', '3分0秒経過'],
    ['2026-11-10T09:59:59Z', '観測時刻が端末時刻より未来です'],
  ])('E-FMT-08：判定時刻%sの経過表示=%s', (evaluatedAt, expected) => {
    expect(observationAgeLabel('2026-11-10T10:00:00Z', evaluatedAt)).toBe(expected)
  })

})

// 前提：サーバーの平均値文字列。操作：表示だけ整形。期待：4桁四捨五入、微小値を0と誤表示しない。
it.each([
  ['0.0488721804511278', '0.0489'], ['0.00015', '0.0002'], ['0.00001', '<0.0001'],
  ['0', '0'], [null, '—'], ['NaN', '—'], ['-0.1', '—'],
])('E-FMT-09：平均%sを表示用%sへ整形する', (value, expected) => {
  expect(formatAverageSpread(value)).toBe(expected)
})
