package com.starcon.warehouse;

import static com.starcon.warehouse.AvailableQuantityModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
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
 * 7.1.1.3 可用量计算。
 *
 * <p>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。连接池固定为 1 条连接： 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>本测试钉住八件「错了会误导人、而且错了没人会发现」的事：
 *
 * <ul>
 *   <li><b>五片恒等式</b>：合格可售 + 待检 + 残次 + 未上架 + 批次冻结 == available。
 *       这是蓝图「不重复扣减」的可执行定义；它破了就是口径改错了，而口径错了就是超卖。
 *   <li><b>互斥性专门造反例</b>：待检品放在<b>不可拣</b>库位上。若四片没有 role 前缀， 这批货会同时落进待检片与未上架片 —— 恒等式当场破掉，这一条用例就是防它的。
 *   <li><b>安全缓冲不被 bucket 行数放大</b>：同一个 SKU 摆 5 条 bucket、SKU 档 safety_stock=10， 只能扣 10，扣 50
 *       就是把同一份承诺算了五遍。这是最容易犯也最难被发现的错。
 *   <li><b>SKU 档只计一次、WAREHOUSE 档按仓各计一次</b>：同一份承诺不能因为跨了三个仓变三份。
 *   <li><b>没配缓冲给 null 不给 0</b>：「没留缓冲」与「留了 0 件缓冲」在界面上必须能区分。
 *   <li><b>缓冲大于基数</b>：可售量钳到 0 并把事实报出来，而不是发一个负数回平台。
 *   <li><b>NUMERIC 精确</b>：0.1×3 读回必须是 0.3。
 *   <li><b>租户隔离三断言</b>实跑 + FORCE RLS 逐张断言。
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AvailableQuantityIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      List.of("warehouse.inventory.read", "warehouse.inventory.update");

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-available-quantity-" + tenant + "-" + ordinal).getBytes());
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
          actor(user, a, List.of(grant("ALL", List.of("warehouse.inventory.read")))));
      actors.put(
          "Bearer no-permission",
          actor(user, a, List.of(grant("ALL", List.of("warehouse.other.read")))));
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

  @Autowired AvailableQuantityService service;
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
          "truncate stock_bucket, inventory_balance, inventory_lot, warehouse_location,"
              + " inventory_quality_status, legal_entity_ref, sku_ref, warehouse,"
              + " inventory_policy, reservation_entry, reservation_allocation, inventory_reservation,"
              + " sellable_policy_warehouse, sellable_policy restart identity cascade");
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
            for (int i = 2; i <= 5; i++) {
              sql.execute(
                  "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                  idFor(tenant, i),
                  tenant,
                  "SKU-" + (i - 1),
                  "商品" + (i - 1));
            }
            // 质量状态三值词表（V1）：可售 / 待检 / 残次
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 6),
                tenant,
                "GOOD",
                "合格",
                "SELLABLE");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 7),
                tenant,
                "PENDING",
                "待检",
                "PENDING_INSPECTION");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 8),
                tenant,
                "DEFECT",
                "残次",
                "DEFECTIVE");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,kind,country_code,timezone,"
                    + "default_legal_entity_id,status) values(?,?,?,?,?,?,?,?,?)",
                idFor(tenant, 9),
                tenant,
                "WH-1",
                "本租仓库",
                "LOCAL",
                "CN",
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

  UUID wh(UUID tenant) {
    return idFor(tenant, 9);
  }

  UUID sku(UUID tenant, int ordinal) {
    return idFor(tenant, ordinal);
  }

  UUID good(UUID tenant) {
    return idFor(tenant, 6);
  }

  UUID pending(UUID tenant) {
    return idFor(tenant, 7);
  }

  UUID defective(UUID tenant) {
    return idFor(tenant, 8);
  }

  // ================================================================ 夹具

  static int locationSeq;
  static int bucketSeq;

  UUID insertLocation(UUID tenant, String code, boolean pickable, boolean active) {
    return access.run(
        actorFor(tenant),
        "warehouse.inventory.read",
        () -> {
          UUID id = UUID.nameUUIDFromBytes(("loc-" + tenant + "-" + code).getBytes());
          sql.execute(
              "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,status,pickable)"
                  + " values(?,?,?,?,?,'STORAGE',?,?)",
              id,
              tenant,
              wh(tenant),
              code,
              "库位" + code,
              active ? "ACTIVE" : "INACTIVE",
              pickable);
          return id;
        });
  }

  UUID insertLot(UUID tenant, UUID skuId, String code, String status) {
    return access.run(
        actorFor(tenant),
        "warehouse.inventory.read",
        () -> {
          UUID id = UUID.nameUUIDFromBytes(("lot-" + tenant + "-" + code).getBytes());
          sql.execute(
              "insert into inventory_lot(id,tenant_id,sku_id,lot_code,status) values(?,?,?,?,?)",
              id,
              tenant,
              skuId,
              code,
              status);
          return id;
        });
  }

  /**
   * 建一条 bucket，并在需要时把所属 balance 的在手/预占**累加**上去。
   *
   * <p>{@code inventory_balance} 的唯一键是 (租户, SKU, 仓, 法人, 质量状态) —— 一个 SKU 的多条 bucket 共用<b>同一行</b>
   * balance。所以第二次给同键的 SKU 落第二条 bucket 时必须累加而不是再插一行 （再插会被唯一约束挡下，而那正是 V2 设计的意图：一个 balance 只有一条）。 累加与插
   * bucket 在同一事务里，守恒是延迟约束，提交时两侧自动对齐。
   */
  UUID insertBucket(
      UUID tenant,
      UUID skuId,
      UUID locationId,
      UUID lotId,
      UUID qualityId,
      BigDecimal onHand,
      BigDecimal reserved) {
    return access.run(
        actorFor(tenant),
        "warehouse.inventory.read",
        () -> {
          // fetchOne 无匹配行时返回 null（不是空 record），必须先判空再取列。
          var existing =
              sql.fetchOne(
                  "select id from inventory_balance where tenant_id=? and sku_id=?"
                      + " and warehouse_id=? and legal_entity_id=? and quality_status_id=?",
                  tenant,
                  skuId,
                  wh(tenant),
                  idFor(tenant, 1),
                  qualityId);
          if (existing == null) {
            UUID balanceId = UUID.randomUUID();
            sql.execute(
                "insert into inventory_balance(id,tenant_id,legal_entity_id,sku_id,warehouse_id,"
                    + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,?)",
                balanceId,
                tenant,
                idFor(tenant, 1),
                skuId,
                wh(tenant),
                qualityId,
                onHand,
                reserved);
            UUID bucketId = UUID.randomUUID();
            sql.execute(
                "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,location_id,"
                    + "lot_id,quality_status_id,balance_id,on_hand,reserved)"
                    + " values(?,?,?,?,?,?,?,?,?,?,?)",
                bucketId,
                tenant,
                idFor(tenant, 1),
                skuId,
                wh(tenant),
                locationId,
                lotId,
                qualityId,
                balanceId,
                onHand,
                reserved);
            return bucketId;
          }
          UUID balance = existing.get(0, UUID.class);
          sql.execute(
              "update inventory_balance set on_hand=on_hand+?, reserved=reserved+?"
                  + " where id=? and tenant_id=?",
              onHand,
              reserved,
              balance,
              tenant);
          UUID bucketId = UUID.randomUUID();
          sql.execute(
              "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,location_id,"
                  + "lot_id,quality_status_id,balance_id,on_hand,reserved)"
                  + " values(?,?,?,?,?,?,?,?,?,?,?)",
              bucketId,
              tenant,
              idFor(tenant, 1),
              skuId,
              wh(tenant),
              locationId,
              lotId,
              qualityId,
              balance,
              onHand,
              reserved);
          return bucketId;
        });
  }

  void insertPolicy(UUID tenant, String scopeType, String scopeKey, BigDecimal safetyStock) {
    access.run(
        actorFor(tenant),
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_policy(id,tenant_id,scope_type,scope_key,safety_stock)"
                  + " values(?,?,?,?,?)",
              UUID.randomUUID(),
              tenant,
              scopeType,
              scopeKey,
              safetyStock);
          return null;
        });
  }

  AvailableQuantityReport report(UUID tenant) {
    return report(
        tenant,
        new AvailableQuantityQuery(
            null, null, null, null, null, null, null, null, null, null, null));
  }

  AvailableQuantityReport report(UUID tenant, AvailableQuantityQuery q) {
    return access.run(actorFor(tenant), "warehouse.inventory.read", () -> service.report(q));
  }

  AvailableQuantityPage skus(UUID tenant) {
    return skus(
        tenant,
        new AvailableQuantityQuery(null, null, null, null, null, null, null, null, null, 0, 50));
  }

  AvailableQuantityPage skus(UUID tenant, AvailableQuantityQuery q) {
    return access.run(actorFor(tenant), "warehouse.inventory.read", () -> service.skuRows(q));
  }

  static BigDecimal amount(AvailableQuantityTotals t, String code) {
    return t.slices().stream()
        .filter(s -> s.code().equals(code))
        .findFirst()
        .orElseThrow()
        .amount();
  }

  // ================================================================ 口径恒等式

  @Test
  @DisplayName("五片互斥且穷尽：Σ五片 == available（蓝图「不重复扣减」的可执行定义）")
  void slicesAreMutuallyExclusiveAndExhaustive() {
    UUID pickable = insertLocation(a, "PICK-1", true, true);
    UUID blocked = insertLocation(a, "BLOCK-1", false, true);
    UUID inactive = insertLocation(a, "OFF-1", true, false);
    UUID frozenLot = insertLot(a, sku(a, 2), "LOT-FROZEN", "EXPIRED");
    UUID activeLot = insertLot(a, sku(a, 2), "LOT-OK", "ACTIVE");

    insertBucket(a, sku(a, 2), pickable, activeLot, good(a), bd(100), bd(0));
    insertBucket(a, sku(a, 2), pickable, null, good(a), bd(30), bd(10)); // 预占 10
    insertBucket(a, sku(a, 2), blocked, activeLot, good(a), bd(50), bd(0)); // 不可拣库位
    insertBucket(a, sku(a, 2), inactive, activeLot, good(a), bd(20), bd(0)); // 停用库位
    insertBucket(a, sku(a, 3), pickable, frozenLot, good(a), bd(40), bd(0)); // 批次过期
    insertBucket(a, sku(a, 3), blocked, null, pending(a), bd(15), bd(0)); // 待检 + 不可拣
    insertBucket(a, sku(a, 4), pickable, null, pending(a), bd(8), bd(0));
    insertBucket(a, sku(a, 4), pickable, null, defective(a), bd(3), bd(0));

    var t = report(a).totals();
    assertThat(t.sliceBalanced()).isTrue();
    // 120 合格（100 + (30−10 预占)）+ 50 不可拣 + 20 停用 + 40 批次冻结 + 23 待检 + 3 残次
    assertThat(amount(t, "SELLABLE")).isEqualByComparingTo("120");
    assertThat(amount(t, "UNLISTED")).isEqualByComparingTo("70"); // 50 + 20
    assertThat(amount(t, "BLOCKED_LOT")).isEqualByComparingTo("40");
    assertThat(amount(t, "PENDING_INSPECTION")).isEqualByComparingTo("23"); // 15 + 8
    assertThat(amount(t, "DEFECTIVE")).isEqualByComparingTo("3");
    // available = on_hand − reserved = 266 − 10
    assertThat(t.available()).isEqualByComparingTo("256");
    assertThat(
            t.slices().stream()
                .map(AvailableQuantitySlice::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo(t.available());
    // 只有合格可售片计入基数
    assertThat(t.sellableBeforeBuffer()).isEqualByComparingTo("120");
    assertThat(t.slices())
        .filteredOn(AvailableQuantitySlice::countedInSellable)
        .singleElement()
        .extracting(AvailableQuantitySlice::code)
        .isEqualTo("SELLABLE");
  }

  @Test
  @DisplayName("互斥性反例：待检品放在不可拣库位上，只落待检片，不重复进未上架片")
  void pendingOnUnpickableLocationIsNotDoubleCounted() {
    UUID blocked = insertLocation(a, "QUAR-1", false, true);
    insertBucket(a, sku(a, 2), blocked, null, pending(a), bd(15), bd(0));

    var t = report(a).totals();
    assertThat(amount(t, "PENDING_INSPECTION")).isEqualByComparingTo("15");
    assertThat(amount(t, "UNLISTED")).isEqualByComparingTo("0");
    assertThat(amount(t, "SELLABLE")).isEqualByComparingTo("0");
    assertThat(t.sliceBalanced()).isTrue();
  }

  @Test
  @DisplayName("可用 available = on_hand − reserved，来自生成列且无第三个来源")
  void availableIsGeneratedColumnWithoutThirdSource() {
    UUID loc = insertLocation(a, "PICK-1", true, true);
    insertBucket(a, sku(a, 2), loc, null, good(a), bd(120), bd(35));
    var t = report(a).totals();
    assertThat(t.onHand()).isEqualByComparingTo("120");
    assertThat(t.reserved()).isEqualByComparingTo("35");
    assertThat(t.available()).isEqualByComparingTo("85");
    assertThat(t.available()).isEqualByComparingTo(t.onHand().subtract(t.reserved()));
    assertThat(t.sellableBeforeBuffer()).isEqualByComparingTo("85"); // 可售货照扣预占
  }

  // ================================================================ 安全缓冲

  @Test
  @DisplayName("安全缓冲不被 bucket 行数放大：5 条 bucket、SKU 档 10 → 只扣 10")
  void safetyBufferIsNotAmplifiedByBucketRows() {
    // 同一个 SKU 散在 5 个可拣库位 —— 这是「多 bucket 行」的真实形态。
    // （同一库位同一批次再插第二条会被 uq_stock_bucket_owner_scope 挡下，那正是六维唯一性的意图。）
    for (int i = 1; i <= 5; i++) {
      UUID loc = insertLocation(a, "PICK-" + i, true, true);
      insertBucket(a, sku(a, 2), loc, null, good(a), bd(20), bd(0));
    }
    insertPolicy(a, "SKU", sku(a, 2).toString(), bd(10));

    var t = report(a).totals();
    assertThat(t.onHand()).isEqualByComparingTo("100");
    assertThat(t.safetyBuffer()).isEqualByComparingTo("10");
    assertThat(t.safetyBufferDeducted()).isEqualByComparingTo("10");
    assertThat(t.bufferedSkuCount()).isEqualTo(1);
    assertThat(t.sellableBeforeBuffer()).isEqualByComparingTo("100");
    assertThat(t.sellable()).isEqualByComparingTo("90");
  }

  @Test
  @DisplayName("SKU 档只计一次：一个 SKU 跨多个仓也不把同一份承诺算多遍")
  void skuScopeBufferCountedOnceAcrossWarehouses() {
    UUID loc = insertLocation(a, "PICK-1", true, true);
    insertBucket(a, sku(a, 2), loc, null, good(a), bd(60), bd(0));
    insertPolicy(a, "SKU", sku(a, 2).toString(), bd(25));

    var rows = skus(a).rows();
    assertThat(rows).hasSize(1);
    var row = rows.get(0);
    assertThat(row.safetyBuffer()).isEqualByComparingTo("25");
    assertThat(row.bufferScopeType()).isEqualTo("SKU");
    assertThat(row.sellable()).isEqualByComparingTo("35");
  }

  @Test
  @DisplayName("SKU 档优先于 WAREHOUSE 档；两者都没有才落到 DEFAULT 兜底档")
  void bufferScopePrioritySkuThenWarehouseThenDefault() {
    UUID loc = insertLocation(a, "PICK-1", true, true);
    insertBucket(a, sku(a, 2), loc, null, good(a), bd(100), bd(0));
    insertBucket(a, sku(a, 3), loc, null, good(a), bd(100), bd(0));
    insertPolicy(a, "WAREHOUSE", wh(a).toString(), bd(8));
    insertPolicy(a, "DEFAULT", "", bd(4));

    var rows =
        skus(a).rows().stream()
            .collect(java.util.stream.Collectors.toMap(r -> r.skuCode(), r -> r));
    // 两个 SKU 都命中 WAREHOUSE 档（比 DEFAULT 更具体），各扣 8
    assertThat(rows.get("SKU-1").safetyBuffer()).isEqualByComparingTo("8");
    assertThat(rows.get("SKU-1").bufferScopeType()).isEqualTo("WAREHOUSE");
    assertThat(rows.get("SKU-2").safetyBuffer()).isEqualByComparingTo("8");

    // SKU 档一命中就压过仓库档
    insertPolicy(a, "SKU", sku(a, 2).toString(), bd(30));
    rows =
        skus(a).rows().stream()
            .collect(java.util.stream.Collectors.toMap(r -> r.skuCode(), r -> r));
    assertThat(rows.get("SKU-1").safetyBuffer()).isEqualByComparingTo("30");
    assertThat(rows.get("SKU-1").bufferScopeType()).isEqualTo("SKU");
    assertThat(rows.get("SKU-1").sellable()).isEqualByComparingTo("70");
    assertThat(rows.get("SKU-2").sellable()).isEqualByComparingTo("92");
  }

  @Test
  @DisplayName("WAREHOUSE 档存在但 safety_stock 为 null 时落到 DEFAULT，且 scope 不误报 WAREHOUSE")
  void warehousePolicyWithoutSafetyStockFallsThroughToDefault() {
    UUID loc = insertLocation(a, "PICK-1", true, true);
    insertBucket(a, sku(a, 2), loc, null, good(a), bd(50), bd(0));
    insertPolicy(a, "WAREHOUSE", wh(a).toString(), null);
    insertPolicy(a, "DEFAULT", "", bd(6));

    var row = skus(a).rows().get(0);
    assertThat(row.safetyBuffer()).isEqualByComparingTo("6");
    assertThat(row.bufferScopeType()).isEqualTo("DEFAULT");
  }

  @Test
  @DisplayName("一个 SKU 都没配缓冲时 safetyBuffer 为 null 而不是 0")
  void unconfiguredBufferIsNullNotZero() {
    UUID loc = insertLocation(a, "PICK-1", true, true);
    insertBucket(a, sku(a, 2), loc, null, good(a), bd(50), bd(0));

    var t = report(a).totals();
    assertThat(t.safetyBuffer()).isNull();
    assertThat(t.safetyBufferDeducted()).isEqualByComparingTo("0"); // 算式里没有减项
    assertThat(t.bufferedSkuCount()).isZero();
    assertThat(t.sellable()).isEqualByComparingTo("50");

    var row = skus(a).rows().get(0);
    assertThat(row.safetyBuffer()).isNull();
    assertThat(row.bufferScopeType()).isEqualTo("NONE");
  }

  @Test
  @DisplayName("缓冲大于可售基数时钳到 0 并如实报出，不发负数")
  void bufferLargerThanBaseClampsToZero() {
    UUID loc = insertLocation(a, "PICK-1", true, true);
    insertBucket(a, sku(a, 2), loc, null, good(a), bd(20), bd(0));
    insertPolicy(a, "SKU", sku(a, 2).toString(), bd(50));

    var t = report(a).totals();
    assertThat(t.bufferExceedsBase()).isTrue();
    assertThat(t.sellable()).isEqualByComparingTo("0");
    assertThat(t.sellableBeforeBuffer()).isEqualByComparingTo("20");
    assertThat(skus(a).rows().get(0).bufferExceedsBase()).isTrue();
  }

  // ================================================================ 精度与分页

  @Test
  @DisplayName("NUMERIC 精确：0.1 三份读回是 0.3，不是浮点残渣")
  void numericPrecisionIsExact() {
    UUID loc = insertLocation(a, "PICK-1", true, true);
    insertBucket(a, sku(a, 2), loc, null, good(a), new BigDecimal("0.1"), bd(0));
    insertBucket(a, sku(a, 3), loc, null, good(a), new BigDecimal("0.1"), bd(0));
    insertBucket(a, sku(a, 4), loc, null, good(a), new BigDecimal("0.1"), bd(0));
    assertThat(report(a).totals().sellableBeforeBuffer()).isEqualByComparingTo("0.3");
  }

  @Test
  @DisplayName("合计不受分页与排序影响")
  void totalsIgnorePagingAndSort() {
    UUID loc = insertLocation(a, "PICK-1", true, true);
    for (int i = 2; i <= 5; i++) {
      insertBucket(a, sku(a, i), loc, null, good(a), bd(i * 10), bd(0));
    }
    var first =
        skus(
            a,
            new AvailableQuantityQuery(
                null, null, null, null, null, null, null, null, "SELLABLE", 0, 2));
    var second =
        skus(
            a,
            new AvailableQuantityQuery(
                null, null, null, null, null, null, null, null, "SKU_CODE", 1, 2));
    assertThat(first.total()).isEqualTo(4);
    assertThat(first.rows()).hasSize(2);
    assertThat(second.rows()).hasSize(2);
    assertThat(first.totals().onHand()).isEqualByComparingTo(second.totals().onHand());
    assertThat(first.totals().onHand()).isEqualByComparingTo("140"); // 20+30+40+50
  }

  @Test
  @DisplayName("非法排序键报 400，不静默按默认序返回")
  void illegalSortKeyIsRejected() {
    UUID loc = insertLocation(a, "PICK-1", true, true);
    insertBucket(a, sku(a, 2), loc, null, good(a), bd(10), bd(0));
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    skus(
                        a,
                        new AvailableQuantityQuery(
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            "on_hand; drop table stock_bucket",
                            0,
                            10))))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("白名单");
  }

  // ================================================================ 预占口径

  @Test
  @DisplayName("预占从 reserved 进算式：同一批货不会被扣两次")
  void reservationEntersThroughReservedColumnOnly() {
    UUID loc = insertLocation(a, "PICK-1", true, true);
    UUID bucket = insertBucket(a, sku(a, 2), loc, null, good(a), bd(100), bd(30));

    // 造一笔真实预占：V102 的 reservation + V107 的 allocation（预占与释放是 7.2.1.2 / 7.2.1.3 的职责，
    // 这里只用来证明「预占确实是通过 reserved 进来的」）。
    var reservationId = UUID.randomUUID();
    var allocationId = UUID.randomUUID();
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_reservation(id,tenant_id,reservation_no,source_system,"
                  + "source_type,legal_entity_id,sku_id,requested_quantity,status)"
                  + " values(?,?,?, 'MANUAL','OTHER_HOLD',?,?,?, 'HELD')",
              reservationId,
              a,
              "RSV-TEST-1",
              idFor(a, 1),
              sku(a, 2),
              bd(30));
          sql.execute(
              "insert into reservation_allocation(id,tenant_id,reservation_id,warehouse_id,"
                  + "location_id,lot_id,allocated_quantity,status) values(?,?,?,?,?,?,?, 'HELD')",
              allocationId,
              a,
              reservationId,
              wh(a),
              loc,
              null,
              bd(30));
          return null;
        });

    var t = report(a).totals();
    assertThat(t.reserved()).isEqualByComparingTo("30");
    assertThat(t.available()).isEqualByComparingTo("70");
    assertThat(t.sellableBeforeBuffer()).isEqualByComparingTo("70");
    assertThat(t.sliceBalanced()).isTrue();
    assertThat(bucket).isNotNull();
  }

  // ================================================================ 能力声明

  @Test
  @DisplayName("能力声明如实报出未交付维度：按日期效期与预占过期释放都不是本模块的职责")
  void capabilitiesReportUndeliveredDimensionsHonestly() {
    var c = service.capabilities();
    assertThat(c.quantityRoleAvailable()).isTrue();
    assertThat(c.locationPickableAvailable()).isTrue();
    assertThat(c.lotStatusAvailable()).isTrue();
    assertThat(c.safetyStockAvailable()).isTrue();
    assertThat(c.channelPolicyAvailable()).isTrue();
    // 刻意不做 —— 必须报 false 且写明归属模块
    assertThat(c.expiryByDateAvailable()).isFalse();
    assertThat(c.expiryByDateOwnerModule()).isEqualTo("7.3.1.2");
    assertThat(c.reservationReleaseAvailable()).isFalse();
    assertThat(c.reservationReleaseOwnerModule()).isEqualTo("7.2.1.3");
    // 口径原文随响应返回（可售量是全系统单一口径来源，口径本身必须能被读到）
    assertThat(c.availableFormula()).contains("max(0").contains("safety_stock");
    assertThat(c.sliceIdentity()).contains("不重复扣减");
    assertThat(c.releaseNote()).contains("7.2.1.3").contains("reserved");
  }

  @Test
  @DisplayName("未配置渠道口径时如实报 configured=false，不按 0 处理")
  void channelPolicyUnconfiguredIsHonest() {
    var policy = report(a).channelPolicy();
    assertThat(policy.configured()).isFalse();
    assertThat(policy.policyId()).isNull();
    assertThat(policy.bufferRatio()).isNull();
    assertThat(policy.ownerModule()).isEqualTo("7.2.2.1");
    assertThat(policy.note()).contains("未配置");
  }

  @Test
  @DisplayName("渠道口径的 bufferRatio 只展示不被混用：它与安全缓冲不是同一个数")
  void channelBufferRatioIsSurfacedButNotConflated() {
    UUID loc = insertLocation(a, "PICK-1", true, true);
    insertBucket(a, sku(a, 2), loc, null, good(a), bd(100), bd(0));
    insertPolicy(a, "SKU", sku(a, 2).toString(), bd(20));
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into sellable_policy(id,tenant_id,scope_type,pool_mode,pool_key,"
                  + "platform_stock_scope,buffer_ratio,include_in_transit,effective_from,"
                  + "created_by_membership_id)"
                  + " values(?,?, 'DEFAULT','EXCLUSIVE','EXCL-','OWN_WAREHOUSE',0.1,false, now() at time zone 'UTC', ?)",
              UUID.randomUUID(),
              a,
              UUID.randomUUID());
          return null;
        });

    var r = report(a);
    assertThat(r.channelPolicy().configured()).isTrue();
    assertThat(r.channelPolicy().bufferRatio()).isEqualByComparingTo("0.1");
    // 安全缓冲仍然是 20 件，与 10% 的比例毫无关系
    assertThat(r.totals().safetyBuffer()).isEqualByComparingTo("20");
    assertThat(r.totals().sellable()).isEqualByComparingTo("80");
  }

  // ================================================================ 筛选

  @Test
  @DisplayName("逐级筛选真的生效：仓 / 库位 / SKU / 关键字")
  void filtersActuallyNarrowTheScope() {
    UUID pick = insertLocation(a, "PICK-1", true, true);
    UUID other = insertLocation(a, "PICK-2", true, true);
    insertBucket(a, sku(a, 2), pick, null, good(a), bd(100), bd(0));
    insertBucket(a, sku(a, 3), other, null, good(a), bd(40), bd(0));

    var all = report(a).totals();
    assertThat(all.onHand()).isEqualByComparingTo("140");

    var bySku = report(a, q(sku(a, 2))).totals();
    assertThat(bySku.onHand()).isEqualByComparingTo("100");

    var byLocation = report(a, q(null, pick)).totals();
    assertThat(byLocation.onHand()).isEqualByComparingTo("100");

    var byKeyword = report(a, q(null, null, null, "SKU-2")).totals();
    assertThat(byKeyword.onHand()).isEqualByComparingTo("40");
  }

  static AvailableQuantityQuery q(UUID skuId, UUID locationId, UUID warehouseId, String keyword) {
    return new AvailableQuantityQuery(
        warehouseId, null, locationId, skuId, null, keyword, null, null, null, 0, 50);
  }

  static AvailableQuantityQuery q(UUID skuId) {
    return q(skuId, null, null, null);
  }

  static AvailableQuantityQuery q(UUID skuId, UUID locationId) {
    return q(skuId, locationId, null, null);
  }

  @Test
  @DisplayName("空范围返回全 0 且五片恒等成立，不返回 null 也不报错")
  void emptyScopeReturnsZeros() {
    var t = report(a).totals();
    assertThat(t.onHand()).isEqualByComparingTo("0");
    assertThat(t.available()).isEqualByComparingTo("0");
    assertThat(t.sellable()).isEqualByComparingTo("0");
    assertThat(t.safetyBuffer()).isNull();
    assertThat(t.sliceBalanced()).isTrue();
    assertThat(skus(a).rows()).isEmpty();
  }

  // ================================================================ 多租户

  @Test
  @DisplayName("租户隔离断言一：两租户互不可见")
  void tenantsAreIsolated() {
    UUID locA = insertLocation(a, "PICK-1", true, true);
    insertBucket(a, sku(a, 2), locA, null, good(a), bd(100), bd(0));
    insertPolicy(a, "SKU", sku(a, 2).toString(), bd(5));

    UUID locB = insertLocation(b, "PICK-1", true, true);
    insertBucket(b, sku(b, 2), locB, null, good(b), bd(7), bd(0));

    // 各自只看到自己的数
    assertThat(report(a).totals().onHand()).isEqualByComparingTo("100");
    assertThat(report(b).totals().onHand()).isEqualByComparingTo("7");
    // 缓冲也不串
    assertThat(report(a).totals().safetyBuffer()).isEqualByComparingTo("5");
    assertThat(report(b).totals().safetyBuffer()).isNull();

    // B 租户按 A 租户的 SKU id 查 → 查不到（RLS 下表现为空，不是别人的数）
    assertThat(report(b, q(sku(a, 2))).totals().onHand()).isEqualByComparingTo("0");
    assertThat(skus(b, q(sku(a, 2))).rows()).isEmpty();
  }

  @Test
  @DisplayName("租户隔离断言二：伪造 X-Tenant-Id 头无效")
  void forgedTenantHeaderIsIneffective() throws Exception {
    UUID locA = insertLocation(a, "PICK-1", true, true);
    insertBucket(a, sku(a, 2), locA, null, good(a), bd(100), bd(0));

    // 用 A 的身份请求，但把头伪造成 B：租户由 identity 的成员关系解析，头进不来
    http.perform(
            get("/warehouse/inventory/sellable")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totals.onHand").value(100));
  }

  @Test
  @DisplayName("租户隔离断言三：连接池归还后不串租户（A→B→A→B 复用同一条物理连接）")
  void pooledConnectionsDoNotLeakTenant() {
    UUID locA = insertLocation(a, "PICK-1", true, true);
    insertBucket(a, sku(a, 2), locA, null, good(a), bd(111), bd(0));
    UUID locB = insertLocation(b, "PICK-1", true, true);
    insertBucket(b, sku(b, 2), locB, null, good(b), bd(222), bd(0));

    for (int round = 0; round < 4; round++) {
      assertThat(report(a).totals().onHand()).isEqualByComparingTo("111");
      assertThat(report(b).totals().onHand()).isEqualByComparingTo("222");
    }
  }

  @Test
  @DisplayName("本模块读的每张表都 FORCE ROW LEVEL SECURITY")
  void everyReadTableForcesRls() throws Exception {
    for (var table :
        List.of(
            "stock_bucket",
            "inventory_balance",
            "inventory_quality_status",
            "warehouse_location",
            "inventory_lot",
            "inventory_policy")) {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s =
              c.prepareStatement(
                  "select relforcerowsecurity,relrowsecurity from pg_class where relname=?")) {
        s.setString(1, table);
        try (var rs = s.executeQuery()) {
          assertThat(rs.next()).as("表 %s 必须存在", table).isTrue();
          assertThat(rs.getBoolean(1)).as("表 %s 必须 FORCE RLS", table).isTrue();
          assertThat(rs.getBoolean(2)).as("表 %s 必须 ENABLE RLS", table).isTrue();
        }
      }
    }
  }

  // ================================================================ 端点与权限

  @Test
  @DisplayName("端点返回真实数据，三个形状都可用")
  void endpointsReturnRealData() throws Exception {
    UUID loc = insertLocation(a, "PICK-1", true, true);
    insertBucket(a, sku(a, 2), loc, null, good(a), bd(100), bd(0));
    insertPolicy(a, "SKU", sku(a, 2).toString(), bd(15));

    http.perform(get("/warehouse/inventory/sellable").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totals.onHand").value(100))
        .andExpect(jsonPath("$.totals.sellable").value(85))
        .andExpect(jsonPath("$.totals.sliceBalanced").value(true))
        .andExpect(jsonPath("$.totals.slices.length()").value(5))
        .andExpect(jsonPath("$.totals.safetyBuffer").value(15))
        .andExpect(jsonPath("$.capabilities.expiryByDateAvailable").value(false))
        .andExpect(jsonPath("$.capabilities.reservationReleaseAvailable").value(false));

    http.perform(
            get("/warehouse/inventory/sellable/skus").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0].skuCode").value("SKU-1"))
        .andExpect(jsonPath("$.rows[0].sellable").value(85))
        .andExpect(jsonPath("$.total").value(1));

    http.perform(
            get("/warehouse/inventory/sellable/capabilities")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.availableFormula").isNotEmpty())
        .andExpect(jsonPath("$.sliceIdentity").isNotEmpty());
  }

  @Test
  @DisplayName("无权限 401 / 403：有读权限才放行")
  void permissionIsEnforced() throws Exception {
    http.perform(get("/warehouse/inventory/sellable")).andExpect(status().isUnauthorized());
    http.perform(
            get("/warehouse/inventory/sellable").header("Authorization", "Bearer no-permission"))
        .andExpect(status().isForbidden());
    http.perform(
            get("/warehouse/inventory/sellable/skus").header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk());
  }

  // ================================================================ 小工具

  static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }

  /** 整数夹具走字符串构造：{@code new BigDecimal(int)} 在 Java 里不存在，{@code valueOf(double)} 会引入浮点。 */
  static BigDecimal bd(int v) {
    return new BigDecimal(Integer.toString(v));
  }
}
