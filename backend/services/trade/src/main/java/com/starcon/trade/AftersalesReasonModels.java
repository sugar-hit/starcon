package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 11.1.2.1 售后原因分类的传输模型。
 *
 * <p>蓝图 11-aftersales-and-returns.md 第 99-104 行原文：
 *
 * <blockquote>
 * 职责：维护售后原因分类树（质量问题、描述不符、尺寸不符、物流损坏、买家原因、误购、欺诈退货），支持多选与平台原因码映射，驱动后续的质量归因分析。
 * 输入：原因分类维护；平台原因码映射（2.1.2.1）。
 * 输出：原因分类记录；报表用统一口径。
 * 关系：上游 = 2 平台映射；下游 = 11.5.2 质量分析、6 采购（供应商索赔）。
 * <b>原因口径不统一会让退货率分析失真</b> —— 必须强制选择分类而不是留自由文本。
 * </blockquote>
 *
 * <p><b>本文件最重要的三件事</b>：
 *
 * <ol>
 *   <li><b>「强制选择分类」是模型层的形状，不是文案。</b>
 *       {@link AftersalesReasonAttributionCreate#items()} 是<b>非空列表</b>，
 *       而 {@link AftersalesReasonAttributionCreate#note()} 是自由文本 ——
 *       两者在类型上就无法构成「只写了备注」的请求。
 *       DB 层再由**提交期延迟约束触发器**钉死「至少一个成员 + 恰好一个主因」
 *       （见 V158 第一节），所以绕过后端直写数据库也存不进去。
 *   <li><b>分类树是可维护结构</b>：{@link AftersalesReasonTreeNode} 带 {@code parentId} / {@code depth} /
 *       {@code sortOrder} / {@code status}，不是一张七行的硬编码枚举表。
 *       七类词表由服务层<b>按租户幂等落库</b>（{@code ensureBaseline}），落库后即可改名/排序/停用/扩展。
 *   <li><b>报表口径由服务端定义</b>：{@link AftersalesReasonReport} 明确给出
 *       「只统计当前生效归因」「占比 = 归因单数 ÷ 生效归因单总数」两条语义，
 *       下游 11.5.2 直接取用，不必自己再定一套。
 * </ol>
 *
 * <p><b>命名。</b>统一用 {@code AftersalesReason*} 前缀，全仓 grep 确认与既有类型零重叠 ——
 * 契约被 codegen 合并进一个扁平文件、同名后者覆盖前者且不报冲突。
 */
public final class AftersalesReasonModels {

  private AftersalesReasonModels() {}

  // ================================================================ 枚举

  /**
   * 蓝图点名的七类根口径，逐字对齐 11-aftersales-and-returns.md。
   *
   * <p>⚠️ 它是**根节点的归类标记**而不是「归因可选值」：归因可选值是租户可维护的树节点
   * （{@link AftersalesReasonTreeNode}），七类只是它们的**汇总口径**。
   * 这样「新增一个租户自定义分类」不需要改这里，而「报表按七类汇总」有稳定锚点。
   *
   * <p>与 V158 的 {@code aftersales_reason_category.root_kind} CHECK 逐字一致 ——
   * 枚举少一个值，基线词表就落不下去。
   */
  public enum AftersalesReasonRootKind {
    QUALITY_DEFECT("质量问题"),
    DESCRIPTION_MISMATCH("描述不符"),
    SIZE_MISMATCH("尺寸不符"),
    LOGISTICS_DAMAGE("物流损坏"),
    BUYER_REASON("买家原因"),
    MISTAKE_BUY("误购"),
    FRAUD_RETURN("欺诈退货");

    private final String label;

    AftersalesReasonRootKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 分类的启用状态。停用是**口径变更**：不再可被新归因选中，历史归因仍指向它。 */
  public enum AftersalesReasonCategoryStatus {
    ACTIVE("启用"),
    DISABLED("停用");

    private final String label;

    AftersalesReasonCategoryStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 平台原因码映射的来源。
   *
   * <p>与 V150 {@code owner_source} / V156 {@code owner_source} 同一套纪律：
   * 区分「租户自己维护的」与「同步器写入的」，后者必须带 {@code sourceReference}，
   * 否则「这行是同步器写的」只是口头声明。
   */
  public enum AftersalesReasonMappingSource {
    TENANT_MAINTAINED("租户自维护"),
    MODULE_2_ENUM_MAPPING("2.1.2.1 平台枚举映射同步器写入");

    private final String label;

    AftersalesReasonMappingSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 映射的启用状态。停用保留历史（报表口径不因停用而改写），只是不再用于新同步。 */
  public enum AftersalesReasonMappingStatus {
    ACTIVE("启用"),
    DISABLED("停用");

    private final String label;

    AftersalesReasonMappingStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 平台来源：与 {@code sales_order.order_source}（V99，同库只读）的 CHECK 逐字对齐。
   *
   * <p>⚠️ 刻意**不是**店铺维度：卷 2 的店铺在 channel 服务独立库，
   * 按 AGENTS.md 模块边界纪律禁止跨服务 SQL ⇒ 本服务读不到店。
   * 平台级是当前唯一可得的粒度，{@link AftersalesReasonDimension} 里如实报出来。
   */
  public enum AftersalesPlatformSource {
    AMAZON("Amazon"),
    SHOPIFY("Shopify"),
    EBAY("eBay"),
    TIKTOK_SHOP("TikTok Shop"),
    ALIBABA_1688("1688");

    private final String label;

    AftersalesPlatformSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：哪些维度真有依据、上游通没通、下游交付没交付、平台原因码映射归属谁。
   *
   * <p>存在的理由与 V156 相同：让界面能说清「为什么平台原因码是租户自己维护的」，
   * 而不是让一个空下拉框显示成空白让人以为「这个租户没有平台映射」。
   */
  @Schema(description = "11.1.2.1 原因分类的能力声明与上下游可用性")
  public record AftersalesReasonCapabilities(
      @Schema(description = "真实存在的分类字段与口径，逐条给出列名 / 函数名") List<String> supportedDimensions,
      @Schema(description = "当前不可得或刻意不提供的维度") List<AftersalesReasonDimension> unavailableDimensions,
      @Schema(description = "七类根口径词表（与 DB 的 root_kind CHECK 逐字一致）")
          List<AftersalesReasonEnumOption> rootKinds,
      @Schema(description = "平台来源词表") List<AftersalesReasonEnumOption> platforms,
      @Schema(description = "蓝图点名的七类是否已作为基线词表落库到当前租户")
          boolean baselineVocabularySeeded,
      @Schema(description = "分类树最大层数（V158 的 depth CHECK）") int maxDepth,
      @Schema(description = "单次归因可选的原因个数上限（服务层校验）") int maxAttributionItems,
      @Schema(description = "是否可跨服务读取上游 2.1.2.1 的枚举映射表")
          boolean crossServiceReadAvailable,
      @Schema(description = "平台原因码映射的归属模块号（跨服务读不可得时的实际归属）")
          String platformMappingOwnerModule,
      @Schema(description = "质量分析是否已交付（11.5.2）") boolean qualityAnalysisDelivered,
      @Schema(description = "供应商索赔是否已交付（6.4.2.2）") boolean supplierClaimDelivered,
      @Schema(description = "平台原因码映射的说明") String platformMappingNote,
      @Schema(description = "「强制选择分类」的实现方式") String forcedClassificationNote,
      @Schema(description = "报表口径说明（下游 11.5.2 直接取用）") String reportSemantics) {}

  /** 一个不可得 / 刻意不提供的维度，以及它归谁。 */
  @Schema(description = "不可得维度 + 归属模块 + 原因")
  public record AftersalesReasonDimension(
      @Schema(description = "维度标识") String name,
      @Schema(description = "维度中文名") String label,
      @Schema(description = "是否可得") boolean available,
      @Schema(description = "归属模块号") String ownerModule,
      @Schema(description = "为什么不可得 / 为什么刻意不提供") String detail) {}

  /** 通用枚举下拉项。 */
  @Schema(description = "枚举下拉项")
  public record AftersalesReasonEnumOption(
      @Schema(description = "枚举值") String value,
      @Schema(description = "中文名") String label) {}

  // ================================================================ 分类树

  /**
   * 分类树的一个节点。
   *
   * <p>{@code depth} 是**触发器维护的真列**（V158 的 {@code guard_aftersales_reason_category_tree}），
   * {@code childCount} / {@code attributionCount} 是查询时聚合出来的，不是冗余存储 ——
   * 存冗余计数就必然与事实漂移，而「这个分类还能不能选」正是一个要立刻准确的事实。
   *
   * <p>{@code assignable} 表示「当前可被新归因选中」：必须是 ACTIVE，
   * 且它的**全部祖先都 ACTIVE**（祖先停用时子节点在界面上应整支灰掉）。
   */
  @Schema(description = "原因分类树节点")
  public record AftersalesReasonTreeNode(
      @Schema(description = "分类 ID") UUID id,
      @Schema(nullable = true, description = "父分类 ID；根为 null") UUID parentId,
      @Schema(description = "编码（租户内唯一，报表的稳定锚点，改名不改编码）")
          String categoryCode,
      @Schema(description = "名称") String categoryName,
      @Schema(nullable = true, description = "根口径；非根节点为 null") String rootKind,
      @Schema(nullable = true, description = "根口径中文名") String rootKindLabel,
      @Schema(description = "状态 ACTIVE / DISABLED") String categoryStatus,
      @Schema(description = "状态中文名") String categoryStatusLabel,
      @Schema(description = "排序位（同一层内，报表导出按它定序）") int sortOrder,
      @Schema(description = "层级，1 是根") int depth,
      @Schema(description = "直接子分类数") long childCount,
      @Schema(description = "引用了这个分类的**全部版本**归因成员数（含历史版本）")
          long attributionCount,
      @Schema(description = "当前是否可被新归因选中：ACTIVE 且全部祖先 ACTIVE") boolean assignable,
      @Schema(nullable = true, description = "不可选的原因；可选时为 null") String notAssignableReason,
      @Schema(description = "从根到本节点的编码路径，如 ROOT.QUALITY_DEFECT/APPEARANCE_DEFECT")
          String codePath,
      @Schema(description = "从根到本节点的名称路径") String namePath,
      @Schema(description = "说明") String description) {}

  /**
   * 分类树整树。
   *
   * <p>⚠️ {@code orderedBy} 把**确定性排序键**原样报给前端：
   * 蓝图要求「报表用统一口径」，而排序键不确定意味着两次导出同一批数据会逐行不同。
   * 本模块的排序键是 {@code (sort_order, category_code, id)} ——
   * 刻意不只用 {@code sort_order}，因为它允许重复，单靠它排不出确定顺序。
   */
  @Schema(description = "原因分类树整树")
  public record AftersalesReasonTree(
      @Schema(description = "节点列表（确定性顺序，前端可按 codePath 建树）")
          List<AftersalesReasonTreeNode> nodes,
      @Schema(description = "节点总数") long totalCount,
      @Schema(description = "启用中的节点数") long activeCount,
      @Schema(description = "停用的节点数") long disabledCount,
      @Schema(description = "最大层数") int maxDepth,
      @Schema(description = "七类根口径是否已作为基线词表落库到当前租户")
          boolean baselineVocabularySeeded,
      @Schema(description = "本次读取是否刚刚落库了基线词表（true 表示这是该租户第一次打开本模块）")
          boolean baselineSeededOnThisRead,
      @Schema(description = "排序键（原样报出，报表导出的确定性由它保证）") String orderedBy,
      @Schema(description = "树结构约束说明") String treeRules,
      @Schema(description = "返回的是全树还是某个子树") String scope) {}

  /** 新建一个分类。 */
  @Schema(description = "新建原因分类")
  public record AftersalesReasonCategoryCreate(
      @Schema(nullable = true, description = "父分类 ID；null 表示建根") UUID parentId,
      @Schema(description = "编码，租户内唯一（大写字母 / 数字 / 点 / 下划线 / 连字符）")
          String categoryCode,
      @Schema(description = "名称") String categoryName,
      @Schema(
              nullable = true,
              description = "根口径；建根时**必填**且只能是七类之一，建子节点时必须为 null")
          String rootKind,
      @Schema(nullable = true, description = "排序位，缺省 0") Integer sortOrder,
      @Schema(nullable = true, description = "说明，缺省空") String description) {}

  /**
   * 更新一个分类。
   *
   * <p>⚠️ {@code rootKind} **不在**可更新列里（V158 的列级白名单也没授它）——
   * 根口径是报表的汇总锚点，改它会让全部历史归因被重新归到另一个口径下。
   * {@code categoryCode} 同理不可改：11.5.2 与 6 采购的历史数据按编码对齐。
   * 要「换口径」请新建一个分类再迁归因。
   */
  @Schema(description = "更新原因分类（只改这些列；rootKind / categoryCode 刻意不可改）")
  public record AftersalesReasonCategoryUpdate(
      @Schema(nullable = true, description = "新名称；不传表示不改") String categoryName,
      @Schema(nullable = true, description = "新状态 ACTIVE / DISABLED；不传表示不改")
          String categoryStatus,
      @Schema(nullable = true, description = "新排序位；不传表示不改") Integer sortOrder,
      @Schema(nullable = true, description = "新父分类 ID；不传表示不改，null 不表示移到根（用 moveToRoot）")
          UUID parentId,
      @Schema(description = "新说明；空串表示清空，null 表示不改") String description,
      @Schema(description = "把节点移到根（必须同时不传 parentId）；默认 false")
          boolean moveToRoot) {}

  // ================================================================ 归因

  /**
   * 归因里的一个原因。
   *
   * <p>⚠️ {@code weight} 是**必填且显式**的，不是默认值 1：
   * 归因分析最没意义的形态就是「什么都勾了、权重全是 1」，那等于没归因。
   * 区间 (0, 1]，由 V158 的 CHECK 强制。
   */
  @Schema(description = "归因里的一个原因分类")
  public record AftersalesReasonAttributionItem(
      @Schema(description = "原因分类 ID") UUID categoryId,
      @Schema(description = "是否为主因；一次归因**恰好一个**为 true") boolean isPrimary,
      @Schema(description = "权重，(0, 1]，小数点后六位。必填，不接受默认值 1")
          BigDecimal weight) {}

  /**
   * 登记一次归因（**替换**语义：新写一版，旧的自动标记为失效）。
   *
   * <p><b>本记录就是加粗句「必须强制选择分类而不是留自由文本」的接口形状</b>：
   *
   * <ul>
   *   <li>{@link #items()} <b>必须非空</b>（服务层与 DB 双层强制），且恰好一个主因；
   *   <li>{@link #note()} 是自由文本，但它是**集合上的补充说明**，
   *       而不是成员的替代品 —— 零成员的集合在 DB 层根本提交不了。
   * </ul>
   *
   * <p><b>刻意不接受 {@code primaryCategoryId} 这种扁平形状</b>：
   * 「主因 + 若干次因」用扁平的几个字段表达，会让人以为「主因是唯一字段、次因是可选数组」，
   * 而真实约束是**成员的属性**（恰好一个 is_primary=true）。扁平形状在多选时无解。
   */
  @Schema(description = "登记一次归因（替换语义；至少一个原因、恰好一个主因）")
  public record AftersalesReasonAttributionCreate(
      @Schema(description = "售后单 ID") UUID caseId,
      @Schema(description = "补充说明（自由文本，≤1000 字符）。**不能替代**分类选择")
          String note,
      @Schema(description = "原因列表，至少一个、最多 10 个，恰好一个主因")
          List<AftersalesReasonAttributionItem> items) {}

  /** 归因里的一个原因的读回形态（带分类上下文，前端不必再查一次树）。 */
  @Schema(description = "归因成员（读回）")
  public record AftersalesReasonAttributionItemRow(
      @Schema(description = "成员 ID") UUID id,
      @Schema(description = "原因分类 ID") UUID categoryId,
      @Schema(description = "原因分类编码") String categoryCode,
      @Schema(description = "原因分类名称") String categoryName,
      @Schema(nullable = true, description = "所属根口径") String rootKind,
      @Schema(nullable = true, description = "所属根口径中文名") String rootKindLabel,
      @Schema(description = "层级") int depth,
      @Schema(description = "是否主因") boolean isPrimary,
      @Schema(description = "权重") BigDecimal weight,
      @Schema(description = "编码路径") String codePath) {}

  /**
   * 一张售后单的当前归因。
   *
   * <p>⚠️ {@code unattributed} 为 true 时 {@code current} 为 null ——
   * 「这张单还没有归因」是一个**真实且合法**的状态（11.1.1.1 登记单时还没有原因），
   * 必须与「归因为空」区分开：前者是没做过归因，后者是不可能存在的数据。
   */
  @Schema(description = "一张售后单的当前归因")
  public record AftersalesReasonAttributionView(
      @Schema(description = "售后单 ID") UUID caseId,
      @Schema(nullable = true, description = "售后单号（aftersales_case.case_no，只读）") String caseNo,
      @Schema(description = "是否已归因；false 表示从未归因（不是「归因为空」）") boolean attributed,
      @Schema(nullable = true, description = "当前生效的归因集合；未归因时为 null")
          AftersalesReasonAttributionSetRow current,
      @Schema(description = "历史版本数（按归因时刻倒序，含当前版）") long versionCount,
      @Schema(description = "自由文本的语义说明（前端必须照此渲染，不能把它显示成「原因」）")
          String noteSemantics,
      @Schema(description = "「强制选择分类」的说明") String enforcementNote) {}

  /** 归因集合的读回形态。 */
  @Schema(description = "归因集合（一个版本）")
  public record AftersalesReasonAttributionSetRow(
      @Schema(description = "归因集合 ID") UUID setId,
      @Schema(description = "补充说明（自由文本，非空也**不构成**一次归因）") String note,
      @Schema(description = "归因成员") List<AftersalesReasonAttributionItemRow> items,
      @Schema(description = "成员数（恒 >= 1，恒 == 1 个主因，由 DB 提交期约束保证）")
          int itemCount,
      @Schema(description = "主因数（恒为 1）") int primaryCount,
      @Schema(description = "权重合计（供界面提示是否需要重新分配）") BigDecimal weightSum,
      @Schema(nullable = true, description = "归因人") UUID attributedBy,
      @Schema(description = "归因时刻") OffsetDateTime attributedAt,
      @Schema(description = "是否当前生效") boolean current,
      @Schema(nullable = true, description = "被哪一版取代的时刻；当前版为 null")
          OffsetDateTime supersededAt) {}

  // ================================================================ 平台原因码映射

  /**
   * 新建一条平台原因码映射。
   *
   * <p>⚠️ 这张表在 **trade 库**，不是 channel 的 {@code platform_enum_mapping}
   * （实查：那张表在 channel 服务独立库 starcon_channel，禁止跨服务 SQL / FK / 视图）。
   * 本表形状与 channel 的枚举映射**语义一致**（内部口径 ⇄ 平台码 + 来源 + 采集时刻），
   * 同步器将来按同一形状写入时**表结构与查询都不用改**。
   */
  @Schema(description = "新建平台原因码映射（trade 自有表，非跨服务读）")
  public record AftersalesReasonMappingCreate(
      @Schema(description = "内部原因分类 ID") UUID categoryId,
      @Schema(description = "平台来源") String platformSource,
      @Schema(description = "平台原因码") String platformReasonCode,
      @Schema(nullable = true, description = "平台原因名（平台返回的原文，缺省空）")
          String platformReasonName,
      @Schema(nullable = true, description = "来源，缺省 TENANT_MAINTAINED") String mappingSource,
      @Schema(
              nullable = true,
              description = "来源引用；mappingSource=MODULE_2_ENUM_MAPPING 时**必填**（哪份来源的哪一段）")
          String sourceReference,
      @Schema(
              nullable = true,
              description = "采集时刻（**不是**登记时刻），缺省取当前时刻")
          OffsetDateTime capturedAt,
      @Schema(nullable = true, description = "状态，缺省 ACTIVE") String mappingStatus) {}

  /** 平台原因码映射的读回形态。 */
  @Schema(description = "平台原因码映射（读回）")
  public record AftersalesReasonMappingRow(
      @Schema(description = "映射 ID") UUID id,
      @Schema(description = "内部原因分类 ID") UUID categoryId,
      @Schema(description = "内部原因分类编码") String categoryCode,
      @Schema(description = "内部原因分类名称") String categoryName,
      @Schema(nullable = true, description = "所属根口径") String rootKind,
      @Schema(nullable = true, description = "所属根口径中文名") String rootKindLabel,
      @Schema(description = "平台来源") String platformSource,
      @Schema(description = "平台原因码") String platformReasonCode,
      @Schema(description = "平台原因名") String platformReasonName,
      @Schema(description = "来源") String mappingSource,
      @Schema(description = "来源引用") String sourceReference,
      @Schema(description = "采集时刻（不是登记时刻）") OffsetDateTime capturedAt,
      @Schema(description = "状态") String mappingStatus,
      @Schema(description = "创建时刻") OffsetDateTime createdAt) {}

  /** 映射列表（真筛选 + 真排序，不是全量回前端）。 */
  @Schema(description = "平台原因码映射列表")
  public record AftersalesReasonMappingList(
      @Schema(description = "映射行") List<AftersalesReasonMappingRow> rows,
      @Schema(description = "命中总数") long total,
      @Schema(description = "生效映射数") long activeCount,
      @Schema(description = "本表归属服务的说明") String ownershipNote,
      @Schema(description = "排序键（原样报出）") String orderedBy) {}

  // ================================================================ 报表

  /**
   * 报表的一行（一个原因分类）。
   *
   * <p>⚠️ {@code primaryCount} 与 {@code attributionCount} <b>刻意分开</b>：
   * 前者是「作为主因被选中」，后者是「被选中过（含作为次因）」。
   * 合成一个数会让「质量问题里既是主因又是次因的单」被重复计入两个口径 ——
   * 这正是加粗句担心的「退货率分析失真」。
   */
  @Schema(description = "报表行（一个原因分类）")
  public record AftersalesReasonReportRow(
      @Schema(description = "分类 ID") UUID categoryId,
      @Schema(description = "分类编码") String categoryCode,
      @Schema(description = "分类名称") String categoryName,
      @Schema(nullable = true, description = "所属根口径") String rootKind,
      @Schema(nullable = true, description = "所属根口径中文名") String rootKindLabel,
      @Schema(description = "层级；> 1 表示它同时被汇总进所属根口径") int depth,
      @Schema(description = "排序位") int sortOrder,
      @Schema(description = "作为主因的归因单数") long primaryCaseCount,
      @Schema(description = "作为次因的归因单数") long secondaryCaseCount,
      @Schema(description = "被选中的归因单数（主因 + 次因）") long attributionCaseCount,
      @Schema(description = "占比（归因单数 ÷ 生效归因单总数，两位小数四舍五入）")
          BigDecimal caseSharePercent,
      @Schema(description = "权重合计（主因权重 + 次因权重）") BigDecimal weightSum) {}

  /**
   * 按七类根口径汇总的一行。
   *
   * <p>⚠️ <b>子分类会汇总进它的根口径</b>：一棵「质量问题 → 外观缺陷」树上，
   * 直接勾「外观缺陷」的单同样计入「质量问题」。这是「报表用统一口径」的关键 ——
   * 七类是下游（11.5.2 质量分析、6 采购索赔）唯一对齐的锚点，
   * 租户自定义的子节点不该让下游找不到落点。
   *
   * <p><b>零也出现</b>：七类全部返回，哪怕全是 0 —— 少列一个会让界面把它显示成空白，
   * 而空白会被读成「这个类没有数据」而不是「这个类真的是 0」。
   */
  @Schema(description = "按七类根口径汇总的一行")
  public record AftersalesReasonRootTotal(
      @Schema(description = "根口径") String rootKind,
      @Schema(description = "根口径中文名") String rootKindLabel,
      @Schema(description = "该口径下作为主因的归因单数") long primaryCaseCount,
      @Schema(description = "该口径下被选中的归因单数（含次因，已按单去重）")
          long attributionCaseCount,
      @Schema(description = "占比（该口径归因单数 ÷ 生效归因单总数，两位小数四舍五入）")
          BigDecimal caseSharePercent) {}

  /**
   * 报表（统一口径）。
   *
   * <p>蓝图输出句是「原因分类记录；<b>报表用统一口径</b>」，下游是 11.5.2 质量分析
   * 与 6 采购（供应商索赔）—— 后两者**均未交付**，本模块**不假装已对接**，
   * 只把口径定义清楚并可通过 HTTP 取用。
   *
   * <p>⚠️ 只统计**当前生效**的归因集合（{@code superseded_at IS NULL}）：
   * 把历史版本一起算进去会让改过一次归因的单被计两次，报表数字随「改了几次」变化 ——
   * 那不叫统一口径。
   */
  @Schema(description = "原因分类报表（统一口径）")
  public record AftersalesReasonReport(
      @Schema(description = "按分类的明细行（确定性顺序）") List<AftersalesReasonReportRow> rows,
      @Schema(description = "按七类根口径的汇总行（七类全部出现，零也出现）")
          List<AftersalesReasonRootTotal> rootTotals,
      @Schema(description = "统计基准时刻（整份报表共用一个）") OffsetDateTime evaluatedAt,
      @Schema(description = "已归因的售后单数（当前生效集合的去重单数）") long attributedCaseCount,
      @Schema(description = "有售后单但从未归因的单数") long unattributedCaseCount,
      @Schema(description = "参与统计的原因成员总数") long attributionItemCount,
      @Schema(description = "权重合计") BigDecimal totalWeight,
      @Schema(description = "统计口径说明（下游直接取用，不必自己再定一套）") String semantics,
      @Schema(description = "下游模块的交付状态") String downstreamNote) {}

  // ================================================================ 树读取的筛选条件

  /**
   * 分类树的筛选条件。
   *
   * <p>⚠️ 刻意只有三个参数，且每一个都对应一个**真实存在**的列或索引：
   * {@code categoryStatus}（{@code category_status}）、{@code rootKind}（{@code root_kind}，
   * 部分索引 {@code aftersales_reason_category_by_root_kind}）与 {@code assignableOnly}
   * （ACTIVE 且全部祖先 ACTIVE）。不提供「按名称搜索」这种**没有对应索引**的参数 ——
   * 一个永远查不出东西的搜索框比没有更糟；真要做搜索时该连同 GIN 索引一起加。
   */
  @Schema(description = "分类树筛选条件")
  public record AftersalesReasonTreeQuery(
      @Schema(nullable = true, description = "状态 ACTIVE / DISABLED") String categoryStatus,
      @Schema(nullable = true, description = "根口径（只看某一类下的整支）") String rootKind,
      @Schema(nullable = true, description = "只要当前可被新归因选中的节点") Boolean assignableOnly) {}
}
