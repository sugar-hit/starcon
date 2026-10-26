package com.starcon.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 13.1.1.1 经营总览的**投影契约**（事件 → 投影管道的数据形状）。
 *
 * <p>⚠️ **这是本服务唯一的写口径**：reporting 是投影服务（蓝图 13 卷首「不产生业务事实、
 * 不做业务写入」），所以它认识的「事实」只有一种形状 —— <b>规范化 envelope</b>。
 * 上游事件与上游拉取结果都被归一化成它，然后交给**同一个投影器**。
 *
 * <p><b>为什么要有 envelope 这一层</b>（而不是让每个上游各写各的表）：
 *
 * <ol>
 *   <li><b>幂等只有一处</b>：同一个上游事实重复到达，只会被投影一次 —— 判据在
 *       {@code reporting_projection_event} 的唯一键上，不散落在各端口里。
 *   <li><b>口径只有一处</b>：指标的定义（单位 / 聚合方式 / 分子分母）在
 *       {@code reporting_metric_definition} 里，envelope 只带「值 + 归属维度 + 归属日」。
 *   <li><b>可重建</b>：清空事实表 + 账本 + 游标后，用同一批 envelope 重放即可回到同一状态。
 * </ol>
 *
 * <p><b>租户不在 envelope 里</b>：租户由已认证会话解析（{@link AccessService}），
 * envelope 里带 tenantId 会被**拒绝**——「客户端自称的租户」与 {@code X-Tenant-Id} 头是同一种不可信输入。
 */
public final class ReportingProjectionModels {

  private ReportingProjectionModels() {}

  /**
   * 规范化事件（envelope）。
   *
   * @param sourceService 上游服务名（trade / channel / finance / warehouse / catalog / external）
   * @param sourceEventId 上游事实标识（**幂等键的另一半**）。拉取适配器用「内容寻址」生成它：
   *     同一行内容没变 ⇒ id 不变 ⇒ 跳过；内容变了 ⇒ id 变 ⇒ 重新投影并覆盖旧事实。
   * @param eventType 事件类型（口径见 {@link ReportingEventTypes}）
   * @param aggregateId 上游聚合根 id（可空）
   * @param aggregateVersion 上游聚合版本（可空）
   * @param occurredAt 事实发生时刻（**不是**投影时刻）
   * @param timezone 分桶所用时区（跨时区店铺汇总必须显示所用时区）
   * @param facts 事实贡献行
   * @param drilldowns 构成明细（可下钻）
   * @param expenseFacts 费用事实贡献行（13.4.2.2 费用差异：预计费用与实际费用**各一张表**，
   *     所以这里是**两种**行，用 {@code factKind} 区分）
   */
  public record ProjectionEvent(
      String sourceService,
      String sourceEventId,
      String eventType,
      @Schema(nullable = true) UUID aggregateId,
      long aggregateVersion,
      OffsetDateTime occurredAt,
      String timezone,
      List<ProjectionFact> facts,
      List<ProjectionDrilldown> drilldowns,
      List<ProjectionExpenseFact> expenseFacts) {

    /**
     * 不带费用事实的构造（**13.1.1.1 时期的事件一律走这里**）。
     *
     * <p>⚠️ 保留这个重载是有意的：envelope 加字段不该让**既有的调用点**全部改一遍 ——
     * 改一遍就意味着「这个模块的事件形状变了」这件事散落在十几个文件里。
     */
    public ProjectionEvent(
        String sourceService,
        String sourceEventId,
        String eventType,
        @Schema(nullable = true) UUID aggregateId,
        long aggregateVersion,
        OffsetDateTime occurredAt,
        String timezone,
        List<ProjectionFact> facts,
        List<ProjectionDrilldown> drilldowns) {
      this(
          sourceService,
          sourceEventId,
          eventType,
          aggregateId,
          aggregateVersion,
          occurredAt,
          timezone,
          facts,
          drilldowns,
          List.of());
    }
  }

  /**
   * 一条费用事实贡献（13.4.2.2）。
   *
   * <p>⚠️ **预计与实际是两种行**（{@code factKind}）：把「规则算出来的数」和
   * 「真的发生的数」写进同一张表，就再也答不出「这个月是规则定错了，还是平台多扣了」。
   *
   * @param factKind {@code ESTIMATE}（预计费用）/ {@code ACTUAL}（实际费用）
   * @param feeType 费用类型（PLATFORM_FEE / LOGISTICS_FEE / AD_FEE / HEAD_FREIGHT）
   * @param feeSubType 上游口径子类型（空串 = 上游没给）
   * @param periodStart 期间起
   * @param periodEnd 期间止（逐日事实两列相同；窗口型事实不同）
   * @param amount 这一侧的金额（预计侧 = 测算值；实际侧 = 发生额，可负）
   * @param baseAmount 销售额 / 货值基数（没有基数时为 0）
   * @param baseQuantity 数量基数（没有基数时为 0）
   * @param sourceReference 上游行标识（与上游行一起构成事实的幂等键）
   */
  public record ProjectionExpenseFact(
      String factKind,
      String feeType,
      String feeSubType,
      String platformCode,
      String siteCode,
      String channelCode,
      String carrierCode,
      String shippingMethodCode,
      String shopCode,
      String skuCode,
      LocalDate periodStart,
      LocalDate periodEnd,
      String currency,
      BigDecimal amount,
      BigDecimal baseAmount,
      BigDecimal baseQuantity,
      String sourceReference) {}

