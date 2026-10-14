package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 17.5.1.1 业务参数。
 *
 * <p>重点不是"能存进去"，而是蓝图点名的两条硬要求真的成立，外加多租户三断言：
 *
 * <ol>
 *   <li><b>参数必须有默认值</b>——每个参数都有出厂默认值，且默认值的类型自洽（数值型带 NUMERIC）。
 *   <li><b>参数必须有生效范围</b>——全局 / 按法人 / 按店铺三档，优先级严格是 <b>店铺 &gt; 法人 &gt; 全局 &gt;
 *       出厂默认</b>，且生效值同时告诉调用方"这个值从哪一档来"。
 *   <li><b>改参数不改历史单据已冻结的行为</b>——冻结后改参数，读回单据快照仍是冻结那一刻的值； 重复冻结被 409 挡下；变更历史追加不可改。
 * </ol>
 *
 * <p>时间字段全部按值断言：用 {@code Timestamp.valueOf(LocalDateTime)} 绑 timestamptz 会静默偏 −28800 秒，所以 {@code
 * effectiveFrom} / {@code changedAt} / {@code frozenAt} 都落在"请求前后两个墙钟之间"才算通过。
 */
class BusinessParameterIntegrationTest extends IdentityIntegrationTestBase {

  /**
   * 每个用例前清空本模块的数据。
   *
   * <p>容器与 local-admin 租户在所有测试类之间共享（见 IdentityIntegrationTestBase）， 同一个「参数 × GLOBAL
   * 档」被上一个用例配过就会撞唯一约束。变更历史有不可篡改触发器， 普通 DELETE 会被它挡住，所以用 TRUNCATE —— 它不触发行级触发器。
   */
  @org.junit.jupiter.api.BeforeEach
  void resetModule() throws Exception {
    try (var c =
            DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate business_parameter_freeze_value, business_parameter_freeze,"
              + " business_parameter_history, business_parameter_override");
    }
  }

  private TenantTransactions tx() {
    return new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  // ------------------------------------------------------------------
  // 读侧辅助
  // ------------------------------------------------------------------

  private JsonNode definitions(String token) throws Exception {
    return request(token, "GET", "/identity/business-parameters", null, 200);
  }

  private JsonNode effective(String token, UUID legalEntityId, UUID shopId) throws Exception {
    var query = new java.util.ArrayList<String>();
    if (legalEntityId != null) query.add("legalEntityId=" + legalEntityId);
    if (shopId != null) query.add("shopId=" + shopId);
    return request(
        token,
        "GET",
        "/identity/business-parameters/effective"
            + (query.isEmpty() ? "" : "?" + String.join("&", query)),
        null,
        200);
  }

  private JsonNode find(JsonNode list, String parameterCode) {
    for (JsonNode n : list) if (parameterCode.equals(n.get("parameterCode").asText())) return n;
    return null;
  }

  private JsonNode findDefinition(JsonNode list, String code) {
    for (JsonNode n : list) if (code.equals(n.get("code").asText())) return n;
    return null;
  }

  private Map<String, Object> overrideInput(
      String code,
      String scopeType,
      UUID legalEntityId,
      UUID shopId,
      String shopLabel,
      String value) {
    var input = new LinkedHashMap<String, Object>();
    input.put("parameterCode", code);
    input.put("scopeType", scopeType);
    if (legalEntityId != null) input.put("legalEntityId", legalEntityId.toString());
    if (shopId != null) input.put("shopId", shopId.toString());
    if (shopLabel != null) input.put("shopLabel", shopLabel);
    input.put("value", value);
    input.put("note", "集成测试配置");
    return input;
  }

  private UUID createOverride(
      String token,
      String code,
      String scopeType,
      UUID legalEntityId,
      UUID shopId,
      String shopLabel,
      String value)
      throws Exception {
    return UUID.fromString(
        request(
                token,
                "POST",
                "/identity/business-parameters/overrides",
                overrideInput(code, scopeType, legalEntityId, shopId, shopLabel, value),
                200)
            .asText());
  }

  private UUID entity(String token, String code) throws Exception {
    return UUID.fromString(
        request(token, "POST", "/identity/legal-entities", legalEntityInput(code, "USD"), 200)
            .asText());
  }

  // ------------------------------------------------------------------
  // 默认值
  // ------------------------------------------------------------------

  @Test
  void everyParameterHasAFactoryDefaultAndSelfConsistentType() throws Exception {
    String token = login();
    var list = definitions(token);

    // 蓝图职责点名的六类参数都在码表里，一个不少。
    assertThat(list).hasSizeGreaterThanOrEqualTo(8);
    for (String code :
        List.of(
            "ORDER_REVIEW_POLICY",
            "INVENTORY_POLICY",
            "AFTERSALE_RETURN_WINDOW_DAYS",
            "AFTERSALE_MUST_REFUND_REASONS",
            "COST_BASIS",
            "DOCUMENT_NUMBER_PREFIX",
            "DISPLAY_TIMEZONE",
            "DISPLAY_CURRENCY"))
      assertThat(findDefinition(list, code)).as("参数 %s 必须在码表里", code).isNotNull();

    // 数值型参数的默认值必须同时带文本与 NUMERIC，两者能还原出同一个数。
    var days = findDefinition(list, "AFTERSALE_RETURN_WINDOW_DAYS");
    assertThat(days.get("valueType").asText()).isEqualTo("INTEGER");
    assertThat(days.get("unit").asText()).isEqualTo("天");
    assertThat(days.get("defaultValue").asText()).isEqualTo("30");
    assertThat(days.get("defaultValueNumeric").decimalValue()).isEqualByComparingTo("30");

    // 非数值型参数不得带数值列：码表里写错会在迁移期被 CHECK 挡住，这里再确认一次读出口径。
    var policy = findDefinition(list, "ORDER_REVIEW_POLICY");
    assertThat(policy.get("defaultValueNumeric").isNull()).isTrue();
    assertThat(policy.get("defaultValue").asText()).isEqualTo("REVIEW_ALL");

    // 多选参数在契约里必须保持数组类型（Map<String,X> 会退化成 {}，前端就渲染不出来了）。
    var reasons = findDefinition(list, "AFTERSALE_MUST_REFUND_REASONS");
    assertThat(reasons.get("allowedValueCodes").isArray()).isTrue();
    assertThat(reasons.get("allowedValueCodes")).hasSize(6);
    assertThat(reasons.get("allowedValueCodes").get(0).asText()).isEqualTo("DAMAGED_ON_ARRIVAL");
    assertThat(findDefinition(list, "INVENTORY_POLICY").get("allowedValueCodes").get(0).asText())
        .isEqualTo("DISALLOW_NEGATIVE");
  }

  // ------------------------------------------------------------------
  // 生效范围与优先级
  // ------------------------------------------------------------------

  @Test
  void effectiveValueFollowsShopThenEntityThenGlobalThenFactoryDefault() throws Exception {
    String token = login(), tag = suffix();
    UUID entity = entity(token, "BP" + tag);
    UUID shop = UUID.randomUUID();

    // 只配全局时，任何上下文都拿到全局值
    createOverride(token, "COST_BASIS", "GLOBAL", null, null, null, "FIFO");
    assertThat(effective(token, null, null).get(0)).isNotNull();
    assertThat(find(effective(token, null, null), "COST_BASIS").get("value").asText())
        .isEqualTo("FIFO");

    // 加法人档后：法人上下文取法人值，其他租户级上下文仍取全局值
    createOverride(token, "COST_BASIS", "LEGAL_ENTITY", entity, null, null, "LIFO");
    var atEntity = find(effective(token, entity, null), "COST_BASIS");
    assertThat(atEntity.get("value").asText()).isEqualTo("LIFO");
    assertThat(atEntity.get("sourceScope").asText()).isEqualTo("LEGAL_ENTITY");
    assertThat(atEntity.get("inherited").asBoolean()).isFalse();
    assertThat(atEntity.get("definitionDefault").asText()).isEqualTo("MOVING_AVERAGE");

    // 无上下文仍是全局档
    var atGlobal = find(effective(token, null, null), "COST_BASIS");
    assertThat(atGlobal.get("value").asText()).isEqualTo("FIFO");
    assertThat(atGlobal.get("sourceScope").asText()).isEqualTo("GLOBAL");

    // 加店铺档后：店铺上下文取店铺值（最高优先级）
    createOverride(token, "COST_BASIS", "SHOP", null, shop, "美国店铺 " + tag, "IMPORTED_COST");
    var atShop = find(effective(token, entity, shop), "COST_BASIS");
    assertThat(atShop.get("value").asText()).isEqualTo("IMPORTED_COST");
    assertThat(atShop.get("sourceScope").asText()).isEqualTo("SHOP");
    // 店铺标签作为可读标签落库
    assertThat(atShop.get("sourceScopeId").asText()).isEqualTo(shop.toString());

    // 只给店铺、不给法人时，店铺档仍然胜出
    assertThat(find(effective(token, null, shop), "COST_BASIS").get("value").asText())
        .isEqualTo("IMPORTED_COST");

    // 删除店铺档后立刻退回法人档——「删除 = 退回继承」必须真的退回
    var shopOverride =
        find(
            request(
                token, "GET", "/identity/business-parameters/overrides?scopeType=SHOP", null, 200),
            "COST_BASIS");
    request(
        token,
        "DELETE",
        "/identity/business-parameters/overrides/" + shopOverride.get("id").asText(),
        null,
        200);
    assertThat(find(effective(token, entity, shop), "COST_BASIS").get("value").asText())
        .isEqualTo("LIFO");
  }

  @Test
  void unconfiguredParameterFallsBackToFactoryDefaultAndSaysSo() throws Exception {
    String token = login();
    var row = find(effective(token, null, null), "DISPLAY_CURRENCY");
    // 没配任何档：值 = 出厂默认，且必须显式告诉调用方它是继承来的
    assertThat(row.get("value").asText()).isEqualTo("CNY");
    assertThat(row.get("sourceScope").asText()).isEqualTo("DEFAULT");
    assertThat(row.get("inherited").asBoolean()).isTrue();
    assertThat(row.get("overrideId").isNull()).isTrue();
    assertThat(row.get("effectiveFrom").isNull()).isTrue();
  }

  // ------------------------------------------------------------------
  // 取值校验（规则来自码表，不是写死在代码里）
  // ------------------------------------------------------------------

  @Test
  void valuesAreValidatedAgainstTheCodeTable() throws Exception {
    String token = login(), tag = suffix();
    UUID entity = entity(token, "BV" + tag);

    // 枚举值不在码表允许范围内
    request(
        token,
        "POST",
        "/identity/business-parameters/overrides",
        overrideInput("COST_BASIS", "GLOBAL", null, null, null, "SOMETHING_ELSE"),
        400);

    // 参数不存在
    request(
        token,
        "POST",
        "/identity/business-parameters/overrides",
        overrideInput("NO_SUCH_PARAM", "GLOBAL", null, null, null, "X"),
        400);

    // 数值型参数：非数值 / 负数 / 超格式
    request(
        token,
        "POST",
        "/identity/business-parameters/overrides",
        overrideInput("AFTERSALE_RETURN_WINDOW_DAYS", "GLOBAL", null, null, null, "三十"),
        400);
    request(
        token,
        "POST",
        "/identity/business-parameters/overrides",
        overrideInput("AFTERSALE_RETURN_WINDOW_DAYS", "GLOBAL", null, null, null, "-5"),
        400);
    request(
        token,
        "POST",
        "/identity/business-parameters/overrides",
        overrideInput("AFTERSALE_RETURN_WINDOW_DAYS", "GLOBAL", null, null, null, "99999"),
        400);

    // 字符串型参数：正则约束（三位大写币种）
    request(
        token,
        "POST",
        "/identity/business-parameters/overrides",
        overrideInput("DISPLAY_CURRENCY", "GLOBAL", null, null, null, "usd"),
        400);
    createOverride(token, "DISPLAY_CURRENCY", "GLOBAL", null, null, null, "USD");

    // 多选参数：其中一项非法即整体拒绝，不做"静默丢弃非法项"
    request(
        token,
        "POST",
        "/identity/business-parameters/overrides",
        overrideInput(
            "AFTERSALE_MUST_REFUND_REASONS",
            "LEGAL_ENTITY",
            entity,
            null,
            null,
            "DAMAGED_ON_ARRIVAL,NOT_A_REAL_REASON"),
        400);

    // 多选参数的空串是合法配置（当前不设必退情形），不该被当成漏填
    createOverride(token, "AFTERSALE_MUST_REFUND_REASONS", "LEGAL_ENTITY", entity, null, null, "");
    var row = find(effective(token, entity, null), "AFTERSALE_MUST_REFUND_REASONS");
    assertThat(row.get("value").asText()).isEmpty();
    assertThat(row.get("valueCodes")).isEmpty();
  }

  @Test
  void scopeShapeMustMatchTheScopeType() throws Exception {
    String token = login(), tag = suffix();
    UUID entity = entity(token, "SS" + tag);

    // 全局档不许指定法人
    request(
        token,
        "POST",
        "/identity/business-parameters/overrides",
        overrideInput("COST_BASIS", "GLOBAL", entity, null, null, "FIFO"),
        400);
    // 法人档必须指定法人
    request(
        token,
        "POST",
        "/identity/business-parameters/overrides",
        overrideInput("COST_BASIS", "LEGAL_ENTITY", null, null, null, "FIFO"),
        400);
    // 店铺档必须给店铺标签（跨服务拿不到可读名，标签由调用方带入并快照）
    request(
        token,
        "POST",
        "/identity/business-parameters/overrides",
        overrideInput("COST_BASIS", "SHOP", null, UUID.randomUUID(), null, "FIFO"),
        400);
    // 法人不存在时必须是 404，而不是静默退回全局值
    request(
        token,
        "POST",
        "/identity/business-parameters/overrides",
        overrideInput("COST_BASIS", "LEGAL_ENTITY", UUID.randomUUID(), null, null, "FIFO"),
        404);
    // 查询时传一个不存在的法人同样要报，不能把"上下文无效"静默降级
    request(
        token,
        "GET",
        "/identity/business-parameters/effective?legalEntityId=" + UUID.randomUUID(),
        null,
        404);
  }

  @Test
  void duplicateConfigurationInTheSameScopeIsRejected() throws Exception {
    String token = login();
    createOverride(token, "DISPLAY_TIMEZONE", "GLOBAL", null, null, null, "UTC");
    request(
        token,
        "POST",
        "/identity/business-parameters/overrides",
        overrideInput("DISPLAY_TIMEZONE", "GLOBAL", null, null, null, "Asia/Tokyo"),
        409);
    // 唯一约束必须真的生效：库里不能出现两条同档同参数的配置。
    // 必须放进租户事务数——事务外 RLS 会把表读空，那样的 0 证明不了任何事。
    assertThat(
            tx().run(
                    UUID.fromString(
                        request(token, "GET", "/identity/tenants/current", null, 200)
                            .get("tenantId")
                            .asText()),
                    () -> sql.fetch("select id from business_parameter_override")))
        .hasSize(1);
  }

  // ------------------------------------------------------------------
  // 数值与时间按值往返
  // ------------------------------------------------------------------

  @Test
  void numericAndTimestampValuesRoundTripWithoutDrift() throws Exception {
    String token = login();
    Instant before = Instant.now();
    UUID id =
        createOverride(token, "AFTERSALE_RETURN_WINDOW_DAYS", "GLOBAL", null, null, null, "45");
    Instant after = Instant.now();

    var row =
        find(
            request(token, "GET", "/identity/business-parameters/overrides", null, 200),
            "AFTERSALE_RETURN_WINDOW_DAYS");
    assertThat(UUID.fromString(row.get("id").asText())).isEqualTo(id);
    // 文本与数值必须能互相还原出同一个数（NUMERIC(19,6)，禁浮点）
    assertThat(row.get("value").asText()).isEqualTo("45");
    assertThat(row.get("valueNumeric").decimalValue()).isEqualByComparingTo("45");

    // effectiveFrom 必须落在请求前后两个墙钟之间：Timestamp.valueOf(LocalDateTime) 的
    // 写法会在这里静默偏 −28800 秒，所以按值断言而不是只看"没抛异常"。
    OffsetDateTime effectiveFrom = OffsetDateTime.parse(row.get("effectiveFrom").asText());
    assertThat(effectiveFrom.toInstant()).isBetween(before.minusSeconds(1), after.plusSeconds(1));

    // 变更历史的 changed_at 同样按值断言
    var history =
        request(
            token,
            "GET",
            "/identity/business-parameters/history?parameterCode=AFTERSALE_RETURN_WINDOW_DAYS",
            null,
            200);
    assertThat(history).hasSize(1);
    OffsetDateTime changedAt = OffsetDateTime.parse(history.get(0).get("changedAt").asText());
    assertThat(changedAt.toInstant()).isBetween(before.minusSeconds(1), after.plusSeconds(1));
  }

  // ------------------------------------------------------------------
  // 变更历史
  // ------------------------------------------------------------------

  @Test
  void everyChangeIsRecordedWithBeforeAndAfterValues() throws Exception {
    String token = login(), tag = suffix();
    UUID entity = entity(token, "CH" + tag);

    UUID id = createOverride(token, "COST_BASIS", "LEGAL_ENTITY", entity, null, null, "FIFO");
    request(
        token,
        "PUT",
        "/identity/business-parameters/overrides/" + id,
        overrideInput("COST_BASIS", "LEGAL_ENTITY", entity, null, null, "LIFO"),
        200);
    request(token, "DELETE", "/identity/business-parameters/overrides/" + id, null, 200);

    var history =
        request(
            token,
            "GET",
            "/identity/business-parameters/history?parameterCode=COST_BASIS&scopeType=LEGAL_ENTITY",
            null,
            200);
    assertThat(history).hasSize(3);
    // 按时间倒序：删除 → 更新 → 新建
    assertThat(history.get(0).get("action").asText()).isEqualTo("DELETED");
    assertThat(history.get(0).get("beforeValue").asText()).isEqualTo("LIFO");
    assertThat(history.get(0).get("afterValue").isNull()).isTrue();

    var updated = history.get(1);
    assertThat(updated.get("action").asText()).isEqualTo("UPDATED");
    assertThat(updated.get("beforeValue").asText()).isEqualTo("FIFO");
    assertThat(updated.get("afterValue").asText()).isEqualTo("LIFO");
    // 变更的是法人档，历史里必须带着法人 id 与可读编码
    assertThat(updated.get("legalEntityId").asText()).isEqualTo(entity.toString());
    assertThat(updated.get("legalEntityCode").asText()).isEqualTo("CH" + tag);
    // 操作人可读名快照在写入时刻，历史不靠 JOIN 一张会变的表
    assertThat(updated.get("actorName").asText()).isEqualTo("local-admin");

    var created = history.get(2);
    assertThat(created.get("action").asText()).isEqualTo("CREATED");
    assertThat(created.get("beforeValue").isNull()).isTrue();
    assertThat(created.get("afterValue").asText()).isEqualTo("FIFO");
  }

  @Test
  void historyRowsCannotBeUpdatedOrDeleted() throws Exception {
    String token = login();
    createOverride(token, "COST_BASIS", "GLOBAL", null, null, null, "FIFO");
    var history =
        request(
            token,
            "GET",
            "/identity/business-parameters/history?parameterCode=COST_BASIS",
            null,
            200);
    UUID id = UUID.fromString(history.get(0).get("id").asText());

    // 用 identity_app 直连：权限层已经不给 UPDATE/DELETE
    try (var c = DriverManager.getConnection(pg().getJdbcUrl(), "identity_app", "test-only")) {
      assertThatThrownBy(
              () ->
                  c.createStatement()
                      .executeUpdate(
                          "update business_parameter_history set action='Tampered' where id='"
                              + id
                              + "'"))
          .isInstanceOf(java.sql.SQLException.class);
      assertThatThrownBy(
              () ->
                  c.createStatement()
                      .executeUpdate(
                          "delete from business_parameter_history where id='" + id + "'"))
          .isInstanceOf(java.sql.SQLException.class);
    }

    // 用超级用户直连：权限被绕过，但触发器仍挡住——这才是真正的不可篡改
    try (var c =
        DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword())) {
      assertThatThrownBy(
              () ->
                  c.createStatement()
                      .executeUpdate(
                          "update business_parameter_history set action='Tampered' where id='"
                              + id
                              + "'"))
          .hasMessageContaining("不可修改或删除");
      assertThatThrownBy(
              () ->
                  c.createStatement()
                      .executeUpdate(
                          "delete from business_parameter_history where id='" + id + "'"))
          .hasMessageContaining("不可修改或删除");
    }

    var still =
        request(
            token,
            "GET",
            "/identity/business-parameters/history?parameterCode=COST_BASIS",
            null,
            200);
    assertThat(still.get(0).get("action").asText()).isEqualTo("CREATED");
  }

  @Test
  void effectiveFromScopeCannotBeChangedInPlace() throws Exception {
    String token = login(), tag = suffix();
    UUID entity = entity(token, "SC" + tag);
    UUID id = createOverride(token, "COST_BASIS", "GLOBAL", null, null, null, "FIFO");

    // 换档等于换一条记录，否则变更历史里分不清"改值"还是"搬家"
    request(
        token,
        "PUT",
        "/identity/business-parameters/overrides/" + id,
        overrideInput("COST_BASIS", "LEGAL_ENTITY", entity, null, null, "LIFO"),
        400);
    // 换参数同样不允许
    request(
        token,
        "PUT",
        "/identity/business-parameters/overrides/" + id,
        overrideInput("INVENTORY_POLICY", "GLOBAL", null, null, null, "ALLOW_NEGATIVE"),
        400);
    // 原值未被改动
    assertThat(
            find(
                    request(token, "GET", "/identity/business-parameters/overrides", null, 200),
                    "COST_BASIS")
                .get("value")
                .asText())
        .isEqualTo("FIFO");
  }

  // ------------------------------------------------------------------
  // 冻结：改参数不改历史单据
  // ------------------------------------------------------------------

  @Test
  void frozenParametersSurviveLaterConfigurationChanges() throws Exception {
    String token = login(), tag = suffix();
    UUID entity = entity(token, "FZ" + tag);
    UUID shop = UUID.randomUUID();

    createOverride(token, "AFTERSALE_RETURN_WINDOW_DAYS", "GLOBAL", null, null, null, "30");
    createOverride(token, "AFTERSALE_RETURN_WINDOW_DAYS", "LEGAL_ENTITY", entity, null, null, "45");

    // 单据创建时冻结：此时该法人上下文下生效值是 45
    var freezeInput = new LinkedHashMap<String, Object>();
    freezeInput.put("documentType", "AFTERSALE");
    freezeInput.put("documentNo", "AS-" + tag);
    freezeInput.put("legalEntityId", entity.toString());
    freezeInput.put("shopLabel", "冻结店铺 " + tag);
    freezeInput.put("note", "售后单创建时冻结");
    UUID freezeId =
        UUID.fromString(
            request(token, "POST", "/identity/business-parameters/freezes", freezeInput, 200)
                .asText());

    var snapshot =
        request(
            token, "GET", "/identity/business-parameters/freezes/AFTERSALE/AS-" + tag, null, 200);
    assertThat(UUID.fromString(snapshot.get("id").asText())).isEqualTo(freezeId);
    var frozenValue = find(snapshot.get("values"), "AFTERSALE_RETURN_WINDOW_DAYS");
    assertThat(frozenValue.get("value").asText()).isEqualTo("45");
    assertThat(frozenValue.get("valueNumeric").decimalValue()).isEqualByComparingTo("45");
    // 快照要记录这个值是从哪一档解析出来的
    assertThat(frozenValue.get("sourceScope").asText()).isEqualTo("LEGAL_ENTITY");
    // 未被任何档覆盖的参数也进快照，取出厂默认值
    var frozenCurrency = find(snapshot.get("values"), "DISPLAY_CURRENCY");
    assertThat(frozenCurrency.get("value").asText()).isEqualTo("CNY");
    assertThat(frozenCurrency.get("sourceScope").asText()).isEqualTo("DEFAULT");
    assertThat(snapshot.get("values").size()).isEqualTo(definitions(token).size());

    // ---- 关键：之后把参数改掉 ----
    var globalOverrides =
        request(
            token,
            "GET",
            "/identity/business-parameters/overrides?parameterCode=AFTERSALE_RETURN_WINDOW_DAYS&scopeType=GLOBAL",
            null,
            200);
    request(
        token,
        "PUT",
        "/identity/business-parameters/overrides/" + globalOverrides.get(0).get("id").asText(),
        overrideInput("AFTERSALE_RETURN_WINDOW_DAYS", "GLOBAL", null, null, null, "7"),
        200);
    createOverride(token, "DISPLAY_CURRENCY", "GLOBAL", null, null, null, "USD");

    // 新单据看到的是新值
    assertThat(
            find(effective(token, entity, null), "AFTERSALE_RETURN_WINDOW_DAYS")
                .get("value")
                .asText())
        .isEqualTo("45");
    assertThat(find(effective(token, null, null), "DISPLAY_CURRENCY").get("value").asText())
        .isEqualTo("USD");

    // 旧单据读到的仍然是冻结那一刻的值，不受任何影响
    var after =
        request(
            token, "GET", "/identity/business-parameters/freezes/AFTERSALE/AS-" + tag, null, 200);
    assertThat(find(after.get("values"), "AFTERSALE_RETURN_WINDOW_DAYS").get("value").asText())
        .isEqualTo("45");
    assertThat(find(after.get("values"), "DISPLAY_CURRENCY").get("value").asText())
        .isEqualTo("CNY");

    // 重复冻结被 409 挡下：一张单据只冻结一次
    request(token, "POST", "/identity/business-parameters/freezes", freezeInput, 409);

    // 没冻结过的单据：404，而不是给一份空快照
    request(token, "GET", "/identity/business-parameters/freezes/ORDER/NO-SUCH", null, 404);
    assertThat(shop).isNotNull();
  }

  @Test
  void shopScopedFreezeCapturesTheShopLevelValue() throws Exception {
    String token = login(), tag = suffix();
    UUID shop = UUID.randomUUID();
    UUID entity = entity(token, "SF" + tag);

    createOverride(token, "COST_BASIS", "GLOBAL", null, null, null, "FIFO");
    createOverride(token, "COST_BASIS", "SHOP", null, shop, "店铺 " + tag, "LIFO");

    var freezeInput = new LinkedHashMap<String, Object>();
    freezeInput.put("documentType", "ORDER");
    freezeInput.put("documentNo", "SO-" + tag);
    freezeInput.put("legalEntityId", entity.toString());
    freezeInput.put("shopId", shop.toString());
    freezeInput.put("shopLabel", "店铺 " + tag);
    UUID freezeId =
        UUID.fromString(
            request(token, "POST", "/identity/business-parameters/freezes", freezeInput, 200)
                .asText());
    assertThat(freezeId).isNotNull();

    var snapshot =
        request(token, "GET", "/identity/business-parameters/freezes/ORDER/SO-" + tag, null, 200);
    assertThat(snapshot.get("shopId").asText()).isEqualTo(shop.toString());
    // 店铺标签被快照下来，即使后续解析时店铺已不存在也不影响可读性
    assertThat(snapshot.get("shopLabel").asText()).isEqualTo("店铺 " + tag);
    var value = find(snapshot.get("values"), "COST_BASIS");
    assertThat(value.get("value").asText()).isEqualTo("LIFO");
    assertThat(value.get("sourceScope").asText()).isEqualTo("SHOP");
  }

  @Test
  void frozenAtIsBoundAsAnInstantWithoutOffsetDrift() throws Exception {
    String token = login();
    Instant before = Instant.now();
    var freezeInput = new LinkedHashMap<String, Object>();
    freezeInput.put("documentType", "ORDER");
    freezeInput.put("documentNo", "SO-DRIFT");
    request(token, "POST", "/identity/business-parameters/freezes", freezeInput, 200);
    Instant after = Instant.now();

    var snapshot =
        request(token, "GET", "/identity/business-parameters/freezes/ORDER/SO-DRIFT", null, 200);
    OffsetDateTime frozenAt = OffsetDateTime.parse(snapshot.get("frozenAt").asText());
    assertThat(frozenAt.toInstant()).isBetween(before.minusSeconds(1), after.plusSeconds(1));
  }

  // ------------------------------------------------------------------
  // 授权
  // ------------------------------------------------------------------

  @Test
  void readingAndChangingParametersRequireTheirOwnPermissions() throws Exception {
    String owner = login(), tag = suffix();
    String role =
        request(
                owner,
                "POST",
                "/identity/roles",
                Map.of(
                    "code",
                    "NOPARAM_" + tag,
                    "name",
                    "无参数权限",
                    "status",
                    "ACTIVE",
                    "permissions",
                    List.of("identity.member.read")),
                200)
            .asText();
    // assignments 是 AssignmentInput{roleId, scopeMode}，不是裸 role id 字符串
    request(
        owner,
        "POST",
        "/identity/members",
        memberInput(
            "noparam-" + tag.toLowerCase(),
            null,
            List.of(Map.of("roleId", role, "scopeMode", "ALL"))),
        200);
    String member = loginUser("noparam-" + tag.toLowerCase());

    request(member, "GET", "/identity/business-parameters", null, 403);
    request(member, "GET", "/identity/business-parameters/effective", null, 403);
    request(member, "GET", "/identity/business-parameters/overrides", null, 403);
    request(member, "GET", "/identity/business-parameters/history", null, 403);
    request(
        member,
        "POST",
        "/identity/business-parameters/overrides",
        overrideInput("COST_BASIS", "GLOBAL", null, null, null, "FIFO"),
        403);

    // 所有者可读
    assertThat(definitions(owner)).isNotEmpty();
  }

  // ------------------------------------------------------------------
  // 多租户三断言
  // ------------------------------------------------------------------

  @Test
  void parametersAreTenantIsolatedAndHeaderForgeryIsIgnored() throws Exception {
    String owner = login(), tag = suffix();
    UUID parentEntity = entity(owner, "PT" + tag);
    createOverride(owner, "COST_BASIS", "LEGAL_ENTITY", parentEntity, null, null, "LIFO");
    createOverride(owner, "DISPLAY_CURRENCY", "GLOBAL", null, null, null, "USD");
    request(
        owner,
        "POST",
        "/identity/business-parameters/freezes",
        Map.of("documentType", "ORDER", "documentNo", "SO-PARENT-" + tag),
        200);

    // 造子租户并配一套完全不同的参数
    String childUser = "bp-child-" + tag.toLowerCase();
    request(
        owner,
        "POST",
        "/identity/children",
        Map.of(
            "code",
            "BPC" + tag,
            "name",
            "业务参数子租户",
            "ownerUsername",
            childUser,
            "ownerPassword",
            "test-member-password"),
        200);
    String childToken = loginUser(childUser);
    UUID childEntity = entity(childToken, "CT" + tag);
    createOverride(
        childToken, "COST_BASIS", "LEGAL_ENTITY", childEntity, null, null, "IMPORTED_COST");
    createOverride(childToken, "DISPLAY_CURRENCY", "GLOBAL", null, null, null, "JPY");

    // 断言一：两租户互不可见
    var parentView = effective(owner, parentEntity, null);
    assertThat(find(parentView, "COST_BASIS").get("value").asText()).isEqualTo("LIFO");
    assertThat(find(parentView, "DISPLAY_CURRENCY").get("value").asText()).isEqualTo("USD");
    assertThat(parentView.toString()).doesNotContain(childEntity.toString());

    var childView = effective(childToken, childEntity, null);
    assertThat(find(childView, "COST_BASIS").get("value").asText()).isEqualTo("IMPORTED_COST");
    assertThat(find(childView, "DISPLAY_CURRENCY").get("value").asText()).isEqualTo("JPY");
    assertThat(childView.toString()).doesNotContain(parentEntity.toString());

    // 覆盖值列表、历史、冻结同样隔离
    assertThat(
            request(owner, "GET", "/identity/business-parameters/overrides", null, 200).toString())
        .doesNotContain("CT" + tag);
    assertThat(
            request(childToken, "GET", "/identity/business-parameters/overrides", null, 200)
                .toString())
        .doesNotContain("PT" + tag);
    assertThat(request(owner, "GET", "/identity/business-parameters/history", null, 200).toString())
        .doesNotContain(childEntity.toString());

    // 冻结快照：父租户看不到子租户的单据；拿子租户单据类型/编号去父租户查必须是 404
    request(
        childToken,
        "POST",
        "/identity/business-parameters/freezes",
        Map.of("documentType", "ORDER", "documentNo", "SO-CHILD-" + tag),
        200);
    request(owner, "GET", "/identity/business-parameters/freezes/ORDER/SO-CHILD-" + tag, null, 404);
    assertThat(
            request(
                    owner,
                    "GET",
                    "/identity/business-parameters/freezes/ORDER/SO-PARENT-" + tag,
                    null,
                    200)
                .get("documentNo")
                .asText())
        .isEqualTo("SO-PARENT-" + tag);
    // 父租户改不动子租户的覆盖值：拿子租户的覆盖 id 去父租户删，必须 404
    var childOverrides =
        request(childToken, "GET", "/identity/business-parameters/overrides", null, 200);
    UUID childOverrideId = UUID.fromString(find(childOverrides, "COST_BASIS").get("id").asText());
    request(
        owner, "DELETE", "/identity/business-parameters/overrides/" + childOverrideId, null, 404);
    request(
        owner,
        "PUT",
        "/identity/business-parameters/overrides/" + childOverrideId,
        overrideInput("COST_BASIS", "LEGAL_ENTITY", parentEntity, null, null, "FIFO"),
        404);

    // 断言二：伪造 tenant header 无效。request() 每次都带随机 X-Tenant-Id。
    String forged = UUID.randomUUID().toString();
    // 伪造成一个不存在的租户 id：结果与不伪造时完全一致
    var forgedView =
        http.perform(
                get("/identity/business-parameters/effective")
                    .header("Authorization", "Bearer " + owner)
                    .header("X-Tenant-Id", forged))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forgedView).contains("USD").doesNotContain("JPY");
    // 伪造成子租户的真实租户 id（这里能拿到，因为调用方本来就是它的所有者）：结果仍然不变，
    // 证明租户确实来自已认证成员关系而不是这个头。
    var forgedChild =
        http.perform(
                get("/identity/business-parameters/effective")
                    .header("Authorization", "Bearer " + owner)
                    .header("X-Tenant-Id", tenantOf(childToken)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forgedChild).contains("USD").doesNotContain("JPY");
    // 父租户直接拿子租户的法人来查也不该看见任何东西：404，而不是悄悄退回自己的值
    http.perform(
            get("/identity/business-parameters/effective?legalEntityId=" + childEntity)
                .header("Authorization", "Bearer " + owner))
        .andExpect(status().isNotFound());
    // 未登录查不到任何参数
    http.perform(get("/identity/business-parameters/effective"))
        .andExpect(status().isUnauthorized());

    // 断言三：连接池归还后不串租户。池大小被压到 1，交替租户请求复用同一物理连接。
    for (int i = 0; i < 3; i++) {
      var parent = effective(owner, parentEntity, null);
      assertThat(find(parent, "COST_BASIS").get("value").asText())
          .as("第 %s 轮父租户不得看到子租户参数", i)
          .isEqualTo("LIFO");
      assertThat(parent.toString()).doesNotContain("IMPORTED_COST");
      var child = effective(childToken, childEntity, null);
      assertThat(find(child, "COST_BASIS").get("value").asText())
          .as("第 %s 轮子租户不得看到父租户参数", i)
          .isEqualTo("IMPORTED_COST");
      assertThat(child.toString()).doesNotContain("LIFO");
      // 冻结快照也必须跟着租户走
      assertThat(
              request(
                  owner,
                  "GET",
                  "/identity/business-parameters/freezes/ORDER/SO-CHILD-" + tag,
                  null,
                  404))
          .isNotNull();
    }

    // 事务外（无租户上下文）直查应当为空
    assertThat(sql.fetch("select id from business_parameter_override")).isEmpty();
    assertThat(sql.fetch("select id from business_parameter_freeze")).isEmpty();
    assertThat(
            tx().run(
                    tenantOf(owner), () -> sql.fetch("select id from business_parameter_override")))
        .isNotEmpty();
  }

  private UUID tenantOf(String token) throws Exception {
    return UUID.fromString(
        request(token, "GET", "/identity/tenants/current", null, 200).get("tenantId").asText());
  }
}
