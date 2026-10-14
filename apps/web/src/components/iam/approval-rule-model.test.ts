import { describe, expect, it } from 'vitest';
import {
  DEFAULT_ACTION_LABELS,
  DIMENSION_LABELS,
  type RuleDraft,
  type RuleSetDraft,
  activeExemptionCeilings,
  conditionSummary,
  emptyDraft,
  ruleSetSummary,
  toRequestBody,
  validateDraft,
} from './approval-rule-model';

const FLOW = 'flow-1';

/** 一条合法的免审规则（带上限金额），按需覆盖字段。 */
function exemptRule(over: Partial<RuleDraft> = {}): RuleDraft {
  return {
    ruleName: '小额免审',
    priority: 0,
    amountCurrencyCode: 'USD',
    amountMinAmount: '',
    amountMaxAmount: '500',
    crossLegalEntity: 'ANY',
    crossDepartment: 'ANY',
    newVendor: 'ANY',
    highRiskProduct: 'ANY',
    decision: 'EXEMPT',
    targetFlowId: null,
    status: 'ACTIVE',
    description: '',
    ...over,
  };
}

/** 一条合法的送审规则。 */
function submitRule(over: Partial<RuleDraft> = {}): RuleDraft {
  return {
    ...exemptRule({ ruleName: '其余送审', priority: 1 }),
    amountCurrencyCode: '',
    amountMaxAmount: '',
    decision: 'SUBMIT_FOR_APPROVAL',
    targetFlowId: FLOW,
    ...over,
  };
}

function draftOf(rules: RuleDraft[], over: Partial<RuleSetDraft> = {}): RuleSetDraft {
  return {
    documentTypeCode: 'PURCHASE_ORDER',
    ruleSetName: '采购规则',
    status: 'ACTIVE',
    defaultAction: 'SUBMIT_FOR_APPROVAL',
    description: '',
    rules,
    ...over,
  };
}

const KNOWN = [FLOW];

describe('17.4.1.2 审批规则前端校验', () => {
  it('合法的「小额免审 + 其余送审」没有任何错误', () => {
    expect(validateDraft(draftOf([exemptRule(), submitRule()]), KNOWN)).toEqual([]);
  });

  it('蓝图硬要求：免审没有上限金额必须被拒', () => {
    const errors = validateDraft(draftOf([exemptRule({ amountMaxAmount: '' })]), KNOWN);
    expect(errors.join()).toContain('免审必须设置上限金额');
  });

  it('免审上限为 0 被拒', () => {
    const errors = validateDraft(draftOf([exemptRule({ amountMaxAmount: '0' })]), KNOWN);
    expect(errors.join()).toContain('必须大于 0');
  });

  it('免审不得豁免四个高风险条件', () => {
    for (const field of [
      'crossLegalEntity',
      'crossDepartment',
      'newVendor',
      'highRiskProduct',
    ] as const) {
      const errors = validateDraft(draftOf([exemptRule({ [field]: 'REQUIRED' })]), KNOWN);
      expect(errors.join(), field).toContain('免审规则不能要求');
    }
  });

  it('免审写成 FORBIDDEN（不命中时才免审）是允许的：收窄而非挖穿', () => {
    expect(
      validateDraft(
        draftOf([exemptRule({ crossLegalEntity: 'FORBIDDEN', newVendor: 'FORBIDDEN' })]),
        KNOWN,
      ),
    ).toEqual([]);
  });

  it('免审不允许指向审批流', () => {
    const errors = validateDraft(draftOf([exemptRule({ targetFlowId: FLOW })]), KNOWN);
    expect(errors.join()).toContain('免审不指向流程');
  });

  it('送审规则必须选择审批流，且必须是已知流程', () => {
    expect(validateDraft(draftOf([submitRule({ targetFlowId: null })]), KNOWN).join()).toContain(
      '必须选择走哪条审批流',
    );
    expect(validateDraft(draftOf([submitRule({ targetFlowId: 'ghost' })]), KNOWN).join()).toContain(
      '引用的审批流不存在',
    );
  });

  it('默认免审与送审规则互斥（静默放行漏洞）', () => {
    const errors = validateDraft(
      draftOf([exemptRule(), submitRule()], { defaultAction: 'EXEMPT' }),
      KNOWN,
    );
    expect(errors.join()).toContain('静默放行');
    // 纯免审规则集 + 默认免审是自洽的。
    expect(validateDraft(draftOf([exemptRule()], { defaultAction: 'EXEMPT' }), KNOWN)).toEqual([]);
  });

  it('优先级必须唯一且非负', () => {
    expect(
      validateDraft(
        draftOf([exemptRule({ priority: 1 }), submitRule({ priority: 1 })]),
        KNOWN,
      ).join(),
    ).toContain('优先级 1 重复');
    expect(validateDraft(draftOf([exemptRule({ priority: -1 })]), KNOWN).join()).toContain(
      '不小于 0 的整数',
    );
  });

  it('金额区间必须配币种，且构成正区间', () => {
    expect(
      validateDraft(draftOf([exemptRule({ amountCurrencyCode: '' })]), KNOWN).join(),
    ).toContain('填了金额区间就必须给币种');
    expect(
      validateDraft(draftOf([exemptRule({ amountMinAmount: '600' })]), KNOWN).join(),
    ).toContain('上限必须大于下限');
    expect(
      validateDraft(draftOf([exemptRule({ amountCurrencyCode: 'usd' })]), KNOWN).join(),
    ).toContain('三个大写字母');
  });

  it('单据类型与规则集名称必填，空规则集被拒', () => {
    expect(validateDraft(draftOf([], { documentTypeCode: '' }), KNOWN).join()).toContain(
      '至少要有一条规则',
    );
    expect(
      validateDraft(
        draftOf([exemptRule()], { documentTypeCode: '', ruleSetName: '' }),
        KNOWN,
      ).join(),
    ).toContain('请选择适用单据类型');
  });

  it('空草稿默认是生效策略送审 + 一条待填上限的免审规则', () => {
    const draft = emptyDraft();
    expect(draft.defaultAction).toBe(DEFAULT_ACTION_LABELS ? 'SUBMIT_FOR_APPROVAL' : '');
    expect(draft.rules).toHaveLength(1);
    expect(draft.rules[0]!.decision).toBe('EXEMPT');
    expect(draft.rules[0]!.amountMaxAmount).toBe('');
  });
});

