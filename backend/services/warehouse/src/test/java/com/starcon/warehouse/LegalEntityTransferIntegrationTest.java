package com.starcon.warehouse;

import static com.starcon.warehouse.LegalEntityTransferModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 7.5.2.1 跨法人调拨：**货权真的换了法人**、转移定价、内部结算凭据、双方确认， 以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接： 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>夹具写法与 {@link InventoryTransferIntegrationTest} 完全一致（同样的 Testcontainers、 同样的 fake identity
 * server、同样的按租户派生 UUID、同样的 TRUNCATE 清场）。
 *
 * <p>本测试刻意盯住三件最容易做错、而错了最贵的事：
 *
 * <ul>
 *   <li><b>货权归属</b>：发出扣的是转出法人的余额，实收加的是<b>转入法人</b>的余额。 如果两边落在同一条余额行上，货权就没转移，12 财务的内部结算也就失去依据。
 *   <li><b>定价与舍入</b>：金额由 DB 生成列算出，落 numeric(19,4) 四舍五入（远离零）。
 *   <li><b>两个法人两个人</b>：同一个人把两方都签掉等于没有第二方，DB 触发器必须挡。
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class LegalEntityTransferIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();

  /** 两个不同的自然人：转出方与转入方必须是两个人。 */
  static final UUID userSource = UUID.randomUUID(), userTarget = UUID.randomUUID();

  static HttpServer identity;
  static final java.util.Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final List<String> allPermissions =
      List.of(
          "warehouse.inventory.read",
          "warehouse.inventory.create",
          "warehouse.inventory.update",
          "warehouse.inventory.delete");

  /** 转出法人。 */
  static final UUID leA1 = idFor(a, 1);

  /** 转入法人。 */
  static final UUID leA2 = idFor(a, 2);

  static final UUID skuA = idFor(a, 3);
  static final UUID whFrom = idFor(a, 4);
  static final UUID whTo = idFor(a, 5);
  static final UUID qSellable = idFor(a, 6);
  static final UUID locFrom = idFor(a, 7);
  static final UUID locTo = idFor(a, 8);

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-cross-entity-test-" + tenant + "-" + ordinal).getBytes());
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
      actors.put("Bearer tenant-a", actor(userSource, a, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer tenant-a-target", actor(userTarget, a, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(userSource, b, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only",
          actor(userSource, a, List.of(grant("ALL", List.of("warehouse.inventory.read")))));
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

  @Autowired LegalEntityTransferService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actorA;
  AccessService.Access actorATarget;
  AccessService.Access actorB;

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorATarget = actors.get("Bearer tenant-a-target");
    actorB = actors.get("Bearer tenant-b");
    // TRUNCATE 不触发行级触发器（append-only 只挡 UPDATE/DELETE），以超级用户连接执行可绕过
    // FORCE RLS —— 只用于测试夹具重建，不给应用角色开这个口子。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate cross_entity_settlement, cross_entity_approval, cross_entity_transfer_line,"
              + " cross_entity_transfer, inventory_transfer, stock_bucket, warehouse_location,"
              + " inventory_lot, inventory_entry, inventory_transaction, inventory_balance,"
              + " inventory_quality_status, warehouse, sku_ref, legal_entity_ref restart identity cascade");
    }
    for (var tenant : List.of(a, b)) {
      final UUID t = tenant;
      access.run(
          actorFor(t),
          "warehouse.inventory.read",
          () -> {
            // 每个租户都有**两个**法人：本模块的货权从转出方转到转入方
            sql.execute(
                "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(t, 1),
                t,
                "LE-HK",
                "转出主体");
            sql.execute(
                "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(t, 2),
                t,
                "LE-SG",
                "转入主体");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(t, 3),
                t,
                "SKU-1",
                "商品一");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone)"
                    + " values(?,?,?,?,?,?)",
                idFor(t, 4),
                t,
                "WH-HK",
                "香港仓",
                "HK",
                "Asia/Hong_Kong");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone)"
                    + " values(?,?,?,?,?,?)",
                idFor(t, 5),
                t,
                "WH-SG",
                "新加坡仓",
                "SG",
                "Asia/Singapore");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role)"
                    + " values(?,?,?,?,?)",
                idFor(t, 6),
                t,
                "SELLABLE",
                "可售",
                "SELLABLE");
            return null;
          });
    }
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,pickable)"
                  + " values(?,?,?,?,?,'STORAGE',true)",
              locFrom,
              a,
              whFrom,
              "HK-A-01",
              "库位 HK-A-01");
          sql.execute(
              "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,pickable)"
                  + " values(?,?,?,?,?,'STORAGE',true)",
              locTo,
              a,
              whTo,
              "SG-A-01",
              "库位 SG-A-01");
          return null;
        });
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  <T> T asA(Supplier<T> work) {
    return access.run(actorA, "warehouse.inventory.read", work);
  }

  <T> T asATarget(Supplier<T> work) {
    return access.run(actorATarget, "warehouse.inventory.read", work);
  }

  <T> T asB(Supplier<T> work) {
    return access.run(actorB, "warehouse.inventory.read", work);
  }

  // ================================================================ 夹具助手

  /** 建余额行并在指定库位拆一个 bucket（同一事务，V2 守恒是延迟约束）。 */
  void seedBalance(UUID legalEntity, UUID warehouse, UUID location, String onHand) {
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          var balanceId = UUID.randomUUID();
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
              balanceId,
              a,
              skuA,
              warehouse,
              legalEntity,
              qSellable,
              new BigDecimal(onHand));
          sql.execute(
              "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,"
                  + "location_id,lot_id,quality_status_id,balance_id,on_hand,reserved,"
                  + "last_movement_at) values(?,?,?,?,?,?,null,?,?,?,0,now())",
              UUID.randomUUID(),
              a,
              legalEntity,
              skuA,
              warehouse,
              location,
              qSellable,
              balanceId,
              new BigDecimal(onHand));
          return null;
        });
  }

  /** 某法人在某仓的余额。 */
  BigDecimal onHand(UUID legalEntity, UUID warehouse) {
    return asA(
        () ->
            sql.fetchOne(
                    "select coalesce(sum(on_hand),0) from inventory_balance"
                        + " where sku_id=? and warehouse_id=? and legal_entity_id=?",
                    skuA,
                    warehouse,
                    legalEntity)
                .get(0, BigDecimal.class));
  }

  /** 与 7.1.1.1 / 7.2.2.1 / 7.1.3.1 / 7.5.1.2 **完全相同**的那条在途口径。 */
  BigDecimal sharedInTransit() {
    return asA(
        () ->
            sql.fetchOne(
                    "select coalesce(sum(quantity),0) from inventory_transfer"
                        + " where status='IN_TRANSIT'")
                .get(0, BigDecimal.class));
  }

  /** 在途行挂在哪个法人名下 —— 发出后货权**尚未**转移。 */
  UUID inTransitLegalEntity() {
    return asA(
        () ->
            sql.fetchOne(
                    "select legal_entity_id from inventory_transfer"
                        + " where status='IN_TRANSIT' order by shipped_at desc limit 1")
                .get(0, UUID.class));
  }

  long countTransactions(String kind) {
    return asA(
        () ->
            sql.fetchOne("select count(*) from inventory_transaction where kind=?", kind)
                .get(0, Long.class));
  }

  LegalEntityTransferCreateRequest request(String quantity, String price) {
    return new LegalEntityTransferCreateRequest(
        whFrom,
        whTo,
        leA1,
        leA2,
        "TRANSFER_COST",
        "cny",
        null,
        null,
        "补充新加坡仓",
        "",
        List.of(
            new LegalEntityTransferLineRequest(
                skuA, qSellable, new BigDecimal(quantity), new BigDecimal(price))));
  }

  LegalEntityTransferSaved create() {
    return asA(() -> service.create(a, userSource, request("60", "12.5")));
  }

  /** 走完建单 → 转出确认 → 转入确认，返回已确认待发运的单。 */
  LegalEntityTransferSaved confirmed() {
    var created = create();
    var s1 =
        asA(
            () ->
                service.sourceConfirm(
                    created.id(),
                    userSource,
                    new LegalEntityTransferApproveRequest(created.version(), "按成本价")));
    return asATarget(
        () ->
            service.targetConfirm(
                created.id(),
                userTarget,
                new LegalEntityTransferApproveRequest(s1.version(), "同意按此价接收")));
  }

  // ================================================================ 定价

  @Test
  void 转移定价必填且金额由数量乘单价算出() {
    var created = create();
    var detail = asA(() -> service.detail(created.id()));
    assertThat(detail.transfer().valuationBasis()).isEqualTo("TRANSFER_COST");
    // 币种一律大写入库：'cny' 这种写法会让「按币种分组求和」静默分成两组
    assertThat(detail.transfer().currencyCode()).isEqualTo("CNY");
    assertThat(detail.transfer().totalAmount()).isEqualByComparingTo("750.0000");
    assertThat(detail.lines()).hasSize(1);
    assertThat(detail.lines().get(0).amount()).isEqualByComparingTo("750.0000");
    assertThat(detail.pricingNote()).contains("行金额 = 数量 × 单价");
  }

  @Test
  void 单价四舍五入到四位小数且远离零() {
    // 1.0001 × 1.0001 = 1.00020001 → 落 numeric(19,4) 应为 1.0002
    var created =
        asA(
            () ->
                service.create(
                    a,
                    userSource,
                    new LegalEntityTransferCreateRequest(
                        whFrom,
                        whTo,
                        leA1,
                        leA2,
                        "MANUAL",
                        "USD",
                        null,
                        null,
                        "",
                        "",
                        List.of(
                            new LegalEntityTransferLineRequest(
                                skuA,
                                qSellable,
                                new BigDecimal("1.0001"),
                                new BigDecimal("1.0001"))))));
    var detail = asA(() -> service.detail(created.id()));
    assertThat(detail.lines().get(0).amount()).isEqualByComparingTo("1.0002");
    assertThat(detail.transfer().totalAmount()).isEqualByComparingTo("1.0002");
    // 合计必须等于明细之和（迁移里由延迟约束触发器钉死，这里再从读侧验一次）
    assertThat(detail.transfer().totalAmount()).isEqualByComparingTo("1.0002");
  }

  @Test
  void 零定价是允许的但要能看见() {
    var created =
        asA(
            () ->
                service.create(
                    a,
                    userSource,
                    new LegalEntityTransferCreateRequest(
                        whFrom,
                        whTo,
                        leA1,
                        leA2,
                        "MANUAL",
                        "CNY",
                        null,
                        null,
                        "",
                        "",
                        List.of(
                            new LegalEntityTransferLineRequest(
                                skuA, qSellable, new BigDecimal("10"), BigDecimal.ZERO)))));
    var detail = asA(() -> service.detail(created.id()));
    assertThat(detail.transfer().totalAmount()).isEqualByComparingTo("0.0000");
    assertThat(detail.lines().get(0).unitPrice()).isEqualByComparingTo("0.0000");
  }

  @Test
  void 缺币种或非法币种直接拒绝() {
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.create(
                            a,
                            userSource,
                            new LegalEntityTransferCreateRequest(
                                whFrom,
                                whTo,
                                leA1,
                                leA2,
                                "MANUAL",
                                null,
                                null,
                                null,
                                "",
                                "",
                                List.of(
                                    new LegalEntityTransferLineRequest(
                                        skuA, qSellable, BigDecimal.ONE, BigDecimal.ONE))))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须指定币种");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.create(
                            a,
                            userSource,
                            new LegalEntityTransferCreateRequest(
                                whFrom,
                                whTo,
                                leA1,
                                leA2,
                                "MANUAL",
                                "US",
                                null,
                                null,
                                "",
                                "",
                                List.of(
                                    new LegalEntityTransferLineRequest(
                                        skuA, qSellable, BigDecimal.ONE, BigDecimal.ONE))))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ISO 4217");
  }

  @Test
  void 超过四位小数直接拒绝而不是悄悄舍入() {
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.create(
                            a,
                            userSource,
                            new LegalEntityTransferCreateRequest(
                                whFrom,
                                whTo,
                                leA1,
                                leA2,
                                "MANUAL",
                                "CNY",
                                null,
                                null,
                                "",
                                "",
                                List.of(
                                    new LegalEntityTransferLineRequest(
                                        skuA,
                                        qSellable,
                                        new BigDecimal("1.00001"),
                                        BigDecimal.ONE))))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("4 位小数");
  }

  // ================================================================ 两个法人

  @Test
  void 同一法人不能建单_那是普通调拨() {
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.create(
                            a,
                            userSource,
                            new LegalEntityTransferCreateRequest(
                                whFrom,
                                whTo,
                                leA1,
                                leA1,
                                "MANUAL",
                                "CNY",
                                null,
                                null,
                                "",
                                "",
                                List.of(
                                    new LegalEntityTransferLineRequest(
                                        skuA, qSellable, BigDecimal.ONE, BigDecimal.ONE))))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能是同一个法人");
  }

  @Test
  void 两个法人必须都在本租户() {
    // b 租户的法人塞进 a 租户的单：复合外键 + RLS 双重挡住
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.create(
                            a,
                            userSource,
                            new LegalEntityTransferCreateRequest(
                                whFrom,
                                whTo,
                                leA1,
                                idFor(b, 2),
                                "MANUAL",
                                "CNY",
                                null,
                                null,
                                "",
                                "",
                                List.of(
                                    new LegalEntityTransferLineRequest(
                                        skuA, qSellable, BigDecimal.ONE, BigDecimal.ONE))))))
        .isInstanceOf(Exception.class);
  }

  // ================================================================ 双方确认

  @Test
  void 双方确认是两个独立动作且顺序不能倒() {
    var created = create();
    assertThat(created.status()).isEqualTo("DRAFT");
    // 转入方不能越级确认
    assertThatThrownBy(
            () ->
                asATarget(
                    () ->
                        service.targetConfirm(
                            created.id(),
                            userTarget,
                            new LegalEntityTransferApproveRequest(created.version(), ""))))
        .isInstanceOf(ResponseStatusException.class);
    var s1 =
        asA(
            () ->
                service.sourceConfirm(
                    created.id(),
                    userSource,
                    new LegalEntityTransferApproveRequest(created.version(), "")));
    assertThat(s1.status()).isEqualTo("PENDING_TARGET_CONFIRM");
    var s2 =
        asATarget(
            () ->
                service.targetConfirm(
                    created.id(),
                    userTarget,
                    new LegalEntityTransferApproveRequest(s1.version(), "")));
    assertThat(s2.status()).isEqualTo("CONFIRMED");
    var detail = asA(() -> service.detail(created.id()));
    assertThat(detail.approvals()).hasSize(2);
    assertThat(detail.approvals().get(0).side()).isEqualTo("SOURCE");
    assertThat(detail.approvals().get(1).side()).isEqualTo("TARGET");
  }

  @Test
  void 同一个人不能代表两个法人确认() {
    var created = create();
    var s1 =
        asA(
            () ->
                service.sourceConfirm(
                    created.id(),
                    userSource,
                    new LegalEntityTransferApproveRequest(created.version(), "")));
    // 同一个自然人试图再当转入方签一次
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        service.targetConfirm(
                            created.id(),
                            userSource,
                            new LegalEntityTransferApproveRequest(s1.version(), ""))))
        .hasMessageContaining("必须是两个人");
    // 转出方也不能重复确认
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.sourceConfirm(
                            created.id(),
                            userSource,
                            new LegalEntityTransferApproveRequest(s1.version(), ""))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("已经确认过");
  }

  @Test
  void 详情必须如实说明双方法人授权尚未交付() {
    var detail = asA(() -> service.detail(create().id()));
    assertThat(detail.authorizationNote()).contains("17 权限");
    assertThat(detail.authorizationNote()).contains("尚未交付");
    assertThat(detail.ownershipNote()).contains("实收");
  }

  // ================================================================ 货权转移

  @Test
  void 货权真的换法人了_发出扣转出方实收加转入方() {
    seedBalance(leA1, whFrom, locFrom, "100");
    var order = confirmed();
    assertThat(onHand(leA1, whFrom)).isEqualByComparingTo("100");

    var shipped =
        asA(
            () ->
                service.ship(
                    order.id(),
                    userSource,
                    new LegalEntityTransferShipRequest(order.version(), null, "")));
    assertThat(shipped.status()).isEqualTo("IN_TRANSIT");
    // 发出只扣转出法人的余额，转入方一分没多
    assertThat(onHand(leA1, whFrom)).isEqualByComparingTo("40");
    assertThat(onHand(leA2, whTo)).isEqualByComparingTo("0");
    // 在途 60，且与其它模块同口径
    assertThat(sharedInTransit()).isEqualByComparingTo("60");
    // 在途期间货权仍在转出方
    assertThat(inTransitLegalEntity()).isEqualTo(leA1);

    var detail = asA(() -> service.detail(order.id()));
    var lineId = detail.lines().get(0).id();
    var received =
        asATarget(
            () ->
                service.receive(
                    order.id(),
                    userSource,
                    new LegalEntityTransferReceiveRequest(
                        shipped.version(),
                        null,
                        locTo,
                        null,
                        "",
                        List.of(
                            new LegalEntityTransferReceiveLineRequest(
                                lineId, new BigDecimal("60"))))));
    assertThat(received.status()).isEqualTo("COMPLETED");
    // 实收落进**转入法人**的余额行 —— 这就是货权转移本身
    assertThat(onHand(leA1, whFrom)).isEqualByComparingTo("40");
    assertThat(onHand(leA2, whTo)).isEqualByComparingTo("60");
    assertThat(sharedInTransit()).isEqualByComparingTo("0");
    // 台账两条分录：一条记转出方、一条记转入方
    assertThat(countTransactions("TRANSFER_OUT")).isEqualTo(1);
    assertThat(countTransactions("TRANSFER_IN")).isEqualTo(1);
  }

  @Test
  void 转出方可用量不足时拒绝发出且不动库存() {
    seedBalance(leA1, whFrom, locFrom, "10");
    var order = confirmed();
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.ship(
                            order.id(),
                            userSource,
                            new LegalEntityTransferShipRequest(order.version(), null, ""))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("可用量不足");
    assertThat(onHand(leA1, whFrom)).isEqualByComparingTo("10");
    assertThat(sharedInTransit()).isEqualByComparingTo("0");
  }

  @Test
  void 货不在转出法人名下时拒绝发出() {
    // 库存挂在转入法人名下，转出方没有这条余额
    seedBalance(leA2, whFrom, locFrom, "100");
    var order = confirmed();
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.ship(
                            order.id(),
                            userSource,
                            new LegalEntityTransferShipRequest(order.version(), null, ""))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("转出法人名下没有这条库存余额");
  }

  @Test
  void 分次实收后仍在途_不提前算已交割() {
    seedBalance(leA1, whFrom, locFrom, "100");
    var order = confirmed();
    var shipped =
        asA(
            () ->
                service.ship(
                    order.id(),
                    userSource,
                    new LegalEntityTransferShipRequest(order.version(), null, "")));
    var lineId = asA(() -> service.detail(order.id())).lines().get(0).id();
    var first =
        asATarget(
            () ->
                service.receive(
                    order.id(),
                    userSource,
                    new LegalEntityTransferReceiveRequest(
                        shipped.version(),
                        null,
                        locTo,
                        null,
                        "",
                        List.of(
                            new LegalEntityTransferReceiveLineRequest(
                                lineId, new BigDecimal("20"))))));
    assertThat(first.status()).isEqualTo("IN_TRANSIT");
    assertThat(onHand(leA2, whTo)).isEqualByComparingTo("20");
    assertThat(sharedInTransit()).isEqualByComparingTo("40");
    var second =
        asATarget(
            () ->
                service.receive(
                    order.id(),
                    userSource,
                    new LegalEntityTransferReceiveRequest(
                        first.version(),
                        null,
                        locTo,
                        null,
                        "",
                        List.of(
                            new LegalEntityTransferReceiveLineRequest(
                                lineId, new BigDecimal("40"))))));
    assertThat(second.status()).isEqualTo("COMPLETED");
    assertThat(sharedInTransit()).isEqualByComparingTo("0");
  }

  @Test
  void 结案把短少如实记为差异且凭据不因此作废() {
    seedBalance(leA1, whFrom, locFrom, "100");
    var order = confirmed();
    var shipped =
        asA(
            () ->
                service.ship(
                    order.id(),
                    userSource,
                    new LegalEntityTransferShipRequest(order.version(), null, "")));
    var lineId = asA(() -> service.detail(order.id())).lines().get(0).id();
    var partial =
        asATarget(
            () ->
                service.receive(
                    order.id(),
                    userSource,
                    new LegalEntityTransferReceiveRequest(
                        shipped.version(),
                        null,
                        locTo,
                        null,
                        "",
                        List.of(
                            new LegalEntityTransferReceiveLineRequest(
                                lineId, new BigDecimal("50"))))));
    var closed =
        asA(
            () ->
                service.close(
                    order.id(),
                    userSource,
                    new LegalEntityTransferCloseRequest(partial.version(), "运输破损", "")));
    assertThat(closed.status()).isEqualTo("DISCREPANCY");
    assertThat(sharedInTransit()).isEqualByComparingTo("0");
    assertThat(onHand(leA2, whTo)).isEqualByComparingTo("50");
    var detail = asA(() -> service.detail(order.id()));
    assertThat(detail.lines().get(0).discrepancyQuantity()).isEqualByComparingTo("10");
    // 凭据保持 ISSUED：部分货权确实转移了，差额怎么处理是财务判断
    assertThat(detail.settlement().status()).isEqualTo("ISSUED");
  }

  @Test
  void 已发运的单不能作废只能结案() {
    seedBalance(leA1, whFrom, locFrom, "100");
    var order = confirmed();
    var shipped =
        asA(
            () ->
                service.ship(
                    order.id(),
                    userSource,
                    new LegalEntityTransferShipRequest(order.version(), null, "")));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.cancel(
                            order.id(),
                            userSource,
                            new LegalEntityTransferCancelRequest(shipped.version(), "不做了"))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("只能结案");
  }

  @Test
  void 发运前可以作废但必须写原因() {
    var order = create();
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.cancel(
                            order.id(),
                            userSource,
                            new LegalEntityTransferCancelRequest(order.version(), " "))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须写明原因");
    var cancelled =
        asA(
            () ->
                service.cancel(
                    order.id(),
                    userSource,
                    new LegalEntityTransferCancelRequest(order.version(), "需求取消")));
    assertThat(cancelled.status()).isEqualTo("CANCELLED");
  }

  // ================================================================ 内部结算凭据

  @Test
  void 凭据在发出时开具且按跨服务定位键唯一() {
    seedBalance(leA1, whFrom, locFrom, "100");
    var order = confirmed();
    // 未发运就没有凭据：404 而不是空壳
    assertThatThrownBy(() -> asA(() -> service.settlement(order.id())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("尚未开具");

    var shipped =
        asA(
            () ->
                service.ship(
                    order.id(),
                    userSource,
                    new LegalEntityTransferShipRequest(order.version(), null, "")));
    var handoff = asA(() -> service.settlement(order.id()));
    assertThat(handoff.sourceSystem()).isEqualTo("warehouse");
    assertThat(handoff.referenceId()).isEqualTo(order.id());
    assertThat(handoff.documentType()).isEqualTo("CROSS_ENTITY_TRANSFER");
    assertThat(handoff.amount()).isEqualByComparingTo("750.0000");
    assertThat(handoff.currencyCode()).isEqualTo("CNY");
    assertThat(handoff.fromLegalEntityId()).isEqualTo(leA1);
    assertThat(handoff.toLegalEntityId()).isEqualTo(leA2);
    assertThat(handoff.settlementStatus()).isEqualTo("ISSUED");
    assertThat(handoff.lines()).hasSize(1);
    assertThat(handoff.lines().get(0).skuCode()).isEqualTo("SKU-1");
    assertThat(handoff.handoffNote()).contains("UNIQUE");
    // 一张单只开一张凭据（跨服务定位键全局唯一）
    assertThat(
            asA(
                () ->
                    sql.fetchOne(
                            "select count(*) from cross_entity_settlement where reference_id=?",
                            order.id())
                        .get(0, Long.class)))
        .isEqualTo(1L);
    assertThat(shipped.settlementStatus()).isEqualTo("ISSUED");
  }

  @Test
  void 实收后凭据被确认() {
    seedBalance(leA1, whFrom, locFrom, "100");
    var order = confirmed();
    var shipped =
        asA(
            () ->
                service.ship(
                    order.id(),
                    userSource,
                    new LegalEntityTransferShipRequest(order.version(), null, "")));
    var lineId = asA(() -> service.detail(order.id())).lines().get(0).id();
    asATarget(
        () ->
            service.receive(
                order.id(),
                userSource,
                new LegalEntityTransferReceiveRequest(
                    shipped.version(),
                    null,
                    locTo,
                    null,
                    "",
                    List.of(
                        new LegalEntityTransferReceiveLineRequest(lineId, new BigDecimal("60"))))));
    var handoff = asA(() -> service.settlement(order.id()));
    assertThat(handoff.settlementStatus()).isEqualTo("ACKNOWLEDGED");
    assertThat(handoff.lines().get(0).receivedQuantity()).isEqualByComparingTo("60");
  }

  // ================================================================ 时间戳带偏移

  @Test
  void 发出时刻带时区偏移写回不偏移() {
    seedBalance(leA1, whFrom, locFrom, "100");
    var order = confirmed();
    var shippedAt = OffsetDateTime.of(2026, 3, 1, 9, 30, 0, 0, ZoneOffset.ofHours(8));
    asA(
        () ->
            service.ship(
                order.id(),
                userSource,
                new LegalEntityTransferShipRequest(order.version(), shippedAt, "跨时区发运")));
    // 从库里读回：时刻必须与写入的瞬时一致（用 Timestamp.valueOf 会静默偏 -28800s）
    var stored =
        asA(
            () ->
                sql.fetchOne("select shipped_at from cross_entity_transfer where id=?", order.id())
                    .get(0, OffsetDateTime.class));
    assertThat(stored.toInstant()).isEqualTo(shippedAt.toInstant());
    assertThat(stored.getOffset()).isEqualTo(ZoneOffset.ofHours(8));
    var leg =
        asA(
            () ->
                sql.fetchOne(
                        "select shipped_at from inventory_transfer where status='IN_TRANSIT' limit 1")
                    .get(0, OffsetDateTime.class));
    assertThat(leg.toInstant()).isEqualTo(shippedAt.toInstant());
  }

  // ================================================================ 乐观锁与列表

  @Test
  void 版本不符一律冲突() {
    var created = create();
    asA(
        () ->
            service.sourceConfirm(
                created.id(),
                userSource,
                new LegalEntityTransferApproveRequest(created.version(), "")));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.targetConfirm(
                            created.id(),
                            userTarget,
                            new LegalEntityTransferApproveRequest(created.version(), ""))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
  }

  @Test
  void 列表按法人筛选并给出待对方确认的计数() {
    seedBalance(leA1, whFrom, locFrom, "100");
    var order = confirmed();
    var page =
        asA(
            () ->
                service.page(
                    new LegalEntityTransferQuery(
                        leA1, null, null, null, null, null, null, null, 0, 50)));
    assertThat(page.total()).isEqualTo(1);
    // confirmed() 已经把两个方向都确认完，所以此时没有「等对方确认」的单
    assertThat(page.summary().awaitingTargetCount()).isZero();
    assertThat(page.summary().awaitingSourceCount()).isZero();
    assertThat(page.currencyAmounts()).hasSize(1);
    assertThat(page.currencyAmounts().get(0).currencyCode()).isEqualTo("CNY");
    assertThat(page.currencyAmounts().get(0).amount()).isEqualByComparingTo("750.0000");
    assertThat(page.legalEntityOptions()).hasSize(2);
    assertThat(page.valuationOptions()).hasSize(3);

    // 按转入法人筛
    var byTarget =
        asA(
            () ->
                service.page(
                    new LegalEntityTransferQuery(
                        null, leA2, null, null, null, null, null, null, 0, 50)));
    assertThat(byTarget.total()).isEqualTo(1);
    // 按一个没人用的转入法人筛
    var none =
        asA(
            () ->
                service.page(
                    new LegalEntityTransferQuery(
                        null, UUID.randomUUID(), null, null, null, null, null, null, 0, 50)));
    assertThat(none.total()).isZero();

    // 再来一张只被转出方确认的单：它正是「等对方确认」那一列要盯的
    var second = create();
    asA(
        () ->
            service.sourceConfirm(
                second.id(),
                userSource,
                new LegalEntityTransferApproveRequest(second.version(), "")));
    var waiting =
        asA(
            () ->
                service.page(
                    new LegalEntityTransferQuery(
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of("PENDING_TARGET_CONFIRM"),
                        null,
                        null,
                        0,
                        50)));
    assertThat(waiting.total()).isEqualTo(1);
    assertThat(waiting.summary().awaitingTargetCount()).isEqualTo(1);
    assertThat(waiting.rows().get(0).status()).isEqualTo("PENDING_TARGET_CONFIRM");
    // 转入方未确认时不允许发出
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.ship(
                            second.id(),
                            userSource,
                            new LegalEntityTransferShipRequest(
                                waiting.rows().get(0).version(), null, ""))))
        .isInstanceOf(ResponseStatusException.class);
    asA(
        () ->
            service.cancel(
                second.id(),
                userSource,
                new LegalEntityTransferCancelRequest(waiting.rows().get(0).version(), "测试收尾")));

    asA(
        () ->
            service.ship(
                order.id(),
                userSource,
                new LegalEntityTransferShipRequest(order.version(), null, "")));
    var afterShip =
        asA(
            () ->
                service.page(
                    new LegalEntityTransferQuery(
                        null, null, null, null, null, null, null, null, 0, 50)));
    assertThat(afterShip.summary().settlementIssuedCount()).isEqualTo(1);
    assertThat(afterShip.summary().inTransitQuantity()).isEqualByComparingTo("60");
  }

  @Test
  void 分录端点复用台账并说明货权跨法人() throws Exception {
    seedBalance(leA1, whFrom, locFrom, "100");
    var order = confirmed();
    asA(
        () ->
            service.ship(
                order.id(),
                userSource,
                new LegalEntityTransferShipRequest(order.version(), null, "")));
    http.perform(
            get("/warehouse/inventory/cross-entity-transfers/{id}/movements", order.id())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.attribution").value("ORDER_GRANULAR"))
        .andExpect(
            jsonPath("$.attributionNote").value(org.hamcrest.Matchers.containsString("不同法人")))
        .andExpect(jsonPath("$.movements.total").value(1));
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    seedBalance(leA1, whFrom, locFrom, "100");
    var order = confirmed();

    var pageA =
        asA(
            () ->
                service.page(
                    new LegalEntityTransferQuery(
                        null, null, null, null, null, null, null, null, 0, 50)));
    assertThat(pageA.total()).isEqualTo(1);
    var pageB =
        asB(
            () ->
                service.page(
                    new LegalEntityTransferQuery(
                        null, null, null, null, null, null, null, null, 0, 50)));
    assertThat(pageB.total()).isZero();
    assertThat(pageB.summary().transferCount()).isZero();

    // 拿 A 的单 id 给 B → 404（403 会泄露「这张单存在」）
    assertThatThrownBy(() -> asB(() -> service.detail(order.id())))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    // 拿 A 的单 id 让 B 确认 → 建不出痕迹
    assertThatThrownBy(
            () ->
                asB(
                    () ->
                        service.sourceConfirm(
                            order.id(), userSource, new LegalEntityTransferApproveRequest(0L, ""))))
        .isInstanceOf(ResponseStatusException.class);
    // B 看不到 A 的凭据
    assertThatThrownBy(() -> asB(() -> service.settlement(order.id())))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    seedBalance(leA1, whFrom, locFrom, "100");
    var order = confirmed();

    http.perform(
            get("/warehouse/inventory/cross-entity-transfers")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
    http.perform(
            get("/warehouse/inventory/cross-entity-transfers")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.rows[0].transferCode").value(order.transferCode()));
    http.perform(
            get("/warehouse/inventory/cross-entity-transfers/{id}", order.id())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
    http.perform(get("/warehouse/inventory/cross-entity-transfers"))
        .andExpect(status().isUnauthorized());
    // 写端点要 update 权限：只有 read 的角色不能建单
    http.perform(
            post("/warehouse/inventory/cross-entity-transfers")
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content("{\"currencyCode\":\"CNY\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void 断言三_连接池归还不串租户() {
    seedBalance(leA1, whFrom, locFrom, "100");
    var order = confirmed();
    // 池压到 1 条连接：这里交替跑两个租户，任何一次租户上下文泄漏都会串
    for (int i = 0; i < 5; i++) {
      assertThat(
              asA(() ->
                      service.page(
                          new LegalEntityTransferQuery(
                              null, null, null, null, null, null, null, null, 0, 50)))
                  .total())
          .isEqualTo(1);
      assertThat(
              asB(() ->
                      service.page(
                          new LegalEntityTransferQuery(
                              null, null, null, null, null, null, null, null, 0, 50)))
                  .total())
          .isZero();
      assertThat(asATarget(() -> service.detail(order.id())).transfer().id()).isEqualTo(order.id());
      assertThatThrownBy(() -> asB(() -> service.detail(order.id())))
          .isInstanceOf(ResponseStatusException.class);
    }
  }
}
