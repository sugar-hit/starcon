package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 6.1.2.3 供应商资质的传输模型。
 *
 * <p>蓝图原话：「登记与跟踪供应商资质：营业执照、质检报告、认证证书（CE/ROHS/ISO 等）、授权书，
 * 明确有效期与即将过期提醒。品牌授权需关联 03 品牌。」
 *
 * <p><b>五条建模纪律</b>：
 *
 * <ul>
 *   <li><b>资质类型是受控词表，不是自由文本</b>：{@link QualificationType} 逐字对齐 DB 的 CHECK，
 *       且与 6.1.1.2 附件资质的类型闭集**同源**（重名的取值共用同一个码）——
 *       两套词汇会让同一个词在同一个界面上对不上号。
 *   <li><b>审核状态是一个状态机</b>：{@link QualificationReviewStatus} × {@link QualificationReviewAction}，
 *       允许的转换由 DB 触发器钉死（见 V239 第七节），枚举里能看出「谁可以变谁」。
 *   <li><b>到期状态不落库</b>：{@link QualificationExpiryState} 是**读出口按数据库时钟现算**的
 *       （存一列就要一个每日任务去维护它，而维护出来的那天与读出口算出的那天差一天时，
 *       「这张证到底过期没有」会答错 —— 而这个答案直接决定能不能刊登、能不能发货）。
 *   <li><b>金额与数量全程 BigDecimal</b>，禁浮点；文件大小是 numeric(19,6)。
 *   <li><b>「即将过期」与「已过期」是两个不同的状态</b>，且**阻断**与提醒是两件事：
 *       提醒是一条可确认的记录，阻断是给下游（04 刊登 / 发货）的判定结论。
 * </ul>
 */
public final class SupplierQualificationModels {

  private SupplierQualificationModels() {}

  // ------------------------------------------------------------ 枚举

  /**
   * 资质类型（封闭词表，只增不改）。
   *
   * <p>取值与 6.1.1.2 的附件资质闭集同源（BUSINESS_LICENSE / ISO9001 / ISO14001 / CE / FDA /
   * COC / OTHER 一字不改），并上蓝图 6.1.2.3 点名的三个：ROHS、质检报告、授权书。
   *
   * <p>加类型 = 改 V239 的 CHECK + 加这里的枚举值 + 一条新迁移放开 CHECK，**不是**插一行数据。
   */
  public enum QualificationType {
    BUSINESS_LICENSE("营业执照", "跨境必备；缺它建单与清关都会被卡"),
    QUALITY_INSPECTION_REPORT("质检报告", "来料 / 出货质检依据，8 收货据此判定"),
    CE("CE 认证", "欧盟市场准入"),
    ROHS("RoHS 认证", "欧盟有害物质限制"),
    ISO9001("ISO9001 体系认证", "质量管理体系，多数类目准入要求"),
    ISO14001("ISO14001 体系认证", "环境管理体系，部分平台与买家要求"),
    FDA("FDA 注册", "美国食品接触 / 医疗类目准入"),
    COC("产地证", "清关与原产地优惠"),
    AUTHORIZATION_LETTER("授权书", "品牌授权：必须关联 03 品牌"),
    OTHER("其它资质", "有 title 兜底；不建自由文本类型");

    private final String label;
    private final String hint;

    QualificationType(String label, String hint) {
      this.label = label;
      this.hint = hint;
    }

    public String label() {
      return label;
    }

    public String hint() {
      return hint;
    }
  }

  /**
   * 审核状态（受控枚举）。
   *
   * <p>允许的转换（V239 第七节的触发器是同一张图的真身）：
   *
   * <pre>
   * DRAFT ──SUBMIT──▶ PENDING ──APPROVE──▶ APPROVED ──REVOKE──▶ REVOKED
   *   ▲                   │                                       │
   *   │                   └──REJECT──▶ REJECTED ◀──REJECT──────────┘
   *   │                                    │                          │
   *   └──────────────RESUBMIT──────────────┴────────RESUBMIT──────────┘
   * </pre>
   */
  public enum QualificationReviewStatus {
    DRAFT("草稿", "已登记，尚未提交审核"),
    PENDING("待审核", "已提交，等待审核结论"),
    APPROVED("已通过", "审核通过：当前有效的资质"),
    REJECTED("已驳回", "审核未通过：修订后可重新提交"),
    REVOKED("已撤销", "曾通过、后被撤销：不再有效（行仍在，是审计事实）");

