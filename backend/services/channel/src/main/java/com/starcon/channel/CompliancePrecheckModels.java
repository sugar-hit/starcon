package com.starcon.channel;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 16.2.2.1 合规预检的传输模型。
 *
 * <p>蓝图：「发布前的合规体检：内容违规、认证缺失、尺寸规格不符、材质与描述不符、危险品与运输限制、标签与说明书要求，
 * 多维度检查并给出整改建议。输入：listing 内容草稿；商品属性；认证状态；平台要求。输出：体检报告（分维度得分 +
 * 问题清单 + 整改建议）。关系：上游 = 4.2.1.1 编辑器；下游 = 16.2.1.2 拦截。<b>预检在保存草稿时就跑一次</b>（即时反馈），
 * <b>发布时再跑一次</b>（强制校验）。」
 *
 * <p>🔴 三个形状各自回答一个不同的问题：
 *
 * <ul>
 *   <li>{@link CompliancePrecheckCapabilities} —— 「哪些维度量得到、哪些量不到，量不到归谁」。
 *       五个维度当前<b>量不到</b>（16.2.1.1 规则库 / 16.2.2.2 认证档案未交付），
 *       这里如实报出，<b>不假装有分数</b>。
 *   <li>{@link CompliancePrecheckReport} —— 「这一次的体检结论」：分维度得分 + 问题清单 + 整改建议。
 *   <li>{@link ReportSummary} —— 「历史上跑过哪些次、每次结论如何」（报告历史的**真分页**视图）。
 * </ul>
 *
 * <p>🔴 <b>得分一律是 0..1 的比值</b>，不是百分数：数据库列是 {@code NUMERIC(5,4)}，
 * 装不下 {@code 100.0000}（整数位只有 1 位）。前端直接显示比值，不做二次乘 100。
 */
public final class CompliancePrecheckModels {

  private CompliancePrecheckModels() {}

  // ---------------------------------------------------------------- 能力声明

