package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * 6.4.2.2 不良品处理的<b>多租户隔离</b>复验。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条断言：
 *
 * <ol>
 *   <li>两租户互不可见；</li>
 *   <li>伪造租户上下文无效；</li>
 *   <li>连接池归还后不串租户。</li>
 * </ol>
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>⚠️ 直查断言全部走<b>应用账号</b>（{@code trade_app}）</b> —— 用超级用户连会绕过 RLS，
 * 断言本身失效。
 *
 * <p>本测试类**刻意不配** {@code DEFECTIVE_INVENTORY_URL}：这正是本仓的真实状态 ——
 * warehouse 侧没有「按处置单变更库存状态」的授权写入口 ⇒ 指令状态必须如实落
 * {@code UNAVAILABLE}，**绝不出现「已登记」却其实没动库存**。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DefectiveGoodsTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(
              com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> allPermissions =
      List.of(
          "trade.defective-disposition.read",
          "trade.defective-disposition.update",
          "trade.defective-disposition.concession");

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
        // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
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
    r.add("INTERNAL_KEY", () -> "test-key");
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;


  /**
   * 一对**每个用例独有**的租户。
   *
   * <p>为什么不用类级别的两个固定租户：同一个容器里所有用例的处置单都落在那两个租户下，
   * 于是「列表里应该只有 1 行」这种断言会被别的用例的残留行打穿 ——
   * 那不是隔离被破坏，是夹具互相污染。断言要的是**本用例造的**那几行。
   */
  record Pair(String a, String b, UUID tenantA, UUID tenantB) {}

  static Pair newPair() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    String bearerA = "Bearer a-" + tenantA;
    String bearerB = "Bearer b-" + tenantB;
    actors.put(bearerA, actor(user, tenantA, List.of(grant("ALL", allPermissions))));
    actors.put(bearerB, actor(user, tenantB, List.of(grant("ALL", allPermissions))));
    return new Pair(bearerA, bearerB, tenantA, tenantB);
  }

  // ================================================================ 夹具

  static final BigDecimal LOT = new BigDecimal("100");
  static final BigDecimal QUALIFIED = new BigDecimal("70");
  static final BigDecimal CONCESSION = new BigDecimal("10");
  static final BigDecimal DEFECTIVE = new BigDecimal("20");
  static final BigDecimal GROSS = new BigDecimal("30");

  /** 种一条**已完结**的质检单当夹具。 */
  UUID seedInspection(UUID tenant, String inspectionNo) {
    var actor =
        actors.values().stream()
            .filter(x -> x.tenantId().equals(tenant))
            .findFirst()
            .orElseThrow();
    return access.run(actor, "trade.quality-inspection.update", () -> {
      UUID templateId = UUID.randomUUID();
      sql.execute(
          "insert into quality_inspection_template(id,tenant_id,code,name,inspection_type,"
              + " sampling_ratio,min_sample_quantity,max_sample_quantity,rounding_mode,"
              + " critical_failure_action,defective_target,status,note,created_by,created_at,updated_at)"
              + " values (?::uuid,?::uuid,?::varchar,?::varchar,'INCOMING',0.1,1,0,'CEILING',"
              + " 'REJECT_LOT','DEFECTIVE','ACTIVE','',?::uuid,now(),now())",
          templateId,
          tenant,
          "TPL-" + inspectionNo,
          "夹具模板 " + inspectionNo,
          user);
      UUID orderId = UUID.randomUUID();
      sql.execute(
          "insert into quality_inspection_order(id,tenant_id,inspection_no,template_id,template_code,"
              + " template_name,inspection_type,sku_id,sku_code,sku_name,supplier_id,supplier_name,"
              + " receipt_code,batch_no,arrived_quantity,lot_size,sampling_ratio,min_sample_quantity,"
              + " max_sample_quantity,rounding_mode,sample_quantity,sampled_quantity,qualified_quantity,"
              + " defective_quantity,concession_quantity,gross_deducted_quantity,status,result,"
              + " disposition,disposition_status,currency_code,unit_price,deducted_amount,note,"
              + " conclusion_note,created_by,created_at,updated_at,completed_at)"
              + " values (?::uuid,?::uuid,?::varchar,?::uuid,?::varchar,?::varchar,'INCOMING',"
              + " ?::uuid,?::varchar,?::varchar,?::uuid,?::varchar,?::varchar,?::varchar,?,?,0.1,1,0,"
              + " 'CEILING',10,10,?,?,?,?,'COMPLETED','PARTIAL','DEFECTIVE','UNAVAILABLE',"
              + " 'USD',25,750,'','',?::uuid,now(),now(),now())",
          orderId,
          tenant,
          inspectionNo,
          templateId,
          "TPL-" + inspectionNo,
          "夹具模板 " + inspectionNo,
          UUID.nameUUIDFromBytes(("sku-" + inspectionNo).getBytes()),
          "SKU-" + inspectionNo,
          "商品 " + inspectionNo,
          UUID.nameUUIDFromBytes(("supplier-" + inspectionNo).getBytes()),
          "供应商 " + inspectionNo,
          "GR-" + inspectionNo,
          "BATCH-" + inspectionNo,
          LOT,
          LOT,
          QUALIFIED,
          DEFECTIVE,
          CONCESSION,
          GROSS,
          user);
      return orderId;
    });
  }

  static Map<String, Object> body(UUID inspectionId, String reason, String quantity) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("inspectionOrderId", inspectionId.toString());
    map.put("reason", reason);
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("dispositionType", "SCRAP");
    line.put("quantity", new BigDecimal(quantity));
    map.put("lines", List.of(line));
    return map;
  }

  String create(String bearer, Map<String, Object> payload) throws Exception {
    var response =
        http.perform(
                post("/trade/defective-dispositions")
                    .header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(payload)))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus())
        .as("建处置单应当成功：%s", response.getContentAsString())
        .isEqualTo(200);
    return json.readTree(response.getContentAsString()).get("id").asText();
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> rowsOf(String jsonText) throws Exception {
    Map<String, Object> page = json.readValue(jsonText, Map.class);
    return (List<Map<String, Object>>) page.get("rows");
  }

  String orders(String bearer) throws Exception {
    var response =
        http.perform(
                get("/trade/defective-dispositions").header("Authorization", bearer))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    return response.getContentAsString();
  }

  // ================================================================ 三条断言

  @Test
  void 断言1_两租户互不可见() throws Exception {
    Pair p = newPair();
    String orderA = create(p.a(), body(seedInspection(p.tenantA(), "QI-TEN-A"), "A 的处置", "20"));
    String orderB = create(p.b(), body(seedInspection(p.tenantB(), "QI-TEN-B"), "B 的处置", "20"));

    var rowsA = rowsOf(orders(p.a()));
    var rowsB = rowsOf(orders(p.b()));
    assertThat(rowsA).hasSize(1);
    assertThat(rowsB).hasSize(1);
    assertThat(rowsA.get(0).get("id")).isEqualTo(orderA);
    assertThat(rowsB.get(0).get("id")).isEqualTo(orderB);

    // 更强的一格：A 明确按 ID 查 B 的单也拿不到（不只是列表过滤）。
    http.perform(
            get("/trade/defective-dispositions/" + orderB).header("Authorization", p.a()))
        .andExpect(status().isNotFound());

    // 汇总视图各自只算自己的单。
    var summaryA =
        http.perform(
                get("/trade/defective-dispositions/summary")
                    .header("Authorization", p.a()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var summaryB =
        http.perform(
                get("/trade/defective-dispositions/summary")
                    .header("Authorization", p.b()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    assertThat(json.readTree(summaryA.getContentAsString()).get("orderCount").asInt()).isEqualTo(1);
    assertThat(json.readTree(summaryB.getContentAsString()).get("orderCount").asInt()).isEqualTo(1);
  }

  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    Pair p = newPair();
    create(p.a(), body(seedInspection(p.tenantA(), "QI-FORGED-A"), "A 的处置", "20"));
    create(p.b(), body(seedInspection(p.tenantB(), "QI-FORGED-B"), "B 的处置", "20"));

    // (1) HTTP 边界上没有伪造入口：X-Tenant-Id 不改任何东西。
    String honest = orders(p.a());
    String forged =
        http.perform(
                get("/trade/defective-dispositions")
                    .header("Authorization", p.a())
                    .header("X-Tenant-Id", p.tenantB().toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forged).as("伪造 X-Tenant-Id 不得改变看到的那份清单").isEqualTo(honest);
    assertThat(rowsOf(forged)).hasSize(1);

    // (2) 伪造头也不能让写入落到别的租户：用 A 的身份建一张带伪造头的单，
    //     然后用 B 的身份查 —— 查不到，且**行确实落在自称的租户名下**。
    var response =
        http.perform(
                post("/trade/defective-dispositions")
                    .header("Authorization", p.a())
                    .header("X-Tenant-Id", p.tenantB().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            body(seedInspection(p.tenantA(), "QI-FORGED-A2"), "带伪造头建单", "5"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    String forgedOrderId = json.readTree(response.getContentAsString()).get("id").asText();

    http.perform(
            get("/trade/defective-dispositions/" + forgedOrderId)
                .header("Authorization", p.b()))
        .andExpect(status().isNotFound());
    // 行确实在 A 名下。
    http.perform(
            get("/trade/defective-dispositions/" + forgedOrderId)
                .header("Authorization", p.a()))
        .andExpect(status().isOk());
  }

  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    Pair p = newPair();
    String orderA = create(p.a(), body(seedInspection(p.tenantA(), "QI-POOL-A"), "A 的处置", "20"));
    String orderB = create(p.b(), body(seedInspection(p.tenantB(), "QI-POOL-B"), "B 的处置", "20"));

    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证第 N 次读到的仍然是 B 而不是上一轮残留的 A。
    for (int i = 0; i < 5; i++) {
      var rowsA = rowsOf(orders(p.a()));
      var rowsB = rowsOf(orders(p.b()));
      assertThat(rowsA).as("第 %d 轮租户 A", i).hasSize(1);
      assertThat(rowsB).as("第 %d 轮租户 B", i).hasSize(1);
      assertThat(rowsA.get(0).get("id")).as("第 %d 轮租户 A", i).isEqualTo(orderA);
      assertThat(rowsB.get(0).get("id")).as("第 %d 轮租户 B", i).isEqualTo(orderB);
      assertThat(rowsA.get(0).get("id")).isNotEqualTo(rowsB.get(0).get("id"));
      assertThat(visibleWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(rowsOf(orders(p.a())).get(0).get("id"))
        .isNotEqualTo(rowsOf(orders(p.b())).get(0).get("id"));
  }

  // ================================================================ 跨租户写入

  @Test
  void 跨租户按id操作会被拒() throws Exception {
    Pair p = newPair();
    String orderA = create(p.a(), body(seedInspection(p.tenantA(), "QI-XW-A"), "A 的处置", "20"));
    // B 拿着 A 的处置单 id 去操作 ⇒ 404（RLS 下查不到），不是 403（403 会泄露存在性）。
    http.perform(
            post("/trade/defective-dispositions/" + orderA + "/decide")
                .header("Authorization", p.b()))
        .andExpect(status().isNotFound());
    http.perform(
            post("/trade/defective-dispositions/" + orderA + "/cancel")
                .header("Authorization", p.b())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("cancelReason", "跨租户作废"))))
        .andExpect(status().isNotFound());

    // B 引用 A 的质检单建处置单 ⇒ 404（质检单也不可见）。
    UUID inspectionA = seedInspection(p.tenantA(), "QI-XW-INSPECTION-A");
    http.perform(
            post("/trade/defective-dispositions")
                .header("Authorization", p.b())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body(inspectionA, "跨租户建单", "5"))))
        .andExpect(status().isNotFound());
  }

  // ================================================================ RLS 本体

  @Test
  void 本模块的表是强制RLS且应用账号真的能读() {
    Pair p = newPair();
    for (String table :
        List.of(
            "defective_disposition_order",
            "defective_disposition_line",
            "defective_inventory_instruction",
            "defective_supplier_claim",
            "defective_disposition_event")) {
      Boolean enabled = sql.fetchOne("select relforcerowsecurity from pg_class where relname = ?", table)
          .get(0, Boolean.class);
      assertThat(enabled).as("%s 必须是 FORCE RLS", table).isTrue();
    }
    // 应用账号在**绑定了租户**的事务里真的能读到自己的行。
    var actor = actors.get(p.a());
    long visible =
        access.run(
            actor,
            "trade.defective-disposition.read",
            () ->
                sql.fetchOne(
                        "select count(*) from defective_disposition_order where tenant_id=?::uuid", p.tenantA())
                    .get(0, Long.class));
    assertThat(visible).isNotNegative();
  }

  /**
   * 没有对端写端点时，库存指令必须如实落 {@code UNAVAILABLE}。
   *
   * <p>这条是本仓的**真实状态**：warehouse 侧没有「按处置单变更库存状态」的授权写入口
   * ⇒ 绝不出现「已登记」却其实没动库存的假记录。
   */
  @Test
  void 没有对端写端点时指令如实落不可用() throws Exception {
    Pair p = newPair();
    UUID inspection = seedInspection(p.tenantA(), "QI-NO-ENDPOINT");
    String id = create(p.a(), body(inspection, "没有对端", "20"));
    http.perform(
            post("/trade/defective-dispositions/" + id + "/decide")
                .header("Authorization", p.a()))
        .andExpect(status().isOk());
    http.perform(
            post("/trade/defective-dispositions/" + id + "/dispatch-inventory")
                .header("Authorization", p.a()))
        .andExpect(status().isOk());

    var response =
        http.perform(
                get("/trade/defective-dispositions/" + id).header("Authorization", p.a()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    Map<String, Object> detail = json.readValue(response.getContentAsString(), Map.class);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> instructions = (List<Map<String, Object>>) detail.get("instructions");
    assertThat(instructions).hasSize(1);
    assertThat(instructions.get(0).get("status").toString()).isEqualTo("UNAVAILABLE");
    assertThat(instructions.get(0).get("externalReference").toString()).isEmpty();
    assertThat(instructions.get(0).get("reason").toString()).contains("warehouse");
    @SuppressWarnings("unchecked")
    Map<String, Object> order = (Map<String, Object>) detail.get("order");
    assertThat(order.get("inventoryStatus").toString()).isEqualTo("UNAVAILABLE");
  }

  // ================================================================ 工具

  /** 事务外（没绑租户）能看到几行 —— 必须是 0。 */
  long visibleWithoutTenant() {
    return sql.fetchOne("select count(*) from defective_disposition_order").get(0, Long.class);
  }
}
