package com.starcon.warehouse;

import static com.starcon.warehouse.WarehouseFacilityModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDateTime;
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
 * 8.1.1.1 仓库列表。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接： 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>本测试钉住四件「错了会误导人」的事：
 *
 * <ul>
 *   <li><b>平台仓观测值与自有账本不相加。</b>V55 把这条写进了两条 DB 触发器， 这里从响应形状上再验一次：平台仓行的 {@code stock} 恒为 0， 它的数只在
 *       {@code platformStock} 里。两处相加就会得到一个不存在的总量。
 *   <li><b>时区不是裸偏移。</b>行上给 IANA ID + 算好的当地时刻； 测试按 <em>同一个时刻</em> 在两个不同时区各算一次，断言两个当地时间不同 ——
 *       只断言「格式对」的话，写死成 UTC 的实现也能过。
 *   <li><b>未交付的作业维度必须报 available=false</b>，而不是算成 0 冒充「没有作业」。
 *   <li><b>租户隔离三断言</b>实跑：两租户互不可见 / 伪造 X-Tenant-Id 无效 / 连接池归还后不串租户。
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class WarehouseFacilityIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      List.of("warehouse.facilities.read", "warehouse.facilities.create");

  static final UUID leOne = idFor(a, 1);
  static final UUID leTwo = idFor(a, 2);
  static final UUID skuOne = idFor(a, 3);
  static final UUID skuTwo = idFor(a, 4);
  static final UUID whLocal = idFor(a, 5);
  static final UUID whBonded = idFor(a, 6);
  static final UUID whFba = idFor(a, 7);
  static final UUID qSellable = idFor(a, 8);
  static final UUID locOne = idFor(a, 9);

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-facility-" + tenant + "-" + ordinal).getBytes());
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

  @Autowired WarehouseFacilityService service;
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
          "truncate stocktake_task, stocktake_sheet, transfer_order, inventory_entry,"
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
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 8),
                tenant,
                "SELLABLE",
                "可售",
                "SELLABLE");
            // 仓库先于库位建：库位有指向仓库的复合外键。
            // B 的仓库刻意用与 A **不同的编码与国家**：若隔离失效，
            // 「B 看到了 A 的仓」会表现为结果里混进另一个国家，断言不会含糊。
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,kind,country_code,timezone,"
                    + "default_legal_entity_id,status) values(?,?,?,?,?,?,?,?,?)",
                idFor(tenant, 5),
                tenant,
                "WH-" + (tenant.equals(a) ? "A" : "B"),
                "本租仓库",
                "LOCAL",
                tenant.equals(a) ? "CN" : "US",
                "Asia/Shanghai",
                idFor(tenant, 1),
                "ACTIVE");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name) values(?,?,?,?,?)",
                idFor(tenant, 9),
                tenant,
                idFor(tenant, 5),
                "A-01",
                "A 区一排");
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

  WarehouseFacilityPage list() {
    return asA(
        "read", () -> service.list(new FacilityQuery(null, null, null, null, null, null, 0, 50)));
  }

  // ================================================================ 夹具

  /** 在租户 A 建一个仓库。 */
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

  /** 一条自有账本余额 + 库位明细 + 一条带符号分录（作业量里的「库存变动」来源）。 */
  void balance(UUID tenant, UUID warehouse, UUID sku, String quantity) {
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
              qSellableOf(tenant),
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
              idFor(tenant, 9),
              qSellableOf(tenant),
              balanceId,
              new BigDecimal(quantity),
              now());
          UUID transactionId = UUID.randomUUID();
          sql.execute(
              "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,actor_id) "
                  + "values(?,?,'RECEIPT','FIXTURE',?,?::timestamptz,?)",
              transactionId,
              tenant,
              UUID.randomUUID(),
              now(),
              user);
          sql.execute(
              "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,"
                  + "reserved_delta) values(?,?,?,?,?,0)",
              UUID.randomUUID(),
              tenant,
              transactionId,
              balanceId,
              new BigDecimal(quantity));
          return null;
        });
  }

  UUID qSellableOf(UUID tenant) {
    return idFor(tenant, 8);
  }

  /** 一条平台仓观测（append-only，可以有历史，取最新）。 */
  void observation(
      UUID warehouse,
      UUID sku,
      String source,
      String onHand,
      String inbound,
      OffsetDateTime collectedAt) {
    access.run(
        actorA,
        "warehouse.facilities.read",
        () -> {
          sql.execute(
              "insert into platform_stock_observation(id,tenant_id,warehouse_id,sku_id,legal_entity_id,"
                  + "source,collected_at,observed_on_hand,observed_inbound) "
                  + "values(?,?,?,?,?,?,?::timestamptz,?,?)",
              UUID.randomUUID(),
              a,
              warehouse,
              sku,
              leOne,
              source,
              collectedAt,
              new BigDecimal(onHand),
              new BigDecimal(inbound));
          return null;
        });
  }

  /** 一张盘点单：WAREHOUSE 范围 = 该仓；LOCATION 范围 = 该仓的某个库位。 */
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

  /**
   * 一张调拨单，**方向显式**：调出仓与调入仓都由调用方给出。
   *
   * <p>V61 的 guard_transfer_order_state 要求状态与三个时间戳互相自洽 （草稿无确认时间、待发无发出时间、非终态无完成时间），所以这里按状态补齐时间戳，
   * 而不是塞一个与状态矛盾的组合让 DB 拒。
   */
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
              leOne,
              status,
              user,
              draft ? null : now(),
              shipped ? now() : null,
              terminal ? now() : null);
          return id;
        });
  }

  // ================================================================ 列表与指标

  @Test
  void 列表展示类型法人国家时区与启用状态() {
    warehouse(a, 6, "WH-BONDED", "BONDED", "CN", "Asia/Shanghai", leTwo, "ACTIVE");
    warehouse(a, 7, "WH-FBA", "PLATFORM_FBA", "US", "America/Los_Angeles", leOne, "INACTIVE");

    var page = list();
    assertThat(page.rows()).hasSize(3);
    var row =
        page.rows().stream().filter(r -> r.code().equals("WH-BONDED")).findFirst().orElseThrow();
    assertThat(row.kind()).isEqualTo(WarehouseKind.BONDED);
    assertThat(row.kindLabel()).isEqualTo("监管仓");
    assertThat(row.countryCode()).isEqualTo("CN");
    assertThat(row.timezone()).isEqualTo("Asia/Shanghai");
    assertThat(row.status()).isEqualTo("ACTIVE");
    // 所属法人是财务前提，必须看得见且带编码
    assertThat(row.legalEntityId()).isEqualTo(leTwo);
    assertThat(row.legalEntityCode()).isEqualTo("LE-2");
    assertThat(row.legalEntityName()).isEqualTo("主体二");
    assertThat(page.summary().warehouseCount()).isEqualTo(3);
    assertThat(page.summary().activeCount()).isEqualTo(2);
  }

  @Test
  void 监管仓是本迁移新增的第五种类型且数据库层拒绝别的取值() {
    warehouse(a, 6, "WH-BONDED", "BONDED", "CN", "Asia/Shanghai", leOne, "ACTIVE");
    assertThat(list().rows().stream().filter(r -> r.code().equals("WH-BONDED")).count())
        .isEqualTo(1);

    // CHECK 在 DB 层裁决：跳过服务层硬写一个词表外的类型必须被拒。
    assertThatThrownBy(
            () -> warehouse(a, 20, "WH-BAD", "CUSTOMS_X", "CN", "Asia/Shanghai", leOne, "ACTIVE"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void 平台仓观测值与自有账本不相加() {
    warehouse(a, 7, "WH-FBA", "PLATFORM_FBA", "US", "America/Los_Angeles", leOne, "ACTIVE");
    observation(whFba, skuOne, "AMAZON_FBA", "300", "40", now().minusDays(2));
    observation(whFba, skuOne, "AMAZON_FBA", "310", "50", now());

    var fba =
        list().rows().stream().filter(r -> r.code().equals("WH-FBA")).findFirst().orElseThrow();
    // 自有账本恒为 0：V55 的触发器禁止平台仓记自有账本。
    assertThat(fba.stock().onHand()).isEqualByComparingTo("0");
    assertThat(fba.platformStock()).isNotNull();
    // 只取最新一条（append-only 历史序列不能累加，否则 300+310=610 是不存在的数）
    assertThat(fba.platformStock().observedOnHand()).isEqualByComparingTo("310");
    assertThat(fba.platformStock().observedInbound()).isEqualByComparingTo("50");
    assertThat(fba.platformStock().latestCollectedAt()).isNotNull();
    // 合计里自有账本不含平台仓观测值
    assertThat(list().summary().onHand()).isEqualByComparingTo("0");
    assertThat(list().summary().platformObservedOnHand()).isEqualByComparingTo("310");
    assertThat(list().summary().platformWarehouseCount()).isEqualTo(1);
  }

  @Test
  void 自有仓不带平台观测字段() {
    // 不是「观测值为 0」，而是「这个来源不存在」——两者在界面上含义完全不同。
    var row = list().rows().stream().filter(r -> r.code().equals("WH-A")).findFirst().orElseThrow();
    assertThat(row.platformStock()).isNull();
  }

  @Test
  void 库存量按仓库聚合且可用量来自生成列() {
    balance(a, whLocal, skuOne, "120");
    balance(a, whLocal, skuTwo, "30");
    var row = list().rows().stream().filter(r -> r.code().equals("WH-A")).findFirst().orElseThrow();
    assertThat(row.stock().onHand()).isEqualByComparingTo("150");
    assertThat(row.stock().reserved()).isEqualByComparingTo("0");
    assertThat(row.stock().available()).isEqualByComparingTo("150");
    assertThat(row.stock().balanceRowCount()).isEqualTo(2);
    assertThat(row.skuCount()).isEqualTo(2);
    assertThat(row.locationCount()).isEqualTo(1);
    assertThat(list().summary().onHand()).isEqualByComparingTo("150");
    assertThat(list().summary().available()).isEqualByComparingTo("150");
  }

  @Test
  void 仓库同时持有多个法人的货时法人数如实计数() {
    // 绑定法人 != 持有货物的法人集合：一个仓同时存多个法人的货是合法的。
    balance(a, whLocal, skuOne, "10");
    asA(
        "read",
        () -> {
          UUID balanceId = UUID.randomUUID();
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
              balanceId,
              a,
              skuTwo,
              whLocal,
              leTwo,
              qSellable,
              new BigDecimal("7"));
          return null;
        });
    var row = list().rows().stream().filter(r -> r.code().equals("WH-A")).findFirst().orElseThrow();
    assertThat(row.legalEntityId()).isEqualTo(leOne);
    assertThat(row.legalEntityCount()).isEqualTo(2);
  }

  @Test
  void 作业量按盘点任务与调拨单分别计数() {
    warehouse(a, 6, "WH-BONDED", "BONDED", "CN", "Asia/Shanghai", leOne, "ACTIVE");
    stocktake("WAREHOUSE", whLocal, "OPEN");
    stocktake("LOCATION", locOne, "COUNTED");
    transfer(whLocal, whBonded, "RECEIVED"); // 自营仓调出 → 监管仓调入，已收
    transfer(whBonded, whLocal, "IN_TRANSIT"); // 监管仓调出 → 自营仓调入，在途

    var local =
        list().rows().stream().filter(r -> r.code().equals("WH-A")).findFirst().orElseThrow();
    // 两张盘点单都算这个仓的作业：一张按仓发起，一张按该仓的库位发起
    assertThat(local.work().stocktakeTaskCount()).isEqualTo(2);
    assertThat(local.work().transferOutCount()).isEqualTo(1); // 调出监管仓那张
    assertThat(local.work().transferInCount()).isEqualTo(1); // 监管仓调入本仓那张
    assertThat(local.work().workOrderTotal()).isEqualTo(4);
    // 未结 = 未录完的盘点（COUNTED 已结）+ 未 RECEIVED 的调拨（RECEIVED 已结）
    //        → OPEN 盘点 1 + IN_TRANSIT 调入 1 = 2
    assertThat(local.work().openWorkOrderCount()).isEqualTo(2);

    var bonded =
        list().rows().stream().filter(r -> r.code().equals("WH-BONDED")).findFirst().orElseThrow();
    assertThat(bonded.work().stocktakeTaskCount()).isZero();
    assertThat(bonded.work().transferInCount()).isEqualTo(1);
    assertThat(bonded.work().transferOutCount()).isEqualTo(1);
    assertThat(bonded.work().workOrderTotal()).isEqualTo(2);
    assertThat(bonded.work().openWorkOrderCount()).isEqualTo(1); // 只有 IN_TRANSIT 那张未结
  }

  @Test
  void 库存变动笔数计入作业量() {
    // 两个 SKU 各一条余额 → 两条带符号分录 → 作业量里的「库存变动」笔数为 2。
    balance(a, whLocal, skuOne, "5");
    balance(a, whLocal, skuTwo, "5");
    var row = list().rows().stream().filter(r -> r.code().equals("WH-A")).findFirst().orElseThrow();
    assertThat(row.work().inventoryMovementCount()).isEqualTo(2);
    assertThat(row.stock().balanceRowCount()).isEqualTo(2);
  }

  @Test
  void 未交付的作业维度如实报available为false() {
    var capabilities = asA("read", () -> service.workCapabilities());
    var byDimension =
        capabilities.stream()
            .collect(java.util.stream.Collectors.toMap(WorkVolumeCapability::dimension, c -> c));
    assertThat(byDimension.get("STOCKTAKE").available()).isTrue();
    assertThat(byDimension.get("TRANSFER").available()).isTrue();
    // 8.2 / 8.3 / 8.4 尚未交付：必须报 false 并说清归哪个模块，
    // 而不是算成 0 冒充「这个仓不作业」。
    assertThat(byDimension.get("RECEIVING").available()).isFalse();
    assertThat(byDimension.get("RECEIVING").ownerModule()).isEqualTo("8.2");
    assertThat(byDimension.get("RECEIVING").note()).contains("尚未交付");
    assertThat(byDimension.get("PICKING").available()).isFalse();
    assertThat(byDimension.get("PICKING").ownerModule()).isEqualTo("8.3");
    assertThat(byDimension.get("ASSEMBLY").available()).isFalse();
    assertThat(byDimension.get("ASSEMBLY").ownerModule()).isEqualTo("8.4");
  }

  // ================================================================ 时区

  @Test
  void 当地时间按仓库时区计算而不是统一UTC() {
    warehouse(a, 6, "WH-NY", "LOCAL", "US", "America/New_York", leOne, "ACTIVE");
    warehouse(a, 7, "WH-SH", "LOCAL", "CN", "Asia/Shanghai", leOne, "ACTIVE");

    var page = list();
    var ny = page.rows().stream().filter(r -> r.code().equals("WH-NY")).findFirst().orElseThrow();
    var sh = page.rows().stream().filter(r -> r.code().equals("WH-SH")).findFirst().orElseThrow();
    // IANA ID 原样给出，前端不需要猜时区
    assertThat(ny.timezone()).isEqualTo("America/New_York");
    assertThat(sh.timezone()).isEqualTo("Asia/Shanghai");
    // 用同一时刻在两个时区各算一次：当地时间必须不同。写死 UTC 的实现会在这里露馅。
    LocalDateTime nyLocal =
        LocalDateTime.ofInstant(now().toInstant(), ZoneId.of("America/New_York"));
    LocalDateTime shLocal = LocalDateTime.ofInstant(now().toInstant(), ZoneId.of("Asia/Shanghai"));
    assertThat(ny.localTime()).isNotEqualTo(sh.localTime());
    assertThat(
            LocalDateTime.parse(
                ny.localTime().replace(' ', 'T'),
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")))
        .isBetween(nyLocal.minusMinutes(5), nyLocal.plusMinutes(5));
    assertThat(
            LocalDateTime.parse(
                sh.localTime().replace(' ', 'T'),
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")))
        .isBetween(shLocal.minusMinutes(5), shLocal.plusMinutes(5));
  }

  @Test
  void 库里存了JDK不认识的时区时如实报空而不是退化成UTC() {
    // 直接绕过应用层写一个非法时区：列表不能静默给一个看起来正常却错了的当地时间。
    asA(
        "read",
        () -> {
          sql.execute(
              "insert into warehouse(id,tenant_id,code,name,kind,country_code,timezone,"
                  + "default_legal_entity_id,status) values(?,?,?,?,'LOCAL','CN','Not/AZone',?,'ACTIVE')",
              UUID.randomUUID(),
              a,
              "WH-BADTZ",
              "坏时区仓",
              leOne);
          return null;
        });
    var row =
        list().rows().stream().filter(r -> r.code().equals("WH-BADTZ")).findFirst().orElseThrow();
    assertThat(row.timezone()).isEqualTo("Not/AZone");
    assertThat(row.localTime()).isNull();
  }

  // ================================================================ 筛选与分页

  @Test
  void 按类型筛选() {
    warehouse(a, 6, "WH-BONDED", "BONDED", "CN", "Asia/Shanghai", leOne, "ACTIVE");
    var page =
        asA(
            "read",
            () -> service.list(new FacilityQuery("BONDED", null, null, null, null, null, 0, 50)));
    assertThat(page.rows()).hasSize(1);
    assertThat(page.rows().get(0).code()).isEqualTo("WH-BONDED");
    assertThat(page.total()).isEqualTo(1);
    // 合计不受筛选影响：切筛选条件不该让总量看起来像丢了数据。
    assertThat(page.summary().warehouseCount()).isEqualTo(2);
  }

  @Test
  void 按法人与国家筛选() {
    warehouse(a, 6, "WH-BONDED", "BONDED", "CN", "Asia/Shanghai", leTwo, "ACTIVE");
    assertThat(
            asA(
                    "read",
                    () ->
                        service.list(new FacilityQuery(null, leTwo, null, null, null, null, 0, 50)))
                .rows())
        .hasSize(1);
    assertThat(
            asA(
                    "read",
                    () ->
                        service.list(new FacilityQuery(null, leOne, null, null, null, null, 0, 50)))
                .rows())
        .hasSize(1);
    assertThat(
            asA(
                    "read",
                    () ->
                        service.list(new FacilityQuery(null, null, "CN", null, null, null, 0, 50)))
                .rows())
        .hasSize(2);
    assertThat(
            asA(
                    "read",
                    () ->
                        service.list(new FacilityQuery(null, null, "US", null, null, null, 0, 50)))
                .rows())
        .isEmpty();
  }

  @Test
  void 按状态与关键字筛选() {
    warehouse(a, 6, "WH-BONDED", "BONDED", "CN", "Asia/Shanghai", leOne, "INACTIVE");
    assertThat(
            asA(
                    "read",
                    () ->
                        service.list(
                            new FacilityQuery(null, null, null, "INACTIVE", null, null, 0, 50)))
                .rows())
        .hasSize(1);
    assertThat(
            asA(
                    "read",
                    () ->
                        service.list(
                            new FacilityQuery(null, null, null, "ACTIVE", null, null, 0, 50)))
                .rows())
        .hasSize(1);
    assertThat(
            asA(
                    "read",
                    () ->
                        service.list(
                            new FacilityQuery(null, null, null, null, "bonded", null, 0, 50)))
                .rows())
        .hasSize(1);
    assertThat(
            asA(
                    "read",
                    () ->
                        service.list(new FacilityQuery(null, null, null, null, "不存在", null, 0, 50)))
                .rows())
        .isEmpty();
  }

  @Test
  void 归档的仓库仍然在列表里() {
    // 列表要能筛到归档行，否则「归档了但查不到」会让用户以为仓库凭空消失。
    warehouse(a, 6, "WH-OLD", "LOCAL", "CN", "Asia/Shanghai", leOne, "ARCHIVED");
    assertThat(list().rows()).hasSize(2);
    assertThat(list().summary().activeCount()).isEqualTo(1);
  }

  @Test
  void 排序是白名单且分页是真分页() {
    for (int i = 6; i <= 9; i++) {
      warehouse(a, i, "WH-0" + i, "LOCAL", "CN", "Asia/Shanghai", leOne, "ACTIVE");
    }
    var first =
        asA(
            "read",
            () -> service.list(new FacilityQuery(null, null, null, null, null, "CODE", 0, 2)));
    var second =
        asA(
            "read",
            () -> service.list(new FacilityQuery(null, null, null, null, null, "CODE", 1, 2)));
    assertThat(first.total()).isEqualTo(5);
    assertThat(first.rows()).hasSize(2);
    assertThat(second.rows()).hasSize(2);
    assertThat(first.rows().get(0).code()).isNotEqualTo(second.rows().get(0).code());
    // 排序键非法必须被拒，而不是静默忽略后按默认序返回一份「看起来能排序」的数据。
    assertThatThrownBy(
            () ->
                asA(
                    "read",
                    () ->
                        service.list(
                            new FacilityQuery(null, null, null, null, null, "DROP TABLE", 0, 2))))
        .hasMessageContaining("不支持的排序键");
  }

  @Test
  void 下拉项只列真实数据() {
    warehouse(a, 6, "WH-BONDED", "BONDED", "CN", "Asia/Shanghai", leTwo, "ACTIVE");
    var options = asA("read", () -> service.options());
    assertThat(options.kinds()).hasSize(5);
    assertThat(options.kinds().stream().map(KindOption::code))
        .containsExactly("LOCAL", "OVERSEAS", "PLATFORM_FBA", "THIRD_PARTY", "BONDED");
    // 平台仓那一项必须标出来：前端要据此把观测值与自有账本分开显示。
    assertThat(
            options.kinds().stream()
                .filter(k -> k.code().equals("PLATFORM_FBA"))
                .findFirst()
                .orElseThrow()
                .platformObserved())
        .isTrue();
    // 国家只列本租户真实在用的，不造内置国别表
    assertThat(options.countries().stream().map(CountryOption::code)).containsExactly("CN");
    assertThat(options.legalEntities()).hasSize(2);
    assertThat(options.statuses()).hasSize(3);
  }

  // ================================================================ 新增

  @Test
  void 新增仓库落库且行立刻可见() {
    var created =
        asA(
            "create",
            () ->
                service.create(
                    new WarehouseCreateInput(
                        "wh-new", "新仓", "BONDED", "cn", "Asia/Shanghai", leTwo, "ACTIVE", false)));
    assertThat(created.kind()).isEqualTo(WarehouseKind.BONDED);
    // 国家码统一大写：CN / cn 不该是两个国家
    var row =
        list().rows().stream().filter(r -> r.id().equals(created.id())).findFirst().orElseThrow();
    assertThat(row.code()).isEqualTo("WH-NEW");
    assertThat(row.countryCode()).isEqualTo("CN");
    assertThat(row.legalEntityId()).isEqualTo(leTwo);
    assertThat(row.kind()).isEqualTo(WarehouseKind.BONDED);
    assertThat(row.stock().onHand()).isEqualByComparingTo("0");
  }

  @Test
  void 新增仓库的非法输入被真实校验拦住() {
    assertThatThrownBy(
            () ->
                asA(
                    "create",
                    () ->
                        service.create(
                            new WarehouseCreateInput(
                                "wh-x", "X", "NOPE", "CN", "Asia/Shanghai", leOne, null, false))))
        .hasMessageContaining("不支持的仓库类型");
    assertThatThrownBy(
            () ->
                asA(
                    "create",
                    () ->
                        service.create(
                            new WarehouseCreateInput(
                                "wh-x", "X", "LOCAL", "CHN", "Asia/Shanghai", leOne, null, false))))
        .hasMessageContaining("两位字母");
    // 非法时区必须在新增时就被拒：错时区会让当地时间永远算错。
    assertThatThrownBy(
            () ->
                asA(
                    "create",
                    () ->
                        service.create(
                            new WarehouseCreateInput(
                                "wh-x", "X", "LOCAL", "CN", "Not/AZone", leOne, null, false))))
        .hasMessageContaining("Not/AZone");
    assertThatThrownBy(
            () ->
                asA(
                    "create",
                    () ->
                        service.create(
                            new WarehouseCreateInput(
                                "wh-x",
                                "X",
                                "LOCAL",
                                "CN",
                                "Asia/Shanghai",
                                UUID.randomUUID(),
                                null,
                                false))))
        .hasMessageContaining("404");
  }

  @Test
  void 法人是财务前提所以停用法人与缺失法人都拒绝() {
    asA(
        "read",
        () -> {
          sql.execute(
              "update legal_entity_ref set status='SUSPENDED' where tenant_id=? and id=?",
              a,
              leTwo);
          return null;
        });
    assertThatThrownBy(
            () ->
                asA(
                    "create",
                    () ->
                        service.create(
                            new WarehouseCreateInput(
                                "wh-x", "X", "LOCAL", "CN", "Asia/Shanghai", leTwo, null, false))))
        .hasMessageContaining("已停用");
  }

  @Test
  void 每个租户至多一个启用中的默认仓() {
    asA(
        "create",
        () ->
            service.create(
                new WarehouseCreateInput(
                    "wh-def", "默认仓", "LOCAL", "CN", "Asia/Shanghai", leOne, "ACTIVE", true)));
    assertThatThrownBy(
            () ->
                asA(
                    "create",
                    () ->
                        service.create(
                            new WarehouseCreateInput(
                                "wh-def2",
                                "第二个默认仓",
                                "LOCAL",
                                "CN",
                                "Asia/Shanghai",
                                leOne,
                                "ACTIVE",
                                true))))
        .hasMessageContaining("默认发货仓");
    var row =
        list().rows().stream().filter(r -> r.code().equals("WH-DEF")).findFirst().orElseThrow();
    assertThat(row.defaultWarehouse()).isTrue();
  }

  @Test
  void 同一租户仓库编码唯一() {
    assertThatThrownBy(
            () ->
                asA(
                    "create",
                    () ->
                        service.create(
                            new WarehouseCreateInput(
                                "wh-a", "撞码", "LOCAL", "CN", "Asia/Shanghai", leOne, null, false))))
        .hasMessageContaining("已存在");
  }

  @Test
  void 平台仓新增后不能记自有账本() {
    asA(
        "create",
        () ->
            service.create(
                new WarehouseCreateInput(
                    "wh-fba2",
                    "FBA 仓",
                    "PLATFORM_FBA",
                    "US",
                    "America/Los_Angeles",
                    leOne,
                    "ACTIVE",
                    false)));
    var fba =
        list().rows().stream().filter(r -> r.code().equals("WH-FBA2")).findFirst().orElseThrow();
    assertThat(fba.kind()).isEqualTo(WarehouseKind.PLATFORM_FBA);
    // V55 的触发器仍然生效：平台仓记自有账本被 DB 拒绝。
    assertThatThrownBy(() -> balance(a, fba.id(), skuOne, "10"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  // ================================================================ 权限

  @Test
  void 只有read权限的角色不能新增仓库() throws Exception {
    // 8.1.1.1 注册的是 read + create 两个码；写权限独立于读权限。
    assertThat(access.require("Bearer read-only", "warehouse.facilities.read")).isNotNull();
    assertThatThrownBy(() -> access.require("Bearer read-only", "warehouse.facilities.create"))
        .hasMessageContaining("403");
    http.perform(
            post("/warehouse/facilities")
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content(
                    """
                {"code":"wh-x","name":"X","kind":"LOCAL","countryCode":"CN",
                 "timezone":"Asia/Shanghai","defaultLegalEntityId":"%s"}"""
                        .formatted(leOne)))
        .andExpect(status().isForbidden());
    http.perform(get("/warehouse/facilities").header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk());
  }

  @Test
  void 端点返回真实数据而不是硬编码常量() throws Exception {
    warehouse(a, 6, "WH-BONDED", "BONDED", "CN", "Asia/Shanghai", leTwo, "ACTIVE");
    http.perform(get("/warehouse/facilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2))
        .andExpect(jsonPath("$.rows[?(@.code=='WH-BONDED')].kind").value("BONDED"))
        .andExpect(jsonPath("$.rows[?(@.code=='WH-BONDED')].kindLabel").value("监管仓"))
        // detailUnavailable 是 8.1.1.1 → 8.1.1.2 的交接开关：详情已交付，所以这里是 false，
        // 前端据此放行「进详情」而不是禁用按钮。
        .andExpect(jsonPath("$.detailUnavailable").value(false));
    http.perform(get("/warehouse/facilities/options").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kinds.length()").value(5));
    http.perform(
            get("/warehouse/facilities/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.dimension=='RECEIVING')].available").value(false))
        .andExpect(jsonPath("$[?(@.dimension=='STOCKTAKE')].available").value(true));
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    warehouse(a, 6, "WH-BONDED", "BONDED", "CN", "Asia/Shanghai", leTwo, "ACTIVE");
    balance(a, whLocal, skuOne, "999");

    var pageA = list();
    assertThat(pageA.rows()).hasSize(2);
    var pageB =
        asB(
            "read",
            () -> service.list(new FacilityQuery(null, null, null, null, null, null, 0, 50)));
    // B 只看到自己的一个仓，A 的仓与库存一个都不出现
    assertThat(pageB.rows()).hasSize(1);
    assertThat(pageB.rows().get(0).code()).isEqualTo("WH-B");
    assertThat(pageB.rows().stream().map(WarehouseRow::id)).doesNotContain(whLocal, whBonded);
    assertThat(pageB.summary().onHand()).isEqualByComparingTo("0");
    assertThat(pageB.summary().warehouseCount()).isEqualTo(1);

    // B 也建不出一个能看见 A 库存的仓：写入落在 B 的名下
    var createdB =
        asB(
            "create",
            () ->
                service.create(
                    new WarehouseCreateInput(
                        "wh-b-two",
                        "B 的第二个仓",
                        "LOCAL",
                        "CN",
                        "Asia/Shanghai",
                        idFor(b, 1),
                        "ACTIVE",
                        false)));
    var afterB =
        asB(
            "read",
            () -> service.list(new FacilityQuery(null, null, null, null, null, null, 0, 50)));
    assertThat(afterB.rows()).hasSize(2);
    assertThat(afterB.rows().stream().map(WarehouseRow::id)).contains(createdB.id());
    // B 新建的仓在 A 的列表里**不存在** —— 行确实落在 B 名下，而不是共享。
    assertThat(list().rows().stream().map(WarehouseRow::id)).doesNotContain(createdB.id());
    assertThat(
            asA(
                    "read",
                    () -> sql.fetchOne("select count(*) from warehouse where id=?", createdB.id()))
                .get(0, Long.class))
        .isZero();
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    warehouse(a, 6, "WH-BONDED", "BONDED", "CN", "Asia/Shanghai", leTwo, "ACTIVE");
    balance(a, whLocal, skuOne, "999");

    // 身份是 B，伪造 X-Tenant-Id 指向 A：B 看到的仍然是 B 的（1 个仓、0 库存）。
    http.perform(
            get("/warehouse/facilities")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.summary.onHand").value(0));
    http.perform(
            get("/warehouse/facilities")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString())
                .param("legalEntityId", leTwo.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
    // 伪造头也不能让 B 建出一个挂到 A 法人名下的仓。
    http.perform(
            post("/warehouse/facilities")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString())
                .contentType("application/json")
                .content(
                    """
                {"code":"wh-forged","name":"伪造仓","kind":"LOCAL","countryCode":"CN",
                 "timezone":"Asia/Shanghai","defaultLegalEntityId":"%s"}"""
                        .formatted(leTwo)))
        .andExpect(status().isNotFound());
    // A 的数据未被这串伪造请求改动。
    assertThat(list().total()).isEqualTo(2);
    assertThat(list().summary().onHand()).isEqualByComparingTo("999");
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    warehouse(a, 6, "WH-BONDED", "BONDED", "CN", "Asia/Shanghai", leTwo, "ACTIVE");
    balance(a, whLocal, skuOne, "999");

    // 交替复用同一条物理连接（池容量=1）：A → B → A → B
    for (int i = 0; i < 3; i++) {
      var a1 = list();
      assertThat(a1.total()).isEqualTo(2);
      assertThat(a1.summary().onHand()).isEqualByComparingTo("999");
      var b1 =
          asB(
              "read",
              () -> service.list(new FacilityQuery(null, null, null, null, null, null, 0, 50)));
      assertThat(b1.total()).isEqualTo(1);
      assertThat(b1.rows().get(0).code()).isEqualTo("WH-B");
      var a2 = list();
      assertThat(a2.summary().onHand()).isEqualByComparingTo("999");
      var b2 =
          asB(
              "read",
              () -> service.list(new FacilityQuery(null, null, null, null, null, null, 0, 50)));
      assertThat(b2.rows().stream().map(WarehouseRow::id)).doesNotContain(whLocal, whBonded);
    }
    // warehouse 与 platform_stock_observation 逐张确认 FORCE RLS
    for (var table : List.of("warehouse", "platform_stock_observation")) {
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
                    "select count(*) from warehouse where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid")
                .get(0, Long.class))
        .isZero();
  }

  @Test
  void 观测时间按timestamptz绑定写回后不被二次偏时区() {
    // collected_at 是**业务发生时刻**（观测值的发生时点），由采集器给出。
    // 用 Timestamp.valueOf(LocalDateTime) 绑定会在东八区静默偏 −28800s，
    // 列表上的「最近采集时间」就会比真实采集晚/早 8 小时 —— 所以这里断言写回的值本身。
    OffsetDateTime collected = OffsetDateTime.parse("2026-03-15T23:40:00+08:00");
    warehouse(a, 7, "WH-OBS", "PLATFORM_FBA", "US", "America/Los_Angeles", leOne, "ACTIVE");
    observation(whFba, skuOne, "AMAZON_FBA", "7", "0", collected);

    var row =
        list().rows().stream().filter(r -> r.code().equals("WH-OBS")).findFirst().orElseThrow();
    // 1) 写回的是同一个瞬间（+08:00 → UTC 15:40Z），不是被平移过的墙面值。
    //    用 Timestamp.valueOf(LocalDateTime) 绑定的话这里会差 8 小时。
    assertThat(row.platformStock().latestCollectedAt().toInstant())
        .isEqualTo(collected.toInstant());
    assertThat(row.platformStock().latestCollectedAt().getOffset())
        .isEqualTo(collected.getOffset());

    // 2) localTime 是「**现在**在这个仓库当地几点」，不是采集时刻 ——
    //    两者是不同的问题，混为一谈会让测试只在一个固定时刻成立。
    var nowInstant = java.time.Instant.now();
    var expected = ZonedDateTime.ofInstant(nowInstant, ZoneId.of("America/Los_Angeles"));
    var formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    assertThat(row.localTime()).isEqualTo(expected.toLocalDateTime().format(formatter));
    // 且确实按仓库时区算，不是拿系统时区（东八区）凑的。
    assertThat(row.localTime())
        .isNotEqualTo(
            ZonedDateTime.ofInstant(nowInstant, ZoneId.of("Asia/Shanghai"))
                .toLocalDateTime()
                .format(formatter));
  }
}
