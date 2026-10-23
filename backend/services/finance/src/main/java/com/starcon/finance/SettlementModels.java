package com.starcon.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 12.3.1.1 结算单管理的传输模型。
 *
 * <p>蓝图 12-finance.md 原文：
 *
 * <blockquote>
 *
 * 职责：管理平台结算单：结算周期、店铺、平台账单汇总（销售额、退款、佣金、FBA 费用、广告费、赔付、其他调整）、结算金额、到账状态。 输入：平台结算报告同步（2
 * 同步）；人工上传账单；结算周期配置。 输出：结算单 + 汇总项 + 明细行 + 与我方账的差额。 关系：上游 = 2 渠道与店铺；下游 = 12.3.2.1 对账、12.2.1.1
 * 收款、13 利润。 <b>平台账单是财务事实的重要外部来源，需保留原始文件与解析版本。</b>
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code FinanceSettlement*} 前缀，与既有服务的类型名零重叠 —— 契约被 codegen
 * 合并进一个扁平文件，同名后者覆盖前者且不报冲突（见 tools/codegen/catalog-contracts.py 的跨服务重名检测）。
 *
 * <p><b>金额一律 BigDecimal + NUMERIC</b>：本文件里没有一个 {@code double}。金额 {@code
 * numeric(19,4)}，数量 {@code numeric(19,6)}。
 *
 * <p><b>币种分开</b>：任何汇总都按币种分组返回（{@link FinanceSettlementCurrencySummary}），
 * USD 与 EUR 加起来不是任何一个金额 —— 这是全仓一致的纪律。
 */
public final class SettlementModels {

  private SettlementModels() {}

  /** 金额小数位（与同服务 12.1.1.1 / 12.2.1.1 一致）。 */
  public static final int MONEY_SCALE = 4;

  // ================================================================ 枚举

  /**
   * 平台账单的汇总类别。
   *
   * <p>蓝图原文：「平台账单汇总（销售额、退款、佣金、FBA 费用、广告费、赔付、其他调整）」—— 本枚举就是这句话的**逐项**落地。
   *
   * <p><b>符号约定</b>（跨境结算单最容易算错的一格，见 V345 文件头第 3 节）：每个类别带一个**固定符号**，
   * 解析阶段把账单里写的那个数**归一**成「对结算金额的贡献」。
   *
   * <ul>
   *   <li>销售额 / 赔付 → <b>+</b>（钱进我们口袋）
   *   <li>退款 / 佣金 / FBA 费用 / 广告费 → <b>−</b>（钱出我们口袋）
   *   <li>其他调整 → <b>按账单原样</b>（可正可负）
   * </ul>
   *
   * <p>⇒ 各汇总项之和 == 结算金额，这条等式由 DB 触发器保证（V345 的派生链），不是靠人记。
   */
  public enum SettlementCategory {
    SALES("销售额", 1),
    REFUND("退款", -1),
    COMMISSION("佣金", -1),
    FBA_FEE("FBA 费用", -1),
    ADVERTISING("广告费", -1),
    COMPENSATION("赔付", 1),
    ADJUSTMENT("其他调整", 0);

    private final String label;

    /** 固定符号：+1 / −1 / 0（0 = 按账单原样，符号由账单自己说）。 */
    private final int sign;

    SettlementCategory(String label, int sign) {
      this.label = label;
      this.sign = sign;
    }

    public String label() {
      return label;
    }

    public int sign() {
      return sign;
    }

    /**
     * 把账单里写的那个数**归一**成对结算金额的贡献。
     *
     * <p>⚠️ 退款 / 佣金 / FBA 费用 / 广告费在各家账单里有的写负数、有的写正数（绝对值）。
     * 这里统一按**绝对值**取，再乘上类别的固定符号 —— 这样「结算金额 = 销售额 − 退款 − 佣金 −
     * FBA 费用 − 广告费 + 赔付 + 调整」在任何一份账单上都成立。
     */
    public BigDecimal normalize(BigDecimal raw) {
      if (raw == null) return null;
      if (sign == 0) return raw.setScale(MONEY_SCALE, java.math.RoundingMode.HALF_UP);
      return raw.abs().multiply(BigDecimal.valueOf(sign)).setScale(MONEY_SCALE, java.math.RoundingMode.HALF_UP);
    }

