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
 * 1.3.2.1 预警规则 —— **集成测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>本类管**业务行为**：生效值的合并语义、预置覆盖与恢复默认、启停、删除边界、
 * 词表与阈值校验、权限码闸、引擎未接通的如实表达。
 *
 * <p>多租户三断言在 {@link AlertRuleTenancyIntegrationTest} 里单独实跑。
 *
 * <p>🔴 <b>容器是本类自己的</b>，并在 {@code @AfterAll} 里 {@code .stop()} ——
 * 不继承 {@code IdentityIntegrationTestBase} 的共享 static 容器（那是历史包袱：
 * 43 个测试类共用一个、只随 JVM 退出回收，容器会一路累积）。
 */
class AlertRuleIntegrationTest extends AlertRuleTestSupport {

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
    // 池压到 1：这样「连接归还后不串租户」才能被真正验证。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    pg.stop();
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  /** 一条自定义规则的完整定义。 */
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

  private JsonNode findRule(JsonNode list, String ruleCode) {
    for (JsonNode rule : list.get("rules")) {
      if (ruleCode.equals(rule.get("ruleCode").asText())) return rule;
    }
    throw new AssertionError("清单里没有规则 " + ruleCode);
  }

  private List<Record> rowsIn(UUID tenant, String statement, Object... bindings) {
    return tx().run(tenant, () -> List.copyOf(sql.fetch(statement, bindings)));
  }

  // ==========================================================================
  // 一、能力声明
  // ==========================================================================

  @Test
  void 能力声明逐条如实报_引擎未接通时写明归属模块() throws Exception {
    String token = isolatedTenant(suffix());
    JsonNode caps = request(token, "GET", "/identity/alert-rules/capabilities", null, 200);

    assertThat(caps.get("engineAvailable").asBoolean())
        .as("18.3 业务规则引擎未交付，引擎必须报未接通")
        .isFalse();
    assertThat(caps.get("engineOwnerModule").asText())
        .as("未接通时必须写明归 18 系统哪个模块")
        .contains("18.3");
    assertThat(caps.get("engineNote").asText()).as("未接通的说明不能是空白").isNotBlank();

    // 预置规则集：蓝图点名的三个例子逐条在列。
    assertThat(caps.get("presets")).hasSize(5);
    List<String> codes = new java.util.ArrayList<>();
    for (JsonNode preset : caps.get("presets")) {
      codes.add(preset.get("ruleCode").asText());
      assertThat(preset.get("objectType").asText()).isNotBlank();
      assertThat(preset.get("metricCode").asText()).isNotBlank();
      assertThat(preset.get("threshold").asText()).isNotBlank();
      assertThat(preset.get("timeWindow").asText()).isNotBlank();
      assertThat(preset.get("comparison").asText()).isNotBlank();
      assertThat(preset.get("upstreamModule").asText()).isNotBlank();
    }
    assertThat(codes)
        .contains(
            "SKU_STOCK_COVER_DAYS_LOW",
            "SHOP_SALES_AMOUNT_DROP",
            "LEGAL_ENTITY_RECEIVABLE_OVERDUE_DAYS");

    // 受控词表齐全。
    assertThat(caps.get("vocabulary").get("objectTypes"))
        .as("对象类型词表")
        .hasSize(4);
    assertThat(caps.get("vocabulary").get("comparisons")).as("比较方向词表").hasSize(4);
    assertThat(caps.get("vocabulary").get("timeWindows")).as("时间窗词表").hasSize(4);
    assertThat(caps.get("vocabulary").get("metrics")).as("指标词表").hasSize(17);

    // 逐条能力声明：未交付的那些必须 available=false + ownerModule。
    JsonNode evaluation = null;
    for (JsonNode dimension : caps.get("dimensions")) {
      if ("ruleEvaluation".equals(dimension.get("code").asText())) evaluation = dimension;
    }
    assertThat(evaluation).as("必须逐条声明规则求值的能力").isNotNull();
    assertThat(evaluation.get("available").asBoolean()).isFalse();
    assertThat(evaluation.get("ownerModule").asText()).contains("18.3");
  }

