package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 6.4.1.1 加工单的传输模型。
 *
 * <p>蓝图原话：「发起委外/自有工厂加工：选择成品 SKU 与 BOM 版本、指定组件领用量与加工厂、
 * 约定完工数量与交期，跟踪加工进度与入库。」输出是
 * 「加工单（组件领用记录 + 完工数量）；入库需求通知 8」。
 *
 * <p><b>四条建模纪律</b>：
 *
 * <ul>
 *   <li><b>BOM 口径不重新定义</b>：{@link ProcessingComponentLine} 的
 *       {@code unitQuantity} / {@code scrapRate} / {@code requiredQuantity}
 *       逐字对应 catalog V25 的规则 R6（损耗率是百分数，5 = 5%）与
 *       规则 R7（需求量 = 单位用量 × 产量 × (1 + 损耗率/100)，6 位小数 HALF_UP）。
 *       本模块**不**另立一套换算，也不把需求量取整成采购量 ——
 *       取整是 3.5.2.2 与 06 采购建议的职责。
 *   <li><b>完工数量与实收数量分开</b>：{@code completedQuantity} 是工厂**报**的，
 *       {@code receivedQuantity} 是仓库**收**的。合成一个数就等于允许
 *       「报完工即视同已入库」，那是仓库账实不符的起点。
 *   <li><b>状态词表只有一处</b>：{@link ProcessingStatus} 一字不改地对应 DB 的 CHECK。
 *   <li><b>数量全程 BigDecimal</b>，禁浮点；数量列一律 NUMERIC(19,6)。
 * </ul>
 */
public final class ProcessingModels {

  private ProcessingModels() {}

  // ------------------------------------------------------------ 枚举

  /**
   * 加工单生命周期（DB 词表，一字不改）。
   *
   * <p>{@code CANCELLED} 之外的每一支都要有时间戳自洽（DB CHECK 已钉）：
   * 已下达要有下达时刻，已完工要有完工时刻，已入库要有入库时刻。
   */
  public enum ProcessingStatus {
    DRAFT("草稿"),
    RELEASED("已下达"),
    IN_PROCESS("加工中"),
    COMPLETED("已完工"),
    RECEIVED("已入库"),
    CANCELLED("已作废");

    private final String label;

    ProcessingStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /**
     * 合法迁移。作废与已入库是终态（D97 触发器 6.6 同样钉死，不只靠这里）。
     *
     * <p>刻意**没有** RELEASED → COMPLETED 之外的捷径：领料、加工、完工、入库
     * 是四件不同的事，跳过其中任何一件，事后都答不出「这批料是什么时候以什么状态离开仓库的」。
     */
    public boolean canMoveTo(ProcessingStatus next) {
      return switch (this) {
        case DRAFT -> next == RELEASED || next == CANCELLED;
        case RELEASED -> next == IN_PROCESS || next == CANCELLED;
        case IN_PROCESS -> next == COMPLETED || next == CANCELLED;
        case COMPLETED -> next == RECEIVED;
        case RECEIVED, CANCELLED -> false;
      };
    }
  }

  /** 加工厂类别（DB 词表）。只作标注，不改变任何库存规则。 */
  public enum FactoryKind {
    OUTSOURCED("委外工厂"),
    IN_HOUSE("自有工厂");

    private final String label;

    FactoryKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 加工厂状态（DB 词表）。停用后不许再下达新单，历史单照常可读。 */
  public enum FactoryStatus {
    ACTIVE("启用"),
    SUSPENDED("停用");

    private final String label;

    FactoryStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 状态与进度事件类型（DB 词表）。 */
  public enum ProcessingEventType {
    CREATED("创建"),
    RELEASED("下达"),
    PROGRESS("进度登记"),
    COMPLETED("完工"),
    RECEIVED("入库"),
    CANCELLED("作废");

    private final String label;

    ProcessingEventType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 入库需求通知状态（DB 词表）。 */
  public enum InboundRequestStatus {
    PENDING("待入库"),
    PARTIAL("部分入库"),
    RECEIVED("已入库"),
    CANCELLED("已作废");

    private final String label;

    InboundRequestStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 加工厂

  public record FactorySaved(
      UUID id,
      String code,
      String name,
      String factoryKind,
      String status,
      String note,
      long version,
      String createdAt,
      String updatedAt) {}

  /** 新建 / 更新加工厂。{@code version=0} 表示新建，否则为乐观锁版本号。 */
  public record FactoryInput(
      @Schema(nullable = true) @Size(max = 64) String code,
      @NotBlank @Size(max = 200) String name,
      @NotBlank String factoryKind,
      @Schema(nullable = true, description = "启用 / 停用；不传表示保持当前状态")
          String status,
      @Schema(nullable = true) @Size(max = 2000) String note,
      @PositiveOrZero long version) {}

