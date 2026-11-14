# 機能E：Vue業務画面の詳細設計

更新：2026-11-14。現行ソースに対応する画面・通信・計算仕様。基本操作とモバイル表示は公開版で受入れ確認済み。網羅的なST/UATの結果は[本番確認記録](../05_テスト/本番配信・ST_UAT確認.md)で区別する。

## 1. 目的と範囲

保存済み市場データの最新価格と直近24時間の推移を閲覧する。実収集はCoinbase Exchange / BTC-USDの1市場。画面操作はDB参照であり、Coinbaseへの収集やDB保存を開始しない。同一市場のBid/Ask Spreadは取引所間の裁定利益とは異なる。

市場一覧にはCoinbase BTC-USD、Coinbase ETH-USD、Kraken BTC-USDの3市場を表示する。後者2件は静的な拡張候補で、選択すると未収集を案内する。架空の価格・履歴を表示せず、候補へのAPI通信も行わない。対応市場数は1のまま。

## 2. 画面構成と操作

```text
ヘッダー：暗号資産 市場モニター／概要／収集対象／表示更新ボタン
市場一覧：市場・Mid Price・Bid/Ask Spread・取得状態
選択市場詳細：Mid・Spread → Best Bid/Ask → 最終観測/JST・鮮度
  直近24時間：Mid Price / Spread切替チャート → 平均・最大・最小Spread
データ収集の補足：直近結果・成功率・経過時間・処理時間／履歴を開く
このアプリについて：概要・技術構成・今後の構想・GitHub/README
```

縦並びでページ全体をスクロールする。PCは最大幅を制限し、カード内の縦スクロールは設けない。スマートフォンの市場行は「市場＋状態」「Mid＋Spread」の2行。Best Bid/Askは横並び、統計は項目と値を横並びにする。数量とアプリ概要は必要時に展開する。

市場行全体をマウス・キーボードで選択でき、選択行は控えめな青緑の枠とラベルで区別する。候補選択中は実市場の詳細・統計・履歴を流用しない。実市場へ戻るとKeepAliveで保持したチャート指標・履歴の開閉・ページを復元する。30秒更新でも選択・操作状態を維持する。

履歴は初期折りたたみ、開くと最新5件、詳細表示は取得済み最大100件を10件ずつページ送りする。DB全履歴をページ検索する機能ではない。SUCCESS / FAILED / SKIPPED / INTERRUPTED / RUNNING、理由、処理時間、中断検出時刻を確認できる。

## 3. ファイルとデータの流れ

基準は[frontend/src](../../frontend/src)。Composition APIとfetchを使い、Router・Pinia・Axiosは導入していない。

| ファイル | 責務 |
|---|---|
| [App.vue](../../frontend/src/App.vue) | ページ配置、市場選択、更新操作、実市場と候補の描画切替、部品の保持 |
| [types/marketMonitor.ts](../../frontend/src/types/marketMonitor.ts) | 3 APIの応答と画面状態の型 |
| [api/marketMonitorApi.ts](../../frontend/src/api/marketMonitorApi.ts) | HTTP取得、タイムアウト、unknown応答の構造・値の検証 |
| [composables/useMarketMonitor.ts](../../frontend/src/composables/useMarketMonitor.ts) | 領域別の状態・並行取得・自動更新・破棄 |
| [components/MarketOverviewPanel.vue](../../frontend/src/components/MarketOverviewPanel.vue) | 実市場と静的候補の一覧・選択イベント |
| [components/MarketDetailPanel.vue](../../frontend/src/components/MarketDetailPanel.vue) | 最新価格、数量詳細、観測時刻、鮮度と警告 |
| [components/PriceHistoryChart.vue](../../frontend/src/components/PriceHistoryChart.vue) | Chart.jsの生成・更新・破棄、指標切替、統計表示 |
| [components/CollectionStatusPanel.vue](../../frontend/src/components/CollectionStatusPanel.vue) | 収集結果・成功率・処理時間・警告の補足 |
| [components/CollectionRunHistoryPanel.vue](../../frontend/src/components/CollectionRunHistoryPanel.vue) | 履歴の開閉・5件/詳細・ページ管理 |
| [components/AboutApp.vue](../../frontend/src/components/AboutApp.vue) | 技術概要と設定されたGitHub/READMEへの導線 |
| [utils/marketCalculations.ts](../../frontend/src/utils/marketCalculations.ts) | decimal.jsによるMid・Spread・率 |
| [utils/priceHistoryChart.ts](../../frontend/src/utils/priceHistoryChart.ts) | チャート座標と十進数のツールチップ値へ変換 |
| [utils/marketDisplay.ts](../../frontend/src/utils/marketDisplay.ts) | 数値・日時・処理時間の表示、鮮度判定 |
| [style.css](../../frontend/src/style.css) | 共通スタイル・レスポンシブ配置 |

```text
Appの初回／手動／30秒更新
  → useMarketMonitor.refreshMarketData（重複を抑止）
  → marketMonitorApiで3 APIを並行取得・検証
  → 各領域の状態を独立更新
  → props → 表示部品 → 表示用計算・書式
```

