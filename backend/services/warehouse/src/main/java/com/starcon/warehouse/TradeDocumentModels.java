package com.starcon.warehouse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 10.5.2.1 贸易单证。
 *
 * <p>蓝图 10-logistics-and-freight.md 10.5.2.1 的输入/输出契约：
 *
 * <ul>
 *   <li>职责：管理与生成贸易相关单证：商业发票、装箱单、原产地证（RCEP/CO/FTA 优惠）、提单/空运单、进口许可与认证文件，按目的地国家规则校验完整性。
 *   <li>输入：发货明细；目的地国家单证要求；16 合规的认证状态。
 *   <li>输出：单证文件 + 完整性校验结果 + 缺失提醒。
 * </ul>
 *
 * <p>DTO 一律带 {@code Trade}/{@code Document} 前缀：四个服务的 schema 被 codegen 拍平进
 * 同一命名空间，重名会被**静默覆盖且没有任何警告**（AGENTS.md 契约纪律）。
 *
 * <p>本模块必须自己讲清楚的四条口径（都随响应返回，不让界面上凭空猜）：
 *
 * <ol>
 *   <li><b>「实质性改变」是业务判断，系统只校验与留档</b>：蓝图关系段原文—— 「FTA 优惠原产地证能显著降低关税，其适用条件（尤其是"实质性改变"）需要业务确认，
 *       系统只负责按规则校验与留档」。所以本模块<b>没有任何</b>实质性改变判定逻辑： {@link SubstantiveChangeStatus}
 *       记的是「有没有人确认过」，不是「法律上成不成立」； 主张优惠而未确认的单证被 DB 闸门挡住提交，并被显式报成阻断级缺失提醒。
 *   <li><b>单证要求规则是数据不是常量</b>：目的地国家 × 单证类型 × 必备程度落在 {@code trade_document_rule}，租户可维护。写死成 Java 常量或
 *       CHECK 词表等于把 海关要求抄成第二份且必然漂移。
 *   <li><b>校验结论 append-only</b>：{@code trade_document_check} 一次校验一行、不改不删
 *       （结论是举证材料），改结论只能重跑一次；缺失提醒是**可逐条处理的工作项**， 处理记录同样留痕。两者分表就是为了不互相打架。
 *   <li><b>16 合规上游未交付</b>：本仓库无 product_compliance 服务，所以认证状态来自 <b>租户可维护</b>的 {@code
 *       trade_certification_status}，每行带 {@code certificationSource} 如实标注「人登记的」还是「16 合规推来的」。
 * </ol>
 */
public final class TradeDocumentModels {
  private TradeDocumentModels() {}

  /**
   * 单证包状态词表。与 {@code trade_document_set.status} 的 CHECK 逐值对应。
   *
   * <p>合法转换只有这一张表（{@link #allowedNext} 是唯一真相，服务层与前端共用）：
   *
   * <pre>
   * DRAFT         → DRAFT（改单）/ UNDER_REVIEW / CANCELLED
   * UNDER_REVIEW  → APPROVED / REJECTED / DRAFT（撤回复核）/ CANCELLED
   * APPROVED      → SUBMITTED（**必须复核通过 + 完整性校验 PASS**）/ DRAFT / CANCELLED
   * REJECTED      → DRAFT（退回改，改完重新走复核）/ UNDER_REVIEW / CANCELLED
   * SUBMITTED     → ACKNOWLEDGED（已接收）/ SENT_BACK（被退回）
   * SENT_BACK     → DRAFT / UNDER_REVIEW / CANCELLED（改完重新走复核）
   * ACKNOWLEDGED  → 终态
   * CANCELLED     → 终态
   * </pre>
   */
  public enum DocumentSetStatus {
    /** 草稿：可自由改单证，不动任何账。 */
    DRAFT("草稿"),
    /** 待复核：已送复核，等待人工查看单证与校验结论。 */
    UNDER_REVIEW("待复核"),
    /** 已复核通过：提交下游的**唯一前置状态**。 */
    APPROVED("已复核通过"),
    /** 复核退回：必须给原因，改完重新走复核。 */
    REJECTED("复核退回"),
    /** 已提交：已送到报关行/目的仓。 */
    SUBMITTED("已提交"),
    /** 被退回：下游退回，必须给原因，改完重新走复核。 */
    SENT_BACK("被退回"),
    /** 已接收：下游已接收备证资料。终态。 */
    ACKNOWLEDGED("已接收"),
    /** 已取消：终态。必须给原因。 */
    CANCELLED("已取消");

