package com.starcon.warehouse;

import static com.starcon.warehouse.WarehouseModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
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
 * 7.1.1.1 库存总览：六个数量维度的聚合口径、总览↔明细核对、超储与零库存识别、
 * 跨服务筛选的显式拒绝，以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InventoryOverviewIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final java.util.Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final List<String> allPermissions =
      java.util.stream.Stream.of("inventory")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "warehouse." + p + "." + op))
          .toList();

  // 参照数据 ID 按租户派生：legal_entity_ref / sku_ref / warehouse 的主键是**全局** uuid
  // （不是复合主键），两个租户不能用同一批固定 UUID —— 那会让租户 B 的参照数据插入
  // 直接撞租户 A 的主键，测试失败的原因与"租户隔离"毫无关系。
  // 断言只关心租户 A，所以下面这一组常量固定为租户 A 的取值。
  static final UUID leA = idFor(a, 1);
  static final UUID skuA = idFor(a, 2);
  static final UUID whSh = idFor(a, 3);
  static final UUID whSz = idFor(a, 4);
  static final UUID qSellable = idFor(a, 5);
  static final UUID qPending = idFor(a, 6);
  static final UUID qDefective = idFor(a, 7);

  /** 由租户与序号确定性地派生一个 UUID：同一租户每次跑得到同一批 ID，便于断言与排错。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-warehouse-test-" + tenant + "-" + ordinal).getBytes());
  }

  static AccessService.Access actor(
      UUID uid, UUID tenant, UUID dept, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        dept,
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
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions))));
      // 只有读权限：阈值写端点必须 403。
      actors.put(
          "Bearer reader",
          actor(
              user, a, null, List.of(grant("ALL", List.of("warehouse.inventory.read")))));
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

  @Autowired InventoryOverviewService service;
  @Autowired WarehouseReferenceService references;
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
    // 用 TRUNCATE 而不是 DELETE 清场：inventory_entry / inventory_transaction 是 append-only，
    // DELETE 会被触发器拒绝（这正是要测的行为），而测试清场必须能跑。
    // TRUNCATE 不触发行级触发器，且以超级用户连接执行可绕过 FORCE RLS —— 它只用于
    // 测试夹具重建，不会给应用角色开这个口子（应用角色 warehouse_app 仍只有 CRUD）。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate inventory_entry, inventory_transaction, inventory_transfer, inventory_balance,"
              + " inventory_policy, inventory_quality_status, warehouse, sku_ref, legal_entity_ref"
              + " restart identity cascade");
    }
    for (var tenant : List.of(a, b)) {
      access.run(actorFor(tenant), "warehouse.inventory.read", () -> {
        sql.execute(
            "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
            idFor(tenant, 1), tenant, "LE-1", "主体一");
        sql.execute(
            "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
            idFor(tenant, 2), tenant, "SKU-1", "商品一");
        sql.execute(
            "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
            idFor(tenant, 3), tenant, "WH-SH", "上海仓", "CN", "Asia/Shanghai");
        sql.execute(
            "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
            idFor(tenant, 4), tenant, "WH-SZ", "深圳仓", "CN", "Asia/Shanghai");
        sql.execute(
            "insert into inventory_quality_status(id,tenant_id,code,name,role) values"
                + "(?,?,?,?,?),(?,?,?,?,?),(?,?,?,?,?)",
            idFor(tenant, 5), tenant, "SELLABLE", "可售", "SELLABLE",
            idFor(tenant, 6), tenant, "PENDING", "待检", "PENDING_INSPECTION",
            idFor(tenant, 7), tenant, "DEFECT", "残次", "DEFECTIVE");
        return null;
      });
    }
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  /** 写一行余额（租户 A）。 */
  void balance(UUID warehouse, UUID quality, String onHand, String reserved) {
    access.run(actorA, "warehouse.inventory.read", () -> {
      sql.execute(
          "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
              + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,?)",
          UUID.randomUUID(), a, skuA, warehouse, leA, quality, new BigDecimal(onHand),
          new BigDecimal(reserved));
      return null;
    });
  }

  /** 一行在途：从上海仓发往深圳仓。 */
  void inTransit(String quantity) {
    access.run(actorA, "warehouse.inventory.read", () -> {
      sql.execute(
          "insert into inventory_transfer(id,tenant_id,sku_id,legal_entity_id,from_warehouse_id,"
              + "to_warehouse_id,quality_status_id,quantity,shipped_at) values(?,?,?,?,?,?,?,?,now())",
          UUID.randomUUID(), a, skuA, leA, whSh, whSz, qSellable, new BigDecimal(quantity));
      return null;
    });
  }

  /** 一条阈值策略。 */
  void policy(String scopeType, String scopeKey, String maxOnHand) {
    access.run(actorA, "warehouse.inventory.read", () -> {
      sql.execute(
          "insert into inventory_policy(id,tenant_id,scope_type,scope_key,max_on_hand) values(?,?,?,?,?)",
          UUID.randomUUID(), a, scopeType, scopeKey, new BigDecimal(maxOnHand));
      return null;
    });
  }

  InventoryOverview overviewOf(AccessService.Access who) {
    return access.run(who, "warehouse.inventory.read", () -> service.overview(new OverviewQuery(
        null, null, null, null, null, null, null, null, null, null, null, null)));
  }

  // ================================================================ 六个数量维度

  @Test
  void 六个数量维度按迁移口径聚合() {
    balance(whSh, qSellable, "100", "30");
    balance(whSh, qPending, "20", "0");
    balance(whSh, qDefective, "5", "0");
    balance(whSz, qSellable, "7", "0");
    inTransit("12");

    var o = overviewOf(actorA);
    var s = o.summary();

    // 在仓实物 = 132；在手 = 在仓实物 + 在途 12 = 144
    assertThat(s.onHandAtWarehouse()).isEqualByComparingTo("132");
    assertThat(s.inTransit()).isEqualByComparingTo("12");
    assertThat(s.onHand()).isEqualByComparingTo("144");
    // 预占只在可售桶上
    assertThat(s.reserved()).isEqualByComparingTo("30");
    // 可用 = 在仓实物 − 预占，**不含在途**（未到货的货拿去预占就是超卖）
    assertThat(s.available()).isEqualByComparingTo("102");
    assertThat(s.sellable()).isEqualByComparingTo("107");
    assertThat(s.pendingInspection()).isEqualByComparingTo("20");
    assertThat(s.defective()).isEqualByComparingTo("5");
    assertThat(s.rowCount()).isEqualTo(4);
  }

  @Test
  void 可用量只有一个来源_生成列_应用层无法写出漂移值() {
    balance(whSh, qSellable, "100", "30");
    // 直接读 DB：available 恒等于 on_hand − reserved，与应用层无关。
    var row =
        access.run(actorA, "warehouse.inventory.read", () -> sql.fetchOne(
            "select on_hand,reserved,available from inventory_balance where tenant_id=?", a));
    assertThat(row.get("available", BigDecimal.class))
        .isEqualByComparingTo(
            row.get("on_hand", BigDecimal.class).subtract(row.get("reserved", BigDecimal.class)));
  }

  @Test
  void 守恒式成立_在手等于可售加待检加残次加在途() {
    balance(whSh, qSellable, "100", "30");
    balance(whSh, qPending, "20", "0");
    balance(whSh, qDefective, "5", "0");
    inTransit("12");

    var s = overviewOf(actorA).summary();
    assertThat(s.balanceCheck().availableMatches()).isTrue();
    assertThat(s.balanceCheck().onHandMatches()).isTrue();
  }

  // ================================================================ 总览 ↔ 明细核对

  @Test
  void 总览合计等于明细逐行求和_每个数量维度分别断言() {
    balance(whSh, qSellable, "100", "30");
    balance(whSh, qPending, "20", "0");
    balance(whSh, qDefective, "5", "0");
    balance(whSz, qSellable, "7", "0");
    inTransit("12");

    // 取**全部**行（不限分页），逐行求和必须等于合计。
    var o = overviewOf(actorA);
    var rows = o.rows();
    var s = o.summary();

    assertThat(rows).isNotEmpty();
    assertThat(sumRows(rows, r -> r.onHand())).isEqualByComparingTo(s.onHandAtWarehouse());
    assertThat(sumRows(rows, r -> r.reserved())).isEqualByComparingTo(s.reserved());
    assertThat(sumRows(rows, r -> r.available())).isEqualByComparingTo(s.available());
    assertThat(sumRows(rows, r -> r.sellable())).isEqualByComparingTo(s.sellable());
    assertThat(sumRows(rows, r -> r.pendingInspection())).isEqualByComparingTo(s.pendingInspection());
    assertThat(sumRows(rows, r -> r.defective())).isEqualByComparingTo(s.defective());
    assertThat(sumRows(rows, r -> r.inTransit())).isEqualByComparingTo(s.inTransit());
    // 行级可用之和 == 在仓实物 − 预占：说明没有第三处"可用量"来源。
    assertThat(sumRows(rows, r -> r.available()))
        .isEqualByComparingTo(
            sumRows(rows, r -> r.onHand()).subtract(sumRows(rows, r -> r.reserved())));
  }

  @Test
  void 分页不改变合计_合计始终是全量而不是当前页之和() {
    // 余额唯一键是 (tenant,sku,warehouse,legal_entity,quality_status)，所以 5 行要落在
    // 不同的仓/质量状态组合上，不能拿同一个组合连插 5 次。
    balance(whSh, qSellable, "10", "0");
    balance(whSh, qPending, "10", "0");
    balance(whSh, qDefective, "10", "0");
    balance(whSz, qSellable, "10", "0");
    balance(whSz, qPending, "10", "0");
    balance(whSz, qDefective, "20", "0");

    var page0 =
        access.run(actorA, "warehouse.inventory.read", () -> service.overview(
            new OverviewQuery(null, null, null, null, null, null, null, null, null, "ON_HAND", 0, 2)));
    var page1 =
        access.run(actorA, "warehouse.inventory.read", () -> service.overview(
            new OverviewQuery(null, null, null, null, null, null, null, null, null, "ON_HAND", 1, 2)));

    assertThat(page0.rows()).hasSize(2);
    assertThat(page1.rows()).hasSize(2);
    // 合计不受分页影响：否则守恒式只在第一页成立。
    assertThat(page0.summary().onHandAtWarehouse()).isEqualByComparingTo("70");
    assertThat(page1.summary().onHandAtWarehouse()).isEqualByComparingTo("70");    // 两页的行不重叠：决胜键 id 生效。
    assertThat(page0.rows().stream().map(OverviewRow::balanceId))
        .doesNotContainAnyElementsOf(page1.rows().stream().map(OverviewRow::balanceId).toList());
  }

  @Test
  void 行形状就是明细的bucket形状_可被7_1_1_2直接消费() {
    balance(whSh, qSellable, "100", "30");
    var row = overviewOf(actorA).rows().get(0);

    // 法人货权 × SKU × 仓库 × 质量状态 四个维度都在行上
    assertThat(row.skuId()).isEqualTo(skuA);
    assertThat(row.warehouseId()).isEqualTo(whSh);
    assertThat(row.legalEntityId()).isEqualTo(leA);
    assertThat(row.qualityStatusId()).isEqualTo(qSellable);
    assertThat(row.skuCode()).isEqualTo("SKU-1");
    assertThat(row.qualityRole()).isEqualTo(QualityRole.SELLABLE);
    // 下钻契约：带全部行级维度
    assertThat(row.detailHref())
        .contains("skuId=" + skuA)
        .contains("warehouseId=" + whSh)
        .contains("legalEntityId=" + leA)
        .contains("qualityStatusId=" + qSellable);
  }

  @Test
  void 分组统计与明细合计一致_图和表不会各说各话() {
    balance(whSh, qSellable, "100", "30");
    balance(whSh, qPending, "20", "0");
    balance(whSz, qSellable, "7", "0");
    inTransit("12");

    var o = overviewOf(actorA);
    var byWarehouse = o.groups().stream().filter(g -> g.dimension().equals("WAREHOUSE")).toList();
    // 每个仓库分组之和 == 总合计
    assertThat(sumGroups(byWarehouse, OverviewGroup::onHand))
        .isEqualByComparingTo(o.summary().onHand());
    assertThat(sumGroups(byWarehouse, OverviewGroup::available))
        .isEqualByComparingTo(o.summary().available());
    var byRole = o.groups().stream().filter(g -> g.dimension().equals("QUALITY_ROLE")).toList();
    // 质量角色分组的"在手"同样含在途（在途挂在可售行上），所以对标的是 onHand 而不是
    // onHandAtWarehouse —— 分组口径必须与行级口径一致，否则图和表会差一个在途。
    assertThat(sumGroups(byRole, OverviewGroup::onHand))
        .isEqualByComparingTo(o.summary().onHand());
  }

  // ================================================================ 零库存与超储

  @Test
  void 零库存按available不大于零识别() {
    balance(whSh, qSellable, "10", "10"); // available=0 → 零库存
    balance(whSh, qPending, "5", "0"); // available=5 → 非零
    var o = overviewOf(actorA);
    assertThat(o.summary().zeroStockRowCount()).isEqualTo(1);
    assertThat(o.rows().stream().filter(OverviewRow::zeroStock).count()).isEqualTo(1);
  }

  @Test
  void 未设阈值与未超储被如实区分_零和没查过含义不同() {
    // WH-SH 完全没有阈值 → "未设阈值"；WH-SZ 阈值 50 而在手 100 → 超储。
    // 两行都必须出现在合计里，但计数上它们是**互斥**的两类，不能混成同一个 0。
    balance(whSh, qSellable, "5", "0");
    balance(whSz, qSellable, "100", "0");
    policy("WAREHOUSE", whSz.toString(), "50");

    var o = overviewOf(actorA);
    var s = o.summary();
    assertThat(s.rowCount()).isEqualTo(2);
    assertThat(s.overstockRowCount()).isEqualTo(1);
    // 未设阈值那一行**不算超储**，但也不能算"查过没超"，必须单独计一个数：
    // 0 与"没查过"含义不同，界面上必须分开显示。
    assertThat(s.thresholdUnsetRowCount()).isEqualTo(1);

    var overRow = o.rows().stream().filter(OverviewRow::overstock).findFirst().orElseThrow();
    assertThat(overRow.warehouseId()).isEqualTo(whSz);
    assertThat(overRow.thresholdConfigured()).isTrue();
    assertThat(overRow.maxOnHand()).isEqualByComparingTo("50");
    assertThat(overRow.policyScope()).isEqualTo("WAREHOUSE");

    var unsetRow = o.rows().stream()
        .filter(r -> r.warehouseId().equals(whSh)).findFirst().orElseThrow();
    assertThat(unsetRow.thresholdConfigured()).isFalse();
    assertThat(unsetRow.maxOnHand()).isNull();
    assertThat(unsetRow.overstock()).isFalse();
  }

  @Test
  void 未设阈值的行不会被误判成超储() {
    balance(whSh, qSellable, "999999", "0");
    var row = overviewOf(actorA).rows().get(0);
    assertThat(row.thresholdConfigured()).isFalse();
    assertThat(row.maxOnHand()).isNull();
    assertThat(row.overstock()).isFalse();
  }

  @Test
  void 阈值解析按SKU优先于仓库优先于默认() {
    balance(whSh, qSellable, "100", "0");
    policy("DEFAULT", "", "1000");
    policy("WAREHOUSE", whSh.toString(), "500");
    policy("SKU", skuA.toString(), "50");
    var row = overviewOf(actorA).rows().get(0);
    assertThat(row.maxOnHand()).isEqualByComparingTo("50");
    assertThat(row.policyScope()).isEqualTo("SKU");
    assertThat(row.overstock()).isTrue();
  }

  @Test
  void 超储与零库存可作为筛选条件() {
    balance(whSh, qSellable, "100", "0");
    balance(whSz, qSellable, "5", "5"); // available=0 → 零库存（预占满）
    policy("SKU", skuA.toString(), "50");

    var over = access.run(actorA, "warehouse.inventory.read", () -> service.overview(
        new OverviewQuery(null, null, null, null, null, null, null, null, true, null, 0, 50)));
    assertThat(over.rows()).isNotEmpty().allMatch(OverviewRow::overstock);
    // 只有在手 100 的那行超储（阈值 50），在手 5 的不超
    assertThat(over.rows()).hasSize(1);

    var zero = access.run(actorA, "warehouse.inventory.read", () -> service.overview(
        new OverviewQuery(null, null, null, null, null, null, null, true, null, null, 0, 50)));
    assertThat(zero.rows()).isNotEmpty().allMatch(OverviewRow::zeroStock);
    assertThat(zero.rows()).hasSize(1);
  }

  @Test
  void 阈值可读可写() {
    var saved =
        access.run(actorA, "warehouse.inventory.update", () -> references.savePolicy(
            null, new InventoryPolicyInput("WAREHOUSE", whSh.toString(), new BigDecimal("80"), null)));
    assertThat(saved.id()).isNotNull();
    var list = access.run(actorA, "warehouse.inventory.read", () -> references.policies("WAREHOUSE"));
    assertThat(list).hasSize(1);
    assertThat(list.get(0).maxOnHand()).isEqualByComparingTo("80");

    // 同一范围重复保存即更新，不会插出第二条
    access.run(actorA, "warehouse.inventory.update", () -> references.savePolicy(
        saved.id(), new InventoryPolicyInput("WAREHOUSE", whSh.toString(), new BigDecimal("90"), null)));
    assertThat(access.run(actorA, "warehouse.inventory.read", () -> references.policies("WAREHOUSE")))
        .hasSize(1);
  }

  @Test
  void 默认级策略必须空键_其余范围必须带键() {
    assertThatThrownBy(() -> access.run(actorA, "warehouse.inventory.update", () -> references.savePolicy(
        null, new InventoryPolicyInput("DEFAULT", "x", new BigDecimal("10"), null))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> access.run(actorA, "warehouse.inventory.update", () -> references.savePolicy(
        null, new InventoryPolicyInput("SKU", "", new BigDecimal("10"), null))))
        .isInstanceOf(IllegalArgumentException.class);
    // 没设上限 = 不参与超储判定，直接拒绝而不是存一条永不生效的策略
    assertThatThrownBy(() -> access.run(actorA, "warehouse.inventory.update", () -> references.savePolicy(
        null, new InventoryPolicyInput("SKU", skuA.toString(), null, null))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ================================================================ 跨服务筛选

  @Test
  void 未接入的上游筛选被显式拒绝_而不是返回空集() throws Exception {
    balance(whSh, qSellable, "10", "0");
    for (CatalogProjectionPort.Dimension d :
        new CatalogProjectionPort.Dimension[] {
          CatalogProjectionPort.Dimension.CATEGORY,
          CatalogProjectionPort.Dimension.BRAND,
          CatalogProjectionPort.Dimension.SHOP
        }) {
      assertThatThrownBy(() -> access.run(actorA, "warehouse.inventory.read", () -> service.overview(
          new OverviewQuery(
              null, null, null, null, List.of(UUID.randomUUID()), null, null, null, null, null, 0, 50))))
          .isInstanceOf(CatalogProjectionPort.DimensionUnavailableException.class);
    }
    http.perform(get("/warehouse/inventory/overview")
            .header("Authorization", "Bearer tenant-a")
            .param("categoryIds", UUID.randomUUID().toString()))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void 能力声明逐维度标注是否可用() {
    var caps = access.run(actorA, "warehouse.inventory.read", service::capabilities);
    assertThat(caps).hasSize(3);
    assertThat(caps).allMatch(d -> !d.available());
    assertThat(caps).allMatch(d -> d.owner() != null && !d.owner().isBlank());
  }

  // ================================================================ 本服务内的筛选

  @Test
  void 按仓库法人与关键字筛选() {
    balance(whSh, qSellable, "10", "0");
    balance(whSz, qSellable, "20", "0");
    var byWarehouse = access.run(actorA, "warehouse.inventory.read", () -> service.overview(
        new OverviewQuery(whSz, null, null, null, null, null, null, null, null, null, 0, 50)));
    assertThat(byWarehouse.rows()).hasSize(1);
    assertThat(byWarehouse.rows().get(0).onHand()).isEqualByComparingTo("20");

    var byKeyword = access.run(actorA, "warehouse.inventory.read", () -> service.overview(
        new OverviewQuery(null, null, null, "深圳", null, null, null, null, null, null, 0, 50)));
    assertThat(byKeyword.rows()).hasSize(1);
    assertThat(byKeyword.rows().get(0).warehouseCode()).isEqualTo("WH-SZ");

    var byEntity = access.run(actorA, "warehouse.inventory.read", () -> service.overview(
        new OverviewQuery(null, leA, null, null, null, null, null, null, null, null, 0, 50)));
    assertThat(byEntity.rows()).hasSize(2);
  }

  @Test
  void 非法排序键被拒绝_排序键不来自白名单就拼不进SQL() {
    assertThatThrownBy(() -> access.run(actorA, "warehouse.inventory.read", () -> service.overview(
        new OverviewQuery(null, null, null, null, null, null, null, null, null, "1;drop table", 0, 50))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ================================================================ 端点与权限

  @Test
  void 总览端点返回汇总与分组() throws Exception {
    balance(whSh, qSellable, "100", "30");
    balance(whSh, qPending, "20", "0");
    // 在途按**收货仓**归属，所以收货仓必须先有对应余额行，在途量才有地方挂 ——
    // 否则在途会显示成 0，而这不是"没有在途"，是"这批货还没有对应的收货仓余额行"。
    balance(whSz, qSellable, "7", "0");
    inTransit("12");
    http.perform(get("/warehouse/inventory/overview").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.onHandAtWarehouse").value(127))
        .andExpect(jsonPath("$.summary.inTransit").value(12))
        .andExpect(jsonPath("$.summary.onHand").value(139))
        .andExpect(jsonPath("$.summary.available").value(97))
        .andExpect(jsonPath("$.summary.balanceCheck.onHandMatches").value(true))
        .andExpect(jsonPath("$.rows.length()").value(3))
        // 仓库 2 + 法人 1 + 质量角色 2（可售/待检，残次无行不入图）+ SKU 1
        .andExpect(jsonPath("$.groups.length()").value(6));
  }

  @Test
  void 参照数据端点可读() throws Exception {
    http.perform(get("/warehouse/warehouses").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
    http.perform(get("/warehouse/legal-entities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].code").value("LE-1"));
    http.perform(get("/warehouse/quality-statuses").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3));
  }

  @Test
  void 无读权限不能写阈值() throws Exception {
    http.perform(post("/warehouse/inventory/policies")
            .header("Authorization", "Bearer reader")
            .contentType("application/json")
            .content(
                "{\"scopeType\":\"WAREHOUSE\",\"scopeKey\":\""
                    + whSh
                    + "\",\"maxOnHand\":10}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void 未认证一律401() throws Exception {
    http.perform(get("/warehouse/inventory/overview")).andExpect(status().isUnauthorized());
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    balance(whSh, qSellable, "100", "30");
    // 租户 B 有参照数据但没有余额 → 看到 0 行
    assertThat(overviewOf(actorA).rows()).hasSize(1);
    assertThat(overviewOf(actorB).rows()).isEmpty();
    assertThat(overviewOf(actorB).summary().onHandAtWarehouse()).isEqualByComparingTo("0");
    // 阈值同样隔离
    policy("SKU", skuA.toString(), "50");
    assertThat(overviewOf(actorA).rows().get(0).thresholdConfigured()).isTrue();
    assertThat(overviewOf(actorB).rows()).isEmpty();
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    balance(whSh, qSellable, "100", "0");
    // 浏览器伪造 X-Tenant-Id 指向 A，身份仍是 B → 只能看到 B 的（空）视图
    http.perform(get("/warehouse/inventory/overview")
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(0))
        .andExpect(jsonPath("$.summary.onHandAtWarehouse").value(0));
    // 反向：身份是 A 时伪造 B 也拿不到 B 的数据
    http.perform(get("/warehouse/inventory/overview")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1));
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    balance(whSh, qSellable, "100", "0");
    // 池大小为 1，同一条连接被反复借还：交替访问两个租户 20 轮。
    for (int i = 0; i < 20; i++) {
      var fa = overviewOf(actorA);
      var fb = overviewOf(actorB);
      assertThat(fa.rows()).hasSize(1);
      assertThat(fb.rows()).isEmpty();
      assertThat(fa.summary().onHandAtWarehouse()).isEqualByComparingTo("100");
      assertThat(fb.summary().onHandAtWarehouse()).isEqualByComparingTo("0");
    }
  }

  // ================================================================ DB 级兜底

  @Test
  void 超卖在DB层被拒绝_绕过应用层也不行() {
    balance(whSh, qSellable, "10", "0");
    assertThatThrownBy(
            () -> access.run(actorA, "warehouse.inventory.read", () -> sql.execute(
                "update inventory_balance set reserved=9999 where tenant_id=?", a)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void 非可售质量状态不允许被预占() {
    balance(whSh, qDefective, "10", "0");
    assertThatThrownBy(
            () -> access.run(actorA, "warehouse.inventory.read", () -> sql.execute(
                "update inventory_balance set reserved=1 where tenant_id=? and quality_status_id=?",
                a, qDefective)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void 在途记录的状态与时间戳必须一致() {
    assertThatThrownBy(
            () -> access.run(actorA, "warehouse.inventory.read", () -> sql.execute(
                "insert into inventory_transfer(id,tenant_id,sku_id,legal_entity_id,from_warehouse_id,"
                    + "to_warehouse_id,quality_status_id,quantity,shipped_at,received_at)"
                    + " values(?,?,?,?,?,?,?,?,now(),now())",
                UUID.randomUUID(), a, skuA, leA, whSh, whSz, qSellable, new BigDecimal("1"))))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void 库存分录是append_only() {
    // 分录挂在某条余额行上，所以先建一行余额（否则 balance_id 无从指起）
    balance(whSh, qSellable, "10", "0");
    var txn = UUID.randomUUID();
    var entry = UUID.randomUUID();
    access.run(actorA, "warehouse.inventory.read", () -> {
      sql.execute(
          "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,actor_id)"
              + " values(?,?,?,?,?,now(),?)",
          txn, a, "RECEIPT", "PURCHASE_RECEIPT", UUID.randomUUID(), user);
      var balanceId = sql.fetchOne("select id from inventory_balance where tenant_id=? limit 1", a)
          .get(0, UUID.class);
      sql.execute(
          "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta)"
              + " values(?,?,?,?,?)",
          entry, a, txn, balanceId, new BigDecimal("5"));
      return null;
    });
    // 改一条已落库的分录 = 直接毁掉余额的真相来源，DB 拒绝
    assertThatThrownBy(
            () -> access.run(actorA, "warehouse.inventory.read", () -> sql.execute(
                "update inventory_entry set on_hand_delta=1 where tenant_id=? and id=?", a, entry)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () -> access.run(actorA, "warehouse.inventory.read", () -> sql.execute(
                "delete from inventory_entry where tenant_id=? and id=?", a, entry)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void 跨服务不得建外键_sku只以逻辑ID存在() {
    // sku_ref 是本服务的引用登记；余额行上不 REFERENCES 任何对方表
    var fkCount = access.run(actorA, "warehouse.inventory.read", () -> sql.fetchOne(
        "select count(*) from information_schema.table_constraints"
            + " where constraint_type='FOREIGN KEY'"
            + " and table_name in ('inventory_balance','inventory_transfer')"
            + " and (constraint_name like '%sku%')"));
    assertThat(fkCount.get(0, Long.class)).isZero();
  }

  // ---------------------------------------------------------------- 工具

  /** 明细逐行求和：总览合计必须等于它，这是"总览只是聚合视图"的核对线。 */
  static BigDecimal sumRows(
      List<OverviewRow> rows, java.util.function.Function<OverviewRow, BigDecimal> money) {
    return rows.stream().map(money).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /** 分组求和：图上的每个数也必须能核对回合计。 */
  static BigDecimal sumGroups(
      List<OverviewGroup> groups, java.util.function.Function<OverviewGroup, BigDecimal> money) {
    return groups.stream().map(money).reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
