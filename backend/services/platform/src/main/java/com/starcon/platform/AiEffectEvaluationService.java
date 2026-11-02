package com.starcon.platform;

import static com.starcon.platform.AiEffectEvaluationModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 18.4.3.2「效果评估」的服务层。
 *
 * <p>蓝图原文：「<b>职责</b>：评估 AI 功能效果：翻译采纳率、预测准确度回测、助手答案有用性反馈，
 * 识别低价值功能并下线。<b>输入</b>：人工采纳/修改记录；预测回测结果；用户反馈。
 * <b>输出</b>：效果报表 + 改进建议。<b>关系</b>：上游 = 18.4 全部；下游 = 18.4 迭代。
 * <b>采纳率是 AI 功能唯一诚实的指标</b>——生成了但没人用的功能应下线。」
 *
 * <p>数据访问一律走 <b>jOOQ 显式 SQL</b>（AGENTS.md：不用 JPA 懒加载掩盖关键扣减逻辑）。
 * 租户上下文由 {@link AccessService#run} 绑定 —— 本类<b>不</b>读任何请求头里的租户。
 *
 * <p><b>八条纪律：</b>
 *
 * <ol>
 *   <li><b>归属只来自会话</b>：{@code tenant_id} 与 {@code membership_id} 都取自
 *       {@link AccessService.Access}；请求体里<b>没有</b>这三个字段。
 *   <li><b>采纳率的分母只有一个来源</b>：{@code platform_ai_usage_record}（18.4.3.1 的**只追加**
 *       用量台账）。本模块**只做分子** —— 拿本模块的反馈条数当分母算出来的是「反馈率」，
 *       不是「采纳率」。
 *   <li><b>翻译采纳是三态</b>：直接采用 / 修改后采用 / 拒绝。<b>前两者都算被采纳</b> ——
 *       只存一个布尔值会把「改了才用」误判成「没用」。
 *   <li><b>比率用十进制文本</b>，分母为 0 时是 {@code null}（不是 0、不是 Infinity）：
 *       「没有调用记录」与「调用了但没人用」是两件完全不同的事。
 *   <li><b>回测的实际值来自真实销量</b>（18.4.2.2 的 {@code platform_forecast_demand_history}），
 *       <b>绝不拿预测值自己当实际值</b>；该 SKU 一条销量都没有 ⇒ 如实记
 *       {@code NO_ACTUAL_SOURCE}，<b>不记 0</b>。
 *   <li><b>只读上游</b>：18.4.3.1 的用量表、18.4.2.2 的预测表，本模块<b>一个字都不写</b>
 *       （每张事实表只有一个写入所有者）。
 *   <li><b>只出建议，不下线</b>：真正下线涉及 18.4.3.1 的开关 / 配额设置 ——
 *       本仓<b>没有</b>按能力粒度的 AI 功能开关 ⇒ 本模块只输出**低价值功能清单 + 建议**，
 *       {@code offline_action} 字段如实写明「需人工执行」。
 *   <li><b>助手答案有用性反馈如实答不可用</b>：18.4.1.1 对话助手已熔断未交付 ⇒
 *       上游<b>不存在</b>助手答案与助手使用数据 ⇒ 本模块**只登记反馈的载体**，
 *       能力声明里逐条报出 {@code available=false} + 未接通原因 + 归属模块，
 *       <b>绝不伪造助手的使用/反馈数据</b>。
 * </ol>
 */
@Service
public class AiEffectEvaluationService {

  /** 数量与比率的标度（对应 {@code NUMERIC(19,6)} / {@code NUMERIC(9,6)}）。 */
  static final int SCALE = 6;

  /** 能力码：大写字母 / 数字 / 下划线。取值域是**开放的**（各 AI 能力自己回报）。 */
  private static final Pattern CAPABILITY = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

  /** 默认评估窗口：最近 30 天（与 18.4.3.1 的用量报表同一口径）。 */
  private static final int DEFAULT_WINDOW_DAYS = 30;

  /** 系统默认阈值（**库里没登记那一行时用这些**，并在响应里写明「未配置」）。 */
  private static final long DEFAULT_MIN_CALLS = 20L;

  private static final BigDecimal DEFAULT_LOW_ADOPTION_RATE = new BigDecimal("0.200000");
  private static final long DEFAULT_MIN_FEEDBACK_COUNT = 5L;
  private static final BigDecimal DEFAULT_LOW_USEFUL_RATE = new BigDecimal("0.500000");
  private static final BigDecimal DEFAULT_MAX_MAPE = new BigDecimal("0.300000");

  /** 改进建议的窗口（左右闭开）。 */
  private static final int DEFAULT_SUGGESTION_WINDOW_DAYS = 30;

  /** {@code relative_error} 列是 {@code numeric(9,6)} ⇒ 上限 999.999999，超出就存不下。 */
  private static final BigDecimal MAX_RELATIVE_ERROR = new BigDecimal("999.999999");

  private final DSLContext sql;

  public AiEffectEvaluationService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 工具

  private static void check(boolean condition, String message) {
    if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  /** 状态冲突（409）：不是「参数不对」，是「当前状态不允许」。 */
  private static void conflict(String message) {
    throw new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  /** 找不到（404）。 */
  private static void notFound(String message) {
    throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static String required(String value, String label, int max) {
    var trimmed = value == null ? "" : value.trim();
    check(!trimmed.isEmpty(), label + "不能为空");
    check(trimmed.length() <= max, label + "长度不能超过 " + max + " 个字符");
    return trimmed;
  }

  private static String optional(String value, String label, int max) {
    var trimmed = value == null ? "" : value.trim();
    check(trimmed.length() <= max, label + "长度不能超过 " + max + " 个字符");
    return trimmed;
  }

  /** 能力码：归一化成大写并校验字面量。 */
  private static String capabilityCode(String raw) {
    var text = required(raw, "能力码", 64).toUpperCase(Locale.ROOT);
    check(CAPABILITY.matcher(text).matches(), "能力码只能是大写字母、数字与下划线（如 TEXT_TRANSLATION）");
    return text;
  }

  /** 比率：十进制文本（0 ~ 1）⇒ BigDecimal。禁浮点：只接受字符串形态的十进制。 */
  private static BigDecimal rate(String raw, String label) {
    var text = raw == null ? "" : raw.trim();
    check(!text.isEmpty(), label + "不能为空");
    check(text.matches("\\d+(\\.\\d+)?"), label + "必须是不带符号的十进制数（0 ~ 1，如 0.2）");
    var value = new BigDecimal(text);
    check(value.compareTo(BigDecimal.ONE) <= 0, label + "必须在 0 ~ 1 之间");
    return value.setScale(SCALE, RoundingMode.HALF_UP);
  }

  /** 非负十进制文本（可为空）。 */
  private static BigDecimal nonNegativeDecimal(String raw, String label) {
    if (raw == null || raw.isBlank()) return null;
    var text = raw.trim();
    check(text.matches("\\d+(\\.\\d+)?"), label + "必须是不带符号的十进制数");
    return new BigDecimal(text);
  }

  /** 数量：十进制文本 ⇒ BigDecimal（比例 6 位）。 */
  private static BigDecimal quantity(String raw, String label) {
    var value = nonNegativeDecimal(raw, label);
    check(value != null, label + "不能为空");
    return value.setScale(SCALE, RoundingMode.HALF_UP);
  }

  /** 十进制文本（对外暴露的口径：全程字符串，前端不碰浮点）。 */
  private static String decimal(BigDecimal value) {
    if (value == null) return null;
    return value.setScale(SCALE, RoundingMode.HALF_UP).toPlainString();
  }

  /**
   * 比率 ⇒ 十进制文本。
   *
   * <p>🔴 <b>分母为 0 ⇒ 返回 {@code null}</b>：不是 0、不是 Infinity ——
   * 「没有调用记录」与「调用了但没人用」是两件完全不同的事。
   */
  private static String rateText(BigDecimal numerator, long denominator) {
    if (denominator <= 0) return null;
    return numerator.divide(BigDecimal.valueOf(denominator), SCALE, RoundingMode.HALF_UP).toPlainString();
  }

  /** 事务局部 GUC：租户由 AccessService 绑定，这里读的是**同一个**绑定。 */
  private UUID currentTenant() {
    var raw =
        sql.fetchOne("select current_setting('app.current_tenant', true) as t").get(0, String.class);
    check(raw != null && !raw.isBlank(), "缺少可信租户上下文");
    return UUID.fromString(raw);
  }

  /** DB 时钟：落库时间一律取它（见 V430 迁移第 2 节）。 */
  private OffsetDateTime dbNow() {
    return sql.fetchOne("select clock_timestamp() as t").get(0, OffsetDateTime.class);
  }

  /** 解析 ISO-8601 时刻；空串 / null 返回 {@code null}。 */
  private static OffsetDateTime parseInstant(String raw, String label) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return OffsetDateTime.parse(raw.trim());
    } catch (RuntimeException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, label + "必须是 ISO-8601 时刻（如 2026-11-02T00:00:00Z）");
    }
  }

  /** 把参数列表拼成 jOOQ 的可变参数数组。 */
  private static Object[] concat(List<Object> params, Object... more) {
    var all = new ArrayList<Object>(params);
    all.addAll(List.of(more));
    return all.toArray();
  }

  /** 评估窗口（左闭右开）；未指定时取最近 N 天。 */
  private OffsetDateTime[] window(String from, String to, int defaultDays) {
    var now = dbNow();
    var fromAt = parseInstant(from, "起始时刻");
    var toAt = parseInstant(to, "结束时刻");
    var start = fromAt == null ? now.minusDays(defaultDays) : fromAt;
    var end = toAt == null ? now : toAt;
    check(end.isAfter(start), "结束时刻必须晚于起始时刻");
    return new OffsetDateTime[] {start, end};
  }

  // ================================================================ 能力声明

  /**
   * 18.4.3.2 的能力声明：逐维说清「有没有、依据是什么、缺了归谁」。
   *
   * <p>🔴 <b>助手答案有用性反馈这一维必须如实答不可用</b>：18.4.1.1 对话助手已熔断未交付 ⇒
   * 上游不存在助手答案与助手使用数据 ⇒ 本模块**只提供反馈登记的载体**（表 + 录入端点 +
   * 按功能维度聚合），**不伪造**任何助手使用/反馈数据。
   */
  public AiEffectCapabilities capabilities() {
    var capabilities =
        List.of(
            new AiEffectCapability(
                "18.4.3.2",
                "翻译采纳率（人工直接采用 / 修改后采用 / 拒绝 三态）",
                true,
                "platform_ai_effect_adoption 台账（三态决策，事实不可改、只可撤回）"
                    + " + GET /platform/ai/effects/adoption-report 报表；"
                    + "分母来自 18.4.3.1 的 platform_ai_usage_record（只读）",
                "",
                ""),
            new AiEffectCapability(
                "18.4.3.2",
                "采纳率的分母**只有一个来源**（AI 调用次数）",
                true,
                "分母 = platform_ai_usage_record 的调用条数（18.4.3.1 只追加台账，本模块只读）；"
                    + "分子 = 本模块的采纳条数；🔴 **分母为 0 时比率是 null**，"
                    + "不是 0（「没有调用记录」≠「调用了但没人用」）",
                "",
                ""),
            new AiEffectCapability(
                "18.4.3.2",
                "预测准确度回测（实际值 vs 预测值 + 误差口径）",
                true,
                "读 18.4.2.2 的 platform_forecast_result（预测值 + 置信区间）与 "
                    + "platform_forecast_demand_history（**真实销量**求和）；"
                    + "误差 = |实际 - 预测|；相对误差在实际值为 0 时**不可计算 ⇒ null**；"
                    + "🔴 **绝不拿预测值自己当实际值**",
                "",
                ""),
            new AiEffectCapability(
                "18.4.3.2",
                "有用性反馈登记与按功能维度聚合",
                true,
                "platform_ai_effect_feedback 台账（三档：有用 / 部分有用 / 无用）+ 受控理由词表；"
                    + "GET /platform/ai/effects/feedback 列表 + 概览里的 usefulness 聚合",
                "",
                ""),
            new AiEffectCapability(
                "18.4.3.2",
                "低价值功能识别（阈值 + 建议下线清单）",
                true,
                "platform_ai_effect_threshold（**租户级一行**阈值；未登记时用系统默认值并如实标注）"
                    + " + GET /platform/ai/effects/suggestions 建议清单；"
                    + "🔴 判定低价值要求**样本量达标**（默认调用次数 ≥ 20），"
                    + "不拿 3 次调用判死刑",
                "",
                ""),
            new AiEffectCapability(
                "18.4.3.2",
                "改进建议台账（只记建议，不执行下线）",
                true,
                "platform_ai_effect_suggestion（同一功能 + 同一建议类型只有一条，重新生成 = 刷新）；"
                    + "🔴 **本模块只出建议清单** —— 真正下线涉及 18.4.3.1 的功能开关 / 配额设置，"
                    + "本仓**没有**按能力粒度的 AI 功能开关 ⇒ 需人工执行",
                "",
                ""),
            new AiEffectCapability(
                "18.4.3.2",
                "**助手答案有用性反馈**（评估对象）",
                false,
                "⚠️ 18.4.1.1 对话助手已熔断未交付 ⇒ 上游**不存在**助手答案、"
                    + "也不存在助手的使用记录 ⇒ 本模块**只提供反馈登记的载体**"
                    + "（表 + 录入端点 + 按功能维度聚合），"
                    + "🔴 **绝不伪造助手的使用/反馈数据**：助手维度聚合恒为「暂无反馈」",
                "18.4.1.1",
                "助手答案维度的有用性反馈**无法评估** —— 上游没交付，"
                    + "本模块只能等它交付后再由各能力自行回报；"
                    + "🔴 「建议下线」也只是建议：真正的下线动作不在本模块"));
    var gaps =
        List.of(
            new AiEffectGap(
                "18.4.1.1",
                "对话助手（自然语言助手）",
                "platform",
                false,
                "上游",
                "本仓**已熔断未交付** ⇒ 助手答案不存在、助手使用数据也不存在 ⇒ "
                    + "「助手答案有用性反馈」这一评估对象**上游不存在**；"
                    + "本模块提供**反馈登记的载体**与按功能维度的聚合能力，"
                    + "并如实标出「该能力当前是否已交付」"),
            new AiEffectGap(
                "18.4.3.1",
                "AI 功能下线开关（按能力粒度）",
                "platform",
                false,
                "下游",
                "18.4.3.1 提供的是**配额**与**敏感数据策略**，"
                    + "**没有**按能力粒度的功能开关 ⇒ 「识别低价值功能并下线」在本仓"
                    + "只能做到「识别 + 给出建议清单」，"
                    + "🔴 **本模块不提供任何 /disable 动作**（假装存在比不做更糟）"),
            new AiEffectGap(
                "18.4",
                "上游 AI 能力的调用回报",
                "platform",
                false,
                "上游",
                "18.4 的其它 AI 能力尚未全部交付 ⇒ 用量台账里可能还没有真实调用记录 ⇒ "
                    + "**采纳率的分母可能为 0 ⇒ 比率是 null**（不是 0）。"
                    + "各能力接上后自行回报（主体成员由服务端从会话解析）"));
    return new AiEffectCapabilities(capabilities, gaps);
  }

  // ================================================================ 阈值

  /**
   * 当前生效的评估阈值。
   *
   * <p>⚠️ 库里**没有**登记这一行时，返回**系统默认值**并把 {@code configured} 标成
   * {@code false} + 在 {@code basis} 里写明「未配置」—— 不谎称是租户定的。
   */
  public AiEffectThresholdRow thresholds() {
    var row =
        sql.fetchOne(
            "select id,min_calls,low_adoption_rate,min_feedback_count,low_useful_rate,max_mape,"
                + " updated_at from platform_ai_effect_threshold");
    if (row == null) {
      return new AiEffectThresholdRow(
          null,
          DEFAULT_MIN_CALLS,
          decimal(DEFAULT_LOW_ADOPTION_RATE),
          DEFAULT_MIN_FEEDBACK_COUNT,
          decimal(DEFAULT_LOW_USEFUL_RATE),
          decimal(DEFAULT_MAX_MAPE),
          false,
          "库里**没有**登记这一行 ⇒ 当前用的是**系统默认阈值**"
              + "（调用次数 ≥ 20、采纳率 < 0.2 判低价值、反馈数 ≥ 5、"
              + "正向反馈占比 < 0.5 判有用性偏低、MAPE > 0.3 判预测准确度偏低）",
          null);
    }
    return new AiEffectThresholdRow(
        row.get("id", UUID.class),
        row.get("min_calls", Long.class),
        decimal(row.get("low_adoption_rate", BigDecimal.class)),
        row.get("min_feedback_count", Long.class),
        decimal(row.get("low_useful_rate", BigDecimal.class)),
        decimal(row.get("max_mape", BigDecimal.class)),
        true,
        "租户已在库里登记这一行 ⇒ 以下阈值是**该租户自己定的**",
        row.get("updated_at", OffsetDateTime.class));
  }

  /** 登记 / 修改评估阈值（**一个租户只有一行**）。 */
  public AiEffectThresholdRow saveThreshold(AiEffectThresholdInput input) {
    var current = thresholds();
    var minCalls = input.minCalls() == null ? current.minCalls() : input.minCalls();
    check(minCalls >= 0, "调用次数门槛不能为负");
    var lowAdoption =
        input.lowAdoptionRate() == null
            ? new BigDecimal(current.lowAdoptionRate())
            : rate(input.lowAdoptionRate(), "低价值采纳率阈值");
    var minFeedback =
        input.minFeedbackCount() == null ? current.minFeedbackCount() : input.minFeedbackCount();
    check(minFeedback >= 0, "反馈数门槛不能为负");
    var lowUseful =
        input.lowUsefulRate() == null
            ? new BigDecimal(current.lowUsefulRate())
            : rate(input.lowUsefulRate(), "低有用性阈值");
    var maxMape =
        input.maxMape() == null
            ? new BigDecimal(current.maxMape())
            : rate(input.maxMape(), "MAPE 阈值");

    var now = dbNow();
    sql.execute(
        "insert into platform_ai_effect_threshold(id,tenant_id,min_calls,low_adoption_rate,"
            + " min_feedback_count,low_useful_rate,max_mape,updated_at)"
            + " values(?,?,?,?,?,?,?,?::timestamptz)"
            + " on conflict (tenant_id) do update"
            + " set min_calls = excluded.min_calls, low_adoption_rate = excluded.low_adoption_rate,"
            + " min_feedback_count = excluded.min_feedback_count,"
            + " low_useful_rate = excluded.low_useful_rate, max_mape = excluded.max_mape,"
            + " updated_at = excluded.updated_at",
        UUID.randomUUID(),
        currentTenant(),
        minCalls,
        lowAdoption,
        minFeedback,
        lowUseful,
        maxMape,
        now);
    return thresholds();
  }

  // ================================================================ 采纳记录

  /**
   * 录入一次人工处置（直接采用 / 修改后采用 / 拒绝）。
   *
   * <p>🔴 **主体成员只来自已认证会话**（请求体不接受 membershipId）。
   * 🔴 **产物只记引用 + 版本快照**，不复制内容当事实。
   */
  public AiAdoptionRow recordAdoption(AiAdoptionInput input, AccessService.Access actor) {
    var capability = capabilityCode(input.capabilityCode());
    var feature = optional(input.featureCode(), "子功能", 64);
    var decision = AdoptionDecision.parse(input.decision());
    check(decision != null, "处置只能是 ACCEPTED（直接采用）/ MODIFIED（修改后采用）/ REJECTED（拒绝）");
    var artifactType = ArtifactType.parse(input.artifactType());
    check(artifactType != null, "产物类型只能是 TRANSLATION / FORECAST / ASSISTANT / OTHER");
    var artifactId = input.artifactId();
    var artifactVersion = input.artifactVersion();
    var artifactRef = optional(input.artifactRef(), "产物定位码", 64);
    var usageRecordId = input.usageRecordId();
    var editNote = optional(input.editNote(), "修改说明", 500);
    var rejectReason = optional(input.rejectReason(), "驳回原因", 500);

    // 🔴 本仓自己拥有的两类产物**必须**带 id（否则「引用」是空话）。
    if (artifactType == ArtifactType.TRANSLATION || artifactType == ArtifactType.FORECAST) {
      check(artifactId != null, artifactType.label() + "必须给出产物 id");
    }
    if (artifactVersion != null) {
      check(artifactVersion >= 1, "产物版本号必须 ≥ 1");
    }
    // 驳回必须写明原因（与 DB CHECK 同口径，服务层先给可读的报错）。
    if (decision == AdoptionDecision.REJECTED) {
      check(!rejectReason.isEmpty(), "驳回必须写明原因");
    }
    // 🔴 挂上用量记录时，必须真的挂在本租户的调用记录上（DB 复合 FK 兜底，这里先给可读报错）。
    if (usageRecordId != null) {
      var usage =
          sql.fetchOne(
              "select id from platform_ai_usage_record where id = ? and tenant_id = ?",
              usageRecordId,
              currentTenant());
      check(usage != null, "引用不到本租户的调用记录");
    }

    var now = dbNow();
    var id = UUID.randomUUID();
    try {
      sql.execute(
          "insert into platform_ai_effect_adoption(id,tenant_id,capability_code,feature_code,"
              + " decision,artifact_type,artifact_id,artifact_version,artifact_ref,usage_record_id,"
              + " membership_id,edit_note,reject_reason,status,occurred_at,created_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?::timestamptz,?::timestamptz)",
          id,
          currentTenant(),
          capability,
          feature,
          decision.name(),
          artifactType.name(),
          artifactId,
          artifactVersion,
          artifactRef,
          usageRecordId,
          actor.membershipId(),
          editNote,
          rejectReason,
          now,
          now);
    } catch (org.jooq.exception.DataAccessException e) {
      // 🔴 同一个产物版本**只允许一条处置**（唯一索引）—— 重复登记会让分子虚高。
      conflict(
          "该产物版本已经登记过处置了（同一产物版本只允许一条）—— "
              + "重复登记会让采纳率虚高，那等于把采纳率做成一件可以刷的事");
    }
    return adoptionRow(id);
  }

  /** 撤回一条采纳记录（事实不可改，但**填错了要能纠正**）。 */
  public AiAdoptionRow withdrawAdoption(UUID id, AiWithdrawInput input, AccessService.Access actor) {
    var reason = optional(input == null ? null : input.reason(), "撤回原因", 500);
    var now = dbNow();
    var updated =
        sql.execute(
            "update platform_ai_effect_adoption"
                + " set status = 'WITHDRAWN', withdrawn_at = ?::timestamptz, withdrawn_by = ?,"
                + " withdrawn_reason = ?"
                + " where id = ? and tenant_id = ? and status = 'ACTIVE'",
            now,
            actor.membershipId(),
            reason,
            id,
            currentTenant());
    check(updated > 0, "这条采纳记录不存在，或已经被撤回过了");
    return adoptionRow(id);
  }

  /** 采纳记录台账（真分页 + 真筛选）。 */
  public AiAdoptionPage adoptions(
      String capability, String feature, String decision, String from, String to, int page, int size) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (capability != null && !capability.isBlank()) {
      where.append(" and capability_code = ?");
      params.add(capability.trim().toUpperCase(Locale.ROOT));
    }
    if (feature != null && !feature.isBlank()) {
      where.append(" and feature_code = ?");
      params.add(feature.trim());
    }
    if (decision != null && !decision.isBlank()) {
      var parsed = AdoptionDecision.parse(decision);
      check(parsed != null, "处置只能是 ACCEPTED / MODIFIED / REJECTED");
      where.append(" and decision = ?");
      params.add(parsed.name());
    }
    var fromAt = parseInstant(from, "起始时刻");
    var toAt = parseInstant(to, "结束时刻");
    if (fromAt != null) {
      where.append(" and occurred_at >= ?::timestamptz");
      params.add(fromAt);
    }
    if (toAt != null) {
      where.append(" and occurred_at < ?::timestamptz");
      params.add(toAt);
    }
    var clause = where.toString();
    var total =
        sql.fetchOne("select count(*) from platform_ai_effect_adoption" + clause, params.toArray())
            .get(0, Long.class);
    var limit = Math.min(Math.max(size, 1), 200);
    var offset = Math.max(page, 0) * limit;
    var items =
        sql.fetch(
                "select id,capability_code,feature_code,decision,artifact_type,artifact_id,"
                    + " artifact_version,artifact_ref,usage_record_id,membership_id,edit_note,"
                    + " reject_reason,status,withdrawn_reason,occurred_at"
                    + " from platform_ai_effect_adoption"
                    + clause
                    + " order by occurred_at desc, id desc limit ? offset ?",
                concat(params, limit, offset))
            .map(AiEffectEvaluationService::toAdoptionRow);
    return new AiAdoptionPage(items, Math.max(page, 0), limit, total);
  }

  private AiAdoptionRow adoptionRow(UUID id) {
    var row =
        sql.fetchOne(
            "select id,capability_code,feature_code,decision,artifact_type,artifact_id,"
                + " artifact_version,artifact_ref,usage_record_id,membership_id,edit_note,"
                + " reject_reason,status,withdrawn_reason,occurred_at"
                + " from platform_ai_effect_adoption where id = ?",
            id);
    if (row == null) notFound("找不到这条采纳记录");
    return toAdoptionRow(row);
  }

  private static AiAdoptionRow toAdoptionRow(org.jooq.Record r) {
    var decision = AdoptionDecision.parse(r.get("decision", String.class));
    var artifactType = ArtifactType.parse(r.get("artifact_type", String.class));
    return new AiAdoptionRow(
        r.get("id", UUID.class),
        r.get("capability_code", String.class),
        r.get("feature_code", String.class),
        decision == null ? "" : decision.name(),
        decision == null ? "" : decision.label(),
        artifactType == null ? "" : artifactType.name(),
        artifactType == null ? "" : artifactType.label(),
        r.get("artifact_id", UUID.class),
        r.get("artifact_version", Integer.class),
        r.get("artifact_ref", String.class),
        r.get("usage_record_id", UUID.class),
        r.get("membership_id", UUID.class),
        r.get("edit_note", String.class),
        r.get("reject_reason", String.class),
        r.get("status", String.class),
        r.get("withdrawn_reason", String.class),
        r.get("occurred_at", OffsetDateTime.class));
  }

  // ================================================================ 有用性反馈

  /**
   * 登记一次有用性反馈。
   *
   * <p>⚠️ <b>助手答案这一维上游未交付</b>：本端点**照样接受** ASSISTANT 类型的反馈登记
   * （它是反馈的**载体**），但聚合时助手维度会如实显示「暂无反馈」——
   * 🔴 **绝不伪造助手的使用/反馈数据**。
   */
  public AiFeedbackRow recordFeedback(AiFeedbackInput input, AccessService.Access actor) {
    var capability = capabilityCode(input.capabilityCode());
    var feature = optional(input.featureCode(), "子功能", 64);
    var usefulness = Usefulness.parse(input.usefulness());
    check(usefulness != null, "有用性只能是 USEFUL（有用）/ PARTIALLY_USEFUL（部分有用）/ NOT_USEFUL（无用）");
    var reasonCode = optional(input.reasonCode(), "理由码", 32);
    if (!reasonCode.isEmpty()) {
      var parsed = FeedbackReason.parse(reasonCode);
      check(parsed != null, "理由码只能是受控词表里的码（INACCURATE / TOO_VERBOSE / …）");
      reasonCode = parsed.name();
    }
    var reasonText = optional(input.reasonText(), "补充说明", 500);
    var artifactType = ArtifactType.parse(input.artifactType());
    check(artifactType != null, "产物类型只能是 TRANSLATION / FORECAST / ASSISTANT / OTHER");
    var artifactId = input.artifactId();
    var artifactVersion = input.artifactVersion();
    var artifactRef = optional(input.artifactRef(), "产物定位码", 64);
    if (artifactType == ArtifactType.TRANSLATION || artifactType == ArtifactType.FORECAST) {
      check(artifactId != null, artifactType.label() + "必须给出产物 id");
    }
    if (artifactVersion != null) {
      check(artifactVersion >= 1, "产物版本号必须 ≥ 1");
    }

    var now = dbNow();
    var id = UUID.randomUUID();
    sql.execute(
        "insert into platform_ai_effect_feedback(id,tenant_id,capability_code,feature_code,"
            + " usefulness,reason_code,reason_text,artifact_type,artifact_id,artifact_version,"
            + " artifact_ref,membership_id,status,occurred_at,created_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        capability,
        feature,
        usefulness.name(),
        reasonCode,
        reasonText,
        artifactType.name(),
        artifactId,
        artifactVersion,
        artifactRef,
        actor.membershipId(),
        now,
        now);
    return feedbackRow(id);
  }

  /** 撤回一条有用性反馈。 */
  public AiFeedbackRow withdrawFeedback(UUID id, AiWithdrawInput input, AccessService.Access actor) {
    var reason = optional(input == null ? null : input.reason(), "撤回原因", 500);
    var now = dbNow();
    var updated =
        sql.execute(
            "update platform_ai_effect_feedback"
                + " set status = 'WITHDRAWN', withdrawn_at = ?::timestamptz, withdrawn_by = ?,"
                + " withdrawn_reason = ?"
                + " where id = ? and tenant_id = ? and status = 'ACTIVE'",
            now,
            actor.membershipId(),
            reason,
            id,
            currentTenant());
    check(updated > 0, "这条有用性反馈不存在，或已经被撤回过了");
    return feedbackRow(id);
  }

  /** 有用性反馈台账（真分页 + 真筛选）。 */
  public AiFeedbackPage feedbacks(
      String capability, String feature, String usefulness, String from, String to, int page, int size) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (capability != null && !capability.isBlank()) {
      where.append(" and capability_code = ?");
      params.add(capability.trim().toUpperCase(Locale.ROOT));
    }
    if (feature != null && !feature.isBlank()) {
      where.append(" and feature_code = ?");
      params.add(feature.trim());
    }
    if (usefulness != null && !usefulness.isBlank()) {
      var parsed = Usefulness.parse(usefulness);
      check(parsed != null, "有用性只能是 USEFUL / PARTIALLY_USEFUL / NOT_USEFUL");
      where.append(" and usefulness = ?");
      params.add(parsed.name());
    }
    var fromAt = parseInstant(from, "起始时刻");
    var toAt = parseInstant(to, "结束时刻");
    if (fromAt != null) {
      where.append(" and occurred_at >= ?::timestamptz");
      params.add(fromAt);
    }
    if (toAt != null) {
      where.append(" and occurred_at < ?::timestamptz");
      params.add(toAt);
    }
    var clause = where.toString();
    var total =
        sql.fetchOne("select count(*) from platform_ai_effect_feedback" + clause, params.toArray())
            .get(0, Long.class);
    var limit = Math.min(Math.max(size, 1), 200);
    var offset = Math.max(page, 0) * limit;
    var items =
        sql.fetch(
                "select id,capability_code,feature_code,usefulness,reason_code,reason_text,"
                    + " artifact_type,artifact_id,artifact_version,artifact_ref,membership_id,status,"
                    + " withdrawn_reason,occurred_at"
                    + " from platform_ai_effect_feedback"
                    + clause
                    + " order by occurred_at desc, id desc limit ? offset ?",
                concat(params, limit, offset))
            .map(AiEffectEvaluationService::toFeedbackRow);
    return new AiFeedbackPage(items, Math.max(page, 0), limit, total);
  }

  private AiFeedbackRow feedbackRow(UUID id) {
    var row =
        sql.fetchOne(
            "select id,capability_code,feature_code,usefulness,reason_code,reason_text,"
                + " artifact_type,artifact_id,artifact_version,artifact_ref,membership_id,status,"
                + " withdrawn_reason,occurred_at"
                + " from platform_ai_effect_feedback where id = ?",
            id);
    if (row == null) notFound("找不到这条有用性反馈");
    return toFeedbackRow(row);
  }

  private static AiFeedbackRow toFeedbackRow(org.jooq.Record r) {
    var usefulness = Usefulness.parse(r.get("usefulness", String.class));
    var reason = FeedbackReason.parse(r.get("reason_code", String.class));
    var artifactType = ArtifactType.parse(r.get("artifact_type", String.class));
    return new AiFeedbackRow(
        r.get("id", UUID.class),
        r.get("capability_code", String.class),
        r.get("feature_code", String.class),
        usefulness == null ? "" : usefulness.name(),
        usefulness == null ? "" : usefulness.label(),
        reason == null ? "" : reason.name(),
        reason == null ? "" : reason.label(),
        r.get("reason_text", String.class),
        artifactType == null ? "" : artifactType.name(),
        artifactType == null ? "" : artifactType.label(),
        r.get("artifact_id", UUID.class),
        r.get("artifact_version", Integer.class),
        r.get("artifact_ref", String.class),
        r.get("membership_id", UUID.class),
        r.get("status", String.class),
        r.get("withdrawn_reason", String.class),
        r.get("occurred_at", OffsetDateTime.class));
  }

  // ================================================================ 采纳率报表

  /**
   * 按功能的采纳率报表（真分页 + 真筛选）。
   *
   * <p>🔴 <b>分母只有���个来源</b>：{@code platform_ai_usage_record}（18.4.3.1 的只追加台账）。
   * 🔴 <b>分母为 0 ⇒ 比率是 null</b>。
   */
  public AiAdoptionReportPage adoptionReport(
      String capability, String from, String to, int page, int size) {
    var w = window(from, to, DEFAULT_WINDOW_DAYS);
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (capability != null && !capability.isBlank()) {
      where.append(" and capability_code = ?");
      params.add(capability.trim().toUpperCase(Locale.ROOT));
    }
    var clause = where.toString();

    // 分母：调用次数（**只读** 18.4.3.1 的台账）。
    var calls =
        sql.fetch(
                "select capability_code, feature_code, count(*) as calls"
                    + " from platform_ai_usage_record"
                    + clause
                    + " and occurred_at >= ?::timestamptz and occurred_at < ?::timestamptz"
                    + " group by capability_code, feature_code",
                concat(params, w[0], w[1]))
            .map(
                r ->
                    Map.entry(
                        r.get("capability_code", String.class) + " " + r.get("feature_code", String.class),
                        r.get("calls", Long.class)));

    // 分子：采纳条数（**只做分子**，剔除已撤回）。
    // 🔴 「采纳」= ACCEPTED + MODIFIED（蓝图原文：直接采用 + 人工修改后采用）；
    //   REJECTED **不**计入分子，单独列在 rejected_count 里。
    var adopted =
        sql.fetch(
                "select capability_code, feature_code,"
                    + " count(*) filter (where decision in ('ACCEPTED','MODIFIED')) as adopted,"
                    + " count(*) filter (where decision = 'ACCEPTED') as accepted,"
                    + " count(*) filter (where decision = 'MODIFIED') as modified,"
                    + " count(*) filter (where decision = 'REJECTED') as rejected,"
                    + " count(*) filter (where decision in ('ACCEPTED','MODIFIED')"
                    + " and usage_record_id is not null) as linked"
                    + " from platform_ai_effect_adoption"
                    + clause
                    + " and status = 'ACTIVE'"
                    + " and occurred_at >= ?::timestamptz and occurred_at < ?::timestamptz"
                    + " group by capability_code, feature_code",
                concat(params, w[0], w[1]))
            .map(
                r -> {
                  var key = r.get("capability_code", String.class) + " " + r.get("feature_code", String.class);
                  return Map.entry(
                      key,
                      new long[] {
                        r.get("adopted", Long.class),
                        r.get("accepted", Long.class),
                        r.get("modified", Long.class),
                        r.get("rejected", Long.class),
                        r.get("linked", Long.class)
                      });
                });

    // 合并两个维度：有的能力有调用但没人采纳，有的能力有人采纳但上游还没报调用。
    // 🔴 每一行 6 个槽位：[0]=calls [1]=adopted [2]=accepted [3]=modified [4]=rejected [5]=linked
    var merged = new LinkedHashMap<String, long[]>();
    var keys = new ArrayList<String>();
    for (var entry : calls) {
      if (!merged.containsKey(entry.getKey())) {
        merged.put(entry.getKey(), new long[6]);
        keys.add(entry.getKey());
      }
      merged.get(entry.getKey())[0] += entry.getValue();
    }
    for (var entry : adopted) {
      if (!merged.containsKey(entry.getKey())) {
        merged.put(entry.getKey(), new long[6]);
        keys.add(entry.getKey());
      }
      var row = merged.get(entry.getKey());
      row[1] = entry.getValue()[0];
      row[2] = entry.getValue()[1];
      row[3] = entry.getValue()[2];
      row[4] = entry.getValue()[3];
      row[5] = entry.getValue()[4];
    }

    var thresholds = thresholds();
    var lowAdoption = new BigDecimal(thresholds.lowAdoptionRate());
    var all = new ArrayList<AiAdoptionReportRow>();
    for (var key : keys) {
      var parts = key.split(" ", -1);
      var capabilityCode = parts[0];
      var featureCode = parts.length > 1 ? parts[1] : "";
      var row = merged.get(key);
      var callCount = row[0];
      var adoptedCount = row[1];
      var acceptedCount = row[2];
      var modifiedCount = row[3];
      var rejectedCount = row[4];
      var linkedCount = row[5];
      var unlinkedCount = adoptedCount - linkedCount;

      var rate = rateText(BigDecimal.valueOf(adoptedCount), callCount);
      var verdict = FeatureVerdict.OK;
      var reason = "";
      if (callCount < thresholds.minCalls()) {
        verdict = FeatureVerdict.INSUFFICIENT_DATA;
        reason =
            "调用次数 "
                + callCount
                + " < 门槛 "
                + thresholds.minCalls()
                + " ⇒ **样本不足，不下低价值结论**（不拿 "
                + callCount
                + " 次调用判死刑）";
      } else if (rate != null && new BigDecimal(rate).compareTo(lowAdoption) < 0) {
        verdict = FeatureVerdict.LOW_VALUE;
        reason =
            "调用 "
                + callCount
                + " 次、采纳 "
                + adoptedCount
                + " 次（直接采用 "
                + acceptedCount
                + " / 修改后采用 "
                + modifiedCount
                + "）⇒ 采纳率 "
                + rate
                + " < 阈值 "
                + thresholds.lowAdoptionRate()
                + " ⇒ **低价值**";
      } else if (rate == null) {
        verdict = FeatureVerdict.INSUFFICIENT_DATA;
        reason = "该功能在本窗口内没有调用记录 ⇒ **分母为 0**，采纳率不可计算（不是 0）";
      } else {
        reason =
            "调用 "
                + callCount
                + " 次、采纳 "
                + adoptedCount
                + " 次（直接采用 "
                + acceptedCount
                + " / 修改后采用 "
                + modifiedCount
                + " / 拒绝 "
                + rejectedCount
                + "）⇒ 采纳率 "
                + rate
                + " ≥ 阈值 "
                + thresholds.lowAdoptionRate();
      }
      all.add(
          new AiAdoptionReportRow(
              capabilityCode,
              featureCode,
              callCount,
              adoptedCount,
              acceptedCount,
              modifiedCount,
              rejectedCount,
              linkedCount,
              unlinkedCount,
              rate,
              "platform_ai_usage_record（18.4.3.1 只追加用量台账）",
              "platform_ai_effect_adoption（ACTIVE · ACCEPTED + MODIFIED）",
              verdict.name(),
              verdict.label(),
              reason,
              w[0].toInstant().toString(),
              w[1].toInstant().toString()));
    }
    all.sort(
        (a, b) -> {
          var byCapability = a.capabilityCode().compareTo(b.capabilityCode());
          if (byCapability != 0) return byCapability;
          return a.featureCode().compareTo(b.featureCode());
        });
    var limit = Math.min(Math.max(size, 1), 200);
    var offset = Math.max(page, 0) * limit;
    var items =
        all.subList(Math.min(offset, all.size()), Math.min(offset + limit, all.size()));
    return new AiAdoptionReportPage(
        items, Math.max(page, 0), limit, all.size(), w[0].toInstant().toString(), w[1].toInstant().toString());
  }

  // ================================================================ 预测准确度回测

  /**
   * 跑一次预测准确度回测。
   *
   * <p>🔴 <b>实际值来自真实销量</b>（{@code platform_forecast_demand_history.units} 在预测期内的求和），
   * <b>绝不拿预测值自己当实际值</b>。
   * 🔴 <b>该 SKU 一条销量都没有 ⇒ 如实记 {@code NO_ACTUAL_SOURCE}</b>，{@code actual_units} 留
   * {@code null} —— <b>不记 0</b>（记 0 等于说「卖了个零」）。
   * 🔴 <b>只读上游</b>：18.4.2.2 的预测结果表与销量历史表，本模块一个字都不写。
   */
  public AiBacktestRunResult runBacktest(AiBacktestRunInput input) {
    var runId = input == null ? null : input.runId();
    if (runId == null) {
      var last =
          sql.fetchOne(
              "select id from platform_forecast_run where status = 'COMPLETED'"
                  + " order by started_at desc limit 1");
      check(last != null, "还没有跑过任何预测运行 ⇒ 没有可回测的预测结果");
      runId = last.get("id", UUID.class);
    }
    var run =
        sql.fetchOne(
            "select id,run_no,status,started_at,horizon_days from platform_forecast_run where id = ?",
            runId);
    if (run == null) notFound("找不到这次预测运行");
    // 🔴 预测运行没跑完 ⇒ 结果不可信 ⇒ 逐条记「不可比」，不拿半截结果算误差。
    var runCompleted = "COMPLETED".equals(run.get("status", String.class));

    // 🔴 **预测期怎么还原**：18.4.2.2 的预测期 = [运行当日 + 1 天, 运行当日 + horizon_days]，
    //   但那张表**没有存**这两个日期 ⇒ 本模块按「运行时刻的 UTC 日期 + 1 天」起算、
    //   跨 horizon_days 天还原，并把还原出来的窗口写进 actual_window_start/end（可审计）。
    var startedAt = run.get("started_at", OffsetDateTime.class);
    var horizonDays = run.get("horizon_days", Integer.class);
    var windowStart = startedAt.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).plusDays(1);
    var windowEnd = windowStart.plusDays(horizonDays - 1L);

    var results =
        sql.fetch(
                "select id,sku_code,sku_name,point,lower,upper,horizon_days"
                    + " from platform_forecast_result where run_id = ? order by sku_code",
                runId);

    var items = new ArrayList<AiBacktestRow>();
    var now = dbNow();
    var evaluated = 0L;
    var compared = 0L;
    var noActual = 0L;
    var notComparable = 0L;
    var sumRelative = BigDecimal.ZERO;
    var sumRelativeCount = 0L;
    var hitCount = 0L;

    for (var result : results) {
      evaluated++;
      var resultId = result.get("id", UUID.class);
      var skuCode = result.get("sku_code", String.class);
      var skuName = result.get("sku_name", String.class);
      var point = result.get("point", BigDecimal.class);
      var lower = result.get("lower", BigDecimal.class);
      var upper = result.get("upper", BigDecimal.class);
      var resultHorizon = result.get("horizon_days", Integer.class);
      // 每条结果自带 horizon_days ⇒ 预测期按**这条结果**自己的口径还原（更准）。
      var rowStart = windowStart;
      var rowEnd = windowStart.plusDays(resultHorizon - 1L);

      // 🔴 实际值 = **真实销量**在预测期内的求和（只读上游）。
      var actual =
          sql.fetchOne(
              "select coalesce(sum(units),0) as units, count(*) as days"
                  + " from platform_forecast_demand_history"
                  + " where sku_code = ? and business_date >= ?::date and business_date <= ?::date",
              skuCode,
              rowStart.toLocalDate(),
              rowEnd.toLocalDate());
      var actualUnits = actual.get("units", BigDecimal.class);
      var actualDays = actual.get("days", Long.class);

      String status;
      String note = "";
      BigDecimal absoluteError = null;
      BigDecimal relativeError = null;
      Boolean withinInterval = null;

      if (!runCompleted) {
        // 🔴 运行没跑完 ⇒ 结果不可比（不是「缺实际值」，也不是「算错了」）。
        status = BacktestStatus.NOT_COMPARABLE.name();
        note =
            "这次预测运行的状态是 "
                + run.get("status", String.class)
                + "（未跑完）⇒ 结果不可信，**不参与误差口径**";
        notComparable++;
      } else if (actualDays == 0L) {
        // 🔴 **没有实际值来源就如实说没有**（不记 0）。
        status = BacktestStatus.NO_ACTUAL_SOURCE.name();
        note =
            "无法回测，缺实际值来源：该 SKU 在预测期 "
                + rowStart.toLocalDate()
                + " ~ "
                + rowEnd.toLocalDate()
                + " 内**一条销量记录都没有**"
                + "（实际值来源 = platform_forecast_demand_history.units）";
        noActual++;
        actualUnits = null;
      } else {
        var error = point.subtract(actualUnits).abs();
        absoluteError = error.setScale(SCALE, RoundingMode.HALF_UP);
        // 🔴 相对误差在**实际值为 0** 时不可计算 ⇒ null（不是 0、不是 Infinity）。
        if (actualUnits.signum() != 0) {
          var ratio = error.divide(actualUnits.abs(), SCALE, RoundingMode.HALF_UP);
          // 🔴 relative_error 列是 numeric(9,6)（上限 999.999999）：实际值极小会让比值爆掉 ⇒
          //   存不下就**不存**（null），并写清原因 —— 宁可少一个数，也不要一个被截断的假数。
          if (ratio.compareTo(MAX_RELATIVE_ERROR) <= 0) {
            relativeError = ratio;
            sumRelative = sumRelative.add(ratio);
            sumRelativeCount++;
          } else {
            note =
                "实际值 "
                    + decimal(actualUnits)
                    + " 极小 ⇒ 相对误差超出可存范围（numeric(9,6)）⇒ 该行**不参与 MAPE**";
          }
        }
        withinInterval =
            actualUnits.compareTo(lower) >= 0 && actualUnits.compareTo(upper) <= 0;
        if (withinInterval) hitCount++;
        status = BacktestStatus.COMPARED.name();
        if (note.isEmpty()) {
          note =
              "实际值取自 platform_forecast_demand_history.units 在预测期内的求和（真实销量）；"
                  + "预测期按运行时刻 UTC 日期 + 1 天起算、跨 "
                  + resultHorizon
                  + " 天还原（18.4.2.2 未存预测期起止日）";
        }
        compared++;
      }

      var backtestId = UUID.randomUUID();
      sql.execute(
          "insert into platform_ai_effect_backtest(id,tenant_id,forecast_result_id,run_id,run_no,"
              + " sku_code,sku_name,forecast_point,forecast_lower,forecast_upper,actual_units,"
              + " actual_source,actual_window_start,actual_window_end,actual_record_days,"
              + " absolute_error,relative_error,within_interval,status,note,evaluated_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?::date,?::date,?,?,?,?,?,?,?::timestamptz)"
              + " on conflict (tenant_id, forecast_result_id) do update"
              + " set run_no = excluded.run_no, sku_code = excluded.sku_code,"
              + " sku_name = excluded.sku_name, forecast_point = excluded.forecast_point,"
              + " forecast_lower = excluded.forecast_lower, forecast_upper = excluded.forecast_upper,"
              + " actual_units = excluded.actual_units, actual_source = excluded.actual_source,"
              + " actual_window_start = excluded.actual_window_start,"
              + " actual_window_end = excluded.actual_window_end,"
              + " actual_record_days = excluded.actual_record_days,"
              + " absolute_error = excluded.absolute_error, relative_error = excluded.relative_error,"
              + " within_interval = excluded.within_interval, status = excluded.status,"
              + " note = excluded.note, evaluated_at = excluded.evaluated_at",
          backtestId,
          currentTenant(),
          resultId,
          runId,
          run.get("run_no", String.class),
          skuCode,
          skuName,
          point,
          lower,
          upper,
          actualUnits,
          actualDays == 0L ? "" : "platform_forecast_demand_history.units",
          rowStart.toLocalDate(),
          rowEnd.toLocalDate(),
          actualDays.intValue(),
          absoluteError,
          relativeError,
          withinInterval,
          status,
          note,
          now);

      items.add(
          new AiBacktestRow(
              backtestId,
              resultId,
              runId,
              run.get("run_no", String.class),
              skuCode,
              skuName,
              decimal(point),
              decimal(lower),
              decimal(upper),
              decimal(actualUnits),
              actualDays == 0L ? "" : "platform_forecast_demand_history.units",
              rowStart.toLocalDate().toString(),
              rowEnd.toLocalDate().toString(),
              actualDays,
              decimal(absoluteError),
              decimal(relativeError),
              withinInterval,
              status,
              BacktestStatus.parse(status).label(),
              note,
              now));
    }

    var mape = sumRelativeCount == 0L ? null : sumRelative.divide(BigDecimal.valueOf(sumRelativeCount), SCALE, RoundingMode.HALF_UP);
    var hitRate = compared == 0L ? null : rateText(BigDecimal.valueOf(hitCount), compared);
    var basis =
        "MAPE = |实际 - 预测| / |实际| 的平均（**实际值为 0 的行不参与**）；"
            + "命中区间比例 = 落在预测置信区间内的行数 / 可比行数；"
            + "实际值来源 = platform_forecast_demand_history.units（真实销量）";
    return new AiBacktestRunResult(
        runId,
        run.get("run_no", String.class),
        evaluated,
        compared,
        noActual,
        decimal(mape),
        hitRate,
        basis,
        items);
  }

  /** 回测结果台账（真分页 + 真筛选）。 */
  public AiBacktestPage backtests(String runId, String skuCode, String status, int page, int size) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (runId != null && !runId.isBlank()) {
      where.append(" and run_id = ?::uuid");
      params.add(runId.trim());
    }
    if (skuCode != null && !skuCode.isBlank()) {
      where.append(" and sku_code = ?");
      params.add(skuCode.trim());
    }
    if (status != null && !status.isBlank()) {
      var parsed = BacktestStatus.parse(status);
      check(parsed != null, "回测判定只能是 COMPARED / NO_ACTUAL_SOURCE / NOT_COMPARABLE");
      where.append(" and status = ?");
      params.add(parsed.name());
    }
    var clause = where.toString();
    var total =
        sql.fetchOne("select count(*) from platform_ai_effect_backtest" + clause, params.toArray())
            .get(0, Long.class);
    var limit = Math.min(Math.max(size, 1), 200);
    var offset = Math.max(page, 0) * limit;
    var items =
        sql.fetch(
                "select id,forecast_result_id,run_id,run_no,sku_code,sku_name,forecast_point,"
                    + " forecast_lower,forecast_upper,actual_units,actual_source,"
                    + " actual_window_start,actual_window_end,actual_record_days,absolute_error,"
                    + " relative_error,within_interval,status,note,evaluated_at"
                    + " from platform_ai_effect_backtest"
                    + clause
                    + " order by evaluated_at desc, id desc limit ? offset ?",
                concat(params, limit, offset))
            .map(AiEffectEvaluationService::toBacktestRow);
    return new AiBacktestPage(items, Math.max(page, 0), limit, total);
  }

  private static AiBacktestRow toBacktestRow(org.jooq.Record r) {
    var status = BacktestStatus.parse(r.get("status", String.class));
    return new AiBacktestRow(
        r.get("id", UUID.class),
        r.get("forecast_result_id", UUID.class),
        r.get("run_id", UUID.class),
        r.get("run_no", String.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        decimal(r.get("forecast_point", BigDecimal.class)),
        decimal(r.get("forecast_lower", BigDecimal.class)),
        decimal(r.get("forecast_upper", BigDecimal.class)),
        decimal(r.get("actual_units", BigDecimal.class)),
        r.get("actual_source", String.class),
        r.get("actual_window_start", java.time.LocalDate.class).toString(),
        r.get("actual_window_end", java.time.LocalDate.class).toString(),
        r.get("actual_record_days", Long.class),
        decimal(r.get("absolute_error", BigDecimal.class)),
        decimal(r.get("relative_error", BigDecimal.class)),
        r.get("within_interval", Boolean.class),
        status == null ? "" : status.name(),
        status == null ? "" : status.label(),
        r.get("note", String.class),
        r.get("evaluated_at", OffsetDateTime.class));
  }

  // ================================================================ 概览

  /** 效果概览：三块（采纳率 / 预测回测误差 / 有用性反馈），每块都带**分子分母与口径**。 */
  public AiEffectOverview overview(String from, String to) {
    var w = window(from, to, DEFAULT_WINDOW_DAYS);
    var thresholds = thresholds();

    // ---- 采纳率 ----
    var calls =
        sql.fetchOne(
                "select count(*) as calls from platform_ai_usage_record"
                    + " where occurred_at >= ?::timestamptz and occurred_at < ?::timestamptz",
                w[0],
                w[1])
            .get("calls", Long.class);
    var adoption =
        sql.fetchOne(
                "select count(*) filter (where decision in ('ACCEPTED','MODIFIED')) as adopted,"
                    + " count(*) filter (where decision = 'REJECTED') as rejected"
                    + " from platform_ai_effect_adoption where status = 'ACTIVE'"
                    + " and occurred_at >= ?::timestamptz and occurred_at < ?::timestamptz",
                w[0],
                w[1]);
    var adoptedCount = adoption.get("adopted", Long.class);
    var rejectedCount = adoption.get("rejected", Long.class);

    var report = adoptionReport(null, from, to, 0, 200);
    long featuresEvaluated = report.total();
    long lowValueFeatures =
        report.items().stream().filter(r -> "LOW_VALUE".equals(r.verdict())).count();

    var adoptionSummary =
        new AiAdoptionSummary(
            calls,
            adoptedCount,
            rejectedCount,
            rateText(BigDecimal.valueOf(adoptedCount), calls),
            featuresEvaluated,
            lowValueFeatures,
            "platform_ai_usage_record（18.4.3.1 只追加用量台账 · 调用次数）",
            "platform_ai_effect_adoption（ACTIVE · ACCEPTED + MODIFIED · 采纳次数）",
            "采纳率 = 采纳次数 / 调用次数；🔴 **分母为 0 时是 null**（不是 0）；"
                + "「修改后采用」**也算被采纳**（蓝图原文：直接采用 + 人工修改后采用）");

    // ---- 预测回测误差 ----
    var backtest =
        sql.fetchOne(
            "select count(*) as evaluated,"
                + " count(*) filter (where status = 'COMPARED') as compared,"
                + " count(*) filter (where status = 'NO_ACTUAL_SOURCE') as no_actual,"
                + " avg(relative_error) as mape,"
                + " count(*) filter (where within_interval is true) as hits"
                + " from platform_ai_effect_backtest");
    var compared = backtest.get("compared", Long.class);
    var hits = backtest.get("hits", Long.class);
    var forecastSummary =
        new AiForecastBacktestSummary(
            backtest.get("evaluated", Long.class),
            compared,
            backtest.get("no_actual", Long.class),
            decimal(backtest.get("mape", BigDecimal.class)),
            rateText(BigDecimal.valueOf(hits), compared),
            "回测结果台账 platform_ai_effect_backtest；"
                + "MAPE = |实际 - 预测| / |实际| 的平均（**实际值为 0 的行不参与**）；"
                + "实际值来源 = platform_forecast_demand_history.units（真实销量）；"
                + "⚠️ **实际值为 0 的行不参与 MAPE**，且一条销量都没有的行记 "
                + "NO_ACTUAL_SOURCE（**不记 0**）");

    // ---- 有用性反馈 ----
    var feedback =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where usefulness = 'USEFUL') as useful,"
                + " count(*) filter (where usefulness = 'PARTIALLY_USEFUL') as partial,"
                + " count(*) filter (where usefulness = 'NOT_USEFUL') as not_useful"
                + " from platform_ai_effect_feedback where status = 'ACTIVE'"
                + " and occurred_at >= ?::timestamptz and occurred_at < ?::timestamptz",
            w[0],
            w[1]);
    var totalFeedback = feedback.get("total", Long.class);
    var useful = feedback.get("useful", Long.class);
    var partial = feedback.get("partial", Long.class);
    var notUseful = feedback.get("not_useful", Long.class);
    var positive = useful + partial;
    var usefulnessSummary =
        new AiUsefulnessSummary(
            totalFeedback,
            useful,
            partial,
            notUseful,
            rateText(BigDecimal.valueOf(useful), totalFeedback),
            rateText(BigDecimal.valueOf(partial), totalFeedback),
            rateText(BigDecimal.valueOf(notUseful), totalFeedback),
            rateText(BigDecimal.valueOf(positive), totalFeedback),
            "有用性反馈台账 platform_ai_effect_feedback（ACTIVE）；"
                + "正向 = 有用 + 部分有用；⚠️ **助手答案维度上游未交付**"
                + "（18.4.1.1 已熔断）⇒ 该维度恒为「暂无反馈」，**不伪造助手数据**");

    // ---- 低价值功能清单 ----
    var lowValues =
        report.items().stream()
            .filter(r -> "LOW_VALUE".equals(r.verdict()))
            .map(
                r ->
                    new AiLowValueFeature(
                        r.capabilityCode(),
                        r.featureCode(),
                        r.calls(),
                        r.adoptionRate(),
                        r.verdict(),
                        r.verdictLabel(),
                        r.reason()))
            .toList();

    return new AiEffectOverview(
        w[0].toInstant().toString(),
        w[1].toInstant().toString(),
        adoptionSummary,
        forecastSummary,
        usefulnessSummary,
        thresholds,
        lowValues,
        "窗口 = [起始, 结束)；采纳率的分母来自 18.4.3.1 的用量台账；"
            + "低价值判定门槛来自 platform_ai_effect_threshold（租户级一行）");
  }

  // ================================================================ 改进建议

  /**
   * 生成改进建议（**只出建议，不执行下线**）。
   *
   * <p>🔴 「建议下线」就是建议：{@code offline_action} 字段如实写明
   * 「下线动作需 18.4.3.1 提供开关 / 需人工执行，本模块只出建议清单」。
   */
  public AiSuggestionGenerateResult generateSuggestions(AiSuggestionGenerateInput input) {
    var from = input == null ? null : input.from();
    var to = input == null ? null : input.to();
    var w = window(from, to, DEFAULT_SUGGESTION_WINDOW_DAYS);
    var thresholds = thresholds();
    var report = adoptionReport(null, from, to, 0, 200);

    var created = 0L;
    var refreshed = 0L;
    var resolved = 0L;
    var now = dbNow();

    // 采纳率维度：低价值 ⇒ 建议下线；样本不足 ⇒ 数据缺口（**不是**低价值）。
    for (var row : report.items()) {
      SuggestionType type;
      SuggestionSeverity severity;
      String title;
      String metric;
      String recommendation;
      if ("LOW_VALUE".equals(row.verdict())) {
        type = SuggestionType.OFFLINE_CANDIDATE;
        severity = SuggestionSeverity.HIGH;
        title = row.capabilityCode() + " 采纳率偏低，建议评估下线";
        metric =
            "调用 "
                + row.calls()
                + " 次 / 采纳 "
                + row.adoptedCount()
                + " 次 / 采纳率 "
                + row.adoptionRate()
                + "（阈值 "
                + thresholds.lowAdoptionRate()
                + "）";
        recommendation =
            "先复核该能力是否仍被业务使用；若确认无人使用，**由人工**停用或下线该 AI 能力。"
                + "⚠️ 本仓**没有**按能力粒度的 AI 功能开关 ⇒ 下线动作需人工执行";
      } else if ("INSUFFICIENT_DATA".equals(row.verdict())) {
        type = SuggestionType.DATA_GAP;
        severity = SuggestionSeverity.LOW;
        title = row.capabilityCode() + " 样本不足，暂不下结论";
        metric =
            "调用 "
                + row.calls()
                + " 次 / 采纳 "
                + row.adoptedCount()
                + " 次（门槛：调用次数 ≥ "
                + thresholds.minCalls()
                + "）";
        recommendation =
            "补足真实调用与采纳数据后再评估；⚠️ **样本不足不是低价值** —— "
                + "不拿 "
                + row.calls()
                + " 次调用判死刑";
      } else {
        continue;
      }
      var evidence =
          "分子 = platform_ai_effect_adoption（ACTIVE · ACCEPTED + MODIFIED，"
              + row.adoptedCount()
              + " 条）；分母 = platform_ai_usage_record（"
              + row.calls()
              + " 次调用）；窗口 "
              + row.windowStart()
              + " ~ "
              + row.windowEnd();
      var outcome =
          upsertSuggestion(
              row.capabilityCode(),
              row.featureCode(),
              type,
              severity,
              title,
              metric,
              recommendation,
              evidence,
              "18.4（AI 能力owner）",
              "下线动作需 18.4.3.1 提供按能力粒度的开关 / 需人工执行，本模块只出建议清单",
              w[0],
              w[1],
              now);
      if (outcome == 1) created++;
      if (outcome == 2) refreshed++;
    }

    // 🔴 **条件不再成立 ⇒ 自动关闭**（RESOLVED）：重新生成时把不再成立的建议关掉。
    var open =
        sql.fetch(
            "select id,capability_code,feature_code,suggestion_type from"
                + " platform_ai_effect_suggestion where status = 'OPEN'");
    for (var row : open) {
      var type = SuggestionType.parse(row.get("suggestion_type", String.class));
      if (type == null) continue;
      var stillHolds = false;
      for (var reportRow : report.items()) {
        if (!reportRow.capabilityCode().equals(row.get("capability_code", String.class))) continue;
        if (!reportRow.featureCode().equals(row.get("feature_code", String.class))) continue;
        stillHolds =
            switch (type) {
              case OFFLINE_CANDIDATE -> "LOW_VALUE".equals(reportRow.verdict());
              case DATA_GAP -> "INSUFFICIENT_DATA".equals(reportRow.verdict());
              default -> true;
            };
        break;
      }
      if (!stillHolds) {
        sql.execute(
            "update platform_ai_effect_suggestion"
                + " set status = 'RESOLVED', updated_at = ?::timestamptz"
                + " where id = ? and tenant_id = ? and status = 'OPEN'",
            now,
            row.get("id", UUID.class),
            currentTenant());
        resolved++;
      }
    }

    var basis =
        "建议来源 = 采纳率报表（分子：platform_ai_effect_adoption；分母：platform_ai_usage_record）"
            + " + 阈值 platform_ai_effect_threshold；"
            + "🔴 **本模块只出建议清单，不执行下线**";
    return new AiSuggestionGenerateResult(
        created, refreshed, resolved, w[0].toInstant().toString(), w[1].toInstant().toString(), basis);
  }

  /**
   * 写入 / 刷新一条建议。
   *
   * <p>🔴 **先看存不存在，再决定更新还是插入** —— 不靠「先插、撞唯一索引、再兜底」：
   *   那种写法会把**每一个**数据库错误都当成「并发撞车」吞掉，真错就被盖住了。
   *
   * @return 1 = 新建，2 = 刷新，0 = 无变化（已被人决策过 / 条件不再成立）
   */
  private int upsertSuggestion(
      String capabilityCode,
      String featureCode,
      SuggestionType type,
      SuggestionSeverity severity,
      String title,
      String metric,
      String recommendation,
      String evidence,
      String actionOwner,
      String offlineAction,
      OffsetDateTime windowStart,
      OffsetDateTime windowEnd,
      OffsetDateTime now) {
    var existing =
        sql.fetchOne(
            "select id,status from platform_ai_effect_suggestion"
                + " where tenant_id = ? and capability_code = ? and feature_code = ?"
                + " and suggestion_type = ?",
            currentTenant(),
            capabilityCode,
            featureCode,
            type.name());
    if (existing != null) {
      // 已经被人决策过（ACCEPTED / REJECTED）⇒ **不覆盖**：人工决定不能被重新生成抹掉。
      if (!"OPEN".equals(existing.get("status", String.class))) return 0;
      var updated =
          sql.execute(
              "update platform_ai_effect_suggestion"
                  + " set severity = ?, title = ?, current_metric = ?, recommendation = ?,"
                  + " evidence = ?, action_owner = ?, offline_action = ?,"
                  + " window_start = ?::timestamptz, window_end = ?::timestamptz,"
                  + " updated_at = ?::timestamptz"
                  + " where id = ? and tenant_id = ? and status = 'OPEN'",
              severity.name(),
              title,
              metric,
              recommendation,
              evidence,
              actionOwner,
              offlineAction,
              windowStart,
              windowEnd,
              now,
              existing.get("id", UUID.class),
              currentTenant());
      return updated > 0 ? 2 : 0;
    }
    sql.execute(
        "insert into platform_ai_effect_suggestion(id,tenant_id,capability_code,feature_code,"
            + " suggestion_type,severity,title,current_metric,recommendation,evidence,"
            + " action_owner,offline_action,window_start,window_end,status,generated_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,'OPEN',"
            + " ?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        capabilityCode,
        featureCode,
        type.name(),
        severity.name(),
        title,
        metric,
        recommendation,
        evidence,
        actionOwner,
        offlineAction,
        windowStart,
        windowEnd,
        now,
        now);
    return 1;
  }

  /** 改进建议列表（真分页 + 真筛选）。 */
  public AiSuggestionPage suggestions(String status, String type, String capability, int page, int size) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (status != null && !status.isBlank()) {
      var parsed = SuggestionStatus.parse(status);
      check(parsed != null, "建议状态只能是 OPEN / ACCEPTED / REJECTED / RESOLVED");
      where.append(" and status = ?");
      params.add(parsed.name());
    }
    if (type != null && !type.isBlank()) {
      var parsed = SuggestionType.parse(type);
      check(parsed != null, "建议类型只能是 OFFLINE_CANDIDATE / LOW_USEFULNESS / FORECAST_INACCURACY / DATA_GAP");
      where.append(" and suggestion_type = ?");
      params.add(parsed.name());
    }
    if (capability != null && !capability.isBlank()) {
      where.append(" and capability_code = ?");
      params.add(capability.trim().toUpperCase(Locale.ROOT));
    }
    var clause = where.toString();
    var total =
        sql.fetchOne("select count(*) from platform_ai_effect_suggestion" + clause, params.toArray())
            .get(0, Long.class);
    var lowValueCount =
        sql.fetchOne(
                "select count(*) from platform_ai_effect_suggestion"
                    + " where suggestion_type = 'OFFLINE_CANDIDATE' and status <> 'RESOLVED'")
            .get(0, Long.class);
    var limit = Math.min(Math.max(size, 1), 200);
    var offset = Math.max(page, 0) * limit;
    var items =
        sql.fetch(
                "select id,capability_code,feature_code,suggestion_type,severity,title,"
                    + " current_metric,recommendation,evidence,action_owner,offline_action,"
                    + " window_start,window_end,status,decided_by,decided_at,decision_note,"
                    + " generated_at,updated_at"
                    + " from platform_ai_effect_suggestion"
                    + clause
                    + " order by generated_at desc, id desc limit ? offset ?",
                concat(params, limit, offset))
            .map(AiEffectEvaluationService::toSuggestionRow);
    return new AiSuggestionPage(items, Math.max(page, 0), limit, total, lowValueCount);
  }

  /**
   * 记录对一条建议的人工决策（**只记录决定，不执行下线**）。
   *
   * <p>🔴 采纳一条「建议下线」= 把「这个功能要下线」这件事落到纸面 ——
   * 它离「真的下线」只差一个开关。门槛要真的拦得住人。
   */
  public AiSuggestionRow decideSuggestion(
      UUID id, SuggestionStatus decision, AiSuggestionDecisionInput input, AccessService.Access actor) {
    check(decision == SuggestionStatus.ACCEPTED || decision == SuggestionStatus.REJECTED,
        "决策只能是 ACCEPTED（采纳建议）/ REJECTED（驳回建议）");
    var note = optional(input == null ? null : input.note(), "决策说明", 500);
    var now = dbNow();
    var updated =
        sql.execute(
            "update platform_ai_effect_suggestion"
                + " set status = ?, decided_by = ?, decided_at = ?::timestamptz,"
                + " decision_note = ?, updated_at = ?::timestamptz"
                + " where id = ? and tenant_id = ? and status = 'OPEN'",
            decision.name(),
            actor.membershipId(),
            now,
            note,
            now,
            id,
            currentTenant());
    check(updated > 0, "这条建议不存在，或已经被决策过了（状态机只允许 OPEN → ACCEPTED / REJECTED）");
    var row =
        sql.fetchOne(
            "select id,capability_code,feature_code,suggestion_type,severity,title,"
                + " current_metric,recommendation,evidence,action_owner,offline_action,"
                + " window_start,window_end,status,decided_by,decided_at,decision_note,"
                + " generated_at,updated_at"
                + " from platform_ai_effect_suggestion where id = ?",
            id);
    if (row == null) notFound("找不到这条建议");
    return toSuggestionRow(row);
  }

  private static AiSuggestionRow toSuggestionRow(org.jooq.Record r) {
    var type = SuggestionType.parse(r.get("suggestion_type", String.class));
    var severity = SuggestionSeverity.parse(r.get("severity", String.class));
    var status = SuggestionStatus.parse(r.get("status", String.class));
    return new AiSuggestionRow(
        r.get("id", UUID.class),
        r.get("capability_code", String.class),
        r.get("feature_code", String.class),
        type == null ? "" : type.name(),
        type == null ? "" : type.label(),
        severity == null ? "" : severity.name(),
        severity == null ? "" : severity.label(),
        r.get("title", String.class),
        r.get("current_metric", String.class),
        r.get("recommendation", String.class),
        r.get("evidence", String.class),
        r.get("action_owner", String.class),
        r.get("offline_action", String.class),
        r.get("window_start", OffsetDateTime.class).toInstant().toString(),
        r.get("window_end", OffsetDateTime.class).toInstant().toString(),
        status == null ? "" : status.name(),
        status == null ? "" : status.label(),
        r.get("decided_by", UUID.class),
        r.get("decided_at", OffsetDateTime.class),
        r.get("decision_note", String.class),
        r.get("generated_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }
}
