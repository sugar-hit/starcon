package com.starcon.crm;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
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
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 15.1.1.3 消息分类的集成测试。
 *
 * <p>跑的是 AGENTS.md 要求的<b>真实 PostgreSQL 16 + FORCE RLS</b>。租户隔离三断言在
 * {@link MessageClassificationTenancyIntegrationTest} 里，本类专攻业务不变量：
 *
 * <ul>
 *   <li><b>规则真的落库、真的能配</b>：关键词必填（一条没有关键词的规则永远不会命中，
 *       却会占掉一个顺序位）、编码唯一且建后不可改、停用即不参与命中。</li>
 *   <li><b>命中依据可复核</b>：命中了哪条规则的哪个关键词、在哪个字段命中，都落库并返回。</li>
 *   <li><b>顺序显式</b>：多条规则都命中时，顺序小的赢，但**落败的规则也记进命中依据** ——
 *       只记赢家就看不出「为什么不是它」。</li>
 *   <li><b>🔴 不覆写人工设过的优先级</b>：建议值与人工值不一致时，
 *       <b>生效值仍是人工值</b>，冲突如实标记，且 {@code crm_message.priority} 一个字都没改。</li>
 *   <li><b>🔴 人工纠正只追加</b>：纠正**不改写**分类结论，自带机器预测快照，
 *       并且 DB 层**改不了也删不掉**。</li>
 *   <li><b>纠正回流成训练样本</b>：训练样本带「机器预测 + 人工纠正 + 消息正文」三件套。</li>
 *   <li><b>下游未交付如实标</b>：15.5.1 自动回复与 1.3 预警在能力声明里 available=false。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class MessageClassificationIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID userId = UUID.randomUUID();
  static final UUID shopId = UUID.randomUUID();
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role crm_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    List<String> permissions = List.of("crm.classification.read", "crm.classification.update");
    actors.put(
        "session",
        new AccessService.Access(
            userId,
            tenant,
            "Test",
            UUID.randomUUID(),
            null,
            permissions,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions))));
    try {
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
            byte[] body = json.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      identity.setExecutor(Executors.newFixedThreadPool(2));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "crm_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table crm_message_classification_feedback, crm_message_classification,"
              + " crm_message_classification_rule, crm_message_attachment, crm_message_intent,"
              + " crm_message");
    }
  }

  /** 登记一条消息（15.1.1.1 的上游事实，同库同服务，直接写库）。 */
  UUID insertMessage(String subject, String body, String platform, String type, String priority) {
    UUID id = UUID.randomUUID();
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into crm_message"
                  + "(id,tenant_id,message_no,shop_id,shop_name,platform_code,buyer_reference,"
                  + " order_reference,subject,body,direction,message_type,priority,read_status,"
                  + " platform_time,attachment_count,source,created_at,updated_at)"
                  + " values (?,?,?,?,'测试店铺',?,'buyer-1','SO-1',?,?,'INBOUND',?,?,'UNREAD',"
                  + " ?::timestamptz,0,'MANUAL_ENTRY',?::timestamptz,?::timestamptz)",
              id,
              tenant,
              "MSG-" + id.toString().substring(0, 8),
              shopId,
              platform,
              subject,
              body,
              type,
              priority,
              OffsetDateTime.parse("2026-06-01T09:30:00+08:00"),
              OffsetDateTime.now(ZoneOffset.UTC),
              OffsetDateTime.now(ZoneOffset.UTC));
          return null;
        });
    return id;
  }

  /** 建一条规则。 */
  JsonNode createRule(
      String code,
      int order,
      String label,
      String priority,
      boolean autoReply,
      List<String> keywords,
      String matchMode,
      List<String> platformCodes) throws Exception {
    return createRule(code, order, label, priority, autoReply, keywords, matchMode, platformCodes, List.of());
  }

  /** 建一条规则（可限定适用消息类型）。 */
  JsonNode createRule(
      String code,
      int order,
      String label,
      String priority,
      boolean autoReply,
      List<String> keywords,
      String matchMode,
      List<String> platformCodes,
      List<String> messageTypes) throws Exception {
    String body =
        json.writeValueAsString(
            Map.ofEntries(
                Map.entry("ruleCode", code),
                Map.entry("ruleName", "规则 " + code),
                Map.entry("priorityOrder", order),
                Map.entry("targetLabel", label),
                Map.entry("suggestedPriority", priority),
                Map.entry("autoReplyEligible", autoReply),
                Map.entry("keywords", keywords),
                Map.entry("matchMode", matchMode),
                Map.entry("platformCodes", platformCodes),
                Map.entry("messageTypes", messageTypes),
                Map.entry("enabled", true),
                Map.entry("note", "")));
    MvcResult result =
        http.perform(
                post("/crm/message-classifications/rules")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString()).get("rule");
  }

  /** 分类一条消息。 */
  JsonNode classify(UUID messageId) throws Exception {
    MvcResult result =
        http.perform(
                post("/crm/message-classifications/classify")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("messageId", messageId.toString()))))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString());
  }

  /** 人工纠正。 */
  JsonNode correct(UUID messageId, String label, String priority, boolean autoReply, String reason)
      throws Exception {
    MvcResult result =
        http.perform(
                post("/crm/message-classifications/feedback")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "messageId", messageId.toString(),
                                "correctedLabel", label,
                                "correctedPriority", priority,
                                "correctedAutoReplyEligible", autoReply,
                                "reason", reason))))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString());
  }

  /** 库里某条消息的人工优先级（用来证明分类**没有**改它）。 */
  String storedPriority(UUID messageId) {
    return tenants.run(
        tenant,
        () -> {
          var row =
              sql.fetchOne(
                  "select priority from crm_message where tenant_id = ? and id = ?",
                  tenant,
                  messageId);
          return row == null ? null : row.get(0, String.class);
        });
  }

  // ==========================================================================
  // 能力声明：未交付的下游如实标
  // ==========================================================================
  @Test
  void 能力声明把未交付的下游如实标成不可用() throws Exception {
    MvcResult result =
        http.perform(
                get("/crm/message-classifications/capabilities").header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode body = json.readTree(result.getResponse().getContentAsString());
    Map<String, JsonNode> byDimension = new java.util.HashMap<>();
    for (JsonNode dimension : body.get("dimensions")) {
      byDimension.put(dimension.get("dimension").asText(), dimension);
    }
    assertThat(byDimension).containsKeys("auto_reply", "alert", "rule_configuration", "training_sample");
    assertThat(byDimension.get("auto_reply").get("available").asBoolean())
        .as("15.5.1 自动回复未交付 ⇒ 必须 available=false")
        .isFalse();
    assertThat(byDimension.get("auto_reply").get("ownerModule").asText()).isEqualTo("15.5.1");
    assertThat(byDimension.get("alert").get("available").asBoolean())
        .as("1.3 预警未交付 ⇒ 必须 available=false")
        .isFalse();
    assertThat(byDimension.get("alert").get("ownerModule").asText()).isEqualTo("1.3");
    assertThat(byDimension.get("rule_configuration").get("available").asBoolean()).isTrue();
    assertThat(byDimension.get("training_sample").get("available").asBoolean()).isTrue();
    // 影响说明必须说清「可自动回复」只是判定结果。
    assertThat(byDimension.get("auto_reply").get("impact").asText()).contains("不触发");
  }

  // ==========================================================================
  // 规则：真的落库、真的能配
  // ==========================================================================
  @Test
  void 规则真的落库() throws Exception {
    JsonNode rule =
        createRule("LOG-001", 10, "LOGISTICS", "HIGH", true, List.of("tracking", "运单"), "ANY", List.of());
    assertThat(rule.get("ruleCode").asText()).isEqualTo("LOG-001");
    assertThat(rule.get("keywords").size()).isEqualTo(2);
    assertThat(rule.get("priorityOrder").asInt()).isEqualTo(10);

    var rows =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                    "select rule_code, keywords, priority_order, target_label, suggested_priority,"
                        + " auto_reply_eligible from crm_message_classification_rule"
                        + " where tenant_id = ?",
                    tenant));
    assertThat(rows).as("规则必须真的落在 PostgreSQL 里").hasSize(1);
    assertThat(rows.get(0).get("rule_code", String.class)).isEqualTo("LOG-001");
    assertThat(rows.get(0).get("keywords", String.class)).contains("tracking");
    assertThat(rows.get(0).get("suggested_priority", String.class)).isEqualTo("HIGH");
    assertThat(rows.get(0).get("auto_reply_eligible", Boolean.class)).isTrue();
  }

  @Test
  void 没有关键词的规则被拒() throws Exception {
    // 一条没有关键词的规则永远不会命中，却会占掉一个顺序位 —— 那是「看起来配了、其实没生效」。
    http.perform(
            post("/crm/message-classifications/rules")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "ruleCode", "EMPTY-001",
                            "ruleName", "空规则",
                            "priorityOrder", 1,
                            "targetLabel", "SPAM",
                            "suggestedPriority", "LOW",
                            "keywords", List.of("  "),
                            "matchMode", "ANY",
                            "platformCodes", List.of(),
                            "messageTypes", List.of()))))
        .andExpect(status().isBadRequest());
    var count =
        tenants.run(
            tenant, () -> sql.fetch("select id from crm_message_classification_rule").size());
    assertThat(count).as("被拒的规则不能落库").isZero();
  }

  @Test
  void 规则编码重复被拒() throws Exception {
    createRule("DUP-001", 1, "SPAM", "LOW", false, List.of("spam"), "ANY", List.of());
    http.perform(
            post("/crm/message-classifications/rules")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "ruleCode", "DUP-001",
                            "ruleName", "重名",
                            "priorityOrder", 2,
                            "targetLabel", "SPAM",
                            "suggestedPriority", "LOW",
                            "keywords", List.of("spam"),
                            "matchMode", "ANY",
                            "platformCodes", List.of(),
                            "messageTypes", List.of()))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 规则编码建后不可改() throws Exception {
    JsonNode rule = createRule("FIX-001", 1, "SPAM", "LOW", false, List.of("spam"), "ANY", List.of());
    String id = rule.get("id").asText();
    http.perform(
            put("/crm/message-classifications/rules/" + id)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "ruleCode", "FIX-002",
                            "ruleName", "改编码",
                            "priorityOrder", 1,
                            "targetLabel", "SPAM",
                            "suggestedPriority", "LOW",
                            "keywords", List.of("spam"),
                            "matchMode", "ANY",
                            "platformCodes", List.of(),
                            "messageTypes", List.of()))))
        .andExpect(status().isBadRequest());
    var rows =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                    "select rule_code from crm_message_classification_rule where tenant_id = ?",
                    tenant));
    assertThat(rows.get(0).get("rule_code", String.class)).isEqualTo("FIX-001");
  }

  @Test
  void 停用的规则不参与命中但还在库里() throws Exception {
    JsonNode rule = createRule("OFF-001", 1, "SPAM", "LOW", false, List.of("spam"), "ANY", List.of());
    String id = rule.get("id").asText();
    http.perform(
            put("/crm/message-classifications/rules/" + id)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "ruleCode", "OFF-001",
                            "ruleName", "规则 OFF-001",
                            "priorityOrder", 1,
                            "targetLabel", "SPAM",
                            "suggestedPriority", "LOW",
                            "keywords", List.of("spam"),
                            "matchMode", "ANY",
                            "platformCodes", List.of(),
                            "messageTypes", List.of(),
                            "enabled", false))))
        .andExpect(status().isOk());

    UUID messageId = insertMessage("主题", "this is spam", "AMAZON", "BUYER_MESSAGE", "NORMAL");
    JsonNode result = classify(messageId);
    assertThat(result.get("label").asText())
        .as("停用的规则不参与命中")
        .isEqualTo("UNCLASSIFIED");

    var rows =
        tenants.run(
            tenant,
            () -> sql.fetch("select id from crm_message_classification_rule where tenant_id = ?", tenant));
    assertThat(rows).as("停用不是删除：历史命中依据要查得到规则").hasSize(1);
  }

  // ==========================================================================
  // 分类：标签 / 建议优先级 / 是否可自动回复 / 命中依据
  // ==========================================================================
  @Test
  void 命中规则给出标签建议优先级与可自动回复() throws Exception {
    createRule("LOG-001", 10, "LOGISTICS", "HIGH", true, List.of("tracking"), "ANY", List.of());
    UUID messageId = insertMessage("物流查询", "where is my tracking number", "AMAZON", "BUYER_MESSAGE", "NORMAL");
    JsonNode result = classify(messageId);
    assertThat(result.get("success").asBoolean()).isTrue();
    assertThat(result.get("label").asText()).isEqualTo("LOGISTICS");
    assertThat(result.get("suggestedPriority").asText()).isEqualTo("HIGH");
    assertThat(result.get("autoReplyEligible").asBoolean()).isTrue();
    assertThat(result.get("matchedRuleCode").asText()).isEqualTo("LOG-001");

    // 命中依据：命中了哪条规则的哪个关键词、在哪个字段命中，都落库并返回。
    MvcResult detail =
        http.perform(
                get("/crm/message-classifications/messages/" + messageId)
                    .header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode body = json.readTree(detail.getResponse().getContentAsString());
    JsonNode basis = body.get("classification").get("matchBasis");
    assertThat(basis.get("winningRuleCode").asText()).isEqualTo("LOG-001");
    JsonNode matches = basis.get("matches");
    assertThat(matches.size()).isEqualTo(1);
    assertThat(matches.get(0).get("ruleCode").asText()).isEqualTo("LOG-001");
    assertThat(matches.get(0).get("won").asBoolean()).isTrue();
    assertThat(matches.get(0).get("keywords").get(0).get("keyword").asText()).isEqualTo("tracking");
    assertThat(matches.get(0).get("keywords").get(0).get("field").asText()).isEqualTo("BODY");
  }

  @Test
  void 没命中任何规则记为未分类且不建议自动回复() throws Exception {
    UUID messageId = insertMessage("随便聊聊", "hello there", "AMAZON", "BUYER_MESSAGE", "NORMAL");
    JsonNode result = classify(messageId);
    assertThat(result.get("label").asText()).isEqualTo("UNCLASSIFIED");
    assertThat(result.get("autoReplyEligible").asBoolean())
        .as("说不清是什么的消息自动回，风险由买家承担")
        .isFalse();
    assertThat(result.get("reason").asText()).contains("没有规则命中");
  }

  @Test
  void 顺序小的规则先赢但落败的规则也记进命中依据() throws Exception {
    createRule("SPAM-010", 10, "SPAM", "LOW", false, List.of("free"), "ANY", List.of());
    createRule("SPAM-020", 20, "SPAM", "URGENT", false, List.of("money"), "ANY", List.of());
    UUID messageId = insertMessage("促销", "free money for you", "AMAZON", "BUYER_MESSAGE", "NORMAL");
    JsonNode result = classify(messageId);
    assertThat(result.get("matchedRuleCode").asText()).isEqualTo("SPAM-010");
    assertThat(result.get("suggestedPriority").asText()).isEqualTo("LOW");

    MvcResult detail =
        http.perform(
                get("/crm/message-classifications/messages/" + messageId)
                    .header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode matches =
        json.readTree(detail.getResponse().getContentAsString())
            .get("classification")
            .get("matchBasis")
            .get("matches");
    assertThat(matches.size()).as("落败的规则也要记：只记赢家就看不出「为什么不是它」").isEqualTo(2);
    assertThat(matches.get(0).get("ruleCode").asText()).isEqualTo("SPAM-010");
    assertThat(matches.get(0).get("won").asBoolean()).isTrue();
    assertThat(matches.get(1).get("ruleCode").asText()).isEqualTo("SPAM-020");
    assertThat(matches.get(1).get("won").asBoolean()).isFalse();
  }

  @Test
  void 平台限定的规则不适用于别的平台() throws Exception {
    createRule("AMZ-001", 10, "LOGISTICS", "HIGH", true, List.of("tracking"), "ANY", List.of("AMAZON"));
    UUID messageId = insertMessage("物流", "tracking?", "SHOPIFY", "BUYER_MESSAGE", "NORMAL");
    JsonNode result = classify(messageId);
    assertThat(result.get("label").asText()).as("平台不符 ⇒ 规则不适用").isEqualTo("UNCLASSIFIED");
    assertThat(result.get("autoReplyEligible").asBoolean()).isFalse();
  }

  @Test
  void 消息类型限定的规则不适用于别的类型() throws Exception {
    createRule(
        "CMP-001",
        10,
        "PLATFORM_COMPLIANCE",
        "URGENT",
        false,
        List.of("policy"),
        "ANY",
        List.of(),
        List.of("COMPLIANCE"));
    UUID messageId = insertMessage("合规", "policy notice", "AMAZON", "BUYER_MESSAGE", "NORMAL");
    JsonNode result = classify(messageId);
    assertThat(result.get("label").asText()).as("消息类型不符 ⇒ 规则不适用").isEqualTo("UNCLASSIFIED");

    UUID compliance =
        insertMessage("合规", "policy notice", "AMAZON", "COMPLIANCE", "NORMAL");
    assertThat(classify(compliance).get("label").asText())
        .as("消息类型相符 ⇒ 规则适用")
        .isEqualTo("PLATFORM_COMPLIANCE");
  }

  @Test
  void all模式要求全部关键词命中() throws Exception {
    createRule("REF-001", 10, "RETURN_REFUND", "HIGH", true, List.of("refund", "return"), "ALL", List.of());
    UUID onlyOne = insertMessage("退款", "I want a refund", "AMAZON", "BUYER_MESSAGE", "NORMAL");
    assertThat(classify(onlyOne).get("label").asText())
        .as("ALL 模式：只命中一个关键词不算命中")
        .isEqualTo("UNCLASSIFIED");

    UUID both = insertMessage("退款", "I want a refund and a return label", "AMAZON", "BUYER_MESSAGE", "NORMAL");
    assertThat(classify(both).get("label").asText()).isEqualTo("RETURN_REFUND");
  }

  @Test
  void any模式任一命中即可() throws Exception {
    createRule("LOG-002", 10, "LOGISTICS", "HIGH", true, List.of("tracking", "运单"), "ANY", List.of());
    UUID messageId = insertMessage("物流", "运单号在哪里", "AMAZON", "BUYER_MESSAGE", "NORMAL");
    assertThat(classify(messageId).get("label").asText()).isEqualTo("LOGISTICS");
  }

  // ==========================================================================
  // 🔴 不覆写人工设过的优先级
  // ==========================================================================
  @Test
  void 人工设过的优先级不被覆写且冲突如实标记() throws Exception {
    createRule("LOW-001", 10, "LOGISTICS", "LOW", false, List.of("tracking"), "ANY", List.of());
    UUID messageId = insertMessage("物流", "tracking?", "AMAZON", "BUYER_MESSAGE", "URGENT");
    JsonNode result = classify(messageId);
    assertThat(result.get("suggestedPriority").asText()).isEqualTo("LOW");
    assertThat(result.get("manualPriority").asText()).isEqualTo("URGENT");
    assertThat(result.get("effectivePriority").asText())
        .as("生效值必须是人工值：建议值只作参考")
        .isEqualTo("URGENT");
    assertThat(result.get("priorityConflict").asBoolean()).isTrue();
    assertThat(storedPriority(messageId))
        .as("🔴 分类绝对不能改 crm_message.priority —— 那是人工设过的")
        .isEqualTo("URGENT");
  }

  @Test
  void 人工值与建议值一致时没有冲突() throws Exception {
    createRule("HIGH-001", 10, "LOGISTICS", "HIGH", false, List.of("tracking"), "ANY", List.of());
    UUID messageId = insertMessage("物流", "tracking?", "AMAZON", "BUYER_MESSAGE", "HIGH");
    JsonNode result = classify(messageId);
    assertThat(result.get("priorityConflict").asBoolean()).isFalse();
    assertThat(result.get("effectivePriority").asText()).isEqualTo("HIGH");
  }

  // ==========================================================================
  // 🔴 人工纠正：只追加、不改写结论、自带机器预测快照
  // ==========================================================================
  @Test
  void 人工纠正只追加且不改写分类结论() throws Exception {
    createRule("LOG-001", 10, "LOGISTICS", "HIGH", true, List.of("tracking"), "ANY", List.of());
    UUID messageId = insertMessage("物流", "tracking?", "AMAZON", "BUYER_MESSAGE", "NORMAL");
    classify(messageId);
    JsonNode corrected = correct(messageId, "RETURN_REFUND", "URGENT", false, "买家其实要退货");
    assertThat(corrected.get("feedback").get("predictedLabel").asText())
        .as("纠正行必须带机器当时的预测快照")
        .isEqualTo("LOGISTICS");
    assertThat(corrected.get("feedback").get("correctedLabel").asText()).isEqualTo("RETURN_REFUND");

    // 结论本身**没有被改写**。
    MvcResult detail =
        http.perform(
                get("/crm/message-classifications/messages/" + messageId)
                    .header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode body = json.readTree(detail.getResponse().getContentAsString());
    assertThat(body.get("classification").get("label").asText())
        .as("🔴 纠正不回写结论：结论永远是「机器当时说了什么」")
        .isEqualTo("LOGISTICS");
    assertThat(body.get("feedback").size()).isEqualTo(1);
    assertThat(body.get("classification").get("correctionCount").asLong()).isEqualTo(1);
  }

  @Test
  void 纠正记录改不了也删不掉() throws Exception {
    createRule("LOG-001", 10, "LOGISTICS", "HIGH", true, List.of("tracking"), "ANY", List.of());
    UUID messageId = insertMessage("物流", "tracking?", "AMAZON", "BUYER_MESSAGE", "NORMAL");
    classify(messageId);
    correct(messageId, "RETURN_REFUND", "URGENT", false, "买家其实要退货");

    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      sql.execute(
                          "update crm_message_classification_feedback set corrected_label = 'SPAM'");
                      return null;
                    }))
        .as("训练样本改掉 = 准确率指标随时可以被改漂亮")
        .hasMessageContaining("只追加");
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      sql.execute("delete from crm_message_classification_feedback");
                      return null;
                    }))
        .hasMessageContaining("只追加");

    var rows =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                    "select corrected_label from crm_message_classification_feedback where tenant_id = ?",
                    tenant));
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("corrected_label", String.class)).isEqualTo("RETURN_REFUND");
  }

  @Test
  void 没有分类结论时纠正被拒() throws Exception {
    UUID messageId = insertMessage("还没分类", "hello", "AMAZON", "BUYER_MESSAGE", "NORMAL");
    http.perform(
            post("/crm/message-classifications/feedback")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "messageId", messageId.toString(),
                            "correctedLabel", "SPAM",
                            "correctedPriority", "LOW",
                            "correctedAutoReplyEligible", false,
                            "reason", "随便改改"))))
        .andExpect(status().isBadRequest());
    var count =
        tenants.run(
            tenant,
            () -> sql.fetch("select id from crm_message_classification_feedback").size());
    assertThat(count).isZero();
  }

  @Test
  void 纠正理由必填() throws Exception {
    createRule("LOG-001", 10, "LOGISTICS", "HIGH", true, List.of("tracking"), "ANY", List.of());
    UUID messageId = insertMessage("物流", "tracking?", "AMAZON", "BUYER_MESSAGE", "NORMAL");
    classify(messageId);
    http.perform(
            post("/crm/message-classifications/feedback")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "messageId", messageId.toString(),
                            "correctedLabel", "SPAM",
                            "correctedPriority", "LOW",
                            "correctedAutoReplyEligible", false,
                            "reason", "   "))))
        .andExpect(status().isBadRequest());
  }

  // ==========================================================================
  // 🔴 训练样本：纠正回流
  // ==========================================================================
  @Test
  void 训练样本带机器预测人工纠正与消息正文() throws Exception {
    createRule("LOG-001", 10, "LOGISTICS", "HIGH", true, List.of("tracking"), "ANY", List.of());
    UUID messageId = insertMessage("物流查询", "where is my tracking number", "AMAZON", "BUYER_MESSAGE", "NORMAL");
    classify(messageId);
    correct(messageId, "RETURN_REFUND", "URGENT", false, "买家其实要退货");

    MvcResult result =
        http.perform(
                get("/crm/message-classifications/training-samples").header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode body = json.readTree(result.getResponse().getContentAsString());
    assertThat(body.get("total").asLong()).isEqualTo(1);
    JsonNode sample = body.get("items").get(0);
    assertThat(sample.get("predictedLabel").asText()).isEqualTo("LOGISTICS");
    assertThat(sample.get("correctedLabel").asText()).isEqualTo("RETURN_REFUND");
    assertThat(sample.get("labelAgreed").asBoolean()).isFalse();
    assertThat(sample.get("body").asText())
        .as("🔴 训练样本必须带正文：少了正文就只剩标签对，训不出「哪句话被判成了什么」")
        .contains("tracking");
    assertThat(sample.get("reason").asText()).isEqualTo("买家其实要退货");
  }

  // ==========================================================================
  // 列表与统计：真分页真筛选
  // ==========================================================================
  @Test
  void 分类列表是真分页真筛选() throws Exception {
    createRule("LOG-001", 10, "LOGISTICS", "HIGH", true, List.of("tracking"), "ANY", List.of());
    createRule("SPAM-001", 20, "SPAM", "LOW", false, List.of("free"), "ANY", List.of());
    UUID a = insertMessage("物流", "tracking?", "AMAZON", "BUYER_MESSAGE", "NORMAL");
    UUID b = insertMessage("促销", "free stuff", "AMAZON", "BUYER_MESSAGE", "NORMAL");
    classify(a);
    classify(b);

    MvcResult all =
        http.perform(
                get("/crm/message-classifications/classifications").header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode allBody = json.readTree(all.getResponse().getContentAsString());
    assertThat(allBody.get("total").asLong()).isEqualTo(2);
    assertThat(allBody.get("summary").get("totalCount").asLong()).isEqualTo(2);

    MvcResult filtered =
        http.perform(
                get("/crm/message-classifications/classifications?label=SPAM")
                    .header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode filteredBody = json.readTree(filtered.getResponse().getContentAsString());
    assertThat(filteredBody.get("total").asLong()).isEqualTo(1);
    assertThat(filteredBody.get("items").get(0).get("label").asText()).isEqualTo("SPAM");

    MvcResult byKeyword =
        http.perform(
                get("/crm/message-classifications/classifications?keyword=tracking")
                    .header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(byKeyword.getResponse().getContentAsString()).get("total").asLong())
        .as("关键字筛选要真的落到消息正文上")
        .isEqualTo(1);

    MvcResult paged =
        http.perform(
                get("/crm/message-classifications/classifications?size=1&page=0")
                    .header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode pagedBody = json.readTree(paged.getResponse().getContentAsString());
    assertThat(pagedBody.get("items").size()).isEqualTo(1);
    assertThat(pagedBody.get("total").asLong()).as("总数是独立的 COUNT(*)").isEqualTo(2);
  }

  @Test
  void 统计给出规则命中数与纠正一致率() throws Exception {
    createRule("LOG-001", 10, "LOGISTICS", "HIGH", true, List.of("tracking"), "ANY", List.of());
    UUID a = insertMessage("物流", "tracking?", "AMAZON", "BUYER_MESSAGE", "NORMAL");
    UUID b = insertMessage("物流", "tracking again", "AMAZON", "BUYER_MESSAGE", "NORMAL");
    classify(a);
    classify(b);
    correct(a, "LOGISTICS", "HIGH", true, "确认就是物流");
    correct(b, "RETURN_REFUND", "URGENT", false, "其实是退货");

    MvcResult result =
        http.perform(
                get("/crm/message-classifications/statistics").header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode body = json.readTree(result.getResponse().getContentAsString());
    assertThat(body.get("totalCount").asLong()).isEqualTo(2);
    assertThat(body.get("correctionCount").asLong()).isEqualTo(2);
    assertThat(body.get("correctionAgreementCount").asLong()).isEqualTo(1);
    assertThat(body.get("correctionAgreementRate").asDouble()).isEqualTo(0.5);
    JsonNode ruleStat = body.get("byRule").get(0);
    assertThat(ruleStat.get("ruleCode").asText()).isEqualTo("LOG-001");
    assertThat(ruleStat.get("hitCount").asLong()).as("规则命中数必须来自真实的命中依据").isEqualTo(2);
    assertThat(ruleStat.get("correctionCount").asLong()).isEqualTo(2);
    assertThat(ruleStat.get("disagreementCount").asLong()).isEqualTo(1);
    assertThat(body.get("note").asText()).contains("不是");
  }

  @Test
  void 批量分类逐条给出结论与原因() throws Exception {
    createRule("LOG-001", 10, "LOGISTICS", "HIGH", true, List.of("tracking"), "ANY", List.of());
    UUID good = insertMessage("物流", "tracking?", "AMAZON", "BUYER_MESSAGE", "NORMAL");
    MvcResult result =
        http.perform(
                post("/crm/message-classifications/classify-batch")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "messageIds",
                                List.of(good.toString(), UUID.randomUUID().toString())))))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode body = json.readTree(result.getResponse().getContentAsString());
    assertThat(body.get("requestedCount").asInt()).isEqualTo(2);
    assertThat(body.get("succeededCount").asInt()).isEqualTo(1);
    assertThat(body.get("failedCount").asInt()).isEqualTo(1);
    assertThat(body.get("items").get(0).get("success").asBoolean()).isTrue();
    assertThat(body.get("items").get(1).get("success").asBoolean()).isFalse();
    assertThat(body.get("items").get(1).get("reason").asText()).contains("不存在");
  }

  // ==========================================================================
  // 时点：写回的值必须是同一个绝对时刻
  // ==========================================================================
  @Test
  void 分类时刻写回的是同一个绝对时刻() throws Exception {
    createRule("LOG-001", 10, "LOGISTICS", "HIGH", true, List.of("tracking"), "ANY", List.of());
    UUID messageId = insertMessage("物流", "tracking?", "AMAZON", "BUYER_MESSAGE", "NORMAL");
    JsonNode result = classify(messageId);
    OffsetDateTime apiValue = OffsetDateTime.parse(result.get("classifiedAt").asText());

    OffsetDateTime stored =
        tenants.run(
            tenant,
            () -> {
              var row =
                  sql.fetchOne(
                      "select classified_at from crm_message_classification where tenant_id = ?"
                          + " and message_id = ?",
                      tenant,
                      messageId);
              return row.get(0, OffsetDateTime.class);
            });
    assertThat(stored.toInstant())
        .as("🔴 写回的值必须是同一个绝对时刻（不能静默偏 8 小时）")
        .isEqualTo(apiValue.toInstant());
    assertThat(stored.toInstant())
        .isBetween(
            OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5).toInstant(),
            OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5).toInstant());
  }

  @Test
  void 消息的平台时刻带偏移量写回后绝对时刻不变() throws Exception {
    UUID messageId = insertMessage("物流", "tracking?", "AMAZON", "BUYER_MESSAGE", "NORMAL");
    OffsetDateTime stored =
        tenants.run(
            tenant,
            () -> {
              var row =
                  sql.fetchOne(
                      "select platform_time from crm_message where tenant_id = ? and id = ?",
                      tenant,
                      messageId);
              return row.get(0, OffsetDateTime.class);
            });
    assertThat(stored.toInstant())
        .as("+08:00 的 09:30 必须还是同一个绝对时刻")
        .isEqualTo(OffsetDateTime.parse("2026-06-01T09:30:00+08:00").toInstant());
  }
}
