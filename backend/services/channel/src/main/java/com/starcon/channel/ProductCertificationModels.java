package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 16.2.2.2 认证与资质的传输模型。
 *
 * <p>蓝图：「管理产品认证与资质（CE、FCC、RoHS、PSE、FDA、CCC 等）：认证编号、有效期、适用 SKU、
 * 对应类目要求，支持上传扫描件与到期提醒。输入：认证录入；文件上传；有效期。输出：认证档案 +
 * 到期提醒 + 覆盖情况统计（哪些 SKU 缺认证）。关系：上游 = 6.1.2.3 供应商资质；下游 = 16.2.2.1 预检、
 * 4 刊登。<b>认证缺失是 listing 被抑制与下架的高频原因</b>，且属于"上架时不管、出事才补"的典型滞后项。」
 *
 * <p>🔴 四个形状各自回答一个不同的问题：
 *
 * <ul>
 *   <li>{@link ProductCertificationCapabilities} —— 「这个模块量得到什么、量不到什么、边界在哪」。
 *   <li>{@link CertificationSummary} / {@link CertificationDetail} —— 认证档案（台账与详情）。
 *   <li>{@link ExpiryReminderRow} —— 到期提醒（按剩余天数排序，剩余少的排前面）。
 *   <li>{@link CoverageReport} —— 覆盖情况统计（哪些 SKU 缺认证）。
 * </ul>
 *
 * <p>🔴 <b>时间字段一律是 {@link OffsetDateTime}</b>（对应 {@code timestamptz}）：
 * 有效期是本模块最容易出时间陷阱的字段 —— 用 {@code Timestamp.valueOf(LocalDateTime)} 绑定会
 * 静默偏 −28800 秒，证书「还剩 0 天」会变成「已经过期 8 小时」。所以这里用带偏移的类型，
 * 前端拿到的也是带偏移的 ISO 串，不存在"本地时间"歧义。
 */
public final class ProductCertificationModels {

  private ProductCertificationModels() {}

  // ---------------------------------------------------------------- 能力声明

  /**
   * 能力声明。
   *
   * @param statuses 认证状态词表
   * @param expiryStates 到期状态词表
   * @param documentKinds 扫描件类别词表
   * @param scopes 认证适用范围词表
   * @param defaultWarningDays 默认「即将过期」窗口天数
   * @param maxDocumentBytes 单份扫描件的字节上限（服务端硬限）
   * @param browserProxyBase64LimitBytes 经浏览器代理上传时 base64 后可承载的原文件字节上限
   * @param expirySemantics 到期状态口径（现算，不落库）
   * @param coverageSemantics 覆盖统计口径
   * @param vocabularyNote 认证类型词表口径（内置 ∪ 租户，租户行优先）
   * @param upstreamNote 上游与不可达时的行为说明
   * @param boundaryNote 与相邻模块的边界说明
   */
  public record ProductCertificationCapabilities(
      List<String> statuses,
      List<String> expiryStates,
      List<String> documentKinds,
      List<String> scopes,
      int defaultWarningDays,
      long maxDocumentBytes,
      int browserProxyBase64LimitBytes,
      String expirySemantics,
      String coverageSemantics,
      String vocabularyNote,
      String upstreamNote,
      String boundaryNote) {}

  // ---------------------------------------------------------------- 认证类型词表

  /**
   * 生效词表里的一行（内置 ∪ 租户，租户行优先）。
   *
   * @param code 认证类型编码
   * @param name 中文名
   * @param scope 适用范围（GLOBAL / MARKETPLACE）
   * @param description 说明
   * @param enabled 是否启用（停用后不能挂到新档案上）
   * @param tenantDefined 是否由本租户自定义（false = 内置）
   */
  public record CertificationTypeRow(
      String code,
      String name,
      String scope,
      String description,
      boolean enabled,
      boolean tenantDefined) {}

  /**
   * 租户字典行的写入请求（新增 / 改名 / 停用内置类型都走它）。
   *
   * @param code 认证类型编码（大写字母、数字、下划线、短横线）
   * @param name 中文名
   * @param scope 适用范围
   * @param description 说明
   * @param enabled 是否启用
   * @param sortOrder 排序位
   */
  public record CertificationTypeInput(
      @NotBlank @Size(max = 32) String code,
      @NotBlank @Size(max = 64) String name,
      @NotBlank String scope,
      @Size(max = 500) String description,
      Boolean enabled,
      @Min(0) @Max(9999) Integer sortOrder) {}

  // ---------------------------------------------------------------- 租户策略

  /**
   * 租户级认证策略。
   *
   * @param warningDays 即将过期窗口天数
   * @param note 备注
   * @param updatedAt 最近一次修改时刻
   */
  public record CertificationPolicyView(int warningDays, String note, OffsetDateTime updatedAt) {}

  /**
   * 租户级认证策略的写入请求。
   *
   * @param warningDays 即将过期窗口天数（1..365）
   * @param note 备注
   */
  public record CertificationPolicyInput(
      @NotNull @Min(1) @Max(365) Integer warningDays, @Size(max = 500) String note) {}

  // ---------------------------------------------------------------- 认证档案

