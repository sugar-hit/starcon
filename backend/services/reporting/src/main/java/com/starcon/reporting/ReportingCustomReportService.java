package com.starcon.reporting;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 13.6.1.1 自定义报表的**服务层**（蓝图 13.6.1.1）。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 * **职责**：业务人员自助构建报表：选择数据源（受控的语义层指标，不是直连库表）、维度、度量、
 * 筛选、图表类型、保存与共享、订阅推送。<br>
 * **输入**：报表配置；可用的语义层指标清单。<br>
 * **输出**：报表结果 + 分享链接 + 定时订阅。<br>
 * **关系**：上游 = 语义层指标定义；下游 = 13.6.2.1 导出。<b>只允许基于语义层指标自助，
 * 不开放任意 SQL</b> —— 否则报表口径会分裂成几十个版本，最终没人知道该信哪个。
 * </blockquote>
 *
 * <p>🔴 **本类不重算口径**：口径（单位 / 聚合方式 / 分子分母）全部来自
 * {@code reporting_metric_definition}（13.1.1.1 经营总览的口径唯一表）。
 * 派生型指标（DERIVED_RATIO / DERIVED_DIFF）是**按登记表里的分子分母分别取数再在服务端算**，
 * 而不是在这里写死一句公式 —— 也不会自己挑别的列凑一个「差不多的比率」。
 *
 * <p>🔴 **用户输入只作为参数值**：SQL 片段只来自
 * {@link ReportingCustomReportCatalog} 的枚举常量（维度白名单 + 运算符白名单），
 * 筛选值一律走 **jOOQ 绑定参数**，**绝不拼进 SQL 文本**。
 *
 * <p>🔴 **「算不出来」与「等于 0」必须分开**：分母为 0、事实不存在、指标未登记，
 * 三种情况一律返回 {@code null} / {@code NO_DATA} / {@code NOT_COMPARABLE}，**不用 0 冒充**。
 *
 * <p>🔴 **不跨币种相加**：涉币指标按币种分别报；只有与货币无关的量给一个跨币种合计
 * （币种显示为空串 —— 空串 = 与币种无关，不是「币种未知」）。
 *
 * <p>⚠️ **本模块只负责「订阅计划 + 待投递记录」**：真正的导出（Excel / CSV / PDF）与
 * 邮件 / IM 投递属 13.6.2.1（未交付）⇒ capabilities 里显式标 {@code available=false}。
 */
@Service
public class ReportingCustomReportService {

  /** 派生型求值的深度上限（毛利率 → 毛利额 → 销售额/成本 = 3 层）。 */
  private static final int MAX_DERIVE_DEPTH = 4;

  /** 默认时间窗口的天数（含今天）。 */
  private static final int DEFAULT_WINDOW_DAYS = 30;

  /** 报表结果的单页最大行数。 */
  private static final int MAX_PAGE_SIZE = 500;

  /** 不可用时的**逐字相同**的 404 文案（不泄露「报表存在但你没权限」）。 */
  private static final String NOT_FOUND_MESSAGE = "报表不存在或无权访问";

  /** 指标口径（运行时唯一真相的本地视图）。 */
  private record MetricDef(
      String code,
      String displayName,
      String unit,
      String aggregation,
      String numerator,
      String denominator,
      boolean currencyAware,
      String description,
      String ownerModule) {}

  /**
   * 一条筛选条件（维度 + 运算符 + 值）。
   *
   * <p>⚠️ **包内可见**（不是 private）：13.6.1.2 看板搭建的组件筛选**复用同一条解析规则**，
   * 直接构造本记录复用它的语义，不另起一套筛选解析（两套解析迟早漂移）。
   */
  record FilterSpec(
      ReportingCustomReportCatalog.Dimension dimension,
      ReportingCustomReportCatalog.FilterOperator operator,
      String value,
      String value2) {}

  /**
   * 一份报表配置（从库里读出来的形状）。
   *
   * <p>⚠️ **包内可见**（不是 private）：13.6.1.2 看板搭建把一个组件当成「一张临时报表」
   * 交给本类的 {@link #execute} 去算 —— **口径不重算**是本模块的第一纪律，
   * 看板必须走同一个查询引擎，而不是自己再写一遍聚合逻辑。
   */
  record ReportConfig(
      UUID id,
      String code,
      String name,
      String description,
      String visibility,
      String chartType,
      LocalDate windowFrom,
      LocalDate windowTo,
      UUID ownerUserId,
      List<String> measureCodes,
      List<String> dimensionCodes,
      List<FilterSpec> filters) {}

  /** 一个维度组合的取值（维度元组 → 指标 × 币种 → 值）。 */
  private record Group(List<String> dimensions, Map<String, Map<String, BigDecimal>> values) {}

  private final DSLContext sql;
  private final TenantTransactions tenants;
  private final ReportingOverviewService overview;
  private final Clock clock;

