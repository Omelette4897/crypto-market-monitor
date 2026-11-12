package com.example.cryptomonitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Bootアプリの起動点。IntelliJの実行ボタンと実行用JARは、このmainを入口にする。
 * Web・DB接続などの設定はapplication.ymlと依存ライブラリに基づいて組み立てられる。
 * 業務処理はここに書かず、今後作成する収集・保存・参照のクラスへ分離する。
 */
@SpringBootApplication
public class CryptoMarketMonitorApplication {
    /**
     * Springの管理環境を準備し、組み込みTomcatなどを起動する。
     * argsにはコマンドライン引数が入り、Spring Bootへそのまま渡す。
     */
    public static void main(String[] args) {
        SpringApplication.run(CryptoMarketMonitorApplication.class, args);
    }
}
