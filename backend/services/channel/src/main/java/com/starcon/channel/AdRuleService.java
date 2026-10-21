package com.starcon.channel;

import static com.starcon.channel.AdRuleModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 14.2.2.2 自动规则。
 *
 * <p>蓝图：「自动化规则：按条件自动调整预算/竞价/启停（ACOS 超标降价、ROAS 达标加预算、消耗完毕暂停、花费无转化停投），
 * 支持规则优先级、单次调整上限与总上限、试运行模式。输入：规则定义；实时广告表现；上下限保护参数。输出：规则命中记录 +
 * 调整执行记录 + 效果回溯。关系：上游 = 14.3 报表；下游 = 平台广告 API。<b>自动规则必须有上限与试运行</b>——没有上限的自动调价一次
 * 异常就能烧掉大量预算。」
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>规则定义</b> → {@code ad_rule} + {@code ad_rule_condition}（V275）。上限、上下限保护、优先级、试运行开关全是**列**，
 *       不是代码里的 if。
 *   <li><b>实时广告表现</b> → {@code ad_rule_metric}：本模块**自持**的指标表，经一个显式的指标登记写入口落库。
 *   <li><b>规则命中记录</b> → {@code ad_rule_hit}：每一条评估都留痕（命中与未命中都留）。
 *   <li><b>调整执行记录</b> → {@code ad_rule_execution} + {@code ad_rule_dispatch}：前者记决策与闸门结论，后者记**待下发**动作。
 *   <li><b>效果回溯</b> → {@code ad_rule_effect}：执行前后的指标变化与结论。
 * </ul>
 *
 * <p>🔴 蓝图加粗句「自动规则必须有上限与试运行」在这里是**执行路径上的三道闸**，不是一句注释：
 *
 * <ol>
 *   <li><b>单次上限</b>：单次调整幅度超过「金额上限」与「比例上限」中更严的那个 ⇒ <b>拒绝并留痕</b>
 *       （{@code outcome=REJECTED, cap_check=SINGLE_CAP_EXCEEDED}），<b>不是截断</b>。
 *       截断会让人以为「规则按预期工作」，而实际上调了另一个数。
 *   <li><b>总上限</b>：窗口内累计金额 / 次数超限 ⇒ 同样拒绝并留痕（{@code TOTAL_CAP_EXCEEDED}）。
 *   <li><b>上下限保护</b>：调整后的预算 / 出价越过 min/max ⇒ 拒绝并留痕（{@code BOUND_VIOLATION}）。
 *   <li><b>试运行</b>：规则自身 {@code dry_run} 为真、或本次运行口径是 {@code DRY_RUN} ⇒ 照常匹配、照常产生命中记录与
 *       「本来会变成多少」，但**不产生实际调整**（{@code outcome=DRY_RUN}），也不产生待下发动作。
 * </ol>
 *
 * <p>🔴 优先级是真的参与判定：同一目标（平台 + 店铺 + 层级 + 外部活动 id + 外部关键词 id）上多条规则同时命中时，
 * <b>按 priority 升序取一条</b>（数字小 = 优先高），落选的那些**照样留痕**并写明「为什么没选它」。
 *
 * <p>🔴 数据来源纪律：平台码表、店铺**同库同服务**读（V1），本类不复制它们的任何一列；
 * <b>不引用 14.1.1.1 活动列表的任何表</b>（同批并行车道）—— 规则按外部 id 定位对象，指标落在本模块自己的表里，
 * 与 14.1.1.1 零耦合、合并时不会互相破坏。
 *
 * <p>🔴 比率口径：ACOS = 花费 / 归因销售额，ROAS = 归因销售额 / 花费；**分母为 0（或归因销售额为空）返回空值**，
 * 绝不返回 0。「压根算不出来」与「算出来是零」在自动调价里是相反的两件事。
 */
@Service
public class AdRuleService {

  /** 指标口径里用**比率 / 计数**阈值（NUMERIC(19,6)）的那些。 */
  private static final Set<String> VALUE_METRICS =
      Set.of("ACOS", "ROAS", "CONVERSIONS", "CLICKS", "IMPRESSIONS");

  /** 指标口径里用**金额**阈值（NUMERIC(19,4)）的那些。 */
  private static final Set<String> AMOUNT_METRICS = Set.of("SPEND", "REVENUE");

  private static final Set<String> ACTION_TYPES =
      Set.of("DECREASE_BID", "INCREASE_BID", "DECREASE_BUDGET", "INCREASE_BUDGET", "PAUSE", "RESUME");

  private static final Set<String> BID_ACTIONS = Set.of("DECREASE_BID", "INCREASE_BID");
  private static final Set<String> BUDGET_ACTIONS = Set.of("DECREASE_BUDGET", "INCREASE_BUDGET");

  private static final String TENANT_FILTER =
      " where {alias}.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid";

  private final DSLContext sql;
  private final ObjectMapper json;

  public AdRuleService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ==================================================================
  // 小工具（照抄 AdTypeDiffService 的同名写法）
  // ==================================================================

  private <T> T one(Class<T> type, String query, Object... binds) {
    Record row = sql.fetchOne(query, binds);
    return row == null ? null : row.get(0, type);
  }

