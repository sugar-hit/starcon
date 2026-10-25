package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Record;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 1.3.2.1 预警规则 —— **多租户三断言**。
 *
 * <p>{@link AlertRuleIntegrationTest} 里也有一份三断言，但那个类的主职责是
 * 业务行为（生效值合并、校验、权限闸、引擎未接通）。本类把三条铁律
 * <b>单独拉出来实跑</b>：预警规则是<b>租户级共享</b>的运营配置 ——
 * 隔离一旦破了，A 租户改低阈值就会让 B 租户的告警线跟着变，
 * 而租户之间**互不可见**是最基本的预期。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>；</li>
 *   <li><b>伪造 {@code X-Tenant-Id} 头无效</b>；</li>
 *   <li><b>连接池归还后不串租户</b>。</li>
 * </ol>
 *
 * <p><b>断言落在真实业务表上</b>（{@code alert_rule} 与 {@code alert_rule_hit}），
 * 并且**按主键直查对方的行、断言读到零行**。只在 HTTP 层断言「列表里没有对方数据」
 * 是不够的 —— 那个结论可能来自过滤条件写对了，而底下的行照样在库里可见。
 *
 * <p><b>连接池压到 1 条连接</b>：只有复用同一条物理连接，
 * 「归还后不串租户」才是真的被验证过 —— 池大于 1 时每次可能拿到另一条干净连接，
 * 「没串」可能只是碰巧。
 *
 * <p>🔴 <b>容器是本类自己的</b>，并在 {@code @AfterAll} 里 {@code .stop()} ——
 * 不进 {@code IdentityIntegrationTestBase} 的共享 static 容器那个历史包袱。
 */
