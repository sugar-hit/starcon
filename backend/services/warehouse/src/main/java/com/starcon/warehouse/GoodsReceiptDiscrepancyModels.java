package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 8.2.1.2 收货差异（**处理**）。
 *
 * <p>蓝图 08-warehouse-operations.md 8.2.1.2 的输入/输出契约：
 *
 * <ul>
 *   <li>职责：处理收货差异：短少、破损、型号不符、过期、错发；支持拍照留证、拒收、部分接收、索赔发起。
 *   <li>输入：差异明细；处理决定；凭证。
 *   <li>输出：差异处理记录 + 索赔单（6 采购）+ 库存拒收不入账记录。
 * </ul>
 *
 * <p>DTO 一律带 {@code Discrepancy} 前缀：四个服务的 schema 被 codegen 拍平进同一命名空间， 重名会被**静默覆盖且没有任何警告**（AGENTS.md
 * 契约纪律）。
 *
 * <p>界面上最容易做错的一件事，这个模块在 DTO 层面就把它写死：{@code disposition} 与 {@code acceptedQuantity} / {@code
 * rejectedQuantity} 是**同一份决定的两面** —— 接受量与拒收量之和恒等于差异数量（DB 触发器钉死），所以「这批货到底哪部分留下、哪部分赔掉」
 * 永远能从一行里算出来，而不是靠人去回忆当初点的是哪个按钮。
 */
public final class GoodsReceiptDiscrepancyModels {
  private GoodsReceiptDiscrepancyModels() {}

  /**
   * 处理状态词表。与 {@code goods_receipt_discrepancy_handling.status} 的 CHECK 逐值对应。
   *
   * <p>合法转换只有这一张表（{@link #ALLOWED} 是唯一真相，服务层与前端共用）： {@code PENDING → DECIDED}、{@code DECIDED →
   * CLAIMED / CLOSED}、 {@code CLAIMED → CLOSED}、{@code CLOSED} 为终态。
   *
   * <p>刻意**没有**「处理中」这一态：它唯一的存在理由是「等 6.4.2.1 质检结论」， 而质检单尚未交付 —— 一个没有人能推进它的状态只会让待办列表永远挂着假待办。
   * 现场拍照留证在待处理状态下随时可做，不必先切一个中间态。
   *
   * <p>{@code DECIDED} **不能改判**（{@code DECIDED → DECIDED} 不在表内）：改判会让已经发生的
   * 库存分录没法用「一次改判」讲清楚。要改判请另登记一条差异 —— 改写历史不如追加事实， 这与 8.2.1.1「预期与实收分开记录」是同一条纪律。
   */
  public enum HandlingStatus {
    /** 待处理：已接手，尚未决定。 */
    PENDING("待处理"),
    /** 已决定：处理决定已给出。 */
    DECIDED("已决定"),
    /** 已索赔：索赔已发起，等待 6.5.1 对账索赔受理。 */
    CLAIMED("已索赔"),
    /** 已关闭：终态。 */
    CLOSED("已关闭");

    private final String label;

    HandlingStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static HandlingStatus parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException("未知的差异处理状态：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }

    /** 唯一真相：合法转换表。任何不在表内的跳转都必须被拒绝。 */
    public static List<HandlingStatus> allowedNext(HandlingStatus current) {
      return switch (current) {
        case PENDING -> List.of(DECIDED);
        case DECIDED -> List.of(CLAIMED, CLOSED);
        case CLAIMED -> List.of(CLOSED);
        case CLOSED -> List.of();
      };
    }
  }

  /**
   * 处理决定词表。
   *
   * <p>蓝图硬规则：「**破损与短少必须拒收或待检入库**，直接入可售会把问题货卖出去。」 因此 {@code ACCEPT}（正常入库）**只对超收与错发开放**；破损 / 短少 /
   * 型号不符 / 过期 一律只能拒收、部分接收或转待检。服务层与 DB 触发器各拦一半，改决定绕不过前者， 直接写库存流转绕不过后者。
   */
  public enum HandlingDisposition {
    /** 拒收：全部不要。货已入库则撤出，从未入库则只留「拒收不入账」痕迹。 */
    REJECT("拒收"),
    /** 部分接收：一部分入库（去向质量状态必填），其余拒收。 */
    PARTIAL_ACCEPT("部分接收"),
    /** 待检入库：全部转入待检或残次，等 6.4.2.1 质检结论。 */
    HOLD_FOR_INSPECTION("待检入库"),
    /** 正常入库：按给定质量状态入可售。**只对超收 / 错发开放**。 */
    ACCEPT("正常入库");

