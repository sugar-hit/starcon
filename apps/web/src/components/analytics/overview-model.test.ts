import { describe, expect, it } from 'vitest';
import {
  aggregationLabel,
  availabilityText,
  baseTotalText,
  cardValueRows,
  comparisonDirection,
  comparisonText,
  dataUpdatedText,
  definitionFormula,
  defaultRange,
  drilldownSourceLabel,
  emptyText,
  metricValueText,
  pageState,
  scopeLabel,
  seriesMax,
  trendSeries,
} from './overview-model';
import type { Comparison, MetricCard, OverviewResponse, TrendPoint } from './overview-model';

/**
 * 13.1.1.1 经营总览 前端模型。
 *
 * 🔴 这些断言盯的是**页面最容易说错的五件事**：
 *
 * 1. **「没有数据」≠「数据是 0」**。`NO_DATA` 的指标必须渲染成「—」+ 原因，
 *    **不是** 0（假 0 会被读成「这个月没卖出东西」）。
 * 2. **不可比 ≠ 0%**。对比期分母为 0 ⇒ `changeRate` 为 null ⇒ 渲染成「不可比」，
 *    而且方向是 `unknown` 而不是 `flat`（`flat` 是「真的是持平」）。
 * 3. **多币种绝不相加**。一张卡上多个币种就分行；本位币合计必须**同时显示汇率与时点**。
 * 4. **数据更新时间常驻**。没有投影过数据时也要有一句话，不能什么都不显示。
 * 5. **前端不做业务算术**。展示格式化只做 Intl 格式化，变化率一律来自服务端。
 */

function card(overrides: Partial<MetricCard> = {}): MetricCard {
  return {
    metricCode: 'SALES_AMOUNT',
    displayName: '销售额',
    unit: 'CURRENCY',
    aggregation: 'SUM',
    description: '成交订单的含税成交金额。',
    ownerModule: '9.1.1.1',
    availability: 'AVAILABLE',
    values: [{ currency: 'USD', value: 1500 }],
    baseTotal: null,
    baseCurrency: null,
    baseRate: null,
    baseRateAsOf: null,
    previousPeriod: comparison(10),
    yearOverYear: comparison(null),
    note: '',
    ...overrides,
  };
}

function comparison(changeRate: number | null): Comparison {
  return {
    label: '环比（上一等长周期）',
    from: '2026-09-29',
    to: '2026-09-30',
    values: [],
    changeRate,
    changeAmount: changeRate === null ? null : 100,
    comparable: changeRate !== null,
    note: changeRate === null ? '对比期取值为 0，变化率不可比（不显示成 0%）。' : '',
  };
}

function response(overrides: Partial<OverviewResponse> = {}): OverviewResponse {
  return {
    scopeType: 'COMPANY',
    legalEntityId: null,
    shopId: null,
    from: '2026-10-01',
    to: '2026-10-02',
    cards: [card()],
    trend: [],
    dataUpdatedAt: '2026-10-03T01:02:03Z',
    dataUpdatedNote: '最近一次投影落库时刻。',
    timezones: ['UTC'],
    warnings: [],
    currencyNote: '',
    ...overrides,
  };
}