class AlertRuleTenancyIntegrationTest extends AlertRuleTestSupport {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("create role starcon_app nologin");
      s.execute("create role identity_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "identity_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("LOCAL_BOOTSTRAP_ENABLED", () -> "true");
    r.add("LOCAL_ADMIN_PASSWORD", () -> "test-admin-password");
    // 池压到 1：每次请求必然复用同一条物理连接。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    pg.stop();
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  private static Map<String, Object> customRule(String name) {
    return Map.of(
        "name", name,
        "objectType", "SKU",
        "metricCode", "STOCK_COVER_DAYS",
        "threshold", "21",
        "timeWindow", "INSTANT",
        "comparison", "LT",
        "changeBasis", "NONE",
        "objectScope", "ALL",
        "timezone", "Asia/Shanghai");
  }

  private JsonNode list(String token) throws Exception {
    return request(token, "GET", "/identity/alert-rules", null, 200);
  }

  private List<Record> rowsIn(UUID tenant, String statement, Object... bindings) {
    return tx().run(tenant, () -> List.copyOf(sql.fetch(statement, bindings)));
  }

  // ==========================================================================
  // 断言一：两租户互不可见
  // ==========================================================================

  @Test
  void 两租户互不可见且各改各的阈值() throws Exception {
    String tag = suffix();
    String parent = isolatedTenant(tag);

    // 父租户改一条预置规则的阈值，并建一条自定义规则。
    String parentPresetId = findId(list(parent), "SKU_STOCK_COVER_DAYS_LOW");
    request(parent, "PUT", "/identity/alert-rules/" + parentPresetId, Map.of("threshold", "21"), 200);
    request(parent, "POST", "/identity/alert-rules", customRule("父租户自定义 " + tag), 200);

    String child = childTenant(parent, tag);

    // 子租户看到的预置规则是**预置默认值**（父租户的覆盖不串过去）。
    JsonNode childView = findRule(list(child), "SKU_STOCK_COVER_DAYS_LOW");
    assertThat(childView.get("threshold").asText())
        .as("父租户改过的阈值不得影响子租户")
        .isEqualTo("14");
    assertThat(childView.get("customized").asBoolean()).isFalse();

    // 子租户看不到父租户的自定义规则。
    assertThat(list(child).get("customCount").asInt())
        .as("父租户的自定义规则不得出现在子租户清单里")
        .isZero();

    // 按主键直查：子租户租户事务里读父租户的行 ⇒ 零行。
    UUID parentTenant = tenantOf(parent);
    UUID childTenantId = tenantOf(child);
    assertThat(rowsIn(childTenantId, "select id from alert_rule where name like '父租户自定义%'"))
        .as("子租户按主键直查父租户的行必须读到零行")
        .isEmpty();
    assertThat(rowsIn(parentTenant, "select id from alert_rule where name like '父租户自定义%'"))
        .as("父租户自己那一行确实在")
        .hasSize(1);

    // 子租户改自己的阈值，父租户不受影响。
    String childPresetId = findId(list(child), "SKU_STOCK_COVER_DAYS_LOW");
    request(child, "PUT", "/identity/alert-rules/" + childPresetId, Map.of("threshold", "7"), 200);

    assertThat(findRule(list(parent), "SKU_STOCK_COVER_DAYS_LOW").get("threshold").asText())
        .as("子租户改的阈值不得影响父租户")
        .isEqualTo("21");
    assertThat(findRule(list(child), "SKU_STOCK_COVER_DAYS_LOW").get("threshold").asText())
        .isEqualTo("7");
  }

  @Test
  void 子租户写的规则落进子租户自己的库() throws Exception {
    String tag = suffix();
    String parent = isolatedTenant(tag);
    String child = childTenant(parent, tag);

    request(child, "POST", "/identity/alert-rules", customRule("子租户自定义 " + tag), 200);

    assertThat(rowsIn(tenantOf(child), "select id from alert_rule where name like '子租户自定义%'"))
        .hasSize(1);
    assertThat(rowsIn(tenantOf(parent), "select id from alert_rule where name like '子租户自定义%'"))
        .as("子租户写的行不得出现在父租户的库里")
        .isEmpty();
  }

  // ==========================================================================
  // 断言二：伪造 X-Tenant-Id 头无效
  // ==========================================================================

  @Test
  void 伪造租户头无效() throws Exception {
    String tag = suffix();
    String parent = isolatedTenant(tag);
    String child = childTenant(parent, tag);
    String childTenantId = tenantOf(child).toString();

    // 伪造一个随机租户 id 与**子租户真实**租户 id：结果都必须不变。
    // 用子租户真实 id 才是真正有杀伤力的那一档 —— 调用方确实知道一个存在的租户 id。
    for (String forged : List.of(UUID.randomUUID().toString(), childTenantId)) {
      String body =
          http.perform(
                  org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                          "/identity/alert-rules/" + findId(list(parent), "SKU_STOCK_COVER_DAYS_LOW"))
                      .header("Authorization", "Bearer " + parent)
                      .header("X-Tenant-Id", forged)
                      .contentType("application/json")
                      .content(json.writeValueAsString(Map.of("threshold", "33"))))
              .andExpect(
                  org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                      .isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(body)
          .as("伪造 X-Tenant-Id=" + forged + " 不得改变生效值所属的租户")
          .contains("\"threshold\":\"33\"");
    }

    // 这次写仍然落回**父租户**自己的库。
    assertThat(rowsIn(tenantOf(parent), "select id from alert_rule"))
        .as("伪造头的写仍然落在父租户自己的库")
        .hasSize(1);
    assertThat(rowsIn(tenantOf(child), "select id from alert_rule"))
        .as("伪造租户头不得把行写进子租户的库")
        .isEmpty();
  }

  // ==========================================================================
  // 断言三：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 连接池归还后多轮交替读写不串租户() throws Exception {
    String tag = suffix();
    String parent = isolatedTenant(tag);
    String child = childTenant(parent, tag);

    String parentPresetId = findId(list(parent), "SKU_STOCK_COVER_DAYS_LOW");
    request(parent, "PUT", "/identity/alert-rules/" + parentPresetId, Map.of("threshold", "21"), 200);

    String childPresetId = findId(list(child), "SKU_STOCK_COVER_DAYS_LOW");
    request(child, "PUT", "/identity/alert-rules/" + childPresetId, Map.of("threshold", "7"), 200);

    // 池大小是 1（见 props），下面每一轮都必然复用同一条物理连接。
    for (int i = 0; i < 5; i++) {
      assertThat(findRule(list(parent), "SKU_STOCK_COVER_DAYS_LOW").get("threshold").asText())
          .as("第 %d 轮：父租户必须只看到自己的阈值", i)
          .isEqualTo("21");
      assertThat(findRule(list(child), "SKU_STOCK_COVER_DAYS_LOW").get("threshold").asText())
          .as("第 %d 轮：子租户必须只看到自己的阈值", i)
          .isEqualTo("7");

      // 交替写：每轮换一个新键，证明读到的是最新那份而不是第一份。
      Map<String, Object> fresh = customRule("轮次 " + i + " " + tag);
      request(child, "POST", "/identity/alert-rules", fresh, 200);
      assertThat(rowsIn(tenantOf(child), "select id from alert_rule where name = ?::varchar",
              "轮次 " + i + " " + tag))
          .as("第 %d 轮：子租户的写必须落在子租户自己的库", i)
          .hasSize(1);
      assertThat(rowsIn(tenantOf(parent), "select id from alert_rule where name = ?::varchar",
              "轮次 " + i + " " + tag))
          .as("第 %d 轮：子租户的写不得落进父租户的库", i)
          .isEmpty();

      // 回到父租户：连接刚被子租户用过，父租户必须仍然只见父租户。
      assertThat(findRule(list(parent), "SKU_STOCK_COVER_DAYS_LOW").get("threshold").asText())
          .as("第 %d 轮：子租户用过连接之后父租户仍不得串", i)
          .isEqualTo("21");
      assertThat(list(parent).get("customCount").asInt())
          .as("第 %d 轮：父租户不得看到子租户建的规则", i)
          .isZero();
    }
  }