  /**
   * 认证档案（台账行）。
   *
   * @param expiryState 到期状态（现算，不落库）
   * @param daysRemaining 剩余天数（负数表示已过期几天）
   */
  public record CertificationSummary(
      String id,
      String code,
      String certificationTypeCode,
      String certificationTypeName,
      String title,
      String certNo,
      String issuer,
      String status,
      String expiryState,
      OffsetDateTime validFrom,
      OffsetDateTime validUntil,
      int daysRemaining,
      int skuCount,
      int categoryCount,
      int documentCount,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 认证档案详情（含适用 SKU / 对应类目 / 扫描件）。
   *
   * @param version 乐观锁版本
   */
  public record CertificationDetail(
      String id,
      String code,
      String certificationTypeCode,
      String certificationTypeName,
      String title,
      String certNo,
      String issuer,
      String issuingCountry,
      String status,
      String expiryState,
      OffsetDateTime validFrom,
      OffsetDateTime validUntil,
      int daysRemaining,
      OffsetDateTime revokedAt,
      String revokedReason,
      String note,
      long version,
      List<CertificationSkuBinding> skus,
      List<CertificationCategoryBinding> categories,
      List<CertificationDocumentRow> documents,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 登记 / 修改认证的请求。
   *
   * @param version 修改时必填的乐观锁版本（登记时留空）
   */
  public record ProductCertificationInput(
      @NotBlank @Size(max = 64) String code,
      @NotBlank @Size(max = 32) String certificationTypeCode,
      @NotBlank @Size(max = 200) String title,
      @Size(max = 64) String certNo,
      @Size(max = 120) String issuer,
      @Size(max = 2) String issuingCountry,
      @NotNull OffsetDateTime validFrom,
      @NotNull OffsetDateTime validUntil,
      @Size(max = 2000) String note,
      @Min(0) Long version) {}

  /** 撤销认证的请求：必须写明理由。 */
  public record CertificationRevokeInput(@NotBlank @Size(max = 500) String reason) {}

  /**
   * 认证适用的一个 SKU。
   *
   * @param skuId catalog 的 SKU 逻辑 id
   */
  public record CertificationSkuBinding(
      String id,
      String skuId,
      String skuCode,
      String skuName,
      String note,
      OffsetDateTime createdAt) {}

  /**
   * 挂适用 SKU 的请求。
   *
   * @param skuId catalog 的 SKU 逻辑 id
   */
  public record CertificationSkuInput(
      @NotNull String skuId,
      @Size(max = 64) String skuCode,
      @Size(max = 200) String skuName,
      @Size(max = 500) String note) {}

  /**
   * 认证对应的一个类目要求。
   *
   * @param categoryId catalog 的类目逻辑 id
   */
  public record CertificationCategoryBinding(
      String id,
      String categoryId,
      String categoryCode,
      String categoryName,
      String requirementNote,
      OffsetDateTime createdAt) {}

  /**
   * 挂对应类目要求的请求。
   *
   * @param categoryId catalog 的类目逻辑 id
   */
  public record CertificationCategoryInput(
      @NotNull String categoryId,
      @Size(max = 64) String categoryCode,
      @Size(max = 100) String categoryName,
      @Size(max = 1000) String requirementNote) {}

  /**
   * 一份扫描件的元数据。
   *
   * @param sizeBytes 文件字节数
   * @param storageKey 受管存储里的 key
   */
  public record CertificationDocumentRow(
      String id,
      String certificationId,
      String documentKind,
      String documentName,
      String mimeType,
      java.math.BigDecimal sizeBytes,
      String storageKey,
      String uploadedBy,
      OffsetDateTime uploadedAt,
      String note,
      OffsetDateTime createdAt) {}

  /**
   * 上传扫描件（JSON 形状：文件本体走 base64）。
   *
   * <p>🔴 浏览器侧走同源代理，代理只转发 JSON（请求体上限约 200,000 字符），
   * 所以 base64 后的原文上限约 140 KB；直连 multipart 上传的服务端硬限是 10 MB。
   * 两者都写在这里，界面据此提示而不是让用户撞一个没有解释的 413。
   *
   * @param fileName 文件名
   * @param contentType MIME 类型
   * @param contentBase64 文件本体的 base64（**不含** data URL 前缀）
   * @param documentKind 文件类别
   * @param note 备注
   */
  public record CertificationDocumentInput(
      @NotBlank @Size(max = 200) String fileName,
      @Size(max = 120) String contentType,
      @NotBlank @Size(max = 20_000_000) String contentBase64,
      @NotBlank String documentKind,
      @Size(max = 500) String note) {}

  // ---------------------------------------------------------------- 要求侧登记

  /**
   * 一条 SKU 的认证要求。
   *
   * @param certificationTypeCode 需要的认证类型
   * @param categoryId 该要求来自哪个类目（可空）
   */
  public record RequirementRow(
      String id,
      String skuId,
      String skuCode,
      String skuName,
      String certificationTypeCode,
      String certificationTypeName,
      String categoryId,
      String categoryCode,
      String categoryName,
      String note,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 登记 SKU 的认证要求。
   *
   * @param skuId catalog 的 SKU 逻辑 id
   * @param certificationTypeCode 需要的认证类型
   * @param categoryId 该要求来自哪个类目（可空）
   */
  public record RequirementInput(
      @NotNull String skuId,
      @Size(max = 64) String skuCode,
      @Size(max = 200) String skuName,
      @NotBlank @Size(max = 32) String certificationTypeCode,
      String categoryId,
      @Size(max = 64) String categoryCode,
      @Size(max = 100) String categoryName,
      @Size(max = 1000) String note) {}

  // ---------------------------------------------------------------- 分页

  /** 认证档案台账（真分页）。 */
  public record CertificationPage(List<CertificationSummary> items, long total, int page, int size) {}

  /** 要求侧登记清单（真分页）。 */
  public record RequirementPage(List<RequirementRow> items, long total, int page, int size) {}

  // ---------------------------------------------------------------- 到期提醒

  /**
   * 一条到期提醒。
   *
   * @param expiryState EXPIRING / EXPIRED
   * @param daysRemaining 剩余天数（负数 = 已过期几天），排序按它升序
   */
  public record ExpiryReminderRow(
      String certificationId,
      String code,
      String certificationTypeCode,
      String certificationTypeName,
      String title,
      String certNo,
      String expiryState,
      OffsetDateTime validUntil,
      int daysRemaining,
      int warningDays,
      int skuCount,
      int documentCount) {}

  /** 到期提醒清单（按剩余天数升序）。 */
  public record ExpiryReminderPage(
      List<ExpiryReminderRow> items, long total, int page, int size, int warningDays) {}

  // ---------------------------------------------------------------- 覆盖统计

  /**
   * 按认证类型汇总的覆盖情况。
   *
   * @param missingSkuCount 缺这个类型认证的 SKU 数
   */
  public record CoverageByType(
      String certificationTypeCode,
      String certificationTypeName,
      long requiredCount,
      long coveredCount,
      long missingCount,
      long missingSkuCount) {}

  /**
   * 按类目汇总的覆盖情况。
   *
   * @param categoryId 类目逻辑 id（要求侧登记里的类目）
   */
  public record CoverageByCategory(
      String categoryId,
      String categoryCode,
      String categoryName,
      long requiredCount,
      long coveredCount,
      long missingCount,
      long missingSkuCount,
      List<String> missingSkuIds) {}

  /**
   * 一个缺认证的 SKU。
   *
   * @param missingTypeCodes 缺哪些认证类型
   */
  public record CoverageMissingSku(
      String skuId, String skuCode, String skuName, List<String> missingTypeCodes) {}

  /**
   * 覆盖情况统计（蓝图点名的输出之一：「哪些 SKU 缺认证」）。
   *
   * @param requiredSkuCount 有认证要求的 SKU 数
   * @param coveredSkuCount 全部要求都已覆盖的 SKU 数
   * @param missingSkuCount 至少缺一个认证的 SKU 数
   * @param byType 按认证类型
   * @param byCategory 按类目
   * @param missingSkus 缺认证的 SKU 明细
   */
  public record CoverageReport(
      long requiredRequirementCount,
      long coveredRequirementCount,
      long missingRequirementCount,
      long requiredSkuCount,
      long coveredSkuCount,
      long missingSkuCount,
      List<CoverageByType> byType,
      List<CoverageByCategory> byCategory,
      List<CoverageMissingSku> missingSkus) {}

  /**
   * 单个 SKU 的覆盖详情。
   *
   * @param coveredTypeCodes 已覆盖的认证类型
   * @param missingTypeCodes 仍缺的认证类型
   */
  public record CoverageSkuDetail(
      String skuId,
      String skuCode,
      String skuName,
      List<CoverageRequirement> requirements,
      List<String> coveredTypeCodes,
      List<String> missingTypeCodes,
      List<String> certificationIds) {}

  /** 一个 SKU 的一条要求及其覆盖情况。 */
  public record CoverageRequirement(
      String certificationTypeCode,
      String certificationTypeName,
      boolean covered,
      String certificationId,
      String certificationCode,
      String expiryState,
      String categoryId,
      String categoryName) {}

  // ---------------------------------------------------------------- 上游候选

  /**
   * catalog 的 SKU 候选（**只读**，跨服务经授权 HTTP API 读）。
   *
   * @param source catalog
   */
  public record ProductCertificationSkuCandidate(
      String id, String code, String name, String category, String spuId, String source) {}

  /**
   * catalog 的类目候选（**只读**）。
   *
   * @param treeKind 树种类（INTERNAL / PLATFORM）
   */
  public record CategoryCandidate(
      String id,
      String code,
      String name,
      String path,
      String treeId,
      String treeKind,
      String source) {}

  /** 候选清单的通用形状。 */
  public record CandidatePage<T>(List<T> items, long total, int page, int size, String source) {}

  /** 概览（页面顶部四张卡）。 */
  public record CertificationOverview(
      long certificationCount,
      long activeCount,
      long expiringCount,
      long expiredCount,
      long revokedCount,
      long coveredSkuCount,
      long missingSkuCount,
      int warningDays) {}
}
