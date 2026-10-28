import { describe, expect, it } from 'vitest';
import {
  METRIC_ADD_TO_CART,
  METRIC_ATTRIBUTED_AOV,
  METRIC_ATTRIBUTED_GMV,
  METRIC_ATTRIBUTED_ORDERS,
  METRIC_CLICKS,
  METRIC_CTR,
  METRIC_IMPRESSIONS,
  METRIC_ROI,
  METRIC_SPEND,
  amountText,
  attributionModelLabel,
  availabilityLabel,
  availabilityTone,
  caliberLabel,
  commissionBasisLabel,
  countText,
  currencyBreakdown,
  currencyLabel,
  dataUpdatedNote,
  dataUpdatedText,
  defaultRange,
  emptyReasonLabel,
  emptyText,
  metricFormulaText,
  metricOf,
  metricValueText,
  pageState,
  percentText,
  poolWeightBasisLabel,
  quantityText,
  rangeText,
  refundTreatmentLabel,
  ruleSummary,
  slotTypeLabel,
  slotTypeTone,
  statusLabel,
} from './affiliate-marketing-model';
import type {
  AffiliateMarketingMetricDefinitionView,
  AffiliateMarketingMetricValue,
  AffiliateMarketingResponse,
  AffiliateMarketingSlotRow,
} from './affiliate-marketing-model';

/**
 * 13.3.2.2 关联营销效果 前端模型的测试。
 *
 * <p>🔴 这里守的是**展示纪律**，不是业务算术：
 * 「算不出来」不许变成 0、「本轮不可用」不许变成 0、
 * 币种空串不许被渲染成「币种未知」、归因规则必须能被一眼看见。
 */

function metric(
  overrides: Partial<AffiliateMarketingMetricValue> = {},
): AffiliateMarketingMetricValue {
  return {
    metricCode: METRIC_IMPRESSIONS,
    displayName: '曝光',
    unit: 'QUANTITY',
    currency: '',
    value: null,
    quantity: null,
    availability: 'AVAILABLE',
    availabilityNote: '',
    ...overrides,
  };
}

function slot(overrides: Partial<AffiliateMarketingSlotRow> = {}): AffiliateMarketingSlotRow {
  return {
    slotType: 'SOCIAL_MEDIA',
    slotTypeName: '社交媒体',
    slotKey: 'inf-1:camp-1',
    slotLabel: '达人甲 × 春季活动',
    caliber: 'INFLUENCER_ATTRIBUTION',
    caliberName: '达人归因口径',
    shopCode: 'shop-a',
    platformCode: '',
    attributionRuleCode: 'RULE-1',
    attributionModel: 'COOPERATION_FULL',
    poolWeightBasis: 'FOLLOWER_SHARE',
    attributionWindowDays: 7,
    refundTreatment: 'GROSS',
    commissionBasis: 'COOPERATION_COMMISSION',
    attributionRecordCount: 3,
    metrics: [],
    ...overrides,
  };
}

function response(
  overrides: Partial<AffiliateMarketingResponse> = {},
): AffiliateMarketingResponse {
  return {
    from: '2026-01-30',
    to: '2026-01-31',
    slots: [],
    attributionRules: [],
    slotTypes: [],
    metricDefinitions: [],
    dataUpdatedAt: null,
    dataUpdatedNote: '',
    status: 'OK',
    statusNote: '',
    emptyReason: null,
    emptyNote: '',
    warnings: [],
    caliberNotes: [],
    ...overrides,
  } as AffiliateMarketingResponse;
}

describe('关联营销效果 · 展示格式化', () => {
  it('算不出来显示「—」而不是 0', () => {
    expect(amountText(null, 'USD')).toBe('—');
    expect(quantityText(null)).toBe('—');
    expect(countText(null)).toBe('—');
  });

  it('比率算不出来显示「不可比」而不是 0%', () => {
    expect(percentText(null)).toBe('不可比');
    expect(percentText(0.1067)).toBe('10.67%');
  });

  it('空币种显示「与币种无关」而不是「币种未知」', () => {
    expect(currencyLabel('')).toBe('与币种无关');
    expect(currencyLabel('USD')).toBe('USD');
    expect(amountText(12.5, '')).toContain('与币种无关');
  });

  it('金额带币种码', () => {
    expect(amountText(1000, 'USD')).toBe('1,000.00 USD');
  });
});

