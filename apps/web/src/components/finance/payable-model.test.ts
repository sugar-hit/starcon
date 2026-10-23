import { describe, expect, it } from 'vitest';
import type { FinancePayablePolicyView } from '../../../../../packages/api-contracts/src/index';
import {
  counterpartyTypeLabel,
  formatPayableAmount,
  formatPayableMoney,
  obligationKindLabel,
  payableEventLabel,
  payableOverdueHint,
  payablePolicyStateLabel,
  payablePolicyStateTone,
  payableSourceLabel,
  payableSourceProducible,
  payableSourceSystemLabel,
  payableStatusLabel,
  payableStatusTone,
  prepaymentStatusLabel,
  prepaymentStatusTone,
} from './payable-model';

/**
 * 12.1.2.1 应付单管理前端模型的单测。
 *
 * <p>重点守住四条**界面纪律**（它们一旦被破坏，页面就会开始骗人）：
 *
 * <ol>
 *   <li>「未配置」与「已禁用」必须能被分开看；</li>
 *   <li>金额**原样展示**，前端不做任何算术；</li>
 *   <li>「未付」与「部分已付」不被合成同一个词；</li>
 *   <li>上游未交付的来源（供应商对账单 / 平台费用）**不得**被当成可产来源。</li>
 * </ol>
 */

const policy = (over: Partial<FinancePayablePolicyView>): FinancePayablePolicyView => ({
  source: 'MANUAL',
  sourceLabel: '人工录入',
  obligationKind: 'PAYABLE',
  obligationKindLabel: '应付',
  configured: false,
  enabled: false,
  dueDays: 0,
  note: '',
  ownerModule: '',
  producible: true,
  detail: '',
  ...over,
});

