package com.starcon.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 5.1.2.1 优先级与冲突检测。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：定义多张价目表的适用优先级，保存时检测冲突（同 SKU + 同店铺 + 同站点 + 同时段生效的多个价目），冲突即拒绝并列出冲突明细。
 *
 * **输入**：适用范围配置（法人/店铺/站点）；现有生效价目集合。
 *
 * **输出**：冲突检测结果；最终的生效价目解析规则。
 *
 * **关系**：上游 = 5.1.1.1；下游 = 5.3.1.1、5.4 调价。**这是价格正确性的守门员**：允许多价目但必须能唯一解析出"这个 SKU 在这个店铺此刻该卖多少"。
 *
 * </blockquote>
 *
 * <h2>🔴 本类的五条纪律</h2>
 *
 * <ol>
 *   <li><b>🔴 优先级是租户可配置的</b>，不是代码常量。它以行存在
 *       （{@code price_list_priority}），各租户的销售场景不同 —— 同一套代码不能替
 *       不同租户排先后。
 *   <li><b>🔴 冲突明细必须逐条可解释</b>：哪两张价目表、哪个店铺、哪个站点、
 *       生效区间重叠在哪、两边各自的有效优先级是多少 —— 缺一项运营就没法改。
 *   <li><b>🔴 冲突即拒绝</b>：保存时检出冲突就**不保存**并把明细退回，
 *       不是「保存后打标」。
 *   <li><b>🔴 同优先级 ⇒ 不可解析</b>：绝不随机取一条（蓝图 5.1.1.2 关系句同款要求）。
 *   <li><b>🔴 同 SKU 这一维度未覆盖</b>：价目项表 {@code price_list_item} 是
 *       **5.1.1.2 的写入所有权**，尚未交付 ⇒ 本模块只做**价目级**检测，
 *       并且在能力声明 / 冲突报告 / 解析结果三处**如实声明**未覆盖与原因
 *       （不许假装检测了，也不许静默返回「无冲突」）。
 * </ol>
 */
public final class PriceListPriorityModels {

  private PriceListPriorityModels() {}

  // ================================================================ 词表

  /**
   * 优先级规则的上下文类型。
   *
   * <p>{@link #GLOBAL} 是该价目表的**默认优先级**（所有上下文生效）；
   * 其余三个沿用 5.1.1.1 的适用范围词表（法人 / 店铺 / 站点）。
   */
  public enum PriorityScopeType {
    /** 默认优先级：适用于该价目表覆盖的所有上下文。 */
    GLOBAL("全局"),
    /** 法人：17.1.2.2 identity 的 legal_entity 编码。 */
    LEGAL_ENTITY("法人"),
    /** 店铺：2.3.1.1 channel 的 shop 编码。 */
    SHOP("店铺"),
    /** 站点：2.1.1.1 channel 的 marketplace（站点）码。 */
    SITE("站点");

    private final String label;

    PriorityScopeType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static PriorityScopeType parse(String raw) {
      if (raw == null) return null;
      for (var t : values()) {
        if (t.name().equalsIgnoreCase(raw.trim())) return t;
      }
      return null;
    }
  }

  /**
   * 冲突检测的维度。
   *
   * <p>蓝图的判据是四个维度：**同 SKU / 同店铺 / 同站点 / 同时段生效**。
   * 另加两个为了让明细可解释而拆出来的维度：{@link #LEGAL_ENTITY}（适用范围里的法人层）
   * 与 {@link #PRIORITY}（同优先级 ⇒ 不可唯一解析）。
   */
  public enum ConflictDimension {
    /** 同 SKU：需要价目项（5.1.1.2）才能判定 ⇒ 本模块未覆盖。 */
    SKU("同 SKU", false),
    /** 同店铺：两张价目表覆盖同一个店铺。 */
    SHOP("同店铺", true),
    /** 同站点：两张价目表覆盖同一个站点。 */
    SITE("同站点", true),
    /** 同法人：两张价目表覆盖同一个法人。 */
    LEGAL_ENTITY("同法人", true),
    /** 同时段生效：两张价目表的生效区间有交集。 */
    TIME("同时段生效", true),
    /** 同优先级：两边的有效优先级相同 ⇒ 不可唯一解析。 */
    PRIORITY("同优先级", true);

