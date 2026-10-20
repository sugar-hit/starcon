package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 10.4.2.2 备货预报的契约模型。
 *
 * <p>🔴 蓝图 §10.4.2.2 加粗句：「**平台仓备货有严格规格**（箱型、贴标、托盘、混装限制），
 * 规格不符会被拒收造成额外成本与延误。」
 *
 * <p>所以「规格」在本模块里是**一等公民**而不是注释：
 *
 * <ul>
 *   <li>{@link ForecastSpecRow} 是**可维护的结构化规格**（箱型/贴标/托盘/混装四组限制各有自己的列）；
 *   <li>{@link SpecRule} 是**逐条违规规则**，校验不过时逐条点名违反了哪一条；
 *   <li>{@link SpecCheckResult} 是校验结果，预报单在通过之前不能提交。
 * </ul>
 */
public final class StockForecastModels {
  private StockForecastModels() {}

  // ================================================================
  // 状态机（显式转换规则）
  // ================================================================

  /**
   * 预报单状态。
   *
   * <p>用 sealed interface + enum 表达（AGENTS.md：状态机用 Java enum / sealed interface
   * 配合显式转换规则表达）。转换规则同时在 DB 层被触发器钉死（迁移 V243），
   * 所以「服务层忘了判」与「有人绕过服务层写 SQL」两种情况都跳不过去。
   */
  public sealed interface ForecastState permits ForecastStatus {
    /** 合法后继状态。空集合 = 终态。 */
    Set<ForecastStatus> allowedNext();

    default boolean canTransitionTo(ForecastStatus next) {
      return allowedNext().contains(next);
    }
  }

  /** 预报单状态枚举。 */
  public enum ForecastStatus implements ForecastState {
    /** 草稿：可改明细，尚未过规格校验。 */
    DRAFT,
    /** 已过规格校验：规格版本已冻结在预报单上。 */
    VALIDATED,
    /** 已提交：平台货件创建任务已生成。 */
    SUBMITTED,
    /** 货件已创建：平台货件编号已回填。 */
    SHIPMENT_CREATED,
    /** 仓端已接收。 */
    ACCEPTED,
    /** 仓端已拒收（真实业务结果，规格不符被拒收要走这条）。 */
    REJECTED,
    /** 已作废。 */
    CANCELLED;

    @Override
    public Set<ForecastStatus> allowedNext() {
      return switch (this) {
        case DRAFT -> Set.of(VALIDATED, CANCELLED);
        case VALIDATED -> Set.of(SUBMITTED, CANCELLED);
        case SUBMITTED -> Set.of(SHIPMENT_CREATED, CANCELLED);
        case SHIPMENT_CREATED -> Set.of(ACCEPTED, REJECTED);
        case ACCEPTED, REJECTED, CANCELLED -> Set.of();
      };
    }

    public boolean isTerminal() {
      return allowedNext().isEmpty();
    }
  }

  /** 平台货件创建任务状态。 */
  public enum ShipmentTaskStatus {
    /** 已生成，等待推进。 */
    PENDING,
    /** 已提交给服务商侧。 */
    SUBMITTED,
    /** 服务商侧已受理，平台货件编号已回填。 */
    SUCCEEDED,
    /** 失败，可重试（attempts 累加）。 */
    FAILED,
    /** 已作废。 */
    CANCELLED;

    public boolean canTransitionTo(ShipmentTaskStatus next) {
      return switch (this) {
        case PENDING -> next == SUBMITTED || next == FAILED || next == CANCELLED;
        case SUBMITTED -> next == SUCCEEDED || next == FAILED;
        case FAILED -> next == PENDING || next == CANCELLED;
        case SUCCEEDED, CANCELLED -> false;
      };
    }
  }

  /** 仓端回执类型。 */
  public enum ReceiptType {
    /** 仓端接收。 */
    ACCEPTED,
    /** 仓端拒收。 */
    REJECTED
  }

