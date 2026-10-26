package com.starcon.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 12.7.1.1 账户管理的传输模型。
 *
 * <p>蓝图 12-finance.md 原文：
 *
 * <blockquote>
 *
 * 职责：管理资金账户：银行账户、支付平台账户、平台备付金账户，记录币种、账号、余额、开户主体（法人）、账户类型。 输入：账户定义；余额同步或手工更新；关联法人。 输出：账户档案 + 余额历史。 关系：上游 = 17 法人；下游 = 12.2 收付款、12.7.1.2 对账。 <b>账户必须绑定法人</b>，跨法人混用资金账户会导致收入归属错误。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code FinanceFundAccount*} 前缀 —— 与同 L3 的 12.7.2.1 资金分析
 * （{@code FinanceFundAnalysis*}）、以及既有 12.1.x / 12.2.x 类型名零重叠。
 *
 * <p><b>本模块的五条口径纪律</b>（每一条都有对应实现）：
 *
 * <ol>
 *   <li><b>账户必须绑定法人。</b>法人来自 identity 的 {@code GET /identity/legal-entities}
 *       （跨服务**只读**端口），落库前<b>实时校验</b>；未登记的法人报错，不静默接受。</li>
 *   <li><b>余额历史是 append-only。</b>每次同步 / 手工更新都<b>追加</b>一条记录，
 *       历史记录不许 UPDATE / DELETE（DB 层触发器 + 收回运行角色权限钉死）。</li>
 *   <li><b>单账户单币种，跨币种绝不相加。</b>汇总一律按币种分组，
 *       本模块<b>不做</b>任何跨币种折算（汇率归 12.7.2.1）。</li>
 *   <li><b>账号脱敏。</b>原始账号不落库、不写日志；只存掩码 + 后四位，接口返回同样脱敏。</li>
 *   <li><b>手工更新必须留痕。</b>谁（{@code operatorUserId}）、何时（{@code recordedAt}）、
 *       为什么（{@code reason}）、改前改后（{@code previousAmount} → {@code balanceAmount}）。</li>
 * </ol>
 */
public final class FundAccountModels {

  private FundAccountModels() {}

  /** 金额小数位（与 12.1.x / 12.2.x / 12.7.x 一致）。 */
  public static final int MONEY_SCALE = 4;

  // ================================================================ 枚举

  /**
   * 资金账户类型 —— **受控词表**（不是自由文本）。
   *
   * <p>蓝图 12-finance.md：「管理资金账户：<b>银行账户、支付平台账户、平台备付金账户</b>」。
   */
  public enum FinanceFundAccountType {
    /** 银行账户（本公司在银行开的户）。 */
    BANK("银行账户"),
    /** 支付平台账户（PayPal / Stripe / 连连 等支付机构的商户账户）。 */
    PAYMENT_PLATFORM("支付平台账户"),
    /** 平台备付金账户（Amazon 等平台的备付金 / 保证金账户）。 */
    PLATFORM_RESERVE("平台备付金账户");

    private final String label;

    FinanceFundAccountType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 资金账户状态。 */
  public enum FinanceFundAccountStatus {
    /** 启用：可登记余额。 */
    ACTIVE("启用"),
    /** 停用：不再接受新的余额登记（资金凭据不做物理删除）。 */
    INACTIVE("停用");

    private final String label;

    FinanceFundAccountStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 余额记录的来源。 */
  public enum FinanceFundBalanceSource {
    /** 建档初始余额（账户开立时的起始余额）。 */
    INITIAL("建档初始"),
    /** 余额同步（来自上游系统 / 平台对账单）。 */
    SYNC("余额同步"),
    /** 手工更新（人改了余额 ⇒ 必须写理由）。 */
    MANUAL("手工更新");

    private final String label;

    FinanceFundBalanceSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了会怎样」。 */
  public record FinanceFundAccountCapability(
      String module, String name, boolean available, String basis, String ownerModule, String detail) {}

