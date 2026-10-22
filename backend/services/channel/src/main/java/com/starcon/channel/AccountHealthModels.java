package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 16.1.1.1 账号健康总览的传输模型。
 *
 * <p>蓝图：「按店铺展示账号健康状态：绩效指标（ODR、迟发率、取消率、有效追踪率）、政策违规计数、
 * 申诉状态、风险等级，支持跨店铺对比与风险排序。输入：平台绩效数据同步；风险等级计算规则；
 * 用户数据范围（店铺维度）。输出：店铺健康卡片 + 风险排序 + 需处理项跳转。
 * 关系：上游 = 2 同步；下游 = 16.1.1.2 指标明细、16.1.2.1 违规。
 * <b>风险排序比明细更重要</b>：先处理最可能掉号的店铺。」
 *
 * <p>🔴 四个形状各自回答一个不同的问题：
 *
 * <ul>
 *   <li>{@link AccountHealthCapabilities} —— 「哪些维度量得到、哪些量不到，量不到归谁」。
 *       平台绩效适配器（归 2.4 同步运行）<b>未交付</b>；16.1.1.2 指标明细、16.1.2.1 违规与处罚、
 *       16.1.2.2 申诉管理<b>已交付</b>但<b>本模块未接入</b> —— 这里如实报出，<b>不假装有</b>。</li>
 *   <li>{@link AccountHealthShopCard} —— 「这一家店现在怎么样」：四项绩效指标 + 风险等级 +
 *       <b>逐条依据</b>（哪一项指标、什么值、命中哪条阈值）。</li>
 *   <li>{@link AccountHealthRankingRow} —— 「谁该先处理」：跨店铺风险排序 + 每一家
 *       <b>为什么排在这个位置</b>（{@code rankReason}）。</li>
 *   <li>{@link AccountHealthAssessmentResult} —— 「那一次是怎么判的」：追加式判定凭据，
 *       带着当时用到的阈值快照与规则版本。</li>
 * </ul>
 *
 * <p>🔴 <b>「不知道」与「不合格」是两个状态</b>：{@code metricValue=null} 是<b>量不到</b>，
 * {@code riskLevel=UNKNOWN} 是<b>判不了</b>，两者都<b>不许</b>被显示成「健康」。
 */
public final class AccountHealthModels {

  private AccountHealthModels() {}

  // ---------------------------------------------------------------- 能力声明

  /**
   * 一个下游（或维度）的交付状态。
   *
   * @param moduleId 模块号（如 16.1.2.1）
   * @param moduleName 模块名
   * @param ownerModule 归谁（available=false 时必填）
   * @param available **本模块**当前是否真的用得上（下游未交付、或已交付但本模块未接入 ⇒ false）
   * @param impact 量不到 / 未交付 / 未接入的影响说明（界面原样显示）
   */
  public record AccountHealthCapabilityGap(
      String moduleId, String moduleName, String ownerModule, boolean available, String impact) {}

  /**
   * 能力声明。
   *
   * @param gaps 下游清单（未交付 / 已交付但本模块未接入，均如实标 available=false）
   * @param metricCodes 四项绩效指标的编码
   * @param riskLevels 风险等级词表
   * @param metricSources 指标来源词表（PLATFORM_SYNC / MANUAL）
   * @param metricValueSemantics 指标值的口径（比值 / NULL 的含义）
   * @param thresholdSemantics 阈值口径（服务端算 · 可配置 · 有版本）
   * @param unknownSemantics 「量不到」的语义（不是 0、不是低风险）
   * @param rankingSemantics 排序口径（先处理最可能掉号的店铺）
   * @param platformSyncNote 平台绩效同步的缺口说明
   * @param upstreamNote 上游说明
   * @param downstreamNote 下游说明
   */
  public record AccountHealthCapabilities(
      List<AccountHealthCapabilityGap> gaps,
      List<String> metricCodes,
      List<String> riskLevels,
      List<String> metricSources,
      String metricValueSemantics,
      String thresholdSemantics,
      String unknownSemantics,
      String rankingSemantics,
      String platformSyncNote,
      String upstreamNote,
      String downstreamNote) {}

  // ---------------------------------------------------------------- 指标与依据

  /**
   * 一项绩效指标的当前值。
   *
   * @param metricCode 指标编码
   * @param metricLabel 指标的人话名
   * @param metricValue **0..1 比值**；**null = 该指标量不到**（不是 0）
   * @param source PLATFORM_SYNC / MANUAL（**手工录入的必须显式标 MANUAL**）
   * @param sourceLabel 来源的人话
   * @param periodStart 指标周期起点
   * @param periodEnd 指标周期终点
   * @param capturedAt 采集 / 录入时刻
   * @param note 备注
   */
  public record AccountHealthMetricCard(
      String metricCode,
      String metricLabel,
      @Schema(description = "0..1 比值；null = 该指标量不到（不是 0）") BigDecimal metricValue,
      String source,
      String sourceLabel,
      String periodStart,
      String periodEnd,
      String capturedAt,
      String note) {}