  public ReportingCustomReportService(
      DSLContext sql, TenantTransactions tenants, ReportingOverviewService overview, Clock clock) {
    this.sql = sql;
    this.tenants = tenants;
    this.overview = overview;
    this.clock = clock;
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================

  /** 能力声明：**导出**与**实际投递**都属 13.6.2.1（未交付）⇒ available=false。 */
  public ReportingCustomReportModels.CustomReportCapabilities capabilities() {
    return new ReportingCustomReportModels.CustomReportCapabilities(
        true,
        true,
        true,
        false,
        "13.6.2.1 导出与订阅",
        "🔴 **大文件导出（Excel / CSV / PDF）未交付** —— 归属 13.6.2.1。"
            + "蓝图关系句写死「大导出走异步任务并给出有效期」⇒ 那需要**异步任务表 + 文件有效期**，"
            + "本模块既没有导出端点，也没有导出任务表。界面上「导出」写「未交付」，不提供假按钮。",
        false,
        "13.6.2.1 导出与订阅",
        "🔴 **邮件 / 企业 IM 的实际投递未交付** —— 归属 13.6.2.1。"
            + "本模块只负责「订阅计划（cron + 通道 + 收件目标）」与「**待投递记录**（队列）」；"
            + "队列里的记录状态是 PENDING（待投递），**不是「已发送」**。",
        ReportingCustomReportCatalog.UPSTREAM_MODULES,
        ReportingCustomReportCatalog.NOT_DELIVERED,
        ReportingCustomReportCatalog.RULES);
  }

  // ==========================================================================
  // 语义层目录
  // ==========================================================================

  /**
   * **可用的语义层指标清单**（蓝图「输入」句）。
   *
   * <p>⚠️ **按当前租户过滤**：指标定义是**租户级**的（UNIQUE(tenant_id, metric_code)），
   * 别的租户登记的指标**不进**本租户的清单。
   */
  public List<ReportingCustomReportModels.CustomReportMetricView> metricCatalog(UUID tenantId) {
    var definitions = overview.metricDefinitions(tenantId);
    var out = new ArrayList<ReportingCustomReportModels.CustomReportMetricView>();
    for (var definition : definitions) {
      out.add(
          new ReportingCustomReportModels.CustomReportMetricView(
              definition.metricCode(),
              definition.displayName(),
              definition.unit(),
              definition.aggregation(),
              definition.numeratorMetricCode(),
              definition.denominatorMetricCode(),
              definition.currencyAware(),
              definition.description(),
              definition.ownerModule()));
    }
    return out;
  }

  /** 可用维度白名单（**只有这 5 个**）。 */
  public List<ReportingCustomReportModels.CustomReportDimensionView> dimensionCatalog() {
    var out = new ArrayList<ReportingCustomReportModels.CustomReportDimensionView>();
    for (var dimension : ReportingCustomReportCatalog.Dimension.values()) {
      out.add(
          new ReportingCustomReportModels.CustomReportDimensionView(
              dimension.name(),
              dimension.label(),
              dimension.kind(),
              switch (dimension) {
                case LEGAL_ENTITY, SHOP ->
                    "语义层不带法人 / 店铺的**名称档案** ⇒ 这里给 uuid 文本，"
                        + "**不编一个名字**（编名字 = 第二个真相）。";
                case BUCKET_DATE -> "事实的分桶日（按记录自己的时区分桶，不是按 UTC 截断）。";
                case SCOPE_TYPE -> "投影事实的归属维度：公司 / 法人 / 店铺。";
                case CURRENCY -> "事实的币种。涉币指标按币种分别报，绝不跨币种相加。";
              }));
    }
    return out;
  }

  // ==========================================================================
  // 报表清单
  // ==========================================================================

  /** 报表清单（真分页 + 真搜索 + 真筛选）。 */
  public ReportingCustomReportModels.CustomReportPage list(
      UUID tenantId, UUID actorUserId, String keyword, String visibility, int page, int size) {
    return tenants.run(
        tenantId,
        () -> {
          var effectiveSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
          var effectivePage = Math.max(page, 0);
          var where = new ArrayList<String>();
          var params = new ArrayList<Object>();
          params.add(tenantId);
          if (keyword != null && !keyword.isBlank()) {
            where.add("(r.display_name ilike ? or r.report_code ilike ?)");
            var like = "%" + keyword.trim() + "%";
            params.add(like);
            params.add(like);
          }
          if (visibility != null && !visibility.isBlank()) {
            where.add("r.visibility = ?");
            params.add(normalizeVisibility(visibility));
          }
          var clause = where.isEmpty() ? "" : " and " + String.join(" and ", where);
          var total =
              sql.fetchOne(
                  "select count(*) from reporting_custom_report r where r.tenant_id = ?" + clause,
                  params.toArray());
          var rows =
              sql.fetch(
                  "select r.id, r.report_code, r.display_name, r.description, r.visibility, r.chart_type,"
                      + " r.owner_user_id, r.updated_at,"
                      + " (select count(*) from reporting_custom_report_measure m"
                      + "   where m.tenant_id = r.tenant_id and m.report_id = r.id),"
                      + " (select count(*) from reporting_custom_report_dimension d"
                      + "   where d.tenant_id = r.tenant_id and d.report_id = r.id)"
                      + " from reporting_custom_report r"
                      + " where r.tenant_id = ?"
                      + clause
                      + " order by r.updated_at desc, r.report_code"
                      + " limit ? offset ?",
                  append(params, effectiveSize, effectivePage * effectiveSize).toArray());
          var items = new ArrayList<ReportingCustomReportModels.CustomReportSummary>();
          for (var row : rows) {
            items.add(
                new ReportingCustomReportModels.CustomReportSummary(
                    row.get(0, UUID.class).toString(),
                    row.get(1, String.class),
                    row.get(2, String.class),
                    row.get(3, String.class),
                    row.get(4, String.class),
                    row.get(5, String.class),
                    row.get(8, Integer.class),
                    row.get(9, Integer.class),
                    row.get(6, UUID.class).toString(),
                    row.get(6, UUID.class).equals(actorUserId),
                    row.get(7, OffsetDateTime.class) == null
                        ? ""
                        : row.get(7, OffsetDateTime.class).toString()));
          }
          return new ReportingCustomReportModels.CustomReportPage(
              items,
              total == null ? 0L : total.get(0, Long.class),
              effectivePage,
              effectiveSize,
              keyword == null ? "" : keyword.trim(),
              visibility == null ? "" : visibility.trim());
        });
  }

  // ==========================================================================
  // 读取 / 保存 / 删除
  // ==========================================================================

  /**
   * 本次查询会用的 **SQL 文本**（**不含绑定参数**）。
   *
   * <p>⚠️ 这是给测试用的可观测点：它让「**用户输入只作为参数值，绝不拼进 SQL 文本**」
   * 这条约束**可以被断言**，而不是只能靠读代码相信。
   *
   * @return SQL 文本（参数占位符是 {@code ?} / {@code ?::类型}）
   */
  String explainQuerySql(UUID tenantId, UUID reportId, LocalDate from, LocalDate to) {
    return tenants.run(
        tenantId,
        () -> {
          var config = loadConfig(tenantId, reportId);
          var window = resolveWindow(config, from, to);
          var catalog = metricCatalogInTenant(tenantId);
          var dimensions = new ArrayList<ReportingCustomReportCatalog.Dimension>();
          for (var dimensionCode : config.dimensionCodes()) {
            dimensions.add(ReportingCustomReportCatalog.Dimension.of(dimensionCode));
          }
          var needed = new LinkedHashSet<String>();
          for (var metricCode : config.measureCodes()) {
            expand(metricCode, catalog, needed, 0);
          }
          var sumCodes = new ArrayList<String>();
          var lastCodes = new ArrayList<String>();
          for (var code : needed) {
            var definition = catalog.get(code);
            if (definition == null) continue;
            if ("LAST".equals(definition.aggregation())) {
              lastCodes.add(code);
            } else {
              sumCodes.add(code);
            }
          }
          return buildQuery(tenantId, dimensions, config.filters(), sumCodes, lastCodes, window)
              .pageSql();
        });
  }

  /** 读一份报表配置。 */
  public ReportingCustomReportModels.CustomReportView get(UUID tenantId, UUID actorUserId, UUID reportId) {
    return tenants.run(
        tenantId,
        () -> {
          var config = loadConfig(tenantId, reportId);
          requireVisible(config, actorUserId);
          return toView(tenantId, config);
        });
  }

  /**
   * 保存一份报表配置（{@code reportId} 为空 ⇒ 新建）。
   *
   * <p>🔴 **创建者来自已认证会话**（不在入参里）⇒ 客户端不能把报表「送给」别人当自己的。
   */
  public ReportingCustomReportModels.CustomReportView save(
      UUID tenantId, UUID actorUserId, UUID reportId, ReportingCustomReportModels.CustomReportSaveRequest request) {
    return tenants.run(
        tenantId,
        () -> {
          if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
          }
          var code = requireText(request.reportCode(), "报表编码");
          var name = requireText(request.displayName(), "报表名称");
          var visibility = normalizeVisibility(request.visibility());
          var chartType =
              whitelist(() -> ReportingCustomReportCatalog.ChartType.of(request.chartType()));
          var description = request.description() == null ? "" : request.description().trim();
          if (description.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "报表说明最长 500 字");
          }

          var measureCodes = new ArrayList<String>();
          for (var metricCode : orEmpty(request.measureMetricCodes())) {
            var normalized = metricCode == null ? "" : metricCode.trim().toUpperCase(Locale.ROOT);
            if (normalized.isEmpty()) continue;
            if (!measureCodes.contains(normalized)) measureCodes.add(normalized);
          }
          if (measureCodes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "至少要选一个度量（报表不能没有度量）");
          }

          var dimensionCodes = new ArrayList<String>();
          for (var dimensionCode : orEmpty(request.dimensionCodes())) {
            var dimension = whitelist(() -> ReportingCustomReportCatalog.Dimension.of(dimensionCode));
            if (!dimensionCodes.contains(dimension.name())) dimensionCodes.add(dimension.name());
          }

          var filters = new ArrayList<FilterSpec>();
          for (var filter : orEmptyItems(request.filters())) {
            filters.add(parseFilter(filter));
          }

          whitelistVoid(() -> chartType.validateShape(dimensionCodes.size(), measureCodes.size()));

          if (request.windowFrom() != null && request.windowTo() != null
              && request.windowFrom().isAfter(request.windowTo())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "默认窗口的起始日期不能晚于结束日期");
          }

          // 🔴 度量必须**已登记**：口径唯一 —— 没有登记的口径就没有那个指标。
          var catalog = metricCatalogInTenant(tenantId);
          for (var metricCode : measureCodes) {
            if (!catalog.containsKey(metricCode)) {
              throw new ResponseStatusException(
                  HttpStatus.BAD_REQUEST,
                  "指标码 " + metricCode + " 未在口径表（reporting_metric_definition）登记 —— "
                      + "口径唯一，没有登记就没有这个指标。");
            }
          }

          var id = reportId == null ? Ids.next() : reportId;
          if (reportId != null) {
            var existing = loadConfig(tenantId, reportId);
            if (!existing.ownerUserId().equals(actorUserId)) {
              // 🔴 个人报表只有创建者能改；不是创建者 ⇒ 与「不存在」逐字相同的 404。
              throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
            }
          }
          sql.execute(
              "insert into reporting_custom_report"
                  + " (id,tenant_id,report_code,display_name,description,owner_user_id,visibility,"
                  + " chart_type,window_from,window_to,created_at,updated_at)"
                  + " values (?,?,?,?,?,?,?,?,?,?,now(),now())"
                  + " on conflict (tenant_id, report_code) do update"
                  + " set display_name = excluded.display_name, description = excluded.description,"
                  + " visibility = excluded.visibility, chart_type = excluded.chart_type,"
                  + " window_from = excluded.window_from, window_to = excluded.window_to,"
                  + " updated_at = now()",
              id,
              tenantId,
              code,
              name,
              description,
              actorUserId,
              visibility,
              chartType.name(),
              request.windowFrom(),
              request.windowTo());

          // 度量 / 维度 / 筛选整体替换（配置语义就是「这一份就是这样」）。
          sql.execute("delete from reporting_custom_report_measure where tenant_id = ? and report_id = ?", tenantId, id);
          sql.execute("delete from reporting_custom_report_dimension where tenant_id = ? and report_id = ?", tenantId, id);
          sql.execute("delete from reporting_custom_report_filter where tenant_id = ? and report_id = ?", tenantId, id);

