import { describe, expect, it } from 'vitest';
import {
  DENOMINATOR_OPTIONS,
  DIMENSION_OPTIONS,
  GRANULARITY_OPTIONS,
  amountText,
  anomalyDirectionLabel,
  dataUpdatedNote,
  dataUpdatedText,
  defaultRange,
  deltaText,
  effectiveThresholdText,
  emptyText,
  pageState,
  periodText,
  rangeText,
  rowStatusLabel,
  rowTone,
  seriesBounds,
  shareBarWidth,
  shareText,
  structureTrendSeries,
  thresholdModeLabel,
} from './cost-structure-model';
import type {
  CostStructureAnomaly,
  CostStructureResponse,
  CostStructureRow,
  CostStructureTrendPoint,
} from './cost-structure-model';

/**
 * 13.4.2.1 成本结构 前端模型。
 *
 * 🔴 这些断言盯的是**页面最容易说错的五件事**：
 *
 * 1. **「算不出来」≠「占比是 0」**。服务端给 `null` 的占比 / 环比差 / 同比差必须渲染成「—」
 *    与「不可比」，**不是** 0.00% / 0%（假 0 会被读成「这一类成本没花钱」）。
 * 2. **分母口径写死**。「占总成本」与「占收入」是两个口径，页面必须能分开显示。
 * 3. **多币种绝不相加**。金额必须带自己的币种。
 * 4. **数据更新时间常驻**。没有投影过成本事实时也要有一句话，不能什么都不显示。
 * 5. **前端不做业务算术**。占比、环比差、同比差、基线占比一律来自服务端，这里只做格式化。
 */

function row(overrides: Partial<CostStructureRow> = {}): CostStructureRow {
  return {
    costCategory: 'AD_FEE',
    costCategoryLabel: '广告费',
    dimensionKey: '',
    dimensionLabel: '全部',
    currency: 'USD',
    amount: 75,
    share: 75,
    denominatorAmount: 100,
    comparable: true,
    previousShare: 50,
    baselineShare: 50,
    yoyShare: 20,
    deltaVsPrevious: 25,
    deltaVsYoY: 55,
    anomaly: false,
    anomalyReason: '占比变化 25.0000 个百分点，未达阈值 5.0000 个百分点。',
    note: '占比可比。',
    ...overrides,
  };
}

function response(overrides: Partial<CostStructureResponse> = {}): CostStructureResponse {
  return {
    from: '2026-10-10',
    to: '2026-10-10',
    granularity: 'DAY',
    dimension: 'ALL',
    denominatorCaliber: 'TOTAL_COST',
    denominatorNote: '占比 = 该类成本 ÷ 同一维度取值下的成本合计。',
    rows: [row()],
    trend: [],
    anomalies: [],
    dataUpdatedAt: '2026-10-11T02:00:00Z',
    dataUpdatedNote: '最近一次成本事实投影落库时刻。',
    warnings: [],
    caliberNote: '七类成本分别来自哪个上游。',
    ...overrides,
  };
}

function trendPoint(overrides: Partial<CostStructureTrendPoint> = {}): CostStructureTrendPoint {
  return {
    periodStart: '2026-10-10',
    periodEnd: '2026-10-10',
    costCategory: 'AD_FEE',
    costCategoryLabel: '广告费',
    dimensionKey: '',
    dimensionLabel: '全部',
    currency: 'USD',
    amount: 75,
    share: 75,
    denominatorAmount: 100,
    comparable: true,
    ...overrides,
  };
}

function anomaly(overrides: Partial<CostStructureAnomaly> = {}): CostStructureAnomaly {
  return {
    costCategory: 'AD_FEE',
    costCategoryLabel: '广告费',
    dimensionKey: '',
    dimensionLabel: '全部',
    currency: 'USD',
    share: 75,
    baselineShare: 50,
    delta: 25,
    direction: 'BOTH',
    thresholdMode: 'FIXED',
    thresholdValue: 5,
    effectiveThreshold: 5,
    reason: '占比 75.0000% 相对基线 50.0000% 变化 25.0000 个百分点，达到阈值 5.0000 个百分点。',
    note: '成本结构变化比绝对值更有信号。',
    ...overrides,
  };
}

describe('13.4.2.1 成本结构 · 「算不出来」与「等于 0」分开', () => {
  it('null 的占比渲染成「—」，绝不是 0.00%', () => {
    expect(shareText(null)).toBe('—');
    expect(shareText(null)).not.toContain('0');
    expect(shareText(0)).toBe('0.00%');
  });

  it('null 的环比 / 同比差渲染成「不可比」，绝不是 0 个百分点', () => {
    expect(deltaText(null)).toBe('不可比');
    expect(deltaText(null)).not.toContain('0');
    expect(deltaText(25)).toBe('+25.00 个百分点');
    expect(deltaText(-25)).toBe('-25.00 个百分点');
  });

  it('null 的金额渲染成「—」，且必须带自己的币种', () => {
    expect(amountText(null, 'USD')).toBe('—');
    expect(amountText(12.5, 'USD')).toBe('12.50 USD');
    expect(amountText(12.5, 'EUR')).toBe('12.50 EUR');
  });

  it('判不了异常时生效阈值是「判不了」，不是 0', () => {
    expect(effectiveThresholdText(anomaly({ effectiveThreshold: null }))).toContain('判不了');
    expect(effectiveThresholdText(anomaly({ effectiveThreshold: null }))).not.toContain('0.00');
    expect(effectiveThresholdText(anomaly({ effectiveThreshold: 5 }))).toBe('5.00 个百分点');
  });
});

