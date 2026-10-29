import { describe, expect, it } from 'vitest';
import {
  DIMENSION_OPTIONS,
  GRANULARITY_OPTIONS,
  amountText,
  baseAmountText,
  baseRateText,
  componentReason,
  componentText,
  dataUpdatedNote,
  dataUpdatedText,
  defaultRange,
  drilldownPageNote,
  drilldownScopeText,
  emptyText,
  grossProfitLabel,
  grossProfitText,
  grossProfitTone,
  grossMarginText,
  isUnattributed,
  operatingModeLabel,
  pageState,
  periodText,
  rangeText,
  rowPeriodText,
} from './profit-report-model';
import type {
  ProfitComponentAmount,
  ProfitDrilldownPage,
  ProfitDrilldownRow,
  ProfitReportResponse,
  ProfitReportRow,
} from './profit-report-model';

/**
 * 13.4.1.1 多模式利润报表 前端模型。
 *
 * 🔴 这些断言盯的是**页面最容易说错的五件事**：
 *
 * 1. **不同经营模式的利润含义完全不同** ⇒ 模式分行展示、**绝不合并**，
 *    「未归属」也不是一种模式（页面要标出来）。
 * 2. **「算不出来」≠「等于 0」**。服务端给 `null` 的成本项与毛利必须渲染成「—」
 *    与「不可比」，**不是** 0.00（假 0 会被读成「完全没花钱」）。
 * 3. **多币种绝不相加**。金额必须带自己的币种；本位币合计要带汇率与时点。
 * 4. **数据更新时间常驻**。没有投影过任何利润事实时也要有一句话，不能什么都不显示。
 * 5. **前端不做业务算术**。毛利、毛利率、成本合计一律来自服务端，这里只做格式化。
 */

function component(
  overrides: Partial<ProfitComponentAmount> = {},
): ProfitComponentAmount {
  return {
    component: 'COGS',
    label: '商品成本',
    amount: 40,
    available: true,
    reason: '',
    ...overrides,
  };
}

function row(overrides: Partial<ProfitReportRow> = {}): ProfitReportRow {
  return {
    dimension: 'MODE',
    dimensionCode: '',
    dimensionLabel: '经营模式',
    operatingMode: 'SELF_FULFILLED',
    operatingModeLabel: '自发货',
    periodStart: '2026-10-01',
    periodEnd: '2026-10-01',
    currency: 'USD',
    components: [
      component({ component: 'REVENUE', label: '订单收入', amount: 100 }),
      component({ component: 'COGS', label: '商品成本', amount: 40 }),
    ],
    totalCost: 40,
    grossProfit: 60,
    grossMargin: 60,
    grossProfitComparable: true,
    missingComponents: [],
    baseCurrency: null,
    baseRevenue: null,
    baseGrossProfit: null,
    baseRate: null,
    baseRateAsOf: null,
    factCount: 2,
    note: '收入取订单总额口径。',
    ...overrides,
  };
}

function response(overrides: Partial<ProfitReportResponse> = {}): ProfitReportResponse {
  return {
    from: '2026-10-01',
    to: '2026-10-01',
    granularity: 'DAY',
    dimension: 'MODE',
    rows: [row()],
    modes: [
      {
        operatingMode: 'SELF_FULFILLED',
        operatingModeLabel: '自发货',
        shopCount: 1,
        shopCodes: ['SHOP-1'],
        note: '模式取自 channel 2.3.2.1。',
      },
    ],
    dataUpdatedAt: '2026-10-02T03:00:00Z',
    dataUpdatedNote: '最近一次投影落库时刻。',
    caliberNote: '口径说明。',
    warnings: [],
    ...overrides,
  };
}

describe('13.4.1.1 多模式利润报表 · 金额格式化', () => {
  it('null 渲染成「—」，绝不用 0 顶替', () => {
    expect(amountText(null, 'USD')).toBe('—');
    expect(amountText(0, 'USD')).toBe('0.00 USD');
  });

  it('金额带自己的币种（多币种绝不相加）', () => {
    expect(amountText(1234.5, 'USD')).toBe('1,234.50 USD');
    expect(amountText(1234.5, 'EUR')).toBe('1,234.50 EUR');
  });

  it('毛利 null ⇒ 「—（不可比）」，不是 0', () => {
    expect(grossProfitText(null, 'USD')).toBe('—（不可比）');
    expect(grossProfitText(0, 'USD')).toBe('0.00 USD');
  });

  it('毛利率 null ⇒ 「不可比」，不是 0%', () => {
    expect(grossMarginText(null)).toBe('不可比');
    expect(grossMarginText(0)).toBe('0.00%');
    expect(grossMarginText(12.3456)).toBe('12.35%');
  });

  it('成本项没有值时是「—」并带原因，不是 0', () => {
    const missing = component({
      component: 'AD_FEE',
      label: '广告费',
      amount: null,
      available: false,
      reason: '本模式在本窗口内没有这类成本事实 —— 算不出来，不是 0。',
    });
    expect(componentText(missing, 'USD')).toBe('—');
    expect(componentReason(missing)).toContain('不是 0');
  });
});

