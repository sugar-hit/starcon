import { describe, expect, it } from 'vitest';
import type {
  AutoApprovalCheck,
  AutoApprovalRoutingSnapshot,
} from '../../../../../packages/api-contracts/src/index';
import {
  EMPTY_EVALUATE,
  EMPTY_POLICY,
  EMPTY_SIGNAL,
  checkSummary,
  decisionLabel,
  decisionTone,
  escalationActionLabel,
  escalationStatusLabel,
  executionStateLabel,
  failedChecks,
  formatMoney,
  parseSignalCodes,
  policyStatusLabel,
  routingSummary,
  signalKindLabel,
  signalKindTone,
  upstreamChannelHint,
  validateEvaluateForm,
  validatePolicyForm,
  validateSignalForm,
} from './auto-approval-model';

/**
 * 18.3.3.1「自动审批」前端模型单测。
 *
 * 🔴 重点盯四件**最容易被做丢**的事：
 * <ol>
 *   <li>「走人工」**不能**显示成「已驳回」—— 主观风险只走人工，不驳回；</li>
 *   <li>「自动通过」**必须**有据可查（逐条判定 + 当时的规则快照）；</li>
 *   <li>金额上限**上下颠倒**的表单必须被挡住（同一笔金额会同时「够小」又「越界」）；</li>
 *   <li>「上游没有程序化决策通道」这句话**必须**出现在页面上。</li>
 * </ol>
 */

function check(code: string, passed: boolean, detail = ''): AutoApprovalCheck {
  return { code, passed, detail };
}

function routing(
  overrides: Partial<AutoApprovalRoutingSnapshot> = {},
): AutoApprovalRoutingSnapshot {
  return {
    routed: true,
    flowId: '00000000-0000-4000-8000-000000000001',
    flowName: '采购审批流',
    currencyCode: 'USD',
    branchName: '大额档',
    minAmount: 1000,
    maxAmount: null,
    targetNodeKey: 'DIRECTOR',
    targetNodeName: '采购总监审批',
    approverType: 'USER',
    roleId: null,
    positionId: null,
    membershipId: '00000000-0000-4000-8000-000000000002',
    upstreamNodeKey: 'MANAGER',
    upstreamRoutedBy: 'AMOUNT_BRANCH',
    note: '',
    ...overrides,
  };
}

describe('18.3.3.1 自动审批 · 决策的显示语义', () => {
  it('自动通过 / 自动驳回 / 走人工 三种结论各有各的说法', () => {
    expect(decisionLabel('AUTO_APPROVED')).toBe('自动通过');
    expect(decisionLabel('AUTO_REJECTED')).toBe('自动驳回');
    expect(decisionLabel('MANUAL_REQUIRED')).toBe('走人工审批');
  });

  it('走人工**不能**显示成红色的驳回', () => {
    expect(decisionTone('MANUAL_REQUIRED')).toBe('warn');
    expect(decisionTone('AUTO_REJECTED')).toBe('danger');
    expect(decisionTone('AUTO_APPROVED')).toBe('ok');
  });

  it('信号性质决定能不能自动驳回', () => {
    expect(signalKindLabel('VIOLATION')).toContain('可自动驳回');
    expect(signalKindLabel('RISK')).toContain('只走人工');
    expect(signalKindTone('VIOLATION')).toBe('danger');
    expect(signalKindTone('RISK')).toBe('warn');
  });

  it('决策落点与升级状态都有可读说法', () => {
    expect(executionStateLabel('NOT_REQUIRED')).toContain('不需要人工');
    expect(executionStateLabel('SUBMITTED_TO_APPROVAL')).toContain('上游');
    expect(executionStateLabel('SUBMIT_FAILED')).toContain('失败');
    expect(escalationStatusLabel('DETECTED')).toContain('待执行');
    expect(escalationStatusLabel('EXECUTED')).toBe('已执行');
    expect(escalationActionLabel('ESCALATE')).toContain('升级');
    expect(escalationActionLabel('AUTO_APPROVE')).toContain('视同通过');
    expect(policyStatusLabel('ACTIVE')).toBe('生效中');
  });
});

describe('18.3.3.1 自动审批 · 决策依据必须能查', () => {
  it('自动通过时也要说清是哪几个条件都成立', () => {
    const checks = [
      check('SMALL_AMOUNT', true, '金额在小额上限之内'),
      check('SUPPLIER_VERIFIED', true, '供应商已验证'),
      check('NO_RISK_SIGNAL', true, '没有任何风险信号'),
    ];
    expect(failedChecks(checks)).toHaveLength(0);
    const summary = checkSummary(checks);
    expect(summary).toContain('全部条件成立');
    expect(summary).toContain('SMALL_AMOUNT');
    expect(summary).toContain('SUPPLIER_VERIFIED');
  });

  it('走人工时要指出**到底哪一条没过**', () => {
    const checks = [
      check('SMALL_AMOUNT', true, '金额在小额上限之内'),
      check('SUPPLIER_VERIFIED', false, '供应商未验证'),
    ];
    expect(failedChecks(checks)).toHaveLength(1);
    expect(checkSummary(checks)).toContain('SUPPLIER_VERIFIED');
    expect(checkSummary(checks)).toContain('供应商未验证');
  });

  it('金额分层路由的说明写清命中哪一档、目标是谁', () => {
    const summary = routingSummary(routing());
    expect(summary).toContain('大额档');
    expect(summary).toContain('DIRECTOR');
    expect(summary).toContain('指定审批人');
  });

  it('没命中分支时如实说没命中，而不是编一个审批人', () => {
    const summary = routingSummary(
      routing({ routed: false, note: '该金额没有落进任何一条金额分支' }),
    );
    expect(summary).toContain('没有落进');
    expect(summary).not.toContain('DIRECTOR');
  });

  it('上游通道的说明必须出现「没有程序化通过/驳回通道」', () => {
    const hint = upstreamChannelHint({
      module: '18.3.3.1 自动审批（platform 服务）',
      upstream: '17.4 审批（identity 服务）',
      upstreamProgrammaticDecisionChannel: false,
      upstreamDecisionChannelNote: '上游没有程序化通过/驳回通道。',
      upstreamIntakeChannel: '有：POST /identity/approval-instances。',
      decisionOwnership: '自动审批决策归本模块。',
      decisions: ['AUTO_APPROVED'],
      checks: ['SMALL_AMOUNT'],
      permissions: ['platform.auto_approval.read'],
    });
    expect(hint).toContain('没有程序化通过/驳回通道');
    expect(hint).toContain('approval-instances');
  });
});

