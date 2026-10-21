import { describe, expect, it } from 'vitest';

import {
  adjustmentTone,
  assumptionGaps,
  baseMethodLabel,
  baseRateText,
  decisionTone,
  emptyApply,
  emptyTemplate,
  hasTurnoverRule,
  missingGaps,
  moneyText,
  moneyWithCurrency,
  percentText,
  productStageLabel,
  quantityText,
  scopeTypeLabel,
  suggestedPriceText,
  summaryStats,
  toTemplateInput,
  validateApply,
  validateTemplate,
} from './pricing-template-model';
import type { ApplyResult, TemplateDetail, TemplateInput } from './pricing-template-model';

/**
 * 5.3.2.1 定价模板前端模型的纯函数断言。
 *
 * <p>这些函数守的是蓝图那条加粗句的**技术落点**：
 * 「模板是"怎么算"，价目表是"算完是多少"」——
 * 以及它旁边两条隐含纪律：
 * 「读不到 ≠ 确实是 0」「建议价 ≠ 生效价」。
 */

function template(overrides: Partial<TemplateDetail> = {}): TemplateDetail {
  return {
    summary: {
      id: '11111111-1111-1111-1111-111111111111',
      code: 'OLD-PLUS-30',
      name: '老品加价 30%',
      baseMethod: 'COST_PLUS',
      baseMethodLabel: '成本加成',
      baseRate: 0.3,
      productStage: 'EXISTING',
      productStageLabel: '老品',
      currency: 'USD',
      competitorReferencePrice: null,
      enabled: true,
      effectiveFrom: '2026-10-01T00:00Z',
      effectiveTo: null,
      adjustmentCount: 1,
      scopeCount: 1,
      createdBy: null,
      updatedAt: '2026-10-01T00:00Z',
    },
    description: '夹具',
    baseMethod: 'COST_PLUS',
    baseRate: 0.3,
    competitorReferencePrice: null,
    competitorCurrency: null,
    productStage: 'EXISTING',
    currency: 'USD',
    platformFeeRate: 0.15,
    platformFeeFixed: null,
    logisticsCostPerUnit: 5,
    costCurrency: null,
    fxRate: null,
    enabled: true,
    effectiveFrom: '2026-10-01T00:00Z',
    effectiveTo: null,
    note: '',
    version: 3,
    adjustments: [
      {
        id: '22222222-2222-2222-2222-222222222222',
        type: 'SITE_DIFFERENTIAL',
        typeLabel: '按站点差异化',
        siteCode: 'US',
        deltaRate: 0.02,
        daysCoverThreshold: null,
        discountRate: null,
        note: '',
      },
    ],
    scopes: [
      {
        id: '33333333-3333-3333-3333-333333333333',
        type: 'BRAND',
        typeLabel: '品牌',
        ref: 'ACME',
        platformCode: null,
      },
    ],
    createdAt: '2026-10-01T00:00Z',
    updatedAt: '2026-10-01T00:00Z',
    ...overrides,
  };
}

function applyResult(overrides: Partial<ApplyResult> = {}): ApplyResult {
  return {
    applicationId: '44444444-4444-4444-4444-444444444444',
    templateId: '11111111-1111-1111-1111-111111111111',
    templateCode: 'OLD-PLUS-30',
    templateName: '老品加价 30%',
    skuId: '55555555-5555-5555-5555-555555555555',
    skuCode: 'SKU-1',
    skuName: '商品一',
    siteCode: 'US',
    platformCode: 'AMAZON',
    currency: 'USD',
    quantity: 1,
    baseMethod: 'COST_PLUS',
    baseMethodLabel: '成本加成',
    effectiveRate: 0.3,
    appliedAdjustments: [
      {
        type: 'SITE_DIFFERENTIAL',
        typeLabel: '按站点差异化',
        siteCode: 'US',
        deltaRate: 0.02,
        daysCoverThreshold: null,
        discountRate: null,
        applied: true,
        reason: '命中站点 US 的差异化规则',
      },
    ],
    suggestedPrice: 27.3,
    decisionReady: true,
    grossMarginRate: 0.080769,
    grossProfit: 2.205,
    calculation: null,
    note: '',
    createdAt: '2026-10-21T00:00Z',
    ...overrides,
  };
}

// ================================================================ null 绝不渲染成 0