describe('条件摘要与免审额度', () => {
  it('免审额度写成「币种 + 上限以内」', () => {
    expect(conditionSummary(exemptRule())).toBe('USD 500 以内');
  });

  it('下限与上限同时给出时是半开区间', () => {
    expect(conditionSummary(exemptRule({ amountMinAmount: '100' }))).toBe('USD 100 ~ 500');
  });

  it('四维里非 ANY 的维度进入摘要，并带三态口径', () => {
    expect(
      conditionSummary(exemptRule({ crossLegalEntity: 'FORBIDDEN', highRiskProduct: 'REQUIRED' })),
    ).toBe(
      `USD 500 以内 · 跨法人·${DIMENSION_LABELS.FORBIDDEN} · 高风险商品·${DIMENSION_LABELS.REQUIRED}`,
    );
  });

  it('什么都不约束时摘要就是「任何提交」', () => {
    expect(conditionSummary(submitRule())).toBe('任何提交');
  });

  it('停用与送审的规则不计入当前免审额度', () => {
    const rules = [
      { status: 'ACTIVE', decision: 'EXEMPT' },
      { status: 'DISABLED', decision: 'EXEMPT' },
      { status: 'ACTIVE', decision: 'SUBMIT_FOR_APPROVAL' },
    ];
    expect(activeExemptionCeilings(rules)).toHaveLength(1);
    expect(activeExemptionCeilings(rules)[0]).toBe(rules[0]);
  });

  it('规则集摘要带出规则数与免审/送审拆分', () => {
    expect(
      ruleSetSummary({
        documentTypeName: '采购订单',
        ruleCount: 3,
        exemptionCount: 1,
        submitCount: 2,
      }),
    ).toBe('采购订单 · 3 条规则 · 免审 1 · 送审 2');
  });
});

describe('请求体转换', () => {
  it('空字符串转 null，免审规则清空审批流，并按优先级排序', () => {
    const body = toRequestBody(
      draftOf([
        submitRule({ ruleName: '后判定', priority: 5 }),
        exemptRule({ ruleName: '先判定', priority: 1, amountMinAmount: '' }),
      ]),
    );
    expect(body.rules.map((r) => r.ruleName)).toEqual(['先判定', '后判定']);
    expect(body.rules[0]!.amountMinAmount).toBeNull();
    expect(body.rules[0]!.targetFlowId).toBeNull();
    expect(body.rules[1]!.targetFlowId).toBe(FLOW);
  });

  it('免审即使草稿里残留了流程也会被清掉', () => {
    const body = toRequestBody(draftOf([exemptRule({ targetFlowId: FLOW })]));
    expect(body.rules[0]!.targetFlowId).toBeNull();
  });
});
