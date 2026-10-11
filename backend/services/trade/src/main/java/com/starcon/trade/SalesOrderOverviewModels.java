package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 9.1.1.2 订单详情（控制塔读投影）的传输模型。
 *
 * <p>蓝图 09 卷 9.1.1.2 原文：「订单全景：买家与地址快照、商品行（SKU、规格、单价、数量、税费）、
 * 费用构成与分摊、支付与退款记录、履约批次与包裹、物流轨迹、售后关联、平台侧原始报文、状态历史。」
 * 加粗句：「**详情页是订单的『控制塔』**：客服、采购、财务都从这一个页面理解这笔订单为什么长这样。」
 *
 * <p><b>本文件最重要的纪律：「拿不到」与「没有」是两回事，代码里必须分得开。</b>
 * 蓝图职责句点名的分区里，<b>只有四段在本服务真的有数据</b>：
 *
 * <ul>
 *   <li>买家与地址快照 → {@code sales_order_party_snapshot} + {@code order_address_snapshot}；</li>
 *   <li>商品行 → {@code sales_order_line} + {@code order_line_attribute_snapshot}；</li>
 *   <li>费用<b>构成</b> → {@code order_charge}；</li>
 *   <li>状态历史 → {@code sales_order_status_history}。</li>
 * </ul>
 *
 * <p><b>两处最容易犯的语义造假，本文件显式拒绝</b>：
 *
 * <ol>
 *   <li><b>费用分摊 ≠ 费用构成。</b>{@code order_charge} 每一行是一笔费用
 *       （运费 / 折扣 / 税 / 包装 / 其他），它回答「这笔单一共收了哪些钱」。
 *       分摊回答的是另一个问题：「这笔整单费用按什么比例摊到了哪几行」。
 *       V99 第 320-323 行原文已写明立场：「这里<b>不是</b>行级分摊明细……
 *       真要做行级分摊时另开 {@code order_charge_allocation} 子表，
 *       <b>别拿 charge_kind 当分摊记号</b>」。所以
 *       {@link SalesOrderOverview#chargeAllocations()} 恒为 <b>null</b>，
 *       绝不拿 charge 的合计冒充分摊 —— 那会让「这一行到底买了多少钱」
 *       保持沉默的同时，界面上却显示着一个像分摊的数字。</li>
 *   <li><b>组件展开</b>：{@code order_line_component} 这张表<b>在 V99 里根本没建</b>
 *       （实体目录 db/schemas/v2/README.md 第 107 行列了它，V99 只建了七张表）。
 *       展开结果是<b>建单那一刻</b>由 BOM 解析算出来的历史快照，读侧凭空造不出来，
 *       更不该造。ownerModule 点名 <b>9.1.2.2</b>。</li>
 * </ol>
 *
 * <p><b>命名。</b>本文件统一用 {@code SalesOrderOverview*} 前缀。{@code SalesOrderDetail}
 * 已被 9.1.2.2 占用（手工订单详情端点），{@code SalesOrderList*} 已被 9.1.1.1 占用，
 * 故两者都不能用。全部 13 个名字<b>逐个 grep 精确比对</b>过
 * {@code packages/api-contracts/src/catalog.ts}（codegen 把所有服务合并进一个扁平文件、
 * 同名 key 后者覆盖前者且不报冲突，所以不按前缀扫而是逐个名字比对），全部 0 命中。
 */
public final class SalesOrderOverviewModels {

  private SalesOrderOverviewModels() {}

  // ================================================================ 分区可用性

  /**
   * 一个分区（蓝图职责句里的一段）在当前的可得性。
   *
   * <p>照 8.3.1.2 出库单详情、9.1.1.1 订单列表已建立的仓库约定：
   * 拿不到的数据<b>显式 {@code available=false}</b>，每段带 {@code ownerModule}
   * 与原因，<b>不建占位表、不返回空集冒充成功</b> ——
   * 「空的包裹列表会被读成『这单没有包裹』而不是『这段能力还没接入』」。
   *
   * @param section 对应响应里的哪个字段（null 的含义是「这一段在响应里没有字段」，
   *     比如组件展开：它连字段都不该有，因为没有任何一列可读）
   * @param href 该分区交付之后应该去哪个端点看（现在还不可用，所以是未来的去处）
   */
  public record SalesOrderOverviewSection(
      String key,
      String label,
      boolean available,
      @Schema(nullable = true) String ownerModule,
      @Schema(nullable = true) String href,
      String reason) {}

  /**
   * 详情页的操作入口。
   *
   * <p>蓝图输出句含「操作入口」。与 9.1.1.1 列表页的批量操作<b>同一批不可用</b>，
   * 理由逐条相同（审单 9.2 未交付、备注归 9.1.2.2 写、取消 9.1.1.3 未交付、
   * 催付 15 未交付），此处不重复推导，只说明本模块<b>刻意不提供任何写端点</b>。
   *
   * @param precondition 真正能落地这条操作的前置条件（照此就能判断「什么时候它会变成可用」）
   */
  public record SalesOrderOverviewAction(
      String code,
      String label,
      boolean available,
      String ownerModule,
      String precondition,
      String reason) {}

  // ================================================================ 详情各段

  /**
   * 订单头。
   *
   * @param documentStatus <b>单据状态</b>（DRAFT / SUBMITTED），
   *     语义是「有没有进审单队列」——<b>不是履约状态</b>
   * @param paymentMethod <b>付款方式</b>（预付 / 货到付 / 电汇 / 账期 / 货代代收），
   *     语义是<b>约定</b>——<b>不是支付状态</b>。两个字段不共用一个列。
   */
  public record SalesOrderOverviewHeader(
      UUID id,
      String orderNo,
      String externalOrderNo,
      String orderSource,
      String manualKind,
      String documentStatus,
      String currency,
      BigDecimal subtotalAmount,
      BigDecimal discountAmount,
      BigDecimal taxAmount,
      BigDecimal shippingAmount,
      BigDecimal otherAmount,
      BigDecimal grandTotalAmount,
      String paymentMethod,
      Integer paymentTermsDays,
      String note,
      OffsetDateTime submittedAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 买家（客户快照）。一单一客户，改名改级别都不影响历史订单。 */
  public record SalesOrderOverviewParty(
      UUID customerPartyId,
      String customerCode,
      String customerName,
      String customerType,
      String customerDefaultCurrency) {}

  /**
   * 地址快照（硬要求 4：<b>不是引用活地址</b>）。
   *
   * <p>收货与账单各一行，<b>合并成一行</b>会让「收货改了、账单没改」这种
   * 正常业务无法表达。买家事后改地址改不动这里 —— 已提交订单由 V99 的
   * {@code order_address_snapshot_frozen} 触发器钉死在数据层。
   */
  public record SalesOrderOverviewAddress(
      String addressRole,
      String recipientName,
      String recipientPhone,
      String recipientEmail,
      String companyName,
      String countryCode,
      String province,
      String city,
      String district,
      String postcode,
      String addressLine1,
      String addressLine2,
      String taxId,
      String note) {}

  /**
   * 商品行（硬要求 3：<b>全部是快照列，不引用实时商品</b>）。
   *
   * @param specSnapshot 下单当时的规格组合（如「黑色 / L」）
   * @param skuMappingVersion 这张行的 SKU 按 catalog 哪一版映射对上的号
   */
  public record SalesOrderOverviewLine(
      UUID id,
      int lineNo,
      UUID skuId,
      String skuCode,
      String skuName,
      String specSnapshot,
      String skuMappingVersion,
      BigDecimal quantity,
      BigDecimal unitPrice,
      BigDecimal discountAmount,
      BigDecimal taxAmount,
      BigDecimal lineAmount,
      String note) {}

  /**
   * 订单行的属性快照（append-only，历史举证凭据）。
   *
   * <p>一行 = 一个「名称 / 值 / 单位 / 来源版本」四元组。
   * V99 的 {@code order_line_attribute_snapshot_append_only} 触发器让
   * 绕过服务层直连 SQL 也改不动。
   */
  public record SalesOrderOverviewLineAttribute(
      UUID id,
      UUID lineId,
      String attributeName,
      String attributeValue,
      String attributeUnit,
      String sourceVersion) {}

  /**
   * 一笔费用（费用<b>构成</b>，不是分摊）。
   *
   * @param chargeKind ORDER = 整单费用；LINE = 记在订单行名下的费用。
   *     <b>这不是分摊明细</b>，见类注释与
   *     {@link SalesOrderOverview#chargeAllocations()}
   * @param amount 正数是加、负数是减（折扣与优惠记负数）
   */
  public record SalesOrderOverviewCharge(
      UUID id,
      String chargeType,
      String label,
      BigDecimal amount,
      String chargeKind,
      String note) {}

  /** 状态历史的一条事件（{@code sales_order_status_history} 的真实行）。 */
  public record SalesOrderOverviewStatusEvent(
      UUID id,
      String eventType,
      String fromStatus,
      String toStatus,
      String reason,
      UUID actorId,
      OffsetDateTime occurredAt) {}

  /**
   * 给 warehouse 出库单详情用的<b>履约引用</b>。
   *
   * <p><b>边界纪律：这是 warehouse 该调用的授权 API 的形状，
   * 不是让它直连 trade 的表的许可。</b>AGENTS.md 明令「跨服务调用走授权 API / event，
   * 禁止读写对方表、跨库 FK 或视图」—— 所以本 record 的存在方式恰恰是
   * <b>不</b>建跨库 FK 或视图，而是由 warehouse 在自己的服务里调本端点。
   *
   * <p><b>为什么按 order_no 而不是按 uuid 找</b>：warehouse 的
   * {@code outbound_order.source_external_ref} 存的就是销售订单<b>号</b>
   * （V72 第 114 行：「外部单号：9 销售订单号」，且刻意是 varchar 而不是 uuid
   * —— 跨服务不猜对方 id 形态，更重要的是它让「一个订单多张出库单」
   * 在数据上成立）。warehouse 手上只有这个字符串，没有 trade 的 uuid，
   * 所以本端点的查询键就是 order_no + order_source。
   *
   * @param shipTo {@link SalesOrderOverviewAddress} 形状的收货地址。
   *     <b>刻意原样返回订单侧冻结的那一份快照</b>，warehouse 不抄第二份 ——
   *     抄了以后地址改过两份会分叉，而作业员分不清哪份是对的
   */
  public record SalesOrderOverviewFulfillmentReference(
      UUID salesOrderId,
      String orderNo,
      String externalOrderNo,
      String orderSource,
      String documentStatus,
      String currency,
      BigDecimal grandTotalAmount,
      OffsetDateTime submittedAt,
      @Schema(nullable = true) SalesOrderOverviewParty buyer,
      @Schema(nullable = true) SalesOrderOverviewAddress shipTo,
      @Schema(nullable = true) SalesOrderOverviewAddress billTo,
      List<SalesOrderOverviewLine> lines,
      String consumptionNote) {}

  /**
   * 订单全景详情（一次调用返回整屏）。
   *
   * <p><b>不可得分区在响应里是 {@code null}，不是空数组、不是 0。</b>
   * 每段配 {@link SalesOrderOverviewSection} 说明它归谁、为什么没有。
   *
   * @param chargeAllocations <b>恒为 null</b>：本服务<b>没有</b>费用分摊表
   *     （{@code order_charge_allocation} 不存在）。V99 明令
   *     「别拿 charge_kind 当分摊记号」，而 {@code chargeTotals}
   *     是费用构成、不是分摊 —— 两者在界面上必须分开呈现。
   * @param lineComponents <b>恒为 null</b>：{@code order_line_component}
   *     这张表在 V99 里没建（实体目录列了它，是 9.1.2.2 的交付缺口）
   * @param payments / refunds <b>恒为 null</b>：12 财务未交付
   * @param fulfillmentBatches / packages <b>恒为 null</b>：9.3.3.1 / 8.3.4.1 未交付
   * @param logisticsTrace <b>恒为 null</b>：10 未交付
   * @param aftersalesRefs <b>恒为 null</b>：11 售后一条未做
   * @param platformRawPayload <b>恒为 null</b>：9.1.2.1 平台同步未交付
   */
  public record SalesOrderOverview(
      SalesOrderOverviewHeader header,
      @Schema(nullable = true) SalesOrderOverviewParty buyer,
      List<SalesOrderOverviewAddress> addresses,
      List<SalesOrderOverviewLine> lines,
      Map<String, List<SalesOrderOverviewLineAttribute>> attributesByLineId,
      List<SalesOrderOverviewCharge> charges,
      SalesOrderOverviewTotals chargeTotals,
      @Schema(nullable = true) List<SalesOrderOverviewCharge> chargeAllocations,
      @Schema(nullable = true) List<SalesOrderOverviewLineComponent> lineComponents,
      List<SalesOrderOverviewStatusEvent> timeline,
      @Schema(nullable = true) List<SalesOrderOverviewPayment> payments,
      @Schema(nullable = true) List<SalesOrderOverviewRefund> refunds,
      @Schema(nullable = true) List<Object> fulfillmentBatches,
      @Schema(nullable = true) List<Object> packages,
      @Schema(nullable = true) Object logisticsTrace,
      @Schema(nullable = true) List<Object> aftersalesRefs,
      @Schema(nullable = true) String platformRawPayload,
      List<SalesOrderOverviewAction> actions,
      String dataScope,
      String dataScopeNote) {}

  /**
   * 费用构成汇总。
   *
   * <p><b>这不是分摊。</b>它把 {@code order_charge} 的行按 charge_type 归拢，
   * 回答「这笔单一共收了哪些钱」，并与订单头由 V99 的
   * {@code guard_sales_order_totals} 触发器钉死的汇总<b>逐项对齐</b>
   * （触发器保证头与明细永远相等，所以这里对不上就是读错了，不是有业务差额）。
   *
   * <p>金额<b>不跨币种相加</b>：一张订单只有一个 currency（sales_order.currency），
   * 而整单的 subtotal/discount/tax/shipping/other/grandTotal 已在订单头上按该币种落库。
   */
  public record SalesOrderOverviewTotals(
      BigDecimal subtotalAmount,
      BigDecimal discountAmount,
      BigDecimal taxAmount,
      BigDecimal shippingAmount,
      BigDecimal otherAmount,
      BigDecimal grandTotalAmount,
      String currency,
      int chargeRowCount,
      String allocationNote) {}

  /** 组件展开的一行。<b>本模块恒不返回</b>（order_line_component 表不存在）。 */
  public record SalesOrderOverviewLineComponent(
      UUID id, UUID lineId, UUID componentSkuId, String componentSkuCode, BigDecimal quantity) {}

  /** 支付记录的一行。<b>本模块恒不返回</b>（12 财务未交付）。 */
  public record SalesOrderOverviewPayment(
      UUID id, String method, BigDecimal amount, String status, OffsetDateTime paidAt) {}

  /** 退款记录的一行。<b>本模块恒不返回</b>（12 财务未交付）。 */
  public record SalesOrderOverviewRefund(
      UUID id, UUID paymentId, BigDecimal amount, String reason, OffsetDateTime refundedAt) {}

  /**
   * 能力声明：11 个分区逐段报可得性 + 操作入口。
   *
   * <p>与 9.1.1.1 的 {@code /trade/sales-orders/list/capabilities} 分开：
   * 那个报<b>列表页</b>的筛选与排序边界，本个报<b>详情页</b>的分区边界。
   * 两者并排读才不会把「列表能筛什么」当成「详情能给什么」。
   */
  public record SalesOrderOverviewCapabilities(
      List<String> availableSections,
      List<SalesOrderOverviewSection> unavailableSections,
      List<SalesOrderOverviewAction> actions,
      String fulfillmentReferenceApi,
      String tenantIsolationNote,
      String writeNote) {}
}