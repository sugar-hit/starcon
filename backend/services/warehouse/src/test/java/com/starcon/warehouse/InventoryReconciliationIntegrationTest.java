package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryReconciliationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 7.1.2.2 账实核对：账实一致也要出结论、差异定位到具体流水、可疑四类、时区不丢偏移、 范围校验，以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>夹具写法与 {@link InventoryLedgerIntegrationTest} 完全一致（同样的 Testcontainers、 同样的 fake identity
 * server、同样的按租户派生 UUID、同样的 TRUNCATE 清场）。
 *
 * <p><b>造差异的手法</b>：夹具 {@code txn(...)} 只插流水**不动余额快照** （真实系统里流水与余额由同一事务推进，这里刻意拆开，才能造出「记了流水没动账」的漂移）。
 * 于是「快照 = N，Σ 分录 = M」时差异恒为 M − N，想造哪一类差异就摆哪一组数。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InventoryReconciliationIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      Stream.of("inventory")
          .flatMap(
              p ->
                  Stream.of("read", "create", "update", "delete")
                      .map(op -> "warehouse." + p + "." + op))
          .toList();

  static final UUID leA = idFor(a, 1);
  static final UUID skuA = idFor(a, 2);
  static final UUID whSh = idFor(a, 3);
  static final UUID whSz = idFor(a, 4);
  static final UUID qSellable = idFor(a, 5);

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-warehouse-test-" + tenant + "-" + ordinal).getBytes());
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
      // 只有读权限：核对要落一份不可变的结论，写权限必须真的区分出来
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
  }

  @Autowired InventoryReconciliationService service;
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
    // TRUNCATE 不触发行级触发器（append-only 触发器只挡 UPDATE/DELETE），以超级用户
    // 连接执行可绕过 FORCE RLS —— 只用于测试夹具重建，不给应用角色开这个口子。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate inventory_reconciliation_finding, inventory_reconciliation_run,"
              + " inventory_entry, inventory_transaction, inventory_balance,"
              + " inventory_quality_status, warehouse, sku_ref, legal_entity_ref"
              + " restart identity cascade");
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
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
                idFor(tenant, 3),
                tenant,
                "WH-SH",
                "上海仓",
                "CN",
                "Asia/Shanghai");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
                idFor(tenant, 4),
                tenant,
                "WH-SZ",
                "深圳仓",
                "CN",
                "Asia/Shanghai");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 5),
                tenant,
                "SELLABLE",
                "可售",
                "SELLABLE");
            return null;
          });
    }
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  // ================================================================ 夹具写入

  /** 一条余额行，返回它的 id。onHand / reserved 是**快照值**，夹具不会随流水自动推进。 */
  UUID balance(UUID warehouse, String onHand, String reserved) {
    var id = UUID.randomUUID();
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,?)",
              id,
              a,
              skuA,
              warehouse,
              leA,
              qSellable,
              new BigDecimal(onHand),
              new BigDecimal(reserved));
          return null;
        });
    return id;
  }

  /**
   * 写一笔库存业务（业务单 + 分录），**不动余额快照** —— 差异就是这么造出来的。
   *
   * <p>两个时间都用 {@code ?::timestamptz} 绑定，且 {@code created_at} 排在 {@code notes} 之前：cast 落在 values
   * 列表末位会让 jOOQ 整体绑定错位一格 （AGENTS.md 与 7.1.2.1 测试的同一条纪律），所以最后一个绑定是普通列 notes。
   *
   * <p>{@code createdAt} 为 null 时落 {@code now()}，即"当时就录了"； 要造迟到补记/并发碰撞时显式传值。
   */
  UUID txn(
      String kind,
      String refType,
      OffsetDateTime occurredAt,
      UUID balanceId,
      String onHandDelta,
      String reservedDelta) {
    return txn(kind, refType, occurredAt, balanceId, onHandDelta, reservedDelta, null);
  }

  UUID txn(
      String kind,
      String refType,
      OffsetDateTime occurredAt,
      UUID balanceId,
      String onHandDelta,
      String reservedDelta,
      OffsetDateTime createdAt) {
    var txId = UUID.randomUUID();
    var refId = UUID.randomUUID();
    var at = createdAt == null ? now() : createdAt.truncatedTo(ChronoUnit.MILLIS);
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,"
                  + "actor_id,created_at,notes) values(?,?,?,?,?,?::timestamptz,?,?::timestamptz,?)",
              txId,
              a,
              kind,
              refType,
              refId,
              occurredAt,
              user,
              at,
              "夹具");
          sql.execute(
              "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,"
                  + "reserved_delta) values(?,?,?,?,?,?)",
              UUID.randomUUID(),
              a,
              txId,
              balanceId,
              new BigDecimal(onHandDelta),
              new BigDecimal(reservedDelta));
          return null;
        });
    return txId;
  }

  /** 租户 B 自己的余额行 + 一笔流水（夹具只给 A 建余额行，B 的单独建）。 */
  UUID tenantBFixture(OffsetDateTime at, String onHand) {
    var balB = UUID.randomUUID();
    var txB = UUID.randomUUID();
    access.run(
        actorB,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
              balB,
              b,
              idFor(b, 2),
              idFor(b, 3),
              idFor(b, 1),
              idFor(b, 5),
              new BigDecimal(onHand));
          sql.execute(
              "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,"
                  + "actor_id,notes) values(?,?,?,?,?,?::timestamptz,?,?)",
              txB,
              b,
              "RECEIPT",
              "PO",
              UUID.randomUUID(),
              at,
              user,
              "B 的入库");
          sql.execute(
              "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,"
                  + "reserved_delta) values(?,?,?,?,?,0)",
              UUID.randomUUID(),
              b,
              txB,
              balB,
              new BigDecimal("7"));
          return null;
        });
    return balB;
  }

  static OffsetDateTime now() {
    return OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
  }

  /** 夹具流水都落在这个窗口内，避免落到"区间后"桶里去干扰断言。 */
  static ReconciliationRequest window(OffsetDateTime from, OffsetDateTime to) {
    return new ReconciliationRequest(from, to, null, null, null);
  }

  static ReconciliationRequest last30Days() {
    var now = now();
    return window(now.minusDays(30), now);
  }

  ReconciliationResult run(AccessService.Access who, ReconciliationRequest request) {
    return access.run(who, "warehouse.inventory.update", () -> service.run(request, who.userId()));
  }

  /** 只带某一类可疑的发现，测试里读起来比 filter 链清楚。 */
  static List<ReconciliationFinding> of(
      List<ReconciliationFinding> all, ReconciliationSuspectCategory category) {
    return all.stream().filter(s -> s.category() == category).toList();
  }

  // ================================================================ 一致与差异

  @Test
  void 账实一致也要出结论_并可追溯核对了什么() {
    var bal = balance(whSh, "40", "0");
    var now = now();
    txn("RECEIPT", "PO", now.minusDays(3), bal, "40", "0");
    txn("SHIPMENT", "ORDER", now.minusDays(2), bal, "-10", "0");
    txn("RECEIPT", "PO", now.minusDays(1), bal, "10", "0");

    var r = run(actorA, last30Days()).run();

    assertThat(r.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
    assertThat(r.onHandDiff()).isEqualByComparingTo("0");
    assertThat(r.reservedDiff()).isEqualByComparingTo("0");
    // 「一致」同样要能回答"核了什么"，否则这份记录无法复现
    assertThat(r.checkedBalanceCount()).isEqualTo(1);
    assertThat(r.checkedEntryCount()).isEqualTo(3);
    assertThat(r.checkedTransactionCount()).isEqualTo(3);
    assertThat(r.expectedOnHand()).isEqualByComparingTo("40");
    assertThat(r.snapshotOnHand()).isEqualByComparingTo("40");
    assertThat(r.periodOnHand()).isEqualByComparingTo("40");
    assertThat(r.openingOnHand()).isEqualByComparingTo("0");
    assertThat(r.afterWindowOnHand()).isEqualByComparingTo("0");
    // opening + period 恰好等于 expected
    assertThat(r.expectedOnHand()).isEqualByComparingTo(r.openingOnHand().add(r.periodOnHand()));
    assertThat(r.discrepancyCount()).isZero();
    assertThat(r.suspectCount()).isZero();
    // 差异为零也要写清核了哪个区间、多少笔分录
    assertThat(r.verdictNote()).contains("1 条余额行").contains("3 笔分录").contains("完全一致");
    assertThat(r.scopeType()).isEqualTo(ReconciliationScopeType.ALL);
    assertThat(r.scopeLabel()).isEqualTo("全部余额行");
  }

  @Test
  void 差异等于累计减快照_并给出逐行差异明细() {
    // 快照 100，流水累计 65 → 差 -35（记了流水没动账）
    var bal = balance(whSh, "100", "0");
    var now = now();
    txn("RECEIPT", "PO", now.minusDays(3), bal, "40", "0");
    txn("RECEIPT", "PO", now.minusDays(2), bal, "25", "0");

    var result = run(actorA, last30Days());
    var r = result.run();

    assertThat(r.status()).isEqualTo(ReconciliationStatus.DISCREPANT);
    assertThat(r.expectedOnHand()).isEqualByComparingTo("65");
    assertThat(r.snapshotOnHand()).isEqualByComparingTo("100");
    assertThat(r.onHandDiff()).isEqualByComparingTo("-35");
    assertThat(r.discrepancyCount()).isEqualTo(1);

    assertThat(result.discrepancies()).hasSize(1);
    var d = result.discrepancies().get(0);
    assertThat(d.kind()).isEqualTo(ReconciliationFindingKind.DISCREPANCY);
    assertThat(d.balanceId()).isEqualTo(bal);
    assertThat(d.skuCode()).isEqualTo("SKU-1");
    assertThat(d.warehouseCode()).isEqualTo("WH-SH");
    assertThat(d.legalEntityCode()).isEqualTo("LE-1");
    assertThat(d.qualityStatusCode()).isEqualTo("SELLABLE");
    assertThat(d.onHandSum()).isEqualByComparingTo("65");
    assertThat(d.snapshotOnHand()).isEqualByComparingTo("100");
    assertThat(d.onHandDiff()).isEqualByComparingTo("-35");
    assertThat(d.entryCount()).isEqualTo(2);
    assertThat(d.transactionCount()).isEqualTo(2);
    // 差异行要能指到最后一笔动过它的流水
    assertThat(d.transactionId()).isNotNull();
    assertThat(d.reason()).contains("65").contains("100");
    // 时间跨度（差异明细的可疑区间）是这条余额行全部流水的发生范围
    assertThat(d.windowStart()).isNotNull();
    assertThat(d.windowEnd()).isNotNull();
    assertThat(d.windowEnd()).isAfter(d.windowStart());
  }

  @Test
  void 预占口径也参与核对() {
    var bal = balance(whSh, "20", "0");
    var now = now();
    txn("RECEIPT", "PO", now.minusDays(1), bal, "20", "0");
    txn("RESERVE", "ORDER", now, bal, "0", "8");

    var r = run(actorA, last30Days()).run();
    // 在手一致、预占不一致 → 整体仍是有差异
    assertThat(r.onHandDiff()).isEqualByComparingTo("0");
    assertThat(r.reservedDiff()).isEqualByComparingTo("8");
    assertThat(r.status()).isEqualTo(ReconciliationStatus.DISCREPANT);
    assertThat(r.discrepancyCount()).isEqualTo(1);
  }

  @Test
  void 区间之前的历史进入期初_差异判定不受区间影响() {
    var bal = balance(whSh, "100", "0");
    var now = now();
    txn("RECEIPT", "PO", now.minusDays(60), bal, "40", "0");
    txn("RECEIPT", "PO", now.minusDays(2), bal, "30", "0");

    var r = run(actorA, window(now.minusDays(30), now)).run();
    // 区间只决定"本期动了多少"与"去哪段时间找可疑"，不决定账对不对
    assertThat(r.openingOnHand()).isEqualByComparingTo("40");
    assertThat(r.periodOnHand()).isEqualByComparingTo("30");
    assertThat(r.expectedOnHand()).isEqualByComparingTo("70");
    assertThat(r.snapshotOnHand()).isEqualByComparingTo("100");
    assertThat(r.onHandDiff()).isEqualByComparingTo("-30");
    // 区间内只有一笔分录，但差异判定覆盖了 60 天前那笔
    assertThat(r.checkedEntryCount()).isEqualTo(1);
  }

  @Test
  void 区间后的流水单独成档并在结论里点出来() {
    var bal = balance(whSh, "5", "0");
    var now = now();
    txn("RECEIPT", "PO", now.minusDays(2), bal, "5", "0");
    // 落在核对区间**之后**：错期录入 / 事后补记
    txn("ADJUSTMENT", "MANUAL", now.plusDays(3), bal, "2", "0");

    var r = run(actorA, window(now.minusDays(10), now)).run();
    assertThat(r.periodOnHand()).isEqualByComparingTo("5");
    assertThat(r.afterWindowOnHand()).isEqualByComparingTo("2");
    // 三个桶恰好把全部历史分完
    assertThat(r.expectedOnHand()).isEqualByComparingTo("7");
    assertThat(r.expectedOnHand())
        .isEqualByComparingTo(r.openingOnHand().add(r.periodOnHand()).add(r.afterWindowOnHand()));
    // 快照 5，累计 5(区间内) + 2(区间后) = 7 → 差 +2
    assertThat(r.onHandDiff()).isEqualByComparingTo("2");
    assertThat(r.verdictNote()).contains("区间结束后仍有流水");
  }

  // ================================================================ 可疑定位

  @Test
  void 漏记_快照非零但区间内一条流水都没有() {
    // 快照 30，全历史零分录 → 没有任何流水能解释它
    var bal = balance(whSh, "30", "0");

    var result = run(actorA, last30Days());

    var missing = of(result.suspects(), ReconciliationSuspectCategory.MISSING);
    assertThat(missing).hasSize(1);
    var m = missing.get(0);
    assertThat(m.balanceId()).isEqualTo(bal);
    // 漏记的结论本身就是"指不出哪一笔"，所以 transactionId 必须为 null 而不是随便指一笔
    assertThat(m.transactionId()).isNull();
    assertThat(m.severity()).isEqualTo(ReconciliationSeverity.WARN);
    assertThat(m.reason()).contains("没有任何分录能解释");
    assertThat(m.onHandDiff()).isEqualByComparingTo("-30");
    assertThat(result.run().suspectCount()).isEqualTo(1);
  }

  @Test
  void 一致的账不产生可疑线索() {
    var bal = balance(whSh, "10", "0");
    txn("RECEIPT", "PO", now(), bal, "10", "0");
    var result = run(actorA, last30Days());
    // 没有差异就没有需要解释的东西：不为平了的行做无谓的扫描
    assertThat(result.suspects()).isEmpty();
    assertThat(result.suspectWindows()).isEmpty();
  }

  @Test
  void 重复记_同余额行同类型同数量同操作人相隔一分钟内的两笔() {
    var bal = balance(whSh, "100", "0");
    var at = now().minusDays(2);
    var first = txn("RECEIPT", "PO", at, bal, "12", "0", at);
    // 同类型同数量同操作人，但来源单据不同 → V1 的 UNIQUE(tenant_id,ref_type,ref_id) 挡不住
    var second =
        txn("RECEIPT", "MANUAL_GRN", at.plusSeconds(20), bal, "12", "0", at.plusSeconds(20));

    var result = run(actorA, last30Days());
    var dupes = of(result.suspects(), ReconciliationSuspectCategory.DUPLICATE);
    assertThat(dupes).hasSize(1);
    var d = dupes.get(0);
    assertThat(d.severity()).isEqualTo(ReconciliationSeverity.HIGH);
    // 怀疑要成对给出：跟谁重复、差多少秒都要有
    assertThat(d.transactionId()).isEqualTo(second);
    assertThat(d.relatedTransactionId()).isEqualTo(first);
    assertThat(d.gapSeconds()).isEqualTo(20L);
    assertThat(d.balanceId()).isEqualTo(bal);
    assertThat(d.skuCode()).isEqualTo("SKU-1");
    assertThat(d.refType()).isEqualTo("MANUAL_GRN");
    // 可疑流水要落在按天的时间桶里
    assertThat(d.windowEnd()).isEqualTo(d.windowStart().plusDays(1));
  }

  @Test
  void 超过并发窗口的同签名两笔不算重复记() {
    var bal = balance(whSh, "100", "0");
    var at = now().minusDays(2);
    txn("RECEIPT", "PO", at, bal, "12", "0", at);
    // 同样同类型同数量，但隔了 10 分钟：这是两次真实的入库，不是重复
    txn("RECEIPT", "MANUAL_GRN", at.plusMinutes(10), bal, "12", "0", at.plusMinutes(10));
    var result = run(actorA, last30Days());
    assertThat(of(result.suspects(), ReconciliationSuspectCategory.DUPLICATE)).isEmpty();
  }

  @Test
  void 并发丢失_同一余额行在写入时刻上撞在一起() {
    var bal = balance(whSh, "100", "0");
    var at = now().minusDays(2);
    // 数量不同（否则会被重复记先命中），但**写入时刻**相差 15 秒
    var first = txn("RECEIPT", "PO", at, bal, "12", "0", at);
    var second = txn("SHIPMENT", "ORDER", at.plusSeconds(15), bal, "-3", "0", at.plusSeconds(15));

    var result = run(actorA, last30Days());
    var lost = of(result.suspects(), ReconciliationSuspectCategory.LOST_UPDATE);
    assertThat(lost).hasSize(1);
    assertThat(lost.get(0).severity()).isEqualTo(ReconciliationSeverity.HIGH);
    assertThat(lost.get(0).transactionId()).isEqualTo(second);
    assertThat(lost.get(0).relatedTransactionId()).isEqualTo(first);
    assertThat(lost.get(0).gapSeconds()).isEqualTo(15L);
    // 数量不同 → 不该被误判成重复记
    assertThat(of(result.suspects(), ReconciliationSuspectCategory.DUPLICATE)).isEmpty();
  }

  @Test
  void 写入时刻离得很远不算并发丢失() {
    var bal = balance(whSh, "100", "0");
    var at = now().minusDays(2);
    txn("RECEIPT", "PO", at, bal, "12", "0", at);
    txn("SHIPMENT", "ORDER", at.plusSeconds(15), bal, "-3", "0", at.plusHours(1));
    var result = run(actorA, last30Days());
    assertThat(of(result.suspects(), ReconciliationSuspectCategory.LOST_UPDATE)).isEmpty();
  }

  @Test
  void 迟到补记_入库时刻远晚于声称的发生时刻() {
    var bal = balance(whSh, "100", "0");
    // 声称 3 天前发生，今天才录进来
    var late =
        txn("ADJUSTMENT", "MANUAL", now().minusDays(3), bal, "7", "0", now().minusSeconds(30));

    var result = run(actorA, last30Days());
    var backfills = of(result.suspects(), ReconciliationSuspectCategory.BACKFILL);
    assertThat(backfills).hasSize(1);
    assertThat(backfills.get(0).transactionId()).isEqualTo(late);
    assertThat(backfills.get(0).latenessSeconds()).isGreaterThan(86400L);
    assertThat(backfills.get(0).severity()).isEqualTo(ReconciliationSeverity.WARN);
  }

  @Test
  void 当天补记不算跨期补记() {
    var bal = balance(whSh, "100", "0");
    // 迟了 2 小时，还不到一天
    txn("ADJUSTMENT", "MANUAL", now().minusHours(2), bal, "7", "0", now().minusSeconds(30));
    var result = run(actorA, last30Days());
    assertThat(of(result.suspects(), ReconciliationSuspectCategory.BACKFILL)).isEmpty();
  }

  @Test
  void 一笔流水可以同时命中多个可疑信号() {
    var bal = balance(whSh, "100", "0");
    var at = now().minusDays(4);
    txn("ADJUSTMENT", "MANUAL", at, bal, "6", "0", now().minusDays(2));
    // 同类型同数量同操作人 + 相隔 30 秒 → 重复记；入库时刻又迟了两天 → 迟到补记
    var second =
        txn(
            "ADJUSTMENT",
            "MANUAL2",
            at.plusSeconds(30),
            bal,
            "6",
            "0",
            now().minusDays(2).plusSeconds(30));

    var result = run(actorA, last30Days());
    // 多一条线索比少一条强：两类都成立就都报
    var dupes = of(result.suspects(), ReconciliationSuspectCategory.DUPLICATE);
    assertThat(dupes).hasSize(1);
    assertThat(dupes.get(0).transactionId()).isEqualTo(second);
    assertThat(of(result.suspects(), ReconciliationSuspectCategory.BACKFILL)).isNotEmpty();
  }

  @Test
  void 可疑区间排名按严重度排序并给出按天的时间桶() {
    var now = now();
    // 两条余额行各管一个时间桶，避免"写入时刻撞车"把两个桶串在一起
    var bal1 = balance(whSh, "100", "0");
    var bal2 = balance(whSz, "100", "0");
    // 桶一（3 天前）：迟到补记 + 重复记 → 含 HIGH
    txn("ADJUSTMENT", "MANUAL", now.minusDays(3), bal1, "5", "0", now.minusDays(1));
    txn("RECEIPT", "PO", now.minusDays(3), bal1, "9", "0", now.minusDays(3));
    txn(
        "RECEIPT",
        "MANUAL_GRN",
        now.minusDays(3).plusSeconds(20),
        bal1,
        "9",
        "0",
        now.minusDays(3).plusSeconds(20));
    // 桶二（2 天前）：只迟到了一条补记 → WARN
    txn("ADJUSTMENT", "MANUAL2", now.minusDays(2), bal2, "4", "0", now);

    var windows = run(actorA, last30Days()).suspectWindows();

    assertThat(windows).hasSize(2);
    // 有 HIGH 的那一段排前面
    assertThat(windows.get(0).severity()).isEqualTo(ReconciliationSeverity.HIGH);
    assertThat(windows.get(0).windowStart()).isBefore(windows.get(1).windowStart());
    assertThat(windows.get(0).suspectCount()).isGreaterThanOrEqualTo(2);
    assertThat(windows.get(1).severity()).isEqualTo(ReconciliationSeverity.WARN);
    assertThat(windows.get(1).suspectCount()).isEqualTo(1);
    // 时间桶按天对齐，与查账的自然粒度一致
    assertThat(windows.get(0).windowEnd()).isEqualTo(windows.get(0).windowStart().plusDays(1));
  }

  // ================================================================ 范围与校验

  @Test
  void 范围按仓库过滤_只核选中的那部分() {
    var sh = balance(whSh, "100", "0");
    var sz = balance(whSz, "50", "0");
    var now = now();
    txn("RECEIPT", "PO", now.minusDays(1), sh, "10", "0");
    txn("RECEIPT", "PO", now.minusDays(1), sz, "10", "0");

    var all = run(actorA, window(now.minusDays(30), now)).run();
    assertThat(all.checkedBalanceCount()).isEqualTo(2);
    assertThat(all.discrepancyCount()).isEqualTo(2);

    var scoped =
        run(actorA, new ReconciliationRequest(now.minusDays(30), now, whSz, null, null)).run();
    assertThat(scoped.scopeType()).isEqualTo(ReconciliationScopeType.WAREHOUSE);
    assertThat(scoped.scopeKey()).isEqualTo(whSz);
    // 范围名固化下来，维表改名后三个月前的记录仍读得懂
    assertThat(scoped.scopeLabel()).isEqualTo("仓库 WH-SZ");
    assertThat(scoped.checkedBalanceCount()).isEqualTo(1);
    assertThat(scoped.discrepancyCount()).isEqualTo(1);
    assertThat(scoped.snapshotOnHand()).isEqualByComparingTo("50");
  }

  @Test
  void 区间倒置直接报错而不是返回空结果() {
    var now = now();
    assertThatThrownBy(() -> run(actorA, window(now, now.minusDays(1))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("左闭右开");
  }

  @Test
  void 区间起止相同也报错() {
    var now = now();
    assertThatThrownBy(() -> run(actorA, window(now, now)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 范围只能选一个维度() {
    var now = now();
    assertThatThrownBy(
            () -> run(actorA, new ReconciliationRequest(now.minusDays(30), now, whSh, skuA, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("三选一");
  }

  @Test
  void 范围不属于本租户时报400而不是悄悄核全量() {
    balance(whSh, "10", "0");
    var now = now();
    assertThatThrownBy(
            () ->
                run(
                    actorA,
                    new ReconciliationRequest(now.minusDays(30), now, idFor(b, 3), null, null)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或无权查看");
  }

  @Test
  void 未认证返回401() throws Exception {
    http.perform(
            post("/warehouse/inventory/reconciliation")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isUnauthorized());
    http.perform(get("/warehouse/inventory/reconciliation")).andExpect(status().isUnauthorized());
  }

  @Test
  void 只有读权限不能发起核对() throws Exception {
    // 核对要落一份不可变的结论，权限码必须真的区分读与写
    http.perform(
            post("/warehouse/inventory/reconciliation")
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    http.perform(
            get("/warehouse/inventory/reconciliation").header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk());
  }

  // ================================================================ 落库与回查

  @Test
  void 结论与发现落库后可回查_分页是真分页() {
    var bal = balance(whSh, "100", "0");
    var at = now().minusDays(2);
    txn("RECEIPT", "PO", at, bal, "12", "0", at);
    txn("RECEIPT", "MANUAL_GRN", at.plusSeconds(20), bal, "12", "0", at.plusSeconds(20));
    var result = run(actorA, last30Days());

    var runs = access.run(actorA, "warehouse.inventory.read", () -> service.runs(0, 20, null));
    assertThat(runs.total()).isEqualTo(1);
    var stored = runs.runs().get(0);
    assertThat(stored.id()).isEqualTo(result.run().id());
    assertThat(stored.status()).isEqualTo(ReconciliationStatus.DISCREPANT);
    // 落库后读回来的数字与内存里算出来的一致
    assertThat(stored.onHandDiff()).isEqualByComparingTo(result.run().onHandDiff());
    assertThat(stored.verdictNote()).isEqualTo(result.run().verdictNote());
    assertThat(stored.triggeredBy()).isEqualTo(user);
    assertThat(stored.createdAt()).isNotNull();

    var disc =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.findings(result.run().id(), "DISCREPANCY", 0, 50));
    assertThat(disc.total()).isEqualTo(1);
    assertThat(disc.findings().get(0).balanceId()).isEqualTo(bal);
    // 快照 100，累计 12+12=24 → 差 -76
    assertThat(disc.findings().get(0).onHandDiff()).isEqualByComparingTo("-76");
    assertThat(disc.findings().get(0).reason()).isNotBlank();

    var sus =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.findings(result.run().id(), "SUSPECT", 0, 50));
    // 两笔相隔 20 秒、同类型同数量：**同时**构成重复记（签名相同）与并发丢失（写入时刻撞车），
    // 两条线索都成立就该都落库
    assertThat(sus.total()).isEqualTo(2);
    assertThat(of(sus.findings(), ReconciliationSuspectCategory.DUPLICATE)).hasSize(1);
    assertThat(of(sus.findings(), ReconciliationSuspectCategory.LOST_UPDATE)).hasSize(1);
    var storedSuspect = of(sus.findings(), ReconciliationSuspectCategory.DUPLICATE).get(0);
    // 成对关系落库后仍在，不只是内存里的临时结论
    assertThat(storedSuspect.relatedTransactionId()).isNotNull();
    assertThat(storedSuspect.gapSeconds()).isEqualTo(20L);
    assertThat(storedSuspect.reason()).isNotBlank();
    assertThat(storedSuspect.severity()).isEqualTo(ReconciliationSeverity.HIGH);

    // 落库后可疑区间仍排得出来，且与内存里算的那份一致
    assertThat(
            access.run(
                actorA,
                "warehouse.inventory.read",
                () -> service.suspectWindows(result.run().id())))
        .hasSameSizeAs(result.suspectWindows());

    // 真分页：第 2 页取不到东西，合计仍是全量
    // 真分页：每页 1 条翻三页 —— 合计恒为全量 2，第 3 页取不到东西，
    // 且两页给的是不同的行（不是把同一行重复吐出来）。
    var page1 =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.findings(result.run().id(), "SUSPECT", 0, 1));
    var page2 =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.findings(result.run().id(), "SUSPECT", 1, 1));
    var page3 =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.findings(result.run().id(), "SUSPECT", 2, 1));
    assertThat(page1.total()).isEqualTo(2);
    assertThat(page1.findings()).hasSize(1);
    assertThat(page2.total()).isEqualTo(2);
    assertThat(page2.findings()).hasSize(1);
    assertThat(page3.findings()).isEmpty();
    assertThat(page1.findings().get(0).id()).isNotEqualTo(page2.findings().get(0).id());
  }

  @Test
  void 核对结论不可修改也不可删除() throws Exception {
    balance(whSh, "10", "0");
    var id = run(actorA, last30Days()).run().id();

    // 第一层：权限。warehouse_app 被 REVOKE 了 UPDATE/DELETE，所以应用路径先撞权限，
    // 根本走不到触发器 —— 断言根因里的 permission denied，而不是笼统的「抛了个异常」。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.read",
                    () ->
                        sql.execute(
                            "update inventory_reconciliation_run set verdict_note='改过了' where id=?",
                            id)))
        .rootCause()
        .hasMessageContaining("permission denied");
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> sql.execute("delete from inventory_reconciliation_run where id=?", id)))
        .rootCause()
        .hasMessageContaining("permission denied");

    // 第二层：触发器。权限只挡应用角色，用超级用户连上去照样能改 ——
    // 这一层证明"改不得"不是只靠一道 REVOKE 撑着。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.execute(
                      "update inventory_reconciliation_run set verdict_note='改过了' where id='"
                          + id
                          + "'"))
          .hasMessageContaining("append-only");
      assertThatThrownBy(
              () -> s.execute("delete from inventory_reconciliation_run where id='" + id + "'"))
          .hasMessageContaining("append-only");
    }
  }

  @Test
  void 按结论筛选批次() {
    var now = now();
    // 第一次核对要真的平（快照 10 = 累计 10），否则两次都是有差异，筛不出 CONSISTENT
    var bal1 = balance(whSh, "10", "0");
    txn("RECEIPT", "PO", now.minusDays(1), bal1, "10", "0");
    run(actorA, window(now.minusDays(30), now));
    var bal2 = balance(whSz, "999", "0");
    txn("RECEIPT", "PO", now.minusDays(1), bal2, "1", "0");
    run(actorA, window(now.minusDays(30), now));

    assertThat(
            access.run(actorA, "warehouse.inventory.read", () -> service.runs(0, 20, null)).total())
        .isEqualTo(2);
    assertThat(
            access
                .run(actorA, "warehouse.inventory.read", () -> service.runs(0, 20, "CONSISTENT"))
                .total())
        .isEqualTo(1);
    assertThat(
            access
                .run(actorA, "warehouse.inventory.read", () -> service.runs(0, 20, "DISCREPANT"))
                .runs()
                .get(0)
                .discrepancyCount())
        .isEqualTo(1);
    // 未知码 400：筛一个不存在的结论返回空列表，用户会以为"这段时间没有差异"
    assertThatThrownBy(
            () ->
                access.run(actorA, "warehouse.inventory.read", () -> service.runs(0, 20, "MAYBE")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未知的核对结论");
  }

  @Test
  void 跨租户查看核对记录返回404() {
    var id = run(actorA, last30Days()).run().id();
    // 403 会泄露"这次核对存在"，RLS 下查不到只能是 404
    assertThatThrownBy(
            () -> access.run(actorB, "warehouse.inventory.read", () -> service.runOf(id)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或无权查看");
  }

  // ================================================================ 时区

  @Test
  void 核对区间与流水时间按带偏移的ISO时间绑定_写回的值不丢偏移() {
    var bal = balance(whSh, "100", "0");
    // +08:00 的本地时间；用 Timestamp.valueOf 会静默偏 -28800s
    var shanghai = OffsetDateTime.parse("2026-10-01T09:30:00+08:00");
    var txId = txn("RECEIPT", "PO", shanghai, bal, "40", "0", shanghai);

    var result =
        run(
            actorA,
            new ReconciliationRequest(
                OffsetDateTime.parse("2026-10-01T00:00:00+08:00"),
                OffsetDateTime.parse("2026-10-02T00:00:00+08:00"),
                null,
                null,
                null));

    // 写回的值必须还是同一瞬间，不是被换算成本机时区
    var stored =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> sql.fetchOne("select occurred_at from inventory_transaction where id=?", txId));
    assertThat(stored).isNotNull();
    assertThat(stored.get(0, OffsetDateTime.class).toInstant()).isEqualTo(shanghai.toInstant());
    assertThat(stored.get(0, OffsetDateTime.class).getOffset().getTotalSeconds()).isEqualTo(28800);

    // 落库的核对区间也保持同一瞬间
    var header =
        access.run(actorA, "warehouse.inventory.read", () -> service.runOf(result.run().id()));
    assertThat(header.windowFrom().toInstant())
        .isEqualTo(OffsetDateTime.parse("2026-10-01T00:00:00+08:00").toInstant());
    assertThat(header.windowTo().toInstant())
        .isEqualTo(OffsetDateTime.parse("2026-10-02T00:00:00+08:00").toInstant());

    // 区间内确实有那笔分录（09:30 落在 [00:00, 次日 00:00) 内）
    assertThat(result.run().periodOnHand()).isEqualByComparingTo("40");
    assertThat(result.run().checkedEntryCount()).isEqualTo(1);
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    var now = now();
    var bal = balance(whSh, "100", "0");
    txn("RECEIPT", "PO", now.minusDays(1), bal, "40", "0");
    // B 的快照是 500，累计只有 7 → B 那边也有一处差异，但两边的差异各不相同
    var balB = tenantBFixture(now.minusDays(1), "500");
    var window = window(now.minusDays(30), now);

    var a = run(actorA, window);
    var b = run(actorB, window);

    assertThat(a.run().checkedBalanceCount()).isEqualTo(1);
    assertThat(a.run().snapshotOnHand()).isEqualByComparingTo("100");
    assertThat(a.discrepancies()).hasSize(1);
    assertThat(a.discrepancies().get(0).balanceId()).isEqualTo(bal);

    assertThat(b.run().checkedBalanceCount()).isEqualTo(1);
    assertThat(b.run().snapshotOnHand()).isEqualByComparingTo("500");
    assertThat(b.discrepancies()).hasSize(1);
    assertThat(b.discrepancies().get(0).balanceId()).isEqualTo(balB);

    // 批次列表也按租户隔离
    assertThat(
            access.run(actorA, "warehouse.inventory.read", () -> service.runs(0, 20, null)).total())
        .isEqualTo(1);
    assertThat(
            access.run(actorB, "warehouse.inventory.read", () -> service.runs(0, 20, null)).total())
        .isEqualTo(1);
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    var now = now();
    var bal = balance(whSh, "100", "0");
    txn("RECEIPT", "PO", now.minusDays(1), bal, "40", "0");
    var balB = tenantBFixture(now.minusDays(1), "500");

    // 身份是 B 但伪造 X-Tenant-Id 指向 A → 只能核到 B 自己那 500
    http.perform(
            post("/warehouse/inventory/reconciliation")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.run.checkedBalanceCount").value(1))
        .andExpect(jsonPath("$.run.status").value("DISCREPANT"))
        .andExpect(jsonPath("$.discrepancies[0].balanceId").value(balB.toString()));

    // 反向：身份是 A 伪造 B 也拿不到 B 的数据
    http.perform(
            post("/warehouse/inventory/reconciliation")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.discrepancies[0].balanceId").value(bal.toString()));

    // 批次列表同样不受伪造头影响：A 已核过两次（上面那次 HTTP + 这里这次），但看不到 B 那次
    var runA = run(actorA, window(now.minusDays(30), now));
    http.perform(
            get("/warehouse/inventory/reconciliation")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.runs.length()").value(2));
    // 反向：B 那边只看得见自己那一次，伪造 A 的头也一样
    http.perform(
            get("/warehouse/inventory/reconciliation")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.runs.length()").value(1))
        .andExpect(jsonPath("$.runs[0].id").value(not(runA.run().id().toString())));
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    var now = now();
    var bal = balance(whSh, "100", "0");
    txn("RECEIPT", "PO", now.minusDays(1), bal, "40", "0");
    tenantBFixture(now.minusDays(1), "500");
    var window = window(now.minusDays(30), now);

    // 池大小为 1，同一条连接被反复借还：交替跑两个租户 20 轮，
    // 每一轮都要同时校验写入与回查两条路径都不串租户。
    for (int i = 0; i < 20; i++) {
      var ra = run(actorA, window).run();
      var rb = run(actorB, window).run();
      assertThat(ra.snapshotOnHand()).isEqualByComparingTo("100");
      assertThat(rb.snapshotOnHand()).isEqualByComparingTo("500");
      assertThat(ra.id()).isNotEqualTo(rb.id());
      // 回查也在同一租户上下文里跑：B 拿不到 A 的核对记录
      assertThat(access.run(actorA, "warehouse.inventory.read", () -> service.runOf(ra.id())).id())
          .isEqualTo(ra.id());
      assertThatThrownBy(
              () -> access.run(actorB, "warehouse.inventory.read", () -> service.runOf(ra.id())))
          .isInstanceOf(ResponseStatusException.class);
      // 批次列表逐轮隔离：A 看得见的每一条都是 100，不含 B 的 500
      var listA = access.run(actorA, "warehouse.inventory.read", () -> service.runs(0, 50, null));
      assertThat(listA.runs())
          .isNotEmpty()
          .allMatch(r -> r.snapshotOnHand().compareTo(new BigDecimal("100")) == 0);
    }
  }
}
