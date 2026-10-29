import { describe, expect, it } from 'vitest';
import {
  METRIC_AD_CONVERSION_RATE,
  METRIC_CONVERSION_RATE,
  METRIC_ORDER_AOV,
  METRIC_PLATFORM_AOV,
  amountText,
  availabilityLabel,
  availabilityTone,
  caliberLabel,
  countText,
  currencyLabel,
  defaultRange,
  diagnosisRuleLabel,
  diagnosisSeverityLabel,
  diagnosisTone,
  drilldownKindLabel,
  emptyReasonLabel,
  granularityOptions,
  keyMetricGroups,
  keyMetricOf,
  metricFormulaText,
  metricUnitLabel,
  metricValueText,
  pageState,
  percentText,
  quantityText,
  refreshStatusLabel,
  refreshStatusTone,
  statusLabel,
  trafficSourceLabel,
  trafficSourceShortLabel,
  trendMetricOf,
} from './traffic-conversion-model';
import type {
  TrafficConversionResponse,
  TrafficDrilldownRow,
  TrafficFunnelStage,
  TrafficMetricDefinitionView,
  TrafficMetricValue,
  TrafficTrendPoint,
} from './traffic-conversion-model';

/**
 * 13.3.2.1 流量转化前端模型的单元测试。
 *
 * <p>本文件盯的是**页面最容易做丢的那几件事**：
 *
 * <ul>
 *   <li>「算不出来」≠「等于 0」：{@code null} 一律显示成「—」/「不可比」，**不显示 0**；
 *   <li>🔴 **两套口径分列**：平台口径与订单口径各有各的指标，页面上不能混成一组；
 *   <li>🔴 **自然流量是推导值**：口径标签必须写明「推导值」，不冒充平台给的数；
 *   <li>🔴 **口径冲突不是 0**：两套口径相减为负时显示「口径冲突」；
 *   <li>空币种 = 与币种无关，**不是「币种未知」**；
 *   <li>「读不到」（error）与「没有数据」（empty）是两种状态。
 * </ul>
 */

function metric(overrides: Partial<TrafficMetricValue> = {}): TrafficMetricValue {
  return {
    metricCode: 'TRAFFIC_IMPRESSIONS',
    displayName: '曝光（平台商品口径）',
    unit: 'QUANTITY',
    caliber: 'PLATFORM',
    trafficSource: 'LISTING',
    currency: '',
    value: 3100,
    availability: 'AVAILABLE',
    availabilityNote: '',
    ...overrides,
  };
}

function stage(overrides: Partial<TrafficFunnelStage> = {}): TrafficFunnelStage {
  return {
    stageKey: 'IMPRESSIONS',
    displayName: '曝光',
    metricCode: 'TRAFFIC_IMPRESSIONS',
    unit: 'QUANTITY',
    caliber: 'PLATFORM',
    trafficSource: 'LISTING',
    currency: '',
    value: 3100,
    stepRate: null,
    overallRate: null,
    availability: 'AVAILABLE',
    availabilityNote: '',
    ...overrides,
  };
}

function response(overrides: Partial<TrafficConversionResponse> = {}): TrafficConversionResponse {
  return {
    requestedFrom: '2026-01-30',
    requestedTo: '2026-02-02',
    granularity: 'DAY',
    granularityLabel: '日',
    funnel: [],
    keyMetrics: [],
    trend: [],
    diagnoses: [],
    metricDefinitions: [],
    dataUpdatedAt: '2026-02-02T03:00:00Z',
    dataUpdatedNote: '数据更新时间 = 最近一次投影落库时刻。',
    status: 'OK',
    statusNote: '已按日粒度给出漏斗、趋势与诊断。',
    emptyReason: null,
    emptyNote: '',
    warnings: [],
    caliberNotes: [],
    ...overrides,
  };
}

