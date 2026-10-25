import { describe, expect, it } from 'vitest';
import {
  FEE_TYPE_OPTIONS,
  GRANULARITY_OPTIONS,
  amountText,
  biasDirection,
  biasLabel,
  biasShortLabel,
  biasTone,
  comparableNote,
  currentRateText,
  dataUpdatedNote,
  dataUpdatedText,
  defaultRange,
  emptyText,
  pageState,
  periodText,
  rangeText,
  rateText,
  recommendationDirectionLabel,
  recommendationTone,
  seriesBounds,
  suggestedRateText,
  unmatchedAmountText,
  varianceText,
  varianceTrendSeries,
} from './expense-variance-model';
import type {
  ExpenseVarianceRecommendation,
  ExpenseVarianceResponse,
  ExpenseVarianceRow,
  ExpenseVarianceTrendPoint,
  UnmatchedActualRow,
} from './expense-variance-model';

/**
 * 13.4.2.2 费用差异 前端模型。
 *
 * 🔴 这些断言盯的是**页面最容易说错的五件事**：
 *
 * 1. **「算不出来」≠「差异是 0」**。服务端给 `null` 的差异额 / 差异率必须渲染成「—」
 *    与「不可比」，**不是** 0.00 / 0%（假 0 会被读成「预计和实际完全一致」）。
 * 2. **符号口径固定**。差异 = 实际 − 预计：负 ⇒ 预计高估，正 ⇒ 预计低估。
 *    方向搞反了，整页的结论就是反的。
 * 3. **多币种绝不相加**。金额必须带自己的币种。
 * 4. **数据更新时间常驻**。没有投影过数据时也要有一句话，不能什么都不显示。
 * 5. **前端不做业务算术**。差异率、建议费率一律来自服务端，这里只做格式化。
 */

function row(overrides: Partial<ExpenseVarianceRow> = {}): ExpenseVarianceRow {
  return {
    feeType: 'PLATFORM_FEE',
    feeTypeLabel: '平台费',
    feeSubType: 'COMMISSION',
    periodStart: '2026-10-01',
    periodEnd: '2026-10-01',
    currency: 'USD',
    estimatedAmount: 100,
    actualAmount: 85,
    varianceAmount: -15,
    varianceRate: -15,
    comparable: true,
    estimateCount: 1,
    actualCount: 1,
    baseAmount: 1000,
    platformCode: 'AMAZON',
    siteCode: '',
    channelCode: '',
    carrierCode: '',
    shippingMethodCode: '',
    shopCode: '',
    skuCode: '',
    scopeLabel: '平台 AMAZON',
    actualSource: 'catalog',
    note: '预计与实际都取得到，可比。',
    ...overrides,
  };
}

function response(overrides: Partial<ExpenseVarianceResponse> = {}): ExpenseVarianceResponse {
  return {
    from: '2026-10-01',
    to: '2026-10-01',
    granularity: 'DAY',
    rows: [row()],
    unmatchedActuals: [],
    trend: [],
    recommendations: [],
    dataUpdatedAt: '2026-10-02T03:00:00Z',
    dataUpdatedNote: '最近一次投影落库时刻。',
    varianceSignNote: '差异 = 实际 − 预计。',
    warnings: [],
    caliberNote: '口径说明。',
    ...overrides,
  };
}

function recommendation(
  overrides: Partial<ExpenseVarianceRecommendation> = {},
): ExpenseVarianceRecommendation {
  return {
    feeType: 'PLATFORM_FEE',
    feeTypeLabel: '平台费',
    feeSubType: 'COMMISSION',
    currency: 'USD',
    scopeLabel: '平台 AMAZON',
    ownerModule: '5.2.3.1',
    direction: 'OVER_ESTIMATED',
    estimatedTotal: 300,
    actualTotal: 255,
    varianceAmount: -45,
    varianceRate: -15,
    currentRate: 0.1,
    impliedRate: 0.085,
    suggestedRate: 0.085,
    sampleCount: 3,
    suggestion: '去 5.2.3.1 校准费率。',
    reason: '连续三天高估。',
    ...overrides,
  };
}

