# Phase 1 参照API基本設計

更新：2026-11-12。既存2 GETの契約を維持し、履歴分析GETを追加。新API・ダッシュボードは実装/検証済み、ユーザーレビューと全体ST/UATは未完了。[参照API詳細設計](../03_詳細設計/Phase1_参照API_詳細設計.md)・[動作確認](../05_テスト/参照APIの動作確認.md)を参照。

## 1. 対象と責務

| API | 返す情報 |
|---|---|
| GET /api/v1/quotes/latest | 市場・通貨ペアごとの最新保存価格一覧 |
| GET /api/v1/collection-runs | 収集実行の履歴一覧 |
| GET /api/v1/quotes/history | 1市場の24時間観測一覧と全期間スプレッド統計 |

価格履歴GET /api/v1/quotesは作成しない。画面は市場概要・24時間分析・収集状況・収集履歴の領域にする。market_snapshotsへの価格履歴保存、collection_runsへの成功・失敗・スキップ・中断の記録は元の仕様を維持する。
画面読込時と表示更新時に両APIを呼ぶ。外部取引所の呼出し・新規収集・保存を起動しない。

## 2. 入力の実装既定

| 入力 | 最新一覧 | 実行履歴 | 省略時 |
|---|---|---|---|
| exchange | 任意 | 任意 | 市場で絞らない |
| symbol | 任意 | 任意 | 通貨ペアで絞らない |
| limit | 設けない | 任意、整数1～100 | 実行履歴100件 |

両フィルターを指定したらAND条件。Phase 1の対応対象はCOINBASE_EXCHANGEとBTC-USD。
省略と空文字は区別し、空文字・未対応値・不正件数は400とする。対応済みだがデータがなければ200とitems空配列。
最新一覧は対象ごとに1件であり、全体から最新1件ではない。実行履歴の件数は全対象を合わせた上限で、各対象100件ではない。
未知のクエリはAPI契約として提供しない。厳格な未知パラメータ拒否のためだけの共通基盤は初期版に追加しない。

呼出し例：

```http
GET /api/v1/quotes/latest
GET /api/v1/quotes/latest?symbol=BTC-USD
GET /api/v1/collection-runs?limit=100
GET /api/v1/collection-runs?exchange=COINBASE_EXCHANGE&symbol=BTC-USD&limit=20
```

## 3. 応答の基本契約【合意済み】

- 成功時はitems配列。0件・1件でも形を変えない。
- idはJSON文字列。DBのBIGINT採番は維持する。
- 価格・数量はJSON文字列、日時はUTCのオフセット付き表記（Z）。
- 値のない任意項目はnull。Entity直返却をせず、API用DTOへ変換する。
- 市場・ペアは応答へ含める。DBのJOIN構造を利用者に意識させない。
- resultDescriptionはresultCodeから生成し、DBに保存しない。内部例外・SQL・資格情報を返さない。

### 最新価格一覧の例

数値・日時は説明用の仮データ。提供元時刻が得られない場合はnull。

```json
{
  "items": [
    {
      "id": "501",
      "exchange": "COINBASE_EXCHANGE",
      "symbol": "BTC-USD",
      "bidPrice": "100000.10",
      "bidSize": "0.25000000",
      "askPrice": "100001.20",
      "askSize": "0.40000000",
      "observedAt": "2026-10-27T03:00:00Z",
      "sourceAt": null
    }
  ]
}
```

最新の判定はobserved_at降順、同時刻なら観測id降順。対象間はexchange/symbol昇順。古い保存値も200で返し、観測時刻を変えない。鮮度の注意表示としきい値は収集・画面実装時に決める。

### 収集実行履歴の例

```json
{
  "items": [
    {
      "id": "102",
      "exchange": "COINBASE_EXCHANGE",
      "symbol": "BTC-USD",
      "status": "FAILED",
      "startedAt": "2026-10-27T03:01:00Z",
      "finishedAt": "2026-10-27T03:01:05Z",
      "interruptionDetectedAt": null,
      "resultCode": "TIMEOUT",
      "resultDescription": "外部APIの応答待ちがタイムアウトしました"
    }
  ]
}
```

並び順はstarted_at降順、同時刻なら実行id降順。状態はRUNNING/SUCCESS/FAILED/SKIPPED/INTERRUPTED。
SUCCESSのresultCode/resultDescriptionはnullとする実装既定。理由コードと公開説明の対応は履歴DTOへの変換処理に実装済み。
失敗した実行履歴を正常に検索できた場合は200。履歴中のFAILEDをHTTPの500へ変換しない。

