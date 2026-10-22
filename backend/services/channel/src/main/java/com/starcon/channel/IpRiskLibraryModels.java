package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 16.4.1.1 侵权库的传输模型。
 *
 * <p>蓝图：「维护知识产权风险库：品牌商标、专利、版权、肖像权，登记风险等级与适用平台。
 * 输入：侵权条目登记；批量导入。输出：侵权库记录。关系：上游 = 3.3.2 品牌；下游 = 16.4.1.2 检测。
 * <b>侵权检测是刊登前拦截而非事后申诉</b>，成本差一个数量级。」
 *
 * <p>🔴🔴 本模块对外最要紧的四条事实，全部<b>在 API 与页面上如实表达</b>：
 *
 * <ol>
 *   <li><b>权利类型是受控枚举</b>（四种）：{@code TRADEMARK} / {@code PATENT} /
 *       {@code COPYRIGHT} / {@code IMAGE_RIGHT}，**不**留自由文本。</li>
 *   <li><b>风险等级是受控等级集</b>（高 / 中 / 低），**不**是 0..100 的裸数字。</li>
 *   <li><b>适用平台是多对多</b>：{@code platformCodes} 是一个列表 ⇒
 *       「这个平台上有哪些高风险条目」可反查。</li>
 *   <li><b>下游缺口是可查的事实</b>：16.4.1.2 检测与预警当前<b>未交付</b> ⇒
 *       能力声明里 {@code available=false} + {@code ownerModule} + 影响说明，
 *       <b>不编造</b>任何检测结论。</li>
 * </ol>
 */
public final class IpRiskLibraryModels {

  private IpRiskLibraryModels() {}

  // ---------------------------------------------------------------- 能力声明

  /**
   * 一个下游（或维度）的交付状态。
   *
   * @param moduleId 模块号（如 16.4.1.2 / 16.4.2.1）
   * @param moduleName 模块名
   * @param ownerModule 归谁（available=false 时必填）
   * @param available 当前是否真的可用
   * @param impact 未交付 / 已交付的影响说明（界面原样显示）
   */
  public record IpRiskLibraryCapabilityGap(
      String moduleId,
      String moduleName,
      String ownerModule,
      boolean available,
      String impact) {}

  /**
   * 能力声明。
   *
   * @param gaps 下游交付状态清单（**可查的事实**，不是文档里的一句话）
   * @param rightTypes 权利类型值域
   * @param rightTypeLabels 权利类型人话
   * @param riskLevels 风险等级值域
   * @param riskLevelLabels 风险等级人话
   * @param statuses 生命周期值域
   * @param statusLabels 生命周期人话
   * @param termKinds 匹配特征分类值域
   * @param termKindLabels 匹配特征分类人话
   * @param sources 来源值域
   * @param sourceLabels 来源人话
   * @param rightTypeNote 权利类型口径说明
   * @param riskLevelNote 风险等级口径说明
   * @param platformNote 适用平台口径说明
   * @param matchFeatureNote 匹配特征口径说明
   * @param lifecycleNote 生命周期口径说明
   * @param importNote 批量导入口径说明
   * @param upstreamNote 上游说明
   * @param downstreamNote 下游说明
   */
  public record IpRiskLibraryCapabilities(
      List<IpRiskLibraryCapabilityGap> gaps,
      List<String> rightTypes,
      Map<String, String> rightTypeLabels,
      List<String> riskLevels,
      Map<String, String> riskLevelLabels,
      List<String> statuses,
      Map<String, String> statusLabels,
      List<String> termKinds,
      Map<String, String> termKindLabels,
      List<String> sources,
      Map<String, String> sourceLabels,
      String rightTypeNote,
      String riskLevelNote,
      String platformNote,
      String matchFeatureNote,
      String lifecycleNote,
      String importNote,
      String upstreamNote,
      String downstreamNote) {}

  // ---------------------------------------------------------------- 平台

  /**
   * 适用平台（读本服务自己的 {@code platform} 目录 —— **全局码表**，不带 tenant_id）。
   *
   * @param platformCode 平台编码
   * @param platformName 平台名
   * @param supportStatus 平台支持状态
   */
  public record IpRiskLibraryPlatform(
      String platformCode, String platformName, String supportStatus) {}

  // ---------------------------------------------------------------- 上游品牌候选

  /**
   * 3.3.2 品牌候选（catalog 服务，**只读**）。
   *
   * @param brandId 品牌 id（落库时只存这个**逻辑 id**）
   * @param code 品牌编码
   * @param name 品牌名（同时是**可被匹配**的品牌名字段）
   * @param authorizationStatus 品牌授权状态
   * @param source 来源说明
   */
  public record IpRiskLibraryBrandCandidate(
      String brandId, String code, String name, String authorizationStatus, String source) {}

