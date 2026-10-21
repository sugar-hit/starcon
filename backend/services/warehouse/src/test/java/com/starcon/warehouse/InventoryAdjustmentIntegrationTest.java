package com.starcon.warehouse;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 7.4.1.1 调整单管理：批量建单、原因码词表、凭证附件、乐观锁、状态机、<b>未审批不得入账</b>、 入账数量守恒，以及分录落进 7.1.2.1 流水台账。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本测试的核心是 {@link #未审批的调整单在数据库层写不进任何分录()} 一条</b>。
 * 蓝图原话：「**调整必须走单据**，不允许在明细页直接改余额——没有单据就没有责任人与原因。」
 * 只测「审批后能过账」证明不了这条红线 —— 拿连接直接 INSERT 分录一样能改余额。
 * 所以必须**真的**去数据库里硬写分录，验证 V260 的入账闸门会当场拒绝。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InventoryAdjustmentIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();

  /** 第二个用户：验证「提交人 ≠ 审批人」的职责分离。 */
  static final UUID approver = UUID.randomUUID();

  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      Stream.of("inventory")
          .flatMap(
              p ->
                  Stream.of("read", "create", "update", "delete")
                      .map(op -> "warehouse." + p + "." + op))
          .toList();

  static final UUID le = idFor(a, 1);
  static final UUID skuOne = idFor(a, 2);
  static final UUID skuTwo = idFor(a, 3);
  static final UUID whOne = idFor(a, 4);
  static final UUID whTwo = idFor(a, 5);
  static final UUID qSellable = idFor(a, 6);
  static final UUID locOne = idFor(a, 7);
  static final UUID locTwo = idFor(a, 8);
  static final UUID locThree = idFor(a, 9);
  static final UUID lotOne = idFor(a, 10);

  static final ObjectMapper JSON = new ObjectMapper();

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-adjustment-" + tenant + "-" + ordinal).getBytes());
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

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        s.execute("create role warehouse_app login password 'test-only' nosuperuser nobypassrls");
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
            byte[] body = JSON.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer approver", actor(approver, a, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only",
          actor(user, a, List.of(grant("ALL", List.of("warehouse.inventory.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "warehouse_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    // 6.4.2.2 的 trade 端口在本测试里**故意指向一个没人监听的端口**：
    // 上游不可达必须如实报不可用，而不是返回空清单冒充「没有不良品处置单」。
    r.add("TRADE_URL", () -> "http://127.0.0.1:1");
  }

  @Autowired AccessService access;
  @Autowired InventoryAdjustmentService service;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actorA;
  AccessService.Access actorB;
  AccessService.Access actorApprover;

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    actorApprover = actors.get("Bearer approver");
    // TRUNCATE 不触发行级触发器，以超级用户连接执行可绕过 FORCE RLS ——
    // 只用于测试夹具重建，不给应用角色开这个口子。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate inventory_adjustment_attachment, inventory_adjustment_order_line,"
              + " inventory_adjustment_audit, inventory_adjustment_order,"
              + " inventory_entry, inventory_transaction, stock_bucket, inventory_balance,"
              + " warehouse_location, inventory_lot, inventory_quality_status, warehouse, sku_ref,"
              + " legal_entity_ref restart identity cascade");
    }
    for (var tenant : List.of(a, b)) {
      access.run(
          actorFor(tenant),
          "warehouse.inventory.read",
          () -> {
            sql.execute(
                "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 1),
                tenant,
                "LE-1",
                "主体一");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 2),
                tenant,
                "SKU-1",
                "商品一");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 3),
                tenant,
                "SKU-2",
                "商品二");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
                idFor(tenant, 4),
                tenant,
                "WH-ONE",
                "一仓",
                "CN",
                "Asia/Shanghai");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
                idFor(tenant, 5),
                tenant,
                "WH-TWO",
                "二仓",
                "CN",
                "Asia/Shanghai");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 6),
                tenant,
                "SELLABLE",
                "可售",
                "SELLABLE");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name) values(?,?,?,?,?)",
                idFor(tenant, 7),
                tenant,
                idFor(tenant, 4),
                "A-01",
                "A 区一排");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name) values(?,?,?,?,?)",
                idFor(tenant, 8),
                tenant,
                idFor(tenant, 4),
                "A-02",
                "A 区二排");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name) values(?,?,?,?,?)",
                idFor(tenant, 9),
                tenant,
                idFor(tenant, 5),
                "B-01",
                "B 区一排");
            sql.execute(
                "insert into inventory_lot(id,tenant_id,sku_id,lot_code,received_on) values(?,?,?,?,?)",
                idFor(tenant, 10),
                tenant,
                idFor(tenant, 2),
                "LOT-A",
                java.sql.Date.valueOf("2026-09-01"));
            return null;
          });
    }
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  static OffsetDateTime now() {
    return OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
  }

  // ================================================================ 夹具

  /**
   * 写一个 bucket（租户 A），返回 bucket id。
   *
   * <p><b>balance 按 (sku, 仓库, 法人, 质量状态) 复用</b>：这正是既有口径（V1 的
   * {@code UNIQUE(tenant_id,sku_id,warehouse_id,legal_entity_id,quality_status_id)}）——
   * 同一批货在一个仓只有一个 balance 行，库位与批次在它之下拆成多行 bucket。
   */
  UUID bucket(UUID warehouse, UUID sku, UUID location, UUID lot, String onHand) {
    return access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          var existing =
              sql.fetchOne(
                  "select id from inventory_balance where tenant_id = ? and sku_id = ?"
                      + " and warehouse_id = ? and legal_entity_id = ? and quality_status_id = ?",
                  a,
                  sku,
                  warehouse,
                  le,
                  qSellable);
          UUID balanceId;
          if (existing == null) {
            balanceId = UUID.randomUUID();
            sql.execute(
                "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                    + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,0::numeric,0::numeric)",
                balanceId,
                a,
                sku,
                warehouse,
                le,
                qSellable);
          } else {
            balanceId = existing.get("id", UUID.class);
          }
          UUID bucketId = UUID.randomUUID();
          sql.execute(
              "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,location_id,"
                  + "lot_id,quality_status_id,balance_id,on_hand,reserved,last_movement_at) "
                  + "values(?,?,?,?,?,?,?,?,?,?,0::numeric,?::timestamptz)",
              bucketId,
              a,
              le,
              sku,
              warehouse,
              location,
              lot,
              qSellable,
              balanceId,
              new BigDecimal(onHand),
              now());
          // balance 与 bucket 同一事务里对上（守恒是延迟约束触发器，提交前核对）。
          sql.execute(
              "update inventory_balance set on_hand = on_hand + ?::numeric where tenant_id = ? and id = ?",
              new BigDecimal(onHand),
              a,
              balanceId);
          return bucketId;
        });
  }

  long bucketVersion(UUID bucketId) {
    return access.run(
        actorA,
        "warehouse.inventory.read",
        () ->
            sql.fetchOne(
                    "select version from stock_bucket where tenant_id = ? and id = ?", a, bucketId)
                .get("version", Long.class));
  }

  BigDecimal balanceOnHand(UUID balanceId) {
    return access.run(
        actorA,
        "warehouse.inventory.read",
        () ->
            sql.fetchOne(
                    "select on_hand from inventory_balance where tenant_id = ? and id = ?",
                    a,
                    balanceId)
                .get("on_hand", BigDecimal.class));
  }

  // ================================================================ HTTP 助手

  MvcResult postJson(String path, Object body, String bearer) throws Exception {
    return http.perform(
            post(path)
                .header("Authorization", bearer)
                .contentType("application/json")
                .content(JSON.writeValueAsString(body)))
        .andReturn();
  }

  Map<String, Object> createOrder(Map<String, Object> body, String bearer) throws Exception {
    var result = postJson("/warehouse/inventory/adjustments", body, bearer);
    assertThat(result.getResponse().getStatus())
        .withFailMessage("建单失败：" + result.getResponse().getContentAsString())
        .isEqualTo(200);
    Map<String, Object> detail = JSON.readValue(result.getResponse().getContentAsString(), Map.class);
    // 头字段（code/status/lineCount/...）在 detail.order 里，明细/凭证/审计在 detail 顶层。
    // 这里把头字段平铺到顶层，测试读单据属性时不用每处都多写一层 .get("order")。
    @SuppressWarnings("unchecked")
    Map<String, Object> header = (Map<String, Object>) detail.get("order");
    Map<String, Object> flat = new java.util.LinkedHashMap<>(header);
    flat.putAll(detail);
    return flat;
  }

  static Map<String, Object> line(UUID bucketId, String qty, long expectedVersion, String reason) {
    var m = new java.util.LinkedHashMap<String, Object>();
    m.put("bucketId", bucketId.toString());
    m.put("qtyDelta", new BigDecimal(qty));
    m.put("expectedVersion", expectedVersion);
    if (reason != null) {
      m.put("reasonCode", reason);
    }
    return m;
  }

  /** JSON 里的整数（Jackson 读成 Integer / Long 都可能）统一取 int。 */
  static int intOf(Object value) {
    return ((Number) value).intValue();
  }

  /** JSON 里的数量统一取 BigDecimal：**不经过 double**（读成 double 再比会引入浮点误差）。 */
  static BigDecimal numOf(Object value) {
    return new BigDecimal(String.valueOf(value));
  }

  /**
   * 按行的**身份**（sku × 库位 × 批次）从明细里找出那一行。
   *
   * <p>🔴 不给「按位置取行」的写法留口子：行的先后是「加行顺序」（line_no，V261），
   * 而主键是随机 UUID —— {@code get(0)} 断言的是运气，不是业务。
   */
  static Map<String, Object> lineOf(
      List<Map<String, Object>> lines, String skuCode, String locationCode, String lotCode) {
    var found =
        lines.stream()
            .filter(
                l ->
                    skuCode.equals(l.get("skuCode"))
                        && locationCode.equals(l.get("locationCode"))
                        && lotCode.equals(l.get("lotCode")))
            .toList();
    assertThat(found)
        .as("按身份找行：sku=%s location=%s lot=%s", skuCode, locationCode, lotCode)
        .hasSize(1);
    return found.get(0);
  }

  static Map<String, Object> body(String reasonCode, List<Map<String, Object>> lines) {    var m = new java.util.LinkedHashMap<String, Object>();
    m.put("reasonCode", reasonCode);
    m.put("note", "外箱破损，按现场照片调整");
    m.put("lines", lines);
    return m;
  }

  // ------------------------------------------------------------ 服务层错误断言

  /**
   * 直接调服务层取错误原因。
   *
   * <p>为什么不在 HTTP 层断言错误文案：MockMvc 里 {@code ResponseStatusException} 走
   * {@code sendError}，响应体是空的（生产环境由容器把 message 填进默认错误体）。
   * 服务层拿到的 {@code getReason()} 才是真正给用户看的那句话 ——
   * 「这句话说清楚了没有」正是本模块要验的东西。
   */
  ResponseStatusException serviceFailure(Runnable work) {
    return org.junit.jupiter.api.Assertions.assertThrows(
        ResponseStatusException.class,
        () ->
            access.run(
                actorA,
                "warehouse.inventory.update",
                () -> {
                  work.run();
                  return null;
                }));
  }

  String serviceFailureReason(Runnable work) {
    var ex = serviceFailure(work);
    return ex.getReason() == null ? "" : ex.getReason();
  }

  /** 用模型对象建一张调整单（服务层入口）。 */
  InventoryAdjustmentModels.InventoryAdjustmentCreateInput createInput(
      String reasonCode, List<InventoryAdjustmentModels.InventoryAdjustmentLineInput> lines) {
    return new InventoryAdjustmentModels.InventoryAdjustmentCreateInput(
        reasonCode, "外箱破损，按现场照片调整", lines, null, null, null);
  }

  InventoryAdjustmentModels.InventoryAdjustmentLineInput lineInput(
      UUID bucketId, String qty, Long expectedVersion, String reason) {
    return new InventoryAdjustmentModels.InventoryAdjustmentLineInput(
        bucketId, new BigDecimal(qty), reason, expectedVersion, null);
  }

  // ================================================================ 建单

  @Test
  void 一次建单多行_批量调整落到同一张单() throws Exception {
    var b1 = bucket(whOne, skuOne, locOne, lotOne, "100");
    var b2 = bucket(whOne, skuOne, locTwo, null, "60");

    var order =
        createOrder(
            body(
                "DAMAGE",
                List.of(
                    line(b1, "-3", bucketVersion(b1), null),
                    line(b2, "-2", bucketVersion(b2), null))),
            "Bearer tenant-a");

    assertThat(order.get("code")).asString().startsWith("ADJ-INV-");
    assertThat(order.get("status")).isEqualTo("DRAFT");
    assertThat(intOf(order.get("lineCount"))).isEqualTo(2);
    assertThat(numOf(order.get("lossQty"))).isEqualByComparingTo("5");
    assertThat(numOf(order.get("netQty"))).isEqualByComparingTo("-5");

    @SuppressWarnings("unchecked")
    var lines = (List<Map<String, Object>>) order.get("lines");
    assertThat(lines).hasSize(2);
    // 六维定位落在行上：调的是哪一件必须能一眼看出来。
    //
    // 🔴 按行的**身份**（sku × 库位 × 批次）找那一行，**不按 get(0) / get(1) 的位置**：
    // 行的先后由「加行顺序」决定（line_no，V261），而主键是随机 UUID ——
    // 断言位置等于断言运气（这条用例上一轮 L2 是绿的，这一轮红了 1 例：约 50% 概率的假红）。
    // 这里断言的是「这两行都在、内容各自对」，不是「谁排在前面」。
    var lotA = lineOf(lines, "SKU-1", "A-01", "LOT-A");
    var noLot = lineOf(lines, "SKU-1", "A-02", "");
    assertThat(numOf(lotA.get("qtyDelta"))).isEqualByComparingTo("-3");
    assertThat(numOf(noLot.get("qtyDelta"))).isEqualByComparingTo("-2");
    // 草稿阶段余额一个数都没动
    assertThat(bucketOnHand(b1)).isEqualByComparingTo("100");
    assertThat(bucketOnHand(b2)).isEqualByComparingTo("60");
    // 同一批货在一个仓只有一个 balance 行（库位/批次在它之下拆开）
    assertThat(balanceOnHand(balanceOf(b1))).isEqualByComparingTo("160");
  }

  /**
   * 🔴 确定性证明：行的顺序由**行号**决定（= 加行顺序），与随机 UUID 的大小无关。
   *
   * <p>旧写法按 {@code order by l.id} 排序，而 id 是随机 uuid(v4) ⇒ 同一张单的两行谁在前
   * 每次都可能不一样（约 50% 概率假红；2026-10-21 集成头全量 verify 红的就是这条）。
   *
   * <p>这里把「必翻车的现场」**真实构造**出来，而不是碰运气等它炸：
   * 把两行的 id 改成与加行顺序**相反**的两个定值 UUID ⇒ 任何还按 id 排序的读法
   * 都会把 A-02 排在 A-01 前面。于是：
   *
   * <ul>
   *   <li>旧写法（断言第 0 行是 A-01）在这个现场**必红**（用 {@code assertThatThrownBy} 钉死）；
   *   <li>新写法（按 line_no）**必须绿**，且顺序 = 加行顺序。
   * </ul>
   */
  @Test
  void 行的顺序是确定的_按加行顺序而不是随机UUID() throws Exception {
    var b1 = bucket(whOne, skuOne, locOne, lotOne, "100");
    var b2 = bucket(whOne, skuOne, locTwo, null, "60");

    var order =
        createOrder(
            body(
                "DAMAGE",
                List.of(
                    line(b1, "-3", bucketVersion(b1), null),
                    line(b2, "-2", bucketVersion(b2), null))),
            "Bearer tenant-a");
    var orderId = UUID.fromString((String) order.get("id"));

    // DB 层的真相：行号按加行顺序分配（A-01 是第 1 行，A-02 是第 2 行）。
    assertThat(lineNoByLocation(orderId))
        .as("行号 = 加行顺序：同一张单内从 1 递增")
        .containsExactly("A-01:1", "A-02:2");

    // ---- 现场：把两行的 UUID 改成与加行顺序相反 --------------------------------
    reverseLineIds(orderId);

    // 旧写法（按 id 排序）在这个现场里**必红**：id 顺序把 A-02 排在前面。
    var byId = locationsById(orderId);
    assertThat(byId).as("现场确实造出来了：按 id 排序与加行顺序相反").containsExactly("A-02", "A-01");
    assertThatThrownBy(() -> assertThat(byId.get(0)).isEqualTo("A-01"))
        .as("旧写法（按随机 UUID 排序 ⇒ 断言第 0 行是 A-01）必红")
        .isInstanceOf(AssertionError.class);

    // 新写法：顺序由行号决定 ⇒ 与加行顺序一致，与 UUID 大小无关。
    var lines = detailLines(orderId);
    assertThat(lines).hasSize(2);
    assertThat(lines.get(0))
        .as("第 1 行仍然是加行时提交的第 1 件（A-01）")
        .containsEntry("skuCode", "SKU-1")
        .containsEntry("locationCode", "A-01")
        .containsEntry("lotCode", "LOT-A");
    assertThat(lines.get(1))
        .as("第 2 行仍然是加行时提交的第 2 件（A-02）")
        .containsEntry("skuCode", "SKU-1")
        .containsEntry("locationCode", "A-02")
        .containsEntry("lotCode", "");
  }

  @Test
  void 零行的调整单被拒绝_否则台账上看起来像处理过了() throws Exception {
    var reason =
        serviceFailureReason(
            () -> service.create(createInput("DAMAGE", List.of()), user, "Bearer tenant-a"));
    assertThat(reason).contains("至少要有一行");
    var result =
        postJson("/warehouse/inventory/adjustments", body("DAMAGE", List.of()), "Bearer tenant-a");
    assertThat(result.getResponse().getStatus()).isEqualTo(400);
  }

  @Test
  void 原因码是受控值_自由文本被拒() {
    var b1 = bucket(whOne, skuOne, locOne, lotOne, "100");
    // 自由文本当原因码：「损坏」「破损」「DAMAGE」三种写法会让「本月损坏调了多少」答不出来
    var reason =
        serviceFailureReason(
            () ->
                service.create(
                    createInput("损坏", List.of(lineInput(b1, "-1", bucketVersion(b1), null))),
                    user,
                    "Bearer tenant-a"));
    assertThat(reason).contains("未知的调整原因码");
  }

  @Test
  void 平台仓差异这个原因码不可用_建行被显式拒绝而不是静默冲掉() {
    var b1 = bucket(whOne, skuOne, locOne, lotOne, "100");
    var ex =
        serviceFailure(
            () ->
                service.create(
                    createInput(
                        "PLATFORM_VARIANCE", List.of(lineInput(b1, "-5", bucketVersion(b1), null))),
                    user,
                    "Bearer tenant-a"));
    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    // 拒绝的理由必须讲清楚「为什么不能用」，而不是只说「不允许」
    assertThat(ex.getReason()).contains("平台仓").contains("观测值");
    // 一行都没建：被拒的单据不能留下半个残骸
    assertThat(countOrders()).isZero();
  }

  @Test
  void 盘盈必须是正数_盘亏必须是负数() {
    var b1 = bucket(whOne, skuOne, locOne, lotOne, "100");
    assertThat(
            serviceFailureReason(
                () ->
                    service.create(
                        createInput("OVERAGE", List.of(lineInput(b1, "-3", bucketVersion(b1), null))),
                        user,
                        "Bearer tenant-a")))
        .contains("盘盈");
    assertThat(
            serviceFailureReason(
                () ->
                    service.create(
                        createInput("SHORTAGE", List.of(lineInput(b1, "3", bucketVersion(b1), null))),
                        user,
                        "Bearer tenant-a")))
        .contains("盘亏");
  }

  @Test
  void 数量超过四位小数被拒而不是被静默四舍五入() {
    var b1 = bucket(whOne, skuOne, locOne, lotOne, "100");
    var reason =
        serviceFailureReason(
            () ->
                service.create(
                    createInput("DAMAGE", List.of(lineInput(b1, "-1.00005", bucketVersion(b1), null))),
                    user,
                    "Bearer tenant-a"));
    assertThat(reason).contains("4 位小数");
  }

  @Test
  void 乐观锁_库位明细变动后建单被拒_而不是拿旧数字改新余额() throws Exception {
    var b1 = bucket(whOne, skuOne, locOne, lotOne, "100");
    long stale = bucketVersion(b1);
    // 期间有人正常出库，bucket 的 version 前进（balance 与 bucket 同一事务里对上，
    // 否则 V2 的守恒触发器会当场拒绝）
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "update stock_bucket set on_hand = on_hand - 5::numeric, last_movement_at = ?::timestamptz"
                  + " where tenant_id = ? and id = ?",
              now(),
              a,
              b1);
          sql.execute(
              "update inventory_balance set on_hand = on_hand - 5::numeric where tenant_id = ? and id = ?",
              a,
              balanceOf(b1));
          return null;
        });
    assertThat(bucketVersion(b1)).isGreaterThan(stale);

    var reason =
        serviceFailureReason(
            () ->
                service.create(
                    createInput("SHORTAGE", List.of(lineInput(b1, "-3", stale, null))),
                    user,
                    "Bearer tenant-a"));
    assertThat(reason).contains("版本");
  }

  @Test
  void 缺expected_version被拒_没有它就没法判断看到的是不是过期数量() {
    var b1 = bucket(whOne, skuOne, locOne, lotOne, "100");
    var reason =
        serviceFailureReason(
            () ->
                service.create(
                    createInput("DAMAGE", List.of(lineInput(b1, "-1", null, null))),
                    user,
                    "Bearer tenant-a"));
    assertThat(reason).contains("expected_version");
  }

  // ================================================================ 凭证

  @Test
  void 凭证是逻辑引用_挂上去能读回来且不建跨库外键() throws Exception {
    var b1 = bucket(whOne, skuOne, locOne, lotOne, "100");
    var order =
        createOrder(
            body("DAMAGE", List.of(line(b1, "-1", bucketVersion(b1), null))), "Bearer tenant-a");
    var orderId = UUID.fromString((String) order.get("id"));

    UUID mediaId = UUID.randomUUID();
    var attachment = new java.util.LinkedHashMap<String, Object>();
    attachment.put("mediaId", mediaId.toString());
    attachment.put("fileName", "破损照片.jpg");
    attachment.put("mediaType", "image/jpeg");
    attachment.put("sizeBytes", 20480L);
    var result =
        postJson(
            "/warehouse/inventory/adjustments/" + orderId + "/attachments", attachment, "Bearer tenant-a");
    assertThat(result.getResponse().getStatus()).isEqualTo(200);

    @SuppressWarnings("unchecked")
    var attachments = (List<Map<String, Object>>) JSON
        .readValue(result.getResponse().getContentAsString(), Map.class)
        .get("attachments");
    assertThat(attachments).hasSize(1);
    assertThat(attachments.get(0)).containsEntry("mediaId", mediaId.toString());
    assertThat(attachments.get(0)).containsEntry("fileName", "破损照片.jpg");
    // 挂载时刻由服务端写：凭证什么时候挂上去必须能查
    assertThat(attachments.get(0)).containsKey("mountedAt");
  }

  @Test
  void 草稿能删行_已提交的行是证据不许删() throws Exception {
    var b1 = bucket(whOne, skuOne, locOne, lotOne, "100");
    var b2 = bucket(whOne, skuOne, locTwo, null, "60");
    var order =
        createOrder(
            body(
                "DAMAGE",
                List.of(
                    line(b1, "-3", bucketVersion(b1), null),
                    line(b2, "-2", bucketVersion(b2), null))),
            "Bearer tenant-a");
    var orderId = UUID.fromString((String) order.get("id"));
    @SuppressWarnings("unchecked")
    var lines = (List<Map<String, Object>>) order.get("lines");
    var lineId = UUID.fromString((String) lines.get(0).get("id"));

    // 草稿：可以摘掉一行
    var removed =
        postJson(
            "/warehouse/inventory/adjustments/" + orderId + "/lines/" + lineId + "/remove",
            Map.of(),
            "Bearer tenant-a");
    assertThat(removed.getResponse().getStatus()).isEqualTo(200);
    @SuppressWarnings("unchecked")
    var afterRemove = (List<Map<String, Object>>) JSON
        .readValue(removed.getResponse().getContentAsString(), Map.class)
        .get("lines");
    assertThat(afterRemove).hasSize(1);

    // 提交之后：同样的动作被拒
    var submitted =
        postJson(
            "/warehouse/inventory/adjustments/" + orderId + "/submit", Map.of(), "Bearer tenant-a");
    assertThat(submitted.getResponse().getStatus()).isEqualTo(200);
    var blocked =
        postJson(
            "/warehouse/inventory/adjustments/" + orderId + "/lines/" + lineId + "/remove",
            Map.of(),
            "Bearer tenant-a");
    assertThat(blocked.getResponse().getStatus()).isEqualTo(409);
  }

  // ================================================================ 审批与入账

  @Test
  void 未审批的调整单不能过账() throws Exception {
    var b1 = bucket(whOne, skuOne, locOne, lotOne, "100");
    var order =
        createOrder(
            body("SHORTAGE", List.of(line(b1, "-3", bucketVersion(b1), null))), "Bearer tenant-a");
    var orderId = UUID.fromString((String) order.get("id"));

    var reason =
        serviceFailureReason(
            () ->
                service.post(
                    orderId, new InventoryAdjustmentModels.InventoryAdjustmentNoteInput(null), user));
    assertThat(reason).contains("只有**已批待入账**");
    // HTTP 层同样是 409
    var result =
        postJson("/warehouse/inventory/adjustments/" + orderId + "/post", Map.of(), "Bearer tenant-a");
    assertThat(result.getResponse().getStatus()).isEqualTo(409);
    // 余额纹丝不动
    assertThat(balanceOnHand(balanceOf(b1))).isEqualByComparingTo("100");
  }

  @Test
  void 未审批的调整单在数据库层写不进任何分录() throws Exception {
    var b1 = bucket(whOne, skuOne, locOne, lotOne, "100");
    var order =
        createOrder(
            body("SHORTAGE", List.of(line(b1, "-3", bucketVersion(b1), null))), "Bearer tenant-a");
    var orderId = UUID.fromString((String) order.get("id"));

    UUID txId = UUID.randomUUID();
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,"
                  + "actor_id,notes,created_at) "
                  + "values(?,?,'ADJUSTMENT','INVENTORY_ADJUSTMENT',?,?::timestamptz,?,'',?::timestamptz)",
              txId,
              a,
              orderId,
              now(),
              user,
              now());
          return null;
        });

    // 绕过服务直接硬写分录：数据库的入账闸门必须当场拒绝
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> {
                      sql.execute(
                          "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,"
                              + "on_hand_delta,reserved_delta) values(?,?,?,?,?,0::numeric)",
                          UUID.randomUUID(),
                          a,
                          txId,
                          balanceOf(b1),
                          new BigDecimal("-3"));
                      return null;
                    }))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("尚未审批通过");
  }

  @Test
  void 入账数量守恒_数据库拒绝少记() throws Exception {
    var b1 = bucket(whOne, skuOne, locOne, lotOne, "100");
    var b2 = bucket(whOne, skuOne, locTwo, null, "60");
    var order =
        createOrder(
            body(
                "SHORTAGE",
                List.of(
                    line(b1, "-3", bucketVersion(b1), null),
                    line(b2, "-2", bucketVersion(b2), null))),
            "Bearer tenant-a");
    var orderId = UUID.fromString((String) order.get("id"));
    // 走完审批
    postJson("/warehouse/inventory/adjustments/" + orderId + "/submit", Map.of(), "Bearer tenant-a");
    postJson(
        "/warehouse/inventory/adjustments/" + orderId + "/approve",
        Map.of("note", "同意，按现场盘点调整"),
        "Bearer approver");

    UUID txId = UUID.randomUUID();
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,"
                  + "actor_id,notes,created_at) "
                  + "values(?,?,'ADJUSTMENT','INVENTORY_ADJUSTMENT',?,?::timestamptz,?,'',?::timestamptz)",
              txId,
              a,
              orderId,
              now(),
              user,
              now());
          return null;
        });
    // 只记了第一行：Σ分录(-3) ≠ Σ行(-5)
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> {
                      sql.execute(
                          "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,"
                              + "on_hand_delta,reserved_delta) values(?,?,?,?,?,0::numeric)",
                          UUID.randomUUID(),
                          a,
                          txId,
                          balanceOf(b1),
                          new BigDecimal("-3"));
                      return null;
                    }))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("入账数量不平");
  }

  @Test
  void 提交人不能批自己提的单() throws Exception {
    var b1 = bucket(whOne, skuOne, locOne, lotOne, "100");
    var order =
        createOrder(
            body("SHORTAGE", List.of(line(b1, "-3", bucketVersion(b1), null))), "Bearer tenant-a");
    var orderId = UUID.fromString((String) order.get("id"));
    postJson("/warehouse/inventory/adjustments/" + orderId + "/submit", Map.of(), "Bearer tenant-a");

    var reason =
        serviceFailureReason(
            () ->
                service.approve(
                    orderId, new InventoryAdjustmentModels.InventoryAdjustmentNoteInput("自己批自己"), user));
    assertThat(reason).contains("提交人不能批自己提的单");
  }

  @Test
  void 批准必须写理由() throws Exception {
    var b1 = bucket(whOne, skuOne, locOne, lotOne, "100");
    var order =
        createOrder(
            body("SHORTAGE", List.of(line(b1, "-3", bucketVersion(b1), null))), "Bearer tenant-a");
    var orderId = UUID.fromString((String) order.get("id"));
    postJson("/warehouse/inventory/adjustments/" + orderId + "/submit", Map.of(), "Bearer tenant-a");

    var reason =
        serviceFailureReason(
            () ->
                service.approve(
                    orderId, new InventoryAdjustmentModels.InventoryAdjustmentNoteInput(null), approver));
    assertThat(reason).contains("批准必须写理由");
  }

  @Test
  void 过账后余额与库位明细都改了_分录落进流水台账() throws Exception {
    var b1 = bucket(whOne, skuOne, locOne, lotOne, "100");
    var b2 = bucket(whOne, skuOne, locTwo, null, "60");
    var order =
        createOrder(
            body(
                "SHORTAGE",
                List.of(
                    line(b1, "-3", bucketVersion(b1), null),
                    line(b2, "5", bucketVersion(b2), "OVERAGE"))),
            "Bearer tenant-a");
    var orderId = UUID.fromString((String) order.get("id"));
    postJson("/warehouse/inventory/adjustments/" + orderId + "/submit", Map.of(), "Bearer tenant-a");
    postJson(
        "/warehouse/inventory/adjustments/" + orderId + "/approve",
        Map.of("note", "现场核实无误"),
        "Bearer approver");

    var posted =
        postJson(
            "/warehouse/inventory/adjustments/" + orderId + "/post",
            Map.of("note", "过账"),
            "Bearer tenant-a");
    assertThat(posted.getResponse().getStatus()).isEqualTo(200);
    @SuppressWarnings("unchecked")
    var result = JSON.readValue(posted.getResponse().getContentAsString(), Map.class);
    assertThat(intOf(result.get("entryCount"))).isEqualTo(2);
    assertThat(result.get("transactionId")).isNotNull();

    // 余额与库位明细都改了
    assertThat(bucketOnHand(b1)).isEqualByComparingTo("97");
    assertThat(bucketOnHand(b2)).isEqualByComparingTo("65");
    // 同一批货共享一条 balance：160 − 3 + 5
    assertThat(balanceOnHand(balanceOf(b1))).isEqualByComparingTo("162");

    // 分录确实写进了 inventory_entry（append-only 的那一张）
    assertThat(entryCount(orderId)).isEqualTo(2);
    // 7.1.2.1 流水台账立刻可见 —— 本模块**不另建调整流水表**
    var ledger =
        http.perform(
                get("/warehouse/inventory/ledger")
                    .header("Authorization", "Bearer tenant-a")
                    .param("refTypes", "INVENTORY_ADJUSTMENT"))
            .andExpect(status().isOk())
            .andReturn();
    @SuppressWarnings("unchecked")
    var ledgerBody = JSON.readValue(ledger.getResponse().getContentAsString(), Map.class);
    assertThat(intOf(ledgerBody.get("total"))).isEqualTo(1);
    @SuppressWarnings("unchecked")
    var rows = (List<Map<String, Object>>) ledgerBody.get("rows");
    assertThat(rows.get(0)).containsEntry("refType", "INVENTORY_ADJUSTMENT");
    assertThat(rows.get(0)).containsEntry("refId", orderId.toString());
    assertThat(numOf(rows.get(0).get("onHandDelta"))).isEqualByComparingTo("2");
  }

  @Test
  void 过账时间写回零漂移() throws Exception {
    var b1 = bucket(whOne, skuOne, locOne, lotOne, "100");
    var order =
        createOrder(
            body("SHORTAGE", List.of(line(b1, "-3", bucketVersion(b1), null))), "Bearer tenant-a");
    var orderId = UUID.fromString((String) order.get("id"));
    postJson("/warehouse/inventory/adjustments/" + orderId + "/submit", Map.of(), "Bearer tenant-a");
    postJson(
        "/warehouse/inventory/adjustments/" + orderId + "/approve",
        Map.of("note", "同意"),
        "Bearer approver");
    var posted =
        postJson(
            "/warehouse/inventory/adjustments/" + orderId + "/post", Map.of(), "Bearer tenant-a");
    assertThat(posted.getResponse().getStatus()).isEqualTo(200);

    // 直接查库核对写回的时刻，而不是只看「没抛异常」
    var row =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                sql.fetchOne(
                    "select o.posted_at, k.last_movement_at from inventory_adjustment_order o"
                        + " join inventory_adjustment_order_line l"
                        + "   on l.tenant_id = o.tenant_id and l.order_id = o.id"
                        + " join stock_bucket k"
                        + "   on k.tenant_id = l.tenant_id and k.id = l.bucket_id"
                        + " where o.tenant_id = ? and o.id = ?",
                    a,
                    orderId));
    OffsetDateTime postedAt = row.get("posted_at", OffsetDateTime.class);
    OffsetDateTime movementAt = row.get("last_movement_at", OffsetDateTime.class);
    assertThat(postedAt).isNotNull();
    // 业务时间必须真的写进去了，而不是停留在建表时的默认值
    assertThat(postedAt).isAfter(OffsetDateTime.now().minusHours(1));
    assertThat(movementAt).isNotNull();
    assertThat(movementAt).isAfter(OffsetDateTime.now().minusHours(1));
  }

  @Test
  void 减调超过在手被挡住_库存不许被做成负数() throws Exception {
    var b1 = bucket(whOne, skuOne, locOne, lotOne, "10");
    var order =
        createOrder(
            body("SHORTAGE", List.of(line(b1, "-30", bucketVersion(b1), null))), "Bearer tenant-a");
    var orderId = UUID.fromString((String) order.get("id"));
    postJson("/warehouse/inventory/adjustments/" + orderId + "/submit", Map.of(), "Bearer tenant-a");
    postJson(
        "/warehouse/inventory/adjustments/" + orderId + "/approve",
        Map.of("note", "同意"),
        "Bearer approver");
    var reason =
        serviceFailureReason(
            () ->
                service.post(
                    orderId, new InventoryAdjustmentModels.InventoryAdjustmentNoteInput(null), approver));
    assertThat(reason).contains("负数");
    // HTTP 层同样被挡
    var posted =
        postJson(
            "/warehouse/inventory/adjustments/" + orderId + "/post", Map.of(), "Bearer tenant-a");
    assertThat(posted.getResponse().getStatus()).isEqualTo(409);
    // 余额没被动过
    assertThat(balanceOnHand(balanceOf(b1))).isEqualByComparingTo("10");
  }

  @Test
  void 调整单不可删除_作废要留痕迹() throws Exception {
    var b1 = bucket(whOne, skuOne, locOne, lotOne, "100");
    var order =
        createOrder(
            body("SHORTAGE", List.of(line(b1, "-3", bucketVersion(b1), null))), "Bearer tenant-a");
    var orderId = UUID.fromString((String) order.get("id"));
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> {
                      sql.execute(
                          "delete from inventory_adjustment_order where tenant_id = ? and id = ?",
                          a,
                          orderId);
                      return null;
                    }))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("不允许删除");
  }

  @Test
  void 审计时间线完整记下每一步() throws Exception {
    var b1 = bucket(whOne, skuOne, locOne, lotOne, "100");
    var order =
        createOrder(
            body("SHORTAGE", List.of(line(b1, "-3", bucketVersion(b1), null))), "Bearer tenant-a");
    var orderId = UUID.fromString((String) order.get("id"));
    postJson("/warehouse/inventory/adjustments/" + orderId + "/submit", Map.of(), "Bearer tenant-a");
    postJson(
        "/warehouse/inventory/adjustments/" + orderId + "/approve",
        Map.of("note", "现场核实"),
        "Bearer approver");
    postJson(
        "/warehouse/inventory/adjustments/" + orderId + "/post", Map.of(), "Bearer tenant-a");

    var detail =
        http.perform(
                get("/warehouse/inventory/adjustments/" + orderId)
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn();
    @SuppressWarnings("unchecked")
    var body = JSON.readValue(detail.getResponse().getContentAsString(), Map.class);
    @SuppressWarnings("unchecked")
    var audit = (List<Map<String, Object>>) body.get("auditTrail");
    assertThat(audit).extracting(x -> x.get("action")).containsExactly("CREATED", "SUBMITTED", "APPROVED", "POSTED");
    // 审批人 ≠ 提交人，两笔痕迹指向不同的人
    assertThat(audit.get(1).get("actorUserId")).isEqualTo(user.toString());
    assertThat(audit.get(2).get("actorUserId")).isEqualTo(approver.toString());
  }

  @Test
  void 能力端点如实报出不可用的部分() throws Exception {
    var result =
        http.perform(
                get("/warehouse/inventory/adjustments/capabilities")
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn();
    @SuppressWarnings("unchecked")
    var body = JSON.readValue(result.getResponse().getContentAsString(), Map.class);
    @SuppressWarnings("unchecked")
    var reasons = (List<Map<String, Object>>) body.get("reasons");
    assertThat(reasons).hasSize(6);
    var platform =
        reasons.stream().filter(x -> "PLATFORM_VARIANCE".equals(x.get("value"))).findFirst().orElseThrow();
    assertThat(platform.get("available")).isEqualTo(false);
    assertThat(platform.get("ownerModule")).asString().isNotEmpty();

    @SuppressWarnings("unchecked")
    var deps = (List<Map<String, Object>>) body.get("dependencies");
    var defective =
        deps.stream().filter(x -> "defectiveDisposition".equals(x.get("key"))).findFirst().orElseThrow();
    // 上游不可达必须报「不可用」而不是给空清单
    assertThat(defective.get("available")).isEqualTo(false);
    assertThat((String) defective.get("note")).contains("不可达");
  }

  // ================================================================ 工具

  UUID balanceOf(UUID bucketId) {
    return access.run(
        actorA,
        "warehouse.inventory.read",
        () ->
            sql.fetchOne(
                    "select balance_id from stock_bucket where tenant_id = ? and id = ?", a, bucketId)
                .get("balance_id", UUID.class));
  }

  BigDecimal bucketOnHand(UUID bucketId) {
    return access.run(
        actorA,
        "warehouse.inventory.read",
        () ->
            sql.fetchOne(
                    "select on_hand from stock_bucket where tenant_id = ? and id = ?", a, bucketId)
                .get("on_hand", BigDecimal.class));
  }

  long entryCount(UUID orderId) {
    return access.run(
        actorA,
        "warehouse.inventory.read",
        () ->
            sql.fetchOne(
                    "select count(*) from inventory_entry e"
                        + " join inventory_transaction tx on tx.tenant_id = e.tenant_id"
                        + "  and tx.id = e.transaction_id"
                        + " where e.tenant_id = ? and tx.ref_type = 'INVENTORY_ADJUSTMENT'"
                        + "   and tx.ref_id = ?",
                    a,
                    orderId)
                .get(0, Long.class));
  }

  long countOrders() {
    return access.run(
        actorA,
        "warehouse.inventory.read",
        () ->
            sql.fetchOne(
                    "select count(*) from inventory_adjustment_order where tenant_id = ?", a)
                .get(0, Long.class));
  }

  // ------------------------------------------------ 行的顺序（line_no，V261）

  /** 详情端点返回的明细行（服务层按 line_no 排序后的真实结果）。 */
  @SuppressWarnings("unchecked")
  List<Map<String, Object>> detailLines(UUID orderId) throws Exception {
    var result =
        http.perform(
                get("/warehouse/inventory/adjustments/" + orderId)
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn();
    Map<String, Object> body = JSON.readValue(result.getResponse().getContentAsString(), Map.class);
    return (List<Map<String, Object>>) body.get("lines");
  }

  /** DB 层的真相：库位 ← 行号（行号 = 加行顺序）。 */
  List<String> lineNoByLocation(UUID orderId) {
    return access.run(
        actorA,
        "warehouse.inventory.read",
        () ->
            sql.fetch(
                    "select line_no, location_code from inventory_adjustment_order_line"
                        + " where tenant_id = ? and order_id = ? order by line_no",
                    a,
                    orderId)
                .stream()
                .map(r -> r.get("location_code", String.class) + ":" + r.get("line_no", Integer.class))
                .toList());
  }

  /** 旧写法（{@code order by l.id}）会看到的顺序：按主键排。 */
  List<String> locationsById(UUID orderId) {
    return access.run(
        actorA,
        "warehouse.inventory.read",
        () ->
            sql.fetch(
                    "select location_code from inventory_adjustment_order_line"
                        + " where tenant_id = ? and order_id = ? order by id",
                    a,
                    orderId)
                .stream()
                .map(r -> r.get("location_code", String.class))
                .toList());
  }

  /**
   * 🔴 现场构造：把两行的主键改成与加行顺序**相反**的两个定值 UUID。
   *
   * <p>这样「按随机 UUID 排序」就一定把 A-02 排在前面 —— 旧写法必红，新写法必须绿。
   * 这是**真的构造**出来的翻车现场，不是碰运气等它炸。
   */
  void reverseLineIds(UUID orderId) {
    access.run(
        actorA,
        "warehouse.inventory.update",
        () ->
            sql.execute(
                "update inventory_adjustment_order_line set id = case line_no"
                    + " when 1 then 'ffffffff-ffff-4fff-bfff-ffffffffffff'::uuid"
                    + " else '00000000-0000-4000-8000-000000000000'::uuid end"
                    + " where tenant_id = ? and order_id = ?",
                a,
                orderId));
  }
}
