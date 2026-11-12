package com.example.cryptomonitor;

import com.example.cryptomonitor.collection.service.CollectionPersistenceService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Springの設定を読み込み、管理用エンドポイントの最小限の公開範囲を確認するテスト。
 * DB/JPA/Flywayの自動設定を除外し、実MySQLの稼働状態に依存させない。
 * DB接続やマイグレーションの検証は別途必要で、このテストのUPはDB疎通の証明ではない。
 *
 * 試験区分：Spring設定とMockMvcの結合確認。HTTP待受け・実DBは使わない。
 * 実行方法：通常の .\mvnw.cmd test またはIntelliJのクラス実行。
 * ケース仕様：各メソッドの観点ID・前提/入力・操作・期待結果を正とする。
 * 共通データ：固定値はフィールド/準備メソッド、入力の組合せは各Source注釈を参照。
 * 保証範囲外・未実施：基盤の最小試験。価格収集から画面までのST/E2Eとは区別する。
 */
@DisplayName("基盤 / Spring・MVC結合確認：管理APIの公開範囲")
@SpringBootTest(properties = {
    "market-monitor.collection.enabled=false",
    "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
@AutoConfigureMockMvc
class ApplicationSmokeTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.example.cryptomonitor.market.service.QuoteHistoryQueryService quoteHistoryQueryService;

    // DBなしの基盤試験を維持する。実検索とJSON契約は機能Cの専用試験で確認する。
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.example.cryptomonitor.market.service.LatestQuoteQueryService latestQuoteQueryService;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.example.cryptomonitor.collection.service.CollectionRunQueryService collectionRunQueryService;

    // DBを除外する基盤試験では保存Beanも置換する。実際の保存は専用DB統合試験で確認する。
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.example.cryptomonitor.collection.service.CollectionPersistenceService collectionPersistenceService;

    // ポートを開いて通信せず、Spring MVCのリクエスト処理をテスト内で呼び出す道具。
    @Autowired
    private MockMvc mockMvc;

    /**
     * 観点：BASE-WEB-01 — ヘルスAPIが内部詳細を公開しない。
     * 前提・入力：DB/JPA/Flywayを除外し保存Beanをモック化したSpringコンテキスト。
     * 操作：MockMvcでGET /actuator/health、Accept=application/json。
     * 期待結果：200・JSON・status=UP、componentsなし。実DBの疎通成功を意味しない。
     */
    @DisplayName("BASE-WEB-01：ヘルスAPIが内部詳細を公開しない")
    @Test
    void healthEndpointWorksWithoutDisclosingInternalDetails() throws Exception {
        // Actuatorは独自メディア型も扱うため、ここではJSONを明示して検証する。
        mockMvc.perform(get("/actuator/health").accept("application/json"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.components").doesNotExist());
    }

    /**
     * 観点：BASE-WEB-02 — 環境情報の管理APIを公開しない。
     * 前提・入力：同じDBなしのSpringコンテキスト。
     * 操作：MockMvcでGET /actuator/env。
     * 期待結果：404。公開環境のネットワークや全管理機能を検査するSTではない。
     */
    @DisplayName("BASE-WEB-02：環境情報の管理APIを公開しない")
    @Test
    void managementEnvironmentEndpointIsNotExposed() throws Exception {
        mockMvc.perform(get("/actuator/env"))
            .andExpect(status().isNotFound());
    }
}