describe('null 绝不渲染成 0', () => {
  it('金额取不到时显示兜底文案而不是 0.00', () => {
    expect(moneyText(null)).toBe('未维护');
    expect(moneyText(undefined)).toBe('未维护');
    expect(moneyText(null, '未计入')).toBe('未计入');
    expect(moneyText(0)).toBe('0');
  });

  it('金额 + 币种：取不到时不再补币种', () => {
    expect(moneyWithCurrency(null, 'USD')).toBe('未维护');
    expect(moneyWithCurrency(27.3, 'USD')).toBe('27.3 USD');
    expect(moneyWithCurrency(27.3, null)).toBe('27.3');
  });

  it('毛利率取不到时显示不可信而不是 0%', () => {
    expect(percentText(null)).toBe('不可信（成本不完整）');
    expect(percentText(0.25)).toBe('25%');
    expect(percentText(0)).toBe('0%');
  });

  it('数量取不到时显示破折号', () => {
    expect(quantityText(null)).toBe('—');
    expect(quantityText(2)).toBe('2');
  });
});

// ================================================================ 建议价 ≠ 生效价

describe('建议价 ≠ 生效价', () => {
  it('建议价的统计卡标题写明「不是生效价」', () => {
    const stats = summaryStats(applyResult());
    expect(stats.length).toBeGreaterThan(0);
    expect(stats[0]?.label).toContain('建议价');
    expect(stats[0]?.label).toContain('不是生效价');
  });

  it('后端拒绝给价时显示「不给建议价（成本不完整）」而不是 0', () => {
    const result = applyResult({ suggestedPrice: null, decisionReady: false });
    expect(suggestedPriceText(result)).toBe('不给建议价（成本不完整）');
    const stats = summaryStats(result);
    expect(stats[0]?.tone).toBe('danger');
  });

  it('还没测算时显示待测算', () => {
    expect(suggestedPriceText(null)).toBe('待测算');
  });

  it('决策就绪度的色调：不可信必须是 danger', () => {
    expect(decisionTone(true)).toBe('success');
    expect(decisionTone(false)).toBe('danger');
    expect(decisionTone(null)).toBe('warning');
  });
});

// ================================================================ 基准率口径

describe('基准率的口径随基准方法走', () => {
  it('同一个 0.25 在三种基准下含义不同', () => {
    expect(baseRateText('COST_PLUS', 0.25)).toContain('成本加');
    expect(baseRateText('FIXED_MARGIN', 0.25)).toContain('目标毛利');
    expect(baseRateText('COMPETITOR_FOLLOW', -0.05)).toContain('比竞品低');
    expect(baseRateText('COMPETITOR_FOLLOW', 0.05)).toContain('比竞品高');
  });

  it('基准率没维护时显示未维护而不是 0%', () => {
    expect(baseRateText('FIXED_MARGIN', null)).toBe('未维护');
  });

  it('词表中文名', () => {
    expect(baseMethodLabel('COST_PLUS')).toBe('成本加成');
    expect(baseMethodLabel('FIXED_MARGIN')).toBe('固定毛利');
    expect(baseMethodLabel('COMPETITOR_FOLLOW')).toBe('竞品跟随');
    expect(productStageLabel('NEW')).toBe('新品');
    expect(productStageLabel('EXISTING')).toBe('老品');
    expect(scopeTypeLabel('SHOP')).toBe('店铺');
  });
});

// ================================================================ 缺口分得开

describe('缺口清单分得开', () => {
  const withGaps = applyResult({
    decisionReady: false,
    suggestedPrice: null,
    grossMarginRate: null,
    grossProfit: null,
    calculation: {
      skuId: 'sku',
      currency: 'USD',
      decisionReady: false,
      decisionBasis: 'INCOMPLETE',
      revenue: null,
      knownDeductionTotal: 16,
      grossProfit: null,
      grossMarginRate: null,
      targetMarginPrice: null,
      blockingGaps: [
        {
          code: 'PLATFORM_FEE',
          label: '平台费用',
          ownerModule: '5.2.3.1 平台费用',
          impact: '缺平台佣金时毛利率是虚高的',
          remedy: '手工填入平台费率',
          manualAssumptionAccepted: false,
        },
        {
          code: 'FX',
          label: '汇率',
          ownerModule: '5.2.2.1 汇率查询',
          impact: '汇率是手工假设',
          remedy: '手工填入汇率',
          manualAssumptionAccepted: true,
        },
      ],
      waterfall: [],
    },
  });

  it('真缺与已用手工假设补上的缺口分得开', () => {
    expect(missingGaps(withGaps).map((gap) => gap.label)).toEqual(['平台费用']);
    expect(assumptionGaps(withGaps).map((gap) => gap.label)).toEqual(['汇率']);
  });

  it('没有测算结果时两个清单都是空的', () => {
    expect(missingGaps(null)).toEqual([]);
    expect(assumptionGaps(null)).toEqual([]);
  });

  it('调整项命中与否的色调', () => {
    expect(adjustmentTone(true)).toBe('success');
    expect(adjustmentTone(false)).toBe('neutral');
  });
});

// ================================================================ 表单校验

