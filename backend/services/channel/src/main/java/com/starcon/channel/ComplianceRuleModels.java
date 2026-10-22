package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 16.2.1.1 规则库的传输模型。
 *
 * <p>蓝图：「管理合规规则库：规则类型（违禁词、极限词、侵权词、认证要求、禁限售、标签要求、材质要求）、
 * 严重度（阻断/警告/提示）、适用平台与站点、生效时间与版本。输入：规则定义与词库上传；版本更新；适用范围配置。
 * 输出：规则集 + 生效版本。关系：上游 = 平台政策同步 + 人工维护；下游 = 16.2.1.2 执行、4.2.3.2 预检。
 * <b>规则版本化是关键</b>：政策变化时历史商品的判定结果要可解释，不能因为规则库更新就说过去的商品当时违规。」
 *
 * <p>🔴🔴 本模块对外最要紧的三条事实，全部<b>在 API 与页面上如实表达</b>：
 *
 * <ol>
 *   <li><b>版本不可改写</b>：改规则 = 新增一个版本。版本详情里给出
 *       {@code effectiveFrom} / {@code effectiveTo} 与 {@code inForce}，
 *       页面据此显示「当时生效的是哪一版」。</li>
 *   <li><b>严重度落在版本上</b>：{@code severity} 是版本的属性，
 *       <b>没有</b>一个可以被单独改掉的「规则严重度」列。</li>
 *   <li><b>下游缺口是可查的事实</b>：16.2.1.2 拦截执行当前<b>未交付</b> ⇒
 *       能力声明里 {@code available=false} + {@code ownerModule} + 影响说明，
 *       <b>不编造</b>指向不存在入口的放行形状。</li>
 * </ol>
 */
public final class ComplianceRuleModels {

  private ComplianceRuleModels() {}

  // ---------------------------------------------------------------- 能力声明

  /**
   * 一个下游（或维度）的交付状态。
   *
   * @param moduleId 模块号（如 16.2.1.2 / 4.2.3.2）
   * @param moduleName 模块名
   * @param ownerModule 归谁（available=false 时必填）
   * @param available 当前是否真的可用
   * @param impact 未交付 / 已交付的影响说明（界面原样显示）
   */
  public record ComplianceRuleCapabilityGap(
      String moduleId,
      String moduleName,
      String ownerModule,
      boolean available,
      String impact) {}

  /**
   * 能力声明。
   *
   * @param gaps 下游交付状态清单（**可查的事实**，不是文档里的一句话）
   * @param ruleTypes 规则类型值域
   * @param ruleTypeLabels 规则类型人话
   * @param severities 严重度值域
   * @param severityLabels 严重度人话
   * @param statuses 规则生命周期值域
   * @param statusLabels 规则生命周期人话
   * @param termKinds 词条分类值域
   * @param termKindLabels 词条分类人话
   * @param wordRuleTypes 带词条集合的规则类型（违禁词 / 极限词 / 侵权词）
   * @param ruleTypeNote 规则类型与版本归属的口径说明
   * @param severityNote 严重度口径说明
   * @param versioningNote 版本化口径说明（**加粗句**）
   * @param wordLibraryNote 词库口径说明
   * @param scopeNote 适用范围口径说明
   * @param upstreamNote 上游说明
   * @param downstreamNote 下游说明
   */
  public record ComplianceRuleCapabilities(
      List<ComplianceRuleCapabilityGap> gaps,
      List<String> ruleTypes,
      Map<String, String> ruleTypeLabels,
      List<String> severities,
      Map<String, String> severityLabels,
      List<String> statuses,
      Map<String, String> statusLabels,
      List<String> termKinds,
      Map<String, String> termKindLabels,
      List<String> wordRuleTypes,
      String ruleTypeNote,
      String severityNote,
      String versioningNote,
      String wordLibraryNote,
      String scopeNote,
      String upstreamNote,
      String downstreamNote) {}

  // ---------------------------------------------------------------- 店铺范围

