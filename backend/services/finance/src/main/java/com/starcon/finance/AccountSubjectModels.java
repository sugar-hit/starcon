package com.starcon.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 12.5.1.1 会计科目的传输模型。
 *
 * <p>蓝图 12-finance.md 原文：
 *
 * <blockquote>
 *
 * 职责：维护法人账簿的会计科目表：科目编码、名称、类别（资产/负债/权益/成本/损益）、余额方向、是否明细科目、关联税务属性；支持科目映射到业务事件。 输入：科目定义；业务事件映射（如发货 → 收入 +
 * 结转成本）。 输出：科目表 + 映射规则。 关系：上游 = 用户会计体系；下游 = 12.5.2.1 凭证、12.5.2.2 报表。**科目体系是用户自定的**，系统提供模板但必须允许完全自定义。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code FinanceAccountSubject*} 前缀，与既有服务的类型名零重叠 —— 契约被 codegen 合并进一个扁平文件，同名后者覆盖前者且不报冲突。
 *
 * <p><b>没有金额。</b>科目是配置，凭证（12.5.2.1）才是金额的载体 ⇒ 本文件里唯一的数值是税率 {@code BigDecimal}（{@code numeric(9,6)}，0.13 = 13%），**没有一个 double**。
 *
 * <p><b>类别与余额方向不联动。</b>「累计折旧」是资产类却记贷方余额 —— 把两者钉死会堵死真实账，所以两个枚举彼此独立，用户可自由改。
 */
public final class AccountSubjectModels {

  private AccountSubjectModels() {}

  // ================================================================ 枚举

  /** 科目类别：资产 / 负债 / 权益 / 成本 / 损益。 */
  public enum AccountSubjectCategory {
    /** 资产。 */
    ASSET("资产"),
    /** 负债。 */
    LIABILITY("负债"),
    /** 权益（所有者权益）。 */
    EQUITY("权益"),
    /** 成本。 */
    COST("成本"),
    /** 损益。 */
    PROFIT_LOSS("损益");

    private final String label;

