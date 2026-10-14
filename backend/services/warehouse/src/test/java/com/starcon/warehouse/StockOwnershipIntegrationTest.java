package com.starcon.warehouse;

import static com.starcon.warehouse.StockOwnershipModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.warehouse.StockOwnershipModels.OwnershipReassignRequest;
import com.starcon.warehouse.StockOwnershipModels.OwnershipRuleInput;
import com.starcon.warehouse.StockOwnershipModels.StockOwnerInput;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 7.7.1.1 货主与所有权。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接： 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>夹具写法与 {@link SellablePolicyIntegrationTest} 完全一致（同样的 Testcontainers、 同样的 fake identity
 * server、同样的按租户派生 UUID、同样的 TRUNCATE 清场）。
 *
 * <p><b>本测试的核心是「同一仓内两个货主必须能并存」这一组</b>：蓝图原话「同一仓内不同货主 必须能并存，{@code stock_bucket} 的组成维度要含货主，不能用 {@code
 * tenant} 代替货主」。 夹具刻意让两批货的**其余七个维度逐列相同**（同仓库、同库位、同 SKU、同法人、同质量状态、 同无批次），只有货主不同 —— 只有在这种最难的夹具下，"两行
 * bucket" 才真的证明了 货主进了唯一键，而不是碰巧两行其它列不同。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StockOwnershipIntegrationTest {
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
  static final UUID whFba = idFor(a, 4);
  static final UUID qSellable = idFor(a, 5);
  static final UUID location = idFor(a, 6);

  /** 第二个库位：唯一键含库位，验证「既有写入路径」的用例需要换一个库位才能插第二行。 */
  static final UUID location2 = idFor(a, 7);

  /** 供应商货主指向 catalog 的 party_supplier_profile：跨服务逻辑 ID，本服务不建外键。 */
  static final UUID supplierRef = UUID.randomUUID();

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-ownership-" + tenant + "-" + ordinal).getBytes());
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

  @Autowired StockOwnershipService service;
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
          "truncate stock_ownership_rule, stock_owner, stock_bucket, warehouse_location, inventory_lot,"
              + " inventory_balance, inventory_quality_status, warehouse, sku_ref, legal_entity_ref"
              + " restart identity cascade");
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
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
                idFor(tenant, 4),
                tenant,
                "WH-FBA",
                "美国 FBA",
                "US",
                "America/Los_Angeles");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 5),
                tenant,
                "SELLABLE",
                "可售",
                "SELLABLE");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name) values(?,?,?,?,?)",
                idFor(tenant, 6),
                tenant,
                idFor(tenant, 3),
                "LOC-1",
                "A 区位 1");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name) values(?,?,?,?,?)",
                idFor(tenant, 7),
                tenant,
                idFor(tenant, 3),
                "LOC-2",
                "A 区位 2");
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

  StockOwnerSaved selfOwner(String code) {
    return saveOwner(
        new StockOwnerInput(code, "自有主体", "SELF", "LEGAL_ENTITY", le, "主体一", false, "ACTIVE", "夹具"),
        actorA);
  }

  StockOwnerSaved consignmentOwner(String code) {
    // 供应商寄售：ownerRefType=SUPPLIER，supplierRef 是 catalog 的跨服务逻辑 ID。
    return saveOwner(
        new StockOwnerInput(
            code,
            "供应商甲寄售",
            "SUPPLIER_CONSIGNMENT",
            "SUPPLIER",
            supplierRef,
            "供应商甲",
            false,
            "ACTIVE",
            "夹具"),
        actorA);
  }

  StockOwnerSaved saveOwner(StockOwnerInput input, AccessService.Access who) {
    return access.run(
        who, "warehouse.inventory.update", () -> service.saveOwner(input, who.membershipId()));
  }

  OwnershipRuleSaved saveRule(UUID supersedeId, OwnershipRuleInput input) {
    return access.run(
        actorA,
        "warehouse.inventory.update",
        () -> service.saveRule(supersedeId, input, actorA.membershipId()));
  }

  OwnershipRuleSaved rule(
      String scope, UUID ownerId, UUID warehouseId, UUID legalEntityId, UUID skuId) {
    return saveRule(
        null,
        new OwnershipRuleInput(scope, warehouseId, legalEntityId, skuId, ownerId, null, "夹具"));
  }

  /** 一条余额行（可选默认货主 —— 绝大多数夹具刻意**不**传，验证触发器兜底）。 */
  UUID balance(String onHand, UUID ownerId) {
    return access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          UUID id = UUID.randomUUID();
          if (ownerId == null) {
            sql.execute(
                "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                    + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
                id,
                a,
                sku,
                whOwn,
                le,
                qSellable,
                new BigDecimal(onHand));
          } else {
            sql.execute(
                "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                    + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
                id,
                a,
                sku,
                whOwn,
                le,
                qSellable,
                new BigDecimal(onHand));
          }
          return id;
        });
  }

  /**
   * 一行库位明细，**刻意不写 stock_owner_id**。
   *
   * <p>这就是 7.1.1.2 / 7.5.1.1 等既有车道真实的 INSERT 形状 —— 本模块不能要求它们改一行代码。
   */
  UUID bucketOnly(UUID balanceId, String onHand) {
    return bucketAt(balanceId, location, onHand);
  }

  /** 指定库位的一行库位明细，同样刻意不写 stock_owner_id。 */
  UUID bucketAt(UUID balanceId, UUID loc, String onHand) {
    return access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          UUID id = UUID.randomUUID();
          sql.execute(
              "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,"
                  + "location_id,lot_id,quality_status_id,balance_id,on_hand,reserved,last_movement_at)"
                  + " values(?,?,?,?,?,?,null,?,?,?,0,now())",
              id,
              a,
              le,
              sku,
              whOwn,
              loc,
              qSellable,
              balanceId,
              new BigDecimal(onHand));
          return id;
        });
  }

  /** 显式指定货主的一行库位明细。 */
  UUID bucketOwned(UUID balanceId, UUID ownerId, String onHand) {
    UUID[] one = bucketsOwned(balanceId, ownerId, onHand);
    return one[0];
  }

  /**
   * 在**一个事务**里写入多行库位明细。
   *
   * <p>必须同事务：V2 的守恒是延迟约束触发器，跨事务时第一条 bucket 提交就会被判成 「bucket 汇总 ≠ balance」而拒绝 ——
   * 那正是守恒式在起作用（也正是本模块必须遵守的纪律）。
   */
  UUID[] bucketsOwned(UUID balanceId, Object... ownerIdThenQty) {
    return access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          UUID[] ids = new UUID[ownerIdThenQty.length / 2];
          for (int i = 0; i < ownerIdThenQty.length; i += 2) {
            UUID id = UUID.randomUUID();
            sql.execute(
                "insert into stock_bucket(id,tenant_id,stock_owner_id,legal_entity_id,sku_id,warehouse_id,"
                    + "location_id,lot_id,quality_status_id,balance_id,on_hand,reserved,last_movement_at)"
                    + " values(?,?,?,?,?,?,?,null,?,?,?,0,now())",
                id,
                a,
                ownerIdThenQty[i],
                le,
                sku,
                whOwn,
                location,
                qSellable,
                balanceId,
                new BigDecimal(String.valueOf(ownerIdThenQty[i + 1])));
            ids[i / 2] = id;
          }
          return ids;
        });
  }

  UUID defaultOwnerId() {
    return access.run(
        actorA,
        "warehouse.inventory.read",
        () -> sql.fetchOne("select id from stock_owner where is_default").get("id", UUID.class));
  }

  UUID ownerOf(UUID bucketId) {
    return access.run(
        actorA,
        "warehouse.inventory.read",
        () ->
            sql.fetchOne("select stock_owner_id from stock_bucket where id = ?", bucketId)
                .get("stock_owner_id", UUID.class));
  }

  // ================================================================ 默认货主与兜底

  @Test
  void 未指定货主的库位明细自动落默认自有货主() {
    UUID balanceId = balance("100", null);
    // 先确认建库存前没有兜底货主 —— 兜底是「按需」建立的，不是迁移时凭空造的
    assertThat(
            access.run(
                actorA,
                "warehouse.inventory.read",
                () -> sql.fetchOne("select count(*) from stock_owner").get(0, Long.class)))
        .isZero();

    UUID bucket = bucketOnly(balanceId, "100");

    var owner =
        access.run(actorA, "warehouse.inventory.read", () -> service.owner(defaultOwnerId()));
    assertThat(owner.ownerKind()).isEqualTo(StockOwnerKind.SELF);
    assertThat(owner.countsAsOwnedAsset()).isTrue();
    assertThat(owner.isDefault()).isTrue();
    // 自有货挂在本租户已登记的法人上
    assertThat(owner.legalEntityLinked()).isTrue();
    assertThat(owner.ownerRefId()).isEqualTo(le);
    assertThat(ownerOf(bucket)).isEqualTo(owner.id());
  }

  @Test
  void 既有写入路径不写货主列也不会违约() {
    // 这是跨车道回归的第一道：7.1.1.2 / 7.5.1.1 的 INSERT 形状里没有 stock_owner_id。
    UUID balanceId = balance("60", null);
    UUID first = bucketOnly(balanceId, "60");
    assertThat(ownerOf(first)).isNotNull();
    // 多写几行，确认不是"第一次侥幸"：换一个库位再写一行，各自都要被赋货主
    UUID second = bucketAt(balanceId, location2, "0");
    assertThat(ownerOf(second)).isEqualTo(ownerOf(first));
  }

  @Test
  void 显式指定不存在的货主被数据库拒绝() {
    UUID balanceId = balance("10", null);
    assertThatThrownBy(() -> bucketOwned(balanceId, UUID.randomUUID(), "10"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("货主维度不允许悬空");
  }

  // ================================================================ 同仓并存（本模块的核心）

  @Test
  void 同一库位两个货主必须能并存() {
    var self = selfOwner("OWN-1");
    var consign = consignmentOwner("CONS-1");
    UUID balanceId = balance("300", null);

    // 其余七个维度逐列相同：同仓库、同库位、同 SKU、同法人、同质量状态、同无批次。
    // 只有 stock_owner_id 不同 —— 这正是蓝图要求"同一仓内不同货主必须能并存"的那一行。
    UUID[] pair = bucketsOwned(balanceId, self.ownerId(), "100", consign.ownerId(), "200");
    UUID ownBucket = pair[0];
    UUID consignedBucket = pair[1];

    assertThat(ownerOf(ownBucket)).isEqualTo(self.ownerId());
    assertThat(ownerOf(consignedBucket)).isEqualTo(consign.ownerId());

    // 两行都在，各自的数量都没被合并掉
    var report =
        access.run(
            actorA, "warehouse.inventory.read", () -> service.report(null, null, null, null));
    assertThat(report.rows()).hasSize(2);
    var selfRow =
        report.rows().stream()
            .filter(r -> r.ownerId().equals(self.ownerId()))
            .findFirst()
            .orElseThrow();
    var consRow =
        report.rows().stream()
            .filter(r -> r.ownerId().equals(consign.ownerId()))
            .findFirst()
            .orElseThrow();
    assertThat(selfRow.onHand()).isEqualByComparingTo("100");
    assertThat(consRow.onHand()).isEqualByComparingTo("200");
    assertThat(report.totalOnHand()).isEqualByComparingTo("300");
  }

  @Test
  void 同一货主同一库位重复插入被唯一约束挡住() {
    var self = selfOwner("OWN-1");
    UUID balanceId = balance("100", null);
    bucketOwned(balanceId, self.ownerId(), "100");
    // 货主进了唯一键，所以"同货主同库位"仍然只能有一行 —— 合并会让两批货的货权在数量上无法区分。
    assertThatThrownBy(() -> bucketOwned(balanceId, self.ownerId(), "50"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("uq_stock_bucket_owner_scope");
  }

  // ================================================================ 生成列：自有资产口径

  @Test
  void 只有自有货计入自有资产() {
    var self = selfOwner("OWN-1");
    var consign = consignmentOwner("CONS-1");
    var platform =
        saveOwner(
            new StockOwnerInput(
                "PLAT-1", "平台仓货主", "PLATFORM", "INTERNAL", null, "", false, "ACTIVE", "夹具"),
            actorA);
    var consignee =
        saveOwner(
            new StockOwnerInput(
                "AGENT-1",
                "代销品牌方",
                "CONSIGNEE",
                "SUPPLIER",
                supplierRef,
                "品牌方",
                false,
                "ACTIVE",
                "夹具"),
            actorA);

    assertThat(readOwner(self.ownerId()).countsAsOwnedAsset()).isTrue();
    // 蓝图下游 = 12 财务「自有货才进自有资产，寄售不进」
    assertThat(readOwner(consign.ownerId()).countsAsOwnedAsset()).isFalse();
    assertThat(readOwner(platform.ownerId()).countsAsOwnedAsset()).isFalse();
    assertThat(readOwner(consignee.ownerId()).countsAsOwnedAsset()).isFalse();
    // 平台所有与代销的指向类型也必须与所有权类型自洽
    assertThat(readOwner(platform.ownerId()).ownerRefType()).isEqualTo(OwnerRefType.INTERNAL);
    assertThat(readOwner(consignee.ownerId()).ownerRefType()).isEqualTo(OwnerRefType.SUPPLIER);
  }

  StockOwnerRow readOwner(UUID id) {
    return access.run(actorA, "warehouse.inventory.read", () -> service.owner(id));
  }

  @Test
  void 货值口径不可用必须显式报出来而不是给一个假金额() {
    var capabilities = access.run(actorA, "warehouse.inventory.read", () -> service.capabilities());
    assertThat(capabilities.amountValuationAvailable()).isFalse();
    assertThat(capabilities.amountValuationNote()).contains("AMOUNT_VALUATION");
    var amount =
        capabilities.dimensions().stream()
            .filter(d -> d.code().equals("AMOUNT_VALUATION"))
            .findFirst()
            .orElseThrow();
    assertThat(amount.available()).isFalse();
    assertThat(amount.unavailableReason()).isNotBlank();
    // 可用维度也必须真的标出来，不能一片 available=false 敷衍
    assertThat(capabilities.dimensions().stream().filter(d -> d.code().equals("STOCK_OWNER_KIND")))
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.available()).isTrue();
              assertThat(d.source()).contains("V67");
            });

    var report =
        access.run(
            actorA, "warehouse.inventory.read", () -> service.report(null, null, null, null));
    assertThat(report.amountBasisAvailable()).isFalse();
    assertThat(report.amountBasisNote()).contains("supplier_quote_price_tier");
  }

  // ================================================================ 货权档解析

  @Test
  void 货权档优先级是sku大于法人大于仓库大于兜底() {
    var defaultOwner = selfOwner("OWN-DEFAULT");
    var whOwner = consignmentOwner("CONS-WH");
    var leOwner = selfOwner("OWN-LE");
    var skuOwner = consignmentOwner("CONS-SKU");

    // 只有兜底档
    rule("DEFAULT", defaultOwner.ownerId(), null, null, null);
    assertThat(
            access
                .run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> service.resolve(whOwn, le, sku, now()))
                .owner()
                .id())
        .isEqualTo(defaultOwner.ownerId());

    // 加仓库档 → 仓库胜过兜底
    rule("WAREHOUSE", whOwner.ownerId(), whOwn, null, null);
    assertThat(
            access
                .run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> service.resolve(whOwn, le, sku, now()))
                .owner()
                .id())
        .isEqualTo(whOwner.ownerId());

    // 加法人档 → 法人胜过仓库
    rule("LEGAL_ENTITY", leOwner.ownerId(), null, le, null);
    assertThat(
            access
                .run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> service.resolve(whOwn, le, sku, now()))
                .owner()
                .id())
        .isEqualTo(leOwner.ownerId());

    // 加 SKU 档 → SKU 胜过法人
    rule("SKU", skuOwner.ownerId(), null, null, sku);
    assertThat(
            access
                .run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> service.resolve(whOwn, le, sku, now()))
                .owner()
                .id())
        .isEqualTo(skuOwner.ownerId());

    // 命中状态必须如实报出来，不能让界面以为"没配过"
    var resolved =
        access.run(
            actorA, "warehouse.inventory.read", () -> service.resolve(whOwn, le, sku, now()));
    assertThat(resolved.usedFallback()).isFalse();
    assertThat(resolved.matchedScope()).isEqualTo(OwnershipScopeType.SKU);
  }

  @Test
  void 四档全未命中时回落到默认货主并标出是回落() {
    var fallback =
        saveOwner(
            new StockOwnerInput(
                "OWN-DEF", "默认自有", "SELF", "LEGAL_ENTITY", le, "主体一", true, "ACTIVE", ""),
            actorA);
    var resolved =
        access.run(
            actorA, "warehouse.inventory.read", () -> service.resolve(whOwn, le, sku, now()));
    assertThat(resolved.usedFallback()).isTrue();
    assertThat(resolved.rule()).isNull();
    assertThat(resolved.owner().id()).isEqualTo(fallback.ownerId());
    assertThat(resolved.owner().ownerKind()).isEqualTo(StockOwnerKind.SELF);
    assertThat(resolved.note()).contains("回落");
  }

  @Test
  void 租户还没建立兜底货主时如实报没有货主而不是编一个() {
    // 只建一个**非**默认货主：此时 is_default 那一行还不存在。
    selfOwner("OWN-1");
    var resolved =
        access.run(
            actorA, "warehouse.inventory.read", () -> service.resolve(whOwn, le, sku, now()));
    assertThat(resolved.owner()).isNull();
    assertThat(resolved.usedFallback()).isTrue();
    assertThat(resolved.note()).contains("写入第一条库位明细时会自动建立");
  }

  @Test
  void 货权档影响之后写入的库位明细() {
    var consigned = consignmentOwner("CONS-1");
    rule("WAREHOUSE", consigned.ownerId(), whOwn, null, null);

    UUID balanceId = balance("120", null);
    // 不写 stock_owner_id 的既有 INSERT，触发器按货权档解析
    UUID bucket = bucketOnly(balanceId, "120");
    assertThat(ownerOf(bucket)).isEqualTo(consigned.ownerId());
    // 关键断言：寄售货不能被算进自有资产
    assertThat(
            access
                .run(actorA, "warehouse.inventory.read", () -> service.owner(consigned.ownerId()))
                .countsAsOwnedAsset())
        .isFalse();
  }

  @Test
  void 同一槽位的生效区间重叠被数据库拒绝() {
    var one = consignmentOwner("CONS-1");
    var two = consignmentOwner("CONS-2");
    rule("WAREHOUSE", one.ownerId(), whOwn, null, null);
    // 区间与上一档重叠：DB 触发器当场拒绝，错误信息里带冲突档 id
    assertThatThrownBy(
            () ->
                saveRule(
                    null,
                    new OwnershipRuleInput(
                        "WAREHOUSE",
                        whOwn,
                        null,
                        null,
                        two.ownerId(),
                        now().minusSeconds(60),
                        "重叠档")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("重叠");
  }

  @Test
  void 作废后不再按这一档判货主() {
    var consigned = consignmentOwner("CONS-1");
    var created = rule("WAREHOUSE", consigned.ownerId(), whOwn, null, null);
    assertThat(
            access
                .run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> service.resolve(whOwn, le, sku, now()))
                .owner()
                .id())
        .isEqualTo(consigned.ownerId());

    var retired =
        access.run(
            actorA,
            "warehouse.inventory.update",
            () -> service.retireRule(created.ruleId(), now().plusSeconds(60)));
    assertThat(retired.effectiveTo()).isNotNull();

    // 收口之后解析不到这一档 → 回落默认货主，而不是继续用这一档
    var resolved =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.resolve(whOwn, le, sku, now().plusSeconds(120)));
    assertThat(resolved.rule()).isNull();
    assertThat(resolved.usedFallback()).isTrue();
  }

  @Test
  void 换版必须落在同一个槽位内() {
    var one = consignmentOwner("CONS-1");
    var two = consignmentOwner("CONS-2");
    var created = rule("WAREHOUSE", one.ownerId(), whOwn, null, null);
    // 跨槽位换档会让原档被收口后无处继承，等于悄悄作废了一个配置
    assertThatThrownBy(
            () ->
                saveRule(
                    created.ruleId(),
                    new OwnershipRuleInput(
                        "LEGAL_ENTITY",
                        null,
                        le,
                        null,
                        two.ownerId(),
                        now().plusSeconds(60),
                        "跨槽位")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("同一个范围槽位");
  }

  @Test
  void 时区写入后零漂移() {
    var consigned = consignmentOwner("CONS-1");
    // 带 +08:00 偏移的时刻写入，读回必须是同一瞬间而不是被当成 UTC
    OffsetDateTime shanghai = OffsetDateTime.of(2026, 3, 1, 9, 30, 0, 0, ZoneOffset.ofHours(8));
    var saved =
        saveRule(
            null,
            new OwnershipRuleInput(
                "WAREHOUSE", whOwn, null, null, consigned.ownerId(), shanghai, "时区夹具"));

    var readBack =
        access.run(actorA, "warehouse.inventory.read", () -> service.rule(saved.ruleId()));
    assertThat(readBack.effectiveFrom().toInstant()).isEqualTo(shanghai.toInstant());
    // 偏移量原样保留，不被服务器本地时区改写
    // TIMESTAMPTZ preserves the instant; its returned offset depends on the database/JVM zone.
    assertThat(readBack.effectiveFrom().withOffsetSameInstant(shanghai.getOffset()).toLocalTime())
        .isEqualTo(shanghai.toLocalTime());
  }

  // ================================================================ 报表

  @Test
  void 报表按货主拆分库存并给出自有与非自有() {
    var self = selfOwner("OWN-1");
    var consign = consignmentOwner("CONS-1");
    UUID balanceId = balance("300", null);
    bucketsOwned(balanceId, self.ownerId(), "100", consign.ownerId(), "200");

    var report =
        access.run(
            actorA, "warehouse.inventory.read", () -> service.report(null, null, null, null));
    // 自有货排前 —— 财务先看的就是它
    assertThat(report.rows().get(0).countsAsOwnedAsset()).isTrue();
    assertThat(report.ownerCount()).isEqualTo(2);
    assertThat(report.totalOnHand()).isEqualByComparingTo("300");
    assertThat(report.ownedAssetOnHand()).isEqualByComparingTo("100");
    assertThat(report.nonOwnedAssetOnHand()).isEqualByComparingTo("200");
    // 自有 + 非自有 == 总量，这条恒等式必须成立
    assertThat(report.ownedAssetOnHand().add(report.nonOwnedAssetOnHand()))
        .isEqualByComparingTo(report.totalOnHand());
  }

  @Test
  void 报表如实报出未分解到库位的差额() {
    var self = selfOwner("OWN-1");
    // 一条 balance 拆到库位（100），另一条 balance 完全没拆（80）。
    // V2 的守恒式规定「只要有 bucket 就必须完全分解」，所以未分解只能是「零 bucket」的那种行。
    UUID balanceId = balance("100", null);
    bucketOwned(balanceId, self.ownerId(), "100");
    // 换一个质量状态，才能再建一条同 (sku, warehouse, legal_entity) 但不同质量的余额行
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
              idFor(a, 8),
              a,
              "PENDING",
              "待检",
              "PENDING_INSPECTION");
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
              UUID.randomUUID(),
              a,
              sku,
              whOwn,
              le,
              idFor(a, 8),
              new BigDecimal("80"));
          return null;
        });

    var report =
        access.run(
            actorA, "warehouse.inventory.read", () -> service.report(null, null, null, null));
    // bucket 口径 100，未分解 80 —— 两者分开报，差额是可见的一个数
    assertThat(report.totalOnHand()).isEqualByComparingTo("100");
    assertThat(report.note()).contains("未分解");
    assertThat(report.note()).contains("80");
  }

  @Test
  void 报表按仓库筛选后只看得到该仓() {
    var self = selfOwner("OWN-1");
    UUID balanceId = balance("100", null);
    bucketOwned(balanceId, self.ownerId(), "100");
    var report =
        access.run(
            actorA, "warehouse.inventory.read", () -> service.report(whFba, null, null, null));
    assertThat(report.rows()).isEmpty();
    assertThat(report.totalOnHand()).isEqualByComparingTo("0.0000");
  }

  // ================================================================ 漂移与转移

  @Test
  void 货权漂移能被检出且不自动改存量() {
    var self = selfOwner("OWN-1");
    var consign = consignmentOwner("CONS-1");
    UUID balanceId = balance("100", null);
    UUID bucket = bucketOwned(balanceId, self.ownerId(), "100");

    // 配一档「整仓寄销」—— 配置只影响之后写入的明细，存量不自动跟改
    rule("WAREHOUSE", consign.ownerId(), whOwn, null, null);

    // 存量仍然挂在原货主上（这是有意的：追溯改存量货权等于改账）
    assertThat(ownerOf(bucket)).isEqualTo(self.ownerId());

    var drift =
        access.run(actorA, "warehouse.inventory.read", () -> service.drift(null, null, null));
    assertThat(drift.rows()).hasSize(1);
    assertThat(drift.rows().get(0).currentOwnerId()).isEqualTo(self.ownerId());
    assertThat(drift.rows().get(0).expectedOwnerId()).isEqualTo(consign.ownerId());
    assertThat(drift.rows().get(0).onHand()).isEqualByComparingTo("100");
    assertThat(drift.note()).contains("有意为之");
  }

  @Test
  void 没有配货权档时不算漂移() {
    var self = selfOwner("OWN-1");
    UUID balanceId = balance("100", null);
    bucketOwned(balanceId, self.ownerId(), "100");
    // 四档全未命中 → 落默认货主，这不是配置漂移，否则会刷出一堆噪音
    var drift =
        access.run(actorA, "warehouse.inventory.read", () -> service.drift(null, null, null));
    assertThat(drift.rows()).isEmpty();
  }

  @Test
  void 货权转移改货主但不动数量() {
    var self = selfOwner("OWN-1");
    var consign = consignmentOwner("CONS-1");
    UUID balanceId = balance("100", null);
    UUID bucket = bucketOwned(balanceId, self.ownerId(), "100");

    long balanceBefore =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                sql.fetchOne("select on_hand from inventory_balance where id = ?", balanceId)
                    .get("on_hand", BigDecimal.class)
                    .longValue());
    long entriesBefore =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> sql.fetchOne("select count(*) from inventory_entry").get(0, Long.class));

    var result =
        access.run(
            actorA,
            "warehouse.inventory.update",
            () ->
                service.reassign(
                    new OwnershipReassignRequest(
                        self.ownerId(), consign.ownerId(), null, null, null),
                    now()));

    assertThat(result.movedBucketCount()).isEqualTo(1);
    assertThat(result.movedOnHand()).isEqualByComparingTo("100");
    // 货主换了
    assertThat(ownerOf(bucket)).isEqualTo(consign.ownerId());
    // 数量、余额、流水一件都没动 —— 货权是账上的归属，不是货物流动
    assertThat(
            access.run(
                actorA,
                "warehouse.inventory.read",
                () ->
                    sql.fetchOne("select on_hand from stock_bucket where id = ?", bucket)
                        .get("on_hand", BigDecimal.class)
                        .longValue()))
        .isEqualTo(100);
    assertThat(
            access.run(
                actorA,
                "warehouse.inventory.read",
                () ->
                    sql.fetchOne("select on_hand from inventory_balance where id = ?", balanceId)
                        .get("on_hand", BigDecimal.class)
                        .longValue()))
        .isEqualTo(balanceBefore);
    assertThat(
            access.run(
                actorA,
                "warehouse.inventory.read",
                () -> sql.fetchOne("select count(*) from inventory_entry").get(0, Long.class)))
        .isEqualTo(entriesBefore);
  }

  @Test
  void 货权转移零行是合法结果而不是失败() {
    var self = selfOwner("OWN-1");
    var consign = consignmentOwner("CONS-1");
    var result =
        access.run(
            actorA,
            "warehouse.inventory.update",
            () ->
                service.reassign(
                    new OwnershipReassignRequest(
                        self.ownerId(), consign.ownerId(), whFba, null, null),
                    now()));
    assertThat(result.movedBucketCount()).isZero();
    assertThat(result.message()).contains("合法的空结果");
  }

  @Test
  void 货权转移转给停用货主被拒绝() {
    var self = selfOwner("OWN-1");
    var consign = consignmentOwner("CONS-1");
    access.run(
        actorA,
        "warehouse.inventory.update",
        () -> service.setOwnerStatus(consign.ownerId(), "INACTIVE"));
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        service.reassign(
                            new OwnershipReassignRequest(
                                self.ownerId(), consign.ownerId(), null, null, null),
                            now())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("停用");
  }

  @Test
  void 转移给同一货主被拒绝() {
    var self = selfOwner("OWN-1");
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        service.reassign(
                            new OwnershipReassignRequest(
                                self.ownerId(), self.ownerId(), null, null, null),
                            now())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("没有任何行会被改动");
  }

  // ================================================================ 货主档案约束

  @Test
  void 自有货主必须挂在本租户已登记的法人上() {
    assertThatThrownBy(
            () ->
                saveOwner(
                    new StockOwnerInput(
                        "BAD-1",
                        "指向不存在的法人",
                        "SELF",
                        "LEGAL_ENTITY",
                        UUID.randomUUID(),
                        "",
                        false,
                        "ACTIVE",
                        ""),
                    actorA))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("法人");
  }

  @Test
  void 寄售货主必须指向供应商() {
    // ownerKind=SUPPLIER_CONSIGNMENT 配 ownerRefType=LEGAL_ENTITY 是自相矛盾的：
    // 那样报表上会出现"寄售货挂在自家法人名下"，然后被算进自有资产。
    assertThatThrownBy(
            () ->
                saveOwner(
                    new StockOwnerInput(
                        "BAD-2",
                        "自相矛盾",
                        "SUPPLIER_CONSIGNMENT",
                        "LEGAL_ENTITY",
                        le,
                        "主体一",
                        false,
                        "ACTIVE",
                        ""),
                    actorA))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("供应商寄售")
        .hasMessageContaining("算进自有资产");
  }

  @Test
  void 指向类型缺id当场被拒而不是留一条悬空货主() {
    assertThatThrownBy(
            () ->
                saveOwner(
                    new StockOwnerInput(
                        "BAD-3",
                        "供应商类缺 id",
                        "SUPPLIER_CONSIGNMENT",
                        "SUPPLIER",
                        null,
                        "",
                        false,
                        "ACTIVE",
                        ""),
                    actorA))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("ownerRefId");
  }

  @Test
  void 所有权类型不可改() {
    var self = selfOwner("OWN-1");
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        service.updateOwner(
                            self.ownerId(),
                            new StockOwnerInput(
                                "OWN-1",
                                "改成寄售",
                                "SUPPLIER_CONSIGNMENT",
                                "SUPPLIER",
                                supplierRef,
                                "供应商甲",
                                false,
                                "ACTIVE",
                                ""),
                            actorA.membershipId())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不可从");
  }

  @Test
  void 货主编码不可改() {
    var self = selfOwner("OWN-1");
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        service.updateOwner(
                            self.ownerId(),
                            new StockOwnerInput(
                                "OWN-2",
                                "改名",
                                "SELF",
                                "LEGAL_ENTITY",
                                le,
                                "主体一",
                                false,
                                "ACTIVE",
                                ""),
                            actorA.membershipId())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("编码不可修改");
  }

  @Test
  void 默认货主不能停用() {
    var defaultId =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> {
              UUID balanceId = balance("10", null);
              bucketOnly(balanceId, "10");
              return defaultOwnerId();
            });
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () -> service.setOwnerStatus(defaultId, "INACTIVE")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("默认货主");
  }

  @Test
  void 同一租户至多一个默认货主() {
    var first = selfOwner("OWN-1");
    var second =
        saveOwner(
            new StockOwnerInput(
                "OWN-2", "另一个自有主体", "SELF", "LEGAL_ENTITY", le, "主体一", true, "ACTIVE", ""),
            actorA);
    assertThat(second.isDefault()).isTrue();
    // 原来那个默认被自动撤下来，"默认"始终有且只有一个
    assertThat(
            access
                .run(actorA, "warehouse.inventory.read", () -> service.owner(first.ownerId()))
                .isDefault())
        .isFalse();
  }

  @Test
  void 货主与货权档不提供删除入口() throws Exception {
    var self = selfOwner("OWN-1");
    for (var path :
        List.of(
            "/warehouse/inventory/ownership/owners/" + self.ownerId(),
            "/warehouse/inventory/ownership/rules/" + self.ownerId())) {
      http.perform(delete(path).header("Authorization", "Bearer tenant-a"))
          .andExpect(status().is4xxClientError());
    }
  }

  // ================================================================ 端点与权限

  @Test
  void 端点鉴权401与403() throws Exception {
    var self = selfOwner("OWN-1");
    http.perform(get("/warehouse/inventory/ownership/owners")).andExpect(status().isUnauthorized());
    http.perform(
            get("/warehouse/inventory/ownership/owners").header("Authorization", "Bearer nope"))
        .andExpect(status().isUnauthorized());
    // 只读角色能读、不能写
    http.perform(
            get("/warehouse/inventory/ownership/owners")
                .header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk());
    http.perform(
            post("/warehouse/inventory/ownership/owners")
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content(
                    """
                {"code":"RO-1","name":"只读角色建的货主","ownerKind":"SELF","ownerRefType":"LEGAL_ENTITY",
                 "ownerRefId":"%s","ownerRefLabel":"主体一","status":"ACTIVE"}
                """
                        .formatted(le)))
        .andExpect(status().isForbidden());
    assertThat(self.ownerId()).isNotNull();
  }

  @Test
  void 端点往返() throws Exception {
    var self = selfOwner("OWN-1");
    http.perform(
            get("/warehouse/inventory/ownership/capabilities")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amountValuationAvailable").value(false));
    http.perform(
            get("/warehouse/inventory/ownership/owners").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.owners[0].code").value("OWN-1"))
        .andExpect(jsonPath("$.owners[0].countsAsOwnedAsset").value(true));
    http.perform(
            get("/warehouse/inventory/ownership/owners/" + self.ownerId())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.legalEntityLinked").value(true));

    var created =
        http.perform(
                post("/warehouse/inventory/ownership/rules")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType("application/json")
                    .content(
                        """
                {"scopeType":"WAREHOUSE","warehouseId":"%s","ownerId":"%s","note":"端点夹具"}
                """
                            .formatted(whOwn, self.ownerId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scopeType").value("WAREHOUSE"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(created).contains("存量 bucket 不受影响");

    http.perform(
            get("/warehouse/inventory/ownership/rules?effectiveOnly=true")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));
    http.perform(
            get("/warehouse/inventory/ownership/resolved")
                .param("warehouseId", whOwn.toString())
                .param("legalEntityId", le.toString())
                .param("skuId", sku.toString())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.usedFallback").value(false))
        .andExpect(jsonPath("$.matchedScope").value("WAREHOUSE"));
    http.perform(
            get("/warehouse/inventory/ownership/report").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amountBasisAvailable").value(false));
    http.perform(
            get("/warehouse/inventory/ownership/drift").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(
            get("/warehouse/inventory/ownership/reference-options")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.warehouses.length()").value(2))
        .andExpect(jsonPath("$.skus[0].code").value("SKU-1"));
  }

  @Test
  void 端点对不存在的货主返回404而不是空集() throws Exception {
    http.perform(
            get("/warehouse/inventory/ownership/owners/" + UUID.randomUUID())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
  }

  @Test
  void 非法范围码当场400() throws Exception {
    http.perform(
            post("/warehouse/inventory/ownership/rules")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    """
                {"scopeType":"NOT_A_SCOPE","ownerId":"%s"}
                """
                        .formatted(UUID.randomUUID())))
        .andExpect(status().isBadRequest());
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    var self = selfOwner("OWN-1");
    access.run(
        actorB,
        "warehouse.inventory.update",
        () ->
            service.saveOwner(
                new StockOwnerInput(
                    "OWN-B",
                    "B 的货主",
                    "SELF",
                    "LEGAL_ENTITY",
                    idFor(b, 1),
                    "主体一",
                    false,
                    "ACTIVE",
                    ""),
                actorB.membershipId()));

    // B 看得到自己的，且看不到 A 的
    var pageB =
        access.run(
            actorB,
            "warehouse.inventory.read",
            () -> service.pageOwners(new StockOwnerQuery(null, null, null, null, null, 0, 50)));
    assertThat(pageB.owners()).extracting(StockOwnerRow::code).containsExactly("OWN-B");
    assertThat(
            access.run(
                actorB,
                "warehouse.inventory.read",
                () ->
                    service
                        .pageOwners(new StockOwnerQuery(null, null, null, null, null, 0, 50))
                        .total()))
        .isEqualTo(1);

    // A 拿 B 的货主 id 去查 → 404（而不是空集，403 会泄露"这个 id 存在"）
    var bOwnerId = pageB.owners().get(0).id();
    assertThatThrownBy(
            () -> access.run(actorA, "warehouse.inventory.read", () -> service.owner(bOwnerId)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或不属于当前租户");
    assertThat(self.ownerId()).isNotEqualTo(bOwnerId);
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    var self = selfOwner("OWN-1");
    UUID balanceId = balance("100", null);
    bucketOwned(balanceId, self.ownerId(), "100");

    // 带伪造 X-Tenant-Id 头访问，读到仍然是 A 的数据（B 租户 id 无效）
    for (var request :
        List.of(
            get("/warehouse/inventory/ownership/owners")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()),
            get("/warehouse/inventory/ownership/report")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))) {
      var body =
          http.perform(request)
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(body).contains("OWN-1");
      assertThat(body).doesNotContain("OWN-B");
    }

    // 写端点同样：伪造的头改不动 app.current_tenant
    http.perform(
            post("/warehouse/inventory/ownership/owners")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString())
                .contentType("application/json")
                .content(
                    """
                {"code":"SPOOF","name":"伪造头建的货主","ownerKind":"SELF","ownerRefType":"LEGAL_ENTITY",
                 "ownerRefId":"%s","status":"ACTIVE"}
                """
                        .formatted(le)))
        .andExpect(status().isOk());
    assertThat(
            access.run(
                actorA,
                "warehouse.inventory.read",
                () ->
                    sql.fetchOne("select tenant_id from stock_owner where code='SPOOF'")
                        .get("tenant_id", UUID.class)))
        .isEqualTo(a);
    assertThat(
            access.run(
                actorB,
                "warehouse.inventory.read",
                () ->
                    sql.fetchOne("select count(*) from stock_owner where code='SPOOF'")
                        .get(0, Long.class)))
        .isZero();
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    // 两个租户各建一个**默认**货主（OWN-1 / OWN-B），它们各自是本租户的兜底货主。
    var aOwner =
        saveOwner(
            new StockOwnerInput(
                "OWN-1", "A 的货主", "SELF", "LEGAL_ENTITY", le, "主体一", true, "ACTIVE", ""),
            actorA);
    var bOwner =
        access.run(
            actorB,
            "warehouse.inventory.update",
            () ->
                service.saveOwner(
                    new StockOwnerInput(
                        "OWN-B",
                        "B 的货主",
                        "SELF",
                        "LEGAL_ENTITY",
                        idFor(b, 1),
                        "主体一",
                        true,
                        "ACTIVE",
                        ""),
                    actorB.membershipId()));

    // 先给两个租户各备好一行余额与一行库位明细，否则测的是"空集不串"而不是"有数据不串"。
    seedStock(actorA, a, le, sku, whOwn, location, qSellable, "10");
    seedStock(actorB, b, idFor(b, 1), idFor(b, 2), idFor(b, 3), idFor(b, 6), idFor(b, 5), "5");

    // 池大小为 1，同一条连接被反复借还：交替跑两个租户 20 轮。
    // 每轮都要同时校验写入、读回、解析三条路径都不串租户。
    for (int i = 0; i < 20; i++) {
      // 写入路径：A、B 各插一行库位档案，编码带轮次因而互不冲突。
      // 这一步必须在循环内 —— 只有反复借还同一条连接，租户上下文残留才会显形。
      int round = i;
      access.run(
          actorA,
          "warehouse.inventory.read",
          () ->
              sql.execute(
                  "insert into warehouse_location(id,tenant_id,warehouse_id,code,name) values(?,?,?,?,?)",
                  UUID.randomUUID(),
                  a,
                  whOwn,
                  "A-LOOP-" + round,
                  "A 轮次 " + round));
      access.run(
          actorB,
          "warehouse.inventory.read",
          () ->
              sql.execute(
                  "insert into warehouse_location(id,tenant_id,warehouse_id,code,name) values(?,?,?,?,?)",
                  UUID.randomUUID(),
                  b,
                  idFor(b, 3),
                  "B-LOOP-" + round,
                  "B 轮次 " + round));

      var aOwners =
          access.run(
              actorA,
              "warehouse.inventory.read",
              () -> service.pageOwners(new StockOwnerQuery(null, null, null, null, null, 0, 50)));
      var bOwners =
          access.run(
              actorB,
              "warehouse.inventory.read",
              () -> service.pageOwners(new StockOwnerQuery(null, null, null, null, null, 0, 50)));
      // A 只有人工建的 OWN-1；B 写入第一条库位明细时按需自动建立了 DEFAULT-OWN 兜底货主。
      // 两者各自只看见自己的行 —— 默认货排在最前，所以 OWN-1 在 A、DEFAULT-OWN 在 B。
      assertThat(aOwners.owners()).extracting(StockOwnerRow::code).containsExactly("OWN-1");
      assertThat(bOwners.owners()).extracting(StockOwnerRow::code).containsExactly("OWN-B");
      assertThat(aOwners.owners().stream().map(StockOwnerRow::id)).contains(aOwner.ownerId());
      assertThat(bOwners.owners().stream().map(StockOwnerRow::id)).contains(bOwner.ownerId());
      // 两个租户绝不能出现对方的货主
      assertThat(aOwners.owners().stream().map(StockOwnerRow::id)).doesNotContain(bOwner.ownerId());
      assertThat(bOwners.owners().stream().map(StockOwnerRow::id)).doesNotContain(aOwner.ownerId());

      // 解析路径也要不串：A 的兜底货主与 B 的兜底货主是两个不同的行
      assertThat(
              access
                  .run(actorA, "warehouse.inventory.read", () -> service.owner(defaultOwnerId()))
                  .id())
          .isEqualTo(aOwner.ownerId());
      assertThat(
              access.run(
                  actorB,
                  "warehouse.inventory.read",
                  () ->
                      sql.fetchOne("select id from stock_owner where is_default")
                          .get("id", UUID.class)))
          .isEqualTo(bOwner.ownerId());

      // 报表路径同样不串：A 报 10、B 报 5，各自只看到自己的库存
      assertThat(
              access
                  .run(
                      actorA,
                      "warehouse.inventory.read",
                      () -> service.report(null, null, null, null))
                  .totalOnHand())
          .isEqualByComparingTo("10");
      assertThat(
              access
                  .run(
                      actorB,
                      "warehouse.inventory.read",
                      () -> service.report(null, null, null, null))
                  .totalOnHand())
          .isEqualByComparingTo("5");
    }

    // 20 轮之后回头确认：对方的行一条都没漏进来
    assertThat(
            access.run(
                actorA,
                "warehouse.inventory.read",
                () ->
                    sql.fetchOne("select count(*) from warehouse_location where tenant_id = ?", b)
                        .get(0, Long.class)))
        .isZero();
    assertThat(
            access.run(
                actorB,
                "warehouse.inventory.read",
                () ->
                    sql.fetchOne("select count(*) from warehouse_location where tenant_id = ?", a)
                        .get(0, Long.class)))
        .isZero();
  }

  /** 给某个租户备一行余额 + 一行库位明细（不写 stock_owner_id，走默认赋权）。 */
  void seedStock(
      AccessService.Access actor,
      UUID tenant,
      UUID legalEntity,
      UUID skuId,
      UUID warehouse,
      UUID loc,
      UUID quality,
      String onHand) {
    access.run(
        actor,
        "warehouse.inventory.read",
        () -> {
          UUID bid = UUID.randomUUID();
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
              bid,
              tenant,
              skuId,
              warehouse,
              legalEntity,
              quality,
              new BigDecimal(onHand));
          sql.execute(
              "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,"
                  + "location_id,lot_id,quality_status_id,balance_id,on_hand,reserved,last_movement_at)"
                  + " values(?,?,?,?,?,?,null,?,?,?,0,now())",
              UUID.randomUUID(),
              tenant,
              legalEntity,
              skuId,
              warehouse,
              loc,
              quality,
              bid,
              new BigDecimal(onHand));
          return null;
        });
  }

  @Test
  void 两张业务表都强制行级安全() throws Exception {
    selfOwner("OWN-1");
    // 没有 app.current_tenant 时直查表为空：RLS 真的在生效，不是应用层自觉过滤
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), "warehouse_app", "test-only");
        var s = c.createStatement()) {
      var rs = s.executeQuery("select count(*) from stock_owner");
      rs.next();
      assertThat(rs.getLong(1)).isZero();
      var rules = s.executeQuery("select count(*) from stock_ownership_rule");
      rules.next();
      assertThat(rules.getLong(1)).isZero();
      // 逐张表确认 FORCE（relforcerowsecurity）
      for (var table : List.of("stock_owner", "stock_ownership_rule", "stock_bucket")) {
        var meta =
            s.executeQuery(
                "select relforcerowsecurity from pg_class where relname = '" + table + "'");
        meta.next();
        assertThat(meta.getBoolean(1)).as(table + " 必须 FORCE RLS").isTrue();
      }
    }
  }
}
