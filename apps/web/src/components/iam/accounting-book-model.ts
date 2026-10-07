/**
 * 17.1.2.2 本位币与账簿的展示口径。
 *
 * 期间边界按 **UTC 日历**定义（后端校验与推导都走 UTC，见 V43 与 AccountingBookController），
 * 所以首期起点展示的是它的 UTC 日期而不是浏览器本地日期：同一个账簿在上海和洛杉矶
 * 打开必须看到同一个期界，否则"本月"这个词在跨时区协作时就没有唯一答案。
 */

export type Rounding = 'HALF_UP' | 'HALF_EVEN' | 'DOWN' | 'UP';
export type BookType = 'BOOK' | 'CASH' | 'BANK';
export type PeriodType = 'MONTH' | 'WEEK' | 'QUARTER';

export const ROUNDING_LABELS: Record<string, string> = {
  HALF_UP: '四舍五入',
  HALF_EVEN: '银行家舍入',
  DOWN: '向下取整',
  UP: '向上取整',
};

export const BOOK_TYPE_LABELS: Record<string, string> = {
  BOOK: '总账',
  CASH: '现金账簿',
  BANK: '银行账簿',
};

export const PERIOD_TYPE_LABELS: Record<string, string> = {
  MONTH: '月度',
  WEEK: '周度',
  QUARTER: '季度',
};

export const MONTH_LABELS: readonly string[] = [
  '1 月',
  '2 月',
  '3 月',
  '4 月',
  '5 月',
  '6 月',
  '7 月',
  '8 月',
  '9 月',
  '10 月',
  '11 月',
  '12 月',
];

/** 取 UTC 日历日期；解析不了就显示占位符，不让一个坏时间戳把整行渲染成 NaN。 */
export function utcCalendarDate(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? '—' : parsed.toISOString().slice(0, 10);
}

/** 首期起点：带时刻的瞬时按 UTC 截到日期。 */
export function firstPeriodStart(value: string | null | undefined): string {
  return utcCalendarDate(value);
}

/** 当前会计年度区间，例如「2026 财年（2026-04-01 ~ 2027-03-31）」。 */
export function fiscalYearLabel(
  year: string | null | undefined,
  start: string | null | undefined,
  end: string | null | undefined,
): string {
  if (!year || !start || !end) return '会计年度未知';
  return `${year} 财年（${start} ~ ${end}）`;
}

/** 记账规则摘要：金额与数量是两套量纲，必须分别报，不能合成一句。 */
export function accountingRuleSummary(book: {
  amountScale: number;
  amountRounding: string;
  quantityScale: number;
  quantityRounding: string;
}): string {
  const amount = `金额 ${book.amountScale} 位 · ${roundingLabel(book.amountRounding)}`;
  const quantity = `数量 ${book.quantityScale} 位 · ${roundingLabel(book.quantityRounding)}`;
  return `${amount} / ${quantity}`;
}

/** 期间设置摘要。 */
export function periodSettingSummary(book: {
  periodType: string;
  fiscalYearStartMonth: number;
}): string {
  const month = MONTH_LABELS[book.fiscalYearStartMonth - 1] ?? `${book.fiscalYearStartMonth} 月`;
  // 周粒度不对齐月份，起始月在这里没有含义，展示出来只会误导。
  if (book.periodType === 'WEEK') return `${periodTypeLabel(book.periodType)}（不按月切分）`;
  return `${periodTypeLabel(book.periodType)} · 起始 ${month}`;
}

/** 本位币行：币种与法人不一致时必须显式示警，而不是照常显示一个币种代码。 */
export function baseCurrencySummary(book: {
  baseCurrency: string;
  legalEntityCurrency: string;
  currencyAligned: boolean;
}): string {
  return book.currencyAligned
    ? book.baseCurrency
    : `${book.baseCurrency}（法人本位币为 ${book.legalEntityCurrency}，需同步）`;
}

export function roundingLabel(value: string): string {
  return ROUNDING_LABELS[value] ?? value;
}

export function bookTypeLabel(value: string): string {
  return BOOK_TYPE_LABELS[value] ?? value;
}

export function periodTypeLabel(value: string): string {
  return PERIOD_TYPE_LABELS[value] ?? value;
}

/**
 * 首期起点是否落在期界上（前端预校验）。
 *
 * <p>与后端 V43 的触发器和 `AccountingBookController.checkPeriodAlignment` 同一口径：
 * 月度看 12、季度看 3，周度不对齐月份所以不校验。后端仍然是最终判定，这里只是
 * 让用户在提交前就看到原因，而不是吃一个 400。
 */
export function firstPeriodAligned(
  periodType: string,
  fiscalYearStartMonth: number,
  firstPeriodStartIso: string,
): boolean {
  if (periodType === 'WEEK') return true;
  const parsed = new Date(firstPeriodStartIso);
  if (Number.isNaN(parsed.getTime())) return false;
  const step = periodType === 'QUARTER' ? 3 : 12;
  const month = Number(utcCalendarDate(firstPeriodStartIso).slice(5, 7));
  return (((month - fiscalYearStartMonth) % step) + step) % step === 0;
}