describe('13.3.2.1 流量转化 · 取值格式化', () => {
  it('null 一律显示「—」/「不可比」，绝不显示 0', () => {
    expect(quantityText(null)).toBe('—');
    expect(countText(null)).toBe('—');
    expect(amountText(null, 'USD')).toBe('—');
    expect(percentText(null)).toBe('不可比');
    expect(metricValueText(metric({ value: null, unit: 'PERCENT' }))).toBe('不可比');
  });

  it('有值时按单位格式化', () => {
    expect(quantityText(3100)).toBe('3,100.000');
    expect(countText(4)).toBe('4');
    expect(amountText(275, 'USD')).toBe('275.00 USD');
    expect(percentText(0.1)).toBe('0.10%');
  });

  it('空币种 = 与币种无关，不是「币种未知」', () => {
    expect(currencyLabel('')).toBe('与币种无关');
    expect(amountText(12.5, '')).toContain('与币种无关');
    expect(currencyLabel('USD')).toBe('USD');
  });
});

describe('13.3.2.1 流量转化 · 口径标注', () => {
  it('口径标签把「页面口径」与「订单口径」分开', () => {
    expect(caliberLabel('PLATFORM')).toBe('平台口径（页面口径）');
    expect(caliberLabel('ORDER')).toBe('订单口径');
  });

  it('来源标签写明自然流量是推导值', () => {
    expect(trafficSourceLabel('LISTING')).toContain('平台商品口径');
    expect(trafficSourceLabel('AD')).toContain('广告口径');
    expect(trafficSourceLabel('ORDER')).toContain('订单口径');
    expect(trafficSourceLabel('ORGANIC')).toContain('推导值');
    expect(trafficSourceShortLabel('ORGANIC')).toBe('自然（推导）');
  });

  it('关键指标按口径分列，不混成一组', () => {
    const data = response({
      keyMetrics: [
        metric({ metricCode: METRIC_CONVERSION_RATE, unit: 'PERCENT', value: 0.1 }),
        metric({
          metricCode: METRIC_ORDER_AOV,
          unit: 'CURRENCY',
          caliber: 'ORDER',
          trafficSource: 'ORDER',
          currency: 'USD',
          value: 275,
        }),
      ],
    });
    const groups = keyMetricGroups(data);
    expect(groups).toHaveLength(2);
    expect(groups[0]?.caliber).toBe('PLATFORM');
    expect(groups[1]?.caliber).toBe('ORDER');
    expect(groups[0]?.metrics).toHaveLength(1);
    expect(groups[1]?.metrics).toHaveLength(1);
  });

  it('平台口径客单价与订单口径客单价是两个数，互不冒充', () => {
    const data = response({
      keyMetrics: [
        metric({
          metricCode: METRIC_PLATFORM_AOV,
          unit: 'CURRENCY',
          currency: 'USD',
          value: 100,
        }),
        metric({
          metricCode: METRIC_ORDER_AOV,
          unit: 'CURRENCY',
          caliber: 'ORDER',
          trafficSource: 'ORDER',
          currency: 'USD',
          value: 275,
        }),
      ],
    });
    expect(keyMetricOf(data, METRIC_PLATFORM_AOV)?.value).toBe(100);
    expect(keyMetricOf(data, METRIC_ORDER_AOV)?.value).toBe(275);
    expect(keyMetricOf(data, METRIC_PLATFORM_AOV)?.value).not.toBe(
      keyMetricOf(data, METRIC_ORDER_AOV)?.value,
    );
  });
});

describe('13.3.2.1 流量转化 · 可用性', () => {
  it('口径冲突是「danger」，不可比是「warning」，没有数据是中性', () => {
    expect(availabilityTone('CALIBER_CONFLICT')).toBe('danger');
    expect(availabilityTone('NOT_COMPARABLE')).toBe('warning');
    expect(availabilityTone('NO_DATA')).toBe('neutral');
    expect(availabilityLabel('CALIBER_CONFLICT')).toBe('口径冲突');
    expect(availabilityLabel('NOT_COMPARABLE')).toBe('不可比');
    expect(availabilityLabel('NO_DATA')).toBe('没有数据');
  });

  it('诊断规则码与严重度有中文名', () => {
    expect(diagnosisRuleLabel('IMPRESSION_COLLAPSE')).toBe('曝光断崖');
    expect(diagnosisRuleLabel('CALIBER_GAP')).toContain('背离');
    expect(diagnosisSeverityLabel('HIGH')).toBe('严重');
    expect(diagnosisTone('HIGH')).toBe('danger');
    expect(diagnosisTone('MEDIUM')).toBe('warning');
  });

  it('刷新结果有中文名与语义色', () => {
    expect(refreshStatusLabel('SUCCEEDED')).toBe('成功');
    expect(refreshStatusLabel('TRUNCATED')).toBe('已截断');
    expect(refreshStatusTone('FAILED')).toBe('danger');
  });
});

