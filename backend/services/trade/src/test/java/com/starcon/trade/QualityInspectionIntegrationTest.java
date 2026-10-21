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
import java.sql.SQLException;
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
 * 6.4.2.1 质检单（trade 侧）。
 *
 * <p><b>本测试是 V249 迁移那些 DB 级不变量的独立复验</b>：逐条在真实 PostgreSQL 16 上跑一遍，
 * 并且走 HTTP 端点而不是直连数据库，以便同时验证「服务层 + DB 层」两层都没有把闸门漏掉。
 *
 * <p><b>上游数据怎么来的（以及为什么这样）</b>：
 *
 * <ul>
 *   <li><b>收货事件（8.2.1.1）</b>走 warehouse 的 stub HTTP 服务（不是 new 一个假对象）：
 *       跨服务一律走端口，禁跨库 SQL / FK / 视图。stub 只按上游契约形状返回数。</li>
 *   <li><b>物理安全属性（3.7.2.1）</b>走 catalog 的 stub HTTP 服务，同理。</li>
 * </ul>
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。
 */
@SpringBootTest
@AutoConfigureMockMvc
class QualityInspectionIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity, warehouse, catalog;
  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          // 数量必须按 BigDecimal 读回来：用 Double 会把 100.000000 变成 100.0，
          // 断言时丢精度（而且看不出是精度问题还是口径问题）。
          .enable(
              com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 收货单（warehouse 侧）。 */
  static final UUID receiptId = UUID.randomUUID();
  static final String RECEIPT_CODE = "GR-2026-0001";
  static final UUID skuA = UUID.nameUUIDFromBytes("starcon-quality-sku-a".getBytes());
  static final String SKU_A = "SKU-A";
  static final UUID skuB = UUID.nameUUIDFromBytes("starcon-quality-sku-b".getBytes());
  static final String SKU_B = "SKU-B";
  static final UUID dangerousSku = UUID.nameUUIDFromBytes("starcon-quality-sku-danger".getBytes());
  static final String DANGEROUS_SKU = "SKU-DANGER";

  /** catalog 侧的安全属性档案（返回 null = 该 SKU 没有档案）。 */
  static final Map<String, Map<String, Object>> safetyProfiles = new ConcurrentHashMap<>();

  /** warehouse 收到的 Authorization 头（最后一次）—— 必须原样转发调用方的 bearer。 */
  static final List<String> warehouseBearers = new ArrayList<>();

  /** warehouse 被调用的次数。 */
  static final AtomicInteger warehouseCalls = new AtomicInteger();

  /** 让 warehouse 返回 500（模拟上游故障）时置 true。 */
  static final AtomicBoolean warehouseBroken = new AtomicBoolean(false);

  static final List<String> allPermissions =
      List.of(
          "trade.quality-inspection.read",
          "trade.quality-inspection.update",
          "trade.quality-inspection.concession");

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

  /**
   * 造一个键值对多于 10 个的 map。
   *
   * <p>{@code Map.of} 最多只收 10 对键值，收多了编译不过 —— 上游的收货单详情恰好
   * 有 11 个字段，所以这里自己拼一个 LinkedHashMap。
   */
  static Map<String, Object> map(Object... keyValues) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
      result.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
    }
    return result;
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，断言全部变成空断言。
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
      // 没有让步接收权限码的另一个身份（同一个租户，只少一个权限码）。
      actors.put(
          "Bearer no-concession",
          actor(
              user,
              a,
              List.of(
                  grant(
                      "ALL",
                      List.of(
                          "trade.quality-inspection.read", "trade.quality-inspection.update")))));
      identity.start();

      // ---- warehouse stub：收货单列表 + 详情 ----
      warehouse = HttpServer.create(new InetSocketAddress(0), 0);
      warehouse.createContext(
          "/warehouse/goods-receipts",
          e -> {
            warehouseBearers.add(e.getRequestHeaders().getFirst("Authorization"));
            warehouseCalls.incrementAndGet();
            if (tenantOf(e.getRequestHeaders().getFirst("Authorization")) == null) {
              write(warehouse, e, 401, Map.of("message", "未认证"));
              return;
            }
            if (warehouseBroken.get()) {
              write(warehouse, e, 500, Map.of("message", "仓库炸了"));
              return;
            }
            String path = e.getRequestURI().getPath();
            if (path.equals("/warehouse/goods-receipts")) {
              write(
                  warehouse,
                  e,
                  200,
                  Map.of(
                      "rows",
                      List.of(
                          Map.of(
                              "id", receiptId.toString(),
                              "receiptCode", RECEIPT_CODE,
                              "sourceType", "TRANSFER",
                              "sourceRefCode", "TO-2026-0001",
                              "warehouseCode", "WH-LA-01",
                              "status", "RECEIVED",
                              "expectedQuantity", 120,
                              "receivedQuantity", 120,
                              "rejectedQuantity", 0,
                              "outstandingQuantity", 0)),
                      "total",
                      1,
                      "page",
                      0,
                      "size",
                      20));
              return;
            }
            String id = path.substring("/warehouse/goods-receipts/".length());
            if (!id.equals(receiptId.toString())) {
              write(warehouse, e, 404, Map.of("message", "没有这张单"));
              return;
            }
            write(
                warehouse,
                e,
                200,
                map(
                    "id",
                    receiptId.toString(),
                    "receiptCode",
                    RECEIPT_CODE,
                    "sourceType",
                    "TRANSFER",
                    "sourceRefCode",
                    "TO-2026-0001",
                    "warehouseCode",
                    "WH-LA-01",
                    "status",
                    "RECEIVED",
                    "expectedQuantity",
                    120,
                    "receivedQuantity",
                    120,
                    "rejectedQuantity",
                    0,
                    "outstandingQuantity",
                    0,
                    "lines",
                    List.of(
                        Map.of(
                            "id",
                            UUID.nameUUIDFromBytes("line-a".getBytes()).toString(),
                            "skuId",
                            skuA.toString(),
                            "skuCode",
                            SKU_A,
                            "skuName",
                            "商品 A",
                            "qualityStatusCode",
                            "PENDING_INSPECTION",
                            "qualityStatusName",
                            "待检",
                            "expectedQuantity",
                            100,
                            "receivedQuantity",
                            100,
                            "rejectedQuantity",
                            0,
                            "outstandingQuantity",
                            0),
                        Map.of(
                            "id",
                            UUID.nameUUIDFromBytes("line-b".getBytes()).toString(),
                            "skuId",
                            skuB.toString(),
                            "skuCode",
                            SKU_B,
                            "skuName",
                            "商品 B",
                            "qualityStatusCode",
                            "PENDING_INSPECTION",
                            "qualityStatusName",
                            "待检",
                            "expectedQuantity",
                            20,
                            "receivedQuantity",
                            20,
                            "rejectedQuantity",
                            0,
                            "outstandingQuantity",
                            0),
                        Map.of(
                            "id",
                            UUID.nameUUIDFromBytes("line-danger".getBytes()).toString(),
                            "skuId",
                            dangerousSku.toString(),
                            "skuCode",
                            DANGEROUS_SKU,
                            "skuName",
                            "带电池的商品",
                            "qualityStatusCode",
                            "PENDING_INSPECTION",
                            "qualityStatusName",
                            "待检",
                            "expectedQuantity",
                            30,
                            "receivedQuantity",
                            30,
                            "rejectedQuantity",
                            0,
                            "outstandingQuantity",
                            0))));
          });
      warehouse.start();

      // ---- catalog stub：物理安全属性 ----
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/physical-safety",
          e -> {
            if (tenantOf(e.getRequestHeaders().getFirst("Authorization")) == null) {
              write(catalog, e, 401, Map.of("message", "未认证"));
              return;
            }
            String path = e.getRequestURI().getPath();
            String skuId = path.substring("/catalog/physical-safety/skus/".length());
            Map<String, Object> profile = safetyProfiles.get(skuId);
            if (profile == null) {
              write(catalog, e, 404, Map.of("message", "没有安全属性档案"));
              return;
            }
            write(catalog, e, 200, profile);
          });
      catalog.start();
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
    r.add("WAREHOUSE_URL", () -> "http://localhost:" + warehouse.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://localhost:" + catalog.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    warehouse.stop(0);
    catalog.stop(0);
    pg.stop();
  }

  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 数据准备

  @BeforeEach
  void seed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate quality_inspection_concession, quality_inspection_order_item,"
              + " quality_inspection_order, quality_inspection_template_item,"
              + " quality_inspection_template cascade");
    }
    safetyProfiles.clear();
    warehouseBroken.set(false);
    warehouseCalls.set(0);
    warehouseBearers.clear();
    safetyProfiles.put(
        skuA.toString(),
        Map.of(
            "skuId", skuA.toString(),
            "skuCode", SKU_A,
            "skuName", "商品 A",
            "safetyCollected", true,
            "containsBattery", false,
            "containsLiquid", false,
            "dangerousGoods", false,
            "isMagnetic", false,
            "activeFlags", List.of(),
            "requiredDeclarations", List.of()));
    safetyProfiles.put(
        dangerousSku.toString(),
        Map.of(
            "skuId", dangerousSku.toString(),
            "skuCode", DANGEROUS_SKU,
            "skuName", "带电池的商品",
            "safetyCollected", true,
            "containsBattery", true,
            "containsLiquid", false,
            "dangerousGoods", false,
            "isMagnetic", false,
            "activeFlags", List.of("CONTAINS_BATTERY"),
            "requiredDeclarations", List.of("UN38_3")));
  }

  // ================================================================ 工具

  @SuppressWarnings("unchecked")
  Map<String, Object> body(String payload) throws Exception {
    return json.readValue(payload, Map.class);
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> rowsOf(String payload) throws Exception {
    return (List<Map<String, Object>>) body(payload).getOrDefault("rows", List.of());
  }

  BigDecimal dec(Object value) {
    return new BigDecimal(String.valueOf(value));
  }

  /** 建一个模板（默认 5 个检验项 / 比例 0.1 / CEILING）。 */
  String createTemplate(
      String bearer,
      String code,
      String ratio,
      String minSample,
      String maxSample,
      String rounding,
      String criticalAction,
      String defectiveTarget,
      List<String> itemCodes,
      boolean criticalFirst)
      throws Exception {
    List<Map<String, Object>> items = new ArrayList<>();
    for (String itemCode : itemCodes) {
      items.add(
          Map.of(
              "itemCode",
              itemCode,
              "name",
              "检验项 " + itemCode,
              "critical",
              criticalFirst && "APPEARANCE".equals(itemCode),
              "acceptanceCriteria",
              "标准 " + itemCode,
              "sortOrder",
              items.size()));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", code);
    body.put("name", "模板 " + code);
    body.put("inspectionType", "INCOMING");
    body.put("samplingRatio", new BigDecimal(ratio));
    body.put("minSampleQuantity", new BigDecimal(minSample));
    body.put("maxSampleQuantity", new BigDecimal(maxSample));
    body.put("roundingMode", rounding);
    body.put("criticalFailureAction", criticalAction);
    body.put("defectiveTarget", defectiveTarget);
    body.put("items", items);
    String payload =
        http.perform(
                post("/trade/quality-inspections/templates")
                    .header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return String.valueOf(json.readTree(payload).get("id").asText());
  }

  /** 建质检单（默认 SKU-A / 收货单 GR-2026-0001 / 整批到货）。 */
  String createOrder(String bearer, String templateId, String skuCode, String skuId, String lotSize)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("templateId", templateId);
    body.put("skuCode", skuCode);
    if (skuId != null) {
      body.put("skuId", skuId);
    }
    body.put("receiptCode", RECEIPT_CODE);
    body.put("supplierName", "供应商甲");
    if (lotSize != null) {
      body.put("lotSize", new BigDecimal(lotSize));
    }
    String payload =
        http.perform(
                post("/trade/quality-inspections")
                    .header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return String.valueOf(json.readTree(payload).get("id").asText());
  }

  /** 模板里的某个检验项 id（按检验项词表查）。 */
  String templateItemId(String templateId, String itemCode) throws Exception {
    String payload =
        http.perform(
                get("/trade/quality-inspections/templates/" + templateId)
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    for (Map<String, Object> item :
        (List<Map<String, Object>>) body(payload).getOrDefault("items", List.of())) {
      if (itemCode.equals(item.get("itemCode"))) {
        return String.valueOf(item.get("id"));
      }
    }
    throw new AssertionError("模板里没有检验项 " + itemCode);
  }

  /** 录一条检验结论。 */
  void recordItem(
      String bearer,
      String orderId,
      String templateItemId,
      String result,
      String failedSample,
      String deducted)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("templateItemId", templateItemId);
    body.put("result", result);
    body.put("failedSampleQuantity", new BigDecimal(failedSample));
    body.put("deductedQuantity", new BigDecimal(deducted));
    body.put("defectDescription", "缺陷描述");
    http.perform(
            post("/trade/quality-inspections/" + orderId + "/items")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isOk());
  }

  void complete(String bearer, String orderId) throws Exception {
    http.perform(
            post("/trade/quality-inspections/" + orderId + "/complete")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());
  }

  /** 直接用**超级用户**连（绕过 RLS），只用来验证 DB 层的 CHECK / 触发器。 */
  void withSuperuser(SqlAction action) throws Exception {
    try (Connection c =
        DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      action.run(c);
    }
  }

  interface SqlAction {
    void run(Connection connection) throws Exception;
  }

  // ================================================================ 迁移落地

  @Test
  void 五张业务表都强制rls而且运行角色真能读写() throws Exception {
    for (String table :
        List.of(
            "quality_inspection_template",
            "quality_inspection_template_item",
            "quality_inspection_order",
            "quality_inspection_order_item",
            "quality_inspection_concession")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V249 必须真的建出 %s 这张表", table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as("%s 的 RLS 未启用", table).isTrue();
      assertThat(row.get(1, Boolean.class))
          .as("%s 只有 FORCE 才挡得住「运行角色被误授 owner」", table)
          .isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('trade_app',?,'SELECT')", table)
                  .get(0, Boolean.class))
          .as("trade_app 必须真的能读 %s", table)
          .isTrue();
    }
  }

  @Test
  void 让步接收只追加_改写与删除都被db层挡住() throws Exception {
    String templateId =
        createTemplate(
            "Bearer tenant-a", "TPL-APPEND", "0.1", "1", "0", "CEILING", "DEDUCT_ONLY",
            "PENDING_INSPECTION", List.of("APPEARANCE"), false);
    String orderId = createOrder("Bearer tenant-a", templateId, SKU_A, skuA.toString(), null);
    recordItem("Bearer tenant-a", orderId, templateItemId(templateId, "APPEARANCE"), "FAIL", "2", "10");
    String concessionId =
        String.valueOf(
            json.readTree(
                    http.perform(
                            post("/trade/quality-inspections/" + orderId + "/concessions")
                                .header("Authorization", "Bearer tenant-a")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                    json.writeValueAsString(
                                        Map.of(
                                            "quantity", 5,
                                            "reason", "外观轻微瑕疵，客户可接受",
                                            "permissionCode", "trade.quality-inspection.concession"))))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText());
    assertThat(concessionId).as("让步记录必须真的落库了").isNotEmpty();

    withSuperuser(
        c -> {
          try (var s = c.createStatement()) {
            assertThatThrownBy(
                    () ->
                        s.execute(
                            "update quality_inspection_concession set reason='偷偷改掉' where id='"
                                + concessionId
                                + "'"))
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(
                    () ->
                        s.execute(
                            "delete from quality_inspection_concession where id='"
                                + concessionId
                                + "'"))
                .isInstanceOf(SQLException.class);
          }
        });
  }

  // ================================================================ 抽样

  @Test
  void 应抽数量按模板的抽样规则在服务端算出来() throws Exception {
    String templateId =
        createTemplate(
            "Bearer tenant-a", "TPL-SAMPLE", "0.1", "3", "30", "CEILING", "DEDUCT_ONLY",
            "PENDING_INSPECTION", List.of("APPEARANCE"), false);
    String orderId = createOrder("Bearer tenant-a", templateId, SKU_A, skuA.toString(), null);
    String payload =
        http.perform(
                get("/trade/quality-inspections/" + orderId).header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Map<String, Object> order = (Map<String, Object>) body(payload).get("order");
    // 批量 100 × 比例 0.1 = 10，夹逼 [3, 30]，CEILING → 10
    assertThat(dec(order.get("sampleQuantity"))).isEqualByComparingTo("10");
    assertThat(dec(order.get("sampledQuantity"))).isEqualByComparingTo("10");
    assertThat(body(payload).get("samplingBasis").toString()).contains("应抽 10");
    assertThat(order.get("arrivedQuantity").toString()).isEqualTo("100.000000");
  }

  @Test
  void 客户端塞进来的抽样字段被直接拒掉() throws Exception {
    String templateId =
        createTemplate(
            "Bearer tenant-a", "TPL-SAMPLE2", "0.1", "3", "30", "CEILING", "DEDUCT_ONLY",
            "PENDING_INSPECTION", List.of("APPEARANCE"), false);
    // 请求体里塞一个 sampleQuantity —— 服务端不认这个字段，应抽数量仍按模板算。
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("templateId", templateId);
    body.put("skuCode", SKU_A);
    body.put("receiptCode", RECEIPT_CODE);
    body.put("sampleQuantity", 999);
    body.put("sampledQuantity", 999);
    // trade 的 ObjectMapper 开了 fail-on-unknown-properties ⇒ 客户端塞进来的
    // 抽样字段是**未知字段**，请求直接 400，一张质检单也不会被建出来。
    http.perform(
            post("/trade/quality-inspections")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
    assertThat(sql.fetchOne("select count(*) from quality_inspection_order").get(0, Long.class))
        .as("请求被拒 ⇒ 一张质检单都不该建出来")
        .isZero();
  }

  @Test
  void 舍入口径真的按模板走() throws Exception {
    String templateId =
        createTemplate(
            "Bearer tenant-a", "TPL-ROUND", "0.125", "0", "0", "FLOOR", "DEDUCT_ONLY",
            "PENDING_INSPECTION", List.of("APPEARANCE"), false);
    String orderId = createOrder("Bearer tenant-a", templateId, SKU_A, skuA.toString(), null);
    Map<String, Object> detail =
        body(
            http.perform(
                    get("/trade/quality-inspections/" + orderId)
                        .header("Authorization", "Bearer tenant-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    Map<String, Object> order = (Map<String, Object>) detail.get("order");
    // 批量 100 × 比例 0.125 = 12.5，FLOOR → 12
    assertThat(dec(order.get("sampleQuantity"))).isEqualByComparingTo("12");
    assertThat(detail.get("samplingBasis").toString()).contains("向下取整");
  }

  @Test
  void 最小抽样数把比例算出来的下界托住() throws Exception {
    String templateId =
        createTemplate(
            "Bearer tenant-a", "TPL-MIN", "0.01", "5", "30", "CEILING", "DEDUCT_ONLY",
            "PENDING_INSPECTION", List.of("APPEARANCE"), false);
    String orderId = createOrder("Bearer tenant-a", templateId, SKU_A, skuA.toString(), null);
    Map<String, Object> order =
        (Map<String, Object>)
            body(
                    http.perform(
                            get("/trade/quality-inspections/" + orderId)
                                .header("Authorization", "Bearer tenant-a"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("order");
    // 100 × 0.01 = 1，但最小抽样数是 5 ⇒ 应抽 5
    assertThat(dec(order.get("sampleQuantity"))).isEqualByComparingTo("5");
  }

  // ================================================================ 到货量来自上游

  @Test
  void 到货量来自上游收货单而不是客户端() throws Exception {
    String templateId =
        createTemplate(
            "Bearer tenant-a", "TPL-ARRIVE", "0.1", "1", "0", "CEILING", "DEDUCT_ONLY",
            "PENDING_INSPECTION", List.of("APPEARANCE"), false);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("templateId", templateId);
    body.put("skuCode", SKU_A);
    body.put("receiptCode", RECEIPT_CODE);
    // 请求体里**不带**任何到货量 —— 落库的量只能是上游收货单的实收量。
    String createdId =
        http.perform(
                post("/trade/quality-inspections")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    createdId = String.valueOf(json.readTree(createdId).get("id").asText());
    Map<String, Object> order =
        (Map<String, Object>)
            body(
                    http.perform(
                            get("/trade/quality-inspections/" + createdId)
                                .header("Authorization", "Bearer tenant-a"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("order");
    // 请求体里根本没有到货量 ⇒ 落库的 100 只能是上游收货单的实收量。
    assertThat(dec(order.get("arrivedQuantity"))).isEqualByComparingTo("100");
  }

  @Test
  void 批量超过到货量被拒() throws Exception {
    String templateId =
        createTemplate(
            "Bearer tenant-a", "TPL-LOT", "0.1", "1", "0", "CEILING", "DEDUCT_ONLY",
            "PENDING_INSPECTION", List.of("APPEARANCE"), false);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("templateId", templateId);
    body.put("skuCode", SKU_A);
    body.put("receiptCode", RECEIPT_CODE);
    body.put("lotSize", 500);
    http.perform(
            post("/trade/quality-inspections")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 分批质检_批量可以小于到货量且三量按批量算() throws Exception {
    String templateId =
        createTemplate(
            "Bearer tenant-a", "TPL-PART", "0.1", "1", "0", "CEILING", "DEDUCT_ONLY",
            "PENDING_INSPECTION", List.of("APPEARANCE"), false);
    String orderId = createOrder("Bearer tenant-a", templateId, SKU_A, skuA.toString(), "40");
    Map<String, Object> order =
        (Map<String, Object>)
            body(
                    http.perform(
                            get("/trade/quality-inspections/" + orderId)
                                .header("Authorization", "Bearer tenant-a"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("order");
    assertThat(dec(order.get("arrivedQuantity"))).isEqualByComparingTo("100");
    assertThat(dec(order.get("lotSize"))).isEqualByComparingTo("40");
    assertThat(dec(order.get("sampleQuantity"))).isEqualByComparingTo("4");
  }

  @Test
  void 上游不可达时报503而不是冒充没有收货() throws Exception {
    warehouseBroken.set(true);
    String templateId =
        createTemplate(
            "Bearer tenant-a", "TPL-DOWN", "0.1", "1", "0", "CEILING", "DEDUCT_ONLY",
            "PENDING_INSPECTION", List.of("APPEARANCE"), false);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("templateId", templateId);
    body.put("skuCode", SKU_A);
    body.put("receiptCode", RECEIPT_CODE);
    http.perform(
            post("/trade/quality-inspections")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isServiceUnavailable());
    // 真的去调了上游（不是本地瞎判）
    assertThat(warehouseCalls.get()).isPositive();
  }

  @Test
  void 转发给上游的是调用方自己的bearer() throws Exception {
    String templateId =
        createTemplate(
            "Bearer tenant-a", "TPL-BEARER", "0.1", "1", "0", "CEILING", "DEDUCT_ONLY",
            "PENDING_INSPECTION", List.of("APPEARANCE"), false);
    createOrder("Bearer tenant-a", templateId, SKU_A, skuA.toString(), null);
    assertThat(warehouseBearers).contains("Bearer tenant-a");
  }

  // ================================================================ 三量自洽

  @Test
  void 三量之和等于本次质检覆盖的批量() throws Exception {
    String templateId =
        createTemplate(
            "Bearer tenant-a", "TPL-BALANCE", "0.1", "1", "0", "CEILING", "DEDUCT_ONLY",
            "PENDING_INSPECTION", List.of("APPEARANCE", "PACKAGING"), false);
    String orderId = createOrder("Bearer tenant-a", templateId, SKU_A, skuA.toString(), null);
    recordItem(
        "Bearer tenant-a", orderId, templateItemId(templateId, "APPEARANCE"), "FAIL", "2", "12");
    recordItem(
        "Bearer tenant-a", orderId, templateItemId(templateId, "PACKAGING"), "PASS", "0", "0");
    complete("Bearer tenant-a", orderId);
    Map<String, Object> order =
        (Map<String, Object>)
            body(
                    http.perform(
                            get("/trade/quality-inspections/" + orderId)
                                .header("Authorization", "Bearer tenant-a"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("order");
    BigDecimal qualified = dec(order.get("qualifiedQuantity"));
    BigDecimal defective = dec(order.get("defectiveQuantity"));
    BigDecimal concession = dec(order.get("concessionQuantity"));
    assertThat(qualified.add(defective).add(concession)).isEqualByComparingTo("100");
    assertThat(defective).isEqualByComparingTo("12");
    assertThat(qualified).isEqualByComparingTo("88");
    assertThat(order.get("result")).isEqualTo("PARTIAL");
    // 落库的值也要自洽（不只是接口返回自洽）。
    // 直查必须在**该租户的 RLS 事务里**做 —— 事务外读是零行，那本身也是隔离的证据。
    var row =
        access.run(
            actors.get("Bearer tenant-a"),
            "trade.quality-inspection.read",
            () ->
                sql.fetchOne(
                    "select qualified_quantity + defective_quantity + concession_quantity as s,"
                        + " lot_size from quality_inspection_order where id = ?::uuid",
                    UUID.fromString(orderId)));
    assertThat(row).isNotNull();
    assertThat(row.get("s", BigDecimal.class))
        .isEqualByComparingTo(row.get("lot_size", BigDecimal.class));
  }

  @Test
  void 让步接收量从扣量合计里面出_不合格量等于扣量减去让步() throws Exception {
    String templateId =
        createTemplate(
            "Bearer tenant-a", "TPL-CONC", "0.1", "1", "0", "CEILING", "DEDUCT_ONLY",
            "PENDING_INSPECTION", List.of("APPEARANCE"), false);
    String orderId = createOrder("Bearer tenant-a", templateId, SKU_A, skuA.toString(), null);
    recordItem("Bearer tenant-a", orderId, templateItemId(templateId, "APPEARANCE"), "FAIL", "2", "20");
    http.perform(
            post("/trade/quality-inspections/" + orderId + "/concessions")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "quantity", 6,
                            "reason", "轻微划痕，客户接受",
                            "permissionCode", "trade.quality-inspection.concession"))))
        .andExpect(status().isOk());
    complete("Bearer tenant-a", orderId);
    Map<String, Object> order =
        (Map<String, Object>)
            body(
                    http.perform(
                            get("/trade/quality-inspections/" + orderId)
                                .header("Authorization", "Bearer tenant-a"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("order");
    assertThat(dec(order.get("grossDeductedQuantity"))).isEqualByComparingTo("20");
    assertThat(dec(order.get("concessionQuantity"))).isEqualByComparingTo("6");
    assertThat(dec(order.get("defectiveQuantity"))).isEqualByComparingTo("14");
    assertThat(dec(order.get("qualifiedQuantity"))).isEqualByComparingTo("80");
    assertThat(dec(order.get("sellableQuantity"))).isEqualByComparingTo("86");
    assertThat(dec(order.get("nonSellableQuantity"))).isEqualByComparingTo("14");
  }

  @Test
  void 不合格的货不能进可售_分流意图指向待检且状态如实报未接线() throws Exception {
    String templateId =
        createTemplate(
            "Bearer tenant-a", "TPL-DISP", "0.1", "1", "0", "CEILING", "DEDUCT_ONLY",
            "PENDING_INSPECTION", List.of("APPEARANCE"), false);
    String orderId = createOrder("Bearer tenant-a", templateId, SKU_A, skuA.toString(), null);
    recordItem("Bearer tenant-a", orderId, templateItemId(templateId, "APPEARANCE"), "FAIL", "2", "10");
    complete("Bearer tenant-a", orderId);
    Map<String, Object> detail =
        body(
            http.perform(
                    get("/trade/quality-inspections/" + orderId)
                        .header("Authorization", "Bearer tenant-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    Map<String, Object> order = (Map<String, Object>) detail.get("order");
    assertThat(order.get("disposition")).isEqualTo("PENDING_INSPECTION");
    // 🔴 绝不出现 DISPATCHED 却其实没分流。
    assertThat(order.get("dispositionStatus")).isNotEqualTo("DISPATCHED");
    assertThat(order.get("dispositionStatus")).isEqualTo("UNAVAILABLE");
    assertThat(detail.get("dispositionNote").toString()).contains("没有真的分流");
  }

  @Test
  void 全部合格时去向是可售() throws Exception {
    String templateId =
        createTemplate(
            "Bearer tenant-a", "TPL-OK", "0.1", "1", "0", "CEILING", "DEDUCT_ONLY",
            "PENDING_INSPECTION", List.of("APPEARANCE"), false);
    String orderId = createOrder("Bearer tenant-a", templateId, SKU_A, skuA.toString(), null);
    recordItem("Bearer tenant-a", orderId, templateItemId(templateId, "APPEARANCE"), "PASS", "0", "0");
    complete("Bearer tenant-a", orderId);
    Map<String, Object> order =
        (Map<String, Object>)
            body(
                    http.perform(
                            get("/trade/quality-inspections/" + orderId)
                                .header("Authorization", "Bearer tenant-a"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("order");
    assertThat(order.get("result")).isEqualTo("PASSED");
    assertThat(order.get("disposition")).isEqualTo("SELLABLE");
    assertThat(dec(order.get("qualifiedQuantity"))).isEqualByComparingTo("100");
  }

  // ================================================================ 关键项

  @Test
  void 关键检验项不合格且模板是整批拒收时整批判不合格() throws Exception {
    String templateId =
        createTemplate(
            "Bearer tenant-a", "TPL-CRIT", "0.1", "1", "0", "CEILING", "REJECT_LOT",
            "DEFECTIVE", List.of("APPEARANCE"), true);
    String orderId = createOrder("Bearer tenant-a", templateId, SKU_A, skuA.toString(), null);
    recordItem("Bearer tenant-a", orderId, templateItemId(templateId, "APPEARANCE"), "FAIL", "1", "3");
    complete("Bearer tenant-a", orderId);
    Map<String, Object> order =
        (Map<String, Object>)
            body(
                    http.perform(
                            get("/trade/quality-inspections/" + orderId)
                                .header("Authorization", "Bearer tenant-a"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("order");
    assertThat(dec(order.get("defectiveQuantity"))).isEqualByComparingTo("100");
    assertThat(dec(order.get("qualifiedQuantity"))).isEqualByComparingTo("0");
    assertThat(order.get("result")).isEqualTo("FAILED");
    assertThat(order.get("disposition")).isEqualTo("DEFECTIVE");
  }

  @Test
  void 关键检验项不合格但模板是仅扣量时只扣它自己那部分() throws Exception {
    String templateId =
        createTemplate(
            "Bearer tenant-a", "TPL-CRIT2", "0.1", "1", "0", "CEILING", "DEDUCT_ONLY",
            "PENDING_INSPECTION", List.of("APPEARANCE"), true);
    String orderId = createOrder("Bearer tenant-a", templateId, SKU_A, skuA.toString(), null);
    recordItem("Bearer tenant-a", orderId, templateItemId(templateId, "APPEARANCE"), "FAIL", "1", "3");
    complete("Bearer tenant-a", orderId);
    Map<String, Object> order =
        (Map<String, Object>)
            body(
                    http.perform(
                            get("/trade/quality-inspections/" + orderId)
                                .header("Authorization", "Bearer tenant-a"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("order");
    assertThat(dec(order.get("defectiveQuantity"))).isEqualByComparingTo("3");
    assertThat(dec(order.get("qualifiedQuantity"))).isEqualByComparingTo("97");
  }

  // ================================================================ 让步接收的权限与原因

  @Test
  void 让步接收缺原因被拒() throws Exception {
    String templateId =
        createTemplate(
            "Bearer tenant-a", "TPL-NOREASON", "0.1", "1", "0", "CEILING", "DEDUCT_ONLY",
            "PENDING_INSPECTION", List.of("APPEARANCE"), false);
    String orderId = createOrder("Bearer tenant-a", templateId, SKU_A, skuA.toString(), null);
    recordItem("Bearer tenant-a", orderId, templateItemId(templateId, "APPEARANCE"), "FAIL", "2", "10");
    http.perform(
            post("/trade/quality-inspections/" + orderId + "/concessions")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "quantity", 5,
                            "permissionCode", "trade.quality-inspection.concession"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 让步接收缺权限码被拒() throws Exception {
    String templateId =
        createTemplate(
            "Bearer tenant-a", "TPL-NOPERM", "0.1", "1", "0", "CEILING", "DEDUCT_ONLY",
            "PENDING_INSPECTION", List.of("APPEARANCE"), false);
    String orderId = createOrder("Bearer tenant-a", templateId, SKU_A, skuA.toString(), null);
    recordItem("Bearer tenant-a", orderId, templateItemId(templateId, "APPEARANCE"), "FAIL", "2", "10");
    http.perform(
            post("/trade/quality-inspections/" + orderId + "/concessions")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("quantity", 5, "reason", "客户接受"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 让步接收的权限码必须真的持有() throws Exception {
    String templateId =
        createTemplate(
            "Bearer tenant-a", "TPL-HOLDPERM", "0.1", "1", "0", "CEILING", "DEDUCT_ONLY",
            "PENDING_INSPECTION", List.of("APPEARANCE"), false);
    String orderId = createOrder("Bearer tenant-a", templateId, SKU_A, skuA.toString(), null);
    recordItem("Bearer tenant-a", orderId, templateItemId(templateId, "APPEARANCE"), "FAIL", "2", "10");
    // 这个身份**没有** trade.quality-inspection.concession 权限码 ⇒ 403。
    http.perform(
            post("/trade/quality-inspections/" + orderId + "/concessions")
                .header("Authorization", "Bearer no-concession")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "quantity", 5,
                            "reason", "客户接受",
                            "permissionCode", "trade.quality-inspection.concession"))))
        .andExpect(status().isForbidden());
    // 让步记录一条都没落库。
    assertThat(
            sql.fetchOne(
                    "select count(*) from quality_inspection_concession where order_id=?::uuid",
                    UUID.fromString(orderId))
                .get(0, Long.class))
        .isZero();
  }

  @Test
  void 让步接收量不得超过扣量合计() throws Exception {
    String templateId =
        createTemplate(
            "Bearer tenant-a", "TPL-TOOMUCH", "0.1", "1", "0", "CEILING", "DEDUCT_ONLY",
            "PENDING_INSPECTION", List.of("APPEARANCE"), false);
    String orderId = createOrder("Bearer tenant-a", templateId, SKU_A, skuA.toString(), null);
    recordItem("Bearer tenant-a", orderId, templateItemId(templateId, "APPEARANCE"), "FAIL", "2", "10");
    http.perform(
            post("/trade/quality-inspections/" + orderId + "/concessions")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "quantity", 50,
                            "reason", "想放行超过扣量的部分",
                            "permissionCode", "trade.quality-inspection.concession"))))
        .andExpect(status().isConflict());
  }

  // ================================================================ 完结前的关口

  @Test
  void 缺检验项结论不能完结() throws Exception {
    String templateId =
        createTemplate(
            "Bearer tenant-a", "TPL-MISSING", "0.1", "1", "0", "CEILING", "DEDUCT_ONLY",
            "PENDING_INSPECTION", List.of("APPEARANCE", "PACKAGING"), false);
    String orderId = createOrder("Bearer tenant-a", templateId, SKU_A, skuA.toString(), null);
    recordItem("Bearer tenant-a", orderId, templateItemId(templateId, "APPEARANCE"), "PASS", "0", "0");
    http.perform(
            post("/trade/quality-inspections/" + orderId + "/complete")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isConflict());
  }

  @Test
  void 危险品缺认证标识检验项不能完结() throws Exception {
    String templateId =
        createTemplate(
            "Bearer tenant-a", "TPL-NOCERT", "0.1", "1", "0", "CEILING", "DEDUCT_ONLY",
            "PENDING_INSPECTION", List.of("APPEARANCE"), false);
    String orderId =
        createOrder("Bearer tenant-a", templateId, DANGEROUS_SKU, dangerousSku.toString(), null);
    recordItem("Bearer tenant-a", orderId, templateItemId(templateId, "APPEARANCE"), "PASS", "0", "0");
    http.perform(
            post("/trade/quality-inspections/" + orderId + "/complete")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isConflict());
  }

  @Test
  void 危险品配了认证标识检验项就能完结() throws Exception {
    String templateId =
        createTemplate(
            "Bearer tenant-a", "TPL-CERT", "0.1", "1", "0", "CEILING", "DEDUCT_ONLY",
            "PENDING_INSPECTION", List.of("APPEARANCE", "CERTIFICATION"), false);
    String orderId =
        createOrder("Bearer tenant-a", templateId, DANGEROUS_SKU, dangerousSku.toString(), null);
    recordItem("Bearer tenant-a", orderId, templateItemId(templateId, "APPEARANCE"), "PASS", "0", "0");
    recordItem(
        "Bearer tenant-a", orderId, templateItemId(templateId, "CERTIFICATION"), "PASS", "0", "0");
    complete("Bearer tenant-a", orderId);
  }

  @Test
  void 已完成的质检单不能再录结论也不能重复完结() throws Exception {
    String templateId =
        createTemplate(
            "Bearer tenant-a", "TPL-CLOSED", "0.1", "1", "0", "CEILING", "DEDUCT_ONLY",
            "PENDING_INSPECTION", List.of("APPEARANCE"), false);
    String orderId = createOrder("Bearer tenant-a", templateId, SKU_A, skuA.toString(), null);
    recordItem("Bearer tenant-a", orderId, templateItemId(templateId, "APPEARANCE"), "PASS", "0", "0");
    complete("Bearer tenant-a", orderId);
    http.perform(
            post("/trade/quality-inspections/" + orderId + "/items")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "templateItemId", templateItemId(templateId, "APPEARANCE"),
                            "result", "FAIL",
                            "deductedQuantity", 5))))
        .andExpect(status().isConflict());
    http.perform(
            post("/trade/quality-inspections/" + orderId + "/complete")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isConflict());
  }

  @Test
  void 检验项必须属于本单的模板() throws Exception {
    String templateA =
        createTemplate(
            "Bearer tenant-a", "TPL-OWN-A", "0.1", "1", "0", "CEILING", "DEDUCT_ONLY",
            "PENDING_INSPECTION", List.of("APPEARANCE"), false);
    String templateB =
        createTemplate(
            "Bearer tenant-a", "TPL-OWN-B", "0.1", "1", "0", "CEILING", "DEDUCT_ONLY",
            "PENDING_INSPECTION", List.of("PACKAGING"), false);
    String orderId = createOrder("Bearer tenant-a", templateA, SKU_A, skuA.toString(), null);
    // 把模板 B 的检验项挂到模板 A 的质检单上 ⇒ 被拒。
    http.perform(
            post("/trade/quality-inspections/" + orderId + "/items")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "templateItemId", templateItemId(templateB, "PACKAGING"),
                            "result", "PASS"))))
        .andExpect(status().isNotFound());
  }

  // ================================================================ DB 层的自洽闸门

  @Test
  void db层拒绝三量对不上的完成态() throws Exception {
    String templateId =
        createTemplate(
            "Bearer tenant-a", "TPL-CHECK", "0.1", "1", "0", "CEILING", "DEDUCT_ONLY",
            "PENDING_INSPECTION", List.of("APPEARANCE"), false);
    String orderId = createOrder("Bearer tenant-a", templateId, SKU_A, skuA.toString(), null);
    withSuperuser(
        c -> {
          try (var s = c.createStatement()) {
            // 直接把单子改成「完成」但三量凑不齐 ⇒ CHECK 拒绝。
            assertThatThrownBy(
                    () ->
                        s.execute(
                            "update quality_inspection_order set status='COMPLETED',"
                                + " qualified_quantity=10, defective_quantity=10,"
                                + " concession_quantity=10, gross_deducted_quantity=20"
                                + " where id='"
                                + orderId
                                + "'"))
                .isInstanceOf(SQLException.class);
          }
        });
  }

  @Test
  void db层拒绝让步接收量超过扣量合计() throws Exception {
    String templateId =
        createTemplate(
            "Bearer tenant-a", "TPL-CHECK2", "0.1", "1", "0", "CEILING", "DEDUCT_ONLY",
            "PENDING_INSPECTION", List.of("APPEARANCE"), false);
    String orderId = createOrder("Bearer tenant-a", templateId, SKU_A, skuA.toString(), null);
    withSuperuser(
        c -> {
          try (var s = c.createStatement()) {
            assertThatThrownBy(
                    () ->
                        s.execute(
                            "update quality_inspection_order set status='COMPLETED',"
                                + " qualified_quantity=90, defective_quantity=0,"
                                + " concession_quantity=20, gross_deducted_quantity=10"
                                + " where id='"
                                + orderId
                                + "'"))
                .isInstanceOf(SQLException.class);
          }
        });
  }

  // ================================================================ 能力声明 / 列表 / 导出

  @Test
  void 能力声明把没接线的下游如实标成不可用() throws Exception {
    String payload =
        http.perform(
                get("/trade/quality-inspections/capabilities")
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Map<String, Object> capabilities = body(payload);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> downstream =
        (List<Map<String, Object>>) capabilities.get("downstream");
    Map<String, Object> disposition =
        downstream.stream()
            .filter(d -> "INVENTORY_DISPOSITION".equals(d.get("code")))
            .findFirst()
            .orElseThrow();
    assertThat(disposition.get("available")).isEqualTo(false);
    assertThat(String.valueOf(disposition.get("ownerModule"))).as("没接通必须说清归谁").isNotBlank();
    assertThat(String.valueOf(disposition.get("impact"))).as("没接通必须说清少做了什么").isNotBlank();
    assertThat(disposition.get("reason").toString()).contains("授权写端点");
  }

  @Test
  void 质检单列表真分页且筛选真的生效() throws Exception {
    String templateId =
        createTemplate(
            "Bearer tenant-a", "TPL-PAGE", "0.1", "1", "0", "CEILING", "DEDUCT_ONLY",
            "PENDING_INSPECTION", List.of("APPEARANCE"), false);
    for (int i = 0; i < 3; i++) {
      createOrder("Bearer tenant-a", templateId, SKU_A, skuA.toString(), null);
    }
    String firstPage =
        http.perform(
                get("/trade/quality-inspections")
                    .header("Authorization", "Bearer tenant-a")
                    .param("page", "0")
                    .param("size", "2"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(rowsOf(firstPage)).hasSize(2);
    assertThat(body(firstPage).get("total")).isEqualTo(3);
    String secondPage =
        http.perform(
                get("/trade/quality-inspections")
                    .header("Authorization", "Bearer tenant-a")
                    .param("page", "1")
                    .param("size", "2"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(rowsOf(secondPage)).hasSize(1);
    // 两页合起来恰好 3 条，且互不重叠。
    List<Object> ids = new ArrayList<>();
    rowsOf(firstPage).forEach(r -> ids.add(r.get("id")));
    rowsOf(secondPage).forEach(r -> ids.add(r.get("id")));
    assertThat(ids).hasSize(3).doesNotHaveDuplicates();

    // 筛选：按 SKU 筛。
    String filtered =
        http.perform(
                get("/trade/quality-inspections")
                    .header("Authorization", "Bearer tenant-a")
                    .param("skuCode", SKU_B))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(rowsOf(filtered)).isEmpty();
  }

  @Test
  void 导出csv含三量与逐项结论与让步记录() throws Exception {
    String templateId =
        createTemplate(
            "Bearer tenant-a", "TPL-CSV", "0.1", "1", "0", "CEILING", "DEDUCT_ONLY",
            "PENDING_INSPECTION", List.of("APPEARANCE"), false);
    String orderId = createOrder("Bearer tenant-a", templateId, SKU_A, skuA.toString(), null);
    recordItem("Bearer tenant-a", orderId, templateItemId(templateId, "APPEARANCE"), "FAIL", "2", "10");
    http.perform(
            post("/trade/quality-inspections/" + orderId + "/concessions")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "quantity", 3,
                            "reason", "客户接受轻微划痕",
                            "permissionCode", "trade.quality-inspection.concession"))))
        .andExpect(status().isOk());
    complete("Bearer tenant-a", orderId);
    String csv =
        http.perform(
                get("/trade/quality-inspections/" + orderId + "/export")
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(csv).contains("合格量,不合格量,让步接收量");
    assertThat(csv).contains("外观");
    assertThat(csv).contains("客户接受轻微划痕");
    assertThat(csv).contains("trade.quality-inspection.concession");
  }

  // ================================================================ HTTP 边界

  @Test
  void 没有会话是401() throws Exception {
    http.perform(get("/trade/quality-inspections")).andExpect(status().isUnauthorized());
    http.perform(get("/trade/quality-inspections/capabilities"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void 未知id是404() throws Exception {
    http.perform(
            get("/trade/quality-inspections/" + UUID.randomUUID())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
  }

  @Test
  void 收货单不存在是404而不是编一个到货量() throws Exception {
    String templateId =
        createTemplate(
            "Bearer tenant-a", "TPL-NORECEIPT", "0.1", "1", "0", "CEILING", "DEDUCT_ONLY",
            "PENDING_INSPECTION", List.of("APPEARANCE"), false);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("templateId", templateId);
    body.put("skuCode", SKU_A);
    body.put("receiptCode", "GR-NOT-EXIST");
    http.perform(
            post("/trade/quality-inspections")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isNotFound());
  }
}