describe('费用差异前端模型', () => {
  it('金额为 null 时显示「—」，绝不显示 0', () => {
    expect(amountText(null, 'USD')).toBe('—');
    expect(amountText(0, 'USD')).toBe('0.00 USD');
    expect(unmatchedAmountText({
      feeType: 'AD_FEE',
      feeTypeLabel: '广告费',
      feeSubType: '',
      currency: 'USD',
      actualAmount: null,
      actualCount: 1,
      scopeLabel: '全部',
      actualSource: 'finance',
      reason: '没有可比预计值。',
    })).toBe('—');
  });

  it('金额必须带自己的币种（不跨币种相加）', () => {
    expect(amountText(12.5, 'EUR')).toBe('12.50 EUR');
    expect(amountText(12.5, 'USD')).toBe('12.50 USD');
  });

  it('差异率为 null 时显示「不可比」，不是 0%', () => {
    expect(rateText(null)).toBe('不可比');
    expect(rateText(0)).toBe('0.00%');
    expect(rateText(-15)).toBe('-15.00%');
    expect(rateText(7.5)).toBe('+7.50%');
  });

  it('差异额为 null 时显示「—（算不出来）」，不是 0.00', () => {
    expect(varianceText(null, 'USD')).toBe('—（算不出来）');
    expect(varianceText(-15, 'USD')).toBe('-15.00 USD');
    expect(varianceText(7.5, 'USD')).toBe('+7.50 USD');
  });

  it('偏差方向：负差异 ⇒ 预计高估；正差异 ⇒ 预计低估', () => {
    expect(biasDirection(-15)).toBe('over');
    expect(biasDirection(15)).toBe('under');
    expect(biasDirection(0)).toBe('balanced');
    expect(biasDirection(null)).toBe('unknown');
  });

  it('不可比 ≠ 持平：两者必须给出不同的文案', () => {
    expect(biasLabel('over')).toContain('预计高估');
    expect(biasLabel('under')).toContain('预计低估');
    expect(biasLabel('balanced')).toContain('一致');
    expect(biasLabel('unknown')).toContain('不可比');
    expect(biasShortLabel('unknown')).toBe('不可比');
    expect(biasShortLabel('balanced')).toBe('一致');
  });

  it('不可比的一行必须把服务端给的原因原样带出来', () => {
    const r = row({ comparable: false, varianceAmount: null, varianceRate: null, note: '只有实际侧。' });
    expect(comparableNote(r)).toBe('只有实际侧。');
    expect(comparableNote(row())).toBe('可比');
  });

  it('建议费率算不出来时给出定性说明，不是 0', () => {
    expect(suggestedRateText(recommendation())).toBe('0.085000');
    expect(suggestedRateText(recommendation({ suggestedRate: null }))).toContain('只能给定性建议');
    expect(currentRateText(recommendation({ currentRate: null }))).toContain('算不出来');
  });

  it('建议方向标签用的是服务端词表', () => {
    expect(recommendationDirectionLabel('OVER_ESTIMATED')).toBe('预计高估');
    expect(recommendationDirectionLabel('UNDER_ESTIMATED')).toBe('预计低估');
    expect(recommendationDirectionLabel('BALANCED')).toBe('偏差在阈值内');
    expect(recommendationDirectionLabel('UNKNOWN_DIRECTION')).toBe('UNKNOWN_DIRECTION');
  });

  it('趋势上不可比的期间没有点（不是 0%）', () => {
    const points: ExpenseVarianceTrendPoint[] = [
      {
        feeType: 'PLATFORM_FEE',
        feeTypeLabel: '平台费',
        feeSubType: 'COMMISSION',
        currency: 'USD',
        periodStart: '2026-10-01',
        periodEnd: '2026-10-01',
        estimatedAmount: 100,
        actualAmount: 85,
        varianceAmount: -15,
        varianceRate: -15,
        scopeLabel: '全部',
      },
      {
        feeType: 'PLATFORM_FEE',
        feeTypeLabel: '平台费',
        feeSubType: 'COMMISSION',
        currency: 'USD',
        periodStart: '2026-10-02',
        periodEnd: '2026-10-02',
        estimatedAmount: null,
        actualAmount: 10,
        varianceAmount: null,
        varianceRate: null,
        scopeLabel: '全部',
      },
    ];
    const series = varianceTrendSeries(points);
    expect(series).toHaveLength(1);
    const line = series[0];
    expect(line?.points).toHaveLength(1);
    expect(line?.points[0]?.rate).toBe(-15);
  });

  it('趋势的取值范围始终包含 0（零线是分界，必须看得见）', () => {
    expect(seriesBounds([])).toBeNull();
    expect(seriesBounds([{ rate: -15 }])).toEqual({ min: -15, max: 0 });
    expect(seriesBounds([{ rate: -15 }, { rate: 7 }])).toEqual({ min: -15, max: 7 });
  });

  it('数据更新时间常驻：没有投影过也要有一句话', () => {
    expect(dataUpdatedText(null)).toBe('');
    expect(dataUpdatedText(response({ dataUpdatedAt: null }))).toContain('尚未投影任何费用事实');
    expect(dataUpdatedText(response())).toContain('数据截至');
    expect(dataUpdatedNote(response())).toBe('最近一次投影落库时刻。');
  });

  it('「读不到」与「没有数据」分成两种状态', () => {
    expect(pageState(null, '')).toBe('loading');
    expect(pageState(null, '服务不可用')).toBe('error');
    expect(pageState(response({ rows: [], unmatchedActuals: [] }), '')).toBe('empty');
    expect(pageState(response(), '')).toBe('ready');
    expect(emptyText(response({ rows: [], unmatchedActuals: [] }))).toContain('没有任何费用事实');
    expect(emptyText(response())).toBe('');
  });

  it('只有「有实际、无预计」的行时**不算空态**（那些行也是有内容）', () => {
    const unmatched: UnmatchedActualRow[] = [
      {
        feeType: 'AD_FEE',
        feeTypeLabel: '广告费',
        feeSubType: '',
        currency: 'USD',
        actualAmount: 42,
        actualCount: 1,
        scopeLabel: '全部',
        actualSource: 'finance',
        reason: '结算明细不带销售额基数，算不出预计值。',
      },
    ];
    const r = response({ rows: [], unmatchedActuals: unmatched });
    expect(pageState(r, '')).toBe('ready');
  });

  it('默认窗口是最近 30 天（含今天）', () => {
    expect(defaultRange('2026-10-31')).toEqual({ from: '2026-10-02', to: '2026-10-31' });
    expect(rangeText('2026-10-01', '2026-10-31')).toBe('2026-10-01 ~ 2026-10-31');
    expect(periodText('2026-10-01', '2026-10-01')).toBe('2026-10-01');
    expect(periodText('2026-10-01', '2026-10-31')).toBe('2026-10-01 ~ 2026-10-31');
  });

  it('语义色跟语义挂钩：算不出来不是错误（不是 danger）', () => {
    expect(biasTone('over')).toBe('warning');
    expect(biasTone('under')).toBe('info');
    expect(biasTone('balanced')).toBe('success');
    expect(biasTone('unknown')).toBe('neutral');
    expect(recommendationTone('OVER_ESTIMATED')).toBe('warning');
    expect(recommendationTone('UNDER_ESTIMATED')).toBe('info');
    expect(recommendationTone('BALANCED')).toBe('success');
    expect(recommendationTone('SOMETHING_ELSE')).toBe('neutral');
  });

  it('筛选项覆盖蓝图点名的四类费用 + 三档粒度', () => {
    expect(FEE_TYPE_OPTIONS.map((o) => o.value)).toEqual([
      '',
      'PLATFORM_FEE',
      'LOGISTICS_FEE',
      'AD_FEE',
      'HEAD_FREIGHT',
    ]);
    expect(GRANULARITY_OPTIONS.map((o) => o.value)).toEqual(['DAY', 'WEEK', 'MONTH']);
  });
});
