package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.CostAllocationModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 12.4.2.2 分摊规则的业务断言，跑在**真实 PostgreSQL 16** 上。
 *
 * <p>覆盖蓝图 12-finance.md 12.4.2.2 的五件事：
 *
 * <ol>
 *   <li><b>分摊维度</b>：SKU / SPU / 店铺 / 订单 / 类目；</li>
 *   <li><b>分摊基数</b>：数量 / 金额 / 重量 / 体积 / 等额；</li>
 *   <li><b>生效区间</b>：区间不覆盖池的归属期间 ⇒ 规则不命中（历史分摊不被新规则追溯改写）；</li>
 *   <li><b>分摊顺序</b>：多条规则命中时按 priority 取第一条；</li>
 *   <li><b>舍入差处理</b>：零头落到**指定对象**上并留痕。</li>
 * </ol>
 *
 * <p><b>🔴 核心不变量单独钉一条</b>：<b>分摊结果之和 = 被分摊总额</b> ——
 * 蓝图关系句写明「否则利润报表总额对不上 —— 这是财务最不能接受的错误」。
 *
 * <p><b>上游数据怎么来的</b>：分摊对象来自**真实上游事实**的只读端口
 * （trade 平台订单 / 销售订单行、channel 店铺主数据、catalog SKU 主数据与包装档案），
 * 用本地 stub 提供上游的 HTTP 契约（端口本身是生产代码，测试只替掉「上游服务」这一端）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CostAllocationIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();

  static final List<String> PERMISSIONS =
      List.of(
          "finance.costallocation.read", "finance.costallocation.create",
          "finance.costallocation.update");

  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer trade;
  static HttpServer channel;
  static HttpServer catalog;

  /** 归属期间：3 月（费用池在 3 月）。 */
  static final LocalDate MARCH_START = LocalDate.of(2026, 3, 1);
  static final LocalDate MARCH_END = LocalDate.of(2026, 3, 31);

  /** 归属期间：4 月（另一个池，用来验证「全部基数不可得 ⇒ 拒绝分摊」）。 */
  static final LocalDate APRIL_START = LocalDate.of(2026, 4, 1);
  static final LocalDate APRIL_END = LocalDate.of(2026, 4, 30);

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

      // ---- trade：平台订单（9.1.2.1）+ 销售订单与订单行（9.1.1.1）----
      trade = HttpServer.create(new InetSocketAddress(0), 0);
      trade.createContext(
          "/trade/platform-orders/orders",
          e -> {
            // 三笔 3 月的美元订单（50 / 30 / 20）、一笔欧元订单（不该进美元池）、
            // 一笔 4 月的零金额订单（金额基数不可得）。
            String body =
                """
                {"rows":[
                  {"id":"11111111-1111-4111-8111-111111111111","orderNo":"SO-001","shopCode":"SHOP-A",
                   "currency":"USD","platformGrandTotalAmount":50.0000,"normalizedStatus":"COMPLETED",
                   "platformPlacedAt":"2026-03-05T10:00:00Z"},
                  {"id":"22222222-2222-4222-8222-222222222222","orderNo":"SO-002","shopCode":"SHOP-B",
                   "currency":"USD","platformGrandTotalAmount":30.0000,"normalizedStatus":"COMPLETED",
                   "platformPlacedAt":"2026-03-08T10:00:00Z"},
                  {"id":"33333333-3333-4333-8333-333333333333","orderNo":"SO-003","shopCode":"SHOP-A",
                   "currency":"USD","platformGrandTotalAmount":20.0000,"normalizedStatus":"COMPLETED",
                   "platformPlacedAt":"2026-03-12T10:00:00Z"},
                  {"id":"44444444-4444-4444-8444-444444444444","orderNo":"SO-004","shopCode":"SHOP-C",
                   "currency":"EUR","platformGrandTotalAmount":999.0000,"normalizedStatus":"COMPLETED",
                   "platformPlacedAt":"2026-03-15T10:00:00Z"},
                  {"id":"55555555-5555-4555-8555-555555555555","orderNo":"SO-005","shopCode":"SHOP-A",
                   "currency":"USD","platformGrandTotalAmount":0.0000,"normalizedStatus":"COMPLETED",
                   "platformPlacedAt":"2026-04-02T10:00:00Z"}
                ],"page":0,"size":200,"total":5}
                """;
            byte[] bytes = body.getBytes();
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, bytes.length);
            e.getResponseBody().write(bytes);
            e.close();
          });
      trade.createContext(
          "/trade/sales-orders/list",
          e -> {
            String body =
                """
                {"rows":[{"id":"66666666-6666-4666-8666-666666666666","orderNo":"M-001",
                          "currency":"USD","grandTotalAmount":40.0000,"documentStatus":"SUBMITTED",
                          "submittedAt":"2026-03-10T08:00:00Z"}],
                 "page":0,"size":200,"total":1}
                """;
            byte[] bytes = body.getBytes();
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, bytes.length);
            e.getResponseBody().write(bytes);
            e.close();
          });
      trade.createContext(
          "/trade/sales-orders/detail",
          e -> {
            String body =
                """
                {"lines":[
                  {"id":"77777777-7777-4777-8777-777777777777","lineNo":1,
                   "skuId":"aaaaaaaa-1111-4111-8111-111111111111","skuCode":"SKU-A","skuName":"商品 A",
                   "quantity":3.000000,"lineAmount":30.0000},
                  {"id":"88888888-8888-4888-8888-888888888888","lineNo":2,
                   "skuId":"bbbbbbbb-2222-4222-8222-222222222222","skuCode":"SKU-B","skuName":"商品 B",
                   "quantity":1.000000,"lineAmount":10.0000}
                ]}
                """;
            byte[] bytes = body.getBytes();
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, bytes.length);
            e.getResponseBody().write(bytes);
            e.close();
          });
      trade.setExecutor(Executors.newFixedThreadPool(2));
      trade.start();

      // ---- channel：店铺主数据（2.3.1.1）----
      channel = HttpServer.create(new InetSocketAddress(0), 0);
      channel.createContext(
          "/channel/shops",
          e -> {
            String body =
                """
                [{"id":"99999999-9999-4999-8999-999999999999","code":"SHOP-A","name":"店铺甲",
                  "platformCode":"amazon","platformName":"亚马逊","status":"ACTIVE"},
                 {"id":"aaaaaaaa-9999-4999-8999-999999999999","code":"SHOP-B","name":"店铺乙",
                  "platformCode":"amazon","platformName":"亚马逊","status":"ACTIVE"},
                 {"id":"bbbbbbbb-9999-4999-8999-999999999999","code":"SHOP-C","name":"店铺丙",
                  "platformCode":"shopee","platformName":"Shopee","status":"ACTIVE"}]
                """;
            byte[] bytes = body.getBytes();
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, bytes.length);
            e.getResponseBody().write(bytes);
            e.close();
          });
      channel.setExecutor(Executors.newFixedThreadPool(2));
      channel.start();

      // ---- catalog：SKU 列表（3.1.2.1）+ SKU 档案（3.1.2.2，含包装重量 / 体积）----
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/skus",
          e -> {
            var path = e.getRequestURI().getPath();
            String body;
            if (path.equals("/catalog/skus")) {
              body =
                  """
                  {"items":[
                    {"id":"aaaaaaaa-1111-4111-8111-111111111111","spuId":"cccccccc-1111-4111-8111-111111111111",
                     "spuCode":"SPU-1","spuName":"系列一","category":"CAT-A","code":"SKU-A","name":"商品 A",
                     "status":"ACTIVE"},
                    {"id":"bbbbbbbb-2222-4222-8222-222222222222","spuId":"cccccccc-1111-4111-8111-111111111111",
                     "spuCode":"SPU-1","spuName":"系列一","category":"CAT-A","code":"SKU-B","name":"商品 B",
                     "status":"ACTIVE"}
                  ],"page":0,"size":200,"total":2}
                  """;
            } else if (path.endsWith("aaaaaaaa-1111-4111-8111-111111111111")) {
              // SKU-A 维护了包装重量与体积。
              body =
                  """
                  {"id":"aaaaaaaa-1111-4111-8111-111111111111",
                   "spuId":"cccccccc-1111-4111-8111-111111111111","spuCode":"SPU-1","category":"CAT-A",
                   "code":"SKU-A","name":"商品 A",
                   "packaging":{"grossWeightG":500.000000,"volumeCm3":1000.000000}}
                  """;
            } else {
              // SKU-B **没有维护包装** ⇒ 重量 / 体积基数取不到（不是 0）。
              body =
                  """
                  {"id":"bbbbbbbb-2222-4222-8222-222222222222",
                   "spuId":"cccccccc-1111-4111-8111-111111111111","spuCode":"SPU-1","category":"CAT-A",
                   "code":"SKU-B","name":"商品 B","packaging":null}
                  """;
            }
            byte[] bytes = body.getBytes();
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, bytes.length);
            e.getResponseBody().write(bytes);
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
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("TRADE_URL", () -> "http://127.0.0.1:" + trade.getAddress().getPort());
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:" + channel.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://127.0.0.1:" + catalog.getAddress().getPort());
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired CostAllocationService allocations;

  /**
   * 服务层给出的**可读原因**。
   *
   * <p>⚠️ 为什么不在 HTTP 响应体里找这句话：MockMvc **不渲染**
   * {@code ResponseStatusException} 的 reason（状态码有，响应体是空的），
   * 仓库既有测试也只断言状态码（见 12.4.2.1 的用例）。
   * ⇒ 「给操作员看得懂的原因」这件事必须在**服务层**断言，
   * 否则就只是断言了一个状态码。
   */
  String reasonOf(Runnable work) {
    try {
      as(
          () -> {
            work.run();
            return null;
          });
      return "";
    } catch (org.springframework.web.server.ResponseStatusException e) {
      return e.getReason() == null ? "" : e.getReason();
    }
  }

  /** 执行分摊时服务层给出的原因（不成功时返回那句话）。 */
  String allocateReason(UUID poolId, UUID ruleId) {
    var request = new FinanceCostAllocationRequest(poolId, ruleId, null);
    return reasonOf(() -> allocations.allocate(request, "session"));
  }

  // ================================================================ 夹具

  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_cost_allocation_result, finance_cost_allocation_batch,"
              + " finance_cost_allocation_target, finance_cost_allocation_rule,"
              + " finance_cost_collection_basis, finance_cost_collection_entry,"
              + " finance_cost_collection_pool, finance_cost_collection_rule"
              + " restart identity cascade");
    }
  }

  <T> T as(Supplier<T> work) {
    return tenants.run(tenant, work);
  }

  Record one(String statement, Object... binds) {
    return as(() -> sql.fetchOne(statement, binds));
  }

  // ================================================================ HTTP 小工具

  MvcResult postTo(String path) throws Exception {
    var result =
        http.perform(MockMvcRequestBuilders.post(path).header("Authorization", "session")).andReturn();
    assertThat(result.getResponse().getStatus())
        .as("POST " + path + " 失败：" + result.getResponse().getContentAsString())
        .isEqualTo(200);
    return result;
  }

  <T> T postToJson(String path, Class<T> type) throws Exception {
    return json.readValue(postTo(path).getResponse().getContentAsString(), type);
  }

  MvcResult getFrom(String path) throws Exception {
    return http.perform(MockMvcRequestBuilders.get(path).header("Authorization", "session"))
        .andExpect(status().isOk())
        .andReturn();
  }

  <T> T getFromJson(String path, Class<T> type) throws Exception {
    return json.readValue(getFrom(path).getResponse().getContentAsString(), type);
  }

  <T> List<T> getListFromJson(String path, Class<T> element) throws Exception {
    return json.readValue(
        getFrom(path).getResponse().getContentAsString(),
        json.getTypeFactory().constructCollectionType(List.class, element));
  }

  // ================================================================ 造数

  /** 归集规则的四元组（V351 钉死的合法配对）。 */
  record Combo(String source, String category, String dimension, String basis) {}

  static Combo comboOf(String dimension) {
    return switch (dimension) {
      case "MONTH" -> new Combo("PLATFORM_SETTLEMENT", "PLATFORM_FEE", "MONTH", "CALENDAR_MONTH");
      case "ORDER" -> new Combo("LOGISTICS_WAYBILL", "LOGISTICS_FEE", "ORDER", "CALENDAR_MONTH");
      case "SKU" -> new Combo("HEAD_MILE_SHIPMENT", "HEAD_MILE_FEE", "SKU", "CALENDAR_MONTH");
      case "PERIOD" -> new Combo("OFFICE", "OFFICE_COST", "PERIOD", "ACCOUNTING_PERIOD");
      default -> throw new IllegalArgumentException(dimension);
    };
  }

  /**
   * 落一个费用池（12.4.2.1 的池）+ 若干归集条目（池合计由触发器派生）。
   *
   * @return 池 id
   */
  UUID seedPool(
      String poolKey, String dimension, LocalDate start, LocalDate end, String currency,
      BigDecimal... amounts) {
    var combo = comboOf(dimension);
    var poolId = UUID.randomUUID();
    var ruleId = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    as(
        () -> {
          sql.execute(
              "insert into finance_cost_collection_rule(id,tenant_id,rule_code,rule_name,fee_source,"
                  + " fee_category,target_dimension,period_basis,pool_key,pool_name,effective_from,"
                  + " status,created_at,updated_at)"
                  + " values(?,?,?,?,?,?,?,?,?,?,?::date,'ACTIVE',?::timestamptz,?::timestamptz)",
              ruleId,
              tenant,
              poolKey + "-RULE",
              poolKey + " 的归集规则",
              combo.source(),
              combo.category(),
              combo.dimension(),
              combo.basis(),
              poolKey,
              poolKey,
              start.minusDays(30),
              now,
              now);
          sql.execute(
              "insert into finance_cost_collection_pool(id,tenant_id,pool_key,pool_name,dimension,"
                  + " period_start,period_end,currency,status,total_amount,entry_count,"
                  + " allocation_available,allocation_owner_module,created_at,updated_at)"
                  + " values(?,?,?,?,?,?::date,?::date,?,'OPEN',0,0,false,'12.4.2.2',"
                  + " ?::timestamptz,?::timestamptz)",
              poolId,
              tenant,
              poolKey,
              poolKey,
              dimension,
              start,
              end,
              currency,
              now,
              now);
          int no = 0;
          for (var amount : amounts) {
            no++;
            sql.execute(
                "insert into finance_cost_collection_entry(id,tenant_id,pool_id,rule_id,fee_source,"
                    + " fee_category,target_dimension,base_date,currency,amount,source_ref_type,"
                    + " source_ref_key,collected_at,created_at,updated_at)"
                    + " values(?,?,?,?,?,?,?,?::date,?,?,'MANUAL_DOCUMENT',?,"
                    + " ?::timestamptz,?::timestamptz,?::timestamptz)",
                UUID.randomUUID(),
                tenant,
                poolId,
                ruleId,
                combo.source(),
                combo.category(),
                combo.dimension(),
                start,
                currency,
                amount,
                poolKey + "#" + no,
                now,
                now,
                now);
          }
          return null;
        });
    return poolId;
  }

  /** 建一条分摊规则（走真实端点）。 */
  UUID createRule(
      String code,
      String dimension,
      String basis,
      String poolKey,
      int priority,
      String policy,
      String roundingType,
      String roundingCode,
      String from,
      String to) throws Exception {
    var map = new LinkedHashMap<String, Object>();
    map.put("ruleCode", code);
    map.put("ruleName", code);
    map.put("targetDimension", dimension);
    map.put("allocationBasis", basis);
    map.put("poolKey", poolKey);
    map.put("priority", priority);
    map.put("roundingPolicy", policy);
    if (roundingType != null) map.put("roundingTargetType", roundingType);
    if (roundingCode != null) map.put("roundingTargetCode", roundingCode);
    map.put("effectiveFrom", from);
    if (to != null) map.put("effectiveTo", to);
    var response =
        http.perform(
                post("/finance/cost-allocations/rules")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(map)))
            .andReturn();
    assertThat(response.getResponse().getStatus())
        .as("建分摊规则失败：" + response.getResponse().getContentAsString())
        .isEqualTo(200);
    return json.readValue(response.getResponse().getContentAsString(), FinanceCostAllocationRule.class)
        .id();
  }

  /** 采集分摊对象（走真实端点）。 */
  FinanceCostAllocationTargetCollectResult collect(UUID poolId, String dimension) throws Exception {
    var result =
        http.perform(
                post("/finance/cost-allocations/targets/collect")
                    .header("Authorization", "session")
                    .param("poolId", poolId.toString())
                    .param("dimension", dimension))
            .andReturn();
    assertThat(result.getResponse().getStatus())
        .as("采集分摊对象失败：" + result.getResponse().getContentAsString())
        .isEqualTo(200);
    return json.readValue(
        result.getResponse().getContentAsString(), FinanceCostAllocationTargetCollectResult.class);
  }

  /** 执行分摊（走真实端点）。 */
  MvcResult allocateRaw(UUID poolId, UUID ruleId) throws Exception {
    var map = new LinkedHashMap<String, Object>();
    map.put("poolId", poolId.toString());
    if (ruleId != null) map.put("ruleId", ruleId.toString());
    return http.perform(
            post("/finance/cost-allocations/allocate")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(map)))
        .andReturn();
  }

  /** 执行分摊并断言成功，返回批次详情。 */
  FinanceCostAllocationBatchDetail allocate(UUID poolId, UUID ruleId) throws Exception {
    var response = allocateRaw(poolId, ruleId);
    assertThat(response.getResponse().getStatus())
        .as("分摊失败：" + response.getResponse().getContentAsString())
        .isEqualTo(200);
    return json.readValue(
        response.getResponse().getContentAsString(), FinanceCostAllocationBatchDetail.class);
  }

  // ==========================================================================
  // 迁移落地 + RLS
  // ==========================================================================

  @Test
  void 本模块的四张表强制rls而不是只启用() {
    for (String table :
        List.of(
            "finance_cost_allocation_rule",
            "finance_cost_allocation_target",
            "finance_cost_allocation_batch",
            "finance_cost_allocation_result")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V354 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class))
          .as(table + " 只有 FORCE 才挡得住「运行角色被误授 owner」")
          .isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('finance_app','" + table + "','SELECT')")
                  .get(0, Boolean.class))
          .as("finance_app 必须真的能读 " + table)
          .isTrue();
    }
  }

  @Test
  void 上游的三列被放宽而不是被收紧() {
    // 12.4.2.1 的老 INSERT **不写**这三列（走默认值）⇒ 放宽之后它们必须仍然合法。
    var poolId = seedPool("PLATFORM_FEE", "MONTH", MARCH_START, MARCH_END, "USD", new BigDecimal("100.0000"));

    var pool =
        one("select allocation_available from finance_cost_collection_pool where id = ?", poolId);
    assertThat(pool.get(0, Boolean.class)).as("新建的池默认未分摊").isFalse();

    var entry =
        one(
            "select allocated_amount, allocation_status from finance_cost_collection_entry"
                + " where pool_id = ?",
            poolId);
    assertThat(entry.get("allocated_amount", BigDecimal.class)).isEqualByComparingTo("0");
    assertThat(entry.get("allocation_status", String.class)).isEqualTo("UNALLOCATED");

    // 放宽方向：真实分摊金额与新状态**能写进去**。
    as(
        () -> {
          sql.execute(
              "update finance_cost_collection_entry set allocated_amount = 12.3400,"
                  + " allocation_status = 'ALLOCATED' where pool_id = ?",
              poolId);
          return null;
        });

    // 但「凭空标成已分摊」不行 —— 必须真有分摊批次。
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "update finance_cost_collection_pool set allocation_available = true"
                              + " where id = ?",
                          poolId);
                      return null;
                    }))
        .as("没有分摊批次就不许标成已分摊")
        .hasStackTraceContaining("凭空标成已分摊");
  }

  // ==========================================================================
  // 🔴 核心不变量：分摊结果之和 = 被分摊总额
  // ==========================================================================

  @Test
  void 分摊结果之和等于被分摊总额() throws Exception {
    var poolId =
        seedPool(
            "PLATFORM_FEE", "MONTH", MARCH_START, MARCH_END, "USD", new BigDecimal("100.0000"));
    createRule(
        "R-AMOUNT", "ORDER", "AMOUNT", "*", 10, "LARGEST_REMAINDER", null, null, "2026-01-01", null);

    var collected = collect(poolId, "ORDER");
    assertThat(collected.collected()).as("3 月的美元订单有三笔").isEqualTo(3);
    assertThat(collected.targets()).extracting(FinanceCostAllocationTarget::targetCode)
        .containsExactly("SO-001", "SO-002", "SO-003");

    var detail = allocate(poolId, null);

    var sum =
        detail.results().stream()
            .map(FinanceCostAllocationResult::allocatedAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(sum)
        .as("🔴 分摊结果之和必须逐分等于被分摊总额（100.0000）")
        .isEqualByComparingTo("100.0000");
    assertThat(detail.batch().allocatedTotal()).isEqualByComparingTo("100.0000");
    assertThat(detail.batch().sourceTotal()).isEqualByComparingTo("100.0000");

    // 逐行金额也���对上（按金额基数摊：50 / 30 / 20）。
    assertThat(detail.results()).extracting(FinanceCostAllocationResult::targetCode)
        .containsExactly("SO-001", "SO-002", "SO-003");
    assertThat(detail.results().get(0).allocatedAmount()).isEqualByComparingTo("50.0000");
    assertThat(detail.results().get(1).allocatedAmount()).isEqualByComparingTo("30.0000");
    assertThat(detail.results().get(2).allocatedAmount()).isEqualByComparingTo("20.0000");

    // 数据库层也复核一遍（不是只看接口返回）。
    var dbSum =
        one(
                "select coalesce(sum(allocated_amount),0) from finance_cost_allocation_result"
                    + " where batch_id = ?",
                detail.batch().id())
            .get(0, BigDecimal.class);
    assertThat(dbSum).as("库里逐行相加也必须等于被分摊总额").isEqualByComparingTo("100.0000");
    var dbBatch =
        one(
                "select source_total, allocated_total from finance_cost_allocation_batch where id = ?",
                detail.batch().id());
    assertThat(dbBatch.get("allocated_total", BigDecimal.class))
        .isEqualByComparingTo(dbBatch.get("source_total", BigDecimal.class));
  }

  // ==========================================================================
  // 舍入差处理：零头落到指定对象上并留痕
  // ==========================================================================

  @Test
  void 舍入差落到指定对象上并留痕() throws Exception {
    var poolId =
        seedPool("OFFICE_COST", "PERIOD", MARCH_START, MARCH_END, "USD", new BigDecimal("10.0000"));
    createRule(
        "R-EQUAL",
        "ORDER",
        "EQUAL",
        "*",
        10,
        "SPECIFIED",
        "ORDER",
        "SO-001",
        "2026-01-01",
        null);
    collect(poolId, "ORDER");

    var detail = allocate(poolId, null);

    // 10 / 3 = 3.3333…：先向下取整得 3.3333 × 3 = 9.9999 ⇒ 零头 0.0001 落到指定对象。
    assertThat(detail.results()).hasSize(3);
    var specified =
        detail.results().stream()
            .filter(r -> "SO-001".equals(r.targetCode()))
            .findFirst()
            .orElseThrow();
    assertThat(specified.allocatedAmount()).isEqualByComparingTo("3.3334");
    assertThat(specified.roundingAdjustment()).isEqualByComparingTo("0.0001");
    assertThat(specified.isRoundingTarget()).as("零头落点必须被标出来").isTrue();
    assertThat(specified.exactAmount()).isEqualByComparingTo("3.3333333333");

    // 其它两行没有拿到零头。
    for (var r : detail.results()) {
      if (!"SO-001".equals(r.targetCode())) {
        assertThat(r.allocatedAmount()).isEqualByComparingTo("3.3333");
        assertThat(r.roundingAdjustment()).isEqualByComparingTo("0.0000");
        assertThat(r.isRoundingTarget()).isFalse();
      }
    }

    // 批次上留痕：舍入差 + 落点对象。
    assertThat(detail.batch().roundingDifference()).isEqualByComparingTo("0.0001");
    assertThat(detail.batch().roundingTargetCode()).isEqualTo("SO-001");
    assertThat(detail.batch().roundingTargetType()).isEqualTo(AllocationDimension.ORDER);

    // 🔴 等式仍然成立：零头没有让总额多出或少掉一分。
    var sum =
        detail.results().stream()
            .map(FinanceCostAllocationResult::allocatedAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(sum).as("舍入差处理之后 Σ结果仍必须等于被分摊总额").isEqualByComparingTo("10.0000");
  }

  @Test
  void 舍入差策略不点名对象时被拒() throws Exception {
    var response =
        http.perform(
                post("/finance/cost-allocations/rules")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"ruleCode":"R-BAD","ruleName":"R-BAD","targetDimension":"ORDER",
                         "allocationBasis":"EQUAL","roundingPolicy":"SPECIFIED",
                         "effectiveFrom":"2026-01-01"}
                        """))
            .andReturn();
    assertThat(response.getResponse().getStatus()).isEqualTo(400);

    var input =
        new FinanceCostAllocationRuleInput(
            "R-BAD",
            "R-BAD",
            AllocationDimension.ORDER,
            AllocationBasis.EQUAL,
            null,
            null,
            RoundingPolicy.SPECIFIED,
            null,
            null,
            LocalDate.of(2026, 1, 1),
            null,
            null,
            null);
    assertThat(reasonOf(() -> allocations.createRule(input)))
        .as("要指名是哪个对象，否则零头无处可落")
        .contains("必须点名");
  }

  // ==========================================================================
  // 基数不可得：明确留痕，不参与分母
  // ==========================================================================

  @Test
  void 基数不可得的对象不参与分母且明确留痕() throws Exception {
    var poolId =
        seedPool(
            "HEAD_MILE_FEE", "SKU", MARCH_START, MARCH_END, "USD", new BigDecimal("40.0000"));
    createRule(
        "R-WEIGHT", "SKU", "WEIGHT", "*", 10, "LARGEST_REMAINDER", null, null, "2026-01-01", null);

    var collected = collect(poolId, "SKU");
    assertThat(collected.collected()).isEqualTo(2);
    var skuA =
        collected.targets().stream()
            .filter(t -> "SKU-A".equals(t.targetCode()))
            .findFirst()
            .orElseThrow();
    var skuB =
        collected.targets().stream()
            .filter(t -> "SKU-B".equals(t.targetCode()))
            .findFirst()
            .orElseThrow();
    // 数量 / 金额来自真实订单行；重量来自 catalog 的包装档案。
    assertThat(skuA.basisQuantity()).isEqualByComparingTo("3.000000");
    assertThat(skuA.basisAmount()).isEqualByComparingTo("30.0000");
    assertThat(skuA.basisWeight()).isEqualByComparingTo("500.000000");
    assertThat(skuB.basisQuantity()).isEqualByComparingTo("1.000000");
    assertThat(skuB.basisAmount()).isEqualByComparingTo("10.0000");
    assertThat(skuB.basisWeight()).as("catalog 没维护包装 ⇒ 重量取不到（不是 0）").isNull();
    assertThat(skuB.basisNote()).contains("包装");

    var detail = allocate(poolId, null);

    var b =
        detail.results().stream()
            .filter(r -> "SKU-B".equals(r.targetCode()))
            .findFirst()
            .orElseThrow();
    assertThat(b.basisAvailable()).isFalse();
    assertThat(b.allocatedAmount()).as("没分到 ≠ 它不用花钱").isEqualByComparingTo("0.0000");
    assertThat(b.basisNote()).isNotBlank();

    // SKU-A 独自承担全部金额（不可得的那行不参与分母，但**总额一分不少**）。
    var a =
        detail.results().stream()
            .filter(r -> "SKU-A".equals(r.targetCode()))
            .findFirst()
            .orElseThrow();
    assertThat(a.allocatedAmount()).isEqualByComparingTo("40.0000");

    var sum =
        detail.results().stream()
            .map(FinanceCostAllocationResult::allocatedAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(sum).isEqualByComparingTo("40.0000");
    assertThat(detail.batch().basisUnavailableCount()).isEqualTo(1);
    assertThat(detail.batch().targetCount()).isEqualTo(1);
  }

  @Test
  void 全部基数不可得时拒绝分摊而不是摊出全零的单() throws Exception {
    var poolId =
        seedPool("PLATFORM_FEE", "MONTH", APRIL_START, APRIL_END, "USD", new BigDecimal("10.0000"));
    createRule(
        "R-AMOUNT-APRIL", "ORDER", "AMOUNT", "*", 10, "LARGEST_REMAINDER", null, null,
        "2026-01-01", null);

    // 4 月只有一笔零金额订单 ⇒ 金额基数取不到。
    var collected = collect(poolId, "ORDER");
    assertThat(collected.collected()).isEqualTo(1);

    var response = allocateRaw(poolId, null);
    assertThat(response.getResponse().getStatus()).as("没有基数就摊不出结果").isEqualTo(422);
    assertThat(allocateReason(poolId, null))
        .as("要说得清是「基数取不到」，而不是一个干巴巴的状态码")
        .contains("取不到");

    // 拒绝 ⇒ 一张分摊批次都不许留下。
    var batches =
        one("select count(*) from finance_cost_allocation_batch where pool_id = ?", poolId)
            .get(0, Long.class);
    assertThat(batches).isZero();
  }

  @Test
  void 没有分摊对象时拒绝分摊() throws Exception {
    var poolId =
        seedPool("PLATFORM_FEE", "MONTH", APRIL_START, APRIL_END, "USD", new BigDecimal("10.0000"));
    createRule(
        "R-AMOUNT-APRIL", "ORDER", "AMOUNT", "*", 10, "LARGEST_REMAINDER", null, null,
        "2026-01-01", null);

    // 没有采集 ⇒ 没有对象 ⇒ 拒绝摊。
    var response = allocateRaw(poolId, null);
    assertThat(response.getResponse().getStatus()).isEqualTo(422);
    assertThat(allocateReason(poolId, null)).contains("分摊对象");
  }

  // ==========================================================================
  // 生效区间 / 分摊顺序 / 重复分摊 / 撤销
  // ==========================================================================

  @Test
  void 生效区间不覆盖归属期间时规则不命中() throws Exception {
    var poolId =
        seedPool("PLATFORM_FEE", "MONTH", MARCH_START, MARCH_END, "USD", new BigDecimal("100.0000"));
    // 规则只覆盖 1 ~ 2 月，池在 3 月 ⇒ 不命中（历史分摊不会被新规则追溯改写）。
    createRule(
        "R-OLD", "ORDER", "AMOUNT", "*", 10, "LARGEST_REMAINDER", null, null, "2026-01-01",
        "2026-02-28");
    collect(poolId, "ORDER");

    var response = allocateRaw(poolId, null);
    assertThat(response.getResponse().getStatus()).isEqualTo(422);
    assertThat(allocateReason(poolId, null))
        .as("要说得清是「没有匹配的规则」还是「规则区间不覆盖」")
        .contains("没有匹配的分摊规则");
  }

  @Test
  void 分摊顺序决定命中哪条规则() throws Exception {
    var poolId =
        seedPool("PLATFORM_FEE", "MONTH", MARCH_START, MARCH_END, "USD", new BigDecimal("100.0000"));
    var first =
        createRule(
            "R-FIRST", "ORDER", "AMOUNT", "*", 10, "LARGEST_REMAINDER", null, null, "2026-01-01",
            null);
    createRule(
        "R-SECOND", "ORDER", "EQUAL", "*", 20, "LARGEST_REMAINDER", null, null, "2026-01-01", null);
    collect(poolId, "ORDER");

    var detail = allocate(poolId, null);
    assertThat(detail.batch().ruleId()).as("数字小的先用").isEqualTo(first);
    assertThat(detail.batch().ruleCode()).isEqualTo("R-FIRST");
    // 按金额摊（不是等额）⇒ 50 / 30 / 20。
    assertThat(detail.results().get(0).allocatedAmount()).isEqualByComparingTo("50.0000");
  }

  @Test
  void 同一个池不能分摊两次() throws Exception {
    var poolId =
        seedPool("PLATFORM_FEE", "MONTH", MARCH_START, MARCH_END, "USD", new BigDecimal("100.0000"));
    createRule(
        "R-AMOUNT", "ORDER", "AMOUNT", "*", 10, "LARGEST_REMAINDER", null, null, "2026-01-01", null);
    collect(poolId, "ORDER");
    allocate(poolId, null);

    var response = allocateRaw(poolId, null);
    assertThat(response.getResponse().getStatus()).as("重复分摊会把钱摊两遍").isEqualTo(409);
    assertThat(allocateReason(poolId, null)).contains("已经分摊过");

    // DB 层也只有一个「有效」批次。
    var active =
        one(
                "select count(*) from finance_cost_allocation_batch"
                    + " where pool_id = ? and status = 'ALLOCATED'",
                poolId)
            .get(0, Long.class);
    assertThat(active).isEqualTo(1);
  }

  @Test
  void 撤销分摊后池回到未分摊且历史留档() throws Exception {
    var poolId =
        seedPool("PLATFORM_FEE", "MONTH", MARCH_START, MARCH_END, "USD", new BigDecimal("100.0000"));
    createRule(
        "R-AMOUNT", "ORDER", "AMOUNT", "*", 10, "LARGEST_REMAINDER", null, null, "2026-01-01", null);
    collect(poolId, "ORDER");
    var detail = allocate(poolId, null);

    // 分摊后：上游的三列被同步。
    var pool = one("select allocation_available from finance_cost_collection_pool where id = ?", poolId);
    assertThat(pool.get(0, Boolean.class)).isTrue();
    var entry =
        one(
            "select allocated_amount, allocation_status from finance_cost_collection_entry"
                + " where pool_id = ?",
            poolId);
    assertThat(entry.get("allocated_amount", BigDecimal.class)).isEqualByComparingTo("100.0000");
    assertThat(entry.get("allocation_status", String.class)).isEqualTo("ALLOCATED");

    var reverted =
        json.readValue(
            http.perform(
                    post("/finance/cost-allocations/batches/" + detail.batch().id() + "/revert")
                        .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceCostAllocationBatchDetail.class);

    assertThat(reverted.batch().status()).isEqualTo(BatchStatus.REVERTED);
    assertThat(reverted.batch().revertedAt()).isNotNull();
    assertThat(reverted.results()).as("被撤销的那次分摊结果留档（历史不消失）").hasSize(3);

    // 上游回到未分摊。
    var pool2 = one("select allocation_available from finance_cost_collection_pool where id = ?", poolId);
    assertThat(pool2.get(0, Boolean.class)).isFalse();
    var entry2 =
        one(
            "select allocated_amount, allocation_status from finance_cost_collection_entry"
                + " where pool_id = ?",
            poolId);
    assertThat(entry2.get("allocated_amount", BigDecimal.class)).isEqualByComparingTo("0");
    assertThat(entry2.get("allocation_status", String.class)).isEqualTo("UNALLOCATED");

    // 撤销后可以按新规则重摊。
    var second =
        createRule(
            "R-EQUAL", "ORDER", "EQUAL", "*", 20, "LARGEST_REMAINDER", null, null, "2026-01-01", null);
    var again = allocate(poolId, second);
    assertThat(again.batch().status()).isEqualTo(BatchStatus.ALLOCATED);
    var sum =
        again.results().stream()
            .map(FinanceCostAllocationResult::allocatedAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(sum).isEqualByComparingTo("100.0000");
  }

  @Test
  void 产生过分摊的规则不允许改口径() throws Exception {
    var poolId =
        seedPool("PLATFORM_FEE", "MONTH", MARCH_START, MARCH_END, "USD", new BigDecimal("100.0000"));
    var ruleId =
        createRule(
            "R-AMOUNT", "ORDER", "AMOUNT", "*", 10, "LARGEST_REMAINDER", null, null, "2026-01-01",
            null);
    collect(poolId, "ORDER");
    allocate(poolId, null);

    var response =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .put("/finance/cost-allocations/rules/" + ruleId)
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"ruleCode":"R-AMOUNT","ruleName":"R-AMOUNT","targetDimension":"ORDER",
                         "allocationBasis":"EQUAL","effectiveFrom":"2026-01-01"}
                        """))
            .andReturn();
    assertThat(response.getResponse().getStatus()).isEqualTo(409);

    var input =
        new FinanceCostAllocationRuleInput(
            "R-AMOUNT",
            "R-AMOUNT",
            AllocationDimension.ORDER,
            AllocationBasis.EQUAL,
            null,
            null,
            null,
            null,
            null,
            LocalDate.of(2026, 1, 1),
            null,
            null,
            null);
    assertThat(reasonOf(() -> allocations.updateRule(ruleId, input)))
        .as("要告诉操作员「新建一条规则」，而不是让他以为改一下就行")
        .contains("新建一条规则");
  }

  // ==========================================================================
  // 时间字段：断言写回的值（timestamptz 不错 −28800s）
  // ==========================================================================

  @Test
  void 时间字段写回的是同一个瞬间而不是偏移过的本地时间() throws Exception {
    var poolId =
        seedPool("PLATFORM_FEE", "MONTH", MARCH_START, MARCH_END, "USD", new BigDecimal("100.0000"));
    createRule(
        "R-AMOUNT", "ORDER", "AMOUNT", "*", 10, "LARGEST_REMAINDER", null, null, "2026-01-01", null);
    collect(poolId, "ORDER");
    var detail = allocate(poolId, null);

    var dbEpoch =
        one(
                "select extract(epoch from allocated_at) from finance_cost_allocation_batch"
                    + " where id = ?",
                detail.batch().id())
            .get(0, BigDecimal.class);
    assertThat(dbEpoch.longValue())
        .as("库里写下的瞬间必须与接口返回的一致")
        .isEqualTo(detail.batch().allocatedAt().toEpochSecond());
    assertThat(detail.batch().allocatedAt().getOffset())
        .as("必须按 UTC 归一（用 Timestamp.valueOf 会静默偏 −28800s）")
        .isEqualTo(ZoneOffset.UTC);

    var reverted =
        json.readValue(
            http.perform(
                    post("/finance/cost-allocations/batches/" + detail.batch().id() + "/revert")
                        .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceCostAllocationBatchDetail.class);
    var revertedEpoch =
        one(
                "select extract(epoch from reverted_at) from finance_cost_allocation_batch"
                    + " where id = ?",
                detail.batch().id())
            .get(0, BigDecimal.class);
    assertThat(revertedEpoch.longValue())
        .isEqualTo(reverted.batch().revertedAt().toEpochSecond());
    assertThat(reverted.batch().revertedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
  }

  // ==========================================================================
  // 采集：真实上游事实
  // ==========================================================================

  @Test
  void 采集分摊对象只收本租户本期间本币种的事实() throws Exception {
    var poolId =
        seedPool("PLATFORM_FEE", "MONTH", MARCH_START, MARCH_END, "USD", new BigDecimal("100.0000"));
    var collected = collect(poolId, "ORDER");

    // 欧元订单（SO-004）不进美元池；4 月的订单不进 3 月的池。
    assertThat(collected.targets()).extracting(FinanceCostAllocationTarget::targetCode)
        .containsExactly("SO-001", "SO-002", "SO-003");
    assertThat(collected.targets()).extracting(FinanceCostAllocationTarget::basisSource)
        .containsOnly("TRADE_PLATFORM_ORDER");
    assertThat(collected.skipped()).as("被跳过的条数也要看得见").isGreaterThan(0);
  }

  @Test
  void 店铺维度按店铺汇总并用店铺主数据取名字() throws Exception {
    var poolId =
        seedPool("PLATFORM_FEE", "MONTH", MARCH_START, MARCH_END, "USD", new BigDecimal("100.0000"));
    var collected = collect(poolId, "SHOP");

    assertThat(collected.targets()).extracting(FinanceCostAllocationTarget::targetCode)
        .containsExactly("SHOP-A", "SHOP-B");
    var shopA =
        collected.targets().stream()
            .filter(t -> "SHOP-A".equals(t.targetCode()))
            .findFirst()
            .orElseThrow();
    assertThat(shopA.targetName()).isEqualTo("店铺甲");
    assertThat(shopA.basisAmount()).as("SHOP-A = 50 + 20").isEqualByComparingTo("70.0000");
  }

  @Test
  void spu维度由sku汇总且部分缺包装时整体不可得() throws Exception {
    var poolId =
        seedPool("HEAD_MILE_FEE", "SKU", MARCH_START, MARCH_END, "USD", new BigDecimal("40.0000"));
    var collected = collect(poolId, "SPU");

    assertThat(collected.collected()).isEqualTo(1);
    var spu = collected.targets().get(0);
    assertThat(spu.targetCode()).isEqualTo("cccccccc-1111-4111-8111-111111111111");
    assertThat(spu.basisQuantity()).isEqualByComparingTo("4.000000");
    assertThat(spu.basisAmount()).isEqualByComparingTo("40.0000");
    assertThat(spu.basisWeight())
        .as("构成 SKU 有一个没维护包装 ⇒ 这一档整体不可得（少算的和不叫缺失，叫算错）")
        .isNull();
    assertThat(spu.basisNote()).contains("未维护");
  }

  @Test
  void 类目维度由sku汇总() throws Exception {
    var poolId =
        seedPool("HEAD_MILE_FEE", "SKU", MARCH_START, MARCH_END, "USD", new BigDecimal("40.0000"));
    var collected = collect(poolId, "CATEGORY");

    assertThat(collected.collected()).isEqualTo(1);
    var category = collected.targets().get(0);
    assertThat(category.targetCode()).isEqualTo("CAT-A");
    assertThat(category.basisQuantity()).isEqualByComparingTo("4.000000");
    assertThat(category.basisAmount()).isEqualByComparingTo("40.0000");
  }

  @Test
  void 重复采集是幂等的() throws Exception {
    var poolId =
        seedPool("PLATFORM_FEE", "MONTH", MARCH_START, MARCH_END, "USD", new BigDecimal("100.0000"));
    collect(poolId, "ORDER");
    collect(poolId, "ORDER");

    var count =
        one(
                "select count(*) from finance_cost_allocation_target where pool_id = ?",
                poolId)
            .get(0, Long.class);
    assertThat(count).as("同一个池上同一个对象只留一行").isEqualTo(3);
  }

  // ==========================================================================
  // 列表：真分页 + 汇总
  // ==========================================================================

  @Test
  void 分摊批次与结果列表是真分页且汇总覆盖整个筛选结果集() throws Exception {
    var poolId =
        seedPool("PLATFORM_FEE", "MONTH", MARCH_START, MARCH_END, "USD", new BigDecimal("100.0000"));
    createRule(
        "R-AMOUNT", "ORDER", "AMOUNT", "*", 10, "LARGEST_REMAINDER", null, null, "2026-01-01", null);
    collect(poolId, "ORDER");
    allocate(poolId, null);

    var page =
        getFromJson(
            "/finance/cost-allocations/batches?poolId=" + poolId + "&size=1", FinanceCostAllocationBatchPage.class);
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items()).hasSize(1);
    assertThat(page.totalsByCurrency()).hasSize(1);
    assertThat(page.totalsByCurrency().get(0).currency()).isEqualTo("USD");
    assertThat(page.totalsByCurrency().get(0).sourceTotal()).isEqualByComparingTo("100.0000");
    assertThat(page.totalsByCurrency().get(0).allocatedTotal())
        .as("🔴 汇总里也必须对得上")
        .isEqualByComparingTo("100.0000");

    var results =
        getFromJson(
            "/finance/cost-allocations/results?poolId=" + poolId + "&size=2",
            FinanceCostAllocationResultPage.class);
    assertThat(results.total()).isEqualTo(3);
    assertThat(results.items()).hasSize(2);
    assertThat(results.totalAmount())
        .as("汇总覆盖整个筛选结果集（不是当前页）")
        .isEqualByComparingTo("100.0000");

    var pools =
        getFromJson(
            "/finance/cost-allocations/pools?poolKey=PLATFORM_FEE", FinanceCostAllocationPoolPage.class);
    assertThat(pools.total()).isEqualTo(1);
    assertThat(pools.items().get(0).targetCount()).isEqualTo(3);
    assertThat(pools.items().get(0).allocationAvailable()).isTrue();
    assertThat(pools.items().get(0).allocatedBatchId()).isNotNull();
  }

  @Test
  void 能力声明与筛选项来自真实数据() throws Exception {
    var capabilities =
        getFromJson(
            "/finance/cost-allocations/capabilities", FinanceCostAllocationCapabilities.class);
    assertThat(capabilities.capabilities()).isNotEmpty();
    assertThat(capabilities.gaps()).isNotEmpty();
    assertThat(capabilities.gaps())
        .extracting(FinanceCostAllocationDownstreamGap::moduleId)
        .contains("13.4.1.1");

    var poolId =
        seedPool("PLATFORM_FEE", "MONTH", MARCH_START, MARCH_END, "USD", new BigDecimal("100.0000"));
    createRule(
        "R-AMOUNT", "ORDER", "AMOUNT", "*", 10, "LARGEST_REMAINDER", null, null, "2026-01-01", null);
    var options =
        getFromJson("/finance/cost-allocations/options", FinanceCostAllocationOptions.class);
    assertThat(options.dimensions()).contains(AllocationDimension.SKU, AllocationDimension.SPU,
        AllocationDimension.SHOP, AllocationDimension.ORDER, AllocationDimension.CATEGORY);
    assertThat(options.bases()).contains(AllocationBasis.QUANTITY, AllocationBasis.AMOUNT,
        AllocationBasis.WEIGHT, AllocationBasis.VOLUME, AllocationBasis.EQUAL);
    assertThat(options.poolKeys()).contains("PLATFORM_FEE");
    assertThat(options.ruleCodes()).contains("R-AMOUNT");
    assertThat(options.currencies()).contains("USD");
  }
}
