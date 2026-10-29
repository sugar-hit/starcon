import { describe, expect, it } from 'vitest';
import {
  amountText,
  anomalyDirectionLabel,
  anomalyTone,
  availabilityLabel,
  availabilityTone,
  bucketLabel,
  caliberNotesOf,
  comparisonCellText,
  comparisonGrowthText,
  comparisonYearText,
  countText,
  dataUpdatedNote,
  dataUpdatedText,
  defaultRange,
  dimensionLabel,
  emptyReasonLabel,
  emptyText,
  eventText,
  eventTypeLabel,
  formatDecimal,
  granularityLabel,
  metricFormulaText,
  metricUnitLabel,
  notDeliveredLines,
  pageState,
  percentText,
  periodOverPeriodNote,
  pointOf,
  quantityText,
  refreshStatusLabel,
  refreshStatusTone,
  ruleNotes,
  seriesOf,
  severityTone,
  statusLabel,
  upstreamModules,
  warningsOf,
  yearOverYearNote,
} from './sales-trend-model';
import type {
  SalesTrendComparisonRow,
  SalesTrendMetricDefinitionView,
  SalesTrendPoint,
  SalesTrendResponse,
  SalesTrendSeries,
} from './sales-trend-model';

/**
 * 13.2.1.1 销量趋势 前端模型的单元测试。
 *
 * <p>🔴 这一页最容易做丢的是「算不出来 ≠ 0」，所以下面的断言大半在钉这一条：
 * {@code null} 一律渲染成「—」/「不可比」，**绝不**渲染成 0.00 / 0%。
 */

function point(overrides: Partial<SalesTrendPoint> = {}): SalesTrendPoint {
  return {
    bucketStart: '2026-10-01',
    bucketEnd: '2026-10-01',
    bucketHour: null,
    bucketLabel: '2026-10-01',
    currency: 'USD',
    value: 300,
    baseValue: null,
    baseCurrency: null,
    periodOverPeriodValue: 300,
    periodOverPeriodRate: 0,
    periodOverPeriodBucketStart: '2026-09-30',
    yearOverYearValue: 50,
    yearOverYearRate: 500,
    yearOverYearBucketStart: '2025-10-01',
    anomaly: false,
    anomalyNote: '',
    events: [],
    ...overrides,
  };
}

function series(overrides: Partial<SalesTrendSeries> = {}): SalesTrendSeries {
  return {
    metricCode: 'SALES_AMOUNT',
    displayName: '成交金额',
    unit: 'CURRENCY',
    dimensionType: 'TOTAL',
    dimensionKey: '',
    dimensionLabel: '全部店铺',
    currency: 'USD',
    currencyAware: true,
    points: [point()],
    ...overrides,
  };
}

function response(overrides: Partial<SalesTrendResponse> = {}): SalesTrendResponse {
  return {
    requestedFrom: '2026-10-01',
    requestedTo: '2026-10-05',
    from: '2026-10-01',
    to: '2026-10-05',
    granularity: 'DAY',
    timezone: 'UTC',
    series: [series()],
    anomalies: [],
    events: [],
    metricDefinitions: [],
    dataUpdatedAt: '2026-10-06T03:00:00Z',
    dataUpdatedNote: '最近一次投影落库时刻（报表不是实时的）。',
    status: 'PROJECTED',
    statusNote: '',
    emptyReason: null,
    emptyNote: '',
    warnings: [],
    caliberNotes: [],
    baseCurrency: null,
    baseRates: [],
    ...overrides,
  };
}

function comparisonRow(overrides: Partial<SalesTrendComparisonRow> = {}): SalesTrendComparisonRow {
  return {
    dimensionType: 'STORE',
    dimensionKey: 'shop-a',
    dimensionLabel: 'shop-a',
    currency: 'USD',
    metrics: [
      {
        metricCode: 'SALES_AMOUNT',
        displayName: '成交金额',
        unit: 'CURRENCY',
        value: 300,
        baseValue: null,
        periodOverPeriodValue: 200,
        periodOverPeriodRate: 50,
        yearOverYearValue: 100,
        yearOverYearRate: 200,
        availability: 'AVAILABLE',
        availabilityNote: '',
      },
    ],
    ...overrides,
  };
}

