package com.starcon.warehouse;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 8.4.1.1 组装单集成测试。
 *
 * <p><b>本测试最要紧的是 {@code 不变量} 一节</b>：蓝图要求「按 BOM 领用组件、消耗组件库存、
 * 产生成品 SKU 库存，同一事务完成」，落成两条业务不变量：
 *
 * <ul>
 *   <li>I1：组件出库<b>分录</b>之和 = 按 BOM 展开的应领量之和；
 *   <li>I2：成品入库量 = 组装数量。
 * </ul>
 *
 * <p>所以除了「走服务层时成立」，还专门用**绕过服务层直连 SQL** 的写法去证明
 * 「只入成品不扣组件」「少扣组件」同样被 V103 的守卫触发器挡下 —— 否则那条不变量
 * 只是应用层的一行 if，绕过服务就能造出账实不符的记录。
 *
 * <p>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AssemblyOrderIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new java.util.concurrent.ConcurrentHashMap<>();
  static final List<String> allPermissions =
      java.util.stream.Stream.of("assembly")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "warehouse." + p + "." + op))
          .toList();

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-assembly-test-" + tenant + "-" + ordinal).getBytes());
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
            byte[] body = json.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only",
          actor(user, a, null, List.of(grant("ALL", List.of("warehouse.assembly.read")))));
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

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  @Autowired AssemblyOrderService assembly;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

  /**
   * warehouse 服务<b>没有</b> {@code TenantTransactions} Bean（它的租户绑定点只有
   * {@link AccessService#run} 一处）。这里按同一个类、同一个绑定语义就地构造一个，
   * 供「连接池归还后不串租户」断言使用 —— 不给生产代码新增第二条绑定路径。
   */
  TenantTransactions tenants() {
    return new TenantTransactions(
        sql, new org.springframework.transaction.support.TransactionTemplate(transactionManager));
  }

  AccessService.Access actorA;
  AccessService.Access actorB;

  final UUID leA = idFor(a, 1),
      skuCompA = idFor(a, 2),
      skuCompB = idFor(a, 3),
      skuOutA = idFor(a, 4),
      whA = idFor(a, 5),
      qSellA = idFor(a, 6),
      locA1 = idFor(a, 8),
      locA2 = idFor(a, 9);
  final UUID leB = idFor(b, 1), skuCompB2 = idFor(b, 2), whB = idFor(b, 5), qSellB = idFor(b, 6),
      locB1 = idFor(b, 8);

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    // TRUNCATE 不触发行级触发器，以超级用户连接执行可绕过 FORCE RLS ——
    // 只用于测试夹具重建，不给应用角色开这个口子。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate assembly_order_component, assembly_order, inventory_serial_movement,"
              + " inventory_serial_operation, inventory_serial, stock_bucket, warehouse_location,"
              + " inventory_lot, inventory_entry, inventory_transaction, inventory_balance,"
              + " inventory_quality_status, warehouse, sku_ref, legal_entity_ref restart identity"
              + " cascade");
    }
    for (var tenant : List.of(a, b)) {
      access.run(
          actorFor(tenant),
          "warehouse.assembly.read",
          () -> {
            sql.execute(
                "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 1), tenant, "LE-1", "主体一");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 2), tenant, "COMP-A", "面料");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 3), tenant, "COMP-B", "拉链");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 4), tenant, "KIT-1", "成品礼盒套装");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone)"
                    + " values(?,?,?,?,?,?)",
                idFor(tenant, 5), tenant, "WH-SH", "上海仓", "CN", "Asia/Shanghai");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role)"
                    + " values(?,?,?,?,?)",
                idFor(tenant, 6), tenant, "SELLABLE", "可售", "SELLABLE");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,"
                    + "status,pickable) values(?,?,?,?,?,'STORAGE','ACTIVE',true)",
                idFor(tenant, 8), tenant, idFor(tenant, 5), "A-01", "组件库位");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,"
                    + "status,pickable) values(?,?,?,?,?,'STORAGE','ACTIVE',true)",
                idFor(tenant, 9), tenant, idFor(tenant, 5), "A-02", "成品库位");
            return null;
          });
    }
    stockA(skuCompA, locA1, "60");
    stockA(skuCompB, locA1, "500");
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "warehouse.assembly.read", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "warehouse.assembly.read", work);
  }

  <T> T asAU(java.util.function.Supplier<T> work) {
    return access.run(actorA, "warehouse.assembly.update", work);
  }

  /** 造一条可出库的库存：bucket 与 balance 同时写，守恒触发器会核对两者。 */
  void stockA(UUID skuId, UUID locationId, String onHand) {
    asA(
        () -> {
          UUID balance = UUID.randomUUID();
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
              balance, a, skuId, whA, leA, qSellA, new BigDecimal(onHand));
          sql.execute(
              "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,"
                  + "location_id,lot_id,quality_status_id,balance_id,on_hand,reserved,"
                  + "last_movement_at) values(?,?,?,?,?,?,null,?,?,?,0,?::timestamptz)",
              UUID.randomUUID(), a, leA, skuId, whA, locationId, qSellA, balance,
              new BigDecimal(onHand), OffsetDateTime.now());
          return null;
        });
  }

  BigDecimal onHandOf(UUID skuId, UUID locationId) {
    return asA(
        () -> {
          Record row =
              sql.fetchOne(
                  "select on_hand from stock_bucket where sku_id=? and location_id=?", skuId,
                  locationId);
          return row == null ? null : row.get("on_hand", BigDecimal.class);
        });
  }

  /** 标准建单：2 个组件，应领量分别为 quantity 与 quantity×10。 */
  AssemblyOrderModels.AssemblyOrderCreateInput createInput(
      UUID bomId, BigDecimal assemblyQuantity, BigDecimal compA, BigDecimal compB) {
    var components = new ArrayList<AssemblyOrderModels.AssemblyComponentInput>();
    components.add(
        new AssemblyOrderModels.AssemblyComponentInput(
            skuCompA, idFor(a, 30), compA, new BigDecimal("2"), new BigDecimal("0"), "面料", null));
    components.add(
        new AssemblyOrderModels.AssemblyComponentInput(
            skuCompB, idFor(a, 31), compB, new BigDecimal("1"), new BigDecimal("5"), "包材", null));
    return new AssemblyOrderModels.AssemblyOrderCreateInput(
        skuOutA, bomId, "BOM-KIT-1", idFor(a, 29), "v1", AssemblyOrderModels.SOURCE_DIRECT, null,
        "", whA, leA, assemblyQuantity, locA1, qSellA, locA2, qSellA, null, null, "组装单测试",
        components);
  }

  AssemblyOrderModels.AssemblyOrderCreated newOrder(
      BigDecimal assemblyQuantity, BigDecimal compA, BigDecimal compB) {
    return asAU(() -> assembly.create(createInput(UUID.randomUUID(), assemblyQuantity, compA, compB),
        user));
  }

  // ================================================================ 能力与迁移

  @Test
  @DisplayName("能力声明把跨服务 BOM 边界、虚拟套装边界与未交付下游如实报出来")
  void capabilities() {
    var caps = asA(() -> assembly.capabilities());
    assertThat(caps.refType()).isEqualTo("ASSEMBLY_ORDER");
    assertThat(caps.atomicComponentAndProductMovements()).isTrue();
    assertThat(caps.supportsDirectInitiation()).isTrue();
    assertThat(caps.bomBoundary()).contains("不 join、不建外键").contains("引用键");
    assertThat(caps.virtualKitBoundary()).contains("虚拟套装").contains("sku_ref");
    assertThat(caps.invariant()).contains("I1").contains("I2");
    assertThat(caps.upstreamModules())
        .anySatisfy(m -> assertThat(m.moduleId()).isEqualTo("3.5.2"));
    assertThat(caps.downstreamModules())
        .anySatisfy(
            m -> {
              assertThat(m.moduleId()).isEqualTo("8.4.2.1");
              assertThat(m.available()).isFalse();
            });
  }

  @Test
  @DisplayName("两张业务表都 tenant_id NOT NULL 且 relforcerowsecurity")
  void rlsIsForcedOnEveryTable() {
    for (String table : List.of("assembly_order", "assembly_order_component")) {
      Boolean forced =
          asA(
              () ->
                  sql.fetchOne(
                          "select relforcerowsecurity from pg_class where relname = ?", table)
                      .get(0, Boolean.class));
      assertThat(forced).as(table).isTrue();
      Boolean notNull =
          asA(
              () ->
                  sql.fetchOne(
                          "select is_nullable = 'NO' from information_schema.columns"
                              + " where table_name = ? and column_name = 'tenant_id'",
                          table)
                      .get(0, Boolean.class));
      assertThat(notNull).as(table + ".tenant_id NOT NULL").isTrue();
    }
  }

  // ================================================================ 建单

  @Test
  @DisplayName("建单落成草稿 + 组件快照，合计由触发器回写；**不动任何库存**")
  void createDoesNotTouchStock() {
    var created = newOrder(new BigDecimal("10"), new BigDecimal("5"), new BigDecimal("50"));

    assertThat(created.status()).isEqualTo("DRAFT");
    assertThat(created.orderCode()).startsWith("ASM-");
    assertThat(created.requiredComponentQuantity()).isEqualByComparingTo("55");
    assertThat(created.componentCount()).isEqualTo(2);
    // 建单是纯单据动作：组件一件没动，成品也没多出来。
    assertThat(onHandOf(skuCompA, locA1)).isEqualByComparingTo("60");
    assertThat(onHandOf(skuOutA, locA2)).isNull();

    // 组件明细带的是 BOM 的冻结快照，不是回查 catalog 得来的。
    var detail = asA(() -> assembly.detail(created.orderId()));
    assertThat(detail.components()).hasSize(2);
    assertThat(detail.components().get(0).requiredQuantity()).isEqualByComparingTo("5");
    assertThat(detail.components().get(0).issuedQuantity()).isEqualByComparingTo("0");
    assertThat(detail.components().get(0).bomComponentId()).isEqualTo(idFor(a, 30));
    // 草稿没有分录
    assertThat(detail.entries()).isEmpty();
  }

  @Test
  @DisplayName("sourceSystem 与 referenceId 的组合被显式校验（两个答案就是没有答案）")
  void sourceSystemAndReferenceMustAgree() {
    var directWithRef =
        new AssemblyOrderModels.AssemblyOrderCreateInput(
            skuOutA, UUID.randomUUID(), "BOM-1", idFor(a, 29), "v1",
            AssemblyOrderModels.SOURCE_DIRECT, UUID.randomUUID(), "PO-1", whA, leA,
            new BigDecimal("1"), locA1, qSellA, locA2, qSellA, null, null, null,
            List.of(
                new AssemblyOrderModels.AssemblyComponentInput(
                    skuCompA, null, BigDecimal.ONE, null, null, null, null)));
    assertThatThrownBy(() -> asAU(() -> assembly.create(directWithRef, user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不该带 referenceId");

    var processingWithoutRef =
        new AssemblyOrderModels.AssemblyOrderCreateInput(
            skuOutA, UUID.randomUUID(), "BOM-1", idFor(a, 29), "v1",
            AssemblyOrderModels.SOURCE_PROCESSING_ORDER, null, "PO-1", whA, leA,
            new BigDecimal("1"), locA1, qSellA, locA2, qSellA, null, null, null,
            List.of(
                new AssemblyOrderModels.AssemblyComponentInput(
                    skuCompA, null, BigDecimal.ONE, null, null, null, null)));
    assertThatThrownBy(() -> asAU(() -> assembly.create(processingWithoutRef, user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("必须给 referenceId");
  }

  @Test
  @DisplayName("虚拟套装进不来：成品 SKU 没有 sku_ref 行 → 404 并说明它该走 09 订单")
  void virtualKitCannotBeAssembled() {
    // catalog 的虚拟套装（bundle）不占 SKU 行，所以它在 warehouse 里没有 sku_ref 行。
    // 这里用一个从未登记过的 SKU 复现同一个形状。
    var input =
        new AssemblyOrderModels.AssemblyOrderCreateInput(
            idFor(a, 91), UUID.randomUUID(), "BOM-1", idFor(a, 29), "v1",
            AssemblyOrderModels.SOURCE_DIRECT, null, null, whA, leA, new BigDecimal("1"), locA1,
            qSellA, locA2, qSellA, null, null, null,
            List.of(
                new AssemblyOrderModels.AssemblyComponentInput(
                    skuCompA, null, BigDecimal.ONE, null, null, null, null)));
    assertThatThrownBy(() -> asAU(() -> assembly.create(input, user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("虚拟套装");
  }

  @Test
  @DisplayName("同一组件出现两次 → 拒绝（「这个组件要几件」无法回答）")
  void duplicateComponentIsRejected() {
    var input =
        new AssemblyOrderModels.AssemblyOrderCreateInput(
            skuOutA, UUID.randomUUID(), "BOM-1", idFor(a, 29), "v1",
            AssemblyOrderModels.SOURCE_DIRECT, null, null, whA, leA, new BigDecimal("1"), locA1,
            qSellA, locA2, qSellA, null, null, null,
            List.of(
                new AssemblyOrderModels.AssemblyComponentInput(
                    skuCompA, null, new BigDecimal("1"), null, null, null, null),
                new AssemblyOrderModels.AssemblyComponentInput(
                    skuCompA, null, new BigDecimal("2"), null, null, null, null)));
    assertThatThrownBy(() -> asAU(() -> assembly.create(input, user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("出现了两次");
  }

  @Test
  @DisplayName("不能被库存刻度精确表示的量被拒绝（不静默取整换取「不变量成立」）")
  void quantityMustBeExactAtStockScale() {
    // 5 位小数的应领量：向下取整等于吞材料、向上取整等于多领，两种都会让
    // 「组件出库 = 应领量」看起来成立而物料对不上。所以拒绝。
    assertThatThrownBy(() -> newOrder(new BigDecimal("10"), new BigDecimal("1.000005"),
            new BigDecimal("50")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("4 位小数");

    assertThatThrownBy(
            () ->
                asAU(
                    () ->
                        assembly.create(
                            createInput(UUID.randomUUID(), new BigDecimal("1.000005"),
                                new BigDecimal("5"), new BigDecimal("50")),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("4 位小数");
  }

  // ================================================================ 不变量（本模块最要紧的一组）

  @Test
  @DisplayName("I1+I2 成立：组件出库分录之和 = 应领量之和，成品入库量 = 组装数量")
  void invariantHoldsOnSuccess() {
    var order = newOrder(new BigDecimal("10"), new BigDecimal("5"), new BigDecimal("50"));
    var issued = asAU(() -> assembly.issue(order.orderId(), null, user));

    // I1：组件真的按 BOM 领走了
    assertThat(onHandOf(skuCompA, locA1)).isEqualByComparingTo("55");
    assertThat(onHandOf(skuCompB, locA1)).isEqualByComparingTo("450");
    assertThat(issued.issuedComponentQuantity()).isEqualByComparingTo("55");
    assertThat(issued.requiredComponentQuantity()).isEqualByComparingTo("55");
    assertThat(issued.issuedComponentQuantity())
        .isEqualByComparingTo(issued.requiredComponentQuantity());
    // I2：成品真的按组装数量入了库
    assertThat(onHandOf(skuOutA, locA2)).isEqualByComparingTo("10");
    assertThat(issued.inboundProductQuantity()).isEqualByComparingTo("10");
    assertThat(issued.inboundProductQuantity()).isEqualByComparingTo(issued.assemblyQuantity());

    // 组件出库 N 条负向分录 + 成品入库 1 条正向分录，挂在同一条 transaction 下。
    assertThat(issued.entryCount()).isEqualTo(3);
    assertThat(issued.entries()).filteredOn(e -> "COMPONENT_OUT".equals(e.direction())).hasSize(2);
    assertThat(issued.entries()).filteredOn(e -> "PRODUCT_IN".equals(e.direction())).hasSize(1);
    assertThat(
            issued.entries().stream()
                .filter(e -> "PRODUCT_IN".equals(e.direction()))
                .findFirst()
                .orElseThrow()
                .skuId())
        .isEqualTo(skuOutA);

    // 明细的实发量逐行等于应领量（行级 CHECK 的第二道证明）
    var detail = asA(() -> assembly.detail(order.orderId()));
    assertThat(detail.order().status()).isEqualTo("COMPLETED");
    assertThat(detail.components())
        .allSatisfy(c -> assertThat(c.issuedQuantity()).isEqualByComparingTo(c.requiredQuantity()));
  }

  @Test
  @DisplayName("同一事务：组件不足 → 整体回滚（成品没入库、组件没被扣、不留半截分录）")
  void issueIsAtomic() {
    var order = newOrder(new BigDecimal("10"), new BigDecimal("5"), new BigDecimal("9999"));
    assertThatThrownBy(() -> asAU(() -> assembly.issue(order.orderId(), null, user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("出库失败");

    assertThat(onHandOf(skuCompA, locA1)).isEqualByComparingTo("60");
    assertThat(onHandOf(skuOutA, locA2)).isNull();
    var detail = asA(() -> assembly.detail(order.orderId()));
    assertThat(detail.order().status()).isEqualTo("DRAFT");
    assertThat(detail.entries()).isEmpty();
    assertThat(detail.components()).allSatisfy(
        c -> assertThat(c.issuedQuantity()).isEqualByComparingTo("0"));
  }

  @Test
  @DisplayName("不变量在数据库层：绕过服务层只入成品不扣组件 → 守卫触发器拒绝")
  void invariantIsEnforcedByDatabase() {
    var order = newOrder(new BigDecimal("10"), new BigDecimal("5"), new BigDecimal("50"));
    var orderId = order.orderId();

    // 直连 SQL 造一条「只有成品入库、没有组件出库」的分录组，然后强行置为 COMPLETED。
    assertThatThrownBy(
            () ->
                asAU(
                    () -> {
                      UUID tx = UUID.randomUUID();
                      OffsetDateTime at = OffsetDateTime.now();
                      // 成品还没有余额行（这张单从没执行过），先造一条 —— 绕过服务层正是为了
                      // 造出「只有成品入库、组件一条没扣」这种服务层不可能产生的账。
                      UUID balance = UUID.randomUUID();
                      sql.execute(
                          "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,"
                              + "legal_entity_id,quality_status_id,on_hand,reserved)"
                              + " values(?,?,?,?,?,?,0,0)",
                          balance, a, skuOutA, whA, leA, qSellA);
                      sql.execute(
                          "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,"
                              + "occurred_at,actor_id,notes) values(?,?,'ADJUSTMENT',?,?,"
                              + "?::timestamptz,?,'')",
                          tx, a, AssemblyOrderModels.REF_TYPE, orderId, at, user);
                      sql.execute(
                          "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,"
                              + "on_hand_delta,reserved_delta) values(?,?,?,?,?,0)",
                          UUID.randomUUID(), a, tx, balance, new BigDecimal("10"));
                      sql.execute(
                          "update assembly_order set status='COMPLETED',"
                              + "inventory_transaction_id=?,inbound_product_quantity=?,"
                              + "issued_at=?::timestamptz,issued_by=? where id=?",
                          tx, new BigDecimal("10"), at, user, orderId);
                      return null;
                    }))
        .hasMessageContaining("I1");

    // 单子仍是草稿，没有被标成完成。
    assertThat(asA(() -> assembly.detail(orderId)).order().status()).isEqualTo("DRAFT");
  }

  @Test
  @DisplayName("不变量在数据库层：0 组件的组装单不能被标记完成（凭空变出成品）")
  void emptyOrderCannotBeCompleted() {
    // 直接插一张 0 组件的单（明细表 FK + 行级 CHECK 都不拦 0 行），
    // 然后置 COMPLETED —— 守卫触发器必须以「没有任何组件明细」拒掉。
    assertThatThrownBy(
            () ->
                asAU(
                    () -> {
                      UUID id = UUID.randomUUID();
                      OffsetDateTime at = OffsetDateTime.now();
                      UUID tx = UUID.randomUUID();
                      sql.execute(
                          "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,"
                              + "occurred_at,actor_id,notes) values(?,?,'ADJUSTMENT',?,?,"
                              + "?::timestamptz,?,'')",
                          tx, a, AssemblyOrderModels.REF_TYPE, id, at, user);
                      sql.execute(
                          "insert into assembly_order(id,tenant_id,order_code,product_sku_id,"
                              + "product_sku_code,bom_id,bom_code,bom_version_id,bom_version_no,"
                              + "source_system,warehouse_id,legal_entity_id,assembly_quantity,"
                              + "status,outbound_location_id,outbound_quality_status_id,"
                              + "inbound_location_id,inbound_quality_status_id,created_by)"
                              + " values(?,?,'ASM-999999',?,?,?,?,?,?,'DIRECT',?,?,?,'DRAFT',?,?,?,?,?)",
                          id, a, skuOutA, "KIT-1", UUID.randomUUID(), "BOM-1", idFor(a, 29),
                          "v1", whA, leA, new BigDecimal("5"), locA1, qSellA, locA2, qSellA, user);
                      // 分录与入库量先备好，再强行置 COMPLETED —— 只剩「0 组件」这一个变量。
                      sql.execute(
                          "update assembly_order set inventory_transaction_id=?,"
                              + "inbound_product_quantity=?,issued_at=?::timestamptz,issued_by=?"
                              + " where id=?",
                          tx, new BigDecimal("5"), at, user, id);
                      sql.execute(
                          "update assembly_order set status='COMPLETED' where id=?", id);
                      return null;
                    }))
        .hasMessageContaining("没有任何组件明细");
  }

  @Test
  @DisplayName("已完成的组装单不可再流转回草稿、不可改数（终态是凭据）")
  void completedOrderIsTerminal() {
    var order = newOrder(new BigDecimal("10"), new BigDecimal("5"), new BigDecimal("50"));
    asAU(() -> assembly.issue(order.orderId(), null, user));

    assertThatThrownBy(
            () ->
                asAU(
                    () -> {
                      sql.execute(
                          "update assembly_order set status='DRAFT' where id=?", order.orderId());
                      return null;
                    }))
        .hasMessageContaining("终态");

    assertThatThrownBy(
            () ->
                asAU(
                    () -> {
                      sql.execute(
                          "update assembly_order set assembly_quantity=99 where id=?",
                          order.orderId());
                      return null;
                    }))
        .hasMessageContaining("凭据");
  }

  @Test
  @DisplayName("重复执行同一张组装单 → 409（不会重复扣料重复入货）")
  void duplicateIssueIsRejected() {
    var order = newOrder(new BigDecimal("10"), new BigDecimal("5"), new BigDecimal("50"));
    asAU(() -> assembly.issue(order.orderId(), null, user));

    assertThatThrownBy(() -> asAU(() -> assembly.issue(order.orderId(), null, user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("只有草稿可以执行或取消");
    assertThat(onHandOf(skuCompA, locA1)).isEqualByComparingTo("55");
    assertThat(onHandOf(skuOutA, locA2)).isEqualByComparingTo("10");
  }

  // ================================================================ 取消

  @Test
  @DisplayName("取消草稿：状态转 CANCELLED，**不动库存**；已完成的不能取消")
  void cancelDraftDoesNotTouchStock() {
    var order = newOrder(new BigDecimal("10"), new BigDecimal("5"), new BigDecimal("50"));
    var cancelled =
        asAU(
            () ->
                assembly.cancel(order.orderId(), new AssemblyOrderModels.AssemblyOrderCancelInput("料没到"),
                    user));
    assertThat(cancelled.status()).isEqualTo("CANCELLED");
    assertThat(cancelled.cancelReason()).isEqualTo("料没到");
    assertThat(onHandOf(skuCompA, locA1)).isEqualByComparingTo("60");
    assertThat(cancelled.inventoryTransactionId()).isNull();

    // 已完成的取消不了：料已经出、货已经进，退回去是 8.4.2.1 拆解单的职责。
    var done = newOrder(new BigDecimal("2"), new BigDecimal("1"), new BigDecimal("10"));
    asAU(() -> assembly.issue(done.orderId(), null, user));
    assertThatThrownBy(
            () ->
                asAU(
                    () ->
                        assembly.cancel(done.orderId(),
                            new AssemblyOrderModels.AssemblyOrderCancelInput("不做了"), user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("只有草稿可以执行或取消");
  }

  // ================================================================ 时间戳

  @Test
  @DisplayName("timestamptz 写入后偏移零漂移（断言写回的值，不是「没抛异常」）")
  void timestampRoundTripsWithoutDrift() {
    var order = newOrder(new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("10"));
    var issued = asAU(() -> assembly.issue(order.orderId(), null, user));
    OffsetDateTime written =
        asA(
            () ->
                sql.fetchOne(
                        "select issued_at from assembly_order where id=?", order.orderId())
                    .get(0, OffsetDateTime.class));
    OffsetDateTime readBack = OffsetDateTime.parse(issued.occurredAt());
    assertThat(written.toInstant()).isEqualTo(readBack.toInstant());
    assertThat(written.getOffset()).isEqualTo(readBack.getOffset());
  }

  // ================================================================ 列表与筛选

  @Test
  @DisplayName("列表：真分页 + 真筛选 + 排序白名单 + 汇总（汇总不被明细放大）")
  void pageIsRealPagingAndFiltering() {
    var first = newOrder(new BigDecimal("10"), new BigDecimal("1"), new BigDecimal("10"));
    asAU(() -> assembly.issue(first.orderId(), null, user));
    var second = newOrder(new BigDecimal("4"), new BigDecimal("2"), new BigDecimal("20"));
    asAU(() -> assembly.cancel(second.orderId(),
        new AssemblyOrderModels.AssemblyOrderCancelInput("不做了"), user));
    newOrder(new BigDecimal("7"), new BigDecimal("3"), new BigDecimal("30"));

    var all = asA(() -> assembly.page(null, null, null, null, null, null, "CREATED_AT", 0, 50));
    assertThat(all.total()).isEqualTo(3);
    assertThat(all.items()).hasSize(3);
    assertThat(all.draftCount()).isEqualTo(1);
    assertThat(all.completedCount()).isEqualTo(1);
    assertThat(all.cancelledCount()).isEqualTo(1);
    // 21 = 10 + 4 + 7：若汇总误 join 明细，会被组件行数放大成 63。
    assertThat(all.totalAssemblyQuantity()).isEqualByComparingTo("21");
    // 66 = (1+10) + (2+20) + (3+30)：若汇总误 join 明细，会被组件行数放大成 132。
    assertThat(all.totalRequiredComponentQuantity()).isEqualByComparingTo("66");

    // 状态筛选
    var completed = asA(() -> assembly.page("COMPLETED", null, null, null, null, null, null, 0, 50));
    assertThat(completed.total()).isEqualTo(1);
    assertThat(completed.items().get(0).orderCode()).isEqualTo(first.orderCode());
    assertThat(completed.totalAssemblyQuantity()).isEqualByComparingTo("10");

    // 关键词筛选（命中单号 / BOM 编码 / 成品编码）
    assertThat(asA(() -> assembly.page(null, null, null, null, null, first.orderCode(), null, 0, 50))
            .total()).isEqualTo(1);
    assertThat(asA(() -> assembly.page(null, null, null, null, null, "KIT-1", null, 0, 50)).total())
        .isEqualTo(3);

    // 真分页：size=1 时 total 仍是 3，items 只有 1 条
    var page0 = asA(() -> assembly.page(null, null, null, null, null, null, "ORDER_CODE", 0, 1));
    var page1 = asA(() -> assembly.page(null, null, null, null, null, null, "ORDER_CODE", 1, 1));
    var page2 = asA(() -> assembly.page(null, null, null, null, null, null, "ORDER_CODE", 2, 1));
    assertThat(page0.items()).hasSize(1);
    assertThat(page1.items()).hasSize(1);
    assertThat(page2.items()).hasSize(1);
    assertThat(page0.total()).isEqualTo(3);
    assertThat(
            List.of(
                    page0.items().get(0).orderCode(),
                    page1.items().get(0).orderCode(),
                    page2.items().get(0).orderCode()))
        .doesNotHaveDuplicates();
    // 超上限的 size 被压回 200 而不是报错（真分页，不是假分页）
    assertThat(asA(() -> assembly.page(null, null, null, null, null, null, null, 0, 9999)).size())
        .isEqualTo(200);
  }

  @Test
  @DisplayName("排序键走白名单（不接受任意排序键，避免拼进 SQL）")
  void sortIsWhitelisted() {
    assertThatThrownBy(
            () -> asA(() -> assembly.page(null, null, null, null, null, null, "id; drop table", 0, 10)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的排序键");
    assertThatThrownBy(
            () -> asA(() -> assembly.page("SHIPPED", null, null, null, null, null, null, 0, 10)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的状态");
    assertThatThrownBy(() -> asA(() -> assembly.page(null, null, null, "SUPPLIER", null, null, null,
            0, 10)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的来源");
  }

  // ================================================================ 多租户三断言（AGENTS.md 硬要求）

  @Test
  @DisplayName("多租户断言 1：A 租户看不见 B 租户的组装单")
  void tenantACannotSeeTenantB() {
    // B 租户自己建一张单并执行
    AccessService.Access savedB = actorB;
    var orderB =
        access.run(
            savedB,
            "warehouse.assembly.update",
            () ->
                assembly.create(
                    new AssemblyOrderModels.AssemblyOrderCreateInput(
                        idFor(b, 4), UUID.randomUUID(), "BOM-B", idFor(b, 29), "v1",
                        AssemblyOrderModels.SOURCE_DIRECT, null, null, whB, idFor(b, 1),
                        new BigDecimal("3"), locB1, qSellB, idFor(b, 9), qSellB, null, null, null,
                        List.of(
                            new AssemblyOrderModels.AssemblyComponentInput(
                                idFor(b, 2), null, new BigDecimal("2"), null, null, null, null))),
                    user));

    assertThat(asA(() -> assembly.page(null, null, null, null, null, null, null, 0, 50)).total())
        .isZero();
    assertThatThrownBy(() -> asA(() -> assembly.detail(orderB.orderId())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或无权查看");
    // 按 B 的引用键筛，A 依然筛不出来
    assertThat(asA(() -> assembly.page(null, null, null, null, null, null, null, 0, 50)).items())
        .isEmpty();
    // B 自己看得到
    assertThat(asB(() -> assembly.detail(orderB.orderId())).order().orderCode())
        .isEqualTo(orderB.orderCode());
  }

  @Test
  @DisplayName("多租户断言 2：伪造 X-Tenant-Id 头无效（端点只认已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    var order = newOrder(new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("10"));
    asAU(() -> assembly.issue(order.orderId(), null, user));

    // 带伪造的 B 租户头访问：身份仍由 Bearer 决定，落到 A 租户，读到的是 A 的数据。
    http.perform(
            get("/warehouse/assembly-orders/" + order.orderId())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.order.orderId").value(order.orderId().toString()));

    // 伪造租户头也拿不到 B 的单子（造一个只存在于 B 租户的 productSkuId 也不可见）
    http.perform(
            get("/warehouse/assembly-orders")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString())
                .param("productSkuId", idFor(b, 4).toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
  }

  @Test
  @DisplayName("多租户断言 3：连接池归还后不串租户（池压到 1，20 轮往返）")
  void connectionPoolDoesNotLeakTenant() {
    TenantTransactions tenants = tenants();
    var order = newOrder(new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("10"));
    asAU(() -> assembly.issue(order.orderId(), null, user));

    for (int i = 0; i < 20; i++) {
      assertThat(asA(() -> assembly.detail(order.orderId()).order().orderCode()))
          .isEqualTo(order.orderCode());
      // B 租户事务内看不见 A 的组装单
      assertThat(countOrdersUnder(b)).isZero();
      // 事务外（没有任何租户上下文）同样一行都看不见
      assertThat(countOrdersWithoutTenant()).isZero();
    }
    assertThat(tenants).isNotNull();
  }

  long countOrdersUnder(UUID tenant) {
    Long count =
        tenants()
            .run(
                tenant,
                () ->
                    sql.fetchOne("select count(*) from assembly_order").get(0, Long.class));
    return count == null ? 0L : count;
  }

  long countOrdersWithoutTenant() {
    Long count = sql.fetchOne("select count(*) from assembly_order").get(0, Long.class);
    return count == null ? 0L : count;
  }

  // ================================================================ HTTP 契约

  @Test
  @DisplayName("HTTP 契约：401 未认证 / 403 只读 / 422 数量非法 / 404 不存在")
  void httpContract() throws Exception {
    http.perform(get("/warehouse/assembly-orders/capabilities"))
        .andExpect(status().isUnauthorized());

    http.perform(get("/warehouse/assembly-orders/capabilities")
            .header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk());

    // 只读身份不能建单
    http.perform(
            post("/warehouse/assembly-orders")
                .header("Authorization", "Bearer read-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(createInput(UUID.randomUUID(), BigDecimal.ONE,
                    BigDecimal.ONE, BigDecimal.ONE))))
        .andExpect(status().isForbidden());

    // 数量非正：Bean Validation 直接拒
    http.perform(
            post("/warehouse/assembly-orders")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        createInput(UUID.randomUUID(), BigDecimal.ZERO, new BigDecimal("1"),
                            new BigDecimal("1")))))
        .andExpect(status().isBadRequest());

    // 未存在
    http.perform(
            get("/warehouse/assembly-orders/" + UUID.randomUUID())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
  }
}