    private final String label;

    DocumentSetStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static DocumentSetStatus parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException("未知的单证包状态：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }

    /** 唯一真相：合法转换表。任何不在表内的跳转都必须被拒绝。 */
    public static List<DocumentSetStatus> allowedNext(DocumentSetStatus current) {
      return switch (current) {
        case DRAFT -> List.of(DRAFT, UNDER_REVIEW, CANCELLED);
        case UNDER_REVIEW -> List.of(APPROVED, REJECTED, DRAFT, CANCELLED);
        case APPROVED -> List.of(SUBMITTED, DRAFT, CANCELLED);
        case REJECTED -> List.of(DRAFT, UNDER_REVIEW, CANCELLED);
        case SUBMITTED -> List.of(ACKNOWLEDGED, SENT_BACK);
        case SENT_BACK -> List.of(DRAFT, UNDER_REVIEW, CANCELLED);
        case ACKNOWLEDGED -> List.of();
        case CANCELLED -> List.of();
      };
    }
  }

  /** 单证类型。与 {@code trade_document.document_kind} 的 CHECK 逐值对应。 */
  public enum TradeDocumentKind {
    COMMERCIAL_INVOICE("商业发票"),
    PACKING_LIST("装箱单"),
    CERTIFICATE_OF_ORIGIN("原产地证"),
    RCEP_CERTIFICATE_OF_ORIGIN("RCEP 原产地证"),
    BILL_OF_LADING("提单"),
    AIR_WAYBELL("空运单"),
    IMPORT_LICENSE("进口许可"),
    CERTIFICATION_DOC("认证文件"),
    OTHER("其他单证");

    private final String label;

    TradeDocumentKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static TradeDocumentKind parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException("未知的单证类型：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }

    /** 是否是原产地证（唯一可以主张 FTA 优惠的单证类型）。 */
    public boolean isOriginCertificate() {
      return this == CERTIFICATE_OF_ORIGIN || this == RCEP_CERTIFICATE_OF_ORIGIN;
    }
  }

  /**
   * 单证要求级别。与 {@code trade_document_rule.requirement_level} 逐值对应。
   *
   * <p>只有 {@link #MANDATORY} 缺了才阻断提交；{@link #CONDITIONAL} 的触发条件是 <b>人读口径</b>（{@code
   * condition_expression}），系统不执行它 —— 把条件做成可执行表达式 等于让运营在没有评审的情况下定义报关规则。
   */
  public enum RequirementLevel {
    MANDATORY("必备"),
    CONDITIONAL("有条件必备"),
    OPTIONAL("可选");

    private final String label;

