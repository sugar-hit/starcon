package com.starcon.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 5.1.1.2 价目项批量维护。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：在价目表内维护 SKU 价格，支持按分类/品牌/SKU 范围批量设价、阶梯价（按数量门槛）、Excel 导入导出，保存前预览影响范围。
 *
 * **输入**：价目项录入/批量操作（固定值、按百分比、按类目统一）；`price_list_item` 记录。
 *
 * **输出**：价目项记录（SKU、单价、货币、数量门槛、生效区间）；导入校验报告。
 *
 * **关系**：上游 = 5.1.1.1、3.1.2.1 SKU；下游 = 5.3 测算、5.4 调价。**同优先级多条有效价目必须报错让用户选**，不随机取（实体目录 catalog 价格规则）。
 *
 * </blockquote>
 *
 * <h2>🔴 本类的五条纪律</h2>
 *
 * <ol>
 *   <li><b>🔴 价目项没有币种字段</b>：币种钉在价目表头上（{@code price_list.currency}），
 *       价目项**结构上无法跨币种**。视图里的 {@code currency} 是**读出来的事实**
 *       （从所属价目表带出来），不是客户端能填的字段。
 *   <li><b>🔴 一条价目项 = 一个阶梯档位</b>：{@code minQuantity} 是该档的起始数量门槛，
 *       {@code 0} = 基础档。同一个 SKU 的多行 = 阶梯价，**不是冲突**。
 *   <li><b>🔴 优先级数字越小越优先</b>（与 3.6.2.1 供应商报价同一约定，不另造反向语义）。
 *       同一档位命中多条**同优先级**的有效价目 ⇒ 判定为歧义，
 *       解析出口抛 409 并列出候选，**绝不随便挑一条**。
 *   <li><b>🔴 生效区间是半开区间</b>：{@code [validFrom, validTo)} —— 起点含、终点不含。
 *   <li><b>🔴 金额与数量都是 BigDecimal</b>：单价与数量门槛在库里都是 {@code NUMERIC(19,6)}，
 *       端到端不碰浮点（AGENTS.md）。
 * </ol>
 */
public final class PriceListItemModels {

  private PriceListItemModels() {}

  // ================================================================ 词表

  /**
   * 批量设价的**模式**（蓝图输入句：「批量操作（固定值、按百分比、按类目统一）」）。
   *
   * <p>三种模式是三个**真正不同**的算法，不是同一个算法的三个名字：
   *
   * <ul>
   *   <li>{@link #FIXED} 固定值：命中的 SKU 全部设成同一个单价；
   *   <li>{@link #PERCENTAGE} 按百分比：在**该 SKU 自己的基准价**上按涨跌幅换算
   *       （{@code 10} = 涨 10%，{@code -10} = 降 10%）；没有基准价的 SKU 会被跳过并说明原因；
   *   <li>{@link #CATEGORY_UNIFIED} 按类目统一：每个分类给一个价，
   *       该分类下所有 SKU 统一设成这个价。
   * </ul>
   */
  public enum BatchMode {
    /** 固定值：所有命中的 SKU 设成同一个单价。 */
    FIXED("固定值"),
    /** 按百分比：在各自的基准价上按涨跌幅换算。 */
    PERCENTAGE("按百分比"),
    /** 按类目统一：每个分类一个价，分类内统一。 */
    CATEGORY_UNIFIED("按类目统一");

    private final String label;

    BatchMode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static BatchMode parse(String raw) {
      if (raw == null) return null;
      for (var mode : values()) {
        if (mode.name().equalsIgnoreCase(raw.trim())) return mode;
      }
      return null;
    }
  }

  /** 批量设价的**范围**（蓝图职责句：「按分类/品牌/SKU 范围批量设价」）。 */
  public enum BatchScope {
    /** 按分类：命中该分类下所有 SKU（分类取自 {@code spu.category}）。 */
    CATEGORY("按分类"),
    /** 按品牌：命中该品牌下所有 SKU（品牌取自 {@code spu.brand}）。 */
    BRAND("按品牌"),
    /** 按 SKU 范围：命中给定的 SKU 编码清单。 */
    SKU_SET("按 SKU 清单");

