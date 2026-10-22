package com.starcon.crm;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 15.1.2.2 邮件规则的集成测试。
 *
 * <p>跑的是 AGENTS.md 要求的<b>真实 PostgreSQL 16 + FORCE RLS</b>。租户隔离三断言在
 * {@link EmailRuleTenancyIntegrationTest} 里，本类专攻业务不变量：
 *
 * <ul>
 *   <li><b>规则真的落库、真的能配</b>：三个条件维度（发件人 / 关键词 / 工单标签）都能配；
 *       编码唯一且建后不可改；改规则版本 +1；停用即不参与命中。</li>
 *   <li><b>顺序显式</b>：多条规则都命中时，顺序小的赢，但**落败的规则也记进命中依据**。</li>
 *   <li><b>🔴 重要沟通命中静默规则 ⇒ 被强制降级</b>为「不静默 + 转人工」，
 *       并且判定来源（分类结果 / 规则标记）留痕。</li>
 *   <li><b>🔴 命中记录只追加</b>：DB 层改不了也删不掉。</li>
 *   <li><b>改规则不追溯改变历史命中记录</b>：命中记录冻结当时的规则版本与快照。</li>
 *   <li><b>自动分配只到「待下游取」为止</b>：不创建工单、不写工单 id；
 *       自动回复只留痕意图，<b>不谎称已发出</b>。</li>
 *   <li><b>引用不存在的工单标签当场被拒</b>（那是一条永远不会命中的规则）。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmailRuleIntegrationTest {

  static final UUID tenant = UUID.randomUUID();
  static final UUID userId = UUID.randomUUID();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", EmailRuleTestSupport.PG::getJdbcUrl);
    r.add("DB_USER", () -> "crm_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", EmailRuleTestSupport.PG::getUsername);
    r.add("DB_MIGRATION_PASSWORD", EmailRuleTestSupport.PG::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + EmailRuleTestSupport.identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @BeforeEach
  void reset() {
    EmailRuleTestSupport.truncate();
    EmailRuleTestSupport.ACTORS.put(
        "session",
        EmailRuleTestSupport.actor(userId, tenant, "Test", EmailRuleTestSupport.PERMISSIONS));
  }

  // ================================================================ 夹具

  /** 登记一封收到的邮件。 */
  UUID insertEmail(String buyerEmail, String subject, String body) {
    UUID id = UUID.randomUUID();
    EmailRuleTestSupport.insertEmail(
        sql, tenants, tenant, id, buyerEmail, subject, body, null, OffsetDateTime.now(ZoneOffset.UTC));
    return id;
  }

  /** 登记一封邮件，并给它挂上分类结论（平台合规）。 */
  UUID insertEmailWithClassification(String buyerEmail, String subject, String body, String label) {
    UUID messageId = UUID.randomUUID();
    EmailRuleTestSupport.insertMessage(sql, tenants, tenant, messageId, subject, body);
    EmailRuleTestSupport.insertClassification(
        sql, tenants, tenant, UUID.randomUUID(), messageId, label);
    UUID id = UUID.randomUUID();
    EmailRuleTestSupport.insertEmail(
        sql,
        tenants,
        tenant,
        id,
        buyerEmail,
        subject,
        body,
        messageId,
        OffsetDateTime.now(ZoneOffset.UTC));
    return id;
  }

  /** 建规则（默认：关键词 + 静默）。 */
  JsonNode createRule(
      String code, String action, int order, List<String> keywords, String extraJson) {
    String keywordsJson =
        keywords.stream().map(k -> "\"" + k + "\"").collect(java.util.stream.Collectors.joining(","));
    // 动作载荷：自动分配必须有分配目标，自动回复必须有话术（服务层会拒「没有载荷」的动作）。
    String payload = extraJson == null ? "" : extraJson;
    if ("AUTO_ASSIGN".equals(action) && !payload.contains("assignTargetCode")) {
      payload = join(payload, "\"assignTargetType\":\"TEAM\",\"assignTargetCode\":\"GROUP-A\"");
    }
    if ("AUTO_REPLY".equals(action) && !payload.contains("replyTemplateCode")) {
      payload = join(payload, "\"replyTemplateCode\":\"T-REPLY\"");
    }
    String body =
        "{\"ruleCode\":\""
            + code
            + "\",\"ruleName\":\"规则 "
            + code
            + "\",\"priorityOrder\":"
            + order
            + ",\"action\":\""
            + action
            + "\",\"keywords\":["
            + keywordsJson
            + "],\"matchMode\":\"ANY\",\"enabled\":true"
            + (payload.isEmpty() ? "" : "," + payload)
            + "}";
    MvcResult result =
        perform(
            () ->
                http.perform(
                        post("/crm/email-rules/rules")
                            .header("Authorization", "session")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andReturn());
    return read(result);
  }

  /** 评估一封邮件。 */
  JsonNode evaluate(UUID emailId) {
    MvcResult result =
        perform(
            () ->
                http.perform(
                        post("/crm/email-rules/emails/" + emailId + "/evaluate")
                            .header("Authorization", "session")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andReturn());
    return read(result);
  }

  /** 从命中依据里按规则编码取出那一条匹配。 */
  static JsonNode matchOf(JsonNode basis, String ruleCode) {
    for (var match : basis.path("matches")) {
      if (ruleCode.equals(match.path("ruleCode").asText())) {
        return match;
      }
    }
    throw new AssertionError("命中依据里没有规则 " + ruleCode + " 的匹配记录");
  }

  /** 拼接附加 JSON 片段。 */
  static String join(String left, String right) {
    return left.isEmpty() ? right : left + "," + right;
  }

  /** 读 JSON 响应。 */
  JsonNode read(MvcResult result) {
    try {
      return EmailRuleTestSupport.JSON.readTree(result.getResponse().getContentAsString());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 执行一次 HTTP 调用并把异常转成断言失败。 */
  MvcResult perform(java.util.concurrent.Callable<MvcResult> call) {
    try {
      return call.call();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // ==========================================================================
  // 规则配置
  // ==========================================================================

  @Test
  void 规则真的落库_改规则版本加一_编码建后不可改() throws Exception {
    JsonNode created = createRule("R-1", "AUTO_ASSIGN", 10, List.of("refund"), null);
    String ruleId = created.path("rule").path("id").asText();
    assertThat(ruleId).isNotBlank();
    assertThat(created.path("rule").path("version").asInt()).isEqualTo(1);
    assertThat(created.path("rule").path("priorityOrder").asInt()).isEqualTo(10);

    // 列表里能看到（真落库，不是内存里的假数据）。
    MvcResult list =
        perform(
            () ->
                http.perform(get("/crm/email-rules/rules").header("Authorization", "session"))
                    .andExpect(status().isOk())
                    .andReturn());
    JsonNode page = read(list);
    assertThat(page.path("total").asLong()).isEqualTo(1);
    assertThat(page.path("items").get(0).path("ruleCode").asText()).isEqualTo("R-1");

    // 改规则 ⇒ 版本 +1。
    MvcResult updated =
        perform(
            () ->
                http.perform(
                        put("/crm/email-rules/rules/" + ruleId)
                            .header("Authorization", "session")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                "{\"ruleCode\":\"R-1\",\"ruleName\":\"改名了\",\"priorityOrder\":20,"
                                    + "\"action\":\"AUTO_ASSIGN\",\"keywords\":[\"refund\"],"
                                    + "\"assignTargetType\":\"TEAM\",\"assignTargetCode\":\"VIP\","
                                    + "\"enabled\":true}"))
                    .andExpect(status().isOk())
                    .andReturn());
    JsonNode updatedRule = read(updated).path("rule");
    assertThat(updatedRule.path("version").asInt()).isEqualTo(2);
    assertThat(updatedRule.path("priorityOrder").asInt()).isEqualTo(20);
    assertThat(updatedRule.path("assignTargetCode").asText()).isEqualTo("VIP");

    // 编码建后不可改。
    http.perform(
            put("/crm/email-rules/rules/" + ruleId)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"ruleCode\":\"R-2\",\"ruleName\":\"改名了\",\"priorityOrder\":20,"
                        + "\"action\":\"AUTO_ASSIGN\",\"keywords\":[\"refund\"],"
                        + "\"assignTargetCode\":\"VIP\",\"enabled\":true}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 规则必须至少有一个条件维度_引用不存在的工单标签当场被拒() throws Exception {
    // 全空维度：一条永远不会命中的规则。
    http.perform(
            post("/crm/email-rules/rules")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"ruleCode\":\"EMPTY\",\"ruleName\":\"空规则\",\"priorityOrder\":1,"
                        + "\"action\":\"SILENCE\",\"keywords\":[],\"enabled\":true}"))
        .andExpect(status().isBadRequest());

    // 引用不存在的工单标签码。
    http.perform(
            post("/crm/email-rules/rules")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"ruleCode\":\"BADTAG\",\"ruleName\":\"坏标签\",\"priorityOrder\":1,"
                        + "\"action\":\"SILENCE\",\"ticketTagCodes\":[\"NOPE\"],\"enabled\":true}"))
        .andExpect(status().isBadRequest());

    // 标签字典里真实存在的标签码才能被引用。
    http.perform(
            post("/crm/email-rules/tags")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tagCode\":\"VIP\",\"name\":\"VIP 客户\"}"))
        .andExpect(status().isOk());
    createRule("GOODTAG", "SILENCE", 1, List.of(), "\"ticketTagCodes\":[\"VIP\"]");
  }

  // ==========================================================================
  // 三个条件维度
  // ==========================================================================

  @Test
  void 发件人维度命中_关键词维度命中_工单标签维度命中() throws Exception {
    // 工单标签字典 + 打标。
    http.perform(
            post("/crm/email-rules/tags")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tagCode\":\"VIP\",\"name\":\"VIP 客户\"}"))
        .andExpect(status().isOk());
    UUID emailId = insertEmail("vip@example.com", "退款咨询", "我要退款");
    http.perform(
            post("/crm/email-rules/emails/" + emailId + "/tags")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tagCode\":\"VIP\"}"))
        .andExpect(status().isOk());

    createRule("BY-SENDER", "AUTO_ASSIGN", 10, List.of(), "\"senderEmails\":[\"vip@example.com\"]");
    createRule("BY-KEYWORD", "AUTO_ASSIGN", 20, List.of("退款"), null);
    createRule("BY-TAG", "AUTO_ASSIGN", 30, List.of(), "\"ticketTagCodes\":[\"VIP\"]");

    // 顺序最小的那条赢；三条其实都命中（发件人 + 关键词 + 标签）。
    JsonNode result = evaluate(emailId);
    assertThat(result.path("matched").asBoolean()).isTrue();
    assertThat(result.path("ruleCode").asText()).isEqualTo("BY-SENDER");

    // 命中依据里三条都在，winner 是第一条。
    MvcResult hits =
        perform(
            () ->
                http.perform(
                        get("/crm/email-rules/hits").param("emailId", emailId.toString())
                            .header("Authorization", "session"))
                    .andExpect(status().isOk())
                    .andReturn());
    JsonNode basis = read(hits).path("items").get(0).path("matchBasis");
    assertThat(basis.path("matches").size()).isEqualTo(3);
    assertThat(basis.path("winningRuleCode").asText()).isEqualTo("BY-SENDER");

    // 逐维度的命中明细都在（发件人 / 关键词 / 工单标签各命中了什么）。
    JsonNode bySender = matchOf(basis, "BY-SENDER");
    JsonNode byKeyword = matchOf(basis, "BY-KEYWORD");
    JsonNode byTag = matchOf(basis, "BY-TAG");
    assertThat(bySender.path("conditions").path("sender").path("matchedEmails").get(0).asText())
        .isEqualTo("vip@example.com");
    assertThat(byKeyword.path("conditions").path("keywords").path("matchedKeywords").get(0).asText())
        .isEqualTo("退款");
    assertThat(byTag.path("conditions").path("ticketTags").path("matchedCodes").get(0).asText())
        .isEqualTo("VIP");
  }

  @Test
  void 停用的规则不参与命中_试算不写命中记录() throws Exception {
    UUID emailId = insertEmail("buyer@example.com", "退款", "退款");
    JsonNode created =
        createRule("R-OFF", "AUTO_REPLY", 10, List.of("退款"), "\"replyTemplateCode\":\"T-REPLY\"");
    String ruleId = created.path("rule").path("id").asText();

    // 试算：命中，但不写命中记录。
    http.perform(
            post("/crm/email-rules/rules/" + ruleId + "/preview")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"emailId\":\"" + emailId + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.matched").value(true));
    MvcResult hits =
        perform(
            () ->
                http.perform(get("/crm/email-rules/hits").header("Authorization", "session"))
                    .andExpect(status().isOk())
                    .andReturn());
    assertThat(read(hits).path("total").asLong()).as("试算不许写命中记录").isZero();

    // 停用 ⇒ 不参与命中。
    http.perform(
            put("/crm/email-rules/rules/" + ruleId)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"ruleCode\":\"R-OFF\",\"ruleName\":\"规则\",\"priorityOrder\":10,"
                        + "\"action\":\"AUTO_REPLY\",\"keywords\":[\"退款\"],"
                        + "\"replyTemplateCode\":\"T1\",\"enabled\":false}"))
        .andExpect(status().isOk());
    JsonNode result = evaluate(emailId);
    assertThat(result.path("matched").asBoolean()).as("停用的规则不参与命中").isFalse();
  }

  // ==========================================================================
  // 🔴 静默规则与重要沟通
  // ==========================================================================

  @Test
  void 普通邮件命中静默规则_真的静默且留痕() throws Exception {
    UUID emailId = insertEmail("buyer@example.com", "促销咨询", "有活动吗");
    createRule("S-1", "SILENCE", 10, List.of("促销"), null);

    JsonNode result = evaluate(emailId);
    assertThat(result.path("matched").asBoolean()).isTrue();
    assertThat(result.path("action").asText()).isEqualTo("SILENCE");
    assertThat(result.path("effectiveAction").asText()).isEqualTo("SILENCE");
    assertThat(result.path("silenceRefused").asBoolean()).isFalse();
    assertThat(result.path("important").asBoolean()).isFalse();

    // 命中记录真的留痕了（只追加）。
    MvcResult hits =
        perform(
            () ->
                http.perform(get("/crm/email-rules/hits").header("Authorization", "session"))
                    .andExpect(status().isOk())
                    .andReturn());
    assertThat(read(hits).path("total").asLong()).isEqualTo(1);
  }

  @Test
  void 关键_重要沟通命中静默规则_被强制降级为不静默加转人工() throws Exception {
    // 上游分类结论 = 平台合规（重要沟通的可判定标识）。
    UUID emailId =
        insertEmailWithClassification("buyer@example.com", "促销咨询", "有活动吗", "PLATFORM_COMPLIANCE");
    createRule("S-2", "SILENCE", 10, List.of("促销"), null);

    JsonNode result = evaluate(emailId);
    assertThat(result.path("matched").asBoolean()).isTrue();
    assertThat(result.path("action").asText()).as("规则声明的动作仍是静默").isEqualTo("SILENCE");
    assertThat(result.path("effectiveAction").asText())
        .as("🔴 实际生效的动作必须是「不静默 + 转人工」")
        .isEqualTo("ESCALATE_MANUAL");
    assertThat(result.path("silenceRefused").asBoolean()).isTrue();
    assertThat(result.path("refusalReason").asText()).contains("重要沟通");
    assertThat(result.path("important").asBoolean()).isTrue();
    assertThat(result.path("importantSource").asText()).isEqualTo("CLASSIFICATION");
    assertThat(result.path("classificationLabel").asText()).isEqualTo("PLATFORM_COMPLIANCE");

    // 命中记录里也留痕（判定来源 + 被拒理由）。
    MvcResult hits =
        perform(
            () ->
                http.perform(get("/crm/email-rules/hits").header("Authorization", "session"))
                    .andExpect(status().isOk())
                    .andReturn());
    JsonNode hit = read(hits).path("items").get(0);
    assertThat(hit.path("effectiveAction").asText()).isEqualTo("ESCALATE_MANUAL");
    assertThat(hit.path("silenceRefused").asBoolean()).isTrue();
    assertThat(hit.path("importantSource").asText()).isEqualTo("CLASSIFICATION");
    assertThat(hit.path("refusalReason").asText()).isNotBlank();
  }

  @Test
  void 规则标记的关键字也能判成重要沟通() throws Exception {
    UUID emailId = insertEmail("buyer@example.com", "attorney letter", "We will contact our lawyer");
    createRule(
        "S-3",
        "SILENCE",
        10,
        List.of("letter"),
        "\"importantMarkers\":[\"attorney\",\"lawyer\"]");

    JsonNode result = evaluate(emailId);
    assertThat(result.path("important").asBoolean()).isTrue();
    assertThat(result.path("importantSource").asText()).isEqualTo("RULE_MARKER");
    assertThat(result.path("effectiveAction").asText()).isEqualTo("ESCALATE_MANUAL");
    assertThat(result.path("silenceRefused").asBoolean()).isTrue();
  }

  @Test
  void 关键_命中记录只追加_改不了也删不掉() {
    UUID emailId = insertEmail("buyer@example.com", "促销", "促销");
    createRule("S-4", "SILENCE", 10, List.of("促销"), null);
    evaluate(emailId);

    assertThatThrownBy(
            () ->
                EmailRuleTestSupport.asMigrationUser(
                    "update crm_email_rule_hit set effective_action = 'SILENCE'"))
        .as("命中记录改不了")
        .hasMessageContaining("只追加");
    assertThatThrownBy(() -> EmailRuleTestSupport.asMigrationUser("delete from crm_email_rule_hit"))
        .as("命中记录删不掉")
        .hasMessageContaining("只追加");
  }

  // ==========================================================================
  // 快照冻结与幂等
  // ==========================================================================

  @Test
  void 改规则不追溯改变历史命中记录() throws Exception {
    UUID emailId = insertEmail("buyer@example.com", "退款", "退款");
    JsonNode created =
        createRule("R-SNAP", "AUTO_REPLY", 10, List.of("退款"), "\"replyTemplateCode\":\"T-REPLY\"");
    String ruleId = created.path("rule").path("id").asText();
    evaluate(emailId);

    // 改规则（关键词换掉 + 停用 + 版本 +1）。
    http.perform(
            put("/crm/email-rules/rules/" + ruleId)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"ruleCode\":\"R-SNAP\",\"ruleName\":\"改过的规则\",\"priorityOrder\":10,"
                        + "\"action\":\"AUTO_REPLY\",\"keywords\":[\"售后\"],"
                        + "\"replyTemplateCode\":\"T2\",\"enabled\":false}"))
        .andExpect(status().isOk());

    // 历史命中记录仍然是**当时**那一版（版本 1 + 当时的关键词）。
    MvcResult hits =
        perform(
            () ->
                http.perform(get("/crm/email-rules/hits").header("Authorization", "session"))
                    .andExpect(status().isOk())
                    .andReturn());
    JsonNode hit = read(hits).path("items").get(0);
    assertThat(hit.path("ruleVersion").asInt()).as("冻结的是当时的版本").isEqualTo(1);
    assertThat(hit.path("ruleName").asText()).isEqualTo("规则 R-SNAP");

    MvcResult detail =
        perform(
            () ->
                http.perform(
                        get("/crm/email-rules/hits/" + hit.path("id").asText())
                            .header("Authorization", "session"))
                    .andExpect(status().isOk())
                    .andReturn());
    JsonNode snapshot = read(detail).path("ruleSnapshot");
    assertThat(snapshot.path("keywords").get(0).asText()).isEqualTo("退款");
    assertThat(snapshot.path("version").asInt()).isEqualTo(1);
  }

  @Test
  void 同一版规则重复评估不重复记账() throws Exception {
    UUID emailId = insertEmail("buyer@example.com", "退款", "退款");
    createRule("R-IDEM", "AUTO_REPLY", 10, List.of("退款"), "\"replyTemplateCode\":\"T-REPLY\"");

    JsonNode first = evaluate(emailId);
    assertThat(first.path("idempotent").asBoolean()).isFalse();
    JsonNode second = evaluate(emailId);
    assertThat(second.path("idempotent").asBoolean()).isTrue();
    assertThat(second.path("hitId").asText()).isEqualTo(first.path("hitId").asText());

    MvcResult hits =
        perform(
            () ->
                http.perform(get("/crm/email-rules/hits").header("Authorization", "session"))
                    .andExpect(status().isOk())
                    .andReturn());
    assertThat(read(hits).path("total").asLong()).isEqualTo(1);
  }

  // ==========================================================================
  // 自动分配结果 / 自动回复意图
  // ==========================================================================

  @Test
  void 自动分配产出分配结果_但绝不写工单id() throws Exception {
    UUID emailId = insertEmail("buyer@example.com", "退款", "退款");
    createRule(
        "A-1",
        "AUTO_ASSIGN",
        10,
        List.of("退款"),
        "\"assignTargetType\":\"TEAM\",\"assignTargetCode\":\"VIP-GROUP\"");

    JsonNode result = evaluate(emailId);
    assertThat(result.path("effectiveAction").asText()).isEqualTo("AUTO_ASSIGN");
    assertThat(result.path("assignTargetCode").asText()).isEqualTo("VIP-GROUP");
    assertThat(result.path("assignmentId").asText()).isNotBlank();

    MvcResult assignments =
        perform(
            () ->
                http.perform(get("/crm/email-rules/assignments").header("Authorization", "session"))
                    .andExpect(status().isOk())
                    .andReturn());
    JsonNode page = read(assignments);
    assertThat(page.path("total").asLong()).isEqualTo(1);
    JsonNode assignment = page.path("items").get(0);
    assertThat(assignment.path("assignTargetCode").asText()).isEqualTo("VIP-GROUP");
    assertThat(assignment.path("status").asText())
        .as("下游 15.4.1.1 未交付 ⇒ 只能是「等下游来取」")
        .isEqualTo("PENDING_DISPATCH");

    // 🔴 库里**没有任何**工单 id（下游没交付就没有 id，编一个会让「已转工单」看起来发生过）。
    var columns =
        sql.fetch(
            "select column_name from information_schema.columns where table_name = 'crm_email_rule_assignment'");
    for (var column : columns) {
      assertThat(column.get(0, String.class).toLowerCase())
          .as("分配结果里不许出现工单 id 列")
          .doesNotContain("ticket");
    }
  }

  @Test
  void 自动回复只留痕意图_不谎称已发出() throws Exception {
    UUID emailId = insertEmail("buyer@example.com", "退款", "退款");
    createRule(
        "R-REPLY",
        "AUTO_REPLY",
        10,
        List.of("退款"),
        "\"replyTemplateCode\":\"T-REPLY\",\"replySubject\":\"已收到\",\"replyBody\":\"我们尽快处理\"");

    JsonNode result = evaluate(emailId);
    assertThat(result.path("effectiveAction").asText()).isEqualTo("AUTO_REPLY");

    MvcResult hits =
        perform(
            () ->
                http.perform(get("/crm/email-rules/hits").header("Authorization", "session"))
                    .andExpect(status().isOk())
                    .andReturn());
    JsonNode hit = read(hits).path("items").get(0);
    assertThat(hit.path("deliveryStatus").asText())
        .as("15.5.1 自动回复未交付 ⇒ 只能是「等下游投递」，不能写成已发出")
        .isEqualTo("PENDING_DOWNSTREAM");
  }

  // ==========================================================================
  // 列表、筛选与统计
  // ==========================================================================

  @Test
  void 命中记录真分页真筛选_统计口径正确() throws Exception {
    UUID emailA = insertEmail("a@example.com", "促销", "促销");
    UUID emailB = insertEmailWithClassification("b@example.com", "促销", "促销", "COMPLAINT");
    createRule("S-5", "SILENCE", 10, List.of("促销"), null);
    evaluate(emailA);
    evaluate(emailB);

    // 真分页。
    MvcResult page1 =
        perform(
            () ->
                http.perform(
                        get("/crm/email-rules/hits").param("size", "1").param("page", "0")
                            .header("Authorization", "session"))
                    .andExpect(status().isOk())
                    .andReturn());
    JsonNode p1 = read(page1);
    assertThat(p1.path("total").asLong()).isEqualTo(2);
    assertThat(p1.path("items").size()).isEqualTo(1);
    assertThat(p1.path("size").asInt()).isEqualTo(1);

    // 真筛选：只看「静默被拒」的。
    MvcResult refused =
        perform(
            () ->
                http.perform(
                        get("/crm/email-rules/hits").param("silenceRefused", "true")
                            .header("Authorization", "session"))
                    .andExpect(status().isOk())
                    .andReturn());
    assertThat(read(refused).path("total").asLong()).isEqualTo(1);

    // 统计：静默被拒 1 条、待下游取 0 条。
    MvcResult stats =
        perform(
            () ->
                http.perform(get("/crm/email-rules/statistics").header("Authorization", "session"))
                    .andExpect(status().isOk())
                    .andReturn());
    JsonNode statistics = read(stats);
    assertThat(statistics.path("totalHitCount").asLong()).isEqualTo(2);
    assertThat(statistics.path("refusedCount").asLong()).isEqualTo(1);
    assertThat(statistics.path("importantCount").asLong()).isEqualTo(1);
    assertThat(statistics.path("byRule").size()).isEqualTo(1);
  }

  @Test
  void 时间字段用timestamptz写入后写回的是同一绝对时刻() throws Exception {
    UUID emailId = insertEmail("buyer@example.com", "退款", "退款");
    createRule("A-2", "AUTO_ASSIGN", 10, List.of("退款"), "\"assignTargetCode\":\"GROUP-A\"");
    evaluate(emailId);

    // 用 +08:00 的时刻写进去，读回来必须是**同一绝对时刻**（不是本地墙钟）。
    // ⚠️ 命中记录是**只追加**的（UPDATE 会被触发器拒），所以时间断言做在**写入路径**上：
    // 新建一封邮件并用带时区的时刻入库，读回同一条记录比较绝对时刻。
    OffsetDateTime moment = OffsetDateTime.of(2026, 3, 4, 12, 30, 0, 0, ZoneOffset.ofHours(8));
    UUID emailId2 = UUID.randomUUID();
    EmailRuleTestSupport.insertEmail(
        sql,
        tenants,
        tenant,
        emailId2,
        "z@example.com",
        "退款",
        "退款",
        null,
        moment);
    String stored =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                        "select received_at from crm_buyer_email where id = ?", emailId2)
                    .get("received_at", OffsetDateTime.class)
                    .toString());
    assertThat(OffsetDateTime.parse(stored).toInstant())
        .as("timestamptz 写回的是同一绝对时刻（用 Timestamp.valueOf 会静默偏 −28800 秒）")
        .isEqualTo(moment.toInstant());
  }
}
