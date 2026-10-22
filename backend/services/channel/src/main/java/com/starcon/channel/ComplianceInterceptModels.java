package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 16.2.1.2 拦截执行的传输模型。
 *
 * <p>蓝图：「在关键业务节点执行合规校验并阻断：刊登发布前、发货前、结算提现前；
 * 展示拦截原因与解除方式（提交资质、申请豁免）。输入：合规规则集；业务动作触发的校验请求（商品/订单/店铺）。
 * 输出：校验结论（通过/警告/阻断）+ 原因 + 解除入口；豁免申请与审批。
 * 关系：上游 = 16.2.1.1；下游 = 4.3.1.1 发布、8.3.4.1 发货、12 提现。
 * <b>豁免必须有审批与理由</b>，否则阻断会被随意绕过。」
 *
 * <p>🔴🔴 本模块对外最要紧的四条事实，全部<b>在 API 与页面上如实表达</b>：
 *
 * <ol>
 *   <li><b>结论是三态 + 一个「判不了」</b>：{@code PASS / WARN / BLOCK / UNDECIDABLE}。
 *       「判不了」<b>不等于</b>「通过」—— 判不出来时 {@code releaseGranted=false}。</li>
 *   <li><b>阻断在服务端强制</b>：判定端点返回 {@code blocked=true} + 逐条原因，
 *       刊登派发路径必须读服务端结论才放行；前端只是显示。</li>
 *   <li><b>豁免要走审批</b>：申请人 ≠ 审批人、理由必填、有效期必填；
 *       因豁免放行时结论里<b>明确标出并指向豁免单 id</b>。</li>
 *   <li><b>未交付的下游不编</b>：8.3.4.1 发货（跨服务）与 12 提现（服务未落地）
 *       在能力声明里 {@code available=false} + {@code ownerModule} + 影响说明。</li>
 * </ol>
 */
public final class ComplianceInterceptModels {

  private ComplianceInterceptModels() {}

  // ---------------------------------------------------------------- 能力声明

  /**
   * 一个下游（或维度）的交付状态。
   *
   * @param moduleId 模块号（如 4.3.1.1 / 8.3.4.1 / 12）
   * @param moduleName 模块名
   * @param ownerModule 归谁（available=false 时必填）
   * @param available 当前是否真的可用
   * @param impact 未交付 / 已交付的影响说明（界面原样显示）
   */
  public record ComplianceInterceptCapabilityGap(
      String moduleId,
      String moduleName,
      String ownerModule,
      boolean available,
      String impact) {}

  /**
   * 能力声明。
   *
   * @param gaps 下游交付状态清单（**可查的事实**，不是文档里的一句话）
   * @param scenes 拦截点值域
   * @param sceneLabels 拦截点人话
   * @param subjectTypes 校验对象值域
   * @param subjectTypeLabels 校验对象人话
   * @param conclusions 结论值域
   * @param conclusionLabels 结论人话
   * @param reasonCodes 原因码值域
   * @param reasonCodeLabels 原因码人话
   * @param exemptionStatuses 豁免状态值域
   * @param exemptionStatusLabels 豁免状态人话
   * @param conclusionNote 结论口径说明（**三态 + 一个「判不了」**）
   * @param enforcementNote 阻断如何被强制执行的说明
   * @param exemptionNote 豁免口径说明（**加粗句**）
   * @param upstreamNote 上游说明
   * @param downstreamNote 下游说明
   */
  public record ComplianceInterceptCapabilities(
      List<ComplianceInterceptCapabilityGap> gaps,
      List<String> scenes,
      Map<String, String> sceneLabels,
      List<String> subjectTypes,
      Map<String, String> subjectTypeLabels,
      List<String> conclusions,
      Map<String, String> conclusionLabels,
      List<String> reasonCodes,
      Map<String, String> reasonCodeLabels,
      List<String> exemptionStatuses,
      Map<String, String> exemptionStatusLabels,
      String conclusionNote,
      String enforcementNote,
      String exemptionNote,
      String upstreamNote,
      String downstreamNote) {}

  // ---------------------------------------------------------------- 策略

  /**
   * 一个拦截点的策略。
   *
   * @param scene 拦截点
   * @param sceneLabel 拦截点人话
   * @param enabled 该拦截点是否真的启用拦截（**默认 false**：没显式开启就没有拦截）
   * @param undecidableAction 判不了时怎么办：BLOCK 失败关闭 / ALLOW 放行并留痕
   * @param note 备注
   * @param updatedAt 策略最后更新时刻
   */
  public record ComplianceInterceptPolicyRow(
      String scene,
      String sceneLabel,
      boolean enabled,
      String undecidableAction,
      String note,
      OffsetDateTime updatedAt) {}

  /** 策略清单（三个拦截点各一条；不存在的按默认值返回）。 */
  public record ComplianceInterceptPolicySet(
      List<ComplianceInterceptPolicyRow> items, String note) {}

