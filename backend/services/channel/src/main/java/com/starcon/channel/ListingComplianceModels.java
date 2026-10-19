package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 4.2.3.2 合规预检（违禁词/侵权） 的传输模型。
 *
 * <p>蓝图：「发布前扫描内容的违规风险：违禁词/敏感词命中、极限词、品牌侵权（外观/商标）、平台类目必填缺失、
 * 图片不合规（尺寸/白底/水印）、认证缺失。输入：内容草稿；租户违禁词库；平台规则库；16 合规的侵权与认证状态；
 * 03 品牌授权状态。输出：风险清单（风险等级、命中位置、规则来源、建议替换）；阻断项（未修复不允许发布）与提醒项。
 * 关系：上游 = 16 合规与风控、03 品牌、02 平台规则；下游 = 4.3.1.1（阻断发布）。
 * <b>阻断项在服务端强制校验</b>，前端提示不能作为唯一防线。」
 *
 * <p>🔴 模型层就把四件事钉死：
 *
 * <ol>
 *   <li><b>风险清单四要素缺一不可</b>：{@link ListingComplianceRiskRow} 的 {@code riskLevel}（风险等级）、
 *       {@code fieldKey} + {@code fieldLabel} + {@code matchedText} + {@code occurrenceIndex}（命中位置）、
 *       {@code ruleSource}（规则来源）、{@code suggestion}（建议替换）**四样都在**，
 *       且 DB 层有 CHECK 与触发器双重保证（不是靠前端自觉）。
 *   <li><b>「不知道」是一个状态</b>：{@link ListingComplianceDimension} 的 {@code state} 取
 *       {@code CHECKED} / {@code UNKNOWN}；UNKNOWN 的维度带 {@code ownerModule}，
 *       <b>不计入已判定维度的分母、也不算通过</b>。
 *   <li><b>结论三态</b>：{@code PASS}（无阻断项且全部维度已判定）/ {@code BLOCKED}（有阻断项）/
 *       {@code INCOMPLETE}（当前没有已知阻断项，但有维度没量到 —— <b>不能说「合规通过」</b>）。
 *   <li><b>阻断项的拦截在服务端</b>：{@link ListingCompliancePublishGate} 是服务端算出来的发布闸门，
 *       前端只负责把它显示清楚，<b>不把前端的区分当作拦截</b>。
 * </ol>
 *
 * <p>载荷一律用具名 {@code record}，不用 {@code Map<String,Object>}：Map 的值类型会被 springdoc 推成 object，
 * codegen 出来是空对象 {@code {}}（9.6.1.3 已踩过）。
 */
public final class ListingComplianceModels {
  private ListingComplianceModels() {}

  // ================================================================ 能力声明

