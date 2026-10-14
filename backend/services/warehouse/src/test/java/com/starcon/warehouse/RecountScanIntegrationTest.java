package com.starcon.warehouse;

import static com.starcon.warehouse.RecountModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
 * 8.3.3.1 复核扫描。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接： 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>本测试钉住十一件「错了会发错货」的事：
 *
 * <ul>
 *   <li><b>开单三道闸都是真的</b>：出库单不在「待复核」开不了、拣货任务没完成开不了、 一行基线都没有开不了。绕开服务层用超级用户也过不去（V117 的守卫）。
 *   <li><b>基线真的是订单明细</b>：改一次 outbound_order_line 的读数方向不会让基线跟着变 （冻结语义），而改一次拣货实拣量基线里的 pickedQuantity
 *       会跟着变 —— 两个数是分开的。
 *   <li><b>序列号必须逐件核对，件数相等不算过</b>：扫 S1、S2、S9 三件而实拣是 S1、S2、S3 → 件数一样，<b>判序列号不符</b>，并且永远拿不到 MATCHED。
 *   <li><b>漏件/多件/错件/重复扫/未知码五类各有判定来源</b>，不是同一类换名字。
 *   <li><b>重扫不重复计件</b>：扫两遍 SN-1，基线实扫仍是 1 件，但多出一条重复扫异常。
 *   <li><b>「复核是错发拦截的最后机会」在 DB 层也成立</b>：有未处置异常时用 <b>超级用户直连 SQL</b> 强行把复核单改成 PASSED，被 V117
 *       的守卫拒（23514）。
 *   <li><b>通过 ≠ 已发货 ≠ 已打包</b>：通过只把出库单推到「待打包」，SHIPPED 拿不到。
 *   <li><b>复核不动库存</b>：通过与失败两种结局下 inventory_balance / stock_bucket / inventory_entry 全都不变，预占不释放。
 *   <li><b>扫描记录 append-only</b>、<b>异常只能处置一次</b>、<b>规则停用不能复活</b>， 都用超级用户直连 SQL 验过。
 *   <li><b>时刻写回不偏 −28800s</b>：带 +08:00 偏移的扫描时刻原样读回。
 *   <li><b>租户隔离三断言</b>实跑 + 六张表逐张断言 FORCE RLS。
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class RecountScanIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      List.of("warehouse.inventory.read", "warehouse.inventory.update");

  static final ZoneId WAREHOUSE_ZONE = ZoneId.of("Asia/Shanghai");

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-recount-" + tenant + "-" + ordinal).getBytes());
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

  @Autowired RecountScanService service;
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
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate recount_event, recount_exception, recount_scan, recount_line,"
              + " recount_session, recount_rule,"
              + " pick_wave_event, pick_wave_task, pick_wave, pick_wave_rule,"
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
            for (int i = 2; i <= 10; i++) {
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

  record Layout(UUID staging, UUID a1, UUID a2) {}

  Layout layout(UUID tenant) {
    UUID a1 = location(tenant, "A-1", "STORAGE", true, null);
    UUID a2 = location(tenant, "A-2", "STORAGE", true, null);
    UUID staging = location(tenant, "OUT", "SHIPPING", true, null);
    return new Layout(staging, a1, a2);
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
                  + " values(?,?,?,?,?,?,cast(? as numeric),cast(? as numeric))",
              balance,
              tenant,
              skuId,
              wh(tenant),
              idFor(tenant, 1),
              sellable(tenant),
              onHand,
              onHand);
          sql.execute(
              "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,"
                  + "location_id,lot_id,quality_status_id,balance_id,on_hand,reserved,"
                  + "last_movement_at)"
                  + " values(?,?,?,?,?,?,null,?,?,cast(? as numeric),cast(? as numeric),?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              idFor(tenant, 1),
              skuId,
              wh(tenant),
              loc,
              sellable(tenant),
              balance,
              onHand,
              onHand,
              OffsetDateTime.now());
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
              id,
              tenant,
              number,
              skuId,
              loc);
          return id;
        });
  }

  /** 建一张出库单（明细 + 交接请求），每行同一数量。 */
  UUID outbound(UUID tenant, String code, String quantity, UUID... skuIds) {
    var codes = new java.util.LinkedHashMap<UUID, String>();
    for (UUID skuId : skuIds) codes.put(skuId, asCode(tenant, skuId));
    return as(
        tenant,
        () -> {
          UUID orderId = UUID.randomUUID();
          sql.execute(
              "insert into outbound_order(id,tenant_id,order_code,source_type,source_external_ref,"
                  + "warehouse_id,carrier_code,status,created_by_user_id)"
                  + " values(?,?,?,'SALES',?,?,'SF','PENDING_PICKING',?)",
              orderId,
              tenant,
              code,
              "SO-" + code,
              wh(tenant),
              user);
          BigDecimal total = BigDecimal.ZERO;
          int lineNo = 0;
          for (UUID skuId : skuIds) {
            lineNo++;
            sql.execute(
                "insert into outbound_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,"
                    + "sku_name,quantity) values(?,?,?,?,?,?,?,cast(? as numeric))",
                UUID.randomUUID(),
                tenant,
                orderId,
                lineNo,
                skuId,
                codes.get(skuId),
                "商品" + lineNo,
                quantity);
            total = total.add(new BigDecimal(quantity));
          }
          sql.execute(
              "insert into outbound_pick_request(id,tenant_id,order_id,line_count,"
                  + "total_piece_count,requested_by_user_id,requested_at)"
                  + " values(?,?,?,?,cast(? as numeric),?,?::timestamptz)",
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

  /** 把出库单强行推回「待拣」，用来验证「不在待复核开不了单」。 */
  void backToPendingPicking(UUID tenant, UUID orderId) {
    asWrite(
        tenant,
        () -> {
          sql.execute(
              "delete from pick_task where tenant_id=? and outbound_order_id=?", tenant, orderId);
          sql.execute(
              "update outbound_order set status='PENDING_PICKING' where tenant_id=? and id=?",
              tenant,
              orderId);
          return null;
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

  /**
   * 把拣货任务推到「已完成」（出库单 → 待复核）。
   *
   * @param serialIdsByIndex 哪些行用序列号逐件拣（行序号从 0 起；null = 全部按件��拣）
   */
  void finishTask(UUID tenant, UUID taskId, Map<Integer, List<UUID>> serialIdsByIndex) {
    var lines = asWrite(tenant, () -> pickTasks.detail(taskId).lines());
    asWrite(
        tenant,
        () -> pickTasks.assign(taskId, new PickTaskModels.PickTaskAssignInput(user, null), user));
    for (int i = 0; i < lines.size(); i++) {
      var line = lines.get(i);
      List<UUID> serials =
          serialIdsByIndex == null ? null : serialIdsByIndex.getOrDefault(i, List.of());
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
                      serials,
                      null,
                      null),
                  user));
    }
    asWrite(tenant, () -> pickTasks.complete(taskId, user));
  }

  /** 建一张「拣完待复核」的出库单 + 拣货任务。 */
  record Picked(UUID orderId, UUID taskId) {}

  Picked picked(
      UUID tenant, String code, String quantity, Map<Integer, List<UUID>> serials, UUID... skuIds) {
    UUID orderId = outbound(tenant, code, quantity, skuIds);
    UUID taskId = pickTask(tenant, orderId);
    finishTask(tenant, taskId, serials);
    return new Picked(orderId, taskId);
  }

  RecountCreated openSession(UUID tenant, UUID orderId, UUID ruleId) {
    return asWrite(
        tenant, () -> service.open(new RecountOpenInput(orderId, ruleId, user, null), user));
  }

  RecountScanRow scan(UUID tenant, UUID sessionId, String type, String value, String at) {
    return asWrite(
        tenant,
        () ->
            service.scan(sessionId.toString(), new RecountScanInput(type, value, at, null), user));
  }

  RecountRuleRow rule(UUID tenant, String code, boolean requireSerial, String threshold) {
    return asWrite(
        tenant,
        () ->
            service.createRule(
                new RecountRuleInput(
                    code,
                    "规则 " + code,
                    wh(tenant),
                    requireSerial,
                    threshold == null ? null : new BigDecimal(threshold),
                    "开箱备注"),
                user));
  }

  /** 用超级用户直连跑一段 SQL —— 用来证明「绕开服务层也过不去」。 */
  void superuserSql(String... statements) throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      for (var statement : statements) s.execute(statement);
    }
  }

  long balanceOnHand(UUID tenant, UUID skuId) {
    return as(
        tenant,
        () ->
            sql.fetchOne(
                    "select on_hand from inventory_balance where tenant_id"
                        + " = current_setting('app.current_tenant', true)::uuid"
                        + " and sku_id = ? and warehouse_id = ?",
                    skuId,
                    wh(tenant))
                .get("on_hand", BigDecimal.class)
                .longValue());
  }

  long reservedOnHand(UUID tenant, UUID skuId) {
    return as(
        tenant,
        () ->
            sql.fetchOne(
                    "select reserved from inventory_balance where tenant_id"
                        + " = current_setting('app.current_tenant', true)::uuid"
                        + " and sku_id = ? and warehouse_id = ?",
                    skuId,
                    wh(tenant))
                .get("reserved", BigDecimal.class)
                .longValue());
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

  long bucketCount(UUID tenant, UUID skuId) {
    return as(
        tenant,
        () ->
            sql.fetchOne(
                    "select count(*) as c from stock_bucket where tenant_id"
                        + " = current_setting('app.current_tenant', true)::uuid and sku_id=?",
                    skuId)
                .get("c", Long.class));
  }

  String outboundStatus(UUID tenant, UUID orderId) {
    return as(
        tenant,
        () ->
            sql.fetchOne(
                    "select status from outbound_order where tenant_id"
                        + " = current_setting('app.current_tenant', true)::uuid and id=?",
                    orderId)
                .get("status", String.class));
  }

  // ================================================================ 能力声明

  @Test
  void capabilitiesReportTheLastChanceGateAndTheHonestHighValueProxy() {
    var caps = service.capabilities();
    assertThat(caps.refType()).isEqualTo("RECOUNT_SCAN");
    assertThat(caps.serialVerificationLive()).isTrue();
    assertThat(caps.setEqualityCheck()).isTrue();
    assertThat(caps.duplicateScanDetected()).isTrue();
    assertThat(caps.missingWrongExtraDetected()).isTrue();
    assertThat(caps.openBoxSupported()).isTrue();
    assertThat(caps.escalationSupported()).isTrue();
    // 「复核是错发拦截的最后机会」：通过有前提，且下游尚未交付要如实报 false。
    assertThat(caps.passesOnlyWhenClean()).isTrue();
    assertThat(caps.lastChanceGate()).isTrue();
    assertThat(caps.packingHandoffLive()).isFalse();
    assertThat(caps.downstreamModule()).isEqualTo("8.3.3.2 打包与称重");
    // 复核是只读核对动作：一件货都不搬，预占也不释放。
    assertThat(caps.inventoryUnaffected()).isFalse();
    assertThat(caps.inventoryOwnerModule()).isEqualTo("8.3.2.1 拣货任务（复核只读不搬，货与预占都还在打包区）");
    assertThat(caps.statuses()).containsExactly("OPEN", "PASSED", "FAILED", "CANCELLED");
    assertThat(caps.exceptionKinds())
        .contains(
            "MISSING_ITEM",
            "WRONG_ITEM",
            "EXTRA_ITEM",
            "DUPLICATE_SCAN",
            "SERIAL_MISMATCH",
            "UNKNOWN_BARCODE");
    // 「高价值」只能取件数 —— 口径必须写在返回里，而不是藏在列名里假装是金额。
    assertThat(caps.highValueBasis())
        .contains("出库单上没有金额列")
        .contains("high_value_quantity_threshold")
        .contains("不是金额");
    assertThat(caps.verifyBasis()).contains("逐行以订单明细").contains("集合相等");
  }

  // ================================================================ 复核规则

  @Test
  void rulesRequireCodeNameAndPositiveThreshold() {
    assertThat(
            catchThrowable(
                () ->
                    asWrite(
                        a,
                        () ->
                            service.createRule(
                                new RecountRuleInput("", "名字", wh(a), true, null, null), user))))
        .hasMessageContaining("ruleCode");
    assertThat(
            catchThrowable(
                () ->
                    asWrite(
                        a,
                        () ->
                            service.createRule(
                                new RecountRuleInput("R1", "  ", wh(a), true, null, null), user))))
        .hasMessageContaining("name");
    assertThat(
            catchThrowable(
                () ->
                    asWrite(
                        a,
                        () ->
                            service.createRule(
                                new RecountRuleInput(
                                    "R1", "名字", wh(a), true, new BigDecimal("0"), null),
                                user))))
        .hasMessageContaining("highValueQuantityThreshold");
    // 重复编码
    rule(a, "R-DUP", true, null);
    assertThat(catchThrowable(() -> rule(a, "R-DUP", true, null))).hasMessageContaining("已存在");
    // 不属于本租户的仓
    UUID foreignWarehouse = as(b, () -> wh(b));
    assertThat(
            catchThrowable(
                () ->
                    asWrite(
                        a,
                        () ->
                            service.createRule(
                                new RecountRuleInput(
                                    "R-X", "名字", foreignWarehouse, true, null, null),
                                user))))
        .hasMessageContaining("不存在");
  }

  @Test
  void retiredRuleCannotBeRevivedAndReasonIsRequired() {
    var created = rule(a, "R-RETIRE", true, "10");
    String ruleId = created.id().toString();
    assertThat(
            catchThrowable(
                () ->
                    asWrite(
                        a, () -> service.retireRule(ruleId, new RecountRetireInput("  "), user))))
        .hasMessageContaining("原因");
    var retired =
        asWrite(a, () -> service.retireRule(ruleId, new RecountRetireInput("口径改了"), user));
    assertThat(retired.status()).isEqualTo("RETIRED");
    assertThat(retired.retireReason()).isEqualTo("口径改了");
    // 服务层挡一次
    assertThat(
            catchThrowable(
                () ->
                    asWrite(
                        a, () -> service.retireRule(ruleId, new RecountRetireInput("再来一次"), user))))
        .hasMessageContaining("不能复活");
    // 超级用户直连也挡一次（23514）
    assertThatThrownBy(
            () ->
                superuserSql(
                    "update recount_rule set status='ACTIVE', retired_at=null, retire_reason=null"
                        + " where rule_code='R-RETIRE' and tenant_id='%s'".formatted(a)))
        .hasMessageContaining("停用后不能复活");
  }

  UUID ruleOf(UUID tenant, String code) {
    return as(
        tenant,
        () ->
            sql.fetchOne(
                    "select id from recount_rule where tenant_id"
                        + " = current_setting('app.current_tenant', true)::uuid and rule_code=?",
                    code)
                .get("id", UUID.class));
  }

  // ================================================================ 开单三道闸

  @Test
  void openRequiresOutboundInRecheckPickTaskCompletedAndNonEmptyBaseline() {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");

    // (1) 出库单不在「待复核」
    UUID notPicked = outbound(a, "OB-NP", "3", sku(a, 1));
    assertThat(catchThrowable(() -> openSession(a, notPicked, null))).hasMessageContaining("待复核");

    // (2) 拣货任务没完成：出库单已经在「待复核」，但工单被直连压回「拣货中」——
    //     这是「订单状态对了、货其实还没到打包区」的真实情形，核了也核不准。
    UUID inFlight = outbound(a, "OB-IF", "3", sku(a, 1));
    UUID inFlightTask = pickTask(a, inFlight);
    asWrite(
        a,
        () -> {
          sql.execute(
              "update outbound_order set status='PENDING_RECHECK' where tenant_id=? and id=?",
              a,
              inFlight);
          sql.execute(
              "update pick_task set status='IN_PROGRESS', assignee_user_id=?,"
                  + " started_at=coalesce(started_at, now()) where tenant_id=? and id=?",
              user,
              a,
              inFlightTask);
          return null;
        });
    assertThat(catchThrowable(() -> openSession(a, inFlight, null))).hasMessageContaining("已完成");

    // (3) 正常路径能开
    var ready = picked(a, "OB-OK", "3", null, sku(a, 1));
    var created = openSession(a, ready.orderId(), null);
    assertThat(created.sessionCode()).startsWith("RS-");
    assertThat(created.status()).isEqualTo("OPEN");
    assertThat(created.baselineLineCount()).isEqualTo(1);
    assertThat(created.baselineQuantity()).isEqualTo("3");
    // 没配规则时口径必须如实说「无规则」，不假装有一条
    assertThat(created.verifyBasis()).contains("本次无复核规则");
  }

  @Test
  void oneOpenSessionPerOutboundOrder() {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    var ready = picked(a, "OB-ONE", "2", null, sku(a, 1));
    openSession(a, ready.orderId(), null);
    assertThat(catchThrowable(() -> openSession(a, ready.orderId(), null)))
        .hasMessageContaining("复核单");
    // 取消之后可以再开（返工轮次是**新会话**，不是把第一轮改成通过）
    var first =
        as(
                a,
                () ->
                    service.page(
                        null,
                        ready.orderId(),
                        "OPEN",
                        null,
                        false,
                        false,
                        null,
                        "STARTED_AT",
                        0,
                        50))
            .items()
            .get(0);
    asWrite(a, () -> service.cancel(first.id().toString(), new RecountCancelInput("先重拣"), user));
    var second = openSession(a, ready.orderId(), null);
    assertThat(second.id()).isNotEqualTo(first.id());
  }

  // ================================================================ 基线冻结

  @Test
  void baselineFreezesOrderLinesAndKeepsPickedQuantitySeparate() {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "50");
    bucket(a, la.a1(), sku(a, 2), "50");
    var ready = picked(a, "OB-FREEZE", "4", null, sku(a, 1), sku(a, 2));
    var session = openSession(a, ready.orderId(), null);

    var detail = as(a, () -> service.detail(session.id().toString()));
    assertThat(detail.lines()).hasSize(2);
    assertThat(detail.lines().get(0).lineNo()).isEqualTo(1);
    assertThat(detail.lines().get(0).skuCode()).isEqualTo("SKU-1");
    // 应发来自订单明细，实拣来自 8.3.2.1：两个数分开给，界面才分得清
    // 「拣漏了」和「复核没扫到」。
    assertThat(detail.lines().get(0).expectedQuantity()).isEqualTo("4");
    assertThat(detail.lines().get(0).pickedQuantity()).isEqualTo("4");
    assertThat(detail.verdict().baselineQuantity()).isEqualTo("8");

    // 上游实拣在任务 COMPLETED 之后**改不动**（8.3.2.1 的守卫）——
    // 这正是复核基线可以放心冻结读数的前提：源头被冻结了。
    UUID lineId =
        as(
            a,
            () ->
                sql.fetchOne(
                        "select id from pick_task_line where tenant_id=current_setting('app.current_tenant',true)::uuid"
                            + " limit 1")
                    .get("id", UUID.class));
    assertThatThrownBy(
            () ->
                asWrite(
                    a,
                    () -> {
                      sql.execute(
                          "update pick_task_line set picked_quantity=3 where tenant_id=? and id=?",
                          a,
                          lineId);
                      return null;
                    }))
        .hasMessageContaining("不可再改");

    // 基线里的实拣读数因此是稳定的冻结值
    var again = as(a, () -> service.detail(session.id().toString()));
    assertThat(again.lines().get(0).pickedQuantity()).isEqualTo("4");
  }

  // ================================================================ 扫描：条码与多件

  @Test
  void barcodeScanMatchesLineAndUnknownBarcodeRaisesException() {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    bucket(a, la.a1(), sku(a, 2), "100");
    var ready = picked(a, "OB-BC", "2", null, sku(a, 1), sku(a, 2));
    var session = openSession(a, ready.orderId(), null);

    // 扫单号 1 两次（应发 2 件）→ 命中；SKU-2 只扫一次 → 结算时才报漏件
    var s1 = scan(a, session.id(), "BARCODE", "SKU-1", null);
    assertThat(s1.verdict()).isEqualTo("MATCHED");
    assertThat(s1.outboundLineId()).isNotNull();
    assertThat(s1.scanOccurrence()).isEqualTo(1);
    scan(a, session.id(), "BARCODE", "SKU-1", null);
    scan(a, session.id(), "BARCODE", "SKU-2", null);

    // 扫一个本单压根没有的码 → 多件（EXTRA_ITEM）
    var extra = scan(a, session.id(), "BARCODE", "SKU-7", null);
    assertThat(extra.verdict()).isEqualTo("WRONG_SKU");
    assertThat(extra.outboundLineId()).isNull();

    var report = asWrite(a, () -> service.settle(session.id().toString(), user));
    assertThat(report.verdict()).isEqualTo("FAILED");
    assertThat(report.matchedLineCount()).isEqualTo(1);
    assertThat(report.mismatchLineCount()).isEqualTo(1);
    assertThat(report.scannedQuantity()).isEqualTo("3");

    var detail = as(a, () -> service.detail(session.id().toString()));
    assertThat(detail.exceptions())
        .extracting(RecountExceptionRow::kind)
        .contains("MISSING_ITEM", "EXTRA_ITEM");
    var missing =
        detail.exceptions().stream()
            .filter(e -> "MISSING_ITEM".equals(e.kind()))
            .findFirst()
            .orElseThrow();
    assertThat(missing.expectedQuantity()).isEqualTo("2");
    assertThat(missing.scannedQuantity()).isEqualTo("1");
  }

  @Test
  void barcodeRescansCountAsMoreUnitsWhileSerialRescansAreDuplicates() throws Exception {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    UUID s1 = serial(a, sku(a, 1), "DUP-1", la.a1());
    UUID s2 = serial(a, sku(a, 1), "DUP-2", la.a1());

    // ---- 条码：同一个码扫 N 遍 = N 件（两台同型号的机器），不是重复件
    var bcReady = picked(a, "OB-DUP-BC", "3", null, sku(a, 1));
    var bcSession = openSession(a, bcReady.orderId(), null);
    assertThat(bcSession.sessionCode()).isNotBlank();
    for (int i = 0; i < 3; i++) {
      var r = scan(a, bcSession.id(), "BARCODE", "SKU-1", null);
      assertThat(r.verdict()).isEqualTo("MATCHED");
      assertThat(r.scanOccurrence()).isEqualTo(i + 1);
    }
    var bcDetail = as(a, () -> service.detail(bcSession.id().toString()));
    assertThat(bcDetail.session().scannedQuantity()).isEqualTo("3");
    assertThat(bcDetail.lines().get(0).lineStatus()).isEqualTo("MATCHED");
    assertThat(bcDetail.exceptions())
        .extracting(RecountExceptionRow::kind)
        .doesNotContain("DUPLICATE_SCAN");

    // ---- 序列号：同一个号扫第二遍是**重复件**，DB 强制 DUPLICATE 且不重复计件
    var ready = picked(a, "OB-DUP", "2", Map.of(0, List.of(s1, s2)), sku(a, 1));
    var session = openSession(a, ready.orderId(), null);

    var first = scan(a, session.id(), "SERIAL", "DUP-1", null);
    assertThat(first.verdict()).isEqualTo("MATCHED");
    assertThat(first.scanOccurrence()).isEqualTo(1);

    var second = scan(a, session.id(), "SERIAL", "DUP-1", null);
    assertThat(second.verdict()).isEqualTo("DUPLICATE");
    assertThat(second.scanOccurrence()).isEqualTo(2);

    var detail = as(a, () -> service.detail(session.id().toString()));
    // 只扫了 1 个不同的号（重扫不重复计件）→ 实扫序列号仍是 1
    assertThat(detail.lines().get(0).scannedSerialCount()).isEqualTo(1);
    assertThat(detail.lines().get(0).scannedQuantity()).isEqualTo("1");
    assertThat(detail.exceptions())
        .extracting(RecountExceptionRow::kind)
        .contains("DUPLICATE_SCAN");

    // 绕过服务层直连插一行 occurrence=1 的重扫：**语句成功但被改写**成
    // occurrence=2 / DUPLICATE —— 判重是 DB 的事，写不出「这是第一次」。
    superuserSql(
        ("insert into recount_scan(id,tenant_id,session_id,scan_type,scanned_value,"
                + "sku_id,serial_id,serial_number,scan_occurrence,quantity,verdict,"
                + "operator_user_id,scanned_at)"
                + " values(gen_random_uuid(),'%s','%s','SERIAL','DUP-1',"
                + "(select sku_id from inventory_serial where serial_number='DUP-1'),"
                + "(select id from inventory_serial where serial_number='DUP-1'),"
                + "'DUP-1',1,1,'MATCHED',gen_random_uuid(),now())")
            .formatted(a, session.id()));
    var afterDirect =
        as(
            a,
            () ->
                sql.fetchOne(
                    "select scan_occurrence, verdict from recount_scan where tenant_id"
                        + " = current_setting('app.current_tenant', true)::uuid"
                        + " and session_id=? and scan_type='SERIAL' and scanned_value='DUP-1'"
                        + " order by scan_occurrence desc limit 1",
                    session.id()));
    assertThat(afterDirect.get("scan_occurrence", Integer.class)).isEqualTo(3);
    assertThat(afterDirect.get("verdict", String.class)).isEqualTo("DUPLICATE");
  }

  // ================================================================ 扫描：序列号逐件

  @Test
  void serialMustMatchAsASetNotJustByCount() {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "50");
    UUID s1 = serial(a, sku(a, 1), "SN-1", la.a1());
    UUID s2 = serial(a, sku(a, 1), "SN-2", la.a1());
    UUID s3 = serial(a, sku(a, 1), "SN-3", la.a1());
    // 一台「不该在这单上」的机器：同一 SKU、别的序列号。
    UUID stranger = serial(a, sku(a, 1), "SN-9", la.a1());

    var ready = picked(a, "OB-SER", "2", Map.of(0, List.of(s1, s2)), sku(a, 1));
    var session = openSession(a, ready.orderId(), null);

    // 实拣登记了 2 个序列号 → 该行进入逐件核对
    var line = as(a, () -> service.detail(session.id().toString())).lines().get(0);
    assertThat(line.requiresSerial()).isTrue();
    assertThat(line.expectedSerialCount()).isEqualTo(2);
    assertThat(line.expectedSerials()).containsExactly("SN-1", "SN-2");

    // **件数相等但序列号集合不等**：扫 SN-1、SN-9 → 数量对得上，仍然不是核过
    scan(a, session.id(), "SERIAL", "SN-1", null);
    scan(a, session.id(), "SERIAL", "SN-9", null);

    var detail = as(a, () -> service.detail(session.id().toString()));
    var after = detail.lines().get(0);
    assertThat(after.scannedQuantity()).isEqualTo("2");
    assertThat(after.scannedSerialCount()).isEqualTo(2);
    assertThat(after.serialMatch()).isFalse();
    assertThat(after.lineStatus()).isEqualTo("MISMATCH");

    // 换成正确的第二个号才核过（正向差集 + 反向差集都空）
    var report = asWrite(a, () -> service.settle(session.id().toString(), user));
    assertThat(report.verdict()).isEqualTo("FAILED");
    assertThat(report.mismatchLineCount()).isEqualTo(1);
    assertThat(report.blockingReasons())
        .anySatisfy(r -> assertThat(r).contains("序列号不符").contains("没核过"));

    // SN-9 被 8.3.2.1 拣给了别的行吗？没有 → 判错件（WRONG_ITEM）
    assertThat(detail.exceptions()).extracting(RecountExceptionRow::kind).contains("WRONG_ITEM");

    // 重新开一张干净的：扫对两个号 → MATCHED
    UUID t1 = serial(a, sku(a, 1), "OK-1", la.a1());
    UUID t2 = serial(a, sku(a, 1), "OK-2", la.a1());
    var ready2 = picked(a, "OB-SER2", "2", Map.of(0, List.of(t1, t2)), sku(a, 1));
    var session2 = openSession(a, ready2.orderId(), null);
    scan(a, session2.id(), "SERIAL", "OK-1", null);
    scan(a, session2.id(), "SERIAL", "OK-2", null);
    var ok = asWrite(a, () -> service.settle(session2.id().toString(), user));
    assertThat(ok.verdict()).isEqualTo("PASSED");
    assertThat(ok.matchedLineCount()).isEqualTo(1);
    assertThat(ok.serialCheckedLineCount()).isEqualTo(1);
    assertThat(stranger).isNotNull();
  }

  @Test
  void serialScanOfUnknownSerialIsRejectedNotTreatedAsPassed() {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "50");
    UUID s1 = serial(a, sku(a, 1), "SN-K1", la.a1());
    var ready = picked(a, "OB-UNK", "1", Map.of(0, List.of(s1)), sku(a, 1));
    var session = openSession(a, ready.orderId(), null);
    assertThat(catchThrowable(() -> scan(a, session.id(), "SERIAL", "SN-NOT-THERE", null)))
        .hasMessageContaining("不在本租户的序列号台账");
  }

  // ================================================================ 完成复核

  @Test
  void completePassesOnlyWhenCleanAndMovesOutboundToPackingNotShipped() {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    var ready = picked(a, "OB-PASS", "2", null, sku(a, 1));
    var session = openSession(a, ready.orderId(), null);
    scan(a, session.id(), "BARCODE", "SKU-1", null);
    scan(a, session.id(), "BARCODE", "SKU-1", null);

    long onHandBefore = balanceOnHand(a, sku(a, 1));
    long reservedBefore = reservedOnHand(a, sku(a, 1));
    long entriesBefore = inventoryEntryCount(a);

    var report = asWrite(a, () -> service.complete(session.id().toString(), user));
    assertThat(report.verdict()).isEqualTo("PASSED");
    assertThat(report.blocking()).isFalse();
    assertThat(report.matchedLineCount()).isEqualTo(1);
    // 通过 = 交给 8.3.3.2「待打包」，**不是已发货**
    assertThat(outboundStatus(a, ready.orderId())).isEqualTo("PENDING_PACKING");
    // 复核是只读核对：库存一行都没动，预占没释放，也没写分录
    assertThat(balanceOnHand(a, sku(a, 1))).isEqualTo(onHandBefore);
    assertThat(reservedOnHand(a, sku(a, 1))).isEqualTo(reservedBefore);
    assertThat(inventoryEntryCount(a)).isEqualTo(entriesBefore);

    var detail = as(a, () -> service.detail(session.id().toString()));
    assertThat(detail.session().status()).isEqualTo("PASSED");
    assertThat(detail.timeline())
        .extracting(RecountEventRow::eventType)
        .contains("OPENED", "SCANNED", "PASSED");
  }

  @Test
  void passingWithCleanRowsButPendingExceptionIsRejectedByTheDatabaseToo() throws Exception {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    var ready = picked(a, "OB-GATE", "2", null, sku(a, 1));
    var session = openSession(a, ready.orderId(), null);
    scan(a, session.id(), "BARCODE", "SKU-1", null);
    scan(a, session.id(), "BARCODE", "SKU-1", null);

    // 人为插一条未处置异常：**每一行都 MATCHED**，但「最后机会」闸必须仍然拦住。
    UUID exId = UUID.randomUUID();
    asWrite(
        a,
        () -> {
          sql.execute(
              "insert into recount_exception(id,tenant_id,session_id,kind,sku_code,sku_name,"
                  + "detail,raised_at) values(?,?,?,'WRONG_ITEM','SKU-X','商品X',"
                  + "'测试：行都核过了但还有异常没处理',?::timestamptz)",
              exId,
              a,
              session.id(),
              OffsetDateTime.now());
          return null;
        });
    assertThat(as(a, () -> service.detail(session.id().toString())).session().matchedLineCount())
        .isEqualTo(1);

    // **超级用户直连强行把复核单改成 PASSED** —— V117 的守卫拒（23514）。
    // 这正是「先放行再补异常」那条路被关死的地方。
    assertThatThrownBy(
            () ->
                superuserSql(
                    "update recount_session set status='PASSED', completed_at=now()"
                        + " where tenant_id='%s' and id='%s'".formatted(a, session.id())))
        .hasMessageContaining("异常没人处理");

    // 处置之前，结算结论已经是「异常」且明确指出拦路的是什么
    var report = asWrite(a, () -> service.settle(session.id().toString(), user));
    assertThat(report.verdict()).isEqualTo("FAILED");
    assertThat(report.blockingReasons()).anySatisfy(r -> assertThat(r).contains("异常没人处理"));
    assertThat(report.pendingExceptionCount()).isEqualTo(1);

    // 处置掉那条异常之后，行本身是干净的 → 真的能过，出库单交到待打包
    asWrite(
        a,
        () ->
            service.resolveException(
                session.id().toString(),
                exId.toString(),
                new RecountResolveInput("IGNORED", "开箱确认是包装附赠品"),
                user));
    assertThat(asWrite(a, () -> service.complete(session.id().toString(), user)).verdict())
        .isEqualTo("PASSED");
    assertThat(outboundStatus(a, ready.orderId())).isEqualTo("PENDING_PACKING");
  }

  @Test
  void lineStatusCannotBeFabricatedByDirectSqlAndUncleanRowsBlockPassing() throws Exception {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    var ready = picked(a, "OB-DIR", "3", null, sku(a, 1));
    var session = openSession(a, ready.orderId(), null);
    scan(a, session.id(), "BARCODE", "SKU-1", null); // 只扫 1 件，应发 3 件

    // 超级用户直连把 line_status 刷成 MATCHED：**语句会成功，但触发器当场改回 MISSING** ——
    // 行状态是派生的、不是可写的列。这比「报错拒掉」更能说明它是真约束：写进去的值留不下来。
    superuserSql(
        "update recount_line set line_status='MATCHED' where tenant_id='%s' and session_id='%s'"
            .formatted(a, session.id()));
    assertThat(as(a, () -> service.detail(session.id().toString())).lines().get(0).lineStatus())
        .isEqualTo("MISSING");

    // 于是「强行判通过」被 V117 的守卫拒（23514）
    assertThatThrownBy(
            () ->
                superuserSql(
                    "update recount_session set status='PASSED', completed_at=now()"
                        + " where tenant_id='%s' and id='%s'".formatted(a, session.id())))
        .hasMessageContaining("没核过");
  }

  // ================================================================ 开箱复核

  @Test
  void openBoxRequiresReasonAndHighValueOnlySuggests() {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    var r = rule(a, "R-HV", true, "3");

    // 应发 5 件 ≥ 阈值 3 → 系统提示开箱，但**不自动开箱**
    var ready = picked(a, "OB-HV", "5", null, sku(a, 1));
    var session = openSession(a, ready.orderId(), r.id());
    assertThat(session.openBox()).isFalse();
    assertThat(as(a, () -> service.detail(session.id().toString())).verdict().openBoxRequired())
        .isTrue();
    assertThat(as(a, () -> service.detail(session.id().toString())).timeline())
        .extracting(RecountEventRow::detail)
        .anySatisfy(d -> assertThat(d).contains("建议开箱复核"));

    // 没写原因不许开箱
    assertThat(
            catchThrowable(
                () ->
                    asWrite(
                        a,
                        () ->
                            service.openBox(
                                session.id().toString(), new RecountOpenBoxInput("  "), user))))
        .hasMessageContaining("原因");

    var opened =
        asWrite(
            a,
            () ->
                service.openBox(
                    session.id().toString(), new RecountOpenBoxInput("高价值，开箱确认"), user));
    assertThat(opened.openBox()).isTrue();
    assertThat(opened.openBoxReason()).isEqualTo("高价值，开箱确认");

    // 低于阈值的单不要求开箱
    var small = picked(a, "OB-SMALL", "1", null, sku(a, 1));
    var smallSession = openSession(a, small.orderId(), r.id());
    assertThat(
            as(a, () -> service.detail(smallSession.id().toString())).verdict().openBoxRequired())
        .isFalse();
  }

  // ================================================================ 异常处置

  @Test
  void escalateMovesAllPendingExceptionsToManualAndRequiresNote() {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    var ready = picked(a, "OB-ESC", "3", null, sku(a, 1));
    var session = openSession(a, ready.orderId(), null);
    scan(a, session.id(), "BARCODE", "SKU-1", null); // 3 应发 / 1 实扫 → 漏件
    asWrite(a, () -> service.settle(session.id().toString(), user));

    assertThat(
            catchThrowable(
                () ->
                    asWrite(
                        a,
                        () ->
                            service.escalate(
                                session.id().toString(), new RecountEscalateInput(" "), user))))
        .hasMessageContaining("处理意见");

    asWrite(
        a,
        () ->
            service.escalate(
                session.id().toString(), new RecountEscalateInput("转仓库主管人工开箱核对"), user));
    var detail = as(a, () -> service.detail(session.id().toString()));
    assertThat(detail.exceptions())
        .allSatisfy(
            e -> {
              assertThat(e.resolution()).isEqualTo("MANUAL");
              assertThat(e.manualNote()).isEqualTo("转仓库主管人工开箱核对");
            });
    assertThat(detail.session().pendingExceptionCount()).isZero();

    // **转人工不等于放行**：处置意见本身不是「这件货没问题」——
    // 漏件那一行仍然没核过，最后机会闸照样拦住。转人工清的是「谁来处理」，
    // 不是「这一行核过了」。
    var report = asWrite(a, () -> service.complete(session.id().toString(), user));
    assertThat(report.verdict()).isEqualTo("FAILED");
    assertThat(report.blockingReasons()).anySatisfy(r -> assertThat(r).contains("1 行没核过"));
    assertThat(outboundStatus(a, ready.orderId())).isEqualTo("PENDING_RECHECK");

    // 结算不会因为上一条已转人工就再报一遍同样的漏件（一行一单只报一次）
    assertThat(as(a, () -> service.detail(session.id().toString())).exceptions())
        .filteredOn(e -> "MISSING_ITEM".equals(e.kind()))
        .hasSize(1);
  }

  @Test
  void exceptionCanOnlyBeResolvedOnceAndRequiresNote() {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    var ready = picked(a, "OB-RES", "3", null, sku(a, 1));
    var session = openSession(a, ready.orderId(), null);
    scan(a, session.id(), "BARCODE", "SKU-1", null);
    asWrite(a, () -> service.settle(session.id().toString(), user));

    var ex = as(a, () -> service.detail(session.id().toString())).exceptions().get(0);
    String sid = session.id().toString();

    assertThat(
            catchThrowable(
                () ->
                    asWrite(
                        a,
                        () ->
                            service.resolveException(
                                sid,
                                ex.id().toString(),
                                new RecountResolveInput("CORRECTED", " "),
                                user))))
        .hasMessageContaining("处理意见");

    var resolved =
        asWrite(
            a,
            () ->
                service.resolveException(
                    sid,
                    ex.id().toString(),
                    new RecountResolveInput("CORRECTED", "已退回拣货补了一件"),
                    user));
    assertThat(resolved.resolution()).isEqualTo("CORRECTED");

    // 服务层挡第二次
    assertThat(
            catchThrowable(
                () ->
                    asWrite(
                        a,
                        () ->
                            service.resolveException(
                                sid,
                                ex.id().toString(),
                                new RecountResolveInput("IGNORED", "反悔"),
                                user))))
        .hasMessageContaining("已经处置过");
    // 超级用户直连也挡第二次（23514）
    assertThatThrownBy(
            () ->
                superuserSql(
                    "update recount_exception set resolution='IGNORED' where id='%s'"
                        .formatted(ex.id())))
        .hasMessageContaining("事实只能处置一次");
  }

  @Test
  void cancelRequiresReasonAndNeverTouchesInventory() {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    var ready = picked(a, "OB-CANCEL", "2", null, sku(a, 1));
    var session = openSession(a, ready.orderId(), null);
    long entriesBefore = inventoryEntryCount(a);

    assertThat(
            catchThrowable(
                () ->
                    asWrite(
                        a,
                        () ->
                            service.cancel(
                                session.id().toString(), new RecountCancelInput(""), user))))
        .hasMessageContaining("原因");
    var cancelled =
        asWrite(
            a, () -> service.cancel(session.id().toString(), new RecountCancelInput("先重拣"), user));
    assertThat(cancelled.status()).isEqualTo("CANCELLED");
    assertThat(inventoryEntryCount(a)).isEqualTo(entriesBefore);
    // 取消后不许再扫
    assertThat(catchThrowable(() -> scan(a, session.id(), "BARCODE", "SKU-1", null)))
        .hasMessageContaining("已取消");
  }

  // ================================================================ 时刻

  @Test
  void scanTimestampRoundTripsWithItsOffsetAndIsNotShifted() {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    var ready = picked(a, "OB-TIME", "2", null, sku(a, 1));
    var session = openSession(a, ready.orderId(), null);

    // 东八区当天的 09:30 —— 若用 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s
    var row = scan(a, session.id(), "BARCODE", "SKU-1", "2026-03-14T09:30:00+08:00");
    assertThat(row.scannedAt().withOffsetSameInstant(java.time.ZoneOffset.ofHours(8)).getHour())
        .isEqualTo(9);
    // TIMESTAMPTZ preserves the instant; its returned offset depends on the database/JVM zone.
    assertThat(row.scannedAt().toInstant().toString()).startsWith("2026-03-14T01:30:00");

    // 事件流水里的时刻同样写的是作业时刻，不是 DB 的 now()
    var scanned =
        as(a, () -> service.detail(session.id().toString())).timeline().stream()
            .filter(e -> "SCANNED".equals(e.eventType()))
            .findFirst()
            .orElseThrow();
    assertThat(scanned.occurredAt().toInstant().toString()).startsWith("2026-03-14T01:30:00");
  }

  // ================================================================ 列表与汇总

  @Test
  void listIsRealPaginatedFilteredAndSummariesIgnoreFilters() {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    bucket(a, la.a1(), sku(a, 2), "100");
    var r1 = picked(a, "OB-L1", "2", null, sku(a, 1));
    var r2 = picked(a, "OB-L2", "3", null, sku(a, 2));
    var s1 = openSession(a, r1.orderId(), null);
    var s2 = openSession(a, r2.orderId(), null);
    scan(a, s2.id(), "BARCODE", "SKU-2", null);
    asWrite(a, () -> service.settle(s2.id().toString(), user)); // 异常在结算时才产生

    var all =
        as(a, () -> service.page(null, null, null, null, false, false, null, "STARTED_AT", 0, 50));
    assertThat(all.total()).isEqualTo(2);
    assertThat(all.summary().totalSessions()).isEqualTo(2);
    assertThat(all.summary().openSessions()).isEqualTo(2);

    // 真分页
    var firstPage =
        as(a, () -> service.page(null, null, null, null, false, false, null, "SESSION_CODE", 0, 1));
    assertThat(firstPage.items()).hasSize(1);
    assertThat(firstPage.total()).isEqualTo(2);

    // 真筛选：关键字能按出库单号命中
    var byKeyword =
        as(
            a,
            () -> service.page(null, null, null, null, false, false, "OB-L1", "STARTED_AT", 0, 50));
    assertThat(byKeyword.total()).isEqualTo(1);
    assertThat(byKeyword.items().get(0).outboundOrderCode()).isEqualTo("OB-L1");

    // 「只有异常」的筛选真的按异常数走
    var withEx =
        as(a, () -> service.page(null, null, null, null, false, true, null, "STARTED_AT", 0, 50));
    assertThat(withEx.total()).isEqualTo(1);
    assertThat(withEx.items().get(0).id()).isEqualTo(s2.id());

    // 筛选只影响行，不影响合计
    assertThat(byKeyword.summary().totalSessions()).isEqualTo(2);

    // 排序白名单
    assertThat(
            catchThrowable(
                () ->
                    as(
                        a,
                        () ->
                            service.page(
                                null, null, null, null, false, false, null, "DROP TABLE", 0, 50))))
        .hasMessageContaining("sort");
    // 状态白名单
    assertThat(
            catchThrowable(
                () ->
                    as(
                        a,
                        () ->
                            service.page(
                                null,
                                null,
                                "NOPE",
                                null,
                                false,
                                false,
                                null,
                                "STARTED_AT",
                                0,
                                50))))
        .hasMessageContaining("status");
    // 超大 size 被夹住，不假装分页不存在
    var capped =
        as(
            a,
            () ->
                service.page(null, null, null, null, false, false, null, "STARTED_AT", 0, 100000));
    assertThat(capped.size()).isEqualTo(200);
    assertThat(s1.id()).isNotEqualTo(s2.id());
    assertThat(r1.orderId()).isNotEqualTo(r2.orderId());
  }

  // ================================================================ 多租户三断言

  @Test
  void twoTenantsCannotSeeEachOther() {
    Layout la = layout(a);
    Layout lb = layout(b);
    bucket(a, la.a1(), sku(a, 1), "100");
    bucket(b, lb.a1(), sku(b, 1), "100");
    var ra = picked(a, "OB-ISO-A", "2", null, sku(a, 1));
    var rb = picked(b, "OB-ISO-B", "2", null, sku(b, 1));
    var sa = openSession(a, ra.orderId(), null);
    var sb = openSession(b, rb.orderId(), null);
    rule(a, "R-ISO-A", true, null);

    // B 用 A 的复核单 id 取详情 → 404
    assertThat(catchThrowable(() -> as(b, () -> service.detail(sa.id().toString()))))
        .hasMessageContaining("不存在");
    // B 扫 A 的复核单 → 404（写路径同样隔离）
    assertThat(catchThrowable(() -> scan(b, sa.id(), "BARCODE", "SKU-1", null)))
        .hasMessageContaining("不存在");
    // B 完成 A 的复核单 → 404
    assertThat(catchThrowable(() -> asWrite(b, () -> service.complete(sa.id().toString(), user))))
        .hasMessageContaining("不存在");
    // B 也看不到 A 的规则。
    // **A 的规则 id 必须先在 A 的上下文里取好**：在 as(b, ...) 里嵌一层 as(a, ...)
    // 会把租户上下文绑两次，拿到的是外层那条连接上的读数（null）——
    // 那时断言测的是嵌套 run，不是租户隔离。
    String aRuleId = ruleOf(a, "R-ISO-A").toString();
    assertThat(catchThrowable(() -> as(b, () -> service.rule(aRuleId))))
        .hasMessageContaining("不存在");
    // B 的列表里只有自己那一单
    var bList =
        as(b, () -> service.page(null, null, null, null, false, false, null, "STARTED_AT", 0, 50));
    assertThat(bList.total()).isEqualTo(1);
    assertThat(bList.items().get(0).id()).isEqualTo(sb.id());
    // B 开不了 A 的出库单的复核单（出库单不在 B 的可见范围）
    assertThat(catchThrowable(() -> openSession(b, ra.orderId(), null)))
        .hasMessageContaining("不存在");
  }

  @Test
  void forgedTenantHeaderIsIgnored() throws Exception {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    var ra = picked(a, "OB-FT", "2", null, sku(a, 1));
    var sa = openSession(a, ra.orderId(), null);

    // 伪造 X-Tenant-Id 头：代理忽略它，身份由 Authorization 解析。
    // 连身份头也伪造时结果是 401，而不是「换成 B 的租户」。
    var forged =
        http.perform(
                get("/warehouse/recount-scans/" + sa.id())
                    .header("Authorization", "Bearer forged")
                    .header("X-Tenant-Id", b.toString()))
            .andReturn()
            .getResponse();
    assertThat(forged.getStatus()).isEqualTo(401);

    // 带上真实身份但伪造租户头：看到的仍然只有 A 自己那一张
    http.perform(
            get("/warehouse/recount-scans")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].sessionCode").value(sa.sessionCode()));
  }

  @Test
  void pooledConnectionDoesNotLeakTenantAcrossReturns() {
    Layout la = layout(a);
    Layout lb = layout(b);
    bucket(a, la.a1(), sku(a, 1), "100");
    bucket(b, lb.a1(), sku(b, 1), "100");
    var ra = picked(a, "OB-POOL-A", "2", null, sku(a, 1));
    var rb = picked(b, "OB-POOL-B", "2", null, sku(b, 1));
    var sa = openSession(a, ra.orderId(), null);
    var sb = openSession(b, rb.orderId(), null);

    // A → B → A 三轮：连接池只有 1 条连接，每次归还后都可能被下一个人拿到。
    for (int round = 0; round < 3; round++) {
      assertThat(
              as(
                      a,
                      () ->
                          service.page(
                              null, null, null, null, false, false, null, "SESSION_CODE", 0, 50))
                  .items()
                  .get(0)
                  .id())
          .isEqualTo(sa.id());
      assertThat(
              as(
                      b,
                      () ->
                          service.page(
                              null, null, null, null, false, false, null, "SESSION_CODE", 0, 50))
                  .items()
                  .get(0)
                  .id())
          .isEqualTo(sb.id());
      assertThat(as(a, () -> service.detail(sa.id().toString())).session().sessionCode())
          .isEqualTo(sa.sessionCode());
      assertThat(as(b, () -> service.detail(sb.id().toString())).session().sessionCode())
          .isEqualTo(sb.sessionCode());
      assertThat(as(a, () -> service.rules(null, null, null, 0, 50)).total()).isZero();
    }
  }

  @Test
  void allSixTablesForceRowLevelSecurity() {
    var forced =
        as(
            a,
            () ->
                sql
                    .fetch(
                        "select relname, relrowsecurity, relforcerowsecurity from pg_class"
                            + " where relname in ('recount_rule','recount_session','recount_line',"
                            + " 'recount_scan','recount_exception','recount_event') order by relname")
                    .stream()
                    .map(
                        r ->
                            r.get("relname", String.class)
                                + ":"
                                + r.get("relrowsecurity", Boolean.class)
                                + "/"
                                + r.get("relforcerowsecurity", Boolean.class))
                    .toList());
    assertThat(forced).hasSize(6);
    assertThat(forced).allMatch(s -> s.endsWith("true/true"));
  }

  // ================================================================ HTTP 面

  @Test
  void httpEndpointsEnforcePermissionsAndExposeTheRealContract() throws Exception {
    Layout la = layout(a);
    bucket(a, la.a1(), sku(a, 1), "100");
    var ready = picked(a, "OB-H", "2", null, sku(a, 1));

    http.perform(get("/warehouse/recount-scans/capabilities")).andExpect(status().isUnauthorized());
    http.perform(
            get("/warehouse/recount-scans/capabilities")
                .header("Authorization", "Bearer no-permission"))
        .andExpect(status().isForbidden());

    http.perform(
            get("/warehouse/recount-scans/capabilities")
                .header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.refType").value("RECOUNT_SCAN"))
        .andExpect(jsonPath("$.lastChanceGate").value(true))
        .andExpect(jsonPath("$.packingHandoffLive").value(false))
        .andExpect(jsonPath("$.statuses").isArray());

    http.perform(get("/warehouse/recount-scans").header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.summary.totalSessions").isNumber());

    var created = openSession(a, ready.orderId(), null);

    // 写端点要写权限
    http.perform(
            post("/warehouse/recount-scans/" + created.id() + "/scans")
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content("{\"scanType\":\"BARCODE\",\"scannedValue\":\"SKU-1\"}"))
        .andExpect(status().isForbidden());

    http.perform(
            post("/warehouse/recount-scans/" + created.id() + "/scans")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"scanType\":\"BARCODE\",\"scannedValue\":\"SKU-1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verdict").value("MATCHED"))
        .andExpect(jsonPath("$.scannedValue").value("SKU-1"));

    http.perform(
            get("/warehouse/recount-scans/" + created.id())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lines[0].expectedQuantity").value("2"))
        .andExpect(jsonPath("$.verdict.verdict").value("FAILED"))
        .andExpect(jsonPath("$.verdict.blocking").value(true));

    // 规则 CRUD
    http.perform(
            post("/warehouse/recount-scans/rules")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"ruleCode\":\"H-R1\",\"name\":\"HTTP 规则\",\"warehouseId\":\""
                        + wh(a)
                        + "\",\"requireSerialCheck\":true,\"highValueQuantityThreshold\":\"5\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ruleCode").value("H-R1"))
        .andExpect(jsonPath("$.highValueQuantityThreshold").value(5.0));

    http.perform(get("/warehouse/recount-scans/rules").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").isNumber());

    // 非法枚举值 → 400 且说清允许值
    http.perform(
            post("/warehouse/recount-scans/" + created.id() + "/scans")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"scanType\":\"QRCODE\",\"scannedValue\":\"SKU-1\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void httpSurfacesNotFoundForMissingAndBadIds() throws Exception {
    http.perform(
            get("/warehouse/recount-scans/" + UUID.randomUUID())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
    http.perform(
            get("/warehouse/recount-scans/not-a-uuid").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isBadRequest());
  }
}