    RequirementLevel(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static RequirementLevel parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException("未知的单证要求级别：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /**
   * 「实质性改变」的业务确认状态。
   *
   * <p><b>这是本模块最容易被读错的一组取值，所以在这里写清楚它答什么、不答什么：</b>
   *
   * <p>它回答的是「<b>有没有人确认过</b>」——谁、什么时候、按什么依据写下的。 它<b>不</b>回答「实质性改变在法律上<b>成不成立</b>」——那是业务/法务的判断，
   * 蓝图关系段把它明确划给业务：系统只负责按规则校验与留档。
   *
   * <p>所以 {@link #CONFIRMED} 的含义**不是**「系统判定成立」，而是「业务已留痕确认」。 本模块不存在（也不允许新增）任何自动把它置为 CONFIRMED 的代码路径。
   */
  public enum SubstantiveChangeStatus {
    /** 不适用：这张单证不是原产地证，或没主张优惠。 */
    NOT_APPLICABLE("不适用"),
    /** 待业务确认：主张了优惠但还没有人确认 —— **阻断提交**。 */
    PENDING_BUSINESS_CONFIRMATION("待业务确认"),
    /** 业务已确认：有人留痕确认过（**不代表系统判定法律上成立**）。 */
    CONFIRMED("业务已确认"),
    /** 业务确认不成立：不主张该项优惠。 */
    REJECTED("业务确认不成立");

    private final String label;

    SubstantiveChangeStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static SubstantiveChangeStatus parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException(
          "未知的实质性改变确认状态：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /** 校验结论。与 {@code trade_document_check.result} 逐值对应。 */
  public enum CheckResult {
    /** 通过：规则要求的单证齐备且没有待业务确认项。 */
    PASS("校验通过"),
    /** 不通过：有必备单证缺失 / 认证状态不合格 / 单证未定稿。 */
    FAIL("校验不通过"),
    /**
     * 待业务确认：单证齐备，但有优惠主张还没人确认。
     *
     * <p><b>刻意不是 PASS</b>：把它混进 PASS 就等于让界面显示「校验通过」而实际上 这批货能不能享受优惠还没人拍板 —— 那正是蓝图关系段要避免的越界。
     */
    PENDING_BUSINESS_CONFIRMATION("待业务确认");

    private final String label;

    CheckResult(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static CheckResult parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException("未知的校验结论：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /** 认证状态。与 {@code trade_certification_status.status} 逐值对应。 */
  public enum CertificationStatus {
    VALID("有效"),
    PENDING("待取证"),
    EXPIRED("已过期"),
    REVOKED("已撤销"),
    NOT_REQUIRED("无需认证");

    private final String label;

    CertificationStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static CertificationStatus parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException("未知的认证状态：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /** 缺失提醒类型。与 {@code trade_document_reminder.reminder_type} 逐值对应。 */
  public enum ReminderType {
    MISSING_DOCUMENT("必备单证缺失"),
    UNDECLARED_DOCUMENT("已出单证但规则未要求"),
    UNCONFIRMED_SUBSTANTIVE_CHANGE("FTA 优惠待业务确认"),
    CERTIFICATION_EXPIRED("认证已过期"),
    CERTIFICATION_REVOKED("认证已被撤销"),
    CERTIFICATION_MISSING("缺认证状态登记"),
    DOCUMENT_NOT_FINAL("单证未定稿");

    private final String label;

    ReminderType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static ReminderType parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException("未知的缺失提醒类型：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /** 提醒级别。{@link #BLOCKER} 阻断提交，{@link #WARNING} 只提示。 */
  public enum ReminderSeverity {
    BLOCKER("阻断"),
    WARNING("提示");

    private final String label;

    ReminderSeverity(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static ReminderSeverity parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException("未知的提醒级别：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  // ================================================================ 响应 DTO

  /**
   * 依赖/维度的可用性声明（与 10.5.1.1 的 {@code CustomsDimension} 同形，前端同一套渲染）。
   *
   * <p>{@code available=false} 时前端显式渲染成「不可用 + 归属模块 + 原因」， 而不是给一个点下去没反应的选项。
   */
  public record TradeDimension(
      String value,
      String label,
      boolean available,
      String ownerModule,
      String href,
      String note) {}

  /** 状态机的合法转换，前端据此只给合法的按钮。 */
  public record TradeTransition(
      String from, String fromLabel, String to, String toLabel, String action, String note) {}

  public record TradeStatusOption(String value, String label) {}

  public record TradeDocumentKindOption(String value, String label, boolean originCertificate) {}

  public record TradeRequirementOption(String value, String label) {}

  public record TradeSubstantiveChangeOption(String value, String label, boolean blocking) {}

  public record TradeCheckResultOption(String value, String label) {}

  public record TradeCertificationOption(String value, String label) {}

  public record TradeReminderTypeOption(String value, String label) {}

  /** 贸易单证能力清单：状态机、单证类型、要求级别、确认状态、校验结论、 **未交付依赖逐条声明**与四条必须讲清的口径。 */
  public record TradeDocumentCapabilities(
      List<TradeDimension> dependencies,
      List<TradeTransition> transitions,
      List<TradeStatusOption> statusOptions,
      List<TradeDocumentKindOption> documentKinds,
      List<TradeRequirementOption> requirementLevels,
      List<TradeSubstantiveChangeOption> substantiveChangeStatuses,
      List<TradeCheckResultOption> checkResults,
      List<TradeCertificationOption> certificationStatuses,
      List<TradeReminderTypeOption> reminderTypes,
      String substantiveChangeNote,
      String submitGateNote,
      String ruleDataNote,
      String certificationSourceNote) {}

  // ---- 规则与模板 ----

  /** 目的地国家单证要求规则。**这是数据不是常量** —— 租户可维护。 */
  public record TradeRuleRow(
      UUID id,
      String countryCode,
      String documentKind,
      String documentKindLabel,
      String requirementLevel,
      String requirementLevelLabel,
      String conditionExpression,
      Boolean requiresCertification,
      String ruleSource,
      String sourceReference,
      LocalDate validFrom,
      LocalDate validTo,
      String status,
      String notes,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record TradeRulePage(
      TradeRuleSummary summary,
      List<TradeRuleRow> rows,
      List<TradeStatusOption> statuses,
      List<TradeRequirementOption> requirementLevels,
      List<TradeDocumentKindOption> documentKinds,
      long total,
      int page,
      int size) {}

  public record TradeRuleSummary(
      long total, long active, long retired, long mandatory, long certificationRequired) {}

  public record TradeRuleCreateRequest(
      String countryCode,
      String documentKind,
      String requirementLevel,
      String conditionExpression,
      Boolean requiresCertification,
      String ruleSource,
      String sourceReference,
      LocalDate validFrom,
      LocalDate validTo,
      String notes) {}

  public record TradeRuleUpdateRequest(
      Long expectedVersion,
      String requirementLevel,
      String conditionExpression,
      Boolean requiresCertification,
      String ruleSource,
      String sourceReference,
      LocalDate validFrom,
      LocalDate validTo,
      String status,
      String notes) {}

  public record TradeRuleSaved(
      UUID id,
      String countryCode,
      String documentKind,
      String status,
      long version,
      String message) {}

  // ---- 单证模板 ----

  public record TradeTemplateRow(
      UUID id,
      String templateCode,
      String documentKind,
      String documentKindLabel,
      String countryCode,
      Integer templateVersion,
      String currency,
      Map<String, Object> templateBody,
      String status,
      UUID supersedesTemplateId,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      String notes,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record TradeTemplatePage(
      List<TradeTemplateRow> rows,
      List<TradeStatusOption> statuses,
      List<TradeDocumentKindOption> documentKinds,
      long total,
      int page,
      int size) {}

  public record TradeTemplateCreateRequest(
      String templateCode,
      String documentKind,
      String countryCode,
      String currency,
      Map<String, Object> templateBody,
      String status,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      String notes) {}

  public record TradeTemplateUpdateRequest(
      Long expectedVersion,
      Map<String, Object> templateBody,
      String status,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      String notes) {}

  public record TradeTemplateSaved(
      UUID id,
      String templateCode,
      Integer templateVersion,
      String status,
      long version,
      String message) {}

  // ---- 认证状态（16 合规未交付 → 租户可维护）----

  public record TradeCertificationRow(
      UUID id,
      UUID skuId,
      String skuCode,
      String skuName,
      String certificationCode,
      String certificationName,
      String status,
      String statusLabel,
      String certificateNo,
      String issuingBody,
      LocalDate issuedOn,
      LocalDate expiresOn,
      String documentRef,
      String certificationSource,
      String sourceReference,
      String notes,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record TradeCertificationPage(
      List<TradeCertificationRow> rows,
      List<TradeCertificationOption> statuses,
      long total,
      int page,
      int size) {}

  public record TradeCertificationCreateRequest(
      UUID skuId,
      String certificationCode,
      String certificationName,
      String status,
      String certificateNo,
      String issuingBody,
      LocalDate issuedOn,
      LocalDate expiresOn,
      String documentRef,
      String certificationSource,
      String sourceReference,
      String notes) {}

  public record TradeCertificationUpdateRequest(
      Long expectedVersion,
      String status,
      String certificateNo,
      String issuingBody,
      LocalDate issuedOn,
      LocalDate expiresOn,
      String documentRef,
      String certificationSource,
      String sourceReference,
      String notes) {}

  public record TradeCertificationSaved(
      UUID id, String certificationCode, String status, long version, String message) {}

  // ---- FTA 优惠规则（只配置口径，不做判定）----

  public record TradePreferenceRuleRow(
      UUID id,
      String scheme,
      String destinationCountryCode,
      String productScope,
      String hsPrefix,
      String preferenceCriteria,
      String substantiveChangeCriterion,
      Boolean requiresBusinessConfirmation,
      LocalDate validFrom,
      LocalDate validTo,
      String status,
      String ruleSource,
      String sourceReference,
      String notes,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record TradePreferenceRulePage(
      List<TradePreferenceRuleRow> rows,
      List<TradeStatusOption> statuses,
      long total,
      int page,
      int size) {}

  public record TradePreferenceRuleCreateRequest(
      String scheme,
      String destinationCountryCode,
      String productScope,
      String hsPrefix,
      String preferenceCriteria,
      String substantiveChangeCriterion,
      Boolean requiresBusinessConfirmation,
      LocalDate validFrom,
      LocalDate validTo,
      String status,
      String ruleSource,
      String sourceReference,
      String notes) {}

  public record TradePreferenceRuleUpdateRequest(
      Long expectedVersion,
      String preferenceCriteria,
      String substantiveChangeCriterion,
      Boolean requiresBusinessConfirmation,
      LocalDate validFrom,
      LocalDate validTo,
      String status,
      String ruleSource,
      String sourceReference,
      String notes) {}

  public record TradePreferenceRuleSaved(
      UUID id, String scheme, String status, long version, String message) {}

  // ---- 单证包 ----

  public record TradeSetRow(
      UUID id,
      String setCode,
      UUID sourceDeclarationId,
      String sourceDeclarationCode,
      UUID legalEntityId,
      String exporterName,
      String consigneeName,
      String consigneeCountryCode,
      String destinationCountryCode,
      String transportMode,
      String documentCurrency,
      BigDecimal totalDeclaredValue,
      Integer totalDocumentCount,
      Integer requiredDocumentCount,
      Integer missingDocumentCount,
      Integer pendingBusinessCount,
      String latestCheckResult,
      String latestCheckResultLabel,
      OffsetDateTime latestCheckAt,
      String status,
      String statusLabel,
      Integer reviewRound,
      OffsetDateTime reviewedAt,
      OffsetDateTime submittedAt,
      OffsetDateTime acknowledgedAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      long version) {}

  public record TradeSetSummary(
      long total,
      long draft,
      long underReview,
      long approved,
      long submitted,
      long blocked,
      long pendingBusiness,
      BigDecimal totalDeclaredValue,
      String documentCurrency) {}

  public record TradeSetPage(
      TradeSetSummary summary,
      List<TradeSetRow> rows,
      List<TradeStatusOption> statuses,
      List<TradeCheckResultOption> checkResults,
      long total,
      int page,
      int size) {}

  /** 列表查询条件。与后端 {@code TradeSetQuery} 一一对应。 */
  public record TradeSetQuery(
      List<String> status,
      String destinationCountryCode,
      UUID sourceDeclarationId,
      String latestCheckResult,
      Boolean blockedOnly,
      String keyword,
      String sort,
      int page,
      int size) {}

  public record TradeSetDetail(
      TradeSetRow set,
      List<TradeDocumentRow> documents,
      List<TradeCheckRow> checks,
      List<TradeReminderRow> reminders,
      List<TradeEventRow> events,
      List<TradeTransition> allowedNext,
      List<TradeDocumentKindOption> documentKinds) {}

  public record TradeSetCreateRequest(UUID sourceDeclarationId, UUID legalEntityId, String notes) {}

  public record TradeSetUpdateRequest(Long expectedVersion, String submittedTo, String notes) {}

  public record TradeSetReviewRequest(String reviewNotes) {}

  public record TradeSetRejectReviewRequest(String reason) {}

  public record TradeSetWithdrawReviewRequest(String notes) {}

  public record TradeSetSubmitRequest(String submittedTo, String submissionReference) {}

  public record TradeSetAcknowledgeRequest(String acknowledgementReference) {}

  public record TradeSetSendBackRequest(String reason) {}

  public record TradeSetCancelRequest(String reason) {}

  public record TradeSaved(
      UUID id, String setCode, String status, String statusLabel, long version, String message) {}

  // ---- 单证 ----

  public record TradeDocumentRow(
      UUID id,
      UUID setId,
      Integer lineNo,
      String documentKind,
      String documentKindLabel,
      String documentNo,
      Integer documentVersion,
      UUID templateId,
      String templateCode,
      String currency,
      BigDecimal totalAmount,
      BigDecimal quantityTotal,
      Integer packageCount,
      Map<String, Object> content,
      String ftaScheme,
      Boolean preferenceClaimed,
      String originCountryCode,
      UUID preferenceRuleId,
      String substantiveChangeStatus,
      String substantiveChangeStatusLabel,
      String substantiveChangeNote,
      OffsetDateTime businessConfirmedAt,
      UUID businessConfirmedBy,
      Long preferenceRuleVersion,
      String status,
      UUID supersedesDocumentId,
      OffsetDateTime issuedAt,
      UUID preparedBy,
      String sourceSystem,
      String sourceReference,
      OffsetDateTime createdAt) {}

  public record TradeDocumentCreateRequest(
      String documentKind,
      UUID templateId,
      String documentNo,
      String currency,
      BigDecimal quantityTotal,
      Integer packageCount,
      Map<String, Object> content,
      String notes) {}

  public record TradeDocumentFinalizeRequest(String documentNo, String sourceReference) {}

  public record TradeDocumentSaved(
      UUID id,
      UUID setId,
      String documentKind,
      String documentKindLabel,
      Integer documentVersion,
      String status,
      long version,
      String message) {}

  /**
   * 优惠主张 / 业务确认请求。
   *
   * <p>{@code substantiveChangeNote} 是**人写的确认依据**，不是系统算出来的结论； {@code confirmed=false}
   * 表示业务确认不成立（撤回优惠主张），而不是「否」这个法律判断。
   */
  public record TradePreferenceClaimRequest(
      String ftaScheme,
      String originCountryCode,
      UUID preferenceRuleId,
      String substantiveChangeNote,
      Boolean confirmed) {}

  public record TradePreferenceConfirmationRow(
      UUID documentId,
      String documentKind,
      String documentKindLabel,
      String documentNo,
      String ftaScheme,
      String originCountryCode,
      Boolean preferenceClaimed,
      String substantiveChangeStatus,
      String substantiveChangeStatusLabel,
      String substantiveChangeNote,
      OffsetDateTime businessConfirmedAt,
      UUID businessConfirmedBy,
      Long preferenceRuleVersion,
      String status,
      OffsetDateTime createdAt) {}

  public record TradePreferenceConfirmationPage(
      List<TradePreferenceConfirmationRow> rows,
      List<TradeSubstantiveChangeOption> statuses,
      long total,
      int page,
      int size) {}

  // ---- 校验与提醒 ----

  public record TradeCheckRow(
      UUID id,
      UUID setId,
      Integer checkRound,
      String result,
      String resultLabel,
      Integer requiredCount,
      Integer satisfiedCount,
      Integer missingCount,
      Integer pendingBusinessCount,
      Integer ruleCount,
      List<Object> items,
      String missingSummary,
      OffsetDateTime checkedAt,
      UUID checkedBy,
      OffsetDateTime createdAt) {}

  public record TradeCheckSummary(
      long total,
      long pass,
      long fail,
      long pendingBusiness,
      long openBlockers,
      long openWarnings) {}

  public record TradeReminderRow(
      UUID id,
      UUID setId,
      UUID checkId,
      String documentKind,
      String documentKindLabel,
      String reminderType,
      String reminderTypeLabel,
      String severity,
      String severityLabel,
      String message,
      String status,
      OffsetDateTime resolvedAt,
      UUID resolvedBy,
      String resolutionNote,
      OffsetDateTime createdAt) {}

  public record TradeReminderPage(
      TradeCheckSummary summary,
      List<TradeReminderRow> rows,
      List<TradeStatusOption> statuses,
      List<TradeDocumentKindOption> documentKinds,
      long total,
      int page,
      int size) {}

  public record TradeReminderResolveRequest(String status, String resolutionNote) {}

  public record TradeReminderSaved(UUID id, String status, String message) {}

  public record TradeEventRow(
      UUID id,
      UUID setId,
      String kind,
      String kindLabel,
      OffsetDateTime occurredAt,
      String fromStatus,
      String toStatus,
      UUID actorId,
      String targetKind,
      UUID targetId,
      String reason,
      String notes,
      OffsetDateTime createdAt) {}
}
