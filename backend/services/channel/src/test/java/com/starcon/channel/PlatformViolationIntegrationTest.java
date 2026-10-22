package com.starcon.channel;

import static com.starcon.channel.PlatformViolationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 16.1.2.1 违规与处罚的集成测试（**真实 PostgreSQL 16**，Testcontainers）。
 *
 * <p>🔴 这组用例守的是本模块最容易做丢的十件事：
 *
 * <ol>
 *   <li><b>值域由 DB 层钉死</b>：非法违规类型 / 非法处罚措施**直接写库会被 CHECK 拒**；</li>
 *   <li><b>扣款金额带币种</b>：扣款必须给金额 + 币种；其它措施**带金额即被拒**；</li>
 *   <li><b>不同币种不直接相加</b>：扣款合计**按币种分组**，**没有**跨币种总数；</li>
 *   <li><b>状态机在服务层与 DB 层都被拒</b>：非法流转 422，**直接 UPDATE 也被触发器拒**；</li>
 *   <li><b>整改凭证 append-only</b>：UPDATE / DELETE 都被 DB 拒；</li>
 *   <li><b>幂等</b>：同一平台通知重复同步**不产生第二条记录**；</li>
 *   <li><b>来源如实</b>：人工登记恒 MANUAL，同步恒 PLATFORM_SYNC，**互不冒充**；</li>
 *   <li><b>批量定位真的批量</b>：受影响 listing 一次查出、库存**一次**调用；</li>
 *   <li><b>查不到 ≠ 没有货</b>：上游不可达时库存那一段报「读不到」，**不是**「可售 0」；</li>
 *   <li><b>下游 4 刊登真的下架</b>：listing 状态真被改成 DELISTED_BY_US 并留状态历史。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlatformViolationIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer warehouse;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");

  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\","
          + "\"channel.sync.read\",\"channel.sync.trigger\"],\"scopeMode\":\"ALL\"}]";

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

  /** 🔴 warehouse 桩：07 库存的可售量。**全局共享**。 */
  static final Map<String, BigDecimal> sellableBySku = new ConcurrentHashMap<>();

  /** 置 true 时 warehouse 桩直接断开连接：用来证明「查不到」不会被读成「可售 0」。 */
  static volatile boolean warehouseDown = false;

  /** 记录 warehouse 被调用的次数与每次的 sku 数量（用来证明「批量」不是「循环」）。 */
  static final List<Integer> warehouseCallSizes = java.util.Collections.synchronizedList(new ArrayList<>());

  static AccessService.Access actor(UUID uid, UUID tenantId, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenantId, "Test", UUID.randomUUID(), null,
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
      identity.start();

      // ---- warehouse 桩：GET /warehouse/inventory/sellable/skus ----
      warehouse = HttpServer.create(new InetSocketAddress(0), 0);
      warehouse.createContext(
          "/warehouse",
          e -> {
            if (warehouseDown) {
              e.close();
              return;
            }
            try {
              String query = e.getRequestURI().getRawQuery();
              List<String> skuIds = new ArrayList<>();
              if (query != null) {
                for (String pair : query.split("&")) {
                  int eq = pair.indexOf('=');
                  if (eq < 0) continue;
                  String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                  if (!"skuId".equals(key)) continue;
                  skuIds.add(URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
              }
              warehouseCallSizes.add(skuIds.size());
              List<Map<String, Object>> rows = new ArrayList<>();
              for (String skuId : skuIds) {
                BigDecimal sellable = sellableBySku.get(skuId);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("skuId", skuId);
                row.put("skuCode", "SKU-" + skuId.substring(0, 8));
                row.put("skuName", "测试商品");
                row.put("onHand", sellable == null ? new BigDecimal("0") : sellable);
                row.put("reserved", new BigDecimal("0"));
                row.put("available", sellable == null ? new BigDecimal("0") : sellable);
                row.put("sellableBeforeBuffer", sellable == null ? new BigDecimal("0") : sellable);
                row.put("safetyBuffer", new BigDecimal("0"));
                row.put("sellable", sellable == null ? new BigDecimal("0") : sellable);
                rows.add(row);
              }
              Map<String, Object> page = new LinkedHashMap<>();
              page.put("rows", rows);
              page.put("total", rows.size());
              page.put("page", 0);
              page.put("size", 50);
              page.put("note", "stub");
              byte[] body = new ObjectMapper().writeValueAsBytes(page);
              e.getResponseHeaders().add("Content-Type", "application/json");
              e.sendResponseHeaders(200, body.length);
              e.getResponseBody().write(body);
              e.close();
            } catch (Exception ex) {
              e.sendResponseHeaders(500, -1);
              e.close();
            }
          });
      warehouse.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * 🔴 关掉 identity / warehouse 两台桩。
   *
   * <p>它们是<b>静态</b>桩：不起就停 ⇒ 端口被占住整整一轮 JVM 生命周期。
   * 既是资源泄漏，也会抬高同模块里「桩抢同一个端口」的机率
   * （见 {@code StockForecastAvailabilityPortTest} 的端口遮蔽假红）。
   */
  @AfterAll
  static void stopStubs() {
    if (identity != null) identity.stop(0);
    if (warehouse != null) warehouse.stop(0);
  }

  @BeforeEach
  void freshTenant() {
    tenant = UUID.randomUUID();
    bearer = "Bearer t-" + tenant;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearer, actor(user, tenant, grants));
    who = actors.get(bearer);
    sellableBySku.clear();
    warehouseCallSizes.clear();
    warehouseDown = false;
  }

  UUID tenant;
  AccessService.Access who;
  String bearer;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("WAREHOUSE_URL", () -> "http://localhost:" + warehouse.getAddress().getPort());
  }

  @Autowired PlatformViolationService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(String permission, Supplier<T> work) {
    return access.run(who, permission, work);
  }

  void execAsTenant(UUID tenantId, String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenantId + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(statement);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static String scalar(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(statement)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static long countRows(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(statement)) {
      return rs.next() ? rs.getLong(1) : 0L;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  UUID seedShop(String code) {
    UUID id = UUID.randomUUID();
    execAsTenant(
        tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('"
            + tenant
            + "','"
            + id
            + "','"
            + AMAZON
            + "','"
            + code
            + "','测试店铺 "
            + code
            + "')");
    return id;
  }

  UUID seedListing(UUID shopId, String externalItemId) {
    UUID id = UUID.randomUUID();
    execAsTenant(
        tenant,
        "insert into listing (tenant_id,id,shop_id,external_item_id,title,status,platform_stock_state)"
            + " values ('"
            + tenant
            + "','"
            + id
            + "','"
            + shopId
            + "','"
            + externalItemId
            + "','测试商品 "
            + externalItemId
            + "','ON_SALE','IN_STOCK')");
    return id;
  }

  void seedVariant(UUID listingId, UUID skuId) {
    execAsTenant(
        tenant,
        "insert into listing_variant (tenant_id,id,listing_id,external_variant_id,mapping_status,internal_sku_id)"
            + " values ('"
            + tenant
            + "','"
            + UUID.randomUUID()
            + "','"
            + listingId
            + "','VAR-"
            + listingId.toString().substring(0, 6)
            + "','MAPPED','"
            + skuId
            + "')");
  }

  PlatformViolationRegisterInput registerInput(
      UUID shopId, String noticeId, String type, String title) {
    return new PlatformViolationRegisterInput(
        shopId.toString(),
        noticeId,
        type,
        title,
        "测试违规说明",
        OffsetDateTime.now(ZoneOffset.UTC).minusDays(3),
        OffsetDateTime.now(ZoneOffset.UTC).minusDays(3),
        OffsetDateTime.now(ZoneOffset.UTC).plusDays(10));
  }

  // ==================================================================
  // 一、能力声明
  // ==================================================================

  @Test
  @DisplayName("16.1.2.1 能力声明：12 财务与 16.1.2.2 如实标未交付，4 刊登已交付")
  void capabilitiesDeclareDownstreamTruthfully() {
    PlatformViolationCapabilities caps = as("channel.operation.read", () -> service.capabilities());

    PlatformViolationCapabilityGap finance =
        caps.gaps().stream().filter(g -> g.moduleId().equals("12")).findFirst().orElseThrow();
    assertThat(finance.available()).as("12 财务未交付 ⇒ available 必须为 false").isFalse();
    assertThat(finance.ownerModule()).isNotBlank();
    assertThat(finance.impact()).contains("不建财务表");

    PlatformViolationCapabilityGap appeal =
        caps.gaps().stream().filter(g -> g.moduleId().equals("16.1.2.2")).findFirst().orElseThrow();
    assertThat(appeal.available()).as("16.1.2.2 申诉管理未交付 ⇒ available 必须为 false").isFalse();

    PlatformViolationCapabilityGap listing =
        caps.gaps().stream().filter(g -> g.moduleId().equals("04")).findFirst().orElseThrow();
    assertThat(listing.available()).as("4 刊登的批量下架已交付 ⇒ available 必须为 true").isTrue();

    assertThat(caps.measures())
        .containsExactly("WARNING", "DELIST", "RESTRICT_SALES", "DEDUCTION", "SUSPEND_ACCOUNT");
    assertThat(caps.statuses()).contains("PENDING", "IN_PROGRESS", "RECTIFIED", "APPEALING", "CLOSED");
    assertThat(caps.statusTransitions().get("CLOSED")).isEmpty();
    assertThat(caps.moneySemantics()).contains("不同币种不得直接相加");
    assertThat(caps.idempotencySemantics()).contains("external_notice_id");
  }

  // ==================================================================
  // 二、登记 + 幂等 + 来源
  // ==================================================================

  @Test
  @DisplayName("16.1.2.1 人工登记恒标 MANUAL；同一平台通知重复登记 ⇒ 幂等命中，不产生第二条")
  void manualRegistrationIsManualAndIdempotent() {
    UUID shop = seedShop("REG-1");
    PlatformViolationRegistration first =
        as(
            "channel.operation.create",
            () -> service.register(registerInput(shop, "NOTICE-1", "PRODUCT_SAFETY", "商品不合规")));
    assertThat(first.created()).isTrue();
    assertThat(first.violation().violation().source()).isEqualTo("MANUAL");
    assertThat(first.violation().violation().sourceLabel()).isEqualTo("人工登记");

    PlatformViolationRegistration second =
        as(
            "channel.operation.create",
            () -> service.register(registerInput(shop, "NOTICE-1", "PRODUCT_SAFETY", "商品不合规")));
    assertThat(second.created()).as("同一条平台通知重复登记 ⇒ 幂等命中").isFalse();
    assertThat(second.violation().violation().violationId())
        .isEqualTo(first.violation().violation().violationId());
    assertThat(countRows("select count(*) from platform_violation where tenant_id='" + tenant + "'"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("16.1.2.1 同步入口：source 恒 PLATFORM_SYNC；重复同步 ⇒ skipped，不产生第二条记录")
  void syncIsIdempotentAndMarkedPlatformSync() {
    UUID shop = seedShop("SYNC-1");
    PlatformViolationNotice notice =
        new PlatformViolationNotice(
            "PLATFORM-NOTICE-1",
            "PROHIBITED_ITEM",
            "违禁品",
            "平台通知",
            OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
            OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
            OffsetDateTime.now(ZoneOffset.UTC).plusDays(5),
            List.of(
                new PlatformViolationSubjectInput(
                    "SKU", "SKU-A", UUID.randomUUID().toString(), null, null, new BigDecimal("3"), "")),
            List.of(
                new PlatformPenaltyInput(
                    "DEDUCTION", new BigDecimal("120.50"), "USD", null, null, "平台罚款")));

    PlatformViolationSyncResult first =
        as(
            "channel.sync.trigger",
            () ->
                service.sync(
                    shop.toString(),
                    new PlatformViolationSyncInput(List.of(notice))));
    assertThat(first.fetched()).isEqualTo(1);
    assertThat(first.written()).isEqualTo(1);
    assertThat(first.skipped()).isZero();
    assertThat(first.failed()).isZero();

    PlatformViolationSyncResult second =
        as(
            "channel.sync.trigger",
            () ->
                service.sync(
                    shop.toString(),
                    new PlatformViolationSyncInput(List.of(notice))));
    assertThat(second.written()).as("重复同步 ⇒ 一条都不许新写").isZero();
    assertThat(second.skipped()).as("重复同步 ⇒ 计入 skipped").isEqualTo(1);
    assertThat(countRows("select count(*) from platform_violation where tenant_id='" + tenant + "'"))
        .isEqualTo(1);
    assertThat(scalar("select source from platform_violation where tenant_id='" + tenant + "'"))
        .as("同步来的记录 source 恒为 PLATFORM_SYNC")
        .isEqualTo("PLATFORM_SYNC");

    // 同步运行记录留痕：取到 / 写入 / 跳过各是几条。
    PlatformViolationSyncRunPage runs =
        as("channel.operation.read", () -> service.syncRuns(shop.toString(), 0, 10));
    assertThat(runs.total()).isEqualTo(2);
    assertThat(runs.items().get(0).skippedCount()).isEqualTo(1);
    assertThat(runs.items().get(0).writtenCount()).isZero();
  }

  @Test
  @DisplayName("16.1.2.1 同步入口：非法违规类型的那条通知被拒并计入 failed，其余照常写入")
  void syncReportsFailuresInsteadOfHidingThem() {
    UUID shop = seedShop("SYNC-2");
    PlatformViolationNotice bad =
        new PlatformViolationNotice(
            "NOTICE-BAD",
            "NOT_A_TYPE",
            "非法类型",
            "",
            OffsetDateTime.now(ZoneOffset.UTC),
            null,
            null,
            List.of(),
            List.of());
    PlatformViolationSyncResult result =
        as(
            "channel.sync.trigger",
            () -> service.sync(shop.toString(), new PlatformViolationSyncInput(List.of(bad))));
    assertThat(result.failed()).isEqualTo(1);
    assertThat(result.written()).isZero();
    assertThat(result.failures()).hasSize(1);
    assertThat(result.failures().get(0)).contains("NOTICE-BAD");
    assertThat(countRows("select count(*) from platform_violation where tenant_id='" + tenant + "'"))
        .isZero();
  }

  // ==================================================================
  // 三、值域：DB 层 CHECK 钉死
  // ==================================================================

  @Test
  @DisplayName("16.1.2.1 🔴 违规类型 / 处罚措施的值域由 DB 层 CHECK 钉死（直接写库被拒）")
  void valueDomainsArePinnedByTheDatabase() {
    UUID shop = seedShop("CHECK-1");
    assertThatThrownBy(
            () ->
                execAsTenant(
                    tenant,
                    "insert into platform_violation (id,tenant_id,shop_id,external_notice_id,source,"
                        + "violation_type,title,occurred_at) values ('"
                        + UUID.randomUUID()
                        + "','"
                        + tenant
                        + "','"
                        + shop
                        + "','NOTICE-CHECK','MANUAL','NOT_A_TYPE','x',now())"))
        .as("非法违规类型必须被 DB 拒")
        .hasMessageContaining("platform_violation");

    UUID violationId = UUID.randomUUID();
    execAsTenant(
        tenant,
        "insert into platform_violation (id,tenant_id,shop_id,external_notice_id,source,"
            + "violation_type,title,occurred_at) values ('"
            + violationId
            + "','"
            + tenant
            + "','"
            + shop
            + "','NOTICE-CHECK-2','MANUAL','PRODUCT_SAFETY','x',now())");
    assertThatThrownBy(
            () ->
                execAsTenant(
                    tenant,
                    "insert into platform_penalty (id,tenant_id,violation_id,shop_id,measure,"
                        + "effective_at) values ('"
                        + UUID.randomUUID()
                        + "','"
                        + tenant
                        + "','"
                        + violationId
                        + "','"
                        + shop
                        + "','NOT_A_MEASURE',now())"))
        .as("非法处罚措施必须被 DB 拒")
        .hasMessageContaining("platform_penalty");
  }

  @Test
  @DisplayName("16.1.2.1 🔴 只有扣款带金额；其它措施带金额 / 扣款缺币种都被拒（应用层 + DB 层）")
  void onlyDeductionCarriesMoney() {
    UUID shop = seedShop("MONEY-1");
    UUID violationId =
        UUID.fromString(as(
                    "channel.operation.create",
                    () ->
                        service.register(registerInput(shop, "NOTICE-M1", "PRODUCT_SAFETY", "x"))
                            .violation()
                            .violation()
                            .violationId())
                );

    // 非扣款带金额 ⇒ 422
    assertThatThrownBy(
            () ->
                as(
                    "channel.operation.create",
                    () ->
                        service.addPenalty(
                            violationId.toString(),
                            new PlatformPenaltyInput(
                                "WARNING", new BigDecimal("10.00"), "USD", null, null, ""))))
        .hasMessageContaining("不带金额");

    // 扣款缺币种 ⇒ 422
    assertThatThrownBy(
            () ->
                as(
                    "channel.operation.create",
                    () ->
                        service.addPenalty(
                            violationId.toString(),
                            new PlatformPenaltyInput(
                                "DEDUCTION", new BigDecimal("10.00"), "", null, null, ""))))
        .hasMessageContaining("币种");

    // 扣款缺金额 ⇒ 422
    assertThatThrownBy(
            () ->
                as(
                    "channel.operation.create",
                    () ->
                        service.addPenalty(
                            violationId.toString(),
                            new PlatformPenaltyInput("DEDUCTION", null, "USD", null, null, ""))))
        .hasMessageContaining("金额");

    // 🔴 DB 层同样钉死：非扣款带金额直接写库被拒。
    assertThatThrownBy(
            () ->
                execAsTenant(
                    tenant,
                    "insert into platform_penalty (id,tenant_id,violation_id,shop_id,measure,"
                        + "penalty_amount,penalty_currency,effective_at) values ('"
                        + UUID.randomUUID()
                        + "','"
                        + tenant
                        + "','"
                        + violationId
                        + "','"
                        + shop
                        + "','WARNING',10.00,'USD',now())"))
        .as("非扣款带金额必须被 DB 拒")
        .hasMessageContaining("platform_penalty");

    // 扣款带币种合法 ⇒ 写入成功。
    PlatformPenalty penalty =
        as(
            "channel.operation.create",
            () ->
                service.addPenalty(
                    violationId.toString(),
                    new PlatformPenaltyInput(
                        "DEDUCTION", new BigDecimal("120.50"), "usd", null, null, "平台罚款")));
    assertThat(penalty.penaltyAmount()).isEqualByComparingTo("120.50");
    assertThat(penalty.penaltyCurrency()).isEqualTo("USD");
    assertThat(penalty.moneyText()).isEqualTo("120.5 USD");
  }

  @Test
  @DisplayName("16.1.2.1 🔴 扣款合计按币种分组：USD 与 EUR 各自成行，**没有**跨币种总数")
  void penaltyTotalsAreGroupedByCurrency() {
    UUID shop = seedShop("TOTAL-1");
    UUID violationId =
        UUID.fromString(as(
                    "channel.operation.create",
                    () ->
                        service.register(registerInput(shop, "NOTICE-T1", "PRODUCT_SAFETY", "x"))
                            .violation()
                            .violation()
                            .violationId())
                );
    as(
        "channel.operation.create",
        () ->
            service.addPenalty(
                violationId.toString(),
                new PlatformPenaltyInput(
                    "DEDUCTION", new BigDecimal("100.00"), "USD", null, null, "")));
    as(
        "channel.operation.create",
        () ->
            service.addPenalty(
                violationId.toString(),
                new PlatformPenaltyInput(
                    "DEDUCTION", new BigDecimal("50.00"), "EUR", null, null, "")));
    as(
        "channel.operation.create",
        () ->
            service.addPenalty(
                violationId.toString(),
                new PlatformPenaltyInput(
                    "DEDUCTION", new BigDecimal("25.00"), "USD", null, null, "")));

    PlatformPenaltyPage page =
        as("channel.operation.read", () -> service.penalties(null, null, null, 0, 50));
    assertThat(page.totalsByCurrency()).hasSize(2);
    assertThat(page.totalsByCurrency())
        .extracting(PlatformPenaltyTotal::currency)
        .containsExactly("EUR", "USD");
    assertThat(page.totalsByCurrency().get(0).amount()).isEqualByComparingTo("50.00");
    assertThat(page.totalsByCurrency().get(1).amount()).isEqualByComparingTo("125.00");
    assertThat(page.totalNote()).contains("按币种分组");
    // 🔴 没有「一个总数」这个字段可以误读成跨币种合计。
    assertThat(page.totalNote()).contains("没有");
  }

  // ==================================================================
  // 四、状态机
  // ==================================================================

  @Test
  @DisplayName("16.1.2.1 处理状态机：合法流转走得通，非法流转 422（未处理不能直接整改）")
  void violationStatusMachineIsEnforcedInTheService() {
    UUID shop = seedShop("STATUS-1");
    UUID violationId =
        UUID.fromString(as(
                    "channel.operation.create",
                    () ->
                        service.register(registerInput(shop, "NOTICE-S1", "PRODUCT_SAFETY", "x"))
                            .violation()
                            .violation()
                            .violationId())
                );

    assertThatThrownBy(
            () ->
                as(
                    "channel.operation.update",
                    () ->
                        service.transition(
                            violationId.toString(), new PlatformViolationStatusInput("RECTIFIED", ""))))
        .as("未处理不能直接转已整改")
        .hasMessageContaining("不能从");

    PlatformViolation moved =
        as(
            "channel.operation.update",
            () ->
                service.transition(
                    violationId.toString(), new PlatformViolationStatusInput("IN_PROGRESS", "")));
    assertThat(moved.status()).isEqualTo("IN_PROGRESS");
    assertThat(moved.nextStatuses()).containsExactly("RECTIFIED", "APPEALING", "CLOSED");
  }

  @Test
  @DisplayName("16.1.2.1 🔴 非法状态转换在 DB 层也被拒（触发器是服务层之外的第二道防线）")
  void illegalStatusTransitionIsRejectedByTheDatabase() {
    UUID shop = seedShop("STATUS-2");
    UUID violationId =
        UUID.fromString(as(
                    "channel.operation.create",
                    () ->
                        service.register(registerInput(shop, "NOTICE-S2", "PRODUCT_SAFETY", "x"))
                            .violation()
                            .violation()
                            .violationId())
                );
    assertThatThrownBy(
            () ->
                execAsTenant(
                    tenant,
                    "update platform_violation set status='CLOSED' where tenant_id='"
                        + tenant
                        + "' and id='"
                        + violationId
                        + "'"))
        .as("未处理直接转已关闭必须被 DB 拒")
        .hasMessageContaining("不能从");
  }

  @Test
  @DisplayName("16.1.2.1 整改状态机：待整改 → 整改中 → 已提交 → 已验收；非法流转 422")
  void remediationStatusMachine() {
    UUID shop = seedShop("REM-1");
    UUID violationId =
        UUID.fromString(as(
                    "channel.operation.create",
                    () ->
                        service.register(registerInput(shop, "NOTICE-R1", "PRODUCT_SAFETY", "x"))
                            .violation()
                            .violation()
                            .violationId())
                );
    UUID remediationId =
        UUID.fromString(as(
                    "channel.operation.create",
                    () ->
                        service.addRemediation(
                                violationId.toString(),
                                new PlatformViolationRemediationInput(
                                    "补齐质检报告", OffsetDateTime.now(ZoneOffset.UTC).plusDays(7), "张三"))
                            .remediationId())
                );

    assertThatThrownBy(
            () ->
                as(
                    "channel.operation.update",
                    () ->
                        service.transitionRemediation(
                            remediationId.toString(),
                            new PlatformViolationRemediationStatusInput("VERIFIED", ""))))
        .as("不能跳过「已提交」直接验收")
        .hasMessageContaining("不能从");

    as(
        "channel.operation.update",
        () ->
            service.transitionRemediation(
                remediationId.toString(),
                new PlatformViolationRemediationStatusInput("IN_PROGRESS", "")));
    PlatformViolationRemediation submitted =
        as(
            "channel.operation.update",
            () ->
                service.transitionRemediation(
                    remediationId.toString(),
                    new PlatformViolationRemediationStatusInput("SUBMITTED", "")));
    assertThat(submitted.status()).isEqualTo("SUBMITTED");
    assertThat(submitted.completedAt()).as("「已提交」必须真的提交过（有时刻）").isNotNull();

    PlatformViolationRemediation verified =
        as(
            "channel.operation.update",
            () ->
                service.transitionRemediation(
                    remediationId.toString(),
                    new PlatformViolationRemediationStatusInput("VERIFIED", "")));
    assertThat(verified.status()).isEqualTo("VERIFIED");
    assertThat(verified.verifiedAt()).as("「已验收」必须真的验收过（有时刻）").isNotNull();
  }

  // ==================================================================
  // 五、整改凭证 append-only
  // ==================================================================

  @Test
  @DisplayName("16.1.2.1 🔴 整改凭证是追加式的：UPDATE 与 DELETE 都被 DB 拒")
  void evidenceIsAppendOnly() {
    UUID shop = seedShop("EVID-1");
    UUID violationId =
        UUID.fromString(as(
                    "channel.operation.create",
                    () ->
                        service.register(registerInput(shop, "NOTICE-E1", "PRODUCT_SAFETY", "x"))
                            .violation()
                            .violation()
                            .violationId())
                );
    UUID remediationId =
        UUID.fromString(as(
                    "channel.operation.create",
                    () ->
                        service.addRemediation(
                                violationId.toString(),
                                new PlatformViolationRemediationInput("补齐质检报告", null, ""))
                            .remediationId())
                );
    UUID evidenceId =
        UUID.fromString(as(
                    "channel.operation.create",
                    () ->
                        service.addEvidence(
                                remediationId.toString(),
                                new PlatformViolationEvidenceInput(
                                    "DOCUMENT", "report.pdf", "质检报告", null))
                            .evidenceId())
                );

    assertThatThrownBy(
            () ->
                execAsTenant(
                    tenant,
                    "update platform_violation_evidence set summary='改一改' where tenant_id='"
                        + tenant
                        + "' and id='"
                        + evidenceId
                        + "'"))
        .as("整改凭证不可改写")
        .hasMessageContaining("追加式");
    assertThatThrownBy(
            () ->
                execAsTenant(
                    tenant,
                    "delete from platform_violation_evidence where tenant_id='"
                        + tenant
                        + "' and id='"
                        + evidenceId
                        + "'"))
        .as("整改凭证不可删除")
        .hasMessageContaining("追加式");
    assertThat(countRows("select count(*) from platform_violation_evidence where tenant_id='" + tenant + "'"))
        .isEqualTo(1);
  }

  // ==================================================================
  // 六、涉及主体（多对多）
  // ==================================================================

  @Test
  @DisplayName("16.1.2.1 涉及商品 / 订单是**多对多**：一张违规可挂多个主体，重复挂同一个被跳过")
  void subjectsAreManyToManyAndIdempotent() {
    UUID shop = seedShop("SUBJ-1");
    UUID violationId =
        UUID.fromString(as(
                    "channel.operation.create",
                    () ->
                        service.register(registerInput(shop, "NOTICE-SUB1", "PRODUCT_SAFETY", "x"))
                            .violation()
                            .violation()
                            .violationId())
                );
    UUID skuA = UUID.randomUUID();
    UUID skuB = UUID.randomUUID();

    PlatformViolationSubjectBatchResult first =
        as(
            "channel.operation.create",
            () ->
                service.attachSubjects(
                    violationId.toString(),
                    List.of(
                        new PlatformViolationSubjectInput(
                            "SKU", "SKU-A", skuA.toString(), null, null, new BigDecimal("2"), ""),
                        new PlatformViolationSubjectInput(
                            "SKU", "SKU-B", skuB.toString(), null, null, null, ""),
                        new PlatformViolationSubjectInput(
                            "ORDER", "ORDER-9", null, null, "ORDER-9", null, ""))));
    assertThat(first.requested()).isEqualTo(3);
    assertThat(first.inserted()).isEqualTo(3);

    PlatformViolationSubjectBatchResult second =
        as(
            "channel.operation.create",
            () ->
                service.attachSubjects(
                    violationId.toString(),
                    List.of(
                        new PlatformViolationSubjectInput(
                            "SKU", "SKU-A", skuA.toString(), null, null, new BigDecimal("2"), ""))));
    assertThat(second.inserted()).as("同一主体重复挂 ⇒ 跳过").isZero();
    assertThat(second.skippedRefs()).containsExactly("SKU-A");

    PlatformViolationDetail detail =
        as("channel.operation.read", () -> service.violation(violationId.toString()));
    assertThat(detail.subjects()).hasSize(3);
    assertThat(detail.violation().subjectCount()).isEqualTo(3);
  }

  @Test
  @DisplayName("16.1.2.1 主体类型与定位列不自洽时被拒（SKU 必须给 skuId，ORDER 必须给订单号）")
  void subjectTypeAndLocatorMustMatch() {
    UUID shop = seedShop("SUBJ-2");
    UUID violationId =
        UUID.fromString(as(
                    "channel.operation.create",
                    () ->
                        service.register(registerInput(shop, "NOTICE-SUB2", "PRODUCT_SAFETY", "x"))
                            .violation()
                            .violation()
                            .violationId())
                );
    assertThatThrownBy(
            () ->
                as(
                    "channel.operation.create",
                    () ->
                        service.attachSubjects(
                            violationId.toString(),
                            List.of(
                                new PlatformViolationSubjectInput(
                                    "SKU", "SKU-X", null, null, null, null, "")))))
        .hasMessageContaining("skuId");
    assertThatThrownBy(
            () ->
                as(
                    "channel.operation.create",
                    () ->
                        service.attachSubjects(
                            violationId.toString(),
                            List.of(
                                new PlatformViolationSubjectInput(
                                    "ORDER", "ORDER-X", null, null, null, null, "")))))
        .hasMessageContaining("orderRef");
  }

  // ==================================================================
  // 七、批量定位受影响 listing 与库存
  // ==================================================================

  @Test
  @DisplayName("16.1.2.1 🔴 批量定位：受影响 listing 一次查出，库存**一次**调用取回（不是逐条循环）")
  void affectedIsLocatedInOneBatch() {
    UUID shop = seedShop("AFF-1");
    UUID violationId =
        UUID.fromString(as(
                    "channel.operation.create",
                    () ->
                        service.register(registerInput(shop, "NOTICE-A1", "PRODUCT_SAFETY", "x"))
                            .violation()
                            .violation()
                            .violationId())
                );
    UUID skuA = UUID.randomUUID();
    UUID skuB = UUID.randomUUID();
    UUID listingA = seedListing(shop, "ITEM-A");
    UUID listingB = seedListing(shop, "ITEM-B");
    seedVariant(listingA, skuA);
    seedVariant(listingB, skuB);
    sellableBySku.put(skuA.toString(), new BigDecimal("12"));
    sellableBySku.put(skuB.toString(), new BigDecimal("7"));

    as(
        "channel.operation.create",
        () ->
            service.attachSubjects(
                violationId.toString(),
                List.of(
                    new PlatformViolationSubjectInput(
                        "SKU", "SKU-A", skuA.toString(), null, null, null, ""),
                    new PlatformViolationSubjectInput(
                        "SKU", "SKU-B", skuB.toString(), null, null, null, ""))));

    PlatformViolationAffected affected =
        as("channel.operation.read", () -> service.affected(violationId.toString(), null, bearer));

    assertThat(affected.listingCount()).isEqualTo(2);
    assertThat(affected.listings()).extracting(PlatformViolationAffectedListing::externalItemId)
        .containsExactly("ITEM-A", "ITEM-B");
    assertThat(affected.inventoryAvailable()).isTrue();
    assertThat(affected.inventory()).hasSize(2);
    assertThat(affected.inventory())
        .filteredOn(PlatformViolationAffectedInventory::found)
        .extracting(PlatformViolationAffectedInventory::sellable)
        .containsExactlyInAnyOrder(new BigDecimal("12"), new BigDecimal("7"));

    // 🔴 关键断言：**一次**调用就把两个 SKU 一起取回来了（逐条循环会是 2 次）。
    assertThat(warehouseCallSizes).as("批量取库存 ⇒ 一次调用").hasSize(1);
    assertThat(warehouseCallSizes.get(0)).as("一次调用里带上全部 SKU").isEqualTo(2);
  }

  @Test
  @DisplayName("16.1.2.1 🔴 上游读不到时库存那一段报「读不到」，**不是**「可售 0」")
  void inventoryUnavailableIsNeverReportedAsZero() {
    UUID shop = seedShop("AFF-2");
    UUID violationId =
        UUID.fromString(as(
                    "channel.operation.create",
                    () ->
                        service.register(registerInput(shop, "NOTICE-A2", "PRODUCT_SAFETY", "x"))
                            .violation()
                            .violation()
                            .violationId())
                );
    UUID skuA = UUID.randomUUID();
    as(
        "channel.operation.create",
        () ->
            service.attachSubjects(
                violationId.toString(),
                List.of(
                    new PlatformViolationSubjectInput(
                        "SKU", "SKU-A", skuA.toString(), null, null, null, ""))));

    warehouseDown = true;
    PlatformViolationAffected affected =
        as("channel.operation.read", () -> service.affected(violationId.toString(), null, bearer));
    warehouseDown = false;

    assertThat(affected.inventoryAvailable()).as("上游读不到 ⇒ inventoryAvailable=false").isFalse();
    assertThat(affected.inventoryNote()).contains("读不到");
    assertThat(affected.inventoryNote()).doesNotContain("可售 0");
    assertThat(affected.inventory()).allSatisfy(row -> assertThat(row.sellable()).isNull());
  }

  // ==================================================================
  // 八、批量下架（下游 4 刊登 · 4.1.1.1）
  // ==================================================================

  @Test
  @DisplayName("16.1.2.1 🔴 下游 4 刊登真的下架：listing 状态被改成 DELISTED_BY_US 并留状态历史")
  void delistReallyDelistsTheListings() {
    UUID shop = seedShop("DELIST-1");
    UUID violationId =
        UUID.fromString(as(
                    "channel.operation.create",
                    () ->
                        service.register(registerInput(shop, "NOTICE-D1", "PRODUCT_SAFETY", "x"))
                            .violation()
                            .violation()
                            .violationId())
                );
    UUID listing = seedListing(shop, "ITEM-DELIST");
    as(
        "channel.operation.create",
        () ->
            service.attachSubjects(
                violationId.toString(),
                List.of(
                    new PlatformViolationSubjectInput(
                        "LISTING", "ITEM-DELIST", null, listing.toString(), null, null, ""))));

    PlatformViolationDelistResult result =
        as("channel.operation.update", () -> service.delist(violationId.toString(), bearer));

    assertThat(result.candidateCount()).isEqualTo(1);
    assertThat(result.affectedCount()).isEqualTo(1);
    assertThat(result.outcome()).isEqualTo("SUCCEEDED");
    assertThat(scalar("select status from listing where tenant_id='" + tenant + "' and id='" + listing + "'"))
        .as("listing 状态真的被改了")
        .isEqualTo("DELISTED_BY_US");
    assertThat(
            countRows(
                "select count(*) from listing_status_event where tenant_id='"
                    + tenant
                    + "' and listing_id='"
                    + listing
                    + "' and to_status='DELISTED_BY_US'"))
        .as("状态变更留痕（DB 触发器落的事件）")
        .isEqualTo(1);
  }

  // ==================================================================
  // 九、时间字段（timestamptz 往返）
  // ==================================================================

  @Test
  @DisplayName("16.1.2.1 🔴 timestamptz 往返不是 −28800s：写回的值必须一模一样")
  void timestampsRoundTripWithoutTheEightHourShift() {
    UUID shop = seedShop("TIME-1");
    OffsetDateTime occurred = OffsetDateTime.now(ZoneOffset.UTC).minusHours(9);
    PlatformViolationRegistration registration =
        as(
            "channel.operation.create",
            () ->
                service.register(
                    new PlatformViolationRegisterInput(
                        shop.toString(),
                        "NOTICE-TIME",
                        "PRODUCT_SAFETY",
                        "时间往返",
                        "",
                        occurred,
                        occurred,
                        occurred.plusDays(5))));
    assertThat(registration.violation().violation().occurredAt())
        .as("写回的时刻必须与写进去的一致（不能被当成本地时区偏 −28800s）")
        .isEqualTo(occurred);
    // 🔴 比「UTC 墙钟」：直接比字符串会被 JVM 的本地时区（+08）干扰，
    //    比 epoch 整数又会被小数秒的截断干扰 —— 显式取 UTC 墙钟才是同一个东西。
    assertThat(
            scalar(
                "select to_char(occurred_at at time zone 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS')"
                    + " from platform_violation where tenant_id='"
                    + tenant
                    + "'"))
        .as("DB 里的时刻必须与写进去的是同一瞬时（不能被当成本地时区偏 −28800s）")
        .isEqualTo(
            occurred.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime().toString().substring(0, 19));
  }

  // ==================================================================
  // 十、HTTP 层：真分页 / 真筛选 / 404 / 401
  // ==================================================================

  @Test
  @DisplayName("16.1.2.1 HTTP 真分页 + 真筛选；未认证 401；不存在的违规 404")
  void httpPaginationFilteringAndErrors() throws Exception {
    UUID shop = seedShop("HTTP-1");
    for (int i = 1; i <= 5; i++) {
      final int index = i;
      final String noticeId = "NOTICE-H" + index;
      final String type = index % 2 == 0 ? "PROHIBITED_ITEM" : "PRODUCT_SAFETY";
      as(
          "channel.operation.create",
          () -> service.register(registerInput(shop, noticeId, type, "违规 " + index)));
    }

    http.perform(get("/channel/platform-violations/violations").param("shopId", shop.toString())
            .header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(5))
        .andExpect(jsonPath("$.items.length()").value(5));

    // 真分页：size=2 ⇒ 只回 2 条，total 仍是 5。
    http.perform(get("/channel/platform-violations/violations").param("shopId", shop.toString())
            .param("size", "2").param("page", "1")
            .header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.total").value(5));

    // 真筛选：按违规类型筛。
    http.perform(get("/channel/platform-violations/violations").param("shopId", shop.toString())
            .param("violationType", "PROHIBITED_ITEM")
            .header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2));

    // 未认证 ⇒ 401。
    http.perform(get("/channel/platform-violations/violations")).andExpect(status().isUnauthorized());

    // 不存在的违规 ⇒ 404。
    http.perform(get("/channel/platform-violations/violations/" + UUID.randomUUID())
            .header("Authorization", bearer))
        .andExpect(status().isNotFound());

    // 非法的状态筛选 ⇒ 422（不静默忽略）。
    http.perform(get("/channel/platform-violations/violations").param("status", "NOT_A_STATUS")
            .header("Authorization", bearer))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  @DisplayName("16.1.2.1 不存在的店铺被拒（404），店铺目录是唯一的店铺事实来源")
  void unknownShopIsRejected() {
    assertThatThrownBy(
            () ->
                as(
                    "channel.operation.create",
                    () ->
                        service.register(
                            registerInput(UUID.randomUUID(), "NOTICE-X1", "PRODUCT_SAFETY", "x"))))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .satisfies(e -> assertThat(((org.springframework.web.server.ResponseStatusException) e).getStatusCode().value())
            .isEqualTo(404));
  }

  @Test
  @DisplayName("16.1.2.1 店铺清单来自真实店铺目录表（不是写死的常量）")
  void shopScopeComesFromTheRealDirectory() {
    UUID shop = seedShop("SCOPE-1");
    PlatformViolationShopPage page =
        as("channel.operation.read", () -> service.shops(null, null, null, 0, 50));
    assertThat(page.items()).extracting(PlatformViolationShop::shopId).contains(shop.toString());
    assertThat(page.items())
        .filteredOn(s -> s.shopId().equals(shop.toString()))
        .singleElement()
        .satisfies(s -> {
          assertThat(s.shopCode()).isEqualTo("SCOPE-1");
          assertThat(s.platformCode()).isEqualTo("AMAZON");
        });
  }
}
