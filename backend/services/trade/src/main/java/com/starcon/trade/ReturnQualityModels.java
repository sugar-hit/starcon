package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 11.2.3.1 质检与判定的传输模型。
 *
 * <p>蓝图 11-aftersales-and-returns.md 原文：
 *
 * <blockquote>
 * 职责：退回商品质检：外观、功能、配件完整性、是否可再销售；给出可再销售/残次/报废判定与残值估算。
 * 输入：待检退货；质检模板（按类目）；质检结果。
 * 输出：质检结论 + 残值金额 + 质量状态指令。
 * 关系：上游 = 11.2.2.1；下游 = 11.2.3.2、12 财务（残值处理）。
 * <b>可再销售判定要保守</b>：包装破损但商品完好可判可再销售，缺少配件则应判残次。
 * </blockquote>
 *
 * <p><b>本文件最重要的三件事</b>：
 *
 * <ol>
 *   <li><b>判定是三值枚举状态机，不是布尔开关</b>（硬规则 1）。{@link QualityVerdict} 的
 *       {@link QualityVerdict#decide} 是唯一的判定入口：它吃一份
 *       {@link QualityFailureProfile}（入库时记的两个维度 + 本次逐项结果里失败项的维度），
 *       吐出<b>允许的结论集合</b>。蓝图那句话的两半因此落在两条**不同**的路径上：
 *       包装破损走 {@link QualityVerdict#allowed} 的「不禁止再销售」，
 *       缺配件走「只能是残次」。用笼统布尔量表达这两件事，等于让质检员去猜那个值说的是包装还是货。</li>
 *   <li><b>残值是真算出来的，传输层没有残值字段</b>（硬规则 2）。{@link QualityInspectionCreate}
 *       与 {@link QualityInspectionConclude} <b>都不接受任何金额</b>：原值取订单行单价 × 实收数量，
 *       系数取模板，成色扣减取失败检项。残值金额由
 *       {@link com.starcon.trade.ReturnQualityService} 按可审阅规则算出后写库，
 *       再由 DB 触发器重算比对（V179 守卫⑤）。</li>
 *   <li><b>质量状态指令只出不消费</b>（输出句第三样）。{@link QualityDispositionInstruction} 恒为
 *       {@code PENDING}、消费方恒为 11.2.3.2 —— 处置端点属 11.2.3.2，未交付，
 *       本模块<b>刻意没有</b>「转可售」入口，运行角色对该表的 UPDATE/DELETE 也已收回。</li>
 * </ol>
 *
 * <p><b>命名。</b>统一用 {@code ReturnQuality*} / {@code Quality*} 前缀，与 {@code ReturnReceipt*}
 * 零重叠 —— 契约被 codegen 合并进一个扁平文件、同名后者覆盖前者且不报冲突。
 */
public final class ReturnQualityModels {

  private ReturnQualityModels() {}

  // ================================================================ 枚举

  /**
   * 质检类目状态。停用是<b>口径变更</b>：停用后不再被新质检单选中，已判定的质检单不受影响。
   *
   * <p>⚠️ 类目真值在 catalog 服务（1 商品中心），按模块边界纪律<b>禁止跨服务 SQL</b>，
   * 本服务读不到。所以「这个 SKU 归哪个质检类目」是<b>租户自维护</b>的作业口径，
   * 而不是商品主数据的复制品（见 V179 第二节）。
   */
  public enum QualityCategoryStatus {
    ACTIVE("启用"),
    DISABLED("停用");

    private final String label;

    QualityCategoryStatus(String label) {
      this.label = label;
    }

    public String label() {
      return this.label;
    }

    public boolean active() {
      return this == ACTIVE;
    }
  }

  /**
   * 质检模板状态。
   *
   * <p>⚠️ <b>只有 DRAFT 能改检项</b>：模板改版会改变判定口径，而历史质检单要能复现当时的口径。
   * 所以 ACTIVE 之后只能新建一个版本号，而不是就地改（V179 守卫②）。
   */
  public enum QualityTemplateStatus {
    DRAFT("草稿"),
    ACTIVE("已启用"),
    RETIRED("已停用");

    private final String label;

    QualityTemplateStatus(String label) {
      this.label = label;
    }

    public String label() {
      return this.label;
    }

    public boolean editable() {
      return this == DRAFT;
    }
  }

  /**
   * 检查维度（职责句的三个动词 + 包装）。
   *
   * <p>⚠️ <b>四个显式取值，刻意不合成一个 condition</b>。蓝图那句加粗句的两半正好落在这个枚举上：
   *
   * <ul>
   *   <li>{@link #PACKAGING} 失败（外箱压瘪 / 损毁 / 无包装）<b>不禁止</b>判可再销售 ——
   *       「包装破损但商品完好可判可再销售」。</li>
   *   <li>{@link #APPEARANCE} 失败（划痕 / 掉漆）也<b>不禁止</b>判可再销售 ——
   *       它扣的是成色系数，不是可售资格；外观瑕疵品折价再售是行业常态。</li>
   *   <li>{@link #FUNCTION} 失败（功能坏了）<b>禁止</b>判可再销售。</li>
   *   <li>{@link #ACCESSORY} 失败（配件少了）<b>禁止</b>判可再销售，且进一步<b>只能判残次</b> ——
   *       「缺少配件则应判残次」。</li>
   * </ul>
   */
  public enum QualityDimension {
    APPEARANCE("外观"),
    FUNCTION("功能"),
    ACCESSORY("配件完整性"),
    PACKAGING("包装");

    private final String label;

    QualityDimension(String label) {
      this.label = label;
    }

    public String label() {
      return this.label;
    }

    /**
     * 该维度失败是否<b>禁止</b>判可再销售。
     *
     * <p>⚠️ 这里就是蓝图那句话被拆成两条路径的地方：PACKAGING / APPEARANCE 返回 false，
     * FUNCTION / ACCESSORY 返回 true。把它做成一个布尔开关（「有没有问题」）的那一刻，
     * 这两条路径就没法共存了。
     */
    public boolean blocksResale() {
      return this == FUNCTION || this == ACCESSORY;
    }
  }

  /**
   * 判定结论：<b>三值枚举</b>（硬规则 1）。
   *
   * <p>⚠️ 刻意<b>不是</b> {@code boolean resalable}：一个布尔量表达不了「残次」与「报废」的区别，
   * 而这两者的残值上限、指令类型、目标库存状态、是否可逆全都不同。
   */
  public enum QualityVerdict {
    RESALABLE("可再销售"),
    DEFECTIVE("残次"),
    SCRAPPED("报废");

    private final String label;

    QualityVerdict(String label) {
      this.label = label;
    }

    public String label() {
      return this.label;
    }

    /** 质量状态指令的类型（蓝图输出句第三样，由 DB 触发器生成）。 */
    public String instructionType() {
      return switch (this) {
        case RESALABLE -> "MOVE_TO_SELLABLE";
        case DEFECTIVE -> "MOVE_TO_DEFECTIVE";
        case SCRAPPED -> "WRITE_OFF_SCRAP";
      };
    }

    /** 目标库存状态（下游 11.2.3.2 的落点）。本模块<b>只出指令，不执行流转</b>。 */
    public String targetInventoryState() {
      return switch (this) {
        case RESALABLE -> "SELLABLE";
        case DEFECTIVE -> "DEFECTIVE";
        case SCRAPPED -> "SCRAP";
      };
    }

    /** 报废是<b>不可逆</b>的（蓝图 11.2.3.2 原话「报废是不可逆的，需审批与原因」）。 */
    public boolean irreversible() {
      return this == SCRAPPED;
    }

    /**
     * 该结论<b>是否</b>被当前失败画像允许 —— 判定状态机的<b>唯一</b>判定入口。
     *
     * <p>三条显式路径（与 V179 守卫⑤逐条对应）：
     *
     * <ol>
     *   <li><b>配件缺失 ⇒ 只能是残次</b>：既不许判可再销售，也不许判报废。
     *       缺配件的机器仍然是机器，判报废等于把一台能作残次处理的货当废铁丢掉。</li>
     *   <li><b>商品本体损坏 / 功能失效 ⇒ 可再销售被禁</b>（残次或报废）。</li>
     *   <li><b>其余 ⇒ 一律不禁止</b>。包装破损、损毁、无包装都在这里 ——
     *       它们扣成色系数，不扣可售资格。</li>
     * </ol>
     */
    public boolean allowed(QualityFailureProfile profile) {
      return switch (profile.requiredPath()) {
        case ACCESSORY_MISSING -> this == DEFECTIVE;
        case GOODS_OR_FUNCTION_DAMAGED -> this != RESALABLE;
        case PACKAGING_OR_APPEARANCE_ONLY -> true;
      };
    }

    /**
     * 按失败画像**推荐**一个结论。
     *
     * <p>⚠️ 这是<b>推荐</b>不是「自动判定」：判定必须由人给结论与理由（判残次要拿去向供应商索赔），
     * 本方法只把默认值填进下拉框，调用方仍然要显式提交。
     */
    public static QualityVerdict recommend(QualityFailureProfile profile) {
      return switch (profile.requiredPath()) {
        case ACCESSORY_MISSING -> DEFECTIVE;
        case GOODS_OR_FUNCTION_DAMAGED -> DEFECTIVE;
        case PACKAGING_OR_APPEARANCE_ONLY -> RESALABLE;
      };
    }
  }

  /**
   * 判定路径：把「入库时记的两个维度」与「本次失败检项的维度」压成<b>三条互斥路径之一</b>。
   *
   * <p>⚠️ 这是硬规则 1 的核心数据结构：它让「包装破损但商品完好」与「缺少配件」在判定的那一刻
   * 就走上不同的分支，而不是等到后面某个 if 里再猜一次。
   */
  public enum QualityVerdictPath {
    /** 路径③：只有包装 / 外观问题 —— <b>不禁止</b>可再销售。 */
    PACKAGING_OR_APPEARANCE_ONLY("仅包装或外观问题，可判可再销售"),
    /** 路径②：商品本体损坏或功能失效 —— 可再销售被禁。 */
    GOODS_OR_FUNCTION_DAMAGED("商品损坏或功能失效，可判残次或报废"),
    /** 路径①：配件缺失 —— <b>只能判残次</b>。 */
    ACCESSORY_MISSING("配件缺失，只能判残次");

    private final String label;

    QualityVerdictPath(String label) {
      this.label = label;
    }

    public String label() {
      return this.label;
    }
  }

  /**
   * 失败画像：判定状态机的输入。
   *
   * @param intakeGoodsCondition 入库时记的<b>商品状态</b>（V172 goods_condition）
   * @param intakePackagingCondition 入库时记的<b>外包装</b>（V172 packaging_condition）
   * @param failedDimensions 本次逐项结果里 FAIL 的维度
   */
  public record QualityFailureProfile(
      String intakeGoodsCondition,
      String intakePackagingCondition,
      List<String> failedDimensions) {

    /**
     * 归入三条路径之一。顺序不可调换：<b>配件缺失优先级最高</b> ——
     * 「缺配件且功能也坏了」仍然只能判残次，不能因为功能坏了就放行报废。
     */
    public QualityVerdictPath requiredPath() {
      if (ReturnReceiptModels.ReturnGoodsCondition.MISSING_PARTS.name().equals(intakeGoodsCondition)
          || hasFailed(QualityDimension.ACCESSORY.name())) {
        return QualityVerdictPath.ACCESSORY_MISSING;
      }
      if (ReturnReceiptModels.ReturnGoodsCondition.DAMAGED.name().equals(intakeGoodsCondition)
          || ReturnReceiptModels.ReturnGoodsCondition.DEFECTIVE.name().equals(intakeGoodsCondition)
          || hasFailed(QualityDimension.FUNCTION.name())) {
        return QualityVerdictPath.GOODS_OR_FUNCTION_DAMAGED;
      }
      return QualityVerdictPath.PACKAGING_OR_APPEARANCE_ONLY;
    }

    private boolean hasFailed(String... dimensions) {
      if (failedDimensions == null) {
        return false;
      }
      for (String dimension : dimensions) {
        if (failedDimensions.contains(dimension)) {
          return true;
        }
      }
      return false;
    }
  }

  /** 质检单状态机。DRAFT → CONCLUDED（终态）/ CANCELLED（终态）。 */
  public enum QualityInspectionStatus {
    DRAFT("草稿"),
    CONCLUDED("已判定"),
    CANCELLED("已撤销");

    private final String label;

    QualityInspectionStatus(String label) {
      this.label = label;
    }

    public String label() {
      return this.label;
    }

    /** 终态：结论与证据不能再改（DB 触发器 guard_return_quality_inspection_frozen 同一纪律）。 */
    public boolean terminal() {
      return this == CONCLUDED || this == CANCELLED;
    }

    public boolean draft() {
      return this == DRAFT;
    }
  }

  /**
   * 逐项结果。
   *
   * <p>⚠️ <b>刻意没有 {@code UNCHECKED}</b>：未检就是这一行还不存在。
   * 给一个「未检」取值的话，「判定时漏了一项」与「判定为不适用」在库里是同一个形状，
   * 而漏检正是质检事故最常见的来源。
   */
  public enum QualityCheckOutcome {
    PASS("合格"),
    FAIL("不合格"),
    NOT_APPLICABLE("不适用");

    private final String label;

    QualityCheckOutcome(String label) {
      this.label = label;
    }

    public String label() {
      return this.label;
    }

    public boolean failed() {
      return this == FAIL;
    }
  }

  /** 排序白名单。排序键会进 SQL 文本，放任调用方传表达式就是一条注入。 */
  public enum QualityInspectionSort {
    CREATED_DESC("i.created_at desc, i.id desc"),
    CONCLUDED_ASC("i.concluded_at asc nulls last, i.id desc"),
    VERDICT_ASC("i.verdict asc nulls last, i.created_at desc"),
    SALVAGE_DESC("i.salvage_amount desc, i.id desc");

    private final String sql;

    QualityInspectionSort(String sql) {
      this.sql = sql;
    }

    public String sql() {
      return this.sql;
    }

    public static QualityInspectionSort parse(String raw) {
      if (raw == null) {
        return CREATED_DESC;
      }
      return switch (raw) {
        case "created_desc" -> CREATED_DESC;
        case "concluded_asc" -> CONCLUDED_ASC;
        case "verdict_asc" -> VERDICT_ASC;
        case "salvage_desc" -> SALVAGE_DESC;
        default -> CREATED_DESC;
      };
    }
  }

  // ================================================================ 入参

  /** 建一个质检类目（租户自维护的作业口径，不是商品主数据）。 */
  public record QualityCategoryCreate(String categoryCode, String categoryName, @Schema(nullable = true) String note) {}

  /** 改类目名称 / 启停。刻意<b>不</b>给改编码的入口：编码是报表与模板口径的稳定锚点。 */
  public record QualityCategoryUpdate(
      String categoryName, String categoryStatus, @Schema(nullable = true) String note) {}

  /** 把一个 SKU 归到质检类目（蓝图「模板按类目取用」的解析依据）。 */
  public record QualitySkuMappingCreate(UUID skuId, @Schema(nullable = true) String skuCode) {}

  /**
   * 建质检模板（草稿）。
   *
   * <p>⚠️ 系数全部是 {@code BigDecimal}（映射 NUMERIC(9,6)），<b>禁浮点</b>：
   * 成色系数是财务口径，0.1 + 0.2 ≠ 0.3 这种事不能出现在残值里。
   */
  public record QualityTemplateCreate(
      UUID categoryId,
      String templateName,
      int templateVersion,
      BigDecimal baseCoefficient,
      BigDecimal minCoefficient,
      BigDecimal resaleCap,
      BigDecimal defectiveCap,
      BigDecimal scrapCap,
      @Schema(nullable = true) String note,
      List<QualityTemplateItemDraft> items) {}

  /** 模板检项（草稿）。维度决定这条检项失败时能不能判可再销售。 */
  public record QualityTemplateItemDraft(
      String itemCode,
      String itemName,
      String itemDimension,
      BigDecimal salvageRate,
      boolean itemRequired,
      @Schema(nullable = true) String expectedValue,
      @Schema(nullable = true) String failHint) {}

  /** 改模板草稿（整单替换检项）。只有 DRAFT 可改，且必须带 expectedVersion。 */
  public record QualityTemplateUpdate(
      String templateName,
      BigDecimal baseCoefficient,
      BigDecimal minCoefficient,
      BigDecimal resaleCap,
      BigDecimal defectiveCap,
      BigDecimal scrapCap,
      @Schema(nullable = true) String note,
      List<QualityTemplateItemDraft> items,
      Long expectedVersion) {}

  /**
   * 开一张质检单（对一条待检入库分录）。
   *
   * <p>⚠️ <b>不接受</b>数量、SKU、币种、残值：这些都由 DB 触发器从待检分录与订单行取
   * （V179 守卫④），传进来也传不进。
   *
   * @param templateId 可空；不给时按 SKU 所属类目自动取该类目<b>唯一启用</b>的模板
   */
  public record QualityInspectionCreate(
      UUID postingId, @Schema(nullable = true) UUID templateId) {}

  /** 逐项检查结果。FAIL 必须写说明（判残次要拿去索赔）。 */
  public record QualityCheckResultDraft(
      UUID templateItemId, String checkOutcome, @Schema(nullable = true) String checkNote,
      @Schema(nullable = true) UUID photoId) {}

  /** 录逐项结果（整单替换）。只有 DRAFT 可录，必须带 expectedVersion。 */
  public record QualityInspectionUpdate(
      List<QualityCheckResultDraft> results, Long expectedVersion) {}

  /**
   * 判定。
   *
   * <p>⚠️ <b>没有残值字段</b>（硬规则 2）：残值由服务层按「原值 × 生效系数」算出后写库，
   * 再由 DB 触发器重算比对。放进一个可填的金额等于把财务数据交给调用方编。
   */
  public record QualityInspectionConclude(String verdict, String conclusionNote, Long expectedVersion) {}

  /** 作废重判（判错了 / 录错了检项）。已判定与已撤销都是终态，所以只有 DRAFT 能撤销。 */
  public record QualityInspectionCancel(String cancelReason, Long expectedVersion) {}

  /** 列表查询。真分页 + 多维筛选 + 排序白名单。 */
  public record QualityInspectionListQuery(
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) String verdict,
      @Schema(nullable = true) UUID categoryId,
      @Schema(nullable = true) UUID templateId,
      @Schema(nullable = true) UUID receiptId,
      @Schema(nullable = true) String skuCode,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort) {}

  // ================================================================ 出参

  /** 质检类目。 */
  public record QualityCategoryRow(
      UUID id,
      String categoryCode,
      String categoryName,
      String categoryStatus,
      String categoryStatusLabel,
      long skuCount,
      long templateCount,
      long activeTemplateCount) {}

  /** 类目列表页。 */
  public record QualityCategoryPage(
      List<QualityCategoryRow> rows, long total, OffsetDateTime evaluatedAt, String note) {}

  /** 模板检项。 */
  public record QualityTemplateItemRow(
      UUID id,
      String itemCode,
      String itemName,
      String itemDimension,
      String itemDimensionLabel,
      boolean blocksResaleWhenFailed,
      BigDecimal salvageRate,
      boolean itemRequired,
      @Schema(nullable = true) String expectedValue,
      @Schema(nullable = true) String failHint,
      int itemNo) {}

  /** 质检模板。 */
  public record QualityTemplateRow(
      UUID id,
      String templateNo,
      UUID categoryId,
      String categoryCode,
      String categoryName,
      String templateName,
      int templateVersion,
      String templateStatus,
      String templateStatusLabel,
      BigDecimal baseCoefficient,
      BigDecimal minCoefficient,
      BigDecimal resaleCap,
      BigDecimal defectiveCap,
      BigDecimal scrapCap,
      int itemCount,
      long mandatoryItemCount,
      @Schema(nullable = true) String note,
      long version) {}

  /** 模板详情（含检项与「能否直接用来判定」的前置条件）。 */
  public record QualityTemplateDetail(
      QualityTemplateRow template,
      List<QualityTemplateItemRow> items,
      @Schema(nullable = true) String verdictPreviewNote,
      List<QualityVerdictOption> verdictOptions,
      List<QualityRelatedModule> relatedModules,
      OffsetDateTime evaluatedAt,
      String note) {}

  /** 模板列表页。 */
  public record QualityTemplatePage(
      List<QualityTemplateRow> rows, int page, int size, long total, QualityTemplateSummary summary,
      OffsetDateTime evaluatedAt, String paginationNote, String sortNote) {}

  /** 模板页汇总。 */
  public record QualityTemplateSummary(
      long total, long draft, long active, long retired, long itemCount, long mandatoryItemCount) {}

  /** 判定结论选项（带「当前是否允许」与不允许的理由）。 */
  public record QualityVerdictOption(
      String code, String label, boolean allowed, @Schema(nullable = true) String blockedReason) {}

  /**
   * 判定预览：质检员在提交之前就能看到「这批货**能**判成什么、**不能**判成什么、残值大概多少」。
   *
   * <p>⚠️ 残值是<b>试算</b>（按当前已录入的逐项结果），最终值以判定那一刻触发器的重算为准。
   */
  public record QualityVerdictPreview(
      UUID postingId,
      UUID receiptLineId,
      String skuCode,
      String skuName,
      BigDecimal pendingQty,
      String intakePackagingCondition,
      String intakePackagingConditionLabel,
      String intakeGoodsCondition,
      String intakeGoodsConditionLabel,
      List<String> failedDimensions,
      QualityVerdictPath requiredPath,
      String requiredPathLabel,
      List<QualityVerdictOption> verdictOptions,
      BigDecimal originalValue,
      String salvageCurrency,
      BigDecimal salvageDeduction,
      BigDecimal gradeCoefficient,
      BigDecimal salvageCap,
      BigDecimal salvageEstimate,
      OffsetDateTime evaluatedAt,
      String note) {}

  /** 质检单列表行。 */
  public record QualityInspectionRow(
      UUID id,
      String inspectionNo,
      UUID postingId,
      UUID receiptId,
      String receiptNo,
      UUID receiptLineId,
      UUID templateId,
      String templateName,
      int templateVersion,
      UUID categoryId,
      String categoryCode,
      String categoryName,
      @Schema(nullable = true) UUID skuId,
      String skuCode,
      String skuName,
      String batchNo,
      BigDecimal inspectedQty,
      String inspectionStatus,
      String inspectionStatusLabel,
      @Schema(nullable = true) String verdict,
      @Schema(nullable = true) String verdictLabel,
      BigDecimal originalValue,
      BigDecimal salvageAmount,
      String salvageCurrency,
      @Schema(nullable = true) String instructionType,
      String instructionStatus,
      int resultCount,
      long version) {}

  /** 逐项检查结果。 */
  public record QualityCheckResultRow(
      UUID id,
      UUID templateItemId,
      String itemCode,
      String itemName,
      String itemDimension,
      String itemDimensionLabel,
      boolean blocksResaleWhenFailed,
      String checkOutcome,
      String checkOutcomeLabel,
      @Schema(nullable = true) String checkNote,
      @Schema(nullable = true) UUID photoId,
      UUID checkedBy,
      OffsetDateTime checkedAt) {}

  /**
   * 质量状态指令（蓝图输出句第三样）。
   *
   * <p>⚠️ <b>恒为 {@code PENDING}，消费方恒为 11.2.3.2</b>：本模块<b>只出指令，不消费</b>。
   * 处置端点属 11.2.3.2，未交付；运行角色对本表的 UPDATE/DELETE 已收回，
   * 所以「在本模块里把它改成已消费」既没有代码路径也没有 SQL 权限。
   */
  public record QualityDispositionInstruction(
      UUID id,
      UUID inspectionId,
      UUID receiptId,
      UUID receiptLineId,
      String instructionType,
      String instructionTypeLabel,
      String targetInventoryState,
      @Schema(nullable = true) UUID skuId,
      String skuCode,
      String skuName,
      String batchNo,
      BigDecimal qty,
      BigDecimal salvageAmount,
      String salvageCurrency,
      BigDecimal originalValue,
      BigDecimal gradeCoefficient,
      String instructionStatus,
      String consumerModule,
      OffsetDateTime issuedAt,
      UUID issuedBy) {}

  /**
   * 质检留痕（追加式）。
   *
   * <p>⚠️ 名字带 {@code ReturnQuality} 前缀是**必需的**，不是风格问题：
   * {@code FulfillmentQualityModels} 里已有一个 {@code QualityEventRow}（9.x 履约质量告警，
   * 字段是 seq / kind / summary / orderId / alertId），两个 record 同在 {@code com.starcon.trade}
   * 一个包里，Java 侧互不冲突，但 **OpenAPI 契约是按简单名拍平的** ——
   * codegen 会让后者覆盖前者**且不报冲突**，被覆盖的 {@code fulfillment-quality-workspace.tsx}
   * 立刻编译失败。已交付模块的类型名不能由本车道重定义。
   */
  public record ReturnQualityEventRow(
      UUID id,
      String eventType,
      String eventTypeLabel,
      @Schema(nullable = true) String fromStatus,
      String toStatus,
      @Schema(nullable = true) String verdict,
      @Schema(nullable = true) BigDecimal salvageAmount,
      String reasonNote,
      UUID occurredBy,
      OffsetDateTime occurredAt) {}

  /** 质检单详情。 */
  public record QualityInspectionDetail(
      QualityInspectionRow head,
      List<QualityCheckResultRow> results,
      @Schema(nullable = true) QualityDispositionInstruction instruction,
      List<ReturnQualityEventRow> events,
      List<QualityTemplateItemRow> pendingItems,
      int mandatoryMissingCount,
      List<QualityVerdictOption> verdictOptions,
      QualityVerdictPath requiredPath,
      String requiredPathLabel,
      List<QualityAction> actions,
      OffsetDateTime evaluatedAt,
      String note) {}

  /** 质检单列表页。 */
  public record QualityInspectionPage(
      List<QualityInspectionRow> rows,
      int page,
      int size,
      long total,
      QualityInspectionSummary summary,
      OffsetDateTime evaluatedAt,
      String paginationNote,
      String salvageNote,
      String verdictNote,
      String sortNote) {}

  /** 质检单页汇总（跨全表算，不随分页变小）。 */
  public record QualityInspectionSummary(
      long total,
      long draft,
      long concluded,
      long cancelled,
      long resalable,
      long defective,
      long scrapped,
      BigDecimal originalValue,
      BigDecimal salvageAmount,
      long awaitingDisposition,
      long pendingQueue) {}

  /**
   * 待检队列行：已入库分录里**还没有**在途质检单的那些。
   *
   * <p>⚠️ 这是质检员上班第一屏要看的表：不是「一共入了多少」，而是
   * <b>「现在有多少件货等着我判」</b>。已判定的分录不再命中（队列天然推进）。
   */
  public record QualityPendingRow(
      UUID postingId,
      UUID receiptId,
      String receiptNo,
      UUID receiptLineId,
      @Schema(nullable = true) UUID skuId,
      String skuCode,
      String skuName,
      String batchNo,
      BigDecimal pendingQty,
      String packagingCondition,
      String packagingConditionLabel,
      String goodsCondition,
      String goodsConditionLabel,
      @Schema(nullable = true) UUID categoryId,
      @Schema(nullable = true) String categoryCode,
      @Schema(nullable = true) UUID templateId,
      @Schema(nullable = true) String templateName,
      boolean hasTemplate,
      OffsetDateTime postedAt) {}

  /** 待检队列页。 */
  public record QualityPendingPage(
      List<QualityPendingRow> rows, int page, int size, long total, OffsetDateTime evaluatedAt,
      String paginationNote, String note) {}

  // ================================================================ 能力声明

  /** 能力区块。 */
  public record QualitySectionCapability(
      String section, String label, boolean available, @Schema(nullable = true) String ownerModule,
      @Schema(nullable = true) String reason, @Schema(nullable = true) String landsIn) {}

  /** 关联模块可用性。未交付的一律 href=null —— 一个点进去 404 的链接比没有链接更糟。 */
  public record QualityRelatedModule(
      String id, String label, boolean delivered, String ownerModule, String reason,
      @Schema(nullable = true) String href) {}

  /** 操作入口。allowed=false 时必须带 blockingReason。 */
  public record QualityAction(
      String code, String label, boolean allowed, @Schema(nullable = true) String method,
      @Schema(nullable = true) String path, boolean required,
      @Schema(nullable = true) String precondition, @Schema(nullable = true) String blockingReason,
      @Schema(nullable = true) String ownerModule, @Schema(nullable = true) String unavailableReason) {}

  /** 本模块<b>刻意不做</b>的事。 */
  public record QualityBoundary(String boundary, String ownerModule, String reason) {}

  /** 能力声明。 */
  public record ReturnQualityCapabilities(
      List<String> dataSources,
      List<QualitySectionCapability> sections,
      List<QualityRelatedModule> relatedModules,
      List<QualityAction> actions,
      List<QualityBoundary> boundaries,
      String stateMachineNote,
      String verdictRuleNote,
      String salvageNote,
      String dispositionNote,
      String templateNote) {}
}
