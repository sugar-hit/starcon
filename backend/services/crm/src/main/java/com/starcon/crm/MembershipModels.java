package com.starcon.crm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 15.6.1.1 会员与等级的传输模型。
 *
 * <p>蓝图 15 卷 15.6.1.1 原话：
 *
 * <blockquote>
 * 职责：管理会员体系：会员注册与合并、等级规则（按累计消费/订单数/复购率自动升级降级）、等级权益（折扣、免邮、优先发货），支持与平台会员体系对接或独立建档。
 * 输入：订单与消费数据（9）；等级规则配置；平台会员数据同步。
 * 输出：会员档案 + 等级变更记录；权益生效配置（→ 5 价目 / 14 促销）。
 * 关系：上游 = 9 订单、15.3.1.2 客户档案；下游 = 5 定价、14 促销、15.6.2 复购。**会员等级必须由真实消费数据驱动**，手工挂等级会导致权益成本失控。
 * </blockquote>
 *
 * <p><b>本文件最重要的四件事</b>：
 *
 * <ol>
 *   <li><b>等级来源必须写出来</b>：每条会员档案带 {@code levelOrigin}（规则算出来的 /
 *       人工覆盖）与 {@code levelReason}（凭什么落这一级）。本轮只交付
 *       <b>规则等级</b>（{@code levelOrigin} 恒为 {@code RULE}）——
 *       人工覆盖通道**未交付**，服务端**没有**任何能直接写等级的入参。</li>
 *   <li><b>「未达任何等级」是一等状态</b>：{@code levelCode = 'NONE'}，
 *       界面上**不能**画成「普通会员」。</li>
 *   <li><b>金额不跨币种相加</b>：{@code totalSpend} 只记主币种，
 *       分币种明细在 {@code amounts} 里一币种一行。</li>
 *   <li><b>阈值口径写在脸上</b>：{@code minTotalSpend} / {@code minOrderCount} /
 *       {@code minRepurchaseRate} 三条门槛的**口径**在界面上原样显示，
 *       不让运营以为「累计消费」是全平台口径。</li>
 * </ol>
 *
 * <p><b>命名</b>：统一用 {@code CrmMembership*} 前缀。全仓 grep 确认与既有类型零重叠 ——
 * 契约被 codegen 合并进一个扁平文件、同名 key 后者覆盖前者且不报冲突，
 * 故不按前缀扫而是逐个名字精确比对。
 */
public final class MembershipModels {

  private MembershipModels() {}

  // ================================================================ 枚举

  /**
   * 等级来源 —— 报表口径要能区分「规则算出来的等级」与「人工覆盖的等级」。
   *
   * <p>🔴 本轮**只交付** {@link #RULE}：蓝图加粗句「会员等级必须由真实消费数据驱动，
   * 手工挂等级会导致权益成本失控」。{@link #MANUAL_OVERRIDE} 这个取值**留着**，
   * 是为了让报表口径将来加人工覆盖时**不用改字段**，而不是说它现在可用 ——
 * DB 层的触发器 {@code guard_crm_membership_origin} 会**直接拒绝**写入它。
   */
  public enum MembershipLevelOrigin {
    RULE("规则算出来的（真实消费数据驱动）"),
    MANUAL_OVERRIDE("人工覆盖（本轮未交付）");

    private final String label;