  /**
   * 店铺在本模块里的覆盖情况。
   *
   * @param shopId 店铺 id
   * @param shopCode 店铺编码
   * @param shopName 店铺名
   * @param platformId 平台 id（**配适用范围时要用**，所以必须给出来）
   * @param platformCode 平台编码
   * @param platformName 平台名
   * @param ruleCount 适用的规则总数
   * @param effectiveRuleCount 其中此刻正在生效的规则数
   */
  public record ComplianceRuleShop(
      String shopId,
      String shopCode,
      String shopName,
      String platformId,
      String platformCode,
      String platformName,
      int ruleCount,
      int effectiveRuleCount) {}

  /** 店铺范围清单（**真分页**，数据来自本服务自己的店铺目录）。 */
  public record ComplianceRuleShopPage(
      List<ComplianceRuleShop> items, long total, int page, int size) {}

  // ---------------------------------------------------------------- 规则

  /**
   * 一条规则。
   *
   * @param ruleId 规则 id
   * @param ruleCode 规则编码（租户内稳定业务键）
   * @param ruleType 规则类型
   * @param ruleTypeLabel 规则类型的人话
   * @param status 规则生命周期
   * @param statusLabel 规则生命周期的人话
   * @param title 标题（取自当前生效版本）
   * @param description 说明（取自当前生效版本）
   * @param severity **当前生效版本**的严重度（无生效版本时为 null）
   * @param severityLabel 严重度的人话
   * @param versionCount 版本总数
   * @param termCount 当前生效版本的词条数
   * @param scopeCount 当前生效版本的适用范围条数
   * @param currentVersionId 当前生效版本 id（无则 null）
   * @param currentVersionNo 当前生效版本号（无则 null）
   * @param currentEffectiveFrom 当前生效版本的生效时刻（无则 null）
   * @param currentEffectiveTo 当前生效版本的结束时刻（无则 null）
   * @param inForce 当前是否有生效版本
   * @param createdAt 创建时刻
   */
  public record ComplianceRule(
      String ruleId,
      String ruleCode,
      String ruleType,
      String ruleTypeLabel,
      String status,
      String statusLabel,
      String title,
      String description,
      String severity,
      String severityLabel,
      int versionCount,
      int termCount,
      int scopeCount,
      String currentVersionId,
      Integer currentVersionNo,
      OffsetDateTime currentEffectiveFrom,
      OffsetDateTime currentEffectiveTo,
      boolean inForce,
      OffsetDateTime createdAt) {}

  /**
   * 规则清单（**真分页**）。
   *
   * @param items 规则行
   * @param total 总条数
   * @param page 当前页
   * @param size 每页条数
   * @param activeCount 在用规则数（覆盖整个筛选结果集）
   * @param retiredCount 已停用规则数（覆盖整个筛选结果集）
   * @param typeCounts 按规则类型的计数（覆盖整个筛选结果集）
   * @param severityCounts 按严重度的计数（覆盖整个筛选结果集）
   * @param note 口径说明
   */
  public record ComplianceRulePage(
      List<ComplianceRule> items,
      long total,
      int page,
      int size,
      long activeCount,
      long retiredCount,
      Map<String, Long> typeCounts,
      Map<String, Long> severityCounts,
      String note) {}

  // ---------------------------------------------------------------- 版本

  /**
   * 规则的一个版本。
   *
   * @param versionId 版本 id
   * @param ruleId 所属规则 id
   * @param ruleCode 所属规则编码
   * @param versionNo 版本号（同一规则内连续）
   * @param severity 严重度（**版本属性**）
   * @param severityLabel 严重度的人话
   * @param title 标题
   * @param description 说明
   * @param effectiveFrom 生效时刻
   * @param effectiveTo 结束时刻（null = 一直有效直到下一版接替）
   * @param inForce 该版本在「此刻」是否正在生效
   * @param changeNote 变更说明
   * @param termCount 词条数
   * @param scopeCount 适用范围条数
   * @param createdAt 创建时刻
   */
  public record ComplianceRuleVersion(
      String versionId,
      String ruleId,
      String ruleCode,
      int versionNo,
      String severity,
      String severityLabel,
      String title,
      String description,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveTo,
      boolean inForce,
      String changeNote,
      int termCount,
      int scopeCount,
      OffsetDateTime createdAt) {}