describe('关联营销效果 · 口径与规则标签', () => {
  it('两套口径各有各的标签', () => {
    expect(caliberLabel('INFLUENCER_ATTRIBUTION')).toBe('达人归因口径');
    expect(caliberLabel('LANDING_LISTING')).toBe('落地商品口径');
  });

  it('归因模型逐字对应上游词表（不是末次点击 / 首次点击）', () => {
    expect(attributionModelLabel('COOPERATION_FULL')).toBe('合作全额归因');
    expect(attributionModelLabel('CAMPAIGN_POOL_SPLIT')).toBe('活动池分摊');
    expect(attributionModelLabel('LAST_CLICK')).toBe('LAST_CLICK');
  });

  it('规则摘要里能看见规则码 / 归因窗口 / 退款口径 / 佣金口径', () => {
    const summary = ruleSummary(slot());
    expect(summary).toContain('RULE-1');
    expect(summary).toContain('归因窗口 7 天');
    expect(summary).toContain('毛额');
    expect(summary).toContain('按合作佣金');
  });

  it('没有归因规则的行明说是「无归因规则」', () => {
    expect(ruleSummary(slot({ attributionRuleCode: '' }))).toContain('无归因规则');
  });

  it('其它规则字段也各有文案', () => {
    expect(poolWeightBasisLabel('FOLLOWER_SHARE')).toBe('粉丝份额');
    expect(refundTreatmentLabel('NET')).toBe('净额（扣退款）');
    expect(commissionBasisLabel('RATE_ON_ATTRIBUTED_GMV')).toBe('按归因成交额 × 比例');
    expect(slotTypeLabel('A_PLUS')).toBe('A+');
    expect(slotTypeLabel('STORE_TRAFFIC')).toBe('Store 导流');
  });
});

describe('关联营销效果 · 可用性', () => {
  it('本轮不可用与算不出来都**不是**「可用」', () => {
    expect(availabilityTone('NOT_AVAILABLE')).not.toBe('success');
    expect(availabilityTone('NOT_COMPARABLE')).not.toBe('success');
    expect(availabilityTone('AVAILABLE')).toBe('success');
  });

  it('本轮不可用有专门的文案', () => {
    expect(availabilityLabel('NOT_AVAILABLE')).toBe('本轮不可用');
    expect(availabilityLabel('NOT_COMPARABLE')).toBe('不可算');
    expect(availabilityLabel('NO_DATA')).toBe('没有数据');
  });

  it('营销位类型不可用时语义色是警告', () => {
    expect(slotTypeTone(false)).toBe('warning');
    expect(slotTypeTone(true)).toBe('success');
  });
});

describe('关联营销效果 · 指标取值', () => {
  it('金额型取金额、数量型取数量', () => {
    expect(
      metricValueText(
        metric({
          metricCode: METRIC_ATTRIBUTED_GMV,
          unit: 'CURRENCY',
          currency: 'USD',
          value: 1000,
          quantity: null,
        }),
      ),
    ).toBe('1,000.00 USD');
    expect(
      metricValueText(
        metric({
          metricCode: METRIC_ATTRIBUTED_ORDERS,
          unit: 'COUNT',
          currency: '',
          value: null,
          quantity: 10,
        }),
      ),
    ).toBe('10');
  });

  it('派生比率取数量列', () => {
    expect(
      metricValueText(
        metric({
          metricCode: METRIC_CTR,
          unit: 'PERCENT',
          value: null,
          quantity: 0.1067,
        }),
      ),
    ).toBe('10.67%');
  });

  it('派生指标算不出来时显示「不可比」', () => {
    expect(
      metricValueText(
        metric({
          metricCode: METRIC_ROI,
          unit: 'CURRENCY',
          currency: 'USD',
          value: null,
          quantity: null,
        }),
      ),
    ).toBe('—');
  });
});

