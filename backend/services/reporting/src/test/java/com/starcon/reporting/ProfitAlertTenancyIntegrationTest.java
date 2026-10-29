package com.starcon.reporting;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
 * 13.4.3.1 利润预警的**多租户隔离测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条断言（缺一不可）：
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（预警清单、阈值规则、通知记录三张表都要验）；</li>
 *   <li><b>伪造租户上下文无效</b>（并断言行确实落在**会话租户**名下）；</li>
 *   <li><b>连接池归还后不串租户</b>。</li>
 * </ol>
 *
 * <p>⚠️ <b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProfitAlertTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.profit-alert.read",
          "reporting.profit-alert.write",
          "reporting.projection.read",
          "reporting.projection.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  static final LocalDate DAY = LocalDate.of(2026, 10, 10);

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute(
          "create role reporting_app login password 'test-only' nosuperuser nobypassrls");
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
      actors.put("Bearer tenant-a", actor(a));
      actors.put("Bearer tenant-b", actor(b));
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
  @Autowired ReportingProfitAlertService alerts;

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
          "truncate reporting_profit_alert_notification, reporting_profit_alert_rule,"
              + " reporting_profit_fact, reporting_profit_shop_profile,"
              + " reporting_metric_fact, reporting_drilldown_fact, reporting_projection_event,"
              + " reporting_projection_cursor, reporting_currency_rate_snapshot,"
              + " reporting_metric_definition cascade");
    }
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  /** 一条利润事实（落在**指定租户**名下）。 */
  private void fact(UUID tenant, String sku, String amount) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              """
              insert into reporting_profit_fact
                (id,tenant_id,operating_mode,component,shop_code,sku_code,spu_code,msku_code,order_ref,
                 period_start,period_end,currency,amount,quantity,source_service,source_reference,updated_at)
              values (?,?,'SELF_FULFILLED','REVENUE','SHOP-1',?,'','','ORDER-1',?,?, 'USD', ?,0,'test',?,now())
              """,
              UUID.randomUUID(),
              tenant,
              sku,
              DAY,
              DAY,
              new BigDecimal(amount),
              UUID.randomUUID().toString());
          sql.execute(
              """
              insert into reporting_profit_fact
                (id,tenant_id,operating_mode,component,shop_code,sku_code,spu_code,msku_code,order_ref,
                 period_start,period_end,currency,amount,quantity,source_service,source_reference,updated_at)
              values (?,?,'SELF_FULFILLED','COGS','SHOP-1',?,'','','',?,?, 'USD', ?,0,'test',?,now())
              """,
              UUID.randomUUID(),
              tenant,
              sku,
              DAY,
              DAY,
              new BigDecimal("120"),
              UUID.randomUUID().toString());
          return null;
        });
  }

  /** 某租户在窗口里看到的预警（走完整的读模型）。 */
  private ReportingProfitAlertModels.ProfitAlertPage page(UUID tenant) {
    return alerts.alerts(tenant, DAY, DAY, null, null, null, null, 0, 50);
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================

  @Test
  void 断言1_两租户互不可见() {
    fact(a, "SKU-A", "100");
    fact(b, "SKU-B", "100");

    var pageA = page(a);
    var pageB = page(b);
    assertThat(pageA.alerts()).as("租户 A 只能看到自己的预警").hasSize(1);
    assertThat(pageA.alerts().get(0).scopeKey()).isEqualTo("SKU-A");
    assertThat(pageB.alerts()).hasSize(1);
    assertThat(pageB.alerts().get(0).scopeKey()).isEqualTo("SKU-B");

    // 更强的一格：A 明确按来源行直查 B 的行也拿不到（RLS 挡住）。
    var cross =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select source_reference from reporting_profit_fact "
                            + "where tenant_id = ? and sku_code = ?",
                        b,
                        "SKU-B")
                    .size());
    assertThat(cross).as("按来源行直查也必须被 RLS 挡住").isZero();

    // 阈值规则表也是租户级的：A 改自己的规则改不动 B 的。
    // ⚠️ 阈值取 -10（不是 -30）：SKU-A 的毛利率是 -20%，阈值 -30 时它就不再命中了，
    //   后面「A 有一条通知」那条断言就失去意义。
    alerts.updateRule(
        a,
        "SKU_NEGATIVE_GROSS_PROFIT",
        new ReportingProfitAlertModels.ProfitAlertRuleInput(
            "FIXED", new BigDecimal("-10"), null, null, null));
    var ruleB =
        alerts.rules(b).stream()
            .filter(r -> r.alertType().equals("SKU_NEGATIVE_GROSS_PROFIT"))
            .findFirst()
            .orElseThrow();
    assertThat(ruleB.thresholdValue()).as("B 的阈值不受 A 影响").isEqualByComparingTo("0");
    var ruleA =
        alerts.rules(a).stream()
            .filter(r -> r.alertType().equals("SKU_NEGATIVE_GROSS_PROFIT"))
            .findFirst()
            .orElseThrow();
    assertThat(ruleA.thresholdValue()).as("A 自己的阈值确实改了").isEqualByComparingTo("-10");

    // 通知记录同样隔离。
    alerts.registerNotifications(a, DAY, DAY);
    assertThat(alerts.notifications(a, null, null, 0, 50).total()).isEqualTo(1);
    assertThat(alerts.notifications(b, null, null, 0, 50).total()).as("B 看不到 A 的通知").isZero();
  }

  // ==========================================================================
  // 断言 2：伪造租户上下文无效
  // ==========================================================================

  @Test
  void 断言2_伪造租户头无效() throws Exception {
    fact(a, "SKU-A", "100");
    fact(b, "SKU-B", "100");

    // (1) 事务外没有「环境租户」：不存在一个可以被沿用的默认租户。
    assertThat(rawTenantSetting()).isNullOrEmpty();
    assertThat(visibleFactsWithoutTenant()).as("未绑定租户时不应看到任何行").isZero();

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
    assertThat(visibleFactsWithoutTenant()).isZero();

    // (3) HTTP 边界：带**租户 A 的合法会话** + **伪造的 X-Tenant-Id（租户 B）**
    //     ⇒ 返回的仍然是**会话租户 A** 的数据，B 的预警一格都看不到。
    http.perform(
            get("/reporting/profit-alert/alerts")
                .param("from", DAY.toString())
                .param("to", DAY.toString())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.alerts.length()").value(1))
        .andExpect(jsonPath("$.alerts[0].scopeKey").value("SKU-A"));

    // 反向也成立：会话是 B、伪造头是 A ⇒ 看到的是 B 的预警，不是 A 的。
    http.perform(
            get("/reporting/profit-alert/alerts")
                .param("from", DAY.toString())
                .param("to", DAY.toString())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.alerts.length()").value(1))
        .andExpect(jsonPath("$.alerts[0].scopeKey").value("SKU-B"));

    // (4) 写侧也必须被 RLS 的 WITH CHECK 挡住：绑定 A 时插入 tenant_id = B 的行必须失败。
    assertThatThrownBy(() -> insertRuleAs(a, b)).as("绑定 A 时不许往 B 名下写行").isInstanceOf(RuntimeException.class);
    var landedInB =
        tenants.run(
            b,
            () ->
                sql.fetchOne(
                        "select count(*) from reporting_profit_alert_rule"
                            + " where tenant_id = ? and threshold_value = -30",
                        b)
                    .get(0, Integer.class));
    assertThat(landedInB).as("B 名下不得多出别人写的规则行").isZero();
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 断言3_连接池归还后不串租户() {
    fact(a, "SKU-A", "100");
    for (int i = 0; i < 5; i++) {
      assertThat(scopeKeyOf(a)).as("第 %d 轮租户 A", i).isEqualTo("SKU-A");
      assertThat(scopeKeyOf(b)).as("第 %d 轮租户 B（无事实）", i).isNull();
      assertThat(visibleFactsWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(scopeKeyOf(a)).isEqualTo("SKU-A");
    assertThat(scopeKeyOf(b)).isNull();
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  /** 某租户在窗口里看到的唯一一条预警的归属取值（没有 ⇒ null）。 */
  private String scopeKeyOf(UUID tenant) {
    var alerts = page(tenant).alerts();
    if (alerts.isEmpty()) return null;
    return alerts.get(0).scopeKey();
  }

  /** 往指定租户名下写一行规则（**行上的 tenant_id 可以与绑定的租户不同**）。 */
  private void insertRuleAs(UUID boundTenant, UUID rowTenant) {
    tenants.run(
        boundTenant,
        () -> {
          sql.execute(
              """
              insert into reporting_profit_alert_rule
                (id,tenant_id,alert_type,threshold_mode,threshold_value,
                 min_history_periods,severity,enabled,updated_at)
              values (?,?, 'SKU_NEGATIVE_GROSS_PROFIT','FIXED', -30, 1,'CRITICAL',true, now())
              """,
              UUID.randomUUID(),
              rowTenant);
          return null;
        });
  }

  private String rawTenantSetting() {
    return sql.fetch("select current_setting('app.current_tenant', true)").stream()
        .findFirst()
        .map(r -> r.get(0, String.class))
        .orElse(null);
  }

  private UUID effectiveTenant() {
    var row = sql.fetchOne("select nullif(current_setting('app.current_tenant', true),'')::uuid");
    return row == null ? null : row.get(0, UUID.class);
  }

  private int visibleFactsWithoutTenant() {
    return sql.fetch("select id from reporting_profit_fact").size();
  }
}
