# CIの実行と確認

## 目的と構成

PRの変更とmainへの取り込み結果を、GitHub Actions上の一時的なLinux環境で検証する。
定義は[ci.yml](../../.github/workflows/ci.yml)。このCIはデプロイや自動マージを行わない。

```text
作業ブランチで変更 → push → main向けPR
                             ├ Backend：通常テスト → JAR作成 → 実MySQL結合テスト
                             └ Frontend：npm ci → テスト → 型チェック・ビルド
                           → 結果と差分をレビュー → 手動でmainへ取り込み
```

| ジョブ | 実行環境・コマンド | 保証する範囲 |
|---|---|---|
| Backend | Ubuntu 24.04 / Temurin Java 21 / `./mvnw --batch-mode --no-transfer-progress -Pmysql-it -DfailIfNoTests=true verify` | 通常テスト、JAR作成、Testcontainersの実MySQL試験。DB制約・Tx・検索/API等の既存ケース |
| Frontend | Ubuntu 24.04 / Node.js 24 / `npm ci` → `npm test` → `npm run build` | Vueの既存テスト、型チェック、本番向けビルド |

2ジョブは並列。Mavenの通常テストを別ジョブで重複実行しない。MySQLサービスを別途定義せず、既存Testcontainersが試験専用コンテナを作成・破棄する。開発DBと.envは使用しない。実Coinbase通信試験はCI対象外で、`coinbase.live-test`は有効化しない。

## 実行タイミング

- mainを対象とするPRの作成・追加push・再オープン。
- mainへのpush（通常はPRの取り込み後）。
- Actions画面からの手動実行。定義がmainへ入るまでは、手動実行の入口が利用できない場合がある。

作業ブランチへのpushだけでは起動しない。PRとpushの二重実行を避けるための設計で、ローカル検証は継続する。同じPR/ブランチに新しい変更が来た場合は古いCIをキャンセルする。キャンセルされた旧実行を、最新コミットの検証成功とは扱わない。

## PRで確認する手順

1. 作業用ブランチからmainへのPRを作る。
2. PRのChecksでBackendとFrontendが起動したことを確認する。
3. Detailsから各ジョブのログを開き、テスト件数・失敗/エラー/スキップとビルド結果を確認する。
4. 失敗時は最初の原因を確認し、同じ作業ブランチで修正・pushする。テストの削除/無効化で成功扱いにしない。
5. 最新コミットの両ジョブが成功し、Files changedを確認できたらmainへの取り込みを判断する。

テスト件数と結果は各ジョブのログで確認する。成果物ストレージを利用しないため、XML・JAR・distはアップロードしない。生成・検証後は一時ランナーとともに破棄される。

CI成功はmainへの取り込みを技術的に禁止/許可する設定ではない。必須チェックやブランチ保護は、利用プランと運用を確認する別設定。CIだけでブラウザST/UATや公開環境の動作を保証しない。

## 権限・利用枠

- 標準のGitHub-hosted Linux runnerを使用。自己ホスト/大型runnerは使わない。
- トークンの権限は`contents: read`だけ。checkoutで認証情報を保存せず、Secretsやクラウド認証は不要。
- 有料利用しない方針。Publicリポジトリの標準ランナーを使用し、大型ランナー・成果物アップロード・依存キャッシュは使わない。依存は毎回ダウンロードするため、キャッシュ利用時より時間がかかる。旧実行キャンセルと実行時間上限も設定する。
- 初回CIより前にPublicへ変更する。Privateでは無料枠を消費するため、将来Privateへ戻す場合は、実行前にCI停止や料金条件を再確認する。有料利用/予算増額は設定しない。

料金・枠の値はアカウントと時期によるため固定値を記載しない。[Actionsの課金](https://docs.github.com/en/billing/concepts/product-billing/github-actions)、[予算と超過停止設定](https://docs.github.com/en/billing/how-tos/set-up-budgets)を参照。

## ローカルとの違い・検証状態

ローカルはWindows、CIはLinux。依存をクリーンインストールする`npm ci`はnode_modulesを作り直すため、開発PCではViteを停止するか検証用コピーで実行する。DB試験にはDocker Engineが必要で、Docker Desktopを用いる開発DBのCompose起動とは別である。

2026-11-13：PR #1とmainコミットc41c252のCI成功を確認。通常211件＋実MySQL54件、Frontend133件と双方ビルド成功。

2026-11-14：Railway準備でBackendジョブにルートの`docker build`を追加したPR #2と、mainコミット612cac3のCI成功を確認。通常JUnit211件、実MySQL54件、同梱配信5件、Frontend133件・型チェック/ビルド、最終Dockerイメージ構築が成功した。

- [PR #2のCI](https://github.com/Omelette4897/crypto-market-monitor/actions/runs/43447021340)
- [mainのCI](https://github.com/Omelette4897/crypto-market-monitor/actions/runs/43574900821)

Dockerfile内でVueビルド、通常JUnitと`web-bundle`配信試験、JREイメージ構築を検証する。イメージ送信・自動デプロイはしない。実際のRailway公開後確認は[本番配信・ST/UAT確認](本番配信・ST_UAT確認.md)を参照し、CI成功と本番受入れを区別する。

## 参照した公式情報

- [GitHub：MavenによるJavaのCI](https://docs.github.com/en/actions/tutorials/build-and-test-code/java-with-maven)
- [actions/checkout v7.0.1](https://github.com/actions/checkout/releases/tag/v7.0.1)
- [actions/setup-java v6.2.0](https://github.com/actions/setup-java/releases/tag/v6.2.0)
- [actions/setup-node v7.1.0](https://github.com/actions/setup-node/releases/tag/v7.1.0)


Actionは上記リリースの完全なコミットSHAへ固定する。更新時は公式の変更内容・action.yml・対応環境を確認し、SHAと版コメントを同時に更新する。
