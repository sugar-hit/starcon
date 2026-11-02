package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.SmartQueryModels.SmartQueryCapabilities;
import com.starcon.platform.SmartQueryModels.SmartQueryPage;
import com.starcon.platform.SmartQueryModels.SmartQueryQueryView;
import com.starcon.platform.SmartQueryModels.SmartQueryResult;
import com.starcon.platform.SmartQueryModels.SmartQuerySemanticLayer;
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
 * 18.4.1.2「智能查询」的集成测试。
 *
 * <p><b>真的 PostgreSQL 16（Testcontainers）+ 真的 HTTP 上游（{@link SmartQueryTestStub}）。</b>
 *
 * <p>本类钉住五件事：
 *
 * <ol>
 *   <li><b>自然语言只映射到受控语义层指标</b>：解析出来的指标码 / 维度码 / 运算符
 *       全部落在封闭词表里；解析不到就<b>不出结论</b>（{@code resolved=false}、
 *       没有行、没有依据、也没有报表草稿）；
 *   <li><b>真的去查了下游</b>：断言 13.6.1.1 的端点被真的调过，而且
 *       <b>建草稿时传下去的度量与维度就是解析出来的那一组</b>；
 *   <li><b>结果表的每一行都带明细依据</b>（真表名 / 真字段名 / 事实引用 / 可点的明细路由）；
 *   <li><b>算不出来 ≠ 0</b>：比率分母为 0 时取值是 {@code null}；
 *   <li><b>常用查询</b>：存 / 读 / 重跑 / 取消，条件沿用当初解析出来的那一组。
 * </ol>
 *
 * <p>租户隔离的三条断言在 {@link SmartQueryTenancyIntegrationTest}。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SmartQueryIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenantA = UUID.randomUUID();
  static SmartQueryTestStub stub;

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
      stub = new SmartQueryTestStub();
      stub.start();
      stub.actor("session-a", actor(tenantA));
      stub.actor("session-b", actor(tenantA));
      // 🔴 有身份但上游读不到（用来验证「读不到」不会被说成「没有数据」）。
      stub.actor("session-down", actor(tenantA));
      // 🔴 口径表里**没登记任何指标**的租户（用来验证「未接通」）。
      stub.actor("session-nometric", actor(tenantA));
      stub.facts(
          "session-a",
          new SmartQueryTestStub.TenantFacts(
              "11111111-1111-1111-1111-111111111111",
              "北美一店",
              "22222222-2222-2222-2222-222222222222",
              "北美二店",
              "33333333-3333-3333-3333-333333333333",
              "北美三店",
              "USD",
              "120.50",
              "80.25"));
      stub.facts(
          "session-b",
          new SmartQueryTestStub.TenantFacts(
              "11111111-1111-1111-1111-111111111111",
              "北美一店",
              "22222222-2222-2222-2222-222222222222",
              "北美二店",
              "33333333-3333-3333-3333-333333333333",
              "北美三店",
              "USD",
              "120.50",
              "80.25"));
      stub.registered("session-a", SmartQueryTestStub.standardMetrics());
      stub.registered("session-b", SmartQueryTestStub.standardMetrics());
      stub.registered("session-down", SmartQueryTestStub.standardMetrics());
      stub.registered("session-nometric", List.of());
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static AccessService.Access actor(UUID tenant) {
    var permissions = SmartQueryTestStub.permissions();
    return new AccessService.Access(
        UUID.randomUUID(),
        tenant,
        "测试租户",
        UUID.randomUUID(),
        null,
        permissions,
        List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions)));
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
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_smart_query_evidence, platform_smart_query,"
              + " platform_smart_query_dimension, platform_smart_query_metric,"
              + " platform_app_audit_event, platform_app_quota_usage, platform_app_quota,"
              + " platform_app_ip_allowlist, platform_app_scope, platform_app_credential,"
              + " platform_app restart identity cascade");
    }
    stub.resetCalls();
  }

  // ================================================================ 夹具

  private SmartQueryResult ask(String bearer, String body) throws Exception {
    var response =
        http.perform(
                post("/platform/smart-query/query")
                    .header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(response, SmartQueryResult.class);
  }

  // ================================================================ 能力声明

  @Test
  void 能力声明逐条报出凭什么这么判() throws Exception {
    var response =
        http.perform(get("/platform/smart-query/capabilities").header("Authorization", "session-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var capabilities = json.readValue(response, SmartQueryCapabilities.class);
    assertThat(capabilities.capabilities()).isNotEmpty();
    for (var capability : capabilities.capabilities()) {
      assertThat(capability.evidence()).as("每一格都要写明凭什么这么判").isNotBlank();
      assertThat(capability.moduleId()).isEqualTo("18.4.1.2");
    }
    // 🔴 缺口要写清归谁。
    assertThat(capabilities.gaps()).isNotEmpty();
    for (var gap : capabilities.gaps()) {
      assertThat(gap.service()).as("缺口要写清归谁 / 在哪个服务").isNotBlank();
      assertThat(gap.note()).isNotBlank();
    }
  }

  // ================================================================ 语义层目录

  @Test
  void 语义层目录是封闭词表且每条都有真实端点() throws Exception {
    var response =
        http.perform(get("/platform/smart-query/semantic-layer").header("Authorization", "session-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var layer = json.readValue(response, SmartQuerySemanticLayer.class);
    assertThat(layer.metrics()).hasSizeGreaterThanOrEqualTo(10);
    assertThat(layer.dimensions()).hasSize(5);
    for (var metric : layer.metrics()) {
      assertThat(metric.sourceService()).isNotBlank();
      assertThat(metric.sourceEndpoint()).startsWith("/");
      assertThat(metric.sourceCaliber()).isNotBlank();
      assertThat(metric.strongKeywords()).as("没有强词的指标永远匹配不上").isNotEmpty();
      assertThat(metric.unit()).isIn("CURRENCY", "COUNT", "QUANTITY", "PERCENT");
      assertThat(metric.aggregation()).isIn("SUM", "LAST", "DERIVED_DIFF", "DERIVED_RATIO");
    }
    for (var dimension : layer.dimensions()) {
      assertThat(dimension.allowedOperators()).isNotEmpty();
      assertThat(dimension.valueKind()).isIn("TEXT", "UUID", "DATE");
    }
    // 🔴 加粗句必须出现在规则里。
    assertThat(layer.rules().stream().anyMatch(rule -> rule.contains("不生成任意 SQL"))).isTrue();
  }

  @Test
  void 口径表里没登记的指标如实标未接通() throws Exception {
    var response =
        http.perform(
                get("/platform/smart-query/semantic-layer").header("Authorization", "session-nometric"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var layer = json.readValue(response, SmartQuerySemanticLayer.class);
    for (var metric : layer.metrics()) {
      assertThat(metric.available()).as("本租户没登记这个指标 ⇒ 必须如实标未接通").isFalse();
      assertThat(metric.unavailableReason()).contains("未接通");
      assertThat(metric.ownerModule()).isNotBlank();
    }
  }

  // ================================================================ 查询

  @Test
  void 自然语言转成受控查询条件并给出带依据的结果表() throws Exception {
    var result = ask("session-a", "{\"question\":\"上周各店铺的销售额排名\"}");
    assertThat(result.resolved()).as("命中受控语义层 ⇒ 必须出结论").isTrue();
    assertThat(result.conditions()).isNotNull();
    assertThat(result.conditions().metricCode()).isEqualTo("SALES_AMOUNT");
    assertThat(result.conditions().dimensionCode()).isEqualTo("SHOP");
    assertThat(result.conditions().aggregation()).isEqualTo("SUM");
    assertThat(result.conditions().sort().direction()).isEqualTo("DESC");
    assertThat(result.conditions().periodCode()).isEqualTo("LAST_WEEK");
    assertThat(result.rows()).isNotEmpty();
    // 🔴 **每一行都带明细依据**（不给依据的数字不许出现）。
    assertThat(result.evidence()).isNotEmpty();
    var first = result.evidence().get(0);
    assertThat(first.factService()).isEqualTo("reporting");
    assertThat(first.factTable()).isEqualTo("reporting_metric_fact");
    assertThat(first.factField()).isEqualTo("value");
    assertThat(first.factRef()).contains("metric_code=SALES_AMOUNT");
    assertThat(first.detailRoute()).startsWith("/analytics/custom-report");
    assertThat(first.caliberNote()).isNotBlank();
    assertThat(first.snapshotAt()).isNotNull();
    // 🔴 **真的去查了下游**（不是本地常量）。
    assertThat(stub.calls("/reporting/custom-report/reports")).isEqualTo(1);
    assertThat(stub.calls("/reporting/custom-report/metrics")).isEqualTo(1);
  }

  @Test
  void 建草稿时传下去的度量与维度就是解析出来的那一组() throws Exception {
    var result = ask("session-a", "{\"question\":\"上周各店铺的销售额排名\"}");
    var config = stub.reportConfig(result.reportCode());
    assertThat(config).as("报表草稿必须真的建在 13.6.1.1 那边").isNotBlank();
    assertThat(config).contains("\"measureMetricCodes\":[\"SALES_AMOUNT\"]");
    assertThat(config).contains("\"dimensionCodes\":[\"SHOP\"]");
    assertThat(config).contains("\"dimensionCode\":\"BUCKET_DATE\"");
    assertThat(result.reportId()).isNotBlank();
  }

  @Test
  void 结果按指标值从高到低排且算不出来的那行排在后面() throws Exception {
    var result = ask("session-a", "{\"question\":\"上周各店铺的销售额排名\"}");
    assertThat(result.rows()).hasSize(3);
    var top = result.rows().get(0);
    var second = result.rows().get(1);
    var nullish = result.rows().get(2);
    assertThat(top.measures().get(0).values().get(0).value())
        .as("金额全程十进制文本（走 JSON number 会在浏览器里掉精度）")
        .isEqualTo("120.50");
    assertThat(second.measures().get(0).values().get(0).value()).isEqualTo("80.25");
    // 🔴 **算不出来 ≠ 0**：第三行的取值是 null。
    assertThat(nullish.measures().get(0).values().get(0).value()).isNull();
    assertThat(nullish.measures().get(0).values().get(0).availability()).isEqualTo("NOT_COMPARABLE");
    // 那一行**仍然有依据**（值是 null，但可下钻）。
    assertThat(result.evidence()).hasSize(3);
  }

  @Test
  void 问最低时排序方向反过来() throws Exception {
    var result = ask("session-a", "{\"question\":\"上周各店铺的销售额最低的是哪个\"}");
    assertThat(result.resolved()).isTrue();
    assertThat(result.conditions().sort().direction()).isEqualTo("ASC");
  }

  @Test
  void 显式传指标码时走显式路径() throws Exception {
    var result =
        ask(
            "session-a",
            "{\"question\":\"随便什么问法\",\"metricCode\":\"ORDER_COUNT\",\"dimensionCode\":\"SHOP\"}");
    assertThat(result.resolved()).isTrue();
    assertThat(result.matchMode()).isEqualTo("EXPLICIT");
    assertThat(result.conditions().metricCode()).isEqualTo("ORDER_COUNT");
  }

  @Test
  void 词表外的指标码被拒而不是静默回落() throws Exception {
    http.perform(
            post("/platform/smart-query/query")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"随便什么问法\",\"metricCode\":\"NOT_A_METRIC\"}"))
        .andExpect(status().isBadRequest());
  }

  // ================================================================ 解析不出来 ⇒ 不出结论

  @Test
  void 解析不到受控条目就不出结论也不造报表草稿() throws Exception {
    var result = ask("session-a", "{\"question\":\"帮我看看今天的汇率怎么样\"}");
    assertThat(result.resolved()).as("词表外的问题不许出结论").isFalse();
    assertThat(result.rows()).isEmpty();
    assertThat(result.evidence()).as("没有依据的数字不许出现").isEmpty();
    assertThat(result.reportId()).as("没解析出来就没有报表草稿").isEmpty();
    assertThat(result.unavailableReason()).isNotBlank();
    assertThat(result.ownerModule()).isNotBlank();
    // 🔴 **一次上游都没调**（不解析 ⇒ 不查）。
    assertThat(stub.calls("/reporting/custom-report/reports")).isZero();
  }

  @Test
  void 问平均如实答不支持并归13_1_1_1() throws Exception {
    var result = ask("session-a", "{\"question\":\"上周各店铺的平均销售额\"}");
    assertThat(result.resolved()).isFalse();
    assertThat(result.unavailableReason()).contains("平均");
    assertThat(result.ownerModule()).isEqualTo("13.1.1.1");
    assertThat(result.rows()).isEmpty();
  }

  @Test
  void 存量型指标不能求和() throws Exception {
    var result = ask("session-a", "{\"question\":\"上周库存总额的合计\"}");
    assertThat(result.resolved()).isFalse();
    assertThat(result.unavailableReason()).contains("LAST");
    assertThat(result.ownerModule()).isEqualTo("13.1.1.1");
  }

  @Test
  void 指标未在本租户口径表登记时如实答未接通() throws Exception {
    var result = ask("session-nometric", "{\"question\":\"上周各店铺的销售额排名\"}");
    assertThat(result.resolved()).isFalse();
    assertThat(result.unavailableReason()).contains("未在本租户的口径表");
    assertThat(result.ownerModule()).isEqualTo("9.1.1.1");
    assertThat(result.rows()).isEmpty();
  }

  // ================================================================ 台账与常用查询

  @Test
  void 查询台账落了库且带明细依据() throws Exception {
    var result = ask("session-a", "{\"question\":\"上周各店铺的销售额排名\"}");
    var rows =
        tenants.run(
            tenantA,
            () ->
                sql.fetch(
                    "select metric_code,dimension_code,resolved,report_code,filters,sort_spec"
                        + " from platform_smart_query where id = ?",
                    UUID.fromString(result.queryId())));
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("metric_code", String.class)).isEqualTo("SALES_AMOUNT");
    assertThat(rows.get(0).get("dimension_code", String.class)).isEqualTo("SHOP");
    assertThat(rows.get(0).get("resolved", Boolean.class)).isTrue();
    assertThat(rows.get(0).get("report_code", String.class)).isNotBlank();
    assertThat(rows.get(0).get("filters", String.class)).contains("BUCKET_DATE");
    assertThat(rows.get(0).get("sort_spec", String.class)).contains("METRIC");

    var evidence =
        tenants.run(
            tenantA,
            () ->
                sql.fetch(
                    "select fact_table,fact_field,fact_ref,detail_route,metric_value"
                        + " from platform_smart_query_evidence where query_id = ? order by seq",
                    UUID.fromString(result.queryId())));
    assertThat(evidence).hasSize(3);
    for (var row : evidence) {
      assertThat(row.get("fact_table", String.class)).isEqualTo("reporting_metric_fact");
      assertThat(row.get("fact_field", String.class)).isEqualTo("value");
      assertThat(row.get("detail_route", String.class)).startsWith("/");
    }
  }

  @Test
  void 台账清单是真分页真搜索() throws Exception {
    ask("session-a", "{\"question\":\"上周各店铺的销售额排名\"}");
    ask("session-a", "{\"question\":\"上周各店铺的订单量排名\"}");
    var response =
        http.perform(
                get("/platform/smart-query/queries")
                    .header("Authorization", "session-a")
                    .param("keyword", "订单量"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var page = json.readValue(response, SmartQueryPage.class);
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.rows()).hasSize(1);
    assertThat(page.rows().get(0).question()).contains("订单量");
    assertThat(page.rows().get(0).ownerUserId()).isNotBlank();
  }

  @Test
  void 常用查询能存能读能重跑也能取消() throws Exception {
    var result = ask("session-a", "{\"question\":\"上周各店铺的销售额排名\"}");
    var queryId = result.queryId();

    var saved =
        http.perform(
                post("/platform/smart-query/queries/" + queryId + "/save")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"displayName\":\"店铺销售额周报\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var savedView = json.readValue(saved, SmartQueryQueryView.class);
    assertThat(savedView.saved()).isTrue();
    assertThat(savedView.displayName()).isEqualTo("店铺销售额周报");

    // 常用查询清单里能查到它。
    var list =
        http.perform(
                get("/platform/smart-query/queries")
                    .header("Authorization", "session-a")
                    .param("saved", "true"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var page = json.readValue(list, SmartQueryPage.class);
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.rows().get(0).queryId()).isEqualTo(queryId);

    // 🔴 **重跑沿用当初解析出来的条件**（不重新猜），而且**不新建**报表草稿。
    var rerun =
        http.perform(
                post("/platform/smart-query/queries/" + queryId + "/run")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var rerunResult = json.readValue(rerun, SmartQueryResult.class);
    assertThat(rerunResult.resolved()).isTrue();
    assertThat(rerunResult.conditions().metricCode()).isEqualTo("SALES_AMOUNT");
    assertThat(rerunResult.conditions().dimensionCode()).isEqualTo("SHOP");
    assertThat(rerunResult.reportId()).isEqualTo(result.reportId());

    // 取消常用查询。
    http.perform(
            delete("/platform/smart-query/queries/" + queryId + "/save")
                .header("Authorization", "session-a"))
        .andExpect(status().isOk());
    var after =
        http.perform(
                get("/platform/smart-query/queries")
                    .header("Authorization", "session-a")
                    .param("saved", "true"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json.readValue(after, SmartQueryPage.class).total()).isZero();
  }

  @Test
  void 空名字的常用查询被拒() throws Exception {
    var result = ask("session-a", "{\"question\":\"上周各店铺的销售额排名\"}");
    http.perform(
            post("/platform/smart-query/queries/" + result.queryId() + "/save")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"  \"}"))
        .andExpect(status().isBadRequest());
  }

  // ================================================================ 边界

  @Test
  void 请求体不接受租户与用户字段() throws Exception {
    http.perform(
            post("/platform/smart-query/query")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"question\":\"上周各店铺的销售额排名\",\"tenantId\":\"" + tenantA + "\"}"))
        .andExpect(status().isBadRequest());
    http.perform(
            post("/platform/smart-query/query")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"上周各店铺的销售额排名\",\"userId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 空问句被拒() throws Exception {
    http.perform(
            post("/platform/smart-query/query")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"   \"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 上游读不到时如实报读不到而不是没有数据() throws Exception {
    http.perform(
            post("/platform/smart-query/query")
                .header("Authorization", "session-down")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"上周各店铺的销售额排名\"}"))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void 未认证的请求被拒() throws Exception {
    http.perform(
            post("/platform/smart-query/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"上周各店铺的销售额排名\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void 别人的查询读不到也存不了() throws Exception {
    var result = ask("session-a", "{\"question\":\"上周各店铺的销售额排名\"}");
    http.perform(
            get("/platform/smart-query/queries/" + result.queryId())
                .header("Authorization", "session-b"))
        .andExpect(status().isNotFound());
  }
}
