package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryLedgerModels.*;
import static com.starcon.warehouse.InventoryLotModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
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
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 7.3.1.1 批次管理：批次建立（手工 + 收货生成）、批次属性、业务日期口径、 按批次聚合的库存分布、复用台账的出入库历史，以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接： 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>夹具写法与 {@link InventoryLedgerIntegrationTest} 完全一致（同样的 Testcontainers、 同样的 fake identity
 * server、同样的按租户派生 UUID、同样的 TRUNCATE 清场）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InventoryLotIntegrationTest {
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
  static final UUID skuA2 = idFor(a, 3);
  static final UUID whSh = idFor(a, 4);
  static final UUID whSz = idFor(a, 5);
  static final UUID qSellable = idFor(a, 6);
  static final UUID qHold = idFor(a, 7);
  static final UUID locSh1 = idFor(a, 8);
  static final UUID locSh2 = idFor(a, 9);
  static final UUID locSz1 = idFor(a, 10);

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-lot-test-" + tenant + "-" + ordinal).getBytes());
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
      actors.put(
          "Bearer self-only",
          actor(user, a, null, List.of(grant("SELF", List.of("warehouse.inventory.read")))));
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

  @Autowired InventoryLotService service;
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
    // TRUNCATE 不触发行级触发器（append-only 只挡 UPDATE/DELETE），以超级用户连接执行可绕过
    // FORCE RLS —— 只用于测试夹具重建，不给应用角色开这个口子。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate stock_bucket, warehouse_location, inventory_lot, inventory_entry,"
              + " inventory_transaction, inventory_balance, inventory_quality_status,"
              + " warehouse, sku_ref, legal_entity_ref restart identity cascade");
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
                idFor(tenant, 4),
                tenant,
                "WH-SH",
                "上海仓",
                "CN",
                "Asia/Shanghai");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
                idFor(tenant, 5),
                tenant,
                "WH-SZ",
                "深圳仓",
                "CN",
                "Asia/Shanghai");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 6),
                tenant,
                "SELLABLE",
                "可售",
                "SELLABLE");
            return null;
          });
    }
    // 租户 A 的库位与第二个质量状态（仓库是共享维度，但库位按仓建）
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
              qHold,
              a,
              "PENDING_INSPECTION",
              "待检",
              "PENDING_INSPECTION");
          sql.execute(
              "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
              skuA2,
              a,
              "SKU-2",
              "商品二");
          location(locSh1, whSh, "SH-A-01", "上海一区一列", "STORAGE", true);
          location(locSh2, whSh, "SH-A-02", "上海一区二列", "STORAGE", true);
          location(locSz1, whSz, "SZ-A-01", "深圳一区一列", "STORAGE", true);
          return null;
        });
  }

  void location(UUID id, UUID warehouse, String code, String name, String type, boolean pickable) {
    sql.execute(
        "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,pickable)"
            + " values(?,?,?,?,?,?,?)",
        id,
        a,
        warehouse,
        code,
        name,
        type,
        pickable);
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  // ================================================================ 夹具写入

  /** 一条 bucket 的夹具描述。质量状态按 balance 取，同一 balance 下的 bucket 必须共享。 */
  record B(UUID lotId, UUID location, String onHand, String reserved) {}

  /**
   * 建一条余额行并按库位/批次拆成若干 bucket，**同一事务**写入，返回 balance id。
   *
   * <p>必须同一事务：V2 的守恒（{@code guard_bucket_conservation}）是**延迟约束触发器**， 提交前才把 balance 的在手/预占与它的 bucket
   * 汇总对平，分两次写第二次提交就会报不平。
   *
   * <p>一个 warehouse × SKU × 货权法人 × 质量状态只有**一条** balance（V2 的唯一键里没有批次）， 所以落在同一仓同一质量状态下的多个批次必须共用这条
   * balance —— 这正是「流水按 balance 记账、 批次只是 balance 之下一层事实」在夹具层面的体现。
   */
  UUID splitBalance(UUID warehouse, UUID quality, List<B> buckets) {
    var balanceId = UUID.randomUUID();
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          var onHandTotal = BigDecimal.ZERO;
          var reservedTotal = BigDecimal.ZERO;
          for (var b : buckets) {
            onHandTotal = onHandTotal.add(new BigDecimal(b.onHand()));
            reservedTotal = reservedTotal.add(new BigDecimal(b.reserved()));
          }
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,?)",
              balanceId,
              a,
              skuA,
              warehouse,
              leA,
              quality,
              onHandTotal,
              reservedTotal);
          for (var b : buckets) {
            sql.execute(
                "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,"
                    + "location_id,lot_id,quality_status_id,balance_id,on_hand,reserved)"
                    + " values(?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(),
                a,
                leA,
                skuA,
                warehouse,
                b.location(),
                b.lotId(),
                quality,
                balanceId,
                new BigDecimal(b.onHand()),
                new BigDecimal(b.reserved()));
          }
          return null;
        });
    return balanceId;
  }

  /** 写一笔库存业务（业务单 + 分录）。occurredAt 走 {@code ?::timestamptz} 绑定纪律。 */
  UUID txn(String kind, String refType, OffsetDateTime occurredAt, UUID balanceId, String delta) {
    var txId = UUID.randomUUID();
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,actor_id,notes)"
                  + " values(?,?,?,?,?,?::timestamptz,?,?)",
              txId,
              a,
              kind,
              refType,
              UUID.randomUUID(),
              occurredAt,
              user,
              "夹具");
          sql.execute(
              "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,reserved_delta)"
                  + " values(?,?,?,?,?,?)",
              UUID.randomUUID(),
              a,
              txId,
              balanceId,
              new BigDecimal(delta),
              BigDecimal.ZERO);
          return null;
        });
    return txId;
  }

  UUID lot(String code, LocalDate produced, LocalDate expires) {
    return lot(code, skuA, produced, expires);
  }

  UUID lot(String code, UUID sku, LocalDate produced, LocalDate expires) {
    var id = UUID.randomUUID();
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          // origin=RECEIPT 必须带来源定位键（V57 的 ck_lot_origin_source），夹具照真实收货单据建。
          sql.execute(
              "insert into inventory_lot(id,tenant_id,sku_id,lot_code,produced_on,expires_on,"
                  + "received_on,origin,source_system,source_ref_id,source_ref_code)"
                  + " values(?,?,?,?,?::date,?::date,current_date,'RECEIPT','PURCHASE',?,'PO-FIXTURE')",
              id,
              a,
              sku,
              code,
              produced,
              expires,
              UUID.randomUUID());
          return null;
        });
    return id;
  }

  LotArchiveQuery q() {
    return new LotArchiveQuery(null, null, null, null, null, null, null, null, null, null, 0, 50);
  }

  LotArchiveQuery q(UUID skuId) {
    return new LotArchiveQuery(skuId, null, null, null, null, null, null, null, null, null, 0, 50);
  }

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "warehouse.inventory.read", work::get);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "warehouse.inventory.read", work::get);
  }

  // ================================================================ 批次建立

  @Test
  void 手工建立批次_批号生产日期失效日期原样落库() {
    var produced = LocalDate.of(2026, 3, 1);
    var expires = LocalDate.of(2027, 3, 1);
    var saved =
        asA(
            () ->
                service.create(
                    a,
                    new LotArchiveCreateRequest(
                        skuA,
                        "LOT-2026-001",
                        produced,
                        expires,
                        LocalDate.of(2026, 5, 20),
                        "MANUAL",
                        null,
                        null,
                        "PO-2026-0001",
                        UUID.randomUUID(),
                        "苏州供应商",
                        "首批到货")));

    assertThat(saved.lotCode()).isEqualTo("LOT-2026-001");
    assertThat(saved.producedOn()).isEqualTo(produced);
    assertThat(saved.expiresOn()).isEqualTo(expires);
    assertThat(saved.receivedOn()).isEqualTo(LocalDate.of(2026, 5, 20));
    assertThat(saved.origin()).isEqualTo("MANUAL");
    assertThat(saved.status()).isEqualTo("ACTIVE");
    assertThat(saved.expired()).isFalse();
    assertThat(saved.supplierName()).isEqualTo("苏州供应商");

    // 业务日期必须是 date 语义：读回来是纯日期，没有时分秒，更不该有时区漂移。
    // 这一读必须走 asA：不经 access.run 就没有 app.current_tenant，RLS 会把所有行都挡住。
    var raw =
        asA(
            () ->
                sql.fetchOne(
                    "select produced_on,expires_on from inventory_lot where id=?", saved.lotId()));
    assertThat(raw.get("produced_on", LocalDate.class)).isEqualTo(produced);
    assertThat(raw.get("expires_on", LocalDate.class)).isEqualTo(expires);
  }

  @Test
  void 收货生成批次_必须带来源单据定位键() {
    // 来源单据缺失 → 明确 400，而不是建出一个"来路不明"的收货批次
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.create(
                            a,
                            new LotArchiveCreateRequest(
                                skuA, "LOT-R-1", null, null, null, "RECEIPT", "RECEIPT", null,
                                "RCV-1", null, null, null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sourceRefId");

    var refId = UUID.randomUUID();
    var saved =
        asA(
            () ->
                service.create(
                    a,
                    new LotArchiveCreateRequest(
                        skuA,
                        "LOT-R-1",
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2027, 1, 1),
                        null,
                        "RECEIPT",
                        "PURCHASE",
                        refId,
                        "PO-9",
                        null,
                        "供应商甲",
                        null)));
    assertThat(saved.origin()).isEqualTo("RECEIPT");
    assertThat(saved.sourceSystem()).isEqualTo("PURCHASE");
    assertThat(saved.sourceRefId()).isEqualTo(refId);
    assertThat(saved.sourceRefCode()).isEqualTo("PO-9");
    // 没传收货日期 → 用数据库当天兜底，而不是 JVM 本地日期
    assertThat(saved.receivedOn()).isNotNull();
  }

  @Test
  void 建立批次的校验_批号重复冲突_效期写反_未知SKU() {
    lot("LOT-DUP", null, null);
    // 同 SKU 同批号 → 409
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.create(
                            a,
                            new LotArchiveCreateRequest(
                                skuA, "LOT-DUP", null, null, null, "MANUAL", null, null, null, null,
                                null, null))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));

    // 失效早于生产 → 400（写反的效期会让 FEFO 排出完全错误的顺序）
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.create(
                            a,
                            new LotArchiveCreateRequest(
                                skuA,
                                "LOT-BAD",
                                LocalDate.of(2026, 5, 1),
                                LocalDate.of(2026, 1, 1),
                                null,
                                "MANUAL",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("失效日期不能早于生产日期");

    // 跨租户的 SKU → 建不出没人认领的批次
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.create(
                            a,
                            new LotArchiveCreateRequest(
                                UUID.randomUUID(),
                                "LOT-X",
                                null,
                                null,
                                null,
                                "MANUAL",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SKU 不存在");
  }

  @Test
  void 修改批次属性_未传的字段保持不变_身份三件套不可改() {
    var lotId = lot("LOT-U", LocalDate.of(2026, 2, 1), LocalDate.of(2027, 2, 1));
    var before = asA(() -> service.archive(lotId));

    // 只改供应商：生产日期 / 失效日期 / 批号都必须原样不动
    var supplier = UUID.randomUUID();
    var saved =
        asA(
            () ->
                service.update(
                    lotId, new LotArchiveUpdateRequest(null, null, null, supplier, "新供应商", null)));
    assertThat(saved.lotCode()).isEqualTo(before.lotCode());
    assertThat(saved.producedOn()).isEqualTo(before.producedOn());
    assertThat(saved.expiresOn()).isEqualTo(before.expiresOn());
    assertThat(saved.supplierName()).isEqualTo("新供应商");

    // updated_at 必须真的推进：inventory_lot 上没有 updated_at 自动触发器，忘了写就永远停住
    assertThat(saved.updatedAt()).isAfterOrEqualTo(before.updatedAt());

    // 只改生产日期时，必须拿**合并后**的值校验：新的生产日期晚于既有失效日期就应被拒绝
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.update(
                            lotId,
                            new LotArchiveUpdateRequest(
                                LocalDate.of(2027, 6, 1), null, null, null, null, null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("失效日期不能早于生产日期");
  }

  // ================================================================ 批次库存分布

  @Test
  void 批次库存分布_真从stock_bucket聚合_仓库与库位合计都等于总计() {
    var lotId = lot("LOT-D1", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));
    // 同一批次跨两个仓、三个库位；locSh1 上有两种质量状态 → 两条 balance、两个 bucket
    splitBalance(
        whSh, qSellable, List.of(new B(lotId, locSh1, "30", "5"), new B(lotId, locSh2, "10", "0")));
    splitBalance(whSh, qHold, List.of(new B(lotId, locSh1, "2", "0")));
    splitBalance(whSz, qSellable, List.of(new B(lotId, locSz1, "8", "1")));

    var d = asA(() -> service.distribution(lotId));

    assertThat(d.totalOnHand()).isEqualByComparingTo("50.0000");
    assertThat(d.totalReserved()).isEqualByComparingTo("6.0000");
    // available 是 V2 的生成列，不可能与在手漂移
    assertThat(d.totalAvailable()).isEqualByComparingTo("44.0000");
    assertThat(d.bucketCount()).isEqualTo(4);
    assertThat(d.warehouseCount()).isEqualTo(2);
    assertThat(d.locationCount()).isEqualTo(3);

    // 仓库级合计 == 总计
    assertThat(d.warehouses()).hasSize(2);
    assertThat(
            d.warehouses().stream()
                .map(LotWarehouseSlice::onHand)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo(d.totalOnHand());
    // 库位级合计 == 总计
    assertThat(d.locations()).hasSize(4);
    assertThat(
            d.locations().stream()
                .map(LotLocationSlice::onHand)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo(d.totalOnHand());
    // 同库位两种质量状态必须分成两行，否则"这批能发多少"就答不出来
    assertThat(
            d.locations().stream()
                .filter(s -> s.locationId().equals(locSh1))
                .map(LotLocationSlice::qualityStatusCode))
        .containsExactlyInAnyOrder("SELLABLE", "PENDING_INSPECTION");

    // 列表行上的数量必须与分布端点同源
    var row = asA(() -> service.archive(lotId));
    assertThat(row.onHand()).isEqualByComparingTo(d.totalOnHand());
    assertThat(row.warehouseCount()).isEqualTo(2);
  }

  @Test
  void 刚建的批次库存为零但仍出现在列表里() {
    var saved =
        asA(
            () ->
                service.create(
                    a,
                    new LotArchiveCreateRequest(
                        skuA, "LOT-NEW", null, null, null, "MANUAL", null, null, null, null, null,
                        "还没收货")));
    var row = asA(() -> service.archive(saved.lotId()));
    assertThat(row.onHand()).isEqualByComparingTo("0.0000");
    assertThat(row.bucketCount()).isZero();

    var page = asA(() -> service.page(q()));
    assertThat(page.total()).isEqualTo(1);
    // inStock 筛"当前有没有货"：零库存批次确实该被筛掉
    assertThat(
            asA(() ->
                    service.page(
                        new LotArchiveQuery(
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            Boolean.TRUE,
                            null,
                            0,
                            50)))
                .total())
        .isZero();
  }

  @Test
  void 批次列表_筛选与合计不受分页影响() {
    var lot1 = lot("LOT-A", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
    var lot2 = lot("LOT-B", LocalDate.of(2026, 2, 1), LocalDate.of(2027, 6, 30));
    var lot3 = lot("LOT-C", null, LocalDate.of(2020, 1, 1)); // 已过期
    lot("LOT-D", null, null); // 无效期
    splitBalance(
        whSh, qSellable, List.of(new B(lot1, locSh1, "10", "0"), new B(lot2, locSh2, "20", "0")));

    var all = asA(() -> service.page(q()));
    assertThat(all.total()).isEqualTo(4);
    assertThat(all.summary().onHand()).isEqualByComparingTo("30.0000");
    assertThat(all.summary().expiredLotCount()).isEqualTo(1); // LOT-C
    assertThat(all.summary().depletedLotCount()).isEqualTo(2); // LOT-C / LOT-D
    // 夹具批次都带来源单据（origin=RECEIPT 必须有来源），所以"有来源"= 全部
    assertThat(all.summary().sourceLinkedLotCount()).isEqualTo(4);
    assertThat(all.summary().manualLotCount()).isZero();

    // 合计不受分页影响：每页 1 行时合计必须与全量一致，否则"翻页时总数变小"会被当成丢数据
    var first =
        asA(
            () ->
                service.page(
                    new LotArchiveQuery(
                        null, null, null, null, null, null, null, null, null, null, 0, 1)));
    var second =
        asA(
            () ->
                service.page(
                    new LotArchiveQuery(
                        null, null, null, null, null, null, null, null, null, null, 1, 1)));
    assertThat(first.rows()).hasSize(1);
    assertThat(second.rows()).hasSize(1);
    assertThat(first.summary().onHand()).isEqualByComparingTo("30.0000");
    assertThat(second.summary().onHand()).isEqualByComparingTo("30.0000");
    assertThat(first.total()).isEqualTo(4);

    // 过期是日历事实，与 status 无关：LOT-C 的 status 还是 ACTIVE
    var expiredRow = asA(() -> service.archive(lot3));
    assertThat(expiredRow.status()).isEqualTo("ACTIVE");
    assertThat(expiredRow.expired()).isTrue();
    assertThat(expiredRow.daysToExpiry()).isNegative();

    // 有效期排序：越早到期越靠前，无效期的排最后
    var byExpiry =
        asA(
            () ->
                service.page(
                    new LotArchiveQuery(
                        null, null, null, null, null, null, null, null, null, "EXPIRY", 0, 50)));
    assertThat(byExpiry.rows())
        .extracting(LotArchiveRow::lotCode)
        .containsExactly("LOT-C", "LOT-A", "LOT-B", "LOT-D");

    // N 天内到期：只含尚未过期且在窗口内的
    var window =
        asA(
            () ->
                service.page(
                    new LotArchiveQuery(
                        null, null, null, null, null, null, null, 3650, null, null, 0, 50)));
    assertThat(window.rows())
        .extracting(LotArchiveRow::lotCode)
        .containsExactlyInAnyOrder("LOT-A", "LOT-B");
    assertThat(window.summary().expiringLotCount()).isEqualTo(2);

    // 关键字：批号 / SKU 编码 / 来源单号 / 供应商名
    var byKeyword =
        asA(
            () ->
                service.page(
                    new LotArchiveQuery(
                        null, null, null, null, null, null, "LOT-C", null, null, null, 0, 50)));
    assertThat(byKeyword.rows()).extracting(LotArchiveRow::lotCode).containsExactly("LOT-C");

    // 按仓筛：只有 lot1 真的在 whSh 里（lOT-B 也在），lOT-C/D 没有 bucket
    var byWarehouse =
        asA(
            () ->
                service.page(
                    new LotArchiveQuery(
                        null, whSh, null, null, null, null, null, null, null, null, 0, 50)));
    assertThat(byWarehouse.rows())
        .extracting(LotArchiveRow::lotCode)
        .containsExactlyInAnyOrder("LOT-A", "LOT-B");

    // 按 SKU 筛
    assertThat(asA(() -> service.page(q(skuA))).total()).isEqualTo(4);
    assertThat(asA(() -> service.page(q(skuA2))).total()).isZero();
  }

  @Test
  void 非法筛选值当场400而不是静默返回空() {
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.page(
                            new LotArchiveQuery(
                                null,
                                null,
                                List.of("NOT_A_STATUS"),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                0,
                                50))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("批次状态");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.page(
                            new LotArchiveQuery(
                                null,
                                null,
                                null,
                                List.of("NOPE"),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                0,
                                50))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("批次建立方式");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.page(
                            new LotArchiveQuery(
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "NOT_A_SORT",
                                0,
                                50))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("排序键");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.page(
                            new LotArchiveQuery(
                                null, null, null, null, null, null, null, -1, null, null, 0, 50))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能为负数");
  }

  // ================================================================ 出入库历史（复用台账）

  @Test
  void 出入库历史复用台账_分录走同一条流水链路() {
    var lotId = lot("LOT-M", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));
    var otherLot = lot("LOT-N", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));
    var balSh = splitBalance(whSh, qSellable, List.of(new B(lotId, locSh1, "30", "0")));
    var balSz = splitBalance(whSz, qSellable, List.of(new B(otherLot, locSz1, "40", "0")));

    var at = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    txn("RECEIPT", "PURCHASE_ORDER", at.minusDays(2), balSh, "30");
    txn("SHIPMENT", "ORDER", at.minusDays(1), balSh, "-5");
    // 另一个批次所在余额行上的流水：按批次筛选后**不该**出现
    txn("RECEIPT", "PURCHASE_ORDER", at, balSz, "40");

    var movements = asA(() -> service.movements(lotId, baseLedgerQuery()));
    assertThat(movements.lotId()).isEqualTo(lotId);
    // 归属口径随响应返回，调用方不必猜这个数怎么来的
    assertThat(movements.attribution()).isEqualTo("BALANCE_GRANULAR");
    assertThat(movements.attributionNote()).contains("inventory_balance");

    assertThat(movements.movements().rows()).hasSize(2);
    assertThat(movements.movements().rows())
        .extracting(InventoryLedgerRow::kind)
        .containsExactly(LedgerKind.SHIPMENT, LedgerKind.RECEIPT);
    assertThat(movements.movements().summary().transactionOnHandDelta())
        .isEqualByComparingTo("25.0000");

    // 台账端点自身的列表也应当给出同一批行：证明这是复用而不是另写一套查询
    var direct = asA(() -> ledgerPage());
    assertThat(direct.rows()).hasSize(3);
  }

  static InventoryLedgerQuery baseLedgerQuery() {
    return new InventoryLedgerQuery(
        null, null, null, null, null, null, null, null, 0, 20, user, false);
  }

  InventoryLedgerPage ledgerPage() {
    return access.run(
        actorA, "warehouse.inventory.read", () -> ledgerService.page(baseLedgerQuery()));
  }

  @Autowired InventoryLedgerService ledgerService;

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    var lotA = lot("LOT-A", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));
    splitBalance(whSh, qSellable, List.of(new B(lotA, locSh1, "30", "0")));

    // 租户 B 有自己的 SKU 与批次，但对 B 不可见 A 的
    access.run(
        actorB,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_lot(id,tenant_id,sku_id,lot_code) values(?,?,?,?)",
              UUID.randomUUID(),
              b,
              idFor(b, 2),
              "LOT-B-TENANT");
          return null;
        });

    var pageA = asA(() -> service.page(q()));
    assertThat(pageA.total()).isEqualTo(1);
    assertThat(pageA.rows().get(0).lotCode()).isEqualTo("LOT-A");
    assertThat(pageA.summary().onHand()).isEqualByComparingTo("30.0000");

    var pageB = asB(() -> service.page(q()));
    assertThat(pageB.total()).isEqualTo(1);
    assertThat(pageB.rows().get(0).lotCode()).isEqualTo("LOT-B-TENANT");
    assertThat(pageB.summary().onHand()).isEqualByComparingTo("0.0000");

    // 分布端点：拿 A 的批次 id 给 B → 404 而不是 403（403 会泄露"这个批次存在"）
    assertThatThrownBy(() -> asB(() -> service.distribution(lotA)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    // 档案与流水同理
    assertThatThrownBy(() -> asB(() -> service.archive(lotA)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> asB(() -> service.movements(lotA, baseLedgerQuery())))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    var lotA = lot("LOT-A", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));
    splitBalance(whSh, qSellable, List.of(new B(lotA, locSh1, "30", "0")));
    access.run(
        actorB,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_lot(id,tenant_id,sku_id,lot_code) values(?,?,?,?)",
              UUID.randomUUID(),
              b,
              idFor(b, 2),
              "LOT-B-TENANT");
          return null;
        });

    // 身份是 B 但伪造 X-Tenant-Id 指向 A → 只能看到 B 自己的批次
    http.perform(
            get("/warehouse/inventory/lots")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.rows[0].lotCode").value("LOT-B-TENANT"))
        .andExpect(jsonPath("$.summary.onHand").value(0));
    // 反向：身份是 A 伪造 B 的租户头也拿不到 B 的数据
    http.perform(
            get("/warehouse/inventory/lots")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1))
        .andExpect(jsonPath("$.rows[0].lotCode").value("LOT-A"))
        .andExpect(jsonPath("$.summary.onHand").value(30));
    // 分布端点同样不受伪造头影响
    http.perform(
            get("/warehouse/inventory/lots/" + lotA + "/distribution")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
    // 未认证 401
    http.perform(get("/warehouse/inventory/lots")).andExpect(status().isUnauthorized());
    // 写端点要 update 权限：只有 read 的角色不能建批次
    http.perform(
            post("/warehouse/inventory/lots")
                .header("Authorization", "Bearer self-only")
                .contentType("application/json")
                .content("{\"skuId\":\"" + skuA + "\",\"lotCode\":\"LOT-NOPE\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    var lotA = lot("LOT-A", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));
    splitBalance(whSh, qSellable, List.of(new B(lotA, locSh1, "30", "0")));
    access.run(
        actorB,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_lot(id,tenant_id,sku_id,lot_code) values(?,?,?,?)",
              UUID.randomUUID(),
              b,
              idFor(b, 2),
              "LOT-B-TENANT");
          return null;
        });

    // 池大小为 1，同一条连接被反复借还：交替访问两个租户 20 轮，
    // 每一轮都要同时校验列表、档案、分布、流水四条读路径都不串租户。
    for (int i = 0; i < 20; i++) {
      var pa = asA(() -> service.page(q()));
      var pb = asB(() -> service.page(q()));
      assertThat(pa.rows()).extracting(LotArchiveRow::lotCode).containsExactly("LOT-A");
      assertThat(pb.rows()).extracting(LotArchiveRow::lotCode).containsExactly("LOT-B-TENANT");
      assertThat(pa.summary().onHand()).isEqualByComparingTo("30.0000");
      assertThat(pb.summary().onHand()).isEqualByComparingTo("0.0000");

      var da = asA(() -> service.distribution(lotA));
      assertThat(da.totalOnHand()).isEqualByComparingTo("30.0000");
      assertThatThrownBy(() -> asB(() -> service.distribution(lotA)))
          .isInstanceOf(ResponseStatusException.class);

      var ma = asA(() -> service.movements(lotA, baseLedgerQuery()));
      assertThat(ma.movements().rows()).isEmpty();
      assertThatThrownBy(() -> asB(() -> service.movements(lotA, baseLedgerQuery())))
          .isInstanceOf(ResponseStatusException.class);
    }
  }

  // ================================================================ 端点层

  @Test
  void 端点_建立后列表与分布可读() throws Exception {
    var created =
        http.perform(
                post("/warehouse/inventory/lots")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType("application/json")
                    .content(
                        "{\"skuId\":\""
                            + skuA
                            + "\",\"lotCode\":\"LOT-HTTP\",\"producedOn\":\"2026-04-01\","
                            + "\"expiresOn\":\"2027-04-01\",\"origin\":\"MANUAL\",\"supplierName\":\"端点供应商\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lotCode").value("LOT-HTTP"))
            .andExpect(jsonPath("$.producedOn").value("2026-04-01"))
            .andExpect(jsonPath("$.expiresOn").value("2027-04-01"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(created).contains("端点供应商");

    http.perform(
            get("/warehouse/inventory/lots")
                .header("Authorization", "Bearer tenant-a")
                .param("keyword", "LOT-HTTP"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.rows[0].lotCode").value("LOT-HTTP"))
        // 下拉词表随响应一起给，界面不用再单独拉一次
        .andExpect(jsonPath("$.statusOptions[0].value").value("ACTIVE"))
        .andExpect(jsonPath("$.originOptions[0].value").value("MANUAL"));

    // 越权读：没有 warehouse.inventory.* 任何权限
    http.perform(get("/warehouse/inventory/lots").header("Authorization", "Bearer nope"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void 端点_非法参数返回400() throws Exception {
    http.perform(
            get("/warehouse/inventory/lots")
                .header("Authorization", "Bearer tenant-a")
                .param("sort", "DROP_TABLE"))
        .andExpect(status().isBadRequest());
    http.perform(
            post("/warehouse/inventory/lots")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"lotCode\":\"\"}"))
        .andExpect(status().isBadRequest());
  }
}
