package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryLedgerModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
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
 * 7.1.2.1 库存流水台账：append-only 语义、粒度口径、时间范围、业务/来源类型过滤、
 * 分录下钻、期间净变动汇总，以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>夹具写法与 {@link InventoryBucketIntegrationTest} 完全一致（同样的 Testcontainers、
 * 同样的 fake identity server、同样的按租户派生 UUID、同样的 TRUNCATE 清场），
 * 避免两个测试类各写一套风格。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InventoryLedgerIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static final UUID other = UUID.randomUUID();
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

  static final UUID leA = idFor(a, 1);
  static final UUID skuA = idFor(a, 2);
  static final UUID whSh = idFor(a, 3);
  static final UUID whSz = idFor(a, 4);
  static final UUID qSellable = idFor(a, 5);

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
      // 数据范围 = 仅本人：台账必须只列本人操作过的业务（蓝图把「用户数据范围」列为输入）
      actors.put(
          "Bearer self-only",
          actor(user, a, null, List.of(grant("SELF", List.of("warehouse.inventory.read")))));
      actors.put(
          "Bearer self-writer",
          actor(
              other,
              a,
              null,
              List.of(grant("SELF", List.of("warehouse.inventory.read", "warehouse.inventory.create")))));
      // 未认证
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

  @Autowired InventoryLedgerService service;
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
          "truncate inventory_entry, inventory_transaction, inventory_balance,"
              + " inventory_quality_status, warehouse, sku_ref, legal_entity_ref"
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
            "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
            idFor(tenant, 5), tenant, "SELLABLE", "可售", "SELLABLE");
        return null;
      });
    }
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  // ================================================================ 夹具写入

  /** 一条余额行，返回它的 id。 */
  UUID balance(UUID warehouse, String onHand) {
    var id = UUID.randomUUID();
    access.run(actorA, "warehouse.inventory.read", () -> {
      sql.execute(
          "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
              + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
          id, a, skuA, warehouse, leA, qSellable, new BigDecimal(onHand));
      return null;
    });
    return id;
  }

  /**
   * 写一笔库存业务（业务单 + 分录）。
   *
   * <p>occurredAt 用 {@code ?::timestamptz} 绑定：jOOQ 在纯文本语句里无法为 OffsetDateTime
   * 推断类型，不显式 cast 会被驱动按 varchar 处理、或静默偏 8 小时（AGENTS.md 与
   * {@code InventoryBucketService.recordBucket} 同一条纪律）。
   */
  UUID txn(
      String kind,
      String refType,
      OffsetDateTime occurredAt,
      UUID balanceId,
      String onHandDelta,
      String reservedDelta,
      UUID actor) {
    var txId = UUID.randomUUID();
    var refId = UUID.randomUUID();
    access.run(actorA, "warehouse.inventory.read", () -> {
      sql.execute(
          "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,actor_id,notes)"
              + " values(?,?,?,?,?,?::timestamptz,?,?)",
          txId,
          a,
          kind,
          refType,
          refId,
          occurredAt,
          actor == null ? user : actor,
          "夹具");
      sql.execute(
          "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,reserved_delta)"
              + " values(?,?,?,?,?,?)",
          UUID.randomUUID(), a, txId, balanceId, new BigDecimal(onHandDelta),
          new BigDecimal(reservedDelta));
      return null;
    });
    return txId;
  }

  /** 同一笔业务写多条分录（跨 SKU/跨仓的一次业务）。 */
  UUID multiEntryTxn(String kind, String refType, OffsetDateTime occurredAt, E... entries) {
    var txId = UUID.randomUUID();
    var refId = UUID.randomUUID();
    access.run(actorA, "warehouse.inventory.read", () -> {
      sql.execute(
          "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,actor_id,notes)"
              + " values(?,?,?,?,?,?::timestamptz,?,?)",
          txId, a, kind, refType, refId, occurredAt, user, "跨仓调拨");
      for (var e : entries) {
        sql.execute(
            "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,reserved_delta)"
                + " values(?,?,?,?,?,?)",
            UUID.randomUUID(), a, txId, e.balance, new BigDecimal(e.onHand), new BigDecimal(e.reserved));
      }
      return null;
    });
    return txId;
  }

  record E(UUID balance, String onHand, String reserved) {}

  InventoryLedgerPage pageOf(AccessService.Access who, InventoryLedgerQuery q) {
    return access.run(who, "warehouse.inventory.read", () -> service.page(q));
  }

  static InventoryLedgerQuery base() {
    return new InventoryLedgerQuery(
        null, null, null, null, null, null, null, null, null, null, user, false);
  }

  // ================================================================ 行与数量符号

  @Test
  void 一行一笔业务_入库为正出库为负_预占只动预占列() {
    var bal = balance(whSh, "100");
    var now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    txn("RECEIPT", "PURCHASE_ORDER", now.minusDays(3), bal, "40", "0", null);
    txn("SHIPMENT", "ORDER", now.minusDays(2), bal, "-15", "0", null);
    txn("RESERVE", "ORDER", now.minusDays(1), bal, "0", "8", null);
    txn("RELEASE", "ORDER", now, bal, "0", "-8", null);

    var rows = pageOf(actorA, base()).rows();
    assertThat(rows).hasSize(4);
    // 按发生时间倒序
    assertThat(rows).extracting(InventoryLedgerRow::kind)
        .containsExactly(
            LedgerKind.RELEASE, LedgerKind.RESERVE, LedgerKind.SHIPMENT, LedgerKind.RECEIPT);
    // 数量列断言的是 numeric(19,4) 的原样字符串：BigDecimal.equals 会把 0 与 0.0000 判成不等，
    // 但页面上的口径就是 4 位小数，写清楚才能发现有人把精度改成 (19,6)。
    assertThat(rows).extracting(r -> r.onHandDelta().toPlainString())
        .containsExactly("0.0000", "0.0000", "-15.0000", "40.0000");
    assertThat(rows).extracting(r -> r.reservedDelta().toPlainString())
        .containsExactly("-8.0000", "8.0000", "0.0000", "0.0000");
    // 来源单据与操作人都在行上：这是"每笔含来源单据、数量符号、操作人、发生时间"的要求
    assertThat(rows).allMatch(r -> r.refType() != null && r.refId() != null);
    assertThat(rows).allMatch(r -> r.actorId().equals(user));
  }

  @Test
  void 期间净变动汇总等于全部分录之和_且不受分页影响() {
    var bal = balance(whSh, "100");
    var now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    txn("RECEIPT", "PURCHASE_ORDER", now.minusDays(3), bal, "40", "0", null);
    txn("SHIPMENT", "ORDER", now.minusDays(2), bal, "-15", "0", null);
    txn("RESERVE", "ORDER", now.minusDays(1), bal, "0", "8", null);

    var full = pageOf(actorA, base());
    assertThat(full.summary().transactionCount()).isEqualTo(3);
    assertThat(full.summary().entryCount()).isEqualTo(3);
    assertThat(full.summary().transactionOnHandDelta()).isEqualByComparingTo("25");
    assertThat(full.summary().transactionReservedDelta()).isEqualByComparingTo("8");
    // 行口径与分录口径在没有维度筛选时必须相等 —— 这就是 7.1.2.2 核对的基线
    assertThat(full.summary().entryOnHandDelta()).isEqualByComparingTo("25");
    assertThat(full.summary().onHandConserved()).isTrue();
    assertThat(full.summary().reservedConserved()).isTrue();

    var paged =
        pageOf(
            actorA,
            new InventoryLedgerQuery(
                null, null, null, null, null, null, null, null, 0, 1, user, false));
    assertThat(paged.rows()).hasSize(1);
    // 合计与笔数不受分页影响：翻页时数字变小会被当成丢数据
    assertThat(paged.summary().transactionCount()).isEqualTo(3);
    assertThat(paged.summary().transactionOnHandDelta()).isEqualByComparingTo("25");
    assertThat(paged.total()).isEqualTo(3);
  }

  @Test
  void 按SKU筛选时行仍是整笔的净变动_差额另有出处() {
    // 一笔跨两仓的调拨：出库 −50、入库 +50，行上必须是净 0，而不是只算命中仓的那一半
    var sh = balance(whSh, "100");
    var sz = balance(whSz, "0");
    txn("TRANSFER_OUT", "TRANSFER_ORDER", OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS), sh, "-50", "0", null);
    txn("TRANSFER_IN", "TRANSFER_ORDER", OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS), sz, "50", "0", null);

    var byWarehouse = pageOf(actorA, withWarehouse(whSh));
    assertThat(byWarehouse.rows()).hasSize(1);
    assertThat(byWarehouse.rows().get(0).onHandDelta()).isEqualByComparingTo("-50");
    // 行口径（整笔 −50）与分录口径（该仓那一条 −50）在单仓筛选下恰好相等
    assertThat(byWarehouse.summary().transactionOnHandDelta()).isEqualByComparingTo("-50");
    assertThat(byWarehouse.summary().entryOnHandDelta()).isEqualByComparingTo("-50");

    // 无筛选时两个口径依然相等（这是不变量，不是有筛选时）
    var all = pageOf(actorA, base());
    assertThat(all.summary().transactionOnHandDelta()).isEqualByComparingTo("0");
    assertThat(all.summary().entryOnHandDelta()).isEqualByComparingTo("0");
    assertThat(all.summary().onHandConserved()).isTrue();
  }

  @Test
  void 一笔跨两笔分录的业务在行上标出跨了几处() {
    var sh = balance(whSh, "100");
    var sz = balance(whSz, "0");
    var id =
        multiEntryTxn(
            "TRANSFER_OUT",
            "TRANSFER_ORDER",
            OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS),
            new E(sh, "-30", "0"),
            new E(sz, "10", "0"));
    var page = pageOf(actorA, base());
    assertThat(page.rows()).hasSize(1);
    var row = page.rows().get(0);
    assertThat(row.transactionId()).isEqualTo(id);
    assertThat(row.entryCount()).isEqualTo(2);
    assertThat(row.warehouseCount()).isEqualTo(2);
    assertThat(row.onHandDelta()).isEqualByComparingTo("-20");
    assertThat(row.skuCount()).isEqualTo(1);

    // 按其中一仓筛选，整笔仍命中（它碰过这一仓），下钻能看全两条分录
    var filtered = pageOf(actorA, withWarehouse(whSz));
    assertThat(filtered.rows()).hasSize(1);
    assertThat(access.run(actorA, "warehouse.inventory.read", () -> service.drill(id)).entries())
        .hasSize(2);
  }

  // ================================================================ 分录下钻

  @Test
  void 分录下钻看得到每条分录的定位维度与当前余额() {
    var bal = balance(whSh, "100");
    var id = txn("RECEIPT", "PURCHASE_ORDER", OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS), bal, "40", "0", null);
    // 余额行当前在手改成 140：下钻显示的"当前在手"应随之变化，且它明确不是历史快照
    access.run(actorA, "warehouse.inventory.read", () -> {
      sql.execute("update inventory_balance set on_hand=140 where id=?", bal);
      return null;
    });

    var drill = access.run(actorA, "warehouse.inventory.read", () -> service.drill(id));
    assertThat(drill.transaction().transactionId()).isEqualTo(id);
    assertThat(drill.entries()).hasSize(1);
    var e = drill.entries().get(0);
    assertThat(e.balanceId()).isEqualTo(bal);
    assertThat(e.onHandDelta()).isEqualByComparingTo("40");
    // SKU × 仓库 × 货权法人 × 质量状态：分录挂在 balance 行上，定位维度一个都不能少
    assertThat(e.skuId()).isEqualTo(skuA);
    assertThat(e.skuCode()).isEqualTo("SKU-1");
    assertThat(e.warehouseId()).isEqualTo(whSh);
    assertThat(e.warehouseCode()).isEqualTo("WH-SH");
    assertThat(e.legalEntityId()).isEqualTo(leA);
    assertThat(e.qualityStatusId()).isEqualTo(qSellable);
    assertThat(e.qualityStatusName()).isEqualTo("可售");
    assertThat(e.balanceOnHandNow()).isEqualByComparingTo("140");
    assertThat(e.balanceAvailableNow()).isEqualByComparingTo("140");
  }

  @Test
  void 跨租户下钻返回404而不是泄露存在性() throws Exception {
    var bal = balance(whSh, "100");
    var id = txn("RECEIPT", "PURCHASE_ORDER", OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS), bal, "40", "0", null);
    // 租户 B 直接按 A 的业务单 id 下钻：RLS 下查不到 → 404（403 会泄露"这笔存在"）
    assertThatThrownBy(() -> access.run(actorB, "warehouse.inventory.read", () -> service.drill(id)))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("不存在");
    http.perform(get("/warehouse/inventory/ledger/{id}", id).header("Authorization", "Bearer tenant-b"))
        .andExpect(status().isNotFound());
  }

  // ================================================================ append-only

  @Test
  void 流水与分录都不允许UPDATE或DELETE_连运行角色都不行() {
    var bal = balance(whSh, "100");
    var id = txn("RECEIPT", "PURCHASE_ORDER", OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS), bal, "40", "0", null);
    // 台账只读：试图改一笔历史流水必须被 DB 拒绝（V1 的 append-only 触发器 + REVOKE 双保险）
    assertThatThrownBy(
            () ->
                access.run(actorA, "warehouse.inventory.read", () -> {
                  sql.execute("update inventory_transaction set notes='改过' where id=?", id);
                  return null;
                }))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () ->
                access.run(actorA, "warehouse.inventory.read", () -> {
                  sql.execute("delete from inventory_transaction where id=?", id);
                  return null;
                }))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    // 原记录永久保留
    var page = pageOf(actorA, base());
    assertThat(page.rows()).hasSize(1);
    assertThat(page.rows().get(0).notes()).isEqualTo("夹具");
  }

  @Test
  void 没有分录的业务单不能被藏起来_单独报数() {
    var txId = UUID.randomUUID();
    access.run(actorA, "warehouse.inventory.read", () -> {
      sql.execute(
          "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,actor_id,notes)"
              + " values(?,?,?,?,?,?::timestamptz,?,?)",
          txId, a, "ADJUSTMENT", "STOCKTAKE", UUID.randomUUID(),
          OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS), user, "写入中断");
      return null;
    });
    var page = pageOf(actorA, base());
    assertThat(page.rows()).hasSize(1);
    assertThat(page.rows().get(0).entryCount()).isZero();
    assertThat(page.rows().get(0).onHandDelta()).isEqualByComparingTo("0");
    // 空分录的业务单要能被看见：它是写入方中途失败的痕迹，静默吞掉账就平得不明不白
    assertThat(page.summary().emptyTransactionCount()).isEqualTo(1);
    assertThat(page.summary().transactionCount()).isEqualTo(1);
  }

  // ================================================================ 过滤

  @Test
  void 业务类型与来源单据类型过滤都真实生效() {
    var bal = balance(whSh, "100");
    var now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    txn("RECEIPT", "PURCHASE_ORDER", now.minusDays(3), bal, "40", "0", null);
    txn("SHIPMENT", "ORDER", now.minusDays(2), bal, "-15", "0", null);
    txn("RESERVE", "ORDER", now.minusDays(1), bal, "0", "8", null);

    var receipts = pageOf(actorA, withKinds("RECEIPT"));
    assertThat(receipts.rows()).hasSize(1);
    assertThat(receipts.rows().get(0).kind()).isEqualTo(LedgerKind.RECEIPT);
    assertThat(receipts.summary().transactionOnHandDelta()).isEqualByComparingTo("40");

    // 多选
    var two = pageOf(actorA, withKinds(List.of("RECEIPT", "SHIPMENT")));
    assertThat(two.rows()).hasSize(2);

    var orders = pageOf(actorA, withRefTypes("ORDER"));
    assertThat(orders.rows()).hasSize(2);
    assertThat(orders.summary().transactionCount()).isEqualTo(2);

    // 来源单据类型下拉是租户级词表，带笔数
    var refs = access.run(actorA, "warehouse.inventory.read", () -> service.refTypes());
    assertThat(refs).extracting(InventoryRefType::refType)
        .containsExactly("ORDER", "PURCHASE_ORDER");
    assertThat(refs).extracting(InventoryRefType::transactionCount).containsExactly(2L, 1L);

    // 分组统计只报期间真实发生过的类型
    assertThat(pageOf(actorA, base()).breakdown()).extracting(InventoryLedgerBreakdown::kind)
        .containsExactly(LedgerKind.RECEIPT, LedgerKind.RESERVE, LedgerKind.SHIPMENT);
  }

  @Test
  void 非法业务类型与非法排序键都400_而不是静默返回空() {
    assertThatThrownBy(() -> pageOf(actorA, withKinds("NOT_A_KIND")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未知的库存业务类型");
    assertThatThrownBy(() -> pageOf(actorA, withSort("ON_HAND")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的排序键");
  }

  @Test
  void 关键字能命中SKU与仓库编码() {
    var bal = balance(whSh, "100");
    txn("RECEIPT", "PURCHASE_ORDER", OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS), bal, "40", "0", null);
    assertThat(pageOf(actorA, withKeyword("SKU-1")).rows()).hasSize(1);
    assertThat(pageOf(actorA, withKeyword("WH-SH")).rows()).hasSize(1);
    assertThat(pageOf(actorA, withKeyword("深圳")).rows()).hasSize(0);
    assertThat(pageOf(actorA, withKeyword("夹具")).rows()).hasSize(1);
  }

  @Test
  void 排序与翻页在同一毫秒下不重不漏() {
    var bal = balance(whSh, "100");
    // 三笔同一时刻：决胜键必须追加 id，否则翻页会漏行或重复
    var at = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    txn("RECEIPT", "PURCHASE_ORDER", at, bal, "10", "0", null);
    txn("SHIPMENT", "ORDER", at, bal, "-3", "0", null);
    txn("ADJUSTMENT", "STOCKTAKE", at, bal, "1", "0", null);

    var p0 = page(0, 2);
    var p1 = page(1, 2);
    assertThat(p0.rows()).hasSize(2);
    assertThat(p1.rows()).hasSize(1);
    var ids = new java.util.ArrayList<UUID>();
    p0.rows().forEach(r -> ids.add(r.transactionId()));
    p1.rows().forEach(r -> ids.add(r.transactionId()));
    assertThat(ids).doesNotHaveDuplicates().hasSize(3);
    assertThat(p0.total()).isEqualTo(3);

    // 按聚合数量排序
    var byQty = pageOf(actorA, withSort("ON_HAND_DELTA"));
    assertThat(byQty.rows().get(0).onHandDelta()).isEqualByComparingTo("10");
  }

  InventoryLedgerPage page(int pageNumber, int size) {
    return access.run(
        actorA,
        "warehouse.inventory.read",
        () ->
            service.page(
                new InventoryLedgerQuery(
                    null, null, null, null, null, null, null, null, pageNumber, size, user, false)));
  }

  // ================================================================ 时间范围与时区

  @Test
  void 时间范围左闭右开且时区零漂移() {
    var bal = balance(whSh, "100");
    // 刻意用 +08:00 偏移写入：写回值必须带同一个偏移被读出来，不能偏 -28800s
    var t0 = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");
    var t1 = OffsetDateTime.parse("2026-03-01T12:00:00+08:00");
    var t2 = OffsetDateTime.parse("2026-03-01T18:00:00+08:00");
    txn("RECEIPT", "PURCHASE_ORDER", t0, bal, "10", "0", null);
    txn("SHIPMENT", "ORDER", t1, bal, "-5", "0", null);
    txn("RECEIPT", "PURCHASE_ORDER", t2, bal, "3", "0", null);

    // 往返：业务时间原样读回（显式 ?::timestamptz，不发生静默偏移）。
    // 断言比的是**绝对时刻**而不是墙钟偏移：timestamptz 读回时按会话/JVM 时区呈现，
    // 同一瞬间在 UTC 与 +08:00 下是两个不同的 OffsetDateTime，比较瞬间才是"有没有偏 8 小时"这件事。
    var all = pageOf(actorA, base());
    assertThat(all.rows()).extracting(r -> r.occurredAt().toInstant())
        .containsExactly(t2.toInstant(), t1.toInstant(), t0.toInstant());
    assertThat(all.summary().firstOccurredAt().toInstant()).isEqualTo(t0.toInstant());
    assertThat(all.summary().lastOccurredAt().toInstant()).isEqualTo(t2.toInstant());

    // 左闭右开：[t0, t2) 命中 t0 与 t1，恰好不含 t2
    var window = pageOf(actorA, withRange(t0, t2));
    assertThat(window.rows()).hasSize(2);
    assertThat(window.summary().transactionOnHandDelta()).isEqualByComparingTo("5");
    // 下界不含、上界含：左闭右开
    assertThat(pageOf(actorA, withRange(t1, t2)).rows()).hasSize(1);
    assertThat(pageOf(actorA, withRange(t0, t1)).rows()).hasSize(1);
    // 空区间（from == to）合法，返回空而不是报错
    assertThat(pageOf(actorA, withRange(t0, t0)).rows()).isEmpty();
  }

  @Test
  void 开始时间晚于结束时间直接400() {
    assertThatThrownBy(
            () ->
                pageOf(
                    actorA,
                    withRange(
                        OffsetDateTime.parse("2026-03-02T00:00:00+08:00"),
                        OffsetDateTime.parse("2026-03-01T00:00:00+08:00"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("开始时间不能晚于结束时间");
  }

  @Test
  void 跨时区查询按绝对时刻比较而不是按墙钟() {
    var bal = balance(whSh, "100");
    // 同一绝对时刻的两种偏移写法，必须都命中
    var sh = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");
    var utc = OffsetDateTime.parse("2026-03-01T00:00:00Z");
    txn("RECEIPT", "PURCHASE_ORDER", sh, bal, "10", "0", null);
    assertThat(pageOf(actorA, withRange(utc, utc.plusHours(1))).rows()).hasSize(1);
    assertThat(pageOf(actorA, withRange(sh, sh.plusHours(1))).rows()).hasSize(1);
    assertThat(pageOf(actorA, withRange(utc, utc.plusHours(1))).rows().get(0).occurredAt().toInstant())
        .isEqualTo(sh.toInstant());
  }

  // ================================================================ 数据范围

  @Test
  void 数据范围为仅本人时只列本人操作过的业务() {
    var bal = balance(whSh, "100");
    var now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    txn("RECEIPT", "PURCHASE_ORDER", now.minusDays(1), bal, "10", "0", null); // user
    txn("SHIPMENT", "ORDER", now, bal, "-3", "0", other); // 别人

    var self = access.run(
        actors.get("Bearer self-writer"),
        "warehouse.inventory.read",
        () -> service.page(new InventoryLedgerQuery(
            null, null, null, null, null, null, null, null, null, null, other, true)));
    assertThat(self.rows()).hasSize(1);
    assertThat(self.rows().get(0).actorId()).isEqualTo(other);
    assertThat(self.dataScope()).isEqualTo("SELF");
    assertThat(self.dataScopeNote()).contains("仅本人");

    // 全范围（tenant-a 的 ALL 授权）两笔都看得到
    assertThat(pageOf(actorA, base()).rows()).hasSize(2);
    assertThat(pageOf(actorA, base()).dataScope()).isEqualTo("ALL");
  }

  // ================================================================ HTTP 与权限

  @Test
  void 端点走HTTP时筛选与汇总都成立() throws Exception {
    var bal = balance(whSh, "100");
    var t = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");
    txn("RECEIPT", "PURCHASE_ORDER", t, bal, "40", "0", null);
    txn("SHIPMENT", "ORDER", t.plusHours(4), bal, "-15", "0", null);

    http.perform(get("/warehouse/inventory/ledger")
            .header("Authorization", "Bearer tenant-a")
            .param("from", "2026-03-01T00:00:00+08:00")
            .param("to", "2026-03-02T00:00:00+08:00")
            .param("kinds", "RECEIPT", "SHIPMENT")
            .param("warehouseId", whSh.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(2))
        .andExpect(jsonPath("$.total").value(2))
        .andExpect(jsonPath("$.summary.transactionOnHandDelta").value(25))
        .andExpect(jsonPath("$.summary.entryOnHandDelta").value(25))
        .andExpect(jsonPath("$.summary.onHandConserved").value(true))
        .andExpect(jsonPath("$.refTypes.length()").value(2))
        .andExpect(jsonPath("$.dataScope").value("ALL"));

    // 时间格式错（不带偏移）→ 400，不按服务器本地时区猜
    http.perform(get("/warehouse/inventory/ledger")
            .header("Authorization", "Bearer tenant-a")
            .param("from", "2026-03-01T00:00:00"))
        .andExpect(status().isBadRequest());
    // 非法业务类型 → 400
    http.perform(get("/warehouse/inventory/ledger")
            .header("Authorization", "Bearer tenant-a")
            .param("kinds", "NOPE"))
        .andExpect(status().isBadRequest());

    http.perform(get("/warehouse/inventory/ledger/ref-types")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
  }

  @Test
  void 未认证401_空租户返回空页而不是报错() throws Exception {
    http.perform(get("/warehouse/inventory/ledger")).andExpect(status().isUnauthorized());
    http.perform(get("/warehouse/inventory/ledger/{id}", UUID.randomUUID())
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
    // 空态：B 有参照数据但没有任何流水 → 空页 + 零汇总，不是 500 也不是报错
    var empty = pageOf(actorB, base());
    assertThat(empty.rows()).isEmpty();
    assertThat(empty.total()).isZero();
    assertThat(empty.summary().transactionCount()).isZero();
    assertThat(empty.summary().transactionOnHandDelta()).isEqualByComparingTo("0");
    assertThat(empty.breakdown()).isEmpty();
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    var bal = balance(whSh, "100");
    var at = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    txn("RECEIPT", "PURCHASE_ORDER", at, bal, "40", "0", null);
    // 租户 B 自己的参照数据存在，但只有它自己的流水对 B 可见
    var balB = UUID.randomUUID();
    access.run(actorB, "warehouse.inventory.read", () -> {
      sql.execute(
          "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
              + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
          balB, b, idFor(b, 2), idFor(b, 3), idFor(b, 1), idFor(b, 5), new BigDecimal("7"));
      sql.execute(
          "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,actor_id,notes)"
              + " values(?,?,?,?,?,?::timestamptz,?,?)",
          UUID.randomUUID(), b, "RECEIPT", "PURCHASE_ORDER", UUID.randomUUID(),
          at, user, "B 的入库");
      return null;
    });

    var pageA = pageOf(actorA, base());
    assertThat(pageA.rows()).hasSize(1);
    assertThat(pageA.summary().transactionOnHandDelta()).isEqualByComparingTo("40");

    var pageB = pageOf(actorB, base());
    assertThat(pageB.rows()).hasSize(1);
    assertThat(pageB.rows().get(0).notes()).isEqualTo("B 的入库");
    assertThat(pageB.summary().transactionOnHandDelta()).isEqualByComparingTo("0");

    // 来源单据类型下拉同样按租户隔离：A 只看得到自己开过的类型
    assertThat(access.run(actorA, "warehouse.inventory.read", () -> service.refTypes()))
        .extracting(InventoryRefType::refType)
        .containsExactly("PURCHASE_ORDER");
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    var bal = balance(whSh, "100");
    var at = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    txn("RECEIPT", "PURCHASE_ORDER", at, bal, "40", "0", null);
    var balB = UUID.randomUUID();
    access.run(actorB, "warehouse.inventory.read", () -> {
      sql.execute(
          "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
              + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
          balB, b, idFor(b, 2), idFor(b, 3), idFor(b, 1), idFor(b, 5), new BigDecimal("7"));
      sql.execute(
          "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,actor_id,notes)"
              + " values(?,?,?,?,?,?::timestamptz,?,?)",
          UUID.randomUUID(), b, "RECEIPT", "PURCHASE_ORDER", UUID.randomUUID(),
          at, user, "B 的入库");
      return null;
    });

    // 身份是 B 但伪造 X-Tenant-Id 指向 A → 只能看到 B 自己的那一笔
    http.perform(get("/warehouse/inventory/ledger")
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0].notes").value("B 的入库"))
        .andExpect(jsonPath("$.total").value(1));
    // 反向：身份是 A 伪造 B 也拿不到 B 的数据
    http.perform(get("/warehouse/inventory/ledger")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1))
        .andExpect(jsonPath("$.summary.transactionOnHandDelta").value(40));
    // 来源单据类型下拉同样不受伪造头影响
    http.perform(get("/warehouse/inventory/ledger/ref-types")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    var bal = balance(whSh, "100");
    var at = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    txn("RECEIPT", "PURCHASE_ORDER", at, bal, "40", "0", null);

    // 池大小为 1，同一条连接被反复借还：交替访问两个租户 20 轮，
    // 每一轮都要同时校验台账列表、汇总与下钻三条读路径都不串租户。
    for (int i = 0; i < 20; i++) {
      var fa = pageOf(actorA, base());
      var fb = pageOf(actorB, base());
      assertThat(fa.rows()).hasSize(1);
      assertThat(fb.rows()).isEmpty();
      assertThat(fa.summary().transactionOnHandDelta()).isEqualByComparingTo("40");
      assertThat(fb.summary().transactionOnHandDelta()).isEqualByComparingTo("0");
      // 下钻也在同一租户上下文里跑：B 拿不到 A 的业务单
      var id = fa.rows().get(0).transactionId();
      assertThat(access.run(actorA, "warehouse.inventory.read", () -> service.drill(id)).entries())
          .hasSize(1);
      assertThatThrownBy(() -> access.run(actorB, "warehouse.inventory.read", () -> service.drill(id)))
          .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
      // 来源单据类型词表也逐轮隔离
      assertThat(access.run(actorA, "warehouse.inventory.read", () -> service.refTypes())).hasSize(1);
      assertThat(access.run(actorB, "warehouse.inventory.read", () -> service.refTypes())).isEmpty();
    }
  }

  // ---------------------------------------------------------------- 查询构造

  static InventoryLedgerQuery withKinds(String... kinds) {
    return withKinds(List.of(kinds));
  }

  static InventoryLedgerQuery withKinds(List<String> kinds) {
    var b = base();
    return new InventoryLedgerQuery(
        b.from(), b.to(), null, null, kinds, null, null, null, null, null, b.actorId(), false);
  }

  static InventoryLedgerQuery withRefTypes(String... refs) {
    var b = base();
    return new InventoryLedgerQuery(
        b.from(), b.to(), null, null, null, List.of(refs), null, null, null, null, b.actorId(), false);
  }

  static InventoryLedgerQuery withWarehouse(UUID warehouseId) {
    var b = base();
    return new InventoryLedgerQuery(
        b.from(), b.to(), null, warehouseId, null, null, null, null, null, null, b.actorId(), false);
  }

  static InventoryLedgerQuery withSku(UUID skuId) {
    var b = base();
    return new InventoryLedgerQuery(
        b.from(), b.to(), skuId, null, null, null, null, null, null, null, b.actorId(), false);
  }

  static InventoryLedgerQuery withKeyword(String keyword) {
    var b = base();
    return new InventoryLedgerQuery(
        b.from(), b.to(), null, null, null, null, keyword, null, null, null, b.actorId(), false);
  }

  static InventoryLedgerQuery withSort(String sort) {
    var b = base();
    return new InventoryLedgerQuery(
        b.from(), b.to(), null, null, null, null, null, sort, null, null, b.actorId(), false);
  }

  static InventoryLedgerQuery withRange(OffsetDateTime from, OffsetDateTime to) {
    var b = base();
    return new InventoryLedgerQuery(
        from, to, null, null, null, null, null, null, null, null, b.actorId(), false);
  }
}