  /**
   * 品牌候选读出结果。
   *
   * @param available 上游是否读到了（false ⇒ 界面显示「读不到」，**不是**「没有品牌」）
   * @param items 候选
   * @param note 读不到 / 读到了什么的原因说明
   */
  public record IpRiskLibraryBrandSet(
      boolean available, List<IpRiskLibraryBrandCandidate> items, String note) {}

  // ---------------------------------------------------------------- 条目

  /**
   * 一条侵权条目。
   *
   * @param entryId 条目 id
   * @param entryCode 条目编码（租户内稳定业务键 · 幂等锚点）
   * @param rightType 权利类型
   * @param rightTypeLabel 权利类型人话
   * @param riskLevel 风险等级
   * @param riskLevelLabel 风险等级人话
   * @param title 条目标题
   * @param rightsHolder 权利人
   * @param brandId 关联的 3.3.2 品牌**逻辑 id**（可空）
   * @param brandName 品牌名（**可被匹配**的字段；可空）
   * @param matchPattern 标识符 / 外观特征描述（可空）
   * @param note 备注
   * @param status 生命周期
   * @param statusLabel 生命周期人话
   * @param disableReason 停用理由（**停用必填**）
   * @param disabledAt 最近一次停用时刻（重新启用时**不抹掉**）
   * @param source 来源（MANUAL / IMPORT）
   * @param sourceLabel 来源人话
   * @param platformCodes 适用平台（**多值**）
   * @param matchTerms 匹配特征（**多值**：关键词 / 短语）
   * @param createdAt 创建时刻
   * @param updatedAt 更新时刻
   */
  public record IpRiskLibraryEntry(
      String entryId,
      String entryCode,
      String rightType,
      String rightTypeLabel,
      String riskLevel,
      String riskLevelLabel,
      String title,
      String rightsHolder,
      String brandId,
      String brandName,
      String matchPattern,
      String note,
      String status,
      String statusLabel,
      String disableReason,
      OffsetDateTime disabledAt,
      String source,
      String sourceLabel,
      List<String> platformCodes,
      List<String> matchTerms,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 侵权库清单（**真分页**）。
   *
   * @param items 条目行
   * @param total 总条数
   * @param page 当前页
   * @param size 每页条数
   * @param activeCount 启用中条数（覆盖整个筛选结果集）
   * @param disabledCount 已停用条数（覆盖整个筛选结果集）
   * @param riskLevelCounts 按风险等级的计数（覆盖整个筛选结果集）
   * @param rightTypeCounts 按权利类型的计数（覆盖整个筛选结果集）
   * @param note 口径说明
   */
  public record IpRiskLibraryEntryPage(
      List<IpRiskLibraryEntry> items,
      long total,
      int page,
      int size,
      long activeCount,
      long disabledCount,
      Map<String, Long> riskLevelCounts,
      Map<String, Long> rightTypeCounts,
      String note) {}

  /** 条目详情（条目 + 匹配特征 + 适用平台）。 */
  public record IpRiskLibraryEntryDetail(
      IpRiskLibraryEntry entry,
      List<IpRiskLibraryMatchTerm> matchTerms,
      List<IpRiskLibraryPlatformRef> platforms,
      String note) {}

  /**
   * 一条匹配特征。
   *
   * @param termId 特征 id
   * @param entryId 所属条目 id
   * @param term 关键词 / 短语原文
   * @param termKind 特征分类
   * @param termKindLabel 特征分类人话
   */
  public record IpRiskLibraryMatchTerm(
      String termId, String entryId, String term, String termKind, String termKindLabel) {}

  /**
   * 一条适用平台。
   *
   * @param platformCode 平台编码
   * @param platformName 平台名
   */
  public record IpRiskLibraryPlatformRef(String platformCode, String platformName) {}

  // ---------------------------------------------------------------- 入参

  /**
   * 登记 / 修改侵权条目的入参。
   *
   * @param entryCode 条目编码（租户内稳定业务键 · 幂等锚点）
   * @param rightType 权利类型（四种之一）
   * @param riskLevel 风险等级（高 / 中 / 低）
   * @param title 条目标题
   * @param rightsHolder 权利人
   * @param brandId 关联的 3.3.2 品牌**逻辑 id**（可空）
   * @param brandName 品牌名（可空 —— 有些专利 / 版权没有品牌名）
   * @param matchPattern 标识符 / 外观特征描述（可空）
   * @param note 备注
   * @param platformCodes 适用平台（**多值**）
   * @param matchTerms 匹配特征（**多值**）
   */
  public record IpRiskLibraryEntryInput(
      String entryCode,
      String rightType,
      String riskLevel,
      String title,
      String rightsHolder,
      String brandId,
      String brandName,
      String matchPattern,
      String note,
      List<String> platformCodes,
      List<IpRiskLibraryTermInput> matchTerms) {}

  /** 一条匹配特征。 */
  public record IpRiskLibraryTermInput(String term, String termKind) {}

  /**
   * 改生命周期的入参。
   *
   * @param status ACTIVE / DISABLED
   * @param reason 停用理由（**停用时必填**；重新启用时可空）
   */
  public record IpRiskLibraryStatusInput(String status, String reason) {}

  /**
   * 登记条目的结果（**幂等**）。
   *
   * @param entry 条目
   * @param created 本次是否真的新建（false ⇒ 命中条目编码，已有记录被原样返回）
   * @param note 口径说明
   */
  public record IpRiskLibraryEntryCreation(
      IpRiskLibraryEntry entry, boolean created, String note) {}

  // ---------------------------------------------------------------- 批量导入

  /**
   * 批量导入的一行。
   *
   * @param entryCode 条目编码（幂等锚点）
   * @param rightType 权利类型
   * @param riskLevel 风险等级
   * @param title 条目标题
   * @param rightsHolder 权利人
   * @param brandId 关联品牌逻辑 id
   * @param brandName 品牌名
   * @param matchPattern 标识符 / 外观特征描述
   * @param note 备注
   * @param platformCodes 适用平台（多值）
   * @param matchTerms 匹配特征（多值）
   */
  public record IpRiskLibraryImportRowInput(
      String entryCode,
      String rightType,
      String riskLevel,
      String title,
      String rightsHolder,
      String brandId,
      String brandName,
      String matchPattern,
      String note,
      List<String> platformCodes,
      List<IpRiskLibraryTermInput> matchTerms) {}

  /**
   * 批量导入的入参。
   *
   * @param sourceName 来源名（文件名 / 来源系统，人可读）
   * @param rows 待导入的行
   */
  public record IpRiskLibraryImportInput(String sourceName, List<IpRiskLibraryImportRowInput> rows) {}

  /**
   * 批量导入的**逐行**结果。
   *
   * @param rowNo 行号（从 1 开始）
   * @param entryCode 该行的条目编码
   * @param outcome INSERTED / SKIPPED / FAILED
   * @param entryId 落库后的条目 id（失败行没有）
   * @param errorField 出问题的**字段**（失败行才有）
   * @param errorMessage 出问题的**原因**（失败行才有）
   */
  public record IpRiskLibraryImportRowResult(
      int rowNo,
      String entryCode,
      String outcome,
      String entryId,
      String errorField,
      String errorMessage) {}

  /**
   * 批量导入的结果。
   *
   * @param batchId 导入批次 id
   * @param sourceName 来源名
   * @param totalRows 总行数
   * @param insertedRows 真正写入的行数
   * @param skippedRows 因**幂等**跳过的行数（条目已存在，未产生重复）
   * @param failedRows 失败行数
   * @param status COMPLETED / PARTIAL
   * @param rows 逐行结果
   * @param note 口径说明
   */
  public record IpRiskLibraryImportResult(
      String batchId,
      String sourceName,
      int totalRows,
      int insertedRows,
      int skippedRows,
      int failedRows,
      String status,
      List<IpRiskLibraryImportRowResult> rows,
      String note) {}

  /**
   * 一次导入批次。
   *
   * @param batchId 批次 id
   * @param sourceName 来源名
   * @param totalRows 总行数
   * @param insertedRows 写入行数
   * @param skippedRows 跳过行数
   * @param failedRows 失败行数
   * @param status COMPLETED / PARTIAL
   * @param createdAt 创建时刻
   */
  public record IpRiskLibraryImportBatch(
      String batchId,
      String sourceName,
      int totalRows,
      int insertedRows,
      int skippedRows,
      int failedRows,
      String status,
      OffsetDateTime createdAt) {}

  /** 导入批次清单（**真分页**）。 */
  public record IpRiskLibraryImportPage(
      List<IpRiskLibraryImportBatch> items, long total, int page, int size, String note) {}

  /** 导入批次详情（批次 + 逐行结果）。 */
  public record IpRiskLibraryImportDetail(
      IpRiskLibraryImportBatch batch, List<IpRiskLibraryImportRowResult> rows, String note) {}

  // ---------------------------------------------------------------- 平台汇总

  /**
   * 一个平台上的侵权风险汇总（**「这个平台上有哪些高风险条目」**的反查入口）。
   *
   * @param platformCode 平台编码
   * @param platformName 平台名
   * @param total 该平台上的条目总数（启用中）
   * @param high 高风险条数
   * @param medium 中风险条数
   * @param low 低风险条数
   * @param disabled 已停用条数
   * @param note 口径说明
   */
  public record IpRiskLibraryPlatformSummary(
      String platformCode,
      String platformName,
      long total,
      long high,
      long medium,
      long low,
      long disabled,
      String note) {}

  /** 平台汇总清单。 */
  public record IpRiskLibraryPlatformSummaryPage(
      List<IpRiskLibraryPlatformSummary> items, String note) {}
}
