package com.starcon.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 13.1.2.1 目标设定的**出入参模型**（蓝图「输出」句：目标记录 + 分解计划）。
 *
 * <p>⚠️ **「算不出来」与「等于 0」必须分开**（与 13.1.1.1 / 13.1.1.2 同纪律）：
 * 凡是可以「算不出来」的数值一律用 {@code null} 表示，**不用 0 冒充**。
 * 基线为 0 与「没有基线」在页面上长得一模一样，含义却相反。
 *
 * <p>⚠️ **多币种绝不相加**：目标值带币种，分解项带币种，汇总按币种分列。
 * 一个 USD 目标和一个 EUR 目标的大小本来就不可比。
 *
 * <p>⚠️ **名字不与既有类型重名**（codegen 的同名闸会中止契约生成）：
 * 13.1.1.1 已有 {@code MetricCard} / {@code CurrencyValue}，
 * 13.1.1.2 已有 {@code StoreMetricValue} / {@code ComparisonCapabilities}。
 * 本文件统一用 {@code Target*} 前缀。
 */
public final class ReportingTargetModels {

  private ReportingTargetModels() {}

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 一个蓝图目标指标在**某个归属维度**上的能力。
   *
   * @param key 蓝图目标指标键
   * @param displayName 蓝图点名的显示名（销售额 / 毛利额 / 毛利率 / 广告 ROI / 库存周转）
   * @param unit 单位（CURRENCY / PERCENT / RATIO）
   * @param ownerModule 口径归属模块
   * @param scopeType 归属对象类型（DEPARTMENT / MEMBER / SHOP / PRODUCT）
   * @param available 该维度上能不能设这个目标。false ⇒ {@code calipers} 为空
   * @param calipers 该维度上**可引用的口径码**（口径真相在口径表里，这里只给引用）
   * @param unavailableReason 接不通的原因（{@code available == false} 时必填）
   * @param note 补充说明
   */
  public record TargetMetricView(
      String key,
      String displayName,
      String unit,
      String ownerModule,
      String scopeType,
      boolean available,
      List<TargetCaliberView> calipers,
      String unavailableReason,
      String note) {}

  /**
   * 一个可引用的口径（**引用**，不是定义）。
   *
   * @param metricCode 口径码
   * @param displayName 口径表里登记的显示名（运行时唯一真相）
   * @param unit 口径表里登记的单位
   * @param aggregation 口径表里登记的聚合方式（SUM / LAST / DERIVED_DIFF / DERIVED_RATIO）
   * @param caliberNote 口径表里登记的口径说明
   * @param ownerModule 口径归属模块
   */
  public record TargetCaliberView(
      String metricCode,
      String displayName,
      String unit,
      String aggregation,
      String caliberNote,
      String ownerModule) {}

  /**
   * 能力声明的完整响应。
   *
   * @param upstreamConnected 组织对象清单的上游是否真接通
   * @param baselineConnected 历史基线是否取得到
   * @param upstreamModules 本服务**真的读过**的上游模块
   * @param notDelivered **确实拿不到**的部分（每条写清原因与影响）
   * @param notes 口径纪律说明
   */
  public record TargetCapabilities(
      boolean upstreamConnected,
      boolean baselineConnected,
      List<String> upstreamModules,
      List<String> notDelivered,
      List<String> notes) {}

  // ==================================================================
  // 归属对象
  // ==================================================================

  /**
   * 一个归属对象（部门 / 人员 / 店铺 / 产品）。
   *
   * @param ownerType 归属对象类型
   * @param ownerId 归属对象标识（部门 / 成员 / 产品的 uuid，或店铺的 shop_code）
   * @param ownerCode 业务编码（部门的 code / 店铺的 shop_code / 产品的 code）
   * @param ownerName 显示名
   * @param detail 补充说明（部门的负责人、成员的部门、店铺的平台、产品的 SKU 数）
   */
  public record TargetOwnerView(
      String ownerType,
      String ownerId,
      String ownerCode,
      String ownerName,
      String detail) {}

