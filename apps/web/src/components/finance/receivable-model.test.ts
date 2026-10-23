import { describe, expect, it } from 'vitest';
import type { FinanceReceivablePolicyView } from '../../../../../packages/api-contracts/src/index';
import {
  agingBucketLabel,
  collectionTaskLevelLabel,
  collectionTaskStatusLabel,
  counterpartyTypeLabel,
  formatReceivableAmount,
  formatReceivableMoney,
  receivableEventLabel,
  receivableOverdueHint,
  receivablePolicyStateLabel,
  receivablePolicyStateTone,
  receivableSourceLabel,
  receivableSourceSystemLabel,
  receivableStatusLabel,
  receivableStatusTone,
} from './receivable-model';

/**
 * 12.1.1.1 应收单管理前端模型的单测。
 *
 * <p>重点守住三条**界面纪律**（它们一旦被破坏，页面就会开始骗人）：
 *
 * <ol>
 *   <li>「未配置」与「已禁用」必须能被分开看；</li>
 *   <li>金额**原样展示**，前端不做任何算术；</li>
 *   <li>来源（谁产出了这张应收）必须能被分开看。</li>
 * </ol>
 */

const policy = (over: Partial<FinanceReceivablePolicyView>): FinanceReceivablePolicyView => ({
  source: 'MANUAL',
  sourceLabel: '人工录入',
  configured: false,
  enabled: false,
  dueDays: 0,
  note: '',
  ownerModule: '',
  producible: true,
  detail: '',
  ...over,
});

describe('应收来源与状态的可读形态', () => {
  it('五个来源各自有可读名，且不认识的原样返回', () => {
    expect(receivableSourceLabel('PLATFORM_SETTLEMENT')).toBe('平台结算');
    expect(receivableSourceLabel('PLATFORM_WITHHOLDING')).toBe('平台代扣');
    expect(receivableSourceLabel('B2B_CREDIT_TERM')).toBe('B2B 客户账期');
    expect(receivableSourceLabel('MANUAL')).toBe('人工录入');
    expect(receivableSourceLabel('OTHER')).toBe('其他');
    expect(receivableSourceLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('「未核销」与「部分核销」不被合成同一个词', () => {
    expect(receivableStatusLabel('OPEN')).toBe('未核销');
    expect(receivableStatusLabel('PARTIALLY_SETTLED')).toBe('部分核销');
    expect(receivableStatusLabel('SETTLED')).toBe('已核销');
    expect(receivableStatusLabel('VOID')).toBe('已作废');
    expect(receivableStatusLabel('OPEN')).not.toBe(receivableStatusLabel('PARTIALLY_SETTLED'));
  });

  it('状态色调：未核销是警示、已核销是成功、已作废是中性', () => {
    expect(receivableStatusTone('OPEN')).toBe('warning');
    expect(receivableStatusTone('SETTLED')).toBe('success');
    expect(receivableStatusTone('VOID')).toBe('neutral');
  });

  it('来源系统要能分开「人工录入」与「从发货确认导入」', () => {
    expect(receivableSourceSystemLabel('manual')).toBe('人工录入');
    expect(receivableSourceSystemLabel('trade')).toBe('从发货确认导入');
    expect(receivableSourceSystemLabel('manual')).not.toBe(receivableSourceSystemLabel('trade'));
  });

  it('应收对象类型与账龄区间、催收级别、事件类型都有可读名', () => {
    expect(counterpartyTypeLabel('PLATFORM')).toBe('平台');
    expect(counterpartyTypeLabel('CUSTOMER')).toBe('客户');
    expect(agingBucketLabel('NOT_DUE')).toBe('未到期');
    expect(agingBucketLabel('D90_PLUS')).toBe('逾期 90 天以上');
    expect(collectionTaskLevelLabel('ESCALATION')).toBe('升级处理');
    expect(collectionTaskStatusLabel('DONE')).toBe('已完成');
    expect(receivableEventLabel('IMPORTED')).toBe('从发货确认导入');
  });
});

describe('金额只原样展示', () => {
  it('四位小数原样显示，不做单位换算', () => {
    expect(formatReceivableAmount(20)).toBe('20.0000');
    expect(formatReceivableAmount(1234.5678)).toBe('1234.5678');
  });

  it('null / undefined 显示「未登记」而不是 0', () => {
    expect(formatReceivableAmount(null)).toBe('未登记');
    expect(formatReceivableAmount(undefined)).toBe('未登记');
    expect(formatReceivableMoney(null, 'USD')).toBe('未登记');
  });

  it('金额 + 币种拼在一起', () => {
    expect(formatReceivableMoney(20, 'USD')).toBe('20.0000 USD');
    expect(formatReceivableMoney(20, null)).toBe('20.0000');
  });
});

describe('应收确认政策：未配置 ≠ 已禁用', () => {
  it('未配置说「未配置」，已禁用说「已禁用」', () => {
    expect(receivablePolicyStateLabel(policy({ configured: false, enabled: false }))).toBe(
      '未配置',
    );
    expect(receivablePolicyStateLabel(policy({ configured: true, enabled: false }))).toBe('已禁用');
    expect(
      receivablePolicyStateLabel(policy({ configured: true, enabled: true, dueDays: 30 })),
    ).toBe('已启用 · 账期 30 天');
    expect(
      receivablePolicyStateLabel(policy({ configured: true, enabled: true, dueDays: 0 })),
    ).toBe('已启用 · 确认即到期');
  });

  it('两种状态的可读形态与色调都不同', () => {
    const unconfigured = receivablePolicyStateLabel(policy({ configured: false }));
    const disabled = receivablePolicyStateLabel(policy({ configured: true, enabled: false }));
    expect(unconfigured).not.toBe(disabled);
    expect(receivablePolicyStateTone(policy({ configured: false }))).toBe('neutral');
    expect(receivablePolicyStateTone(policy({ configured: true, enabled: false }))).toBe('danger');
    expect(receivablePolicyStateTone(policy({ configured: true, enabled: true }))).toBe('success');
  });
});

describe('逾期提示', () => {
  const row = (dueDate: string, status = 'OPEN') =>
    ({
      id: 'x',
      receivableNo: 'AR-1',
      source: 'MANUAL',
      sourceSystem: 'manual',
      sourceReference: null,
      counterpartyType: 'CUSTOMER',
      counterpartyRef: null,
      counterpartyName: '客户',
      shopId: null,
      shopName: '',
      currency: 'USD',
      amount: 20,
      exchangeRate: null,
      baseCurrency: null,
      baseAmount: null,
      settledAmount: 0,
      status,
      dueDate,
      confirmedAt: '2026-10-01T00:00:00Z',
      disputeFlag: false,
      disputeNote: '',
      note: '',
      createdAt: '2026-10-01T00:00:00Z',
      updatedAt: '2026-10-01T00:00:00Z',
    }) as never;

  it('到期日未到就是「未到期」', () => {
    expect(receivableOverdueHint(row('2026-12-01'), '2026-10-23')).toBe('未到期');
  });

  it('已核销 / 已作废不再谈逾期', () => {
    expect(receivableOverdueHint(row('2026-10-01', 'SETTLED'), '2026-10-23')).toBe('—');
    expect(receivableOverdueHint(row('2026-10-01', 'VOID'), '2026-10-23')).toBe('—');
  });

  it('过期单给出到期日而不是一个算出来的天数', () => {
    expect(receivableOverdueHint(row('2026-10-01'), '2026-10-23')).toContain('2026-10-01');
  });
});