  /**
   * 一条判定依据。
   *
   * @param metricCode 指标编码
   * @param metricLabel 指标的人话名
   * @param level CRITICAL / WARNING / NONE / UNKNOWN
   * @param metricValue 实际值（量不到时为 null）
   * @param thresholdValue 命中的阈值（量不到时为 null）
   * @param thresholdKind WARN / CRITICAL / NONE / UNKNOWN
   * @param direction ABOVE_IS_BAD / BELOW_IS_BAD
   * @param message 人话说明（**界面上直接显示**）
   */
  public record AccountHealthReason(
      String metricCode,
      String metricLabel,
      String level,
      BigDecimal metricValue,
      BigDecimal thresholdValue,
      String thresholdKind,
      String direction,
      String message) {}

  // ---------------------------------------------------------------- 健康卡片

  /**
   * 一家店的账号健康卡片。
   *
   * @param shopId 店铺 id
   * @param shopCode 店铺编码
   * @param shopName 店铺名
   * @param platformCode 平台编码
   * @param platformName 平台名
   * @param riskLevel CRITICAL / WARNING / HEALTHY / UNKNOWN
   * @param riskLevelLabel 等级的人话
   * @param rank 跨店铺风险排序里的名次（1 = 最该先处理）
   * @param rankReason **为什么排在这个位置**
   * @param knownMetricCount 量到了几项指标
   * @param breachCount 命中阈值的指标数
   * @param partial 是否量不全（true ⇒ 不能断言「健康」）
   * @param periodStart 本次口径覆盖的周期起点
   * @param periodEnd 本次口径覆盖的周期终点
   * @param metrics 四项指标（含量不到的那几项，值为 null）
   * @param reasons 逐条依据
   * @param unknownMetricCodes 量不到的指标编码
   * @param ruleSource 阈值来源（BUILTIN_DEFAULT / TENANT_CUSTOM / MIXED）
   * @param ruleVersion 阈值规则版本
   * @param platformSyncAvailable 平台绩效同步是否已接入（**当前恒为 false**）
   */
  public record AccountHealthShopCard(
      String shopId,
      String shopCode,
      String shopName,
      String platformCode,
      String platformName,
      String riskLevel,
      String riskLevelLabel,
      int rank,
      String rankReason,
      int knownMetricCount,
      int breachCount,
      boolean partial,
      String periodStart,
      String periodEnd,
      List<AccountHealthMetricCard> metrics,
      List<AccountHealthReason> reasons,
      List<String> unknownMetricCodes,
      String ruleSource,
      long ruleVersion,
      boolean platformSyncAvailable) {}

  /** 健康卡片列表（**真分页**，排序恒为风险优先）。 */
  public record AccountHealthShopPage(
      List<AccountHealthShopCard> items, long total, int page, int size) {}

  // ---------------------------------------------------------------- 风险排序

  /**
   * 风险排序里的一行。
   *
   * @param rank 名次（1 = 最该先处理）
   * @param shopId 店铺 id
   * @param shopCode 店铺编码
   * @param shopName 店铺名
   * @param platformCode 平台编码
   * @param riskLevel 风险等级
   * @param riskLevelLabel 等级的人话
   * @param knownMetricCount 量到了几项指标
   * @param breachCount 命中阈值的指标数
   * @param partial 是否量不全
   * @param rankReason **为什么排在这个位置**
   */
  public record AccountHealthRankingRow(
      int rank,
      String shopId,
      String shopCode,
      String shopName,
      String platformCode,
      String riskLevel,
      String riskLevelLabel,
      int knownMetricCount,
      int breachCount,
      boolean partial,
      String rankReason) {}

  /**
   * 风险排序清单（**真分页**）。
   *
   * @param items 排序行
   * @param total 参与排序的店铺总数
   * @param page 当前页
   * @param size 每页条数
   * @param criticalCount CRITICAL 店铺数（**不受分页影响**）
   * @param warningCount WARNING 店铺数
   * @param unknownCount UNKNOWN 店铺数
   * @param healthyCount HEALTHY 店铺数
   * @param note 口径说明
   */
  public record AccountHealthRankingPage(
      List<AccountHealthRankingRow> items,
      long total,
      int page,
      int size,
      long criticalCount,
      long warningCount,
      long unknownCount,
      long healthyCount,
      String note) {}

  // ---------------------------------------------------------------- 判定凭据

