package com.starcon.catalog;

import static com.starcon.catalog.PriceAdjustmentModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 5.4.1.1 调价单的**共享底座**。
 *
 * <p>🔴 本底座**继承** 5.3.1.2 的 {@link BatchCalcFixture}（真实 PG16 容器 + identity 权限替身 +
 * warehouse / channel 替身 + 单连接连接池 + SKU / 价格观测夹具），**不改它一个字符** ——
 * 底座是共享资产，改它会让兄弟模块的测试一起红。本类只加本模块自己需要的东西：
 * 一个 **17 组织（identity）审批替身**。
 *
 * <p><b>为什么审批替身必须自己写</b>：审批是本模块的**上游**，替身要能回答三件事 ——
 * 「这次要不要审」「把它挂到哪条待办上」「那边批了没有」。这三个动作的形状必须与
 * identity 的真实端点逐字一致（{@code POST /identity/approval-rules/evaluate}、
 * {@code POST /identity/approval-instances}、{@code GET /identity/approval-records/{id}}），
 * 否则本模块就会在「以为接上了」的状态下写错代码。
 *
 * <p><b>替身只存在于测试代码里</b>：生产代码路径永远是真实的 HTTP 调用。
 */
abstract class PriceAdjustmentFixture extends BatchCalcFixture {

  /** 求值模式：这次提交要不要走审批。 */
  enum Evaluation {
    /** 命中规则 ⇒ 需审批。 */
    REQUIRE_APPROVAL,
    /** 免审（规则集 defaultAction = EXEMPT）。 */
    EXEMPT,
    /** 本租户未配规则集 ⇒ 默认放行（identity 的 UNCONTROLLED 语义）。 */
    UNCONTROLLED
  }

  /** 审批替身里的一条在办实例。 */
  record StubInstance(
      UUID id,
      String instanceNo,
      UUID flowId,
      String sourceReferenceId,
      String status,
      Instant submittedAt) {}

  /** 审批流 id（替身固定用它，够测试用）。 */
  static final UUID FLOW_ID = UUID.randomUUID();

  /** 命中的规则 id。 */
  static final UUID RULE_ID = UUID.randomUUID();

  /** 求值模式。 */
  static final AtomicReference<Evaluation> EVALUATION =
      new AtomicReference<>(Evaluation.REQUIRE_APPROVAL);

  /** 置位后审批替身一律回 503（模拟「审批服务不可达」）。 */
  static final AtomicBoolean identityDown = new AtomicBoolean();

  /** 替身里在办的实例：sourceReferenceId → 实例。 */
  static final Map<String, StubInstance> INSTANCES = new ConcurrentHashMap<>();

  /** 替身收到的 X-Internal-Key 头（断言服务间授权真的带上了）。 */
  static final AtomicReference<String> lastInternalKey = new AtomicReference<>();

  /** 替身收到的最近一次 Authorization 头（断言租户不是靠自定义头传过去的）。 */
  static final AtomicReference<String> lastApprovalBearer = new AtomicReference<>();

  /** 实例号自增。 */
  static final AtomicInteger INSTANCE_SEQ = new AtomicInteger();

  static {
    identity.createContext("/identity", PriceAdjustmentFixture::handleIdentity);
  }

  // ================================================================ 审批替身