  // ---------------------------------------------------------------- 判定

  /**
   * 一条原因。
   *
   * @param reasonId 原因 id
   * @param reasonCode 原因码（**具名**）
   * @param reasonCodeLabel 原因码人话
   * @param reasonText 人话解释
   * @param ruleId 关联规则 id（引擎级原因为 null）
   * @param ruleVersionId **当时生效的那个版本**（冻结在行里）
   * @param ruleCode 规则编码
   * @param ruleType 规则类型
   * @param severity 严重度（取自那个生效版本）
   * @param fieldKey 命中的字段
   * @param matchedTerm 命中的词条
   * @param waived 是否因豁免放行
   * @param exemptionId 因豁免放行时指向的豁免单 id
   * @param undecidable 这条原因是不是「判不了」类
   */
  public record ComplianceInterceptReasonRow(
      String reasonId,
      String reasonCode,
      String reasonCodeLabel,
      String reasonText,
      String ruleId,
      String ruleVersionId,
      String ruleCode,
      String ruleType,
      String severity,
      String fieldKey,
      String matchedTerm,
      boolean waived,
      String exemptionId,
      boolean undecidable) {}

  /**
   * 因豁免放行的指向（审计上「阻断去哪了」必须答得出来）。
   *
   * @param exemptionId 豁免单 id
   * @param ruleId 被豁免的规则 id
   * @param ruleCode 被豁免的规则编码
   * @param validFrom 豁免有效期起
   * @param validTo 豁免有效期止
   * @param note 人话说明
   */
  public record ComplianceInterceptExemptionRef(
      String exemptionId,
      String ruleId,
      String ruleCode,
      OffsetDateTime validFrom,
      OffsetDateTime validTo,
      String note) {}

  /**
   * 解除方式（蓝图的「展示拦截原因与<b>解除方式</b>」）。
   *
   * @param reasonCode 对应哪条原因
   * @param remedy 人话说明
   * @param canApplyExemption 是否可以申请豁免（引擎级原因「判不了」不能靠豁免绕过）
   */
  public record ComplianceInterceptRemedy(
      String reasonCode, String remedy, boolean canApplyExemption) {}

  /**
   * 一次判定的结论。
   *
   * @param decisionId 判定 id
   * @param scene 拦截点
   * @param sceneLabel 拦截点人话
   * @param subjectType 校验对象
   * @param subjectTypeLabel 校验对象人话
   * @param subjectId 主体逻辑 id
   * @param shopId 判定所依据的店铺
   * @param platformId 判定所依据的平台
   * @param marketplaceId 判定所依据的站点
   * @param conclusion 结论（PASS / WARN / BLOCK / UNDECIDABLE）
   * @param conclusionLabel 结论人话
   * @param blocked 是不是被阻断（只有 BLOCK 为 true）
   * @param releaseGranted 能不能放行（**UNDECIDABLE 也是 false**）
   * @param ruleCount 参与判定的生效规则数
   * @param reasonCount 原因条数
   * @param blockingReasonCount 其中阻断级条数
   * @param waivedReasonCount 因豁免放行的条数
   * @param undecidableReasonCount 「判不了」的条数
   * @param reasons 逐条原因
   * @param exemptions 因豁免放行的指向
   * @param remedies 解除方式
   * @param decidedAt 判定时刻
   * @param decidedBy 判定人
   * @param note 口径说明
   */
  public record ComplianceInterceptDecision(
      String decisionId,
      String scene,
      String sceneLabel,
      String subjectType,
      String subjectTypeLabel,
      String subjectId,
      String shopId,
      String platformId,
      String marketplaceId,
      String conclusion,
      String conclusionLabel,
      boolean blocked,
      boolean releaseGranted,
      int ruleCount,
      int reasonCount,
      int blockingReasonCount,
      int waivedReasonCount,
      int undecidableReasonCount,
      List<ComplianceInterceptReasonRow> reasons,
      List<ComplianceInterceptExemptionRef> exemptions,
      List<ComplianceInterceptRemedy> remedies,
      OffsetDateTime decidedAt,
      String decidedBy,
      String note) {}

  /**
   * 判定历史（**真分页**）。
   *
   * @param items 判定行
   * @param total 总条数
   * @param page 当前页
   * @param size 每页条数
   * @param blockCount 阻断数（覆盖整个筛选结果集）
   * @param warnCount 警告数（覆盖整个筛选结果集）
   * @param undecidableCount 判不了的条数（覆盖整个筛选结果集）
   * @param passCount 通过数（覆盖整个筛选结果集）
   * @param note 口径说明
   */
  public record ComplianceInterceptDecisionPage(
      List<ComplianceInterceptDecision> items,
      long total,
      int page,
      int size,
      long passCount,
      long warnCount,
      long blockCount,
      long undecidableCount,
      String note) {}

  // ---------------------------------------------------------------- 豁免

