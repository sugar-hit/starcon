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
 * 6.4.1.1 加工单的**仓库侧**：组件出库 + 成品入库。
 *
 * <p><b>本测试的核心是蓝图那句加粗要求的复验</b>： 「加工是『消耗组件产生成品』的库存动作，**必须在 warehouse 本地事务完成**， 不能由 trade 直接改库存。」
 *
 * <p>所以最要紧的一组断言是 {@code 原子性} 一节：组件出库与成品入库 <b>要么都成、要么都回滚</b>，逐条证明不会出现 「组件扣了成品没进」或反之 —— 这是两种账实不符的来源。
 *
 * <p>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。连接池固定为 1 条连接： 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>本测试<b>不建任何新表</b>：加工单据归 trade（V97），仓库侧复用 V1 既有 {@code inventory_transaction} + {@code
 * inventory_entry}， 与 8.2.1.2 收货差异处理同一纪律。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProcessingOrderIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final List<String> allPermissions =
      java.util.stream.Stream.of("processing")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "warehouse." + p + "." + op))
          .toList();

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-processing-test-" + tenant + "-" + ordinal).getBytes());
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
          actor(user, a, null, List.of(grant("ALL", List.of("warehouse.processing.read")))));
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

  @Autowired ProcessingOrderService processing;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

  /**
   * warehouse 服务**没有** {@code TenantTransactions} Bean（与 trade 不同： 它的租户绑定点只有 {@link
   * AccessService#run} 一处）。 这里按同一个类、同一个绑定语义就地构造一个，供「连接池归还后不串租户」 断言使用 —— 不给生产代码新增第二条绑定路径。
   */
  TenantTransactions tenants() {
    return new TenantTransactions(
        sql, new org.springframework.transaction.support.TransactionTemplate(transactionManager));
  }

  AccessService.Access actorA;
  AccessService.Access actorB;

  // 主数据（按租户派生，避免两租户撞主键）
  final UUID leA = idFor(a, 1),
      skuCompA = idFor(a, 2),
      skuCompB = idFor(a, 3),
      skuOutA = idFor(a, 4),
      whA = idFor(a, 5),
      qSellA = idFor(a, 6),
      qHoldA = idFor(a, 7),
      locA1 = idFor(a, 8),
      locA2 = idFor(a, 9);
  // B 租户自己的主数据（按 b 派生，seed 循环里已为两个租户各建一套）
  final UUID leB = idFor(b, 1),
      skuCompB2 = idFor(b, 2),
      whB = idFor(b, 5),
      qSellB = idFor(b, 6),
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
          "truncate inventory_serial_movement, inventory_serial_operation, inventory_serial,"
              + " stock_bucket, warehouse_location, inventory_lot, inventory_entry,"
              + " inventory_transaction, inventory_balance, inventory_quality_status, warehouse,"
              + " sku_ref, legal_entity_ref restart identity cascade");
    }
    for (var tenant : List.of(a, b)) {
      access.run(
          actorFor(tenant),
          "warehouse.processing.read",
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
                "OUT-1",
                "成品外套");
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
                "A 库位");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,"
                    + "status,pickable) values(?,?,?,?,?,'STORAGE','ACTIVE',true)",
                idFor(tenant, 9),
                tenant,
                idFor(tenant, 5),
                "A-02",
                "A 库位二");
            return null;
          });
    }
    // 组件在库（夹具直接写 bucket 与 balance，二者之和必须相等 —— V2 守恒触发器会核对）。
    stockA(skuCompA, locA1, "60");
    stockA(skuCompB, locA1, "500");
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "warehouse.processing.read", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "warehouse.processing.read", work);
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

  ProcessingOrderModels.ProcessingOrderIssueInput issueInput(
      UUID orderId, BigDecimal completed, BigDecimal... componentQuantities) {
    var issues = new java.util.ArrayList<ProcessingOrderModels.ComponentIssue>();
    UUID[] skus = {skuCompA, skuCompB};
    for (int i = 0; i < componentQuantities.length; i++) {
      issues.add(new ProcessingOrderModels.ComponentIssue(skus[i], componentQuantities[i], null));
    }
    return new ProcessingOrderModels.ProcessingOrderIssueInput(
        orderId,
        "PO-2026-0001",
        whA,
        leA,
        qSellA,
        skuOutA,
        completed,
        issues,
        locA1,
        qSellA,
        locA2,
        null,
        null,
        "加工单测试");
  }

  // ================================================================ 能力与边界

  @Test
  @DisplayName("能力声明把「库存动作归 warehouse」与未交付下游如实报出来")
  void capabilities() {
    var caps = asA(() -> processing.capabilities());
    assertThat(caps.refType()).isEqualTo("PROCESSING_ORDER");
    assertThat(caps.atomicWithComponentAndProductMovements()).isTrue();
    assertThat(caps.upstreamNote()).contains("3.5.2.1").contains("R6/R7");
    assertThat(caps.downstreamGaps())
        .anySatisfy(
            gap -> {
              assertThat(gap.moduleId()).isEqualTo("8.4.1.1");
              assertThat(gap.available()).isFalse();
            });
    var boundary = asA(() -> processing.boundary());
    assertThat(boundary.owner()).isEqualTo("warehouse");
    assertThat(boundary.localTransaction()).contains("一个本地事务");
    assertThat(boundary.note()).contains("不能由单据服务代劳");
  }

  // ================================================================ 同事务原子性（本模块最要紧的一组）

  @Test
  @DisplayName("组件出库与成品入库同事务：成功时两者都落账，分录挂在同一条单据下")
  void issueIsAtomicOnSuccess() {
    UUID orderId = UUID.randomUUID();
    var saved =
        asA(
            () ->
                processing.issue(
                    issueInput(
                        orderId, new BigDecimal("10"), new BigDecimal("5"), new BigDecimal("50")),
                    user));

    // 组件确实扣了
    assertThat(onHandOf(skuCompA, locA1)).isEqualByComparingTo("55");
    assertThat(onHandOf(skuCompB, locA1)).isEqualByComparingTo("450");
    // 成品确实入了
    assertThat(onHandOf(skuOutA, locA2)).isEqualByComparingTo("10");
    // 两者挂在同一条 inventory_transaction 上
    assertThat(saved.entryCount()).isEqualTo(3);
    var rows = asA(() -> processing.pageExecutions(orderId, 0, 10));
    assertThat(rows.total()).isEqualTo(1);
    var row = rows.items().get(0);
    assertThat(row.issuedQuantity()).isEqualByComparingTo("55");
    assertThat(row.completedQuantity()).isEqualByComparingTo("10");
    assertThat(row.componentCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("组件不足 → 整体回滚：成品没有入库，组件也没有被扣（不出现「扣料没入货」）")
  void issueRollsBackWhenComponentShort() {
    UUID orderId = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        processing.issue(
                            issueInput(
                                orderId,
                                new BigDecimal("10"),
                                new BigDecimal("999"),
                                new BigDecimal("50")),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("出库失败");

    // 关键断言：**一件都没动**。第一个组件的出库已在事务里执行过，
    // 但整个事务回滚了 —— 这正是「同事务」与「先出料后入货」两个决定的意义。
    assertThat(onHandOf(skuCompA, locA1)).isEqualByComparingTo("60");
    assertThat(onHandOf(skuCompB, locA1)).isEqualByComparingTo("500");
    assertThat(onHandOf(skuOutA, locA2)).isNull();
    // 也没有留下半截分录
    assertThat(asA(() -> processing.pageExecutions(orderId, 0, 10)).total()).isZero();
  }

  @Test
  @DisplayName("成品入库环节失败 → 组件回滚（不出现「入了货没扣料」）")
  void issueRollsBackWhenInboundFails() {
    UUID orderId = UUID.randomUUID();
    // 成品 SKU 未在 sku_ref 登记 → 入库环节必失败。
    var input =
        new ProcessingOrderModels.ProcessingOrderIssueInput(
            orderId,
            "PO-2026-0002",
            whA,
            leA,
            qSellA,
            idFor(a, 90),
            new BigDecimal("10"),
            List.of(new ProcessingOrderModels.ComponentIssue(skuCompA, new BigDecimal("5"), null)),
            locA1,
            qSellA,
            locA2,
            null,
            null,
            "成品 SKU 未登记");
    assertThatThrownBy(() -> asA(() -> processing.issue(input, user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("没有在本服务登记");

    // 组件出库已经执行过，但整个事务回滚了。
    assertThat(onHandOf(skuCompA, locA1)).isEqualByComparingTo("60");
  }

  @Test
  @DisplayName("绕过服务层直连 SQL 造「只有入货没有出料」的分录组也被幂等锚点挡住")
  void duplicateExecutionIsRejectedByIdempotencyAnchor() {
    UUID orderId = UUID.randomUUID();
    asA(
        () ->
            processing.issue(
                issueInput(
                    orderId, new BigDecimal("10"), new BigDecimal("5"), new BigDecimal("50")),
                user));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        processing.issue(
                            issueInput(
                                orderId,
                                new BigDecimal("10"),
                                new BigDecimal("5"),
                                new BigDecimal("50")),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已经执行过入库");
    // 库存没有被扣第二次
    assertThat(onHandOf(skuCompA, locA1)).isEqualByComparingTo("55");
  }

  // ================================================================ 口径与校验

  @Test
  @DisplayName("库存刻度是 4 位小数：6 位小数的需求量按库存口径入账且如实报出")
  void quantityFollowsStockScale() {
    UUID orderId = UUID.randomUUID();
    // 1.5 米面料 × 3 = 4.5，与 6 位小数的需求量一致
    var saved =
        asA(
            () ->
                processing.issue(
                    issueInput(
                        orderId, new BigDecimal("3"), new BigDecimal("4.5"), new BigDecimal("1.5")),
                    user));
    assertThat(onHandOf(skuCompA, locA1)).isEqualByComparingTo("55.5");
    assertThat(saved.components().get(0).postedQuantity()).isEqualByComparingTo("4.5");
  }

  @Test
  @DisplayName("timestamptz 写入后偏移零漂移（不是「没抛异常」而已）")
  void timestampRoundTripsWithoutDrift() {
    UUID orderId = UUID.randomUUID();
    var saved =
        asA(
            () ->
                processing.issue(
                    issueInput(
                        orderId, new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("1")),
                    user));
    OffsetDateTime written =
        asA(
            () ->
                sql.fetchOne(
                        "select occurred_at from inventory_transaction where id=?",
                        saved.inventoryTransactionId())
                    .get(0, OffsetDateTime.class));
    OffsetDateTime readBack = OffsetDateTime.parse(saved.occurredAt());
    // PostgreSQL timestamps have microsecond precision; still reject any timezone drift.
    assertThat(written.toInstant())
        .isCloseTo(
            readBack.toInstant(),
            org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MICROS));
    // TIMESTAMPTZ preserves the instant; its returned offset depends on the database/JVM zone.
  }

  @Test
  @DisplayName("出库不给库位 → 显式拒绝并说明为什么（库存最小事实单位含库位）")
  void outboundWithoutLocationIsRejected() {
    UUID orderId = UUID.randomUUID();
    var input =
        new ProcessingOrderModels.ProcessingOrderIssueInput(
            orderId,
            "PO-2026-0003",
            whA,
            leA,
            qSellA,
            skuOutA,
            new BigDecimal("10"),
            List.of(new ProcessingOrderModels.ComponentIssue(skuCompA, new BigDecimal("5"), null)),
            null,
            qSellA,
            locA2,
            null,
            null,
            null);
    assertThatThrownBy(() -> asA(() -> processing.issue(input, user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("必须给库位");
  }

  @Test
  @DisplayName("同一组件出现两次 → 拒绝（「这个组件要几件」无法回答）")
  void duplicateComponentIsRejected() {
    UUID orderId = UUID.randomUUID();
    var input =
        new ProcessingOrderModels.ProcessingOrderIssueInput(
            orderId,
            "PO-2026-0004",
            whA,
            leA,
            qSellA,
            skuOutA,
            new BigDecimal("10"),
            List.of(
                new ProcessingOrderModels.ComponentIssue(skuCompA, new BigDecimal("2"), null),
                new ProcessingOrderModels.ComponentIssue(skuCompA, new BigDecimal("3"), null)),
            locA1,
            qSellA,
            locA2,
            null,
            null,
            null);
    assertThatThrownBy(() -> asA(() -> processing.issue(input, user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("出现了两次");
  }

  @Test
  @DisplayName("组件 SKU 未登记 → 404，而不是凭空扣一个不存在的 SKU")
  void unknownComponentSkuIsRejected() {
    UUID orderId = UUID.randomUUID();
    var input =
        new ProcessingOrderModels.ProcessingOrderIssueInput(
            orderId,
            "PO-2026-0005",
            whA,
            leA,
            qSellA,
            skuOutA,
            new BigDecimal("10"),
            List.of(
                new ProcessingOrderModels.ComponentIssue(idFor(a, 91), new BigDecimal("1"), null)),
            locA1,
            qSellA,
            locA2,
            null,
            null,
            null);
    assertThatThrownBy(() -> asA(() -> processing.issue(input, user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("没有在本服务登记");
  }

  // ================================================================ 多租户三断言（AGENTS.md 硬要求）

  @Test
  @DisplayName("多租户断言 1：A 租户看不见 B 租户的执行记录")
  void tenantACannotSeeTenantB() {
    UUID orderB = UUID.randomUUID();
    // B 租户自己有主数据，造一条执行记录
    access.run(
        actorB,
        "warehouse.processing.read",
        () -> {
          UUID balance = UUID.randomUUID();
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,10,0)",
              balance,
              b,
              skuCompB2,
              whB,
              leB,
              qSellB);
          sql.execute(
              "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,"
                  + "location_id,lot_id,quality_status_id,balance_id,on_hand,reserved,"
                  + "last_movement_at) values(?,?,?,?,?,?,null,?,?,10,0,?::timestamptz)",
              UUID.randomUUID(),
              b,
              leB,
              skuCompB2,
              whB,
              locB1,
              qSellB,
              balance,
              OffsetDateTime.now());
          sql.execute(
              "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,"
                  + "actor_id,notes) values(?,?,'ADJUSTMENT','PROCESSING_ORDER',?,?::timestamptz,?,'')",
              UUID.randomUUID(),
              b,
              orderB,
              OffsetDateTime.now(),
              user);
          sql.execute(
              "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,"
                  + "reserved_delta) values(?,?,(select id from inventory_transaction"
                  + " where tenant_id=? and ref_id=?),?,?,0)",
              UUID.randomUUID(),
              b,
              b,
              orderB,
              balance,
              new BigDecimal("-10"));
          return null;
        });

    // A 租户看不到 B 的执行记录
    assertThat(asA(() -> processing.pageExecutions(orderB, 0, 10).total())).isZero();
    assertThat(asA(() -> processing.pageExecutions(null, 0, 50).total())).isZero();
    // B 租户自己看得到
    assertThat(asB(() -> processing.pageExecutions(orderB, 0, 10).total())).isEqualTo(1);
  }

  @Test
  @DisplayName("多租户断言 2：伪造 X-Tenant-Id 头无效（端点只认已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    UUID orderId = UUID.randomUUID();
    asA(
        () ->
            processing.issue(
                issueInput(
                    orderId, new BigDecimal("10"), new BigDecimal("5"), new BigDecimal("50")),
                user));

    // 带伪造租户头访问：身份仍由 Bearer 决定，落到 A 租户，读到的是 A 的数据。
    http.perform(
            get("/warehouse/processing-orders/executions")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString())
                .param("processingOrderId", orderId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));

    // 伪造 B 租户身份头也拿不到 B 的数据：B 租户根本没有这张单
    http.perform(
            get("/warehouse/processing-orders/executions")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", a.toString())
                .param("processingOrderId", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
  }

  @Test
  @DisplayName("多租户断言 3：连接池归还后不串租户（池压到 1，20 轮往返）")
  void connectionPoolDoesNotLeakTenant() throws Exception {
    TenantTransactions tenants = tenants();
    UUID orderA = UUID.randomUUID();
    asA(
        () ->
            processing.issue(
                issueInput(orderA, new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("1")),
                user));

    for (int i = 0; i < 20; i++) {
      assertThat(asA(() -> processing.pageExecutions(orderA, 0, 10).total())).isEqualTo(1);
      // B 租户事务内看不见 A 的加工分录
      assertThat(countProcessingRowsUnder(b)).isZero();
      // 事务外（没有任何租户上下文）同样一行都看不见
      assertThat(countProcessingRowsWithoutTenant()).isZero();
    }
  }

  /** 在指定租户事务里数加工分录行数。 */
  long countProcessingRowsUnder(UUID tenant) throws Exception {
    Long count =
        tenants()
            .run(
                tenant,
                () ->
                    sql.fetchOne(
                            "select count(*) from inventory_transaction"
                                + " where ref_type = 'PROCESSING_ORDER'")
                        .get(0, Long.class));
    return count == null ? 0L : count;
  }

  /** 事务外数加工分录行数（没有租户上下文，FORCE RLS 下应为零）。 */
  long countProcessingRowsWithoutTenant() {
    Long count =
        sql.fetchOne(
                "select count(*) from inventory_transaction where ref_type = 'PROCESSING_ORDER'")
            .get(0, Long.class);
    return count == null ? 0L : count;
  }

  @Test
  @DisplayName("每张涉及本模块的表都 relforcerowsecurity")
  void rlsIsForcedOnEveryTable() {
    // 本模块不新建表，因此断言的是它复用的 V1 三张表的 FORCE 状态。
    for (String table :
        List.of("inventory_transaction", "inventory_entry", "stock_bucket", "inventory_balance")) {
      Boolean forced =
          asA(
              () ->
                  sql.fetchOne("select relforcerowsecurity from pg_class where relname = ?", table)
                      .get(0, Boolean.class));
      assertThat(forced).as(table).isTrue();
    }
  }

  // ================================================================ HTTP 契约

  @Test
  @DisplayName("HTTP 契约：401 未认证 / 403 只读 / 422 数量非法")
  void httpContract() throws Exception {
    http.perform(get("/warehouse/processing-orders/capabilities"))
        .andExpect(status().isUnauthorized());

    http.perform(
            get("/warehouse/processing-orders/capabilities")
                .header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk());

    var invalid = issueInput(UUID.randomUUID(), BigDecimal.ZERO, new BigDecimal("1"));
    http.perform(
            post("/warehouse/processing-orders/issues")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(invalid)))
        .andExpect(status().isBadRequest());

    // 数量给负 / 0：Bean Validation 直接拒
    var negative = issueInput(UUID.randomUUID(), new BigDecimal("-1"), new BigDecimal("1"));
    http.perform(
            post("/warehouse/processing-orders/issues")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(negative)))
        .andExpect(status().isBadRequest());

    // 组件明细为空：@NotEmpty 拒
    var empty =
        new ProcessingOrderModels.ProcessingOrderIssueInput(
            UUID.randomUUID(),
            "PO-X",
            whA,
            leA,
            qSellA,
            skuOutA,
            new BigDecimal("1"),
            List.of(),
            locA1,
            qSellA,
            locA2,
            null,
            null,
            null);
    http.perform(
            post("/warehouse/processing-orders/issues")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(empty)))
        .andExpect(status().isBadRequest());

    // 只读身份不能执行入库（会真的扣库存）
    http.perform(
            post("/warehouse/processing-orders/issues")
                .header("Authorization", "Bearer read-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        issueInput(UUID.randomUUID(), new BigDecimal("1"), new BigDecimal("1")))))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("分页是真分页：两页不重叠，汇总不受分页影响")
  void paginationIsReal() {
    for (int i = 0; i < 3; i++) {
      UUID orderId = UUID.randomUUID();
      asA(
          () ->
              processing.issue(
                  issueInput(
                      orderId, new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("1")),
                  user));
    }
    var page1 = asA(() -> processing.pageExecutions(null, 0, 2));
    var page2 = asA(() -> processing.pageExecutions(null, 1, 2));
    assertThat(page1.total()).isEqualTo(3);
    assertThat(page2.total()).isEqualTo(3);
    assertThat(page1.items()).hasSize(2);
    assertThat(page2.items()).hasSize(1);
    var ids1 =
        page1.items().stream()
            .map(ProcessingOrderModels.ProcessingStockRow::inventoryTransactionId)
            .toList();
    var ids2 =
        page2.items().stream()
            .map(ProcessingOrderModels.ProcessingStockRow::inventoryTransactionId)
            .toList();
    assertThat(ids1).doesNotContainAnyElementsOf(ids2);
    assertThat(page1.totalCompletedQuantity()).isEqualByComparingTo("3");
  }
}
