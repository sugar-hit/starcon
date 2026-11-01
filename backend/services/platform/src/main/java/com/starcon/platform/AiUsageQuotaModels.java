package com.starcon.platform;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 18.4.3.1「使用与配额」的模型（卷 18 → 18.4 AI 治理 → 18.4.3 AI 使用与效果管理）。
 *
 * <p>蓝图原文：「<b>职责</b>：管理 AI 使用：按租户与用户的调用量与成本、配额限制、模型选择、
 * 敏感数据处理策略开关。<b>输入</b>：调用记录；配额配置。<b>输出</b>：用量报表 + 配额执行。
 * <b>关系</b>：上游 = 18.4 全部；下游 = 17.6.1 计费。」
 *
 * <p><b>五条纪律：</b>
 *
 * <ol>
 *   <li><b>请求体里没有 tenantId / userId / membershipId</b>：归属一律由服务端从
 *       <b>已认证会话</b>解析（{@link AccessService#require}）——
 *       客户端自称的归属连字段都不存在（Jackson 全局 fail-on-unknown-properties ⇒ 400）。
 *   <li><b>金额与用量一律十进制文本</b>：{@code String} 承载 {@code NUMERIC(19,6)} 的值，
 *       前端全程不碰浮点（AGENTS.md：禁浮点记账）。只有<b>次数</b>是整数，用 {@code long}。
 *   <li><b>量纲必须显式</b>：{@code unit} 说明「调用量以什么计量」，
 *       同样是 0.002，「每次 0.002」与「每 Token 0.002」差着好几个数量级。
 *   <li><b>单价是快照</b>：台账行里存的是「记这一行那一刻」的目录单价，
 *       目录改价不动历史。
 *   <li><b>超限必须说清是哪一条</b>：超了哪条配额、当前用量、阈值、周期、什么时候重置，
 *       五样缺一不可 —— 只说一句「超配额了」，调用方没法行动。
 * </ol>
 */
public final class AiUsageQuotaModels {

  private AiUsageQuotaModels() {}

  // ================================================================ 口径枚举

  /**
   * 调用量的<b>量纲</b>。
   *
   * <p>🔴 与「次数」严格区分：{@link #REQUEST} 是「调用了几次」，
   * 其余三个是「这次调用消耗了多少内容」。配额里的<b>次数上限</b>数的是调用次数，
   * <b>成本上限</b>数的是钱 —— 两者都不是这个量纲。
   */
  public enum UsageUnit {
    REQUEST("次", "每次调用"),
    TOKEN("Token", "Token 数"),
    THOUSAND_TOKEN("千 Token", "千 Token 数"),
    CHARACTER("字符", "字符数");

    private final String label;
    private final String description;

    UsageUnit(String label, String description) {
      this.label = label;
      this.description = description;
    }

    public String label() {
      return label;
    }

    public String description() {
      return description;
    }

    /** 解析量纲；非法取值返回 {@code null}。 */
    public static UsageUnit parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var u : values()) {
        if (u.name().equalsIgnoreCase(raw.trim())) return u;
      }
      return null;
    }
  }

  /** 配额主体范围。 */
  public enum QuotaScope {
    TENANT("租户级"),
    USER("用户级");

    private final String label;

    QuotaScope(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析主体范围；非法取值返回 {@code null}。 */
    public static QuotaScope parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  /**
   * 配额周期。
   *
   * <p>🔴 边界固定按 <b>UTC 自然日 / 自然月</b>切（见 V425 迁移第 3 节）：
   * 周期边界是跨租户对齐的口径（下游计费按同一口径切账期）。
   */
  public enum QuotaPeriod {
    DAY("日"),
    MONTH("月");

    private final String label;

    QuotaPeriod(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析周期；非法取值返回 {@code null}。 */
    public static QuotaPeriod parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var p : values()) {
        if (p.name().equalsIgnoreCase(raw.trim())) return p;
      }
      return null;
    }
  }

  /** 敏感数据处理动作。 */
  public enum SensitiveAction {
    BLOCK("禁传"),
    MASK("脱敏"),
    ALLOW("允许");

    private final String label;

    SensitiveAction(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析处理动作；非法取值返回 {@code null}。 */
    public static SensitiveAction parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var a : values()) {
        if (a.name().equalsIgnoreCase(raw.trim())) return a;
      }
      return null;
    }
  }

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了归谁」。 */
  public record AiUsageCapability(
      String module,
      String name,
      boolean available,
      String basis,
      String ownerModule,
      String impact) {}

  /** 下游缺口：谁没交付、归谁。 */
  public record AiUsageGap(
      String module,
      String name,
      String ownerService,
      boolean available,
      String group,
      String detail) {}

  /** 18.4.3.1 的能力声明。 */
  public record AiUsageCapabilities(
      List<AiUsageCapability> capabilities, List<AiUsageGap> downstreamGaps) {}

  // ================================================================ 模型目录

  /** 模型目录的一行。 */
  public record AiModelRow(
      UUID id,
      String modelCode,
      String modelName,
      String provider,
      String unit,
      String unitLabel,
      String unitDescription,
      String unitCost,
      String currency,
      int contextLimit,
      boolean enabled,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 登记 / 修改一个模型的入参。
   *
   * <p>🔴 <b>没有 tenantId / userId / membershipId</b>：归属来自已认证会话。
   */
  public record AiModelInput(
      String modelCode,
      String modelName,
      String provider,
      String unit,
      String unitCost,
      String currency,
      @Schema(nullable = true) Integer contextLimit,
      @Schema(nullable = true) Boolean enabled,
      String note) {}

  // ================================================================ 用量台账

  /** 用量台账的一行。 */
  public record AiUsageRecordRow(
      UUID id,
      UUID membershipId,
      String capabilityCode,
      String featureCode,
      String modelCode,
      String unit,
      String unitLabel,
      String quantity,
      String unitCost,
      String cost,
      String currency,
      String requestRef,
      OffsetDateTime occurredAt) {}

  /**
   * 记一次用量的入参。
   *
   * <p>🔴 <b>没有 tenantId / userId / membershipId</b>：主体成员由服务端从会话解析。
   *
   * @param quantity 调用量（量纲由模型目录决定，服务端会校验它与目录一致）
   */
  public record AiUsageRecordInput(
      String capabilityCode,
      String featureCode,
      String modelCode,
      String quantity,
      String requestRef) {}

  /** 用量台账（真分页 + 真筛选）。 */
  public record AiUsageRecordPage(
      List<AiUsageRecordRow> items, int page, int size, long total) {}

  // ================================================================ 用量报表

  /**
   * 报表的一行：<b>能力 × 模型 × 币种</b>。
   *
   * <p>🔴 **这是可计费粒度**：成本只在「同一模型 + 同一币种」内可比。
   * 把不同模型的 Token 数与不同币种的钱加在一起得到一个总数，
   * 那个数**没有业务含义** —— 所以本模块不做这种合计。
   */
  public record AiUsageSummaryRow(
      String capabilityCode,
      String modelCode,
      String modelName,
      String unit,
      String unitLabel,
      String currency,
      long calls,
      String quantity,
      String cost) {}

  /** 报表的一行：<b>成员 × 币种</b>。 */
  public record AiUsageMemberRow(UUID membershipId, String currency, long calls, String cost) {}

  /** 报表的一行：<b>币种 → 成本合计</b>（不同币种**不混算**）。 */
  public record AiUsageCurrencyRow(String currency, long calls, String cost) {}

  /**
   * 用量报表（**可计费口径**）。
   *
   * @param byCapability 能力 × 模型 × 币种 —— 下游计费直接取这一层的口径
   * @param billableBasis 计费口径说明：这批数字**怎么**可以拿去算钱
   */
  public record AiUsageSummary(
      List<AiUsageSummaryRow> byCapability,
      List<AiUsageMemberRow> byMember,
      List<AiUsageCurrencyRow> byCurrency,
      long totalCalls,
      String periodStart,
      String periodEnd,
      String billableBasis) {}

  // ================================================================ 配额

  /** 配额配置的一行（**含当前周期用量与判定**）。 */
  public record AiQuotaRow(
      UUID id,
      String scope,
      String scopeLabel,
      @Schema(nullable = true) UUID membershipId,
      String capabilityCode,
      boolean countConfigured,
      long limitCount,
      boolean costConfigured,
      String limitCost,
      String currency,
      String period,
      String periodLabel,
      boolean enabled,
      String note,
      long usedCount,
      String usedCost,
      boolean exceeded,
      String periodStart,
      String periodEnd,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 登记 / 修改一条配额的入参（**租户级**或**用户级**共用同一个形状）。
   *
   * <p>🔴 <b>没有 membershipId</b>：用户级配额的主体成员来自
   * 「服务端解析的会话成员」（自助）或「路径里指定的成员」（管理员代配）。
   *
   * @param currency 成本上限的币种；不传用 USD。不同币种**不混算** ——
   *     币种不符的调用照计次数、不计成本。
   */
  public record AiQuotaInput(
      String capabilityCode,
      @Schema(nullable = true) Long limitCount,
      String limitCost,
      @Schema(nullable = true) String currency,
      String period,
      @Schema(nullable = true) Boolean enabled,
      String note) {}
  // ================================================================ 配额判定

  /**
   * 一条**被越线**的配额。
   *
   * <p>🔴 五个要素缺一不可：超了哪一条、当前用量、阈值、周期、什么时候重置。
   */
  public record AiQuotaViolation(
      UUID quotaId,
      String scope,
      String scopeLabel,
      @Schema(nullable = true) UUID membershipId,
      String capabilityCode,
      String limitKind,
      String limitValue,
      String currentUsage,
      String period,
      String periodLabel,
      String periodStart,
      String periodEnd,
      String resetAt,
      String message) {}

  /**
   * 配额判定结果。
   *
   * @param mode 判定口径：{@code POST_RECORD} = 记用量时的**权威**判定（原子递增之后）；
   *     {@code PREFLIGHT} = 调用前的**预检快照**（读当前计数，权威判定仍以记用量那一次为准）
   */
  public record AiQuotaEvaluation(
      boolean allowed, String mode, List<AiQuotaViolation> violations, String evaluatedAt) {}

  /**
   * 配额预检的入参。
   *
   * <p>🔴 <b>没有 membershipId</b>：预检的主体是**调用方自己**（会话成员）。
   */
  public record AiQuotaCheckInput(
      String capabilityCode, String modelCode, String quantity, @Schema(nullable = true) Long callCount) {}

  // ================================================================ 敏感数据策略

  /** 敏感数据策略的一行。 */
  public record AiSensitivePolicyRow(
      UUID id,
      String capabilityCode,
      String modelCode,
      String action,
      String actionLabel,
      boolean enabled,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 登记 / 修改一条敏感数据策略的入参。
   *
   * @param modelCode 空串 = 全部模型
   */
  public record AiSensitivePolicyInput(
      String capabilityCode, String modelCode, String action, @Schema(nullable = true) Boolean enabled, String note) {}

  /**
   * **生效**的那条策略（调用方在真正调用 AI 之前查的就是它）。
   *
   * @param configured 库里**是否**登记过这条策略（false = 没有登记，落到默认档）
   * @param basis 这一格的依据（默认档时写明默认是什么、为什么）
   */
  public record AiSensitivePolicyView(
      String capabilityCode,
      String modelCode,
      String action,
      String actionLabel,
      boolean configured,
      String basis) {}

  // ================================================================ 记用量的结果

  /**
   * 记一次用量的结果：**台账行 + 配额判定**。
   *
   * @param evaluation 这次记账之后的**权威**配额判定（原子递增之后读到的值）
   */
  public record AiUsageRecordResult(AiUsageRecordRow record, AiQuotaEvaluation evaluation) {}
}
