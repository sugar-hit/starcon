package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.CostValuationModels.*;
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
 * 12.4.1.1 计价方法配置的业务断言，跑在**真实 PostgreSQL 16** 上。
 *
 * <p>覆盖的是蓝图 12-finance.md 里那两句**加粗句**与「关键约束」：
 *
 * <ul>
 *   <li>「<b>系统提供选项与试算，不替用户决定政策</b>」⇒ 系统把四个方法连同口径说明摆出来，
 *       但**零预置行**：落库的每一行都是租户自己选的；试算只给结果，不写任何成本事实；</li>
 *   <li>「<b>切换方法需重算并留版本对比</b>」⇒ 切换 = <b>追加版本</b>（不是就地改），
 *       旧版本被收口并留作对比，历史成本不被追溯改写；</li>
 *   <li>「按法人或品类设置，确定生效时间」⇒ 范围来自上游真实清单（identity / catalog），
 *       生效时间必填且必须递进；</li>
 *   <li>「金额一律 BigDecimal + NUMERIC，禁止浮点」⇒ 金额是四位小数的 numeric，逐位相等；
 *       数量是六位小数的 numeric；</li>
 *   <li>「币种不许跨币种相加」⇒ 试算只吃同币种的成本层，异币种层被排除并如实报出。</li>
 * </ul>
 *
 * <p><b>上游用真实的 HTTP 服务模拟</b>（{@code com.sun.net.httpserver}，与同服务其它模块逐字同构）：
 * identity 返回法人档案、catalog 返回成本构成（真实到货记录派生的成本层）与品类树。
 * 上游读不到时本模块一律 <b>503</b>，不用空集合冒充「没有成本层」。
 *
 * <p><b>断言风格与本仓其它模块一致</b>：业务规则与拒绝理由走**服务层**
 * （{@code assertThatThrownBy(...).hasStackTraceContaining(...)}），HTTP 层只断言**状态码**。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CostValuationMethodIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID legalEntityId = UUID.randomUUID();
  static final UUID categoryId = UUID.randomUUID();
  static final UUID skuId = UUID.randomUUID();

  static final List<String> PERMISSIONS =
      List.of(
          "finance.cost_valuation.read",
          "finance.cost_valuation.create",
          "finance.cost_valuation.update");

  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer catalog;

  /** catalog 的成本构成响应（真实到货记录派生的两层成本）。 */
  static volatile String compositionPayload = "";

  /** catalog 读不到时置 true（模拟上游 500）。 */
  static volatile boolean catalogDown = false;

  /** identity 的法人档案响应。 */
  static volatile String legalEntityPayload = "[]";

  /** identity 的法人档案读不到（模拟上游 500）。 */
  static volatile boolean legalEntityDown = false;

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
      identity.createContext(
          "/identity/legal-entities",
          e -> {
            if (legalEntityDown) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            byte[] body = legalEntityPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      identity.setExecutor(Executors.newFixedThreadPool(2));
      identity.start();

      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/sku-cost-compositions",
          e -> {
            if (catalogDown) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            byte[] body = compositionPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      catalog.createContext(
          "/catalog/categories/trees",
          e -> {
            var body =
                ("[{\"id\":\"" + categoryId + "\",\"code\":\"CAT-INTERNAL\",\"name\":\"内部管理分类\"}]")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      catalog.createContext(
          "/catalog/categories/trees/",
          e -> {
            var body =
                ("[{\"id\":\"" + categoryId + "\",\"treeId\":\"" + categoryId + "\",\"treeCode\":"
                        + "\"CAT-INTERNAL\",\"code\":\"C-1\",\"name\":\"家居\",\"path\":\"家居 > 客厅\"}]")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      catalog.setExecutor(Executors.newFixedThreadPool(2));
      catalog.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
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
    r.add("CATALOG_URL", () -> "http://127.0.0.1:" + catalog.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired CostValuationService costValuations;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_cost_valuation_trial_result, finance_cost_valuation_trial,"
              + " finance_cost_valuation_specified_cost, finance_cost_valuation_method"
              + " restart identity cascade");
    }
    catalogDown = false;
    compositionPayload =
        """
        {"skuId":"%s","skuCode":"SKU-001","skuName":"测试商品","currency":"USD",
         "lots":[
           {"lotRef":"LOT-A","receivedDate":"2026-01-05","quantity":100,"baseQuantity":100,
            "amount":1000.00,"unitCost":10.0000,"currency":"USD"},
           {"lotRef":"LOT-B","receivedDate":"2026-02-05","quantity":100,"baseQuantity":100,
            "amount":1200.00,"unitCost":12.0000,"currency":"USD"},
           {"lotRef":"LOT-EUR","receivedDate":"2026-02-10","quantity":100,"baseQuantity":100,
            "amount":900.00,"unitCost":9.0000,"currency":"EUR"}],
         "excludedDeliveryCount":0,"windowFrom":"2025-10-24","windowTo":"2026-10-24",
         "missingComponents":[]}
        """
            .formatted(skuId);
    legalEntityDown = false;
    legalEntityPayload =
        ("[{\"id\":\"" + legalEntityId + "\",\"code\":\"LE-US\",\"legalName\":\"美国主体\","
                + "\"status\":\"ACTIVE\",\"baseCurrency\":\"USD\"}]");
  }

  /** 在**租户事务里**跑一段工作（与生产同一条路径：RLS 靠它隔离）。 */
  <T> T as(java.util.function.Supplier<T> work) {
    return tenants.run(tenant, work);
  }

  /** 登记一版计价方法（在租户事务里 —— 与生产同一条路径：RLS 靠它隔离）。 */
  FinanceCostValuationMethodRow switchMethod(
      ValuationScopeType scopeType,
      UUID scopeRefId,
      String method,
      String currency,
      OffsetDateTime effectiveFrom) {
    return as(
        () ->
            costValuations.switchMethod(
                new FinanceCostValuationMethodInput(
                    scopeType.name(), scopeRefId, "LE-US", "美国主体", method, currency, effectiveFrom, "测试配置"),
                actors.get("session")));
  }

  // ==========================================================================
  // 能力声明 / 选项 / 不预置
  // ==========================================================================
  @Test
  void 能力声明把四个方法摆出来但不替用户决定政策() {
    var capabilities = as(() -> costValuations.capabilities());
    assertThat(capabilities.capabilities())
        .as("能力声明必须说清「系统提供选项」与「不预置任何一行」")
        .anyMatch(c -> (c.basis() + c.detail()).contains("不预置任何一行"));
    assertThat(capabilities.downstreamGaps())
        .as("12.4.1.2 / 12.4.1.3 / 13 必须如实记为未交付")
        .anyMatch(g -> g.module().equals("12.4.1.2") && !g.available())
        .anyMatch(g -> g.module().equals("12.4.1.3") && !g.available())
        .anyMatch(g -> g.module().equals("13") && !g.available());

    var options = as(() -> costValuations.options());
    assertThat(options.methods())
        .as("四个计价方法必须都作为选项摆出来")
        .extracting(FinanceCostValuationMethodOption::value)
        .containsExactlyInAnyOrder("FIFO", "MOVING_AVERAGE", "BATCH", "SPECIFIED");
    assertThat(options.methods()).allMatch(o -> o.basis() != null && !o.basis().isBlank());
  }

  @Test
  void 系统不预置任何一行配置() {
    var page = as(() -> costValuations.methods(null, null, null, null, null, null, 0, 50));
    assertThat(page.items()).as("系统不得替用户预置计价方法").isEmpty();
    assertThat(page.total()).isZero();
    assertThat(page.activeCount()).isZero();
  }

  // ==========================================================================
  // 版本链
  // ==========================================================================
  @Test
  void 切换方法追加新版本而不是就地改() {
    var first = switchMethod(ValuationScopeType.LEGAL_ENTITY, legalEntityId, "FIFO", "USD", OffsetDateTime.parse("2026-01-01T00:00:00Z"));
    assertThat(first.version()).isEqualTo(1);
    assertThat(first.status()).isEqualTo("ACTIVE");
    assertThat(first.recalculationStatus()).isEqualTo("NOT_REQUIRED");
    assertThat(first.supersedesId()).isNull();

    var second =
        switchMethod(ValuationScopeType.LEGAL_ENTITY, legalEntityId, "MOVING_AVERAGE", "USD", OffsetDateTime.parse("2026-07-01T00:00:00Z"));
    assertThat(second.version()).isEqualTo(2);
    assertThat(second.status()).isEqualTo("ACTIVE");
    assertThat(second.supersedesId()).isEqualTo(first.id());
    assertThat(second.recalculationStatus())
        .as("切换方法 ⇒ 需要 12.4.1.2 重算")
        .isEqualTo("PENDING");
    assertThat(second.recalculationOwnerModule()).isEqualTo("12.4.1.2");

    // 旧版本被收口：状态 SUPERSEDED + 生效终点 = 新版本生效起点。
    var versions = as(() -> costValuations.versions(ValuationScopeType.LEGAL_ENTITY.name(), legalEntityId));
    assertThat(versions).hasSize(2);
    var old = versions.stream().filter(v -> v.version() == 1).findFirst().orElseThrow();
    assertThat(old.status()).isEqualTo("SUPERSEDED");
    assertThat(old.effectiveTo())
        .as("旧版本的生效终点必须等于新版本的生效起点（历史成本不追溯改写）")
        .isEqualTo(OffsetDateTime.parse("2026-07-01T00:00:00Z"));
    assertThat(old.valuationMethod()).isEqualTo(ValuationMethod.FIFO);
  }

  @Test
  void 生效时间必须晚于上一版本() {
    switchMethod(ValuationScopeType.LEGAL_ENTITY, legalEntityId, "FIFO", "USD", OffsetDateTime.parse("2026-01-01T00:00:00Z"));
    assertThatThrownBy(
            () ->
                switchMethod(
                    ValuationScopeType.LEGAL_ENTITY,
                    legalEntityId,
                    "BATCH",
                    "USD",
                    OffsetDateTime.parse("2026-01-01T00:00:00Z")))
        .hasStackTraceContaining("必须晚于上一版本");
  }

  @Test
  void 历史版本不可改写() {
    var first = switchMethod(ValuationScopeType.LEGAL_ENTITY, legalEntityId, "FIFO", "USD", OffsetDateTime.parse("2026-01-01T00:00:00Z"));
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "update finance_cost_valuation_method set valuation_method = 'BATCH'"
                              + " where tenant_id = ? and id = ?",
                          tenant,
                          first.id());
                      return null;
                    }))
        .as("版本对比要有着落，旧版本的方法就不许就地改")
        .hasStackTraceContaining("历史版本不可改写");
  }

  @Test
  void 同一范围同时只能有一个生效版本() {
    var first = switchMethod(ValuationScopeType.LEGAL_ENTITY, legalEntityId, "FIFO", "USD", OffsetDateTime.parse("2026-01-01T00:00:00Z"));
    // ⚠️ 版本号必须是合法的下一个版本（否则先被版本守卫挡住，测不到唯一索引这一层）。
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "insert into finance_cost_valuation_method(id,tenant_id,scope_type,scope_ref_id,"
                              + " scope_code,scope_name,valuation_method,currency,effective_from,status,"
                              + " version,supersedes_id,recalculation_status,recalculation_owner_module,"
                              + " created_at,updated_at)"
                              + " values(?,?,'LEGAL_ENTITY',?,'LE-US','美国主体','BATCH','USD',"
                              + " '2026-09-01T00:00:00Z','ACTIVE',2,?,'NOT_REQUIRED','12.4.1.2',"
                              + " ?::timestamptz,?::timestamptz)",
                          UUID.randomUUID(),
                          tenant,
                          legalEntityId,
                          first.id(),
                          OffsetDateTime.now(ZoneOffset.UTC),
                          OffsetDateTime.now(ZoneOffset.UTC));
                      return null;
                    }))
        .as("「同一范围只能有一个生效版本」是数据层不变量")
        .hasStackTraceContaining("finance_cost_valuation_method_one_active");
  }

  @Test
  void 版本对比给出切换前后的差异() {
    switchMethod(ValuationScopeType.LEGAL_ENTITY, legalEntityId, "FIFO", "USD", OffsetDateTime.parse("2026-01-01T00:00:00Z"));
    switchMethod(ValuationScopeType.LEGAL_ENTITY, legalEntityId, "MOVING_AVERAGE", "USD", OffsetDateTime.parse("2026-07-01T00:00:00Z"));
    var comparison =
        as(() -> costValuations.compare(ValuationScopeType.LEGAL_ENTITY.name(), legalEntityId, 1, 2));
    assertThat(comparison.methodChanged()).isTrue();
    assertThat(comparison.differences())
        .as("版本对比必须逐字段给出差异")
        .anyMatch(d -> d.field().equals("valuationMethod") && d.changed())
        .anyMatch(d -> d.field().equals("effectiveFrom") && d.changed());
    assertThat(comparison.recalculationNote()).contains("12.4.1.2");
  }

  // ==========================================================================
  // 指定成本
  // ==========================================================================
  @Test
  void 指定单价必须大于零且改价追加版本() {
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        costValuations.upsertSpecifiedCost(
                            new FinanceCostValuationSpecifiedCostInput(
                                skuId,
                                "SKU-001",
                                "USD",
                                BigDecimal.ZERO,
                                OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                                ""),
                            actors.get("session"))))
        .hasStackTraceContaining("必须大于 0");

    var first =
        as(
            () ->
                costValuations.upsertSpecifiedCost(
                    new FinanceCostValuationSpecifiedCostInput(
                        skuId,
                        "SKU-001",
                        "USD",
                        new BigDecimal("9.5000"),
                        OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                        ""),
                    actors.get("session")));
    assertThat(first.version()).isEqualTo(1);
    assertThat(first.unitCost()).isEqualByComparingTo("9.5000");

    var second =
        as(
            () ->
                costValuations.upsertSpecifiedCost(
                    new FinanceCostValuationSpecifiedCostInput(
                        skuId,
                        "SKU-001",
                        "USD",
                        new BigDecimal("9.7500"),
                        OffsetDateTime.parse("2026-06-01T00:00:00Z"),
                        ""),
                    actors.get("session")));
    assertThat(second.version()).isEqualTo(2);
    assertThat(second.status()).isEqualTo("ACTIVE");

    var rows = as(() -> costValuations.specifiedCosts(skuId, "USD", null, 0, 50));
    assertThat(rows.items()).hasSize(2);
    assertThat(rows.items()).filteredOn(r -> r.status().equals("SUPERSEDED")).hasSize(1);
  }

  // ==========================================================================
  // 试算
  // ==========================================================================
  @Test
  void 试算用真实成本层算出四种方法的结果() {
    as(
        () ->
            costValuations.upsertSpecifiedCost(
                new FinanceCostValuationSpecifiedCostInput(
                    skuId,
                    "SKU-001",
                    "USD",
                    new BigDecimal("9.5000"),
                    OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                    ""),
                actors.get("session")));
    switchMethod(ValuationScopeType.LEGAL_ENTITY, legalEntityId, "FIFO", "USD", OffsetDateTime.parse("2026-01-01T00:00:00Z"));

    var trial =
        as(
            () ->
                costValuations.trial(
                    new FinanceCostValuationTrialRequest(
                        ValuationScopeType.LEGAL_ENTITY.name(),
                        legalEntityId,
                        skuId,
                        "SKU-001",
                        "USD",
                        new BigDecimal("150"),
                        null,
                        OffsetDateTime.parse("2026-03-01T00:00:00Z")),
                    actors.get("session"),
                    "session"));

    assertThat(trial.results()).hasSize(4);
    var fifo = trial.results().stream().filter(r -> r.valuationMethod().equals("FIFO")).findFirst().orElseThrow();
    // 先进先出：先消耗 LOT-A 的 100 件（10.0000），再从 LOT-B 取 50 件（12.0000）= 1600.0000
    assertThat(fifo.costAmount()).isEqualByComparingTo("1600.0000");
    assertThat(fifo.coveredQuantity()).isEqualByComparingTo("150.000000");
    assertThat(fifo.shortfallQuantity()).isEqualByComparingTo("0.000000");
    assertThat(fifo.comparable()).isTrue();

    var moving =
        trial.results().stream().filter(r -> r.valuationMethod().equals("MOVING_AVERAGE")).findFirst().orElseThrow();
    // 移动加权：(1000 + 1200) ÷ 200 = 11.0000 → 150 × 11 = 1650.0000
    // ⚠️ 异币种层（LOT-EUR）**不参与**（绝不跨币种相加）。
    assertThat(moving.unitCost()).isEqualByComparingTo("11.0000");
    assertThat(moving.costAmount()).isEqualByComparingTo("1650.0000");

    var batch =
        trial.results().stream().filter(r -> r.valuationMethod().equals("BATCH")).findFirst().orElseThrow();
    assertThat(batch.comparable()).as("没指定批次就算不出结果").isFalse();
    assertThat(batch.unitCost()).as("算不出来就不给数字，不给 0 冒充成本为 0").isNull();
    assertThat(batch.costAmount()).isNull();
    assertThat(batch.gapNote()).contains("没有指定批次");

    var specified =
        trial.results().stream().filter(r -> r.valuationMethod().equals("SPECIFIED")).findFirst().orElseThrow();
    assertThat(specified.comparable()).isTrue();
    assertThat(specified.unitCost()).isEqualByComparingTo("9.5000");
    assertThat(specified.costAmount()).isEqualByComparingTo("1425.0000");

    // 当前生效方法是 FIFO ⇒ 其它方法给出差额（正 = 更贵）。
    assertThat(trial.effectiveMethod()).isEqualTo("FIFO");
    assertThat(moving.differenceToEffectiveMethod()).isEqualByComparingTo("50.0000");
    assertThat(specified.differenceToEffectiveMethod()).isEqualByComparingTo("-175.0000");

    // 试算留痕：表头一行 + 结果四行。
    var trials = as(() -> costValuations.trials(skuId, null, 0, 50));
    assertThat(trials.items()).hasSize(1);
    assertThat(trials.items().get(0).results()).hasSize(4);
    assertThat(trials.items().get(0).availableQuantity())
        .as("可用数量只算同币种层（100 + 100）")
        .isEqualByComparingTo("200.000000");
    assertThat(trials.items().get(0).sourceNote()).contains("其他币种");
  }

  @Test
  void 批次成本按指定批次算且缺口如实报出() {
    var trial =
        as(
            () ->
                costValuations.trial(
                    new FinanceCostValuationTrialRequest(
                        ValuationScopeType.LEGAL_ENTITY.name(),
                        legalEntityId,
                        skuId,
                        "SKU-001",
                        "USD",
                        new BigDecimal("150"),
                        "LOT-B",
                        OffsetDateTime.parse("2026-03-01T00:00:00Z")),
                    actors.get("session"),
                    "session"));
    var batch =
        trial.results().stream().filter(r -> r.valuationMethod().equals("BATCH")).findFirst().orElseThrow();
    assertThat(batch.comparable()).isTrue();
    assertThat(batch.unitCost()).isEqualByComparingTo("12.0000");
    assertThat(batch.coveredQuantity()).isEqualByComparingTo("100.000000");
    assertThat(batch.shortfallQuantity()).as("批次数量不足 ⇒ 缺口如实报出").isEqualByComparingTo("50.000000");
    assertThat(batch.costAmount()).isEqualByComparingTo("1200.0000");
    assertThat(batch.differenceToEffectiveMethod()).as("没有生效配置就没有对比基准").isNull();
  }

  @Test
  void 上游读不到一律503而不是空结果() {
    catalogDown = true;
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        costValuations.trial(
                            new FinanceCostValuationTrialRequest(
                                ValuationScopeType.LEGAL_ENTITY.name(),
                                legalEntityId,
                                skuId,
                                "SKU-001",
                                "USD",
                                new BigDecimal("10"),
                                null,
                                OffsetDateTime.parse("2026-03-01T00:00:00Z")),
                            actors.get("session"),
                            "session")))
        .as("上游读不到 ⇒ 503 并写明「读不到」，绝不冒充「没有成本层」")
        .hasStackTraceContaining("成本层读不到");

    // 读不到时不许落一条「空试算」。
    var trials = as(() -> costValuations.trials(skuId, null, 0, 50));
    assertThat(trials.items()).isEmpty();
  }

  @Test
  void 覆盖量加缺口量等于试算量由数据层钉死() {
    var trialId = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    as(
        () -> {
          sql.execute(
              "insert into finance_cost_valuation_trial(id,tenant_id,scope_type,scope_ref_id,scope_code,"
                  + " scope_name,sku_id,sku_code,currency,issue_quantity,available_quantity,layer_count,"
                  + " source_service,created_at) values(?,?,'LEGAL_ENTITY',?,'LE-US','美国主体',?,'SKU-001',"
                  + " 'USD',10,20,2,'catalog',?::timestamptz)",
              trialId,
              tenant,
              legalEntityId,
              skuId,
              now);
          return null;
        });
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "insert into finance_cost_valuation_trial_result(id,tenant_id,trial_id,"
                              + " valuation_method,currency,covered_quantity,shortfall_quantity,unit_cost,"
                              + " cost_amount,comparable,gap_note,basis_note,created_at)"
                              + " values(?,?,?,'FIFO','USD',5,0,1.0000,5.0000,true,'','',?::timestamptz)",
                          UUID.randomUUID(),
                          tenant,
                          trialId,
                          now);
                      return null;
                    }))
        .as("覆盖量 + 缺口量 ≠ 试算量 ⇒ 数据层直接拒绝")
        .hasStackTraceContaining("必须等于试算数量");
  }

  // ==========================================================================
  // 范围（法人 / 品类）
  // ==========================================================================
  @Test
  void 范围来自上游真实清单() {
    var options = costValuations.scopeOptions("session");
    assertThat(options.legalEntities())
        .as("法人清单实时读 identity")
        .anyMatch(o -> o.code().equals("LE-US") && o.name().equals("美国主体"));
    assertThat(options.categories())
        .as("品类清单实时读 catalog 品类树")
        .anyMatch(o -> o.code().equals("C-1") && o.name().equals("家居"));
    assertThat(options.legalEntityNote()).isEmpty();
  }

  @Test
  void 法人清单读不到时如实说读不到() {
    // 空清单 ≠ 读不到：空清单不写原因。
    legalEntityPayload = "[]";
    var empty = costValuations.scopeOptions("session");
    assertThat(empty.legalEntities()).isEmpty();
    assertThat(empty.legalEntityNote()).isEmpty();

    // 上游读不到：清单为空 **且** 写明「读不到」（不是「这个租户没有法人」）。
    legalEntityDown = true;
    var down = costValuations.scopeOptions("session");
    assertThat(down.legalEntities()).isEmpty();
    assertThat(down.legalEntityNote()).as("读不到与没有，必须能分开").isNotBlank();
  }

  // ==========================================================================
  // 端点
  // ==========================================================================
  @Test
  void 端点读写与方法配置贯通() throws Exception {
    http.perform(
            post("/finance/cost-valuation/methods")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"scopeType":"LEGAL_ENTITY","scopeRefId":"%s","scopeCode":"LE-US","scopeName":"美国主体",
                     "valuationMethod":"FIFO","currency":"USD","effectiveFrom":"2026-01-01T00:00:00Z","note":"端点建"}
                    """
                        .formatted(legalEntityId)))
        .andExpect(status().isOk());

    var page =
        json.readValue(
            http.perform(
                    get("/finance/cost-valuation/methods")
                        .header("Authorization", "session")
                        .param("scopeType", "LEGAL_ENTITY"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceCostValuationMethodPage.class);
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).valuationMethod()).isEqualTo(ValuationMethod.FIFO);
    assertThat(page.activeCount()).isEqualTo(1);
  }

  @Test
  void 非法取值给一句能直接展示的话() throws Exception {
    http.perform(
            post("/finance/cost-valuation/methods")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"scopeType":"LEGAL_ENTITY","scopeRefId":"%s","scopeCode":"LE-US",
                     "valuationMethod":"MAGIC","currency":"USD","effectiveFrom":"2026-01-01T00:00:00Z"}
                    """
                        .formatted(legalEntityId)))
        .andExpect(status().isBadRequest());
  }
}