  /**
   * 一条事实贡献。
   *
   * @param scopeType 归属层级（COMPANY / LEGAL_ENTITY / SHOP）
   * @param metricCode 指标码（**必须已在口径表登记**，否则 DB 触发器直接拒写）
   * @param bucketDate 归属日历日（按 {@code timezone} 分桶，不是按 UTC 截断）
   * @param currency 币种（**不跨币种相加**）
   * @param value 金额 / 数量型取值
   * @param sourceReference 上游行标识（与上游行一起构成事实的幂等键）
   */
  public record ProjectionFact(
      String scopeType,
      @Schema(nullable = true) UUID legalEntityId,
      @Schema(nullable = true) UUID shopId,
      String metricCode,
      LocalDate bucketDate,
      String currency,
      BigDecimal value,
      BigDecimal quantity,
      String sourceReference) {}

  /** 一条构成明细（卷 13 卷首「每个汇总数字必须能钻到构成明细」）。 */
  public record ProjectionDrilldown(
      String scopeType,
      @Schema(nullable = true) UUID legalEntityId,
      @Schema(nullable = true) UUID shopId,
      String metricCode,
      LocalDate bucketDate,
      String currency,
      String sourceKind,
      String referenceId,
      String referenceLabel,
      BigDecimal quantity,
      BigDecimal value,
      String sourceReference) {}

  /**
   * 一个事件投影的结果。
   *
   * @param outcome APPLIED（已投影）/ SKIPPED_DUPLICATE（**同一事件重投，已跳过**）
   */
  public record ProjectionResult(
      String sourceService,
      String sourceEventId,
      String eventType,
      String outcome,
      int factCount,
      int drilldownCount,
      OffsetDateTime appliedAt,
      String note) {}

  /** 一批事件的投影结果。 */
  public record ProjectionBatchResult(
      List<ProjectionResult> results,
      int appliedCount,
      int skippedCount,
      int factCount,
      int drilldownCount,
      String note) {}

  /** 刷新（从上游拉取 → 归一化 → 投影）的请求。 */
  public record RefreshRequest(
      @Schema(nullable = true) LocalDate from,
      @Schema(nullable = true) LocalDate to,
      List<String> sources) {}

  /** 刷新结果。 */
  public record RefreshResult(
      String sourceService,
      String stream,
      int eventCount,
      int appliedCount,
      int skippedCount,
      String status,
      String note) {}

  /** 重建（清空投影 → 重新拉取）的请求。 */
  public record RebuildRequest(
      @Schema(nullable = true) LocalDate from, @Schema(nullable = true) LocalDate to) {}

  /**
   * 规范化事件类型词表（**本服务自己的口径**，不照抄上游的 event_type 命名）。
   *
   * <p>为什么不让 envelope 直接带上游的 event_type：上游各说各话（catalog 说
   * {@code SkuProfileSaved}、channel 说 {@code SyncHealthChanged}），而本服务只关心
   * 「这条事实是什么口径」。归一化之后，投影器只认这几个词，**新增上游不用改投影器**。
   */
  public static final class ReportingEventTypes {
    private ReportingEventTypes() {}

    /** 销售事实（订单 / 订单行）。 */
    public static final String SALES_FACT = "SalesFactRecorded";
    /** 成本事实（出库成本）。 */
    public static final String COST_FACT = "CostFactRecorded";
    /** 退货事实。 */
    public static final String RETURN_FACT = "ReturnFactRecorded";
    /** 广告花费事实。 */
    public static final String AD_SPEND_FACT = "AdSpendFactRecorded";
    /** 应收余额快照。 */
    public static final String RECEIVABLE_SNAPSHOT = "ReceivableSnapshotRecorded";
    /** 库存估值快照。 */
    public static final String INVENTORY_SNAPSHOT = "InventorySnapshotRecorded";
    /** 净利事实（12 财务口径）。 */
    public static final String PROFIT_FACT = "ProfitFactRecorded";
    /** 汇率观测（带时点）。 */
    public static final String CURRENCY_RATE = "CurrencyRateObserved";
    /**
     * 费用事实（13.4.2.2 费用差异：预计费用 / 实际费用）。
     *
     * <p>⚠️ 预计与实际**在同一个事件里**落地：它们的幂等键共用「上游那一行」，
     * 分开成两个事件会让「同一行的预计与实际」在重建时错位。
     */
    public static final String EXPENSE_FACT = "ExpenseFactRecorded";

    /** 全部事件类型（能力声明与校验用）。 */
    public static final List<String> ALL =
        List.of(
            SALES_FACT,
            COST_FACT,
            RETURN_FACT,
            AD_SPEND_FACT,
            RECEIVABLE_SNAPSHOT,
            INVENTORY_SNAPSHOT,
            PROFIT_FACT,
            CURRENCY_RATE,
            EXPENSE_FACT);
  }
}
