package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
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
 * 6.4.2.2 不良品处理（trade 侧）。
 *
 * <p><b>本测试是 V256 迁移那些 DB 级不变量的独立复验</b>：逐条在真实 PostgreSQL 16 上跑一遍，
 * 并且走 HTTP 端点而不是只查数据库，以便同时验证「服务层 + DB 层」两层都没有把闸门漏掉。
 *
 * <p><b>上游数据怎么来的</b>：6.4.2.1 质检单在<b>同一个服务、同一个库</b>里
 * ⇒ 集成测试直接种一条**已完结**的质检单当夹具（不合格量 / 合格量 / 让步接收量都是
 * 明确写死的数），本模块的每个数量都必须从它回读。
 *
 * <p><b>下游数据怎么来的</b>：库存状态变更指令只经授权 HTTP 端口发出去 ——
 * 测试里起一个真的 HTTP stub（不是 new 一个假对象），端口对着它发；
 * 另一个测试类不配这个地址，用来验证「没有对端端点时如实标 UNAVAILABLE」。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DefectiveGoodsIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity, inventory;
  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          // 数量必须按 BigDecimal 读回来：用 Double 会把 100.000000 变成 100.0，
          // 断言时丢精度（而且看不出是精度问题还是口径问题）。
          .enable(
              com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** warehouse 侧收到的请求体（用来证明指令真的发出去了）。 */
  static final List<Map<String, Object>> inventoryCalls = new ArrayList<>();

  /** 让 warehouse 返回 500（模拟对端故障）时置 true。 */
  static final AtomicBoolean inventoryBroken = new AtomicBoolean(false);

  static final AtomicInteger inventoryCalls2 = new AtomicInteger();

  static final List<String> allPermissions =
      List.of(
          "trade.defective-disposition.read",
          "trade.defective-disposition.update",
          "trade.defective-disposition.concession");

  /** 没有让步接收权限码的身份（同一个租户，只少一个权限码）。 */
  static final String NO_CONCESSION = "Bearer no-concession";

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
      actors.put(
          "Bearer no-concession",
          actor(
              user,
              a,
              List.of(
                  grant(
                      "ALL",
                      List.of(
                          "trade.defective-disposition.read",
                          "trade.defective-disposition.update")))));
      identity.start();

      // ---- warehouse stub：库存状态变更指令的对端 ----
      inventory = HttpServer.create(new InetSocketAddress(0), 0);
      inventory.createContext(
          "/warehouse/inventory/dispositions",
          e -> {
            inventoryCalls2.incrementAndGet();
            if (tenantOf(e.getRequestHeaders().getFirst("Authorization")) == null) {
              write(inventory, e, 401, Map.of("message", "未认证"));
              return;
            }
            if (inventoryBroken.get()) {
              write(inventory, e, 500, Map.of("message", "仓库炸了"));
              return;
            }
            String raw = new String(e.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            inventoryCalls.add(json.readValue(raw, Map.class));
            write(inventory, e, 200, Map.of("reference", "INV-REF-0001"));
          });
      inventory.start();
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
    // 库存状态变更指令的**对端**地址：配了它，指令才真的发得出去。
    r.add("DEFECTIVE_INVENTORY_URL", () -> "http://localhost:" + inventory.getAddress().getPort());
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    inventory.stop(0);
    pg.stop();
  }

  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;


  /** 一个用例一个租户：避免各用例的处置单在同一个租户下互相污染计数与列表断言。 */
  record Tenant(String bearer, UUID id) {}

  static Tenant newTenant() {
    UUID tenant = UUID.randomUUID();
    String bearer = "Bearer t-" + tenant;
    actors.put(bearer, actor(user, tenant, List.of(grant("ALL", allPermissions))));
    actors.put(
        bearer + "-nc",
        actor(
            user,
            tenant,
            List.of(
                grant(
                    "ALL",
                    List.of(
                        "trade.defective-disposition.read",
                        "trade.defective-disposition.update")))));
    return new Tenant(bearer, tenant);
  }

  static String noConcession(String bearer) {
    return bearer + "-nc";
  }

  // ================================================================ 夹具

  /** 已完结的质检单的四个量（不合格量 20 / 合格量 70 / 让步接收量 10 / 扣量合计 30）。 */
  static final BigDecimal LOT = new BigDecimal("100");
  static final BigDecimal QUALIFIED = new BigDecimal("70");
  static final BigDecimal CONCESSION = new BigDecimal("10");
  static final BigDecimal DEFECTIVE = new BigDecimal("20");
  static final BigDecimal GROSS = new BigDecimal("30");

  /**
   * 种一条**已完结**的质检单当夹具。
   *
   * <p>三量自洽（70 + 20 + 10 = 100 = lot_size），且
   * 不合格量 + 让步接收量 = 扣量合计（20 + 10 = 30）—— 与 6.4.2.1 的 DB CHECK 同口径。
   */
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

  /** 建处置单的请求体。 */
  static Map<String, Object> body(
      UUID inspectionId,
      String reason,
      List<Map<String, Object>> lines,
      String currency,
      BigDecimal claimAmount) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("inspectionOrderId", inspectionId.toString());
    map.put("reason", reason);
    if (currency != null) {
      map.put("currencyCode", currency);
    }
    if (claimAmount != null) {
      map.put("claimAmount", claimAmount);
    }
    map.put("lines", lines);
    return map;
  }

  static Map<String, Object> line(String type, String quantity) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("dispositionType", type);
    map.put("quantity", new BigDecimal(quantity));
    return map;
  }

  String create(String bearer, Map<String, Object> body) throws Exception {
    var response =
        http.perform(
                post("/trade/defective-dispositions")
                    .header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus())
        .as("建处置单应当成功：%s", response.getContentAsString())
        .isEqualTo(200);
    return json.readTree(response.getContentAsString()).get("id").asText();
  }

  String createExpecting(int status, String bearer, Map<String, Object> body)
      throws Exception {
    var response =
        http.perform(
                post("/trade/defective-dispositions")
                    .header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).as("响应：%s", response.getContentAsString()).isEqualTo(status);
    return response.getContentAsString();
  }

  Map<String, Object> detail(String bearer, String id) throws Exception {
    var response =
        http.perform(
                get("/trade/defective-dispositions/" + id).header("Authorization", bearer))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    return json.readValue(response.getContentAsString(), Map.class);
  }

  // ================================================================ 用例

  @Test
  void 建处置单的数量全部来自质检单_不接受调用方自报() throws Exception {
    Tenant t = newTenant();
    UUID inspection = seedInspection(t.id(), "QI-SRC-1");
    Map<String, Object> payload = body(inspection, "这批外包装压坏了", List.of(line("SCRAP", "20")), null, null);

    // 🔴 入参里塞一个「处置总量」必须被拒（对象映射开了 fail-on-unknown-properties）。
    payload.put("disposedQuantity", new BigDecimal("999"));
    createExpecting(400, t.bearer(), payload);

    // 不塞的话，处置量由明细行算出来 = 20，且与质检单的不合格量对得上。
    payload.remove("disposedQuantity");
    String id = create(t.bearer(), payload);
    Map<String, Object> detail = detail(t.bearer(), id);
    Map<String, Object> order = castMap(detail.get("order"));
    assertThat(new BigDecimal(order.get("disposedQuantity").toString()))
        .isEqualByComparingTo(new BigDecimal("20"));
    assertThat(new BigDecimal(order.get("inspectionDefectiveQuantity").toString()))
        .isEqualByComparingTo(DEFECTIVE);
    // 可售量上限 = 合格量 + 让步接收量 = 80，被显式带到界面上。
    assertThat(new BigDecimal(order.get("sellableCeiling").toString()))
        .isEqualByComparingTo(new BigDecimal("80"));
    assertThat(new BigDecimal(order.get("remainingQuantity").toString()))
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(order.get("dispositionSummary").toString()).contains("SCRAP");
  }

  @Test
  void 处置量不得超过质检单的不合格量_累计也受同一条约束() throws Exception {
    Tenant t = newTenant();
    UUID inspection = seedInspection(t.id(), "QI-SRC-2");
    // 一次超量。
    createExpecting(
        409, t.bearer(), body(inspection, "超量", List.of(line("SCRAP", "21")), null, null));

    // 分两张单各处置 15 件 —— 单张都合法，但累计 30 > 不合格量 20 ⇒ 第二张被拒。
    create(t.bearer(), body(inspection, "第一批", List.of(line("SCRAP", "15")), null, null));
    createExpecting(
        409, t.bearer(), body(inspection, "第二批", List.of(line("SCRAP", "15")), null, null));

    // 再来 5 件刚好用完额度（15 + 5 = 20 = 不合格量）。
    String third = create(t.bearer(), body(inspection, "第三批", List.of(line("SCRAP", "5")), null, null));
    createExpecting(
        409, t.bearer(), body(inspection, "第四批", List.of(line("SCRAP", "1")), null, null));
    // 作废第三批后，额度让出来 5 件。
    http.perform(
            post("/trade/defective-dispositions/" + third + "/cancel")
                .header("Authorization", t.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("cancelReason", "撤销"))))
        .andExpect(status().isOk());
    create(t.bearer(), body(inspection, "第五批", List.of(line("SCRAP", "5")), null, null));
  }

  @Test
  void 让步接收必须带原因_也必须带权限() throws Exception {
    Tenant t = newTenant();
    UUID inspection = seedInspection(t.id(), "QI-SRC-3");

    // 缺原因。
    Map<String, Object> noReason = line("CONCESSION", "5");
    createExpecting(400, t.bearer(), body(inspection, "缺原因", List.of(noReason), null, null));

    // 有原因但记录人没有让步接收权限码 ⇒ 403（质检员不能随手放行）。
    Map<String, Object> withReason = line("CONCESSION", "5");
    withReason.put("concessionReason", "客户同意接受轻微外观瑕疵");
    createExpecting(403, noConcession(t.bearer()), body(inspection, "缺权限", List.of(withReason), null, null));

    // 有原因 + 有权限 ⇒ 放行。
    Map<String, Object> ok = line("CONCESSION", "5");
    ok.put("concessionReason", "客户同意接受轻微外观瑕疵");
    ok.put("permissionCode", "trade.defective-disposition.concession");
    String id = create(t.bearer(), body(inspection, "让步接收", List.of(ok), null, null));
    Map<String, Object> detail = detail(t.bearer(), id);
    List<Map<String, Object>> lines = castList(detail.get("lines"));
    assertThat(lines).hasSize(1);
    assertThat(lines.get(0).get("concessionReason").toString()).contains("外观瑕疵");
    assertThat(lines.get(0).get("permissionCode").toString())
        .isEqualTo("trade.defective-disposition.concession");
    // 让步接收的行**整行**回可售池 ⇒ 放行量 = 该行数量。
    assertThat(new BigDecimal(lines.get(0).get("releasedQuantity").toString()))
        .isEqualByComparingTo(new BigDecimal("5"));
  }

  @Test
  void 放行量不得超过质检单的扣量合计() throws Exception {
    Tenant t = newTenant();
    UUID inspection = seedInspection(t.id(), "QI-SRC-4");
    // 扣量合计 30，不合格量 20。让步 25 件：单行数量就超了不合格量 ⇒ 被拒。
    Map<String, Object> line = line("CONCESSION", "25");
    line.put("concessionReason", "供应商同意让步");
    createExpecting(409, t.bearer(), body(inspection, "让步超量", List.of(line), null, null));
  }

  @Test
  void 放行量由处置类型决定_不接受调用方自报() throws Exception {
    Tenant t = newTenant();
    UUID inspection = seedInspection(t.id(), "QI-SRC-5");
    Map<String, Object> line = line("SCRAP", "5");
    line.put("releasedQuantity", new BigDecimal("5"));
    createExpecting(400, t.bearer(), body(inspection, "自报放行量", List.of(line), null, null));
  }

  @Test
  void 决策定稿生成库存指令与供应商索赔记录() throws Exception {
    Tenant t = newTenant();
    UUID inspection = seedInspection(t.id(), "QI-SRC-6");
    String id =
        create(
            t.bearer(),
            body(
                inspection,
                "外箱压坏，供应商同意退款并补货",
                List.of(line("RETURN_EXCHANGE", "8"), line("SCRAP", "12")),
                "USD",
                new BigDecimal("500.00")));

    http.perform(
            post("/trade/defective-dispositions/" + id + "/decide")
                .header("Authorization", t.bearer()))
        .andExpect(status().isOk());

    Map<String, Object> detail = detail(t.bearer(), id);
    List<Map<String, Object>> instructions = castList(detail.get("instructions"));
    assertThat(instructions).hasSize(2);
    // 每条指令都写明**写入所有权归谁**，且没派发出去之前状态是「未派发」。
    assertThat(instructions.get(0).get("ownerModule").toString()).contains("warehouse");
    assertThat(instructions.get(0).get("status").toString()).isEqualTo("NOT_DISPATCHED");
    assertThat(instructions.get(0).get("action").toString()).isEqualTo("RETURN_OUT");

    List<Map<String, Object>> claims = castList(detail.get("claims"));
    assertThat(claims).hasSize(1);
    assertThat(claims.get(0).get("currencyCode").toString()).isEqualTo("USD");
    assertThat(new BigDecimal(claims.get(0).get("claimedAmount").toString()))
        .isEqualByComparingTo(new BigDecimal("500.0000"));
    // 🔴 已生效冲减金额恒为 0（12 财务在本仓不存在），财务状态恒为 PENDING_FINANCE。
    assertThat(new BigDecimal(claims.get(0).get("effectiveAmount").toString()))
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(claims.get(0).get("financeStatus").toString()).isEqualTo("PENDING_FINANCE");
    assertThat(claims.get(0).get("financeReference").toString()).isEmpty();

    // 定稿后不许再改明细。
    http.perform(
            put("/trade/defective-dispositions/" + id)
                .header("Authorization", t.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body(inspection, "改一下", List.of(line("SCRAP", "1")), null, null))))
        .andExpect(status().isConflict());
  }

  @Test
  void 未登记索赔金额时按质检单的单价快照乘处置量推导() throws Exception {
    Tenant t = newTenant();
    UUID inspection = seedInspection(t.id(), "QI-SRC-7");
    String id = create(t.bearer(), body(inspection, "推导金额", List.of(line("SCRAP", "20")), null, null));
    http.perform(
            post("/trade/defective-dispositions/" + id + "/decide")
                .header("Authorization", t.bearer()))
        .andExpect(status().isOk());
    Map<String, Object> detail = detail(t.bearer(), id);
    List<Map<String, Object>> claims = castList(detail.get("claims"));
    assertThat(claims).hasSize(1);
    // 单价 25 × 处置量 20 = 500。
    assertThat(new BigDecimal(claims.get(0).get("claimedAmount").toString()))
        .isEqualByComparingTo(new BigDecimal("500.0000"));
    // 币种取自质检单的币种快照。
    assertThat(claims.get(0).get("currencyCode").toString()).isEqualTo("USD");
  }

  @Test
  void 派发库存指令会真的发到对端_并如实落状态() throws Exception {
    Tenant t = newTenant();
    inventoryCalls.clear();
    UUID inspection = seedInspection(t.id(), "QI-SRC-8");
    String id = create(t.bearer(), body(inspection, "派发", List.of(line("SCRAP", "20")), null, null));
    http.perform(
            post("/trade/defective-dispositions/" + id + "/decide")
                .header("Authorization", t.bearer()))
        .andExpect(status().isOk());

    http.perform(
            post("/trade/defective-dispositions/" + id + "/dispatch-inventory")
                .header("Authorization", t.bearer()))
        .andExpect(status().isOk());

    // 指令真的发到对端了（HTTP stub 收到了）。
    assertThat(inventoryCalls).hasSize(1);
    assertThat(inventoryCalls.get(0).get("action").toString()).isEqualTo("SCRAP_OUT");
    assertThat(new BigDecimal(inventoryCalls.get(0).get("quantity").toString()))
        .isEqualByComparingTo(new BigDecimal("20"));

    Map<String, Object> detail = detail(t.bearer(), id);
    List<Map<String, Object>> instructions = castList(detail.get("instructions"));
    assertThat(instructions.get(0).get("status").toString()).isEqualTo("DISPATCHED");
    assertThat(instructions.get(0).get("externalReference").toString()).isEqualTo("INV-REF-0001");
    assertThat(castMap(detail.get("order")).get("inventoryStatus").toString())
        .isEqualTo("DISPATCHED");
  }

  @Test
  void 对端故障时指令状态如实落失败_不假装已登记() throws Exception {
    Tenant t = newTenant();
    inventoryBroken.set(true);
    try {
      UUID inspection = seedInspection(t.id(), "QI-SRC-9");
      String id = create(t.bearer(), body(inspection, "对端故障", List.of(line("SCRAP", "20")), null, null));
      http.perform(
              post("/trade/defective-dispositions/" + id + "/decide")
                  .header("Authorization", t.bearer()))
          .andExpect(status().isOk());
      http.perform(
              post("/trade/defective-dispositions/" + id + "/dispatch-inventory")
                  .header("Authorization", t.bearer()))
          .andExpect(status().isOk());
      Map<String, Object> detail = detail(t.bearer(), id);
      List<Map<String, Object>> instructions = castList(detail.get("instructions"));
      assertThat(instructions.get(0).get("status").toString()).isEqualTo("FAILED");
      assertThat(instructions.get(0).get("externalReference").toString()).isEmpty();
    } finally {
      inventoryBroken.set(false);
    }
  }

  @Test
  void 索赔走完提交到认可_已生效冲减金额仍是零() throws Exception {
    Tenant t = newTenant();
    UUID inspection = seedInspection(t.id(), "QI-SRC-10");
    String id =
        create(
            t.bearer(),
            body(inspection, "索赔", List.of(line("SCRAP", "20")), "USD", new BigDecimal("500.00")));
    http.perform(
            post("/trade/defective-dispositions/" + id + "/decide")
                .header("Authorization", t.bearer()))
        .andExpect(status().isOk());
    String claimId = castList(detail(t.bearer(), id).get("claims")).get(0).get("id").toString();

    http.perform(
            post("/trade/defective-dispositions/" + id + "/claims/" + claimId + "/submit")
                .header("Authorization", t.bearer()))
        .andExpect(status().isOk());

    http.perform(
            post("/trade/defective-dispositions/" + id + "/claims/" + claimId + "/agree")
                .header("Authorization", t.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("agreedAmount", new BigDecimal("450.00"), "note", "供应商认一半"))))
        .andExpect(status().isOk());

    Map<String, Object> claim = castList(detail(t.bearer(), id).get("claims")).get(0);
    assertThat(claim.get("status").toString()).isEqualTo("AGREED");
    assertThat(new BigDecimal(claim.get("agreedAmount").toString()))
        .isEqualByComparingTo(new BigDecimal("450.0000"));
    // 🔴 认可了赔付 ≠ 已经冲减了应付：12 财务未交付 ⇒ 已生效金额恒为 0。
    assertThat(new BigDecimal(claim.get("effectiveAmount").toString()))
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(claim.get("financeStatus").toString()).isEqualTo("PENDING_FINANCE");
  }

  @Test
  void 认可金额不得超过登记金额() throws Exception {
    Tenant t = newTenant();
    UUID inspection = seedInspection(t.id(), "QI-SRC-11");
    String id =
        create(
            t.bearer(),
            body(inspection, "索赔", List.of(line("SCRAP", "20")), "USD", new BigDecimal("100.00")));
    http.perform(
            post("/trade/defective-dispositions/" + id + "/decide")
                .header("Authorization", t.bearer()))
        .andExpect(status().isOk());
    String claimId = castList(detail(t.bearer(), id).get("claims")).get(0).get("id").toString();
    http.perform(
            post("/trade/defective-dispositions/" + id + "/claims/" + claimId + "/submit")
                .header("Authorization", t.bearer()))
        .andExpect(status().isOk());
    http.perform(
            post("/trade/defective-dispositions/" + id + "/claims/" + claimId + "/agree")
                .header("Authorization", t.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("agreedAmount", new BigDecimal("500.00")))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 拒赔必须写理由() throws Exception {
    Tenant t = newTenant();
    UUID inspection = seedInspection(t.id(), "QI-SRC-12");
    String id =
        create(
            t.bearer(),
            body(inspection, "索赔", List.of(line("SCRAP", "20")), "USD", new BigDecimal("100.00")));
    http.perform(
            post("/trade/defective-dispositions/" + id + "/decide")
                .header("Authorization", t.bearer()))
        .andExpect(status().isOk());
    String claimId = castList(detail(t.bearer(), id).get("claims")).get(0).get("id").toString();
    http.perform(
            post("/trade/defective-dispositions/" + id + "/claims/" + claimId + "/submit")
                .header("Authorization", t.bearer()))
        .andExpect(status().isOk());
    http.perform(
            post("/trade/defective-dispositions/" + id + "/claims/" + claimId + "/reject")
                .header("Authorization", t.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("reason", "  "))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 数据库层挡住绕过服务层直接改处置量() throws Exception {
    Tenant t = newTenant();
    UUID inspection = seedInspection(t.id(), "QI-SRC-13");
    String id = create(t.bearer(), body(inspection, "绕过", List.of(line("SCRAP", "20")), null, null));
    UUID orderId = UUID.fromString(id);
    var actor = actors.get(t.bearer());

    // 绕过服务层直连 SQL 把处置量改成 999 ⇒ DB 触发器必须挡下来。
    assertThatThrownBy(
            () ->
                access.run(
                    actor,
                    "trade.defective-disposition.update",
                    () -> {
                      sql.execute(
                          "update defective_disposition_order set disposed_quantity = ?"
                              + " where tenant_id=?::uuid and id=?::uuid",
                          new BigDecimal("999"),
                          t.id(),
                          orderId);
                      return null;
                    }))
        .hasMessageContaining("处置量");
  }

  @Test
  void 数据库层挡住伪造的质检结论快照() throws Exception {
    Tenant t = newTenant();
    UUID inspection = seedInspection(t.id(), "QI-SRC-14");
    String id = create(t.bearer(), body(inspection, "伪造快照", List.of(line("SCRAP", "20")), null, null));
    UUID orderId = UUID.fromString(id);
    var actor = actors.get(t.bearer());
    assertThatThrownBy(
            () ->
                access.run(
                    actor,
                    "trade.defective-disposition.update",
                    () -> {
                      sql.execute(
                          "update defective_disposition_order"
                              + " set inspection_defective_quantity = ?"
                              + " where tenant_id=?::uuid and id=?::uuid",
                          new BigDecimal("999"),
                          t.id(),
                          orderId);
                      return null;
                    }))
        .hasMessageContaining("质检结论");
  }

  @Test
  void 时间写回没有负八小时漂移() throws Exception {
    Tenant t = newTenant();
    UUID inspection = seedInspection(t.id(), "QI-SRC-15");
    String id = create(t.bearer(), body(inspection, "时间", List.of(line("SCRAP", "20")), null, null));
    http.perform(
            post("/trade/defective-dispositions/" + id + "/decide")
                .header("Authorization", t.bearer()))
        .andExpect(status().isOk());
    var actor = actors.get(t.bearer());
    OffsetDateTime decidedAt =
        access.run(
            actor,
            "trade.defective-disposition.read",
            () -> {
              var row =
                  sql.fetchOne(
                      "select decided_at from defective_disposition_order"
                          + " where tenant_id=?::uuid and id=?::uuid",
                      t.id(),
                      UUID.fromString(id));
              return row.get("decided_at", OffsetDateTime.class);
            });
    assertThat(decidedAt).isNotNull();
    // 与「现在」相差不到一小时 ⇒ 没有静默偏 −28800s（那是用 Timestamp.valueOf 绑 timestamptz 的症状）。
    assertThat(Math.abs(java.time.Duration.between(OffsetDateTime.now(ZoneOffsetHolder.UTC), decidedAt).toMinutes()))
        .isLessThan(60L);
  }

  @Test
  void 能力声明把没接通的下游逐条点名() throws Exception {
    Tenant t = newTenant();
    var response =
        http.perform(
                get("/trade/defective-dispositions/capabilities")
                    .header("Authorization", t.bearer()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    Map<String, Object> capabilities = json.readValue(response.getContentAsString(), Map.class);
    List<Map<String, Object>> downstream = castList(capabilities.get("downstream"));
    Map<String, Object> inventory = downstream.stream()
        .filter(x -> "INVENTORY_ADJUSTMENT".equals(x.get("code")))
        .findFirst()
        .orElseThrow();
    assertThat(inventory.get("available")).isEqualTo(false);
    assertThat(inventory.get("ownerModule").toString()).contains("7.1.1.2");
    assertThat(inventory.get("impact").toString()).contains("可售量");

    Map<String, Object> finance = downstream.stream()
        .filter(x -> "FINANCE_CLAIM".equals(x.get("code")))
        .findFirst()
        .orElseThrow();
    assertThat(finance.get("available")).isEqualTo(false);
    assertThat(finance.get("ownerModule").toString()).contains("12 财务");
  }

  @Test
  void 列表是真分页真筛选_汇总视图能算出数() throws Exception {
    Tenant t = newTenant();
    UUID inspection = seedInspection(t.id(), "QI-SRC-16");
    create(t.bearer(), body(inspection, "第一张", List.of(line("SCRAP", "10")), null, null));
    create(t.bearer(), body(inspection, "第二张", List.of(line("REPAIR", "5")), null, null));

    var all =
        http.perform(
                get("/trade/defective-dispositions").param("size", "1").param("page", "0")
                    .header("Authorization", t.bearer()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    Map<String, Object> page = json.readValue(all.getContentAsString(), Map.class);
    assertThat(castList(page.get("rows"))).hasSize(1);
    assertThat(((Number) page.get("total")).intValue()).isEqualTo(2);
    assertThat(((Number) page.get("totalPages")).intValue()).isEqualTo(2);

    var filtered =
        http.perform(
                get("/trade/defective-dispositions").param("dispositionType", "REPAIR")
                    .header("Authorization", t.bearer()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    Map<String, Object> filteredPage = json.readValue(filtered.getContentAsString(), Map.class);
    assertThat(((Number) filteredPage.get("total")).intValue()).isEqualTo(1);

    var summary =
        http.perform(
                get("/trade/defective-dispositions/summary")
                    .header("Authorization", t.bearer()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    Map<String, Object> sum = json.readValue(summary.getContentAsString(), Map.class);
    assertThat(((Number) sum.get("orderCount")).intValue()).isEqualTo(2);
    assertThat(new BigDecimal(sum.get("disposedQuantity").toString()))
        .isEqualByComparingTo(new BigDecimal("15"));
  }

  @Test
  void 导出CSV含处置单明细指令索赔与事件五段() throws Exception {
    Tenant t = newTenant();
    UUID inspection = seedInspection(t.id(), "QI-SRC-17");
    String id =
        create(
            t.bearer(),
            body(inspection, "导出", List.of(line("SCRAP", "20")), "USD", new BigDecimal("500.00")));
    http.perform(
            post("/trade/defective-dispositions/" + id + "/decide")
                .header("Authorization", t.bearer()))
        .andExpect(status().isOk());
    var response =
        http.perform(
                get("/trade/defective-dispositions/" + id + "/export")
                    .header("Authorization", t.bearer()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    String csv = response.getContentAsString();
    assertThat(csv).contains("处置单号");
    assertThat(csv).contains("处置明细");
    assertThat(csv).contains("库存状态变更指令");
    assertThat(csv).contains("供应商索赔");
    assertThat(csv).contains("事件台账");
    assertThat(csv).contains("SCRAP");
  }

  @Test
  void 未认证与越权都被拒() throws Exception {
    Tenant t = newTenant();
    http.perform(get("/trade/defective-dispositions")).andExpect(status().isUnauthorized());
    http.perform(get("/trade/defective-dispositions/capabilities"))
        .andExpect(status().isUnauthorized());
    UUID inspection = seedInspection(t.id(), "QI-SRC-18");
    http.perform(
            post("/trade/defective-dispositions")
                .header("Authorization", "Bearer nobody")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body(inspection, "x", List.of(line("SCRAP", "1")), null, null))))
        .andExpect(status().isUnauthorized());
  }

  // ================================================================ 工具

  /** UTC 偏移（避免在断言里散落 ZoneOffset 的 import）。 */
  static final class ZoneOffsetHolder {
    static final java.time.ZoneOffset UTC = java.time.ZoneOffset.UTC;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> castMap(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> castList(Object value) {
    return (List<Map<String, Object>>) value;
  }
}