  private long count(String query, Object... binds) {
    Long v = one(Long.class, query, binds);
    return v == null ? 0L : v;
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankToNull(String value) {
    String trimmed = text(value);
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static BigDecimal money(BigDecimal value) {
    return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
  }

  private static BigDecimal ratio(BigDecimal value) {
    return value == null ? null : value.setScale(6, RoundingMode.HALF_UP);
  }

  private static Object[] concat(List<Object> binds, Object... tail) {
    Object[] all = new Object[binds.size() + tail.length];
    for (int i = 0; i < binds.size(); i++) {
      all[i] = binds.get(i);
    }
    System.arraycopy(tail, 0, all, binds.size(), tail.length);
    return all;
  }

  /** 当前事务内的租户（由 backend/libraries/tenancy 在进入事务时绑定，不来自任何客户端头）。 */
  private UUID currentTenant() {
    String raw = one(String.class, "select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(raw);
  }

  /** 跨租户与不存在返回**逐字相同**的 404（照抄同服务同款口径）。 */
  private ResponseStatusException notFound(String what) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what + "不存在");
  }

  private ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static UUID parseId(String value) {
    try {
      return UUID.fromString(text(value));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id 不是合法 UUID");
    }
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  public AdRuleCapabilities capabilities() {
    return new AdRuleCapabilities(
        List.of(
            new AdRuleCapabilityDimension(
                "rule_definition",
                "规则定义（条件、动作、优先级、单次上限、总上限、上下限保护、试运行开关）",
                true,
                "ad_rule + ad_rule_condition（V275）",
                "",
                "没有上限与试运行的规则建不出来：上限是必填列（> 0），试运行默认开"),
            new AdRuleCapabilityDimension(
                "metric_intake",
                "广告表现数据登记（本模块自持的指标表 + 显式写入口）",
                true,
                "ad_rule_metric（V275）",
                "",
                "上游 14.3 报表未开发，指标必须先登记进来；没有登记 = 规则没有可判定的数据"),
            new AdRuleCapabilityDimension(
                "hit_record",
                "规则命中记录（命中与未命中都留痕，未命中写清差在哪）",
                true,
                "ad_rule_hit（V275）",
                "",
                "只留命中不留未命中，就无法回答「为什么这条规则一直没生效」"),
            new AdRuleCapabilityDimension(
                "execution_record",
                "调整执行记录（动作 + 幅度 + 前后值 + 逐项闸门结论）",
                true,
                "ad_rule_execution（V275）",
                "",
                "被闸门拒绝的调整不留痕，就会以为规则按预期在工作"),
            new AdRuleCapabilityDimension(
                "effect_review",
                "效果回溯（执行前后的指标变化 + 结论）",
                true,
                "ad_rule_effect（V275）",
                "",
                "没有回溯就只有「规则命中过」这个事实，答不出「它管用吗」"),
            new AdRuleCapabilityDimension(
                "platform_dispatch",
                "平台侧真实下发（把待下发动作发到平台广告 API）",
                false,
                "—",
                "平台广告适配器（未交付）",
                "调整只会进入「待下发」队列，平台侧的预算 / 出价 / 状态在通道接上之前**不会真的变**；"
                    + "界面必须显示「待下发」而不是「已生效」"),
            new AdRuleCapabilityDimension(
                "report_upstream",
                "上游 14.3 报表（广告表现数据自动流入）",
                false,
                "—",
                "14.3.1.1 活动报表",
                "广告表现数据必须经本模块的指标登记写入口进来，不会自动从平台同步；"
                    + "「没有数据」与「没同步到」在界面上是分开显示的"),
            new AdRuleCapabilityDimension(
                "campaign_catalog",
                "14.1.1.1 活动列表（活动名称、分组、归属）",
                false,
                "—",
                "14.1.1.1 活动列表",
                "规则按**外部活动 id** 定位对象，所以本模块独立成立；但列表里显示不出活动名称与分组，"
                    + "运营要自己认外部 id"),
            new AdRuleCapabilityDimension(
                "budget_allocation",
                "14.2.2.1 预算分配（跨店铺 / 跨活动的预算再平衡）",
                false,
                "—",
                "14.2.2.1 预算分配",
                "本模块只做「规则命中后的单对象调整」，不做跨活动再平衡；两者职责不同，不要互相顶替")),
        List.of("DECREASE_BID", "INCREASE_BID", "DECREASE_BUDGET", "INCREASE_BUDGET", "PAUSE", "RESUME"),
        List.of("PERCENT", "ABSOLUTE"),
        List.of("ACOS", "ROAS", "SPEND", "REVENUE", "CONVERSIONS", "CLICKS", "IMPRESSIONS"),
        List.of("GT", "GTE", "LT", "LTE", "EQ"),
        List.of("CAMPAIGN", "KEYWORD"),
        List.of("DRY_RUN", "APPLY"),
        List.of("DRY_RUN", "APPLIED", "REJECTED"),
        List.of("IMPROVED", "WORSENED", "UNCHANGED", "INSUFFICIENT_DATA"),
        "🔴 上限是执行路径上的**强制闸门**，不是提示：单次调整幅度超过「单次金额上限」与「单次比例上限」中"
            + "更严的那个 ⇒ 拒绝并留痕（outcome=REJECTED / cap_check=SINGLE_CAP_EXCEEDED），**绝不静默截断**；"
            + "窗口内累计金额或次数超过总上限 ⇒ 拒绝并留痕（TOTAL_CAP_EXCEEDED）；调整后的预算 / 出价越过上下限保护 ⇒ "
            + "拒绝并留痕（BOUND_VIOLATION）。截断会让人以为「规则按预期工作」，而实际上调了另一个数。",
        "🔴 试运行是**一等的模式**：规则自带 dry_run 开关（默认 true，新建规则一律先试运行），本次运行也有 run_mode。"
            + "只要有一处是试运行，这次执行就只算不调 —— 照常匹配、照常产生命中记录、照常算出「本来会变成多少」，"
            + "但不产生实际调整，也不产生待下发动作。执行记录里 outcome=DRY_RUN 与 outcome=APPLIED **分得开**，"
            + "事后能说清「上周那次到底执行了没有」。",
        "🔴 优先级真的参与判定：同一目标（平台 + 店铺 + 层级 + 外部活动 id + 外部关键词 id）上多条规则同时命中时，"
            + "按 priority 升序取一条（数字小 = 优先高），**落选的规则照样留痕**并写明「为什么没选它」。"
            + "不做多条规则叠加：叠加会让上限判定失去意义（每条都以为自己只动了一点）。",
        "🔴 下游 = 平台广告 API，本模块**不实现**平台侧真实下发：调整只落成「待下发动作」"
            + "（ad_rule_dispatch，dispatch_status=PENDING），载荷按平台适配器要什么放什么，不猜平台字段名。"
            + "真实通道接上以后从这张表取 PENDING 的行发出去即可。在那之前，界面上必须写「待下发」。",
        "上游 14.3 报表**未开发**，广告表现数据由本模块自己的 ad_rule_metric 表承载，经一个显式的指标登记写入口落库；"
            + "每一行都带**数据截至时间**（metrics_as_of），因此「没有数据」与「有数据但没同步到」在界面上是分开显示的。");
  }

  // ==================================================================
  // 规则
  // ==================================================================

  private static final String RULE_COLUMNS =
      """
       select r.id, r.rule_code, r.rule_name, r.platform_id, p.code as platform_code,
              r.shop_code, r.target_type, r.external_campaign_id, r.external_keyword_id,
              r.action_type, r.adjustment_mode, r.adjustment_value,
              r.single_adjust_cap_amount, r.single_adjust_cap_percent,
              r.total_cap_amount, r.total_cap_count, r.total_cap_window_hours,
              r.min_budget, r.max_budget, r.min_bid, r.max_bid,
              r.priority, r.dry_run, r.evaluation_window_hours, r.status, r.note, r.version,
              r.created_at, r.updated_at,
              (select count(*) from ad_rule_condition c
                where c.tenant_id = r.tenant_id and c.rule_id = r.id) as condition_count,
              (select max(m.metrics_as_of) from ad_rule_metric m
                where m.tenant_id = r.tenant_id and m.platform_id = r.platform_id
                  and m.shop_code = r.shop_code and m.metric_scope = r.target_type
                  and (r.external_campaign_id = '' or m.external_campaign_id = r.external_campaign_id)
                  and (r.external_keyword_id = '' or m.external_keyword_id = r.external_keyword_id)
              ) as latest_metrics_as_of
         from ad_rule r join platform p on p.id = r.platform_id
      """;

  private AdRuleRow ruleRow(Record r) {
    OffsetDateTime latest = r.get("latest_metrics_as_of", OffsetDateTime.class);
    int window = r.get("evaluation_window_hours", Integer.class);
    // 🔴 「没有数据」与「没同步到」必须分得开：
    //   latest == null ⇒ 压根没有指标数据；latest != null 但早于评估窗口 ⇒ 数据过期、没同步到。
    boolean stale = latest != null && window > 0
        && latest.isBefore(OffsetDateTime.now().minusHours(window));
    return new AdRuleRow(
        r.get("id", UUID.class),
        r.get("rule_code", String.class),
        r.get("rule_name", String.class),
        r.get("platform_id", UUID.class),
        r.get("platform_code", String.class),
        r.get("shop_code", String.class),
        r.get("target_type", String.class),
        r.get("external_campaign_id", String.class),
        r.get("external_keyword_id", String.class),
        r.get("action_type", String.class),
        r.get("adjustment_mode", String.class),
        r.get("adjustment_value", BigDecimal.class),
        r.get("single_adjust_cap_amount", BigDecimal.class),
        r.get("single_adjust_cap_percent", BigDecimal.class),
        r.get("total_cap_amount", BigDecimal.class),
        r.get("total_cap_count", Integer.class),
        r.get("total_cap_window_hours", Integer.class),
        r.get("min_budget", BigDecimal.class),
        r.get("max_budget", BigDecimal.class),
        r.get("min_bid", BigDecimal.class),
        r.get("max_bid", BigDecimal.class),
        r.get("priority", Integer.class),
        r.get("dry_run", Boolean.class),
        r.get("evaluation_window_hours", Integer.class),
        r.get("status", String.class),
        r.get("condition_count", Integer.class),
        latest,
        stale,
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /** 规则列表：真分页 + 真筛选。 */
  public AdRulePage rules(
      String status, UUID platformId, String shopCode, String targetType, Boolean dryRun, int page, int size) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where =
        new StringBuilder(
            " where r.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid");
    String state = blankToNull(status);
    if (state != null) {
      where.append(" and r.status = ?");
      binds.add(state);
    }
    if (platformId != null) {
      where.append(" and r.platform_id = ?");
      binds.add(platformId);
    }
    String shop = blankToNull(shopCode);
    if (shop != null) {
      where.append(" and r.shop_code = ?");
      binds.add(shop);
    }
    String scope = blankToNull(targetType);
    if (scope != null) {
      where.append(" and r.target_type = ?");
      binds.add(scope);
    }
    if (dryRun != null) {
      where.append(" and r.dry_run = ?");
      binds.add(dryRun);
    }

    long total = count("select count(*) from ad_rule r" + where, binds.toArray());

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    List<AdRuleRow> items =
        sql.fetch(
                RULE_COLUMNS + where + " order by r.priority asc, r.created_at asc, r.id asc limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::ruleRow)
            .toList();

    long dryRunCount =
        count("select count(*) from ad_rule r" + where + " and r.dry_run = true", binds.toArray());
    long activeCount =
        count(
            "select count(*) from ad_rule r" + where + " and r.status = 'ACTIVE'",
            binds.toArray());

    return new AdRulePage(items, total, safePage, safeSize, dryRunCount, activeCount);
  }

  /** 规则详情：规则本体 + 条件 + 最近一次评估的结论。 */
  public AdRuleDetail rule(String id) {
    UUID ruleId = parseId(id);
    Record row =
        sql.fetchOne(RULE_COLUMNS + TENANT_FILTER.replace("{alias}", "r") + " and r.id = ?", ruleId);
    if (row == null) {
      throw notFound("自动规则");
    }
    return new AdRuleDetail(
        ruleRow(row), conditions(ruleId.toString()), lastRun(ruleId), "规则条件是 AND 语义：全部成立才算命中。");
  }

  /** 某条规则最近一次评估的结论。 */
  private AdRuleLastRun lastRun(UUID ruleId) {
    // 🔴 用 order by limit 取最近一次，**不用 max(uuid)**：PostgreSQL 的 max() 不接受 uuid。
    Record r =
        sql.fetchOne(
            """
            select x.id, x.run_mode, x.status, x.hit_count, x.selected_count,
                   x.applied_count, x.dry_run_count, x.rejected_count, x.started_at
              from ad_rule_run x
             where x.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
               and x.id = (select r2.id
                            from ad_rule_run r2
                            join ad_rule_hit h on h.tenant_id = r2.tenant_id and h.run_id = r2.id
                           where r2.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
                             and h.rule_id = ?
                           order by r2.started_at desc, r2.id desc
                           limit 1)
            """,
            ruleId);
    if (r == null) {
      return null;
    }
    return new AdRuleLastRun(
        r.get("id", UUID.class),
        r.get("run_mode", String.class),
        r.get("status", String.class),
        r.get("hit_count", Integer.class),
        r.get("selected_count", Integer.class),
        r.get("applied_count", Integer.class),
        r.get("dry_run_count", Integer.class),
        r.get("rejected_count", Integer.class),
        r.get("started_at", OffsetDateTime.class));
  }

  /** 规则条件。 */
  public List<AdRuleConditionRow> conditions(String id) {
    UUID ruleId = parseId(id);
    requireRule(ruleId);
    return sql.fetch(
            """
            select c.id, c.rule_id, c.metric, c.operator, c.threshold_value, c.threshold_amount,
                   c.note, c.position, c.created_at
              from ad_rule_condition c
             where c.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
               and c.rule_id = ?
             order by c.position asc, c.id asc
            """,
            ruleId)
        .stream()
        .map(
            r ->
                new AdRuleConditionRow(
                    r.get("id", UUID.class),
                    r.get("rule_id", UUID.class),
                    r.get("metric", String.class),
                    r.get("operator", String.class),
                    r.get("threshold_value", BigDecimal.class),
                    r.get("threshold_amount", BigDecimal.class),
                    r.get("note", String.class),
                    r.get("position", Integer.class),
                    r.get("created_at", OffsetDateTime.class)))
        .toList();
  }

  private void requireRule(UUID ruleId) {
    if (one(UUID.class, "select id from ad_rule" + TENANT_FILTER.replace("{alias}", "ad_rule") + " and id = ?", ruleId)
        == null) {
      throw notFound("自动规则");
    }
  }

  /**
   * 登记 / 改一条规则。
   *
   * <p>🔴 上限是**必填且必须为正**的：请求模型里它们没有 null 的语义位置，DB 层还有 CHECK 兜底。
   * 一条没有上限的规则在服务层就被拒 —— 这是蓝图加粗句「必须有上限」的第一道闸。
   */
  public AdRuleDetail saveRule(SaveRuleRequest request) {
    UUID tenant = currentTenant();
    String code = text(request.ruleCode());
    String name = text(request.ruleName());
    String action = text(request.actionType());
    String mode = text(request.adjustmentMode());
    String targetType = text(request.targetType());
    String status = text(request.status());

    if (code.isEmpty() || name.isEmpty()) {
      throw badRequest("规则编码与规则名称不能为空");
    }
    if (!ACTION_TYPES.contains(action)) {
      throw badRequest("动作类型不合法：" + action);
    }
    if (!Set.of("PERCENT", "ABSOLUTE").contains(mode)) {
      throw badRequest("调整幅度口径不合法：" + mode);
    }
    if (!Set.of("CAMPAIGN", "KEYWORD").contains(targetType)) {
      throw badRequest("作用对象层级不合法：" + targetType);
    }
    if (!Set.of("ACTIVE", "PAUSED", "ARCHIVED").contains(status)) {
      throw badRequest("状态不合法：" + status);
    }

    // 🔴 上限校验：一条没有上限的规则不许建。
    if (request.singleAdjustCapAmount() == null
        || request.singleAdjustCapAmount().signum() <= 0) {
      throw badRequest("单次调整金额上限必须为正数 —— 没有上限的自动调价一次异常就能烧掉大量预算");
    }
    if (request.totalCapAmount() == null || request.totalCapAmount().signum() <= 0) {
      throw badRequest("总上限（窗口内累计金额）必须为正数 —— 没有上限的自动调价一次异常就能烧掉大量预算");
    }
    if (request.totalCapCount() <= 0) {
      throw badRequest("总上限（窗口内累计次数）必须至少为 1");
    }
    if (request.totalCapWindowHours() <= 0) {
      throw badRequest("总上限的累计窗口长度必须至少为 1 小时");
    }
    if (request.singleAdjustCapPercent() != null && request.singleAdjustCapPercent().signum() < 0) {
      throw badRequest("单次调整比例上限不能为负");
    }

    // 幅度与动作必须自洽
    BigDecimal adjustmentValue = nz(request.adjustmentValue());
    if (action.equals("PAUSE") || action.equals("RESUME")) {
      if (adjustmentValue.signum() != 0) {
        throw badRequest("启停动作（PAUSE / RESUME）没有调整幅度，幅度必须为 0");
      }
    } else if (adjustmentValue.signum() <= 0) {
      throw badRequest("调整幅度必须大于 0");
    }

    // 上下限必须自洽
    if (request.maxBudget() != null
        && request.minBudget() != null
        && request.maxBudget().compareTo(request.minBudget()) < 0) {
      throw badRequest("预算上限不能低于预算下限");
    }
    if (request.maxBid() != null
        && request.minBid() != null
        && request.maxBid().compareTo(request.minBid()) < 0) {
      throw badRequest("出价上限不能低于出价下限");
    }

    // 作用对象必须真的指到某个外部对象
    String campaignId = text(request.externalCampaignId());
    String keywordId = text(request.externalKeywordId());
    if (campaignId.isEmpty() && keywordId.isEmpty()) {
      throw badRequest("规则必须给出外部活动 id 或外部关键词 id —— 规则靠外部引用定位广告对象");
    }
    if (targetType.equals("KEYWORD") && keywordId.isEmpty()) {
      throw badRequest("关键词级规则必须给出外部关键词 id");
    }

    // 条件校验（AND 语义，至少一条）
    List<SaveRuleConditionRequest> conditions = request.conditions();
    if (conditions == null || conditions.isEmpty()) {
      throw badRequest("规则至少要有一个条件 —— 没有条件的规则永远不会命中");
    }
    for (SaveRuleConditionRequest c : conditions) {
      String metric = text(c.metric());
      if (!VALUE_METRICS.contains(metric) && !AMOUNT_METRICS.contains(metric)) {
        throw badRequest("条件指标口径不合法：" + metric);
      }
      if (!Set.of("GT", "GTE", "LT", "LTE", "EQ").contains(text(c.operator()))) {
        throw badRequest("条件比较符不合法：" + c.operator());
      }
      if (VALUE_METRICS.contains(metric)) {
        if (c.thresholdAmount() != null) {
          throw badRequest(metric + " 是比率 / 计数口径，阈值不能填 thresholdAmount（单位不同，混填会让记录无法分辨）");
        }
        if (c.thresholdValue() == null) {
          throw badRequest(metric + " 是比率 / 计数口径，阈值必须填 thresholdValue");
        }
      } else {
        if (c.thresholdValue() != null) {
          throw badRequest(metric + " 是金额口径，阈值不能填 thresholdValue（单位不同，混填会让记录无法分辨）");
        }
        if (c.thresholdAmount() == null) {
          throw badRequest(metric + " 是金额口径，阈值必须填 thresholdAmount");
        }
      }
    }

    // 平台 / 店铺必须存在，且平台要与店铺一致
    String platformCode = one(String.class, "select code from platform where id = ?", request.platformId());
    if (platformCode == null) {
      throw notFound("平台");
    }
    String shopCode = text(request.shopCode());
    UUID shopPlatform =
        one(
            UUID.class,
            "select platform_id from shop"
                + " where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and code = ?",
            shopCode);
    if (shopPlatform == null) {
      throw notFound("店铺");
    }
    if (!shopPlatform.equals(request.platformId())) {
      throw badRequest("规则的平台与店铺所属平台不一致 —— 指标永远匹配不上，规则会静默不生效");
    }

    // 规则编码租户内唯一
    UUID sameCode =
        one(
            UUID.class,
            "select id from ad_rule"
                + " where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and rule_code = ?",
            code);
    UUID id = request.id() == null ? Ids.next() : request.id();
    if (sameCode != null && !sameCode.equals(id)) {
      throw conflict("规则编码已存在：" + code);
    }

    OffsetDateTime now = OffsetDateTime.now();
    boolean exists =
        one(
                UUID.class,
                "select id from ad_rule"
                    + " where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                    + " and id = ?",
                id)
            != null;

    // 🔴 插入与更新共用同一份列清单：手写两遍迟早会对不上（本次就差点多写一列），
    //    所以列名、占位符、绑定值全部由这一份清单派生。
    List<String> ruleColumns =
        List.of(
            "rule_code", "rule_name", "platform_id", "shop_code", "target_type",
            "external_campaign_id", "external_keyword_id", "action_type", "adjustment_mode",
            "adjustment_value", "single_adjust_cap_amount", "single_adjust_cap_percent",
            "total_cap_amount", "total_cap_count", "total_cap_window_hours",
            "min_budget", "max_budget", "min_bid", "max_bid",
            "priority", "dry_run", "evaluation_window_hours", "status", "note");

    // 🔴 updated_at / created_at 走 timestamptz：jOOQ 3.19 会把 OffsetDateTime 绑成 varchar，
    //    PG 直接拒 —— 所以 values 位置必须写 `?::timestamptz`。
    List<String> allColumns = new ArrayList<>(ruleColumns);
    allColumns.add("updated_at");
    allColumns.add("created_at");

    // 🔴 上下限保护等列可以是 null，所以这里**不能**用 List.of（它不接受 null 元素）。
    List<Object> values = new ArrayList<>();
    values.add(code);
    values.add(name);
    values.add(request.platformId());
    values.add(shopCode);
    values.add(targetType);
    values.add(campaignId);
    values.add(keywordId);
    values.add(action);
    values.add(mode);
    values.add(adjustmentValue);
    values.add(request.singleAdjustCapAmount());
    values.add(nz(request.singleAdjustCapPercent()));
    values.add(request.totalCapAmount());
    values.add(request.totalCapCount());
    values.add(request.totalCapWindowHours());
    values.add(request.minBudget());
    values.add(request.maxBudget());
    values.add(request.minBid());
    values.add(request.maxBid());
    values.add(request.priority());
    values.add(request.dryRun());
    values.add(request.evaluationWindowHours());
    values.add(status);
    values.add(text(request.note()));
    values.add(now);
    values.add(now);

    if (exists) {
      StringBuilder assignments = new StringBuilder();
      for (int i = 0; i < ruleColumns.size(); i++) {
        if (i > 0) assignments.append(", ");
        assignments.append(ruleColumns.get(i)).append(" = ?");
      }
      assignments.append(", updated_at = ?::timestamptz");
      List<Object> updateValues = new ArrayList<>(values.subList(0, ruleColumns.size()));
      updateValues.add(now);

      sql.execute(
          "update ad_rule set " + assignments + ", version = ad_rule.version + 1"
              + " where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and id = ?",
          concat(updateValues, id));
      // 条件整体替换：AND 语义的一组条件，增删都走同一条路径，不留半截旧条件。
      sql.execute(
          "delete from ad_rule_condition"
              + " where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and rule_id = ?",
          id);
    } else {
      StringBuilder placeholders = new StringBuilder();
      for (int i = 0; i < ruleColumns.size(); i++) {
        placeholders.append(", ?");
      }
      placeholders.append(", ?::timestamptz, ?::timestamptz");

      List<Object> insertValues = new ArrayList<>();
      insertValues.add(id);
      insertValues.addAll(values);
      sql.execute(
          "insert into ad_rule (tenant_id, id, " + String.join(", ", allColumns) + ")"
              + " values (nullif(current_setting('app.current_tenant',true),'')::uuid, ?"
              + placeholders + ")",
          insertValues.toArray());
    }

    int position = 0;
    for (SaveRuleConditionRequest c : conditions) {
      sql.execute(
          """
          insert into ad_rule_condition
            (tenant_id, id, rule_id, metric, operator, threshold_value, threshold_amount, note, position, created_at)
          values (nullif(current_setting('app.current_tenant',true),'')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz)
          """,
          Ids.next(),
          id,
          text(c.metric()),
          text(c.operator()),
          c.thresholdValue(),
          c.thresholdAmount(),
          text(c.note()),
          c.position() > 0 ? c.position() : position * 10,
          now);
      position++;
    }

    return rule(id.toString());
  }

  /** 启停一条规则。 */
  public AdRuleRow setRuleStatus(String id, String status) {
    UUID ruleId = parseId(id);
    requireRule(ruleId);
    String state = text(status);
    if (!Set.of("ACTIVE", "PAUSED", "ARCHIVED").contains(state)) {
      throw badRequest("状态不合法：" + state);
    }
    sql.execute(
        "update ad_rule set status = ?, version = ad_rule.version + 1, updated_at = ?::timestamptz"
            + " where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and id = ?",
        state,
        OffsetDateTime.now(),
        ruleId);
    return rule(id).rule();
  }

  // ==================================================================
  // 指标（本模块自持的写入口）
  // ==================================================================

  private static final String METRIC_COLUMNS =
      """
       select m.id, m.platform_id, p.code as platform_code, m.shop_code, m.metric_scope,
              m.external_campaign_id, m.external_keyword_id, m.currency,
              m.delivery_status, m.current_budget, m.current_bid,
              m.impressions, m.clicks, m.conversions, m.spend, m.attributed_revenue,
              m.acos, m.roas, m.metrics_as_of, m.window_start, m.window_end,
              m.source_ref, m.note, m.created_at, m.updated_at
         from ad_rule_metric m join platform p on p.id = m.platform_id
      """;

  private AdRuleMetricRow metricRow(Record r) {
    return new AdRuleMetricRow(
        r.get("id", UUID.class),
        r.get("platform_id", UUID.class),
        r.get("platform_code", String.class),
        r.get("shop_code", String.class),
        r.get("metric_scope", String.class),
        r.get("external_campaign_id", String.class),
        r.get("external_keyword_id", String.class),
        r.get("currency", String.class),
        r.get("delivery_status", String.class),
        r.get("current_budget", BigDecimal.class),
        r.get("current_bid", BigDecimal.class),
        r.get("impressions", BigDecimal.class),
        r.get("clicks", BigDecimal.class),
        r.get("conversions", BigDecimal.class),
        r.get("spend", BigDecimal.class),
        r.get("attributed_revenue", BigDecimal.class),
        r.get("acos", BigDecimal.class),
        r.get("roas", BigDecimal.class),
        r.get("metrics_as_of", OffsetDateTime.class),
        r.get("window_start", OffsetDateTime.class),
        r.get("window_end", OffsetDateTime.class),
        r.get("source_ref", String.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /** 指标列表：真分页 + 真筛选，并给出**全库最新的数据截至时间**。 */
  public AdRuleMetricPage metrics(
      UUID platformId, String shopCode, String metricScope, String externalCampaignId, int page, int size) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where =
        new StringBuilder(
            " where m.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid");
    if (platformId != null) {
      where.append(" and m.platform_id = ?");
      binds.add(platformId);
    }
    String shop = blankToNull(shopCode);
    if (shop != null) {
      where.append(" and m.shop_code = ?");
      binds.add(shop);
    }
    String scope = blankToNull(metricScope);
    if (scope != null) {
      where.append(" and m.metric_scope = ?");
      binds.add(scope);
    }
    String campaign = blankToNull(externalCampaignId);
    if (campaign != null) {
      where.append(" and m.external_campaign_id = ?");
      binds.add(campaign);
    }

    long total = count("select count(*) from ad_rule_metric m" + where, binds.toArray());

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    List<AdRuleMetricRow> items =
        sql.fetch(
                METRIC_COLUMNS + where + " order by m.metrics_as_of desc, m.id desc limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::metricRow)
            .toList();

    OffsetDateTime latest =
        one(
            OffsetDateTime.class,
            "select max(metrics_as_of) from ad_rule_metric m" + where,
            binds.toArray());

    return new AdRuleMetricPage(items, total, safePage, safeSize, latest);
  }

  public AdRuleMetricRow metric(String id) {
    UUID metricId = parseId(id);
    Record row =
        sql.fetchOne(
            METRIC_COLUMNS + TENANT_FILTER.replace("{alias}", "m") + " and m.id = ?", metricId);
    if (row == null) {
      throw notFound("广告表现指标");
    }
    return metricRow(row);
  }

  /**
   * 登记一条广告表现指标。
   *
   * <p>🔴 这是本模块的**显式写入口**：上游 14.3 报表未开发，广告表现数据只能从这里进来。
   * 登记时**必须给数据截至时间**（{@code metricsAsOf}）—— 没有它就分不清「没有数据」与「没同步到」。
   */
  public AdRuleMetricRow saveMetric(SaveMetricRequest request) {
    UUID tenant = currentTenant();
    String scope = text(request.metricScope());
    if (!Set.of("CAMPAIGN", "KEYWORD").contains(scope)) {
      throw badRequest("作用对象层级不合法：" + scope);
    }
    if (!Set.of("ENABLED", "PAUSED", "ENDED").contains(text(request.deliveryStatus()))) {
      throw badRequest("当前投放状态不合法：" + request.deliveryStatus());
    }
    String campaignId = text(request.externalCampaignId());
    String keywordId = text(request.externalKeywordId());
    if (campaignId.isEmpty()) {
      throw badRequest("外部活动 id 不能为空 —— 指标靠外部引用定位对象");
    }
    if (scope.equals("KEYWORD") && keywordId.isEmpty()) {
      throw badRequest("关键词级指标必须给出外部关键词 id");
    }
    if (scope.equals("CAMPAIGN") && !keywordId.isEmpty()) {
      throw badRequest("活动级指标不得带外部关键词 id —— 层级串了会让规则作用到错的对象");
    }
    if (request.windowEnd() == null || request.windowStart() == null) {
      throw badRequest("统计窗口的起止时间必填");
    }
    if (!request.windowEnd().isAfter(request.windowStart())) {
      throw badRequest("统计窗口结束时间必须晚于开始时间");
    }
    if (request.metricsAsOf() == null) {
      throw badRequest("数据截至时间必填 —— 没有它就分不清「没有数据」与「没同步到」");
    }
    if (one(String.class, "select code from platform where id = ?", request.platformId()) == null) {
      throw notFound("平台");
    }
    String shopCode = text(request.shopCode());
    UUID shopPlatform =
        one(
            UUID.class,
            "select platform_id from shop"
                + " where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and code = ?",
            shopCode);
    if (shopPlatform == null) {
      throw notFound("店铺");
    }
    if (!shopPlatform.equals(request.platformId())) {
      throw badRequest("指标的平台与店铺所属平台不一致");
    }

    UUID id = request.id() == null ? Ids.next() : request.id();
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        """
        insert into ad_rule_metric
          (tenant_id, id, platform_id, shop_code, metric_scope, external_campaign_id, external_keyword_id,
           currency, delivery_status, current_budget, current_bid,
           impressions, clicks, conversions, spend, attributed_revenue,
           metrics_as_of, window_start, window_end, source_ref, note, created_at, updated_at)
        values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz,?,?,?::timestamptz,?::timestamptz)
        on conflict (tenant_id, platform_id, shop_code, metric_scope, external_campaign_id, external_keyword_id, window_start)
        do update set currency = excluded.currency,
                    delivery_status = excluded.delivery_status,
                    current_budget = excluded.current_budget,
                    current_bid = excluded.current_bid,
                    impressions = excluded.impressions,
                    clicks = excluded.clicks,
                    conversions = excluded.conversions,
                    spend = excluded.spend,
                    attributed_revenue = excluded.attributed_revenue,
                    metrics_as_of = excluded.metrics_as_of,
                    window_end = excluded.window_end,
                    source_ref = excluded.source_ref,
                    note = excluded.note,
                    updated_at = excluded.updated_at
        """,
        tenant,
        id,
        request.platformId(),
        shopCode,
        scope,
        campaignId,
        keywordId,
        text(request.currency()),
        text(request.deliveryStatus()),
        request.currentBudget(),
        request.currentBid(),
        nz(request.impressions()),
        nz(request.clicks()),
        nz(request.conversions()),
        nz(request.spend()),
        request.attributedRevenue(),
        request.metricsAsOf(),
        request.windowStart(),
        request.windowEnd(),
        text(request.sourceRef()),
        text(request.note()),
        now,
        now);

    UUID stored =
        one(
            UUID.class,
            "select id from ad_rule_metric"
                + " where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and platform_id = ? and shop_code = ? and metric_scope = ?"
                + " and external_campaign_id = ? and external_keyword_id = ? and window_start = ?::timestamptz",
            request.platformId(),
            shopCode,
            scope,
            campaignId,
            keywordId,
            request.windowStart());
    return metric(stored.toString());
  }

  // ==================================================================
  // 评估运行
  // ==================================================================

  /** 一次评估里「规则 × 指标」的中间结果（还没落库）。 */
  private static final class HitDraft {
    final RuleSpec rule;
    final MetricSpec metric;
    final boolean matched;
    final String reason;
    String selectionNote = "";
    boolean selected;
    UUID hitId;

    HitDraft(RuleSpec rule, MetricSpec metric, boolean matched, String reason) {
      this.rule = rule;
      this.metric = metric;
      this.matched = matched;
      this.reason = reason;
    }
  }

  /** 规则的可执行快照（评估时读一次，避免每条条件都回表）。 */
  private static final class RuleSpec {
    final UUID id;
    final String code;
    final String name;
    final UUID platformId;
    final String platformCode;
    final String shopCode;
    final String targetType;
    final String externalCampaignId;
    final String externalKeywordId;
    final String actionType;
    final String adjustmentMode;
    final BigDecimal adjustmentValue;
    final BigDecimal singleCapAmount;
    final BigDecimal singleCapPercent;
    final BigDecimal totalCapAmount;
    final int totalCapCount;
    final int totalCapWindowHours;
    final BigDecimal minBudget;
    final BigDecimal maxBudget;
    final BigDecimal minBid;
    final BigDecimal maxBid;
    final int priority;
    final boolean dryRun;
    final int evaluationWindowHours;
    final OffsetDateTime createdAt;
    final List<ConditionSpec> conditions;

    RuleSpec(
        UUID id,
        String code,
        String name,
        UUID platformId,
        String platformCode,
        String shopCode,
        String targetType,
        String externalCampaignId,
        String externalKeywordId,
        String actionType,
        String adjustmentMode,
        BigDecimal adjustmentValue,
        BigDecimal singleCapAmount,
        BigDecimal singleCapPercent,
        BigDecimal totalCapAmount,
        int totalCapCount,
        int totalCapWindowHours,
        BigDecimal minBudget,
        BigDecimal maxBudget,
        BigDecimal minBid,
        BigDecimal maxBid,
        int priority,
        boolean dryRun,
        int evaluationWindowHours,
        OffsetDateTime createdAt,
        List<ConditionSpec> conditions) {
      this.id = id;
      this.code = code;
      this.name = name;
      this.platformId = platformId;
      this.platformCode = platformCode;
      this.shopCode = shopCode;
      this.targetType = targetType;
      this.externalCampaignId = externalCampaignId;
      this.externalKeywordId = externalKeywordId;
      this.actionType = actionType;
      this.adjustmentMode = adjustmentMode;
      this.adjustmentValue = adjustmentValue;
      this.singleCapAmount = singleCapAmount;
      this.singleCapPercent = singleCapPercent;
      this.totalCapAmount = totalCapAmount;
      this.totalCapCount = totalCapCount;
      this.totalCapWindowHours = totalCapWindowHours;
      this.minBudget = minBudget;
      this.maxBudget = maxBudget;
      this.minBid = minBid;
      this.maxBid = maxBid;
      this.priority = priority;
      this.dryRun = dryRun;
      this.evaluationWindowHours = evaluationWindowHours;
      this.createdAt = createdAt;
      this.conditions = conditions;
    }
  }

  private static final class ConditionSpec {
    final String metric;
    final String operator;
    final BigDecimal thresholdValue;
    final BigDecimal thresholdAmount;

    ConditionSpec(String metric, String operator, BigDecimal thresholdValue, BigDecimal thresholdAmount) {
      this.metric = metric;
      this.operator = operator;
      this.thresholdValue = thresholdValue;
      this.thresholdAmount = thresholdAmount;
    }

    BigDecimal threshold() {
      return thresholdValue != null ? thresholdValue : thresholdAmount;
    }
  }

  /** 指标的可判定快照。 */
  private static final class MetricSpec {
    final UUID id;
    final String platformCode;
    final String shopCode;
    final String metricScope;
    final String externalCampaignId;
    final String externalKeywordId;
    final String currency;
    final String deliveryStatus;
    final BigDecimal currentBudget;
    final BigDecimal currentBid;
    final BigDecimal impressions;
    final BigDecimal clicks;
    final BigDecimal conversions;
    final BigDecimal spend;
    final BigDecimal attributedRevenue;
    final BigDecimal acos;
    final BigDecimal roas;
    final OffsetDateTime metricsAsOf;

    MetricSpec(
        UUID id,
        String platformCode,
        String shopCode,
        String metricScope,
        String externalCampaignId,
        String externalKeywordId,
        String currency,
        String deliveryStatus,
        BigDecimal currentBudget,
        BigDecimal currentBid,
        BigDecimal impressions,
        BigDecimal clicks,
        BigDecimal conversions,
        BigDecimal spend,
        BigDecimal attributedRevenue,
        BigDecimal acos,
        BigDecimal roas,
        OffsetDateTime metricsAsOf) {
      this.id = id;
      this.platformCode = platformCode;
      this.shopCode = shopCode;
      this.metricScope = metricScope;
      this.externalCampaignId = externalCampaignId;
      this.externalKeywordId = externalKeywordId;
      this.currency = currency;
      this.deliveryStatus = deliveryStatus;
      this.currentBudget = currentBudget;
      this.currentBid = currentBid;
      this.impressions = impressions;
      this.clicks = clicks;
      this.conversions = conversions;
      this.spend = spend;
      this.attributedRevenue = attributedRevenue;
      this.acos = acos;
      this.roas = roas;
      this.metricsAsOf = metricsAsOf;
    }

    /** 🔴 指标口径的取值；null = **不可比**（不是 0）。 */
    BigDecimal valueOf(String metric) {
      return switch (metric) {
        case "ACOS" -> acos;
        case "ROAS" -> roas;
        case "SPEND" -> spend;
        case "REVENUE" -> attributedRevenue;
        case "CONVERSIONS" -> conversions;
        case "CLICKS" -> clicks;
        case "IMPRESSIONS" -> impressions;
        default -> null;
      };
    }

    /** 目标键：同一目标上的多条命中才需要比优先级。 */
    String targetKey() {
      return platformCode + "|" + shopCode + "|" + metricScope + "|" + externalCampaignId + "|" + externalKeywordId;
    }
  }

  /**
   * 跑一次评估。
   *
   * <p>🔴 试运行的判定是「取更严的那个」：规则自身 dry_run 为真 **或** 本次运行口径是 DRY_RUN ⇒ 这次就是试运行。
   * 也就是说，一次「APPLY」的运行**不能**把一条开着试运行的规则推成真实执行。
   */
  public AdRuleRunDetail run(RunRequest request) {
    UUID tenant = currentTenant();
    String runMode = text(request.runMode());
    if (!Set.of("DRY_RUN", "APPLY").contains(runMode)) {
      throw badRequest("运行口径不合法：" + runMode);
    }
    OffsetDateTime now = OffsetDateTime.now();
    UUID runId = Ids.next();
    sql.execute(
        """
        insert into ad_rule_run (tenant_id, id, run_mode, trigger_source, status, started_at, note)
        values (?,?,?,'MANUAL','RUNNING',?::timestamptz,?)
        """,
        tenant,
        runId,
        runMode,
        now,
        text(request.note()));

    // ---- 取规则 ----
    List<Object> ruleBinds = new ArrayList<>();
    StringBuilder ruleWhere =
        new StringBuilder(
            " where r.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and r.status = 'ACTIVE'");
    if (request.ruleId() != null) {
      ruleWhere.append(" and r.id = ?");
      ruleBinds.add(request.ruleId());
    }
    List<RuleSpec> rules = loadRules(ruleWhere.toString(), ruleBinds);

    // ---- 逐规则 × 逐指标求值 ----
    List<HitDraft> drafts = new ArrayList<>();
    for (RuleSpec rule : rules) {
      for (MetricSpec metric : candidateMetrics(rule)) {
        drafts.add(evaluate(rule, metric));
      }
    }

    // ---- 🔴 优先级：同一目标上只取一条 ----
    Map<String, List<HitDraft>> byTarget = new LinkedHashMap<>();
    for (HitDraft d : drafts) {
      if (!d.matched) continue;
      byTarget.computeIfAbsent(d.metric.targetKey(), k -> new ArrayList<>()).add(d);
    }
    for (Map.Entry<String, List<HitDraft>> entry : byTarget.entrySet()) {
      List<HitDraft> group = entry.getValue();
      HitDraft winner =
          group.stream()
              .min(
                  Comparator.comparingInt((HitDraft d) -> d.rule.priority)
                      .thenComparing(d -> d.rule.createdAt)
                      .thenComparing(d -> d.rule.id))
              .orElse(null);
      if (winner == null) continue;
      for (HitDraft d : group) {
        if (d == winner) {
          d.selected = true;
          d.selectionNote =
              "同目标上有 "
                  + group.size()
                  + " 条规则命中，本条优先级 "
                  + d.rule.priority
                  + "（数字小 = 优先高）最高，被选中执行";
        } else {
          d.selected = false;
          d.selectionNote =
              "同目标上存在优先级更高的规则（"
                  + winner.rule.code
                  + "，优先级 "
                  + winner.rule.priority
                  + " < 本条 "
                  + d.rule.priority
                  + "），本条落选、不执行";
        }
      }
    }

    // ---- 落库命中记录 ----
    int hitCount = 0;
    int selectedCount = 0;
    for (HitDraft d : drafts) {
      if (d.matched) hitCount++;
      if (d.selected) selectedCount++;
      UUID hitId = Ids.next();
      d.hitId = hitId;
      sql.execute(
          """
          insert into ad_rule_hit
            (tenant_id, id, run_id, rule_id, metric_id, matched, selected, selection_note, hit_reason,
             priority, spend_snapshot, revenue_snapshot, conversions_snapshot, clicks_snapshot,
             impressions_snapshot, acos_snapshot, roas_snapshot, metrics_as_of, evaluated_at)
          values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)
          """,
          tenant,
          hitId,
          runId,
          d.rule.id,
          d.metric.id,
          d.matched,
          d.selected,
          d.selectionNote,
          d.reason,
          d.rule.priority,
          nz(d.metric.spend),
          d.metric.attributedRevenue,
          nz(d.metric.conversions),
          nz(d.metric.clicks),
          nz(d.metric.impressions),
          d.metric.acos,
          d.metric.roas,
          d.metric.metricsAsOf,
          now);
    }

    // ---- 对被选中的命中执行调整 ----
    int executionCount = 0;
    int appliedCount = 0;
    int dryRunCount = 0;
    int rejectedCount = 0;
    for (HitDraft d : drafts) {
      if (!d.selected) continue;
      Outcome outcome = execute(runId, d, runMode, now);
      executionCount++;
      switch (outcome.outcome()) {
        case "APPLIED" -> appliedCount++;
        case "DRY_RUN" -> dryRunCount++;
        default -> rejectedCount++;
      }
    }

    sql.execute(
        "update ad_rule_run set status = 'COMPLETED', evaluated_rules = ?, hit_count = ?, selected_count = ?,"
            + " execution_count = ?, applied_count = ?, dry_run_count = ?, rejected_count = ?, finished_at = ?::timestamptz"
            + " where tenant_id = ? and id = ?",
        rules.size(),
        hitCount,
        selectedCount,
        executionCount,
        appliedCount,
        dryRunCount,
        rejectedCount,
        OffsetDateTime.now(),
        tenant,
        runId);

    return runDetail(runId.toString());
  }

  private List<RuleSpec> loadRules(String where, List<Object> binds) {
    List<RuleSpec> rules = new ArrayList<>();
    for (Record r :
        sql.fetch(
            """
            select r.id, r.rule_code, r.rule_name, r.platform_id, p.code as platform_code,
                   r.shop_code, r.target_type, r.external_campaign_id, r.external_keyword_id,
                   r.action_type, r.adjustment_mode, r.adjustment_value,
                   r.single_adjust_cap_amount, r.single_adjust_cap_percent,
                   r.total_cap_amount, r.total_cap_count, r.total_cap_window_hours,
                   r.min_budget, r.max_budget, r.min_bid, r.max_bid,
                   r.priority, r.dry_run, r.evaluation_window_hours, r.created_at
              from ad_rule r join platform p on p.id = r.platform_id
            """
                + where
                + " order by r.priority asc, r.created_at asc, r.id asc",
            binds.toArray())) {
      UUID ruleId = r.get("id", UUID.class);
      List<ConditionSpec> conditions = new ArrayList<>();
      for (Record c :
          sql.fetch(
              """
              select c.metric, c.operator, c.threshold_value, c.threshold_amount
                from ad_rule_condition c
               where c.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
                 and c.rule_id = ?
               order by c.position asc, c.id asc
              """,
              ruleId)) {
        conditions.add(
            new ConditionSpec(
                c.get("metric", String.class),
                c.get("operator", String.class),
                c.get("threshold_value", BigDecimal.class),
                c.get("threshold_amount", BigDecimal.class)));
      }
      rules.add(
          new RuleSpec(
              ruleId,
              r.get("rule_code", String.class),
              r.get("rule_name", String.class),
              r.get("platform_id", UUID.class),
              r.get("platform_code", String.class),
              r.get("shop_code", String.class),
              r.get("target_type", String.class),
              r.get("external_campaign_id", String.class),
              r.get("external_keyword_id", String.class),
              r.get("action_type", String.class),
              r.get("adjustment_mode", String.class),
              r.get("adjustment_value", BigDecimal.class),
              r.get("single_adjust_cap_amount", BigDecimal.class),
              r.get("single_adjust_cap_percent", BigDecimal.class),
              r.get("total_cap_amount", BigDecimal.class),
              r.get("total_cap_count", Integer.class),
              r.get("total_cap_window_hours", Integer.class),
              r.get("min_budget", BigDecimal.class),
              r.get("max_budget", BigDecimal.class),
              r.get("min_bid", BigDecimal.class),
              r.get("max_bid", BigDecimal.class),
              r.get("priority", Integer.class),
              r.get("dry_run", Boolean.class),
              r.get("evaluation_window_hours", Integer.class),
              r.get("created_at", OffsetDateTime.class),
              conditions));
    }
    return rules;
  }

  /** 该规则作用范围内的候选指标：**每个目标取最新的一行**。 */
  private List<MetricSpec> candidateMetrics(RuleSpec rule) {
    StringBuilder where =
        new StringBuilder(
            " where m.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and m.platform_id = ? and m.shop_code = ? and m.metric_scope = ?");
    List<Object> binds = new ArrayList<>();
    binds.add(rule.platformId);
    binds.add(rule.shopCode);
    binds.add(rule.targetType);
    if (!rule.externalCampaignId.isEmpty()) {
      where.append(" and m.external_campaign_id = ?");
      binds.add(rule.externalCampaignId);
    }
    if (!rule.externalKeywordId.isEmpty()) {
      where.append(" and m.external_keyword_id = ?");
      binds.add(rule.externalKeywordId);
    }

    List<MetricSpec> metrics = new ArrayList<>();
    for (Record r :
        sql.fetch(
            """
            select distinct on (m.external_campaign_id, m.external_keyword_id)
                   m.id, p.code as platform_code, m.shop_code, m.metric_scope,
                   m.external_campaign_id, m.external_keyword_id, m.currency,
                   m.delivery_status, m.current_budget, m.current_bid,
                   m.impressions, m.clicks, m.conversions, m.spend, m.attributed_revenue,
                   m.acos, m.roas, m.metrics_as_of
              from ad_rule_metric m join platform p on p.id = m.platform_id
            """
                + where
                + " order by m.external_campaign_id, m.external_keyword_id, m.metrics_as_of desc, m.id desc",
            binds.toArray())) {
      metrics.add(
          new MetricSpec(
              r.get("id", UUID.class),
              r.get("platform_code", String.class),
              r.get("shop_code", String.class),
              r.get("metric_scope", String.class),
              r.get("external_campaign_id", String.class),
              r.get("external_keyword_id", String.class),
              r.get("currency", String.class),
              r.get("delivery_status", String.class),
              r.get("current_budget", BigDecimal.class),
              r.get("current_bid", BigDecimal.class),
              r.get("impressions", BigDecimal.class),
              r.get("clicks", BigDecimal.class),
              r.get("conversions", BigDecimal.class),
              r.get("spend", BigDecimal.class),
              r.get("attributed_revenue", BigDecimal.class),
              r.get("acos", BigDecimal.class),
              r.get("roas", BigDecimal.class),
              r.get("metrics_as_of", OffsetDateTime.class)));
    }
    return metrics;
  }

  /** 求值：全部条件成立才算命中（AND 语义）。 */
  private HitDraft evaluate(RuleSpec rule, MetricSpec metric) {
    if (rule.conditions.isEmpty()) {
      return new HitDraft(rule, metric, false, "规则没有条件，永远不会命中");
    }
    List<String> passed = new ArrayList<>();
    for (ConditionSpec c : rule.conditions) {
      BigDecimal value = metric.valueOf(c.metric);
      if (value == null) {
        return new HitDraft(
            rule,
            metric,
            false,
            "条件不可比："
                + c.metric
                + " 算不出来（分母为 0 或该指标未登记）—— 不可比与「真的是 0」是两件事，不能当成不满足");
      }
      BigDecimal threshold = c.threshold();
      boolean ok = compare(value, c.operator, threshold);
      if (!ok) {
        return new HitDraft(
            rule,
            metric,
            false,
            "条件不成立："
                + c.metric
                + " "
                + value.stripTrailingZeros().toPlainString()
                + " 未满足 "
                + c.operator
                + " "
                + threshold.stripTrailingZeros().toPlainString());
      }
      passed.add(
          c.metric
              + " "
              + value.stripTrailingZeros().toPlainString()
              + " "
              + c.operator
              + " "
              + threshold.stripTrailingZeros().toPlainString());
    }
    return new HitDraft(rule, metric, true, "全部条件成立：" + String.join("；", passed));
  }

  private static boolean compare(BigDecimal value, String operator, BigDecimal threshold) {
    int c = value.compareTo(threshold);
    return switch (operator) {
      case "GT" -> c > 0;
      case "GTE" -> c >= 0;
      case "LT" -> c < 0;
      case "LTE" -> c <= 0;
      case "EQ" -> c == 0;
      default -> false;
    };
  }

  /** 一次调整尝试的结论。 */
  private record Outcome(
      UUID executionId,
      String outcome,
      String rejectReason,
      String rejectDetail,
      BigDecimal before,
      BigDecimal after,
      BigDecimal delta,
      String beforeStatus,
      String afterStatus,
      String capCheck,
      String boundCheck) {}

  /**
   * 执行一次调整：**先过三道闸，再决定要不要真的调**。
   *
   * <p>🔴 顺序与语义：
   *
   * <ol>
   *   <li>单次上限（金额上限与比例上限取更严的）⇒ 超限**拒绝**，不截断；
   *   <li>总上限（窗口内累计金额 / 次数）⇒ 超限**拒绝**；
   *   <li>上下限保护 ⇒ 越界**拒绝**；
   *   <li>试运行 ⇒ 算完就停，**不产生实际调整、不产生待下发动作**；
   *   <li>全部通过 ⇒ 产生待下发动作（平台侧真实下发本模块不实现）。
   * </ol>
   *
   * <p>🔴 试运行**也要过闸**：闸门拦下来的调整，在试运行里同样显示为 REJECTED ——
   * 这样「把试运行关掉会怎样」在关之前就能看清，而不是关掉之后才发现每次都被上限拦。
   */
  private Outcome execute(UUID runId, HitDraft d, String runMode, OffsetDateTime now) {
    RuleSpec rule = d.rule;
    MetricSpec metric = d.metric;
    boolean dryRun = rule.dryRun || runMode.equals("DRY_RUN");

    // ---- 启停动作：没有数值幅度 ----
    if (rule.actionType.equals("PAUSE") || rule.actionType.equals("RESUME")) {
      String beforeStatus = metric.deliveryStatus;
      String afterStatus = rule.actionType.equals("PAUSE") ? "PAUSED" : "ENABLED";
      if (beforeStatus.equals(afterStatus)) {
        return record(
            runId,
            d,
            "REJECTED",
            "NOT_APPLICABLE",
            "对象当前状态已经是 " + beforeStatus + "，无需再" + (rule.actionType.equals("PAUSE") ? "暂停" : "恢复"),
            null,
            null,
            null,
            beforeStatus,
            afterStatus,
            "PASSED",
            "NOT_APPLICABLE",
            now);
      }
      Outcome duplicated = duplicateGuard(runId, d, now, null, null, beforeStatus, afterStatus);
      if (duplicated != null) {
        return duplicated;
      }
      Outcome outcome =
          record(
              runId,
              d,
              dryRun ? "DRY_RUN" : "APPLIED",
              "",
              dryRun
                  ? "试运行：本来要把状态从 " + beforeStatus + " 改成 " + afterStatus
                  : "已生成待下发动作：状态从 " + beforeStatus + " 改成 " + afterStatus
                      + "（平台侧真实下发通道未交付，故状态是「待下发」而不是「已生效」）",
              null,
              null,
              null,
              beforeStatus,
              afterStatus,
              "PASSED",
              "NOT_APPLICABLE",
              now);
      if (!dryRun) {
        enqueueDispatch(d, outcome.executionId(), null, null, beforeStatus, afterStatus);
      }
      return outcome;
    }

    boolean bid = BID_ACTIONS.contains(rule.actionType);
    BigDecimal current = bid ? metric.currentBid : metric.currentBudget;
    if (current == null) {
      return record(
          runId,
          d,
          "REJECTED",
          "NOT_APPLICABLE",
          (bid ? "当前出价" : "当前预算") + "没有登记，无法计算调整前后值",
          null,
          null,
          null,
          null,
          null,
          "PASSED",
          "NOT_APPLICABLE",
          now);
    }

    // ---- 目标值 ----
    BigDecimal delta;
    if (rule.adjustmentMode.equals("PERCENT")) {
      delta =
          current
              .multiply(rule.adjustmentValue)
              .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)
              .setScale(4, RoundingMode.HALF_UP);
    } else {
      delta = rule.adjustmentValue.setScale(4, RoundingMode.HALF_UP);
    }
    boolean increase = rule.actionType.equals("INCREASE_BID") || rule.actionType.equals("INCREASE_BUDGET");
    BigDecimal after = money(increase ? current.add(delta) : current.subtract(delta));
    BigDecimal absDelta = after.subtract(current).abs();

    // ---- 🔴 闸门①：单次上限（金额上限与比例上限取更严的）----
    BigDecimal percentCap = null;
    if (rule.singleCapPercent != null && rule.singleCapPercent.signum() > 0) {
      percentCap =
          current
              .multiply(rule.singleCapPercent)
              .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)
              .setScale(4, RoundingMode.HALF_UP);
    }
    BigDecimal effectiveCap = rule.singleCapAmount;
    String capNote = "单次金额上限 " + rule.singleCapAmount.toPlainString();
    if (percentCap != null) {
      capNote =
          capNote
              + "、单次比例上限 "
              + rule.singleCapPercent.toPlainString()
              + "%（相当于 "
              + percentCap.toPlainString()
              + "）";
      if (percentCap.compareTo(effectiveCap) < 0) {
        effectiveCap = percentCap;
      }
    }
    if (absDelta.compareTo(effectiveCap) > 0) {
      return record(
          runId,
          d,
          "REJECTED",
          "SINGLE_CAP_EXCEEDED",
          "本次调整幅度 "
              + absDelta.toPlainString()
              + " 超过单次上限 "
              + effectiveCap.toPlainString()
              + "（"
              + capNote
              + "）—— 超限的调整被拒绝，不是被静默截断",
          current,
          after,
          null,
          null,
          null,
          "SINGLE_CAP_EXCEEDED",
          "PASSED",
          now);
    }

    // ---- 🔴 闸门②：总上限（窗口内累计金额 / 次数）----
    OffsetDateTime windowStart = now.minusHours(rule.totalCapWindowHours);
    // 🔴 累计的是**绝对幅度**：降价是负数，直接 sum 会互相抵消，
    //    「降了 0.2 又加了 0.2」会被算成「累计 0」⇒ 总上限形同虚设。
    BigDecimal usedAmount =
        nz(
            one(
                BigDecimal.class,
                "select coalesce(sum(abs(applied_delta)),0) from ad_rule_execution"
                    + " where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                    + " and rule_id = ? and outcome = 'APPLIED' and executed_at >= ?::timestamptz",
                rule.id,
                windowStart));
    Long usedCountBox =
        one(
            Long.class,
            "select count(*) from ad_rule_execution"
                + " where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and rule_id = ? and outcome = 'APPLIED' and executed_at >= ?::timestamptz",
            rule.id,
            windowStart);
    long used = usedCountBox == null ? 0L : usedCountBox;

    if (usedAmount.add(absDelta).compareTo(rule.totalCapAmount) > 0) {
      return record(
          runId,
          d,
          "REJECTED",
          "TOTAL_CAP_EXCEEDED",
          "窗口内（"
              + rule.totalCapWindowHours
              + " 小时）累计调整金额 "
              + usedAmount.toPlainString()
              + " + 本次 "
              + absDelta.toPlainString()
              + " 超过总上限 "
              + rule.totalCapAmount.toPlainString()
              + " —— 超限的调整被拒绝，不是被静默截断",
          current,
          after,
          null,
          null,
          null,
          "TOTAL_CAP_EXCEEDED",
          "PASSED",
          now,
          usedAmount,
          used);
    }
    if (used + 1 > rule.totalCapCount) {
      return record(
          runId,
          d,
          "REJECTED",
          "TOTAL_CAP_EXCEEDED",
          "窗口内（"
              + rule.totalCapWindowHours
              + " 小时）已执行 "
              + used
              + " 次，本次将是第 "
              + (used + 1)
              + " 次，超过总次数上限 "
              + rule.totalCapCount
              + " —— 超限的调整被拒绝，不是被静默截断",
          current,
          after,
          null,
          null,
          null,
          "TOTAL_CAP_EXCEEDED",
          "PASSED",
          now,
          usedAmount,
          used);
    }

    // ---- 🔴 闸门③：上下限保护 ----
    BigDecimal min = bid ? rule.minBid : rule.minBudget;
    BigDecimal max = bid ? rule.maxBid : rule.maxBudget;
    if (after.signum() < 0) {
      return record(
          runId,
          d,
          "REJECTED",
          "BOUND_VIOLATION",
          "调整后的" + (bid ? "出价" : "预算") + "为负（" + after.toPlainString() + "），越过下限保护",
          current,
          after,
          null,
          null,
          null,
          "PASSED",
          "BOUND_VIOLATION",
          now,
          usedAmount,
          used);
    }
    if (min != null && after.compareTo(min) < 0) {
      return record(
          runId,
          d,
          "REJECTED",
          "BOUND_VIOLATION",
          "调整后的"
              + (bid ? "出价" : "预算")
              + " "
              + after.toPlainString()
              + " 低于下限保护 "
              + min.toPlainString(),
          current,
          after,
          null,
          null,
          null,
          "PASSED",
          "BOUND_VIOLATION",
          now,
          usedAmount,
          used);
    }
    if (max != null && after.compareTo(max) > 0) {
      return record(
          runId,
          d,
          "REJECTED",
          "BOUND_VIOLATION",
          "调整后的"
              + (bid ? "出价" : "预算")
              + " "
              + after.toPlainString()
              + " 高于上限保护 "
              + max.toPlainString(),
          current,
          after,
          null,
          null,
          null,
          "PASSED",
          "BOUND_VIOLATION",
          now,
          usedAmount,
          used);
    }

    // ---- 重复下发保护：同一目标上已有待下发的同类动作，就不再排一条 ----
    Outcome duplicated = duplicateGuard(runId, d, now, current, after, null, null);
    if (duplicated != null) {
      return duplicated;
    }

    // ---- 试运行：算完就停 ----
    if (dryRun) {
      return record(
          runId,
          d,
          "DRY_RUN",
          "",
          "试运行：本来要把"
              + (bid ? "出价" : "预算")
              + " 从 "
              + current.toPlainString()
              + " 改成 "
              + after.toPlainString()
              + "，但本次不产生实际调整",
          current,
          after,
          after.subtract(current),
          null,
          null,
          "PASSED",
          "PASSED",
          now,
          usedAmount,
          used);
    }

    // ---- 真实执行：产生待下发动作 ----
    Outcome applied =
        record(
            runId,
            d,
            "APPLIED",
            "",
            "已生成待下发动作："
                + (bid ? "出价" : "预算")
                + " 从 "
                + current.toPlainString()
                + " 改成 "
                + after.toPlainString()
                + "（平台侧真实下发通道未交付，故状态是「待下发」而不是「已生效」）",
            current,
            after,
            after.subtract(current),
            null,
            null,
            "PASSED",
            "PASSED",
            now,
            usedAmount,
            used);
    enqueueDispatch(d, applied.executionId(), current, after, null, null);
    return applied;
  }

  /** 同一目标上已有待下发的同类动作 ⇒ 拒绝再排一条（否则每次运行都会重复排）。 */
  private Outcome duplicateGuard(
      UUID runId,
      HitDraft d,
      OffsetDateTime now,
      BigDecimal before,
      BigDecimal after,
      String beforeStatus,
      String afterStatus) {
    Long pending =
        one(
            Long.class,
            "select count(*) from ad_rule_dispatch"
                + " where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and dispatch_status = 'PENDING'"
                + " and platform_id = ? and shop_code = ?"
                + " and external_campaign_id = ? and external_keyword_id = ? and action_type = ?",
            d.rule.platformId,
            d.rule.shopCode,
            d.metric.externalCampaignId,
            d.metric.externalKeywordId,
            d.rule.actionType);
    if (pending == null || pending == 0L) {
      return null;
    }
    return record(
        runId,
        d,
        "REJECTED",
        "DUPLICATE_PENDING_DISPATCH",
        "该目标上已有 "
            + pending
            + " 条同类型的待下发动作 —— 上一条还没下发就再排一条，等于把同一个调整排两遍",
        before,
        after,
        null,
        beforeStatus,
        afterStatus,
        "PASSED",
        "PASSED",
        now);
  }

  /** 落一条执行记录（被拒绝的、试运行的、真实执行的都落）。 */
  private Outcome record(
      UUID runId,
      HitDraft d,
      String outcome,
      String rejectReason,
      String rejectDetail,
      BigDecimal before,
      BigDecimal after,
      BigDecimal delta,
      String beforeStatus,
      String afterStatus,
      String capCheck,
      String boundCheck,
      OffsetDateTime now) {
    return record(
        runId, d, outcome, rejectReason, rejectDetail, before, after, delta, beforeStatus,
        afterStatus, capCheck, boundCheck, now, BigDecimal.ZERO, 0L);
  }

  private Outcome record(
      UUID runId,
      HitDraft d,
      String outcome,
      String rejectReason,
      String rejectDetail,
      BigDecimal before,
      BigDecimal after,
      BigDecimal delta,
      String beforeStatus,
      String afterStatus,
      String capCheck,
      String boundCheck,
      OffsetDateTime now,
      BigDecimal windowUsedAmount,
      long windowUsedCount) {
    UUID executionId = Ids.next();
    RuleSpec rule = d.rule;
    sql.execute(
        """
        insert into ad_rule_execution
          (tenant_id, id, run_id, rule_id, hit_id, metric_id, action_type, adjustment_mode,
           requested_value, outcome, reject_reason, reject_detail,
           before_value, after_value, applied_delta, before_status, after_status,
           cap_check, bound_check, single_cap_amount, single_cap_percent,
           total_cap_amount, total_cap_count, total_cap_window_hours,
           window_used_amount, window_used_count, priority_selection_note, executed_at)
        values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)
        """,
        currentTenant(),
        executionId,
        runId,
        rule.id,
        d.hitId,
        d.metric.id,
        rule.actionType,
        rule.adjustmentMode,
        nz(rule.adjustmentValue),
        outcome,
        rejectReason,
        rejectDetail,
        before,
        after,
        delta,
        beforeStatus,
        afterStatus,
        capCheck,
        boundCheck,
        rule.singleCapAmount,
        nz(rule.singleCapPercent),
        rule.totalCapAmount,
        rule.totalCapCount,
        rule.totalCapWindowHours,
        windowUsedAmount,
        (int) windowUsedCount,
        d.selectionNote,
        now);
    return new Outcome(
        executionId,
        outcome,
        rejectReason,
        rejectDetail,
        before,
        after,
        delta,
        beforeStatus,
        afterStatus,
        capCheck,
        boundCheck);
  }

  /** 产生待下发动作（**下游 = 平台广告 API，本模块不实现真实下发**）。 */
  private void enqueueDispatch(
      HitDraft d,
      UUID executionId,
      BigDecimal before,
      BigDecimal after,
      String beforeStatus,
      String afterStatus) {
    RuleSpec rule = d.rule;
    ObjectNode payload = json.createObjectNode();
    payload.put("actionType", rule.actionType);
    payload.put("externalCampaignId", d.metric.externalCampaignId);
    payload.put("externalKeywordId", d.metric.externalKeywordId);
    payload.put("currency", d.metric.currency == null ? "" : d.metric.currency);
    if (beforeStatus != null) {
      payload.put("statusBefore", beforeStatus);
      payload.put("statusAfter", afterStatus);
    } else {
      boolean bid = BID_ACTIONS.contains(rule.actionType);
      payload.put(bid ? "bidBefore" : "budgetBefore", before == null ? "" : before.toPlainString());
      payload.put(bid ? "bidAfter" : "budgetAfter", after == null ? "" : after.toPlainString());
      payload.put("delta", after == null || before == null ? "" : after.subtract(before).toPlainString());
    }
    try {
      sql.execute(
          """
          insert into ad_rule_dispatch
            (tenant_id, id, execution_id, platform_id, shop_code, external_campaign_id, external_keyword_id,
             action_type, action_payload, dispatch_status, note)
          values (?,?,?,?,?,?,?,?,?::jsonb,'PENDING',?)
          """,
          currentTenant(),
          Ids.next(),
          executionId,
          rule.platformId,
          rule.shopCode,
          d.metric.externalCampaignId,
          d.metric.externalKeywordId,
          rule.actionType,
          json.writeValueAsString(payload),
          "🔴 待下发：平台侧真实下发通道未交付，本模块只把要下发什么落库");
    } catch (Exception e) {
      throw new IllegalStateException("待下发动作落库失败", e);
    }
  }

  // ==================================================================
  // 命中 / 执行 / 待下发 / 回溯 的读
  // ==================================================================

  private static final String HIT_COLUMNS =
      """
       select h.id, h.run_id, h.rule_id, r.rule_name, h.metric_id,
              p.code as platform_code, m.shop_code, m.external_campaign_id, m.external_keyword_id,
              h.matched, h.selected, h.selection_note, h.hit_reason, h.priority,
              h.spend_snapshot, h.revenue_snapshot, h.conversions_snapshot, h.clicks_snapshot,
              h.impressions_snapshot, h.acos_snapshot, h.roas_snapshot,
              h.metrics_as_of, h.evaluated_at
         from ad_rule_hit h
         join ad_rule r on r.tenant_id = h.tenant_id and r.id = h.rule_id
         join ad_rule_metric m on m.tenant_id = h.tenant_id and m.id = h.metric_id
         join platform p on p.id = m.platform_id
      """;

  private AdRuleHitRow hitRow(Record r) {
    return new AdRuleHitRow(
        r.get("id", UUID.class),
        r.get("run_id", UUID.class),
        r.get("rule_id", UUID.class),
        r.get("rule_name", String.class),
        r.get("metric_id", UUID.class),
        r.get("platform_code", String.class),
        r.get("shop_code", String.class),
        r.get("external_campaign_id", String.class),
        r.get("external_keyword_id", String.class),
        r.get("matched", Boolean.class),
        r.get("selected", Boolean.class),
        r.get("selection_note", String.class),
        r.get("hit_reason", String.class),
        r.get("priority", Integer.class),
        r.get("spend_snapshot", BigDecimal.class),
        r.get("revenue_snapshot", BigDecimal.class),
        r.get("conversions_snapshot", BigDecimal.class),
        r.get("clicks_snapshot", BigDecimal.class),
        r.get("impressions_snapshot", BigDecimal.class),
        r.get("acos_snapshot", BigDecimal.class),
        r.get("roas_snapshot", BigDecimal.class),
        r.get("metrics_as_of", OffsetDateTime.class),
        r.get("evaluated_at", OffsetDateTime.class));
  }

  /** 命中记录：真分页 + 真筛选。 */
  public AdRuleHitPage hits(
      UUID ruleId, UUID runId, Boolean matched, Boolean selected, int page, int size) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where =
        new StringBuilder(
            " where h.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid");
    if (ruleId != null) {
      where.append(" and h.rule_id = ?");
      binds.add(ruleId);
    }
    if (runId != null) {
      where.append(" and h.run_id = ?");
      binds.add(runId);
    }
    if (matched != null) {
      where.append(" and h.matched = ?");
      binds.add(matched);
    }
    if (selected != null) {
      where.append(" and h.selected = ?");
      binds.add(selected);
    }

    long total = count("select count(*) from ad_rule_hit h" + where, binds.toArray());
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    List<AdRuleHitRow> items =
        sql.fetch(
                HIT_COLUMNS + where + " order by h.evaluated_at desc, h.id desc limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::hitRow)
            .toList();

    long matchedCount =
        count("select count(*) from ad_rule_hit h" + where + " and h.matched = true", binds.toArray());
    long selectedCount =
        count("select count(*) from ad_rule_hit h" + where + " and h.selected = true", binds.toArray());

    return new AdRuleHitPage(items, total, safePage, safeSize, matchedCount, selectedCount);
  }

  private static final String EXECUTION_COLUMNS =
      """
       select e.id, e.run_id, e.rule_id, r.rule_name, e.hit_id, e.metric_id,
              e.action_type, e.adjustment_mode, e.requested_value,
              e.outcome, e.reject_reason, e.reject_detail,
              e.before_value, e.after_value, e.applied_delta, e.before_status, e.after_status,
              e.cap_check, e.bound_check,
              e.single_cap_amount, e.single_cap_percent, e.total_cap_amount, e.total_cap_count,
              e.total_cap_window_hours, e.window_used_amount, e.window_used_count,
              e.priority_selection_note, e.executed_at
         from ad_rule_execution e join ad_rule r on r.tenant_id = e.tenant_id and r.id = e.rule_id
      """;

  private AdRuleExecutionRow executionRow(Record r) {
    return new AdRuleExecutionRow(
        r.get("id", UUID.class),
        r.get("run_id", UUID.class),
        r.get("rule_id", UUID.class),
        r.get("rule_name", String.class),
        r.get("hit_id", UUID.class),
        r.get("metric_id", UUID.class),
        r.get("action_type", String.class),
        r.get("adjustment_mode", String.class),
        r.get("requested_value", BigDecimal.class),
        r.get("outcome", String.class),
        r.get("reject_reason", String.class),
        r.get("reject_detail", String.class),
        r.get("before_value", BigDecimal.class),
        r.get("after_value", BigDecimal.class),
        r.get("applied_delta", BigDecimal.class),
        r.get("before_status", String.class),
        r.get("after_status", String.class),
        r.get("cap_check", String.class),
        r.get("bound_check", String.class),
        r.get("single_cap_amount", BigDecimal.class),
        r.get("single_cap_percent", BigDecimal.class),
        r.get("total_cap_amount", BigDecimal.class),
        r.get("total_cap_count", Integer.class),
        r.get("total_cap_window_hours", Integer.class),
        r.get("window_used_amount", BigDecimal.class),
        r.get("window_used_count", Integer.class),
        r.get("priority_selection_note", String.class),
        r.get("executed_at", OffsetDateTime.class));
  }

  /** 执行记录：真分页 + 真筛选。 */
  public AdRuleExecutionPage executions(
      UUID ruleId, UUID runId, String outcome, int page, int size) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where =
        new StringBuilder(
            " where e.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid");
    if (ruleId != null) {
      where.append(" and e.rule_id = ?");
      binds.add(ruleId);
    }
    if (runId != null) {
      where.append(" and e.run_id = ?");
      binds.add(runId);
    }
    String state = blankToNull(outcome);
    if (state != null) {
      where.append(" and e.outcome = ?");
      binds.add(state);
    }

    long total = count("select count(*) from ad_rule_execution e" + where, binds.toArray());
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    List<AdRuleExecutionRow> items =
        sql.fetch(
                EXECUTION_COLUMNS + where + " order by e.executed_at desc, e.id desc limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::executionRow)
            .toList();

    long appliedCount =
        count("select count(*) from ad_rule_execution e" + where + " and e.outcome = 'APPLIED'", binds.toArray());
    long dryRunCount =
        count("select count(*) from ad_rule_execution e" + where + " and e.outcome = 'DRY_RUN'", binds.toArray());
    long rejectedCount =
        count("select count(*) from ad_rule_execution e" + where + " and e.outcome = 'REJECTED'", binds.toArray());

    return new AdRuleExecutionPage(items, total, safePage, safeSize, appliedCount, dryRunCount, rejectedCount);
  }

  public AdRuleExecutionRow execution(String id) {
    UUID executionId = parseId(id);
    Record row =
        sql.fetchOne(
            EXECUTION_COLUMNS + TENANT_FILTER.replace("{alias}", "e") + " and e.id = ?", executionId);
    if (row == null) {
      throw notFound("调整执行记录");
    }
    return executionRow(row);
  }

  private static final String DISPATCH_COLUMNS =
      """
       select d.id, d.execution_id, e.rule_id, r.rule_name, d.platform_id, p.code as platform_code,
              d.shop_code, d.external_campaign_id, d.external_keyword_id, d.action_type,
              d.action_payload, d.dispatch_status, d.dispatched_at, d.dispatch_ref, d.note,
              d.created_at, d.updated_at
         from ad_rule_dispatch d
         join ad_rule_execution e on e.tenant_id = d.tenant_id and e.id = d.execution_id
         join ad_rule r on r.tenant_id = d.tenant_id and r.id = e.rule_id
         join platform p on p.id = d.platform_id
      """;

  private AdRuleDispatchRow dispatchRow(Record r) {
    return new AdRuleDispatchRow(
        r.get("id", UUID.class),
        r.get("execution_id", UUID.class),
        r.get("rule_id", UUID.class),
        r.get("rule_name", String.class),
        r.get("platform_id", UUID.class),
        r.get("platform_code", String.class),
        r.get("shop_code", String.class),
        r.get("external_campaign_id", String.class),
        r.get("external_keyword_id", String.class),
        r.get("action_type", String.class),
        jsonNode(r.get("action_payload")),
        r.get("dispatch_status", String.class),
        r.get("dispatched_at", OffsetDateTime.class),
        r.get("dispatch_ref", String.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private com.fasterxml.jackson.databind.JsonNode jsonNode(Object raw) {
    if (raw == null) return json.createObjectNode();
    if (raw instanceof com.fasterxml.jackson.databind.JsonNode n) return n;
    try {
      return json.readTree(raw.toString());
    } catch (Exception e) {
      return json.createObjectNode();
    }
  }

  /** 待下发动作：真分页 + 真筛选。 */
  public AdRuleDispatchPage dispatches(UUID ruleId, String dispatchStatus, int page, int size) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where =
        new StringBuilder(
            " where d.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid");
    if (ruleId != null) {
      where.append(" and e.rule_id = ?");
      binds.add(ruleId);
    }
    String state = blankToNull(dispatchStatus);
    if (state != null) {
      where.append(" and d.dispatch_status = ?");
      binds.add(state);
    }

    long total = count("select count(*) from ad_rule_dispatch d join ad_rule_execution e on e.tenant_id = d.tenant_id and e.id = d.execution_id" + where, binds.toArray());
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    List<AdRuleDispatchRow> items =
        sql.fetch(
                DISPATCH_COLUMNS + where + " order by d.created_at desc, d.id desc limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::dispatchRow)
            .toList();

    long pendingCount =
        count(
            "select count(*) from ad_rule_dispatch d join ad_rule_execution e on e.tenant_id = d.tenant_id and e.id = d.execution_id"
                + where
                + " and d.dispatch_status = 'PENDING'",
            binds.toArray());

    return new AdRuleDispatchPage(items, total, safePage, safeSize, pendingCount);
  }

  /**
   * 撤销一条待下发动作。
   *
   * <p>🔴 这是本模块**唯一**能改变待下发队列的动作。真实下发（把动作发到平台）本模块**不实现**，
   * 所以这里不提供「标记已下发」这种假动作 —— 界面显示的是「待下发」，不是「已生效」。
   */
  public AdRuleDispatchRow cancelDispatch(String id, String reason) {
    UUID dispatchId = parseId(id);
    Record row =
        sql.fetchOne(
            DISPATCH_COLUMNS + TENANT_FILTER.replace("{alias}", "d") + " and d.id = ?", dispatchId);
    if (row == null) {
      throw notFound("待下发动作");
    }
    String status = row.get("dispatch_status", String.class);
    if (!status.equals("PENDING")) {
      throw conflict("只有待下发的动作可以撤销，当前状态：" + status);
    }
    String note = "已撤销：" + text(reason);
    sql.execute(
        "update ad_rule_dispatch set dispatch_status = 'CANCELLED', note = ?, updated_at = ?::timestamptz"
            + " where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and id = ?",
        note,
        OffsetDateTime.now(),
        dispatchId);
    Record updated =
        sql.fetchOne(
            DISPATCH_COLUMNS + TENANT_FILTER.replace("{alias}", "d") + " and d.id = ?", dispatchId);
    return dispatchRow(updated);
  }

  // ==================================================================
  // 效果回溯
  // ==================================================================

  private static final String EFFECT_COLUMNS =
      """
       select f.id, f.execution_id, f.rule_id, r.rule_name, f.metric_id,
              f.before_acos, f.after_acos, f.acos_delta,
              f.before_roas, f.after_roas, f.roas_delta,
              f.before_spend, f.after_spend, f.before_revenue, f.after_revenue, f.revenue_delta,
              f.verdict, f.verdict_note, f.observed_at
         from ad_rule_effect f join ad_rule r on r.tenant_id = f.tenant_id and r.id = f.rule_id
      """;

  private AdRuleEffectRow effectRow(Record r) {
    return new AdRuleEffectRow(
        r.get("id", UUID.class),
        r.get("execution_id", UUID.class),
        r.get("rule_id", UUID.class),
        r.get("rule_name", String.class),
        r.get("metric_id", UUID.class),
        r.get("before_acos", BigDecimal.class),
        r.get("after_acos", BigDecimal.class),
        r.get("acos_delta", BigDecimal.class),
        r.get("before_roas", BigDecimal.class),
        r.get("after_roas", BigDecimal.class),
        r.get("roas_delta", BigDecimal.class),
        r.get("before_spend", BigDecimal.class),
        r.get("after_spend", BigDecimal.class),
        r.get("before_revenue", BigDecimal.class),
        r.get("after_revenue", BigDecimal.class),
        r.get("revenue_delta", BigDecimal.class),
        r.get("verdict", String.class),
        r.get("verdict_note", String.class),
        r.get("observed_at", OffsetDateTime.class));
  }

  /** 效果回溯：真分页 + 真筛选。 */
  public AdRuleEffectPage effects(UUID ruleId, String verdict, int page, int size) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where =
        new StringBuilder(
            " where f.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid");
    if (ruleId != null) {
      where.append(" and f.rule_id = ?");
      binds.add(ruleId);
    }
    String state = blankToNull(verdict);
    if (state != null) {
      where.append(" and f.verdict = ?");
      binds.add(state);
    }

    long total = count("select count(*) from ad_rule_effect f" + where, binds.toArray());
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    List<AdRuleEffectRow> items =
        sql.fetch(
                EFFECT_COLUMNS + where + " order by f.observed_at desc, f.id desc limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::effectRow)
            .toList();

    long improved = count("select count(*) from ad_rule_effect f" + where + " and f.verdict = 'IMPROVED'", binds.toArray());
    long worsened = count("select count(*) from ad_rule_effect f" + where + " and f.verdict = 'WORSENED'", binds.toArray());
    long insufficient = count("select count(*) from ad_rule_effect f" + where + " and f.verdict = 'INSUFFICIENT_DATA'", binds.toArray());

    return new AdRuleEffectPage(items, total, safePage, safeSize, improved, worsened, insufficient);
  }

  /**
   * 回溯：把执行后的指标变化算出来，落成效果回溯记录。
   *
   * <p>🔴 「执行后还没有更新的指标数据」是一个**合法且必须被看见**的答案
   * （{@code verdict=INSUFFICIENT_DATA}），不能拿 0 冒充「没变化」。
   */
  public AdRuleEffectPage evaluateEffects(EffectEvaluateRequest request) {
    UUID tenant = currentTenant();
    List<Object> binds = new ArrayList<>();
    StringBuilder where =
        new StringBuilder(
            " where e.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and e.outcome = 'APPLIED'"
                // 🔴 只重算「还没有结论」的与「结论是数据不足」的：
                //    已有确定结论（IMPROVED / WORSENED / UNCHANGED）的不重复改写，
                //    而 INSUFFICIENT_DATA 的**必须**在数据到齐后重算一次 ——
                //    否则「等指标到了再看结论」这条路就断了。
                + " and not exists (select 1 from ad_rule_effect f"
                + "                 where f.tenant_id = e.tenant_id and f.execution_id = e.id"
                + "                   and f.verdict <> 'INSUFFICIENT_DATA')");
    if (request.ruleId() != null) {
      where.append(" and e.rule_id = ?");
      binds.add(request.ruleId());
    }

    List<Record> pending =
        sql.fetch(
            EXECUTION_COLUMNS + where + " order by e.executed_at asc, e.id asc", binds.toArray());

    OffsetDateTime observedAt = OffsetDateTime.now();
    for (Record r : pending) {
      UUID executionId = r.get("id", UUID.class);
      UUID ruleId = r.get("rule_id", UUID.class);
      UUID metricId = r.get("metric_id", UUID.class);
      OffsetDateTime executedAt = r.get("executed_at", OffsetDateTime.class);

      // 执行时的指标快照（来自命中记录）
      Record hit =
          sql.fetchOne(
              "select spend_snapshot, revenue_snapshot, acos_snapshot, roas_snapshot from ad_rule_hit"
                  + " where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                  + " and rule_id = ? and metric_id = ? order by id desc limit 1",
              ruleId,
              metricId);
      BigDecimal beforeSpend = hit == null ? BigDecimal.ZERO : nz(hit.get("spend_snapshot", BigDecimal.class));
      BigDecimal beforeRevenue = hit == null ? null : hit.get("revenue_snapshot", BigDecimal.class);
      BigDecimal beforeAcos = hit == null ? null : hit.get("acos_snapshot", BigDecimal.class);
      BigDecimal beforeRoas = hit == null ? null : hit.get("roas_snapshot", BigDecimal.class);

      // 执行之后最新的一行指标（同一对象、同一层级、指标截至时间晚于执行时刻）
      Record later =
          sql.fetchOne(
              "select spend, attributed_revenue, acos, roas from ad_rule_metric"
                  + " where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                  + " and platform_id = (select platform_id from ad_rule_metric where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and id = ?)"
                  + " and shop_code = (select shop_code from ad_rule_metric where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and id = ?)"
                  + " and metric_scope = (select metric_scope from ad_rule_metric where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and id = ?)"
                  + " and external_campaign_id = (select external_campaign_id from ad_rule_metric where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and id = ?)"
                  + " and external_keyword_id = (select external_keyword_id from ad_rule_metric where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and id = ?)"
                  + " and metrics_as_of > ?::timestamptz"
                  + " order by metrics_as_of desc, id desc limit 1",
              metricId,
              metricId,
              metricId,
              metricId,
              metricId,
              executedAt);

      BigDecimal afterSpend = BigDecimal.ZERO;
      BigDecimal afterRevenue = null;
      BigDecimal afterAcos = null;
      BigDecimal afterRoas = null;
      BigDecimal acosDelta = null;
      BigDecimal roasDelta = null;
      BigDecimal revenueDelta = null;
      String verdict;
      String note;
      if (later == null) {
        // 🔴 「数据还没到」是一个**合法结论**，不是「没变化」—— 绝不能拿 0 冒充。
        verdict = "INSUFFICIENT_DATA";
        note =
            "执行之后还没有更新的指标数据 —— 这是「数据还没到」，不是「调整没有效果」；"
                + "等指标登记进来再回溯一次就能看到结论";
      } else {
        afterSpend = nz(later.get("spend", BigDecimal.class));
        afterRevenue = later.get("attributed_revenue", BigDecimal.class);
        afterAcos = later.get("acos", BigDecimal.class);
        afterRoas = later.get("roas", BigDecimal.class);
        acosDelta =
            (beforeAcos == null || afterAcos == null) ? null : ratio(afterAcos.subtract(beforeAcos));
        roasDelta =
            (beforeRoas == null || afterRoas == null) ? null : ratio(afterRoas.subtract(beforeRoas));
        revenueDelta =
            (beforeRevenue == null || afterRevenue == null)
                ? null
                : money(afterRevenue.subtract(beforeRevenue));
        if (acosDelta != null) {
          verdict = acosDelta.signum() < 0 ? "IMPROVED" : (acosDelta.signum() > 0 ? "WORSENED" : "UNCHANGED");
          note =
              "以 ACOS 为准：执行前 "
                  + beforeAcos.toPlainString()
                  + " → 执行后 "
                  + afterAcos.toPlainString()
                  + "（ACOS 越低越好）";
        } else if (roasDelta != null) {
          verdict = roasDelta.signum() > 0 ? "IMPROVED" : (roasDelta.signum() < 0 ? "WORSENED" : "UNCHANGED");
          note =
              "ACOS 不可比（销售额为 0 或未登记），改以 ROAS 为准：执行前 "
                  + beforeRoas.toPlainString()
                  + " → 执行后 "
                  + afterRoas.toPlainString()
                  + "（ROAS 越高越好）";
        } else if (revenueDelta != null) {
          verdict =
              revenueDelta.signum() > 0 ? "IMPROVED" : (revenueDelta.signum() < 0 ? "WORSENED" : "UNCHANGED");
          note =
              "ACOS / ROAS 都不可比，改以归因销售额为准：执行前 "
                  + (beforeRevenue == null ? "" : beforeRevenue.toPlainString())
                  + " → 执行后 "
                  + afterRevenue.toPlainString();
        } else {
          verdict = "INSUFFICIENT_DATA";
          note = "执行后的指标里 ACOS / ROAS / 归因销售额都不可比，无法给出结论";
        }
      }

      // 🔴 数据不足也是一条**结论**，照样落库（否则「还没数据」与「没回溯过」会分不清）。
      sql.execute(
          """
          insert into ad_rule_effect
            (tenant_id, id, execution_id, rule_id, metric_id,
             before_acos, after_acos, acos_delta, before_roas, after_roas, roas_delta,
             before_spend, after_spend, before_revenue, after_revenue, revenue_delta,
             verdict, verdict_note, observed_at)
          values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)
          on conflict (tenant_id, execution_id) do update
             set after_acos = excluded.after_acos, acos_delta = excluded.acos_delta,
                 after_roas = excluded.after_roas, roas_delta = excluded.roas_delta,
                 after_spend = excluded.after_spend, after_revenue = excluded.after_revenue,
                 revenue_delta = excluded.revenue_delta,
                 verdict = excluded.verdict, verdict_note = excluded.verdict_note,
                 observed_at = excluded.observed_at
          """,
          tenant,
          Ids.next(),
          executionId,
          ruleId,
          metricId,
          beforeAcos,
          afterAcos,
          acosDelta,
          beforeRoas,
          afterRoas,
          roasDelta,
          money(beforeSpend),
          money(afterSpend),
          beforeRevenue,
          afterRevenue,
          revenueDelta,
          verdict,
          note,
          observedAt);
    }

    return effects(request.ruleId(), null, 0, 50);
  }

  // ==================================================================
  // 运行
  // ==================================================================

  private AdRuleRunRow runRow(Record r) {
    return new AdRuleRunRow(
        r.get("id", UUID.class),
        r.get("run_mode", String.class),
        r.get("trigger_source", String.class),
        r.get("status", String.class),
        r.get("evaluated_rules", Integer.class),
        r.get("hit_count", Integer.class),
        r.get("selected_count", Integer.class),
        r.get("execution_count", Integer.class),
        r.get("applied_count", Integer.class),
        r.get("dry_run_count", Integer.class),
        r.get("rejected_count", Integer.class),
        r.get("started_at", OffsetDateTime.class),
        r.get("finished_at", OffsetDateTime.class),
        r.get("note", String.class));
  }

  /** 运行列表：真分页。 */
  public AdRuleRunPage runs(String runMode, int page, int size) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where =
        new StringBuilder(
            " where x.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid");
    String mode = blankToNull(runMode);
    if (mode != null) {
      where.append(" and x.run_mode = ?");
      binds.add(mode);
    }
    long total = count("select count(*) from ad_rule_run x" + where, binds.toArray());
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);
    List<AdRuleRunRow> items =
        sql.fetch(
                """
                select x.id, x.run_mode, x.trigger_source, x.status, x.evaluated_rules, x.hit_count,
                       x.selected_count, x.execution_count, x.applied_count, x.dry_run_count,
                       x.rejected_count, x.started_at, x.finished_at, x.note
                  from ad_rule_run x
                """
                    + where
                    + " order by x.started_at desc, x.id desc limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::runRow)
            .toList();
    return new AdRuleRunPage(items, total, safePage, safeSize);
  }

  /** 运行详情：运行头 + 本次的命中记录 + 本次的执行记录。 */
  public AdRuleRunDetail runDetail(String id) {
    UUID runId = parseId(id);
    Record row =
        sql.fetchOne(
            """
            select x.id, x.run_mode, x.trigger_source, x.status, x.evaluated_rules, x.hit_count,
                   x.selected_count, x.execution_count, x.applied_count, x.dry_run_count,
                   x.rejected_count, x.started_at, x.finished_at, x.note
              from ad_rule_run x
             where x.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
               and x.id = ?
            """,
            runId);
    if (row == null) {
      throw notFound("评估运行");
    }
    List<AdRuleHitRow> hits =
        sql.fetch(
                HIT_COLUMNS
                    + " where h.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                    + " and h.run_id = ? order by h.priority asc, h.id asc",
                runId)
            .stream()
            .map(this::hitRow)
            .toList();
    List<AdRuleExecutionRow> executions =
        sql.fetch(
                EXECUTION_COLUMNS
                    + " where e.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                    + " and e.run_id = ? order by e.id asc",
                runId)
            .stream()
            .map(this::executionRow)
            .toList();
    return new AdRuleRunDetail(
        runRow(row), hits, executions, "命中记录与执行记录按本次运行逐条列出，未命中与被拒绝的都在。");
  }
}