  public record FactoryPage(
      List<FactorySaved> items,
      int page,
      int size,
      long total,
      long activeCount,
      long suspendedCount,
      long inHouseCount) {}

  public record FactoryQuery(
      String factoryKind, String status, String keyword, int page, int size) {}

  // ------------------------------------------------------------ 加工单

  /**
   * 组件领用记录（蓝图输出的硬输出之一）。
   *
   * <p>{@code requiredQuantity} 与 {@code issuedQuantity} 的差额就是「还欠多少料」——
   * 这才是进度跟踪的真身，比一个 status 字段能回答的问题多得多。
   */
  public record ProcessingComponentLine(
      UUID id,
      UUID skuId,
      String skuCode,
      @Schema(description = "单位用量：每 1 个成品需要这个组件多少（catalog V25 R7 因子）")
          @Digits(integer = 13, fraction = 6)
          BigDecimal unitQuantity,
      @Schema(
              description = "损耗率，**百分数不是比率**：5 表示损耗 5%（catalog V25 规则 R6）",
              minimum = "0",
              maximum = "100")
          @Digits(integer = 3, fraction = 4)
          BigDecimal scrapRate,
      @Schema(
              description = "需求量 = 单位用量 × 产量 × (1 + 损耗率/100)，6 位小数 HALF_UP，不取整（V25 R7）")
          @Digits(integer = 13, fraction = 6)
          BigDecimal requiredQuantity,
      @Schema(description = "已领数量（仓库实际发出去了多少）")
          @Digits(integer = 13, fraction = 6)
          BigDecimal issuedQuantity,
      @Schema(description = "还欠多少料 = requiredQuantity − issuedQuantity")
          @Digits(integer = 13, fraction = 6)
          BigDecimal outstandingQuantity,
      String role,
      String note) {}

  /** 入库需求通知（蓝图输出的硬输出之二：入库需求通知 8）。 */
  public record InboundRequestView(
      UUID id,
      UUID orderId,
      String orderNo,
      UUID skuId,
      String skuCode,
      @Digits(integer = 13, fraction = 6) BigDecimal requestedQuantity,
      @Digits(integer = 13, fraction = 6) BigDecimal receivedQuantity,
      @Digits(integer = 13, fraction = 6) BigDecimal outstandingQuantity,
      String inventoryTransactionRef,
      String status,
      String requestedAt,
      @Schema(nullable = true) String receivedAt,
      String note) {}

  /** 状态与进度事件流的一行（append-only 留痕）。 */
  public record ProcessingEventView(
      UUID id,
      String eventType,
      String eventTypeLabel,
      String fromStatus,
      String toStatus,
      @Digits(integer = 13, fraction = 6) BigDecimal quantity,
      String note,
      UUID actorId,
      String occurredAt) {}

  public record ProcessingOrderSaved(
      UUID id,
      String orderNo,
      UUID factoryId,
      String factoryCode,
      String factoryName,
      UUID outputSkuId,
      String outputSkuCode,
      UUID bomId,
      UUID bomVersionId,
      String bomVersionNo,
      @Digits(integer = 13, fraction = 6) BigDecimal plannedOutputQuantity,
      @Digits(integer = 13, fraction = 6) BigDecimal completedQuantity,
      String dueOn,
      String status,
      String statusLabel,
      long componentCount,
      long issuedComponentCount,
      @Digits(integer = 13, fraction = 6) BigDecimal totalRequiredQuantity,
      @Digits(integer = 13, fraction = 6) BigDecimal totalIssuedQuantity,
      String note,
      long version,
      @Schema(nullable = true) String createdAt,
      @Schema(nullable = true) String releasedAt,
      @Schema(nullable = true) String completedAt,
      @Schema(nullable = true) String receivedAt,
      @Schema(nullable = true) String cancelReason) {}

  /** 加工单详情：单头 + 组件领用记录 + 入库需求通知 + 事件流。 */
  public record ProcessingOrderDetail(
      ProcessingOrderSaved order,
      List<ProcessingComponentLine> components,
      @Schema(nullable = true) InboundRequestView inboundRequest,
      List<ProcessingEventView> events,
      List<ProcessingStatus> nextStatuses,
      List<String> boundaryNotes) {}

  public record ProcessingOrderPage(
      List<ProcessingOrderSaved> items,
      int page,
      int size,
      long total,
      long draftCount,
      long releasedCount,
      long inProcessCount,
      long completedCount,
      long receivedCount,
      long cancelledCount,
      long overdueCount,
      @Digits(integer = 13, fraction = 6) BigDecimal totalPlannedQuantity,
      @Digits(integer = 13, fraction = 6) BigDecimal totalCompletedQuantity) {}

