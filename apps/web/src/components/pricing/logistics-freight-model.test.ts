import { describe, expect, it } from 'vitest';

import {
  EMPTY_FREIGHT_ACTUAL_FILTERS,
  EMPTY_FREIGHT_RULE_FILTERS,
  EMPTY_FREIGHT_SURCHARGE_FILTERS,
  EMPTY_HEAD_STRUCTURE_FILTERS,
  actualSourceLabel,
  chargeBasisLabel,
  dayText,
  headCalcBasisLabel,
  headComponentTypeLabel,
  headScopeText,
  moneyText,
  moneyWithCurrency,
  priceModeLabel,
  rateText,
  ruleScopeText,
  statusLabel,
  surchargeBasisLabel,
  surchargeTypeLabel,
  timeText,
  validateActual,
  validateDisableReason,
  validateHeadStructure,
  validateRule,
  validateSurcharge,
  varianceHeadline,
  varianceText,
  varianceTone,
} from './logistics-freight-model';
import type {
  FreightActualInput,
  FreightRuleInput,
  FreightRuleRow,
  FreightTierInput,
  FreightVarianceReport,
  HeadFreightStructureInput,
} from './logistics-freight-model';

function tier(over: Partial<FreightTierInput> = {}): FreightTierInput {
  return {
    seq: 1,
    minValue: '0',
    maxValue: '30',
    priceMode: 'FIRST_PLUS_ADDITIONAL',
    flatPrice: null,
    firstLimit: '1',
    firstPrice: '12.00',
    additionalPrice: '5.00',
    ...over,
  };
}

function rule(over: Partial<FreightRuleInput> = {}): FreightRuleInput {
  return {
    channelCode: 'YUNDA',
    channelName: '韵达',
    carrierCode: null,
    carrierName: null,
    shippingMethodCode: 'EXPRESS',
    shippingMethodName: '快递',
    chargeBasis: 'BY_WEIGHT',
    currency: 'CNY',
    volumetricDivisor: null,
    minimumCharge: null,
    effectiveFrom: '2026-03-01T00:00:00Z',
    effectiveTo: null,
    note: '',
    tiers: [tier()],
    ...over,
  };
}

function actual(over: Partial<FreightActualInput> = {}): FreightActualInput {
  return {
    channelCode: 'YUNDA',
    channelName: '韵达',
    carrierCode: null,
    carrierName: null,
    shippingMethodCode: 'EXPRESS',
    shippingMethodName: '快递',
    chargeBasis: 'BY_WEIGHT',
    amount: '30.00',
    baseAmount: null,
    surchargeAmount: null,
    currency: 'CNY',
    chargeableQuantity: '3.5',
    quantity: '1',
    occurredOn: '2026-03-01',
    sourceRef: '',
    note: '',
    ...over,
  };
}

function headStructure(over: Partial<HeadFreightStructureInput> = {}): HeadFreightStructureInput {
  return {
    shippingMethodCode: 'SEA',
    shippingMethodName: '海运',
    originCode: 'CN-SZ',
    originName: '深圳',
    destinationCode: 'US-LAX',
    destinationName: '洛杉矶',
    currency: 'CNY',
    effectiveFrom: '2026-03-01T00:00:00Z',
    effectiveTo: null,
    note: '',
    components: [
      {
        seq: 1,
        componentType: 'LINE_HAUL',
        label: '海运费',
        calcBasis: 'PER_CBM',
        unitAmount: '120.00',
        currency: 'CNY',
      },
    ],
    ...over,
  };
}

