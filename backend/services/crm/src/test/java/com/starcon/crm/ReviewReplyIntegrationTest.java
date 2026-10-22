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
import java.util.LinkedHashMap;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 15.2.1.2 评价回复的集成测试。
 *
 * <p>跑的是 AGENTS.md 要求的<b>真实 PostgreSQL 16 + FORCE RLS</b>。租户隔离三断言在
 * {@link ReviewReplyTenancyIntegrationTest} 里，本类专攻业务不变量：
 *
 * <ul>
 *   <li><b>🔴 加粗句「模板必须符合平台措辞规范」</b>：不合规的正文一律 400 且<b>一行都不写</b>
 *       （促销措辞 / 导流联系方式 / 质疑买家 / 差评不致歉）。</li>
 *   <li><b>模板版本化</b>：同键再发一版版本号递增；回复记录追到<b>当时</b>用的那一版，
 *       旧回复不会被改写。</li>
 *   <li><b>多语言</b>：找不到请求语言时用兜底语言，且回复记录记的是<b>实际</b>用掉的语言。</li>
 *   <li><b>两种形态</b>：公开回复与卖家评论追加各能写一条（且要各自配规范）。</li>
 *   <li><b>批量回复同类评价</b>：真的按「同类」筛，逐条落库，<b>跳过的逐条给原因</b>。</li>
 *   <li><b>🔴 平台投递未接入</b>：投递端点 409、<b>不写假回执</b>、回复停在 PENDING，
 *       但这次尝试<b>要留痕</b>。</li>
 *   <li><b>时区不静默偏移（硬性要求 6）</b>：用 {@code +08:00} 的偏移量写入，
 *       再<b>断言写回的值</b>等于同一个绝对时刻。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReviewReplyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID userId = UUID.randomUUID();
  static final UUID shopId = UUID.randomUUID();
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 固定时刻（东八区偏移量，用来验时区不静默偏移）。 */
  static final String EFFECTIVE_FROM = "2026-06-01T09:30:00+08:00";

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
    List<String> permissions = List.of("crm.review_reply.read", "crm.review_reply.update");
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
          "truncate table crm_review_reply_delivery, crm_review_reply, crm_review_reply_batch,"
              + " crm_review_reply_template_variant, crm_review_reply_template,"
              + " crm_review_reply_policy, crm_product_review restart identity cascade");
    }
  }

  /** 往评价表里塞一条评价（15.2.1.1 的事实，本模块只读引用）。 */
  UUID insertReview(int rating, String platformCode, String reviewNo) {
    UUID id = UUID.randomUUID();
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into crm_product_review"
                  + "(id,tenant_id,review_no,shop_id,shop_name,platform_code,source,rating,"
                  + " product_ref,product_title,variant_ref,variant_label,image_urls,"
                  + " has_image,has_video,replied,platform_reviewed_at,registered_at,"
                  + " created_at,updated_at)"
                  + " values (?,?,?,?,?,?,'MANUAL_ENTRY',?,'SPU-1','商品','SKU-1','变体',"
                  + " '[]'::jsonb,false,false,false,?::timestamptz,?::timestamptz,"
                  + " ?::timestamptz,?::timestamptz)",
              id,
              tenant,
              reviewNo,
              shopId,
              "主店铺",
              platformCode,
              rating,
              OffsetDateTime.now(),
              OffsetDateTime.now(),
              OffsetDateTime.now(),
              OffsetDateTime.now());
          return null;
        });
    return id;
  }

  /** 配置一条平台措辞规范（默认档：不允许导流 / 促销 / 外链，差评必致歉）。 */
  JsonNode configurePolicy(String platformCode, String replyType) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("platformCode", platformCode);
    body.put("replyType", replyType);
    body.put("minLength", 10);
    body.put("maxLength", 500);
    body.put("allowContactInfo", false);
    body.put("allowPromotion", false);
    body.put("allowExternalLink", false);
    body.put("maxExclamation", 1);
    body.put("maxQuestionMarks", 2);
    body.put("requireApology", true);
    body.put("bannedTerms", List.of());
    body.put("effectiveFrom", EFFECTIVE_FROM);
    return putJson("/crm/review-replies/policies", body);
  }

  /** 建一个模板（草稿）并发布，返回模板 id。 */
  UUID publishTemplate(
      String templateKey, String platformCode, String replyType, String ratingScope, String body)
      throws Exception {
    Map<String, Object> variant = new LinkedHashMap<>();
    variant.put("locale", "en-US");
    variant.put("body", body);
    variant.put("isDefault", true);
    Map<String, Object> template = new LinkedHashMap<>();
    template.put("templateKey", templateKey);
    template.put("name", templateKey);
    template.put("platformCode", platformCode);
    template.put("replyType", replyType);
    template.put("ratingScope", ratingScope);
    template.put("effectiveFrom", EFFECTIVE_FROM);
    template.put("variants", List.of(variant));
    var created = postJson("/crm/review-replies/templates", template);
    String id = created.get("template").get("id").asText();
    postJson("/crm/review-replies/templates/" + id + "/publish", Map.of());
    return UUID.fromString(id);
  }

  /** 写一条回复的请求体。 */
  Map<String, Object> replyBody(UUID reviewId, String replyType, String locale, String body) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("reviewId", reviewId.toString());
    out.put("replyType", replyType);
    out.put("locale", locale);
    out.put("body", body);
    return out;
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
    var result = http.perform(get(path).header("Authorization", "session")).andReturn();
    if (result.getResponse().getStatus() != 200) {
      throw new AssertionError(
          "HTTP "
              + result.getResponse().getStatus()
              + " : "
              + result.getResponse().getContentAsString());
    }
    return json.readTree(result.getResponse().getContentAsString());
  }

  /** 失败时把**原因**取出来（ResponseStatusException 走 sendError，响应体是空的）。 */
  String reasonOf(MockHttpServletRequestBuilder request) throws Exception {
    var resolved = http.perform(request).andReturn().getResolvedException();
    assertThat(resolved).as("应当抛出带原因的异常").isNotNull();
    String message = resolved.getMessage() == null ? "" : resolved.getMessage();
    if (resolved instanceof org.springframework.web.server.ResponseStatusException rse
        && rse.getReason() != null) {
      message = message + " " + rse.getReason();
    }
    return message;
  }

  String reasonOfPost(String path, Object body) throws Exception {
    return reasonOf(
        post(path)
            .header("Authorization", "session")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(body)));
  }

  String reasonOfPut(String path, Object body) throws Exception {
    return reasonOf(
        put(path)
            .header("Authorization", "session")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(body)));
  }

  // ================================================================ 断言助手

  long countReplies() {
    return tenants.run(
        tenant, () -> sql.fetchOne("select count(*) from crm_review_reply").get(0, Long.class));
  }

  long countDeliveries() {
    return tenants.run(
        tenant,
        () -> sql.fetchOne("select count(*) from crm_review_reply_delivery").get(0, Long.class));
  }

  // ================================================================ 能力声明

  @Test
  void 能力声明逐条说清有没有_未交付的三维必须点名归属模块() throws Exception {
    var node = getJson("/crm/review-replies/capabilities");
    var dimensions = node.get("dimensions");
    assertThat(dimensions).as("能力声明必须逐维列出").isNotNull();
    assertThat(dimensions.size()).isGreaterThanOrEqualTo(8);

    var byKey = new LinkedHashMap<String, JsonNode>();
    for (JsonNode d : dimensions) byKey.put(d.get("dimension").asText(), d);

    // 加粗句对应的两维必须可用，且必须点名 DB 层的落点。
    assertThat(byKey.get("reply_template").get("available").asBoolean()).isTrue();
    assertThat(byKey.get("wording_policy").get("available").asBoolean()).isTrue();
    assertThat(byKey.get("wording_enforcement").get("available").asBoolean()).isTrue();
    assertThat(byKey.get("batch_reply").get("available").asBoolean()).isTrue();
    assertThat(byKey.get("reply_template").get("source").asText()).contains("V298");

    // 未交付的三维必须 available=false **且**带 ownerModule 与 impact。
    for (String key : List.of("platform_delivery", "negative_review_handling", "buyer_care")) {
      var dimension = byKey.get(key);
      assertThat(dimension).as(key + " 必须出现在能力声明里").isNotNull();
      assertThat(dimension.get("available").asBoolean()).as(key + " 必须标成不可用").isFalse();
      assertThat(dimension.get("ownerModule").asText()).as(key + " 必须点名归属模块").isNotBlank();
      assertThat(dimension.get("impact").asText()).as(key + " 必须说清缺了会怎样").isNotBlank();
    }
    // 🔴 平台投递这一维必须说清「不会返回假回执」。
    assertThat(byKey.get("platform_delivery").get("impact").asText()).contains("回执");
  }

  // ================================================================ 规范闸门

  @Test
  void 未配置措辞规范时写不出回复_且一行都不写() throws Exception {
    UUID review = insertReview(5, "AMAZON", "PR-NO-POLICY");
    var reason =
        reasonOfPost(
            "/crm/review-replies",
            replyBody(review, "PUBLIC_REPLY", "en-US", "Thanks for sharing your feedback with us."));
    assertThat(reason).as("未配置规范必须明确拒绝").contains("措辞规范");
    assertThat(countReplies()).as("被拒的回复不能留下一行").isZero();
  }

  @Test
  void 不合规的正文被拒_促销导流质疑买家一律不写库() throws Exception {
    configurePolicy("AMAZON", "PUBLIC_REPLY");
    UUID review = insertReview(5, "AMAZON", "PR-BAD");
    UUID negative = insertReview(1, "AMAZON", "PR-BAD-NEG");

    // 促销措辞
    assertThat(
            reasonOfPost(
                "/crm/review-replies",
                replyBody(
                    review,
                    "PUBLIC_REPLY",
                    "en-US",
                    "Thanks for the review, here is a discount coupon for your next order.")))
        .contains("促销");
    // 导流联系方式
    assertThat(
            reasonOfPost(
                "/crm/review-replies",
                replyBody(
                    review,
                    "PUBLIC_REPLY",
                    "en-US",
                    "Thanks for the review, you can reach us at support@example.com anytime.")))
        .contains("邮箱");
    // 质疑买家
    assertThat(
            reasonOfPost(
                "/crm/review-replies",
                replyBody(
                    review,
                    "PUBLIC_REPLY",
                    "en-US",
                    "Thanks for the review, but you are wrong about the delivery delay.")))
        .contains("质疑买家");
    // 差评不致歉
    assertThat(
            reasonOfPost(
                "/crm/review-replies",
                replyBody(
                    negative,
                    "PUBLIC_REPLY",
                    "en-US",
                    "Thank you for taking the time to write this review to us.")))
        .contains("致歉");

    assertThat(countReplies()).as("四条被拒的回复一条都不能落库").isZero();
  }

  @Test
  void 差评也能回复_带致歉就放行() throws Exception {
    configurePolicy("AMAZON", "PUBLIC_REPLY");
    UUID negative = insertReview(1, "AMAZON", "PR-NEG");
    var created =
        postJson(
            "/crm/review-replies",
            replyBody(
                negative,
                "PUBLIC_REPLY",
                "en-US",
                "We are sorry the parcel did not arrive as expected, we have asked the carrier."));
    assertThat(created.get("reviewScope").asText()).isEqualTo("NEGATIVE");
    assertThat(created.get("status").asText()).isEqualTo("PENDING");
    assertThat(countReplies()).isEqualTo(1);
  }

  // ================================================================ 模板版本化

  @Test
  void 模板版本化_同键再发一版版本号递增_回复追到当时那一版() throws Exception {
    configurePolicy("AMAZON", "PUBLIC_REPLY");
    UUID reviewOne = insertReview(5, "AMAZON", "PR-V1");
    UUID reviewTwo = insertReview(5, "AMAZON", "PR-V2");

    UUID v1 =
        publishTemplate(
            "THANKS",
            "AMAZON",
            "PUBLIC_REPLY",
            "ALL",
            "Thanks for taking the time to share this feedback with our team.");
    UUID v2 =
        publishTemplate(
            "THANKS",
            "AMAZON",
            "PUBLIC_REPLY",
            "ALL",
            "Thanks for taking the time to share this feedback, it helps us improve daily.");

    var templateV1 = getJson("/crm/review-replies/templates/" + v1).get("template");
    var templateV2 = getJson("/crm/review-replies/templates/" + v2).get("template");
    assertThat(templateV1.get("version").asInt()).isEqualTo(1);
    assertThat(templateV2.get("version").asInt()).isEqualTo(2);
    assertThat(templateV1.get("templateKey").asText()).isEqualTo(templateV2.get("templateKey").asText());

    var replyOne =
        postJson(
            "/crm/review-replies",
            Map.of(
                "reviewId", reviewOne.toString(),
                "replyType", "PUBLIC_REPLY",
                "locale", "en-US",
                "templateId", v1.toString()));
    var replyTwo =
        postJson(
            "/crm/review-replies",
            Map.of(
                "reviewId", reviewTwo.toString(),
                "replyType", "PUBLIC_REPLY",
                "locale", "en-US",
                "templateId", v2.toString()));

    // 每条回复追到**自己当时**用的那一版。
    assertThat(replyOne.get("templateVersion").asInt()).isEqualTo(1);
    assertThat(replyTwo.get("templateVersion").asInt()).isEqualTo(2);
    assertThat(replyOne.get("templateKey").asText()).isEqualTo("THANKS");
    // 旧回复**不会**被后来的版本改写。
    assertThat(replyOne.get("body").asText()).contains("share this feedback with our team");
    assertThat(replyTwo.get("body").asText()).contains("it helps us improve daily");
  }

  @Test
  void 已生效的模板内容冻结_改不动() throws Exception {
    configurePolicy("AMAZON", "PUBLIC_REPLY");
    UUID template =
        publishTemplate(
            "FROZEN",
            "AMAZON",
            "PUBLIC_REPLY",
            "ALL",
            "Thanks for taking the time to share this feedback with our team.");
    Map<String, Object> variant = new LinkedHashMap<>();
    variant.put("locale", "en-US");
    variant.put("body", "Totally different body that would rewrite the history of this version.");
    variant.put("isDefault", true);
    Map<String, Object> update = new LinkedHashMap<>();
    update.put("name", "改名");
    update.put("variants", List.of(variant));

    var reason = reasonOfPut("/crm/review-replies/templates/" + template, update);
    assertThat(reason).as("已生效的模板内容必须冻结").isNotBlank();
    var after = getJson("/crm/review-replies/templates/" + template);
    assertThat(after.get("template").get("name").asText()).isEqualTo("FROZEN");
    assertThat(after.get("variants").get(0).get("body").asText())
        .isEqualTo("Thanks for taking the time to share this feedback with our team.");
  }

  @Test
  void 多语言_没有该语言就用兜底语言_回复记录记实际语言() throws Exception {
    configurePolicy("AMAZON", "PUBLIC_REPLY");
    UUID review = insertReview(5, "AMAZON", "PR-I18N");
    UUID other = insertReview(5, "AMAZON", "PR-I18N-2");

    Map<String, Object> en = new LinkedHashMap<>();
    en.put("locale", "en-US");
    en.put("body", "Thanks for taking the time to share this feedback with our team.");
    en.put("isDefault", true);
    Map<String, Object> zh = new LinkedHashMap<>();
    zh.put("locale", "zh-CN");
    zh.put("body", "感谢您花时间写下这条评价，我们会认真对待每一条反馈。");
    zh.put("isDefault", false);

    Map<String, Object> template = new LinkedHashMap<>();
    template.put("templateKey", "I18N");
    template.put("name", "多语言模板");
    template.put("platformCode", "AMAZON");
    template.put("replyType", "PUBLIC_REPLY");
    template.put("ratingScope", "ALL");
    template.put("effectiveFrom", EFFECTIVE_FROM);
    template.put("variants", List.of(en, zh));
    var created = postJson("/crm/review-replies/templates", template);
    String templateId = created.get("template").get("id").asText();
    postJson("/crm/review-replies/templates/" + templateId + "/publish", Map.of());

    var zhReply =
        postJson(
            "/crm/review-replies",
            Map.of(
                "reviewId", review.toString(),
                "replyType", "PUBLIC_REPLY",
                "locale", "zh-CN",
                "templateId", templateId));
    assertThat(zhReply.get("locale").asText()).isEqualTo("zh-CN");
    assertThat(zhReply.get("body").asText()).contains("感谢您花时间");

    // 请求一个没有的语言 ⇒ 用兜底语言，且记录里写的是**实际**用掉的语言。
    var fallback =
        postJson(
            "/crm/review-replies",
            Map.of(
                "reviewId", other.toString(),
                "replyType", "PUBLIC_REPLY",
                "locale", "fr-FR",
                "templateId", templateId));
    assertThat(fallback.get("locale").asText()).isEqualTo("en-US");
    assertThat(fallback.get("templateLocale").asText()).isEqualTo("en-US");
    assertThat(fallback.get("body").asText()).contains("Thanks for taking the time");
  }

  @Test
  void 两种形态都能写_公开回复与卖家评论追加各一条() throws Exception {
    configurePolicy("AMAZON", "PUBLIC_REPLY");
    configurePolicy("AMAZON", "SELLER_COMMENT_APPEND");
    UUID review = insertReview(5, "AMAZON", "PR-TWO-FORMS");
    UUID other = insertReview(5, "AMAZON", "PR-TWO-FORMS-2");

    var publicReply =
        postJson(
            "/crm/review-replies",
            replyBody(
                review, "PUBLIC_REPLY", "en-US", "Thanks for taking the time to share your feedback."));
    var sellerComment =
        postJson(
            "/crm/review-replies",
            replyBody(
                other,
                "SELLER_COMMENT_APPEND",
                "en-US",
                "Thanks for taking the time to share your feedback."));
    assertThat(publicReply.get("replyType").asText()).isEqualTo("PUBLIC_REPLY");
    assertThat(sellerComment.get("replyType").asText()).isEqualTo("SELLER_COMMENT_APPEND");
    assertThat(countReplies()).isEqualTo(2);
  }

  @Test
  void 两种形态各自的规范互不通用_没配的那个形态写不出来() throws Exception {
    configurePolicy("AMAZON", "PUBLIC_REPLY");
    UUID review = insertReview(5, "AMAZON", "PR-ONE-FORM");
    var reason =
        reasonOfPost(
            "/crm/review-replies",
            replyBody(
                review,
                "SELLER_COMMENT_APPEND",
                "en-US",
                "Thanks for taking the time to share your feedback."));
    assertThat(reason).as("没配规范的那个形态必须被拒").contains("措辞规范");
    assertThat(countReplies()).isZero();
  }

  // ================================================================ 批量回复

  @Test
  void 批量回复同类评价_只回同平台同档位的_跳过的逐条给原因() throws Exception {
    configurePolicy("AMAZON", "PUBLIC_REPLY");
    insertReview(5, "AMAZON", "PR-P1");
    insertReview(4, "AMAZON", "PR-P2");
    insertReview(1, "AMAZON", "PR-N1");
    insertReview(5, "SHOPIFY", "PR-S1");

    // 一个没有致歉的模板：用 ALL 档批量回复时，差评那条会被逐条跳过并给出原因。
    UUID template =
        publishTemplate(
            "GENERIC",
            "AMAZON",
            "PUBLIC_REPLY",
            "ALL",
            "Thank you for taking the time to write this review to our support team.");

    Map<String, Object> batch = new LinkedHashMap<>();
    batch.put("platformCode", "AMAZON");
    batch.put("replyType", "PUBLIC_REPLY");
    batch.put("locale", "en-US");
    batch.put("ratingScope", "ALL");
    batch.put("templateId", template.toString());
    batch.put("limit", 50);
    var result = postJson("/crm/review-replies/batches", batch);

    // 只回 AMAZON 的（POSITIVE 两条 + NEGATIVE 一条 = 3 条目标；SHOPIFY 不在其中）。
    assertThat(result.get("batch").get("targetCount").asInt()).isEqualTo(3);
    // 差评那条因缺致歉被跳过。
    assertThat(result.get("batch").get("createdCount").asInt()).isEqualTo(2);
    assertThat(result.get("batch").get("skippedCount").asInt()).isEqualTo(1);
    assertThat(result.get("skipped").get(0).get("reason").asText()).contains("致歉");
    assertThat(result.get("skipped").get(0).get("reviewNo").asText()).isEqualTo("PR-N1");
    // 每条回复都挂在批次上。
    assertThat(result.get("replies").get(0).get("batchNo").asText()).isNotBlank();
    assertThat(countReplies()).isEqualTo(2);
  }

  @Test
  void 批量回复_已经回过的评价不会被再回一次() throws Exception {
    configurePolicy("AMAZON", "PUBLIC_REPLY");
    insertReview(5, "AMAZON", "PR-DUP");
    UUID template =
        publishTemplate(
            "ONCE",
            "AMAZON",
            "PUBLIC_REPLY",
            "ALL",
            "Thanks for taking the time to share this feedback with our team.");
    Map<String, Object> batch = new LinkedHashMap<>();
    batch.put("platformCode", "AMAZON");
    batch.put("replyType", "PUBLIC_REPLY");
    batch.put("locale", "en-US");
    batch.put("ratingScope", "ALL");
    batch.put("templateId", template.toString());

    var first = postJson("/crm/review-replies/batches", batch);
    assertThat(first.get("batch").get("createdCount").asInt()).isEqualTo(1);
    var second = postJson("/crm/review-replies/batches", batch);
    assertThat(second.get("batch").get("targetCount").asInt())
        .as("已经回过的评价不该再进目标集")
        .isZero();
    assertThat(countReplies()).isEqualTo(1);
  }

  // ================================================================ 平台投递（未接入）

  @Test
  void 平台投递未接入_明确409且不写假回执_但这次尝试要留痕() throws Exception {
    configurePolicy("AMAZON", "PUBLIC_REPLY");
    UUID review = insertReview(5, "AMAZON", "PR-DELIVER");
    var created =
        postJson(
            "/crm/review-replies",
            replyBody(
                review, "PUBLIC_REPLY", "en-US", "Thanks for taking the time to share your feedback."));

    var reason = reasonOfPost("/crm/review-replies/" + created.get("id").asText() + "/deliver", Map.of());
    assertThat(reason).as("投递未接入必须明确答").contains("未交付");

    // 🔴 绝不写假回执：状态停在 PENDING，回执为空。
    var after = getJson("/crm/review-replies/" + created.get("id").asText());
    assertThat(after.get("reply").get("status").asText()).isEqualTo("PENDING");
    assertThat(after.get("reply").get("platformReceipt").isNull()).isTrue();
    assertThat(after.get("reply").get("platformReplyId").isNull()).isTrue();
    assertThat(after.get("reply").get("deliveredAt").isNull()).isTrue();
    // 但这次尝试**留了痕**：查得到「试过、没发出去、为什么」。
    assertThat(after.get("deliveries").size()).isEqualTo(1);
    assertThat(after.get("deliveries").get(0).get("outcome").asText()).isEqualTo("NOT_INTEGRATED");
    assertThat(after.get("deliveries").get(0).get("note").asText()).isNotBlank();
    assertThat(countDeliveries()).isEqualTo(1);
  }

  @Test
  void 重复回复同一条评价被拒_重复回复买家是平台违规的常见来源() throws Exception {
    configurePolicy("AMAZON", "PUBLIC_REPLY");
    UUID review = insertReview(5, "AMAZON", "PR-ONCE");
    postJson(
        "/crm/review-replies",
        replyBody(review, "PUBLIC_REPLY", "en-US", "Thanks for taking the time to share your feedback."));
    var reason =
        reasonOfPost(
            "/crm/review-replies",
            replyBody(review, "PUBLIC_REPLY", "en-US", "Thanks for taking the time to write to us."));
    assertThat(reason).as("重复回复必须被拒").contains("重复回复");
    assertThat(countReplies()).isEqualTo(1);
  }

  // ================================================================ 时区

  @Test
  void 时区不静默偏移_规范的生效起点原样读回() throws Exception {
    configurePolicy("AMAZON", "PUBLIC_REPLY");
    var page = getJson("/crm/review-replies/policies");
    var row = page.get("items").get(0);
    OffsetDateTime written = OffsetDateTime.parse(row.get("effectiveFrom").asText());
    assertThat(written.toInstant())
        .as("写入的 +08:00 时刻必须还是同一个绝对时刻（不能偏 8 小时）")
        .isEqualTo(OffsetDateTime.parse(EFFECTIVE_FROM).toInstant());
  }

  @Test
  void 规范版本化_改规范要发新版本_旧版本留在历史里() throws Exception {
    configurePolicy("AMAZON", "PUBLIC_REPLY");
    Map<String, Object> tighter = new LinkedHashMap<>();
    tighter.put("platformCode", "AMAZON");
    tighter.put("replyType", "PUBLIC_REPLY");
    tighter.put("minLength", 10);
    tighter.put("maxLength", 100);
    tighter.put("allowContactInfo", false);
    tighter.put("allowPromotion", false);
    tighter.put("allowExternalLink", false);
    tighter.put("maxExclamation", 0);
    tighter.put("maxQuestionMarks", 1);
    tighter.put("requireApology", true);
    tighter.put("bannedTerms", List.of("shipment"));
    tighter.put("effectiveFrom", "2026-07-01T00:00:00+08:00");
    var v2 = putJson("/crm/review-replies/policies", tighter);
    assertThat(v2.get("version").asInt()).isEqualTo(2);

    var page = getJson("/crm/review-replies/policies");
    assertThat(page.get("totalElements").asLong()).as("两版规范都要留在历史里").isEqualTo(2);
    // 旧版本的生效区间被新版本收口，不允许重叠。
    var versions = page.get("items");
    assertThat(versions.get(0).get("version").asInt()).isEqualTo(2);
    assertThat(versions.get(1).get("effectiveTo").isNull()).isFalse();
  }
}
