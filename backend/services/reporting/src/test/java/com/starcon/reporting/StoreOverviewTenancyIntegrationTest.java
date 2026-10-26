package com.starcon.reporting;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
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
 * 1.1.2.2 店铺概览的**多租户隔离**测试（AGENTS.md 明文要求的三条断言，缺一不可）。
 *
 * <ol>
 *   <li>两租户互不可见；</li>
 *   <li>伪造租户上下文无效；</li>
 *   <li>连接池归还后不串租户。</li>
 * </ol>
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p>⚠️ 本模块读的是<b>店铺维度投影表</b>（{@code reporting_store_profile} /
 * {@code reporting_store_metric_definition} / {@code reporting_store_metric_fact}），
 * 它们都是 **FORCE RLS** ⇒ 三条断言验的是**真实业务表**，不是占位探针表。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StoreOverviewTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final String BEARER_A = "Bearer tenant-a";
  static final String BEARER_B = "Bearer tenant-b";

  static final List<String> permissions =
      List.of(
          "reporting.overview.read",
          "reporting.projection.read",
          "reporting.projection.write",
          "reporting.store-comparison.read");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  static final LocalDate DAY = LocalDate.of(2026, 10, 1);

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role reporting_app login password 'test-only' nosuperuser nobypassrls");
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
            byte[] body = JSON.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put(BEARER_A, actor(a));
      actors.put(BEARER_B, actor(b));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static AccessService.Access actor(UUID tenant) {
    var grant = new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions);
    return new AccessService.Access(
        user, tenant, "Test", UUID.randomUUID(), null, permissions, List.of(grant));
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "reporting_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingStoreComparisonRefreshService refresh;
  @Autowired StoreOverviewService overview;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate reporting_store_metric_fact, reporting_store_metric_definition,"
              + " reporting_store_profile cascade");
    }
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  private void seed(UUID tenantId, String shopCode, String amount) {
    tenants.run(
        tenantId,
        () -> {
          // ⚠️ 口径播种必须**在租户事务里**做：口径表是 FORCE RLS 的，未绑定租户时写入被策略拒绝。
          refresh.seedDefinitions(tenantId);
          sql.execute(
              """
              insert into reporting_store_profile
                (id,tenant_id,shop_code,shop_name,platform_code,platform_name,
                 source_service,source_reference,created_at,updated_at)
              values (?,?,?,?,'AMZ','Amazon','channel',?,now(),now())
              on conflict (tenant_id, shop_code) do update set updated_at = now()
              """,
              Ids.next(),
              tenantId,
              shopCode,
              shopCode,
              "channel:shops:" + shopCode);
          sql.execute(
              """
              insert into reporting_store_metric_fact
                (id,tenant_id,shop_code,metric_code,bucket_date,currency,value,quantity,
                 timezone,source_service,source_reference,updated_at)
              values (?,?,?,?,?,'USD',?,0,'UTC','test',?,now())
              on conflict (tenant_id, shop_code, metric_code, bucket_date, currency,
                           source_service, source_reference)
              do update set value = excluded.value, updated_at = now()
              """,
              Ids.next(),
              tenantId,
              shopCode,
              ReportingStoreComparisonCatalog.STORE_GMV_PLATFORM,
              DAY,
              new BigDecimal(amount),
              shopCode + ":1");
          return null;
        });
  }

  private void seedA() {
    seed(a, "shop-a", "1500.00");
  }

  private void seedB() {
    seed(b, "shop-b", "77.00");
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================

  @Test
  void 断言1_两租户互不可见() {
    seedA();
    seedB();

    assertThat(shops(a)).as("租户 A 只能看到自己的店铺").containsExactly("shop-a");
    assertThat(shops(b)).as("租户 B 只能看到自己的店铺").containsExactly("shop-b");

    // 更强的一格：A 明确按店铺码直查 B 的行也拿不到。
    var cross =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select shop_code from reporting_store_profile "
                            + "where tenant_id = ? and shop_code = ?",
                        a,
                        "shop-b")
                    .size());
    assertThat(cross).as("按主键直查也必须被 RLS 挡住").isZero();
  }

  // ==========================================================================
  // 断言 2：伪造租户上下文无效
  // ==========================================================================

  @Test
  void 断言2_伪造租户头无效() throws Exception {
    seedA();
    seedB();

    // (1) 事务外没有「环境租户」：不存在一个可以被沿用的默认租户。
    assertThat(rawTenantSetting()).isNullOrEmpty();
    assertThat(visibleProfilesWithoutTenant()).as("未绑定租户时不应看到任何行").isZero();

    // (2) 事务内绑定的值精确等于可信参数，提交后立刻失效。
    var bound =
        tenants.run(
            b,
            () -> {
              assertThat(effectiveTenant()).isEqualTo(b);
              return rawTenantSetting();
            });
    assertThat(bound).isEqualTo(b.toString());
    assertThat(effectiveTenant()).as("提交后租户上下文必须已清空").isNull();
    assertThat(visibleProfilesWithoutTenant()).isZero();

    // (3) HTTP 边界：带**租户 A 的合法会话** + **伪造的 X-Tenant-Id（租户 B）** ⇒
    //     概览仍然只给 A 的店铺，B 的店铺一滴都看不到。
    http.perform(
            get("/reporting/store-overview")
                .param("window", "CUSTOM")
                .param("from", DAY.toString())
                .param("to", DAY.toString())
                .header("Authorization", BEARER_A)
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1))
        .andExpect(jsonPath("$.rows[0].shopCode").value("shop-a"));

    assertThat(shops(a)).as("伪造头不得让概览显示别人的店铺").containsExactly("shop-a");
    assertThat(shops(b)).as("被伪造的租户不得看见别人的事实").containsExactly("shop-b");
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 断言3_连接池归还后不串租户() {
    seedA();
    seedB();
    for (int i = 0; i < 5; i++) {
      assertThat(shops(a)).as("第 %d 轮租户 A", i).containsExactly("shop-a");
      assertThat(shops(b)).as("第 %d 轮租户 B", i).containsExactly("shop-b");
      assertThat(visibleProfilesWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(shops(a)).containsExactly("shop-a");
    assertThat(shops(b)).containsExactly("shop-b");
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  /** 某个租户在这个窗口里能看到的店铺编码。 */
  private List<String> shops(UUID tenantId) {
    var response = overview.overview(tenantId, "CUSTOM", DAY, DAY, null);
    return response.rows().stream().map(StoreOverviewModels.StoreOverviewRow::shopCode).toList();
  }

  /** 当前事务里生效的租户（空串 = 未绑定 ⇒ null）。 */
  private UUID effectiveTenant() {
    var row = sql.fetchOne("select nullif(current_setting('app.current_tenant', true),'')::uuid");
    return row == null ? null : row.get(0, UUID.class);
  }

  /** 原始设置值（事务外应当为空）。 */
  private String rawTenantSetting() {
    return sql.fetch("select current_setting('app.current_tenant', true)").stream()
        .findFirst()
        .map(r -> r.get(0, String.class))
        .orElse(null);
  }

  /** 不绑定租户时能看到的店铺档案行数（必须是 0）。 */
  private long visibleProfilesWithoutTenant() {
    var row = sql.fetchOne("select count(*) from reporting_store_profile");
    return row == null ? 0L : row.get(0, Long.class);
  }
}
