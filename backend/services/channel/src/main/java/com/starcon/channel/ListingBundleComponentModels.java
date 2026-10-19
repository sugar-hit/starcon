package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 4.1.2.3 套装组件映射 的传输模型。
 *
 * <p>蓝图：「当平台商品对应内部虚拟套装时，维护组件 SKU 与数量的映射，支持平台变体与不同套装版本对应，并校验组件 SKU 在目标仓库可售。输入：组件映射维护；套装版本选择。输出：<b>组件映射记录 + 可售性校验结果</b>。关系：上游 = 3.5.1 套装版本、4.1.2.1；下游 = 07 库存（按组件预占）。<b>组件数量必须大于 0</b>，缺任一组件时整个套装不可售。」
 *
 * <p>载荷一律用具名 {@code record}，不用 {@code Map<String,Object>}：Map 的值类型会被 springdoc
 * 推成 object，codegen 出来是空对象 {@code {}}（与 4.1.2.1 / 4.1.2.2 同一理由）。
 *
 * <p>🔴 模型层就把本模块最容易做丢的两件事钉死：
 *
 * <ol>
 *   <li><b>「查不到」与「确实不可售」必须在类型上分得开</b>：可售性校验结果
 *       {@link ListingBundleSellableCheck} 上有 {@code available}（上游 warehouse 读到了没有）与
 *       {@code bundleSellable}（套装整体能不能卖）两个独立布尔。上游不可达时本服务返回
 *       <b>503</b> 而不是「全部可售」——返回「可售」会让运营把「我们查不到」读成「能卖」，
 *       而那正是会真的超卖的路径。</li>
 *   <li><b>「缺任一组件时整个套装不可售」是整体判定，不是逐行判定</b>：{@code bundleSellable}
 *       与 {@code componentCount} 同在 {@link ListingBundleSellableCheck} 上返回，组件行自己也有
 *       {@code sellable}。只给逐行结果的话，界面很容易在「三行里两行可售」时给出"可以卖"的暗示。</li>
 * </ol>
 *
 * <p><b>没有金额字段</b>：组件映射不碰钱。唯一的数值是 {@code quantity}（{@link BigDecimal} +
 * NUMERIC(19,6)，蓝图加粗句要求恒 > 0）与可售量。
 *
 * <p>与相邻模块的分工：4.1.2.1 拥有**映射**本身（平台变体 → 套装 SKU）与
 * {@code componentQuantity}（平台侧 1 件 = 内部几套）；本模块拥有**逐组件明细**与
 * **组件可售性**。catalog 3.5.1 拥有内部套装的产品定义（哪些 SKU 组成一版套装）。
 * 三者回答三个不同的问题，因此不合并（见 V186 第一节）。
 */
public final class ListingBundleComponentModels {
  private ListingBundleComponentModels() {}

  // ================================================================ 能力声明

