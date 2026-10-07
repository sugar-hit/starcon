package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 8.3.1.1 出库单列表的传输模型。
 *
 * <p>蓝图原话：「出库作业主列表：来源（销售/采购退货/调拨/加工出库）、SKU 行数、件数、状态
 * （待拣/拣货中/待复核/待打包/待发货/已发货/取消）、发货仓、承运渠道。」输入：「筛选条件
 * （仓库、来源、状态、时间）；用户数据范围。」输出：「分页列表 + 批量操作（生成拣货任务、
 * 打印、取消）。」以及本模块被点名的那条关系：「**出库单 ≠ 销售订单**：一订单可多出库单
 * （分仓/分批），出库单是履约动作的载体。」
 *
 * <p><b>出库单是履约动作的载体，不是订单的影子。</b> 这决定了两个数据形状：
 * 外部单号是 {@code String} 而不是指向订单表的外键（9 订单履约尚未交付，跨服务也不能建
 * FK），并且刻意**不加** (tenant_id, source_external_ref) 唯一索引 —— 加了就等于把
 * 「分仓/分批拆多张出库单」在数据上禁掉了，那正是这条关系要表达的相反面。
 *
 * <p><b>七态状态机在 {@link OutboundOrderStatus} 里显式建模</b>，与 V72 的触发器白名单
 * 一一对应（两处都写，改一处必须改另一处；单测里有一条断言比对两者）。
 *
 * <p><b>「生成拣货任务」只做到交接请求为止</b>：蓝图把 8.3.2.1 拣货任务列为下游，而它
 * 拥有拣货任务表、拣货明细与异常上报。本模块不建那张表，也不假装它存在 —— 它做的是
 * 真实的三件事：校验待拣状态、落一行交接请求、把状态推到拣货中。8.3.2.1 交付后按
 * (tenant_id, order_id) 接手这张表。
 */
public final class OutboundOrderModels {

  private OutboundOrderModels() {}

  // ------------------------------------------------------------ 枚举

  /**
   * 出库来源（四类，蓝图原文：销售 / 采购退货 / 调拨 / 加工出库）。
   *
   * <p>四类的<b>可信度不一样，所以表达方式也不一样</b>：
   *
   * <ul>
   *   <li>{@link #SALES} 销售 —— 上游是 9 订单履约，<b>尚未交付</b>：只存外部订单号字符串，
   *       跨服务不建 FK、不查对方的表（AGENTS.md）。
   *   <li>{@link #PURCHASE_RETURN} 采购退货 —— 上游是 6 采购，同样未交付，同样只存单号。
   *   <li>{@link #PRODUCTION} 加工出库 —— 上游是 10 加工，同样未交付，同样只存工单号。
   *   <li>{@link #TRANSFER} 调拨 —— 上游是 7.5.1.1，<b>已交付且同在 warehouse 服务</b>：
   *       真复合外键到 {@code transfer_order}，建单时还会校验发货仓与明细 SKU 对得上。
   * </ul>
   *
   * <p>前三类合起来是「外部 id 字符串」，第四类是「真关联」，两者在库上互斥（V72 的 CHECK）。
   */
  public enum OutboundSourceType {
    SALES("销售", "9 订单履约：只存外部销售订单号，不跨服务建外键"),
    PURCHASE_RETURN("采购退货", "6 采购：只存外部退货单号，不跨服务建外键"),
    TRANSFER("调拨", "7.5.1.1 调拨单：真关联 transfer_order，发货仓与明细 SKU 都要对得上"),
    PRODUCTION("加工出库", "10 加工：只存外部工单号，不跨服务建外键");

    private final String label;
    private final String description;

    OutboundSourceType(String label, String description) {
      this.label = label;
      this.description = description;
    }

    public String label() {
      return label;
    }

    public String description() {
      return description;
    }
  }

