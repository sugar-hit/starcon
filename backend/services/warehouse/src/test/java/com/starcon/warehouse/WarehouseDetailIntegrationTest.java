package com.starcon.warehouse;

import static com.starcon.warehouse.WarehouseDetailModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 8.1.1.2 仓库详情。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接： 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>本测试钉住五件「错了会误导人」的事：
 *
 * <ul>
 *   <li><b>未交付 ≠ 0。</b>库位树、上架任务、拣货出库、组装、备货策略、人员花名册、设备 都没有数据表，必须报 {@code available=false} + {@code
 *       ownerModule} + 说明， 而不是算成 0 或返回空数组冒充「这个仓没有」。
 *   <li><b>平台仓观测值与自有账本不相加。</b>详情页是控制塔，汇总数字最容易被顺手加在一起； 这里从响应形状上钉住：{@code stock.onHand} 与 {@code
 *       stock.platform} 是两个字段。
 *   <li><b>地址缺失要报缺什么</b>，不是渲染成一行空白。
 *   <li><b>时区不是裸偏移</b>；归档是终态，不可回退。
 *   <li><b>租户隔离三断言</b>实跑 + 逐张断言 FORCE RLS。
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class WarehouseDetailIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      List.of(
          "warehouse.facilities.read",
          "warehouse.facilities.create",
          "warehouse.facilities.update");

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-detail-" + tenant + "-" + ordinal).getBytes());
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
            byte[] body =
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only",
          actor(user, a, List.of(grant("ALL", List.of("warehouse.facilities.read")))));
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
  }

  @Autowired WarehouseDetailService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actorA;
  AccessService.Access actorB;

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    // TRUNCATE 不触发行级触发器，以超级用户连接执行可绕过 FORCE RLS ——
    // 只用于测试夹具重建，不给应用角色开这个口子。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate warehouse_work_task, goods_receipt, in_transit_event, inventory_transfer,"
              + " stocktake_task, stocktake_sheet, transfer_order, inventory_entry,"
              + " inventory_transaction, stock_bucket, platform_stock_observation, warehouse_location,"
              + " inventory_lot, inventory_balance, inventory_quality_status, warehouse, sku_ref,"
              + " legal_entity_ref restart identity cascade");
    }
    for (var tenant : List.of(a, b)) {
      access.run(
          actorFor(tenant),
          "warehouse.facilities.read",
          () -> {
            sql.execute(
                "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 1),
                tenant,
                "LE-1",
                "主体一");
            sql.execute(
                "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 2),
                tenant,
                "LE-2",
                "主体二");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 3),
                tenant,
                "SKU-1",
                "商品一");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 4),
                tenant,
                "SKU-2",
                "商品二");
            // 两个质量状态：可售与待检。详情页的库存分布要按它们分行，
            // 只建一个就验不出「待检的货没有并进可售」这条纪律。
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 5),
                tenant,
                "SELLABLE",
                "可售",
                "SELLABLE");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 6),
                tenant,
                "PENDING_INSPECTION",
                "待检",
                "PENDING_INSPECTION");
            // B 的仓库刻意用与 A **不同的编码与国家**：若隔离失效，
            // 「B 看到了 A 的仓」会表现为结果里混进另一个国家，断言不会含糊。
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,kind,country_code,timezone,"
                    + "default_legal_entity_id,status) values(?,?,?,?,?,?,?,?,?)",
                idFor(tenant, 7),
                tenant,
                "WH-" + (tenant.equals(a) ? "A" : "B"),
                "本租仓库",
                "LOCAL",
                tenant.equals(a) ? "CN" : "US",
                "Asia/Shanghai",
                idFor(tenant, 1),
                "ACTIVE");
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

  <T> T asA(String permission, java.util.function.Supplier<T> work) {
    return access.run(actorA, "warehouse.facilities." + permission, work);
  }

  <T> T asB(String permission, java.util.function.Supplier<T> work) {
    return access.run(actorB, "warehouse.facilities." + permission, work);
  }

  WarehouseDetail detail(UUID warehouseId) {
    return asA("read", () -> service.detail(warehouseId));
  }

  // ================================================================ 夹具

  UUID whOf(UUID tenant) {
    return idFor(tenant, 7);
  }

  UUID leOne(UUID tenant) {
    return idFor(tenant, 1);
  }

  UUID qSellable(UUID tenant) {
    return idFor(tenant, 5);
  }

  UUID qPending(UUID tenant) {
    return idFor(tenant, 6);
  }

  /** 本租户的第二个仓库：在途腿的 from/to 都在同租户名下（复合外键强制这一点）。 */
  UUID otherWarehouseA() {
    return asA(
        "read",
        () -> {
          UUID id = UUID.randomUUID();
          sql.execute(
              "insert into warehouse(id,tenant_id,code,name,kind,country_code,timezone,"
                  + "default_legal_entity_id,status) values(?,?,?,?,'LOCAL','CN','Asia/Shanghai',?,'ACTIVE')",
              id,
              a,
              "WH-A2-" + id.toString().substring(0, 6),
              "本租第二仓",
              leOne(a));
          return id;
        });
  }

  UUID warehouse(
      UUID tenant,
      int ordinal,
      String code,
      String kind,
      String country,
      String tz,
      UUID legalEntity,
      String status) {
    return access.run(
        actorFor(tenant),
        "warehouse.facilities.read",
        () -> {
          UUID id = idFor(tenant, ordinal);
          sql.execute(
              "insert into warehouse(id,tenant_id,code,name,kind,country_code,timezone,"
                  + "default_legal_entity_id,status) values(?,?,?,?,?,?,?,?,?)",
              id,
              tenant,
              code,
              code + " 仓",
              kind,
              country,
              tz,
              legalEntity,
              status);
          return id;
        });
  }

  /** 一个库位。type / status / pickable 三列是 V2 建表时就有的真实属性。 */
  UUID location(
      UUID tenant,
      int ordinal,
      UUID warehouse,
      String code,
      String type,
      String status,
      boolean pickable) {
    return access.run(
        actorFor(tenant),
        "warehouse.facilities.read",
        () -> {
          UUID id = idFor(tenant, ordinal);
          sql.execute(
              "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,status,pickable)"
                  + " values(?,?,?,?,?,?,?,?)",
              id,
              tenant,
              warehouse,
              code,
              code + " 位",
              type,
              status,
              pickable);
          return id;
        });
  }

  /**
   * 一条余额 + 一条库位明细（库存分布与库位结构两个面板的共同来源）。
   *
   * @param movedAt 业务变动时间，必须以 {@code ?::timestamptz} 绑定 —— {@code Timestamp.valueOf(LocalDateTime)}
   *     会在东八区静默偏 −28800s
   */
  void stock(
      UUID tenant,
      UUID warehouse,
      UUID location,
      UUID sku,
      UUID quality,
      String quantity,
      OffsetDateTime movedAt) {
    access.run(
        actorFor(tenant),
        "warehouse.facilities.read",
        () -> {
          UUID balanceId = UUID.randomUUID();
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
              balanceId,
              tenant,
              sku,
              warehouse,
              idFor(tenant, 1),
              quality,
              new BigDecimal(quantity));
          sql.execute(
              "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,location_id,"
                  + "quality_status_id,balance_id,on_hand,reserved,last_movement_at) "
                  + "values(?,?,?,?,?,?,?,?,?,0::numeric,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              idFor(tenant, 1),
              sku,
              warehouse,
              location,
              quality,
              balanceId,
              new BigDecimal(quantity),
              movedAt);
          return null;
        });
  }

  /** 一张收货单（V70 已交付）。status 走 V70 的四值词表。 */
  UUID receipt(UUID tenant, UUID warehouse, String status, String receivedQuantity) {
    return access.run(
        actorFor(tenant),
        "warehouse.facilities.read",
        () -> {
          UUID id = UUID.randomUUID();
          sql.execute(
              "insert into goods_receipt(id,tenant_id,receipt_code,source_type,source_ref_id,"
                  + "source_ref_code,warehouse_id,legal_entity_id,status,created_by,"
                  + "closed_at,closed_by,close_reason) values(?,?,?,'TRANSFER',?,?,?,?,?,?,"
                  + "?::timestamptz,?,?)",
              id,
              tenant,
              "GR-" + id.toString().substring(0, 8),
              UUID.randomUUID(),
              "TR-REF-1",
              warehouse,
              idFor(tenant, 1),
              status,
              user,
              "CLOSED".equals(status) ? now() : null,
              "CLOSED".equals(status) ? user : null,
              "CLOSED".equals(status) ? "夹具关单" : "");
          sql.execute(
              "insert into goods_receipt_line(id,tenant_id,receipt_id,sku_id,quality_status_id,"
                  + "expected_quantity,received_quantity) values(?,?,?,?,?,?,?)",
              UUID.randomUUID(),
              tenant,
              id,
              idFor(tenant, 3),
              qSellable(tenant),
              new BigDecimal(receivedQuantity),
              new BigDecimal(receivedQuantity));
          return id;
        });
  }

  /** 一个作业任务（V74 / 8.5.1.1）。status 决定要填哪些时间戳与派工列。 */
  UUID workTask(UUID tenant, UUID warehouse, String workType, String status, UUID operator) {
    return access.run(
        actorFor(tenant),
        "warehouse.facilities.read",
        () -> {
          UUID id = UUID.randomUUID();
          boolean dispatched = operator != null;
          boolean started = !"PENDING".equals(status);
          boolean finished = "COMPLETED".equals(status) || "EXCEPTION".equals(status);
          sql.execute(
              "insert into warehouse_work_task(id,tenant_id,code,work_type,source_module,warehouse_id,"
                  + "operator_user_id,assigned_at,assigned_by_user_id,status,started_at,completed_at,"
                  + "exception_code) values(?,?,?,?,?,?,?,?::timestamptz,?,?,"
                  + "?::timestamptz,?::timestamptz,?)",
              id,
              tenant,
              "WT-" + id.toString().substring(0, 8),
              workType,
              "8.5.1.1",
              warehouse,
              operator,
              dispatched ? now() : null,
              dispatched ? user : null,
              status,
              started ? now() : null,
              finished ? now() : null,
              "EXCEPTION".equals(status) ? "PICK_MISMATCH" : null);
          return id;
        });
  }

  UUID stocktake(String scopeType, UUID scopeKey, String status) {
    return asA(
        "read",
        () -> {
          UUID id = UUID.randomUUID();
          sql.execute(
              "insert into stocktake_sheet(id,tenant_id,code,scope_type,scope_key,scope_label,"
                  + "task_unit,frozen_at,status,created_by_user_id) "
                  + "values(?,?,?,?,?,?,'BY_LOCATION',?::timestamptz,?,?)",
              id,
              a,
              "ST-" + id.toString().substring(0, 8),
              scopeType,
              scopeKey,
              scopeType + " 范围",
              now(),
              status,
              user);
          sql.execute(
              "insert into stocktake_task(id,tenant_id,sheet_id,task_unit,task_key,task_code,task_name) "
                  + "values(?,?,?,'BY_LOCATION',?,'A-01','A 区一排')",
              UUID.randomUUID(),
              a,
              id,
              scopeKey);
          return id;
        });
  }

  UUID transfer(UUID from, UUID to, String status) {
    return asA(
        "read",
        () -> {
          UUID id = UUID.randomUUID();
          boolean draft = "DRAFT".equals(status);
          boolean shipped = !draft && !"PENDING_SHIPMENT".equals(status);
          boolean terminal = "RECEIVED".equals(status) || "DISCREPANCY".equals(status);
          sql.execute(
              "insert into transfer_order(id,tenant_id,order_code,from_warehouse_id,to_warehouse_id,"
                  + "legal_entity_id,status,created_by,confirmed_at,shipped_at,completed_at) "
                  + "values(?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz)",
              id,
              a,
              "TR-" + id.toString().substring(0, 8),
              from,
              to,
              leOne(a),
              status,
              user,
              draft ? null : now(),
              shipped ? now() : null,
              terminal ? now() : null);
          return id;
        });
  }

  /** 一条在途腿 + 一条到达事件。事件量必须与已了结量对上，否则延迟约束触发器会拒。 */
  UUID transitLeg(UUID from, UUID to, String quantity) {
    return asA(
        "read",
        () -> {
          UUID id = UUID.randomUUID();
          sql.execute(
              "insert into inventory_transfer(id,tenant_id,sku_id,legal_entity_id,"
                  + "from_warehouse_id,to_warehouse_id,quality_status_id,quantity,status,"
                  + "shipped_at,settled_quantity) values(?,?,?,?,?,?,?,?, 'IN_TRANSIT',now(),?)",
              id,
              a,
              idFor(a, 3),
              leOne(a),
              from,
              to,
              qSellable(a),
              new BigDecimal(quantity),
              new BigDecimal(quantity));
          sql.execute(
              "insert into in_transit_event(id,tenant_id,leg_id,kind,occurred_at,quantity,actor_id) "
                  + "values(?,?,?,'ARRIVED',?::timestamptz,?,?)",
              UUID.randomUUID(),
              a,
              id,
              now(),
              new BigDecimal(quantity),
              user);
          return id;
        });
  }

  void observation(
      UUID warehouse, UUID sku, String source, String onHand, OffsetDateTime collectedAt) {
    asA(
        "read",
        () -> {
          sql.execute(
              "insert into platform_stock_observation(id,tenant_id,warehouse_id,sku_id,legal_entity_id,"
                  + "source,collected_at,observed_on_hand,observed_inbound) "
                  + "values(?,?,?,?,?,?,?::timestamptz,?,?)",
              UUID.randomUUID(),
              a,
              warehouse,
              sku,
              leOne(a),
              source,
              collectedAt,
              new BigDecimal(onHand),
              new BigDecimal("0"));
          return null;
        });
  }

  // ================================================================ 基础信息与地址

  @Test
  void 详情返回基础信息并带上当地时间() {
    UUID wh = whOf(a);
    var d = detail(wh);
    assertThat(d.base().id()).isEqualTo(wh);
    assertThat(d.base().code()).isEqualTo("WH-A");
    assertThat(d.base().kind()).isEqualTo(WarehouseFacilityModels.WarehouseKind.LOCAL);
    assertThat(d.base().countryCode()).isEqualTo("CN");
    assertThat(d.base().timezone()).isEqualTo("Asia/Shanghai");
    assertThat(d.base().statusLabel()).isEqualTo("启用中");
    // 当地时间按仓库时区算，写死 UTC 的实现会在这里露馅。
    var expected =
        ZonedDateTime.ofInstant(java.time.Instant.now(), ZoneId.of("Asia/Shanghai"))
            .toLocalDateTime()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    assertThat(d.base().localTime()).isEqualTo(expected);
  }

  @Test
  void 库里时区无法识别时当地时间报空而不是退化成UTC() {
    UUID wh = warehouse(a, 10, "WH-BADTZ", "LOCAL", "CN", "Not/AZone", leOne(a), "ACTIVE");
    var d = detail(wh);
    assertThat(d.base().timezone()).isEqualTo("Not/AZone");
    assertThat(d.base().localTime()).isNull();
  }

  @Test
  void 地址写入后读回且拼成一行() {
    UUID wh = whOf(a);
    asA(
        "update",
        () ->
            service.update(
                wh,
                new WarehouseUpdateInput(
                    "本租仓库",
                    "CN",
                    "Asia/Shanghai",
                    "浦东新区世纪大道 100 号",
                    "A 座 12 层",
                    "上海",
                    "上海",
                    "200120",
                    null)));
    var d = detail(wh);
    assertThat(d.address().line1()).isEqualTo("浦东新区世纪大道 100 号");
    assertThat(d.address().line2()).isEqualTo("A 座 12 层");
    assertThat(d.address().city()).isEqualTo("上海");
    assertThat(d.address().postalCode()).isEqualTo("200120");
    assertThat(d.address().text()).isEqualTo("浦东新区世纪大道 100 号 A 座 12 层 上海 上海 200120 CN");
    assertThat(d.address().missingFields()).isEmpty();
  }

  @Test
  void 地址缺失时如实报缺哪几个而不是渲染成空白() {
    UUID wh = whOf(a);
    var d = detail(wh);
    // 新仓没有地址：这与「填了地址但没填邮编」必须能一眼分开。
    assertThat(d.address().missingFields()).containsExactly("街道地址", "城市", "省/州", "邮编");
    assertThat(d.address().text()).isEqualTo("CN");

    asA(
        "update",
        () ->
            service.update(
                wh,
                new WarehouseUpdateInput(
                    "本租仓库", "CN", "Asia/Shanghai", "某路 1 号", "  ", "深圳", null, null, null)));
    var after = detail(wh);
    // 只缺省/州与邮编，街道与城市已填 —— missingFields 是差集不是全量
    assertThat(after.address().missingFields()).containsExactly("省/州", "邮编");
  }

  @Test
  void 空白地址归一成空而不是留下空格() {
    UUID wh = whOf(a);
    asA(
        "update",
        () ->
            service.update(
                wh,
                new WarehouseUpdateInput(
                    "本租仓库", "CN", "Asia/Shanghai", "   ", "  ", "  ", "  ", "  ", null)));
    var d = detail(wh);
    assertThat(d.address().line1()).isNull();
    assertThat(d.address().city()).isNull();
    // 「   」被归一成 null，所以 DB 层的非空白 CHECK 不会拦下来；
    // 这条断言钉住的是**归一发生在应用层**，而不是靠 DB 报错兜底。
    assertThat(d.address().missingFields()).contains("街道地址");
  }

  // ================================================================ 库存分布

  @Test
  void 库存分布按质量状态分行且待检不并进可售() {
    UUID wh = whOf(a);
    UUID loc = location(a, 11, wh, "A-01", "STORAGE", "ACTIVE", true);
    stock(a, wh, loc, idFor(a, 3), qSellable(a), "100", now());
    stock(a, wh, loc, idFor(a, 4), qPending(a), "30", now());

    var d = detail(wh);
    assertThat(d.stock().onHand()).isEqualByComparingTo("130");
    assertThat(d.stock().available()).isEqualByComparingTo("130");
    assertThat(d.stock().skuCount()).isEqualTo(2);
    var sellable =
        d.stock().byQuality().stream()
            .filter(q -> q.role().equals("SELLABLE"))
            .findFirst()
            .orElseThrow();
    var pending =
        d.stock().byQuality().stream()
            .filter(q -> q.role().equals("PENDING_INSPECTION"))
            .findFirst()
            .orElseThrow();
    // 两个质量状态是两个数：把待检并进可售会让运营以为这 130 件都能发
    assertThat(sellable.onHand()).isEqualByComparingTo("100");
    assertThat(pending.onHand()).isEqualByComparingTo("30");
  }

  @Test
  void 平台仓观测值与自有账本不相加() {
    UUID wh =
        warehouse(a, 12, "WH-FBA", "PLATFORM_FBA", "US", "America/Los_Angeles", leOne(a), "ACTIVE");
    observation(wh, idFor(a, 3), "AMAZON_FBA", "300", now().minusDays(2));
    observation(wh, idFor(a, 3), "AMAZON_FBA", "310", now());

    var d = detail(wh);
    // 自有账本恒为 0：V55 的触发器禁止平台仓记自有账本。
    assertThat(d.stock().onHand()).isEqualByComparingTo("0");
    assertThat(d.stock().platform()).isNotNull();
    // 只取最新一条（append-only 历史序列累加会得到 610 这个不存在的数）
    assertThat(d.stock().platform().observedOnHand()).isEqualByComparingTo("310");
    assertThat(d.stock().platform().latestCollectedAt()).isNotNull();
  }

  @Test
  void 自有仓不带平台观测字段() {
    // 不是「观测值为 0」，而是「这个来源不存在」—— 两者在界面上含义完全不同。
    assertThat(detail(whOf(a)).stock().platform()).isNull();
  }

  @Test
  void 库存分布按货权法人分行() {
    UUID wh = whOf(a);
    UUID loc = location(a, 11, wh, "A-01", "STORAGE", "ACTIVE", true);
    stock(a, wh, loc, idFor(a, 3), qSellable(a), "10", now());
    // 同一个仓持有第二个法人的货是合法的，详情页要能按法人拆开看
    asA(
        "read",
        () -> {
          UUID balanceId = UUID.randomUUID();
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
              balanceId,
              a,
              idFor(a, 4),
              wh,
              idFor(a, 2),
              qSellable(a),
              new BigDecimal("7"));
          sql.execute(
              "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,location_id,"
                  + "quality_status_id,balance_id,on_hand,reserved,last_movement_at) "
                  + "values(?,?,?,?,?,?,?,?,?,0::numeric,?::timestamptz)",
              UUID.randomUUID(),
              a,
              idFor(a, 2),
              idFor(a, 4),
              wh,
              loc,
              qSellable(a),
              balanceId,
              new BigDecimal("7"),
              now());
          return null;
        });
    var d = detail(wh);
    assertThat(d.stock().byLegalEntity()).hasSize(2);
    assertThat(d.stock().byLegalEntity().stream().map(LegalEntitySlice::code))
        .containsExactly("LE-1", "LE-2");
    // 按法人分行之和必须等于总量 —— 合计与明细不是两个真相来源
    assertThat(
            d.stock().byLegalEntity().stream()
                .map(LegalEntitySlice::onHand)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo(d.stock().onHand());
    assertThat(d.base().legalEntityCount()).isEqualTo(2);
  }

  @Test
  void 数量按NUMERIC读回不经浮点() {
    UUID wh = whOf(a);
    UUID loc = location(a, 11, wh, "A-01", "STORAGE", "ACTIVE", true);
    // 0.1 三次相加在 double 里是 0.30000000000000004；NUMERIC(19,4) 读回必须是 0.3
    stock(a, wh, loc, idFor(a, 3), qSellable(a), "0.1", now());
    stock(a, wh, loc, idFor(a, 4), qSellable(a), "0.1", now());
    stock(a, wh, loc, idFor(a, 3), qPending(a), "0.1", now());
    assertThat(detail(wh).stock().onHand()).isEqualByComparingTo("0.3");
    assertThat(detail(wh).stock().onHand().toPlainString()).doesNotContain("0000");
  }

  // ================================================================ 库位结构与清单

  @Test
  void 库位结构按类型聚合并区分可拣() {
    UUID wh = whOf(a);
    UUID storage = location(a, 11, wh, "A-01", "STORAGE", "ACTIVE", true);
    location(a, 12, wh, "A-02", "STORAGE", "ACTIVE", false);
    location(a, 13, wh, "Q-01", "QUARANTINE", "ACTIVE", false);
    stock(a, wh, storage, idFor(a, 3), qSellable(a), "50", now());

    var d = detail(wh);
    assertThat(d.locations().totalCount()).isEqualTo(3);
    assertThat(d.locations().pickableCount()).isEqualTo(1);
    // 有量的库位 1 个，其余 2 个是空的 —— 空库位不是「不存在」
    assertThat(d.locations().occupiedCount()).isEqualTo(1);
    assertThat(d.locations().onHand()).isEqualByComparingTo("50");
    assertThat(d.locations().byType()).hasSize(2);
    assertThat(d.locations().byType().stream().filter(t -> t.type().equals("QUARANTINE")))
        .allMatch(t -> t.onHand().compareTo(BigDecimal.ZERO) == 0);
  }

  @Test
  void 库存分布与库位结构两个面板的库位切面是同一份事实() {
    UUID wh = whOf(a);
    UUID loc = location(a, 11, wh, "A-01", "STORAGE", "ACTIVE", true);
    stock(a, wh, loc, idFor(a, 3), qSellable(a), "42", now());
    var d = detail(wh);
    // 两处返回同一批行，所以「库存分布说 42、库位结构说 41」这种漂移不可能发生
    assertThat(d.stock().byLocationType()).isEqualTo(d.locations().byType());
  }

  @Test
  void 库位清单是真分页且合计不受分页与筛选影响() {
    UUID wh = whOf(a);
    for (int i = 11; i <= 15; i++) {
      location(a, i, wh, "A-0" + (i - 10), "STORAGE", "ACTIVE", true);
    }
    location(a, 16, wh, "Q-01", "QUARANTINE", "ACTIVE", false);

    var first =
        asA("read", () -> service.locations(wh, new LocationQuery(null, null, null, "CODE", 0, 2)));
    var second =
        asA("read", () -> service.locations(wh, new LocationQuery(null, null, null, "CODE", 1, 2)));
    assertThat(first.total()).isEqualTo(6);
    assertThat(first.rows()).hasSize(2);
    assertThat(second.rows()).hasSize(2);
    assertThat(first.rows().get(0).code()).isNotEqualTo(second.rows().get(0).code());
    // 合计是全量 6，不随翻页变化
    assertThat(first.totals().locationCount()).isEqualTo(6);

    var filtered =
        asA(
            "read",
            () ->
                service.locations(wh, new LocationQuery("QUARANTINE", null, null, "CODE", 0, 50)));
    assertThat(filtered.rows()).hasSize(1);
    // 切了筛选条件，合计仍是全量 —— 否则「筛一下怎么总量变了」像数据丢了
    assertThat(filtered.totals().locationCount()).isEqualTo(6);
  }

  @Test
  void 库位排序是白名单且非法类型被拒() {
    UUID wh = whOf(a);
    location(a, 11, wh, "A-01", "STORAGE", "ACTIVE", true);
    assertThatThrownBy(
            () ->
                asA(
                    "read",
                    () ->
                        service.locations(
                            wh, new LocationQuery(null, null, null, "DROP TABLE", 0, 20))))
        .hasMessageContaining("不支持的排序键");
    assertThatThrownBy(
            () ->
                asA(
                    "read",
                    () ->
                        service.locations(
                            wh, new LocationQuery("NOT_A_TYPE", null, null, "CODE", 0, 20))))
        .hasMessageContaining("不支持的库位类型");
    // 合法排序键真的生效：按在手量降序，最大的那个排第一
    UUID loc = location(a, 12, wh, "B-01", "STORAGE", "ACTIVE", true);
    stock(a, wh, loc, idFor(a, 3), qSellable(a), "99", now());
    var byStock =
        asA(
            "read",
            () -> service.locations(wh, new LocationQuery(null, null, null, "STOCK", 0, 20)));
    assertThat(byStock.rows().get(0).code()).isEqualTo("B-01");
  }

  @Test
  void 库位行的最近变动时间按业务时刻写回不被二次偏时区() {
    UUID wh = whOf(a);
    UUID loc = location(a, 11, wh, "A-01", "STORAGE", "ACTIVE", true);
    // 用 Timestamp.valueOf(LocalDateTime) 绑定会在东八区静默偏 −28800s，
    // 库位上的「最近变动」就会平移 8 小时 —— 所以断言写回的值本身。
    OffsetDateTime moved = OffsetDateTime.parse("2026-03-15T23:40:00+08:00");
    stock(a, wh, loc, idFor(a, 3), qSellable(a), "5", moved);

    var row =
        asA("read", () -> service.locations(wh, new LocationQuery(null, null, null, "CODE", 0, 20)))
            .rows()
            .get(0);
    assertThat(row.lastMovementAt().toInstant()).isEqualTo(moved.toInstant());
    // TIMESTAMPTZ preserves the instant; its returned offset depends on the database/JVM zone.
    // 业务量本身没有时区：数量与时刻的绑定方式互不影响
    assertThat(row.onHand()).isEqualByComparingTo("5");
  }

  // ================================================================ 作业统计

  @Test
  void 作业统计含收货盘点调拨与库存变动() {
    UUID wh = whOf(a);
    UUID loc = location(a, 11, wh, "A-01", "STORAGE", "ACTIVE", true);
    stock(a, wh, loc, idFor(a, 3), qSellable(a), "10", now());
    receipt(a, wh, "PARTIALLY_RECEIVED", "80");
    receipt(a, wh, "CLOSED", "20");
    stocktake("WAREHOUSE", wh, "OPEN");
    transitLeg(wh, otherWarehouseA(), "5"); // 调出本仓的一条在途腿

    var d = detail(wh);
    assertThat(d.work().receiptTotal()).isEqualTo(2);
    assertThat(d.work().receiptOpenCount()).isEqualTo(1); // PARTIALLY_RECEIVED 未关
    assertThat(d.work().receiptClosedCount()).isEqualTo(1);
    // 「收了 2 单」与「收了 100 件」是两个问题，所以件数单独给
    assertThat(d.work().receiptReceivedQuantity()).isEqualByComparingTo("100");
    assertThat(d.work().stocktakeTaskCount()).isEqualTo(1);
    assertThat(d.work().workOrderTotal()).isEqualTo(3); // 2 收货 + 1 盘点
  }

  @Test
  void 未交付的作业维度不计进合计而是在入口里报未接入() {
    var d = detail(whOf(a));
    // 合计里没有上架 / 拣货 / 组装，因为它们没有表 —— 不是「这个仓没有这些作业」
    assertThat(d.work().workOrderTotal()).isZero();
    var byKey =
        d.entryPoints().stream()
            .collect(java.util.stream.Collectors.toMap(DetailEntryPoint::key, e -> e));
    assertThat(byKey.get("PUTAWAY").available()).isFalse();
    assertThat(byKey.get("PUTAWAY").module()).isEqualTo("8.2.2.1");
    assertThat(byKey.get("PICK_OUTBOUND").available()).isFalse();
    assertThat(byKey.get("ASSEMBLY").available()).isFalse();
    // 未接入的入口 href 必须是 null：前端据此禁用按钮而不是跳到不存在的路由
    assertThat(byKey.get("PUTAWAY").href()).isNull();
    assertThat(byKey.get("PICK_OUTBOUND").href()).isNull();
    assertThat(byKey.get("LOCATION_TREE").available()).isFalse();
    assertThat(byKey.get("LOCATION_TREE").module()).isEqualTo("8.1.2.1");
  }

  // ================================================================ 备货策略

  @Test
  void 备货策略未交付但背后的事实是真数() {
    UUID wh = whOf(a);
    transitLeg(wh, otherWarehouseA(), "5");
    var byDimension =
        detail(wh).strategies().stream()
            .collect(java.util.stream.Collectors.toMap(StrategyBinding::dimension, s -> s));

    var self = byDimension.get("SELF_FULFILLMENT");
    assertThat(self.available()).isFalse();
    assertThat(self.ownerModule()).isEqualTo("8.1.1.3");
    assertThat(self.note()).contains("尚未交付");
    // 策略没交付 ≠ 背后的事实也没有：中转在途腿是真的
    assertThat(byDimension.get("CROSS_DOCK").facts().inTransitLegCount()).isEqualTo(1);
    assertThat(byDimension.get("CROSS_DOCK").facts().note()).contains("未交付");
    // 自有仓没有平台观测，FBA 维度的观测值如实为 null 而不是 0
    assertThat(byDimension.get("FBA_STOCKING").facts().platformObservedOnHand()).isNull();
  }

  @Test
  void 平台仓的FBA事实带真实观测值() {
    UUID wh =
        warehouse(a, 12, "WH-FBA", "PLATFORM_FBA", "US", "America/Los_Angeles", leOne(a), "ACTIVE");
    observation(wh, idFor(a, 3), "AMAZON_FBA", "88", now());
    var fba =
        detail(wh).strategies().stream()
            .filter(s -> s.dimension().equals("FBA_STOCKING"))
            .findFirst()
            .orElseThrow();
    assertThat(fba.available()).isFalse();
    assertThat(fba.facts().platformObservedOnHand()).isEqualByComparingTo("88");
    assertThat(fba.facts().platformSourceCount()).isEqualTo(1L);
  }

  // ================================================================ 人员与设备

  @Test
  void 作业任务是真数而花名册与设备如实报未接入() {
    UUID wh = whOf(a);
    workTask(a, wh, "PICKING", "COMPLETED", user);
    workTask(a, wh, "PICKING", "RUNNING", UUID.randomUUID());
    workTask(a, wh, "PACKING", "EXCEPTION", UUID.randomUUID());
    workTask(a, wh, "REVIEW", "PENDING", null); // 未派工

    var labor = detail(wh).labor();
    assertThat(labor.taskAvailable()).isTrue();
    assertThat(labor.taskTotal()).isEqualTo(4);
    assertThat(labor.completedTaskCount()).isEqualTo(1);
    assertThat(labor.runningTaskCount()).isEqualTo(1);
    assertThat(labor.exceptionTaskCount()).isEqualTo(1);
    assertThat(labor.unassignedTaskCount()).isEqualTo(1);
    assertThat(labor.distinctOperatorCount()).isEqualTo(3);
    assertThat(labor.byWorkType()).hasSize(3);
    // 花名册与设备没有数据来源，必须报 false 而不是拿「有派工」推出「有这个人」
    assertThat(labor.rosterAvailable()).isFalse();
    assertThat(labor.rosterOwnerModule()).isEqualTo("8.1.1.3");
    assertThat(labor.equipmentAvailable()).isFalse();
  }

  @Test
  void 设备表没有warehouse_id所以按仓统计设备数在数据层就无从谈起() {
    // 这条断言钉住「设备报 available=false」的真实原因：不是漏做，
    // 是 print_device 与仓库之间没有绑定关系。
    var hasWarehouseColumn =
        sql.fetchOne(
                "select count(*) from information_schema.columns"
                    + " where table_name='print_device' and column_name='warehouse_id'")
            .get(0, Long.class);
    assertThat(hasWarehouseColumn).isZero();
    assertThat(detail(whOf(a)).labor().equipmentNote()).contains("没有 warehouse_id");
  }

  // ================================================================ 维护

  @Test
  void 改基础信息与地址落库并写回() {
    UUID wh = whOf(a);
    var updated =
        asA(
            "update",
            () ->
                service.update(
                    wh,
                    new WarehouseUpdateInput(
                        "改名后的仓",
                        "us",
                        "America/New_York",
                        "1 Main St",
                        null,
                        "Seattle",
                        "WA",
                        "98101",
                        "INACTIVE")));
    assertThat(updated.name()).isEqualTo("改名后的仓");
    assertThat(updated.status()).isEqualTo("INACTIVE");
    assertThat(updated.updatedAt()).isNotNull();

    var d = detail(wh);
    assertThat(d.base().name()).isEqualTo("改名后的仓");
    assertThat(d.base().countryCode()).isEqualTo("US"); // 小写归一成大写
    assertThat(d.base().timezone()).isEqualTo("America/New_York");
    assertThat(d.base().status()).isEqualTo("INACTIVE");
  }

  @Test
  void 归档是终态不可回退且归档后不能再改() {
    UUID wh = whOf(a);
    asA(
        "update",
        () ->
            service.update(
                wh,
                new WarehouseUpdateInput(
                    "本租仓库", "CN", "Asia/Shanghai", null, null, null, null, null, "ARCHIVED")));
    assertThat(detail(wh).base().status()).isEqualTo("ARCHIVED");
    assertThat(detail(wh).base().statusLabel()).isEqualTo("已归档");
    // 取消归档不存在：归档是终态
    assertThatThrownBy(
            () ->
                asA(
                    "update",
                    () ->
                        service.update(
                            wh,
                            new WarehouseUpdateInput(
                                "本租仓库",
                                "CN",
                                "Asia/Shanghai",
                                null,
                                null,
                                null,
                                null,
                                null,
                                "ACTIVE"))))
        .hasMessageContaining("终态");
    // 归档后再改地址也不行
    assertThatThrownBy(
            () ->
                asA(
                    "update",
                    () ->
                        service.update(
                            wh,
                            new WarehouseUpdateInput(
                                "改名",
                                "CN",
                                "Asia/Shanghai",
                                "别的地址",
                                null,
                                null,
                                null,
                                null,
                                null))))
        .hasMessageContaining("终态");
  }

  @Test
  void 维护的非法输入被真实校验拦住() {
    UUID wh = whOf(a);
    // 偏移量时区会让当地时间永远算错，必须在维护时就拒
    assertThatThrownBy(
            () ->
                asA(
                    "update",
                    () ->
                        service.update(
                            wh,
                            new WarehouseUpdateInput(
                                "X", "CN", "+08:00", null, null, null, null, null, null))))
        .hasMessageContaining("+08:00");
    assertThatThrownBy(
            () ->
                asA(
                    "update",
                    () ->
                        service.update(
                            wh,
                            new WarehouseUpdateInput(
                                "X", "CHN", "Asia/Shanghai", null, null, null, null, null, null))))
        .hasMessageContaining("两位字母");
    assertThatThrownBy(
            () ->
                asA(
                    "update",
                    () ->
                        service.update(
                            wh,
                            new WarehouseUpdateInput(
                                "  ", "CN", "Asia/Shanghai", null, null, null, null, null, null))))
        .hasMessageContaining("名称不能为空");
    assertThatThrownBy(
            () ->
                asA(
                    "update",
                    () ->
                        service.update(
                            wh,
                            new WarehouseUpdateInput(
                                "X", "CN", "Asia/Shanghai", null, null, null, null, null, "GONE"))))
        .hasMessageContaining("不支持的仓库状态");
  }

  @Test
  void 维护不存在的仓库返回404() {
    assertThatThrownBy(
            () ->
                asA(
                    "update",
                    () ->
                        service.update(
                            UUID.randomUUID(),
                            new WarehouseUpdateInput(
                                "X", "CN", "Asia/Shanghai", null, null, null, null, null, null))))
        .hasMessageContaining("404");
  }

  // ================================================================ 端点与权限

  @Test
  void 端点返回真实数据而不是硬编码常量() throws Exception {
    UUID wh = whOf(a);
    UUID loc = location(a, 11, wh, "A-01", "STORAGE", "ACTIVE", true);
    stock(a, wh, loc, idFor(a, 3), qSellable(a), "77", now());

    http.perform(get("/warehouse/facilities/{id}", wh).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.base.code").value("WH-A"))
        .andExpect(jsonPath("$.base.kind").value("LOCAL"))
        .andExpect(jsonPath("$.stock.onHand").value(77))
        .andExpect(jsonPath("$.locations.totalCount").value(1))
        .andExpect(jsonPath("$.labor.taskAvailable").value(true))
        .andExpect(jsonPath("$.labor.rosterAvailable").value(false))
        .andExpect(jsonPath("$.entryPoints[?(@.key=='PUTAWAY')].available").value(false));
    http.perform(
            get("/warehouse/facilities/{id}/locations", wh)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.rows[0].code").value("A-01"))
        .andExpect(jsonPath("$.rows[0].onHand").value(77));
    http.perform(
            get("/warehouse/facilities/{id}/entrypoints", wh)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.key=='LOCATION_TREE')].available").value(false))
        .andExpect(jsonPath("$[?(@.key=='INVENTORY')].available").value(true));
    // 排序键非法 → 400，不是静默按默认序返回
    http.perform(
            get("/warehouse/facilities/{id}/locations", wh)
                .header("Authorization", "Bearer tenant-a")
                .param("sort", "DROP TABLE"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 拼接错的链接落在不存在的仓库上必须404() throws Exception {
    // 入口清单本身与仓库数据无关，但「不存在的仓库也返回 200」会让拼错的链接看起来有效
    http.perform(
            get("/warehouse/facilities/{id}/entrypoints", UUID.randomUUID())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
  }

  @Test
  void 只有read权限的角色不能维护() throws Exception {
    assertThat(access.require("Bearer read-only", "warehouse.facilities.read")).isNotNull();
    assertThatThrownBy(() -> access.require("Bearer read-only", "warehouse.facilities.update"))
        .hasMessageContaining("403");
    http.perform(
            put("/warehouse/facilities/{id}", whOf(a))
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content(
                    """
                {"name":"改名","countryCode":"CN","timezone":"Asia/Shanghai"}"""))
        .andExpect(status().isForbidden());
    http.perform(
            get("/warehouse/facilities/{id}", whOf(a)).header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk());
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    UUID whA = whOf(a);
    UUID loc = location(a, 11, whA, "A-01", "STORAGE", "ACTIVE", true);
    stock(a, whA, loc, idFor(a, 3), qSellable(a), "999", now());

    var dA = detail(whA);
    assertThat(dA.base().code()).isEqualTo("WH-A");
    assertThat(dA.stock().onHand()).isEqualByComparingTo("999");

    // B 拿 A 的仓库 id 取详情：404，不是「B 的同名仓库」也不是空对象
    assertThatThrownBy(() -> asB("read", () -> service.detail(whA))).hasMessageContaining("404");
    var dB = asB("read", () -> service.detail(whOf(b)));
    assertThat(dB.base().code()).isEqualTo("WH-B");
    assertThat(dB.stock().onHand()).isEqualByComparingTo("0");
    assertThat(dB.base().legalEntityCount()).isZero();
    // B 也读不到 A 的库位与作业统计
    assertThat(
            asB(
                    "read",
                    () ->
                        service.locations(
                            whOf(b), new LocationQuery(null, null, null, "CODE", 0, 50)))
                .total())
        .isZero();
    assertThat(dB.work().workOrderTotal()).isZero();
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    UUID whA = whOf(a);
    UUID loc = location(a, 11, whA, "A-01", "STORAGE", "ACTIVE", true);
    stock(a, whA, loc, idFor(a, 3), qSellable(a), "999", now());

    // 身份是 B，伪造 X-Tenant-Id 指向 A：B 仍然拿不到 A 的仓库
    http.perform(
            get("/warehouse/facilities/{id}", whA)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
    // 伪造头也不能让 B 改到 A 的仓库
    http.perform(
            put("/warehouse/facilities/{id}", whA)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString())
                .contentType("application/json")
                .content(
                    """
                {"name":"伪造改名","countryCode":"CN","timezone":"Asia/Shanghai"}"""))
        .andExpect(status().isNotFound());
    // A 的数据未被这串伪造请求改动
    assertThat(detail(whA).base().name()).isEqualTo("本租仓库");
    assertThat(detail(whA).stock().onHand()).isEqualByComparingTo("999");
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    UUID whA = whOf(a);
    UUID loc = location(a, 11, whA, "A-01", "STORAGE", "ACTIVE", true);
    stock(a, whA, loc, idFor(a, 3), qSellable(a), "999", now());

    // 交替复用同一条物理连接（池容量=1）：A → B → A → B
    for (int i = 0; i < 3; i++) {
      assertThat(detail(whA).stock().onHand()).isEqualByComparingTo("999");
      assertThat(asB("read", () -> service.detail(whOf(b))).stock().onHand())
          .isEqualByComparingTo("0");
      assertThat(detail(whA).locations().totalCount()).isEqualTo(1);
      assertThat(asB("read", () -> service.detail(whOf(b))).locations().totalCount()).isZero();
    }
    // 本模块读到的每一张表逐张确认 FORCE RLS：
    // 只读聚合不建新表，但「读到别人家的行」的风险一样存在，所以逐张断言运行时事实。
    for (var table :
        List.of(
            "warehouse",
            "warehouse_location",
            "inventory_balance",
            "stock_bucket",
            "goods_receipt",
            "goods_receipt_line",
            "warehouse_work_task",
            "stocktake_sheet",
            "stocktake_task",
            "transfer_order",
            "inventory_transfer",
            "in_transit_event",
            "inventory_entry",
            "platform_stock_observation")) {
      boolean forced =
          sql.fetchOne(
                  "select (relrowsecurity and relforcerowsecurity) as on from pg_class"
                      + " where relname=? and relnamespace='public'::regnamespace",
                  table)
              .get("on", Boolean.class);
      assertThat(forced).as("%s 必须 FORCE RLS", table).isTrue();
    }
    // 不设 app.current_tenant 时读不到任何行：第一道防线在服务层谓词之前就生效。
    assertThat(
            sql.fetchOne(
                    "select count(*) from warehouse"
                        + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid")
                .get(0, Long.class))
        .isZero();
  }
}
