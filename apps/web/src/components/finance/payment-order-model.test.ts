import { describe, expect, it } from 'vitest';
import type {
  FinancePaymentExchangeRateView,
  FinancePaymentPayableLineOption,
} from '../../../../../packages/api-contracts/src/index';
import {
  approvalActionLabel,
  buildPaymentOrderInput,
  formatPaymentOrderAmount,
  formatPaymentOrderMoney,
  installmentStatusLabel,
  paymentAccountLabel,
  paymentApprovalStatusLabel,
  paymentApprovalStatusTone,
  paymentCounterpartyTypeLabel,
  paymentExecutionLabel,
  paymentExchangeRateHint,
  paymentMethodLabel,
  paymentOrderEventLabel,
  paymentOrderStatusLabel,
  paymentOrderStatusTone,
  payableLineBlockedReason,
  payableLineSelectable,
} from './payment-order-model';

/**
 * 12.2.2.1 付款单与审批前端模型的单测。
 *
 * <p>重点守住四条**界面纪律**（它们一旦被破坏，页面就会开始骗人）：
 *
 * <ol>
 *   <li>金额**原样展示**，前端不做任何业务算术；</li>
 *   <li>「已批」**不等于**「已付」—— 页面绝不能把它渲染成成功态（否则会被读成钱已经付了）；</li>
 *   <li>付款执行与资金账户**如实显示未交付**（归属模块写出来，不假装可用）；</li>
 *   <li>推导出来的汇率**必须让人看见**（上游只登记反方向时，我们取倒数）。</li>
 * </ol>
 */

const line = (over: Partial<FinancePaymentPayableLineOption>): FinancePaymentPayableLineOption => ({
  payableLineId: '11111111-1111-1111-1111-111111111111',
  payableId: '22222222-2222-2222-2222-222222222222',
  payableNo: 'AP-ABC',
  lineNo: 1,
  description: '第一批货',
  skuCode: 'SKU-1',
  currency: 'USD',
  amount: 100,
  paidAmount: 0,
  unpaidAmount: 100,
  dueDate: '2026-10-31',
  counterpartyName: '测试供应商',
  lockedByOrderId: null,
  lockedByOrderNo: null,
  ...over,
});