    private final String label;
    private final String hint;

    QualificationReviewStatus(String label, String hint) {
      this.label = label;
      this.hint = hint;
    }

    public String label() {
      return label;
    }

    public String hint() {
      return hint;
    }
  }

  /** 审核动作（封闭词表）。每个动作对应状态机上一条唯一的边。 */
  public enum QualificationReviewAction {
    SUBMIT("提交审核", "DRAFT → PENDING"),
    APPROVE("审核通过", "PENDING → APPROVED"),
    REJECT("驳回", "PENDING → REJECTED"),
    REVOKE("撤销", "APPROVED → REVOKED"),
    RESUBMIT("重新提交", "REJECTED / REVOKED → PENDING");

    private final String label;
    private final String hint;

    QualificationReviewAction(String label, String hint) {
      this.label = label;
      this.hint = hint;
    }

    public String label() {
      return label;
    }

    public String hint() {
      return hint;
    }
  }

  /**
   * 到期状态（**不落库**，按数据库时钟现算）。
   *
   * <p>{@code NOT_EFFECTIVE} 与 {@code EXPIRING} 是两件不同的事：一张还没生效的证与一张快过期的证，
   * 运营要做的动作完全不同（等它生效 vs 赶紧续期）。
   */
  public enum QualificationExpiryState {
    NOT_EFFECTIVE("未生效", "还没到有效期起点"),
    VALID("有效", "在有效期内，且不在提醒窗口内"),
    EXPIRING("即将过期", "仍在有效期内，但落在提醒窗口内"),
    EXPIRED("已过期", "已过有效期终点：阻断");

    private final String label;
    private final String hint;

    QualificationExpiryState(String label, String hint) {
      this.label = label;
      this.hint = hint;
    }

    public String label() {
      return label;
    }

    public String hint() {
      return hint;
    }
  }

  /** 资质文件类别（封闭词表）。 */
  public enum QualificationDocumentKind {
    ORIGINAL("原件", "首次登记时上传的原件"),
    RENEWAL("续期件", "续期上传的新件（旧件保留，是「当时交的是什么」的凭据）"),
    ATTACHMENT("补充材料", "补充说明材料");

    private final String label;
    private final String hint;

    QualificationDocumentKind(String label, String hint) {
      this.label = label;
      this.hint = hint;
    }

    public String label() {
      return label;
    }

    public String hint() {
      return hint;
    }
  }

  /** 提醒类别（封闭词表）。 */
  public enum QualificationReminderKind {
    EXPIRING("即将过期", "仍在有效期内，落在提醒窗口内"),
    EXPIRED("已过期", "已过有效期终点");

    private final String label;
    private final String hint;

    QualificationReminderKind(String label, String hint) {
      this.label = label;
      this.hint = hint;
    }

    public String label() {
      return label;
    }

    public String hint() {
      return hint;
    }
  }

  /** 提醒处理状态（封闭词表）。 */
  public enum QualificationReminderStatus {
    OPEN("待处理", "已生成，还没人处理"),
    ACKNOWLEDGED("已确认", "有人看到了，处理中"),
    RESOLVED("已处理", "已续期 / 已撤销 / 已关闭");

    private final String label;
    private final String hint;

    QualificationReminderStatus(String label, String hint) {
      this.label = label;
      this.hint = hint;
    }

    public String label() {
      return label;
    }

    public String hint() {
      return hint;
    }
  }

  // ------------------------------------------------------------ 词表 / 选项

  /** 一个词表选项（值 / 中文名 / 提示）。 */
  public record QualificationOption(String value, String label, String hint) {}

  /** 一个可排序键。 */
  public record QualificationSortOption(String key, String label, boolean defaultDesc) {}