  /** identity 审批替身：形状与 identity 的真实端点逐字一致。 */
  private static void handleIdentity(HttpExchange e) throws IOException {
    lastApprovalBearer.set(e.getRequestHeaders().getFirst("Authorization"));
    if (identityDown.get()) {
      respond(e, 503, Map.of("message", "审批服务暂时不可达"));
      return;
    }
    String path = e.getRequestURI().getPath();
    String method = e.getRequestMethod();

    if ("POST".equals(method) && path.equals("/identity/approval-rules/evaluate")) {
      respond(e, 200, evaluation());
      return;
    }
    if ("POST".equals(method) && path.equals("/identity/approval-instances")) {
      lastInternalKey.set(e.getRequestHeaders().getFirst("X-Internal-Key"));
      var body = readJson(e);
      String sourceReferenceId = String.valueOf(body.get("sourceReferenceId"));
      var existing = INSTANCES.get(sourceReferenceId);
      if (existing != null && "PENDING".equals(existing.status())) {
        // 🔴 幂等：同一来源单据存活期间只能有一条实例 ⇒ 409。
        respond(e, 409, Map.of("message", "该来源单据已有一条在办的审批实例，请勿重复提交"));
        return;
      }
      var instance =
          new StubInstance(
              UUID.randomUUID(),
              "AI-2026-" + String.format("%04d", INSTANCE_SEQ.incrementAndGet()),
              UUID.fromString(String.valueOf(body.get("flowId"))),
              sourceReferenceId,
              "PENDING",
              Instant.now());
      INSTANCES.put(sourceReferenceId, instance);
      respond(
          e,
          200,
          Map.of(
              "id", instance.id(),
              "instanceNo", instance.instanceNo(),
              "flowId", instance.flowId(),
              "currentNodeKey", "MANAGER",
              "currentNodeName", "经理审批",
              "applicantId", user,
              "routedBy", "AMOUNT_BRANCH",
              "submittedAt", instance.submittedAt().toString()));
      return;
    }
    if ("GET".equals(method) && path.startsWith("/identity/approval-records/")) {
      UUID id = UUID.fromString(path.substring("/identity/approval-records/".length()));
      var found = INSTANCES.values().stream().filter(i -> i.id().equals(id)).findFirst();
      if (found.isEmpty()) {
        respond(e, 404, Map.of("message", "审批记录不存在或不属于当前租户"));
        return;
      }
      respond(e, 200, Map.of("instance", instanceView(found.get()), "timeline", List.of()));
      return;
    }
    if ("GET".equals(method) && path.equals("/identity/approval-records")) {
      var items = new ArrayList<Object>();
      for (var instance : INSTANCES.values()) items.add(instanceView(instance));
      respond(
          e,
          200,
          Map.of(
              "items", items,
              "total", items.size(),
              "page", 0,
              "size", 200,
              "summary",
              Map.of("total", items.size(), "pendingTotal", items.size(), "longestDwellSeconds", 0)));
      return;
    }
    respond(e, 404, Map.of("message", "审批替身没有这个端点：" + method + " " + path));
  }

  /** 求值响应（形状 = identity 的 ApprovalEvaluationView）。 */
  private static Map<String, Object> evaluation() {
    var body = new LinkedHashMap<String, Object>();
    body.put("documentTypeCode", "PRICE_CHANGE");
    switch (EVALUATION.get()) {
      case REQUIRE_APPROVAL -> {
        body.put("source", "RULE");
        body.put("decision", "SUBMIT_FOR_APPROVAL");
        body.put("matchedRuleId", RULE_ID);
        body.put("matchedRuleName", "大额调价需审批");
        body.put("matchedPriority", 10);
        body.put("targetFlowId", FLOW_ID);
        body.put("targetFlowName", "调价审批流");
        body.put("exemptionCeilingAmount", null);
        body.put("exemptionCeilingCurrency", null);
        body.put("reason", "命中「大额调价需审批」规则");
      }
      case EXEMPT -> {
        body.put("source", "RULE");
        body.put("decision", "EXEMPT");
        body.put("matchedRuleId", RULE_ID);
        body.put("matchedRuleName", "小额免审");
        body.put("matchedPriority", 20);
        body.put("targetFlowId", null);
        body.put("targetFlowName", null);
        body.put("exemptionCeilingAmount", new BigDecimal("100"));
        body.put("exemptionCeilingCurrency", "USD");
        body.put("reason", "命中「小额免审」规则，免审额度 100 USD");
      }
      default -> {
        body.put("source", "UNCONTROLLED");
        body.put("decision", "EXEMPT");
        body.put("matchedRuleId", null);
        body.put("matchedRuleName", null);
        body.put("matchedPriority", null);
        body.put("targetFlowId", null);
        body.put("targetFlowName", null);
        body.put("exemptionCeilingAmount", null);
        body.put("exemptionCeilingCurrency", null);
        body.put("reason", "本租户未为单据类型「PRICE_CHANGE」配置生效规则集，按默认放行处理");
      }
    }
    return body;
  }