describe('13.4.1.1 多模式利润报表 · 模式不合并', () => {
  it('经营模式的中文名来自服务端，未知模式原样显示', () => {
    expect(operatingModeLabel('SELF_FULFILLED', '自发货')).toBe('自发货');
    expect(operatingModeLabel('SOMETHING_NEW', 'SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('「未归属」被标出来 —— 它不是一种经营模式', () => {
    expect(isUnattributed('UNKNOWN')).toBe(true);
    expect(isUnattributed('SELF_FULFILLED')).toBe(false);
  });

  it('毛利语义色：盈利 success / 亏损 danger / 不可比 neutral', () => {
    expect(grossProfitTone(row({ grossProfit: 10, grossProfitComparable: true }))).toBe('success');
    expect(grossProfitTone(row({ grossProfit: -10, grossProfitComparable: true }))).toBe('danger');
    expect(
      grossProfitTone(row({ grossProfit: null, grossProfitComparable: false })),
    ).toBe('neutral');
  });

  it('毛利状态标签', () => {
    expect(grossProfitLabel(row({ grossProfit: 10, grossProfitComparable: true }))).toBe('盈利');
    expect(grossProfitLabel(row({ grossProfit: -10, grossProfitComparable: true }))).toBe('亏损');
    expect(grossProfitLabel(row({ grossProfit: null, grossProfitComparable: false }))).toBe('不可比');
  });
});

describe('13.4.1.1 多模式利润报表 · 汇率快照', () => {
  it('没有汇率快照就没有本位币合计（不是 0）', () => {
    expect(baseAmountText(null, 'USD')).toBe('—');
    expect(baseAmountText(100, null)).toBe('—');
    expect(baseAmountText(100, 'CNY')).toBe('100.00 CNY');
  });

  it('汇率必须带时点', () => {
    expect(baseRateText(null, null)).toBe('—');
    expect(baseRateText(7.123456, '2026-10-01T00:00:00Z')).toBe(
      '7.123456（2026-10-01T00:00:00Z）',
    );
  });
});

describe('13.4.1.1 多模式利润报表 · 页面状态', () => {
  it('数据更新时间常驻：没投影过也要有一句话', () => {
    expect(dataUpdatedText(null)).toBe('');
    expect(dataUpdatedText(response({ dataUpdatedAt: null }))).toBe(
      '数据更新时间：尚未投影任何利润事实',
    );
    expect(dataUpdatedText(response())).toContain('数据截至');
    expect(dataUpdatedNote(response())).toBe('最近一次投影落库时刻。');
  });

  it('「读不到」与「没有数据」分开说', () => {
    expect(pageState(null, 'boom')).toBe('error');
    expect(pageState(null, '')).toBe('loading');
    expect(pageState(response({ rows: [] }), '')).toBe('empty');
    expect(pageState(response(), '')).toBe('ready');
  });

  it('空态文案说清楚「不是利润为 0」', () => {
    expect(emptyText(response({ rows: [] }))).toContain('不是「利润为 0」');
    expect(emptyText(response())).toBe('');
  });
});

describe('13.4.1.1 多模式利润报表 · 下钻与期间', () => {
  it('下钻分页说明带真计数', () => {
    const page: ProfitDrilldownPage = {
      items: [],
      page: 1,
      size: 50,
      total: 120,
      dimension: 'SHOP',
      dimensionCode: 'SHOP-1',
      operatingMode: 'SELF_FULFILLED',
      note: '',
    };
    expect(drilldownPageNote(page)).toContain('共 120 条');
    expect(drilldownPageNote(null)).toBe('');
  });

  it('下钻一行的归属摘要取服务端原值', () => {
    const item: ProfitDrilldownRow = {
      component: 'COGS',
      componentLabel: '商品成本',
      operatingMode: 'SELF_FULFILLED',
      operatingModeLabel: '自发货',
      shopCode: 'SHOP-1',
      skuCode: 'SKU-1',
      spuCode: '',
      mskuCode: '',
      orderRef: '',
      periodStart: '2026-10-01',
      periodEnd: '2026-10-01',
      currency: 'USD',
      amount: 40,
      quantity: 0,
      sourceService: 'finance',
      sourceReference: 'cost-allocation:1',
      note: '',
    };
    expect(drilldownScopeText(item)).toContain('店铺 SHOP-1');
    expect(drilldownScopeText(item)).toContain('SKU SKU-1');
    expect(drilldownScopeText({ ...item, shopCode: '', skuCode: '' })).toContain('没有更细的归属');
  });

  it('期间文本：逐日事实两列相同，窗口型事实不同', () => {
    expect(periodText('2026-10-01', '2026-10-01')).toBe('2026-10-01');
    expect(periodText('2026-10-01', '2026-10-31')).toBe('2026-10-01 ~ 2026-10-31');
    expect(rowPeriodText(row())).toBe('2026-10-01');
  });

  it('时间范围与默认窗口', () => {
    expect(rangeText('2026-10-01', '2026-10-31')).toBe('2026-10-01 ~ 2026-10-31');
    const range = defaultRange('2026-10-31');
    expect(range.to).toBe('2026-10-31');
    expect(range.from).toBe('2026-10-02');
  });
});

describe('13.4.1.1 多模式利润报表 · 筛选选项', () => {
  it('核算维度覆盖蓝图点名的多维度', () => {
    const values = DIMENSION_OPTIONS.map((option) => option.value);
    expect(values).toEqual(['MODE', 'SHOP', 'ORDER', 'SKU', 'SPU', 'MSKU']);
  });

  it('期间粒度只有按天 / 按月', () => {
    expect(GRANULARITY_OPTIONS.map((option) => option.value)).toEqual(['DAY', 'MONTH']);
  });
});
