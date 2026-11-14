# 機能A：Coinbase価格取得・検証の詳細設計

更新：2026-11-09。現行設計。実装・通常テストは完了。実Coinbase接続1件の成功は2026-11-06の過去実績であり、今回再実行した結果ではない。本文の対象範囲は機能Aの責務を示す。

## 目的・範囲・完成条件

BTC-USDのLevel 1から最良Bid/Askと各数量を取得し、本文受信完了時刻とともに既存Mapperへ渡せるようにする。採用可能なら共通観測、不採用なら分類と理由を呼び出し元が判断できる。

対象はHTTP取得・本文読込み・受信時刻・JSONのDTO化・既存Mapperとの接続。DB保存、実行状態更新、参照API、定期実行、画面は今回の対象外。

## クラスのつながり【合意済み】

```text
呼び出し元（CollectionService）
  ├─ CoinbaseClient.fetchBtcUsd()
  │    → GET /products/BTC-USD/book?level=1
  │    → HTTP確認・本文受信・時刻記録・JSON読込み
  │    ← CoinbaseBookResponseWithReceivedAt
  └─ CoinbaseBookMapper.convertBtcUsd(response, receivedAt)
       ← CoinbaseBookConversionResult
            ACCEPTED → MarketObservation
            SKIPPED / FAILED → 理由コード
```

| 型 | 入出力・責務 | 実装状態 |
|---|---|---|
| CoinbaseClient | BTC-USD固定のHTTP取得。価格の業務検証を重複実装しない | 実装済み |
| CoinbaseBookResponseWithReceivedAt | CoinbaseBookResponse responseとInstant receivedAtを保持 | 実装済み |
| CoinbaseAccessException | 取得・読込みに失敗した理由を呼び出し元へ伝える | 実装済み |
| CoinbaseBookResponse | Coinbase固有のJSONを受けるDTO | 実装済み |
| CoinbaseBookMapper | DTOと受信時刻を検証・変換する | 実装済み |
| CoinbaseBookConversionResult | 変換の採否と観測または理由を保持する | 実装済み |
| MarketObservation | 取引所非依存の価格・数量・時刻を保持する | 実装済み |

受信先はCoinbase Exchangeの公開GET。APIキーは不要。取得対象はサーバー側のBTC-USD固定で、別ペアの応答をBTC-USDと誤認しない。通信実装はSpringの同期RestClientを使用する。

## 時刻とデータ

本文を受信し終えた直後、JSONのDTO変換前に時刻を記録する。receivedAtをMapperのobservedAt引数に渡し、MarketObservation.observedAtへ同じInstantを保持する。変換時に現在時刻を取り直さない。時刻取得を注入可能にし、テストを固定する。

sourceAtは提供元timeの意味が確認できるまでnull。DB/APIの時刻仕様は維持し、DB保存境界の小数秒6桁への切り捨ては機能Bに実装済み。価格・数量の既存採否規則を変更しない。

## 異常時の扱い【合意済み】

- ClientでHTTP取得・本文/JSON読込みが成立しなかった場合はCoinbaseAccessExceptionで分類を伝える。
- タイムアウト、接続失敗、HTTPエラー、429、JSON読込み不能を区別できるようにする。具体的な理由コードの一覧と公開説明の管理は既存Mapperのコードと整合させる。
- Mapperまで進んだ観測の採否はCoinbaseBookConversionResultとして返す。
- タイムアウトはアプリが判断できた取得失敗。機能Bの実行履歴ではFAILEDの対象で、INTERRUPTEDとは異なる。
- Client/Mapper自体はDBへFAILEDを書き込まない。呼び出し元のCollectionServiceが保存処理へ接続する。
- 想定外のプログラム不具合を、すべて取得データ不正へ置き換えない。外部へ内部例外・SQL・秘密を返さない。

## タイムアウト・再試行【初期方針として合意】

- 接続タイムアウト：3秒。
- 読取り待ちタイムアウト：5秒。
- 設定で変更可能にする。最適値・性能を実測した結果ではなく、初期値として暫定採用したもの。
- この2値は「すべての処理が必ず合計8秒以内」という保証ではない。実装するHTTP方式の接続/本文待ちの動作をテストで確認する。
- Client内でアプリ独自の自動再試行は行わない。通信ライブラリ内部の動作とアプリの再試行方針は区別する。
- 収集周期・起動時回収・429後の次回制御は、合意・実装済みの機能Dで扱う。

## 合意したテスト観点

| ケース | 保証したい内容 |
|---|---|
| 正常応答 | BTC-USD/Level 1へのGET、DTOと受信時刻の取得 |
| 時刻の意味 | 本文受信後・JSON変換前の時刻を保持し、Mapperへ同じ値を渡す |
| HTTP 500/429 | 正常価格として扱わず原因を区別する |
| タイムアウト・接続失敗 | 待ち続けず、失敗分類を伝える |
| 空本文・壊れたJSON・型違い | 正常なDTOとみなさず読込み失敗を扱う |
| ClientとMapperの接続 | 実装済みの検証を経て共通観測を得る |

自動テストは固定データと模擬HTTPを使い、実CoinbaseやMySQLに依存させない。実Coinbase疎通は別の確認として明示する。関連する既存テストも回帰確認の対象とする。


## 状態・確認手順

現在の配置は[パッケージ構成](パッケージ構成.md)、実接続の実行方法と限界は[実Coinbase接続確認](../05_テスト/実Coinbase接続確認.md)を参照。Clientの生成だけでは通信せず、現在は機能DからCollectionServiceを介して呼ばれる。到達不能ネットワークでの接続タイムアウト実測は未確認。