  /** 下游缺口：谁没交付、归谁。 */
  public record FinanceFundAccountDownstreamGap(
      String module, String name, String ownerService, boolean available, String group, String detail) {}

  /** 12.7.1.1 的能力声明。 */
  public record FinanceFundAccountCapabilities(
      List<FinanceFundAccountCapability> capabilities,
      List<FinanceFundAccountDownstreamGap> downstreamGaps) {}

  // ================================================================ 筛选项

  /** 受控词表里的一项（账户类型）。 */
  public record FinanceFundAccountOption(String code, String label) {}

  /** 法人候选项（来自 identity 的授权只读端点，只用于显示与选择）。 */
  public record FinanceFundLegalEntityOption(
      UUID id, String code, String legalName, String status, String baseCurrency) {}

  /**
   * 筛选项下拉。
   *
   * @param currencies 币种候选：取自**本租户已建账户上真实出现过的币种**（distinct 查询），不是常量。
   * @param legalEntitiesAvailable 法人清单<b>读到了没有</b> —— 「读不到」≠「这个租户没有法人」。
   */
  public record FinanceFundAccountOptions(
      List<FinanceFundAccountOption> accountTypes,
      List<String> currencies,
      boolean legalEntitiesAvailable,
      List<FinanceFundLegalEntityOption> legalEntities,
      String legalEntityNote,
      String note) {}

  // ================================================================ 账户档案