    MembershipLevelOrigin(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 等级变更性质。 */
  public enum MembershipChangeKind {
    REGISTERED("首次建档定级"),
    UPGRADE("升级"),
    DOWNGRADE("降级");

    private final String label;

    MembershipChangeKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 入参

  /**
   * 重算会员等级的入参。
   *
   * <p>统计窗口起止必须**成对**给：只给一个会让窗口的两端对不上。
   * 都不给 ⇒ 用默认窗口（<b>全部历史</b>）。
   *
   * <p>🔴 请求体**不接受** tenantId / userId / memberKey / levelCode：
   * 租户来自已认证成员关系；等级由服务端算，**不接受**客户端指定。
   */
  public record CrmMembershipComputeInput(
      @Schema(nullable = true) String windowFrom,
      @Schema(nullable = true) String windowTo) {}

  /**
   * 等级规则（+ 等级权益）的写入入参。
   *
   * @param levelCode 等级代码（租户自定义，如 GOLD）
   * @param levelName 等级名称（如「黄金会员」）
   * @param levelRank 等级序（越大越高）
   * @param minTotalSpend 累计消费门槛（NULL = 不限这一维）；**必须**同时给币种
   * @param minTotalSpendCurrency 累计消费门槛币种
   * @param minOrderCount 订单数门槛（NULL = 不限这一维）
   * @param minRepurchaseRate 复购率门槛（NULL = 不限这一维）
   * @param discountRate 折扣率（0.05 = 减免 5%；NULL = 该等级无折扣权益）
   * @param freeShipping 免邮权益
   * @param priorityShipping 优先发货权益
   * @param effectiveFrom 生效起点（ISO-8601 带偏移量）
   * @param effectiveTo 生效终点（NULL = 至今仍生效）
   */
  public record CrmMembershipLevelRuleInput(
      @NotBlank @Size(max = 24) String levelCode,
      @NotBlank @Size(max = 64) String levelName,
      @NotNull @Min(0) @Max(9999) Integer levelRank,
      @Schema(nullable = true) @DecimalMin("0") BigDecimal minTotalSpend,
      @Schema(nullable = true) @Pattern(regexp = "[A-Za-z]{3}") String minTotalSpendCurrency,
      @Schema(nullable = true) @Min(0) Integer minOrderCount,
      @Schema(nullable = true) @DecimalMin("0") @DecimalMax("1") BigDecimal minRepurchaseRate,
      @Schema(nullable = true) @DecimalMin("0") @DecimalMax("0.999999") BigDecimal discountRate,
      @Schema(nullable = true) Boolean freeShipping,
      @Schema(nullable = true) Boolean priorityShipping,
      @Schema(nullable = true) Boolean enabled,
      @NotBlank String effectiveFrom,
      @Schema(nullable = true) String effectiveTo,
      @Schema(nullable = true) @Size(max = 600) String note) {}

  // ================================================================ 出参

  /** 能力维度：每一维都要说清「有没有、依据是什么、缺了会怎样」。 */
  public record CrmMembershipCapabilityDimension(
      String dimension, String description, boolean available, String source, String ownerModule, String impact) {}

  /** 能力声明：前端在渲染前就知道哪些维度有、哪些没有。 */
  public record CrmMembershipCapabilities(
      List<CrmMembershipCapabilityDimension> dimensions,
      List<String> levelOrigins,
      List<String> changeKinds,
      String levelRulePolicy,
      String boundaryNote,
      String highlight) {}

  /** 分币种的一行消费明细（**一币种一行**，跨币种绝不合并）。 */
  public record CrmMembershipAmount(String currency, int orderCount, BigDecimal totalSpend) {}

  /**
   * 会员档案的一行。
   *
   * @param memberKey 会员主键：已合并 = 逻辑客户号；未合并 = `来源店铺:客户标识`
   * @param levelCode 当前等级代码；{@code NONE} = 未达任何等级
   * @param levelOrigin 等级来源（本轮恒为 {@code RULE} = 规则算出来的）
   * @param levelReason 判定理由（凭什么落这一级）
   * @param repurchaseRate 复购率；窗口内没有订单时为 null（**不可判定**，不是 0）
   */
  public record CrmMembershipMemberRow(
      UUID id,
      String memberKey,
      String customerRef,
      String sourceShop,
      String customerName,
      @Schema(nullable = true) String logicalRef,
      int memberCount,
      List<String> sourceShops,
      int orderCount,
      @Schema(nullable = true) BigDecimal totalSpend,
      @Schema(nullable = true) String spendCurrency,
      List<CrmMembershipAmount> amounts,
      @Schema(nullable = true) BigDecimal repurchaseRate,
      @Schema(nullable = true) String firstOrderAt,
      @Schema(nullable = true) String lastOrderAt,
      String levelCode,
      int levelRank,
      String levelOrigin,
      String levelOriginLabel,
      @Schema(nullable = true) String levelRuleId,
      String levelReason,
      String levelEvaluatedAt,
      String registeredAt,
      @Schema(nullable = true) String windowFrom,
      @Schema(nullable = true) String windowTo) {}

  /** 会员详情 = 会员档案 + 该会员的等级变更记录。 */
  public record CrmMembershipMemberDetail(
      CrmMembershipMemberRow member, List<CrmMembershipLevelChangeRow> levelChanges) {}

  /** 等级分布的一格。 */
  public record CrmMembershipLevelBucket(String levelCode, String levelName, long memberCount) {}

  /**
   * 会员统计。
   *
   * <p>🔴 **与分页无关**：这些数字来自**单独一条**统计查询（不是拿当前页聚合出来的）。
   */
  public record CrmMembershipStats(
      long totalMembers,
      long unmatchedMembers,
      long upgradedMembers,
      long downgradedMembers,
      List<CrmMembershipLevelBucket> byLevel,
      List<CrmMembershipAmount> spendByCurrency,
      String note) {}

  /** 会员档案列表页：真分页（LIMIT/OFFSET + 稳定全序排序）+ 与分页无关的统计。 */
  public record CrmMembershipMemberPage(
      List<CrmMembershipMemberRow> rows,
      int page,
      int size,
      long total,
      int totalPages,
      CrmMembershipStats stats,
      String dataScope,
      String dataScopeNote) {}

  /**
   * 等级变更记录的一行。
   *
   * @param changeKind REGISTERED 首次建档定级 / UPGRADE 升级 / DOWNGRADE 降级
   * @param origin 等级来源（本轮恒为 {@code RULE}）
   * @param basis 判定依据快照（当时的消费指标 + 命中的门槛）
   */
  public record CrmMembershipLevelChangeRow(
      UUID id,
      String memberKey,
      String customerRef,
      String sourceShop,
      String customerName,
      String fromLevelCode,
      String toLevelCode,
      int fromLevelRank,
      int toLevelRank,
      String changeKind,
      String changeKindLabel,
      String origin,
      String originLabel,
      @Schema(nullable = true) String ruleId,
      String reason,
      Map<String, Object> basis,
      String changedAt,
      @Schema(nullable = true) String operatorId) {}

  /** 等级变更记录列表页（真分页）。 */
  public record CrmMembershipLevelChangePage(
      List<CrmMembershipLevelChangeRow> rows, int page, int size, long total, int totalPages) {}

  /**
   * 等级规则的一行。
   *
   * @param effectiveNow 该规则此刻是否生效（**按数据库的 now() 判定**，不拿 JVM 墙钟比大小）
   */
  public record CrmMembershipLevelRuleRow(
      UUID id,
      String levelCode,
      String levelName,
      int levelRank,
      @Schema(nullable = true) BigDecimal minTotalSpend,
      @Schema(nullable = true) String minTotalSpendCurrency,
      @Schema(nullable = true) Integer minOrderCount,
      @Schema(nullable = true) BigDecimal minRepurchaseRate,
      @Schema(nullable = true) BigDecimal discountRate,
      boolean freeShipping,
      boolean priorityShipping,
      boolean enabled,
      String effectiveFrom,
      @Schema(nullable = true) String effectiveTo,
      boolean effectiveNow,
      String note,
      String createdAt) {}

  /** 等级规则列表页（真分页）。 */
  public record CrmMembershipLevelRulePage(
      List<CrmMembershipLevelRuleRow> rows, int page, int size, long total, int totalPages) {}

  /**
   * 权益生效配置的一行 —— 这是本模块交给 **5 定价 / 14 促销** 的东西。
   *
   * @param benefitSummary 人可读的权益摘要（如「减免 5% · 免邮 · 优先发货」）
   * @param appliesTo 生效条件（人可读，如「累计消费 ≥ 5000 USD 且订单数 ≥ 3」）
   * @param downstreamConsumers 该配置的下游消费方（模块 ID）
   */
  public record CrmMembershipBenefitConfigRow(
      String levelCode,
      String levelName,
      int levelRank,
      @Schema(nullable = true) BigDecimal discountRate,
      boolean freeShipping,
      boolean priorityShipping,
      String benefitSummary,
      String appliesTo,
      String effectiveFrom,
      @Schema(nullable = true) String effectiveTo,
      boolean effectiveNow,
      List<String> downstreamConsumers,
      String note) {}

  /**
   * 权益生效配置全量（**不分页**：下游要的是「当前生效的全部等级权益」）。
   *
   * <p>🔴 本模块**只输出**这份配置，**不**去改价目表 / 促销表（跨服务禁止）。
   */
  public record CrmMembershipBenefitConfig(
      List<CrmMembershipBenefitConfigRow> rows,
      List<String> downstreamConsumers,
      String generatedAt,
      String note) {}

  /**
   * 重算的结果。
   *
   * @param currencySkippedCount 因**跨币种**而无法用某条规则判定的会员数（如实报出，不是 0）
   * @param truncated 本次扫描被截断（订单翻页到上限）
   */
  public record CrmMembershipComputeResult(
      UUID runId,
      String runNo,
      String status,
      @Schema(nullable = true) String windowFrom,
      String windowTo,
      int memberCount,
      int levelChangedCount,
      int ruleCount,
      int unmatchedCount,
      int currencySkippedCount,
      boolean truncated,
      String failureReason,
      String startedAt,
      @Schema(nullable = true) String finishedAt,
      String note) {}

  /** 一次重算运行的记录。 */
  public record CrmMembershipRunRow(
      UUID id,
      String runNo,
      String status,
      @Schema(nullable = true) String windowFrom,
      String windowTo,
      int memberCount,
      int levelChangedCount,
      int ruleCount,
      int unmatchedCount,
      int currencySkippedCount,
      boolean truncated,
      String failureReason,
      Map<String, Object> facts,
      @Schema(nullable = true) String operatorId,
      String startedAt,
      @Schema(nullable = true) String finishedAt) {}

  /** 重算运行记录列表页（真分页）。 */
  public record CrmMembershipRunPage(
      List<CrmMembershipRunRow> rows, int page, int size, long total, int totalPages) {}
}
