import { describe, expect, it } from 'vitest';

import {
  EMPTY_ACTUAL_FILTERS,
  EMPTY_RULE_FILTERS,
  PLATFORM_FEE_SECTIONS,
  actualSourceLabel,
  allocationBasisLabel,
  chargeBasisLabel,
  feeTypeLabel,
  moneyText,
  moneyWithCurrency,
  rateText,
  ruleScopeText,
  timeText,
  validateActual,
  validateRule,
  validateVoidReason,
  varianceHeadline,
  varianceText,
  varianceTone,
} from './platform-fee-model';
import type {
  FeeActualInput,
  FeeRuleInput,
  FeeRuleRow,
  FeeVarianceReport,
} from './platform-fee-model';

function rule(over: Partial<FeeRuleInput> = {}): FeeRuleInput {
  return {
    platformCode: 'AMAZON',
    siteCode: 'US',
    internalCategoryId: null,
    platformCategoryId: null,
    feeType: 'COMMISSION',
    chargeBasis: 'PERCENTAGE_OF_SALE',
    rate: '0.15',
    fixedAmount: null,
    currency: null,
    periodTotalAmount: null,
    periodCurrency: null,
    allocationBasis: null,
    allocationDivisor: null,
    effectiveFrom: '2026-03-01T00:00:00Z',
    effectiveTo: null,
    enabled: true,
    note: '',
    version: null,
    ...over,
  };
}

function actual(over: Partial<FeeActualInput> = {}): FeeActualInput {
  return {
    platformCode: 'AMAZON',
    siteCode: 'US',
    internalCategoryId: null,
    platformCategoryId: null,
    feeType: 'COMMISSION',
    amount: '4.50',
    currency: 'USD',
    saleAmount: '24.80',
    quantity: '1',
    occurredOn: '2026-03-01',
    sourceRef: '',
    note: '',
    ...over,
  };
}

