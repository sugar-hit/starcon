package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 4.1.2.2 映射版本 的传输模型。
 *
 * <p>蓝图：「映射变更时生成新版本而非原地修改，保留每个版本对应的商品/订单范围，支持查看某订单使用的是哪个版本、按版本回溯受影响订单。输入：映射变更；生效时间点；变更原因。输出：版本列表 + 受影响订单范围查询。关系：上游 = 4.1.2.1；下游 = 09 订单（{@code order_line_component} 冻结）。<b>改映射不影响已产生的订单</b>，只对新订单生效——这是财务与售后追溯的前提。」
 *
 * <p>载荷一律用具名 {@code record}，不用 {@code Map<String,Object>}：Map 的值类型会被 springdoc 推成 object，codegen 出来是空对象 {@code {}}（与 4.1.2.1 同一理由）。
 *
 * <p>🔴 模型层就把本模块最容易做丢的三件事钉死：
 *
 * <ol>
 *   <li><b>「受影响订单范围」是一个显式不可用的形状</b>：{@link VersionAffectedOrders} 上
 *       {@code available=false}（且**没有** {@code items} 字段：不交付的维度不配列表）。
 *       模型里<b>没有</b>「空数组 = 没有受影响订单」这个歧义形状 —— 那种形状一存在，
 *       调用方就会在赶对账时选它，于是"查不到"被读成"确实没有"。</li>
 *   <li><b>商品范围是冻结快照</b>：{@link VersionProductScope} 的字段一律带
 *       {@code Snapshot} 后缀，与平台侧**当前**值（仍在 {@code listing}）分开。
 *       两者混用就会让"历史被后来的同步改写"。</li>
 *   <li><b>没有金额字段</b>：映射版本不碰钱。有字段就得填值，填 0 会被读成"版本错了 0 块钱"。
 *       唯一的数值是 {@code componentQuantity}（{@link BigDecimal} + NUMERIC(19,6)）与版本号。</li>
 * </ol>
 *
 * <p>与 4.1.2.1 的分工：4.1.2.1 拥有<b>写入路径</b>（{@code save} / {@code remove} 建版本）
 * 与单条映射的详情；本模块拥有<b>版本查询面</b>（版本列表 / 版本详情 / 按版本回溯商品范围）
 * 与「受影响订单范围」的交付状态声明。两者共用 {@code listing_mapping_version} 一张表。
 */
public final class ListingMappingVersionModels {
  private ListingMappingVersionModels() {}

  // ================================================================ 能力声明

