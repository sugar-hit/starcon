package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 17.5.1.2 编号规则。
 *
 * <p>重点不是"能存进去"，而是蓝图点名的三件事真的成立，外加多租户三断言：
 *
 * <ol>
 *   <li><b>规则冲突要在配置时校验</b>——同一实体上两条启用主规则的生效区间不允许交叠， 写入前就 409；相邻区间（前一条的 {@code effectiveTo} 等于后一条的
 *       {@code effectiveFrom}）不算冲突，那是正常的接续配置。
 *   <li><b>序号按实体/日期重置</b>——跨日、跨月、跨年各换一个桶，序号从头开始； 换一个实体则是另一条独立的计数线。分桶用的是业务日期（补录上周单据要落上周的桶）。
 *   <li><b>多级嵌套</b>——规则可嵌子规则，预览必须真实反映嵌套后的最终编号， 而不是把子规则当成一段死文本。嵌套环与超深嵌套在配置时就被拒。
 * </ol>
 *
 * <p>另外两条边界同样要实跑：<b>编号是可读标识不是外键</b>（单据引用只是字符串， 且同一个实体下同一个编号不允许发两次），以及<b>取号与预览必须一致</b>
 * （预览不占号，取号才递增，两个出口共用同一段渲染逻辑）。
 *
 * <p>时间字段按值断言：用 {@code Timestamp.valueOf(LocalDateTime)} 绑 timestamptz 会静默偏 −28800 秒，所以 {@code
 * effectiveFrom} / {@code issuedAt} 都落在 "请求前后两个墙钟之间"才算通过。
 */
class NumberRuleIntegrationTest extends IdentityIntegrationTestBase {

