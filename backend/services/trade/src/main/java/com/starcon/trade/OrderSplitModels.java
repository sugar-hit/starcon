package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 9.3.1.1 拆单的传输模型。
 *
 * <p>蓝图原话：「把一个订单拆成多个履约单元：按仓库拆、按承运商拆、按发货时效拆（如部分现货先发）、 按平台限制拆。」输入是「拆单请求或自动拆单规则；可用库存分布」，输出是「多个履约请求 + 库存预占指令 + 发货回传按拆分执行」。
 *
 * <p><b>四条建模纪律</b>：
 *
 * <ul>
 *   <li><b>拆单不产生新的销售订单</b>：{@link SplitRow} 里没有新单号，只有一个
 *       {@code createsSalesOrder} 恒为 false 的字段 —— 蓝图加粗句「订单总金额与买家视角不变」
 *       的承载点。订单总额 {@code orderTotalAmount} 从拆分到发货**一个数字都不许变**，
 *       由 DB 的延迟约束触发器复核「各单元分摊额 + 尾差 == 订单总额」。
 *   <li><b>尾差有归属，不静默吞掉</b>：{@link UnitLine} 的 {@code residualAmount} 显式承载
 *       HALF_UP 分摊产生的几分钱，至多一行承载（DB 部分唯一索引 + 触发器复核）。
 *   <li><b>库存预占只是指令</b>：{@link SplitReservationCommand} 上有
 *       {@code upstreamReserved} 恒 false —— 7 预占在 warehouse 独立库，本模块锁不了，
 *       两边都以为占了同一批货是必须避免的事故。
 *   <li><b>金额与数量全程 BigDecimal</b>，数量对齐 DB 的 numeric(19,6)、金额对齐 numeric(19,4)。
 * </ul>
 */
public final class OrderSplitModels {

  private OrderSplitModels() {}

  // ------------------------------------------------------------ 枚举

  /** 拆单状态机（DB 词表，一字不改）。CANCELLED 是终态，不可离开。 */
  public enum SplitStatus {
    PLANNED("已拆分"),
    CONFIRMED("已确认"),
    DISPATCHED("已下发"),
    CANCELLED("已作废");

    private final String label;

    SplitStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 履约单元状态（与拆单头同向，单元不许比头更靠前）。 */
  public enum UnitStatus {
    PLANNED("待下发"),
    ALLOCATED("预占已登记"),
    DISPATCHED("已下发"),
    CANCELLED("已作废");

    private final String label;

    UnitStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 拆分维度（= 规则的主维度取值，DB 词表）。
   *
   * <p>「按仓库拆 / 按承运商拆 / 按时效拆 / 按平台限制拆」是**租户口径的落库存档配置**
   * （order_split_rule 的四个布尔 + primary_strategy），不是 Java 里的 if 链。
   */
  public enum SplitStrategy {
    WAREHOUSE("按仓库拆"),
    CARRIER("按承运商拆"),
    TIMELINESS("按发货时效拆"),
    PLATFORM("按平台限制拆");

    private final String label;

