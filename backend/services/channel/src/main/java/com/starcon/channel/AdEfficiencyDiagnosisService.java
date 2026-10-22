package com.starcon.channel;

import static com.starcon.channel.AdEfficiencyDiagnosisModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
 * 14.3.2.2 效率诊断。
 *
 * <p>蓝图：「诊断投放效率问题：高花费零转化、ACOS 长期超标、预算受限、竞价过低曝光不足、创意衰退，
 * 给出可执行建议与一键调整。输入：14.3 报表指标；诊断规则。输出：诊断清单 + 建议动作。
 * 关系：上游 = 14.3.1；下游 = 14.2.1.1、14.1.3.1。<b>诊断要给动作而不是只报警</b>，否则用户看完还是要自己想怎么办。」
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>诊断投放效率问题</b> → {@link #run(RunDiagnosisRequest)}：五种判定口径
 *       （高花费零转化 / ACOS 长期超标 / 预算受限 / 竞价过低曝光不足 / 创意衰退），
 *       全部**从已交付的事实表算**（14.1.1.1 的 {@code ad_campaign} +
 *       {@code ad_campaign_daily_metric}），**不引用 14.3.1.1 报表的任何代码** ——
 *       蓝图那条「上游 = 14.3.1」是**业务上下游**，不是代码依赖。
 *   <li><b>输入：诊断规则</b> → {@code ad_diagnosis_rule}：阈值是租户可配的**输入**，
 *       规则本身由 {@link #initDefaultRules()} 显式建出来（不是写死的常量数组）。
 *   <li><b>输出：诊断清单 + 建议动作</b> → {@code ad_diagnosis} + {@code ad_diagnosis_action}，
 *       两者都带**观察量快照**，任何人都能复算。
 *   <li><b>一键调整</b> → {@link #applyAction(String, ApplyActionRequest)}：预算 / 暂停走
 *       <b>14.1.1.1 已交付的写路径</b>（{@code AdCampaignService.bulkOperation}），
 *       因此护栏（单次 / 总额 / 单活动上限）、审计（{@code ad_campaign_operation_log}）、
 *       幂等（已是目标状态 ⇒ SKIPPED）**全部复用**，本模块**不另写一套绕过护栏的写路径**。
 * </ul>
 *
 * <p>🔴 三处「不许假装」：
 *
 * <ol>
 *   <li><b>创意衰退</b>：已交付上游里没有创意数据（14.1.1.2 是同批并行车道）⇒
 *       对应规则 {@code data_source_ready=false}，引擎**不产出任何结论**，
 *       运行记录里写明「数据源未就绪」。不用随机数或假曲线假装有创意数据。
 *   <li><b>竞价调整</b>：下游 14.2.1.1 竞价管理未交付 ⇒ 对应动作
 *       {@code actionMode=UNAVAILABLE} + {@code ownerModule} + {@code impact}，
 *       界面上显示「竞价调整待 14.2.1.1 交付后开放」。**不跳过 14.2.1.1 直接写平台。**
 *   <li><b>比率不可比</b>：销售额为 0 时 ACOS 是**不可比**（null），不是 0 ——
 *       拿 0 冒充会让「烧了钱没转化」看起来像「效率极高」。
 * </ol>
 */
@Service
public class AdEfficiencyDiagnosisService {

  private static final String TENANT =
      "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 判定口径全集（职责句点名的五种问题）。 */
  static final List<String> RULE_TYPES =
      List.of(
          "HIGH_SPEND_NO_CONVERSION",
          "ACOS_OVER_TARGET",
          "BUDGET_CAPPED",
          "LOW_DELIVERY",
          "CREATIVE_DECAY");

  static final List<String> SEVERITIES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW");

  static final List<String> ACTION_TYPES =
      List.of(
          "PAUSE_CAMPAIGN",
          "INCREASE_BUDGET",
          "DECREASE_BUDGET",
          "INCREASE_BID",
          "OPTIMIZE_KEYWORDS",
          "NONE");

  static final List<String> ACTION_MODES = List.of("WRITE", "NAVIGATE", "UNAVAILABLE");

  static final List<String> OUTCOMES =
      List.of("NOT_APPLIED", "DRY_RUN", "APPLIED", "REJECTED", "SKIPPED", "UNAVAILABLE");

  static final List<String> DIAGNOSIS_STATUSES = List.of("OPEN", "ACTIONED", "DISMISSED", "STALE");

  /** 🔴 14.2.1.1 竞价管理未交付 ⇒ 竞价类动作的责任模块口径。 */
  static final String BID_OWNER_MODULE = "14.2.1.1 竞价管理";

  /** 🔴 14.1.3.1 关键词管理**已交付** ⇒ 关键词层优化是「去那边处理」，不是「不可用」。 */
  static final String KEYWORD_TARGET_MODULE = "14.1.3.1 关键词管理";
  static final String KEYWORD_TARGET_PATH = "/ads/keywords";

  static final String METRIC_DEFINITION_NOTE =
      "ACOS = 消耗 / 销售额，ROAS = 销售额 / 消耗，CTR = 点击 / 曝光；**分母为 0 时一律不可比（空值）**，"
          + "不是 0 —— 销售额为 0 的活动「ACOS = 0」和「ACOS 算不出来」是相反的两件事。"
          + "所有比率都是**先汇总再相除**（Σ消耗 / Σ销售额），不是各天比率的平均。";

  static final String RULE_DEFINITION_NOTE =
      "① 高花费零转化：窗口内 Σ花费 ≥ 金额门槛 且 Σ订单 = 0（且 Σ销售额 = 0）。"
          + "② ACOS 长期超标：Σ销售额 > 0（ACOS 可比）且 ACOS = Σ花费 / Σ销售额 > 目标 ACOS，"
          + "且窗口内超标天数 ≥ 下限（「长期」不能只看一天）。"
          + "③ 预算受限：窗口内「把预算花满」的天数 ≥ 下限（花满 = 当日花费 ≥ 日预算 × 预算利用率下限）。"
          + "④ 曝光不足：日均曝光 / 同平台在投活动中位数 < 曝光相对下限，且**不是**预算受限"
          + "（预算是瓶颈时由 ③ 负责，两条不重复报）。"
          + "⑤ 创意衰退：**数据源未就绪**（创意数据不存在）⇒ 不产出任何结论，"
          + "只用能力声明说明差在哪，不用假数据凑。";

  static final String ACTION_NOTE =
      "🔴 诊断要给动作而不是只报警：每条结论都带建议动作与「本来会变成多少」。"
          + "预算类动作（提高 / 降低预算、暂停）**现在就能做** —— 走 14.1.1.1 已交付的写路径，"
          + "带单次 / 总额 / 单活动预算上限、审计与幂等；"
          + "竞价类动作**现在不能做**（14.2.1.1 竞价管理未交付）⇒ 显式标为不可用并写明责任模块，"
          + "**不跳过 14.2.1.1 直接写平台**；"
          + "关键词层优化**已经能做**（14.1.3.1 关键词管理已交付）⇒ 去那个模块处理。";

  static final String GUARDRAIL_NOTE =
      "🔴 一键调整的四道闸：①**上限** —— 单次 / 总额 / 单活动预算上限由 14.1.1.1 的写路径强制执行，"
          + "越限**拒绝并留痕**，绝不静默截断（截断会让人以为按预期工作了）；"
          + "②**试运行** —— 规则自带 dry_run 与本次请求的 dry_run **取更严的那个**："
          + "请求侧的 dry_run **默认开**（点一下只算出「本来会变成多少」，不真的调），"
          + "规则侧的 dry_run 是「这条规则只出建议、不许落地」的开关 —— "
          + "手工新建的规则**默认开**它（阈值还没验证过，不该动钱），"
          + "而**初始化出来的默认规则是关的**（本模块**没有自动执行路径**，每一步都是人点的，"
          + "护栏由请求侧的默认试运行 + 上限 + 审计 + 幂等承担）；"
          + "结论 DRY_RUN 与 APPLIED 分得开，事后能说清「上周那条到底执行了没有」；"
          + "③**幂等** —— 同一条建议动作重复提交 ⇒ SKIPPED，不会把同一笔调整做两遍；"
          + "④**审计** —— 生效与被拒都写进 14.1.1.1 的操作审计。";

  static final String UPSTREAM_NOTE =
      "上游 14.3.1 活动报表与本批并行开发，本模块**不引用它的任何代码**："
          + "诊断指标全部从已交付的 14.1.1.1 事实表（ad_campaign + ad_campaign_daily_metric）算，"
          + "因此本模块可以独立成立。诊断窗口的末端取**本租户最新有数据的自然日**（不是墙钟），"
          + "所以同一份数据重复诊断结论稳定可比。";

  static final String BOUNDARY_NOTE =
      "下游 = 14.2.1.1 竞价管理（**未交付**）与 14.1.3.1 关键词管理（**已交付**）。"
          + "本模块**不实现**竞价策略引擎、动态竞价、目标 ACOS 反推竞价 —— 那是 14.2.1.1 的职责；"
          + "本模块**不实现**平台侧真实下发（平台回写队列未交付）⇒ 预算 / 暂停只落本地并标记「待回写平台」。";

  private final DSLContext sql;

  /** 🔴 复用 14.1.1.1 已交付的写路径（护栏 + 审计 + 幂等），不另写一套。 */
  private final AdCampaignService campaigns;

  public AdEfficiencyDiagnosisService(DSLContext sql, AdCampaignService campaigns) {
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

  private UUID currentTenant() {
    String raw = one(String.class, "select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(raw);
  }

  private UUID currentActor() {
    String raw = one(String.class, "select nullif(current_setting('app.actor',true),'')");
    return raw == null || raw.isBlank() ? null : UUID.fromString(raw);
  }

  /** 🔴 跨租户与不存在返回**逐字相同**的 404（照抄 14.1.1.1 / 14.2.2.2 同款口径）。 */
  private ResponseStatusException notFound(String what) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what + "不存在");
  }

  private static UUID parseId(String id) {
    try {
      return UUID.fromString(text(id));
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id 不是合法 UUID");
    }
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  /** 🔴 比率：分母为 0 ⇒ null（不可比），不是 0。 */
  private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
    if (numerator == null || denominator == null || denominator.signum() == 0) return null;
    return numerator.divide(denominator, 6, RoundingMode.HALF_UP);
  }

  private static BigDecimal money(BigDecimal value) {
    return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
  }

  /** 🔴 可空整数的安全读取：数据库里允许为空的列（未使用的阈值）不能直接拆箱。 */
  private static int intOr(Record r, String field, int fallback) {
    Integer value = r.get(field, Integer.class);
    return value == null ? fallback : value;
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  public AdDiagnosisCapabilities capabilities() {
    return new AdDiagnosisCapabilities(
        List.of(
            new AdDiagnosisCapabilityDimension(
                "diagnosis_rule",
                "诊断规则（阈值可配：观察窗口、金额 / 比率门槛、建议动作与幅度）",
                true,
                "ad_diagnosis_rule（V280）",
                "",
                "没有阈值的规则永远判不出东西：阈值是该规则类型的必填列，DB 层还有延迟约束触发器兜底"),
            new AdDiagnosisCapabilityDimension(
                "diagnosis_run",
                "诊断运行（跑一次、扫了多少活动、产出多少结论、逐条规则的评估结论）",
                true,
                "ad_diagnosis_run + ad_diagnosis_run_rule（V280）",
                "",
                "「没产出结论」与「没跑」是两件事：被跳过的规则（数据源未就绪）也留痕并写明原因"),
            new AdDiagnosisCapabilityDimension(
                "diagnosis_finding",
                "诊断结论（活动 × 规则）：观察量快照 + 判定依据 + 可执行建议",
                true,
                "ad_diagnosis（V280）",
                "",
                "没有观察量快照的结论只是一句断言，谁也复算不了"),
            new AdDiagnosisCapabilityDimension(
                "recommended_action",
                "建议动作 + 一键调整的执行结果（含被护栏拒绝的）",
                true,
                "ad_diagnosis_action（V280）",
                "",
                "被护栏拒绝的动作不留痕，就会以为调整按预期工作了"),
            new AdDiagnosisCapabilityDimension(
                "budget_action",
                "🔴 预算 / 暂停类动作（提高预算、降低预算、暂停活动）",
                true,
                "14.1.1.1 AdCampaignService.bulkOperation（V273 的 ad_campaign + ad_campaign_operation_log）",
                "",
                "本地已改、尚未回写平台（平台回写队列未交付）—— 界面必须标出，"
                    + "不能把「本地已暂停」当成「平台侧已暂停」"),
            new AdDiagnosisCapabilityDimension(
                "bid_action",
                "🔴 竞价类动作（提高竞价）",
                false,
                "—",
                BID_OWNER_MODULE,
                "「竞价过低曝光不足」这条诊断**能给结论**，但它的建议动作（提高竞价）**现在不能执行** —— "
                    + "14.2.1.1 竞价管理未交付。界面上写「竞价调整待 14.2.1.1 交付后开放」，"
                    + "本模块**不跳过 14.2.1.1 直接写平台**"),
            new AdDiagnosisCapabilityDimension(
                "keyword_action",
                "关键词层优化（去 14.1.3.1 关键词管理处理）",
                true,
                "14.1.3.1 关键词管理（V274 已交付）",
                "",
                "曝光不足的次要建议动作：竞价这条路没通，关键词层还有路可走"),
            new AdDiagnosisCapabilityDimension(
                "creative_decay",
                "🔴 创意衰退诊断",
                false,
                "—",
                "14.1.1.2 活动详情（创意子资源）",
                "已交付的上游里**没有创意数据** ⇒ 这条诊断规则不产出任何结论，"
                    + "运行记录里写明「数据源未就绪」。**不用假数据假装有创意数据**"),
            new AdDiagnosisCapabilityDimension(
                "report_upstream",
                "上游 14.3 报表（广告表现指标自动流入）",
                true,
                "14.1.1.1 ad_campaign + ad_campaign_daily_metric（V273 已交付）",
                "",
                "本模块直接读 14.1.1.1 的事实表算指标，**不等 14.3.1.1 报表交付**，"
                    + "因此与 14.3.1.1 零耦合、可以独立成立"),
            new AdDiagnosisCapabilityDimension(
                "platform_dispatch",
                "平台侧真实下发（把调整发到平台广告 API）",
                false,
                "—",
                "平台广告适配器（未交付）",
                "调整只落本地并标记「待回写平台」，平台侧的预算 / 状态在通道接上之前**不会真的变**")),
        RULE_TYPES,
        SEVERITIES,
        ACTION_TYPES,
        ACTION_MODES,
        OUTCOMES,
        DIAGNOSIS_STATUSES,
        List.of("NO_RULES", "NO_DATA", "NO_FINDINGS", "FILTERED_EMPTY"),
        METRIC_DEFINITION_NOTE,
        RULE_DEFINITION_NOTE,
        ACTION_NOTE,
        GUARDRAIL_NOTE,
        UPSTREAM_NOTE,
        BOUNDARY_NOTE);
  }

  // ==================================================================
  // 诊断规则（蓝图的输入之一：诊断规则）
  // ==================================================================

  private static final String RULE_COLUMNS =
      """
       select id, rule_code, rule_name, rule_type, severity, enabled,
              lookback_days, min_metric_days,
              threshold_amount, threshold_ratio, min_over_target_days,
              budget_utilization_ratio, delivery_ratio,
              action_type, action_percent, action_amount,
              secondary_action_type, secondary_action_percent, secondary_action_amount,
              data_source_ready, dry_run, note, version, created_at, updated_at
         from ad_diagnosis_rule
        where tenant_id = """
          + TENANT;

  private AdDiagnosisRuleRow ruleRow(Record r) {
    return new AdDiagnosisRuleRow(
        r.get("id", UUID.class),
        r.get("rule_code", String.class),
        r.get("rule_name", String.class),
        r.get("rule_type", String.class),
        r.get("severity", String.class),
        r.get("enabled", Boolean.class),
        r.get("lookback_days", Integer.class),
        r.get("min_metric_days", Integer.class),
        r.get("threshold_amount", BigDecimal.class),
        r.get("threshold_ratio", BigDecimal.class),
        intOr(r, "min_over_target_days", 0),
        r.get("budget_utilization_ratio", BigDecimal.class),
        r.get("delivery_ratio", BigDecimal.class),
        r.get("action_type", String.class),
        r.get("action_percent", BigDecimal.class),
        r.get("action_amount", BigDecimal.class),
        r.get("secondary_action_type", String.class),
        r.get("secondary_action_percent", BigDecimal.class),
        r.get("secondary_action_amount", BigDecimal.class),
        r.get("data_source_ready", Boolean.class),
        r.get("dry_run", Boolean.class),
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  public AdDiagnosisRulePage rules(String ruleType, Boolean enabled, int page, int size) {
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    // 🔴 片段以「 and 」开头：租户锚点已经在 RULE_COLUMNS 里，不要重复写 where
    StringBuilder where = new StringBuilder("");
    List<Object> binds = new ArrayList<>();
    String type = blankToNull(ruleType);
    if (type != null) {
      where.append(" and rule_type = ?");
      binds.add(type);
    }
    if (enabled != null) {
      where.append(" and enabled = ?");
      binds.add(enabled);
    }

    long total =
        count(
            "select count(*) from ad_diagnosis_rule where tenant_id = " + TENANT + where,
            binds.toArray());
    List<Object> listBinds = new ArrayList<>(binds);
    listBinds.add(safeSize);
    listBinds.add(safePage * safeSize);
    List<AdDiagnosisRuleRow> items =
        sql.fetch(
                RULE_COLUMNS + where + " order by rule_type, rule_code, id limit ? offset ?",
                listBinds.toArray())
            .stream()
            .map(this::ruleRow)
            .toList();

    long enabledCount =
        count(
            "select count(*) from ad_diagnosis_rule where tenant_id = "
                + TENANT
                + " and enabled = true");
    long notReadyCount =
        count(
            "select count(*) from ad_diagnosis_rule where tenant_id = "
                + TENANT
                + " and data_source_ready = false");

    return new AdDiagnosisRulePage(items, total, safePage, safeSize, enabledCount, notReadyCount);
  }

  public AdDiagnosisRuleRow rule(String id) {
    Record r = sql.fetchOne(RULE_COLUMNS + " and id = ?", parseId(id));
    if (r == null) throw notFound("诊断规则");
    return ruleRow(r);
  }

  /**
   * 登记 / 改一条诊断规则。
   *
   * <p>🔴 阈值是**必填**的（按规则类型分别必填）：DB 层有延迟约束触发器兜底，
   * 绕服务层直接写 SQL 同样被拒。
   */
  public AdDiagnosisRuleRow saveRule(SaveRuleRequest request) {
    String ruleCode = text(request.ruleCode());
    if (ruleCode.isEmpty()) throw badRequest("规则编码必填");
    String ruleType = text(request.ruleType()).toUpperCase(Locale.ROOT);
    if (!RULE_TYPES.contains(ruleType)) {
      throw badRequest("判定口径只能是 " + String.join(" / ", RULE_TYPES));
    }
    String severity = text(request.severity()).toUpperCase(Locale.ROOT);
    if (!SEVERITIES.contains(severity)) {
      throw badRequest("严重度只能是 " + String.join(" / ", SEVERITIES));
    }
    String actionType = text(request.actionType()).toUpperCase(Locale.ROOT);
    if (!ACTION_TYPES.contains(actionType)) {
      throw badRequest("建议动作只能是 " + String.join(" / ", ACTION_TYPES));
    }
    String secondary = blankToNull(request.secondaryActionType());
    if (secondary != null) {
      secondary = secondary.toUpperCase(Locale.ROOT);
      if (!ACTION_TYPES.contains(secondary) || "NONE".equals(secondary)) {
        throw badRequest("次要建议动作只能是 " + String.join(" / ", ACTION_TYPES));
      }
    }
    if (request.thresholdAmount() != null && request.thresholdAmount().signum() < 0) {
      throw badRequest("金额阈值不能为负");
    }
    if (request.thresholdRatio() != null && request.thresholdRatio().signum() < 0) {
      throw badRequest("比率阈值不能为负");
    }

    int lookbackDays = request.lookbackDays() == null ? 14 : request.lookbackDays();
    int minMetricDays = request.minMetricDays() == null ? 3 : request.minMetricDays();
    if (minMetricDays > lookbackDays) {
      throw badRequest("最少需要的天数不能大于观察窗口");
    }
    boolean enabled = request.enabled() == null || request.enabled();
    boolean dryRun = request.dryRun() == null || request.dryRun();
    // 🔴 创意衰退的数据源**没有** ⇒ 只能是 false；其余规则默认 true。
    boolean dataSourceReady =
        request.dataSourceReady() == null
            ? !"CREATIVE_DECAY".equals(ruleType)
            : request.dataSourceReady();

    UUID id = request.id();
    boolean isNew = false;
    if (id == null) {
      // 没给 id ⇒ 按 rule_code 幂等更新（同一租户内编码唯一）
      Record existing =
          sql.fetchOne(
              "select id from ad_diagnosis_rule where tenant_id = " + TENANT + " and rule_code = ?",
              ruleCode);
      if (existing != null) {
        id = existing.get("id", UUID.class);
      } else {
        id = Ids.next();
        isNew = true;
      }
    }

    Object[] binds = {
      ruleCode,
      text(request.ruleName()),
      ruleType,
      severity,
      enabled,
      lookbackDays,
      minMetricDays,
      request.thresholdAmount(),
      request.thresholdRatio(),
      request.minOverTargetDays(),
      request.budgetUtilizationRatio(),
      request.deliveryRatio(),
      actionType,
      request.actionPercent(),
      request.actionAmount(),
      secondary,
      request.secondaryActionPercent(),
      request.secondaryActionAmount(),
      dataSourceReady,
      dryRun,
      text(request.note())
    };

    if (isNew) {
      sql.execute(
          "insert into ad_diagnosis_rule (tenant_id,id,rule_code,rule_name,rule_type,severity,enabled,"
              + " lookback_days,min_metric_days,threshold_amount,threshold_ratio,min_over_target_days,"
              + " budget_utilization_ratio,delivery_ratio,action_type,action_percent,action_amount,"
              + " secondary_action_type,secondary_action_percent,secondary_action_amount,"
              + " data_source_ready,dry_run,note,version,created_at,updated_at)"
              + " values ("
              + TENANT
              + ",?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,now(),now())",
          binds);
    } else {
      List<Object> updateBinds = new ArrayList<>(java.util.Arrays.asList(binds));
      updateBinds.add(id);
      sql.execute(
          "update ad_diagnosis_rule set rule_code = ?, rule_name = ?, rule_type = ?, severity = ?,"
              + " enabled = ?, lookback_days = ?, min_metric_days = ?, threshold_amount = ?,"
              + " threshold_ratio = ?, min_over_target_days = ?, budget_utilization_ratio = ?,"
              + " delivery_ratio = ?, action_type = ?, action_percent = ?, action_amount = ?,"
              + " secondary_action_type = ?, secondary_action_percent = ?, secondary_action_amount = ?,"
              + " data_source_ready = ?, dry_run = ?, note = ?, version = version + 1, updated_at = now()"
              + " where tenant_id = "
              + TENANT
              + " and id = ?",
          updateBinds.toArray());
    }
    return rule(id.toString());
  }

  public AdDiagnosisRuleRow setRuleStatus(String id, String status) {
    UUID ruleId = parseId(id);
    String state = text(status).toUpperCase(Locale.ROOT);
    if (!List.of("ENABLED", "DISABLED").contains(state)) {
      throw badRequest("状态只能是 ENABLED / DISABLED");
    }
    int updated =
        sql.execute(
            "update ad_diagnosis_rule set enabled = ?, version = version + 1, updated_at = now()"
                + " where tenant_id = "
                + TENANT
                + " and id = ?",
            "ENABLED".equals(state),
            ruleId);
    if (updated == 0) throw notFound("诊断规则");
    return rule(id);
  }

  /**
   * 为本租户初始化**默认诊断规则**（幂等：已存在的不覆盖）。
   *
   * <p>🔴 默认阈值是**种子值**而不是真相源：它们落成真实的库行，租户可以逐条改。
   * 引擎只读库里的规则 —— 库里没有规则就没有诊断，**不会**拿硬编码常量当数据源。
   */
  public AdDiagnosisRuleDefaultsResult initDefaultRules() {
    List<DefaultRule> defaults =
        List.of(
            new DefaultRule(
                "HIGH-SPEND-NO-ORDER", "高花费零转化", "HIGH_SPEND_NO_CONVERSION", "CRITICAL",
                14, 3, new BigDecimal("100.0000"), null, null, null, null,
                "PAUSE_CAMPAIGN", null, null, null, null, null, true, false,
                "花费超过门槛却零订单：先暂停止损，确认商品与 listing 状态后再重开"),
            new DefaultRule(
                "ACOS-OVER-TARGET", "ACOS 长期超标", "ACOS_OVER_TARGET", "HIGH",
                14, 3, null, new BigDecimal("0.300000"), 3, null, null,
                "DECREASE_BUDGET", new BigDecimal("20.000000"), null, null, null, null, true, false,
                "ACOS 连续多天高于目标：先下调预算止血，别在效率没改善前加预算"),
            new DefaultRule(
                "BUDGET-CAPPED", "预算受限", "BUDGET_CAPPED", "MEDIUM",
                14, 3, null, null, null, new BigDecimal("0.950000"), null,
                "INCREASE_BUDGET", new BigDecimal("20.000000"), null, null, null, null, true, false,
                "预算被花满：跑不满潜力，适当提高日预算"),
            new DefaultRule(
                "LOW-DELIVERY", "竞价过低曝光不足", "LOW_DELIVERY", "MEDIUM",
                14, 3, null, null, null, null, new BigDecimal("0.500000"),
                "INCREASE_BID", null, null, "OPTIMIZE_KEYWORDS", null, null, true, false,
                "曝光显著低于同平台在投活动：常见原因是竞价过低（调整竞价待 14.2.1.1 交付后开放）"),
            new DefaultRule(
                "CREATIVE-DECAY", "创意衰退", "CREATIVE_DECAY", "LOW",
                30, 7, null, null, null, null, null,
                "NONE", null, null, null, null, null, false, true,
                "🔴 数据源未就绪：创意数据不存在（14.1.1.2 未交付），本规则不产出任何结论"));

    int created = 0;
    int existing = 0;
    for (DefaultRule d : defaults) {
      boolean present =
          count(
                  "select count(*) from ad_diagnosis_rule where tenant_id = "
                      + TENANT
                      + " and rule_code = ?",
                  d.code())
              > 0;
      if (present) {
        existing++;
        continue;
      }
      List<Object> binds =
          new ArrayList<>(
              java.util.Arrays.asList(
                  new Object[] {
                    Ids.next(),
                    d.code(),
                    d.name(),
                    d.ruleType(),
                    d.severity(),
                    d.lookbackDays(),
                    d.minMetricDays(),
                    d.thresholdAmount(),
                    d.thresholdRatio(),
                    d.minOverTargetDays(),
                    d.budgetUtilizationRatio(),
                    d.deliveryRatio(),
                    d.actionType(),
                    d.actionPercent(),
                    d.actionAmount(),
                    d.secondaryActionType(),
                    d.secondaryActionPercent(),
                    d.secondaryActionAmount(),
                    d.dataSourceReady(),
                    d.dryRun(),
                    d.note()
                  }));
      sql.execute(
          "insert into ad_diagnosis_rule (tenant_id,id,rule_code,rule_name,rule_type,severity,enabled,"
              + " lookback_days,min_metric_days,threshold_amount,threshold_ratio,min_over_target_days,"
              + " budget_utilization_ratio,delivery_ratio,action_type,action_percent,action_amount,"
              + " secondary_action_type,secondary_action_percent,secondary_action_amount,"
              + " data_source_ready,dry_run,note,version,created_at,updated_at)"
              + " values ("
              + TENANT
              + ",?,?,?,?,?,true,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,now(),now())",
          binds.toArray());
      created++;
    }
    List<AdDiagnosisRuleRow> ruleRows = rules(null, null, 0, 200).items();
    return new AdDiagnosisRuleDefaultsResult(
        created,
        existing,
        ruleRows,
        "默认规则是**种子值**，落成了真实库行，可逐条改阈值与动作；"
            + "已存在的规则不会被覆盖。创意衰退规则的数据源未就绪，因此不产出任何结论。");
  }

  /** 一条默认诊断规则的种子值（落成真实库行后可以逐条改）。 */
  private record DefaultRule(
      String code,
      String name,
      String ruleType,
      String severity,
      int lookbackDays,
      int minMetricDays,
      BigDecimal thresholdAmount,
      BigDecimal thresholdRatio,
      Integer minOverTargetDays,
      BigDecimal budgetUtilizationRatio,
      BigDecimal deliveryRatio,
      String actionType,
      BigDecimal actionPercent,
      BigDecimal actionAmount,
      String secondaryActionType,
      BigDecimal secondaryActionPercent,
      BigDecimal secondaryActionAmount,
      boolean dataSourceReady,
      boolean dryRun,
      String note) {}

  // ==================================================================
  // 诊断运行
  // ==================================================================

  /** 一条活动在窗口里的观察量（判定与快照都用它）。 */
  private record CampaignMetrics(
      UUID campaignId,
      UUID platformId,
      String platformCode,
      String shopCode,
      String campaignName,
      String status,
      String currency,
      BigDecimal dailyBudget,
      BigDecimal impressions,
      BigDecimal clicks,
      BigDecimal orders,
      BigDecimal spend,
      BigDecimal sales,
      int metricDays,
      int cappedDays,
      int overTargetDays) {}

  /** 一条规则的评估结论（评估了 / 跳过了 + 为什么）。 */
  private record RuleOutcome(
      UUID ruleId,
      String ruleCode,
      String ruleType,
      String severity,
      boolean evaluated,
      String skipReason,
      String noMatchReason,
      List<PendingDiagnosis> findings) {}

  /** 一条待落库的诊断结论。 */
  private record PendingDiagnosis(
      UUID ruleId,
      String ruleCode,
      String ruleType,
      String severity,
      CampaignMetrics m,
      BigDecimal acos,
      BigDecimal roas,
      BigDecimal ctr,
      BigDecimal budgetUtilization,
      BigDecimal deliveryRatio,
      String title,
      String evidence,
      String recommendation) {}

  public AdDiagnosisRunPage runs(int page, int size) {
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);
    long total = count("select count(*) from ad_diagnosis_run where tenant_id = " + TENANT);
    List<AdDiagnosisRunRow> items =
        sql.fetch(
                "select id, lookback_days, window_from, window_to, rule_count, enabled_rule_count,"
                    + " skipped_rule_count, scanned_campaign_count, diagnosis_count, data_as_of,"
                    + " status, message, started_at, finished_at, created_at"
                    + " from ad_diagnosis_run where tenant_id = "
                    + TENANT
                    + " order by created_at desc, id desc limit ? offset ?",
                safeSize,
                safePage * safeSize)
            .stream()
            .map(this::runRow)
            .toList();
    return new AdDiagnosisRunPage(items, total, safePage, safeSize);
  }

  private AdDiagnosisRunRow runRow(Record r) {
    return new AdDiagnosisRunRow(
        r.get("id", UUID.class),
        r.get("lookback_days", Integer.class),
        r.get("window_from", LocalDate.class),
        r.get("window_to", LocalDate.class),
        r.get("rule_count", Integer.class),
        r.get("enabled_rule_count", Integer.class),
        r.get("skipped_rule_count", Integer.class),
        r.get("scanned_campaign_count", Integer.class),
        r.get("diagnosis_count", Integer.class),
        r.get("data_as_of", OffsetDateTime.class),
        r.get("status", String.class),
        r.get("message", String.class),
        r.get("started_at", OffsetDateTime.class),
        r.get("finished_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class));
  }

  public AdDiagnosisRunDetail runDetail(String id) {
    UUID runId = parseId(id);
    Record r =
        sql.fetchOne(
            "select id, lookback_days, window_from, window_to, rule_count, enabled_rule_count,"
                + " skipped_rule_count, scanned_campaign_count, diagnosis_count, data_as_of,"
                + " status, message, started_at, finished_at, created_at"
                + " from ad_diagnosis_run where tenant_id = "
                + TENANT
                + " and id = ?",
            runId);
    if (r == null) throw notFound("诊断运行");
    return new AdDiagnosisRunDetail(
        runRow(r),
        runRules(runId),
        diagnoses(null, null, null, null, runId.toString(), 0, 200).items(),
        actions(null, null, null, null, runId.toString(), 0, 200).items(),
        "运行详情：逐条规则的评估结论（跳过的也在，写明差在哪）+ 本次产出的诊断结论与建议动作。");
  }

  private List<AdDiagnosisRunRuleRow> runRules(UUID runId) {
    return sql.fetch(
            "select id, run_id, rule_id, rule_code, rule_type, severity, evaluated, skip_reason,"
                + " no_match_reason, matched_campaign_count, created_at"
                + " from ad_diagnosis_run_rule where tenant_id = "
                + TENANT
                + " and run_id = ? order by rule_type, rule_code",
            runId)
        .stream()
        .map(
            x ->
                new AdDiagnosisRunRuleRow(
                    x.get("id", UUID.class),
                    x.get("run_id", UUID.class),
                    x.get("rule_id", UUID.class),
                    x.get("rule_code", String.class),
                    x.get("rule_type", String.class),
                    x.get("severity", String.class),
                    x.get("evaluated", Boolean.class),
                    x.get("skip_reason", String.class),
                    x.get("no_match_reason", String.class),
                    x.get("matched_campaign_count", Integer.class),
                    x.get("created_at", OffsetDateTime.class)))
        .toList();
  }

  /**
   * 跑一次诊断。
   *
   * <p>🔴 诊断窗口的末端取**本租户最新有数据的自然日**（不是墙钟）——
   * 这样同一份数据重复诊断结论稳定可比，测试也不依赖当天日期。
   */
  public AdDiagnosisRunDetail run(RunDiagnosisRequest request) {
    OffsetDateTime startedAt = OffsetDateTime.now();
    UUID runId = Ids.next();

    List<Record> ruleRecords =
        sql.fetch(
            RULE_COLUMNS + " and enabled = true order by rule_type, rule_code, id");

    // 🔴 锚点 = 本租户最新有数据的自然日
    LocalDate anchor =
        one(
            LocalDate.class,
            "select max(metric_date) from ad_campaign_daily_metric where tenant_id = " + TENANT);

    int effectiveLookback = request.lookbackDays() == null ? 0 : request.lookbackDays();
    if (effectiveLookback <= 0) {
      for (Record r : ruleRecords) {
        effectiveLookback = Math.max(effectiveLookback, r.get("lookback_days", Integer.class));
      }
    }
    if (effectiveLookback <= 0) effectiveLookback = 14;

    LocalDate windowTo = anchor;
    LocalDate windowFrom = anchor == null ? null : anchor.minusDays(effectiveLookback - 1L);

    OffsetDateTime dataAsOf =
        anchor == null
            ? null
            : one(
                OffsetDateTime.class,
                "select max(c.data_as_of) from ad_campaign c"
                    + " join ad_campaign_daily_metric dm on dm.tenant_id = c.tenant_id"
                    + " and dm.campaign_id = c.id"
                    + " where c.tenant_id = "
                    + TENANT
                    + " and dm.metric_date = ?::date",
                anchor);

    StringBuilder message = new StringBuilder();
    if (ruleRecords.isEmpty()) {
      message.append("本租户还没有启用任何诊断规则 —— 先在「诊断规则」里配置或初始化默认规则。");
    } else if (anchor == null) {
      message.append(
          "本租户还没有任何广告每日指标（14.1.1.1 活动列表尚未同步到数据）—— "
              + "没有事实就没有诊断结论，这不是「没有效率问题」。");
    }

    // ---- 逐条规则评估 ----
    List<RuleOutcome> outcomes = new ArrayList<>();
    for (Record r : ruleRecords) {
      outcomes.add(evaluateRule(r, request, anchor, effectiveLookback));
    }

    int skipped = (int) outcomes.stream().filter(o -> !o.evaluated()).count();
    int evaluatedCount = outcomes.size() - skipped;

    // ---- 落库：先写运行头，再写逐条规则评估，最后写结论与动作 ----
    sql.execute(
        "insert into ad_diagnosis_run (tenant_id,id,lookback_days,window_from,window_to,"
            + " rule_count,enabled_rule_count,skipped_rule_count,scanned_campaign_count,"
            + " diagnosis_count,data_as_of,status,message,started_at,finished_at,created_at)"
            + " values ("
            + TENANT
            + ",?,?,?,?,?,?,?,0,0,?::timestamptz,'SUCCESS',?,?::timestamptz,now(),now())",
        runId,
        effectiveLookback,
        windowFrom,
        windowTo,
        ruleRecords.size(),
        evaluatedCount,
        skipped,
        dataAsOf,
        message.toString(),
        startedAt);

    int scanned = 0;
    int findings = 0;
    for (RuleOutcome outcome : outcomes) {
      sql.execute(
          "insert into ad_diagnosis_run_rule (tenant_id,id,run_id,rule_id,rule_code,rule_type,"
              + " severity,evaluated,skip_reason,no_match_reason,matched_campaign_count,created_at)"
              + " values ("
              + TENANT
              + ",?,?,?,?,?,?,?,?,?,?,now())",
          Ids.next(),
          runId,
          outcome.ruleId(),
          outcome.ruleCode(),
          outcome.ruleType(),
          outcome.severity(),
          outcome.evaluated(),
          outcome.skipReason(),
          outcome.noMatchReason(),
          outcome.findings().size());

      for (PendingDiagnosis finding : outcome.findings()) {
        CampaignMetrics m = finding.m();
        UUID diagnosisId = Ids.next();
        sql.execute(
            "insert into ad_diagnosis (tenant_id,id,run_id,rule_id,campaign_id,rule_code,rule_type,"
                + " severity,window_from,window_to,metric_days,impressions,clicks,orders,spend,sales,"
                + " acos,roas,ctr,daily_budget,budget_utilization,delivery_ratio,over_target_days,"
                + " title,evidence,recommendation,status,created_at,updated_at)"
                + " values ("
                + TENANT
                + ",?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'OPEN',now(),now())",
            diagnosisId,
            runId,
            finding.ruleId(),
            m.campaignId(),
            finding.ruleCode(),
            finding.ruleType(),
            finding.severity(),
            m.campaignId() == null ? null : windowFrom,
            windowTo,
            m.metricDays(),
            m.impressions(),
            m.clicks(),
            m.orders(),
            m.spend(),
            m.sales(),
            finding.acos(),
            finding.roas(),
            finding.ctr(),
            m.dailyBudget(),
            finding.budgetUtilization(),
            finding.deliveryRatio(),
            m.overTargetDays(),
            finding.title(),
            finding.evidence(),
            finding.recommendation());
        findings++;

        createActions(diagnosisId, finding, outcome);
      }
      scanned = Math.max(scanned, outcome.findings().size());
    }

    sql.execute(
        "update ad_diagnosis_run set scanned_campaign_count = ?, diagnosis_count = ?,"
            + " finished_at = ?::timestamptz where tenant_id = "
            + TENANT
            + " and id = ?",
        scanned,
        findings,
        OffsetDateTime.now(),
        runId);

    return runDetail(runId.toString());
  }

  /**
   * 评估一条规则。
   *
   * <p>🔴 五种口径的判定全在这里；**创意衰退**直接判为「数据源未就绪 ⇒ 跳过」，
   * 不产出任何结论。
   */
  private RuleOutcome evaluateRule(
      Record rule, RunDiagnosisRequest request, LocalDate anchor, int effectiveLookback) {
    UUID ruleId = rule.get("id", UUID.class);
    String ruleCode = rule.get("rule_code", String.class);
    String ruleType = rule.get("rule_type", String.class);
    String severity = rule.get("severity", String.class);

    if (!rule.get("data_source_ready", Boolean.class)) {
      return new RuleOutcome(
          ruleId,
          ruleCode,
          ruleType,
          severity,
          false,
          "数据源未就绪："
              + ("CREATIVE_DECAY".equals(ruleType)
                  ? "创意数据不存在（14.1.1.2 活动详情的创意子资源未交付）"
                  : "该规则的数据源尚未就绪")
              + " ⇒ 本次不评估、也不产出任何结论。",
          "",
          List.of());
    }
    if (anchor == null) {
      return new RuleOutcome(
          ruleId,
          ruleCode,
          ruleType,
          severity,
          false,
          "没有广告每日指标（14.1.1.1 活动列表尚未同步到数据）⇒ 无从判定。",
          "",
          List.of());
    }

    // 规则自己的窗口；请求显式给了 lookbackDays ⇒ 本次统一用请求的窗口
    int lookback =
        request.lookbackDays() == null
            ? rule.get("lookback_days", Integer.class)
            : request.lookbackDays();
    int minMetricDays = rule.get("min_metric_days", Integer.class);
    LocalDate from = anchor.minusDays(lookback - 1L);
    LocalDate to = anchor;

    List<CampaignMetrics> metrics = campaignMetrics(ruleType, rule, from, to);
    if (metrics.isEmpty()) {
      return new RuleOutcome(
          ruleId,
          ruleCode,
          ruleType,
          severity,
          true,
          "",
          "窗口内没有任何在投活动有数据（活动必须是 ENABLED 且窗口内有每日指标）",
          List.of());
    }

    // 🔴 曝光不足要跟「同平台在投活动中位数」比 ⇒ 先算同平台中位数
    Map<UUID, BigDecimal> peerMedians =
        "LOW_DELIVERY".equals(ruleType) ? peerMedianImpressions(from, to) : Map.of();

    List<PendingDiagnosis> findings = new ArrayList<>();
    for (CampaignMetrics m : metrics) {
      PendingDiagnosis finding = judge(rule, m, minMetricDays, peerMedians);
      if (finding != null) findings.add(finding);
    }

    String noMatch = "";
    if (findings.isEmpty()) {
      noMatch = "评估了 " + metrics.size() + " 条在投活动，没有一条符合「" + ruleCode + "」的判据";
    }
    return new RuleOutcome(
        ruleId, ruleCode, ruleType, severity, true, "", noMatch, findings);
  }

  /** 窗口内每条**在投**活动的观察量（阈值相关的计数在 SQL 里一次算完）。 */
  private List<CampaignMetrics> campaignMetrics(
      String ruleType, Record rule, LocalDate from, LocalDate to) {
    StringBuilder aggregate =
        new StringBuilder(
            "select dm.campaign_id,"
                + " sum(dm.impressions) impressions, sum(dm.clicks) clicks, sum(dm.orders) orders,"
                + " sum(dm.spend) spend, sum(dm.sales) sales, count(*) metric_days");

    List<Object> binds = new ArrayList<>();
    if ("BUDGET_CAPPED".equals(ruleType)) {
      // 花满 = 当日花费 ≥ 日预算 × 预算利用率下限
      aggregate.append(
          ", count(*) filter (where c.daily_budget is not null and c.daily_budget > 0"
              + " and dm.spend >= c.daily_budget * ?) capped_days");
      binds.add(rule.get("budget_utilization_ratio", BigDecimal.class));
    } else if ("LOW_DELIVERY".equals(ruleType)) {
      // 曝光不足与预算受限**互斥**：这里用「花满预算」这个固定口径判断对方是不是瓶颈
      aggregate.append(
          ", count(*) filter (where c.daily_budget is not null and c.daily_budget > 0"
              + " and dm.spend >= c.daily_budget) capped_days");
    } else {
      aggregate.append(", 0 capped_days");
    }

    if ("ACOS_OVER_TARGET".equals(ruleType)) {
      aggregate.append(
          ", count(*) filter (where dm.sales > 0 and dm.spend > dm.sales * ?) over_target_days");
      binds.add(rule.get("threshold_ratio", BigDecimal.class));
    } else {
      aggregate.append(", 0 over_target_days");
    }

    aggregate.append(
        " from ad_campaign_daily_metric dm"
            + " join ad_campaign c on c.tenant_id = dm.tenant_id and c.id = dm.campaign_id"
            + " where dm.tenant_id = "
            + TENANT
            + " and dm.metric_date between ?::date and ?::date"
            + " group by dm.campaign_id");
    binds.add(from);
    binds.add(to);

    String sqlText =
        "select c.id, c.platform_id, pf.code platform_code, c.shop_code, c.campaign_name,"
            + " c.status, c.currency, c.daily_budget,"
            + " coalesce(m.impressions,0) impressions, coalesce(m.clicks,0) clicks,"
            + " coalesce(m.orders,0) orders, coalesce(m.spend,0) spend, coalesce(m.sales,0) sales,"
            + " coalesce(m.metric_days,0) metric_days, coalesce(m.capped_days,0) capped_days,"
            + " coalesce(m.over_target_days,0) over_target_days"
            + " from ad_campaign c"
            + " join platform pf on pf.id = c.platform_id"
            + " left join ("
            + aggregate
            + " ) m on m.campaign_id = c.id"
            + " where c.tenant_id = "
            + TENANT
            + " and c.status = 'ENABLED'"
            + " and coalesce(m.metric_days,0) > 0"
            + " order by c.id";

    return sql.fetch(sqlText, binds.toArray()).stream().map(this::metricsRow).toList();
  }

  private CampaignMetrics metricsRow(Record r) {
    return new CampaignMetrics(
        r.get("id", UUID.class),
        r.get("platform_id", UUID.class),
        r.get("platform_code", String.class),
        r.get("shop_code", String.class),
        r.get("campaign_name", String.class),
        r.get("status", String.class),
        r.get("currency", String.class),
        r.get("daily_budget", BigDecimal.class),
        r.get("impressions", BigDecimal.class),
        r.get("clicks", BigDecimal.class),
        r.get("orders", BigDecimal.class),
        r.get("spend", BigDecimal.class),
        r.get("sales", BigDecimal.class),
        r.get("metric_days", Integer.class),
        r.get("capped_days", Integer.class),
        r.get("over_target_days", Integer.class));
  }

  /**
   * 同平台**在投**活动的日均曝光中位数（曝光不足的对比基准）。
   *
   * <p>🔴 用 {@code percentile_disc}（离散中位数）而不是 {@code percentile_cont}：
   * 前者是**精确值**且全程 numeric，不引入浮点。
   */
  private Map<UUID, BigDecimal> peerMedianImpressions(LocalDate from, LocalDate to) {
    Map<UUID, BigDecimal> medians = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select c.platform_id,"
                + " percentile_disc(0.5) within group (order by t.avg_imp) median_imp"
                + " from (select dm.campaign_id, sum(dm.impressions) / count(*) avg_imp"
                + "         from ad_campaign_daily_metric dm"
                + "        where dm.tenant_id = "
                + TENANT
                + "          and dm.metric_date between ?::date and ?::date"
                + "        group by dm.campaign_id) t"
                + " join ad_campaign c on c.tenant_id = "
                + TENANT
                + " and c.id = t.campaign_id and c.status = 'ENABLED'"
                + " group by c.platform_id",
            from,
            to)) {
      medians.put(r.get("platform_id", UUID.class), r.get("median_imp", BigDecimal.class));
    }
    return medians;
  }

  /** 按规则类型判定一条活动；不命中返回 null。 */
  private PendingDiagnosis judge(
      Record rule,
      CampaignMetrics m,
      int minMetricDays,
      Map<UUID, BigDecimal> peerMedians) {
    String ruleType = rule.get("rule_type", String.class);
    UUID ruleId = rule.get("id", UUID.class);
    String ruleCode = rule.get("rule_code", String.class);
    String severity = rule.get("severity", String.class);

    BigDecimal spend = nz(m.spend());
    BigDecimal sales = nz(m.sales());
    BigDecimal orders = nz(m.orders());
    BigDecimal impressions = nz(m.impressions());
    BigDecimal clicks = nz(m.clicks());

    BigDecimal acos = ratio(spend, sales);
    BigDecimal roas = ratio(sales, spend);
    BigDecimal ctr = ratio(clicks, impressions);

    BigDecimal avgDailySpend =
        m.metricDays() > 0 ? spend.divide(BigDecimal.valueOf(m.metricDays()), 6, RoundingMode.HALF_UP) : null;
    BigDecimal avgDailyImpressions =
        m.metricDays() > 0
            ? impressions.divide(BigDecimal.valueOf(m.metricDays()), 6, RoundingMode.HALF_UP)
            : null;
    BigDecimal budgetUtilization =
        (avgDailySpend != null && m.dailyBudget() != null && m.dailyBudget().signum() > 0)
            ? avgDailySpend.divide(m.dailyBudget(), 6, RoundingMode.HALF_UP)
            : null;

    BigDecimal median = peerMedians.get(m.platformId());
    BigDecimal deliveryRatio =
        (avgDailyImpressions != null && median != null && median.signum() > 0)
            ? avgDailyImpressions.divide(median, 6, RoundingMode.HALF_UP)
            : null;

    String currency = m.currency() == null ? "" : m.currency();
    String days = m.metricDays() + " 天";

    switch (ruleType) {
      case "HIGH_SPEND_NO_CONVERSION" -> {
        BigDecimal threshold = rule.get("threshold_amount", BigDecimal.class);
        if (threshold == null) return null;
        if (m.metricDays() < minMetricDays) return null;
        if (spend.compareTo(threshold) < 0) return null;
        if (orders.signum() != 0 || sales.signum() != 0) return null;
        return new PendingDiagnosis(
            ruleId,
            ruleCode,
            ruleType,
            severity,
            m,
            acos,
            roas,
            ctr,
            budgetUtilization,
            deliveryRatio,
            "高花费零转化：" + m.campaignName(),
            "近 "
                + days
                + "共花费 "
                + spend
                + (currency.isEmpty() ? "" : " " + currency)
                + "，订单数 0、销售额 0；判定门槛为「花费 ≥ "
                + threshold
                + " 且零订单」，实际花费 "
                + spend
                + "。",
            "建议先暂停该活动止损：钱已经花出去但没有带来任何订单，"
                + "继续投放只会再烧一遍预算。暂停后请先确认商品 / listing 状态与出价是否可投，再重新开启。");
      }
      case "ACOS_OVER_TARGET" -> {
        BigDecimal target = rule.get("threshold_ratio", BigDecimal.class);
        Integer minOver = rule.get("min_over_target_days", Integer.class);
        if (target == null || minOver == null) return null;
        if (m.metricDays() < minMetricDays) return null;
        // 🔴 销售额为 0 ⇒ ACOS **不可比**（null），不是 0 ⇒ 不可比的就不判「超标」
        if (acos == null) return null;
        if (acos.compareTo(target) <= 0) return null;
        if (m.overTargetDays() < minOver) return null;
        BigDecimal pct = rule.get("action_percent", BigDecimal.class);
        BigDecimal drop = pct == null ? null : pct;
        return new PendingDiagnosis(
            ruleId,
            ruleCode,
            ruleType,
            severity,
            m,
            acos,
            roas,
            ctr,
            budgetUtilization,
            deliveryRatio,
            "ACOS 长期超标：" + m.campaignName(),
            "近 "
                + days
                + "ACOS = "
                + acos
                + "（消耗 "
                + spend
                + " / 销售额 "
                + sales
                + "），高于目标 "
                + target
                + "；窗口内 "
                + m.overTargetDays()
                + " 天超标（要求 ≥ "
                + minOver
                + " 天，所以这是「长期」而不是某一天）。",
            "建议把日预算下调"
                + (drop == null ? "一些" : " " + drop + "%")
                + "先止血：效率没改善之前加预算只会把亏损放大。"
                + "调完观察几天，ACOS 回到目标以内再考虑加回来。");
      }
      case "BUDGET_CAPPED" -> {
        BigDecimal utilizationThreshold = rule.get("budget_utilization_ratio", BigDecimal.class);
        if (utilizationThreshold == null) return null;
        if (m.dailyBudget() == null || m.dailyBudget().signum() <= 0) return null;
        if (m.metricDays() < minMetricDays) return null;
        if (m.cappedDays() < minMetricDays) return null;
        BigDecimal pct = rule.get("action_percent", BigDecimal.class);
        return new PendingDiagnosis(
            ruleId,
            ruleCode,
            ruleType,
            severity,
            m,
            acos,
            roas,
            ctr,
            budgetUtilization,
            deliveryRatio,
            "预算受限：" + m.campaignName(),
            "近 "
                + days
                + "日均花费 "
                + avgDailySpend
                + "，日预算 "
                + m.dailyBudget()
                + "，预算利用率 "
                + budgetUtilization
                + "；其中 "
                + m.cappedDays()
                + " 天把预算花满（利用率 ≥ "
                + utilizationThreshold
                + "）⇒ 预算是当前的瓶颈，跑不满这条活动的潜力。",
            "建议把日预算提高"
                + (pct == null ? "一些" : " " + pct + "%")
                + "，否则这条活动会一直被预算卡住。"
                + "提高预算不会降低 ACOS，但会让本来能跑出来的量跑出来。");
      }
      case "LOW_DELIVERY" -> {
        BigDecimal deliveryThreshold = rule.get("delivery_ratio", BigDecimal.class);
        if (deliveryThreshold == null) return null;
        if (m.dailyBudget() == null || m.dailyBudget().signum() <= 0) return null;
        if (m.metricDays() < minMetricDays) return null;
        // 🔴 与「预算受限」互斥：预算是瓶颈时由 BUDGET_CAPPED 负责，两条不重复报
        if (m.cappedDays() >= minMetricDays) return null;
        if (deliveryRatio == null) return null;
        if (deliveryRatio.compareTo(deliveryThreshold) >= 0) return null;
        return new PendingDiagnosis(
            ruleId,
            ruleCode,
            ruleType,
            severity,
            m,
            acos,
            roas,
            ctr,
            budgetUtilization,
            deliveryRatio,
            "竞价过低曝光不足：" + m.campaignName(),
            "近 "
                + days
                + "日均曝光 "
                + avgDailyImpressions
                + "，同平台在投活动的日均曝光中位数 "
                + median
                + "，曝光相对倍数 "
                + deliveryRatio
                + "（低于门槛 "
                + deliveryThreshold
                + "）；同期只有 "
                + m.cappedDays()
                + " 天把预算花满 ⇒ 不是预算受限，是拿不到量。",
            "曝光不足最常见的原因是竞价过低。建议提高竞价 —— 但**竞价调整待 14.2.1.1 交付后开放**，"
                + "本模块不跳过它直接写平台。在那之前可以先到 14.1.3.1 关键词管理里"
                + "优化投放词与匹配方式。");
      }
      default -> {
        // CREATIVE_DECAY 不会走到这里（数据源未就绪 ⇒ 早已跳过）
        return null;
      }
    }
  }

  /** 为一条诊断结论落建议动作（主 + 次）。 */
  private void createActions(UUID diagnosisId, PendingDiagnosis finding, RuleOutcome outcome) {
    Record rule =
        sql.fetchOne(
            "select action_type, action_percent, action_amount, secondary_action_type,"
                + " secondary_action_percent, secondary_action_amount"
                + " from ad_diagnosis_rule where tenant_id = "
                + TENANT
                + " and id = ?",
            finding.ruleId());
    if (rule == null) return;
    CampaignMetrics m = finding.m();
    insertAction(
        diagnosisId,
        finding,
        rule.get("action_type", String.class),
        rule.get("action_percent", BigDecimal.class),
        rule.get("action_amount", BigDecimal.class),
        m);
    String secondary = rule.get("secondary_action_type", String.class);
    if (secondary != null) {
      insertAction(
          diagnosisId,
          finding,
          secondary,
          rule.get("secondary_action_percent", BigDecimal.class),
          rule.get("secondary_action_amount", BigDecimal.class),
          m);
    }
  }

  private void insertAction(
      UUID diagnosisId,
      PendingDiagnosis finding,
      String actionType,
      BigDecimal percent,
      BigDecimal amount,
      CampaignMetrics m) {
    if (actionType == null || "NONE".equals(actionType)) return;

    String mode;
    boolean available;
    String ownerModule = "";
    String impact = "";
    String targetModule = "";
    String targetPath = "";

    switch (actionType) {
      case "INCREASE_BID" -> {
        mode = "UNAVAILABLE";
        available = false;
        ownerModule = BID_OWNER_MODULE;
        impact =
            "提高竞价需要 14.2.1.1 竞价管理（未交付）：本模块**不跳过它直接写平台**。"
                + "在该模块交付之前，这条建议只能人工处理。";
      }
      case "OPTIMIZE_KEYWORDS" -> {
        mode = "NAVIGATE";
        available = true;
        targetModule = KEYWORD_TARGET_MODULE;
        targetPath = KEYWORD_TARGET_PATH;
      }
      default -> {
        mode = "WRITE";
        available = true;
      }
    }

    // 🔴 预算类动作才带前后值；暂停 / 竞价 / 关键词动作没有「金额幅度」
    BigDecimal beforeValue = null;
    BigDecimal afterValue = null;
    if ("INCREASE_BUDGET".equals(actionType) || "DECREASE_BUDGET".equals(actionType)) {
      beforeValue = money(m.dailyBudget());
      afterValue = money(proposedBudget(actionType, m.dailyBudget(), percent, amount));
    }

    sql.execute(
        "insert into ad_diagnosis_action (tenant_id,id,diagnosis_id,rule_id,campaign_id,action_type,"
            + " action_mode,available,owner_module,impact,target_module,target_path,action_percent,"
            + " action_amount,before_value,after_value,outcome,message,guardrail_note,dry_run,"
            + " created_at,updated_at)"
            + " values ("
            + TENANT
            + ",?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,true,now(),now())",
        Ids.next(),
        diagnosisId,
        finding.ruleId(),
        m.campaignId(),
        actionType,
        mode,
        available,
        ownerModule,
        impact,
        targetModule,
        targetPath,
        percent,
        amount,
        beforeValue,
        afterValue,
        available ? "NOT_APPLIED" : "UNAVAILABLE",
        available ? "" : impact,
        "");
  }

  /** 建议调整后的日预算（比例或绝对额）。 */
  private static BigDecimal proposedBudget(
      String actionType, BigDecimal current, BigDecimal percent, BigDecimal amount) {
    if (current == null) return amount;
    if (amount != null) {
      return "DECREASE_BUDGET".equals(actionType)
          ? current.subtract(amount).max(BigDecimal.ZERO)
          : current.add(amount);
    }
    if (percent == null) return current;
    BigDecimal factor =
        "DECREASE_BUDGET".equals(actionType)
            ? BigDecimal.ONE.subtract(percent.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP))
            : BigDecimal.ONE.add(percent.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
    return current.multiply(factor).setScale(4, RoundingMode.HALF_UP);
  }

  // ==================================================================
  // 诊断清单
  // ==================================================================

  public AdDiagnosisPage diagnoses(
      String ruleType, String severity, String status, String campaignId, String runId, int page, int size) {
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    StringBuilder where = new StringBuilder(" where d.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    String type = blankToNull(ruleType);
    if (type != null) {
      where.append(" and d.rule_type = ?");
      binds.add(type);
    }
    String sev = blankToNull(severity);
    if (sev != null) {
      where.append(" and d.severity = ?");
      binds.add(sev);
    }
    String st = blankToNull(status);
    if (st != null) {
      where.append(" and d.status = ?");
      binds.add(st);
    }
    String campaign = blankToNull(campaignId);
    if (campaign != null) {
      where.append(" and d.campaign_id = ?::uuid");
      binds.add(campaign);
    }
    String run = blankToNull(runId);
    if (run != null) {
      where.append(" and d.run_id = ?::uuid");
      binds.add(run);
    }

    long total = count("select count(*) from ad_diagnosis d" + where, binds.toArray());
    List<Object> listBinds = new ArrayList<>(binds);
    listBinds.add(safeSize);
    listBinds.add(safePage * safeSize);
    List<AdDiagnosisRow> items =
        sql.fetch(
                "select d.id, d.run_id, d.rule_id, d.rule_code, d.rule_type, d.severity,"
                    + " d.campaign_id, c.campaign_name, pf.code platform_code, c.shop_code, d.status,"
                    + " d.window_from, d.window_to, d.metric_days, d.impressions, d.clicks, d.orders,"
                    + " d.spend, d.sales, d.acos, d.roas, d.ctr, d.daily_budget, d.budget_utilization,"
                    + " d.delivery_ratio, d.over_target_days, d.title, d.evidence, d.recommendation,"
                    + " d.created_at, d.updated_at"
                    + " from ad_diagnosis d"
                    + " join ad_campaign c on c.tenant_id = d.tenant_id and c.id = d.campaign_id"
                    + " join platform pf on pf.id = c.platform_id"
                    + where
                    + " order by d.created_at desc, d.id desc limit ? offset ?",
                listBinds.toArray())
            .stream()
            .map(this::diagnosisRow)
            .toList();

    long openCount =
        count(
            "select count(*) from ad_diagnosis where tenant_id = " + TENANT + " and status = 'OPEN'");
    long criticalCount =
        count(
            "select count(*) from ad_diagnosis where tenant_id = "
                + TENANT
                + " and severity = 'CRITICAL'");

    // 🔴 空态：把「没有规则」「没有数据」「跑过了但没发现」「被筛掉了」分开
    String emptyReason = null;
    String emptyNote = "";
    if (total == 0) {
      long ruleCount = count("select count(*) from ad_diagnosis_rule where tenant_id = " + TENANT);
      long metricRows =
          count("select count(*) from ad_campaign_daily_metric where tenant_id = " + TENANT);
      long allFindings = count("select count(*) from ad_diagnosis where tenant_id = " + TENANT);
      if (ruleCount == 0) {
        emptyReason = "NO_RULES";
        emptyNote = "本租户还没有诊断规则 —— 先在「诊断规则」里初始化默认规则或逐条配置，规则是诊断的输入。";
      } else if (metricRows == 0) {
        emptyReason = "NO_DATA";
        emptyNote =
            "本租户还没有广告每日指标（14.1.1.1 活动列表尚未同步到数据）—— "
                + "没有事实就没有诊断结论，这不是「投放效率没问题」。";
      } else if (allFindings > 0) {
        emptyReason = "FILTERED_EMPTY";
        emptyNote = "有诊断结论，但被当前的筛选条件全部排除了。放宽条件再试。";
      } else {
        emptyReason = "NO_FINDINGS";
        emptyNote = "已经跑过诊断且没有发现效率问题 —— 这是一个**结论**，不是「没数据」。";
      }
    }

    return new AdDiagnosisPage(
        items,
        total,
        safePage,
        safeSize,
        openCount,
        criticalCount,
        emptyReason,
        emptyNote,
        METRIC_DEFINITION_NOTE);
  }

  private AdDiagnosisRow diagnosisRow(Record r) {
    return new AdDiagnosisRow(
        r.get("id", UUID.class),
        r.get("run_id", UUID.class),
        r.get("rule_id", UUID.class),
        r.get("rule_code", String.class),
        r.get("rule_type", String.class),
        r.get("severity", String.class),
        r.get("campaign_id", UUID.class),
        r.get("campaign_name", String.class),
        r.get("platform_code", String.class),
        r.get("shop_code", String.class),
        r.get("status", String.class),
        r.get("window_from", LocalDate.class),
        r.get("window_to", LocalDate.class),
        r.get("metric_days", Integer.class),
        r.get("impressions", BigDecimal.class),
        r.get("clicks", BigDecimal.class),
        r.get("orders", BigDecimal.class),
        r.get("spend", BigDecimal.class),
        r.get("sales", BigDecimal.class),
        r.get("acos", BigDecimal.class),
        r.get("roas", BigDecimal.class),
        r.get("ctr", BigDecimal.class),
        r.get("daily_budget", BigDecimal.class),
        r.get("budget_utilization", BigDecimal.class),
        r.get("delivery_ratio", BigDecimal.class),
        r.get("over_target_days", Integer.class),
        r.get("title", String.class),
        r.get("evidence", String.class),
        r.get("recommendation", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  public AdDiagnosisDetail diagnosis(String id) {
    UUID diagnosisId = parseId(id);
    Record r =
        sql.fetchOne(
            "select d.id, d.run_id, d.rule_id, d.rule_code, d.rule_type, d.severity,"
                + " d.campaign_id, c.campaign_name, pf.code platform_code, c.shop_code, d.status,"
                + " d.window_from, d.window_to, d.metric_days, d.impressions, d.clicks, d.orders,"
                + " d.spend, d.sales, d.acos, d.roas, d.ctr, d.daily_budget, d.budget_utilization,"
                + " d.delivery_ratio, d.over_target_days, d.title, d.evidence, d.recommendation,"
                + " d.created_at, d.updated_at"
                + " from ad_diagnosis d"
                + " join ad_campaign c on c.tenant_id = d.tenant_id and c.id = d.campaign_id"
                + " join platform pf on pf.id = c.platform_id"
                + " where d.tenant_id = "
                + TENANT
                + " and d.id = ?",
            diagnosisId);
    if (r == null) throw notFound("诊断结论");
    return new AdDiagnosisDetail(
        diagnosisRow(r),
        actions(null, null, null, null, null, 0, 200).items().stream()
            .filter(a -> a.diagnosisId().equals(diagnosisId))
            .toList(),
        "诊断详情：结论 + 观察量快照（可复算）+ 建议动作（可执行的与明确不可用的都在）。");
  }

  // ==================================================================
  // 建议动作
  // ==================================================================

  public AdDiagnosisActionPage actions(
      String outcome, String actionMode, Boolean available, String diagnosisId, String runId, int page, int size) {
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    StringBuilder where = new StringBuilder(" where a.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    String out = blankToNull(outcome);
    if (out != null) {
      where.append(" and a.outcome = ?");
      binds.add(out);
    }
    String mode = blankToNull(actionMode);
    if (mode != null) {
      where.append(" and a.action_mode = ?");
      binds.add(mode);
    }
    if (available != null) {
      where.append(" and a.available = ?");
      binds.add(available);
    }
    String diagnosis = blankToNull(diagnosisId);
    if (diagnosis != null) {
      where.append(" and a.diagnosis_id = ?::uuid");
      binds.add(diagnosis);
    }
    String run = blankToNull(runId);
    if (run != null) {
      where.append(" and a.diagnosis_id in (select id from ad_diagnosis where tenant_id = "
          + TENANT + " and run_id = ?::uuid)");
      binds.add(run);
    }

    long total = count("select count(*) from ad_diagnosis_action a" + where, binds.toArray());
    List<Object> listBinds = new ArrayList<>(binds);
    listBinds.add(safeSize);
    listBinds.add(safePage * safeSize);
    List<AdDiagnosisActionRow> items =
        sql.fetch(
                "select a.id, a.diagnosis_id, a.rule_id, a.campaign_id, c.campaign_name, a.action_type,"
                    + " a.action_mode, a.available, a.owner_module, a.impact, a.target_module,"
                    + " a.target_path, a.action_percent, a.action_amount, a.before_value, a.after_value,"
                    + " a.outcome, a.message, a.guardrail_note, a.dry_run, a.requested_by, a.applied_at,"
                    + " a.created_at, a.updated_at"
                    + " from ad_diagnosis_action a"
                    + " join ad_campaign c on c.tenant_id = a.tenant_id and c.id = a.campaign_id"
                    + where
                    + " order by a.created_at desc, a.id desc limit ? offset ?",
                listBinds.toArray())
            .stream()
            .map(this::actionRow)
            .toList();

    long availableCount =
        count(
            "select count(*) from ad_diagnosis_action where tenant_id = " + TENANT + " and available = true");
    long appliedCount =
        count(
            "select count(*) from ad_diagnosis_action where tenant_id = " + TENANT + " and outcome = 'APPLIED'");
    long rejectedCount =
        count(
            "select count(*) from ad_diagnosis_action where tenant_id = " + TENANT + " and outcome = 'REJECTED'");
    long dryRunCount =
        count(
            "select count(*) from ad_diagnosis_action where tenant_id = " + TENANT + " and outcome = 'DRY_RUN'");
    long unavailableCount =
        count(
            "select count(*) from ad_diagnosis_action where tenant_id = " + TENANT + " and available = false");

    return new AdDiagnosisActionPage(
        items,
        total,
        safePage,
        safeSize,
        availableCount,
        appliedCount,
        rejectedCount,
        dryRunCount,
        unavailableCount);
  }

  private AdDiagnosisActionRow actionRow(Record r) {
    return new AdDiagnosisActionRow(
        r.get("id", UUID.class),
        r.get("diagnosis_id", UUID.class),
        r.get("rule_id", UUID.class),
        r.get("campaign_id", UUID.class),
        r.get("campaign_name", String.class),
        r.get("action_type", String.class),
        r.get("action_mode", String.class),
        r.get("available", Boolean.class),
        r.get("owner_module", String.class),
        r.get("impact", String.class),
        r.get("target_module", String.class),
        r.get("target_path", String.class),
        r.get("action_percent", BigDecimal.class),
        r.get("action_amount", BigDecimal.class),
        r.get("before_value", BigDecimal.class),
        r.get("after_value", BigDecimal.class),
        r.get("outcome", String.class),
        r.get("message", String.class),
        r.get("guardrail_note", String.class),
        r.get("dry_run", Boolean.class),
        r.get("requested_by", UUID.class),
        r.get("applied_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  public AdDiagnosisActionRow action(String id) {
    UUID actionId = parseId(id);
    Record r =
        sql.fetchOne(
            "select a.id, a.diagnosis_id, a.rule_id, a.campaign_id, c.campaign_name, a.action_type,"
                + " a.action_mode, a.available, a.owner_module, a.impact, a.target_module, a.target_path,"
                + " a.action_percent, a.action_amount, a.before_value, a.after_value, a.outcome,"
                + " a.message, a.guardrail_note, a.dry_run, a.requested_by, a.applied_at, a.created_at,"
                + " a.updated_at"
                + " from ad_diagnosis_action a"
                + " join ad_campaign c on c.tenant_id = a.tenant_id and c.id = a.campaign_id"
                + " where a.tenant_id = "
                + TENANT
                + " and a.id = ?",
            actionId);
    if (r == null) throw notFound("建议动作");
    return actionRow(r);
  }

  // ==================================================================
  // 🔴 一键调整
  // ==================================================================

  /**
   * 一键调整：执行一条建议动作。
   *
   * <p>🔴 四道闸：
   *
   * <ol>
   *   <li><b>可用性</b>：{@code actionMode != WRITE} 的动作**不能执行** ——
   *       竞价动作（14.2.1.1 未交付）显式拒绝并说明责任模块，**不跳过它直接写平台**；
   *       关键词动作（14.1.3.1 已交付）是「去那边处理」，本模块不代劳。
   *   <li><b>幂等</b>：已生效过的动作重复提交 ⇒ SKIPPED，不会把同一笔调整做两遍。
   *   <li><b>试运行</b>：规则自身的 {@code dry_run} 与请求的 {@code dry_run}
   *       <b>取更严的那个</b>；试运行只算出「本来会变成多少」，不产生实际调整。
   *   <li><b>上限与审计</b>：实际调整**走 14.1.1.1 已交付的写路径**
   *       （{@code AdCampaignService.bulkOperation}）⇒ 单次 / 总额 / 单活动预算上限、
   *       操作审计、「已是目标状态 ⇒ SKIPPED」全部复用，**不另写一套绕过护栏的写路径**。
   * </ol>
   */
  public AdDiagnosisApplyResult applyAction(String id, ApplyActionRequest request) {
    UUID actionId = parseId(id);
    Record row =
        sql.fetchOne(
            "select a.id, a.diagnosis_id, a.rule_id, a.campaign_id, a.action_type, a.action_mode,"
                + " a.available, a.owner_module, a.impact, a.action_percent, a.action_amount,"
                + " a.outcome, a.dry_run, c.daily_budget, c.status campaign_status,"
                + " c.currency, r.dry_run rule_dry_run"
                + " from ad_diagnosis_action a"
                + " join ad_campaign c on c.tenant_id = a.tenant_id and c.id = a.campaign_id"
                + " join ad_diagnosis_rule r on r.tenant_id = a.tenant_id and r.id = a.rule_id"
                + " where a.tenant_id = "
                + TENANT
                + " and a.id = ?",
            actionId);
    if (row == null) throw notFound("建议动作");

    String actionType = row.get("action_type", String.class);
    String mode = row.get("action_mode", String.class);
    boolean available = row.get("available", Boolean.class);
    UUID campaignId = row.get("campaign_id", UUID.class);
    UUID diagnosisId = row.get("diagnosis_id", UUID.class);

    // ---- 闸 ①：可用性 ----
    if (!available || !"WRITE".equals(mode)) {
      String message =
          "UNAVAILABLE".equals(mode)
              ? "该建议动作现在**不可用**："
                  + (row.get("owner_module", String.class))
                  + " 未交付。"
                  + (row.get("impact", String.class))
              : "该建议动作要在 "
                  + row.get("target_module", String.class)
                  + " 处理，本模块不代劳（去 "
                  + row.get("target_path", String.class)
                  + "）。";
      sql.execute(
          "update ad_diagnosis_action set outcome = ?, message = ?, updated_at = now()"
              + " where tenant_id = "
              + TENANT
              + " and id = ?",
          "UNAVAILABLE".equals(mode) ? "UNAVAILABLE" : "NOT_APPLIED",
          message,
          actionId);
      throw conflict(message);
    }

    // ---- 闸 ②：幂等 ----
    if ("APPLIED".equals(row.get("outcome", String.class))) {
      return new AdDiagnosisApplyResult(
          action(actionId.toString()),
          "SKIPPED",
          "该建议动作**已经执行过**（幂等）：重复提交不会再调一次。",
          "",
          "已生效的动作不会被重复执行 —— 否则同一笔调整会被做两遍。");
    }

    // ---- 闸 ③：试运行（取更严的那个）----
    boolean requestedDryRun = request == null || request.dryRun() == null || request.dryRun();
    boolean ruleDryRun = row.get("rule_dry_run", Boolean.class);
    boolean dryRun = requestedDryRun || ruleDryRun;

    String reason =
        request == null || request.reason() == null
            ? ""
            : text(request.reason());
    if (reason.isEmpty()) {
      reason = "14.3.2.2 效率诊断的一键调整（建议动作 " + actionId + "）";
    }

    BigDecimal currentBudget = row.get("daily_budget", BigDecimal.class);
    BigDecimal percent = row.get("action_percent", BigDecimal.class);
    BigDecimal amount = row.get("action_amount", BigDecimal.class);

    String operation;
    BigDecimal budget = null;
    BigDecimal delta = null;
    BigDecimal proposed = null;

    if ("PAUSE_CAMPAIGN".equals(actionType)) {
      operation = "PAUSE";
    } else if ("INCREASE_BUDGET".equals(actionType) || "DECREASE_BUDGET".equals(actionType)) {
      if (currentBudget == null) {
        String message =
            "该活动**没有日预算**（平台侧就是无预算），无法按建议幅度调整预算。";
        sql.execute(
            "update ad_diagnosis_action set outcome = 'REJECTED', message = ?, guardrail_note = ?,"
                + " updated_at = now() where tenant_id = "
                + TENANT
                + " and id = ?",
            message,
            "缺少日预算 ⇒ 无法换算调整幅度",
            actionId);
        return new AdDiagnosisApplyResult(
            action(actionId.toString()), "REJECTED", message, "缺少日预算 ⇒ 无法换算调整幅度", "");
      }
      proposed = proposedBudget(actionType, currentBudget, percent, amount);
      delta = proposed.subtract(currentBudget);
      operation = "ADJUST_BUDGET";
      budget = null;
    } else {
      String message = "动作类型 " + actionType + " 不在本模块的可执行范围内。";
      throw conflict(message);
    }

    // ---- 闸 ④：试运行只算不调 ----
    if (dryRun) {
      String message =
          "试运行：只算出「本来会变成多少」"
              + (proposed == null ? "" : "（预算会变成 " + proposed + "）")
              + "，**没有真的调整**。规则或本次请求任一开启试运行，就不产生实际调整。";
      sql.execute(
          "update ad_diagnosis_action set outcome = 'DRY_RUN', dry_run = true, before_value = ?,"
              + " after_value = ?, message = ?, requested_by = ?, updated_at = now()"
              + " where tenant_id = "
              + TENANT
              + " and id = ?",
          money(currentBudget),
          money(proposed),
          message,
          currentActor(),
          actionId);
      return new AdDiagnosisApplyResult(
          action(actionId.toString()), "DRY_RUN", message, "试运行：只算不调", "");
    }

    // ---- 实际执行：走 14.1.1.1 已交付的写路径（护栏 + 审计 + 幂等）----
    AdCampaignModels.BulkOperationResult result;
    try {
      result =
          campaigns.bulkOperation(
              new AdCampaignModels.BulkOperationRequest(
                  List.of(campaignId), operation, budget, delta, reason));
    } catch (org.springframework.web.server.ResponseStatusException refused) {
      // 🔴 写路径的**前置**护栏拒了（单次调整额超过上限这类）⇒ 翻译成本模块的结论并**留痕**。
      //   直接把 400 抛出去会让人以为「什么都没发生」，而「为什么这条没生效」就查不出来了。
      String message =
          refused.getReason() == null ? "写路径拒绝了这次调整" : refused.getReason();
      String guardrailNote = "被 14.1.1.1 的护栏拒绝：" + message;
      writeRejectedAudit(
          campaignId, operation, row.get("campaign_status", String.class), currentBudget, reason, message);
      sql.execute(
          "update ad_diagnosis_action set outcome = 'REJECTED', dry_run = false, before_value = ?,"
              + " after_value = ?, message = ?, guardrail_note = ?, requested_by = ?,"
              + " applied_at = ?::timestamptz,"
              + " updated_at = now() where tenant_id = "
              + TENANT
              + " and id = ?",
          money(currentBudget),
          money(proposed),
          message,
          guardrailNote,
          currentActor(),
          OffsetDateTime.now(),
          actionId);
      return new AdDiagnosisApplyResult(
          action(actionId.toString()), "REJECTED", message, guardrailNote, "");
    }

    AdCampaignModels.BulkOperationItem item =
        result.items().stream()
            .filter(i -> i.campaignId().equals(campaignId))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("写路径没有返回这一条的结果"));

    String outcome;
    String message;
    String guardrailNote = "";
    switch (item.result()) {
      case "APPLIED" -> {
        outcome = "APPLIED";
        message = item.message();
        guardrailNote = "已通过单次 / 总额 / 单活动预算上限与上下限保护（14.1.1.1 的写路径裁决）。";
      }
      case "REJECTED" -> {
        outcome = "REJECTED";
        message = item.message();
        guardrailNote = "被 14.1.1.1 的护栏拒绝：" + item.message();
      }
      case "SKIPPED" -> {
        outcome = "SKIPPED";
        message = item.message();
        guardrailNote = "无需执行：" + item.message();
      }
      default -> {
        outcome = "REJECTED";
        message = "未知的执行结果：" + item.result();
        guardrailNote = message;
      }
    }

    BigDecimal afterValue = item.afterBudget() == null ? null : money(item.afterBudget());
    sql.execute(
        "update ad_diagnosis_action set outcome = ?, dry_run = false, before_value = ?,"
            + " after_value = ?, message = ?, guardrail_note = ?, requested_by = ?,"
            + " applied_at = ?::timestamptz,"
            + " updated_at = now() where tenant_id = "
            + TENANT
            + " and id = ?",
        outcome,
        money(currentBudget),
        afterValue,
        message,
        guardrailNote,
        currentActor(),
        OffsetDateTime.now(),
        actionId);

    if ("APPLIED".equals(outcome)) {
      sql.execute(
          "update ad_diagnosis set status = 'ACTIONED', updated_at = now() where tenant_id = "
              + TENANT
              + " and id = ?",
          diagnosisId);
    }

    return new AdDiagnosisApplyResult(
        action(actionId.toString()),
        outcome,
        message,
        guardrailNote,
        "🔴 本次改动只落本地并标记「待回写平台」：平台回写队列尚未交付，"
            + "界面上会显式标出，避免把「本地已暂停」当成「平台侧已暂停」。");
  }

  /**
   * 🔴 被护栏拒绝的动作也要写进 14.1.1.1 的操作审计。
   *
   * <p>写路径的**前置**护栏（单次调整额超上限这类）是在进入逐条处理**之前**就拒的，
   * 审计里没有这一条 ⇒ 「为什么这条没生效」就查不出来。
   * 这里补一条 {@code result=REJECTED} 的审计，语义与 14.1.1.1 自己的
   * {@code logOperation} 一致：留痕，不改任何东西。
   */
  private void writeRejectedAudit(
      UUID campaignId,
      String operation,
      String beforeStatus,
      BigDecimal beforeBudget,
      String reason,
      String message) {
    UUID actor = currentActor();
    sql.execute(
        "insert into ad_campaign_operation_log (tenant_id,id,campaign_id,operation,before_status,"
            + " after_status,before_budget,after_budget,requested_by,reason,result,message,created_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,now())",
        currentTenant(),
        Ids.next(),
        campaignId,
        operation,
        beforeStatus == null ? "" : beforeStatus,
        beforeStatus == null ? "" : beforeStatus,
        beforeBudget,
        beforeBudget,
        actor == null ? currentTenant() : actor,
        reason,
        "REJECTED",
        message);
  }
}