  /** 词表选项：前端下拉的数据源，与后端同源。 */
  public record SupplierQualificationOptions(
      List<QualificationOption> qualificationTypes,
      List<QualificationOption> reviewStatuses,
      List<QualificationOption> expiryStates,
      List<QualificationOption> reminderKinds,
      List<QualificationOption> reminderStatuses,
      List<QualificationSortOption> sorts,
      /** 当前租户的「即将过期」窗口（天）。 */
      int warningDays,
      /** 当前租户要求的必备资质类型。 */
      List<String> requiredTypes,
      String note) {}

  // ------------------------------------------------------------ 资质档案

  /**
   * 一份资质档案（列表行 / 详情头）。
   *
   * <p>{@code expiryState} 与 {@code daysToExpiry} 由读出口按数据库时钟现算：
   * {@code daysToExpiry} 为负表示**已过期 N 天**（界面照实显示，不粉饰成 0）。
   */
  public record QualificationRow(
      UUID id,
      String code,
      /** 供应商（catalog SUPPLIER 角色的逻辑 ID）。 */
      UUID supplierRoleId,
      /** 登记那一刻的供应商名快照。 */
      String supplierName,
      String qualificationType,
      String qualificationTypeLabel,
      String title,
      String certNo,
      String issuer,
      /** 关联的 03 品牌（未关联为 null）。 */
      @Schema(nullable = true) UUID brandId,
      /** 关联那一刻的品牌名快照。 */
      String brandName,
      String reviewStatus,
      String reviewStatusLabel,
      /** 有效期起点。 */
      String validFrom,
      /** 有效期终点。 */
      String validUntil,
      /** 到期状态（现算，不落库）。 */
      String expiryState,
      String expiryStateLabel,
      /** 距到期天数（负 = 已过期 N 天）。 */
      int daysToExpiry,
      /** 提交审核时刻（从未提交为 null）。 */
      @Schema(nullable = true) String submittedAt,
      /** 最近一次审核时刻（从未审核为 null）。 */
      @Schema(nullable = true) String reviewedAt,
      /** 最近一次审核理由（驳回 / 撤销的原因）。 */
      String reviewNote,
      /** 资质文件数。 */
      long documentCount,
      /** 未处理的提醒数。 */
      long openReminderCount,
      String note,
      long version) {}

  /** 资质详情：档案 + 全部文件 + 全部审核流转。 */
  public record QualificationDetail(
      QualificationRow qualification,
      List<QualificationDocumentRow> documents,
      List<QualificationReviewEventRow> reviews,
      String note) {}

  /** 登记资质入参。 */
  public record QualificationInput(
      @NotBlank @Size(max = 64) String code,
      @NotNull UUID supplierRoleId,
      @NotBlank @Size(max = 32) String qualificationType,
      @NotBlank @Size(max = 200) String title,
      @Size(max = 64) String certNo,
      @Size(max = 120) String issuer,
      /** 品牌授权关联的 03 品牌（授权书必填）。 */
      @Schema(nullable = true) UUID brandId,
      /** 有效期起点（ISO-8601 带时区）。 */
      @NotNull String validFrom,
      /** 有效期终点（ISO-8601 带时区）。 */
      @NotNull String validUntil,
      @Size(max = 2000) String note,
      /** 乐观锁版本号（修改时必填）。 */
      @PositiveOrZero long version) {}

  /** 修改资质入参。 */
  public record QualificationUpdateInput(
      @NotBlank @Size(max = 200) String title,
      @Size(max = 64) String certNo,
      @Size(max = 120) String issuer,
      @Schema(nullable = true) UUID brandId,
      @NotNull String validFrom,
      @NotNull String validUntil,
      @Size(max = 2000) String note,
      @PositiveOrZero long version) {}