    /**
     * 账单里可能出现的写法 → 类别。
     *
     * <p>⚠️ 各家平台的账单列名不一样（「销售额」「sales」「order_sales」……），
     * 所以解析器带一张**别名表**；表里没有的写法按**行级错误**拒收（跳过那一行并说明原因），
     * 绝不猜一个类别 —— 猜错类别等于把一笔退款记成销售额。
     */
    public static SettlementCategory parse(String raw) {
      if (raw == null) return null;
      String key = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
      if (key.isEmpty()) return null;
      switch (key) {
        case "sales", "sale", "order_sales", "sales_amount", "gross_sales", "total_sales",
            "销售额", "销售", "销售总额", "总销售额", "销售收入" -> {
          return SALES;
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
            "storage_fee", "storage_fees", "fba_storage", "fba 费用", "fba费用", "仓储费",
            "配送费", "物流费" -> {
          return FBA_FEE;
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
        default -> {
          return null;
        }
      }
    }
  }

  /** 结算单状态（到账状态）。 */
  public enum SettlementStatus {
    /** 待到账（还没到）。 */
    PENDING("待到账"),
    /** 部分到账（到了但没到齐）。 */
    PARTIAL("部分到账"),
    /** 已到账（到齐了）。终态。 */
    SETTLED("已到账"),
    /** 已作废（红冲的等价物）。终态。 */
    VOID("已作废"),
    /** 已被同一账单的新解析版本取代。终态，不进汇总口径。 */
    SUPERSEDED("已被新版本取代");

    private final String label;

    SettlementStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 终态：不可再改，只能新建一张结算单。 */
    public boolean terminal() {
      return this == SETTLED || this == VOID || this == SUPERSEDED;
    }

    /** 进汇总口径（在册）。 */
    public boolean counted() {
      return this != VOID && this != SUPERSEDED;
    }
  }

  /**
   * 结算单来源。
   *
   * <p>⚠️ <b>词表里有一个值当前不可产生</b>：{@link #PLATFORM_SYNC}（平台结算报告自动同步）的上游是
   * 「卷 02 同步」，全仓**没有**这个端点。它的值在 DB 与本枚举里都存在（上游交付时不需要改结构），
   * 但服务层会拒绝用它建单，并在能力声明里写明归属模块 —— <b>绝不造一份「同步来的」假账单顶替它</b>。
   */
  public enum SettlementSource {
    /** 人工上传账单（CSV / JSON）：真解析、真入库。 */
    MANUAL_UPLOAD("人工上传账单"),
    /** 平台结算报告自动同步：上游「卷 02 同步」未交付该端点，当前不可产生。 */
    PLATFORM_SYNC("平台结算报告同步"),
    /** 人工登记（连明细行一起手工录）。 */
    MANUAL("人工登记");

    private final String label;

    SettlementSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 这个来源**当前**能不能在本服务里产生结算单。 */
    public boolean producible() {
      return this != PLATFORM_SYNC;
    }
  }

  /** 结算周期类型。 */
  public enum SettlementCycleType {
    WEEKLY("周结"),
    BIWEEKLY("双周结"),
    MONTHLY("月结");

    private final String label;

    SettlementCycleType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 平台账单的导入格式。 */
  public enum SettlementImportFormat {
    CSV("CSV"),
    JSON("JSON");

    private final String label;

    SettlementImportFormat(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了会怎样」。 */
  public record FinanceSettlementCapability(
      String module,
      String name,
      boolean available,
      String basis,
      String ownerModule,
      String detail) {}

  /** 下游缺口：谁没交付、归谁。 */
  public record FinanceSettlementDownstreamGap(
      String module,
      String name,
      String ownerService,
      boolean available,
      String group,
      String detail) {}

  /** 12.3.1.1 的能力声明。 */
  public record FinanceSettlementCapabilities(
      List<FinanceSettlementCapability> capabilities,
      List<FinanceSettlementDownstreamGap> downstreamGaps) {}

  // ================================================================ 结算单

  /** 结算单头。 */
  public record FinanceSettlementRow(
      UUID id,
      String settlementNo,
      UUID shopId,
      String shopCode,
      String shopName,
      String platformCode,
      LocalDate periodStart,
      LocalDate periodEnd,
      String currency,
      /** 结算金额：**由汇总项求和派生**（DB 触发器维护），服务层写不进去。 */
      BigDecimal settlementAmount,
      SettlementStatus status,
      /** 预计到账时间（来自结算周期配置）。 */
      @Schema(nullable = true) OffsetDateTime expectedAt,
      /** 实际到账时间（timestamptz，核心字段）。 */
      @Schema(nullable = true) OffsetDateTime arrivedAt,
      BigDecimal receivedAmount,
      SettlementSource source,
      String sourceSystem,
      @Schema(nullable = true) String sourceReference,
      /** 原始文件记录（同一张表的版本号）。 */
      @Schema(nullable = true) UUID sourceFileId,
      int parseVersion,
      @Schema(nullable = true) UUID cycleId,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 结算汇总项（**由明细行派生**）。 */
  public record FinanceSettlementSummaryRow(
      UUID id,
      UUID settlementId,
      SettlementCategory category,
      /** 类别的可读名（服务端给出，前端不自己翻译）。 */
      String categoryLabel,
      /** 对结算金额的贡献（正=增加、负=减少）。 */
      BigDecimal amount,
      long lineCount) {}

  /** 结算明细行。 */
  public record FinanceSettlementLineRow(
      UUID id,
      UUID settlementId,
      int lineNo,
      SettlementCategory category,
      String categoryLabel,
      /** 归一后的贡献。 */
      BigDecimal amount,
      /** 账单里写的那个数（原样保留）。 */
      BigDecimal rawAmount,
      String currency,
      @Schema(nullable = true) BigDecimal quantity,
      @Schema(nullable = true) LocalDate occurredOn,
      @Schema(nullable = true) String externalReference,
      String description) {}

  /** 单据事件（append-only 举证凭据）。 */
  public record FinanceSettlementEventRow(
      UUID id,
      String eventType,
      String fromStatus,
      String toStatus,
      String reason,
      @Schema(nullable = true) UUID actorId,
      OffsetDateTime occurredAt) {}

  /**
   * 原始文件与解析版本记录。
   *
   * <p>蓝图加粗句：「<b>平台账单是财务事实的重要外部来源，需保留原始文件与解析版本。</b>」
   */
  public record FinanceSettlementSourceFileRow(
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

  /** 结算单详情：一次调用返回整屏。 */
  public record FinanceSettlementDetail(
      FinanceSettlementRow settlement,
      List<FinanceSettlementSummaryRow> summary,
      List<FinanceSettlementLineRow> lines,
      List<FinanceSettlementEventRow> events,
      @Schema(nullable = true) FinanceSettlementSourceFileRow sourceFile) {}

  /**
   * 按币种的汇总。
   *
   * <p>⚠️ **不跨币种相加**：USD 与 EUR 加起来不是任何一个金额。
   */
  public record FinanceSettlementCurrencySummary(
      String currency,
      BigDecimal settlementAmount,
      BigDecimal receivedAmount,
      long count,
      long pendingCount) {}

  /** 结算单列表页。 */
  public record FinanceSettlementPage(
      List<FinanceSettlementRow> items,
      int page,
      int size,
      long total,
      List<FinanceSettlementCurrencySummary> summaryByCurrency) {}

  // ================================================================ 差额

  /** 我方应收侧按来源的分组（**真的从 finance_receivable 算出来的**）。 */
  public record FinanceSettlementReceivableBreakdown(
      String source,
      long count,
      BigDecimal amount,
      BigDecimal openAmount) {}

  /**
   * 「与我方账的差额」。
   *
   * <p>蓝图输出之一：「结算单 + 汇总项 + 明细行 + <b>与我方账的差额</b>」。
   *
   * <p>⚠️ <b>这个差额是实时算出来的，不是存下来的快照。</b>
   *
   * <ul>
   *   <li><b>平台侧</b>：结算单的结算金额与实际到账金额（由汇总项派生）。
   *   <li><b>我方应收侧</b>：同店铺、同结算周期、同币种的 {@code finance_receivable}
   *       （12.1.1.1，同服务同库），按来源分组报出来。
   *   <li><b>我方收款侧</b>：按 {@code finance_receipt.settlement_no = 结算单号} 关联。
   *       ⚠️ 12.2.1.1 的 {@code settlement_no} 目前由 CHECK 钉死恒为 NULL ⇒ 这一侧现在恒为 0，
   *       等 12.2.1.1 放开那条 CHECK 后**自动**就有数据了（读路径已经铺好）。
   *   <li><b>不做「金额相同即匹配」</b>：卷 12 硬约束 —— 金额相同只是候选，
   *       必须结合订单号、日期、类型。所以收款侧**只按结算单号关联**，不做金额猜测。
   * </ul>
   */
  public record FinanceSettlementDifference(
      UUID settlementId,
      String settlementNo,
      String currency,
      LocalDate periodStart,
      LocalDate periodEnd,
      /** 平台侧：结算金额。 */
      BigDecimal platformSettlementAmount,
      /** 平台侧：实际到账金额。 */
      BigDecimal platformReceivedAmount,
      /** 我方应收侧：应收总额。 */
      BigDecimal receivableAmount,
      /** 我方应收侧：未核销余额（应收 − 已核销）。 */
      BigDecimal receivableOpenAmount,
      long receivableCount,
      List<FinanceSettlementReceivableBreakdown> receivableBySource,
      /** 差额 = 平台结算金额 − 我方应收未核销余额。 */
      BigDecimal differenceVsReceivable,
      /** 按结算单号关联到的收款单数（12.2.1.1 放开 CHECK 后才会有数）。 */
      long linkedReceiptCount,
      /** 按结算单号关联到的收款金额。 */
      BigDecimal linkedReceiptAmount,
      /** 收款侧为什么现在没数 —— 说清归属模块与接上动作。 */
      String receiptLinkNote,
      /** 自动对账任务归属说明。 */
      String reconciliationNote,
      /** 差额计算基准时刻（UTC）。 */
      OffsetDateTime computedAt) {}

  // ================================================================ 结算周期配置

  /** 结算周期配置（店铺 → 周期类型 + 到账滞后天数）。 */
  public record FinanceSettlementCycleRow(
      UUID id,
      UUID shopId,
      String shopCode,
      String platformCode,
      SettlementCycleType cycleType,
      String cycleTypeLabel,
      int payoutLagDays,
      @Schema(nullable = true) String currency,
      String status,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  // ================================================================ 店铺（上游 channel）

  /**
   * 店铺选项（来自 channel 服务 2.3.1.1）。
   *
   * <p>⚠️ 这是**跨服务只读**的形状：finance 侧只经 channel 的授权 HTTP API 读，
   * 不直连对方库、不建跨库视图、不建跨库外键（AGENTS.md 模块边界纪律）。
   */
  public record FinanceSettlementShopOption(
      UUID id, String code, String name, String platformCode, String platformName, String status) {}

  // ================================================================ 查询参数

  /**
   * 列表查询条件。
   *
   * <p>可选属性显式带 {@code | null}：仓库启用了 {@code exactOptionalPropertyTypes}，
   * 少了它的话「省略」与「显式传 null」在类型上不是一回事。
   */
  public record SettlementQuery(
      @Schema(nullable = true) SettlementStatus status,
      @Schema(nullable = true) UUID shopId,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) SettlementSource source,
      @Schema(nullable = true) String platformCode,
      @Schema(nullable = true) LocalDate periodFrom,
      @Schema(nullable = true) LocalDate periodTo,
      @Schema(nullable = true) String keyword,
      int page,
      int size) {}

  /** 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。 */
  public record FinanceSettlementOptions(
      List<String> statuses,
      List<String> sources,
      List<String> currencies,
      List<String> platforms,
      List<FinanceSettlementShopOption> shops) {}

  // ================================================================ 输入

  /**
   * 人工登记一张结算单（连明细行一起）。
   *
   * <p>⚠️ {@code source} 只允许 MANUAL_UPLOAD / MANUAL：平台结算报告同步的上游
   * （卷 02 同步）**未交付**，服务端会拒绝并写明归属模块。
   */
  public record FinanceSettlementInput(
      @Schema(nullable = true) SettlementSource source,
      @Schema(nullable = true) String sourceSystem,
      @Schema(nullable = true) String sourceReference,
      UUID shopId,
      LocalDate periodStart,
      LocalDate periodEnd,
      String currency,
      List<FinanceSettlementLineInput> lines,
      @Schema(nullable = true) String note) {}

  /** 一行结算明细。 */
  public record FinanceSettlementLineInput(
      SettlementCategory category,
      BigDecimal amount,
      @Schema(nullable = true) BigDecimal quantity,
      @Schema(nullable = true) LocalDate occurredOn,
      @Schema(nullable = true) String externalReference,
      @Schema(nullable = true) String description) {}

  /**
   * 平台账单导入。
   *
   * @param sourceSystem 来源系统（amazon / shopify / …），与来源流水号组成幂等键。
   * @param fileKey 账单身份（平台结算报告号 / 上传文件名）：同一份账单重解析时版本递增。
   * @param content 原始内容（CSV 文本或 JSON 文本）—— **原文会原样落库**。
   */
  public record FinanceSettlementImportInput(
      @Schema(nullable = true) SettlementSource source,
      String sourceSystem,
      String fileKey,
      SettlementImportFormat format,
      String content,
      UUID shopId,
      LocalDate periodStart,
      LocalDate periodEnd,
      String currency,
      @Schema(nullable = true) String note) {}

  /**
   * 平台账单导入的结果。
   *
   * @param scanned 这次解析出多少条
   * @param imported 新建了多少行明细
   * @param skipped 没进来多少条（行内数据有问题）
   * @param duplicate 是否是**幂等命中**（同一份账单同样的内容已经导过）
   * @param parseVersion 本次解析版本号（同一份账单每次重解析 +1）
   * @param settlement 产出的结算单（幂等命中时是已存在的那张）
   * @param warnings 逐条说明：哪几条没进来、为什么。**不能吞掉** ——
   *     「导入 0 条」如果不说清原因，现场会以为文件是空的。
   */
  public record FinanceSettlementImportResult(
      int scanned,
      int imported,
      int skipped,
      boolean duplicate,
      int parseVersion,
      @Schema(nullable = true) FinanceSettlementRow settlement,
      List<String> warnings) {}

  /** 登记到账。 */
  public record FinanceSettlementArrivalInput(
      /** 实际到账时间（**必填**，timestamptz）。 */
      OffsetDateTime arrivedAt,
      BigDecimal receivedAmount,
      @Schema(nullable = true) String note) {}

  /** 作废。理由必填。 */
  public record FinanceSettlementVoidInput(String reason) {}

  /** 结算周期配置。 */
  public record FinanceSettlementCycleInput(
      UUID shopId,
      SettlementCycleType cycleType,
      int payoutLagDays,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) String note) {}
}