// ================================================================ 十进制格式化

describe('formatDecimal', () => {
  it('按十进制定标，不受浮点误差影响', () => {
    expect(formatDecimal(1.005, 2)).toBe('1.01');
    expect(formatDecimal(1.0049, 2)).toBe('1.00');
    expect(formatDecimal(1234.5678, 2)).toBe('1,234.57');
    expect(formatDecimal(0, 2)).toBe('0.00');
  });

  it('负数与千分位都对', () => {
    expect(formatDecimal(-1234.5, 2)).toBe('-1,234.50');
    expect(formatDecimal(-0.125, 2)).toBe('-0.13');
  });
});

// ================================================================ 「算不出来 ≠ 0」

describe('「算不出来」与「等于 0」分开', () => {
  it('金额为 null 渲染成「—」而不是 0.00', () => {
    expect(amountText(null, 'USD')).toBe('—');
    expect(amountText(0, 'USD')).toBe('0.00 USD');
  });

  it('比率为 null 渲染成「不可比」而不是 0%', () => {
    expect(percentText(null)).toBe('不可比');
    expect(percentText(0)).toBe('0.00%');
    expect(percentText(12.5)).toBe('+12.50%');
    expect(percentText(-12.5)).toBe('-12.50%');
  });

  it('数量与笔数为 null 渲染成「—」', () => {
    expect(quantityText(null)).toBe('—');
    expect(countText(null)).toBe('—');
    expect(quantityText(12)).toBe('12');
  });

  it('基期缺失时给出「为什么不可比」', () => {
    const missing = point({
      periodOverPeriodValue: null,
      periodOverPeriodRate: null,
      yearOverYearValue: null,
      yearOverYearRate: null,
    });
    expect(periodOverPeriodNote(missing)).toContain('没有事实');
    expect(yearOverYearNote(missing)).toContain('没有事实');
  });

  it('基期为 0 时也明说「不可比」而不是 0%', () => {
    const zeroBase = point({ periodOverPeriodValue: 0, periodOverPeriodRate: null });
    expect(periodOverPeriodNote(zeroBase)).toContain('为 0');
    expect(percentText(zeroBase.periodOverPeriodRate)).toBe('不可比');
  });

  it('算得出来的时候不给多余的说明', () => {
    expect(periodOverPeriodNote(point())).toBe('');
    expect(yearOverYearNote(point())).toBe('');
  });
});

// ================================================================ 指标单位分派

describe('按口径单位分派渲染', () => {
  it('金额带币种，百分比带 %，数量不带小数点', () => {
    expect(metricUnitLabel('CURRENCY')).toBe('金额');
    expect(metricUnitLabel('PERCENT')).toBe('百分比');
    expect(metricUnitLabel('QUANTITY')).toBe('数量');
    expect(metricUnitLabel('COUNT')).toBe('笔数');
  });

  it('对比矩阵的取值按单位渲染，null 仍是「—」', () => {
    const row = comparisonRow();
    expect(comparisonCellText(row, 'SALES_AMOUNT', 'CURRENCY')).toBe('300.00 USD');
    expect(comparisonCellText(row, 'UNKNOWN', 'CURRENCY')).toBe('—');
  });

  it('对比矩阵的环比 / 同比在基期缺失时是「不可比」', () => {
    const row = comparisonRow({
      metrics: [
        {
          ...comparisonRow().metrics[0]!,
          periodOverPeriodRate: null,
          yearOverYearRate: null,
        },
      ],
    });
    expect(comparisonGrowthText(row, 'SALES_AMOUNT')).toBe('不可比');
    expect(comparisonYearText(row, 'SALES_AMOUNT')).toBe('不可比');
  });
});