    SplitStrategy(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 预占指令状态（指令登记，不是预占事实 —— 见类注释第三条）。 */
  public enum SplitReservationStatus {
    REQUESTED("待下游认领"),
    ACKNOWLEDGED("已预占"),
    REJECTED("下游拒绝"),
    CANCELLED("已撤销");

    private final String label;

    SplitReservationStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 发货回传状态（与 V119 / V144 的回传词表同形）。 */
  public enum SplitWritebackStatus {
    PENDING("待回传"),
    SUBMITTED("已提交"),
    CONFIRMED("已回传"),
    REJECTED("平台拒绝"),
    PENDING_VERIFICATION("结果待查证");

    private final String label;

    SplitWritebackStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 候选落选原因（DB 词表）。
   *
   * <p>「为什么这批货没从上海仓发」是这一页最常被问的问题，所以落选候选必须逐条带原因返回
   * —— 与 V144 合单的 ExclusionReason 同一立场：让界面从答案里读，而不是从「结果里没有它」去猜。
   */
  public enum RejectReason {
    INSUFFICIENT_STOCK("库存不足"),
    PLATFORM_RESTRICTED("该仓不接此平台"),
    SHIP_BY_TOO_SLOW("发货时效不达标"),
    STOCK_TOO_STALE("库存快照太旧"),
    WAREHOUSE_INACTIVE("仓已停用"),
    NO_CARRIER("该仓没有承运商"),
    BEATEN_BY_TIEBREAK("字典序落败"),
    MAX_UNITS_EXCEEDED("超过单元数上限"),
    PARTIAL_FULFILMENT_DISABLED("规则不允许部分先发"),
    SAME_WAREHOUSE_ALREADY_FULL("该仓已把这一行发满");

    private final String label;

    RejectReason(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 能力声明

  @Schema(description = "9.3.1.1 拆单能力与上下游可用性")
  public record OrderSplitCapabilities(
      @Schema(description = "能力维度清单") List<SplitDimension> dimensions,
      @Schema(description = "拆单状态机取值") List<SplitStatus> statuses,
      @Schema(description = "拆分维度取值") List<SplitStrategy> strategies,
      @Schema(description = "预占指令状态取值") List<SplitReservationStatus> reservationStatuses,
      @Schema(description = "回传状态取值") List<SplitWritebackStatus> writebackStatuses,
      @Schema(description = "候选落选原因词表") List<RejectReason> rejectReasons,
      @Schema(description = "本租户是否已配置拆单规则（false 时不能落拆单）") boolean ruleConfigured,
      @Schema(description = "🔴 本模块不预占上游库存：预占住在 warehouse 独立库，不得跨库读写")
          boolean reservesUpstreamStock,
      @Schema(description = "🔴 上游 9.2 审单整条线未交付，本模块只存逻辑引用与快照列")
          boolean orderReviewConnected,
      @Schema(description = "口径与交接说明") String notes,
      @Schema(description = "下游缺口说明") String downstreamGaps) {

    @Schema(description = "能力维度：依赖什么、是否真的可用、不可用时怎么办")
    public record SplitDimension(
        @Schema(description = "维度码") String code,
        @Schema(description = "维度名") String label,
        @Schema(description = "是否真的可用") boolean available,
        @Schema(description = "上游来源与归属") String source,
        @Schema(description = "不可用时的原因与交接方式；可用时为 null") String unavailableReason) {}
  }

  // ------------------------------------------------------------ 拆单规则

  @Schema(description = "拆单规则（每租户一条）")
  public record SplitRule(
      @Schema(description = "规则 id") UUID id,
      @Schema(description = "规则是否启用") boolean enabled,
      @Schema(description = "是否按仓库拆") boolean splitByWarehouse,
      @Schema(description = "是否按承运商拆") boolean splitByCarrier,
      @Schema(description = "是否按发货时效拆") boolean splitByTimeliness,
      @Schema(description = "是否按平台限制拆") boolean splitByPlatformRestriction,
      @Schema(description = "主维度（分组时以它为准）") SplitStrategy primaryStrategy,
      @Schema(description = "单次拆单最多拆几个履约单元") int maxUnitsPerSplit,
      @Schema(description = "是否允许部分先发（同一行跨单元分批）") boolean allowPartialFulfillment,
      @Schema(description = "时效档位上限（小时）；null = 不限，不是 0") Integer maxShipByHours,
      @Schema(description = "库存新鲜度上限（小时）；null = 不限") Integer stockFreshnessHours,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "更新时间") OffsetDateTime updatedAt) {}

  @Schema(description = "保存拆单规则")
  public record SplitRuleInput(
      @Schema(description = "规则是否启用（默认 true）") Boolean enabled,
      @Schema(description = "是否按仓库拆（默认 true）") Boolean splitByWarehouse,
      @Schema(description = "是否按承运商拆（默认 true）") Boolean splitByCarrier,
      @Schema(description = "是否按发货时效拆（默认 false）") Boolean splitByTimeliness,
      @Schema(description = "是否按平台限制拆（默认 true）") Boolean splitByPlatformRestriction,
      @Schema(description = "主维度；必须是一个被开启的维度") SplitStrategy primaryStrategy,
      @NotNull
          @Min(1)
          @Max(64)
          @Schema(description = "单次拆单最多拆几个履约单元")
          Integer maxUnitsPerSplit,
      @Schema(description = "是否允许部分先发（默认 true）") Boolean allowPartialFulfillment,
      @Schema(description = "时效档位上限（小时）；null = 不限") Integer maxShipByHours,
      @Schema(description = "库存新鲜度上限（小时）；null = 不限") Integer stockFreshnessHours) {}

  // ------------------------------------------------------------ 仓与库存分布

  @Schema(description = "发货仓登记")
  public record SplitWarehouseRow(
      @Schema(description = "仓 id") UUID id,
      @Schema(description = "仓引用码（租户内唯一）") String warehouseRef,
      @Schema(description = "仓名") String warehouseName,
      @Schema(description = "货权归属（法人）") String legalEntityCode,
      @Schema(description = "该仓默认承运商") String carrierCode,
      @Schema(description = "承诺发货时效（小时）；null = 未知，不是 0") Integer shipByHours,
      @Schema(description = "平台限制清单（逗号分隔）；空 = 不限平台") String allowedPlatforms,
      @Schema(description = "是否参与拆单候选") boolean active,
      @Schema(description = "备注") String notes,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "创建时刻") OffsetDateTime createdAt) {}

  @Schema(description = "登记 / 更新发货仓")
  public record SplitWarehouseInput(
      @NotBlank @Size(max = 64) @Schema(description = "仓引用码") String warehouseRef,
      @Size(max = 120) @Schema(description = "仓名") String warehouseName,
      @Schema(description = "货权归属（法人）") String legalEntityCode,
      @Schema(description = "该仓默认承运商；按承运商拆单时用它") String carrierCode,
      @Schema(description = "承诺发货时效（小时）；null = 未知") Integer shipByHours,
      @Schema(
              description =
                  "平台限制清单：逗号分隔的平台码大写列表（如 AMAZON,SHOPIFY）；空 = 不限平台")
          String allowedPlatforms,
      @Schema(description = "是否参与拆单候选（默认 true）") Boolean active,
      @Schema(description = "备注") String notes) {}

  @Schema(description = "可用库存分布快照（7.1.1.3 的租户可维护替身）")
  public record SplitStockSnapshotRow(
      @Schema(description = "快照 id") UUID id,
      @Schema(description = "仓引用码") String warehouseRef,
      @Schema(description = "SKU 逻辑引用") String skuRef,
      @Schema(description = "可用量") BigDecimal availableQty,
      @Schema(description = "采集时刻（由采集方给出，不由写入时刻冒充）") OffsetDateTime collectedAt,
      @Schema(description = "来源：MODULE_7_1_1_3 / TENANT_MAINTAINED") String stockSource,
      @Schema(description = "来源单号（来源为 MODULE_7_1_1_3 时必填）") String sourceReference,
      @Schema(description = "备注") String notes,
      @Schema(description = "创建时刻") OffsetDateTime createdAt) {}

  @Schema(description = "登记 / 更新可用库存分布")
  public record SplitStockSnapshotInput(
      @NotBlank @Size(max = 64) @Schema(description = "仓引用码") String warehouseRef,
      @NotBlank @Size(max = 64) @Schema(description = "SKU 逻辑引用") String skuRef,
      @NotNull
          @DecimalMin("0")
          @Schema(description = "可用量（numeric(19,6)）")
          BigDecimal availableQty,
      @NotNull
          @Schema(description = "采集时刻（timestamptz）。由采集方给出，不取服务器当前时间")
          OffsetDateTime collectedAt,
      @Schema(description = "来源：MODULE_7_1_1_3 / TENANT_MAINTAINED（默认后者）") String stockSource,
      @Schema(description = "来源单号；stockSource=MODULE_7_1_1_3 时必填") String sourceReference,
      @Schema(description = "备注") String notes) {}

  // ------------------------------------------------------------ 拆单入参

  @Schema(description = "订单行快照（拆单请求里随行带来的真实业务数据，不是 mock）")
  public record OrderLineInput(
      @NotBlank @Size(max = 64) @Schema(description = "订单行引用（UUID 文本或租户自己的行号）")
          String orderLineRef,
      @Size(max = 64) @Schema(description = "SKU 逻辑引用") String skuRef,
      @Size(max = 64) @Schema(description = "SKU 编码快照") String skuCode,
      @Size(max = 200) @Schema(description = "SKU 名称快照") String skuName,
      @NotNull @DecimalMin(value = "0", inclusive = false) @Schema(description = "本行总数量") BigDecimal qty,
      @NotNull @DecimalMin("0") @Schema(description = "本行总金额（numeric(19,4)，与订单总额口径一致）")
          BigDecimal amount) {}

  @Schema(description = "落一次拆单的入参（蓝图输入「拆单请求」）")
  public record SplitInput(
      @NotBlank @Size(max = 64) @Schema(description = "订单引用（逻辑引用，不校验存在性：上游 9.2 审单未交付）")
          String orderRef,
      @Size(max = 64) @Schema(description = "我方单号快照") String orderNo,
      @Schema(
              description =
                  "订单内容来源：SALES_ORDER = 按 tenant 从 sales_order 真实联表读取；"
                      + "REQUEST_SNAPSHOT = 用下面的 lines 快照。留空则先试 SALES_ORDER，取不到回落快照")
          String orderSource,
      @Schema(description = "币种（ISO 4217 alpha-3）") String currencyCode,
      @Schema(description = "收货人姓名快照") String recipientName,
      @Schema(description = "收货人电话快照") String recipientPhone,
      @Schema(description = "收货人邮箱快照") String recipientEmail,
      @Schema(description = "国家码（ISO 3166-1 alpha-2）") String countryCode,
      @Schema(description = "可读地址快照") String addressLine,
      @Schema(description = "平台码快照（决定平台限制维度怎么切）") String platformCode,
      @Schema(description = "店铺码快照") String shopCode,
      @Schema(description = "平台单号快照") String platformOrderRef,
      @NotNull @DecimalMin("0") @Schema(description = "订单总金额（拆单全程不变，蓝图加粗句）")
          BigDecimal orderTotalAmount,
      @Schema(description = "订单行快照；orderSource=REQUEST_SNAPSHOT 时必填")
          List<@Valid OrderLineInput> lines,
      @NotNull @Schema(description = "拆分时刻（timestamptz）。由调用方显式给出，不取服务器当前时间")
          OffsetDateTime plannedAt,
      @Schema(description = "备注") String note) {}

  @Schema(
      description =
          "拆单预览的结果：与真正落库用的是**同一段判定逻辑**，"
              + "差别只是不写 order_split* 那几张表")
  public record SplitPreview(
      @Schema(description = "本次按哪个维度拆") SplitStrategy strategy,
      @Schema(description = "拟拆出的履约单元（尚未落库）") List<PreviewUnit> units,
      @Schema(description = "逐行逐仓候选与落选原因（尚未落库）") List<PreviewCandidate> candidates,
      @Schema(description = "订单总金额") BigDecimal orderTotalAmount,
      @Schema(description = "各单元分摊合计") BigDecimal allocatedTotal,
      @Schema(description = "尾差合计（分摊舍入后归到某一行的那几分钱）") BigDecimal residualTotal,
      @Schema(description = "🔴 金额守恒是否成立（各单元分摊 + 尾差 == 订单总额）") boolean amountConserved,
      @Schema(description = "🔴 是否会产生新的销售订单（恒为 false，蓝图加粗句）") boolean createsSalesOrder,
      @Schema(description = "订单内容来源：SALES_ORDER / REQUEST_SNAPSHOT") String orderSource,
      @Schema(description = "拆单规则是否已配置") boolean ruleConfigured,
      @Schema(description = "阻断原因；未阻断时为 null") String blockedReason) {}

  @Schema(description = "预览里的一个履约单元")
  public record PreviewUnit(
      @Schema(description = "单元序号（确定性排序：字典序）") int unitSeq,
      @Schema(description = "本单元用哪个维度切出来") SplitStrategy unitStrategy,
      @Schema(description = "仓引用码") String warehouseRef,
      @Schema(description = "承运商码") String carrierCode,
      @Schema(description = "发货时效档位（小时）；null = 未知") Integer shipByHours,
      @Schema(description = "承诺发货时刻；按时效拆时必填") OffsetDateTime shipByAt,
      @Schema(description = "平台码") String platformCode,
      @Schema(description = "货权归属") String legalEntityCode,
      @Schema(description = "行数") int lineCount,
      @Schema(description = "件数") BigDecimal totalQty,
      @Schema(description = "分摊金额") BigDecimal allocatedAmount) {}

  @Schema(description = "预览里的一条候选（含落选原因）")
  public record PreviewCandidate(
      @Schema(description = "订单行引用") String orderLineRef,
      @Schema(description = "仓引用码") String warehouseRef,
      @Schema(description = "承运商码") String carrierCode,
      @Schema(description = "是否被选中") boolean selected,
      @Schema(description = "落选原因码；被选中时为空串") String rejectReason,
      @Schema(description = "落选原因中文；被选中时为空串") String rejectReasonLabel,
      @Schema(description = "落选说明") String rejectDetail,
      @Schema(description = "该仓该行可用量") BigDecimal availableQty,
      @Schema(description = "该行需求量") BigDecimal demandQty,
      @Schema(description = "候选排序键（确定性 tiebreak）") int rankNo) {}

  // ------------------------------------------------------------ 拆单结果

  @Schema(description = "拆单一行（汇总数由 DB 从单元行实算回填）")
  public record SplitRow(
      @Schema(description = "拆单 id") UUID id,
      @Schema(description = "拆单号") String splitNo,
      @Schema(description = "状态") SplitStatus status,
      @Schema(description = "状态中文") String statusLabel,
      @Schema(description = "订单引用（逻辑引用）") String orderRef,
      @Schema(description = "我方单号") String orderNo,
      @Schema(description = "订单内容来源") String orderSource,
      @Schema(description = "平台码快照") String orderOriginPlatform,
      @Schema(description = "店铺码快照") String orderOriginShop,
      @Schema(description = "平台单号快照") String platformOrderRef,
      @Schema(description = "收货人姓名") String recipientName,
      @Schema(description = "收货人电话") String recipientPhone,
      @Schema(description = "收货人邮箱") String recipientEmail,
      @Schema(description = "国家码") String countryCode,
      @Schema(description = "可读地址") String addressLine,
      @Schema(description = "币种") String currencyCode,
      @Schema(description = "🔴 订单总金额（拆单全程不变）") BigDecimal orderTotalAmount,
      @Schema(description = "🔴 是否产生新的销售订单（恒 false）") boolean createsSalesOrder,
      @Schema(description = "本次拆分维度") SplitStrategy strategy,
      @Schema(description = "履约单元数（DB 实算）") int unitCount,
      @Schema(description = "涉及仓数（DB 实算）") int distinctWarehouseCount,
      @Schema(description = "涉及承运商数（DB 实算）") int distinctCarrierCount,
      @Schema(description = "各单元分摊合计（DB 实算）") BigDecimal allocatedAmountTotal,
      @Schema(description = "尾差合计（DB 实算）") BigDecimal residualAmountTotal,
      @Schema(description = "件数合计（DB 实算）") BigDecimal totalQty,
      @Schema(description = "拆分时刻") OffsetDateTime plannedAt,
      @Schema(description = "确认时刻") OffsetDateTime confirmedAt,
      @Schema(description = "下发时刻") OffsetDateTime dispatchedAt,
      @Schema(description = "作废时刻") OffsetDateTime cancelledAt,
      @Schema(description = "作废原因") String cancelReason,
      @Schema(description = "备注") String note,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "创建时刻") OffsetDateTime createdAt) {}

  @Schema(description = "履约单元（蓝图输出①「多个履约请求」在本服务的落法）")
  public record SplitUnit(
      @Schema(description = "单元 id") UUID id,
      @Schema(description = "单元序号") int unitSeq,
      @Schema(description = "单元状态") UnitStatus unitStatus,
      @Schema(description = "状态中文") String unitStatusLabel,
      @Schema(description = "本单元用哪个维度切出来") SplitStrategy unitStrategy,
      @Schema(description = "仓引用码") String warehouseRef,
      @Schema(description = "承运商码") String carrierCode,
      @Schema(description = "承诺发货时刻；按时效拆时必填") OffsetDateTime shipByAt,
      @Schema(description = "时效档位（小时）") Integer shipByHours,
      @Schema(description = "平台码") String platformCode,
      @Schema(description = "货权归属") String legalEntityCode,
      @Schema(description = "行数（DB 实算）") int lineCount,
      @Schema(description = "件数（DB 实算）") BigDecimal totalQty,
      @Schema(description = "分摊金额（DB 实算）") BigDecimal allocatedAmount,
      @Schema(description = "下发时刻") OffsetDateTime dispatchedAt,
      @Schema(description = "备注") String note) {}

  @Schema(description = "单元行级明细（金额守恒的最小粒度）")
  public record UnitLine(
      @Schema(description = "明细 id") UUID id,
      @Schema(description = "单元 id") UUID unitId,
      @Schema(description = "订单行引用") String orderLineRef,
      @Schema(description = "SKU 逻辑引用") String skuRef,
      @Schema(description = "SKU 编码快照") String skuCode,
      @Schema(description = "SKU 名称快照") String skuName,
      @Schema(description = "本单元切走的数量") BigDecimal allocatedQty,
      @Schema(description = "本单元分摊金额") BigDecimal allocatedAmount,
      @Schema(description = "🔴 尾差承载额（全拆单至多一行非零）") BigDecimal residualAmount) {}

  @Schema(description = "候选依据（落选候选与被选候选同等重要）")
  public record CandidateRow(
      @Schema(description = "候选 id") UUID id,
      @Schema(description = "订单行引用") String orderLineRef,
      @Schema(description = "仓引用码") String warehouseRef,
      @Schema(description = "承运商码") String carrierCode,
      @Schema(description = "是否被选中") boolean selected,
      @Schema(description = "落选原因码；被选中时为空串") String rejectReason,
      @Schema(description = "落选原因中文") String rejectReasonLabel,
      @Schema(description = "落选说明") String rejectDetail,
      @Schema(description = "该仓该行可用量") BigDecimal availableQty,
      @Schema(description = "需求量") BigDecimal demandQty,
      @Schema(description = "库存采集时刻") OffsetDateTime collectedAt,
      @Schema(description = "库存来源") String stockSource,
      @Schema(description = "候选排序键") int rankNo) {}

  @Schema(description = "库存预占指令（蓝图输出②；本服务登记，不预占上游库存）")
  public record SplitReservationCommand(
      @Schema(description = "指令 id") UUID id,
      @Schema(description = "单元 id") UUID unitId,
      @Schema(description = "SKU 逻辑引用") String skuRef,
      @Schema(description = "订单行引用") String orderLineRef,
      @Schema(description = "请求预占数量") BigDecimal reservedQty,
      @Schema(description = "仓引用码") String warehouseRef,
      @Schema(description = "目标服务（7 预占归属 warehouse）") String targetService,
      @Schema(description = "指令来源：SPLIT_PLAN / MANUAL") String commandSource,
      @Schema(description = "指令状态") SplitReservationStatus commandStatus,
      @Schema(description = "状态中文") String commandStatusLabel,
      @Schema(description = "warehouse 侧返回的预占号") String externalReservationRef,
      @Schema(description = "🔴 本模块不预占上游库存（恒 false）") boolean upstreamReserved,
      @Schema(description = "拒绝原因") String rejectReason,
      @Schema(description = "回执时刻") OffsetDateTime acknowledgedAt,
      @Schema(description = "创建时刻") OffsetDateTime createdAt) {}

  @Schema(description = "发货回传任务（蓝图输出③：按拆分执行，一单元一条）")
  public record SplitWritebackTask(
      @Schema(description = "任务 id") UUID id,
      @Schema(description = "单元 id") UUID unitId,
      @Schema(description = "拆单 id") UUID splitId,
      @Schema(description = "平台码") String platformCode,
      @Schema(description = "店铺码") String shopCode,
      @Schema(description = "平台单号") String platformOrderRef,
      @Schema(description = "物流号") String trackingNumber,
      @Schema(description = "承运商码") String carrierCode,
      @Schema(description = "状态") SplitWritebackStatus status,
      @Schema(description = "状态中文") String statusLabel,
      @Schema(description = "尝试次数") int attemptCount,
      @Schema(description = "最后一次错误") String lastError,
      @Schema(description = "提交时刻") OffsetDateTime submittedAt,
      @Schema(description = "结算时刻") OffsetDateTime settledAt) {}

  @Schema(description = "拆单事件（append-only）")
  public record SplitEvent(
      @Schema(description = "事件 id") UUID id,
      @Schema(description = "事件类型") String eventType,
      @Schema(description = "原状态") String fromStatus,
      @Schema(description = "新状态") String toStatus,
      @Schema(description = "操作人成员 id") UUID actorMembershipId,
      @Schema(description = "说明") String detail,
      @Schema(description = "发生时刻") OffsetDateTime occurredAt) {}

  @Schema(description = "拆单详情：头 + 单元 + 行级明细 + 候选 + 预占指令 + 回传 + 事件流")
  public record SplitDetail(
      @Schema(description = "拆单") SplitRow split,
      @Schema(description = "履约单元（9.3.3.1 后续消费的就是这些行）") List<SplitUnit> units,
      @Schema(description = "单元行级明细") List<UnitLine> unitLines,
      @Schema(description = "逐行逐仓候选依据（落选带原因）") List<CandidateRow> candidates,
      @Schema(description = "库存预占指令（登记，不是预占事实）") List<SplitReservationCommand> reservations,
      @Schema(description = "发货回传任务") List<SplitWritebackTask> writebackTasks,
      @Schema(description = "事件流") List<SplitEvent> events,
      @Schema(description = "🔴 金额守恒复算（DB 侧独立复核的结果）") boolean amountConserved,
      @Schema(description = "🔴 是否产生新的销售订单（恒 false）") boolean createsSalesOrder,
      @Schema(description = "读这一页时必须知道的事") String notes) {}

  @Schema(description = "拆单列表查询")
  public record SplitQuery(
      @Schema(description = "拆单号 / 订单号 / 订单引用模糊匹配") String keyword,
      @Schema(description = "状态筛选") String status,
      @Schema(description = "拆分维度筛选") String strategy,
      @Schema(description = "只看含指定订单的拆单") String orderRef,
      @Schema(description = "仓引用码筛选") String warehouseRef,
      @Schema(description = "页码（从 0 开始）") int page,
      @Schema(description = "每页行数") int size) {}

  @Schema(description = "拆单分页")
  public record SplitPage(
      @Schema(description = "本页数据") List<SplitRow> items,
      @Schema(description = "总条数（不受分页影响）") long total,
      @Schema(description = "当前页") int page,
      @Schema(description = "每页行数") int size,
      @Schema(description = "汇总：已下发拆单数（不受分页影响）") long dispatchedCount,
      @Schema(description = "汇总：待认领预占指令数（不受分页影响）") long pendingReservationCount,
      @Schema(description = "汇总：待回传任务数（不受分页影响）") long pendingWritebackCount) {}

  @Schema(description = "推进拆单状态")
  public record SplitStatusInput(
      @NotNull @Schema(description = "目标状态：CONFIRMED / DISPATCHED / CANCELLED") SplitStatus status,
      @Schema(description = "作废原因；status=CANCELLED 时必填") String reason,
      @NotNull @Schema(description = "发生时刻（timestamptz）；CANCELLED 时也用作废时刻")
          OffsetDateTime occurredAt) {}

  @Schema(description = "登记预占指令回执（warehouse 侧回执）")
  public record SplitReservationInput(
      @NotNull @Schema(description = "指令状态：ACKNOWLEDGED / REJECTED") SplitReservationStatus status,
      @Schema(description = "warehouse 侧预占号；status=ACKNOWLEDGED 时必填") String externalReservationRef,
      @Schema(description = "拒绝原因；status=REJECTED 时必填") String rejectReason,
      @NotNull @Schema(description = "回执时刻（timestamptz）") OffsetDateTime occurredAt) {}

  @Schema(description = "登记发货回传结果（按拆分执行：一单元一条）")
  public record SplitWritebackInput(
      @NotNull @Schema(description = "回传状态") SplitWritebackStatus status,
      @Schema(description = "物流号；status=CONFIRMED 时必填") String trackingNumber,
      @Schema(description = "承运商码") String carrierCode,
      @Schema(description = "失败原因；status=REJECTED 时给") String lastError,
      @NotNull @Schema(description = "发生时刻（timestamptz）") OffsetDateTime occurredAt) {}

  @Schema(description = "操作结果")
  public record SplitSaved(
      @Schema(description = "拆单 id") UUID id,
      @Schema(description = "拆单号") String splitNo,
      @Schema(description = "状态") SplitStatus status,
      @Schema(description = "履约单元数") int unitCount,
      @Schema(description = "各单元分摊合计") BigDecimal allocatedAmountTotal,
      @Schema(description = "尾差合计") BigDecimal residualAmountTotal,
      @Schema(description = "🔴 金额守恒是否成立") boolean amountConserved,
      @Schema(description = "提示语（说清这一步实际做了什么，而不是「操作成功」）") String message) {}

  @Schema(description = "预占指令列表查询")
  public record SplitReservationQuery(
      @Schema(description = "拆单 id") UUID splitId,
      @Schema(description = "单元 id") UUID unitId,
      @Schema(description = "状态筛选") String status,
      @Schema(description = "SKU 引用模糊匹配") String skuRef,
      @Schema(description = "页码（从 0 开始）") int page,
      @Schema(description = "每页行数") int size) {}

  @Schema(description = "预占指令分页")
  public record SplitReservationPage(
      @Schema(description = "本页数据") List<SplitReservationCommand> items,
      @Schema(description = "总条数（不受分页影响）") long total,
      @Schema(description = "当前页") int page,
      @Schema(description = "每页行数") int size,
      @Schema(description = "汇总：待认领数（不受分页影响）") long requestedCount,
      @Schema(description = "汇总：已预占数（不受分页影响）") long acknowledgedCount,
      @Schema(description = "汇总：被拒数（不受分页影响）") long rejectedCount) {}

  @Schema(description = "回传任务列表查询")
  public record SplitWritebackQuery(
      @Schema(description = "拆单 id") UUID splitId,
      @Schema(description = "平台码") String platformCode,
      @Schema(description = "状态筛选") String status,
      @Schema(description = "平台单号模糊匹配") String platformOrderRef,
      @Schema(description = "页码（从 0 开始）") int page,
      @Schema(description = "每页行数") int size) {}

  @Schema(description = "回传任务分页")
  public record SplitWritebackPage(
      @Schema(description = "本页数据") List<SplitWritebackTask> items,
      @Schema(description = "总条数（不受分页影响）") long total,
      @Schema(description = "当前页") int page,
      @Schema(description = "每页行数") int size,
      @Schema(description = "汇总：待回传数（不受分页影响）") long pendingCount,
      @Schema(description = "汇总：被平台拒数（不受分页影响）") long rejectedCount,
      @Schema(description = "汇总：结果待查证数（不受分页影响）") long pendingVerificationCount) {}
}