  /** 预报来源（蓝图输入句：FBA 发货计划或海外仓备货需求）。 */
  public enum ForecastSource {
    /** 6.2.1.2 补货建议驱动。 */
    REPLENISHMENT_SUGGESTION,
    /** FBA 发货计划。 */
    FBA_SHIPMENT_PLAN,
    /** 人工登记。 */
    MANUAL
  }

  // ================================================================
  // 规格违规规则
  // ================================================================

  /**
   * 规格违规规则。
   *
   * <p>每条规则对应蓝图加粗句里的一个具体限制。违规时**点名是哪一条**，
   * 而不是丢一句「规格不符」—— 运营要知道改什么才不会又被拒收。
   */
  public enum SpecRule {
    /** 目的仓没有生效规格。 */
    SPEC_MISSING("目的仓未配置生效的备货规格"),
    /** 箱型不在允许集合内。 */
    BOX_TYPE_NOT_ALLOWED("箱型不在目的仓允许范围内"),
    /** 没给箱数，但规格有限制要靠箱数换算。 */
    CARTON_COUNT_MISSING("缺少箱数，无法核对单箱限制"),
    /** 单箱件数超限。 */
    MAX_UNITS_PER_CARTON_EXCEEDED("单箱件数超出上限"),
    /** 规格有重量上限，但这一行没给单件重量。 */
    UNIT_WEIGHT_MISSING("缺少单件重量，无法核对重量上限"),
    /** 单箱重量超限。 */
    MAX_WEIGHT_PER_CARTON_EXCEEDED("单箱重量超出上限"),
    /** 规格要求贴标但没给贴标格式。 */
    LABEL_FORMAT_MISSING("缺少贴标格式"),
    /** 贴标格式不在允许集合内。 */
    LABEL_FORMAT_NOT_ALLOWED("贴标格式不在允许范围内"),
    /** 规格要求箱标但没给箱唛编号。 */
    CARTON_LABEL_MISSING("缺少箱唛编号"),
    /** 规格要求托盘但没给托盘数。 */
    PALLET_REQUIRED_MISSING("规格要求托盘装载但未给出托盘数"),
    /** 单托盘箱数超限。 */
    MAX_CARTONS_PER_PALLET_EXCEEDED("单托盘箱数超出上限"),
    /** 托盘重量超限。 */
    PALLET_WEIGHT_EXCEEDED("托盘重量超出上限"),
    /** 同一箱型里出现多个 SKU，而规格禁止混装。 */
    MIXED_LOADING_FORBIDDEN("该箱型禁止混装"),
    /** 同一箱型下的 SKU 数超出单箱上限。 */
    MAX_SKUS_PER_CARTON_EXCEEDED("同一箱型下 SKU 数超出单箱上限"),
    /** 期望到货时间早于规格要求的最短提前期。 */
    EXPECTED_ARRIVAL_TOO_SOON("期望到货时间早于目的仓要求的备货提前期"),
    /** 同一预报单内 SKU 重复。 */
    DUPLICATE_SKU("同一预报单内 SKU 重复"),
    /** 数量非法。 */
    QUANTITY_NOT_POSITIVE("数量必须大于 0"),
    /** 服务端异常。 */
    UNKNOWN("校验过程异常");

    private final String label;

    SpecRule(String label) {
      this.label = label;
    }

    /** 这条规则的一句话说明（界面上直接显示，不用前端再翻字典）。 */
    public String label() {
      return label;
    }
  }

  /** 一条规格违规。 */
  public record SpecViolation(
      String rule,
      String ruleLabel,
      @Schema(nullable = true) String skuCode,
      String message,
      @Schema(nullable = true) String expected,
      @Schema(nullable = true) String actual) {}

  /** 规格校验结果。 */
  public record SpecCheckResult(
      boolean passed,
      List<SpecViolation> violations,
      @Schema(nullable = true) UUID specId,
      @Schema(nullable = true) Integer specVersion,
      OffsetDateTime checkedAt,
      String note) {}

