# GitHubリポジトリの案内設定

READMEの補助として、GitHubのAbout欄から概要・デモへ到達できるようにする。本書は設定案であり、管理画面の変更を実施済みとする記録ではない。

2026-11-14の公開API確認では、HomepageはデモURLが設定済み、Topicsは空。Descriptionの冒頭は`ava / Spring Boot`となっていたため、次の修正を推奨する。

| 項目 | 推奨値 |
|---|---|
| Description | Java / Spring Boot・MySQLによる暗号資産市場データの定期収集・保存・可視化Webアプリ |
| Website / Homepage | https://app-production-d13c7e57.up.railway.app/ |
| Topics | `java` `spring-boot` `mysql` `spring-data-jpa` `vue` `typescript` `docker` `testcontainers` `github-actions` `cryptocurrency` |

1. [リポジトリ](https://github.com/Omelette4897/crypto-market-monitor)のCodeタブを開く。
2. 右側Aboutの歯車を開く。
3. Description・Website・Topicsを上記の内容へ設定して保存する。
4. Codeタブへ戻り、概要・公開デモ・トピックリンクを確認する。

個人的な経歴や制作動機は公開設定へ含めない。`portfolio`は任意だが、ここではアプリの内容と実装技術を示すTopicsを優先する。Descriptionの更新はコード・本番設定・公開URLを変更しない。