describe('5.2.3.1 平台费用前端模型', () => {
  it('费率是小数口径，显示成百分比', () => {
    expect(rateText('0.15')).toBe('15%');
    expect(rateText('0.125')).toBe('12.5%');
    expect(rateText('0.0825')).toBe('8.25%');
    expect(rateText(null)).toBe('—');
  });

  it('算不出来渲染成「算不出来」而不是 0.00', () => {
    expect(moneyText(null)).toBe('算不出来');
    expect(moneyText('')).toBe('算不出来');
    expect(moneyText('3.7200')).toBe('3.7200');
    expect(moneyWithCurrency(null, 'USD')).toBe('算不出来');
    expect(moneyWithCurrency('3.7200', 'USD')).toBe('USD 3.7200');
  });

  it('费用类型与计价形态的兜底文案', () => {
    expect(feeTypeLabel('COMMISSION')).toBe('平台佣金');
    expect(feeTypeLabel('REFERRAL')).toBe('Referral Fee');
    expect(feeTypeLabel('SUBSCRIPTION')).toBe('订阅费');
    expect(feeTypeLabel(null)).toBe('未维护');
    expect(chargeBasisLabel('TIERED_PERCENTAGE')).toBe('阶梯比例');
    expect(allocationBasisLabel('PER_ORDER')).toBe('按单分摊');
  });

  it('实际费用的来源明写是人工登记，不冒充 12 的结算对账', () => {
    expect(actualSourceLabel('MANUAL_OBSERVATION')).toBe('人工登记的平台账单观察值');
    expect(actualSourceLabel('MANUAL_OBSERVATION')).not.toContain('对账');
  });

  it('规则的作用域一句话读得懂', () => {
    const row = {
      platformCode: 'AMAZON',
      siteCode: 'US',
      internalCategoryName: '玩具',
      platformCategoryName: null,
    } as FeeRuleRow;
    expect(ruleScopeText(row)).toContain('平台 AMAZON');
    expect(ruleScopeText(row)).toContain('站点 US');
    expect(ruleScopeText(row)).toContain('玩具');
    expect(ruleScopeText({ ...row, siteCode: null })).toContain('全部站点');
  });

  it('费率超出 0..1 被本地校验拒掉', () => {
    expect(validateRule(rule({ rate: '15' })).some((p) => p.includes('0 到 1'))).toBe(true);
    expect(validateRule(rule({ rate: '0.15' }))).toEqual([]);
  });

  it('订阅费必须填周期总额与分摊基数', () => {
    const problems = validateRule(
      rule({
        feeType: 'SUBSCRIPTION',
        chargeBasis: 'FIXED_PER_ORDER',
        rate: null,
        periodTotalAmount: null,
        periodCurrency: null,
        allocationBasis: null,
        allocationDivisor: null,
      }),
    );
    expect(problems.some((p) => p.includes('周期总额'))).toBe(true);
    expect(problems.some((p) => p.includes('分摊基数'))).toBe(true);
  });

  it('订阅费填了周期总额与分摊基数就通过', () => {
    expect(
      validateRule(
        rule({
          feeType: 'SUBSCRIPTION',
          chargeBasis: 'FIXED_PER_ORDER',
          rate: null,
          periodTotalAmount: '39.99',
          periodCurrency: 'USD',
          allocationBasis: 'PER_ORDER',
          allocationDivisor: '100',
        }),
      ),
    ).toEqual([]);
  });

  it('实际费用必须填销售额基数与数量基数（否则差异算不出来）', () => {
    const problems = validateActual(actual({ saleAmount: '', quantity: '' }));
    expect(problems.some((p) => p.includes('销售额基数'))).toBe(true);
    expect(problems.some((p) => p.includes('数量基数'))).toBe(true);
  });

  it('实际费用填全了就通过', () => {
    expect(validateActual(actual())).toEqual([]);
  });

  it('作废理由必填', () => {
    expect(validateVoidReason('  ')).toHaveLength(1);
    expect(validateVoidReason('录错了')).toEqual([]);
  });

  it('差异算不出来时明说算不出来，不显示 0', () => {
    const report: FeeVarianceReport = {
      available: false,
      reason: '这个区间没有实际费用记录，费用差异算不出来。',
      platformCode: 'AMAZON',
      siteCode: 'US',
      from: '2026-03-01',
      to: '2026-03-31',
      groups: [],
      note: '',
    };
    const headline = varianceHeadline(report);
    expect(headline.text).toContain('无法计算费用差异');
    expect(headline.text).not.toContain('0.00');
  });

  it('实际多收时差异标红、少收时标黄、一致时标绿', () => {
    const over = {
      feeType: 'COMMISSION',
      feeTypeLabel: '平台佣金',
      currency: 'USD',
      actualAmount: '4.5000',
      estimatedAmount: '3.7200',
      varianceAmount: '0.7800',
      actualCount: 1,
      comparableCount: 1,
      note: '',
    };
    const under = { ...over, varianceAmount: '-0.7800' };
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

  it('页面分区里 5.2.3.2 显式标成未交付', () => {
    const logistics = PLATFORM_FEE_SECTIONS.find((s) => s.moduleId === '5.2.3.2');
    expect(logistics?.delivered).toBe(false);
    expect(logistics?.summary).toContain('未交付');
    const platform = PLATFORM_FEE_SECTIONS.find((s) => s.moduleId === '5.2.3.1');
    expect(platform?.delivered).toBe(true);
  });

  it('默认筛选是空条件 + 第一页', () => {
    expect(EMPTY_RULE_FILTERS.page).toBe(0);
    expect(EMPTY_RULE_FILTERS.keyword).toBe('');
    expect(EMPTY_ACTUAL_FILTERS.page).toBe(0);
  });

  it('时刻展示只到秒', () => {
    expect(timeText('2026-03-01T08:30:00Z')).toBe('2026-03-01 08:30:00Z');
    expect(timeText(null)).toBe('—');
  });
});