  /**
   * 归属对象清单。
   *
   * @param scopeType 归属对象类型
   * @param owners 清单
   * @param source 清单来源（identity / catalog / reporting 本服务的店铺档案）
   * @param note 口径说明（清单为空时说明为什么）
   */
  public record TargetOwnerList(
      String scopeType, List<TargetOwnerView> owners, String source, String note) {}

  // ==================================================================
  // 分解计划
  // ==================================================================

  /**
   * 一个分解项（一个月 / 一周的目标金额）。
   *
   * @param bucketStart 分解项起
   * @param bucketEnd 分解项止
   * @param granularity 分解粒度（MONTHLY / WEEKLY）
   * @param currency 币种（**与目标同币种** ⇒ 合计不会跨币种）
   * @param targetValue 该分解项的目标金额
   * @param weight 相对权重（EVEN 恒为 1；WEIGHTED 是用户给的相对权重）
   */
  public record TargetBucketView(
      LocalDate bucketStart,
      LocalDate bucketEnd,
      String granularity,
      String currency,
      BigDecimal targetValue,
      BigDecimal weight) {}

  /**
   * 分解预览（**不含落库**）：给定周期与目标值，算出分解计划。
   *
   * @param granularity 分解粒度
   * @param decompositionMethod 分解方式（EVEN / WEIGHTED）
   * @param currency 币种
   * @param targetValue 周期内目标总额
   * @param buckets 分解项
   * @param bucketSum 分解项合计（**必须精确等于** {@code targetValue}）
   * @param reconciled 合计是否与目标值一致（数据层还有一道延迟约束触发器兜底）
   * @param remainderNote 余数归属说明（把「均分后合计 ≠ 目标值」这件事说清楚）
   */
  public record TargetDecompositionPreview(
      String granularity,
      String decompositionMethod,
      String currency,
      BigDecimal targetValue,
      List<TargetBucketView> buckets,
      BigDecimal bucketSum,
      boolean reconciled,
      String remainderNote) {}

  // ==================================================================
  // 周期边界
  // ==================================================================

  /**
   * 当前周期（**按本租户时区算**）。
   *
   * @param granularity MONTHLY / WEEKLY
   * @param from 周期起（自然月 1 日 / ISO 周周一）
   * @param to 周期止（自然月末日 / ISO 周周日）
   * @param asOfDate 「今天」在这个租户时区里的日期
   * @param timezone 周期边界所用的时区
   * @param timezoneSource PROJECTION（取自本租户投影事实的分桶时区）/ FALLBACK_UTC
   * @param note 说明（时区回退时写清原因）
   */
  public record TargetCurrentPeriod(
      String granularity,
      LocalDate from,
      LocalDate to,
      LocalDate asOfDate,
      String timezone,
      String timezoneSource,
      String note) {}

  // ==================================================================
  // 目标记录
  // ==================================================================