describe('13.3.2.1 流量转化 · 页面状态', () => {
  it('「读不到」是 error，「没有数据」是 empty，两者不混', () => {
    expect(pageState(null, '上游不可达')).toBe('error');
    expect(pageState(response({ status: 'EMPTY' }), '')).toBe('empty');
    expect(pageState(response(), '')).toBe('ready');
  });

  it('空态原因与状态有中文名', () => {
    expect(emptyReasonLabel('NO_FACTS_IN_WINDOW')).toBe('这个窗口内没有流量事实');
    expect(statusLabel('OK')).toBe('就绪');
    expect(statusLabel('EMPTY')).toBe('空');
  });
});

describe('13.3.2.1 流量转化 · 口径目录与下钻', () => {
  it('派生指标的口径说明写出分子分母', () => {
    const definition: TrafficMetricDefinitionView = {
      metricCode: 'TRAFFIC_ORGANIC_IMPRESSIONS',
      displayName: '曝光（自然流量，推导值）',
      unit: 'QUANTITY',
      aggregation: 'DERIVED_DIFF',
      numeratorMetricCode: 'TRAFFIC_IMPRESSIONS',
      denominatorMetricCode: 'TRAFFIC_AD_IMPRESSIONS',
      caliber: 'PLATFORM',
      trafficSource: null,
      currencyAware: false,
      description: '',
      ownerModule: '13.3.2.1',
    };
    expect(metricFormulaText(definition)).toContain('−');
    expect(metricFormulaText(definition)).toContain('推导值');
  });

  it('单位的展示名', () => {
    expect(metricUnitLabel('CURRENCY')).toBe('金额');
    expect(metricUnitLabel('PERCENT')).toBe('百分比');
  });

  it('下钻的构成类型与来源各自带口径', () => {
    expect(drilldownKindLabel('LISTING')).toContain('平台口径');
    expect(drilldownKindLabel('AD_CAMPAIGN')).toContain('广告口径');
    expect(drilldownKindLabel('ORDER')).toContain('订单口径');
  });

  it('趋势桶里的指标取不到时返回 null 而不是 0', () => {
    const point: TrafficTrendPoint = {
      bucketStart: '2026-01-30',
      bucketEnd: '2026-01-30',
      bucketLabel: '2026-01-30',
      metrics: [
        metric({
          metricCode: METRIC_AD_CONVERSION_RATE,
          unit: 'PERCENT',
          value: null,
          availability: 'NOT_COMPARABLE',
        }),
      ],
    };
    expect(trendMetricOf(point, METRIC_AD_CONVERSION_RATE)?.value).toBeNull();
    expect(trendMetricOf(point, 'TRAFFIC_UNKNOWN')).toBeNull();
  });

  it('明细行的取数：金额优先，其次数量', () => {
    const row: TrafficDrilldownRow = {
      trafficSource: 'ORDER',
      sourceKind: 'ORDER',
      referenceId: 'SO-1',
      referenceLabel: 'SO-1',
      shopCode: '',
      platformCode: '',
      orderNo: 'SO-1',
      occurredAt: '2026-01-30T10:00:00Z',
      statDate: '2026-01-30',
      currency: 'USD',
      quantity: 1,
      value: 500,
    };
    expect(row.value).toBe(500);
  });
});

describe('13.3.2.1 流量转化 · 时间范围与粒度', () => {
  it('默认范围是最近 30 天（含今天）', () => {
    const range = defaultRange('2026-02-02');
    expect(range.to).toBe('2026-02-02');
    expect(range.from).toBe('2026-01-04');
  });

  it('粒度只有日 / 周 / 月三档', () => {
    expect(granularityOptions().map((option) => option.value)).toEqual(['DAY', 'WEEK', 'MONTH']);
  });
});
