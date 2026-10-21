package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 6.3.1.1 采购单列表的传输模型。
 *
 * <p>蓝图 09 卷 06-purchase-and-supply.md 的 6.3.1.1 原文：「采购单主列表：单号、供应商、SKU 行数、
 * 总金额、币种、状态、承诺到货日、已收数量/比例、逾期标记，支持多维筛选与导出。」
 *
 * <p><b>本文件最重要的纪律：拿不到的口径必须是 null，不是 0、不是假枚举。</b>
 *
 * <ul>
 *   <li><b>已收数量 / 比例</b>：口径是<b>本库 6.3.2.2 的到货跟踪</b>
 *       （{@code purchase_arrival_tracking.received_qty}，由 DB 触发器从到货流水重算，
 *       行里带 08 收货单号快照）。一张采购单<b>还没进入 6.3.2.2 到货跟踪</b>时，
 *       {@code receivedQuantity} 与 {@code receivedRatio} 是 <b>null</b>，
 *       {@code receivedQuantitySource} = {@code NOT_TRACKED} ——
 *       「还没登记到货」与「一件都没到」是两件事，混成一个 0 会让采购员误判该不该催货。
 *   <li><b>总金额</b>：一张采购单<b>只有一个币种</b>；跨币种的合计<b>按币种分组</b>返回
 *       （{@link PurchaseOrderListSummary#amountsByCurrency()}），
 *       <b>绝不</b>把 USD 与 EUR 加成一个数，也<b>绝不</b>按 1:1 折算。
 *   <li><b>本位币合计</b>：用<b>建单那一刻冻结的汇率快照</b>算（{@code fx_rate_to_functional}）。
 *       冻结不到的订单<b>不参与</b>合计并计入 {@code excludedOrderCount}，理由写进 note ——
 *       宁可少报一个合计，也不报一个用固定汇率凑出来的「看起来能对账」的数。
 * </ul>
 *
 * <p><b>命名。</b>全仓契约被 codegen 合并进<b>一个扁平文件</b>、同名 key 后者覆盖前者且不报冲突，
 * 所以本文件的每一个类型都带 {@code PurchaseOrderList} 前缀（已逐个 grep 比对，零重叠）。
 */
public final class PurchaseOrderListModels {

  private PurchaseOrderListModels() {}

  // ------------------------------------------------------------ 枚举

  /**
   * 采购单状态。
   *
   * <p>枚举与状态机在 V254 里就写死了（DB 触发器 guard_purchase_order_status 逐条钉死合法跃迁）：
   * 6.3.1.2（创建与编辑）、6.3.1.3（审批）交付时<b>直接用这套值</b>，不必 ALTER 本表。
   */
  public enum PurchaseOrderListStatus {
    DRAFT("草稿"),
    PENDING_APPROVAL("待审批"),
    APPROVED("已审批"),
    REJECTED("已驳回"),
    IN_FULFILLMENT("执行中"),
    CLOSED("已关闭");

    private final String label;

    PurchaseOrderListStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 可用排序维度。
   *
   * <p>刻意只有六个，且<b>每一个都对应一条真实存在的列</b>（金额、承诺到货日、创建时间、
   * 逾期判定）。不存在「排不出顺序」的排序项 —— 界面上点下去顺序没变，比没有这个按钮更糟。
   */
  public enum PurchaseOrderListSort {
    CREATED_AT_DESC("创建时间，最新在前", "o.created_at desc, o.id desc"),
    CREATED_AT_ASC("创建时间，最早在前", "o.created_at asc, o.id asc"),
    AMOUNT_DESC("总金额，由高到低", "o.total_amount desc, o.id desc"),
    AMOUNT_ASC("总金额，由低到高", "o.total_amount asc, o.id asc"),
    PROMISED_ASC("承诺到货日，最早在前", "o.promised_arrival_date asc, o.id asc"),
    OVERDUE_FIRST("逾期优先", "o.promised_arrival_date asc, o.id desc");

    private final String label;
    private final String sql;

    PurchaseOrderListSort(String label, String sql) {
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
    public static PurchaseOrderListSort parse(String raw) {
      if (raw == null || raw.isBlank()) return CREATED_AT_DESC;
      try {
        return valueOf(raw.trim().toUpperCase());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "排序维度只能是 CREATED_AT_DESC / CREATED_AT_ASC / AMOUNT_DESC / AMOUNT_ASC /"
                + " PROMISED_ASC / OVERDUE_FIRST");
      }
    }
  }

  // ------------------------------------------------------------ 入参

  /**
   * 采购单列表查询。
   *
   * <p>每一个筛选参数都对应一个<b>真实存在</b>的列或一条真实存在的聚合。
   *
   * <p><b>{@code actorId} / {@code actorDepartmentId} / {@code dataScope} 由服务端从
   * <b>已认证身份</b>与角色授权推导，<b>不接受请求参数传入</b> ——
   * 否则「只看我自己的单」会变成一个能越权的开关（照 9.1.1.1 SalesOrderList 的既有做法）。
   */
  public record PurchaseOrderListQuery(
      @Schema(nullable = true) UUID supplierRefId,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) UUID targetWarehouseId,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) OffsetDateTime createdFrom,
      @Schema(nullable = true) OffsetDateTime createdTo,
      @Schema(nullable = true) LocalDate promisedFrom,
      @Schema(nullable = true) LocalDate promisedTo,
      @Schema(nullable = true) Boolean overdueOnly,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort,
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size,
      @Schema(nullable = true) UUID actorId,
      @Schema(nullable = true) UUID actorDepartmentId,
      @Schema(nullable = true) String dataScope) {}

  // ------------------------------------------------------------ 出参

  /**
   * 列表行。
   *
   * <p><b>null 与 0 的区别在每个字段上都是实质的</b>：
   * {@code receivedQuantity} 是 0 表示「到货跟踪说确实一件都没到」，
   * 是 null 表示「这张单还没进入 6.3.2.2 到货跟踪」——
   * 前者该催货，后者该去把单登记进跟踪，两者对采购员的动作含义相反。
   *
   * @param lineCount SKU 行数（行数，不是去重 SKU 数）
   * @param skuCount 去重 SKU 数
   * @param receivedRatio 已收比例（0~1，scale 4，HALF_UP）；未跟踪时为 null
   * @param receivedQuantitySource 已收数量的口径来源：ARRIVAL_TRACKING / NOT_TRACKED
   * @param arrivalStatus 6.3.2.2 的到货状态（原样透传，供对照；未跟踪时为 null）
   * @param warehouseReceiptCode 最近一次到货登记引用的 08 收货单号（来源快照，不是本模块的事实）
   * @param overdue 逾期标记（口径见类的注释：承诺日 + DB 时钟 + 尚未全部到货）
   * @param overdueDays 逾期天数（未逾期时为 null）
   */
  public record PurchaseOrderListRow(
      UUID id,
      String orderNo,
      String status,
      UUID supplierRefId,
      String supplierCode,
      String supplierName,
      int lineCount,
      int skuCount,
      BigDecimal totalAmount,
      String currency,
      LocalDate promisedArrivalDate,
      @Schema(nullable = true) BigDecimal receivableQuantity,
      @Schema(nullable = true) BigDecimal receivedQuantity,
      @Schema(nullable = true) BigDecimal receivedRatio,
      String receivedQuantitySource,
      @Schema(nullable = true) String arrivalStatus,
      @Schema(nullable = true) String warehouseReceiptCode,
      boolean overdue,
      @Schema(nullable = true) Integer overdueDays,
      UUID targetWarehouseId,
      String targetWarehouseCode,
      String targetWarehouseName,
      UUID ownerUserId,
      @Schema(nullable = true) UUID ownerDepartmentId,
      int urgeCount,
      @Schema(nullable = true) OffsetDateTime lastUrgedAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 汇总条。
   *
   * <p><b>金额按币种分开报</b>：租户内同时存在多种币种时，把 USD 与 EUR 加成一个数
   * <b>没有意义</b> —— 那不是任何一个金额。所以返回 {@code amountsByCurrency}，
   * 而不是「一个假装跨币种可加的 totalAmount」。
   *
   * @param totalCount 命中筛选条件的采购单总数（不受分页影响）
   * @param overdueCount 命中筛选条件且逾期的采购单数
   * @param countsByStatus 各状态的单数
   * @param functionalTotal 本位币合计（<b>可为空</b>：冻结不到汇率快照时不给一个凑出来的数）
   */
  public record PurchaseOrderListSummary(
      long totalCount,
      long overdueCount,
      List<PurchaseOrderListStatusCount> countsByStatus,
      List<PurchaseOrderListCurrencyAmount> amountsByCurrency,
      @Schema(nullable = true) PurchaseOrderListFunctionalTotal functionalTotal,
      String totalAmountNote) {}

  /** 单个状态的单数。 */
  public record PurchaseOrderListStatusCount(String status, String label, long count) {}

  /** 单币种金额汇总。金额一律 BigDecimal，对应 DB 的 numeric(19,6)，禁浮点。 */
  public record PurchaseOrderListCurrencyAmount(String currency, BigDecimal amount, long orderCount) {}

  /**
   * 本位币合计。
   *
   * @param functionalCurrency 本位币
   * @param amount 合计金额（用<b>建单时冻结的汇率快照</b>换算，不是列表读取时的实时汇率）
   * @param includedOrderCount 参与合计的单数
   * @param excludedOrderCount 被排除的单数（没冻结到汇率快照，或口径不唯一）
   * @param excludedCurrencies 被排除的单涉及的币种
   * @param note 人可读的原因说明
   */
  public record PurchaseOrderListFunctionalTotal(
      String functionalCurrency,
      BigDecimal amount,
      long includedOrderCount,
      long excludedOrderCount,
      List<String> excludedCurrencies,
      String note) {}

  /**
   * 列表页：真分页（LIMIT/OFFSET + 独立的 COUNT(*) 总数查询）+ 与分页无关的全量汇总。
   *
   * @param dataScope 实际生效的数据范围：ALL / DEPARTMENT / SELF
   * @param dataScopeNote 数据范围的说明文案（界面上要讲清 SELF 到底筛了什么）
   */
  public record PurchaseOrderListPage(
      List<PurchaseOrderListRow> rows,
      int page,
      int size,
      long total,
      PurchaseOrderListSummary summary,
      String dataScope,
      String dataScopeNote) {}

  /**
   * 筛选项下拉用的租户级词表（<b>不随时间筛选变化</b>，避免下拉随翻页漂移）。
   *
   * @param suppliers 供应商选项（来自真实数据，不是硬编码词表）
   * @param warehouses 目标仓选项（来自真实数据）
   */
  public record PurchaseOrderListFilterOptions(
      List<String> statuses,
      List<String> currencies,
      List<PurchaseOrderListSupplierOption> suppliers,
      List<PurchaseOrderListWarehouseOption> warehouses,
      List<PurchaseOrderListSortOption> sorts) {}

  /** 供应商下拉项。 */
  public record PurchaseOrderListSupplierOption(
      UUID supplierRefId, String supplierCode, String supplierName, long orderCount) {}

  /** 目标仓下拉项。 */
  public record PurchaseOrderListWarehouseOption(
      UUID warehouseId, String warehouseCode, String warehouseName, long orderCount) {}

  /** 单个排序选项。 */
  public record PurchaseOrderListSortOption(String code, String label) {}

  // ------------------------------------------------------------ 批量操作

  /**
   * 催单（蓝图 6.3.1.1 输出句里的批量操作之一）。
   *
   * @param orderIds 要催的采购单 id（批量）
   * @param note 催单说明（会进留痕）
   */
  public record PurchaseOrderListUrgeRequest(List<UUID> orderIds, String note) {}

  /**
   * 关闭（蓝图 6.3.1.1 输出句里的批量操作之一）。
   *
   * @param reason 关闭理由（<b>必填</b>：「关掉了但不知道为什么」等于没关）
   */
  public record PurchaseOrderListCloseRequest(List<UUID> orderIds, String reason) {}

  /**
   * 批量操作结果。
   *
   * <p><b>逐单成败</b>：批量里有的单能关、有的不能关，是常事（已关闭的、不是你的、状态不允许）。
   * 返回逐单结论，而不是整批一个布尔 —— 整批一个布尔会让人重试整批，
   * 于是把已经关成功的单又关一遍。
   */
  public record PurchaseOrderListBatchResult(
      int requested, int succeeded, List<PurchaseOrderListBatchFailure> failures, String note) {}

  /** 单单失败原因。 */
  public record PurchaseOrderListBatchFailure(UUID orderId, String orderNo, String reason) {}

  // ------------------------------------------------------------ 转单（建采购单）

  /**
   * 由 6.2.2.1 的转单事实生成采购单。
   *
   * @param conversionIds 6.2.2.1 {@code purchase_request_conversion} 的 id 列表
   */
  public record PurchaseOrderListConversionRequest(List<UUID> conversionIds) {}

  /**
   * 转单结果。
   *
   * @param orderId 生成的采购单 id（<b>全部被拒时为 null</b>）
   * @param rejected 被拒收的转单事实与原因（<b>不静默丢弃</b>：丢掉的行等于少订的货）
   * @param fxSnapshotStatus 汇率快照状态：CAPTURED / UNAVAILABLE / NOT_APPLICABLE
   */
  public record PurchaseOrderListConversionResult(
      @Schema(nullable = true) UUID orderId,
      @Schema(nullable = true) String orderNo,
      int lineCount,
      @Schema(nullable = true) BigDecimal totalAmount,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) String functionalCurrency,
      String fxSnapshotStatus,
      List<PurchaseOrderListRejectedConversion> rejected) {}

  /** 被拒收的转单事实与原因码。 */
  public record PurchaseOrderListRejectedConversion(UUID conversionId, String reasonCode, String reason) {}

  // ------------------------------------------------------------ 能力声明

  /**
   * 蓝图维度里<b>本服务拿不到</b>的项。
   *
   * @param field 列表响应里对应为 null 的字段名
   * @param ownerModule 该维度的归属模块（谁交付之后它才会变成真数据）
   */
  public record PurchaseOrderListDimension(
      String field, String label, boolean available, String ownerModule, String reason) {}

  /**
   * 批量操作。
   *
   * @param precondition 真正能落地这条操作的前置条件
   */
  public record PurchaseOrderListBulkOperation(
      String code, String label, boolean available, String ownerModule, String precondition, String reason) {}

  /** 能力声明：把「哪些维度真有依据」「哪些批量操作能落地」显式报出来。 */
  public record PurchaseOrderListCapabilities(
      List<String> availableDimensions,
      List<PurchaseOrderListDimension> unavailableDimensions,
      List<PurchaseOrderListBulkOperation> bulkOperations,
      List<PurchaseOrderListSortOption> availableSorts,
      String filterableNote,
      String exportNote,
      String receivedQuantityNote) {}
}