describe('关联营销效果 · 行内查找', () => {
  const row = slot({
    metrics: [
      metric({ metricCode: METRIC_ATTRIBUTED_GMV, unit: 'CURRENCY', currency: 'USD', value: 200 }),
      metric({ metricCode: METRIC_ATTRIBUTED_GMV, unit: 'CURRENCY', currency: 'EUR', value: 300 }),
      metric({ metricCode: METRIC_ATTRIBUTED_ORDERS, unit: 'COUNT', quantity: 5 }),
    ],
  });

  it('按币种取金额（不同币种的数不许互相冒充）', () => {
    expect(metricOf(row, METRIC_ATTRIBUTED_GMV, 'USD')?.value).toBe(200);
    expect(metricOf(row, METRIC_ATTRIBUTED_GMV, 'EUR')?.value).toBe(300);
    expect(metricOf(row, METRIC_ATTRIBUTED_GMV, 'JPY')).toBeNull();
  });

  it('币种分列看得见', () => {
    expect(currencyBreakdown(row, METRIC_ATTRIBUTED_GMV).map((entry) => entry.currency)).toEqual([
      'USD',
      'EUR',
    ]);
  });
});

describe('关联营销效果 · 页面状态', () => {
  it('读不到 / 加载中 / 空 / 就绪是四个不同的状态', () => {
    expect(pageState(null, 'boom')).toBe('error');
    expect(pageState(null, '')).toBe('loading');
    expect(pageState(response({ status: 'EMPTY' }), '')).toBe('empty');
    expect(pageState(response({ status: 'OK' }), '')).toBe('ready');
  });

  it('空态有专门的说明', () => {
    expect(emptyReasonLabel('NO_FACTS_IN_WINDOW')).toContain('没有关联营销事实');
    expect(emptyText(response({ emptyNote: '这段时间没投放' }))).toBe('这段时间没投放');
    expect(statusLabel('OK')).toBe('就绪');
    expect(statusLabel('EMPTY')).toBe('空');
  });

  it('没有投影数据时不给假的时间', () => {
    expect(dataUpdatedText(response({ dataUpdatedAt: null }))).toBe('尚未投影任何数据');
    expect(dataUpdatedText(null)).toBe('尚未读取数据');
    expect(dataUpdatedNote(response({ dataUpdatedNote: '报表是投影' }))).toBe('报表是投影');
  });
});

describe('关联营销效果 · 口径目录与时间范围', () => {
  it('派生指标只写分子分母引用', () => {
    const definition: AffiliateMarketingMetricDefinitionView = {
      metricCode: METRIC_ROI,
      displayName: 'ROI',
      unit: 'CURRENCY',
      aggregation: 'DERIVED_RATIO',
      numeratorMetricCode: METRIC_ATTRIBUTED_GMV,
      denominatorMetricCode: METRIC_SPEND,
      currencyAware: true,
      attributionBound: false,
      available: true,
      ownerModule: '13.3.2.2',
      description: '同币种、同一条归因规则',
    };
    expect(metricFormulaText(definition)).toBe(
      `${METRIC_ATTRIBUTED_GMV} ÷ ${METRIC_SPEND}`,
    );
  });

  it('汇总型指标显示「窗口内相加」', () => {
    const definition: AffiliateMarketingMetricDefinitionView = {
      metricCode: METRIC_ADD_TO_CART,
      displayName: '加购',
      unit: 'QUANTITY',
      aggregation: 'SUM',
      numeratorMetricCode: null,
      denominatorMetricCode: null,
      currencyAware: false,
      attributionBound: false,
      available: false,
      ownerModule: '2.1.2.1',
      description: '本轮不可用',
    };
    expect(metricFormulaText(definition)).toBe('窗口内相加');
  });

  it('默认时间范围是最近 30 天（含今天）', () => {
    expect(defaultRange('2026-02-01')).toEqual({ from: '2026-01-03', to: '2026-02-01' });
    expect(rangeText('2026-01-30', '2026-01-31')).toBe('2026-01-30 ~ 2026-01-31');
  });
});
