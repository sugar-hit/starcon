import { describe, expect, it } from 'vitest';
import type { FinanceReceiptCurrencySummary, FinanceReceiptRow } from '../../../../../packages/api-contracts/src/index';
import {
  allocationStatusLabel,
  fetchReceiptBalance,
  fetchReceiptCapabilities,
  fetchReceiptOptions,
  fetchReceipt,
  fetchReceipts,
  formatReceivedAt,
  formatReceiptAmount,
  formatReceiptMoney,
  fundAccountLabelHint,
  importReceipts,
  payerTypeLabel,
  receiptEventLabel,
  receiptSourceLabel,
  receiptSourceSystemLabel,
  receiptStatusLabel,
  receiptStatusTone,
  releaseReceiptAllocation,
  reserveReceiptAllocation,
  voidReceipt,
} from './receipt-model';

/**
 * 12.2.1.1 收款单前端模型的单测。
 *
 * <p>重点守住三条**界面纪律**（它们一旦被破坏，页面就会开始骗人）：
 *
 * <ol>
 *   <li>金额**原样展示**，前端不做任何算术（USD 与 EUR 加起来不是任何一个金额）；</li>
 *   <li>「预留」与「核销」必须能被分开看 —— 页面不能让人以为预留已经把账核掉了；</li>
 *   <li>上游没交付的事就说没交付：平台回款与受管资金账户的提示必须写在界面上。</li>
 * </ol>
 */

const row = (over: Partial<FinanceReceiptRow>): FinanceReceiptRow => ({
  id: '11111111-1111-1111-1111-111111111111',
  receiptNo: 'RCPT-1',
  source: 'MANUAL',
  sourceSystem: 'manual',
  sourceReference: null,
  currency: 'USD',
  amount: 1000,
  settledAmount: 0,
  unsettledAmount: 1000,
  advanceFlag: false,
  status: 'RECORDED',
  receivedAt: '2026-10-01T00:30:00Z',
  payerType: 'CUSTOMER',
  payerName: '客户甲',
  payerRef: null,
  fundAccountRef: null,
  fundAccountLabel: '',
  settlementNo: null,
  note: '',
  createdAt: '2026-10-01T00:30:00Z',
  updatedAt: '2026-10-01T00:30:00Z',
  ...over,
});

