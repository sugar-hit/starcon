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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 15.1.1.2 消息发送的集成测试。
 *
 * <p>跑的是 AGENTS.md 要求的<b>真实 PostgreSQL 16 + FORCE RLS</b>。租户隔离三断言在
 * {@link MessageSendTenancyIntegrationTest} 里，本类专攻业务不变量：
 *
 * <ul>
 *   <li><b>🔴 加粗句「发送失败要保留原消息状态」</b>：发送失败前后，源消息那一行
 *       <b>逐列相同</b>（含 updated_at），推导出来的「已回复」状态<b>不变</b>。</li>
 *   <li><b>平台投递未接入</b>：{@code transport=PLATFORM_API} 明确 <b>409</b>，
 *       且<b>库里一条记录都不写</b>。</li>
 *   <li><b>模板 / 手工编辑 / 知识库话术</b>：三种来源各自留痕（body_source），
 *       模板变量真的渲染，<b>渲染不出来就让发送失败</b>。</li>
 *   <li><b>幂等键</b>：同一条键重放<b>不会</b>产生第二条发送记录。</li>
 *   <li><b>失败重试</b>：每一次尝试一行，失败原因各自留痕。</li>
 *   <li><b>批量相同回复</b>：逐条给出结论，失败的条目不会被悄悄跳过。</li>
 *   <li><b>时区不静默偏移（硬性要求 5）</b>：用 {@code +08:00} 的偏移量写入，
 *       再<b>断言写回的值</b>等于同一个绝对时刻。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class MessageSendIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID userId = UUID.randomUUID();
  static final UUID shopId = UUID.randomUUID();
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 上游（channel）的店铺清单 —— 测试自己往里塞，**不 mock 上游服务**。 */
  static final List<MessageContextPort.ShopView> upstreamShops = new CopyOnWriteArrayList<>();

  /** 上游（trade）的订单 —— 测试自己往里塞。 */
  static final List<MessageContextPort.OrderView> upstreamOrders = new CopyOnWriteArrayList<>();

  /** 让订单列表接口报 500（用来证明「读不到」不是「没有这一单」）。 */
  static final AtomicBoolean ordersBroken = new AtomicBoolean(false);

  static HttpServer identity;
  static HttpServer channel;
  static HttpServer trade;

  /** 固定的平台时刻（东八区偏移量，用来验时区不静默偏移）。 */
  static final String PLATFORM_AT = "2026-06-01T09:30:00+08:00";

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
    List<String> permissions =
        List.of(
            "crm.message.send.read",
            "crm.message.send.update",
            "crm.message.read",
            "crm.message.update",
            "crm.knowledge.read",
            "crm.knowledge.update");
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
    // 上游 stub：真的起 HTTP 服务，路径与真实 channel / trade 服务同形。
    try {
      channel = HttpServer.create(new InetSocketAddress(0), 0);
      channel.createContext("/channel/shops", e -> write(e, List.copyOf(upstreamShops)));
      channel.setExecutor(Executors.newFixedThreadPool(2));
      channel.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    try {
      trade = HttpServer.create(new InetSocketAddress(0), 0);
      trade.createContext(
          "/trade/sales-orders/list",
          e -> {
            if (ordersBroken.get()) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            String query = e.getRequestURI().getQuery();
            String keyword = "";
            if (query != null) {
              for (String part : query.split("&")) {
                if (part.startsWith("keyword=")) {
                  keyword = java.net.URLDecoder.decode(part.substring("keyword=".length()));
                }
              }
            }
            final String kw = keyword;
            var rows =
                upstreamOrders.stream()
                    .filter(
                        o ->
                            kw.isEmpty()
                                || (o.orderNo() != null && o.orderNo().contains(kw))
                                || (o.externalOrderNo() != null && o.externalOrderNo().contains(kw))
                                || (o.buyerName() != null && o.buyerName().contains(kw)))
                    .toList();
            write(
                e,
                Map.of(
                    "rows",
                    rows,
                    "page",
                    0,
                    "size",
                    rows.size(),
                    "total",
                    rows.size(),
                    "dataScope",
                    "ALL",
                    "dataScopeNote",
                    "本租户全量"));
          });
      trade.setExecutor(Executors.newFixedThreadPool(2));
      trade.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static void write(com.sun.net.httpserver.HttpExchange e, Object body) {
    try {
      byte[] bytes = json.writeValueAsBytes(body);
      e.getResponseHeaders().add("Content-Type", "application/json");
      e.sendResponseHeaders(200, bytes.length);
      e.getResponseBody().write(bytes);
      e.close();
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
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
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:" + channel.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://127.0.0.1:" + trade.getAddress().getPort());
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
      // ⚠️ 顺序：先清引用方（发送记录），再清被引用方（消息、知识）。
      s.execute(
          "truncate table crm_message_send_attachment, crm_message_send_attempt,"
              + " crm_message_send, crm_message_send_template");
      s.execute("truncate table crm_knowledge_reference, crm_knowledge_version, crm_knowledge_entry");
      s.execute("truncate table crm_message_attachment, crm_message_intent, crm_message");
    }
    upstreamShops.clear();
    upstreamOrders.clear();
    ordersBroken.set(false);
    upstreamShops.add(
        new MessageContextPort.ShopView(
            shopId, "SHOP-A", "美国主店", "AMAZON", "亚马逊", "SELF_FULFILLED", "ACTIVE"));
    upstreamOrders.add(
        new MessageContextPort.OrderView(
            UUID.randomUUID(),
            "SO-1001",
            "AMZ-1001",
            "AMAZON",
            null,
            "SUBMITTED",
            "USD",
            new java.math.BigDecimal("39.90"),
            2,
            UUID.randomUUID(),
            "Ada Buyer",
            List.of("SKU-A", "SKU-B"),
            OffsetDateTime.parse("2026-05-30T10:00:00+08:00"),
            OffsetDateTime.parse("2026-05-30T09:59:00+08:00"),
            "AWAITING_SHIPMENT",
            "PAID",
            "UNFULFILLED",
            "WH-01",
            null));
  }

  /**
   * 在**可信租户事务**里读。
   *
   * <p>⚠️ 业务表都是 FORCE RLS：事务外读（没有 {@code app.current_tenant}）
   * 一行都看不到 —— 那正是租户隔离在起作用，不是「读不到数据」。
   */
  private <T> T tenantRead(java.util.function.Supplier<T> work) {
    return tenants.run(tenant, work);
  }

  /** 本租户的发送记录条数（事务外读会被 RLS 挡住，所以必须在租户事务里读）。 */
  private long sendCount() {
    return tenantRead(
        () -> sql.fetchOne("select count(*) from crm_message_send").get(0, Long.class));
  }

  private JsonNode postJson(String path, String content) throws Exception {
    MvcResult result =
        http.perform(
                MockMvcRequestBuilders.post(path)
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(content))
            .andReturn();
    String body = result.getResponse().getContentAsString();
    assertThat(result.getResponse().getStatus())
        .as("POST %s 返回 %s：%s", path, result.getResponse().getStatus(), body)
        .isEqualTo(200);
    return json.readTree(body);
  }

  private JsonNode getJson(String path) throws Exception {
    MvcResult result =
        http.perform(MockMvcRequestBuilders.get(path).header("Authorization", "session")).andReturn();
    String body = result.getResponse().getContentAsString();
    assertThat(result.getResponse().getStatus())
        .as("GET %s 返回 %s：%s", path, result.getResponse().getStatus(), body)
        .isEqualTo(200);
    return json.readTree(body);
  }

  /** 登记一条收到的消息（15.1.1.1 收件箱的真实入口）。 */
  private String createMessage(String buyer, String order) throws Exception {
    return postJson(
            "/crm/message-inbox/messages",
            """
            {"shopId":"%s","buyerReference":"%s","orderReference":"%s","subject":"关于发货",
             "direction":"INBOUND","messageType":"BUYER_MESSAGE","priority":"NORMAL",
             "platformTime":"%s","body":"买家留言正文"}
            """
                .formatted(shopId, buyer, order, PLATFORM_AT))
        .get("id")
        .asText();
  }

  /** 直接写一条「发出去方向」的消息（用来证明不能回复自己发出去的那条）。 */
  private String createOutboundMessage() throws Exception {
    return postJson(
            "/crm/message-inbox/messages",
            """
            {"shopId":"%s","buyerReference":"buyer-out","orderReference":"SO-OUT","subject":"我们发出的",
             "direction":"OUTBOUND","messageType":"BUYER_MESSAGE","priority":"NORMAL",
             "platformTime":"%s","body":"我们已发出"}
            """
                .formatted(shopId, PLATFORM_AT))
        .get("id")
        .asText();
  }

  /** 建一条回复模板。 */
  private String createTemplate(String code, String subject, String body) throws Exception {
    return postJson(
            "/crm/message-sends/templates",
            """
            {"templateCode":"%s","name":"测试模板","locale":"zh-CN","subject":"%s","body":"%s"}
            """
                .formatted(code, subject, body))
        .get("id")
        .asText();
  }

  /** 建一条知识库条目 + 已发布版本（15.4.2 上游的真实入口）。 */
  private String createPublishedKnowledge(String answer) throws Exception {
    String entryId =
        postJson(
                "/crm/knowledge/entries",
                """
                {"topicKey":"FAQ-SHIP","title":"发货时效","shopId":"%s","shopName":"美国主店",
                 "categoryId":"%s","categoryName":"物流","locale":"zh-CN","platformCode":"AMAZON",
                 "topicType":"FAQ"}
                """
                    .formatted(shopId, UUID.randomUUID()))
            .get("id")
            .asText();
    String versionId =
        postJson(
                "/crm/knowledge/entries/" + entryId + "/versions",
                """
                {"versionNo":1,"question":"多久发货？","answer":"%s","effectiveFrom":"2026-01-01T00:00:00+08:00"}
                """
                    .formatted(answer))
            .get("id")
            .asText();
    postJson("/crm/knowledge/versions/" + versionId + "/publish", "{\"note\":\"发布\"}");
    return versionId;
  }

  /** 发送请求体。 */
  private String sendBody(
      String messageId, String body, String idempotencyKey, String transport, String status) {
    return """
        {"targetMessageId":"%s","body":"%s","transport":"%s","status":"%s",
         "idempotencyKey":"%s"}
        """
        .formatted(messageId, body, transport, status, idempotencyKey);
  }

  /** 源消息那一行的**逐列快照**（用来证明「发送失败不改原消息状态」）。 */
  private Map<String, Object> messageSnapshot(String messageId) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    var row =
        tenantRead(
            () ->
                sql.fetchOne(
                    "select id,tenant_id,message_no,shop_id,shop_name,platform_code,buyer_reference,"
                        + " order_reference,subject,body,direction,message_type,priority,read_status,"
                        + " read_at,read_by,platform_time,attachment_count,source,external_message_id,"
                        + " created_by,note,created_at,updated_at from crm_message where id=?",
                    UUID.fromString(messageId)));
    assertThat(row).as("源消息必须存在").isNotNull();
    for (var field : row.fields()) {
      Object value = row.get(field);
      snapshot.put(field.getName(), value instanceof OffsetDateTime moment ? moment.toInstant() : value);
    }
    return snapshot;
  }

  // ================================================================ 迁移与 RLS

  @Test
  void 迁移落地且业务表强制rls() {
    for (String table :
        List.of(
            "crm_message_send_template",
            "crm_message_send",
            "crm_message_send_attachment",
            "crm_message_send_attempt")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V297 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住").isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('crm_app','" + table + "','SELECT')")
                  .get(0, Boolean.class))
          .as("crm_app 必须真的能读 " + table)
          .isTrue();
    }
    // 序列单独授权：漏了就会变成「第一条发送记录就报权限不足」。
    assertThat(
            sql.fetchOne(
                    "select has_sequence_privilege('crm_app','crm_message_send_no_seq','USAGE')")
                .get(0, Boolean.class))
        .as("crm_app 必须能用发送号序列")
        .isTrue();
    // 本迁移零外键：外键会打破既有测试的 TRUNCATE。
    assertThat(
            sql.fetch(
                "select conname from pg_constraint where contype='f'"
                    + " and conrelid::regclass::text like 'crm_message_send%'"))
        .as("本迁移必须零外键")
        .isEmpty();
  }

  @Test
  void 能力声明把未接入的维度标出来() throws Exception {
    JsonNode caps = getJson("/crm/message-sends/capabilities");
    Map<String, JsonNode> byDimension = new LinkedHashMap<>();
    for (JsonNode dimension : caps.get("dimensions")) {
      byDimension.put(dimension.get("dimension").asText(), dimension);
    }
    assertThat(byDimension).containsKey("platform_message_api");
    assertThat(byDimension.get("platform_message_api").get("available").asBoolean())
        .as("平台发送接口未接入，必须 available=false")
        .isFalse();
    assertThat(byDimension.get("platform_message_api").get("ownerModule").asText()).isEqualTo("2");
    for (String delivered :
        List.of(
            "reply_templates",
            "knowledge_reply_source",
            "manual_edit",
            "attachments",
            "send_records",
            "failure_retry",
            "batch_identical_reply",
            "idempotency")) {
      assertThat(byDimension.get(delivered).get("available").asBoolean())
          .as(delivered + " 必须 available=true")
          .isTrue();
    }
    // 下游未交付的模块一律标 available=false + ownerModule。
    for (String missing : List.of("message_classification", "auto_reply", "email_same_thread")) {
      assertThat(byDimension.get(missing).get("available").asBoolean())
          .as(missing + " 未交付，必须 available=false")
          .isFalse();
      assertThat(byDimension.get(missing).get("ownerModule").asText()).isNotBlank();
    }
  }

  // ================================================================ 模板

  @Test
  void 模板能建能改能删且编码唯一() throws Exception {
    String id = createTemplate("TPL-1", "关于发货", "您好，我们会在 24 小时内发出。");
    JsonNode list = getJson("/crm/message-sends/templates?keyword=TPL-1");
    assertThat(list.get("total").asInt()).isEqualTo(1);
    JsonNode row = list.get("items").get(0);
    assertThat(row.get("templateCode").asText()).isEqualTo("TPL-1");
    assertThat(row.get("status").asText()).isEqualTo("ACTIVE");

    // 重复编码被拒。
    http.perform(
            MockMvcRequestBuilders.post("/crm/message-sends/templates")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"templateCode":"TPL-1","name":"重复","locale":"zh-CN","body":"x"}
                    """))
        .andExpect(status().isBadRequest());

    // 改模板。
    MvcResult updated =
        http.perform(
                MockMvcRequestBuilders.put("/crm/message-sends/templates/" + id)
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"templateCode":"TPL-1","name":"改名了","locale":"en-US","body":"Updated"}
                        """))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode row2 = json.readTree(updated.getResponse().getContentAsString());
    assertThat(row2.get("name").asText()).isEqualTo("改名了");
    assertThat(row2.get("locale").asText()).isEqualTo("en-US");

    // 删（还没有发送记录引用 ⇒ 真删）。
    http.perform(
            MockMvcRequestBuilders.delete("/crm/message-sends/templates/" + id)
                .header("Authorization", "session"))
        .andExpect(status().isOk());
    assertThat(
            tenantRead(
                () ->
                    sql.fetchOne(
                                "select count(*) from crm_message_send_template where id=?",
                                UUID.fromString(id))
                            .get(0, Long.class)))
        .isZero();
  }

  @Test
  void 被发送记录引用过的模板只停用不删除() throws Exception {
    String templateId = createTemplate("TPL-2", "关于发货", "您好，我们会尽快发出。");
    String messageId = createMessage("buyer-ref", "SO-1001");
    postJson(
        "/crm/message-sends/sends",
        """
        {"targetMessageId":"%s","templateId":"%s","transport":"MANUAL_HANDOFF","status":"QUEUED",
         "idempotencyKey":"key-ref-template"}
        """
            .formatted(messageId, templateId));

    http.perform(
            MockMvcRequestBuilders.delete("/crm/message-sends/templates/" + templateId)
                .header("Authorization", "session"))
        .andExpect(status().isOk());

    var row =
        tenantRead(
            () ->
                sql.fetchOne(
                    "select status from crm_message_send_template where id=?",
                    UUID.fromString(templateId)));
    assertThat(row).as("模板不能被真删：历史发送记录还要靠它读出「当时用的哪套话术」").isNotNull();
    assertThat(row.get("status", String.class)).isEqualTo("DISABLED");
  }

  // ================================================================ 发送

  @Test
  void 手工发送真的落库并生成发送记录() throws Exception {
    String messageId = createMessage("buyer-manual", "SO-1001");
    JsonNode outcome =
        postJson(
            "/crm/message-sends/sends",
            sendBody(messageId, "您好，已为您安排补发。", "key-manual-1", "MANUAL_HANDOFF", "QUEUED"));

    JsonNode send = outcome.get("send");
    assertThat(send.get("sendNo").asText()).startsWith("MSGS-");
    assertThat(send.get("status").asText()).isEqualTo("QUEUED");
    assertThat(send.get("bodySource").asText()).isEqualTo("MANUAL");
    assertThat(send.get("body").asText()).isEqualTo("您好，已为您安排补发。");
    assertThat(send.get("attemptCount").asInt()).isEqualTo(1);
    assertThat(outcome.get("replayed").asBoolean()).isFalse();
    assertThat(outcome.get("target").get("repliedState").asText()).isEqualTo("UNREPLIED");

    // 发送记录真的在库里。
    assertThat(
            tenantRead(
                () ->
                    sql.fetchOne(
                            "select count(*) from crm_message_send where idempotency_key=?",
                            "key-manual-1")
                        .get(0, Long.class)))
        .isEqualTo(1);
    // 尝试留痕也有一行。
    assertThat(
            tenantRead(
                () ->
                    sql.fetchOne("select count(*) from crm_message_send_attempt").get(0, Long.class)))
        .isEqualTo(1);
  }

  @Test
  void 附件真的落库且计数由数据库维护() throws Exception {
    String messageId = createMessage("buyer-attach", "SO-1001");
    JsonNode outcome =
        postJson(
            "/crm/message-sends/sends",
            """
            {"targetMessageId":"%s","body":"带附件的回复","transport":"MANUAL_HANDOFF","status":"QUEUED",
             "idempotencyKey":"key-attach-1",
             "attachments":[{"fileName":"invoice.pdf","fileUrl":"https://cdn/invoice.pdf",
                             "contentType":"application/pdf","sizeBytes":2048.5}]}
            """
                .formatted(messageId));
    assertThat(outcome.get("send").get("attachmentCount").asInt()).isEqualTo(1);

    JsonNode detail = getJson("/crm/message-sends/sends/" + outcome.get("send").get("id").asText());
    assertThat(detail.get("attachments")).hasSize(1);
    assertThat(detail.get("attachments").get(0).get("fileName").asText()).isEqualTo("invoice.pdf");
    // 字节数是 numeric(19,6) ⇒ 原样透传，禁浮点。
    assertThat(detail.get("attachments").get(0).get("sizeBytes").decimalValue())
        .isEqualByComparingTo("2048.5");
  }

  @Test
  void 模板真的被调用且变量被渲染() throws Exception {
    String templateId =
        createTemplate(
            "TPL-3",
            "关于 {{order_no}}",
            "您好 {{buyer_name}}，订单 {{order_no}} 金额 {{currency}} {{order_amount}}，"
                + "商品 {{sku_list}}，我们会尽快发出。");
    String messageId = createMessage("buyer-tpl", "SO-1001");
    JsonNode outcome =
        postJson(
            "/crm/message-sends/sends",
            """
            {"targetMessageId":"%s","templateId":"%s","transport":"MANUAL_HANDOFF","status":"QUEUED",
             "idempotencyKey":"key-tpl-1"}
            """
                .formatted(messageId, templateId));
    JsonNode send = outcome.get("send");
    assertThat(send.get("bodySource").asText()).isEqualTo("TEMPLATE");
    assertThat(send.get("templateCode").asText()).isEqualTo("TPL-3");
    String body = send.get("body").asText();
    assertThat(body).contains("Ada Buyer");
    assertThat(body).contains("SO-1001");
    assertThat(body).contains("39.90");
    assertThat(body).contains("SKU-A、SKU-B");
    assertThat(body).doesNotContain("{{");
  }

  @Test
  void 模板起稿后手工编辑会留痕() throws Exception {
    String templateId = createTemplate("TPL-4", "关于发货", "您好，我们会在 24 小时内发出。");
    String messageId = createMessage("buyer-edit", "SO-1001");
    JsonNode outcome =
        postJson(
            "/crm/message-sends/sends",
            """
            {"targetMessageId":"%s","templateId":"%s","body":"改过的正文：我们今天就发出。",
             "transport":"MANUAL_HANDOFF","status":"QUEUED","idempotencyKey":"key-edit-1"}
            """
                .formatted(messageId, templateId));
    assertThat(outcome.get("send").get("bodySource").asText()).isEqualTo("TEMPLATE_EDITED");
    assertThat(outcome.get("send").get("body").asText()).isEqualTo("改过的正文：我们今天就发出。");
  }

  @Test
  void 知识库话术能被调用并登记引用() throws Exception {
    String versionId = createPublishedKnowledge("知识库的标准答法：三个工作日内发出。");
    String messageId = createMessage("buyer-kb", "SO-1001");
    JsonNode outcome =
        postJson(
            "/crm/message-sends/sends",
            """
            {"targetMessageId":"%s","knowledgeVersionId":"%s","transport":"MANUAL_HANDOFF","status":"SENT",
             "idempotencyKey":"key-kb-1"}
            """
                .formatted(messageId, versionId));
    assertThat(outcome.get("send").get("bodySource").asText()).isEqualTo("KNOWLEDGE");
    assertThat(outcome.get("send").get("body").asText())
        .isEqualTo("知识库的标准答法：三个工作日内发出。");
    assertThat(outcome.get("send").get("knowledgeVersionNo").asInt()).isEqualTo(1);

    // 引用记录真的登记进知识库了（使「买家当时收到了哪一版」可查）。
    var reference =
        tenantRead(
            () ->
                sql.fetchOne(
                    "select consumer, source_ref from crm_knowledge_reference where version_id=?",
                    UUID.fromString(versionId)));
    assertThat(reference).as("引用知识版本必须登记引用记录").isNotNull();
    assertThat(reference.get("consumer", String.class)).isEqualTo("MANUAL_REPLY");
    assertThat(reference.get("source_ref", String.class)).isEqualTo(outcome.get("send").get("sendNo").asText());
  }

  @Test
  void 未定义的模板变量让发送失败而不是把占位符发给买家() throws Exception {
    String templateId = createTemplate("TPL-5", "标题", "您好 {{unknown_variable}}。");
    String messageId = createMessage("buyer-unknown-var", "SO-1001");
    http.perform(
            MockMvcRequestBuilders.post("/crm/message-sends/sends")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"targetMessageId":"%s","templateId":"%s","transport":"MANUAL_HANDOFF",
                     "status":"QUEUED","idempotencyKey":"key-unknown-var"}
                    """
                        .formatted(messageId, templateId)))
        .andExpect(status().isBadRequest());
    assertThat(
            sendCount())
        .as("失败的发送不许留下发送记录")
        .isZero();
  }

  @Test
  void 订单上下文读不到时依赖它的变量让发送失败() throws Exception {
    String templateId = createTemplate("TPL-6", "标题", "订单金额 {{order_amount}}。");
    String messageId = createMessage("buyer-no-order", "SO-1001");
    ordersBroken.set(true);
    http.perform(
            MockMvcRequestBuilders.post("/crm/message-sends/sends")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"targetMessageId":"%s","templateId":"%s","transport":"MANUAL_HANDOFF",
                     "status":"QUEUED","idempotencyKey":"key-no-order"}
                    """
                        .formatted(messageId, templateId)))
        .andExpect(status().isBadRequest());
    assertThat(
            sendCount())
        .as("填不上的变量不能让发送通过")
        .isZero();
  }

  @Test
  void 平台发送接口未接入时明确拒绝且一条数据都不写() throws Exception {
    String messageId = createMessage("buyer-platform", "SO-1001");
    http.perform(
            MockMvcRequestBuilders.post("/crm/message-sends/sends")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    sendBody(messageId, "假装发出去", "key-platform", "PLATFORM_API", "SENT")))
        .andExpect(status().isConflict());
    assertThat(
            sendCount())
        .as("未接入的投递通道一次数据都不写")
        .isZero();
    assertThat(
            tenantRead(
                () ->
                    sql.fetchOne("select count(*) from crm_message_send_attempt").get(0, Long.class)))
        .isZero();
  }

  @Test
  void 只能回复收到的消息() throws Exception {
    String outboundId = createOutboundMessage();
    http.perform(
            MockMvcRequestBuilders.post("/crm/message-sends/sends")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    sendBody(outboundId, "回复自己", "key-outbound", "MANUAL_HANDOFF", "QUEUED")))
        .andExpect(status().isBadRequest());
    assertThat(
            sendCount())
        .isZero();
  }

  // ================================================================ 🔴 加粗句

  @Test
  void 发送失败保留原消息状态() throws Exception {
    String messageId = createMessage("buyer-fail", "SO-1001");
    Map<String, Object> before = messageSnapshot(messageId);

    // 登记一次失败（人工交接后回登记失败原因）。
    JsonNode outcome =
        postJson(
            "/crm/message-sends/sends",
            """
            {"targetMessageId":"%s","body":"会失败的回复","transport":"MANUAL_HANDOFF","status":"FAILED",
             "failureReason":"平台侧返回限流","idempotencyKey":"key-fail-1"}
            """
                .formatted(messageId));
    assertThat(outcome.get("send").get("status").asText()).isEqualTo("FAILED");
    assertThat(outcome.get("send").get("failureReason").asText()).isEqualTo("平台侧返回限流");

    // 🔴 源消息那一行**逐列没变**（含 updated_at）。
    Map<String, Object> after = messageSnapshot(messageId);
    assertThat(after)
        .as("发送失败后源消息必须逐列不变 —— 客服不能看到「已回复」而实际没发出去")
        .isEqualTo(before);

    // 推导出来的「已回复」状态也**没变**。
    assertThat(outcome.get("target").get("repliedState").asText())
        .as("发送失败后仍然是未回复")
        .isEqualTo("UNREPLIED");
    JsonNode targets = getJson("/crm/message-sends/targets?onlyUnreplied=true");
    assertThat(targets.get("unrepliedCount").asInt()).isEqualTo(1);
  }

  @Test
  void 登记已发出后目标消息才算已回复() throws Exception {
    String messageId = createMessage("buyer-sent", "SO-1001");
    JsonNode outcome =
        postJson(
            "/crm/message-sends/sends",
            sendBody(messageId, "已经发出去了", "key-sent-1", "MANUAL_HANDOFF", "SENT"));
    assertThat(outcome.get("target").get("repliedState").asText()).isEqualTo("REPLIED");
    assertThat(outcome.get("target").get("repliedSendNo").asText())
        .isEqualTo(outcome.get("send").get("sendNo").asText());
    // 已发出必须有发出时刻。
    assertThat(outcome.get("send").get("sentAt").asText()).isNotBlank();
  }

  // ================================================================ 幂等

  @Test
  void 同一个幂等键重放不会产生第二条记录() throws Exception {
    String messageId = createMessage("buyer-idem", "SO-1001");
    JsonNode first =
        postJson(
            "/crm/message-sends/sends",
            sendBody(messageId, "第一次", "key-idem-1", "MANUAL_HANDOFF", "QUEUED"));
    JsonNode second =
        postJson(
            "/crm/message-sends/sends",
            sendBody(messageId, "第二次（重放）", "key-idem-1", "MANUAL_HANDOFF", "QUEUED"));

    assertThat(first.get("replayed").asBoolean()).isFalse();
    assertThat(second.get("replayed").asBoolean()).as("重放必须被识别出来").isTrue();
    assertThat(second.get("send").get("id").asText()).isEqualTo(first.get("send").get("id").asText());
    assertThat(second.get("send").get("body").asText())
        .as("重放不得改写原记录")
        .isEqualTo("第一次");
    assertThat(
            sendCount())
        .isEqualTo(1);
  }

  // ================================================================ 重试

  @Test
  void 失败可以重试且每次尝试都留痕() throws Exception {
    String messageId = createMessage("buyer-retry", "SO-1001");
    JsonNode failed =
        postJson(
            "/crm/message-sends/sends",
            """
            {"targetMessageId":"%s","body":"第一次尝试","transport":"MANUAL_HANDOFF","status":"FAILED",
             "failureReason":"平台侧返回限流","idempotencyKey":"key-retry-1"}
            """
                .formatted(messageId));
    String sendId = failed.get("send").get("id").asText();

    // 重试：开一次新的尝试，状态回到待发。
    JsonNode retried =
        postJson("/crm/message-sends/sends/" + sendId + "/retry", "{\"operatorNote\":\"再试一次\"}");
    assertThat(retried.get("send").get("status").asText()).isEqualTo("QUEUED");
    assertThat(retried.get("send").get("attemptCount").asInt()).isEqualTo(2);
    assertThat(retried.get("target").get("repliedState").asText())
        .as("重试期间仍然是未回复")
        .isEqualTo("UNREPLIED");

    // 第二次尝试登记成功。
    JsonNode done =
        postJson(
            "/crm/message-sends/sends/" + sendId + "/result",
            """
            {"status":"SENT","platformReceipt":"平台回执 ABC","platformMessageId":"PM-1"}
            """);
    assertThat(done.get("send").get("status").asText()).isEqualTo("SENT");
    assertThat(done.get("send").get("platformReceipt").asText()).isEqualTo("平台回执 ABC");
    assertThat(done.get("target").get("repliedState").asText()).isEqualTo("REPLIED");

    // 两次尝试各一行，失败原因仍在第一次那一行里。
    JsonNode detail = getJson("/crm/message-sends/sends/" + sendId);
    assertThat(detail.get("attempts")).hasSize(2);
    assertThat(detail.get("attempts").get(0).get("status").asText()).isEqualTo("FAILED");
    assertThat(detail.get("attempts").get(0).get("failureReason").asText())
        .isEqualTo("平台侧返回限流");
    assertThat(detail.get("attempts").get(1).get("status").asText()).isEqualTo("SENT");
  }

  @Test
  void 已发出的记录不能再重试() throws Exception {
    String messageId = createMessage("buyer-noretry", "SO-1001");
    JsonNode sent =
        postJson(
            "/crm/message-sends/sends",
            sendBody(messageId, "已经发出", "key-noretry", "MANUAL_HANDOFF", "SENT"));
    http.perform(
            MockMvcRequestBuilders.post(
                    "/crm/message-sends/sends/" + sent.get("send").get("id").asText() + "/retry")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  // ================================================================ 批量相同回复

  @Test
  void 批量相同回复逐条给出结论() throws Exception {
    String first = createMessage("buyer-b1", "SO-1001");
    String second = createMessage("buyer-b2", "SO-1001");
    String missing = UUID.randomUUID().toString();

    JsonNode result =
        postJson(
            "/crm/message-sends/batch",
            """
            {"messageIds":["%s","%s","%s"],"body":"同一句回复","transport":"MANUAL_HANDOFF",
             "idempotencyKey":"key-batch-1"}
            """
                .formatted(first, second, missing));

    assertThat(result.get("requestedCount").asInt()).isEqualTo(3);
    assertThat(result.get("succeededCount").asInt()).isEqualTo(2);
    assertThat(result.get("failedCount").asInt()).isEqualTo(1);
    assertThat(result.get("items")).hasSize(3);
    // 失败的那一条**带原因**，不会被悄悄跳过。
    JsonNode failedItem = null;
    for (JsonNode item : result.get("items")) {
      if (!item.get("success").asBoolean()) {
        failedItem = item;
      }
    }
    assertThat(failedItem).as("失败的条目必须逐条给出结论").isNotNull();
    assertThat(failedItem.get("reason").asText()).isNotBlank();
    assertThat(failedItem.get("messageId").asText()).isEqualTo(missing);

    // 同批次共享批次号。
    String batchId = result.get("batchId").asText();
    assertThat(
            tenantRead(
                () ->
                    sql.fetchOne(
                                "select count(distinct batch_id) from crm_message_send where batch_id=?",
                                UUID.fromString(batchId))
                            .get(0, Long.class)))
        .isEqualTo(1);
    assertThat(
            tenantRead(
                () ->
                    sql.fetchOne(
                                "select count(*) from crm_message_send where batch_id=?",
                                UUID.fromString(batchId))
                            .get(0, Long.class)))
        .isEqualTo(2);
  }

  @Test
  void 批量重放不会发第二遍() throws Exception {
    String first = createMessage("buyer-breplay", "SO-1001");
    String body =
        """
        {"messageIds":["%s"],"body":"批量回复","transport":"MANUAL_HANDOFF","idempotencyKey":"key-batch-replay"}
        """
            .formatted(first);
    postJson("/crm/message-sends/batch", body);
    postJson("/crm/message-sends/batch", body);
    assertThat(
            sendCount())
        .as("同一批重放不会产生第二条发送记录")
        .isEqualTo(1);
  }

  // ================================================================ 列表与筛选

  @Test
  void 发送记录列表是真分页真筛选() throws Exception {
    String first = createMessage("buyer-p1", "SO-1001");
    String second = createMessage("buyer-p2", "SO-2002");
    postJson(
        "/crm/message-sends/sends",
        sendBody(first, "第一条回复", "key-page-1", "MANUAL_HANDOFF", "SENT"));
    postJson(
        "/crm/message-sends/sends",
        """
        {"targetMessageId":"%s","body":"第二条回复","transport":"MANUAL_HANDOFF","status":"FAILED",
         "failureReason":"平台侧返回限流","idempotencyKey":"key-page-2"}
        """
            .formatted(second));

    JsonNode all = getJson("/crm/message-sends/sends");
    assertThat(all.get("total").asInt()).isEqualTo(2);
    assertThat(all.get("summary").get("sentCount").asInt()).isEqualTo(1);
    assertThat(all.get("summary").get("failedCount").asInt()).isEqualTo(1);

    JsonNode failed = getJson("/crm/message-sends/sends?status=FAILED");
    assertThat(failed.get("total").asInt()).isEqualTo(1);
    assertThat(failed.get("items").get(0).get("idempotencyKey").asText()).isEqualTo("key-page-2");

    JsonNode keyword = getJson("/crm/message-sends/sends?keyword=第二条");
    assertThat(keyword.get("total").asInt()).isEqualTo(1);
    assertThat(keyword.get("items").get(0).get("idempotencyKey").asText()).isEqualTo("key-page-2");

    // 真分页：size=1 时只给一条，但总数仍然是 2。
    JsonNode page = getJson("/crm/message-sends/sends?page=0&size=1");
    assertThat(page.get("items")).hasSize(1);
    assertThat(page.get("total").asInt()).isEqualTo(2);
  }

  @Test
  void 目标列表能筛出未回复的() throws Exception {
    String first = createMessage("buyer-t1", "SO-1001");
    String second = createMessage("buyer-t2", "SO-1001");
    postJson(
        "/crm/message-sends/sends",
        sendBody(first, "已回", "key-target-1", "MANUAL_HANDOFF", "SENT"));

    JsonNode unreplied = getJson("/crm/message-sends/targets?onlyUnreplied=true");
    assertThat(unreplied.get("total").asInt()).isEqualTo(1);
    assertThat(unreplied.get("items").get(0).get("buyerReference").asText()).isEqualTo("buyer-t2");

    JsonNode all = getJson("/crm/message-sends/targets");
    assertThat(all.get("total").asInt()).isEqualTo(2);
    assertThat(all.get("unrepliedCount").asInt()).isEqualTo(1);
  }

  // ================================================================ 时区

  @Test
  void 时刻写入不静默偏移() throws Exception {
    String messageId = createMessage("buyer-tz", "SO-1001");
    JsonNode outcome =
        postJson(
            "/crm/message-sends/sends",
            sendBody(messageId, "时区检查", "key-tz-1", "MANUAL_HANDOFF", "QUEUED"));
    String sendId = outcome.get("send").get("id").asText();
    var row =
        tenantRead(
            () ->
                sql.fetchOne(
                    "select created_at, last_attempt_at from crm_message_send where id=?",
                    UUID.fromString(sendId)));
    OffsetDateTime created = row.get("created_at", OffsetDateTime.class);
    OffsetDateTime attempted = row.get("last_attempt_at", OffsetDateTime.class);
    assertThat(created).as("写入的必须是同一个绝对时刻（不能偏 −28800 秒）").isNotNull();
    assertThat(attempted).isNotNull();
    assertThat(attempted.toInstant()).isEqualTo(created.toInstant());
    // 源消息的平台时刻按 +08:00 存，读回来仍是同一个绝对时刻。
    var message =
        tenantRead(
            () ->
                sql.fetchOne(
                    "select platform_time from crm_message where id=?", UUID.fromString(messageId)));
    assertThat(message.get("platform_time", OffsetDateTime.class).toInstant())
        .isEqualTo(OffsetDateTime.parse(PLATFORM_AT).toInstant());
  }

  // ================================================================ 越权

  @Test
  void 没有发送权限就进不来() throws Exception {
    http.perform(
            MockMvcRequestBuilders.get("/crm/message-sends/sends").header("Authorization", "nobody"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void 知识库来源只列生效版本() throws Exception {
    createPublishedKnowledge("生效的答法");
    JsonNode list = getJson("/crm/message-sends/knowledge-templates");
    assertThat(list).isNotEmpty();
    for (JsonNode item : list) {
      assertThat(item.get("versionNo").asInt()).isPositive();
      assertThat(item.get("answer").asText()).isNotBlank();
    }
  }

  @Test
  void 批量发送的每条都落在同一批次且各自有发送号() throws Exception {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      ids.add(createMessage("buyer-multi-" + i, "SO-1001"));
    }
    JsonNode result =
        postJson(
            "/crm/message-sends/batch",
            """
            {"messageIds":["%s"],"body":"批量","transport":"MANUAL_HANDOFF","idempotencyKey":"key-multi"}
            """
                .formatted(String.join("\",\"", ids)));
    assertThat(result.get("succeededCount").asInt()).isEqualTo(3);
    List<String> sendNos = new ArrayList<>();
    for (JsonNode item : result.get("items")) {
      sendNos.add(item.get("sendNo").asText());
    }
    assertThat(sendNos).doesNotHaveDuplicates();
  }
}
