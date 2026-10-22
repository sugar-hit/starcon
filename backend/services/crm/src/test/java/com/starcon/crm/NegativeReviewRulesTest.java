package com.starcon.crm;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
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
 * 15.2.1.3 差评处理的<b>规则语义</b>测试。
 *
 * <p>蓝图加粗句「<b>判定是否申诉（部分平台支持）</b>」与职责句里的
 * 「<b>标记原因（产品/物流/服务/误判）</b>」都要求这两件事**真的可配** ——
 * 所以本类专门盯住规则这一层：
 *
 * <ul>
 *   <li>规则**真的落库**（不是代码里的常量）：改一条规则，处理单的判定立刻跟着变。</li>
 *   <li><b>一个平台只有一条策略</b>、<b>一个（平台 × 原因）只有一条原因规则</b> ——
 *       两条并存时「这条差评按哪条规则判的」就答不了。</li>
 *   <li><b>阈值逐平台可配</b>：两个平台可以有不同的差评口径。</li>
 *   <li><b>平台支不支持申诉要如实区分</b>：不支持的平台判为不可申诉，不一律说能申诉。</li>
 *   <li><b>原地改 = 版本 +1</b>：处理单上的规则版本快照才有意义。</li>
 *   <li><b>配额周期可配</b>：月度 / 季度 / 年度 / 不开放。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NegativeReviewRulesTest {

  static final UUID tenant = UUID.randomUUID();
  static final UUID userId = UUID.randomUUID();
  static final UUID shopId = UUID.randomUUID();

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

  @BeforeEach
  void reset() {
    NegativeReviewTestSupport.ACTORS.clear();
    NegativeReviewTestSupport.ACTORS.put(
        "session",
        NegativeReviewTestSupport.actor(
            userId, tenant, "Test", NegativeReviewTestSupport.PERMISSIONS));
    NegativeReviewTestSupport.SHOPS.clear();
    NegativeReviewTestSupport.SHOPS.add(
        new SignoffDeliveredOrderPort.ShopView(
            shopId, "SHOP-1", "主店铺", "AMAZON", "亚马逊", "SELF_FULFILLED", "ACTIVE"));
    NegativeReviewTestSupport.asMigrationUser(
        "truncate table crm_negative_review_action, crm_negative_review_appeal,"
            + " crm_negative_review_case, crm_negative_review_rule,"
            + " crm_product_review restart identity cascade");
  }

  // ================================================================ 助手

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
    String message = resolved.getMessage() == null ? "" : resolved.getMessage();
    if (resolved instanceof org.springframework.web.server.ResponseStatusException rse
        && rse.getReason() != null) {
      message = message + " " + rse.getReason();
    }
    return message;
  }

  /** 建一条平台策略规则。 */
  JsonNode platformPolicy(
      String platformCode, int threshold, boolean appealSupported, int quota, String period)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ruleScope", "PLATFORM");
    body.put("platformCode", platformCode);
    body.put("label", platformCode + " 策略");
    body.put("negativeRatingThreshold", threshold);
    body.put("appealSupported", appealSupported);
    body.put("contactBuyerRequired", true);
    body.put("appealQuotaLimit", quota);
    body.put("quotaPeriod", period);
    body.put("appealWindowDays", 30);
    return postJson("/crm/negative-reviews/rules", body);
  }

  /** 建一条原因规则。 */
  JsonNode causeRule(String platformCode, String causeCode, boolean appealEligible)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ruleScope", "CAUSE");
    body.put("platformCode", platformCode);
    body.put("causeCode", causeCode);
    body.put("label", causeCode + " 原因");
    body.put("appealEligible", appealEligible);
    body.put("appealBasisTemplate", "依据模板：" + causeCode);
    return postJson("/crm/negative-reviews/rules", body);
  }

  long countRules() {
    return tenants.run(
        tenant, () -> sql.fetchOne("select count(*) from crm_negative_review_rule").get(0, Long.class));
  }

  // ================================================================ 规则落库与唯一性

  @Test
  void 规则真的落库_不是代码里的常量() throws Exception {
    var rule = platformPolicy("AMAZON", 2, true, 5, "MONTHLY");
    assertThat(rule.get("ruleNo").asText()).startsWith("NVR-");
    assertThat(rule.get("ruleScope").asText()).isEqualTo("PLATFORM");
    assertThat(rule.get("negativeRatingThreshold").asInt()).isEqualTo(2);
    assertThat(rule.get("appealSupported").asBoolean()).isTrue();
    assertThat(rule.get("appealQuotaLimit").asInt()).isEqualTo(5);
    assertThat(countRules()).isEqualTo(1);

    // 直接读库确认它真的在表里（不是只在响应里）。
    var stored =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                    "select platform_code, negative_rating_threshold, appeal_supported,"
                        + " appeal_quota_limit, quota_period from crm_negative_review_rule"));
    assertThat(stored).hasSize(1);
    assertThat(stored.get(0).get("platform_code", String.class)).isEqualTo("AMAZON");
    assertThat(stored.get(0).get("negative_rating_threshold", Integer.class)).isEqualTo(2);
  }

  @Test
  void 一个平台只允许一条策略规则_否则这条差评按哪条阈值判的答不了() throws Exception {
    platformPolicy("AMAZON", 2, true, 5, "MONTHLY");
    var reason = errorOf("POST", "/crm/negative-reviews/rules", policyBody("AMAZON", 1, true, 5, "MONTHLY"));
    assertThat(reason).contains("已经有一条差评处理策略");
    assertThat(countRules()).isEqualTo(1);
  }

  @Test
  void 一个平台加一个原因只允许一条原因规则() throws Exception {
    causeRule("AMAZON", "LOGISTICS", true);
    var reason =
        errorOf("POST", "/crm/negative-reviews/rules", causeBody("AMAZON", "LOGISTICS", false));
    assertThat(reason).contains("已经有一条规则");
    assertThat(countRules()).isEqualTo(1);
  }

  @Test
  void 平台策略不能挂原因_原因规则必须挂原因() throws Exception {
    var platformWithCause = policyBody("AMAZON", 2, true, 5, "MONTHLY");
    platformWithCause.put("causeCode", "LOGISTICS");
    var withCause = errorOf("POST", "/crm/negative-reviews/rules", platformWithCause);
    assertThat(withCause).contains("不能带原因分类");
    var causeWithoutCode = causeBody("AMAZON", "PRODUCT", true);
    causeWithoutCode.remove("causeCode");
    var withoutCause = errorOf("POST", "/crm/negative-reviews/rules", causeWithoutCode);
    assertThat(withoutCause).contains("必须指定原因分类");
    assertThat(countRules()).isZero();
  }

  @Test
  void 原地改规则版本加一_处理单上的版本快照才有意义() throws Exception {
    var rule = platformPolicy("AMAZON", 2, true, 5, "MONTHLY");
    assertThat(rule.get("version").asInt()).isEqualTo(1);
    var updated =
        http.perform(
                put("/crm/negative-reviews/rules/" + rule.get("id").asText())
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        NegativeReviewTestSupport.JSON.writeValueAsBytes(
                            Map.of("appealQuotaLimit", 9))))
            .andReturn();
    assertThat(updated.getResponse().getStatus()).isEqualTo(200);
    var after =
        NegativeReviewTestSupport.JSON.readTree(updated.getResponse().getContentAsString());
    assertThat(after.get("version").asInt()).isEqualTo(2);
    assertThat(after.get("appealQuotaLimit").asInt()).isEqualTo(9);
  }

  // ================================================================ 阈值逐平台可配

  @Test
  void 阈值逐平台可配_两个平台可以有不同口径() throws Exception {
    platformPolicy("AMAZON", 2, true, 5, "MONTHLY");
    platformPolicy("EBAY", 1, true, 5, "MONTHLY");
    var rules = getJson("/crm/negative-reviews/rules?ruleScope=PLATFORM");
    assertThat(rules.get("totalElements").asLong()).isEqualTo(2);
    var byPlatform = new LinkedHashMap<String, Integer>();
    for (JsonNode item : rules.get("items")) {
      byPlatform.put(item.get("platformCode").asText(), item.get("negativeRatingThreshold").asInt());
    }
    assertThat(byPlatform).containsEntry("AMAZON", 2).containsEntry("EBAY", 1);
  }

  @Test
  void 策略规则在店铺目录里如实显示_没配的平台要说得出来() throws Exception {
    platformPolicy("AMAZON", 2, true, 5, "MONTHLY");
    var shops = getJson("/crm/negative-reviews/shops");
    assertThat(shops.size()).isEqualTo(1);
    var shop = shops.get(0);
    assertThat(shop.get("policyConfigured").asBoolean()).isTrue();
    assertThat(shop.get("negativeRatingThreshold").asInt()).isEqualTo(2);
    assertThat(shop.get("appealSupported").asBoolean()).isTrue();
    assertThat(shop.get("policyNote").asText()).contains("rating <= 2");
  }

  // ================================================================ 配额周期

  @Test
  void 配额周期可配_季度配额键要能算出来() throws Exception {
    platformPolicy("AMAZON", 2, true, 3, "QUARTERLY");
    var stats = getJson("/crm/negative-reviews/stats");
    var buckets = stats.get("byPlatformQuota");
    assertThat(buckets.size()).isEqualTo(1);
    assertThat(buckets.get(0).get("quotaPeriod").asText()).isEqualTo("QUARTERLY");
    assertThat(buckets.get(0).get("quotaPeriodKey").asText())
        .as("季度配额键必须落成「年-季」")
        .matches("\\d{4}-Q[1-4]");
    assertThat(buckets.get(0).get("quotaLimit").asInt()).isEqualTo(3);
    assertThat(buckets.get(0).get("quotaUsed").asLong()).isZero();
    assertThat(buckets.get(0).get("quotaRemaining").asLong()).isEqualTo(3);
  }

  @Test
  void 配额周期非法时拒收() throws Exception {
    var reason = errorOf("POST", "/crm/negative-reviews/rules", policyBody("AMAZON", 2, true, 3, "WEEKLY"));
    assertThat(reason).contains("配额周期");
    assertThat(countRules()).isZero();
  }

  // ================================================================ 规则列表筛选

  @Test
  void 规则列表按作用域_平台_原因筛选() throws Exception {
    platformPolicy("AMAZON", 2, true, 5, "MONTHLY");
    causeRule("AMAZON", "LOGISTICS", true);
    causeRule("AMAZON", "PRODUCT", false);
    assertThat(getJson("/crm/negative-reviews/rules").get("totalElements").asLong()).isEqualTo(3);
    assertThat(getJson("/crm/negative-reviews/rules?ruleScope=CAUSE").get("totalElements").asLong())
        .isEqualTo(2);
    assertThat(getJson("/crm/negative-reviews/rules?causeCode=LOGISTICS").get("totalElements").asLong())
        .isEqualTo(1);
    assertThat(
            getJson("/crm/negative-reviews/rules?platformCode=EBAY").get("totalElements").asLong())
        .isZero();
  }

  @Test
  void 单条规则读得到_不存在的读不到() throws Exception {
    var rule = platformPolicy("AMAZON", 2, true, 5, "MONTHLY");
    var one = getJson("/crm/negative-reviews/rules/" + rule.get("id").asText());
    assertThat(one.get("id").asText()).isEqualTo(rule.get("id").asText());
    var missing =
        http.perform(
                get("/crm/negative-reviews/rules/" + UUID.randomUUID())
                    .header("Authorization", "session"))
            .andReturn();
    assertThat(missing.getResponse().getStatus()).isEqualTo(404);
  }

  // ================================================================ 入参构造

  static Map<String, Object> policyBody(
      String platformCode, int threshold, boolean appealSupported, int quota, String period) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ruleScope", "PLATFORM");
    body.put("platformCode", platformCode);
    body.put("label", platformCode + " 策略");
    body.put("negativeRatingThreshold", threshold);
    body.put("appealSupported", appealSupported);
    body.put("contactBuyerRequired", true);
    body.put("appealQuotaLimit", quota);
    body.put("quotaPeriod", period);
    body.put("appealWindowDays", 30);
    return body;
  }

  static Map<String, Object> causeBody(
      String platformCode, String causeCode, boolean appealEligible) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ruleScope", "CAUSE");
    body.put("platformCode", platformCode);
    body.put("causeCode", causeCode);
    body.put("label", causeCode + " 原因");
    body.put("appealEligible", appealEligible);
    return body;
  }

  /** 词表要全：前端据此渲染下拉，不用自己猜有哪些原因 / 状态。 */
  @Test
  void 能力声明带全词表() throws Exception {
    var caps = getJson("/crm/negative-reviews/capabilities");
    assertThat(textsOf(caps.get("causeCodes")))
        .contains("PRODUCT", "LOGISTICS", "SERVICE", "MISJUDGMENT");
    assertThat(textsOf(caps.get("caseStatuses")))
        .contains("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED");
    assertThat(textsOf(caps.get("resolutionStatuses")))
        .contains("UNRESOLVED", "RESOLVED", "CLOSED_UNRESOLVED");
    assertThat(textsOf(caps.get("appealTypes")))
        .contains("REMOVAL", "MODIFICATION", "POLICY_VIOLATION");
    assertThat(textsOf(caps.get("appealStatuses")))
        .contains("DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "CANCELLED");
    assertThat(caps.get("highlight").asText()).contains("联系买家");
  }

  /** 把一个 JSON 字符串数组读成字符串列表。 */
  static List<String> textsOf(JsonNode node) {
    List<String> out = new java.util.ArrayList<>();
    for (JsonNode item : node) out.add(item.asText());
    return out;
  }
}
