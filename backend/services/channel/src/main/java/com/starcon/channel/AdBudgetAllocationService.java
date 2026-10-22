package com.starcon.channel;

import static com.starcon.channel.AdBudgetAllocationModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 14.2.2.1 预算分配。
 *
 * <p>蓝图：「跨店铺/跨活动的预算分配与再平衡：按销售目标、利润、历史 ROAS 分配预算，识别预算受限的活动与闲置预算。
 * 输入：预算总额；分配规则；各活动历史表现。输出：分配方案 + 再平衡建议 + 执行任务。
 * 关系：上游 = 14.1.1.1、13.4 利润；下游 = 14.2.2.2。<b>预算应向利润高的活动倾斜</b>，按销售额平均分配是常见浪费。」
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>预算总额</b> → {@link #pools(...)} / {@link #savePool(...)}：预算是**池**（一个池一个币种，
 *       币种不一致的活动不折算、不参与分配，逐条列明原因）。
 *   <li><b>分配规则</b> → {@link #rules(...)} / {@link #saveRule(...)}：权重来源**可插拔**
 *       （销售目标 / 历史 ROAS / 利润位），利润位当前恒为 0（13.4 未交付）。
 *   <li><b>各活动历史表现</b> → 从 <b>14.1.1.1 的 {@code ad_campaign_daily_metric}</b> 聚合：
 *       窗口显式给出，日均消耗 = 窗口总消耗 / 窗口自然日数（口径可复算）。
 *   <li><b>分配方案</b> → {@link #computePlan(...)}：分配额之和**严格等于**预算总额（最大余数法）。
 *   <li><b>再平衡建议</b> → {@link #rebalances(...)}：闲置的钱挪给受限的活动，**总额不变**。
 *   <li><b>执行任务</b> → {@link #createTask(...)} / {@link #applyTask(String)}：逐条执行、
 *       逐条留痕，被护栏拒绝的那几条**照样有记录**。
 * </ul>
 *
 * <p>🔴 本模块最容易犯的三个错，这里逐个挡住：
 *
 * <ol>
 *   <li><b>用假利润顶替</b>。13.4 利润模块**未交付**（卷 13 的主责服务 reporting 不在本仓），
 *       所以利润维度<b>显式 available=false</b>，规则里的利润权重位<b>恒为 0</b>（DB CHECK 钉死），
 *       服务层拒绝任何非零利润权重。当前只按销售目标与历史 ROAS 分配。
 *   <li><b>按销售额平均分</b>。权重来源是三档**归一化后的分额**再加权 —— 销售目标（钱）与
 *       ROAS（比率）单位不同，直接相加就是错的；先归一化成 [0,1] 的分额再加权才可比。
 *   <li><b>分配额之和 ≠ 预算总额</b>。少分是预算凭空消失，超发是超预算 —— 两头都是事故。
 *       本模块用最大余数法（照抄 5.2.1.3 的做法）保证 Σ分配额 == 池总额，DB 层还有 CHECK 兜底。
 * </ol>
 *
 * <p>🔴 数据来源纪律：本模块**只读** 14.1.1.1 的 {@code ad_campaign} / {@code ad_campaign_daily_metric}，
 * 写入一律走**14.1.1.1 的写入口**（{@link AdCampaignService#bulkOperation}）——
 * {@code ad_campaign} 是 14.1.1.1 的事实表，**每张事实表只有一个写入所有者**，
 * 本模块不另开一条写路径（那会让两份代码各自改预算，最后谁也说不清预算是谁改的）。
 */
@Service
public class AdBudgetAllocationService {

  // ==================================================================
  // 护栏：执行任务能一次调多少预算（蓝图 14 章关键约束「必须有上限保护」）
  // ==================================================================

  /** 单个活动一次允许调整的预算上限（绝对值）。与 14.1.1.1 同口径。 */
  static final BigDecimal MAX_SINGLE_BUDGET_DELTA = new BigDecimal("5000.0000");

  /** 一次执行任务允许的总预算调整上限（绝对值）。与 14.1.1.1 同口径。 */
  static final BigDecimal MAX_TOTAL_BUDGET_DELTA = new BigDecimal("20000.0000");

  /** 单个活动的预算上限（调预算后的结果值不得超过）。与 14.1.1.1 同口径。 */
  static final BigDecimal MAX_CAMPAIGN_BUDGET = new BigDecimal("100000.0000");

  /** 一次执行任务最多允许的活动条数。 */
  static final int MAX_CAMPAIGNS_PER_TASK = 200;

  private static final String TENANT =
      "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 权重来源全集。PROFIT 是**位子**（当前恒为 0），不是兜底值。 */
  static final List<String> WEIGHT_SOURCES = List.of("SALES_TARGET", "ROAS", "PROFIT");

  static final List<String> RULE_STATUSES = List.of("ACTIVE", "PAUSED", "ARCHIVED");
  static final List<String> PLAN_STATUSES = List.of("COMPUTED", "APPROVED", "APPLIED", "SUPERSEDED");
  static final List<String> TASK_STATUSES =
      List.of("PENDING", "APPLIED", "PARTIALLY_APPLIED", "FAILED", "CANCELLED");
  static final List<String> RECOMMENDATIONS =
      List.of("HOLD", "INCREASE", "DECREASE", "PAUSE_CANDIDATE", "START_CANDIDATE");
  static final List<String> SKIP_REASONS =
      List.of("CURRENCY_MISMATCH", "NO_SCORE_BASIS", "STATUS_NOT_ELIGIBLE", "OUT_OF_SCOPE");

  /** 参与分配的活动状态（已结束 / 已归档 / 审核中的活动不该占预算）。 */
  static final List<String> ELIGIBLE_STATUSES = List.of("ENABLED", "PAUSED");

  // ==================================================================
  // 口径说明（随响应返回，界面上的表头与跨部门沟通用同一份定义）
  // ==================================================================

  static final String ALLOCATION_NOTE =
      "分配额 = 池总额 × (该活动权重 / 全体权重之和)。权重来自各来源**先归一化再加权**："
          + "每个来源先在活动之间换成分额（该活动该来源的取值 ÷ 全体取值之和），"
          + "再乘该来源的权重相加 —— 这样销售目标（钱）与 ROAS（比率）单位不同也不会互相污染。"
          + "分配额用**最大余数法**处理尾数（先向下取整到 0.0001，再按小数部分从大到小补回），"
          + "保证**分配额之和严格等于池总额**（少分是预算凭空消失，超发是超预算）。"
          + "「按销售额平均分配」不是本模块的口径：销售额高的活动本来就在赚钱，再加钱只是养肥边际递减的活动。";

  static final String UTILIZATION_NOTE =
      "日均消耗 = 窗口内总消耗 ÷ **窗口自然日数**（对所有活动一致，口径可复算）；"
          + "利用率 = 日均消耗 ÷ 日预算。"
          + "日预算为空或 0 ⇒ 利用率**不可比**（空值），与 0（可比但真的是零）含义相反；"
          + "窗口内没有任何每日指标 ⇒ 利用率同样不可比（分不清是「没花钱」还是「没同步到数据」）。"
          + "预算受限 = 利用率 ≥ 规则阈值（默认 0.95）；闲置预算 = 利用率 < 规则阈值（默认 0.30）。";

  static final String PROFIT_NOTE =
      "🔴 **利润维度当前不可用**：上游 13.4 利润分析（13.4.1.1 多模式利润报表）**未交付**"
          + "（卷 13 的主责服务 reporting 不在本仓）。当前只按**销售目标**与**历史 ROAS**分配，"
          + "**没有用假利润数字顶替**。规则里给利润留了权重位（可插拔），但权重恒为 0，"
          + "且 DB 层有 CHECK 钉死 —— 绕服务层直接写 SQL 也写不进非零利润权重。"
          + "13.4 交付后放开该 CHECK 并把利润分值接进评分即可，**分配算法一行都不用改**。";

  static final String CURRENCY_NOTE =
      "🔴 一个预算池一个币种，**不折算**：汇率查询（5.2.2.1）未交付，本仓没有可用的汇率来源，"
          + "写一个 1.0 的假汇率会让下游折算出错的金额。币种与预算池不一致的活动"
          + "**不参与分配**，并逐条列进「未纳入分配」清单写明原因（CURRENCY_MISMATCH）。";

  private final DSLContext sql;
  private final AdCampaignService campaigns;

  public AdBudgetAllocationService(DSLContext sql, AdCampaignService campaigns) {
    this.sql = sql;
    this.campaigns = campaigns;
  }

  // ==================================================================
  // 小工具（照抄 AdCampaignService 的同名写法）
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

  /** 当前事务内的租户（由 backend/libraries/tenancy 绑定，不来自任何客户端头）。 */
  private UUID currentTenant() {
    String raw = one(String.class, "select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(raw);
  }

  /** 当前操作者（已认证成员）。 */
  private UUID currentActor() {
    String raw = one(String.class, "select nullif(current_setting('app.actor',true),'')");
    return raw == null || raw.isBlank() ? null : UUID.fromString(raw);
  }

  /** 跨租户与不存在返回**逐字相同**的 404（照抄 10.1.1.2 / 10.1.2.1 同款口径）。 */
  private ResponseStatusException notFound(String what) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what + "不存在");
  }

  private static UUID parseId(String id) {
    try {
      return UUID.fromString(id.trim());
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id 不是合法 UUID");
    }
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  public AdBudgetCapabilities capabilities() {
    return new AdBudgetCapabilities(
        List.of(
            new AdBudgetCapabilityDimension(
                "budget_pool",
                "预算池：预算总额 + 币种 + 周期 + 分配规则（蓝图的「输入：预算总额」）",
                true,
                "ad_budget_pool（V278）",
                "",
                "没有预算总额就无从分配；一条池一个币种，跨币种不折算"),
            new AdBudgetCapabilityDimension(
                "allocation_rule",
                "分配规则：范围 + 上下限 + 利用率阈值 + **可插拔的权重位**",
                true,
                "ad_budget_rule + ad_budget_rule_weight + ad_budget_rule_target（V278）",
                "",
                "权重写死成常量，13.4 利润交付后就得重写算法"),
            new AdBudgetCapabilityDimension(
                "historical_performance",
                "各活动历史表现（窗口内的消耗 / 销售额 / 订单 / ROAS）",
                true,
                "ad_campaign_daily_metric（V273，14.1.1.1）",
                "",
                "没有历史表现就分不出「闲置预算」与「预算受限」"),
            new AdBudgetCapabilityDimension(
                "allocation_plan",
                "分配方案：逐活动分配额 + 权重 + 判定依据（冻结快照）",
                true,
                "ad_budget_plan + ad_budget_plan_line（V278）",
                "",
                "分配额之和必须严格等于池总额，否则预算凭空消失或超发"),
            new AdBudgetCapabilityDimension(
                "budget_constrained_and_idle",
                "识别**预算受限**（想花没预算）与**闲置预算**（有预算花不出去）",
                true,
                "ad_budget_plan_line.utilization（V278）",
                "",
                "把两者混成一个「预算利用率」数字，就没法回答「该给谁加钱」"),
            new AdBudgetCapabilityDimension(
                "rebalance_suggestion",
                "再平衡建议：把闲置的钱挪给受限的活动（**总额不变**）",
                true,
                "ad_budget_rebalance（V278）",
                "",
                "没有再平衡建议，跨店铺的钱只能靠人拍脑袋挪"),
            new AdBudgetAllocationModels.AdBudgetCapabilityDimension(
                "execution_task",
                "执行任务：逐条落预算，逐条留痕，被护栏拒绝的也留痕",
                true,
                "ad_budget_task + ad_budget_task_line（V278）",
                "",
                "没有执行任务，分配方案就只是一张纸"),
            new AdBudgetAllocationModels.AdBudgetCapabilityDimension(
                "profit_weight",
                "🔴 **利润加权**：按利润高低把预算倾斜过去（蓝图加粗句）",
                false,
                "—",
                "13.4 利润分析（13.4.1.1 多模式利润报表）",
                "当前只按销售目标与历史 ROAS 分配；利润权重位已占位但恒为 0（DB CHECK 钉死），"
                    + "**不用假利润数字顶替**；13.4 交付后放开闸门即可接上，算法不用改"),
            new AdBudgetAllocationModels.AdBudgetCapabilityDimension(
                "fx_conversion",
                "跨币种预算折算",
                false,
                "—",
                "5.2.2.1 汇率查询",
                "预算池一个币种；币种不一致的活动不参与分配并单独列明原因，**不做假折算**"),
            new AdBudgetAllocationModels.AdBudgetCapabilityDimension(
                "platform_writeback",
                "把改后的预算回写平台",
                false,
                "—",
                "平台适配层（channel/vendor）",
                "执行任务只落**本地**预算并标记「待回写平台」；界面上不把「本地已改」说成「平台侧已改」"),
            new AdBudgetAllocationModels.AdBudgetCapabilityDimension(
                "auto_rule_handoff",
                "按分配方案自动生成 14.2.2.2 自动规则",
                false,
                "—",
                "14.2.2.2 自动规则",
                "自动规则引擎已交付且直接读 ad_campaign（本模块落预算后它立刻能看到）；"
                    + "但「按分配方案自动建规则」这条通道本模块不提供"),
            new AdBudgetAllocationModels.AdBudgetCapabilityDimension(
                "sales_target_source",
                "销售目标的**系统来源**（目标下达 / 预测）",
                false,
                "—",
                "13.4.1.1 多模式利润报表（销售目标与利润口径同源）",
                "当前的销售目标是**人工登记**的（规则下逐活动登记），不是系统下达的目标")),
        WEIGHT_SOURCES,
        RULE_STATUSES,
        PLAN_STATUSES,
        TASK_STATUSES,
        RECOMMENDATIONS,
        SKIP_REASONS,
        guardrails(),
        ALLOCATION_NOTE,
        UTILIZATION_NOTE,
        PROFIT_NOTE,
        CURRENCY_NOTE,
        "上游 = 14.1.1.1 广告活动列表（已交付，本模块只读它的活动与每日指标）；"
            + "13.4 利润分析**未交付** ⇒ 利润维度 available=false。"
            + "各活动的「数据截至 X 时间」由 14.1.1.1 的同步水位负责，本模块不重复造一个水位。",
        "下游 = 14.2.2.2 自动规则（已交付，本模块**不实现**规则引擎）；"
            + "平台侧回写未交付 ⇒ 改动只落本地并标记待回写。");
  }

  private static AdBudgetAllocationGuardrails guardrails() {
    return new AdBudgetAllocationGuardrails(
        MAX_SINGLE_BUDGET_DELTA,
        MAX_TOTAL_BUDGET_DELTA,
        MAX_CAMPAIGN_BUDGET,
        MAX_CAMPAIGNS_PER_TASK,
        "🔴 单次上限与总额上限一起给：单次上限挡住「把一个活动改成天价」，"
            + "总额上限挡住「一次误操作烧掉整月预算」。"
            + "超限的那几条会被**逐条拒绝**并留痕（不是静默截断 —— "
            + "静默截断会让人以为「按预期工作」，而实际上调了另一个数）。");
  }

  // ==================================================================
  // 选项
  // ==================================================================

  public AdBudgetOptions options() {
    List<String> currencies =
        sql.fetch(
                "select distinct currency from ad_campaign where tenant_id = "
                    + TENANT
                    + " and currency <> '' order by currency")
            .stream()
            .map(r -> r.get(0, String.class))
            .toList();

    LocalDate metricFrom =
        one(
            LocalDate.class,
            "select min(metric_date) from ad_campaign_daily_metric where tenant_id = " + TENANT);
    LocalDate metricTo =
        one(
            LocalDate.class,
            "select max(metric_date) from ad_campaign_daily_metric where tenant_id = " + TENANT);

    return new AdBudgetOptions(
        weightSourceOptions(),
        ruleStatusOptions(),
        planStatusOptions(),
        taskStatusOptions(),
        recommendationOptions(),
        skipReasonOptions(),
        currencies,
        metricFrom,
        metricTo,
        guardrails(),
        ALLOCATION_NOTE,
        UTILIZATION_NOTE);
  }

  private static List<AdBudgetOption> weightSourceOptions() {
    return List.of(
        new AdBudgetOption("SALES_TARGET", "销售目标权重（人工登记的目标销售额）"),
        new AdBudgetOption("ROAS", "历史 ROAS 权重（窗口内销售额 / 消耗）"),
        new AdBudgetOption("PROFIT", "利润权重（🔴 13.4 未交付，当前不可用）"));
  }

  private static List<AdBudgetOption> ruleStatusOptions() {
    return List.of(
        new AdBudgetOption("ACTIVE", "启用"),
        new AdBudgetOption("PAUSED", "停用"),
        new AdBudgetOption("ARCHIVED", "已归档"));
  }

  private static List<AdBudgetOption> planStatusOptions() {
    return List.of(
        new AdBudgetOption("COMPUTED", "已计算"),
        new AdBudgetOption("APPROVED", "已确认"),
        new AdBudgetOption("APPLIED", "已执行"),
        new AdBudgetOption("SUPERSEDED", "已被新方案取代"));
  }

  private static List<AdBudgetOption> taskStatusOptions() {
    return List.of(
        new AdBudgetOption("PENDING", "待执行"),
        new AdBudgetOption("APPLIED", "全部生效"),
        new AdBudgetOption("PARTIALLY_APPLIED", "部分生效"),
        new AdBudgetOption("FAILED", "未生效"),
        new AdBudgetOption("CANCELLED", "已撤销"));
  }

  private static List<AdBudgetOption> recommendationOptions() {
    return List.of(
        new AdBudgetOption("HOLD", "维持"),
        new AdBudgetOption("INCREASE", "建议加预算"),
        new AdBudgetOption("DECREASE", "建议减预算"),
        new AdBudgetOption("PAUSE_CANDIDATE", "建议收回预算并保持暂停"),
        new AdBudgetOption("START_CANDIDATE", "建议开预算"));
  }

  private static List<AdBudgetOption> skipReasonOptions() {
    return List.of(
        new AdBudgetOption("CURRENCY_MISMATCH", "币种与预算池不一致（不折算）"),
        new AdBudgetOption("NO_SCORE_BASIS", "既没有登记销售目标，也没有可比的 ROAS"),
        new AdBudgetOption("STATUS_NOT_ELIGIBLE", "活动状态不该参与分配"),
        new AdBudgetOption("OUT_OF_SCOPE", "不在规则范围内"));
  }

  // ==================================================================
  // 规则
  // ==================================================================

  public AdBudgetRulePage rules(String status, String currency, String keyword, int page, int size) {
    StringBuilder where = new StringBuilder(" where tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    String state = blankToNull(status);
    if (state != null) {
      where.append(" and status = ?");
      binds.add(state);
    }
    String cur = blankToNull(currency);
    if (cur != null) {
      where.append(" and currency = ?");
      binds.add(cur.toUpperCase(Locale.ROOT));
    }
    String word = blankToNull(keyword);
    if (word != null) {
      where.append(" and (rule_code ilike ? or rule_name ilike ?)");
      String pattern = "%" + escapeLike(word) + "%";
      binds.add(pattern);
      binds.add(pattern);
    }

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    long total = count("select count(*) from ad_budget_rule" + where, binds.toArray());

    List<AdBudgetRuleRow> items =
        sql.fetch(
                "select id, rule_code, rule_name, currency, scope_platform_id, scope_shop_code,"
                    + " min_campaign_budget, max_campaign_budget, constrained_utilization,"
                    + " idle_utilization, status, note, version, created_at, updated_at,"
                    + " (select p.code from platform p where p.id = ad_budget_rule.scope_platform_id)"
                    + " scope_platform_code,"
                    + " (select count(*) from ad_budget_rule_target t"
                    + "   where t.tenant_id = ad_budget_rule.tenant_id and t.rule_id = ad_budget_rule.id)"
                    + " target_count"
                    + " from ad_budget_rule"
                    + where
                    + " order by created_at desc, id desc limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(r -> ruleRow(r, weightsOf(r.get("id", UUID.class))))
            .toList();

    return new AdBudgetRulePage(items, total, safePage, safeSize);
  }

  private AdBudgetRuleRow ruleRow(Record r, List<AdBudgetWeightRow> weights) {
    return new AdBudgetRuleRow(
        r.get("id", UUID.class),
        r.get("rule_code", String.class),
        r.get("rule_name", String.class),
        r.get("currency", String.class),
        r.get("scope_platform_id", UUID.class),
        r.get("scope_platform_code", String.class),
        r.get("scope_shop_code", String.class),
        r.get("min_campaign_budget", BigDecimal.class),
        r.get("max_campaign_budget", BigDecimal.class),
        r.get("constrained_utilization", BigDecimal.class),
        r.get("idle_utilization", BigDecimal.class),
        r.get("status", String.class),
        weights,
        r.get("target_count", Integer.class),
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private List<AdBudgetWeightRow> weightsOf(UUID ruleId) {
    return sql.fetch(
            "select weight_source, weight, note, position from ad_budget_rule_weight"
                + " where tenant_id = "
                + TENANT
                + " and rule_id = ? order by position, weight_source",
            ruleId)
        .stream()
        .map(
            r ->
                new AdBudgetWeightRow(
                    r.get("weight_source", String.class),
                    r.get("weight", BigDecimal.class),
                    r.get("note", String.class),
                    r.get("position", Integer.class)))
        .toList();
  }

  public AdBudgetRuleDetail rule(String id) {
    UUID ruleId = parseId(id);
    Record row =
        sql.fetchOne(
            "select id, rule_code, rule_name, currency, scope_platform_id, scope_shop_code,"
                + " min_campaign_budget, max_campaign_budget, constrained_utilization,"
                + " idle_utilization, status, note, version, created_at, updated_at,"
                + " (select p.code from platform p where p.id = ad_budget_rule.scope_platform_id)"
                + " scope_platform_code"
                + " from ad_budget_rule where tenant_id = "
                + TENANT
                + " and id = ?",
            ruleId);
    if (row == null) {
      throw notFound("分配规则");
    }
    List<AdBudgetWeightRow> weights = weightsOf(ruleId);
    List<AdBudgetRuleTargetRow> targets = targetsOf(ruleId);
    AdBudgetRuleRow rule =
        new AdBudgetRuleRow(
            row.get("id", UUID.class),
            row.get("rule_code", String.class),
            row.get("rule_name", String.class),
            row.get("currency", String.class),
            row.get("scope_platform_id", UUID.class),
            row.get("scope_platform_code", String.class),
            row.get("scope_shop_code", String.class),
            row.get("min_campaign_budget", BigDecimal.class),
            row.get("max_campaign_budget", BigDecimal.class),
            row.get("constrained_utilization", BigDecimal.class),
            row.get("idle_utilization", BigDecimal.class),
            row.get("status", String.class),
            weights,
            targets.size(),
            row.get("note", String.class),
            row.get("version", Long.class),
            row.get("created_at", OffsetDateTime.class),
            row.get("updated_at", OffsetDateTime.class));
    return new AdBudgetRuleDetail(
        rule,
        targets,
        "权重位是可插拔的：SALES_TARGET / ROAS 现在可用，PROFIT 当前恒为 0（13.4 利润未交付）。");
  }

  private List<AdBudgetRuleTargetRow> targetsOf(UUID ruleId) {
    return sql.fetch(
            "select t.id, t.campaign_id, c.campaign_name, pf.code platform_code, c.shop_code,"
                + " t.sales_target, t.min_budget, t.max_budget, t.note, t.created_at"
                + " from ad_budget_rule_target t"
                + " join ad_campaign c on c.tenant_id = t.tenant_id and c.id = t.campaign_id"
                + " join platform pf on pf.id = c.platform_id"
                + " where t.tenant_id = "
                + TENANT
                + " and t.rule_id = ? order by c.campaign_name, t.id",
            ruleId)
        .stream()
        .map(
            r ->
                new AdBudgetRuleTargetRow(
                    r.get("id", UUID.class),
                    r.get("campaign_id", UUID.class),
                    r.get("campaign_name", String.class),
                    r.get("platform_code", String.class),
                    r.get("shop_code", String.class),
                    r.get("sales_target", BigDecimal.class),
                    r.get("min_budget", BigDecimal.class),
                    r.get("max_budget", BigDecimal.class),
                    r.get("note", String.class),
                    r.get("created_at", OffsetDateTime.class)))
        .toList();
  }


  /**
   * 登记分配规则（含权重位与销售目标）。
   *
   * <p>🔴 三道闸，缺一不可：
   *
   * <ol>
   *   <li><b>至少一个权重大于 0</b>：全 0 的规则算出来的分配额全是 0，留着只会让人以为它在生效。
   *   <li><b>利润权重必须为 0</b>：13.4 利润未交付 ⇒ 没有数据源。非零利润权重一律拒绝，
   *       并说清「等 13.4 交付」—— 拒绝的理由要说得出「为什么现在不行」，而不是「参数非法」。
   *   <li><b>上下限自洽</b>：上限低于下限的规则算出来的分配额必然越界。
   * </ol>
   */
  public AdBudgetRuleDetail saveRule(SaveBudgetRuleRequest request) {
    String code = text(request.ruleCode());
    String name = text(request.ruleName());
    String currency = text(request.currency()).toUpperCase(Locale.ROOT);
    if (code.isEmpty() || name.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "规则编码与名称都不能为空");
    }
    if (currency.length() != 3) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必须是 3 位字母代码");
    }

    BigDecimal constrained = request.constrainedUtilization();
    BigDecimal idle = request.idleUtilization();
    if (constrained == null) {
      constrained = new BigDecimal("0.950000");
    }
    if (idle == null) {
      idle = new BigDecimal("0.300000");
    }
    if (constrained.signum() <= 0 || constrained.compareTo(new BigDecimal("10")) > 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "预算受限的利用率阈值必须在 0 之后且不超过 10");
    }
    if (idle.signum() < 0 || idle.compareTo(BigDecimal.ONE) >= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "闲置预算的利用率阈值必须在 [0, 1) 之间");
    }
    if (constrained.compareTo(idle) <= 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "预算受限阈值必须高于闲置阈值，否则同一条活动会同时「受限」又「闲置」");
    }
    if (request.minCampaignBudget() != null
        && request.maxCampaignBudget() != null
        && request.maxCampaignBudget().compareTo(request.minCampaignBudget()) < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单活动预算上限不能低于下限");
    }

    // ---- 权重位 ----
    Map<String, BigDecimal> weightBySource = new LinkedHashMap<>();
    int position = 0;
    for (SaveBudgetWeight weight : request.weights()) {
      String source = text(weight.weightSource()).toUpperCase(Locale.ROOT);
      if (!WEIGHT_SOURCES.contains(source)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "权重来源只能是 SALES_TARGET / ROAS / PROFIT");
      }
      if (weight.weight() == null || weight.weight().signum() < 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "权重不能为负");
      }
      if ("PROFIT".equals(source) && weight.weight().signum() > 0) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "利润权重当前不可用：上游 13.4 利润分析（13.4.1.1 多模式利润报表）尚未交付，"
                + "没有利润数据源。现在只能按销售目标与历史 ROAS 分配；"
                + "13.4 交付后这一档会自动放开。");
      }
      if (weightBySource.containsKey(source)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "权重来源重复：" + source);
      }
      weightBySource.put(source, weight.weight());
      position++;
    }
    boolean anyPositive = weightBySource.values().stream().anyMatch(w -> w.signum() > 0);
    if (!anyPositive) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "至少一个权重大于 0 —— 全 0 的规则算出来的分配额全是 0");
    }

    // ---- 销售目标 ----
    Map<UUID, SaveBudgetTarget> targets = new LinkedHashMap<>();
    for (SaveBudgetTarget target : request.targets()) {
      if (target.campaignId() == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "销售目标必须点名活动");
      }
      if (targets.containsKey(target.campaignId())) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "同一个活动在一条规则里只能登记一次销售目标");
      }
      if (target.salesTarget() == null || target.salesTarget().signum() <= 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "销售目标必须大于 0");
      }
      if (target.maxBudget() != null
          && target.minBudget() != null
          && target.maxBudget().compareTo(target.minBudget()) < 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单活动预算上限不能低于下限");
      }
      Long exists =
          one(
              Long.class,
              "select count(*) from ad_campaign where tenant_id = " + TENANT + " and id = ?",
              target.campaignId());
      if (exists == null || exists == 0) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "销售目标指向的活动不存在或不属于当前租户");
      }
      targets.put(target.campaignId(), target);
    }

    UUID ruleId = Ids.next();
    sql.execute(
        "insert into ad_budget_rule (tenant_id,id,rule_code,rule_name,currency,scope_platform_id,"
            + " scope_shop_code,min_campaign_budget,max_campaign_budget,constrained_utilization,"
            + " idle_utilization,status,note,version,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,0,now(),now())",
        currentTenant(),
        ruleId,
        code,
        name,
        currency,
        request.scopePlatformId(),
        blankToNull(request.scopeShopCode()),
        request.minCampaignBudget(),
        request.maxCampaignBudget(),
        constrained,
        idle,
        text(request.note()));

    int pos = 0;
    for (Map.Entry<String, BigDecimal> entry : weightBySource.entrySet()) {
      sql.execute(
          "insert into ad_budget_rule_weight (tenant_id,id,rule_id,weight_source,weight,note,position,"
              + " created_at) values (?,?,?,?,?,?,?,now())",
          currentTenant(),
          Ids.next(),
          ruleId,
          entry.getKey(),
          entry.getValue(),
          "PROFIT".equals(entry.getKey())
              ? "利润权重位：13.4 利润分析未交付，当前恒为 0"
              : "",
          pos);
      pos++;
    }

    for (SaveBudgetTarget target : targets.values()) {
      sql.execute(
          "insert into ad_budget_rule_target (tenant_id,id,rule_id,campaign_id,sales_target,"
              + " min_budget,max_budget,note,created_at) values (?,?,?,?,?,?,?,?,now())",
          currentTenant(),
          Ids.next(),
          ruleId,
          target.campaignId(),
          target.salesTarget(),
          target.minBudget(),
          target.maxBudget(),
          text(target.note()));
    }

    return rule(ruleId.toString());
  }

  // ==================================================================
  // 预算池
  // ==================================================================

  public AdBudgetPoolPage pools(String currency, String keyword, int page, int size) {
    // 🔴 �� p.tenant_id 限定：这里 join 了规则表，两张表都有 tenant_id，不限定就是「列引用有歧义」
    StringBuilder where = new StringBuilder(" where p.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    String cur = blankToNull(currency);
    if (cur != null) {
      where.append(" and currency = ?");
      binds.add(cur.toUpperCase(Locale.ROOT));
    }
    String word = blankToNull(keyword);
    if (word != null) {
      where.append(" and (pool_code ilike ? or pool_name ilike ?)");
      String pattern = "%" + escapeLike(word) + "%";
      binds.add(pattern);
      binds.add(pattern);
    }

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    long total = count("select count(*) from ad_budget_pool p" + where, binds.toArray());

    List<AdBudgetPoolRow> items =
        sql.fetch(
                "select p.id, p.pool_code, p.pool_name, p.currency, p.total_amount, p.period_start,"
                    + " p.period_end, p.rule_id, r.rule_name, p.status, p.note, p.version,"
                    + " p.created_at, p.updated_at"
                    + " from ad_budget_pool p"
                    + " join ad_budget_rule r on r.tenant_id = p.tenant_id and r.id = p.rule_id"
                    + where
                    + " order by p.created_at desc, p.id desc limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::poolRow)
            .toList();

    return new AdBudgetPoolPage(items, total, safePage, safeSize);
  }

  private AdBudgetPoolRow poolRow(Record r) {
    return new AdBudgetPoolRow(
        r.get("id", UUID.class),
        r.get("pool_code", String.class),
        r.get("pool_name", String.class),
        r.get("currency", String.class),
        r.get("total_amount", BigDecimal.class),
        r.get("period_start", LocalDate.class),
        r.get("period_end", LocalDate.class),
        r.get("rule_id", UUID.class),
        r.get("rule_name", String.class),
        r.get("status", String.class),
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  public AdBudgetPoolRow pool(String id) {
    UUID poolId = parseId(id);
    Record row =
        sql.fetchOne(
            "select p.id, p.pool_code, p.pool_name, p.currency, p.total_amount, p.period_start,"
                + " p.period_end, p.rule_id, r.rule_name, p.status, p.note, p.version,"
                + " p.created_at, p.updated_at"
                + " from ad_budget_pool p"
                + " join ad_budget_rule r on r.tenant_id = p.tenant_id and r.id = p.rule_id"
                + " where p.tenant_id = "
                + TENANT
                + " and p.id = ?",
            poolId);
    if (row == null) {
      throw notFound("预算池");
    }
    return poolRow(row);
  }

  /** 登记预算池（蓝图的「输入：预算总额」）。 */
  public AdBudgetPoolRow savePool(SaveBudgetPoolRequest request) {
    String code = text(request.poolCode());
    String name = text(request.poolName());
    String currency = text(request.currency()).toUpperCase(Locale.ROOT);
    if (code.isEmpty() || name.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "预算池编码与名称都不能为空");
    }
    if (currency.length() != 3) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必须是 3 位字母代码");
    }
    if (request.totalAmount() == null || request.totalAmount().signum() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "预算总额必须大于 0");
    }
    if (request.periodStart() == null
        || request.periodEnd() == null
        || request.periodEnd().isBefore(request.periodStart())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "预算池的周期区间不合法");
    }
    Record rule =
        sql.fetchOne(
            "select currency from ad_budget_rule where tenant_id = " + TENANT + " and id = ?",
            request.ruleId());
    if (rule == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分配规则不存在或不属于当前租户");
    }
    String ruleCurrency = rule.get("currency", String.class);
    if (!ruleCurrency.equals(currency)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "预算池币种必须与分配规则一致（规则是 " + ruleCurrency + "）—— "
              + "跨币种分配需要汇率，而汇率查询尚未交付");
    }

    UUID poolId = Ids.next();
    sql.execute(
        "insert into ad_budget_pool (tenant_id,id,pool_code,pool_name,currency,total_amount,"
            + " period_start,period_end,rule_id,status,note,version,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?::date,?::date,?,'ACTIVE',?,0,now(),now())",
        currentTenant(),
        poolId,
        code,
        name,
        currency,
        request.totalAmount().setScale(4, RoundingMode.HALF_UP),
        request.periodStart(),
        request.periodEnd(),
        request.ruleId(),
        text(request.note()));

    return pool(poolId.toString());
  }

  // ==================================================================
  // 分配方案
  // ==================================================================

  public AdBudgetPlanPage plans(String status, UUID poolId, UUID ruleId, int page, int size) {
    StringBuilder where = new StringBuilder(" where p.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    String state = blankToNull(status);
    if (state != null) {
      where.append(" and p.status = ?");
      binds.add(state);
    }
    if (poolId != null) {
      where.append(" and p.pool_id = ?");
      binds.add(poolId);
    }
    if (ruleId != null) {
      where.append(" and p.rule_id = ?");
      binds.add(ruleId);
    }

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    long total = count("select count(*) from ad_budget_plan p" + where, binds.toArray());

    List<AdBudgetPlanRow> items =
        sql.fetch(
                "select p.id, p.pool_id, po.pool_name, p.rule_id, r.rule_name, p.status, p.currency,"
                    + " p.total_amount, p.allocated_amount, p.unallocated_amount, p.released_amount,"
                    + " p.idle_budget, p.constrained_shortfall, p.campaign_count, p.skipped_count,"
                    + " p.window_start, p.window_end, p.computed_at, p.note"
                    + " from ad_budget_plan p"
                    + " join ad_budget_pool po on po.tenant_id = p.tenant_id and po.id = p.pool_id"
                    + " join ad_budget_rule r on r.tenant_id = p.tenant_id and r.id = p.rule_id"
                    + where
                    + " order by p.created_at desc, p.id desc limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::planRow)
            .toList();

    return new AdBudgetPlanPage(items, total, safePage, safeSize);
  }

  private AdBudgetPlanRow planRow(Record r) {
    return new AdBudgetPlanRow(
        r.get("id", UUID.class),
        r.get("pool_id", UUID.class),
        r.get("pool_name", String.class),
        r.get("rule_id", UUID.class),
        r.get("rule_name", String.class),
        r.get("status", String.class),
        r.get("currency", String.class),
        r.get("total_amount", BigDecimal.class),
        r.get("allocated_amount", BigDecimal.class),
        r.get("unallocated_amount", BigDecimal.class),
        r.get("released_amount", BigDecimal.class),
        r.get("idle_budget", BigDecimal.class),
        r.get("constrained_shortfall", BigDecimal.class),
        r.get("campaign_count", Integer.class),
        r.get("skipped_count", Integer.class),
        r.get("window_start", LocalDate.class),
        r.get("window_end", LocalDate.class),
        r.get("computed_at", OffsetDateTime.class),
        r.get("note", String.class));
  }

  public AdBudgetPlanDetail plan(String id) {
    UUID planId = parseId(id);
    Record row =
        sql.fetchOne(
            "select p.id, p.pool_id, po.pool_name, p.rule_id, r.rule_name, p.status, p.currency,"
                + " p.total_amount, p.allocated_amount, p.unallocated_amount, p.released_amount,"
                + " p.idle_budget, p.constrained_shortfall, p.campaign_count, p.skipped_count,"
                + " p.window_start, p.window_end, p.computed_at, p.note"
                + " from ad_budget_plan p"
                + " join ad_budget_pool po on po.tenant_id = p.tenant_id and po.id = p.pool_id"
                + " join ad_budget_rule r on r.tenant_id = p.tenant_id and r.id = p.rule_id"
                + " where p.tenant_id = "
                + TENANT
                + " and p.id = ?",
            planId);
    if (row == null) {
      throw notFound("分配方案");
    }
    return new AdBudgetPlanDetail(
        planRow(row), planLines(planId), planSkipped(planId), planRebalances(planId), ALLOCATION_NOTE);
  }

  private List<AdBudgetPlanLineRow> planLines(UUID planId) {
    return sql.fetch(
            "select id, campaign_id, campaign_name, platform_code, shop_code, campaign_status,"
                + " currency, current_budget, historical_spend, historical_sales, historical_orders,"
                + " roas, acos, daily_avg_spend, utilization, sales_target, score, weight,"
                + " weight_share, allocated_budget, budget_delta, budget_constrained, idle_budget,"
                + " recommendation, reason, position"
                + " from ad_budget_plan_line where tenant_id = "
                + TENANT
                + " and plan_id = ? order by position, campaign_id",
            planId)
        .stream()
        .map(
            r ->
                new AdBudgetPlanLineRow(
                    r.get("id", UUID.class),
                    r.get("campaign_id", UUID.class),
                    r.get("campaign_name", String.class),
                    r.get("platform_code", String.class),
                    r.get("shop_code", String.class),
                    r.get("campaign_status", String.class),
                    r.get("currency", String.class),
                    r.get("current_budget", BigDecimal.class),
                    r.get("historical_spend", BigDecimal.class),
                    r.get("historical_sales", BigDecimal.class),
                    r.get("historical_orders", BigDecimal.class),
                    r.get("roas", BigDecimal.class),
                    r.get("acos", BigDecimal.class),
                    r.get("daily_avg_spend", BigDecimal.class),
                    r.get("utilization", BigDecimal.class),
                    r.get("sales_target", BigDecimal.class),
                    r.get("score", BigDecimal.class),
                    r.get("weight", BigDecimal.class),
                    r.get("weight_share", BigDecimal.class),
                    r.get("allocated_budget", BigDecimal.class),
                    r.get("budget_delta", BigDecimal.class),
                    r.get("budget_constrained", Boolean.class),
                    r.get("idle_budget", Boolean.class),
                    r.get("recommendation", String.class),
                    r.get("reason", String.class),
                    r.get("position", Integer.class)))
        .toList();
  }

  private List<AdBudgetSkippedRow> planSkipped(UUID planId) {
    return sql.fetch(
            "select id, campaign_id, campaign_name, platform_code, shop_code, campaign_status,"
                + " currency, current_budget, skip_reason, reason"
                + " from ad_budget_plan_skipped where tenant_id = "
                + TENANT
                + " and plan_id = ? order by campaign_id",
            planId)
        .stream()
        .map(
            r ->
                new AdBudgetSkippedRow(
                    r.get("id", UUID.class),
                    r.get("campaign_id", UUID.class),
                    r.get("campaign_name", String.class),
                    r.get("platform_code", String.class),
                    r.get("shop_code", String.class),
                    r.get("campaign_status", String.class),
                    r.get("currency", String.class),
                    r.get("current_budget", BigDecimal.class),
                    r.get("skip_reason", String.class),
                    r.get("reason", String.class)))
        .toList();
  }

  private List<AdBudgetRebalanceRow> planRebalances(UUID planId) {
    return sql.fetch(
            "select rb.id, rb.plan_id, rb.from_campaign_id, fc.campaign_name from_campaign_name,"
                + " rb.to_campaign_id, tc.campaign_name to_campaign_name, rb.amount, rb.reason,"
                + " rb.status, rb.decided_at, rb.created_at"
                + " from ad_budget_rebalance rb"
                + " join ad_campaign fc on fc.tenant_id = rb.tenant_id and fc.id = rb.from_campaign_id"
                + " join ad_campaign tc on tc.tenant_id = rb.tenant_id and tc.id = rb.to_campaign_id"
                + " where rb.tenant_id = "
                + TENANT
                + " and rb.plan_id = ? order by rb.created_at, rb.id",
            planId)
        .stream()
        .map(
            r ->
                new AdBudgetRebalanceRow(
                    r.get("id", UUID.class),
                    r.get("plan_id", UUID.class),
                    r.get("from_campaign_id", UUID.class),
                    r.get("from_campaign_name", String.class),
                    r.get("to_campaign_id", UUID.class),
                    r.get("to_campaign_name", String.class),
                    r.get("amount", BigDecimal.class),
                    r.get("reason", String.class),
                    r.get("status", String.class),
                    r.get("decided_at", OffsetDateTime.class),
                    r.get("created_at", OffsetDateTime.class)))
        .toList();
  }

  // ==================================================================
  // 🔴 计算分配方案
  // ==================================================================

  /** 计算中的一条活动（候选）。 */
  private record Candidate(
      UUID id,
      String name,
      String platformCode,
      String shopCode,
      String status,
      String currency,
      BigDecimal currentBudget,
      BigDecimal spend,
      BigDecimal sales,
      BigDecimal orders,
      int metricDays) {}

  /** 参与分配的一条活动（已定权重）。 */
  private record Participant(
      Candidate candidate,
      BigDecimal salesTarget,
      BigDecimal roas,
      BigDecimal acos,
      BigDecimal dailyAvgSpend,
      BigDecimal utilization,
      BigDecimal score,
      BigDecimal weight,
      BigDecimal allocated,
      BigDecimal delta,
      boolean constrained,
      boolean idle,
      String recommendation,
      String reason) {}

  /**
   * 按预算池与规则计算一份分配方案并落库。
   *
   * <p>🔴 三条不可让步的纪律：
   *
   * <ol>
   *   <li><b>统计窗口显式给出</b>：不从墙上时钟推导（「上个月」这种键在月初与月末会圈出不同范围）。
   *   <li><b>分配额之和严格等于池总额</b>：最大余数法（先向下取整到 0.0001，再按小数部分从大到小补回）。
   *   <li><b>没用假利润</b>：利润权重恒为 0（13.4 未交付），当前只按销售目标与历史 ROAS 分配。
   * </ol>
   */
  public AdBudgetPlanDetail computePlan(ComputeBudgetPlanRequest request) {
    if (request.windowStart() == null || request.windowEnd() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "统计窗口必须显式给出");
    }
    if (request.windowEnd().isBefore(request.windowStart())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "统计窗口的结束日期不能早于开始日期");
    }

    Record poolRow =
        sql.fetchOne(
            "select id, pool_code, pool_name, currency, total_amount, period_start, period_end,"
                + " rule_id from ad_budget_pool where tenant_id = "
                + TENANT
                + " and id = ?",
            request.poolId());
    if (poolRow == null) {
      throw notFound("预算池");
    }
    String currency = poolRow.get("currency", String.class);
    BigDecimal total = poolRow.get("total_amount", BigDecimal.class);
    UUID ruleId = poolRow.get("rule_id", UUID.class);

    Record ruleRow =
        sql.fetchOne(
            "select id, rule_code, currency, scope_platform_id, scope_shop_code,"
                + " min_campaign_budget, max_campaign_budget, constrained_utilization,"
                + " idle_utilization from ad_budget_rule where tenant_id = "
                + TENANT
                + " and id = ?",
            ruleId);
    if (ruleRow == null) {
      throw notFound("分配规则");
    }
    UUID scopePlatformId = ruleRow.get("scope_platform_id", UUID.class);
    String scopeShopCode = ruleRow.get("scope_shop_code", String.class);
    BigDecimal ruleMin = ruleRow.get("min_campaign_budget", BigDecimal.class);
    BigDecimal ruleMax = ruleRow.get("max_campaign_budget", BigDecimal.class);
    BigDecimal constrainedThreshold = ruleRow.get("constrained_utilization", BigDecimal.class);
    BigDecimal idleThreshold = ruleRow.get("idle_utilization", BigDecimal.class);

    // ---- 权重位（可插拔；PROFIT 当前恒为 0）----
    Map<String, BigDecimal> weights = new LinkedHashMap<>();
    for (AdBudgetWeightRow w : weightsOf(ruleId)) {
      weights.put(w.weightSource(), w.weight());
    }
    BigDecimal wSales = weights.getOrDefault("SALES_TARGET", BigDecimal.ZERO);
    BigDecimal wRoas = weights.getOrDefault("ROAS", BigDecimal.ZERO);
    BigDecimal wProfit = weights.getOrDefault("PROFIT", BigDecimal.ZERO);
    if (wProfit.signum() > 0) {
      // DB CHECK 已经钉死，这里再挡一道：绕开服务层写进来的数据也要在这里被看见。
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "这条规则带着非零的利润权重，但 13.4 利润分析尚未交付 —— 没有利润数据源，拒绝计算。");
    }

    // ---- 销售目标 ----
    Map<UUID, SaveBudgetTarget> targets = new LinkedHashMap<>();
    for (AdBudgetRuleTargetRow t : targetsOf(ruleId)) {
      SaveBudgetTarget st = new SaveBudgetTarget(
          t.campaignId(), t.salesTarget(), t.minBudget(), t.maxBudget(), t.note());
      targets.put(t.campaignId(), st);
    }

    // ---- 候选活动（跨店铺 / 跨活动，规则范围内）----
    List<Candidate> candidates = candidates(ruleId, scopePlatformId, scopeShopCode, request);

    int windowDays =
        (int) ChronoUnit.DAYS.between(request.windowStart(), request.windowEnd()) + 1;

    // ---- 第一遍：分出「参与」与「未参与」----
    List<Participant> participants = new ArrayList<>();
    List<AdBudgetSkippedRow> skipped = new ArrayList<>();

    for (Candidate c : candidates) {
      if (!currency.equals(c.currency())) {
        skipped.add(
            skippedRow(
                c,
                "CURRENCY_MISMATCH",
                "活动币种 " + c.currency() + " 与预算池币种 " + currency + " 不一致；"
                    + "汇率查询尚未交付，本模块不做折算，该活动不参与本次分配。"));
        continue;
      }
      SaveBudgetTarget target = targets.get(c.id());
      BigDecimal salesTarget = target == null ? null : target.salesTarget();

      BigDecimal roas = null;
      BigDecimal acos = null;
      if (c.spend().signum() > 0) {
        roas = c.sales().divide(c.spend(), 6, RoundingMode.HALF_UP);
        if (c.sales().signum() > 0) {
          acos = c.spend().divide(c.sales(), 6, RoundingMode.HALF_UP);
        }
      }

      boolean hasSalesTarget = salesTarget != null && salesTarget.signum() > 0;
      boolean hasRoas = roas != null;
      if (!hasSalesTarget && !hasRoas) {
        skipped.add(
            skippedRow(
                c,
                "NO_SCORE_BASIS",
                "既没有登记销售目标，也没有可比的 ROAS（窗口内消耗为 0 ⇒ ROAS 不可比），"
                    + "没有可用的分配依据；该活动的现有预算**不受本次分配影响**。"));
        continue;
      }

      // ---- 日均消耗与利用率 ----
      BigDecimal dailyAvgSpend = null;
      BigDecimal utilization = null;
      if (c.metricDays() > 0) {
        dailyAvgSpend =
            c.spend().divide(BigDecimal.valueOf(windowDays), 4, RoundingMode.HALF_UP);
        if (c.currentBudget() != null && c.currentBudget().signum() > 0) {
          utilization =
              dailyAvgSpend.divide(c.currentBudget(), 6, RoundingMode.HALF_UP);
        }
      }

      boolean constrained =
          utilization != null && utilization.compareTo(constrainedThreshold) >= 0;
      boolean idle = utilization != null && utilization.compareTo(idleThreshold) < 0;

      participants.add(
          new Participant(
              c, salesTarget, roas, acos, dailyAvgSpend, utilization, BigDecimal.ZERO,
              BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, constrained, idle, "HOLD", ""));
    }

    if (participants.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "没有可参与分配的活动：范围内 "
              + candidates.size()
              + " 条活动，"
              + skipped.size()
              + " 条被排除（"
              + skipped.stream()
                  .map(AdBudgetSkippedRow::skipReason)
                  .distinct()
                  .reduce((a, b) -> a + "、" + b)
                  .orElse("无")
              + "）。请先补销售目标或广告数据同步。");
    }

    // ---- 第二遍：各来源归一化成分额，再按权重加权 ----
    BigDecimal sumSalesTarget =
        participants.stream()
            .map(p -> nz(p.salesTarget()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal sumRoas =
        participants.stream()
            .map(p -> nz(p.roas()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    List<Participant> scored = new ArrayList<>();
    for (Participant p : participants) {
      BigDecimal salesShare =
          sumSalesTarget.signum() > 0 && p.salesTarget() != null
              ? p.salesTarget().divide(sumSalesTarget, 12, RoundingMode.HALF_UP)
              : BigDecimal.ZERO;
      BigDecimal roasShare =
          sumRoas.signum() > 0 && p.roas() != null
              ? p.roas().divide(sumRoas, 12, RoundingMode.HALF_UP)
              : BigDecimal.ZERO;
      BigDecimal score =
          wSales
              .multiply(salesShare)
              .add(wRoas.multiply(roasShare))
              .add(wProfit.multiply(BigDecimal.ZERO))
              .setScale(12, RoundingMode.HALF_UP);
      scored.add(
          new Participant(
              p.candidate(),
              p.salesTarget(),
              p.roas(),
              p.acos(),
              p.dailyAvgSpend(),
              p.utilization(),
              score,
              BigDecimal.ZERO,
              BigDecimal.ZERO,
              BigDecimal.ZERO,
              p.constrained(),
              p.idle(),
              p.recommendation(),
              p.reason()));
    }

    BigDecimal sumScore =
        scored.stream().map(p -> p.score()).reduce(BigDecimal.ZERO, BigDecimal::add);
    if (sumScore.signum() <= 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "权重合计为 0，算不出分配额：规则里的权重大于 0，但对应来源在本窗口内没有可用数据。");
    }

    List<Participant> weighted = new ArrayList<>();
    for (Participant p : scored) {
      BigDecimal weight =
          p.score().divide(sumScore, 12, RoundingMode.HALF_UP);
      weighted.add(
          new Participant(
              p.candidate(),
              p.salesTarget(),
              p.roas(),
              p.acos(),
              p.dailyAvgSpend(),
              p.utilization(),
              p.score(),
              weight,
              BigDecimal.ZERO,
              BigDecimal.ZERO,
              p.constrained(),
              p.idle(),
              p.recommendation(),
              p.reason()));
    }

    // ---- 第三遍：分配额（最大余数法 ⇒ Σ 严格等于池总额）----
    Map<UUID, BigDecimal> allocated = allocateLargestRemainder(total, weighted);

    // ---- 第四遍：单活动上下限（上下限是软约束，池总额是硬约束）----
    //   上限挡住的部分留在池里（→ 未分配额）；下限要抬高某条活动时，只能用
    //   「上限挡下来的那部分」或池里剩下的空间 —— 池总额是硬约束，
    //   因为池里只有这么多钱，许诺超出手里的钱就是超发预算。
    BigDecimal headroom = total.subtract(sum(allocated.values()));
    List<Participant> capped = new ArrayList<>();
    Map<UUID, BigDecimal> finalAllocated = new LinkedHashMap<>();
    Map<UUID, String> capNotes = new LinkedHashMap<>();
    for (Participant p : weighted) {
      BigDecimal base = allocated.get(p.candidate().id());
      BigDecimal min =
          targetMin(targets, p.candidate().id(), ruleMin);
      BigDecimal max =
          targetMax(targets, p.candidate().id(), ruleMax);
      BigDecimal value = base;
      String note = "";
      if (max != null && base.compareTo(max) > 0) {
        value = max;
        note = "受单活动预算上限 " + max + " 约束";
      }
      if (min != null && value.compareTo(min) < 0) {
        BigDecimal need = min.subtract(value);
        if (need.compareTo(headroom) <= 0) {
          value = min;
          headroom = headroom.subtract(need);
          note = note.isEmpty() ? "按单活动预算下限 " + min + " 兜底" : note + "；并按下限 " + min + " 兜底";
        } else {
          note =
              (note.isEmpty() ? "" : note + "；")
                  + "单活动预算下限 "
                  + min
                  + " 受池总额限制未满足（池里只剩 "
                  + headroom
                  + "）";
        }
      }
      if (!note.isEmpty()) {
        capNotes.put(p.candidate().id(), note);
      }
      finalAllocated.put(p.candidate().id(), value);
      BigDecimal delta = value.subtract(nz(p.candidate().currentBudget()));
      capped.add(
          new Participant(
              p.candidate(),
              p.salesTarget(),
              p.roas(),
              p.acos(),
              p.dailyAvgSpend(),
              p.utilization(),
              p.score(),
              p.weight(),
              value,
              delta,
              p.constrained(),
              p.idle(),
              "HOLD",
              ""));
    }

    // ---- 第五遍：判定（建议 + 依据）----
    List<Participant> judged = new ArrayList<>();
    int position = 0;
    for (Participant p : capped) {
      Candidate c = p.candidate();
      BigDecimal current = c.currentBudget();
      boolean noBudget = current == null || current.signum() == 0;
      String recommendation;
      String reason;
      if ("PAUSED".equals(c.status()) && current != null && current.signum() > 0) {
        recommendation = "PAUSE_CANDIDATE";
        reason =
            "活动已暂停但仍挂着日预算 "
                + current
                + "，这部分预算既花不出去也不产生投放，建议收回。";
      } else if (noBudget) {
        recommendation = "START_CANDIDATE";
        reason = "当前没有日预算（平台侧没有预算，不是 0），本次分配给了起始预算。";
      } else if (p.constrained() && p.roas() != null) {
        recommendation = "INCREASE";
        reason =
            "预算受限：利用率 "
                + p.utilization()
                + " ≥ 阈值 "
                + constrainedThreshold
                + "，且历史 ROAS "
                + p.roas()
                + " 可比，值得加预算。";
      } else if (p.constrained()) {
        recommendation = "HOLD";
        reason =
            "预算受限：利用率 "
                + p.utilization()
                + " ≥ 阈值 "
                + constrainedThreshold
                + "，但历史 ROAS **不可比**（窗口内消耗为 0），"
                + "不知道加预算是否划算，先维持。";
      } else if (p.idle()) {
        recommendation = "DECREASE";
        reason =
            "闲置预算：利用率 "
                + p.utilization()
                + " < 阈值 "
                + idleThreshold
                + "（日均消耗 "
                + p.dailyAvgSpend()
                + " / 日预算 "
                + current
                + "），钱花不出去。";
      } else {
        recommendation = "HOLD";
        reason =
            p.utilization() == null
                ? "利用率不可比（窗口内没有每日指标，或平台侧没有日预算），不做调整。"
                : "利用率 "
                    + p.utilization()
                    + " 在正常区间，维持。";
      }
      String capNote = capNotes.get(c.id());
      if (capNote != null) {
        reason = reason + " " + capNote + "。";
      }
      judged.add(
          new Participant(
              c,
              p.salesTarget(),
              p.roas(),
              p.acos(),
              p.dailyAvgSpend(),
              p.utilization(),
              p.score(),
              p.weight(),
              p.allocated(),
              p.delta(),
              p.constrained(),
              p.idle(),
              recommendation,
              reason));
      position++;
    }

    BigDecimal allocatedTotal = sum(finalAllocated.values());
    BigDecimal unallocated = total.subtract(allocatedTotal);

    BigDecimal released = BigDecimal.ZERO;
    BigDecimal idleBudget = BigDecimal.ZERO;
    BigDecimal shortfall = BigDecimal.ZERO;
    for (Participant p : judged) {
      BigDecimal current = nz(p.candidate().currentBudget());
      if (current.subtract(p.allocated()).signum() > 0) {
        released = released.add(current.subtract(p.allocated()));
      }
      if (p.idle() && p.dailyAvgSpend() != null) {
        BigDecimal unused = current.subtract(p.dailyAvgSpend());
        if (unused.signum() > 0) {
          idleBudget = idleBudget.add(unused);
        }
      }
      if (p.constrained() && p.dailyAvgSpend() != null) {
        BigDecimal need = p.dailyAvgSpend().subtract(current);
        if (need.signum() > 0) {
          shortfall = shortfall.add(need);
        }
      }
    }

    // ---- 落库 ----
    UUID planId = Ids.next();
    sql.execute(
        "insert into ad_budget_plan (tenant_id,id,pool_id,rule_id,status,currency,total_amount,"
            + " allocated_amount,unallocated_amount,released_amount,idle_budget,constrained_shortfall,"
            + " campaign_count,skipped_count,window_start,window_end,computed_at,computed_by,note,"
            + " version,created_at,updated_at)"
            + " values (?,?,?,?,'COMPUTED',?,?,?,?,?,?,?,?,?,?::date,?::date,?::timestamptz,?,?,0,now(),now())",
        currentTenant(),
        planId,
        request.poolId(),
        ruleId,
        currency,
        total.setScale(4, RoundingMode.HALF_UP),
        allocatedTotal.setScale(4, RoundingMode.HALF_UP),
        unallocated.setScale(4, RoundingMode.HALF_UP),
        released.setScale(4, RoundingMode.HALF_UP),
        idleBudget.setScale(4, RoundingMode.HALF_UP),
        shortfall.setScale(4, RoundingMode.HALF_UP),
        judged.size(),
        skipped.size(),
        request.windowStart(),
        request.windowEnd(),
        OffsetDateTime.now(),
        currentActor(),
        text(request.note()));

    int pos = 0;
    for (Participant p : judged) {
      Candidate c = p.candidate();
      sql.execute(
          "insert into ad_budget_plan_line (tenant_id,id,plan_id,campaign_id,campaign_name,"
              + " platform_code,shop_code,campaign_status,currency,current_budget,historical_spend,"
              + " historical_sales,historical_orders,roas,acos,daily_avg_spend,utilization,"
              + " sales_target,score,weight,weight_share,allocated_budget,budget_delta,"
              + " budget_constrained,idle_budget,recommendation,reason,position,created_at)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now())",
          currentTenant(),
          Ids.next(),
          planId,
          c.id(),
          c.name(),
          c.platformCode(),
          c.shopCode(),
          c.status(),
          c.currency(),
          c.currentBudget(),
          c.spend(),
          c.sales(),
          c.orders(),
          p.roas(),
          p.acos(),
          p.dailyAvgSpend(),
          p.utilization(),
          p.salesTarget(),
          p.score().setScale(6, RoundingMode.HALF_UP),
          p.weight().setScale(6, RoundingMode.HALF_UP),
          p.weight().setScale(6, RoundingMode.HALF_UP),
          p.allocated().setScale(4, RoundingMode.HALF_UP),
          p.delta().setScale(4, RoundingMode.HALF_UP),
          p.constrained(),
          p.idle(),
          p.recommendation(),
          p.reason(),
          pos);
      pos++;
    }

    for (AdBudgetSkippedRow row : skipped) {
      sql.execute(
          "insert into ad_budget_plan_skipped (tenant_id,id,plan_id,campaign_id,campaign_name,"
              + " platform_code,shop_code,campaign_status,currency,current_budget,skip_reason,reason,"
              + " created_at) values (?,?,?,?,?,?,?,?,?,?,?,?,now())",
          currentTenant(),
          Ids.next(),
          planId,
          row.campaignId(),
          row.campaignName(),
          row.platformCode(),
          row.shopCode(),
          row.campaignStatus(),
          row.currency(),
          row.currentBudget(),
          row.skipReason(),
          row.reason());
    }

    // ---- 再平衡建议（总额不变，只改分布）----
    List<RebalanceDraft> drafts = rebalanceDrafts(judged, idleThreshold);
    for (RebalanceDraft d : drafts) {
      sql.execute(
          "insert into ad_budget_rebalance (tenant_id,id,plan_id,from_campaign_id,to_campaign_id,"
              + " amount,reason,status,created_at) values (?,?,?,?,?,?,?,'PROPOSED',now())",
          currentTenant(),
          Ids.next(),
          planId,
          d.from().id(),
          d.to().id(),
          d.amount().setScale(4, RoundingMode.HALF_UP),
          d.reason());
    }

    return plan(planId.toString());
  }

  private record RebalanceDraft(Candidate from, Candidate to, BigDecimal amount, String reason) {}

  /** 未纳入分配的一条活动。 */
  private AdBudgetSkippedRow skippedRow(Candidate c, String reason, String detail) {
    return new AdBudgetSkippedRow(
        null,
        c.id(),
        c.name(),
        c.platformCode(),
        c.shopCode(),
        c.status(),
        c.currency(),
        c.currentBudget(),
        reason,
        detail);
  }

  /** 候选活动：规则范围内、状态可参与、窗口内的历史表现一次取齐。 */
  private List<Candidate> candidates(
      UUID ruleId, UUID scopePlatformId, String scopeShopCode, ComputeBudgetPlanRequest request) {
    StringBuilder sqlText =
        new StringBuilder(
            """
            select c.id, c.campaign_name, pf.code platform_code, c.shop_code, c.status, c.currency,
                   c.daily_budget,
                   coalesce(m.spend,0) spend, coalesce(m.sales,0) sales, coalesce(m.orders,0) orders,
                   coalesce(m.metric_days,0) metric_days
              from ad_campaign c
              join platform pf on pf.id = c.platform_id
              left join (
            """);
    sqlText.append(
        "select campaign_id, sum(spend) spend, sum(sales) sales, sum(orders) orders,"
            + " count(distinct metric_date) metric_days"
            + " from ad_campaign_daily_metric where tenant_id = "
            + TENANT
            + " and metric_date >= ?::date and metric_date <= ?::date group by campaign_id");
    sqlText.append(" ) m on m.campaign_id = c.id");
    sqlText.append(" where c.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    binds.add(request.windowStart());
    binds.add(request.windowEnd());
    sqlText.append(" and c.status in ('ENABLED','PAUSED')");
    if (scopePlatformId != null) {
      sqlText.append(" and c.platform_id = ?");
      binds.add(scopePlatformId);
    }
    String shop = blankToNull(scopeShopCode);
    if (shop != null) {
      sqlText.append(" and c.shop_code = ?");
      binds.add(shop);
    }
    sqlText.append(" order by c.id");

    return sql.fetch(sqlText.toString(), binds.toArray()).stream()
        .map(
            r ->
                new Candidate(
                    r.get("id", UUID.class),
                    r.get("campaign_name", String.class),
                    r.get("platform_code", String.class),
                    r.get("shop_code", String.class),
                    r.get("status", String.class),
                    r.get("currency", String.class),
                    r.get("daily_budget", BigDecimal.class),
                    nz(r.get("spend", BigDecimal.class)),
                    nz(r.get("sales", BigDecimal.class)),
                    nz(r.get("orders", BigDecimal.class)),
                    r.get("metric_days", Integer.class)))
        .toList();
  }

  /**
   * 最大余数法分摊：先向下取整到 0.0001，再按小数部分从大到小把余数补回去。
   *
   * <p>🔴 保证 **Σ分配额严格等于池总额**（少分是预算凭空消失，超发是超预算，两头都是事故）。
   * 并列时按活动 id 升序 ⇒ 结果可复现，不会每次算出来都不一样。
   */
  private static Map<UUID, BigDecimal> allocateLargestRemainder(
      BigDecimal total, List<Participant> participants) {
    Map<UUID, BigDecimal> raw = new LinkedHashMap<>();
    Map<UUID, BigDecimal> fraction = new LinkedHashMap<>();
    Map<UUID, BigDecimal> floor = new LinkedHashMap<>();
    for (Participant p : participants) {
      BigDecimal value =
          total.multiply(p.weight()).setScale(10, RoundingMode.HALF_UP);
      BigDecimal floored = value.setScale(4, RoundingMode.DOWN);
      raw.put(p.candidate().id(), value);
      floor.put(p.candidate().id(), floored);
      fraction.put(p.candidate().id(), value.subtract(floored));
    }
    BigDecimal allocatedSum =
        floor.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal remainder = total.subtract(allocatedSum);

    List<UUID> order =
        participants.stream()
            .map(p -> p.candidate().id())
            .sorted(
                Comparator.comparing((UUID id) -> fraction.get(id))
                    .reversed()
                    .thenComparing(Comparator.naturalOrder()))
            .toList();

    Map<UUID, BigDecimal> result = new LinkedHashMap<>(floor);
    int index = 0;
    while (remainder.signum() > 0 && !order.isEmpty()) {
      UUID id = order.get(index % order.size());
      BigDecimal step = BigDecimal.valueOf(0.0001);
      result.put(id, result.get(id).add(step));
      remainder = remainder.subtract(step);
      index++;
      if (index > order.size() * 4) {
        // 兜底：余数不该超过行数（向下取整最多每行丢 0.0001）。真走到这里说明有 bug，
        // 宁可停在整数分上，也不要造出 Σ ≠ 总额的分配额。
        break;
      }
    }
    return result;
  }

  private static BigDecimal sum(Iterable<BigDecimal> values) {
    BigDecimal total = BigDecimal.ZERO;
    for (BigDecimal v : values) {
      total = total.add(v);
    }
    return total;
  }

  private static BigDecimal targetMin(Map<UUID, SaveBudgetTarget> targets, UUID campaignId, BigDecimal fallback) {
    SaveBudgetTarget t = targets.get(campaignId);
    if (t != null && t.minBudget() != null) {
      return t.minBudget();
    }
    return fallback;
  }

  private static BigDecimal targetMax(Map<UUID, SaveBudgetTarget> targets, UUID campaignId, BigDecimal fallback) {
    SaveBudgetTarget t = targets.get(campaignId);
    if (t != null && t.maxBudget() != null) {
      return t.maxBudget();
    }
    return fallback;
  }

  /**
   * 再平衡建议：从闲置的活动挪给预算受限的活动，**总额不变**。
   *
   * <p>🔴 与分配方案是**两个独立视角**：分配方案回答「这笔预算池怎么摊」，
   * 再平衡回答「当前的预算分布里钱该从谁挪给谁」。两者都基于同一份历史表现，但口径不同。
   *
   * <p>可挪额度 = 当前日预算 − 日均消耗 / 闲置阈值（保留够到闲置阈值的预算，其余是可挪的）；
   * 缺口 = 日均消耗 − 当前日预算（补到 100% 利用率还差多少）。
   */
  private static List<RebalanceDraft> rebalanceDrafts(
      List<Participant> judged, BigDecimal idleThreshold) {
    List<Participant> donors = new ArrayList<>();
    List<Participant> recipients = new ArrayList<>();
    for (Participant p : judged) {
      BigDecimal current = nz(p.candidate().currentBudget());
      if (p.dailyAvgSpend() == null) {
        continue;
      }
      if ("PAUSE_CANDIDATE".equals(p.recommendation())
          || (p.idle() && current.signum() > 0)) {
        BigDecimal keep =
            idleThreshold.signum() > 0
                ? p.dailyAvgSpend().divide(idleThreshold, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal releasable = current.subtract(keep);
        if (releasable.signum() > 0) {
          donors.add(
              new Participant(
                  p.candidate(),
                  p.salesTarget(),
                  p.roas(),
                  p.acos(),
                  p.dailyAvgSpend(),
                  p.utilization(),
                  p.score(),
                  p.weight(),
                  releasable,
                  BigDecimal.ZERO,
                  p.constrained(),
                  p.idle(),
                  p.recommendation(),
                  p.reason()));
        }
      }
      if (p.constrained()) {
        BigDecimal need = p.dailyAvgSpend().subtract(current);
        if (need.signum() > 0) {
          recipients.add(
              new Participant(
                  p.candidate(),
                  p.salesTarget(),
                  p.roas(),
                  p.acos(),
                  p.dailyAvgSpend(),
                  p.utilization(),
                  p.score(),
                  p.weight(),
                  need,
                  BigDecimal.ZERO,
                  p.constrained(),
                  p.idle(),
                  p.recommendation(),
                  p.reason()));
        }
      }
    }

    donors.sort(
        Comparator.comparing((Participant p) -> p.allocated()).reversed()
            .thenComparing(p -> p.candidate().id()));
    recipients.sort(
        Comparator.comparing((Participant p) -> p.score()).reversed()
            .thenComparing(p -> p.candidate().id()));

    Map<UUID, BigDecimal> donorLeft = new LinkedHashMap<>();
    for (Participant d : donors) {
      donorLeft.put(d.candidate().id(), d.allocated());
    }

    List<RebalanceDraft> drafts = new ArrayList<>();
    for (Participant r : recipients) {
      BigDecimal needLeft = r.allocated();
      for (Participant d : donors) {
        if (needLeft.signum() <= 0) {
          break;
        }
        BigDecimal donorAmount = donorLeft.get(d.candidate().id());
        if (donorAmount.signum() <= 0) {
          continue;
        }
        BigDecimal amount = needLeft.min(donorAmount).setScale(4, RoundingMode.DOWN);
        if (amount.signum() <= 0) {
          continue;
        }
        donorLeft.put(d.candidate().id(), donorAmount.subtract(amount));
        needLeft = needLeft.subtract(amount);
        drafts.add(
            new RebalanceDraft(
                d.candidate(),
                r.candidate(),
                amount,
                "「"
                    + d.candidate().name()
                    + "」闲置预算（日均消耗 "
                    + d.dailyAvgSpend()
                    + " / 日预算 "
                    + nz(d.candidate().currentBudget())
                    + "），"
                    + "「"
                    + r.candidate().name()
                    + "」预算受限（利用率 "
                    + r.utilization()
                    + "，日均消耗 "
                    + r.dailyAvgSpend()
                    + " / 日预算 "
                    + nz(r.candidate().currentBudget())
                    + "）；建议把 "
                    + amount
                    + " 从前者挪给后者（总额不变）。"));
      }
    }
    return drafts;
  }

  // ==================================================================
  // 再平衡建议集合读
  // ==================================================================

  public AdBudgetRebalancePage rebalances(UUID planId, String status, int page, int size) {
    StringBuilder where = new StringBuilder(" where rb.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    if (planId != null) {
      where.append(" and rb.plan_id = ?");
      binds.add(planId);
    }
    String state = blankToNull(status);
    if (state != null) {
      where.append(" and rb.status = ?");
      binds.add(state);
    }
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    long total =
        count("select count(*) from ad_budget_rebalance rb" + where, binds.toArray());

    List<AdBudgetRebalanceRow> items =
        sql.fetch(
                "select rb.id, rb.plan_id, rb.from_campaign_id, fc.campaign_name from_campaign_name,"
                    + " rb.to_campaign_id, tc.campaign_name to_campaign_name, rb.amount, rb.reason,"
                    + " rb.status, rb.decided_at, rb.created_at"
                    + " from ad_budget_rebalance rb"
                    + " join ad_campaign fc on fc.tenant_id = rb.tenant_id and fc.id = rb.from_campaign_id"
                    + " join ad_campaign tc on tc.tenant_id = rb.tenant_id and tc.id = rb.to_campaign_id"
                    + where
                    + " order by rb.created_at desc, rb.id desc limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(
                r ->
                    new AdBudgetRebalanceRow(
                        r.get("id", UUID.class),
                        r.get("plan_id", UUID.class),
                        r.get("from_campaign_id", UUID.class),
                        r.get("from_campaign_name", String.class),
                        r.get("to_campaign_id", UUID.class),
                        r.get("to_campaign_name", String.class),
                        r.get("amount", BigDecimal.class),
                        r.get("reason", String.class),
                        r.get("status", String.class),
                        r.get("decided_at", OffsetDateTime.class),
                        r.get("created_at", OffsetDateTime.class)))
            .toList();

    return new AdBudgetRebalancePage(items, total, safePage, safeSize);
  }

  // ==================================================================
  // 执行任务
  // ==================================================================

  public AdBudgetTaskPage tasks(UUID planId, String status, int page, int size) {
    StringBuilder where = new StringBuilder(" where t.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    if (planId != null) {
      where.append(" and t.plan_id = ?");
      binds.add(planId);
    }
    String state = blankToNull(status);
    if (state != null) {
      where.append(" and t.status = ?");
      binds.add(state);
    }
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    long total = count("select count(*) from ad_budget_task t" + where, binds.toArray());

    List<AdBudgetTaskRow> items =
        sql.fetch(
                "select t.id, t.plan_id, po.pool_name, t.task_type, t.status, t.currency, t.requested_by,"
                    + " t.reason, t.applied_at, t.note, t.created_at,"
                    + " (select count(*) from ad_budget_task_line l where l.tenant_id = t.tenant_id"
                    + "   and l.task_id = t.id) line_count,"
                    + " (select count(*) from ad_budget_task_line l where l.tenant_id = t.tenant_id"
                    + "   and l.task_id = t.id and l.result = 'APPLIED') applied_count,"
                    + " (select count(*) from ad_budget_task_line l where l.tenant_id = t.tenant_id"
                    + "   and l.task_id = t.id and l.result = 'REJECTED') rejected_count,"
                    + " (select count(*) from ad_budget_task_line l where l.tenant_id = t.tenant_id"
                    + "   and l.task_id = t.id and l.result = 'SKIPPED') skipped_count"
                    + " from ad_budget_task t"
                    + " join ad_budget_plan p on p.tenant_id = t.tenant_id and p.id = t.plan_id"
                    + " join ad_budget_pool po on po.tenant_id = p.tenant_id and po.id = p.pool_id"
                    + where
                    + " order by t.created_at desc, t.id desc limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::taskRow)
            .toList();

    return new AdBudgetTaskPage(items, total, safePage, safeSize);
  }

  private AdBudgetTaskRow taskRow(Record r) {
    return new AdBudgetTaskRow(
        r.get("id", UUID.class),
        r.get("plan_id", UUID.class),
        r.get("pool_name", String.class),
        r.get("task_type", String.class),
        r.get("status", String.class),
        r.get("currency", String.class),
        r.get("requested_by", UUID.class),
        r.get("reason", String.class),
        r.get("applied_at", OffsetDateTime.class),
        r.get("note", String.class),
        r.get("line_count", Integer.class),
        r.get("applied_count", Integer.class),
        r.get("rejected_count", Integer.class),
        r.get("skipped_count", Integer.class),
        r.get("created_at", OffsetDateTime.class));
  }

  public AdBudgetTaskDetail task(String id) {
    UUID taskId = parseId(id);
    Record row =
        sql.fetchOne(
            "select t.id, t.plan_id, po.pool_name, t.task_type, t.status, t.currency, t.requested_by,"
                + " t.reason, t.applied_at, t.note, t.created_at,"
                + " (select count(*) from ad_budget_task_line l where l.tenant_id = t.tenant_id"
                + "   and l.task_id = t.id) line_count,"
                + " (select count(*) from ad_budget_task_line l where l.tenant_id = t.tenant_id"
                + "   and l.task_id = t.id and l.result = 'APPLIED') applied_count,"
                + " (select count(*) from ad_budget_task_line l where l.tenant_id = t.tenant_id"
                + "   and l.task_id = t.id and l.result = 'REJECTED') rejected_count,"
                + " (select count(*) from ad_budget_task_line l where l.tenant_id = t.tenant_id"
                + "   and l.task_id = t.id and l.result = 'SKIPPED') skipped_count"
                + " from ad_budget_task t"
                + " join ad_budget_plan p on p.tenant_id = t.tenant_id and p.id = t.plan_id"
                + " join ad_budget_pool po on po.tenant_id = p.tenant_id and po.id = p.pool_id"
                + " where t.tenant_id = "
                + TENANT
                + " and t.id = ?",
            taskId);
    if (row == null) {
      throw notFound("执行任务");
    }
    List<AdBudgetTaskLineRow> lines =
        sql.fetch(
                "select id, campaign_id, campaign_name, before_budget, target_budget,"
                    + " applied_budget, budget_delta, result, message"
                    + " from ad_budget_task_line where tenant_id = "
                    + TENANT
                    + " and task_id = ? order by created_at, id",
                taskId)
            .stream()
            .map(
                l ->
                    new AdBudgetTaskLineRow(
                        l.get("id", UUID.class),
                        l.get("campaign_id", UUID.class),
                        l.get("campaign_name", String.class),
                        l.get("before_budget", BigDecimal.class),
                        l.get("target_budget", BigDecimal.class),
                        l.get("applied_budget", BigDecimal.class),
                        l.get("budget_delta", BigDecimal.class),
                        l.get("result", String.class),
                        l.get("message", String.class)))
            .toList();
    return new AdBudgetTaskDetail(
        taskRow(row),
        lines,
        "🔴 改动只落**本地**并标记「待回写平台」（平台回写队列未交付）；"
            + "被护栏拒绝的那几条照样有记录，不是静默截断。");
  }

  /**
   * 从一份方案生成执行任务（待执行）。
   *
   * <p>🔴 任务**只包含真的有预算变更的活动**：没有变更就不该占一条任务明细，
   * 否则「执行了 200 条」会淹没「真正动了 3 条」。
   */
  public AdBudgetTaskDetail createTask(CreateBudgetTaskRequest request) {
    Record planRow =
        sql.fetchOne(
            "select id, pool_id, rule_id, currency, status from ad_budget_plan where tenant_id = "
                + TENANT
                + " and id = ?",
            request.planId());
    if (planRow == null) {
      throw notFound("分配方案");
    }
    List<AdBudgetPlanLineRow> lines = planLines(request.planId());
    List<AdBudgetPlanLineRow> changing =
        lines.stream().filter(l -> l.budgetDelta().signum() != 0).toList();
    if (changing.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "这份方案没有任何预算变更，没有可执行的任务");
    }
    if (changing.size() > MAX_CAMPAIGNS_PER_TASK) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "一次执行任务最多 " + MAX_CAMPAIGNS_PER_TASK + " 条活动，本次有 " + changing.size() + " 条");
    }

    UUID taskId = Ids.next();
    sql.execute(
        "insert into ad_budget_task (tenant_id,id,plan_id,task_type,status,currency,requested_by,"
            + " reason,note,created_at,updated_at) values (?,?,?,'APPLY_PLAN','PENDING',?,?,?,?,now(),now())",
        currentTenant(),
        taskId,
        request.planId(),
        planRow.get("currency", String.class),
        currentActor(),
        text(request.reason()),
        "按分配方案调整 " + changing.size() + " 条活动的日预算");

    for (AdBudgetPlanLineRow line : changing) {
      sql.execute(
          "insert into ad_budget_task_line (tenant_id,id,task_id,campaign_id,campaign_name,shop_code,"
              + " before_budget,target_budget,applied_budget,budget_delta,result,message,created_at)"
              + " values (?,?,?,?,?,?,?,?,null,?,'PENDING','',now())",
          currentTenant(),
          Ids.next(),
          taskId,
          line.campaignId(),
          line.campaignName(),
          line.shopCode(),
          line.currentBudget(),
          line.allocatedBudget(),
          line.budgetDelta());
    }

    return task(taskId.toString());
  }

  /**
   * 执行任务：逐条落预算，逐条留痕。
   *
   * <p>🔴 三条纪律：
   *
   * <ol>
   *   <li><b>走 14.1.1.1 的写入口</b>（{@link AdCampaignService#bulkOperation}）：
   *       {@code ad_campaign} 是 14.1.1.1 的事实表，本模块不另开写路径。
   *   <li><b>护栏逐条判定</b>：单次上限 / 单活动预算上限 / 总额上限，超限的**逐条拒绝**并留痕，
   *       **绝不静默截断**（截断会让人以为「按预期工作」）。
   *   <li><b>本地生效 ≠ 平台生效</b>：上游会把改动标成「待回写平台」，本模块如实透传。
   * </ol>
   */
  public AdBudgetTaskDetail applyTask(String id) {
    UUID taskId = parseId(id);
    Record taskRow =
        sql.fetchOne(
            "select id, plan_id, status, currency, reason from ad_budget_task where tenant_id = "
                + TENANT
                + " and id = ?",
            taskId);
    if (taskRow == null) {
      throw notFound("执行任务");
    }
    String status = taskRow.get("status", String.class);
    if (!"PENDING".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "执行任务当前状态是 " + status + "，只有待执行的任务可以执行");
    }

    List<Record> lines =
        sql.fetch(
            "select id, campaign_id, campaign_name, before_budget, target_budget, budget_delta"
                + " from ad_budget_task_line where tenant_id = "
                + TENANT
                + " and task_id = ? order by created_at, id",
            taskId);

    BigDecimal appliedTotal = BigDecimal.ZERO;
    int applied = 0;
    int rejected = 0;
    int skipped = 0;
    OffsetDateTime now = OffsetDateTime.now();
    String reason = text(taskRow.get("reason", String.class));

    for (Record line : lines) {
      UUID lineId = line.get("id", UUID.class);
      UUID campaignId = line.get("campaign_id", UUID.class);
      String campaignName = line.get("campaign_name", String.class);
      BigDecimal target = line.get("target_budget", BigDecimal.class);
      BigDecimal before = line.get("before_budget", BigDecimal.class);
      BigDecimal delta = line.get("budget_delta", BigDecimal.class);

      if (target.compareTo(MAX_CAMPAIGN_BUDGET) > 0) {
        rejected++;
        updateTaskLine(
            lineId, "REJECTED", null, "目标预算超过单活动预算上限 " + MAX_CAMPAIGN_BUDGET);
        continue;
      }
      if (delta.abs().compareTo(MAX_SINGLE_BUDGET_DELTA) > 0) {
        rejected++;
        updateTaskLine(
            lineId,
            "REJECTED",
            null,
            "单次调整额 " + delta.abs() + " 超过单活动上限 " + MAX_SINGLE_BUDGET_DELTA);
        continue;
      }
      if (appliedTotal.add(delta.abs()).compareTo(MAX_TOTAL_BUDGET_DELTA) > 0) {
        rejected++;
        updateTaskLine(
            lineId,
            "REJECTED",
            null,
            "本次执行任务的总预算调整上限 "
                + MAX_TOTAL_BUDGET_DELTA
                + " 已用尽（已用 "
                + appliedTotal
                + "）");
        continue;
      }

      try {
        AdCampaignModels.BulkOperationResult result =
            campaigns.bulkOperation(
                new AdCampaignModels.BulkOperationRequest(
                    List.of(campaignId),
                    "SET_BUDGET",
                    target,
                    null,
                    reason.isEmpty() ? "预算分配执行任务 " + taskId : reason));
        AdCampaignModels.BulkOperationItem item = result.items().get(0);
        if ("APPLIED".equals(item.result())) {
          applied++;
          appliedTotal = appliedTotal.add(delta.abs());
          updateTaskLine(lineId, "APPLIED", item.afterBudget(), item.message());
        } else if ("SKIPPED".equals(item.result())) {
          skipped++;
          updateTaskLine(lineId, "SKIPPED", item.afterBudget(), item.message());
        } else {
          rejected++;
          updateTaskLine(lineId, "REJECTED", null, item.message());
        }
      } catch (ResponseStatusException e) {
        // 🔴 一条失败不影响其余条目 —— 批量执行的「批量」指一次提交多条，不是全成功或全失败。
        rejected++;
        updateTaskLine(lineId, "REJECTED", null, e.getReason() == null ? "执行被拒绝" : e.getReason());
      }
    }

    String taskStatus =
        applied == 0 && rejected > 0
            ? "FAILED"
            : (rejected == 0 && skipped == 0 ? "APPLIED" : "PARTIALLY_APPLIED");
    sql.execute(
        "update ad_budget_task set status = ?, applied_at = ?::timestamptz, updated_at = now()"
            + " where tenant_id = "
            + TENANT
            + " and id = ?",
        taskStatus,
        now,
        taskId);

    // 方案状态：执行过就标 APPLIED（方案是冻结快照，不回写明细）
    sql.execute(
        "update ad_budget_plan set status = 'APPLIED', updated_at = now()"
            + " where tenant_id = "
            + TENANT
            + " and id = ? and status <> 'SUPERSEDED'",
        taskRow.get("plan_id", UUID.class));

    return task(taskId.toString());
  }

  private void updateTaskLine(UUID lineId, String result, BigDecimal applied, String message) {
    sql.execute(
        "update ad_budget_task_line set result = ?, applied_budget = ?, message = ?"
            + " where tenant_id = "
            + TENANT
            + " and id = ?",
        result,
        applied,
        message == null ? "" : message,
        lineId);
  }

  // ==================================================================
  // 小工具
  // ==================================================================

  /** LIKE 里的通配符要转义，否则用户搜 "50%" 会匹配到一切。 */
  private static String escapeLike(String raw) {
    return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static Object[] concat(List<Object> binds, Object... tail) {
    Object[] all = new Object[binds.size() + tail.length];
    for (int i = 0; i < binds.size(); i++) {
      all[i] = binds.get(i);
    }
    System.arraycopy(tail, 0, all, binds.size(), tail.length);
    return all;
  }
}