  /**
   * 逐维度能力声明。
   *
   * <p>🔴 未交付的段一律 {@code available=false} + {@code ownerModule} + 影响说明，
   * **不假装有**：16.2.1.1 合规规则库、16.2.2.2 认证与资质、
   * 16.2.2.1 合规预检（体检报告，同批并行车道）、4.3.1.1 一键刊登。
   */
  public record ListingComplianceCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  public record ListingComplianceCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false + ownerModule")
          List<ListingComplianceCapabilityDimension> dimensions,
      @Schema(description = "风险维度取值域") List<String> riskDimensions,
      @Schema(description = "风险等级取值域：CRITICAL / HIGH / MEDIUM / LOW") List<String> riskLevels,
      @Schema(description = "处置取值域：BLOCKING 阻断项 / WARNING 提醒项") List<String> severities,
      @Schema(description = "结论取值域：PASS / BLOCKED / INCOMPLETE") List<String> conclusions,
      @Schema(description = "维度状态取值域：CHECKED 已判定 / UNKNOWN 量不到") List<String> dimensionStates,
      @Schema(description = "🔴 阻断项在服务端强制校验的说明；前端据此把「阻断」显示成不可发布")
          String serverEnforcementNote,
      @Schema(description = "🔴 与 16.2.2.1 合规预检（体检报告）的边界说明") String boundaryNote,
      @Schema(description = "上游 / 下游说明") String relationNote) {}

  // ================================================================ 扫描

  /** 一次扫描请求：扫哪份草稿。 */
  public record ListingComplianceScanRequest(
      @Schema(description = "被扫描的编辑器草稿 id（4.2.1.1 listing_editor_draft）") @NotNull UUID draftId,
      @Schema(description = "触发来源：MANUAL 人工预检 / PRE_PUBLISH 发布前预检")
          @NotBlank
          @Size(max = 32)
          String triggerSource) {}

  /**
   * 一个维度的判定结论。
   *
   * <p>🔴 {@code state=UNKNOWN} 表示这一维度<b>量不到</b>（上游未交付 / 读不到），
   * 带 {@code ownerModule} 指回落点；它**不计入已判定维度的分母、也不算通过**。
   */
  public record ListingComplianceDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度中文名") String label,
      @Schema(description = "CHECKED 已判定 / UNKNOWN 量不到（**不算通过**）") String state,
      @Schema(description = "本维度产出的风险条数") int riskCount,
      @Schema(description = "本维度的阻断项条数") int blockingCount,
      @Schema(description = "本维度的提醒项条数") int warningCount,
      @Schema(description = "数据来源（表 / 上游端点）") String source,
      @Schema(description = "量不到时的归属模块；非空表示这一段没有取到", nullable = true)
          String ownerModule,
      @Schema(description = "说明（前端原样展示）") String note) {}

  /**
   * 扫描结果（输出一：风险清单的宿主）。
   *
   * <p>🔴 {@code conclusion} 三态：PASS / BLOCKED / INCOMPLETE。
   * {@code INCOMPLETE} 的意思是「当前没有已知阻断项，但有维度没量到」——
   * 界面**不能**把它渲染成「合规通过」。
   */
  public record ListingComplianceScanResult(
      @Schema(description = "扫描 id") UUID scanId,
      @Schema(description = "被扫描的草稿 id") UUID draftId,
      @Schema(description = "目标店铺 id") UUID shopId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "站点编码") String marketplaceCode,
      @Schema(description = "语言") String locale,
      @Schema(description = "COMPLETED / PARTIAL（有维度没量到）") String state,
      @Schema(description = "PASS / BLOCKED / INCOMPLETE") String conclusion,
      @Schema(description = "触发来源：MANUAL / PRE_PUBLISH") String triggerSource,
      @Schema(description = "风险总条数") int riskCount,
      @Schema(description = "🔴 阻断项条数（未修复不允许发布）") int blockingCount,
      @Schema(description = "提醒项条数") int warningCount,
      @Schema(description = "已判定维度数") int evaluatedDimensionCount,
      @Schema(description = "应判定维度总数") int totalDimensionCount,
      @Schema(description = "逐维度结论") List<ListingComplianceDimension> dimensions,
      @Schema(description = "🔴 风险清单（每条带风险等级 / 命中位置 / 规则来源 / 建议替换）")
          List<ListingComplianceRiskRow> risks,
      @Schema(description = "扫描时刻") OffsetDateTime scannedAt) {}

  /**
   * 一条风险（输出一：风险清单的一条）。
   *
   * <p>🔴 四要素：
   *
   * <ul>
   *   <li>风险等级 = {@code riskLevel}
   *   <li>命中位置 = {@code fieldKey} + {@code fieldLabel} + {@code matchedText} + {@code occurrenceIndex}
   *   <li>规则来源 = {@code ruleSource}（+ {@code ruleOwnerModule}）
   *   <li>建议替换 = {@code suggestion}
   * </ul>
   */
  public record ListingComplianceRiskRow(
      @Schema(description = "风险 id") UUID riskId,
      @Schema(description = "扫描 id") UUID scanId,
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度中文名") String dimensionLabel,
      @Schema(description = "BLOCKING 阻断项 / WARNING 提醒项") String severity,
      @Schema(description = "🔴 风险等级：CRITICAL / HIGH / MEDIUM / LOW") String riskLevel,
      @Schema(description = "风险码") String code,
      @Schema(description = "🔴 命中位置：字段键（TITLE / BULLETS / DESCRIPTION / IMAGES / …）")
          String fieldKey,
      @Schema(description = "🔴 命中位置：字段中文名") String fieldLabel,
      @Schema(description = "🔴 命中位置：命中的原文", nullable = true) String matchedText,
      @Schema(description = "🔴 命中位置：在列表型字段里是第几项（0 起）；非列表字段为 -1")
          int occurrenceIndex,
      @Schema(description = "🔴 规则来源") String ruleSource,
      @Schema(description = "规则的归属模块", nullable = true) String ruleOwnerModule,
      @Schema(description = "🔴 建议替换") String suggestion,
      @Schema(description = "人话说明（前端原样展示）") String message,
      @Schema(description = "该风险的归属模块", nullable = true) String ownerModule,
      @Schema(description = "证据（命中的上下文 / 上游返回摘要）") java.util.Map<String, Object> evidence,
      @Schema(description = "OPEN 未处理 / RESOLVED 已修复 / IGNORED 已忽略") String status,
      @Schema(description = "创建时刻") OffsetDateTime createdAt) {}

  /** 扫描历史的一行（真分页）。 */
  public record ListingComplianceScanRow(
      @Schema(description = "扫描 id") UUID scanId,
      @Schema(description = "草稿 id") UUID draftId,
      @Schema(description = "目标店铺 id") UUID shopId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "站点编码") String marketplaceCode,
      @Schema(description = "语言") String locale,
      @Schema(description = "COMPLETED / PARTIAL") String state,
      @Schema(description = "PASS / BLOCKED / INCOMPLETE") String conclusion,
      @Schema(description = "触发来源") String triggerSource,
      @Schema(description = "风险总条数") int riskCount,
      @Schema(description = "阻断项条数") int blockingCount,
      @Schema(description = "提醒项条数") int warningCount,
      @Schema(description = "已判定维度数") int evaluatedDimensionCount,
      @Schema(description = "应判定维度总数") int totalDimensionCount,
      @Schema(description = "扫描时刻") OffsetDateTime scannedAt) {}

  /** 扫描历史分页。 */
  public record ListingComplianceScanPage(
      @Schema(description = "当前页的扫描记录") List<ListingComplianceScanRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size) {}

  // ================================================================ 发布闸门

  /**
   * 🔴 发布闸门（服务端算出来的结论）。
   *
   * <p>这才是「阻断项在服务端强制校验」的对外形状：{@code allowed=false} 时**服务端不让发布**，
   * 前端只是把这件事显示清楚。<b>前端把阻断项和提醒项分区显示 ≠ 拦截</b>。
   */
  public record ListingCompliancePublishGate(
      @Schema(description = "草稿 id") UUID draftId,
      @Schema(description = "🔴 是否允许发布（有未修复的阻断项就不允许）") boolean allowed,
      @Schema(description = "NOT_SCANNED 没扫过 / SCANNED 扫过") String state,
      @Schema(description = "PASS / BLOCKED / INCOMPLETE") String conclusion,
      @Schema(description = "🔴 阻断项条数") int blockingCount,
      @Schema(description = "提醒项条数") int warningCount,
      @Schema(description = "已判定维度数") int evaluatedDimensionCount,
      @Schema(description = "应判定维度总数") int totalDimensionCount,
      @Schema(description = "🔴 逐条阻断项（未修复不允许发布）") List<ListingComplianceRiskRow> blockers,
      @Schema(description = "提醒项") List<ListingComplianceRiskRow> warnings,
      @Schema(description = "最近一次扫描 id；没扫过为空串") String scanId,
      @Schema(description = "🔴 拦截说明（前端原样展示）") String note) {}

  // ================================================================ 违禁词库

  /** 词库录入请求。 */
  public record ListingComplianceWordInput(
      @Schema(description = "词（大小写不敏感，录入时会被归一化）") @NotBlank @Size(max = 200) String word,
      @Schema(description = "词的类型：PROHIBITED / SENSITIVE / SUPERLATIVE / RESTRICTED") @NotBlank
          @Size(max = 32)
          String category,
      @Schema(description = "BLOCKING 阻断项 / WARNING 提醒项") @NotBlank @Size(max = 16) String severity,
      @Schema(description = "风险等级：CRITICAL / HIGH / MEDIUM / LOW") @NotBlank @Size(max = 16)
          String riskLevel,
      @Schema(description = "🔴 规则来源（这条词是谁定的、依据是什么）") @NotBlank @Size(max = 200)
          String ruleSource,
      @Schema(description = "🔴 建议替换（命中之后应该改成什么）") @NotBlank @Size(max = 500) String suggestion,
      @Schema(description = "平台编码；空 = 全部平台") @Size(max = 32) String platformCode,
      @Schema(description = "语言；空 = 全部语言") @Size(max = 16) String locale,
      @Schema(description = "备注") @Size(max = 500) String note) {}

  /** 词库一行。 */
  public record ListingComplianceWordRow(
      @Schema(description = "词 id") UUID wordId,
      @Schema(description = "词（归一化后）") String word,
      @Schema(description = "录入时的原样") String displayWord,
      @Schema(description = "词的类型") String category,
      @Schema(description = "BLOCKING / WARNING") String severity,
      @Schema(description = "风险等级") String riskLevel,
      @Schema(description = "🔴 规则来源") String ruleSource,
      @Schema(description = "🔴 建议替换") String suggestion,
      @Schema(description = "平台编码；空 = 全部平台", nullable = true) String platformCode,
      @Schema(description = "语言；空 = 全部语言", nullable = true) String locale,
      @Schema(description = "是否启用") boolean enabled,
      @Schema(description = "备注") String note,
      @Schema(description = "版本（乐观锁）") long version,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  /** 词库分页。 */
  public record ListingComplianceWordPage(
      @Schema(description = "当前页的词") List<ListingComplianceWordRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size) {}

  // ================================================================ 可扫描的草稿

  /** 一个可扫描的编辑器草稿（来源选择用）。 */
  public record ListingComplianceDraftRow(
      @Schema(description = "草稿 id") UUID draftId,
      @Schema(description = "目标店铺 id") UUID shopId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "站点编码") String marketplaceCode,
      @Schema(description = "语言") String locale,
      @Schema(description = "源商品 SKU id", nullable = true) UUID sourceSkuId,
      @Schema(description = "关联的线上 listing id", nullable = true) UUID listingId,
      @Schema(description = "目标平台类目路径") String categoryPath,
      @Schema(description = "草稿状态：DRAFT / SUBMITTED / DISCARDED") String state,
      @Schema(description = "最近一次扫描的结论；没扫过为空串") String lastConclusion,
      @Schema(description = "最近一次扫描的阻断项条数；没扫过为 0") int lastBlockingCount,
      @Schema(description = "最近更新时刻") OffsetDateTime updatedAt) {}

  /** 可扫描草稿分页。 */
  public record ListingComplianceDraftPage(
      @Schema(description = "当前页的草稿") List<ListingComplianceDraftRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size) {}

  // ================================================================ 筛选项词表

  /** 筛选项与取值域（不硬编码在前端）。 */
  public record ListingComplianceFilterOptions(
      @Schema(description = "词的类型取值域") List<String> wordCategories,
      @Schema(description = "风险等级取值域") List<String> riskLevels,
      @Schema(description = "处置取值域") List<String> severities,
      @Schema(description = "风险维度取值域") List<String> dimensions,
      @Schema(description = "平台候选（真实数据）") List<ListingCompliancePlatformOption> platforms,
      @Schema(description = "店铺候选（真实数据）") List<ListingComplianceShopOption> shops) {}

  public record ListingCompliancePlatformOption(
      @Schema(description = "平台编码") String code, @Schema(description = "平台名") String name) {}

  public record ListingComplianceShopOption(
      @Schema(description = "店铺 id") UUID id,
      @Schema(description = "店铺编码") String code,
      @Schema(description = "店铺名") String name,
      @Schema(description = "平台编码") String platformCode) {}

  // ================================================================ 风险处置

  /** 人工处置一条风险（标记已修复 / 已忽略）。 */
  public record ListingComplianceRiskDecisionRequest(
      @Schema(description = "RESOLVED 已修复 / IGNORED 已忽略 / OPEN 重新打开") @NotBlank @Size(max = 16)
          String status,
      @Schema(description = "处置说明") @Size(max = 500) String note) {}

  /** 一批风险的处置请求。 */
  public record ListingComplianceRiskDecisionBatchRequest(
      @Schema(description = "要处置的风险 id") @NotEmpty List<UUID> riskIds,
      @Schema(description = "RESOLVED 已修复 / IGNORED 已忽略 / OPEN 重新打开") @NotBlank @Size(max = 16)
          String status,
      @Schema(description = "处置说明") @Size(max = 500) String note) {}
}