          var order = 0;
          for (var metricCode : measureCodes) {
            sql.execute(
                "insert into reporting_custom_report_measure"
                    + " (id,tenant_id,report_id,metric_code,display_order,created_at)"
                    + " values (?,?,?,?,?,now())",
                Ids.next(),
                tenantId,
                id,
                metricCode,
                order++);
          }
          order = 0;
          for (var dimensionCode : dimensionCodes) {
            sql.execute(
                "insert into reporting_custom_report_dimension"
                    + " (id,tenant_id,report_id,dimension_code,display_order,created_at)"
                    + " values (?,?,?,?,?,now())",
                Ids.next(),
                tenantId,
                id,
                dimensionCode,
                order++);
          }
          order = 0;
          for (var filter : filters) {
            sql.execute(
                "insert into reporting_custom_report_filter"
                    + " (id,tenant_id,report_id,dimension_code,operator,value_text,value_text2,"
                    + " display_order,created_at)"
                    + " values (?,?,?,?,?,?,?,?,now())",
                Ids.next(),
                tenantId,
                id,
                filter.dimension().name(),
                filter.operator().name(),
                filter.value(),
                filter.value2(),
                order++);
          }

          var config = loadConfig(tenantId, id);
          return toView(tenantId, config);
        });
  }

  /** 删除一份报表（连同它的度量 / 维度 / 筛选 / 分享 / 订阅 / 投递记录）。 */
  public void delete(UUID tenantId, UUID actorUserId, UUID reportId) {
    tenants.run(
        tenantId,
        () -> {
          var config = loadConfig(tenantId, reportId);
          if (!config.ownerUserId().equals(actorUserId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
          }
          sql.execute("delete from reporting_custom_report_delivery where tenant_id = ? and report_id = ?", tenantId, reportId);
          sql.execute("delete from reporting_custom_report_subscription where tenant_id = ? and report_id = ?", tenantId, reportId);
          sql.execute("delete from reporting_custom_report_share where tenant_id = ? and report_id = ?", tenantId, reportId);
          sql.execute("delete from reporting_custom_report_filter where tenant_id = ? and report_id = ?", tenantId, reportId);
          sql.execute("delete from reporting_custom_report_dimension where tenant_id = ? and report_id = ?", tenantId, reportId);
          sql.execute("delete from reporting_custom_report_measure where tenant_id = ? and report_id = ?", tenantId, reportId);
          sql.execute("delete from reporting_custom_report where tenant_id = ? and id = ?", tenantId, reportId);
          return null;
        });
  }

  // ==========================================================================
  // 报表结果（查询引擎）
  // ==========================================================================

  /**
   * 跑一份报表，返回结果集。
   *
   * @param from 起始日（空 ⇒ 用报表的默认窗口，再空 ⇒ 最近 30 天）
   * @param to 结束日（同上）
   */
  public ReportingCustomReportModels.CustomReportRunResponse run(
      UUID tenantId,
      UUID reportId,
      UUID actorUserId,
      LocalDate from,
      LocalDate to,
      int page,
      int size) {
    return tenants.run(
        tenantId,
        () -> {
          var config = loadConfig(tenantId, reportId);
          requireVisible(config, actorUserId);
          return execute(tenantId, config, from, to, page, size);
        });
  }

  /**
   * 可见性闸门：**个人报表只有创建者能看**。
   *
   * <p>🔴 不通过时抛的异常与「报表不存在」**逐字相同**（不泄露存在性）。
   */
  private void requireVisible(ReportConfig config, UUID actorUserId) {
    if ("PRIVATE".equals(config.visibility()) && !config.ownerUserId().equals(actorUserId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
    }
  }

  /** 只有创建者能改这份报表（分享 / 订阅这类**管理动作**）。 */
  private void requireOwner(ReportConfig config, UUID actorUserId) {
    if (!config.ownerUserId().equals(actorUserId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
    }
  }

  /**
   * 报表查询引擎（**口径不重算**）。
   *
   * <p>🔴 **执行顺序**：
   *
   * <ol>
   *   <li>解析窗口；</li>
   *   <li>读口径表 ⇒ 度量的指标码必须**已登记**；</li>
   *   <li>展开派生依赖 ⇒ 得到要取的**基础指标码**（SUM / LAST）；</li>
   *   <li>拼 SQL（片段只来自维度 / 运算符白名单，值只走绑定参数）；</li>
   *   <li>服务端按登记口径**分别取数再算**派生型；</li>
   *   <li>分母为 0 ⇒ NULL（不可比），不是 0。</li>
   * </ol>
   *
   * <p>⚠️ **包内可见**（不是 private）：13.6.1.2 看板搭建的组件数据由本方法算出
   * （**口径不重算**：看板不另起一套指标计算逻辑）。
   */
  ReportingCustomReportModels.CustomReportRunResponse execute(
      UUID tenantId,
      ReportConfig config,
      LocalDate from,
      LocalDate to,
      int page,
      int size) {
    var effectiveSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var effectivePage = Math.max(page, 0);
    var window = resolveWindow(config, from, to);

    var catalog = metricCatalogInTenant(tenantId);
    // 🔴 度量的指标码必须已登记（口径唯一）：没有登记就没有那个指标。
    for (var metricCode : config.measureCodes()) {
      if (!catalog.containsKey(metricCode)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "指标码 " + metricCode + " 未在口径表登记 —— 口径唯一，没有登记就没有这个指标。");
      }
    }

    var dimensions = new ArrayList<ReportingCustomReportCatalog.Dimension>();
    for (var dimensionCode : config.dimensionCodes()) {
      dimensions.add(whitelist(() -> ReportingCustomReportCatalog.Dimension.of(dimensionCode)));
    }

    // 展开派生依赖 ⇒ 基础指标码（SUM / LAST）。
    var needed = new LinkedHashSet<String>();
    for (var metricCode : config.measureCodes()) {
      expand(metricCode, catalog, needed, 0);
    }
    var sumCodes = new ArrayList<String>();
    var lastCodes = new ArrayList<String>();
    for (var code : needed) {
      var definition = catalog.get(code);
      if (definition == null) continue;
      if ("LAST".equals(definition.aggregation())) {
        lastCodes.add(code);
      } else {
        sumCodes.add(code);
      }
    }
    if (sumCodes.isEmpty() && lastCodes.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "这份报表的度量没有可取的底层指标（口径表里查不到）。");
    }

    var query = buildQuery(tenantId, dimensions, config.filters(), sumCodes, lastCodes, window);
    var rows =
        sql.fetch(
            query.pageSql(),
            append(query.commonParams(), effectiveSize, effectivePage * effectiveSize).toArray());
    var total = sql.fetchOne(query.countSql(), query.commonParams().toArray());
    var totalCount = total == null ? 0L : total.get(0, Long.class);

    // ── 分组：维度元组 → (指标码 × 币种) → 值 ──
    var groups = new LinkedHashMap<String, Group>();
    var dimNames = dimColumnNames(dimensions.size());
    for (var row : rows) {
      var dimValues = new ArrayList<String>();
      for (var index = 0; index < dimNames.size(); index++) {
        dimValues.add(row.get(index, String.class));
      }
      var metricCode = row.get(dimNames.size(), String.class);
      var currency = row.get(dimNames.size() + 1, String.class);
      var value = row.get(dimNames.size() + 2, BigDecimal.class);
      var key = String.join("\u0001", dimValues);
      var group =
          groups.computeIfAbsent(
              key, ignored -> new Group(dimValues, new LinkedHashMap<String, Map<String, BigDecimal>>()));
      var byCurrency =
          group.values().computeIfAbsent(metricCode, ignored -> new LinkedHashMap<String, BigDecimal>());
      byCurrency.put(currency, value);
    }

    var measureColumns = new ArrayList<ReportingCustomReportModels.CustomReportMetricView>();
    for (var metricCode : config.measureCodes()) {
      measureColumns.add(toMetricView(catalog.get(metricCode)));
    }

    var resultRows = new ArrayList<ReportingCustomReportModels.CustomReportRow>();
    for (var group : groups.values()) {
      var dimensionCells = new ArrayList<ReportingCustomReportModels.CustomReportDimensionCell>();
      for (var index = 0; index < dimensions.size(); index++) {
        var dimension = dimensions.get(index);
        dimensionCells.add(
            new ReportingCustomReportModels.CustomReportDimensionCell(
                dimension.name(), dimension.label(), group.dimensions().get(index)));
      }
      var measureCells = new ArrayList<ReportingCustomReportModels.CustomReportMeasureCell>();
      for (var metricCode : config.measureCodes()) {
        measureCells.add(measureCell(metricCode, catalog, group));
      }
      resultRows.add(new ReportingCustomReportModels.CustomReportRow(dimensionCells, measureCells));
    }

    var dataUpdatedAt = dataUpdatedAt(tenantId);
    var caliberNotes = new ArrayList<String>();
    caliberNotes.add(
        "口径全部取自 reporting_metric_definition（13.1.1.1 经营总览的**口径唯一表**）—— "
            + "本模块不复制一份，也不自己写公式。");
    caliberNotes.add(
        "🔴 **涉币指标按币种分别报**（绝不跨币种相加）；与货币无关的量（笔数 / 件数）"
            + "给一个跨币种合计，它的币种显示为空串。");
    if (!lastCodes.isEmpty()) {
      caliberNotes.add(
          "存量型指标（" + String.join("、", lastCodes) + "）取**窗口末之前最近一天的快照**"
              + "（余额不能累加），口径与 13.1.1.1 经营总览完全一致。");
    }
    if (!config.dimensionCodes().isEmpty()) {
      caliberNotes.add(
          "维度取自白名单（"
              + String.join("、", config.dimensionCodes())
              + "）；法人 / 店铺给的是 **uuid 文本** —— 语义层不带名称档案，不编名字。");
    }

    var status = resultRows.isEmpty() ? "NO_DATA" : "AVAILABLE";
    var statusNote =
        resultRows.isEmpty()
            ? "这个窗口里没有任何维度组合有事实数据（不是 0）：上游可能还没有投影，或窗口不对。"
            : "";
    return new ReportingCustomReportModels.CustomReportRunResponse(
        config.id().toString(),
        config.code(),
        config.name(),
        config.description(),
        config.chartType(),
        window.from(),
        window.to(),
        new ArrayList<ReportingCustomReportModels.CustomReportDimensionCell>(),
        measureColumns,
        resultRows,
        totalCount,
        effectivePage,
        effectiveSize,
        caliberNotes,
        dataUpdatedAt,
        "最近一次投影落库时刻（reporting_projection_event.applied_at 与事实行 updated_at 取较晚者）。",
        status,
        statusNote,
        resultRows.isEmpty() ? "NO_FACTS" : null,
        resultRows.isEmpty()
            ? "这个时间窗口里没有任何事实数据。上游可能还没有同步，或刷新窗口不对。"
            : "");
  }

  /** 一个度量在某个维度组合上的取值。 */
  private ReportingCustomReportModels.CustomReportMeasureCell measureCell(
      String metricCode, Map<String, MetricDef> catalog, Group group) {
    var definition = catalog.get(metricCode);
    if (definition == null) {
      return new ReportingCustomReportModels.CustomReportMeasureCell(
          metricCode,
          metricCode,
          "",
          false,
          List.of(),
          "NO_DATA",
          "该指标码未在口径表登记 —— 口径唯一，没有登记就没有这个指标。");
    }
    // 这个度量依赖的基础指标（分子 / 分母也可能是派生型）。
    var closure = new LinkedHashSet<String>();
    expand(metricCode, catalog, closure, 0);

    var values = new ArrayList<ReportingCustomReportModels.CustomReportCurrencyValue>();
    if (definition.currencyAware()) {
      // 🔴 涉币指标**按币种分别报**：每个币种一行，绝不跨币种相加。
      var currencies = new LinkedHashSet<String>();
      for (var code : closure) {
        var byCurrency = group.values().get(code);
        if (byCurrency == null) continue;
        currencies.addAll(byCurrency.keySet());
      }
      for (var currency : currencies) {
        var value = evaluate(metricCode, currency, catalog, group, 0);
        values.add(
            new ReportingCustomReportModels.CustomReportCurrencyValue(
                currency,
                value,
                value == null ? "NOT_COMPARABLE" : "AVAILABLE",
                value == null ? notComparableNote(metricCode, catalog, currency) : ""));
      }
    } else {
      // 与货币无关的量（笔数 / 件数）⇒ 一个跨币种合计，币种是**空串**。
      var value = evaluate(metricCode, null, catalog, group, 0);
      values.add(
          new ReportingCustomReportModels.CustomReportCurrencyValue(
              "",
              value,
              value == null ? "NOT_COMPARABLE" : "AVAILABLE",
              value == null ? notComparableNote(metricCode, catalog, null) : ""));
    }

    var hasFacts = false;
    for (var code : closure) {
      if (group.values().containsKey(code)) {
        hasFacts = true;
        break;
      }
    }
    var allNull = true;
    for (var value : values) {
      if (value.value() != null) {
        allNull = false;
        break;
      }
    }
    String availability;
    String note;
    if (values.isEmpty() || !hasFacts) {
      availability = "NO_DATA";
      note = "该口径下**没有事实数据**（不是 0）：上游还没有投影，或这个窗口里没有这类事实。";
    } else if (allNull) {
      availability = "NOT_COMPARABLE";
      note = notComparableNote(metricCode, catalog, null);
    } else {
      availability = "AVAILABLE";
      note = "";
    }
    return new ReportingCustomReportModels.CustomReportMeasureCell(
        metricCode,
        definition.displayName(),
        definition.unit(),
        definition.currencyAware(),
        values,
        availability,
        note);
  }

  /** 算不出来的时候，原因写清楚（**不是 0**）。 */
  private String notComparableNote(
      String metricCode, Map<String, MetricDef> catalog, String currency) {
    var definition = catalog.get(metricCode);
    if (definition == null) {
      return "该指标码未在口径表登记 —— 口径唯一，没有登记就没有这个指标。";
    }
    if ("DERIVED_RATIO".equals(definition.aggregation())) {
      return "🔴 **分母为 0 或缺数据 ⇒ 算不出来（NULL），不是 0** ——"
          + "「压根算不出来」与「算出来是零」在报表里是相反的两件事。"
          + "分母指标：" + definition.denominator() + "。";
    }
    if ("DERIVED_DIFF".equals(definition.aggregation())) {
      return "分子或分母缺数据 ⇒ 算不出来（NULL），不是 0。分子："
          + definition.numerator() + "，分母：" + definition.denominator() + "。";
    }
    return currency == null
        ? "这个维度组合下没有事实数据（不是 0）。"
        : "这个维度组合下没有 " + currency + " 的事实数据（不是 0）。";
  }

  /**
   * 按登记口径求一个指标的值。
   *
   * @param currency 币种（null = 跨币种合计）
   */
  private BigDecimal evaluate(
      String metricCode,
      String currency,
      Map<String, MetricDef> catalog,
      Group group,
      int depth) {
    if (depth > MAX_DERIVE_DEPTH) {
      throw new IllegalStateException("指标派生层级超过上限，可能是口径表被改坏了：" + metricCode);
    }
    var definition = catalog.get(metricCode);
    if (definition == null) return null;
    return switch (definition.aggregation()) {
      case "SUM", "LAST" -> baseValue(metricCode, currency, catalog, group);
      case "DERIVED_DIFF" -> {
        var numerator = evaluate(definition.numerator(), currency, catalog, group, depth + 1);
        var denominator = evaluate(definition.denominator(), currency, catalog, group, depth + 1);
        // ⚠️ 分子分母**任一缺失就不算**（不用 0 冒充）。
        if (numerator == null || denominator == null) yield null;
        yield numerator.subtract(denominator);
      }
      case "DERIVED_RATIO" -> {
        var numerator = evaluate(definition.numerator(), currency, catalog, group, depth + 1);
        var denominator = evaluate(definition.denominator(), currency, catalog, group, depth + 1);
        // 🔴 **分母为 0 ⇒ 比率为 NULL（不可比）**，不是 0。
        if (numerator == null || denominator == null || denominator.signum() == 0) yield null;
        var ratio = numerator.divide(denominator, 8, RoundingMode.HALF_UP);
        if ("PERCENT".equals(definition.unit())) {
          ratio = ratio.movePointRight(2).setScale(4, RoundingMode.HALF_UP);
        }
        yield ratio;
      }
      default -> null;
    };
  }

  /**
   * 基础指标（SUM / LAST）的取值。
   *
   * <p>🔴 **跨币种合计只对与货币无关的量开放**：涉币指标跨币种相加是**红线**
   * （把两个币种的金额加起来 = 造假），所以涉币指标在跨币种口径下**返回 null**。
   */
  private BigDecimal baseValue(
      String metricCode, String currency, Map<String, MetricDef> catalog, Group group) {
    var definition = catalog.get(metricCode);
    var byCurrency = group.values().get(metricCode);
    if (definition == null || byCurrency == null) return null;
    if (currency != null) {
      return byCurrency.get(currency);
    }
    // 跨币种合计：涉币指标**不给**（红线）。
    if (definition.currencyAware()) return null;
    BigDecimal total = null;
    for (var value : byCurrency.values()) {
      if (value == null) continue;
      total = total == null ? value : total.add(value);
    }
    return total;
  }

  // ==========================================================================
  // 分享
  // ==========================================================================

  /** 生成分享链接。 */
  public ReportingCustomReportModels.CustomReportShareView createShare(
      UUID tenantId, UUID reportId, UUID actorUserId, String visibility) {
    return tenants.run(
        tenantId,
        () -> {
          var config = loadConfig(tenantId, reportId);
          if (!config.ownerUserId().equals(actorUserId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
          }
          var shareVisibility = visibility == null || visibility.isBlank() ? "TEAM" : normalizeVisibility(visibility);
          var token = randomToken();
          var id = Ids.next();
          sql.execute(
              "insert into reporting_custom_report_share"
                  + " (id,tenant_id,report_id,share_token,visibility,created_by,created_at)"
                  + " values (?,?,?,?,?,?,now())",
              id,
              tenantId,
              reportId,
              token,
              shareVisibility,
              actorUserId);
          return new ReportingCustomReportModels.CustomReportShareView(
              id.toString(),
              reportId.toString(),
              token,
              "/analytics/custom-report/shared/" + token,
              shareVisibility,
              actorUserId.toString(),
              "",
              null);
        });
  }

  /** 某张报表的分享链接清单。 */
  public List<ReportingCustomReportModels.CustomReportShareView> listShares(
      UUID tenantId, UUID actorUserId, UUID reportId) {
    return tenants.run(
        tenantId,
        () -> {
          var config = loadConfig(tenantId, reportId);
          requireOwner(config, actorUserId);
          var rows =
              sql.fetch(
                  "select id, report_id, share_token, visibility, created_by, created_at, revoked_at"
                      + " from reporting_custom_report_share"
                      + " where tenant_id = ? and report_id = ?"
                      + " order by created_at desc",
                  tenantId,
                  reportId);
          var out = new ArrayList<ReportingCustomReportModels.CustomReportShareView>();
          for (var row : rows) {
            var token = row.get(2, String.class);
            out.add(
                new ReportingCustomReportModels.CustomReportShareView(
                    row.get(0, UUID.class).toString(),
                    row.get(1, UUID.class).toString(),
                    token,
                    "/analytics/custom-report/shared/" + token,
                    row.get(3, String.class),
                    row.get(4, UUID.class).toString(),
                    row.get(5, OffsetDateTime.class) == null ? "" : row.get(5, OffsetDateTime.class).toString(),
                    row.get(6, OffsetDateTime.class) == null ? null : row.get(6, OffsetDateTime.class).toString()));
          }
          return out;
        });
  }

  /** 撤销一条分享链接。 */
  public void revokeShare(UUID tenantId, UUID actorUserId, UUID shareId) {
    tenants.run(
        tenantId,
        () -> {
          var row =
              sql.fetchOne(
                  "select report_id, created_by from reporting_custom_report_share"
                      + " where tenant_id = ? and id = ?",
                  tenantId,
                  shareId);
          if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
          }
          var owner = row.get(1, UUID.class);
          if (!owner.equals(actorUserId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
          }
          sql.execute(
              "update reporting_custom_report_share set revoked_at = now()"
                  + " where tenant_id = ? and id = ? and revoked_at is null",
              tenantId,
              shareId);
          return null;
        });
  }

  /**
   * 通过分享链接读报表。
   *
   * <p>🔴 **未授权访问与「报表不存在」返回逐字相同的 404**：
   * 令牌在自己的租户上下文里查不到（别的租户的令牌 ⇒ RLS 挡住 ⇒ 查不到），
   * 或者查到了但调用者对这个范围没权限（PRIVATE 且不是创建者）—— 两种情况
   * **抛同一个异常、同一句话**。
   */
  public ReportingCustomReportModels.CustomReportSharedResponse shared(
      UUID tenantId, UUID actorUserId, String token, int page, int size) {
    return tenants.run(
        tenantId,
        () -> {
          var normalized = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
          if (!normalized.matches("^[0-9a-f]{32}$")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
          }
          var row =
              sql.fetchOne(
                  "select id, report_id, visibility, created_at from reporting_custom_report_share"
                      + " where tenant_id = ? and share_token = ? and revoked_at is null",
                  tenantId,
                  normalized);
          if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
          }
          var shareVisibility = row.get(2, String.class);
          var reportId = row.get(1, UUID.class);
          var config = loadConfig(tenantId, reportId);
          if (!"TEAM".equals(shareVisibility) && !config.ownerUserId().equals(actorUserId)) {
            // 🔴 与「报表不存在」**逐字相同**的 404。
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
          }
          var view = toView(tenantId, config);
          var result = execute(tenantId, config, null, null, page, size);
          return new ReportingCustomReportModels.CustomReportSharedResponse(
              view,
              result,
              shareVisibility,
              row.get(3, OffsetDateTime.class) == null ? "" : row.get(3, OffsetDateTime.class).toString());
        });
  }

  // ==========================================================================
  // 订阅与投递
  // ==========================================================================

  /** 某张报表的订阅计划清单。 */
  public List<ReportingCustomReportModels.CustomReportSubscriptionView> listSubscriptions(
      UUID tenantId, UUID actorUserId, UUID reportId) {
    return tenants.run(
        tenantId,
        () -> {
          var config = loadConfig(tenantId, reportId);
          requireOwner(config, actorUserId);
          var rows =
              sql.fetch(
                  "select s.id, s.report_id, r.report_code, r.display_name, s.cron_expression, s.channel,"
                      + " s.target, s.enabled, s.next_run_at, s.last_enqueued_at, s.created_at"
                      + " from reporting_custom_report_subscription s"
                      + " join reporting_custom_report r on r.tenant_id = s.tenant_id and r.id = s.report_id"
                      + " where s.tenant_id = ? and s.report_id = ?"
                      + " order by s.created_at desc",
                  tenantId,
                  reportId);
          return subscriptionViews(rows);
        });
  }

  /** 保存一条订阅计划（不存在 ⇒ 新建）。 */
  public ReportingCustomReportModels.CustomReportSubscriptionView saveSubscription(
      UUID tenantId, UUID reportId, UUID actorUserId, ReportingCustomReportModels.CustomReportSubscriptionRequest request) {
    return tenants.run(
        tenantId,
        () -> {
          var config = loadConfig(tenantId, reportId);
          if (!config.ownerUserId().equals(actorUserId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
          }
          if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
          }
          var cron = requireText(request.cronExpression(), "cron 表达式");
          // 🔴 **不支持的 cron 写法一律报错**（不静默回落成「每天一次」）。
          var schedule = whitelist(() -> ReportingCustomReportCron.parse(cron));
          var channel = requireText(request.channel(), "投递通道").toUpperCase(Locale.ROOT);
          if (!channel.equals("EMAIL") && !channel.equals("IM")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "投递通道只能是 EMAIL 或 IM");
          }
          var target = requireText(request.target(), "收件目标");
          var enabled = request.enabled() == null || request.enabled();
          var nextRun =
              whitelist(() -> ReportingCustomReportCron.nextAfter(schedule, OffsetDateTime.now(clock)));
          var id = Ids.next();
          sql.execute(
              "insert into reporting_custom_report_subscription"
                  + " (id,tenant_id,report_id,cron_expression,channel,target,enabled,next_run_at,"
                  + " created_at,updated_at)"
                  + " values (?,?,?,?,?,?,?,?::timestamptz,now(),now())",
              id,
              tenantId,
              reportId,
              cron,
              channel,
              target,
              enabled,
              nextRun);
          return new ReportingCustomReportModels.CustomReportSubscriptionView(
              id.toString(),
              reportId.toString(),
              config.code(),
              config.name(),
              cron,
              channel,
              target,
              enabled,
              nextRun,
              null,
              "");
        });
  }

  /** 切换一条订阅的启停。 */
  public ReportingCustomReportModels.CustomReportSubscriptionView toggleSubscription(
      UUID tenantId, UUID actorUserId, UUID subscriptionId, Boolean enabled) {
    return tenants.run(
        tenantId,
        () -> {
          var row =
              sql.fetchOne(
                  "select s.id, s.report_id, r.report_code, r.display_name, s.cron_expression, s.channel,"
                      + " s.target, s.enabled, s.next_run_at, s.last_enqueued_at, s.created_at"
                      + " from reporting_custom_report_subscription s"
                      + " join reporting_custom_report r on r.tenant_id = s.tenant_id and r.id = s.report_id"
                      + " where s.tenant_id = ? and s.id = ?",
                  tenantId,
                  subscriptionId);
          if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
          }
          var reportId = row.get(1, UUID.class);
          var config = loadConfig(tenantId, reportId);
          if (!config.ownerUserId().equals(actorUserId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
          }
          var value = enabled == null || enabled;
          var nextRun = row.get(8, OffsetDateTime.class);
          if (value && nextRun == null) {
            var schedule = whitelist(() -> ReportingCustomReportCron.parse(row.get(4, String.class)));
            nextRun =
                whitelist(() -> ReportingCustomReportCron.nextAfter(schedule, OffsetDateTime.now(clock)));
          }
          sql.execute(
              "update reporting_custom_report_subscription set enabled = ?, next_run_at = ?::timestamptz,"
                  + " updated_at = now() where tenant_id = ? and id = ?",
              value,
              nextRun,
              tenantId,
              subscriptionId);
          var views = subscriptionViews(sql.fetch(
              "select s.id, s.report_id, r.report_code, r.display_name, s.cron_expression, s.channel,"
                  + " s.target, s.enabled, s.next_run_at, s.last_enqueued_at, s.created_at"
                  + " from reporting_custom_report_subscription s"
                  + " join reporting_custom_report r on r.tenant_id = s.tenant_id and r.id = s.report_id"
                  + " where s.tenant_id = ? and s.id = ?",
              tenantId,
              subscriptionId));
          return views.isEmpty() ? null : views.get(0);
        });
  }

  /** 删除一条订阅计划（它的待投递记录一并收口成 CANCELLED）。 */
  public void deleteSubscription(UUID tenantId, UUID actorUserId, UUID subscriptionId) {
    tenants.run(
        tenantId,
        () -> {
          var row =
              sql.fetchOne(
                  "select report_id from reporting_custom_report_subscription"
                      + " where tenant_id = ? and id = ?",
                  tenantId,
                  subscriptionId);
          if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
          }
          var config = loadConfig(tenantId, row.get(0, UUID.class));
          if (!config.ownerUserId().equals(actorUserId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
          }
          sql.execute(
              "update reporting_custom_report_delivery set status = 'CANCELLED', updated_at = now()"
                  + " where tenant_id = ? and subscription_id = ? and status = 'PENDING'",
              tenantId,
              subscriptionId);
          sql.execute(
              "delete from reporting_custom_report_subscription where tenant_id = ? and id = ?",
              tenantId,
              subscriptionId);
          return null;
        });
  }

  /** 待投递记录台账（真分页 + 真计数）。 */
  public ReportingCustomReportModels.CustomReportDeliveryPage listDeliveries(
      UUID tenantId, String status, int page, int size) {
    return tenants.run(
        tenantId,
        () -> {
          var effectiveSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
          var effectivePage = Math.max(page, 0);
          var where = new ArrayList<String>();
          var params = new ArrayList<Object>();
          params.add(tenantId);
          if (status != null && !status.isBlank()) {
            where.add("d.status = ?");
            params.add(status.trim().toUpperCase(Locale.ROOT));
          }
          var clause = where.isEmpty() ? "" : " and " + String.join(" and ", where);
          var total =
              sql.fetchOne(
                  "select count(*) from reporting_custom_report_delivery d where d.tenant_id = ?" + clause,
                  params.toArray());
          var rows =
              sql.fetch(
                  "select d.id, d.report_id, r.report_code, d.subscription_id, d.scheduled_for, d.status,"
                      + " d.attempt_count, d.last_error, d.created_at"
                      + " from reporting_custom_report_delivery d"
                      + " join reporting_custom_report r on r.tenant_id = d.tenant_id and r.id = d.report_id"
                      + " where d.tenant_id = ?"
                      + clause
                      + " order by d.scheduled_for desc, d.created_at desc"
                      + " limit ? offset ?",
                  append(params, effectiveSize, effectivePage * effectiveSize).toArray());
          var items = new ArrayList<ReportingCustomReportModels.CustomReportDeliveryView>();
          for (var row : rows) {
            items.add(
                new ReportingCustomReportModels.CustomReportDeliveryView(
                    row.get(0, UUID.class).toString(),
                    row.get(1, UUID.class).toString(),
                    row.get(2, String.class),
                    row.get(3, UUID.class).toString(),
                    row.get(4, OffsetDateTime.class),
                    row.get(5, String.class),
                    row.get(6, Integer.class),
                    row.get(7, String.class),
                    row.get(8, OffsetDateTime.class) == null ? "" : row.get(8, OffsetDateTime.class).toString()));
          }
          return new ReportingCustomReportModels.CustomReportDeliveryPage(
              items,
              total == null ? 0L : total.get(0, Long.class),
              effectivePage,
              effectiveSize,
              status == null ? "" : status.trim());
        });
  }

  /**
   * 跑一轮：把**到期**的订阅计划落成**待投递记录**。
   *
   * <p>⚠️ **本服务不装 {@code @Scheduled}**（与 channel / identity 同一纪律：多副本下自带定时器
   * 要做主节点选举与分布式锁）⇒ 这一轮由**外部调度**调用
   * {@code POST /reporting/custom-report/deliveries/tick} 触发。
   *
   * <p>⚠️ **落的是「待投递」，不是「已发送」**：真正的投递由 13.6.2.1（未交付）推进。
   */
  public ReportingCustomReportModels.CustomReportTickResult tick(UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          var now = OffsetDateTime.now(clock);
          var due =
              sql.fetch(
                  "select id, report_id, cron_expression, next_run_at"
                      + " from reporting_custom_report_subscription"
                      + " where tenant_id = ? and enabled and next_run_at is not null"
                      + " and next_run_at <= ?::timestamptz order by next_run_at",
                  tenantId,
                  now);
          var enqueued = 0;
          var advanced = 0;
          for (var row : due) {
            var subscriptionId = row.get(0, UUID.class);
            var reportId = row.get(1, UUID.class);
            var scheduledFor = row.get(3, OffsetDateTime.class);
            var inserted =
                sql.fetchOne(
                    "insert into reporting_custom_report_delivery"
                        + " (id,tenant_id,report_id,subscription_id,scheduled_for,status,attempt_count,"
                        + " last_error,created_at,updated_at)"
                        + " values (?,?,?,?,?::timestamptz,'PENDING',0,'',now(),now())"
                        + " on conflict (tenant_id, subscription_id, scheduled_for) do nothing"
                        + " returning id",
                    Ids.next(),
                    tenantId,
                    reportId,
                    subscriptionId,
                    scheduledFor);
            if (inserted != null) enqueued++;
            var schedule = whitelist(() -> ReportingCustomReportCron.parse(row.get(2, String.class)));
            var next = whitelist(() -> ReportingCustomReportCron.nextAfter(schedule, scheduledFor));
            sql.execute(
                "update reporting_custom_report_subscription set next_run_at = ?::timestamptz,"
                    + " updated_at = now() where tenant_id = ? and id = ?",
                next,
                tenantId,
                subscriptionId);
            advanced++;
          }
          return new ReportingCustomReportModels.CustomReportTickResult(
              enqueued,
              advanced,
              "本轮把 " + enqueued + " 条到期的订阅计划落成了**待投递记录**（PENDING）。"
                  + "🔴 **实际投递（邮件 / IM）属 13.6.2.1，未交付** —— "
                  + "这些记录现在是「待投递」，不是「已发送」。");
        });
  }

  // ==========================================================================
  // 内部工具
  // ==========================================================================

  /** 时间窗口：入参优先，其次报表的默认窗口，最后「最近 30 天」。 */
  private record Window(LocalDate from, LocalDate to) {}

  private Window resolveWindow(ReportConfig config, LocalDate from, LocalDate to) {
    var end = to != null ? to : (config.windowTo() != null ? config.windowTo() : LocalDate.now(clock));
    var start =
        from != null
            ? from
            : (config.windowFrom() != null
                ? config.windowFrom()
                : end.minusDays(DEFAULT_WINDOW_DAYS - 1));
    if (start.isAfter(end)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "起始日期不能晚于结束日期（from=" + start + "、to=" + end + "）");
    }
    return new Window(start, end);
  }

  /** 读一份报表配置（RLS 挡住别的租户）。 */
  private ReportConfig loadConfig(UUID tenantId, UUID reportId) {
    var row =
        sql.fetchOne(
            "select id, report_code, display_name, description, visibility, chart_type, window_from,"
                + " window_to, owner_user_id from reporting_custom_report"
                + " where tenant_id = ? and id = ?",
            tenantId,
            reportId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
    }
    var measureRows =
        sql.fetch(
            "select metric_code from reporting_custom_report_measure"
                + " where tenant_id = ? and report_id = ? order by display_order, metric_code",
            tenantId,
            reportId);
    var measureCodes = new ArrayList<String>();
    for (var measure : measureRows) {
      measureCodes.add(measure.get(0, String.class));
    }
    var dimensionRows =
        sql.fetch(
            "select dimension_code from reporting_custom_report_dimension"
                + " where tenant_id = ? and report_id = ? order by display_order, dimension_code",
            tenantId,
            reportId);
    var dimensionCodes = new ArrayList<String>();
    for (var dimension : dimensionRows) {
      dimensionCodes.add(dimension.get(0, String.class));
    }
    var filterRows =
        sql.fetch(
            "select dimension_code, operator, value_text, value_text2 from reporting_custom_report_filter"
                + " where tenant_id = ? and report_id = ? order by display_order",
            tenantId,
            reportId);
    var filters = new ArrayList<FilterSpec>();
    for (var filter : filterRows) {
      filters.add(
          new FilterSpec(
              whitelist(() -> ReportingCustomReportCatalog.Dimension.of(filter.get(0, String.class))),
              whitelist(() -> ReportingCustomReportCatalog.FilterOperator.of(filter.get(1, String.class))),
              filter.get(2, String.class),
              filter.get(3, String.class)));
    }
    return new ReportConfig(
        row.get(0, UUID.class),
        row.get(1, String.class),
        row.get(2, String.class),
        row.get(3, String.class),
        row.get(4, String.class),
        row.get(5, String.class),
        row.get(6, LocalDate.class),
        row.get(7, LocalDate.class),
        row.get(8, UUID.class),
        measureCodes,
        dimensionCodes,
        filters);
  }

  /** 报表配置 → 出参。 */
  private ReportingCustomReportModels.CustomReportView toView(UUID tenantId, ReportConfig config) {
    var catalog = metricCatalogInTenant(tenantId);
    var measures = new ArrayList<ReportingCustomReportModels.CustomReportMetricView>();
    for (var metricCode : config.measureCodes()) {
      var definition = catalog.get(metricCode);
      measures.add(
          definition == null
              ? new ReportingCustomReportModels.CustomReportMetricView(
                  metricCode, metricCode, "", "UNKNOWN", null, null, false, "", "")
              : toMetricView(definition));
    }
    var filters = new ArrayList<ReportingCustomReportModels.CustomReportFilterView>();
    for (var filter : config.filters()) {
      filters.add(
          new ReportingCustomReportModels.CustomReportFilterView(
              filter.dimension().name(), filter.operator().name(), filter.value(), filter.value2()));
    }
    return new ReportingCustomReportModels.CustomReportView(
        config.id().toString(),
        config.code(),
        config.name(),
        config.description(),
        config.visibility(),
        config.chartType(),
        config.windowFrom(),
        config.windowTo(),
        measures,
        config.dimensionCodes(),
        filters,
        config.ownerUserId().toString(),
        "",
        "");
  }

  private ReportingCustomReportModels.CustomReportMetricView toMetricView(MetricDef definition) {
    return new ReportingCustomReportModels.CustomReportMetricView(
        definition.code(),
        definition.displayName(),
        definition.unit(),
        definition.aggregation(),
        definition.numerator(),
        definition.denominator(),
        definition.currencyAware(),
        definition.description(),
        definition.ownerModule());
  }

  /** 该租户的口径表（**运行时唯一真相**）。 */
  private Map<String, MetricDef> metricCatalogInTenant(UUID tenantId) {
    var out = new LinkedHashMap<String, MetricDef>();
    for (var definition : overview.metricDefinitions(tenantId)) {
      out.put(
          definition.metricCode(),
          new MetricDef(
              definition.metricCode(),
              definition.displayName(),
              definition.unit(),
              definition.aggregation(),
              definition.numeratorMetricCode(),
              definition.denominatorMetricCode(),
              definition.currencyAware(),
              definition.description(),
              definition.ownerModule()));
    }
    return out;
  }

  /** 展开派生依赖 ⇒ 需要取的基础指标码集合。 */
  private void expand(
      String metricCode, Map<String, MetricDef> catalog, Set<String> acc, int depth) {
    if (depth > MAX_DERIVE_DEPTH) {
      throw new IllegalStateException("指标派生层级超过上限，可能是口径表被改坏了：" + metricCode);
    }
    if (!acc.add(metricCode)) return;
    var definition = catalog.get(metricCode);
    if (definition == null) return;
    if ("DERIVED_DIFF".equals(definition.aggregation())
        || "DERIVED_RATIO".equals(definition.aggregation())) {
      expand(definition.numerator(), catalog, acc, depth + 1);
      expand(definition.denominator(), catalog, acc, depth + 1);
    }
  }

  // ---------------------------------------------------------------------------
  // SQL 拼装（🔴 片段只来自白名单枚举，值只走绑定参数）
  // ---------------------------------------------------------------------------

  /** 一条查询（结果与计数共用同一组前缀参数，分页参数只在结果那条上）。 */
  private record Query(String pageSql, String countSql, List<Object> commonParams) {}

  /** 维度列名（{@code d0} / {@code d1} …）。 */
  private List<String> dimColumnNames(int count) {
    var out = new ArrayList<String>();
    for (var index = 0; index < Math.max(count, 1); index++) {
      out.add("d" + index);
    }
    return out;
  }

  /**
   * 读一个维度取值。
   *
   * <p>⚠️ **NULL ⇒ 空串**（不是「null」字符串）：公司级事实行的法人 / 店铺维度就是空的
   * —— 空串 = 「这一行不适用」，不是「查不到名字」。
   */
  private String dimensionValue(
      org.jooq.Record row,
      int columnIndex,
      List<ReportingCustomReportCatalog.Dimension> dimensions,
      int dimensionIndex) {
    if (dimensionIndex >= dimensions.size()) {
      // 没有维度时那句 `1 as d0` 只是占位（分组恒为一行）。
      return "";
    }
    var dimension = dimensions.get(dimensionIndex);
    if ("DATE".equals(dimension.kind())) {
      var date = row.get(columnIndex, LocalDate.class);
      return date == null ? "" : date.toString();
    }
    var value = row.get(columnIndex, String.class);
    return value == null ? "" : value;
  }

  /**
   * 拼装查询。
   *
   * <p>⚠️ **SQL 片段全部来自枚举常量**：维度列名来自
   * {@link ReportingCustomReportCatalog.Dimension}，运算符来自
   * {@link ReportingCustomReportCatalog.FilterOperator}；**筛选值只进绑定参数**。
   */
  private Query buildQuery(
      UUID tenantId,
      List<ReportingCustomReportCatalog.Dimension> dimensions,
      List<FilterSpec> filters,
      List<String> sumCodes,
      List<String> lastCodes,
      Window window) {
    var params = new ArrayList<Object>();
    params.add(tenantId);
    // 🔴 `in (...)` 里是**全部**要取的基础指标码（流量型 + 存量型）。
    var allCodes = new ArrayList<String>(sumCodes);
    allCodes.addAll(lastCodes);
    for (var code : allCodes) {
      params.add(code);
    }
    params.add(window.to());

    var dimSelects = new ArrayList<String>();
    for (var index = 0; index < dimensions.size(); index++) {
      dimSelects.add(dimensions.get(index).expression("f") + " as d" + index);
    }
    if (dimSelects.isEmpty()) {
      dimSelects.add("1 as d0");
    }
    var dimColumns = dimColumnNames(dimensions.size());
    var dimList = String.join(", ", dimColumns);

    var filterSql = new StringBuilder();
    var filterParams = new ArrayList<Object>();
    for (var filter : filters) {
      var expression = filter.dimension().expression("f");
      var cast = filter.dimension().valueCast();
      switch (filter.operator()) {
        case EQ -> {
          filterSql.append(" and ").append(expression).append(" = ?::").append(cast);
          filterParams.add(filter.value());
        }
        case IN -> {
          var values = splitIn(filter.value());
          filterSql.append(" and ").append(expression).append(" in (");
          for (var index = 0; index < values.size(); index++) {
            if (index > 0) filterSql.append(", ");
            filterSql.append("?::").append(cast);
            filterParams.add(values.get(index));
          }
          filterSql.append(")");
        }
        case GTE -> {
          filterSql.append(" and ").append(expression).append(" >= ?::").append(cast);
          filterParams.add(filter.value());
        }
        case LTE -> {
          filterSql.append(" and ").append(expression).append(" <= ?::").append(cast);
          filterParams.add(filter.value());
        }
        case BETWEEN -> {
          filterSql
              .append(" and ")
              .append(expression)
              .append(" between ?::")
              .append(cast)
              .append(" and ?::")
              .append(cast);
          filterParams.add(filter.value());
          filterParams.add(filter.value2());
        }
      }
    }

    // 🔴 窗口下界**只对流量型指标生效**：存量型（LAST）取的是窗口末之前最近一天的快照，
    //   与 13.1.1.1 经营总览的口径**完全一致**（余额不能累加，也不能被窗口下界切掉）。
    var windowClause = new StringBuilder();
    params.add(window.from());
    if (lastCodes.isEmpty()) {
      windowClause.append(" and f.bucket_date >= ?::date");
    } else {
      windowClause.append(" and (f.bucket_date >= ?::date or f.metric_code in (");
      windowClause.append(placeholders(lastCodes.size()));
      windowClause.append("))");
      // 绑定参数（指标码来自口径表，**不是**用户输入）。
      params.addAll(lastCodes);
    }
    // ⚠️ 筛选参数**必须排在窗口参数之后** —— SQL 文本里的占位符顺序就是这个顺序。
    params.addAll(filterParams);

    var baseCte =
        "with base as (select "
            + String.join(", ", dimSelects)
            + ", f.metric_code as metric_code, f.currency as currency,"
            + " f.bucket_date as bucket_date, f.value as value"
            + " from reporting_metric_fact f"
            + " where f.tenant_id = ? and f.metric_code in ("
            + placeholders(allCodes.size())
            + ") and f.bucket_date <= ?::date"
            + windowClause
            + filterSql
            + ")";

    // 流量型（SUM）：窗口内把贡献行相加。
    var sumsCte =
        ", sums as (select b."
            + dimList
            + ", b.metric_code, b.currency, sum(b.value) as value from base b"
            + (lastCodes.isEmpty() ? "" : " where b.metric_code not in (" + placeholders(lastCodes.size()) + ")")
            + " group by b."
            + dimList
            + ", b.metric_code, b.currency)";
    if (!lastCodes.isEmpty()) {
      params.addAll(lastCodes);
    }

    // 存量型（LAST）：取**窗口末之前最近一天**的快照（余额不能累加）。
    var lastsCte =
        lastCodes.isEmpty()
            ? ""
            : ", lasts as (select b."
                + dimList
                + ", b.metric_code, b.currency, sum(b.value) as value from base b"
                + " where b.metric_code in ("
                + placeholders(lastCodes.size())
                + ") and b.bucket_date = (select max(b2.bucket_date) from base b2"
                + " where b2.metric_code = b.metric_code and b2.currency = b.currency)"
                + " group by b."
                + dimList
                + ", b.metric_code, b.currency)";
    if (!lastCodes.isEmpty()) {
      params.addAll(lastCodes);
    }

    // 全部分组（**不带分页**）—— 计数的依据。
    var union = "select " + dimList + " from sums";
    if (!lastCodes.isEmpty()) {
      union = union + " union all select " + dimList + " from lasts";
    }
    var allGroupsCte =
        ", all_groups as (select distinct g." + dimList + " from (" + union + ") g)";

    var joinCondition = new StringBuilder();
    for (var column : dimColumns) {
      if (joinCondition.length() > 0) joinCondition.append(" and ");
      joinCondition
          .append("g.")
          .append(column)
          .append(" is not distinct from s.")
          .append(column);
    }
    var joinLast = new StringBuilder();
    for (var column : dimColumns) {
      if (joinLast.length() > 0) joinLast.append(" and ");
      joinLast
          .append("g.")
          .append(column)
          .append(" is not distinct from l.")
          .append(column);
    }

    // 本页的分组（分页只作用在**分组**上 ⇒ 一组不会被拆到两页）。
    var pageGroupsCte =
        ", page_groups as (select * from all_groups order by " + dimList + " limit ? offset ?)";

    var resultCte =
        ", result as (select s."
            + dimList
            + ", s.metric_code, s.currency, s.value from sums s join page_groups g on "
            + joinCondition
            + (lastCodes.isEmpty()
                ? ""
                : " union all select l."
                    + dimList
                    + ", l.metric_code, l.currency, l.value from lasts l join page_groups g on "
                    + joinLast)
            + ")";

    var prefix = baseCte + sumsCte + lastsCte + allGroupsCte;
    var pageSql =
        prefix
            + pageGroupsCte
            + resultCte
            + " select "
            + dimList
            + ", metric_code, currency, value from result order by "
            + dimList
            + ", metric_code, currency";
    var countSql = prefix + " select count(*) from all_groups counted";
    return new Query(pageSql, countSql, params);
  }

  private String placeholders(int count) {
    return String.join(", ", java.util.Collections.nCopies(count, "?"));
  }

  // ---------------------------------------------------------------------------
  // 参数解析与校验
  // ---------------------------------------------------------------------------

  /** 解析一条筛选条件并**校验取值**（不是数字 / 不是 uuid ⇒ 400，不静默丢弃）。 */
  private FilterSpec parseFilter(ReportingCustomReportModels.CustomReportFilterRequest request) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "筛选条件不能为空");
    }
    var dimension = whitelist(() -> ReportingCustomReportCatalog.Dimension.of(request.dimensionCode()));
    var operator = whitelist(() -> ReportingCustomReportCatalog.FilterOperator.of(request.operator()));
    var value = request.valueText() == null ? "" : request.valueText().trim();
    var value2 = request.valueText2() == null ? "" : request.valueText2().trim();
    switch (operator) {
      case EQ, GTE, LTE -> {
        if (value.isEmpty()) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "筛选值不能为空（维度：" + dimension.label() + "）");
        }
        validateValue(dimension, value);
      }
      case IN -> {
        var values = splitIn(value);
        if (values.isEmpty()) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "「属于」筛选至少要一个值（维度：" + dimension.label() + "）");
        }
        for (var item : values) {
          validateValue(dimension, item);
        }
      }
      case BETWEEN -> {
        if (value.isEmpty() || value2.isEmpty()) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "「介于」筛选需要上下两个值（维度：" + dimension.label() + "）");
        }
        validateValue(dimension, value);
        validateValue(dimension, value2);
      }
    }
    return new FilterSpec(dimension, operator, value, value2);
  }

  /** 校验筛选值的形状（日期 / uuid / 币种 / 归属维度）。 */
  private void validateValue(ReportingCustomReportCatalog.Dimension dimension, String value) {
    switch (dimension) {
      case BUCKET_DATE -> {
        try {
          LocalDate.parse(value);
        } catch (RuntimeException e) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "「日历日」维度的筛选值必须是 YYYY-MM-DD（收到的是「" + value + "」）");
        }
      }
      case LEGAL_ENTITY, SHOP -> {
        try {
          UUID.fromString(value);
        } catch (RuntimeException e) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "「" + dimension.label() + "」维度的筛选值必须是 uuid（收到的是「" + value + "」）");
        }
      }
      case CURRENCY -> {
        if (!value.matches("^[A-Za-z]{3}$")) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "「币种」维度的筛选值必须是 3 位字母（收到的是「" + value + "」）");
        }
      }
      case SCOPE_TYPE -> {
        var normalized = value.toUpperCase(Locale.ROOT);
        if (!normalized.equals("COMPANY")
            && !normalized.equals("LEGAL_ENTITY")
            && !normalized.equals("SHOP")) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "「公司 / 法人 / 店铺」维度的筛选值只能是 COMPANY / LEGAL_ENTITY / SHOP"
                  + "（收到的是「" + value + "」）");
        }
      }
    }
  }

  /** 逗号分隔的取值列表。 */
  private List<String> splitIn(String value) {
    var out = new ArrayList<String>();
    if (value == null) return out;
    for (var part : value.split(",")) {
      var item = part.trim();
      if (!item.isEmpty()) out.add(item);
    }
    return out;
  }

  /** 可见范围（PRIVATE / TEAM）。 */
  private String normalizeVisibility(String visibility) {
    if (visibility == null || visibility.isBlank()) return "PRIVATE";
    var normalized = visibility.trim().toUpperCase(Locale.ROOT);
    if (!normalized.equals("PRIVATE") && !normalized.equals("TEAM")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "可见范围只能是 PRIVATE（个人）或 TEAM（团队）");
    }
    return normalized;
  }

  /**
   * 白名单解析失败 ⇒ **400**（说清差在哪）。
   *
   * <p>⚠️ 白名单解析（维度码 / 运算符 / 图表类型 / cron）抛的是
   * {@link IllegalArgumentException}；这里翻译成带原始原因的 400，
   * 让调用方看到「差在哪」而不是一个笼统的「格式不正确」。
   */
  private <T> T whitelist(java.util.function.Supplier<T> work) {
    try {
      return work.get();
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /** 白名单解析失败 ⇒ **400**（无返回值的那一类）。 */
  private void whitelistVoid(Runnable work) {
    try {
      work.run();
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  private String requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能为空");
    }
    return value.trim();
  }

  private List<String> orEmpty(List<String> values) {
    return values == null ? List.of() : values;
  }

  private <T> List<T> orEmptyItems(List<T> values) {
    return values == null ? List.of() : values;
  }

  private List<Object> append(List<Object> params, Object... extra) {
    var out = new ArrayList<>(params);
    out.addAll(Arrays.asList(extra));
    return out;
  }

  /** 分享令牌：32 位十六进制（128 bit 熵，不可枚举）。 */
  private String randomToken() {
    var bytes = new byte[16];
    new java.security.SecureRandom().nextBytes(bytes);
    var out = new StringBuilder();
    for (var value : bytes) {
      out.append(String.format("%02x", value));
    }
    return out.toString();
  }

  /** 数据更新时间（卷 13 卷首「数据延迟要显示」）。 */
  private OffsetDateTime dataUpdatedAt(UUID tenantId) {
    var factRow =
        sql.fetchOne("select max(updated_at) from reporting_metric_fact where tenant_id = ?", tenantId);
    var eventRow =
        sql.fetchOne(
            "select max(applied_at) from reporting_projection_event where tenant_id = ?", tenantId);
    var factAt = factRow == null ? null : factRow.get(0, OffsetDateTime.class);
    var eventAt = eventRow == null ? null : eventRow.get(0, OffsetDateTime.class);
    if (factAt == null && eventAt == null) return null;
    if (factAt == null) return eventAt;
    if (eventAt == null) return factAt;
    return factAt.isAfter(eventAt) ? factAt : eventAt;
  }

  /** 订阅计划 → 出参。 */
  private List<ReportingCustomReportModels.CustomReportSubscriptionView> subscriptionViews(
      List<org.jooq.Record> rows) {
    var out = new ArrayList<ReportingCustomReportModels.CustomReportSubscriptionView>();
    for (var row : rows) {
      out.add(
          new ReportingCustomReportModels.CustomReportSubscriptionView(
              row.get(0, UUID.class).toString(),
              row.get(1, UUID.class).toString(),
              row.get(2, String.class),
              row.get(3, String.class),
              row.get(4, String.class),
              row.get(5, String.class),
              row.get(6, String.class),
              Boolean.TRUE.equals(row.get(7, Boolean.class)),
              row.get(8, OffsetDateTime.class),
              row.get(9, OffsetDateTime.class),
              row.get(10, OffsetDateTime.class) == null ? "" : row.get(10, OffsetDateTime.class).toString()));
    }
    return out;
  }

  /** 供测试断言用：UTC 归一。 */
  static OffsetDateTime utcNow(Clock clock) {
    return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
  }
}
