package com.starcon.crm;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 15.5.1.1 自动回复的集成测试。
 *
 * <p>跑的是 AGENTS.md 要求的<b>真实 PostgreSQL 16 + FORCE RLS</b>。租户隔离三断言在
 * {@link AutoReplyTenancyIntegrationTest} 里，本类专攻业务不变量：
 *
 * <ul>
 *   <li><b>三种触发条件</b>真的分开生效：分类命中 / 关键词命中 / 无人工介入超时；
 *       分类复用 15.1.1.3 的结论（<b>不另造平行分类表</b>）。</li>
 *   <li><b>🔴 上游 15.1.1.3 判定「不可自动回复」时真的不发</b>，且留痕写明原因。</li>
 *   <li><b>🔴 分级自动回复</b>：先发一级 → 仍无人工介入 → 间隔到了再发一级 → 转人工；
 *       每一级都留痕。</li>
 *   <li><b>🔴 一键关闭是即时生效</b>：关掉后新触发不再发，已排队待发的被逐条撤销。</li>
 *   <li><b>🔴 人工接管后同一会话不再自动回复</b>。</li>
 *   <li><b>值班日历真的生效</b>：无人值守时段不允许自动回复时**转人工**（留出口）。</li>
 *   <li><b>记录只追加</b>：改规则不追溯改变历史记录。</li>
 *   <li><b>真分页真筛选</b>；<b>时刻写回等于同一绝对时刻</b>（不静默偏 −28800 秒）。</li>
 * </ul>
 *
 * <p>⚠️ 本类所有**直连数据库**的读写都在 {@code tenants.run(tenant, …)} 里 ——
 * RLS 是<b>强制</b>的，事务外没有租户上下文时一行都看不到。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AutoReplyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID userId = UUID.randomUUID();
  static final UUID shopId = UUID.randomUUID();
  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，隔离断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role crm_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    List<String> permissions = List.of("crm.auto_reply.read", "crm.auto_reply.update");
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
    // ⚠️ TRUNCATE 走**迁移用户**（超级用户）直连：运行角色 crm_app 没有 TRUNCATE 权限。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table crm_auto_reply_takeover, crm_auto_reply_escalation,"
              + " crm_auto_reply_record, crm_auto_reply_duty_calendar, crm_auto_reply_rule,"
              + " crm_auto_reply_setting, crm_message_classification, crm_message_send_attempt,"
              + " crm_message_send_attachment, crm_message_send, crm_message_send_template,"
              + " crm_message cascade");
    }
  }

  /** 登记一条收到的消息（15.1.1.1 上游）。 */
  UUID seedMessage(String buyer, String subject, String body, OffsetDateTime platformTime) {
    return tenants.run(
        tenant,
        () -> {
          UUID id = UUID.randomUUID();
          OffsetDateTime moment = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into crm_message"
                  + "(id,tenant_id,message_no,shop_id,shop_name,platform_code,buyer_reference,"
                  + " order_reference,subject,body,direction,message_type,priority,read_status,"
                  + " platform_time,attachment_count,source,external_message_id,created_by,note,"
                  + " created_at,updated_at)"
                  + " values (?,?,?,?,?,?,?,?,?,?,'INBOUND','BUYER_MESSAGE','NORMAL','UNREAD',"
                  + "?::timestamptz,0,'MANUAL_ENTRY','',?,'',?::timestamptz,?::timestamptz)",
              id,
              tenant,
              "MSG-" + id.toString().substring(0, 8),
              shopId,
              "测试店铺",
              "SHOPIFY",
              buyer,
              "ORDER-1",
              subject,
              body,
              platformTime,
              userId,
              moment,
              moment);
          return id;
        });
  }

  /** 登记一条分类结论（15.1.1.3 上游，只读不改）。 */
  void seedClassification(UUID messageId, String label, boolean eligible) {
    tenants.run(
        tenant,
        () -> {
          OffsetDateTime moment = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into crm_message_classification"
                  + "(id,tenant_id,classification_no,message_id,label,suggested_priority,"
                  + " manual_priority,effective_priority,priority_conflict,auto_reply_eligible,"
                  + " matched_rule_id,matched_rule_code,matched_rule_name,match_basis,"
                  + " classifier_version,classified_at,created_at,updated_at)"
                  + " values (?,?,?,?,?,'NORMAL','NORMAL','NORMAL',false,?,null,'','',"
                  + "'{}'::jsonb,'RULE_MATCH_V1',?::timestamptz,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              "CLS-" + UUID.randomUUID().toString().substring(0, 8),
              messageId,
              label,
              eligible,
              moment,
              moment,
              moment);
          return null;
        });
  }

  /** 登记一条「客服已回复」的发送记录（15.1.1.2，人工介入的判定依据）。 */
  void seedHumanSend(UUID messageId) {
    tenants.run(
        tenant,
        () -> {
          OffsetDateTime moment = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into crm_message_send"
                  + "(id,tenant_id,send_no,target_message_id,shop_id,shop_name,platform_code,"
                  + " buyer_reference,order_reference,subject,body,body_source,transport,status,"
                  + " failure_reason,platform_receipt,platform_message_id,attachment_count,"
                  + " attempt_count,last_attempt_at,sent_at,idempotency_key,batch_id,created_by,"
                  + " note,created_at,updated_at)"
                  + " values (?,?,?,?,?,?,?,?,?,?,?,'MANUAL','MANUAL_HANDOFF','SENT','','','',0,1,"
                  + "?::timestamptz,?::timestamptz,?,null,?,'',?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              "SND-" + UUID.randomUUID().toString().substring(0, 8),
              messageId,
              shopId,
              "测试店铺",
              "SHOPIFY",
              "BUYER-1",
              "ORDER-1",
              "回复",
              "人工回复内容",
              moment,
              moment,
              "human-" + UUID.randomUUID(),
              userId,
              moment,
              moment);
          return null;
        });
  }

  // ================================================================ 工具

  String postJson(String path, String body) throws Exception {
    var result =
        http.perform(
                post(path)
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andReturn();
    if (result.getResponse().getStatus() != 200) {
      throw new AssertionError(
          "HTTP "
              + result.getResponse().getStatus()
              + " : "
              + result.getResponse().getContentAsString()
              + " resolved="
              + result.getResolvedException());
    }
    return result.getResponse().getContentAsString();
  }

  String putJson(String path, String body) throws Exception {
    var result =
        http.perform(
                put(path)
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andReturn();
    if (result.getResponse().getStatus() != 200) {
      throw new AssertionError(
          "HTTP "
              + result.getResponse().getStatus()
              + " : "
              + result.getResponse().getContentAsString()
              + " resolved="
              + result.getResolvedException());
    }
    return result.getResponse().getContentAsString();
  }

  String postExpecting(String path, String body, int status) throws Exception {
    var result =
        http.perform(
                post(path)
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(status);
    return result.getResponse().getContentAsString();
  }

  JsonNode getJson(String path) throws Exception {
    var result =
        http.perform(get(path).header("Authorization", "session")).andReturn();
    if (result.getResponse().getStatus() != 200) {
      throw new AssertionError(
          "HTTP "
              + result.getResponse().getStatus()
              + " : "
              + result.getResponse().getContentAsString());
    }
    return json.readTree(result.getResponse().getContentAsString());
  }

  String ruleJson(
      String code,
      String ladder,
      int level,
      String trigger,
      boolean escalate,
      String labels,
      String keywords,
      int humanTimeout,
      int levelWait,
      String body) {
    // ⚠️ 关键词是**换行分隔**的（与 15.1.1.3 的 rules.keywords 同一形状），
    //   所以进 JSON 前必须把换行转义 —— 直接拼进去会让 JSON 解析器报
    //   「Illegal unquoted character (CTRL-CHAR, code 10)」。
    return "{\"ruleCode\":\""
        + code
        + "\",\"ruleName\":\""
        + code
        + "\",\"ladderCode\":\""
        + ladder
        + "\",\"level\":"
        + level
        + ",\"escalateToHuman\":"
        + escalate
        + ",\"triggerType\":\""
        + trigger
        + "\",\"classificationLabels\":\""
        + labels
        + "\",\"keywords\":\""
        + keywords.replace("\n", "\\n")
        + "\",\"humanTimeoutMinutes\":"
        + humanTimeout
        + ",\"levelWaitMinutes\":"
        + levelWait
        + ",\"body\":\""
        + body
        + "\"}";
  }

  long recordCount() {
    return tenants.run(
        tenant, () -> sql.fetchOne("select count(*) from crm_auto_reply_record").get(0, Long.class));
  }

  long sendCount() {
    return tenants.run(
        tenant, () -> sql.fetchOne("select count(*) from crm_message_send").get(0, Long.class));
  }

  long escalationCount() {
    return tenants.run(
        tenant,
        () -> sql.fetchOne("select count(*) from crm_auto_reply_escalation").get(0, Long.class));
  }

  /** 从触发结果里取出「真的发了的那一条」。 */
  JsonNode firedItem(JsonNode result) {
    for (var item : result.get("items")) {
      if (item.get("fired").asBoolean()) {
        return item;
      }
    }
    throw new AssertionError("触发结果里没有「发出去了」的那一条：" + result);
  }

  // ================================================================ 触发条件

  @Test
  void 分类命中会真的登记一次发送_并且复用下游1511的发送记录() throws Exception {
    UUID messageId = seedMessage("BUYER-1", "物流到哪了", "我的快递什么时候到", OffsetDateTime.now(ZoneOffset.UTC));
    seedClassification(messageId, "LOGISTICS", true);
    postJson(
        "/crm/auto-replies/rules",
        ruleJson("R-LOG", "LAD-LOG", 1, "CLASSIFICATION", true, "LOGISTICS", "", 0, 0, "您的包裹正在派送中。"));

    JsonNode result = json.readTree(postJson("/crm/auto-replies/trigger", "{\"messageId\":\"" + messageId + "\"}"));
    assertThat(result.get("evaluated").asBoolean()).isTrue();
    assertThat(result.get("firedCount").asInt()).isEqualTo(1);
    assertThat(result.get("items").get(0).get("outcome").asText()).isEqualTo("SEND_REGISTERED");
    assertThat(result.get("classificationLabel").asText()).isEqualTo("LOGISTICS");

    // 🔴 复用 15.1.1.2：真的多了一条 crm_message_send（不是本模块自己造的发送表）。
    assertThat(sendCount()).isEqualTo(1);
    assertThat(recordCount()).isEqualTo(1);

    JsonNode records = getJson("/crm/auto-replies/records");
    JsonNode row = records.get("items").get(0);
    assertThat(row.get("outcome").asText()).isEqualTo("SEND_REGISTERED");
    assertThat(row.get("level").asInt()).isEqualTo(1);
    assertThat(row.get("escalated").asBoolean()).as("本级标记了转人工").isTrue();
    assertThat(row.get("templateBodySnapshot").asText()).isEqualTo("您的包裹正在派送中。");
    assertThat(row.get("sendNo").asText()).as("关联到 15.1.1.2 的发送记录").isNotBlank();
    // 平台侧发送接口未接入 ⇒ transport 恒为 MANUAL_HANDOFF，且**不假装已发出**。
    assertThat(row.get("sendStatus").asText()).isEqualTo("QUEUED");

    // 阶梯终点 ⇒ 转人工标记真的落库可查。
    assertThat(escalationCount()).isEqualTo(1);
    JsonNode escalations = getJson("/crm/auto-replies/escalations");
    assertThat(escalations.get("items").get(0).get("reasonCode").asText()).isEqualTo("LADDER_EXHAUSTED");
    assertThat(escalations.get("items").get(0).get("takenOver").asBoolean()).isFalse();
  }

  @Test
  void 上游判定不可自动回复时不发_但留痕写明原因() throws Exception {
    UUID messageId = seedMessage("BUYER-1", "垃圾广告", "buy now cheap", OffsetDateTime.now(ZoneOffset.UTC));
    seedClassification(messageId, "SPAM", false);
    postJson(
        "/crm/auto-replies/rules",
        ruleJson("R-SPAM", "LAD-SPAM", 1, "CLASSIFICATION", false, "SPAM", "", 0, 0, "自动回复内容"));

    JsonNode result = json.readTree(postJson("/crm/auto-replies/trigger", "{\"messageId\":\"" + messageId + "\"}"));
    assertThat(result.get("firedCount").asInt()).isEqualTo(0);
    assertThat(result.get("suppressedCount").asInt()).isEqualTo(1);
    assertThat(sendCount()).as("上游说不许自动回，就一条都不能发").isZero();

    JsonNode row = getJson("/crm/auto-replies/records").get("items").get(0);
    assertThat(row.get("outcome").asText()).isEqualTo("SUPPRESSED");
    assertThat(row.get("reason").asText()).contains("15.1.1.3").contains("auto_reply_eligible=false");
  }

  @Test
  void 关键词命中才会触发_不命中时连记录都不留() throws Exception {
    UUID messageId = seedMessage("BUYER-1", "退款", "我要退款", OffsetDateTime.now(ZoneOffset.UTC));
    postJson(
        "/crm/auto-replies/rules",
        ruleJson("R-KW", "LAD-KW", 1, "KEYWORD", false, "", "退款\n退货", 0, 0, "已收到您的退款申请。"));

    JsonNode result = json.readTree(postJson("/crm/auto-replies/trigger", "{\"messageId\":\"" + messageId + "\"}"));
    assertThat(result.get("firedCount").asInt()).isEqualTo(1);
    assertThat(getJson("/crm/auto-replies/records").get("items").get(0).get("triggerReason").asText())
        .contains("退款");

    UUID other = seedMessage("BUYER-2", "你好", "在吗", OffsetDateTime.now(ZoneOffset.UTC));
    JsonNode miss = json.readTree(postJson("/crm/auto-replies/trigger", "{\"messageId\":\"" + other + "\"}"));
    assertThat(miss.get("firedCount").asInt()).isZero();
    assertThat(miss.get("items").get(0).get("matched").asBoolean()).isFalse();
    assertThat(miss.get("items").get(0).get("reason").asText()).contains("关键词未命中");
  }

  @Test
  void 无人工介入超时才触发_客服已经回过就不触发() throws Exception {
    OffsetDateTime twoHoursAgo = OffsetDateTime.now(ZoneOffset.UTC).minusHours(2);
    UUID messageId = seedMessage("BUYER-1", "在吗", "没人理我", twoHoursAgo);
    postJson(
        "/crm/auto-replies/rules",
        ruleJson("R-TO", "LAD-TO", 1, "HUMAN_TIMEOUT", false, "", "", 60, 0, "抱歉让您久等，正在为您处理。"));

    JsonNode result = json.readTree(postJson("/crm/auto-replies/trigger", "{\"messageId\":\"" + messageId + "\"}"));
    assertThat(result.get("firedCount").asInt()).as("已超过 60 分钟无人工介入").isEqualTo(1);

    // 超时口径未到 ⇒ 不触发。
    UUID fresh = seedMessage("BUYER-3", "在吗", "刚发的", OffsetDateTime.now(ZoneOffset.UTC));
    JsonNode freshResult =
        json.readTree(postJson("/crm/auto-replies/trigger", "{\"messageId\":\"" + fresh + "\"}"));
    assertThat(freshResult.get("firedCount").asInt()).isZero();
    assertThat(freshResult.get("items").get(0).get("reason").asText()).contains("无人工介入超时的口径是 60 分钟");

    // 客服已经回过 ⇒ 不构成「无人工介入超时」。
    UUID answered = seedMessage("BUYER-4", "在吗", "也没人理我", twoHoursAgo);
    seedHumanSend(answered);
    JsonNode answeredResult =
        json.readTree(postJson("/crm/auto-replies/trigger", "{\"messageId\":\"" + answered + "\"}"));
    assertThat(answeredResult.get("firedCount").asInt()).isZero();
    assertThat(answeredResult.get("items").get(0).get("reason").asText()).contains("人工介入");
  }

  // ================================================================ 分级自动回复

  @Test
  void 分级自动回复先发一级_间隔到了再发一级_然后转人工() throws Exception {
    UUID messageId = seedMessage("BUYER-1", "退款", "我要退款", OffsetDateTime.now(ZoneOffset.UTC));
    postJson(
        "/crm/auto-replies/rules",
        ruleJson("R-L1", "LAD", 1, "KEYWORD", false, "", "退款", 0, 0, "第一级：已收到，正在为您查询。"));
    postJson(
        "/crm/auto-replies/rules",
        ruleJson("R-L2", "LAD", 2, "KEYWORD", true, "", "退款", 0, 30, "第二级：仍在处理，请稍候。"));

    OffsetDateTime t0 = OffsetDateTime.now(ZoneOffset.UTC);
    JsonNode first = json.readTree(postJson("/crm/auto-replies/trigger", "{\"messageId\":\"" + messageId + "\",\"at\":\"" + t0 + "\"}"));
    assertThat(first.get("firedCount").asInt()).isEqualTo(1);
    assertThat(first.get("items").get(0).get("level").asInt()).isEqualTo(1);
    assertThat(escalationCount()).as("第一级还没走完，不该转人工").isZero();

    // 立刻再触发：同一级不重发（幂等），第二级间隔未到也不发。
    JsonNode second = json.readTree(postJson("/crm/auto-replies/trigger", "{\"messageId\":\"" + messageId + "\",\"at\":\"" + t0.plusMinutes(1) + "\"}"));
    assertThat(second.get("firedCount").asInt()).isZero();
    assertThat(sendCount()).as("只发了一次").isEqualTo(1);

    // 间隔到了 ⇒ 第二级发出并转人工。
    JsonNode third = json.readTree(postJson("/crm/auto-replies/trigger", "{\"messageId\":\"" + messageId + "\",\"at\":\"" + t0.plusMinutes(31) + "\"}"));
    assertThat(third.get("firedCount").asInt()).isEqualTo(1);
    // items 里**每一级**都在（没命中的也列出来并写明原因），
    // 所以要按「真的发了的那一条」去取，而不是取第一项。
    JsonNode fired = firedItem(third);
    assertThat(fired.get("level").asInt()).isEqualTo(2);
    assertThat(fired.get("escalated").asBoolean()).isTrue();
    assertThat(third.get("escalatedCount").asInt()).isEqualTo(1);
    assertThat(sendCount()).isEqualTo(2);

    // 每一级都留痕。
    JsonNode records = getJson("/crm/auto-replies/records?ladderCode=LAD");
    assertThat(records.get("totalElements").asLong()).isEqualTo(2);
    assertThat(records.get("items").get(0).get("level").asInt()).isEqualTo(2);
    assertThat(records.get("items").get(1).get("level").asInt()).isEqualTo(1);
  }

  @Test
  void 阶梯必须连号_不能先建第二级() throws Exception {
    String body =
        ruleJson("R-L2", "LAD-X", 2, "KEYWORD", true, "", "退款", 0, 30, "第二级");
    postExpecting("/crm/auto-replies/rules", body, 400);
    assertThat(recordCount()).isZero();
  }

  // ================================================================ 一键关闭

  @Test
  void 一键关闭是即时生效_并且把已排队待发的逐条撤销() throws Exception {
    UUID messageId = seedMessage("BUYER-1", "物流", "到哪了", OffsetDateTime.now(ZoneOffset.UTC));
    seedClassification(messageId, "LOGISTICS", true);
    postJson(
        "/crm/auto-replies/rules",
        ruleJson("R-KILL", "LAD-K", 1, "CLASSIFICATION", false, "LOGISTICS", "", 0, 0, "派送中"));
    postJson("/crm/auto-replies/trigger", "{\"messageId\":\"" + messageId + "\"}");
    assertThat(sendCount()).isEqualTo(1);

    JsonNode closed = json.readTree(postJson("/crm/auto-replies/settings/toggle", "{\"enabled\":false,\"reason\":\"平台对纯自动回复有限制，先全关\"}"));
    assertThat(closed.get("autoReplyEnabled").asBoolean()).isFalse();
    assertThat(closed.get("closedReason").asText()).contains("平台对纯自动回复有限制");

    // 撤销行真的落库（**只追加**，不是把原记录改掉）。
    JsonNode records = getJson("/crm/auto-replies/records?outcome=CANCELLED");
    assertThat(records.get("totalElements").asLong()).isEqualTo(1);
    assertThat(records.get("items").get(0).get("reason").asText()).contains("不得再发");
    assertThat(records.get("items").get(0).get("originRecordId").asText()).isNotBlank();

    // 关闭之后的新触发：**不评估、不发送、不写任何记录**。
    long before = recordCount();
    JsonNode after = json.readTree(postJson("/crm/auto-replies/trigger", "{\"messageId\":\"" + messageId + "\"}"));
    assertThat(after.get("globalClosed").asBoolean()).isTrue();
    assertThat(after.get("evaluated").asBoolean()).isFalse();
    assertThat(after.get("closedReason").asText()).contains("平台对纯自动回复有限制");
    assertThat(recordCount()).isEqualTo(before);
    assertThat(sendCount()).as("关闭后不得再发").isEqualTo(1);

    // 关闭必须写原因。
    postExpecting("/crm/auto-replies/settings/toggle", "{\"enabled\":false}", 400);
  }

  // ================================================================ 人工接管

  @Test
  void 人工接管后同一会话不再自动回复() throws Exception {
    UUID messageId = seedMessage("BUYER-1", "物流", "到哪了", OffsetDateTime.now(ZoneOffset.UTC));
    seedClassification(messageId, "LOGISTICS", true);
    postJson(
        "/crm/auto-replies/rules",
        ruleJson("R-TO", "LAD-TO", 1, "CLASSIFICATION", false, "LOGISTICS", "", 0, 0, "派送中"));

    JsonNode takeover = json.readTree(postJson("/crm/auto-replies/takeovers", "{\"messageId\":\"" + messageId + "\",\"reason\":\"客服接手了\"}"));
    assertThat(takeover.get("buyerReference").asText()).isEqualTo("BUYER-1");
    assertThat(takeover.get("conversationKey").asText()).contains("BUYER-1");

    JsonNode result = json.readTree(postJson("/crm/auto-replies/trigger", "{\"messageId\":\"" + messageId + "\"}"));
    assertThat(result.get("conversationTakenOver").asBoolean()).isTrue();
    assertThat(result.get("firedCount").asInt()).as("接管后不再自动回复").isZero();
    assertThat(sendCount()).isZero();
    JsonNode row = getJson("/crm/auto-replies/records").get("items").get(0);
    assertThat(row.get("outcome").asText()).isEqualTo("SUPPRESSED");
    assertThat(row.get("reason").asText()).contains("人工接管");

    // 接管必须写原因。
    postExpecting("/crm/auto-replies/takeovers", "{\"messageId\":\"" + messageId + "\"}", 400);

    // 同一个会话重复接管 ⇒ 幂等返回既有行，不制造第二条。
    long takeovers =
        tenants.run(
            tenant,
            () -> sql.fetchOne("select count(*) from crm_auto_reply_takeover").get(0, Long.class));
    assertThat(takeovers).isEqualTo(1);
  }

  // ================================================================ 值班日历

  @Test
  void 值班日历挡下自动回复时转人工_留人工出口() throws Exception {
    UUID messageId = seedMessage("BUYER-1", "物流", "到哪了", OffsetDateTime.now(ZoneOffset.UTC));
    seedClassification(messageId, "LOGISTICS", true);
    postJson(
        "/crm/auto-replies/rules",
        ruleJson("R-DUTY", "LAD-DUTY", 1, "CLASSIFICATION", false, "LOGISTICS", "", 0, 0, "派送中"));

    // 排班：今天有人值班且不允许自动回复。
    String today = OffsetDateTime.now(ZoneOffset.UTC).toLocalDate().toString();
    JsonNode entry = json.readTree(postJson("/crm/auto-replies/calendar",
        "{\"scope\":\"GLOBAL\",\"calendarDate\":\"" + today + "\",\"dutyMode\":\"ATTENDED\","
            + "\"autoReplyAllowed\":false,\"holidayName\":\"国庆\",\"timezone\":\"UTC\"}"));
    assertThat(entry.get("autoReplyAllowed").asBoolean()).isFalse();
    assertThat(entry.get("holidayName").asText()).isEqualTo("国庆");

    JsonNode result = json.readTree(postJson("/crm/auto-replies/trigger", "{\"messageId\":\"" + messageId + "\"}"));
    assertThat(result.get("firedCount").asInt()).isZero();
    assertThat(result.get("suppressedCount").asInt()).isEqualTo(1);
    assertThat(result.get("escalatedCount").asInt()).as("无人值守时段仍要保留转人工出口").isEqualTo(1);
    assertThat(sendCount()).isZero();

    JsonNode escalation = getJson("/crm/auto-replies/escalations").get("items").get(0);
    assertThat(escalation.get("reasonCode").asText()).isEqualTo("DUTY_GATE");
    assertThat(escalation.get("reason").asText()).contains("值班闸");
  }

  @Test
  void 值班日历是可配置的_休息时段不允许自动回复() throws Exception {
    String today = OffsetDateTime.now(ZoneOffset.UTC).toLocalDate().toString();
    // 「休息」与「允许自动回复」自相矛盾 ⇒ DB 层 CHECK 挡住。
    postExpecting(
        "/crm/auto-replies/calendar",
        "{\"scope\":\"GLOBAL\",\"calendarDate\":\"" + today + "\",\"dutyMode\":\"CLOSED\",\"autoReplyAllowed\":true}",
        400);
    // 时段的起与止必须同时给。
    postExpecting(
        "/crm/auto-replies/calendar",
        "{\"scope\":\"GLOBAL\",\"calendarDate\":\"" + today + "\",\"dutyMode\":\"UNATTENDED\",\"dutyStart\":\"09:00\"}",
        400);
  }

  // ================================================================ 只追加 & 快照

  @Test
  void 自动回复记录只追加_改规则不追溯改变历史记录() throws Exception {
    UUID messageId = seedMessage("BUYER-1", "物流", "到哪了", OffsetDateTime.now(ZoneOffset.UTC));
    seedClassification(messageId, "LOGISTICS", true);
    JsonNode rule =
        json.readTree(
            postJson(
                "/crm/auto-replies/rules",
                ruleJson("R-SNAP", "LAD-SNAP", 1, "CLASSIFICATION", false, "LOGISTICS", "", 0, 0, "第一版话术")));
    postJson("/crm/auto-replies/trigger", "{\"messageId\":\"" + messageId + "\"}");

    // 改规则的话术。
    putJson(
        "/crm/auto-replies/rules/" + rule.get("id").asText(),
        ruleJson("R-SNAP", "LAD-SNAP", 1, "CLASSIFICATION", false, "LOGISTICS", "", 0, 0, "第二版话术"));

    // 历史记录里的正文快照**不变**。
    JsonNode row = getJson("/crm/auto-replies/records").get("items").get(0);
    assertThat(row.get("templateBodySnapshot").asText()).isEqualTo("第一版话术");

    // DB 层拒绝改 / 删记录。
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      sql.execute("update crm_auto_reply_record set reason='改一下'");
                      return null;
                    }))
        .as("自动回复记录只追加：改规则不追溯改变历史记录")
        .hasMessageContaining("只追加");
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      sql.execute("delete from crm_auto_reply_record");
                      return null;
                    }))
        .hasMessageContaining("只追加");
  }

  @Test
  void 时刻写回等于同一绝对时刻_不静默偏28800秒() throws Exception {
    UUID messageId = seedMessage("BUYER-1", "物流", "到哪了", OffsetDateTime.now(ZoneOffset.UTC));
    seedClassification(messageId, "LOGISTICS", true);
    postJson(
        "/crm/auto-replies/rules",
        ruleJson("R-TZ", "LAD-TZ", 1, "CLASSIFICATION", false, "LOGISTICS", "", 0, 0, "派送中"));

    OffsetDateTime at = OffsetDateTime.parse("2026-10-23T18:30:00+08:00");
    postJson("/crm/auto-replies/trigger", "{\"messageId\":\"" + messageId + "\",\"at\":\"" + at + "\"}");

    OffsetDateTime stored =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne("select occurred_at from crm_auto_reply_record limit 1")
                    .get(0, OffsetDateTime.class));
    assertThat(stored.toInstant()).as("写回的值必须是同一个绝对时刻").isEqualTo(at.toInstant());
  }

  // ================================================================ 分页 / 筛选 / 统计

  @Test
  void 真分页真筛选_统计口径服务端算() throws Exception {
    for (int i = 0; i < 5; i++) {
      UUID messageId = seedMessage("BUYER-" + i, "物流", "到哪了", OffsetDateTime.now(ZoneOffset.UTC));
      seedClassification(messageId, "LOGISTICS", true);
    }
    postJson(
        "/crm/auto-replies/rules",
        ruleJson("R-PAGE", "LAD-PAGE", 1, "CLASSIFICATION", false, "LOGISTICS", "", 0, 0, "派送中"));
    // 逐条触发（每条消息各自走一遍阶梯的第一级）。
    for (var row :
        tenants.run(
            tenant,
            () -> sql.fetch("select id from crm_message order by message_no").map(r -> r.get(0, UUID.class)))) {
      postJson("/crm/auto-replies/trigger", "{\"messageId\":\"" + row + "\"}");
    }

    JsonNode page = getJson("/crm/auto-replies/records?size=2&page=0");
    assertThat(page.get("totalElements").asLong()).isEqualTo(5);
    assertThat(page.get("totalPages").asInt()).isEqualTo(3);
    assertThat(page.get("items").size()).isEqualTo(2);
    JsonNode page2 = getJson("/crm/auto-replies/records?size=2&page=2");
    assertThat(page2.get("items").size()).isEqualTo(1);

    JsonNode filtered = getJson("/crm/auto-replies/records?outcome=SUPPRESSED");
    assertThat(filtered.get("totalElements").asLong()).isZero();

    JsonNode stats = getJson("/crm/auto-replies/stats");
    assertThat(stats.get("totalRecords").asLong()).isEqualTo(5);
    assertThat(stats.get("sendRegistered").asLong()).isEqualTo(5);
    assertThat(stats.get("recordsByLevelOne").asLong()).isEqualTo(5);
    assertThat(stats.get("byOutcome").size()).isEqualTo(1);
    assertThat(stats.get("byOutcome").get(0).get("code").asText()).isEqualTo("SEND_REGISTERED");
    assertThat(stats.get("byOutcome").get(0).get("count").asLong()).isEqualTo(5);
    assertThat(stats.get("byOutcome").get(0).get("label").asText()).isNotBlank();
  }

  @Test
  void 能力声明把未交付的维度标成不可用_不编平台白名单() throws Exception {
    JsonNode caps = getJson("/crm/auto-replies/capabilities");
    assertThat(caps.get("globalKillSwitch").asText()).contains("即时生效");
    assertThat(caps.get("conversationScope").asText()).contains("shop_id");
    assertThat(caps.get("platformAutoReplyPolicy").asText()).contains("不编");
    boolean sawUnavailable = false;
    for (var dimension : caps.get("dimensions")) {
      if (!dimension.get("available").asBoolean()) {
        sawUnavailable = true;
        assertThat(dimension.get("ownerModule").asText()).as("不可用的维度必须点名归谁").isNotBlank();
        assertThat(dimension.get("impact").asText()).as("不可用的维度必须说清缺了会怎样").isNotBlank();
      }
    }
    assertThat(sawUnavailable).isTrue();
    assertThat(caps.get("triggerTypes").toString()).contains("HUMAN_TIMEOUT");
  }

  @Test
  void 转人工标记一条消息只转一次_重复标记被拒() throws Exception {
    UUID messageId = seedMessage("BUYER-1", "物流", "到哪了", OffsetDateTime.now(ZoneOffset.UTC));
    seedClassification(messageId, "LOGISTICS", true);
    postJson(
        "/crm/auto-replies/rules",
        ruleJson("R-ESC", "LAD-ESC", 1, "CLASSIFICATION", false, "LOGISTICS", "", 0, 0, "派送中"));
    postJson("/crm/auto-replies/trigger", "{\"messageId\":\"" + messageId + "\"}");
    JsonNode record = getJson("/crm/auto-replies/records").get("items").get(0);

    JsonNode escalation =
        json.readTree(
            postJson(
                "/crm/auto-replies/records/" + record.get("id").asText() + "/escalate",
                "{\"reason\":\"买家情绪激动，需要人工\"}"));
    assertThat(escalation.get("reasonCode").asText()).isEqualTo("MANUAL_ESCALATION");

    postExpecting(
        "/crm/auto-replies/records/" + record.get("id").asText() + "/escalate",
        "{\"reason\":\"再标一次\"}",
        409);
    assertThat(escalationCount()).isEqualTo(1);
  }

  @Test
  void 触发条件互斥_分类规则不给标签会被拒() throws Exception {
    postExpecting(
        "/crm/auto-replies/rules",
        ruleJson("R-NO", "LAD-NO", 1, "CLASSIFICATION", false, "", "", 0, 0, "话术"),
        400);
    postExpecting(
        "/crm/auto-replies/rules",
        ruleJson("R-NO2", "LAD-NO2", 1, "HUMAN_TIMEOUT", false, "", "", 0, 0, "话术"),
        400);
    postExpecting(
        "/crm/auto-replies/rules",
        ruleJson("R-NO3", "LAD-NO3", 1, "CLASSIFICATION", false, "不存在的标签", "", 0, 0, "话术"),
        400);
  }
}