  // ================================================================
  // 规格
  // ================================================================

  /** 目的仓的备货规格（版本化的一行）。 */
  public record ForecastSpecRow(
      UUID id,
      UUID accountId,
      String accountName,
      int versionNo,
      String status,
      List<String> allowedBoxTypes,
      @Schema(nullable = true) Integer maxUnitsPerCarton,
      @Schema(nullable = true) BigDecimal maxWeightPerCartonKg,
      @Schema(nullable = true) Integer maxSkusPerCarton,
      boolean requiresFnskuLabel,
      boolean requiresCartonLabel,
      List<String> allowedLabelFormats,
      boolean palletRequired,
      @Schema(nullable = true) Integer maxCartonsPerPallet,
      @Schema(nullable = true) BigDecimal maxPalletWeightKg,
      boolean mixedLoadingAllowed,
      int minArrivalLeadDays,
      String note,
      OffsetDateTime createdAt,
      @Schema(nullable = true) OffsetDateTime retiredAt) {}

  /** 新建/换版备货规格。 */
  public record CreateForecastSpecRequest(
      @NotNull UUID accountId,
      List<String> allowedBoxTypes,
      @Schema(nullable = true) Integer maxUnitsPerCarton,
      @Schema(nullable = true) BigDecimal maxWeightPerCartonKg,
      @Schema(nullable = true) Integer maxSkusPerCarton,
      Boolean requiresFnskuLabel,
      Boolean requiresCartonLabel,
      List<String> allowedLabelFormats,
      Boolean palletRequired,
      @Schema(nullable = true) Integer maxCartonsPerPallet,
      @Schema(nullable = true) BigDecimal maxPalletWeightKg,
      Boolean mixedLoadingAllowed,
      @Schema(nullable = true) Integer minArrivalLeadDays,
      String note) {}

  // ================================================================
  // 预报单
  // ================================================================

  /** 预报单列表行。 */
  public record ForecastRow(
      UUID id,
      String forecastNo,
      UUID accountId,
      String accountName,
      String providerCode,
      String source,
      String status,
      OffsetDateTime expectedArrivalAt,
      BigDecimal totalQuantity,
      int lineCount,
      String platformShipmentId,
      @Schema(nullable = true) OffsetDateTime submittedAt,
      @Schema(nullable = true) OffsetDateTime closedAt,
      String lastError,
      String note,
      OffsetDateTime createdAt) {}

  /** 预报单列表页（真分页 + 真筛选 + 真排序）。 */
  public record ForecastPage(
      List<ForecastRow> rows,
      int page,
      int size,
      long total,
      int pageCount,
      ForecastSummary summary) {}

  /** 预报单状态汇总（**全量**，不受分页与筛选影响）。 */
  public record ForecastSummary(
      long total,
      long draft,
      long validated,
      long submitted,
      long shipmentCreated,
      long accepted,
      long rejected,
      long cancelled,
      BigDecimal totalQuantity) {}

  /** 预报明细行。 */
  public record ForecastLineRow(
      UUID id,
      String skuCode,
      BigDecimal quantity,
      String unit,
      String boxType,
      @Schema(nullable = true) Integer cartonCount,
      @Schema(nullable = true) Integer palletCount,
      String labelFormat,
      String cartonLabelRef,
      @Schema(nullable = true) BigDecimal unitWeightKg,
      String note,
      int position) {}

  /** 预报明细行（输入）。 */
  public record ForecastLineInput(
      @NotBlank String skuCode,
      @NotNull @DecimalMin(value = "0.000001") BigDecimal quantity,
      String unit,
      String boxType,
      @Schema(nullable = true) @Positive Integer cartonCount,
      @Schema(nullable = true) @Positive Integer palletCount,
      String labelFormat,
      String cartonLabelRef,
      @Schema(nullable = true) BigDecimal unitWeightKg,
      String note) {}