  public record VersionCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  public record ListingMappingVersionCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false") List<VersionCapabilityDimension> dimensions,
      @Schema(description = "排序取值") List<String> sorts,
      @Schema(description = "变更来源取值：MANUAL 人工 / BATCH_CONFIRMED 批量候选人工确认")
          List<String> changeSources,
      @Schema(description = "映射基数取值：ONE_TO_ONE / ONE_TO_MANY / MANY_TO_ONE") List<String> relationTypes,
      @Schema(description = "🔴 未交付段说明（前端原样展示，不要自己编）") String gapNote,
      @Schema(description = "上游 / 下游依赖说明") String dependencyNote) {}

  public record VersionFilterOptions(
      @Schema(description = "映射基数取值") List<String> relationTypes,
      @Schema(description = "变更来源取值") List<String> changeSources,
      @Schema(description = "生效状态取值：CURRENT 当前生效 / CLOSED 已关闭") List<String> effectiveStates,
      @Schema(description = "排序取值") List<String> sorts,
      @Schema(description = "🔴 单页上限") int maxPageSize) {}

  // ================================================================ 版本列表

  /** 版本列表的一行。 */
  public record VersionRow(
      @Schema(description = "版本 id") UUID id,
      @Schema(description = "映射 id（4.1.2.1 的 listing_mapping）") UUID mappingId,
      @Schema(description = "版本号（同一条映射内从 1 递增）") int versionNo,
      @Schema(description = "🔴 映射基数：ONE_TO_ONE / ONE_TO_MANY / MANY_TO_ONE") String relationType,
      @Schema(description = "MAPPED / BUNDLE / UNMAPPED（解除映射的那一版）") String mappingStatus,
      @Schema(description = "🔴 该版本指向的内部 SKU 逻辑 id（03 拥有，跨服务不建 FK）；解除版本为 null")
          UUID internalSkuId,
      @Schema(description = "套装组件数量；仅 ONE_TO_MANY 有值，NUMERIC(19,6)") BigDecimal componentQuantity,
      @Schema(description = "🔴 生效时间点：区间为 [effectiveFrom, effectiveTo)") OffsetDateTime effectiveFrom,
      @Schema(description = "生效时间点终点；null = 当前生效版本") OffsetDateTime effectiveTo,
      @Schema(description = "🔴 当前生效 = true（effectiveTo 为 null）") boolean current,
      @Schema(description = "🔴 变更原因") String changeReason,
      @Schema(description = "MANUAL 人工建立 / BATCH_CONFIRMED 批量候选人工确认") String changeSource,
      @Schema(description = "操作者 id（DB 触发器盖上，服务层写不出）") UUID actorId,
      // ---- 商品范围（V182 冻结快照）：这一版当时覆盖哪个平台商品 / 变体 ----
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "平台商品 ID 原文（版本落库时的快照）") String externalItemId,
      @Schema(description = "平台标题原文（版本落库时的快照）") String titleSnapshot,
      @Schema(description = "平台变体 id") UUID listingVariantId,
      @Schema(description = "平台变体 ID 原文（版本落库时的快照）") String externalVariantId,
      @Schema(description = "店铺编码（版本落库时的快照）") String shopCode,
      @Schema(description = "范围抓取时刻") OffsetDateTime capturedAt,
      @Schema(description = "落库时刻") OffsetDateTime createdAt) {}

  public record VersionPage(
      @Schema(description = "版本明细") List<VersionRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "🔴 本页当前生效版本条数（当前生效也是真实结果，不藏）") int currentInPage) {}

  // ================================================================ 版本详情 / 商品范围

  /**
   * 按版本回溯的商品范围（V182 的冻结快照）。
   *
   * <p>🔴 字段名带 {@code Snapshot} 后缀是**故意的**：平台侧当前值仍在 {@code listing}，
   * 两者不一样是有意保留的差异（运营要能同时看到"当时叫什么"和"现在叫什么"）。
   */
  public record VersionProductScope(
      @Schema(description = "版本 id") UUID versionId,
      @Schema(description = "映射 id") UUID mappingId,
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "平台商品 ID 原文（版本落库时的快照）") String externalItemId,
      @Schema(description = "平台标题原文（版本落库时的快照）") String titleSnapshot,
      @Schema(description = "平台变体 id") UUID listingVariantId,
      @Schema(description = "平台变体 ID 原文（版本落库时的快照）") String externalVariantId,
      @Schema(description = "店铺编码（版本落库时的快照）") String shopCode,
      @Schema(description = "平台商品 ID 原文（平台侧**当前**值，用于与快照对照）") String currentExternalItemId,
      @Schema(description = "平台标题原文（平台侧**当前**值，用于与快照对照）") String currentTitle,
      @Schema(description = "🔴 快照与当前值是否已经不一致（平台侧后来被重新同步过）") boolean driftedFromPlatform,
      @Schema(description = "范围抓取时刻") OffsetDateTime capturedAt,
      @Schema(description = "🔴 说明：平台侧当前值只读自 listing，本模块不改它") String note) {}

  /**
   * 单个版本的详情。
   *
   * <p>「这一版相对上一版改了什么」在这里是<b>真比对</b>出来的
   * （{@code changedFields} 由服务层逐字段比对相邻两个版本得出），不是前端自己推的。
   */
  public record MappingVersionDetail(
      @Schema(description = "版本本身") VersionRow version,
      @Schema(description = "该版本对应的商品范围（冻结快照）") VersionProductScope productScope,
      @Schema(description = "上一版版本号；首版为 null") Integer previousVersionNo,
      @Schema(description = "下一版版本号；当前生效版为 null") Integer nextVersionNo,
      @Schema(description = "🔴 本版相对上一版的变更字段（RELATION_TYPE / INTERNAL_SKU_ID / COMPONENT_QUANTITY / MAPPING_STATUS）")
          List<String> changedFields,
      @Schema(description = "🔴 受影响订单范围（available=false，无 items 字段）")
          VersionAffectedOrders affectedOrders) {}

  // ================================================================ 受影响订单范围（未交付）

  /**
   * 「按版本回溯受影响订单」的交付状态。
   *
   * <p>🔴 这是本模块最容易被赶工做丢的一块，而它<b>刻意</b>没有交付：受影响订单是 09 订单
   * （trade 服务）的真实数据，集成头上 trade 侧<b>没有任何</b>可供 channel 查询订单的端点，
   * {@code order_line_component} 的冻结也未落地。
   *
   * <p>跨服务 SQL / 直连对方库被 AGENTS.md 明令禁止，因此本模块**不造假数据**：
   * {@code available=false} + {@code ownerModule} 指明归属 + {@code note} 说明为什么答不出来。
   *
   * <p>🔴 <b>刻意没有 {@code items} 字段</b>，与 {@code UnmappedPage.backflowDelivered} 同一手法：
   * 已交付的清单才配一个列表，没交付的维度只给标志 + 说明。加一个恒为空的列表只会制造两种误读 ——
   * 空数组会被对账流程读成"这一版确实没有影响任何订单"，而真相是"查不了"；
   * 而 {@code List<Object>} 还会被 springdoc 推成 {@code {}} 空对象，正是 9.6.1.3 踩过的那个
   * codegen 坑（本车道实测复现：生成出 {@code Array<{ }>}）。判别"查不到"的是 {@code available} 这一个布尔。
   */
  public record VersionAffectedOrders(
      @Schema(description = "版本 id") UUID versionId,
      @Schema(description = "版本号") int versionNo,
      @Schema(description = "🔴 是否可查；本模块固定为 false（09 订单侧尚无已交付查询端点）")
          boolean available,
      @Schema(description = "🔴 责任模块") String ownerModule,
      @Schema(description = "🔴 不可用的原因") String note,
      @Schema(description = "🔴 变成可查需要下游先交付什么") String prerequisite) {}
}
