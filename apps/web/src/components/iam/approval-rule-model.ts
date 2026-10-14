/**
 * 17.4.1.2 审批规则的展示口径与前端预校验。
 *
 * <p>后端 {@code ApprovalRuleController} 是最终判定（免审必须有上限金额、免审不得豁免高风险条件、
 * 送审必须指向本单据类型的生效流程、优先级唯一、生效唯一），这里的校验与展示同口径，
 * 目的是让用户在提交前就看到「为什么不行」，而不是提交后吃一个 400。后端仍然是权威。
 *
 * <p>蓝图那句「免审要设上限金额，否则小额免审会被用来绕过大额管控」在两侧都必须成立，
 * 所以 {@link validateDraft} 里两条免审硬约束与后端逐条对应：
 *
 * <ul>
 *   <li><b>免审必须有上限金额</b>：与后端 {@code approval_trigger_rule_decision_shape} 的
 *       {@code amount_max_amount IS NOT NULL AND amount_max_amount > 0} 同一口径。
 *   <li><b>免审不得豁免高风险条件</b>：跨法人 / 跨部门 / 新供应商 / 高风险商品四维不允许 REQUIRED。
 *       光卡上限金额不够 —— 一条「1000 以内免审」再叠一个「REQUIRED 跨法人」，等于跨法人小额
 *       也免审，管控面被从侧面挖穿。
 * </ul>
 *
 * <p><b>三态维度</b>（ANY / REQUIRED / FORBIDDEN）与后端同口径：{@code false} 无法表达
 * 「不约束」与「必须不满足」的差别，所以前端也不把它压成布尔。
 */

/** 条件维度三态。 */
export const DIMENSION_MODES = ['ANY', 'REQUIRED', 'FORBIDDEN'] as const;
export type DimensionMode = (typeof DIMENSION_MODES)[number];

/** 规则集状态。 */
export const RULE_SET_STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  ACTIVE: '生效中',
  DISABLED: '已停用',
};
export const RULE_SET_STATUS_TONES: Record<string, 'neutral' | 'primary' | 'success' | 'warning'> =
  {
    DRAFT: 'neutral',
    ACTIVE: 'success',
    DISABLED: 'warning',
  };

/** 命中后的动作：送审（走流程）或免审（直接通过）。 */
export const DECISION_LABELS: Record<string, string> = {
  SUBMIT_FOR_APPROVAL: '送审',
  EXEMPT: '免审',
};
export const DECISION_TONES: Record<string, 'neutral' | 'primary' | 'success' | 'warning'> = {
  SUBMIT_FOR_APPROVAL: 'primary',
  EXEMPT: 'success',
};

/** 一条都没命中时的生效策略。 */
export const DEFAULT_ACTION_LABELS: Record<string, string> = {
  SUBMIT_FOR_APPROVAL: '一条都没命中也送审',
  EXEMPT: '一条都没命中就免审',
};

/** 三态维度的人话说明。这一段是「三态而不是布尔」的理由，界面上要讲清楚。 */
export const DIMENSION_LABELS: Record<string, string> = {
  ANY: '不约束',
  REQUIRED: '必须命中',
  FORBIDDEN: '必须不命中',
};

export const DIMENSION_HINTS: Record<string, string> = {
  ANY: '这一维不参与判断',
  REQUIRED: '本次提交满足该条件时才适用本规则',
  FORBIDDEN: '本次提交不满足该条件时才适用本规则',
};

/** 蓝图点名的四个条件维度，免审规则在这四维上不允许 REQUIRED。 */
export const GUARD_DIMENSIONS = [
  { field: 'crossLegalEntity', label: '跨法人' },
  { field: 'crossDepartment', label: '跨部门' },
  { field: 'newVendor', label: '新供应商' },
  { field: 'highRiskProduct', label: '高风险商品' },
] as const;

export const SEVERITY_LABELS: Record<string, string> = {
  ERROR: '阻断',
  WARNING: '提醒',
  INFO: '说明',
};

export const SEVERITY_TONES: Record<string, 'neutral' | 'primary' | 'success' | 'warning'> = {
  ERROR: 'warning',
  WARNING: 'primary',
  INFO: 'neutral',
};

/** 编辑器里的一条规则（还没落库，所以没有 id）。 */
export type RuleDraft = {
  ruleName: string;
  priority: number;
  amountCurrencyCode: string;
  amountMinAmount: string;
  amountMaxAmount: string;
  crossLegalEntity: DimensionMode;
  crossDepartment: DimensionMode;
  newVendor: DimensionMode;
  highRiskProduct: DimensionMode;
  decision: string;
  targetFlowId: string | null;
  status: string;
  description: string;
};

