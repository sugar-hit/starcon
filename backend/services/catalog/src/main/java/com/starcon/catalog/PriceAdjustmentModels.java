package com.starcon.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 5.4.1.1 调价单：发起价格变更（选择 SKU 与目标店铺、输入新旧价、说明原因、提交审批），
 * 记录变更前后值；生效后产出「待回写」事实给 5.4.2.1。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：发起价格变更：选择 SKU 与目标店铺、输入新旧价、说明原因、提交审批（若启用）；记录变更前后值。
 *
 * **输入**：调价请求；`expected_version`；审批要求（17 组织的审批规则）。
 *
 * **输出**：调价单（草稿/待审/已批/已执行/已驳回）；生效后触发平台回写。
 *
 * **关系**：上游 = 5.3.1.1、5.3.2.2；下游 = 5.4.2.1、17 审批。**调价是受控动作**：大额或大幅调价走审批，普通调整可配置免审。
 *
 * </blockquote>
 *
 * <h2>🔴 本模块的四条纪律</h2>
 *
 * <ol>
 *   <li><b>🔴 零个测算算法</b>：改价前后的毛利额与毛利率全部来自 5.3.1.1 的引擎
 *       （{@link ProfitCalculatorService#calculate}）。本模块只做「旧价 → 新价 → 前后对比」。</li>
 *   <li><b>🔴 审批不是本模块发明的</b>：要不要审批由 17 组织的审批规则裁定
 *       （identity 的 {@code POST /identity/approval-rules/evaluate}，单据类型 {@code PRICE_CHANGE}），
 *       审批结论也由 identity 那边的人做出。本模块**只**记录结论镜像与实例号，
 *       状态同步是**拉取式**的（{@code GET /identity/approval-records/{id}}）。</li>
 *   <li><b>🔴 取不到当前价就整单拒绝，绝不拿 0 顶</b>：0 会被当成「免费」，
 *       而它其实是一个取不到的事实。跨币种同样**绝不按 1:1 折算**。</li>
 *   <li><b>🔴 null 绝不等于 0</b>：成本不完整时毛利额与毛利率是 null（不可信），
 *       亏损标记也是 null。</li>
 * </ol>
 */
public final class PriceAdjustmentModels {

  private PriceAdjustmentModels() {}

  // ================================================================ 词表

  /**
   * 调价单状态（蓝图的输出句逐字对应：草稿/待审/已批/已执行/已驳回）。
   *
   * <p>🔴 状态之间的合法转换写在 {@link PriceAdjustmentRules#canTransition}，
   * 不在这里 —— 词表只回答「有哪些状态」，转换规则是另一件事。
   */
  public enum Status {
    /** 草稿：已填好新旧价与理由，还没提交。 */
    DRAFT("草稿"),
    /** 待审：已提交，命中了审批规则，等 identity 那边的人批。 */
    PENDING_APPROVAL("待审"),
    /** 已批：免审直通，或审批结论回来了。 */
    APPROVED("已批"),
    /** 已执行：新价已生效，并已产出待回写事实。 */
    EXECUTED("已执行"),
    /** 已驳回：审批被驳回，或本人把这张单撤了。 */
    REJECTED("已驳回");

    private final String label;

    Status(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static Status parse(String raw) {
      if (raw == null) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  /** 当前价的来源事实。 */
  public enum PriceSource {
    /** 5.5.2.1 的价格观测台账（price_anomaly_observation）里该 (SKU, 店铺) 的最新一条观测。 */
    PRICE_OBSERVATION("价格观测台账"),
    /** 5.4.1.2 批量调价明细里记下的新价（比观测更新）。 */
    BATCH_ADJUSTMENT("批量调价新价");

    private final String label;

    PriceSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 台账排序。 */
  public enum Sort {
    /** 最近创建的（默认）。 */
    NEWEST("最新创建"),
    /** 最旧创建的。 */
    OLDEST("最早创建"),
    /** 调价幅度绝对值从大到小。 */
    AMOUNT_DESC("调价金额 从大到小"),
    /** 调价幅度绝对值从小到大。 */
    AMOUNT_ASC("调价金额 从小到大");

    private final String label;

    Sort(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static Sort parse(String raw) {
      if (raw == null) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  // ================================================================ 输入

  /**
   * 创建一张调价单（{@code POST /catalog/price-adjustments}）。
   *
   * <p>🔴 {@code newPrice} 是**人输入的新价**（蓝图的职责句：「输入新旧价」）——
   * 本模块**不**用 5.3.2.1 的模板算新价，模板是「建议」，拍板是人。
   *
   * <p>🔴 {@code oldPrice} 是**可选的**：给了就当「你看到的那个价」，服务端会拿它与
   * **当前真实价**比对，对不上 ⇒ 409（你看到的是过期价），不给就以服务端解析到的当前价为准。
   */
  public record PriceAdjustmentRequest(
      @NotNull(message = "必须指定 SKU") UUID skuId,
      @NotBlank(message = "必须指定平台") String platformCode,
      @NotBlank(message = "必须指定店铺（站点）") String siteCode,
      @NotNull(message = "必须指定售价币种") @Pattern(regexp = "^[A-Za-z]{3}$", message = "币种必须是三位大写字母")
          String currency,
      /** 成本币种。空 = 与售价同币种。 */
      @Schema(nullable = true)
          @Pattern(regexp = "^[A-Za-z]{3}$", message = "币种必须是三位大写字母")
          String costCurrency,
      /** 汇率：1 成本币种 = fx_rate 售价币种。跨币种时必填。 */
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal fxRate,
      /** 新价（人输入）。必须 > 0 —— 把价格改成 0 就是「免费」，那不是调价。 */
      @NotNull(message = "必须输入新价") @DecimalMin(value = "0", inclusive = false) BigDecimal newPrice,
      /** 你看到的旧价（可选）。给了就与服务端解析到的当前价比对。 */
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal oldPrice,
      /** 数量（用于阶梯与运费）。空 = 1。 */
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
      /** 平台费率（售价的小数比例，0.15 = 15%）。🔴 5.2.3.1 未交付 ⇒ 手工假设。 */
      @Schema(nullable = true)
          @DecimalMin(value = "0")
          @DecimalMax(value = "0.999999")
          BigDecimal platformFeeRate,
      /** 平台固定费（每单固定金额）。🔴 5.2.3.1 未交付 ⇒ 手工假设。 */
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal platformFeeFixed,
      /** 物流每单位运费。🔴 5.2.3.2 未交付 ⇒ 手工假设。 */
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal logisticsCostPerUnit,
      /** 月度固定成本（盈亏平衡销量的固定费用基数）。 */
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal monthlyFixedCost,
      /** 成本口径窗口起（含）。空 = 默认最近 90 天。 */
      @Schema(nullable = true) LocalDate costWindowFrom,
      /** 成本口径窗口止（含）。空 = 今天。 */
      @Schema(nullable = true) LocalDate costWindowTo,
      /** 标题（提交审批实例时作为 subjectTitle）。空 = 自动生成。 */
      @Schema(nullable = true) @Size(max = 200) String title,
      /** 调价原因（蓝图的职责句：「说明原因」）。 */
      @NotBlank(message = "必须说明调价原因") @Size(max = 500) String reason,
      /** 备注。 */
      String note) {}

  /**
   * 带乐观锁版本号的操作入参（提交 / 执行）。
   *
   * <p>🔴 蓝图的输入句明写 {@code expected_version}：客户端读到的版本与服务端当前版本
   * 对不上 ⇒ **409 冲突**（别人已经改过这张单，或它已经不在你以为的状态上了）。
   */
  public record PriceAdjustmentExpectedVersionRequest(
      @NotNull(message = "必须带上你读到的版本号") @Min(value = 1) Integer expectedVersion,
      /** 提交时可选的一句话说明。 */
      @Schema(nullable = true) @Size(max = 500) String comment) {}

  /** 驳回（草稿撤单 / 审批结论为驳回时的说明）。 */
  public record PriceAdjustmentRejectRequest(
      @NotNull(message = "必须带上你读到的版本号") @Min(value = 1) Integer expectedVersion,
      @NotBlank(message = "必须写明驳回原因") @Size(max = 500) String reason) {}

  /** 待回写事实的回执（由下游 5.4.2.1 调用）。 */
  public record PriceAdjustmentWritebackAckRequest(@Schema(nullable = true) @Size(max = 500) String note) {}

  // ================================================================ 输出

  /**
   * 当前价解析结果（{@code GET /catalog/price-adjustments/current-price}）。
   *
   * <p>🔴 {@code price} 为 null 时**不是 0**，而是真的「取不到」——
   * {@code reason} 写明为什么取不到，调用方据此拒绝建单而不是拿 0 顶。
   */
  public record PriceAdjustmentCurrentPrice(
      UUID skuId,
      String platformCode,
      String siteCode,
      String currency,
      @Schema(nullable = true) BigDecimal price,
      @Schema(nullable = true) PriceSource source,
      @Schema(nullable = true) OffsetDateTime observedAt,
      String reason) {}

  /** 调价单明细 = 变更前后值（蓝图的输出句：「记录变更前后值」）。 */
  public record PriceAdjustmentOrderItem(
      UUID id,
      UUID orderId,
      UUID skuId,
      String skuCode,
      String skuName,
      String platformCode,
      String siteCode,
      String currency,
      BigDecimal oldPrice,
      BigDecimal newPrice,
      BigDecimal priceDelta,
      @Schema(nullable = true) BigDecimal priceRate,
      PriceSource priceSource,
      String priceSourceLabel,
      OffsetDateTime priceObservedAt,
      @Schema(nullable = true) BigDecimal grossProfitBefore,
      @Schema(nullable = true) BigDecimal grossMarginBefore,
      @Schema(nullable = true) BigDecimal grossProfitAfter,
      @Schema(nullable = true) BigDecimal grossMarginAfter,
      boolean decisionReady,
      @Schema(nullable = true) Boolean lossBefore,
      @Schema(nullable = true) Boolean lossAfter) {}

  /**
   * 一张调价单（台账与详情共用同一个形状）。
   *
   * <p>🔴 {@code approvalSyncError} 非空表示「上一次同步审批状态失败了」——
   * 单子停在待审，**没有**被当成已批。把它渲染出来，运营才知道该等还是该处理。
   */
  public record PriceAdjustmentDetail(
      UUID id,
      Status status,
      String statusLabel,
      /** 🔴 当前版本（乐观锁）。下一次操作要把它作为 expectedVersion 带回来。 */
      int version,
      UUID skuId,
      String skuCode,
      String skuName,
      String platformCode,
      String siteCode,
      String currency,
      String title,
      String reason,
      String note,
      BigDecimal quantity,
      String costCurrency,
      @Schema(nullable = true) BigDecimal fxRate,
      @Schema(nullable = true) BigDecimal platformFeeRate,
      @Schema(nullable = true) BigDecimal platformFeeFixed,
      @Schema(nullable = true) BigDecimal logisticsCostPerUnit,
      @Schema(nullable = true) BigDecimal monthlyFixedCost,
      @Schema(nullable = true) LocalDate costWindowFrom,
      @Schema(nullable = true) LocalDate costWindowTo,
      @Schema(nullable = true) BigDecimal oldPrice,
      @Schema(nullable = true) BigDecimal newPrice,
      @Schema(nullable = true) BigDecimal priceDelta,
      @Schema(nullable = true) BigDecimal priceRate,
      @Schema(nullable = true) PriceSource priceSource,
      @Schema(nullable = true) String priceSourceLabel,
      @Schema(nullable = true) OffsetDateTime priceObservedAt,
      @Schema(nullable = true) BigDecimal grossProfitBefore,
      @Schema(nullable = true) BigDecimal grossMarginBefore,
      @Schema(nullable = true) BigDecimal grossProfitAfter,
      @Schema(nullable = true) BigDecimal grossMarginAfter,
      @Schema(nullable = true) Boolean decisionReady,
      @Schema(nullable = true) Boolean lossBefore,
      @Schema(nullable = true) Boolean lossAfter,
      boolean approvalRequired,
      @Schema(nullable = true) String approvalDecision,
      @Schema(nullable = true) String approvalSource,
      @Schema(nullable = true) UUID approvalRuleId,
      @Schema(nullable = true) String approvalRuleName,
      @Schema(nullable = true) UUID approvalFlowId,
      @Schema(nullable = true) String approvalFlowName,
      String approvalReason,
      @Schema(nullable = true) UUID approvalInstanceId,
      @Schema(nullable = true) String approvalInstanceNo,
      @Schema(nullable = true) OffsetDateTime approvalSubmittedAt,
      @Schema(nullable = true) OffsetDateTime approvalDecidedAt,
      @Schema(nullable = true) UUID approvalDecidedBy,
      String approvalSyncError,
      @Schema(nullable = true) OffsetDateTime submittedAt,
      @Schema(nullable = true) OffsetDateTime executedAt,
      @Schema(nullable = true) OffsetDateTime rejectedAt,
      @Schema(nullable = true) UUID rejectedBy,
      String rejectReason,
      @Schema(nullable = true) UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      List<PriceAdjustmentOrderItem> items) {}

  /** 调价单列表（真分页）。 */
  public record PriceAdjustmentPage(
      List<PriceAdjustmentDetail> items, int page, int size, long total) {}

  /** 待回写平台的一条事实（给 5.4.2.1）。 */
  public record PriceAdjustmentWriteback(
      UUID id,
      UUID orderId,
      UUID orderItemId,
      UUID skuId,
      String skuCode,
      String platformCode,
      String siteCode,
      String currency,
      BigDecimal expectedPrice,
      @Schema(nullable = true) BigDecimal previousPrice,
      String status,
      String idempotencyKey,
      @Schema(nullable = true) OffsetDateTime acknowledgedAt,
      String ackNote,
      OffsetDateTime createdAt) {}

  /** 待回写事实列表（真分页 + 真筛选）。 */
  public record PriceAdjustmentWritebackPage(
      List<PriceAdjustmentWriteback> items, int page, int size, long total) {}

  /**
   * 能力声明。
   *
   * <p>页面顶部固定显示它 —— 蓝图那句「**调价是受控动作**」意味着运营会拿这页的
   * 口径去决定「这张单要不要提、提了会不会被卡」，所以口径必须写在数字旁边。
   */
  public record PriceAdjustmentCapabilities(
      String listingDefinitionNote,
      String priceSourceNote,
      String approvalNote,
      String approvalSyncNote,
      String stateMachineNote,
      String versionNote,
      String profitNote,
      String roundingNote,
      String upstreamNote,
      String downstreamNote,
      String permissionNote) {}

  /** 台账筛选（真筛选在数据库层）。 */
  public record OrderQuery(
      /** 状态筛选：DRAFT / PENDING_APPROVAL / APPROVED / EXECUTED / REJECTED / 空 = 全部。 */
      @Schema(nullable = true) String status,
      /** SKU 筛选。 */
      @Schema(nullable = true) UUID skuId,
      /** 平台码筛选。 */
      @Schema(nullable = true) String platformCode,
      /** 站点码筛选。 */
      @Schema(nullable = true) String siteCode,
      /** 关键字（SKU 编码 / 名称 / 标题 / 原因模糊匹配）。 */
      @Schema(nullable = true) String keyword,
      /** 排序。 */
      @Schema(nullable = true) String sort,
      int page,
      int size) {}
}