  /**
   * 逐维度能力声明。
   *
   * @param code 维度编码
   * @param label 中文名
   * @param available 该维度当前是否<b>量得到</b>（false = 量不到，界面显示「未知」而不是 0 分）
   * @param source 依据来源（表 / 端点 / 规则位置）
   * @param ownerModule 量不到时归谁（available=true 时为空）
   * @param impact 量不到的影响说明（界面原样显示）
   */
  public record CompliancePrecheckCapabilityDimension(
      String code,
      String label,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  /**
   * 能力声明。
   *
   * @param dimensions 六个维度的可测性
   * @param dimensionOrder 维度顺序（界面按这个顺序渲染，不按字母序）
   * @param checkStates 检查项状态词表
   * @param severities 严重度词表
   * @param triggerKinds 两种触发语义
   * @param scoreSemantics 得分口径（比值 / 分母规则）
   * @param verdictSemantics 结论口径（PASS / ATTENTION / UNKNOWN 各自意味着什么）
   * @param unknownSemantics 「量不到」的语义（不计入分母，不是 0 分）
   * @param reportNote 报告口径说明
   * @param enforcementNote 拦截归属说明（16.2.1.2 未交付，本模块不拦截）
   * @param boundaryNote 与 4.2.3.2 的边界说明
   * @param upstreamNote 上游说明
   */
  public record CompliancePrecheckCapabilities(
      List<CompliancePrecheckCapabilityDimension> dimensions,
      List<String> dimensionOrder,
      List<String> checkStates,
      List<String> severities,
      List<String> triggerKinds,
      String scoreSemantics,
      String verdictSemantics,
      String unknownSemantics,
      String reportNote,
      String enforcementNote,
      String boundaryNote,
      String upstreamNote) {}

  // ---------------------------------------------------------------- 输入侧

  /**
   * 可供体检的内容草稿（4.2.1.1 的草稿，本模块只读）。
   *
   * @param draftId 草稿 id
   * @param shopId 目标店铺
   * @param platformCode 平台编码
   * @param marketplaceCode 站点编码
   * @param locale 语言
   * @param title 草稿标题（列表上直接看得到是哪条）
   * @param state 草稿状态（DRAFT / SUBMITTED / DISCARDED）
   * @param sourceSkuId 关联的源商品（可空）
   * @param updatedAt 最近更新时刻
   */
  public record CompliancePrecheckDraft(
      String draftId,
      String shopId,
      String platformCode,
      String marketplaceCode,
      String locale,
      String title,
      String state,
      String sourceSkuId,
      String updatedAt) {}

  /** 可体检草稿列表（**真分页**：筛选 / 排序 / LIMIT / OFFSET 全在 SQL 上）。 */
  public record CompliancePrecheckDraftPage(
      List<CompliancePrecheckDraft> items, long total, int page, int size) {}

  // ---------------------------------------------------------------- 报告

  /**
   * 报告头。
   *
   * @param reportId 报告 id
   * @param draftId 被体检的草稿
   * @param triggerKind 触发语义（DRAFT_SAVE / PUBLISH_GATE）
   * @param triggerLabel 触发语义的人话
   * @param state SCORED / UNKNOWN
   * @param overallScore 总体得分（0..1 比值；**null = 未知**，不是 0）
   * @param overallState SCORED / UNKNOWN（总体是不是可知的）
   * @param overallScoreText 总分的展示串（后端算好；量不到时是「未知」）
   * @param verdict PASS / ATTENTION / UNKNOWN
   * @param hasUnknownDimensions 是否有量不到的维度
   * @param totalDimensionCount 维度总数
   * @param scoredDimensionCount 评得出分的维度数
   * @param unknownDimensionCount 量不到的维度数
   * @param checkItemCount 检查项总数
   * @param failedItemCount 违规检查项数
   * @param unknownItemCount 量不到的检查项数
   * @param platformCode 平台编码
   * @param marketplaceCode 站点编码
   * @param locale 语言
   * @param checkedAt 体检时刻
   */
  public record CompliancePrecheckReportHeader(
      String reportId,
      String draftId,
      String triggerKind,
      String triggerLabel,
      String state,
      BigDecimal overallScore,
      String overallState,
      String overallScoreText,
      String verdict,
      boolean hasUnknownDimensions,
      int totalDimensionCount,
      int scoredDimensionCount,
      int unknownDimensionCount,
      int checkItemCount,
      int failedItemCount,
      int unknownItemCount,
      String platformCode,
      String marketplaceCode,
      String locale,
      String checkedAt) {}

  /**
   * 分维度得分。
   *
   * @param dimensionCode 维度编码
   * @param dimensionLabel 维度中文名
   * @param weight 维度权重（0..1）
   * @param score 维度得分（0..1 比值；**null = 该维度量不到**）
   * @param scoreText 维度得分的展示串（后端算好；量不到时是「未知」）
   * @param state SCORED / UNKNOWN
   * @param checkItemCount 该维度的检查项总数
   * @param passedItemCount 通过数
   * @param failedItemCount 违规数
   * @param unknownItemCount 量不到的数（**不进分母**）
   * @param ownerModule 量不到时归谁
   * @param unavailableReason 量不到的原因
   * @param note 补充说明
   */
  public record CompliancePrecheckDimensionScore(
      String dimensionCode,
      String dimensionLabel,
      BigDecimal weight,
      BigDecimal score,
      String scoreText,
      String state,
      int checkItemCount,
      int passedItemCount,
      int failedItemCount,
      int unknownItemCount,
      String ownerModule,
      String unavailableReason,
      String note) {}

  /**
   * 问题清单里的一条。
   *
   * @param findingId finding id
   * @param dimensionCode 所属维度
   * @param dimensionLabel 所属维度中文名
   * @param checkCode 检查项编码
   * @param subjectRef 命中位置（字段键 / 能力键 / 属性键）
   * @param location 人话位置
   * @param severity BLOCKING / WARNING / INFO
   * @param state VIOLATION / UNKNOWN
   * @param message 问题描述
   * @param suggestion 整改建议（**量到的也必须有**）
   * @param ownerModule 归属模块
   * @param evidence 证据（实际值 vs 期望值）
   */
  public record CompliancePrecheckFinding(
      String findingId,
      String dimensionCode,
      String dimensionLabel,
      String checkCode,
      String subjectRef,
      String location,
      String severity,
      String state,
      String message,
      String suggestion,
      String ownerModule,
      Map<String, Object> evidence) {}

  /**
   * 一条整改建议（按维度归并后的行动项）。
   *
   * @param dimensionCode 维度
   * @param dimensionLabel 维度中文名
   * @param ownerModule 这一步归谁做
   * @param action 要做什么
   * @param priority HIGH / MEDIUM / LOW
   */
  public record CompliancePrecheckRemediation(
      String dimensionCode,
      String dimensionLabel,
      String ownerModule,
      String action,
      String priority) {}

  /** 一份完整报告：分维度得分 + 问题清单 + 整改建议。 */
  public record CompliancePrecheckReport(
      CompliancePrecheckReportHeader header,
      List<CompliancePrecheckDimensionScore> dimensions,
      List<CompliancePrecheckFinding> findings,
      List<CompliancePrecheckRemediation> remediation) {}

  /** 报告历史里的一行。 */
  public record CompliancePrecheckReportSummary(
      String reportId,
      String draftId,
      String draftTitle,
      String triggerKind,
      String triggerLabel,
      String state,
      BigDecimal overallScore,
      String overallScoreText,
      String verdict,
      int failedItemCount,
      int unknownItemCount,
      String platformCode,
      String marketplaceCode,
      String checkedAt) {}

  /** 报告历史（**真分页**）。 */
  public record CompliancePrecheckReportPage(
      List<CompliancePrecheckReportSummary> items, long total, int page, int size) {}
}