  /**
   * 一张豁免单。
   *
   * @param exemptionId 豁免单 id
   * @param scene 拦截点
   * @param sceneLabel 拦截点人话
   * @param subjectType 校验对象
   * @param subjectTypeLabel 校验对象人话
   * @param subjectId 主体逻辑 id
   * @param ruleId 被豁免的规则 id
   * @param ruleCode 被豁免的规则编码
   * @param ruleTitle 被豁免的规则标题
   * @param status 豁免状态
   * @param statusLabel 状态人话
   * @param applicantId 申请人
   * @param applicantReason 申请人理由（**必填**）
   * @param approverId 审批人（待审批时为 null）
   * @param decisionNote 审批意见（审批后必填）
   * @param validFrom 有效期起
   * @param validTo 有效期止
   * @param expired 现在是否已过期（按数据库时钟现算，不落库）
   * @param effective 现在是否真的生效（已批准且未过期）
   * @param decidable 当前登录人能不能审批这张单（**申请人不能审批自己**；服务端算好）
   * @param decidedAt 审批时刻
   * @param createdAt 创建时刻
   */
  public record ComplianceInterceptExemptionRow(
      String exemptionId,
      String scene,
      String sceneLabel,
      String subjectType,
      String subjectTypeLabel,
      String subjectId,
      String ruleId,
      String ruleCode,
      String ruleTitle,
      String status,
      String statusLabel,
      String applicantId,
      String applicantReason,
      String approverId,
      String decisionNote,
      OffsetDateTime validFrom,
      OffsetDateTime validTo,
      boolean expired,
      boolean effective,
      boolean decidable,
      OffsetDateTime decidedAt,
      OffsetDateTime createdAt) {}

  /**
   * 豁免单清单（**真分页**）。
   *
   * @param items 豁免单
   * @param total 总条数
   * @param page 当前页
   * @param size 每页条数
   * @param pendingCount 待审批数（覆盖整个筛选结果集）
   * @param approvedCount 已批准数（覆盖整个筛选结果集）
   * @param rejectedCount 已驳回数（覆盖整个筛选结果集）
   * @param effectiveCount 当前真正生效的豁免数（覆盖整个筛选结果集）
   * @param note 口径说明
   */
  public record ComplianceInterceptExemptionPage(
      List<ComplianceInterceptExemptionRow> items,
      long total,
      int page,
      int size,
      long pendingCount,
      long approvedCount,
      long rejectedCount,
      long effectiveCount,
      String note) {}

  // ---------------------------------------------------------------- 入参

  /**
   * 触发一次校验的入参。
   *
   * @param scene 拦截点（LISTING_PUBLISH / SHIPMENT / WITHDRAWAL）
   * @param subjectType 校验对象（PRODUCT / ORDER / SHOP）
   * @param subjectId 主体逻辑 id
   * @param shopId 主体所在店铺（可空）
   * @param platformId 主体所在平台（可空）
   * @param marketplaceId 主体所在站点（可空）
   * @param fields 被校验的文本字段（标题 / 描述 / 卖点 …）
   * @param requirements 非词条类规则的**要求状态**（认证 / 禁限售 / 标签 / 材质）
   */
  public record ComplianceInterceptEvaluateInput(
      String scene,
      String subjectType,
      String subjectId,
      String shopId,
      String platformId,
      String marketplaceId,
      List<ComplianceInterceptFieldInput> fields,
      List<ComplianceInterceptRequirementInput> requirements) {}

  /** 一个被校验的文本字段。 */
  public record ComplianceInterceptFieldInput(String key, String value) {}

  /**
   * 一条要求状态。
   *
   * @param ruleCode 规则编码（对应 16.2.1.1 的规则）
   * @param status SATISFIED 已满足 / UNSATISFIED 未满足
   * @param evidence 依据（人能读：凭什么说它满足 / 不满足）
   */
  public record ComplianceInterceptRequirementInput(
      String ruleCode, String status, String evidence) {}

  /** 🔴 改策略的入参。 */
  public record ComplianceInterceptPolicyInput(String enabled, String undecidableAction, String note) {}

  /**
   * 申请豁免的入参。
   *
   * @param scene 拦截点
   * @param subjectType 校验对象
   * @param subjectId 主体逻辑 id
   * @param ruleId 被豁免的规则 id（**必填**：一张豁免只针对一条规则）
   * @param reason 申请人理由（**必填**，非空且非空白）
   * @param validFrom 有效期起
   * @param validTo 有效期止（**必填**：豁免不能永久生效）
   */
  public record ComplianceInterceptExemptionInput(
      String scene,
      String subjectType,
      String subjectId,
      String ruleId,
      String reason,
      OffsetDateTime validFrom,
      OffsetDateTime validTo) {}

  /**
   * 审批的入参。
   *
   * @param note 审批意见（**必填**，非空且非空白）
   */
  public record ComplianceInterceptDecisionInput(String note) {}
}