## 4. 通信と状態管理

参照先はGET `/api/v1/quotes/latest`、`/api/v1/collection-runs?limit=100`、`/api/v1/quotes/history`。履歴分析の対象はCoinbase BTC-USD。JSON契約は[API基本設計](../02_基本設計/API基本設計.md)を正本とする。Actuatorを通常閲覧の前提として呼び出さない。

各領域でitems・isLoading・errorMessage・lastSucceededAtを保持する。履歴分析は期間・truncated・統計も保持する。Promise.allの内部で領域別に失敗を処理し、一部失敗で成功領域を隠さない。正常0件は空表示へ更新、通信・応答検証失敗は最後の正常値と成功時刻を保持して警告する。業務データのFAILEDとHTTP取得失敗は別状態。

初回取得、手動取得、30秒間隔の取得を共通化する。取得中は重複要求を開始せず、再試行のキューも作らない。各通信のタイムアウトは8秒。自動リトライなし。画面破棄で30秒更新・1秒表示時計を解除し、進行中の通信をAbortControllerで中止する。破棄後の応答を状態へ反映しない。

通常時はAPI取得成功日時を繰り返し表示せず、エラー時に前回取得成功時刻を表示する。これは市場の観測時刻とは異なる。内部の全API成功時刻も、新しい市場観測の証拠には使わない。

## 5. 数値・時刻・鮮度

- APIの価格・数量・IDは文字列を維持する。内部計算はdecimal.jsの精度50桁。Mid=(Bid+Ask)/2、Spread=Ask−Bid、Spread Rate=Spread/Mid×100。非数値・非正価格・Bid>Askを有効な指標にしない。
- 価格/数量は桁区切りと末尾ゼロ除去。率は小数6桁HALF_UP、微小な非ゼロ値は閾値未満と表示する。平均スプレッドだけ表示時に小数4桁HALF_UPとし、非ゼロで0.0001未満ならその旨を表示する。API精度・DB値・内部計算を変更しない。
- チャートの座標だけNumberへ変換し、ツールチップの値は十進数で計算した文字列を使う。
- API時刻はUTC、表示はJST。null・未終了は「—」。処理時間は開始と終了の差で、INTERRUPTEDの終了時刻を作らない。
- 鮮度はobservedAt（応答本文受信完了時刻）から端末時計までの経過。1秒ごとに判定し3分以上を注意表示する。受信前の市場更新時刻や裁定可能性を保証しない。通信失敗・市場データなし・古いデータを区別する。
- 成功率は取得済み収集履歴のうちRUNNINGを除く終了済み件数を母数とする。SKIPPED/INTERRUPTEDも母数に含み、終了済み0件は「—」。全期間や収集対象外市場の成功率ではない。

## 6. 24時間分析とチャート

Chart.jsを直接利用しVue専用ラッパーは追加しない。必要なLine/LinearScale/Tooltip等のみ登録する。Vue 3との接続は単一コンポーネントが担い、API取得や集計は持ち込まない。

サーバーは1回決定した期間末尾から24時間の半開区間を検索し、最新最大2000観測を昇順で返す。2001件目で上限超過を検知する。平均・最大・最小・件数は、返却点だけでなく期間内全観測を同一の読み取り専用REPEATABLE_READトランザクションで集計する。平均はサーバーで小数16桁HALF_UP。統計0件はnull。詳細は[QuoteHistoryQueryService](../../backend/src/main/java/com/example/cryptomonitor/market/service/QuoteHistoryQueryService.java)。

Mid/Spreadを切り替え、横軸は実観測時刻の線形軸・JST、縦軸はUSD。期間は24時間を維持し、観測間隔が3分を超える区間を線で結ばず欠測注記を表示する。価格の補間・架空データ・24時間変動率は追加しない。0件は未収集表示、1件は点、通信失敗は以前のデータと警告、上限超過は対象点数の制限を表示する。

レスポンシブで幅・軸目盛数を調整する。データ変更時に旧Chartを破棄し、KeepAlive復帰時はDOM復帰後にresize/updateする。画面破棄時もChartを破棄する。

## 7. テストと確認範囲

個別ケースの観点ID・前提・操作・期待結果・モック境界は各`*.test.ts`を正本とする。[ソース一覧](../../frontend/src)から対象部品と同じ名前のテストを参照する。

- 計算/書式UT：十進精度、異常値、微小値、平均表示丸め、JST、鮮度、処理時間。
- API/Composable：応答検証、正常0件、領域別失敗、保持、30秒更新、重複抑止、解除/abort。
- コンポーネント：実市場/候補、値一致、選択とKeepAlive、履歴展開/ページ、チャート状態/切替、GitHub導線。
- Vitest/Vue Test UtilsのDOM・Canvasモック試験は実ブラウザの描画やRailway通信を保証しない。PC/スマートフォンの確認は[画面確認手順](../05_テスト/業務画面の動作確認.md)、結果は[本番確認記録](../05_テスト/本番配信・ST_UAT確認.md)。現行CIの結果は[CI記録](../05_テスト/CIの実行と確認.md)に集約する。
