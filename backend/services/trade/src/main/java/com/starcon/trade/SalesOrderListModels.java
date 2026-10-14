package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 9.1.1.1 订单列表（全渠道统一订单列表读投影）的传输模型。
 *
 * <p>蓝图 09 卷 9.1.1.1 原文：「全渠道订单统一列表：订单号、平台店铺、下单时间、买家、SKU 行数、
 * 总金额/币种、支付状态、履约状态、发货仓、平台状态，支持多维筛选、导出与批量操作 （审单、备注、取消、发催付）。」
 *
 * <p><b>本文件最重要的纪律：拿不到的维度必须是 null，不是 0、不是假枚举。</b> 蓝图点名的九个维度里，有三个在本服务**根本不存在**：
 *
 * <ul>
 *   <li><b>支付状态</b>：sales_order 只有 {@code payment_method}（付款方式 = 我方与客户的 <i>约定</i>：预付 / 货到付 / 电汇 /
 *       账期）。支付状态是<i>实收流水</i>（已付 / 部分付 / 未付 / 逾期），由 12 财务记账。把前者标成后者是语义造假，所以 {@link
 *       SalesOrderListRow#paymentStatus()} 恒为 null。
 *   <li><b>履约状态</b>：{@code status} 的 CHECK 只有 {@code ('DRAFT','SUBMITTED')}，
 *       语义是「这张单有没有进审单队列」，<b>完全没有履约概念</b>。 {@link SalesOrderListRow#documentStatus()} 如实返回 DRAFT /
 *       SUBMITTED， 而 {@link SalesOrderListRow#fulfillmentStatus()} 恒为 null ——
 *       两者是**两个不同的字段**，不共用一个列。
 *   <li><b>平台店铺 / 发货仓 / 平台状态</b>：订单表上没有这些列， 平台同步（9.1.2.1）与履约请求（9.3.3.1）都未交付。
 * </ul>
 *
 * <p>为什么必须显式报 null（照 8.3.1.2 出库单详情已建立的仓库约定）： 「空的包裹列表会被读成『这单没有包裹』而不是『这段能力还没接入』」。 同样地，返回 {@code
 * paymentStatus: "UNPAID"} 会让界面显示「未支付」， 而真相是「本服务根本不知道支付进展」—— 这两者对运营的决策含义完全不同。
 *
 * <p><b>命名。</b>本文件统一用 {@code SalesOrderList*} 前缀，与 9.1.2.2 已占用的 19 个 {@code SalesOrder*}（含列表页最想用的
 * {@code SalesOrderRow} / {@code SalesOrderPage}） 逐个 grep 比对确认**零重叠**。全仓契约被 codegen 合并进**一个扁平文件**、
 * 同名 key 后者覆盖前者且不报冲突，故不按前缀扫而是逐个名字精确比对。
 */
public final class SalesOrderListModels {

  private SalesOrderListModels() {}

  // ------------------------------------------------------------ 入参

  /**
   * 订单列表查询。
   *
   * <p>每一个筛选参数都对应一个<b>真实存在</b>的列或一条真实存在的聚合。 蓝图点名的「平台 / 店铺 / 支付状态 / 履约状态 / 发货仓 / 售后状态」筛选项
   * <b>刻意不作为参数存在</b> —— 提供一个永远筛不出东西的参数比不提供更糟： 界面上那个下拉框会让人以为这条链路已经通了。它们在 {@link
   * SalesOrderListCapabilities#unavailableDimensions()} 里逐条报出 ownerModule 与原因。
   *
   * <p>{@code sort} 取值与 {@link SalesOrderListSort} 逐条对齐；非法值 400 而不是静默忽略。
   *
   * <p>{@code actorId} / {@code selfOnly} 由服务端从<b>已认证身份</b>与角色数据范围推导， <b>不接受请求参数传入</b> ——
   * 否则「只看我自己的单」会变成一个能越权的开关。 （照 warehouse {@code InventoryController.selfOnly} 的既有先例。）
   */
  public record SalesOrderListQuery(
      @Schema(nullable = true) String orderSource,
      @Schema(nullable = true) String documentStatus,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) UUID customerPartyId,
      @Schema(nullable = true) String buyerKeyword,
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) OffsetDateTime orderedFrom,
      @Schema(nullable = true) OffsetDateTime orderedTo,
      @Schema(nullable = true) BigDecimal minAmount,
      @Schema(nullable = true) BigDecimal maxAmount,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort,
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size,
      @Schema(nullable = true) UUID actorId,
      @Schema(nullable = true) Boolean selfOnly) {}

  // ------------------------------------------------------------ 排序

  /**
   * 可用排序维度。
   *
   * <p><b>刻意只有四个</b>：蓝图要求「能按任一维度筛选与排序」，而三个状态维度 （支付 / 履约 / 售后）在本服务都不存在，没有列可排。
   * 提供一个排不出顺序的排序项，界面上点下去只会发现顺序没变。
   */
  public enum SalesOrderListSort {
    ORDERED_AT_DESC("下单时间，最新在前", "o.created_at desc, o.id desc"),
    ORDERED_AT_ASC("下单时间，最早在前", "o.created_at asc, o.id asc"),
    AMOUNT_DESC("订单金额，由高到低", "o.grand_total_amount desc, o.id desc"),
    AMOUNT_ASC("订单金额，由低到高", "o.grand_total_amount asc, o.id asc");

    private final String label;
    private final String sql;

    SalesOrderListSort(String label, String sql) {
      this.label = label;
      this.sql = sql;
    }

    public String label() {
      return label;
    }

    public String sql() {
      return sql;
    }

    /** 非法值在这里就 400，不静默回落到默认排序。 */
    public static SalesOrderListSort parse(String raw) {
      if (raw == null || raw.isBlank()) return ORDERED_AT_DESC;
      try {
        return valueOf(raw.trim().toUpperCase());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "排序维度只能是 ORDERED_AT_DESC / ORDERED_AT_ASC / AMOUNT_DESC / AMOUNT_ASC："
                + "支付、履约、售后三个状态维度在本服务不存在，没有可排的列");
      }
    }
  }

  // ------------------------------------------------------------ 出参

  /**
   * 列表行。
   *
   * <p><b>null 与 0 的区别在每个字段上都是实质的</b>： {@code lineCount} 是 0 表示「这张单真的没有商品行」（DRAFT 允许）， {@code
   * fulfillmentStatus} 是 null 表示「本服务没有履约概念」。
   *
   * @param buyerName 买家名（来自客户快照 sales_order_party_snapshot，已聚合）
   * @param skuCodes 该单涉及的 SKU 编码摘要（列表页直接显示的一行摘要； 完整明细在 9.1.1.2 详情）
   * @param documentStatus <b>单据状态</b>（DRAFT / SUBMITTED）， 语义是「有没有进审单队列」——<b>不是履约状态</b>
   * @param paymentStatus <b>恒为 null</b>：本服务没有支付状态，只有付款方式
   * @param fulfillmentStatus <b>恒为 null</b>：本服务没有履约状态
   * @param shippingWarehouse <b>恒为 null</b>：销售订单与出库单之间尚无履约请求关联
   * @param platformStatus <b>恒为 null</b>：平台同步未交付
   * @param aftersalesStatus <b>恒为 null</b>：11 售后一条未做
   */
  public record SalesOrderListRow(
      UUID id,
      String orderNo,
      String externalOrderNo,
      String orderSource,
      String manualKind,
      String documentStatus,
      String currency,
      BigDecimal grandTotalAmount,
      int lineCount,
      UUID customerPartyId,
      String buyerName,
      List<String> skuCodes,
      OffsetDateTime submittedAt,
      OffsetDateTime createdAt,
      @Schema(nullable = true) String platformStatus,
      @Schema(nullable = true) String paymentStatus,
      @Schema(nullable = true) String fulfillmentStatus,
      @Schema(nullable = true) String shippingWarehouse,
      @Schema(nullable = true) String aftersalesStatus) {}

  /**
   * 汇总条（订单数 / 金额 / 待处理数）。
   *
   * <p><b>金额按币种分开报</b>：租户内同时存在多种币种时，把 USD 与 EUR 加成一个数 是没有意义的（那不是任何一个金额）。所以返回 {@code
   * amountsByCurrency} 而不是 一个假装跨币种可加的 {@code totalAmount}。
   *
   * @param totalCount 命中筛选条件的订单总数（不受分页影响）
   * @param pendingCount <b>恒为 null</b>：蓝图要的「待处理数」其口径由 9.2 审单定义 （待审？待付？待发？），9.2
   *     未交付。刻意<b>不</b>用「DRAFT 算待处理」自造一个 替代口径然后在界面上宣称它就是待处理数 —— 那会让运营照着一个从未被定义过的 口径做决策
   * @param pendingCountOwner 「待处理数」的归属模块，供界面直接显示原因
   */
  public record SalesOrderListSummary(
      long totalCount,
      long draftCount,
      long submittedCount,
      List<SalesOrderListCurrencyAmount> amountsByCurrency,
      @Schema(nullable = true) Long pendingCount,
      String pendingCountOwner,
      String pendingCountNote) {}

  /** 单币种金额汇总。金额一律 BigDecimal，对应 DB 的 numeric(19,4)，禁浮点。 */
  public record SalesOrderListCurrencyAmount(String currency, BigDecimal amount, long orderCount) {}

  /**
   * 列表页：真分页（LIMIT/OFFSET + 独立的 COUNT(*) 总数查询）+ 与分页无关的全量汇总。
   *
   * @param dataScope 实际生效的数据范围：ALL / SELF
   * @param dataScopeNote 数据范围的说明文案（界面上要讲清 SELF 到底筛了什么）
   */
  public record SalesOrderListPage(
      List<SalesOrderListRow> rows,
      int page,
      int size,
      long total,
      SalesOrderListSummary summary,
      String dataScope,
      String dataScopeNote) {}

  /**
   * 下拉筛选项（租户级词表，<b>不随时间筛选变化</b>，避免下拉随翻页漂移）。
   *
   * @param currencies 本租户订单里真实出现过的币种（来自数据，不是硬编码词表）
   */
  public record SalesOrderListFilterOptions(
      List<String> orderSources,
      List<String> documentStatuses,
      List<String> currencies,
      List<SalesOrderListSortOption> sorts) {}

  /** 单个排序选项。 */
  public record SalesOrderListSortOption(String code, String label) {}

  /**
   * 蓝图维度里<b>本服务拿不到</b>的项。
   *
   * <p>照 8.3.1.2 已建立的仓库约定：拿不到的数据<b>显式报 available=false</b>， 每段带 ownerModule
   * 与原因，<b>不建占位表、不返回空集冒充成功</b>。
   *
   * @param field 列表响应里对应为 null 的字段名
   * @param ownerModule 该维度的归属模块（谁交付之后它才会变成真数据）
   */
  public record SalesOrderListDimension(
      String field, String label, boolean available, String ownerModule, String reason) {}

  /**
   * 批量操作。
   *
   * <p>蓝图把「审单、备注、取消、发催付」写在本模块职责里，但四条<b>全部</b>不可用： 审单 = 9.2 未交付；取消 = 9.1.1.3 未交付； <b>备注会写 {@code
   * sales_order.note}，而写入所有者是 9.1.2.2</b> —— 本模块是读投影，不抢写它的事实表；发催付 = 15 客服侧未交付。
   *
   * @param precondition 真正能落地这条操作的前置条件（照此就能判断「什么时候它会变成可用」）
   */
  public record SalesOrderListBulkOperation(
      String code,
      String label,
      boolean available,
      String ownerModule,
      String precondition,
      String reason) {}

  /** 能力声明：把「哪些维度真有依据」「哪些批量操作能落地」「下游有没有接上」 显式报出来，而不是让调用方从 404 或空数组里去猜哪一环没通。 */
  public record SalesOrderListCapabilities(
      List<String> availableDimensions,
      List<SalesOrderListDimension> unavailableDimensions,
      List<SalesOrderListBulkOperation> bulkOperations,
      List<SalesOrderListSortOption> availableSorts,
      String filterableNote,
      String exportNote) {}
}