  @Test
  void 指标词表逐条报出上游是否已交付该口径() throws Exception {
    String token = isolatedTenant(suffix());
    JsonNode caps = request(token, "GET", "/identity/alert-rules/capabilities", null, 200);
    JsonNode cover = null;
    JsonNode sales = null;
    for (JsonNode metric : caps.get("vocabulary").get("metrics")) {
      if ("STOCK_COVER_DAYS".equals(metric.get("code").asText())) cover = metric;
      if ("SALES_AMOUNT".equals(metric.get("code").asText())) sales = metric;
    }
    assertThat(cover).isNotNull();
    assertThat(cover.get("available").asBoolean())
        .as("覆盖天数口径 07 库存还没交付，必须报未接通而不是假装有")
        .isFalse();
    assertThat(cover.get("unavailableReason").asText()).isNotBlank();
    assertThat(cover.get("ownerModule").asText()).isNotBlank();

    assertThat(sales.get("available").asBoolean())
        .as("销售额口径 13 指标已交付")
        .isTrue();
  }

  // ==========================================================================
  // 二、生效值 = 预置默认值 ⨯ 租户覆盖
  // ==========================================================================

  @Test
  void 默认清单是五条预置规则且全部生效未被改过() throws Exception {
    String token = isolatedTenant(suffix());
    JsonNode list = list(token);

    assertThat(list.get("presetCount").asInt()).isEqualTo(5);
    assertThat(list.get("customCount").asInt()).isEqualTo(0);
    assertThat(list.get("customizedCount").asInt()).isEqualTo(0);
    assertThat(list.get("disabledCount").asInt()).isEqualTo(0);
    assertThat(list.get("rules")).hasSize(5);

    JsonNode stock = findRule(list, "SKU_STOCK_COVER_DAYS_LOW");
    assertThat(stock.get("origin").asText()).isEqualTo("PRESET");
    assertThat(stock.get("customized").asBoolean()).isFalse();
    assertThat(stock.get("enabled").asBoolean()).isTrue();
    assertThat(stock.get("threshold").asText()).isEqualTo("14");
    assertThat(stock.get("objectType").asText()).isEqualTo("SKU");
    assertThat(stock.get("metricCode").asText()).isEqualTo("STOCK_COVER_DAYS");
    assertThat(stock.get("timeWindow").asText()).isEqualTo("INSTANT");
    assertThat(stock.get("comparison").asText()).isEqualTo("LT");

    JsonNode sales = findRule(list, "SHOP_SALES_AMOUNT_DROP");
    assertThat(sales.get("changeBasis").asText()).isEqualTo("PERIOD_OVER_PERIOD");
    assertThat(sales.get("unit").asText())
        .as("环比比较的是变化率，单位是百分比而不是金额")
        .isEqualTo("PERCENT");

    // 库里**没有**任何预置规则的行 —— 预置集是代码里的声明，不落库。
    assertThat(rowsIn(tenantOf(token), "select id from alert_rule"))
        .as("没改过预置规则时，库里不许有行")
        .isEmpty();
  }

