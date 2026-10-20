package com.starcon.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 5.2.1.1 成本构成：单位成本构成（分项 + 合计 + 生效时间）与成本变动历史。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：展示并维护 SKU 的单位成本构成：采购成本、头程分摊、包装成本、关税、其他直接成本。采购成本来自 06 采购的实际单价（按成本法加权），其他项来自参数或手工调整。
 *
 * **输入**：成本法选择（移动加权/先进先出/批次）；12 财务的成本核算结果；手工调整项及原因。
 *
 * **输出**：单位成本明细（分项 + 合计 + 生效时间）；成本变动历史。
 *
 * **关系**：上游 = 06 采购、12 财务、10 头程费用；下游 = 5.3.1.1 测算、13 利润。**成本口径必须在页面上明示用了哪种成本法**，否则同一 SKU 今天显示毛利 30% 明天 25% 无法解释。
 *
 * </blockquote>
 *
 * <p><b>🔴 本模块最容易被误读的地方：五个分项分属三个服务，其中两个上游没交付。</b>
 *
 * <table border="1">
 *
 * <caption>分项来源</caption>
 *
 * <tr><th>分项</th><th>来源</th><th>本仓真实状态</th></tr>
 *
 * <tr><td>采购成本</td><td>06 采购的实际单价</td><td>🔴 06 采购单（6.3）未开发 ⇒ 改用同库 {@code vendor_delivery_record}（V137）的<b>实际到货金额</b>按成本法加权（来源码 {@code ACTUAL_RECEIPT}）。<b>不是</b> {@code supplier_quote} 的报价 —— 报价只作为 {@code QuoteReference} 只读参考单列，<b>不计入合计</b></td></tr>
 *
 * <tr><td>头程分摊</td><td>10 头程费用</td><td>✅ warehouse {@code first_mile_shipment} / {@code _line}（经 {@link CostBasisPort} 读）</td></tr>
 *
 * <tr><td>包装成本</td><td>参数或手工调整</td><td>⚠️ 全仓无包装成本事实（包材目录无成本列）⇒ <b>参数</b>，可空 = 未维护</td></tr>
 *
 * <tr><td>关税</td><td>12 财务的成本核算结果</td><td>🔴 12 财务（finance 服务）不存在 ⇒ 改用 warehouse {@code duty_tax_estimate_line}（10.5.1.3 已交付）。<b>这是本服务口径，不是 12 财务的核算结果</b></td></tr>
 *
 * <tr><td>其他直接成本</td><td>手工调整</td><td>✅ 本服务自持（{@code sku_cost_adjustment}，原因必填、只追加）</td></tr>
 *
 * </table>
 *
 * <p><b>🔴 跨币种绝不相加</b>：一个 SKU 在一个币种下有一条构成；上游其它币种的金额单列在
 * {@code foreignAmounts} 且**不折算**（本仓没有汇率表，汇率是 12 财务的资产）。
 */
public final class SkuCostCompositionModels {

  private SkuCostCompositionModels() {}

  // ================================================================ 词表

  /**
   * 成本法。
   *
   * <p><b>枚举只增不改</b>：成本法会进历史快照（{@code sku_cost_revision.cost_method}），
   * 改语义会让历史成本读起来是错的。
   */
  public enum CostMethod {
    /** 移动加权：窗口内全部有效到货的加权平均单价。 */
    MOVING_AVERAGE(
        "移动加权",
        "窗口内全部有效到货按金额÷数量加权平均。适合货品同质、批次差异小的场景。"),
    /** 先进先出：按到货日升序消耗到目标数量后的加权平均单价。 */
    FIFO(
        "先进先出",
        "按到货日升序消耗到目标数量后的加权平均单价，取的是最早一批还在动的价格。"
            + "本服务没有出库消耗台账（07 库存未交付），目标数量以最近一次到货数量为代理值。"),
    /** 批次：以每一条到货记录为一个批次，单位成本取目标批次的单价。 */
    LOT(
        "批次",
        "每一条到货记录即一个批次，单位成本取目标批次的单价；响应同时给全部批次清单。");

    private final String label;
    private final String basis;

    CostMethod(String label, String basis) {
      this.label = label;
      this.basis = basis;
    }

    public String label() {
      return label;
    }

    public String basis() {
      return basis;
    }

    public static CostMethod parse(String raw) {
      if (raw == null) return null;
      for (var m : values()) {
        if (m.name().equalsIgnoreCase(raw.trim())) return m;
      }
      return null;
    }
  }

  /** 五个成本分项。 */
  public enum CostComponent {
    PURCHASE("采购成本"),
    FIRST_MILE("头程分摊"),
    PACKAGING("包装成本"),
    DUTY("关税"),
    OTHER_DIRECT("其他直接成本");

    private final String label;