  /** 建预报单（SKU 与数量 + 目的仓 + 期望到货时间）。 */
  public record CreateForecastRequest(
      @NotNull UUID accountId,
      @Schema(nullable = true) String source,
      @NotNull OffsetDateTime expectedArrivalAt,
      String note,
      @NotEmpty @Valid List<ForecastLineInput> lines) {}

  /** 替换预报明细（只在草稿态允许；非草稿态返回 409）。 */
  public record ReplaceForecastLinesRequest(
      @NotEmpty @Valid List<ForecastLineInput> lines) {}

  /** 预报单详情。 */
  public record ForecastDetail(
      ForecastRow forecast,
      List<ForecastLineRow> lines,
      @Schema(nullable = true) ForecastSpecRow spec,
      @Schema(nullable = true) ShipmentTaskRow shipmentTask,
      List<ForecastReceiptRow> receipts,
      SpecCheckResult specCheck) {}

  // ================================================================
  // 货件任务与报送
  // ================================================================

  /** 平台货件创建任务。 */
  public record ShipmentTaskRow(
      UUID id,
      UUID forecastId,
      String taskNo,
      String status,
      int attempts,
      String platformShipmentId,
      String lastError,
      @Schema(nullable = true) OffsetDateTime acknowledgedAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 登记平台货件报送行（货件任务的真实输入）。 */
  public record CreateShipmentReportRequest(
      @NotBlank String externalShipmentRef,
      @Schema(nullable = true) String source,
      @NotNull OffsetDateTime reportedAt) {}

  /** 平台货件报送行。 */
  public record ShipmentReportRow(
      UUID id,
      String externalShipmentRef,
      String source,
      OffsetDateTime reportedAt,
      OffsetDateTime createdAt) {}

  // ================================================================
  // 仓端回执
  // ================================================================

  /** 登记仓端回执（接收 / 拒收）。 */
  public record CreateReceiptRequest(
      @NotBlank String receiptType,
      String receiptRef,
      String detail,
      String rejectedReason,
      @NotNull OffsetDateTime receivedAt,
      @Schema(nullable = true) String source) {}

  /** 仓端回执行。 */
  public record ForecastReceiptRow(
      UUID id,
      String receiptType,
      String receiptRef,
      String detail,
      String rejectedReason,
      OffsetDateTime receivedAt,
      String source,
      OffsetDateTime createdAt) {}

  // ================================================================
  // 备货可用性（库存 + 在途，只读 Port）
  // ================================================================

  /**
   * 一个 SKU 的备货可用性。
   *
   * @param sellable 自营可售量（{@code null} = 上游没有这一行，**不是 0**）
   * @param sellableKnown 上游是否给出了可售量行
   * @param inTransitQuantity 头程在途量（{@code null} = 上游没有在途腿，**不是 0**）
   * @param inTransitKnown 上游是否给出了在途腿
   * @param coverable 可售量是否覆盖本次预报量（{@code null} = 未知，口径缺失时不猜）
   */
  public record ForecastAvailabilityLine(
      String skuCode,
      BigDecimal requestedQuantity,
      String unit,
      @Schema(nullable = true) BigDecimal sellable,
      boolean sellableKnown,
      @Schema(nullable = true) BigDecimal inTransitQuantity,
      boolean inTransitKnown,
      @Schema(nullable = true) Boolean coverable,
      String note) {}

  /** 备货可用性页。 */
  public record ForecastAvailabilityPage(
      List<ForecastAvailabilityLine> rows, int page, int size, long total, String note) {}

  // ================================================================
  // 能力声明
  // ================================================================

  /** 一条能力维度。 */
  public record ForecastCapabilityDimension(
      String key, String label, boolean available, String ownerModule, String impact) {}

  /** 备货预报的能力声明。 */
  public record ForecastCapabilities(
      List<ForecastCapabilityDimension> dimensions,
      List<String> boxTypes,
      List<String> labelFormats,
      List<String> sources,
      String note) {}
}
