# スクリーンショットの撮影・配置

現在は実画像未掲載。架空の価格・合成UI・旧レイアウトの画像で代用しない。

1. [公開デモ](https://app-production-d13c7e57.up.railway.app/)をPCで開く。幅1280〜1440px、表示倍率100%を目安にする。
2. Coinbase Exchange / BTC-USDを選択し、価格・チャートの取得完了を待つ。24時間分未蓄積でも実際の状態をそのまま撮影する。
3. ヘッダー・3市場一覧・最新価格・チャート・スプレッド統計が分かる範囲を撮影する。個人のブックマーク・アカウント・管理画面・通知を含めない。
4. `assets/screenshots/dashboard-desktop.png`へ保存する。無加工か、ブラウザ枠などの切取りだけにする。価格・警告・日時は書き換えない。
5. ルートREADMEの未掲載の案内を次の記述へ置き換える。実ファイルが存在してからリンクを追加する。

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

PC画像をメインにし、スマホ版はREADMEの折りたたみ補足に配置する。画像が存在してから、次を追加する。

```markdown
<details>
<summary>スマートフォン表示（375px）</summary>

![スマートフォンでの市場データ表示](assets/screenshots/dashboard-mobile.png)

</details>
```

2026-11-14：Computer UseはブラウザURLを安全に確認できずツール側で停止し、画像の取得・保存は未実施。生成画像や旧画像で代用せず、上記の手動撮影を残タスクとして扱う。スクリーンショットは撮影時点の状態であり、継続稼働や24時間の全観測を保証しない。