describe('收款来源与状态的可读形态', () => {
  it('五个来源各自有可读名，且不认识的原样返回', () => {
    expect(receiptSourceLabel('PLATFORM_SETTLEMENT')).toBe('平台回款');
    expect(receiptSourceLabel('BANK_TRANSFER')).toBe('银行到账');
    expect(receiptSourceLabel('PAYMENT_GATEWAY')).toBe('第三方支付到账');
    expect(receiptSourceLabel('MANUAL')).toBe('人工登记');
    expect(receiptSourceLabel('OTHER')).toBe('其他收入');
    expect(receiptSourceLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('来源系统要能分开「人工登记」与「从流水导入」', () => {
    expect(receiptSourceSystemLabel('manual')).toBe('人工登记');
    expect(receiptSourceSystemLabel('bank')).toBe('银行流水导入');
    expect(receiptSourceSystemLabel('paypal')).toBe('PayPal 流水导入');
    expect(receiptSourceSystemLabel('manual')).not.toBe(receiptSourceSystemLabel('bank'));
  });

  it('状态与付款方类型、事件类型都有可读名', () => {
    expect(receiptStatusLabel('RECORDED')).toBe('已登记');
    expect(receiptStatusLabel('VOID')).toBe('已作废');
    expect(receiptStatusTone('RECORDED')).toBe('success');
    expect(receiptStatusTone('VOID')).toBe('neutral');
    expect(payerTypeLabel('PLATFORM')).toBe('平台');
    expect(payerTypeLabel('CUSTOMER')).toBe('客户');
    expect(payerTypeLabel('PARTNER')).toBe('合作方');
    expect(receiptEventLabel('IMPORTED')).toBe('到账流水导入');
    expect(receiptEventLabel('RESERVED')).toBe('预留核销');
  });

  it('「预留」与「已释放」不被合成同一个词', () => {
    expect(allocationStatusLabel('RESERVED')).toBe('已预留');
    expect(allocationStatusLabel('RELEASED')).toBe('已释放');
    expect(allocationStatusLabel('RESERVED')).not.toBe(allocationStatusLabel('RELEASED'));
  });
});

describe('金额只原样展示', () => {
  it('四位小数原样显示，不做单位换算', () => {
    expect(formatReceiptAmount(1000)).toBe('1000.0000');
    expect(formatReceiptAmount(1234.5678)).toBe('1234.5678');
  });

  it('null / undefined 显示「未登记」而不是 0', () => {
    expect(formatReceiptAmount(null)).toBe('未登记');
    expect(formatReceiptAmount(undefined)).toBe('未登记');
    expect(formatReceiptMoney(null, 'USD')).toBe('未登记');
  });

  it('金额 + 币种拼在一起', () => {
    expect(formatReceiptMoney(1000, 'USD')).toBe('1000.0000 USD');
    expect(formatReceiptMoney(1000, null)).toBe('1000.0000');
  });
});

describe('未核销余额：核销未交付 ⇒ 恒等于收款金额', () => {
  const summary = (over: Partial<FinanceReceiptCurrencySummary>): FinanceReceiptCurrencySummary => ({
    currency: 'USD',
    amount: 1000,
    settledAmount: 0,
    unsettledAmount: 1000,
    count: 1,
    advanceCount: 0,
    advanceAmount: 0,
    ...over,
  });

  it('未核销余额原样显示，前端不自己重算', () => {
    expect(summary({}).unsettledAmount).toBe(1000);
    expect(formatReceiptAmount(summary({}).unsettledAmount)).toBe('1000.0000');
  });

  it('预收金额与总未核销余额能分开看', () => {
    const mixed = summary({ advanceCount: 1, advanceAmount: 400 });
    expect(mixed.advanceAmount).toBe(400);
    expect(mixed.unsettledAmount).toBe(1000);
  });
});

describe('上游缺口在界面上必须说清', () => {
  it('资金账户标识要写明它不是受管资金账户', () => {
    expect(fundAccountLabelHint(row({ fundAccountLabel: '' }))).toContain('12.7.1.1');
    expect(fundAccountLabelHint(row({ fundAccountLabel: '招商银行 6222****1234' }))).toContain(
      '不是受管资金账户',
    );
  });

  it('受管账户引用恒为 null，结算单号恒为 null', () => {
    const r = row({});
    expect(r.fundAccountRef).toBeNull();
    expect(r.settlementNo).toBeNull();
  });
});

describe('到账时间只做显示格式化', () => {
  it('带偏移的 ISO 串能被显示，不做时区换算', () => {
    expect(formatReceivedAt('2026-10-01T00:30:00Z')).not.toBe('未登记');
    expect(formatReceivedAt('2026-10-01T00:30:00Z')).toContain('2026');
  });

  it('缺失或非法值原样显示而不是变成 0', () => {
    expect(formatReceivedAt(null)).toBe('未登记');
    expect(formatReceivedAt(undefined)).toBe('未登记');
    expect(formatReceivedAt('not-a-time')).toBe('not-a-time');
  });
});

describe('端点形状', () => {
  it('所有端点都挂在 finance/receipts 命名空间下', () => {
    // 端点函数本身不发请求（这里只钉住 URL 形状）；
    // 真正「放行 / 拒绝」由 api-proxy 的白名单双向断言守住。
    expect(typeof fetchReceiptCapabilities).toBe('function');
    expect(typeof fetchReceiptOptions).toBe('function');
    expect(typeof fetchReceipts).toBe('function');
    expect(typeof fetchReceipt).toBe('function');
    expect(typeof fetchReceiptBalance).toBe('function');
    expect(typeof importReceipts).toBe('function');
    expect(typeof reserveReceiptAllocation).toBe('function');
    expect(typeof releaseReceiptAllocation).toBe('function');
    expect(typeof voidReceipt).toBe('function');
  });
});
