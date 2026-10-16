package com.starcon.channel;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 14.1.2.2 广告类型差异 的传输模型。
 *
 * <p>蓝图：「适配不同平台与广告类型的字段差异（自动广告、SB/SD、品牌广告、DSP、大促广告），把差异字段归一到内部
 * 模型展示。输入：各平台广告类型定义；平台 API 返回。输出：归一后的广告类型视图 + 特有字段补充展示。
 * 关系：上游 = 2.1.2.1 平台映射；下游 = 14.1.1.1。<b>归一化让跨平台广告可比</b>，但<b>必须保留原始类型标记，不能丢
 * 信息</b>。」
 *
 * <p>载荷一律用具名 {@code record}，不用 {@code Map<String,Object>}：Map 的值类型会被 springdoc 推成
 * object，codegen 出来是空对象 {@code {}}（9.6.1.3 已踩过）。
 *
 * <p>🔴 本模块最容易被做丢的一件事，在模型层就钉死：**归一后的类型与原始类型标记必须同表并存**。
 * 下面每一个承载广告数据的 record 都同时带 {@code platformAdType}（原始标记）与
 * {@code internalAdType}（归一后类型），谁也不能只给后者 —— 只给归一类型，「平台 API 原文是什么」就永远
 * 答不出来了，而那正是蓝图加粗句要保住的东西。
 *
 * <p>🔴 金额与数量一律 {@link BigDecimal}，**没有 double**：花费 / 出价 / 预算是钱，展示 / 点击 / 转化与
 * CTR / CVR / ACOS 是跨平台可比的口径，它们直接参与 14.3.1.1 报表与归因，浮点不可接受。
 *
 * <p>🔴 两处「必须分开显示」的语义在类型层面就分开：
 *
 * <ol>
 *   <li>{@code internalAdType == "UNMAPPED"}（2.1.2.1 两层都没查到）<b>不等于</b>「没有这种广告」—— 它存在，
 *       带完整原始标记，但不参与任何跨平台可比聚合。
 *   <li>{@code mappingStatus == "PARTIAL_FIELD_COVERAGE"}<b>不等于</b>「归一失败」—— 类型是归一了的，只是有
 *       字段没有内部落点、原样留在原始报文里。界面上必须列出那些字段，否则「不能丢信息」就只剩一句口号。
 * </ol>
 */
public final class AdTypeDiffModels {
  private AdTypeDiffModels() {}

  // ================================================================ 能力声明