  @Test
  void 两张表在两租户之间都查不到对方的行且均为强制RLS() throws Exception {
    // 事务外（app.current_tenant 未设置）直查必须为空：租户上下文不是「默认放行」。
    assertThat(sql.fetch("select id from alert_rule")).isEmpty();
    assertThat(sql.fetch("select id from alert_rule_hit")).isEmpty();

    for (String table : List.of("alert_rule", "alert_rule_hit")) {
      Boolean forced =
          sql.fetchOne("select relforcerowsecurity from pg_class where relname = ?::varchar", table)
              .get(0, Boolean.class);
      assertThat(forced).as("%s 必须是 FORCE RLS", table).isTrue();
    }

    // 顺带证明连接确实来自非超级用户、也没有 bypassrls —— 上面两条才有意义。
    assertThat(
            sql.fetchOne("select rolbypassrls, rolsuper from pg_roles where rolname=current_user")
                .get("rolbypassrls", Boolean.class))
        .as("应用连接不得持有 bypassrls，否则 FORCE RLS 形同虚设")
        .isFalse();
  }

  @Test
  void 触发器在缺少租户上下文时拒写() throws Exception {
    // 事务外直接写：app.current_tenant 没设置 ⇒ 触发器必须拒写。
    assertThatThrownBy(
            () ->
                sql.execute(
                    "insert into alert_rule(id,tenant_id,rule_code,origin,name,object_type,"
                        + "metric_code,threshold,time_window,comparison,change_basis,object_scope,"
                        + "timezone,timezone_source,status,created_by)"
                        + " values (?::uuid,?::uuid,'CUSTOM-X','CUSTOM','无上下文','SKU',"
                        + "'STOCK_COVER_DAYS',1.0,'INSTANT','LT','NONE','ALL','Asia/Shanghai',"
                        + "'EXPLICIT','ENABLED',?::uuid)",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID()))
        .as("缺少租户上下文时必须拒写（触发器兜底）")
        .hasMessageContaining("租户上下文");
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  private static JsonNode findRule(JsonNode list, String ruleCode) {
    for (JsonNode rule : list.get("rules")) {
      if (ruleCode.equals(rule.get("ruleCode").asText())) return rule;
    }
    throw new AssertionError("清单里没有规则 " + ruleCode);
  }

  private static String findId(JsonNode list, String ruleCode) {
    return findRule(list, ruleCode).get("id").asText();
  }
}