    private final String label;

    HandlingDisposition(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static HandlingDisposition parse(String raw) {
      for (var d : values()) {
        if (d.name().equalsIgnoreCase(raw)) {
          return d;
        }
      }
      throw new IllegalArgumentException("未知的处理决定：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /**
   * 哪些差异类型**禁止**按「正常入库」处理。
   *
   * <p>蓝图点名了破损与短少；这里把型号不符与过期一并禁掉 —— 「型号不符」意味着这箱货不是 买家要的那个型号，「过期」意味着它根本不该再卖。四类里只有超收与错发是
   * 「货本身没问题、只是单据对不上」，那两类才允许正常入库。
   *
   * <p>与迁移 V78 的 {@code guard_discrepancy_disposition} 逐值对应。
   */
  public static final List<String> RISK_DISCREPANCY_TYPES =
      List.of("DAMAGED", "SHORT_RECEIPT", "WRONG_ITEM", "EXPIRED");

  /** 索赔类型。与差异类型同源，但口径不同：差异说的是「现场发现了什么」，索赔说的是「向谁追什么」。 */
  public enum ClaimType {
    /** 短少索赔。 */
    SHORTAGE("短少"),
    /** 货损索赔。 */
    DAMAGE("货损"),
    /** 型号不符索赔。 */
    WRONG_ITEM("型号不符"),
    /** 过期索赔。 */
    EXPIRED("过期"),
    /** 错发索赔。 */
    MISDELIVERY("错发"),
    /** 超收索赔（多发部分要退回去）。 */
    OVER_RECEIPT("超收");

    private final String label;

    ClaimType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static ClaimType parse(String raw) {
      for (var t : values()) {
        if (t.name().equalsIgnoreCase(raw)) {
          return t;
        }
      }
      throw new IllegalArgumentException("未知的索赔类型：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /**
   * 索赔状态词表。
   *
   * <p>只到 {@code SUBMITTED} 为止：{@code ACKNOWLEDGED} / {@code SETTLED} 归 6.5.1 对账索赔拥有。 {@code
   * WITHDRAWN} 是 warehouse 自己的终态 —— 发起方在对方受理前撤回。
   */
  public enum ClaimStatus {
    /** 已发起：已建记录，尚未提交给对方。 */
    INITIATED("已发起"),
    /** 已提交：已推给对方，等待 6.5.1 对账受理。终态，不可撤回。 */
    SUBMITTED("已提交"),
    /** 已撤回：提交前由发起方撤回。终态。 */
    WITHDRAWN("已撤回");

    private final String label;

    ClaimStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static ClaimStatus parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException("未知的索赔状态：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /** 凭证类型词表。现场能立刻拿出来的都是照片，其余是随货到达的凭据。 */
  public enum EvidenceType {
    /** 照片：货损 / 短少现场留证。 */
    PHOTO("照片"),
    /** 复磅单：短少争议的重量凭据。 */
    WEIGHING("复磅单"),
    /** 质检报告：待检判定结论。 */
    INSPECTION("质检报告"),
    /** 单据：承运异常通知单、供应商回函等。 */
    DOCUMENT("单据");

    private final String label;

    EvidenceType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static EvidenceType parse(String raw) {
      for (var t : values()) {
        if (t.name().equalsIgnoreCase(raw)) {
          return t;
        }
      }
      throw new IllegalArgumentException("未知的凭证类型：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /** 库存流转方向词表。与 {@code goods_receipt_discrepancy_stock_move.direction} 的 CHECK 逐值对应。 */
  public enum StockMoveDirection {
    /** 拒收不入账：这批货从没进过库存。蓝图的「库存拒收不入账记录」就是这一行。 */
    NOT_POSTED("拒收不入账"),
    /**
     * 撤出：从某个质量状态扣掉。两种来源共用这一个方向 —— 拒收撤出、 以及「已入库的货改判待检」时的质量状态流转出。两者在库存上是同一个动作； 具体是拒收还是改判由处理记录上的
     * disposition 表达，不在这里重复一遍。
     */
    WITHDRAW("撤出"),
    /** 转入待检 / 残次。 */
    INBOUND_TO_HOLD("转入待检"),
    /** 正常入库：入可售。风险差异类型禁止此方向。 */
    INBOUND_TO_SELLABLE("正常入库"),
    /**
     * 无需移动：货已经在去向质量状态里（超收确认就是这种 —— 收货时已入可售，现在只是确认）。 单独记一行是为了回答「为什么这条处理没有库存变动」：完全没有痕迹的话，
     * 界面上「库存没动」看起来像处理漏了。
     */
    NO_MOVE("无需移动");

    private final String label;

    StockMoveDirection(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static StockMoveDirection parse(String raw) {
      for (var d : values()) {
        if (d.name().equalsIgnoreCase(raw)) {
          return d;
        }
      }
      throw new IllegalArgumentException("未知的库存流转方向：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /**
   * 下游维度接入情况。
   *
   * <p>沿用仓库既有约定（8.2.1.1 {@code ReceiptSourceCapability}、7.1.1.2 参照数据）： {@code available=false} 时
   * {@code href} 仍然给出 —— 它是**路由契约**而不是死链， 指明这个出口落在哪个模块、上游交付后直接可用；{@code note} 说明当前为什么不可用。
   */
  public record DiscrepancyDependencyCapability(
      String key, String label, boolean available, String ownerModule, String href, String note) {}

  /** 状态机的合法转换，前端据此只给合法的按钮。 */
  public record DiscrepancyHandlingTransition(
      String from, String to, boolean allowed, String note) {}

  public record DiscrepancyHandlingOption(String value, String label, long count) {}

  /** 处理决定选项。{@code riskOnly} 为真表示它是对风险差异类型（破损/短少等）**唯一允许**的两条。 */
  public record DiscrepancyDispositionOption(
      String value,
      String label,
      boolean allowedForRiskTypes,
      String requiresInboundQualityStatus,
      String note) {}

  /**
   * 差异处理能力：五态状态机、四种处理决定、索赔与凭证词表、五条下游出口接入情况， 以及三条必须如实讲清楚的说明（可售硬规则、索赔单归属、拒收不入账口径）。
   *
   * <p>前端据此渲染动作按钮与去向质量状态选择器，而不是把规则硬编码在界面上。
   */
  public record GoodsReceiptDiscrepancyCapabilities(
      List<DiscrepancyHandlingOption> statusOptions,
      List<DiscrepancyHandlingTransition> transitions,
      List<DiscrepancyDispositionOption> dispositionOptions,
      List<String> riskDiscrepancyTypes,
      List<DiscrepancyHandlingOption> claimTypeOptions,
      List<DiscrepancyHandlingOption> evidenceTypeOptions,
      List<DiscrepancyHandlingOption> stockMoveDirectionOptions,
      List<DiscrepancyDependencyCapability> downstream,
      List<DiscrepancyQualityStatusOption> qualityStatusOptions,
      String sellableRuleNote,
      String claimNote,
      String notPostedNote,
      String revisionNote) {}

  /** 可选入库去向质量状态。role 决定它是可售还是待检 / 残次。 */
  public record DiscrepancyQualityStatusOption(UUID id, String code, String name, String role) {}

  /** 列表查询条件。全部对应 SQL 里的真实条件，没有「接了但不生效」的参数。 */
  public record DiscrepancyHandlingQuery(
      UUID receiptId,
      UUID warehouseId,
      List<String> status,
      List<String> disposition,
      List<String> discrepancyType,
      List<String> claimStatus,
      String keyword,
      String sort,
      Integer page,
      Integer size) {}

  /** 处理记录列表行。 */
  public record DiscrepancyHandlingRow(
      UUID id,
      UUID discrepancyId,
      UUID receiptId,
      String receiptCode,
      UUID warehouseId,
      String warehouseCode,
      @Schema(nullable = true) UUID lineId,
      String skuCode,
      String skuName,
      String discrepancyType,
      String discrepancyTypeLabel,
      BigDecimal quantity,
      String status,
      String statusLabel,
      @Schema(nullable = true) String disposition,
      @Schema(nullable = true) String dispositionLabel,
      BigDecimal acceptedQuantity,
      BigDecimal rejectedQuantity,
      String inboundQualityStatusName,
      long evidenceCount,
      long stockMoveCount,
      boolean claimed,
      String claimStatus,
      @Schema(nullable = true) OffsetDateTime decidedAt,
      @Schema(nullable = true) OffsetDateTime closedAt,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 列表页的全量汇总（**不受分页影响**）。 */
  public record DiscrepancyHandlingSummary(
      long handlingCount,
      long pendingCount,
      long decidedCount,
      long claimedCount,
      long closedCount,
      long openCount,
      long riskCount,
      BigDecimal discrepancyQuantity,
      BigDecimal acceptedQuantity,
      BigDecimal rejectedQuantity,
      BigDecimal claimedAmount,
      long evidenceCount,
      long claimCount,
      long unclaimedRiskCount,
      @Schema(nullable = true) OffsetDateTime earliestCreatedAt) {}

  /** 列表页。{@code summary} 是全量汇总，下拉词表随响应一起给。 */
  public record DiscrepancyHandlingPage(
      DiscrepancyHandlingSummary summary,
      List<DiscrepancyHandlingRow> rows,
      List<DiscrepancyHandlingOption> statusOptions,
      List<DiscrepancyDependencyCapability> downstream,
      List<DiscrepancyQualityStatusOption> qualityStatusOptions,
      long total,
      int page,
      int size) {}

  /** 原始差异事实（来自 8.2.1.1，本模块只读）。留证时它就是「在给哪条差异拍照」。 */
  public record DiscrepancyFactRow(
      UUID id,
      UUID lineId,
      String discrepancyType,
      String discrepancyTypeLabel,
      BigDecimal quantity,
      String reasonCode,
      String reasonNote,
      String evidenceNote,
      OffsetDateTime recordedAt,
      UUID actorId) {}

  /**
   * 凭证行。{@code dataUrl} 是 base64 data URI —— 凭证本体真落 PostgreSQL（bytea）， 不是「将来会有的」外链：索赔时拿不出照片等于没留证。
   */
  public record DiscrepancyEvidenceRow(
      UUID id,
      String evidenceType,
      String evidenceTypeLabel,
      String fileName,
      String contentType,
      int byteSize,
      String sha256,
      OffsetDateTime capturedAt,
      UUID uploadedBy,
      String note,
      String dataUrl) {}

  /** 库存流转行：哪批货因为哪条差异被撤出 / 转待检 / 入库。 */
  public record DiscrepancyStockMoveRow(
      UUID id,
      String direction,
      String directionLabel,
      BigDecimal quantity,
      String fromQualityStatusName,
      String toQualityStatusName,
      String locationCode,
      String lotCode,
      @Schema(nullable = true) UUID transactionId,
      OffsetDateTime occurredAt,
      String note) {}

  /** 索赔行。{@code claimRefId} 在 6.5.1 交付前恒为空 —— 这是边界，不是漏填。 */
  public record DiscrepancyClaimRow(
      UUID id,
      String claimCode,
      String claimType,
      String claimTypeLabel,
      BigDecimal claimedQuantity,
      @Schema(nullable = true) BigDecimal claimedAmount,
      @Schema(nullable = true) String currency,
      String status,
      String statusLabel,
      @Schema(nullable = true) UUID sourceRefId,
      String sourceRefCode,
      @Schema(nullable = true) UUID claimRefId,
      String reason,
      @Schema(nullable = true) OffsetDateTime submittedAt,
      @Schema(nullable = true) OffsetDateTime withdrawnAt,
      long version,
      OffsetDateTime createdAt) {}

  /** 处理记录详情：处理记录 + 原始差异事实 + 凭证 + 库存流转 + 索赔 + 当前状态的合法下一步。 */
  public record DiscrepancyHandlingDetail(
      DiscrepancyHandlingRow handling,
      DiscrepancyFactRow discrepancy,
      List<DiscrepancyEvidenceRow> evidence,
      List<DiscrepancyStockMoveRow> stockMoves,
      @Schema(nullable = true) DiscrepancyClaimRow claim,
      List<DiscrepancyHandlingTransition> transitions,
      List<DiscrepancyDispositionOption> allowedDispositions,
      String sellableRuleNote,
      String notPostedNote) {}

  /**
   * 接手一条差异事实，建处理记录。
   *
   * <p>刻意**没有备注字段**：接手是一次状态推进，不是内容录入。给「接手」配一个备注框， 现场会以为它会被记进处理过程，而实际上处理记录上没有一个「接手时写的话」该待的地方 ——
   * 塞进决定说明会撒谎（还没决定），塞进凭证说明会混成一条凭证。 要留话就在留证或处理决定里写。
   */
  public record DiscrepancyHandlingIntakeRequest(UUID discrepancyId) {}

  /**
   * 拍照留证请求。
   *
   * <p>{@code contentBase64} 是 base64 编码的字节（前端在浏览器侧把照片压到长边 ≤1280px 的 JPEG 再上传）。凭证本体落
   * PostgreSQL，单条上限 1.5 MB（DB CHECK 兜底）。
   */
  public record DiscrepancyEvidenceRequest(
      String evidenceType,
      String fileName,
      String contentType,
      String contentBase64,
      @Schema(nullable = true) OffsetDateTime capturedAt,
      String note) {}

  /**
   * 处理决定请求。
   *
   * <p>数量口径：{@code acceptedQuantity} 与 {@code rejectedQuantity} 之和必须**恰好**等于差异数量 （DB
   * 触发器钉死）。四种决定的填法：
   *
   * <ul>
   *   <li>拒收：拒收量留空，服务层按差异数量补满，接受量必须为 0。
   *   <li>部分接收：两个量都必填，且要去向质量状态与落库库位都必填。
   *   <li>待检入库 / 正常入库：接受量留空由服务层按差异数量补满，去向质量状态与落库库位必填。
   * </ul>
   *
   * <p>{@code locationId} 的有无决定拒收那部分的库存痕迹： 给了就是「货已入库、真从库存撤出」，不给就是「这批货从没进过库」，
   * 只留一条拒收不入账记录。两者都如实记录，界面也据实显示。
   */
  public record DiscrepancyDecisionRequest(
      Long expectedVersion,
      String disposition,
      @Schema(nullable = true) BigDecimal acceptedQuantity,
      @Schema(nullable = true) BigDecimal rejectedQuantity,
      @Schema(nullable = true) UUID inboundQualityStatusId,
      @Schema(nullable = true) UUID fromQualityStatusId,
      @Schema(nullable = true) UUID locationId,
      @Schema(nullable = true) UUID lotId,
      String reason,
      String note) {}

  /** 发起索赔。金额可空：供应商还没报价时先把索赔发起，别因为等一个数字而压着货损不报。 */
  public record DiscrepancyClaimRequest(
      Long expectedVersion,
      String claimType,
      @Schema(nullable = true) BigDecimal claimedQuantity,
      @Schema(nullable = true) BigDecimal claimedAmount,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) UUID sourceRefId,
      String sourceRefCode,
      String reason) {}

  /** 提交索赔给对方。已提交不可撤回。 */
  public record DiscrepancyClaimSubmitRequest(Long expectedVersion) {}

  /** 提交前撤回索赔。 */
  public record DiscrepancyClaimWithdrawRequest(Long expectedVersion, String reason) {}

  /** 关闭处理。要求已决定，且必须有凭证 / 库存流转 / 索赔三者之一（DB 触发器钉死）。 */
  public record DiscrepancyHandlingCloseRequest(Long expectedVersion, String reason) {}

  /** 每个写动作后的回读结果（响应是「库里真实存了什么」）。 */
  public record DiscrepancyHandlingSaved(
      UUID id,
      String status,
      String statusLabel,
      @Schema(nullable = true) String disposition,
      @Schema(nullable = true) String dispositionLabel,
      BigDecimal acceptedQuantity,
      BigDecimal rejectedQuantity,
      long evidenceCount,
      boolean claimed,
      @Schema(nullable = true) String claimStatus,
      long version,
      @Schema(nullable = true) OffsetDateTime decidedAt,
      @Schema(nullable = true) OffsetDateTime closedAt) {}
}