    private final String label;

    BatchScope(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static BatchScope parse(String raw) {
      if (raw == null) return null;
      for (var scope : values()) {
        if (scope.name().equalsIgnoreCase(raw.trim())) return scope;
      }
      return null;
    }
  }

  /**
   * 一条价目项在解析时的选择状态。
   *
   * <p>与 3.6.2.1 供应商报价的 {@code selectionState} 同一套词表：
   * {@code SELECTED} = 唯一默认采用；{@code AMBIGUOUS} = 与另一条并列（同优先级，必须人工选）；
   * {@code LOWER_PRIORITY} = 有效但优先级更低（备选）；
   * {@code NOT_APPLICABLE} = 该档位在本次数量下不适用（门槛高于数量）。
   */
  public enum SelectionState {
    SELECTED("默认采用"),
    AMBIGUOUS("需人工选择"),
    LOWER_PRIORITY("备选"),
    NOT_APPLICABLE("数量下不适用");

    private final String label;

    SelectionState(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static SelectionState of(String raw) {
      if (raw == null) return null;
      for (var state : values()) {
        if (state.name().equalsIgnoreCase(raw.trim())) return state;
      }
      return null;
    }
  }

  // ================================================================ 输入

  /**
   * 一条价目项（录入 / 修改共用同一个形状）。
   *
   * <p>🔴 这里**没有** {@code currency} 字段：币种只能来自所属价目表。
   * 客户端塞一个 {@code currency} 进来会被 Jackson 直接 400 拒掉（fail-on-unknown-properties）。
   */
  public record PriceListItemInput(
      /** SKU 编码（租户内唯一）。 */
      @NotBlank String skuCode,
      /** 单价（>= 0）。 */
      @NotNull BigDecimal unitPrice,
      /** 数量门槛（阶梯档位起点）。空 = 0 = 基础档。 */
      @Schema(nullable = true) BigDecimal minQuantity,
      /** 优先级（数字越小越优先）。空 = 0。 */
      @Schema(nullable = true) Integer priority,
      /** 生效起点（含）。 */
      @NotNull OffsetDateTime validFrom,
      /** 生效终点（不含）。空 = 长期有效。 */
      @Schema(nullable = true) OffsetDateTime validTo,
      Boolean enabled,
      String note,
      /** 乐观锁版本（修改时必填；新建时忽略）。 */
      @Schema(nullable = true) Long version) {}

  /** 「按类目统一」模式下的一个分类与其统一价。 */
  public record PriceListItemCategoryPrice(@NotBlank String category, @NotNull BigDecimal unitPrice) {}

  /**
   * 一次批量设价请求（预览与执行共用同一个形状）。
   *
   * <p>🔴 **预览与执行是同一个请求体**：`POST …/items/preview` 只算不算，
   * `POST …/items/apply` 才落库。形状一致 ⇒ 预览看到的就是执行会做的。
   *
   * <p>各字段按模式/范围生效（模式专属字段没给 ⇒ 400 并说清缺哪个）：
   *
   * <table border="1">
   *   <caption>字段生效矩阵</caption>
   *   <tr><th>模式</th><th>需要的字段</th></tr>
   *   <tr><td>FIXED</td><td>{@code unitPrice}</td></tr>
   *   <tr><td>PERCENTAGE</td><td>{@code percent}（涨跌幅百分比）</td></tr>
   *   <tr><td>CATEGORY_UNIFIED</td><td>{@code categoryPrices}</td></tr>
   * </table>
   */
  public record PriceListItemBatchRequest(
      @NotNull BatchMode mode,
      @Schema(nullable = true) BatchScope scope,
      /** 范围 = 按分类时必填。 */
      @Schema(nullable = true) String category,
      /** 范围 = 按品牌时必填。 */
      @Schema(nullable = true) String brand,
      /** 范围 = 按 SKU 清单时必填。 */
      @Schema(nullable = true) List<String> skuCodes,
      /** 模式 = 按类目统一时必填。 */
      @Schema(nullable = true) @Valid List<PriceListItemCategoryPrice> categoryPrices,
      /** 模式 = 固定值时必填。 */
      @Schema(nullable = true) BigDecimal unitPrice,
      /** 模式 = 按百分比时必填（10 = 涨 10%，-10 = 降 10%）。 */
      @Schema(nullable = true) BigDecimal percent,
      /** 阶梯档位起点。空 = 0 = 基础档。 */
      @Schema(nullable = true) BigDecimal minQuantity,
      /** 优先级（数字越小越优先）。空 = 0。 */
      @Schema(nullable = true) Integer priority,
      /** 生效起点（含）。 */
      @NotNull OffsetDateTime validFrom,
      /** 生效终点（不含）。空 = 长期有效。 */
      @Schema(nullable = true) OffsetDateTime validTo,
      Boolean enabled,
      String note) {}