export type RuleSetDraft = {
  documentTypeCode: string;
  ruleSetName: string;
  status: string;
  defaultAction: string;
  description: string;
  rules: RuleDraft[];
};

/**
 * 空规则草稿。
 *
 * <p>默认给一条<b>免审</b>规则而不是送审规则：免审那侧的上限金额是必填的，一眼就能看见
 * 「免审要封顶」这条硬要求（蓝图原话）；送审那侧还要先有生效流程才能选，容易让人先填一半。
 */
export function emptyRule(flowId: string | null): RuleDraft {
  return {
    ruleName: '',
    priority: 0,
    amountCurrencyCode: '',
    amountMinAmount: '',
    amountMaxAmount: '',
    crossLegalEntity: 'ANY',
    crossDepartment: 'ANY',
    newVendor: 'ANY',
    highRiskProduct: 'ANY',
    decision: 'EXEMPT',
    targetFlowId: flowId,
    status: 'ACTIVE',
    description: '',
  };
}

/** 空规则集。默认生效策略是「送审」（fail-closed），与后端 CHECK 的默认值一致。 */
export function emptyDraft(): RuleSetDraft {
  return {
    documentTypeCode: '',
    ruleSetName: '',
    status: 'DRAFT',
    defaultAction: 'SUBMIT_FOR_APPROVAL',
    description: '',
    rules: [emptyRule(null)],
  };
}

const MONEY = /^\d+(\.\d+)?$/;

/**
 * 草稿整体校验，返回可直接渲染的错误文案列表。
 *
 * <p>顺序刻意与后端 {@code normalizeRules} 一致，用户看到的提示与服务端拒绝的理由对得上。
 */
export function validateDraft(draft: RuleSetDraft, knownFlows: string[]): string[] {
  const errors: string[] = [];
  if (!draft.documentTypeCode.trim()) errors.push('请选择适用单据类型');
  if (!draft.ruleSetName.trim()) errors.push('请填写规则集名称');
  if (draft.rules.length === 0) {
    errors.push('规则集至少要有一条规则');
    return errors;
  }

  const names = new Set<string>();
  const priorities = new Set<number>();
  for (const r of draft.rules) {
    const name = r.ruleName.trim();
    const label = name || '未命名规则';
    if (!name) errors.push(`${label}：规则名称不能为空`);
    else if (names.has(name)) errors.push(`规则名称重复：${name}`);
    names.add(name);
    if (!Number.isInteger(r.priority) || r.priority < 0)
      errors.push(`${label}：优先级必须是不小于 0 的整数`);
    if (priorities.has(r.priority))
      errors.push(`${label}：优先级 ${r.priority} 重复；先命中先生效，同序没有唯一答案`);
    priorities.add(r.priority);

    const currency = r.amountCurrencyCode.trim();
    const min = r.amountMinAmount.trim();
    const max = r.amountMaxAmount.trim();
    if (currency && !/^[A-Z]{3}$/.test(currency))
      errors.push(`${label}：币种必须是三个大写字母，如 USD`);
    // 区间只在同一币种内有意义，两侧必须同时给或同时不给。
    if ((min || max) && !currency)
      errors.push(`${label}：填了金额区间就必须给币种，区间只在同一币种内有意义`);
    if (!min && !max && currency)
      errors.push(`${label}：只填币种不填金额区间等于没约束金额，请把币种也清空`);
    if (min && !MONEY.test(min)) errors.push(`${label}：金额下限必须是非负数字`);
    if (max && !MONEY.test(max)) errors.push(`${label}：金额上限必须是非负数字`);
    if (min && max && Number(max) <= Number(min)) errors.push(`${label}：上限必须大于下限`);

    if (r.decision === 'EXEMPT') {
      // 蓝图那句「免审要设上限金额」的前端第一道闸。
      if (!max)
        errors.push(
          `${label}：免审必须设置上限金额；免审没有上限就能被用来绕过大额管控（后端同样会拒）`,
        );
      else if (Number(max) <= 0) errors.push(`${label}：免审上限金额必须大于 0`);
      if (r.targetFlowId) errors.push(`${label}：免审不指向流程，免审就是不走审批，请把审批流清空`);
      // 第二道闸：免审不得豁免蓝图点名的四个高风险条件。
      for (const guard of GUARD_DIMENSIONS)
        if (r[guard.field] === 'REQUIRED')
          errors.push(
            `${label}：免审规则不能要求${guard.label}；免审只允许「该条件不命中时才免审」或「不约束」`,
          );
    } else {
      if (!r.targetFlowId) errors.push(`${label}：送审规则必须选择走哪条审批流`);
      else if (knownFlows.length > 0 && !knownFlows.includes(r.targetFlowId))
        errors.push(`${label}：引用的审批流不存在，请重新选择`);
    }
  }

  // 「默认免审 + 存在送审规则」= 没命中规则的提交被静默放行。与后端 checkDefaultAction 同口径。
  if (draft.defaultAction === 'EXEMPT') {
    const submits = draft.rules
      .filter((r) => r.decision === 'SUBMIT_FOR_APPROVAL')
      .map((r) => r.ruleName.trim() || '未命名规则');
    if (submits.length > 0)
      errors.push(
        `生效策略选了「一条都没命中就免审」，但还有送审规则（${submits.join('、')}）：没命中规则的提交会被静默放行，存在送审规则时默认策略只能是送审`,
      );
  }

  return errors;
}

