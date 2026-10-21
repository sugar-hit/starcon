package com.starcon.channel;

import java.util.List;
import java.util.Map;

/**
 * 16.4.2.1 品牌授权的传输模型。
 *
 * <p>蓝图：「管理品牌授权：授权方、授权范围（店铺/国家/类目/期限）、授权文件、被授权主体，
 * 校验刊登时的品牌使用是否在授权范围内。输入：授权录入；文件上传；有效期。输出：授权档案 + 校验结果。
 * 关系：上游 = 6.1.2.3 供应商资质；下游 = 3.3.2 品牌状态、4.2.3.2 预检。
 * <b>授权过期是「平时没事、上新时出事」的典型</b>，需到期提醒与刊登阻断。」
 *
 * <p>🔴 四个形状各自回答一个不同的问题：
 *
 * <ul>
 *   <li>{@link BrandAuthorizationCapabilities} —— 「哪些能力量得到、哪些量不到，量不到归谁」。
 *       16.4.1.1 侵权库 / 16.4.1.2 检测与预警 / 16.4.2.2 授权凭证 / 16.2.1.1 规则库 /
 *       16.2.1.2 拦截执行当前<b>都未交付</b>，这里如实报出，<b>不假装有</b>。
 *   <li>{@link BrandAuthorizationRecord} + {@link BrandAuthorizationDetail} —— 「授权档案」：
 *       授权方 / 范围 / 文件 / 被授权主体 / 期限。
 *   <li>{@link BrandAuthorizationCheckResult} —— 「这次的刊登品牌使用是否在授权范围内」：
 *       结论 + 逐条原因 + 阻断标记。
 *   <li>{@link BrandAuthorizationExpiryPage} —— 「哪几条授权快到期 / 已过期」（真分页）。
 * </ul>
 *
 * <p>🔴 <b>「不知道」与「不合格」是两个状态</b>：{@code conclusion=UNKNOWN} 是<b>判不了</b>
 * （例如刊登上下文没给国家，无法判定范围是否覆盖），{@code conclusion=OUT_OF_SCOPE} 才是
 * <b>明确不合规</b>。两者都不许丢掉原因。
 */
public final class BrandAuthorizationModels {

  private BrandAuthorizationModels() {}

  // ---------------------------------------------------------------- 能力声明

  /**
   * 一个未交付 / 已交付的下游或维度。
   *
   * @param moduleId 模块号（如 16.4.2.2）
   * @param moduleName 模块名
   * @param ownerModule 归谁（available=false 时必填）
   * @param available 当前是否已交付
   * @param impact 量不到 / 未交付的影响说明（界面原样显示）
   */
  public record BrandAuthorizationCapabilityGap(
      String moduleId, String moduleName, String ownerModule, boolean available, String impact) {}

  /**
   * 能力声明。
   *
   * @param gaps 未交付的下游清单
   * @param scopeKinds 三种范围维度
   * @param conclusions 结论词表
   * @param severities 分级词表
   * @param expiryStates 到期状态词表
   * @param verdictSemantics 结论口径（每种结论各自意味着什么）
   * @param unknownSemantics 「判不了」的语义（不是「不合格」，也不是「合格」）
   * @param blockingSemantics 阻断口径（服务端强制，��端提示不构成防线）
   * @param expirySemantics 到期口径（DB 时钟 + 剩余天数）
   * @param boundaryNote 与 catalog 3.2.1.2 的边界说明
   * @param upstreamNote 上游说明
   * @param downstreamNote 下游说明
   */
  public record BrandAuthorizationCapabilities(
      List<BrandAuthorizationCapabilityGap> gaps,
      List<String> scopeKinds,
      List<String> conclusions,
      List<String> severities,
      List<String> expiryStates,
      String verdictSemantics,
      String unknownSemantics,
      String blockingSemantics,
      String expirySemantics,
      String boundaryNote,
      String upstreamNote,
      String downstreamNote) {}

  // ---------------------------------------------------------------- 授权档案