## 4. データなし・エラー【合意済み】

| 状況 | HTTPステータス | 本文 |
|---|---|---|
| 正常、該当0件を含む | 200 | items配列 |
| 入力不正 | 400 | code/message |
| 一時的なDB接続不能 | 503 | code/message |
| 想定外の内部不具合 | 500 | code/message |

DBのSQL不具合を一律503にしない。エラーコードの具体名は以下を実装既定とする：INVALID_PARAMETER / SERVICE_UNAVAILABLE / INTERNAL_ERROR。外部API取得時のTIMEOUT等の収集理由コードとは別。

```json
{
  "code": "INVALID_PARAMETER",
  "message": "limitは1から100までで指定してください"
}
```

通信断や上流の起動待ち等ではAPIのJSONが届かない場合もある。フロントは成功以外のHTTPステータスと、通信そのものの失敗を両方扱う。

## 5. フロントの動作と確認予定

両APIを独立して取得し、最新価格と実行履歴に表示する。一方が失敗しても成功領域は表示する。再取得に失敗した領域は前回表示を残し、更新失敗を明示する。
0件は未取得として表示し、価格0で代用しない。時刻はJSTへ変換して明記。JSONのid/価格/数量を安易にNumberへ変換しない。

確認予定：

1. 最新値は対象ごとに1件、同時刻の順序も安定する。
2. 実行履歴は既定100件・指定件数・並び順に従う。
3. 省略、フィルター片方/両方、0件、不正値を確認する。
4. id/価格/数量の型、UTC日時、nullが契約どおり。
5. DB利用不能と内部例外で適切なステータスと説明を返し、内部情報を漏らさない。
6. API参照がCoinbase通信やDB書込みを起動しない。
7. 画面の未取得・部分失敗・表示更新失敗を確認する。

API側の自動テスト・DB結合は実施済み。画面の未取得・部分失敗・表示更新失敗は機能Eで設計・実装・検証する。

## 6. 24時間の価格履歴・スプレッド分析【2026-11-12追加・実装済み】

GET /api/v1/quotes/history?exchange=COINBASE_EXCHANGE&symbol=BTC-USD

このAPIだけは単一市場の分析を目的とし、exchange/symbolの省略時はCOINBASE_EXCHANGE/BTC-USDを選ぶ。既存APIの省略時の意味は変更しない。空文字・未対応値は400。期間はサーバーUTC時計を1回取得したrangeEndの24時間前から、[rangeStart,rangeEnd)で固定。任意期間/limit/サンプリングは提供しない。

```json
{
  "rangeStart": "2026-11-11T10:00:00Z",
  "rangeEnd": "2026-11-12T10:00:00Z",
  "truncated": false,
  "items": [
    {"id":"501","exchange":"COINBASE_EXCHANGE","symbol":"BTC-USD",
     "observedAt":"2026-11-12T09:59:00Z","bidPrice":"100.100000000000",
     "askPrice":"101.200000000000","spread":"1.100000000000"}
  ],
  "spreadStatistics": {
    "observationCount":1,
    "averageSpread":"1.1000000000000000",
    "minimumSpread":"1.100000000000",
    "maximumSpread":"1.100000000000"
  }
}
```

上記は説明用。itemsは期間内の最新2,000件を選んだ後、observedAt昇順・同時刻id昇順に返す。超過時はtruncated=true。ID・金額は文字列、時刻はUTC、observationCountは整数。

spreadStatisticsは一覧制限前の同市場・同期間の全保存観測が対象。spread=Ask−Bid。DBで件数/合計/最小/最大を求め、JavaのBigDecimalで単純平均を小数16桁・HALF_UPとして計算する。時間加重・数量加重ではない。欠測・収集失敗へ架空の価格を補完しない。SUCCESS条件は追加しない。

0件は200・items=[]・件数0・3金額null。検索失敗は空配列にせず既存の400/503/500形式を使う。2 SELECTは同じ読み取り専用・REPEATABLE_READのTxで実行し、並行収集による読取り対象のずれを避ける。

索引・DB/Flywayは変更しない。返却上限は走査負荷の上限ではない。大量蓄積時の性能確認・索引評価は未実施。画面・実装結果は[機能E詳細設計](../03_詳細設計/Phase1_Vue業務画面_詳細設計.md)第9〜10節を参照。
