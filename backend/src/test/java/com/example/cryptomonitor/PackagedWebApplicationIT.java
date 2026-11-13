package com.example.cryptomonitor;

import com.example.cryptomonitor.collection.service.CollectionPersistenceService;
import com.example.cryptomonitor.collection.service.CollectionRunQueryService;
import com.example.cryptomonitor.market.dto.LatestQuotesResponse;
import com.example.cryptomonitor.market.service.LatestQuoteQueryService;
import com.example.cryptomonitor.market.service.QuoteHistoryQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 試験区分：本番用Vue成果物・Spring MVC・prod設定の結合確認。
 * 実行：Vue distをtarget/frontend-distへ置き、Mavenの-Pweb-bundle verify。
 * 実体：Viteが生成したHTML/JS/CSSとMVC/Actuator。DB検索・起動時回復はモック。
 * 保証範囲外：実HTTP待受け、実MySQL、Railwayのネットワーク・終了処理・ST/UAT。
 * DB自動設定の除外はこの試験だけ。本番設定からDB接続を外すものではない。
 */
@DisplayName("本番配信 / Vue同梱・MVC結合確認")
@ActiveProfiles("prod")
@SpringBootTest(properties = {
    "market-monitor.collection.enabled=false",
    "DB_HOST=database.invalid", "DB_PORT=3306", "DB_NAME=test_database",
    "DB_USER=test_user", "DB_PASSWORD=test_only", "PORT=19090",
    "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
@AutoConfigureMockMvc
class PackagedWebApplicationIT {
    @MockitoBean private CollectionPersistenceService collectionPersistenceService;
    @MockitoBean private CollectionRunQueryService collectionRunQueryService;
    @MockitoBean private LatestQuoteQueryService latestQuoteQueryService;
    @MockitoBean private QuoteHistoryQueryService quoteHistoryQueryService;
    @Autowired private MockMvc mockMvc;
    @Autowired private ServerProperties serverProperties;

    /** 前提：prod・Railway相当のPORT。操作：実設定のバインド。期待：外部待受けと注入PORTを採用。 */
    @Test
    @DisplayName("DEPLOY-WEB-05：prodはRailwayのPORTと全インターフェース待受けを採用する")
    void bindsPlatformPortAndExternalAddress() {
        assertEquals(19090, serverProperties.getPort());
        assertTrue(serverProperties.getAddress().isAnyLocalAddress());
        assertEquals(org.springframework.boot.web.server.Shutdown.GRACEFUL, serverProperties.getShutdown());
    }

    /** 前提：実Vue成果物を同梱。操作：トップとHTMLへGET。期待：トップがindexへ接続しHTMLを配信。 */
    @Test
    @DisplayName("DEPLOY-WEB-01：トップから実ビルド済みVueのHTMLを配信する")
    void servesBundledVueIndex() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
        mockMvc.perform(get("/index.html")).andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/html"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"app\"")));
    }

    /** 前提：実HTML内のassets参照。操作：JS/CSSをGET。期待：両方存在しHTMLではなく各種別で配信。 */
    @Test
    @DisplayName("DEPLOY-WEB-02：HTMLが参照する実JS・CSSを同じサーバーで配信する")
    void servesAssetsReferencedByBuiltIndex() throws Exception {
        String indexHtml = new ClassPathResource("static/index.html").getContentAsString(StandardCharsets.UTF_8);
        var assetMatcher = Pattern.compile("(?:src|href)=\"(/assets/[^\"]+\\.(js|css))\"").matcher(indexHtml);
        boolean javascriptFound = false;
        boolean stylesheetFound = false;
        while (assetMatcher.find()) {
            boolean javascript = assetMatcher.group(2).equals("js");
            mockMvc.perform(get(assetMatcher.group(1))).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(javascript ? "text/javascript" : "text/css"));
            javascriptFound |= javascript;
            stylesheetFound |= !javascript;
        }
        assertTrue(javascriptFound && stylesheetFound, "ViteのJSとCSSの両方を実際に検査する");
    }

    /** 前提：検索結果0件。操作：既存APIへGET。期待：Vueへの書換えなしで既存JSON契約を維持。 */
    @Test
    @DisplayName("DEPLOY-WEB-03：Vueと既存の参照APIが同じMVC内で共存する")
    void preservesExistingJsonApiAlongsideStaticFiles() throws Exception {
        when(latestQuoteQueryService.findLatestQuotes(null, null)).thenReturn(new LatestQuotesResponse(List.of()));
        mockMvc.perform(get("/api/v1/quotes/latest")).andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.items").isEmpty());
        mockMvc.perform(get("/api/v1/not-present")).andExpect(status().isNotFound());
    }

    /** 前提：prod・DBなし。操作：health/envへGET。期待：状態だけ公開、環境・内部詳細は非公開。 */
    @Test
    @DisplayName("DEPLOY-WEB-04：prodの管理APIはhealthだけを詳細なしで公開する")
    void limitsProductionManagementEndpoints() throws Exception {
        mockMvc.perform(get("/actuator/health").accept("application/json")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.components").doesNotExist());
        mockMvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
    }
}