describe('5.2.3.2 物流与头程费用前端模型', () => {
  it('费率是小数口径，显示成百分比', () => {
    expect(rateText('0.15')).toBe('15%');
    expect(rateText('0.0825')).toBe('8.25%');
    expect(rateText(null)).toBe('—');
  });

  it('算不出来渲染成「算不出来」而不是 0.00', () => {
    expect(moneyText(null)).toBe('算不出来');
    expect(moneyText('')).toBe('算不出来');
    expect(moneyText('24.5000')).toBe('24.5000');
    expect(moneyWithCurrency(null, 'CNY')).toBe('算不出来');
    expect(moneyWithCurrency('24.5000', 'CNY')).toBe('CNY 24.5000');
  });

  it('词表兜底文案', () => {
    expect(chargeBasisLabel('BY_WEIGHT')).toBe('按重量');
    expect(chargeBasisLabel('BY_VOLUME')).toBe('按体积');
    expect(chargeBasisLabel('BY_PIECE')).toBe('按件数');
    expect(surchargeTypeLabel('FUEL')).toBe('燃油附加');
    expect(surchargeTypeLabel('PEAK_SEASON')).toBe('旺季附加');
    expect(surchargeTypeLabel('REMOTE_AREA')).toBe('偏远附加');
    expect(surchargeBasisLabel('PERCENT_OF_BASE_FREIGHT')).toBe('基础运费比例');
    expect(priceModeLabel('FLAT_PER_TIER')).toBe('整档一口价');
    expect(priceModeLabel('FIRST_PLUS_ADDITIONAL')).toBe('首段加续段');
    expect(headComponentTypeLabel('LINE_HAUL')).toBe('主运费');
    expect(headCalcBasisLabel('PER_CBM')).toBe('每立方米');
    expect(statusLabel('ACTIVE')).toBe('生效中');
    expect(statusLabel('SUPERSEDED')).toBe('已被取代');
    expect(statusLabel('DISABLED')).toBe('已停用');
  });

  it('实际运费的来源明写是人工登记，不冒充上游的结算结果', () => {
    expect(actualSourceLabel('MANUAL_OBSERVATION')).toBe('人工登记的物流商账单观察值');
    expect(actualSourceLabel('MANUAL_OBSERVATION')).not.toContain('对账');
  });

  it('规则与头程结构的作用域一句话读得懂', () => {
    const row = {
      channelCode: 'YUNDA',
      carrierCode: null,
      shippingMethodCode: 'EXPRESS',
      chargeBasis: 'BY_WEIGHT',
    } as FreightRuleRow;
    expect(ruleScopeText(row)).toContain('渠道 YUNDA');
    expect(ruleScopeText(row)).toContain('通用承运商');
    expect(ruleScopeText(row)).toContain('运输方式 EXPRESS');
    expect(ruleScopeText(row)).toContain('按重量');
    expect(ruleScopeText({ ...row, carrierCode: 'SF' })).toContain('承运商 SF');

    const structure = {
      shippingMethodCode: 'SEA',
      originCode: 'CN-SZ',
      destinationCode: 'US-LAX',
    } as never;
    expect(headScopeText(structure)).toContain('CN-SZ');
    expect(headScopeText(structure)).toContain('US-LAX');
  });

  it('规则必须至少有一档阶梯', () => {
    const problems = validateRule(rule({ tiers: [] }));
    expect(problems.some((p) => p.includes('至少要有一档阶梯'))).toBe(true);
  });

  it('阶梯必须首尾相接', () => {
    const problems = validateRule(
      rule({
        tiers: [
          tier({ seq: 1, minValue: '0', maxValue: '1' }),
          tier({ seq: 2, minValue: '2', maxValue: null, firstLimit: '3' }),
        ],
      }),
    );
    expect(problems.some((p) => p.includes('首尾相接'))).toBe(true);
  });

  it('体积重换算系数只对按重量有意义', () => {
    const problems = validateRule(rule({ chargeBasis: 'BY_PIECE', volumetricDivisor: '5000' }));
    expect(problems.some((p) => p.includes('体积重换算系数只对'))).toBe(true);
  });

  it('档位形态与参数必须互斥', () => {
    const problems = validateRule(
      rule({
        tiers: [
          tier({
            priceMode: 'FLAT_PER_TIER',
            flatPrice: '15.00',
            firstLimit: null,
            firstPrice: null,
            additionalPrice: null,
          }),
        ],
      }),
    );
    expect(problems).toEqual([]);
    const bad = validateRule(
      rule({
        tiers: [tier({ priceMode: 'FLAT_PER_TIER', flatPrice: null })],
      }),
    );
    expect(bad.some((p) => p.includes('必须填整档价'))).toBe(true);
  });

  it('附加费的形态与参数必须互斥且费率在 0..1', () => {
    const percent = validateSurcharge({
      channelCode: 'YUNDA',
      channelName: null,
      carrierCode: null,
      carrierName: null,
      shippingMethodCode: 'EXPRESS',
      shippingMethodName: null,
      surchargeType: 'FUEL',
      calcBasis: 'PERCENT_OF_BASE_FREIGHT',
      rate: '0.15',
      fixedAmount: null,
      currency: 'CNY',
      effectiveFrom: '2026-03-01T00:00:00Z',
      effectiveTo: null,
      note: '',
    });
    expect(percent).toEqual([]);
    const mixed = validateSurcharge({
      channelCode: 'YUNDA',
      channelName: null,
      carrierCode: null,
      carrierName: null,
      shippingMethodCode: 'EXPRESS',
      shippingMethodName: null,
      surchargeType: 'FUEL',
      calcBasis: 'PERCENT_OF_BASE_FREIGHT',
      rate: '0.15',
      fixedAmount: '3',
      currency: 'CNY',
      effectiveFrom: '2026-03-01T00:00:00Z',
      effectiveTo: null,
      note: '',
    });
    expect(mixed.some((p) => p.includes('不填固定额'))).toBe(true);
    const outOfRange = validateSurcharge({
      channelCode: 'YUNDA',
      channelName: null,
      carrierCode: null,
      carrierName: null,
      shippingMethodCode: 'EXPRESS',
      shippingMethodName: null,
      surchargeType: 'FUEL',
      calcBasis: 'PERCENT_OF_BASE_FREIGHT',
      rate: '15',
      fixedAmount: null,
      currency: 'CNY',
      effectiveFrom: '2026-03-01T00:00:00Z',
      effectiveTo: null,
      note: '',
    });
    expect(outOfRange.some((p) => p.includes('0 到 1'))).toBe(true);
  });

  it('头程费用结构至少要有一个成本项', () => {
    const problems = validateHeadStructure(headStructure({ components: [] }));
    expect(problems.some((p) => p.includes('至少要有一个成本项'))).toBe(true);
    expect(validateHeadStructure(headStructure())).toEqual([]);
  });

  it('实际运费必须填计费量与件数（否则差异算不出来）', () => {
    const problems = validateActual(actual({ chargeableQuantity: '', quantity: '' }));
    expect(problems.some((p) => p.includes('计费量'))).toBe(true);
    expect(problems.some((p) => p.includes('件数'))).toBe(true);
    expect(validateActual(actual())).toEqual([]);
  });

  it('停用 / 作废理由必填', () => {
    expect(validateDisableReason('  ')).toHaveLength(1);
    expect(validateDisableReason('渠道换了合作方')).toEqual([]);
  });

  it('差异算不出来时明说算不出来，不显示 0', () => {
    const report: FreightVarianceReport = {
      available: false,
      reason: '这个区间没有实际运费记录，运费差异算不出来。',
      from: '2026-03-01',
      to: '2026-03-31',
      groups: [],
      note: '',
    };
    const headline = varianceHeadline(report);
    expect(headline.text).toContain('无法计算运费差异');
    expect(headline.text).not.toContain('0.00');
  });

  it('实际多收时差异标红、少收时标黄、一致时标绿', () => {
    const over = {
      channelCode: 'YUNDA',
      shippingMethodCode: 'EXPRESS',
      currency: 'CNY',
      actualAmount: '30.0000',
      estimatedAmount: '24.5000',
      varianceAmount: '5.5000',
      actualCount: 1,
      comparableCount: 1,
      note: '',
    };
    const under = { ...over, varianceAmount: '-5.5000' };
    const same = { ...over, varianceAmount: '0.0000' };
    const unknown = { ...over, varianceAmount: null };
    expect(varianceTone(over)).toBe('danger');
    expect(varianceTone(under)).toBe('warning');
    expect(varianceTone(same)).toBe('success');
    expect(varianceTone(unknown)).toBe('warning');
    expect(varianceText(over)).toContain('多收');
    expect(varianceText(under)).toContain('少收');
    expect(varianceText(unknown)).toContain('算不出来');
  });

  it('默认筛选是空条件 + 第一页', () => {
    expect(EMPTY_FREIGHT_RULE_FILTERS.page).toBe(0);
    expect(EMPTY_FREIGHT_RULE_FILTERS.keyword).toBe('');
    expect(EMPTY_FREIGHT_SURCHARGE_FILTERS.page).toBe(0);
    expect(EMPTY_HEAD_STRUCTURE_FILTERS.page).toBe(0);
    expect(EMPTY_FREIGHT_ACTUAL_FILTERS.page).toBe(0);
  });

  it('时刻与日期展示', () => {
    expect(timeText('2026-03-01T08:30:00Z')).toBe('2026-03-01 08:30:00Z');
    expect(timeText(null)).toBe('—');
    expect(dayText('2026-03-01T08:30:00Z')).toBe('2026-03-01');
    expect(dayText(null)).toBe('—');
  });
});