    CostComponent(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析失败返回 null（调用方据此给出可读错误，而不是抛枚举异常）。 */
    public static CostComponent parse(String raw) {
      if (raw == null) return null;
      for (var c : values()) {
        if (c.name().equalsIgnoreCase(raw.trim())) return c;
      }
      return null;
    }
  }

  // ================================================================ 能力声明

  /**
   * 一个分项的能力声明。
   *
   * @param code 分项代码
   * @param label 分项中文名
   * @param source 数据来源（{@code CATALOG_VENDOR_DELIVERY} / {@code WAREHOUSE_FIRST_MILE} / ...）
   * @param kind 来源类型（{@code DERIVED} 派生 / {@code PARAMETER} 参数 / {@code MANUAL} 手工）
   * @param available 当前是否取得到
   * @param ownerModule 该分项的归属模块（未交付时点名）
   * @param impact 未交付时本模块怎么处理
   * @param basis 口径说明（页面上要显示的那一句）
   * @param note 补充说明
   */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record ComponentCapability(
      String code,
      String label,
      String source,
      String kind,
      boolean available,
      String ownerModule,
      String impact,
      String basis,
      String note) {}

  /** 能力声明。页面上把它固定显示在顶部 —— 蓝图那句「成本口径必须在页面上明示」靠它落地。 */
  public record CostCapabilities(
      List<ComponentCapability> components,
      List<MethodOption> costMethods,
      List<String> currencies,
      String costBasisNote,
      String upstreamNote,
      String downstreamNote,
      String sourceNote,
      String actionsNote) {}

  /** 成本法选项。 */
  public record MethodOption(String value, String label, String basis) {}

  // ================================================================ 列表

  /** 列表查询条件。 */
  public record CostListQuery(
      String keyword,
      String costMethod,
      String currency,
      Boolean maintained,
      @Schema(nullable = true) LocalDate windowFrom,
      @Schema(nullable = true) LocalDate windowTo,
      int page,
      int size) {}

  /**
   * 列表一行 = 一个 (SKU, 计价币种) 的成本构成条目；未维护的 SKU 也在列表里（{@code maintained=false}）。
   *
   * <p><b>列表不实时连上游</b>：头程分摊与关税分项要经 {@link CostBasisPort} 取上游，
   * 列表页有几十行，逐行连上游会把一次列表查询变成几十次跨服务调用。
   * 所以列表只给**本服务自有**的部分 + 同库派生的采购成本，头程与关税在明细里算。
   */
  public record CostRow(
      UUID skuId,
      String skuCode,
      String skuName,
      String spuCode,
      String baseUnit,
      String skuStatus,
      @Schema(nullable = true) UUID profileId,
      boolean maintained,
      String costMethod,
      String costMethodLabel,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) BigDecimal packagingCost,
      @Schema(nullable = true) BigDecimal otherDirectCost,
      @Schema(nullable = true) BigDecimal purchaseCost,
      @Schema(nullable = true) Integer purchaseDeliveryCount,
      int revisionCount,
      @Schema(nullable = true) OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime updatedAt) {}

  /** 列表页。 */
  public record CostListPage(
      List<CostRow> items, long total, int page, int size, String windowNote, String note) {}

  // ================================================================ 明细

  /** 一个币种上的一个金额。 */
  public record AmountRow(String currency, BigDecimal amount) {}

  /**
   * 一个分项。
   *
   * @param available 这一项**取得到**吗。false 时 {@code amount} 必为 null，{@code unavailableReason} 说明为什么。
   *     <b>false ≠ 0</b>：读不到与「确实是 0」是两件事。
   * @param partial 这一项**取到了但不完整**吗（例如到货记录里有几行折算不出基础数量，被排除在外）。
   *     partial 的分项**有值**，但那个值只覆盖了一部分事实 ⇒ 合计只能算「已知部分合计」。
   * @param amount 计价币种下的取值；取不到时为 null
   * @param amounts 逐币种明细（跨币种不相加）
   */
  public record CostComponentRow(
      String code,
      String label,
      String source,
      String kind,
      boolean available,
      boolean partial,
      String unavailableReason,
      BigDecimal amount,
      List<AmountRow> amounts,
      String basis,
      String note) {}

  /**
   * 供应商报价参考。
   *
   * <p><b>🔴 这不是采购成本。</b>蓝图要求采购成本来自 06 采购的**实际成交价**，
   * 而 6.3 采购单尚未交付（见 {@code ComponentCapability} 的说明）；本仓 catalog 侧能拿到的
   * 只有 {@code supplier_quote}（V32）——那是**报价**，是「还没下单时谈下来的价」。
   * 所以本字段是**只读参考**，{@code kind=REFERENCE}，<b>绝不计入任何合计</b>，
   * 页面上必须与采购成本分项**分开**显示并标明「未成交」。
   */
  public record QuoteReference(
      boolean available,
      String currency,
      @Schema(nullable = true) BigDecimal unitPrice,
      @Schema(nullable = true) BigDecimal minQuantity,
      String purchaseUnitCode,
      String supplierRoleId,
      String status,
      String validFrom,
      @Schema(nullable = true) String validTo,
      String reason,
      String note) {}

