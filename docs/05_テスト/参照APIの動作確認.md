# 参照APIの動作確認

更新：2026-11-08。機能Cの実行手順。個別テストケースの仕様はテストソースの観点ID・前提・期待結果を参照する。

## 開発用アプリを手動で確認する

1. Docker Desktopと既存ComposeのMySQLを起動する。
2. IntelliJでCryptoMarketMonitorApplicationを起動する。既に以前のコードで動かしていた場合は再起動する。
3. ブラウザまたはPowerShellから次のGETへアクセスする。Viteは不要。

```powershell
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/quotes/latest' | ConvertTo-Json -Depth 10
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/collection-runs?limit=20' | ConvertTo-Json -Depth 10
```

データがなければ両方とも200と`{"items":[]}`を返す。GET自体は収集や保存を開始しない。機能Dの追加後は起動時回収と自動収集が有効になるため、起動後に初回の履歴が増える。[定期実行の動作確認](定期実行の動作確認.md)の設定で自動収集を無効化して参照だけ確認することもできる。

入力不正をHTTPステータスと合わせて確認する例：

```powershell
curl.exe -i 'http://127.0.0.1:8080/api/v1/collection-runs?limit=0'
```

400とcode/messageのJSONになる。これは保存済み履歴のFAILEDとは別の、APIリクエストの失敗。

## 自動テスト

backendで実行する。

```powershell
# UT・MockMvc・既存の模擬HTTP試験。Dockerや実Coinbaseは不要
.\mvnw.cmd test

# 上記＋機能B/Cの実MySQL結合試験。Docker Desktopが必要
.\mvnw.cmd -Pmysql-it verify
```

mysql-itではCollectionPersistenceITとQueryApiITを実行する。どちらも専用コンテナ内のデータを初期化して試験し、終了後にコンテナを破棄する。開発用DB・volumeは使わない。CoinbaseLiveConnectionITは対象外であり、外部市場へ接続しない。

IntelliJでは各テストクラスの左の▶から実行できる。DB結合クラスはDocker Desktopが必要。実Coinbase用のVMオプションは不要。結果は通常試験がtarget/surefire-reports、DB結合がtarget/failsafe-reportsに生成される。

## 代表コードを追う順序

1. market/controller/LatestQuoteController.java：任意条件を検索Serviceへ渡す。
2. market/service/LatestQuoteQueryService.java：条件検証→Repository検索→公開DTO。
3. market/repository/MarketSnapshotRepository.java：対象ごとの最新選択とJOIN FETCH。
4. collection/service/CollectionRunQueryService.java：件数指定・履歴DTO・固定理由説明。
5. common/api/ApiExceptionHandler.java：400と、判別可能な接続障害503、内部障害500。

対応する試験：LatestQuoteQueryServiceTest、CollectionRunQueryServiceTest、QueryApiControllerTest、QueryApiIT。モック試験の保証範囲と実DB試験の保証範囲は、各クラス冒頭に記載している。