  /**
   * 出库单状态（七态）与显式转换矩阵。
   *
   * <p>七个值与蓝图括号里逐字对应：待拣 / 拣货中 / 待复核 / 待打包 / 待发货 / 已发货 / 取消。
   * 转换矩阵与 V72 的 {@code outbound_order_status_guard} 触发器白名单一一对应：
   * 服务层用它给出可读的拒绝理由，触发器保证绕过服务层也过不去。
   *
   * <p><b>两条返工边不是随手加的</b>：
   *
   * <ul>
   *   <li>待复核 → 拣货中：复核发现拣错 / 缺件，退回拣货重做。
   *   <li>待打包 → 待复核：打包时发现缺件，退回复核重新确认。
   * </ul>
   *
   * <p><b>复核不通过为什么不退回「待拣」</b>：待拣的含义是「一件都没拣」，而复核不通过时
   * 货已经离开库位、正处在返工循环里。退回待拣会让待拣同时表达「没开始」与「拣错了
   * 重来」，作业台按状态分组时这两种单子必须分开处理。这个选择同时让「一张出库单只
   * 生成一次拣货任务」（{@code outbound_pick_request} 上的 UNIQUE）站得住 ——
   * 拣货任务本来就是一次性交接，不是反复重发的工单。
   */
  public enum OutboundOrderStatus {
    PENDING_PICKING("待拣", false),
    PICKING("拣货中", false),
    PENDING_RECHECK("待复核", false),
    PENDING_PACKING("待打包", false),
    PENDING_SHIPMENT("待发货", false),
    SHIPPED("已发货", true),
    CANCELLED("取消", true);

    private final String label;
    private final boolean terminal;

    OutboundOrderStatus(String label, boolean terminal) {
      this.label = label;
      this.terminal = terminal;
    }

    public String label() {
      return label;
    }

    /** 终态：已发货 / 已取消。终态行的来源、发货仓、承运渠道在 DB 层被冻结。 */
    public boolean terminal() {
      return terminal;
    }

    /** 是否已经离开待拣 —— 即拣货任务是否已被交接过。 */
    public boolean pickingStarted() {
      return this != PENDING_PICKING;
    }

    /**
     * 一次合法流转：起止状态、是否强制填原因、以及一句话说明。
     *
     * <p>说明会被写进 append-only 的 {@code outbound_order_event.detail}，
     * 所以它读起来要能独立成立（半年后只看这一行也知道当时发生了什么）。
     */
    public record Transition(
        OutboundOrderStatus from,
        OutboundOrderStatus to,
        boolean reasonRequired,
        boolean requiresCarrier,
        String note) {}

    private static final Map<OutboundOrderStatus, List<Transition>> MATRIX =
        Map.of(
            PENDING_PICKING,
                List.of(
                    new Transition(
                        PENDING_PICKING,
                        PICKING,
                        false,
                        false,
                        "生成拣货任务，出库单交给 8.3.2.1 拣货作业"),
                    new Transition(PENDING_PICKING, CANCELLED, true, false, "单据作废")),
            PICKING,
                List.of(
                    new Transition(
                        PICKING, PENDING_RECHECK, false, false, "拣货完成，提交复核（8.3.3.1）"),
                    new Transition(PICKING, CANCELLED, true, false, "拣货中发现无法发货，单据作废")),
            PENDING_RECHECK,
                List.of(
                    new Transition(
                        PENDING_RECHECK,
                        PENDING_PACKING,
                        false,
                        false,
                        "复核通过，提交打包（8.3.3.2）"),
                    new Transition(
                        PENDING_RECHECK,
                        PICKING,
                        true,
                        false,
                        "复核发现拣错或缺件，退回拣货重做"),
                    new Transition(PENDING_RECHECK, CANCELLED, true, false, "复核不通过且无法补救，作废")),
            PENDING_PACKING,
                List.of(
                    new Transition(
                        PENDING_PACKING,
                        PENDING_SHIPMENT,
                        false,
                        false,
                        "打包完成，交接发货（8.3.4.1）"),
                    new Transition(
                        PENDING_PACKING,
                        PENDING_RECHECK,
                        true,
                        false,
                        "打包时发现缺件，退回复核重新确认"),
                    new Transition(PENDING_PACKING, CANCELLED, true, false, "打包中发现无法发货，作废")),
            PENDING_SHIPMENT,
                List.of(
                    new Transition(
                        PENDING_SHIPMENT,
                        SHIPPED,
                        true,
                        true,
                        "承运商已揽收，出库单完成"),
                    new Transition(PENDING_SHIPMENT, CANCELLED, true, false, "发货前取消")),
            SHIPPED,
                List.of(),
            CANCELLED,
                List.of());