  /**
   * 每个用例前清空本模块的数据。
   *
   * <p>容器与 local-admin 租户在所有测试类之间共享（见 IdentityIntegrationTestBase），
   * 同一个实体上的主规则会被上一个用例配过而撞冲突校验。已发放编号有不可篡改触发器， 普通 DELETE 会被它挡住，所以用 TRUNCATE —— 它不触发行级触发器。
   */
  @BeforeEach
  void resetModule() throws Exception {
    try (var c =
            DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate document_number_issued, document_number_sequence,"
              + " document_number_rule_segment, document_number_rule");
    }
  }

  private TenantTransactions tx() {
    return new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  // ------------------------------------------------------------------
  // 请求辅助
  // ------------------------------------------------------------------

  private JsonNode entities(String token) throws Exception {
    return request(token, "GET", "/identity/numbering/entities", null, 200);
  }

  private JsonNode rules(String token) throws Exception {
    return request(token, "GET", "/identity/numbering/rules", null, 200);
  }

  private JsonNode findRule(JsonNode list, String name) {
    for (JsonNode n : list) if (name.equals(n.get("ruleName").asText())) return n;
    return null;
  }

  private JsonNode segment(JsonNode n, String type) {
    for (JsonNode s : n.get("segments")) if (type.equals(s.get("segmentType").asText())) return s;
    return null;
  }

  private Map<String, Object> prefix(String value) {
    return Map.of("segmentType", "PREFIX", "literalText", value);
  }

  private Map<String, Object> suffix(String value) {
    return Map.of("segmentType", "SUFFIX", "literalText", value);
  }

  private Map<String, Object> date(String format) {
    return Map.of("segmentType", "DATE", "dateFormat", format);
  }

  private Map<String, Object> sequence(int width, long start, String reset, String scope) {
    var input = new LinkedHashMap<String, Object>();
    input.put("segmentType", "SEQUENCE");
    input.put("sequenceWidth", width);
    input.put("sequenceStart", start);
    input.put("sequenceReset", reset);
    input.put("sequenceScope", scope);
    return input;
  }

  private Map<String, Object> nested(UUID childRuleId) {
    return Map.of("segmentType", "NESTED", "childRuleId", childRuleId.toString());
  }

  /** 规则输入。生效时间默认给一段很宽的区间，具体用例需要收窄时自己覆盖。 */
  private Map<String, Object> ruleInput(
      String entityCode, String name, String kind, List<Map<String, Object>> segments) {
    var input = new LinkedHashMap<String, Object>();
    input.put("entityCode", entityCode);
    input.put("ruleName", name);
    input.put("ruleKind", kind);
    input.put("status", "ACTIVE");
    input.put("timezone", "Asia/Shanghai");
    input.put("description", "集成测试规则");
    input.put("segments", segments);
    return input;
  }

  private UUID createRule(String token, Map<String, Object> input) throws Exception {
    return UUID.fromString(
        request(token, "POST", "/identity/numbering/rules", input, 200).asText());
  }

  /**
   * 给规则安排一段不与既有规则交叠的生效区间。
   *
   * <p>默认（{@code ruleInput} 不带生效时间）是从 now() 起长期有效，所以同一个实体上 再建第二条主规则会被冲突校验挡下——这正是「规则冲突要在配置时校验」要的行为。
   * 用例里需要同实体多条规则时必须显式排开区间，否则 409 是正确结果而不是缺陷。
   */
  private Map<String, Object> during(Map<String, Object> input, String from, String to) {
    input.put("effectiveFrom", from);
    input.put("effectiveTo", to);
    return input;
  }

  /** 生成一个编号：不落单据引用，只验证编号本身。 */
  private JsonNode issue(String token, UUID ruleId, String documentDate) throws Exception {
    var input = new LinkedHashMap<String, Object>();
    if (documentDate != null) input.put("documentDate", documentDate);
    return request(token, "POST", "/identity/numbering/rules/" + ruleId + "/next", input, 200);
  }

  private JsonNode preview(String token, Map<String, Object> input) throws Exception {
    return request(token, "POST", "/identity/numbering/preview", input, 200);
  }

  private JsonNode sequences(String token, String entityCode) throws Exception {
    var q = entityCode == null ? "" : "?entityCode=" + entityCode;
    return request(token, "GET", "/identity/numbering/sequences" + q, null, 200);
  }

  // ------------------------------------------------------------------
  // 实体码表
  // ------------------------------------------------------------------

  @Test
  void factoryEntityCodeTableCoversTheDocumentTypesDownstreamCreates() throws Exception {
    var list = entities(login());

    // 蓝图点名的下游是「全部单据创建（订单、采购、出库、售后等）」，这些实体必须在码表里。
    for (String code :
        List.of(
            "ORDER",
            "PURCHASE_ORDER",
            "INBOUND",
            "OUTBOUND",
            "STOCK_TRANSFER",
            "STOCK_ADJUSTMENT",
            "AFTERSALE",
            "RETURN_ORDER",
            "PAYMENT",
            "RECEIPT",
            "CREDIT_NOTE")) assertThat(findByCode(list, code)).as("实体 %s 必须在码表里", code).isNotNull();

    // 码表按 sort_order 稳定排序，前端下拉不用自己排。
    assertThat(list.get(0).get("code").asText()).isEqualTo("ORDER");
    assertThat(findByCode(list, "ORDER").get("name").asText()).isEqualTo("销售订单");
    assertThat(findByCode(list, "ORDER").get("description").asText()).contains("9");
  }

  private JsonNode findByCode(JsonNode list, String code) {
    for (JsonNode n : list) if (code.equals(n.get("code").asText())) return n;
    return null;
  }

  // ------------------------------------------------------------------
  // 前缀 + 日期 + 序号 + 后缀
  // ------------------------------------------------------------------

  @Test
  void prefixDateSequenceSuffixComposeTheNumber() throws Exception {
    String token = login();
    UUID id =
        createRule(
            token,
            ruleInput(
                "ORDER",
                "销售订单标准编号",
                "PRIMARY",
                List.of(
                    prefix("SO"),
                    date("yyyyMMdd"),
                    sequence(4, 1, "NONE", "ENTITY"),
                    suffix("-US"))));

    var detail = request(token, "GET", "/identity/numbering/rules/" + id, null, 200);

    // 段按 position 有序，四段齐全
    var segments = detail.get("segments");
    assertThat(segments).hasSize(4);
    assertThat(segments.get(0).get("segmentType").asText()).isEqualTo("PREFIX");
    assertThat(segments.get(1).get("segmentType").asText()).isEqualTo("DATE");
    assertThat(segments.get(2).get("segmentType").asText()).isEqualTo("SEQUENCE");
    assertThat(segments.get(3).get("segmentType").asText()).isEqualTo("SUFFIX");

    // 样例编号把四段都体现出来了
    var sample = detail.get("sampleNumber").asText();
    assertThat(sample).startsWith("SO").endsWith("-US");
    assertThat(sample).matches("SO\\d{8}0001-US");

    // 逐段 rendered 让配置页看清编号是怎么拼的
    assertThat(segments.get(0).get("rendered").asText()).isEqualTo("SO");
    assertThat(segments.get(2).get("rendered").asText()).isEqualTo("0001");

    // 取号拿到的第一个号与样例一致（都从 1 起）
    assertThat(issue(token, id, null).get("number").asText()).isEqualTo(sample);
  }

  @Test
  void dateSegmentRendersInTheRuleTimezoneNotUtc() throws Exception {
    String token = login();
    // 2026-10-07T20:00Z 在上海是 10-08，在 UTC 是 10-07：这条规则必须渲染成 10-08。
    var at = OffsetDateTime.of(2026, 10, 7, 20, 0, 0, 0, ZoneOffset.UTC).toString();

    UUID shanghai =
        createRule(
            token,
            during(
                ruleInput(
                    "ORDER",
                    "上海时区编号",
                    "PRIMARY",
                    List.of(prefix("SH"), date("yyyyMMdd"), sequence(3, 1, "NONE", "ENTITY"))),
                "2020-01-01T00:00:00Z",
                "2021-01-01T00:00:00Z"));
    var shanghaiInput = new LinkedHashMap<String, Object>();
    shanghaiInput.put("ruleId", shanghai.toString());
    shanghaiInput.put("documentDate", at);
    assertThat(preview(token, shanghaiInput).get("number").asText()).isEqualTo("SH20261008001");

    var utcRuleInput =
        during(
            ruleInput(
                "ORDER",
                "UTC 编号",
                "PRIMARY",
                List.of(prefix("UT"), date("yyyyMMdd"), sequence(3, 1, "NONE", "ENTITY"))),
            "2021-01-01T00:00:00Z",
            "2022-01-01T00:00:00Z");
    utcRuleInput.put("timezone", "UTC");
    UUID utcRule = createRule(token, utcRuleInput);
    var utcInput = new LinkedHashMap<String, Object>();
    utcInput.put("ruleId", utcRule.toString());
    utcInput.put("documentDate", at);
    assertThat(preview(token, utcInput).get("number").asText()).isEqualTo("UT20261007001");
  }

  // ------------------------------------------------------------------
  // 序号按实体/日期重置
  // ------------------------------------------------------------------

  @Test
  void sequenceResetsWhenTheBusinessDateCrossesADay() throws Exception {
    String token = login();
    UUID id =
        createRule(
            token,
            ruleInput(
                "OUTBOUND",
                "出库按日重置",
                "PRIMARY",
                List.of(prefix("OB"), date("yyyyMMdd"), sequence(4, 1, "DAILY", "ENTITY"))));

    String day1 = "2026-03-01T09:00:00Z", day2 = "2026-03-02T09:00:00Z";

    // 同一天连发三个：序号连续递增
    assertThat(issue(token, id, day1).get("number").asText()).isEqualTo("OB202603010001");
    assertThat(issue(token, id, day1).get("number").asText()).isEqualTo("OB202603010002");
    assertThat(issue(token, id, day1).get("number").asText()).isEqualTo("OB202603010003");

    // 跨日：新的重置桶，序号从 1 重新开始（日期段也随之变成新的一天）
    assertThat(issue(token, id, day2).get("number").asText()).isEqualTo("OB202603020001");
    assertThat(issue(token, id, day2).get("number").asText()).isEqualTo("OB202603020002");

    // 回补第一天的单据：落回第一天的桶接着编号，不是插进第二天的序号中间
    assertThat(issue(token, id, day1).get("number").asText()).isEqualTo("OB202603010004");

    // 计数器视图按桶分行，两天各一个
    var counters = sequences(token, "OUTBOUND");
    assertThat(counters).hasSize(2);
    var buckets = new java.util.TreeMap<String, String>();
    for (JsonNode c : counters)
      buckets.put(c.get("resetKey").asText(), c.get("currentValue").toString());
    assertThat(buckets.get("2026-03-01")).isEqualTo("4");
    assertThat(buckets.get("2026-03-02")).isEqualTo("2");
  }

  @Test
  void sequenceResetsMonthlyAndYearlyAndIsIndependentPerEntity() throws Exception {
    String token = login();
    UUID monthly =
        createRule(
            token,
            ruleInput(
                "PURCHASE_ORDER",
                "采购按月重置",
                "PRIMARY",
                List.of(prefix("PO"), date("yyyyMM"), sequence(3, 1, "MONTHLY", "ENTITY"))));
    // 规则时区是 Asia/Shanghai，所以这里用不会跨日的时刻：断言聚焦"按月分桶"本身，
    // 而不是被时区换算搅进去（时区换算另有 dateSegmentRendersInTheRuleTimezoneNotUtc 覆盖）。
    assertThat(issue(token, monthly, "2026-03-15T00:00:00Z").get("number").asText())
        .isEqualTo("PO202603001");
    assertThat(issue(token, monthly, "2026-04-10T00:00:00Z").get("number").asText())
        .isEqualTo("PO202604001");

    UUID yearly =
        createRule(
            token,
            ruleInput(
                "PAYMENT",
                "付款按年重置",
                "PRIMARY",
                List.of(prefix("PAY"), date("yyyy"), sequence(2, 1, "YEARLY", "ENTITY"))));
    // 日期格式是 yyyy（4 位年份），不是 yy
    assertThat(issue(token, yearly, "2026-12-15T10:00:00Z").get("number").asText())
        .isEqualTo("PAY202601");
    assertThat(issue(token, yearly, "2027-01-05T10:00:00Z").get("number").asText())
        .isEqualTo("PAY202701");

    // 不同实体是两条独立的计数线：同样的日期，采购与付款互不影响
    var counters = sequences(token, null);
    var byEntity = new java.util.TreeMap<String, Integer>();
    for (JsonNode c : counters) byEntity.merge(c.get("entityCode").asText(), 1, Integer::sum);
    assertThat(byEntity).containsKeys("PURCHASE_ORDER", "PAYMENT");
    assertThat(byEntity.get("PURCHASE_ORDER")).isEqualTo(2);
    assertThat(byEntity.get("PAYMENT")).isEqualTo(2);
  }

  @Test
  void neverResetRuleKeepsCountingAcrossDays() throws Exception {
    String token = login();
    UUID id =
        createRule(
            token,
            ruleInput(
                "RECEIPT",
                "收款不重置",
                "PRIMARY",
                List.of(prefix("RC"), sequence(5, 1, "NONE", "ENTITY"))));

    assertThat(issue(token, id, "2026-03-01T09:00:00Z").get("number").asText())
        .isEqualTo("RC00001");
    assertThat(issue(token, id, "2026-03-02T09:00:00Z").get("number").asText())
        .isEqualTo("RC00002");
    assertThat(issue(token, id, "2027-01-01T09:00:00Z").get("number").asText())
        .isEqualTo("RC00003");

    // 只有一个桶：NONE 的桶名固定是 ALL
    var counters = sequences(token, "RECEIPT");
    assertThat(counters).hasSize(1);
    assertThat(counters.get(0).get("resetKey").asText()).isEqualTo("ALL");
  }

  @Test
  void ruleScopedSequenceRestartsWhileEntityScopedContinuesAcrossRules() throws Exception {
    String token = login();
    UUID first =
        createRule(
            token,
            during(
                ruleInput(
                    "AFTERSALE",
                    "售后规则一",
                    "PRIMARY",
                    List.of(prefix("AS"), sequence(3, 1, "NONE", "ENTITY"))),
                "2020-01-01T00:00:00Z",
                "2029-01-01T00:00:00Z"));
    assertThat(issue(token, first, null).get("number").asText()).isEqualTo("AS001");

    // 第二条规则接在同一实体上（时间段不重叠所以配置得进去），ENTITY 作用域接着数
    var second =
        during(
            ruleInput(
                "AFTERSALE",
                "售后规则二",
                "PRIMARY",
                List.of(prefix("AS"), sequence(3, 1, "NONE", "ENTITY"))),
            "2030-01-01T00:00:00Z",
            "2031-01-01T00:00:00Z");
    UUID secondId = createRule(token, second);
    assertThat(issue(token, secondId, null).get("number").asText()).isEqualTo("AS002");

    // ENTITY 作用域下第一条规则接着数到 003
    assertThat(issue(token, first, null).get("number").asText()).isEqualTo("AS003");

    // 换成 RULE 作用域：计数作用域键从 ENTITY 变成 RULE:<该段所属规则 id>，
    // 新规则从 1 重新开始（前缀另取一个字母，免得与已发放的编号文本撞唯一约束——
    // 编号文本重复会怎样由 sameEntityRefusesToIssueTheSameNumberTwice 专门覆盖）。
    var third =
        during(
            ruleInput(
                "AFTERSALE",
                "售后规则三",
                "PRIMARY",
                List.of(prefix("B"), sequence(3, 1, "NONE", "RULE"))),
            "2031-01-01T00:00:00Z",
            null);
    UUID thirdId = createRule(token, third);
    assertThat(issue(token, thirdId, null).get("number").asText()).isEqualTo("B001");

    // 计数器上确实分成了两个作用域键
    var scopes = new java.util.TreeSet<String>();
    for (JsonNode c : sequences(token, "AFTERSALE")) scopes.add(c.get("scopeKey").asText());
    assertThat(scopes).hasSize(2);
    assertThat(scopes).contains("ENTITY");
    assertThat(scopes).anyMatch(s -> s.startsWith("RULE:"));
  }

  // ------------------------------------------------------------------
  // 预览不占号，且与取号一致
  // ------------------------------------------------------------------

  @Test
  void previewDoesNotConsumeSequenceAndAgreesWithTheRealNumber() throws Exception {
    String token = login();
    UUID id =
        createRule(
            token,
            ruleInput(
                "STOCK_TRANSFER",
                "调拨预览",
                "PRIMARY",
                List.of(prefix("TR"), date("yyyyMMdd"), sequence(4, 7, "DAILY", "ENTITY"))));
    var day = "2026-05-04T02:00:00Z";

    var input = new LinkedHashMap<String, Object>();
    input.put("ruleId", id.toString());
    input.put("documentDate", day);

    // 连预览三次，序号不得被推前：起始值 7，桶还不存在，所以每次都该是 0007
    for (int i = 0; i < 3; i++) {
      var preview = preview(token, input);
      assertThat(preview.get("number").asText()).as("第 %s 次预览", i).isEqualTo("TR202605040007");
      assertThat(preview.get("consumed").asBoolean()).isFalse();
    }
    // 预览没有写计数器
    assertThat(sequences(token, "STOCK_TRANSFER")).isEmpty();

    // 真正取号：仍是 0007，与预览一致——两个出口共用同一段渲染逻辑
    assertThat(issue(token, id, day).get("number").asText()).isEqualTo("TR202605040007");
    // 取过之后预览给出的"下一个"才变成 0008
    assertThat(preview(token, input).get("number").asText()).isEqualTo("TR202605040008");

    // 预览逐段明细能看清编号是怎么拼出来的
    var parts = preview(token, input).get("parts");
    assertThat(parts).hasSize(3);
    assertThat(parts.get(0).get("value").asText()).isEqualTo("TR");
    assertThat(parts.get(1).get("value").asText()).isEqualTo("20260504");
    assertThat(parts.get(2).get("value").asText()).isEqualTo("0008");
    assertThat(parts.get(0).get("depth").asInt()).isZero();

    // 取号计划把"取哪个桶"讲清楚
    var plan = preview(token, input).get("sequences").get(0);
    assertThat(plan.get("resetScope").asText()).isEqualTo("DAILY");
    assertThat(plan.get("resetKey").asText()).isEqualTo("2026-05-04");
    assertThat(plan.get("entityCode").asText()).isEqualTo("STOCK_TRANSFER");
    assertThat(plan.get("scopeKey").asText()).isEqualTo("ENTITY");
  }

  @Test
  void previewResolvesTheActiveRuleByEntityWhenNoRuleIdGiven() throws Exception {
    String token = login();
    var input =
        ruleInput(
            "STOCK_ADJUSTMENT",
            "调整标准编号",
            "PRIMARY",
            List.of(prefix("ADJ"), date("yyyyMMdd"), sequence(4, 1, "DAILY", "ENTITY")));
    createRule(token, input);

    var byEntity = new LinkedHashMap<String, Object>();
    byEntity.put("entityCode", "STOCK_ADJUSTMENT");
    byEntity.put("documentDate", "2026-06-01T01:00:00Z");
    var preview = preview(token, byEntity);
    assertThat(preview.get("number").asText()).isEqualTo("ADJ202606010001");
    assertThat(preview.get("ruleName").asText()).isEqualTo("调整标准编号");

    // 没有生效中的规则时报 404，而不是静默给一个空编号
    var missing = new LinkedHashMap<String, Object>();
    missing.put("entityCode", "CREDIT_NOTE");
    request(token, "POST", "/identity/numbering/preview", missing, 404);
    // 两者都不给时报 400
    request(token, "POST", "/identity/numbering/preview", Map.of(), 400);
  }

  // ------------------------------------------------------------------
  // 多级嵌套
  // ------------------------------------------------------------------

  @Test
  void nestedChildRuleExpandsIntoTheFinalNumber() throws Exception {
    String token = login();

    // 子规则：一段可复用的「日期 + 序号」片段
    UUID component =
        createRule(
            token,
            ruleInput(
                "ORDER",
                "通用日期序号片段",
                "COMPONENT",
                List.of(date("yyyyMMdd"), sequence(4, 1, "DAILY", "ENTITY"))));

    // 父规则：前缀 + 嵌套片段 + 后缀
    UUID parent =
        createRule(
            token,
            during(
                ruleInput(
                    "ORDER",
                    "销售订单嵌套编号",
                    "PRIMARY",
                    List.of(prefix("SO"), nested(component), suffix("-X"))),
                "2020-01-01T00:00:00Z",
                "2021-01-01T00:00:00Z"));

    var day = "2026-07-08T03:00:00Z";
    var input = new LinkedHashMap<String, Object>();
    input.put("ruleId", parent.toString());
    input.put("documentDate", day);

    // 预览必须真实反映嵌套后的最终编号：子规则被展开，不是当成一段死文本
    var preview = preview(token, input);
    assertThat(preview.get("number").asText()).isEqualTo("SO202607080001-X");

    // 逐段明细里，子规则的段带更深的 depth，能看出哪几段来自嵌套
    var parts = preview.get("parts");
    assertThat(parts).hasSize(4);
    // depth 0 = 父规则自己的段，depth 1 = 展开出来的子规则段
    assertThat(parts.get(0).get("depth").asInt()).isZero();
    assertThat(parts.get(0).get("value").asText()).isEqualTo("SO");
    assertThat(parts.get(1).get("depth").asInt()).isEqualTo(1);
    assertThat(parts.get(1).get("segmentType").asText()).isEqualTo("DATE");
    assertThat(parts.get(1).get("value").asText()).isEqualTo("20260708");
    assertThat(parts.get(2).get("depth").asInt()).isEqualTo(1);
    assertThat(parts.get(2).get("segmentType").asText()).isEqualTo("SEQUENCE");
    assertThat(parts.get(2).get("value").asText()).isEqualTo("0001");
    assertThat(parts.get(3).get("depth").asInt()).isZero();
    assertThat(parts.get(3).get("value").asText()).isEqualTo("-X");
    // 子规则的段带的是子规则自己的 id，配置页据此还原嵌套层次
    assertThat(parts.get(1).get("ruleId").asText()).isEqualTo(component.toString());
    assertThat(parts.get(3).get("ruleId").asText()).isEqualTo(parent.toString());

    // 取号与预览一致；子规则的序号段用的是**根实体**的桶
    assertThat(issue(token, parent, day).get("number").asText()).isEqualTo("SO202607080001-X");
    var counters = sequences(token, "ORDER");
    assertThat(counters).hasSize(1);
    assertThat(counters.get(0).get("entityCode").asText()).isEqualTo("ORDER");

    // 规则详情里 NESTED 段带出子规则名与展开后的文本。
    // 注意样例用的是「今天 + 当前计数器」（detail 不接受预览参数），所以不能硬编码具体日期，
    // 断言它是一段「日期+序号」并且真的拼进了样例编号即可。
    var detail = request(token, "GET", "/identity/numbering/rules/" + parent, null, 200);
    var nestedSegment = segment(detail, "NESTED");
    assertThat(nestedSegment.get("childRuleName").asText()).isEqualTo("通用日期序号片段");
    var expanded = nestedSegment.get("rendered").asText();
    assertThat(expanded).matches("\\d{8}\\d{4}");
    assertThat(detail.get("sampleNumber").asText()).isEqualTo("SO" + expanded + "-X");
  }

  @Test
  void nestingThreeLevelsDeepStillRendersTheFinalNumber() throws Exception {
    String token = login();
    UUID innermost =
        createRule(
            token,
            ruleInput("ORDER", "最内层片段", "COMPONENT", List.of(sequence(4, 1, "DAILY", "ENTITY"))));
    UUID middle =
        createRule(
            token,
            ruleInput("ORDER", "中间层片段", "COMPONENT", List.of(date("yyyyMMdd"), nested(innermost))));
    UUID outer =
        createRule(
            token,
            during(
                ruleInput(
                    "ORDER", "最外层编号", "PRIMARY", List.of(prefix("L"), nested(middle), suffix("R"))),
                "2020-01-01T00:00:00Z",
                "2021-01-01T00:00:00Z"));

    var input = new LinkedHashMap<String, Object>();
    input.put("ruleId", outer.toString());
    input.put("documentDate", "2026-08-09T04:00:00Z");
    assertThat(preview(token, input).get("number").asText()).isEqualTo("L202608090001R");
    assertThat(issue(token, outer, "2026-08-09T04:00:00Z").get("number").asText())
        .isEqualTo("L202608090001R");
  }

  @Test
  void nestedRuleCountsAgainstTheRootEntityNotItsOwn() throws Exception {
    String token = login();

    // 同一个子规则被两个不同实体的主规则嵌套
    UUID component =
        createRule(
            token,
            ruleInput(
                "ORDER",
                "共享序号片段",
                "COMPONENT",
                List.of(prefix("S"), sequence(4, 1, "NONE", "ENTITY"))));
    UUID forOrder =
        createRule(
            token,
            during(
                ruleInput("ORDER", "订单用共享片段", "PRIMARY", List.of(nested(component))),
                "2020-01-01T00:00:00Z",
                "2021-01-01T00:00:00Z"));
    UUID forPurchase =
        createRule(
            token,
            during(
                ruleInput("PURCHASE_ORDER", "采购用共享片段", "PRIMARY", List.of(nested(component))),
                "2020-01-01T00:00:00Z",
                "2021-01-01T00:00:00Z"));

    var day = "2026-09-10T05:00:00Z";
    // 两个实体各自独立计数：同样是 0001，不会互相把对方的序号推前
    assertThat(issue(token, forOrder, day).get("number").asText()).isEqualTo("S0001");
    assertThat(issue(token, forPurchase, day).get("number").asText()).isEqualTo("S0001");
    assertThat(issue(token, forOrder, day).get("number").asText()).isEqualTo("S0002");

    var counters = sequences(token, null);
    var byEntity = new java.util.TreeMap<String, String>();
    for (JsonNode c : counters)
      byEntity.put(c.get("entityCode").asText(), c.get("currentValue").toString());
    assertThat(byEntity).containsEntry("ORDER", "2");
    assertThat(byEntity).containsEntry("PURCHASE_ORDER", "1");
  }

  @Test
  void nestingCycleAndSelfReferenceAreRejectedAtConfigurationTime() throws Exception {
    String token = login();

    // 直接自嵌
    UUID id = createRule(token, ruleInput("ORDER", "自嵌宿主", "COMPONENT", List.of(prefix("H"))));
    request(
        token,
        "PUT",
        "/identity/numbering/rules/" + id,
        ruleInput("ORDER", "自嵌规则", "COMPONENT", List.of(nested(id))),
        400);

    // 间接成环：A 嵌 B，B 再嵌 A
    UUID a = createRule(token, ruleInput("ORDER", "环A", "COMPONENT", List.of(prefix("A"))));
    UUID b = createRule(token, ruleInput("ORDER", "环B", "COMPONENT", List.of(nested(a))));
    // 让 A 嵌上 B 就成环了 → 写入时就得拒
    request(
        token,
        "PUT",
        "/identity/numbering/rules/" + a,
        ruleInput("ORDER", "环A", "COMPONENT", List.of(nested(b))),
        400);
    // A 仍是单段规则，B 也仍是嵌 A，环没有真的写进去
    assertThat(
            segment(request(token, "GET", "/identity/numbering/rules/" + a, null, 200), "NESTED"))
        .isNull();
    assertThat(
            segment(request(token, "GET", "/identity/numbering/rules/" + b, null, 200), "NESTED"))
        .isNotNull();

    // 只能嵌子规则，不能嵌另一条主规则
    UUID primary =
        createRule(
            token,
            during(
                ruleInput("ORDER", "一条主规则", "PRIMARY", List.of(prefix("P"))),
                "2020-01-01T00:00:00Z",
                "2021-01-01T00:00:00Z"));
    request(
        token,
        "PUT",
        "/identity/numbering/rules/" + a,
        ruleInput("ORDER", "环A", "COMPONENT", List.of(nested(primary))),
        400);
    // 嵌一个不存在的规则同样被拒
    request(
        token,
        "PUT",
        "/identity/numbering/rules/" + a,
        ruleInput("ORDER", "环A", "COMPONENT", List.of(nested(UUID.randomUUID()))),
        404);
  }

  @Test
  void deletedChildRuleIsRefusedSoNestingNeverDangles() throws Exception {
    String token = login();
    UUID component =
        createRule(
            token,
            ruleInput(
                "ORDER",
                "待删除片段",
                "COMPONENT",
                List.of(prefix("C"), sequence(2, 1, "NONE", "ENTITY"))));
    UUID parent =
        createRule(
            token,
            during(
                ruleInput("ORDER", "引用片段的主规则", "PRIMARY", List.of(nested(component))),
                "2020-01-01T00:00:00Z",
                "2021-01-01T00:00:00Z"));

    // 被别的规则引用时删不掉
    request(token, "DELETE", "/identity/numbering/rules/" + component, null, 409);
    // 主规则本身没被引用也没发过号，可以删
    request(token, "DELETE", "/identity/numbering/rules/" + parent, null, 200);
    // 删掉之后子规则就能删了
    request(token, "DELETE", "/identity/numbering/rules/" + component, null, 200);
  }

  // ------------------------------------------------------------------
  // 规则冲突：同一实体 + 生效区间交叠
  // ------------------------------------------------------------------

  @Test
  void overlappingEffectiveIntervalsOnTheSameEntityAreRejectedAtConfigurationTime()
      throws Exception {
    String token = login();
    var segments = List.of(prefix("X"), sequence(4, 1, "NONE", "ENTITY"));

    var base = ruleInput("RETURN_ORDER", "退货规则基线", "PRIMARY", segments);
    base.put("effectiveFrom", "2026-01-01T00:00:00Z");
    base.put("effectiveTo", "2026-07-01T00:00:00Z");
    createRule(token, base);

    // 与基线完全重叠 → 409
    var overlap = ruleInput("RETURN_ORDER", "完全重叠", "PRIMARY", segments);
    overlap.put("effectiveFrom", "2026-03-01T00:00:00Z");
    overlap.put("effectiveTo", "2026-04-01T00:00:00Z");
    request(token, "POST", "/identity/numbering/rules", overlap, 409);

    // 一头压住基线的起点 → 409
    var headOverlap = ruleInput("RETURN_ORDER", "压住起点", "PRIMARY", segments);
    headOverlap.put("effectiveFrom", "2025-12-01T00:00:00Z");
    headOverlap.put("effectiveTo", "2026-02-01T00:00:00Z");
    request(token, "POST", "/identity/numbering/rules", headOverlap, 409);

    // 基线之后接一条（前一条的 to 等于后一条的 from）：相邻不算交叠，配置得进去
    var adjacent = ruleInput("RETURN_ORDER", "接续规则", "PRIMARY", segments);
    adjacent.put("effectiveFrom", "2026-07-01T00:00:00Z");
    adjacent.put("effectiveTo", "2027-01-01T00:00:00Z");
    UUID adjacentId = createRule(token, adjacent);

    // 不同实体：各自独立，冲突判定不会跨实体误伤
    var other = ruleInput("CREDIT_NOTE", "贷项编号", "PRIMARY", segments);
    other.put("effectiveFrom", "2026-01-01T00:00:00Z");
    other.put("effectiveTo", "2026-07-01T00:00:00Z");
    createRule(token, other);

    // 停用的规则不参与判定：把接续规则停掉后，同区间可以再配一条
    var disabled = new LinkedHashMap<String, Object>(adjacent);
    disabled.put("status", "DISABLED");
    request(token, "PUT", "/identity/numbering/rules/" + adjacentId, disabled, 200);
    var afterDisable = ruleInput("RETURN_ORDER", "停用后新增", "PRIMARY", segments);
    afterDisable.put("effectiveFrom", "2026-07-01T00:00:00Z");
    afterDisable.put("effectiveTo", "2027-01-01T00:00:00Z");
    createRule(token, afterDisable);

    // 冲突检测出口：报出来的 overlaps 与写入判定同源
    var conflicts =
        request(
            token, "GET", "/identity/numbering/rules/conflicts?entityCode=RETURN_ORDER", null, 200);
    assertThat(conflicts).isNotEmpty();
    boolean anyHit = false;
    for (JsonNode c : conflicts) if (c.get("overlaps").asBoolean()) anyHit = true;
    assertThat(anyHit).as("冲突检测必须能报出交叠").isTrue();
  }

  @Test
  void updatingARuleDoesNotConflictWithItselfAndRejectsReversedInterval() throws Exception {
    String token = login();
    var input =
        ruleInput(
            "INBOUND", "入库编号", "PRIMARY", List.of(prefix("IN"), sequence(4, 1, "NONE", "ENTITY")));
    input.put("effectiveFrom", "2026-01-01T00:00:00Z");
    UUID id = createRule(token, input);

    // 改自己不该被自己的生效区间挡住（排除自身）
    var changed = new LinkedHashMap<String, Object>(input);
    changed.put("ruleName", "入库编号改名");
    request(token, "PUT", "/identity/numbering/rules/" + id, changed, 200);
    assertThat(findRule(rules(token), "入库编号改名")).isNotNull();

    // 生效区间倒置被拒
    var reversed = new LinkedHashMap<String, Object>(changed);
    reversed.put("effectiveTo", "2025-01-01T00:00:00Z");
    request(token, "PUT", "/identity/numbering/rules/" + id, reversed, 400);
  }

  // ------------------------------------------------------------------
  // 编号是可读标识，不是外键
  // ------------------------------------------------------------------

  @Test
  void issuedNumberCarriesReadableDocumentReferenceWithoutForeignKey() throws Exception {
    String token = login();
    UUID id =
        createRule(
            token,
            ruleInput(
                "AFTERSALE",
                "售后编号",
                "PRIMARY",
                List.of(prefix("AS"), date("yyyyMMdd"), sequence(4, 1, "DAILY", "ENTITY"))));

    var input = new LinkedHashMap<String, Object>();
    input.put("documentDate", "2026-04-15T01:00:00Z");
    input.put("documentType", "AFTERSALE_REQUEST");
    // 随便一个单据号：它是可读标识，不是外键，库里查不到对应业务行也照样能发
    input.put("documentNo", "AS-EXT-9001");
    var issued = request(token, "POST", "/identity/numbering/rules/" + id + "/next", input, 200);

    assertThat(issued.get("number").asText()).isEqualTo("AS202604150001");
    assertThat(issued.get("documentType").asText()).isEqualTo("AFTERSALE_REQUEST");
    assertThat(issued.get("documentNo").asText()).isEqualTo("AS-EXT-9001");
    assertThat(issued.get("resetKey").asText()).isEqualTo("2026-04-15");

    // issued_at 真的写进去了，且落在请求前后的墙钟之间（不是默认值也不是偏移 8 小时）
    var before = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
    var after = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1);
    var issuedAt = OffsetDateTime.parse(issued.get("issuedAt").asText());
    assertThat(issuedAt.toInstant()).isBetween(before.toInstant(), after.toInstant());

    // 读回来能看到它
    var list = request(token, "GET", "/identity/numbering/issued?entityCode=AFTERSALE", null, 200);
    assertThat(list).hasSize(1);
    assertThat(list.get(0).get("number").asText()).isEqualTo("AS202604150001");

    // 已发放记录不可改：触发器与 REVOKE 双重防线
    try (var c =
            DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var s = c.createStatement()) {
      var ex =
          assertThrows(
              java.sql.SQLException.class,
              () -> s.execute("update document_number_issued set number_text='X'"));
      assertThat(ex.getMessage()).contains("不可修改");
    }
  }

  @Test
  void sameEntityRefusesToIssueTheSameNumberTwice() throws Exception {
    String token = login();
    // 两条不冲突的规则（按 RULE 作用域，各自独立计数），却会渲染出同一个编号文本
    var first =
        during(
            ruleInput(
                "CREDIT_NOTE",
                "贷项规则一",
                "PRIMARY",
                List.of(prefix("CN"), sequence(4, 1, "NONE", "RULE"))),
            "2020-01-01T00:00:00Z",
            "2029-01-01T00:00:00Z");
    UUID firstId = createRule(token, first);
    var second =
        during(
            ruleInput(
                "CREDIT_NOTE",
                "贷项规则二",
                "PRIMARY",
                List.of(prefix("CN"), sequence(4, 1, "NONE", "RULE"))),
            "2030-01-01T00:00:00Z",
            null);
    UUID secondId = createRule(token, second);

    assertThat(issue(token, firstId, null).get("number").asText()).isEqualTo("CN0001");
    // 第二条规则也从头数，渲染出同样的编号：唯一约束必须挡住，409 而不是发出去两个一样的号
    request(
        token,
        "POST",
        "/identity/numbering/rules/" + secondId + "/next",
        Map.of("documentDate", "2030-06-01T00:00:00Z"),
        409);

    // 已发放过的规则不能删（编号的来源要查得到）
    request(token, "DELETE", "/identity/numbering/rules/" + firstId, null, 400);
  }

  // ------------------------------------------------------------------
  // 权限
  // ------------------------------------------------------------------

  @Test
  void readsNeedNumberingReadAndWritesAreOwnerOnly() throws Exception {
    String owner = login(), tag = suffix();

    UUID id =
        createRule(
            owner,
            ruleInput(
                "PAYMENT",
                "权限测试编号",
                "PRIMARY",
                List.of(prefix("PM"), sequence(4, 1, "NONE", "ENTITY"))));

    // 造一个只有成员读权限的角色：它对编号规则一无所知
    UUID role =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/roles",
                    Map.of(
                        "code",
                        "NONUM_" + tag,
                        "name",
                        "无编号规则权限",
                        "status",
                        "ACTIVE",
                        "permissions",
                        List.of("identity.member.read")),
                    200)
                .asText());
    request(
        owner,
        "POST",
        "/identity/members",
        memberInput(
            "num-viewer-" + tag.toLowerCase(),
            null,
            List.of(Map.of("roleId", role, "scopeMode", "ALL"))),
        200);
    String viewer = loginUser("num-viewer-" + tag.toLowerCase());

    // 无编号规则权限：读与写都不放行
    request(viewer, "GET", "/identity/numbering/rules", null, 403);
    request(viewer, "POST", "/identity/numbering/preview", Map.of("ruleId", id.toString()), 403);
    request(viewer, "POST", "/identity/numbering/rules/" + id + "/next", null, 403);

    // 所有者可读可写
    assertThat(rules(owner)).isNotEmpty();
    assertThat(request(owner, "GET", "/identity/numbering/entities", null, 200)).isNotEmpty();
  }

  @Test
  void malformedSegmentsAreRejectedWithReadableErrors() throws Exception {
    String token = login();

    // 缺文本的前缀段
    request(
        token,
        "POST",
        "/identity/numbering/rules",
        ruleInput("ORDER", "坏前缀", "PRIMARY", List.of(Map.of("segmentType", "PREFIX"))),
        400);
    // 未实现的日期格式
    request(
        token,
        "POST",
        "/identity/numbering/rules",
        ruleInput(
            "ORDER",
            "坏日期",
            "PRIMARY",
            List.of(prefix("A"), Map.of("segmentType", "DATE", "dateFormat", "yyyy/MM/dd"))),
        400);
    // 序号段缺重置口径
    var noReset = new LinkedHashMap<String, Object>();
    noReset.put("segmentType", "SEQUENCE");
    noReset.put("sequenceWidth", 4);
    noReset.put("sequenceStart", 1);
    request(
        token,
        "POST",
        "/identity/numbering/rules",
        ruleInput("ORDER", "坏序号", "PRIMARY", List.of(noReset)),
        400);
    // 不存在的实体
    request(
        token,
        "POST",
        "/identity/numbering/rules",
        ruleInput("NOT_AN_ENTITY", "坏实体", "PRIMARY", List.of(prefix("A"))),
        400);
    // 时区非法
    var badZone = ruleInput("ORDER", "坏时区", "PRIMARY", List.of(prefix("A")));
    badZone.put("timezone", "Mars/Olympus");
    request(token, "POST", "/identity/numbering/rules", badZone, 400);
    // 空的段列表
    var noSegments = ruleInput("ORDER", "空规则", "PRIMARY", new ArrayList<Map<String, Object>>());
    request(token, "POST", "/identity/numbering/rules", noSegments, 400);
    // 跨租户 / 不存在的 id 一律 404
    request(token, "GET", "/identity/numbering/rules/" + UUID.randomUUID(), null, 404);
  }

  // ------------------------------------------------------------------
  // 多租户三断言
  // ------------------------------------------------------------------

  @Test
  void numberingRulesAreTenantIsolatedAndHeaderForgeryIsIgnored() throws Exception {
    String owner = login(), tag = suffix();

    // 父租户：ORDER 规则 + 一个已发放编号
    UUID parentRule =
        createRule(
            owner,
            ruleInput(
                "ORDER",
                "父租户订单编号",
                "PRIMARY",
                List.of(prefix("PO"), date("yyyyMMdd"), sequence(4, 1, "DAILY", "ENTITY"))));
    var parentIssue = new LinkedHashMap<String, Object>();
    parentIssue.put("documentDate", "2026-02-02T01:00:00Z");
    parentIssue.put("documentNo", "PARENT-" + tag);
    request(owner, "POST", "/identity/numbering/rules/" + parentRule + "/next", parentIssue, 200);

    // 子租户：一套完全不同的规则与编号
    String childUser = "num-child-" + tag.toLowerCase();
    request(
        owner,
        "POST",
        "/identity/children",
        Map.of(
            "code",
            "NUMC" + tag,
            "name",
            "编号规则子租户",
            "ownerUsername",
            childUser,
            "ownerPassword",
            "test-member-password"),
        200);
    String childToken = loginUser(childUser);
    UUID childRule =
        createRule(
            childToken,
            ruleInput(
                "ORDER",
                "子租户订单编号",
                "PRIMARY",
                List.of(prefix("CO"), date("yyyyMMdd"), sequence(4, 1, "DAILY", "ENTITY"))));
    var childIssue = new LinkedHashMap<String, Object>();
    childIssue.put("documentDate", "2026-02-02T01:00:00Z");
    childIssue.put("documentNo", "CHILD-" + tag);
    request(
        childToken, "POST", "/identity/numbering/rules/" + childRule + "/next", childIssue, 200);

    // 断言一：两租户互不可见
    var parentView = rules(owner);
    assertThat(findRule(parentView, "父租户订单编号")).isNotNull();
    assertThat(findRule(parentView, "子租户订单编号")).isNull();
    assertThat(parentView.toString()).doesNotContain("CO2026");

    var childView = rules(childToken);
    assertThat(findRule(childView, "子租户订单编号")).isNotNull();
    assertThat(findRule(childView, "父租户订单编号")).isNull();

    // 已发放编号、计数器、历史同样隔离
    assertThat(request(owner, "GET", "/identity/numbering/issued", null, 200).toString())
        .contains("PARENT-" + tag)
        .doesNotContain("CHILD-" + tag);
    assertThat(request(childToken, "GET", "/identity/numbering/issued", null, 200).toString())
        .contains("CHILD-" + tag)
        .doesNotContain("PARENT-" + tag);
    assertThat(sequences(owner, "ORDER").get(0).get("currentValue").toString()).isEqualTo("1");
    assertThat(sequences(childToken, "ORDER").get(0).get("currentValue").toString()).isEqualTo("1");

    // 拿子租户的规则 id 去父租户读 / 改 / 删 / 取号一律 404
    request(owner, "GET", "/identity/numbering/rules/" + childRule, null, 404);
    request(
        owner,
        "PUT",
        "/identity/numbering/rules/" + childRule,
        ruleInput("ORDER", "父租户改名", "PRIMARY", List.of(prefix("X"))),
        404);
    request(owner, "DELETE", "/identity/numbering/rules/" + childRule, null, 404);
    request(owner, "POST", "/identity/numbering/rules/" + childRule + "/next", null, 404);

    // 父租户按子租户的规则预览：404 而不是悄悄用自己的规则渲染
    request(
        owner, "POST", "/identity/numbering/preview", Map.of("ruleId", childRule.toString()), 404);

    // 断言二：伪造 tenant header 无效。request() 每次都带随机 X-Tenant-Id。
    String forged = UUID.randomUUID().toString();
    var forgedView =
        http.perform(
                get("/identity/numbering/rules")
                    .header("Authorization", "Bearer " + owner)
                    .header("X-Tenant-Id", forged))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forgedView).contains("父租户订单编号").doesNotContain("子租户订单编号");
    // 伪造成子租户的真实租户 id（调用方本来就是它的所有者）：结果仍然不变
    var forgedChild =
        http.perform(
                get("/identity/numbering/rules")
                    .header("Authorization", "Bearer " + owner)
                    .header("X-Tenant-Id", tenantOf(childToken)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forgedChild).contains("父租户订单编号").doesNotContain("子租户订单编号");
    // 未登录读不到任何规则
    http.perform(get("/identity/numbering/rules")).andExpect(status().isUnauthorized());

    // 断言三：连接池归还后不串租户。池大小被压到 1，交替租户请求复用同一物理连接。
    for (int i = 0; i < 3; i++) {
      var parent = rules(owner);
      assertThat(findRule(parent, "父租户订单编号")).as("第 %s 轮父租户不得看到子租户规则", i).isNotNull();
      assertThat(parent.toString()).doesNotContain("子租户订单编号");
      var child = rules(childToken);
      assertThat(findRule(child, "子租户订单编号")).as("第 %s 轮子租户不得看到父租户规则", i).isNotNull();
      assertThat(child.toString()).doesNotContain("父租户订单编号");
      // 计数器也必须跟着租户走
      assertThat(sequences(owner, "ORDER").get(0).get("currentValue").toString()).isEqualTo("1");
      assertThat(sequences(childToken, "ORDER").get(0).get("currentValue").toString())
          .isEqualTo("1");
    }

    // 事务外（无租户上下文）直查应当为空
    assertThat(sql.fetch("select id from document_number_rule")).isEmpty();
    assertThat(sql.fetch("select id from document_number_issued")).isEmpty();
    assertThat(tx().run(tenantOf(owner), () -> sql.fetch("select id from document_number_rule")))
        .isNotEmpty();
  }

  private UUID tenantOf(String token) throws Exception {
    return UUID.fromString(
        request(token, "GET", "/identity/tenants/current", null, 200).get("tenantId").asText());
  }
}