describe('模板表单校验', () => {
  /** 一份已经合法的模板：后续每条用例只改自己关心的那一项。 */
  const base = (): TemplateInput => ({ ...emptyTemplate(), code: 'OLD-PLUS-30', name: '老品加价 30%' });

  it('编码与名称必填', () => {
    const input = base();
    expect(validateTemplate({ ...input, code: '' })?.field).toBe('code');
    expect(validateTemplate({ ...input, name: '' })?.field).toBe('name');
  });

  it('目标毛利率是小数口径', () => {
    const input = base();
    expect(
      validateTemplate({ ...input, baseMethod: 'FIXED_MARGIN', baseRate: 25 })?.message,
    ).toContain('小数口径');
    expect(
      validateTemplate({ ...input, baseMethod: 'FIXED_MARGIN', baseRate: 0.25 }),
    ).toBeNull();
  });

  it('竞品跟随必须登记竞品基准价', () => {
    const input = base();
    const invalid = validateTemplate({
      ...input,
      baseMethod: 'COMPETITOR_FOLLOW',
      baseRate: -0.05,
      competitorReferencePrice: null,
    });
    expect(invalid?.field).toBe('competitorReferencePrice');
    expect(invalid?.message).toContain('人工登记');
  });

  it('跨币种没有汇率就拒绝提交', () => {
    const input = base();
    const invalid = validateTemplate({ ...input, currency: 'EUR', costCurrency: 'USD' });
    expect(invalid?.field).toBe('fxRate');
    expect(invalid?.message).toContain('绝不按 1:1 折算');
  });

  it('站点差异化必须给站点码与增量', () => {
    const input = base();
    const invalid = validateTemplate({
      ...input,
      adjustments: [
        { type: 'SITE_DIFFERENTIAL', siteCode: '', deltaRate: null, daysCoverThreshold: null, discountRate: null, note: '' },
      ],
    });
    expect(invalid?.field).toBe('adjustments');
  });

  it('库存周转调整必须给阈值与折扣率', () => {
    const input = base();
    const invalid = validateTemplate({
      ...input,
      adjustments: [
        {
          type: 'INVENTORY_TURNOVER',
          siteCode: null,
          deltaRate: null,
          daysCoverThreshold: null,
          discountRate: null,
          note: '',
        },
      ],
    });
    expect(invalid?.field).toBe('adjustments');
  });

  it('适用对象必须给取值', () => {
    const input = base();
    const invalid = validateTemplate({ ...input, scopes: [{ type: 'BRAND', ref: '', platformCode: null }] });
    expect(invalid?.field).toBe('scopes');
  });
});

describe('应用表单校验', () => {
  it('SKU 必填', () => {
    const request = emptyApply();
    expect(validateApply(request, false)?.field).toBe('skuId');
  });

  it('模板配了库存周转调整而没填可售天数 ⇒ 拒绝提交', () => {
    const request = emptyApply();
    request.skuId = 'sku';
    const invalid = validateApply(request, true);
    expect(invalid?.field).toBe('daysOfCover');
    expect(invalid?.message).toContain('可售天数');
    // 没配这条规则时不拦。
    expect(validateApply(request, false)).toBeNull();
  });

  it('跨币种没有汇率就拒绝提交', () => {
    const request = emptyApply();
    request.skuId = 'sku';
    request.currency = 'EUR';
    request.costCurrency = 'USD';
    const invalid = validateApply(request, false);
    expect(invalid?.field).toBe('fxRate');
  });

  it('数量必须大于 0', () => {
    const request = emptyApply();
    request.skuId = 'sku';
    request.quantity = 0;
    expect(validateApply(request, false)?.field).toBe('quantity');
  });
});

// ================================================================ 详情 ↔ 表单

describe('详情与表单互转', () => {
  it('模板详情能还原成表单并保留调整项与适用对象', () => {
    const input = toTemplateInput(template());
    expect(input.code).toBe('OLD-PLUS-30');
    expect(input.baseMethod).toBe('COST_PLUS');
    expect(input.baseRate).toBe(0.3);
    expect(input.adjustments).toHaveLength(1);
    expect(input.adjustments[0]?.siteCode).toBe('US');
    expect(input.scopes).toHaveLength(1);
    expect(input.scopes[0]?.ref).toBe('ACME');
    expect(input.version).toBe(3);
  });

  it('能看出模板上是否配了库存周转调整', () => {
    expect(hasTurnoverRule(template())).toBe(false);
    expect(hasTurnoverRule(null)).toBe(false);
    const withTurnover = template({
      adjustments: [
        {
          id: 'x',
          type: 'INVENTORY_TURNOVER',
          typeLabel: '按库存周转调整',
          siteCode: null,
          deltaRate: null,
          daysCoverThreshold: 90,
          discountRate: 0.1,
          note: '',
        },
      ],
    });
    expect(hasTurnoverRule(withTurnover)).toBe(true);
  });
});