  /** 版本清单（**真分页**）。 */
  public record ComplianceRuleVersionPage(
      List<ComplianceRuleVersion> items, long total, int page, int size, String note) {}

  // ---------------------------------------------------------------- 词条

  /**
   * 一个词条。
   *
   * @param termId 词条 id
   * @param ruleVersionId 所属版本 id
   * @param term 词条原文
   * @param termKind 词条分类
   * @param termKindLabel 词条分类的人话
   * @param note 备注
   * @param createdAt 创建时刻
   */
  public record ComplianceRuleTerm(
      String termId,
      String ruleVersionId,
      String term,
      String termKind,
      String termKindLabel,
      String note,
      OffsetDateTime createdAt) {}

  /** 词条清单（**真分页**）。 */
  public record ComplianceRuleTermPage(
      List<ComplianceRuleTerm> items, long total, int page, int size, String note) {}

  // ---------------------------------------------------------------- 适用范围

  /**
   * 一条适用范围。
   *
   * @param scopeId 适用范围 id
   * @param ruleVersionId 所属版本 id
   * @param platformId 适用平台 id
   * @param platformCode 适用平台编码
   * @param platformName 适用平台名
   * @param marketplaceId 适用站点 id（null = 该平台全部站点）
   * @param marketplaceCode 适用站点编码
   * @param marketplaceName 适用站点名
   * @param shopId 适用店铺 id（null = 该平台/站点下全部店铺）
   * @param shopCode 适用店铺编码
   * @param shopName 适用店铺名
   * @param createdAt 创建时刻
   */
  public record ComplianceRuleScope(
      String scopeId,
      String ruleVersionId,
      String platformId,
      String platformCode,
      String platformName,
      String marketplaceId,
      String marketplaceCode,
      String marketplaceName,
      String shopId,
      String shopCode,
      String shopName,
      OffsetDateTime createdAt) {}

  /** 适用范围清单（**真分页**）。 */
  public record ComplianceRuleScopePage(
      List<ComplianceRuleScope> items, long total, int page, int size, String note) {}

  // ---------------------------------------------------------------- 详情

  /** 规则详情（规则 + 全部版本）。 */
  public record ComplianceRuleDetail(
      ComplianceRule rule, List<ComplianceRuleVersion> versions, String note) {}

  /** 版本详情（版本 + 词条 + 适用范围）。 */
  public record ComplianceRuleVersionDetail(
      ComplianceRule rule,
      ComplianceRuleVersion version,
      List<ComplianceRuleTerm> terms,
      List<ComplianceRuleScope> scopes,
      String note) {}

  /**
   * 「某一时刻生效的是哪一版」—— <b>可解释性</b>的落点。
   *
   * @param rule 规则
   * @param version 该时刻生效的版本
   * @param terms 该版本的词条（**原样**，不按今天的内容重算）
   * @param scopes 该版本的适用范围（**原样**）
   * @param resolvedAt 解析用的那个时刻
   * @param note 口径说明
   */
  public record ComplianceRuleEffectiveVersion(
      ComplianceRule rule,
      ComplianceRuleVersion version,
      List<ComplianceRuleTerm> terms,
      List<ComplianceRuleScope> scopes,
      OffsetDateTime resolvedAt,
      String note) {}

  /**
   * 生效规则集里的一条。
   *
   * @param rule 规则
   * @param version 该时刻生效的版本
   * @param termCount 该版本的词条数
   * @param platformCodes 适用平台编码
   * @param marketplaceCodes 适用站点编码
   * @param shopCodes 适用店铺编码
   * @param note 口径说明
   */
  public record ComplianceRuleEffectiveEntry(
      ComplianceRule rule,
      ComplianceRuleVersion version,
      int termCount,
      List<String> platformCodes,
      List<String> marketplaceCodes,
      List<String> shopCodes,
      String note) {}

