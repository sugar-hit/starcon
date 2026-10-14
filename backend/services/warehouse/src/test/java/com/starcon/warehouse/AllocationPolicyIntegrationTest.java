package com.starcon.warehouse;

import static com.starcon.warehouse.AllocationPolicyModels.*;
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
import java.util.ArrayList;
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
 * 7.8.1.1 分配策略配置：分档生效、三种分摊方法的真实算式、守恒式、DB 级约束、 以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接： 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>夹具写法与 {@link SellablePolicyIntegrationTest} 完全一致（同样的 Testcontainers、 同样的 fake identity
 * server、同样的按租户派生 UUID、同样的 TRUNCATE 清场）。
 *
 * <p><b>本测试的核心是「守恒」而不是「配了几个字段」</b>：蓝图红线要防的是超卖， 所以每个分摊用例都必须同时断言 {@code available = buffer +
 * allocatable} 与 {@code allocatable = Σ各店分摊 + 未分配}， 并且要有一条用例直接拿连接写一组 Σ分摊 > 可分配量的行，让 DB 当场拒绝。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AllocationPolicyIntegrationTest {
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
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-allocation-" + tenant + "-" + ordinal).getBytes());
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

  @Autowired AllocationPolicyService service;
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
          "truncate allocation_run_line, allocation_run, allocation_shop_sales,"
              + " allocation_policy_member, allocation_policy, inventory_balance,"
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

  static OffsetDateTime now() {
    return OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
  }

  // ================================================================ 夹具写入

  /** 一条余额行。质量状态决定它是否计入合格量。 */
  void balance(String onHand) {
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?::numeric,0::numeric)",
              UUID.randomUUID(),
              a,
              sku,
              whOwn,
              le,
              qSellable,
              new BigDecimal(onHand));
          return null;
        });
  }

  /** 登记一条店铺销量（真实写 allocation_shop_sales，不是内存替身）。 */
  void sales(UUID shop, String units) {
    recordSales(shop, units);
  }

  AllocationShopSalesSaved recordSales(UUID shop, String units) {
    LocalDate today = LocalDate.now();
    return access.run(
        actorA,
        "warehouse.inventory.update",
        () ->
            service.recordSales(
                new AllocationShopSalesInput(
                    "CHANNEL",
                    shop,
                    "店-" + shop.toString().substring(0, 4),
                    sku,
                    today.minusDays(20),
                    today,
                    new BigDecimal(units)),
                user));
  }

  AllocationPolicyInput.AllocationPolicyMemberInput member(
      UUID shop, String mode, Integer priority, String shareLimit, Boolean enabled) {
    return new AllocationPolicyInput.AllocationPolicyMemberInput(
        "CHANNEL",
        shop,
        "店-" + shop.toString().substring(0, 4),
        mode,
        priority,
        shareLimit == null ? null : new BigDecimal(shareLimit),
        enabled);
  }

  /** 默认档：全租户兜底 + 按比例留 10% 缓冲 + 三家店先到先得。 */
  AllocationPolicyInput defaultPolicy(String method, String bufferMode, String ratio, String qty) {
    return new AllocationPolicyInput(
        "DEFAULT",
        null,
        null,
        "兜底档",
        method,
        bufferMode,
        ratio == null ? null : new BigDecimal(ratio),
        qty == null ? null : new BigDecimal(qty),
        null,
        30,
        null,
        "夹具",
        List.of(
            member(shopOne, "SELF_FULFILLED", 10, "0.5", true),
            member(shopTwo, "FULL_CONSIGNMENT", 20, "0.3", true),
            member(shopThree, "SEMI_CONSIGNMENT", 30, "0.2", true)));
  }

  AllocationPolicySaved save(AllocationPolicyInput input) {
    return save(null, input, actorA);
  }

  AllocationPolicySaved save(
      UUID supersedeId, AllocationPolicyInput input, AccessService.Access who) {
    return access.run(
        who, "warehouse.inventory.update", () -> service.save(supersedeId, input, who.userId()));
  }

  /** 试算（不落库）。 */
  List<AllocationResult> preview(String available, boolean persist) {
    return access.run(
        actorA,
        "warehouse.inventory.read",
        () ->
            service.allocate(
                new AllocationRequest(
                    List.of(sku),
                    available == null ? null : new BigDecimal(available),
                    null,
                    "DEFAULT",
                    null,
                    persist),
                user));
  }

  AllocationResult first(List<AllocationResult> results) {
    return results.get(0);
  }

  /** 三条守恒式。每一组分摊用例都必须过它。 */
  void assertConservation(AllocationResult r) {
    assertThat(r.bufferQuantity().add(r.allocatableQuantity()))
        .as("守恒式一：缓冲 + 可分配 = 可用")
        .isEqualByComparingTo(r.availableQuantity());
    BigDecimal sum =
        r.lines().stream()
            .map(AllocationLine::allocatedQty)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(sum).as("Σ各店分摊额").isEqualByComparingTo(r.allocatedTotal());
    assertThat(r.allocatedTotal().add(r.unallocatedQuantity()))
        .as("守恒式二：分摊合计 + 未分配 = 可分配")
        .isEqualByComparingTo(r.allocatableQuantity());
    assertThat(r.allocatedTotal())
        .as("Σ分摊 ≤ 可分配量，超卖由这一条挡住")
        .isLessThanOrEqualTo(r.allocatableQuantity());
    for (AllocationLine line : r.lines()) {
      assertThat(line.allocatedQty().scale())
          .as("分摊额必须是整件：" + line.referenceLabel() + " 分到 " + line.allocatedQty())
          .isLessThanOrEqualTo(0);
      assertThat(line.allocatedQty().signum()).as("分摊额不能为负").isNotNegative();
    }
  }

  // ================================================================ 分档生效

  @Test
  void 新建策略落库并可解析出生效档() {
    var saved = save(defaultPolicy("FIXED_PRIORITY", "RATIO", "0.10", null));
    assertThat(saved.memberCount()).isEqualTo(3);

    var page =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.page(new AllocationPolicyQuery(null, null, null, false, null, 0, 50)));
    assertThat(page.total()).isEqualTo(1);
    var row = page.policies().get(0);
    assertThat(row.id()).isEqualTo(saved.policyId());
    assertThat(row.scopeType()).isEqualTo(AllocationScopeType.DEFAULT);
    assertThat(row.method()).isEqualTo(AllocationMethod.FIXED_PRIORITY);
    assertThat(row.effectiveTo()).isNull();
    // 店铺类型顺序留空时用默认顺序，且必须是 5 种经营模式的合法排列。
    assertThat(row.shopTypeOrder()).isEqualTo(AllocationPolicyService.DEFAULT_SHOP_TYPE_ORDER);

    var detail =
        access.run(actorA, "warehouse.inventory.read", () -> service.detail(saved.policyId()));
    assertThat(detail.members()).hasSize(3);
    // 成员按优先级升序返回：界面上「谁先拿」看到的次序就是真实次序。
    assertThat(detail.members().get(0).referenceId()).isEqualTo(shopOne);
    assertThat(detail.members().get(0).shopOperatingMode()).isEqualTo("SELF_FULFILLED");

    var hit =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.effective("DEFAULT", null, null, null));
    assertThat(hit.policy()).isNotNull();
    assertThat(hit.usedFallback()).isFalse();
  }

  @Test
  void 改策略是收口加新档且历史原样保留() {
    var t0 = now().minusHours(2);
    var t1 = now().minusHours(1);
    var first =
        save(
            new AllocationPolicyInput(
                "DEFAULT",
                null,
                null,
                "第一档",
                "FIXED_PRIORITY",
                "RATIO",
                new BigDecimal("0.10"),
                null,
                null,
                30,
                t0,
                "第一档",
                List.of(member(shopOne, "SELF_FULFILLED", 10, "1", true))));
    var second =
        save(
            first.policyId(),
            new AllocationPolicyInput(
                "DEFAULT",
                null,
                null,
                "第二档",
                "PROPORTIONAL_SALES",
                "QUANTITY",
                null,
                new BigDecimal("5"),
                null,
                30,
                t1,
                "第二档",
                List.of(member(shopOne, "SELF_FULFILLED", 10, null, true))),
            actorA);

    assertThat(second.supersededPolicyId()).isEqualTo(first.policyId());
    var page =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.page(new AllocationPolicyQuery(null, null, null, false, null, 0, 50)));
    assertThat(page.total()).isEqualTo(2);
    var old =
        page.policies().stream()
            .filter(p -> p.id().equals(first.policyId()))
            .findFirst()
            .orElseThrow();
    var now =
        page.policies().stream()
            .filter(p -> p.id().equals(second.policyId()))
            .findFirst()
            .orElseThrow();
    // 历史那一档原样保留：它仍然是「当时真正生效的那套算法」的唯一凭据。
    assertThat(old.method()).isEqualTo(AllocationMethod.FIXED_PRIORITY);
    assertThat(old.effectiveTo()).isNotNull();
    assertThat(now.method()).isEqualTo(AllocationMethod.PROPORTIONAL_SALES);
    assertThat(now.bufferQuantity()).isEqualByComparingTo("5");
    assertThat(now.effectiveTo()).isNull();
  }

  @Test
  void 策略内容写定后改不动() {
    var saved = save(defaultPolicy("FIXED_PRIORITY", "RATIO", "0.10", null));
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        sql.execute(
                            "update allocation_policy set buffer_ratio = ?::numeric where id = ?",
                            new BigDecimal("0.9"),
                            saved.policyId())))
        .hasMessageContaining("写定后不可修改");
  }

  @Test
  void 同一槽位区间重叠被拒且报出冲突档() {
    save(defaultPolicy("FIXED_PRIORITY", "RATIO", "0.10", null));
    assertThatThrownBy(
            () ->
                save(
                    new AllocationPolicyInput(
                        "DEFAULT",
                        null,
                        null,
                        "重叠档",
                        "FIXED_PRIORITY",
                        "RATIO",
                        new BigDecimal("0.2"),
                        null,
                        null,
                        30,
                        now().plusDays(1),
                        "故意重叠",
                        List.of(member(shopOne, "SELF_FULFILLED", 10, "1", true)))))
        .hasMessageContaining("区间与另一档重叠");
  }

  @Test
  void 作废后不再生效且行仍在() {
    var saved = save(defaultPolicy("FIXED_PRIORITY", "RATIO", "0.10", null));
    var retired =
        access.run(
            actorA, "warehouse.inventory.update", () -> service.retire(saved.policyId(), now()));
    assertThat(retired.effectiveTo()).isNotNull();
    assertThat(retired.message()).contains("行本身保留");

    var hit =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.effective("DEFAULT", null, null, null));
    assertThat(hit.policy()).as("收口之后没有在效档了").isNull();
    assertThat(hit.note()).contains("没有找到生效的分摊策略");

    var page =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.page(new AllocationPolicyQuery(null, null, null, false, null, 0, 50)));
    assertThat(page.total()).as("档位行永久保留，历史可回看").isEqualTo(1);
  }

  @Test
  void SKU档优先于店铺档与兜底档() {
    // 兜底：先到先得，一家独享
    save(
        new AllocationPolicyInput(
            "DEFAULT",
            null,
            null,
            "兜底",
            "FIXED_PRIORITY",
            "RATIO",
            new BigDecimal("0"),
            null,
            null,
            30,
            null,
            "兜底",
            List.of(member(shopOne, "SELF_FULFILLED", 10, null, true))));
    // 店铺档
    save(
        new AllocationPolicyInput(
            "SHOP",
            "CHANNEL",
            shopOne,
            "店一",
            "FIXED_PRIORITY",
            "RATIO",
            new BigDecimal("0"),
            null,
            null,
            30,
            null,
            "店铺档",
            List.of(member(shopOne, "SELF_FULFILLED", 10, "0.5", true))));
    // SKU 档
    save(
        new AllocationPolicyInput(
            "SKU",
            "CATALOG",
            sku,
            "SKU-1",
            "FIXED_PRIORITY",
            "RATIO",
            new BigDecimal("0"),
            null,
            null,
            30,
            null,
            "SKU 档",
            List.of(member(shopOne, "SELF_FULFILLED", 10, "0.25", true))));

    var skuHit =
        access.run(
            actorA, "warehouse.inventory.read", () -> service.effective(null, shopOne, sku, null));
    assertThat(skuHit.resolvedScopeType()).isEqualTo(AllocationScopeType.SKU);
    assertThat(skuHit.usedFallback()).isFalse();
    var shopHit =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.effective("SHOP", shopOne, null, null));
    assertThat(shopHit.resolvedScopeType()).isEqualTo(AllocationScopeType.SHOP);
    var fallback =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.effective("DEFAULT", null, null, null));
    assertThat(fallback.resolvedScopeType()).isEqualTo(AllocationScopeType.DEFAULT);
  }

  // ================================================================ 分摊算法

  @Test
  void 比例分配余数按小数部分补发且总账平() {
    // 可用 10 件，按比例留 0% 缓冲 → 可分配 10；销量 6/3/1 → 6/3/1，余数 0。
    save(defaultPolicy("PROPORTIONAL_SALES", "RATIO", "0", null));
    sales(shopOne, "6");
    sales(shopTwo, "3");
    sales(shopThree, "1");

    var r = first(preview("10", false));
    assertConservation(r);
    assertThat(r.allocatableQuantity()).isEqualByComparingTo("10");
    assertThat(r.allocatedTotal()).isEqualByComparingTo("10");
    assertThat(r.unallocatedQuantity()).isEqualByComparingTo("0");
    assertThat(line(r, shopOne).allocatedQty()).isEqualByComparingTo("6");
    assertThat(line(r, shopTwo).allocatedQty()).isEqualByComparingTo("3");
    assertThat(line(r, shopThree).allocatedQty()).isEqualByComparingTo("1");
    assertThat(line(r, shopOne).salesShare()).isEqualByComparingTo("0.6");
  }

  @Test
  void 比例分配遇到除不尽时把余数补给小数部分最大的店() {
    // 可用 10 件，三家销量 1:1:1 → 每家 3.33 件，逐个向下取整得 3/3/3（合计 9），
    // 余数 1 件按小数部分（全部相等）→ 决胜键店铺 id 升序补给它，结果仍然 Σ=10。
    save(defaultPolicy("PROPORTIONAL_SALES", "RATIO", "0", null));
    sales(shopOne, "1");
    sales(shopTwo, "1");
    sales(shopThree, "1");

    var r = first(preview("10", false));
    assertConservation(r);
    assertThat(r.allocatedTotal()).as("余数必须被显式处理，不能凭空丢掉").isEqualByComparingTo("10");
    assertThat(r.unallocatedQuantity()).isEqualByComparingTo("0");
    // 同一输入必须得到同一结果：补发次序由决胜键定死，不是随机的。
    var again = first(preview("10", false));
    for (int i = 0; i < r.lines().size(); i++) {
      assertThat(r.lines().get(i).allocatedQty())
          .isEqualByComparingTo(again.lines().get(i).allocatedQty());
    }
    // 6+3+1 那种整除场景与 1:1:1 的非整除场景，Σ 都必须恰好等于可分配量。
    assertThat(
            r.lines().stream()
                .map(AllocationLine::allocatedQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo("10");
  }

  @Test
  void 比例分配全部销量为零时如实报零而不是均分() {
    save(defaultPolicy("PROPORTIONAL_SALES", "RATIO", "0", null));
    // 刻意不登记任何销量。

    var r = first(preview("100", false));
    assertConservation(r);
    assertThat(r.allocatedTotal()).as("没有分母时不能凭空分货").isEqualByComparingTo("0");
    assertThat(r.unallocatedQuantity()).isEqualByComparingTo("100");
    for (AllocationLine l : r.lines()) {
      assertThat(l.allocatedQty()).isEqualByComparingTo("0");
      assertThat(l.outcome()).isEqualTo(AllocationOutcome.NO_SALES);
    }
    assertThat(r.note()).contains("没有任何销量登记");
  }

  @Test
  void 先到先得按优先级分配且不超发() {
    // 可用 10，缓冲 0 → 可分配 10；上限 0.5 / 0.3 / 0.2 → 5 / 3 / 2。
    save(defaultPolicy("FIXED_PRIORITY", "RATIO", "0", null));
    var r = first(preview("10", false));
    assertConservation(r);
    assertThat(line(r, shopOne).allocatedQty()).isEqualByComparingTo("5");
    assertThat(line(r, shopTwo).allocatedQty()).isEqualByComparingTo("3");
    assertThat(line(r, shopThree).allocatedQty()).isEqualByComparingTo("2");
    // 前两家恰好取到自己的上限而货还有剩（第三家还有份），所以如实标 CAP_REACHED ——
    // 封顶是生效的，界面上必须看得见；最后一家把货分完，标 OK。
    assertThat(line(r, shopOne).outcome()).isEqualTo(AllocationOutcome.CAP_REACHED);
    assertThat(line(r, shopTwo).outcome()).isEqualTo(AllocationOutcome.CAP_REACHED);
    assertThat(line(r, shopThree).outcome()).isEqualTo(AllocationOutcome.OK);
    assertThat(r.starvedMemberCount()).isZero();
  }

  @Test
  void 先到先得总额不足时后面的店分不到并标出原因() {
    // 可分配只有 4：前两家各取到上限（trunc(4×0.5)=2、trunc(4×0.3)=1），只剩 1 件，
    // 而第三家的上限 trunc(4×0.2)=0 —— 货在它之前就被分完了，不是它触到了自己的上限。
    save(defaultPolicy("FIXED_PRIORITY", "RATIO", "0", null));
    var r = first(preview("4", false));
    assertConservation(r);
    assertThat(r.allocatedTotal()).isEqualByComparingTo("3");
    assertThat(r.unallocatedQuantity()).as("剩 1 件无人可分，显式记为未分配").isEqualByComparingTo("1");
    assertThat(line(r, shopOne).outcome()).isEqualTo(AllocationOutcome.CAP_REACHED);
    assertThat(line(r, shopThree).allocatedQty()).isEqualByComparingTo("0");
    assertThat(line(r, shopThree).outcome())
        .as("分到 0 的原因必须区分「触顶」与「被前面的分完」")
        .isEqualTo(AllocationOutcome.DEMAND_EXHAUSTED);
    assertThat(r.starvedMemberCount()).isEqualTo(1);
  }

  @Test
  void 未设份额上限的成员会拿走全部剩余并如实标出来() {
    save(
        new AllocationPolicyInput(
            "DEFAULT",
            null,
            null,
            "兜底",
            "FIXED_PRIORITY",
            "RATIO",
            new BigDecimal("0"),
            null,
            null,
            30,
            null,
            "不封顶",
            List.of(
                member(shopOne, "SELF_FULFILLED", 10, null, true),
                member(shopTwo, "FULL_CONSIGNMENT", 20, "0.2", true))));
    var r = first(preview("10", false));
    assertConservation(r);
    assertThat(line(r, shopOne).allocatedQty()).as("不设上限 = 先到先得全部").isEqualByComparingTo("10");
    assertThat(line(r, shopTwo).allocatedQty()).isEqualByComparingTo("0");
    assertThat(line(r, shopTwo).outcome()).isEqualTo(AllocationOutcome.DEMAND_EXHAUSTED);
    assertThat(r.note()).contains("未设份额上限");
  }

  @Test
  void 按店铺类型优先级排序且顺序可配置() {
    // 类型顺序刻意把「全托管」排在「自营」前面：货权是谁的货谁优先是经营决策，
    // 写死在代码里就等于替租户做了决定。
    save(
        new AllocationPolicyInput(
            "DEFAULT",
            null,
            null,
            "兜底",
            "SHOP_TYPE_PRIORITY",
            "RATIO",
            new BigDecimal("0"),
            null,
            "FULL_CONSIGNMENT,SELF_FULFILLED,SEMI_CONSIGNMENT,OVERSEAS_WAREHOUSE,LOCAL",
            30,
            null,
            "托管优先",
            List.of(
                member(shopOne, "SELF_FULFILLED", 10, "0.5", true),
                member(shopTwo, "FULL_CONSIGNMENT", 20, "0.3", true),
                member(shopThree, "SEMI_CONSIGNMENT", 30, "0.2", true))));

    var r = first(preview("100", false));
    assertConservation(r);
    assertThat(r.lines().get(0).shopOperatingMode()).as("全托管被配置排在最前").isEqualTo("FULL_CONSIGNMENT");
    assertThat(r.lines().get(0).referenceId()).isEqualTo(shopTwo);
    assertThat(r.lines().get(1).referenceId()).isEqualTo(shopOne);
    assertThat(r.lines().get(2).referenceId()).isEqualTo(shopThree);
    assertThat(r.allocatedTotal()).isEqualByComparingTo("100");
  }

  @Test
  void 同类型内按成员优先级决定先后() {
    // 三个成员类型顺序排在最后（LOCAL），只能靠 priority 决定次序。
    save(
        new AllocationPolicyInput(
            "DEFAULT",
            null,
            null,
            "兜底",
            "SHOP_TYPE_PRIORITY",
            "RATIO",
            new BigDecimal("0"),
            null,
            "FULL_CONSIGNMENT,SEMI_CONSIGNMENT,OVERSEAS_WAREHOUSE,SELF_FULFILLED,LOCAL",
            30,
            null,
            "同类型比优先级",
            List.of(
                member(shopOne, "LOCAL", 30, "0.5", true),
                member(shopTwo, "LOCAL", 10, "0.3", true),
                member(shopThree, "LOCAL", 20, "0.2", true))));
    var r = first(preview("100", false));
    assertConservation(r);
    assertThat(r.lines().get(0).referenceId()).as("priority 最小者先拿").isEqualTo(shopTwo);
    assertThat(r.lines().get(1).referenceId()).isEqualTo(shopThree);
    assertThat(r.lines().get(2).referenceId()).isEqualTo(shopOne);
  }

  @Test
  void 停用成员分到零且原因写明而不是凭空消失() {
    save(
        new AllocationPolicyInput(
            "DEFAULT",
            null,
            null,
            "兜底",
            "FIXED_PRIORITY",
            "RATIO",
            new BigDecimal("0"),
            null,
            null,
            30,
            null,
            "含停用成员",
            List.of(
                member(shopOne, "SELF_FULFILLED", 10, "0.5", true),
                member(shopTwo, "FULL_CONSIGNMENT", 20, "0.3", false),
                member(shopThree, "SEMI_CONSIGNMENT", 30, "0.5", true))));
    var r = first(preview("100", false));
    assertConservation(r);
    assertThat(r.lines()).as("停用的店仍要出现在结果里").hasSize(3);
    var disabled = line(r, shopTwo);
    assertThat(disabled.allocatedQty()).isEqualByComparingTo("0");
    assertThat(disabled.outcome()).isEqualTo(AllocationOutcome.DISABLED);
  }

  @Test
  void 缓冲按比例与按件数都真的生效() {
    save(defaultPolicy("FIXED_PRIORITY", "RATIO", "0.20", null));
    var ratioRun = first(preview("100", false));
    assertConservation(ratioRun);
    assertThat(ratioRun.bufferQuantity()).isEqualByComparingTo("20");
    assertThat(ratioRun.allocatableQuantity()).isEqualByComparingTo("80");

    // 换版成按件数留 50
    var current =
        access
            .run(
                actorA,
                "warehouse.inventory.read",
                () ->
                    service.page(
                        new AllocationPolicyQuery("DEFAULT", null, null, true, null, 0, 50)))
            .policies()
            .get(0);
    save(current.id(), defaultPolicy("FIXED_PRIORITY", "QUANTITY", null, "50"), actorA);
    var qtyRun = first(preview("100", false));
    assertConservation(qtyRun);
    assertThat(qtyRun.bufferQuantity()).isEqualByComparingTo("50");
    assertThat(qtyRun.allocatableQuantity()).isEqualByComparingTo("50");
  }

  @Test
  void 缓冲大于可用量时被夹到可用量而不是算出负数() {
    save(
        new AllocationPolicyInput(
            "DEFAULT",
            null,
            null,
            "兜底",
            "FIXED_PRIORITY",
            "QUANTITY",
            null,
            new BigDecimal("500"),
            null,
            30,
            null,
            "缓冲过大",
            List.of(member(shopOne, "SELF_FULFILLED", 10, null, true))));
    var r = first(preview("30", false));
    assertConservation(r);
    assertThat(r.bufferQuantity()).as("缓冲不能超过可用量").isEqualByComparingTo("30");
    assertThat(r.allocatableQuantity()).isEqualByComparingTo("0");
    assertThat(r.allocatedTotal()).isEqualByComparingTo("0");
  }

  @Test
  void 试算与落库结果逐字相同() {
    save(defaultPolicy("PROPORTIONAL_SALES", "RATIO", "0.10", null));
    sales(shopOne, "50");
    sales(shopTwo, "30");
    sales(shopThree, "20");

    var previewed = first(preview("101", false));
    assertThat(previewed.run()).as("试算不写任何行").isNull();

    var persisted =
        access.run(
            actorA,
            "warehouse.inventory.update",
            () ->
                service.allocate(
                    new AllocationRequest(
                        List.of(sku), new BigDecimal("101"), null, "DEFAULT", null, Boolean.TRUE),
                    user));
    var stored = first(persisted);
    assertConservation(stored);
    assertThat(stored.run()).isNotNull();
    assertThat(stored.run().persisted()).isTrue();
    assertThat(stored.allocatedTotal()).isEqualByComparingTo(previewed.allocatedTotal());
    assertThat(stored.bufferQuantity()).isEqualByComparingTo(previewed.bufferQuantity());
    for (int i = 0; i < previewed.lines().size(); i++) {
      assertThat(stored.lines().get(i).allocatedQty())
          .isEqualByComparingTo(previewed.lines().get(i).allocatedQty());
      assertThat(stored.lines().get(i).outcome()).isEqualTo(previewed.lines().get(i).outcome());
    }
  }

  @Test
  void 未配置任何策略时如实报空状态而不是按平均分配() {
    var results = preview("100", false);
    var r = first(results);
    assertThat(r.effective().policy()).isNull();
    assertThat(r.lines()).isEmpty();
    assertThat(r.allocatedTotal()).isEqualByComparingTo("0");
    assertThat(r.note()).contains("没有统一算法");
    assertThat(r.note()).contains("所有店铺都以为有货");
  }

  @Test
  void 可用量不给时按可售质量状态现算() {
    balance("60");
    access.run(
        actorA,
        "warehouse.inventory.update",
        () -> {
          sql.execute(
              "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
              UUID.randomUUID(),
              a,
              "DEFECTIVE",
              "残次",
              "DEFECTIVE");
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?::numeric,0::numeric)",
              UUID.randomUUID(),
              a,
              sku,
              whOwn,
              le,
              access.run(
                  actorA,
                  "warehouse.inventory.read",
                  () ->
                      sql.fetchOne(
                              "select id from inventory_quality_status where tenant_id = ? and code = 'DEFECTIVE'",
                              a)
                          .get("id", UUID.class)),
              new BigDecimal("500"));
          return null;
        });
    save(defaultPolicy("FIXED_PRIORITY", "RATIO", "0", null));
    var r = first(preview(null, false));
    assertConservation(r);
    // 残次 500 件被**排除**而不是扣减，所以可用量是 60 而不是 -440。
    assertThat(r.availableQuantity()).isEqualByComparingTo("60");
  }

  AllocationLine line(AllocationResult r, UUID shop) {
    return r.lines().stream().filter(l -> l.referenceId().equals(shop)).findFirst().orElseThrow();
  }

  // ================================================================ 销量登记

  @Test
  void 销量登记落库并可按窗口聚合() {
    var saved = recordSales(shopOne, "12");
    assertThat(saved.unitsSold()).isEqualByComparingTo("12");
    assertThat(saved.message()).contains("已登记");

    var rows =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.sales(sku, LocalDate.now().minusDays(30), LocalDate.now()));
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).referenceId()).isEqualTo(shopOne);
    assertThat(rows.get(0).units()).isEqualByComparingTo("12");
  }

  @Test
  void 同一店铺同一区间重复登记被唯一约束拒绝() {
    recordSales(shopOne, "12");
    assertThatThrownBy(() -> recordSales(shopOne, "8"))
        .hasMessageContaining("allocation_shop_sales");
  }

  @Test
  void 销量登记的负数与倒置区间被拒() {
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        service.recordSales(
                            new AllocationShopSalesInput(
                                "CHANNEL",
                                shopOne,
                                "店一",
                                sku,
                                LocalDate.now().minusDays(5),
                                LocalDate.now(),
                                new BigDecimal("-1")),
                            user)))
        .hasMessageContaining("销量不能为负数");
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        service.recordSales(
                            new AllocationShopSalesInput(
                                "CHANNEL",
                                shopOne,
                                "店一",
                                sku,
                                LocalDate.now(),
                                LocalDate.now().minusDays(5),
                                BigDecimal.ONE),
                            user)))
        .hasMessageContaining("销量区间倒置");
  }

  // ================================================================ DB 级约束

  @Test
  void 缓冲超出可用量被DB拒绝() {
    access.run(
        actorA,
        "warehouse.inventory.update",
        () -> {
          sql.execute(
              "insert into allocation_policy(id,tenant_id,scope_type,method,buffer_mode,"
                  + "buffer_ratio,buffer_quantity,shop_type_order,sales_window_days,"
                  + "effective_from,created_by_membership_id) "
                  + "values (?,?,'DEFAULT','FIXED_PRIORITY','QUANTITY',0,9999,"
                  + "?,30,now(),?)",
              UUID.randomUUID(),
              a,
              AllocationPolicyService.DEFAULT_SHOP_TYPE_ORDER,
              user);
          return null;
        });
    UUID policyId =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                sql.fetchOne("select id from allocation_policy where tenant_id = ?", a)
                    .get("id", UUID.class));
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        sql.execute(
                            "insert into allocation_run (id,tenant_id,policy_id,method,sku_id,allocated_at,"
                                + "sales_window_start,sales_window_end,available_quantity,buffer_quantity,"
                                + "allocatable_quantity,allocated_total,unallocated_quantity,"
                                + "created_by_membership_id) values (?,?,?,'FIXED_PRIORITY',?,now(),"
                                + "current_date,current_date,10::numeric,50::numeric,"
                                + "(-40)::numeric,0::numeric,(-40)::numeric,?)",
                            UUID.randomUUID(),
                            a,
                            policyId,
                            sku,
                            user)))
        .hasMessageContaining("allocatable_quantity");
  }

  @Test
  void 分摊合计对不上可分配量被DB拒绝() {
    var saved = save(defaultPolicy("FIXED_PRIORITY", "RATIO", "0", null));
    UUID runId = UUID.randomUUID();
    // 头里写 allocated_total=10（可分配=10，未分配=0），行里只给 6 → Σ行≠头。
    // 头与行必须写在**同一个**事务里：守恒是延迟约束触发器，在提交那一刻才核对，
    // 分成两次提交的话第一次就已经因为 6≠10 被拒绝了，测的就不是「行写少了」而是「提交失败」。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () -> {
                      sql.execute(
                          "insert into allocation_run (id,tenant_id,policy_id,method,sku_id,allocated_at,"
                              + "sales_window_start,sales_window_end,available_quantity,buffer_quantity,"
                              + "allocatable_quantity,allocated_total,unallocated_quantity,"
                              + "created_by_membership_id) values (?,?,?,'FIXED_PRIORITY',?,now(),"
                              + "current_date,current_date,10::numeric,0::numeric,10::numeric,10::numeric,"
                              + "0::numeric,?)",
                          runId,
                          a,
                          saved.policyId(),
                          sku,
                          user);
                      sql.execute(
                          "insert into allocation_run_line (tenant_id,run_id,source_system,reference_id,"
                              + "shop_operating_mode,allocation_order,sales_units,allocated_qty,outcome) "
                              + "values (?,?,'CHANNEL',?,'SELF_FULFILLED',1,0::numeric,6::numeric,'OK')",
                          a,
                          runId,
                          shopOne);
                      return null;
                    }))
        .hasMessageContaining("分摊行合计");
  }

  @Test
  void 分摊头与行不可改不可删() throws Exception {
    save(defaultPolicy("FIXED_PRIORITY", "RATIO", "0", null));
    var results =
        access.run(
            actorA,
            "warehouse.inventory.update",
            () ->
                service.allocate(
                    new AllocationRequest(
                        List.of(sku), new BigDecimal("10"), null, "DEFAULT", null, Boolean.TRUE),
                    user));
    UUID runId = first(results).run().runId();
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        sql.execute(
                            "update allocation_run set allocated_total = ?::numeric where id = ?",
                            new BigDecimal("999"),
                            runId)))
        .hasMessageContaining("append-only");
    // DELETE 走应用角色时被**权限层**先挡住（REVOKE DELETE），根本走不到触发器 ——
    // 两道防线各挡一道，这条用例分别证明它们真的都在。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () -> sql.execute("delete from allocation_run where id = ?", runId)))
        .hasMessageContaining("bad SQL grammar");
    // 拿到超级用户连接（绕过权限层）去删，触发器仍然拒绝：不是「权限恰好没给」而已。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      try (var s = c.prepareStatement("delete from allocation_run where id = ?")) {
        s.setObject(1, runId);
        var e = org.assertj.core.api.Assertions.catchThrowable(() -> s.execute());
        assertThat(e).isNotNull();
        assertThat(rootMessage(e)).contains("append-only");
      }
    }
  }

  /** 取最内层的 PG 错误信息：spring/jOOQ 会把它包好几层。 */
  static String rootMessage(Throwable e) {
    Throwable cur = e;
    while (cur.getCause() != null) {
      cur = cur.getCause();
    }
    return cur.getMessage() == null ? "" : cur.getMessage();
  }

  @Test
  void 分摊批次可列出并下钻且跨租户404() {
    save(defaultPolicy("FIXED_PRIORITY", "RATIO", "0", null));
    var results =
        access.run(
            actorA,
            "warehouse.inventory.update",
            () ->
                service.allocate(
                    new AllocationRequest(
                        List.of(sku), new BigDecimal("10"), null, "DEFAULT", null, Boolean.TRUE),
                    user));
    UUID runId = first(results).run().runId();

    var page =
        access.run(actorA, "warehouse.inventory.read", () -> service.runs(null, null, 0, 20));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.runs().get(0).runId()).isEqualTo(runId);
    assertThat(page.runs().get(0).lineCount()).isEqualTo(3);
    assertThat(page.runs().get(0).skuCode()).isEqualTo("SKU-1");

    var detail = access.run(actorA, "warehouse.inventory.read", () -> service.run(runId));
    assertThat(detail.lines()).hasSize(3);
    assertConservation(first(results));

    assertThatThrownBy(
            () -> access.run(actorB, "warehouse.inventory.read", () -> service.run(runId)))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void 店铺类型顺序非法时被服务与DB双重拒绝() {
    assertThatThrownBy(
            () ->
                save(
                    new AllocationPolicyInput(
                        "DEFAULT",
                        null,
                        null,
                        "兜底",
                        "FIXED_PRIORITY",
                        "RATIO",
                        new BigDecimal("0"),
                        null,
                        "SELF_FULFILLED,SELF_FULFILLED",
                        30,
                        null,
                        "重复类型",
                        List.of(member(shopOne, "SELF_FULFILLED", 10, "1", true)))))
        .hasMessageContaining("店铺类型顺序");
    assertThatThrownBy(
            () ->
                save(
                    new AllocationPolicyInput(
                        "DEFAULT",
                        null,
                        null,
                        "兜底",
                        "FIXED_PRIORITY",
                        "RATIO",
                        new BigDecimal("0"),
                        null,
                        "SELF_FULFILLED",
                        30,
                        null,
                        "只列一种",
                        List.of(member(shopOne, "SELF_FULFILLED", 10, "1", true)))))
        .hasMessageContaining("店铺类型顺序");
    assertThatThrownBy(
            () ->
                save(
                    new AllocationPolicyInput(
                        "DEFAULT",
                        null,
                        null,
                        "兜底",
                        "FIXED_PRIORITY",
                        "RATIO",
                        new BigDecimal("0"),
                        null,
                        "SELF_FULFILLED,SEMI_CONSIGNMENT,FULL_CONSIGNMENT,OVERSEAS_WAREHOUSE,MADE_UP",
                        30,
                        null,
                        "未知类型",
                        List.of(member(shopOne, "SELF_FULFILLED", 10, "1", true)))))
        .hasMessageContaining("未知的店铺类型");
  }

  @Test
  void 成员为空或店铺重复时被拒() {
    assertThatThrownBy(
            () ->
                save(
                    new AllocationPolicyInput(
                        "DEFAULT",
                        null,
                        null,
                        "兜底",
                        "FIXED_PRIORITY",
                        "RATIO",
                        new BigDecimal("0"),
                        null,
                        null,
                        30,
                        null,
                        "没有成员",
                        List.of())))
        .hasMessageContaining("至少要配一个参与分摊的店铺");
    assertThatThrownBy(
            () ->
                save(
                    new AllocationPolicyInput(
                        "DEFAULT",
                        null,
                        null,
                        "兜底",
                        "FIXED_PRIORITY",
                        "RATIO",
                        new BigDecimal("0"),
                        null,
                        null,
                        30,
                        null,
                        "重复店铺",
                        List.of(
                            member(shopOne, "SELF_FULFILLED", 10, "0.5", true),
                            member(shopOne, "FULL_CONSIGNMENT", 20, "0.5", true)))))
        .hasMessageContaining("出现了两次");
  }

  @Test
  void 缓冲比例与件数不能同时给() {
    assertThatThrownBy(
            () ->
                save(
                    new AllocationPolicyInput(
                        "DEFAULT",
                        null,
                        null,
                        "兜底",
                        "FIXED_PRIORITY",
                        "RATIO",
                        new BigDecimal("0.1"),
                        new BigDecimal("5"),
                        null,
                        30,
                        null,
                        "两列都填",
                        List.of(member(shopOne, "SELF_FULFILLED", 10, "1", true)))))
        .hasMessageContaining("没有唯一答案");
  }

  // ================================================================ 端点

  @Test
  void 端点串起策略配置到分摊结果() throws Exception {
    http.perform(
            post("/warehouse/inventory/allocation-sales")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"sourceSystem\":\"CHANNEL\",\"referenceId\":\""
                        + shopOne
                        + "\",\"referenceLabel\":\"店一\",\"skuId\":\""
                        + sku
                        + "\",\"periodStart\":\""
                        + LocalDate.now().minusDays(10)
                        + "\",\"periodEnd\":\""
                        + LocalDate.now()
                        + "\",\"unitsSold\":7}"))
        .andExpect(status().isOk());

    http.perform(
            get("/warehouse/inventory/allocation-policies/capabilities")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        // 销量不可自动派生这件事必须如实报出来，不能让界面以为是从订单算的。
        .andExpect(jsonPath("$.salesBasisAvailable").value(false))
        .andExpect(jsonPath("$.methods.length()").value(3));

    var saved = save(defaultPolicy("PROPORTIONAL_SALES", "RATIO", "0.10", null));
    http.perform(
            post("/warehouse/inventory/allocation-policies/" + saved.policyId() + "/versions")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"scopeType\":\"DEFAULT\",\"method\":\"PROPORTIONAL_SALES\","
                        + "\"bufferMode\":\"RATIO\",\"bufferRatio\":0.1,\"salesWindowDays\":30,"
                        + "\"members\":[{\"sourceSystem\":\"CHANNEL\",\"referenceId\":\""
                        + shopOne
                        + "\",\"shopOperatingMode\":\"SELF_FULFILLED\",\"priority\":1,\"shareLimit\":1}]}"))
        .andExpect(status().isOk());

    http.perform(
            post("/warehouse/inventory/allocation-policies/calculation")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"skuIds\":[\"" + sku + "\"],\"availableQuantity\":100,\"persist\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].availableQuantity").value(100))
        .andExpect(jsonPath("$[0].bufferQuantity").value(10))
        .andExpect(jsonPath("$[0].allocatableQuantity").value(90));

    // 落库走另一个端点且要 update 权限：只读角色发 POST 必须 403。
    http.perform(
            post("/warehouse/inventory/allocation-policies/runs")
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content("{\"skuIds\":[\"" + sku + "\"]}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void 试算端点无视persist字段_否则只读角色就能写批次() throws Exception {
    save(defaultPolicy("FIXED_PRIORITY", "RATIO", "0", null));
    // 只读角色 + persist=true 打试算端点：必须被**忽略**成试算，一行都不写。
    // 让一个布尔字段决定要不要落库，等于给写闸门开了一道后门。
    http.perform(
            post("/warehouse/inventory/allocation-policies/calculation")
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content(
                    "{\"skuIds\":[\"" + sku + "\"],\"availableQuantity\":10,\"persist\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].run").doesNotExist());

    var page =
        access.run(actorA, "warehouse.inventory.read", () -> service.runs(null, null, 0, 20));
    assertThat(page.total()).as("试算一行都不写").isZero();

    // 落库端点则无视 persist=false，强制落库。
    http.perform(
            post("/warehouse/inventory/allocation-policies/runs")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"skuIds\":[\"" + sku + "\"],\"availableQuantity\":10,\"persist\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].run.persisted").value(true));
    var after =
        access.run(actorA, "warehouse.inventory.read", () -> service.runs(null, null, 0, 20));
    assertThat(after.total()).isEqualTo(1);
  }

  @Test
  void 非法的时刻参数当场400而不是悄悄用当前时刻() throws Exception {
    http.perform(
            get("/warehouse/inventory/allocation-policies/effective?at=不是时刻")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isBadRequest());
    // 缺参数是另一回事：缺省取当前时刻，不报错。
    http.perform(
            get("/warehouse/inventory/allocation-policies/effective")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    var policyA = save(defaultPolicy("FIXED_PRIORITY", "RATIO", "0.20", null));
    // B 租户造一个同名兜底档，缓冲不同，便于确认读到的是自己那一档而不是 A 的。
    access.run(
        actorB,
        "warehouse.inventory.update",
        () -> {
          sql.execute(
              "insert into allocation_policy(id,tenant_id,scope_type,method,buffer_mode,"
                  + "buffer_ratio,buffer_quantity,shop_type_order,sales_window_days,"
                  + "effective_from,created_by_membership_id) "
                  + "values (?,?,'DEFAULT','FIXED_PRIORITY','RATIO',?::numeric,0::numeric,"
                  + "?,30,now(),?)",
              UUID.randomUUID(),
              b,
              new BigDecimal("0.60"),
              AllocationPolicyService.DEFAULT_SHOP_TYPE_ORDER,
              user);
          return null;
        });

    var pageA =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.page(new AllocationPolicyQuery(null, null, null, false, null, 0, 50)));
    assertThat(pageA.total()).isEqualTo(1);
    assertThat(pageA.policies().get(0).id()).isEqualTo(policyA.policyId());
    assertThat(pageA.policies().get(0).bufferRatio()).isEqualByComparingTo("0.20");

    var pageB =
        access.run(
            actorB,
            "warehouse.inventory.read",
            () -> service.page(new AllocationPolicyQuery(null, null, null, false, null, 0, 50)));
    assertThat(pageB.total()).isEqualTo(1);
    assertThat(pageB.policies().get(0).bufferRatio()).isEqualByComparingTo("0.60");

    // 拿 A 的 id 去 B 那里读 → 404（RLS 下查不到），不泄露「这条存在」。
    assertThatThrownBy(
            () ->
                access.run(
                    actorB, "warehouse.inventory.read", () -> service.detail(policyA.policyId())))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    save(defaultPolicy("FIXED_PRIORITY", "RATIO", "0.20", null));

    // 身份是 B、伪造 X-Tenant-Id 指向 A → B 只能看到 B 自己的（空）
    http.perform(
            get("/warehouse/inventory/allocation-policies")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));

    // 身份是 A、伪造 B 的头 → A 仍然只看得到自己的那一档
    http.perform(
            get("/warehouse/inventory/allocation-policies")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));

    // 写入路径同样不受伪造头影响
    http.perform(
            post("/warehouse/inventory/allocation-policies")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString())
                .contentType("application/json")
                .content(
                    "{\"scopeType\":\"DEFAULT\",\"method\":\"FIXED_PRIORITY\","
                        + "\"bufferMode\":\"RATIO\",\"bufferRatio\":0.9,\"members\":["
                        + "{\"sourceSystem\":\"CHANNEL\",\"referenceId\":\""
                        + shopOne
                        + "\",\"shopOperatingMode\":\"SELF_FULFILLED\"}]}"))
        .andExpect(status().isOk());
    http.perform(
            get("/warehouse/inventory/allocation-policies")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(jsonPath("$.total").value(1));
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    var policyA = save(defaultPolicy("FIXED_PRIORITY", "RATIO", "0.20", null));
    access.run(
        actorB,
        "warehouse.inventory.update",
        () -> {
          sql.execute(
              "insert into allocation_policy(id,tenant_id,scope_type,method,buffer_mode,"
                  + "buffer_ratio,buffer_quantity,shop_type_order,sales_window_days,"
                  + "effective_from,created_by_membership_id) "
                  + "values (?,?,'DEFAULT','FIXED_PRIORITY','RATIO',?::numeric,0::numeric,"
                  + "?,30,now(),?)",
              UUID.randomUUID(),
              b,
              new BigDecimal("0.60"),
              AllocationPolicyService.DEFAULT_SHOP_TYPE_ORDER,
              user);
          return null;
        });

    // 池大小为 1，同一条连接被反复借还：交替跑两个租户 20 轮，
    // 每一轮都要同时校验写入、回查、解析与分摊四条路径都不串租户。
    for (int i = 0; i < 20; i++) {
      var pageA =
          access.run(
              actorA,
              "warehouse.inventory.read",
              () ->
                  service.page(
                      new AllocationPolicyQuery("DEFAULT", null, null, true, null, 0, 50)));
      var pageB =
          access.run(
              actorB,
              "warehouse.inventory.read",
              () ->
                  service.page(
                      new AllocationPolicyQuery("DEFAULT", null, null, true, null, 0, 50)));
      assertThat(pageA.policies()).hasSize(1);
      assertThat(pageB.policies()).hasSize(1);
      assertThat(pageA.policies().get(0).id()).isEqualTo(policyA.policyId());
      assertThat(pageA.policies().get(0).bufferRatio()).isEqualByComparingTo("0.20");
      assertThat(pageB.policies().get(0).bufferRatio()).isEqualByComparingTo("0.60");

      // 解析路径：各自解析到自己的档
      assertThat(
              access
                  .run(
                      actorA,
                      "warehouse.inventory.read",
                      () -> service.effective("DEFAULT", null, null, null))
                  .policy()
                  .bufferRatio())
          .isEqualByComparingTo("0.20");
      assertThat(
              access
                  .run(
                      actorB,
                      "warehouse.inventory.read",
                      () -> service.effective("DEFAULT", null, null, null))
                  .policy()
                  .bufferRatio())
          .isEqualByComparingTo("0.60");

      // 分摊路径：B 租户的名册是空的（它的档没有成员），必须拒绝而不是分出 0 件冒充成功
      assertThatThrownBy(
              () ->
                  access.run(
                      actorB,
                      "warehouse.inventory.read",
                      () ->
                          service.allocate(
                              new AllocationRequest(
                                  List.of(sku), new BigDecimal("10"), null, "DEFAULT", null, false),
                              user)))
          .as("B 的档没有成员，必须拒绝而不是分出 0 件冒充成功")
          .isInstanceOf(ResponseStatusException.class);
    }
  }

  @Test
  void 五张业务表都强制行级安全() {
    save(defaultPolicy("FIXED_PRIORITY", "RATIO", "0.20", null));
    // 没有 app.current_tenant 时直查表为空：RLS 真的在生效，不是应用层自觉过滤。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), "warehouse_app", "test-only");
        var s = c.createStatement()) {
      var rs = s.executeQuery("select count(*) from allocation_policy");
      rs.next();
      assertThat(rs.getLong(1)).isZero();
      // 逐张表确认 FORCE（relforcerowsecurity）
      for (var table :
          List.of(
              "allocation_policy",
              "allocation_policy_member",
              "allocation_shop_sales",
              "allocation_run",
              "allocation_run_line")) {
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

  // ================================================================ 跨车道回归

  @Test
  void 新迁移没有改动既有模块的表() {
    // V68 只建五张新表：给既有表加 NOT NULL / CHECK / 触发器会打掉别的车道的直写夹具。
    // 这条断言把「不越界」钉住：pg 里 allocation_* 之外不该多出任何本迁移的痕迹。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      var rs =
          s.executeQuery(
              "select count(*) from information_schema.columns"
                  + " where table_schema = 'public' and table_name like 'allocation\\_%'");
      rs.next();
      assertThat(rs.getLong(1)).isPositive();
      var missing = new ArrayList<String>();
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
        if (exists.getLong(1) == 0) {
          missing.add(table);
        }
      }
      assertThat(missing).isEmpty();
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
