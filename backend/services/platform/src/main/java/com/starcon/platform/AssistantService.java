package com.starcon.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.AssistantIntentCatalog.Definition;
import com.starcon.platform.AssistantModels.AssistantAnswer;
import com.starcon.platform.AssistantModels.AssistantAskInput;
import com.starcon.platform.AssistantModels.AssistantCapabilities;
import com.starcon.platform.AssistantModels.AssistantCapability;
import com.starcon.platform.AssistantModels.AssistantEvidence;
import com.starcon.platform.AssistantModels.AssistantGap;
import com.starcon.platform.AssistantModels.AssistantIntentView;
import com.starcon.platform.AssistantModels.AssistantNextAction;
import com.starcon.platform.AssistantModels.AssistantSessionView;
import com.starcon.platform.AssistantModels.AssistantTurnView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 18.4.1.1「对话助手」的服务层。
 *
 * <p>蓝图加粗句：「**答案必须附明细链接**——不给依据的 AI 数字不能用于决策」。
 *
 * <p>⇒ 本服务的**结构性保证**：<b>{@code answered=true} 的答案一定带至少一条明细依据</b>；
 * 读不到事实的那一轮 <b>不出结论</b>（{@code answered=false} + 如实说明哪一格读不到），
 * 而不是出一条没有依据的结论。
 *
 * <p>🔴 <b>受控意图目录</b>（{@link AssistantIntentCatalog}，7 条）：意图识别是**确定性关键词匹配**，
 * 每个意图对应**一条写死的只读查询**。不把任意自然语言直译成任意 SQL（那是 18.4.1.2 的边界）。
 *
 * <p>🔴 <b>自由问句走既有 AI 端口</b>（{@link AiTextGenerationPort}）：本仓没有任何真实 LLM 适配器
 * ⇒ 端口如实答「未接通 + 缺什么 + 归谁」，本服务<b>不返回任何看似成功的答案</b>。
 *
 * <p>🔴 <b>租户绝不来自客户端</b>：租户由 {@link AccessService} 从已认证会话解析，
 * 请求体里出现 tenantId / userId / membershipId 会被 Jackson 直接 400。
 *
 * <p>🔴 <b>相对时间用 DB 的 now() 算</b>（{@link #today()}）：不拿 JVM 墙钟和 DB 时钟比大小
 * （跨时钟域比较会产出恒定的假红）。
 */
@Service
public class AssistantService {

  /** 一条待落库的明细依据（快照时刻在落库时补）。 */
  private record EvidenceDraft(
      String factService,
      String factTable,
      String factField,
      String factRef,
      String factLabel,
      String metricCode,
      BigDecimal metricValue,
      String metricUnit,
      String detailRoute,
      String detailLabel,
      String caliberNote) {

    /** 人话形态的取值（**算不出来就说算不出来**，不写 0）。 */
    String metricLabel() {
      if (metricValue == null) return "算不出来";
      var unit = metricUnit == null || metricUnit.isBlank() ? "" : " " + metricUnit;
      return metricValue.stripTrailingZeros().toPlainString() + unit;
    }
  }

  /** 一个分组（一组同口径的候选项；组内有序）。 */
  private record Group(String key, String label, List<EvidenceDraft> ordered) {}

  /** 一次意图执行的结果。 */
  private record Payload(
      boolean answered,
      String answer,
      String caliber,
      List<EvidenceDraft> evidence,
      List<AssistantNextAction> nextActions) {}

  /** 一个待发货候选项（带 SLA 截止时刻，用于排序）。 */
  private record PendingCandidate(OffsetDateTime deadline, EvidenceDraft draft) {}

  /** 会话上下文（从 platform_assistant_session 读出来的那一行）。 */
  private record SessionRow(
      UUID id,
      String intentCode,
      String shopCode,
      String scopeType,
      String periodCode,
      LocalDate periodFrom,
      LocalDate periodTo,
      int turnCount) {}

  private final DSLContext sql;
  private final ObjectMapper json;
  private final AiTextGenerationPort ai;
  private final AssistantReportingPort reporting;
  private final AssistantFinancePort finance;
  private final AssistantTradePort trade;
  private final AssistantCrmPort crm;

  public AssistantService(
      DSLContext sql,
      ObjectMapper json,
      AiTextGenerationPort ai,
      AssistantReportingPort reporting,
      AssistantFinancePort finance,
      AssistantTradePort trade,
      AssistantCrmPort crm) {
    this.sql = sql;
    this.json = json;
    this.ai = ai;
    this.reporting = reporting;
    this.finance = finance;
    this.trade = trade;
    this.crm = crm;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：哪些真接通、哪些还没交付、归谁。
   *
   * <p>🔴 **AI 通道这一格必须是 {@code available=false}**：本仓没有任何真实 LLM 适配器，
   * 如实写「未接入 + 缺什么 + 归谁」，而不是写「已支持智能问答」。
   */
  public AssistantCapabilities capabilities() {
    var aiAvailability = ai.availability();
    var aiConnected = aiAvailability.stream().anyMatch(AiTextGenerationPort.AiAvailability::connected);
    var aiReason =
        aiAvailability.isEmpty()
            ? "AI 文本生成端口没有报出任何能力状态。"
            : aiAvailability.get(0).reason();
    var aiOwner = aiAvailability.isEmpty() ? "18.4" : aiAvailability.get(0).ownerModule();

    var capabilities = new ArrayList<AssistantCapability>();
    capabilities.add(
        new AssistantCapability(
            "18.4.1.1",
            "受控意图问答",
            true,
            "受控意图目录 " + AssistantIntentCatalog.DEFINITIONS.size()
                + " 条（platform_assistant_intent），每条对应一个上游只读端点",
            "",
            "问句按封闭词表确定性匹配；命中的意图走真实只读查询，答案带可点的明细链接"
                + "（platform_assistant_evidence）。"));
    capabilities.add(
        new AssistantCapability(
            "18.4.1.1",
            "追问与上下文延续",
            true,
            "会话上下文 platform_assistant_session（意图 / 店铺 / 数据范围 / 时间范围）",
            "",
            "追问沿用上一轮的意图与口径，只替换被显式修改的维度（时间 / 名次）。"));
    capabilities.add(
        new AssistantCapability(
            "18.4.1.1",
            "自由问句（自然语言理解）",
            aiConnected,
            "AI 文本生成端口 " + (aiConnected ? "已接通" : "未接通"),
            aiConnected ? "" : aiOwner,
            aiConnected ? "" : aiReason));

    var gaps =
        List.of(
            new AssistantGap(
                "18.4.1.2",
                "智能查询",
                "platform",
                false,
                "本模块的上游",
                "任意自然语言 → 任意 SQL 属于 18.4.1.2 的边界；本模块只走受控意图目录。"),
            new AssistantGap(
                "18.4",
                "AI 供应商适配器",
                "platform",
                false,
                "本模块的上游",
                "本仓没有任何真实的 LLM 适配器（openai / anthropic / qwen 一个都没有）"
                    + "⇒ 自由问句如实答「未接通」。"));

    return new AssistantCapabilities(capabilities, gaps);
  }

  // ================================================================ 意图目录

  /** 受控意图目录（**真读库**；首次访问时按内置目录幂等落库）。 */
  public List<AssistantIntentView> intents(UUID tenantId) {
    ensureCatalog(tenantId);
    return sql
        .fetch(
            "select intent_code,intent_name,question_examples,source_service,source_endpoint,"
                + "source_caliber,detail_route,next_actions from platform_assistant_intent"
                + " where tenant_id = ? order by intent_code",
            tenantId)
        .map(
            r ->
                new AssistantIntentView(
                    r.get("intent_code", String.class),
                    r.get("intent_name", String.class),
                    list(r.get("question_examples", String[].class)),
                    r.get("source_service", String.class),
                    r.get("source_endpoint", String.class),
                    r.get("source_caliber", String.class),
                    r.get("detail_route", String.class),
                    nextActions(r.get("next_actions", org.jooq.JSONB.class))));
  }

  /**
   * 把受控意图目录**幂等**落到本租户名下。
   *
   * <p>⚠️ 用 {@code ON CONFLICT DO NOTHING}：重复调用不产生重复行，也不会覆盖运营改过的目录。
   */
  private void ensureCatalog(UUID tenantId) {
    for (var d : AssistantIntentCatalog.DEFINITIONS) {
      sql.execute(
          "insert into platform_assistant_intent(id,tenant_id,intent_code,intent_name,"
              + "question_examples,match_keywords,source_service,source_endpoint,source_caliber,"
              + "detail_route,next_actions,created_at,updated_at)"
              + " values(?,?,?,?,?::text[],?::text[],?,?,?,?,?::jsonb,now(),now())"
              + " on conflict (tenant_id,intent_code) do nothing",
          UUID.randomUUID(),
          tenantId,
          d.code(),
          d.name(),
          pgTextArray(d.examples()),
          pgTextArray(d.strongKeywords()),
          d.sourceService(),
          d.sourceEndpoint(),
          d.caliber(),
          d.detailRoute(),
          jsonValue(d.nextActions()));
    }
  }

  // ================================================================ 提问

  /**
   * 提一个问题（**主入口**）。
   *
   * @param bearer 调用方自己的会话（转发给上游只读端点；上游据此解析租户）
   */
  public AssistantAnswer ask(String bearer, UUID tenantId, AssistantAskInput input) {
    var question = input.question() == null ? "" : input.question().trim();
    if (question.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "问题不能为空");
    }
    if (question.length() > 500) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "问题最多 500 个字符");
    }
    ensureCatalog(tenantId);

    // ---- 会话（租户从会话解析；客户端传来的会话 id 也必须属于本租户）
    var sessionId = input.sessionId();
    var session = sessionId == null ? null : loadSession(tenantId, sessionId);
    if (sessionId != null && session == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在或不属于当前租户");
    }
    var newSession = session == null;
    if (newSession) {
      sessionId = UUID.randomUUID();
      session = new SessionRow(sessionId, "", "", "COMPANY", AssistantIntentCatalog.DEFAULT_PERIOD, null, null, 0);
    }

    // ---- 时间范围：问句里显式提了才替换，否则沿用上一轮（上下文延续）
    var periodCode = AssistantIntentCatalog.periodOf(question);
    if (periodCode == null) {
      periodCode = session.periodCode() == null || session.periodCode().isBlank()
          ? AssistantIntentCatalog.DEFAULT_PERIOD
          : session.periodCode();
    }
    var window = window(periodCode);

    // 新会话：先把会话行落下来（**租户由 RLS + 触发器钉死**，不是客户端自称的）。
    if (newSession) {
      insertSession(tenantId, sessionId, window);
    }

    // ---- 意图
    Definition definition = null;
    var matchMode = "FREE_TEXT";
    if (input.intentCode() != null && !input.intentCode().isBlank()) {
      definition = AssistantIntentCatalog.byCode(input.intentCode());
      if (definition == null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "未知意图码：" + input.intentCode() + "（受控目录里没有这一条）");
      }
      matchMode = "KEYWORD";
    } else {
      definition = AssistantIntentCatalog.match(question);
      if (definition != null) {
        matchMode = "KEYWORD";
      } else if (!session.intentCode().isBlank() && AssistantIntentCatalog.isFollowUp(question)) {
        // 🔴 **追问**：沿用上一轮的意图与口径，只替换被显式修改的维度。
        definition = AssistantIntentCatalog.byCode(session.intentCode());
        matchMode = "FOLLOW_UP";
      }
    }

    // ---- 名次（「那第二名呢」）
    var position = AssistantIntentCatalog.rankOf(question);
    if (position == null) position = 1;

    if (definition == null) {
      return freeText(bearer, tenantId, sessionId, session, question, periodCode, window);
    }

    var payload = dispatch(definition, window, bearer, position);
    var turnNo = session.turnCount() + 1;
    var turnId = recordTurn(
        tenantId,
        sessionId,
        turnNo,
        question,
        matchMode,
        definition,
        payload.answered(),
        payload.answer(),
        payload.caliber(),
        window,
        payload.evidence());
    updateSession(
        tenantId,
        sessionId,
        definition.code(),
        session.shopCode(),
        session.scopeType(),
        periodCode,
        window,
        question,
        turnNo);

    return new AssistantAnswer(
        sessionId,
        turnId,
        turnNo,
        question,
        matchMode,
        definition.code(),
        definition.name(),
        payload.answered(),
        payload.answer(),
        payload.caliber(),
        periodCode,
        window.from(),
        window.to(),
        session.scopeType(),
        session.shopCode(),
        definition.sourceService(),
        definition.sourceEndpoint(),
        payload.evidence().stream().map(e -> toEvidence(e)).toList(),
        payload.nextActions(),
        payload.answered() ? "" : payload.answer(),
        "");
  }

  /**
   * 自由问句：走既有 AI 端口，**未接通就如实答「未接通」**。
   *
   * <p>🔴 <b>不返回任何看似成功的答案</b>：本仓没有任何真实 LLM 适配器，
   * 端口返回 {@code connected=false}，这里把它原样变成「为什么没答 + 归谁」。
   * 落库的是「问过什么、为什么没答」的台账，<b>不是答案</b>。
   */
  private AssistantAnswer freeText(
      String bearer,
      UUID tenantId,
      UUID sessionId,
      SessionRow session,
      String question,
      String periodCode,
      Window window) {
    var outcome =
        ai.generate(
            new AiTextGenerationPort.AiGenerationRequest(
                "ASSISTANT_QUESTION", question, "zh-CN", "zh-CN", "", List.of()),
            bearer);
    var reason =
        outcome.reason() == null || outcome.reason().isBlank()
            ? "AI 生成通道未接通（没有报出原因）。"
            : outcome.reason();

    var turnNo = session.turnCount() + 1;
    var turnId = recordFreeTextTurn(tenantId, sessionId, turnNo, question, reason, window);
    updateSession(
        tenantId,
        sessionId,
        session.intentCode(),
        session.shopCode(),
        session.scopeType(),
        periodCode,
        window,
        question,
        turnNo);

    return new AssistantAnswer(
        sessionId,
        turnId,
        turnNo,
        question,
        "FREE_TEXT",
        "",
        "",
        false,
        reason,
        "自由问句没有命中受控意图目录 ⇒ 这一轮**没有出结论**；"
            + "受控意图目录只认封闭词表里的问题形态（见 /platform/assistant/intents）。",
        periodCode,
        window.from(),
        window.to(),
        session.scopeType(),
        session.shopCode(),
        "",
        "",
        List.of(),
        List.of(),
        reason,
        outcome.ownerModule());
  }

  // ================================================================ 意图分派

  private Payload dispatch(Definition definition, Window window, String bearer, int position) {
    return switch (definition.code()) {
      case "SHOP_PROFIT_RANKING" -> profitRanking(definition, window, bearer, position);
      case "INVENTORY_REORDER" -> inventoryReorder(definition, window, bearer, position);
      case "PRODUCT_TOP_SELLER" -> topSeller(definition, window, bearer, position);
      case "FUND_CASHFLOW" -> fundCashflow(definition, window, bearer, position);
      case "LIFECYCLE_DECLINE" -> lifecycleDecline(definition, window, bearer, position);
      case "CUSTOMER_CHURN" -> customerChurn(definition, window, bearer, position);
      case "ORDER_PENDING_SHIPMENT" -> pendingShipment(definition, window, bearer, position);
      default ->
          throw new ResponseStatusException(
              HttpStatus.INTERNAL_SERVER_ERROR, "受控意图目录里有未实现的意图：" + definition.code());
    };
  }

  /**
   * 店铺利润排名（13.4.1.1 多模式利润报表，店铺维度）。
   *
   * <p>🔴 <b>不跨币种比较</b>：毛利额按币种分列，不同币种的毛利额不是同一个量纲 ⇒ 分组比较。
   */
  private Payload profitRanking(
      Definition definition, Window window, String bearer, int position) {
    var response = reporting.profitByShop(window.from(), window.to(), bearer);
    var byCurrency = new LinkedHashMap<String, List<AssistantReportingPort.AssistantProfitRow>>();
    for (var row : rows(response.rows())) {
      if (row.grossProfit() == null) {
        // 🔴 **算不出来的毛利额不是 0**：跳过，并在口径说明里点名「不可比」。
        continue;
      }
      var currency = row.currency() == null || row.currency().isBlank() ? "未标注币种" : row.currency();
      byCurrency.computeIfAbsent(currency, k -> new ArrayList<>()).add(row);
    }
    if (byCurrency.isEmpty()) {
      return noConclusion(
          definition,
          window,
          "该窗口内没有可比的店铺利润事实（毛利额算不出来的行不参与排名）。"
              + "口径与时间范围见下。");
    }
    var groups = new ArrayList<Group>();
    byCurrency.forEach(
        (currency, list) -> {
          list.sort(Comparator.comparing(AssistantReportingPort.AssistantProfitRow::grossProfit));
          var ordered = new ArrayList<EvidenceDraft>();
          for (var row : list) {
            var missing = row.missingComponents() == null ? List.<String>of() : row.missingComponents();
            ordered.add(
                new EvidenceDraft(
                    "reporting",
                    "reporting_profit_fact",
                    "amount",
                    row.dimensionCode(),
                    row.dimensionLabel() == null || row.dimensionLabel().isBlank()
                        ? row.dimensionCode()
                        : row.dimensionLabel(),
                    "GROSS_PROFIT",
                    row.grossProfit(),
                    currency,
                    definition.detailRoute() + "?shopCode=" + enc(row.dimensionCode()),
                    "打开该店的利润明细",
                    "毛利额 = 收入 − 各成本项之和（reporting_profit_fact.amount 按店铺与期间聚合）。"
                        + (missing.isEmpty() ? "" : "缺成本项：" + String.join("、", missing) + "（不可比）。")
                        + (row.note() == null || row.note().isBlank() ? "" : " " + row.note())));
          }
          groups.add(new Group(currency, currency + " 口径", ordered));
        });
    return render(
        definition,
        window,
        groups,
        position,
        "第 " + position + " 名（毛利额从低到高）",
        response.caliberNote(),
        response.warnings());
  }

  /**
   * 补货与库存周转（13.5.1.1 库存分析 SKU 下钻）。
   *
   * <p>🔴 **判定口径是写死的、可复核的**：可用量 ≤ 0 ⇒ 缺货；
   * 可用量 &lt; 窗口销量 ⇒ 窗口内卖掉的量已经超过可用量（缺口 = 销量 − 可用量）。
   */
  private Payload inventoryReorder(
      Definition definition, Window window, String bearer, int position) {
    var response = reporting.inventoryDrilldown(window.from(), window.to(), bearer);
    var ordered = new ArrayList<EvidenceDraft>();
    for (var row : rows(response.rows())) {
      if (row.availableQuantity() == null) continue;
      var available = row.availableQuantity();
      var sold = row.soldQuantity() == null ? BigDecimal.ZERO : row.soldQuantity();
      var outOfStock = available.signum() <= 0;
      var gap = sold.subtract(available);
      if (!outOfStock && gap.signum() <= 0) continue;
      var metric = outOfStock ? BigDecimal.ZERO : gap.setScale(6, RoundingMode.HALF_UP);
      var title = row.skuName() == null || row.skuName().isBlank() ? row.skuCode() : row.skuName();
      ordered.add(
          new EvidenceDraft(
              "reporting",
              "reporting_inventory_analysis_position",
              "available",
              row.skuCode(),
              title,
              "REORDER_GAP",
              metric,
              "件",
              definition.detailRoute() + "?skuCode=" + enc(row.skuCode()),
              "打开该 SKU 的库存分析",
              outOfStock
                  ? "可用量 ≤ 0（缺货）；窗口销量 " + plain(sold) + " 件。"
                  : "缺口 = 窗口销量 " + plain(sold) + " 件 − 可用量 " + plain(available) + " 件。"
                      + (row.abcClassLabel() == null || row.abcClassLabel().isBlank()
                          ? ""
                          : " ABC 分档：" + row.abcClassLabel() + "。")));
    }
    if (ordered.isEmpty()) {
      return noConclusion(
          definition,
          window,
          "该窗口内没有命中补货口径的 SKU（可用量 ≤ 0 或可用量 < 窗口销量）。");
    }
    ordered.sort(
        Comparator.comparing((EvidenceDraft e) -> e.metricValue() == null ? BigDecimal.ZERO : e.metricValue())
            .reversed());
    var groups = List.of(new Group("ALL", "全部 SKU", ordered));
    return render(
        definition,
        window,
        groups,
        position,
        "第 " + position + " 项（缺口从大到小）",
        response.caliberNote(),
        response.warnings());
  }

  /**
   * 商品销量表现（13.2 商品维度销量）。
   *
   * <p>🔴 销量是**件数**口径（销售订单明细行数量之和，按 SKU 码分组），
   * 与金额口径不是同一个数，不能相加。
   */
  private Payload topSeller(Definition definition, Window window, String bearer, int position) {
    var response = reporting.salesTrend(window.from(), window.to(), bearer);
    var quantities = new LinkedHashMap<String, BigDecimal>();
    var labels = new LinkedHashMap<String, String>();
    for (var series : rows(response.series())) {
      if (!AssistantReportingPort.PRODUCT_SALES_QUANTITY.equals(series.metricCode())) continue;
      if (series.dimensionKey() == null || series.dimensionKey().isBlank()) continue;
      var sum = BigDecimal.ZERO;
      for (var point : rows(series.points())) {
        if (point.value() == null) continue;
        sum = sum.add(point.value());
      }
      quantities.merge(series.dimensionKey(), sum, BigDecimal::add);
      if (series.dimensionLabel() != null && !series.dimensionLabel().isBlank()) {
        labels.put(series.dimensionKey(), series.dimensionLabel());
      }
    }
    if (quantities.isEmpty()) {
      return noConclusion(
          definition,
          window,
          "该窗口内没有商品维度销量事实（销量口径只统计取到明细的订单）。");
    }
    var ordered = new ArrayList<EvidenceDraft>();
    quantities.forEach(
        (sku, quantity) ->
            ordered.add(
                new EvidenceDraft(
                    "reporting",
                    "reporting_sales_trend_fact",
                    "quantity",
                    sku,
                    labels.getOrDefault(sku, sku),
                    AssistantReportingPort.PRODUCT_SALES_QUANTITY,
                    quantity.setScale(6, RoundingMode.HALF_UP),
                    "件",
                    definition.detailRoute() + "?skuCode=" + enc(sku),
                    "打开该 SKU 的销量明细",
                    "商品维度销量 = 销售订单明细行的数量之和，按 SKU 码分组（窗口内各日相加）。")));
    ordered.sort(Comparator.comparing((EvidenceDraft e) -> e.metricValue()).reversed());
    var groups = List.of(new Group("ALL", "全部 SKU", ordered));
    return render(
        definition,
        window,
        groups,
        position,
        "第 " + position + " 名（销量从高到低）",
        "",
        response.warnings());
  }

  /**
   * 资金与现金流（12.7.2.1 资金分析）。
   *
   * <p>🔴 <b>三个口径并列、不混</b>：实际流入 / 预期流入 / 计划流出。
   * 🔴 <b>实际流出没有时间序列</b>（上游说明了原因）⇒ 「净流入」只是
   * 「实际流入 − 计划流出」这一口径的净值，<b>不是完整现金流</b>。
   */
  private Payload fundCashflow(Definition definition, Window window, String bearer, int position) {
    var response = finance.cashFlowTrend(window.from(), window.to(), bearer);
    if (rows(response.totals()).isEmpty()) {
      return noConclusion(definition, window, "该窗口内没有资金流水事实（收款 / 付款计划都为空）。");
    }
    var groups = new ArrayList<Group>();
    for (var total : response.totals()) {
      var currency = total.currency() == null || total.currency().isBlank() ? "未标注币种" : total.currency();
      var ordered = new ArrayList<EvidenceDraft>();
      ordered.add(
          new EvidenceDraft(
              "finance",
              "finance_receipt",
              "amount",
              currency,
              currency + " 实际流入",
              "ACTUAL_INFLOW",
              total.actualInflowAmount(),
              currency,
              definition.detailRoute() + "?currency=" + enc(currency),
              "打开资金分析",
              "实际流入 = 已到账收款单金额合计（finance_receipt.amount，status=RECORDED）。"));
      ordered.add(
          new EvidenceDraft(
              "finance",
              "finance_payment_plan",
              "planned_amount",
              currency,
              currency + " 计划流出",
              "PLANNED_OUTFLOW",
              total.plannedOutflowAmount(),
              currency,
              definition.detailRoute() + "?currency=" + enc(currency),
              "打开资金分析",
              "计划流出 = 未付应付的**排期**（finance_payment_plan.planned_amount），"
                  + "与当前未付应付**重叠**，两个数不能相加。"));
      ordered.add(
          new EvidenceDraft(
              "finance",
              "finance_payment_plan",
              "planned_amount",
              currency,
              currency + " 净流入（实际流入 − 计划流出）",
              "NET_INFLOW",
              total.netExpectedAmount(),
              currency,
              definition.detailRoute() + "?currency=" + enc(currency),
              "打开资金分析",
              "净流入 = 实际流入 − 计划流出。⚠️ **实际流出没有时间序列**"
                  + (response.actualOutflowBasis() == null || response.actualOutflowBasis().isBlank()
                      ? ""
                      : "（上游说明：" + response.actualOutflowBasis() + "）")
                  + " ⇒ 这个净值**不是完整现金流**。"));
      groups.add(new Group(currency, currency + " 口径", ordered));
    }
    return render(
        definition,
        window,
        groups,
        position,
        "第 " + position + " 项",
        response.actualOutflowBasis(),
        List.of());
  }

  /**
   * 走下坡商品名单（13.3.1.2 生命周期与分层，商品维度）。
   *
   * <p>🔴 判定口径是**可测的下行幅度**（近期销量 vs 前期销量的下行比），
   * 不是「档位名叫什么」—— 档位算不出来时不会被塞进某个默认档位。
   */
  private Payload lifecycleDecline(Definition definition, Window window, String bearer, int position) {
    var response = reporting.lifecycle(window.from(), window.to(), bearer);
    var ordered = new ArrayList<EvidenceDraft>();
    for (var row : rows(response.rows())) {
      if (row.declineRatio() == null) continue;
      if (row.declineRatio().signum() <= 0) continue;
      var title = row.dimensionName() == null || row.dimensionName().isBlank()
          ? row.dimensionKey()
          : row.dimensionName();
      ordered.add(
          new EvidenceDraft(
              "reporting",
              "reporting_lifecycle_segmentation_result",
              "decline_ratio",
              row.dimensionKey(),
              title,
              "DECLINE_RATIO",
              row.declineRatio(),
              "比率",
              definition.detailRoute() + "?skuCode=" + enc(row.dimensionKey()),
              "打开该商品的生命周期分层",
              "下行比 = 1 − 近期销量 ÷ 前期销量（reporting_lifecycle_segmentation_result.decline_ratio）。"
                  + (row.lifecycleSegment() == null || row.lifecycleSegment().isBlank()
                      ? ""
                      : " 档位：" + row.lifecycleSegment() + "。")
                  + (row.recommendedAction() == null || row.recommendedAction().isBlank()
                      ? ""
                      : " 上游建议动作：" + row.recommendedAction() + "。")));
    }
    if (ordered.isEmpty()) {
      return noConclusion(definition, window, "该窗口内没有下行比大于 0 的商品（下行比算不出来的不进名单）。");
    }
    ordered.sort(Comparator.comparing((EvidenceDraft e) -> e.metricValue()).reversed());
    var groups = List.of(new Group("ALL", "全部商品", ordered));
    return render(
        definition,
        window,
        groups,
        position,
        "第 " + position + " 名（下行比从高到低）",
        String.join("；", strings(response.caliberNotes())),
        List.of());
  }

  /**
   * 高价值客户流失名单（15.6.2.1 生命周期与流失预警）。
   *
   * <p>🔴 **风险分是规则分，不是模型置信度**：阈值归 crm，平台不自己定阈值。
   */
  private Payload customerChurn(Definition definition, Window window, String bearer, int position) {
    var response = crm.churnAlerts(bearer);
    var ordered = new ArrayList<EvidenceDraft>();
    for (var row : rows(response.rows())) {
      var score = row.riskScore() == null ? BigDecimal.ZERO : row.riskScore();
      ordered.add(
          new EvidenceDraft(
              "crm",
              "crm_churn_alert",
              "risk_score",
              row.customerRef(),
              row.customerName() == null || row.customerName().isBlank()
                  ? row.customerRef()
                  : row.customerName(),
              "RISK_SCORE",
              score,
              "分",
              definition.detailRoute() + "?customerRef=" + enc(row.customerRef()),
              "打开该客户的生命周期预警",
              "风险分 = 规则分（crm_churn_alert.risk_score，0–100），**不是模型置信度**。"
                  + " 超出期 " + row.overdueDays() + " 天（距上次下单 " + row.daysSinceLastOrder()
                  + " 天 − 预期复购 " + row.expectedRepurchaseDays() + " 天 − 容差 " + row.toleranceDays()
                  + " 天）。状态：" + (row.statusLabel() == null ? row.status() : row.statusLabel()) + "。"));
    }
    if (ordered.isEmpty()) {
      return noConclusion(definition, window, "当前没有流失预警记录（预警清单为空）。");
    }
    ordered.sort(Comparator.comparing((EvidenceDraft e) -> e.metricValue()).reversed());
    var groups = List.of(new Group("ALL", "全部客户", ordered));
    return render(
        definition,
        window,
        groups,
        position,
        "第 " + position + " 名（风险分从高到低）",
        response.note(),
        List.of());
  }

  /**
   * 待发货积压（9.4.1.1 待发货）。
   *
   * <p>🔴 <b>时点口径</b>：上游这个端点没有时间窗口参数 ⇒ 问句里的「上周 / 本月」对它不生效，
   * 口径说明里如实写明。
   */
  private Payload pendingShipment(Definition definition, Window window, String bearer, int position) {
    var response = trade.pendingUnits(bearer);
    var items = rows(response.items());
    if (items.isEmpty()) {
      return noConclusion(definition, window, "当前没有卡在待发货的单元（清单为空）。");
    }
    // 最紧急的排前面：有 SLA 截止的按截止时刻升序（早的在前），没有截止的排最后。
    var byDeadline = new ArrayList<PendingCandidate>();
    for (var unit : items) {
      var deadline = unit.slaDeadline() == null ? "未设 SLA" : unit.slaDeadline().toString();
      var packed = unit.packedAt() == null ? "未记录" : unit.packedAt().toString();
      var draft =
          new EvidenceDraft(
              "trade",
              "shipment_pending_unit",
              "parcel_count",
              unit.salesOrderNo(),
              unit.outboundOrderNo() + " / " + (unit.warehouseRef() == null ? "" : unit.warehouseRef()),
              "PARCEL_COUNT",
              BigDecimal.valueOf(unit.parcelCount()),
              "件",
              definition.detailRoute() + "?salesOrderNo=" + enc(unit.salesOrderNo()),
              "打开该订单的待发货明细",
              "打包时刻 " + packed + "；SLA 截止 " + deadline
                  + "；SLA 档位 " + (unit.slaBucket() == null ? "未标注" : unit.slaBucket())
                  + "（上游判定，平台不自己算超时）。");
      byDeadline.add(new PendingCandidate(unit.slaDeadline(), draft));
    }
    byDeadline.sort(
        Comparator.comparing(
            PendingCandidate::deadline, Comparator.nullsLast(Comparator.naturalOrder())));
    var ordered = byDeadline.stream().map(PendingCandidate::draft).toList();
    var groups = List.of(new Group("ALL", "全部待发货单元", ordered));
    return render(
        definition,
        window,
        groups,
        position,
        "第 " + position + " 项（SLA 截止最早的在前）",
        "时点口径：待发货是**现在还卡着**的清单，不是「某段时间里卡过」的清单。",
        List.of());
  }

  // ================================================================ 答案渲染

  /**
   * 把分组渲染成答案 + 明细依据。
   *
   * <p>🔴 <b>结构性保证</b>：{@code answered=true} 时 evidence 一定非空 ——
   * 没有明细依据的答案**不许出现**（蓝图加粗句）。
   */
  private Payload render(
      Definition definition,
      Window window,
      List<Group> groups,
      int position,
      String headlineLabel,
      String upstreamNote,
      List<String> warnings) {
    var evidence = new ArrayList<EvidenceDraft>();
    var lines = new ArrayList<String>();
    var missing = new ArrayList<String>();

    for (var group : groups) {
      if (group.ordered().isEmpty()) continue;
      var index = Math.min(position, group.ordered().size()) - 1;
      if (position > group.ordered().size()) {
        missing.add(group.label() + "只有 " + group.ordered().size() + " 项，没有第 " + position + " 项");
      }
      var headline = group.ordered().get(index);
      lines.add(
          group.label()
              + "："
              + headlineLabel.replace("第 " + position + " ", "")
              + "是 "
              + headline.factLabel()
              + "（"
              + headline.metricLabel()
              + "）。");
      // 证据：先放头条那一条，再放它后面的（最多 3 条 / 组，合计最多 6 条）。
      for (int i = index; i < group.ordered().size() && i < index + 3; i++) {
        evidence.add(group.ordered().get(i));
      }
    }

    if (evidence.isEmpty()) {
      return noConclusion(definition, window, String.join("；", missing.isEmpty()
          ? List.of("没有可下钻的事实。")
          : missing));
    }

    var answer = new StringBuilder(String.join(" ", lines));
    if (!missing.isEmpty()) {
      answer.append("（").append(String.join("；", missing)).append("）");
    }
    var caliber =
        definition.caliber()
            + " 时间范围："
            + window.from()
            + " ~ "
            + window.to()
            + "（"
            + periodLabel(window.periodCode())
            + "）。"
            + (upstreamNote == null || upstreamNote.isBlank() ? "" : " 上游口径：" + upstreamNote)
            + (warnings == null || warnings.isEmpty() ? "" : " 上游提示：" + String.join("；", warnings));

    return new Payload(
        true, answer.toString(), caliber, evidence, definition.nextActions());
  }

  /**
   * 「没有结论」的结果。
   *
   * <p>🔴 <b>不出结论就是不出结论</b>：没有可下钻的事实时，如实说明为什么，
   * <b>不返回一条看起来像答案的句子</b>（蓝图加粗句）。
   */
  private Payload noConclusion(Definition definition, Window window, String reason) {
    return new Payload(
        false,
        reason,
        definition.caliber()
            + " 时间范围："
            + window.from()
            + " ~ "
            + window.to()
            + "（"
            + periodLabel(window.periodCode())
            + "）。",
        List.of(),
        definition.nextActions());
  }

  // ================================================================ 时间窗口

  /** 一个时间窗口。 */
  private record Window(String periodCode, LocalDate from, LocalDate to) {}

  /**
   * **DB 的「今天」**。
   *
   * <p>🔴 相对时间（上周 / 本月）必须与数据落在**同一个时钟域**：
   * 这里用 {@code now() at time zone 'UTC'} 取，而不是 JVM 墙钟
   * （跨时钟域比较会产出恒定的假红 —— 2026-10-21 实测过这个坑）。
   */
  private LocalDate today() {
    return sql.fetchOne("select (now() at time zone 'UTC')::date").get(0, LocalDate.class);
  }

  /** 按代号解析窗口（起止日都由 DB 的「今天」推导）。 */
  private Window window(String periodCode) {
    var today = today();
    return switch (periodCode) {
      case "LAST_7_DAYS" -> new Window(periodCode, today.minusDays(6), today);
      case "LAST_WEEK" -> {
        // 上周 = 上一自然周（周一至周日）：上一个**严格早于**今天的周一。
        var monday = today.with(TemporalAdjusters.previous(DayOfWeek.MONDAY));
        yield new Window(periodCode, monday, monday.plusDays(6));
      }
      case "THIS_MONTH" ->
          new Window(periodCode, today.with(java.time.temporal.TemporalAdjusters.firstDayOfMonth()), today);
      default -> new Window(periodCode, today.minusDays(29), today);
    };
  }

  private static String periodLabel(String code) {
    return switch (code) {
      case "LAST_7_DAYS" -> "最近 7 天";
      case "LAST_WEEK" -> "上周（上一自然周，周一至周日）";
      case "THIS_MONTH" -> "本月（自然月至今）";
      default -> "最近 30 天";
    };
  }

  // ================================================================ 落库

  /** 记一轮问答（**有依据的那种**）。 */
  private UUID recordTurn(
      UUID tenantId,
      UUID sessionId,
      int turnNo,
      String question,
      String matchMode,
      Definition definition,
      boolean answered,
      String answer,
      String caliber,
      Window window,
      List<EvidenceDraft> evidence) {
    var turnId = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into platform_assistant_turn(id,tenant_id,session_id,turn_no,question_text,"
            + "intent_code,intent_name,match_mode,answer_summary,answer_scope,period_from,period_to,"
            + "source_service,source_endpoint,created_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
        turnId,
        tenantId,
        sessionId,
        turnNo,
        truncate(question, 1000),
        definition.code(),
        definition.name(),
        matchMode,
        truncate(answer, 1000),
        truncate(caliber, 500),
        window.from(),
        window.to(),
        definition.sourceService(),
        definition.sourceEndpoint(),
        now);
    var seq = 1;
    for (var draft : evidence) {
      sql.execute(
          "insert into platform_assistant_evidence(id,tenant_id,turn_id,session_id,seq,"
              + "fact_service,fact_table,fact_field,fact_ref,fact_label,metric_code,metric_value,"
              + "metric_unit,detail_route,detail_label,caliber_note,snapshot_at,created_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(),
          tenantId,
          turnId,
          sessionId,
          seq++,
          draft.factService(),
          draft.factTable(),
          draft.factField(),
          truncate(draft.factRef(), 200),
          truncate(draft.factLabel(), 200),
          draft.metricCode(),
          draft.metricValue(),
          draft.metricUnit(),
          draft.detailRoute(),
          truncate(draft.detailLabel(), 200),
          truncate(draft.caliberNote(), 500),
          now,
          now);
    }
    return turnId;
  }

  /** 记一轮**没有出结论**的问答（自由问句：AI 通道未接通）。 */
  private UUID recordFreeTextTurn(
      UUID tenantId,
      UUID sessionId,
      int turnNo,
      String question,
      String reason,
      Window window) {
    var turnId = UUID.randomUUID();
    sql.execute(
        "insert into platform_assistant_turn(id,tenant_id,session_id,turn_no,question_text,"
            + "intent_code,intent_name,match_mode,answer_summary,answer_scope,period_from,period_to,"
            + "source_service,source_endpoint,created_at)"
            + " values(?,?,?,?,?,'','','FREE_TEXT',?,?,?,?,'','',?::timestamptz)",
        turnId,
        tenantId,
        sessionId,
        turnNo,
        truncate(question, 1000),
        truncate(reason, 1000),
        truncate(
            "自由问句没有命中受控意图目录 ⇒ 这一轮没有出结论。", 500),
        window.from(),
        window.to(),
        OffsetDateTime.now(ZoneOffset.UTC));
    return turnId;
  }

  /** 更新会话上下文（**追问与上下文延续**的载体）。 */
  private void updateSession(
      UUID tenantId,
      UUID sessionId,
      String intentCode,
      String shopCode,
      String scopeType,
      String periodCode,
      Window window,
      String question,
      int turnCount) {
    sql.execute(
        "update platform_assistant_session set intent_code=?,shop_code=?,scope_type=?,"
            + "period_code=?,period_from=?,period_to=?,last_question=?,turn_count=?,updated_at=now()"
            + " where id=? and tenant_id=?",
        intentCode == null ? "" : intentCode,
        shopCode == null ? "" : shopCode,
        scopeType == null || scopeType.isBlank() ? "COMPANY" : scopeType,
        periodCode,
        window.from(),
        window.to(),
        truncate(question, 500),
        turnCount,
        sessionId,
        tenantId);
  }

  private SessionRow loadSession(UUID tenantId, UUID sessionId) {
    var row =
        sql.fetchOne(
            "select id,intent_code,shop_code,scope_type,period_code,period_from,period_to,turn_count"
                + " from platform_assistant_session where id=? and tenant_id=?",
            sessionId,
            tenantId);
    if (row == null) return null;
    return new SessionRow(
        row.get("id", UUID.class),
        nz(row.get("intent_code", String.class)),
        nz(row.get("shop_code", String.class)),
        nz(row.get("scope_type", String.class)),
        nz(row.get("period_code", String.class)),
        row.get("period_from", LocalDate.class),
        row.get("period_to", LocalDate.class),
        row.get("turn_count", Integer.class) == null ? 0 : row.get("turn_count", Integer.class));
  }

  /** 新建一个会话（第一轮提问时建）。 */
  private void insertSession(UUID tenantId, UUID sessionId, Window window) {
    sql.execute(
        "insert into platform_assistant_session(id,tenant_id,intent_code,shop_code,scope_type,"
            + "period_code,period_from,period_to,last_question,turn_count,created_at,updated_at)"
            + " values(?,?,'','','COMPANY',?,?,?,'',0,now(),now())",
        sessionId,
        tenantId,
        AssistantIntentCatalog.DEFAULT_PERIOD,
        window.from(),
        window.to());
  }

  // ================================================================ 会话查询

  /** 会话上下文 + 问答历史（**带明细链接**）。 */
  public AssistantSessionView session(UUID tenantId, UUID sessionId) {
    var row =
        sql.fetchOne(
            "select id,intent_code,shop_code,scope_type,period_code,period_from,period_to,turn_count"
                + " from platform_assistant_session where id=? and tenant_id=?",
            sessionId,
            tenantId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在或不属于当前租户");
    }
    var turns =
        sql.fetch(
            "select id,turn_no,question_text,match_mode,intent_code,intent_name,answer_summary,"
                + "answer_scope,period_from,period_to,created_at from platform_assistant_turn"
                + " where session_id=? and tenant_id=? order by turn_no",
            sessionId,
            tenantId);
    var views = new ArrayList<AssistantTurnView>();
    for (var turn : turns) {
      var turnId = turn.get("id", UUID.class);
      var evidence =
          sql.fetch(
                  "select fact_service,fact_table,fact_field,fact_ref,fact_label,metric_code,"
                      + "metric_value,metric_unit,detail_route,detail_label,caliber_note,snapshot_at"
                      + " from platform_assistant_evidence where turn_id=? and tenant_id=? order by seq",
                  turnId,
                  tenantId)
              .map(r -> toEvidence(r));
      views.add(
          new AssistantTurnView(
              turnId,
              turn.get("turn_no", Integer.class),
              turn.get("question_text", String.class),
              turn.get("match_mode", String.class),
              turn.get("intent_code", String.class),
              turn.get("intent_name", String.class),
              !turn.get("answer_summary", String.class).isBlank()
                  && !"FREE_TEXT".equals(turn.get("match_mode", String.class)),
              turn.get("answer_summary", String.class),
              turn.get("answer_scope", String.class),
              turn.get("period_from", LocalDate.class),
              turn.get("period_to", LocalDate.class),
              turn.get("created_at", OffsetDateTime.class),
              evidence));
    }
    var intent = AssistantIntentCatalog.byCode(nz(row.get("intent_code", String.class)));
    return new AssistantSessionView(
        row.get("id", UUID.class),
        nz(row.get("intent_code", String.class)),
        intent == null ? "" : intent.name(),
        nz(row.get("shop_code", String.class)),
        nz(row.get("scope_type", String.class)),
        nz(row.get("period_code", String.class)),
        row.get("period_from", LocalDate.class),
        row.get("period_to", LocalDate.class),
        row.get("turn_count", Integer.class) == null ? 0 : row.get("turn_count", Integer.class),
        views);
  }

  // ================================================================ 工具

  private AssistantEvidence toEvidence(EvidenceDraft draft) {
    return new AssistantEvidence(
        draft.factService(),
        draft.factTable(),
        draft.factField(),
        draft.factRef(),
        draft.factLabel(),
        draft.metricCode(),
        // 🔴 **十进制文本**：走 JSON number 会在浏览器里掉精度（金额是判定口径）。
        draft.metricValue() == null ? null : draft.metricValue().toPlainString(),
        draft.metricUnit(),
        draft.detailRoute(),
        draft.detailLabel(),
        draft.caliberNote(),
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  private AssistantEvidence toEvidence(org.jooq.Record r) {
    return new AssistantEvidence(
        r.get("fact_service", String.class),
        r.get("fact_table", String.class),
        r.get("fact_field", String.class),
        r.get("fact_ref", String.class),
        r.get("fact_label", String.class),
        r.get("metric_code", String.class),
        r.get("metric_value", BigDecimal.class) == null
            ? null
            : r.get("metric_value", BigDecimal.class).toPlainString(),
        r.get("metric_unit", String.class),
        r.get("detail_route", String.class),
        r.get("detail_label", String.class),
        r.get("caliber_note", String.class),
        r.get("snapshot_at", OffsetDateTime.class));
  }

  private List<AssistantNextAction> nextActions(org.jooq.JSONB value) {
    if (value == null) return List.of();
    try {
      var node = json.readTree(value.data());
      var out = new ArrayList<AssistantNextAction>();
      for (var item : node) {
        out.add(
            new AssistantNextAction(
                item.path("label").asText(""),
                item.path("route").asText(""),
                item.path("moduleId").asText("")));
      }
      return out;
    } catch (Exception e) {
      // 目录里的 next_actions 是本模块自己写的 JSONB；解析不了就当没有建议，
      // **不返回一条编出来的建议**。
      return List.of();
    }
  }

  private String jsonValue(List<AssistantNextAction> actions) {
    try {
      return json.writeValueAsString(actions);
    } catch (Exception e) {
      return "[]";
    }
  }

  /** PG 的 text 数组字面量（配合 {@code ?::text[]} 绑定）。 */
  private static String pgTextArray(List<String> values) {
    if (values == null || values.isEmpty()) return "{}";
    var sb = new StringBuilder("{");
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) sb.append(",");
      sb.append("\"").append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
    }
    return sb.append("}").toString();
  }

  private static String enc(String value) {
    if (value == null) return "";
    return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
  }

  private static String plain(BigDecimal value) {
    return value == null ? "—" : value.stripTrailingZeros().toPlainString();
  }

  private static String truncate(String value, int max) {
    if (value == null) return "";
    return value.length() <= max ? value : value.substring(0, max);
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static <T> List<T> rows(List<T> list) {
    return list == null ? List.of() : list;
  }

  /** 同上，但元素是字符串（{@code String.join} 要的就是这个）。 */
  private static List<String> strings(List<String> list) {
    return list == null ? List.of() : list;
  }

  private static List<String> list(String[] values) {
    return values == null ? List.of() : List.of(values);
  }
}