    AccountSubjectCategory(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 余额方向：借方 / 贷方。 */
  public enum AccountBalanceDirection {
    /** 借方余额。 */
    DEBIT("借方"),
    /** 贷方余额。 */
    CREDIT("贷方");

    private final String label;

    AccountBalanceDirection(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 关联税务属性。 */
  public enum AccountTaxTreatment {
    /** 应税。 */
    TAXABLE("应税"),
    /** 免税。 */
    EXEMPT("免税"),
    /** 零税率。 */
    ZERO_RATED("零税率"),
    /** 不征税。 */
    NON_TAXABLE("不征税"),
    /** 视同应税。 */
    REVERSE_CHARGE("视同应税");

    private final String label;

    AccountTaxTreatment(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 科目状态。 */
  public enum AccountSubjectStatus {
    /** 在用。 */
    ACTIVE("在用"),
    /** 停用（**不是删除**：历史凭证仍要能引用到它）。 */
    DISABLED("停用");

    private final String label;

    AccountSubjectStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 映射规则状态。 */
  public enum AccountMappingStatus {
    /** 草稿：分录行可以随便改。 */
    DRAFT("草稿"),
    /** 启用：借贷齐备才让启用，且启用后改行仍要保持齐备。 */
    ACTIVE("启用"),
    /** 停用。 */
    INACTIVE("停用");

    private final String label;

    AccountMappingStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 映射行的借贷方向。 */
  public enum AccountMappingDirection {
    /** 借方。 */
    DEBIT("借方"),
    /** 贷方。 */
    CREDIT("贷方");

    private final String label;

    AccountMappingDirection(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 映射行的金额口径：这个分录行取业务事件的哪个量。
   *
   * <p>⚠️ 模板**不固定金额**，所以这里给的是「取哪个量」的约定，金额相等由下游 12.5.2.1 按业务事件实算。
   */
  public enum AccountAmountBasis {
    /** 商品金额。 */
    ORDER_ITEM_AMOUNT("商品金额"),
    /** 商品成本。 */
    ITEM_COST("商品成本"),
    /** 运费。 */
    SHIPPING_AMOUNT("运费"),
    /** 税额。 */
    TAX_AMOUNT("税额"),
    /** 平台费用。 */
    PLATFORM_FEE("平台费用"),
    /** 退款金额。 */
    REFUND_AMOUNT("退款金额"),
    /** 调整金额。 */
    ADJUSTMENT_AMOUNT("调整金额"),
    /** 其他（自定义口径）。 */
    OTHER("其他");

    private final String label;

    AccountAmountBasis(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了会怎样」。 */
  public record FinanceAccountSubjectCapability(
      String module,
      String name,
      boolean available,
      String basis,
      String ownerModule,
      String detail) {}

  /** 下游缺口：谁没交付、归谁。 */
  public record FinanceAccountSubjectDownstreamGap(
      String module,
      String name,
      String ownerService,
      boolean available,
      String group,
      String detail) {}

  /** 12.5.1.1 的能力声明。 */
  public record FinanceAccountSubjectCapabilities(
      List<FinanceAccountSubjectCapability> capabilities,
      List<FinanceAccountSubjectDownstreamGap> downstreamGaps) {}

  // ================================================================ 科目

  /** 科目行（一行 = 一个科目编码）。 */
  public record FinanceAccountSubjectRow(
      UUID id,
      UUID accountingBookId,
      UUID legalEntityId,
      /** 科目编码：**租户内唯一**，下游 12.5.2.1 按它软引用。 */
      String subjectCode,
      String subjectName,
      AccountSubjectCategory category,
      String categoryLabel,
      AccountBalanceDirection balanceDirection,
      String balanceDirectionLabel,
      /** 是否明细科目：false = 汇总科目（不能被凭证直接记账）。 */
      boolean detail,
      /** 上级科目编码（软引用自身）。 */
      @Schema(nullable = true) String parentSubjectCode,
      /** 层级（由 DB 触发器派生）。 */
      int level,
      AccountTaxTreatment taxTreatment,
      String taxTreatmentLabel,
      /** 税率（0.13 = 13%）；为空 = 不固定税率。 */
      @Schema(nullable = true) BigDecimal taxRate,
      String taxNote,
      AccountSubjectStatus status,
      String statusLabel,
      /** CUSTOM 用户自定义 / TEMPLATE 从系统模板导入。 */
      String source,
      @Schema(nullable = true) String templateKey,
      /** 有多少条映射规则引用了这个科目。 */
      long mappingReferenceCount,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 新增科目。
   *
   * <p>⚠️ 科目编码是**唯一且不可改**的引用键 —— 建完就不能再改，只能停用后另建。
   */
  public record FinanceAccountSubjectInput(
      UUID accountingBookId,
      UUID legalEntityId,
      String subjectCode,
      String subjectName,
      AccountSubjectCategory category,
      AccountBalanceDirection balanceDirection,
      Boolean detail,
      @Schema(nullable = true) String parentSubjectCode,
      @Schema(nullable = true) AccountTaxTreatment taxTreatment,
      @Schema(nullable = true) BigDecimal taxRate,
      @Schema(nullable = true) String taxNote) {}

  /**
   * 改科目。
   *
   * <p>⚠️ **没有 subjectCode 字段**：编码一经入账不可改（下游凭证按编码软引用它）。
   */
  public record FinanceAccountSubjectUpdateInput(
      String subjectName,
      AccountSubjectCategory category,
      AccountBalanceDirection balanceDirection,
      Boolean detail,
      @Schema(nullable = true) String parentSubjectCode,
      @Schema(nullable = true) AccountTaxTreatment taxTreatment,
      @Schema(nullable = true) BigDecimal taxRate,
      @Schema(nullable = true) String taxNote) {}

  /** 变更留痕（append-only）。 */
  public record FinanceAccountSubjectEventRow(
      UUID id,
      String targetType,
      UUID targetId,
      String eventType,
      String fieldName,
      String fromValue,
      String toValue,
      String reason,
      @Schema(nullable = true) UUID actorId,
      OffsetDateTime occurredAt) {}

  /** 引用了某科目的映射规则（详情页用）。 */
  public record FinanceAccountSubjectMappingRef(
      UUID mappingId,
      String eventType,
      String eventLabel,
      String status,
      String direction,
      String amountBasis) {}

  /** 科目详情：科目 + 变更留痕 + 引用它的映射。 */
  public record FinanceAccountSubjectDetail(
      FinanceAccountSubjectRow subject,
      List<FinanceAccountSubjectEventRow> events,
      List<FinanceAccountSubjectMappingRef> mappingReferences) {}

  /** 按科目编码查（下游 12.5.2.1 的集成点：编码租户内唯一 ⇒ 唯一命中）。 */
  public record FinanceAccountSubjectByCodeResult(
      boolean found,
      @Schema(nullable = true) FinanceAccountSubjectRow subject,
      String basis) {}

  /** 分类汇总（本模块没有金额，汇总是**科目笔数**）。 */
  public record FinanceAccountSubjectCategorySummary(
      AccountSubjectCategory category,
      String categoryLabel,
      long count,
      long detailCount,
      long groupCount) {}

  /** 科目列表页。 */
  public record FinanceAccountSubjectPage(
      List<FinanceAccountSubjectRow> items,
      int page,
      int size,
      long total,
      /** 汇总覆盖**整个筛选结果集**，不是当前页。 */
      List<FinanceAccountSubjectCategorySummary> summaryByCategory) {}

  /** 列表查询条件。 */
  public record AccountSubjectQuery(
      @Schema(nullable = true) UUID accountingBookId,
      @Schema(nullable = true) AccountSubjectCategory category,
      @Schema(nullable = true) AccountBalanceDirection balanceDirection,
      @Schema(nullable = true) AccountTaxTreatment taxTreatment,
      @Schema(nullable = true) AccountSubjectStatus status,
      @Schema(nullable = true) Boolean detail,
      @Schema(nullable = true) String parentSubjectCode,
      @Schema(nullable = true) String keyword,
      int page,
      int size) {}

  /** 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。 */
  public record FinanceAccountSubjectOptions(
      List<String> categories,
      List<String> balanceDirections,
      List<String> taxTreatments,
      List<String> statuses,
      List<String> sources,
      /** 真实出现过的账簿 ID（去重）。 */
      List<UUID> accountingBookIds,
      /** 真实出现过的上级科目编码。 */
      List<String> parentSubjectCodes) {}

  // ================================================================ 系统模板

  /** 模板里的一条科目。 */
  public record FinanceAccountSubjectTemplateRow(
      String templateKey,
      String subjectCode,
      String subjectName,
      AccountSubjectCategory category,
      String categoryLabel,
      AccountBalanceDirection balanceDirection,
      String balanceDirectionLabel,
      boolean detail,
      @Schema(nullable = true) String parentSubjectCode,
      AccountTaxTreatment taxTreatment,
      String taxTreatmentLabel,
      @Schema(nullable = true) BigDecimal taxRate,
      String taxNote,
      int sortOrder) {}

  /** 模板概要。 */
  public record FinanceAccountSubjectTemplateSummary(
      String templateKey,
      String templateName,
      long subjectCount,
      long detailCount,
      long groupCount) {}

  /** 模板目录。 */
  public record FinanceAccountSubjectTemplateCatalog(
      List<FinanceAccountSubjectTemplateSummary> templates,
      List<FinanceAccountSubjectTemplateRow> subjects,
      String basis) {}

  /** 从模板导入科目的入参。 */
  public record FinanceAccountSubjectTemplateApplyInput(
      UUID accountingBookId, UUID legalEntityId, String templateKey) {}

  /**
   * 导入结果。
   *
   * <p>⚠️ **已存在的编码会被跳过而不是覆盖** —— 模板只是导入源，用户改过的科目不能被模板冲掉。
   */
  public record FinanceAccountSubjectTemplateApplyResult(
      String templateKey,
      UUID accountingBookId,
      int created,
      int skipped,
      /** 被跳过的编码（已存在），逐个列出 —— 现场要知道「哪几个没进来」。 */
      List<String> skippedCodes) {}

  // ================================================================ 业务事件映射

  /** 映射规则的一行分录。 */
  public record FinanceAccountSubjectMappingLineRow(
      UUID id,
      UUID mappingId,
      String subjectCode,
      /** 顺带带出科目名与方向，界面不用二次请求。 */
      @Schema(nullable = true) String subjectName,
      AccountMappingDirection direction,
      String directionLabel,
      AccountAmountBasis amountBasis,
      String amountBasisLabel,
      int sequence,
      String note) {}

  /** 映射规则（头 + 分录行）。 */
  public record FinanceAccountSubjectMappingRow(
      UUID id,
      UUID accountingBookId,
      String eventType,
      String eventLabel,
      AccountMappingStatus status,
      String statusLabel,
      @Schema(nullable = true) OffsetDateTime effectiveFrom,
      String note,
      List<FinanceAccountSubjectMappingLineRow> lines,
      int debitLineCount,
      int creditLineCount,
      /** 借贷是否齐备（启用前置条件）。 */
      boolean balanced,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 映射分录行入参。 */
  public record FinanceAccountSubjectMappingLineInput(
      String subjectCode, AccountMappingDirection direction, AccountAmountBasis amountBasis, Integer sequence, String note) {}

  /** 新建映射规则（头 + 分录行一次给全）。 */
  public record FinanceAccountSubjectMappingInput(
      UUID accountingBookId,
      String eventType,
      @Schema(nullable = true) String eventLabel,
      @Schema(nullable = true) OffsetDateTime effectiveFrom,
      @Schema(nullable = true) String note,
      List<FinanceAccountSubjectMappingLineInput> lines) {}

  /**
   * 改映射规则。
   *
   * <p>⚠️ 分录行是**全量替换**语义：传进来的列表就是替换后的全部行。
   */
  public record FinanceAccountSubjectMappingUpdateInput(
      @Schema(nullable = true) String eventLabel,
      @Schema(nullable = true) OffsetDateTime effectiveFrom,
      @Schema(nullable = true) String note,
      List<FinanceAccountSubjectMappingLineInput> lines) {}

  /** 映射规则列表页。 */
  public record FinanceAccountSubjectMappingPage(
      List<FinanceAccountSubjectMappingRow> items, int page, int size, long total) {}

  /** 映射规则列表查询条件。 */
  public record AccountSubjectMappingQuery(
      @Schema(nullable = true) UUID accountingBookId,
      @Schema(nullable = true) AccountMappingStatus status,
      @Schema(nullable = true) String keyword,
      int page,
      int size) {}
}