  /** 上游其它币种的金额：<b>未折算，不计入合计</b>。 */
  public record ForeignAmount(
      String component,
      String componentLabel,
      String currency,
      BigDecimal amount,
      String reason) {}

  /** 手工调整项。 */
  public record AdjustmentRow(
      UUID id,
      String component,
      String componentLabel,
      BigDecimal amount,
      String currency,
      String reason,
      OffsetDateTime effectiveFrom,
      boolean isVoid,
      String voidReason,
      @Schema(nullable = true) OffsetDateTime voidedAt,
      OffsetDateTime createdAt) {}

  /** 成本变动历史的一版。 */
  public record RevisionRow(
      UUID id,
      int revisionNo,
      String costMethod,
      String costMethodLabel,
      String currency,
      @Schema(nullable = true) BigDecimal purchaseCost,
      @Schema(nullable = true) BigDecimal firstMileAllocation,
      @Schema(nullable = true) BigDecimal packagingCost,
      @Schema(nullable = true) BigDecimal dutyCost,
      @Schema(nullable = true) BigDecimal otherDirectCost,
      @Schema(nullable = true) BigDecimal totalUnitCost,
      boolean snapshotComplete,
      String missingComponents,
      OffsetDateTime effectiveFrom,
      String changeReason,
      OffsetDateTime createdAt) {}

  /** 批次（成本法 = 批次时的批次清单）。 */
  public record LotRow(
      String lotRef,
      LocalDate receivedDate,
      BigDecimal quantity,
      BigDecimal baseQuantity,
      BigDecimal amount,
      @Schema(nullable = true) BigDecimal unitCost,
      String currency,
      boolean selected) {}

  /**
   * 成本构成明细。
   *
   * @param totalComplete 合计是否**完整**。false 表示有分项读不到（上游不可达 / 该分项无事实），
   *     此时 {@code totalAmount} 只是「已知部分合计」，页面上必须标明。
   * @param missingComponents 缺了哪些分项（完全读不到）
   * @param partialComponents 哪些分项**有值但不完整**（例如到货记录里有一部分折算不出基础数量）
   * @param excludedDeliveryCount 窗口内被排除的到货记录数。>0 ⇒ 采购成本分项不完整。
   * @param quoteReference 供应商报价参考（**只读参考，不是采购成本，不计入合计**）
   */
  public record CostDetail(
      UUID skuId,
      String skuCode,
      String skuName,
      String spuCode,
      String baseUnit,
      boolean maintained,
      String costMethod,
      String costMethodLabel,
      String costMethodBasis,
      String currency,
      @Schema(nullable = true) OffsetDateTime effectiveFrom,
      List<CostComponentRow> components,
      List<AmountRow> totals,
      @Schema(nullable = true) BigDecimal totalAmount,
      boolean totalComplete,
      List<String> missingComponents,
      List<String> partialComponents,
      int excludedDeliveryCount,
      List<ForeignAmount> foreignAmounts,
      List<AdjustmentRow> adjustments,
      List<RevisionRow> recentRevisions,
      List<LotRow> lots,
      QuoteReference quoteReference,
      LocalDate windowFrom,
      LocalDate windowTo,
      String basis,
      String note) {}

  // ================================================================ 写

  /**
   * 保存成本构成（成本法 / 计价币种 / 包装成本参数 / 生效时间）。
   *
   * <p>{@code currency} 是构成的身份的一部分：同 SKU 换币种 = <b>新增一条构成</b>，不是就地改币种
   * （就地改会把旧币种下的历史版本与新币种混在一起）。
   */
  public record ProfileInput(
      @NotBlank String costMethod,
      @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
      @NotNull OffsetDateTime effectiveFrom,
      @Schema(nullable = true) BigDecimal packagingCost,
      @NotBlank @Size(max = 500) String changeReason,
      long version) {}

  /** 追加手工调整项。 */
  public record AdjustmentInput(
      @NotBlank String component,
      @NotNull BigDecimal amount,
      @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
      @NotBlank @Size(max = 500) String reason,
      @NotNull OffsetDateTime effectiveFrom) {}

  /** 作废理由。 */
  public record VoidInput(@NotBlank @Size(max = 500) String reason) {}

  /** 保存结果。 */
  public record CostSaved(
      UUID profileId,
      UUID revisionId,
      int revisionNo,
      String currency,
      OffsetDateTime effectiveFrom,
      long version) {}
}