  /** 实例视图（形状 = identity 的 ApprovalRecordView 的一部分）。 */
  private static Map<String, Object> instanceView(StubInstance instance) {
    var body = new LinkedHashMap<String, Object>();
    body.put("id", instance.id());
    body.put("instanceNo", instance.instanceNo());
    body.put("documentTypeCode", "PRICE_CHANGE");
    body.put("documentTypeName", "调价单");
    body.put("flowName", "调价审批流");
    body.put("subjectTitle", "调价单 " + instance.sourceReferenceId());
    body.put("amount", new BigDecimal("100"));
    body.put("currencyCode", "USD");
    body.put("applicantId", user);
    body.put("applicantName", "tester");
    body.put("currentNodeKey", "MANAGER");
    body.put("currentNodeName", "经理审批");
    body.put("status", instance.status());
    body.put("sourceSystem", "catalog");
    body.put("sourceReferenceId", instance.sourceReferenceId());
    body.put("submittedAt", instance.submittedAt().toString());
    body.put("finishedAt", instance.status().equals("PENDING") ? null : instance.submittedAt().toString());
    body.put("dwellSeconds", 0);
    body.put("actionCount", 0);
    body.put("rejectCount", 0);
    body.put("transferCount", 0);
    body.put("originalInstanceId", null);
    body.put("originalInstanceNo", null);
    body.put("resubmittedCount", 0);
    return body;
  }

  /** 把一条在办实例改成已批 / 已驳回（模拟审批人在 identity 那边做了决定）。 */
  static void decide(String sourceReferenceId, String status) {
    var instance = INSTANCES.get(sourceReferenceId);
    if (instance == null) throw new IllegalStateException("没有这条审批实例：" + sourceReferenceId);
    INSTANCES.put(
        sourceReferenceId,
        new StubInstance(
            instance.id(),
            instance.instanceNo(),
            instance.flowId(),
            instance.sourceReferenceId(),
            status,
            instance.submittedAt()));
  }

  /** 替身收到的审批实例条数（断言「不产生第二条实例」）。 */
  static int instanceCount() {
    return INSTANCES.size();
  }

  // ================================================================ 请求构造

  /** 一次调价请求（默认：AMAZON/US、USD、新价 120）。 */
  PriceAdjustmentRequest request(UUID skuId, String newPrice) {
    return new PriceAdjustmentRequest(
        skuId,
        "AMAZON",
        "US",
        "USD",
        "USD",
        null,
        new BigDecimal(newPrice),
        null,
        BigDecimal.ONE,
        new BigDecimal("0.15"),
        null,
        new BigDecimal("5"),
        null,
        DAY_OLD.minusDays(1),
        TODAY,
        null,
        "竞品降价跟价",
        "测试调价单");
  }

  /** 带旧价与标题的调价请求。 */
  PriceAdjustmentRequest request(UUID skuId, String oldPrice, String newPrice) {
    var base = request(skuId, newPrice);
    return new PriceAdjustmentRequest(
        base.skuId(),
        base.platformCode(),
        base.siteCode(),
        base.currency(),
        base.costCurrency(),
        base.fxRate(),
        base.newPrice(),
        new BigDecimal(oldPrice),
        base.quantity(),
        base.platformFeeRate(),
        base.platformFeeFixed(),
        base.logisticsCostPerUnit(),
        base.monthlyFixedCost(),
        base.costWindowFrom(),
        base.costWindowTo(),
        "标题：跟价",
        base.reason(),
        base.note());
  }

  // ================================================================ HTTP 小工具

  /** 读请求体（解析成 JSON 对象）。 */
  @SuppressWarnings("unchecked")
  static Map<String, Object> readJson(HttpExchange e) throws IOException {
    var body = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    if (body.isBlank()) return Map.of();
    return new ObjectMapper().findAndRegisterModules().readValue(body, Map.class);
  }

  /** 回一个 JSON 响应。 */
  static void respond(HttpExchange e, int status, Object body) throws IOException {
    byte[] bytes = JSON.writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(status, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }

  /** 解析查询串。 */
  static String query(HttpExchange e, String key) {
    var raw = e.getRequestURI().getRawQuery();
    if (raw == null) return null;
    for (var pair : raw.split("&")) {
      var kv = pair.split("=", 2);
      if (kv.length == 2 && URLDecoder.decode(kv[0], StandardCharsets.UTF_8).equals(key)) {
        return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
      }
    }
    return null;
  }
}
