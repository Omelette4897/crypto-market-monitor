# スクリーンショットの撮影・配置

2026-11-14の公開アプリを手動撮影した実画像を掲載済み。画像内容は加工せず、PC/スマホの内容と一致するようファイル名を整理した。アカウント・認証情報・個人のブックマーク等の映り込みがないことを目視確認済み。

| ファイル | 内容 | 画像サイズ |
|---|---|---|
| [dashboard-desktop.png](dashboard-desktop.png) | PC版のダッシュボード全体 | 1028 × 1948 px |
| [dashboard-mobile.png](dashboard-mobile.png) | スマートフォン表示（高密度画像） | 750 × 3388 px |

画像ピクセル幅とCSSの表示幅は異なる。スマホ画像はREADMEで幅375pxに表示する。PC画像には右側・下部の余白が多く含まれる。架空の価格・合成UI・旧レイアウトの画像で代用しない。

## 差し替え時の撮影手順

1. [公開デモ](https://app-production-d13c7e57.up.railway.app/)をPCで開く。幅1280〜1440px、表示倍率100%を目安にする。
2. Coinbase Exchange / BTC-USDを選択し、価格・チャートの取得完了を待つ。24時間分未蓄積でも実際の状態をそのまま撮影する。
3. ヘッダー・3市場一覧・最新価格・チャート・スプレッド統計が分かる範囲を撮影する。個人のブックマーク・アカウント・管理画面・通知を含めない。
4. `assets/screenshots/dashboard-desktop.png`へ保存する。無加工か、ブラウザ枠などの切取りだけにする。価格・警告・日時は書き換えない。
5. 差し替え後、ルートREADMEの画像・撮影日を確認する。画像リンクは次の形式を使う。

```markdown
![Coinbase BTC-USDの最新価格・24時間推移・スプレッド統計](assets/screenshots/dashboard-desktop.png)

撮影：YYYY-MM-DD。本番環境の実データ。価格・観測件数は撮影時点のものです。
```

## スマートフォン版（375px）

1. PCブラウザで公開アプリを開き、F12で開発者ツールを表示する。
2. Ctrl+Shift+Mで端末表示へ切り替え、Responsiveの幅を375pxにする。倍率は100%を基本とする。
3. ヘッダー・3市場一覧・詳細・チャートが実データで表示されるまで待つ。データや警告は書き換えない。
4. DevToolsのCtrl+Shift+Pから`Capture full size screenshot`を実行する。PC版も端末表示を解除して同じ方法でページ全体を撮影できる。ブラウザ枠やブックマークは撮影範囲へ含めない。
5. ダウンロードした画像を`assets/screenshots/dashboard-mobile.png`へ保存する。PC版は`dashboard-desktop.png`。ファイルを開き、アカウント・通知・管理画面・認証情報が入っていないことを確認する。

PC画像をメインにし、スマホ版はREADMEの折りたたみ補足に幅375pxで配置する。

```markdown
<details>
<summary>スマートフォン表示（375px）</summary>

<img src="assets/screenshots/dashboard-mobile.png" alt="スマートフォンでの市場データ表示" width="375">

</details>
```

スクリーンショットは撮影時点の状態であり、継続稼働や24時間の全観測を保証しない。