  /**
   * Excel 导入请求（**文件走 base64 JSON**，不走 multipart）。
   *
   * <p>🔴 浏览器同源代理只转发 JSON（{@code request.text()} + 固定 {@code Content-Type: application/json}），
   * multipart 过不去 —— 这是 15.x「认证材料上传」已经踩过的坑，本模块照抄同一手法。
   */
  public record PriceListItemImportRequest(
      @NotBlank String fileName,
      /** xlsx 文件内容的 Base64（**不是**明文，也不是 URL）。 */
      @NotBlank String contentBase64,
      /** true = 校验通过则落库；false（默认）= 只出校验报告，不落库。 */
      @Schema(nullable = true) Boolean apply) {}

  // ================================================================ 输出

  /** 一条价目项（读）。 */
  public record PriceListItemView(
      UUID id,
      UUID priceListId,
      String priceListCode,
      /** 币种（**读自所属价目表**，不是价目项自己的字段）。 */
      String currency,
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal unitPrice,
      BigDecimal minQuantity,
      int priority,
      String priorityLabel,
      OffsetDateTime validFrom,
      @Schema(nullable = true) OffsetDateTime validTo,
      boolean enabled,
      /** 此刻是否生效（半开区间判定，用 DB 时钟）。 */
      boolean effectiveNow,
      String note,
      /** 乐观锁版本（回传给修改接口）。 */
      long version,
      @Schema(nullable = true) String createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 价目项列表（分页）。 */
  public record PriceListItemPage(List<PriceListItemView> items, int page, int size, long total) {}

  /** 解析时的一条候选（带选择状态）。 */
  public record PriceListItemCandidate(
      UUID id,
      String skuCode,
      String skuName,
      BigDecimal unitPrice,
      BigDecimal minQuantity,
      int priority,
      OffsetDateTime validFrom,
      @Schema(nullable = true) OffsetDateTime validTo,
      String selectionState,
      String selectionStateLabel) {}

  /**
   * 「这个 SKU 在这张表此刻该卖多少」的解析结果。
   *
   * @param selected 唯一命中的价目项；**歧义时为 null**（系统不随机挑）
   * @param unitPrice 解析出的单价；**歧义时为 null**
   * @param ambiguous 是否歧义（同优先级多条有效价目）
   */
  public record PriceListItemResolution(
      UUID priceListId,
      String priceListCode,
      String currency,
      UUID skuId,
      String skuCode,
      String skuName,
      OffsetDateTime at,
      @Schema(nullable = true) BigDecimal quantity,
      @Schema(nullable = true) PriceListItemView selected,
      @Schema(nullable = true) BigDecimal unitPrice,
      List<PriceListItemCandidate> candidates,
      boolean ambiguous,
      @Schema(nullable = true) String ambiguityProblem,
      List<String> warnings,
      String note) {}

  /**
   * 一组冲突：同一个 SKU、同一数量门槛、同一优先级，且生效区间重叠的多条有效价目。
   *
   * <p>🔴 这正是蓝图加粗句「同优先级多条有效价目必须报错让用户选」的具象：
   * 在 {@code overlapFrom ~ overlapTo} 这段时间里，这个 SKU 的这一档**无法唯一解析**。
   */
  public record PriceListItemConflict(
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal minQuantity,
      int priority,
      OffsetDateTime overlapFrom,
      @Schema(nullable = true) OffsetDateTime overlapTo,
      List<PriceListItemCandidate> candidates) {}

  /** 批量操作里一个 SKU 的预览行。 */
  public record PriceListItemBatchLine(
      UUID skuId,
      String skuCode,
      String skuName,
      @Schema(nullable = true) String category,
      @Schema(nullable = true) String brand,
      /** 基准价（按百分比模式下是算价基数；其它模式为空）。 */
      @Schema(nullable = true) BigDecimal basePrice,
      /** 算出来的新价。 */
      @Schema(nullable = true) BigDecimal newPrice,
      /** CREATE = 新增一条；UPDATE = 覆盖同档位的既有价目项；SKIP = 不动（原因在 reason）。 */
      String action,
      @Schema(nullable = true) String reason,
      /** 这一行会不会造成同优先级冲突。 */
      boolean conflict,
      BigDecimal minQuantity,
      int priority) {}

  /**
   * 批量操作的影响范围预览（蓝图职责句：「保存前预览影响范围」）。
   *
   * @param matchedSkus 命中的 SKU 数
   * @param createdItems 会新增的价目项条数
   * @param updatedItems 会覆盖的价目项条数
   * @param skippedSkus 跳过的 SKU 数（原因在 lines 里逐条写明）
   * @param conflictSkus 会造成同优先级冲突的 SKU 数
   */
  public record PriceListItemBatchPreview(
      String mode,
      String modeLabel,
      String scope,
      String scopeLabel,
      String currency,
      int matchedSkus,
      int createdItems,
      int updatedItems,
      int skippedSkus,
      int conflictSkus,
      List<PriceListItemBatchLine> lines,
      int lineLimit,
      boolean truncated,
      List<PriceListItemConflict> conflicts,
      List<String> warnings,
      String summary) {}

  /** 批量执行的结果。 */
  public record PriceListItemBatchResult(
      String mode,
      String currency,
      int matchedSkus,
      int createdItems,
      int updatedItems,
      int skippedSkus,
      int conflictSkus,
      List<PriceListItemConflict> conflicts,
      List<String> warnings,
      String summary) {}

  /** 导入校验报告里的一行。 */
  public record PriceListItemImportRowReport(
      int rowNo,
      String skuCode,
      @Schema(nullable = true) String skuName,
      @Schema(nullable = true) BigDecimal unitPrice,
      BigDecimal minQuantity,
      int priority,
      @Schema(nullable = true) OffsetDateTime validFrom,
      @Schema(nullable = true) OffsetDateTime validTo,
      @Schema(nullable = true) String declaredCurrency,
      boolean enabled,
      /** OK = 可落库；ERROR = 有错（整批不落库）。 */
      String status,
      List<String> messages) {}

  /**
   * 导入校验报告（蓝图输出句：「导入校验报告」）。
   *
   * @param applied 这一批是否真的落了库
   */
  public record PriceListItemImportReport(
      UUID batchId,
      String fileName,
      /** PREVIEW / APPLIED / REJECTED。 */
      String status,
      int totalRows,
      int okRows,
      int errorRows,
      int warningRows,
      int appliedRows,
      boolean applied,
      List<PriceListItemImportRowReport> rows,
      String summary) {}

  // ================================================================ 能力声明

  /** 能力声明里的一条口径说明。 */
  public record CapabilityNote(String title, String content) {}

  /** 能力声明。页面顶部固定显示它。 */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record PriceListItemCapabilities(
      List<BatchMode> modes,
      List<BatchScope> scopes,
      List<SelectionState> selectionStates,
      List<CapabilityNote> notes,
      String currencyNote,
      String tierNote,
      String priorityNote,
      String conflictNote,
      String importNote,
      String exportNote,
      String upstreamNote,
      String downstreamNote) {}
}