  /** 生效规则集（**真分页**）。 */
  public record ComplianceRuleEffectiveSet(
      List<ComplianceRuleEffectiveEntry> items,
      long total,
      int page,
      int size,
      OffsetDateTime resolvedAt,
      String note) {}

  // ---------------------------------------------------------------- 入参

  /**
   * 建规则的入参（**只建身份**）。
   *
   * @param ruleCode 规则编码（租户内稳定业务键）
   * @param ruleType 规则类型（七种之一）
   * @param title 标题
   * @param description 说明
   */
  public record ComplianceRuleCreateInput(
      String ruleCode, String ruleType, String title, String description) {}

  /**
   * 改规则身份的入参（**改不到版本内容上**）。
   *
   * <p>🔴 只有<b>生命周期</b>可改（停用 / 重新启用）。标题与说明是<b>版本</b>的属性 ——
   * 改它们 = 新增一个版本（标题、说明、严重度都会原样留在旧版本里）。
   *
   * @param status 生命周期（ACTIVE / RETIRED）
   */
  public record ComplianceRuleUpdateInput(String status) {}

  /**
   * 建规则的结果（**幂等**）。
   *
   * @param rule 规则
   * @param created 本次是否真的新建（false ⇒ 命中规则编码，已有记录被原样返回）
   * @param note 口径说明
   */
  public record ComplianceRuleCreation(ComplianceRule rule, boolean created, String note) {}

  /**
   * 新增一个版本的入参 —— <b>规则改动的唯一入口</b>。
   *
   * @param severity 严重度（阻断 / 警告 / 提示）
   * @param title 标题
   * @param description 说明
   * @param effectiveFrom 生效时刻（**不得早于当前时刻**）
   * @param changeNote 变更说明
   * @param terms 词条（可空）
   * @param termsText 词库原文（每行一个词；与 terms 合并去重）
   * @param scopes 适用范围（可空）
   */
  public record ComplianceRuleVersionInput(
      String severity,
      String title,
      String description,
      OffsetDateTime effectiveFrom,
      String changeNote,
      List<ComplianceRuleTermInput> terms,
      String termsText,
      List<ComplianceRuleScopeInput> scopes) {}

  /** 一个词条。 */
  public record ComplianceRuleTermInput(String term, String termKind, String note) {}

  /** 一条适用范围。 */
  public record ComplianceRuleScopeInput(
      String platformId, String marketplaceId, String shopId) {}

  /** 批量追加词条的入参。 */
  public record ComplianceRuleTermsInput(List<ComplianceRuleTermInput> terms) {}

  /**
   * 词库上传的入参 —— <b>整份词库一次落库</b>。
   *
   * @param content 词库原文，每行一个词（空行与 # 开头的注释行忽略）
   */
  public record ComplianceRuleTermsUploadInput(String content) {}

  /** 批量追加适用范围的入参。 */
  public record ComplianceRuleScopesInput(List<ComplianceRuleScopeInput> scopes) {}

  // ---------------------------------------------------------------- 结果

  /**
   * 词条批量落库的结果。
   *
   * @param ruleVersionId 版本 id
   * @param requested 请求条数
   * @param inserted 真正写入的条数
   * @param skipped 跳过的条数（同一版本里已存在）
   * @param skippedTerms 跳过的词（逐条列出）
   * @param note 口径说明
   */
  public record ComplianceRuleTermBatchResult(
      String ruleVersionId,
      int requested,
      int inserted,
      int skipped,
      List<String> skippedTerms,
      String note) {}

  /**
   * 适用范围批量落库的结果。
   *
   * @param ruleVersionId 版本 id
   * @param requested 请求条数
   * @param inserted 真正写入的条数
   * @param skipped 跳过的条数
   * @param skippedScopes 跳过的范围（逐条列出）
   * @param note 口径说明
   */
  public record ComplianceRuleScopeBatchResult(
      String ruleVersionId,
      int requested,
      int inserted,
      int skipped,
      List<String> skippedScopes,
      String note) {}
}
