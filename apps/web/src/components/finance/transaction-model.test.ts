import { describe, expect, it } from 'vitest';
import {
  TRANSACTION_TYPES,
  formatOrderReference,
  formatTransactionContribution,
  formatTransactionDate,
  formatTransactionMoney,
  reconciliationHint,
  transactionCategoryLabel,
  transactionSourceLabel,
  transactionTypeLabel,
} from './transaction-model';

/**
 * 12.3.1.2 交易明细的前端模型断言。
 *
 * <p>这几条守的是**界面纪律**，不是排版：
 *
 * <ul>
 *   <li>十类交易类型**一个都不能少**（蓝图点名的：订单销售 / 退款 / 佣金 / 仓储 / 配送 / 广告 / 赔付 / 调整 / 代扣税）；
 *   <li>金额带符号显示（正=增加结算金额、负=减少）—— 不带符号的话「退款 −100」会被读成「退款了 100 块」；
 *   <li>「没有订单号」显示成「—」，**不是**异常文案 —— 蓝图加粗句「允许无订单行的费用类明细」；
 *   <li>日历日**不做时区换算** —— `'YYYY-MM-DD'` 交给 {@code new Date()} 会被当成 UTC 午夜，
 *       在 UTC 以西的时区渲染出来就是**前一天**；
 *   <li>「平台交易报告同步」必须带着「当前不可用」的提示 —— 上游没交付，放一个能点的入口就是骗人。
 * </ul>
 */
describe('12.3.1.2 交易明细 · 前端模型', () => {
  it('十类交易类型都在词表里，且各有一个可读名', () => {
    expect(TRANSACTION_TYPES).toHaveLength(10);
    expect(TRANSACTION_TYPES).toContain('ORDER_SALES');
    expect(TRANSACTION_TYPES).toContain('REFUND');
    expect(TRANSACTION_TYPES).toContain('COMMISSION');
    expect(TRANSACTION_TYPES).toContain('STORAGE_FEE');
    expect(TRANSACTION_TYPES).toContain('SHIPPING_FEE');
    expect(TRANSACTION_TYPES).toContain('ADVERTISING');
    expect(TRANSACTION_TYPES).toContain('COMPENSATION');
    expect(TRANSACTION_TYPES).toContain('ADJUSTMENT');
    expect(TRANSACTION_TYPES).toContain('WITHHOLDING_TAX');
    for (const type of TRANSACTION_TYPES) {
      expect(transactionTypeLabel(type)).not.toBe(type);
    }
    expect(transactionTypeLabel('WITHHOLDING_TAX')).toBe('代扣税');
  });

  it('结算类别名与 12.3.1.1 同一份词表（含新加的 TAX）', () => {
    expect(transactionCategoryLabel('SALES')).toBe('销售额');
    expect(transactionCategoryLabel('TAX')).toBe('代扣税');
    expect(transactionCategoryLabel('UNKNOWN')).toBe('UNKNOWN');
  });

  it('「平台交易报告同步」带着「当前不可用」的提示', () => {
    expect(transactionSourceLabel('PLATFORM_SYNC')).toContain('当前不可用');
    expect(transactionSourceLabel('MANUAL_UPLOAD')).toBe('人工上传交易报告');
    expect(transactionSourceLabel('MANUAL')).toBe('人工登记');
  });

  it('贡献金额带符号：正数显式加 +，负数原样', () => {
    expect(formatTransactionContribution(250)).toBe('+250.0000');
    expect(formatTransactionContribution(-100)).toBe('-100.0000');
    expect(formatTransactionContribution(0)).toBe('0.0000');
    expect(formatTransactionContribution(null)).toBe('未登记');
  });

  it('金额 + 币种原样透传，null 显示「未登记」而不是 0', () => {
    expect(formatTransactionMoney(12.5, 'USD')).toBe('12.5000 USD');
    expect(formatTransactionMoney(null, 'USD')).toBe('未登记');
    expect(formatTransactionMoney(12.5, null)).toBe('12.5000');
  });

  it('日历日不做时区换算：原样显示 YYYY-MM-DD', () => {
    // ⚠️ 这一条是本仓踩过的坑（docs/backlog/2026-10/2026-10-24-finance-due-date-calendar-zone-assertion.md）：
    //   '2026-10-05' 交给 new Date() 会被当成 UTC 午夜，在 UTC 以西的时区渲染出来是 10-04。
    expect(formatTransactionDate('2026-10-05')).toBe('2026-10-05');
    expect(formatTransactionDate('2026-01-01')).toBe('2026-01-01');
    expect(formatTransactionDate(null)).toBe('未登记');
    expect(formatTransactionDate('')).toBe('未登记');
  });

  it('没有订单号显示成「—」，不是异常文案', () => {
    expect(formatOrderReference(null)).toBe('—');
    expect(formatOrderReference('')).toBe('—');
    expect(formatOrderReference('ORDER-1')).toBe('ORDER-1');
  });

  it('汇总校验结论把三个数与「是否对平」一起说清', () => {
    const hint = reconciliationHint({
      settlementId: '11111111-1111-1111-1111-111111111111',
      settlementNo: 'SETL-1',
      settlementStatus: 'PENDING',
      currency: 'USD',
      periodStart: '2026-10-01',
      periodEnd: '2026-10-15',
      lineTotal: 620,
      summaryTotal: 620,
      settlementAmount: 620,
      balanced: true,
      categories: [],
      lineCount: 7,
      orderlessLineCount: 3,
      orderlessFeeLineCount: 3,
      salesWithoutOrderLineCount: 0,
      currencyMismatchLineCount: 0,
      outsidePeriodLineCount: 0,
      signViolationLineCount: 0,
      issues: [],
      checkedAt: '2026-10-24T00:00:00Z',
    });
    expect(hint).toContain('明细合计 620.0000 USD');
    expect(hint).toContain('汇总合计 620.0000 USD');
    expect(hint).toContain('结算金额 620.0000 USD');
    expect(hint).toContain('三者相等');

    const broken = reconciliationHint({
      settlementId: '11111111-1111-1111-1111-111111111111',
      settlementNo: 'SETL-1',
      settlementStatus: 'PENDING',
      currency: 'USD',
      periodStart: '2026-10-01',
      periodEnd: '2026-10-15',
      lineTotal: 620,
      summaryTotal: 600,
      settlementAmount: 600,
      balanced: false,
      categories: [],
      lineCount: 7,
      orderlessLineCount: 3,
      orderlessFeeLineCount: 3,
      salesWithoutOrderLineCount: 0,
      currencyMismatchLineCount: 0,
      outsidePeriodLineCount: 0,
      signViolationLineCount: 0,
      issues: [],
      checkedAt: '2026-10-24T00:00:00Z',
    });
    expect(broken).toContain('不相等');
  });
});