  /** 上传资质文件入参。文件本体不入库，只存对象存储的 key。 */
  public record QualificationDocumentInput(
      @NotBlank @Size(max = 16) String documentKind,
      /** 对象存储的 key（不是 URL，也不是文件本体）。 */
      @NotBlank @Size(max = 500) String documentKey,
      @Size(max = 200) String documentName,
      @Size(max = 120) String mimeType,
      /** 文件大小（字节）。 */
      @DecimalMin(value = "0", inclusive = true)
          @Digits(integer = 13, fraction = 6)
          BigDecimal sizeBytes,
      @Size(max = 2000) String note) {}

  /** 审核流转入参。 */
  public record QualificationReviewInput(
      @NotBlank @Size(max = 16) String action,
      /** 理由：驳回与撤销必填。 */
      @Size(max = 2000) String reason) {}

  // ------------------------------------------------------------ 文件 / 审核事件 / 提醒

  /** 一份资质文件。 */
  public record QualificationDocumentRow(
      UUID id,
      UUID qualificationId,
      String documentKind,
      String documentKindLabel,
      /** 对象存储的 key（**不是**文件本体，也不是可公开下载的 URL）。 */
      String documentKey,
      String documentName,
      String mimeType,
      BigDecimal sizeBytes,
      String uploadedAt,
      String note) {}

  /** 一次审核流转（append-only）。 */
  public record QualificationReviewEventRow(
      UUID id,
      UUID qualificationId,
      int seq,
      String action,
      String actionLabel,
      String fromStatus,
      String fromStatusLabel,
      String toStatus,
      String toStatusLabel,
      String reason,
      String operatedAt) {}

  /** 一条过期提醒。 */
  public record QualificationReminderRow(
      UUID id,
      UUID qualificationId,
      String qualificationCode,
      String qualificationTitle,
      /** 提醒背后的供应商（catalog SUPPLIER 角色 id）。 */
      UUID supplierRoleId,
      String supplierName,
      String qualificationType,
      String qualificationTypeLabel,
      String reminderKind,
      String reminderKindLabel,
      /** 提醒针对的到期日。 */
      String dueDate,
      /** 生成时的阈值窗口（天）。 */
      int warningDays,
      String status,
      String statusLabel,
      String generatedAt,
      @Schema(nullable = true) String acknowledgedAt,
      String note) {}

  /** 提醒处理入参。 */
  public record QualificationReminderHandleInput(
      @NotBlank @Size(max = 16) String status, @Size(max = 2000) String note) {}

  // ------------------------------------------------------------ 阻断判定

  /** 一条阻断原因。 */
  public record BlockingReason(
      String code,
      String label,
      /** 可直接展示的人话。 */
      String message,
      /** 相关的资质（缺失类原因没有对应行，为 null）。 */
      @Schema(nullable = true) UUID qualificationId,
      @Schema(nullable = true) String qualificationCode,
      @Schema(nullable = true) String qualificationType,
      @Schema(nullable = true) String qualificationTypeLabel,
      @Schema(nullable = true) String reviewStatus,
      @Schema(nullable = true) String reviewStatusLabel,
      @Schema(nullable = true) String expiryState,
      @Schema(nullable = true) String expiryStateLabel,
      @Schema(nullable = true) String validUntil,
      @Schema(nullable = true) Integer daysToExpiry,
      /** 原因落在哪个供应商上（商品维度判定时可能有多条）。 */
      @Schema(nullable = true) UUID supplierRoleId,
      @Schema(nullable = true) String supplierName) {}

  /**
   * 阻断判定结论。
   *
   * <p>蓝图加粗句：「资质过期必须阻断相关商品的刊登与发货，不能只做提醒」——
   * 所以本形状是给 04 刊登与发货链路直接消费的判定，而不是一句人话提示。
   */
  public record BlockingDecision(
      /** 是否阻断。 */
      boolean blocked,
      /** 机器可读的原因码（多原因时取第一条）。 */
      @Schema(nullable = true) String blockCode,
      @Schema(nullable = true) String blockLabel,
      /** 可直接展示的人话。 */
      String blockMessage,
      /** 判定范围：SUPPLIER / BRAND / SKU。 */
      String scope,
      /** 判定基准时刻（服务端时钟）。 */
      String evaluatedAt,
      List<BlockingReason> reasons,
      String note) {}

