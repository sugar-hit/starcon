package com.starcon.warehouse;

import static com.starcon.warehouse.SellablePolicyModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.warehouse.SellablePolicyModels.SellablePolicyInput;
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
import org.springframework.dao.DataAccessException;
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
 * 7.2.2.1 可售量口径：分档生效、共享池 + 缓冲组合判定、含 FBA 开关、超卖容忍、时区零漂移，
 * 以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>夹具写法与 {@link InventoryReconciliationIntegrationTest} 完全一致（同样的 Testcontainers、
 * 同样的 fake identity server、同样的按租户派生 UUID、同样的 TRUNCATE 清场）。
 *
 * <p><b>为什么本测试的核心是「共享池 + 缓冲」这一组</b>：蓝图原话「多店铺共享池时，缓冲不足会
 * 在同步延迟期间超卖」。共享池和缓冲分开测都成立，合起来才是真实风险 —— 两个店铺各留 50% 缓冲，
 * 分配之和恰好等于池容量，测试必须能把这个「零余量」判出来，而不是回一个"看起来有货"。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SellablePolicyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      Stream.of("inventory")
          .flatMap(p -> Stream.of("read", "create", "update", "delete").map(op -> "warehouse." + p + "." + op))
          .toList();

  static final UUID le = idFor(a, 1);
  static final UUID sku = idFor(a, 2);
  static final UUID whOwn = idFor(a, 3);
  static final UUID whFba = idFor(a, 4);
  static final UUID qSellable = idFor(a, 5);
  static final UUID qDefective = idFor(a, 6);
  /** 店铺是 channel 服务的实体：这里只造一个逻辑 id，warehouse 侧不建外键也不查它的表。 */
  static final UUID shopOne = UUID.randomUUID();
  static final UUID shopTwo = UUID.randomUUID();

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-warehouse-sellable-" + tenant + "-" + ordinal).getBytes());
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

  @Autowired SellablePolicyService service;
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
          "truncate sellable_policy_warehouse, sellable_policy, inventory_transfer, inventory_balance,"
              + " inventory_quality_status, warehouse, sku_ref, legal_entity_ref restart identity cascade");
    }
    for (var tenant : List.of(a, b)) {
      access.run(
          actorFor(tenant),
          "warehouse.inventory.read",
          () -> {
            sql.execute(
                "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 1), tenant, "LE-1", "主体一");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 2), tenant, "SKU-1", "商品一");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
                idFor(tenant, 3), tenant, "WH-OWN", "自有仓", "CN", "Asia/Shanghai");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
                idFor(tenant, 4), tenant, "WH-FBA", "美国 FBA", "US", "America/Los_Angeles");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 5), tenant, "SELLABLE", "可售", "SELLABLE");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 6), tenant, "DEFECTIVE", "残次", "DEFECTIVE");
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
  void balance(UUID warehouse, UUID quality, String onHand, String reserved) {
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,?)",
              UUID.randomUUID(), a, sku, warehouse, le, quality, new BigDecimal(onHand),
              new BigDecimal(reserved));
          return null;
        });
  }

  /** 一笔在途调拨。{@code to} 是「会到这仓来」的量 —— 与 7.1.1.3 的在途口径同源。 */
  void inTransit(UUID to, String quantity) {
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_transfer(id,tenant_id,sku_id,legal_entity_id,from_warehouse_id,"
                  + "to_warehouse_id,quality_status_id,quantity,status,shipped_at) "
                  + "values(?,?,?,?,?,?,?,?,'IN_TRANSIT',?::timestamptz)",
              UUID.randomUUID(), a, sku, le, whFba, to, qSellable, new BigDecimal(quantity), now());
          return null;
        });
  }

  /** 标准库存：自有仓 100 件可售、40 件在途；FBA 仓 60 件可售；自有仓另有 500 件残次。 */
  void standardStock() {
    balance(whOwn, qSellable, "100", "0");
    balance(whFba, qSellable, "60", "0");
    balance(whOwn, qDefective, "500", "0");
    inTransit(whOwn, "40");
  }

  SellablePolicyInput shopPolicy(
      UUID shop, String label, PoolMode mode, String poolKey, String buffer, PlatformStockScope scope,
      List<SellablePolicyInput.SellablePolicyWarehouseBinding> warehouses, boolean inTransitFlag) {
    return new SellablePolicyInput(
        "SHOP", "CHANNEL", shop, label, mode.name(), poolKey, false, BigDecimal.ZERO,
        scope.name(), new BigDecimal(buffer), inTransitFlag, null, "夹具",
        warehouses);
  }

  List<SellablePolicyInput.SellablePolicyWarehouseBinding> ownOnly() {
    return List.of(new SellablePolicyInput.SellablePolicyWarehouseBinding(whOwn, "OWN"));
  }

  List<SellablePolicyInput.SellablePolicyWarehouseBinding> ownAndFba() {
    return List.of(
        new SellablePolicyInput.SellablePolicyWarehouseBinding(whOwn, "OWN"),
        new SellablePolicyInput.SellablePolicyWarehouseBinding(whFba, "PLATFORM_FBA"));
  }

  SellablePolicySaved save(UUID supersedeId, SellablePolicyInput input, AccessService.Access who) {
    return access.run(who, "warehouse.inventory.update", () -> service.save(supersedeId, input, who.userId()));
  }

  SellablePolicySaved save(SellablePolicyInput input) {
    return save(null, input, actorA);
  }

  SellablePolicySaved save(SellablePolicyInput input, AccessService.Access who) {
    return save(null, input, who);
  }

  SellableQuantityResult calculate(UUID shop) {
    return access.run(
        actorA,
        "warehouse.inventory.read",
        () -> service.calculate(new SellableQuantityRequest(List.of(sku), null, shop, "SHOP", null)));
  }

  // ================================================================ 分档生效

  @Test
  void 新建口径落库并可解析出生效档() {
    standardStock();
    var saved = save(shopPolicy(
        shopOne, "美国店", PoolMode.EXCLUSIVE, null, "0.20", PlatformStockScope.OWN_WAREHOUSE,
        ownOnly(), false));

    var page = access.run(
        actorA, "warehouse.inventory.read", () -> service.page(new SellablePolicyQuery(null, null, null, false, null, 0, 50)));
    assertThat(page.total()).isEqualTo(1);
    var row = page.policies().get(0);
    assertThat(row.id()).isEqualTo(saved.policyId());
    assertThat(row.scopeType()).isEqualTo(PolicyScopeType.SHOP);
    // 独占池键由服务派生，不接受调用方自填 —— 人手填才可能把独占池写成共享池。
    assertThat(row.poolKey()).isEqualTo("EXCL-" + shopOne);
    assertThat(row.bufferRatio()).isEqualByComparingTo("0.20");
    assertThat(row.effectiveTo()).isNull();

    var hit = access.run(
        actorA,
        "warehouse.inventory.read",
        () -> service.effective("SHOP", shopOne, null));
    assertThat(hit.policy()).isNotNull();
    assertThat(hit.resolvedScopeType()).isEqualTo(PolicyScopeType.SHOP);
    assertThat(hit.usedFallback()).isFalse();

    // 详情带挂仓关系：池容量的构成单元不是挂在页面上的说明文字。
    var detail = access.run(actorA, "warehouse.inventory.read", () -> service.detail(saved.policyId()));
    assertThat(detail.warehouses()).hasSize(1);
    assertThat(detail.warehouses().get(0).warehouseKind()).isEqualTo(WarehouseKind.OWN);
    assertThat(detail.warehouses().get(0).warehouseCode()).isEqualTo("WH-OWN");
  }

  @Test
  void 改口径是收口加新档且历史原样保留() {
    standardStock();
    var t0 = now().minusHours(2);
    var t1 = now().minusHours(1);
    var first = save(new SellablePolicyInput(
        "SHOP", "CHANNEL", shopOne, "美国店", "EXCLUSIVE", null, false, BigDecimal.ZERO,
        "OWN_WAREHOUSE", new BigDecimal("0.20"), false, t0, "第一档", ownOnly()));

    var second = save(first.policyId(), new SellablePolicyInput(
        "SHOP", "CHANNEL", shopOne, "美国店", "EXCLUSIVE", null, false, BigDecimal.ZERO,
        "OWN_WAREHOUSE", new BigDecimal("0.35"), false, t1, "第二档", ownOnly()), actorA);

    // 两档并存：第一档的旧值没有被改掉，它仍是「当时真正生效的防线」的凭据。
    var page = access.run(
        actorA, "warehouse.inventory.read", () -> service.page(new SellablePolicyQuery("SHOP", shopOne, null, false, null, 0, 50)));
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.policies().stream().filter(p -> p.bufferRatio().compareTo(new BigDecimal("0.20")) == 0)
            .findFirst().orElseThrow().effectiveTo())
        .isEqualTo(t1);
    assertThat(page.policies().stream().filter(p -> p.id().equals(second.policyId())).findFirst().orElseThrow().effectiveTo())
        .isNull();

    // 按时刻解析：换版前后各自生效的是哪一档
    assertThat(access.run(actorA, "warehouse.inventory.read", () -> service.effective("SHOP", shopOne, t0.plusMinutes(30)))
            .policy().bufferRatio())
        .isEqualByComparingTo("0.20");
    assertThat(access.run(actorA, "warehouse.inventory.read", () -> service.effective("SHOP", shopOne, t1.plusMinutes(30)))
            .policy().bufferRatio())
        .isEqualByComparingTo("0.35");
    // 落在收口那一刻的那一秒：左闭右开，新档已经生效
    assertThat(access.run(actorA, "warehouse.inventory.read", () -> service.effective("SHOP", shopOne, t1))
            .policy().bufferRatio())
        .isEqualByComparingTo("0.35");
  }

  @Test
  void 未配置店铺时回退兜底档并如实报出() {
    standardStock();
    // 没有店铺档也没有兜底档 → 如实报空，而不是静默按 0 处理
    var none = access.run(actorA, "warehouse.inventory.read", () -> service.effective("SHOP", shopTwo, null));
    assertThat(none.policy()).isNull();
    assertThat(none.usedFallback()).isFalse();

    var result = access.run(
        actorA, "warehouse.inventory.read",
        () -> service.calculate(new SellableQuantityRequest(List.of(sku), null, shopTwo, "SHOP", null)));
    assertThat(result.rows()).isEmpty();
    assertThat(result.verdict()).isNull();
    // 回写 0 = 立刻断货，回写满 = 超卖，两条路都比拒绝更糟。
    assertThat(result.note()).contains("拒绝");

    // 只配兜底档时，未命中的店铺回退到它，且 usedFallback 如实标记
    save(new SellablePolicyInput(
        "DEFAULT", "", null, "", "EXCLUSIVE", null, false, BigDecimal.ZERO,
        "OWN_WAREHOUSE", new BigDecimal("0.10"), false, null, "兜底", ownOnly()));
    var fallback = access.run(actorA, "warehouse.inventory.read", () -> service.effective("SHOP", shopTwo, null));
    assertThat(fallback.policy()).isNotNull();
    assertThat(fallback.resolvedScopeType()).isEqualTo(PolicyScopeType.DEFAULT);
    assertThat(fallback.usedFallback()).isTrue();
    assertThat(fallback.note()).contains("回退");
  }

  // ================================================================ DB 级不变量

  @Test
  void 口径内容与区间由数据库挡住() {
    standardStock();
    var id = save(shopPolicy(
        shopOne, "美国店", PoolMode.EXCLUSIVE, null, "0.20", PlatformStockScope.OWN_WAREHOUSE, ownOnly(), false));

    // 内容列不可改：改缓冲比例必须走换版，否则"当时那道防线"会被悄悄改掉。
    assertThatThrownBy(() -> access.run(actorA, "warehouse.inventory.read", () -> {
          sql.execute("update sellable_policy set buffer_ratio = ?::numeric where id = ?",
              new BigDecimal("0.05"), id.policyId());
          return null;
        }))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("不可修改");

    // 区间重叠：同一槽位同一时刻只能有一个生效值，否则解析没有唯一答案。
    assertThatThrownBy(() -> access.run(actorA, "warehouse.inventory.read", () -> {
          sql.execute(
              "insert into sellable_policy(id,tenant_id,scope_type,source_system,reference_id,reference_label,"
                  + "pool_mode,pool_key,oversell_allowed,oversell_tolerance,platform_stock_scope,buffer_ratio,"
                  + "include_in_transit,effective_from,note,created_by_membership_id) "
                  + "values (?,?,'SHOP','CHANNEL',?,'重叠档','EXCLUSIVE',?,false,0::numeric,'OWN_WAREHOUSE',"
                  + "0.30::numeric,false,?::timestamptz,'重叠',?)",
              UUID.randomUUID(), a, shopOne, "EXCL-" + shopOne, now().minusDays(1), user);
          return null;
        }))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("区间");

    // 缓冲比例的取值范围落在 DB 层：应用层绕不过去。
    assertThatThrownBy(() -> access.run(actorA, "warehouse.inventory.read", () -> {
          sql.execute(
              "insert into sellable_policy(id,tenant_id,scope_type,source_system,reference_id,reference_label,"
                  + "pool_mode,pool_key,oversell_allowed,oversell_tolerance,platform_stock_scope,buffer_ratio,"
                  + "include_in_transit,effective_from,note,created_by_membership_id) "
                  + "values (?,?,'SHOP','CHANNEL',?,'越界档','EXCLUSIVE',?,false,0::numeric,'OWN_WAREHOUSE',"
                  + "1.500000::numeric,false,now(),'越界',?)",
              UUID.randomUUID(), a, shopTwo, "EXCL-" + shopTwo, user);
          return null;
        }))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("sellable_policy_buffer_ratio_check");

    // 档位行不物理删除：它是审计凭据。
    assertThatThrownBy(() -> access.run(actorA, "warehouse.inventory.read", () -> {
          sql.execute("delete from sellable_policy where id = ?", id.policyId());
          return null;
        }))
        .isInstanceOf(DataAccessException.class);

    // 作废走收口区间，行还在。
    var retired = access.run(
        actorA, "warehouse.inventory.update", () -> service.retire(id.policyId(), now().plusMinutes(5)));
    assertThat(retired.effectiveTo()).isNotNull();
    assertThat(access.run(actorA, "warehouse.inventory.read", () -> service.detail(id.policyId())).policy().effectiveTo())
        .isNotNull();
    // 重复作废不会产生第二个收口时刻
    assertThatThrownBy(() -> access.run(
            actorA, "warehouse.inventory.update", () -> service.retire(id.policyId(), now().plusMinutes(9))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已被作废");
  }

  @Test
  void 输入校验把配错的路径挡在门外() {
    standardStock();
    // 共享池必须取名：没有池名解析不出成员集合
    assertThatThrownBy(() -> save(shopPolicy(
            shopOne, "美国店", PoolMode.SHARED, "  ", "0.20", PlatformStockScope.OWN_WAREHOUSE, ownOnly(), false)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("共享池必须取池名");
    // 共享池名不能占用独占池的命名空间
    assertThatThrownBy(() -> save(shopPolicy(
            shopOne, "美国店", PoolMode.SHARED, "EXCL-x", "0.20", PlatformStockScope.OWN_WAREHOUSE, ownOnly(), false)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("EXCL-");
    // 兜底档不能共享
    assertThatThrownBy(() -> save(new SellablePolicyInput(
            "DEFAULT", "", null, "", "SHARED", "POOL-A", false, BigDecimal.ZERO,
            "OWN_WAREHOUSE", new BigDecimal("0.20"), false, null, "", ownOnly())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("兜底档不能设为共享池");
    // 缓冲比例越界（服务层也拒，不依赖 DB 兜底）
    assertThatThrownBy(() -> save(shopPolicy(
            shopOne, "美国店", PoolMode.EXCLUSIVE, null, "1.5", PlatformStockScope.OWN_WAREHOUSE, ownOnly(), false)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("缓冲比例必须在 0 与 1 之间");
    // 不许超卖却留着额度
    assertThatThrownBy(() -> save(new SellablePolicyInput(
            "SHOP", "CHANNEL", shopOne, "美国店", "EXCLUSIVE", null, false, new BigDecimal("5"),
            "OWN_WAREHOUSE", new BigDecimal("0.20"), false, null, "", ownOnly())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("超卖容忍必须是 0");
    // 挂到不存在的仓：少算平台可售数看起来更安全，实际是丢单与断货
    assertThatThrownBy(() -> save(new SellablePolicyInput(
            "SHOP", "CHANNEL", shopOne, "美国店", "EXCLUSIVE", null, false, BigDecimal.ZERO,
            "OWN_WAREHOUSE", new BigDecimal("0.20"), false, null, "",
            List.of(new SellablePolicyInput.SellablePolicyWarehouseBinding(UUID.randomUUID(), "OWN")))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在");
    // 空 SKU 集合
    assertThatThrownBy(() -> access.run(
            actorA, "warehouse.inventory.read",
            () -> service.calculate(new SellableQuantityRequest(List.of(), null, shopOne, "SHOP", null))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("至少传一个 SKU");
  }

  // ================================================================ 共享池 + 缓冲

  @Test
  void 共享池与缓冲不足的组合判定为没有余量() {
    standardStock();
    // 两个店铺共享同一个池，各留 50% 缓冲 —— 分配之和恰好等于池容量，余量为 0。
    save(shopPolicy(shopOne, "美国店", PoolMode.SHARED, "US-POOL", "0.50",
        PlatformStockScope.OWN_WAREHOUSE, ownOnly(), false));
    save(shopPolicy(shopTwo, "加拿大店", PoolMode.SHARED, "US-POOL", "0.50",
        PlatformStockScope.OWN_WAREHOUSE, ownOnly(), false));

    var roster = access.run(actorA, "warehouse.inventory.read", () -> service.pools(null));
    assertThat(roster).hasSize(1);
    var shared = roster.get(0);
    assertThat(shared.poolKey()).isEqualTo("US-POOL");
    assertThat(shared.memberCount()).isEqualTo(2);
    // 2 个成员 − 缓冲之和 1.0 = 分配比 1.0 → 没有余量
    assertThat(shared.allocationRatio()).isEqualByComparingTo("1.0");
    assertThat(shared.verdict()).isEqualTo(PoolSafetyVerdict.NO_HEADROOM);

    // 真实库存下的逐 SKU 判定：余量恰好为 0，不是"看起来还有货"
    var result = calculate(shopOne);
    assertThat(result.pool().memberCount()).isEqualTo(2);
    assertThat(result.totalPoolCapacity()).isEqualByComparingTo("100");
    assertThat(result.pool().headroom()).isEqualByComparingTo("0");
    assertThat(result.verdict()).isEqualTo(PoolSafetyVerdict.NO_HEADROOM);

    // 把其中一个店铺的缓冲提到 80% → 分配比 0.7 → 重新出现 30% 余量
    var before = access.run(actorA, "warehouse.inventory.read", () -> service.effective("SHOP", shopOne, null));
    save(before.policy().id(),
        shopPolicy(shopOne, "美国店", PoolMode.SHARED, "US-POOL", "0.80",
            PlatformStockScope.OWN_WAREHOUSE, ownOnly(), false), actorA);

    var fixed = calculate(shopTwo);
    assertThat(fixed.pool().allocationRatio()).isEqualByComparingTo("0.7");
    assertThat(fixed.pool().headroom()).isEqualByComparingTo("30");
    assertThat(fixed.verdict()).isEqualTo(PoolSafetyVerdict.GUARDED);

    // 池详情给出成员清单与仓构成（同一仓被两个成员引用只算一个仓）
    var detail = access.run(actorA, "warehouse.inventory.read", () -> service.pool("US-POOL", null));
    assertThat(detail.members()).hasSize(2);
    assertThat(detail.summary().warehouseCount()).isEqualTo(1);
    assertThat(detail.members()).extracting(SellablePoolMember::referenceLabel)
        .containsExactlyInAnyOrder("美国店", "加拿大店");
    // 池名对不上 → 404，不返回一个空壳让人以为"池是干净的"
    assertThatThrownBy(() -> access.run(actorA, "warehouse.inventory.read", () -> service.pool("NOPE", null)))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void 共享池缓冲过低被判为必然超卖() {
    standardStock();
    // 各留 10% 缓冲：2 − 0.2 = 1.8 倍池容量被分配出去 → 必然超卖
    save(shopPolicy(shopOne, "美国店", PoolMode.SHARED, "US-POOL", "0.10",
        PlatformStockScope.OWN_WAREHOUSE, ownOnly(), false));
    save(shopPolicy(shopTwo, "加拿大店", PoolMode.SHARED, "US-POOL", "0.10",
        PlatformStockScope.OWN_WAREHOUSE, ownOnly(), false));

    var result = calculate(shopOne);
    assertThat(result.pool().allocationRatio()).isEqualByComparingTo("1.8");
    assertThat(result.pool().headroom()).isEqualByComparingTo("-80");
    assertThat(result.verdict()).isEqualTo(PoolSafetyVerdict.OVERSOLD);
    assertThat(result.note()).contains("必然超卖");
    // 名册（无库存数据）按比例给同一结论，不依赖有没有货
    assertThat(access.run(actorA, "warehouse.inventory.read", () -> service.pools(null)).get(0).verdict())
        .isEqualTo(PoolSafetyVerdict.OVERSOLD);
  }

  @Test
  void 超卖容忍额度内判为有意超卖() {
    standardStock();
    // 独占池 + 零缓冲 + 容忍 3 件：余量 −3，落在额度内 → 有意为之
    var id = save(new SellablePolicyInput(
        "SHOP", "CHANNEL", shopOne, "美国店", "EXCLUSIVE", null, true, new BigDecimal("3"),
        "OWN_WAREHOUSE", new BigDecimal("0"), false, null, "允许超卖 3 件", ownOnly()));
    var tolerated = calculate(shopOne);
    assertThat(tolerated.pool().headroom()).isEqualByComparingTo("-3");
    assertThat(tolerated.verdict()).isEqualTo(PoolSafetyVerdict.TOLERATED);
    assertThat(tolerated.rows().get(0).platformListing()).isEqualByComparingTo("103");

    // 把额度去掉：同样的余量就变成配置错误 → 必然超卖
    save(id.policyId(), new SellablePolicyInput(
        "SHOP", "CHANNEL", shopOne, "美国店", "EXCLUSIVE", null, false, BigDecimal.ZERO,
        "OWN_WAREHOUSE", new BigDecimal("0"), false, null, "收回超卖容忍", ownOnly()), actorA);
    var oversold = calculate(shopOne);
    assertThat(oversold.pool().headroom()).isEqualByComparingTo("0");
    assertThat(oversold.verdict()).isEqualTo(PoolSafetyVerdict.NO_HEADROOM);
  }

  // ================================================================ 口径逐项生效

  @Test
  void 含Fba开关真实改变池容量() {
    standardStock();
    // 仅自有仓：FBA 的 60 件不计入池容量（7.1.3.1：FBA 是平台观测值不是自有账本）
    save(shopPolicy(shopOne, "美国店", PoolMode.EXCLUSIVE, null, "0.20",
        PlatformStockScope.OWN_WAREHOUSE, ownAndFba(), false));
    var ownOnlyResult = calculate(shopOne);
    assertThat(ownOnlyResult.totalPoolCapacity()).isEqualByComparingTo("100");
    assertThat(ownOnlyResult.rows().get(0).platformListing()).isEqualByComparingTo("80");

    // 被排除的 FBA 量必须**报出来**，否则用户以为 FBA 已被计入
    var detail = access.run(actorA, "warehouse.inventory.read", () -> service.pool("EXCL-" + shopOne, null));
    assertThat(detail.excludedPlatformQuantity()).isEqualByComparingTo("60");
    assertThat(detail.note()).contains("FBA");

    // 含 FBA：同一个仓集合下容量变成 160
    var hit = access.run(actorA, "warehouse.inventory.read", () -> service.effective("SHOP", shopOne, null));
    save(hit.policy().id(),
        shopPolicy(shopOne, "美国店", PoolMode.EXCLUSIVE, null, "0.20",
            PlatformStockScope.OWN_PLUS_FBA, ownAndFba(), false), actorA);
    var withFba = calculate(shopOne);
    assertThat(withFba.totalPoolCapacity()).isEqualByComparingTo("160");
    assertThat(withFba.rows().get(0).platformListing()).isEqualByComparingTo("128");
  }

  @Test
  void 是否含在途真实改变池容量() {
    standardStock();
    // 不含在途：可用 100（残次 500 被排除，不是扣减）
    save(shopPolicy(shopOne, "美国店", PoolMode.EXCLUSIVE, null, "0.10",
        PlatformStockScope.OWN_WAREHOUSE, ownOnly(), false));
    var withoutTransit = calculate(shopOne);
    assertThat(withoutTransit.totalPoolCapacity()).isEqualByComparingTo("100");
    assertThat(withoutTransit.rows().get(0).qualifiedOnHand()).isEqualByComparingTo("100");
    assertThat(withoutTransit.rows().get(0).inTransitAdded()).isEqualByComparingTo("0");

    // 含在途：发往自有仓的 40 件被计入
    var hit = access.run(actorA, "warehouse.inventory.read", () -> service.effective("SHOP", shopOne, null));
    save(hit.policy().id(),
        shopPolicy(shopOne, "美国店", PoolMode.EXCLUSIVE, null, "0.10",
            PlatformStockScope.OWN_WAREHOUSE, ownOnly(), true), actorA);
    var withTransit = calculate(shopOne);
    assertThat(withTransit.totalPoolCapacity()).isEqualByComparingTo("140");
    assertThat(withTransit.rows().get(0).inTransitAdded()).isEqualByComparingTo("40");
    // 残次那一档既没被计入、也没出现在缓冲扣减里
    assertThat(withTransit.rows().get(0).bufferReserved()).isEqualByComparingTo("14");
  }

  @Test
  void 回写件数向下取整而不是四舍五入() {
    // 11 件 × 保留 5% = 10.45 件可回写，四舍五入会变成 10，凭空多卖半件
    balance(whOwn, qSellable, "11", "0");
    save(shopPolicy(shopOne, "美国店", PoolMode.EXCLUSIVE, null, "0.05",
        PlatformStockScope.OWN_WAREHOUSE, ownOnly(), false));
    var result = calculate(shopOne);
    assertThat(result.totalPoolCapacity()).isEqualByComparingTo("11");
    assertThat(result.rows().get(0).platformListing()).isEqualByComparingTo("10");
    // 名册与逐 SKU 用的是同一套口径，两个数不会互相打架
    assertThat(result.pool().committedListing()).isEqualByComparingTo("10");
  }

  @Test
  void 生效时刻带时区偏移往返零漂移() {
    standardStock();
    // 用一个非本机时区的偏移写入：走 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s。
    var written = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");
    var id = save(new SellablePolicyInput(
        "SHOP", "CHANNEL", shopOne, "美国店", "EXCLUSIVE", null, false, BigDecimal.ZERO,
        "OWN_WAREHOUSE", new BigDecimal("0.20"), false, written, "带偏移写入", ownOnly()));

    var readBack =
        access.run(actorA, "warehouse.inventory.read", () -> sql.fetchOne(
                "select effective_from from sellable_policy where id = ?", id.policyId()))
            .get("effective_from", OffsetDateTime.class);
    assertThat(readBack.toInstant()).isEqualTo(written.toInstant());
    assertThat(readBack.getOffset().getTotalSeconds()).isEqualTo(8 * 3600);
    // 按时刻解析也要认这个偏移：写进去之前那一刻用旧档/未配置，之后用新档
    assertThat(access.run(actorA, "warehouse.inventory.read",
            () -> service.effective("SHOP", shopOne, written.minusSeconds(1))).policy())
        .isNull();
    assertThat(access.run(actorA, "warehouse.inventory.read",
            () -> service.effective("SHOP", shopOne, written)).policy())
        .isNotNull();
  }

  // ================================================================ HTTP 与权限

  @Test
  void 端点契约与权限码() throws Exception {
    standardStock();
    save(shopPolicy(shopOne, "美国店", PoolMode.EXCLUSIVE, null, "0.20",
        PlatformStockScope.OWN_WAREHOUSE, ownOnly(), false));

    http.perform(get("/warehouse/inventory/sellable-policies").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.policies[0].bufferRatio").value(0.2));
    http.perform(get("/warehouse/inventory/sellable-policies/effective")
            .header("Authorization", "Bearer tenant-a")
            .param("scopeType", "SHOP")
            .param("referenceId", shopOne.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.policy.scopeType").value("SHOP"));
    http.perform(get("/warehouse/inventory/sellable-policies/pools").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
    http.perform(post("/warehouse/inventory/sellable-policies/calculation")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content("{\"skuIds\":[\"%s\"],\"scopeType\":\"SHOP\",\"referenceId\":\"%s\"}".formatted(sku, shopOne)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestedSkuCount").value(1))
        .andExpect(jsonPath("$.rows[0].platformListing").value(80));

    // 未认证 401
    http.perform(get("/warehouse/inventory/sellable-policies")).andExpect(status().isUnauthorized());

    // 改防超卖的最后一道防线必须挂写权限：只有读权限的人配不了缓冲比例。
    http.perform(post("/warehouse/inventory/sellable-policies")
            .header("Authorization", "Bearer read-only")
            .contentType("application/json")
            .content("{\"scopeType\":\"DEFAULT\",\"poolMode\":\"EXCLUSIVE\",\"platformStockScope\":"
                + "\"OWN_WAREHOUSE\",\"bufferRatio\":0.2}"))
        .andExpect(status().isForbidden());
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() throws Exception {
    standardStock();
    var policyA = save(shopPolicy(shopOne, "A 的店", PoolMode.EXCLUSIVE, null, "0.20",
        PlatformStockScope.OWN_WAREHOUSE, ownOnly(), false));

    // B 租户造一个同名店铺口径，缓冲不同，便于确认读到的是自己那一档而不是 A 的
    access.run(actorB, "warehouse.inventory.update", () -> {
          sql.execute(
              "insert into sellable_policy(id,tenant_id,scope_type,source_system,reference_id,reference_label,"
                  + "pool_mode,pool_key,oversell_allowed,oversell_tolerance,platform_stock_scope,buffer_ratio,"
                  + "include_in_transit,effective_from,note,created_by_membership_id) "
                  + "values (?,?,'SHOP','CHANNEL',?,'B 的店','EXCLUSIVE',?,false,0::numeric,'OWN_WAREHOUSE',"
                  + "0.60::numeric,false,?::timestamptz,'B 夹具',?)",
              UUID.randomUUID(), b, shopOne, "EXCL-" + shopOne, now(), user);
          return null;
        });

    var pageA = access.run(actorA, "warehouse.inventory.read",
        () -> service.page(new SellablePolicyQuery(null, null, null, false, null, 0, 50)));
    assertThat(pageA.total()).isEqualTo(1);
    assertThat(pageA.policies().get(0).id()).isEqualTo(policyA.policyId());
    assertThat(pageA.policies().get(0).bufferRatio()).isEqualByComparingTo("0.20");

    var pageB = access.run(actorB, "warehouse.inventory.read",
        () -> service.page(new SellablePolicyQuery(null, null, null, false, null, 0, 50)));
    assertThat(pageB.total()).isEqualTo(1);
    assertThat(pageB.policies().get(0).bufferRatio()).isEqualByComparingTo("0.60");

    // 拿 A 的 id 去 B 那里读 → 404（RLS 下查不到），不泄露"这条存在"
    assertThatThrownBy(() -> access.run(actorB, "warehouse.inventory.read", () -> service.detail(policyA.policyId())))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    standardStock();
    save(shopPolicy(shopOne, "A 的店", PoolMode.EXCLUSIVE, null, "0.20",
        PlatformStockScope.OWN_WAREHOUSE, ownOnly(), false));

    // 身份是 B、伪造 X-Tenant-Id 指向 A → B 只能看到 B 自己的（空）
    http.perform(get("/warehouse/inventory/sellable-policies")
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));

    // 身份是 A、伪造 B 的头 → A 仍然只看得到自己的那一档
    http.perform(get("/warehouse/inventory/sellable-policies")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.policies[0].referenceLabel").value("A 的店"));

    // 写入路径同样不受伪造头影响
    http.perform(post("/warehouse/inventory/sellable-policies")
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString())
            .contentType("application/json")
            .content("{\"scopeType\":\"DEFAULT\",\"poolMode\":\"EXCLUSIVE\",\"platformStockScope\":"
                + "\"OWN_WAREHOUSE\",\"bufferRatio\":0.9}"))
        .andExpect(status().isOk());
    http.perform(get("/warehouse/inventory/sellable-policies")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(jsonPath("$.policies[?(@.scopeType=='DEFAULT')]").isEmpty());
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    standardStock();
    var a1 = save(shopPolicy(shopOne, "A 的店", PoolMode.EXCLUSIVE, null, "0.20",
        PlatformStockScope.OWN_WAREHOUSE, ownOnly(), false), actorA);
    access.run(actorB, "warehouse.inventory.update", () -> {
          sql.execute(
              "insert into sellable_policy(id,tenant_id,scope_type,source_system,reference_id,reference_label,"
                  + "pool_mode,pool_key,oversell_allowed,oversell_tolerance,platform_stock_scope,buffer_ratio,"
                  + "include_in_transit,effective_from,note,created_by_membership_id) "
                  + "values (?,?,'SHOP','CHANNEL',?,'B 的店','EXCLUSIVE',?,false,0::numeric,'OWN_WAREHOUSE',"
                  + "0.60::numeric,false,?::timestamptz,'B 夹具',?)",
              UUID.randomUUID(), b, shopOne, "EXCL-" + shopOne, now(), user);
          return null;
        });

    // 池大小为 1，同一条连接被反复借还：交替跑两个租户 20 轮，
    // 每一轮都要同时校验写入、回查与解析三条路径都不串租户。
    for (int i = 0; i < 20; i++) {
      var pageA = access.run(actorA, "warehouse.inventory.read",
          () -> service.page(new SellablePolicyQuery("SHOP", shopOne, null, true, null, 0, 50)));
      var pageB = access.run(actorB, "warehouse.inventory.read",
          () -> service.page(new SellablePolicyQuery("SHOP", shopOne, null, true, null, 0, 50)));
      assertThat(pageA.policies()).hasSize(1);
      assertThat(pageB.policies()).hasSize(1);
      assertThat(pageA.policies().get(0).id()).isEqualTo(a1.policyId());
      assertThat(pageA.policies().get(0).bufferRatio()).isEqualByComparingTo("0.20");
      assertThat(pageB.policies().get(0).bufferRatio()).isEqualByComparingTo("0.60");

      // 解析路径：同名店铺在两个租户解析到各自的档
      assertThat(access.run(actorA, "warehouse.inventory.read", () -> service.effective("SHOP", shopOne, null))
              .policy().bufferRatio())
          .isEqualByComparingTo("0.20");
      assertThat(access.run(actorB, "warehouse.inventory.read", () -> service.effective("SHOP", shopOne, null))
              .policy().bufferRatio())
          .isEqualByComparingTo("0.60");
    }
  }

  @Test
  void 两张业务表都强制行级安全() {
    standardStock();
    // 没有 app.current_tenant 时直查表为空：RLS 真的在生效，不是应用层自觉过滤
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), "warehouse_app", "test-only");
        var s = c.createStatement()) {
      var rs = s.executeQuery("select count(*) from sellable_policy");
      rs.next();
      assertThat(rs.getLong(1)).isZero();
      // 逐张表确认 FORCE（relforcerowsecurity）
      for (var table : List.of("sellable_policy", "sellable_policy_warehouse")) {
        var meta = s.executeQuery(
            "select relforcerowsecurity from pg_class where relname = '" + table + "'");
        meta.next();
        assertThat(meta.getBoolean(1)).as(table + " 必须 FORCE RLS").isTrue();
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
