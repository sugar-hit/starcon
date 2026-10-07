import { expect, it } from 'vitest';
import {
  MONTH_LABELS,
  accountingRuleSummary,
  baseCurrencySummary,
  bookTypeLabel,
  firstPeriodAligned,
  firstPeriodStart,
  fiscalYearLabel,
  periodSettingSummary,
  periodTypeLabel,
  roundingLabel,
  utcCalendarDate,
} from './accounting-book-model';

it('期间边界按 UTC 日历呈现：同一账簿在任何时区打开都是同一个期界', () => {
  // 09:30 +08:00 == 01:30Z，UTC 日历日期仍是 1 月 1 日
  expect(firstPeriodStart('2026-01-01T09:30:00+08:00')).toBe('2026-01-01');
  expect(utcCalendarDate('2026-04-01T00:00:00Z')).toBe('2026-04-01');
  // 坏时间戳退化成占位符，不让整行渲染成 NaN
  expect(utcCalendarDate('not-a-date')).toBe('—');
  expect(utcCalendarDate(null)).toBe('—');
  expect(firstPeriodStart(undefined)).toBe('—');
});

it('枚举标签有中文口径，未知取值原样透出而不是静默变成空白', () => {
  expect(roundingLabel('HALF_EVEN')).toBe('银行家舍入');
  expect(bookTypeLabel('BANK')).toBe('银行账簿');
  expect(periodTypeLabel('QUARTER')).toBe('季度');
  expect(roundingLabel('WHATEVER')).toBe('WHATEVER');
  expect(MONTH_LABELS).toHaveLength(12);
});

it('记账规则把金额与数量分两套量纲报，不合成一句含糊的话', () => {
  const summary = accountingRuleSummary({
    amountScale: 2,
    amountRounding: 'HALF_UP',
    quantityScale: 6,
    quantityRounding: 'DOWN',
  });
  expect(summary).toBe('金额 2 位 · 四舍五入 / 数量 6 位 · 向下取整');
});

it('期间设置摘要：周度不按月切分，起始月对它没有意义', () => {
  expect(periodSettingSummary({ periodType: 'MONTH', fiscalYearStartMonth: 4 })).toBe(
    '月度 · 起始 4 月',
  );
  expect(periodSettingSummary({ periodType: 'QUARTER', fiscalYearStartMonth: 1 })).toBe(
    '季度 · 起始 1 月',
  );
  expect(periodSettingSummary({ periodType: 'WEEK', fiscalYearStartMonth: 4 })).toBe(
    '周度（不按月切分）',
  );
});

it('本位币未对齐法人时显式示警，不能照常显示一个币种代码', () => {
  expect(
    baseCurrencySummary({ baseCurrency: 'USD', legalEntityCurrency: 'USD', currencyAligned: true }),
  ).toBe('USD');
  expect(
    baseCurrencySummary({
      baseCurrency: 'USD',
      legalEntityCurrency: 'EUR',
      currencyAligned: false,
    }),
  ).toBe('USD（法人本位币为 EUR，需同步）');
});

it('会计年度区间缺任一段就明说未知，不拼出半截区间', () => {
  expect(fiscalYearLabel('2026', '2026-04-01', '2027-03-31')).toBe(
    '2026 财年（2026-04-01 ~ 2027-03-31）',
  );
  expect(fiscalYearLabel('2026', '', '2027-03-31')).toBe('会计年度未知');
  expect(fiscalYearLabel(null, null, null)).toBe('会计年度未知');
});

it('首期对齐预校验与后端同一口径：月度看 12、季度看 3、周度不校验', () => {
  // 月度起始月 4 → 4 月首期对齐，3 月不对齐
  expect(firstPeriodAligned('MONTH', 4, '2026-04-01T00:00:00Z')).toBe(true);
  expect(firstPeriodAligned('MONTH', 4, '2026-03-01T00:00:00Z')).toBe(false);
  // 跨年回绕：起始月 11，2 月首期同样不对齐
  expect(firstPeriodAligned('MONTH', 11, '2026-02-01T00:00:00Z')).toBe(false);
  expect(firstPeriodAligned('MONTH', 11, '2026-11-01T00:00:00Z')).toBe(true);
  // 季度看 3：起始月 4 时 4/7/10 月对齐，6 月不对齐
  expect(firstPeriodAligned('QUARTER', 4, '2026-07-01T00:00:00Z')).toBe(true);
  expect(firstPeriodAligned('QUARTER', 4, '2026-06-01T00:00:00Z')).toBe(false);
  // 周度不对齐月份，一律放行
  expect(firstPeriodAligned('WEEK', 4, '2026-03-11T00:00:00Z')).toBe(true);
  // 坏输入不许静默通过
  expect(firstPeriodAligned('MONTH', 1, 'garbage')).toBe(false);
});