/**
 * 一条规则的条件摘要（界面上给免审额度用）。
 *
 * <p>金额写作半开区间 {@code [下限, 上限)}：上限是<b>封顶</b>，达到上限就走下一条规则。
 */
export function conditionSummary(rule: {
  amountCurrencyCode?: string | null;
  amountMinAmount?: number | string | null;
  amountMaxAmount?: number | string | null;
  crossLegalEntity: string;
  crossDepartment: string;
  newVendor: string;
  highRiskProduct: string;
}): string {
  const parts: string[] = [];
  const currency = rule.amountCurrencyCode?.trim();
  if (currency) {
    const min =
      rule.amountMinAmount === null || rule.amountMinAmount === undefined
        ? ''
        : String(rule.amountMinAmount);
    const max =
      rule.amountMaxAmount === null || rule.amountMaxAmount === undefined
        ? ''
        : String(rule.amountMaxAmount);
    if (!min && !max) parts.push(`任意金额 ${currency}`);
    else if (!min) parts.push(`${currency} ${max} 以内`);
    else if (!max) parts.push(`${currency} ${min} 及以上`);
    else parts.push(`${currency} ${min} ~ ${max}`);
  }
  for (const guard of GUARD_DIMENSIONS)
    if (rule[guard.field] !== 'ANY')
      parts.push(`${guard.label}·${DIMENSION_LABELS[rule[guard.field]]}`);
  return parts.length === 0 ? '任何提交' : parts.join(' · ');
}

/** 规则集摘要：列表页一行说清「管哪个单据类型、几条规则、其中几条免审」。 */
export function ruleSetSummary(set: {
  documentTypeName: string;
  ruleCount: number;
  exemptionCount: number;
  submitCount: number;
}): string {
  return `${set.documentTypeName} · ${set.ruleCount} 条规则 · 免审 ${set.exemptionCount} · 送审 ${set.submitCount}`;
}

/**
 * 当前生效规则集里的免审额度（配置页最需要盯住的一处）。
 *
 * <p>停用的规则不计入：停用等于这条额度当前不存在，留在清单里会让人以为还在免审。
 */
export function activeExemptionCeilings<T extends { status: string; decision: string }>(
  rules: T[],
): T[] {
  return rules.filter((r) => r.status === 'ACTIVE' && r.decision === 'EXEMPT');
}

/** 草稿 → 请求体。把空字符串转成 null，省得把「」当成金额下限发上去。 */
export function toRequestBody(draft: RuleSetDraft) {
  const blank = (v: string) => (v.trim() === '' ? null : v.trim());
  return {
    documentTypeCode: draft.documentTypeCode.trim(),
    ruleSetName: draft.ruleSetName.trim(),
    status: draft.status,
    defaultAction: draft.defaultAction,
    description: draft.description.trim(),
    rules: [...draft.rules]
      .sort((a, b) => a.priority - b.priority)
      .map((r) => ({
        ruleName: r.ruleName.trim(),
        priority: r.priority,
        amountCurrencyCode: blank(r.amountCurrencyCode),
        amountMinAmount: blank(r.amountMinAmount),
        amountMaxAmount: blank(r.amountMaxAmount),
        crossLegalEntity: r.crossLegalEntity,
        crossDepartment: r.crossDepartment,
        newVendor: r.newVendor,
        highRiskProduct: r.highRiskProduct,
        decision: r.decision,
        targetFlowId: r.decision === 'EXEMPT' ? null : r.targetFlowId,
        status: r.status,
        description: r.description.trim(),
      })),
  };
}
