package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 17.4.1.2 审批规则（触发规则 + 免审规则）。
 *
 * <p>蓝图对本模块点名的东西逐条对应到断言上：
 *
 * <ol>
 *   <li><b>金额区间、跨法人、跨部门、新供应商、高风险商品五类触发条件</b>——每一条都要能真的配出来， 并通过 {@code /evaluate} 试算证明「命中的是它、没命中别的」。
 *   <li><b>免审要设上限金额</b>——免审没有上限必须 400；上限为 0 也必须 400。
 *   <li><b>免审不得被用来绕过大额管控</b>——免审规则把任一高风险条件标成 REQUIRED 必须 400 （这是绕过大额管控的第二种走法，光卡上限金额不够）。
 *   <li><b>先命中先生效</b>——priority 唯一、按 priority 升序判定；一条都没命中时走规则集的生效策略。
 *   <li><b>输出「规则集 + 生效策略」</b>——{@code /effective-policy} 回答「这个单据类型现在受不受管控、 有哪些免审额度」；没配就是 {@code
 *       controlled=false}，即默认放行。
 * </ol>
 *
 * <p>另外实跑：金额 BigDecimal 与时间 {@code timestamptz} 的<b>写回值</b>断言、版本随保存 +1、
 * 规则指向别的单据类型的流程必须拒、结构体检、审计留痕，以及多租户三断言 （两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户）。
 *
 * <p>本模块刻意<b>不</b>覆盖：发起审批、审批实例、待办列表 —— 那是 17.4.2.1 的运行时。 {@code /evaluate} 只对已保存配置做只读试算，不产生任何审批实例。
 */
class ApprovalRuleIntegrationTest extends IdentityIntegrationTestBase {

  @BeforeEach
  void resetModule() throws Exception {
    try (var c =
            DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var s = c.createStatement()) {
      // 本模块的用例要为每个单据类型准备一条「生效」流程，而 approval_flow 上有
      // UNIQUE(tenant_id, document_type_code) WHERE status='ACTIVE' —— 不清就会撞唯一索引。
      // 规则表必须与流程表同批清空（approval_trigger_rule.target_flow_id 外键指向 approval_flow）。
      // approval_instance / approval_instance_action（17.4.2.1 待我审批）同样引用 approval_flow，
      // 所以也在这批里 —— 否则本类与 ApprovalFlowIntegrationTest 的 TRUNCATE 都会被外键拒绝。
      s.execute(
          "truncate approval_flow_escalation, approval_flow_amount_branch,"
              + " approval_flow_node, approval_trigger_rule, approval_rule_set,"
              + " approval_instance_action, approval_instance, approval_flow");
    }
  }

  // ------------------------------------------------------------------
  // 请求与数据辅助
  // ------------------------------------------------------------------

  private String tag;

  /**
   * 一个用例里可能要给多个单据类型各建一条流程，角色编码必须逐次唯一， 否则第二次 POST /identity/roles 会撞 UNIQUE(tenant_id, code) 得到 409。
   * role 的 code 允许 {@code [A-Z][A-Z0-9_-]{0,63}}，追加序号后仍合法。
   */
  private int roleSeq;

  private UUID roleId(String token, String code) throws Exception {
    String unique = code + "-" + (roleSeq++);
    return UUID.fromString(
        request(
                token,
                "POST",
                "/identity/roles",
                Map.of(
                    "code",
                    unique,
                    "name",
                    "审批角色 " + unique,
                    "status",
                    "ACTIVE",
                    "permissions",
                    List.of("identity.approval_flow.read")),
                200)
            .asText());
  }

  /**
   * 先在 17.4.1.1 注册一条<b>生效</b>审批流。
   *
   * <p>本模块的送审规则必须指向一条已存在的生效流程，所以每个需要送审的用例都得先有它 —— 这正是「先注册流程、再配规则」这条写入顺序约束的体现。
   */
  private UUID activeFlow(String token, String documentType, String name) throws Exception {
    var role = roleId(token, "AR" + tag);
    var node = new LinkedHashMap<String, Object>();
    node.put("nodeKey", "R1");
    node.put("nodeName", "审批节点");
    node.put("nodeOrder", 0);
    node.put("stageIndex", 0);
    node.put("approverType", "ROLE");
    node.put("roleId", role);
    node.put("approvalMode", "SERIAL");
    var flow = new LinkedHashMap<String, Object>();
    flow.put("documentTypeCode", documentType);
    flow.put("flowName", name);
    flow.put("status", "ACTIVE");
    flow.put("description", "供 17.4.1.2 规则指向的流程");
    flow.put("nodes", List.of(node));
    flow.put("branches", List.of());
    flow.put("escalations", List.of());
    return UUID.fromString(request(token, "POST", "/identity/approval-flows", flow, 200).asText());
  }

  /** 规则：默认四维不约束、送审、指向给定流程。 */
  private Map<String, Object> rule(String name, int priority, UUID flow) {
    var r = new LinkedHashMap<String, Object>();
    r.put("ruleName", name);
    r.put("priority", priority);
    r.put("crossLegalEntity", "ANY");
    r.put("crossDepartment", "ANY");
    r.put("newVendor", "ANY");
    r.put("highRiskProduct", "ANY");
    r.put("decision", "SUBMIT_FOR_APPROVAL");
    r.put("targetFlowId", flow);
    r.put("status", "ACTIVE");
    return r;
  }

  /** 免审规则：必须给币种与上限金额（蓝图「免审要设上限金额」）。 */
  private Map<String, Object> exempt(String name, int priority, String currency, String max) {
    var r = new LinkedHashMap<String, Object>();
    r.put("ruleName", name);
    r.put("priority", priority);
    r.put("amountCurrencyCode", currency);
    if (max != null) r.put("amountMaxAmount", new BigDecimal(max));
    r.put("crossLegalEntity", "ANY");
    r.put("crossDepartment", "ANY");
    r.put("newVendor", "ANY");
    r.put("highRiskProduct", "ANY");
    r.put("decision", "EXEMPT");
    r.put("status", "ACTIVE");
    return r;
  }