describe('付款单状态的可读名与色调', () => {
  it('六态各有可读名，且不认识的原样返回', () => {
    expect(paymentOrderStatusLabel('DRAFT')).toBe('草稿');
    expect(paymentOrderStatusLabel('PENDING_APPROVAL')).toBe('待审');
    expect(paymentOrderStatusLabel('APPROVED')).toBe('已批');
    expect(paymentOrderStatusLabel('EXECUTING')).toBe('执行中');
    expect(paymentOrderStatusLabel('PAID')).toBe('已付');
    expect(paymentOrderStatusLabel('FAILED')).toBe('失败');
    expect(paymentOrderStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('🔴 「已批」不是成功态 —— 它只表示审批通过了，钱还没付出去', () => {
    expect(paymentOrderStatusTone('APPROVED')).not.toBe('success');
    expect(paymentOrderStatusTone('PENDING_APPROVAL')).not.toBe('success');
    expect(paymentOrderStatusTone('EXECUTING')).not.toBe('success');
    // 只有真的付出去了才是成功态。
    expect(paymentOrderStatusTone('PAID')).toBe('success');
    expect(paymentOrderStatusTone('FAILED')).toBe('danger');
    expect(paymentOrderStatusTone('DRAFT')).toBe('neutral');
  });
});

describe('审批状态的可读名与色调', () => {
  it('四态各有可读名与色调', () => {
    expect(paymentApprovalStatusLabel('NOT_SUBMITTED')).toBe('未提交');
    expect(paymentApprovalStatusLabel('PENDING')).toBe('待审');
    expect(paymentApprovalStatusLabel('APPROVED')).toBe('已批');
    expect(paymentApprovalStatusLabel('REJECTED')).toBe('已驳回');
    expect(paymentApprovalStatusTone('PENDING')).toBe('warning');
    expect(paymentApprovalStatusTone('REJECTED')).toBe('danger');
    expect(paymentApprovalStatusTone('NOT_SUBMITTED')).toBe('neutral');
  });
});

describe('付款方式与付款对象', () => {
  it('付款方式各有可读名', () => {
    expect(paymentMethodLabel('BANK_TRANSFER')).toBe('银行电汇');
    expect(paymentMethodLabel('PAYPAL')).toBe('PayPal');
    expect(paymentMethodLabel('STRIPE')).toBe('Stripe');
    expect(paymentMethodLabel('PINGPONG')).toBe('PingPong');
    expect(paymentMethodLabel('OTHER')).toBe('其他');
  });

  it('付款对象类型各有可读名', () => {
    expect(paymentCounterpartyTypeLabel('SUPPLIER')).toBe('供应商');
    expect(paymentCounterpartyTypeLabel('CARRIER')).toBe('承运商');
    expect(paymentCounterpartyTypeLabel('PLATFORM')).toBe('平台');
    expect(paymentCounterpartyTypeLabel('OTHER')).toBe('其他');
  });
});

describe('未交付能力的如实显示', () => {
  it('付款执行恒为不可用，且写明归属模块', () => {
    expect(paymentExecutionLabel(false, '12.2.2.2')).toContain('12.2.2.2');
    expect(paymentExecutionLabel(false, '12.2.2.2')).toContain('不可用');
  });

  it('付款账户只登记引用与名称，归属写明 12.7.1.1', () => {
    expect(paymentAccountLabel('', '12.7.1.1')).toBe('未指定付款账户');
    expect(paymentAccountLabel('招商银行香港', '12.7.1.1')).toContain('招商银行香港');
    expect(paymentAccountLabel('招商银行香港', '12.7.1.1')).toContain('12.7.1.1');
  });
});

describe('金额只原样展示', () => {
  it('四位小数原样透传，缺失显示为破折号', () => {
    expect(formatPaymentOrderAmount(12.5)).toBe('12.5000');
    expect(formatPaymentOrderAmount(0)).toBe('0.0000');
    expect(formatPaymentOrderAmount(null)).toBe('—');
    expect(formatPaymentOrderAmount(undefined)).toBe('—');
  });

  it('金额 + 币种，币种缺失时也不留尾巴', () => {
    expect(formatPaymentOrderMoney(100, 'USD')).toBe('100.0000 USD');
    expect(formatPaymentOrderMoney(100, null)).toBe('100.0000');
    expect(formatPaymentOrderMoney(null, 'USD')).toBe('—');
  });
});

describe('汇率快照的如实显示', () => {
  const rate = (over: Partial<FinancePaymentExchangeRateView>): FinancePaymentExchangeRateView => ({
    baseCurrency: 'USD',
    quoteCurrency: 'CNY',
    rate: 7.1234,
    effectiveDate: '2026-10-20',
    sourceCode: 'MANUAL',
    sourceName: '手工录入',
    inverted: false,
    display: '1 USD = 7.1234 CNY',
    ...over,
  });

  it('直接登记的汇率正常显示', () => {
    expect(paymentExchangeRateHint(rate({}))).toBe('1 USD = 7.1234 CNY');
    expect(paymentExchangeRateHint(rate({}))).not.toContain('推导');
  });

  it('🔴 推导出来的汇率必须标出来', () => {
    const inverted = rate({ inverted: true, rate: 7.14285714 });
    expect(paymentExchangeRateHint(inverted)).toContain('推导');
  });
});

describe('可选应付明细', () => {
  it('未被占用且有余额 ⇒ 可选', () => {
    expect(payableLineSelectable(line({}))).toBe(true);
    expect(payableLineBlockedReason(line({}))).toBe('');
  });

  it('已被在册付款单占用 ⇒ 不可选，并说清被谁占了', () => {
    const locked = line({
      lockedByOrderId: '33333333-3333-3333-3333-333333333333',
      lockedByOrderNo: 'PO-ABC',
    });
    expect(payableLineSelectable(locked)).toBe(false);
    expect(payableLineBlockedReason(locked)).toContain('PO-ABC');
  });

  it('未付余额为 0 ⇒ 不可选', () => {
    const paid = line({ unpaidAmount: 0, paidAmount: 100 });
    expect(payableLineSelectable(paid)).toBe(false);
    expect(payableLineBlockedReason(paid)).toBe('未付余额为 0');
  });
});

describe('事件与审批动作的可读名', () => {
  it('事件类型各有可读名', () => {
    expect(paymentOrderEventLabel('CREATED')).toBe('建单');
    expect(paymentOrderEventLabel('SUBMITTED')).toBe('提交审批');
    expect(paymentOrderEventLabel('APPROVED')).toBe('审批通过');
    expect(paymentOrderEventLabel('RETURNED_TO_DRAFT')).toBe('退回草稿');
    expect(paymentOrderEventLabel('UNKNOWN')).toBe('UNKNOWN');
  });

  it('审批动作各有可读名', () => {
    expect(approvalActionLabel('SUBMIT')).toBe('提交审批');
    expect(approvalActionLabel('APPROVE')).toBe('审批通过');
    expect(approvalActionLabel('REJECT')).toBe('审批驳回');
    expect(approvalActionLabel('TRANSFER')).toBe('转交');
  });

  it('分批状态各有可读名', () => {
    expect(installmentStatusLabel('PLANNED')).toBe('已排期');
    expect(installmentStatusLabel('EXECUTING')).toBe('执行中');
    expect(installmentStatusLabel('PAID')).toBe('已付');
    expect(installmentStatusLabel('FAILED')).toBe('失败');
  });
});

describe('付款单入参的构造', () => {
  it('明细与分批被规整成契约形状；没有分批时 installments 为 null', () => {
    const input = buildPaymentOrderInput({
      counterpartyType: 'SUPPLIER',
      counterpartyRef: null,
      counterpartyName: '测试供应商',
      currency: 'USD',
      amount: '100',
      baseCurrency: null,
      exchangeRate: null,
      paymentMethod: 'BANK_TRANSFER',
      paymentAccountId: null,
      paymentAccountName: null,
      paymentAccountCurrency: null,
      paymentPlanId: null,
      note: null,
      lines: [{ payableLineId: 'l1', description: null, currency: 'USD', amount: '100' }],
      installments: [],
    });
    expect(input.amount).toBe(100);
    expect(input.lines).toHaveLength(1);
    expect(input.lines[0]?.payableLineId).toBe('l1');
    expect(input.installments).toBeNull();
  });

  it('有分批时按分批原样带上', () => {
    const input = buildPaymentOrderInput({
      counterpartyType: 'SUPPLIER',
      counterpartyRef: null,
      counterpartyName: null,
      currency: 'USD',
      amount: '100',
      baseCurrency: 'CNY',
      exchangeRate: '7.1234',
      paymentMethod: 'BANK_TRANSFER',
      paymentAccountId: null,
      paymentAccountName: null,
      paymentAccountCurrency: null,
      paymentPlanId: null,
      note: null,
      lines: [{ payableLineId: 'l1', description: null, currency: 'USD', amount: '100' }],
      installments: [{ plannedPaidDate: '2026-10-31', currency: 'USD', amount: '100' }],
    });
    expect(input.baseCurrency).toBe('CNY');
    expect(input.exchangeRate).toBe(7.1234);
    expect(input.installments).toHaveLength(1);
    expect(input.installments?.[0]?.plannedPaidDate).toBe('2026-10-31');
  });
});
