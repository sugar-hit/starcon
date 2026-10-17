package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 9.2.1.2 审单规则的传输模型。
 *
 * <p>蓝图原话：「配置自动审单规则：按店铺/金额/买家等级/商品/收货国家自动通过或转人工；
 * 命中黑名单、地址异常、大额、异常折扣的强制转人工。规则有优先级与生效期。」
 * 输入是「规则定义（条件、动作、优先级）；测试数据」，输出是
 * 「规则命中结果（每单命中的规则链）；规则生效记录」。
 *
 * <p><b>三条建模纪律</b>：
 *
 * <ul>
 *   <li><b>命中链是三层而不是一个布尔值</b>：{@link ReviewDecision}（结论）→
 *       {@link RuleHit}（被考虑到的每一条规则，含未命中的）→
 *       {@link HitCondition}（每个条件命中订单的哪个字段、实际值多少）。
 *       蓝图加粗句「客服被买家投诉时能说出『为什么这单被拦了』」只能由第三层兑现 ——
 *       只给「命中了金额条件」，客服依然答不出「为什么」。
 *   <li><b>「取不到值」是空值不是默认值</b>：买家没有画像行时
 *       {@link HitCondition#actualValue()} 是空串而不是 "STANDARD"。兜一个默认等级会让
 *       「VIP 自动通过」把全网买家都放过去。
 *   <li><b>金额与数量全程 BigDecimal</b>，对齐 DB 的 numeric(19,4) / numeric(19,6)。
 * </ul>
 */
public final class OrderReviewRuleModels {

  private OrderReviewRuleModels() {}

  // ------------------------------------------------------------ 枚举

  /** 规则动作。只有「自动通过 / 转人工」两个：自动拒单是 9.2.1.3 的主权。 */
  public enum ReviewAction {
    AUTO_APPROVE("自动通过"),
    MANUAL_REVIEW("转人工");

    private final String label;

    ReviewAction(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 规则状态。DRAFT 草稿 → ACTIVE 生效中 → DISABLED 停用。 */
  public enum ReviewRuleStatus {
    DRAFT("草稿"),
    ACTIVE("生效中"),
    DISABLED("已停用");

    private final String label;

    ReviewRuleStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 判定结论。
   *
   * <p>「没有规则命中」也是 {@link #MANUAL_REVIEW}：与 9.2.1.1「批量通过必须基于明确的规则，
   * 而不是默认全过」同一立场。默认全过会变成一批没人看过的货直接发走。
   */
  public enum ReviewDecisionType {
    AUTO_APPROVE("自动通过"),
    MANUAL_REVIEW("转人工");

    private final String label;

    ReviewDecisionType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 判定模式：试跑不产生生产结论。 */
  public enum ReviewMode {
    DRY_RUN("试跑"),
    RECORDED("生效判定");

    private final String label;

    ReviewMode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 条件维度，**逐个对应职责句里的一个词**。
   *
   * <p>刻意不给「任意表达式」维度：能写任意表达式的引擎出错时说不出
   * 「哪个条件命中了哪个字段」，而可解释正是本模块的加粗句要求。
   */
  public enum ConditionDimension {
    SHOP("店铺（平台来源）"),
    AMOUNT("订单金额"),
    BUYER_LEVEL("买家等级"),
    ITEM("商品"),
    SHIPPING_COUNTRY("收货国家"),
    BUYER_BLACKLIST("买家黑名单"),
    ADDRESS_ANOMALY("地址异常"),
    DISCOUNT_RATIO("折扣比例");

    private final String label;

    ConditionDimension(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 条件运算符，分三族：集合型、数值型、标记型。
   *
   * <p>DB 侧有一条「维度与运算符必须匹配」的 CHECK（数值型只能配 AMOUNT / DISCOUNT_RATIO、
   * 标记型只能配 BUYER_BLACKLIST、其余维度只能配集合型），把「配了但永远判不出结果」
   * 的条件挡在库外 —— 那是最难查的一类故障：界面上看规则配得好好的，就是不命中。
   */
  public enum ConditionOperator {
    IN("属于"),
    NOT_IN("不属于"),
    GTE("大于等于"),
    GT("大于"),
    LTE("小于等于"),
    LT("小于"),
    EQ("等于"),
    IS_TRUE("为真"),
    IS_FALSE("为假");

    private final String label;

    ConditionOperator(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 集合型：SHOP / ITEM / SHIPPING_COUNTRY / BUYER_LEVEL / ADDRESS_ANOMALY。 */
    public boolean isSetBased() {
      return this == IN || this == NOT_IN;
    }

    /** 数值型：AMOUNT / DISCOUNT_RATIO。 */
    public boolean isNumeric() {
      return this == GTE || this == GT || this == LTE || this == LT || this == EQ;
    }

    /** 标记型：BUYER_BLACKLIST。 */
    public boolean isFlag() {
      return this == IS_TRUE || this == IS_FALSE;
    }

    /**
     * 这个运算符能不能配在这个维度上。
     *
     * <p>与 V149 的 {@code order_review_rule_condition_operator_fits_dimension} 同一口径，
     * 服务层先挡一次（错误信息带维度与运算符的名字），DB 再挡一次。
     */
    public boolean fits(ConditionDimension dimension) {
      return switch (dimension) {
        case AMOUNT, DISCOUNT_RATIO -> isNumeric();
        case BUYER_BLACKLIST -> isFlag();
        default -> isSetBased();
      };
    }
  }

  /** 试跑的取值范围。 */
  public enum EvaluationScope {
    EFFECTIVE("此刻生效中的规则"),
    SELECTED("调用方点名的规则");

    private final String label;

    EvaluationScope(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 能力声明

  @Schema(description = "9.2.1.2 审单规则能力与上下游可用性")
  public record OrderReviewRuleCapabilities(
      @Schema(description = "能力维度清单") List<OrderReviewDimension> dimensions,
      @Schema(description = "可配置的条件维度") List<ConditionDimension> conditionDimensions,
      @Schema(description = "条件运算符") List<ConditionOperator> operators,
      @Schema(description = "规则动作取值") List<ReviewAction> actions,
      @Schema(description = "规则状态取值") List<ReviewRuleStatus> ruleStatuses,
      @Schema(description = "判定结论取值") List<ReviewDecisionType> decisions,
      @Schema(description = "本租户规则条数") long ruleCount,
      @Schema(description = "本租户此刻生效中的规则条数") long activeRuleCount,
      @Schema(description = "本租户已登记的买家画像条数") long buyerProfileCount,
      @Schema(description = "口径与判读说明") String notes,
      @Schema(description = "下游缺口说明") String downstreamGaps) {

    @Schema(description = "能力维度：依赖什么、是否真的可用、不可用时怎么办")
    public record OrderReviewDimension(
        @Schema(description = "维度码") String code,
        @Schema(description = "维度名") String label,
        @Schema(description = "是否真的可用") boolean available,
        @Schema(description = "数据来源与归属") String source,
        @Schema(description = "不可用时的原因与交接方式；可用时为 null") String unavailableReason) {}
  }

  // ------------------------------------------------------------ 规则

  /** 规则条件一行（定义侧）。 */
  @Schema(description = "规则条件")
  public record ReviewRuleCondition(
      @Schema(description = "条件 id") UUID id,
      @Schema(description = "条件序号（命中明细按它排序）") int seqNo,
      @Schema(description = "维度") ConditionDimension dimension,
      @Schema(description = "维度中文") String dimensionLabel,
      @Schema(description = "运算符") ConditionOperator operator,
      @Schema(description = "运算符中文") String operatorLabel,
      @Schema(description = "集合取值（IN / NOT_IN 用）") List<String> valueSet,
      @Schema(description = "数值阈值（GTE/GT/LTE/LT/EQ 用；折扣比例口径是百分数，85 = 85%）", nullable = true) BigDecimal valueNumeric,
      @Schema(description = "条件说明") String description) {}

  /** 保存规则时提交的条件。 */
  @Schema(description = "保存规则时提交的条件")
  public record ReviewRuleConditionInput(
      @NotNull @Min(1) @Schema(description = "条件序号（从 1 开始）") Integer seqNo,
      @NotNull @Schema(description = "维度") ConditionDimension dimension,
      @NotNull @Schema(description = "运算符") ConditionOperator operator,
      @Schema(description = "集合取值；IN / NOT_IN 必填且至少一个", nullable = true) List<String> valueSet,
      @Schema(description = "数值阈值；GTE/GT/LTE/LT/EQ 必填", nullable = true) BigDecimal valueNumeric,
      @Size(max = 400) @Schema(description = "条件说明") String description) {}

  /** 规则列表一行。 */
  @Schema(description = "审单规则一行")
  public record ReviewRuleRow(
      @Schema(description = "规则 id") UUID id,
      @Schema(description = "规则编号（租户内唯一，同时是排序的确定性来源）") String ruleCode,
      @Schema(description = "规则名称") String ruleName,
      @Schema(description = "规则说明") String description,
      @Schema(description = "优先级：数字大的先判定") int priority,
      @Schema(description = "动作") ReviewAction action,
      @Schema(description = "动作中文") String actionLabel,
      @Schema(description = "是否强制转人工（命中即压过任何自动通过规则）") boolean forcedManual,
      @Schema(description = "条件组合方式：ALL / ANY") String matchMode,
      @Schema(description = "状态") ReviewRuleStatus status,
      @Schema(description = "状态中文") String statusLabel,
      @Schema(description = "生效起") OffsetDateTime effectiveFrom,
      @Schema(description = "生效止；null = 长期有效", nullable = true) OffsetDateTime effectiveTo,
      @Schema(description = "此刻是否落在生效期内（按查询时刻实算）") boolean effectiveNow,
      @Schema(description = "条件条数") int conditionCount,
      @Schema(description = "条件维度摘要（逗号分隔，供列表快速扫读）") String dimensionSummary,
      @Schema(description = "能力自述：条件引用字段此刻取不到值时的说明", nullable = true) String capabilityNote,
      @Schema(description = "备注") String note,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时间") OffsetDateTime updatedAt) {}

  /** 规则详情 = 头 + 条件。 */
  @Schema(description = "审单规则详情")
  public record ReviewRuleDetail(
      @Schema(description = "规则") ReviewRuleRow rule,
      @Schema(description = "条件（按 seqNo 升序）") List<ReviewRuleCondition> conditions,
      @Schema(description = "最近一次用这条规则判定的结论；没有则 null", nullable = true) ReviewDecision lastDecision,
      @Schema(description = "读这一页必须知道的事") String notes) {}

  /** 保存规则（新建与更新共用）。 */
  @Schema(description = "保存审单规则")
  public record ReviewRuleInput(
      @NotBlank
          @Size(max = 64)
          @Pattern(regexp = "^[A-Za-z0-9._-]{1,64}$")
          @Schema(description = "规则编号；租户内唯一，只允许字母数字与 . _ -")
          String ruleCode,
      @NotBlank @Size(max = 120) @Schema(description = "规则名称") String ruleName,
      @Size(max = 600) @Schema(description = "规则说明", nullable = true) String description,
      @NotNull @Min(0) @Max(9999) @Schema(description = "优先级：数字大的先判定") Integer priority,
      @NotNull @Schema(description = "动作：AUTO_APPROVE / MANUAL_REVIEW") ReviewAction action,
      @Schema(description = "是否强制转人工；命中即压过任何自动通过规则", nullable = true) Boolean forcedManual,
      @Schema(description = "条件组合方式，默认 ALL", nullable = true) String matchMode,
      @NotNull @Schema(description = "生效起（timestamptz），必填") OffsetDateTime effectiveFrom,
      @Schema(description = "生效止（timestamptz）；留空 = 长期有效", nullable = true) OffsetDateTime effectiveTo,
      @Size(max = 400) @Schema(description = "能力自述", nullable = true) String capabilityNote,
      @Size(max = 600) @Schema(description = "备注", nullable = true) String note,
      @Valid
          @NotEmpty
          @Size(max = 20)
          @Schema(description = "条件（1~20 条）。ACTIVE 规则不允许零条件")
          List<ReviewRuleConditionInput> conditions,
      @Schema(description = "乐观锁版本；更新时必填，创建时忽略", nullable = true) Long version) {}

  /** 切换规则状态。 */
  @Schema(description = "切换规则状态")
  public record ReviewRuleStatusInput(
      @NotNull @Schema(description = "目标状态：DRAFT / ACTIVE / DISABLED") ReviewRuleStatus status,
      @Schema(description = "启用时顺带改生效起；不给则保持原值", nullable = true) OffsetDateTime effectiveFrom,
      @Schema(description = "启用时顺带改生效止；不给则保持原值，传 null 表示长期有效时需用清空语义", nullable = true) OffsetDateTime effectiveTo,
      @Size(max = 600) @Schema(description = "备注；停用时必填", nullable = true) String note,
      @Schema(description = "乐观锁版本", nullable = true) Long version) {}

  // ------------------------------------------------------------ 买家画像

  @Schema(description = "买家画像（等级 + 黑名单，租户自维护）")
  public record BuyerProfile(
      @Schema(description = "画像 id") UUID id,
      @Schema(description = "买家身份键：收货地址快照三件套的 md5") String buyerKey,
      @Schema(description = "收件人姓名") String buyerName,
      @Schema(description = "收件人电话") String buyerPhone,
      @Schema(description = "收件人邮箱（判定时先按身份键、再按邮箱反查）") String buyerEmail,
      @Schema(description = "买家等级；没有这一行 = 等级未知，不是 STANDARD") String buyerLevel,
      @Schema(description = "是否在黑名单里") boolean blacklisted,
      @Schema(description = "拉黑理由（拉黑时必填，否则客服无法解释）") String blacklistReason,
      @Schema(description = "拉黑时刻") OffsetDateTime blacklistedAt,
      @Schema(description = "备注") String note,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "更新时间") OffsetDateTime updatedAt) {}

  @Schema(description = "保存买家画像（按身份键 upsert）")
  public record BuyerProfileInput(
      @NotBlank
          @Pattern(regexp = "^[0-9a-f]{32}$")
          @Schema(description = "买家身份键 md5；与 V144 合单的地址分支同一算法")
          String buyerKey,
      @Size(max = 120) @Schema(description = "收件人姓名", nullable = true) String buyerName,
      @Size(max = 64) @Schema(description = "收件人电话", nullable = true) String buyerPhone,
      @Schema(description = "收件人邮箱", nullable = true) String buyerEmail,
      @NotNull
          @Schema(description = "买家等级：STANDARD / KEY / STRATEGIC / VIP / RISK")
          String buyerLevel,
      @Schema(description = "是否拉黑") Boolean blacklisted,
      @Size(max = 400) @Schema(description = "拉黑理由；拉黑时必填", nullable = true) String blacklistReason,
      @Schema(description = "拉黑时刻；不填则取当前时刻", nullable = true) OffsetDateTime blacklistedAt,
      @Size(max = 600) @Schema(description = "备注", nullable = true) String note,
      @Schema(description = "乐观锁版本", nullable = true) Long version) {}

  // ------------------------------------------------------------ 判定结果

  /**
   * 逐条件明细 —— 加粗句「哪条规则的哪个条件命中了订单的哪个字段」的落点。
   *
   * <p>{@link #actualField()} 是订单侧的列名（含表名），{@link #actualValue()} 是该列的实际值，
   * {@link #explanation()} 是人话。三句合起来客服可以原话念给买家听。
   */
  @Schema(description = "逐条件命中明细")
  public record HitCondition(
      @Schema(description = "条件序号") int seqNo,
      @Schema(description = "维度") ConditionDimension dimension,
      @Schema(description = "维度中文") String dimensionLabel,
      @Schema(description = "运算符") ConditionOperator operator,
      @Schema(description = "规则侧期望值（当时的条件快照）") String expected,
      @Schema(description = "订单侧被比对的列；取不到时为空串") String actualField,
      @Schema(description = "订单侧实际值；**取不到时是空串而不是默认值**") String actualValue,
      @Schema(description = "该条件是否满足") boolean satisfied,
      @Schema(description = "人话解释") String explanation) {}

  /**
   * 被考虑到的每一条规则（含未命中的）。
   *
   * <p>未命中同样留痕：只记命中项时「为什么这条规则没兜住」答不上来，
   * 而那正是客服被追问时的第二个问题。
   */
  @Schema(description = "命中链一行（未命中的规则也在内）")
  public record RuleHit(
      @Schema(description = "命中行 id") UUID id,
      @Schema(description = "规则 id") UUID ruleId,
      @Schema(description = "规则编号快照") String ruleCode,
      @Schema(description = "规则名称快照") String ruleName,
      @Schema(description = "优先级快照") int priority,
      @Schema(description = "动作快照") ReviewAction action,
      @Schema(description = "动作中文") String actionLabel,
      @Schema(description = "是否强制转人工") boolean forcedManual,
      @Schema(description = "判定次序：priority DESC, rule_code ASC 之后的名次") int evalRank,
      @Schema(description = "是否命中") boolean matched,
      @Schema(description = "命中或未命中的理由") String reason,
      @Schema(description = "逐条件明细（按 seqNo 升序）") List<HitCondition> conditions) {}

  /** 判定时用到的订单事实快照（让客服看到「判的是哪张单、哪些值」）。 */
  @Schema(description = "判定时用到的订单事实快照")
  public record OrderFactsSummary(
      @Schema(description = "订单 id") UUID orderId,
      @Schema(description = "我方单号") String orderNo,
      @Schema(description = "外部/平台单号", nullable = true) String externalOrderNo,
      @Schema(description = "订单来源（SHOP 维度比的就是它）") String orderSource,
      @Schema(description = "币种") String currencyCode,
      @Schema(description = "订单总额（AMOUNT 维度比的就是它）") BigDecimal orderTotal,
      @Schema(description = "订单折扣额") BigDecimal discountAmount,
      @Schema(description = "小计") BigDecimal subtotalAmount,
      @Schema(description = "折扣比例百分数；小计为 0 时是 null（不是 0）", nullable = true) BigDecimal discountRatePercent,
      @Schema(description = "收货国家（SHIPPING_COUNTRY 维度比的就是它）", nullable = true) String shippingCountry,
      @Schema(description = "收件人姓名", nullable = true) String recipientName,
      @Schema(description = "收件人电话", nullable = true) String recipientPhone,
      @Schema(description = "收件人邮箱", nullable = true) String recipientEmail,
      @Schema(description = "买家身份键") String buyerKey,
      @Schema(description = "买家等级；null = 未登记档案（等级未知，不是 STANDARD）", nullable = true) String buyerLevel,
      @Schema(description = "是否命中黑名单；null = 未登记档案", nullable = true) Boolean blacklisted,
      @Schema(description = "命中的地址异常码") List<String> addressAnomalyCodes,
      @Schema(description = "订单内的 SKU 编码（ITEM 维度比的就是它）") List<String> skuCodes,
      @Schema(description = "订单行数") int lineCount,
      @Schema(description = "订单件数") BigDecimal totalQty,
      @Schema(description = "提交时刻") OffsetDateTime submittedAt) {}

  /** 判定结论 = 规则生效记录一行。 */
  @Schema(description = "审单判定结论（规则生效记录）")
  public record ReviewDecision(
      @Schema(description = "判定 id") UUID id,
      @Schema(description = "订单 id") UUID orderId,
      @Schema(description = "我方单号快照") String orderNo,
      @Schema(description = "订单来源快照") String orderSource,
      @Schema(description = "币种快照") String currencyCode,
      @Schema(description = "订单金额快照") BigDecimal orderTotal,
      @Schema(description = "折扣比例百分数快照；null = 小计为 0，比例不可计算", nullable = true) BigDecimal discountRatePercent,
      @Schema(description = "模式：DRY_RUN 试跑 / RECORDED 生效判定") ReviewMode mode,
      @Schema(description = "模式中文") String modeLabel,
      @Schema(description = "取值范围：EFFECTIVE 生效中 / SELECTED 点名") String evaluatedScope,
      @Schema(description = "结论") ReviewDecisionType decision,
      @Schema(description = "结论中文") String decisionLabel,
      @Schema(description = "一句话结论（客服第一眼要看的就是它）") String decisionReason,
      @Schema(description = "决定结论的规则 id；无规则命中时为 null", nullable = true) UUID winningRuleId,
      @Schema(description = "决定结论的规则编号快照") String winningRuleCode,
      @Schema(description = "决定结论的规则名称快照") String winningRuleName,
      @Schema(description = "参与判定的规则数") int consideredRuleCount,
      @Schema(description = "命中的规则数") int matchedRuleCount,
      @Schema(description = "强制命中的规则数") int forcedRuleCount,
      @Schema(description = "命中的地址异常码") List<String> addressAnomalyCodes,
      @Schema(description = "判定时刻（timestamptz）") OffsetDateTime evaluatedAt,
      @Schema(description = "判定人；自动判定时为 null", nullable = true) UUID evaluatedByMembershipId,
      @Schema(description = "落库时刻") OffsetDateTime createdAt) {}

  /** 判定详情 = 结论 + 命中链 + 订单事实快照。 */
  @Schema(description = "审单判定详情：结论 + 命中链 + 订单事实")
  public record ReviewDetail(
      @Schema(description = "判定结论") ReviewDecision decision,
      @Schema(description = "命中链：被考虑到的每一条规则（按 evalRank 升序）") List<RuleHit> hits,
      @Schema(description = "判定时用到的订单事实") OrderFactsSummary orderFacts,
      @Schema(description = "读这一页必须知道的事") String notes) {}

  /** 发起判定 / 试跑。 */
  @Schema(description = "发起审单判定")
  public record ReviewRunInput(
      @NotNull
          @Schema(description = "判定时刻（timestamptz）。由调用方显式给出，不取服务器当前时间")
          OffsetDateTime evaluatedAt,
      @Schema(description = "试跑指定的规则 id；不给 = 只跑此刻生效中的规则", nullable = true) List<UUID> ruleIds) {}

  // ------------------------------------------------------------ 列表与结果

  @Schema(description = "规则列表查询")
  public record ReviewRuleQuery(
      @Schema(description = "规则编号或名称模糊匹配") String keyword,
      @Schema(description = "状态筛选") String status,
      @Schema(description = "动作筛选") String action,
      @Schema(description = "只看法制转人工的规则") Boolean forcedOnly,
      @Schema(description = "只看引用了某个维度的规则") String dimension,
      @Schema(description = "只看此刻生效中的规则") Boolean effectiveOnly,
      @Schema(description = "页码（从 0 开始）") int page,
      @Schema(description = "每页行数") int size) {}

  @Schema(description = "规则分页")
  public record ReviewRulePage(
      @Schema(description = "本页数据") List<ReviewRuleRow> items,
      @Schema(description = "总条数（不受分页影响）") long total,
      @Schema(description = "当前页") int page,
      @Schema(description = "每页行数") int size,
      @Schema(description = "汇总：生效中的规则数（不受分页影响）") long activeCount,
      @Schema(description = "汇总：强制转人工的规则数（不受分页影响）") long forcedCount,
      @Schema(description = "汇总：此刻落在生效期内的规则数（不受分页影响）") long effectiveNowCount) {}

  @Schema(description = "判定记录列表查询")
  public record EvaluationQuery(
      @Schema(description = "订单号模糊匹配") String keyword,
      @Schema(description = "订单 id 精确筛选") UUID orderId,
      @Schema(description = "结论筛选") String decision,
      @Schema(description = "模式筛选：DRY_RUN / RECORDED") String mode,
      @Schema(description = "只看强制转人工触发的判定") Boolean forcedOnly,
      @Schema(description = "页码（从 0 开始）") int page,
      @Schema(description = "每页行数") int size) {}

  @Schema(description = "判定记录分页")
  public record EvaluationPage(
      @Schema(description = "本页数据") List<ReviewDecision> items,
      @Schema(description = "总条数（不受分页影响）") long total,
      @Schema(description = "当前页") int page,
      @Schema(description = "每页行数") int size,
      @Schema(description = "汇总：自动通过条数（不受分页影响）") long autoApproveCount,
      @Schema(description = "汇总：转人工条数（不受分页影响）") long manualReviewCount,
      @Schema(description = "汇总：强制转人工触发条数（不受分页影响）") long forcedCount,
      @Schema(description = "汇总：结论为「无规则命中默认转人工」的条数（不受分页影响）")
          long noRuleMatchedCount) {}

  @Schema(description = "买家画像列表查询")
  public record BuyerProfileQuery(
      @Schema(description = "姓名 / 邮箱 / 电话模糊匹配") String keyword,
      @Schema(description = "等级筛选") String buyerLevel,
      @Schema(description = "只看黑名单") Boolean blacklistedOnly,
      @Schema(description = "页码（从 0 开始）") int page,
      @Schema(description = "每页行数") int size) {}

  @Schema(description = "买家画像分页")
  public record BuyerProfilePage(
      @Schema(description = "本页数据") List<BuyerProfile> items,
      @Schema(description = "总条数（不受分页影响）") long total,
      @Schema(description = "当前页") int page,
      @Schema(description = "每页行数") int size,
      @Schema(description = "汇总：黑名单条数（不受分页影响）") long blacklistedCount) {}

  @Schema(description = "操作结果")
  public record ReviewRuleSaved(
      @Schema(description = "规则 id") UUID id,
      @Schema(description = "规则编号") String ruleCode,
      @Schema(description = "状态") ReviewRuleStatus status,
      @Schema(description = "条件条数") int conditionCount,
      @Schema(description = "提示语（说清这一步实际做了什么，而不是「操作成功」）") String message) {}
}