  private Map<String, Object> ruleSet(
      String documentType, String name, String status, String defaultAction, List<?> rules) {
    var s = new LinkedHashMap<String, Object>();
    s.put("documentTypeCode", documentType);
    s.put("ruleSetName", name);
    s.put("status", status);
    s.put("defaultAction", defaultAction);
    s.put("description", "17.4.1.2 测试规则集");
    s.put("rules", rules);
    return s;
  }

  private UUID createSet(
      String token, String documentType, String name, String defaultAction, List<?> rules)
      throws Exception {
    return UUID.fromString(
        request(
                token,
                "POST",
                "/identity/approval-rules",
                ruleSet(documentType, name, "ACTIVE", defaultAction, rules),
                200)
            .asText());
  }

  private JsonNode evaluate(
      String token, String documentType, String currency, String amount, Map<String, Object> flags)
      throws Exception {
    var body = new LinkedHashMap<String, Object>();
    body.put("documentTypeCode", documentType);
    if (currency != null) body.put("currencyCode", currency);
    if (amount != null) body.put("amount", new BigDecimal(amount));
    body.putAll(flags);
    return request(token, "POST", "/identity/approval-rules/evaluate", body, 200);
  }

  private JsonNode detail(String token, UUID id) throws Exception {
    return request(token, "GET", "/identity/approval-rules/" + id, null, 200);
  }

  private JsonNode ruleNamed(JsonNode rules, String name) {
    for (JsonNode r : rules) if (name.equals(r.get("ruleName").asText())) return r;
    return null;
  }

  // ------------------------------------------------------------------
  // 五类触发条件
  // ------------------------------------------------------------------

  @Test
  void 五类触发条件都能配出规则且试算命中正确() throws Exception {
    var token = login();
    tag = suffix();
    UUID flow = activeFlow(token, "PURCHASE_ORDER", "采购审批流 " + tag);

    var byCrossEntity = rule("跨法人必审 " + tag, 1, flow);
    byCrossEntity.put("crossLegalEntity", "REQUIRED");
    var byCrossDept = rule("跨部门必审 " + tag, 2, flow);
    byCrossDept.put("crossDepartment", "REQUIRED");
    var byNewVendor = rule("新供应商必审 " + tag, 3, flow);
    byNewVendor.put("newVendor", "REQUIRED");
    var byRisk = rule("高风险商品必审 " + tag, 4, flow);
    byRisk.put("highRiskProduct", "REQUIRED");
    var byAmount = rule("大额必审 " + tag, 5, flow);
    byAmount.put("amountCurrencyCode", "USD");
    byAmount.put("amountMinAmount", new BigDecimal("5000"));

    UUID id =
        createSet(
            token,
            "PURCHASE_ORDER",
            "采购触发规则 " + tag,
            "SUBMIT_FOR_APPROVAL",
            List.of(byCrossEntity, byCrossDept, byNewVendor, byRisk, byAmount));

    var rules = detail(token, id).get("rules");
    assertThat(rules).hasSize(5);
    // 三态写回：填了 REQUIRED 的那维是 REQUIRED，其余保持 ANY（不约束），不被压成 false。
    assertThat(ruleNamed(rules, "跨法人必审 " + tag).get("crossLegalEntity").asText())
        .isEqualTo("REQUIRED");
    assertThat(ruleNamed(rules, "跨法人必审 " + tag).get("crossDepartment").asText()).isEqualTo("ANY");
    assertThat(ruleNamed(rules, "大额必审 " + tag).get("amountCurrencyCode").asText()).isEqualTo("USD");

    // 每条条件都真的能命中，且只命中它自己那件事。
    assertThat(
            evaluate(token, "PURCHASE_ORDER", "USD", "100", Map.of("crossLegalEntity", true))
                .get("matchedRuleName")
                .asText())
        .isEqualTo("跨法人必审 " + tag);
    assertThat(
            evaluate(token, "PURCHASE_ORDER", "USD", "100", Map.of("crossDepartment", true))
                .get("matchedRuleName")
                .asText())
        .isEqualTo("跨部门必审 " + tag);
    assertThat(
            evaluate(token, "PURCHASE_ORDER", "USD", "100", Map.of("newVendor", true))
                .get("matchedRuleName")
                .asText())
        .isEqualTo("新供应商必审 " + tag);
    assertThat(
            evaluate(token, "PURCHASE_ORDER", "USD", "100", Map.of("highRiskProduct", true))
                .get("matchedRuleName")
                .asText())
        .isEqualTo("高风险商品必审 " + tag);
    var big = evaluate(token, "PURCHASE_ORDER", "USD", "8000", Map.of());
    assertThat(big.get("matchedRuleName").asText()).isEqualTo("大额必审 " + tag);
    assertThat(big.get("decision").asText()).isEqualTo("SUBMIT_FOR_APPROVAL");
    assertThat(big.get("targetFlowId").asText()).isEqualTo(flow.toString());

    // 「不约束」不是「必须不满足」：一笔不跨法人也不跨部门的普通小额提交，
    // 不该被前面任何一条规则命中，而落到生效策略。
    var plain = evaluate(token, "PURCHASE_ORDER", "USD", "100", Map.of());
    assertThat(plain.get("source").asText()).isEqualTo("DEFAULT");
    assertThat(plain.get("matchedRuleId").isNull()).isTrue();
    assertThat(plain.get("decision").asText()).isEqualTo("SUBMIT_FOR_APPROVAL");
  }

