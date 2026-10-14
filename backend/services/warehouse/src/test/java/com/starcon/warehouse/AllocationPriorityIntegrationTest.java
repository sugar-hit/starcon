package com.starcon.warehouse;

import static com.starcon.warehouse.AllocationPolicyModels.*;
import static com.starcon.warehouse.AllocationPriorityModels.*;
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
 * 7.8.1.2 优先级与保底：DB 级约束、保底裁决的落库形态、以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接： 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>夹具写法与 {@code AllocationPolicyIntegrationTest}（7.8.1.1）完全一致 —— 同样的 Testcontainers、同样的 fake
 * identity server、同样的按租户派生 UUID、同样的 TRUNCATE 清场。
 *
 * <p><b>算式本身不在这里重复验</b>：{@code AllocationPriorityModelTest} 是纯函数单测， 覆盖两遍分配的全部边界。这一个类只验「DB
 * 层真的挡住了什么」与「租户真的隔离了」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AllocationPriorityIntegrationTest {
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

  static final UUID le = idFor(a, 1);
  static final UUID sku = idFor(a, 2);
  static final UUID whOwn = idFor(a, 3);
  static final UUID qSellable = idFor(a, 5);

  /** 店铺是 channel 服务的实体：这里只造逻辑 id，warehouse 侧不建外键也不查它的表。 */
  static final UUID shopOne = UUID.randomUUID();

  static final UUID shopTwo = UUID.randomUUID();
  static final UUID shopThree = UUID.randomUUID();

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-warehouse-floor-" + tenant + "-" + ordinal).getBytes());
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

  @Autowired AllocationPriorityService service;
  @Autowired AllocationPolicyService policies;
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
          "truncate allocation_priority_resolution, allocation_floor, allocation_run_line,"
              + " allocation_run, allocation_shop_sales, allocation_policy_member,"
              + " allocation_policy, inventory_balance, inventory_quality_status, warehouse,"
              + " sku_ref, legal_entity_ref restart identity cascade");
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
                "WH-OWN",
                "自有仓",
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

  // ================================================================ 夹具

  /** 一档策略：全租户兜底 + 无缓冲 + FIXED_PRIORITY，三家店（都由 shop 表逻辑 id 充当）。 */
  AllocationPolicySaved policy(AccessService.Access who) {
    return access.run(
        who,
        "warehouse.inventory.update",
        () ->
            policies.save(
                null,
                new AllocationPolicyInput(
                    "DEFAULT",
                    null,
                    null,
                    "兜底档",
                    "FIXED_PRIORITY",
                    "RATIO",
                    BigDecimal.ZERO,
                    null,
                    null,
                    30,
                    null,
                    "夹具",
                    List.of(
                        member(shopOne, "SELF_FULFILLED", 10),
                        member(shopTwo, "SEMI_CONSIGNMENT", 20),
                        member(shopThree, "FULL_CONSIGNMENT", 30))),
                user));
  }

  AllocationPolicySaved defaultPolicy() {
    return policy(actorA);
  }

  static AllocationPolicyInput.AllocationPolicyMemberInput member(
      UUID shop, String mode, int priority) {
    return new AllocationPolicyInput.AllocationPolicyMemberInput(
        "CHANNEL", shop, "店-" + shop.toString().substring(0, 4), mode, priority, null, true);
  }

  AllocationFloorInput floorInput(
      UUID policyId, UUID shop, String mode, String qty, String ratio, String safety) {
    return new AllocationFloorInput(
        policyId,
        "CHANNEL",
        shop,
        "店-" + shop.toString().substring(0, 4),
        mode,
        ratio == null ? null : new BigDecimal(ratio),
        qty == null ? null : new BigDecimal(qty),
        safety == null ? BigDecimal.ZERO : new BigDecimal(safety),
        null,
        "夹具");
  }

  AllocationFloorSaved saveFloor(AllocationFloorInput input) {
    return saveFloor(null, input, actorA);
  }

  AllocationFloorSaved saveFloor(
      UUID supersedeId, AllocationFloorInput input, AccessService.Access who) {
    return access.run(
        who, "warehouse.inventory.update", () -> service.save(supersedeId, input, user));
  }

  List<PriorityAllocationResult> preview(String available) {
    return preview(available, actorA);
  }

  List<PriorityAllocationResult> preview(String available, AccessService.Access who) {
    return access.run(
        who,
        "warehouse.inventory.read",
        () ->
            service.preview(
                new PriorityAllocationRequest(sku, new BigDecimal(available), null, null), user));
  }

  List<PriorityAllocationResult> runOnce(String available) {
    return access.run(
        actorA,
        "warehouse.inventory.update",
        () ->
            service.runOnce(
                new PriorityAllocationRequest(sku, new BigDecimal(available), null, null), user));
  }

  static PriorityAllocationResult first(List<PriorityAllocationResult> results) {
    return results.get(0);
  }

  static PriorityAllocationLine lineOf(PriorityAllocationResult r, UUID shop) {
    return r.lines().stream().filter(l -> l.referenceId().equals(shop)).findFirst().orElseThrow();
  }

  /** 按店铺定位一档保底。刻意不用行序：created_at 会撞在同一瞬间，排序不保证谁是第 0 行。 */
  static AllocationFloorRow bFloor(AllocationFloorPage page, UUID shop) {
    return page.floors().stream()
        .filter(f -> f.referenceId().equals(shop))
        .findFirst()
        .orElseThrow();
  }

  /** 两条守恒式 + 本模块自己的一条。每组分摊用例都必须过它。 */
  void assertConservation(PriorityAllocationResult r) {
    assertThat(r.bufferQuantity().add(r.allocatableQuantity()))
        .as("守恒式一：缓冲 + 可分配 = 可用")
        .isEqualByComparingTo(r.availableQuantity());
    BigDecimal sum =
        r.lines().stream()
            .map(PriorityAllocationLine::allocatedQty)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(sum).as("Σ各店分摊").isEqualByComparingTo(r.allocatedTotal());
    assertThat(r.allocatedTotal().add(r.unallocatedQuantity()))
        .as("守恒式二：分摊合计 + 未分配 = 可分配")
        .isEqualByComparingTo(r.allocatableQuantity());
    assertThat(r.floorPool().add(r.residualPool()))
        .as("本模块守恒式：保底池 + 余量池 = 可分配量")
        .isEqualByComparingTo(r.allocatableQuantity());
    for (PriorityAllocationLine line : r.lines()) {
      assertThat(line.allocatedQty().signum()).as("分摊额不能为负").isNotNegative();
    }
  }

  // ================================================================ 保底分档

  @Test
  void 新建保底落库并可按策略与店铺过滤() {
    var p = defaultPolicy();
    var saved = saveFloor(floorInput(p.policyId(), shopOne, "QUANTITY", "6", null, "2"));
    assertThat(saved.floorQuantity()).isEqualByComparingTo("6");
    assertThat(saved.safetyQuantity()).isEqualByComparingTo("2");

    var page =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.page(new FloorQuery(p.policyId(), null, true, null, 0, 50)));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.floors().get(0).referenceId()).isEqualTo(shopOne);
    assertThat(page.floors().get(0).floorMode()).isEqualTo(FloorMode.QUANTITY);

    assertThat(
            access
                .run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> service.page(new FloorQuery(null, shopTwo, true, null, 0, 50)))
                .total())
        .as("按店铺过滤应为空")
        .isZero();
  }

  @Test
  void 保底内容写定后改不动且换版会收口旧档() {
    var p = defaultPolicy();
    var first = saveFloor(floorInput(p.policyId(), shopOne, "QUANTITY", "6", null, "0"));

    // 直写 DB 改数量 → 触发器当场拒绝
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () -> {
                      sql.execute(
                          "update allocation_floor set floor_quantity = 999 where id = ?",
                          first.floorId());
                      return null;
                    }))
        .as("保底是承诺，能悄悄改掉它等于把承诺本身改掉了")
        .hasMessageContaining("保底内容");

    var second =
        saveFloor(
            first.floorId(), floorInput(p.policyId(), shopOne, "QUANTITY", "9", null, "0"), actorA);
    assertThat(second.supersededFloorId()).isEqualTo(first.floorId());
    assertThat(second.effectiveTo()).as("旧档被收口").isNotNull();
    assertThat(
            access
                .run(actorA, "warehouse.inventory.read", () -> service.detail(first.floorId()))
                .floorQuantity())
        .as("历史那一档原样保留 —— 复盘「当时承诺过什么」全靠它")
        .isEqualByComparingTo("6");
  }

  @Test
  void 同一店铺同一策略不能有两档保底同时生效() {
    var p = defaultPolicy();
    saveFloor(floorInput(p.policyId(), shopOne, "QUANTITY", "6", null, "0"));
    assertThatThrownBy(
            () -> saveFloor(floorInput(p.policyId(), shopOne, "QUANTITY", "8", null, "0")))
        .as("两个重叠的保底承诺会让「它该保多少」没有唯一答案")
        .hasMessageContaining("只能有一个保底承诺");
  }

  @Test
  void 三个口径全为零的保底被拒() {
    var p = defaultPolicy();
    assertThatThrownBy(
            () -> saveFloor(floorInput(p.policyId(), shopOne, "QUANTITY", "0", null, "0")))
        .as("这样的保底在算法里与「没配」完全等价，但界面上看起来配了")
        .hasMessageContaining("不能同时为 0");
  }

  @Test
  void 两个口径同时给时被拒() {
    var p = defaultPolicy();
    assertThatThrownBy(
            () -> saveFloor(floorInput(p.policyId(), shopOne, "QUANTITY", "6", "0.2", "0")))
        .as("「到底保底保多少」这个问题没有唯一答案")
        .hasMessageContaining("没有唯一答案");
  }

  @Test
  void 给不在名册里的店铺配保底被DB挡住() {
    var p = defaultPolicy();
    var stranger = UUID.randomUUID();
    assertThatThrownBy(
            () -> saveFloor(floorInput(p.policyId(), stranger, "QUANTITY", "6", null, "0")))
        .as("不在名册里的店铺配了保底，货一多就会凭空抢走别人的货，而界面上查不到是谁拿走的")
        .hasMessageContaining("不是策略");
  }

  @Test
  void 作废后不再参与裁决且行仍在() {
    var p = defaultPolicy();
    var saved = saveFloor(floorInput(p.policyId(), shopOne, "QUANTITY", "8", null, "0"));
    var r = first(preview("10"));
    assertThat(lineOf(r, shopOne).floorGranted()).isEqualByComparingTo("8");

    access.run(actorA, "warehouse.inventory.update", () -> service.retire(saved.floorId(), null));
    var after = first(preview("10"));
    assertThat(lineOf(after, shopOne).floorGranted()).as("作废之后保底不再参与裁决").isEqualByComparingTo("0");
    assertThat(
            access.run(actorA, "warehouse.inventory.read", () -> service.detail(saved.floorId())))
        .as("档位行永久保留")
        .isNotNull();
  }

  // ================================================================ 分摊落库

  @Test
  void 保底兑现记录逐店留痕且可查询() {
    var p = defaultPolicy();
    saveFloor(floorInput(p.policyId(), shopOne, "QUANTITY", "8", null, "0"));
    saveFloor(floorInput(p.policyId(), shopTwo, "QUANTITY", "8", null, "0"));

    var r = first(runOnce("10"));
    assertConservation(r);
    assertThat(r.runId()).as("落库时必须有 runId").isNotNull();

    var page =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.resolutions(r.runId(), null, false, false, 0, 50));
    assertThat(page.total()).isEqualTo(3);
    var first_ =
        page.resolutions().stream()
            .filter(x -> x.referenceId().equals(shopOne))
            .findFirst()
            .orElseThrow();
    assertThat(first_.status()).isEqualTo(FloorStatus.FULFILLED);
    assertThat(first_.grantedQty()).isEqualByComparingTo("8");
    assertThat(first_.shortfallQty()).isEqualByComparingTo("0");

    var second =
        page.resolutions().stream()
            .filter(x -> x.referenceId().equals(shopTwo))
            .findFirst()
            .orElseThrow();
    assertThat(second.status()).as("池子被前一家分完，B 只拿到剩下的 2 件").isEqualTo(FloorStatus.PARTIAL);
    assertThat(second.grantedQty()).isEqualByComparingTo("2");
    assertThat(second.shortfallQty()).isEqualByComparingTo("6");
    assertThat(second.shortfallReason()).isEqualTo(FloorShortfallReason.POOL_EXHAUSTED);

    assertThat(
            access
                .run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> service.resolutions(r.runId(), null, true, false, 0, 50))
                .total())
        .as("shortfallOnly 只看保底没兑现的")
        .isEqualTo(1);
  }

  @Test
  void 被压低店铺带基线与原因进记录() {
    var p = defaultPolicy();
    // 给优先级最低的店配 6 件保底，而可分配只有 10 → 排第一的店被挤下去。
    saveFloor(floorInput(p.policyId(), shopThree, "QUANTITY", "6", null, "0"));
    var r = first(runOnce("10"));
    assertConservation(r);

    var page =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.resolutions(r.runId(), null, false, true, 0, 50));
    assertThat(page.total()).as("suppressedOnly 只看被压低的").isEqualTo(1);
    var hit = page.resolutions().get(0);
    assertThat(hit.referenceId()).isEqualTo(shopOne);
    assertThat(hit.suppressionReason())
        .as("被别家店的保底挤下去，与「本来就轮不到它」是两种病")
        .isEqualTo(SuppressionReason.RESERVED_FOR_FLOOR);
    assertThat(hit.baseAllocatedQty()).as("基线留着，界面上才答得出这个数被谁挤下去").isGreaterThan(hit.allocatedQty());
  }

  @Test
  void 试算与落库结果逐字相同() {
    var p = defaultPolicy();
    saveFloor(floorInput(p.policyId(), shopOne, "QUANTITY", "6", null, "2"));

    var preview = first(preview("40"));
    var persisted = first(runOnce("40"));
    assertThat(preview.note()).contains("试算");
    assertThat(persisted.runId()).isNotNull();
    assertThat(persisted.lines())
        .as("两套算法才是最怕的")
        .usingRecursiveComparison()
        .isEqualTo(preview.lines());
    assertThat(persisted.floorPool()).isEqualByComparingTo(preview.floorPool());
    assertThat(persisted.residualPool()).isEqualByComparingTo(preview.residualPool());
  }

  @Test
  void 落库的分摊头与行由7_8_1_1的守恒约束兜住() {
    var p = defaultPolicy();
    saveFloor(floorInput(p.policyId(), shopOne, "QUANTITY", "5", null, "0"));
    var r = first(runOnce("20"));
    // 头与行写进的是 7.8.1.1 的表 —— 下游 7.8.2.1 要读的正是那一张。
    var head =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> sql.fetchOne("select * from allocation_run where id = ?", r.runId()));
    assertThat(head).isNotNull();
    // 直查必须**在租户上下文里**做：RLS 是真的开着，脱离上下文查到的 0 行不代表没落库。
    long lines =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                sql.fetchOne(
                        "select count(*) as c from allocation_run_line where run_id = ?", r.runId())
                    .get("c", Long.class));
    assertThat(lines).isEqualTo(3);
    long resolutions =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                sql.fetchOne(
                        "select count(*) as c from allocation_priority_resolution where run_id = ?",
                        r.runId())
                    .get("c", Long.class));
    assertThat(resolutions).as("保底生效记录逐店一行").isEqualTo(3);
  }

  @Test
  void 保底生效记录不可改也不可删() {
    var p = defaultPolicy();
    saveFloor(floorInput(p.policyId(), shopOne, "QUANTITY", "5", null, "0"));
    var r = first(runOnce("20"));
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () -> {
                      sql.execute(
                          "update allocation_priority_resolution set granted_qty = 999"
                              + " where run_id = ?",
                          r.runId());
                      return null;
                    }))
        .as("它回答的是「那次分摊为什么它没保住」，改了就再也答不出来")
        .hasMessageContaining("append-only");
  }

  @Test
  void 保底发放超过可分配量被DB当场拒掉() throws Exception {
    var p = defaultPolicy();
    saveFloor(floorInput(p.policyId(), shopOne, "QUANTITY", "5", null, "0"));
    var r = first(runOnce("20"));
    // append-only 触发器会先挡掉 UPDATE，所以这条走 **INSERT**：
    // 「应用层在一次事务里写出的保底发放合计超过可分配量」才是这条延迟约束真正要防的形态。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), "warehouse_app", "test-only")) {
      c.createStatement().execute("set app.current_tenant = '" + a + "'");
      c.setAutoCommit(true);
      var s = c.createStatement();
      assertThatThrownBy(
              () ->
                  s.execute(
                      "insert into allocation_priority_resolution (tenant_id,run_id,reference_id,source_system,"
                          + "reference_label,floor_order,floor_mode,floor_ratio,floor_quantity,safety_quantity,"
                          + "requested_qty,granted_qty,shortfall_qty,status,shortfall_reason,residual_qty,"
                          + "base_allocated_qty,allocated_qty,suppressed,suppression_reason) values ("
                          + "'"
                          + a
                          + "','"
                          + r.runId()
                          + "','"
                          + UUID.randomUUID()
                          + "','CHANNEL','多写的店',1,"
                          + "'QUANTITY',0,9999,0,9999,9999,0,'FULFILLED',null,0,9999,9999,false,null)"))
          .as("保底是在分摊之前先发的，发放总量超过可分配量时余量池会变成负数 —— 那是超卖")
          .hasMessageContaining("超过可分配量");
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void 没有生效策略时报空状态而不是默默按均分处理() {
    assertThatThrownBy(() -> preview("10"))
        .as("保底是分摊之上的裁决，没有分摊就没有保底可言")
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("没有解析到任何生效的分摊策略");
  }

  @Test
  void 能力端点如实报出三条未纳入的上游() {
    var c = service.capabilities();
    assertThat(c.performanceBasisAvailable()).as("店铺绩效（17 绩效域）未交付，不许假装可用").isFalse();
    assertThat(c.performanceBasisNote()).contains("未交付");
    assertThat(c.availableQuantityRequired()).isTrue();
    assertThat(c.poolNote()).contains("三个互不相同");
    assertThat(c.floorModes()).hasSize(2);
    assertThat(c.suppressReasons())
        .extracting(FloorCapabilityOption::code)
        .contains("RESERVED_FOR_FLOOR");
  }

  // ================================================================ 端点与权限

  @Test
  void 只读权限能看但不能写() throws Exception {
    var p = defaultPolicy();
    var readOnly = actors.get("Bearer read-only");
    http.perform(
            get("/warehouse/inventory/allocation-priority/floors")
                .header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk());
    http.perform(
            post("/warehouse/inventory/allocation-priority/floors")
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content(
                    "{\"policyId\":\""
                        + p.policyId()
                        + "\",\"sourceSystem\":\"CHANNEL\","
                        + "\"referenceId\":\""
                        + shopOne
                        + "\",\"floorMode\":\"QUANTITY\","
                        + "\"floorQuantity\":6}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void 端点返回可解析的分摊结果() throws Exception {
    var p = defaultPolicy();
    saveFloor(floorInput(p.policyId(), shopOne, "QUANTITY", "6", null, "0"));
    http.perform(
            post("/warehouse/inventory/allocation-priority/calculation")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"skuId\":\"" + sku + "\",\"availableQuantity\":40}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].runId").doesNotExist())
        .andExpect(jsonPath("$[0].lines.length()").value(3))
        .andExpect(jsonPath("$[0].floorPool").value(6));
    http.perform(
            get("/warehouse/inventory/allocation-priority/capabilities")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.performanceBasisAvailable").value(false));
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    var policyA = defaultPolicy();
    saveFloor(floorInput(policyA.policyId(), shopOne, "QUANTITY", "6", null, "0"));

    // B 租户造自己的一档 + 数量不同的保底，便于确认读到的是自己那一档。
    var policyB = policy(actorB);
    access.run(
        actorB,
        "warehouse.inventory.update",
        () -> {
          sql.execute(
              "insert into allocation_floor (id,tenant_id,policy_id,source_system,reference_id,"
                  + "reference_label,floor_mode,floor_ratio,floor_quantity,safety_quantity,"
                  + "effective_from,note,created_by_membership_id)"
                  + " values (?,?,?,'CHANNEL',?,'B店','QUANTITY',0,99,0,now(),'夹具',?)",
              UUID.randomUUID(),
              b,
              policyB.policyId(),
              shopOne,
              user);
          return null;
        });

    var pageA =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.page(new FloorQuery(null, null, true, null, 0, 50)));
    var pageB =
        access.run(
            actorB,
            "warehouse.inventory.read",
            () -> service.page(new FloorQuery(null, null, true, null, 0, 50)));
    assertThat(pageA.total()).isEqualTo(1);
    assertThat(pageA.floors().get(0).floorQuantity()).isEqualByComparingTo("6");
    assertThat(pageB.total()).isEqualTo(1);
    assertThat(pageB.floors().get(0).floorQuantity())
        .as("读到的是 B 自己的保底，不是 A 的")
        .isEqualByComparingTo("99");

    // 拿 A 的保底 id 去 B 那里读 → 404（RLS 下查不到），不泄露「这条存在」。
    var floorA = pageA.floors().get(0).id();
    assertThatThrownBy(
            () -> access.run(actorB, "warehouse.inventory.read", () -> service.detail(floorA)))
        .isInstanceOf(ResponseStatusException.class);

    // 保底生效记录同样隔离。
    var runA = first(runOnce("10")).runId();
    assertThat(
            access
                .run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> service.resolutions(runA, null, false, false, 0, 50))
                .total())
        .isPositive();
    assertThat(
            access
                .run(
                    actorB,
                    "warehouse.inventory.read",
                    () -> service.resolutions(runA, null, false, false, 0, 50))
                .total())
        .as("B 读不到 A 的分摊记录")
        .isZero();
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    var policyA = defaultPolicy();
    saveFloor(floorInput(policyA.policyId(), shopOne, "QUANTITY", "6", null, "0"));

    // 身份是 B、伪造 X-Tenant-Id 指向 A → B 只能看到 B 自己的（空）
    http.perform(
            get("/warehouse/inventory/allocation-priority/floors")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));

    // 身份是 A、伪造 B 的头 → A 仍然只看得到自己的那一档
    http.perform(
            get("/warehouse/inventory/allocation-priority/floors")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));

    // 写入路径同样不受伪造头影响：B 的请求写不进 A 的租户。
    http.perform(
            post("/warehouse/inventory/allocation-priority/floors")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString())
                .contentType("application/json")
                .content(
                    "{\"policyId\":\""
                        + policyA.policyId()
                        + "\",\"sourceSystem\":\"CHANNEL\","
                        + "\"referenceId\":\""
                        + shopTwo
                        + "\",\"floorMode\":\"QUANTITY\","
                        + "\"floorQuantity\":9}"))
        .andExpect(status().is4xxClientError());
    http.perform(
            get("/warehouse/inventory/allocation-priority/floors")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(jsonPath("$.total").value(1));
  }

  @Test
  void 断言三_连接池归还后不串租户() throws Exception {
    var policyA = defaultPolicy();
    saveFloor(floorInput(policyA.policyId(), shopOne, "QUANTITY", "6", null, "0"));
    var policyB = policy(actorB);
    access.run(
        actorB,
        "warehouse.inventory.update",
        () -> {
          sql.execute(
              "insert into allocation_floor (id,tenant_id,policy_id,source_system,reference_id,"
                  + "reference_label,floor_mode,floor_ratio,floor_quantity,safety_quantity,"
                  + "effective_from,note,created_by_membership_id)"
                  + " values (?,?,?,'CHANNEL',?,'B店','QUANTITY',0,99,0,now(),'夹具',?)",
              UUID.randomUUID(),
              b,
              policyB.policyId(),
              shopOne,
              user);
          return null;
        });

    // 池大小为 1，同一条连接被反复借还：交替跑两个租户 20 轮，
    // 每一轮都要同时校验回查、分摊与写入三条路径都不串租户。
    int bWrites = 0;
    UUID lastB = null;
    for (int i = 0; i < 20; i++) {
      var pageA =
          access.run(
              actorA,
              "warehouse.inventory.read",
              () -> service.page(new FloorQuery(null, null, true, null, 0, 50)));
      var pageB =
          access.run(
              actorB,
              "warehouse.inventory.read",
              () -> service.page(new FloorQuery(null, null, true, null, 0, 50)));
      assertThat(pageA.floors()).hasSize(1);
      // B 始终只有两档在生效：shopOne 的 99 + 一档轮换的 7（换版会先收口上一档，数量不增长）。
      assertThat(pageB.total()).isEqualTo(bWrites == 0 ? 1 : 2);
      // 按店铺定位而不是按行序取：created_at 会撞在同一瞬间，排序本身不保证谁是第 0 行。
      assertThat(bFloor(pageB, shopOne).floorQuantity()).isEqualByComparingTo("99");
      assertThat(bFloor(pageA, shopOne).floorQuantity()).isEqualByComparingTo("6");

      // 分摊路径：两个租户各自按自己的保底算，B 的 99 必须被裁到可分配量 10，
      // 而不是读到 A 的 6。
      var allocA = first(preview("10", actorA));
      var allocB = first(preview("10", actorB));
      assertThat(lineOf(allocA, shopOne).floorGranted()).isEqualByComparingTo("6");
      assertThat(lineOf(allocB, shopOne).floorGranted())
          .as("B 的分摊必须用 B 自己的保底 99 去裁，不受 A 的 6 影响")
          .isEqualByComparingTo("10");
      assertThat(lineOf(allocB, shopOne).floorRequested())
          .as("请求照记 99，差额 89 件显式欠账 —— 承诺存在过却没兑现不能被夹成「没要过」")
          .isEqualByComparingTo("99");

      // 写入路径：B 每两轮「收口上一档 + 开新一档」，A 的名册数量必须纹丝不动。
      // 反复写同一个店铺会撞上「一店至多一档保底在生效」的唯一索引，所以走换版而不是叠加。
      if (i % 2 == 0) {
        final UUID retiring = lastB;
        if (retiring != null) {
          access.run(actorB, "warehouse.inventory.update", () -> service.retire(retiring, null));
        }
        var target = (i / 2) % 2 == 0 ? shopTwo : shopThree;
        lastB =
            access
                .run(
                    actorB,
                    "warehouse.inventory.update",
                    () ->
                        service.save(
                            null,
                            floorInput(policyB.policyId(), target, "QUANTITY", "7", null, "0"),
                            user))
                .floorId();
        bWrites++;
        assertThat(
                access
                    .run(
                        actorB,
                        "warehouse.inventory.read",
                        () -> service.page(new FloorQuery(null, null, true, null, 0, 50)))
                    .total())
            .isEqualTo(2);
        assertThat(
                access
                    .run(
                        actorA,
                        "warehouse.inventory.read",
                        () -> service.page(new FloorQuery(null, null, true, null, 0, 50)))
                    .total())
            .as("B 写了这么多次，A 的名册数量必须纹丝不动")
            .isEqualTo(1);
      }
    }
  }

  @Test
  void 两张新表都强制行级安全() {
    var p = defaultPolicy();
    saveFloor(floorInput(p.policyId(), shopOne, "QUANTITY", "6", null, "0"));
    // 没有 app.current_tenant 时直查表为空：RLS 真的在生效，不是应用层自觉过滤。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), "warehouse_app", "test-only");
        var s = c.createStatement()) {
      for (var table : List.of("allocation_floor", "allocation_priority_resolution")) {
        var rs = s.executeQuery("select count(*) from " + table);
        rs.next();
        assertThat(rs.getLong(1)).as(table + " 在无租户上下文时必须查不到任何行").isZero();
        var meta =
            s.executeQuery(
                "select relforcerowsecurity from pg_class where relname = '" + table + "'");
        meta.next();
        assertThat(meta.getBoolean(1)).as(table + " 必须 FORCE RLS").isTrue();
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void 新迁移没有改动既有模块的表() {
    // V76 只建两张新表：给既有表加列或改 CHECK 会打掉别的车道的直写夹具。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      for (var table : List.of("allocation_floor", "allocation_priority_resolution")) {
        var exists =
            s.executeQuery(
                "select count(*) from information_schema.tables"
                    + " where table_schema = 'public' and table_name = '"
                    + table
                    + "'");
        exists.next();
        assertThat(exists.getLong(1)).as(table + " 必须存在").isEqualTo(1);
      }
      // 7.8.1.1 的五张表仍在，且列数没被本迁移动过（V68 各表的列由它自己的迁移负责）。
      for (var table :
          List.of(
              "allocation_policy",
              "allocation_policy_member",
              "allocation_shop_sales",
              "allocation_run",
              "allocation_run_line")) {
        var exists =
            s.executeQuery(
                "select count(*) from information_schema.tables"
                    + " where table_schema = 'public' and table_name = '"
                    + table
                    + "'");
        exists.next();
        assertThat(exists.getLong(1)).as(table + " 是 7.8.1.1 的表，本迁移不得删改").isEqualTo(1);
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
