package com.starcon.finance;

import com.starcon.finance.SettlementModels.SettlementCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 12.3.1.2 交易明细的传输模型。
 *
 * <p>蓝图 12-finance.md（12.3.1.2 交易明细）原文：
 *
 * <blockquote>
 *
 * 职责：管理结算单下的逐笔明细：订单销售、退款、费用（佣金/仓储/配送/广告）、赔付、调整、代扣税，保留外部流水号与币种；<b>允许无订单行的费用类明细</b>。
 * 输入：平台交易报告解析结果；<code>idempotency_key</code>。 输出：明细行 + 汇总校验（明细合计 = 结算单汇总）。 关系：上游 = 12.3.1.1；
 * 下游 = 12.3.2.1 对账、13 利润。 <b>不强制每行都有订单</b>是硬约束——佣金、罚款、月度调整天然无订单行，硬要匹配会产生大量虚假异常。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code FinanceTransaction*} 前缀，与既有服务的类型名零重叠 —— 契约被 codegen 合并进一个扁平文件，
 * 同名后者覆盖前者且不报冲突（见 tools/codegen/catalog-contracts.py 的跨服务重名检测）。
 *
 * <p><b>金额一律 BigDecimal + NUMERIC</b>：本文件里没有一个 {@code double}。金额 {@code numeric(19,4)}，数量 {@code
 * numeric(19,6)}。
 *
 * <p><b>两个词表的关系。</b>{@link FinanceTransactionType}（交易类型，10 个）比 {@link SettlementCategory}（结算汇总类别，8 个）
 * 更细：仓储费与配送费在汇总层都落进 FBA 费用桶。两个词表的**配对关系由 DB 层的 CHECK 钉死**（V348），本文件里的 {@link
 * FinanceTransactionType#category()} 与那条 CHECK 一一对应。
 */
public final class TransactionModels {

  private TransactionModels() {}

  /** 金额小数位（与同服务 12.1.1.1 / 12.2.1.1 / 12.3.1.1 一致）。 */
  public static final int MONEY_SCALE = 4;

  /** 数量小数位（与 DB 的 numeric(19,6) 一致）。 */
  public static final int QUANTITY_SCALE = 6;

  // ================================================================ 枚举

  /**
   * **交易类型**（12.3.1.2 的分类词表）。
   *
   * <p>蓝图原文：「订单销售、退款、费用（佣金/仓储/配送/广告）、赔付、调整、代扣税」—— 本枚举就是这句话的**逐项**落地。
   *
   * <p>⚠️ <b>它比 12.3.1.1 的结算类别更细</b>：V345 的类别是**汇总桶**（仓储费与配送费同落 FBA_FEE 桶），
   * 而本模块要分的是**交易类型**。两者的配对由 DB 层 CHECK 钉死（V348 的 {@code finance_settlement_line_type_category_check}），
   * 服务层与直连 SQL 都写不出「明细说仓储费、汇总却算进别的桶」这种错位。
   *
   * <p><b>符号约定</b>沿用 12.3.1.1：销售额/赔付为正、退款/佣金/FBA 费用/广告费/代扣税为负、其他调整按账单原样。
   * 归一发生在**登记/解析阶段**，库里同时留 {@code raw_amount}（账单里写的那个数）与 {@code amount}（归一后的贡献）。
   */
  public enum FinanceTransactionType {
    ORDER_SALES("订单销售", SettlementCategory.SALES, 1),
    REFUND("退款", SettlementCategory.REFUND, -1),
    COMMISSION("佣金", SettlementCategory.COMMISSION, -1),
    FBA_FEE("FBA 费用", SettlementCategory.FBA_FEE, -1),
    STORAGE_FEE("仓储费", SettlementCategory.FBA_FEE, -1),
    SHIPPING_FEE("配送费", SettlementCategory.FBA_FEE, -1),
    ADVERTISING("广告费", SettlementCategory.ADVERTISING, -1),
    COMPENSATION("赔付", SettlementCategory.COMPENSATION, 1),
    ADJUSTMENT("其他调整", SettlementCategory.ADJUSTMENT, 0),
    WITHHOLDING_TAX("代扣税", SettlementCategory.TAX, -1);

    private final String label;
    private final SettlementCategory category;
    private final int sign;

    FinanceTransactionType(String label, SettlementCategory category, int sign) {
      this.label = label;
      this.category = category;
      this.sign = sign;
    }

    public String label() {
      return label;
    }

    /** 这个交易类型归到哪个**结算汇总类别**（DB 层 CHECK 与此一一对应）。 */
    public SettlementCategory category() {
      return category;
    }

    /** 固定符号：+1 / −1 / 0（0 = 按账单原样，符号由账单自己说）。 */
    public int sign() {
      return sign;
    }

    /**
     * 把账单里写的那个数**归一**成对结算金额的贡献。
     *
     * <p>⚠️ 退款 / 佣金 / FBA 费用 / 广告费 / 代扣税在各家账单里有的写负数、有的写正数（绝对值）。
     * 这里统一按**绝对值**取，再乘上固定符号 —— 这样「结算金额 = 销售额 − 退款 − 佣金 − FBA 费用 − 广告费 − 代扣税 + 赔付 + 调整」
     * 在任何一份账单上都成立。
     */
    public BigDecimal normalize(BigDecimal raw) {
      if (raw == null) return null;
      if (sign == 0) return raw.setScale(MONEY_SCALE, java.math.RoundingMode.HALF_UP);
      return raw.abs().multiply(BigDecimal.valueOf(sign)).setScale(MONEY_SCALE, java.math.RoundingMode.HALF_UP);
    }

    /**
     * 交易报告里可能出现的写法 → 交易类型。
     *
     * <p>⚠️ 各家平台的报告列名不一样（「销售额」「sales」「order_sales」……），所以解析器带一张**别名表**；
     * 表里没有的写法按**行级错误**拒收（跳过那一行并说明原因），绝不猜一个类型 ——
     * 猜错类型等于把一笔退款记成销售额。
     */
    public static FinanceTransactionType parse(String raw) {
      if (raw == null) return null;
      String key = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
      if (key.isEmpty()) return null;
      switch (key) {
        case "order_sales", "order_sale", "sales", "sale", "sales_amount", "gross_sales",
            "total_sales", "order_revenue", "订单销售", "销售额", "销售", "销售总额", "总销售额",
            "销售收入", "订单收入" -> {
          return ORDER_SALES;
        }
        case "refund", "refunds", "refund_amount", "refunds_amount", "returns", "return_amount",
            "退款", "退款额", "退款金额", "退货退款" -> {
          return REFUND;
        }
        case "commission", "commissions", "commission_amount", "referral_fee", "referral_fees",
            "selling_fee", "sales_commission", "佣金", "销售佣金", "平台佣金", "推荐费" -> {
          return COMMISSION;
        }
        case "fba_fee", "fba_fees", "fba_fee_amount", "fulfillment_fee", "fulfillment_fees",
            "fba 费用", "fba费用" -> {
          return FBA_FEE;
        }
        case "storage_fee", "storage_fees", "storage", "storage_charge", "仓储费", "仓储费用",
            "库存费", "配送费", "物流费", "shipping_fee", "shipping_fees", "shipping",
            "配送费用" -> {
          return STORAGE_FEE;
        }
        case "advertising", "advertising_amount", "ad", "ads", "ad_spend", "ad_spend_amount",
            "advertising_spend", "广告费", "广告", "广告支出", "推广费" -> {
          return ADVERTISING;
        }
        case "compensation", "compensations", "reimbursement", "claim", "claims", "claim_amount",
            "赔付", "赔偿", "补偿", "赔偿金" -> {
          return COMPENSATION;
        }
        case "adjustment", "adjustments", "other", "other_adjustment", "other_adjustments",
            "adjustment_amount", "misc", "调整", "其他调整", "其它调整", "杂项" -> {
          return ADJUSTMENT;
        }
        case "tax", "withholding_tax", "withholding", "vat", "marketplace_facilitator_tax",
            "marketplace_facilitator_tax_fee", "sales_tax", "代扣税", "代扣税费", "增值税",
            "税费", "销售税", "平台代扣税" -> {
          return WITHHOLDING_TAX;
        }
        default -> {
          return null;
        }
      }
    }
  }

  /**
   * 交易明细的来源。
   *
   * <p>⚠️ <b>词表里有一个值当前不可产生</b>：{@link #PLATFORM_SYNC}（平台交易报告自动同步）的上游是
   * 「卷 02 同步」，全仓**没有**这个端点。它的值在 DB 与本枚举里都存在（上游交付时不需要改结构），
   * 但服务层会拒绝用它登记明细，并在能力声明里写明归属模块 ——
   * <b>绝不造一份「同步来的」假交易报告顶替它</b>。
   */
  public enum FinanceTransactionSource {
    /** 人工上传平台交易报告（CSV / JSON）：真解析、真入库。 */
    MANUAL_UPLOAD("人工上传交易报告"),
    /** 平台交易报告自动同步：上游「卷 02 同步」未交付该端点，当前不可产生。 */
    PLATFORM_SYNC("平台交易报告同步"),
    /** 人工逐笔登记。 */
    MANUAL("人工登记");

    private final String label;

    FinanceTransactionSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 这个来源**当前**能不能在本服务里产生交易明细。 */
    public boolean producible() {
      return this != PLATFORM_SYNC;
    }
  }

  /** 交易报告的导入格式。 */
  public enum FinanceTransactionImportFormat {
    CSV("CSV"),
    JSON("JSON");

    private final String label;

    FinanceTransactionImportFormat(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了会怎样」。 */
  public record FinanceTransactionCapability(
      String module,
      String name,
      boolean available,
      String basis,
      String ownerModule,
      String detail) {}

  /** 下游缺口：谁没交付、归谁。 */
  public record FinanceTransactionDownstreamGap(
      String module,
      String name,
      String ownerService,
      boolean available,
      String group,
      String detail) {}

  /** 12.3.1.2 的能力声明。 */
  public record FinanceTransactionCapabilities(
      List<FinanceTransactionCapability> capabilities,
      List<FinanceTransactionDownstreamGap> downstreamGaps) {}

  // ================================================================ 明细行

  /**
   * 一条交易明细（结算单下的一笔）。
   *
   * <p>⚠️ {@code orderReference} 与 {@code orderLineReference} **可空**：费用类明细天然没有订单行
   * （蓝图加粗句「允许无订单行的费用类明细」）。
   */
  public record FinanceTransactionRow(
      UUID id,
      UUID settlementId,
      String settlementNo,
      String shopCode,
      String shopName,
      String platformCode,
      LocalDate periodStart,
      LocalDate periodEnd,
      int lineNo,
      FinanceTransactionType transactionType,
      String transactionTypeLabel,
      /** 归到哪个结算汇总类别（服务端给出，页面不自己翻译）。 */
      SettlementCategory category,
      String categoryLabel,
      /** 归一后的贡献（正=增加结算金额，负=减少）。 */
      BigDecimal amount,
      /** 账单里写的那个数（原样保留，便于逐行核对）。 */
      BigDecimal rawAmount,
      String currency,
      @Schema(nullable = true) BigDecimal quantity,
      @Schema(nullable = true) LocalDate occurredOn,
      /** 外部流水号（平台交易号）。 */
      @Schema(nullable = true) String externalReference,
      /** 订单号（**可空**：费用类明细天然没有订单行）。 */
      @Schema(nullable = true) String orderReference,
      /** 订单行引用（**可空**：给了就必须同时给订单号）。 */
      @Schema(nullable = true) String orderLineReference,
      /** 幂等键（蓝图输入里点名的那个）。 */
      @Schema(nullable = true) String idempotencyKey,
      FinanceTransactionSource source,
      String sourceLabel,
      String description) {}

  /** 明细行的登记上下文（单条详情用）。 */
  public record FinanceTransactionDetail(
      FinanceTransactionRow transaction,
      /** 这条明细所属结算单的当前状态与金额（读出来，不重算）。 */
      String settlementStatus,
      BigDecimal settlementAmount,
      BigDecimal settlementReceivedAmount,
      /** 该结算单的汇总项（由明细行派生）。 */
      List<FinanceTransactionSummaryRow> summary) {}

  /** 结算汇总项（**由明细行派生**，与 12.3.1.1 同一张表）。 */
  public record FinanceTransactionSummaryRow(
      SettlementCategory category,
      String categoryLabel,
      BigDecimal amount,
      long lineCount) {}

  // ================================================================ 列表与汇总

  /** 明细行列表页。 */
  public record FinanceTransactionPage(
      List<FinanceTransactionRow> items,
      int page,
      int size,
      long total,
      /** 按币种的汇总（**不跨币种相加**）。覆盖整个筛选结果集，不是当前页。 */
      List<FinanceTransactionCurrencySummary> summaryByCurrency,
      /** 按交易类型的汇总。覆盖整个筛选结果集，不是当前页。 */
      List<FinanceTransactionTypeSummary> summaryByType) {}

  /** 按币种的汇总。 */
  public record FinanceTransactionCurrencySummary(
      String currency,
      BigDecimal amount,
      long count,
      /** 其中没有订单行的笔数（费用类明细天然如此，不是异常）。 */
      long orderlessCount) {}

  /** 按交易类型的汇总。 */
  public record FinanceTransactionTypeSummary(
      FinanceTransactionType transactionType,
      String transactionTypeLabel,
      SettlementCategory category,
      String categoryLabel,
      BigDecimal amount,
      long count,
      long orderlessCount) {}

  // ================================================================ 汇总校验

  /**
   * 一个类别上的汇总校验。
   *
   * @param lineAmount 明细行按该类别求和（**实时算出来的**）
   * @param summaryAmount 结算汇总项里该类别的金额（DB 派生）
   * @param matched 两个数是否相等
   */
  public record FinanceTransactionCategoryCheck(
      SettlementCategory category,
      String categoryLabel,
      BigDecimal lineAmount,
      BigDecimal summaryAmount,
      long lineCount,
      long summaryLineCount,
      boolean matched) {}

  /**
   * 汇总校验：<b>明细合计 = 结算单汇总</b>。
   *
   * <p>蓝图输出之一：「明细行 + <b>汇总校验（明细合计 = 结算单汇总）</b>」。
   *
   * <p>⚠️ 这个结果是**实时算出来的**，不是存下来的快照：
   *
   * <ul>
   *   <li>「明细合计 = 汇总项合计 = 结算金额」在 12.3.1.1 的 DB 派生链里**结构上**恒成立
   *       （V345 的三段派生链）；本模块做的是**把它算出来给人看**，并且**逐项核对**下面这些
   *       **不恒成立**的东西 ——
   *   <li><b>分类口径</b>：每个类别的明细合计 vs 汇总项金额；
   *   <li><b>笔数</b>：每个类别的明细笔数 vs 汇总项的 line_count；
   *   <li><b>币种</b>：明细行币种是否都等于结算单币种（混币种会把合计算成废数）；
   *   <li><b>符号</b>：归一后的贡献符号是否与类别约定一致（退款为正 = 归一被绕过）；
   *   <li><b>周期</b>：明细行日期是否落在结算周期内；
   *   <li><b>订单行</b>：多少笔明细没有订单行（费用类天然如此，**是正常状态不是异常**）。
   * </ul>
   */
  public record FinanceTransactionReconciliation(
      UUID settlementId,
      String settlementNo,
      /** 所属结算单的状态（终态后不可再追加明细）。 */
      String settlementStatus,
      String currency,
      LocalDate periodStart,
      LocalDate periodEnd,
      /** 明细行合计（实时算）。 */
      BigDecimal lineTotal,
      /** 汇总项合计（DB 派生）。 */
      BigDecimal summaryTotal,
      /** 结算单头的结算金额（DB 派生）。 */
      BigDecimal settlementAmount,
      /** 三者是否相等。 */
      boolean balanced,
      List<FinanceTransactionCategoryCheck> categories,
      long lineCount,
      /** 没有订单行的明细笔数（**不是异常**：佣金/罚款/月度调整天然无订单行）。 */
      long orderlessLineCount,
      /** 其中属于费用类的笔数（真正「天然无订单」的那些）。 */
      long orderlessFeeLineCount,
      /** 销售类但没有订单号的笔数（可复核项，不是硬错误）。 */
      long salesWithoutOrderLineCount,
      /** 明细行币种与结算单币种不一致的笔数。 */
      long currencyMismatchLineCount,
      /** 明细行日期落在结算周期之外的笔数。 */
      long outsidePeriodLineCount,
      /** 归一后符号与类别约定不一致的笔数。 */
      long signViolationLineCount,
      /** 逐条说明（人话）。 */
      List<String> issues,
      /** 校验基准时刻（UTC）。 */
      OffsetDateTime checkedAt) {}

  // ================================================================ 筛选项与查询

  /**
   * 结算单选项（筛选用）。
   *
   * <p>⚠️ 必须带 {@code id}（uuid）：筛选与汇总校验都是**按结算单 id** 走的，
   * 只给单号的话页面没法把选择变成请求参数。
   */
  public record FinanceTransactionSettlementOption(
      UUID id,
      String settlementNo,
      String shopCode,
      String shopName,
      LocalDate periodStart,
      LocalDate periodEnd,
      String currency,
      String status) {}

  /** 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。 */
  public record FinanceTransactionOptions(
      List<String> transactionTypes,
      List<String> categories,
      List<String> currencies,
      List<String> sources,
      List<FinanceTransactionSettlementOption> settlements,
      List<String> platforms) {}

  /**
   * 列表查询条件。
   *
   * <p>可选属性显式带 {@code | null}：仓库启用了 {@code exactOptionalPropertyTypes}，
   * 少了它的话「省略」与「显式传 null」在类型上不是一回事。
   */
  public record FinanceTransactionQuery(
      @Schema(nullable = true) UUID settlementId,
      @Schema(nullable = true) UUID shopId,
      @Schema(nullable = true) FinanceTransactionType transactionType,
      @Schema(nullable = true) SettlementCategory category,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) FinanceTransactionSource source,
      @Schema(nullable = true) String platformCode,
      @Schema(nullable = true) LocalDate occurredFrom,
      @Schema(nullable = true) LocalDate occurredTo,
      /** 只看有订单行的 / 只看没订单行的。 */
      @Schema(nullable = true) Boolean hasOrder,
      @Schema(nullable = true) String keyword,
      int page,
      int size) {}

  // ================================================================ 输入

  /**
   * 登记一条交易明细。
   *
   * <p>⚠️ {@code source} 只允许 MANUAL / MANUAL_UPLOAD：平台交易报告同步的上游（卷 02 同步）**未交付**，
   * 服务端会拒绝并写明归属模块。
   *
   * <p>⚠️ {@code currency} 留空 = 继承结算单币种；填了且与结算单不一致 ⇒ **拒绝** ——
   * 结算金额是**单币种**的金额，混币种会把「明细合计 = 结算单汇总」算成废数。
   *
   * <p>⚠️ {@code orderReference} / {@code orderLineReference} **可空**：费用类明细天然没有订单行。
   */
  public record FinanceTransactionInput(
      @Schema(nullable = true) FinanceTransactionSource source,
      UUID settlementId,
      FinanceTransactionType transactionType,
      BigDecimal amount,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) BigDecimal quantity,
      @Schema(nullable = true) LocalDate occurredOn,
      @Schema(nullable = true) String externalReference,
      @Schema(nullable = true) String orderReference,
      @Schema(nullable = true) String orderLineReference,
      @Schema(nullable = true) String idempotencyKey,
      @Schema(nullable = true) String description) {}

  /**
   * 批量登记交易明细（同一张结算单）。
   *
   * <p>⚠️ 批量**不**放松任何一条校验：每一行都单独验（金额、币种、幂等键、订单行约束）。
   */
  public record FinanceTransactionBatchInput(
      @Schema(nullable = true) FinanceTransactionSource source,
      UUID settlementId,
      List<FinanceTransactionLineInput> lines) {}

  /** 批量登记里的一行。 */
  public record FinanceTransactionLineInput(
      FinanceTransactionType transactionType,
      BigDecimal amount,
      /** 币种（可空 = 继承结算单币种；填了且与结算单不一致 ⇒ 拒绝）。 */
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) BigDecimal quantity,
      @Schema(nullable = true) LocalDate occurredOn,
      @Schema(nullable = true) String externalReference,
      @Schema(nullable = true) String orderReference,
      @Schema(nullable = true) String orderLineReference,
      @Schema(nullable = true) String idempotencyKey,
      @Schema(nullable = true) String description) {}

  /**
   * 平台交易报告导入。
   *
   * @param settlementId 追加到哪张结算单（**必须已存在**且在册）。
   * @param fileKey 交易报告身份（平台交易报告号 / 上传文件名）：同一份报告重解析时版本递增。
   * @param content 原始内容（CSV 文本或 JSON 文本）—— **原文会原样落库**。
   */
  public record FinanceTransactionImportInput(
      @Schema(nullable = true) FinanceTransactionSource source,
      UUID settlementId,
      String fileKey,
      FinanceTransactionImportFormat format,
      String content,
      @Schema(nullable = true) String note) {}

  /**
   * 平台交易报告导入的结果。
   *
   * @param scanned 这次解析出多少条
   * @param imported 新建了多少行明细
   * @param duplicated 多少条因为**幂等键已存在**被跳过（重复导入不是错误）
   * @param skipped 多少条没进来（行内数据有问题）
   * @param parseVersion 本次解析版本号（同一份报告每次重解析 +1）
   * @param warnings 逐条说明：哪几条没进来、为什么。**不能吞掉** ——
   *     「导入 0 条」如果不说清原因，现场会以为文件是空的。
   */
  public record FinanceTransactionImportResult(
      int scanned,
      int imported,
      int duplicated,
      int skipped,
      int parseVersion,
      @Schema(nullable = true) FinanceTransactionSourceFileRow sourceFile,
      List<String> warnings) {}

  /**
   * 平台交易报告的原文与解析版本。
   *
   * <p>⚠️ 与 12.3.1.1 的 {@code finance_settlement_source_file} **刻意分开**（那是**平台结算报告**）。
   */
  public record FinanceTransactionSourceFileRow(
      UUID id,
      String fileKey,
      String fileName,
      String contentType,
      String contentHash,
      long byteSize,
      int parseVersion,
      String parseStatus,
      List<String> parseWarnings,
      OffsetDateTime parsedAt,
      OffsetDateTime createdAt) {}
}
