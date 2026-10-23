package com.starcon.warehouse;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 11.2.3.2 转可售/残次/报废 —— warehouse 侧执行。
 *
 * <p>跑的是蓝图输出句与那条红线：
 *
 * <blockquote>
 * 职责：按质检结论把退货转入对应状态：可再销售转可售库存、残次转残次库存（可用于清仓）、
 * 报废则核销并计入损耗。
 * 输出：库存状态变更分录；可售量更新；报废损耗记录。
 * <b>报废是不可逆的</b>，需审批与原因，不能由客服随手处理。
 * </blockquote>
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接。
 *
 * <p><b>本类最看重的七组断言</b>：
 *
 * <ol>
 *   <li><b>库存状态变更分录真的落库</b>：转可售 / 转残次 / 报废三条路径各落
 *       {@code inventory_transaction(kind='ADJUSTMENT', ref_type='RETURN_DISPOSITION')}
 *       + 一对带符号的 {@code inventory_entry}（-qty / +qty）。</li>
 *   <li><b>可售量更新</b>：转可售后可售量真的涨了；转残次 / 报废不动可售量。</li>
 *   <li><b>报废 = 核销，不是「挪到报废 bucket」</b>：没有目标 bucket、余额同步减少、
 *       货不再占任何库位（质量状态词表里本来就没有 SCRAP）。</li>
 *   <li><b>库位类型与质量状态一一对应</b>：残次只能进 DEFECTIVE 位，拿 STORAGE 位做目标要被拒。</li>
 *   <li><b>幂等</b>：同一张处置单执行两次，第二次是幂等重放，<b>库存没有再动一次</b>。</li>
 *   <li><b>只处置待检货</b>：拿可售 bucket 来处置要被拒（已处置过的货不能再处置）。</li>
 *   <li><b>库存不足要报错而不是把货扣成负数</b>。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReturnDispositionExecutionIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();

  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      Stream.of("inventory")
          .flatMap(
              p ->
                  Stream.of("read", "create", "update", "delete")
                      .map(op -> "warehouse." + p + "." + op))
          .toList();

  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-return-disposition-" + tenant + "-" + ordinal).getBytes());
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
            byte[] body = JSON.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, List.of(grant("ALL", allPermissions))));
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
    // 单连接：只有复用同一条连接才能证明连接池归还后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired AccessService access;
  @Autowired com.starcon.warehouse.ReturnDispositionExecutionService execService;
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
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate inventory_return_disposition_execution, inventory_entry, inventory_transaction,"
              + " stock_bucket, inventory_balance, warehouse_location, inventory_lot,"
              + " inventory_quality_status, warehouse, sku_ref, legal_entity_ref restart identity cascade");
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
                "WH-ONE",
                "一仓",
                "CN",
                "Asia/Shanghai");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 4),
                tenant,
                "SELLABLE",
                "可售",
                "SELLABLE");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 5),
                tenant,
                "PENDING_INSPECTION",
                "待检",
                "PENDING_INSPECTION");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 6),
                tenant,
                "DEFECTIVE",
                "残次",
                "DEFECTIVE");
            // 待检位（QUARANTINE）、可售位（STORAGE）、残次位（DEFECTIVE）：
            // 库位类型与质量状态一一对应（location_allocation_required_type）。
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,pickable)"
                    + " values(?,?,?,?,?,'QUARANTINE',false)",
                idFor(tenant, 7),
                tenant,
                idFor(tenant, 3),
                "Q-01",
                "待检区");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,pickable)"
                    + " values(?,?,?,?,?,'STORAGE',true)",
                idFor(tenant, 8),
                tenant,
                idFor(tenant, 3),
                "S-01",
                "存储区");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,pickable)"
                    + " values(?,?,?,?,?,'DEFECTIVE',false)",
                idFor(tenant, 9),
                tenant,
                idFor(tenant, 3),
                "D-01",
                "残次区");
            sql.execute(
                "insert into inventory_lot(id,tenant_id,sku_id,lot_code,received_on) values(?,?,?,?,?)",
                idFor(tenant, 10),
                tenant,
                idFor(tenant, 2),
                "LOT-A",
                java.sql.Date.valueOf("2026-09-01"));
            // 待检库存行：在手 10 件。
            UUID balanceId = UUID.randomUUID();
            sql.execute(
                "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                    + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
                balanceId,
                tenant,
                idFor(tenant, 2),
                idFor(tenant, 3),
                idFor(tenant, 1),
                idFor(tenant, 5),
                new BigDecimal("10"));
            sql.execute(
                "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,location_id,"
                    + "lot_id,quality_status_id,balance_id,on_hand,reserved,last_movement_at)"
                    + " values(?,?,?,?,?,?,?,?,?,?,0,now())",
                idFor(tenant, 11),
                tenant,
                idFor(tenant, 1),
                idFor(tenant, 2),
                idFor(tenant, 3),
                idFor(tenant, 7),
                idFor(tenant, 10),
                idFor(tenant, 5),
                balanceId,
                new BigDecimal("10"));
            return null;
          });
    }
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  // ================================================================ 夹具

  static OffsetDateTime now() {
    return OffsetDateTime.now();
  }

  /** 一条执行命令。 */
  Map<String, Object> command(
      UUID dispositionId, String targetState, BigDecimal qty, UUID targetLocationId) {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("dispositionId", dispositionId.toString());
    body.put("dispositionNo", "RDP-TEST-001");
    body.put("instructionId", UUID.randomUUID().toString());
    body.put("inspectionId", UUID.randomUUID().toString());
    body.put("targetInventoryState", targetState);
    body.put("skuId", idFor(a, 2).toString());
    body.put("skuCode", "SKU-1");
    body.put("skuName", "商品一");
    body.put("batchNo", "LOT-A");
    body.put("qty", qty);
    body.put("sourceBucketId", idFor(a, 11).toString());
    body.put("targetLocationId", targetLocationId == null ? null : targetLocationId.toString());
    body.put("occurredAt", now().toString());
    return body;
  }

  MvcResult execute(Map<String, Object> body) throws Exception {
    return http.perform(
            post("/warehouse/inventory/return-dispositions/execute")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(JSON.writeValueAsString(body)))
        .andReturn();
  }

  BigDecimal bucketOnHand(UUID bucketId) {
    return bucketOnHand(a, bucketId);
  }

  /** 按租户读在手量（跨租户断言要用对的身份，否则 RLS 会把行藏掉）。 */
  BigDecimal bucketOnHand(UUID tenant, UUID bucketId) {
    return access.run(
        actorFor(tenant),
        "warehouse.inventory.read",
        () ->
            sql.fetchOne(
                    "select on_hand from stock_bucket where tenant_id=? and id=?", tenant, bucketId)
                .get("on_hand", BigDecimal.class));
  }

  BigDecimal balanceOnHand(UUID balanceId) {
    return access.run(
        actorA,
        "warehouse.inventory.read",
        () ->
            sql.fetchOne(
                    "select on_hand from inventory_balance where tenant_id=? and id=?", a, balanceId)
                .get("on_hand", BigDecimal.class));
  }

  // ================================================================ 断言

  @Test
  void 转可售_库存状态变更分录真的落库且可售量真的涨了() throws Exception {
    var result = execute(command(UUID.randomUUID(), "SELLABLE", new BigDecimal("2"), idFor(a, 8)));
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    var json = JSON.readTree(result.getResponse().getContentAsString());
    assertThat(json.get("idempotentReplay").asBoolean()).isFalse();
    assertThat(json.get("sellableQuantityBefore").decimalValue()).isEqualByComparingTo("0");
    assertThat(json.get("sellableQuantityAfter").decimalValue()).isEqualByComparingTo("2");

    // 待检库存行减 2。
    assertThat(bucketOnHand(idFor(a, 11))).isEqualByComparingTo("8");
    // 可售库存行新建 2 件（目标库位 S-01）。
    Record sellable =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                sql.fetchOne(
                    "select b.on_hand,q.role from stock_bucket b"
                        + " join inventory_quality_status q on q.tenant_id=b.tenant_id"
                        + " and q.id=b.quality_status_id"
                        + " where b.tenant_id=? and b.quality_status_id=? and b.location_id=?",
                    a,
                    idFor(a, 4),
                    idFor(a, 8)));
    assertThat(sellable).isNotNull();
    assertThat(sellable.get("on_hand", BigDecimal.class)).isEqualByComparingTo("2");
    assertThat(sellable.get("role", String.class)).isEqualTo("SELLABLE");

    // 库存状态变更分录：一头两腿（-2 / +2），且头带上了 RETURN_DISPOSITION 的定位键。
    List<Record> tx =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                sql.fetch(
                    "select id,kind,ref_type,ref_id from inventory_transaction"
                        + " where tenant_id=? and ref_type='RETURN_DISPOSITION'",
                    a));
    assertThat(tx).hasSize(1);
    assertThat(tx.get(0).get("kind", String.class)).isEqualTo("ADJUSTMENT");

    List<Record> entries =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                sql.fetch(
                    "select e.on_hand_delta,e.balance_id from inventory_entry e"
                        + " join inventory_transaction t on t.tenant_id=e.tenant_id"
                        + " and t.id=e.transaction_id"
                        + " where e.tenant_id=? and t.ref_type='RETURN_DISPOSITION'"
                        + " order by e.on_hand_delta",
                    a));
    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).get("on_hand_delta", BigDecimal.class)).isEqualByComparingTo("-2");
    assertThat(entries.get(1).get("on_hand_delta", BigDecimal.class)).isEqualByComparingTo("2");

    // 执行回执落库（因果链）。
    Record receipt =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                sql.fetchOne(
                    "select target_inventory_state,qty,sellable_quantity_before,"
                        + "sellable_quantity_after from inventory_return_disposition_execution"
                        + " where tenant_id=?",
                    a));
    assertThat(receipt).isNotNull();
    assertThat(receipt.get("target_inventory_state", String.class)).isEqualTo("SELLABLE");
    assertThat(receipt.get("qty", BigDecimal.class)).isEqualByComparingTo("2");
    assertThat(receipt.get("sellable_quantity_after", BigDecimal.class)).isEqualByComparingTo("2");
  }

  @Test
  void 转残次_目标库位类型不对要被拒_残次品不计入可售量() throws Exception {
    // 拿 STORAGE 位当残次目标：库位类型与质量状态不匹配，必须拒。
    var wrong = execute(command(UUID.randomUUID(), "DEFECTIVE", new BigDecimal("2"), idFor(a, 8)));
    assertThat(wrong.getResponse().getStatus()).isEqualTo(400);
    assertThat(bucketOnHand(idFor(a, 11))).isEqualByComparingTo("10");

    // 用 DEFECTIVE 位：成功，且可售量不动。
    var ok = execute(command(UUID.randomUUID(), "DEFECTIVE", new BigDecimal("3"), idFor(a, 9)));
    assertThat(ok.getResponse().getStatus()).isEqualTo(200);
    var json = JSON.readTree(ok.getResponse().getContentAsString());
    assertThat(json.get("sellableQuantityBefore").decimalValue()).isEqualByComparingTo("0");
    assertThat(json.get("sellableQuantityAfter").decimalValue()).isEqualByComparingTo("0");
    assertThat(bucketOnHand(idFor(a, 11))).isEqualByComparingTo("7");

    Record defective =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                sql.fetchOne(
                    "select b.on_hand,q.role from stock_bucket b"
                        + " join inventory_quality_status q on q.tenant_id=b.tenant_id"
                        + " and q.id=b.quality_status_id"
                        + " where b.tenant_id=? and b.quality_status_id=? and b.location_id=?",
                    a,
                    idFor(a, 6),
                    idFor(a, 9)));
    assertThat(defective).isNotNull();
    assertThat(defective.get("on_hand", BigDecimal.class)).isEqualByComparingTo("3");
    assertThat(defective.get("role", String.class)).isEqualTo("DEFECTIVE");
  }

  @Test
  void 报废_是核销不是挪到报废bucket_货不再占库位() throws Exception {
    var result = execute(command(UUID.randomUUID(), "SCRAP", new BigDecimal("4"), null));
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    var json = JSON.readTree(result.getResponse().getContentAsString());
    // 报废没有目标 bucket。
    assertThat(json.get("targetBucketId").isNull()).isTrue();
    // 可售量不受影响。
    assertThat(json.get("sellableQuantityAfter").decimalValue()).isEqualByComparingTo("0");

    // 待检库存行减 4，余额同步减 4（守恒）。
    assertThat(bucketOnHand(idFor(a, 11))).isEqualByComparingTo("6");
    Record balance =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                sql.fetchOne(
                    "select on_hand from inventory_balance where tenant_id=? and sku_id=?"
                        + " and quality_status_id=?",
                    a,
                    idFor(a, 2),
                    idFor(a, 5)));
    assertThat(balance.get("on_hand", BigDecimal.class)).isEqualByComparingTo("6");

    // 只落一条负向分录（没有「报废 bucket」这一说）。
    List<Record> entries =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                sql.fetch(
                    "select e.on_hand_delta from inventory_entry e"
                        + " join inventory_transaction t on t.tenant_id=e.tenant_id"
                        + " and t.id=e.transaction_id"
                        + " where e.tenant_id=? and t.ref_type='RETURN_DISPOSITION'",
                    a));
    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).get("on_hand_delta", BigDecimal.class)).isEqualByComparingTo("-4");

    // 回执里报废没有目标质量状态 / 目标 bucket。
    Record receipt =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                sql.fetchOne(
                    "select target_inventory_state,target_bucket_id,target_balance_id,"
                        + "target_quality_status_id from inventory_return_disposition_execution"
                        + " where tenant_id=?",
                    a));
    assertThat(receipt.get("target_inventory_state", String.class)).isEqualTo("SCRAP");
    assertThat(receipt.get("target_bucket_id", UUID.class)).isNull();
    assertThat(receipt.get("target_balance_id", UUID.class)).isNull();
    assertThat(receipt.get("target_quality_status_id", UUID.class)).isNull();
  }

  @Test
  void 幂等_同一张处置单执行两次库存不会被动两次() throws Exception {
    UUID dispositionId = UUID.randomUUID();
    var first = execute(command(dispositionId, "SELLABLE", new BigDecimal("2"), idFor(a, 8)));
    assertThat(first.getResponse().getStatus()).isEqualTo(200);
    assertThat(bucketOnHand(idFor(a, 11))).isEqualByComparingTo("8");

    var second = execute(command(dispositionId, "SELLABLE", new BigDecimal("2"), idFor(a, 8)));
    assertThat(second.getResponse().getStatus()).isEqualTo(200);
    var json = JSON.readTree(second.getResponse().getContentAsString());
    assertThat(json.get("idempotentReplay").asBoolean()).isTrue();
    // 库存没有再动一次。
    assertThat(bucketOnHand(idFor(a, 11))).isEqualByComparingTo("8");

    long executions =
        access.run(
                actorA,
                "warehouse.inventory.read",
                () ->
                    sql.fetchOne(
                            "select count(*) from inventory_return_disposition_execution"
                                + " where tenant_id=?",
                            a)
                        .get(0, Long.class));
    assertThat(executions).isEqualTo(1);
  }

  @Test
  void 只处置待检货_可售bucket不能被再处置() throws Exception {
    // 先把待检的货转成可售，再用那张可售 bucket 处置一次。
    assertThat(execute(command(UUID.randomUUID(), "SELLABLE", new BigDecimal("2"), idFor(a, 8)))
            .getResponse().getStatus())
        .isEqualTo(200);
    UUID sellableBucket =
        access.run(
                actorA,
                "warehouse.inventory.read",
                () ->
                    sql.fetchOne(
                            "select id from stock_bucket where tenant_id=? and quality_status_id=?",
                            a,
                            idFor(a, 4))
                        .get("id", UUID.class));

    Map<String, Object> body = command(UUID.randomUUID(), "SELLABLE", new BigDecimal("1"), idFor(a, 8));
    body.put("sourceBucketId", sellableBucket.toString());
    var result = execute(body);
    assertThat(result.getResponse().getStatus()).isEqualTo(400);
    // 库存没有被动过（可售那一行还是 2）。
    Record sellable =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                sql.fetchOne(
                    "select on_hand from stock_bucket where tenant_id=? and quality_status_id=?",
                    a,
                    idFor(a, 4)));
    assertThat(sellable.get("on_hand", BigDecimal.class)).isEqualByComparingTo("2");
  }

  @Test
  void 库存不足要报错而不是把货扣成负数() throws Exception {
    var result = execute(command(UUID.randomUUID(), "SELLABLE", new BigDecimal("99"), idFor(a, 8)));
    assertThat(result.getResponse().getStatus()).isEqualTo(400);
    assertThat(bucketOnHand(idFor(a, 11))).isEqualByComparingTo("10");
  }

  @Test
  void 跨租户的库位拿不到() throws Exception {
    // tenant-b 的待检 bucket，tenant-a 的身份去处置。
    Map<String, Object> body = command(UUID.randomUUID(), "SELLABLE", new BigDecimal("1"), idFor(a, 8));
    body.put("sourceBucketId", idFor(b, 11).toString());
    var result = execute(body);
    assertThat(result.getResponse().getStatus()).isEqualTo(404);
    assertThat(bucketOnHand(b, idFor(b, 11))).isEqualByComparingTo("10");
  }

  @Test
  void 处置选项_来源只给待检_目标按质量状态给库位类型() throws Exception {
    http.perform(
            get("/warehouse/inventory/return-dispositions/options")
                .header("Authorization", "Bearer tenant-a")
                .param("skuId", idFor(a, 2).toString())
                .param("targetState", "SELLABLE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetInventoryState").value("SELLABLE"))
        .andExpect(jsonPath("$.requiredLocationType").value("STORAGE"))
        // 候选来源库位只有待检行。
        .andExpect(jsonPath("$.pendingBuckets.length()").value(1))
        .andExpect(jsonPath("$.pendingBuckets[0].bucketId").value(idFor(a, 11).toString()))
        .andExpect(jsonPath("$.pendingBuckets[0].qualityStatusCode").value("PENDING_INSPECTION"))
        // 候选目标库位只有 STORAGE 位（待检位与残次位都不是）。
        .andExpect(jsonPath("$.targetLocations.length()").value(1))
        .andExpect(jsonPath("$.targetLocations[0].locationId").value(idFor(a, 8).toString()));

    http.perform(
            get("/warehouse/inventory/return-dispositions/options")
                .header("Authorization", "Bearer tenant-a")
                .param("skuId", idFor(a, 2).toString())
                .param("targetState", "DEFECTIVE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requiredLocationType").value("DEFECTIVE"))
        .andExpect(jsonPath("$.targetLocations.length()").value(1))
        .andExpect(jsonPath("$.targetLocations[0].locationId").value(idFor(a, 9).toString()));

    // 报废没有目标库位。
    http.perform(
            get("/warehouse/inventory/return-dispositions/options")
                .header("Authorization", "Bearer tenant-a")
                .param("skuId", idFor(a, 2).toString())
                .param("targetState", "SCRAP"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetLocations.length()").value(0));
  }

  @Test
  void 能力声明如实报下游归属() throws Exception {
    http.perform(
            get("/warehouse/inventory/return-dispositions/capabilities")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.blocks.length()").value(org.hamcrest.Matchers.greaterThan(3)))
        .andExpect(jsonPath("$.boundaries.length()").value(org.hamcrest.Matchers.greaterThan(2)));
  }
}