  @Test
  void 改预置规则的阈值会写一行覆盖并标为已被本租户改过() throws Exception {
    String token = isolatedTenant(suffix());
    JsonNode before = findRule(list(token), "SKU_STOCK_COVER_DAYS_LOW");
    String id = before.get("id").asText();

    JsonNode updated =
        request(
            token,
            "PUT",
            "/identity/alert-rules/" + id,
            Map.of("threshold", "21"),
            200);

    assertThat(updated.get("threshold").asText()).isEqualTo("21");
    assertThat(updated.get("customized").asBoolean()).isTrue();
    assertThat(updated.get("enabled").asBoolean()).isTrue();
    assertThat(updated.get("origin").asText()).isEqualTo("PRESET");
    // 没改的项仍然取预置默认。
    assertThat(updated.get("metricCode").asText()).isEqualTo("STOCK_COVER_DAYS");
    assertThat(updated.get("objectType").asText()).isEqualTo("SKU");
    assertThat(updated.get("comparison").asText()).isEqualTo("LT");

    // 真的落库了。
    List<Record> rows =
        rowsIn(
            tenantOf(token),
            "select rule_code, origin, threshold, metric_code from alert_rule");
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().get("rule_code", String.class))
        .isEqualTo("SKU_STOCK_COVER_DAYS_LOW");
    assertThat(rows.getFirst().get("origin", String.class)).isEqualTo("PRESET");
    assertThat(rows.getFirst().get("threshold", java.math.BigDecimal.class))
        .isEqualByComparingTo("21");
    assertThat(rows.getFirst().get("metric_code", String.class))
        .as("没改的项在覆盖行里必须留空，好让预置默认继续生效")
        .isNull();
  }

  @Test
  void 把某一项改回预置默认值等于清掉那一项的覆盖() throws Exception {
    String token = isolatedTenant(suffix());
    String id = findRule(list(token), "SKU_STOCK_COVER_DAYS_LOW").get("id").asText();
    request(token, "PUT", "/identity/alert-rules/" + id, Map.of("threshold", "21"), 200);

    assertThat(rowsIn(tenantOf(token), "select id, rule_code, origin, threshold from alert_rule"))
        .as("第一次改参数应当写下一行覆盖")
        .hasSize(1);

    // 改回预置默认 14 ⇒ 这一项的覆盖被清掉。
    JsonNode back =
        request(token, "PUT", "/identity/alert-rules/" + id, Map.of("threshold", "14"), 200);
    assertThat(back.get("threshold").asText()).isEqualTo("14");
    assertThat(back.get("customized").asBoolean())
        .as("覆盖清空后不该再标成「已被本租户改过」")
        .isFalse();
    assertThat(rowsIn(tenantOf(token), "select id from alert_rule"))
        .as("覆盖清空后那行没有意义，应当被删掉")
        .isEmpty();
  }

  @Test
  void 恢复默认会删掉覆盖行并回到预置值() throws Exception {
    String token = isolatedTenant(suffix());
    String id = findRule(list(token), "SKU_STOCK_COVER_DAYS_LOW").get("id").asText();
    request(token, "PUT", "/identity/alert-rules/" + id, Map.of("threshold", "21"), 200);
    assertThat(rowsIn(tenantOf(token), "select id from alert_rule")).hasSize(1);

    request(token, "POST", "/identity/alert-rules/" + id + "/reset", null, 200);

    JsonNode after = findRule(list(token), "SKU_STOCK_COVER_DAYS_LOW");
    assertThat(after.get("threshold").asText()).isEqualTo("14");
    assertThat(after.get("customized").asBoolean()).isFalse();
    assertThat(rowsIn(tenantOf(token), "select id from alert_rule")).isEmpty();
  }

  @Test
  void 关闭预置规则再重新启用会回到预置状态() throws Exception {
    String token = isolatedTenant(suffix());
    String id = findRule(list(token), "SKU_STOCK_COVER_DAYS_LOW").get("id").asText();

    JsonNode disabled = request(token, "POST", "/identity/alert-rules/" + id + "/disable", null, 200);
    assertThat(disabled.get("enabled").asBoolean()).isFalse();
    assertThat(disabled.get("customized").asBoolean()).isTrue();
    assertThat(list(token).get("disabledCount").asInt()).isEqualTo(1);

    JsonNode enabled = request(token, "POST", "/identity/alert-rules/" + id + "/enable", null, 200);
    assertThat(enabled.get("enabled").asBoolean()).isTrue();
    assertThat(enabled.get("customized").asBoolean())
        .as("只关闭过、没改过参数的规则，重新启用后就是纯预置状态")
        .isFalse();
    assertThat(rowsIn(tenantOf(token), "select id from alert_rule"))
        .as("重新启用后那行覆盖没有意义，应当被删掉")
        .isEmpty();
  }

  @Test
  void 关闭预置规则时保留已有的参数覆盖() throws Exception {
    String token = isolatedTenant(suffix());
    String id = findRule(list(token), "SKU_STOCK_COVER_DAYS_LOW").get("id").asText();
    request(token, "PUT", "/identity/alert-rules/" + id, Map.of("threshold", "21"), 200);

    JsonNode disabled = request(token, "POST", "/identity/alert-rules/" + id + "/disable", null, 200);
    assertThat(disabled.get("enabled").asBoolean()).isFalse();
    assertThat(disabled.get("threshold").asText())
        .as("关闭不应抹掉用户改过的参数")
        .isEqualTo("21");

    JsonNode enabled = request(token, "POST", "/identity/alert-rules/" + id + "/enable", null, 200);
    assertThat(enabled.get("enabled").asBoolean()).isTrue();
    assertThat(enabled.get("threshold").asText()).isEqualTo("21");
    assertThat(enabled.get("customized").asBoolean()).isTrue();
  }

  // ==========================================================================
  // 三、自定义规则
  // ==========================================================================

  @Test
  void 自定义规则可建可改可删且真的落库() throws Exception {
    String token = isolatedTenant(suffix());
    JsonNode created = request(token, "POST", "/identity/alert-rules", customRule("我的补货线"), 200);

    assertThat(created.get("origin").asText()).isEqualTo("CUSTOM");
    assertThat(created.get("displayName").asText()).isEqualTo("我的补货线");
    assertThat(created.get("threshold").asText()).isEqualTo("21");
    assertThat(created.get("enabled").asBoolean()).isTrue();
    assertThat(created.get("customized").asBoolean()).isTrue();
    assertThat(created.get("ruleCode").asText()).startsWith("CUSTOM-");
    String id = created.get("id").asText();

    assertThat(list(token).get("customCount").asInt()).isEqualTo(1);

    // 改：只提交改的那一项，其余保持。
    JsonNode updated =
        request(token, "PUT", "/identity/alert-rules/" + id, Map.of("threshold", "7.5"), 200);
    assertThat(updated.get("threshold").asText()).isEqualTo("7.5");
    assertThat(updated.get("displayName").asText()).isEqualTo("我的补货线");
    assertThat(updated.get("metricCode").asText()).isEqualTo("STOCK_COVER_DAYS");

    // 删。
    request(token, "DELETE", "/identity/alert-rules/" + id, null, 200);
    assertThat(list(token).get("customCount").asInt()).isEqualTo(0);
    assertThat(rowsIn(tenantOf(token), "select id from alert_rule")).isEmpty();
  }

  @Test
  void 预置规则不允许删除只允许关闭() throws Exception {
    String token = isolatedTenant(suffix());
    String id = findRule(list(token), "SKU_STOCK_COVER_DAYS_LOW").get("id").asText();

    requestExpecting(token, "DELETE", "/identity/alert-rules/" + id, null, 409);

    assertThat(list(token).get("presetCount").asInt())
        .as("删除被拒后清单不变")
        .isEqualTo(5);
  }

  @Test
  void 自定义规则没有默认值可恢复() throws Exception {
    String token = isolatedTenant(suffix());
    JsonNode created = request(token, "POST", "/identity/alert-rules", customRule("不恢复"), 200);
    requestExpecting(
        token, "POST", "/identity/alert-rules/" + created.get("id").asText() + "/reset", null, 400);
  }

  // ==========================================================================
  // 四、校验
  // ==========================================================================

  @Test
  void 词表外的度量被拒() throws Exception {
    String token = isolatedTenant(suffix());
    Map<String, Object> input = new java.util.HashMap<>(customRule("坏度量"));
    input.put("metricCode", "NOT_A_METRIC");
    requestExpecting(token, "POST", "/identity/alert-rules", input, 400);
  }

  @Test
  void 词表外的对象类型与比较方向被拒() throws Exception {
    String token = isolatedTenant(suffix());
    Map<String, Object> input = new java.util.HashMap<>(customRule("坏对象类型"));
    input.put("objectType", "WAREHOUSE");
    requestExpecting(token, "POST", "/identity/alert-rules", input, 400);

    input = new java.util.HashMap<>(customRule("坏比较方向"));
    input.put("comparison", "EQUALS");
    requestExpecting(token, "POST", "/identity/alert-rules", input, 400);
  }

  @Test
  void 阈值必须是十进制文本且不能超精度() throws Exception {
    String token = isolatedTenant(suffix());
    Map<String, Object> input = new java.util.HashMap<>(customRule("坏阈值"));
    input.put("threshold", "abc");
    requestExpecting(token, "POST", "/identity/alert-rules", input, 400);

    input = new java.util.HashMap<>(customRule("科学计数法"));
    input.put("threshold", "1E2");
    requestExpecting(token, "POST", "/identity/alert-rules", input, 400);

    input = new java.util.HashMap<>(customRule("小数位太多"));
    input.put("threshold", "1.1234567");
    requestExpecting(token, "POST", "/identity/alert-rules", input, 400);

    input = new java.util.HashMap<>(customRule("负阈值"));
    input.put("threshold", "-1");
    requestExpecting(token, "POST", "/identity/alert-rules", input, 400);
  }

  @Test
  void 小数阈值精确写回不漂移() throws Exception {
    String token = isolatedTenant(suffix());
    Map<String, Object> input = new java.util.HashMap<>(customRule("精确阈值"));
    input.put("threshold", "20.5");
    JsonNode created = request(token, "POST", "/identity/alert-rules", input, 200);

    assertThat(created.get("threshold").asText()).isEqualTo("20.5");
    assertThat(
            rowsIn(
                    tenantOf(token),
                    "select threshold from alert_rule where name = '精确阈值'")
                .getFirst()
                .get("threshold", java.math.BigDecimal.class))
        .as("库里必须是精确的 20.5，不是 20.499999")
        .isEqualByComparingTo("20.5");
  }

  @Test
  void 对象范围与对象标识必须自洽() throws Exception {
    String token = isolatedTenant(suffix());
    Map<String, Object> input = new java.util.HashMap<>(customRule("缺对象标识"));
    input.put("objectScope", "SPECIFIC");
    requestExpecting(token, "POST", "/identity/alert-rules", input, 400);

    input = new java.util.HashMap<>(customRule("多对象标识"));
    input.put("objectScope", "ALL");
    input.put("objectRef", "SKU-1");
    requestExpecting(token, "POST", "/identity/alert-rules", input, 400);

    input = new java.util.HashMap<>(customRule("正确对象标识"));
    input.put("objectScope", "SPECIFIC");
    input.put("objectRef", "SKU-1");
    JsonNode created = request(token, "POST", "/identity/alert-rules", input, 200);
    assertThat(created.get("objectScope").asText()).isEqualTo("SPECIFIC");
    assertThat(created.get("objectRef").asText()).isEqualTo("SKU-1");
  }

  @Test
  void 度量不能挂在不匹配的对象类型上() throws Exception {
    String token = isolatedTenant(suffix());
    Map<String, Object> input = new java.util.HashMap<>(customRule("店铺销售额"));
    input.put("objectType", "SKU");
    input.put("metricCode", "SALES_AMOUNT");
    requestExpecting(token, "POST", "/identity/alert-rules", input, 400);
  }

  @Test
  void 零阈值护栏挡住永不可达或恒命中的规则() throws Exception {
    String token = isolatedTenant(suffix());
    Map<String, Object> input = new java.util.HashMap<>(customRule("永远不命中"));
    input.put("metricCode", "STOCK_COVER_DAYS");
    input.put("comparison", "LT");
    input.put("threshold", "0");
    requestExpecting(token, "POST", "/identity/alert-rules", input, 400);

    input = new java.util.HashMap<>(customRule("恒命中"));
    input.put("metricCode", "STOCK_COVER_DAYS");
    input.put("comparison", "GE");
    input.put("threshold", "0");
    requestExpecting(token, "POST", "/identity/alert-rules", input, 400);
  }

  @Test
  void 请求体不接受租户与成员字段() throws Exception {
    String token = isolatedTenant(suffix());
    Map<String, Object> input = new java.util.HashMap<>(customRule("带租户"));
    input.put("tenantId", UUID.randomUUID().toString());
    requestExpecting(token, "POST", "/identity/alert-rules", input, 400);

    input = new java.util.HashMap<>(customRule("带成员"));
    input.put("membershipId", UUID.randomUUID().toString());
    requestExpecting(token, "POST", "/identity/alert-rules", input, 400);

    assertThat(rowsIn(tenantOf(token), "select id from alert_rule"))
        .as("被拒的请求不许留下任何行")
        .isEmpty();
  }

  @Test
  void 时区必须是受控的IANA区域id() throws Exception {
    String token = isolatedTenant(suffix());
    Map<String, Object> input = new java.util.HashMap<>(customRule("坏时区"));
    input.put("timezone", "UTC+8");
    requestExpecting(token, "POST", "/identity/alert-rules", input, 400);

    input = new java.util.HashMap<>(customRule("好时区"));
    input.put("timezone", "America/New_York");
    JsonNode created = request(token, "POST", "/identity/alert-rules", input, 200);
    assertThat(created.get("timezone").asText()).isEqualTo("America/New_York");
    assertThat(created.get("timezoneSource").asText()).isEqualTo("EXPLICIT");
  }

  @Test
  void 不给时区时继承当前操作人的展示时区并标明来源() throws Exception {
    String token = isolatedTenant(suffix());
    Map<String, Object> input = new java.util.HashMap<>(customRule("继承时区"));
    input.remove("timezone");
    JsonNode created = request(token, "POST", "/identity/alert-rules", input, 200);

    assertThat(created.get("timezone").asText()).isNotBlank();
    assertThat(created.get("timezoneSource").asText())
        .as("继承来的时区必须标明来源，否则用户以为按别的时区算")
        .isEqualTo("ACCOUNT_LOCALE");
  }

  // ==========================================================================
  // 五、命中历史（引擎未接通 ⇒ 空集合 + 如实说明）
  // ==========================================================================

  @Test
  void 命中历史在引擎未接通时是空集合并说明归属() throws Exception {
    String token = isolatedTenant(suffix());
    JsonNode hits = request(token, "GET", "/identity/alert-rules/hits", null, 200);

    assertThat(hits.get("hits"))
        .as("引擎未接通时命中历史恒为空 —— 绝不许伪造命中记录")
        .isEmpty();
    assertThat(hits.get("engineAvailable").asBoolean()).isFalse();
    assertThat(hits.get("engineOwnerModule").asText()).contains("18.3");
    assertThat(hits.get("engineNote").asText()).isNotBlank();
    assertThat(hits.get("total").asInt()).isZero();

    assertThat(rowsIn(tenantOf(token), "select id from alert_rule_hit"))
        .as("本模块不写命中历史，库里必须一行都没有")
        .isEmpty();
  }

  // ==========================================================================
  // 六、权限
  // ==========================================================================

  @Test
  void 没有权限码时端点恒403() throws Exception {
    String tag = suffix();
    String owner = isolatedTenant(tag);
    String nobody = memberWithout(owner, tag);

    requestExpecting(nobody, "GET", "/identity/alert-rules", null, 403);
    requestExpecting(nobody, "GET", "/identity/alert-rules/capabilities", null, 403);
    requestExpecting(nobody, "GET", "/identity/alert-rules/hits", null, 403);
    requestExpecting(nobody, "POST", "/identity/alert-rules", customRule("没权限"), 403);
  }

  @Test
  void 只有读权限时写动作403读动作正常() throws Exception {
    String tag = suffix();
    String owner = isolatedTenant(tag);
    String reader =
        memberWith(owner, tag, List.of("identity.alert_rule.read"), "ALL");

    requestExpecting(reader, "GET", "/identity/alert-rules", null, 200);
    requestExpecting(reader, "POST", "/identity/alert-rules", customRule("只读"), 403);
  }

  @Test
  void 持有写权限的角色只能绑定租户内全部范围() throws Exception {
    String tag = suffix();
    String owner = isolatedTenant(tag);
    UUID role =
        createRole(
            owner, tag, List.of("identity.alert_rule.read", "identity.alert_rule.write"));

    // 平台层的不变量：持有非 catalog.* 权限的角色**只能**按租户内全部范围绑定
    // （identity 的成员分配端点直接拒掉其它范围）——
    // 预警规则是租户级共享配置，「只对本部门生效」的授权本就不该能改全租户的告警线。
    // 本模块的写端点因此再查一次 scopeMode == ALL：两道防线，同一件事。
    addMember(owner, "ars-" + tag.toLowerCase(), role, "SELF", 400);
    addMember(owner, "ara-" + tag.toLowerCase(), role, "ALL", 200);

    // 租户级授权的成员可以改规则。
    String writer = loginUser("ara-" + tag.toLowerCase());
    JsonNode created = request(writer, "POST", "/identity/alert-rules", customRule("租户级授权"), 200);
    assertThat(created.get("origin").asText()).isEqualTo("CUSTOM");
  }

  @Test
  void 租户级写权限可以改规则() throws Exception {
    String tag = suffix();
    String owner = isolatedTenant(tag);
    String writer =
        memberWith(
            owner, tag, List.of("identity.alert_rule.read", "identity.alert_rule.write"), "ALL");

    JsonNode created = request(writer, "POST", "/identity/alert-rules", customRule("授权改"), 200);
    assertThat(created.get("origin").asText()).isEqualTo("CUSTOM");
  }
}