describe('13.1.1.1 经营总览 · 模型', () => {
  describe('没有数据 ≠ 数据是 0', () => {
    it('NO_DATA 的指标不渲染任何取值行', () => {
      const c = card({ availability: 'NO_DATA', values: [] });
      expect(cardValueRows(c)).toEqual([]);
      expect(availabilityText(c)).toContain('不是 0');
    });

    it('取值为 null 时展示破折号而不是 0', () => {
      expect(metricValueText(null, 'CURRENCY')).toBe('—');
      expect(metricValueText(null, 'PERCENT')).toBe('—');
      expect(metricValueText(0, 'CURRENCY')).toBe('0.00');
    });

    it('未登记的指标与没有数据的指标说法不同', () => {
      expect(availabilityText(card({ availability: 'NOT_REGISTERED' }))).toBe('该指标未在口径表登记');
    });
  });

  describe('不可比 ≠ 0%', () => {
    it('变化率为 null 时方向是 unknown 而不是 flat', () => {
      expect(comparisonDirection(comparison(null))).toBe('unknown');
      expect(comparisonText(comparison(null))).toContain('不可比');
    });

    it('真的是持平才是 flat', () => {
      expect(comparisonDirection(comparison(0))).toBe('flat');
    });

    it('涨与跌分得开', () => {
      expect(comparisonDirection(comparison(12.5))).toBe('up');
      expect(comparisonDirection(comparison(-8))).toBe('down');
    });

    it('变化率带正负号与两位小数', () => {
      expect(comparisonText(comparison(12.5))).toBe('+12.50%');
      expect(comparisonText(comparison(-8))).toBe('-8.00%');
    });
  });

  describe('多币种绝不相加', () => {
    it('多个币种分行显示', () => {
      const c = card({
        values: [
          { currency: 'USD', value: 100 },
          { currency: 'EUR', value: 50 },
        ],
      });
      expect(cardValueRows(c)).toEqual(['100.00 USD', '50.00 EUR']);
    });

    it('没有本位币合计就不显示合计行', () => {
      expect(baseTotalText(card())).toBe('');
    });

    it('本位币合计必须同时显示汇率与时点', () => {
      const c = card({
        baseTotal: 1200,
        baseCurrency: 'CNY',
        baseRate: 0.8,
        baseRateAsOf: '2026-10-02T00:00:00Z',
      });
      const text = baseTotalText(c);
      expect(text).toContain('1,200.00 CNY');
      expect(text).toContain('汇率');
      expect(text).toContain('0.80000000');
      expect(text).toContain('2026-10-02');
    });
  });

  describe('数据更新时间常驻', () => {
    it('有投影时显示数据截至时刻', () => {
      expect(dataUpdatedText(response())).toContain('数据截至');
      expect(dataUpdatedText(response())).toContain('2026-10-03T01:02:03Z');
    });

    it('还没有投影时也有话说，而不是空白', () => {
      expect(dataUpdatedText(response({ dataUpdatedAt: null }))).toContain('尚未投影');
    });

    it('响应还没到时返回空串而不是报错', () => {
      expect(dataUpdatedText(null)).toBe('');
    });
  });

  describe('趋势图只画有事实的天', () => {
    const points: TrendPoint[] = [
      { metricCode: 'SALES_AMOUNT', date: '2026-10-01', currency: 'USD', value: 100 },
      { metricCode: 'SALES_AMOUNT', date: '2026-10-02', currency: 'USD', value: 200 },
      { metricCode: 'SALES_AMOUNT', date: '2026-10-01', currency: 'EUR', value: 50 },
      { metricCode: 'ORDER_COUNT', date: '2026-10-01', currency: 'USD', value: 3 },
    ];

    it('按指标 × 币种拆线', () => {
      const series = trendSeries(points, ['SALES_AMOUNT']);
      expect(series).toHaveLength(2);
      expect(series.find((s) => s.currency === 'USD')?.points).toHaveLength(2);
    });

    it('null 值的点不进入曲线', () => {
      const withNull: TrendPoint[] = [
        ...points,
        { metricCode: 'SALES_AMOUNT', date: '2026-10-03', currency: 'USD', value: null },
      ];
      const series = trendSeries(withNull, ['SALES_AMOUNT']);
      const usd = series.find((s) => s.currency === 'USD');
      expect(usd?.points).toHaveLength(2);
    });

    it('没有点时最大值是 null 而不是 0', () => {
      expect(seriesMax({ metricCode: 'X', currency: 'USD', points: [] })).toBeNull();
    });
  });

  describe('页面状态：读不到 ≠ 没有数据', () => {
    it('出错时是 error', () => {
      expect(pageState(null, '服务不可达')).toBe('error');
    });

    it('还没到是 loading', () => {
      expect(pageState(null, '')).toBe('loading');
    });

    it('读到但一格数据都没有是 empty', () => {
      const r = response({ cards: [card({ availability: 'NO_DATA', values: [] })] });
      expect(pageState(r, '')).toBe('empty');
      expect(emptyText(r)).toContain('没有任何指标有数据');
    });

    it('有数据是 ready', () => {
      expect(pageState(response(), '')).toBe('ready');
    });
  });

  describe('口径写在卡片上', () => {
    it('派生型只显示分子分母引用，不显示公式 SQL', () => {
      expect(
        definitionFormula({
          metricCode: 'GROSS_MARGIN',
          displayName: '毛利率',
          unit: 'PERCENT',
          aggregation: 'DERIVED_RATIO',
          numeratorMetricCode: 'GROSS_PROFIT',
          denominatorMetricCode: 'SALES_AMOUNT',
          currencyAware: true,
          description: '',
          ownerModule: '13.1.1.1',
        }),
      ).toBe('GROSS_PROFIT ÷ SALES_AMOUNT');
    });

    it('聚合方式有中文标签', () => {
      expect(aggregationLabel('LAST')).toBe('取最近一天的快照');
      expect(aggregationLabel('SUM')).toBe('窗口内求和');
    });
  });

  describe('维度与时间范围', () => {
    it('维度有中文标签', () => {
      expect(scopeLabel('COMPANY')).toBe('公司维度');
      expect(scopeLabel('LEGAL_ENTITY')).toBe('法人维度');
      expect(scopeLabel('SHOP')).toBe('店铺维度');
    });

    it('默认窗口是最近 30 天（含今天）', () => {
      const range = defaultRange('2026-10-25');
      expect(range.to).toBe('2026-10-25');
      expect(range.from).toBe('2026-09-26');
    });
  });

  describe('下钻', () => {
    it('来源类型有中文标签', () => {
      expect(drilldownSourceLabel('ORDER')).toBe('订单');
      expect(drilldownSourceLabel('CAMPAIGN')).toBe('广告活动');
      expect(drilldownSourceLabel('RECEIVABLE')).toBe('应收单');
    });
  });
});