// ================================================================ 口径

describe('口径展示', () => {
  const definition: SalesTrendMetricDefinitionView = {
    metricCode: 'SALES_AMOUNT_PERIOD_OVER_PERIOD',
    displayName: '成交金额环比增长率',
    unit: 'PERCENT',
    aggregation: 'PERIOD_OVER_PERIOD',
    baseMetricCode: 'SALES_AMOUNT',
    dimensionType: 'TOTAL',
    currencyAware: false,
    description: '口径说明',
    ownerModule: '13.2.1.1',
  };

  it('对比型口径必须说清比的是哪个指标', () => {
    expect(metricFormulaText(definition)).toContain('SALES_AMOUNT');
    expect(metricFormulaText({ ...definition, baseMetricCode: null })).toContain('没有声明');
  });

  it('维度的展示名分得清', () => {
    expect(dimensionLabel('TOTAL')).toContain('公司');
    expect(dimensionLabel('STORE')).toBe('店铺');
    expect(dimensionLabel('PRODUCT')).toContain('SKU');
  });
});

// ================================================================ 页面状态

describe('页面三态分开', () => {
  it('读不到是错误态，读到了但没数据是空态', () => {
    expect(pageState(null, '')).toBe('loading');
    expect(pageState(null, 'boom')).toBe('error');
    expect(pageState(response({ series: [] }), '')).toBe('empty');
    expect(pageState(response(), '')).toBe('ready');
  });

  it('空态文案用服务端给的原因，不自己编', () => {
    const empty = response({
      series: [],
      emptyReason: 'NOT_PROJECTED',
      emptyNote: '这个租户还没有投影过销量事实。',
    });
    expect(emptyText(empty)).toBe('这个租户还没有投影过销量事实。');
    expect(emptyReasonLabel('NOT_PROJECTED')).toBe('还没有投影事实');
    expect(emptyReasonLabel('NO_FACTS_IN_WINDOW')).toBe('这个窗口没有数据');
  });

  it('状态标签有中文', () => {
    expect(statusLabel('PROJECTED')).toBe('已投影');
    expect(statusLabel('NOT_PROJECTED')).toBe('未投影');
  });
});

// ================================================================ 数据更新时间

describe('数据更新时间常驻', () => {
  it('空表时显示「尚未投影」而不是一个假时间', () => {
    expect(dataUpdatedText(response({ dataUpdatedAt: null }))).toContain('尚未投影');
    expect(dataUpdatedText(response())).toContain('数据截至');
    expect(dataUpdatedNote(response())).toContain('投影落库时刻');
  });
});

// ================================================================ 趋势

describe('趋势序列', () => {
  it('按指标 + 币种取序列，取不到返回 null', () => {
    expect(seriesOf(response(), 'SALES_AMOUNT', 'USD')?.currency).toBe('USD');
    expect(seriesOf(response(), 'SALES_AMOUNT', 'EUR')).toBeNull();
    expect(seriesOf(null, 'SALES_AMOUNT', 'USD')).toBeNull();
  });

  it('桶里没有事实就是没有点（不是 0）', () => {
    const s = series({ points: [point(), point({ bucketStart: '2026-10-02' })] });
    expect(pointOf(s, '2026-10-02')).not.toBeNull();
    expect(pointOf(s, '2026-10-03')).toBeNull();
    expect(pointOf(null, '2026-10-01')).toBeNull();
  });

  it('桶标签用服务端给的原文', () => {
    expect(bucketLabel(point({ bucketLabel: '2026-10-01 ~ 2026-10-07' }))).toBe(
      '2026-10-01 ~ 2026-10-07',
    );
  });

  it('默认窗口是最近 30 天（含今天）', () => {
    expect(defaultRange('2026-10-10')).toEqual({ from: '2026-09-11', to: '2026-10-10' });
  });
});

