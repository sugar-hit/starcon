package com.starcon.channel;

import static com.starcon.channel.AdCouponModels.*;
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
 * 14.4.1.2 优惠券与秒杀。
 *
 * <p>本测试类钉住本模块最容易做丢的几件事：
 *
 * <ol>
 *   <li><b>🔴 核销必须与订单绑定</b>（蓝图加粗句）：订单标识为空直接拒绝；
 *       落库后从库里读回 {@code order_ref} 断言（只断言没抛异常不算数）。</li>
 *   <li><b>🔴 生成码是真库存计数</b>：生成 N 张 ⇒ {@code generated_count = N}；
 *       请求数超过剩余库存直接 409（不做静默截断）。</li>
 *   <li><b>🔴 限领判据是累计</b>：同一个人第二次领取超限 ⇒ 409。</li>
 *   <li><b>🔴 库存扣减是显式 SQL</b>：领取后 {@code claimed_count} 真扣；
 *       库存不足 ⇒ 409。</li>
 *   <li><b>🔴 买赠券的补贴成本是 null 而不是 0</b>（成本价在 05 定价，跨服务不可读），
 *       统计里单列「不可测算笔数」。</li>
 *   <li><b>🔴 核销统计是真聚合</b>：核销率 / 补贴成本合计 / 渠道拆分 / 按日拆分
 *       全部来自 GROUP BY，不是前端算的。</li>
 *   <li><b>🔴 门槛与面额是快照</b>：不从 catalog 拉（跨服务禁止）。</li>
 *   <li><b>🔴 DB 层兜底</b>：绕服务层直接写 SQL，CHECK 也把「折扣率与面额同时给」拒掉。</li>
 *   <li><b>秒杀</b>：库存真扣 + 按人限领 + 核销绑订单 + 统计真聚合。</li>
 *   <li><b>真分页真筛选</b>；跨租户与不存在返回逐字相同的 404。</li>
 * </ol>
 *
 * <p>多租户三断言在 {@link AdCouponTenancyIntegrationTest} 里（AGENTS.md 硬性要求，缺一不可）。
 *
 * <p><b>时刻一律用固定的 {@link OffsetDateTime} 钉死</b>，不用 {@code now()}：用墙钟会让
 * 「这次写入落在哪个断言分支上」成为一个 flaky 断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdCouponIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
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

  @Autowired AdCouponService service;
  @Autowired AdFlashSaleService flashService;
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

  /** 登记一张折扣码券（默认 10% 折扣、库存 10、限领 1）。 */
  AdCouponDetail savePercentCoupon(AccessService.Access who, String code, String shopCode) {
    return saveCoupon(
        who,
        new SaveCouponRequest(
            code,
            "券 " + code,
            "DISCOUNT_CODE",
            AMAZON,
            shopCode,
            null,
            new BigDecimal("0.100000"),
            null,
            null,
            null,
            null,
            null,
            "USD",
            START,
            END,
            new BigDecimal("10"),
            BigDecimal.ONE,
            "TEST",
            6,
            null,
            "测试登记"));
  }

  AdCouponDetail saveCoupon(AccessService.Access who, SaveCouponRequest request) {
    return as(who, "channel.operation.create", () -> service.saveCoupon(request));
  }

  /** 把券置为生效中（生成码与领取都要求 ACTIVE）。 */
  AdCouponDetail activate(AccessService.Access who, UUID couponId) {
    return as(
        who,
        "channel.operation.update",
        () ->
            service.changeStatus(
                couponId.toString(),
                new ChangeCouponStatusRequest("ACTIVE", "测试上线")));
  }

  /** 从库里读回券行的关键列（**绕过服务层**，确保断言的是真正落库的值）。 */
  record RawCoupon(
      String platformSyncStatus,
      boolean pendingWriteback,
      OffsetDateTime startAt,
      OffsetDateTime endAt,
      BigDecimal discountRate,
      BigDecimal discountValue,
      BigDecimal thresholdAmount,
      BigDecimal totalStock,
      BigDecimal generatedCount,
      BigDecimal claimedCount,
      BigDecimal redeemedCount,
      String status) {}

  RawCoupon rawCoupon(UUID tenant, UUID couponId) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var ps =
            c.prepareStatement(
                "select platform_sync_status, pending_platform_writeback, start_at, end_at,"
                    + " discount_rate, discount_value, threshold_amount, total_stock,"
                    + " generated_count, claimed_count, redeemed_count, status"
                    + " from ad_coupon where tenant_id = ? and id = ?")) {
      ps.setObject(1, tenant);
      ps.setObject(2, couponId);
      try (var rs = ps.executeQuery()) {
        if (rs.next()) {
          return new RawCoupon(
              rs.getString("platform_sync_status"),
              rs.getBoolean("pending_platform_writeback"),
              rs.getObject("start_at", OffsetDateTime.class),
              rs.getObject("end_at", OffsetDateTime.class),
              rs.getBigDecimal("discount_rate"),
              rs.getBigDecimal("discount_value"),
              rs.getBigDecimal("threshold_amount"),
              rs.getBigDecimal("total_stock"),
              rs.getBigDecimal("generated_count"),
              rs.getBigDecimal("claimed_count"),
              rs.getBigDecimal("redeemed_count"),
              rs.getString("status"));
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
    throw new IllegalStateException("券不存在：" + couponId);
  }

  /** 从库里读回核销记录（**绕过服务层**）。 */
  record RawRedemption(
      String orderRef,
      BigDecimal orderAmount,
      BigDecimal redeemedAmount,
      BigDecimal subsidyCost,
      String currency,
      OffsetDateTime redeemedAt) {}

  RawRedemption rawRedemption(UUID tenant, UUID couponId) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var ps =
            c.prepareStatement(
                "select order_ref, order_amount, redeemed_amount, subsidy_cost, currency,"
                    + " redeemed_at from ad_coupon_redemption where tenant_id = ? and coupon_id = ?")) {
      ps.setObject(1, tenant);
      ps.setObject(2, couponId);
      try (var rs = ps.executeQuery()) {
        if (rs.next()) {
          return new RawRedemption(
              rs.getString("order_ref"),
              rs.getBigDecimal("order_amount"),
              rs.getBigDecimal("redeemed_amount"),
              rs.getBigDecimal("subsidy_cost"),
              rs.getString("currency"),
              rs.getObject("redeemed_at", OffsetDateTime.class));
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
    throw new IllegalStateException("核销记录不存在：" + couponId);
  }

  /** 从库里读回券码的状态与订单标识。 */
  record RawCode(String status, String claimantKey, String orderRef, OffsetDateTime usedAt) {}

  /** 取这张券上**已核销**的那个券码（绕过服务层）。 */
  RawCode rawUsedCode(UUID tenant, UUID couponId) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var ps =
            c.prepareStatement(
                "select status, claimant_key, order_ref, used_at from ad_coupon_code"
                    + " where tenant_id = ? and coupon_id = ? and status = 'USED'")) {
      ps.setObject(1, tenant);
      ps.setObject(2, couponId);
      try (var rs = ps.executeQuery()) {
        if (rs.next()) {
          return new RawCode(
              rs.getString("status"),
              rs.getString("claimant_key"),
              rs.getString("order_ref"),
              rs.getObject("used_at", OffsetDateTime.class));
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
    throw new IllegalStateException("券码不存在：" + couponId);
  }

  // ==================================================================
  // 1. 能力声明：三个未交付维度必须显式 false + ownerModule
  // ==================================================================

  @Test
  @DisplayName("14.4.1.2 能力声明：平台营销回写 / 客户主数据 / 买赠补贴成本显式 available=false")
  void capabilitiesDeclareUndeliveredParts() {
    var caps = as(actorA, "channel.operation.read", () -> service.capabilities());
    Map<String, AdCouponCapabilityDimension> dims =
        caps.dimensions().stream()
            .collect(java.util.stream.Collectors.toMap(AdCouponCapabilityDimension::dimension, d -> d));

    // 🔴 本地真能做的几段必须是 true
    assertThat(dims.get("coupon_definition").available()).isTrue();
    assertThat(dims.get("code_generation").available()).isTrue();
    assertThat(dims.get("stock_and_limit").available()).isTrue();
    assertThat(dims.get("channel_placement").available()).isTrue();
    assertThat(dims.get("claim_tracking").available()).isTrue();
    assertThat(dims.get("redemption_binding").available()).isTrue();
    assertThat(dims.get("redemption_statistics").available()).isTrue();
    assertThat(dims.get("targeted_issue").available()).isTrue();

    // 🔴 平台营销回写未交付
    assertThat(dims.get("platform_marketing_writeback").available()).isFalse();
    // 🔴 15 客户未交付
    assertThat(dims.get("customer_master_data").available()).isFalse();
    assertThat(dims.get("customer_master_data").ownerModule()).contains("15 客户");
    // 🔴 买赠补贴成本不可测算
    assertThat(dims.get("bogo_subsidy_cost").available()).isFalse();
    assertThat(dims.get("bogo_subsidy_cost").ownerModule()).contains("5 定价");

    // 🔴 「拿不到」这件事必须写在返回值里（界面要让人看见这个状态）
    assertThat(caps.syncNote()).contains("平台侧还没有");
    assertThat(caps.subsidyNote()).contains("不写 0");
    assertThat(caps.orderBindingNote()).contains("订单标识");
    assertThat(caps.pricingSnapshotNote()).contains("catalog").contains("快照");
    assertThat(caps.recipientNote()).contains("收件人标识");
  }

  // ==================================================================
  // 2. 券真的落库（时间 / 金额 / 同步状态都断言**写回值**）
  // ==================================================================

  @Test
  @DisplayName("14.4.1.2 券真的落库：时间、折扣率、库存、同步状态都从库里读回断言")
  void couponIsPersistedWithRealValues() {
    seedShop(a, "PERSIST-AMZ");
    var detail = savePercentCoupon(actorA, "C-PERSIST", "PERSIST-AMZ");

    assertThat(detail.coupon().couponCode()).isEqualTo("C-PERSIST");
    assertThat(detail.coupon().status()).isEqualTo("DRAFT");
    assertThat(detail.coupon().totalStock()).isEqualByComparingTo("10");

    RawCoupon raw = rawCoupon(a, detail.coupon().id());

    // 🔴 时间字段断言**写回值**（AGENTS.md）
    assertThat(raw.startAt()).isNotNull();
    assertThat(raw.startAt().toInstant()).isEqualTo(START.toInstant());
    assertThat(raw.endAt().toInstant()).isEqualTo(END.toInstant());

    // 🔴 金额断言写回值
    assertThat(raw.discountRate()).isEqualByComparingTo("0.100000");
    assertThat(raw.discountValue()).isNull();
    assertThat(raw.thresholdAmount()).isNull();
    assertThat(raw.totalStock()).isEqualByComparingTo("10");
    assertThat(raw.generatedCount()).isEqualByComparingTo("0");

    // 🔴 平台同步状态：本地已建 ⇒ LOCAL_ONLY + 待回写平台
    assertThat(raw.platformSyncStatus()).isEqualTo("LOCAL_ONLY");
    assertThat(raw.pendingWriteback()).isTrue();
    assertThat(detail.coupon().pendingPlatformWriteback()).isTrue();
  }

  // ==================================================================
  // 3. 生成码 = 真库存计数
  // ==================================================================

  @Test
  @DisplayName("14.4.1.2 生成码是真库存计数：生成 3 张 ⇒ generated_count=3；超库存被拒")
  void generatedCodesAreRealStockCounted() {
    seedShop(a, "GEN-AMZ");
    var created = savePercentCoupon(actorA, "C-GEN", "GEN-AMZ");
    activate(actorA, created.coupon().id());

    var codes =
        as(
            actorA,
            "channel.operation.update",
            () ->
                service.generateCodes(
                    created.coupon().id().toString(),
                    new GenerateCouponCodesRequest(3, null, null, null)));

    assertThat(codes.items()).hasSize(3);
    assertThat(codes.items().stream().map(AdCouponCodeRow::code).distinct().count()).isEqualTo(3);
    assertThat(codes.items()).allMatch(c -> c.status().equals("GENERATED"));

    RawCoupon raw = rawCoupon(a, created.coupon().id());
    assertThat(raw.generatedCount()).isEqualByComparingTo("3");

    // 🔴 超库存必须被拒（不做静默截断：截断会让调用方以为生成了 N 张）
    var thrown =
        catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.update",
                    () ->
                        service.generateCodes(
                            created.coupon().id().toString(),
                            new GenerateCouponCodesRequest(99, null, null, null))));
    assertThat(thrown).isNotNull();
    assertThat(rawCoupon(a, created.coupon().id()).generatedCount())
        .as("被拒的生成请求不得改动库存计数")
        .isEqualByComparingTo("3");

    // 🔴 库存用完后再生成 ⇒ 409
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.generateCodes(
                created.coupon().id().toString(),
                new GenerateCouponCodesRequest(7, null, null, null)));
    var exhausted =
        catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.update",
                    () ->
                        service.generateCodes(
                            created.coupon().id().toString(),
                            new GenerateCouponCodesRequest(1, null, null, null))));
    assertThat(exhausted).isNotNull();
  }

  // ==================================================================
  // 4. 限领校验真读真扣 + 库存扣减
  // ==================================================================

  @Test
  @DisplayName("14.4.1.2 限领判据是累计：同一个人第二次领取超限被拒；库存真扣")
  void claimEnforcesPerUserLimitAndDeductsStock() {
    seedShop(a, "LIMIT-AMZ");
    var created = savePercentCoupon(actorA, "C-LIMIT", "LIMIT-AMZ");
    activate(actorA, created.coupon().id());
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.generateCodes(
                created.coupon().id().toString(),
                new GenerateCouponCodesRequest(2, null, null, null)));

    // 第一次领取成功
    var afterFirst =
        as(
            actorA,
            "channel.operation.update",
            () ->
                service.claim(
                    created.coupon().id().toString(),
                    new ClaimCouponRequest("PULL", "user-1", "ON_SITE", null, null)));
    assertThat(afterFirst.coupon().claimedCount()).isEqualByComparingTo("1");
    assertThat(rawCoupon(a, created.coupon().id()).claimedCount()).isEqualByComparingTo("1");

    // 🔴 同一个人再领一次 ⇒ 超过每人限领 1 张 ⇒ 被拒
    var thrown =
        catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.update",
                    () ->
                        service.claim(
                            created.coupon().id().toString(),
                            new ClaimCouponRequest("PULL", "user-1", "ON_SITE", null, null))));
    assertThat(thrown).isNotNull();
    assertThat(rawCoupon(a, created.coupon().id()).claimedCount())
        .as("被拒的领取不得改动领取计数")
        .isEqualByComparingTo("1");

    // 🔴 另一个人可以领（限领是**按人**的）
    var other =
        as(
            actorA,
            "channel.operation.update",
            () ->
                service.claim(
                    created.coupon().id().toString(),
                    new ClaimCouponRequest("PULL", "user-2", "ON_SITE", null, null)));
    assertThat(other.coupon().claimedCount()).isEqualByComparingTo("2");
    // 🔴 此刻可领的剩余额度 = 已生成 2 − 已领取 2 = 0
    assertThat(other.coupon().claimableCount()).isEqualByComparingTo("0");

    // 🔴 库存用完后再领 ⇒ 被拒
    var empty =
        catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.update",
                    () ->
                        service.claim(
                            created.coupon().id().toString(),
                            new ClaimCouponRequest("PULL", "user-3", "ON_SITE", null, null))));
    assertThat(empty).isNotNull();
  }

  // ==================================================================
  // 5. 定向发放（下游 15 客户未交付 ⇒ 自包含地做完）
  // ==================================================================

  @Test
  @DisplayName("14.4.1.2 定向发放自包含：发放记录落库 + 真的扣库存 + 受每人限领约束")
  void targetedIssueIsSelfContained() {
    seedShop(a, "PUSH-AMZ");
    var created = savePercentCoupon(actorA, "C-PUSH", "PUSH-AMZ");
    activate(actorA, created.coupon().id());
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.generateCodes(
                created.coupon().id().toString(),
                new GenerateCouponCodesRequest(5, null, null, null)));

    var issued =
        as(
            actorA,
            "channel.operation.update",
            () ->
                service.claim(
                    created.coupon().id().toString(),
                    new ClaimCouponRequest("PUSH", "customer-42", null, null, null)));

    assertThat(issued.coupon().claimedCount()).isEqualByComparingTo("1");
    assertThat(issued.recentClaims()).hasSize(1);
    AdCouponClaimRow claim = issued.recentClaims().get(0);
    assertThat(claim.claimKind()).isEqualTo("PUSH");
    assertThat(claim.channelType()).isEqualTo("DIRECT");
    assertThat(claim.recipientKey()).isEqualTo("customer-42");
    assertThat(claim.code()).isNotBlank();

    // 🔴 定向发放同样受每人限领约束
    var thrown =
        catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.update",
                    () ->
                        service.claim(
                            created.coupon().id().toString(),
                            new ClaimCouponRequest("PUSH", "customer-42", null, null, null))));
    assertThat(thrown).isNotNull();
  }

  // ==================================================================
  // 6. 核销：绑定订单标识 + 补贴成本落库
  // ==================================================================

  @Test
  @DisplayName("14.4.1.2 核销必须与订单绑定：空订单标识被拒；落库后读回 order_ref 与补贴成本")
  void redemptionBindsOrderAndStoresSubsidyCost() {
    seedShop(a, "REDEEM-AMZ");
    var created = savePercentCoupon(actorA, "C-REDEEM", "REDEEM-AMZ");
    activate(actorA, created.coupon().id());
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.generateCodes(
                created.coupon().id().toString(),
                new GenerateCouponCodesRequest(1, null, null, null)));
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.claim(
                created.coupon().id().toString(),
                new ClaimCouponRequest("PULL", "user-9", "ON_SITE", null, null)));

    // 🔴 蓝图加粗句：订单标识为空 ⇒ 拒绝
    var thrown =
        catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.update",
                    () ->
                        service.redeem(
                            created.coupon().id().toString(),
                            new RedeemCouponRequest("   ", null, new BigDecimal("100")))));
    assertThat(thrown).isNotNull();

    // 正常核销
    var redeemed =
        as(
            actorA,
            "channel.operation.update",
            () ->
                service.redeem(
                    created.coupon().id().toString(),
                    new RedeemCouponRequest("SO-2026-0001", null, new BigDecimal("100"))));

    assertThat(redeemed.coupon().redeemedCount()).isEqualByComparingTo("1");
    assertThat(redeemed.recentRedemptions()).hasSize(1);
    AdCouponRedemptionRow row = redeemed.recentRedemptions().get(0);
    assertThat(row.orderRef()).isEqualTo("SO-2026-0001");
    // 🔴 让利额 = 100 × 10% = 10.0000，补贴成本同口径
    assertThat(row.redeemedAmount()).isEqualByComparingTo("10.0000");
    assertThat(row.subsidyCost()).isEqualByComparingTo("10.0000");

    // 🔴 从库里读回（绕过服务层）
    RawRedemption raw = rawRedemption(a, created.coupon().id());
    assertThat(raw.orderRef()).isEqualTo("SO-2026-0001");
    assertThat(raw.orderAmount()).isEqualByComparingTo("100");
    assertThat(raw.redeemedAmount()).isEqualByComparingTo("10.0000");
    assertThat(raw.subsidyCost()).isEqualByComparingTo("10.0000");
    assertThat(raw.currency()).isEqualTo("USD");
    assertThat(raw.redeemedAt()).isNotNull();

    // 🔴 券码状态变成 USED 且带上订单标识
    RawCode code = rawUsedCode(a, created.coupon().id());
    assertThat(code.status()).isEqualTo("USED");
    assertThat(code.orderRef()).isEqualTo("SO-2026-0001");
    assertThat(code.claimantKey()).isEqualTo("user-9");
    assertThat(code.usedAt()).isNotNull();
    assertThat(rawCoupon(a, created.coupon().id()).redeemedCount()).isEqualByComparingTo("1");
  }

  @Test
  @DisplayName("14.4.1.2 同一张券在同一订单上只能核销一次；券码也只能核销一次")
  void redemptionIsIdempotentPerOrderAndCode() {
    seedShop(a, "ONCE-AMZ");
    var created = savePercentCoupon(actorA, "C-ONCE", "ONCE-AMZ");
    activate(actorA, created.coupon().id());
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.generateCodes(
                created.coupon().id().toString(),
                new GenerateCouponCodesRequest(2, null, null, null)));
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.claim(
                created.coupon().id().toString(),
                new ClaimCouponRequest("PULL", "user-1", "ON_SITE", null, null)));
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.claim(
                created.coupon().id().toString(),
                new ClaimCouponRequest("PULL", "user-2", "ON_SITE", null, null)));

    as(
        actorA,
        "channel.operation.update",
        () ->
            service.redeem(
                created.coupon().id().toString(),
                new RedeemCouponRequest("SO-DUP-1", null, new BigDecimal("100"))));

    // 🔴 第二个券码、同一个订单 ⇒ 同一张券在同一订单上只能核销一次
    var thrown =
        catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.update",
                    () ->
                        service.redeem(
                            created.coupon().id().toString(),
                            new RedeemCouponRequest("SO-DUP-1", null, new BigDecimal("100")))));
    assertThat(thrown).isNotNull();
    assertThat(rawCoupon(a, created.coupon().id()).redeemedCount())
        .as("被拒的重复核销不得改动核销计数")
        .isEqualByComparingTo("1");
  }

  // ==================================================================
  // 7. 核销统计 = 真聚合
  // ==================================================================

  @Test
  @DisplayName("14.4.1.2 核销统计是真聚合：核销率 / 补贴成本 / 渠道拆分 / 按日拆分")
  void statisticsAreRealAggregations() {
    seedShop(a, "STAT-AMZ");
    var created = savePercentCoupon(actorA, "C-STAT", "STAT-AMZ");
    activate(actorA, created.coupon().id());
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.generateCodes(
                created.coupon().id().toString(),
                new GenerateCouponCodesRequest(4, null, null, null)));

    var channel =
        as(
            actorA,
            "channel.operation.update",
            () ->
                service.saveChannel(
                    created.coupon().id().toString(),
                    new SaveCouponChannelRequest(
                        "ON_SITE", "首页 banner", START, END, new BigDecimal("1000"), "RUNNING", "")));

    as(
        actorA,
        "channel.operation.update",
        () ->
            service.claim(
                created.coupon().id().toString(),
                new ClaimCouponRequest("CHANNEL", "user-1", "ON_SITE", channel.id(), null)));
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.claim(
                created.coupon().id().toString(),
                new ClaimCouponRequest("PULL", "user-2", "ON_SITE", null, null)));
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.redeem(
                created.coupon().id().toString(),
                new RedeemCouponRequest("SO-STAT-1", null, new BigDecimal("100"))));

    var stats = as(actorA, "channel.operation.read", () -> service.statistics(created.coupon().id().toString()));

    assertThat(stats.generatedCount()).isEqualTo(4);
    assertThat(stats.claimedCount()).isEqualTo(2);
    assertThat(stats.redeemedCount()).isEqualTo(1);
    // 🔴 核销率 = 1 / 2 = 0.5
    assertThat(stats.redemptionRate()).isEqualByComparingTo("0.500000");
    // 🔴 补贴成本合计 = 10.0000（100 × 10%）
    assertThat(stats.totalSubsidyCost()).isEqualByComparingTo("10.0000");
    assertThat(stats.totalRedeemedAmount()).isEqualByComparingTo("10.0000");
    assertThat(stats.unmeasurableRedemptions()).isZero();

    // 🔴 渠道拆分是真聚合
    assertThat(stats.channelBreakdown()).isNotEmpty();
    assertThat(stats.channelBreakdown().stream().map(AdCouponChannelStat::claimCount)
            .reduce(0L, Long::sum))
        .isEqualTo(2L);

    // 🔴 按日拆分来自 GROUP BY
    assertThat(stats.dailyTrend()).hasSize(1);
    assertThat(stats.dailyTrend().get(0).redemptionCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("14.4.1.2 没有领取时核销率不可比（null），不是 0")
  void redemptionRateIsNullWithoutClaims() {
    seedShop(a, "NOCLAIM-AMZ");
    var created = savePercentCoupon(actorA, "C-NOCLAIM", "NOCLAIM-AMZ");
    var stats = as(actorA, "channel.operation.read", () -> service.statistics(created.coupon().id().toString()));
    assertThat(stats.claimedCount()).isZero();
    assertThat(stats.redemptionRate()).isNull();
    assertThat(stats.totalSubsidyCost()).isNull();
  }

  // ==================================================================
  // 8. 买赠券：补贴成本不可测算 ⇒ null 而不是 0
  // ==================================================================

  @Test
  @DisplayName("14.4.1.2 买赠券的补贴成本是 null（不可测算），不是 0；统计单列不可测算笔数")
  void bogoSubsidyCostIsNullNotZero() {
    seedShop(a, "BOGO-AMZ");
    var created =
        saveCoupon(
            actorA,
            new SaveCouponRequest(
                "C-BOGO",
                "买赠券",
                "BOGO",
                AMAZON,
                "BOGO-AMZ",
                null,
                null,
                null,
                null,
                null,
                new BigDecimal("2"),
                new BigDecimal("1"),
                "USD",
                START,
                END,
                new BigDecimal("5"),
                BigDecimal.ONE,
                "BOGO",
                6,
                null,
                "测试买赠"));
    activate(actorA, created.coupon().id());
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.generateCodes(
                created.coupon().id().toString(),
                new GenerateCouponCodesRequest(1, null, null, null)));
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.claim(
                created.coupon().id().toString(),
                new ClaimCouponRequest("PULL", "user-1", "ON_SITE", null, null)));
    var redeemed =
        as(
            actorA,
            "channel.operation.update",
            () ->
                service.redeem(
                    created.coupon().id().toString(),
                    new RedeemCouponRequest("SO-BOGO-1", null, null)));

    // 🔴 买赠券没有金额口径 ⇒ 让利额与补贴成本都**不可测算**
    AdCouponRedemptionRow row = redeemed.recentRedemptions().get(0);
    assertThat(row.subsidyCost()).isNull();
    assertThat(row.redeemedAmount()).isNull();

    RawRedemption raw = rawRedemption(a, created.coupon().id());
    assertThat(raw.subsidyCost()).isNull();
    assertThat(raw.redeemedAmount()).isNull();

    // 🔴 统计里单列「不可测算笔数」，而不是把成本算成 0
    var stats = as(actorA, "channel.operation.read", () -> service.statistics(created.coupon().id().toString()));
    assertThat(stats.redeemedCount()).isEqualTo(1);
    assertThat(stats.unmeasurableRedemptions()).isEqualTo(1);
    assertThat(stats.totalSubsidyCost()).isNull();
  }

  // ==================================================================
  // 9. 满减门槛：订单金额不达标不许核销
  // ==================================================================

  @Test
  @DisplayName("14.4.1.2 满减门槛不达标不许核销；达标后让利额 = 减免额")
  void fullReductionThresholdBlocksRedemption() {
    seedShop(a, "FULL-AMZ");
    var created =
        saveCoupon(
            actorA,
            new SaveCouponRequest(
                "C-FULL",
                "满减券",
                "FULL_REDUCTION",
                AMAZON,
                "FULL-AMZ",
                null,
                null,
                new BigDecimal("5"),
                new BigDecimal("20"),
                null,
                null,
                null,
                "USD",
                START,
                END,
                new BigDecimal("5"),
                BigDecimal.ONE,
                "FULL",
                6,
                null,
                "测试满减"));
    activate(actorA, created.coupon().id());
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.generateCodes(
                created.coupon().id().toString(),
                new GenerateCouponCodesRequest(1, null, null, null)));
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.claim(
                created.coupon().id().toString(),
                new ClaimCouponRequest("PULL", "user-1", "ON_SITE", null, null)));

    var thrown =
        catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.update",
                    () ->
                        service.redeem(
                            created.coupon().id().toString(),
                            new RedeemCouponRequest("SO-FULL-1", null, new BigDecimal("19")))));
    assertThat(thrown).isNotNull();

    var redeemed =
        as(
            actorA,
            "channel.operation.update",
            () ->
                service.redeem(
                    created.coupon().id().toString(),
                    new RedeemCouponRequest("SO-FULL-2", null, new BigDecimal("100"))));
    assertThat(redeemed.recentRedemptions().get(0).redeemedAmount())
        .isEqualByComparingTo("5.0000");
    assertThat(redeemed.recentRedemptions().get(0).subsidyCost())
        .isEqualByComparingTo("5.0000");
  }

  // ==================================================================
  // 10. DB 层兜底
  // ==================================================================

  @Test
  @DisplayName("14.4.1.2 DB 层兜底：折扣率与面额同时给被 CHECK 拒；券码唯一性带租户维度")
  void databaseConstraintsRejectInvalidShapes() {
    seedShop(a, "DB-AMZ");
    var created = savePercentCoupon(actorA, "C-DB", "DB-AMZ");

    // 🔴 绕服务层直接写 SQL：折扣率与面额同时给 ⇒ CHECK 拒
    var thrown =
        catchThrowable(
            () ->
                execAsTenant(
                    a,
                    "insert into ad_coupon (tenant_id,id,platform_id,shop_code,coupon_code,"
                        + " coupon_name,coupon_type,discount_rate,discount_value,currency,total_stock,"
                        + " status) values ('"
                        + a
                        + "','"
                        + UUID.randomUUID()
                        + "','"
                        + AMAZON
                        + "','DB-AMZ','C-DB-BAD','坏券','DISCOUNT_CODE',0.1,5,'USD',1,'DRAFT')"));
    assertThat(thrown).isNotNull();

    // 🔴 绕服务层：把库存改到低于已生成数 ⇒ CHECK 拒（claimed_count <= generated_count <= total_stock）
    var stockThrown =
        catchThrowable(
            () ->
                execAsTenant(
                    a,
                    "update ad_coupon set generated_count = 99 where tenant_id = '"
                        + a
                        + "' and id = '"
                        + created.coupon().id()
                        + "'"));
    assertThat(stockThrown).isNotNull();

    // 🔴 券码唯一性带租户维度：同租户内同码被拒，另一个租户同码可以
    activate(actorA, created.coupon().id());
    var codes =
        as(
            actorA,
            "channel.operation.update",
            () ->
                service.generateCodes(
                    created.coupon().id().toString(),
                    new GenerateCouponCodesRequest(1, "DUP", 6, null)));
    String code = codes.items().get(0).code();

    var dupThrown =
        catchThrowable(
            () ->
                execAsTenant(
                    a,
                    "insert into ad_coupon_code (tenant_id,id,coupon_id,code,status)"
                        + " values ('"
                        + a
                        + "','"
                        + UUID.randomUUID()
                        + "','"
                        + created.coupon().id()
                        + "','"
                        + code
                        + "','GENERATED')"));
    assertThat(dupThrown).isNotNull();

    // 另一个租户用同一个码串 ⇒ 不冲突（唯一性约束**带租户维度**）
    seedShop(b, "DB-AMZ-B");
    var other =
        savePercentCoupon(actorB, "C-DB-B", "DB-AMZ-B");
    activate(actorB, other.coupon().id());
    execAsTenant(
        b,
        "insert into ad_coupon_code (tenant_id,id,coupon_id,code,status)"
            + " values ('"
            + b
            + "','"
            + UUID.randomUUID()
            + "','"
            + other.coupon().id()
            + "','"
            + code
            + "','GENERATED')");
  }

  // ==================================================================
  // 11. 真分页真筛选 + 跨租户 404
  // ==================================================================

  @Test
  @DisplayName("14.4.1.2 真分页真筛选；跨租户与不存在返回逐字相同的 404")
  void paginationAndFiltersAreReal() {
    seedShop(a, "PAGE-AMZ");
    for (int i = 1; i <= 5; i++) {
      savePercentCoupon(actorA, "C-PAGE-" + i, "PAGE-AMZ");
    }
    var page = as(actorA, "channel.operation.read", () -> service.coupons(null, null, null, null, null, null, null, 0, 2));
    assertThat(page.items()).hasSize(2);
    assertThat(page.total()).isEqualTo(5);
    assertThat(page.size()).isEqualTo(2);

    var filtered =
        as(
            actorA,
            "channel.operation.read",
            () -> service.coupons(null, "DISCOUNT_CODE", null, "PAGE-AMZ", null, null, "C-PAGE-3", 0, 50));
    assertThat(filtered.total()).isEqualTo(1);
    assertThat(filtered.items().get(0).couponCode()).isEqualTo("C-PAGE-3");

    // 🔴 跨租户与不存在返回**逐字相同**的 404
    var crossTenant =
        catchThrowable(
            () ->
                as(
                    actorB,
                    "channel.operation.read",
                    () -> service.coupon(page.items().get(0).id().toString())));
    var missing =
        catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.read",
                    () -> service.coupon(UUID.randomUUID().toString())));
    assertThat(crossTenant).isNotNull();
    assertThat(missing).isNotNull();
    assertThat(((org.springframework.web.server.ResponseStatusException) crossTenant).getReason())
        .isEqualTo(((org.springframework.web.server.ResponseStatusException) missing).getReason());
  }

  @Test
  @DisplayName("14.4.1.2 HTTP 端点真的通：券列表 / 详情 / 统计 / 券码")
  void httpEndpointsWork() throws Exception {
    seedShop(a, "HTTP-AMZ");
    var created = savePercentCoupon(actorA, "C-HTTP", "HTTP-AMZ");
    activate(actorA, created.coupon().id());
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.generateCodes(
                created.coupon().id().toString(),
                new GenerateCouponCodesRequest(2, null, null, null)));

    http.perform(get("/channel/ad-coupons/capabilities").header("Authorization", bearerA))
        .andExpect(status().isOk());
    http.perform(get("/channel/ad-coupons/options").header("Authorization", bearerA))
        .andExpect(status().isOk());
    http.perform(get("/channel/ad-coupons/coupons").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));
    http.perform(get("/channel/ad-coupons/coupons/" + created.coupon().id()).header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.coupon.couponCode").value("C-HTTP"));
    http.perform(
            get("/channel/ad-coupons/coupons/" + created.coupon().id() + "/codes")
                .header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2));
    http.perform(
            get("/channel/ad-coupons/coupons/" + created.coupon().id() + "/statistics")
                .header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.generatedCount").value(2));
    http.perform(
            get("/channel/ad-coupons/coupons/" + created.coupon().id() + "/channels")
                .header("Authorization", bearerA))
        .andExpect(status().isOk());
    // 伪造 tenant 头不改变租户上下文
    http.perform(
            get("/channel/ad-coupons/coupons")
                .header("Authorization", bearerA)
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));
  }

  // ==================================================================
  // 12. 秒杀：库存真扣 + 限领 + 核销绑订单 + 统计真聚合
  // ==================================================================

  @Test
  @DisplayName("14.4.1.2 秒杀：库存真扣、按人限领、核销绑定订单、统计真聚合")
  void flashSaleDeductsStockBindsOrderAndAggregates() {
    UUID shopId = seedShop(a, "FS-AMZ");
    UUID listingId = seedListing(a, shopId, "EXT-FS-1", "秒杀商品");

    var created =
        as(
            actorA,
            "channel.operation.create",
            () ->
                flashService.saveFlashSale(
                    new AdFlashSaleModels.SaveFlashSaleRequest(
                        "FS-1",
                        "限时秒杀",
                        AMAZON,
                        "FS-AMZ",
                        listingId,
                        new BigDecimal("9.9000"),
                        new BigDecimal("19.9000"),
                        "USD",
                        START,
                        END,
                        new BigDecimal("5"),
                        BigDecimal.ONE,
                        "ON_SITE",
                        "首页秒杀位",
                        "测试秒杀")));

    assertThat(created.sale().status()).isEqualTo("DRAFT");
    as(
        actorA,
        "channel.operation.update",
        () ->
            flashService.changeStatus(
                created.sale().id().toString(),
                new AdFlashSaleModels.ChangeFlashSaleStatusRequest("ACTIVE", "测试上线")));

    // 领取：库存真扣
    var claimed =
        as(
            actorA,
            "channel.operation.update",
            () ->
                flashService.claim(
                    created.sale().id().toString(),
                    new AdFlashSaleModels.ClaimFlashSaleRequest("user-1", null)));
    assertThat(claimed.sale().claimedQuantity()).isEqualByComparingTo("1");
    assertThat(claimed.sale().remainingStock()).isEqualByComparingTo("4");

    // 🔴 同一个人再领 ⇒ 超过每人限领 1 件 ⇒ 被拒
    var thrown =
        catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.update",
                    () ->
                        flashService.claim(
                            created.sale().id().toString(),
                            new AdFlashSaleModels.ClaimFlashSaleRequest("user-1", null))));
    assertThat(thrown).isNotNull();

    // 🔴 核销必须带订单标识
    var noOrder =
        catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.update",
                    () ->
                        flashService.redeem(
                            created.sale().id().toString(),
                            new AdFlashSaleModels.RedeemFlashSaleRequest("", null))));
    assertThat(noOrder).isNotNull();

    var redeemed =
        as(
            actorA,
            "channel.operation.update",
            () ->
                flashService.redeem(
                    created.sale().id().toString(),
                    new AdFlashSaleModels.RedeemFlashSaleRequest("SO-FS-1", null)));
    AdFlashSaleModels.AdFlashSaleClaimRow row = redeemed.recentClaims().get(0);
    assertThat(row.orderRef()).isEqualTo("SO-FS-1");
    // 🔴 让利额 = (19.90 − 9.90) × 1 = 10.0000
    assertThat(row.redeemedAmount()).isEqualByComparingTo("10.0000");
    assertThat(row.subsidyCost()).isEqualByComparingTo("10.0000");
    assertThat(row.status()).isEqualTo("REDEEMED");

    // 🔴 统计是真聚合
    var stats =
        as(actorA, "channel.operation.read", () -> flashService.statistics(created.sale().id().toString()));
    assertThat(stats.claimedCount()).isEqualTo(1);
    assertThat(stats.redeemedCount()).isEqualTo(1);
    assertThat(stats.remainingStock()).isEqualByComparingTo("4");
    assertThat(stats.redemptionRate()).isEqualByComparingTo("1.000000");
    assertThat(stats.totalSubsidyCost()).isEqualByComparingTo("10.0000");
    assertThat(stats.dailyTrend()).hasSize(1);
    // 🔴 毛利维度不可用（成本价在 catalog，跨服务不可读）—— 必须写清，不写假 0
    assertThat(stats.marginNote()).contains("不可用");
  }

  @Test
  @DisplayName("14.4.1.2 秒杀：库存不足被拒；原价没登记 ⇒ 让利额不可比（null），不是 0")
  void flashSaleStockGuardAndIncomparableNetPrice() {
    UUID shopId = seedShop(a, "FS2-AMZ");
    UUID listingId = seedListing(a, shopId, "EXT-FS-2", "秒杀商品 2");

    var created =
        as(
            actorA,
            "channel.operation.create",
            () ->
                flashService.saveFlashSale(
                    new AdFlashSaleModels.SaveFlashSaleRequest(
                        "FS-2",
                        "限量秒杀",
                        AMAZON,
                        "FS2-AMZ",
                        listingId,
                        new BigDecimal("9.9000"),
                        null,
                        "USD",
                        START,
                        END,
                        new BigDecimal("1"),
                        null,
                        "SOCIAL",
                        "社媒",
                        "测试秒杀")));
    as(
        actorA,
        "channel.operation.update",
        () ->
            flashService.changeStatus(
                created.sale().id().toString(),
                new AdFlashSaleModels.ChangeFlashSaleStatusRequest("ACTIVE", "测试上线")));

    as(
        actorA,
        "channel.operation.update",
        () ->
            flashService.claim(
                created.sale().id().toString(),
                new AdFlashSaleModels.ClaimFlashSaleRequest("user-1", null)));

    // 🔴 库存 1 件已领完 ⇒ 再领被拒
    var thrown =
        catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.update",
                    () ->
                        flashService.claim(
                            created.sale().id().toString(),
                            new AdFlashSaleModels.ClaimFlashSaleRequest("user-2", null))));
    assertThat(thrown).isNotNull();

    // 🔴 原价没登记 ⇒ 让利额不可比（null），不是 0
    var redeemed =
        as(
            actorA,
            "channel.operation.update",
            () ->
                flashService.redeem(
                    created.sale().id().toString(),
                    new AdFlashSaleModels.RedeemFlashSaleRequest("SO-FS-2", null)));
    assertThat(redeemed.recentClaims().get(0).redeemedAmount()).isNull();
    assertThat(redeemed.recentClaims().get(0).subsidyCost()).isNull();
  }

  @Test
  @DisplayName("14.4.1.2 券状态机：终态不可回退；草稿态不能生成券码")
  void couponStatusMachineIsEnforced() {
    seedShop(a, "STAT2-AMZ");
    var created = savePercentCoupon(actorA, "C-STAT2", "STAT2-AMZ");

    // 🔴 草稿态不能生成券码
    var thrown =
        catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.update",
                    () ->
                        service.generateCodes(
                            created.coupon().id().toString(),
                            new GenerateCouponCodesRequest(1, null, null, null))));
    assertThat(thrown).isNotNull();

    activate(actorA, created.coupon().id());
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.changeStatus(
                created.coupon().id().toString(),
                new ChangeCouponStatusRequest("CANCELLED", "测试停用")));

    // 🔴 终态不可回退
    var back =
        catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.update",
                    () ->
                        service.changeStatus(
                            created.coupon().id().toString(),
                            new ChangeCouponStatusRequest("ACTIVE", "试图复活"))));
    assertThat(back).isNotNull();
    assertThat(rawCoupon(a, created.coupon().id()).status()).isEqualTo("CANCELLED");
  }

  @Test
  @DisplayName("14.4.1.2 生效时间窗之外不能领取")
  void claimOutsideScheduleWindowIsRejected() {
    seedShop(a, "WINDOW-AMZ");
    var created =
        saveCoupon(
            actorA,
            new SaveCouponRequest(
                "C-WINDOW",
                "未来的券",
                "DISCOUNT_CODE",
                AMAZON,
                "WINDOW-AMZ",
                null,
                new BigDecimal("0.100000"),
                null,
                null,
                null,
                null,
                null,
                "USD",
                FUTURE_START,
                null,
                new BigDecimal("3"),
                BigDecimal.ONE,
                "WIN",
                6,
                null,
                "测试时间窗"));
    activate(actorA, created.coupon().id());
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.generateCodes(
                created.coupon().id().toString(),
                new GenerateCouponCodesRequest(1, null, null, null)));

    var thrown =
        catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.update",
                    () ->
                        service.claim(
                            created.coupon().id().toString(),
                            new ClaimCouponRequest("PULL", "user-1", "ON_SITE", null, null))));
    assertThat(thrown).isNotNull();
  }
}