  public record ProcessingOrderQuery(
      UUID factoryId,
      UUID outputSkuId,
      String status,
      String keyword,
      Boolean overdueOnly,
      int page,
      int size) {}

  /**
   * 新建加工单。
   *
   * <p>{@code components} 刻意**不**由调用方逐行提交数量：组件与需求量必须由
   * 本服务按 catalog 的 R7 从 BOM 版本算出来（{@code bomVersionId} + 产量），
   * 否则「领用量要能从 BOM 算出来」这条硬要求就退化成了「调用方自称」，
   * 而加工单恰恰是**要照着领料去仓库**的凭据。
   */
  public record ProcessingOrderInput(
      @NotBlank @Size(max = 64) String orderNo,
      @NotNull UUID factoryId,
      @NotNull UUID bomVersionId,
      @Schema(description = "约定完工数量：与 BOM 版本一起决定组件需求量（R7 的产量因子）")
          @NotNull
          @Positive
          @Digits(integer = 13, fraction = 6)
          BigDecimal plannedOutputQuantity,
      @NotNull LocalDate dueOn,
      @Schema(nullable = true) @Size(max = 2000) String note) {}

  /**
   * 组件领用登记：把「这次发了多少料」记到某一条组件领用记录上。
   *
   * <p>本模块**不**改库存（那是 warehouse 在它自己的本地事务里做的事），
   * 这里记的是**领用进度**：仓库那边发料成功后回写已领数量，
   * 加工单因此能算出「还欠多少料」。
   */
  public record IssueInput(
      @NotNull UUID componentId,
      @NotNull
          @Positive
          @Digits(integer = 13, fraction = 6)
          BigDecimal issuedQuantity,
      @Schema(nullable = true) @Size(max = 2000) String note,
      @PositiveOrZero long version) {}

  /** 状态推进请求。 */
  public record ProcessingStatusInput(
      @NotBlank @Size(max = 16) String status,
      @Schema(
              description = "本次推进涉及的成品数量（完工报数时必填且 > 0）",
              nullable = true)
          @Positive
          @Digits(integer = 13, fraction = 6)
          BigDecimal quantity,
      @Schema(nullable = true) @Size(max = 300) String reason,
      @Schema(nullable = true) @Size(max = 2000) String note,
      @PositiveOrZero long version) {}

  /** 进度登记（不改生命周期，只记「推进到哪、报了多少」）。 */
  public record ProcessingProgressInput(
      @Schema(nullable = true)
          @Positive
          @Digits(integer = 13, fraction = 6)
          BigDecimal quantity,
      @NotBlank @Size(max = 2000) String note,
      @PositiveOrZero long version) {}

  // ------------------------------------------------------------ 能力声明

  /**
   * 本模块的能力与上下游可用性声明。
   *
   * @param upstreamNote 上游依赖实查结论：BOM 属 catalog（V25 已交付，走授权 API
   *     读，不跨库）；库存属 warehouse（组件出库与成品入库在 warehouse 本地事务完成）
   * @param downstreamGaps 未交付的下游模块与其对本模块的实际影响
   */
  public record ProcessingCapabilities(
      List<ProcessingStatus> statuses,
      List<String> statusTransitions,
      List<FactoryKind> factoryKinds,
      List<InboundRequestStatus> inboundRequestStatuses,
      boolean bomVersionBindingAvailable,
      boolean warehouseStockMovementOwnedByWarehouse,
      String upstreamNote,
      List<DownstreamGap> downstreamGaps,
      String note) {

    /**
     * 一个未交付下游。
     *
     * @param available 该下游是否已交付
     * @param ownerModule 归属模块（页面据此写清「为什么这个按钮不能按」）
     */
    public record DownstreamGap(
        String moduleId,
        String moduleName,
        String service,
        boolean available,
        String ownerModule,
        String effect) {}
  }

  /**
   * 组件需求量试算输入（读 catalog 的 R7，不落库）。
   *
   * <p>刻意**不返回**「建议采购量」：蓝图原话「展开结果是建议不是事实」，
   * 取整与补安全库存是 3.5.2.2 与 06 的决定。
   */
  public record RequirementPreviewInput(
      @NotNull UUID bomVersionId,
      @NotNull
          @Positive
          @Digits(integer = 13, fraction = 6)
          BigDecimal outputQuantity) {}

  /** 组件需求量试算结果（与落库的组件领用记录同口径）。 */
  public record RequirementPreview(
      UUID bomId,
      UUID bomVersionId,
      String bomVersionNo,
      UUID outputSkuId,
      String outputSkuCode,
      @Digits(integer = 13, fraction = 6) BigDecimal outputQuantity,
      List<ProcessingComponentLine> components,
      boolean singleLevel,
      String note) {}
}
