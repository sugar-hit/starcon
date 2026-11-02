package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.AssistantModels.AssistantAnswer;
import com.starcon.platform.AssistantModels.AssistantCapabilities;
import com.starcon.platform.AssistantModels.AssistantIntentView;
import com.starcon.platform.AssistantModels.AssistantSessionView;
import com.starcon.tenancy.TenantTransactions;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 18.4.1.1「对话助手」的集成测试。
 *
 * <p><b>真的 PostgreSQL 16（Testcontainers）+ 真的 HTTP 上游（{@link AssistantTestStub}）。</b>
 *
 * <p>本类钉住三件事：
 *
 * <ol>
 *   <li><b>答案必须带明细链接</b>：{@code answered=true} 的答案一定带至少一条依据，
 *       依据带真表名 / 真字段名 / 事实引用 / 可点的明细路由；
 *   <li><b>确实去查了上游</b>：断言上游端点被真的调过（而不是本地常量）；
 *   <li><b>自由问句如实答「未接通」</b>：AI 供应商未接入 ⇒ {@code answered=false}，
 *       不返回任何看似成功的答案。
 * </ol>
 *
 * <p>租户隔离的三条断言在 {@link AssistantTenancyIntegrationTest}。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AssistantIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenantA = UUID.randomUUID();
  static AssistantTestStub stub;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，隔离断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role platform_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    try {
      stub = new AssistantTestStub();
      stub.start();
      stub.actor(
          "session-a",
          new AccessService.Access(
              UUID.randomUUID(),
              tenantA,
              "测试租户",
              UUID.randomUUID(),
              null,
              AssistantTestStub.permissions(),
              List.of(
                  new AccessService.Grant(
                      UUID.randomUUID(), "TEST", "ALL", AssistantTestStub.permissions()))));
      // 有身份但上游读不到（用来验证「读不到」不会被说成「没有数据」）。
      stub.actor(
          "session-down",
          new AccessService.Access(
              UUID.randomUUID(),
              tenantA,
              "测试租户",
              UUID.randomUUID(),
              null,
              AssistantTestStub.permissions(),
              List.of(
                  new AccessService.Grant(
                      UUID.randomUUID(), "TEST", "ALL", AssistantTestStub.permissions()))));
      stub.facts(
          "session-a",
          new AssistantTestStub.TenantFacts(
              "SHOP-A", "北美一店", "SKU-A", "蓝牙耳机", "CUST-A", "安琪", "SO-A"));
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "platform_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    r.add("IDENTITY_URL", () -> stub.url());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("REPORTING_URL", () -> stub.url());
    r.add("FINANCE_URL", () -> stub.url());
    r.add("TRADE_URL", () -> stub.url());
    r.add("CRM_URL", () -> stub.url());
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_assistant_evidence, platform_assistant_turn,"
              + " platform_assistant_session, platform_assistant_intent,"
              + " platform_app_audit_event, platform_app_quota_usage, platform_app_quota,"
              + " platform_app_ip_allowlist, platform_app_scope, platform_app_credential,"
              + " platform_app restart identity cascade");
    }
    stub.resetCalls();
  }

  // ================================================================ 夹具

  private AssistantAnswer ask(String bearer, String body) throws Exception {
    var response =
        http.perform(
                post("/platform/assistant/ask")
                    .header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(response, AssistantAnswer.class);
  }

  // ================================================================ 能力声明

  @Test
  void 能力声明里自由问句如实答未接通() throws Exception {
    var response =
        http.perform(get("/platform/assistant/capabilities").header("Authorization", "session-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var capabilities = json.readValue(response, AssistantCapabilities.class);
    var freeText =
        capabilities.capabilities().stream()
            .filter(c -> c.name().contains("自由问句"))
            .findFirst()
            .orElse(null);
    assertThat(freeText).as("自由问句那一格必须在能力声明里").isNotNull();
    assertThat(freeText.available()).as("本仓没有任何真实 LLM 适配器 ⇒ 必须如实答未接通").isFalse();
    assertThat(freeText.ownerModule()).isEqualTo("18.4");
    assertThat(freeText.note()).contains("未接入");
    assertThat(capabilities.gaps()).isNotEmpty();
  }

  @Test
  void 意图目录是受控的七条且每条都有真实数据源() throws Exception {
    var response =
        http.perform(get("/platform/assistant/intents").header("Authorization", "session-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var intents = json.readValue(response, AssistantIntentView[].class);
    assertThat(intents).hasSizeLessThanOrEqualTo(8);
    assertThat(intents.length).isGreaterThanOrEqualTo(5);
    for (var intent : intents) {
      assertThat(intent.sourceService()).isIn("reporting", "finance", "trade", "crm");
      assertThat(intent.sourceEndpoint()).startsWith("/");
      assertThat(intent.detailRoute()).startsWith("/");
      assertThat(intent.sourceCaliber()).isNotBlank();
      assertThat(intent.nextActions()).isNotEmpty();
      for (var action : intent.nextActions()) {
        assertThat(action.route()).as("建议的下一步必须指向真实存在的页面").startsWith("/");
      }
    }
  }

  // ================================================================ 意图查询

  @Test
  void 问上周哪个店利润最差能拿到带明细链接的答案() throws Exception {
    var answer = ask("session-a", "{\"question\":\"上周哪个店利润最差\"}");
    assertThat(answer.answered()).as("命中受控意图 ⇒ 必须出结论").isTrue();
    assertThat(answer.intentCode()).isEqualTo("SHOP_PROFIT_RANKING");
    assertThat(answer.matchMode()).isEqualTo("KEYWORD");
    assertThat(answer.evidence()).as("没有明细链接的答案不许出现").isNotEmpty();
    var first = answer.evidence().get(0);
    assertThat(first.factService()).isEqualTo("reporting");
    assertThat(first.factTable()).isEqualTo("reporting_profit_fact");
    assertThat(first.factField()).isNotBlank();
    assertThat(first.detailRoute()).startsWith("/analytics/profit/profit-report");
    assertThat(first.metricValue()).isNotNull();
    assertThat(answer.caliber()).contains("时间范围");
    assertThat(answer.nextActions()).isNotEmpty();
    // 🔴 **真的去查了上游**（不是本地常量）。
    assertThat(stub.calls("/reporting/profit-report/report")).isEqualTo(1);
    // 口径里必须写清窗口。
    assertThat(answer.periodFrom()).isNotNull();
    assertThat(answer.periodTo()).isNotNull();
  }

  @Test
  void 明细依据真的落了库且带真表名与明细路由() throws Exception {
    var answer = ask("session-a", "{\"question\":\"上周哪个店利润最差\"}");
    var rows =
        tenants.run(
            tenantA,
            () ->
                sql.fetch(
                    "select fact_service,fact_table,fact_field,fact_ref,detail_route,metric_value"
                        + " from platform_assistant_evidence where turn_id = ? order by seq",
                    answer.turnId()));
    assertThat(rows).as("答案的每条依据都要落在本模块自己的表里").isNotEmpty();
    for (var row : rows) {
      assertThat(row.get("fact_table", String.class)).isNotBlank();
      assertThat(row.get("fact_field", String.class)).isNotBlank();
      assertThat(row.get("detail_route", String.class)).startsWith("/");
      assertThat(row.get("metric_value", java.math.BigDecimal.class)).isNotNull();
    }
    assertThat(rows.get(0).get("fact_table", String.class)).isEqualTo("reporting_profit_fact");
  }

  @Test
  void 补货意图走库存下钻并给出可点的明细链接() throws Exception {
    var answer = ask("session-a", "{\"question\":\"哪些 SKU 该补货了\"}");
    assertThat(answer.intentCode()).isEqualTo("INVENTORY_REORDER");
    assertThat(answer.answered()).isTrue();
    assertThat(answer.evidence()).isNotEmpty();
    assertThat(answer.evidence().get(0).factTable()).isEqualTo("reporting_inventory_analysis_position");
    assertThat(answer.evidence().get(0).detailRoute()).startsWith("/analytics/inventory/analysis");
    assertThat(stub.calls("/reporting/inventory-analysis/drilldown")).isEqualTo(1);
  }

  @Test
  void 哪个sku卖得最好走商品维度销量口径() throws Exception {
    var answer = ask("session-a", "{\"question\":\"哪个 SKU 卖得最好\"}");
    assertThat(answer.intentCode()).isEqualTo("PRODUCT_TOP_SELLER");
    assertThat(answer.answered()).isTrue();
    assertThat(answer.evidence()).isNotEmpty();
    assertThat(answer.evidence().get(0).factTable()).isEqualTo("reporting_sales_trend_fact");
    assertThat(answer.evidence().get(0).metricValue()).isEqualByComparingTo("20");
    assertThat(answer.evidence().get(0).detailRoute()).startsWith("/analytics/sales/products");
  }

  @Test
  void 本月现金流走资金分析并标注净流入不是完整现金流() throws Exception {
    var answer = ask("session-a", "{\"question\":\"本月现金流怎么样\"}");
    assertThat(answer.intentCode()).isEqualTo("FUND_CASHFLOW");
    assertThat(answer.answered()).isTrue();
    assertThat(answer.periodCode()).isEqualTo("THIS_MONTH");
    assertThat(answer.evidence()).isNotEmpty();
    assertThat(answer.evidence().get(0).factTable()).isEqualTo("finance_receipt");
    assertThat(answer.caliber()).contains("不是完整现金流");
  }

  @Test
  void 走下坡商品名单走生命周期分层() throws Exception {
    var answer = ask("session-a", "{\"question\":\"最近有哪些商品在走下坡\"}");
    assertThat(answer.intentCode()).isEqualTo("LIFECYCLE_DECLINE");
    assertThat(answer.answered()).isTrue();
    assertThat(answer.evidence().get(0).factTable()).isEqualTo("reporting_lifecycle_segmentation_result");
    assertThat(answer.evidence().get(0).detailRoute()).startsWith("/analytics/products/lifecycle");
  }

  @Test
  void 高价值客户流失名单走crm只读端点() throws Exception {
    var answer = ask("session-a", "{\"question\":\"最近有哪些高价值客户在流失\"}");
    assertThat(answer.intentCode()).isEqualTo("CUSTOMER_CHURN");
    assertThat(answer.answered()).isTrue();
    assertThat(answer.evidence().get(0).factService()).isEqualTo("crm");
    assertThat(answer.evidence().get(0).factTable()).isEqualTo("crm_churn_alert");
    assertThat(answer.evidence().get(0).detailRoute()).startsWith("/crm/customer-lifecycle");
    assertThat(answer.evidence().get(0).caliberNote()).contains("不是模型置信度");
  }

  @Test
  void 待发货积压走trade只读端点() throws Exception {
    var answer = ask("session-a", "{\"question\":\"哪些订单卡在待发货\"}");
    assertThat(answer.intentCode()).isEqualTo("ORDER_PENDING_SHIPMENT");
    assertThat(answer.answered()).isTrue();
    assertThat(answer.evidence().get(0).factService()).isEqualTo("trade");
    assertThat(answer.evidence().get(0).factTable()).isEqualTo("shipment_pending_unit");
    assertThat(answer.evidence().get(0).detailRoute()).startsWith("/orders/fulfillment/pending-shipment");
    assertThat(answer.caliber()).contains("时点口径");
  }

  // ================================================================ 追问与上下文延续

  @Test
  void 追问第二名沿用上一轮的意图与口径() throws Exception {
    var first = ask("session-a", "{\"question\":\"上周哪个店利润最差\"}");
    var second =
        ask(
            "session-a",
            "{\"question\":\"那第二名呢\",\"sessionId\":\"" + first.sessionId() + "\"}");
    assertThat(second.sessionId()).isEqualTo(first.sessionId());
    assertThat(second.intentCode()).as("追问沿用上一轮的意图").isEqualTo(first.intentCode());
    assertThat(second.matchMode()).isEqualTo("FOLLOW_UP");
    assertThat(second.turnNo()).isEqualTo(first.turnNo() + 1);
    assertThat(second.evidence()).isNotEmpty();
    assertThat(second.evidence().get(0).factRef())
        .as("第二名 ⇒ 引用换成另一条事实")
        .isNotEqualTo(first.evidence().get(0).factRef());
    assertThat(second.periodFrom()).as("没提时间就沿用上一轮的口径").isEqualTo(first.periodFrom());
  }

  @Test
  void 追问换成上周只替换被显式修改的时间维度() throws Exception {
    var first = ask("session-a", "{\"question\":\"哪些 SKU 该补货了\"}");
    var second =
        ask(
            "session-a",
            "{\"question\":\"换成上周\",\"sessionId\":\"" + first.sessionId() + "\"}");
    assertThat(second.intentCode()).as("意图不变").isEqualTo("INVENTORY_REORDER");
    assertThat(second.periodCode()).isEqualTo("LAST_WEEK");
    // 「上周」= 上一自然周（周一至周日）⇒ 起点比「最近 30 天」的起点晚，但窗口更短。
    assertThat(second.periodFrom())
        .as("上周的起点比「最近 30 天」的起点晚（窗口更短，不是更早）")
        .isAfter(first.periodFrom());
    assertThat(second.periodTo()).isBefore(first.periodTo());
  }

  @Test
  void 会话历史带着每轮的明细链接() throws Exception {
    var answer = ask("session-a", "{\"question\":\"上周哪个店利润最差\"}");
    var response =
        http.perform(
                get("/platform/assistant/sessions/" + answer.sessionId())
                    .header("Authorization", "session-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var session = json.readValue(response, AssistantSessionView.class);
    assertThat(session.turns()).hasSize(1);
    assertThat(session.turns().get(0).evidence()).isNotEmpty();
    assertThat(session.turns().get(0).evidence().get(0).detailRoute()).startsWith("/");
    assertThat(session.intentCode()).isEqualTo("SHOP_PROFIT_RANKING");
  }

  // ================================================================ 自由问句（AI 未接通）

  @Test
  void 自由问句如实答未接通且不返回任何答案() throws Exception {
    var answer = ask("session-a", "{\"question\":\"帮我看看今天的汇率怎么样\"}");
    assertThat(answer.answered()).as("AI 通道未接通 ⇒ 不能出结论").isFalse();
    assertThat(answer.intentCode()).isEmpty();
    assertThat(answer.matchMode()).isEqualTo("FREE_TEXT");
    assertThat(answer.evidence()).as("没有依据 ⇒ 不许有答案").isEmpty();
    assertThat(answer.unavailableReason()).contains("未接入");
    assertThat(answer.ownerModule()).isEqualTo("18.4");
    // 落库的是「问过什么、为什么没答」，不是答案。
    var turn =
        tenants.run(
            tenantA,
            () ->
                sql.fetchOne(
                    "select match_mode,answer_summary from platform_assistant_turn where id = ?",
                    answer.turnId()));
    assertThat(turn.get("match_mode", String.class)).isEqualTo("FREE_TEXT");
    assertThat(turn.get("answer_summary", String.class)).contains("未接入");
    var evidenceCount =
        tenants.run(
            tenantA,
            () -> sql.fetch("select id from platform_assistant_evidence where turn_id = ?", answer.turnId()).size());
    assertThat(evidenceCount).isZero();
  }

  // ================================================================ 边界

  @Test
  void 请求体不接受租户与用户字段() throws Exception {
    http.perform(
            post("/platform/assistant/ask")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"上周哪个店利润最差\",\"tenantId\":\"" + tenantA + "\"}"))
        .andExpect(status().isBadRequest());
    http.perform(
            post("/platform/assistant/ask")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"上周哪个店利润最差\",\"userId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 空问题被拒() throws Exception {
    http.perform(
            post("/platform/assistant/ask")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"   \"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 上游读不到时如实报读不到而不是没有数据() throws Exception {
    http.perform(
            post("/platform/assistant/ask")
                .header("Authorization", "session-down")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"上周哪个店利润最差\"}"))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void 未认证的请求被拒() throws Exception {
    http.perform(
            post("/platform/assistant/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"上周哪个店利润最差\"}"))
        .andExpect(status().isUnauthorized());
  }
}
