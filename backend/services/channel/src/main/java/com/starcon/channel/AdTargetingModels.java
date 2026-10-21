package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 14.1.3.2 投放定位 的传输模型。
 *
 * <p>蓝图：「管理投放定位（地域、人群、设备、时段、匹配类型），支持按定位维度看效果，
 * 指导出价调整。输入：定位设置；定位维度效果数据。输出：定位效果对比表 + 调整建议。
 * 关系：上游 = 14.1.2.1；下游 = 14.2.1.2 分时策略。
 * <b>定位效果差异大时应收窄投放，无效定位的花费是最容易省下来的钱。</b>
 *
 * <p>🔴 这个模型层钉死本模块最容易做丢的五件事：
 *
 * <ol>
 *   <li><b>平台原文与归一定位是两份事实</b>。上游 V281 的 {@code targeting_settings} 是
 *       **平台原文串，不拆解不归一**（它的注释写明「解读归 14.1.3.2」）。
 *       本模块**不���写上游列**，用自己的表承载**解读后的归一定位维度**，
 *       同时**保留原文**（{@code platformRawKey} / {@code platformRawValue}）以便回溯。
 *   <li><b>「归一不出来」要显式说</b>。{@code dimension='UNMAPPED'} 是**显式合法值**
 *       （照抄 2.1.2.1「映射缺失必须显式报未映射，不能默认落到某个兜底状态」），
 *       不是兜底 —— 认不出的键**不丢弃**，落成 UNMAPPED 让运营看得见。
 *   <li><b>指标只有一处来源</b>。定位效果对比表与调整建议的指标**全部**来自
 *       {@code ad_targeting_metric}；销售额为 0 时 ACOS 给 <b>null（不可比）</b>，
 *       不是 0（照抄 V274 同一款纪律）。
 *   <li><b>跨币种不相加</b>。对比表按币种分行，汇总的金额按币种分列；
 *       {@code impressions/clicks/conversions} 是**计数**（与币种无关）才可以相加。
 *   <li><b>调整建议必须带依据</b>。每条建议都带 {@code evidence}（依据数值）+
 *       {@code basis}（口径与阈值）+ {@code actionable}（样本够不够）——
 *       **没有依据的「建议」就是硬编码文案**。
 * </ol>
 *
 * <p>🔴 金额与数量一律 {@link BigDecimal}，**没有 double**：花费与销售额直接进
 * ACOS / ROAS 口径；数量（时段小时数 / 出价系数）语义是 {@code NUMERIC(19,6)}。
 */
public final class AdTargetingModels {
  private AdTargetingModels() {}

  // ================================================================ 能力声明

  public record AdTargetingCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  /**
   * 本模块的阈值与护栏。
   *
   * <p>🔴 建议门槛与系数上下限**一起返回给界面**：运营在点「收窄」之前就该知道
   * 门槛在哪、天花板在哪 —— 建议引擎的阈值不公开，界面就变成黑箱。
   */
  public record AdTargetingGuardrails(
      @Schema(description = "出价系数下限（相对基准）", type = "string") BigDecimal minBidFactor,
      @Schema(description = "出价系数上限（相对基准）", type = "string") BigDecimal maxBidFactor,
      @Schema(description = "🔴 给出建议所需的最小花费（同币种内）", type = "string")
          BigDecimal minSpendForSuggestion,
      @Schema(description = "🔴 给出建议所需的最小展示数") long minImpressionsForSuggestion,
      @Schema(description = "🔴 给出建议所需的最小点击数") long minClicksForSuggestion,
      @Schema(description = "🔴 目标 ACOS（低于它算表现好）", type = "string") BigDecimal targetAcos,
      @Schema(description = "🔴 硬上限 ACOS（超过它算严重超标）", type = "string") BigDecimal hardAcos,
      @Schema(description = "🔴 维度内差异倍数阈值（最差 ACOS ≥ 最佳 × 该倍数 ⇒ 建议收窄）")
          BigDecimal dimensionSpreadFactor,
      @Schema(description = "🔴 一次解读允许的定位维度条数上限") int maxDimensionsPerInterpret,
      @Schema(description = "护栏说明") String note) {}