  public record ListingBundleCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  public record ListingBundleComponentCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false") List<ListingBundleCapabilityDimension> dimensions,
      @Schema(description = "排序取值") List<String> sorts,
      @Schema(description = "生效状态取值：CURRENT 当前生效 / RETIRED 已退役") List<String> effectiveStates,
      @Schema(description = "🔴 未交付段说明（前端原样展示，不要自己编）") String gapNote,
      @Schema(description = "上游 / 下游依赖说明") String dependencyNote) {}

  public record ListingBundleFilterOptions(
      @Schema(description = "生效状态取值：CURRENT / RETIRED") List<String> effectiveStates,
      @Schema(description = "排序取值") List<String> sorts,
      @Schema(description = "🔴 单页上限") int maxPageSize) {}

  // ================================================================ 写入载荷

  /**
   * 「套装版本选择」：把 4.1.2.1 的套装映射指向某个内部套装版本。
   *
   * <p>🔴 换版<b>不改这里的 {@code effectiveFrom}</b>：已生效的绑定只允许被「关闭」，
   * 直接写 SQL 也绕不过去（V186 的 {@code guard_listing_bundle_binding_frozen}）。
   * 「同一映射已有当前生效版本」由数据库的部分唯一索引拒绝，服务层先查只是为了给出可读提示。
   */
  public record SaveBundleBindingRequest(
      @Schema(description = "绑定 id；留空表示新建", nullable = true) UUID id,
      @Schema(description = "4.1.2.1 套装映射 id（relation_type=ONE_TO_MANY 的生效映射）") UUID mappingId,
      @Schema(description = "🔴 catalog 3.5.1 套装版本逻辑 id（跨服务不建 FK）") UUID bundleVersionId,
      @Schema(description = "🔴 生效时间点（半开区间起点）") OffsetDateTime effectiveFrom,
      @Schema(description = "🔴 变更原因") String changeReason,
      @Schema(description = "🔴 退役既有绑定时填写：把当前生效的那一版关闭到这个时刻", nullable = true)
          OffsetDateTime retireCurrentAt) {}

  /** 「组件映射维护」的一条：组件 SKU 与数量。 */
  public record SaveBundleComponentRequest(
      @Schema(description = "组件行 id；留空表示新增", nullable = true) UUID id,
      @Schema(description = "绑定 id（必须处于当前生效状态）") UUID bindingId,
      @Schema(description = "🔴 catalog 03 的组件 SKU 逻辑 id（跨服务不建 FK）") UUID componentSkuId,
      @Schema(description = "🔴 每卖 1 件平台套装需要这个组件多少；必须 > 0（NUMERIC(19,6)）")
          BigDecimal quantity,
      @Schema(description = "组件角色（主机 / 配件 / 赠品…），只给人看") String role,
      @Schema(description = "备注") String note) {}

  // ================================================================ 绑定列表 / 详情

  /**
   * 套装版本绑定的一行 = 「这个平台变体的套装映射，当前（或曾经）指向哪个内部套装版本」。
   *
   * <p>🔴 {@code componentCount} 与 {@code totalQuantity} 由聚合查询算出：运营看列表时
   * 最想知道的就是「这一版配了几个组件」，不该点进详情才看得见。
   */
  public record ListingBundleBindingRow(
      @Schema(description = "绑定 id") UUID id,
      @Schema(description = "映射 id（4.1.2.1 的 listing_mapping）") UUID mappingId,
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "listing 变体 id") UUID listingVariantId,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "平台商品 ID 原文") String externalItemId,
      @Schema(description = "平台变体 ID 原文") String externalVariantId,
      @Schema(description = "🔴 catalog 套装版本逻辑 id（3.5.1）") UUID bundleVersionId,
      @Schema(description = "🔴 生效区间起点") OffsetDateTime effectiveFrom,
      @Schema(description = "生效区间终点；null = 当前生效") OffsetDateTime effectiveTo,
      @Schema(description = "🔴 当前生效 = true（effectiveTo 为 null）") boolean current,
      @Schema(description = "变更原因") String changeReason,
      @Schema(description = "操作者 id（DB 触发器盖上）") UUID actorId,
      @Schema(description = "组件条数") int componentCount,
      @Schema(description = "🔴 组件数量合计（每卖 1 件平台套装要扣的总件数；无组件为 0）")
          BigDecimal totalQuantity,
      @Schema(description = "落库时刻") OffsetDateTime createdAt) {}

  public record ListingBundleBindingPage(
      @Schema(description = "绑定明细") List<ListingBundleBindingRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "🔴 本页当前生效绑定条数（真实结果，不藏）") int currentInPage) {}

  // ================================================================ 组件映射记录（模块的输出）

  /** 组件映射记录的一行 —— 蓝图输出句的「组件映射记录」。 */
  public record ListingBundleComponentRow(
      @Schema(description = "组件行 id") UUID id,
      @Schema(description = "绑定 id") UUID bindingId,
      @Schema(description = "🔴 catalog 03 的组件 SKU 逻辑 id") UUID componentSkuId,
      @Schema(description = "🔴 数量：每卖 1 件平台套装需要这个组件多少；恒 > 0（NUMERIC(19,6)）")
          BigDecimal quantity,
      @Schema(description = "组件角色（只给人看，不参与判定）") String role,
      @Schema(description = "备注") String note,
      @Schema(description = "该绑定是否当前生效（已退役绑定的组件是历史，只读）") boolean bindingCurrent,
      @Schema(description = "落库时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record ListingBundleComponentPage(
      @Schema(description = "组件明细") List<ListingBundleComponentRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  /**
   * 删除一条组件登记后的结果。
   *
   * <p>🔴 返回<b>删除前</b>的完整组件清单：界面要能立刻显示「现在还剩哪几个组件」，
   * 而「删掉最后一个 ⇒ 整套不可售」这个后果必须在同一次响应里看得见，不能靠用户再点一次校验。
   */
  public record ListingBundleComponentDetail(
      @Schema(description = "已删除的组件行 id") UUID removedId,
      @Schema(description = "绑定 id") UUID bindingId,
      @Schema(description = "🔴 删除前的组件清单（让界面立刻看出删到哪一步了）")
          List<ListingBundleComponentRow> componentsBeforeRemoval,
      @Schema(description = "绑定本身") ListingBundleBindingRow binding,
      @Schema(description = "🔴 说明：删到一条不剩时整套不可售") String note) {}

  /**
   * 单个绑定的详情：套装版本选择 + 逐组件明细。
   *
   * <p>🔴 可售性**不在这里**返回：可售性随目标仓库变化，是一个「校验」动作而不是绑定的属性。
   * 把它做成详情的一部分会让同一个绑定在不同时刻有两个答案。
   */
  public record BundleBindingDetail(
      @Schema(description = "绑定本身") ListingBundleBindingRow binding,
      @Schema(description = "🔴 组件映射记录（逐组件 SKU 与数量）") List<ListingBundleComponentRow> components,
      @Schema(description = "🔴 说明：构成的真值在 catalog，本表只记刊登侧的扣减口径") String note) {}

  // ================================================================ 可售性校验结果（模块的输出）

  /**
   * 单个组件的可售性。
   *
   * <p>🔴 数量列叫 {@code sellableQuantity} 而不是 {@code sellable}：record 会按字段名生成同名
   * 访问器，一个名叫 {@code sellable} 的 BigDecimal 字段会和布尔判定 {@code sellable()} 直接撞名。
   * 两个概念也确实是两件事 —— 一个是「还剩多少件」，一个是「够不够卖一套」。
   */
  public record ListingBundleComponentSellable(
      @Schema(description = "组件行 id") UUID componentId,
      @Schema(description = "组件 SKU 逻辑 id") UUID componentSkuId,
      @Schema(description = "每件平台套装需要的数量") BigDecimal quantity,
      @Schema(description = "该组件在目标仓库的可售量（warehouse 口径：在手 − 预占 − 安全缓冲）")
          BigDecimal sellableQuantity,
      @Schema(description = "🔴 按当前组件数量还能成套卖几件（可售量 ÷ 每件用量，向下取整到件）")
          BigDecimal setsAvailable,
      @Schema(description = "🔴 这一项是否可售：可售量 ≥ 每件用量") boolean sellable,
      @Schema(description = "🔴 不可售的原因；可售时为 null") String reason) {}

  /**
   * 可售性校验结果 —— 蓝图输出句的「可售性校验结果」。
   *
   * <p>🔴 <b>{@code available} 与 {@code bundleSellable} 是两个独立布尔</b>：
   *
   * <ul>
   *   <li>{@code available=false} = 上游 warehouse 读不到（服务层此时抛 503，本对象不会带这个值返回），
   *       保留这个字段是为了让「查不到」在类型上就有一个位置；</li>
   *   <li>{@code bundleSellable=false} = <b>确实不可售</b>：组件数为 0（缺组件），
   *       或任一组件的可售量不足每件用量（缺任一组件）。</li>
   * </ul>
   *
   * <p>蓝图加粗句「缺任一组件时整个套装不可售」就落在 {@code bundleSellable} 这一个布尔上：
   * 逐行看每个组件可能都还有货，但只要有一个不够，这一整套就卖不出去。
   */
  public record ListingBundleSellableCheck(
      @Schema(description = "绑定 id") UUID bindingId,
      @Schema(description = "映射 id") UUID mappingId,
      @Schema(description = "catalog 套装版本逻辑 id") UUID bundleVersionId,
      @Schema(description = "🔴 目标仓库 id（07 库存口径，可售量按该仓库计算）") UUID warehouseId,
      @Schema(description = "🔴 上游可售量是否读到；读到才可能判定可售") boolean available,
      @Schema(description = "🔴 整套是否可售：组件数为 0 或任一组件可售量不足 ⇒ false") boolean bundleSellable,
      @Schema(description = "组件条数") int componentCount,
      @Schema(description = "🔴 不可售的组件条数") int unsellableCount,
      @Schema(description = "🔴 整体不可售的原因；可售时为 null") String reason,
      @Schema(description = "逐组件可售性") List<ListingBundleComponentSellable> components,
      @Schema(description = "🔴 说明：可售量口径与跨服务边界") String note) {}
}
