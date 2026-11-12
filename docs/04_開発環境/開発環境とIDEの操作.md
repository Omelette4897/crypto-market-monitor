# 開発環境とIDEの操作

更新：2026-11-09。導入/起動確認済みの構成を使う現行手順。インストール時の画面操作や学習用の編集練習は過去資料へ分離した。

| 対象 | 開くフォルダ | 実行方法 |
|---|---|---|
| Java | IntelliJでbackend、またはそのpom.xml | JDK21、Maven Wrapper、作業ディレクトリbackend。CryptoMarketMonitorApplication.mainのRun/Debug |
| Vue | VS Codeでfrontend | 統合ターミナルのPowerShellでnpm.cmd run dev |
| DB | プロジェクトルートのcompose.yaml | Docker Desktop起動後、docker compose up -d --wait |

Maven/Nodeはビルドや起動を行う道具。IDEから実行しても同じソースとpom.xml/package.jsonを使う。JavaはWindows上のJVMで、DBはDocker DesktopのLinuxコンテナで動く。ローカルでNginxは使わない。

## フロント

VS Codeでfrontendを開き、統合ターミナルの現在地を確認して起動する。

```powershell
Get-Location
npm.cmd run dev
```

既定シェルはPowerShell。npm.cmdはnpm.ps1の実行ポリシー制限を避けるWindows用コマンド。npmスクリプト一覧のボタン起動を前提にしない。依存取得は初回/lock変更時等にnpm.cmd ci。停止はターミナルでCtrl+C。通常の表示確認はhttp://127.0.0.1:5173/。

```powershell
npm.cmd run typecheck
npm.cmd run build
```

Viteは保存時に画面へ反映する開発基盤。本番ではdistを配信し、開発サーバーを運用しない。

## Java

IntelliJでbackendをMavenプロジェクトとして開き、SDK21とMaven Wrapperを選ぶ。mainのRun/Debugで起動・IDEのStopで停止する。強制終了は正常な終了待機を通らない場合がある。Javaの変更は保存だけで反映される前提にせず、ビルド/再起動する。

backendのPowerShellからも実行できる。

```powershell
.\mvnw.cmd spring-boot:run
.\mvnw.cmd test
.\mvnw.cmd package
.\mvnw.cmd -Pmysql-it verify
```

testは通常テスト、packageは通常テストとJAR作成、mysql-itのverifyは専用MySQLを使う結合試験も行う。JUnitはIntelliJのクラス/メソッド左のRunからも実行できる。実Coinbase接続試験は別途明示指定する。

通常起動は回収後に価格収集を開始する。参照だけ確認するならCOLLECTION_ENABLED=falseを環境変数へ設定して再起動する。ただし回収は実施する。詳細は[定期実行](../05_テスト/定期実行の動作確認.md)を参照。

## DBと起動順

Docker DesktopとMySQLコンテナの起動は別。DBコンテナを起動した後にBoot、画面確認にはViteを起動する。同じBootやViteを重複起動しない。秘密情報の再設定やvolume初期化はこの手順に含めない。

プロジェクトルートでの設定準備、.envの扱い、具体的な移動先/停止操作は[ローカル起動手順](ローカル起動手順.md)を参照。
