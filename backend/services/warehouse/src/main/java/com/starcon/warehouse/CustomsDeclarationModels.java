package com.starcon.warehouse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 10.5.1.1 报关单管理。
 *
 * <p>蓝图 10-logistics-and-freight.md 10.5.1.1 的输入/输出契约：
 *
 * <ul>
 *   <li>职责：管理报关单：按发货单生成申报资料（报关单、装箱单、发票），提交报关行/IOR，接收放行、退单、改单结果。
 *   <li>输入：发货明细；3.7.1.1 HS 编码与申报要素；货值与汇率快照；贸易方式与收发货人信息。
 *   <li>输出：报关单 + 三单据 + 报关行提交结果 + 放行/异常状态。
 * </ul>
 *
 * <p>DTO 一律带 {@code Customs}/{@code Declaration} 前缀：四个服务的 schema 被 codegen
 * 拍平进同一命名空间，重名会被**静默覆盖且没有任何警告**（AGENTS.md 契约纪律）。
 *
 * <p>四条本模块必须自己讲清楚的口径（都随响应返回，不让界面上凭空猜）：
 *
 * <ol>
 *   <li><b>人工复核是硬闸，不是建议</b>：蓝图关系段原文「报关数据错误导致的扣货与罚款由卖家承担， 因此必须有人工复核节点」。所以 {@code REVIEWED →
 *       SUBMITTED} 是 DB 级触发器钉死的， 绕过服务层直接改状态同样被挡；复核不通过退回草稿后，改完必须<b>重新走复核</b>才能再次提交。
 *   <li><b>货值与汇率是快照</b>：海关事后复核 12 个月前的报关单时要能回答「当时按哪个汇率申报的」，
 *       所以汇率、汇率精度刻度、汇率日期、折算本位币金额四列一起落库并被延迟约束钉死自洽。
 *   <li><b>一票货只报一次关</b>：重复报关是真实法律风险（可能被判申报不实），且账上完全看不出来。 改单走状态机与单据版本链，不靠再造一张单。
 *   <li><b>HS 编码上游未交付</b>：3.7.1.1 被 product_compliance 服务缺失阻塞，因此 {@code hsCode} 接受自由码，并带 {@code
 *       hsCodeSource} / {@code hsCodeVerified} 如实记下 「这个码是人工填的还是字典来的」；申报要素留成<b>可后补结构</b>（jsonb 对象 +
 *       齐备性状态机）， 上游交付后回填即可，不需要改结构。
 * </ol>
 */
public final class CustomsDeclarationModels {
  private CustomsDeclarationModels() {}

  /**
   * 报关单状态词表。与 {@code customs_declaration.status} 的 CHECK 逐值对应。
   *
   * <p>合法转换只有这一张表（{@link #allowedNext} 是唯一真相，服务层与前端共用）：
   *
   * <pre>
   * DRAFT           → DRAFT（改单）/ PENDING_REVIEW / CANCELLED
   * PENDING_REVIEW  → REVIEWED / REJECTED / DRAFT（撤回复核）/ CANCELLED
   * REVIEWED        → SUBMITTED（**必须先复核**）/ PENDING_REVIEW（撤回复核）/ DRAFT / CANCELLED
   * REJECTED        → DRAFT（退回改，改完重新走复核）/ PENDING_REVIEW / CANCELLED
   * SUBMITTED       → CLEARED / CUSTOMS_REJECTED / AMENDED
   * CUSTOMS_REJECTED→ DRAFT（退回改）/ AMENDED（改单后重提）
   * AMENDED         → CLEARED / CUSTOMS_REJECTED / AMENDED
   * CLEARED         → 终态
   * CANCELLED       → 终态
   * </pre>
   *
   * <p>注意 {@code REVIEWED → SUBMITTED} 是唯一允许的提交路径，而 {@code DRAFT → SUBMITTED}、 {@code
   * PENDING_REVIEW → SUBMITTED}、{@code REJECTED → SUBMITTED} **都不在表内** ——
   * 这正是蓝图那句「扣货与罚款由卖家承担」对应的闸门，服务层与 DB 层同时钉死。
   */
  public enum DeclarationStatus {
    /** 草稿：可自由改单，不动任何账。 */
    DRAFT("草稿"),
    /** 待复核：已提交复核，等待人工查看申报资料。 */
    PENDING_REVIEW("待复核"),
    /** 已复核：人工复核通过，是提交报关行的**唯一前置状态**。 */
    REVIEWED("已复核"),
    /** 复核退回：人工复核不通过，必须退回草稿改完重新走复核。 */
    REJECTED("复核退回"),
    /** 已提交：已送到报关行/IOR，等待海关回执。 */
    SUBMITTED("已提交"),
    /** 已放行：海关已清关放行。终态。 */
    CLEARED("已放行"),
    /** 海关退单：被退单，必须按退单原因改（走改单）或退回草稿重报。 */
    CUSTOMS_REJECTED("海关退单"),
    /** 已改单：改单后重提。 */
    AMENDED("已改单"),
    /** 已取消：终态。取消必须给原因。 */
    CANCELLED("已取消");

