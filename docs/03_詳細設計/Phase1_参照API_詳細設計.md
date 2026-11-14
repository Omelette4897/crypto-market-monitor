# 機能C：参照APIの詳細設計

更新：2026-11-09。現行設計。実装・自動テストは完了。本書は最新価格・収集履歴の2 GETを扱う。追加の24時間履歴分析GETは[API基本設計](../02_基本設計/API基本設計.md)と[Vue詳細設計](Phase1_Vue業務画面_詳細設計.md)を参照。

## 1. 目的と既存契約

保存済みの最新気配値と収集実行履歴を、GET APIから参照できるようにする。基本契約は[API基本設計](../02_基本設計/API基本設計.md)を維持する。価格収集・保存・Scheduler・画面・DBスキーマは変更しない。

| API | 入力 | 出力 |
|---|---|---|
| GET /api/v1/quotes/latest | 任意のexchange・symbol | 対象ごとの最新保存価格をitems配列で返す |
| GET /api/v1/collection-runs | 任意のexchange・symbol・limit | 指定条件の収集実行履歴をitems配列で返す |

履歴limitは省略時100、整数1～100。両フィルター指定はAND。省略と空文字を区別し、空文字・未対応値・不正件数は400。Phase 1の入力対応対象はCOINBASE_EXCHANGE/BTC-USD。検索成功で0件なら200/items=[]、検索失敗を空配列に置き換えない。

ID・価格・数量はJSON文字列、日時はUTCのZ形式、任意値はnull。市場・通貨ペアを応答へ含める。実行履歴のFAILEDとAPI検索失敗は別であり、FAILEDの履歴を検索できれば200。

## 2. クラスとデータの流れ

```text
LatestQuoteController
  → LatestQuoteQueryService.findLatestQuotes(exchange, symbol)
    → MarketSnapshotRepository（最新検索）
    → LatestQuoteItemへ変換
  → LatestQuotesResponse → JSON

CollectionRunController
  → CollectionRunQueryService.findCollectionRuns(exchange, symbol, limit)
    → CollectionRunRepository（履歴検索）
    → CollectionRunHistoryItemへ変換
  → CollectionRunHistoryResponse → JSON
```

| 配置パッケージ | クラス・担当 |
|---|---|
| market.controller | LatestQuoteController：最新価格GETの受付 |
| market.service | LatestQuoteQueryService：入力検証・検索・DTO変換 |
| market.dto | LatestQuoteItem：1観測分、LatestQuotesResponse：itemsを持つ応答全体 |
| collection.controller | CollectionRunController：実行履歴GETの受付 |
| collection.service | CollectionRunQueryService：入力検証・検索・DTO変換 |
| collection.dto | CollectionRunHistoryItem：1実行分、CollectionRunHistoryResponse：itemsを持つ応答全体 |
| common.api | ApiExceptionHandler：共通エラー応答、ApiErrorResponse：code/message |

DTO変換は各検索Serviceの小さな変換メソッドに置き、現時点で専用Mapperは増やさない。Entityは外へ返さない。参照Serviceは保存担当Service・CoinbaseClientを呼ばず、両Repositoryの検索メソッドを呼ぶ。

検索Serviceの参照用@Transactional(readOnly=true)の範囲で、検索からDTO変換まで行う。readOnlyをDB更新の強制禁止と解釈せず、実際の呼出し経路も参照専用に保つ。

## 3. 検索方法

- 最新価格はmarket_snapshotsからcollection_runsへJOINし、取引所・ペアの取得と条件絞り込みに使う。
- **SUCCESS条件は付けない。** 正規の保存時の状態・観測の整合性は機能Bが保証する。参照側で状態による追加除外をしない。
- 最新の順位はobserved_at降順、同値なら観測ID降順。同じ取引所・ペアに自分より新しい観測が存在しない行をDB側で選ぶ。NOT EXISTSを用い、全履歴をJavaへ取り込んで選別しない。
- 最新結果の対象間の順序はexchange/symbol昇順。後続収集がFAILEDでも以前の保存観測を返し、観測時刻を変更しない。
- 最新価格の実行履歴関連は同時に取得し、行ごとの追加SELECT（N+1）を避ける。関連値はDTO変換前に取得する。
- 実行履歴は任意条件で絞り、started_at降順・同値なら実行ID降順。limitは全対象を合わせた上限で、DB側で件数制限する。不要な総件数取得を前提にしない。

JPQL・例外分類は実装とMySQL結合試験で確認済み。将来の複数対象は検索試験データで扱い、実収集対象を増やしたものではない。

## 4. DTOとエラー分類

最新価格の項目は観測id・exchange・symbol・bidPrice・bidSize・askPrice・askSize・observedAt・sourceAt。数値はBigDecimalから指数表記を避けて文字列化する。JSTや表示小数桁への調整は画面の責務。

履歴は実行id・exchange・symbol・status・startedAt・finishedAt・interruptionDetectedAt・resultCode・resultDescription。resultDescriptionは既存理由コードから生成し、DBへ説明文を追加しない。SUCCESSの理由/説明はnull。Entityをそのまま公開するためのDTOにはしない。

| 事象 | HTTP/コード | 方針 |
|---|---|---|
| 入力不正 | 400/INVALID_PARAMETER | 参照条件の不正として説明する |
| 接続取得不能・接続断など判別できるDB接続障害 | 503/SERVICE_UNAVAILABLE | 一時的なサービス利用不能として返す |
| SQL/JPAマッピングの不具合・制約違反など内部障害 | 500/INTERNAL_ERROR | DB例外を一律503にしない |

内部の例外文・SQL・資格情報をレスポンスへ含めない。内部調査情報はログに残す。通信そのものが失敗してJSONが届かない場合もあり、すべての障害でJSONが必ず届くとは保証しない。

## 5. テスト分担

| 区分 | 確認範囲 |
|---|---|
| 検索ServiceのUT | 入力検証、省略時の件数、ID/数値/日時/null/理由説明のDTO変換 |
| ControllerのMockMvc | URL・パラメータ、成功JSONの型・空配列/null、400/503/500、内部情報を返さないこと |
| TestcontainersのDB結合 | 対象別最新、同時刻のID順、フィルター片方/両方/なし、後続FAILED、状態で観測を除外しないこと、0件、履歴の順序と既定/指定件数、JOINによる関連取得 |

GETから外部取得・DB更新を起動しないことも確認する。DBの実結果は実MySQLで検証し、UTのモックだけで検索やTxの正しさを保証したと扱わない。

各ケースの観点ID・日本語表示名・前提/入力・操作・期待結果はテストソースへ記載する。本書は設計上のテスト分担を示すもので、個別ケース仕様を二重管理する資料にはしない。


## 実装上の確認と範囲

最新はJOIN FETCH＋NOT EXISTS、履歴はList＋PageableによるDB件数制限を採用。実MySQLで検索、関連取得、SQL回数、GETで更新しないことを確認した。接続障害503は模擬例外による確認であり、実接続断の故障注入は未実施。操作は[参照APIの動作確認](../05_テスト/参照APIの動作確認.md)を参照。
