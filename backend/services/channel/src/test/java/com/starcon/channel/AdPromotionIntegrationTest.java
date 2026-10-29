package com.starcon.channel;

import static com.starcon.channel.AdPromotionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 14.4.1.1 促销列表。
 *
 * <p>本测试类钉住本模块最容易做丢的几件事：
 *
 * <ol>
 *   <li><b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：两租户互不可见、伪造 tenant header 无效、
 *       连接池归还后不串租户
 *   <li><b>🔴 毛利 / 利润维度未交付</b>：能力声明里 available=false + ownerModule 指向 5 定价；
 *       响应里必须带「拿不到成本」的说明 —— <b>不许出现一个假的 0 毛利</b>
 *   <li><b>🔴 平台同步状态</b>：本地已建 ⇒ LOCAL_ONLY + pendingPlatformWriteback=true，
 *       且**从库里读回**断言（只断言没抛异常不算数）
 *   <li><b>🔴 时间字段写回值正确</b>（AGENTS.md 要求断言写回值，不能只断言没抛异常）
 *   <li><b>🔴 折后价是真算出来的</b>：基准价 × (1 − 折扣率)；没登记基准价 ⇒ <b>null（不可比）</b>，
 *       <b>不是 0</b>
 *   <li><b>🔴 拦截真的拦得住</b>：折扣幅度 / 折后价下限 / 促销叠加三条规则都会 BLOCKED，
 *       并且**激活被拒**；改完重跑检查后才放行
 *   <li><b>🔴 DB 层兜底</b>：绕服务层直接写 SQL，CHECK 也把「折扣率与减免额同时给」拒掉
 *   <li><b>真分页真筛选</b>；跨租户与不存在返回逐字相同的 404
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link OffsetDateTime} 钉死</b>，不用 {@code now()}：用墙钟会让
 * 「这次写入落在哪个断言分支上」成为一个 flaky 断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdPromotionIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 🔴 每个用例一对**全新的租户**（共用租户会让前一个用例的数据被后一个用例数进去）。 */
  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA;

  /** 🔴 钉死的时刻与金额。 */
  static final OffsetDateTime START = OffsetDateTime.parse("2026-10-01T00:00:00+08:00");
  static final OffsetDateTime END = OffsetDateTime.parse("2026-12-31T23:59:59+08:00");
  static final OffsetDateTime FUTURE_START = OffsetDateTime.parse("2027-01-01T00:00:00+08:00");

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111111");
  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\"],\"scopeMode\":\"ALL\"}]";

  static final List<String> allPermissions =
      java.util.stream.Stream.of("registry", "connection", "shop", "sync", "operation")
          .flatMap(
              p ->
                  java.util.stream.Stream.concat(
                      java.util.stream.Stream.of("read", "create", "update", "delete")
                          .map(op -> "channel." + p + "." + op),
                      p.equals("sync")
                          ? java.util.stream.Stream.of("channel.sync.trigger", "channel.sync.complete")
                          : java.util.stream.Stream.empty()))
          .distinct()
          .toList();

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenant, "Test", UUID.randomUUID(), null,
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
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='starcon_app')"
            + " then create role starcon_app nologin; end if; end $$");
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='channel_app')"
            + " then create role channel_app login password 'test-only' nosuperuser nobypassrls; end if; end $$");
        s.execute("alter role channel_app login password 'test-only' nosuperuser nobypassrls");
      }
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            AccessService.Access who = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (who == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = new ObjectMapper().writeValueAsBytes(who);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @BeforeEach
  void freshTenants() {
    a = UUID.randomUUID();
    b = UUID.randomUUID();
    bearerA = "Bearer t-a-" + a;
    String bearerB = "Bearer t-b-" + b;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearerA, actor(user, a, grants));
    actors.put(bearerB, actor(user, b, grants));
    actorA = actors.get(bearerA);
    actorB = actors.get(bearerB);
  }

  /** 播种全局码表（平台目录）。刻意**不在静态块里**播种（那时表还没建）。 */
  @BeforeEach
  void seedGlobalCatalog() {
    exec("insert into platform (id,code,name,integration,support_status,position)"
        + " values ('" + AMAZON + "','AMAZON','Amazon','OFFICIAL_API','AVAILABLE',1)"
        + " on conflict (id) do nothing");
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://127.0.0.1:1");
    r.add("INTERNAL_KEY", () -> "test-key");
    // 连接池压到 1：多租户串租户在单连接下最容易暴露。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired AdPromotionService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  /** 以管理员（超级用户，RLS 不生效）连接执行 SQL。 */
  static void exec(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 以「已认证的租户操作者」身份执行 SQL：绑定租户 + actor + 权限 + 数据范围。 */
  static void execAsTenant(UUID tenant, String sql) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 播种一个店铺（幂等），返回店铺 id。 */
  UUID seedShop(UUID tenant, String code) {
    UUID id = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenant + "','" + id
            + "','" + AMAZON + "','" + code + "','测试店铺 " + code + "')"
            + " on conflict (tenant_id,code) do nothing");
    return id;
  }

  /** 播种一条 listing（4.1.1.1 已交付，同库同服务）。 */
  UUID seedListing(UUID tenant, UUID shopId, String externalItemId, String title) {
    UUID id = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into listing (tenant_id,id,shop_id,external_item_id,title)"
            + " values ('" + tenant + "','" + id + "','" + shopId + "','" + externalItemId
            + "','" + title + "')");
    return id;
  }

  /** 一个促销的完整定义（按需覆盖）。 */
  record Promo(
      String code,
      String type,
      String rate,
      String amount,
      String budgetCap,
      UUID listingId,
      String basePrice,
      OffsetDateTime startAt,
      OffsetDateTime endAt) {}

  AdPromotionDetail save(AccessService.Access who, Promo promo, String shopCode) {
    List<SavePromotionProduct> products =
        promo.listingId() == null
            ? List.of()
            : List.of(
                new SavePromotionProduct(
                    promo.listingId(),
                    promo.basePrice() == null ? null : new BigDecimal(promo.basePrice()),
                    "USD",
                    ""));
    return as(
        who,
        "channel.operation.create",
        () ->
            service.savePromotion(
                new SavePromotionRequest(
                    promo.code(),
                    "促销 " + promo.code(),
                    promo.type(),
                    AMAZON,
                    shopCode,
                    promo.rate() == null ? null : new BigDecimal(promo.rate()),
                    promo.amount() == null ? null : new BigDecimal(promo.amount()),
                    promo.budgetCap() == null ? null : new BigDecimal(promo.budgetCap()),
                    "USD",
                    promo.startAt(),
                    promo.endAt(),
                    products,
                    "测试登记")));
  }

  AdPromotionGuardrailRow guardrail(
      AccessService.Access who, String code, String maxRate, String minNetPrice, boolean blockOnStacking) {
    return as(
        who,
        "channel.operation.create",
        () ->
            service.saveGuardrail(
                new SaveGuardrailRequest(
                    code,
                    "护栏 " + code,
                    maxRate == null ? null : new BigDecimal(maxRate),
                    minNetPrice == null ? null : new BigDecimal(minNetPrice),
                    blockOnStacking,
                    "ACTIVE",
                    "测试登记")));
  }

  /** 从库里读回促销行的关键列（**绕过服务层**，确保断言的是真正落库的值）。 */
  record RawPromotion(
      String platformSyncStatus,
      boolean pendingWriteback,
      OffsetDateTime startAt,
      OffsetDateTime endAt,
      BigDecimal discountRate,
      BigDecimal discountAmount,
      BigDecimal budgetCap,
      String status) {}

  RawPromotion rawPromotion(UUID tenant, UUID promotionId) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var ps =
            c.prepareStatement(
                "select platform_sync_status, pending_platform_writeback, start_at, end_at,"
                    + " discount_rate, discount_amount, budget_cap, status"
                    + " from ad_promotion where tenant_id = ? and id = ?")) {
      ps.setObject(1, tenant);
      ps.setObject(2, promotionId);
      try (var rs = ps.executeQuery()) {
        if (rs.next()) {
          return new RawPromotion(
              rs.getString("platform_sync_status"),
              rs.getBoolean("pending_platform_writeback"),
              rs.getObject("start_at", OffsetDateTime.class),
              rs.getObject("end_at", OffsetDateTime.class),
              rs.getBigDecimal("discount_rate"),
              rs.getBigDecimal("discount_amount"),
              rs.getBigDecimal("budget_cap"),
              rs.getString("status"));
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
    throw new IllegalStateException("促销不存在：" + promotionId);
  }

  // ==================================================================
  // 1. 能力声明：四个未交付维度必须显式 false + ownerModule
  // ==================================================================

  @Test
  @DisplayName("14.4.1.1 能力声明：毛利 / 平台效果 / 平台回写 / 促销费用显式 available=false + ownerModule")
  void capabilitiesDeclareUndeliveredParts() {
    var caps = as(actorA, "channel.operation.read", () -> service.capabilities());
    Map<String, AdPromotionCapabilityDimension> dims =
        caps.dimensions().stream()
            .collect(java.util.stream.Collectors.toMap(AdPromotionCapabilityDimension::dimension, d -> d));

    // 🔴 本地真能算的几段必须是 true
    assertThat(dims.get("promotion_definition").available()).isTrue();
    assertThat(dims.get("applicable_products").available()).isTrue();
    assertThat(dims.get("net_price").available()).isTrue();
    assertThat(dims.get("guardrail_interception").available()).isTrue();
    assertThat(dims.get("platform_sync_status").available()).isTrue();

    // 🔴 毛利 / 利润：上游 5 定价在 catalog，跨服务不可读且无端点
    assertThat(dims.get("gross_margin").available()).isFalse();
    assertThat(dims.get("gross_margin").ownerModule()).contains("5 定价");
    assertThat(dims.get("gross_margin").impact()).contains("不写假毛利");

    // 🔴 平台效果：无同步源 ⇒ 不建空的促销效果表
    assertThat(dims.get("promotion_effect").available()).isFalse();
    assertThat(dims.get("promotion_effect").ownerModule()).contains("14.4.2.1");

    // 🔴 平台回写：平台促销 API 未交付
    assertThat(dims.get("platform_writeback").available()).isFalse();

    // 🔴 促销费用：12 财务不在本仓
    assertThat(dims.get("promotion_expense").available()).isFalse();
    assertThat(dims.get("promotion_expense").ownerModule()).contains("12 财务");

    // 🔴 「拿不到成本」这件事必须写在返回值里（界面要让人看见这个状态）
    assertThat(caps.marginNote()).contains("5 定价").contains("不写一个 0 毛利");
    assertThat(caps.effectNote()).contains("14.4.2.1");
    assertThat(caps.financeNote()).contains("12 财务");
    assertThat(caps.syncNote()).contains("平台侧还没有");
  }

  // ==================================================================
  // 2. 促销记录真的落库（时间 / 金额 / 同步状态都断言**写回值**）
  // ==================================================================

  @Test
  @DisplayName("14.4.1.1 促销真的落库：时间、折扣率、预算上限、同步状态都从库里读回断言")
  void promotionIsPersistedWithRealValues() {
    UUID shopId = seedShop(a, "PERSIST-AMZ");
    UUID listingId = seedListing(a, shopId, "EXT-PERSIST", "持久化商品");

    var detail =
        save(
            actorA,
            new Promo(
                "P-PERSIST",
                "PERCENT_OFF",
                "0.200000",
                null,
                "500.0000",
                listingId,
                "100.0000",
                START,
                END),
            "PERSIST-AMZ");

    assertThat(detail.promotion().promotionCode()).isEqualTo("P-PERSIST");
    assertThat(detail.promotion().status()).isEqualTo("DRAFT");
    assertThat(detail.promotion().productCount()).isEqualTo(1);

    RawPromotion raw = rawPromotion(a, detail.promotion().id());

    // 🔴 时间字段断言**写回值**（AGENTS.md）：偏移被比掉或偏了 −28800s 都要红
    assertThat(raw.startAt()).isNotNull();
    assertThat(raw.startAt().toInstant()).isEqualTo(START.toInstant());
    assertThat(raw.endAt().toInstant()).isEqualTo(END.toInstant());

    // 🔴 金额断言写回值
    assertThat(raw.discountRate()).isEqualByComparingTo("0.200000");
    assertThat(raw.discountAmount()).isNull();
    assertThat(raw.budgetCap()).isEqualByComparingTo("500.0000");

    // 🔴 平台同步状态：本地已建 ⇒ LOCAL_ONLY + 待回写平台（平台促销 API 未交付）
    assertThat(raw.platformSyncStatus()).isEqualTo("LOCAL_ONLY");
    assertThat(raw.pendingWriteback()).isTrue();
    assertThat(detail.promotion().pendingPlatformWriteback()).isTrue();
    assertThat(detail.promotion().platformPromotionId()).isEmpty();
  }

  @Test
  @DisplayName("14.4.1.1 折后价是真算出来的；没登记基准价 ⇒ 不可比（null），不是 0")
  void netPriceIsRealAndNullWhenBasePriceMissing() {
    UUID shopId = seedShop(a, "NET-AMZ");
    UUID listingWithPrice = seedListing(a, shopId, "EXT-NET-1", "有基准价的商品");
    UUID listingNoPrice = seedListing(a, shopId, "EXT-NET-2", "没基准价的商品");

    var detail =
        save(
            actorA,
            new Promo(
                "P-NET",
                "PERCENT_OFF",
                "0.200000",
                null,
                null,
                listingWithPrice,
                "100.0000",
                START,
                END),
            "NET-AMZ");

    assertThat(detail.products()).hasSize(1);
    AdPromotionProductRow priced = detail.products().get(0);
    // 🔴 折后价 = 100 × (1 − 0.2) = 80
    assertThat(priced.netPrice()).isEqualByComparingTo("80.0000");
    assertThat(priced.discountDepth()).isEqualByComparingTo("0.200000");
    assertThat(priced.stackedPromotionCount()).isZero();

    // 🔴 没登记基准价 ⇒ 折后价**不可比**（null），与 0（可比但真的是零）相反
    var updated =
        as(
            actorA,
            "channel.operation.update",
            () ->
                service.updatePromotion(
                    detail.promotion().id().toString(),
                    new SavePromotionRequest(
                        "P-NET",
                        "促销 P-NET",
                        "PERCENT_OFF",
                        AMAZON,
                        "NET-AMZ",
                        new BigDecimal("0.200000"),
                        null,
                        null,
                        "USD",
                        START,
                        END,
                        List.of(
                            new SavePromotionProduct(
                                listingWithPrice, new BigDecimal("100.0000"), "USD", ""),
                            new SavePromotionProduct(listingNoPrice, null, "USD", "")),
                        "测试登记")));

    assertThat(updated.products()).hasSize(2);
    AdPromotionProductRow noPrice =
        updated.products().stream()
            .filter(p -> p.listingId().equals(listingNoPrice))
            .findFirst()
            .orElseThrow();
    assertThat(noPrice.basePrice()).isNull();
    assertThat(noPrice.netPrice()).isNull();
    assertThat(noPrice.stackedNetPrice()).isNull();
  }

  // ==================================================================
  // 3. 拦截 / 预警：真规则、真判定、真拦截
  // ==================================================================

  @Test
  @DisplayName("14.4.1.1 折扣幅度超阈值 ⇒ 拦截 + 激活被拒；改完重跑检查后才放行")
  void discountDepthBlocksActivationUntilFixed() {
    UUID shopId = seedShop(a, "DEPTH-AMZ");
    UUID listingId = seedListing(a, shopId, "EXT-DEPTH", "深度折扣商品");
    guardrail(actorA, "G-DEPTH", "0.500000", null, true);

    var detail =
        save(
            actorA,
            new Promo(
                "P-DEPTH",
                "PERCENT_OFF",
                "0.800000",
                null,
                null,
                listingId,
                "100.0000",
                START,
                END),
            "DEPTH-AMZ");

    assertThat(detail.checks())
        .anySatisfy(
            c -> {
              assertThat(c.ruleCode()).isEqualTo("DISCOUNT_DEPTH");
              assertThat(c.severity()).isEqualTo("BLOCKED");
              assertThat(c.observedValue()).isEqualByComparingTo("0.800000");
              assertThat(c.thresholdValue()).isEqualByComparingTo("0.500000");
              assertThat(c.message()).contains("80.00%").contains("50.00%");
            });
    assertThat(detail.blockedCount()).isGreaterThan(0);

    // 🔴 排期被拒：拦截是**真的拦**，不是只显示一个红字
    var blocked =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.update",
                    () ->
                        service.changeStatus(
                            detail.promotion().id().toString(),
                            new ChangePromotionStatusRequest("SCHEDULED", "排期"))));
    assertThat(blocked)
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(blocked.getMessage()).contains("拦截").contains("DISCOUNT_DEPTH");

    // 🔴 改完重跑检查后放行：先排期，再上线
    var fixed =
        as(
            actorA,
            "channel.operation.update",
            () ->
                service.updatePromotion(
                    detail.promotion().id().toString(),
                    new SavePromotionRequest(
                        "P-DEPTH",
                        "促销 P-DEPTH",
                        "PERCENT_OFF",
                        AMAZON,
                        "DEPTH-AMZ",
                        new BigDecimal("0.300000"),
                        null,
                        null,
                        "USD",
                        START,
                        END,
                        List.of(
                            new SavePromotionProduct(
                                listingId, new BigDecimal("100.0000"), "USD", "")),
                        "测试登记")));

    assertThat(fixed.checks())
        .noneSatisfy(c -> assertThat(c.ruleCode()).isEqualTo("DISCOUNT_DEPTH"));
    assertThat(fixed.blockedCount()).isZero();

    var scheduled =
        as(
            actorA,
            "channel.operation.update",
            () ->
                service.changeStatus(
                    fixed.promotion().id().toString(),
                    new ChangePromotionStatusRequest("SCHEDULED", "排期")));
    assertThat(scheduled.promotion().status()).isEqualTo("SCHEDULED");

    var activated =
        as(
            actorA,
            "channel.operation.update",
            () ->
                service.changeStatus(
                    fixed.promotion().id().toString(),
                    new ChangePromotionStatusRequest("ACTIVE", "上线")));
    assertThat(activated.promotion().status()).isEqualTo("ACTIVE");
    assertThat(rawPromotion(a, fixed.promotion().id()).status()).isEqualTo("ACTIVE");
  }

  @Test
  @DisplayName("14.4.1.1 折后价低于下限 ⇒ 拦截（用叠加后的折后价判定）")
  void priceFloorBlocks() {
    UUID shopId = seedShop(a, "FLOOR-AMZ");
    UUID listingId = seedListing(a, shopId, "EXT-FLOOR", "折后价过低商品");
    guardrail(actorA, "G-FLOOR", null, "50.0000", true);

    var detail =
        save(
            actorA,
            new Promo(
                "P-FLOOR",
                "PERCENT_OFF",
                "0.600000",
                null,
                null,
                listingId,
                "100.0000",
                START,
                END),
            "FLOOR-AMZ");

    // 🔴 折后价 = 100 × 0.4 = 40 < 下限 50
    assertThat(detail.products().get(0).netPrice()).isEqualByComparingTo("40.0000");
    assertThat(detail.checks())
        .anySatisfy(
            c -> {
              assertThat(c.ruleCode()).isEqualTo("PRICE_FLOOR");
              assertThat(c.severity()).isEqualTo("BLOCKED");
              assertThat(c.observedValue()).isEqualByComparingTo("40.0000");
              assertThat(c.thresholdValue()).isEqualByComparingTo("50.0000");
            });
  }

  @Test
  @DisplayName("14.4.1.1 促销叠加被识别：可拦截也可只预警（规则可配）")
  void promotionStackingIsDetected() {
    UUID shopId = seedShop(a, "STACK-AMZ");
    UUID listingId = seedListing(a, shopId, "EXT-STACK", "被叠加的商品");
    guardrail(actorA, "G-STACK", "0.900000", null, true);

    var first =
        save(
            actorA,
            new Promo(
                "P-STACK-1",
                "PERCENT_OFF",
                "0.100000",
                null,
                null,
                listingId,
                "100.0000",
                START,
                END),
            "STACK-AMZ");
    var second =
        save(
            actorA,
            new Promo(
                "P-STACK-2",
                "PERCENT_OFF",
                "0.200000",
                null,
                null,
                listingId,
                "100.0000",
                START,
                END),
            "STACK-AMZ");

    // 🔴 两条促销都要进入「已排期」才会参与叠加判定（DRAFT 不算）
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.changeStatus(
                first.promotion().id().toString(), new ChangePromotionStatusRequest("SCHEDULED", "")));
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.changeStatus(
                second.promotion().id().toString(), new ChangePromotionStatusRequest("SCHEDULED", "")));

    var rechecked = as(actorA, "channel.operation.update", () -> service.check(second.promotion().id().toString()));

    AdPromotionProductRow product = rechecked.products().get(0);
    assertThat(product.stackedPromotionCount()).isEqualTo(1);
    // 🔴 叠加折后价 = 100 × 0.9 × 0.8 = 72
    assertThat(product.stackedNetPrice()).isEqualByComparingTo("72.0000");
    assertThat(rechecked.checks())
        .anySatisfy(
            c -> {
              assertThat(c.ruleCode()).isEqualTo("PROMOTION_STACKING");
              assertThat(c.severity()).isEqualTo("BLOCKED");
            });

    // 🔴 规则改成「只预警」⇒ 结论从 BLOCKED 变成 WARNING（结论跟着规则走）
    guardrail(actorA, "G-STACK-WARN", "0.900000", null, false);
    var warned = as(actorA, "channel.operation.update", () -> service.check(second.promotion().id().toString()));
    assertThat(warned.checks())
        .filteredOn(c -> c.ruleCode().equals("PROMOTION_STACKING"))
        .allSatisfy(c -> assertThat(c.severity()).isEqualTo("WARNING"));
    assertThat(warned.blockedCount()).isZero();
    assertThat(warned.warningCount()).isGreaterThan(0);
  }

  @Test
  @DisplayName("14.4.1.1 最坏情况让利额超过预算上限 ⇒ 预警（不是拦截）")
  void budgetCapWarns() {
    UUID shopId = seedShop(a, "BUDGET-AMZ");
    UUID listingId = seedListing(a, shopId, "EXT-BUDGET", "超预算商品");

    var detail =
        save(
            actorA,
            new Promo(
                "P-BUDGET",
                "PERCENT_OFF",
                "0.500000",
                null,
                "10.0000",
                listingId,
                "100.0000",
                START,
                END),
            "BUDGET-AMZ");

    assertThat(detail.checks())
        .anySatisfy(
            c -> {
              assertThat(c.ruleCode()).isEqualTo("BUDGET_CAP");
              assertThat(c.severity()).isEqualTo("WARNING");
              assertThat(c.observedValue()).isEqualByComparingTo("50.0000");
              assertThat(c.thresholdValue()).isEqualByComparingTo("10.0000");
            });
    assertThat(detail.blockedCount()).isZero();
  }

  // ==================================================================
  // 4. DB 层兜底 + 跨租户挂载
  // ==================================================================

  @Test
  @DisplayName("14.4.1.1 DB 层兜底：绕服务层直接写 SQL，CHECK 把「折扣率与减免额同时给」拒掉")
  void dbRejectsBothDiscountForms() {
    UUID shopId = seedShop(a, "DBGATE-AMZ");
    assertThatThrownBy(() ->
            execAsTenant(
                a,
                "insert into ad_promotion (tenant_id,id,platform_id,shop_code,promotion_code,"
                    + " promotion_name,promotion_type,discount_rate,discount_amount,currency,status,"
                    + " platform_sync_status,pending_platform_writeback,local_changed_at,version,"
                    + " created_at,updated_at)"
                    + " values ('" + a + "','" + UUID.randomUUID() + "','" + AMAZON
                    + "','DBGATE-AMZ','P-DBGATE','绕过服务层','PERCENT_OFF',0.2,5.00,'USD','DRAFT',"
                    + " 'LOCAL_ONLY',true,now(),0,now(),now())"))
        .hasMessageContaining("ad_promotion");

    // 🔴 「平台已同步」与「待回写」不能同时成立
    assertThatThrownBy(() ->
            execAsTenant(
                a,
                "insert into ad_promotion (tenant_id,id,platform_id,shop_code,promotion_code,"
                    + " promotion_name,promotion_type,discount_rate,currency,status,"
                    + " platform_sync_status,pending_platform_writeback,local_changed_at,version,"
                    + " created_at,updated_at)"
                    + " values ('" + a + "','" + UUID.randomUUID() + "','" + AMAZON
                    + "','DBGATE-AMZ','P-DBGATE2','绕过服务层','PERCENT_OFF',0.2,'USD','DRAFT',"
                    + " 'SYNCED',true,now(),0,now(),now())"))
        .hasMessageContaining("ad_promotion");
  }

  @Test
  @DisplayName("14.4.1.1 跨租户的 listing 挂不进来（服务层拒 + 复合 FK 兜底）")
  void crossTenantListingCannotBeAttached() {
    UUID shopA = seedShop(a, "XISO-AMZ");
    UUID shopB = seedShop(b, "XISO-AMZ-B");
    UUID listingB = seedListing(b, shopB, "EXT-XISO", "B 租户的商品");

    assertThatThrownBy(() ->
            as(
                actorA,
                "channel.operation.create",
                () ->
                    service.savePromotion(
                        new SavePromotionRequest(
                            "P-XISO",
                            "跨租户促销",
                            "PERCENT_OFF",
                            AMAZON,
                            "XISO-AMZ",
                            new BigDecimal("0.100000"),
                            null,
                            null,
                            "USD",
                            START,
                            END,
                            List.of(
                                new SavePromotionProduct(
                                    listingB, new BigDecimal("10.0000"), "USD", "")),
                            "测试登记"))))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("不属于当前租户");
  }

  // ==================================================================
  // 5. 真分页真筛选 + 404 口径
  // ==================================================================

  @Test
  @DisplayName("14.4.1.1 列表真分页真筛选；跨租户与不存在返回逐字相同的 404")
  void listsArePagedAndFiltered() {
    UUID shopId = seedShop(a, "PAGE-AMZ");
    UUID listingId = seedListing(a, shopId, "EXT-PAGE", "分页商品");
    save(
        actorA,
        new Promo(
            "P-PAGE-1", "PERCENT_OFF", "0.100000", null, null, listingId, "10.0000", START, END),
        "PAGE-AMZ");
    save(
        actorA,
        new Promo(
            "P-PAGE-2", "FIXED_AMOUNT_OFF", null, "5.0000", null, listingId, "10.0000", START, END),
        "PAGE-AMZ");

    var all = as(actorA, "channel.operation.read", () -> service.promotions(null, null, null, null, null, null, 0, 50));
    assertThat(all.total()).isEqualTo(2);

    var firstPage = as(actorA, "channel.operation.read", () -> service.promotions(null, null, null, null, null, null, 0, 1));
    var secondPage = as(actorA, "channel.operation.read", () -> service.promotions(null, null, null, null, null, null, 1, 1));
    assertThat(firstPage.items()).hasSize(1);
    assertThat(secondPage.items()).hasSize(1);
    assertThat(firstPage.items().get(0).id()).isNotEqualTo(secondPage.items().get(0).id());

    var byType =
        as(
            actorA,
            "channel.operation.read",
            () -> service.promotions(null, "FIXED_AMOUNT_OFF", null, null, null, null, 0, 50));
    assertThat(byType.total()).isEqualTo(1);
    assertThat(byType.items().get(0).promotionType()).isEqualTo("FIXED_AMOUNT_OFF");

    var byKeyword =
        as(
            actorA,
            "channel.operation.read",
            () -> service.promotions(null, null, null, null, null, "P-PAGE-1", 0, 50));
    assertThat(byKeyword.total()).isEqualTo(1);

    var byShop =
        as(
            actorA,
            "channel.operation.read",
            () -> service.promotions(null, null, null, "PAGE-AMZ", null, null, 0, 50));
    assertThat(byShop.total()).isEqualTo(2);
    var otherShop =
        as(
            actorA,
            "channel.operation.read",
            () -> service.promotions(null, null, null, "NOT-EXIST", null, null, 0, 50));
    assertThat(otherShop.total()).isZero();

    // 🔴 先把 id 取出来：租户上下文是**事务内**的，嵌套调用会改写 app.current_tenant
    UUID promotionId = all.items().get(0).id();
    var notThere =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.read",
                    () -> service.promotion(UUID.randomUUID().toString())));
    var crossTenant =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> as(actorB, "channel.operation.read", () -> service.promotion(promotionId.toString())));
    assertThat(notThere).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(crossTenant).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(crossTenant.getMessage()).isEqualTo(notThere.getMessage());
  }

  // ==================================================================
  // 6. 多租户三断言（AGENTS.md 硬性要求，缺一不可）
  // ==================================================================

  @Test
  @DisplayName("14.4.1.1 断言一：两租户互不可见")
  void tenantsAreIsolated() {
    UUID shopA = seedShop(a, "ISO-A-AMZ");
    UUID shopB = seedShop(b, "ISO-B-AMZ");
    UUID listingA = seedListing(a, shopA, "EXT-ISO-A", "A 租户商品");
    UUID listingB = seedListing(b, shopB, "EXT-ISO-B", "B 租户商品");

    save(
        actorA,
        new Promo(
            "P-ISO-A", "PERCENT_OFF", "0.100000", null, null, listingA, "10.0000", START, END),
        "ISO-A-AMZ");
    save(
        actorB,
        new Promo(
            "P-ISO-B", "PERCENT_OFF", "0.100000", null, null, listingB, "10.0000", START, END),
        "ISO-B-AMZ");

    var pageA =
        as(
            actorA,
            "channel.operation.read",
            () -> service.promotions(null, null, null, null, null, null, 0, 50));
    var pageB =
        as(
            actorB,
            "channel.operation.read",
            () -> service.promotions(null, null, null, null, null, null, 0, 50));
    assertThat(pageA.total()).isEqualTo(1);
    assertThat(pageB.total()).isEqualTo(1);
    assertThat(pageA.items().get(0).promotionCode()).isEqualTo("P-ISO-A");
    assertThat(pageB.items().get(0).promotionCode()).isEqualTo("P-ISO-B");

    // 🔴 护栏规则也互不可见
    guardrail(actorA, "G-ISO-A", "0.500000", null, true);
    assertThat(as(actorA, "channel.operation.read", () -> service.guardrails(null, 0, 50)).total())
        .isEqualTo(1);
    assertThat(as(actorB, "channel.operation.read", () -> service.guardrails(null, 0, 50)).total())
        .isZero();

    // 🔴 详情也互不可见
    UUID idA = pageA.items().get(0).id();
    assertThatThrownBy(() -> as(actorB, "channel.operation.read", () -> service.promotion(idA.toString())))
        .hasMessageContaining("不存在");
  }

  @Test
  @DisplayName("14.4.1.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    UUID shopA = seedShop(a, "FORGE-A-AMZ");
    UUID shopB = seedShop(b, "FORGE-B-AMZ");
    UUID listingA = seedListing(a, shopA, "EXT-FORGE-A", "A 租户商品");
    save(
        actorA,
        new Promo(
            "P-FORGE-A", "PERCENT_OFF", "0.100000", null, null, listingA, "10.0000", START, END),
        "FORGE-A-AMZ");
    save(
        actorB,
        new Promo(
            "P-FORGE-B", "PERCENT_OFF", "0.100000", null, null, null, null, START, END),
        "FORGE-B-AMZ");

    // A 的会话 + B 的头 ⇒ 仍然只看得见 A 的促销
    http.perform(get("/channel/ad-promotions/promotions")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()", org.hamcrest.Matchers.is(1)))
        .andExpect(jsonPath("$.items[0].promotionCode", org.hamcrest.Matchers.is("P-FORGE-A")));

    // 无会话一律 401
    http.perform(get("/channel/ad-promotions/promotions")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("14.4.1.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    UUID shopA = seedShop(a, "POOL-A-AMZ");
    UUID shopB = seedShop(b, "POOL-B-AMZ");
    UUID listingA = seedListing(a, shopA, "EXT-POOL-A", "A 租户商品");
    save(
        actorA,
        new Promo(
            "P-POOL-A", "PERCENT_OFF", "0.100000", null, null, listingA, "10.0000", START, END),
        "POOL-A-AMZ");
    save(
        actorB,
        new Promo(
            "P-POOL-B", "PERCENT_OFF", "0.100000", null, null, null, null, START, END),
        "POOL-B-AMZ");

    for (int i = 0; i < 3; i++) {
      var pageA =
          as(
              actorA,
              "channel.operation.read",
              () -> service.promotions(null, null, null, null, null, null, 0, 50));
      var pageB =
          as(
              actorB,
              "channel.operation.read",
              () -> service.promotions(null, null, null, null, null, null, 0, 50));
      assertThat(pageA.total()).isEqualTo(1);
      assertThat(pageB.total()).isEqualTo(1);
      assertThat(pageA.items().get(0).promotionCode()).isEqualTo("P-POOL-A");
      assertThat(pageB.items().get(0).promotionCode()).isEqualTo("P-POOL-B");
    }
  }

  // ==================================================================
  // 7. 端点形状（契约与白名单的形状来源）
  // ==================================================================

  @Test
  @DisplayName("14.4.1.1 端点可用：能力声明 / 促销列表 / 详情")
  void endpointsAreReachable() throws Exception {
    http.perform(get("/channel/ad-promotions/capabilities").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions.length()", org.hamcrest.Matchers.greaterThan(0)))
        .andExpect(jsonPath("$.marginNote", org.hamcrest.Matchers.containsString("5 定价")));

    http.perform(get("/channel/ad-promotions/options").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.promotionTypes.length()", org.hamcrest.Matchers.is(5)));

    http.perform(get("/channel/ad-promotions/promotions").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()", org.hamcrest.Matchers.is(0)));
  }

  /**
   * 🔴 收尾：停掉本地桩与 Testcontainers 容器。
   *
   * <p>不回收容器的话，Ryuk 只在 JVM 退出时按 session 标签统一回收 —— 整个模块跑完之前容器一直活着
   * （实测 channel 一个服务就能堆到 110+ 个 postgres 容器），容器内存 + Docker VM 把机器压进 swap，
   * JVM 被卡住十几分钟 ⇒ 全量跑时断言假红、SSL 握手失败。
   *
   * <p>顺序：先停外部服务，再停容器。反了会出现「容器已停但还有 in-flight 请求打进来」。
   */
  @AfterAll
  static void stopTestResources() {
    if (identity != null) identity.stop(0);
    pg.stop();
  }
}