describe('13.4.2.1 成本结构 · 语义色与状态', () => {
  it('不可比 ⇒ neutral（算不出来不是错误）；异常 ⇒ danger；正常 ⇒ success', () => {
    expect(rowTone(row({ comparable: false, anomaly: false }))).toBe('neutral');
    expect(rowTone(row({ anomaly: true }))).toBe('danger');
    expect(rowTone(row({ anomaly: false }))).toBe('success');
  });

  it('状态标签把「不可比」与「正常」分开', () => {
    expect(rowStatusLabel(row({ comparable: false }))).toBe('不可比');
    expect(rowStatusLabel(row({ anomaly: true }))).toBe('异常');
    expect(rowStatusLabel(row({ anomaly: false }))).toBe('正常');
  });

  it('异常方向与阈值口径有中文名', () => {
    expect(anomalyDirectionLabel('UP')).toBe('占比突然上升');
    expect(anomalyDirectionLabel('DOWN')).toBe('占比突然下降');
    expect(anomalyDirectionLabel('BOTH')).toBe('占比跳变');
    expect(thresholdModeLabel('FIXED')).toBe('固定百分点');
    expect(thresholdModeLabel('STDDEV')).toBe('基线标准差的若干倍');
  });
});

describe('13.4.2.1 成本结构 · 结构条', () => {
  it('宽度只做展示钳位，不做算术', () => {
    expect(shareBarWidth(75)).toBe(75);
    expect(shareBarWidth(-5)).toBe(0);
    expect(shareBarWidth(150)).toBe(100);
    expect(shareBarWidth(null)).toBe(0);
  });
});

describe('13.4.2.1 成本结构 · 数据更新时间常驻', () => {
  it('没有响应时不编一句话出来', () => {
    expect(dataUpdatedText(null)).toBe('');
  });

  it('尚未投影成本事实时写「尚未投影任何成本事实」，不是 -infinity', () => {
    const text = dataUpdatedText(response({ dataUpdatedAt: null }));
    expect(text).toContain('尚未投影任何成本事实');
    expect(text).not.toContain('infinity');
  });

  it('有数据时显示数据截至时刻与口径说明', () => {
    const value = response();
    expect(dataUpdatedText(value)).toContain('2026-10-11');
    expect(dataUpdatedNote(value)).toContain('投影落库时刻');
  });
});

describe('13.4.2.1 成本结构 · 页面状态', () => {
  it('读不到 / 读到了但没有数据 / 正常，三态分开', () => {
    expect(pageState(null, 'boom')).toBe('error');
    expect(pageState(null, '')).toBe('loading');
    expect(pageState(response({ rows: [] }), '')).toBe('empty');
    expect(pageState(response(), '')).toBe('ready');
  });

  it('空态文案说明是「没有事实」而不是「读不到」', () => {
    expect(emptyText(response({ rows: [] }))).toContain('没有任何成本事实');
    expect(emptyText(null)).toBe('');
  });
});

describe('13.4.2.1 成本结构 · 趋势', () => {
  it('不可比的期间没有点（不是 0%）', () => {
    const series = structureTrendSeries([
      trendPoint({ periodStart: '2026-10-08', share: 50 }),
      trendPoint({ periodStart: '2026-10-09', share: null, comparable: false }),
      trendPoint({ periodStart: '2026-10-10', share: 75 }),
    ]);
    expect(series).toHaveLength(1);
    const points = series[0]?.points ?? [];
    expect(points).toHaveLength(2);
    expect(points.map((p) => p.periodStart)).toEqual(['2026-10-08', '2026-10-10']);
  });

  it('按「成本类别 × 币种」拆线，跨币种不混', () => {
    const series = structureTrendSeries([
      trendPoint({ share: 10 }),
      trendPoint({ currency: 'EUR', share: 20 }),
    ]);
    expect(series).toHaveLength(2);
  });

  it('范围里始终包含 0；没有点时返回 null', () => {
    expect(seriesBounds([{ share: 30 }, { share: 70 }])).toEqual({ min: 0, max: 70 });
    expect(seriesBounds([])).toBeNull();
  });
});

describe('13.4.2.1 成本结构 · 选项与文案', () => {
  it('分母口径两个都在下拉里（口径必须能分开选）', () => {
    expect(DENOMINATOR_OPTIONS.map((o) => o.value)).toEqual(['TOTAL_COST', 'REVENUE']);
  });

  it('维度下拉覆盖蓝图点名的 SKU / 店铺 / 类目', () => {
    const values = DIMENSION_OPTIONS.map((o) => o.value);
    expect(values).toContain('SKU');
    expect(values).toContain('SHOP');
    expect(values).toContain('CATEGORY');
  });

  it('期间粒度三种都在', () => {
    expect(GRANULARITY_OPTIONS.map((o) => o.value)).toEqual(['DAY', 'WEEK', 'MONTH']);
  });

  it('默认窗口是最近 30 天（含今天）', () => {
    const range = defaultRange('2026-10-10');
    expect(range.to).toBe('2026-10-10');
    expect(range.from).toBe('2026-09-11');
    expect(rangeText(range.from, range.to)).toBe('2026-09-11 ~ 2026-10-10');
  });

  it('期间文本逐日事实只显示一天', () => {
    expect(periodText('2026-10-10', '2026-10-10')).toBe('2026-10-10');
    expect(periodText('2026-10-01', '2026-10-31')).toBe('2026-10-01 ~ 2026-10-31');
  });
});