    /** 合法转换矩阵：从当前状态能去哪儿、每一步要不要理由与承运渠道，都在此显式建模。 */
    public List<Transition> transitionsFrom() {
      return MATRIX.get(this);
    }

    public boolean canTransitionTo(OutboundOrderStatus to) {
      return to != null && transitionsFrom().stream().anyMatch(t -> t.to() == to);
    }

    public Transition transitionTo(OutboundOrderStatus to) {
      for (var t : transitionsFrom()) if (t.to() == to) return t;
      throw new IllegalArgumentException(
          "不允许的状态流转：" + label() + " → " + (to == null ? "空状态" : to.label()));
    }
  }

  /**
   * 打印文档种类。
   *
   * <p>刻意**只有两种**：出库单与拣货单 —— 都是 8.3.1.1 自己持有的数据。
   * 面单、箱唛、标签打印属于 8.3.4.2（并行车道 V73），在这里放一种就是抢它的表与端点，
   * 所以本枚举里一个都不列。
   */
  public enum OutboundPrintDocument {
    OUTBOUND_ORDER("出库单", "单头 + 全部明细行"),
    PICK_LIST("拣货单", "单头 + 全部明细行，按行号排序，不含价格与买家信息");

    private final String label;
    private final String description;

    OutboundPrintDocument(String label, String description) {
      this.label = label;
      this.description = description;
    }

    public String label() {
      return label;
    }

    public String description() {
      return description;
    }
  }

  // ------------------------------------------------------------ 入参

  /**
   * 建单入参。
   *
   * <p>{@code skuId} + {@code quantity} 成对出现（{@code lines} 的元素）：
   * skuCode / skuName 由服务端读 {@code sku_ref} 冻结，不接受调用方自填 ——
   * 让调用方能自填商品名，就等于允许台账上的货名与商品中心各说各话。
   */
  public record OutboundOrderCreateInput(
      String sourceType,
      @Schema(nullable = true) UUID sourceTransferOrderId,
      @Schema(nullable = true) String sourceExternalRef,
      UUID warehouseId,
      @Schema(nullable = true) String carrierCode,
      @Schema(nullable = true) String note,
      List<OutboundOrderLineInput> lines) {}

  public record OutboundOrderLineInput(UUID skuId, BigDecimal quantity) {}

  /** 状态推进入参：目标状态 + 说明。 */
  public record OutboundTransitionInput(String targetStatus, @Schema(nullable = true) String note) {}

  /** 批量生成拣货任务入参。 */
  public record OutboundPickRequestInput(List<UUID> orderIds, @Schema(nullable = true) String note) {}

  /** 批量取消入参：原因必填（DB 级 CHECK 也要求非空）。 */
  public record OutboundCancelInput(List<UUID> orderIds, String reason) {}

  /** 批量打印入参。 */
  public record OutboundPrintInput(List<UUID> orderIds, String document) {}

  // ------------------------------------------------------------ 出参