describe('18.3.3.1 自动审批 · 表单校验', () => {
  it('规则表单：必填项与币种形状', () => {
    expect(validatePolicyForm(EMPTY_POLICY)).toContain('规则编码');
    expect(validatePolicyForm({ ...EMPTY_POLICY, ruleCode: 'A' })).toContain('规则名称');
    expect(validatePolicyForm({ ...EMPTY_POLICY, ruleCode: 'A', ruleName: 'B' })).toContain(
      '单据类型',
    );
    expect(
      validatePolicyForm({
        ...EMPTY_POLICY,
        ruleCode: 'A',
        ruleName: 'B',
        documentTypeCode: 'PO',
        currencyCode: 'usd',
      }),
    ).toContain('币种');
  });

  it('规则表单：小额上限不能大于硬上限', () => {
    const base = {
      ...EMPTY_POLICY,
      ruleCode: 'A',
      ruleName: 'B',
      documentTypeCode: 'PO',
      currencyCode: 'USD',
    };
    expect(
      validatePolicyForm({ ...base, autoApproveMaxAmount: '10000', amountHardLimit: '1000' }),
    ).toContain('小额上限不能大于硬上限');
    expect(
      validatePolicyForm({ ...base, autoApproveMaxAmount: '1000', amountHardLimit: '10000' }),
    ).toBeNull();
  });

  it('规则表单：金额不能为负、优先级必须是整数', () => {
    const base = {
      ...EMPTY_POLICY,
      ruleCode: 'A',
      ruleName: 'B',
      documentTypeCode: 'PO',
      currencyCode: 'USD',
    };
    expect(validatePolicyForm({ ...base, autoApproveMaxAmount: '-1' })).toContain('不能为负');
    expect(validatePolicyForm({ ...base, priority: '1.5' })).toContain('整数');
  });

  it('信号表单：性质只能是两类', () => {
    expect(validateSignalForm(EMPTY_SIGNAL)).toContain('信号编码');
    expect(validateSignalForm({ ...EMPTY_SIGNAL, signalCode: 'X' })).toContain('信号名称');
    expect(
      validateSignalForm({
        ...EMPTY_SIGNAL,
        signalCode: 'X',
        signalName: 'Y',
        signalKind: 'OTHER',
      }),
    ).toContain('信号性质');
    expect(
      validateSignalForm({ ...EMPTY_SIGNAL, signalCode: 'X', signalName: 'Y', signalKind: 'RISK' }),
    ).toBeNull();
  });

  it('判定表单：金额与币种必须成对', () => {
    const base = {
      ...EMPTY_EVALUATE,
      documentTypeCode: 'PO',
      documentRef: 'PO-1',
      sourceSystem: 'trade',
    };
    expect(validateEvaluateForm({ ...base, amount: '100', currencyCode: '' })).toContain(
      '必须同时提供',
    );
    expect(validateEvaluateForm({ ...base, amount: '', currencyCode: 'USD' })).toContain(
      '必须同时提供',
    );
    expect(validateEvaluateForm({ ...base, amount: '100', currencyCode: 'USD' })).toBeNull();
    expect(validateEvaluateForm({ ...base, amount: '', currencyCode: '' })).toBeNull();
  });

  it('判定表单：必填项与金额不能为负', () => {
    expect(validateEvaluateForm(EMPTY_EVALUATE)).toContain('单据类型');
    expect(validateEvaluateForm({ ...EMPTY_EVALUATE, documentTypeCode: 'PO' })).toContain('单据号');
    expect(
      validateEvaluateForm({ ...EMPTY_EVALUATE, documentTypeCode: 'PO', documentRef: 'X' }),
    ).toContain('来源系统');
    expect(
      validateEvaluateForm({
        ...EMPTY_EVALUATE,
        documentTypeCode: 'PO',
        documentRef: 'X',
        sourceSystem: 'trade',
        amount: '-1',
      }),
    ).toContain('不能为负');
  });

  it('风险信号码可以从逗号 / 顿号 / 换行里拆出来', () => {
    expect(parseSignalCodes('A, B、C\nD')).toEqual(['A', 'B', 'C', 'D']);
    expect(parseSignalCodes('  ')).toEqual([]);
  });
});

describe('18.3.3.1 自动审批 · 显示细节', () => {
  it('金额为空时显示破折号而不是 0', () => {
    expect(formatMoney(null, 'USD')).toBe('—');
    expect(formatMoney(100, 'USD')).toBe('100 USD');
    expect(formatMoney(100, null)).toBe('100');
  });
});