    private final String label;

    DeclarationStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static DeclarationStatus parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException("未知的报关单状态：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }

    /** 唯一真相：合法转换表。任何不在表内的跳转都必须被拒绝。 */
    public static List<DeclarationStatus> allowedNext(DeclarationStatus current) {
      return switch (current) {
        case DRAFT -> List.of(DRAFT, PENDING_REVIEW, CANCELLED);
        case PENDING_REVIEW -> List.of(REVIEWED, REJECTED, DRAFT, CANCELLED);
        case REVIEWED -> List.of(SUBMITTED, PENDING_REVIEW, DRAFT, CANCELLED);
        case REJECTED -> List.of(DRAFT, PENDING_REVIEW, CANCELLED);
        case SUBMITTED -> List.of(CLEARED, CUSTOMS_REJECTED, AMENDED);
        case CUSTOMS_REJECTED -> List.of(DRAFT, AMENDED, CANCELLED);
        case AMENDED -> List.of(CLEARED, CUSTOMS_REJECTED, AMENDED);
        case CLEARED -> List.of();
        case CANCELLED -> List.of();
      };
    }
  }

  /**
   * 贸易方式（海关监管方式代码）。决定退税率与监管条件，DB 层 NOT NULL + CHECK。
   *
   * <p>逐值对应 {@code customs_declaration.trade_mode} 的 CHECK 约束。
   */
  public enum TradeMode {
    GENERAL_TRADE("一般贸易"),
    PROCESSING_TRADE("进料加工"),
    PROCESSING_SUPPLIED("来料加工"),
    TRANSIT_TRADE("转口贸易"),
    CROSS_BORDER_B2B("跨境电商 B2B"),
    CROSS_BORDER_B2C("跨境电商 B2C"),
    SAMPLES("样品"),
    GIFT("礼品"),
    REPAIR("维修"),
    PERSONAL("个人物品");

    private final String label;