  /**
   * 一次判定的结论（追加式凭据）。
   *
   * @param assessmentId 判定凭据 id
   * @param shopId 店铺 id
   * @param assessedAt 判定时刻
   * @param periodStart 覆盖的周期起点
   * @param periodEnd 覆盖的周期终点
   * @param riskLevel 风险等级
   * @param riskLevelLabel 等级的人话
   * @param knownMetricCount 量到了几项指标
   * @param breachCount 命中阈值的指标数
   * @param ruleVersion 阈值规则版本
   * @param ruleSource 阈值来源
   * @param thresholds **当时用到的阈值快照**
   * @param reasons 逐条依据
   * @param summary 一句话摘要
   */
  public record AccountHealthAssessmentResult(
      String assessmentId,
      String shopId,
      String assessedAt,
      String periodStart,
      String periodEnd,
      String riskLevel,
      String riskLevelLabel,
      int knownMetricCount,
      int breachCount,
      long ruleVersion,
      String ruleSource,
      Map<String, Object> thresholds,
      List<AccountHealthReason> reasons,
      String summary) {}

  /** 判定历史里的一行。 */
  public record AccountHealthAssessmentSummary(
      String assessmentId,
      String shopId,
      String assessedAt,
      String periodStart,
      String periodEnd,
      String riskLevel,
      String riskLevelLabel,
      int knownMetricCount,
      int breachCount,
      long ruleVersion,
      String ruleSource,
      String summary,
      int reasonCount) {}

  /** 判定历史（**真分页**）。 */
  public record AccountHealthAssessmentPage(
      List<AccountHealthAssessmentSummary> items, long total, int page, int size) {}

  // ---------------------------------------------------------------- 阈值

  /**
   * 一条生效中的阈值规则。
   *
   * @param metricCode 指标编码
   * @param metricLabel 指标的人话名
   * @param direction ABOVE_IS_BAD / BELOW_IS_BAD
   * @param warnValue 警告阈值（0..1 比值）
   * @param criticalValue 严重阈值（0..1 比值）
   * @param ruleVersion 规则版本
   * @param ruleSource BUILTIN_DEFAULT / TENANT_CUSTOM
   * @param ruleSourceLabel 来源的人话
   * @param builtin 是否是模块内置默认（true ⇒ 尚未被租户覆盖）
   * @param editable 是否可改（内置默认也可改 —— 改了就变成租户自定义）
   * @param effectiveFrom 生效时刻（内置默认为 null）
   * @param note 备注
   */
  public record AccountHealthThresholdView(
      String metricCode,
      String metricLabel,
      String direction,
      BigDecimal warnValue,
      BigDecimal criticalValue,
      long ruleVersion,
      String ruleSource,
      String ruleSourceLabel,
      boolean builtin,
      boolean editable,
      String effectiveFrom,
      String note) {}

  /** 阈值清单（固定四条，一次返回）。 */
  public record AccountHealthThresholdList(
      List<AccountHealthThresholdView> items, String note) {}

  /**
   * 阈值修改的入参。
   *
   * @param direction ABOVE_IS_BAD / BELOW_IS_BAD
   * @param warnValue 警告阈值（0..1 比值）
   * @param criticalValue 严重阈值（0..1 比值）
   * @param note 备注
   */
  public record AccountHealthThresholdInput(
      String direction, BigDecimal warnValue, BigDecimal criticalValue, String note) {}

  // ---------------------------------------------------------------- 指标录入

  /**
   * 手工录入一条绩效指标的入参。
   *
   * @param metricCode 指标编码
   * @param metricValue **0..1 比值**；null = 显式登记「该指标量不到」
   * @param periodStart 周期起点
   * @param periodEnd 周期终点
   * @param note 备注
   */
  public record AccountHealthMetricInput(
      String metricCode, BigDecimal metricValue, String periodStart, String periodEnd, String note) {}

  // ---------------------------------------------------------------- 需处理项

  /**
   * 一条**本模块自己真实存在**的需处理项。
   *
   * @param kind RISK_BREACH / DATA_MISSING / PLATFORM_SYNC_UNAVAILABLE
   * @param label 一句话标题
   * @param description 说明
   * @param shopId 店铺 id（可空）
   * @param shopCode 店铺编码（可空）
   * @param shopName 店铺名（可空）
   * @param targetModule 跳转目标所属模块（**只能是真实存在的页面**）
   * @param targetPath 跳转目标路径
   * @param targetLabel 跳转目标的人话
   */
  public record AccountHealthPendingItem(
      String kind,
      String label,
      String description,
      String shopId,
      String shopCode,
      String shopName,
      String targetModule,
      String targetPath,
      String targetLabel) {}

  /** 需处理项清单。 */
  public record AccountHealthPendingList(
      List<AccountHealthPendingItem> items, long total, String note) {}
}
