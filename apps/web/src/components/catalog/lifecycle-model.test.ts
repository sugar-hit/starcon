import { describe, expect, it } from 'vitest';
import type {
  RuleInput,
  TransitionOption,
} from '../../../../../packages/api-contracts/src/catalog';
import {
  operatorLabel,
  ruleDraftValid,
  ruleTargets,
  statusLabel,
  transitionAllowed,
} from './lifecycle-model';

const transitions: TransitionOption[] = [
  {
    toStatus: 'PENDING_LISTING',
    toLabel: '待上架',
    note: '提交待上架',
    reasonRequired: true,
    requiresActiveSku: true,
  },
  {
    toStatus: 'DISCONTINUED',
    toLabel: '停产',
    note: '作废草稿',
    reasonRequired: true,
    requiresActiveSku: false,
  },
];

function draft(patch: Partial<RuleInput> = {}): RuleInput {
  return {
    code: 'STOP-LOW-DEMAND',
    name: '停售长尾商品',
    description: '',
    enabled: true,
    targetStatus: 'SUSPENDED',
    reasonTemplate: '批量停售',
    version: 0,
    conditions: [{ metric: 'ACTIVE_SKU_COUNT', operator: 'GTE', threshold: 1, label: null }],
    ...patch,
  };
}

describe('statusLabel', () => {
  it('labels every lifecycle state and falls back to the raw code', () => {
    expect(statusLabel('DRAFT')).toBe('草稿');
    expect(statusLabel('PENDING_LISTING')).toBe('待上架');
    expect(statusLabel('ON_SALE')).toBe('在售');
    expect(statusLabel('SUSPENDED')).toBe('停售');
    expect(statusLabel('DISCONTINUED')).toBe('停产');
    expect(statusLabel('WHATEVER')).toBe('WHATEVER');
  });
});

describe('operatorLabel', () => {
  it('maps the six supported comparison operators', () => {
    expect(operatorLabel('GTE')).toBe('大于等于');
    expect(operatorLabel('LT')).toBe('小于');
    expect(operatorLabel('BAD')).toBe('BAD');
  });
});

describe('transitionAllowed', () => {
  it('requires a target from the matrix', () => {
    expect(transitionAllowed(transitions, '', '原因')).toBe(false);
    expect(transitionAllowed(transitions, 'ON_SALE', '原因')).toBe(false);
  });

  it('requires a non-blank reason when the matrix marks it required', () => {
    expect(transitionAllowed(transitions, 'PENDING_LISTING', '   ')).toBe(false);
    expect(transitionAllowed(transitions, 'PENDING_LISTING', '资料齐备')).toBe(true);
  });

  it('allows an empty reason when the matrix does not require it', () => {
    expect(
      transitionAllowed([{ ...transitions[0]!, reasonRequired: false }], 'PENDING_LISTING', ''),
    ).toBe(true);
  });
});

describe('ruleDraftValid', () => {
  it('rejects codes outside the shared code rule', () => {
    expect(ruleDraftValid(draft({ code: 'bad code' }))).toContain('规则编码');
    expect(ruleDraftValid(draft({ code: 'OK_CODE-1' }))).toBeNull();
  });

  it('requires a name and at least one condition', () => {
    expect(ruleDraftValid(draft({ name: ' ' }))).toBe('请填写规则名称');
    expect(ruleDraftValid(draft({ conditions: [] }))).toBe('至少需要一个判定条件');
  });

  it('rejects a draft status outside the rule targets', () => {
    expect(ruleDraftValid(draft({ targetStatus: 'DRAFT' }))).toContain('不能把商品退回草稿');
    for (const target of ruleTargets)
      expect(ruleDraftValid(draft({ targetStatus: target }))).toBeNull();
  });

  it('rejects non-numeric and over-precise thresholds', () => {
    expect(
      ruleDraftValid(
        draft({
          conditions: [{ metric: 'SKU_COUNT', operator: 'GT', threshold: Number.NaN, label: null }],
        }),
      ),
    ).toBe('阈值必须是数字');
    expect(
      ruleDraftValid(
        draft({
          conditions: [
            { metric: 'INVENTORY_ON_HAND', operator: 'GTE', threshold: 1.1234567, label: null },
          ],
        }),
      ),
    ).toBe('阈值最多 6 位小数');
  });
});