describe('应付来源与状态的可读形态', () => {
  it('七个来源各自有可读名，且不认识的原样返回', () => {
    expect(payableSourceLabel('PURCHASE_RECEIPT')).toBe('采购收货');
    expect(payableSourceLabel('LOGISTICS_FEE')).toBe('物流费用');
    expect(payableSourceLabel('ADVERTISING_FEE')).toBe('广告费');
    expect(payableSourceLabel('SUPPLIER_STATEMENT')).toBe('供应商对账单');
    expect(payableSourceLabel('PLATFORM_FEE')).toBe('平台费用');
    expect(payableSourceLabel('MANUAL')).toBe('人工录入');
    expect(payableSourceLabel('OTHER')).toBe('其他');
    expect(payableSourceLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('「未付」与「部分已付」不被合成同一个词', () => {
    expect(payableStatusLabel('OPEN')).toBe('未付');
    expect(payableStatusLabel('PARTIALLY_PAID')).toBe('部分已付');
    expect(payableStatusLabel('PAID')).toBe('已付清');
    expect(payableStatusLabel('VOID')).toBe('已作废');
    expect(payableStatusLabel('OPEN')).not.toBe(payableStatusLabel('PARTIALLY_PAID'));
  });

  it('义务类型三类分开可读（应付 / 预付款 / 采购承诺）', () => {
    expect(obligationKindLabel('PAYABLE')).toBe('应付');
    expect(obligationKindLabel('PREPAYMENT')).toBe('预付款');
    expect(obligationKindLabel('COMMITMENT')).toBe('采购承诺');
  });

  it('应付对象四类分开可读', () => {
    expect(counterpartyTypeLabel('SUPPLIER')).toBe('供应商');
    expect(counterpartyTypeLabel('CARRIER')).toBe('承运商');
    expect(counterpartyTypeLabel('PLATFORM')).toBe('平台');
    expect(counterpartyTypeLabel('OTHER')).toBe('其他');
  });

  it('状态色调：未付是提醒色、已付清是成功色、作废是中性色', () => {
    expect(payableStatusTone('OPEN')).toBe('warning');
    expect(payableStatusTone('PARTIALLY_PAID')).toBe('warning');
    expect(payableStatusTone('PAID')).toBe('success');
    expect(payableStatusTone('VOID')).toBe('neutral');
  });

  it('预付款状态与色调', () => {
    expect(prepaymentStatusLabel('OPEN')).toBe('未抵扣');
    expect(prepaymentStatusLabel('PARTIALLY_ALLOCATED')).toBe('部分抵扣');
    expect(prepaymentStatusLabel('ALLOCATED')).toBe('已抵扣完');
    expect(prepaymentStatusLabel('VOID')).toBe('已作废');
    expect(prepaymentStatusTone('ALLOCATED')).toBe('success');
    expect(prepaymentStatusTone('OPEN')).toBe('warning');
  });
});

describe('确认政策的三态', () => {
  it('「未配置」与「已禁用」用不同文案', () => {
    expect(payablePolicyStateLabel(policy({ configured: false }))).toBe('未配置');
    expect(payablePolicyStateLabel(policy({ configured: true, enabled: false }))).toBe('已禁用');
    expect(
      payablePolicyStateLabel(policy({ configured: true, enabled: true, dueDays: 30 })),
    ).toContain('30 天');
  });

  it('采购承诺在本服务不建模 —— 不给「已启用」这种文案', () => {
    const commitment = policy({
      source: 'MANUAL',
      obligationKind: 'COMMITMENT',
      obligationKindLabel: '采购承诺',
      ownerModule: '6.3.1.1',
      producible: false,
    });
    expect(payablePolicyStateLabel(commitment)).toBe('本服务不建模');
    expect(payablePolicyStateTone(commitment)).toBe('neutral');
  });

  it('上游未交付的来源即便配置了也是「警示」而不是「成功」', () => {
    const statement = policy({
      source: 'SUPPLIER_STATEMENT',
      producible: false,
      configured: true,
      enabled: true,
      ownerModule: '12.1.2.3',
    });
    expect(payablePolicyStateTone(statement)).toBe('warning');
  });
});

describe('来源可产性', () => {
  it('采购收货 / 物流费用 / 广告费 / 人工录入 / 其他可产', () => {
    expect(payableSourceProducible('PURCHASE_RECEIPT')).toBe(true);
    expect(payableSourceProducible('LOGISTICS_FEE')).toBe(true);
    expect(payableSourceProducible('ADVERTISING_FEE')).toBe(true);
    expect(payableSourceProducible('MANUAL')).toBe(true);
    expect(payableSourceProducible('OTHER')).toBe(true);
  });

  it('供应商对账单与平台费用当前不可产（上游未交付）', () => {
    expect(payableSourceProducible('SUPPLIER_STATEMENT')).toBe(false);
    expect(payableSourceProducible('PLATFORM_FEE')).toBe(false);
  });
});

describe('金额原样展示', () => {
  it('四位小数原样透传，不做前端算术', () => {
    expect(formatPayableAmount(20)).toBe('20.0000');
    expect(formatPayableAmount(12.5)).toBe('12.5000');
    expect(formatPayableAmount(0)).toBe('0.0000');
  });

  it('null 显示「未登记」而不是 0', () => {
    expect(formatPayableAmount(null)).toBe('未登记');
    expect(formatPayableAmount(undefined)).toBe('未登记');
    expect(formatPayableMoney(null, 'USD')).toBe('未登记');
  });

  it('金额 + 币种', () => {
    expect(formatPayableMoney(88.8, 'USD')).toBe('88.8000 USD');
    expect(formatPayableMoney(88.8, null)).toBe('88.8000');
  });
});

describe('来源系统与事件的可读形态', () => {
  it('谁产出了这张应付必须能分开看', () => {
    expect(payableSourceSystemLabel('manual')).toBe('人工录入');
    expect(payableSourceSystemLabel('trade')).toBe('从到货应付确认导入');
    expect(payableSourceSystemLabel('warehouse')).toBe('从头程发运单导入');
    expect(payableSourceSystemLabel('channel')).toBe('从广告花费导入');
    expect(payableSourceSystemLabel('other')).toBe('other');
  });

  it('事件类型可读', () => {
    expect(payableEventLabel('CREATED')).toBe('建单');
    expect(payableEventLabel('CONFIRMED')).toBe('确认');
    expect(payableEventLabel('IMPORTED')).toBe('从上游导入');
    expect(payableEventLabel('VOIDED')).toBe('作废');
  });
});

describe('逾期提示', () => {
  const row = (over: Partial<{ status: string; dueDate: string }>) =>
    ({
      id: 'id',
      payableNo: 'AP-1',
      source: 'MANUAL',
      sourceSystem: 'manual',
      sourceReference: null,
      counterpartyType: 'SUPPLIER',
      counterpartyRef: null,
      counterpartyName: '供应商甲',
      currency: 'USD',
      amount: 100,
      exchangeRate: null,
      baseCurrency: null,
      baseAmount: null,
      paidAmount: 0,
      unpaidAmount: 100,
      status: 'OPEN',
      dueDate: '2026-01-01',
      confirmedAt: '2026-01-01T00:00:00Z',
      note: '',
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
      ...over,
    }) as never;

  it('已付清与已作废不提示逾期', () => {
    expect(payableOverdueHint(row({ status: 'PAID' }), '2026-02-01')).toBe('—');
    expect(payableOverdueHint(row({ status: 'VOID' }), '2026-02-01')).toBe('—');
  });

  it('未到期与逾期中分开', () => {
    expect(payableOverdueHint(row({ dueDate: '2026-03-01' }), '2026-02-01')).toBe('未到期');
    expect(payableOverdueHint(row({ dueDate: '2026-01-01' }), '2026-02-01')).toContain('逾期中');
  });
});
