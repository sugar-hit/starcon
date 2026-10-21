package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
 * 6.2.1.3 采购计划（trade 侧）。
 *
 * <p><b>本测试是 V242 迁移那些 DB 级不变量的独立复验</b>：逐条在真实 PostgreSQL 16 上跑一遍，
 * 并且走 HTTP 端点而不是直连数据库，以便同时验证「服务层 + DB 层」两层都没有把闸门漏掉。
 *
 * <p><b>上游数据怎么来的（以及为什么这样）</b>：
 *
 * <ul>
 *   <li><b>补货建议（6.2.1.2）</b>直接种进它自己的表 {@code replenishment_suggestion} ——
 *       本模块是它的**下游消费者**，读它的事实表是本模块要证明的事；6.2.1.2 自己的算式与
 *       处置留痕由它自己的测试负责，在这里重跑一遍只会重复它的用例。</li>
 *   <li><b>目标仓 / 法人 / 预占</b>走 warehouse 的 stub HTTP 服务（不是 new 一个假对象）：
 *       跨服务一律走端口，禁跨库 SQL / FK / 视图。stub 只按上游契约形状返回数。</li>
 * </ul>
 *
 * <p><b>不碰 6.2.1.2 的表做写入</b>：种数据用的是迁移用户（超级用户），
 * 生产运行角色 trade_app 对这些表只有 6.2.1.2 授予的权限，本模块不新增任何权限。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PurchasePlanIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity, warehouse;
  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          // 数量必须按 BigDecimal 读回来：用 Double 会把 360.000000 变成 360.0，
          // 断言时丢精度（而且看不出是精度问题还是口径问题）。
          .enable(
              com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 目标仓 / 法人（warehouse 侧的参照）。 */
  static final UUID warehouseId = UUID.randomUUID();
  static final UUID legalEntityId = UUID.randomUUID();

  /** 大促档期。 */
  static final LocalDate CAMPAIGN_START = LocalDate.of(2026, 11, 20);
  static final LocalDate CAMPAIGN_END = LocalDate.of(2026, 11, 30);
  static final int CAMPAIGN_LEAD_DAYS = 25;

  /** 月度计划周期。 */
  static final LocalDate PERIOD_START = LocalDate.of(2026, 11, 1);
  static final LocalDate PERIOD_END = LocalDate.of(2026, 11, 30);

  static final List<String> allPermissions =
      List.of("trade.purchase-plan.read", "trade.purchase-plan.update");

  /** warehouse 预占登记的应答：置 true 时返回 403（模拟权限不足）。 */
  static final AtomicBoolean reservationForbidden = new AtomicBoolean(false);

  /** warehouse 预占登记被调用的次数。 */
  static final AtomicInteger reservationCalls = new AtomicInteger();

  /** warehouse 登记预占时收到的请求体（最后一次）。 */
  static final Map<String, Object> lastReservationBody = new ConcurrentHashMap<>();

  static UUID skuFor(int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-purchase-plan-sku-" + ordinal).getBytes());
  }

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static void write(HttpServer server, com.sun.net.httpserver.HttpExchange e, int code, Object body)
      throws java.io.IOException {
    byte[] bytes = json.writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(code, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }

  static UUID tenantOf(String bearer) {
    var access = actors.get(bearer);
    return access == null ? null : access.tenantId();
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
      }
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
      actors.put("Bearer tenant-a", actor(user, a, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, List.of(grant("ALL", allPermissions))));
      identity.start();

      // ---- warehouse stub：仓库 / 法人参照 + 7.2.1.1 预占登记 ----
      warehouse = HttpServer.create(new InetSocketAddress(0), 0);
      warehouse.createContext(
          "/warehouse/warehouses",
          e -> {
            UUID tenant = tenantOf(e.getRequestHeaders().getFirst("Authorization"));
            if (tenant == null) {
              write(warehouse, e, 401, Map.of("message", "未认证"));
              return;
            }
            write(
                warehouse,
                e,
                200,
                List.of(
                    Map.of(
                        "id", warehouseId.toString(),
                        "code", "WH-LA-01",
                        "name", "洛杉矶仓",
                        "countryCode", "US",
                        "status", "ACTIVE",
                        "isDefault", true)));
          });
      warehouse.createContext(
          "/warehouse/legal-entities",
          e -> {
            UUID tenant = tenantOf(e.getRequestHeaders().getFirst("Authorization"));
            if (tenant == null) {
              write(warehouse, e, 401, Map.of("message", "未认证"));
              return;
            }
            write(
                warehouse,
                e,
                200,
                List.of(
                    Map.of(
                        "id", legalEntityId.toString(),
                        "code", "US-LLC",
                        "name", "美国子公司",
                        "status", "ACTIVE")));
          });
      warehouse.createContext(
          "/warehouse/inventory/reservations",
          e -> {
            UUID tenant = tenantOf(e.getRequestHeaders().getFirst("Authorization"));
            if (tenant == null) {
              write(warehouse, e, 401, Map.of("message", "未认证"));
              return;
            }
            reservationCalls.incrementAndGet();
            String raw = new String(e.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = json.readValue(raw, Map.class);
            lastReservationBody.putAll(body);
            if (reservationForbidden.get()) {
              write(warehouse, e, 403, Map.of("message", "当前身份无权在仓库登记预占"));
              return;
            }
            write(
                warehouse,
                e,
                200,
                Map.of(
                    "id", UUID.randomUUID().toString(),
                    "reservationNo", "RSV-20261101-000001",
                    "status", "HELD",
                    "statusLabel", "预占中"));
          });
      warehouse.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "trade_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    // 锁库端口必须真的接线（WAREHOUSE_URL 非空 ⇒ 走 HTTP 实现而不是 Unwired 实现）。
    r.add("WAREHOUSE_URL", () -> "http://localhost:" + warehouse.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    warehouse.stop(0);
    pg.stop();
  }

  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 数据准备

  /** 用迁移用户（超级用户）连接：绕开 RLS，只用来清表与种上游事实。 */
  private static Connection admin() throws Exception {
    return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }

  /** 清掉本模块与 6.2.1.2 的表。 */
  private static void truncateAll() throws Exception {
    try (var c = admin();
        var s = c.createStatement()) {
      s.execute(
          "truncate purchase_plan_adjustment, purchase_plan_lock_log, purchase_plan_line,"
              + " purchase_plan, purchase_plan_campaign, purchase_plan_lock_policy,"
              + " replenishment_suggestion_decision, replenishment_suggestion_step,"
              + " replenishment_suggestion, replenishment_suggestion_batch cascade");
    }
  }

  /**
   * 种一批 6.2.1.2 的补货建议（上游事实）。
   *
   * @param advice REPLENISH / HOLD / NO_QUOTE …
   * @param status SUGGESTED / ACCEPTED / ADJUSTED / IGNORED
   * @param suggested 建议量（null = 上游给不出量）
   * @param decided 处置后的量（null = 未处置）
   */
  private static UUID seedSuggestion(
      UUID tenant,
      UUID batchId,
      int ordinal,
      String skuCode,
      String advice,
      String status,
      String suggested,
      String decided,
      LocalDate arrival) throws Exception {
    UUID skuId = skuFor(ordinal);
    UUID id = UUID.randomUUID();
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into replenishment_suggestion(id,tenant_id,batch_id,sku_id,sku_code,sku_name,"
                    + " coverage_days,coverage_source,available_quantity,in_transit_quantity,"
                    + " suggested_quantity,expected_arrival_date,advice,advice_basis,status,"
                    + " decided_quantity,decision_by,decision_at,sort_rank,created_at)"
                    + " values (?::uuid,?::uuid,?::uuid,?::uuid,?,?,30,'DEFAULT',0,0,?::numeric,"
                    + " ?::date,?,?::varchar,?::varchar,?::numeric,?::uuid,?::timestamptz,?,now())")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setObject(3, batchId);
      ps.setObject(4, skuId);
      ps.setString(5, skuCode);
      ps.setString(6, skuCode + " 名称");
      if (suggested == null) {
        ps.setNull(7, java.sql.Types.NUMERIC);
      } else {
        ps.setBigDecimal(7, new BigDecimal(suggested));
      }
      if (arrival == null) {
        ps.setNull(8, java.sql.Types.DATE);
      } else {
        ps.setDate(8, java.sql.Date.valueOf(arrival));
      }
      ps.setString(9, advice);
      ps.setString(10, "测试种入的上游事实");
      ps.setString(11, status);
      if (decided == null) {
        ps.setNull(12, java.sql.Types.NUMERIC);
        // DB 层的「处置状态与处置信息必须自洽」：没处置过就没有处置人与处置时刻。
        ps.setNull(13, java.sql.Types.OTHER);
        ps.setNull(14, java.sql.Types.TIMESTAMP);
      } else {
        ps.setBigDecimal(12, new BigDecimal(decided));
        ps.setObject(13, user);
        ps.setTimestamp(14, new java.sql.Timestamp(System.currentTimeMillis()));
      }
      ps.setInt(15, ordinal);
      ps.executeUpdate();
    }
    return id;
  }

  private static UUID seedBatch(UUID tenant) throws Exception {
    UUID id = UUID.randomUUID();
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into replenishment_suggestion_batch(id,tenant_id,batch_no,as_of_date,"
                    + " computed_at,default_coverage_days,created_by,created_at)"
                    + " values (?::uuid,?::uuid,'RS-20261101-TEST',?::date,now(),30,?::uuid,now())")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setDate(3, java.sql.Date.valueOf(LocalDate.of(2026, 11, 1)));
      ps.setObject(4, user);
      ps.executeUpdate();
    }
    return id;
  }

  @BeforeEach
  void seed() throws Exception {
    truncateAll();
    reservationForbidden.set(false);
    reservationCalls.set(0);
    lastReservationBody.clear();
  }

  // ================================================================ 工具

  @SuppressWarnings("unchecked")
  Map<String, Object> read(String payload) throws Exception {
    return json.readValue(payload, Map.class);
  }

  List<Map<String, Object>> rowsOf(String payload) throws Exception {
    Map<String, Object> parsed = read(payload);
    return (List<Map<String, Object>>) parsed.getOrDefault("rows", List.of());
  }

  /** 建一条大促档期，返回它的 id。 */
  String createCampaign(String code, String salesMultiplier, String stockFactor, int leadDays)
      throws Exception {
    String payload =
        http.perform(
                post("/trade/purchase-plans/campaigns")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "code", code,
                                "name", code + " 大促",
                                "shopCode", "SHOP-US-01",
                                "startDate", CAMPAIGN_START.toString(),
                                "endDate", CAMPAIGN_END.toString(),
                                "salesMultiplier", new BigDecimal(salesMultiplier),
                                "stockFactor", new BigDecimal(stockFactor),
                                "arrivalLeadDays", leadDays))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return String.valueOf(read(payload).get("id"));
  }

  /** 建一张计划，返回它的 id。 */
  String createPlan(String planType, String campaignId) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("planType", planType);
    if (campaignId != null) {
      body.put("campaignId", campaignId);
    }
    body.put("periodType", "MONTHLY");
    body.put("periodStart", PERIOD_START.toString());
    body.put("periodEnd", PERIOD_END.toString());
    body.put("targetWarehouseId", warehouseId.toString());
    body.put("legalEntityId", legalEntityId.toString());
    body.put("priority", "HIGH");
    String payload =
        http.perform(
                post("/trade/purchase-plans")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return String.valueOf(read(payload).get("id"));
  }

  String aggregate(String planId, UUID batchId) throws Exception {
    return http.perform(
            post("/trade/purchase-plans/" + planId + "/aggregate")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("sourceBatchId", batchId.toString()))))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  // ================================================================ 迁移落地

  @Test
  void 迁移确实落地了且rls是强制的() {
    for (String table :
        List.of(
            "purchase_plan_campaign",
            "purchase_plan",
            "purchase_plan_line",
            "purchase_plan_adjustment",
            "purchase_plan_lock_log",
            "purchase_plan_lock_policy")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V242 必须真的建出 %s 这张表", table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as("%s 的 RLS 未启用", table).isTrue();
      assertThat(row.get(1, Boolean.class)).as("%s 只有 FORCE 才挡得住「运行角色被误授 owner」", table)
          .isTrue();
      assertThat(
              sql.fetchOne(
                      "select has_table_privilege('trade_app',?,'SELECT')", table)
                  .get(0, Boolean.class))
          .as("trade_app 必须真的能读 %s", table)
          .isTrue();
    }
  }

  @Test
  void 汇总不会改动补货建议本身() throws Exception {
    UUID batchId = seedBatch(a);
    UUID suggestionId =
        seedSuggestion(
            a, batchId, 1, "SKU-001", "REPLENISH", "SUGGESTED", "100", null,
            LocalDate.of(2026, 11, 10));
    String planId = createPlan("ROUTINE", null);
    aggregate(planId, batchId);

    // 补货建议的**写入所有权是 6.2.1.2**：本模块汇总完不能动它一个字。
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "select suggested_quantity,status,expected_arrival_date from"
                    + " replenishment_suggestion where id = ?::uuid")) {
      ps.setObject(1, suggestionId);
      try (var rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getBigDecimal(1)).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(rs.getString(2)).isEqualTo("SUGGESTED");
        assertThat(rs.getDate(3)).isEqualTo(java.sql.Date.valueOf(LocalDate.of(2026, 11, 10)));
      }
    }
    assertThat(suggestionId).isNotNull();
  }

  // ================================================================ 大促日历

  @Test
  void 大促日历可以维护且真分页() throws Exception {
    createCampaign("CAMPAIGN-01", "3", "1.2", 25);
    createCampaign("CAMPAIGN-02", "2", "1", 10);
    createCampaign("CAMPAIGN-03", "1.5", "1", 5);

    // 真分页：第 1 页 2 条、总数 3。
    String page0 =
        http.perform(
                get("/trade/purchase-plans/campaigns")
                    .header("Authorization", "Bearer tenant-a")
                    .param("page", "0")
                    .param("size", "2"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Map<String, Object> parsed = read(page0);
    assertThat(((List<?>) parsed.get("rows")).size()).as("第 1 页应当只有 2 条").isEqualTo(2);
    assertThat(((Number) parsed.get("total")).longValue()).as("总数是 3").isEqualTo(3L);
    assertThat(((Number) parsed.get("page")).intValue()).isZero();

    String page1 =
        http.perform(
                get("/trade/purchase-plans/campaigns")
                    .header("Authorization", "Bearer tenant-a")
                    .param("page", "1")
                    .param("size", "2"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(rowsOf(page1)).as("第 2 页应当只有 1 条").hasSize(1);

    // 真筛选：按编码前缀。
    String filtered =
        http.perform(
                get("/trade/purchase-plans/campaigns")
                    .header("Authorization", "Bearer tenant-a")
                    .param("code", "CAMPAIGN-01"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(rowsOf(filtered)).hasSize(1);
    assertThat(rowsOf(filtered).get(0).get("code")).isEqualTo("CAMPAIGN-01");

    // 改。
    String campaignId = createCampaign("CAMPAIGN-EDIT", "2", "1", 10);
    http.perform(
            put("/trade/purchase-plans/campaigns/" + campaignId)
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "code", "CAMPAIGN-EDIT",
                            "name", "改过的大促",
                            "startDate", CAMPAIGN_START.toString(),
                            "endDate", CAMPAIGN_END.toString(),
                            "salesMultiplier", new BigDecimal("4"),
                            "stockFactor", BigDecimal.ONE,
                            "arrivalLeadDays", 12,
                            "status", "ACTIVE"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("改过的大促"))
        .andExpect(jsonPath("$.salesMultiplier").value(4.0))
        .andExpect(jsonPath("$.status").value("ACTIVE"));

    // 删（没被引用时删得掉）。
    http.perform(delete("/trade/purchase-plans/campaigns/" + campaignId).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNoContent());
    String afterDelete =
        http.perform(
                get("/trade/purchase-plans/campaigns")
                    .header("Authorization", "Bearer tenant-a")
                    .param("code", "CAMPAIGN-EDIT"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(rowsOf(afterDelete)).isEmpty();
  }

  @Test
  void 大促档期被计划引用时删不掉() throws Exception {
    String campaignId = createCampaign("CAMPAIGN-INUSE", "3", "1", 25);
    createPlan("CAMPAIGN", campaignId);
    http.perform(
            delete("/trade/purchase-plans/campaigns/" + campaignId)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isConflict());
  }

  // ================================================================ 建计划

  @Test
  void 周期边界不对会被拒() throws Exception {
    // 月度计划的起点必须是 1 号。
    http.perform(
            post("/trade/purchase-plans")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "planType", "ROUTINE",
                            "periodType", "MONTHLY",
                            "periodStart", "2026-11-05",
                            "periodEnd", "2026-11-30",
                            "targetWarehouseId", warehouseId.toString(),
                            "legalEntityId", legalEntityId.toString()))))
        .andExpect(status().isBadRequest());

    // 月度计划的终点必须是月末。
    http.perform(
            post("/trade/purchase-plans")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "planType", "ROUTINE",
                            "periodType", "MONTHLY",
                            "periodStart", "2026-11-01",
                            "periodEnd", "2026-11-29",
                            "targetWarehouseId", warehouseId.toString(),
                            "legalEntityId", legalEntityId.toString()))))
        .andExpect(status().isBadRequest());

    // 季度计划：起点必须是季首月的 1 号，终点必须是季末月末。
    http.perform(
            post("/trade/purchase-plans")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "planType", "ROUTINE",
                            "periodType", "QUARTERLY",
                            "periodStart", "2026-11-01",
                            "periodEnd", "2026-12-31",
                            "targetWarehouseId", warehouseId.toString(),
                            "legalEntityId", legalEntityId.toString()))))
        .andExpect(status().isBadRequest());

    // 正确的季度周期 ⇒ 通过。
    http.perform(
            post("/trade/purchase-plans")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "planType", "ROUTINE",
                            "periodType", "QUARTERLY",
                            "periodStart", "2026-10-01",
                            "periodEnd", "2026-12-31",
                            "targetWarehouseId", warehouseId.toString(),
                            "legalEntityId", legalEntityId.toString()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.periodTypeLabel").value("季度"));
  }

  @Test
  void 大促计划必须带档期且周期要覆盖整个档期() throws Exception {
    // 大促计划不带 campaignId ⇒ 拒。
    http.perform(
            post("/trade/purchase-plans")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "planType", "CAMPAIGN",
                            "periodType", "MONTHLY",
                            "periodStart", PERIOD_START.toString(),
                            "periodEnd", PERIOD_END.toString(),
                            "targetWarehouseId", warehouseId.toString(),
                            "legalEntityId", legalEntityId.toString()))))
        .andExpect(status().isBadRequest());

    // 日常计划挂大促 ⇒ 拒（大促与日常必须分开）。
    String campaignId = createCampaign("CAMPAIGN-MIX", "3", "1", 25);
    http.perform(
            post("/trade/purchase-plans")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "planType", "ROUTINE",
                            "campaignId", campaignId,
                            "periodType", "MONTHLY",
                            "periodStart", PERIOD_START.toString(),
                            "periodEnd", PERIOD_END.toString(),
                            "targetWarehouseId", warehouseId.toString(),
                            "legalEntityId", legalEntityId.toString()))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 建计划时会解析目标仓与法人() throws Exception {
    String planId = createPlan("ROUTINE", null);
    String payload =
        http.perform(
                get("/trade/purchase-plans/" + planId).header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Map<String, Object> plan = (Map<String, Object>) read(payload).get("plan");
    assertThat(plan.get("targetWarehouseCode")).isEqualTo("WH-LA-01");
    assertThat(plan.get("targetWarehouseName")).isEqualTo("洛杉矶仓");
    assertThat(plan.get("legalEntityCode")).isEqualTo("US-LLC");
  }

  // ================================================================ 汇总成计划行

  @Test
  void 大促计划按系数放大且期望到货日提前() throws Exception {
    UUID batchId = seedBatch(a);
    seedSuggestion(
        a, batchId, 1, "SKU-001", "REPLENISH", "SUGGESTED", "100", null,
        LocalDate.of(2026, 11, 10));
    seedSuggestion(
        a, batchId, 2, "SKU-002", "REPLENISH", "ACCEPTED", "50", "60",
        LocalDate.of(2026, 11, 12));
    // 「无需补货」与「答不出来」的建议**不能**变成计划行。
    seedSuggestion(a, batchId, 3, "SKU-003", "HOLD", "SUGGESTED", null, null, null);
    seedSuggestion(a, batchId, 4, "SKU-004", "NO_QUOTE", "SUGGESTED", null, null, null);

    String campaignId = createCampaign("CAMPAIGN-BIG", "3", "1.2", 25);
    String planId = createPlan("CAMPAIGN", campaignId);

    String result = aggregate(planId, batchId);
    Map<String, Object> parsed = read(result);
    assertThat(((Number) parsed.get("createdLineCount")).intValue()).as("只有 2 条真建议能进计划").isEqualTo(2);
    assertThat(((Number) parsed.get("skippedNotReplenishCount")).intValue())
        .as("答不出来 / 无需补货的建议要如实报出来")
        .isEqualTo(2);

    String lines =
        http.perform(
                get("/trade/purchase-plans/" + planId + "/lines")
                    .header("Authorization", "Bearer tenant-a")
                    .param("size", "50"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<Map<String, Object>> rows = rowsOf(lines);
    assertThat(rows).hasSize(2);

    Map<String, Object> first = rows.get(0);
    assertThat(first.get("skuCode")).isEqualTo("SKU-001");
    assertThat(((BigDecimal) first.get("planQuantity")).compareTo(new BigDecimal("360.000000")))
        .as("建议量 100 × 销量系数 3 × 备货系数 1.2 = 360")
        .isZero();
    assertThat(first.get("expectedArrivalDate"))
        .as("大促期望到货日 = 大促开始日 2026-11-20 − 提前 25 天 = 2026-10-26")
        .isEqualTo("2026-10-26");
    assertThat(String.valueOf(first.get("quantityBasis")))
        .as("算式原文要随结论一起返回")
        .contains("销量系数")
        .contains("备货系数")
        .contains("CAMPAIGN-BIG");

    Map<String, Object> second = rows.get(1);
    assertThat(second.get("skuCode")).isEqualTo("SKU-002");
    assertThat(((BigDecimal) second.get("planQuantity")).compareTo(new BigDecimal("216.000000")))
        .as("上游已调整过的建议用处置后的量 60 × 3 × 1.2 = 216")
        .isZero();
    assertThat(String.valueOf(second.get("quantityBasis"))).contains("上游处置后调整为 60");
  }

  @Test
  void 日常计划不放大且用建议给出的到货日() throws Exception {
    UUID batchId = seedBatch(a);
    seedSuggestion(
        a, batchId, 1, "SKU-001", "REPLENISH", "SUGGESTED", "100", null,
        LocalDate.of(2026, 11, 18));
    String planId = createPlan("ROUTINE", null);
    aggregate(planId, batchId);

    String lines =
        http.perform(
                get("/trade/purchase-plans/" + planId + "/lines")
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Map<String, Object> row = rowsOf(lines).get(0);
    assertThat(((BigDecimal) row.get("planQuantity")).compareTo(new BigDecimal("100.000000")))
        .as("日常计划不放大")
        .isZero();
    assertThat(row.get("expectedArrivalDate")).isEqualTo("2026-11-18");
    assertThat(String.valueOf(row.get("quantityBasis"))).contains("日常补货不放大");
  }

  @Test
  void 重新汇总不会覆盖已调整的行() throws Exception {
    UUID batchId = seedBatch(a);
    seedSuggestion(
        a, batchId, 1, "SKU-001", "REPLENISH", "SUGGESTED", "100", null,
        LocalDate.of(2026, 11, 10));
    String planId = createPlan("ROUTINE", null);
    aggregate(planId, batchId);

    String lines =
        http.perform(
                get("/trade/purchase-plans/" + planId + "/lines")
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String lineId = String.valueOf(rowsOf(lines).get(0).get("id"));

    // 运营把数量调成 250。
    http.perform(
            post("/trade/purchase-plans/" + planId + "/lines/" + lineId + "/adjust")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("planQuantity", new BigDecimal("250"), "reason", "按在途情况调大"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.planQuantity").value(250.0));

    // 再汇总一次 ⇒ 跳过，不覆盖。
    Map<String, Object> result = read(aggregate(planId, batchId));
    assertThat(((Number) result.get("createdLineCount")).intValue()).isZero();
    assertThat(((Number) result.get("skippedExistingCount")).intValue()).isEqualTo(1);

    String after =
        http.perform(
                get("/trade/purchase-plans/" + planId + "/lines")
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(((BigDecimal) rowsOf(after).get(0).get("planQuantity")).compareTo(new BigDecimal("250")))
        .as("已调整的数量不能被重新汇总抹回去")
        .isZero();
  }

  @Test
  void 汇总一个不存在的批次会被拒() throws Exception {
    String planId = createPlan("ROUTINE", null);
    http.perform(
            post("/trade/purchase-plans/" + planId + "/aggregate")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("sourceBatchId", UUID.randomUUID().toString()))))
        .andExpect(status().isNotFound());
  }

  // ================================================================ 计划行调整

  @Test
  void 计划行调整会留痕并写回值() throws Exception {
    UUID batchId = seedBatch(a);
    seedSuggestion(
        a, batchId, 1, "SKU-001", "REPLENISH", "SUGGESTED", "100", null,
        LocalDate.of(2026, 11, 10));
    String planId = createPlan("ROUTINE", null);
    aggregate(planId, batchId);
    String lines =
        http.perform(
                get("/trade/purchase-plans/" + planId + "/lines")
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String lineId = String.valueOf(rowsOf(lines).get(0).get("id"));

    http.perform(
            post("/trade/purchase-plans/" + planId + "/lines/" + lineId + "/adjust")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "planQuantity", new BigDecimal("180.5"),
                            "expectedArrivalDate", "2026-11-05",
                            "priority", "CRITICAL",
                            "reason", "大促提前备"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.planQuantity").value(180.5))
        .andExpect(jsonPath("$.expectedArrivalDate").value("2026-11-05"))
        .andExpect(jsonPath("$.priority").value("CRITICAL"))
        .andExpect(jsonPath("$.statusLabel").value("已调整"));

    // 留痕：逐字段一行，且写了理由。
    String detail =
        http.perform(
                get("/trade/purchase-plans/" + planId + "/lines/" + lineId)
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Map<String, Object> parsed = read(detail);
    List<Map<String, Object>> adjustments = (List<Map<String, Object>>) parsed.get("adjustments");
    assertThat(adjustments).as("三个字段各留一条痕").hasSize(3);
    assertThat(adjustments).allSatisfy(a -> assertThat(a.get("reason")).isEqualTo("大促提前备"));
    assertThat(adjustments)
        .extracting(a -> a.get("field"))
        .contains("plan_quantity", "expected_arrival_date", "priority");
  }

  @Test
  void 空调整会被拒() throws Exception {
    UUID batchId = seedBatch(a);
    seedSuggestion(
        a, batchId, 1, "SKU-001", "REPLENISH", "SUGGESTED", "100", null,
        LocalDate.of(2026, 11, 10));
    String planId = createPlan("ROUTINE", null);
    aggregate(planId, batchId);
    String lines =
        http.perform(
                get("/trade/purchase-plans/" + planId + "/lines")
                    .header("Authorization", "Bearer tenant-a"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String lineId = String.valueOf(rowsOf(lines).get(0).get("id"));
    http.perform(
            post("/trade/purchase-plans/" + planId + "/lines/" + lineId + "/adjust")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("reason", "随便写一句"))))
        .andExpect(status().isBadRequest());
  }

  // ================================================================ 锁定

  @Test
  void 锁定会真的向warehouse登记预占并写回预占号() throws Exception {
    UUID batchId = seedBatch(a);
    seedSuggestion(
        a, batchId, 1, "SKU-001", "REPLENISH", "SUGGESTED", "100", null,
        LocalDate.of(2026, 11, 10));
    String planId = createPlan("ROUTINE", null);
    aggregate(planId, batchId);
    String lines =
        http.perform(
                get("/trade/purchase-plans/" + planId + "/lines")
                    .header("Authorization", "Bearer tenant-a"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String lineId = String.valueOf(rowsOf(lines).get(0).get("id"));

    // 默认锁库策略要求计划**已确认**才能锁 ⇒ 草稿计划锁不了。
    http.perform(
            post("/trade/purchase-plans/" + planId + "/lines/" + lineId + "/lock")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isConflict());
    assertThat(reservationCalls.get()).as("策略不通过时根本不该去 warehouse").isZero();

    http.perform(post("/trade/purchase-plans/" + planId + "/confirm").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statusLabel").value("已确认"));

    http.perform(
            post("/trade/purchase-plans/" + planId + "/lines/" + lineId + "/lock")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lockStatus").value("CONFIRMED"))
        .andExpect(jsonPath("$.reservationNo").value("RSV-20261101-000001"))
        .andExpect(jsonPath("$.lockStatusLabel").value("已确认"));

    assertThat(reservationCalls.get()).as("真的去 warehouse 登记了一次预占").isEqualTo(1);
    assertThat(lastReservationBody.get("sourceSystem")).isEqualTo("TRADE");
    assertThat(lastReservationBody.get("sourceType")).isEqualTo("OTHER_HOLD");
    assertThat(String.valueOf(lastReservationBody.get("skuId"))).isEqualTo(skuFor(1).toString());
    assertThat(
            ((BigDecimal) lastReservationBody.get("requestedQuantity"))
                .compareTo(new BigDecimal("100")))
        .isZero();
    assertThat(lastReservationBody.get("heldAt")).as("heldAt 必须显式传（时钟差的事实）").isNotNull();
    assertThat(lastReservationBody.get("expiresAt")).as("持有天数 ⇒ 有过期时刻").isNotNull();

    // 行状态与留痕都要写回。
    String detail =
        http.perform(
                get("/trade/purchase-plans/" + planId + "/lines/" + lineId)
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Map<String, Object> parsed = read(detail);
    Map<String, Object> line = (Map<String, Object>) parsed.get("line");
    assertThat(line.get("lockStatus")).isEqualTo("CONFIRMED");
    assertThat(line.get("lockReservationNo")).isEqualTo("RSV-20261101-000001");
    assertThat(line.get("lockConfirmedAt")).as("确认时刻必须写回").isNotNull();
    List<Map<String, Object>> logs = (List<Map<String, Object>>) parsed.get("lockLogs");
    assertThat(logs).as("请求 + 确认两条留痕").hasSize(2);
    assertThat(logs).extracting(l -> l.get("action")).contains("REQUEST", "CONFIRM");

    // 计划状态由行状态派生 ⇒ 已锁定。
    http.perform(get("/trade/purchase-plans/" + planId).header("Authorization", "Bearer tenant-a"))
        .andExpect(jsonPath("$.plan.status").value("LOCKED"))
        .andExpect(jsonPath("$.plan.lockedLineCount").value(1));
  }

  @Test
  void 锁定失败会如实记成失败而不是假装成功() throws Exception {
    UUID batchId = seedBatch(a);
    seedSuggestion(
        a, batchId, 1, "SKU-001", "REPLENISH", "SUGGESTED", "100", null,
        LocalDate.of(2026, 11, 10));
    String planId = createPlan("ROUTINE", null);
    aggregate(planId, batchId);
    http.perform(post("/trade/purchase-plans/" + planId + "/confirm").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    String lines =
        http.perform(
                get("/trade/purchase-plans/" + planId + "/lines")
                    .header("Authorization", "Bearer tenant-a"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String lineId = String.valueOf(rowsOf(lines).get(0).get("id"));

    reservationForbidden.set(true);
    http.perform(
            post("/trade/purchase-plans/" + planId + "/lines/" + lineId + "/lock")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());

    String detail =
        http.perform(
                get("/trade/purchase-plans/" + planId + "/lines/" + lineId)
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Map<String, Object> parsed = read(detail);
    Map<String, Object> line = (Map<String, Object>) parsed.get("line");
    assertThat(line.get("lockStatus")).as("上游拒了就是失败").isEqualTo("FAILED");
    assertThat(line.get("lockStatusLabel")).isEqualTo("锁定失败");
    assertThat(line.get("lockReservationId")).as("没锁住就不能有预占号").isNull();
    assertThat(String.valueOf(line.get("lockFailureReason"))).contains("无权");
    List<Map<String, Object>> logs = (List<Map<String, Object>>) parsed.get("lockLogs");
    assertThat(logs).extracting(l -> l.get("action")).contains("REQUEST", "FAIL");

    // 计划状态也要如实是「锁定失败」。
    http.perform(get("/trade/purchase-plans/" + planId).header("Authorization", "Bearer tenant-a"))
        .andExpect(jsonPath("$.plan.status").value("LOCK_FAILED"));
  }

  @Test
  void 锁库策略不生效时不能锁() throws Exception {
    UUID batchId = seedBatch(a);
    seedSuggestion(
        a, batchId, 1, "SKU-001", "REPLENISH", "SUGGESTED", "100", null,
        LocalDate.of(2026, 11, 10));
    String planId = createPlan("ROUTINE", null);
    aggregate(planId, batchId);
    http.perform(post("/trade/purchase-plans/" + planId + "/confirm").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    String lines =
        http.perform(
                get("/trade/purchase-plans/" + planId + "/lines")
                    .header("Authorization", "Bearer tenant-a"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String lineId = String.valueOf(rowsOf(lines).get(0).get("id"));

    // 登记一条「日常不允许锁库」的策略（生效起点在今天之后 ⇒ 立即生效）。
    http.perform(
            post("/trade/purchase-plans/lock-policies")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "planType", "ROUTINE",
                            "lockEnabled", false,
                            "holdDays", 30,
                            "requireConfirmed", true,
                            "effectiveFrom", LocalDate.of(2000, 1, 1).toString()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lockEnabled").value(false));

    http.perform(
            post("/trade/purchase-plans/" + planId + "/lines/" + lineId + "/lock")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isConflict());
    assertThat(reservationCalls.get()).isZero();
  }

  // ================================================================ 导出

  @Test
  void 导出csv给采购员而且内容是真的() throws Exception {
    UUID batchId = seedBatch(a);
    seedSuggestion(
        a, batchId, 1, "SKU-001", "REPLENISH", "SUGGESTED", "100", null,
        LocalDate.of(2026, 11, 10));
    seedSuggestion(
        a, batchId, 2, "SKU-002", "REPLENISH", "SUGGESTED", "200", null,
        LocalDate.of(2026, 11, 11));
    String campaignId = createCampaign("CAMPAIGN-EXPORT", "2", "1", 15);
    String planId = createPlan("CAMPAIGN", campaignId);
    aggregate(planId, batchId);

    byte[] body =
        http.perform(
                get("/trade/purchase-plans/" + planId + "/export")
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    String csv = new String(body, java.nio.charset.StandardCharsets.UTF_8);
    assertThat(csv).isNotEmpty();
    assertThat(csv).contains("计划号").contains("SKU编码").contains("计划量").contains("期望到货日");
    assertThat(csv).contains("SKU-001").contains("SKU-002");
    assertThat(csv).contains("大促备货").contains("CAMPAIGN-EXPORT");
    assertThat(csv).contains("销量系数");
    // 表头 + 2 行数据。
    assertThat(csv.strip().split("\n")).hasSize(3);
  }

  // ================================================================ 汇总视图

  @Test
  void 汇总视图把日常与大促分开() throws Exception {
    UUID batchId = seedBatch(a);
    seedSuggestion(
        a, batchId, 1, "SKU-001", "REPLENISH", "SUGGESTED", "100", null,
        LocalDate.of(2026, 11, 10));
    String routinePlan = createPlan("ROUTINE", null);
    aggregate(routinePlan, batchId);

    String campaignId = createCampaign("CAMPAIGN-SUM", "3", "1", 25);
    String campaignPlan = createPlan("CAMPAIGN", campaignId);
    aggregate(campaignPlan, batchId);

    String summary =
        http.perform(
                get("/trade/purchase-plans/summary").header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Map<String, Object> parsed = read(summary);
    Map<String, Object> routine = (Map<String, Object>) parsed.get("routine");
    Map<String, Object> campaign = (Map<String, Object>) parsed.get("campaign");

    assertThat(routine.get("planTypeLabel")).isEqualTo("日常补货");
    assertThat(campaign.get("planTypeLabel")).isEqualTo("大促备货");
    assertThat(((Number) routine.get("planCount")).longValue()).isEqualTo(1L);
    assertThat(((Number) campaign.get("planCount")).longValue()).isEqualTo(1L);
    assertThat(((BigDecimal) routine.get("totalPlanQuantity")).compareTo(new BigDecimal("100")))
        .isZero();
    assertThat(((BigDecimal) campaign.get("totalPlanQuantity")).compareTo(new BigDecimal("300")))
        .as("大促段按系数放大")
        .isZero();
    assertThat(parsed).as("汇总里不得有一个把两者加在一起的总数量").doesNotContainKey("totalQuantity");
  }

  // ================================================================ 列表

  @Test
  void 计划列表真分页真筛选真排序() throws Exception {
    UUID batchId = seedBatch(a);
    seedSuggestion(
        a, batchId, 1, "SKU-001", "REPLENISH", "SUGGESTED", "100", null,
        LocalDate.of(2026, 11, 10));
    createPlan("ROUTINE", null);
    String campaignId = createCampaign("CAMPAIGN-LIST", "2", "1", 25);
    createPlan("CAMPAIGN", campaignId);

    String all =
        http.perform(
                get("/trade/purchase-plans").header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(rowsOf(all)).hasSize(2);

    // 真分页。
    String page0 =
        http.perform(
                get("/trade/purchase-plans")
                    .header("Authorization", "Bearer tenant-a")
                    .param("page", "0")
                    .param("size", "1"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(rowsOf(page0)).hasSize(1);
    assertThat(((Number) read(page0).get("total")).longValue()).isEqualTo(2L);

    // 真筛选：按类型。
    String campaignsOnly =
        http.perform(
                get("/trade/purchase-plans")
                    .header("Authorization", "Bearer tenant-a")
                    .param("planType", "CAMPAIGN"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(rowsOf(campaignsOnly)).hasSize(1);
    assertThat(rowsOf(campaignsOnly).get(0).get("planType")).isEqualTo("CAMPAIGN");

    // 真排序：按优先级（业务序，不是字典序）。
    http.perform(
            post("/trade/purchase-plans/lock-policies")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "planType", "ROUTINE",
                            "lockEnabled", true,
                            "holdDays", 30,
                            "requireConfirmed", true,
                            "effectiveFrom", LocalDate.of(2000, 1, 1).toString()))))
        .andExpect(status().isOk());

    String byPriority =
        http.perform(
                get("/trade/purchase-plans")
                    .header("Authorization", "Bearer tenant-a")
                    .param("sort", "priority"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<Map<String, Object>> sorted = rowsOf(byPriority);
    assertThat(sorted).hasSize(2);
    assertThat(sorted.get(0).get("priority")).isEqualTo("HIGH");
  }

  @Test
  void 能力声明如实报出上下游的可用性() throws Exception {
    String payload =
        http.perform(
                get("/trade/purchase-plans/capabilities").header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Map<String, Object> parsed = read(payload);
    assertThat(parsed.get("suggestionAvailable")).isEqualTo(true);
    assertThat(parsed.get("warehouseReferenceAvailable")).isEqualTo(true);
    assertThat(parsed.get("campaignCalendarAvailable")).isEqualTo(true);
    assertThat(parsed.get("purchaseRequestAvailable")).isEqualTo(false);
    assertThat(String.valueOf(parsed.get("lockPortAvailable")))
        .as("WAREHOUSE_URL 配了 ⇒ 锁库端口已接线")
        .isEqualTo("true");
    assertThat(String.valueOf(parsed.get("campaignCalendarNote"))).contains("自带");
  }
}