  /**
   * 历史基线（快照）。
   *
   * @param status AVAILABLE 有基线 / NO_DATA 该维度还没有事实 / NOT_AVAILABLE 该维度没有事实来源
   * @param value 基线值（**{@code status == AVAILABLE} 时才有值**；否则为 null，**不是 0**）
   * @param currency 币种
   * @param from 基线窗口起
   * @param to 基线窗口止
   * @param scope 基线取自哪一级维度：OWNER 归属对象自己的维度 / TENANT 租户级参考值
   * @param note 口径说明（为什么取不到 / 为什么是租户级）
   */
  public record TargetBaselineView(
      String status,
      @Schema(nullable = true) BigDecimal value,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) LocalDate from,
      @Schema(nullable = true) LocalDate to,
      String scope,
      String note) {}

  /**
   * 一个目标记录。
   *
   * @param id 目标 id
   * @param metricCode 引用的口径码
   * @param metricDisplayName 口径表里登记的显示名
   * @param unit 单位
   * @param scopeType 归属对象类型
   * @param ownerId 归属对象标识
   * @param ownerCode 归属对象业务编码（**快照**）
   * @param ownerName 归属对象显示名（**快照** —— 组织改名后历史目标仍认得出是谁）
   * @param currency 币种
   * @param targetValue 周期内目标总额
   * @param baseline 历史基线（快照）
   * @param periodStart 周期起
   * @param periodEnd 周期止
   * @param granularity 分解粒度
   * @param decompositionMethod 分解方式
   * @param status ACTIVE / ARCHIVED
   * @param note 备注
   * @param buckets 分解项（详情里给全；列表里给空并用 bucketCount / bucketSum 概括）
   * @param bucketCount 分解项个数
   * @param bucketSum 分解项合计（**必须精确等于** {@code targetValue}）
   * @param reconciled 合计是否与目标值一致
   * @param createdAt 创建时刻
   * @param updatedAt 更新时刻
   */
  public record TargetView(
      UUID id,
      String metricCode,
      String metricDisplayName,
      String unit,
      String scopeType,
      String ownerId,
      String ownerCode,
      String ownerName,
      String currency,
      BigDecimal targetValue,
      TargetBaselineView baseline,
      LocalDate periodStart,
      LocalDate periodEnd,
      String granularity,
      String decompositionMethod,
      String status,
      String note,
      List<TargetBucketView> buckets,
      int bucketCount,
      BigDecimal bucketSum,
      boolean reconciled,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 按币种的合计（**绝不跨币种相加**）。
   *
   * @param currency 币种
   * @param total 该币种下的目标合计
   * @param count 该币种下的目标个数
   */
  public record TargetCurrencyTotal(String currency, BigDecimal total, int count) {}

  /**
   * 目标清单（真分页 + 真计数）。
   *
   * @param page 页码（0 起）
   * @param size 每页条数
   * @param total 总条数
   * @param targets 目标
   * @param currencyTotals 按币种的合计（**不跨币种相加**）
   * @param note 口径说明
   */
  public record TargetListResponse(
      int page,
      int size,
      long total,
      List<TargetView> targets,
      List<TargetCurrencyTotal> currencyTotals,
      String note) {}

  // ==================================================================
  // 写入
  // ==================================================================

  /**
   * 设定 / 修改一个目标的入参。
   *
   * ⚠️ **不接受 tenantId / userId / membershipId**：租户只从已认证成员关系解析
   * （AGENTS.md 多租户纪律），归属对象由服务端按 {@code ownerId} 回查权威来源并取快照。
   *
   * @param metricCode 引用的口径码（必须已登记）
   * @param scopeType 归属对象类型（DEPARTMENT / MEMBER / SHOP / PRODUCT）
   * @param ownerId 归属对象标识
   * @param currency 币种（3 位大写）
   * @param targetValue 周期内目标总额
   * @param periodStart 周期起
   * @param periodEnd 周期止
   * @param granularity 分解粒度（MONTHLY / WEEKLY）
   * @param decompositionMethod 分解方式（EVEN / WEIGHTED）
   * @param weights WEIGHTED 时的相对权重（**长度必须等于分解项个数**；EVEN 时留空）
   * @param status ACTIVE 生效中 / ARCHIVED 已归档（留空 ⇒ 新建时 ACTIVE、修改时保持原状）
   * @param note 备注
   */
  public record TargetInput(
      @NotBlank @Size(min = 1, max = 48) String metricCode,
      @NotBlank @Pattern(regexp = "DEPARTMENT|MEMBER|SHOP|PRODUCT") String scopeType,
      @NotBlank @Size(min = 1, max = 160) String ownerId,
      @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
      @NotNull BigDecimal targetValue,
      @NotNull LocalDate periodStart,
      @NotNull LocalDate periodEnd,
      @NotBlank @Pattern(regexp = "MONTHLY|WEEKLY") String granularity,
      @NotBlank @Pattern(regexp = "EVEN|WEIGHTED") String decompositionMethod,
      @Schema(nullable = true) List<BigDecimal> weights,
      @Schema(nullable = true) @Pattern(regexp = "ACTIVE|ARCHIVED") String status,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  /** 能力声明里的一条「确实拿不到」。 */
  public record TargetGap(String area, String reason, String ownerModule) {}
}