  /**
   * 授权档案的一行。
   *
   * @param authorizationId 授权 id
   * @param brandId 03 品牌 id（catalog 逻辑 id）
   * @param brandName 品牌名快照
   * @param grantor 授权方
   * @param licenseeName 被授权主体
   * @param licenseeType 被授权主体类型
   * @param qualificationId 6.1.2.3 供应商资质 id（trade 服务逻辑 id，可空）
   * @param effectiveFrom 授权期限起点
   * @param effectiveTo 授权期限终点
   * @param enabled 是否启用
   * @param ruleVersion 判定规则版本
   * @param expiryState 到期状态（ACTIVE / EXPIRING / EXPIRED / NOT_YET_EFFECTIVE / DISABLED）
   * @param expiryStateLabel 到期状态的人话
   * @param daysToExpiry 距到期天数（负 = 已过期 N 天）
   * @param scopeCount 范围行数
   * @param documentCount 文件数
   * @param note 备注
   * @param createdAt 创建时刻
   * @param updatedAt 最近更新时刻
   */
  public record BrandAuthorizationRecord(
      String authorizationId,
      String brandId,
      String brandName,
      String grantor,
      String licenseeName,
      String licenseeType,
      String qualificationId,
      String effectiveFrom,
      String effectiveTo,
      boolean enabled,
      long ruleVersion,
      String expiryState,
      String expiryStateLabel,
      int daysToExpiry,
      int scopeCount,
      int documentCount,
      String note,
      String createdAt,
      String updatedAt) {}

  /** 一行授权范围。 */
  public record BrandAuthorizationScopeLine(
      String scopeKind, String scopeValue, String scopeLabel) {}

  /** 一份授权文件（**只有引用与元数据**，文件本体不在这里）。 */
  public record BrandAuthorizationDocumentRow(
      String documentId,
      String fileName,
      String contentType,
      long sizeBytes,
      String storageKey,
      String uploadedAt,
      String note) {}

  /** 授权档案详情：档案 + 范围 + 文件。 */
  public record BrandAuthorizationDetail(
      BrandAuthorizationRecord record,
      List<BrandAuthorizationScopeLine> scopes,
      List<BrandAuthorizationDocumentRow> documents) {}

  /** 授权档案列表（**真分页**）。 */
  public record BrandAuthorizationPage(
      List<BrandAuthorizationRecord> items, long total, int page, int size) {}

  // ---------------------------------------------------------------- 录入

  /** 一行范围（录入用）。 */
  public record BrandAuthorizationScopeInput(
      String scopeKind, String scopeValue, String scopeLabel) {}

  /** 一份授权文件（录入用；**只登记引用与元数据**）。 */
  public record BrandAuthorizationDocumentInput(
      String fileName,
      String contentType,
      long sizeBytes,
      String storageKey,
      String uploadedAt,
      String note) {}

  /**
   * 授权录入 / 修改的入参。
   *
   * <p>🔴 名字里带 {@code Registration} 是**必需**的，不是啰嗦：catalog 3.2.1.2 已经有一个
   * {@code BrandAuthorizationInput}（品牌档案自带的授权凭证入参），两个服务同名 DTO 会被
   * {@code tools/codegen/catalog-contracts.py} 的跨服务覆盖检测拦下（谁多出 / 谁独有字段会消失），
   * 而桶文件按服务顺序绑定第一份 ⇒ catalog 那份会把本模块的入参整份遮掉。
   * 改回 {@code BrandAuthorizationInput} 会让 codegen 直接中止。
   */
  public record BrandAuthorizationRegistrationInput(
      String brandId,
      String brandName,
      String grantor,
      String licenseeName,
      String licenseeType,
      String qualificationId,
      String effectiveFrom,
      String effectiveTo,
      Boolean enabled,
      String note,
      List<BrandAuthorizationScopeInput> scopes,
      List<BrandAuthorizationDocumentInput> documents) {}

  // ---------------------------------------------------------------- 校验

  /** 校验的入参：一次刊登上下文里的品牌使用。 */
  public record BrandAuthorizationCheckRequest(
      String brandId, String shopId, String countryCode, String categoryId) {}

