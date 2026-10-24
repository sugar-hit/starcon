package com.starcon.channel;

import static com.starcon.channel.VatIossModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * 16.3.2.1 VAT/IOSS 的集成测试（**真实 PostgreSQL 16**）。
 *
 * <p>🔴 这组用例守的是本模块最容易做丢的十六件事：
 *
 * <ol>
 *   <li><b>IOSS 与一般 VAT 分档真的落进数据</b>（不是页面上的一列颜色）；</li>
 *   <li><b>「判不了」是独立的一档</b>（没有收货国家 / 买家类型没采到 / 汇率读不到）；</li>
 *   <li><b>税率读不到 ⇒ 税率为 null</b>，**绝不是 0%**；</li>
 *   <li><b>应税消费品 ⇒ 一般 VAT</b>（哪怕货值没超门槛）；</li>
 *   <li><b>超过门槛 ⇒ 一般 VAT</b>；</li>
 *   <li><b>统计按分档 × 币种分组</b>，EUR + USD 不相加；</li>
 *   <li><b>上游读不到 ⇒ 整体失败、一条都不写</b>；</li>
 *   <li>登记号与生效时刻<b>不可回改</b>（服务层 + DB 触发器各拒一次）；</li>
 *   <li>已关闭的登记<b>内容冻结</b>（DB 拒）；</li>
 *   <li>辖区码<b>自由文本被 DB 拒</b>；</li>
 *   <li>申报期必须落在已配置的申报周期里；</li>
 *   <li>申报期<b>同一区间不许开两次</b>；</li>
 *   <li>申报状态<b>只能前进</b>（DRAFT → DRAFTED → CONFIRMED），回退被拒；</li>
 *   <li><b>timestamptz 往返断言写回的值</b>；</li>
 *   <li>订单清单<b>真分页真筛选</b>；</li>
 *   <li>辖区码表<b>只读</b>（本模块不写上游的表）。</li>
 * </ol>
 *
 * <p>🔴 上游（trade / finance / catalog）用<b>本进程内的 HttpServer</b>扮演 ——
 * 它们是**跨服务**的，测的时候只能经它们**真实的 HTTP 形状**说话。
 */
