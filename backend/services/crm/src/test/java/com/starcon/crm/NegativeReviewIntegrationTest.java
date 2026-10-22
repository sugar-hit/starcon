package com.starcon.crm;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

/**
 * 15.2.1.3 差评处理的集成测试。
 *
 * <p>跑的是 AGENTS.md 要求的<b>真实 PostgreSQL 16 + FORCE RLS</b>。租户隔离三断言在
 * {@link NegativeReviewTenancyIntegrationTest} 里，本类专攻业务不变量：
 *
 * <ul>
 *   <li><b>🔴 加粗句「解决差评的正确路径是先解决买家问题再请求修改」</b>：
 *       没走完「联系买家」就请求修改 / 建申诉任务 ⇒ 服务端拒绝 + 明确原因 + 零行写入。</li>
 *   <li><b>🔴 平台侧未接入 ⇒ 零行写入</b>：走平台侧的请求（站内信 / 平台申诉）
 *       明确答「未接入」（409），<b>一条假记录都不写</b>，
 *       绝不返回假的「申诉已提交」回执。</li>
 *   <li><b>判定是否可申诉真的可配</b>：平台支不支持、原因能不能申诉，都在规则里；
 *       判定<b>结果</b>与判定<b>依据</b>一起落库。</li>
 *   <li><b>申诉配额</b>：按（租户 × 平台 × 周期）计数，建任务即占用，撤回即归还。</li>
 *   <li><b>11 售后未交付 ⇒ 只落转交意图</b>：原因必填，<b>不落任何下游单据 id</b>。</li>
 *   <li><b>解决率由服务端算</b>：分子分母口径随响应一起给出。</li>
 *   <li><b>时区不静默偏移（硬性要求 5）</b>：用 {@code +08:00} 的偏移量写入，
 *       再<b>断言写回的值</b>等于同一个绝对时刻。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NegativeReviewIntegrationTest {

  static final UUID tenant = UUID.randomUUID();
  static final UUID userId = UUID.randomUUID();
  static final UUID shopId = UUID.randomUUID();

  /** 固定时刻（东八区偏移量，用来验时区不静默偏移）。 */
  static final String REVIEWED_AT = "2026-06-01T09:30:00+08:00";

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", NegativeReviewTestSupport.PG::getJdbcUrl);
    r.add("DB_USER", () -> "crm_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", NegativeReviewTestSupport.PG::getUsername);
    r.add("DB_MIGRATION_PASSWORD", NegativeReviewTestSupport.PG::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + NegativeReviewTestSupport.identity.getAddress().getPort());
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:" + NegativeReviewTestSupport.channel.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://127.0.0.1:" + NegativeReviewTestSupport.trade.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  @BeforeEach
  void reset() {
    NegativeReviewTestSupport.ACTORS.clear();
    NegativeReviewTestSupport.ACTORS.put(
        "session",
        NegativeReviewTestSupport.actor(
            userId,
            tenant,
            "Test",
            // 差评的来源是 15.2.1.1 的评价端点 ⇒ 登记一条真评价需要它的权限。
            // （本模块自己的权限仍只有 crm.negative_review.*）
            java.util.List.of(
                "crm.negative_review.read",
                "crm.negative_review.update",
                "crm.product_review.read",
                "crm.product_review.update")));
    NegativeReviewTestSupport.SHOPS.clear();
    NegativeReviewTestSupport.SHOPS.add(
        new SignoffDeliveredOrderPort.ShopView(
            shopId, "SHOP-1", "主店铺", "AMAZON", "亚马逊", "SELF_FULFILLED", "ACTIVE"));
    NegativeReviewTestSupport.asMigrationUser(
        "truncate table crm_negative_review_action, crm_negative_review_appeal,"
            + " crm_negative_review_case, crm_negative_review_rule,"
            + " crm_product_review restart identity cascade");
  }

  // ================================================================ HTTP 助手

  JsonNode postJson(String path, Object body) throws Exception {
    var result =
        http.perform(
                post(path)
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(NegativeReviewTestSupport.JSON.writeValueAsBytes(body)))
            .andReturn();
    if (result.getResponse().getStatus() != 200) {
      throw new AssertionError(
          "HTTP "
              + result.getResponse().getStatus()
              + " : "
              + result.getResponse().getContentAsString());
    }
    return NegativeReviewTestSupport.JSON.readTree(result.getResponse().getContentAsString());
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
    return NegativeReviewTestSupport.JSON.readTree(result.getResponse().getContentAsString());
  }

  /** 失败时把**原因**取出来（ResponseStatusException 走 sendError，响应体是空的）。 */
  String errorOf(String method, String path, Object body) throws Exception {
    var request =
        method.equals("PUT")
            ? put(path)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(NegativeReviewTestSupport.JSON.writeValueAsBytes(body))
            : post(path)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(NegativeReviewTestSupport.JSON.writeValueAsBytes(body));
    var resolved = http.perform(request).andReturn().getResolvedException();
    assertThat(resolved).as("应当抛出带原因的异常").isNotNull();
    return reasonOf(resolved);
  }

  /** 把异常里的可读原因取出来（ResponseStatusException 的 reason）。 */
  static String reasonOf(Throwable resolved) {
    String message = resolved.getMessage() == null ? "" : resolved.getMessage();
    if (resolved instanceof org.springframework.web.server.ResponseStatusException rse
        && rse.getReason() != null) {
      message = message + " " + rse.getReason();
    }
    return message;
  }

  // ================================================================ 数据构造

  /** 登记一条评价（走 15.2.1.1 的真实端点，差评的来源必须是真评价）。 */
  JsonNode registerReview(int rating) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("shopId", shopId.toString());
    body.put("rating", rating);
    body.put("content", "买家评价正文");
    body.put("productRef", "SPU-1");
    body.put("variantRef", "SKU-1");
    body.put("platformReviewedAt", REVIEWED_AT);
    body.put("salesOrderNo", "SO-1");
    return postJson("/crm/reviews", body);
  }

  /** 建一条平台策略规则。 */
  JsonNode platformPolicy(int threshold, boolean appealSupported, int quota) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ruleScope", "PLATFORM");
    body.put("platformCode", "AMAZON");
    body.put("label", "亚马逊差评处理策略");
    body.put("negativeRatingThreshold", threshold);
    body.put("appealSupported", appealSupported);
    body.put("contactBuyerRequired", true);
    body.put("appealQuotaLimit", quota);
    body.put("quotaPeriod", "MONTHLY");
    body.put("appealWindowDays", 30);
    return postJson("/crm/negative-reviews/rules", body);
  }

  /** 建一条原因规则。 */
  JsonNode causeRule(String causeCode, boolean appealEligible) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ruleScope", "CAUSE");
    body.put("platformCode", "AMAZON");
    body.put("causeCode", causeCode);
    body.put("label", "物流问题");
    body.put("appealEligible", appealEligible);
    body.put("appealBasisTemplate", "物流事实可查且在平台申诉时限内");
    body.put("evidenceRequired", false);
    return postJson("/crm/negative-reviews/rules", body);
  }

  /** 建一张处理单。 */
  JsonNode createCase(String reviewId) throws Exception {
    return postJson("/crm/negative-reviews", Map.of("reviewId", reviewId));
  }

  /** 建一张处理单（uuid 形态）。 */
  JsonNode createCase(UUID reviewId) throws Exception {
    return createCase(reviewId.toString());
  }

  /** 登记一个处理动作。 */
  JsonNode recordAction(String caseId, String type, String transport, String outcome, String summary)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("actionType", type);
    body.put("transport", transport);
    body.put("outcome", outcome);
    if (summary != null) body.put("summary", summary);
    return postJson("/crm/negative-reviews/" + caseId + "/actions", body);
  }

  long countCases() {
    return tenants.run(tenant, () -> sql.fetchOne("select count(*) from crm_negative_review_case").get(0, Long.class));
  }

  long countActions() {
    return tenants.run(tenant, () -> sql.fetchOne("select count(*) from crm_negative_review_action").get(0, Long.class));
  }

  long countAppeals() {
    return tenants.run(tenant, () -> sql.fetchOne("select count(*) from crm_negative_review_appeal").get(0, Long.class));
  }

  long countRules() {
    return tenants.run(tenant, () -> sql.fetchOne("select count(*) from crm_negative_review_rule").get(0, Long.class));
  }

  // ================================================================ 能力声明

  @Test
  void 能力声明逐条说清有没有_未交付的三维必须点名归属模块() throws Exception {
    var node = getJson("/crm/negative-reviews/capabilities");
    var dimensions = node.get("dimensions");
    assertThat(dimensions).as("能力声明必须逐维列出").isNotNull();
    assertThat(dimensions.size()).isGreaterThanOrEqualTo(6);

    var byKey = new LinkedHashMap<String, JsonNode>();
    for (JsonNode d : dimensions) byKey.put(d.get("dimension").asText(), d);

    for (String key : List.of("negative_review_case", "cause_classification", "appeal_task",
        "case_action_log", "resolution_rate")) {
      var dimension = byKey.get(key);
      assertThat(dimension).as(key + " 必须出现在能力声明里").isNotNull();
      assertThat(dimension.get("available").asBoolean()).as(key + " 必须标成可用").isTrue();
      assertThat(dimension.get("source").asText()).as(key + " 必须点名 DB 层的落点").contains("V302");
    }
    // 未交付的三维必须 available=false **且**带 ownerModule 与 impact。
    for (String key : List.of("platform_appeal_submit", "after_sales_handoff", "platform_review_sync")) {
      var dimension = byKey.get(key);
      assertThat(dimension).as(key + " 必须出现在能力声明里").isNotNull();
      assertThat(dimension.get("available").asBoolean()).as(key + " 必须标成不可用").isFalse();
      assertThat(dimension.get("ownerModule").asText()).as(key + " 必须点名归属模块").isNotBlank();
      assertThat(dimension.get("impact").asText()).as(key + " 必须说清缺了会怎样").isNotBlank();
    }
  }

  // ================================================================ 规则与建单

  @Test
  void 没有平台策略就建不了处理单_没有规则就没有差评口径() throws Exception {
    var review = registerReview(1);
    var reason = errorOf("POST", "/crm/negative-reviews", Map.of("reviewId", review.get("id").asText()));
    assertThat(reason).contains("还没有配置差评处理策略");
    assertThat(countCases()).as("被拒的建单不得留下任何行").isZero();
  }

  @Test
  void 星级高于阈值的评价不是差评_不能建处理单() throws Exception {
    platformPolicy(2, true, 5);
    var review = registerReview(3);
    var reason = errorOf("POST", "/crm/negative-reviews", Map.of("reviewId", review.get("id").asText()));
    assertThat(reason).contains("不是差评");
    assertThat(countCases()).isZero();
  }

  @Test
  void 阈值可配_阈值改成1时两星评价不再是差评() throws Exception {
    platformPolicy(2, true, 5);
    var twoStar = registerReview(2);
    // 阈值 2 ⇒ 两星可建单
    assertThat(createCase(UUID.fromString(twoStar.get("id").asText())).get("caseNo").asText())
        .startsWith("NVC-");
    // 阈值改成 1 ⇒ 之后的两星评价不再是差评（**原地改**已有策略，不是再建一条）
    var policy = getJson("/crm/negative-reviews/rules?ruleScope=PLATFORM").get("items").get(0);
    var updated =
        http.perform(
                put("/crm/negative-reviews/rules/" + policy.get("id").asText())
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        NegativeReviewTestSupport.JSON.writeValueAsBytes(
                            Map.of("negativeRatingThreshold", 1))))
            .andReturn();
    assertThat(updated.getResponse().getStatus()).isEqualTo(200);
    var laterTwoStar = registerReview(2);
    var reason =
        errorOf(
            "POST",
            "/crm/negative-reviews",
            Map.of("reviewId", laterTwoStar.get("id").asText()));
    assertThat(reason).contains("不是差评");
    assertThat(countCases()).isEqualTo(1);
  }

  @Test
  void 同一条评价只允许一个处理单_否则解决率的分母会翻倍() throws Exception {
    platformPolicy(2, true, 5);
    var review = registerReview(1);
    createCase(UUID.fromString(review.get("id").asText()));
    var reason =
        errorOf("POST", "/crm/negative-reviews", Map.of("reviewId", review.get("id").asText()));
    assertThat(reason).contains("已经有处理单");
    assertThat(countCases()).isEqualTo(1);
  }

  @Test
  void 处理单真的落库_差评清单里它不再是没有处理单的候选() throws Exception {
    platformPolicy(2, true, 5);
    var review = registerReview(1);
    var reviewId = UUID.fromString(review.get("id").asText());
    var created = createCase(reviewId);
    assertThat(created.get("caseNo").asText()).startsWith("NVC-");
    assertThat(created.get("caseStatus").asText()).isEqualTo("OPEN");
    assertThat(created.get("resolutionStatus").asText()).isEqualTo("UNRESOLVED");
    assertThat(created.get("negativeRatingThreshold").asInt()).isEqualTo(2);
    assertThat(created.get("reviewRating").asInt()).isEqualTo(1);

    var candidates = getJson("/crm/negative-reviews/candidates");
    assertThat(candidates.get("totalElements").asLong()).isEqualTo(1);
    assertThat(candidates.get("items").get(0).get("caseExists").asBoolean())
        .as("候选清单必须显示这条差评已经有处理单").isTrue();
  }

  // ================================================================ 分类与判定

  @Test
  void 分类按规则判定可申诉_判定结果与判定依据一起落库() throws Exception {
    platformPolicy(2, true, 5);
    causeRule("LOGISTICS", true);
    var review = registerReview(1);
    var created = createCase(UUID.fromString(review.get("id").asText()));
    var caseId = created.get("id").asText();

    var classified =
        postJson(
            "/crm/negative-reviews/" + caseId + "/classify",
            Map.of("causeCode", "LOGISTICS", "causeNote", "包裹延迟 10 天"));
    assertThat(classified.get("causeCode").asText()).isEqualTo("LOGISTICS");
    assertThat(classified.get("appealEligible").asBoolean()).as("平台支持 + 原因可申诉 ⇒ 可申诉").isTrue();
    assertThat(classified.get("appealBasis").asText()).as("判定依据必须落库").isNotBlank();
    assertThat(classified.get("appealBasis").asText()).contains("支持");
    assertThat(classified.get("appealAssessedAt").asText()).isNotBlank();
    assertThat(classified.get("causeRuleVersion").asInt()).isEqualTo(1);
  }

  @Test
  void 平台不支持申诉时如实判为不可申诉_不一律说能申诉() throws Exception {
    platformPolicy(2, false, 0);
    causeRule("LOGISTICS", true);
    var review = registerReview(1);
    var created = createCase(UUID.fromString(review.get("id").asText()));
    var classified =
        postJson(
            "/crm/negative-reviews/" + created.get("id").asText() + "/classify",
            Map.of("causeCode", "LOGISTICS"));
    assertThat(classified.get("appealEligible").asBoolean()).isFalse();
    assertThat(classified.get("appealSupported").asBoolean()).isFalse();
    assertThat(classified.get("appealBasis").asText()).contains("不支持");
  }

  @Test
  void 原因不可申诉时如实判为不可申诉() throws Exception {
    platformPolicy(2, true, 5);
    causeRule("MISJUDGMENT", false);
    var review = registerReview(1);
    var created = createCase(UUID.fromString(review.get("id").asText()));
    var classified =
        postJson(
            "/crm/negative-reviews/" + created.get("id").asText() + "/classify",
            Map.of("causeCode", "MISJUDGMENT"));
    assertThat(classified.get("appealEligible").asBoolean()).isFalse();
    assertThat(classified.get("appealBasis").asText()).contains("不可");
  }

  @Test
  void 没有配置原因规则就不能凭硬编码去判定() throws Exception {
    platformPolicy(2, true, 5);
    var review = registerReview(1);
    var created = createCase(UUID.fromString(review.get("id").asText()));
    var reason =
        errorOf(
            "POST",
            "/crm/negative-reviews/" + created.get("id").asText() + "/classify",
            Map.of("causeCode", "PRODUCT"));
    assertThat(reason).contains("还没有配置");
    assertThat(countRules()).isEqualTo(1);
  }

  // ================================================================ 顺序闸（加粗句）

  @Test
  void 没走完联系买家就不允许请求修改_服务端拒绝并说明原因() throws Exception {
    platformPolicy(2, true, 5);
    causeRule("LOGISTICS", true);
    var review = registerReview(1);
    var created = createCase(UUID.fromString(review.get("id").asText()));
    var caseId = created.get("id").asText();
    postJson("/crm/negative-reviews/" + caseId + "/classify", Map.of("causeCode", "LOGISTICS"));

    var reason =
        errorOf(
            "POST",
            "/crm/negative-reviews/" + caseId + "/actions",
            Map.of(
                "actionType", "REQUEST_MODIFICATION",
                "transport", "MANUAL_HANDOFF",
                "outcome", "SUCCEEDED",
                "summary", "请求买家修改评价"));
    assertThat(reason).as("拒绝必须说明原因").contains("联系买家");
    assertThat(countActions()).as("被拒的动作不得留下任何行").isZero();
  }

  @Test
  void 没走完联系买家就不允许建申诉任务() throws Exception {
    platformPolicy(2, true, 5);
    causeRule("LOGISTICS", true);
    var review = registerReview(1);
    var created = createCase(UUID.fromString(review.get("id").asText()));
    var caseId = created.get("id").asText();
    postJson("/crm/negative-reviews/" + caseId + "/classify", Map.of("causeCode", "LOGISTICS"));

    var reason =
        errorOf(
            "POST",
            "/crm/negative-reviews/" + caseId + "/appeals",
            Map.of("appealType", "REMOVAL", "reason", "物流延误导致评价不实"));
    assertThat(reason).contains("联系买家");
    assertThat(countAppeals()).as("被拒的申诉任务不得留下任何行").isZero();
  }

  @Test
  void 一次失败的联系不算走完联系买家() throws Exception {
    platformPolicy(2, true, 5);
    causeRule("LOGISTICS", true);
    var review = registerReview(1);
    var created = createCase(UUID.fromString(review.get("id").asText()));
    var caseId = created.get("id").asText();
    postJson("/crm/negative-reviews/" + caseId + "/classify", Map.of("causeCode", "LOGISTICS"));
    recordAction(caseId, "CONTACT_BUYER", "MANUAL_HANDOFF", "FAILED", "电话未接通");

    var reason =
        errorOf(
            "POST",
            "/crm/negative-reviews/" + caseId + "/actions",
            Map.of(
                "actionType", "REQUEST_MODIFICATION",
                "transport", "MANUAL_HANDOFF",
                "outcome", "SUCCEEDED"));
    assertThat(reason).contains("联系买家");
    assertThat(countActions()).isEqualTo(1);
  }

  @Test
  void 走完联系买家之后就可以请求修改() throws Exception {
    platformPolicy(2, true, 5);
    causeRule("LOGISTICS", true);
    var review = registerReview(1);
    var created = createCase(UUID.fromString(review.get("id").asText()));
    var caseId = created.get("id").asText();
    postJson("/crm/negative-reviews/" + caseId + "/classify", Map.of("causeCode", "LOGISTICS"));
    recordAction(caseId, "CONTACT_BUYER", "MANUAL_HANDOFF", "SUCCEEDED", "已在平台站内信联系买家");
    var modified =
        recordAction(
            caseId, "REQUEST_MODIFICATION", "MANUAL_HANDOFF", "SUCCEEDED", "买家同意修改评价");
    assertThat(modified.get("actionType").asText()).isEqualTo("REQUEST_MODIFICATION");
    assertThat(countActions()).isEqualTo(2);

    var detail = getJson("/crm/negative-reviews/" + caseId);
    assertThat(detail.get("negativeReview").get("caseStatus").asText()).isEqualTo("IN_PROGRESS");
  }

  // ================================================================ 平台侧未接入 ⇒ 零行写入

  @Test
  void 走平台侧联系买家时适配器未接入_返回409且一行都不写() throws Exception {
    platformPolicy(2, true, 5);
    var review = registerReview(1);
    var created = createCase(UUID.fromString(review.get("id").asText()));
    var caseId = created.get("id").asText();

    var reason =
        errorOf(
            "POST",
            "/crm/negative-reviews/" + caseId + "/actions",
            Map.of(
                "actionType", "CONTACT_BUYER",
                "transport", "PLATFORM_API",
                "outcome", "SUCCEEDED"));
    assertThat(reason).as("未接入必须说清原因").contains("未接入");
    assertThat(countActions()).as("未接入时一行都不能写").isZero();
    var after = getJson("/crm/negative-reviews/" + caseId);
    assertThat(after.get("negativeReview").get("caseStatus").asText())
        .as("未接入时处理单状态必须保持不变").isEqualTo("OPEN");
  }

  @Test
  void 向平台提交申诉时适配器未接入_返回409且一条假记录都不写() throws Exception {
    platformPolicy(2, true, 5);
    causeRule("LOGISTICS", true);
    var review = registerReview(1);
    var created = createCase(UUID.fromString(review.get("id").asText()));
    var caseId = created.get("id").asText();
    postJson("/crm/negative-reviews/" + caseId + "/classify", Map.of("causeCode", "LOGISTICS"));
    recordAction(caseId, "CONTACT_BUYER", "MANUAL_HANDOFF", "SUCCEEDED", "已联系买家");
    var appeal =
        postJson(
            "/crm/negative-reviews/" + caseId + "/appeals",
            Map.of("appealType", "REMOVAL", "reason", "物流延误，评价与事实不符"));
    var appealId = appeal.get("id").asText();
    assertThat(appeal.get("appealStatus").asText()).isEqualTo("DRAFT");
    long actionsBefore = countActions();

    var reason =
        errorOf(
            "POST",
            "/crm/negative-reviews/" + caseId + "/appeals/" + appealId + "/submit",
            Map.of());
    assertThat(reason).contains("未接入");
    assertThat(countAppeals()).as("未接入时不得新增行").isEqualTo(1);
    assertThat(countActions()).as("未接入时不得新增动作").isEqualTo(actionsBefore);

    var after = getJson("/crm/negative-reviews/" + caseId);
    var theAppeal = after.get("appeals").get(0);
    assertThat(theAppeal.get("appealStatus").asText())
        .as("未接入时申诉状态必须仍是「待提交」").isEqualTo("DRAFT");
    assertThat(theAppeal.get("submittedAt").isNull()).as("绝不能出现假的提交时刻").isTrue();
    assertThat(theAppeal.get("platformReceipt").isNull()).as("绝不能出现假的平台回执").isTrue();
  }

  // ================================================================ 申诉配额

  @Test
  void 申诉配额用尽后不能再建申诉任务() throws Exception {
    platformPolicy(2, true, 1);
    causeRule("LOGISTICS", true);
    var review1 = registerReview(1);
    var review2 = registerReview(1);
    var case1 = createCase(UUID.fromString(review1.get("id").asText()));
    var case2 = createCase(UUID.fromString(review2.get("id").asText()));
    for (JsonNode c : List.of(case1, case2)) {
      var id = c.get("id").asText();
      postJson("/crm/negative-reviews/" + id + "/classify", Map.of("causeCode", "LOGISTICS"));
      recordAction(id, "CONTACT_BUYER", "MANUAL_HANDOFF", "SUCCEEDED", "已联系买家");
    }
    var first =
        postJson(
            "/crm/negative-reviews/" + case1.get("id").asText() + "/appeals",
            Map.of("appealType", "REMOVAL", "reason", "理由一"));
    assertThat(first.get("quotaIndex").asInt()).isEqualTo(1);
    assertThat(first.get("quotaLimit").asInt()).isEqualTo(1);

    var reason =
        errorOf(
            "POST",
            "/crm/negative-reviews/" + case2.get("id").asText() + "/appeals",
            Map.of("appealType", "REMOVAL", "reason", "理由二"));
    assertThat(reason).contains("配额");
    assertThat(countAppeals()).isEqualTo(1);
  }

  @Test
  void 撤回申诉归还配额() throws Exception {
    platformPolicy(2, true, 1);
    causeRule("LOGISTICS", true);
    var review = registerReview(1);
    var created = createCase(UUID.fromString(review.get("id").asText()));
    var caseId = created.get("id").asText();
    postJson("/crm/negative-reviews/" + caseId + "/classify", Map.of("causeCode", "LOGISTICS"));
    recordAction(caseId, "CONTACT_BUYER", "MANUAL_HANDOFF", "SUCCEEDED", "已联系买家");
    var appeal =
        postJson(
            "/crm/negative-reviews/" + caseId + "/appeals",
            Map.of("appealType", "REMOVAL", "reason", "理由"));
    var appealId = appeal.get("id").asText();

    var cancelled =
        postJson(
            "/crm/negative-reviews/" + caseId + "/appeals/" + appealId + "/cancel",
            Map.of("reason", "买家已自行修改评价"));
    assertThat(cancelled.get("appealStatus").asText()).isEqualTo("CANCELLED");

    var again =
        postJson(
            "/crm/negative-reviews/" + caseId + "/appeals",
            Map.of("appealType", "REMOVAL", "reason", "换个理由再试"));
    assertThat(again.get("quotaIndex").asInt()).as("撤回之后配额要还回来").isEqualTo(1);
  }

  @Test
  void 人工登记平台判定_申诉通过后处理单落到已解决() throws Exception {
    platformPolicy(2, true, 5);
    causeRule("LOGISTICS", true);
    var review = registerReview(1);
    var created = createCase(UUID.fromString(review.get("id").asText()));
    var caseId = created.get("id").asText();
    postJson("/crm/negative-reviews/" + caseId + "/classify", Map.of("causeCode", "LOGISTICS"));
    recordAction(caseId, "CONTACT_BUYER", "MANUAL_HANDOFF", "SUCCEEDED", "已联系买家");
    var appeal =
        postJson(
            "/crm/negative-reviews/" + caseId + "/appeals",
            Map.of("appealType", "REMOVAL", "reason", "物流延误，评价与事实不符"));
    var appealId = appeal.get("id").asText();

    var submitted =
        postJson(
            "/crm/negative-reviews/" + caseId + "/appeals/" + appealId + "/submitted",
            Map.of("submittedAt", "2026-06-05T10:00:00+08:00", "platformReference", "CASE-X1"));
    assertThat(submitted.get("appealStatus").asText()).isEqualTo("SUBMITTED");
    assertThat(submitted.get("submittedAt").asText()).isNotBlank();

    var decided =
        postJson(
            "/crm/negative-reviews/" + caseId + "/appeals/" + appealId + "/outcome",
            Map.of(
                "result", "APPROVED",
                "decidedAt", "2026-06-08T10:00:00+08:00",
                "platformReceipt", "平台已移除差评",
                "reason", "平台认定评价与事实不符"));
    assertThat(decided.get("appealStatus").asText()).isEqualTo("APPROVED");
    assertThat(decided.get("decidedResult").asText()).isEqualTo("APPROVED");

    var detail = getJson("/crm/negative-reviews/" + caseId);
    assertThat(detail.get("negativeReview").get("caseStatus").asText()).isEqualTo("RESOLVED");
    assertThat(detail.get("negativeReview").get("resolutionStatus").asText()).isEqualTo("RESOLVED");
    assertThat(detail.get("negativeReview").get("resolutionAt").asText()).isNotBlank();
  }

  @Test
  void 申诉被驳回时处理单仍保持未解决() throws Exception {
    platformPolicy(2, true, 5);
    causeRule("LOGISTICS", true);
    var review = registerReview(1);
    var created = createCase(UUID.fromString(review.get("id").asText()));
    var caseId = created.get("id").asText();
    postJson("/crm/negative-reviews/" + caseId + "/classify", Map.of("causeCode", "LOGISTICS"));
    recordAction(caseId, "CONTACT_BUYER", "MANUAL_HANDOFF", "SUCCEEDED", "已联系买家");
    var appeal =
        postJson(
            "/crm/negative-reviews/" + caseId + "/appeals",
            Map.of("appealType", "REMOVAL", "reason", "理由"));
    var appealId = appeal.get("id").asText();
    postJson(
        "/crm/negative-reviews/" + caseId + "/appeals/" + appealId + "/submitted",
        Map.of("submittedAt", "2026-06-05T10:00:00+08:00"));
    var decided =
        postJson(
            "/crm/negative-reviews/" + caseId + "/appeals/" + appealId + "/outcome",
            Map.of("result", "REJECTED", "decidedAt", "2026-06-08T10:00:00+08:00"));
    assertThat(decided.get("appealStatus").asText()).isEqualTo("REJECTED");
    var detail = getJson("/crm/negative-reviews/" + caseId);
    assertThat(detail.get("negativeReview").get("resolutionStatus").asText())
        .as("申诉被驳回 ≠ 差评已解决").isEqualTo("UNRESOLVED");
  }

  // ================================================================ 售后转交

  @Test
  void 售后转交必须写原因_且不落任何下游单据id() throws Exception {
    platformPolicy(2, true, 5);
    var review = registerReview(1);
    var created = createCase(UUID.fromString(review.get("id").asText()));
    var caseId = created.get("id").asText();

    var reason =
        errorOf(
            "POST",
            "/crm/negative-reviews/" + caseId + "/actions",
            Map.of(
                "actionType", "AFTER_SALES_HANDOFF",
                "transport", "INTERNAL",
                "outcome", "SUCCEEDED"));
    assertThat(reason).contains("必须写原因");
    assertThat(countActions()).isZero();

    var handoff =
        postJson(
            "/crm/negative-reviews/" + caseId + "/actions",
            Map.of(
                "actionType", "AFTER_SALES_HANDOFF",
                "transport", "INTERNAL",
                "outcome", "SUCCEEDED",
                "summary", "包裹破损，需要退款",
                "afterSalesReason", "包裹破损，需要退款"));
    assertThat(handoff.get("afterSalesReason").asText()).isNotBlank();
    // 🔴 11 售后未交付 ⇒ 表里**没有**任何「售后单据 id」列。
    var columns =
        sql.fetch(
            "select column_name from information_schema.columns"
                + " where table_name = 'crm_negative_review_action'");
    for (var row : columns) {
      var name = row.get(0, String.class);
      assertThat(name.toLowerCase())
          .as("动作表不得出现任何下游单据 id 列：" + name)
          .doesNotContain("after_sales_ticket")
          .doesNotContain("after_sales_order")
          .doesNotContain("after_sales_id");
    }
  }

  // ================================================================ 解决率统计

  @Test
  void 解决率由服务端算_分子分母口径随响应一起给出() throws Exception {
    platformPolicy(2, true, 5);
    causeRule("LOGISTICS", true);
    var review1 = registerReview(1);
    var review2 = registerReview(1);
    var case1 = createCase(UUID.fromString(review1.get("id").asText()));
    var case2 = createCase(UUID.fromString(review2.get("id").asText()));
    postJson("/crm/negative-reviews/" + case1.get("id").asText() + "/classify", Map.of("causeCode", "LOGISTICS"));
    recordAction(case1.get("id").asText(), "CONTACT_BUYER", "MANUAL_HANDOFF", "SUCCEEDED", "已联系买家");
    var resolved =
        recordAction(case1.get("id").asText(), "RESOLVE", "INTERNAL", "SUCCEEDED", "买家已修改评价，问题解决");
    assertThat(resolved.get("actionType").asText()).isEqualTo("RESOLVE");

    var stats = getJson("/crm/negative-reviews/stats");
    assertThat(stats.get("totalCases").asLong()).isEqualTo(2);
    assertThat(stats.get("resolvedCases").asLong()).isEqualTo(1);
    assertThat(stats.get("unresolvedCases").asLong()).isEqualTo(1);
    assertThat(stats.get("resolutionRate").asDouble()).isEqualTo(0.5000);
    assertThat(stats.get("resolutionRateBasis").asText()).as("分母口径必须写清").contains("处理单数");
    assertThat(stats.get("resolutionRateNumerator").asText()).as("分子口径必须写清").contains("已解决");
    assertThat(stats.get("contactBuyerDoneCases").asLong()).isEqualTo(1);
    // 按原因拆开：一单已分类（物流），一单还没分类 ⇒ 两个桶（未分类也是一个桶）。
    assertThat(stats.get("byCause").size()).isEqualTo(2);
    var logistics =
        stats.get("byCause").get(0).get("causeCode").asText().equals("LOGISTICS")
            ? stats.get("byCause").get(0)
            : stats.get("byCause").get(1);
    assertThat(logistics.get("causeCode").asText()).isEqualTo("LOGISTICS");
    assertThat(logistics.get("count").asLong()).isEqualTo(1);
    assertThat(logistics.get("resolvedCount").asLong()).isEqualTo(1);
    assertThat(logistics.get("appealEligibleCount").asLong()).isEqualTo(1);
  }

  @Test
  void 解决率的筛选面生效_换筛选就是换口径() throws Exception {
    platformPolicy(2, true, 5);
    var review = registerReview(1);
    createCase(UUID.fromString(review.get("id").asText()));
    assertThat(getJson("/crm/negative-reviews/stats").get("totalCases").asLong()).isEqualTo(1);
    assertThat(getJson("/crm/negative-reviews/stats?causeCode=LOGISTICS").get("totalCases").asLong())
        .isZero();
    assertThat(getJson("/crm/negative-reviews/stats?caseStatus=OPEN").get("totalCases").asLong())
        .isEqualTo(1);
  }

  // ================================================================ 时区

  @Test
  void 时区不静默偏移_写进去的绝对时刻原样读回() throws Exception {
    platformPolicy(2, true, 5);
    var review = registerReview(1);
    var created = createCase(UUID.fromString(review.get("id").asText()));
    var caseId = created.get("id").asText();
    var action =
        postJson(
            "/crm/negative-reviews/" + caseId + "/actions",
            Map.of(
                "actionType", "CONTACT_BUYER",
                "transport", "MANUAL_HANDOFF",
                "outcome", "SUCCEEDED",
                "occurredAt", "2026-06-03T14:20:00+08:00"));
    OffsetDateTime written = OffsetDateTime.parse(action.get("actionAt").asText());
    assertThat(written.toInstant())
        .as("写入的 +08:00 时刻必须还是同一个绝对时刻（不能偏 8 小时）")
        .isEqualTo(OffsetDateTime.parse("2026-06-03T14:20:00+08:00").toInstant());
  }

  // ================================================================ 详情

  @Test
  void 详情给出动作流水_申诉任务与订单上下文不可用时必须说明原因() throws Exception {
    platformPolicy(2, true, 5);
    causeRule("LOGISTICS", true);
    var review = registerReview(1);
    var created = createCase(UUID.fromString(review.get("id").asText()));
    var caseId = created.get("id").asText();
    postJson("/crm/negative-reviews/" + caseId + "/classify", Map.of("causeCode", "LOGISTICS"));
    recordAction(caseId, "CONTACT_BUYER", "MANUAL_HANDOFF", "SUCCEEDED", "已联系买家");

    var detail = getJson("/crm/negative-reviews/" + caseId);
    assertThat(detail.get("actions").size()).isEqualTo(1);
    assertThat(detail.get("actions").get(0).get("actionType").asText()).isEqualTo("CONTACT_BUYER");
    assertThat(detail.get("appeals").size()).isZero();
    // 上游订单事实读不到（桩返回空）⇒ 必须 available=false + 原因，绝不返回空白。
    assertThat(detail.get("orderContext").get("available").asBoolean()).isFalse();
    assertThat(detail.get("orderContext").get("reason").asText()).isNotBlank();
    // 当时用的规则也要给出来。
    assertThat(detail.get("platformPolicy").get("ruleScope").asText()).isEqualTo("PLATFORM");
    assertThat(detail.get("causeRule").get("causeCode").asText()).isEqualTo("LOGISTICS");
  }
}