  @Test
  void 三态的FORBIDDEN是必须不满足而不是不约束() throws Exception {
    var token = login();
    tag = suffix();
    UUID flow = activeFlow(token, "STOCK_ADJUSTMENT", "调整审批流 " + tag);

    // FORBIDDEN：只有「不跨法人」的提交才命中。ANY 则是两种都命中。
    var forbid = rule("仅不跨法人时送审 " + tag, 1, flow);
    forbid.put("crossLegalEntity", "FORBIDDEN");
    var any = rule("任何情况都送审 " + tag, 2, flow);
    any.put("crossLegalEntity", "ANY");

    createSet(
        token, "STOCK_ADJUSTMENT", "三态规则 " + tag, "SUBMIT_FOR_APPROVAL", List.of(forbid, any));

    assertThat(
            evaluate(token, "STOCK_ADJUSTMENT", "USD", "50", Map.of("crossLegalEntity", false))
                .get("matchedRuleName")
                .asText())
        .isEqualTo("仅不跨法人时送审 " + tag);
    // 跨法人的提交跳过 FORBIDDEN 那条，落到 ANY 那条 —— 证明 FORBIDDEN 真的是「要求不成立」。
    assertThat(
            evaluate(token, "STOCK_ADJUSTMENT", "USD", "50", Map.of("crossLegalEntity", true))
                .get("matchedRuleName")
                .asText())
        .isEqualTo("任何情况都送审 " + tag);
  }

  // ------------------------------------------------------------------
  // 免审：蓝图那句「免审要设上限金额」
  // ------------------------------------------------------------------

  @Test
  void 免审必须设上限金额否则被拒() throws Exception {
    var token = login();
    tag = suffix();
    UUID flow = activeFlow(token, "REFUND", "退款审批流 " + tag);

    var noCeiling = exempt("无上限免审 " + tag, 1, "USD", null);
    noCeiling.remove("amountMaxAmount");
    request(
        token,
        "POST",
        "/identity/approval-rules",
        ruleSet(
            "REFUND",
            "无上限规则集 " + tag,
            "ACTIVE",
            "SUBMIT_FOR_APPROVAL",
            List.of(noCeiling, rule("大额送审 " + tag, 2, flow))),
        400);

    var zeroCeiling = exempt("零额度免审 " + tag, 1, "USD", "0");
    request(
        token,
        "POST",
        "/identity/approval-rules",
        ruleSet(
            "REFUND",
            "零额度规则集 " + tag,
            "ACTIVE",
            "SUBMIT_FOR_APPROVAL",
            List.of(zeroCeiling, rule("兜底送审 " + tag, 2, flow))),
        400);

    // 上限为负同样被拒。
    var negative = exempt("负额度免审 " + tag, 1, "USD", "-5");
    request(
        token,
        "POST",
        "/identity/approval-rules",
        ruleSet(
            "REFUND",
            "负额度规则集 " + tag,
            "ACTIVE",
            "SUBMIT_FOR_APPROVAL",
            List.of(negative, rule("兜底送审2 " + tag, 2, flow))),
        400);

    // 一个都建不进去：库里 approval_trigger_rule_decision_shape 也会拒，
    // 但走的是 Java 的可读 400，不该出现 409 或 500。
    assertThat(request(token, "GET", "/identity/approval-rules", null, 200)).isEmpty();
  }

  @Test
  void 免审不得豁免跨法人跨部门新供应商与高风险商品() throws Exception {
    var token = login();
    tag = suffix();
    UUID flow = activeFlow(token, "VENDOR_ONBOARD", "供应商审批流 " + tag);
    var fallback = rule("兜底送审 " + tag, 9, flow);

    // 免审规则把四个高风险条件之一标成 REQUIRED，就是「小额免审顺手免掉跨法人大额」的写法，必须拒。
    for (var dimension :
        List.of("crossLegalEntity", "crossDepartment", "newVendor", "highRiskProduct")) {
      var r = exempt("危险免审 " + tag, 1, "USD", "1000");
      r.put(dimension, "REQUIRED");
      request(
          token,
          "POST",
          "/identity/approval-rules",
          ruleSet(
              "VENDOR_ONBOARD",
              "危险免审规则集 " + tag,
              "ACTIVE",
              "SUBMIT_FOR_APPROVAL",
              List.of(r, fallback)),
          400);
    }
    // 同一维度写成 FORBIDDEN（该条件不成立时才免审）是允许的 —— 那是把管控面收窄，不是挖穿。
    var narrowed = exempt("不跨法人才免审 " + tag, 1, "USD", "1000");
    narrowed.put("crossLegalEntity", "FORBIDDEN");
    narrowed.put("newVendor", "FORBIDDEN");
    UUID id =
        createSet(
            token,
            "VENDOR_ONBOARD",
            "收窄免审规则集 " + tag,
            "SUBMIT_FOR_APPROVAL",
            List.of(narrowed, fallback));
    var rules = detail(token, id).get("rules");
    assertThat(ruleNamed(rules, "不跨法人才免审 " + tag).get("crossLegalEntity").asText())
        .isEqualTo("FORBIDDEN");
    assertThat(ruleNamed(rules, "不跨法人才免审 " + tag).get("newVendor").asText()).isEqualTo("FORBIDDEN");
    // 跨法门的提交不得享受这条免审，落到兜底送审。
    assertThat(
            evaluate(token, "VENDOR_ONBOARD", "USD", "800", Map.of("crossLegalEntity", true))
                .get("matchedRuleName")
                .asText())
        .isEqualTo("兜底送审 " + tag);
  }

