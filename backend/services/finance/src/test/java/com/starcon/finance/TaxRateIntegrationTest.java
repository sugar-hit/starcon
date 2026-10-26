package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.TaxRateModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
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
 * 12.6.1.1 税率配置的业务断言，跑在**真实 PostgreSQL 16** 上。
 *
 * <p>覆盖的是蓝图 12-finance.md 里那句**加粗句**与本模块的职责边界：
 *
 * <ul>
 *   <li>「<b>税率必须带生效时间，历史订单用当时税率，不能被新政策追溯修改</b>」
 *       ⇒ 税率带生效区间 + 版本链；改税率 = <b>追加新版本</b>（不是就地改）；
 *       已存在的版本行<b>连直连 SQL 都改不动</b>；生效区间不许重叠；
 *       按生效时间取税率<b>唯一命中</b>，<b>命中不到就报错</b>（不是 0%、不是最新一条）；</li>
 *   <li>「支持按店铺站点绑定默认税率与税码」⇒ 绑定带生效区间与版本链，换绑不追溯；</li>
 *   <li>「🔴 州销售税归 channel」⇒ 美国辖区在本模块<b>录不进去</b>，且页面只**只读引用** channel；</li>
 *   <li>「系统提供结构与辖区选项，不替用户决定税率」⇒ <b>税率零预置行</b>。</li>
 * </ul>
 *
 * <p><b>上游用真实的 HTTP 服务模拟</b>（{@code com.sun.net.httpserver}，与同服务其它模块逐字同构）：
 * identity 返回已认证成员关系，channel 返回受控辖区码表、店铺目录与州销售税规则。
 * 上游读不到时本模块一律 <b>503</b>，不用空集合冒充「没有辖区 / 没有店铺」。
 *
 * <p><b>断言风格与本仓其它模块一致</b>：业务规则与拒绝理由走**服务层**
 * （{@code assertThatThrownBy(...).hasStackTraceContaining(...)}），HTTP 层只断言**状态码**。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TaxRateIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID shopId = UUID.randomUUID();

  static final List<String> PERMISSIONS =
      List.of(
          "finance.tax_rate.read", "finance.tax_rate.create", "finance.tax_rate.update");

  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer channel;

  /** channel 的辖区码表响应（真实上游形状：欧盟国家 + 英国 + 美国联邦与州）。 */
  static volatile String jurisdictionPayload = "[]";

  /** channel 的店铺目录响应。 */
  static volatile String shopPayload = "[]";

  /** channel 的州销售税规则响应。 */
  static volatile String stateRulePayload = "{\"items\":[],\"total\":0,\"page\":0,\"size\":20}";

  /** channel 读不到时置 true（模拟上游 500）。 */
  static volatile boolean channelDown = false;

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("create role starcon_app nologin");
      s.execute("create role finance_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    actors.put(
        "session",
        new AccessService.Access(
            UUID.randomUUID(),
            tenant,
            "Test",
            UUID.randomUUID(),
            null,
            PERMISSIONS,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", PERMISSIONS))));

    try {
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            var access = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (access == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = json.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      identity.setExecutor(Executors.newFixedThreadPool(2));
      identity.start();

      channel = HttpServer.create(new InetSocketAddress(0), 0);
      channel.createContext(
          "/channel/tax-registration/jurisdictions",
          e -> respond(e, jurisdictionPayload));
      channel.createContext("/channel/shops", e -> respond(e, shopPayload));
      channel.createContext(
          "/channel/sales-tax/rate-rules", e -> respond(e, stateRulePayload));
      channel.setExecutor(Executors.newFixedThreadPool(2));
      channel.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** 统一的响应器：channel 挂掉时一律 500（模拟上游不可读）。 */
  static void respond(com.sun.net.httpserver.HttpExchange e, String payload) {
    try {
      if (channelDown) {
        e.sendResponseHeaders(500, -1);
        e.close();
        return;
      }
      byte[] body = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      e.getResponseHeaders().add("Content-Type", "application/json");
      e.sendResponseHeaders(200, body.length);
      e.getResponseBody().write(body);
      e.close();
    } catch (Exception ex) {
      e.close();
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "finance_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:" + channel.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired TaxRateService taxRates;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_tax_shop_binding, finance_tax_rate restart identity cascade");
    }
    channelDown = false;
    jurisdictionPayload =
        """
        {"items":[
          {"code":"EU-DE","name":"德国","region":"EUROPEAN_UNION","countryCode":"DE",
           "subdivisionCode":"","taxRegime":"VAT","vatRequired":true,"requiresEpr":true,
           "defaultFilingCycle":"MONTHLY","derivationScope":"COUNTRY"},
          {"code":"EU-FR","name":"法国","region":"EUROPEAN_UNION","countryCode":"FR",
           "subdivisionCode":"","taxRegime":"VAT","vatRequired":true,"requiresEpr":true,
           "defaultFilingCycle":"MONTHLY","derivationScope":"COUNTRY"},
          {"code":"GB","name":"英国","region":"UNITED_KINGDOM","countryCode":"GB",
           "subdivisionCode":"","taxRegime":"VAT","vatRequired":true,"requiresEpr":true,
           "defaultFilingCycle":"MONTHLY","derivationScope":"COUNTRY"},
          {"code":"US","name":"美国（联邦）","region":"UNITED_STATES","countryCode":"US",
           "subdivisionCode":"","taxRegime":"SALES_TAX","vatRequired":false,"requiresEpr":false,
           "defaultFilingCycle":"QUARTERLY","derivationScope":"SUBNATIONAL"},
          {"code":"US-CA","name":"美国 加利福尼亚州","region":"UNITED_STATES","countryCode":"US",
           "subdivisionCode":"US-CA","taxRegime":"SALES_TAX","vatRequired":true,"requiresEpr":false,
           "defaultFilingCycle":"QUARTERLY","derivationScope":"SUBNATIONAL"}],
         "total":5,"note":"channel 16.3.1.1 受控辖区码表"}
        """;
    shopPayload =
        ("[{\"id\":\"" + shopId + "\",\"code\":\"SHOP-DE\",\"name\":\"德国店\",\"platformCode\":\"AMAZON\","
                + "\"platformName\":\"亚马逊\",\"status\":\"ACTIVE\"}]");
    stateRulePayload =
        """
        {"items":[{"id":"11111111-1111-4111-8111-111111111111","stateCode":"US-CA",
                   "stateName":"美国 加利福尼亚州","ruleVersion":2,"effectiveFrom":"2026-01-01",
                   "effectiveTo":null,"baseRate":0.0725,"thresholdAmount":1000000.000000,
                   "exemptionAmount":0.000000,"marketplaceFacilitator":true,"filingCycle":"QUARTERLY",
                   "status":"ACTIVE","note":"channel 维护的州规则"}],
         "total":1,"page":0,"size":20,"note":"channel 16.3.2.3"}
        """;
  }

  /** 在**租户事务里**跑一段工作（与生产同一条路径：RLS 靠它隔离）。 */
  <T> T as(java.util.function.Supplier<T> work) {
    return tenants.run(tenant, work);
  }

  /** 登记一版税率。 */
  FinanceTaxRateRow upsertRate(
      String taxType, String jurisdiction, String taxCode, String rate, String effectiveFrom) {
    return as(
        () ->
            taxRates.upsertRate(
                new FinanceTaxRateInput(
                    taxType, jurisdiction, taxCode, new BigDecimal(rate),
                    OffsetDateTime.parse(effectiveFrom), "测试配置"),
                actors.get("session"),
                "session"));
  }

  /** 登记一条店铺绑定。 */
  FinanceTaxBindingRow upsertBinding(
      String taxType, String jurisdiction, String taxCode, String effectiveFrom) {
    return as(
        () ->
            taxRates.upsertBinding(
                new FinanceTaxBindingInput(
                    shopId, "SHOP-DE", "德国店", taxType, jurisdiction, taxCode,
                    OffsetDateTime.parse(effectiveFrom), "测试绑定"),
                actors.get("session"),
                "session"));
  }

  // ==========================================================================
  // 能力声明 / 选项 / 零预置
  // ==========================================================================
  @Test
  void 能力声明把四个税种摆出来且不替用户决定税率() {
    var capabilities = as(() -> taxRates.capabilities());
    assertThat(capabilities.capabilities())
        .as("能力声明必须说清「税率零预置行」")
        .anyMatch(c -> (c.basis() + c.detail()).contains("零预置行"));
    assertThat(capabilities.capabilities())
        .as("必须说清州销售税归 channel 16.3.2.3")
        .anyMatch(c -> c.ownerModule().equals("16.3.2.3"));
    assertThat(capabilities.downstreamGaps())
        .as("12.6.1.2 / 9 订单 / 12.6.2.1 必须如实记为未交付")
        .anyMatch(g -> g.module().equals("12.6.1.2") && !g.available())
        .anyMatch(g -> g.module().equals("9") && !g.available())
        .anyMatch(g -> g.module().equals("12.6.2.1") && !g.available());

    var options = as(() -> taxRates.options());
    assertThat(options.taxTypes())
        .as("蓝图点名的四个税种必须都作为选项摆出来")
        .extracting(FinanceTaxRateTypeOption::value)
        .containsExactlyInAnyOrder("SALES_TAX", "VAT", "IOSS", "WITHHOLDING");
    assertThat(options.taxTypes()).allMatch(o -> o.basis() != null && !o.basis().isBlank());
  }

  @Test
  void 系统不预置任何一行税率() {
    var page = as(() -> taxRates.rates(null, null, null, null, null, 0, 50));
    assertThat(page.items()).as("系统不得替用户预置税率").isEmpty();
    assertThat(page.total()).isZero();
    assertThat(page.activeCount()).isZero();
    assertThat(as(() -> taxRates.bindings(null, null, null, null, null, 0, 50)).items()).isEmpty();
  }

  // ==========================================================================
  // 版本链与生效区间
  // ==========================================================================
  @Test
  void 登记税率真的落库() {
    var row = upsertRate("VAT", "EU-DE", "STANDARD", "0.19", "2026-01-01T00:00:00Z");
    assertThat(row.version()).isEqualTo(1);
    assertThat(row.status()).isEqualTo("ACTIVE");
    assertThat(row.effectiveTo()).isNull();
    assertThat(row.supersedesId()).isNull();
    assertThat(row.rate()).isEqualByComparingTo("0.19");
    assertThat(row.jurisdictionName()).isEqualTo("德国");
    assertThat(row.countryCode()).isEqualTo("DE");

    var stored =
        as(
            () ->
                sql.fetchOne(
                    "select rate, jurisdiction_name, country_code, status from finance_tax_rate"));
    assertThat(stored.get("rate", BigDecimal.class)).isEqualByComparingTo("0.19");
    assertThat(stored.get("jurisdiction_name", String.class)).isEqualTo("德国");
  }

  @Test
  void 改税率是追加新版本而不是就地改() {
    var first = upsertRate("VAT", "EU-DE", "STANDARD", "0.19", "2026-01-01T00:00:00Z");
    var second = upsertRate("VAT", "EU-DE", "STANDARD", "0.17", "2026-07-01T00:00:00Z");

    assertThat(second.version()).isEqualTo(2);
    assertThat(second.supersedesId()).isEqualTo(first.id());
    assertThat(second.rate()).isEqualByComparingTo("0.17");

    var versions = as(() -> taxRates.versions("VAT", "EU-DE", "STANDARD"));
    assertThat(versions).hasSize(2);
    var old = versions.stream().filter(v -> v.version() == 1).findFirst().orElseThrow();
    assertThat(old.status()).as("旧版本必须被收口").isEqualTo("SUPERSEDED");
    assertThat(old.effectiveTo()).as("旧版本必须被收口生效区间").isNotNull();
    assertThat(old.rate()).as("旧版本的税率一字未改").isEqualByComparingTo("0.19");
    assertThat(old.effectiveFrom()).isEqualTo(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
  }

  @Test
  void 直连sql也改不动历史版本的税率() {
    var first = upsertRate("VAT", "EU-DE", "STANDARD", "0.19", "2026-01-01T00:00:00Z");
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "update finance_tax_rate set rate = 0.01 where tenant_id = ? and id = ?",
                          tenant,
                          first.id());
                      return null;
                    }))
        .as("历史版本的税率必须不可改写 —— 改了历史订单就没法解释")
        .hasStackTraceContaining("历史版本不可改写");
  }

  @Test
  void 直连sql也改不动历史版本的生效起点() {
    var first = upsertRate("VAT", "EU-DE", "STANDARD", "0.19", "2026-01-01T00:00:00Z");
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "update finance_tax_rate set effective_from = ?::timestamptz"
                              + " where tenant_id = ? and id = ?",
                          OffsetDateTime.parse("2025-01-01T00:00:00Z"),
                          tenant,
                          first.id());
                      return null;
                    }))
        .as("生效起点一旦存在就不许被追溯挪动")
        .hasStackTraceContaining("历史版本不可改写");
  }

  @Test
  void 直连sql也改不动已收口的失效时间() {
    var first = upsertRate("VAT", "EU-DE", "STANDARD", "0.19", "2026-01-01T00:00:00Z");
    upsertRate("VAT", "EU-DE", "STANDARD", "0.17", "2026-07-01T00:00:00Z");
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "update finance_tax_rate set effective_to = ?::timestamptz"
                              + " where tenant_id = ? and id = ?",
                          OffsetDateTime.parse("2026-03-01T00:00:00Z"),
                          tenant,
                          first.id());
                      return null;
                    }))
        .as("失效时间一经收口不可再改")
        .hasStackTraceContaining("失效时间一经收口不可再改");
  }

  @Test
  void 直连sql也写不出跳号的版本() {
    upsertRate("VAT", "EU-DE", "STANDARD", "0.19", "2026-01-01T00:00:00Z");
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "insert into finance_tax_rate(id,tenant_id,tax_type,jurisdiction_code,"
                              + " jurisdiction_name,country_code,tax_code,rate,effective_from,status,"
                              + " version,created_at,updated_at)"
                              + " values(?,?,'VAT','EU-DE','德国','DE','STANDARD',0.17,"
                              + " ?::timestamptz,'ACTIVE',7,?::timestamptz,?::timestamptz)",
                          UUID.randomUUID(),
                          tenant,
                          OffsetDateTime.parse("2026-07-01T00:00:00Z"),
                          OffsetDateTime.now(ZoneOffset.UTC),
                          OffsetDateTime.now(ZoneOffset.UTC));
                      return null;
                    }))
        .as("版本号必须递增")
        .hasStackTraceContaining("版本号必须递增");
  }

  @Test
  void 直连sql也写不出生效区间重叠的两版() {
    var first = upsertRate("VAT", "EU-DE", "STANDARD", "0.19", "2026-01-01T00:00:00Z");
    // 直接插一条生效区间与第一版重叠的版本 —— 版本链守卫全部满足（版本号 2 + 接在第一版之后），
    // 只剩「区间不许重叠」这一条能挡住它。
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "insert into finance_tax_rate(id,tenant_id,tax_type,jurisdiction_code,"
                              + " jurisdiction_name,country_code,tax_code,rate,effective_from,effective_to,"
                              + " status,version,supersedes_id,created_at,updated_at)"
                              + " values(?,?,'VAT','EU-DE','德国','DE','STANDARD',0.17,"
                              + " ?::timestamptz,?::timestamptz,'SUPERSEDED',2,?,"
                              + " ?::timestamptz,?::timestamptz)",
                          UUID.randomUUID(),
                          tenant,
                          OffsetDateTime.parse("2026-03-01T00:00:00Z"),
                          OffsetDateTime.parse("2026-09-01T00:00:00Z"),
                          first.id(),
                          OffsetDateTime.now(ZoneOffset.UTC),
                          OffsetDateTime.now(ZoneOffset.UTC));
                      return null;
                    }))
        .as("生效区间不许重叠 —— 否则「某一时点命中哪一版」就不唯一了")
        .hasStackTraceContaining("生效区间不许重叠");
  }

  @Test
  void 新版本的生效时间必须晚于上一版() {
    upsertRate("VAT", "EU-DE", "STANDARD", "0.19", "2026-01-01T00:00:00Z");
    assertThatThrownBy(
            () -> upsertRate("VAT", "EU-DE", "STANDARD", "0.17", "2025-12-01T00:00:00Z"))
        .as("生效起点必须严格递进 —— 否则历史订单会被追溯改写")
        .hasStackTraceContaining("必须晚于上一版本");
  }

  // ==========================================================================
  // 按生效时间取税率
  // ==========================================================================
  @Test
  void 按生效时间取税率唯一命中当时那一版() {
    upsertRate("VAT", "EU-DE", "STANDARD", "0.19", "2026-01-01T00:00:00Z");
    upsertRate("VAT", "EU-DE", "STANDARD", "0.17", "2026-07-01T00:00:00Z");

    var before = as(() -> taxRates.resolve("VAT", OffsetDateTime.parse("2026-03-01T00:00:00Z"), null, "EU-DE", "STANDARD"));
    assertThat(before.rate()).as("3 月要用 1 月那一版").isEqualByComparingTo("0.19");

    var after = as(() -> taxRates.resolve("VAT", OffsetDateTime.parse("2026-09-01T00:00:00Z"), null, "EU-DE", "STANDARD"));
    assertThat(after.rate()).as("9 月要用 7 月那一版").isEqualByComparingTo("0.17");

    var boundary = as(() -> taxRates.resolve("VAT", OffsetDateTime.parse("2026-07-01T00:00:00Z"), null, "EU-DE", "STANDARD"));
    assertThat(boundary.rate()).as("生效起点是新一版的起点（左闭右开）").isEqualByComparingTo("0.17");
  }

  @Test
  void 命中不到就报错而不是退回最新一条或按0处理() {
    upsertRate("VAT", "EU-DE", "STANDARD", "0.19", "2026-01-01T00:00:00Z");

    assertThatThrownBy(
            () ->
                as(
                    () ->
                        taxRates.resolve(
                            "VAT", OffsetDateTime.parse("2025-06-01T00:00:00Z"), null, "EU-DE", "STANDARD")))
        .as("生效之前没有税率 ⇒ 必须报错")
        .hasStackTraceContaining("这一刻没有生效税率");

    assertThatThrownBy(
            () ->
                as(
                    () ->
                        taxRates.resolve(
                            "VAT", OffsetDateTime.parse("2026-06-01T00:00:00Z"), null, "EU-DE", "NO-SUCH-CODE")))
        .as("税码不存在 ⇒ 必须报错")
        .hasStackTraceContaining("这一刻没有生效税率");
  }

  @Test
  void 按店铺站点取税率走绑定那条路() {
    upsertRate("VAT", "EU-DE", "STANDARD", "0.19", "2026-01-01T00:00:00Z");
    upsertBinding("VAT", "EU-DE", "STANDARD", "2026-01-01T00:00:00Z");

    var hit =
        as(
            () ->
                taxRates.resolve(
                    "VAT", OffsetDateTime.parse("2026-03-01T00:00:00Z"), shopId, "EU-DE", null));
    assertThat(hit.resolvedBy()).isEqualTo("SHOP_BINDING");
    assertThat(hit.shopId()).isEqualTo(shopId);
    assertThat(hit.taxCode()).isEqualTo("STANDARD");
    assertThat(hit.rate()).isEqualByComparingTo("0.19");

    assertThatThrownBy(
            () ->
                as(
                    () ->
                        taxRates.resolve(
                            "VAT", OffsetDateTime.parse("2026-03-01T00:00:00Z"), shopId, "EU-FR", null)))
        .as("该店铺在法国没有绑定 ⇒ 必须报错，不能当成 0%")
        .hasStackTraceContaining("没有生效的");
  }

  // ==========================================================================
  // 店铺绑定
  // ==========================================================================
  @Test
  void 绑定落库且带生效区间与版本() {
    upsertRate("VAT", "EU-DE", "STANDARD", "0.19", "2026-01-01T00:00:00Z");
    var binding = upsertBinding("VAT", "EU-DE", "STANDARD", "2026-01-01T00:00:00Z");

    assertThat(binding.version()).isEqualTo(1);
    assertThat(binding.status()).isEqualTo("ACTIVE");
    assertThat(binding.taxCode()).isEqualTo("STANDARD");
    assertThat(binding.jurisdictionCode()).isEqualTo("EU-DE");
    assertThat(binding.rate()).as("绑定行带上当时那条税档的税率").isEqualByComparingTo("0.19");
  }

  @Test
  void 换绑是追加新版本而不是就地改() {
    upsertRate("VAT", "EU-DE", "STANDARD", "0.19", "2026-01-01T00:00:00Z");
    upsertRate("VAT", "EU-DE", "REDUCED", "0.07", "2026-01-01T00:00:00Z");
    var first = upsertBinding("VAT", "EU-DE", "STANDARD", "2026-01-01T00:00:00Z");
    var second = upsertBinding("VAT", "EU-DE", "REDUCED", "2026-07-01T00:00:00Z");

    assertThat(second.version()).isEqualTo(2);
    assertThat(second.supersedesId()).isEqualTo(first.id());
    assertThat(second.taxCode()).isEqualTo("REDUCED");

    var versions = as(() -> taxRates.bindingVersions(shopId, "VAT", "EU-DE"));
    assertThat(versions).hasSize(2);
    var old = versions.stream().filter(v -> v.version() == 1).findFirst().orElseThrow();
    assertThat(old.status()).isEqualTo("SUPERSEDED");
    assertThat(old.taxCode()).as("旧绑定的税码一字未改").isEqualTo("STANDARD");
  }

  @Test
  void 直连sql也改不动历史绑定() {
    upsertRate("VAT", "EU-DE", "STANDARD", "0.19", "2026-01-01T00:00:00Z");
    var binding = upsertBinding("VAT", "EU-DE", "STANDARD", "2026-01-01T00:00:00Z");
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "update finance_tax_shop_binding set tax_code = 'REDUCED'"
                              + " where tenant_id = ? and id = ?",
                          tenant,
                          binding.id());
                      return null;
                    }))
        .as("换绑必须追加新版本，不能就地改历史行")
        .hasStackTraceContaining("历史版本不可改写");
  }

  @Test
  void 绑定到没有税率的税档被拒() {
    assertThatThrownBy(() -> upsertBinding("VAT", "EU-DE", "NO-SUCH-CODE", "2026-01-01T00:00:00Z"))
        .as("绑到一个没有税率的税档上 ⇒ 取税率时必然落空")
        .hasStackTraceContaining("还没有税码");
  }

  @Test
  void 直连sql也绑不出没有税率的税档() {
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "insert into finance_tax_shop_binding(id,tenant_id,shop_id,shop_code,shop_name,"
                              + " tax_type,jurisdiction_code,tax_code,effective_from,status,version,"
                              + " created_at,updated_at)"
                              + " values(?,?,?,'SHOP-DE','德国店','VAT','EU-DE','GHOST',"
                              + " ?::timestamptz,'ACTIVE',1,?::timestamptz,?::timestamptz)",
                          UUID.randomUUID(),
                          tenant,
                          shopId,
                          OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                          OffsetDateTime.now(ZoneOffset.UTC),
                          OffsetDateTime.now(ZoneOffset.UTC));
                      return null;
                    }))
        .as("数据层也要挡住「绑到不存在的税档」")
        .hasStackTraceContaining("还没有任何税率记录");
  }

  // ==========================================================================
  // 辖区边界（州销售税归 channel）
  // ==========================================================================
  @Test
  void 美国辖区录不进去因为州销售税归channel() {
    assertThatThrownBy(() -> upsertRate("SALES_TAX", "US-CA", "STATE", "0.0725", "2026-01-01T00:00:00Z"))
        .as("美国州级销售税归 channel 16.3.2.3，本模块不重复建表")
        .hasStackTraceContaining("16.3.2.3");

    var options = as(() -> taxRates.jurisdictionOptions("session"));
    var ca = options.items().stream().filter(j -> j.code().equals("US-CA")).findFirst().orElseThrow();
    assertThat(ca.usableForTaxRate()).isFalse();
    assertThat(ca.unusableReason()).contains("16.3.2.3");
    var de = options.items().stream().filter(j -> j.code().equals("EU-DE")).findFirst().orElseThrow();
    assertThat(de.usableForTaxRate()).isTrue();
  }

  @Test
  void 自由文本辖区码被拒() {
    assertThatThrownBy(() -> upsertRate("VAT", "XX-NOPE", "STANDARD", "0.19", "2026-01-01T00:00:00Z"))
        .as("辖区码不是自由文本 —— 必须落在上游受控码表里")
        .hasStackTraceContaining("不在上游受控辖区码表里");
  }

  @Test
  void channel读不到时如实说读不到而不是当成没有辖区() {
    channelDown = true;
    assertThatThrownBy(() -> as(() -> taxRates.jurisdictionOptions("session")))
        .as("上游读不到必须 503 并写明「读不到」")
        .hasStackTraceContaining("读不到");
    assertThatThrownBy(() -> upsertRate("VAT", "EU-DE", "STANDARD", "0.19", "2026-01-01T00:00:00Z"))
        .as("读不到辖区码表就不能落库 —— 绝不接受自由文本辖区码")
        .hasStackTraceContaining("读不到");
  }

  @Test
  void 州销售税只读引用channel且标明来源模块() {
    var rules = as(() -> taxRates.stateSalesTax("session", null, null, 0, 20));
    assertThat(rules.available()).isTrue();
    assertThat(rules.sourceModule()).isEqualTo("16.3.2.3");
    assertThat(rules.sourceService()).isEqualTo("channel");
    assertThat(rules.items()).hasSize(1);
    assertThat(rules.items().get(0).stateCode()).isEqualTo("US-CA");
    assertThat(rules.items().get(0).baseRate()).isEqualByComparingTo("0.0725");

    channelDown = true;
    assertThatThrownBy(() -> as(() -> taxRates.stateSalesTax("session", null, null, 0, 20)))
        .as("channel 读不到必须如实报「读不到」，不能用空集合冒充「没有州销售税」")
        .hasStackTraceContaining("读不到");
  }

  // ==========================================================================
  // 校验与精度
  // ==========================================================================
  @Test
  void 税率必须是小数率且精度不超过六位() {
    assertThatThrownBy(() -> upsertRate("VAT", "EU-DE", "STANDARD", "19", "2026-01-01T00:00:00Z"))
        .as("税率是 0 ~ 1 的小数率，19 意味着 1900%")
        .hasStackTraceContaining("必须在 0 ~ 1 之间");
    assertThatThrownBy(() -> upsertRate("VAT", "EU-DE", "STANDARD", "0.1234567", "2026-01-01T00:00:00Z"))
        .as("精度超过 6 位会被静默四舍五入 ⇒ 必须让用户自己先定好")
        .hasStackTraceContaining("最多 6 位小数");
  }

  @Test
  void 税率列是numeric且不是浮点() {
    upsertRate("VAT", "EU-DE", "STANDARD", "0.19", "2026-01-01T00:00:00Z");
    var column =
        as(
            () ->
                sql.fetchOne(
                    "select data_type, numeric_precision, numeric_scale from information_schema.columns"
                        + " where table_name = 'finance_tax_rate' and column_name = 'rate'"));
    assertThat(column.get("data_type", String.class)).isEqualTo("numeric");
    assertThat(column.get("numeric_precision", Integer.class)).isEqualTo(9);
    assertThat(column.get("numeric_scale", Integer.class)).isEqualTo(6);
  }

  @Test
  void 时间列是timestamptz且带时区夹具写回零漂移() {
    // 🔴 用 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s ⇒ 这里断言**写回的值**。
    var moment = OffsetDateTime.parse("2026-03-01T10:30:00+08:00");
    var row = upsertRate("VAT", "EU-DE", "STANDARD", "0.19", moment.toString());

    var stored =
        as(
            () ->
                sql.fetchOne(
                    "select effective_from from finance_tax_rate where tenant_id = ? and id = ?",
                    tenant,
                    row.id()));
    assertThat(stored.get("effective_from", OffsetDateTime.class).toInstant())
        .as("+08:00 夹具必须原样写回，零漂移")
        .isEqualTo(moment.toInstant());

    var column =
        as(
            () ->
                sql.fetchOne(
                    "select data_type from information_schema.columns"
                        + " where table_name = 'finance_tax_rate' and column_name = 'effective_from'"));
    assertThat(column.get("data_type", String.class)).isEqualTo("timestamp with time zone");
  }

  // ==========================================================================
  // 列表 / 版本对比
  // ==========================================================================
  @Test
  void 列表是真分页真筛选() {
    upsertRate("VAT", "EU-DE", "STANDARD", "0.19", "2026-01-01T00:00:00Z");
    upsertRate("VAT", "EU-FR", "STANDARD", "0.20", "2026-01-01T00:00:00Z");
    upsertRate("IOSS", "EU-DE", "STANDARD", "0.19", "2026-01-01T00:00:00Z");

    var all = as(() -> taxRates.rates(null, null, null, null, null, 0, 50));
    assertThat(all.total()).isEqualTo(3);
    assertThat(all.items()).hasSize(3);

    var filtered = as(() -> taxRates.rates("VAT", "EU-DE", null, null, null, 0, 50));
    assertThat(filtered.total()).isEqualTo(1);
    assertThat(filtered.items().get(0).jurisdictionCode()).isEqualTo("EU-DE");

    var byCode = as(() -> taxRates.rates(null, null, "STANDARD", null, null, 0, 50));
    assertThat(byCode.total()).isEqualTo(3);

    var page = as(() -> taxRates.rates(null, null, null, null, null, 1, 2));
    assertThat(page.items()).hasSize(1);
    assertThat(page.page()).isEqualTo(1);
    assertThat(page.size()).isEqualTo(2);
    assertThat(page.total()).isEqualTo(3);
  }

  @Test
  void 版本对比逐字段给出差异() {
    upsertRate("VAT", "EU-DE", "STANDARD", "0.19", "2026-01-01T00:00:00Z");
    upsertRate("VAT", "EU-DE", "STANDARD", "0.17", "2026-07-01T00:00:00Z");

    var comparison = as(() -> taxRates.compare("VAT", "EU-DE", "STANDARD", 1, 2));
    assertThat(comparison.rateChanged()).isTrue();
    var rateDiff =
        comparison.differences().stream()
            .filter(d -> d.field().equals("rate"))
            .findFirst()
            .orElseThrow();
    assertThat(rateDiff.changed()).isTrue();
    assertThat(rateDiff.fromValue()).isEqualTo("0.19");
    assertThat(rateDiff.toValue()).isEqualTo("0.17");
  }

  // ==========================================================================
  // HTTP 层（状态码）
  // ==========================================================================
  @Test
  void 端点只认已认证会话且按权限放行() throws Exception {
    http.perform(get("/finance/tax-rates/capabilities")).andExpect(status().isUnauthorized());
    http.perform(get("/finance/tax-rates/rates").header("Authorization", "session"))
        .andExpect(status().isOk());
    http.perform(get("/finance/tax-rates/options").header("Authorization", "session"))
        .andExpect(status().isOk());
    http.perform(get("/finance/tax-rates/jurisdictions").header("Authorization", "session"))
        .andExpect(status().isOk());
    http.perform(get("/finance/tax-rates/shops").header("Authorization", "session"))
        .andExpect(status().isOk());
    http.perform(get("/finance/tax-rates/state-sales-tax").header("Authorization", "session"))
        .andExpect(status().isOk());
  }

  @Test
  void 命中不到时http层是404而不是200() throws Exception {
    upsertRate("VAT", "EU-DE", "STANDARD", "0.19", "2026-01-01T00:00:00Z");
    http.perform(
            get("/finance/tax-rates/resolve")
                .header("Authorization", "session")
                .param("taxType", "VAT")
                .param("jurisdictionCode", "EU-DE")
                .param("taxCode", "STANDARD")
                .param("asOf", "2025-01-01T00:00:00Z"))
        .andExpect(status().isNotFound());
  }

  @Test
  void 伪造租户头无效且行落在会话租户名下() throws Exception {
    var body =
        """
        {"taxType":"VAT","jurisdictionCode":"EU-DE","taxCode":"STANDARD","rate":0.19,
         "effectiveFrom":"2026-01-01T00:00:00Z"}
        """;
    http.perform(
            post("/finance/tax-rates/rates")
                .header("Authorization", "session")
                .header("X-Tenant-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());

    var rows = as(() -> sql.fetch("select tenant_id from finance_tax_rate"));
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("tenant_id", UUID.class)).isEqualTo(tenant);
  }
}
