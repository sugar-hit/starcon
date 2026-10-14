package com.starcon.warehouse;

import static com.starcon.warehouse.PickTaskModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 8.3.2.1 拣货任务。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>本测试钉住十件「错了会误导人」的事：
 *
 * <ul>
 *   <li><b>路径是真的路径，不是按 id 排。</b>库位树里 A 段下有 A-1/A-2，B 段下有 B-1，
 *       拣货序号必须父先于子（A-1 在 B-1 之前），哪怕 uuid 顺序完全相反。
 *   <li><b>同一 SKU 收敛到尽量少的库位</b>（8.5.2.1「SKU 散在多库位」的落地）：
 *       一个库位够拣时就不拆段；不够时才拆，且 {@code skuLocationCount} 是
 *       <b>真实跨库位计数</b>（不是写死的 1）。
 *   <li><b>不可拣库位是最后退路，且必须被标出来</b>（8.5.2.1「高频货在不可拣库位」的
 *       落地）：有可拣来源就绝不碰待检位；实在没有才退，且 {@code nonPickableSource=true}。
 *   <li><b>拣货搬库位、balance 不动、预占不释放。</b>同一 balance 的两侧 bucket 一增一减，
 *       {@code inventory_balance} 与 {@code inventory_entry} 一个字都不改 ——
 *       「拣货完不等于已发货」在这一层是数据事实。
 *   <li><b>实际库位允许与计划不同</b>（分配是建议、现场是事实），但「那个库位没有这件货」
 *       与「拣超过计划」必须当场拒。
 *   <li><b>序列号三校验</b>：不在库 / SKU 不符 / 不在本次实拣的库位上，全部拒。
 *   <li><b>缺货必须写缺几件</b>，其余四类不许写数量；处置只能写一次。
 *   <li><b>完成拣货 ≠ 已发货</b>：出库单只到「待复核」，SHIPPED 由 8.3.4.1 写。
 *       没拣齐又没报缺货的段，完成时当场拒（不提供「强制完成」）。
 *   <li><b>DB 级不变量</b>：终态任务明细不可改、状态非法跃迁、实拣记录 append-only、
 *       非法流水号被 23514 拒 —— 全部用<b>超级用户直连 SQL</b>证明绕开服务层也过不去。
 *   <li><b>租户隔离三断言</b>实跑 + 六张表逐张断言 FORCE RLS + 时刻写回精确到瞬时。
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class PickTaskIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      List.of("warehouse.inventory.read", "warehouse.inventory.update");

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-pick-task-" + tenant + "-" + ordinal).getBytes());
  }

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenant, "Test", UUID.randomUUID(), null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(), grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try {
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
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
            byte[] body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only", actor(user, a, List.of(grant("ALL", List.of("warehouse.inventory.read")))));
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

  @Autowired PickTaskService service;
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
          "truncate pick_task_serial, pick_task_pick, pick_task_exception, pick_task_line,"
              + " pick_task_event, pick_task, outbound_pick_request, outbound_order_event,"
              + " outbound_order_line, outbound_order, inventory_serial, stock_bucket,"
              + " inventory_balance, warehouse_location, inventory_lot,"
              + " inventory_quality_status, legal_entity_ref, sku_ref, warehouse cascade");
      s.execute("truncate inventory_entry, inventory_transaction restart identity cascade");
    }
    for (var tenant : List.of(a, b)) {
      access.run(
          actorFor(tenant),
          "warehouse.inventory.read",
          () -> {
            sql.execute(
                "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 1), tenant, "LE-1", "主体一");
            for (int i = 2; i <= 8; i++) {
              sql.execute(
                  "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                  idFor(tenant, i), tenant, "SKU-" + (i - 1), "商品" + (i - 1));
            }
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 9), tenant, "SELLABLE", "可售", "SELLABLE");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 10), tenant, "PENDING", "待检", "PENDING_INSPECTION");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,kind,country_code,timezone,"
                    + "default_legal_entity_id,status) values(?,?,?,?,?,?,?,?,?)",
                idFor(tenant, 11), tenant, "WH-" + (tenant.equals(a) ? "A" : "B"), "本租仓库",
                "LOCAL", tenant.equals(a) ? "CN" : "US", "Asia/Shanghai", idFor(tenant, 1),
                "ACTIVE");
            return null;
          });
    }
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  UUID wh(UUID tenant) { return idFor(tenant, 11); }
  UUID sku(UUID tenant, int n) { return idFor(tenant, 1 + n); }
  UUID sellable(UUID tenant) { return idFor(tenant, 9); }

  <T> T as(UUID tenant, java.util.function.Supplier<T> work) {
    return access.run(actorFor(tenant), "warehouse.inventory.read", work);
  }

  // ================================================================ 夹具

  UUID location(UUID tenant, String code, String type, boolean pickable, UUID parent) {
    return as(
        tenant,
        () -> {
          UUID id = UUID.randomUUID();
          sql.execute(
              "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,status,"
                  + "pickable,parent_id) values(?,?,?,?,?,?,'ACTIVE',?,?)",
              id, tenant, wh(tenant), code, code + " 位", type, pickable, parent);
          return id;
        });
  }

  void bucket(UUID tenant, UUID loc, UUID skuId, String onHand, String reserved) {
    as(
        tenant,
        () -> {
          UUID balance;
          Record found =
              sql.fetchOne(
                  "select id from inventory_balance where tenant_id"
                      + " = current_setting('app.current_tenant', true)::uuid"
                      + " and sku_id = ? and warehouse_id = ?",
                  skuId, wh(tenant));
          if (found != null) {
            balance = found.get("id", UUID.class);
            // 复用既有 balance 时把在手与预占一起累加，保持 V2 §4.4 的延迟守恒式
            // （Σ bucket == balance）成立 —— 否则夹具本身就造出一条解释不了的差额。
            sql.execute(
                "update inventory_balance set on_hand=on_hand+cast(? as numeric),"
                    + " reserved=reserved+cast(? as numeric)"
                    + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                    + " and id = ?",
                onHand, reserved, balance);
          } else {
            balance = UUID.randomUUID();
            sql.execute(
                "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                    + "quality_status_id,on_hand,reserved)"
                    + " values(?,?,?,?,?,?,cast(? as numeric),cast(? as numeric))",
                balance, tenant, skuId, wh(tenant), idFor(tenant, 1), sellable(tenant),
                onHand, reserved);
          }
          sql.execute(
              "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,"
                  + "location_id,lot_id,quality_status_id,balance_id,on_hand,reserved,"
                  + "last_movement_at)"
                  + " values(?,?,?,?,?,?,null,?,?,cast(? as numeric),cast(? as numeric),"
                  + "?::timestamptz)",
              UUID.randomUUID(), tenant, idFor(tenant, 1), skuId, wh(tenant), loc,
              sellable(tenant), balance, onHand, reserved, OffsetDateTime.now());
          return null;
        });
  }

  UUID serial(UUID tenant, UUID skuId, String number, UUID loc) {
    return as(
        tenant,
        () -> {
          UUID id = UUID.randomUUID();
          sql.execute(
              "insert into inventory_serial(id,tenant_id,serial_number,sku_id,status,"
                  + "current_location_id) values(?,?,?,?,'IN_STOCK',?)",
              id, tenant, number, skuId, loc);
          return id;
        });
  }

  /** 建一张出库单（明细 + 交接请求 + 状态推到拣货中），模拟 8.3.1.1 已交付的那一步。 */
  UUID outbound(UUID tenant, String code, UUID... skuIds) {
    // sku_code 必须在进事务前取好：access.run 里再嵌一层 run 会把租户上下文
    // 绑定两次，取到的可能是外层那一条连接。
    var codes = new java.util.LinkedHashMap<UUID, String>();
    for (UUID skuId : skuIds) codes.put(skuId, asCode(tenant, skuId));
    return as(
        tenant,
        () -> {
          UUID orderId = UUID.randomUUID();
          sql.execute(
              "insert into outbound_order(id,tenant_id,order_code,source_type,source_external_ref,"
                  + "warehouse_id,status,created_by_user_id) values(?,?,?,'SALES',?,?,'PENDING_PICKING',?)",
              orderId, tenant, code, "SO-" + code, wh(tenant), user);
          BigDecimal total = BigDecimal.ZERO;
          int lineNo = 0;
          for (UUID skuId : skuIds) {
            lineNo++;
            sql.execute(
                "insert into outbound_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,"
                    + "sku_name,quantity) values(?,?,?,?,?,?,?,'10')",
                UUID.randomUUID(), tenant, orderId, lineNo, skuId,
                codes.get(skuId), "商品" + lineNo);
            total = total.add(new BigDecimal("10"));
          }
          sql.execute(
              "insert into outbound_pick_request(id,tenant_id,order_id,line_count,"
                  + "total_piece_count,requested_by_user_id,requested_at)"
                  + " values(?,?,?,?,?,?,?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, skuIds.length, total, user,
              OffsetDateTime.now());
          // V72 的状态机允许 待拣 → 拣货中，触发器不会拦（与 8.3.1.1 走的是同一条边）。
          sql.execute(
              "update outbound_order set status='PICKING' where tenant_id=? and id=?",
              tenant, orderId);
          return orderId;
        });
  }

  String asCode(UUID tenant, UUID skuId) {
    // 必须自带租户上下文：这条查询用 current_setting('app.current_tenant') 取租户，
    // 在事务外跑会拿到空串（PG 直接报 invalid input syntax for type uuid）。
    return as(
        tenant,
        () ->
            sql.fetchOne(
                    "select code from sku_ref where tenant_id"
                        + " = current_setting('app.current_tenant', true)::uuid and id = ?",
                    skuId)
                .get("code", String.class));
  }

  PickTaskCreated generate(UUID tenant, UUID orderId) {
    return asWriteValue(
        tenant, () -> service.generate(new PickTaskGenerateInput(orderId, null, null), user));
  }

  <T> T asWriteValue(UUID tenant, java.util.function.Supplier<T> work) {
    return access.run(actorFor(tenant), "warehouse.inventory.update", work);
  }

  /** 两条拣货位（A-1/A-2 在 A 段下，B-1 在 B 段下）+ 一个待检位（不可拣）+ 一个发货暂存位。 */
  record Layout(UUID staging, UUID a1, UUID a2, UUID b1, UUID quarantine) {}

  Layout layout(UUID tenant) {
    UUID zoneA = location(tenant, "A", "STORAGE", true, null);
    UUID zoneB = location(tenant, "B", "STORAGE", true, null);
    UUID a1 = location(tenant, "A-1", "STORAGE", true, zoneA);
    UUID a2 = location(tenant, "A-2", "STORAGE", true, zoneA);
    UUID b1 = location(tenant, "B-1", "STORAGE", true, zoneB);
    UUID quarantine = location(tenant, "Q-1", "QUARANTINE", false, null);
    UUID staging = location(tenant, "OUT", "SHIPPING", true, null);
    return new Layout(staging, a1, a2, b1, quarantine);
  }

  BigDecimal onHand(UUID tenant, UUID loc, UUID skuId) {
    return as(
        tenant,
        () -> {
          Record r =
              sql.fetchOne(
                  "select coalesce(sum(on_hand),0) as oh from stock_bucket"
                      + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                      + " and location_id = ? and sku_id = ?",
                  loc, skuId);
          return r.get("oh", BigDecimal.class);
        });
  }

  BigDecimal reservedAt(UUID tenant, UUID loc, UUID skuId) {
    return as(
        tenant,
        () -> {
          Record r =
              sql.fetchOne(
                  "select coalesce(sum(reserved),0) as rs from stock_bucket"
                      + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                      + " and location_id = ? and sku_id = ?",
                  loc, skuId);
          return r.get("rs", BigDecimal.class);
        });
  }

  BigDecimal balanceOnHand(UUID tenant, UUID skuId) {
    return as(
        tenant,
        () ->
            sql.fetchOne(
                    "select on_hand from inventory_balance"
                        + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                        + " and sku_id = ? and warehouse_id = ?",
                    skuId, wh(tenant))
                .get("on_hand", BigDecimal.class));
  }

  long inventoryEntryCount(UUID tenant) {
    return as(
        tenant,
        () ->
            sql.fetchOne(
                    "select count(*) as c from inventory_entry"
                        + " where tenant_id = current_setting('app.current_tenant', true)::uuid")
                .get("c", Long.class));
  }

  /** 用超级用户直连跑一段 SQL —— 用来证明「绕开服务层也过不去」。 */
  void superuserSql(String... statements) throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      for (var statement : statements) s.execute(statement);
    }
  }

  // ================================================================ 能力声明

  @Test
  void capabilitiesSayPickingIsNotShippingAndNameTheUndeliveredNeighbours() {
    var caps = asWriteValue(a, service::capabilities);
    assertThat(caps.refType()).isEqualTo("PICK_TASK");
    assertThat(caps.pathOptimizationLive()).isTrue();
    // 「拣货完不等于已发货」在能力声明里就说死，别让调用方自己推。
    assertThat(caps.balanceUnchangedByPicking()).isTrue();
    assertThat(caps.reservationRetained()).isTrue();
    assertThat(caps.inventoryNote()).contains("不写 inventory_transaction / inventory_entry");
    // 8.3.2.2 波次已交付（V113 + /warehouse/pick-waves）：合并是**波次那一层**的排班，
    // 本模块的工单粒度不变 —— 仍然一张出库单一张任务。
    assertThat(caps.waveAvailable()).isTrue();
    assertThat(caps.waveOwnerModule()).isEqualTo("8.3.2.2 波次与合并拣货（已交付）");
    // 未交付的邻居仍然如实报 false + 归属模块，不假装存在。
    assertThat(caps.recheckAvailable()).isFalse();
    assertThat(caps.recheckOwnerModule()).isEqualTo("8.3.3.1 复核扫描");
    // 四态里没有「已发货」。
    assertThat(caps.statuses()).containsExactly("CREATED", "IN_PROGRESS", "COMPLETED", "CANCELLED");
    assertThat(caps.exceptionKinds()).hasSize(5);
    assertThat(caps.pathBasis()).contains("库位树层级路径");
  }

  // ================================================================ 生成

  @Test
  void generationWalksTheLocationTreeParentFirstNotUuidOrder() {
    Layout l = layout(a);
    bucket(a, l.a1(), sku(a, 1), "10", "0");
    bucket(a, l.b1(), sku(a, 2), "10", "0");
    UUID order = outbound(a, "OB-1", sku(a, 1), sku(a, 2));

    var created = generate(a, order);
    assertThat(created.status()).isEqualTo("CREATED");
    assertThat(created.lineCount()).isEqualTo(2);

    var lines = linesOf(a, created.id());
    // 关键：A-1（A 段下）在 B-1（B 段下）之前 —— 与 uuid 顺序无关，取决于层级路径。
    assertThat(lines).extracting(PickTaskLine::locationPath).containsExactly("A|A-1", "B|B-1");
    assertThat(lines).extracting(PickTaskLine::sequenceNo).containsExactly(1, 2);
    assertThat(lines).allSatisfy(line -> assertThat(line.sourceHasStock()).isTrue());
    assertThat(lines).extracting(PickTaskLine::locationDepth).containsExactly(1, 1);
  }

  @Test
  void oneLocationEnoughPicksInOneStopInsteadOfSplittingAcrossLocations() {
    Layout l = layout(a);
    // 同一 SKU 散在两个库位，但一个库位就够 10 件 —— 不该拆成两段。
    bucket(a, l.a1(), sku(a, 1), "10", "0");
    bucket(a, l.b1(), sku(a, 1), "4", "0");
    UUID order = outbound(a, "OB-2", sku(a, 1));

    var created = generate(a, order);
    var lines = linesOf(a, created.id());
    assertThat(lines).hasSize(1);
    assertThat(lines.get(0).sourceLocationId()).isEqualTo(l.a1());
    // 「散在几个库位」是真数：2（不是写死的 1）。
    assertThat(lines.get(0).skuLocationCount()).isEqualTo(2);
    assertThat(lines.get(0).plannedQuantity()).isEqualByComparingTo("10");
  }

  @Test
  void splitsIntoTwoStopsOnlyWhenOneLocationCannotCoverTheLine() {
    Layout l = layout(a);
    bucket(a, l.a1(), sku(a, 1), "4", "0");
    bucket(a, l.b1(), sku(a, 1), "10", "0");
    UUID order = outbound(a, "OB-3", sku(a, 1));

    var created = generate(a, order);
    var lines = linesOf(a, created.id());
    assertThat(lines).hasSize(2);
    assertThat(lines).extracting(PickTaskLine::sourceLocationId)
        .containsExactly(l.a1(), l.b1());
    // 先拣够的那一段 = min(4, 10)，剩下 6 段在 B-1：路径顺序决定谁先，不是谁多谁先。
    assertThat(lines).extracting(PickTaskLine::plannedQuantity)
        .satisfiesExactly(
            v -> assertThat(v).isEqualByComparingTo("4"), v -> assertThat(v).isEqualByComparingTo("6"));
    var report = report(a, created.id());
    assertThat(report.stops()).hasSize(2);
    assertThat(report.dispersionSkuCount()).isEqualTo(2);
    assertThat(report.splitLineCount()).isEqualTo(1);
  }

  @Test
  void nonPickableLocationIsTheLastResortAndGetsFlagged() {
    Layout l = layout(a);
    // 待检位上有货、可拣位上没有：只能退到待检位，且必须被标出来。
    bucket(a, l.quarantine(), sku(a, 1), "10", "0");
    UUID order = outbound(a, "OB-4", sku(a, 1));

    var created = generate(a, order);
    var lines = linesOf(a, created.id());
    assertThat(lines).hasSize(1);
    assertThat(lines.get(0).nonPickableSource()).isTrue();
    assertThat(lines.get(0).sourcePickable()).isFalse();
    assertThat(created.nonPickableLineCount()).isEqualTo(1);
    assertThat(report(a, created.id()).nonPickableLineCount()).isEqualTo(1);
  }

  @Test
  void pickableSourceWinsEvenWhenNonPickableHoldsMoreStock() {
    Layout l = layout(a);
    bucket(a, l.quarantine(), sku(a, 1), "100", "0");
    bucket(a, l.a1(), sku(a, 1), "10", "0");
    UUID order = outbound(a, "OB-5", sku(a, 1));

    var lines = linesOf(a, generate(a, order).id());
    assertThat(lines).hasSize(1);
    assertThat(lines.get(0).sourceLocationId()).isEqualTo(l.a1());
    assertThat(lines.get(0).nonPickableSource()).isFalse();
  }

  @Test
  void aLineWithNoStockAtAllBecomesAVisibleUnallocatedSegment() {
    Layout l = layout(a);
    bucket(a, l.a1(), sku(a, 1), "10", "0");
    UUID order = outbound(a, "OB-6", sku(a, 1), sku(a, 2));

    var lines = linesOf(a, generate(a, order).id());
    var unallocated =
        lines.stream().filter(line -> !line.sourceHasStock()).toList();
    assertThat(unallocated).hasSize(1);
    assertThat(unallocated.get(0).skuCode()).isEqualTo("SKU-2");
    // 计划量仍然是 10：作业台要看见「这一行要 10 件但没货」，而不是这一行消失。
    assertThat(unallocated.get(0).plannedQuantity()).isEqualByComparingTo("10");
    assertThat(unallocated.get(0).shortfall()).isEqualByComparingTo("10");
  }

  @Test
  void generationRefusesWithoutAPickRequestAndWithoutAStagingLocation() {
    Layout l = layout(a);
    bucket(a, l.a1(), sku(a, 1), "10", "0");
    // 没有交接请求
    as(
        a,
        () -> {
          UUID orderId = UUID.randomUUID();
          sql.execute(
              "insert into outbound_order(id,tenant_id,order_code,source_type,source_external_ref,"
                  + "warehouse_id,status,created_by_user_id)"
                  + " values(?,?,?,'SALES',?,?,'PICKING',?)",
              orderId, a, "OB-X", "SO-X", wh(a), user);
          return orderId;
        });
    var noRequest = as(a, () -> sql.fetchOne(
        "select id from outbound_order where tenant_id = current_setting('app.current_tenant', true)"
            + "::uuid and order_code='OB-X'")).get("id", UUID.class);
    assertThat(catchThrowable(() -> generate(a, noRequest)))
        .hasMessageContaining("还没有拣货交接请求");

    // 有交接请求但没有发货暂存位当打包区
    as(
        a,
        () -> {
          sql.execute("update warehouse_location set type='STORAGE' where id = ?", l.staging());
          return null;
        });
    UUID order = outbound(a, "OB-Y", sku(a, 1));
    assertThat(catchThrowable(() -> generate(a, order)))
        .hasMessageContaining("没有启用的发货暂存库位");
  }

  @Test
  void ambiguousStagingLocationIsRefusedInsteadOfGuessed() {
    Layout l = layout(a);
    bucket(a, l.a1(), sku(a, 1), "10", "0");
    location(a, "OUT-2", "SHIPPING", true, null);
    UUID order = outbound(a, "OB-7", sku(a, 1));
    assertThat(catchThrowable(() -> generate(a, order)))
        .hasMessageContaining("不猜哪个是打包区");
  }

  @Test
  void oneTaskPerOutboundOrder() {
    Layout l = layout(a);
    bucket(a, l.a1(), sku(a, 1), "10", "0");
    UUID order = outbound(a, "OB-8", sku(a, 1));
    var created = generate(a, order);
    assertThat(catchThrowable(() -> generate(a, order)))
        .hasMessageContaining("一张出库单只生成一次拣货任务");
    assertThat(created.taskCode()).startsWith("PT-");
  }

  // ================================================================ 接单与实拣

  @Test
  void pickingMovesStockToStagingWithoutTouchingBalanceOrEntries() {
    Layout l = layout(a);
    bucket(a, l.a1(), sku(a, 1), "10", "4");
    UUID order = outbound(a, "OB-9", sku(a, 1));
    var created = generate(a, order);
    asWriteValue(a, () -> service.assign(created.id(),
        new PickTaskAssignInput(user, "PDA 接单"), user));

    BigDecimal balanceBefore = balanceOnHand(a, sku(a, 1));
    long entriesBefore = inventoryEntryCount(a);
    assertThat(balanceBefore).isEqualByComparingTo("10");

    var pick = asWriteValue(a, () -> service.recordPick(created.id(),
        new PickTaskPickInput(linesOf(a, created.id()).get(0).id(), l.a1(), null,
            new BigDecimal("6"), List.of(), "2026-10-14T09:30:00+08:00", "先拣 6 件"), user));

    assertThat(pick.quantity()).isEqualByComparingTo("6");
    assertThat(pick.locationId()).isEqualTo(l.a1());
    // 源库位：on_hand 减 6；预占**跟着货走**，搬 min(4, 6) = 4 件。
    assertThat(onHand(a, l.a1(), sku(a, 1))).isEqualByComparingTo("4");
    assertThat(reservedAt(a, l.a1(), sku(a, 1))).isEqualByComparingTo("0");
    // 打包区：货与预占都在这里了 —— 「拣货只是把货从库位移到打包区」。
    assertThat(onHand(a, l.staging(), sku(a, 1))).isEqualByComparingTo("6");
    assertThat(reservedAt(a, l.staging(), sku(a, 1))).isEqualByComparingTo("4");
    // balance 一个字都没改，inventory_entry 一条都没写。
    assertThat(balanceOnHand(a, sku(a, 1))).isEqualByComparingTo("10");
    assertThat(inventoryEntryCount(a)).isEqualTo(entriesBefore);
  }

  @Test
  void pickedAtKeepsItsOffsetInsteadOfSlidingMinusEightHours() {
    Layout l = layout(a);
    bucket(a, l.a1(), sku(a, 1), "10", "0");
    var created = generate(a, outbound(a, "OB-10", sku(a, 1)));
    asWriteValue(a, () -> service.assign(created.id(),
        new PickTaskAssignInput(user, null), user));
    var pick = asWriteValue(a, () -> service.recordPick(created.id(),
        new PickTaskPickInput(linesOf(a, created.id()).get(0).id(), l.a1(), null,
            new BigDecimal("1"), List.of(), "2026-10-14T09:30:00+08:00", null), user));

    // 写回的值：东八区 09:30 存进来必须是同一瞬时，不是 01:30（那是 -28800s 的经典坑）。
    assertThat(pick.pickedAt().toInstant())
        .isEqualTo(OffsetDateTime.parse("2026-10-14T09:30:00+08:00").toInstant());
    assertThat(pick.pickedAt().getHour()).isEqualTo(9);
  }

  @Test
  void pickingIsRefusedWhenTheActualLocationHasNoSuchStock() {
    Layout l = layout(a);
    bucket(a, l.a1(), sku(a, 1), "10", "0");
    var created = generate(a, outbound(a, "OB-11", sku(a, 1)));
    asWriteValue(a, () -> service.assign(created.id(),
        new PickTaskAssignInput(user, null), user));
    // B-1 上没有这件货：现场情况与分配不符也不能凭空拣。
    assertThat(catchThrowable(() -> asWriteValue(a, () -> service.recordPick(created.id(),
        new PickTaskPickInput(linesOf(a, created.id()).get(0).id(), l.b1(), null,
            new BigDecimal("1"), List.of(), null, null), user))))
        .hasMessageContaining("没有这件货的库存行");
  }

  @Test
  void overPickingIsRefusedByTheServiceAndByTheDatabase() {
    Layout l = layout(a);
    bucket(a, l.a1(), sku(a, 1), "10", "0");
    var created = generate(a, outbound(a, "OB-12", sku(a, 1)));
    asWriteValue(a, () -> service.assign(created.id(),
        new PickTaskAssignInput(user, null), user));
    var line = linesOf(a, created.id()).get(0);
    asWriteValue(a, () -> service.recordPick(created.id(),
        new PickTaskPickInput(line.id(), l.a1(), null, new BigDecimal("8"), List.of(), null, null),
        user));
    // 计划 10、已拣 8，再拣 3 就超了。
    assertThat(catchThrowable(() -> asWriteValue(a, () -> service.recordPick(created.id(),
        new PickTaskPickInput(line.id(), l.a1(), null, new BigDecimal("3"), List.of(), null, null),
        user))))
        .hasMessageContaining("超拣");

    // 绕开服务层直连 SQL：V110 的 guard_pick_task_pick_fn 照样拒。
    assertThat(catchThrowable(() -> superuserSql(
            "insert into pick_task_pick(id,tenant_id,task_id,line_id,location_id,quantity,"
                + "picked_by,picked_at) values(gen_random_uuid(),'" + a + "','" + created.id()
                + "','" + line.id() + "','" + l.a1() + "',5,'" + user
                + "',now())")))
        .hasMessageContaining("超拣");
  }

  @Test
  void pickingFromStagingItselfIsRefused() {
    Layout l = layout(a);
    bucket(a, l.a1(), sku(a, 1), "10", "0");
    var created = generate(a, outbound(a, "OB-13", sku(a, 1)));
    asWriteValue(a, () -> service.assign(created.id(),
        new PickTaskAssignInput(user, null), user));
    var line = linesOf(a, created.id()).get(0);
    assertThat(catchThrowable(() -> asWriteValue(a, () -> service.recordPick(created.id(),
        new PickTaskPickInput(line.id(), l.staging(), null, new BigDecimal("1"), List.of(), null,
            null), user))))
        .hasMessageContaining("没有这件货的库存行");
  }

  @Test
  void serialNumbersMoveWithTheGoodsAndAreCheckedThreeWays() {
    Layout l = layout(a);
    bucket(a, l.a1(), sku(a, 1), "10", "0");
    UUID s1 = serial(a, sku(a, 1), "SN-1", l.a1());
    UUID s2 = serial(a, sku(a, 1), "SN-2", l.a1());
    UUID elsewhere = serial(a, sku(a, 1), "SN-3", l.b1());
    UUID otherSkuSerial = serial(a, sku(a, 2), "SN-9", l.a1());

    var created = generate(a, outbound(a, "OB-14", sku(a, 1)));
    asWriteValue(a, () -> service.assign(created.id(),
        new PickTaskAssignInput(user, null), user));
    var line = linesOf(a, created.id()).get(0);

    var pick = asWriteValue(a, () -> service.recordPick(created.id(),
        new PickTaskPickInput(line.id(), l.a1(), null, new BigDecimal("2"), List.of(s1, s2),
            null, "逐件核对"), user));
    assertThat(pick.serialCount()).isEqualTo(2);
    assertThat(pick.serials()).extracting(PickTaskSerialRow::serialNumber)
        .containsExactly("SN-1", "SN-2");
    assertThat(serialLocation(a, s1)).isEqualTo(l.staging());
    assertThat(serialLocation(a, s2)).isEqualTo(l.staging());
    // status 保持 IN_STOCK：货还在仓里，只是已经不在拣货位上了。
    assertThat(serialStatus(a, s1)).isEqualTo("IN_STOCK");

    // 序列号条数与数量对不上（逐件核对的含义就是「一件一号」）。
    bucket(a, l.b1(), sku(a, 1), "5", "0");
    assertThat(catchThrowable(() -> asWriteValue(a, () -> service.recordPick(created.id(),
        new PickTaskPickInput(line.id(), l.a1(), null, new BigDecimal("1"),
            List.of(elsewhere, otherSkuSerial), null, null), user))))
        .hasMessageContaining("序列号条数");

    // 不在本次实拣库位上的号：号挂在 B-1，我们却从 A-1 把它记成被拿走了。
    assertThat(catchThrowable(() -> asWriteValue(a, () -> service.recordPick(created.id(),
        new PickTaskPickInput(line.id(), l.a1(), null, new BigDecimal("1"), List.of(elsewhere),
            null, null), user))))
        .hasMessageContaining("不在本次实拣的库位上");

    // 绑定的 SKU 与拣货行不符（序列号与 SKU 的绑定是永久的）。
    assertThat(catchThrowable(() -> asWriteValue(a, () -> service.recordPick(created.id(),
        new PickTaskPickInput(line.id(), l.a1(), null, new BigDecimal("1"),
            List.of(otherSkuSerial), null, null), user))))
        .hasMessageContaining("绑定的 SKU 与拣货行不符");

    // 不是「在库」的号也拣不走：把 SN-1 置为已售（V58 要求同时清空位置）。
    as(
        a,
        () -> {
          sql.execute("update inventory_serial set status='SOLD', current_location_id=null"
              + " where id=?", s1);
          return null;
        });
    assertThat(catchThrowable(() -> asWriteValue(a, () -> service.recordPick(created.id(),
        new PickTaskPickInput(line.id(), l.a1(), null, new BigDecimal("1"), List.of(s1), null,
            null), user))))
        .hasMessageContaining("只有「在库」的号能被拣走");
  }

  // ================================================================ 异常

  @Test
  void shortageMustSayHowManyAreMissing() {
    Layout l = layout(a);
    bucket(a, l.a1(), sku(a, 1), "10", "0");
    var created = generate(a, outbound(a, "OB-15", sku(a, 1)));
    asWriteValue(a, () -> service.assign(created.id(),
        new PickTaskAssignInput(user, null), user));
    var line = linesOf(a, created.id()).get(0);

    assertThat(catchThrowable(() -> asWriteValue(a, () -> service.reportException(created.id(),
        new PickTaskExceptionInput("SHORTAGE", line.id(), null, l.a1(), "库里只有 6 件", null),
        user))))
        .hasMessageContaining("缺货数量不能为空");
    // 非缺货类不许填数量：两个地方各记一遍会与实拣/计划之差对不上。
    assertThat(catchThrowable(() -> asWriteValue(a, () -> service.reportException(created.id(),
        new PickTaskExceptionInput("DAMAGED", line.id(), new BigDecimal("2"), l.a1(), "压坏",
            null), user))))
        .hasMessageContaining("只有缺货");
    assertThat(catchThrowable(() -> asWriteValue(a, () -> service.reportException(created.id(),
        new PickTaskExceptionInput("SHORTAGE", line.id(), new BigDecimal("2"), l.a1(), " ", null),
        user))))
        .hasMessageContaining("必须写 detail");

    var row = asWriteValue(a, () -> service.reportException(created.id(),
        new PickTaskExceptionInput("SHORTAGE", line.id(), new BigDecimal("4"), l.a1(),
            "A-1 只剩 6 件", null), user));
    assertThat(row.shortageQuantity()).isEqualByComparingTo("4");
    assertThat(row.resolved()).isFalse();
    assertThat(row.detail()).isEqualTo("A-1 只剩 6 件");
  }

  @Test
  void exceptionResolutionIsWrittenExactlyOnce() {
    Layout l = layout(a);
    bucket(a, l.a1(), sku(a, 1), "10", "0");
    var created = generate(a, outbound(a, "OB-16", sku(a, 1)));
    asWriteValue(a, () -> service.assign(created.id(),
        new PickTaskAssignInput(user, null), user));
    var line = linesOf(a, created.id()).get(0);
    var ex =
        asWriteValue(a, () -> service.reportException(created.id(),
            new PickTaskExceptionInput("SHORTAGE", line.id(), new BigDecimal("4"), l.a1(), "缺 4 件",
                null), user));

    assertThat(catchThrowable(() -> asWriteValue(a, () -> service.resolveException(created.id(),
        ex.id(), new PickTaskResolveInput("REPLACED", " "), user))))
        .hasMessageContaining("必须写 note");
    var resolved = asWriteValue(a, () -> service.resolveException(created.id(), ex.id(),
        new PickTaskResolveInput("REPLACED", "从 B-1 补齐"), user));
    assertThat(resolved.resolved()).isTrue();
    assertThat(resolved.resolution()).isEqualTo("REPLACED");
    assertThat(resolved.resolveNote()).isEqualTo("从 B-1 补齐");

    assertThat(catchThrowable(() -> asWriteValue(a, () -> service.resolveException(created.id(),
        ex.id(), new PickTaskResolveInput("IGNORED", "算了"), user))))
        .hasMessageContaining("已经处理过");
    // DB 层同样只让写一次（23514）。
    assertThat(catchThrowable(() -> superuserSql(
            "update pick_task_exception set resolved_at=now() where id='" + ex.id() + "'")))
        .hasMessageContaining("处理结论不可再改");
  }

  // ================================================================ 完成

  @Test
  void completeRequiresEveryLinePickedOrReportedShort() {
    Layout l = layout(a);
    bucket(a, l.a1(), sku(a, 1), "10", "0");
    var created = generate(a, outbound(a, "OB-17", sku(a, 1)));
    asWriteValue(a, () -> service.assign(created.id(),
        new PickTaskAssignInput(user, null), user));
    var line = linesOf(a, created.id()).get(0);
    asWriteValue(a, () -> service.recordPick(created.id(),
        new PickTaskPickInput(line.id(), l.a1(), null, new BigDecimal("6"), List.of(), null, null),
        user));

    // 6/10，既没拣齐也没报缺货 —— 不提供「强制完成」。
    assertThat(catchThrowable(() -> asWriteValue(a, () -> service.complete(created.id(), user))))
        .hasMessageContaining("没拣齐、也没按缺货登记异常");

    asWriteValue(a, () -> service.reportException(created.id(),
        new PickTaskExceptionInput("SHORTAGE", line.id(), new BigDecimal("4"), l.a1(), "只有 6 件",
            null), user));
    var detail = asWriteValue(a, () -> service.complete(created.id(), user));
    assertThat(detail.task().status()).isEqualTo("COMPLETED");
    // 拣货完 ≠ 已发货：出库单只到「待复核」。
    assertThat(detail.outboundOrderStatus()).isEqualTo("PENDING_RECHECK");
    assertThat(orderStatus(a, created.id())).isEqualTo("PENDING_RECHECK");
    assertThat(detail.lines().get(0).lineStatus()).isEqualTo(PickTaskLineStatus.SHORT);
  }

  @Test
  void fullyPickedLineIsMarkedPickedAndProgressIsDerivedNotStored() {
    Layout l = layout(a);
    bucket(a, l.a1(), sku(a, 1), "10", "0");
    var created = generate(a, outbound(a, "OB-18", sku(a, 1)));
    asWriteValue(a, () -> service.assign(created.id(),
        new PickTaskAssignInput(user, null), user));
    var line = linesOf(a, created.id()).get(0);
    asWriteValue(a, () -> service.recordPick(created.id(),
        new PickTaskPickInput(line.id(), l.a1(), null, new BigDecimal("10"), List.of(), null, null),
        user));

    var detail = asWriteValue(a, () -> service.complete(created.id(), user));
    assertThat(detail.task().status()).isEqualTo("COMPLETED");
    assertThat(detail.task().fullyPicked()).isTrue();
    assertThat(detail.task().pickedQuantity()).isEqualByComparingTo("10");
    assertThat(detail.task().progress()).isEqualByComparingTo("1");
    assertThat(detail.lines().get(0).lineStatus()).isEqualTo(PickTaskLineStatus.PICKED);
    assertThat(detail.events()).extracting(PickTaskEventRow::eventType)
        .containsExactly("GENERATED", "ASSIGNED", "PICKED", "COMPLETED");
  }

  @Test
  void numericStaysExactThroughTheWholePick() {
    Layout l = layout(a);
    bucket(a, l.a1(), sku(a, 1), "0.300000", "0");
    var created = generate(a, outbound(a, "OB-19", sku(a, 1)));
    asWriteValue(a, () -> service.assign(created.id(),
        new PickTaskAssignInput(user, null), user));
    asWriteValue(a, () -> service.recordPick(created.id(),
        new PickTaskPickInput(linesOf(a, created.id()).get(0).id(), l.a1(), null,
            new BigDecimal("0.1"), List.of(), null, null), user));
    // 0.1 读回必须是 0.1，不能是 0.1000000000000000055511151231257827…（浮点的老毛病）。
    assertThat(onHand(a, l.a1(), sku(a, 1)).stripTrailingZeros())
        .isEqualByComparingTo("0.2");
    assertThat(onHand(a, l.staging(), sku(a, 1)).stripTrailingZeros())
        .isEqualByComparingTo("0.1");
  }

  // ================================================================ 取消与重开

  @Test
  void cancelNeedsAReasonAndLeavesStockUntouched() {
    Layout l = layout(a);
    bucket(a, l.a1(), sku(a, 1), "10", "2");
    var created = generate(a, outbound(a, "OB-20", sku(a, 1)));
    assertThat(catchThrowable(() -> asWriteValue(a, () -> service.cancel(created.id(),
        new PickTaskCancelInput(" "), user))))
        .hasMessageContaining("取消必须写原因");
    var row = asWriteValue(a, () -> service.cancel(created.id(),
        new PickTaskCancelInput("单据已取消"), user));
    assertThat(row.status()).isEqualTo("CANCELLED");
    // 取消不动库存：货仍在原库位，预占不变。
    assertThat(onHand(a, l.a1(), sku(a, 1))).isEqualByComparingTo("10");
    assertThat(reservedAt(a, l.a1(), sku(a, 1))).isEqualByComparingTo("2");
    assertThat(onHand(a, l.staging(), sku(a, 1))).isEqualByComparingTo("0");
  }

  @Test
  void reopenRequiresTheOrderBackInPickingAndAccumulatesInsteadOfOverwriting() {
    Layout l = layout(a);
    bucket(a, l.a1(), sku(a, 1), "20", "0");
    var created = generate(a, outbound(a, "OB-21", sku(a, 1)));
    asWriteValue(a, () -> service.assign(created.id(),
        new PickTaskAssignInput(user, null), user));
    var line = linesOf(a, created.id()).get(0);
    // 拣 6 件、按缺货报 4 件才允许提交复核（没有「强制完成」）。
    asWriteValue(a, () -> service.recordPick(created.id(),
        new PickTaskPickInput(line.id(), l.a1(), null, new BigDecimal("6"), List.of(), null, null),
        user));
    asWriteValue(a, () -> service.reportException(created.id(),
        new PickTaskExceptionInput("SHORTAGE", line.id(), new BigDecimal("4"), l.a1(), "库里只有 6 件",
            null), user));
    asWriteValue(a, () -> service.complete(created.id(), user));

    // 出库单还没被复核退回 → 重开不成立。
    assertThat(catchThrowable(() -> asWriteValue(a, () -> service.reopen(created.id(),
        new PickTaskReopenInput("复核退回", null), user))))
        .hasMessageContaining("不是「拣货中」");
    // 空原因不收。
    assertThat(catchThrowable(() -> {
      as(a, () -> {
        sql.execute(
            "update outbound_order set status='PICKING' where tenant_id=? and id=?",
            a, orderOf(a, created.id()));
        return null;
      });
      asWriteValue(a, () -> service.reopen(created.id(), new PickTaskReopenInput(" ", null), user));
    })).hasMessageContaining("重开必须写原因");

    var row = asWriteValue(a, () -> service.reopen(created.id(),
        new PickTaskReopenInput("复核发现拣错一件", user), user));
    assertThat(row.status()).isEqualTo("IN_PROGRESS");
    assertThat(row.reopenCount()).isEqualTo(1);
    // 实拣量**累加**：第一次拣的 6 件仍然在账上（重开不是清零重来 ——
    // 复核退回的原因恰恰就在第一次的记录里）。
    assertThat(row.pickedQuantity()).isEqualByComparingTo("6");
    assertThat(row.assigneeUserId()).isEqualTo(user);

    asWriteValue(a, () -> service.recordPick(created.id(),
        new PickTaskPickInput(line.id(), l.a1(), null, new BigDecimal("4"), List.of(), null, null),
        user));
    var line2 = linesOf(a, created.id()).get(0);
    assertThat(line2.pickedQuantity()).isEqualByComparingTo("10");
    assertThat(line2.lineStatus()).isEqualTo(PickTaskLineStatus.PICKED);
  }

  // ================================================================ DB 级不变量

  @Test
  void terminalTaskRejectsDirectSqlEditsToItsLines() throws Exception {
    Layout l = layout(a);
    bucket(a, l.a1(), sku(a, 1), "10", "0");
    var created = generate(a, outbound(a, "OB-22", sku(a, 1)));
    asWriteValue(a, () -> service.cancel(created.id(), new PickTaskCancelInput("放弃"), user));
    var lineId = linesOf(a, created.id()).get(0).id();
    // 超级用户直连：FORCE RLS 与行级守卫都绕不开。
    assertThat(catchThrowable(() -> superuserSql(
            "update pick_task_line set planned_quantity=99 where id='" + lineId + "'")))
        .hasMessageContaining("拣货明细不可再改");
  }

  @Test
  void illegalStatusJumpIsRejectedByTheDatabase() throws Exception {
    Layout l = layout(a);
    bucket(a, l.a1(), sku(a, 1), "10", "0");
    var created = generate(a, outbound(a, "OB-23", sku(a, 1)));
    // 已生成 → 已完成：V110 的状态机里没有这条边。
    assertThat(catchThrowable(() -> superuserSql(
            "update pick_task set status='COMPLETED', completed_at=now() where id='"
                + created.id() + "'")))
        .hasMessageContaining("状态非法跃迁");
  }

  @Test
  void stagingLocationMustBeAShippingLocation() throws Exception {
    Layout l = layout(a);
    bucket(a, l.a1(), sku(a, 1), "10", "0");
    var created = generate(a, outbound(a, "OB-24", sku(a, 1)));
    assertThat(catchThrowable(() -> superuserSql(
            "update pick_task set staging_location_id='" + l.a1() + "' where id='" + created.id()
                + "'")))
        .hasMessageContaining("打包区必须是发货暂存库位");
  }

  @Test
  void pickRecordsAreAppendOnly() throws Exception {
    Layout l = layout(a);
    bucket(a, l.a1(), sku(a, 1), "10", "0");
    var created = generate(a, outbound(a, "OB-25", sku(a, 1)));
    asWriteValue(a, () -> service.assign(created.id(),
        new PickTaskAssignInput(user, null), user));
    var line = linesOf(a, created.id()).get(0);
    var pick = asWriteValue(a, () -> service.recordPick(created.id(),
        new PickTaskPickInput(line.id(), l.a1(), null, new BigDecimal("1"), List.of(), null, null),
        user));
    assertThat(catchThrowable(() -> superuserSql(
            "update pick_task_pick set quantity=99 where id='" + pick.id() + "'")))
        .hasMessageContaining("不允许 UPDATE");
    assertThat(catchThrowable(() -> superuserSql(
            "delete from pick_task_event where task_id='" + created.id() + "'")))
        .hasMessageContaining("append-only");
  }

  @Test
  void allSixTablesForceRowLevelSecurity() {
    for (String table :
        List.of("pick_task", "pick_task_line", "pick_task_pick", "pick_task_serial",
            "pick_task_exception", "pick_task_event")) {
      var row =
          as(
              a,
              () ->
                  sql.fetchOne(
                      "select relrowsecurity, relforcerowsecurity from pg_class"
                          + " where relname = ?",
                      table));
      assertThat(row.get("relforcerowsecurity", Boolean.class)).as(table + " 必须 FORCE RLS")
          .isTrue();
      assertThat(row.get("relrowsecurity", Boolean.class)).as(table + " 必须 ENABLE RLS")
          .isTrue();
    }
  }

  // ================================================================ 列表

  @Test
  void listPagesFiltersAndKeepsTheSummaryGlobal() {
    Layout la = layout(a);
    Layout lb = layout(b);
    bucket(a, la.a1(), sku(a, 1), "10", "0");
    bucket(b, lb.a1(), sku(b, 1), "10", "0");
    UUID taskA = generate(a, outbound(a, "OB-26", sku(a, 1))).id();
    UUID taskB = generate(a, outbound(a, "OB-27", sku(a, 1))).id();
    UUID taskC = generate(b, outbound(b, "OB-28", sku(b, 1))).id();
    asWriteValue(a, () -> service.assign(taskA, new PickTaskAssignInput(user, null), user));

    var all = as(a, () -> service.page(wh(a), null, null, null, "CREATED_AT", 0, 50));
    assertThat(all.total()).isEqualTo(2);
    assertThat(all.items()).hasSize(2);

    var filtered = as(a, () -> service.page(wh(a), "CREATED", null, null, "CREATED_AT", 0, 50));
    assertThat(filtered.total()).isEqualTo(1);
    assertThat(filtered.items().get(0).id()).isEqualTo(taskB);
    // 汇总不受筛选影响：筛完只看得到 1 行，合计仍是全仓 2 张。
    assertThat(filtered.summary().totalCount()).isEqualTo(2);
    assertThat(filtered.summary().byStatus().get("CREATED")).isEqualTo(1L);
    assertThat(filtered.summary().byStatus().get("IN_PROGRESS")).isEqualTo(1L);
    assertThat(filtered.summary().totalPlannedQuantity()).isEqualByComparingTo("20");

    // 真分页：size=1 时只回一行，但 total 不变。
    var paged = as(a, () -> service.page(wh(a), null, null, null, "CREATED_AT", 1, 1));
    assertThat(paged.items()).hasSize(1);
    assertThat(paged.total()).isEqualTo(2);

    // 接单人筛选。
    var byAssignee = as(a, () -> service.page(wh(a), null, user, null, "CREATED_AT", 0, 50));
    assertThat(byAssignee.total()).isEqualTo(1);

    // 关键字按单号 / 出库单号都能搜到。
    assertThat(as(a, () -> service.page(wh(a), null, null, "OB-27", "CREATED_AT", 0, 50)).total())
        .isEqualTo(1);

    // 排序白名单：非法键当场 400，不接受任意列。
    assertThat(catchThrowable(() -> as(a, () -> service.page(wh(a), null, null, null, "drop", 0, 50))))
        .hasMessageContaining("排序只能是");
    // B 租户看不到 A 租户的任务，也搜不到 A 的出库单号。
    assertThat(as(b, () -> service.page(wh(b), null, null, "OB-26", "CREATED_AT", 0, 50)).total())
        .isEqualTo(0);
    assertThat(as(b, () -> service.page(null, null, null, null, "CREATED_AT", 0, 50)).total())
        .isEqualTo(1);
    assertThat(taskC).isNotNull();
  }

  // ================================================================ 多租户三断言

  @Test
  void twoTenantsCannotSeeEachOther() {
    Layout la = layout(a);
    Layout lb = layout(b);
    bucket(a, la.a1(), sku(a, 1), "10", "0");
    bucket(b, lb.a1(), sku(b, 1), "10", "0");
    var taskA = generate(a, outbound(a, "OB-29", sku(a, 1)));
    var taskB = generate(b, outbound(b, "OB-30", sku(b, 1)));

    // B 用 A 的任务 id 取详情 → 404（A 的出库单 id 在 B 的租户里也不存在）。
    assertThat(catchThrowable(() -> as(b, () -> service.detail(taskA.id()))))
        .hasMessageContaining("不存在");
    assertThat(catchThrowable(() -> as(b, () -> service.generate(
        new PickTaskGenerateInput(taskA.id(), null, null), user))))
        .hasMessageContaining("不存在");
    // B 的仓库里看不到 A 的任务。
    assertThat(as(b, () -> service.page(wh(b), null, null, null, "CREATED_AT", 0, 50)).total())
        .isEqualTo(1);
    assertThat(as(b, () -> service.detail(taskB.id())).task().id()).isEqualTo(taskB.id());
  }

  @Test
  void forgedTenantHeaderIsIgnored() throws Exception {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "10", "0");
    var task = generate(a, outbound(a, "OB-31", sku(a, 1)));

    // 伪造 X-Tenant-Id 头：代理忽略它，身份由 Authorization 解析 —— 这里连身份头也伪造，
    // 结果是 401 而不是「换成 B 的租户」。
    var forged =
        http.perform(
                get("/warehouse/pick-tasks/" + task.id())
                    .header("Authorization", "Bearer forged")
                    .header("X-Tenant-Id", b.toString()))
            .andReturn()
            .getResponse();
    assertThat(forged.getStatus()).isEqualTo(401);

    // 带上真实身份但伪造租户头：看到的仍然只有 A 自己的那一行。
    http.perform(
                get("/warehouse/pick-tasks")
                    .header("Authorization", "Bearer tenant-a")
                    .header("X-Tenant-Id", b.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].taskCode").value(task.taskCode()))
            .andExpect(jsonPath("$.items[0].outboundOrderCode").value("OB-31"));
  }

  @Test
  void pooledConnectionDoesNotLeakTenantAcrossReturns() {
    Layout la = layout(a);
    Layout lb = layout(b);
    bucket(a, la.a1(), sku(a, 1), "10", "0");
    bucket(b, lb.a1(), sku(b, 1), "10", "0");
    var taskA = generate(a, outbound(a, "OB-32", sku(a, 1)));
    var taskB = generate(b, outbound(b, "OB-33", sku(b, 1)));

    // A → B → A 三轮：连接池只有 1 条连接，每次归还后都可能被下一个人拿到。
    for (int round = 0; round < 3; round++) {
      assertThat(as(a, () -> service.page(null, null, null, null, "CREATED_AT", 0, 50)).total())
          .isEqualTo(1);
      assertThat(as(a, () -> service.page(null, null, null, null, "CREATED_AT", 0, 50)).items().get(0)
              .taskCode())
          .isEqualTo(taskA.taskCode());
      assertThat(as(b, () -> service.page(null, null, null, null, "CREATED_AT", 0, 50)).total())
          .isEqualTo(1);
      assertThat(as(b, () -> service.page(null, null, null, null, "CREATED_AT", 0, 50)).items().get(0)
              .taskCode())
          .isEqualTo(taskB.taskCode());
    }
  }

  // ================================================================ HTTP 面

  @Test
  void httpEndpointsEnforcePermissionsAndExposeTheRealContract() throws Exception {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "10", "0");
    var order = outbound(a, "OB-34", sku(a, 1));

    http.perform(get("/warehouse/pick-tasks/capabilities"))
        .andExpect(status().isUnauthorized());
    http.perform(get("/warehouse/pick-tasks/capabilities").header("Authorization",
            "Bearer no-permission"))
        .andExpect(status().isForbidden());
    http.perform(get("/warehouse/pick-tasks/capabilities")
            .header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.refType").value("PICK_TASK"))
        .andExpect(jsonPath("$.balanceUnchangedByPicking").value(true))
        .andExpect(jsonPath("$.waveAvailable").value(true))
        .andExpect(jsonPath("$.pathBasis").isNotEmpty());

    var created = generate(a, order);
    http.perform(get("/warehouse/pick-tasks/" + created.id())
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.task.status").value("CREATED"))
        .andExpect(jsonPath("$.lines[0].sequenceNo").value(1))
        .andExpect(jsonPath("$.pathReport.basis").isNotEmpty())
        .andExpect(jsonPath("$.outboundOrderStatus").value("PICKING"));

    // 读权限不能改：登记实拣要写权限。
    var lineId = linesOf(a, created.id()).get(0).id();
    http.perform(
            post("/warehouse/pick-tasks/" + created.id() + "/picks")
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content(
                    "{\"lineId\":\"" + lineId + "\",\"locationId\":\"" + la.a1()
                        + "\",\"quantity\":1}"))
        .andExpect(status().isForbidden());
  }

  // ================================================================ 内部辅助

  List<PickTaskLine> linesOf(UUID tenant, UUID taskId) {
    return as(tenant, () -> service.detail(taskId).lines());
  }

  PickTaskPathReport report(UUID tenant, UUID taskId) {
    return as(tenant, () -> service.detail(taskId).pathReport());
  }

  UUID orderOf(UUID tenant, UUID taskId) {
    return as(
        tenant,
        () ->
            sql.fetchOne(
                    "select outbound_order_id from pick_task"
                        + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                        + " and id = ?",
                    taskId)
                .get("outbound_order_id", UUID.class));
  }

  String orderStatus(UUID tenant, UUID taskId) {
    return as(
        tenant,
        () -> {
          UUID orderId = orderOf(tenant, taskId);
          return sql.fetchOne(
                  "select status from outbound_order"
                      + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                      + " and id = ?",
                  orderId)
              .get("status", String.class);
        });
  }

  UUID serialLocation(UUID tenant, UUID serialId) {
    return as(
        tenant,
        () ->
            sql.fetchOne(
                    "select current_location_id from inventory_serial"
                        + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                        + " and id = ?",
                    serialId)
                .get("current_location_id", UUID.class));
  }

  String serialStatus(UUID tenant, UUID serialId) {
    return as(
        tenant,
        () ->
            sql.fetchOne(
                    "select status from inventory_serial"
                        + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                        + " and id = ?",
                    serialId)
                .get("status", String.class));
  }
}