  @Test
  void 小额免审与大额送审按上限金额分界() throws Exception {
    var token = login();
    tag = suffix();
    UUID flow = activeFlow(token, "STOCK_TRANSFER", "调拨审批流 " + tag);

    var small = exempt("小额免审 " + tag, 1, "USD", "500");
    small.put("amountMinAmount", new BigDecimal("0"));
    var big = rule("大额送审 " + tag, 2, flow);
    big.put("amountCurrencyCode", "USD");
    big.put("amountMinAmount", new BigDecimal("500"));

    createSet(
        token, "STOCK_TRANSFER", "免审分界规则集 " + tag, "SUBMIT_FOR_APPROVAL", List.of(small, big));

    // 上限是闭区间外的边界：500 免审，500.000001 起送审（半开区间 [0,500) 与 [500,…)）。
    var under = evaluate(token, "STOCK_TRANSFER", "USD", "499.999999", Map.of());
    assertThat(under.get("decision").asText()).isEqualTo("EXEMPT");
    assertThat(under.get("matchedRuleName").asText()).isEqualTo("小额免审 " + tag);
    assertThat(under.get("exemptionCeilingAmount").decimalValue())
        .isEqualByComparingTo("500.000000");
    assertThat(under.get("exemptionCeilingCurrency").asText()).isEqualTo("USD");

    var over = evaluate(token, "STOCK_TRANSFER", "USD", "500", Map.of());
    assertThat(over.get("decision").asText()).isEqualTo("SUBMIT_FOR_APPROVAL");
    assertThat(over.get("matchedRuleName").asText()).isEqualTo("大额送审 " + tag);
    assertThat(over.get("exemptionCeilingAmount").isNull()).isTrue();

    // 币种不同就是另一笔钱：两条规则都是 USD 口径的，EUR 100 两条都不命中，
    // 于是落到生效策略（送审）而不是套用 USD 的免审额度。
    var otherCurrency = evaluate(token, "STOCK_TRANSFER", "EUR", "100", Map.of());
    assertThat(otherCurrency.get("source").asText()).isEqualTo("DEFAULT");
    assertThat(otherCurrency.get("matchedRuleId").isNull()).isTrue();
    assertThat(otherCurrency.get("decision").asText()).isEqualTo("SUBMIT_FOR_APPROVAL");
  }

  @Test
  void 免审规则不指向流程且送审规则必须指向生效流程() throws Exception {
    var token = login();
    tag = suffix();
    UUID flow = activeFlow(token, "PRICE_CHANGE", "调价审批流 " + tag);

    // 免审规则带 targetFlowId：语义自相矛盾（免审就是不走审批），400。
    var exemptWithFlow = exempt("免审却指向流程 " + tag, 1, "USD", "500");
    exemptWithFlow.put("targetFlowId", flow);
    request(
        token,
        "POST",
        "/identity/approval-rules",
        ruleSet(
            "PRICE_CHANGE",
            "免审带流程 " + tag,
            "ACTIVE",
            "SUBMIT_FOR_APPROVAL",
            List.of(exemptWithFlow, rule("兜底 " + tag, 2, flow))),
        400);

    // 送审规则不指向流程：400。
    var submitNoFlow = rule("送审无流程 " + tag, 1, null);
    submitNoFlow.remove("targetFlowId");
    request(
        token,
        "POST",
        "/identity/approval-rules",
        ruleSet(
            "PRICE_CHANGE", "送审无流程 " + tag, "ACTIVE", "SUBMIT_FOR_APPROVAL", List.of(submitNoFlow)),
        400);

    // 指向不存在的流程：400。
    request(
        token,
        "POST",
        "/identity/approval-rules",
        ruleSet(
            "PRICE_CHANGE",
            "悬空流程 " + tag,
            "ACTIVE",
            "SUBMIT_FOR_APPROVAL",
            List.of(rule("悬空 " + tag, 1, UUID.randomUUID()))),
        400);

    // 指向**别的单据类型**的生效流程：400（跨单据类型送审是笔糊涂账）。
    UUID otherFlow = activeFlow(token, "CREDIT_NOTE", "贷项审批流 " + tag);
    request(
        token,
        "POST",
        "/identity/approval-rules",
        ruleSet(
            "PRICE_CHANGE",
            "跨单据类型 " + tag,
            "ACTIVE",
            "SUBMIT_FOR_APPROVAL",
            List.of(rule("跨类型 " + tag, 1, otherFlow))),
        400);

    // 指向草稿状态的流程：400（送审必须有可走的流程）。
    UUID draftFlow = draftFlow(token, "WRITE_OFF", "核销草稿流 " + tag);
    request(
        token,
        "POST",
        "/identity/approval-rules",
        ruleSet(
            "PRICE_CHANGE",
            "指向草稿流程 " + tag,
            "ACTIVE",
            "SUBMIT_FOR_APPROVAL",
            List.of(rule("指向草稿 " + tag, 1, draftFlow))),
        400);
  }

  private UUID draftFlow(String token, String documentType, String name) throws Exception {
    var role = roleId(token, "DRAFTR" + tag);
    var node = new LinkedHashMap<String, Object>();
    node.put("nodeKey", "R1");
    node.put("nodeName", "审批节点");
    node.put("nodeOrder", 0);
    node.put("stageIndex", 0);
    node.put("approverType", "ROLE");
    node.put("roleId", role);
    node.put("approvalMode", "SERIAL");
    var flow = new LinkedHashMap<String, Object>();
    flow.put("documentTypeCode", documentType);
    flow.put("flowName", name);
    flow.put("status", "DRAFT");
    flow.put("description", "草稿流程");
    flow.put("nodes", List.of(node));
    flow.put("branches", List.of());
    flow.put("escalations", List.of());
    return UUID.fromString(request(token, "POST", "/identity/approval-flows", flow, 200).asText());
  }

