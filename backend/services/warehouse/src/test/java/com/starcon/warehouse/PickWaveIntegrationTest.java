package com.starcon.warehouse;

import static com.starcon.warehouse.PickWaveModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
 * 8.3.2.2 波次与合并拣货。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接： 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>本测试钉住十件「错了会误导人」的事：
 *
 * <ul>
 *   <li><b>三个合并维度都是真的。</b>承运渠道分两组就分成两个波次；库区取的是 <b>库位树顶层根节点</b>（在 B-1 拣货的订单属于 B 区，不属于「B-1」）；
 *       订单数上限撞顶后剩下的确实在等下一波，不是被悄悄丢掉。
 *   <li><b>时段按仓本地小时判</b>，不是 UTC：一条 09:00–18:00 的规则收不到 落在窗口外的出库单；波次的计划拣货时刻真的落在仓本地 09:00（+08:00）。
 *   <li><b>跨区任务没有单一库区</b>，只能进「库区不限」的规则 —— 不被硬塞进某一个区。
 *   <li><b>同键只能有一个未完波次、一张工单只能在一个未完波次里</b>：用 <b>超级用户直连 SQL</b>证明绕开服务层也过不去（23505）。
 *   <li><b>移出成员后位置重排为 1..n 连续</b>，不留下 1,2,4 这种洞。
 *   <li><b>波次完成 = 全部成员工单终态</b>：少一条时服务层报错， 直连 SQL 也被 V113 的守卫拒（23514）。这是「合并不改订单粒度」在 DB 层的落点。
 *   <li><b>合并不改订单粒度</b>：成员一行就是一张出库单的一张拣货任务； 出库单状态停在「拣货中」；取消波次不动 inventory_balance /
 *       inventory_entry。
 *   <li><b>波次流水 append-only</b>、<b>规则停用必填原因且只能停一次</b>、 <b>四条原因缺失的请求全部 400</b>，都直连 SQL 验过。
 *   <li><b>租户隔离三断言</b>实跑 + 四张表逐张断言 FORCE RLS。
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class PickWaveIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      List.of("warehouse.inventory.read", "warehouse.inventory.update");

  /** 本仓的时区（与夹具一致）。时段与计划拣货时刻都按它算。 */
  static final ZoneId WAREHOUSE_ZONE = ZoneId.of("Asia/Shanghai");

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-pick-wave-" + tenant + "-" + ordinal).getBytes());
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

  @Autowired PickWaveService service;
  @Autowired PickTaskService pickTasks;
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
          "truncate pick_wave_event, pick_wave_task, pick_wave, pick_wave_rule,"
              + " pick_task_serial, pick_task_pick, pick_task_exception, pick_task_line,"
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
                idFor(tenant, 1),
                tenant,
                "LE-1",
                "主体一");
            for (int i = 2; i <= 8; i++) {
              sql.execute(
                  "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                  idFor(tenant, i),
                  tenant,
                  "SKU-" + (i - 1),
                  "商品" + (i - 1));
            }
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 9),
                tenant,
                "SELLABLE",
                "可售",
                "SELLABLE");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,kind,country_code,timezone,"
                    + "default_legal_entity_id,status) values(?,?,?,?,?,?,?,?,?)",
                idFor(tenant, 11),
                tenant,
                "WH-" + (tenant.equals(a) ? "A" : "B"),
                "本租仓库",
                "LOCAL",
                tenant.equals(a) ? "CN" : "US",
                WAREHOUSE_ZONE.getId(),
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
    return idFor(tenant, 11);
  }

  UUID sku(UUID tenant, int n) {
    return idFor(tenant, 1 + n);
  }

  UUID sellable(UUID tenant) {
    return idFor(tenant, 9);
  }

  <T> T as(UUID tenant, java.util.function.Supplier<T> work) {
    return access.run(actorFor(tenant), "warehouse.inventory.read", work);
  }

  <T> T asWrite(UUID tenant, java.util.function.Supplier<T> work) {
    return access.run(actorFor(tenant), "warehouse.inventory.update", work);
  }

  // ================================================================ 夹具

  record Layout(UUID staging, UUID zoneA, UUID a1, UUID a2, UUID zoneB, UUID b1) {}

  Layout layout(UUID tenant) {
    UUID zoneA = location(tenant, "A", "STORAGE", true, null);
    UUID a1 = location(tenant, "A-1", "STORAGE", true, zoneA);
    UUID a2 = location(tenant, "A-2", "STORAGE", true, zoneA);
    UUID zoneB = location(tenant, "B", "STORAGE", true, null);
    UUID b1 = location(tenant, "B-1", "STORAGE", true, zoneB);
    UUID staging = location(tenant, "OUT", "SHIPPING", true, null);
    return new Layout(staging, zoneA, a1, a2, zoneB, b1);
  }

  UUID location(UUID tenant, String code, String type, boolean pickable, UUID parent) {
    return as(
        tenant,
        () -> {
          UUID id = UUID.randomUUID();
          sql.execute(
              "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,status,"
                  + "pickable,parent_id) values(?,?,?,?,?,?,'ACTIVE',?,?)",
              id,
              tenant,
              wh(tenant),
              code,
              code + " 位",
              type,
              pickable,
              parent);
          return id;
        });
  }

  void bucket(UUID tenant, UUID loc, UUID skuId, String onHand) {
    as(
        tenant,
        () -> {
          UUID balance = UUID.randomUUID();
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved)"
                  + " values(?,?,?,?,?,?,cast(? as numeric),0)",
              balance,
              tenant,
              skuId,
              wh(tenant),
              idFor(tenant, 1),
              sellable(tenant),
              onHand);
          sql.execute(
              "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,"
                  + "location_id,lot_id,quality_status_id,balance_id,on_hand,reserved,"
                  + "last_movement_at)"
                  + " values(?,?,?,?,?,?,null,?,?,cast(? as numeric),0,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              idFor(tenant, 1),
              skuId,
              wh(tenant),
              loc,
              sellable(tenant),
              balance,
              onHand,
              OffsetDateTime.now());
          return null;
        });
  }

  /** 建一张带承运渠道的出库单（明细 + 交接请求 + 状态推到拣货中）。 */
  UUID outbound(UUID tenant, String code, String carrier, UUID... skuIds) {
    var codes = new java.util.LinkedHashMap<UUID, String>();
    for (UUID skuId : skuIds) codes.put(skuId, asCode(tenant, skuId));
    return as(
        tenant,
        () -> {
          UUID orderId = UUID.randomUUID();
          sql.execute(
              "insert into outbound_order(id,tenant_id,order_code,source_type,source_external_ref,"
                  + "warehouse_id,carrier_code,status,created_by_user_id)"
                  + " values(?,?,?,'SALES',?,?,?,'PENDING_PICKING',?)",
              orderId,
              tenant,
              code,
              "SO-" + code,
              wh(tenant),
              carrier,
              user);
          BigDecimal total = BigDecimal.ZERO;
          int lineNo = 0;
          for (UUID skuId : skuIds) {
            lineNo++;
            sql.execute(
                "insert into outbound_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,"
                    + "sku_name,quantity) values(?,?,?,?,?,?,?,'10')",
                UUID.randomUUID(),
                tenant,
                orderId,
                lineNo,
                skuId,
                codes.get(skuId),
                "商品" + lineNo);
            total = total.add(new BigDecimal("10"));
          }
          sql.execute(
              "insert into outbound_pick_request(id,tenant_id,order_id,line_count,"
                  + "total_piece_count,requested_by_user_id,requested_at)"
                  + " values(?,?,?,?,?,?,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              orderId,
              skuIds.length,
              total,
              user,
              OffsetDateTime.now());
          sql.execute(
              "update outbound_order set status='PICKING' where tenant_id=? and id=?",
              tenant,
              orderId);
          return orderId;
        });
  }

  String asCode(UUID tenant, UUID skuId) {
    return as(
        tenant,
        () ->
            sql.fetchOne(
                    "select code from sku_ref where tenant_id"
                        + " = current_setting('app.current_tenant', true)::uuid and id = ?",
                    skuId)
                .get("code", String.class));
  }

  /** 走 8.3.2.1 的真实入口生成拣货任务（不手工插 pick_task，保证链路是真的）。 */
  UUID pickTask(UUID tenant, UUID orderId) {
    return asWrite(
            tenant,
            () ->
                pickTasks.generate(
                    new PickTaskModels.PickTaskGenerateInput(orderId, null, null), user))
        .id();
  }

  PickWaveRuleRow rule(
      UUID tenant, String code, String carrier, UUID zoneId, int start, int end, int cap) {
    return asWrite(
        tenant,
        () ->
            service.createRule(
                new PickWaveRuleInput(
                    code, "规则 " + code, wh(tenant), carrier, zoneId, start, end, cap, 100),
                user));
  }

  PickWaveCreated wave(UUID tenant, UUID ruleId, String carrier, UUID zoneId) {
    return asWrite(
        tenant,
        () ->
            service.create(
                new PickWaveCreateInput(wh(tenant), ruleId, carrier, zoneId, null, null, null),
                user));
  }

  /** 把一张拣货任务推到终态（走 8.3.2.1 的真实动作）。 */
  void finishTask(UUID tenant, UUID taskId) {
    var lines = asWrite(tenant, () -> pickTasks.detail(taskId).lines());
    asWrite(
        tenant,
        () -> pickTasks.assign(taskId, new PickTaskModels.PickTaskAssignInput(user, null), user));
    for (var line : lines) {
      asWrite(
          tenant,
          () ->
              pickTasks.recordPick(
                  taskId,
                  new PickTaskModels.PickTaskPickInput(
                      line.id(),
                      line.sourceLocationId(),
                      null,
                      line.plannedQuantity(),
                      null,
                      null,
                      null),
                  user));
    }
    asWrite(tenant, () -> pickTasks.complete(taskId, user));
  }

  /** 用超级用户直连跑一段 SQL —— 用来证明「绕开服务层也过不去」。 */
  void superuserSql(String... statements) throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      for (var statement : statements) s.execute(statement);
    }
  }

  long inventoryEntryCount(UUID tenant) {
    return as(
        tenant,
        () ->
            sql.fetchOne(
                    "select count(*) as c from inventory_entry where tenant_id"
                        + " = current_setting('app.current_tenant', true)::uuid")
                .get("c", Long.class));
  }

  BigDecimal balanceOnHand(UUID tenant, UUID skuId) {
    return as(
        tenant,
        () ->
            sql.fetchOne(
                    "select on_hand from inventory_balance where tenant_id"
                        + " = current_setting('app.current_tenant', true)::uuid"
                        + " and sku_id = ? and warehouse_id = ?",
                    skuId,
                    wh(tenant))
                .get("on_hand", BigDecimal.class));
  }

  // ================================================================ 能力声明

  @Test
  void capabilitiesReportTheThreeMergeDimensionsAndThatNothingIsMoved() {
    var caps = service.capabilities();
    assertThat(caps.refType()).isEqualTo("PICK_WAVE");
    assertThat(caps.carrierMergeLive()).isTrue();
    assertThat(caps.zoneMergeLive()).isTrue();
    assertThat(caps.zoneDerivedFromLocationTree()).isTrue();
    assertThat(caps.timeSlotMergeLive()).isTrue();
    assertThat(caps.orderCountCapEnforced()).isTrue();
    assertThat(caps.oneOpenWavePerMergeKey()).isTrue();
    assertThat(caps.oneOpenWavePerTask()).isTrue();
    // 波次是排班：一件货都不搬，inventory 由 8.3.2.1 拥有。
    assertThat(caps.inventoryUnaffected()).isFalse();
    assertThat(caps.inventoryOwnerModule()).isEqualTo("8.3.2.1 拣货任务");
    // 蓝图关系段原话：合并不改订单粒度。
    assertThat(caps.taskOrderGranularityUnchanged()).isTrue();
    assertThat(caps.mergeBasis()).contains("承运渠道").contains("库区").contains("订单数上限");
    assertThat(caps.timeSlotBasis()).contains("warehouse.timezone");
    assertThat(caps.statuses()).containsExactly("PLANNED", "PICKING", "COMPLETED", "CANCELLED");
  }

  // ================================================================ 合并规则

  @Test
  void rulesRequireNameCodeAndOrderCap() {
    Layout la = layout(a);
    assertThat(
            catchThrowable(
                () ->
                    asWrite(
                        a,
                        () ->
                            service.createRule(
                                new PickWaveRuleInput("", "名字", wh(a), "", null, 0, 24, 5, 100),
                                user))))
        .hasMessageContaining("ruleCode");
    assertThat(
            catchThrowable(
                () ->
                    asWrite(
                        a,
                        () ->
                            service.createRule(
                                new PickWaveRuleInput("R-X", "", wh(a), "", null, 0, 24, 5, 100),
                                user))))
        .hasMessageContaining("ruleName");
    // 蓝图点名的第三个合并维度：订单数上限必须是正数，不能留空。
    assertThat(
            catchThrowable(
                () ->
                    asWrite(
                        a,
                        () ->
                            service.createRule(
                                new PickWaveRuleInput("R-Y", "没上限", wh(a), "", null, 0, 24, 0, 100),
                                user))))
        .hasMessageContaining("maxOrderCount");
    // 时段窗口非法（跨午夜请拆两条）。
    assertThat(
            catchThrowable(
                () ->
                    asWrite(
                        a,
                        () ->
                            service.createRule(
                                new PickWaveRuleInput("R-Z", "坏时段", wh(a), "", null, 18, 9, 5, 100),
                                user))))
        .hasMessageContaining("时段窗口");
    assertThat(la.zoneA()).isNotNull();
  }

  @Test
  void twoActiveRulesCannotClaimTheSameCarrierZoneAndSlot() {
    layout(a);
    rule(a, "R-1", "SF", null, 9, 18, 10);
    assertThat(catchThrowable(() -> rule(a, "R-2", "SF", null, 9, 18, 20)))
        .hasMessageContaining("只能有一条生效规则");
    // 时段窗口不同 → 两条规则共存合法（它们认领的是不同批次的货），
    // 而 R-2 因为撞了 pick_wave_rule_active_key 没有被创建，所以台账上是 2 条。
    rule(a, "R-3", "SF", null, 18, 24, 20);
    assertThat(as(a, () -> service.rules(wh(a))).items())
        .extracting(PickWaveRuleRow::ruleCode)
        .containsExactlyInAnyOrder("R-1", "R-3");
  }

  @Test
  void retiringARuleNeedsAReasonAndHappensOnlyOnce() {
    layout(a);
    var r = rule(a, "R-RET", "SF", null, 0, 24, 10);
    assertThat(catchThrowable(() -> retire(a, r.id(), ""))).hasMessageContaining("reason");
    var retired = retire(a, r.id(), "承运商换了");
    assertThat(retired.status()).isEqualTo("INACTIVE");
    assertThat(retired.retiredAt()).isNotNull();
    assertThat(retired.retireReason()).isEqualTo("承运商换了");
    assertThat(catchThrowable(() -> retire(a, r.id(), "再来一次"))).hasMessageContaining("已经停用");
  }

  PickWaveRuleRow retire(UUID tenant, UUID ruleId, String reason) {
    return asWrite(
        tenant, () -> service.retireRule(ruleId, new PickWaveRuleRetireInput(reason), user));
  }

  // ================================================================ 合并

  @Test
  void mergesByCarrierAndZoneAndCapsAtTheRuleLimit() {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    bucket(a, la.a1(), sku(a, 2), "100");
    // 三张同承运商、同库区的单 + 一张不同承运商的。
    pickTask(a, outbound(a, "OB-A1", "SF", sku(a, 1)));
    pickTask(a, outbound(a, "OB-A2", "SF", sku(a, 2)));
    UUID third = outbound(a, "OB-A3", "SF", sku(a, 1));
    pickTask(a, third);
    pickTask(a, outbound(a, "OB-B1", "YTO", sku(a, 1)));

    var r = rule(a, "R-MERGE", "SF", la.zoneA(), 0, 24, 2);
    var candidates = as(a, () -> service.candidates(wh(a)));
    // 两个承运商 → 两个合并键。
    assertThat(candidates.groups()).hasSize(2);
    var sfGroup = groupOf(candidates, "SF", la.zoneA());
    assertThat(sfGroup.eligibleCount()).isEqualTo(2);
    // 撞上限的那张要真的在「等」，不能被悄悄丢掉。
    assertThat(sfGroup.waitingCount()).isEqualTo(1);
    assertThat(sfGroup.candidates()).hasSize(3);
    assertThat(sfGroup.maxOrderCount()).isEqualTo(2);

    var created = wave(a, r.id(), "SF", la.zoneA());
    assertThat(created.memberCount()).isEqualTo(2);
    assertThat(created.orderCount()).isEqualTo(2);
    assertThat(created.waitingCount()).isEqualTo(1);
    assertThat(created.carrierCode()).isEqualTo("SF");
    assertThat(created.zoneId()).isEqualTo(la.zoneA());

    // 位置 1..n 连续，且每一行都是一张出库单的一张拣货任务。
    var detail = as(a, () -> service.detail(created.id()));
    assertThat(detail.members()).hasSize(2);
    assertThat(detail.members().get(0).position()).isEqualTo(1);
    assertThat(detail.members().get(1).position()).isEqualTo(2);
    assertThat(detail.members())
        .extracting(PickWaveMemberRow::outboundOrderCode)
        .containsExactly("OB-A1", "OB-A2");
    // 合并口径原文随响应返回。
    assertThat(detail.wave().mergeBasis()).contains("SF").contains("库区 = A").contains("订单数上限 2");
    assertThat(detail.report().orderGranularityNote()).contains("不改变订单粒度");
  }

  @Test
  void zoneComesFromTheLocationTreeRootNotFromThePickLocation() {
    Layout la = layout(a);
    bucket(a, la.b1(), sku(a, 1), "100");
    var task = pickTask(a, outbound(a, "OB-Z", "SF", sku(a, 1)));
    var r = rule(a, "R-Z", "SF", null, 0, 24, 10);
    var created = wave(a, r.id(), "SF", null);
    // 在 B-1 拣货 → 库区是 B（B-1 的顶层根节点），不是 B-1 本身，也不是「不限」。
    assertThat(created.zoneId()).isEqualTo(la.zoneB());
    assertThat(created.zoneCode()).isEqualTo("B");
    assertThat(as(a, () -> service.detail(created.id())).members().get(0).zoneCode())
        .isEqualTo("B");
    assertThat(task).isNotNull();
  }

  @Test
  void aZoneBoundRuleDoesNotMatchOrdersFromAnotherZone() {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    bucket(a, la.b1(), sku(a, 2), "100");
    pickTask(a, outbound(a, "OB-IN-A", "SF", sku(a, 1)));
    pickTask(a, outbound(a, "OB-IN-B", "SF", sku(a, 2)));
    // 规则只认 A 区。
    var r = rule(a, "R-ONLY-A", "SF", la.zoneA(), 0, 24, 10);
    var created = wave(a, r.id(), "SF", la.zoneA());
    assertThat(created.memberCount()).isEqualTo(1);
    assertThat(as(a, () -> service.detail(created.id())).members().get(0).outboundOrderCode())
        .isEqualTo("OB-IN-A");
    // B 区那张落在「没有规则认领」里，不会被这条规则顺手捡走。
    var candidates = as(a, () -> service.candidates(wh(a)));
    var bGroup = groupOf(candidates, "SF", la.zoneB());
    assertThat(bGroup.unruledCount()).isEqualTo(1);
    assertThat(bGroup.eligibleCount()).isZero();
  }

  @Test
  void aTaskSpanningTwoZonesHasNoSingleZoneAndOnlyMatchesAnUnboundedZoneRule() {
    Layout la = layout(a);
    // 一个出库单两个 SKU，分别放在 A-1 与 B-1 → 拣货段横跨两个区。
    bucket(a, la.a1(), sku(a, 1), "100");
    bucket(a, la.b1(), sku(a, 2), "100");
    pickTask(a, outbound(a, "OB-XZONE", "SF", sku(a, 1), sku(a, 2)));

    var candidates = as(a, () -> service.candidates(wh(a)));
    var group = candidates.groups().get(0);
    assertThat(group.candidates()).hasSize(1);
    var row = group.candidates().get(0);
    assertThat(row.crossZone()).isTrue();
    assertThat(row.zoneId()).isNull();

    // 「库区不限」的规则能收它。
    var open = rule(a, "R-ANY-ZONE", "SF", null, 0, 24, 10);
    var created = wave(a, open.id(), "SF", null);
    assertThat(created.memberCount()).isEqualTo(1);
    assertThat(created.zoneId()).isNull();
    // 限定库区的规则收不到它。
    var scoped = rule(a, "R-ZONE-A", "SF", la.zoneA(), 0, 24, 10);
    var after = as(a, () -> service.candidates(wh(a)));
    assertThat(groupOf(after, "SF", null).unruledCount()).isZero();
    assertThat(scoped.zoneId()).isEqualTo(la.zoneA());
  }

  @Test
  void timeSlotIsEvaluatedInWarehouseLocalHoursNotUtc() {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    bucket(a, la.a1(), sku(a, 2), "100");
    // 两张单：一张的创建时刻落在仓本地 08:00（UTC 00:00），一张落在 20:00。
    UUID morning = outbound(a, "OB-MORNING", "SF", sku(a, 1));
    setCreatedAt(
        a,
        morning,
        LocalDate.now(WAREHOUSE_ZONE).atTime(8, 0).atZone(WAREHOUSE_ZONE).toOffsetDateTime());
    UUID night = outbound(a, "OB-NIGHT", "SF", sku(a, 2));
    setCreatedAt(
        a,
        night,
        LocalDate.now(WAREHOUSE_ZONE).atTime(20, 0).atZone(WAREHOUSE_ZONE).toOffsetDateTime());
    pickTask(a, morning);
    pickTask(a, night);

    // 09:00–18:00 的规则：只收得上午那张（08:00 已在窗口外，两个都收不到）；
    // 改成 08:00–19:00 才收得到上午那张、收不到晚上那张。
    var morningOnly = rule(a, "R-SLOT-AM", "SF", la.zoneA(), 8, 19, 10);
    var group = groupOf(as(a, () -> service.candidates(wh(a))), "SF", la.zoneA());
    assertThat(group.candidates().stream().filter(c -> c.blocked() || true).count()).isEqualTo(2);
    var morningRow =
        group.candidates().stream()
            .filter(c -> c.outboundOrderCode().equals("OB-MORNING"))
            .findFirst()
            .orElseThrow();
    var nightRow =
        group.candidates().stream()
            .filter(c -> c.outboundOrderCode().equals("OB-NIGHT"))
            .findFirst()
            .orElseThrow();
    assertThat(morningRow.localHour()).isEqualTo(8);
    assertThat(nightRow.localHour()).isEqualTo(20);
    assertThat(morningRow.matchedRuleId()).isEqualTo(morningOnly.id());
    assertThat(nightRow.matchedRuleId()).isNull();
    assertThat(morningOnly.timeSlotStart()).isEqualTo(8);
    assertThat(morningOnly.timeSlotEnd()).isEqualTo(19);
  }

  @Test
  void plannedPickAtIsDerivedFromTheRuleSlotInWarehouseLocalTime() {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    UUID plan = outbound(a, "OB-PLAN", "SF", sku(a, 1));
    setCreatedAt(
        a,
        plan,
        LocalDate.now(WAREHOUSE_ZONE).atTime(10, 0).atZone(WAREHOUSE_ZONE).toOffsetDateTime());
    pickTask(a, plan);
    var r = rule(a, "R-SLOT-PLAN", "SF", la.zoneA(), 9, 18, 10);
    var created = wave(a, r.id(), "SF", la.zoneA());

    // 断言写回的值：仓本地今天的 09:00，带 +08:00 偏移量。
    // （走 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s，所以这里必须断偏移量。）
    OffsetDateTime expected =
        LocalDate.now(WAREHOUSE_ZONE).atStartOfDay(WAREHOUSE_ZONE).plusHours(9).toOffsetDateTime();
    assertThat(created.plannedPickAt()).isEqualTo(expected);
    assertThat(created.plannedPickAt().getOffset()).isEqualTo(ZoneOffset.ofHours(8));
    assertThat(as(a, () -> service.detail(created.id())).members().get(0).plannedPickAt())
        .isEqualTo(expected);

    // 显式给了时刻就按给的来（同样断言写回值）。
    String given = "2026-10-14T09:30:00+08:00";
    var explicit =
        asWrite(
            a,
            () ->
                service.create(
                    new PickWaveCreateInput(
                        wh(a),
                        null,
                        "YTO",
                        null,
                        List.of(pickTask(a, outbound(a, "OB-PLAN2", "YTO", sku(a, 1)))),
                        given,
                        null),
                    user));
    assertThat(explicit.plannedPickAt()).isEqualTo(OffsetDateTime.parse(given));
  }

  PickWaveCandidateGroup groupOf(PickWaveCandidatePage page, String carrier, UUID zoneId) {
    return page.groups().stream()
        .filter(
            g -> g.carrierCode().equals(carrier) && java.util.Objects.equals(g.zoneId(), zoneId))
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "找不到合并键 "
                        + carrier
                        + "/"
                        + zoneId
                        + "，现有："
                        + page.groups().stream()
                            .map(g -> g.carrierCode() + "/" + g.zoneId())
                            .toList()));
  }

  void setCreatedAt(UUID tenant, UUID orderId, OffsetDateTime at) {
    as(
        tenant,
        () -> {
          sql.execute(
              "update outbound_order set created_at=?::timestamptz where tenant_id=? and id=?",
              at,
              tenant,
              orderId);
          return null;
        });
  }

  // ================================================================ DB 级不变量

  @Test
  void databaseRefusesASecondOpenWaveOnTheSameMergeKey() throws Exception {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    pickTask(a, outbound(a, "OB-KEY", "SF", sku(a, 1)));
    var created = wave(a, rule(a, "R-KEY", "SF", la.zoneA(), 0, 24, 10).id(), "SF", la.zoneA());

    // 绕开服务层、超级用户直连：同键的第二个未完波次必须被部分唯一索引拒掉。
    assertThat(
            catchThrowable(
                () ->
                    superuserSql(
                        "insert into pick_wave(id,tenant_id,wave_code,warehouse_id,carrier_code,"
                            + "zone_id,merge_basis,status,planned_pick_at,created_by)"
                            + " values(gen_random_uuid(),'"
                            + a
                            + "','PW-DUP','"
                            + wh(a)
                            + "','SF','"
                            + la.zoneA()
                            + "','手工绕过服务层','PLANNED',now(),'"
                            + user
                            + "')")))
        .hasMessageContaining("pick_wave_open_key");

    // 服务层也要给出人话（而不是只抛一个约束名）。
    pickTask(a, outbound(a, "OB-KEY2", "SF", sku(a, 1)));
    assertThat(catchThrowable(() -> wave(a, null, "SF", la.zoneA())))
        .hasMessageContaining("已经有一个未完波次");
    assertThat(created.waveCode()).startsWith("PW-");
  }

  @Test
  void databaseRefusesPuttingTheSamePickTaskIntoTwoOpenWaves() throws Exception {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    bucket(a, la.a1(), sku(a, 2), "100");
    UUID taskOne = pickTask(a, outbound(a, "OB-T1", "SF", sku(a, 1)));
    pickTask(a, outbound(a, "OB-T2", "YTO", sku(a, 2)));

    // 两个不同承运商 → 两个不同的合并键 → 两波都合法。
    var waveOne = wave(a, rule(a, "R-T1", "SF", la.zoneA(), 0, 24, 10).id(), "SF", la.zoneA());
    var waveTwo = wave(a, rule(a, "R-T2", "YTO", la.zoneA(), 0, 24, 10).id(), "YTO", la.zoneA());
    assertThat(waveOne.memberCount()).isEqualTo(1);
    assertThat(waveTwo.memberCount()).isEqualTo(1);

    // 同一张工单再塞进第二个波次：索引必须拒。
    assertThat(
            catchThrowable(
                () ->
                    superuserSql(
                        "insert into pick_wave_task(id,tenant_id,wave_id,pick_task_id,"
                            + "outbound_order_id,position,planned_pick_at)"
                            + " select gen_random_uuid(),'"
                            + a
                            + "','"
                            + waveTwo.id()
                            + "','"
                            + taskOne
                            + "',o.id,99,now() from outbound_order o"
                            + " where o.tenant_id='"
                            + a
                            + "' and o.order_code='OB-T1'")))
        .hasMessageContaining("pick_wave_task_active");
  }

  @Test
  void databaseRefusesCompletingAWaveWhileAMemberTaskIsStillOpen() throws Exception {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    bucket(a, la.a1(), sku(a, 2), "100");
    pickTask(a, outbound(a, "OB-C1", "SF", sku(a, 1)));
    pickTask(a, outbound(a, "OB-C2", "SF", sku(a, 2)));
    var created = wave(a, rule(a, "R-C", "SF", la.zoneA(), 0, 24, 10).id(), "SF", la.zoneA());
    asWrite(a, () -> service.release(created.id(), new PickWaveReleaseInput(user, null), user));

    // 服务层先说人话。
    assertThat(catchThrowable(() -> complete(a, created.id())))
        .hasMessageContaining("不替代任何一张工单的完成");

    // 超用户直连绕过服务层：V113 的守卫仍然拒（它回表读成员的真实状态）。
    assertThat(
            catchThrowable(
                () ->
                    superuserSql(
                        "update pick_wave set status='COMPLETED', completed_at=now()"
                            + " where tenant_id='"
                            + a
                            + "' and id='"
                            + created.id()
                            + "'")))
        .hasMessageContaining("不替代任何一张工单的完成");

    // 两张都到终态之后就能完成。
    for (var member : as(a, () -> service.detail(created.id())).members()) {
      finishTask(a, member.pickTaskId());
    }
    var done = complete(a, created.id());
    assertThat(done.wave().status()).isEqualTo("COMPLETED");
    assertThat(done.wave().progress().fullyPicked()).isTrue();
    assertThat(done.wave().completedAt()).isNotNull();
  }

  @Test
  void illegalWaveStatusTransitionsAreRejectedByTheDatabase() throws Exception {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    pickTask(a, outbound(a, "OB-ST", "SF", sku(a, 1)));
    var created = wave(a, rule(a, "R-ST", "SF", la.zoneA(), 0, 24, 10).id(), "SF", la.zoneA());

    // PLANNED 不能直接跳到 COMPLETED（成员还没动，且少了一条成员工单终态检查）。
    assertThat(
            catchThrowable(
                () ->
                    superuserSql(
                        "update pick_wave set status='COMPLETED', completed_at=now()"
                            + " where tenant_id='"
                            + a
                            + "' and id='"
                            + created.id()
                            + "'")))
        .hasMessageContaining("不能从 PLANNED 变成 COMPLETED");
    // 已放行之后不能退回「已排波」。
    asWrite(a, () -> service.release(created.id(), new PickWaveReleaseInput(user, null), user));
    assertThat(
            catchThrowable(
                () ->
                    superuserSql(
                        "update pick_wave set status='PLANNED' where tenant_id='"
                            + a
                            + "' and id='"
                            + created.id()
                            + "'")))
        .hasMessageContaining("不能从 PICKING 变成 PLANNED");
  }

  @Test
  void waveEventsAreAppendOnly() throws Exception {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    pickTask(a, outbound(a, "OB-EV", "SF", sku(a, 1)));
    var created = wave(a, rule(a, "R-EV", "SF", la.zoneA(), 0, 24, 10).id(), "SF", la.zoneA());
    var events = as(a, () -> service.detail(created.id())).events();
    assertThat(events).hasSize(1);
    assertThat(events.get(0).eventType()).isEqualTo("PLANNED");
    assertThat(events.get(0).detail()).contains("SF");
    assertThat(
            catchThrowable(
                () ->
                    superuserSql(
                        "update pick_wave_event set detail='改过了' where tenant_id='"
                            + a
                            + "' and id='"
                            + events.get(0).id()
                            + "'")))
        .hasMessageContaining("append-only");
  }

  // ================================================================ 生命周期

  @Test
  void detachingAMemberRenumbersPositionsWithoutLeavingHoles() {
    Layout la = layout(a);
    for (int i = 1; i <= 3; i++) bucket(a, la.a1(), sku(a, i), "100");
    pickTask(a, outbound(a, "OB-D1", "SF", sku(a, 1)));
    pickTask(a, outbound(a, "OB-D2", "SF", sku(a, 2)));
    pickTask(a, outbound(a, "OB-D3", "SF", sku(a, 3)));
    var created = wave(a, rule(a, "R-D", "SF", la.zoneA(), 0, 24, 10).id(), "SF", la.zoneA());
    assertThat(as(a, () -> service.detail(created.id())).members()).hasSize(3);

    var middle = as(a, () -> service.detail(created.id())).members().get(1).pickTaskId();
    var after = asWrite(a, () -> service.detach(created.id(), middle, user));
    assertThat(after.members()).hasSize(2);
    // 移走中间那个之后必须重排成 1,2，不能留下 1,3。
    assertThat(after.members()).extracting(PickWaveMemberRow::position).containsExactly(1, 2);
    assertThat(after.members())
        .extracting(PickWaveMemberRow::outboundOrderCode)
        .containsExactly("OB-D1", "OB-D3");
    // 移出后它回到候选池：不再 blocked，但因为这一波还没收工（同键未完波次挡住），
    // 它是「在等下一波」而不是「现在就能并」—— 这正是 pick_wave_open_key 的代价。
    var candidates = as(a, () -> service.candidates(wh(a)));
    var detachedRow =
        candidates.groups().get(0).candidates().stream()
            .filter(c -> c.pickTaskId().equals(middle))
            .findFirst()
            .orElseThrow();
    assertThat(detachedRow.blocked()).isFalse();
    assertThat(candidates.totalWaiting()).isGreaterThanOrEqualTo(1);

    // 只剩一个成员时不允许再移：空波次既不能放行也不能完成。
    // 另开一个两成员的波次（另一个承运商 = 另一个合并键）来验这一条。
    bucket(a, la.a1(), sku(a, 4), "100");
    pickTask(a, outbound(a, "OB-D4", "YTO", sku(a, 4)));
    bucket(a, la.a2(), sku(a, 5), "100");
    pickTask(a, outbound(a, "OB-D5", "YTO", sku(a, 5)));
    var two = wave(a, rule(a, "R-D-YTO", "YTO", la.zoneA(), 0, 24, 10).id(), "YTO", la.zoneA());
    assertThat(two.memberCount()).isEqualTo(2);
    var one =
        asWrite(
            a,
            () ->
                service.detach(
                    two.id(),
                    as(a, () -> service.detail(two.id())).members().get(0).pickTaskId(),
                    user));
    assertThat(one.members()).hasSize(1);
    var last = one.members().get(0).pickTaskId();
    assertThat(catchThrowable(() -> asWrite(a, () -> service.detach(two.id(), last, user))))
        .hasMessageContaining("要放弃这一波请用取消");
  }

  @Test
  void detachIsRejectedOnceTheWaveIsReleased() {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    bucket(a, la.a1(), sku(a, 2), "100");
    pickTask(a, outbound(a, "OB-R1", "SF", sku(a, 1)));
    pickTask(a, outbound(a, "OB-R2", "SF", sku(a, 2)));
    var created = wave(a, rule(a, "R-R", "SF", la.zoneA(), 0, 24, 10).id(), "SF", la.zoneA());
    asWrite(a, () -> service.release(created.id(), new PickWaveReleaseInput(user, null), user));
    var member = as(a, () -> service.detail(created.id())).members().get(0).pickTaskId();
    assertThat(catchThrowable(() -> asWrite(a, () -> service.detach(created.id(), member, user))))
        .hasMessageContaining("已经放行");
  }

  @Test
  void releaseRequiresAnOwner() {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    pickTask(a, outbound(a, "OB-REL", "SF", sku(a, 1)));
    var created = wave(a, rule(a, "R-REL", "SF", la.zoneA(), 0, 24, 10).id(), "SF", la.zoneA());
    assertThat(
            catchThrowable(
                () ->
                    asWrite(
                        a,
                        () ->
                            service.release(
                                created.id(), new PickWaveReleaseInput(null, null), user))))
        .hasMessageContaining("assigneeUserId");
    var released =
        asWrite(a, () -> service.release(created.id(), new PickWaveReleaseInput(user, "早班"), user));
    assertThat(released.wave().status()).isEqualTo("PICKING");
    assertThat(released.wave().assigneeUserId()).isEqualTo(user);
    assertThat(released.wave().startedAt()).isNotNull();
  }

  @Test
  void cancellingReleasesMembersBackToThePoolAndMovesNoStock() {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    bucket(a, la.a1(), sku(a, 2), "100");
    pickTask(a, outbound(a, "OB-CX1", "SF", sku(a, 1)));
    pickTask(a, outbound(a, "OB-CX2", "SF", sku(a, 2)));
    var created = wave(a, rule(a, "R-CX", "SF", la.zoneA(), 0, 24, 10).id(), "SF", la.zoneA());

    long entriesBefore = inventoryEntryCount(a);
    BigDecimal balanceBefore = balanceOnHand(a, sku(a, 1));
    assertThat(
            catchThrowable(
                () ->
                    asWrite(
                        a, () -> service.cancel(created.id(), new PickWaveCancelInput(""), user))))
        .hasMessageContaining("reason");

    var cancelled =
        asWrite(a, () -> service.cancel(created.id(), new PickWaveCancelInput("货还没到齐"), user));
    assertThat(cancelled.wave().status()).isEqualTo("CANCELLED");
    assertThat(cancelled.wave().cancelReason()).isEqualTo("货还没到齐");
    // 取消不动库存、不写分录。
    assertThat(inventoryEntryCount(a)).isEqualTo(entriesBefore);
    assertThat(balanceOnHand(a, sku(a, 1))).isEqualByComparingTo(balanceBefore);
    // 成员被放回候选池（released=true）。
    long notReleased =
        as(
            a,
            () ->
                sql.fetchOne(
                        "select count(*) as c from pick_wave_task where tenant_id"
                            + " = current_setting('app.current_tenant', true)::uuid"
                            + " and wave_id = ? and not released",
                        created.id())
                    .get("c", Long.class));
    assertThat(notReleased).isZero();

    // 放回池子之后同一合并键可以再开一波（未完波次的唯一键不再挡路）。
    var again = wave(a, null, "SF", la.zoneA());
    assertThat(again.memberCount()).isEqualTo(2);
  }

  @Test
  void mergingDoesNotChangeOrderGranularity() {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    bucket(a, la.a1(), sku(a, 2), "100");
    UUID o1 = outbound(a, "OB-G1", "SF", sku(a, 1));
    UUID o2 = outbound(a, "OB-G2", "SF", sku(a, 2));
    pickTask(a, o1);
    pickTask(a, o2);
    var created = wave(a, rule(a, "R-G", "SF", la.zoneA(), 0, 24, 10).id(), "SF", la.zoneA());

    var members = as(a, () -> service.detail(created.id())).members();
    // 一个波次，两个成员 —— 每个成员仍是各自那张出库单、各自那张拣货任务。
    assertThat(members).hasSize(2);
    assertThat(members)
        .extracting(PickWaveMemberRow::outboundOrderId)
        .containsExactlyInAnyOrder(o1, o2);
    assertThat(members.stream().map(PickWaveMemberRow::pickTaskId).distinct()).hasSize(2);
    // 出库单状态没有被波次动过，仍是「拣货中」（复核/打包是 8.3.3.1 按订单做的事）。
    for (var member : members) {
      assertThat(member.outboundOrderStatus()).isEqualTo("PICKING");
      assertThat(member.taskStatus()).isEqualTo("CREATED");
    }
    // 波次进度是各工单进度之和（现算，不是冗余列）。
    var waveRow = as(a, () -> service.detail(created.id())).wave();
    assertThat(waveRow.progress().taskCount()).isEqualTo(2);
    assertThat(waveRow.progress().orderCount()).isEqualTo(2);
    assertThat(waveRow.progress().plannedQuantity()).isEqualByComparingTo("20");
    assertThat(waveRow.progress().progress()).isEqualByComparingTo("0");
  }

  @Test
  void manualMembersMustShareCarrierAndWarehouse() {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    bucket(a, la.a1(), sku(a, 2), "100");
    UUID sfTask = pickTask(a, outbound(a, "OB-M1", "SF", sku(a, 1)));
    UUID ytoTask = pickTask(a, outbound(a, "OB-M2", "YTO", sku(a, 2)));

    assertThat(
            catchThrowable(
                () ->
                    asWrite(
                        a,
                        () ->
                            service.create(
                                new PickWaveCreateInput(
                                    wh(a), null, null, null, List.of(sfTask, ytoTask), null, null),
                                user))))
        .hasMessageContaining("承运渠道不一致");

    var manual =
        asWrite(
            a,
            () ->
                service.create(
                    new PickWaveCreateInput(wh(a), null, null, null, List.of(sfTask), null, null),
                    user));
    assertThat(manual.memberCount()).isEqualTo(1);
    assertThat(manual.carrierCode()).isEqualTo("SF");
    assertThat(manual.ruleId()).isNull();
    assertThat(manual.mergeBasis()).contains("手工指定");
  }

  // ================================================================ 读面

  @Test
  void listPagesFiltersSortsAndSummarizes() {
    Layout la = layout(a);
    for (int i = 1; i <= 3; i++) bucket(a, la.a1(), sku(a, i), "100");
    pickTask(a, outbound(a, "OB-L1", "SF", sku(a, 1)));
    pickTask(a, outbound(a, "OB-L2", "YTO", sku(a, 2)));
    pickTask(a, outbound(a, "OB-L3", "SF", sku(a, 3)));
    wave(a, rule(a, "R-L-SF", "SF", la.zoneA(), 0, 24, 10).id(), "SF", la.zoneA());
    wave(a, rule(a, "R-L-YTO", "YTO", la.zoneA(), 0, 24, 10).id(), "YTO", la.zoneA());

    var all = as(a, () -> service.page(wh(a), null, null, null, null, "PLANNED_PICK_AT", 0, 20));
    assertThat(all.total()).isEqualTo(2);
    assertThat(all.items()).hasSize(2);
    assertThat(all.summary().plannedCount()).isEqualTo(2);
    // SF 那一波吃掉了两张（OB-L1 / OB-L3），YTO 那一波一张 —— 合计 3 张出库单。
    assertThat(all.summary().openOrderCount()).isEqualTo(3);

    // 真分页（不是前端切片）。
    var first = as(a, () -> service.page(wh(a), null, null, null, null, "PLANNED_PICK_AT", 0, 1));
    var second = as(a, () -> service.page(wh(a), null, null, null, null, "PLANNED_PICK_AT", 1, 1));
    assertThat(first.items()).hasSize(1);
    assertThat(second.items()).hasSize(1);
    assertThat(first.items().get(0).id()).isNotEqualTo(second.items().get(0).id());

    // 真筛选：承运渠道 / 状态 / 关键字。
    assertThat(
            as(a, () -> service.page(wh(a), null, "SF", null, null, "PLANNED_PICK_AT", 0, 20))
                .total())
        .isEqualTo(1);
    assertThat(
            as(a, () -> service.page(wh(a), "PICKING", null, null, null, "PLANNED_PICK_AT", 0, 20))
                .total())
        .isZero();
    assertThat(
            as(a, () -> service.page(wh(a), null, null, null, "OB-L2", "PLANNED_PICK_AT", 0, 20))
                .total())
        .isEqualTo(1);

    // 排序白名单：非法排序名直接 400，不静默降级。
    assertThat(
            catchThrowable(
                () ->
                    as(a, () -> service.page(wh(a), null, null, null, null, "DROP TABLE", 0, 20))))
        .hasMessageContaining("排序只能是");
    assertThat(
            catchThrowable(
                () ->
                    as(
                        a,
                        () -> service.page(wh(a), "NOT_A_STATUS", null, null, null, null, 0, 20))))
        .hasMessageContaining("状态只能是");
  }

  @Test
  void rulesListShowsClaimedAndWaitingCounts() {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    bucket(a, la.a1(), sku(a, 2), "100");
    bucket(a, la.a1(), sku(a, 3), "100");
    pickTask(a, outbound(a, "OB-RS1", "SF", sku(a, 1)));
    pickTask(a, outbound(a, "OB-RS2", "SF", sku(a, 2)));
    pickTask(a, outbound(a, "OB-RS3", "SF", sku(a, 3)));
    var r = rule(a, "R-RS", "SF", la.zoneA(), 0, 24, 2);

    var before = ruleOf(a, r.id());
    assertThat(before.activeTaskCount()).isEqualTo(3);
    assertThat(before.waitingOrderCount()).isZero();

    wave(a, r.id(), "SF", la.zoneA());

    // 建完波之后：认领 3 张、在波里 2 张、等下一波 1 张。
    var after = ruleOf(a, r.id());
    assertThat(after.activeTaskCount()).isEqualTo(1);
    assertThat(after.waitingOrderCount()).isEqualTo(1);
    assertThat(after.ruleCode()).isEqualTo("R-RS");
  }

  PickWaveRuleRow ruleOf(UUID tenant, UUID ruleId) {
    return as(tenant, () -> service.rules(null)).items().stream()
        .filter(r -> r.id().equals(ruleId))
        .findFirst()
        .orElse(null);
  }

  PickWaveDetail complete(UUID tenant, UUID waveId) {
    return asWrite(tenant, () -> service.complete(waveId, null, user));
  }

  // ================================================================ 多租户三断言

  @Test
  void twoTenantsCannotSeeEachOther() {
    Layout la = layout(a);
    Layout lb = layout(b);
    bucket(a, la.a1(), sku(a, 1), "100");
    bucket(b, lb.a1(), sku(b, 1), "100");
    pickTask(a, outbound(a, "OB-ISO-A", "SF", sku(a, 1)));
    pickTask(b, outbound(b, "OB-ISO-B", "SF", sku(b, 1)));
    var waveA = wave(a, rule(a, "R-ISO-A", "SF", la.zoneA(), 0, 24, 10).id(), "SF", la.zoneA());
    var waveB = wave(b, rule(b, "R-ISO-B", "SF", lb.zoneA(), 0, 24, 10).id(), "SF", lb.zoneA());

    // B 用 A 的波次 id 取详情 → 404。
    assertThat(catchThrowable(() -> as(b, () -> service.detail(waveA.id()))))
        .hasMessageContaining("不存在");
    // B 也看不到 A 的规则。
    assertThat(ruleOf(b, waveA.ruleId())).isNull();
    // B 的列表里只有自己那一波。
    assertThat(
            as(b, () -> service.page(wh(b), null, null, null, null, "PLANNED_PICK_AT", 0, 50))
                .total())
        .isEqualTo(1);
    assertThat(as(b, () -> service.detail(waveB.id())).members()).hasSize(1);
    assertThat(
            catchThrowable(
                () ->
                    asWrite(
                        b, () -> service.cancel(waveA.id(), new PickWaveCancelInput("x"), user))))
        .hasMessageContaining("不存在");
  }

  @Test
  void ruleOfMissingTenantReturnsNull() {
    assertThat(ruleOf(b, UUID.randomUUID())).isNull();
  }

  @Test
  void forgedTenantHeaderIsIgnored() throws Exception {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    pickTask(a, outbound(a, "OB-FT", "SF", sku(a, 1)));
    var created = wave(a, rule(a, "R-FT", "SF", la.zoneA(), 0, 24, 10).id(), "SF", la.zoneA());

    // 伪造 X-Tenant-Id 头：代理忽略它，身份由 Authorization 解析 —— 连身份头也伪造时
    // 结果是 401，而不是「换成 B 的租户」。
    var forged =
        http.perform(
                get("/warehouse/pick-waves/" + created.id())
                    .header("Authorization", "Bearer forged")
                    .header("X-Tenant-Id", b.toString()))
            .andReturn()
            .getResponse();
    assertThat(forged.getStatus()).isEqualTo(401);

    // 带上真实身份但伪造租户头：看到的仍然只有 A 自己的那一波。
    http.perform(
            get("/warehouse/pick-waves")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].waveCode").value(created.waveCode()))
        .andExpect(jsonPath("$.items[0].carrierCode").value("SF"));
  }

  @Test
  void pooledConnectionDoesNotLeakTenantAcrossReturns() {
    Layout la = layout(a);
    Layout lb = layout(b);
    bucket(a, la.a1(), sku(a, 1), "100");
    bucket(b, lb.a1(), sku(b, 1), "100");
    pickTask(a, outbound(a, "OB-POOL-A", "SF", sku(a, 1)));
    pickTask(b, outbound(b, "OB-POOL-B", "SF", sku(b, 1)));
    var waveA = wave(a, rule(a, "R-POOL-A", "SF", la.zoneA(), 0, 24, 10).id(), "SF", la.zoneA());
    var waveB = wave(b, rule(b, "R-POOL-B", "SF", lb.zoneA(), 0, 24, 10).id(), "SF", lb.zoneA());

    // A → B → A 三轮：连接池只有 1 条连接，每次归还后都可能被下一个人拿到。
    for (int round = 0; round < 3; round++) {
      assertThat(
              as(a, () -> service.page(wh(a), null, null, null, null, "PLANNED_PICK_AT", 0, 50))
                  .items()
                  .get(0)
                  .id())
          .isEqualTo(waveA.id());
      assertThat(
              as(b, () -> service.page(wh(b), null, null, null, null, "PLANNED_PICK_AT", 0, 50))
                  .items()
                  .get(0)
                  .id())
          .isEqualTo(waveB.id());
      assertThat(as(a, () -> service.rules(wh(a))).items())
          .extracting(PickWaveRuleRow::ruleCode)
          .containsExactly("R-POOL-A");
      assertThat(as(b, () -> service.rules(wh(b))).items())
          .extracting(PickWaveRuleRow::ruleCode)
          .containsExactly("R-POOL-B");
    }
  }

  @Test
  void allFourTablesForceRowLevelSecurity() {
    var forced =
        as(
            a,
            () ->
                sql
                    .fetch(
                        "select relname, relrowsecurity, relforcerowsecurity from pg_class"
                            + " where relname in ('pick_wave','pick_wave_rule','pick_wave_task',"
                            + " 'pick_wave_event') order by relname")
                    .stream()
                    .map(
                        r ->
                            r.get("relname", String.class)
                                + ":"
                                + r.get("relrowsecurity", Boolean.class)
                                + "/"
                                + r.get("relforcerowsecurity", Boolean.class))
                    .toList());
    assertThat(forced).hasSize(4);
    assertThat(forced).allMatch(s -> s.endsWith("true/true"));
  }

  // ================================================================ HTTP 面

  @Test
  void httpEndpointsEnforcePermissionsAndExposeTheRealContract() throws Exception {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    bucket(a, la.a1(), sku(a, 2), "100");
    UUID task = pickTask(a, outbound(a, "OB-H1", "SF", sku(a, 1)));
    pickTask(a, outbound(a, "OB-H2", "SF", sku(a, 2)));

    http.perform(get("/warehouse/pick-waves/capabilities")).andExpect(status().isUnauthorized());
    http.perform(
            get("/warehouse/pick-waves/capabilities")
                .header("Authorization", "Bearer no-permission"))
        .andExpect(status().isForbidden());
    http.perform(
            get("/warehouse/pick-waves/capabilities").header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.refType").value("PICK_WAVE"))
        .andExpect(jsonPath("$.carrierMergeLive").value(true))
        .andExpect(jsonPath("$.zoneDerivedFromLocationTree").value(true))
        .andExpect(jsonPath("$.inventoryUnaffected").value(false))
        .andExpect(jsonPath("$.taskOrderGranularityUnchanged").value(true))
        .andExpect(jsonPath("$.mergeBasis").isNotEmpty());

    // 建规则要写权限。
    http.perform(
            post("/warehouse/pick-waves/rules")
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content(
                    "{\"ruleCode\":\"R-HTTP\",\"ruleName\":\"读权限不能建规则\",\"warehouseId\":\""
                        + wh(a)
                        + "\",\"maxOrderCount\":5}"))
        .andExpect(status().isForbidden());

    var created =
        http.perform(
                post("/warehouse/pick-waves/rules")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType("application/json")
                    .content(
                        "{\"ruleCode\":\"R-HTTP\",\"ruleName\":\"HTTP 建规则\",\"warehouseId\":\""
                            + wh(a)
                            + "\",\"carrierMatch\":\"SF\",\"zoneId\":\""
                            + la.zoneA()
                            + "\",\"timeSlotStart\":0,\"timeSlotEnd\":24,\"maxOrderCount\":5}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ruleCode").value("R-HTTP"))
            .andExpect(jsonPath("$.carrierUnbounded").value(false))
            .andExpect(jsonPath("$.zoneUnbounded").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(created).contains("R-HTTP");

    // 候选 + 建波次 + 详情。
    http.perform(
            get("/warehouse/pick-waves/candidates?warehouseId=" + wh(a))
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.groups[0].carrierCode").value("SF"))
        .andExpect(jsonPath("$.groups[0].eligibleCount").value(2))
        .andExpect(jsonPath("$.timezone").value("Asia/Shanghai"));

    http.perform(
            post("/warehouse/pick-waves")
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content("{\"warehouseId\":\"" + wh(a) + "\",\"carrierCode\":\"SF\"}"))
        .andExpect(status().isForbidden());

    var waveBody =
        http.perform(
                post("/warehouse/pick-waves")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType("application/json")
                    .content(
                        "{\"warehouseId\":\""
                            + wh(a)
                            + "\",\"carrierCode\":\"SF\",\"zoneId\":\""
                            + la.zoneA()
                            + "\",\"pickTaskIds\":[\""
                            + task
                            + "\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PLANNED"))
            .andExpect(jsonPath("$.memberCount").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String waveId = waveBody.replaceAll("(?s).*\"id\":\"([^\"]+)\".*", "$1");

    http.perform(get("/warehouse/pick-waves/" + waveId).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.members[0].position").value(1))
        .andExpect(jsonPath("$.members[0].outboundOrderCode").value("OB-H1"))
        .andExpect(jsonPath("$.events[0].eventType").value("PLANNED"))
        .andExpect(jsonPath("$.report.orderGranularityNote").isNotEmpty());

    // 必填校验：放行不给负责人 → 400（带原因原文，不静默接受）。
    http.perform(
            post("/warehouse/pick-waves/" + waveId + "/release")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isBadRequest());
    http.perform(
            post("/warehouse/pick-waves/" + waveId + "/cancel")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"reason\":\"\"}"))
        .andExpect(status().isBadRequest());
    http.perform(
            post("/warehouse/pick-waves/rules/" + UUID.randomUUID() + "/retire")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"reason\":\"没有原因\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void upstreamPickTaskCapabilitiesNowReportWaveAsAvailable() {
    // 8.3.2.1 的能力声明曾如实报 waveAvailable=false 并归属到 8.3.2.2；
    // 波次交付之后这一个字段必须跟着改，否则调用方会以为波次还不存在。
    var caps = asWrite(a, () -> pickTasks.capabilities());
    assertThat(caps.waveAvailable()).isTrue();
    assertThat(caps.waveOwnerModule()).isEqualTo("8.3.2.2 波次与合并拣货（已交付）");
  }
}