    TradeMode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static TradeMode parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException("未知的贸易方式：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /** 运输方式。与 {@code customs_declaration.transport_mode} 的 CHECK 逐值对应。 */
  public enum DeclarationTransportMode {
    SEA("海运"),
    AIR("空运"),
    RAIL("铁路"),
    EXPRESS("快递");

    private final String label;

    DeclarationTransportMode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static DeclarationTransportMode parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException("未知的运输方式：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /**
   * 申报要素齐备性状态机。
   *
   * <p>与 {@code customs_declaration_line.declaration_element_status} 的 CHECK 逐值对应。 复核通过时 DB
   * 层逐行检查：只有 {@code MANUAL_COMPLETE} 或 {@code MODULE_3_7_1_1_COMPLETE} 才放行 ——
   * 因为「申报要素不齐」是被退单与处罚最常见的原因之一。
   *
   * <p>{@code MODULE_*} 两种取值现在就可用：结构已经留好，3.7.1.1 交付后由它回填即可。
   */
  public enum DeclarationElementStatus {
    /** 人工录入、尚未标齐备（默认）。 */
    MANUAL_PENDING("人工录入·未齐备"),
    /** 人工录入、已标齐备。 */
    MANUAL_COMPLETE("人工录入·已齐备"),
    /** 3.7.1.1 字典回填、尚未标齐备。上游未交付，当前不会有数据落这里。 */
    MODULE_3_7_1_1_PENDING("3.7.1.1 字典·未齐备"),
    /** 3.7.1.1 字典回填、已标齐备。上游未交付，当前不会有数据落这里。 */
    MODULE_3_7_1_1_COMPLETE("3.7.1.1 字典·已齐备");

    private final String label;

    DeclarationElementStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static DeclarationElementStatus parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException("未知的申报要素状态：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /** 三单据类型。与 {@code customs_document.document_type} 的 CHECK 逐值对应。 */
  public enum CustomsDocumentType {
    /** 报关单。 */
    CUSTOMS_DECLARATION("报关单"),
    /** 装箱单。 */
    PACKING_LIST("装箱单"),
    /** 商业发票。 */
    COMMERCIAL_INVOICE("商业发票");

    private final String label;

    CustomsDocumentType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static CustomsDocumentType parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException("未知的单据类型：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /**
   * 报关行提交结果。与 {@code customs_submission.submission_result} 的 CHECK 逐值对应。
   *
   * <p>区分「已受理」与「已放行」：受理只表示报关行收下了这份单子，货还在等海关。
   */
  public enum CustomsSubmissionResult {
    /** 已受理：报关行收下了，等待海关处理。 */
    ACCEPTED("已受理"),
    /** 待定：报关行给的中间态。 */
    PENDING("待定"),
    /** 已放行。 */
    CLEARED("已放行"),
    /** 被退单。 */
    REJECTED("被退单");

    private final String label;

    CustomsSubmissionResult(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static CustomsSubmissionResult parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException("未知的提交结果：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /**
   * 海关结果类型 —— 蓝图点名的三种。与 {@code customs_result.result_type} 的 CHECK 逐值对应。
   *
   * <p>三种结果都必须落审计（谁、何时、原结果、新结果、原因），所以每种结果一行。
   */
  public enum CustomsResultType {
    /** 放行。终态。 */
    CLEARED("放行"),
    /** 退单：必须按原因改单或退回草稿重报。 */
    REJECTED("退单"),
    /** 改单：改单后重提。 */
    AMENDED("改单");

    private final String label;

    CustomsResultType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static CustomsResultType parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException("未知的海关结果类型：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  // ================================================================ 响应 DTO

  /**
   * 维度/依赖的可用性声明（照抄 10.4.1.1 的 {@code FirstMileDimension} 形状，前端同一套渲染）。
   *
   * <p>{@code available=false} 时前端显式渲染成「不可用 + 归属模块 + 原因」， 而不是给一个点下去没反应的选项。
   */
  public record CustomsDimension(
      String value,
      String label,
      boolean available,
      String ownerModule,
      String href,
      String note) {}

  /** 状态机的合法转换，前端据此只给合法的按钮。 */
  public record CustomsTransition(
      String from, String fromLabel, String to, String toLabel, String action, String note) {}

  /** 下拉词表项。 */
  public record CustomsStatusOption(String value, String label) {}

  public record CustomsTradeModeOption(String value, String label) {}

  public record CustomsTransportModeOption(String value, String label) {}

  public record CustomsDocumentTypeOption(String value, String label) {}

  public record CustomsElementStatusOption(String value, String label) {}

  /**
   * 报关单能力清单：贸易方式、运输方式、状态机、**未交付依赖逐条声明**与三条必须讲清的口径。
   *
   * <p>前端据此渲染来源下拉、单据类型、提示文案 —— 未交付的依赖 <b>显式报不可用并给出归属模块</b>，而不是假装可用。
   */
  public record CustomsCapabilities(
      List<CustomsDimension> dependencies,
      List<CustomsTransition> transitions,
      List<CustomsStatusOption> statusOptions,
      List<CustomsTradeModeOption> tradeModes,
      List<CustomsTransportModeOption> transportModes,
      List<CustomsDocumentTypeOption> documentTypes,
      List<CustomsElementStatusOption> elementStatuses,
      String reviewGateNote,
      String rateSnapshotNote,
      String oneShipmentOneDeclarationNote,
      String hsCodeNote) {}

  /** 报关单列表行。金额与汇率一律 {@link BigDecimal}（DB 侧 NUMERIC），全程禁浮点。 */
  public record CustomsDeclarationRow(
      UUID id,
      String declarationCode,
      UUID sourceShipmentId,
      String sourceShipmentCode,
      String tradeMode,
      String tradeModeLabel,
      String exporterName,
      String consigneeName,
      String consigneeCountryCode,
      String destinationCountryCode,
      String transportMode,
      String declarationCurrency,
      BigDecimal declarationValue,
      String baseCurrency,
      BigDecimal exchangeRate,
      Integer exchangeRateScale,
      LocalDate exchangeRateDate,
      BigDecimal declarationValueBase,
      Integer totalLineCount,
      Integer reviewRound,
      String status,
      String statusLabel,
      Integer amendmentCount,
      OffsetDateTime reviewedAt,
      OffsetDateTime submittedAt,
      OffsetDateTime clearedAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      long version) {}

  /** 报关单明细行。申报要素是可后补的 jsonb 对象。 */
  public record CustomsLineRow(
      UUID id,
      Integer lineNo,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID sourceLineId,
      Integer sourceLineNo,
      String declaredItemName,
      String hsCode,
      String hsCodeSource,
      Boolean hsCodeVerified,
      Map<String, Object> declarationElements,
      String declarationElementStatus,
      String declarationElementStatusLabel,
      String declarationElementsSource,
      String declarationElementsRef,
      String specMaterial,
      String usage,
      String brand,
      String modelNo,
      String originCountryCode,
      BigDecimal quantity,
      String uom,
      BigDecimal declaredUnitValue,
      BigDecimal declaredValue,
      BigDecimal grossWeightKg,
      BigDecimal netWeightKg,
      BigDecimal volumeCbm,
      Integer packageCount) {}

  /** 三单据行。content 是<b>生成那一刻的单据内容快照</b>，读取时不再按当前明细重算。 */
  public record CustomsDocumentRow(
      UUID id,
      String documentType,
      String documentTypeLabel,
      String documentNo,
      Integer documentVersion,
      String currency,
      BigDecimal totalAmount,
      Map<String, Object> content,
      String status,
      UUID supersedesDocumentId,
      OffsetDateTime issuedAt,
      UUID preparedBy,
      String sourceSystem,
      String sourceReference,
      OffsetDateTime createdAt) {}

  /** 报关行提交回执行（一次提交 = 一行）。 */
  public record CustomsSubmissionRow(
      UUID id,
      Integer submissionRound,
      UUID documentId,
      String submissionReference,
      String brokerName,
      String submissionResult,
      String submissionResultLabel,
      OffsetDateTime submittedAt,
      UUID submittedBy,
      String sourceSystem,
      String sourceReference,
      String resultReason) {}

  /** 海关结果回执行（放行 / 退单 / 改单各一行，逐条留痕）。 */
  public record CustomsResultRow(
      UUID id,
      UUID submissionId,
      String resultType,
      String resultTypeLabel,
      String fromResult,
      String toResult,
      String resultReference,
      String reason,
      OffsetDateTime resultAt,
      UUID actorId,
      String sourceSystem,
      String sourceReference) {}

  /** 事件时间线行（一次业务动作 = 一行，逐笔回放）。 */
  public record CustomsEventRow(
      UUID id,
      String kind,
      String fromStatus,
      String toStatus,
      UUID actorId,
      String reason,
      String notes,
      OffsetDateTime occurredAt) {}

  /** 列表汇总（**合计不受分页影响**）。 */
  public record CustomsSummary(
      long total,
      long draft,
      long pendingReview,
      long reviewed,
      long submitted,
      long cleared,
      long customsRejected,
      BigDecimal totalDeclarationValue,
      String totalDeclarationCurrency) {}

  /** 报关单列表页。 */
  public record CustomsDeclarationPage(
      CustomsSummary summary,
      List<CustomsDeclarationRow> rows,
      List<CustomsStatusOption> statusOptions,
      List<CustomsTradeModeOption> tradeModes,
      List<CustomsTransportModeOption> transportModes,
      long total,
      int page,
      int size) {}

  /** 报关单详情：头 + 明细 + 三单据 + 提交回执 + 结果回执 + 事件时间线 + 合法下一步。 */
  public record CustomsDeclarationDetail(
      CustomsDeclarationRow declaration,
      List<CustomsLineRow> lines,
      List<CustomsDocumentRow> documents,
      List<CustomsSubmissionRow> submissions,
      List<CustomsResultRow> results,
      List<CustomsEventRow> events,
      List<CustomsTransition> transitions,
      String sourceShipmentNote,
      String reviewGateNote) {}

  /** 写动作返回：ID + 单号 + 状态 + 版本 + 可读提示。 */
  public record CustomsSaved(
      UUID id,
      String declarationCode,
      String status,
      String statusLabel,
      long version,
      String message) {}

  // ================================================================ 请求 DTO

  /** 建单请求。来源固定是 10.4.1.1 头程发运单（一票货只报一次关）。 */
  public record CustomsLineRequest(
      UUID sourceLineId,
      String declaredItemName,
      String hsCode,
      String declarationElementStatus,
      Map<String, Object> declarationElements,
      String specMaterial,
      String usage,
      String brand,
      String modelNo,
      String originCountryCode,
      BigDecimal quantity,
      String uom,
      BigDecimal declaredUnitValue,
      BigDecimal declaredValue,
      BigDecimal grossWeightKg,
      BigDecimal netWeightKg,
      BigDecimal volumeCbm,
      Integer packageCount) {}

  public record CustomsCreateRequest(
      UUID sourceShipmentId,
      UUID legalEntityId,
      String tradeMode,
      String exporterName,
      String exporterCountryCode,
      String consigneeName,
      String consigneeCountryCode,
      String iorName,
      String iorCountryCode,
      String brokerName,
      String brokerCode,
      String brokerContact,
      String destinationCountryCode,
      String destinationCustomsDistrict,
      String departurePort,
      String destinationPort,
      String transportMode,
      String billOfLadingNo,
      String declarationCurrency,
      String baseCurrency,
      BigDecimal exchangeRate,
      Integer exchangeRateScale,
      LocalDate exchangeRateDate,
      String notes,
      List<CustomsLineRequest> lines) {}

  /** 改单请求（仅草稿与复核退回可改；已复核之后一行都不让改，否则「复核」就变成走过场）。 */
  public record CustomsUpdateRequest(
      Long expectedVersion,
      String tradeMode,
      String exporterName,
      String exporterCountryCode,
      String consigneeName,
      String consigneeCountryCode,
      String iorName,
      String iorCountryCode,
      String brokerName,
      String brokerCode,
      String brokerContact,
      String destinationCountryCode,
      String destinationCustomsDistrict,
      String departurePort,
      String destinationPort,
      String transportMode,
      String billOfLadingNo,
      String declarationCurrency,
      String baseCurrency,
      BigDecimal exchangeRate,
      Integer exchangeRateScale,
      LocalDate exchangeRateDate,
      String notes,
      List<CustomsLineRequest> lines) {}

  /** 送人工复核。 */
  public record CustomsSubmitForReviewRequest(String notes) {}

  /** 人工复核通过。 */
  public record CustomsReviewRequest(String reviewNotes) {}

  /** 复核退回：必须给原因。 */
  public record CustomsRejectReviewRequest(String reason) {}

  /** 撤回复核（回到待复核）。 */
  public record CustomsWithdrawReviewRequest(String reason) {}

  /** 生成三单据：一次生成一份，按类型递增版本。 */
  public record CustomsGenerateDocumentRequest(String documentType, String documentNo) {}

  /** 定稿单据：定稿后才能提交报关行。 */
  public record CustomsFinalizeDocumentRequest(String documentNo) {}

  /** 提交报关行：只能从 REVIEWED 出发（DB 级复核闸）。 */
  public record CustomsSubmitRequest(
      String submissionReference,
      String brokerName,
      String submissionResult,
      String resultReason) {}

  /** 登记海关结果：放行 / 退单 / 改单。 */
  public record CustomsResultRequest(
      String resultType,
      String resultReference,
      String reason,
      OffsetDateTime resultAt,
      String sourceSystem,
      String sourceReference) {}

  /** 改单：登记改单结果并把单退回草稿重改。 */
  public record CustomsAmendRequest(String reason, String resultReference) {}

  /** 取消：必须给原因。 */
  public record CustomsCancelRequest(String reason) {}

  /** 列表查询条件。全部为 null 即不过滤。 */
  public record CustomsQuery(
      List<String> status,
      String tradeMode,
      String transportMode,
      String destinationCountryCode,
      UUID sourceShipmentId,
      Boolean pendingReviewOnly,
      String keyword,
      String sort,
      int page,
      int size) {}
}