  /** 列表行：一行 = 一张出库单。SKU 行数与件数由明细现算（不存列，理由见 V72 第五节）。 */
  public record OutboundOrderSummary(
      UUID id,
      String orderCode,
      String sourceType,
      String sourceTypeLabel,
      @Schema(nullable = true) UUID sourceTransferOrderId,
      @Schema(nullable = true) String sourceTransferCode,
      @Schema(nullable = true) String sourceExternalRef,
      UUID warehouseId,
      String warehouseCode,
      String warehouseName,
      String carrierCode,
      String status,
      String statusLabel,
      /** SKU 行数 = 明细行数 = count(distinct sku)，三者恒相等。 */
      int skuLineCount,
      /** 件数 = 计划出库件数合计（NUMERIC(19,6)，禁浮点）。 */
      BigDecimal totalPieceCount,
      @Schema(nullable = true) OffsetDateTime shippedAt,
      @Schema(nullable = true) OffsetDateTime cancelledAt,
      @Schema(nullable = true) String cancelReason,
      UUID createdByUserId,
      OffsetDateTime createdAt,
      /** 是否已把拣货任务交接给 8.3.2.1（部分唯一索引保证至多一次）。 */
      boolean pickRequested) {}

  public record OutboundOrderPage(List<OutboundOrderSummary> items, long total, int page, int size) {}

  /** 打印出参：真正要印的内容（不是「已提交打印」的空壳）。 */
  public record OutboundPrintDocumentLine(
      int lineNo, String skuCode, String skuName, BigDecimal quantity) {}

  public record OutboundPrintOrder(
      UUID orderId,
      String orderCode,
      String status,
      String statusLabel,
      String warehouseCode,
      String warehouseName,
      String carrierCode,
      @Schema(nullable = true) String sourceExternalRef,
      BigDecimal totalPieceCount,
      List<OutboundPrintDocumentLine> lines) {}

  public record OutboundPrintResult(
      String document,
      String documentLabel,
      OffsetDateTime generatedAt,
      int orderCount,
      List<OutboundPrintOrder> orders) {}

  /** 批量操作的逐单结果：成功与失败都逐条列出，不做「部分成功」这种含糊表达。 */
  public record OutboundBulkFailure(
      UUID orderId,
      // 可空：跨租户 / 不存在的单据查不到单号，而「查不到」本身就是要回报的失败原因。
      @Schema(nullable = true) String orderCode,
      String reason) {}

  public record OutboundPickRequestResult(
      int requestedCount, List<OutboundOrderSummary> orders, List<OutboundBulkFailure> failures) {}

  public record OutboundCancelResult(
      int cancelledCount, List<OutboundOrderSummary> orders, List<OutboundBulkFailure> failures) {}

  /** 拣货任务交接请求的对外形状（8.3.2.1 的契约位）。 */
  public record OutboundPickRequestView(
      UUID id,
      UUID orderId,
      String orderCode,
      int lineCount,
      BigDecimal totalPieceCount,
      String note,
      UUID requestedByUserId,
      OffsetDateTime requestedAt) {}

  // ------------------------------------------------------------ 能力声明

  public record OutboundEnumOption(String name, String label, String description) {}

  public record OutboundTransitionView(
      String from,
      String fromLabel,
      String to,
      String toLabel,
      boolean reasonRequired,
      boolean requiresCarrier,
      String note) {}

  /** 列表筛选维度，前端据此渲染筛选器而不是把维度硬编码在界面上。 */
  public record OutboundFilterView(
      String name, String label, boolean multiValued, List<String> values) {}

  /**
   * 能力声明。
   *
   * <p>三条说明必须如实讲清楚，不能藏在注释里：
   *
   * <ul>
   *   <li>{@code pickingHandoverNote} —— 拣货任务归 8.3.2.1，本模块只做到交接请求。
   *   <li>{@code orderFulfillmentNote} —— 9 订单履约未交付，销售/退货/加工三类来源只存外部单号。
   *   <li>{@code dataScopeNote} —— 用户数据范围的实际生效口径。
   * </ul>
   */
  public record OutboundOrderCapabilities(
      List<OutboundEnumOption> sourceTypes,
      List<OutboundEnumOption> statuses,
      List<OutboundTransitionView> transitions,
      List<OutboundFilterView> filters,
      List<OutboundEnumOption> printDocuments,
      String pickingHandoverNote,
      String orderFulfillmentNote,
      String dataScopeNote,
      String printScopeNote) {}
}