  public record AdTargetingCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false")
          List<AdTargetingCapabilityDimension> dimensions,
      @Schema(description = "归一后的定位维度全集") List<String> dimensionValues,
      @Schema(description = "定位设置状态全集") List<String> statuses,
      @Schema(description = "🔴 建议门槛与系数护栏") AdTargetingGuardrails guardrails,
      @Schema(description = "🔴 平台原文与归一定位的口径说明") String interpretationNote,
      @Schema(description = "🔴 平台回写未交付的影响说明") String platformWritebackNote,
      @Schema(description = "上游依赖说明") String upstreamNote,
      @Schema(description = "下游与边界说明") String boundaryNote) {}

  // ================================================================ 选项

  public record AdTargetingOption(
      @Schema(description = "取值") String value,
      @Schema(description = "展示名") String label) {}

  public record AdTargetingOptions(
      @Schema(description = "定位维度下拉项") List<AdTargetingOption> dimensions,
      @Schema(description = "状态下拉项") List<AdTargetingOption> statuses,
      @Schema(description = "排序下拉项") List<AdTargetingOption> sorts,
      @Schema(description = "🔴 建议门槛与系数护栏") AdTargetingGuardrails guardrails,
      @Schema(description = "说明") String note) {}

  // ================================================================ 定位设置

  /**
   * 一条定位设置（**归一后**的定位维度）。
   *
   * <p>🔴 {@code groupId} 为空 = **活动级定位**，不是「组丢了」；
   * {@code platformRawValue} 为空 = 平台原文里没有值（人工设置），不是「值是空串」。
   */
  public record AdTargetingSettingRow(
      @Schema(description = "定位设置 id") UUID id,
      @Schema(description = "所属活动 id") UUID campaignId,
      @Schema(description = "活动名") String campaignName,
      @Schema(description = "活动所在店铺编码") String shopCode,
      @Schema(description = "活动币种") String currency,
      @Schema(description = "🔴 所属广告组 id（null = 活动级定位）") UUID groupId,
      @Schema(description = "所属广告组名（null = 活动级定位）") String groupName,
      @Schema(description = "归属层级：CAMPAIGN / GROUP") String scopeKind,
      @Schema(description = "🔴 归一后的定位维度（UNMAPPED = 认不出来的键）") String dimension,
      @Schema(description = "归一后的取值") String dimensionValue,
      @Schema(description = "🔴 平台原文的键") String platformRawKey,
      @Schema(description = "🔴 平台原文的取值") String platformRawValue,
      @Schema(description = "状态：ACTIVE / PAUSED / ARCHIVED") String status,
      @Schema(description = "🔴 出价系数（1 = 不调整；本模块不据此改钱）", type = "string")
          BigDecimal bidFactor,
      @Schema(description = "🔴 时段起始小时（只有 DAYPART 有值）") Integer daypartStartHour,
      @Schema(description = "🔴 时段结束小时（只有 DAYPART 有值）") Integer daypartEndHour,
      @Schema(description = "🔴 时段覆盖小时数（数量，NUMERIC(19,6)）", type = "string")
          BigDecimal daypartHours,
      @Schema(description = "🔴 时段是否跨零点") Boolean daypartCrossesMidnight,
      @Schema(description = "生效起始时刻") OffsetDateTime effectiveFrom,
      @Schema(description = "🔴 生效结束时刻（null = 长期有效）") OffsetDateTime effectiveTo,
      @Schema(description = "🔴 本地已改、尚未回写平台") boolean pendingPlatformWriteback,
      @Schema(description = "🔴 本地最后一次改动时刻（null = 本地没动过）")
          OffsetDateTime localChangedAt,
      @Schema(description = "来源：PLATFORM 由平台原文解读而来 / MANUAL 人工设置") String source,
      @Schema(description = "备注") String note,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record AdTargetingSettingPage(
      @Schema(description = "当前页数据") List<AdTargetingSettingRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  /** 定位设置列表汇总（**不做跨币种金额相加**）。 */
  public record AdTargetingSettingSummary(
      @Schema(description = "定位设置总条数") long total,
      @Schema(description = "覆盖的活动数") long campaignCount,
      @Schema(description = "🔴 各维度条数（维度 → 条数）") Map<String, Long> dimensionCounts,
      @Schema(description = "🔴 各状态条数（状态 → 条数）") Map<String, Long> statusCounts,
      @Schema(description = "🔴 标着「本地已改、尚未回写平台」的条数") long pendingWritebackCount,
      @Schema(description = "🔴 汇总口径说明") String note) {}

  // ================================================================ 定位效果对比表

  /**
   * 定位效果对比表的一行 = **一个定位取值在一个币种下的区间聚合**。
   *
   * <p>🔴 派生比率（CTR / CPC / CVR / ACOS / ROAS）**分母为 0 时是 null**，不是 0 ——
   * 「不可比」与「真的是零」在界面上必须分开（照抄 V274 同一款纪律）。
   */
  public record AdTargetingEffectRow(
      @Schema(description = "活动 id") UUID campaignId,
      @Schema(description = "活动名") String campaignName,
      @Schema(description = "活动所在店铺编码") String shopCode,
      @Schema(description = "🔴 广告组 id（null = 活动级定位）") UUID groupId,
      @Schema(description = "🔴 广告组名（null = 活动级定位）") String groupName,
      @Schema(description = "🔴 归属层级：CAMPAIGN / GROUP") String scopeKind,
      @Schema(description = "定位维度") String dimension,
      @Schema(description = "定位取值") String dimensionValue,
      @Schema(description = "指标币种（🔴 金额只在同币种内可比）") String currency,
      @Schema(description = "展示次数（计数）") long impressions,
      @Schema(description = "点击次数（计数）") long clicks,
      @Schema(description = "转化次数（计数）") long conversions,
      @Schema(description = "花费（金额，NUMERIC(19,4)）", type = "string") BigDecimal spend,
      @Schema(description = "销售额（金额，NUMERIC(19,4)）", type = "string") BigDecimal sales,
      @Schema(description = "点击率（null = 展示为 0，不可比）", type = "string") BigDecimal ctr,
      @Schema(description = "平均点击成本（null = 点击为 0，不可比）", type = "string") BigDecimal cpc,
      @Schema(description = "转化率（null = 点击为 0，不可比）", type = "string") BigDecimal cvr,
      @Schema(description = "🔴 ACOS（null = 销售额为 0，不可比）", type = "string") BigDecimal acos,
      @Schema(description = "🔴 ROAS（null = 花费为 0，不可比）", type = "string") BigDecimal roas,
      @Schema(description = "🔴 无转化花费（转化数为 0 的花费合计，金额）", type = "string")
          BigDecimal wastedSpend,
      @Schema(description = "🔴 无转化花费占花费比重（null = 花费为 0）", type = "string")
          BigDecimal wastedRatio,
      @Schema(description = "🔴 占同维度花费的比重（null = 该维度无花费）", type = "string")
          BigDecimal spendShare,
      @Schema(description = "该取值下的定位设置条数") long settingCount,
      @Schema(description = "🔴 这批指标的数据截至时刻") OffsetDateTime dataAsOf) {}

  /**
   * 定位效果对比表的汇总。
   *
   * <p>🔴 **计数可以相加**（与币种无关），**金额按币种分列** ——
   * 跨币种相加没有意义（那是把美元和人民币加起来）。
   */
  public record AdTargetingEffectSummary(
      @Schema(description = "对比表行数") long rowCount,
      @Schema(description = "覆盖的活动数") long campaignCount,
      @Schema(description = "🔴 各维度行数（维度 → 行数）") Map<String, Long> dimensionCounts,
      @Schema(description = "🔴 各币种花费（币种 → 花费）") Map<String, BigDecimal> spendByCurrency,
      @Schema(description = "🔴 各币种销售额（币种 → 销售额）") Map<String, BigDecimal> salesByCurrency,
      @Schema(description = "展示次数合计（计数，与币种无关）") long impressions,
      @Schema(description = "点击次数合计（计数，与币种无关）") long clicks,
      @Schema(description = "转化次数合计（计数，与币种无关）") long conversions,
      @Schema(description = "🔴 汇总口径说明") String note) {}

  public record AdTargetingEffectPage(
      @Schema(description = "当前页数据") List<AdTargetingEffectRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "🔴 区间汇总（不受分页影响）") AdTargetingEffectSummary summary) {}

  // ================================================================ 调整建议

  /**
   * 一条调整建议。
   *
   * <p>🔴 {@code evidence} 是**依据数值**（每条都带真实数字），{@code basis} 说明
   * **口径与阈值**，{@code actionable=false} 表示**样本不足，不该照做** ——
   * 这三样缺一，建议就退化成硬编码文案。
   */
  public record AdTargetingSuggestion(
      @Schema(description = "规则 id（WASTED_TARGETING / ACOS_OVER_TARGET / UNDERPERFORMING / SCALE_UP / DIMENSION_SPREAD）")
          String ruleId,
      @Schema(description = "规则标题") String ruleTitle,
      @Schema(description = "建议动作：NARROW 收窄投放 / REDUCE_BID 降价 / INCREASE_BID 加价")
          String action,
      @Schema(description = "严重度：HIGH / MEDIUM / LOW") String severity,
      @Schema(description = "活动 id") UUID campaignId,
      @Schema(description = "活动名") String campaignName,
      @Schema(description = "🔴 广告组 id（null = 活动级定位）") UUID groupId,
      @Schema(description = "🔴 广告组名（null = 活动级定位）") String groupName,
      @Schema(description = "定位维度") String dimension,
      @Schema(description = "定位取值") String dimensionValue,
      @Schema(description = "指标币种") String currency,
      @Schema(description = "区间花费（金额）", type = "string") BigDecimal spend,
      @Schema(description = "区间销售额（金额）", type = "string") BigDecimal sales,
      @Schema(description = "展示次数") long impressions,
      @Schema(description = "点击次数") long clicks,
      @Schema(description = "转化次数") long conversions,
      @Schema(description = "🔴 ACOS（null = 销售额为 0，不可比）", type = "string") BigDecimal acos,
      @Schema(description = "🔴 目标 ACOS", type = "string") BigDecimal targetAcos,
      @Schema(description = "🔴 无转化花费（金额）", type = "string") BigDecimal wastedSpend,
      @Schema(description = "🔴 占同维度花费比重（null = 该维度无花费）", type = "string")
          BigDecimal spendShare,
      @Schema(description = "🔴 建议出价系数（null = 本条不给系数）", type = "string")
          BigDecimal bidFactor,
      @Schema(description = "🔴 依据数值（逐条列出真实数字）") List<String> evidence,
      @Schema(description = "🔴 依据口径（规则 + 用了哪些阈值）") String basis,
      @Schema(description = "🔴 是否可执行（false = 样本不足，别照做）") boolean actionable,
      @Schema(description = "说明") String note) {}

  public record AdTargetingSuggestionResult(
      @Schema(description = "建议清单（按严重度 + 浪费额排序）") List<AdTargetingSuggestion> items,
      @Schema(description = "🔴 建议条数") int count,
      @Schema(description = "🔴 可执行条数（样本够）") int actionableCount,
      @Schema(description = "🔴 区间口径说明") String note) {}

  // ================================================================ 时段画像（供下游 14.2.1.2）

  /**
   * 一条时段画像 = **一个时段窗口在区间内的聚合**。
   *
   * <p>🔴 这是本模块**交给下游 14.2.1.2 分时策略的可复用数据**：下游要的就是
   * 「哪个时段值得投、哪个时段在烧钱」。
   */
  public record AdTargetingDaypartRow(
      @Schema(description = "活动 id") UUID campaignId,
      @Schema(description = "活动名") String campaignName,
      @Schema(description = "🔴 广告组 id（null = 活动级定位）") UUID groupId,
      @Schema(description = "🔴 广告组名（null = 活动级定位）") String groupName,
      @Schema(description = "时段取值（原文归一后的值）") String dimensionValue,
      @Schema(description = "时段起始小时（0-23）") Integer startHour,
      @Schema(description = "时段结束小时（0-23）") Integer endHour,
      @Schema(description = "🔴 时段是否跨零点") boolean crossesMidnight,
      @Schema(description = "🔴 时段覆盖小时数（数量）", type = "string") BigDecimal hours,
      @Schema(description = "展示次数") long impressions,
      @Schema(description = "点击次数") long clicks,
      @Schema(description = "转化次数") long conversions,
      @Schema(description = "花费（金额）", type = "string") BigDecimal spend,
      @Schema(description = "销售额（金额）", type = "string") BigDecimal sales,
      @Schema(description = "指标币种") String currency,
      @Schema(description = "🔴 ACOS（null = 销售额为 0，不可比）", type = "string") BigDecimal acos,
      @Schema(description = "🔴 ROAS（null = 花费为 0，不可比）", type = "string") BigDecimal roas) {}

  public record AdTargetingDaypartProfile(
      @Schema(description = "时段画像清单") List<AdTargetingDaypartRow> items,
      @Schema(description = "🔴 下游责任模块") String ownerModule,
      @Schema(description = "🔴 口径说明") String note) {}

  // ================================================================ 详情与留痕

  public record AdTargetingOperationRow(
      @Schema(description = "留痕 id") UUID id,
      @Schema(description = "活动 id") UUID campaignId,
      @Schema(description = "🔴 广告组 id（null = 活动级定位）") UUID groupId,
      @Schema(description = "🔴 目标定位设置 id（null = 批量级动作）") UUID settingId,
      @Schema(description = "写动作") String operation,
      @Schema(description = "变更前快照（紧凑 JSON 原文）") String beforeValue,
      @Schema(description = "变更后快照（紧凑 JSON 原文）") String afterValue,
      @Schema(description = "执行结果：APPLIED / REJECTED / SKIPPED") String result,
      @Schema(description = "逐条结果说明") String message,
      @Schema(description = "操作人（已认证成员）") UUID requestedBy,
      @Schema(description = "操作理由") String reason,
      @Schema(description = "留痕时刻") OffsetDateTime createdAt) {}

  public record AdTargetingOperationPage(
      @Schema(description = "当前页数据") List<AdTargetingOperationRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  /** 定位设置详情。 */
  public record AdTargetingDetail(
      @Schema(description = "🔴 定位设置") AdTargetingSettingRow setting,
      @Schema(description = "🔴 该设置在最近区间的效果（无指标行时为空列表）")
          List<AdTargetingEffectRow> effects,
      @Schema(description = "🔴 最近的定位操作留痕（按时刻倒序）")
          List<AdTargetingOperationRow> recentOperations,
      @Schema(description = "🔴 下游入口（分时策略 / 竞价）") List<AdTargetingEntry> entries,
      @Schema(description = "说明") String note) {}

  /** 一个下游入口。 */
  public record AdTargetingEntry(
      @Schema(description = "入口键") String entry,
      @Schema(description = "展示名") String label,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "实现方 / 责任模块") String source,
      @Schema(description = "下游责任模块（空 = 本模块自己就是责任方）") String ownerModule,
      @Schema(description = "不交付的影响") String impact,
      @Schema(description = "说明") String note) {}

  // ================================================================ 写请求

  /**
   * 新建定位设置。
   *
   * <p>🔴 {@code dimension} 必须是**归一后的维度**（GEO / AUDIENCE / DEVICE /
   * DAYPART / MATCH_TYPE / UNMAPPED）；{@code dimensionValue} 是归一后的取值。
   * 要「把平台原文解读成归一定位」请走 {@link AdTargetingInterpretRequest}。
   */
  public record AdTargetingCreateRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "所属活动 id") @NotNull
          UUID campaignId,
      @Schema(description = "🔴 所属广告组 id（不给 = 活动级定位）") UUID groupId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "归一后的定位维度")
          @NotBlank
          @Size(max = 32)
          String dimension,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "归一后的定位取值")
          @NotBlank
          @Size(max = 200)
          String dimensionValue,
      @Schema(description = "🔴 平台原文的键（保留原文用）") @Size(max = 64) String platformRawKey,
      @Schema(description = "🔴 平台原文的取值（保留原文用）") @Size(max = 500)
          String platformRawValue,
      @Schema(description = "🔴 出价系数（1 = 不调整；留空 = 1）") @PositiveOrZero BigDecimal bidFactor,
      @Schema(description = "生效起始时刻（留空 = 现在）") OffsetDateTime effectiveFrom,
      @Schema(description = "🔴 生效结束时刻（留空 = 长期有效）") OffsetDateTime effectiveTo,
      @Schema(description = "操作理由（写进留痕）") String reason) {}

  /**
   * 改定位设置（**部分更新**：没给的字段不改）。
   *
   * <p>🔴 {@code bidFactor} 给 null = 不改；想恢复成 1（不调整）显式给 1。
   * {@code effectiveTo} 想清空用 {@code clearEffectiveTo=true}（null 与「不改」在 JSON 里分不开）。
   */
  public record AdTargetingUpdateRequest(
      @Schema(description = "归一后的定位取值（不给 = 不改）") @Size(max = 200) String dimensionValue,
      @Schema(description = "状态（不给 = 不改）") @Size(max = 16) String status,
      @Schema(description = "🔴 出价系数（不给 = 不改；给数值 = 设置）") @PositiveOrZero
          BigDecimal bidFactor,
      @Schema(description = "生效起始时刻（不给 = 不改）") OffsetDateTime effectiveFrom,
      @Schema(description = "生效结束时刻（不给 = 不改）") OffsetDateTime effectiveTo,
      @Schema(description = "🔴 显式清空生效结束时刻（长期有效）") Boolean clearEffectiveTo,
      @Schema(description = "备注（不给 = 不改）") @Size(max = 500) String note,
      @Schema(description = "操作理由（写进留痕）") String reason) {}

  /**
   * 解读平台原文定位串。
   *
   * <p>🔴 {@code targetingSettings} 给了就用它；没给但给了 {@code groupId} ⇒
   * **从上游 {@code ad_campaign_group.targeting_settings} 读原文**（这才是
   * 「解读归 14.1.3.2」的真正场景）。
   */
  public record AdTargetingInterpretRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "所属活动 id") @NotNull
          UUID campaignId,
      @Schema(description = "🔴 所属广告组 id（活动级定位不给；给了则原文可从组里读）")
          UUID groupId,
      @Schema(description = "🔴 平台原文定位串（不给 = 从广告组读原文）") String targetingSettings,
      @Schema(description = "🔴 匹配类型原文（不给 = 从广告组读；给了会并入 MATCH_TYPE 维度）")
          @Size(max = 64)
          String matchType,
      @Schema(description = "🔴 是否落库（false / 不给 = 只预览解读结果）") Boolean apply,
      @Schema(description = "操作理由（写进留痕）") String reason) {}

  /** 解读结果里的一条归一定位。 */
  public record AdTargetingInterpretedRow(
      @Schema(description = "归一后的定位维度（UNMAPPED = 认不出来的键）") String dimension,
      @Schema(description = "归一后的取值") String dimensionValue,
      @Schema(description = "平台原文的键") String platformRawKey,
      @Schema(description = "平台原文的取值") String platformRawValue,
      @Schema(description = "🔴 是否识别成功（false = 键或时段窗口认不出来）") boolean recognized,
      @Schema(description = "🔴 时段起始小时（只有 DAYPART 有值）") Integer daypartStartHour,
      @Schema(description = "🔴 时段结束小时（只有 DAYPART 有值）") Integer daypartEndHour,
      @Schema(description = "🔴 时段覆盖小时数（数量，NUMERIC(19,6)）", type = "string")
          BigDecimal daypartHours,
      @Schema(description = "🔴 时段是否跨零点") Boolean daypartCrossesMidnight,
      @Schema(description = "说明") String note) {}

  public record AdTargetingInterpretResult(
      @Schema(description = "🔴 解读出的归一定位清单") List<AdTargetingInterpretedRow> items,
      @Schema(description = "🔴 识别成功的条数") int recognizedCount,
      @Schema(description = "🔴 识别不出来的条数（显式报出，不丢弃）") int unrecognizedCount,
      @Schema(description = "🔴 是否已落库") boolean applied,
      @Schema(description = "🔴 落库的定位设置（apply=true 才有）")
          List<AdTargetingSettingRow> appliedSettings,
      @Schema(description = "🔴 跳过的条数（已存在同归属同维度同取值）") int skippedCount,
      @Schema(description = "说明") String note) {}

  /** 一条定位效果数据。 */
  public record AdTargetingMetricEntry(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "定位设置 id") @NotNull
          UUID settingId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "指标日（UTC 日）") @NotNull
          LocalDate bucketDate,
      @Schema(description = "展示次数") @PositiveOrZero long impressions,
      @Schema(description = "点击次数") @PositiveOrZero long clicks,
      @Schema(description = "转化次数") @PositiveOrZero long conversions,
      @Schema(description = "花费（金额）") @PositiveOrZero BigDecimal spend,
      @Schema(description = "销售额（金额）") @PositiveOrZero BigDecimal sales,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "指标币种") @NotBlank
          @Size(max = 8)
          String currency,
      @Schema(description = "🔴 数据截至时刻（不给 = 现在）") OffsetDateTime syncedAt,
      @Schema(description = "备注") @Size(max = 500) String note) {}

  /** 定位效果数据同步（真实写入口）。 */
  public record AdTargetingMetricBatchRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "指标清单") @NotEmpty
          List<@NotNull AdTargetingMetricEntry> entries,
      @Schema(description = "🔴 批次幂等键（同一批重投不重复落）") @Size(max = 160) String batchKey,
      @Schema(description = "🔴 来源：PLATFORM / API / IMPORT（不给 = PLATFORM）") @Size(max = 16)
          String source,
      @Schema(description = "来源引用（平台侧批次 id 之类）") @Size(max = 160) String sourceRef,
      @Schema(description = "操作理由（写进留痕）") String reason) {}

  public record AdTargetingMetricBatchResult(
      @Schema(description = "🔴 落库的条数") int accepted,
      @Schema(description = "🔴 覆盖写入的条数（同设置同一天已存在）") int replaced,
      @Schema(description = "🔴 被拒的条数") int rejected,
      @Schema(description = "🔴 逐条被拒原因") List<String> rejectedMessages,
      @Schema(description = "🔴 批次幂等键") String batchKey,
      @Schema(description = "🔴 数据截至时刻") OffsetDateTime syncedAt,
      @Schema(description = "说明") String note) {}

  /**
   * 收窄投放（按建议把一条定位置为暂停）。
   *
   * <p>🔴 {@code override=true} 表示「我确认要收窄，但证据不足」——
   * 必须给理由，留痕里会记下这是覆盖操作（审计要能分清「有依据」与「人工决定」）。
   */
  public record AdTargetingNarrowRequest(
      @Schema(description = "操作理由（写进留痕）") String reason,
      @Schema(description = "🔴 采纳的出价系数（不给 = 不改）") @PositiveOrZero BigDecimal bidFactor,
      @Schema(description = "🔴 覆盖证据不足（true = 无浪费证据也要收窄，必须给理由）")
          Boolean override) {}

  public record AdTargetingNarrowResult(
      @Schema(description = "定位设置 id") UUID settingId,
      @Schema(description = "🔴 变更后的状态") String status,
      @Schema(description = "🔴 本地已改、尚未回写平台") boolean pendingPlatformWriteback,
      @Schema(description = "🔴 变更后的出价系数", type = "string") BigDecimal bidFactor,
      @Schema(description = "🔴 依据（覆盖时写明是人工决定）") List<String> evidence,
      @Schema(description = "说明") String note) {}

  /** 归档定位设置。 */
  public record AdTargetingArchiveRequest(
      @Schema(description = "操作理由（写进留痕）") String reason) {}
}