// ================================================================ 异常与事件

describe('异常与事件', () => {
  it('异常方向有中文标签，语义色是 warning（异常不是错误）', () => {
    expect(anomalyDirectionLabel('SPIKE')).toBe('异常偏高');
    expect(anomalyDirectionLabel('DIP')).toBe('异常偏低');
    expect(anomalyTone('SPIKE')).toBe('warning');
    expect(anomalyTone('DIP')).toBe('warning');
  });

  it('事件类型与严重级别有标签', () => {
    expect(eventTypeLabel('PROMOTION')).toBe('大促');
    expect(eventTypeLabel('FLASH_SALE')).toBe('秒杀');
    expect(eventTypeLabel('STOCKOUT')).toBe('断货');
    expect(eventTypeLabel('SHORTAGE')).toBe('缺货');
    expect(severityTone('CRITICAL')).toBe('danger');
    expect(severityTone('WARNING')).toBe('warning');
    expect(severityTone('INFO')).toBe('info');
  });

  it('事件的展示文本带上作用范围', () => {
    const text = eventText({
      eventCode: 'PROMO-1',
      eventType: 'PROMOTION',
      eventTypeLabel: '大促',
      title: '黑五大促',
      detail: '折扣率 0.2',
      eventDate: '2026-10-02',
      endDate: '2026-10-02',
      shopCode: 'shop-a',
      skuCode: '',
      severity: 'INFO',
    });
    expect(text).toContain('黑五大促');
    expect(text).toContain('shop-a');
  });
});

// ================================================================ 能力声明

describe('能力声明', () => {
  it('拿不到的部分原样透出（不藏起来）', () => {
    const capabilities = {
      upstreamConnected: true,
      trendAvailable: true,
      comparisonAvailable: true,
      eventsAvailable: true,
      dataUpdatedAtVisible: true,
      upstreamModules: ['9.1.1.1 销售订单列表'],
      notDelivered: ['**「价格调整」事件**：没有上游事件源'],
      rules: [{ ruleCode: 'ANOMALY', description: '偏离 ≥ ±50%' }],
      granularities: ['DAY'],
      dimensions: ['TOTAL'],
    };
    expect(notDeliveredLines(capabilities)).toHaveLength(1);
    expect(upstreamModules(capabilities)).toHaveLength(1);
    expect(ruleNotes(capabilities)).toHaveLength(1);
  });

  it('刷新结果的状态有中文与语义色', () => {
    expect(refreshStatusLabel('SUCCEEDED')).toBe('成功');
    expect(refreshStatusLabel('FAILED')).toBe('失败');
    expect(refreshStatusLabel('TRUNCATED')).toBe('已截断');
    expect(refreshStatusTone('FAILED')).toBe('danger');
    expect(refreshStatusTone('TRUNCATED')).toBe('warning');
  });
});

// ================================================================ 常驻提醒

describe('常驻提醒', () => {
  it('提醒与口径说明原样透出', () => {
    const r = response({
      warnings: ['金额按币种分别列出，**不跨币种相加**'],
      caliberNotes: ['三个维度的口径**不可相加**'],
    });
    expect(warningsOf(r)).toHaveLength(1);
    expect(caliberNotesOf(r)).toHaveLength(1);
  });

  it('可用性标签与语义色', () => {
    expect(availabilityLabel('AVAILABLE')).toBe('有数据');
    expect(availabilityLabel('NO_DATA')).toBe('没有数据');
    expect(availabilityLabel('NOT_COMPARABLE')).toBe('不可比');
    expect(availabilityTone('AVAILABLE')).toBe('success');
    expect(availabilityTone('NO_DATA')).toBe('neutral');
  });

  it('粒度有中文标签', () => {
    expect(granularityLabel('DAY')).toBe('日');
    expect(granularityLabel('HOUR')).toBe('小时');
  });
});
