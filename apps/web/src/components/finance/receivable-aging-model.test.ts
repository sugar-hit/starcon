import { describe, expect, it } from 'vitest';
import type { FinanceAgingBucketDef } from '../../../../../packages/api-contracts/src/index';
import {
  agingBucketLabel,
  agingBucketRange,
  agingBucketSourceLabel,
  agingDimensionLabel,
  formatAgingAmount,
  formatAgingMoney,
  formatAgingShare,
  formatOverdueDays,
} from './receivable-aging-model';

/**
 * 12.1.1.2 应收账龄前端模型的单测。
 *
 * <p>重点守住三条**界面纪律**（它们一旦被破坏，页面就会开始骗人）：
 *
 * <ol>
 *   <li>金额**原样展示**，前端不做任何业务算术；</li>
 *   <li>「没有逾期」与「占比 0」必须能分开（服务端给 null，前端显示「—」）；</li>
 *   <li>账龄区间的名称与边界取**服务端下发**的值 —— 页面不写死五档
 *       （租户改了档位，页面必须跟着变）。</li>
 * </ol>
 */

const bucket = (over: Partial<FinanceAgingBucketDef>): FinanceAgingBucketDef => ({
  code: 'D1_30',
  label: '逾期 1-30 天',
  overdueFromDays: 1,
  overdueToDays: 30,
  sortOrder: 1,
  ...over,
});

describe('下钻维度的可读名', () => {
  it('三个维度各自有可读名，且不认识的原样返回', () => {
    expect(agingDimensionLabel('SHOP')).toBe('按店铺');
    expect(agingDimensionLabel('COUNTERPARTY')).toBe('按客户');
    expect(agingDimensionLabel('CURRENCY')).toBe('按币种');
    expect(agingDimensionLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('账龄区间的可读形态', () => {
  it('名称与边界都取服务端下发的值，不在前端写死五档', () => {
    expect(agingBucketLabel(bucket({ label: '逾期 1-15 天' }))).toBe('逾期 1-15 天');
    expect(agingBucketLabel(bucket({ code: 'X', label: '自定义档' }))).toBe('自定义档');
    expect(agingBucketLabel(undefined)).toBe('—');
  });

  it('区间天数范围：无上界写「N 天起」，未到期档写「未到期」', () => {
    expect(agingBucketRange(bucket({ overdueFromDays: 1, overdueToDays: 30 }))).toBe('1-30 天');
    expect(agingBucketRange(bucket({ overdueFromDays: 46, overdueToDays: null }))).toBe('46 天起');
    expect(
      agingBucketRange(bucket({ overdueFromDays: null, overdueToDays: null, label: '未到期' })),
    ).toBe('未到期');
    expect(agingBucketRange(undefined)).toBe('—');
  });

  it('区间来源要能分开「租户自配」与「蓝图默认」', () => {
    expect(agingBucketSourceLabel('TENANT_CONFIG')).toBe('租户自配');
    expect(agingBucketSourceLabel('BLUEPRINT_DEFAULT')).toBe('蓝图默认');
    expect(agingBucketSourceLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('金额与占比的可读形态', () => {
  it('金额原样透传，只做显示格式化', () => {
    expect(formatAgingAmount(2350)).toBe('2350.0000');
    expect(formatAgingAmount(0.5)).toBe('0.5000');
    expect(formatAgingAmount(null)).toBe('—');
    expect(formatAgingAmount(undefined)).toBe('—');
  });

  it('金额 + 币种：币种不参与任何换算', () => {
    expect(formatAgingMoney(700, 'EUR')).toBe('700.0000 EUR');
    expect(formatAgingMoney(null, 'EUR')).toBe('—');
    expect(formatAgingMoney(700, null)).toBe('700.0000');
  });

  it('占比：分母为 0 时服务端给 null，页面显示「—」而不是 0.00%', () => {
    expect(formatAgingShare(0.6667)).toBe('66.67%');
    expect(formatAgingShare(null)).toBe('—');
    expect(formatAgingShare(undefined)).toBe('—');
  });
});

describe('逾期天数的可读形态', () => {
  it('逾期天数由服务端算好，页面不自己算日期差', () => {
    expect(formatOverdueDays(45)).toBe('逾期 45 天');
    expect(formatOverdueDays(0)).toBe('未到期');
    expect(formatOverdueDays(-3)).toBe('未到期');
  });
});
