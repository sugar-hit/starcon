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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * 15.2.2.2 索评规则的集成测试。
 *
 * <p>跑的是 AGENTS.md 要求的<b>真实 PostgreSQL 16 + FORCE RLS</b>。租户隔离三断言在
 * {@link ReviewRuleTenancyIntegrationTest} 里，本类专攻业务不变量：
 *
 * <ul>
 *   <li><b>🔴 生效策略只有一个答案</b>：同一适用范围同一优先级不允许有两条 ACTIVE 规则
 *       （DB 唯一索引挡住），命中时范围越具体越优先。
 *   <li><b>节假日规避按规则时区换算</b>：用 UTC 日界会把「东八区 10-02 00:00」算成 10-01，
 *       节假日就错开一天 —— 本类用一条<b>跨 UTC 日界</b>的排期把它钉死。
 *   <li><b>A/B 分桶是确定性的</b>：同一个种子永远落到同一个臂。
 *   <li><b>效果对比来自真实明细</b>：分配数 / 已发出 / 回收 / 平均星级都从真表聚合；
 *       分母为 0 时回收率<b>报空</b>而不是 0%。
 *   <li><b>时区不静默偏移（硬性要求 6）</b>：用 {@code +08:00} 的偏移量写入，
 *       再<b>断言写回的值</b>等于同一个绝对时刻。
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReviewRuleIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID userId = UUID.randomUUID();
  static final UUID shopId = UUID.randomUUID();
  static final UUID otherShopId = UUID.randomUUID();
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 上游（channel）的店铺清单。 */
  static final List<SignoffDeliveredOrderPort.ShopView> upstreamShops = new CopyOnWriteArrayList<>();

  static HttpServer identity;
  static HttpServer channel;

  /** 固定的上游签收时刻（东八区偏移量，用来验时区不静默偏移）。 */
  static final String DELIVERED_AT = "2026-10-01T07:00:00+08:00";

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
    List<String> permissions = List.of("crm.rule.read", "crm.rule.update");
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
    try {
      channel = HttpServer.create(new InetSocketAddress(0), 0);
      channel.createContext(
          "/channel/shops",
          e -> {
            byte[] body = json.writeValueAsBytes(List.copyOf(upstreamShops));
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      channel.setExecutor(Executors.newFixedThreadPool(2));
      channel.start();
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
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:" + channel.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  @BeforeEach
  void reset() throws Exception {
    upstreamShops.clear();
    upstreamShops.add(
        new SignoffDeliveredOrderPort.ShopView(
            shopId, "SHOP-1", "主店铺", "AMAZON", "亚马逊", "SELF_FULFILLED", "ACTIVE"));
    upstreamShops.add(
        new SignoffDeliveredOrderPort.ShopView(
            otherShopId, "SHOP-2", "平台仓店铺", "AMAZON", "亚马逊", "OVERSEAS_WAREHOUSE", "ACTIVE"));
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table crm_review_rule_assignment, crm_review_holiday,"
              + " crm_review_message_variant, crm_review_message_template, crm_review_rule,"
              + " crm_review_recovery, crm_review_send_record, crm_review_request,"
              + " crm_review_frequency_policy restart identity cascade");
    }
    seedFrequencyPolicy();
  }

  /**
   * 15.2.2.1 的频次硬上限（V220 的触发器要求它存在，否则索评请求写不进去）。
   *
   * <p>本模块**改不动**它 —— 这正是「频次上限是兜底闸门」的意思。
   */
  void seedFrequencyPolicy() {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into crm_review_frequency_policy"
                  + "(id,tenant_id,max_per_buyer_per_window,window_days,min_cooldown_hours,"
                  + " max_per_order,min_delay_hours,max_batch_size,created_at,updated_at)"
                  + " values (?,?,3,30,1,3,1,200,now(),now())",
              UUID.randomUUID(),
              tenant);
          return null;
        });
  }

  /** 往 15.2.2.1 的索评请求表里塞一条（**同库只读**的目标事实）。 */
  UUID seedReviewRequest(String buyerRef, String orderNo, String deliveredAt) {
    UUID id = UUID.randomUUID();
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into crm_review_request"
                  + "(id,tenant_id,request_no,shop_id,shop_name,platform_code,sales_order_no,"
                  + " buyer_ref,logistics_status,delivered_at,status,rule_delay_hours,"
                  + " rule_cooldown_hours,rule_max_per_buyer,rule_max_per_order,scheduled_at,"
                  + " created_at,updated_at)"
                  + " values (?,?,?,?,'主店铺','AMAZON',?,?,'DELIVERED',?::timestamptz,'SCHEDULED',"
                  + " 48,1,3,3,?::timestamptz,?::timestamptz,?::timestamptz)",
              id,
              tenant,
              "RR-T-" + buyerRef,
              shopId,
              orderNo,
              buyerRef,
              OffsetDateTime.parse(deliveredAt),
              OffsetDateTime.parse(deliveredAt).plusHours(48),
              OffsetDateTime.parse(deliveredAt),
              OffsetDateTime.parse(deliveredAt));
          return null;
        });
    return id;
  }

  /** 登记一条评价回收（15.2.2.1 的回收桶）—— 效果对比的「回收」来自它。 */
  void seedRecovery(UUID requestId, int rating) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into crm_review_recovery"
                  + "(id,tenant_id,request_id,rating,content,has_image,has_video,platform_code,"
                  + " source,received_at,created_at)"
                  + " values (?,?,?,?,'',false,false,'AMAZON','MANUAL_ENTRY',?::timestamptz,now())",
              UUID.randomUUID(),
              tenant,
              requestId,
              rating,
              OffsetDateTime.parse(DELIVERED_AT).plusDays(3));
          return null;
        });
  }

  /** 把索评请求标成「已发出」。 */
  void markSent(UUID requestId) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "update crm_review_request set status='SENT' where tenant_id=? and id=?",
              tenant,
              requestId);
          return null;
        });
  }

  // ================================================================ HTTP 助手

  JsonNode postJson(String path, Object body) throws Exception {
    var result =
        http.perform(
                post(path)
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(body)))
            .andReturn();
    if (result.getResponse().getStatus() != 200) {
      throw new AssertionError(
          "HTTP "
              + result.getResponse().getStatus()
              + " : "
              + result.getResponse().getContentAsString());
    }
    return json.readTree(result.getResponse().getContentAsString());
  }

  JsonNode putJson(String path, Object body) throws Exception {
    var result =
        http.perform(
                put(path)
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(body)))
            .andReturn();
    if (result.getResponse().getStatus() != 200) {
      throw new AssertionError(
          "HTTP "
              + result.getResponse().getStatus()
              + " : "
              + result.getResponse().getContentAsString());
    }
    return json.readTree(result.getResponse().getContentAsString());
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

  int deleteStatus(String path) throws Exception {
    return http.perform(delete(path).header("Authorization", "session"))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  /**
   * 失败时把**原因**取出来。
   *
   * <p>⚠️ 不能读响应体：{@code ResponseStatusException} 走 Spring 的 {@code sendError}，
   * MockMvc 里**响应体是空的**，原因在 {@code getResolvedException()} 上。
   *
   * <p>⚠️ 也**不能只读** {@code getMessage()}：{@code ResponseStatusException} 的
   * {@code getMessage()} 走 {@code NestedRuntimeException}，拿到的是**cause** 的消息
   * （比如 jOOQ 的 "duplicate key value violates ..."），**不含**人话原因。
   * 人话在 {@code getReason()} 里 —— 必须把两者拼起来断言，
   * 否则断言就变成在测「数据库报的是哪条约束」而不是「接口告诉了运营什么」。
   */
  String errorOf(String method, String path, Object body) throws Exception {
    var request =
        method.equals("PUT")
            ? put(path)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))
            : method.equals("DELETE")
                ? delete(path).header("Authorization", "session")
                : post(path)
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(body));
    var result = http.perform(request).andReturn();
    var resolved = result.getResolvedException();
    if (resolved == null) return "";
    String message = resolved.getMessage() == null ? "" : resolved.getMessage();
    if (resolved instanceof org.springframework.web.server.ResponseStatusException rse
        && rse.getReason() != null) {
      message = message + " " + rse.getReason();
    }
    return message;
  }

  /** 规则输入（可覆盖）。 */
  Map<String, Object> ruleInput(Object... kv) {
    Map<String, Object> input = new java.util.LinkedHashMap<>();
    input.put("ruleName", "自发货 48 小时");
    input.put("logisticsMode", "SELF_FULFILLED");
    input.put("delayHours", 48);
    input.put("status", "ACTIVE");
    input.put("priority", 100);
    input.put("avoidHolidays", true);
    input.put("holidayShiftDays", 1);
    input.put("timezone", "Asia/Shanghai");
    input.put("note", "");
    for (int i = 0; i + 1 < kv.length; i += 2) {
      input.put(String.valueOf(kv[i]), kv[i + 1]);
    }
    return input;
  }

  /** 模板输入（两个变体）。 */
  Map<String, Object> templateInput(Object... kv) {
    Map<String, Object> input = new java.util.LinkedHashMap<>();
    input.put("templateName", "索评标准话术");
    input.put("locale", "zh-CN");
    input.put("status", "ACTIVE");
    input.put("note", "");
    input.put(
        "variants",
        List.of(
            Map.of(
                "variantCode", "A",
                "subject", "感谢您的购买",
                "body", "您好，非常感谢您的购买，方便给个评价吗？",
                "weight", 60,
                "status", "ACTIVE"),
            Map.of(
                "variantCode", "B",
                "subject", "期待您的反馈",
                "body", "您好，产品还满意吗？欢迎分享使用体验。",
                "weight", 40,
                "status", "ACTIVE")));
    for (int i = 0; i + 1 < kv.length; i += 2) {
      input.put(String.valueOf(kv[i]), kv[i + 1]);
    }
    return input;
  }

  // ================================================================ 规则增删改查

  @Test
  void 规则增删改查() throws Exception {
    var created = postJson("/crm/review-rules", ruleInput("shopId", shopId.toString()));
    String ruleId = created.get("id").asText();
    assertThat(created.get("ruleNo").asText()).startsWith("RULE-");
    assertThat(created.get("shopName").asText()).isEqualTo("主店铺");
    assertThat(created.get("delayHours").asDouble()).isEqualTo(48.0);
    assertThat(created.get("status").asText()).isEqualTo("ACTIVE");

    var page = getJson("/crm/review-rules?size=10");
    assertThat(page.get("totalElements").asLong()).isEqualTo(1);
    assertThat(page.get("items").get(0).get("id").asText()).isEqualTo(ruleId);

    var updated =
        putJson(
            "/crm/review-rules/" + ruleId,
            ruleInput("shopId", shopId.toString(), "delayHours", 72, "status", "PAUSED"));
    assertThat(updated.get("delayHours").asDouble()).isEqualTo(72.0);
    assertThat(updated.get("status").asText()).isEqualTo("PAUSED");

    assertThat(deleteStatus("/crm/review-rules/" + ruleId)).isEqualTo(200);
    assertThat(getJson("/crm/review-rules").get("totalElements").asLong()).isZero();
  }

  @Test
  void 店铺必须在上游真实清单里() throws Exception {
    String message =
        errorOf(
            "POST",
            "/crm/review-rules",
            ruleInput("shopId", UUID.randomUUID().toString()));
    assertThat(message).contains("店铺清单");
  }

  @Test
  void 同一范围同一优先级不允许有两条生效规则() throws Exception {
    postJson("/crm/review-rules", ruleInput("shopId", shopId.toString()));
    String message =
        errorOf("POST", "/crm/review-rules", ruleInput("shopId", shopId.toString()));
    assertThat(message).contains("数据库层拒绝");
  }

  // ================================================================ 话术模板

  @Test
  void 话术模板增删改查() throws Exception {
    var created = postJson("/crm/review-rules/templates", templateInput());
    String templateId = created.get("id").asText();
    assertThat(created.get("templateNo").asText()).startsWith("TPL-");
    assertThat(created.get("variants")).hasSize(2);
    assertThat(created.get("variants").get(0).get("variantCode").asText()).isEqualTo("A");

    var page = getJson("/crm/review-rules/templates");
    assertThat(page.get("totalElements").asLong()).isEqualTo(1);

    var updated =
        putJson(
            "/crm/review-rules/templates/" + templateId,
            templateInput(
                "variants",
                List.of(
                    Map.of(
                        "variantCode", "A",
                        "subject", "改了标题",
                        "body", "改了正文",
                        "weight", 70,
                        "status", "ACTIVE"))));
    assertThat(updated.get("variants")).hasSize(1);
    assertThat(updated.get("variants").get(0).get("subject").asText()).isEqualTo("改了标题");

    assertThat(deleteStatus("/crm/review-rules/templates/" + templateId)).isEqualTo(200);
    assertThat(getJson("/crm/review-rules/templates").get("totalElements").asLong()).isZero();
  }

  @Test
  void 模板里变体码不能重复() throws Exception {
    String message =
        errorOf(
            "POST",
            "/crm/review-rules/templates",
            templateInput(
                "variants",
                List.of(
                    Map.of(
                        "variantCode", "A",
                        "subject", "一",
                        "body", "一",
                        "weight", 50,
                        "status", "ACTIVE"),
                    Map.of(
                        "variantCode", "A",
                        "subject", "二",
                        "body", "二",
                        "weight", 50,
                        "status", "ACTIVE"))));
    assertThat(message).contains("重复");
  }

  @Test
  void 引用不存在的话术模板的规则被拒() throws Exception {
    String message =
        errorOf(
            "POST",
            "/crm/review-rules",
            ruleInput("shopId", shopId.toString(), "templateId", UUID.randomUUID().toString()));
    assertThat(message).contains("话术模板");
  }

  // ================================================================ 节假日

  @Test
  void 节假日增删改查() throws Exception {
    Map<String, Object> holiday = new java.util.LinkedHashMap<>();
    holiday.put("holidayDate", "2026-10-01");
    holiday.put("holidayEndDate", "2026-10-03");
    holiday.put("holidayName", "国庆");
    holiday.put("region", "");
    holiday.put("kind", "HOLIDAY");
    holiday.put("note", "");
    var created = postJson("/crm/review-rules/holidays", holiday);
    String id = created.get("id").asText();
    assertThat(created.get("holidayDate").asText()).isEqualTo("2026-10-01");
    assertThat(created.get("holidayEndDate").asText()).isEqualTo("2026-10-03");

    var page = getJson("/crm/review-rules/holidays?year=2026");
    assertThat(page.get("totalElements").asLong()).isEqualTo(1);

    assertThat(deleteStatus("/crm/review-rules/holidays/" + id)).isEqualTo(200);
    assertThat(getJson("/crm/review-rules/holidays").get("totalElements").asLong()).isZero();
  }

  @Test
  void 节假日的结束日期不能早于开始日期() throws Exception {
    Map<String, Object> holiday = new java.util.LinkedHashMap<>();
    holiday.put("holidayDate", "2026-10-03");
    holiday.put("holidayEndDate", "2026-10-01");
    holiday.put("holidayName", "写反了");
    holiday.put("region", "");
    holiday.put("kind", "HOLIDAY");
    holiday.put("note", "");
    assertThat(errorOf("POST", "/crm/review-rules/holidays", holiday)).contains("早于");
  }

  // ================================================================ 生效策略

  @Test
  void 生效策略按范围具体程度命中() throws Exception {
    // 全局规则（不限店铺、不限物流方式）。
    postJson("/crm/review-rules", ruleInput("logisticsMode", "ANY", "delayHours", 24, "priority", 10));
    // 店铺 + 物流方式规则（更具体 ⇒ 命中它）。
    postJson(
        "/crm/review-rules",
        ruleInput("shopId", shopId.toString(), "delayHours", 48, "priority", 999));

    var policy =
        getJson(
            "/crm/review-rules/effective?shopId="
                + shopId
                + "&logisticsMode=SELF_FULFILLED&at="
                + DELIVERED_AT);
    assertThat(policy.get("ruleId").asText()).isNotEmpty();
    assertThat(policy.get("delayHours").asDouble()).isEqualTo(48.0);
    assertThat(policy.get("matchedScope").asText()).isEqualTo("店铺 + 物流方式");
  }

  @Test
  void 没有命中规则时如实说没有() throws Exception {
    var policy = getJson("/crm/review-rules/effective?logisticsMode=SELF_FULFILLED");
    assertThat(policy.get("ruleId").isNull()).isTrue();
    assertThat(policy.get("matchedScope").asText()).contains("没有命中");
    assertThat(policy.get("note").asText()).contains("没有 ACTIVE 规则");
  }

  @Test
  void ab分桶是确定性的() throws Exception {
    var template = postJson("/crm/review-rules/templates", templateInput());
    String templateId = template.get("id").asText();
    postJson(
        "/crm/review-rules",
        ruleInput(
            "shopId", shopId.toString(),
            "templateId", templateId,
            "abTestEnabled", true));

    var first =
        getJson(
            "/crm/review-rules/effective?shopId="
                + shopId
                + "&logisticsMode=SELF_FULFILLED&seedKey=buyer-1");
    var again =
        getJson(
            "/crm/review-rules/effective?shopId="
                + shopId
                + "&logisticsMode=SELF_FULFILLED&seedKey=buyer-1");
    assertThat(first.get("pickedVariantId").asText())
        .isEqualTo(again.get("pickedVariantId").asText());
    assertThat(first.get("pickedVariantId").asText()).isNotEmpty();
    assertThat(first.get("variants")).hasSize(2);
  }

  @Test
  void 没有种子时只给变体清单不给具体臂() throws Exception {
    var template = postJson("/crm/review-rules/templates", templateInput());
    postJson(
        "/crm/review-rules",
        ruleInput(
            "shopId", shopId.toString(),
            "templateId", template.get("id").asText(),
            "abTestEnabled", true));
    var policy =
        getJson("/crm/review-rules/effective?shopId=" + shopId + "&logisticsMode=SELF_FULFILLED");
    assertThat(policy.get("pickedVariantId").isNull())
        .as("没有分桶种子就说不出「分到哪个臂」，此时必须报空而不是随便挑一个")
        .isTrue();
    assertThat(policy.get("variants")).hasSize(2);
  }

  // ================================================================ 节假日规避（时区正确性）

  @Test
  void 节假日规避按规则时区换算而不是utc日界() throws Exception {
    // 签收时刻 2026-10-01T07:00+08:00 = 2026-09-30T23:00Z。
    // 延后 17 小时 ⇒ 排期落在 2026-10-02T00:00+08:00。
    // 东八区日期是 10-02，**UTC 日期是 10-01** ⇒ 节假日必须按东八区算。
    postJson(
        "/crm/review-rules",
        ruleInput("shopId", shopId.toString(), "delayHours", 17, "timezone", "Asia/Shanghai"));
    // 只在 10-02 放节假日（东八区）。
    Map<String, Object> holiday = new java.util.LinkedHashMap<>();
    holiday.put("holidayDate", "2026-10-02");
    holiday.put("holidayName", "东八区的假日");
    holiday.put("region", "");
    holiday.put("kind", "HOLIDAY");
    holiday.put("note", "");
    postJson("/crm/review-rules/holidays", holiday);

    var policy =
        getJson(
            "/crm/review-rules/effective?shopId="
                + shopId
                + "&logisticsMode=SELF_FULFILLED&at="
                + DELIVERED_AT);
    assertThat(policy.get("holidayAvoided").asBoolean()).isTrue();
    assertThat(policy.get("holidayName").asText()).isEqualTo("东八区的假日");
    assertThat(policy.get("shiftedDays").asInt()).isEqualTo(1);
    assertThat(OffsetDateTime.parse(policy.get("scheduledAt").asText()).toInstant())
        .isEqualTo(OffsetDateTime.parse("2026-10-03T00:00:00+08:00").toInstant());
  }

  @Test
  void 不是节假日的排期日不被顺延() throws Exception {
    postJson("/crm/review-rules", ruleInput("shopId", shopId.toString(), "delayHours", 17));
    Map<String, Object> holiday = new java.util.LinkedHashMap<>();
    holiday.put("holidayDate", "2026-10-01");
    holiday.put("holidayName", "只有这一天");
    holiday.put("region", "");
    holiday.put("kind", "HOLIDAY");
    holiday.put("note", "");
    postJson("/crm/review-rules/holidays", holiday);

    var policy =
        getJson(
            "/crm/review-rules/effective?shopId="
                + shopId
                + "&logisticsMode=SELF_FULFILLED&at="
                + DELIVERED_AT);
    assertThat(policy.get("holidayAvoided").asBoolean()).isFalse();
    assertThat(policy.get("shiftedDays").asInt()).isZero();
  }

  // ================================================================ 应用策略与效果对比

  @Test
  void 应用策略把索评请求落到话术变体上() throws Exception {
    var template = postJson("/crm/review-rules/templates", templateInput());
    String templateId = template.get("id").asText();
    postJson(
        "/crm/review-rules",
        ruleInput(
            "shopId", shopId.toString(),
            "templateId", templateId,
            "abTestEnabled", true,
            "delayHours", 48));

    UUID requestId = seedReviewRequest("buyer-1", "SO-1", DELIVERED_AT);
    var assignment =
        postJson(
            "/crm/review-rules/assignments",
            Map.of("reviewRequestId", requestId.toString(), "logisticsMode", "SELF_FULFILLED"));
    assertThat(assignment.get("reviewRequestId").asText()).isEqualTo(requestId.toString());
    assertThat(assignment.get("variantCode").asText()).isIn("A", "B");
    assertThat(assignment.get("requestNo").asText()).isEqualTo("RR-T-buyer-1");
    assertThat(assignment.get("shopName").asText()).isEqualTo("主店铺");
    // 时刻写回的是**同一个绝对时刻**（硬性要求 6：断言写回的值）。
    assertThat(OffsetDateTime.parse(assignment.get("scheduledAt").asText()).toInstant())
        .isEqualTo(OffsetDateTime.parse(DELIVERED_AT).plusHours(48).toInstant());
    var stored =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                        "select scheduled_at from crm_review_rule_assignment where tenant_id=? and id=?",
                        tenant,
                        UUID.fromString(assignment.get("id").asText()))
                    .get("scheduled_at", OffsetDateTime.class));
    assertThat(stored.toInstant())
        .as("写回库里的时刻必须与 API 返回的是同一个绝对时刻")
        .isEqualTo(OffsetDateTime.parse(DELIVERED_AT).plusHours(48).toInstant());
  }

  @Test
  void 同一条索评请求不能被分配两次() throws Exception {
    postJson("/crm/review-rules", ruleInput("shopId", shopId.toString()));
    UUID requestId = seedReviewRequest("buyer-2", "SO-2", DELIVERED_AT);
    Map<String, Object> body =
        Map.of("reviewRequestId", requestId.toString(), "logisticsMode", "SELF_FULFILLED");
    postJson("/crm/review-rules/assignments", body);
    String message = errorOf("POST", "/crm/review-rules/assignments", body);
    assertThat(message).contains("只能被分配一次");
  }

  @Test
  void 被频次规则拒绝的索评请求不能分配话术() throws Exception {
    postJson("/crm/review-rules", ruleInput("shopId", shopId.toString()));
    UUID requestId = seedReviewRequest("buyer-3", "SO-3", DELIVERED_AT);
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "update crm_review_request set status='REJECTED', reject_code='PER_ORDER_LIMIT',"
                  + " reject_reason='每订单上限挡住了' where tenant_id=? and id=?",
              tenant,
              requestId);
          return null;
        });
    String message =
        errorOf(
            "POST",
            "/crm/review-rules/assignments",
            Map.of("reviewRequestId", requestId.toString(), "logisticsMode", "SELF_FULFILLED"));
    assertThat(message).contains("伪造一次触达");
  }

  @Test
  void 应用策略时必须给出具体物流方式() throws Exception {
    postJson("/crm/review-rules", ruleInput("shopId", shopId.toString()));
    UUID requestId = seedReviewRequest("buyer-4", "SO-4", DELIVERED_AT);
    String message =
        errorOf(
            "POST",
            "/crm/review-rules/assignments",
            Map.of("reviewRequestId", requestId.toString(), "logisticsMode", "ANY"));
    assertThat(message).contains("具体");
  }

  @Test
  void 效果对比按变体拆开真实明细() throws Exception {
    var template = postJson("/crm/review-rules/templates", templateInput());
    String templateId = template.get("id").asText();
    postJson(
        "/crm/review-rules",
        ruleInput(
            "shopId", shopId.toString(),
            "templateId", templateId,
            "abTestEnabled", true,
            "delayHours", 48));

    UUID sent = seedReviewRequest("buyer-sent", "SO-S", DELIVERED_AT);
    markSent(sent);
    seedRecovery(sent, 5);
    UUID silent = seedReviewRequest("buyer-silent", "SO-X", DELIVERED_AT);

    postJson(
        "/crm/review-rules/assignments",
        Map.of("reviewRequestId", sent.toString(), "logisticsMode", "SELF_FULFILLED"));
    postJson(
        "/crm/review-rules/assignments",
        Map.of("reviewRequestId", silent.toString(), "logisticsMode", "SELF_FULFILLED"));

    var comparison = getJson("/crm/review-rules/effect-comparison?days=30");
    assertThat(comparison.get("totalAssignments").asLong()).isEqualTo(2);
    assertThat(comparison.get("variants")).hasSize(2);
    long assigned = 0;
    long sentCount = 0;
    long recovered = 0;
    for (JsonNode variant : comparison.get("variants")) {
      assigned += variant.get("assigned").asLong();
      sentCount += variant.get("sent").asLong();
      recovered += variant.get("recovered").asLong();
      // 分母为 0 的那一臂必须报**空**而不是 0%。
      if (variant.get("sent").asLong() == 0) {
        assertThat(variant.get("recoveryRate").isNull())
            .as("没有已发出的那一臂，回收率必须报空而不是 0%")
            .isTrue();
        assertThat(variant.get("note").asText()).contains("不计算");
      } else {
        assertThat(variant.get("recoveryRate").asDouble()).isEqualTo(1.0);
        assertThat(variant.get("averageRating").asDouble()).isEqualTo(5.0);
      }
    }
    assertThat(assigned).isEqualTo(2);
    assertThat(sentCount).isEqualTo(1);
    assertThat(recovered).isEqualTo(1);
    assertThat(comparison.get("note").asText()).contains("人工登记");
  }

  @Test
  void 效果对比没有样本时报空而不是0() throws Exception {
    var comparison = getJson("/crm/review-rules/effect-comparison?days=30");
    assertThat(comparison.get("totalAssignments").asLong()).isZero();
    assertThat(comparison.get("note").asText()).contains("没有生效策略落地记录");
  }

  @Test
  void 有落地记录的规则不能删() throws Exception {
    var created = postJson("/crm/review-rules", ruleInput("shopId", shopId.toString()));
    String ruleId = created.get("id").asText();
    UUID requestId = seedReviewRequest("buyer-5", "SO-5", DELIVERED_AT);
    postJson(
        "/crm/review-rules/assignments",
        Map.of("reviewRequestId", requestId.toString(), "logisticsMode", "SELF_FULFILLED"));
    assertThat(deleteStatus("/crm/review-rules/" + ruleId)).isEqualTo(409);
  }

  @Test
  void 能力声明把未交付的维度标出来() throws Exception {
    var caps = getJson("/crm/review-rules/capabilities");
    var dimensions = caps.get("dimensions");
    assertThat(dimensions).hasSize(8);
    boolean dispatch = false;
    boolean sync = false;
    for (JsonNode dimension : dimensions) {
      if ("message_dispatch".equals(dimension.get("dimension").asText())) {
        dispatch = true;
        assertThat(dimension.get("available").asBoolean()).isFalse();
        assertThat(dimension.get("ownerModule").asText()).isEqualTo("15.1.1.2");
      }
      if ("review_sync".equals(dimension.get("dimension").asText())) {
        sync = true;
        assertThat(dimension.get("available").asBoolean()).isFalse();
      }
    }
    assertThat(dispatch).isTrue();
    assertThat(sync).isTrue();
  }
}