  /**
   * 一条原因。
   *
   * @param code 原因码（机器可读）
   * @param label 原因的人话标题
   * @param message 原因说明（**必须能直接显示给运营**）
   * @param severity BLOCKING / REVIEW / NONE
   * @param authorizationId 相关授权 id（没有则为 null）
   * @param authorizationLabel 相关授权的标题（授权方 / 被授权主体）
   */
  public record BrandAuthorizationReason(
      String code,
      String label,
      String message,
      String severity,
      String authorizationId,
      String authorizationLabel) {}

  /**
   * 一次校验的结论。
   *
   * @param checkId 判定凭据 id
   * @param brandId 被校验的品牌
   * @param brandName 品牌名快照
   * @param shopId 刊登上下文里的店铺
   * @param countryCode 刊登上下文里的国家
   * @param categoryId 刊登上下文里的类目
   * @param evaluatedAt 判定时刻（DB 时钟）
   * @param ruleVersion 判定用的规则版本
   * @param inScope 是否在授权范围内
   * @param blocking 是否阻断刊登
   * @param severity BLOCKING / REVIEW / NONE
   * @param conclusion 结论
   * @param matchedAuthorizationId 命中了哪条授权
   * @param reasons 逐条原因（**非 IN_SCOPE 时必非空**）
   * @param snapshot 判定时的输入快照
   */
  public record BrandAuthorizationCheckResult(
      String checkId,
      String brandId,
      String brandName,
      String shopId,
      String countryCode,
      String categoryId,
      String evaluatedAt,
      long ruleVersion,
      boolean inScope,
      boolean blocking,
      String severity,
      String conclusion,
      String matchedAuthorizationId,
      List<BrandAuthorizationReason> reasons,
      Map<String, Object> snapshot) {}

  /**
   * 刊登闸门结论（服务端算出来的阻断判定）。
   *
   * @param allowed 是否允许刊登（{@code blocking=false} 时为 true）
   * @param blocking 是否阻断
   * @param severity 分级
   * @param conclusion 结论
   * @param reasons 逐条原因
   * @param evaluatedAt 判定时刻
   * @param ruleVersion 规则版本
   * @param checkId 判定凭据 id
   * @param brandId 被校验的品牌
   */
  public record BrandAuthorizationPublishGate(
      boolean allowed,
      boolean blocking,
      String severity,
      String conclusion,
      List<BrandAuthorizationReason> reasons,
      String evaluatedAt,
      long ruleVersion,
      String checkId,
      String brandId) {}

  /** 校验历史里的一行。 */
  public record BrandAuthorizationCheckSummary(
      String checkId,
      String brandId,
      String brandName,
      String shopId,
      String countryCode,
      String categoryId,
      String evaluatedAt,
      long ruleVersion,
      boolean inScope,
      boolean blocking,
      String severity,
      String severityLabel,
      String conclusion,
      String conclusionLabel,
      String matchedAuthorizationId,
      List<BrandAuthorizationReason> reasons,
      int reasonCount) {}

  /** 校验历史（**真分页**）。 */
  public record BrandAuthorizationCheckPage(
      List<BrandAuthorizationCheckSummary> items, long total, int page, int size) {}

  // ---------------------------------------------------------------- 到期提醒

  /** 到期提醒清单里的一行。 */
  public record BrandAuthorizationExpiryRow(
      String authorizationId,
      String brandId,
      String brandName,
      String grantor,
      String licenseeName,
      String effectiveTo,
      String expiryState,
      String expiryStateLabel,
      int daysToExpiry,
      boolean enabled,
      String scopeSummary,
      String note) {}

  /** 到期提醒清单（**真分页**，计数不受分页影响）。 */
  public record BrandAuthorizationExpiryPage(
      List<BrandAuthorizationExpiryRow> items,
      long total,
      int page,
      int size,
      long expiringCount,
      long expiredCount,
      String note) {}

  // ---------------------------------------------------------------- 候选

  /** 一个候选（品牌 / 被授权主体）。 */
  public record BrandAuthorizationCandidate(
      String id, String code, String name, String status, String note) {}

  /** 候选清单。 */
  public record BrandAuthorizationCandidatePage(
      List<BrandAuthorizationCandidate> items, long total, String note) {}

  /** 一个可选店铺（连同它启用的国家）。 */
  public record BrandAuthorizationShopOption(
      String shopId, String shopCode, String shopName, String platformCode, List<String> countryCodes) {}
}