  // ------------------------------------------------------------ 策略 / 概览 / 能力声明

  /** 租户级策略。 */
  public record QualificationPolicyRow(
      /** 即将过期窗口（天）。 */
      int warningDays,
      /** 必备资质类型（闭集子集）。 */
      List<String> requiredTypes,
      String note,
      String updatedAt) {}

  /** 修改策略入参。 */
  public record QualificationPolicyInput(
      @Min(1) @Max(365) int warningDays,
      @NotEmpty @Size(max = 10) List<@NotBlank String> requiredTypes,
      @Size(max = 2000) String note) {}

  /** 概览统计（不受筛选与分页影响）。 */
  public record QualificationOverview(
      long totalCount,
      long draftCount,
      long pendingCount,
      long approvedCount,
      long rejectedCount,
      long revokedCount,
      long expiringCount,
      long expiredCount,
      long blockingCount,
      long openReminderCount,
      long supplierCount,
      long brandAuthorizationCount,
      @Schema(nullable = true) String nearestExpiry,
      String note) {}

  /** 一个可选供应商候选（catalog 的 SUPPLIER 角色，只读）。 */
  public record QualificationSupplierCandidateRow(
      UUID roleId, String partyCode, String partyName, String partyStatus) {}

  /** 一个可选品牌候选（03 品牌，只读）。 */
  public record QualificationBrandCandidateRow(
      UUID id, String code, String name, String authorizationStatus, boolean enabled) {}

  /** 一个未交付的下游 / 维度。 */
  public record QualificationGap(
      String moduleId, String moduleName, String ownerModule, boolean available, String impact) {}

  /** 能力声明：把「哪些下游还没交付」「阻断判定的口径是什么」显式报出来。 */
  public record SupplierQualificationCapabilities(
      List<QualificationOption> qualificationTypes,
      List<QualificationOption> reviewStatuses,
      List<QualificationOption> expiryStates,
      /** 各下游模块的可用性。 */
      List<QualificationGap> gaps,
      /** 阻断语义说明（界面上要照实显示给运营）。 */
      String blockingSemantics,
      String note) {}

  // ------------------------------------------------------------ 查询条件

  /** 资质列表的查询条件。 */
  public record QualificationQuery(
      @Schema(nullable = true) UUID supplierRoleId,
      @Schema(nullable = true) String qualificationType,
      @Schema(nullable = true) String reviewStatus,
      @Schema(nullable = true) String expiryState,
      @Schema(nullable = true) UUID brandId,
      @Schema(nullable = true) String keyword,
      /** 排序键白名单。 */
      String sort,
      boolean desc,
      int page,
      int size) {}

  /** 提醒清单的查询条件。 */
  public record QualificationReminderQuery(
      @Schema(nullable = true) UUID supplierRoleId,
      @Schema(nullable = true) String reminderKind,
      @Schema(nullable = true) String status,
      int page,
      int size) {}

  /** 资质列表分页结果。 */
  public record QualificationPage(
      List<QualificationRow> items,
      int page,
      int size,
      long total,
      /** 不受筛选与分页影响的汇总。 */
      long expiringCount,
      long expiredCount,
      long approvedCount,
      long openReminderCount,
      String note) {}

  /** 提醒清单分页结果。 */
  public record QualificationReminderPage(
      List<QualificationReminderRow> items,
      int page,
      int size,
      long total,
      long openCount,
      long expiringCount,
      long expiredCount,
      String note) {}

  /** 提醒扫描结果（生成 / 刷新了多少条）。 */
  public record QualificationReminderScanResult(
      int createdCount, int expiringCount, int expiredCount, String scannedAt, String note) {}

  /** 上传结果。 */
  public record QualificationDocumentSaved(UUID id, UUID qualificationId, String documentKind, String uploadedAt) {}

  /** 审核流转结果。 */
  public record QualificationReviewSaved(
      UUID qualificationId,
      String action,
      String fromStatus,
      String toStatus,
      int seq,
      String operatedAt,
      String reviewNote) {}
}
