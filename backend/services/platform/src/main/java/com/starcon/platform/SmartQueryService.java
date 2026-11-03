package com.starcon.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.SmartQueryModels.SmartQueryAskInput;
import com.starcon.platform.SmartQueryModels.SmartQueryCapabilities;
import com.starcon.platform.SmartQueryModels.SmartQueryCapability;
import com.starcon.platform.SmartQueryModels.SmartQueryConditions;
import com.starcon.platform.SmartQueryModels.SmartQueryCurrencyValue;
import com.starcon.platform.SmartQueryModels.SmartQueryDimensionColumn;
import com.starcon.platform.SmartQueryModels.SmartQueryDimensionValue;
import com.starcon.platform.SmartQueryModels.SmartQueryDimensionView;
import com.starcon.platform.SmartQueryModels.SmartQueryEvidence;
import com.starcon.platform.SmartQueryModels.SmartQueryFilter;
import com.starcon.platform.SmartQueryModels.SmartQueryGap;
import com.starcon.platform.SmartQueryModels.SmartQueryMeasureValue;
import com.starcon.platform.SmartQueryModels.SmartQueryMetricColumn;
import com.starcon.platform.SmartQueryModels.SmartQueryMetricView;
import com.starcon.platform.SmartQueryModels.SmartQueryNextAction;
import com.starcon.platform.SmartQueryModels.SmartQueryPage;
import com.starcon.platform.SmartQueryModels.SmartQueryQueryView;
import com.starcon.platform.SmartQueryModels.SmartQueryResult;
import com.starcon.platform.SmartQueryModels.SmartQueryResultRow;
import com.starcon.platform.SmartQueryModels.SmartQuerySemanticLayer;
import com.starcon.platform.SmartQueryModels.SmartQuerySort;
import com.starcon.platform.SmartQuerySemanticCatalog.DimensionDefinition;
import com.starcon.platform.SmartQuerySemanticCatalog.MetricDefinition;
import java.math.BigDecimal;
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
 * 18.4.1.2「智能查询」的服务层。
 *
 * <p>蓝图加粗句：「<b>自然语言只映射到受控语义层指标，不生成任意 SQL</b>」。
 *
 * <p>⇒ 本服务的**结构性保证**：
 *
 * <ol>
 *   <li><b>解析只落到封闭词表上</b>（{@link SmartQuerySemanticCatalog}）：指标 12 条、
 *       维度 5 条、运算符 5 个、币种码 5 个、时间窗口 4 个 —— 词表外的东西**解析不出来**；
 *   <li><b>解析不出来就不出结论</b>：{@code resolved=false} + 「缺什么 + 归谁」，
 *       <b>不返回任何数字</b>（不是返回 0，也不是返回别的指标顶替）；
 *   <li><b>用户输入永远不进 SQL 文本</b>：本模块的 jOOQ 查询里没有一处拼接问句；
 *       问句只在<b>词表 → 码</b>这一步被消化掉，之后全程是码与绑定参数；
 *   <li><b>执行委托 13.6.1.1 的查询引擎</b>（{@link SmartQueryReportingPort}）——
 *       口径唯一，本模块不重算；
 *   <li><b>结果表的每一行都带明细依据</b>（{@code platform_smart_query_evidence}）——
 *       真表名 / 真字段名 / 事实引用 / 可点的明细路由 / 口径说明 / 快照时间。
 * </ol>
 *
 * <p>🔴 <b>相对时间用 DB 的 now() 算</b>（{@link #today()}）：不拿 JVM 墙钟和 DB 时钟比大小
 * （跨时钟域比较会产出恒定的假红）。
 *
 * <p>🔴 <b>金额与数量全程十进制</b>：库里 {@code NUMERIC(19,6)}，对外是十进制文本。
 * <b>本模块没有一个 double。</b>
 */
@Service
public class SmartQueryService {

  /** 事实表真名（明细依据的第一跳）。 */
  static final String FACT_TABLE = "reporting_metric_fact";

  /** 事实表真字段名（明细依据的第二跳）。 */
  static final String FACT_FIELD = "value";

  /** 明细下钻页（前端**真实存在**的路由）。 */
  static final String DETAIL_ROUTE = "/analytics/custom-report";

  /** 一次查询最多取多少行结果（上游也是分页的，这里要一个确定的窗口）。 */
  static final int RESULT_PAGE_SIZE = 200;

  /** 语义层目录里指标的条数（能力声明里报出来）。 */
  private final DSLContext sql;
  private final ObjectMapper json;
  private final SmartQueryReportingPort reporting;

  public SmartQueryService(DSLContext sql, ObjectMapper json, SmartQueryReportingPort reporting) {
    this.sql = sql;
    this.json = json;
    this.reporting = reporting;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：哪些真接通、哪些还没接通、归谁。
   *
   * <p>🔴 **每一格都写明凭什么这么判**（端点 / 表 / 规则的具体名字），
   * 不写「已支持智能查询」这种没有依据的话。
   */
  public SmartQueryCapabilities capabilities() {
    var capabilities = new ArrayList<SmartQueryCapability>();
    capabilities.add(
        new SmartQueryCapability(
            "18.4.1.2",
            "受控语义层目录",
            true,
            "封闭词表：指标 "
                + SmartQuerySemanticCatalog.METRICS.size()
                + " 条（platform_smart_query_metric）+ 维度 "
                + SmartQuerySemanticCatalog.DIMENSIONS.size()
                + " 条（platform_smart_query_dimension），"
                + "指标码与 13.1.1.1 的口径表、维度码与 13.6.1.1 的白名单**逐字一致**",
            "",
            "问句按封闭词表确定性打分（强词 3 / 弱词 1，只命中弱词不算数）；"
                + "词表里没有的指标**解析不出来**，如实答「不支持这个问法」。"));
    capabilities.add(
        new SmartQueryCapability(
            "18.4.1.2",
            "自然语言转查询条件（筛选 / 聚合 / 排序）",
            true,
            "解析结果只落到词表里的码；筛选运算符限 EQ / IN / GTE / LTE / BETWEEN",
            "",
            "🔴 **不生成任意 SQL**：没有自由 SQL 入口，也没有「让模型生成 SQL」的通道；"
                + "问句只在「词表 → 码」这一步被消化掉。"));
    capabilities.add(
        new SmartQueryCapability(
            "18.4.1.2",
            "报表配置草稿与查询结果表",
            true,
            "下游 13.6.1.1 已交付：POST /reporting/custom-report/reports（建草稿）+ "
                + "POST /reporting/custom-report/reports/{reportId}/run（取结果）",
            "",
            "执行**委托** 13.6.1.1 的查询引擎（同一个指标在两边给出同一个数）；"
                + "草稿落在 13.6.1.1 的库里，本模块只存它的 id 与编码。"));
    capabilities.add(
        new SmartQueryCapability(
            "18.4.1.2",
            "常用查询",
            true,
            "platform_smart_query 的 saved 标志 + display_name（同一张表，不另立一份）",
            "",
            "常用查询 = **固化下来的一条查询条件**；重跑走同一条受控查询，不会重新猜。"));

    var gaps =
        List.of(
            new SmartQueryGap(
                "13.1.1.1",
                "平均 / 均值口径",
                "reporting",
                false,
                "语义层",
                "13.1.1.1 的口径表只有 SUM / LAST / DERIVED_DIFF / DERIVED_RATIO 四种聚合，"
                    + "**没有平均**。问「平均销售额」本模块如实答不支持（归 13.1.1.1），"
                    + "不拿总额 ÷ 笔数自己凑一个。"),
            new SmartQueryGap(
                "13.1.1.1",
                "库存周转率等库存类衍生指标",
                "reporting",
                false,
                "语义层",
                "口径表里登记的是**库存总额**（存量型，成本法），**没有库存周转率**。"
                    + "问「库存周转」本模块如实答不支持（归 13.1.1.1 / 13.5.1.1）。"),
            new SmartQueryGap(
                "13.6.1.1",
                "按法人 / 店铺**名称**筛选",
                "reporting",
                false,
                "语义层",
                "语义层不带法人 / 店铺的**名称档案**（只有 uuid）⇒ 本模块的受控词表"
                    + "只认**币种**与**日历日**两种筛选值；按名称筛选归 13.6.1.1。"),
            new SmartQueryGap(
                "18.4",
                "AI 供应商适配器",
                "platform",
                false,
                "本模块的上游",
                "本仓没有任何真实的 LLM 适配器 ⇒ 本模块的解析是**封闭词表 + 确定性打分**，"
                    + "**不是**模型自由理解；也因此不会「猜」出一个词表外的指标。"));

    return new SmartQueryCapabilities(capabilities, gaps);
  }

  // ================================================================ 语义层目录

  /**
   * 受控语义层目录（**真读库** + **实时核对上游**）。
   *
   * <p>🔴 「这个指标现在能不能用」由**上游口径表**说了算：
   * 本租户没在 {@code reporting_metric_definition} 里登记的指标，
   * 这里如实标 {@code available=false} 并写明归谁 —— 词表里有 ≠ 现在能用。
   */
  public SmartQuerySemanticLayer semanticLayer(String bearer, UUID tenantId) {
    ensureSemanticLayer(tenantId);
    var registered = registeredMetrics(bearer);
    var upstreamDimensions = upstreamDimensions(bearer);

    var metrics = new ArrayList<SmartQueryMetricView>();
    for (var definition : SmartQuerySemanticCatalog.METRICS) {
      var live =
          registered.stream()
              .filter(m -> m.metricCode() != null && m.metricCode().equals(definition.code()))
              .findFirst()
              .orElse(null);
      metrics.add(
          new SmartQueryMetricView(
              definition.code(),
              definition.name(),
              definition.unit(),
              definition.aggregation(),
              definition.currencyAware(),
              definition.strongKeywords(),
              definition.weakKeywords(),
              definition.sourceService(),
              definition.sourceEndpoint(),
              definition.caliber(),
              definition.detailRoute(),
              definition.ownerModule(),
              live != null,
              live == null
                  ? "该指标未在本租户的口径表（reporting_metric_definition）里登记 ⇒ 未接通；"
                      + "归 " + (definition.ownerModule().isBlank() ? "13.1.1.1" : definition.ownerModule())
                  : ""));
    }

    var dimensions = new ArrayList<SmartQueryDimensionView>();
    for (var definition : SmartQuerySemanticCatalog.DIMENSIONS) {
      var known =
          upstreamDimensions.stream()
              .anyMatch(d -> d.dimensionCode() != null && d.dimensionCode().equals(definition.code()));
      dimensions.add(
          new SmartQueryDimensionView(
              definition.code(),
              definition.name(),
              definition.valueKind(),
              definition.allowedOperators(),
              definition.strongKeywords(),
              definition.weakKeywords(),
              definition.ownerModule(),
              known,
              known ? "" : "上游维度白名单里没有这个维度码 ⇒ 未接通；归 13.6.1.1"));
    }

    var rules =
        List.of(
            "🔴 **自然语言只映射到受控语义层指标，不生成任意 SQL** —— 指标 "
                + SmartQuerySemanticCatalog.METRICS.size()
                + " 条、维度 "
                + SmartQuerySemanticCatalog.DIMENSIONS.size()
                + " 条，全部是封闭词表；问句只被消化成**码**，之后全程是码与绑定参数。",
            "🔴 **口径唯一** —— 指标口径来自 13.1.1.1 的 reporting_metric_definition，"
                + "维度白名单来自 13.6.1.1 的 5 个维度码；本模块**不重算**，执行也**委托** 13.6.1.1 的查询引擎。",
            "🔴 **解析不出来就不出结论** —— 词表里没有的指标 / 不支持的聚合 / 未登记的口径，"
                + "一律如实答「不支持这个问法 + 缺什么 + 归谁」，**不返回任何数字**。",
            "🔴 **不给依据的数字不能用于决策** —— 结果表的每一行都带一条明细依据"
                + "（真表名 reporting_metric_fact / 真字段名 value / 事实引用 / 可点的明细路由 / 口径说明 / 快照时间）。",
            "🔴 **涉币指标不跨币种比较** —— 结果按币种分组，组内排序；"
                + "「算不出来」是 **null**（显示「—」），不是 0。");

    return new SmartQuerySemanticLayer(metrics, dimensions, rules, capabilities().gaps());
  }

  // ================================================================ 主流程：提问

  /**
   * 执行一次智能查询（**主入口**）。
   *
   * @param bearer 调用方自己的会话（转发给 13.6.1.1；租户由上游按已认证成员关系解析）
   */
  public SmartQueryResult query(
      String bearer, UUID tenantId, UUID userId, SmartQueryAskInput input) {
    var question = input.question() == null ? "" : input.question().trim();
    if (question.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "问句不能为空");
    }
    if (question.length() > 500) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "问句最多 500 个字符");
    }
    ensureSemanticLayer(tenantId);

    // ---- 时间窗口（受控时间词；相对时间用 DB 的 now() 算）
    var periodCode = SmartQuerySemanticCatalog.periodOf(question);
    if (periodCode == null) periodCode = SmartQuerySemanticCatalog.DEFAULT_PERIOD;
    var window = window(periodCode);

    // ---- 解析（受控词表 + 确定性打分）
    var metricDefinition = resolveMetric(input.metricCode(), question);
    var dimensionDefinition = resolveDimension(input.dimensionCode(), question);
    var matchMode =
        (input.metricCode() != null && !input.metricCode().isBlank())
                || (input.dimensionCode() != null && !input.dimensionCode().isBlank())
            ? "EXPLICIT"
            : "KEYWORD";

    // 🔴 **解析不出来就不出结论**（不是返回一个空表，也不是返回别的指标顶替）。
    if (metricDefinition == null) {
      return unresolved(
          tenantId,
          userId,
          question,
          matchMode,
          window,
          "这句问法没有落到受控语义层的任何一个指标上。"
              + "受控词表里只有："
              + SmartQuerySemanticCatalog.METRICS.stream()
                  .map(MetricDefinition::code)
                  .toList()
              + "。换个说法或显式指定指标码再试。",
          "18.4.1.2");
    }

    // ---- 聚合口径的受控补充
    if (SmartQuerySemanticCatalog.asksForAverage(question)) {
      return unresolved(
          tenantId,
          userId,
          question,
          matchMode,
          window,
          "受控语义层**没有平均口径**（口径表只有 SUM / LAST / DERIVED_DIFF / DERIVED_RATIO）"
              + "⇒ 这一格算不出来，本模块不拿总额 ÷ 笔数自己凑一个。",
          "13.1.1.1");
    }
    if (SmartQuerySemanticCatalog.asksForSum(question) && !"SUM".equals(metricDefinition.aggregation())) {
      return unresolved(
          tenantId,
          userId,
          question,
          matchMode,
          window,
          "「" + metricDefinition.name() + "」是**"
              + metricDefinition.aggregation()
              + " 型**指标（余额不能累加）⇒ 求和这一格不成立，本模块不照算。",
          "13.1.1.1");
    }

    // ---- 指标在**当前租户**的口径表里登记了吗（没登记 ⇒ 未接通）
    var registered = registeredMetrics(bearer);
    var live =
        registered.stream()
            .filter(m -> m.metricCode() != null && m.metricCode().equals(metricDefinition.code()))
            .findFirst()
            .orElse(null);
    if (live == null) {
      return unresolved(
          tenantId,
          userId,
          question,
          matchMode,
          window,
          "指标「"
              + metricDefinition.name()
              + "」（"
              + metricDefinition.code()
              + "）未在本租户的口径表（reporting_metric_definition）里登记 ⇒ 未接通，"
              + "本模块不返回任何数字。",
          metricDefinition.ownerModule().isBlank() ? "13.1.1.1" : metricDefinition.ownerModule());
    }

    // ---- 维度（词表 + 上游白名单双确认）
    if (dimensionDefinition != null) {
      var known =
          upstreamDimensions(bearer).stream()
              .anyMatch(d -> d.dimensionCode() != null && d.dimensionCode().equals(dimensionDefinition.code()));
      if (!known) {
        return unresolved(
            tenantId,
            userId,
            question,
            matchMode,
            window,
            "维度「"
                + dimensionDefinition.name()
                + "」（"
                + dimensionDefinition.code()
                + "）不在 13.6.1.1 的维度白名单里 ⇒ 未接通。",
            "13.6.1.1");
      }
    }

    // ---- 查询条件（筛选 / 聚合 / 排序 / 时间窗口）
    var filters = buildFilters(question, window);
    var sort = buildSort(question, dimensionDefinition);

    // ---- 执行（委托 13.6.1.1 的查询引擎）
    var reportCode = reportCode(userId, metricDefinition, dimensionDefinition, filters, sort, window);
    var displayName =
        "智能查询草稿 · "
            + metricDefinition.name()
            + (dimensionDefinition == null ? " · 公司口径" : " · " + dimensionDefinition.name());
    var saveRequest =
        new SmartQueryReportingPort.ReportSaveRequest(
            reportCode,
            displayName,
            "18.4.1.2 智能查询生成的报表配置草稿；问句：" + truncate(question, 400),
            "PRIVATE",
            "TABLE",
            window.from(),
            window.to(),
            List.of(metricDefinition.code()),
            dimensionDefinition == null ? List.of() : List.of(dimensionDefinition.code()),
            filters.stream()
                .map(
                    f ->
                        new SmartQueryReportingPort.ReportFilterRequest(
                            f.dimensionCode(), f.operator(), f.valueText(), f.valueText2()))
                .toList());

    var report = reporting.createReport(bearer, saveRequest);
    var run =
        reporting.runReport(
            bearer,
            report.reportId(),
            window.from(),
            window.to(),
            0,
            RESULT_PAGE_SIZE);

    // ---- 排序（**本模块内**做：13.6.1.1 的配置里没有排序这一项）
    var sorted = sortRows(run.rows(), metricDefinition.code(), sort);

    // ---- 结果表 + 明细依据
    var dimensionColumns = new ArrayList<SmartQueryDimensionColumn>();
    if (dimensionDefinition != null) {
      dimensionColumns.add(
          new SmartQueryDimensionColumn(dimensionDefinition.code(), dimensionDefinition.name()));
    }
    var metricColumns = new ArrayList<SmartQueryMetricColumn>();
    for (var measure : run.measures() == null ? List.<SmartQueryReportingPort.ReportMetric>of() : run.measures()) {
      metricColumns.add(
          new SmartQueryMetricColumn(
              measure.metricCode(),
              measure.displayName(),
              measure.unit(),
              measure.currencyAware(),
              measure.description()));
    }

    var caliberNotes = new ArrayList<String>();
    if (run.caliberNotes() != null) caliberNotes.addAll(run.caliberNotes());
    caliberNotes.add("本模块**不重算口径**：执行委托 13.6.1.1 的查询引擎，"
        + "同一个指标在「智能查询」与「自定义报表」里给出同一个数。");
    caliberNotes.add("🔴 **涉币指标不跨币种比较**：结果按币种分组，组内排序；"
        + "「算不出来」是 null（显示「—」），不是 0。");
    if (sort.by().equals("DIMENSION")) {
      caliberNotes.add("排序按**维度值**（" + dimensionName(dimensionDefinition) + "）。");
    } else {
      caliberNotes.add("排序按**指标值**（" + metricDefinition.name() + "），"
          + (sort.direction().equals("DESC") ? "从高到低" : "从低到高") + "。");
    }

    var evidence = buildEvidence(sorted, metricDefinition, report.reportId(), run.dataUpdatedAt());
    var conditions =
        new SmartQueryConditions(
            metricDefinition.code(),
            metricDefinition.name(),
            metricDefinition.unit(),
            metricDefinition.aggregation(),
            metricDefinition.currencyAware(),
            dimensionDefinition == null ? "" : dimensionDefinition.code(),
            dimensionName(dimensionDefinition),
            filters,
            sort,
            window.periodCode(),
            window.from(),
            window.to(),
            metricDefinition.caliber());

    // ---- 落库（查询台账 + 明细依据）
    var queryId = insertQuery(
        tenantId,
        userId,
        question,
        matchMode,
        metricDefinition.code(),
        dimensionDefinition == null ? null : dimensionDefinition.code(),
        filters,
        sort,
        window,
        report.reportId(),
        report.reportCode(),
        true,
        "",
        "");
    insertEvidence(tenantId, queryId, evidence);
    return new SmartQueryResult(
        queryId.toString(),
        question,
        matchMode,
        true,
        conditions,
        report.reportId(),
        report.reportCode(),
        dimensionColumns,
        metricColumns,
        sorted,
        run.total(),
        run.page(),
        run.size(),
        run.status(),
        run.statusNote(),
        run.dataUpdatedAt(),
        run.dataUpdatedNote(),
        caliberNotes,
        evidence,
        nextActions(report.reportId()),
        "",
        "");
  }

  // ================================================================ 台账与常用查询

  /** 查询台账 / 常用查询清单（**真分页 + 真计数 + 真搜索**）。 */
  public SmartQueryPage list(
      UUID tenantId, UUID userId, String keyword, Boolean saved, int page, int size) {
    var effectiveSize = Math.min(Math.max(size, 1), 100);
    var effectivePage = Math.max(page, 0);
    var where = new StringBuilder(" where tenant_id = ? and owner_user_id = ?");
    var params = new ArrayList<Object>();
    params.add(tenantId);
    params.add(userId);
    if (saved != null) {
      where.append(" and saved = ?");
      params.add(saved);
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (question_text ilike ? or display_name ilike ?)");
      var pattern = "%" + keyword.trim() + "%";
      params.add(pattern);
      params.add(pattern);
    }
    var total =
        sql.fetchOne("select count(*) from platform_smart_query" + where, params.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select id,question_text,display_name,saved,match_mode,resolved,metric_code,dimension_code,"
                + "period_code,period_from,period_to,report_id,report_code,unavailable_reason,"
                + "owner_module,owner_user_id,created_at,updated_at from platform_smart_query"
                + where
                + " order by updated_at desc, id desc limit ? offset ?",
            concat(params, effectiveSize, effectivePage * effectiveSize));
    var views = new ArrayList<SmartQueryQueryView>();
    for (var row : rows) {
      var queryId = row.get("id", UUID.class);
      views.add(
          new SmartQueryQueryView(
              queryId.toString(),
              row.get("owner_user_id", UUID.class).toString(),
              row.get("question_text", String.class),
              row.get("display_name", String.class),
              Boolean.TRUE.equals(row.get("saved", Boolean.class)),
              row.get("match_mode", String.class),
              Boolean.TRUE.equals(row.get("resolved", Boolean.class)),
              nz(row.get("metric_code", String.class)),
              metricName(row.get("metric_code", String.class)),
              nz(row.get("dimension_code", String.class)),
              dimensionName(SmartQuerySemanticCatalog.dimension(row.get("dimension_code", String.class))),
              row.get("period_code", String.class),
              row.get("period_from", LocalDate.class),
              row.get("period_to", LocalDate.class),
              row.get("report_id", UUID.class) == null ? "" : row.get("report_id", UUID.class).toString(),
              nz(row.get("report_code", String.class)),
              nz(row.get("unavailable_reason", String.class)),
              nz(row.get("owner_module", String.class)),
              evidenceCount(tenantId, queryId),
              row.get("created_at", OffsetDateTime.class),
              row.get("updated_at", OffsetDateTime.class)));
    }
    return new SmartQueryPage(views, total, effectivePage, effectiveSize, nz(keyword), saved);
  }

  /** 读一条查询（**RLS 挡住别人的**）。 */
  public SmartQueryQueryView get(UUID tenantId, UUID userId, UUID queryId) {
    var row =
        sql.fetchOne(
            "select id,question_text,display_name,saved,match_mode,resolved,metric_code,dimension_code,"
                + "period_code,period_from,period_to,report_id,report_code,unavailable_reason,"
                + "owner_module,owner_user_id,created_at,updated_at from platform_smart_query"
                + " where id = ? and tenant_id = ? and owner_user_id = ?",
            queryId,
            tenantId,
            userId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "查询不存在或不属于当前用户");
    }
    return new SmartQueryQueryView(
        row.get("id", UUID.class).toString(),
        row.get("owner_user_id", UUID.class).toString(),
        row.get("question_text", String.class),
        row.get("display_name", String.class),
        Boolean.TRUE.equals(row.get("saved", Boolean.class)),
        row.get("match_mode", String.class),
        Boolean.TRUE.equals(row.get("resolved", Boolean.class)),
        nz(row.get("metric_code", String.class)),
        metricName(row.get("metric_code", String.class)),
        nz(row.get("dimension_code", String.class)),
        dimensionName(SmartQuerySemanticCatalog.dimension(row.get("dimension_code", String.class))),
        row.get("period_code", String.class),
        row.get("period_from", LocalDate.class),
        row.get("period_to", LocalDate.class),
        row.get("report_id", UUID.class) == null ? "" : row.get("report_id", UUID.class).toString(),
        nz(row.get("report_code", String.class)),
        nz(row.get("unavailable_reason", String.class)),
        nz(row.get("owner_module", String.class)),
        evidenceCount(tenantId, row.get("id", UUID.class)),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  /** 保存为常用查询（**只有发起人本人**能存自己的查询）。 */
  public SmartQueryQueryView saveFavorite(
      UUID tenantId, UUID userId, UUID queryId, String displayName) {
    var name = displayName == null ? "" : displayName.trim();
    if (name.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "常用查询的名字不能为空");
    }
    if (name.length() > 120) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "常用查询的名字最长 120 字");
    }
    var updated =
        sql.execute(
            "update platform_smart_query set display_name = ?, saved = true, updated_at = now()"
                + " where id = ? and tenant_id = ? and owner_user_id = ?",
            name,
            queryId,
            tenantId,
            userId);
    if (updated == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "查询不存在或不属于当前用户");
    }
    return get(tenantId, userId, queryId);
  }

  /** 取消常用查询（台账行留着 —— 它是一次真实的查询记录）。 */
  public SmartQueryQueryView removeFavorite(UUID tenantId, UUID userId, UUID queryId) {
    var updated =
        sql.execute(
            "update platform_smart_query set saved = false, display_name = '', updated_at = now()"
                + " where id = ? and tenant_id = ? and owner_user_id = ?",
            queryId,
            tenantId,
            userId);
    if (updated == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "查询不存在或不属于当前用户");
    }
    return get(tenantId, userId, queryId);
  }

  /**
   * 重跑一条查询（**沿用它当初解析出来的受控条件**，不重新猜）。
   *
   * <p>⚠️ 重跑**不新建**报表草稿：更新同一份草稿（13.6.1.1 的 PUT）⇒
   * 常用查询不会在自定义报表的清单里堆出一堆同名草稿。
   */
  public SmartQueryResult rerun(String bearer, UUID tenantId, UUID userId, UUID queryId) {
    var row =
        sql.fetchOne(
            "select id,question_text,match_mode,metric_code,dimension_code,filters,sort_spec,"
                + "period_code,period_from,period_to,report_id,report_code,resolved"
                + " from platform_smart_query where id = ? and tenant_id = ? and owner_user_id = ?",
            queryId,
            tenantId,
            userId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "查询不存在或不属于当前用户");
    }
    if (!Boolean.TRUE.equals(row.get("resolved", Boolean.class))) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "这条查询没有解析出受控条件（" + row.get("unavailable_reason", String.class) + "）");
    }
    var metricDefinition = SmartQuerySemanticCatalog.metric(row.get("metric_code", String.class));
    if (metricDefinition == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "这条查询的指标码已不在受控词表里");
    }
    var dimensionDefinition = SmartQuerySemanticCatalog.dimension(row.get("dimension_code", String.class));
    var filters = readFilters(row.get("filters", org.jooq.JSONB.class));
    var sort = readSort(row.get("sort_spec", org.jooq.JSONB.class));
    var window =
        new Window(
            row.get("period_code", String.class),
            row.get("period_from", LocalDate.class),
            row.get("period_to", LocalDate.class));
    var reportId = row.get("report_id", UUID.class);

    var saveRequest =
        new SmartQueryReportingPort.ReportSaveRequest(
            row.get("report_code", String.class),
            "智能查询草稿 · "
                + metricDefinition.name()
                + (dimensionDefinition == null ? " · 公司口径" : " · " + dimensionDefinition.name()),
            "18.4.1.2 智能查询生成的报表配置草稿；问句：" + truncate(row.get("question_text", String.class), 400),
            "PRIVATE",
            "TABLE",
            window.from(),
            window.to(),
            List.of(metricDefinition.code()),
            dimensionDefinition == null ? List.of() : List.of(dimensionDefinition.code()),
            filters.stream()
                .map(
                    f ->
                        new SmartQueryReportingPort.ReportFilterRequest(
                            f.dimensionCode(), f.operator(), f.valueText(), f.valueText2()))
                .toList());
    var report = reporting.updateReport(bearer, reportId.toString(), saveRequest);
    var run =
        reporting.runReport(bearer, report.reportId(), window.from(), window.to(), 0, RESULT_PAGE_SIZE);

    var sorted = sortRows(run.rows(), metricDefinition.code(), sort);
    var evidence = buildEvidence(sorted, metricDefinition, report.reportId(), run.dataUpdatedAt());
    var caliberNotes = new ArrayList<String>();
    if (run.caliberNotes() != null) caliberNotes.addAll(run.caliberNotes());
    caliberNotes.add("这是**重跑**：沿用这条查询当初解析出来的受控条件（指标 / 维度 / 筛选 / 排序 / 时间窗口）。");
    var conditions =
        new SmartQueryConditions(
            metricDefinition.code(),
            metricDefinition.name(),
            metricDefinition.unit(),
            metricDefinition.aggregation(),
            metricDefinition.currencyAware(),
            dimensionDefinition == null ? "" : dimensionDefinition.code(),
            dimensionName(dimensionDefinition),
            filters,
            sort,
            window.periodCode(),
            window.from(),
            window.to(),
            metricDefinition.caliber());

    sql.execute("delete from platform_smart_query_evidence where query_id = ? and tenant_id = ?", queryId, tenantId);
    insertEvidence(tenantId, queryId, evidence);
    sql.execute(
        "update platform_smart_query set updated_at = now() where id = ? and tenant_id = ?", queryId, tenantId);

    var dimensionColumns = new ArrayList<SmartQueryDimensionColumn>();
    if (dimensionDefinition != null) {
      dimensionColumns.add(
          new SmartQueryDimensionColumn(dimensionDefinition.code(), dimensionDefinition.name()));
    }
    var metricColumns = new ArrayList<SmartQueryMetricColumn>();
    for (var measure : run.measures() == null ? List.<SmartQueryReportingPort.ReportMetric>of() : run.measures()) {
      metricColumns.add(
          new SmartQueryMetricColumn(
              measure.metricCode(),
              measure.displayName(),
              measure.unit(),
              measure.currencyAware(),
              measure.description()));
    }
    return new SmartQueryResult(
        queryId.toString(),
        row.get("question_text", String.class),
        row.get("match_mode", String.class),
        true,
        conditions,
        report.reportId(),
        report.reportCode(),
        dimensionColumns,
        metricColumns,
        sorted,
        run.total(),
        run.page(),
        run.size(),
        run.status(),
        run.statusNote(),
        run.dataUpdatedAt(),
        run.dataUpdatedNote(),
        caliberNotes,
        evidence,
        nextActions(report.reportId()),
        "",
        "");
  }

  // ================================================================ 解析

  /** 指标：显式指定优先（必须命中词表），否则按封闭词表确定性匹配。 */
  private MetricDefinition resolveMetric(String explicitCode, String question) {
    if (explicitCode != null && !explicitCode.isBlank()) {
      var definition = SmartQuerySemanticCatalog.metric(explicitCode);
      if (definition == null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "未知指标码：" + explicitCode + "（受控语义层里没有这一条）");
      }
      return definition;
    }
    var match = SmartQuerySemanticCatalog.matchMetric(question);
    return match.matched() ? match.definition() : null;
  }

  /** 维度：显式指定优先（必须命中词表），否则按封闭词表确定性匹配。 */
  private DimensionDefinition resolveDimension(String explicitCode, String question) {
    if (explicitCode != null && !explicitCode.isBlank()) {
      var definition = SmartQuerySemanticCatalog.dimension(explicitCode);
      if (definition == null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "未知维度码：" + explicitCode + "（受控语义层里没有这一条）");
      }
      return definition;
    }
    var match = SmartQuerySemanticCatalog.matchDimension(question);
    return match.matched() ? match.definition() : null;
  }

  /** 筛选条件：**只有**日历日与币种两种（受控词表里可解析的值就这两种）。 */
  private List<SmartQueryFilter> buildFilters(String question, Window window) {
    var filters = new ArrayList<SmartQueryFilter>();
    filters.add(new SmartQueryFilter("BUCKET_DATE", "BETWEEN", window.from().toString(), window.to().toString()));
    var currency = SmartQuerySemanticCatalog.currencyOf(question);
    if (currency != null) {
      filters.add(new SmartQueryFilter("CURRENCY", "EQ", currency, null));
    }
    return filters;
  }

  /** 排序：按指标值（默认从高到低）或按维度值。 */
  private SmartQuerySort buildSort(String question, DimensionDefinition dimensionDefinition) {
    var by = "METRIC";
    var q = question.toLowerCase(Locale.ROOT);
    if (dimensionDefinition != null && (q.contains("排序") || q.contains("排列"))) {
      by = "DIMENSION";
    }
    var direction = SmartQuerySemanticCatalog.sortDirectionOf(question);
    if (direction == null) direction = "DESC";
    return new SmartQuerySort(by, direction);
  }

  // ================================================================ 排序

  /**
   * 给结果表排序（**本模块内**做：13.6.1.1 的报表配置里没有排序这一项）。
   *
   * <p>🔴 <b>涉币指标不跨币种比较</b>：结果先按「这一行的币种集合」分组，
   * 组与组之间按币种码排（确定），组内才按指标值排。
   * 一个组里有多币种（同一行有多个币种取值）⇒ **无法比较** ⇒ 保持原序，
   * 并在口径说明里点名。
   */
  private List<SmartQueryResultRow> sortRows(
      List<SmartQueryReportingPort.ReportRow> rows, String metricCode, SmartQuerySort sort) {
    var source = rows == null ? List.<SmartQueryReportingPort.ReportRow>of() : rows;
    var indexed = new ArrayList<IndexedRow>();
    for (int index = 0; index < source.size(); index++) {
      var row = source.get(index);
      var measure = findMeasure(row, metricCode);
      var signature = currencySignature(measure);
      var value = singleValue(measure);
      indexed.add(new IndexedRow(index, signature, value, row));
    }

    // 分组（按币种集合）—— 组之间按币种码排（确定）。
    var groups = new LinkedHashMap<String, List<IndexedRow>>();
    for (var item : indexed) {
      groups.computeIfAbsent(item.signature(), ignored -> new ArrayList<>()).add(item);
    }
    var signatures = new ArrayList<>(groups.keySet());
    signatures.sort(Comparator.naturalOrder());

    var out = new ArrayList<SmartQueryResultRow>();
    var seq = 1;
    for (var signature : signatures) {
      var group = groups.get(signature);
      if (sort.by().equals("DIMENSION")) {
        group.sort(Comparator.comparing(IndexedRow::dimensionKey).thenComparingInt(IndexedRow::index));
      } else {
        // 🔴 **算不出来的行排在后面**（不是当成 0 参与排序）：分两组，
        //   有值的按指标值排（方向由受控词表决定），没值的保持原序接在后面。
        var withValue = new ArrayList<IndexedRow>();
        var withoutValue = new ArrayList<IndexedRow>();
        for (var item : group) {
          if (item.value() == null) {
            withoutValue.add(item);
          } else {
            withValue.add(item);
          }
        }
        Comparator<IndexedRow> comparator =
            Comparator.comparing(
                (IndexedRow item) -> item.value(),
                Comparator.nullsLast(Comparator.<BigDecimal>naturalOrder()));
        if (sort.direction().equals("DESC")) comparator = comparator.reversed();
        withValue.sort(comparator.thenComparingInt(IndexedRow::index));
        group = new ArrayList<>(withValue);
        group.addAll(withoutValue);
      }
      for (var item : group) {
        out.add(toResultRow(seq++, item.row()));
      }
    }
    return out;
  }

  /** 一行的（币种集合 / 单一取值 / 维度键 / 原始行）。 */
  private record IndexedRow(
      int index, String signature, BigDecimal value, SmartQueryReportingPort.ReportRow row) {

    String dimensionKey() {
      if (row.dimensions() == null || row.dimensions().isEmpty()) return "";
      var cell = row.dimensions().get(0);
      return nz(cell.value());
    }
  }

  private SmartQueryReportingPort.ReportMeasureCell findMeasure(
      SmartQueryReportingPort.ReportRow row, String metricCode) {
    if (row.measures() == null) return null;
    for (var measure : row.measures()) {
      if (measure.metricCode() != null && measure.metricCode().equals(metricCode)) return measure;
    }
    return row.measures().isEmpty() ? null : row.measures().get(0);
  }

  /** 这一行的币种集合（排序的分组键）。 */
  private String currencySignature(SmartQueryReportingPort.ReportMeasureCell measure) {
    if (measure == null || measure.values() == null || measure.values().isEmpty()) return "";
    var codes = new ArrayList<String>();
    for (var value : measure.values()) {
      codes.add(nz(value.currency()));
    }
    codes.sort(Comparator.naturalOrder());
    return String.join(",", codes);
  }

  /** 只有一个币种取值时的那个值（**算不出来就是 null，不是 0**）。 */
  private BigDecimal singleValue(SmartQueryReportingPort.ReportMeasureCell measure) {
    if (measure == null || measure.values() == null || measure.values().size() != 1) return null;
    return measure.values().get(0).value();
  }

  private SmartQueryResultRow toResultRow(int seq, SmartQueryReportingPort.ReportRow row) {
    var dimensions = new ArrayList<SmartQueryDimensionValue>();
    if (row.dimensions() != null) {
      for (var cell : row.dimensions()) {
        dimensions.add(
            new SmartQueryDimensionValue(cell.dimensionCode(), cell.label(), nz(cell.value())));
      }
    }
    var measures = new ArrayList<SmartQueryMeasureValue>();
    if (row.measures() != null) {
      for (var measure : row.measures()) {
        var values = new ArrayList<SmartQueryCurrencyValue>();
        if (measure.values() != null) {
          for (var value : measure.values()) {
            values.add(
                new SmartQueryCurrencyValue(
                    nz(value.currency()),
                    value.value() == null ? null : value.value().toPlainString(),
                    nz(value.availability()),
                    nz(value.availabilityNote())));
          }
        }
        measures.add(
            new SmartQueryMeasureValue(
                measure.metricCode(),
                measure.displayName(),
                measure.unit(),
                measure.currencyAware(),
                values,
                nz(measure.availability()),
                nz(measure.availabilityNote())));
      }
    }
    return new SmartQueryResultRow(seq, dimensions, measures);
  }

  // ================================================================ 明细依据

  /**
   * 给结果表的每一行造一条明细依据。
   *
   * <p>🔴 <b>真表名 + 真字段名</b>：事实住在 13.1.1.1 的 {@code reporting_metric_fact}，
   * 数字在 {@code value} 列上 —— 不是一个编出来的字段名。
   */
  private List<SmartQueryEvidence> buildEvidence(
      List<SmartQueryResultRow> rows,
      MetricDefinition metricDefinition,
      String reportId,
      OffsetDateTime dataUpdatedAt) {
    var snapshot = dataUpdatedAt == null ? OffsetDateTime.now(ZoneOffset.UTC) : dataUpdatedAt;
    var evidence = new ArrayList<SmartQueryEvidence>();
    for (var row : rows) {
      var dimensionRef = new StringBuilder("metric_code=").append(metricDefinition.code());
      var dimensionLabel = new StringBuilder();
      for (var dimension : row.dimensions()) {
        dimensionRef
            .append("; ")
            .append(dimension.dimensionCode())
            .append("=")
            .append(dimension.value());
        dimensionLabel.append(dimension.label()).append(" ").append(dimension.value()).append(" ");
      }
      if (row.dimensions().isEmpty()) {
        dimensionRef.append("; scope_type=COMPANY");
        dimensionLabel.append("公司口径");
      }
      if (row.measures().isEmpty()) {
        // 🔴 **每一行都要有依据**：这一行没有度量取值 ⇒ 也给一条（值是 null = 算不出来）。
        evidence.add(
            new SmartQueryEvidence(
                "reporting",
                FACT_TABLE,
                FACT_FIELD,
                truncate(dimensionRef.toString(), 200),
                truncate(dimensionLabel.toString().trim(), 200),
                metricDefinition.code(),
                null,
                unitLabel(metricDefinition),
                detailRoute(reportId),
                "打开这份报表草稿",
                truncate(
                    metricDefinition.caliber() + " 🔴 这一行**没有度量取值**（算不出来，不是 0）。", 500),
                snapshot));
        continue;
      }
      for (var measure : row.measures()) {
        if (measure.values().isEmpty()) {
          evidence.add(
              new SmartQueryEvidence(
                  "reporting",
                  FACT_TABLE,
                  FACT_FIELD,
                  truncate(dimensionRef.toString(), 200),
                  truncate(dimensionLabel.toString().trim(), 200),
                  metricDefinition.code(),
                  null,
                  unitLabel(metricDefinition),
                  detailRoute(reportId),
                  "打开这份报表草稿",
                  truncate(
                      metricDefinition.caliber()
                          + " 🔴 这一格**算不出来**（不是 0）："
                          + (measure.availabilityNote().isBlank()
                              ? measure.availability()
                              : measure.availabilityNote()),
                      500),
                  snapshot));
          continue;
        }
        for (var value : measure.values()) {
          evidence.add(
              new SmartQueryEvidence(
                  "reporting",
                  FACT_TABLE,
                  FACT_FIELD,
                  truncate(dimensionRef.toString(), 200),
                  truncate(dimensionLabel.toString().trim(), 200),
                  metricDefinition.code(),
                  value.value(),
                  valueUnit(metricDefinition, value.currency()),
                  detailRoute(reportId),
                  "打开这份报表草稿",
                  truncate(
                      metricDefinition.caliber()
                          + " 币种："
                          + (value.currency().isBlank() ? "与货币无关的跨币种合计" : value.currency())
                          + (value.availabilityNote().isBlank() ? "" : "；" + value.availabilityNote()),
                      500),
                  snapshot));
        }
      }
    }
    return evidence;
  }

  /** 证据的落库形态（数值仍是 BigDecimal；对外才是十进制文本）。 */
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
      String caliberNote,
      OffsetDateTime snapshotAt) {}

  private void insertEvidence(UUID tenantId, UUID queryId, List<SmartQueryEvidence> evidence) {
    var seq = 1;
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    for (var item : evidence) {
      sql.execute(
          "insert into platform_smart_query_evidence(id,tenant_id,query_id,seq,fact_service,"
              + "fact_table,fact_field,fact_ref,fact_label,metric_code,metric_value,metric_unit,"
              + "detail_route,detail_label,caliber_note,snapshot_at,created_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(),
          tenantId,
          queryId,
          seq++,
          item.factService(),
          item.factTable(),
          item.factField(),
          item.factRef(),
          item.factLabel(),
          item.metricCode(),
          item.metricValue() == null ? null : new BigDecimal(item.metricValue()),
          item.metricUnit(),
          item.detailRoute(),
          item.detailLabel(),
          item.caliberNote(),
          item.snapshotAt(),
          now);
    }
  }

  // ================================================================ 落库

  /** 记一次查询（**出没出结论都记** —— 台账里也要能查「当时为什么没查出来」）。 */
  private UUID insertQuery(
      UUID tenantId,
      UUID userId,
      String question,
      String matchMode,
      String metricCode,
      String dimensionCode,
      List<SmartQueryFilter> filters,
      SmartQuerySort sort,
      Window window,
      String reportId,
      String reportCode,
      boolean resolved,
      String unavailableReason,
      String ownerModule) {
    var queryId = UUID.randomUUID();
    sql.execute(
        "insert into platform_smart_query(id,tenant_id,owner_user_id,question_text,display_name,"
            + "saved,match_mode,metric_code,dimension_code,filters,sort_spec,period_code,"
            + "period_from,period_to,report_id,report_code,resolved,unavailable_reason,"
            + "owner_module,created_at,updated_at)"
            + " values(?,?,?,?,'',false,?,?,?,?::jsonb,?::jsonb,?,?,?,?::uuid,?,?,?,?,now(),now())",
        queryId,
        tenantId,
        userId,
        truncate(question, 500),
        matchMode,
        metricCode,
        dimensionCode,
        jsonValue(filters),
        jsonValue(sort),
        window.periodCode(),
        window.from(),
        window.to(),
        reportId,
        truncate(reportCode, 64),
        resolved,
        truncate(unavailableReason, 500),
        truncate(ownerModule, 32));
    return queryId;
  }

  /** 没解析出来的那一次（**不出结论，也不造报表草稿**）。 */
  private SmartQueryResult unresolved(
      UUID tenantId,
      UUID userId,
      String question,
      String matchMode,
      Window window,
      String reason,
      String ownerModule) {
    var queryId =
        insertQuery(
            tenantId,
            userId,
            question,
            matchMode,
            null,
            null,
            List.of(),
            new SmartQuerySort("METRIC", "DESC"),
            window,
            null,
            "",
            false,
            reason,
            ownerModule);
    var caliber =
        "这一轮**没有出结论**：自然语言只映射到受控语义层指标，而这句问法没有落到词表里的条目上。"
            + "时间范围："
            + window.from()
            + " ~ "
            + window.to()
            + "（"
            + periodLabel(window.periodCode())
            + "）。";
    return new SmartQueryResult(
        queryId.toString(),
        question,
        matchMode,
        false,
        null,
        "",
        "",
        List.of(),
        List.of(),
        List.of(),
        0,
        0,
        0,
        "NOT_RESOLVED",
        reason,
        null,
        "",
        List.of(caliber),
        List.of(),
        List.of(),
        reason,
        ownerModule);
  }

  /** 把受控语义层目录**幂等**落到本租户名下。 */
  private void ensureSemanticLayer(UUID tenantId) {
    for (var definition : SmartQuerySemanticCatalog.METRICS) {
      sql.execute(
          "insert into platform_smart_query_metric(id,tenant_id,metric_code,display_name,unit,"
              + "aggregation,currency_aware,strong_keywords,weak_keywords,source_service,"
              + "source_endpoint,source_caliber,detail_route,owner_module,enabled,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?::text[],?::text[],?,?,?,?,?,true,now(),now())"
              + " on conflict (tenant_id, metric_code) do nothing",
          UUID.randomUUID(),
          tenantId,
          definition.code(),
          definition.name(),
          definition.unit(),
          definition.aggregation(),
          definition.currencyAware(),
          pgTextArray(definition.strongKeywords()),
          pgTextArray(definition.weakKeywords()),
          definition.sourceService(),
          definition.sourceEndpoint(),
          definition.caliber(),
          definition.detailRoute(),
          definition.ownerModule());
    }
    for (var definition : SmartQuerySemanticCatalog.DIMENSIONS) {
      sql.execute(
          "insert into platform_smart_query_dimension(id,tenant_id,dimension_code,display_name,"
              + "value_kind,allowed_operators,strong_keywords,weak_keywords,owner_module,enabled,"
              + "created_at,updated_at)"
              + " values(?,?,?,?,?,?::text[],?::text[],?::text[],?,true,now(),now())"
              + " on conflict (tenant_id, dimension_code) do nothing",
          UUID.randomUUID(),
          tenantId,
          definition.code(),
          definition.name(),
          definition.valueKind(),
          pgTextArray(definition.allowedOperators()),
          pgTextArray(definition.strongKeywords()),
          pgTextArray(definition.weakKeywords()),
          definition.ownerModule());
    }
  }

  // ================================================================ 上游目录

  /** 上游登记的指标清单（**当前租户**）。 */
  private List<SmartQueryReportingPort.ReportMetric> registeredMetrics(String bearer) {
    return reporting.metrics(bearer);
  }

  /** 上游的维度白名单。 */
  private List<SmartQueryReportingPort.ReportDimension> upstreamDimensions(String bearer) {
    return reporting.dimensions(bearer);
  }

  // ================================================================ 时间窗口

  /** 一个时间窗口。 */
  private record Window(String periodCode, LocalDate from, LocalDate to) {}

  /**
   * **DB 的「今天」**。
   *
   * <p>🔴 相对时间（上周 / 本月）必须与数据落在**同一个时钟域**：
   * 这里用 {@code now() at time zone 'UTC'} 取，而不是 JVM 墙钟。
   */
  private LocalDate today() {
    return sql.fetchOne("select (now() at time zone 'UTC')::date").get(0, LocalDate.class);
  }

  /** 按代号解析窗口（起止日都由 DB 的「今天」推导）。 */
  private Window window(String periodCode) {
    var today = today();
    return switch (periodCode) {
      case SmartQuerySemanticCatalog.LAST_7_DAYS -> new Window(periodCode, today.minusDays(6), today);
      case SmartQuerySemanticCatalog.LAST_WEEK -> {
        // 上周 = 上一自然周（周一至周日）。🔴 口径见 PeriodWindow：
        // 不能用 TemporalAdjusters.previous(MONDAY)（那是「最近的周一」，终点会落在未来）。
        var monday = PeriodWindow.lastWeekMonday(today);
        yield new Window(periodCode, monday, monday.plusDays(6));
      }
      case SmartQuerySemanticCatalog.THIS_MONTH ->
          new Window(periodCode, today.with(TemporalAdjusters.firstDayOfMonth()), today);
      default -> new Window(periodCode, today.minusDays(29), today);
    };
  }

  private static String periodLabel(String code) {
    return switch (code) {
      case SmartQuerySemanticCatalog.LAST_7_DAYS -> "最近 7 天";
      case SmartQuerySemanticCatalog.LAST_WEEK -> "上周（上一自然周，周一至周日）";
      case SmartQuerySemanticCatalog.THIS_MONTH -> "本月（自然月至今）";
      default -> "最近 30 天";
    };
  }

  // ================================================================ 工具

  /** 报表编码：**同一组条件 + 同一个发起人 ⇒ 同一份草稿**（重跑不新建）。 */
  private String reportCode(
      UUID userId,
      MetricDefinition metric,
      DimensionDefinition dimension,
      List<SmartQueryFilter> filters,
      SmartQuerySort sort,
      Window window) {
    var raw =
        userId
            + "|"
            + metric.code()
            + "|"
            + (dimension == null ? "" : dimension.code())
            + "|"
            + filters.stream()
                .map(f -> f.dimensionCode() + f.operator() + f.valueText() + nz(f.valueText2()))
                .reduce("", (a, b) -> a + "," + b)
            + "|"
            + sort.by()
            + sort.direction()
            + "|"
            + window.from()
            + "~"
            + window.to();
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      var bytes = digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      var sb = new StringBuilder("SQ-");
      for (int index = 0; index < 8; index++) {
        sb.append(String.format("%02X", bytes[index]));
      }
      return sb.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 明细链接（**前端真实存在**的路由 + 这份草稿的 id）。 */
  private String detailRoute(String reportId) {
    return DETAIL_ROUTE + "?reportId=" + enc(nz(reportId));
  }

  private List<SmartQueryNextAction> nextActions(String reportId) {
    return List.of(
        new SmartQueryNextAction("打开这份报表草稿", detailRoute(reportId), "13.6.1.1"),
        new SmartQueryNextAction("打开自定义报表", DETAIL_ROUTE, "13.6.1.1"),
        new SmartQueryNextAction("打开智能查询工作区", "/platform/smart-query", "18.4.1.2"));
  }

  /** 单位标签：涉币指标用币种码当单位（金额的单位就是那个币种）。 */
  private static String unitLabel(MetricDefinition definition) {
    return "CURRENCY".equals(definition.unit()) ? "CURRENCY" : definition.unit();
  }

  /** 一个取值的单位：涉币指标的单位是**那个币种**（空串 = 与货币无关的合计）。 */
  private static String valueUnit(MetricDefinition definition, String currency) {
    if (!"CURRENCY".equals(definition.unit())) return definition.unit();
    return currency == null ? "" : currency;
  }

  private int evidenceCount(UUID tenantId, UUID queryId) {
    return sql.fetchOne(
            "select count(*) from platform_smart_query_evidence where query_id = ? and tenant_id = ?",
            queryId,
            tenantId)
        .get(0, Integer.class);
  }

  private static String metricName(String code) {
    var definition = SmartQuerySemanticCatalog.metric(code);
    return definition == null ? "" : definition.name();
  }

  private static String dimensionName(DimensionDefinition definition) {
    return definition == null ? "" : definition.name();
  }

  private List<SmartQueryFilter> readFilters(org.jooq.JSONB value) {
    if (value == null) return List.of();
    try {
      var node = json.readTree(value.data());
      var out = new ArrayList<SmartQueryFilter>();
      for (var item : node) {
        out.add(
            new SmartQueryFilter(
                item.path("dimensionCode").asText(""),
                item.path("operator").asText(""),
                item.path("valueText").asText(""),
                item.path("valueText2").asText("")));
      }
      return out;
    } catch (Exception e) {
      return List.of();
    }
  }

  private SmartQuerySort readSort(org.jooq.JSONB value) {
    if (value == null) return new SmartQuerySort("METRIC", "DESC");
    try {
      var node = json.readTree(value.data());
      return new SmartQuerySort(
          node.path("by").asText("METRIC"), node.path("direction").asText("DESC"));
    } catch (Exception e) {
      return new SmartQuerySort("METRIC", "DESC");
    }
  }

  private String jsonValue(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      return "{}";
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

  private static String truncate(String value, int max) {
    if (value == null) return "";
    return value.length() <= max ? value : value.substring(0, max);
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static Object[] concat(List<Object> params, int size, int offset) {
    var out = new ArrayList<Object>(params);
    out.add(size);
    out.add(offset);
    return out.toArray();
  }
}