    private final String label;

    /** 这个维度在本模块里是否**可判定**（{@link #SKU} 为 false：上游未交付）。 */
    private final boolean covered;

    ConflictDimension(String label, boolean covered) {
      this.label = label;
      this.covered = covered;
    }

    public String label() {
      return label;
    }

    public boolean covered() {
      return covered;
    }
  }

  /** 冲突被排除时的原因。 */
  public enum ExclusionReason {
    /** 未配置优先级 ⇒ 不参与解析（不是「没有价格」，是「没有排进这场赛跑」）。 */
    NO_PRIORITY("未配置优先级"),
    /** 此刻不生效（生效区间不含该时刻）。 */
    NOT_EFFECTIVE("此刻不生效"),
    /** 已停用。 */
    DISABLED("已停用");

    private final String label;

    ExclusionReason(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 输入

  /**
   * 一条优先级规则（输入）。
   *
   * <p>🔴 {@code scopeRef} 在 {@link PriorityScopeType#GLOBAL} 时固定写 {@code *}；
   * 服务层会归一化并拒绝矛盾写法。
   */
  public record PriorityRuleInput(
      @NotNull PriorityScopeType scopeType,
      @NotBlank String scopeRef,
      @NotNull @Min(0) @Max(9999) Integer priority,
      String note) {}

  /**
   * 保存某张价目表的优先级规则（整体替换）。
   *
   * <p>🔴 **必须含且只含一条 GLOBAL 规则**：没有它，价目表在某个上下文里就没有优先级
   * ⇒ 它不参与解析（服务层拒绝这种形状）。
   */
  public record PriorityRulesInput(@Valid List<PriorityRuleInput> rules) {}

  /**
   * 解析请求：这个 SKU 在这个店铺此刻该卖多少。
   *
   * <p>🔴 {@code sku} 是**可选**的：它落到哪张价目表上要看价目项
   * （5.1.1.2 未交付 ⇒ 未覆盖）。填了它，响应里会明确写「SKU 维度未覆盖及原因」。
   */
  public record ResolveRequest(
      @NotBlank String shopCode,
      @Schema(nullable = true) String siteCode,
      @Schema(nullable = true) String sku,
      /** 判定时刻（空 = 数据库时钟 now()）。生效区间是半开区间 [起点, 终点)。 */
      @Schema(nullable = true) OffsetDateTime at) {}

  // ================================================================ 输出

  /** 一条优先级规则（读）。 */
  public record PriorityRuleView(
      UUID id,
      UUID priceListId,
      String priceListCode,
      String priceListName,
      PriorityScopeType scopeType,
      String scopeTypeLabel,
      String scopeRef,
      /** 🔴 数字越小优先级越高。 */
      int priority,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 一条冲突明细。
   *
   * <p>🔴 「哪两张价目表、哪个店铺、哪个站点、生效区间重叠在哪、两边各是什么优先级」
   * —— 这些字段缺一项，运营就没法改。
   */
  public record ConflictDetail(
      /** 命中的维度（逐条独立列出）。 */
      List<ConflictDimension> dimensions,
      /** 左侧价目表。 */
      UUID leftPriceListId,
      String leftPriceListCode,
      String leftPriceListName,
      /** 右侧价目表。 */
      UUID rightPriceListId,
      String rightPriceListCode,
      String rightPriceListName,
      @Schema(nullable = true) String shopCode,
      @Schema(nullable = true) String siteCode,
      @Schema(nullable = true) String legalEntityCode,
      /** 生效区间重叠的那一段（半开区间 [from, to)）。 */
      OffsetDateTime overlapFrom,
      @Schema(nullable = true) OffsetDateTime overlapTo,
      /** 两边在该上下文里的有效优先级（空 = 未配置优先级 ⇒ 不参与解析）。 */
      @Schema(nullable = true) Integer leftPriority,
      @Schema(nullable = true) Integer rightPriority,
      /** 一句话说清为什么算冲突。 */
      String reason,
      /** true = 阻断保存（同优先级 ⇒ 不可唯一解析）。 */
      boolean blocking) {}

  /**
   * 一个维度的覆盖说明。
   *
   * <p>🔴 {@code covered=false} 的维度必须给出**原因** —— 不许让界面猜。
   */
  public record DimensionCoverage(
      ConflictDimension dimension,
      String label,
      boolean covered,
      String detail) {}

  /** 冲突检测结果（蓝图输出句：「冲突检测结果」）。 */
  public record ConflictReport(
      @Schema(nullable = true) UUID priceListId,
      @Schema(nullable = true) String priceListCode,
      /** 明细条数（去重后的配对 × 上下文）。 */
      int conflictCount,
      /** 其中会阻断保存的条数。 */
      int blockingCount,
      List<ConflictDetail> conflicts,
      List<DimensionCoverage> dimensionCoverage,
      /** 同 SKU 维度未覆盖的原因（未交付上游，不是「没有冲突」）。 */
      String skuDimensionNote,
      /** 本租户里已配置优先级的价目表数。 */
      int rankedPriceListCount,
      /** 明细是否被截断（超出上限时为 true，同时给出真实总数）。 */
      boolean truncated,
      /** 冲突配对总数（与 {@link #conflictCount} 可能不同：这里按「两张表」计数）。 */
      int pairCount,
      String note) {}

  /** 解析命中的价目表。 */
  public record ResolvedPriceList(UUID id, String code, String name, String currency) {}

  /**
   * 解析依据：命中了哪条优先级规则。
   *
   * <p>🔴 「为什么是这张表」必须能说清 —— 只回一个价目表 id 的话，
   * 运营没法判断这个结果对不对。
   */
  public record ResolutionBasis(
      int priority,
      @Schema(nullable = true) UUID ruleId,
      PriorityScopeType ruleScopeType,
      String ruleScopeLabel,
      String ruleScopeRef,
      String explanation) {}

  /** 参与解析的候选价目表。 */
  public record ResolutionCandidate(
      UUID priceListId,
      String priceListCode,
      String priceListName,
      String currency,
      int priority,
      PriorityScopeType ruleScopeType,
      String ruleScopeLabel,
      String ruleScopeRef,
      OffsetDateTime validFrom,
      @Schema(nullable = true) OffsetDateTime validTo,
      boolean selected) {}

  /** 被排除的价目表（附排除原因 —— 排除也要可解释）。 */
  public record ExcludedPriceList(
      UUID priceListId,
      String priceListCode,
      String priceListName,
      ExclusionReason reason,
      String reasonLabel,
      String detail) {}

  /**
   * SKU 维度的覆盖情况。
   *
   * <p>🔴 {@code covered=false} 时 {@code reason} 必须写清为什么（上游未交付）。
   */
  public record SkuCoverage(boolean covered, @Schema(nullable = true) String sku, String reason) {}

  /** 解析结果（蓝图输出句：「最终的生效价目解析规则」）。 */
  public record Resolution(
      boolean resolved,
      /** 解析不出来时的一句话原因。 */
      String reason,
      @Schema(nullable = true) ResolvedPriceList priceList,
      @Schema(nullable = true) ResolutionBasis basis,
      List<ResolutionCandidate> candidates,
      List<ExcludedPriceList> excluded,
      SkuCoverage skuCoverage,
      String shopCode,
      @Schema(nullable = true) String siteCode,
      @Schema(nullable = true) String legalEntityCode,
      /** 判定时刻（ISO-8601，带偏移）。 */
      String at) {}

  /** 保存优先级规则的结果。 */
  public record PriorityRulesSaved(
      UUID priceListId,
      String priceListCode,
      List<PriorityRuleView> rules,
      ConflictReport report) {}

  /** 全租户冲突概览。 */
  public record TenantConflictOverview(
      int rankedPriceListCount,
      int priceListCount,
      int conflictPairCount,
      int blockingPairCount,
      List<ConflictDetail> conflicts,
      List<DimensionCoverage> dimensionCoverage,
      String skuDimensionNote,
      String note) {}

  // ================================================================ 能力声明

  /** 能力声明里的一条口径说明。 */
  public record PriorityNote(String title, String content) {}

  /** 能力声明。页面顶部固定显示它。 */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record PriceListPriorityCapabilities(
      List<PriorityScopeType> scopeTypes,
      List<PriorityNote> notes,
      List<DimensionCoverage> dimensionCoverage,
      String prioritySemantics,
      String tieNote,
      String scopeNote,
      String upstreamNote,
      String downstreamNote,
      String skuNote) {}
}