@SpringBootTest
@AutoConfigureMockMvc
class VatIossIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer trade;
  static HttpServer finance;
  static HttpServer catalog;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");

  /** 🔴 上游行为开关（每个用例自己摆好，避免用例之间互相影响）。 */
  static final List<Map<String, Object>> PLATFORM_ORDERS = new java.util.concurrent.CopyOnWriteArrayList<>();
  static final Map<String, Map<String, Object>> SALES_ORDERS = new ConcurrentHashMap<>();
  static volatile boolean TRADE_DOWN = false;
  static volatile boolean FINANCE_DOWN = false;
  static volatile boolean CATALOG_DOWN = false;
  static volatile String FINANCE_RATE = "0.190000";
  static volatile String CATALOG_RATE = "0.900000";

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

      // ---- trade 上游：平台单列表 + 统一销售订单详情 ----
      trade = HttpServer.create(new InetSocketAddress(0), 0);
      trade.createContext(
          "/trade/platform-orders/orders",
          e -> {
            if (TRADE_DOWN) {
              e.sendResponseHeaders(503, -1);
              e.close();
              return;
            }
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("rows", PLATFORM_ORDERS);
            page.put("page", 0);
            page.put("size", 200);
            page.put("total", PLATFORM_ORDERS.size());
            writeJson(e, 200, page);
          });
      trade.createContext(
          "/trade/sales-orders",
          e -> {
            if (TRADE_DOWN) {
              e.sendResponseHeaders(503, -1);
              e.close();
              return;
            }
            String path = e.getRequestURI().getPath();
            String id = path.substring(path.lastIndexOf('/') + 1);
            Map<String, Object> detail = SALES_ORDERS.get(id);
            if (detail == null) {
              e.sendResponseHeaders(404, -1);
              e.close();
              return;
            }
            writeJson(e, 200, detail);
          });
      trade.start();

      // ---- finance 上游：税率解析 ----
      finance = HttpServer.create(new InetSocketAddress(0), 0);
      finance.createContext(
          "/finance/tax-rates/resolve",
          e -> {
            if (FINANCE_DOWN || FINANCE_RATE == null) {
              e.sendResponseHeaders(404, -1);
              e.close();
              return;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("resolvedBy", "JURISDICTION");
            body.put("taxType", "VAT");
            body.put("jurisdictionCode", query(e, "jurisdictionCode"));
            body.put("jurisdictionName", "德国");
            body.put("taxCode", "DE-VAT");
            body.put("rate", new java.math.BigDecimal(FINANCE_RATE));
            body.put("asOf", "2026-10-01T00:00:00Z");
            writeJson(e, 200, body);
          });
      finance.start();

      // ---- catalog 上游：汇率 ----
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/exchange-rates/latest",
          e -> {
            if (CATALOG_DOWN || CATALOG_RATE == null) {
              e.sendResponseHeaders(404, -1);
              e.close();
              return;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("baseCurrency", query(e, "baseCurrency"));
            body.put("quoteCurrency", query(e, "quoteCurrency"));
            body.put("rate", new java.math.BigDecimal(CATALOG_RATE));
            body.put("effectiveDate", "2026-10-01");
            writeJson(e, 200, body);
          });
      catalog.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static void writeJson(com.sun.net.httpserver.HttpExchange e, int status, Object body)
      throws IOException {
    byte[] bytes = JSON.writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = e.getResponseBody()) {
      out.write(bytes);
    }
  }

  static String query(com.sun.net.httpserver.HttpExchange e, String name) {
    String raw = e.getRequestURI().getRawQuery();
    if (raw == null) {
      return "";
    }
    for (String pair : raw.split("&")) {
      int at = pair.indexOf('=');
      if (at > 0 && pair.substring(0, at).equals(name)) {
        return java.net.URLDecoder.decode(pair.substring(at + 1), StandardCharsets.UTF_8);
      }
    }
    return "";
  }

  @BeforeEach
  void freshTenant() {
    tenant = UUID.randomUUID();
    bearer = "Bearer t-" + tenant;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearer, actor(user, tenant, grants));
    who = actors.get(bearer);
    PLATFORM_ORDERS.clear();
    SALES_ORDERS.clear();
    TRADE_DOWN = false;
    FINANCE_DOWN = false;
    CATALOG_DOWN = false;
    FINANCE_RATE = "0.190000";
    CATALOG_RATE = "0.900000";
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
    r.add("FINANCE_URL", () -> "http://localhost:" + finance.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://localhost:" + trade.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://localhost:" + catalog.getAddress().getPort());
  }

  @Autowired VatIossService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(String permission, Supplier<T> work) {
    return access.run(who, permission, work);
  }

  private void exec(String dbUser, String dbPassword, UUID tenantId, String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), dbUser, dbPassword);
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

  void execAsTenant(UUID tenantId, String statement) {
    exec(pg.getUsername(), pg.getPassword(), tenantId, statement);
  }

  void execAsApp(UUID tenantId, String statement) {
    exec("channel_app", "test-only", tenantId, statement);
  }

  long countAsApp(UUID tenantId, String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenantId + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      try (var rs = s.executeQuery(statement)) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
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

  /** 站点 ID（V1 用 md5 派生，这里直接查，不写死）。 */
  UUID marketplace(String code) {
    String raw =
        scalar("select id from marketplace where platform_id='" + AMAZON + "' and code='" + code + "'");
    assertThat(raw).as("站点 " + code + " 必须存在于 V1 的站点目录里").isNotNull();
    return UUID.fromString(raw);
  }

  /** 造一家店，并把它挂到指定的站点上（= 它在那些国家卖）。 */
  UUID seedShop(String code, String marketplaceCode) {
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
    if (marketplaceCode != null) {
      execAsTenant(
          tenant,
          "insert into shop_marketplace (tenant_id,shop_id,marketplace_id,status) values ('"
              + tenant
              + "','"
              + id
              + "','"
              + marketplace(marketplaceCode)
              + "','ACTIVE')");
    }
    return id;
  }

  /** 登记一条 IOSS 登记（默认：EUR 150 门槛 + 指定适用店铺）。 */
  VatIossRegistrationView register(String registrationNo, List<VatIossShopScope> shops) {
    return as(
        "channel.operation.create",
        () ->
            service.create(
                new VatIossRegistrationInput(
                    registrationNo,
                    "ACTIVE",
                    "2026-01-01T00:00:00Z",
                    null,
                    "",
                    "150.000000",
                    "EUR",
                    shops,
                    List.of(),
                    "集成测试")));
  }

  // ==================================================================
  // 1. IOSS 登记
  // ==================================================================

  @Test
  @DisplayName("16.3.2.1 登记真的落库（含适用店铺与适用商品），且读回来一致")
  void registrationRoundTrips() {
    VatIossRegistrationView view =
        register(
            "IM250001234",
            List.of(new VatIossShopScope("AMAZON", "SHOP-DE")));
    assertThat(view.registrationNo()).isEqualTo("IM250001234");
    assertThat(view.current()).isTrue();
    assertThat(view.thresholdAmount()).isEqualTo("150.000000");
    assertThat(view.thresholdCurrency()).isEqualTo("EUR");
    assertThat(view.shopScopes()).containsExactly(new VatIossShopScope("AMAZON", "SHOP-DE"));

    // 库里真的有一行（不是只在内存里）
    assertThat(countAsApp(tenant, "select count(*) from vat_ioss_registration")).isEqualTo(1);
    assertThat(countAsApp(tenant, "select count(*) from vat_ioss_registration_shop")).isEqualTo(1);
  }

  @Test
  @DisplayName("16.3.2.1 🔴 timestamptz 往返断言写回的值（不是只断言没抛异常）")
  void timestamptzRoundTrip() {
    OffsetDateTime from = OffsetDateTime.parse("2026-03-15T10:30:00Z");
    VatIossRegistrationView view =
        as(
            "channel.operation.create",
            () ->
                service.create(
                    new VatIossRegistrationInput(
                        "IM250009999",
                        "ACTIVE",
                        from.toString(),
                        null,
                        "",
                        "150.000000",
                        "EUR",
                        List.of(),
                        List.of(),
                        "")));
    // 🔴 断言的是**时刻**（instant），不是带偏移量的字符串 ——
    //    DB 读回来带的是 JVM 本地偏移量（+08:00），同一个瞬间在 Z 与 +08:00 下
    //    文本不同但时刻相同；断言时刻才能抓住 −28800s 那种静默偏移。
    assertThat(OffsetDateTime.parse(view.effectiveFrom()).toInstant()).isEqualTo(from.toInstant());
    // 数据库里那一列也是同一个时刻（epoch 秒，与会话时区无关）
    assertThat(
            Long.parseLong(
                scalar(
                    "select extract(epoch from effective_from)::bigint from vat_ioss_registration"
                        + " where registration_no='IM250009999'")))
        .isEqualTo(from.toEpochSecond());
  }

  @Test
  @DisplayName("16.3.2.1 已有生效登记时再登记 ⇒ 409（一个租户一个 IOSS 号）")
  void secondRegistrationConflicts() {
    register("IM250001234", List.of(new VatIossShopScope("AMAZON", "SHOP-DE")));
    assertThatThrownBy(
            () ->
                as(
                    "channel.operation.create",
                    () ->
                        service.create(
                            new VatIossRegistrationInput(
                                "IM250005555",
                                "ACTIVE",
                                null,
                                null,
                                "",
                                "150.000000",
                                "EUR",
                                List.of(),
                                List.of(),
                                ""))))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("已有一条生效中的 IOSS 登记");
  }

  @Test
  @DisplayName("16.3.2.1 🔴 登记号与生效时刻不可回改（服务层 + DB 触发器各拒一次）")
  void registrationIdentityIsImmutable() {
    VatIossRegistrationView view = register("IM250001234", List.of());
    // 服务层先拒
    assertThatThrownBy(
            () ->
                as(
                    "channel.operation.update",
                    () ->
                        service.update(
                            view.id(),
                            new VatIossRegistrationInput(
                                "IM250007777",
                                "ACTIVE",
                                "2026-01-01T00:00:00Z",
                                null,
                                "",
                                "150.000000",
                                "EUR",
                                List.of(),
                                List.of(),
                                ""))))
        .hasMessageContaining("登记号不可改");
    // DB 层兜底（绕过服务层直接改 ⇒ 触发器拒）
    assertThatThrownBy(
            () ->
                execAsApp(
                    tenant,
                    "update vat_ioss_registration set registration_no = 'IM250007777' where id = '"
                        + view.id()
                        + "'"))
        .hasMessageContaining("registration_no is immutable");
  }

  @Test
  @DisplayName("16.3.2.1 🔴 已关闭的登记内容冻结（DB 拒）")
  void closedRegistrationIsFrozen() {
    VatIossRegistrationView view = register("IM250001234", List.of());
    as(
        "channel.operation.update",
        () ->
            service.update(
                view.id(),
                new VatIossRegistrationInput(
                    "IM250001234",
                    "ACTIVE",
                    "2026-01-01T00:00:00Z",
                    "2026-06-30T00:00:00Z",
                    "",
                    "150.000000",
                    "EUR",
                    List.of(),
                    List.of(),
                    "")));
    assertThatThrownBy(
            () ->
                execAsApp(
                    tenant,
                    "update vat_ioss_registration set note = '偷偷改' where id = '"
                        + view.id()
                        + "'"))
        .hasMessageContaining("is closed");
    // 关闭之后可以再登记一条
    VatIossRegistrationView next = register("IM250005555", List.of());
    assertThat(next.current()).isTrue();
  }

  // ==================================================================
  // 2. 申报周期与申报期
  // ==================================================================

  @Test
  @DisplayName("16.3.2.1 🔴 辖区码自由文本被 DB 拒（值域钉死在 16.3.1.1 的码表里）")
  void freeTextJurisdictionIsRejected() {
    assertThatThrownBy(
            () ->
                execAsApp(
                    tenant,
                    "insert into vat_ioss_filing_cycle(id,tenant_id,jurisdiction_code,filing_cycle,"
                        + " filing_start_month,deadline_day,status) values('"
                        + UUID.randomUUID()
                        + "','"
                        + tenant
                        + "','EU-XX','MONTHLY',1,20,'ACTIVE')"))
        .hasMessageContaining("is not a known jurisdiction code");
  }

  @Test
  @DisplayName("16.3.2.1 申报周期配置：辖区 + 周期唯一，重复 ⇒ 409")
  void filingCycleIsUnique() {
    as(
        "channel.operation.create",
        () ->
            service.createFilingCycle(
                new VatIossFilingCycleInput("EU-DE", "MONTHLY", 1, 20, "ACTIVE", "")));
    assertThatThrownBy(
            () ->
                as(
                    "channel.operation.create",
                    () ->
                        service.createFilingCycle(
                            new VatIossFilingCycleInput("EU-DE", "MONTHLY", 1, 20, "ACTIVE", ""))))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("已经有一条");
  }

  @Test
  @DisplayName("16.3.2.1 申报期必须落在已配置的申报周期里（区间对不上 ⇒ 422）")
  void filingPeriodMustMatchCycle() {
    as(
        "channel.operation.create",
        () ->
            service.createFilingCycle(
                new VatIossFilingCycleInput("EU-DE", "MONTHLY", 1, 20, "ACTIVE", "")));
    assertThatThrownBy(
            () ->
                as(
                    "channel.operation.create",
                    () ->
                        service.createFilingPeriod(
                            new VatIossFilingPeriodInput(
                                "EU-DE", "2026-03-01", "2026-06-30", ""))))
        .hasMessageContaining("申报周期对不上");
    // 月度周期 + 一个月区间 ⇒ 通过
    VatIossFilingPeriodView period =
        as(
            "channel.operation.create",
            () ->
                service.createFilingPeriod(
                    new VatIossFilingPeriodInput("EU-DE", "2026-03-01", "2026-03-31", "")));
    assertThat(period.status()).isEqualTo("DRAFT");
    // 同一区间不许开两次
    assertThatThrownBy(
            () ->
                as(
                    "channel.operation.create",
                    () ->
                        service.createFilingPeriod(
                            new VatIossFilingPeriodInput(
                                "EU-DE", "2026-03-01", "2026-03-31", ""))))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("已经开过一期");
  }

  @Test
  @DisplayName("16.3.2.1 🔴 申报状态只能前进：DRAFT → DRAFTED → CONFIRMED，回退被拒")
  void filingStatusMovesForwardOnly() {
    as(
        "channel.operation.create",
        () ->
            service.createFilingCycle(
                new VatIossFilingCycleInput("EU-DE", "MONTHLY", 1, 20, "ACTIVE", "")));
    VatIossFilingPeriodView period =
        as(
            "channel.operation.create",
            () ->
                service.createFilingPeriod(
                    new VatIossFilingPeriodInput("EU-DE", "2026-03-01", "2026-03-31", "")));

    // 不能跳过「出底稿」直接确认
    assertThatThrownBy(() -> as("channel.operation.update", () -> service.confirmFilingPeriod(period.id())))
        .hasMessageContaining("只有「已出底稿」的申报期能确认");

    VatIossFilingPeriodView drafted =
        as("channel.operation.update", () -> service.draftFilingPeriod(period.id()));
    assertThat(drafted.status()).isEqualTo("DRAFTED");
    assertThat(drafted.draftedAt()).isNotBlank();

    // 已出底稿之后不能再出一次底稿（那就是回退）
    assertThatThrownBy(() -> as("channel.operation.update", () -> service.draftFilingPeriod(period.id())))
        .hasMessageContaining("只有「待提交」的申报期能出底稿");

    VatIossFilingPeriodView confirmed =
        as("channel.operation.update", () -> service.confirmFilingPeriod(period.id()));
    assertThat(confirmed.status()).isEqualTo("CONFIRMED");
    assertThat(confirmed.confirmedAt()).isNotBlank();
    assertThat(confirmed.confirmedBy()).isEqualTo(user.toString());

    // DB 层兜底：直接回退状态被触发器拒
    assertThatThrownBy(
            () ->
                execAsApp(
                    tenant,
                    "update vat_filing_period set status = 'DRAFT' where id = '"
                        + period.id()
                        + "'"))
        .hasMessageContaining("cannot move backwards");
  }

  // ==================================================================
  // 3. IOSS 订单识别（真实上游订单）
  // ==================================================================

  /** 造一笔上游平台单 + 它的统一销售订单详情。 */
  void seedUpstreamOrder(
      String orderNo,
      String shopCode,
      String currency,
      String country,
      String customerType,
      String goodsAmount,
      String shippingAmount,
      String taxAmount,
      String skuCode,
      String normalizedStatus) {
    UUID platformOrderId = UUID.randomUUID();
    UUID salesOrderId = UUID.randomUUID();
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", platformOrderId.toString());
    row.put("salesOrderId", salesOrderId.toString());
    row.put("orderNo", orderNo);
    row.put("platformCode", "AMAZON");
    row.put("shopCode", shopCode);
    row.put("externalOrderNo", "EXT-" + orderNo);
    row.put("orderSource", "AMAZON");
    row.put("normalizedStatus", normalizedStatus);
    row.put("currency", currency);
    row.put("platformOriginalAmount", new java.math.BigDecimal(goodsAmount));
    row.put("platformDiscountAmount", java.math.BigDecimal.ZERO);
    row.put("platformTaxAmount", new java.math.BigDecimal(taxAmount));
    row.put("platformShippingAmount", new java.math.BigDecimal(shippingAmount));
    row.put("platformGrandTotalAmount", new java.math.BigDecimal(goodsAmount));
    row.put("platformPlacedAt", "2026-10-05T09:00:00Z");
    PLATFORM_ORDERS.add(row);

    Map<String, Object> line = new LinkedHashMap<>();
    line.put("lineNo", 1);
    line.put("skuCode", skuCode);
    line.put("skuName", "测试商品 " + skuCode);
    line.put("quantity", new java.math.BigDecimal("2"));
    line.put("unitPrice", new java.math.BigDecimal(goodsAmount));
    line.put("discountAmount", java.math.BigDecimal.ZERO);
    line.put("taxAmount", new java.math.BigDecimal(taxAmount));
    line.put("lineAmount", new java.math.BigDecimal(goodsAmount));

    Map<String, Object> address = new LinkedHashMap<>();
    address.put("id", UUID.randomUUID().toString());
    address.put("addressRole", "SHIPPING");
    address.put("countryCode", country);

    Map<String, Object> customer = new LinkedHashMap<>();
    customer.put("customerType", customerType);

    Map<String, Object> order = new LinkedHashMap<>();
    order.put("id", salesOrderId.toString());
    order.put("orderNo", orderNo);
    order.put("currency", currency);

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("order", order);
    detail.put("customer", customer);
    detail.put("lines", List.of(line));
    detail.put("addresses", country.isEmpty() ? List.of() : List.of(address));
    detail.put("charges", List.of());
    detail.put("events", List.of());
    SALES_ORDERS.put(salesOrderId.toString(), detail);
  }

  @Test
  @DisplayName("16.3.2.1 🔴 IOSS 与一般 VAT **真的分档落进数据**，且统计按分档 × 币种分组")
  void ordersAreClassifiedAndGrouped() {
    seedShop("SHOP-DE", "DE");
    register(
        "IM250001234",
        List.of(new VatIossShopScope("AMAZON", "SHOP-DE"), new VatIossShopScope("AMAZON", "SHOP-FR")));

    // ① 欧盟 B2C、未超门槛 ⇒ IOSS（EUR）
    seedUpstreamOrder("SO-1", "SHOP-DE", "EUR", "DE", "INDIVIDUAL", "100.00", "10.00", "19.00", "SKU-1", "COMPLETED");
    // ② 欧盟 B2C、超门槛 ⇒ 一般 VAT（EUR）
    seedUpstreamOrder("SO-2", "SHOP-DE", "EUR", "DE", "INDIVIDUAL", "200.00", "10.00", "42.00", "SKU-2", "COMPLETED");
    // ③ 欧盟 B2C、未超门槛、但币种是 USD（要折算门槛）
    seedUpstreamOrder("SO-3", "SHOP-DE", "USD", "DE", "INDIVIDUAL", "100.00", "0.00", "0.00", "SKU-3", "COMPLETED");
    // ④ 收货地不在欧盟 ⇒ 不在范围
    seedUpstreamOrder("SO-4", "SHOP-DE", "EUR", "US", "INDIVIDUAL", "100.00", "0.00", "0.00", "SKU-4", "COMPLETED");
    // ⑤ 没有收货国家 ⇒ 判不了
    seedUpstreamOrder("SO-5", "SHOP-DE", "EUR", "", "INDIVIDUAL", "100.00", "0.00", "0.00", "SKU-5", "COMPLETED");

    VatIossOrderSyncResult result = as("channel.operation.create", () -> service.syncOrders(null, bearer));
    assertThat(result.scanned()).isEqualTo(5);
    assertThat(result.iossCount()).isEqualTo(2); // ① + ③（USD 100 × 0.9 = 90 EUR < 150）
    assertThat(result.generalVatCount()).isEqualTo(1);
    assertThat(result.outOfScopeCount()).isEqualTo(1);
    assertThat(result.unresolvedCount()).isEqualTo(1);

    // 🔴 分档是真的**列**，不是页面上的一列颜色
    assertThat(countAsApp(tenant, "select count(*) from vat_ioss_order where filing_scope='IOSS'"))
        .isEqualTo(2);
    assertThat(countAsApp(tenant, "select count(*) from vat_ioss_order where filing_scope='GENERAL_VAT'"))
        .isEqualTo(1);
    assertThat(countAsApp(tenant, "select count(*) from vat_ioss_order where filing_scope='OUT_OF_SCOPE'"))
        .isEqualTo(1);
    assertThat(countAsApp(tenant, "select count(*) from vat_ioss_order where filing_scope='UNRESOLVED'"))
        .isEqualTo(1);

    // 🔴 统计按分档 × 币种分组 —— EUR 与 USD **绝不加在一起**
    VatIossStatistics stats = as("channel.operation.read", () -> service.statistics(null, null, null, null, null, null));
    assertThat(stats.rows())
        .extracting(VatIossStatisticsRow::filingScope, VatIossStatisticsRow::currency)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("IOSS", "EUR"),
            org.assertj.core.groups.Tuple.tuple("IOSS", "USD"),
            org.assertj.core.groups.Tuple.tuple("GENERAL_VAT", "EUR"),
            org.assertj.core.groups.Tuple.tuple("OUT_OF_SCOPE", "EUR"),
            org.assertj.core.groups.Tuple.tuple("UNRESOLVED", "EUR"));
    // EUR 的 IOSS 那组只含 ① —— 100 + 10 = 110，不含 USD 的 100
    VatIossStatisticsRow iossEur =
        stats.rows().stream()
            .filter(r -> r.filingScope().equals("IOSS") && r.currency().equals("EUR"))
            .findFirst()
            .orElseThrow();
    assertThat(iossEur.orderCount()).isEqualTo(1);
    assertThat(iossEur.taxBase()).isEqualTo("110.000000");
    assertThat(iossEur.taxAmount()).isEqualTo("19.000000");

    // 🔴 申报底稿读不到 ⇒ 如实说读不到（12.6.1.2 未交付）
    assertThat(stats.filingDraftAvailable()).isFalse();
    assertThat(stats.filingDraftOwnerModule()).isEqualTo("12.6.1.2");
    assertThat(stats.filingDraftImpact()).contains("读不到申报底稿");
  }

  @Test
  @DisplayName("16.3.2.1 🔴 税率读不到 ⇒ 税率为 null 且 source=UNAVAILABLE（**绝不是 0%**）")
  void taxRateUnavailableIsNotZero() {
    seedShop("SHOP-DE", "DE");
    register("IM250001234", List.of(new VatIossShopScope("AMAZON", "SHOP-DE")));
    seedUpstreamOrder("SO-1", "SHOP-DE", "EUR", "DE", "INDIVIDUAL", "100.00", "0.00", "19.00", "SKU-1", "COMPLETED");

    FINANCE_DOWN = true;
    as("channel.operation.create", () -> service.syncOrders(null, bearer));

    assertThat(
            countAsApp(
                tenant,
                "select count(*) from vat_ioss_order where tax_rate is null and tax_rate_source='UNAVAILABLE'"))
        .isEqualTo(1);
    // 库里绝对不能出现 0% 的税率（0% 看起来像免税）
    assertThat(countAsApp(tenant, "select count(*) from vat_ioss_order where tax_rate = 0")).isZero();
  }

  @Test
  @DisplayName("16.3.2.1 🔴 汇率读不到 ⇒ 该单判成「判不了」（不许拿猜的汇率归档进 IOSS）")
  void exchangeRateUnavailableMakesOrderUnresolved() {
    seedShop("SHOP-DE", "DE");
    register("IM250001234", List.of(new VatIossShopScope("AMAZON", "SHOP-DE")));
    seedUpstreamOrder("SO-1", "SHOP-DE", "USD", "DE", "INDIVIDUAL", "100.00", "0.00", "0.00", "SKU-1", "COMPLETED");

    CATALOG_DOWN = true;
    as("channel.operation.create", () -> service.syncOrders(null, bearer));

    assertThat(countAsApp(tenant, "select count(*) from vat_ioss_order where filing_scope='UNRESOLVED'"))
        .isEqualTo(1);
    assertThat(
            countAsApp(
                tenant, "select count(*) from vat_ioss_order where reason_code='THRESHOLD_UNRESOLVED'"))
        .isEqualTo(1);
    assertThat(countAsApp(tenant, "select count(*) from vat_ioss_order where filing_scope='IOSS'"))
        .isZero();
  }

  @Test
  @DisplayName("16.3.2.1 🔴 应税消费品 ⇒ 一般 VAT（哪怕没超门槛）")
  void exciseGoodsFallOutOfIoss() {
    seedShop("SHOP-DE", "DE");
    as(
        "channel.operation.create",
        () ->
            service.create(
                new VatIossRegistrationInput(
                    "IM250001234",
                    "ACTIVE",
                    "2026-01-01T00:00:00Z",
                    null,
                    "",
                    "150.000000",
                    "EUR",
                    List.of(new VatIossShopScope("AMAZON", "SHOP-DE")),
                    List.of(new VatIossGoodsScope("SKU-WINE", "红酒", true)),
                    "")));
    seedUpstreamOrder("SO-1", "SHOP-DE", "EUR", "DE", "INDIVIDUAL", "100.00", "0.00", "19.00", "SKU-WINE", "COMPLETED");

    as("channel.operation.create", () -> service.syncOrders(null, bearer));

    assertThat(countAsApp(tenant, "select count(*) from vat_ioss_order where filing_scope='GENERAL_VAT'"))
        .isEqualTo(1);
    assertThat(
            countAsApp(tenant, "select count(*) from vat_ioss_order where reason_code='EXCISE_GOODS'"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("16.3.2.1 🔴 上游读不到 ⇒ 整体失败、一条都不写")
  void upstreamFailureWritesNothing() {
    seedShop("SHOP-DE", "DE");
    register("IM250001234", List.of(new VatIossShopScope("AMAZON", "SHOP-DE")));
    seedUpstreamOrder("SO-1", "SHOP-DE", "EUR", "DE", "INDIVIDUAL", "100.00", "0.00", "19.00", "SKU-1", "COMPLETED");

    TRADE_DOWN = true;
    assertThatThrownBy(() -> as("channel.operation.create", () -> service.syncOrders(null, bearer)))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("trade 服务");
    assertThat(countAsApp(tenant, "select count(*) from vat_ioss_order")).isZero();
  }

  @Test
  @DisplayName("16.3.2.1 订单清单真分页真筛选（按分档筛）")
  void ordersArePagedAndFiltered() {
    seedShop("SHOP-DE", "DE");
    register("IM250001234", List.of(new VatIossShopScope("AMAZON", "SHOP-DE")));
    seedUpstreamOrder("SO-1", "SHOP-DE", "EUR", "DE", "INDIVIDUAL", "100.00", "0.00", "19.00", "SKU-1", "COMPLETED");
    seedUpstreamOrder("SO-2", "SHOP-DE", "EUR", "DE", "INDIVIDUAL", "300.00", "0.00", "57.00", "SKU-2", "COMPLETED");
    as("channel.operation.create", () -> service.syncOrders(null, bearer));

    VatIossOrderPage all = as("channel.operation.read", () -> service.orders(null, null, null, null, null, null, null, null, 0, 50));
    assertThat(all.total()).isEqualTo(2);

    VatIossOrderPage iossOnly =
        as("channel.operation.read", () -> service.orders("IOSS", null, null, null, null, null, null, null, 0, 50));
    assertThat(iossOnly.items()).hasSize(1);
    assertThat(iossOnly.items().get(0).sourceOrderNo()).isEqualTo("SO-1");
    assertThat(iossOnly.iossCount()).isEqualTo(1);
    assertThat(iossOnly.generalVatCount()).isEqualTo(1);

    VatIossOrderPage paged =
        as("channel.operation.read", () -> service.orders(null, null, null, null, null, null, null, null, 0, 1));
    assertThat(paged.items()).hasSize(1);
    assertThat(paged.total()).isEqualTo(2);
  }

  // ==================================================================
  // 4. 辖区码表只读 + HTTP 层
  // ==================================================================

  @Test
  @DisplayName("16.3.2.1 🔴 本模块不写上游的辖区码表（16.3.1.1 的写所有权不变）")
  void jurisdictionCodeTableIsReadOnly() {
    long before =
        Long.parseLong(scalar("select count(*) from tax_registration_jurisdiction"));
    assertThat(before).as("辖区码表应当是已交付的参考数据").isPositive();
    // 本模块的写操作只会碰自己的表
    seedShop("SHOP-DE", "DE");
    register("IM250001234", List.of());
    assertThat(
            Long.parseLong(scalar("select count(*) from tax_registration_jurisdiction")))
        .isEqualTo(before);
  }

  @Test
  @DisplayName("16.3.2.1 HTTP 层：能力声明把「读不到申报底稿」写清楚")
  void capabilitiesReportTheFilingDraftGap() throws Exception {
    http.perform(get("/channel/vat-ioss/capabilities").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.gaps[?(@.moduleId == '12.6.1.2')].available").value(false))
        .andExpect(jsonPath("$.filingScopes").isArray())
        .andExpect(jsonPath("$.upstreams[?(@.moduleId == '12.6.1.1')].available").value(true));
  }

  @Test
  @DisplayName("16.3.2.1 HTTP 层：统计端点返回按分档 × 币种分组的行")
  void statisticsEndpointWorks() throws Exception {
    http.perform(get("/channel/vat-ioss/statistics").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.filingDraftAvailable").value(false))
        .andExpect(jsonPath("$.rows").isArray());
  }
}