  @Test
  void 规则集生效要求上游先有生效流程() throws Exception {
    var token = login();
    tag = suffix();
    // MEMBER_OFFBOARD 在出厂码表里，但本租户没为它注册任何流程。
    request(
        token,
        "POST",
        "/identity/approval-rules",
        ruleSet(
            "MEMBER_OFFBOARD",
            "无流程规则集 " + tag,
            "ACTIVE",
            "SUBMIT_FOR_APPROVAL",
            List.of(exempt("免审 " + tag, 1, "USD", "500"))),
        400);
    // 设为草稿则允许存下来：草稿不参与管控，顺序上可以先存后配流程。
    UUID id =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/approval-rules",
                    ruleSet(
                        "MEMBER_OFFBOARD",
                        "草稿规则集 " + tag,
                        "DRAFT",
                        "SUBMIT_FOR_APPROVAL",
                        List.of(exempt("免审 " + tag, 1, "USD", "500"))),
                    200)
                .asText());
    assertThat(detail(token, id).get("ruleSet").get("status").asText()).isEqualTo("DRAFT");
  }

  // ------------------------------------------------------------------
  // 生效策略与规则集出口
  // ------------------------------------------------------------------

  @Test
  void 一条都没命中时走生效策略且默认免审与送审规则互斥() throws Exception {
    var token = login();
    tag = suffix();
    UUID flow = activeFlow(token, "DATA_EXPORT", "导出审批流 " + tag);

    // 默认免审 + 存在送审规则 = 静默放行漏洞，配置期就拒。
    request(
        token,
        "POST",
        "/identity/approval-rules",
        ruleSet(
            "DATA_EXPORT",
            "默认免审 " + tag,
            "ACTIVE",
            "EXEMPT",
            List.of(rule("导出必审 " + tag, 1, flow))),
        400);

    // 默认免审 + 纯免审规则集是自洽的，允许。
    createSet(
        token,
        "DATA_EXPORT",
        "整体免审 " + tag,
        "EXEMPT",
        List.of(exempt("小额免审 " + tag, 1, "USD", "1000")));
    var fallback = evaluate(token, "DATA_EXPORT", "USD", "5000", Map.of());
    assertThat(fallback.get("source").asText()).isEqualTo("DEFAULT");
    assertThat(fallback.get("decision").asText()).isEqualTo("EXEMPT");
    assertThat(fallback.get("matchedRuleId").isNull()).isTrue();
  }

  @Test
  void 生效唯一且规则集可改不可换单据类型() throws Exception {
    var token = login();
    tag = suffix();
    UUID flow = activeFlow(token, "PURCHASE_PAYMENT", "付款审批流 " + tag);

    UUID id =
        createSet(
            token,
            "PURCHASE_PAYMENT",
            "付款规则 " + tag,
            "SUBMIT_FOR_APPROVAL",
            List.of(exempt("小额免审 " + tag, 1, "USD", "300"), rule("其余送审 " + tag, 2, flow)));
    assertThat(detail(token, id).get("ruleSet").get("submitCount").asInt()).isEqualTo(1);
    assertThat(detail(token, id).get("ruleSet").get("exemptionCount").asInt()).isEqualTo(1);
    assertThat(detail(token, id).get("ruleSet").get("ruleCount").asInt()).isEqualTo(2);

    // 同一单据类型再来一条生效规则集：409。
    request(
        token,
        "POST",
        "/identity/approval-rules",
        ruleSet(
            "PURCHASE_PAYMENT",
            "重复生效 " + tag,
            "ACTIVE",
            "SUBMIT_FOR_APPROVAL",
            List.of(rule("重复 " + tag, 1, flow))),
        409);

    // 建后不可改单据类型：400。
    request(
        token,
        "PUT",
        "/identity/approval-rules/" + id,
        ruleSet(
            "REFUND",
            "改单据类型 " + tag,
            "ACTIVE",
            "SUBMIT_FOR_APPROVAL",
            List.of(rule("改名 " + tag, 1, flow))),
        400);
  }

  @Test
  void 规则优先级必须唯一否则先命中没有唯一答案() throws Exception {
    var token = login();
    tag = suffix();
    UUID flow = activeFlow(token, "STOCK_ADJUSTMENT", "调整审批流2 " + tag);
    request(
        token,
        "POST",
        "/identity/approval-rules",
        ruleSet(
            "STOCK_ADJUSTMENT",
            "同序规则 " + tag,
            "ACTIVE",
            "SUBMIT_FOR_APPROVAL",
            List.of(rule("甲 " + tag, 1, flow), rule("乙 " + tag, 1, flow))),
        400);
    request(
        token,
        "POST",
        "/identity/approval-rules",
        ruleSet(
            "STOCK_ADJUSTMENT",
            "重名规则 " + tag,
            "ACTIVE",
            "SUBMIT_FOR_APPROVAL",
            List.of(rule("同名 " + tag, 1, flow), rule("同名 " + tag, 2, flow))),
        400);
  }

  @Test
  void 生效策略出口给出受管控状态与全部免审额度() throws Exception {
    var token = login();
    tag = suffix();
    UUID flow = activeFlow(token, "PURCHASE_ORDER", "采购审批流3 " + tag);

    // 没配规则集的出厂单据类型：默认放行，这是蓝图那句「未注册的变更类操作默认可直接执行」。
    var uncontrolled =
        request(
            token,
            "GET",
            "/identity/approval-rules/effective-policy?documentTypeCode=ROLE_GRANT",
            null,
            200);
    assertThat(uncontrolled.get("controlled").asBoolean()).isFalse();
    assertThat(uncontrolled.get("activeRuleSetId").isNull()).isTrue();
    assertThat(evaluate(token, "ROLE_GRANT", "USD", "1", Map.of()).get("source").asText())
        .isEqualTo("UNCONTROLLED");

    var usdExempt = exempt("美元小额免审 " + tag, 1, "USD", "500");
    usdExempt.put("crossLegalEntity", "FORBIDDEN");
    createSet(
        token,
        "PURCHASE_ORDER",
        "采购规则 " + tag,
        "SUBMIT_FOR_APPROVAL",
        List.of(usdExempt, exempt("欧元小额免审 " + tag, 2, "EUR", "400"), rule("其余送审 " + tag, 3, flow)));

    var policy =
        request(
            token,
            "GET",
            "/identity/approval-rules/effective-policy?documentTypeCode=PURCHASE_ORDER",
            null,
            200);
    assertThat(policy.get("controlled").asBoolean()).isTrue();
    assertThat(policy.get("documentTypeName").asText()).isEqualTo("采购订单");
    assertThat(policy.get("activeFlowId").asText()).isEqualTo(flow.toString());
    assertThat(policy.get("activeRuleCount").asInt()).isEqualTo(3);
    // 免审额度逐条列出：这是本模块最需要被盯住的一处。
    var ceilings = policy.get("exemptionCeilings");
    assertThat(ceilings).hasSize(2);
    assertThat(ceilings.get(0).get("currencyCode").asText()).isEqualTo("USD");
    assertThat(ceilings.get(0).get("maxAmount").decimalValue()).isEqualByComparingTo("500.000000");
    assertThat(ceilings.get(0).get("crossLegalEntity").asText()).isEqualTo("FORBIDDEN");
    assertThat(ceilings.get(1).get("currencyCode").asText()).isEqualTo("EUR");
    assertThat(ceilings.get(1).get("maxAmount").decimalValue()).isEqualByComparingTo("400.000000");
  }

  @Test
  void 停用的规则不参与判定() throws Exception {
    var token = login();
    tag = suffix();
    UUID flow = activeFlow(token, "STOCK_TRANSFER", "调拨审批流4 " + tag);

    var disabled = exempt("停用的免审 " + tag, 1, "USD", "500");
    disabled.put("status", "DISABLED");
    createSet(
        token,
        "STOCK_TRANSFER",
        "含停用规则 " + tag,
        "SUBMIT_FOR_APPROVAL",
        List.of(disabled, rule("兜底送审 " + tag, 2, flow)));

    var result = evaluate(token, "STOCK_TRANSFER", "USD", "100", Map.of());
    assertThat(result.get("matchedRuleName").asText()).isEqualTo("兜底送审 " + tag);
    // 停用规则不出现在生效策略的免审额度里 —— 停用等于这条额度当前不存在。
    var policy =
        request(
            token,
            "GET",
            "/identity/approval-rules/effective-policy?documentTypeCode=STOCK_TRANSFER",
            null,
            200);
    assertThat(policy.get("exemptionCeilings")).isEmpty();
  }

  @Test
  void 删除规则集连带删除其规则且被引用的流程删不掉() throws Exception {
    var token = login();
    tag = suffix();
    UUID flow = activeFlow(token, "REFUND", "退款审批流 " + tag);

    UUID id =
        createSet(
            token,
            "REFUND",
            "待删规则集 " + tag,
            "SUBMIT_FOR_APPROVAL",
            List.of(exempt("小额免审 " + tag, 1, "USD", "300"), rule("其余送审 " + tag, 2, flow)));
    assertThat(detail(token, id).get("ruleSet").get("ruleCount").asInt()).isEqualTo(2);

    // 规则引用的流程不允许被静默删掉：那会让管控无声地掉回默认放行（RESTRICT）。
    request(token, "DELETE", "/identity/approval-flows/" + flow, null, 409);

    request(token, "DELETE", "/identity/approval-rules/" + id, null, 200);
    request(token, "GET", "/identity/approval-rules/" + id, null, 404);
    try (var c =
            DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var s = c.createStatement();
        var rs =
            s.executeQuery(
                "select count(*) from approval_trigger_rule where rule_set_id='" + id + "'")) {
      rs.next();
      assertThat(rs.getInt(1)).isZero();
    }
    // 规则集没了之后该单据类型回到默认放行。
    assertThat(
            request(
                    token,
                    "GET",
                    "/identity/approval-rules/effective-policy?documentTypeCode=REFUND",
                    null,
                    200)
                .get("controlled")
                .asBoolean())
        .isFalse();
  }

  // ------------------------------------------------------------------
  // 版本、金额与时间写回值、结构体检、审计
  // ------------------------------------------------------------------

  @Test
  void 版本递增金额与时间戳写回正确() throws Exception {
    var token = login();
    tag = suffix();
    UUID flow = activeFlow(token, "CREDIT_NOTE", "贷项审批流 " + tag);
    var before = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(2);

    var exemptRule = exempt("小额免审 " + tag, 1, "USD", "1000.500000");
    UUID id =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/approval-rules",
                    ruleSet(
                        "CREDIT_NOTE",
                        "贷项规则 " + tag,
                        "DRAFT",
                        "SUBMIT_FOR_APPROVAL",
                        List.of(exemptRule, rule("其余送审 " + tag, 2, flow))),
                    200)
                .asText());

    var created = detail(token, id).get("ruleSet");
    assertThat(created.get("version").asLong()).isZero();
    assertThat(created.get("status").asText()).isEqualTo("DRAFT");
    // timestamptz 写回值必须落在请求前后的两个墙钟之间：用 LocalDateTime 绑会静默偏 -28800 秒。
    assertThat(OffsetDateTime.parse(created.get("createdAt").asText()))
        .isAfter(before)
        .isBefore(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(5));

    // 金额按 numeric(19,6) 原样写回，不做浮点也不截断小数位。
    var stored = ruleNamed(detail(token, id).get("rules"), "小额免审 " + tag);
    assertThat(stored.get("amountMaxAmount").decimalValue()).isEqualByComparingTo("1000.500000");
    try (var c =
            DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var s = c.createStatement();
        var rs =
            s.executeQuery(
                "select amount_max_amount,amount_currency_code from approval_trigger_rule"
                    + " where tenant_id=(select tenant_id from approval_rule_set where id='"
                    + id
                    + "') and rule_name='"
                    + "小额免审 "
                    + tag
                    + "'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("1000.500000");
      assertThat(rs.getString(2)).isEqualTo("USD");
    }

    request(
        token,
        "PUT",
        "/identity/approval-rules/" + id,
        ruleSet(
            "CREDIT_NOTE",
            "贷项规则改 " + tag,
            "ACTIVE",
            "SUBMIT_FOR_APPROVAL",
            List.of(exempt("小额免审 " + tag, 1, "USD", "1000.500000"), rule("其余送审 " + tag, 2, flow))),
        200);
    var updated = detail(token, id).get("ruleSet");
    assertThat(updated.get("version").asLong()).isEqualTo(1);
    assertThat(updated.get("status").asText()).isEqualTo("ACTIVE");
    assertThat(updated.get("ruleSetName").asText()).isEqualTo("贷项规则改 " + tag);
  }

  @Test
  void 结构体检能查出事后才暴露的问题() throws Exception {
    var token = login();
    tag = suffix();
    // 草稿规则集：可以没有生效流程，体检应给 INFO 而不是替配置页做决定。
    UUID draft =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/approval-rules",
                    ruleSet(
                        "MEMBER_OFFBOARD",
                        "草稿体检 " + tag,
                        "DRAFT",
                        "SUBMIT_FOR_APPROVAL",
                        List.of(exempt("免审 " + tag, 1, "USD", "500"))),
                    200)
                .asText());
    var draftReport =
        request(token, "GET", "/identity/approval-rules/" + draft + "/validation", null, 200);
    assertThat(draftReport.get("errorCount").asInt()).isZero();

    // 生效规则集 + 生效流程，然后把流程停用：规则指向的流程不再是生效状态，必须被查出来。
    UUID flow = activeFlow(token, "REFUND", "退款审批流 " + tag);
    UUID id =
        createSet(
            token,
            "REFUND",
            "体检规则集 " + tag,
            "SUBMIT_FOR_APPROVAL",
            List.of(rule("送审 " + tag, 1, flow)));
    request(
        token,
        "PUT",
        "/identity/approval-flows/" + flow,
        flow("REFUND", "退款审批流 " + tag, "DISABLED"),
        200);

    var report = request(token, "GET", "/identity/approval-rules/" + id + "/validation", null, 200);
    assertThat(report.get("errorCount").asInt()).isPositive();
    assertThat(report.toString()).contains("TARGET_FLOW_INACTIVE");
    // 停用后该规则集不能再被激活：写入时会先拒。
    request(
        token,
        "PUT",
        "/identity/approval-rules/" + id,
        ruleSet(
            "REFUND",
            "体检规则集 " + tag,
            "ACTIVE",
            "SUBMIT_FOR_APPROVAL",
            List.of(rule("送审 " + tag, 1, flow))),
        400);
  }

  private Map<String, Object> flow(String documentType, String name, String status)
      throws Exception {
    var token = login();
    var role = roleId(token, "FIX" + tag);
    var node = new LinkedHashMap<String, Object>();
    node.put("nodeKey", "R1");
    node.put("nodeName", "审批节点");
    node.put("nodeOrder", 0);
    node.put("stageIndex", 0);
    node.put("approverType", "ROLE");
    node.put("roleId", role);
    node.put("approvalMode", "SERIAL");
    var f = new LinkedHashMap<String, Object>();
    f.put("documentTypeCode", documentType);
    f.put("flowName", name);
    f.put("status", status);
    f.put("description", "停用流程");
    f.put("nodes", List.of(node));
    f.put("branches", List.of());
    f.put("escalations", List.of());
    return f;
  }

  @Test
  void 变更留痕进审计() throws Exception {
    var token = login();
    tag = suffix();
    UUID flow = activeFlow(token, "DATA_EXPORT", "导出审批流 " + tag);
    UUID id =
        createSet(
            token,
            "DATA_EXPORT",
            "审计规则集 " + tag,
            "SUBMIT_FOR_APPROVAL",
            List.of(rule("导出送审 " + tag, 1, flow)));
    assertThat(request(token, "GET", "/identity/audit", null, 200).toString())
        .contains("ApprovalRuleSetCreated")
        .contains("审计规则集 " + tag);
    request(token, "DELETE", "/identity/approval-rules/" + id, null, 200);
    assertThat(request(token, "GET", "/identity/audit", null, 200).toString())
        .contains("ApprovalRuleSetDeleted");
  }

  @Test
  void 未登录与无权限读不到任何审批规则() throws Exception {
    http.perform(get("/identity/approval-rules")).andExpect(status().isUnauthorized());
    http.perform(get("/identity/approval-rules/effective-policy?documentTypeCode=REFUND"))
        .andExpect(status().isUnauthorized());
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/identity/approval-rules/evaluate")
                .contentType("application/json")
                .content("{\"documentTypeCode\":\"REFUND\"}"))
        .andExpect(status().isUnauthorized());
  }

  // ------------------------------------------------------------------
  // 多租户三断言
  // ------------------------------------------------------------------

  @Test
  void 多租户隔离三断言() throws Exception {
    var parent = login();
    tag = suffix();

    UUID parentFlow = activeFlow(parent, "PURCHASE_ORDER", "父租户审批流 " + tag);
    UUID parentSet =
        createSet(
            parent,
            "PURCHASE_ORDER",
            "父租户规则集 " + tag,
            "SUBMIT_FOR_APPROVAL",
            List.of(exempt("父租户免审 " + tag, 1, "USD", "500"), rule("父租户送审 " + tag, 2, parentFlow)));

    // 子租户：一套完全独立的流程与规则集
    String childUser = "apr-child-" + tag.toLowerCase();
    request(
        parent,
        "POST",
        "/identity/children",
        Map.of(
            "code",
            "APR" + tag,
            "name",
            "审批规则子租户",
            "ownerUsername",
            childUser,
            "ownerPassword",
            "test-member-password"),
        200);
    String child = loginUser(childUser);
    UUID childFlow = activeFlow(child, "PURCHASE_ORDER", "子租户审批流 " + tag);
    UUID childSet =
        createSet(
            child,
            "PURCHASE_ORDER",
            "子租户规则集 " + tag,
            "SUBMIT_FOR_APPROVAL",
            List.of(exempt("子租户免审 " + tag, 1, "USD", "300"), rule("子租户送审 " + tag, 2, childFlow)));

    // 断言一：两租户互不可见
    var parentView = request(parent, "GET", "/identity/approval-rules", null, 200).toString();
    assertThat(parentView).contains("父租户规则集 " + tag).doesNotContain("子租户规则集 " + tag);
    var childView = request(child, "GET", "/identity/approval-rules", null, 200).toString();
    assertThat(childView).contains("子租户规则集 " + tag).doesNotContain("父租户规则集 " + tag);

    // 生效策略与试算各自只认本租户的额度：父租户 400 免审、子租户 500 不免审。
    assertThat(
            request(
                    parent,
                    "GET",
                    "/identity/approval-rules/effective-policy?documentTypeCode=PURCHASE_ORDER",
                    null,
                    200)
                .get("exemptionCeilings")
                .get(0)
                .get("maxAmount")
                .decimalValue())
        .isEqualByComparingTo("500.000000");
    assertThat(
            request(
                    child,
                    "GET",
                    "/identity/approval-rules/effective-policy?documentTypeCode=PURCHASE_ORDER",
                    null,
                    200)
                .get("exemptionCeilings")
                .get(0)
                .get("maxAmount")
                .decimalValue())
        .isEqualByComparingTo("300.000000");
    assertThat(evaluate(child, "PURCHASE_ORDER", "USD", "400", Map.of()).get("decision").asText())
        .isEqualTo("SUBMIT_FOR_APPROVAL");
    assertThat(evaluate(parent, "PURCHASE_ORDER", "USD", "400", Map.of()).get("decision").asText())
        .isEqualTo("EXEMPT");

    // 拿子租户的 id 去父租户读 / 改 / 删 / 体检一律 404
    request(parent, "GET", "/identity/approval-rules/" + childSet, null, 404);
    request(
        parent,
        "PUT",
        "/identity/approval-rules/" + childSet,
        ruleSet(
            "PURCHASE_ORDER",
            "父租户越权改名 " + tag,
            "ACTIVE",
            "SUBMIT_FOR_APPROVAL",
            List.of(rule("越权 " + tag, 1, parentFlow))),
        404);
    request(parent, "DELETE", "/identity/approval-rules/" + childSet, null, 404);
    request(parent, "GET", "/identity/approval-rules/" + childSet + "/validation", null, 404);

    // 父租户也配不出引用子租户流程的规则：那条流程在本租户不可见。
    request(
        parent,
        "POST",
        "/identity/approval-rules",
        ruleSet(
            "PURCHASE_ORDER",
            "引用子租户流程 " + tag,
            "DRAFT",
            "SUBMIT_FOR_APPROVAL",
            List.of(rule("引用 " + tag, 1, childFlow))),
        400);

    // 断言二：伪造 tenant header 无效。request() 每次都带随机 X-Tenant-Id。
    String forged = UUID.randomUUID().toString();
    var forgedView =
        http.perform(
                get("/identity/approval-rules")
                    .header("Authorization", "Bearer " + parent)
                    .header("X-Tenant-Id", forged))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forgedView).contains("父租户规则集 " + tag).doesNotContain("子租户规则集 " + tag);
    // 伪造成子租户的真实租户 id（调用方本来就是它的所有者）：结果仍然不变。
    var forgedChild =
        http.perform(
                get("/identity/approval-rules")
                    .header("Authorization", "Bearer " + parent)
                    .header("X-Tenant-Id", tenantOf(child)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forgedChild).contains("父租户规则集 " + tag).doesNotContain("子租户规则集 " + tag);
    http.perform(get("/identity/approval-rules")).andExpect(status().isUnauthorized());

    // 断言三：连接池归还后不串租户。池大小被压到 1，交替租户请求复用同一物理连接。
    for (int i = 0; i < 3; i++) {
      var p = request(parent, "GET", "/identity/approval-rules", null, 200).toString();
      assertThat(p).as("第 %s 轮父租户不得看到子租户规则集", i).doesNotContain("子租户规则集 " + tag);
      assertThat(p).contains("父租户规则集 " + tag);
      var c = request(child, "GET", "/identity/approval-rules", null, 200).toString();
      assertThat(c).as("第 %s 轮子租户不得看到父租户规则集", i).doesNotContain("父租户规则集 " + tag);
      assertThat(c).contains("子租户规则集 " + tag);
      // 试算也必须跟着租户走：同一个金额在两个租户下结论不同。
      assertThat(
              evaluate(parent, "PURCHASE_ORDER", "USD", "400", Map.of()).get("decision").asText())
          .as("第 %s 轮父租户 400 应免审", i)
          .isEqualTo("EXEMPT");
      assertThat(evaluate(child, "PURCHASE_ORDER", "USD", "400", Map.of()).get("decision").asText())
          .as("第 %s 轮子租户 400 应送审", i)
          .isEqualTo("SUBMIT_FOR_APPROVAL");
    }

    // 事务外（无租户上下文）直查应当为空
    assertThat(sql.fetch("select id from approval_rule_set")).isEmpty();
    assertThat(sql.fetch("select id from approval_trigger_rule")).isEmpty();
    // 两张表都必须是 FORCE RLS，否则表 owner 连接会绕过隔离。
    for (var table : List.of("approval_rule_set", "approval_trigger_rule")) {
      assertThat(
              sql.fetchOne(
                      "select relforcerowsecurity as forced from pg_class where relname=?", table)
                  .get("forced", Boolean.class))
          .as("%s 必须 FORCE RLS", table)
          .isTrue();
    }
    // 父租户仍能按自己的 id 读到自己的规则集（前面那些 404 是拿子租户 id 越权的结果）。
    assertThat(detail(parent, parentSet).get("ruleSet").get("ruleSetName").asText())
        .isEqualTo("父租户规则集 " + tag);
    assertThat(detail(child, childSet).get("ruleSet").get("ruleSetName").asText())
        .isEqualTo("子租户规则集 " + tag);
  }

  private UUID tenantOf(String token) throws Exception {
    return UUID.fromString(
        request(token, "GET", "/identity/tenants/current", null, 200).get("tenantId").asText());
  }
}
