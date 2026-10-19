package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 11.1.2.2 商品问题归因的传输模型。
 *
 * <p>蓝图 11-aftersales-and-returns.md 原文：
 *
 * <blockquote>
 * 职责：把质量问题<b>归因到具体环节</b>（生产、运输、仓储、描述），支持<b>批次追溯</b>与<b>照片比对</b>，
 * 形成对供应商或流程的<b>改进建议</b>。
 * 输入：售后原因、批次信息、退回实物照片、质检结论。
 * 输出：<b>归因结论 + 批次质量标记</b>。
 * 关系：上游 = 7.3.1.1 批次、11.2.3.1；下游 = 6.4.2.2 索赔、6.1.1.3 评级。
 * <b>批次归因是「同一个批次退回来 10 件」和「10 个批次各退 1 件」的区别</b>，
 * 前者是供应商责任，后者可能是运输或买家因素。
 * </blockquote>
 *
 * <p><b>本文件最重要的三件事</b>：
 *
 * <ol>
 *   <li><b>环节是六值枚举，不是自由文本</b>（硬规则 1）。{@link AttributionStage} 的四个取值
 *       逐字对应职责句点名的「生产、运输、仓储、描述」，另加
 *       {@link AttributionStage#BUYER_FACTOR} 与 {@link AttributionStage#UNDETERMINED}
 *       两个<b>诚实出口</b>：「责任待定」是一个必须能被如实记下来的结论，
 *       把它逼成四选一等于逼运营编一个环节出来，而编出来的环节会直接流向下游索赔。</li>
 *   <li><b>照片比对落的是内容指纹，不是图像相似度</b>（硬规则 2）。{@link PhotoCompareResult}
 *       只有 {@link PhotoCompareResult#IDENTITY_SAME} / {@link PhotoCompareResult#IDENTITY_DIFFER}
 *       两个取值，<b>刻意没有 0.87 这种「相似度」</b> —— 本仓没有图像库、没有训练好的模型、
 *       也没有第三方视觉服务的接入点，编一个相似度数字就是在冒充图像识别能力。
 *       {@link PhotoCompareBasis} 恒为 {@code CONTENT_FINGERPRINT}，
 *       把「我们到底比了什么」写在数据里而不是只写在文档里。</li>
 *   <li><b>批次质量标记的统计列传输层不接受任何取值</b>（硬规则 3）。{@link LotFlagCreate}
 *       里<b>没有</b> suspectReturnCount / affectedReturnQty / stageBreakdown ——
 *       这三列由 DB 触发器按当前生效归因重算（V180 守卫⑤），
 *       服务层聚合后写入、再由触发器<b>独立重算比对</b>，不等就拒。</li>
 * </ol>
 *
 * <p><b>命名。</b>统一用 {@code ProductIssueAttribution*} / {@code AttributionStage} 等前缀，
 * 与 {@code ReturnQuality*}、{@code AftersalesReason*} 零重叠 ——
 * 契约被 codegen 合并进一个扁平文件、同名后者覆盖前者且不报冲突。
 */
public final class ProductIssueAttributionModels {

  private ProductIssueAttributionModels() {}

  // ================================================================ 枚举

  /**
   * 归因环节（职责句点名的四个 + 两个诚实出口）。
   *
   * <p>⚠️ <b>四个环节不可改</b>：它们是下游 6.4.2.2 索赔与 6.1.1.3 评级唯一对齐的锚点，
   * 改一个取值会让全部历史归因被重新归到另一个责任方。
   */
  public enum AttributionStage {
    PRODUCTION("生产"),
    TRANSPORT("运输"),
    WAREHOUSE("仓储"),
    DESCRIPTION("描述"),
    BUYER_FACTOR("买家因素"),
    UNDETERMINED("待定");

    private final String label;

    AttributionStage(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /**
     * 生产责任**必须挂批次**（V180 守卫②的表级 CHECK）。
     *
     * <p>「生产责任」的最小单位是批次 —— 没有批次就指不到某一次投料，
     * 向供应商索赔时根本说不出「哪一批」。
     */
    public boolean requiresLot() {
      return this == PRODUCTION;
    }

    /** 待定不是一个失败态：它是一个<b>合法</b>的结论，理由照写不误。 */
    public boolean honest() {
      return this == UNDETERMINED || this == BUYER_FACTOR;
    }
  }

  /** 归因单状态机。归因结论终态不可逆：判错了改判留痕，不就地改数。 */
  public enum AttributionStatus {
    DRAFT("草稿"),
    ATTRIBUTED("已归因"),
    SUPERSEDED("已被取代");

    private final String label;

    AttributionStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 只有草稿可录照片 / 比对。 */
    public boolean editable() {
      return this == DRAFT;
    }
  }

  /** 照片角色。决定它能否支撑某个环节的归因（比对时按角色取图）。 */
  public enum PhotoRole {
    PRODUCT_OVERVIEW("整体外观"),
    DEFECT_CLOSEUP("缺陷特写"),
    PACKAGING("外包装"),
    LABEL_BATCH("标签批次"),
    OTHER("其它");

    private final String label;

    PhotoRole(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 照片比对结果（硬规则 2）。
   *
   * <p>⚠️ <b>只有两种取值，刻意没有相似度数字。</b>
   * 见类注释：比的是 sha256 内容指纹，语义级的「是不是同一处缺陷」由人工在归因说明里写。
   */
  public enum PhotoCompareResult {
    IDENTITY_SAME("内容指纹一致"),
    IDENTITY_DIFFER("内容指纹不一致");

    private final String label;

    PhotoCompareResult(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 比对口径。恒为 {@code CONTENT_FINGERPRINT}；未来真接入图像服务时可新增取值而不让历史语义漂移。 */
  public enum PhotoCompareBasis {
    CONTENT_FINGERPRINT("内容指纹（sha256）");

    private final String label;

    PhotoCompareBasis(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 批次质量标记状态。SUSPECT 要求至少 {@code minFlagQty} **件**（默认 3）当前生效归因。 */
  public enum LotFlagStatus {
    NONE("无标记"),
    SUSPECT("可疑批次"),
    CONFIRMED("已确认");

    private final String label;

    LotFlagStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 留痕事件类型。追加式，运行角色对 event 表的 UPDATE/DELETE 已在 DB 层收回。 */
  public enum AttributionEventType {
    CREATED("已建单"),
    PHOTO_REGISTERED("已登记照片"),
    PHOTO_COMPARED("已比对照片"),
    ATTRIBUTED("已归因"),
    SUPERSEDED("已改判"),
    LOT_FLAGGED("已刷新批次标记");

    private final String label;

    AttributionEventType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 传输模型

  /**
   * 归因单列表行。
   *
   * <p>⚠️ 带 {@code improvementSuggestion}：它是蓝图职责句明文要求的输出
   * （「形成对供应商或流程的改进建议」），列在列表行里是为了详情页不必再发一次请求 ——
   * 而一个需要额外请求才能看到的关键输出，通常也就会被忘掉看。
   */
  public record AttributionRow(
      UUID id,
      String attributionNo,
      UUID caseId,
      @Schema(nullable = true) UUID inspectionId,
      @Schema(nullable = true) UUID lotId,
      String lotCode,
      @Schema(nullable = true) LocalDate lotProducedOn,
      String skuCode,
      String skuName,
      BigDecimal attributedQty,
      String status,
      String statusLabel,
      @Schema(nullable = true) String attributedStage,
      @Schema(nullable = true) String stageLabel,
      String attributionNote,
      String improvementSuggestion,
      int photoCount,
      int comparisonCount,
      OffsetDateTime createdAt,
      @Schema(nullable = true) OffsetDateTime attributedAt) {}

  /** 归因单列表页。 */
  public record AttributionPage(
      List<AttributionRow> rows,
      int page,
      int size,
      long total,
      AttributionSummary summary,
      OffsetDateTime evaluatedAt,
      String paginationNote,
      String sortNote,
      String stageNote) {}

  /** 归因单页汇总（跨全表算，不随分页变小）。 */
  public record AttributionSummary(
      long total,
      long draft,
      long attributed,
      long superseded,
      long production,
      long transport,
      long warehouse,
      long description,
      long buyerFactor,
      long undetermined,
      BigDecimal attributedQty,
      long withLot,
      long withoutLot,
      long flaggedLots) {}

  /** 归因单详情：头 + 照片 + 比对 + 批次标记 + 留痕 + 当前可执行动作。 */
  public record AttributionDetail(
      AttributionRow row,
      List<PhotoRow> photos,
      List<PhotoComparisonRow> comparisons,
      List<LotFlagRow> lotFlags,
      List<AttributionEventRow> events,
      List<AttributionAction> actions,
      String photoBasisNote,
      String crossServiceNote) {}

  // -------------------------------------------------------------- 照片

  /** 归因单下登记的照片（对象键 + 内容指纹 + 字节数，**不存二进制大对象**）。 */
  public record PhotoRow(
      UUID id,
      UUID attributionId,
      @Schema(nullable = true) UUID sourcePhotoId,
      String photoRole,
      String photoRoleLabel,
      String objectUri,
      String contentSha256,
      long contentBytes,
      String contentType,
      OffsetDateTime capturedAt,
      OffsetDateTime registeredAt,
      String caption,
      String registeredBy) {}

  /** 照片比对记录：内容指纹一致 / 不一致。 */
  public record PhotoComparisonRow(
      UUID id,
      UUID attributionId,
      UUID leftPhotoId,
      UUID rightPhotoId,
      String leftSha256,
      String rightSha256,
      String compareResult,
      String compareResultLabel,
      String compareBasis,
      String comparisonNote,
      UUID comparedBy,
      OffsetDateTime comparedAt) {}

  // -------------------------------------------------------------- 批次标记

  /** 批次质量标记行（输出句第二样）。统计列由 DB 触发器重算，不接受传入。 */
  public record LotFlagRow(
      UUID id,
      UUID lotId,
      String lotCode,
      @Schema(nullable = true) LocalDate lotProducedOn,
      @Schema(nullable = true) UUID lotSupplierRefId,
      @Schema(nullable = true) UUID skuId,
      String skuCode,
      int suspectReturnCount,
      BigDecimal affectedReturnQty,
      StageBreakdown stageBreakdown,
      String flagStatus,
      String flagStatusLabel,
      BigDecimal minFlagQty,
      String flagNote,
      OffsetDateTime recomputedAt,
      String flaggedBy) {}

  /**
   * 按环节拆分的件数分布。
   *
   * <p>⚠️ <b>六个环节全部出现</b>，没有命中的也是 0 —— 少列一个环节，
   * 界面就显示成空白，而空白会被读成「没有这个数据」而不是「真的是 0」。
   */
  public record StageBreakdown(
      int production, int transport, int warehouse, int description, int buyerFactor, int undetermined) {

    /** 全环节合计 = 这个批次一共被归因出多少件。 */
    public int total() {
      return production + transport + warehouse + description + buyerFactor + undetermined;
    }

    /**
     * 主导环节（件数最多的那个）。
     *
     * <p>⚠️ 平票时**不**随手取第一个 —— 那样同一批数据在不同机器上可能报出不同的主导环节。
     * 平票返回 {@code null} 并在界面上显示「责任分散」，因为
     * 「一半生产一半运输」本身就是一个需要人看的结论，而不是一个可以随便挑的答案。
     */
    public AttributionStage dominant() {
      int max = 0;
      AttributionStage winner = null;
      boolean tie = false;
      for (var stage : AttributionStage.values()) {
        int count = countOf(stage);
        if (count > max) {
          max = count;
          winner = stage;
          tie = false;
        } else if (count == max && count > 0) {
          tie = true;
        }
      }
      return max == 0 || tie ? null : winner;
    }

    private int countOf(AttributionStage stage) {
      return switch (stage) {
        case PRODUCTION -> production;
        case TRANSPORT -> transport;
        case WAREHOUSE -> warehouse;
        case DESCRIPTION -> description;
        case BUYER_FACTOR -> buyerFactor;
        case UNDETERMINED -> undetermined;
      };
    }
  }

  /** 批次质量标记列表页。 */
  public record LotFlagPage(
      List<LotFlagRow> rows,
      int page,
      int size,
      long total,
      LotFlagSummary summary,
      OffsetDateTime evaluatedAt,
      String thresholdNote,
      String ownershipNote) {}

  /** 批次标记页汇总。 */
  public record LotFlagSummary(
      long total, long none, long suspect, long confirmed, BigDecimal minFlagQty, long suppliers) {}

  // -------------------------------------------------------------- 留痕

  /** 归因留痕事件（追加式）。 */
  public record AttributionEventRow(
      UUID id,
      UUID attributionId,
      String eventType,
      String eventTypeLabel,
      String eventNote,
      UUID occurredBy,
      OffsetDateTime occurredAt) {}

  // -------------------------------------------------------------- 请求模型

  /**
   * 建归因单。
   *
   * <p>⚠️ <b>不接受</b> {@code status} / {@code attributedStage} / {@code attributedAt}：
   * 建单恒为 DRAFT（草稿），归因只走 {@link #attributed} 一个入口，
   * 状态流转在服务层与 DB 触发器各判一次。
   */
  public record AttributionCreate(
      UUID caseId,
      @Schema(nullable = true) UUID inspectionId,
      @Schema(nullable = true) UUID reasonSetId,
      @Schema(nullable = true) UUID lotId,
      @Schema(nullable = true) String lotCode,
      @Schema(nullable = true) LocalDate lotProducedOn,
      @Schema(nullable = true) UUID lotSupplierRefId,
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) String skuCode,
      @Schema(nullable = true) String skuName,
      BigDecimal attributedQty) {}

  /**
   * 归因（唯一的状态流转入口）。
   *
   * <p><b>必须</b>给环节 + 理由 + 改进建议（职责句明文要求「形成对供应商或流程的改进建议」，
   * DB 层 CHECK 会拒掉缺任一项的归因）。
   */
  public record AttributionConclude(
      String attributedStage,
      String attributionNote,
      String improvementSuggestion) {}

  /** 改判（终态不可逆，只能留痕另起一版）。 */
  public record AttributionSupersede(String reason, String attributedStage, String attributionNote, String improvementSuggestion) {}

  /** 登记照片（整单替换）。 */
  public record PhotoRegister(
      @Schema(nullable = true) UUID sourcePhotoId,
      String photoRole,
      String objectUri,
      String contentSha256,
      long contentBytes,
      @Schema(nullable = true) String contentType,
      OffsetDateTime capturedAt,
      @Schema(nullable = true) String caption) {}

  /** 照片整单替换。 */
  public record PhotoRegisterRequest(List<PhotoRegister> photos) {}

  /**
   * 照片比对。
   *
   * <p>⚠️ <b>不接受调用方指定 {@code compareResult}</b>：结果由服务层按两张照片的
   * sha256 真相比对得出，再由 DB 触发器<b>独立比对一次</b>（V180 比对守卫）——
   * 「指纹明明不同却写 SAME」在任何路径下都存不进去。
   */
  public record PhotoCompareRequest(UUID leftPhotoId, UUID rightPhotoId, @Schema(nullable = true) String comparisonNote) {}

  /**
   * 刷新批次质量标记。
   *
   * <p>⚠️ <b>不接受</b> suspectReturnCount / affectedReturnQty / stageBreakdown ——
   * 这三列是统计结论，由服务层聚合 + DB 触发器重算比对（V180 守卫⑤，传输层根本收不到）。
   */
  public record LotFlagRefresh(@Schema(nullable = true) String flagNote, @Schema(nullable = true) String flagStatus) {}

  // -------------------------------------------------------------- 能力声明

  /** 能力区块。未交付的一律 available=false 并报出归属模块。 */
  public record AttributionSectionCapability(
      String section, String label, boolean available, @Schema(nullable = true) String ownerModule,
      @Schema(nullable = true) String reason, @Schema(nullable = true) String landsIn) {}

  /** 关联模块可用性。未交付的一律 href=null —— 一个点进去 404 的链接比没有链接更糟。 */
  public record AttributionRelatedModule(
      String id, String label, boolean delivered, String ownerModule, String reason,
      @Schema(nullable = true) String href) {}

  /** 操作入口。allowed=false 时必须带 blockingReason。 */
  public record AttributionAction(
      String code, String label, boolean allowed, @Schema(nullable = true) String method,
      @Schema(nullable = true) String path, boolean required,
      @Schema(nullable = true) String precondition, @Schema(nullable = true) String blockingReason,
      @Schema(nullable = true) String ownerModule, @Schema(nullable = true) String unavailableReason) {}

  /** 本模块<b>刻意不做</b>的事。 */
  public record AttributionBoundary(String boundary, String ownerModule, String reason) {}

  /** 能力声明。 */
  public record ProductAttributionCapabilities(
      List<String> dataSources,
      List<AttributionSectionCapability> sections,
      List<AttributionRelatedModule> relatedModules,
      List<AttributionAction> actions,
      List<AttributionBoundary> boundaries,
      String stateMachineNote,
      String stageRuleNote,
      String photoBasisNote,
      String lotFlagNote,
      String crossServiceNote) {}
}