  /** 一条资金账户档案。 */
  public record FinanceFundAccountRow(
      UUID id,
      String accountNo,
      String accountName,
      String accountType,
      String accountTypeLabel,
      String currency,
      UUID legalEntityId,
      String legalEntityCode,
      String legalEntityName,
      String bankName,
      /** 账号掩码（原始账号不落库、不返回）。 */
      String accountNumberMasked,
      /** 账号后四位。 */
      String accountNumberTail,
      String accountHolder,
      String status,
      String statusLabel,
      /** 当前余额（**当前值**，最新一条余额历史推导而来）。 */
      BigDecimal currentBalance,
      /** 当前余额对应的事实时刻。 */
      OffsetDateTime currentBalanceAt,
      /** 余额历史条数。 */
      long balanceRecordCount,
      String note,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 一个账户的详情：档案 + 最近若干条余额历史。 */
  public record FinanceFundAccountDetail(
      FinanceFundAccountRow account,
      List<FinanceFundAccountBalanceRow> recentBalances,
      long balanceTotal) {}

  /** 账户档案页（真分页）。 */
  public record FinanceFundAccountPage(
      List<FinanceFundAccountRow> items, int page, int size, long total) {}

  // ================================================================ 余额历史

  /** 一条余额历史（append-only：只追加，不许改写）。 */
  public record FinanceFundAccountBalanceRow(
      UUID id,
      UUID accountId,
      String currency,
      UUID legalEntityId,
      String legalEntityCode,
      String legalEntityName,
      /** 改后余额。 */
      BigDecimal balanceAmount,
      /** 改前余额。 */
      BigDecimal previousAmount,
      String changeSource,
      String changeSourceLabel,
      /** 手工更新的理由（MANUAL 必填）。 */
      String reason,
      String sourceSystem,
      String sourceReference,
      /** 谁登记的（已认证会话里的用户 id，不是客户端自称）。 */
      UUID operatorUserId,
      /** 事实发生时刻。 */
      OffsetDateTime recordedAt,
      OffsetDateTime createdAt) {}

  /** 余额历史页（真分页）。 */
  public record FinanceFundAccountBalancePage(
      List<FinanceFundAccountBalanceRow> items, int page, int size, long total) {}

  // ================================================================ 按币种汇总

  /** 某个法人在某个币种下的余额合计。 */
  public record FinanceFundLegalEntityBalance(
      UUID legalEntityId,
      String legalEntityCode,
      String legalEntityName,
      long accountCount,
      BigDecimal totalBalance) {}

  /** 一个币种下的余额合计（**跨币种绝不相加** ⇒ 汇总只到币种级）。 */
  public record FinanceFundCurrencyBalanceTotal(
      String currency,
      long accountCount,
      long activeAccountCount,
      BigDecimal totalBalance,
      List<FinanceFundLegalEntityBalance> byLegalEntity) {}

  /**
   * 余额汇总（按币种分组）。
   *
   * @param crossCurrencyTotalAvailable 恒为 {@code false}：本模块<b>不</b>做跨币种折算，
   *     所以「全部账户加在一起」这个数**没有意义**，如实给 {@code null} 而不是给一个假数。
   * @param conversionOwnerModule 折算能力的归属模块（12.7.2.1 资金分析经 catalog 读汇率）。
   */
  public record FinanceFundCurrencyBalanceSummary(
      String note,
      boolean crossCurrencyTotalAvailable,
      @Schema(nullable = true) BigDecimal crossCurrencyTotal,
      String conversionOwnerModule,
      List<FinanceFundCurrencyBalanceTotal> totals) {}

  // ================================================================ 输入

  /**
   * 建账户。
   *
   * @param accountNo 租户内的账户编号；**省略**（null / 空）= 由系统生成 {@code FA-xxxxxxxxxxxx}。
   * @param accountNumber 原始账号（敏感）：<b>只在请求作用域内存活</b>，落库只留掩码 + 后四位。
   * @param initialBalance 建档初始余额（可空 = 0）：账户开立时的余额事实，会写成第一条余额历史。
   */
  public record FinanceFundAccountCreateInput(
      @Pattern(regexp = "[A-Z0-9_-]{1,48}") String accountNo,
      @NotBlank @Size(max = 120) String accountName,
      @NotNull FinanceFundAccountType accountType,
      @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
      @NotNull UUID legalEntityId,
      @Size(max = 120) String bankName,
      @Size(max = 64) String accountNumber,
      @Size(max = 120) String accountHolder,
      @Size(max = 500) String note,
      BigDecimal initialBalance) {}

  /**
   * 改账户档案。
   *
   * <p>⚠️ <b>币种与账户编号不在这里</b>：单账户单币种是硬约束，账户编号是身份，改了就换了。
   *
   * @param accountNumber {@code null}/省略 = <b>不改</b>账号；空串 = <b>清除</b>账号（掩码与后四位置空）。
   * @param legalEntityId {@code null}/省略 = 不换绑法人；给了就<b>实时校验</b>后换绑。
   */
  public record FinanceFundAccountUpdateInput(
      @NotBlank @Size(max = 120) String accountName,
      @NotNull FinanceFundAccountType accountType,
      UUID legalEntityId,
      @Size(max = 120) String bankName,
      @Size(max = 64) String accountNumber,
      @Size(max = 120) String accountHolder,
      @Size(max = 500) String note) {}

  /**
   * 登记一次余额。
   *
   * @param reason 手工更新（MANUAL）<b>必填</b>；同步（SYNC）留空。
   * @param sourceSystem / sourceReference 同步（SYNC）<b>必填</b>（幂等键的两半）。
   * @param recordedAt 事实发生时刻；省略 = 现在。
   */
  public record FinanceFundAccountBalanceInput(
      @NotNull BigDecimal balanceAmount,
      @NotNull FinanceFundBalanceSource changeSource,
      @Size(max = 500) String reason,
      @Size(max = 48) String sourceSystem,
      @Size(max = 120) String sourceReference,
      OffsetDateTime recordedAt) {}

  // ================================================================ 查询参数

  /**
   * 账户列表查询条件。
   *
   * <p>可选属性显式带 {@code | null}：仓库启用了 {@code exactOptionalPropertyTypes}，
   * 少了它的话「省略」与「显式传 null」在类型上不是一回事。
   */
  public record FinanceFundAccountQuery(
      @Schema(nullable = true) FinanceFundAccountType accountType,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) UUID legalEntityId,
      @Schema(nullable = true) FinanceFundAccountStatus status,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort,
      @Schema(nullable = true) String direction,
      int page,
      int size) {}
}
