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
 * 8.4.2.1 拆解单集成测试。
 *
 * <p><b>本测试最要紧的两节</b>：
 *
 * <ol>
 *   <li><b>蓝图加粗句</b>：「拆解结果的组件质量状态由接收方决定，不能默认全部回可售。」 落成一条硬约束（去向逐行必填、服务层不提供默认值），所以有专门的用例证明
 *       <b>不填去向就是拒绝</b>，而不是「替你挑一个可售」。
 *   <li><b>不变量</b>：D1 成品出库分录 = 拆解数量；D2 组件入库分录之和 = 应回量之和； D3 成品不得出现在自己的组件明细里。三条都除了「走服务层时成立」，还用
 *       <b>绕过服务层直连 SQL</b> 的写法去证明同样被 V108 的守卫触发器挡下 —— 否则那三条只是应用层的一行 if，绕过服务就能造出账实不符的记录。
 * </ol>
 *
 * <p>还有一节是<b>与 8.4.1.1 组装单的成对可对账</b>：先组装再拆解，账上必须留下成对的分录 （那边消耗组件产出成品，这边消耗成品产出组件），两边数量必须能对上。
 *
 * <p>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。连接池固定为 1 条连接： 只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DisassemblyOrderIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final List<String> allPermissions =
      java.util.stream.Stream.of("disassembly")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "warehouse." + p + "." + op))
          .toList();

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-disassembly-test-" + tenant + "-" + ordinal).getBytes());
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
          actor(user, a, null, List.of(grant("ALL", List.of("warehouse.disassembly.read")))));
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

  @Autowired DisassemblyOrderService disassembly;
  @Autowired AssemblyOrderService assembly;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

  /**
   * warehouse 服务<b>没有</b> {@code TenantTransactions} Bean（它的租户绑定点只有 {@link AccessService#run}
   * 一处）。这里按同一个类、同一个绑定语义就地构造一个， 供「连接池归还后不串租户」断言使用 —— 不给生产代码新增第二条绑定路径。
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
      skuProductA = idFor(a, 4),
      whA = idFor(a, 5),
      qSellA = idFor(a, 6),
      qInspectA = idFor(a, 7),
      locA1 = idFor(a, 8),
      locA2 = idFor(a, 9);
  final UUID leB = idFor(b, 1),
      skuCompB2 = idFor(b, 2),
      skuProductB = idFor(b, 4),
      whB = idFor(b, 5),
      qSellB = idFor(b, 6),
      locB1 = idFor(b, 8),
      locB2 = idFor(b, 9);

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    // TRUNCATE 不触发行级触发器，以超级用户连接执行可绕过 FORCE RLS ——
    // 只用于测试夹具重建，不给应用角色开这个口子。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate disassembly_order_component, disassembly_order, assembly_order_component,"
              + " assembly_order, inventory_serial_movement, inventory_serial_operation,"
              + " inventory_serial, stock_bucket, warehouse_location, inventory_lot, inventory_entry,"
              + " inventory_transaction, inventory_balance, inventory_quality_status, warehouse,"
              + " sku_ref, legal_entity_ref restart identity cascade");
    }
    for (var tenant : List.of(a, b)) {
      access.run(
          actorFor(tenant),
          "warehouse.disassembly.read",
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
                "COMP-A",
                "面料");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 3),
                tenant,
                "COMP-B",
                "拉链");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 4),
                tenant,
                "KIT-1",
                "成品礼盒套装");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone)"
                    + " values(?,?,?,?,?,?)",
                idFor(tenant, 5),
                tenant,
                "WH-SH",
                "上海仓",
                "CN",
                "Asia/Shanghai");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role)"
                    + " values(?,?,?,?,?)",
                idFor(tenant, 6),
                tenant,
                "SELLABLE",
                "可售",
                "SELLABLE");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role)"
                    + " values(?,?,?,?,?)",
                idFor(tenant, 7),
                tenant,
                "PENDING_INSPECTION",
                "待检",
                "PENDING_INSPECTION");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,"
                    + "status,pickable) values(?,?,?,?,?,'STORAGE','ACTIVE',true)",
                idFor(tenant, 8),
                tenant,
                idFor(tenant, 5),
                "A-01",
                "组件库位");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,"
                    + "status,pickable) values(?,?,?,?,?,'STORAGE','ACTIVE',true)",
                idFor(tenant, 9),
                tenant,
                idFor(tenant, 5),
                "A-02",
                "成品库位");
            return null;
          });
    }
    // A 租户：成品在 locA2 上有货（拆解要从这里出）；组件备货是为了能先走一遍
    // 8.4.1.1 组装单（「成对可对账」用例要先组装出成品，再把它拆回去）。
    stockA(skuProductA, locA2, "20");
    stockA(skuCompA, locA1, "5");
    stockA(skuCompB, locA1, "100");
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "warehouse.disassembly.read", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "warehouse.disassembly.read", work);
  }

  <T> T asAU(java.util.function.Supplier<T> work) {
    return access.run(actorA, "warehouse.disassembly.update", work);
  }

  /** 造一条可出库的库存：bucket 与 balance 同时写，守恒触发器会核对两者。 */
  void stockA(UUID skuId, UUID locationId, String onHand) {
    asA(
        () -> {
          UUID balance = UUID.randomUUID();
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
              balance,
              a,
              skuId,
              whA,
              leA,
              qSellA,
              new BigDecimal(onHand));
          sql.execute(
              "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,"
                  + "location_id,lot_id,quality_status_id,balance_id,on_hand,reserved,"
                  + "last_movement_at) values(?,?,?,?,?,?,null,?,?,?,0,?::timestamptz)",
              UUID.randomUUID(),
              a,
              leA,
              skuId,
              whA,
              locationId,
              qSellA,
              balance,
              new BigDecimal(onHand),
              OffsetDateTime.now());
          return null;
        });
  }

  BigDecimal onHandOf(UUID skuId, UUID locationId) {
    return asA(
        () -> {
          Record row =
              sql.fetchOne(
                  "select on_hand from stock_bucket where sku_id=? and location_id=?",
                  skuId,
                  locationId);
          return row == null ? null : row.get("on_hand", BigDecimal.class);
        });
  }

  /** 标准建单：2 个组件（面料回可售、拉链回待检），应回量分别为 quantity 与 quantity×10。 */
  DisassemblyOrderModels.DisassemblyOrderCreateInput createInput(
      BigDecimal quantity, BigDecimal compA, BigDecimal compB) {
    var components = new ArrayList<DisassemblyOrderModels.DisassemblyComponentInput>();
    components.add(
        new DisassemblyOrderModels.DisassemblyComponentInput(
            skuCompA,
            idFor(a, 30),
            compA,
            new BigDecimal("2"),
            new BigDecimal("100"),
            qSellA,
            locA1,
            null,
            "面料",
            null));
    components.add(
        new DisassemblyOrderModels.DisassemblyComponentInput(
            skuCompB,
            idFor(a, 31),
            compB,
            new BigDecimal("1"),
            new BigDecimal("95"),
            qInspectA,
            locA1,
            null,
            "拉链",
            null));
    return new DisassemblyOrderModels.DisassemblyOrderCreateInput(
        skuProductA,
        UUID.randomUUID(),
        "BOM-KIT-1",
        idFor(a, 29),
        "v1",
        DisassemblyOrderModels.SOURCE_DIRECT,
        null,
        "",
        whA,
        leA,
        quantity,
        locA2,
        qSellA,
        null,
        "拆解单测试",
        components);
  }

  DisassemblyOrderModels.DisassemblyOrderCreated newOrder(
      BigDecimal quantity, BigDecimal compA, BigDecimal compB) {
    return asAU(() -> disassembly.create(createInput(quantity, compA, compB), user));
  }

  // ================================================================ 能力与迁移

  @Test
  @DisplayName("能力声明把跨服务 BOM 边界、去向必填与未交付下游如实报出来")
  void capabilities() {
    var caps = asA(() -> disassembly.capabilities());
    assertThat(caps.refType()).isEqualTo("DISASSEMBLY_ORDER");
    assertThat(caps.atomicProductAndComponentMovements()).isTrue();
    assertThat(caps.supportsDirectInitiation()).isTrue();
    assertThat(caps.bomBoundary()).contains("不 join、不建外键").contains("引用键");
    // 蓝图加粗句必须出现在能力声明里：不能只活在代码注释里。
    assertThat(caps.qualityDestinationRule())
        .contains("由接收方决定")
        .contains("不能默认全部回可售")
        .contains("SELLABLE")
        .contains("PENDING_INSPECTION")
        .contains("DEFECTIVE");
    // 上游必须是 8.4.1.1 组装单，且这条边界要说明为什么不建外键。
    assertThat(caps.upstreamBoundary()).contains("8.4.1.1").contains("不建外键");
    assertThat(caps.invariant()).contains("D1").contains("D2").contains("D3");
    assertThat(caps.upstreamModules())
        .anySatisfy(
            m -> {
              assertThat(m.moduleId()).isEqualTo("8.4.1.1");
              assertThat(m.available()).isTrue();
            });
    assertThat(caps.downstreamModules())
        .anySatisfy(
            m -> {
              assertThat(m.moduleId()).isEqualTo("11");
              assertThat(m.available()).isFalse();
            });
  }

  @Test
  @DisplayName("两张业务表都 tenant_id NOT NULL 且 relforcerowsecurity")
  void rlsIsForcedOnEveryTable() {
    for (String table : List.of("disassembly_order", "disassembly_order_component")) {
      Boolean forced =
          asA(
              () ->
                  sql.fetchOne("select relforcerowsecurity from pg_class where relname = ?", table)
                      .get(0, Boolean.class));
      assertThat(forced).as(table).isTrue();
      Boolean notNull =
          asA(
              () ->
                  sql.fetchOne(
                          "select is_nullable from information_schema.columns"
                              + " where table_name = ? and column_name = 'tenant_id'",
                          table)
                      .get(0, String.class)
                      .equals("NO"));
      assertThat(notNull).as(table).isTrue();
    }
  }

  // ================================================================ 建单

  @Test
  @DisplayName("建单落成草稿 + 组件反向快照，合计由触发器回写；**不动任何库存**")
  void createIsDraftAndMovesNoStock() {
    BigDecimal productBefore = onHandOf(skuProductA, locA2);
    var order = newOrder(new BigDecimal("2"), new BigDecimal("2"), new BigDecimal("20"));
    assertThat(order.status()).isEqualTo("DRAFT");
    assertThat(order.componentCount()).isEqualTo(2);
    assertThat(order.expectedComponentQuantity()).isEqualByComparingTo("22");

    var detail = asA(() -> disassembly.detail(order.orderId()));
    assertThat(detail.order().status()).isEqualTo("DRAFT");
    assertThat(detail.components()).hasSize(2);
    assertThat(detail.components()).allSatisfy(c -> assertThat(c.recoveredQuantity()).isZero());
    // 去向逐行冻结，且回读得到 role（可用 / 待检）
    assertThat(detail.components())
        .filteredOn(c -> c.skuCode().equals("COMP-A"))
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.destinationQualityRole()).isEqualTo("SELLABLE");
              assertThat(c.destinationLocationId()).isEqualTo(locA1);
            });
    assertThat(detail.components())
        .filteredOn(c -> c.skuCode().equals("COMP-B"))
        .singleElement()
        .satisfies(c -> assertThat(c.destinationQualityRole()).isEqualTo("PENDING_INSPECTION"));
    // 回收率是拆解独有的一维，原样落库
    assertThat(detail.components())
        .filteredOn(c -> c.skuCode().equals("COMP-B"))
        .singleElement()
        .satisfies(c -> assertThat(c.recoveryRate()).isEqualByComparingTo("95.0000"));
    // 库存一动不动
    assertThat(onHandOf(skuProductA, locA2)).isEqualByComparingTo(productBefore);
    assertThat(detail.entries()).isEmpty();
  }

  @Test
  @DisplayName("sourceSystem 与 referenceId 的组合被显式校验（两个答案就是没有答案）")
  void sourceAndReferenceMustAgree() {
    assertThatThrownBy(
            () ->
                asAU(
                    () ->
                        disassembly.create(
                            withSource(DisassemblyOrderModels.SOURCE_DIRECT, UUID.randomUUID()),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("DIRECT");
    assertThatThrownBy(
            () ->
                asAU(
                    () ->
                        disassembly.create(
                            withSource(DisassemblyOrderModels.SOURCE_AFTERSALES_RETURN, null),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("必须给 referenceId");
    assertThatThrownBy(
            () -> asAU(() -> disassembly.create(withSource("SUPPLIER", UUID.randomUUID()), user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("上游来源只能是");
  }

  DisassemblyOrderModels.DisassemblyOrderCreateInput withSource(String source, UUID referenceId) {
    var base = createInput(new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("10"));
    return new DisassemblyOrderModels.DisassemblyOrderCreateInput(
        base.productSkuId(),
        base.bomId(),
        base.bomCode(),
        base.bomVersionId(),
        base.bomVersionNo(),
        source,
        referenceId,
        referenceId == null ? null : "REF-1",
        base.warehouseId(),
        base.legalEntityId(),
        base.disassemblyQuantity(),
        base.outboundLocationId(),
        base.outboundQualityStatusId(),
        base.outboundLotId(),
        base.notes(),
        base.components());
  }

  /**
   * 上游组装单必须存在且已完成 —— 8.4.1.1 → 8.4.2.1 的真实引用关系。
   *
   * <p>拆一件没组装过的成品在账上说不通：库里那件成品是谁放进来的？外键表达不了 「上游必须已完成」，所以这条校验在服务层用显式 SQL 做。
   */
  @Test
  @DisplayName("上游组装单（8.4.1.1）必须存在且已完成，草稿组装单不能被拆")
  void upstreamAssemblyMustBeCompleted() {
    var assemblyOrder = newAssemblyOrder();
    // 草稿态的组装单：还没产出成品，拆它就是拆一件不存在的东西
    assertThatThrownBy(
            () ->
                asAU(
                    () ->
                        disassembly.create(
                            withSource(
                                DisassemblyOrderModels.SOURCE_ASSEMBLY_ORDER,
                                assemblyOrder.orderId()),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("DRAFT");

    asAU(() -> assembly.issue(assemblyOrder.orderId(), null, user));
    var order =
        asAU(
            () ->
                disassembly.create(
                    withSource(
                        DisassemblyOrderModels.SOURCE_ASSEMBLY_ORDER, assemblyOrder.orderId()),
                    user));
    assertThat(order.status()).isEqualTo("DRAFT");

    // 上游根本不存在（伪造一个 UUID）→ 404，而不是「当 DIRECT 处理」
    assertThatThrownBy(
            () ->
                asAU(
                    () ->
                        disassembly.create(
                            withSource(
                                DisassemblyOrderModels.SOURCE_ASSEMBLY_ORDER, UUID.randomUUID()),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或无权查看");
  }

  /** 造一张 8.4.1.1 组装单（草稿），用真实的 BOM 冻结明细。 */
  AssemblyOrderModels.AssemblyOrderCreated newAssemblyOrder() {
    var components = new ArrayList<AssemblyOrderModels.AssemblyComponentInput>();
    components.add(
        new AssemblyOrderModels.AssemblyComponentInput(
            skuCompA, null, new BigDecimal("4"), new BigDecimal("2"), BigDecimal.ZERO, "面料", null));
    components.add(
        new AssemblyOrderModels.AssemblyComponentInput(
            skuCompB,
            null,
            new BigDecimal("40"),
            new BigDecimal("1"),
            BigDecimal.ZERO,
            "拉链",
            null));
    return asAU(
        () ->
            assembly.create(
                new AssemblyOrderModels.AssemblyOrderCreateInput(
                    skuProductA,
                    UUID.randomUUID(),
                    "BOM-KIT-1",
                    idFor(a, 29),
                    "v1",
                    AssemblyOrderModels.SOURCE_DIRECT,
                    null,
                    "",
                    whA,
                    leA,
                    new BigDecimal("2"),
                    locA1,
                    qSellA,
                    locA2,
                    qSellA,
                    null,
                    null,
                    "为拆解准备的组装单",
                    components),
                user));
  }

  // ================================================================ 蓝图加粗句：去向必填

  /**
   * 蓝图原话：「拆解结果的组件质量状态由接收方决定，<b>不能默认全部回可售</b>。」
   *
   * <p>所以缺去向必须被**拒绝**，而不是「替你挑一个可售」。这条用例就是那句加粗句的判卷点。
   */
  @Test
  @DisplayName("蓝图加粗句：组件去向必填，不填就是拒绝（不替你默认回可售）")
  void destinationQualityIsRequiredAndNeverDefaulted() {
    var base = createInput(new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("10"));
    var noDestination =
        new DisassemblyOrderModels.DisassemblyComponentInput(
            skuCompA,
            null,
            new BigDecimal("1"),
            new BigDecimal("2"),
            new BigDecimal("100"),
            // destinationQualityStatusId = null：接收方没判定
            null,
            locA1,
            null,
            "面料",
            null);
    var input =
        new DisassemblyOrderModels.DisassemblyOrderCreateInput(
            base.productSkuId(),
            base.bomId(),
            base.bomCode(),
            base.bomVersionId(),
            base.bomVersionNo(),
            base.sourceSystem(),
            base.referenceId(),
            base.referenceNo(),
            base.warehouseId(),
            base.legalEntityId(),
            base.disassemblyQuantity(),
            base.outboundLocationId(),
            base.outboundQualityStatusId(),
            base.outboundLotId(),
            base.notes(),
            List.of(noDestination));

    assertThatThrownBy(() -> asAU(() -> disassembly.create(input, user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不能默认全部回可售");
  }

  @Test
  @DisplayName("去向可以逐行不同（面料回可售、拉链回待检），单头没有整单默认")
  void destinationIsPerLineNotPerOrder() {
    var order = newOrder(new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("10"));
    var detail = asA(() -> disassembly.detail(order.orderId()));
    assertThat(detail.components())
        .extracting(DisassemblyOrderModels.DisassemblyComponentRow::destinationQualityRole)
        .containsExactlyInAnyOrder("SELLABLE", "PENDING_INSPECTION");
  }

  // ================================================================ 明细校验

  @Test
  @DisplayName("同一组件出现两次 → 拒绝（「这个组件能拆出几件」无法回答）")
  void duplicateComponentRejected() {
    var base = createInput(new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("10"));
    var dup = base.components().get(0);
    assertThatThrownBy(
            () -> asAU(() -> disassembly.create(withComponents(List.of(dup, dup)), user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("出现了两次");
  }

  /** D3 的服务层一半：成品出现在自己的组件明细里 → 那是复制不是拆解。 */
  @Test
  @DisplayName("成品不能出现在自己的组件明细里（复制不叫拆解）")
  void productCannotBeItsOwnComponent() {
    var self =
        new DisassemblyOrderModels.DisassemblyComponentInput(
            skuProductA,
            null,
            new BigDecimal("1"),
            null,
            new BigDecimal("100"),
            qSellA,
            locA1,
            null,
            null,
            null);
    assertThatThrownBy(() -> asAU(() -> disassembly.create(withComponents(List.of(self)), user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不叫「拆解」");
  }

  @Test
  @DisplayName("不能被库存刻度精确表示的量被拒绝（不静默取整换取「不变量成立」）")
  void quantityMustBeExactAtStockScale() {
    assertThatThrownBy(
            () -> newOrder(new BigDecimal("1.00001"), new BigDecimal("1"), new BigDecimal("10")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("4 位小数");
    assertThatThrownBy(
            () -> newOrder(new BigDecimal("1"), new BigDecimal("0.123456"), new BigDecimal("10")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("4 位小数");
  }

  DisassemblyOrderModels.DisassemblyOrderCreateInput withComponents(
      List<DisassemblyOrderModels.DisassemblyComponentInput> components) {
    var base = createInput(new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("10"));
    return new DisassemblyOrderModels.DisassemblyOrderCreateInput(
        base.productSkuId(),
        base.bomId(),
        base.bomCode(),
        base.bomVersionId(),
        base.bomVersionNo(),
        base.sourceSystem(),
        base.referenceId(),
        base.referenceNo(),
        base.warehouseId(),
        base.legalEntityId(),
        base.disassemblyQuantity(),
        base.outboundLocationId(),
        base.outboundQualityStatusId(),
        base.outboundLotId(),
        base.notes(),
        components);
  }

  // ================================================================ 不变量 D1 / D2 / D3

  @Test
  @DisplayName("D1+D2 成立：成品出库分录 = 拆解数量，组件入库分录之和 = 应回量之和")
  void invariantsHoldThroughTheService() {
    BigDecimal productBefore = onHandOf(skuProductA, locA2);
    var order = newOrder(new BigDecimal("3"), new BigDecimal("3"), new BigDecimal("30"));
    var executed = asAU(() -> disassembly.execute(order.orderId(), null, user));

    // D1：成品从 20 减到 17
    assertThat(onHandOf(skuProductA, locA2))
        .isEqualByComparingTo(productBefore.subtract(new BigDecimal("3")));
    assertThat(executed.outboundProductQuantity()).isEqualByComparingTo("3");
    // D2：组件应回 33（3 + 30），实回也必须是 33
    assertThat(executed.expectedComponentQuantity()).isEqualByComparingTo("33");
    assertThat(executed.recoveredComponentQuantity()).isEqualByComparingTo("33");
    assertThat(executed.components()).hasSize(2);
    assertThat(executed.entryCount()).isEqualTo(3);

    // 去向在结果里可读回：组件进了**它自己的质量状态**，不是全部回可售
    assertThat(executed.components())
        .filteredOn(c -> c.skuCode().equals("COMP-B"))
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.destinationQualityRole()).isEqualTo("PENDING_INSPECTION");
              assertThat(c.balanceId()).isNotNull();
            });

    var detail = asA(() -> disassembly.detail(order.orderId()));
    assertThat(detail.order().status()).isEqualTo("COMPLETED");
    assertThat(detail.entries()).hasSize(3);
    assertThat(detail.components())
        .allSatisfy(
            c -> assertThat(c.recoveredQuantity()).isEqualByComparingTo(c.expectedQuantity()));
  }

  /**
   * 组装 + 拆解成对可对账。
   *
   * <p>8.4.1.1 消耗组件产出成品，本模块消耗成品产出组件。两者在账上必须成对： 同一批组件先被组装消耗、再被拆解产出，净变化为 0 —— 这正是「成对且可对账」的判据。
   */
  @Test
  @DisplayName("与 8.4.1.1 组装单成对可对账：拆回去的组件正好抵掉组装用掉的")
  void pairsWithAssemblyOrder() {
    BigDecimal compBefore = onHandOf(skuCompA, locA1);
    BigDecimal productBefore = onHandOf(skuProductA, locA2);

    // 组装 2 件：用掉面料 4、拉链 40，产出成品 2
    var assemblyOrder = newAssemblyOrder();
    asAU(() -> assembly.issue(assemblyOrder.orderId(), null, user));
    assertThat(onHandOf(skuCompA, locA1))
        .isEqualByComparingTo(compBefore.subtract(new BigDecimal("4")));
    assertThat(onHandOf(skuProductA, locA2))
        .isEqualByComparingTo(productBefore.add(new BigDecimal("2")));

    // 拆解同样 2 件：消耗成品 2，按 100% 回收率产回面料 4
    var order = newOrder(new BigDecimal("2"), new BigDecimal("4"), new BigDecimal("40"));
    asAU(() -> disassembly.execute(order.orderId(), null, user));
    assertThat(onHandOf(skuCompA, locA1)).isEqualByComparingTo(compBefore);
    assertThat(onHandOf(skuProductA, locA2)).isEqualByComparingTo(productBefore);

    // 两张单的分录挂在不同的 ref_type 上，但都指向各自的单据，可分别追溯
    var executed = asA(() -> disassembly.detail(order.orderId())).entries();
    assertThat(executed).hasSize(3);
    assertThat(asA(() -> disassembly.detail(order.orderId())).order().inventoryTransactionId())
        .isNotEqualTo(
            asA(() -> assembly.detail(assemblyOrder.orderId())).order().inventoryTransactionId());
  }

  @Test
  @DisplayName("同一事务：成品不足 → 整体回滚（组件没入、不留半截分录）")
  void rollbackWhenProductIsShort() {
    var order = newOrder(new BigDecimal("999"), new BigDecimal("1"), new BigDecimal("10"));
    assertThatThrownBy(() -> asAU(() -> disassembly.execute(order.orderId(), null, user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("成品不足");

    // 一件组件都没入、单据还停在草稿
    assertThat(asA(() -> disassembly.detail(order.orderId())).order().status()).isEqualTo("DRAFT");
    assertThat(asA(() -> disassembly.detail(order.orderId())).entries()).isEmpty();
    assertThat(asA(() -> disassembly.detail(order.orderId())).components())
        .allSatisfy(c -> assertThat(c.recoveredQuantity()).isZero());
  }

  /** 不变量在数据库层：绕过服务层只入组件、不出成品 → 守卫触发器拒绝。 */
  @Test
  @DisplayName("不变量在数据库层：绕过服务层只入组件不扣成品 → 守卫触发器拒绝（D1）")
  void dbGuardRejectsMissingProductOutbound() {
    var order = newOrder(new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("10"));
    assertThatThrownBy(
            () ->
                asAU(
                    () -> {
                      UUID tx = UUID.randomUUID();
                      sql.execute(
                          "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,"
                              + "occurred_at,actor_id,notes)"
                              + " values(?,?,?,?,?,?::timestamptz,?,'')",
                          tx,
                          a,
                          "ADJUSTMENT",
                          DisassemblyOrderModels.REF_TYPE,
                          order.orderId(),
                          OffsetDateTime.now(),
                          user);
                      // 只给组件造一条正向分录，成品一条都没有
                      Record balance =
                          sql.fetchOne(
                              "select id from inventory_balance where sku_id=? and warehouse_id=?"
                                  + " and legal_entity_id=? and quality_status_id=?",
                              skuCompA,
                              whA,
                              leA,
                              qSellA);
                      sql.execute(
                          "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,"
                              + "on_hand_delta,reserved_delta) values(?,?,?,?,?,0)",
                          UUID.randomUUID(),
                          a,
                          tx,
                          balance.get("id", UUID.class),
                          BigDecimal.ONE);
                      sql.execute(
                          "update disassembly_order set status='COMPLETED',"
                              + "inventory_transaction_id=?,outbound_product_quantity=?,"
                              + "executed_at=?::timestamptz,executed_by=? where id=?",
                          tx,
                          BigDecimal.ONE,
                          OffsetDateTime.now(),
                          user,
                          order.orderId());
                      return null;
                    }))
        .hasMessageContaining("D1");
    // 事务回滚：单据仍是草稿
    assertThat(asA(() -> disassembly.detail(order.orderId())).order().status()).isEqualTo("DRAFT");
  }

  @Test
  @DisplayName("不变量在数据库层：0 组件的拆解单不能被标记完成（凭空变出组件）")
  void dbGuardRejectsCompletionWithoutComponents() {
    UUID orderId = UUID.randomUUID();
    asA(
        () -> {
          sql.execute(
              "insert into disassembly_order(id,tenant_id,order_code,product_sku_id,"
                  + "product_sku_code,bom_id,bom_code,bom_version_id,bom_version_no,source_system,"
                  + "reference_id,reference_no,warehouse_id,legal_entity_id,disassembly_quantity,"
                  + "status,outbound_location_id,outbound_quality_status_id,created_by)"
                  + " values(?,?,?,?,?,?,?,?,?,?,null,'','"
                  + whA
                  + "','"
                  + leA
                  + "',1,'DRAFT','"
                  + locA2
                  + "','"
                  + qSellA
                  + "','"
                  + user
                  + "')",
              orderId,
              a,
              "DSA-000001",
              skuProductA,
              "KIT-1",
              idFor(a, 28),
              "BOM",
              idFor(a, 29),
              "v1",
              DisassemblyOrderModels.SOURCE_DIRECT);
          return null;
        });
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update disassembly_order set status='COMPLETED',"
                                + "inventory_transaction_id=?,outbound_product_quantity=1,"
                                + "executed_at=?::timestamptz,executed_by=? where id=?",
                            UUID.randomUUID(),
                            OffsetDateTime.now(),
                            user,
                            orderId)))
        .hasMessageContaining("凭空变出组件");
  }

  /**
   * D3 的数据库层一半：成品出现在自己的组件明细里 → 完成时被守卫挡下。
   *
   * <p>注意断言点：<b>建单/插明细时放行，完成时才拒</b>。明细守卫只管「非草稿不可改明细」， D3 是<b>完成那一刻</b>才回读明细与账本的不变量 —— 所以这张单要一直停在
   * DRAFT， 直到状态推进到 COMPLETED 那一刻才被拒。这样守卫拦的是「绕过服务层造出复制单」， 而不是把「草稿还在编辑」也堵死。
   */
  @Test
  @DisplayName("不变量在数据库层：成品出现在自己的组件明细里 → 完成被拒（D3）")
  void dbGuardRejectsSelfReferentialComponent() {
    var order = newOrder(new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("10"));
    // 绕过服务层直接塞一行「成品 = 自己的组件」：草稿期明细守卫不管内容，放行。
    asA(
        () ->
            sql.execute(
                "insert into disassembly_order_component(id,tenant_id,order_id,sku_id,"
                    + "sku_code,expected_quantity,recovery_rate,"
                    + "destination_quality_status_id,destination_location_id)"
                    + " values(?,?,?,?,?,1,100,?,?)",
                UUID.randomUUID(),
                a,
                order.orderId(),
                skuProductA,
                "KIT-1",
                qSellA,
                locA1));

    // 推进到 COMPLETED 的那一刻，D3 回读明细发现成品在自己列表里 → 拒。
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update disassembly_order set status='COMPLETED',"
                                + "inventory_transaction_id=?,outbound_product_quantity=1,"
                                + "executed_at=?::timestamptz,executed_by=? where id=?",
                            UUID.randomUUID(),
                            OffsetDateTime.now(),
                            user,
                            order.orderId())))
        .hasMessageContaining("D3");
    // 事务回滚：单据仍是草稿
    assertThat(asA(() -> disassembly.detail(order.orderId())).order().status()).isEqualTo("DRAFT");
  }

  @Test
  @DisplayName("已完成的拆解单不可再流转回草稿、不可改数（终态是凭据）")
  void completedOrderIsTerminal() {
    var order = newOrder(new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("10"));
    asAU(() -> disassembly.execute(order.orderId(), null, user));
    assertThatThrownBy(() -> asAU(() -> disassembly.execute(order.orderId(), null, user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("CONFLICT");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update disassembly_order set status='DRAFT' where id=?",
                            order.orderId())))
        .hasMessageContaining("终态");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update disassembly_order set disassembly_quantity=9 where id=?",
                            order.orderId())))
        .hasMessageContaining("凭据");
  }

  @Test
  @DisplayName("已完成的拆解单不可改明细（尤其不可事后把去向改成可售）")
  void completedOrderRejectsComponentEdits() {
    var order = newOrder(new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("10"));
    asAU(() -> disassembly.execute(order.orderId(), null, user));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update disassembly_order_component set destination_quality_status_id=?"
                                + " where order_id=? and sku_id=?",
                            qSellA,
                            order.orderId(),
                            skuCompB)))
        .hasMessageContaining("不可再改");
  }

  @Test
  @DisplayName("重复执行同一张拆解单 → 409（不会重复扣成品重复入组件）")
  void repeatedExecuteConflicts() {
    var order = newOrder(new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("10"));
    asAU(() -> disassembly.execute(order.orderId(), null, user));
    assertThatThrownBy(() -> asAU(() -> disassembly.execute(order.orderId(), null, user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("只有草稿可以执行或取消");
  }

  @Test
  @DisplayName("取消草稿：状态转 CANCELLED，**不动库存**；已完成的不能取消")
  void cancelDraft() {
    var order = newOrder(new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("10"));
    BigDecimal before = onHandOf(skuProductA, locA2);
    var cancelled =
        asAU(
            () ->
                disassembly.cancel(
                    order.orderId(),
                    new DisassemblyOrderModels.DisassemblyOrderCancelInput("样品不用拆了"),
                    user));
    assertThat(cancelled.status()).isEqualTo("CANCELLED");
    assertThat(onHandOf(skuProductA, locA2)).isEqualByComparingTo(before);

    var done = newOrder(new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("10"));
    asAU(() -> disassembly.execute(done.orderId(), null, user));
    assertThatThrownBy(
            () ->
                asAU(
                    () ->
                        disassembly.cancel(
                            done.orderId(),
                            new DisassemblyOrderModels.DisassemblyOrderCancelInput("反悔"),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("只有草稿可以执行或取消");
  }

  // ================================================================ 时间戳

  @Test
  @DisplayName("timestamptz 写入后偏移零漂移（断言写回的值，不是「没抛异常」）")
  void timestampRoundTripsWithoutDrift() {
    var order = newOrder(new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("10"));
    var executed = asAU(() -> disassembly.execute(order.orderId(), null, user));
    OffsetDateTime written =
        asA(
            () ->
                sql.fetchOne(
                        "select executed_at from disassembly_order where id=?", order.orderId())
                    .get(0, OffsetDateTime.class));
    OffsetDateTime readBack = OffsetDateTime.parse(executed.occurredAt());
    assertThat(written.toInstant()).isEqualTo(readBack.toInstant());
    assertThat(written.getOffset()).isEqualTo(readBack.getOffset());
  }

  // ================================================================ 列表与筛选

  @Test
  @DisplayName("列表：真分页 + 真筛选 + 排序白名单 + 汇总（汇总不被明细放大）+ 去向分布")
  void pageIsRealPagingAndFiltering() {
    var first = newOrder(new BigDecimal("10"), new BigDecimal("1"), new BigDecimal("10"));
    asAU(() -> disassembly.execute(first.orderId(), null, user));
    var second = newOrder(new BigDecimal("4"), new BigDecimal("2"), new BigDecimal("20"));
    asAU(
        () ->
            disassembly.cancel(
                second.orderId(),
                new DisassemblyOrderModels.DisassemblyOrderCancelInput("不拆了"),
                user));
    newOrder(new BigDecimal("7"), new BigDecimal("3"), new BigDecimal("30"));

    var all = asA(() -> disassembly.page(null, null, null, null, null, null, "CREATED_AT", 0, 50));
    assertThat(all.total()).isEqualTo(3);
    assertThat(all.items()).hasSize(3);
    assertThat(all.draftCount()).isEqualTo(1);
    assertThat(all.completedCount()).isEqualTo(1);
    assertThat(all.cancelledCount()).isEqualTo(1);
    // 21 = 10 + 4 + 7：若汇总误 join 明细，会被组件行数放大成 63。
    assertThat(all.totalDisassemblyQuantity()).isEqualByComparingTo("21");
    // 66 = (1+10) + (2+20) + (3+30)：若汇总误 join 明细，会被组件行数放大成 132。
    assertThat(all.totalExpectedComponentQuantity()).isEqualByComparingTo("66");
    // 实回只来自那 1 张已执行的单：11
    assertThat(all.totalRecoveredComponentQuantity()).isEqualByComparingTo("11");

    // 去向分布：可售 vs 待检，两档都在，且各自的数量正确（未被放大）
    assertThat(all.destinations()).hasSize(2);
    assertThat(all.destinations())
        .filteredOn(d -> d.qualityRole().equals("SELLABLE"))
        .singleElement()
        .satisfies(d -> assertThat(d.expectedQuantity()).isEqualByComparingTo("6"));
    assertThat(all.destinations())
        .filteredOn(d -> d.qualityRole().equals("PENDING_INSPECTION"))
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.expectedQuantity()).isEqualByComparingTo("60");
              // 只有第 1 张单执行过，所以实回只有 10
              assertThat(d.recoveredQuantity()).isEqualByComparingTo("10");
            });

    // 状态筛选
    var completed =
        asA(() -> disassembly.page("COMPLETED", null, null, null, null, null, null, 0, 50));
    assertThat(completed.total()).isEqualTo(1);
    assertThat(completed.items().get(0).orderCode()).isEqualTo(first.orderCode());

    // 关键词筛选
    assertThat(
            asA(() ->
                    disassembly.page(null, null, null, null, null, first.orderCode(), null, 0, 50))
                .total())
        .isEqualTo(1);
    assertThat(
            asA(() -> disassembly.page(null, null, null, null, null, "KIT-1", null, 0, 50)).total())
        .isEqualTo(3);

    // 真分页：size=1 时 total 仍是 3，items 只有 1 条，且三页互不重复
    var page0 = asA(() -> disassembly.page(null, null, null, null, null, null, "ORDER_CODE", 0, 1));
    var page1 = asA(() -> disassembly.page(null, null, null, null, null, null, "ORDER_CODE", 1, 1));
    var page2 = asA(() -> disassembly.page(null, null, null, null, null, null, "ORDER_CODE", 2, 1));
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
    assertThat(
            asA(() -> disassembly.page(null, null, null, null, null, null, null, 0, 9999)).size())
        .isEqualTo(200);
  }

  @Test
  @DisplayName("排序键走白名单（不接受任意排序键，避免拼进 SQL）")
  void sortIsWhitelisted() {
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        disassembly.page(
                            null, null, null, null, null, null, "id; drop table", 0, 10)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的排序键");
    assertThatThrownBy(
            () -> asA(() -> disassembly.page("SHIPPED", null, null, null, null, null, null, 0, 10)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的状态");
    assertThatThrownBy(
            () ->
                asA(() -> disassembly.page(null, null, null, "SUPPLIER", null, null, null, 0, 10)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的来源");
  }

  // ================================================================ 多租户三断言（AGENTS.md 硬要求）

  @Test
  @DisplayName("多租户断言 1：A 租户看不见 B 租户的拆解单")
  void tenantACannotSeeTenantB() {
    var orderB =
        access.run(
            actorB,
            "warehouse.disassembly.update",
            () ->
                disassembly.create(
                    new DisassemblyOrderModels.DisassemblyOrderCreateInput(
                        skuProductB,
                        UUID.randomUUID(),
                        "BOM-B",
                        idFor(b, 29),
                        "v1",
                        DisassemblyOrderModels.SOURCE_DIRECT,
                        null,
                        null,
                        whB,
                        idFor(b, 1),
                        new BigDecimal("3"),
                        locB2,
                        qSellB,
                        null,
                        null,
                        List.of(
                            new DisassemblyOrderModels.DisassemblyComponentInput(
                                idFor(b, 2),
                                null,
                                new BigDecimal("2"),
                                null,
                                new BigDecimal("100"),
                                qSellB,
                                locB1,
                                null,
                                null,
                                null))),
                    user));

    assertThat(asA(() -> disassembly.page(null, null, null, null, null, null, null, 0, 50)).total())
        .isZero();
    assertThatThrownBy(() -> asA(() -> disassembly.detail(orderB.orderId())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或无权查看");
    // B 自己看得到
    assertThat(asB(() -> disassembly.detail(orderB.orderId())).order().orderCode())
        .isEqualTo(orderB.orderCode());
  }

  @Test
  @DisplayName("多租户断言 2：伪造 X-Tenant-Id 头无效（端点只认已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    var order = newOrder(new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("10"));
    asAU(() -> disassembly.execute(order.orderId(), null, user));

    // 带伪造的 B 租户头访问：身份仍由 Bearer 决定，落到 A 租户，读到的是 A 的数据。
    http.perform(
            get("/warehouse/disassembly-orders/" + order.orderId())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.order.orderId").value(order.orderId().toString()));

    // 伪造租户头也拿不到 B 的单子
    http.perform(
            get("/warehouse/disassembly-orders")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString())
                .param("productSkuId", skuProductB.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
  }

  @Test
  @DisplayName("多租户断言 3：连接池归还后不串租户（池压到 1，20 轮往返）")
  void connectionPoolDoesNotLeakTenant() {
    TenantTransactions tenants = tenants();
    var order = newOrder(new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("10"));
    asAU(() -> disassembly.execute(order.orderId(), null, user));

    for (int i = 0; i < 20; i++) {
      assertThat(asA(() -> disassembly.detail(order.orderId()).order().orderCode()))
          .isEqualTo(order.orderCode());
      // B 租户事务内看不见 A 的拆解单
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
                () -> sql.fetchOne("select count(*) from disassembly_order").get(0, Long.class));
    return count == null ? 0L : count;
  }

  long countOrdersWithoutTenant() {
    Long count = sql.fetchOne("select count(*) from disassembly_order").get(0, Long.class);
    return count == null ? 0L : count;
  }

  // ================================================================ HTTP 契约

  @Test
  @DisplayName("HTTP 契约：401 未认证 / 403 只读 / 400 缺去向 / 404 不存在")
  void httpContract() throws Exception {
    http.perform(get("/warehouse/disassembly-orders/capabilities"))
        .andExpect(status().isUnauthorized());

    http.perform(
            get("/warehouse/disassembly-orders/capabilities")
                .header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk());

    // 只读身份不能建单
    http.perform(
            post("/warehouse/disassembly-orders")
                .header("Authorization", "Bearer read-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        createInput(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE))))
        .andExpect(status().isForbidden());

    // 数量非正：Bean Validation 直接拒
    http.perform(
            post("/warehouse/disassembly-orders")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        createInput(BigDecimal.ZERO, new BigDecimal("1"), new BigDecimal("1")))))
        .andExpect(status().isBadRequest());

    // 缺去向：Bean Validation（@NotNull）直接拒 —— 没有默认值这条约束在 HTTP 侧同样成立
    http.perform(
            post("/warehouse/disassembly-orders")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"productSkuId":"%s","bomId":"%s","bomCode":"BOM","bomVersionId":"%s",
                     "bomVersionNo":"v1","sourceSystem":"DIRECT","warehouseId":"%s",
                     "legalEntityId":"%s","disassemblyQuantity":1,"outboundLocationId":"%s",
                     "components":[{"skuId":"%s","expectedQuantity":1,"destinationLocationId":"%s"}]}
                    """
                        .formatted(
                            skuProductA,
                            idFor(a, 28),
                            idFor(a, 29),
                            whA,
                            leA,
                            locA2,
                            skuCompA,
                            locA1)))
        .andExpect(status().isBadRequest());

    // 未存在
    http.perform(
            get("/warehouse/disassembly-orders/" + UUID.randomUUID())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
  }
}