  public record AdTypeCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  public record AdTypeDiffCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false") List<AdTypeCapabilityDimension> dimensions,
      @Schema(description = "归一后的内部广告类型全集（UNMAPPED 是显式值，不是兜底）") List<String> internalAdTypes,
      @Schema(description = "归一口径来源：走 2.1.2.1 的哪一层") List<String> mappingSources,
      @Schema(description = "拍卖方式取值") List<String> auctionModels,
      @Schema(description = "投放目标取值") List<String> objectives,
      @Schema(description = "特有字段的数据类型") List<String> fieldDataTypes,
      @Schema(description = "保真状态取值") List<String> mappingStatuses,
      @Schema(description = "归一与保真的总口径") String normalizationNote,
      @Schema(description = "「不能丢信息」的物理落点说明") String fidelityNote,
      @Schema(description = "「未映射不兜底」的说明（照抄 2.1.2.1 加粗句）") String unmappedNote,
      @Schema(description = "上游依赖说明") String upstreamNote) {}

  // ================================================================ 广告类型档案

  public record AdTypeProfileRow(
      @Schema(description = "档案 id") UUID id,
      @Schema(description = "平台 id（上游 2.1.1.1 平台目录）") UUID platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "平台名称") String platformName,
      @Schema(description = "🔴 原始平台类型标记：平台怎么叫就怎么存") String platformAdType,
      @Schema(description = "🔴 归一后的内部类型；UNMAPPED 表示 2.1.2.1 两层都没查到") String internalAdType,
      @Schema(description = "展示名") String displayName,
      @Schema(description = "拍卖方式（跨平台可比的关键口径）") String auctionModel,
      @Schema(description = "投放目标（跨平台可比的关键口径）") String objective,
      @Schema(description = "是否支持定向") boolean supportsTargeting,
      @Schema(description = "是否支持分时竞价") boolean supportsBidSchedule,
      @Schema(description = "是否支持否定关键词") boolean supportsNegativeKw,
      @Schema(description = "是否支持创意素材") boolean supportsCreative,
      @Schema(description = "是否支持 ASCII 广告位") boolean supportsAsciiAd,
      @Schema(description = "🔴 归一口径来源：PLATFORM_DEFAULT / TENANT_OVERRIDE / UNMAPPED") String mappingSource,
      @Schema(description = "上游 2.1.2.1 的枚举族名（本模块固定 AD_TYPE）") String enumKind,
      @Schema(description = "该档案的特有字段定义条数") int fieldSpecCount,
      @Schema(description = "该档案已归一的广告条数") int payloadCount,
      @Schema(description = "备注") String note,
      @Schema(description = "状态") String status,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record AdTypeProfilePage(
      @Schema(description = "当前页数据") List<AdTypeProfileRow> items,
      @Schema(description = "符合筛选条件的总条数（不受分页影响）") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  // ================================================================ 特有字段定义

  public record AdTypeFieldSpecRow(
      @Schema(description = "字段定义 id") UUID id,
      @Schema(description = "所属档案 id") UUID adTypeProfileId,
      @Schema(description = "🔴 原始字段键（与原始报文里的顶层键同名字面）") String fieldKey,
      @Schema(description = "界面上的中文标签") String fieldLabel,
      @Schema(description = "数据类型") String dataType,
      @Schema(description = "展示单位（标签，不做单位换算）") String unit,
      @Schema(description = "ENUM 型的候选值（逗号分隔的数据）") String enumValues,
      @Schema(description = "🔴 是否参与跨平台可比") boolean comparable,
      @Schema(description = "🔴 归一后的内部落点；为空表示该字段只能原样留在原始报文里") String internalRepresentation,
      @Schema(description = "录入是否必填") boolean requiredOnInput,
      @Schema(description = "展示顺序") int position,
      @Schema(description = "备注") String note,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  /** 档案详情：档案本体 + 它的全部特有字段定义 + 尚未被任何字段定义覆盖的原始键。 */
  public record AdTypeProfileDetail(
      @Schema(description = "档案") AdTypeProfileRow profile,
      @Schema(description = "🔴 特有字段定义（驱动「特有字段补充展示」）") List<AdTypeFieldSpecRow> fieldSpecs,
      @Schema(
              description =
                  "🔴 原始报文里出现、但没有任何字段定义覆盖的键 —— 它们会被保真审计列成"
                      + " raw_only_field_keys，界面上必须显式告诉运营「系统没有归一这些字段」")
          List<String> undocumentedKeys,
      @Schema(description = "说明") String note) {}

  // ================================================================ 归一后的广告数据

  public record AdTypePayloadRow(
      @Schema(description = "广告数据 id") UUID id,
      @Schema(description = "档案 id") UUID adTypeProfileId,
      @Schema(description = "🔴 原始平台类型标记") String platformAdType,
      @Schema(description = "🔴 归一后的内部类型") String internalAdType,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "平台原始类型展示名") String displayName,
      @Schema(description = "拍卖方式") String auctionModel,
      @Schema(description = "投放目标") String objective,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "平台侧广告 id") String externalAdId,
      @Schema(description = "平台侧广告组 id") String externalCampaignId,
      @Schema(description = "广告名称") String adName,
      @Schema(description = "归一后的状态（跨平台可比）") String adStatus,
      @Schema(description = "币种") String currency,
      @Schema(description = "日预算", type = "string") BigDecimal dailyBudget,
      @Schema(description = "默认出价", type = "string") BigDecimal defaultBid,
      @Schema(description = "出价下限", type = "string") BigDecimal bidFloor,
      @Schema(description = "开始时间") OffsetDateTime startAt,
      @Schema(description = "结束时间") OffsetDateTime endAt,
      @Schema(description = "展示量", type = "string") BigDecimal impressions,
      @Schema(description = "点击量", type = "string") BigDecimal clicks,
      @Schema(description = "转化量", type = "string") BigDecimal conversions,
      @Schema(description = "花费", type = "string") BigDecimal spend,
      @Schema(description = "归因销售额", type = "string") BigDecimal attributedRevenue,
      @Schema(description = "🔴 点击率（派生列；分母为 0 时为 null=不可比，与 0 含义相反）", type = "string")
          BigDecimal ctr,
      @Schema(description = "🔴 单次点击成本（派生列）", type = "string") BigDecimal cpc,
      @Schema(description = "🔴 转化率（派生列）", type = "string") BigDecimal cvr,
      @Schema(description = "🔴 ACOS（派生列）", type = "string") BigDecimal acos,
      @Schema(description = "🔴 已归一的键（保真审计的一部分）") List<String> normalizedFieldKeys,
      @Schema(
              description =
                  "🔴 只留在原始报文里、没有内部落点的键（由 DB 触发器从原始报文派生，服务层传什么都不作数）")
          List<String> rawOnlyFieldKeys,
      @Schema(description = "🔴 保真状态") String mappingStatus,
      @Schema(description = "原始报文抓取时刻") OffsetDateTime rawFetchedAt,
      @Schema(description = "归一入库时刻") OffsetDateTime ingestedAt,
      @Schema(description = "来源凭据") String sourceRef,
      @Schema(description = "备注") String note,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  /**
   * 特有字段补充展示（单条广告一行）。
   *
   * <p>🔴 由<b>服务端</b>组装好，前端只渲染：它要同时给出「原始报文里的原值」与「归一后的内部落点值」，
   * 任何一侧在前端自己找都会让两端各有一套拼装逻辑。
   */
  public record ExtraFieldView(
      @Schema(description = "原始字段键") String fieldKey,
      @Schema(description = "界面标签") String fieldLabel,
      @Schema(description = "数据类型") String dataType,
      @Schema(description = "展示单位") String unit,
      @Schema(description = "🔴 原始报文里的原值（平台原样，未加工）") String rawValue,
      @Schema(description = "🔴 该字段是否有内部落点") boolean normalized,
      @Schema(
              description =
                  "🔴 内部落点的列名；为空表示系统没有归一这个字段，它只原样存在于原始报文里")
          String internalRepresentation,
      @Schema(description = "解释：这一行为什么可比 / 不可比") String comparabilityNote) {}

  public record AdTypePayloadDetail(
      @Schema(description = "归一后的广告数据") AdTypePayloadRow payload,
      @Schema(description = "🔴 原始差异字段原文（平台 API 返回，一个字都没改）") JsonNode rawPayload,
      @Schema(description = "🔴 特有字段补充展示（服务端已组装，前端只渲染）") List<ExtraFieldView> extraFields,
      @Schema(
              description =
                  "🔴 原始报文里没有任何字段定义覆盖的键 —— 未定义就是「系统不认识它」，"
                      + "必须原样露出而不是悄悄忽略")
          List<String> undocumentedKeys,
      @Schema(description = "保真说明") String fidelityNote) {}

  public record AdTypePayloadPage(
      @Schema(description = "当前页数据") List<AdTypePayloadRow> items,
      @Schema(description = "符合筛选条件的总条数（不受分页影响）") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "其中 UNMAPPED_TYPE 的条数：这些不参与任何可比聚合") long unmappedCount,
      @Schema(description = "其中 PARTIAL_FIELD_COVERAGE 的条数：有字段只留在原始报文里") long partialCount) {}

  // ================================================================ 跨平台可比视图（归一后的广告类型视图）

  /** 一个归一后内部类型在某租户下的可比汇总（**已排除 UNMAPPED 类型**）。 */
  public record ComparableTypeSummary(
      @Schema(description = "🔴 归一后的内部类型") String internalAdType,
      @Schema(description = "该类型下的广告条数") long adCount,
      @Schema(description = "覆盖的平台数（归一后才有的维度）") int platformCount,
      @Schema(description = "各平台的原始类型标记 —— 归一让它们可比，但标记必须一并留着") List<PlatformTypeMarker> markers,
      @Schema(description = "拍卖方式（同一内部类型下应当一致，不一致说明归一口径有问题）") List<String> auctionModels,
      @Schema(description = "投放目标") List<String> objectives,
      @Schema(description = "展示量合计", type = "string") BigDecimal impressions,
      @Schema(description = "点击量合计", type = "string") BigDecimal clicks,
      @Schema(description = "转化量合计", type = "string") BigDecimal conversions,
      @Schema(description = "花费合计", type = "string") BigDecimal spend,
      @Schema(description = "归因销售额合计", type = "string") BigDecimal attributedRevenue,
      @Schema(description = "🔴 汇总口径说明：为什么这些数可以横向比") String comparabilityNote) {}

  /** 平台 + 原始类型标记（归一视图里必须成对出现，否则「原始标记」这一环就丢了）。 */
  public record PlatformTypeMarker(
      @Schema(description = "平台 id") UUID platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "🔴 该平台上的原始类型标记") String platformAdType,
      @Schema(description = "该平台上的展示名") String displayName,
      @Schema(description = "该平台该类型下的广告条数") long adCount) {}

  public record ComparableView(
      @Schema(description = "按归一后内部类型聚合的可比汇总（不含 UNMAPPED）")
          List<ComparableTypeSummary> types,
      @Schema(
              description =
                  "🔴 未映射的原始类型清单：这些广告**存在且带完整原始标记**，只是 2.1.2.1 没有归一口径，"
                      + "因此不参与上面的横向比较")
          List<UnmappedTypeSummary> unmappedTypes,
      @Schema(description = "可比口径总说明") String note) {}

  public record UnmappedTypeSummary(
      @Schema(description = "平台 id") UUID platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "🔴 原始类型标记") String platformAdType,
      @Schema(description = "展示名") String displayName,
      @Schema(description = "该原始类型下的广告条数") long adCount,
      @Schema(
              description =
                  "🔴 为什么没进可比表：上游 2.1.2.1 的 platform_enum_mapping / platform_enum_override"
                      + " 都没有 enum_kind=AD_TYPE、external_value=该标记 的行")
          String reason) {}

  // ================================================================ 保真概览

  public record FidelityOverview(
      @Schema(description = "归一后的广告条数") long totalAds,
      @Schema(description = "🔴 完全归一（NORMALIZED）的条数") long normalizedAds,
      @Schema(description = "🔴 部分覆盖（PARTIAL_FIELD_COVERAGE）的条数") long partialAds,
      @Schema(description = "🔴 未映射类型（UNMAPPED_TYPE）的条数") long unmappedAds,
      @Schema(
              description =
                  "🔴 只留在原始报文里、没有内部落点的字段按出现次数排序 —— 这是「系统没有归一这些字段」的"
                      + "明账，运营要能直接看见")
          List<RawOnlyFieldCount> rawOnlyFields,
      @Schema(description = "说明") String note) {}

  public record RawOnlyFieldCount(
      @Schema(description = "原始字段键") String fieldKey,
      @Schema(description = "出现条数") long occurrences) {}

  // ================================================================ 2.1.2.1 归一口径解析

  /**
   * 走 2.1.2.1 两层（平台默认 + 租户覆盖）解析某个原始广告类型该归到哪个内部类型。
   *
   * <p>🔴 {@code mapped=false} 且 {@code internalAdType="UNMAPPED"} 是**合法且必须被看见**的答案 ——
   * 照抄 2.1.2.1 加粗句「映射缺失必须显式报未映射，不能默认落到某个兜底状态」。
   */
  public record MappingResolution(
      @Schema(description = "平台 id") UUID platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "🔴 原始类型标记") String platformAdType,
      @Schema(description = "🔴 解析出的归一后内部类型；查不到时为 UNMAPPED") String internalAdType,
      @Schema(description = "🔴 是否命中了 2.1.2.1 的任一层") boolean mapped,
      @Schema(description = "🔴 命中哪一层：TENANT_OVERRIDE 优先于 PLATFORM_DEFAULT") String mappingSource,
      @Schema(description = "枚举族名") String enumKind,
      @Schema(description = "生效顺序说明") String precedenceNote,
      @Schema(description = "为什么是未映射（命中时为空）") String unmappedReason) {}

  // ================================================================ 写入请求

  public record SaveProfileRequest(
      @Schema(description = "档案 id；留空表示新建") UUID id,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "平台 id（上游 2.1.1.1）")
          UUID platformId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "🔴 原始平台类型标记，原样存不折叠")
          @NotBlank
          @Size(max = 64)
          String platformAdType,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description =
                  "🔴 归一后的内部类型；必须是 2.1.2.1 真的能解析出来的那个值，查不到就传 UNMAPPED")
          @NotBlank
          String internalAdType,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "展示名") @NotBlank @Size(max = 100)
          String displayName,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "拍卖方式") @NotBlank String auctionModel,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "投放目标") @NotBlank String objective,
      @Schema(description = "是否支持定向") boolean supportsTargeting,
      @Schema(description = "是否支持分时竞价") boolean supportsBidSchedule,
      @Schema(description = "是否支持否定关键词") boolean supportsNegativeKw,
      @Schema(description = "是否支持创意素材") boolean supportsCreative,
      @Schema(description = "是否支持 ASCII 广告位") boolean supportsAsciiAd,
      @Schema(description = "🔴 归一口径来源；DB 会核对它与 2.1.2.1 的实际行是否对得上") @NotBlank
          String mappingSource,
      @Schema(description = "备注") @Size(max = 1000) String note) {}

  public record SaveFieldSpecRequest(
      @Schema(description = "字段定义 id；留空表示新建") UUID id,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "🔴 原始字段键") @NotBlank @Size(max = 64)
          String fieldKey,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "界面标签") @NotBlank @Size(max = 100)
          String fieldLabel,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "数据类型") @NotBlank String dataType,
      @Schema(description = "展示单位") @Size(max = 32) String unit,
      @Schema(description = "ENUM 型的候选值，逗号分隔") String enumValues,
      @Schema(description = "🔴 是否参与跨平台可比；为 true 时必须有内部落点（DB 层 CHECK）") boolean comparable,
      @Schema(description = "🔴 归一后的内部落点；留空表示该字段只能原样留在原始报文里") @Size(max = 64)
          String internalRepresentation,
      @Schema(description = "录入是否必填") boolean requiredOnInput,
      @Schema(description = "展示顺序") @Min(0) @Max(9999) int position,
      @Schema(description = "备注") @Size(max = 1000) String note) {}

  public record SavePayloadRequest(
      @Schema(description = "广告数据 id；留空表示新建") UUID id,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "广告类型档案 id") @NotNull
          UUID adTypeProfileId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "店铺编码") @NotBlank @Size(max = 64)
          String shopCode,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "平台侧广告 id") @NotBlank @Size(max = 128)
          String externalAdId,
      @Schema(description = "平台侧广告组 id") @Size(max = 128) String externalCampaignId,
      @Schema(description = "广告名称") @Size(max = 200) String adName,
      @Schema(description = "🔴 归一后的状态（不同平台的 ENABLED/RUNNING/ACTIVE 归到一处）") @NotBlank
          String adStatus,
      @Schema(description = "币种") @Size(max = 3) String currency,
      @Schema(description = "日预算", type = "string") @DecimalMin("0") BigDecimal dailyBudget,
      @Schema(description = "默认出价", type = "string") @DecimalMin("0") BigDecimal defaultBid,
      @Schema(description = "出价下限", type = "string") @DecimalMin("0") BigDecimal bidFloor,
      @Schema(description = "开始时间（ISO-8601 带时区）") OffsetDateTime startAt,
      @Schema(description = "结束时间（ISO-8601 带时区）") OffsetDateTime endAt,
      @Schema(description = "展示量", type = "string") @DecimalMin("0") BigDecimal impressions,
      @Schema(description = "点击量", type = "string") @DecimalMin("0") BigDecimal clicks,
      @Schema(description = "转化量", type = "string") @DecimalMin("0") BigDecimal conversions,
      @Schema(description = "花费", type = "string") @DecimalMin("0") BigDecimal spend,
      @Schema(description = "归因销售额", type = "string") @DecimalMin("0") BigDecimal attributedRevenue,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description =
                  "🔴 平台 API 返回的差异字段原文，一个字都不改。它是「不能丢信息」的兜底：归一做错了还能回到原文重来")
          @NotNull
          com.fasterxml.jackson.databind.JsonNode rawPayload,
      @Schema(description = "原始报文抓取时刻（ISO-8601 带时区）") @NotNull OffsetDateTime rawFetchedAt,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description =
                  "🔴 声明哪些键已归一到内部字段；DB 会核对它们确实存在于原文里，不许虚报覆盖率")
          @NotNull
          List<String> normalizedFieldKeys,
      @Schema(description = "来源凭据（报表 id / 批次号）") @Size(max = 1000) String sourceRef,
      @Schema(description = "备注") @Size(max = 1000) String note) {}

  public record SaveResponse<T>(
      @Schema(description = "落库后的实体") T row,
      @Schema(description = "🔴 DB 派生出来的保真结果（服务层声称什么都不作数）") String mappingStatus,
      @Schema(
              description =
                  "🔴 DB 派生出来的「只留在原始报文里的键」；服务层声称什么都不作数，隐瞒在此被改写出来")
          List<String> rawOnlyFieldKeys) {}
}
